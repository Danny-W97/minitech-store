#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${SECURITY_API_BASE_URL:-http://127.0.0.1:18080}"
BASE_URL="${BASE_URL%/}"
API_URL="$BASE_URL/api/v1"

command -v curl >/dev/null 2>&1 || { printf 'Brak wymaganej komendy: curl\n' >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { printf 'Brak wymaganej komendy: python3\n' >&2; exit 1; }

check_status() {
  local name="$1" expected="$2" url="$3"
  shift 3
  local actual
  actual="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
    -o /dev/null -w '%{http_code}' "$@" "$url" 2>/dev/null || true)"
  actual="${actual:-000}"
  if [[ "$actual" == "$expected" ]]; then
    printf '%-35s OK (HTTP %s)\n' "$name" "$actual"
  else
    printf '%-35s FAIL (expected HTTP %s, got %s)\n' "$name" "$expected" "$actual" >&2
    return 1
  fi
}

failures=0
check_status 'OpenAPI document' 200 "$BASE_URL/v3/api-docs" || failures=$((failures + 1))
check_status 'Actuator health' 200 "$BASE_URL/actuator/health" || failures=$((failures + 1))
check_status 'Protected customer profile' 401 "$API_URL/customers/me" || failures=$((failures + 1))
check_status 'Malformed bearer token' 401 "$API_URL/customers/me" \
  -H 'Authorization: Bearer not-a-jwt' || failures=$((failures + 1))
check_status 'Unexposed actuator env' 404 "$BASE_URL/actuator/env" || failures=$((failures + 1))

spec_file="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-security.XXXXXX")"
health_file="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-health.XXXXXX")"
trap 'rm -f -- "$spec_file" "$health_file"' EXIT

curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
  "$BASE_URL/actuator/health" > "$health_file" || failures=$((failures + 1))
if [[ -s "$health_file" ]]; then
  if python3 - "$health_file" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    document = json.load(handle)

allowed_keys = {"status", "groups"}
unexpected_keys = set(document) - allowed_keys
if document.get("status") != "UP" or unexpected_keys:
    print("Unexpected health payload keys/status: {} / {}".format(
        sorted(document), document.get("status")
    ))
    sys.exit(1)
if "components" in document or "details" in document:
    print("Health component details must not be exposed")
    sys.exit(1)
PY
  then
    printf '%-35s OK (status-only payload)\n' 'Actuator detail exposure'
  else
    printf '%-35s FAIL\n' 'Actuator detail exposure' >&2
    failures=$((failures + 1))
  fi
fi

curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
  "$BASE_URL/v3/api-docs" > "$spec_file" || failures=$((failures + 1))
if [[ -s "$spec_file" ]]; then
  if python3 - "$spec_file" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    document = json.load(handle)
serialized = json.dumps(document).lower()
for forbidden in ("customer_jwt_secret", "db_password", "refresh_token_hash"):
    if forbidden in serialized:
        print("Forbidden secret identifier exposed: {}".format(forbidden))
        sys.exit(1)
PY
  then
    printf '%-35s OK (no secret identifiers)\n' 'OpenAPI secret exposure'
  else
    printf '%-35s FAIL\n' 'OpenAPI secret exposure' >&2
    failures=$((failures + 1))
  fi
fi

if (( failures == 0 )); then
  printf 'Security test OK\n'
else
  printf 'Security test FAILED: %s problemów\n' "$failures" >&2
  exit 1
fi
