#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
API_URL="${E2E_API_BASE_URL:-http://127.0.0.1:18080/api/v1}"
API_URL="${API_URL%/}"
MAILPIT_URL="${E2E_MAILPIT_URL:-http://127.0.0.1:18025}"
MAILPIT_URL="${MAILPIT_URL%/}"

for command in curl python3 bash; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Brak wymaganej komendy: %s\n' "$command" >&2
    exit 1
  }
done

temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/mini-order-platform-e2e.XXXXXX")"
chmod 700 "$temp_dir"
trap 'rm -rf -- "$temp_dir"' EXIT

response_file="$temp_dir/response.json"
cookie_file="$temp_dir/cookies.txt"
touch "$response_file" "$cookie_file"
chmod 600 "$response_file" "$cookie_file"

timestamp="$(date +%s)"
email="e2e-${timestamp}-$$@minitech.local"
password="E2ePassword-${timestamp}!"

json_register="$(E2E_EMAIL="$email" E2E_PASSWORD="$password" python3 - <<'PY'
import json
import os

print(json.dumps({
    "email": os.environ["E2E_EMAIL"],
    "password": os.environ["E2E_PASSWORD"],
    "firstName": "E2E",
    "lastName": "Tester",
    "street": "Testowa 1",
    "postalCode": "00-001",
    "city": "Warszawa",
    "country": "Polska",
}))
PY
)"

status="$(curl --silent --show-error --connect-timeout 3 --max-time 15 \
  -o "$response_file" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -X POST "$API_URL/customers/register" --data "$json_register" 2>/dev/null || true)"
status="${status:-000}"
[[ "$status" == "201" ]] || {
  printf 'E2E FAIL: rejestracja zwróciła HTTP %s\n' "$status" >&2
  cat "$response_file" >&2 || true
  exit 1
}
printf 'Rejestracja: OK (%s)\n' "$email"

verification_token=""
for attempt in {1..20}; do
  search_response="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
    --get "$MAILPIT_URL/api/v1/search" \
    --data-urlencode "query=to:${email}" 2>/dev/null || true)"
  message_id="$(printf '%s' "$search_response" | python3 -c '
import json, sys
try:
    messages = json.load(sys.stdin).get("messages", [])
    print(messages[0].get("ID", "") if messages else "")
except Exception:
    print("")
')"
  if [[ -n "$message_id" ]]; then
    message_text="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
      "$MAILPIT_URL/view/${message_id}.txt" 2>/dev/null || true)"
    verification_token="$(printf '%s' "$message_text" | python3 -c '
import re, sys
match = re.search(r"verify=([A-Za-z0-9_-]{20,})", sys.stdin.read())
print(match.group(1) if match else "")
')"
    [[ -n "$verification_token" ]] && break
  fi
  sleep 1
done

[[ -n "$verification_token" ]] || {
  printf 'E2E FAIL: nie znaleziono linku potwierdzającego w Mailpit.\n' >&2
  exit 1
}

status="$(curl --silent --show-error --connect-timeout 3 --max-time 15 \
  -o "$response_file" -w '%{http_code}' \
  "$API_URL/customers/verify?token=$verification_token" 2>/dev/null || true)"
status="${status:-000}"
[[ "$status" == "200" ]] || {
  printf 'E2E FAIL: potwierdzenie konta zwróciło HTTP %s\n' "$status" >&2
  exit 1
}
printf 'Potwierdzenie konta przez Mailpit: OK\n'

json_login="$(E2E_EMAIL="$email" E2E_PASSWORD="$password" python3 - <<'PY'
import json
import os
print(json.dumps({"email": os.environ["E2E_EMAIL"], "password": os.environ["E2E_PASSWORD"]}))
PY
)"
status="$(curl --silent --show-error --connect-timeout 3 --max-time 15 \
  -o "$response_file" -w '%{http_code}' -c "$cookie_file" \
  -H 'Content-Type: application/json' \
  -X POST "$API_URL/customers/login" --data "$json_login" 2>/dev/null || true)"
status="${status:-000}"
[[ "$status" == "200" ]] || {
  printf 'E2E FAIL: logowanie zwróciło HTTP %s\n' "$status" >&2
  exit 1
}
access_token="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken", ""))' < "$response_file")"
[[ -n "$access_token" ]] || { printf 'E2E FAIL: brak access tokenu.\n' >&2; exit 1; }
printf 'Logowanie JWT: OK (token ukryty)\n'

status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
  -o "$response_file" -w '%{http_code}' \
  -H "Authorization: Bearer $access_token" "$API_URL/customers/me" 2>/dev/null || true)"
status="${status:-000}"
[[ "$status" == "200" ]] || { printf 'E2E FAIL: /customers/me HTTP %s\n' "$status" >&2; exit 1; }
printf 'JWT /me: OK\n'

status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
  -o "$response_file" -w '%{http_code}' \
  "$API_URL/recommendations/products/${E2E_PRODUCT_ID:-4}?limit=4" 2>/dev/null || true)"
status="${status:-000}"
[[ "$status" == "200" ]] || {
  printf 'E2E FAIL: rekomendacje produktu HTTP %s\n' "$status" >&2
  cat "$response_file" >&2 || true
  exit 1
}
printf 'Recommendation Service przez Gateway: OK\n'

status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
  -o "$response_file" -w '%{http_code}' -b "$cookie_file" -c "$cookie_file" \
  -X POST "$API_URL/customers/refresh" 2>/dev/null || true)"
status="${status:-000}"
[[ "$status" == "200" ]] || { printf 'E2E FAIL: refresh token HTTP %s\n' "$status" >&2; exit 1; }
access_token="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken", ""))' < "$response_file")"
[[ -n "$access_token" ]] || { printf 'E2E FAIL: refresh nie zwrócił access tokenu.\n' >&2; exit 1; }
printf 'RefreshToken: OK\n'

status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
  -o /dev/null -w '%{http_code}' -b "$cookie_file" -c "$cookie_file" \
  -X POST "$API_URL/customers/logout" 2>/dev/null || true)"
status="${status:-000}"
[[ "$status" == "204" ]] || { printf 'E2E FAIL: logout HTTP %s\n' "$status" >&2; exit 1; }
printf 'Wylogowanie serwerowe: OK\n'

printf '\nUruchamiam istniejący scenariusz zakupu MVP...\n'
export APP_VERIFY_PRODUCT_ID="${E2E_PRODUCT_ID:-4}"
export APP_VERIFY_QUANTITY="${E2E_QUANTITY:-1}"
bash "$PROJECT_ROOT/scripts/control-center/control-center.sh" app-verify

printf 'Full E2E zakończony powodzeniem: rejestracja, Mailpit, JWT, refresh, logout i zakup.\n'
