"""Control Center feature; runtime dependencies are supplied by the composition root."""
from __future__ import annotations
from typing import Any

def get_chaos_status(services) -> dict[str, Any]:
    code, stdout, _ = services.run_docker_cli(["ps", "-a", "--format", "{{.Names}}\t{{.Status}}"])
    lines = stdout.strip().splitlines() if code == 0 else []
    c_status: dict[str, str] = {}
    for line in lines:
        parts = line.split("\t", 1)
        if len(parts) == 2:
            c_status[parts[0].strip()] = parts[1].strip()

    result = {}
    for key, info in services.CHAOS_TARGETS.items():
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

def apply_chaos_action(services, target: str, action: str) -> dict[str, Any]:
    if action == "restore_all" or target == "restore_all":
        for info in services.CHAOS_TARGETS.values():
            services.run_docker_cli(["unpause", info["container"]])
            services.run_docker_cli(["start", info["container"]])
        return {"ok": True, "message": "Przywrócono wszystkie kontenery."}

    info = services.CHAOS_TARGETS.get(target)
    if not info:
        raise ValueError(f"Nieznany cel chaosu: {target}")

    c_name = info["container"]
    if action == "pause":
        code, out, err = services.run_docker_cli(["pause", c_name])
    elif action == "unpause":
        code, out, err = services.run_docker_cli(["unpause", c_name])
    elif action == "stop":
        code, out, err = services.run_docker_cli(["stop", c_name])
    elif action == "start":
        code, out, err = services.run_docker_cli(["start", c_name])
    else:
        raise ValueError(f"Nieznana akcja chaosu: {action}")

    if code != 0:
        raise RuntimeError(f"Błąd operacji Docker ({action} {c_name}): {err or out}")

    return {"ok": True, "target": target, "action": action, "status": services.get_chaos_status().get(target)}
