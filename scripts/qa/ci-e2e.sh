#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
MAVEN_WRAPPER="$PROJECT_ROOT/mvnw"
DB_PASSWORD_VALUE="${CI_DB_PASSWORD:-ci-db-password}"
JWT_SECRET_VALUE="${CUSTOMER_JWT_SECRET:-c29tZS10ZXN0LWp3dC1zZWNyZXQta2V5LWxvbmcgZW5vdWdo}"
COMPOSE_PROJECT_NAME="${CI_COMPOSE_PROJECT_NAME:-mini-order-platform-ci}"
RUNTIME_DIRECTORY="$PROJECT_ROOT/gitIgnoreOwn/runtime"
APP_PIDS=()

cleanup() {
  local pid
  for pid in "${APP_PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  docker compose -p "$COMPOSE_PROJECT_NAME" -f "$PROJECT_ROOT/compose.yaml" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

for command in docker curl python3; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Brak wymaganej komendy CI E2E: %s\n' "$command" >&2
    exit 1
  }
done

export PRODUCT_DB_PASSWORD="$DB_PASSWORD_VALUE"
export ORDER_DB_PASSWORD="$DB_PASSWORD_VALUE"
export INVENTORY_DB_PASSWORD="$DB_PASSWORD_VALUE"
export PAYMENT_DB_PASSWORD="$DB_PASSWORD_VALUE"
export NOTIFICATION_DB_PASSWORD="$DB_PASSWORD_VALUE"
export CUSTOMER_DB_PASSWORD="$DB_PASSWORD_VALUE"
export DELIVERY_DB_PASSWORD="$DB_PASSWORD_VALUE"
export PROMOTION_DB_PASSWORD="$DB_PASSWORD_VALUE"
export WISHLIST_DB_PASSWORD="$DB_PASSWORD_VALUE"
export REVIEW_DB_PASSWORD="$DB_PASSWORD_VALUE"
export RECOMMENDATION_DB_PASSWORD="$DB_PASSWORD_VALUE"
export CHAT_DB_PASSWORD="$DB_PASSWORD_VALUE"
export RETURNS_DB_PASSWORD="$DB_PASSWORD_VALUE"
export BILLING_DB_PASSWORD="$DB_PASSWORD_VALUE"

docker compose -p "$COMPOSE_PROJECT_NAME" -f "$PROJECT_ROOT/compose.yaml" up -d

start_service() {
  local project="$1"
  local db_password="${2:-}"
  local jar
  jar="$(find "$PROJECT_ROOT/$project/target" -maxdepth 1 -type f \
        -name '*-SNAPSHOT.jar' ! -name '*.original' | head -1)"
  if [[ -z "$jar" ]]; then
    printf 'CI E2E: brak zbudowanego JAR-a dla %s — uruchamiam spring-boot:run\n' "$project" >&2
    (
      cd "$PROJECT_ROOT"
      SPRING_PROFILES_ACTIVE=dev \
      DB_PASSWORD="$db_password" \
      CUSTOMER_JWT_SECRET="$JWT_SECRET_VALUE" \
      MAIL_HOST=127.0.0.1 MAIL_PORT=11025 MAIL_FROM=no-reply@minitech.local \
      bash "$MAVEN_WRAPPER" -q -pl "$project" spring-boot:run
    ) > "$RUNTIME_DIRECTORY/ci-${project}.log" 2>&1 &
    APP_PIDS+=("$!")
    return
  fi
  (
    SPRING_PROFILES_ACTIVE=dev \
    DB_PASSWORD="$db_password" \
    CUSTOMER_JWT_SECRET="$JWT_SECRET_VALUE" \
    MAIL_HOST=127.0.0.1 MAIL_PORT=11025 MAIL_FROM=no-reply@minitech.local \
    java -XX:MaxRAMPercentage=50.0 -jar "$jar"
  ) > "$RUNTIME_DIRECTORY/ci-${project}.log" 2>&1 &
  APP_PIDS+=("$!")
}

mkdir -p "$RUNTIME_DIRECTORY"
start_service product-service "$DB_PASSWORD_VALUE"
start_service inventory-service "$DB_PASSWORD_VALUE"
start_service order-service "$DB_PASSWORD_VALUE"
start_service payment-service "$DB_PASSWORD_VALUE"
start_service notification-service "$DB_PASSWORD_VALUE"
start_service customer-service "$DB_PASSWORD_VALUE"
start_service delivery-service "$DB_PASSWORD_VALUE"
start_service promotion-service "$DB_PASSWORD_VALUE"
start_service wishlist-service "$DB_PASSWORD_VALUE"
start_service review-service "$DB_PASSWORD_VALUE"
start_service recommendation-service "$DB_PASSWORD_VALUE"
start_service chat-service "$DB_PASSWORD_VALUE"
start_service returns-service "$DB_PASSWORD_VALUE"
start_service billing-service "$DB_PASSWORD_VALUE"
start_service api-gateway

(
  cd "$PROJECT_ROOT"
  exec python3 scripts/frontend-server.py --bind 127.0.0.1 --port 18070 --directory frontend
) > "$RUNTIME_DIRECTORY/ci-frontend.log" 2>&1 &
APP_PIDS+=("$!")

wait_for() {
  local name="$1" url="$2" elapsed=0
  while (( elapsed < 180 )); do
    if curl --silent --show-error --fail --connect-timeout 2 --max-time 5 "$url" >/dev/null 2>&1; then
      printf '%-15s READY\n' "$name"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  printf '%-15s TIMEOUT\n' "$name" >&2
  return 1
}

wait_for frontend "http://127.0.0.1:18070/index.html"
wait_for product "http://127.0.0.1:18081/products"
wait_for inventory "http://127.0.0.1:18083/inventory/items/1"
wait_for order "http://127.0.0.1:18082/orders"
wait_for payment "http://127.0.0.1:18084/payments"
wait_for notification "http://127.0.0.1:18086/notifications"
wait_for customer "http://127.0.0.1:18087/actuator/health"
wait_for delivery "http://127.0.0.1:18088/deliveries/methods"
wait_for promotion "http://127.0.0.1:18089/promotions/active"
wait_for wishlist "http://127.0.0.1:18090/actuator/health"
wait_for review "http://127.0.0.1:18091/actuator/health"
wait_for recommendation "http://127.0.0.1:18092/actuator/health"
wait_for chat "http://127.0.0.1:18093/actuator/health"
wait_for returns "http://127.0.0.1:18094/actuator/health"
wait_for billing "http://127.0.0.1:18095/actuator/health"
wait_for gateway "http://127.0.0.1:18080/actuator/health"
wait_for mailpit "http://127.0.0.1:18025/api/v1/info"

bash "$SCRIPT_DIR/contract-test.sh"
bash "$SCRIPT_DIR/security-test.sh"
bash "$SCRIPT_DIR/zap-dast.sh"
bash "$SCRIPT_DIR/load-test.sh"
bash "$SCRIPT_DIR/e2e.sh"

if command -v pnpm >/dev/null 2>&1 && [[ -d "$PROJECT_ROOT/node_modules/@playwright/test" ]]; then
  (
    cd "$PROJECT_ROOT"
    PLAYWRIGHT_FULL_SYSTEM=1 pnpm exec playwright test e2e/tests/full-system --project=chromium
  )
else
  printf 'CI E2E FAIL: Playwright/pnpm nie jest zainstalowany.\n' >&2
  exit 1
fi

printf 'CI E2E zakończony powodzeniem.\n'
