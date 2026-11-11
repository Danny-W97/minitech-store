#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT/scripts/control-center/lib/operations.sh"
C_RED='' C_RESET=''
require_command() { command -v "$1" >/dev/null; }
test_calls="$(mktemp)"
trap 'rm -f -- "$test_calls"' EXIT
export TEST_CORRECTION_CALLS="$test_calls"
operator_curl() {
  python3 - "$@" <<'PY'
import json,os,sys
args=sys.argv[1:]
if '--data-binary' in args:
    body=json.loads(args[args.index('--data-binary')+1])
    assert body==dict(reason='Rabat',correctionGrossAmount='-10.00',idempotencyKey='stable-correction-key',expectedVersion=2),body
    with open(os.environ['TEST_CORRECTION_CALLS'],'a') as calls:calls.write('POST\n')
    print(json.dumps(dict(id=7,correctionNumber='KOR/2026/000001',originalGross=70,finalGross=60,balanceVersion=3)))
else:
    assert args[-1].endswith('/invoices/1/correction-balance'),args
    print(json.dumps(dict(invoiceId=1,version=2,grossAmount=70)))
PY
}
invoice_correct 1 Rabat -10.00 stable-correction-key 2 >/dev/null
invoice_correct 1 Rabat -10.00 stable-correction-key 2 >/dev/null
invoice_balance 1 | python3 -c 'import json,sys; assert json.load(sys.stdin)["version"]==2'
if invoice_correct 1 Rabat 0.001 stable-correction-key 2 >/dev/null 2>&1; then exit 1; fi
if invoice_correct 1 Rabat -10.00 >/dev/null 2>&1; then exit 1; fi
[[ "$(wc -l < "$test_calls")" -eq 2 ]]
printf 'PASS: correction CLI preserves decimal amount, key and version across retries; invalid commands never write\n'
