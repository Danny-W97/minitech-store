#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT/scripts/control-center/lib/operations.sh"
require_command() { command -v "$1" >/dev/null; }
test_calls="$(mktemp)"
trap 'rm -f -- "$test_calls"' EXIT
export TEST_RETURNS_CALLS="$test_calls"
operator_curl() {
  python3 - "$@" <<'PY'
import json,os,sys
args=sys.argv[1:]
if '--data-binary' in args:
    data=json.loads(args[args.index('--data-binary')+1]);assert data['expectedVersion']==0 and data['operationId']=='11111111-1111-4111-8111-111111111111' and data['note']=='Review reason',data
    method=args[args.index('-X')+1]
    assert (method=='PATCH' and data['status']=='UNDER_REVIEW') or (method=='POST' and 'status' not in data),data
    with open(os.environ['TEST_RETURNS_CALLS'],'a') as output:output.write(json.dumps(data)+'\n')
    print('{"id":1,"version":1}')
else:
    assert args[-1].endswith('/returns/1'),args
    print('{"id":1,"version":0}')
PY
}
returns_status 1 UNDER_REVIEW 0 11111111-1111-4111-8111-111111111111 'Review reason' >/dev/null
returns_status 1 UNDER_REVIEW 0 11111111-1111-4111-8111-111111111111 'Review reason' >/dev/null
returns_reconcile 1 0 11111111-1111-4111-8111-111111111111 'Review reason' >/dev/null
returns_show 1 | python3 -c 'import json,sys;assert json.load(sys.stdin)["version"]==0'
for status in COMPLETED REQUESTED UNKNOWN; do if returns_status 1 "$status" 0 11111111-1111-4111-8111-111111111111 'Review reason' >/dev/null 2>&1; then exit 1; fi; done
if returns_status 1 UNDER_REVIEW >/dev/null 2>&1; then exit 1; fi
if returns_status 1 UNDER_REVIEW 0 bad-key 'Review reason' >/dev/null 2>&1; then exit 1; fi
if returns_status 1 UNDER_REVIEW 0 11111111-1111-4111-8111-111111111111 '' >/dev/null 2>&1; then exit 1; fi
[[ "$(wc -l < "$test_calls")" -eq 3 ]]
printf 'PASS: return CLI keeps version/UUID/note on replay, reconciles explicitly and rejects unguarded/terminal writes\n'
