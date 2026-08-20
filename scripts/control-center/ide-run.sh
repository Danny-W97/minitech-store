#!/usr/bin/env bash
set -Euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVICE="${1:-}"
PROFILE="${2:-dev}"

case "$SERVICE" in
  gateway|api-gateway|product|order|inventory|payment|notification|customer|delivery|promotion|wishlist|review|recommendation|chat|returns|billing) ;;
  *)
    printf 'Uzycie: bash scripts/control-center/ide-run.sh <service> [dev|test]\n' >&2
    exit 2
    ;;
esac

case "$PROFILE" in
  dev|test) ;;
  *)
    printf 'Profil IDE musi byc dev albo test.\n' >&2
    exit 2
    ;;
esac

# IDE uses the same Control Center path as WSL. SOPS + age decrypt the
# selected profile only for the lifetime of the Maven/Spring process.
exec bash "$SCRIPT_DIR/control-center.sh" app-run "$SERVICE" "$PROFILE"
