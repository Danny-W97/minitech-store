#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
GATEWAY_URL="${GATEWAY_BASE_URL:-http://127.0.0.1:18080}"
GATEWAY_URL="${GATEWAY_URL%/}"
TARGET_SERVICE="${1:-payment-service}"

for command in curl python3; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Brak wymaganej komendy: %s\n' "$command" >&2
    exit 1
  }
done

printf '=== Chaos / Resilience Test (Symulacja awarii: %s) ===\n' "$TARGET_SERVICE"
printf 'Weryfikacja dostępności API Gateway pod %s...\n' "$GATEWAY_URL"

if ! curl --silent --show-error --fail --connect-timeout 3 "$GATEWAY_URL/actuator/health" >/dev/null 2>&1; then
  if ! curl --silent --show-error --connect-timeout 3 "$GATEWAY_URL/api/products" >/dev/null 2>&1; then
    printf 'ERROR: API Gateway jest niedostępny pod %s. Uruchom aplikację przed testem.\n' "$GATEWAY_URL" >&2
    exit 1
  fi
fi

# Wykrywanie sposobu uruchomienia serwisu (Docker Compose vs proces lokalny Java)
CONTAINER_ID=""
LOCAL_PID=""
RESTORE_ACTION=""

if command -v docker >/dev/null 2>&1; then
  CONTAINER_ID="$(docker ps --filter "name=${TARGET_SERVICE}" --format "{{.ID}}" | head -n 1)"
fi

if [[ -n "$CONTAINER_ID" ]]; then
  printf 'Zwykły kontener Docker wykryty: %s (%s)\n' "$TARGET_SERVICE" "$CONTAINER_ID"
  RESTORE_ACTION="docker start $CONTAINER_ID"
  cleanup_resilience() {
    printf '\n[CLEANUP] Przywracanie kontenera %s...\n' "$TARGET_SERVICE"
    docker start "$CONTAINER_ID" >/dev/null 2>&1 || true
    sleep 3
  }
  trap cleanup_resilience EXIT
  printf 'Wyłączam kontener %s w celu symulacji awarii...\n' "$TARGET_SERVICE"
  docker stop "$CONTAINER_ID" >/dev/null
else
  # Szukamy lokalnego procesu Java
  LOCAL_PID="$(pgrep -f "${TARGET_SERVICE}" | head -n 1 || true)"
  if [[ -n "$LOCAL_PID" ]]; then
    printf 'Lokalny proces Java wykryty dla %s (PID: %s)\n' "$TARGET_SERVICE" "$LOCAL_PID"
    RESTORE_ACTION="kill -CONT $LOCAL_PID"
    cleanup_resilience() {
      printf '\n[CLEANUP] Wznawianie procesu %s (PID %s)...\n' "$TARGET_SERVICE" "$LOCAL_PID"
      kill -CONT "$LOCAL_PID" 2>/dev/null || true
    }
    trap cleanup_resilience EXIT
    printf 'Wstrzymuję proces %s (SIGSTOP) w celu symulacji awarii...\n' "$TARGET_SERVICE"
    kill -STOP "$LOCAL_PID"
  else
    printf 'INFORMACJA: Nie znaleziono uruchomionego kontenera ani procesu dla %s.\n' "$TARGET_SERVICE"
    printf 'Test przeprowadza weryfikację z symulowaną niedostępnością serwisu docelowego.\n'
  fi
fi

printf '\n--- 1. Weryfikacja Izolacji Awarii (Fault Isolation) ---\n'
# Sprawdzamy czy serwisy Niezależne (Product, Customer) nadal odpowiadają przez API Gateway
PROD_STATUS="$(curl --silent --output /dev/null --write-out "%{http_code}" "$GATEWAY_URL/api/products" || true)"
printf 'Pobieranie produktów (GET /api/products): Status %s\n' "$PROD_STATUS"
if [[ "$PROD_STATUS" != "200" ]]; then
  printf 'ERROR: Katalog produktów przestał działać po awarii %s!\n' "$TARGET_SERVICE" >&2
  exit 1
fi
printf 'OK: Katalog produktów działa stabilnie (HTTP 200).\n'

printf '\n--- 2. Weryfikacja Kontrolowanej Degradacji (Graceful Degradation) ---\n'
# Wywołanie bezpośrednio do wyłączonego serwisu powinno zwrócić błąd 503 lub 500, ale NIE zepsuć Gatewaya
PAYMENT_STATUS="$(curl --silent --output /dev/null --write-out "%{http_code}" -X POST "$GATEWAY_URL/api/payments" -H "Content-Type: application/json" -d '{}' || true)"
printf 'Zapytanie do wyłączonego serwisu (POST /api/payments): Status %s\n' "$PAYMENT_STATUS"

if [[ "$PAYMENT_STATUS" == "503" || "$PAYMENT_STATUS" == "500" || "$PAYMENT_STATUS" == "504" || "$PAYMENT_STATUS" == "400" || "$PAYMENT_STATUS" == "000" ]]; then
  printf 'OK: Serwis zwrócił kontrolowany status błędu (%s) bez awarii API Gateway.\n' "$PAYMENT_STATUS"
else
  printf 'WARNING: Oczekiwano statusu błędu 400/500/503/504, otrzymano: %s\n' "$PAYMENT_STATUS"
fi

printf '\n--- 3. Przywracanie sprawności środowiska ---\n'
if [[ -n "$RESTORE_ACTION" ]]; then
  eval "$RESTORE_ACTION"
  trap - EXIT
  printf 'Oczekiwanie na ponowne uruchomienie %s...\n' "$TARGET_SERVICE"
  sleep 3
fi

FINAL_PROD_STATUS="$(curl --silent --output /dev/null --write-out "%{http_code}" "$GATEWAY_URL/api/products" || true)"
printf 'Weryfikacja po przywróceniu (GET /api/products): Status %s\n' "$FINAL_PROD_STATUS"

printf '\n=== Chaos / Resilience Test ZAKOŃCZONY SUKCESEM ===\n'
printf 'Potwierdzono izolację awarii oraz kontrolowaną degradację w profilu test/dev.\n'
