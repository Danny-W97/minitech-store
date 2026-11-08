#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
base="${LOAD_API_BASE_URL:-http://127.0.0.1:18080}"
base="${base%/}"; base="${base%/api/v1}"
exec python3 "$SCRIPT_DIR/quality_gates.py" load --base-url "$base" --metrics-url "${LOAD_METRICS_URL:-http://127.0.0.1:18081}"
