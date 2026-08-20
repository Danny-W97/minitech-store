#!/usr/bin/env bash
set -Euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${1:-}" == "--cli" ]]; then
  shift
  exec bash "$SCRIPT_DIR/control-center/control-center.sh" "$@"
fi

# Preserve the stable direct-command interface used by CI, IntelliJ and docs.
if [[ $# -gt 0 && "${1:-}" != "--no-browser" && "${1:-}" != "--port" ]]; then
  exec bash "$SCRIPT_DIR/control-center/control-center.sh" "$@"
fi

exec bash "$SCRIPT_DIR/control-center/web/start-web.sh" "$@"
