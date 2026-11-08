#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
printf 'Isolated chaos: own databases, ports and JVMs; build all JARs first.\n'
exec python3 "$SCRIPT_DIR/access-control-e2e.py" --quality-gates --chaos-service "${1:-payment-service}" --port-base "${QA_PORT_BASE:-36180}"
