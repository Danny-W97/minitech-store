"""Control Center feature; runtime dependencies are supplied by the composition root."""
from __future__ import annotations
from typing import Any
from datetime import datetime
import json
import os
import re
import shlex
import subprocess
import urllib.request
from urllib.parse import urlparse

def operator_credential(services) -> str:
    configured = os.environ.get("PLATFORM_OPERATOR_TOKEN")
    if configured:
        return configured
    profile = services.active_profile()
    root = services.secret_root_directory()
    encrypted = root / "secrets" / f"{profile}.env.sops"
    key = root / "keys" / f"{profile}.agekey"
    stamp = (profile, encrypted.stat().st_mtime_ns)
    with services.OPERATOR_CREDENTIAL_LOCK:
        if services.OPERATOR_CREDENTIAL_CACHE.get("stamp") == stamp:
            return services.OPERATOR_CREDENTIAL_CACHE["token"]
        env = dict(os.environ, SOPS_AGE_KEY_FILE=str(key))
        result = subprocess.run(["sops", "--decrypt", "--input-type", "dotenv", "--output-type", "dotenv", str(encrypted)],
                                env=env, capture_output=True, text=True, timeout=10, check=True)
        token = None
        for line in result.stdout.splitlines():
            if line.startswith("PLATFORM_OPERATOR_TOKEN="):
                token = shlex.split(line)[0].split("=", 1)[1]
                break
        if not token:
            raise RuntimeError("Brak uprawnień operatora. Uruchom platformę, aby uzupełnić zaszyfrowany profil.")
        services.OPERATOR_CREDENTIAL_CACHE.update(stamp=stamp, token=token)
        return token

def platform_request(services, url, *args, **kwargs):
    request = urllib.request.Request(url, *args, **kwargs)
    parsed = urlparse(url)
    platform_ports = {18080, 18081, 18082, 18083, 18084, *range(18086, 18096)}
    if parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost"} and parsed.port in platform_ports:
        request.add_unredirected_header("Authorization", "Bearer " + services.operator_credential())
    return request

def api_json(services, url: str, *, method: str = "GET", payload: Any = None, timeout: float = 10) -> Any:
    data = None if payload is None else services.json_bytes(payload)
    request = services.platform_request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read()
        return json.loads(raw.decode("utf-8")) if raw else None

def export_params(services, query: dict) -> dict:
    result = {}
    for key in ("from", "to"):
        value = str(query.get(key, [""])[0]).strip()
        if not value:
            continue
        if len(value) > 64:
            raise ValueError("Nieprawidłowa data eksportu.")
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.utcoffset() is None:
            raise ValueError("Data eksportu wymaga strefy czasowej.")
        result[key] = parsed.isoformat()
    if "from" in result and "to" in result and datetime.fromisoformat(result["from"]) > datetime.fromisoformat(result["to"]):
        raise ValueError("Początek zakresu nie może być późniejszy niż koniec.")
    return result

def list_params(services, query: dict) -> dict:
    cursor = str(query.get("cursor", ["0"])[0])
    size = str(query.get("size", ["50"])[0])
    if not re.fullmatch(r"[0-9]{1,19}", cursor) or int(cursor) > 9223372036854775807:
        raise ValueError("Nieprawidłowy kursor.")
    if not re.fullmatch(r"[0-9]{1,3}", size) or not 1 <= int(size) <= 200:
        raise ValueError("Rozmiar strony musi wynosić od 1 do 200.")
    result = {"cursor": cursor, "size": size}
    for key in ("search", "status"):
        value = str(query.get(key, [""])[0]).strip()
        if len(value) > 200:
            raise ValueError("Filtr jest zbyt długi (maks. 200 znaków).")
        if value and value != "ALL":
            result[key] = value
    return result

def api_page(services, url: str, params: dict) -> dict[str, Any]:
    request = services.platform_request(url + "?" + urllib.parse.urlencode(params), headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        items = json.loads(response.read().decode("utf-8"))
        if not isinstance(items, list) or len(items) > int(params["size"]):
            raise ValueError("Nieprawidłowa odpowiedź stronicowanego API.")
        more = response.headers.get("X-Has-More") == "true"
        cursor = response.headers.get("X-Next-Cursor", "0")
        if more and not re.fullmatch(r"[1-9][0-9]{0,18}", cursor):
            raise ValueError("Brak kursora kolejnej strony.")
        return {"items": items, "hasMore": more, "nextCursor": cursor, "size": int(params["size"])}
