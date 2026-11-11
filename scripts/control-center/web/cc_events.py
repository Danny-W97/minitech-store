"""Control Center feature; runtime dependencies are supplied by the composition root."""
from __future__ import annotations
from typing import Any
import json
import re
import subprocess
import time

def read_redis_stream(services, stream_name: str = "payment-events", count: int = 40) -> list[dict[str, Any]]:
    # Resolve container name flexibly
    container = "miniorderplatform-event-broker-1"
    code_names, names_out, _ = services.run_docker_cli(["ps", "--format", "{{.Names}}"], timeout=2.0)
    if code_names == 0:
        for n in names_out.splitlines():
            if "event-broker" in n:
                container = n.strip()
                break

    # 1. First try modern redis-cli --json
    code, stdout, _ = services.run_docker_cli(
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
    code, stdout, _ = services.run_docker_cli(
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

def get_outbox_events(services, limit: int = 40) -> list[dict[str, Any]]:
    sql = (
        f"SELECT id, event_key, event_type, order_id, amount, recipient, status, attempts, "
        f"last_error, correlation_id, created_at, published_at "
        f"FROM payment_outbox_events ORDER BY id DESC LIMIT {limit}"
    )
    return services.psql_query_json("payment", sql)

def get_dlq_events(services, limit: int = 40) -> list[dict[str, Any]]:
    try:
        dlq = services.api_json("http://127.0.0.1:18086/notifications/dead-letter", timeout=2.0)
        if isinstance(dlq, list):
            return dlq[:limit]
    except Exception:
        pass
    sql = (
        f"SELECT id, notification_id, event_type, source_id, recipient, channel, subject, "
        f"message, status, failure_reason, attempt_count, created_at, resolved_at "
        f"FROM notification_dead_letters ORDER BY id DESC LIMIT {limit}"
    )
    return services.psql_query_json("notification", sql)

def replay_dlq_entry(services, dlq_id: int | str) -> dict[str, Any]:
    try:
        res = services.api_json(f"http://127.0.0.1:18086/notifications/dead-letter/{dlq_id}/replay", method="POST", timeout=3.0)
        return {"ok": True, "id": dlq_id, "result": res, "method": "REST"}
    except Exception:
        pass

    cmd = ["bash", str(services.CONTROL_SCRIPT), "dlq-replay", str(dlq_id)]
    try:
        proc = subprocess.run(cmd, cwd=services.PROJECT_ROOT, capture_output=True, text=True, timeout=10.0)
        if proc.returncode == 0:
            return {"ok": True, "id": dlq_id, "output": proc.stdout, "method": "CLI"}
        raise RuntimeError(proc.stderr or proc.stdout)
    except Exception as exc:
        raise RuntimeError(f"Błąd podczas ponawiania DLQ: {exc}")

def parse_trace_log_line(services, service: str, line: str) -> dict[str, Any]:
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

def trace_correlation_id(services, correlation_id: str) -> list[dict[str, Any]]:
    cid = correlation_id.strip()
    if not cid:
        return []

    events: list[dict[str, Any]] = []

    log_dir = services.PROJECT_ROOT / "gitIgnoreOwn" / "runtime" / "logs"
    if log_dir.is_dir():
        for log_file in log_dir.glob("*.log"):
            service_name = log_file.stem
            try:
                content = log_file.read_text(encoding="utf-8", errors="replace")
                for line in content.splitlines():
                    if cid in line:
                        events.append(services.parse_trace_log_line(service_name, line))
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
        code, out, _ = services.run_docker_cli(["logs", "--tail", "1500", c_name], timeout=3.0)
        if code == 0 and cid in out:
            for line in out.splitlines():
                if cid in line:
                    events.append(services.parse_trace_log_line(srv_alias, line))

    seen: set[str] = set()
    unique_events: list[dict[str, Any]] = []
    for ev in events:
        key = f"{ev['service']}-{ev['timestamp']}-{ev['message'][:40]}"
        if key not in seen:
            seen.add(key)
            unique_events.append(ev)

    unique_events.sort(key=lambda x: x.get("timestamp") or "")
    return unique_events
