#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${LOAD_API_BASE_URL:-http://127.0.0.1:18080/api/v1}"
BASE_URL="${BASE_URL%/}"
REQUESTS="${LOAD_REQUESTS:-100}"
CONCURRENCY="${LOAD_CONCURRENCY:-10}"

for command in curl python3; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Brak wymaganej komendy: %s\n' "$command" >&2
    exit 1
  }
done

[[ "$REQUESTS" =~ ^[0-9]+$ && "$REQUESTS" -gt 0 ]] || {
  printf 'LOAD_REQUESTS musi być dodatnią liczbą.\n' >&2
  exit 2
}
[[ "$CONCURRENCY" =~ ^[0-9]+$ && "$CONCURRENCY" -gt 0 && "$CONCURRENCY" -le "$REQUESTS" ]] || {
  printf 'LOAD_CONCURRENCY musi być liczbą od 1 do LOAD_REQUESTS.\n' >&2
  exit 2
}

temp_file="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-load.XXXXXX")"
trap 'rm -f -- "$temp_file"' EXIT

printf 'Test obciążeniowy: %s/products, żądań=%s, równoległość=%s\n' \
  "$BASE_URL" "$REQUESTS" "$CONCURRENCY"
start_seconds="$(date +%s)"

for ((worker = 1; worker <= CONCURRENCY; worker++)); do
  (
    for ((request = worker; request <= REQUESTS; request += CONCURRENCY)); do
      status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
        -o /dev/null -w '%{http_code}' "$BASE_URL/products" 2>/dev/null || true)"
      printf '%s\n' "${status:-000}"
    done
  ) >> "$temp_file" &
done
wait

end_seconds="$(date +%s)"
python3 - "$temp_file" "$REQUESTS" "$((end_seconds - start_seconds))" <<'PY'
from collections import Counter
import sys

status_file, expected_count, duration = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
with open(status_file, encoding="utf-8") as handle:
    statuses = [line.strip() for line in handle if line.strip()]

counts = Counter(statuses)
successes = sum(1 for status in statuses if status.startswith("2"))
failures = len(statuses) - successes
print("Statusy: {}".format(dict(sorted(counts.items()))))
print("Czas: {} s | sukcesy: {} | błędy: {}".format(duration, successes, failures))

if len(statuses) != expected_count or failures:
    print("Load test FAILED")
    sys.exit(1)
print("Load test OK")
PY
