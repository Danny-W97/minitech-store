#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
base="${SECURITY_API_BASE_URL:-http://127.0.0.1:18080}"
exec python3 "$SCRIPT_DIR/quality_gates.py" security --base-url "$base"
