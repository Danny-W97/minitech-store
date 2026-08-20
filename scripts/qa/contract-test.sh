#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
CONTRACT_FILE="$SCRIPT_DIR/contracts/api-gateway-v1.json"
BASE_URL="${CONTRACT_API_BASE_URL:-http://127.0.0.1:18080}"
BASE_URL="${BASE_URL%/}"

for command in curl python3; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Brak wymaganej komendy: %s\n' "$command" >&2
    exit 1
  }
done

[[ -f "$CONTRACT_FILE" ]] || {
  printf 'Brak kontraktu: %s\n' "$CONTRACT_FILE" >&2
  exit 1
}

temp_file="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-contract.XXXXXX")"
trap 'rm -f -- "$temp_file"' EXIT

printf 'Pobieram kontrakt OpenAPI: %s/v3/api-docs\n' "$BASE_URL"
curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
  "$BASE_URL/v3/api-docs" > "$temp_file"

python3 - "$CONTRACT_FILE" "$temp_file" <<'PY'
import json
import sys

contract_path, spec_path = sys.argv[1:]
with open(contract_path, encoding="utf-8") as handle:
    contract = json.load(handle)
with open(spec_path, encoding="utf-8") as handle:
    spec = json.load(handle)

errors = []
if spec.get("info", {}).get("version") != contract["version"]:
    errors.append(
        "info.version: expected {}, got {}".format(
            contract["version"], spec.get("info", {}).get("version")
        )
    )

paths = spec.get("paths", {})
for path, methods in contract["paths"].items():
    actual = paths.get(path)
    if actual is None:
        errors.append("missing path: {}".format(path))
        continue
    for method in methods:
        if method.lower() not in actual:
            errors.append("missing operation: {} {}".format(method, path))

if errors:
    print("API contract FAILED")
    for error in errors:
        print(" - " + error)
    sys.exit(1)

print("API contract OK: {} paths and version {}".format(len(contract["paths"]), contract["version"]))
PY

printf 'Kontrakt: %s\n' "$CONTRACT_FILE"
printf 'API Gateway v1 spełnia zdefiniowany kontrakt.\n'
