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

# Automatyczne sprawdzenie i uruchomienie bazy danych przed startem serwisu
ensure_service_db() {
  local svc="$1"
  local port=""
  case "$svc" in
    product|product-service) port="15433" ;;
    order|order-service) port="15432" ;;
    inventory|inventory-service) port="15434" ;;
    payment|payment-service) port="15435" ;;
    notification|notification-service) port="15436" ;;
    customer|customer-service) port="15437" ;;
    delivery|delivery-service) port="15438" ;;
    promotion|promotion-service) port="15439" ;;
    wishlist|wishlist-service) port="15440" ;;
    review|review-service) port="15441" ;;
    recommendation|recommendation-service) port="15442" ;;
    chat|chat-service) port="15443" ;;
    returns|returns-service) port="15444" ;;
    billing|billing-service) port="15445" ;;
    gateway|api-gateway) return 0 ;;
  esac

  if [[ -n "$port" ]]; then
    if ! bash -c "</dev/tcp/127.0.0.1/$port" 2>/dev/null; then
      printf 'Baza danych dla %s (port %s) nie odpowiada. Uruchamiam infrastrukture (infra-up)...\n' "$svc" "$port"
      bash "$SCRIPT_DIR/control-center.sh" infra-up >/dev/null 2>&1 || true

      local attempts=0
      while ! bash -c "</dev/tcp/127.0.0.1/$port" 2>/dev/null; do
        sleep 1
        attempts=$((attempts + 1))
        if (( attempts > 30 )); then
          printf 'Ostrzezenie: Baza danych na porcie %s nadal nie odpowiada po 30s.\n' "$port" >&2
          break
        fi
      done
      if bash -c "</dev/tcp/127.0.0.1/$port" 2>/dev/null; then
        printf 'Baza danych (port %s) jest gotowa i przyjmuje polaczenia.\n' "$port"
      fi
    fi
  fi
}

ensure_service_db "$SERVICE"

# IDE uses the same Control Center path as WSL. SOPS + age decrypt the
# selected profile only for the lifetime of the Maven/Spring process.
exec bash "$SCRIPT_DIR/control-center.sh" app-run "$SERVICE" "$PROFILE"
