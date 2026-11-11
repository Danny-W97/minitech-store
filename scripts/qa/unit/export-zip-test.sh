#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/control-center/lib/operations.sh
require_command() { command -v "$1" >/dev/null; }
C_BLUE='' C_GREEN='' C_RED='' C_RESET=''
test_dir="$(mktemp -d tmp/export-cli.XXXXXX)"
trap 'rm -f -- "$test_dir/original.zip" "$test_dir/calls"; rmdir -- "$test_dir"' EXIT
output="$test_dir/original.zip"
printf 'previous file' > "$output"
operator_curl() {
  printf 'call\n' >> "$test_dir/calls"
  local destination=''
  while (($#)); do
    if [[ "$1" == -o ]]; then destination="$2"; shift 2; else shift; fi
  done
  if [[ "${EXPORT_FIXTURE_MODE:-bad}" == bad ]]; then printf 'partial' > "$destination"; return 1; fi
  python3 -c 'import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],"w"); z.writestr("test.pdf",b"%PDF-test"); z.close()' "$destination"
}
if invoices_export_zip "$output" >/dev/null 2>&1; then exit 1; fi
[[ "$(cat "$output")" == 'previous file' ]]
[[ "$(wc -l < "$test_dir/calls")" == 1 ]] # no fallback/retry after a failed stream
EXPORT_FIXTURE_MODE=good invoices_export_zip "$output" '2026-01-01T00:00:00Z' '2026-01-02T00:00:00Z' >/dev/null
python3 -c 'import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); assert z.testzip() is None; assert z.read("test.pdf")==b"%PDF-test"' "$output"
if invoices_export_zip "$output" 'bad-date' >/dev/null 2>&1; then exit 1; fi
printf 'PASS: ZIP CLI date validation, no retry, valid archive and preservation of the previous file on failure.\n'
