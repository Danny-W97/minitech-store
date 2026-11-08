"""Control Center feature; runtime dependencies are supplied by the composition root."""
from __future__ import annotations
from typing import Any
import json
import shutil
import subprocess
import time
import urllib.request

def run_docker_cli(services, args: list[str], timeout: float = 5.0) -> tuple[int, str, str]:
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

def get_docker_stats(services) -> dict[str, dict[str, str]]:
    with services.DOCKER_STATS_LOCK:
        now = time.monotonic()
        if now - services.DOCKER_STATS_CACHE["timestamp"] < 5.0 and services.DOCKER_STATS_CACHE["data"]:
            return services.DOCKER_STATS_CACHE["data"]

        code, stdout, _ = services.run_docker_cli(
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
        services.DOCKER_STATS_CACHE["timestamp"] = now
        services.DOCKER_STATS_CACHE["data"] = stats
        return stats

def check_service(services, item: tuple[str, str, int]) -> dict[str, Any]:
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

    stats = services.get_docker_stats()
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

def psql_query_json(services, db_alias: str, query: str, timeout: float = 4.0) -> list[dict[str, Any]]:
    # A failed/denied REST call must not bypass authorization through SQL fallbacks.
    session = services.api_json(f"{services.GATEWAY_API}/customers/operator-session", timeout=3.0)
    if not isinstance(session, dict) or session.get("role") != "OPERATOR":
        raise PermissionError("Wymagana zweryfikowana sesja operatora.")
    container = f"miniorderplatform-{db_alias}-db-1"
    user = f"{db_alias}_app"
    db_name = f"{db_alias}_db"
    sql = f"SELECT coalesce(json_agg(t), '[]'::json) FROM ({query}) t;"
    code, stdout, _ = services.run_docker_cli(
        ["exec", "-i", container, "psql", "-U", user, "-d", db_name, "-t", "-A", "-c", sql],
        timeout=timeout,
    )
    if code != 0 or not stdout.strip():
        return []
    try:
        return json.loads(stdout.strip())
    except Exception:
        return []
