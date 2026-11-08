"""Control Center feature; runtime dependencies are supplied by the composition root."""
from __future__ import annotations
from typing import Any
from pathlib import Path
import base64
from dataclasses import field
import os
import subprocess

def secret_root_directory(services) -> Path:
    configured = os.environ.get("MINI_ORDER_PLATFORM_SECRETS_DIR")
    if configured:
        return Path(configured).expanduser()
    config_home = os.environ.get("XDG_CONFIG_HOME")
    config_directory = Path(config_home).expanduser() if config_home else Path.home() / ".config"
    return config_directory / "mini-order-platform"

def public_secret_profiles(services) -> dict[str, dict[str, bool]]:
    root = services.secret_root_directory()
    return {
        profile: {
            "key": (root / "keys" / f"{profile}.agekey").is_file(),
            "file": (root / "secrets" / f"{profile}.env.sops").is_file(),
        }
        for profile in sorted(services.SECRET_PROFILES)
    }

def update_profile_secrets(services, profile: str, values: Any) -> list[str]:
    if profile not in services.SECRET_PROFILES:
        raise ValueError("Edycja sekretów jest dostępna wyłącznie dla profilu dev albo test.")
    if not isinstance(values, dict):
        raise ValueError("Nieprawidłowe dane formularza sekretów.")

    unknown = set(values) - services.SECRET_EDIT_NAMES
    if unknown:
        raise ValueError("Formularz zawiera niedozwolone pole sekretu.")

    encoded_lines: list[str] = []
    updated_names: list[str] = []
    for field in services.SECRET_EDIT_FIELDS:
        name = field["name"]
        if name not in values:
            continue
        value = values[name]
        if not isinstance(value, str):
            raise ValueError("Wartości sekretów muszą być tekstem.")
        # An empty field means "leave the existing value unchanged". This
        # prevents an accidental blank submission from deleting a secret.
        if value == "":
            continue
        if len(value) > 4096 or any(character in value for character in ("\x00", "\n", "\r")):
            raise ValueError(f"Wartość pola {name} jest za długa albo zawiera niedozwolone znaki.")
        encoded = base64.b64encode(value.encode("utf-8")).decode("ascii")
        encoded_lines.append(f"{name}\t{encoded}\n")
        updated_names.append(name)

    if not encoded_lines:
        raise ValueError("Wprowadź co najmniej jedną nową wartość. Puste pola pozostają bez zmian.")

    command = ["bash", str(services.CONTROL_SCRIPT), "secrets-update-web", profile]
    environment = os.environ.copy()
    environment["TERM"] = "dumb"
    try:
        with services.SECRET_UPDATE_LOCK:
            result = subprocess.run(
                command,
                cwd=services.PROJECT_ROOT,
                input="".join(encoded_lines),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                text=True,
                env=environment,
                timeout=60,
                check=False,
            )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError("Nie udało się uruchomić bezpiecznej aktualizacji sekretów.") from error
    if result.returncode != 0:
        raise RuntimeError("Nie udało się zapisać sekretów. Sprawdź SOPS, age, klucz profilu i plik sekretów.")
    return updated_names

def setup_profile_secrets(services, profile: str, values: Any) -> list[str]:
    if profile not in services.SECRET_PROFILES:
        raise ValueError("Utworzenie sekretów jest dostępne wyłącznie dla profilu dev albo test.")
    if not isinstance(values, dict):
        raise ValueError("Nieprawidłowe dane formularza pierwszej konfiguracji.")

    unknown = set(values) - services.SECRET_SETUP_NAMES
    if unknown:
        raise ValueError("Formularz zawiera niedozwolone pole sekretu.")

    normalized: dict[str, str] = {}
    for field in services.SECRET_SETUP_FIELDS:
        name = field["name"]
        value = values.get(name, field.get("default", ""))
        if not isinstance(value, str):
            raise ValueError("Wartości sekretów muszą być tekstem.")
        if len(value) > 4096 or any(character in value for character in ("\x00", "\n", "\r")):
            raise ValueError(f"Wartość pola {name} jest za długa albo zawiera niedozwolone znaki.")
        if field.get("required") and value == "":
            raise ValueError(f"Pole „{field['label']}” jest wymagane.")
        if field.get("type") == "select" and value not in field.get("options", []):
            raise ValueError(f"Nieprawidłowa wartość pola „{field['label']}”.")
        normalized[name] = value

    try:
        decoded_jwt = base64.b64decode(normalized["CUSTOMER_JWT_SECRET"], validate=True)
    except (ValueError, base64.binascii.Error) as error:
        raise ValueError("Legacy JWT secret musi być poprawnym tekstem Base64.") from error
    if len(decoded_jwt) < 32:
        raise ValueError("Legacy JWT secret musi mieć minimum 32 bajty po dekodowaniu Base64.")

    encoded_lines = []
    for name, value in normalized.items():
        if value == "":
            continue
        encoded = base64.b64encode(value.encode("utf-8")).decode("ascii")
        encoded_lines.append(f"{name}\t{encoded}\n")

    command = ["bash", str(services.CONTROL_SCRIPT), "secrets-setup-web", profile]
    environment = os.environ.copy()
    environment["TERM"] = "dumb"
    try:
        with services.SECRET_UPDATE_LOCK:
            result = subprocess.run(
                command,
                cwd=services.PROJECT_ROOT,
                input="".join(encoded_lines),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                text=True,
                env=environment,
                timeout=60,
                check=False,
            )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError("Nie udało się uruchomić kreatora pierwszej konfiguracji sekretów.") from error
    if result.returncode != 0:
        raise RuntimeError("Nie udało się utworzyć sekretów. Sprawdź SOPS, age oraz status profilu.")
    return [field["name"] for field in services.SECRET_SETUP_FIELDS if normalized.get(field["name"], "") != ""]
