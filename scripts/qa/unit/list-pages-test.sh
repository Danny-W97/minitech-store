#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/control-center/lib/operations.sh
require_command() { command -v "$1" >/dev/null; }
operator_curl() {
  local header_file='' url=''
  while (($#)); do
    if [[ "$1" == --dump-header ]]; then header_file="$2"; shift 2; else [[ "$1" == http* ]] && url="$1"; shift; fi
  done
  [[ -z "${CC_EXPECT_URL:-}" || "$url" == "$CC_EXPECT_URL" ]] || return 1
  printf 'HTTP/1.1 200 OK\r\nX-Has-More: true\r\nX-Next-Cursor: 9223372036854775806\r\n\r\n' > "$header_file"
  printf '[{"id":9}]'
}
result="$(business_page '/orders' 20 0 2>/dev/null)"
[[ "$result" == '[{"id":9}]' ]]
for size in 0 201 -1 invalid; do
  if business_page '/orders' "$size" 0 >/dev/null 2>&1; then exit 1; fi
done
for cursor in -1 9223372036854775808 '1 OR true'; do
  if business_page '/orders' 20 "$cursor" >/dev/null 2>&1; then exit 1; fi
done
business_page '/orders' 200 9223372036854775806 >/dev/null
CC_EXPECT_URL='http://127.0.0.1:18080/api/payments/follow-ups?size=20&cursor=0&status=DEAD_LETTER'
payment_follow_up_list DEAD_LETTER 20 0 >/dev/null
CC_EXPECT_URL='http://127.0.0.1:18080/api/payments/follow-ups?size=50&cursor=0'
payment_follow_up_list ALL 50 0 >/dev/null
if payment_follow_up_list 'DEAD_LETTER&size=9999' >/dev/null 2>&1; then exit 1; fi
CC_EXPECT_URL='http://127.0.0.1:18080/api/orders/sagas?size=20&cursor=0&state=RECONCILIATION_REQUIRED'
order_sagas RECONCILIATION_REQUIRED 20 0 >/dev/null
CC_EXPECT_URL='http://127.0.0.1:18080/api/inventory/operations?size=20&cursor=0&productId=42'
inventory_operations 42 20 0 >/dev/null
if order_sagas 'RECONCILIATION_REQUIRED&size=9999' >/dev/null 2>&1; then exit 1; fi
if order_reconcile '../42' >/dev/null 2>&1; then exit 1; fi
if payment_follow_up_replay '../1' >/dev/null 2>&1; then exit 1; fi
operator_curl() { return 22; }
if inventory_receive 42 10 '00000000-0000-4000-8000-000000000001' >/dev/null 2>&1; then exit 1; fi
if inventory_receive 42 '1.5' '00000000-0000-4000-8000-000000000001' >/dev/null 2>&1; then exit 1; fi
if inventory_receive 42 10 '../bad' >/dev/null 2>&1; then exit 1; fi
if inventory_adjust 42 10 -1 '00000000-0000-4000-8000-000000000001' >/dev/null 2>&1; then exit 1; fi
if inventory_operation '../bad' >/dev/null 2>&1; then exit 1; fi
if order_reconcile 42 >/dev/null 2>&1; then exit 1; fi
if payment_follow_up_replay 42 >/dev/null 2>&1; then exit 1; fi
if payment_collect 42 '1.001' 'RECEIPT-42' >/dev/null 2>&1; then exit 1; fi
if payment_collect 42 '1.00' 'bad"reference' >/dev/null 2>&1; then exit 1; fi
if payment_collect 42 '1.00' 'RECEIPT-42' >/dev/null 2>&1; then exit 1; fi
if order_cancel '../42' >/dev/null 2>&1; then exit 1; fi
if order_cancel 0 >/dev/null 2>&1; then exit 1; fi
if order_cancel 42 >/dev/null 2>&1; then exit 1; fi
operator_curl() {
  [[ "$*" == *'-X PATCH http://127.0.0.1:18080/api/orders/42/status'* ]]
  [[ "${!#}" == '{"status":"CANCELLED"}' ]]
  printf '{"id":42,"status":"CANCELLED"}'
}
[[ "$(order_cancel 42)" == '{"id":42,"status":"CANCELLED"}' ]]
operator_curl() { cat; }
[[ "$(inventory_receive 42 10 '00000000-0000-4000-8000-000000000001')" == '{"quantity":10,"operationId":"00000000-0000-4000-8000-000000000001"}' ]]
[[ "$(inventory_adjust 42 10 9 '00000000-0000-4000-8000-000000000001')" == '{"availableQuantity":10,"expectedVersion":9,"operationId":"00000000-0000-4000-8000-000000000001"}' ]]
[[ "$(payment_collect 42 '12.30' 'RECEIPT-42')" == '{"amount":12.30,"currency":"PLN","receiptReference":"RECEIPT-42"}' ]]
printf 'PASS: CLI bounded windows, cursor precision, invalid arguments and pipeable output.\n'
