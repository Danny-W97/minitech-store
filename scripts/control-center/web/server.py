#!/usr/bin/env python3
"""Local-only web interface for Mini Order Platform Control Center.

The server deliberately has no external dependencies. It binds to loopback,
executes only allow-listed Control Center commands and keeps runtime output in
gitIgnoreOwn. Interactive operations remain available through a PTY session.
"""

from __future__ import annotations

import argparse
import base64
import html
import json
import os
import re
import secrets
import shutil
import signal
import smtplib
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import date
from email.mime.application import MIMEApplication
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

try:
    import pty
    import select
except (ImportError, ModuleNotFoundError):  # Windows syntax/HTTP smoke tests only.
    pty = None  # type: ignore[assignment]
    select = None  # type: ignore[assignment]


WEB_ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = WEB_ROOT.parents[2]
CONTROL_SCRIPT = PROJECT_ROOT / "scripts" / "control-center" / "control-center.sh"
RUNTIME_ROOT = PROJECT_ROOT / "gitIgnoreOwn" / "runtime" / "control-center-web"
JOB_ROOT = RUNTIME_ROOT / "jobs"
ACTIVE_PROFILE_FILE = PROJECT_ROOT / "gitIgnoreOwn" / "runtime" / "active-profile"
LANGUAGE_FILE = PROJECT_ROOT / "gitIgnoreOwn" / "runtime" / "language"
DISPATCH_HISTORY_FILE = RUNTIME_ROOT / "dispatch-history.json"
GATEWAY_API = "http://127.0.0.1:18080/api"
BILLING_DIRECT_API = "http://127.0.0.1:18095"
ANSI_RE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
MAX_BODY = 1_048_576

SERVICES = [
    ("gateway", "API Gateway", 18080),
    ("product", "Product", 18081),
    ("order", "Order", 18082),
    ("inventory", "Inventory", 18083),
    ("payment", "Payment", 18084),
    ("notification", "Notification", 18086),
    ("customer", "Customer", 18087),
    ("delivery", "Delivery", 18088),
    ("promotion", "Promotion", 18089),
    ("wishlist", "Wishlist", 18090),
    ("review", "Review", 18091),
    ("recommendation", "Recommendation", 18092),
    ("chat", "Chat", 18093),
    ("returns", "Returns", 18094),
    ("billing", "Billing", 18095),
]

SERVICE_OPTIONS = [item[0] for item in SERVICES]
DB_OPTIONS = SERVICE_OPTIONS[1:]

SECRET_EDIT_FIELDS = [
    {"name": "PRODUCT_DB_PASSWORD", "label": "Product DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "ORDER_DB_PASSWORD", "label": "Order DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "INVENTORY_DB_PASSWORD", "label": "Inventory DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "PAYMENT_DB_PASSWORD", "label": "Payment DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "NOTIFICATION_DB_PASSWORD", "label": "Notification DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "CUSTOMER_DB_PASSWORD", "label": "Customer DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "DELIVERY_DB_PASSWORD", "label": "Delivery DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "PROMOTION_DB_PASSWORD", "label": "Promotion DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "WISHLIST_DB_PASSWORD", "label": "Wishlist DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "REVIEW_DB_PASSWORD", "label": "Review DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "RECOMMENDATION_DB_PASSWORD", "label": "Recommendation DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "CHAT_DB_PASSWORD", "label": "Chat DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "RETURNS_DB_PASSWORD", "label": "Returns DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "BILLING_DB_PASSWORD", "label": "Billing DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "GRAFANA_ADMIN_PASSWORD", "label": "Grafana — hasło administratora", "type": "password", "section": "Usługi lokalne"},
    {"name": "GRAFANA_ADMIN_USER", "label": "Grafana — użytkownik administratora", "type": "text", "section": "Usługi lokalne"},
    {"name": "MAIL_HOST", "label": "SMTP — host", "type": "text", "section": "Mailpit / SMTP"},
    {"name": "MAIL_PORT", "label": "SMTP — port", "type": "text", "section": "Mailpit / SMTP"},
    {"name": "MAIL_USERNAME", "label": "SMTP — użytkownik", "type": "text", "section": "Mailpit / SMTP"},
    {"name": "MAIL_PASSWORD", "label": "SMTP — hasło", "type": "password", "section": "Mailpit / SMTP"},
    {"name": "MAIL_FROM", "label": "SMTP — adres nadawcy", "type": "email", "section": "Mailpit / SMTP"},
    {"name": "MAIL_SMTP_AUTH", "label": "SMTP — uwierzytelnianie", "type": "select", "options": ["false", "true"], "section": "Mailpit / SMTP"},
    {"name": "MAIL_SMTP_STARTTLS_ENABLE", "label": "SMTP — STARTTLS", "type": "select", "options": ["false", "true"], "section": "Mailpit / SMTP"},
    {"name": "CHAT_SUPPORT_EMAIL", "label": "Czat — adres wsparcia", "type": "email", "section": "Kontakt"},
    {"name": "CUSTOMER_JWT_SECRET", "label": "Legacy JWT secret", "type": "password", "section": "Zaawansowane", "note": "Zmiana może unieważnić tokeny w trybie legacy. Rotację kluczy RSA wykonuj osobną operacją JWT."},
]
SECRET_EDIT_NAMES = {field["name"] for field in SECRET_EDIT_FIELDS}
SECRET_SETUP_DB_FIELDS = [
    ("PRODUCT_DB_PASSWORD", "Product DB — hasło"),
    ("ORDER_DB_PASSWORD", "Order DB — hasło"),
    ("INVENTORY_DB_PASSWORD", "Inventory DB — hasło"),
    ("PAYMENT_DB_PASSWORD", "Payment DB — hasło"),
    ("NOTIFICATION_DB_PASSWORD", "Notification DB — hasło"),
    ("CUSTOMER_DB_PASSWORD", "Customer DB — hasło"),
    ("DELIVERY_DB_PASSWORD", "Delivery DB — hasło"),
    ("PROMOTION_DB_PASSWORD", "Promotion DB — hasło"),
    ("WISHLIST_DB_PASSWORD", "Wishlist DB — hasło"),
    ("REVIEW_DB_PASSWORD", "Review DB — hasło"),
    ("RECOMMENDATION_DB_PASSWORD", "Recommendation DB — hasło"),
    ("CHAT_DB_PASSWORD", "Chat DB — hasło"),
    ("RETURNS_DB_PASSWORD", "Returns DB — hasło"),
    ("BILLING_DB_PASSWORD", "Billing DB — hasło"),
]
SECRET_SETUP_FIELDS = [
    *({"name": name, "label": label, "type": "password", "section": "Bazy danych", "required": True} for name, label in SECRET_SETUP_DB_FIELDS),
    {"name": "CUSTOMER_JWT_SECRET", "label": "Legacy JWT secret (Base64)", "type": "password", "section": "Bezpieczeństwo", "required": True, "note": "Minimum 32 bajty po dekodowaniu Base64."},
    {"name": "GRAFANA_ADMIN_PASSWORD", "label": "Grafana — hasło administratora", "type": "password", "section": "Usługi lokalne", "required": True},
    {"name": "GRAFANA_ADMIN_USER", "label": "Grafana — użytkownik administratora", "type": "text", "section": "Usługi lokalne", "default": "admin"},
    {"name": "MAIL_HOST", "label": "SMTP — host", "type": "text", "section": "Mailpit / SMTP", "default": "127.0.0.1"},
    {"name": "MAIL_PORT", "label": "SMTP — port", "type": "text", "section": "Mailpit / SMTP", "default": "11025"},
    {"name": "MAIL_USERNAME", "label": "SMTP — użytkownik", "type": "text", "section": "Mailpit / SMTP"},
    {"name": "MAIL_PASSWORD", "label": "SMTP — hasło", "type": "password", "section": "Mailpit / SMTP"},
    {"name": "MAIL_FROM", "label": "SMTP — adres nadawcy", "type": "email", "section": "Mailpit / SMTP"},
    {"name": "MAIL_SMTP_AUTH", "label": "SMTP — uwierzytelnianie", "type": "select", "options": ["false", "true"], "section": "Mailpit / SMTP", "default": "false"},
    {"name": "MAIL_SMTP_STARTTLS_ENABLE", "label": "SMTP — STARTTLS", "type": "select", "options": ["false", "true"], "section": "Mailpit / SMTP", "default": "false"},
    {"name": "CHAT_SUPPORT_EMAIL", "label": "Czat — adres wsparcia", "type": "email", "section": "Kontakt"},
]
SECRET_SETUP_NAMES = {field["name"] for field in SECRET_SETUP_FIELDS}
SECRET_SETUP_REQUIRED_NAMES = {field["name"] for field in SECRET_SETUP_FIELDS if field.get("required")}
SECRET_PROFILES = {"dev", "test"}
SECRET_UPDATE_LOCK = threading.Lock()


def f(name: str, label: str, kind: str = "text", **extra: Any) -> dict[str, Any]:
    value = {"name": name, "label": label, "type": kind}
    value.update(extra)
    return value


def action(
    action_id: str,
    title: str,
    group: str,
    description: str,
    command: str,
    fields: list[dict[str, Any]] | None = None,
    *,
    danger: bool = False,
    stdin_fields: list[str] | None = None,
    secret_profile: str | None = None,
    secret_mode: str = "secret-edit",
    external_url: str | None = None,
) -> dict[str, Any]:
    value = {
        "id": action_id,
        "title": title,
        "group": group,
        "description": description,
        "command": command,
        "fields": fields or [],
        "danger": danger,
        "stdinFields": stdin_fields or [],
    }
    if secret_profile:
        value["kind"] = secret_mode
        value["profile"] = secret_profile
    if external_url:
        value["externalUrl"] = external_url
    return value


