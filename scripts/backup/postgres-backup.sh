#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
CONTROL_CENTER="$PROJECT_ROOT/scripts/control-center/control-center.sh"

# This wrapper is intended for WSL cron. It uses the active dev/test profile,
# creates encrypted backups, and applies the configured retention policy.
bash "$CONTROL_CENTER" db-backup-all
bash "$CONTROL_CENTER" db-backup-prune