def build_actions(lang: str = "pl") -> list[dict[str, Any]]:
    is_en = lang == "en"
    profile_label = "Profile" if is_en else "Profil"
    service_label = "Service" if is_en else "Serwis"
    database_label = "Database / service" if is_en else "Baza / serwis"
    limit_label = "Limit"

    profile = f("profile", profile_label, "select", options=["dev", "test"], default="dev")
    service = f("service", service_label, "select", options=SERVICE_OPTIONS, default="product")
    database = f("database", database_label, "select", options=["all", *DB_OPTIONS], default="all")
    limit = f("limit", limit_label, "number", default=50, min=1, max=1000)

    grp_diag = "Startup & diagnostics" if is_en else "Start i diagnostyka"
    grp_sec_prof = "Profiles & secrets" if is_en else "Profile i sekrety"
    grp_settings = "Settings" if is_en else "Ustawienia"
    grp_security = "Security" if is_en else "Bezpieczeństwo"
    grp_infra = "Infrastructure" if is_en else "Infrastruktura"
    grp_db = "Databases" if is_en else "Bazy danych"
    grp_backup = "Backups" if is_en else "Backupy"
    grp_demo = "Demo data" if is_en else "Dane demonstracyjne"
    grp_apps = "Applications" if is_en else "Aplikacje"
    grp_frontend = "Frontend" if is_en else "Frontend"
    grp_events = "Redis & events" if is_en else "Redis i zdarzenia"
    grp_monitoring = "Monitoring & DLQ" if is_en else "Monitoring i DLQ"
    grp_mail = "Mailpit" if is_en else "Mailpit"
    grp_store = "Store operations" if is_en else "Operacje sklepu"
    grp_tests = "Tests" if is_en else "Testy"
    grp_build = "Building" if is_en else "Budowanie"
    grp_docs = "API documentation" if is_en else "Dokumentacja API"
    grp_prod = "Prod — validation only" if is_en else "Prod — tylko walidacja"

    result = [
        action("fresh-start", "1-Click Fresh Start" if is_en else "🚀 Pełny start (1-Click)", grp_apps, "Initializes JWT keys, starts databases and starts 15 services in Docker." if is_en else "Inicjalizuje klucze JWT, bazy danych i uruchamia 15 mikroserwisów w Dockerze.", "fresh-start"),
        action("seed-demo-data", "Seed Demo Data" if is_en else "📦 Zasil danymi demo", grp_demo, "Populates store with demo orders, customer chats, reviews, and promotion codes." if is_en else "Wypełnia sklep przykładowymi zamówieniami, czatami, opiniami i kodami rabatowymi.", "seed-demo-data"),
        action("doctor-fix", "Auto-Healing Doctor" if is_en else "🚑 Doktor i autonaprawa", grp_diag, "Audits environment and automatically fixes missing databases, keys, and stopped containers." if is_en else "Przeprowadza audyt środowiska i automatycznie naprawia brakujące bazy, klucze i serwisy.", "doctor-fix"),
        action("environment-check", "Preflight check" if is_en else "Kontrola przed startem", grp_diag, "Checks WSL, JDK, Docker, secrets, and project files." if is_en else "Sprawdza WSL, JDK, Docker, sekrety i pliki projektu.", "environment-check"),
        action("doctor", "Environment Doctor" if is_en else "Doctor środowiska", grp_diag, "Full diagnostics of the running environment." if is_en else "Pełna diagnostyka działającego środowiska.", "doctor"),
        action("github-check", "Pre-GitHub check" if is_en else "Kontrola przed GitHub", grp_diag, "Checks for secrets and private artifacts before publishing." if is_en else "Szuka sekretów i prywatnych artefaktów przed publikacją.", "github-check"),
        action("platform-info", "Platform info" if is_en else "Informacje o platformie", grp_diag, "Shows detected OS and platform runtime adapter." if is_en else "Pokazuje wykryty system i adapter uruchomieniowy.", "platform-info"),
        action("profile-show", "Active profile" if is_en else "Aktywny profil", grp_sec_prof, "Shows dev/test profile currently used by Control Center." if is_en else "Pokazuje profil dev/test używany przez Centralę.", "profile-show"),
        action("profile-set", "Change profile" if is_en else "Zmień profil", grp_sec_prof, "Sets active local profile." if is_en else "Ustawia aktywny profil lokalny.", "profile-set", [profile]),
        action("language-show", "Terminal menu language" if is_en else "Język menu terminalowego", grp_settings, "Shows current language of classic Control Center menu." if is_en else "Pokazuje język klasycznego menu Centrali.", "language-show"),
        action("language-set", "Change menu language" if is_en else "Zmień język menu terminalowego", grp_settings, "Sets Polish or English language for Control Center." if is_en else "Ustawia polski albo angielski język klasycznego menu Centrali.", "language-set", [f("language", "Language" if is_en else "Język", "select", options=["pl", "en"], default="pl")]),
        action("secrets-status", "Vault status" if is_en else "Status skarbca", grp_sec_prof, "Checks SOPS, age, keys, and encrypted files without exposing values." if is_en else "Sprawdza SOPS, age, klucze i zaszyfrowane pliki bez ujawniania wartości.", "secrets-status"),
        action("secrets-validate", "Validate secrets" if is_en else "Waliduj sekrety", grp_sec_prof, "Validates required secret keys." if is_en else "Sprawdza komplet wymaganych nazw sekretów.", "secrets-validate", [profile]),
        action("secrets-init", "Initialize age keys" if is_en else "Inicjalizuj klucze age", grp_sec_prof, "Creates missing age keys for dev/test profiles." if is_en else "Tworzy brakujące klucze profili dev/test.", "secrets-init", danger=True),
        action("secrets-setup-dev", "Create DEV secrets" if is_en else "Utwórz sekrety DEV", grp_sec_prof, "Creates initial encrypted secrets file for dev profile." if is_en else "Tworzy pierwszy zaszyfrowany plik sekretów profilu dev bez logowania wartości.", "secrets-setup-web", danger=True, secret_profile="dev", secret_mode="secret-setup"),
        action("secrets-setup-test", "Create TEST secrets" if is_en else "Utwórz sekrety TEST", grp_sec_prof, "Creates initial encrypted secrets file for test profile." if is_en else "Tworzy pierwszy zaszyfrowany plik sekretów profilu test bez logowania wartości.", "secrets-setup-web", danger=True, secret_profile="test", secret_mode="secret-setup"),
        action("secrets-ensure-auth", "Generate JWT secret" if is_en else "Uzupełnij sekret JWT", grp_sec_prof, "Generates missing JWT keys without displaying them." if is_en else "Generuje brakujący materiał JWT bez jego wyświetlania.", "secrets-ensure-auth", [profile], danger=True),
        action("secrets-edit-dev", "Edit DEV secrets" if is_en else "Edytuj sekrety DEV", grp_sec_prof, "Opens secure secret editor for dev profile; values remain hidden." if is_en else "Otwiera bezpieczny formularz aktualizacji sekretów profilu dev; wartości pozostają ukryte.", "secrets-update-web", danger=True, secret_profile="dev"),
        action("secrets-edit-test", "Edit TEST secrets" if is_en else "Edytuj sekrety TEST", grp_sec_prof, "Opens secure secret editor for test profile; values remain hidden." if is_en else "Otwiera bezpieczny formularz aktualizacji sekretów profilu test; wartości pozostają ukryte.", "secrets-update-web", danger=True, secret_profile="test"),
        action("jwt-status", "JWT status" if is_en else "Status JWT", grp_security, "Shows JWT configuration without exposing tokens or private keys." if is_en else "Pokazuje konfigurację JWT bez tokenów i kluczy prywatnych.", "jwt-status"),
        action("jwt-rotate", "Rotate JWT keys" if is_en else "Rotuj klucze JWT", grp_security, "Generates a new RSA key and retains previous public key." if is_en else "Generuje nowy klucz RSA i zachowuje poprzedni klucz publiczny.", "jwt-rotate", [profile], danger=True),
        action("jwt-smoke", "JWT smoke" if is_en else "Smoke JWT", grp_security, "Tests login, /me, refresh, and logout." if is_en else "Sprawdza login, /me, refresh i logout.", "jwt-smoke", [f("email", "Email (optional)" if is_en else "E-mail (opcjonalnie)", required=False)]),
        action("audit-list", "Audit log" if is_en else "Dziennik audytowy", grp_security, "Shows security audit events from Customer Service." if is_en else "Pokazuje zdarzenia bezpieczeństwa Customer Service.", "audit-list", [limit]),
        action("password-reset-request", "Password reset" if is_en else "Reset hasła", grp_security, "Sends password reset link to Mailpit." if is_en else "Wysyła link resetu hasła do Mailpit.", "password-reset-request", [f("email", "Email" if is_en else "E-mail", "email", required=True)]),
        action("infra-up", "Start infrastructure" if is_en else "Uruchom infrastrukturę", grp_infra, "Starts databases, Redis, and Mailpit with active secrets." if is_en else "Uruchamia bazy, Redis i Mailpit z aktywnymi sekretami.", "infra-up"),
        action("infra-down", "Stop infrastructure" if is_en else "Zatrzymaj infrastrukturę", grp_infra, "Stops local infrastructure containers." if is_en else "Zatrzymuje lokalne kontenery infrastruktury.", "infra-down", danger=True),
        action("infra-status", "Docker Compose status" if is_en else "Status Docker Compose", grp_infra, "Shows all project containers." if is_en else "Pokazuje wszystkie kontenery projektu.", "infra-status"),
        action("infra-logs", "Infrastructure logs" if is_en else "Logi infrastruktury", grp_infra, "Shows recent logs for all infrastructure containers." if is_en else "Pokazuje ostatnie logi wszystkich kontenerów infrastruktury.", "infra-logs"),
        action("db-list", "Databases & ports map" if is_en else "Mapa baz i portów", grp_db, "Shows PostgreSQL database owners, names, and ports." if is_en else "Pokazuje właścicieli, nazwy i porty baz PostgreSQL.", "db-list"),
        action("db-up", "Start database(s)" if is_en else "Uruchom bazę/bazy", grp_db, "Starts selected database or all databases." if is_en else "Uruchamia wskazaną bazę lub wszystkie bazy.", "db-up", [database]),
        action("db-down", "Stop database(s)" if is_en else "Zatrzymaj bazę/bazy", grp_db, "Stops selected database or all databases." if is_en else "Zatrzymuje wskazaną bazę lub wszystkie bazy.", "db-down", [database], danger=True),
        action("db-status", "Database status" if is_en else "Status baz", grp_db, "Shows status of PostgreSQL containers." if is_en else "Pokazuje stan kontenerów PostgreSQL.", "db-status", [database]),
        action("db-logs", "Database logs" if is_en else "Logi bazy", grp_db, "Shows recent logs for selected database." if is_en else "Pokazuje ostatnie logi wybranej bazy.", "db-logs", [database]),
        action("db-backup", "Create backup" if is_en else "Wykonaj backup", grp_backup, "Creates encrypted backup of one or all databases." if is_en else "Tworzy szyfrowany backup jednej lub wszystkich baz.", "db-backup", [database]),
        action("db-backup-list", "Backup list" if is_en else "Lista backupów", grp_backup, "Shows available backups for active profile." if is_en else "Pokazuje dostępne backupy aktywnego profilu.", "db-backup-list", [database]),
        action("db-restore-test", "Restore test" if is_en else "Test odtworzenia", grp_backup, "Restores backup to test database and verifies result." if is_en else "Odtwarza backup do bazy testowej i weryfikuje wynik.", "db-restore-test", [database], danger=True),
        action("db-backup-prune", "Prune backups" if is_en else "Retencja backupów", grp_backup, "Removes backups older than specified number of days." if is_en else "Usuwa backupy starsze niż podana liczba dni.", "db-backup-prune", [f("days", "Days" if is_en else "Liczba dni", "number", default=14, min=1)], danger=True),
        action("backup-key-init", "Backup key init" if is_en else "Klucz backupów", grp_backup, "Generates age key for encrypting backups." if is_en else "Przygotowuje klucz age do szyfrowania kopii.", "backup-key-init", [profile], danger=True),
        action("backup-schedule-install", "Install backup schedule" if is_en else "Harmonogram backupu", grp_backup, "Installs daily backup schedule in WSL cron." if is_en else "Instaluje codzienny harmonogram w WSL.", "backup-schedule-install", [f("hour", "UTC Hour" if is_en else "Godzina UTC", "number", default=2, min=0, max=23)], danger=True),
        action("backup-schedule-status", "Schedule status" if is_en else "Status harmonogramu", grp_backup, "Shows automatic backup schedule status." if is_en else "Pokazuje konfigurację automatycznych backupów.", "backup-schedule-status"),
        action("backup-schedule-remove", "Remove backup schedule" if is_en else "Usuń harmonogram", grp_backup, "Removes automated backup schedule entry." if is_en else "Usuwa wpis automatycznego backupu.", "backup-schedule-remove", danger=True),
        action("inventory-reset", "Reset demo stock" if is_en else "Reset magazynu demo", grp_demo, "Restores base inventory quantities." if is_en else "Przywraca bazowe ilości magazynowe.", "inventory-reset", danger=True),
        action("docker-apps-up", "Start Docker Apps" if is_en else "Uruchom serwisy (Docker)", grp_apps, "Starts all 15 microservices and frontend in Docker containers." if is_en else "Uruchamia wszystkie 15 mikroserwisów i frontend w kontenerach Docker.", "docker-apps-up"),
        action("docker-apps-down", "Stop Docker Apps" if is_en else "Zatrzymaj serwisy (Docker)", grp_apps, "Stops all application containers." if is_en else "Zatrzymuje kontenery aplikacji i frontendu w Dockerze.", "docker-apps-down", danger=True),
        action("docker-apps-status", "Docker Apps Status" if is_en else "Status kontenerów (Docker)", grp_apps, "Shows status of Docker application containers." if is_en else "Pokazuje stan kontenerów aplikacji i frontendu w Dockerze.", "docker-apps-status"),
        action("app-run", "Start service (Maven)" if is_en else "Uruchom serwis (Maven)", grp_apps, "Starts one microservice via Maven in WSL." if is_en else "Uruchamia jeden mikroserwis przez Maven w WSL.", "app-run", [service, profile]),
        action("app-stop", "Stop service (process)" if is_en else "Zatrzymaj serwis (proces)", grp_apps, "Stops one selected microservice process." if is_en else "Zatrzymuje proces wybranego mikroserwisu.", "app-stop", [service], danger=True),
        action("app-run-all", "Start all services (WSL processes)" if is_en else "Uruchom serwisy (procesy WSL)", grp_apps, "Starts 15 services in background as local WSL processes with active profile." if is_en else "Uruchamia 15 usług w tle jako lokalne procesy WSL (Maven) z aktywnym profilem.", "app-run-all"),
        action("app-stop-all", "Stop all services (WSL processes)" if is_en else "Zatrzymaj serwisy (procesy WSL)", grp_apps, "Stops service processes started by Control Center in WSL." if is_en else "Zatrzymuje procesy usług uruchomione przez Centralę w WSL.", "app-stop-all", danger=True),
        action("app-status-all", "WSL Process status" if is_en else "Status procesów WSL", grp_apps, "Shows PID, port, and status of all local WSL service processes." if is_en else "Pokazuje PID, port i stan procesów usług w WSL.", "app-status-all"),
        action("app-logs", "Service process logs" if is_en else "Logi procesu serwisu", grp_apps, "Shows recent logs for selected local service process." if is_en else "Pokazuje ostatni log procesu wskazanej usługi w WSL.", "app-logs", [service]),
        action("health", "Service health" if is_en else "Dostępność usług", grp_apps, "Checks endpoints of all services." if is_en else "Sprawdza endpointy wszystkich usług.", "health", [f("service", "Service" if is_en else "Serwis", "select", options=["all", *SERVICE_OPTIONS], default="all")]),
        action("frontend-up", "Start storefront" if is_en else "Uruchom sklep", grp_frontend, "Starts local storefront on port 18070." if is_en else "Uruchamia lokalny frontend na porcie 18070.", "frontend-up"),
        action("frontend-down", "Stop storefront" if is_en else "Zatrzymaj sklep", grp_frontend, "Stops local storefront." if is_en else "Zatrzymuje lokalny frontend.", "frontend-down", danger=True),
        action("frontend-status", "Storefront status" if is_en else "Status frontendu", grp_frontend, "Checks storefront process and URL." if is_en else "Sprawdza proces i adres sklepu.", "frontend-status"),
        action("frontend-logs", "Storefront logs" if is_en else "Logi frontendu", grp_frontend, "Shows static server logs." if is_en else "Pokazuje log serwera statycznego.", "frontend-logs"),
        action("broker-up", "Start Redis" if is_en else "Uruchom Redis", grp_events, "Starts Event Broker." if is_en else "Uruchamia Event Broker.", "broker-up"),
        action("broker-down", "Stop Redis" if is_en else "Zatrzymaj Redis", grp_events, "Stops Event Broker." if is_en else "Zatrzymuje Event Broker.", "broker-down", danger=True),
        action("broker-status", "Redis status" if is_en else "Status Redis", grp_events, "Checks container and PING response." if is_en else "Sprawdza kontener i PING.", "broker-status"),
        action("broker-logs", "Redis logs" if is_en else "Logi Redis", grp_events, "Shows broker logs." if is_en else "Pokazuje logi brokera.", "broker-logs"),
        action("stream-info", "Stream info" if is_en else "Informacje o streamie", grp_events, "Shows streams and consumer groups." if is_en else "Pokazuje streamy i grupy konsumentów.", "stream-info"),
        action("stream-read", "Recent events" if is_en else "Ostatnie zdarzenia", grp_events, "Shows recent stream event entries." if is_en else "Pokazuje ostatnie wpisy streamu.", "stream-read", [limit]),
        action("broker-reset-group", "Reset consumer group", grp_events, "Removes consumer group without deleting stream." if is_en else "Usuwa grupę konsumenta bez kasowania streamu.", "broker-reset-group", [f("group", "Group name" if is_en else "Nazwa grupy", default="notification-service")], danger=True),
        action("monitoring-up", "Start monitoring" if is_en else "Uruchom monitoring", grp_monitoring, "Starts Prometheus and Grafana." if is_en else "Uruchamia Prometheus i Grafanę.", "monitoring-up"),
        action("monitoring-down", "Stop monitoring" if is_en else "Zatrzymaj monitoring", grp_monitoring, "Stops Prometheus and Grafana." if is_en else "Zatrzymuje Prometheus i Grafanę.", "monitoring-down", danger=True),
        action("monitoring-status", "Monitoring status" if is_en else "Status monitoringu", grp_monitoring, "Shows monitoring containers and endpoints." if is_en else "Pokazuje kontenery i endpointy monitoringu.", "monitoring-status"),
        action("monitoring-logs", "Monitoring logs" if is_en else "Logi monitoringu", grp_monitoring, "Shows recent Prometheus and Grafana logs." if is_en else "Pokazuje ostatnie logi Prometheusa i Grafany.", "monitoring-logs"),
        action("monitoring-urls", "Monitoring URLs" if is_en else "Adresy monitoringu", grp_monitoring, "Shows Prometheus and Grafana URLs." if is_en else "Pokazuje adresy Prometheus i Grafany.", "monitoring-urls"),
        action("dlq-list", "DLQ entries" if is_en else "Wpisy Dead Letter Queue", grp_monitoring, "Shows dead-letter notifications queue." if is_en else "Pokazuje niewysłane powiadomienia.", "dlq-list"),
        action("dlq-replay", "Replay DLQ entry" if is_en else "Ponów wpis DLQ", grp_monitoring, "Replays sending of specified DLQ entry." if is_en else "Ponawia wysyłkę wskazanego wpisu.", "dlq-replay", [f("id", "Entry ID" if is_en else "ID wpisu", "number", required=True, min=1)], danger=True),
        action("mailpit-up", "Start Mailpit" if is_en else "Uruchom Mailpit", grp_mail, "Starts test SMTP server and mailbox." if is_en else "Uruchamia testowy serwer SMTP i skrzynkę.", "mailpit-up"),
        action("mailpit-down", "Stop Mailpit" if is_en else "Zatrzymaj Mailpit", grp_mail, "Stops Mailpit." if is_en else "Zatrzymuje Mailpit.", "mailpit-down", danger=True),
        action("mailpit-status", "Mailpit status" if is_en else "Status Mailpit", grp_mail, "Checks container and web mailbox." if is_en else "Sprawdza kontener i panel poczty.", "mailpit-status"),
        action("mailpit-logs", "Mailpit logs" if is_en else "Logi Mailpit", grp_mail, "Shows recent test mail server logs." if is_en else "Pokazuje ostatnie logi lokalnego serwera poczty.", "mailpit-logs"),
        action("mailpit-list", "Recent messages" if is_en else "Ostatnie wiadomości", grp_mail, "Shows messages in test mailbox." if is_en else "Pokazuje wiadomości w skrzynce testowej.", "mailpit-list", [limit]),
        action("mailpit-search", "Search messages" if is_en else "Szukaj wiadomości", grp_mail, "Searches messages by Mailpit query." if is_en else "Wyszukuje wiadomości według zapytania Mailpit.", "mailpit-search", [f("query", "Query" if is_en else "Zapytanie", required=True)]),
        action("mailpit-smoke", "Mailpit smoke" if is_en else "Smoke e-mail", grp_mail, "Checks Notification Service → SMTP → Mailpit." if is_en else "Sprawdza Notification Service → SMTP → Mailpit.", "mailpit-smoke"),
        action("mailpit-send", "Send test email" if is_en else "Wyślij e-mail testowy", grp_mail, "Sends custom message via Notification Service." if is_en else "Wysyła własną wiadomość przez Notification Service.", "mailpit-send", [f("recipient", "Recipient" if is_en else "Odbiorca", "email", default="mailpit-test@minitech.local"), f("subject", "Subject" if is_en else "Temat", default="Test z Control Center" if not is_en else "Test message from Control Center"), f("message", "Message" if is_en else "Treść", "textarea", default="Wiadomość testowa z Control Center" if not is_en else "Test message from Control Center")], stdin_fields=["recipient", "subject", "message"]),
        action("mailpit-clear", "Clear Mailpit" if is_en else "Wyczyść Mailpit", grp_mail, "Deletes all messages from test mailbox." if is_en else "Usuwa wszystkie wiadomości z lokalnej skrzynki testowej.", "mailpit-clear", danger=True),
        action("orders-list", "All orders" if is_en else "Wszystkie zamówienia", grp_store, "Shows ID, status, total, email, invoice, and line items." if is_en else "Pokazuje ID, status, kwotę, e-mail, fakturę i pozycje.", "orders-list", [limit]),
        action("promotion-codes", "Promotion codes" if is_en else "Kody rabatowe", grp_store, "Lists codes or checks specific code." if is_en else "Pokazuje kody albo sprawdza konkretny kod.", "promotion-codes", [f("code", "Code (empty = list)" if is_en else "Kod (puste = lista)", required=False)]),
        action("promotion-code-create", "Create promotion code" if is_en else "Utwórz kod rabatowy", grp_store, "Creates promotion rule for dev/test." if is_en else "Tworzy regułę rabatową dla dev/test.", "promotion-code-create", [f("code", "Code (empty = auto)" if is_en else "Kod (puste = automatyczny)"), f("description", "Description" if is_en else "Opis", default="MiniTech Store discount" if is_en else "Kod rabatowy MiniTech Store"), f("discount", "Discount %" if is_en else "Rabat %", "number", default=10, min=1, max=100), f("limit", "Usage limit (empty = unlimited)" if is_en else "Limit użyć (puste = bez limitu)"), f("minimum", "Min order total" if is_en else "Minimalna wartość zamówienia", "number", default=0, min=0), f("maximum", "Max discount amount" if is_en else "Maksymalna kwota rabatu (puste = bez limitu)"), f("from", "Valid from" if is_en else "Ważny od", "date"), f("to", "Valid to" if is_en else "Ważny do", "date", default="2099-12-31"), f("active", "Active" if is_en else "Aktywny", "select", options=["true", "false"], default="true")], stdin_fields=["code", "description", "discount", "limit", "minimum", "maximum", "from", "to", "active"]),
        action("review-pending", "Pending reviews" if is_en else "Opinie do moderacji", grp_store, "Shows pending customer product reviews." if is_en else "Pokazuje oczekujące opinie klientów.", "review-pending"),
        action("review-moderate", "Moderate review" if is_en else "Moderuj opinię", grp_store, "Publishes or rejects a customer review." if is_en else "Publikuje albo odrzuca opinię.", "review-moderate", [f("id", "Review ID" if is_en else "ID opinii", "number", required=True, min=1), f("status", "Status", "select", options=["PUBLISHED", "REJECTED"], default="PUBLISHED"), f("note", "Note" if is_en else "Notatka", "textarea")], danger=True),
        action("delivery-tracking", "Delivery tracking" if is_en else "Śledzenie dostawy", grp_store, "Shows carrier, tracking number, and package status." if is_en else "Pokazuje przewoźnika, numer i status przesyłki.", "delivery-tracking", [f("order", "Order ID" if is_en else "ID zamówienia", "number", required=True, min=1)]),
        action("delivery-tracking-status", "Update delivery status" if is_en else "Zmień status dostawy", grp_store, "Updates package delivery status in dev/test." if is_en else "Zmienia status przesyłki w profilu dev/test.", "delivery-tracking-status", [f("order", "Order ID" if is_en else "ID zamówienia", "number", required=True, min=1), f("status", "Status", "select", options=["LABEL_CREATED", "READY_FOR_PICKUP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "PICKED_UP", "CANCELLED"])], danger=True),
        action("returns-list", "Returns & complaints" if is_en else "Zwroty i reklamacje", grp_store, "Shows customer return and complaint requests." if is_en else "Pokazuje zgłoszenia klientów.", "returns-list"),
        action("returns-status", "Update return status" if is_en else "Zmień status zgłoszenia", grp_store, "Updates return or complaint status." if is_en else "Aktualizuje zwrot lub reklamację.", "returns-status", [f("id", "Ticket ID" if is_en else "ID zgłoszenia", "number", required=True, min=1), f("status", "Status", "select", options=["UNDER_REVIEW", "APPROVED", "REJECTED", "COMPLETED"]), f("note", "Note" if is_en else "Notatka", "textarea")], danger=True),
        action("invoices-list", "Invoices list" if is_en else "Lista faktur", grp_store, "Shows issued VAT invoices from Billing Service." if is_en else "Pokazuje listę wystawionych faktur VAT.", "invoices-list", [limit]),
        action("invoices-export-zip", "Export invoices ZIP" if is_en else "Eksportuj faktury ZIP", grp_store, "Downloads ZIP archive containing all invoice PDFs." if is_en else "Pobiera archiwum ZIP ze wszystkimi fakturami PDF.", "invoices-export-zip"),
        action("invoice-correct", "Issue correction invoice" if is_en else "Wystaw fakturę korygującą", grp_store, "Issues a credit note / correction invoice for an existing invoice." if is_en else "Wystawia fakturę korygującą (KOR) dla wskazanej faktury.", "invoice-correct", [f("invoice", "Invoice ID" if is_en else "ID faktury", "number", required=True, min=1), f("reason", "Reason" if is_en else "Przyczyna korekty", "text", required=True), f("delta", "Gross change (e.g. -129.99)" if is_en else "Kwota zmiany brutto (np. -129.99)", "number", required=True)]),
        action("corrections-list", "Corrections list" if is_en else "Lista korekt faktur", grp_store, "Lists issued correction invoices." if is_en else "Pokazuje listę wystawionych faktur korygujących.", "corrections-list"),
        action("invoice-show", "Show invoice" if is_en else "Pokaż fakturę", grp_store, "Fetches invoice data for an order." if is_en else "Pobiera dane faktury dla zamówienia.", "invoice-show", [f("order", "Order ID" if is_en else "ID zamówienia", "number", required=True, min=1)]),
        action("invoice-pdf", "Download invoice PDF" if is_en else "Pobierz PDF faktury", grp_store, "Saves invoice PDF in private runtime directory." if is_en else "Zapisuje PDF w prywatnym katalogu runtime.", "invoice-pdf", [f("order", "Order ID" if is_en else "ID zamówienia", "number", required=True, min=1)]),
    ]

    tests = [
        ("smoke", "API Gateway Smoke" if is_en else "Smoke API Gateway", "Quickly verifies catalogue via public Gateway entrypoint." if is_en else "Szybko sprawdza katalog przez publiczny punkt wejścia."),
        ("app-verify", "App Verify", "Tests purchase, payment, stock reservation, and email." if is_en else "Sprawdza zakup, płatność, magazyn i e-mail."),
        ("verify-mvp", "Full MVP Verification" if is_en else "Pełna weryfikacja MVP", "Runs comprehensive end-to-end verification of MVP flow." if is_en else "Uruchamia rozszerzoną weryfikację kompletnego przepływu MVP."),
        ("delivery-smoke", "Delivery Service Smoke" if is_en else "Smoke Delivery Service", "Tests delivery quote, schedule, and tracking." if is_en else "Sprawdza wycenę, termin i śledzenie dostawy."),
        ("promotion-smoke", "Promotion Service Smoke" if is_en else "Smoke Promotion Service", "Tests active promotions and discount rules." if is_en else "Sprawdza aktywne promocje i reguły rabatowe."),
        ("contract-test", "API v1 Contract Test" if is_en else "Contract test API v1", "Validates public Gateway OpenAPI against v1 schema contract." if is_en else "Porównuje publiczny kontrakt z oczekiwanym schematem."),
        ("security-test", "API Security Test" if is_en else "Test bezpieczeństwa API", "Validates endpoint boundaries, JWT restrictions, and actuator safety." if is_en else "Sprawdza podstawowe zabezpieczenia endpointów."),
        ("zap-dast", "OWASP ZAP DAST", "Runs dynamic vulnerability scanner against API Gateway." if is_en else "Uruchamia skaner dynamiczny API Gateway."),
        ("load-test", "Load Test" if is_en else "Test obciążeniowy", "Sends concurrent HTTP requests to local catalogue API." if is_en else "Wysyła kontrolowany ruch do lokalnego API."),
        ("e2e", "Full E2E API Test" if is_en else "Pełny E2E API", "Full end-to-end flow: Registration, JWT, order, payment, and Mailpit." if is_en else "Rejestracja, JWT, zakup, płatność i Mailpit."),
        ("ui-test-status", "Playwright Status" if is_en else "Status Playwright", "Checks Node.js runtime, packages, and browser binaries." if is_en else "Sprawdza Node, pakiety i przeglądarki."),
        ("ui-test-install", "Install Playwright" if is_en else "Instalacja Playwright", "Installs test dependencies and Chromium browser." if is_en else "Instaluje zależności testów i Chromium."),
        ("ui-test-smoke", "Playwright Smoke", "Checks critical storefront screens in Chromium." if is_en else "Sprawdza krytyczne ekrany sklepu."),
        ("ui-test", "Chromium UI Regression" if is_en else "Regresja UI Chromium", "Runs full UI test suite with mocked API." if is_en else "Uruchamia pełny zestaw UI z mock API."),
        ("ui-test-system", "Full System UI Checkout" if is_en else "Systemowy checkout UI", "Tests frontend checkout with real live microservices." if is_en else "Testuje frontend z prawdziwymi usługami."),
        ("ui-test-browsers", "Multi-Browser UI Test" if is_en else "UI wiele przeglądarek", "Runs regression across Chromium, Firefox, WebKit, and mobile." if is_en else "Chromium, Firefox, WebKit i mobile."),
        ("ui-test-headed", "Headed UI Test" if is_en else "UI headed", "Runs browser tests with visible window." if is_en else "Uruchamia test z widoczną przeglądarką."),
        ("ui-test-debug", "Playwright Inspector", "Opens interactive Playwright step-by-step inspector (requires Resume / F8)." if is_en else "Otwiera interaktywny debugger krok po kroku (wymaga Resume / F8)."),
        ("ui-test-report", "Playwright Report" if is_en else "Raport Playwright", "Serves generated HTML test report." if is_en else "Otwiera wygenerowany raport HTML."),
    ]
    result.extend(action(command, title, grp_tests, description, command) for command, title, description in tests)
    result.extend([
        action("resilience-test", "Chaos / Resilience Test" if is_en else "Chaos / Test odporności", grp_tests, "Simulates failure of selected microservice and verifies fault isolation." if is_en else "Symuluje awarię wybranego mikroserwisu i sprawdza izolację awarii oraz degradację.", "resilience-test", [f("service", "Selected microservice" if is_en else "Wybrany mikroserwis", "select", options=["payment-service", "inventory-service", "product-service", "order-service", "customer-service", "delivery-service", "promotion-service", "notification-service", "chat-service", "returns-service", "billing-service"], default="payment-service")]),
        action("app-test", "Maven Tests" if is_en else "Testy Maven", grp_build, "Runs Maven unit/integration tests for selected service or all." if is_en else "Uruchamia testy serwisu albo monorepo.", "app-test", [f("service", "Scope" if is_en else "Zakres", "select", options=["all", *SERVICE_OPTIONS], default="all")]),
        action("app-package", "Maven Package" if is_en else "Pakietowanie Maven", grp_build, "Packages selected service or entire monorepo." if is_en else "Buduje jeden serwis albo całe monorepo.", "app-package", [f("service", "Scope" if is_en else "Zakres", "select", options=["all", *SERVICE_OPTIONS], default="all")]),
        action("images-build", "Docker Images" if is_en else "Obrazy Docker", grp_build, "Builds local Docker images for all microservices." if is_en else "Buduje lokalne obrazy wszystkich aplikacji.", "images-build"),
        action("swagger-ui", "Swagger UI (Główna brama :18080)" if not is_en else "Swagger UI (Gateway :18080)", grp_docs, "Główny zagregowany interfejs Swagger UI z rozwijaną listą wszystkich 15 mikroserwisów. Umożliwia interaktywne testowanie bez CORS." if not is_en else "Main aggregated Swagger UI with dropdown list of all 15 microservices. Interactive testing without CORS.", "swagger-ui", external_url="http://127.0.0.1:18080/swagger-ui.html"),
        action("api-docs-urls", "Wykaz adresów OpenAPI" if not is_en else "OpenAPI Endpoints List", grp_docs, "Shows Swagger UI documentation URLs for all services." if is_en else "Pokazuje adresy dokumentacji usług.", "api-docs-urls"),
        action("resilience-status", "Service Resilience Status" if is_en else "Odporność usług", grp_docs, "Shows Prometheus metrics for Circuit Breaker, Retry, and Bulkhead." if is_en else "Pokazuje Retry, Circuit Breaker i Bulkhead.", "resilience-status"),
        action("prod-status", "Prod Profile Status" if is_en else "Status profilu prod", grp_prod, "Explains why production profile is currently locked." if is_en else "Wyjaśnia, dlaczego prod jest zablokowany.", "prod-status"),
        action("k8s-validate", "Validate Kubernetes" if is_en else "Waliduj Kubernetes", grp_prod, "Validates Kubernetes manifests without deploying." if is_en else "Sprawdza manifesty bez wdrażania.", "k8s-validate"),
        action("k8s-status", "Kubernetes Status" if is_en else "Status Kubernetes", grp_prod, "Shows cluster status if configured." if is_en else "Pokazuje stan klastra, jeśli jest skonfigurowany.", "k8s-status"),
        action("openbao-validate", "Validate OpenBao/TLS" if is_en else "Waliduj OpenBao/TLS", grp_prod, "Validates production OpenBao and TLS preparation files." if is_en else "Sprawdza pliki przygotowania produkcyjnego.", "openbao-validate"),
        action("openbao-status", "OpenBao Status" if is_en else "Status OpenBao", grp_prod, "Checks OpenBao endpoint without exposing secrets." if is_en else "Sprawdza endpoint bez ujawniania sekretów.", "openbao-status"),
        action("tls-status", "TLS Status" if is_en else "Status TLS", grp_prod, "Checks TLS certificate of specified host." if is_en else "Sprawdza certyfikat wskazanego hosta.", "tls-status"),
    ])
    return result


ACTIONS = build_actions("pl")
ACTION_BY_ID = {item["id"]: item for item in ACTIONS}

ERROR_DATABASE_PATH = PROJECT_ROOT / "scripts" / "control-center" / "data" / "error_database.json"


def load_error_database() -> list[dict[str, Any]]:
    if ERROR_DATABASE_PATH.is_file():
        try:
            data = json.loads(ERROR_DATABASE_PATH.read_text(encoding="utf-8"))
            return data.get("errors", [])
        except Exception:
            pass
    return []


def diagnose_job_log(log_text: str) -> dict[str, Any] | None:
    if not log_text:
        return None
    errors = load_error_database()
    for rule in errors:
        for pattern in rule.get("patterns", []):
            try:
                if re.search(pattern, log_text, re.IGNORECASE):
                    return {
                        "matched": True,
                        "errorId": rule.get("id"),
                        "category": rule.get("category", "general"),
                        "title": rule.get("title", {}),
                        "cause": rule.get("cause", {}),
                        "solution": rule.get("solution", {}),
                        "fixAction": rule.get("fix_action"),
                        "fixButtonLabel": rule.get("fix_button_label", {}),
                    }
            except re.error:
                continue
    return {
        "matched": False,
        "errorId": "UNKNOWN_ERROR",
        "category": "unknown",
        "title": {
            "pl": "Nierozpoznany błąd",
            "en": "Unrecognized Error",
        },
        "cause": {
            "pl": "Treść błędu nie została jeszcze skatalogowana w lokalnej bazie wiedzy.",
            "en": "This error output has not been cataloged in the local knowledge base yet.",
        },
        "solution": {
            "pl": "Możesz przekazać treść logu do Asystenta AI w celu automatycznej analizy lub dodać regułę do docs/ERROR_KNOWLEDGE_BASE.md.",
            "en": "You can ask the AI Assistant to analyze this log or add a new rule to docs/ERROR_KNOWLEDGE_BASE.md.",
        },
        "fixAction": None,
        "fixButtonLabel": None,
        "canAskAi": True,
    }


@dataclass
class Job:
    id: str
    action_id: str
    title: str
    command: list[str]
    log_path: Path
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    status: str = "queued"
    exit_code: int | None = None

    def public(self, include_output: bool = False, lang: str | None = None) -> dict[str, Any]:
        target_lang = lang or active_language()
        title = self.title
        if self.action_id:
            try:
                for act in build_actions(target_lang):
                    if act.get("id") == self.action_id:
                        title = act.get("title") or title
                        break
            except Exception:
                pass
        value = {
            "id": self.id,
            "actionId": self.action_id,
            "title": title,
            "command": " ".join(self.command[2:]),
            "createdAt": self.created_at,
            "startedAt": self.started_at,
            "finishedAt": self.finished_at,
            "status": self.status,
            "exitCode": self.exit_code,
            "diagnosis": None,
        }
        raw_log = ""
        try:
            raw_log = ANSI_RE.sub("", self.log_path.read_text(encoding="utf-8", errors="replace"))
        except FileNotFoundError:
            pass

        if self.status == "failed" and raw_log:
            value["diagnosis"] = diagnose_job_log(raw_log)

        if include_output:
            value["output"] = raw_log[-120_000:]
        return value


@dataclass
class TerminalSession:
    id: str
    pid: int
    fd: int
    output: bytearray = field(default_factory=bytearray)
    alive: bool = True
    lock: threading.Lock = field(default_factory=threading.Lock)


JOBS: dict[str, Job] = {}
TERMINALS: dict[str, TerminalSession] = {}
STATE_LOCK = threading.Lock()
CSRF_TOKEN = secrets.token_urlsafe(32)


def json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False).encode("utf-8")


def active_profile() -> str:
    try:
        value = ACTIVE_PROFILE_FILE.read_text(encoding="utf-8").strip()
        return value if value in {"dev", "test"} else "dev"
    except FileNotFoundError:
        return "dev"


def active_language() -> str:
    try:
        value = LANGUAGE_FILE.read_text(encoding="utf-8").strip().lower()
        return "en" if value in {"en", "english"} else "pl"
    except FileNotFoundError:
        return "pl"


def secret_root_directory() -> Path:
    configured = os.environ.get("MINI_ORDER_PLATFORM_SECRETS_DIR")
    if configured:
        return Path(configured).expanduser()
    config_home = os.environ.get("XDG_CONFIG_HOME")
    config_directory = Path(config_home).expanduser() if config_home else Path.home() / ".config"
    return config_directory / "mini-order-platform"


def public_secret_profiles() -> dict[str, dict[str, bool]]:
    root = secret_root_directory()
    return {
        profile: {
            "key": (root / "keys" / f"{profile}.agekey").is_file(),
            "file": (root / "secrets" / f"{profile}.env.sops").is_file(),
        }
        for profile in sorted(SECRET_PROFILES)
    }


def update_profile_secrets(profile: str, values: Any) -> list[str]:
    if profile not in SECRET_PROFILES:
        raise ValueError("Edycja sekretów jest dostępna wyłącznie dla profilu dev albo test.")
    if not isinstance(values, dict):
        raise ValueError("Nieprawidłowe dane formularza sekretów.")

    unknown = set(values) - SECRET_EDIT_NAMES
    if unknown:
        raise ValueError("Formularz zawiera niedozwolone pole sekretu.")

    encoded_lines: list[str] = []
    updated_names: list[str] = []
    for field in SECRET_EDIT_FIELDS:
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

    command = ["bash", str(CONTROL_SCRIPT), "secrets-update-web", profile]
    environment = os.environ.copy()
    environment["TERM"] = "dumb"
    try:
        with SECRET_UPDATE_LOCK:
            result = subprocess.run(
                command,
                cwd=PROJECT_ROOT,
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


def setup_profile_secrets(profile: str, values: Any) -> list[str]:
    if profile not in SECRET_PROFILES:
        raise ValueError("Utworzenie sekretów jest dostępne wyłącznie dla profilu dev albo test.")
    if not isinstance(values, dict):
        raise ValueError("Nieprawidłowe dane formularza pierwszej konfiguracji.")

    unknown = set(values) - SECRET_SETUP_NAMES
    if unknown:
        raise ValueError("Formularz zawiera niedozwolone pole sekretu.")

    normalized: dict[str, str] = {}
    for field in SECRET_SETUP_FIELDS:
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

    command = ["bash", str(CONTROL_SCRIPT), "secrets-setup-web", profile]
    environment = os.environ.copy()
    environment["TERM"] = "dumb"
    try:
        with SECRET_UPDATE_LOCK:
            result = subprocess.run(
                command,
                cwd=PROJECT_ROOT,
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
    return [field["name"] for field in SECRET_SETUP_FIELDS if normalized.get(field["name"], "") != ""]


def api_json(url: str, *, method: str = "GET", payload: Any = None, timeout: float = 10) -> Any:
    data = None if payload is None else json_bytes(payload)
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read()
        return json.loads(raw.decode("utf-8")) if raw else None


DOCKER_STATS_CACHE: dict[str, Any] = {"timestamp": 0.0, "data": {}}
DOCKER_STATS_LOCK = threading.Lock()


def run_docker_cli(args: list[str], timeout: float = 5.0) -> tuple[int, str, str]:
    if shutil.which("docker"):
        cmd = ["docker", *args]
    elif shutil.which("wsl"):
        cmd = ["wsl", "docker", *args]
    else:
        return 1, "", "Brak narzędzia docker / wsl w PATH."
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            encoding="utf-8",
            errors="replace",
        )
        return proc.returncode, proc.stdout, proc.stderr
    except Exception as exc:
        return 1, "", str(exc)


def get_docker_stats() -> dict[str, dict[str, str]]:
    with DOCKER_STATS_LOCK:
        now = time.monotonic()
        if now - DOCKER_STATS_CACHE["timestamp"] < 5.0 and DOCKER_STATS_CACHE["data"]:
            return DOCKER_STATS_CACHE["data"]

        code, stdout, _ = run_docker_cli(
            ["stats", "--no-stream", "--format", "{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}"],
            timeout=3.0,
        )
        stats: dict[str, dict[str, str]] = {}
        if code == 0:
            for line in stdout.strip().splitlines():
                parts = line.split("\t")
                if len(parts) >= 2:
                    c_name = parts[0].strip()
                    mem = parts[1].strip()
                    cpu = parts[2].strip() if len(parts) > 2 else ""
                    stats[c_name] = {"memory": mem, "cpu": cpu}
        DOCKER_STATS_CACHE["timestamp"] = now
        DOCKER_STATS_CACHE["data"] = stats
        return stats


def check_service(item: tuple[str, str, int]) -> dict[str, Any]:
    alias, name, port = item
    started = time.monotonic()
    url = f"http://127.0.0.1:{port}/actuator/health"
    try:
        with urllib.request.urlopen(url, timeout=1.3) as response:
            payload = json.loads(response.read().decode("utf-8"))
            online = response.status == 200 and payload.get("status") == "UP"
            state = payload.get("status", f"HTTP {response.status}")
    except Exception:
        online, state = False, "OFFLINE"

    stats = get_docker_stats()
    c_candidates = [
        f"miniorderplatform-{alias}-service-1",
        f"miniorderplatform-{alias}-1",
        f"miniorderplatform-api-gateway-1" if alias == "gateway" else "",
    ]
    mem_info, cpu_info = "", ""
    valid_candidates = [c for c in c_candidates if c]
    for candidate in valid_candidates:
        if candidate in stats:
            mem_info = stats[candidate]["memory"]
            cpu_info = stats[candidate]["cpu"]
            break
    if not mem_info:
        for c_name, c_stat in stats.items():
            if any(c_name.endswith(f"_{cand}") or cand in c_name for cand in valid_candidates):
                mem_info = c_stat["memory"]
                cpu_info = c_stat["cpu"]
                break

    return {
        "alias": alias,
        "name": name,
        "port": port,
        "url": url,
        "online": online,
        "status": state,
        "latencyMs": round((time.monotonic() - started) * 1000),
        "memory": mem_info,
        "cpu": cpu_info,
    }


def psql_query_json(db_alias: str, query: str, timeout: float = 4.0) -> list[dict[str, Any]]:
    container = f"miniorderplatform-{db_alias}-db-1"
    user = f"{db_alias}_app"
    db_name = f"{db_alias}_db"
    sql = f"SELECT coalesce(json_agg(t), '[]'::json) FROM ({query}) t;"
    code, stdout, _ = run_docker_cli(
        ["exec", "-i", container, "psql", "-U", user, "-d", db_name, "-t", "-A", "-c", sql],
        timeout=timeout,
    )
    if code != 0 or not stdout.strip():
        return []
    try:
        return json.loads(stdout.strip())
    except Exception:
        return []


def get_orders_data(limit: int = 500) -> list[dict[str, Any]]:
    invoices_map: dict[int, str] = {}
    try:
        db_invs = psql_query_json("billing", "SELECT order_id, invoice_number FROM invoices")
        for inv in db_invs:
            if isinstance(inv, dict) and "order_id" in inv and "invoice_number" in inv:
                invoices_map[inv["order_id"]] = inv["invoice_number"]
    except Exception:
        pass

    orders: list[dict[str, Any]] = []
    try:
        fetched = api_json(f"{GATEWAY_API}/orders", timeout=3.0)
        if isinstance(fetched, list):
            # Sort newest first so that taking the first N items preserves latest orders
            fetched.sort(
                key=lambda value: (
                    value.get("createdAt") or value.get("created_at") or "",
                    value.get("id") or 0,
                ),
                reverse=True,
            )
            orders = fetched[:limit] if limit > 0 else fetched
    except Exception:
        pass

    if not orders:
        limit_sql = f"LIMIT {limit}" if limit > 0 else ""
        sql = f"SELECT id, customer_id, customer_email, total_price, status, invoice_requested, created_at, updated_at FROM orders ORDER BY id DESC {limit_sql}"
        orders = psql_query_json("order", sql)

    for o in orders:
        oid = o.get("id")
        inv_num = invoices_map.get(oid)
        o["hasInvoice"] = bool(inv_num)
        o["invoiceNumber"] = inv_num
        price = o.get("total_price") if o.get("total_price") is not None else o.get("totalPrice")
        if price is not None:
            o["totalPrice"] = price
            o["total_price"] = price
        if not o.get("currency"):
            o["currency"] = "PLN"

    return orders


def generate_invoice_for_order(order_id: int) -> dict[str, Any]:
    order = None
    try:
        order = api_json(f"{GATEWAY_API}/orders/{order_id}", timeout=3.0)
    except Exception:
        pass
    if not isinstance(order, dict) or not order.get("id"):
        rows = psql_query_json(
            "order",
            f"SELECT id, customer_id, customer_email, total_price, status, delivery_cost, discount_amount, delivery_first_name, delivery_last_name, delivery_street, delivery_postal_code, delivery_city, delivery_country FROM orders WHERE id = {order_id}",
        )
        if rows:
            order = rows[0]
            items = psql_query_json(
                "order",
                f"SELECT product_id, sku, product_name, quantity, unit_price, total_price FROM order_items WHERE order_id = {order_id}",
            )
            order["items"] = items
        else:
            return {"success": False, "error": f"Nie znaleziono zamówienia #{order_id}."}

    raw_items = order.get("items") or []
    items_payload = []
    for item in raw_items:
        unit_p = item.get("unitPrice") if item.get("unitPrice") is not None else item.get("unit_price")
        tot_p = item.get("totalPrice") if item.get("totalPrice") is not None else item.get("total_price")
        items_payload.append({
            "productId": item.get("productId") or item.get("product_id") or 1,
            "sku": item.get("sku") or "ITEM-1",
            "productName": item.get("productName") or item.get("product_name") or "Produkt",
            "quantity": item.get("quantity") or 1,
            "unitPrice": f"{float(unit_p):.2f}" if unit_p is not None else "0.00",
            "totalPrice": f"{float(tot_p):.2f}" if tot_p is not None else "0.00",
        })

    if not items_payload:
        total_p = f"{float(order.get('totalPrice') or order.get('total_price') or 0.0):.2f}"
        items_payload.append({
            "productId": 1,
            "sku": "ITEM-GENERIC",
            "productName": f"Pozycja z zamówienia #{order_id}",
            "quantity": 1,
            "unitPrice": total_p,
            "totalPrice": total_p,
        })

    addr = order.get("deliveryAddress") or {}
    first_name = addr.get("firstName") or order.get("delivery_first_name") or ""
    last_name = addr.get("lastName") or order.get("delivery_last_name") or ""
    buyer_name = f"{first_name} {last_name}".strip() or "Klient Indywidualny"
    street = addr.get("street") or order.get("delivery_street") or "ul. Sklepowa 1"
    postal_code = addr.get("postalCode") or order.get("delivery_postal_code") or "00-001"
    city = addr.get("city") or order.get("delivery_city") or "Warszawa"
    country = addr.get("country") or order.get("delivery_country") or "Polska"
    cust_email = order.get("customerEmail") or order.get("customer_email") or f"klient-{order_id}@minitech.local"

    gross_amt = f"{float(order.get('totalPrice') or order.get('total_price') or 0.0):.2f}"
    del_cost = f"{float(order.get('deliveryCost') or order.get('delivery_cost') or 0.0):.2f}"
    disc_amt = f"{float(order.get('discountAmount') or order.get('discount_amount') or 0.0):.2f}"

    invoice_payload = {
        "orderId": order_id,
        "customerId": order.get("customerId") or order.get("customer_id"),
        "customerEmail": cust_email,
        "grossAmount": gross_amt,
        "currency": "PLN",
        "deliveryCost": del_cost,
        "discountAmount": disc_amt,
        "items": items_payload,
        "billingData": {
            "company": False,
            "buyerName": buyer_name,
            "companyName": None,
            "taxId": None,
            "street": street,
            "postalCode": postal_code,
            "city": city,
            "country": country,
        },
    }

    try:
        body_bytes = json.dumps(invoice_payload).encode("utf-8")
        req = urllib.request.Request(
            f"{GATEWAY_API}/invoices",
            data=body_bytes,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5.0) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return {"success": True, "invoice": data, "invoiceNumber": data.get("invoiceNumber")}
    except urllib.error.HTTPError as err:
        err_body = err.read().decode("utf-8", errors="replace")
    except Exception as exc:
        return {"success": False, "error": f"Błąd wystawiania faktury: {exc}"}


def get_corrections_data() -> list[dict[str, Any]]:
    for base in [f"{GATEWAY_API}/invoices/corrections", f"{BILLING_DIRECT_API}/invoices/corrections"]:
        try:
            req = urllib.request.Request(base, headers={"Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=3.0) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if isinstance(data, list):
                    return data
        except Exception:
            pass
    try:
        sql = "SELECT id, invoice_id, order_id, correction_number, original_invoice_number, reason, original_net, original_vat, original_gross, correction_net, correction_vat, correction_gross, final_net, final_vat, final_gross, created_at FROM invoice_corrections ORDER BY id DESC"
        db_res = psql_query_json("billing", sql)
        if isinstance(db_res, list):
            for c in db_res:
                c["invoiceId"] = c.get("invoiceId") if c.get("invoiceId") is not None else c.get("invoice_id")
                c["orderId"] = c.get("orderId") if c.get("orderId") is not None else c.get("order_id")
                c["correctionNumber"] = c.get("correctionNumber") or c.get("correction_number")
                c["originalInvoiceNumber"] = c.get("originalInvoiceNumber") or c.get("original_invoice_number")
                c["originalGross"] = float(c.get("originalGross") if c.get("originalGross") is not None else c.get("original_gross", 0) or 0)
                c["correctionGross"] = float(c.get("correctionGross") if c.get("correctionGross") is not None else c.get("correction_gross", 0) or 0)
                c["finalGross"] = float(c.get("finalGross") if c.get("finalGross") is not None else c.get("final_gross", 0) or 0)
                c["createdAt"] = c.get("createdAt") or c.get("created_at") or ""
            return db_res
    except Exception:
        pass
    return []


def get_invoices_data(limit: int = 500, search: str = "") -> dict[str, Any]:
    invoices: list[dict[str, Any]] = []
    total_count: int = 0
    search_param = f"&search={urllib.parse.quote(search)}" if search else ""
    # 1. Try via API Gateway / Billing Service REST API
    try:
        req_url = f"{GATEWAY_API}/invoices?size={limit}{search_param}"
        req = urllib.request.Request(req_url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=3.0) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            if isinstance(data, list):
                invoices = data
                total_count = int(resp.headers.get("X-Total-Count", len(data)))
    except Exception:
        pass

    # 2. Fallback to direct DB query if API failed
    if not invoices:
        try:
            where_clause = ""
            if search:
                s = search.replace("'", "''")
                where_clause = f"WHERE LOWER(invoice_number) LIKE LOWER('%{s}%') OR LOWER(buyer_name) LIKE LOWER('%{s}%') OR LOWER(coalesce(company_name, '')) LIKE LOWER('%{s}%') OR LOWER(customer_email) LIKE LOWER('%{s}%') OR LOWER(coalesce(tax_id, '')) LIKE LOWER('%{s}%')"
            sql = f"SELECT id, order_id, customer_id, customer_email, invoice_number, billing_kind, buyer_name, company_name, tax_id, net_amount, vat_amount, gross_amount, currency, status, created_at FROM invoices {where_clause} ORDER BY created_at DESC LIMIT {limit}"
            db_res = psql_query_json("billing", sql)
            if isinstance(db_res, list):
                invoices = db_res
                total_count = len(db_res)
        except Exception:
            pass

    corrections = get_corrections_data()
    corr_by_invoice: dict[int, list[dict[str, Any]]] = {}
    for c in corrections:
        inv_id = c.get("invoiceId")
        if inv_id is not None:
            corr_by_invoice.setdefault(inv_id, []).append(c)

    total_net = 0.0
    total_vat = 0.0
    total_gross = 0.0
    for inv in invoices:
        inv_id = inv.get("id")
        inv["orderId"] = inv.get("orderId") if inv.get("orderId") is not None else inv.get("order_id")
        inv["customerId"] = inv.get("customerId") if inv.get("customerId") is not None else inv.get("customer_id")
        inv["customerEmail"] = inv.get("customerEmail") or inv.get("customer_email") or ""
        inv["invoiceNumber"] = inv.get("invoiceNumber") or inv.get("invoice_number") or ""
        inv["billingKind"] = inv.get("billingKind") or inv.get("billing_kind") or ""
        inv["buyerName"] = inv.get("buyerName") or inv.get("buyer_name") or ""
        inv["companyName"] = inv.get("companyName") or inv.get("company_name") or ""
        inv["taxId"] = inv.get("taxId") or inv.get("tax_id") or ""
        inv["createdAt"] = inv.get("createdAt") or inv.get("created_at") or ""

        inv_corrs = corr_by_invoice.get(inv_id, [])
        inv["corrections"] = inv_corrs
        inv["hasCorrections"] = bool(inv_corrs)

        try:
            net = float(inv.get("netAmount") if inv.get("netAmount") is not None else inv.get("net_amount", 0) or 0)
        except Exception:
            net = 0.0
        try:
            vat = float(inv.get("vatAmount") if inv.get("vatAmount") is not None else inv.get("vat_amount", 0) or 0)
        except Exception:
            vat = 0.0
        try:
            gross = float(inv.get("grossAmount") if inv.get("grossAmount") is not None else inv.get("gross_amount", 0) or 0)
        except Exception:
            gross = 0.0
        inv["netAmount"] = net
        inv["vatAmount"] = vat
        inv["grossAmount"] = gross
        total_net += net
        total_vat += vat
        total_gross += gross

    return {
        "invoices": invoices,
        "corrections": corrections,
        "totalCount": total_count,
        "totals": {
            "net": round(total_net, 2),
            "vat": round(total_vat, 2),
            "gross": round(total_gross, 2),
            "count": len(invoices)
        }
    }


def load_dispatch_history() -> list[dict[str, Any]]:
    try:
        if DISPATCH_HISTORY_FILE.exists():
            with open(DISPATCH_HISTORY_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    return data
    except Exception:
        pass
    return []


def record_dispatch(entry: dict[str, Any]) -> None:
    try:
        RUNTIME_ROOT.mkdir(parents=True, exist_ok=True)
        history = load_dispatch_history()
        history.insert(0, entry)
        history = history[:200]
        with open(DISPATCH_HISTORY_FILE, "w", encoding="utf-8") as f:
            json.dump(history, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def send_document_email(
    doc_type: str,
    doc_id: Any,
    order_id: Any,
    doc_number: str,
    recipient: str,
    subject: str = "",
    custom_message: str = "",
    buyer_name: str = "",
) -> dict[str, Any]:
    # 1. Fetch PDF content
    pdf_bytes: bytes | None = None
    is_corr = str(doc_type).upper() == "CORRECTION"
    if is_corr:
        candidates = [
            f"{GATEWAY_API}/invoices/corrections/{doc_id}/pdf",
            f"{BILLING_DIRECT_API}/invoices/corrections/{doc_id}/pdf",
        ]
    else:
        candidates = [
            f"{GATEWAY_API}/invoices/{doc_id}/pdf",
            f"{BILLING_DIRECT_API}/invoices/{doc_id}/pdf",
            f"{GATEWAY_API}/invoices/order/{order_id}/pdf",
            f"{BILLING_DIRECT_API}/invoices/order/{order_id}/pdf",
        ]

    for u in candidates:
        try:
            req = urllib.request.Request(u)
            with urllib.request.urlopen(req, timeout=10.0) as resp:
                data = resp.read()
                if data and len(data) > 100:
                    pdf_bytes = data
                    break
        except Exception:
            continue

    if not pdf_bytes:
        return {"success": False, "error": f"Nie udało się pobrać pliku PDF dokumentu {doc_number} z Billing Service."}

    # 2. Build email
    doc_title = "Faktura Korygująca (KOR)" if is_corr else "Faktura VAT"
    if not subject:
        subject = f"[MiniTech] {doc_title} nr {doc_number} do zamówienia #{order_id}"

    msg = MIMEMultipart("alternative")
    msg["Subject"] = subject
    msg["From"] = "MiniTech Store <faktury@minitech.local>"
    msg["To"] = recipient

    safe_nr = re.sub(r"[^\w\-_.]", "_", doc_number)
    file_prefix = "korekta" if is_corr else "faktura"
    attachment_filename = f"{file_prefix}-{safe_nr}.pdf"

    custom_text_part = f"\n\n--- Wiadomość od obsługi klienta ---\n{custom_message}\n----------------------------------\n" if custom_message else ""
    text_content = f"""Dzień dobry,

W załączniku przesyłamy dokument księgowy: {doc_title} nr {doc_number} do zamówienia #{order_id}.{custom_text_part}

Dokument został dołączony w formacie PDF.

Pozdrawiamy,
Zespół MiniTech Store
http://127.0.0.1:18070
"""

    custom_html_box = f"""
    <div style="margin: 20px 0; padding: 14px 18px; background: #f8fafc; border-left: 4px solid #0ea5e9; border-radius: 4px; font-style: normal;">
      <strong style="color: #0284c7; display: block; margin-bottom: 6px; font-size: 13px;">Wiadomość od obsługi klienta MiniTech:</strong>
      <p style="margin: 0; color: #334155; font-size: 14px; line-height: 1.5; white-space: pre-wrap;">{html.escape(custom_message)}</p>
    </div>
    """ if custom_message else ""

    buyer_row = f"""
          <tr>
            <td style="padding: 4px 0; color: #64748b;">Nabywca:</td>
            <td style="padding: 4px 0; font-weight: 600; text-align: right; color: #0f172a;">{html.escape(buyer_name)}</td>
          </tr>
    """ if buyer_name else ""

    html_content = f"""<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #1e293b; background-color: #f1f5f9; padding: 24px; margin: 0;">
  <div style="max-width: 600px; margin: 0 auto; background: #ffffff; border-radius: 10px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 4px 6px rgba(0,0,0,0.05);">
    <div style="background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%); padding: 22px 28px; color: #ffffff;">
      <h1 style="margin: 0; font-size: 20px; font-weight: 700; letter-spacing: -0.02em;">MiniTech Store</h1>
      <p style="margin: 4px 0 0; color: #94a3b8; font-size: 13px;">Elektroniczny obieg dokumentów księgowych</p>
    </div>
    <div style="padding: 28px;">
      <h2 style="margin: 0 0 16px; font-size: 18px; color: #0f172a;">Dzień dobry,</h2>
      <p style="margin: 0 0 16px; font-size: 14px; line-height: 1.6; color: #475569;">
        Przesyłamy dokument księgowy: <strong style="color: #0ea5e9;">{html.escape(doc_title)} nr {html.escape(doc_number)}</strong> wystawiony do zamówienia <strong>#{order_id}</strong>.
      </p>

      {custom_html_box}

      <div style="background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px 18px; margin: 20px 0;">
        <table style="width: 100%; border-collapse: collapse; font-size: 13px;">
          <tr>
            <td style="padding: 4px 0; color: #64748b;">Typ dokumentu:</td>
            <td style="padding: 4px 0; font-weight: 600; text-align: right; color: #0f172a;">{html.escape(doc_title)}</td>
          </tr>
          <tr>
            <td style="padding: 4px 0; color: #64748b;">Numer dokumentu:</td>
            <td style="padding: 4px 0; font-weight: 700; text-align: right; font-family: monospace; color: #0284c7;">{html.escape(doc_number)}</td>
          </tr>
          <tr>
            <td style="padding: 4px 0; color: #64748b;">ID Zamówienia:</td>
            <td style="padding: 4px 0; font-weight: 600; text-align: right; color: #0f172a;">#{order_id}</td>
          </tr>
          {buyer_row}
          <tr>
            <td style="padding: 4px 0; color: #64748b;">Załączony plik:</td>
            <td style="padding: 4px 0; font-weight: 600; text-align: right; color: #16a34a;">📎 {attachment_filename}</td>
          </tr>
        </table>
      </div>

      <p style="margin: 16px 0 0; font-size: 13px; color: #64748b; line-height: 1.5;">
        Plik PDF z pełną treścią dokumentu znajduje się w załączniku niniejszej wiadomości.
      </p>
    </div>
    <div style="background: #f8fafc; border-top: 1px solid #e2e8f0; padding: 16px 28px; text-align: center; font-size: 12px; color: #94a3b8;">
      MiniTech Store Sp. z o.o. • Mini Order Platform (Control Center)<br>
      Wiadomość wygenerowana automatycznie.
    </div>
  </div>
</body>
</html>
"""

    part_text = MIMEText(text_content, "plain", "utf-8")
    part_html = MIMEText(html_content, "html", "utf-8")
    msg.attach(part_text)
    msg.attach(part_html)

    part_pdf = MIMEApplication(pdf_bytes, _subtype="pdf")
    part_pdf.add_header("Content-Disposition", "attachment", filename=attachment_filename)
    msg.attach(part_pdf)

    # 3. Dispatch via SMTP to Mailpit
    try:
        with smtplib.SMTP("127.0.0.1", 11025, timeout=10.0) as smtp:
            smtp.send_message(msg)
    except Exception as exc:
        return {"success": False, "error": f"Błąd połączenia z serwerem poczty Mailpit (port 11025): {exc}"}

    # 4. Save history record
    rec = {
        "id": str(uuid.uuid4())[:8],
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime()),
        "documentType": doc_type,
        "documentId": doc_id,
        "orderId": order_id,
        "documentNumber": doc_number,
        "recipient": recipient,
        "subject": subject,
        "customMessage": custom_message,
        "attachmentFilename": attachment_filename,
        "attachmentSize": len(pdf_bytes),
        "status": "SENT",
    }
    record_dispatch(rec)

    return {"success": True, "record": rec}


def get_inventory_data() -> list[dict[str, Any]]:
    items = psql_query_json(
        "inventory",
        "SELECT id, product_id, available_quantity, reserved_quantity, updated_at FROM inventory_items ORDER BY product_id ASC LIMIT 100",
    )
    products_map: dict[int, dict[str, Any]] = {}
    try:
        prods = api_json(f"{GATEWAY_API}/products", timeout=2.0)
        if isinstance(prods, list):
            for p in prods:
                if isinstance(p, dict) and "id" in p:
                    products_map[p["id"]] = p
    except Exception:
        db_prods = psql_query_json("product", "SELECT id, name, sku, price, category FROM products")
        for p in db_prods:
            if isinstance(p, dict) and "id" in p:
                products_map[p["id"]] = p

    for item in items:
        pid = item.get("product_id")
        pinfo = products_map.get(pid, {})
        item["name"] = pinfo.get("name", f"Produkt #{pid}")
        item["sku"] = pinfo.get("sku", "-")
        item["price"] = pinfo.get("price", 0.0)
        item["category"] = pinfo.get("category", "-")

    return items


def restock_inventory(product_id: int, quantity: int) -> dict[str, Any]:
    try:
        current = api_json(f"http://127.0.0.1:18083/inventory/items/{product_id}", timeout=2.0)
        if current and "availableQuantity" in current:
            new_qty = int(current["availableQuantity"]) + quantity
            api_json(
                f"http://127.0.0.1:18083/inventory/items/{product_id}",
                method="PATCH",
                payload={"availableQuantity": new_qty},
                timeout=2.0,
            )
            return {"ok": True, "productId": product_id, "newQuantity": new_qty, "method": "REST"}
    except Exception:
        pass

    sql = f"UPDATE inventory_items SET available_quantity = available_quantity + {int(quantity)}, updated_at = NOW() WHERE product_id = {int(product_id)};"
    code, stdout, stderr = run_docker_cli(
        ["exec", "-i", "miniorderplatform-inventory-db-1", "psql", "-U", "inventory_app", "-d", "inventory_db", "-c", sql]
    )
    if code == 0:
        return {"ok": True, "productId": product_id, "added": quantity, "method": "SQL"}
    raise RuntimeError(f"Nie udało się uzupełnić stanu magazynowego: {stderr or stdout}")


def get_customers_data(limit: int = 50) -> list[dict[str, Any]]:
    try:
        custs = api_json(f"{GATEWAY_API}/customers", timeout=2.0)
        if isinstance(custs, list):
            return custs[:limit]
    except Exception:
        pass
    sql = f"SELECT id, username, email, first_name, last_name, balance, created_at FROM customers ORDER BY id ASC LIMIT {limit}"
    return psql_query_json("customer", sql)


def read_redis_stream(stream_name: str = "payment-events", count: int = 40) -> list[dict[str, Any]]:
    # Resolve container name flexibly
    container = "miniorderplatform-event-broker-1"
    code_names, names_out, _ = run_docker_cli(["ps", "--format", "{{.Names}}"], timeout=2.0)
    if code_names == 0:
        for n in names_out.splitlines():
            if "event-broker" in n:
                container = n.strip()
                break

    # 1. First try modern redis-cli --json
    code, stdout, _ = run_docker_cli(
        ["exec", "-i", container, "redis-cli", "--json", "XREVRANGE", stream_name, "+", "-", "COUNT", str(count)],
        timeout=3.0,
    )
    if code == 0 and stdout.strip():
        try:
            raw_entries = json.loads(stdout.strip())
            events: list[dict[str, Any]] = []
            for item in raw_entries:
                if not isinstance(item, list) or len(item) < 2:
                    continue
                entry_id = str(item[0])
                ts_part = int(entry_id.split("-")[0]) if "-" in entry_id else 0
                ts_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts_part / 1000.0)) if ts_part > 1_000_000_000 else ""
                fields = item[1]
                data_dict: dict[str, str] = {}
                if isinstance(fields, list):
                    for i in range(0, len(fields) - 1, 2):
                        data_dict[str(fields[i])] = str(fields[i+1])
                events.append({
                    "id": entry_id,
                    "timestamp": ts_str,
                    "data": data_dict
                })
            return events
        except Exception:
            pass

    # 2. Fallback to robust line-by-line parsing
    code, stdout, _ = run_docker_cli(
        ["exec", "-i", container, "redis-cli", "XREVRANGE", stream_name, "+", "-", "COUNT", str(count)],
        timeout=3.0,
    )
    if code != 0 or not stdout.strip():
        return []

    events = []
    lines = [line.strip() for line in stdout.strip().splitlines() if line.strip()]
    current_entry: dict[str, Any] | None = None
    key_buffer: str | None = None

    for line in lines:
        id_match = re.search(r'(?:^|["\'\s\)])(\d{10,}-\d+)(?:["\'\s]|$)', line)
        if id_match:
            if current_entry:
                events.append(current_entry)
            entry_id = id_match.group(1)
            ts_part = int(entry_id.split("-")[0]) if "-" in entry_id else 0
            ts_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts_part / 1000.0)) if ts_part > 1_000_000_000 else ""
            current_entry = {"id": entry_id, "timestamp": ts_str, "data": {}}
            key_buffer = None
            continue

        clean_val = re.sub(r'^\d+\)\s*', '', line).strip('"\'')
        if current_entry is not None:
            if key_buffer is None:
                key_buffer = clean_val
            else:
                current_entry["data"][key_buffer] = clean_val
                key_buffer = None

    if current_entry:
        events.append(current_entry)
    return events


def get_outbox_events(limit: int = 40) -> list[dict[str, Any]]:
    sql = (
        f"SELECT id, event_key, event_type, order_id, amount, recipient, status, attempts, "
        f"last_error, correlation_id, created_at, published_at "
        f"FROM payment_outbox_events ORDER BY id DESC LIMIT {limit}"
    )
    return psql_query_json("payment", sql)


def get_dlq_events(limit: int = 40) -> list[dict[str, Any]]:
    try:
        dlq = api_json("http://127.0.0.1:18086/notifications/dead-letter", timeout=2.0)
        if isinstance(dlq, list):
            return dlq[:limit]
    except Exception:
        pass
    sql = (
        f"SELECT id, notification_id, event_type, source_id, recipient, channel, subject, "
        f"message, status, failure_reason, attempt_count, created_at, resolved_at "
        f"FROM notification_dead_letters ORDER BY id DESC LIMIT {limit}"
    )
    return psql_query_json("notification", sql)


def replay_dlq_entry(dlq_id: int | str) -> dict[str, Any]:
    try:
        res = api_json(f"http://127.0.0.1:18086/notifications/dead-letter/{dlq_id}/replay", method="POST", timeout=3.0)
        return {"ok": True, "id": dlq_id, "result": res, "method": "REST"}
    except Exception:
        pass

    cmd = ["bash", str(CONTROL_SCRIPT), "dlq-replay", str(dlq_id)]
    try:
        proc = subprocess.run(cmd, cwd=PROJECT_ROOT, capture_output=True, text=True, timeout=10.0)
        if proc.returncode == 0:
            return {"ok": True, "id": dlq_id, "output": proc.stdout, "method": "CLI"}
        raise RuntimeError(proc.stderr or proc.stdout)
    except Exception as exc:
        raise RuntimeError(f"Błąd podczas ponawiania DLQ: {exc}")


def parse_trace_log_line(service: str, line: str) -> dict[str, Any]:
    ts_match = re.search(r"(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?)", line)
    timestamp = ts_match.group(1) if ts_match else ""

    level = "INFO"
    if " ERROR " in line:
        level = "ERROR"
    elif " WARN " in line:
        level = "WARN"
    elif " DEBUG " in line:
        level = "DEBUG"

    msg = line
    if " : " in line:
        msg = line.split(" : ", 1)[-1].strip()
    elif " --- " in line:
        msg = line.split(" --- ", 1)[-1].strip()

    return {
        "service": service,
        "timestamp": timestamp,
        "level": level,
        "message": msg,
        "raw": line[:500],
    }


def trace_correlation_id(correlation_id: str) -> list[dict[str, Any]]:
    cid = correlation_id.strip()
    if not cid:
        return []

    events: list[dict[str, Any]] = []

    log_dir = PROJECT_ROOT / "gitIgnoreOwn" / "runtime" / "logs"
    if log_dir.is_dir():
        for log_file in log_dir.glob("*.log"):
            service_name = log_file.stem
            try:
                content = log_file.read_text(encoding="utf-8", errors="replace")
                for line in content.splitlines():
                    if cid in line:
                        events.append(parse_trace_log_line(service_name, line))
            except Exception:
                pass

    trace_containers = [
        ("api-gateway", "miniorderplatform-api-gateway-1"),
        ("order", "miniorderplatform-order-service-1"),
        ("inventory", "miniorderplatform-inventory-service-1"),
        ("payment", "miniorderplatform-payment-service-1"),
        ("notification", "miniorderplatform-notification-service-1"),
        ("delivery", "miniorderplatform-delivery-service-1"),
        ("billing", "miniorderplatform-billing-service-1"),
    ]
    for srv_alias, c_name in trace_containers:
        code, out, _ = run_docker_cli(["logs", "--tail", "1500", c_name], timeout=3.0)
        if code == 0 and cid in out:
            for line in out.splitlines():
                if cid in line:
                    events.append(parse_trace_log_line(srv_alias, line))

    seen: set[str] = set()
    unique_events: list[dict[str, Any]] = []
    for ev in events:
        key = f"{ev['service']}-{ev['timestamp']}-{ev['message'][:40]}"
        if key not in seen:
            seen.add(key)
            unique_events.append(ev)

    unique_events.sort(key=lambda x: x.get("timestamp") or "")
    return unique_events


CHAOS_TARGETS = {
    "inventory": {
        "container": "miniorderplatform-inventory-service-1",
        "label": "Inventory Service",
        "desc": "Symuluje brak odpowiedzi magazynu (blokada rezerwacji, timeouty)",
    },
    "mailpit": {
        "container": "miniorderplatform-mailpit-1",
        "label": "Mailpit SMTP",
        "desc": "Symuluje awarię serwera poczty (generuje Notification Dead Letter Queue)",
    },
    "payment": {
        "container": "miniorderplatform-payment-service-1",
        "label": "Payment Service",
        "desc": "Symuluje awarię bramki płatności (testuje Circuit Breaker i Saga rollback)",
    },
    "broker": {
        "container": "miniorderplatform-event-broker-1",
        "label": "Redis Event Broker",
        "desc": "Symuluje awarię brokera zdarzeń (testuje odkładanie do Outbox)",
    },
}


def get_chaos_status() -> dict[str, Any]:
    code, stdout, _ = run_docker_cli(["ps", "-a", "--format", "{{.Names}}\t{{.Status}}"])
    lines = stdout.strip().splitlines() if code == 0 else []
    c_status: dict[str, str] = {}
    for line in lines:
        parts = line.split("\t", 1)
        if len(parts) == 2:
            c_status[parts[0].strip()] = parts[1].strip()

    result = {}
    for key, info in CHAOS_TARGETS.items():
        c_name = info["container"]
        st = c_status.get(c_name, "Not found / stopped")
        paused = "(Paused)" in st
        running = st.startswith("Up") and not paused
        result[key] = {
            "key": key,
            "label": info["label"],
            "desc": info["desc"],
            "container": c_name,
            "status": st,
            "running": running,
            "paused": paused,
        }
    return result


def apply_chaos_action(target: str, action: str) -> dict[str, Any]:
    if action == "restore_all" or target == "restore_all":
        for info in CHAOS_TARGETS.values():
            run_docker_cli(["unpause", info["container"]])
            run_docker_cli(["start", info["container"]])
        return {"ok": True, "message": "Przywrócono wszystkie kontenery."}

    info = CHAOS_TARGETS.get(target)
    if not info:
        raise ValueError(f"Nieznany cel chaosu: {target}")

    c_name = info["container"]
    if action == "pause":
        code, out, err = run_docker_cli(["pause", c_name])
    elif action == "unpause":
        code, out, err = run_docker_cli(["unpause", c_name])
    elif action == "stop":
        code, out, err = run_docker_cli(["stop", c_name])
    elif action == "start":
        code, out, err = run_docker_cli(["start", c_name])
    else:
        raise ValueError(f"Nieznana akcja chaosu: {action}")

    if code != 0:
        raise RuntimeError(f"Błąd operacji Docker ({action} {c_name}): {err or out}")

    return {"ok": True, "target": target, "action": action, "status": get_chaos_status().get(target)}


def run_job(job: Job, stdin_text: str) -> None:
    job.started_at = time.time()
    job.status = "running"
    JOB_ROOT.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["TERM"] = "dumb"
    with job.log_path.open("w", encoding="utf-8") as output:
        output.write(f"MiniTech Control · {job.title}\n$ {' '.join(job.command)}\n\n")
        output.flush()
        try:
            process = subprocess.Popen(
                job.command,
                cwd=PROJECT_ROOT,
                stdin=subprocess.PIPE,
                stdout=output,
                stderr=subprocess.STDOUT,
                text=True,
                env=env,
            )
            process.communicate(stdin_text if stdin_text else None)
            job.exit_code = process.returncode
            job.status = "success" if process.returncode == 0 else "failed"
        except Exception as error:
            output.write(f"\nBłąd uruchomienia: {error}\n")
            job.exit_code = -1
            job.status = "failed"
        finally:
            job.finished_at = time.time()


def start_terminal() -> TerminalSession:
    if pty is None or select is None:
        raise RuntimeError("Terminal PTY jest dostępny wyłącznie po uruchomieniu panelu w WSL/Linux.")
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(PROJECT_ROOT)
        os.execvp("bash", ["bash", str(CONTROL_SCRIPT)])
    session = TerminalSession(uuid.uuid4().hex[:12], pid, fd)
    TERMINALS[session.id] = session

    def reader() -> None:
        while session.alive:
            try:
                ready, _, _ = select.select([fd], [], [], 0.5)
                if not ready:
                    try:
                        ended, _ = os.waitpid(pid, os.WNOHANG)
                        if ended:
                            session.alive = False
                    except ChildProcessError:
                        session.alive = False
                    continue
                chunk = os.read(fd, 8192)
                if not chunk:
                    session.alive = False
                    break
                with session.lock:
                    session.output.extend(chunk)
                    if len(session.output) > 500_000:
                        del session.output[:-400_000]
            except OSError:
                session.alive = False
                break

    threading.Thread(target=reader, daemon=True).start()
    return session

AI_CONFIG_FILE = RUNTIME_ROOT / "ai-config.json"
DEFAULT_AI_CONFIG: dict[str, Any] = {
    "provider": "antigravity",
    "model": "antigravity-expert",
    "apiKey": "",
    "customEndpoint": "http://localhost:11434/v1",
    "includeContext": True,
}


def load_ai_config() -> dict[str, Any]:
    res = dict(DEFAULT_AI_CONFIG)
    if AI_CONFIG_FILE.is_file():
        try:
            saved = json.loads(AI_CONFIG_FILE.read_text(encoding="utf-8"))
            res.update(saved)
        except Exception:
            pass
    # Auto-discover keys from environment if not explicitly configured
    if not res.get("apiKey"):
        prov = res.get("provider", "antigravity")
        if prov == "antigravity":
            env_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY") or os.environ.get("ANTIGRAVITY_API_KEY", "")
            if env_key:
                res["apiKey"] = env_key
        elif prov == "codex":
            env_key = os.environ.get("OPENAI_API_KEY") or os.environ.get("CODEX_API_KEY", "")
            if env_key:
                res["apiKey"] = env_key
    return res


def save_ai_config(cfg: dict[str, Any]) -> dict[str, Any]:
    current = load_ai_config()
    current["provider"] = str(cfg.get("provider", current["provider"])).strip().lower()
    current["model"] = str(cfg.get("model", current["model"])).strip()
    current["customEndpoint"] = str(cfg.get("customEndpoint", current["customEndpoint"])).strip()
    current["includeContext"] = bool(cfg.get("includeContext", current["includeContext"]))
    new_key = str(cfg.get("apiKey", "")).strip()
    if new_key and not new_key.startswith("••••"):
        current["apiKey"] = new_key
    elif cfg.get("apiKey") == "":
        current["apiKey"] = ""
    AI_CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
    AI_CONFIG_FILE.write_text(json.dumps(current, indent=2, ensure_ascii=False), encoding="utf-8")
    return public_ai_config()


def public_ai_config() -> dict[str, Any]:
    cfg = load_ai_config()
    key = cfg.get("apiKey", "")
    masked = f"••••{key[-4:]}" if len(key) >= 8 else ("••••" if key else "")
    return {
        "provider": cfg.get("provider", "antigravity"),
        "model": cfg.get("model", "antigravity-expert"),
        "customEndpoint": cfg.get("customEndpoint", "http://localhost:11434/v1"),
        "includeContext": cfg.get("includeContext", True),
        "hasApiKey": bool(key),
        "maskedApiKey": masked,
    }


def get_project_system_prompt(lang: str = "pl") -> str:
    if lang == "en":
        return """You are the MiniTech AI Architecture Assistant for Mini Order Platform.
Project details:
- Monorepo with 15 Spring Boot 3.4 microservices (Java 25, Maven) + vanilla JS Storefront frontend (:18070).
- Microservices & Ports:
  * API Gateway: 18080 (routing to /api/products, /api/orders, /api/inventory, /api/payments, /api/customers, etc.)
  * Product Service: 18081 (PostgreSQL product_db)
  * Order Service: 18082 (PostgreSQL order_db, Outbox pattern, Redis Streams events)
  * Inventory Service: 18083 (PostgreSQL inventory_db)
  * Payment Service: 18084 (PostgreSQL payment_db)
  * Notification Service: 18086 (PostgreSQL notification_db, Mailpit :1025, Web mailbox :18025)
  * Customer Service: 18087 (PostgreSQL customer_db, JWT auth)
  * Delivery Service: 18088 (PostgreSQL delivery_db)
  * Promotion Service: 18089 (PostgreSQL promotion_db)
  * Wishlist Service: 18090 (PostgreSQL wishlist_db)
  * Review Service: 18091 (PostgreSQL review_db)
  * Recommendation Service: 18092 (PostgreSQL recommendation_db)
  * Chat Service: 18093 (PostgreSQL chat_db)
  * Returns Service: 18094 (PostgreSQL returns_db)
  * Billing Service: 18095 (PostgreSQL billing_db)
- Infrastructure:
  * 14 isolated PostgreSQL databases on ports 15432-15446.
  * Redis Streams event broker (localhost:16379, container event-broker:6379)
  * Mailpit SMTP :18025 / :1025
  * Prometheus :19090, Grafana :13000
  * MiniTech Control Center :18100
- Docker stacks: compose.yaml (Databases + Redis + Mailpit), compose.apps.yaml (All 15 microservices + frontend), compose.monitoring.yaml.
- Security & Secrets: age keys + Mozilla SOPS (.env.sops).
- Scripts: bash ./scripts/start.sh, docker-apps-up, docker-apps-down, infra-up, infra-down, app-verify.
Provide clear, structured, technical markdown answers with code snippets and commands when relevant."""
    else:
        return """Jesteś Asystentem Architektury AI dla projektu Mini Order Platform (MiniTech).
Informacje o projekcie:
- Monorepo z 15 mikroserwisami Spring Boot 3.4 (Java 25, Maven) + frontend sklepu w czystym JS (:18070).
- Mikroserwisy i ich porty lokalne:
  * API Gateway: :18080 (routing do /api/products, /api/orders, /api/inventory, /api/payments, /api/customers, itd.)
  * Product Service: :18081 (PostgreSQL product_db)
  * Order Service: :18082 (PostgreSQL order_db, wzorzec Outbox, publikacja zdarzeń do Redis Streams)
  * Inventory Service: :18083 (PostgreSQL inventory_db)
  * Payment Service: :18084 (PostgreSQL payment_db)
  * Notification Service: :18086 (PostgreSQL notification_db, Mailpit SMTP :1025, interfejs web :18025)
  * Customer Service: :18087 (PostgreSQL customer_db, autoryzacja RSA / JWT)
  * Delivery Service: :18088 (PostgreSQL delivery_db)
  * Promotion Service: :18089 (PostgreSQL promotion_db)
  * Wishlist Service: :18090 (PostgreSQL wishlist_db)
  * Review Service: :18091 (PostgreSQL review_db)
  * Recommendation Service: :18092 (PostgreSQL recommendation_db)
  * Chat Service: :18093 (PostgreSQL chat_db, obsługa czatu klientów)
  * Returns Service: :18094 (PostgreSQL returns_db, zwroty towarów)
  * Billing Service: :18095 (PostgreSQL billing_db, faktury i rozliczenia)
- Infrastruktura:
  * 14 odizolowanych baz PostgreSQL na portach 15432-15446.
  * Redis Streams event broker (lokalnie 16379, w sieci Docker container: event-broker:6379)
  * Mailpit testowy serwer poczty :18025 (web) / :1025 (smtp)
  * Prometheus :19090, Grafana :13000
  * Centrala MiniTech Control :18100
- Stosy Docker Compose:
  * `compose.yaml` – bazy danych, Redis broker, Mailpit.
  * `compose.apps.yaml` – wszystkie 15 mikroserwisów i frontend.
  * `compose.monitoring.yaml` – Prometheus i Grafana.
- Bezpieczeństwo i sekrety: Klucze age + Mozilla SOPS (`.env.sops`).
- Główne polecenia Centrali: `bash ./scripts/start.sh`, `docker-apps-up`, `docker-apps-down`, `infra-up`, `infra-down`, `app-verify`, `jwt-status`.
Odpowiadaj konkretnie, w formacie markdown, z tabelami, listami i przykładami poleceń, gdy to przydatne."""


def query_customers_db() -> list[dict[str, Any]]:
    cmd = [
        "docker", "exec", "-i", "miniorderplatform-customer-db-1",
        "psql", "-U", "customer_app", "-d", "customer_db",
        "-t", "-A", "-F", "\t",
        "-c", "SELECT id, email, first_name, last_name, city, enabled, created_at FROM customers ORDER BY id ASC;"
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=3)
        if proc.returncode == 0 and proc.stdout.strip():
            rows = []
            for line in proc.stdout.strip().splitlines():
                parts = line.split("\t")
                if len(parts) >= 7:
                    rows.append({
                        "id": parts[0],
                        "email": parts[1],
                        "firstName": parts[2],
                        "lastName": parts[3],
                        "city": parts[4],
                        "enabled": parts[5] in {"t", "true", "1"},
                        "createdAt": parts[6]
                    })
            return rows
    except Exception:
        pass
    return []


def query_reviews_db() -> list[dict[str, Any]]:
    cmd = [
        "docker", "exec", "-i", "miniorderplatform-review-db-1",
        "psql", "-U", "review_app", "-d", "review_db",
        "-t", "-A", "-F", "\t",
        "-c", "SELECT id, product_id, rating, title, content, status, created_at FROM reviews ORDER BY id ASC;"
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=3)
        if proc.returncode == 0 and proc.stdout.strip():
            rows = []
            for line in proc.stdout.strip().splitlines():
                parts = line.split("\t")
                if len(parts) >= 6:
                    rows.append({
                        "id": parts[0],
                        "productId": parts[1],
                        "rating": int(parts[2]) if parts[2].isdigit() else 5,
                        "title": parts[3],
                        "content": parts[4],
                        "status": parts[5],
                        "createdAt": parts[6] if len(parts) > 6 else ""
                    })
            return rows
    except Exception:
        pass
    return []


def generate_local_knowledge_reply(query: str, lang: str = "pl") -> str:
    q = query.lower().strip()
    is_en = lang == "en"

    # 1. Customer Support Chat inquiries
    if any(k in q for k in ["czat", "chat", "wiadomośc", "wiadomos", "inquiry", "ticket", "pomoc"]):
        try:
            chat_data = api_json(f"{GATEWAY_API}/chat/messages", timeout=3)
            if isinstance(chat_data, list):
                count = len(chat_data)
                lines = [
                    f"### 💬 Zgłoszenia i czat klientów ({count} wątków)" if not is_en else f"### 💬 Customer Chat Inquiries ({count} threads)",
                    "",
                    "| ID | Klient | Temat | Status | Data |" if not is_en else "| ID | Customer | Subject | Status | Date |",
                    "| :---: | :--- | :--- | :---: | :--- |"
                ]
                for c in chat_data[:6]:
                    c_date = str(c.get("createdAt", ""))[:16].replace("T", " ")
                    lines.append(f"| `#{c.get('id')}` | {c.get('customerName')} (`{c.get('customerEmail')}`) | **{c.get('subject')}** | `{c.get('status')}` | {c_date} |")
                lines.extend([
                    "",
                    "💡 **Wskazówka:** Możesz odpowiadać klientom bezpośrednio w zakładce **Czat klientów** w lewym menu Centrali."
                ])
                return "\n".join(lines)
        except Exception:
            pass

    # 2. Customer Reviews
    if any(k in q for k in ["opini", "recenzj", "ocen", "review"]):
        reviews = query_reviews_db()
        if reviews:
            count = len(reviews)
            lines = [
                f"### ⭐ Opinie klientów o produktach ({count} opinii)" if not is_en else f"### ⭐ Customer Reviews ({count} reviews)",
                "",
                "Oto opinie i recenzje produktów zapisane w bazie `review_db`:" if not is_en else "Customer reviews registered in `review_db`:",
                "",
                "| ID | Produkt | Ocena | Tytuł | Treść opinii | Status |" if not is_en else "| ID | Product | Rating | Title | Review Content | Status |",
                "| :---: | :---: | :---: | :--- | :--- | :---: |"
            ]
            for r in reviews:
                stars = "★" * r.get("rating", 5)
                st_badge = "🟢 " + r["status"] if r["status"] == "PUBLISHED" else ("🔴 " + r["status"] if r["status"] == "REJECTED" else "🟡 " + r["status"])
                lines.append(f"| `#{r['id']}` | `Produkt #{r['productId']}` | **{stars}** | **{r['title']}** | {r['content']} | `{st_badge}` |")
            if not is_en:
                lines.extend([
                    "",
                    "💡 **Wskazówka:** W panelu sklepu klienci mogą dodawać opinie do zakupionych produktów po zrealizowaniu zamówienia."
                ])
            return "\n".join(lines)
        else:
            if is_en:
                return "### ⭐ Customer Reviews\n\nThere are currently no reviews in `review_db`."
            else:
                return "### ⭐ Opinie klientów o produktach\n\nAktualnie w bazie `review_db` nie ma jeszcze żadnych opinii klientów.\n\nMożesz wygenerować przykładowe recenzje kafelkiem **`📦 Zasil danymi demo`**."

    # 3. Promotion codes & Discounts
    if any(k in q for k in ["promocj", "kod", "rabat", "kupon", "promo", "coupon", "discount"]):
        try:
            codes = api_json(f"{GATEWAY_API}/promotions/codes", timeout=3)
            if isinstance(codes, list):
                if not codes:
                    return (
                        "### 🏷️ Kody rabatowe\n\nAktualnie w bazie `promotion_db` nie ma jeszcze żadnych kodów rabatowych.\n\n"
                        "- Możesz utworzyć nowy kod kafelkiem **`Utwórz kod rabatowy`** na Pulpicie Centrali.\n"
                        "- Lub zasilić sklep przykładowymi kodami (np. `MINITECH10`, `VIP20`) kafelkiem **`📦 Zasil danymi demo`**."
                        if not is_en else
                        "### 🏷️ Promotion Codes\n\nThere are currently no promotion codes in `promotion_db`.\n\n"
                        "- You can create a new code using the **`Create promotion code`** action.\n"
                        "- Or populate demo codes using the **`📦 Seed Demo Data`** tile in Centrala."
                    )
                count = len(codes)
                today = date.today()
                lines = [
                    f"### 🏷️ Kody promocyjne w sklepie ({count} kodów)" if not is_en else f"### 🏷️ Promotion Codes ({count} codes)",
                    "",
                    "Oto lista kodów rabatowych zarejestrowanych w `Promotion Service` wraz ze statusem ich ważności:" if not is_en else "Here is the list of promotion codes registered in `Promotion Service` with validity status:",
                    "",
                    "| Kod rabatowy | Wysokość rabatu | Opis promocji | Status ważności | Liczba użyć |" if not is_en else "| Promo Code | Discount | Description | Validity Status | Redemptions |",
                    "| :--- | :---: | :--- | :---: | :---: |"
                ]
                for cd in codes:
                    v_to_str = cd.get("validTo")
                    v_from_str = cd.get("validFrom")
                    v_to = date.fromisoformat(v_to_str) if v_to_str else None
                    v_from = date.fromisoformat(v_from_str) if v_from_str else None
                    max_r = cd.get("maxRedemptions")
                    red_c = cd.get("redeemedCount", 0)

                    if not cd.get("active"):
                        st = "🔴 Nieaktywny" if not is_en else "🔴 Inactive"
                    elif v_from and today < v_from:
                        st = f"🟡 Ważny od {v_from}" if not is_en else f"🟡 Valid from {v_from}"
                    elif v_to and today > v_to:
                        st = f"🔴 Wygasł ({v_to})" if not is_en else f"🔴 Expired ({v_to})"
                    elif max_r is not None and red_c >= max_r:
                        st = f"🔴 Limit wyczerpany ({red_c}/{max_r})" if not is_en else f"🔴 Limit reached ({red_c}/{max_r})"
                    else:
                        st = f"🟢 Ważny do {v_to or 'bezterminowo'}" if not is_en else f"🟢 Valid until {v_to or 'indefinite'}"

                    lim_label = f"{red_c}/{max_r if max_r is not None else '∞'}"
                    lines.append(f"| **`{cd.get('code')}`** | **-{cd.get('discountPercent')}%** | {cd.get('description')} | {st} | {lim_label} |")
                if not is_en:
                    lines.extend([
                        "",
                        "💡 **Wskazówka:** Możesz użyć aktywnych kodów w koszyku w sklepie (`:18070`) lub wygenerować nowe kody kafelkiem **`Utwórz kod rabatowy`** na Pulpicie."
                    ])
                return "\n".join(lines)
        except Exception as err:
            if not is_en:
                return f"### 🏷️ Kody rabatowe\n\nNie udało się pobrać kodów z Promotion Service: `{err}`.\n\nUpewnij się, że usługa działa (port `:18089`)."
            return f"### 🏷️ Promotion Codes\n\nFailed to fetch codes from Promotion Service: `{err}`.\n\nMake sure the service is running (port `:18089`)."

    # 4. Registered Customer Accounts
    if any(k in q for k in ["konta", "konto", "klient", "user", "użytkownik", "uzytkownik", "rejestracj", "account", "customer"]):
        customers = query_customers_db()
        if customers:
            count = len(customers)
            lines = [
                f"### 👥 Zarejestrowane konta klientów (Łącznie: {count})" if not is_en else f"### 👥 Registered Customer Accounts ({count} accounts)",
                "",
                "W bazie danych `customer_db` znajdują się następujące konta klientów:" if not is_en else "The following accounts are registered in `customer_db`:",
                "",
                "| ID | Imię i Nazwisko | E-mail | Miasto | Status | Data rejestracji |" if not is_en else "| ID | Name | Email | City | Status | Registered At |",
                "| :---: | :--- | :--- | :--- | :---: | :--- |"
            ]
            for c in customers:
                st = "🟢 Aktywne" if c["enabled"] else "🟡 Weryfikacja"
                if is_en:
                    st = "🟢 Active" if c["enabled"] else "🟡 Pending"
                full_name = f"{c['firstName']} {c['lastName']}".strip()
                date_str = str(c.get("createdAt", ""))[:16].replace("T", " ")
                lines.append(f"| `#{c['id']}` | **{full_name}** | `{c['email']}` | {c['city']} | {st} | {date_str} |")
            if not is_en:
                lines.extend([
                    "",
                    "💡 **Wskazówka:** Możesz sprawdzić dziennik audytu logowań w **Operacje → Dziennik audytowy (`audit-list`)**."
                ])
            return "\n".join(lines)

    # 5. Product Catalog
    if any(k in q for k in ["produkt", "product", "katalog", "towar", "magazyn", "stock"]):
        try:
            products = api_json(f"{GATEWAY_API}/products", timeout=3)
            if isinstance(products, list) and products:
                count = len(products)
                lines = [
                    f"### 🛒 Katalog produktów w sklepie ({count} pozycji)" if not is_en else f"### 🛒 Product Catalog ({count} products)",
                    "",
                    "| ID | Nazwa produktu | Cena | Kategoria | Producent |" if not is_en else "| ID | Product Name | Price | Category | Manufacturer |",
                    "| :---: | :--- | :---: | :--- | :--- |"
                ]
                for p in products[:8]:
                    lines.append(f"| `#{p.get('id')}` | **{p.get('name')}** | {p.get('price')} PLN | {p.get('category', '—')} | {p.get('manufacturer', '—')} |")
                lines.extend([
                    "",
                    f"*(Wyświetlono 8 z {count} produktów. Pełny katalog dostępny w sklepie: http://127.0.0.1:18070)*"
                ])
                return "\n".join(lines)
        except Exception as err:
            if not is_en:
                return f"### 🛒 Katalog produktów\n\nNie udało się połączyć z Product Service: `{err}`."

    # 6. Invoices & Billing Service
    if any(k in q for k in ["faktur", "billing", "invoice", "vat", "rachun"]):
        if is_en:
            return """### 🧾 Invoices & Billing Service (:18095):

1. **How it works:**
   - When an order is paid (`OrderPaid` event via Redis Streams), **Billing Service** automatically generates an invoice with a sequential VAT number and PDF document.
2. **Accessing invoices:**
   - In Centrala: Go to **Operations → Show invoice (`invoice-show`)** or **Download invoice PDF (`invoice-pdf`)**.
   - In Storefront: Customers can view and download invoices directly from their order history."""
        else:
            return """### 🧾 Faktury i Billing Service (:18095):

1. **Zasada działania:**
   - Po opłaceniu zamówienia zdarzenie `OrderPaid` trafia przez Redis Streams do **Billing Service**.
   - Usługa automatycznie rejestruje fakturę z kolejnym numerem VAT oraz generuje plik PDF.
2. **Przeglądanie i pobieranie:**
   - W Centrali: zakładka **Operacje → Pokaż fakturę (`invoice-show`)** lub **Pobierz PDF faktury (`invoice-pdf`)**.
   - W sklepie: klient może pobrać fakturę w szczegółach swojego zamówienia."""

    # 7. Deliveries & Shipping
    if any(k in q for k in ["dostaw", "kurier", "paczkomat", "delivery", "przesyłk", "przesylk", "tracking", "wysyłk"]):
        if is_en:
            return """### 🚚 Deliveries & Delivery Service (:18088):

1. **Supported Delivery Methods:**
   - `STANDARD_COURIER` – standard door-to-door courier delivery.
   - `PARCEL_LOCKER` – delivery to automated parcel locker machines.
   - `STORE_PICKUP` – free customer pickup at the physical store.
2. **Package Tracking:**
   - Check status and carrier in Centrala: **Operations → Delivery tracking (`delivery-tracking`)**.
   - Update delivery status in dev/test: **Operations → Update delivery status (`delivery-tracking-status`)**."""
        else:
            return """### 🚚 Dostawy i Delivery Service (:18088):

1. **Obsługiwane metody dostawy:**
   - `STANDARD_COURIER` – dostawa kurierem pod adres domowy / firmowy.
   - `PARCEL_LOCKER` – dostawa do Paczkomatu / automatu paczkowego.
   - `STORE_PICKUP` – bezpłatny odbiór osobisty w salonie stacjonarnym MiniTech.
2. **Śledzenie przesyłek:**
   - W Centrali: **Operacje → Śledzenie dostawy (`delivery-tracking`)**.
   - W dev/test możesz zmieniać status paczki (np. `IN_TRANSIT`, `DELIVERED`): **Operacje → Zmień status dostawy (`delivery-tracking-status`)**."""

    # 8. Real-time Live Orders query
    if any(k in q for k in ["zamówien", "zamowien", "order", "zakup"]):
        try:
            orders = api_json(f"{GATEWAY_API}/orders", timeout=3)
            if isinstance(orders, list):
                count = len(orders)
                if count == 0:
                    if is_en:
                        return "### 📦 Orders in Database\n\nThere are currently **no orders** in the database.\n\n- You can place an order via Storefront at http://127.0.0.1:18070\n- Or populate demo orders using the **`📦 Seed Demo Data`** tile in Centrala."
                    else:
                        return "### 📦 Zamówienia w bazie danych\n\nAktualnie w bazie **nie ma jeszcze żadnych zamówień** (baza jest pusta).\n\n- Możesz złożyć pierwsze zamówienie w sklepie: http://127.0.0.1:18070\n- Lub kliknąć kafelek **`📦 Zasil danymi demo`** na Pulpicie Centrali, aby wygenerować przykładowe zamówienia i faktury."
                else:
                    lines = [
                        f"### 📦 Zamówienia w bazie danych (Łącznie: {count})" if not is_en else f"### 📦 Orders in Database ({count} orders)",
                        "",
                        "Tak, w bazie danych znajdują się zamówienia. Oto lista ostatnich wpisów:" if not is_en else "Yes, there are orders in the database. Here is the recent list:",
                        "",
                        "| ID | Status | Kwota | E-mail klienta | Data | Pozycje |" if not is_en else "| ID | Status | Total | Customer Email | Date | Items |",
                        "| :---: | :---: | :---: | :--- | :--- | :---: |"
                    ]
                    for ord_item in orders[-8:]:
                        ord_id = ord_item.get("id", "—")
                        st = ord_item.get("status", "—")
                        total = f"{ord_item.get('totalPrice', 0):.2f} PLN"
                        email = ord_item.get("customerEmail", "—")
                        order_date = str(ord_item.get("createdAt", ""))[:16].replace("T", " ")
                        items_count = len(ord_item.get("items") or [])
                        lines.append(f"| `#{ord_id}` | `{st}` | **{total}** | {email} | {order_date} | {items_count} szt. |")
                    
                    if not is_en:
                        lines.extend([
                            "",
                            "💡 **Wskazówka:** Możesz pobrać PDF faktury wybranego zamówienia w **Operacje → Pobierz PDF faktury (`invoice-pdf`)**."
                        ])
                    return "\n".join(lines)
        except Exception as err:
            if not is_en:
                return f"### 📦 Zamówienia (Order Service)\n\nNie udało się połączyć z Order Service na porcie `:18082` przez API Gateway: `{err}`.\n\nUpewnij się, że serwisy są uruchomione (przycisk **`▶ Uruchom serwisy`** w Centrali)."

    # 9. Real-time Service Health
    if any(k in q for k in ["stan serwis", "stan usług", "stan uslug", "status serwis", "zdrowi", "działają", "dzialaja", "health", "offline", "online", "czy serwisy"]):
        with ThreadPoolExecutor(max_workers=len(SERVICES)) as pool:
            srv_res = list(pool.map(check_service, SERVICES))
        online_count = sum(1 for s in srv_res if s["online"])
        lines = [
            f"### 📊 Stan zdrowia mikroserwisów ({online_count}/{len(SERVICES)} ONLINE)" if not is_en else f"### 📊 Microservices Health ({online_count}/{len(SERVICES)} ONLINE)",
            "",
            "| Mikroserwis | Port | Status | Czas odpowiedzi |" if not is_en else "| Service | Port | Status | Latency |",
            "| :--- | :---: | :---: | :---: |"
        ]
        for s in srv_res:
            icon = "🟢 UP" if s["online"] else "🔴 OFFLINE"
            lines.append(f"| **{s['name']}** | `:{s['port']}` | {icon} | {s['latencyMs']} ms |")
        return "\n".join(lines)

    # 10. Troubleshooting & Error Knowledge Base
    if any(k in q for k in ["błąd", "blad", "błędy", "bledy", "problem", "awari", "troubleshoot", "napraw"]):
        if is_en:
            return """### 🚨 Error Knowledge Base & 1-Click Auto-Fix:

MiniTech platform includes a local Error Knowledge Base cataloging the top 12 common issues:

1. **PostgreSQL Connection Refused:** Database container down → click **`◫ Databases (infra-up)`**.
2. **JWT Secret Missing / Mismatch:** Run **`jwt-rotate dev`** or **`secrets-ensure-auth`**.
3. **Redis Event Broker Offline:** Run **`broker-up`**.
4. **Port Conflicts (18080/18100 in use):** Run **`app-stop-all`** or stop conflicting processes.
5. **Flyway Checksum Mismatch:** Review migration scripts in `db/migration`.

💡 *Full catalog: see `docs/ERROR_KNOWLEDGE_BASE.md` or click **🚑 Auto-Healing Doctor** on the Dashboard.*"""
        else:
            return """### 🚨 Baza Wiedzy o Błędach i Autodiagnostyka:

Platforma MiniTech posiada wbudowaną bazę 12 najczęstszych problemów i automatycznych rozwiązań:

1. **Brak połączenia z PostgreSQL (port 5432 refused):** Baza wyłączona → kliknij **`◫ Infrastruktura (infra-up)`**.
2. **Brak klucza JWT / MFA:** Wygeneruj klucze poleceniem **`jwt-rotate dev`** lub uzupełnij sekrety DEV.
3. **Broker Redis niedostępny:** Uruchom brokera przyciskiem **`Uruchom Redis`** w Operacjach.
4. **Zajęty port TCP (Address already in use):** Zatrzymaj blokujące procesy poleceniem **`app-stop-all`**.
5. **Błąd Flyway (suma kontrolna migracji):** Sprawdź pliki SQL w katalogu `db/migration`.

💡 *Pełny katalog znajdziesz w pliku `docs/ERROR_KNOWLEDGE_BASE.md` lub klikając **🚑 Skanuj i napraw** na Pulpicie Centrali.*"""

    # 11. Returns and RMA
    if any(k in q for k in ["zwrot", "reklamacj", "return", "rma"]):
        if is_en:
            return """### 🔄 Returns & Returns Service (:18094):

1. **Customer Complaints & Returns:**
   - Handles customer return requests, RMA tracking, and refund eligibility.
2. **Operations in Centrala:**
   - **`returns-list`** – View all customer return and complaint tickets.
   - **`returns-status`** – Update ticket status (`UNDER_REVIEW`, `APPROVED`, `REJECTED`, `COMPLETED`)."""
        else:
            return """### 🔄 Zwroty i Returns Service (:18094):

1. **Obsługa zwrotów i reklamacji:**
   - Klienci mogą składać wnioski o zwrot lub reklamację zakupionych produktów.
2. **Zarządzanie w Centrali:**
   - **`returns-list`** (w Operacjach) – podgląd wszystkich zgłoszeń klientów.
   - **`returns-status`** – zmiana statusu zgłoszenia (`UNDER_REVIEW`, `APPROVED`, `REJECTED`, `COMPLETED`)."""

    # 11. Ports and Routing
    if any(k in q for k in ["port", "porty", "ports", "endpoint", "adres", "url"]):
        if is_en:
            return """### 🌐 MiniTech Microservices & Infrastructure Ports:

| Component | Port | Description / Database |
| :--- | :---: | :--- |
| **Frontend Storefront** | `:18070` | Customer shop UI |
| **API Gateway** | `:18080` | Spring Cloud Gateway entry point |
| **Product Service** | `:18081` | Product catalog (`product_db`) |
| **Order Service** | `:18082` | Order management & Outbox (`order_db`) |
| **Inventory Service** | `:18083` | Stock & inventory lock (`inventory_db`) |
| **Payment Service** | `:18084` | Payment processing (`payment_db`) |
| **Notification Service** | `:18086` | Email dispatcher (`notification_db`) |
| **Customer Service** | `:18087` | User profile & JWT Auth (`customer_db`) |
| **Delivery Service** | `:18088` | Shipping tracking (`delivery_db`) |
| **Promotion Service** | `:18089` | Discount coupons (`promotion_db`) |
| **Wishlist Service** | `:18090` | User wishlists (`wishlist_db`) |
| **Review Service** | `:18091` | Customer product reviews (`review_db`) |
| **Recommendation Service** | `:18092` | Product recommendations (`recommendation_db`) |
| **Chat Service** | `:18093` | Live customer support (`chat_db`) |
| **Returns Service** | `:18094` | Returns & RMA (`returns_db`) |
| **Billing Service** | `:18095` | Invoices & Billing (`billing_db`) |
| **Control Center** | `:18100` | Local Web Dashboard (`scripts/start.sh`) |
| **Mailpit Web** | `:18025` | Test mailbox UI (SMTP port `:1025`) |
| **Prometheus** | `:19090` | Metrics scraper |
| **Grafana** | `:13000` | Monitoring dashboards |"""
        else:
            return """### 🌐 Wykaz portów mikroserwisów i infrastruktury MiniTech:

| Komponent | Port | Opis i Baza Danych |
| :--- | :---: | :--- |
| **Frontend Sklepu** | `:18070` | Interfejs klienta w przeglądarce |
| **API Gateway** | `:18080` | Główna brama wejściowa Spring Cloud Gateway |
| **Product Service** | `:18081` | Katalog produktów (`product_db`) |
| **Order Service** | `:18082` | Składanie zamówień + Outbox (`order_db`) |
| **Inventory Service** | `:18083` | Stany magazynowe i rezerwacje (`inventory_db`) |
| **Payment Service** | `:18084` | Płatności i symulacja transakcji (`payment_db`) |
| **Notification Service** | `:18086` | Wysyłka powiadomień e-mail (`notification_db`) |
| **Customer Service** | `:18087` | Rejestracja, logowanie i tokeny JWT (`customer_db`) |
| **Delivery Service** | `:18088` | Śledzenie dostaw i przesyłek (`delivery_db`) |
| **Promotion Service** | `:18089` | Kody rabatowe i promocje (`promotion_db`) |
| **Wishlist Service** | `:18090` | Listy życzeń klientów (`wishlist_db`) |
| **Review Service** | `:18091` | Opinie i oceny produktów (`review_db`) |
| **Recommendation Service** | `:18092` | Rekomendacje powiązanych produktów (`recommendation_db`) |
| **Chat Service** | `:18093` | Czat klienta ze sklepem (`chat_db`) |
| **Returns Service** | `:18094` | Obsługa zwrotów i reklamacji (`returns_db`) |
| **Billing Service** | `:18095` | Faktury i rozliczenia (`billing_db`) |
| **Centrala MiniTech** | `:18100` | Panel kontrolny Web (`bash ./scripts/start.sh`) |
| **Mailpit Web** | `:18025` | Skrzynka testowa e-mail (SMTP port `:1025`) |
| **Prometheus** | `:19090` | Zbieranie metryk aplikacji |
| **Grafana** | `:13000` | Pulpity monitoringu |"""

    # 8. Outbox & Redis Streams
    if any(k in q for k in ["outbox", "zdarzen", "event", "redis", "stream", "broker", "komunikacj"]):
        if is_en:
            return """### ⚡ Event-Driven Communication & Transactional Outbox:

1. **Transactional Outbox Pattern:**
   - When an event occurs (e.g., `OrderCreated`), the service saves both the entity state and the event record into the `outbox_events` table within a **single database transaction**.
   - This ensures atomicity: if the database transaction succeeds, the event is guaranteed to be recorded.

2. **Redis Streams as Message Broker:**
   - A background outbox poller reads pending events from `outbox_events` and publishes them to **Redis Streams** (`stream:events`).
   - Downstream microservices consume events using Redis Consumer Groups.

3. **Inspection & Control:**
   - In Centrala (Operations or CLI), run `stream-info` or `stream-read` to inspect recent event entries."""
        else:
            return """### ⚡ Komunikacja asynchroniczna i wzorzec Transactional Outbox:

1. **Wzorzec Transactional Outbox:**
   - Gdy dochodzi do zmiany stanu (np. utworzenie zamówienia w `Order Service`), rekord biznesowy oraz zdarzenie w tabeli `outbox_events` są zapisywane w **jednej atomowej transakcji SQL**.
   - Gwarantuje to brak utraty zdarzeń nawet przy awarii sieci lub brokera.

2. **Redis Streams jako broker zdarzeń:**
   - W tle wątek procesora Outbox odczytuje nieopublikowane wpisy i przesyła je do strumienia **Redis Streams** (`stream:events`).
   - Zainteresowane serwisy nasłuchują na strumieniu przy użyciu dedykowanych grup konsumentów (*Consumer Groups*).

3. **Diagnostyka w Centrali:**
   - W zakładce **Operacje** możesz podejrzeć strumień poleceniami: `stream-info`, `stream-read`, `dlq-list` i `dlq-replay`."""

    # 13. Testing & QA
    if any(k in q for k in ["test", "verify", "playwright", "smoke", "jakość", "e2e"]):
        if is_en:
            return """### 🧪 Testing & Quality Gates:

1. **App Verify (Purchasing Scenario):**
   - Tests end-to-end checkout: customer registration, product catalog, cart, stock reservation, payment processing, delivery creation, email notification.
   - Run from Dashboard tile **`App Verify`** or CLI: `app-verify`.

2. **Smoke Test:**
   - Runs fast health checks on all endpoints: `test-smoke`.

3. **Playwright Multi-Browser E2E:**
   - Automated UI browser regression across Chromium, Firefox, WebKit: `test-e2e`."""
        else:
            return """### 🧪 Testy i weryfikacja jakości platformy:

1. **App Verify (Kompletny scenariusz zakupowy):**
   - Weryfikuje pełną ścieżkę: rejestrację klienta, pobranie katalogu, koszyk, blokadę magazynową, symulację płatności, wygenerowanie dostawy i wysyłkę e-maila.
   - Kliknij kafelek **`App Verify`** na Pulpicie lub polecenie `app-verify` w Operacjach.

2. **Smoke Test:**
   - Szybka weryfikacja dostępności HTTP wszystkich endpointów: `test-smoke`.

3. **Playwright E2E (Wieloprzeglądarkowe testy UI):**
   - Automatyczne testy przeglądarkowe w Chromium, Firefox i WebKit: `test-e2e`."""

    # 14. Docker & Start/Stop
    if any(k in q for k in ["docker", "compose", "kontener", "uruchom", "start", "stop"]):
        if is_en:
            return """### 🐳 Docker & Running the Platform:

1. **Start all services in Docker:**
   - Click **`▶ Start Services (Docker)`** or **`🚀 1-Click Fresh Start`** in Centrala.
   - CLI command: `bash ./scripts/control-center/control-center.sh docker-apps-up`

2. **Start databases & infrastructure:**
   - Click **`◫ Databases`** in Centrala (`infra-up`).

3. **Stop services:**
   - Click **`⏹ Stop Services`** in Centrala (`docker-apps-down`)."""
        else:
            return """### 🐳 Uruchamianie platformy w Dockerze i procesach:

1. **Uruchomienie wszystkich 15 mikroserwisów i frontendu:**
   - Kliknij **`▶ Uruchom serwisy (Docker)`** lub **`🚀 Pełny start (1-Click)`** na Pulpicie.
   - Lub z terminala: `bash ./scripts/control-center/control-center.sh docker-apps-up`.

2. **Uruchomienie baz danych (PostgreSQL, Redis, Mailpit):**
   - Kliknij **`◫ Bazy danych`** w Centrali (`infra-up`).

3. **Zatrzymanie serwisów:**
   - Kliknij **`⏹ Zatrzymaj serwisy`** w Centrali (`docker-apps-down`)."""

    # 11. Security, Secrets & SOPS
    if any(k in q for k in ["secret", "sekret", "sops", "age", "hasł", "password", "jwt", "klucz"]):
        if is_en:
            return """### 🔐 Security, SOPS & Secret Management:

1. **Age Encryption:**
   - Secrets are encrypted with Mozilla SOPS using age recipient keys stored in `~/.config/mini-order-platform/keys/`.
2. **Encrypted Files:**
   - `dev.env.sops` and `test.env.sops` contain database passwords, JWT private keys, and external service credentials.
3. **Safe Editing in Centrala:**
   - Use the **`DEV Secrets`** or **`TEST Secrets`** quick actions in Centrala."""
        else:
            return """### 🔐 Zarządzanie sekretami i szyfrowanie SOPS:

1. **Szyfrowanie kluczami Age:**
   - Wszystkie hasła baz danych i klucze prywatne są szyfrowane narzędziem **Mozilla SOPS** przy użyciu kluczy `age` w `~/.config/mini-order-platform/keys/`.
2. **Pliki `.env.sops`:**
   - Profile `dev` i `test` posiadają własne pliki `dev.env.sops` i `test.env.sops`.
3. **Edycja w Centrali:**
   - Kliknij kafelki **`Sekrety DEV`** lub **`Sekrety TEST`** w Centrali."""

    if is_en:
        return f"""### ✦ MiniTech Assistant Response

I received your question about **Mini Order Platform**:
> *"{query}"*

**Key Architectural Context:**
- **Monorepo:** 15 Spring Boot 3.4 microservices (Java 25) + vanilla JS Storefront frontend (:18070).
- **Communication:** Synchronous REST through API Gateway (:18080) and Asynchronous Event-Driven via Redis Streams with Transactional Outbox pattern.
- **Databases:** 14 isolated PostgreSQL instances, encrypted secrets via Mozilla SOPS and age keys.
- **Control Center:** Web UI at `:18100` (`scripts/start.sh`) for Docker containers, tests, jobs, diagnostics, and customer chat.

*Tip: You can configure an external AI engine (OpenAI Codex, Google Gemini, or Cursor/Local LLM) in the **⚙ AI Settings** dialog above.*"""
    else:
        return f"""### ✦ Asystent MiniTech

Otrzymałem Twoje pytanie dotyczące projektu **Mini Order Platform**:
> *„{query}”*

**Kluczowe informacje o systemie:**
- **Struktura:** 15 mikroserwisów Spring Boot 3.4 (Java 25) + frontend sklepu (:18070).
- **Komunikacja:** Synchroniczny REST przez API Gateway (:18080) oraz asynchroniczne zdarzenia przez Redis Streams z wzorcem Transactional Outbox.
- **Bazy danych:** 14 niezależnych baz PostgreSQL z szyfrowaniem sekretów SOPS + age.
- **Centrala:** Panel kontrolny na porcie `:18100` (`scripts/start.sh`) do zarządzania kontenerami, bazami, logami, testami i obsługą klienta.

*Wskazówka: Możesz skonfigurować zewnętrzny silnik AI (OpenAI Codex, Google Gemini lub Cursor / Local LLM) w oknie **⚙ Ustawienia AI** na górze strony.*"""


def handle_ai_chat(message: str, history: list[dict[str, str]], lang: str = "pl") -> dict[str, Any]:
    cfg = load_ai_config()
    provider = cfg.get("provider", "antigravity")
    model = cfg.get("model", "antigravity-expert")
    api_key = cfg.get("apiKey", "").strip()
    custom_endpoint = cfg.get("customEndpoint", "http://localhost:11434/v1").rstrip("/")
    include_ctx = cfg.get("includeContext", True)

    system_prompt = get_project_system_prompt(lang) if include_ctx else (
        "You are an expert AI software engineering assistant." if lang == "en" else "Jesteś ekspertem i asystentem inżynierii oprogramowania."
    )

    # 1. Custom Endpoint / Cursor / Ollama (OpenAI compatible)
    if provider in {"cursor", "cursor_custom"} and custom_endpoint:
        url = f"{custom_endpoint}/chat/completions"
        messages = [{"role": "system", "content": system_prompt}]
        for item in history[-10:]:
            role = "user" if item.get("role") == "user" else "assistant"
            messages.append({"role": role, "content": str(item.get("content", ""))})
        messages.append({"role": "user", "content": message})
        body_bytes = json.dumps({"model": model or "default", "messages": messages, "temperature": 0.3}).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
        try:
            req = urllib.request.Request(url, data=body_bytes, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=45) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                reply = data.get("choices", [{}])[0].get("message", {}).get("content", "")
                return {"reply": reply, "provider": provider, "model": model}
        except Exception as error:
            return {"reply": f"⚠️ Błąd połączenia z endpointem Cursor ({url}): {error}\n\n" + generate_local_knowledge_reply(message, lang), "provider": provider, "model": model, "error": str(error)}

    # 2. OpenAI / Codex API
    elif provider == "codex" and api_key:
        url = "https://api.openai.com/v1/chat/completions"
        messages = [{"role": "system", "content": system_prompt}]
        for item in history[-10:]:
            role = "user" if item.get("role") == "user" else "assistant"
            messages.append({"role": role, "content": str(item.get("content", ""))})
        messages.append({"role": "user", "content": message})
        body_bytes = json.dumps({"model": model or "gpt-4o", "messages": messages, "temperature": 0.3}).encode("utf-8")
        headers = {"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"}
        try:
            req = urllib.request.Request(url, data=body_bytes, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=45) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                reply = data.get("choices", [{}])[0].get("message", {}).get("content", "")
                return {"reply": reply, "provider": provider, "model": model}
        except Exception as error:
            return {"reply": f"⚠️ Błąd OpenAI/Codex API: {error}\n\n" + generate_local_knowledge_reply(message, lang), "provider": provider, "model": model, "error": str(error)}

    # 3. Antigravity / Gemini with API key
    elif provider == "antigravity" and api_key:
        target_model = model if model != "antigravity-expert" else "gemini-2.5-flash"
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{target_model}:generateContent?key={api_key}"
        contents = [
            {"role": "user", "parts": [{"text": f"System context:\n{system_prompt}\n\nUser request:\n{message}"}]}
        ]
        body_bytes = json.dumps({"contents": contents}).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        try:
            req = urllib.request.Request(url, data=body_bytes, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=45) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                reply = data.get("candidates", [{}])[0].get("content", {}).get("parts", [{}])[0].get("text", "")
                return {"reply": reply, "provider": provider, "model": target_model}
        except Exception as error:
            return {"reply": f"⚠️ Błąd Gemini/Antigravity API: {error}\n\n" + generate_local_knowledge_reply(message, lang), "provider": provider, "model": target_model, "error": str(error)}

    # 4. Built-in Antigravity / Local Knowledge Engine
    reply = generate_local_knowledge_reply(message, lang)
    return {"reply": reply, "provider": provider, "model": model}


class Handler(SimpleHTTPRequestHandler):
    server_version = "MiniTechControl/1.0"

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, directory=str(WEB_ROOT), **kwargs)

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[control-web] {self.client_address[0]} {fmt % args}")

    def end_headers(self) -> None:
        # The panel is a local development tool. Never reuse stale JavaScript or
        # styles after it has been updated and the server has been restarted.
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        super().end_headers()

    def send_json(self, value: Any, status: int = 200) -> None:
        raw = json_bytes(value)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY:
            raise ValueError("Nieprawidłowy rozmiar żądania.")
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def require_token(self) -> bool:
        if self.headers.get("X-Control-Center-Token") != CSRF_TOKEN:
            self.send_json({"error": "Nieprawidłowy token lokalnej sesji."}, HTTPStatus.FORBIDDEN)
            return False
        return True

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/api/ping":
            self.send_json({"status": "UP", "profile": active_profile(), "language": active_language()})
            return
        if path == "/api/bootstrap":
            current_lang = active_language()
            self.send_json({
                "token": CSRF_TOKEN,
                "profile": active_profile(),
                "language": current_lang,
                "project": str(PROJECT_ROOT),
                "actions": build_actions(current_lang),
                "links": {
                    "store": "http://127.0.0.1:18070",
                    "mailpit": "http://127.0.0.1:18025",
                    "prometheus": "http://127.0.0.1:19090",
                    "grafana": "http://127.0.0.1:13000",
                },
            })
            return
        if path == "/api/ai/config":
            self.send_json(public_ai_config())
            return
        if path == "/api/knowledge-base/errors":
            self.send_json({"errors": load_error_database()})
            return
        if path == "/api/secrets":
            self.send_json({
                "profiles": public_secret_profiles(),
                "fields": SECRET_EDIT_FIELDS,
                "setupFields": SECRET_SETUP_FIELDS,
                "warning": "Puste pola pozostawiają dotychczasowe wartości bez zmian. Aktualne wartości nie są odczytywane ani zwracane.",
            })
            return
        if path == "/api/health":
            with ThreadPoolExecutor(max_workers=len(SERVICES)) as pool:
                result = list(pool.map(check_service, SERVICES))
            self.send_json({"services": result, "online": sum(1 for item in result if item["online"])})
            return
        if path == "/api/jobs":
            current_lang = active_language()
            with STATE_LOCK:
                jobs = sorted(JOBS.values(), key=lambda item: item.created_at, reverse=True)[:50]
            self.send_json({"jobs": [job.public(lang=current_lang) for job in jobs]})
            return
        if path.startswith("/api/jobs/"):
            job_id = path.rsplit("/", 1)[-1]
            job = JOBS.get(job_id)
            if not job:
                self.send_json({"error": "Nie znaleziono zadania."}, HTTPStatus.NOT_FOUND)
            else:
                self.send_json(job.public(include_output=True, lang=active_language()))
            return
        if path == "/api/chat/messages":
            try:
                self.send_json({"messages": api_json(f"{GATEWAY_API}/chat/messages")})
            except Exception as error:
                self.send_json({"error": f"Chat Service jest niedostępny: {error}"}, HTTPStatus.BAD_GATEWAY)
            return
        if path.startswith("/api/terminal/"):
            session_id = path.split("/")[3]
            session = TERMINALS.get(session_id)
            if not session:
                self.send_json({"error": "Sesja terminala nie istnieje."}, HTTPStatus.NOT_FOUND)
                return
            with session.lock:
                output = ANSI_RE.sub("", session.output.decode("utf-8", errors="replace"))
            self.send_json({"id": session.id, "alive": session.alive, "output": output[-150_000:]})
            return
        if path == "/api/data/orders":
            query_params = parse_qs(parsed.query)
            lim = int(query_params.get("limit", [500])[0]) if query_params.get("limit") else 500
            self.send_json({"orders": get_orders_data(limit=lim)})
            return
        if path == "/api/data/invoices/export-zip":
            resp = None
            for zip_url in [f"{GATEWAY_API}/invoices/export/zip", f"{BILLING_DIRECT_API}/invoices/export/zip"]:
                try:
                    req = urllib.request.Request(zip_url)
                    resp = urllib.request.urlopen(req, timeout=30.0)
                    break
                except Exception:
                    continue
            if not resp:
                self.send_json({"error": "Nie udało się pobrać archiwum ZIP z Billing Service."}, HTTPStatus.BAD_GATEWAY)
                return
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Disposition", 'attachment; filename="faktury.zip"')
            self.end_headers()
            try:
                while chunk := resp.read(65536):
                    self.wfile.write(chunk)
            finally:
                resp.close()
            return
        if path.startswith("/api/data/invoices/corrections/") and path.endswith("/pdf"):
            parts = path.strip("/").split("/")
            correction_id = parts[4]
            resp = None
            for pdf_url in [f"{GATEWAY_API}/invoices/corrections/{correction_id}/pdf", f"{BILLING_DIRECT_API}/invoices/corrections/{correction_id}/pdf"]:
                try:
                    req = urllib.request.Request(pdf_url)
                    resp = urllib.request.urlopen(req, timeout=10.0)
                    break
                except Exception:
                    continue
            if not resp:
                self.send_json({"error": "Nie udało się pobrać PDF korekty."}, HTTPStatus.BAD_GATEWAY)
                return
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Disposition", f'attachment; filename="KOR_{correction_id}.pdf"')
            self.end_headers()
            try:
                while chunk := resp.read(65536):
                    self.wfile.write(chunk)
            finally:
                resp.close()
            return
        if path == "/api/data/invoices":
            query_params = parse_qs(parsed.query)
            lim = int(query_params.get("limit", [500])[0]) if query_params.get("limit") else 500
            q = str(query_params.get("search", [""])[0]).strip()
            self.send_json(get_invoices_data(limit=lim, search=q))
            return
        if path == "/api/data/invoices/dispatch-history":
            self.send_json({"history": load_dispatch_history()})
            return
        if path == "/api/data/inventory":
            self.send_json({"inventory": get_inventory_data()})
            return
        if path == "/api/data/customers":
            self.send_json({"customers": get_customers_data()})
            return
        if path == "/api/events/stream":
            query_params = parse_qs(parsed.query)
            st_name = query_params.get("stream", ["payment-events"])[0]
            self.send_json({"stream": st_name, "events": read_redis_stream(stream_name=st_name)})
            return
        if path == "/api/events/outbox":
            self.send_json({"outbox": get_outbox_events()})
            return
        if path == "/api/events/dlq":
            self.send_json({"dlq": get_dlq_events()})
            return
        if path == "/api/trace":
            query_params = parse_qs(parsed.query)
            cid = query_params.get("correlationId", [""])[0]
            self.send_json({"correlationId": cid, "timeline": trace_correlation_id(cid)})
            return
        if path == "/api/chaos/status":
            self.send_json({"targets": get_chaos_status()})
            return
        if path == "/api/system/diagnostic-report":
            report_lines = [
                f"# 📋 Mini Order Platform — Raport Diagnostyczny Środowiska",
                f"> Wygenerowano: {time.strftime('%Y-%m-%d %H:%M:%S UTC', time.gmtime())}",
                f"> Profil: **{active_profile()}** | Język: **{active_language()}**",
                "",
                "## 🌐 Stan Mikroserwisów i Frontendu",
            ]
            with ThreadPoolExecutor(max_workers=len(SERVICES)) as pool:
                srv_res = list(pool.map(check_service, SERVICES))
            online_count = sum(1 for s in srv_res if s["online"])
            report_lines.append(f"**Podsumowanie:** {online_count}/{len(SERVICES)} mikroserwisów ONLINE\n")
            report_lines.append("| Mikroserwis | Port | Status | Czas odpowiedzi |")
            report_lines.append("| :--- | :--- | :--- | :--- |")
            for s in srv_res:
                st_icon = "🟢 UP" if s["online"] else "🔴 OFFLINE"
                report_lines.append(f"| {s['name']} | `{s['port']}` | {st_icon} | {s['latencyMs']} ms |")

            report_lines.extend([
                "",
                "## 🔒 Stan Skarbca Sekretów SOPS / Age",
            ])
            sec_profiles = public_secret_profiles()
            for prof_name, prof_data in sec_profiles.items():
                k_stat = "OK" if prof_data.get("key") else "BRAK"
                f_stat = "OK" if prof_data.get("file") else "BRAK"
                report_lines.append(f"- **Profil `{prof_name}`**: Klucz Age: `{k_stat}`, Plik SOPS: `{f_stat}`")

            report_lines.extend([
                "",
                "## 🐳 Ostatnie Zadania i Logi",
            ])
            with STATE_LOCK:
                recent_jobs = sorted(JOBS.values(), key=lambda j: j.created_at, reverse=True)[:5]
            if recent_jobs:
                for j in recent_jobs:
                    report_lines.append(f"- **{j.title}** (`{j.action_id}`): Status `{j.status}` (kod: `{j.exit_code}`)")
            else:
                report_lines.append("- Brak zarejestrowanych zadań w bieżącej sesji.")

            self.send_json({
                "markdown": "\n".join(report_lines),
                "timestamp": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime()),
                "profile": active_profile(),
                "language": active_language(),
                "onlineCount": online_count,
                "totalCount": len(SERVICES),
                "services": srv_res,
                "secretProfiles": sec_profiles,
                "recentJobs": [
                    {
                        "id": j.id,
                        "title": j.public(lang=active_language())["title"],
                        "actionId": j.action_id,
                        "status": j.status,
                        "exitCode": j.exit_code,
                        "createdAt": j.created_at,
                    }
                    for j in (recent_jobs if recent_jobs else [])
                ],
            })
            return
        super().do_GET()

    def do_POST(self) -> None:  # noqa: N802
        if not self.require_token():
            return
        path = urlparse(self.path).path
        try:
            payload = self.read_json()
        except (ValueError, json.JSONDecodeError) as error:
            self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
            return
        if path == "/api/language":
            lang = str(payload.get("language", "pl")).strip().lower()
            lang = "en" if lang in {"en", "english"} else "pl"
            LANGUAGE_FILE.parent.mkdir(parents=True, exist_ok=True)
            LANGUAGE_FILE.write_text(f"{lang}\n", encoding="utf-8")
            self.send_json({"ok": True, "language": lang, "actions": build_actions(lang)})
            return
        if path == "/api/secrets/setup":
            if payload.get("confirmed") is not True:
                self.send_json({"error": "Potwierdź utworzenie zaszyfrowanego pliku sekretów."}, HTTPStatus.CONFLICT)
                return
            try:
                profile = str(payload.get("profile", ""))
                created = setup_profile_secrets(profile, payload.get("values") or {})
            except ValueError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
                return
            except RuntimeError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_GATEWAY)
                return
            self.send_json({
                "ok": True,
                "profile": profile,
                "createdFields": created,
                "message": "Utworzono zaszyfrowany plik sekretów. Wartości nie zostały wyświetlone ani zapisane w logu zadania.",
            })
            return
        if path == "/api/secrets/update":
            if payload.get("confirmed") is not True:
                self.send_json({"error": "Potwierdź zapis zmienionych sekretów."}, HTTPStatus.CONFLICT)
                return
            try:
                updated = update_profile_secrets(str(payload.get("profile", "")), payload.get("values") or {})
            except ValueError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
                return
            except RuntimeError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_GATEWAY)
                return
            self.send_json({
                "ok": True,
                "profile": str(payload.get("profile", "")),
                "updatedFields": updated,
                "message": "Sekrety zapisano zaszyfrowane. Ich wartości nie zostały wyświetlone.",
            })
            return
        if path == "/api/jobs":
            action_id = str(payload.get("action", ""))
            selected = ACTION_BY_ID.get(action_id)
            if not selected:
                self.send_json({"error": "Operacja nie znajduje się na białej liście."}, HTTPStatus.BAD_REQUEST)
                return
            if selected.get("kind") in {"secret-edit", "secret-setup"}:
                self.send_json({"error": "Operacja sekretów wymaga dedykowanego formularza."}, HTTPStatus.BAD_REQUEST)
                return
            if selected["danger"] and payload.get("confirmed") is not True:
                self.send_json({"error": "Operacja wymaga potwierdzenia."}, HTTPStatus.CONFLICT)
                return
            values = payload.get("values") or {}
            args: list[str] = []
            for descriptor in selected["fields"]:
                name = descriptor["name"]
                if name in selected["stdinFields"]:
                    continue
                value = str(values.get(name, descriptor.get("default", ""))).strip()
                if descriptor.get("required") and not value:
                    self.send_json({"error": f"Pole „{descriptor['label']}” jest wymagane."}, HTTPStatus.BAD_REQUEST)
                    return
                if value:
                    args.append(value)
            stdin_text = "".join(str(values.get(name, "")) + "\n" for name in selected["stdinFields"])
            job_id = uuid.uuid4().hex[:12]
            command = ["bash", str(CONTROL_SCRIPT), selected["command"], *args]
            job = Job(job_id, action_id, selected["title"], command, JOB_ROOT / f"{job_id}.log")
            with STATE_LOCK:
                JOBS[job_id] = job
            threading.Thread(target=run_job, args=(job, stdin_text), daemon=True).start()
            self.send_json(job.public(), HTTPStatus.ACCEPTED)
            return
        if path.startswith("/api/chat/") and path.endswith("/reply"):
            parts = path.strip("/").split("/")
            try:
                message_id = int(parts[2])
                message = str(payload.get("message", "")).strip()
                if not message:
                    raise ValueError("Odpowiedź nie może być pusta.")
                result = api_json(f"{GATEWAY_API}/chat/messages/{message_id}/reply", method="POST", payload={"message": message})
                self.send_json({"message": result})
            except ValueError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
            except Exception as error:
                self.send_json({"error": f"Nie udało się wysłać odpowiedzi: {error}"}, HTTPStatus.BAD_GATEWAY)
            return
        if path == "/api/terminal":
            try:
                session = start_terminal()
                self.send_json({"id": session.id, "alive": True}, HTTPStatus.CREATED)
            except Exception as error:
                self.send_json({"error": f"Nie udało się uruchomić terminala: {error}"}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return
        if path.startswith("/api/terminal/") and path.endswith("/input"):
            session_id = path.split("/")[3]
            session = TERMINALS.get(session_id)
            if not session or not session.alive:
                self.send_json({"error": "Sesja terminala jest zamknięta."}, HTTPStatus.GONE)
                return
            data = str(payload.get("data", ""))
            os.write(session.fd, data.encode("utf-8"))
            self.send_json({"ok": True})
            return
        if path == "/api/ai/config":
            self.send_json(save_ai_config(payload))
            return
        if path == "/api/ai/chat":
            msg = str(payload.get("message", "")).strip()
            if not msg:
                self.send_json({"error": "Wiadomość nie może być pusta."}, HTTPStatus.BAD_REQUEST)
                return
            history = payload.get("history") or []
            lang = active_language()
            result = handle_ai_chat(msg, history, lang)
            self.send_json(result)
            return
        if path == "/api/data/inventory/restock":
            prod_id = int(payload.get("productId", 0))
            qty = int(payload.get("quantity", 10))
            if prod_id <= 0 or qty <= 0:
                self.send_json({"error": "Nieprawidłowe ID produktu lub ilość."}, HTTPStatus.BAD_REQUEST)
                return
            try:
                res = restock_inventory(prod_id, qty)
                self.send_json(res)
            except Exception as err:
                self.send_json({"error": str(err)}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return
        if path == "/api/events/dlq/replay":
            dlq_id = payload.get("id")
            if not dlq_id:
                self.send_json({"error": "Wymagane ID wpisu DLQ."}, HTTPStatus.BAD_REQUEST)
                return
            try:
                res = replay_dlq_entry(dlq_id)
                self.send_json(res)
            except Exception as err:
                self.send_json({"error": str(err)}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return
        if path == "/api/chaos/toggle":
            target = str(payload.get("target", "")).strip()
            action = str(payload.get("action", "")).strip()
            try:
                res = apply_chaos_action(target, action)
                self.send_json(res)
            except Exception as err:
                self.send_json({"error": str(err)}, HTTPStatus.INTERNAL_SERVER_ERROR)
        if path == "/api/data/orders/generate-invoice":
            order_id = payload.get("orderId")
            if not order_id:
                self.send_json({"error": "Brak parametru orderId."}, HTTPStatus.BAD_REQUEST)
                return
            result = generate_invoice_for_order(int(order_id))
            if result.get("success"):
                self.send_json(result)
            else:
                self.send_json(result, HTTPStatus.BAD_REQUEST)
            return
        if path == "/api/data/invoices/correct":
            invoice_id = payload.get("invoiceId")
            reason = str(payload.get("reason", "")).strip()
            delta = payload.get("correctionGross")
            if not invoice_id or not reason or delta is None:
                self.send_json({"error": "Wymagane pola: invoiceId, reason, correctionGross."}, HTTPStatus.BAD_REQUEST)
                return
            post_body = json.dumps({"reason": reason, "correctionGross": float(delta)}).encode("utf-8")
            resp_data = None
            err_msg = None
            for base_url in [f"{GATEWAY_API}/invoices/{invoice_id}/corrections", f"{BILLING_DIRECT_API}/invoices/{invoice_id}/corrections"]:
                try:
                    req = urllib.request.Request(base_url, data=post_body, headers={"Content-Type": "application/json"}, method="POST")
                    with urllib.request.urlopen(req, timeout=10.0) as resp:
                        resp_data = json.loads(resp.read().decode("utf-8"))
                        break
                except urllib.error.HTTPError as http_err:
                    try:
                        err_payload = json.loads(http_err.read().decode("utf-8"))
                        err_msg = err_payload.get("error") or err_payload.get("message")
                    except Exception:
                        err_msg = f"HTTP {http_err.code}"
                except Exception as exc:
                    err_msg = str(exc)
            if resp_data:
                self.send_json({"success": True, "correction": resp_data})
            else:
                self.send_json({"error": err_msg or "Nie udało się wystawić faktury korygującej."}, HTTPStatus.BAD_REQUEST)
            return
        if path == "/api/data/invoices/send-email":
            doc_type = payload.get("documentType", "INVOICE")
            doc_id = payload.get("documentId")
            order_id = payload.get("orderId")
            doc_number = str(payload.get("documentNumber", "")).strip()
            recipient = str(payload.get("recipient", "")).strip()
            subject = str(payload.get("subject", "")).strip()
            custom_message = str(payload.get("customMessage", "")).strip()
            buyer_name = str(payload.get("buyerName", "")).strip()

            if not doc_id or not recipient or not doc_number:
                self.send_json({"error": "Wymagane pola: documentId, documentNumber, recipient."}, HTTPStatus.BAD_REQUEST)
                return

            res = send_document_email(
                doc_type=doc_type,
                doc_id=doc_id,
                order_id=order_id,
                doc_number=doc_number,
                recipient=recipient,
                subject=subject,
                custom_message=custom_message,
                buyer_name=buyer_name,
            )
            if res.get("success"):
                self.send_json(res)
            else:
                self.send_json(res, HTTPStatus.BAD_REQUEST)
            return
        if path == "/api/system/reload":
            self.send_json({"ok": True, "message": "Serwer jest przeładowywany..."})
            threading.Thread(target=reload_server, daemon=True).start()
            return
        self.send_json({"error": "Nieznany endpoint."}, HTTPStatus.NOT_FOUND)


GLOBAL_SERVER: ThreadingHTTPServer | None = None


def reload_server() -> None:
    print("Przeładowywanie serwera Centrali...", flush=True)
    time.sleep(0.1)
    shutdown_terminals()
    try:
        if GLOBAL_SERVER:
            GLOBAL_SERVER.server_close()
    except Exception:
        pass
    import sys
    os.execv(sys.executable, [sys.executable] + sys.argv)


def shutdown_terminals() -> None:
    for session in list(TERMINALS.values()):
        if session.alive:
            try:
                os.kill(session.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass


def main() -> None:
    global GLOBAL_SERVER
    parser = argparse.ArgumentParser(description="MiniTech Control web panel")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18100)
    args = parser.parse_args()
    if args.host not in {"127.0.0.1", "localhost", "::1"}:
        raise SystemExit("Panel może nasłuchiwać wyłącznie na interfejsie loopback.")
    RUNTIME_ROOT.mkdir(parents=True, exist_ok=True)
    ThreadingHTTPServer.allow_reuse_address = True
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    GLOBAL_SERVER = server
    if hasattr(signal, "SIGHUP"):
        signal.signal(signal.SIGHUP, lambda signum, frame: reload_server())
    print(f"MiniTech Control: http://{args.host}:{args.port}", flush=True)
    print("Panel działa lokalnie. Ctrl+C zatrzymuje serwer.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        shutdown_terminals()
        server.server_close()


if __name__ == "__main__":
    main()
