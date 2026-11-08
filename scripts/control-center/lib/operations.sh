#!/usr/bin/env bash
# Domain operations: orders, invoices, corrections, deliveries, promos, chat, reviews

# One bounded page. The next cursor is printed to stderr; JSON stays pipeable.
business_page() (
  require_command python3 || exit 1
  local path="$1" size="${2:-50}" cursor="${3:-0}" filters="${4:-}" headers
  python3 -c 'import sys; s,c=sys.argv[1:]; sys.exit(0 if s.isascii() and s.isdecimal() and c.isascii() and c.isdecimal() and 1<=int(s)<=200 and 0<=int(c)<=9223372036854775807 else 2)' "$size" "$cursor" || {
    printf 'Rozmiar strony: 1–200; kursor: nieujemna liczba ID.\n' >&2; exit 2;
  }
  headers="$(mktemp "${TMPDIR:-/tmp}/minitech-page.XXXXXX")" || exit 1
  chmod 600 "$headers"
  trap 'rm -f -- "$headers"' EXIT
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 15 --dump-header "$headers" \
    "http://127.0.0.1:18080/api${path}?size=${size}&cursor=${cursor}${filters:+&$filters}" || exit 1
  python3 -c 'from pathlib import Path; import sys; h=dict(line.lower().strip().split(":",1) for line in Path(sys.argv[1]).read_text().splitlines() if ":" in line); print("Kolejna strona: użyj rozmiaru "+sys.argv[2]+" i kursora "+h.get("x-next-cursor","0").strip() if h.get("x-has-more","").strip()=="true" else "Koniec listy.",file=sys.stderr)' "$headers" "$size"
)

payment_follow_up_list() {
  local status="${1:-DEAD_LETTER}" query=''
  case "$status" in
    ALL) ;;
    PENDING|WAITING|RETRY|SUCCEEDED|DEAD_LETTER|CANCELLED) query="status=$status" ;;
    *) printf 'Nieprawidlowy status zadania platnosci.\n' >&2; return 2 ;;
  esac
  business_page '/payments/follow-ups' "${2:-50}" "${3:-0}" "$query"
}

payment_follow_up_replay() {
  local id="${1:-}"
  [[ "$id" =~ ^[1-9][0-9]{0,18}$ ]] || { printf 'Podaj dodatnie ID zadania platnosci.\n' >&2; return 2; }
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 15 \
    -X POST "http://127.0.0.1:18080/api/payments/follow-ups/${id}/replay" || return 1
  printf '\n'
}

inventory_receive() {
  require_command python3 || return 1
  local product="${1:-}" quantity="${2:-}" operation="${3:-}" payload
  python3 -c 'import re,sys; p,q,k=sys.argv[1:]; sys.exit(0 if p.isascii() and p.isdecimal() and 0<int(p)<=9223372036854775807 and re.fullmatch(r"[1-9][0-9]*",q) and int(q)<=2147483647 and re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}",k) else 2)' "$product" "$quantity" "$operation" || {
    printf 'Uzycie: inventory-receive <product-id> <ilosc> <UUID-operacji>. Zachowaj UUID przy ponowieniu.\n' >&2; return 2;
  }
  printf -v payload '{"quantity":%s,"operationId":"%s"}' "$quantity" "$operation"
  printf '%s' "$payload" | operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 20 \
    -H 'Content-Type: application/json' -X POST "http://127.0.0.1:18080/api/inventory/items/${product}/receipts" --data-binary @- || {
      printf '\nWynik operacji %s wymaga sprawdzenia: inventory-operation %s. Ponow z tym samym UUID.\n' "$operation" "$operation" >&2; return 1;
    }
  printf '\n'
}

inventory_adjust() {
  require_command python3 || return 1
  local product="${1:-}" quantity="${2:-}" version="${3:-}" operation="${4:-}" payload
  python3 -c 'import re,sys; p,q,v,k=sys.argv[1:]; sys.exit(0 if all(x.isascii() and x.isdecimal() for x in [p,q,v]) and 0<int(p)<=9223372036854775807 and 0<=int(q)<=2147483647 and 0<=int(v)<=9223372036854775807 and re.fullmatch(r"0|[1-9][0-9]*",q) and re.fullmatch(r"0|[1-9][0-9]*",v) and re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}",k) else 2)' "$product" "$quantity" "$version" "$operation" || {
    printf 'Uzycie: inventory-adjust <product-id> <stan> <odczytana-wersja> <UUID-operacji>.\n' >&2; return 2;
  }
  printf -v payload '{"availableQuantity":%s,"expectedVersion":%s,"operationId":"%s"}' "$quantity" "$version" "$operation"
  printf '%s' "$payload" | operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 20 \
    -H 'Content-Type: application/json' -X PATCH "http://127.0.0.1:18080/api/inventory/items/${product}" --data-binary @- || return 1
  printf '\n'
}

inventory_operation() {
  local operation="${1:-}"
  [[ "$operation" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || return 2
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 15 \
    "http://127.0.0.1:18080/api/inventory/operations/${operation}" || return 1
  printf '\n'
}

inventory_operations() {
  local product="${1:-}" filter=''
  [[ -z "$product" || "$product" =~ ^[1-9][0-9]{0,18}$ ]] || return 2
  [[ -z "$product" ]] || filter="productId=$product"
  business_page '/inventory/operations' "${2:-50}" "${3:-0}" "$filter"
}

inventory_item() {
  local product="${1:-}"
  [[ "$product" =~ ^[1-9][0-9]{0,18}$ ]] || return 2
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 15 "http://127.0.0.1:18080/api/inventory/items/${product}" || return 1
  printf '\n'
}

order_sagas() {
  local state="${1:-RECONCILIATION_REQUIRED}"
  case "$state" in
    INVENTORY_RESERVATION_PENDING|WAITING_FOR_PAYMENT|COMPENSATION_PENDING|COMPLETED|FAILED|RECONCILIATION_REQUIRED) ;;
    *) printf 'Nieprawidlowy stan sagi zamowienia.\n' >&2; return 2 ;;
  esac
  business_page '/orders/sagas' "${2:-50}" "${3:-0}" "state=$state"
}

order_reconcile() {
  local id="${1:-}"
  [[ "$id" =~ ^[1-9][0-9]{0,18}$ ]] || { printf 'Podaj dodatnie ID zamowienia.\n' >&2; return 2; }
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 20 \
    -X POST "http://127.0.0.1:18080/api/orders/${id}/reconcile" || return 1
  printf '\n'
}

order_coupon() {
  local id="${1:-}" method="${2:-GET}" suffix=''
  [[ "$id" =~ ^[1-9][0-9]{0,18}$ ]] || { printf 'Podaj dodatnie ID zamowienia.\n' >&2; return 2; }
  [[ "$method" == POST ]] && suffix='/reconcile'
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 20 -X "$method" \
    "http://127.0.0.1:18080/api/orders/${id}/coupon${suffix}" || return 1
  printf '\n'
}

coupon_capacity() {
  local code="${1:-}"
  [[ "$code" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{2,39}$ ]] || { printf 'Kod: 3-40 znakow (litery ASCII, cyfry, _ lub -).\n' >&2; return 2; }
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 15 \
    "http://127.0.0.1:18080/api/promotions/codes/${code}/capacity" || return 1
  printf '\n'
}

coupon_reservations() {
  local code="${1:-}" state="${2:-RESERVED}" filters=''
  [[ "$code" == '*' ]] && code=''
  [[ -z "$code" || "$code" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{2,39}$ ]] || return 2
  case "$state" in
    ALL) ;;
    RESERVED|CONSUMED|RELEASED) filters="state=$state" ;;
    *) printf 'Stan: RESERVED, CONSUMED, RELEASED lub ALL.\n' >&2; return 2 ;;
  esac
  [[ -z "$code" ]] || filters="${filters:+$filters&}code=$code"
  business_page '/promotions/codes/reservations' "${3:-50}" "${4:-0}" "$filters"
}

order_cancel() {
  local id="${1:-}"
  [[ "$id" =~ ^[1-9][0-9]{0,18}$ ]] || { printf 'Podaj dodatnie ID zamowienia.\n' >&2; return 2; }
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 20 \
    -H 'Content-Type: application/json' -X PATCH "http://127.0.0.1:18080/api/orders/${id}/status" \
    --data-binary '{"status":"CANCELLED"}' || return 1
  printf '\n'
}

payment_stripe_read() {
  local id="${1:-}" suffix="${2:-}" method="${3:-GET}"
  [[ "$id" =~ ^[1-9][0-9]{0,18}$ ]] || { printf 'Podaj dodatnie ID platnosci.\n' >&2; return 2; }
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 55 \
    -X "$method" "http://127.0.0.1:18080/api/payments/${id}/${suffix}" || return 1
  printf '\n'
}
payment_stripe_status() { payment_stripe_read "${1:-}" stripe-status; }
delivery_sync() {
  local id="${1:-}" method="${2:-GET}" suffix=''
  [[ "$id" =~ ^[1-9][0-9]{0,18}$ ]] || { printf 'Podaj dodatnie ID zamowienia.\n' >&2; return 2; }
  [[ "$method" == POST ]] && suffix='/reconcile'
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 50 -X "$method" \
    "http://127.0.0.1:18080/api/deliveries/orders/$id/synchronization$suffix" || return 1
  printf '\n'
}
payment_stripe_reconcile() { payment_stripe_read "${1:-}" reconcile POST; }
payment_refunds() { payment_stripe_read "${1:-}" refunds; }
payment_refund_reconcile() {
  local id="${1:-}"
  [[ "$id" =~ ^[1-9][0-9]{0,18}$ ]] || return 2
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 55 \
    -X POST "http://127.0.0.1:18080/api/payments/refunds/${id}/reconcile" || return 1
  printf '\n'
}
payment_refund() {
  local id="${1:-}" amount="${2:-}" key="${3:-}" reason="${4:-CUSTOMER_REQUEST}" payload
  [[ "$id" =~ ^[1-9][0-9]{0,18}$ && "$amount" =~ ^(0|[1-9][0-9]{0,16})(\.[0-9]{1,2})?$ && "$key" =~ ^[A-Za-z0-9_-]{8,100}$ ]] || {
    printf 'Uzycie: payment-refund <payment-id> <pelna-kwota-PLN> <staly-klucz-ponowienia> [powod].\n' >&2; return 2;
  }
  case "$reason" in CUSTOMER_REQUEST|DUPLICATE_PAYMENT|OPERATOR_RECONCILIATION) ;; *) return 2 ;; esac
  printf -v payload '{"amount":%s,"idempotencyKey":"%s","reason":"%s"}' "$amount" "$key" "$reason"
  printf '%s' "$payload" | operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 55 \
    -H 'Content-Type: application/json' -X POST "http://127.0.0.1:18080/api/payments/${id}/refunds" --data-binary @- || return 1
  printf '\n'
}

payment_collect() {
  local id="${1:-}" amount="${2:-}" reference="${3:-}" payload
  [[ "$id" =~ ^[1-9][0-9]{0,18}$ && "$amount" =~ ^(0|[1-9][0-9]{0,16})(\.[0-9]{1,2})?$ && "$reference" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]{2,79}$ ]] || {
    printf 'Uzycie: payment-collect <payment-id> <kwota-PLN> <id-dowodu-wplaty>.\n' >&2; return 2;
  }
  printf -v payload '{"amount":%s,"currency":"PLN","receiptReference":"%s"}' "$amount" "$reference"
  printf '%s' "$payload" | operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 20 \
    -H 'Content-Type: application/json' -X POST "http://127.0.0.1:18080/api/payments/${id}/collect" --data-binary @- || return 1
  printf '\n'
}


delivery_smoke() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local methods method_code quote
  methods="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$gateway/deliveries/methods")" || {
    printf '%sDelivery Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  method_code="$(printf '%s' "$methods" | python3 -c 'import json,sys; data=json.load(sys.stdin); print(data[0]["code"] if data else "")')"
  [[ -n "$method_code" ]] || {
    printf '%sDelivery Service nie zwrocil aktywnych metod.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  quote="$(printf '{"methodCode":"%s","postalCode":"00-001","itemCount":1,"subtotal":499.99}' "$method_code" | \
    operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
      -H 'Content-Type: application/json' -X POST "$gateway/deliveries/quote" --data-binary @- )" || {
    printf '%sNie udalo sie wyliczyc wyceny dostawy.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf 'Aktywne metody dostawy: %s\n' "$(printf '%s' "$methods" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
  printf 'Przykladowa wycena (%s, koszyk 499,99 zl): %s\n' "$method_code" \
    "$(printf '%s' "$quote" | python3 -c 'import json,sys; q=json.load(sys.stdin); print("{} zl, razem {} zl".format(q["deliveryCost"], q["totalPrice"]))')"
  printf '%sDelivery smoke zakonczony powodzeniem.%s\n' "$C_GREEN" "$C_RESET"
}


delivery_tracking() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local order_id="${1:-}" response
  if [[ -z "$order_id" ]]; then
    printf '%s: ' "$(cc_text 'Numer zamówienia' 'Order ID')"
    read -r order_id || return 1
  fi
  case "$(platform_lowercase "$order_id")" in
    0) return 0 ;;
    exit|quit) exit 0 ;;
  esac
  [[ "$order_id" =~ ^[1-9][0-9]*$ ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Numer zamówienia musi być dodatnią liczbą.' 'Order ID must be a positive number.')" "$C_RESET" >&2
    return 2
  }
  response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$gateway/deliveries/orders/$order_id/tracking")" || {
    printf '%sNie znaleziono przesyłki albo Delivery Service jest niedostępny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s\n' "$response" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("Zamówienie #{} | {} | {} | {}".format(d["orderId"], d["carrier"], d["trackingNumber"], d["status"]))'
}


delivery_tracking_status() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local order_id="${1:-}" status="${2:-}" payload response
  if [[ -z "$order_id" ]]; then
    printf '%s: ' "$(cc_text 'Numer zamówienia' 'Order ID')"
    read -r order_id || return 1
  fi
  case "$(platform_lowercase "$order_id")" in
    0) return 0 ;;
    exit|quit) exit 0 ;;
  esac
  if [[ -z "$status" ]]; then
    printf '%s [LABEL_CREATED|READY_FOR_PICKUP|IN_TRANSIT|OUT_FOR_DELIVERY|DELIVERED|PICKED_UP|CANCELLED]: ' \
      "$(cc_text 'Nowy status przesyłki' 'New shipment status')"
    read -r status || return 1
  fi
  status="$(printf '%s' "$status" | tr '[:lower:]' '[:upper:]')"
  case "$status" in
    LABEL_CREATED|READY_FOR_PICKUP|IN_TRANSIT|OUT_FOR_DELIVERY|DELIVERED|PICKED_UP|CANCELLED) ;;
    0) return 0 ;;
    EXIT|QUIT) exit 0 ;;
    *) printf '%sNieprawidłowy status przesyłki.%s\n' "$C_RED" "$C_RESET" >&2; return 2 ;;
  esac
  payload="$(STATUS="$status" python3 -c 'import json,os; print(json.dumps({"status": os.environ["STATUS"]}))')"
  response="$(printf '%s' "$payload" | operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' -X PATCH \
    "$gateway/deliveries/orders/$order_id/tracking" --data-binary @-)" || {
    printf '%sNie udało się zmienić statusu przesyłki.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s\n' "$response" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("Zamówienie #{} | {} | {}".format(d["orderId"], d["trackingNumber"], d["status"]))'
}


promotion_smoke() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local campaigns evaluation
  campaigns="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$gateway/promotions/active")" || {
    printf '%sPromotion Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  evaluation="$(printf '[{"productId":1,"basePrice":699.99,"catalogIndex":0,"catalogSize":10}]' | \
    operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
      -H 'Content-Type: application/json' -X POST "$gateway/promotions/evaluate" --data-binary @- )" || {
    printf '%sNie udalo sie wyliczyc promocji.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf 'Aktywne kampanie promocji: %s\n' "$(printf '%s' "$campaigns" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
  printf 'Przykladowa oferta: %s\n' "$(printf '%s' "$evaluation" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d[0] if d else "brak")')"
  printf '%sPromotion smoke zakonczony powodzeniem.%s\n' "$C_GREEN" "$C_RESET"
}


promotion_codes() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local code="${1:-}" response
  if [[ -n "$code" ]]; then
    response="$(printf '{"code":"%s","subtotal":499.99}' "${code//\"/}" | \
      operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
        -H 'Content-Type: application/json' -X POST "$gateway/promotions/codes/validate" --data-binary @-)" || {
      printf '%sNie udalo sie zweryfikowac kodu rabatowego.%s\n' "$C_RED" "$C_RESET" >&2
      return 1
    }
    printf 'Weryfikacja kodu %s: %s\n' "$code" "$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin))')"
    return 0
  fi
  response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$gateway/promotions/codes")" || {
    printf '%sPromotion Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s\n' "$response" | python3 -c '
import json, sys
from datetime import date

today = date.today()
for c in json.load(sys.stdin):
    v_from = date.fromisoformat(c["validFrom"]) if c.get("validFrom") else None
    v_to = date.fromisoformat(c["validTo"]) if c.get("validTo") else None

    max_r = c.get("maxRedemptions")
    red_c = c.get("redeemedCount", 0)
    if not c.get("active"):
        status_info = "🔴 NIEAKTYWNY"
    elif v_from and today < v_from:
        status_info = f"🟡 WAŻNY OD {v_from}"
    elif v_to and today > v_to:
        status_info = f"🔴 WYGASŁY ({v_to})"
    elif max_r is not None and red_c >= max_r:
        status_info = f"🔴 LIMIT WYCZERPANY ({red_c}/{max_r})"
    else:
        v_to_str = str(v_to) if v_to else "bezterminowo"
        status_info = f"🟢 WAŻNY (do {v_to_str})"

    limit_max = max_r if max_r is not None else "bez limitu"
    limits = f"{red_c}/{limit_max}"
    code_name = c["code"]
    disc = c["discountPercent"]
    desc = c.get("description", "-")
    print(f"{code_name}: -{disc}% | {desc} | użycia={limits} | status={status_info}")
'
}


promotion_code_create() {
  require_command curl || return 1
  require_command python3 || return 1
  ensure_local_profile || return 1
  local gateway='http://127.0.0.1:18080/api'
  local code description discount max_redemptions minimum_order_value maximum_discount_amount valid_from valid_to active payload response
  local default_from default_to

  default_from="$(date -u +%F)"
  default_to='2099-12-31'
  printf '%s\n' "$(cc_text 'Tworzenie kodu rabatowego (dev/test)' 'Create promotion code (dev/test)')"
  printf '%s: ' "$(cc_text 'Kod (puste = wygeneruj automatycznie, 0 = powrot)' 'Code (empty = generate automatically, 0 = back)')"
  read -r code || return 1
  case "$(platform_lowercase "$code")" in
    0) return 0 ;;
    exit|quit) exit 0 ;;
  esac
  [[ -z "$code" || "$code" =~ ^[[:alnum:]][[:alnum:]_-]{2,39}$ ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Kod musi miec 3-40 znakow: litery, cyfry, - lub _.' 'Code must contain 3-40 letters, digits, - or _.')" "$C_RESET" >&2
    return 2
  }

  printf '%s: ' "$(cc_text 'Opis [Kod rabatowy MiniTech Store]' 'Description [MiniTech Store promotion code]')"
  read -r description || return 1
  description="${description:-Kod rabatowy MiniTech Store}"

  printf '%s: ' "$(cc_text 'Rabat procentowy [10]' 'Discount percentage [10]')"
  read -r discount || return 1
  discount="${discount:-10}"
  [[ "$discount" =~ ^[0-9]+$ && "$discount" -ge 1 && "$discount" -le 100 ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Rabat musi byc liczba od 1 do 100.' 'Discount must be a number from 1 to 100.')" "$C_RESET" >&2
    return 2
  }

  printf '%s: ' "$(cc_text 'Limit uzyc [puste = bez limitu]' 'Maximum redemptions [empty = unlimited]')"
  read -r max_redemptions || return 1
  [[ -z "$max_redemptions" || ( "$max_redemptions" =~ ^[0-9]+$ && "$max_redemptions" -gt 0 ) ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Limit musi byc dodatnia liczba albo pozostac pusty.' 'Limit must be a positive number or empty.')" "$C_RESET" >&2
    return 2
  }

  printf '%s: ' "$(cc_text 'Minimalna wartosc koszyka [0]' 'Minimum cart value [0]')"
  read -r minimum_order_value || return 1
  minimum_order_value="${minimum_order_value:-0}"
  [[ "$minimum_order_value" =~ ^[0-9]+([.][0-9]{1,2})?$ ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Minimalna wartosc koszyka musi byc liczba nieujemna.' 'Minimum cart value must be a non-negative number.')" "$C_RESET" >&2
    return 2
  }

  printf '%s: ' "$(cc_text 'Maksymalny rabat w zl [puste = bez limitu]' 'Maximum discount in PLN [empty = unlimited]')"
  read -r maximum_discount_amount || return 1
  [[ -z "$maximum_discount_amount" || "$maximum_discount_amount" =~ ^[0-9]+([.][0-9]{1,2})?$ ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Maksymalny rabat musi byc liczba dodatnia albo pusty.' 'Maximum discount must be a positive number or empty.')" "$C_RESET" >&2
    return 2
  }

  printf '%s [%s]: ' "$(cc_text 'Wazny od' 'Valid from')" "$default_from"
  read -r valid_from || return 1
  valid_from="${valid_from:-$default_from}"
  printf '%s [%s]: ' "$(cc_text 'Wazny do' 'Valid to')" "$default_to"
  read -r valid_to || return 1
  valid_to="${valid_to:-$default_to}"
  if ! FROM_DATE="$valid_from" TO_DATE="$valid_to" python3 -c 'from datetime import date; import os; start=date.fromisoformat(os.environ["FROM_DATE"]); end=date.fromisoformat(os.environ["TO_DATE"]); raise SystemExit(0 if end >= start else 1)'; then
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Daty musza miec format RRRR-MM-DD, a data koncowa nie moze byc wczesniejsza.' 'Dates must use YYYY-MM-DD and the end date cannot be earlier than the start date.')" "$C_RESET" >&2
    return 2
  fi

  printf '%s [true]: ' "$(cc_text 'Aktywny (true/false)' 'Active (true/false)')"
  read -r active || return 1
  active="${active:-true}"
  case "$(platform_lowercase "$active")" in
    true|t|yes|y|tak) active=true ;;
    false|f|no|n|nie) active=false ;;
    *) printf '%s%s%s\n' "$C_RED" "$(cc_text 'Aktywny musi byc true albo false.' 'Active must be true or false.')" "$C_RESET" >&2; return 2 ;;
  esac

  payload="$(CODE="$code" DESCRIPTION="$description" DISCOUNT="$discount" MAX_REDEMPTIONS="$max_redemptions" MINIMUM_ORDER_VALUE="$minimum_order_value" MAXIMUM_DISCOUNT_AMOUNT="$maximum_discount_amount" VALID_FROM="$valid_from" VALID_TO="$valid_to" ACTIVE="$active" python3 -c 'import json,os; print(json.dumps({"code": os.environ["CODE"] or None, "description": os.environ["DESCRIPTION"], "discountPercent": int(os.environ["DISCOUNT"]), "maxRedemptions": int(os.environ["MAX_REDEMPTIONS"]) if os.environ["MAX_REDEMPTIONS"] else None, "minimumOrderValue": float(os.environ["MINIMUM_ORDER_VALUE"]), "maximumDiscountAmount": float(os.environ["MAXIMUM_DISCOUNT_AMOUNT"]) if os.environ["MAXIMUM_DISCOUNT_AMOUNT"] else None, "validFrom": os.environ["VALID_FROM"], "validTo": os.environ["VALID_TO"], "active": os.environ["ACTIVE"] == "true"}))')"
  response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' -X POST "$gateway/promotions/codes" --data-binary "$payload")" || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Nie udalo sie utworzyc kodu. Sprawdz, czy Promotion Service dziala i czy kod nie istnieje.' 'Could not create the code. Check Promotion Service and whether the code already exists.')" "$C_RESET" >&2
    return 1
  }
  printf '%s%s%s\n' "$C_GREEN" "$(printf '%s' "$response" | python3 -c 'import json,sys; d=json.load(sys.stdin); limit=d["maxRedemptions"] if d["maxRedemptions"] is not None else "bez limitu"; print("Utworzono kod {}: -{}%, wazny {}–{}, limit: {}".format(d["code"], d["discountPercent"], d["validFrom"], d["validTo"], limit))')" "$C_RESET"
}


review_pending() {
  business_page '/reviews/moderation/pending' "${1:-50}" "${2:-0}" || return $?
  printf '\n'
}


returns_list() {
  require_command python3 || return 1
  local limit="${1:-50}" cursor="${2:-0}" response
  response="$(business_page '/returns' "$limit" "$cursor")" || return $?
  printf '%s' "$response" | python3 -c '
import json, sys, textwrap

raw = sys.stdin.read()
if not raw.strip():
    print("Brak odpowiedzi z serwisu zwrotow.")
    sys.exit(0)

try:
    data = json.loads(raw)
except Exception:
    print(raw)
    sys.exit(0)

if not data:
    print("Brak zgloszen zwrotow i reklamacji.")
    sys.exit(0)

type_names = {
    "COMPLAINT": "REKLAMACJA",
    "RETURN": "ZWROT"
}

print("=" * 125)
print("{:<5} | {:<11} | {:<11} | {:<13} | {:<18} | {:<16} | {}".format("ID", "ZAMOWIENIE", "TYP", "STATUS", "SKUTKI DOMENY", "DATA", "KLIENT"))
print("=" * 125)

for r in data:
    ret_id = "#" + str(r.get("id", "?"))
    ord_id = "#" + str(r.get("orderId", "?"))
    rtype = type_names.get(r.get("requestType"), str(r.get("requestType", "-")))
    st = str(r.get("status", "-"))
    eff = str(r.get("effectsState", "-"))
    dt = (r.get("createdAt") or "")[:16].replace("T", " ")
    email = r.get("customerEmail") or "-"
    cid = r.get("customerId")
    cust = email + (" [ID: " + str(cid) + "]" if cid is not None else "")

    print("{:<5} | {:<11} | {:<11} | {:<13} | {:<18} | {:<16} | {}".format(ret_id, ord_id, rtype, st, eff, dt, cust))

    items = r.get("items") or []
    if items:
        it_str = ", ".join("{} x{} (ID prod: {})".format(it.get("productName", "Produkt"), it.get("quantity", 1), it.get("productId", "-")) for it in items)
    else:
        it_str = "brak zgloszonych pozycji"
    print("   ↳ Pozycje:   {}".format(it_str))

    reason = r.get("reason")
    if reason:
        print("   ↳ Powod:     {}".format(reason))

    desc = r.get("description")
    if desc:
        wrapped = textwrap.fill(desc, width=100)
        lines = wrapped.splitlines()
        print("   ↳ Opis:      {}".format(lines[0]))
        for l in lines[1:]:
            print("                {}".format(l))

    ver = r.get("version", 0)
    rec = "TAK (wymaga uzgodnienia)" if r.get("requiresReconciliation") else "NIE"
    meta_line = "Wersja: {} | Uzgodnienie: {}".format(ver, rec)
    mod = r.get("moderationNote")
    if mod:
        meta_line += " | Notatka: {}".format(mod)
    print("   ↳ Stan:      {}".format(meta_line))
    print("-" * 125)

print("\nLacznie zgloszen na liscie: {}".format(len(data)))
print("<!-- CC_RETURNS_JSON: {} -->".format(json.dumps(data)))
'
}


returns_status() {
  require_command python3 || return 1
  local id="${1:-}" status="${2:-}" version="${3:-}" operation="${4:-}" note="${5:-}" payload suffix='status' method='PATCH'
  [[ "$id" =~ ^[1-9][0-9]{0,18}$ ]] || return 2
  case "$status" in UNDER_REVIEW|APPROVED|REJECTED) ;; RECONCILE) suffix='reconcile'; method='POST' ;; *) printf 'Dozwolone: UNDER_REVIEW, APPROVED, REJECTED.\n' >&2; return 2 ;; esac
  payload="$(python3 -c 'import sys,json,re; s,v,k,n=sys.argv[1:]; valid=v.isascii() and v.isdecimal() and 0<=int(v)<=9223372036854775807 and re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}",k) and 0<len(n.strip())<=1000; valid or sys.exit("Wymagane: wersja z returns-show, staly UUID operacji i notatka."); p=dict(expectedVersion=int(v),operationId=k,note=n.strip()); p.update({} if s=="RECONCILE" else dict(status=s)); print(json.dumps(p))' "$status" "$version" "$operation" "$note")" || return 2
  operator_curl --silent --show-error --fail-with-body --connect-timeout 3 --max-time 20 -H 'Content-Type: application/json' -X "$method" \
    "http://127.0.0.1:18080/api/returns/$id/$suffix" --data-binary "$payload" || {
      printf '\nNie potwierdzono operacji %s. Sprawdz returns-show/returns-history; ponawiaj te same dane, wersje i UUID.\n' "$operation" >&2; return 1;
    }
  printf '\n'
}

returns_show() {
  [[ "${1:-}" =~ ^[1-9][0-9]{0,18}$ ]] || return 2
  require_command python3 || return 1
  local response
  response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 15 "http://127.0.0.1:18080/api/returns/$1")" || return $?
  if [[ ! -t 1 || "${2:-}" == "--raw" || "${2:-}" == "--json" ]]; then
    printf '%s\n' "$response"
    return 0
  fi
  printf '%s' "$response" | python3 -c '
import json, sys, textwrap

raw = sys.stdin.read()
if not raw.strip():
    print("Brak odpowiedzi z serwisu zwrotow.")
    sys.exit(0)
try:
    r = json.loads(raw)
except Exception:
    print(raw)
    sys.exit(0)

type_names = {"COMPLAINT": "REKLAMACJA", "RETURN": "ZWROT"}
rtype = type_names.get(r.get("requestType"), str(r.get("requestType", "-")))
ret_id = str(r.get("id", "?"))

print("\n" + "=" * 80)
print("ZGŁOSZENIE ZWROTU / REKLAMACJI #{} ({})".format(ret_id, rtype))
print("=" * 80)
print("Status zgloszenia:      {}".format(r.get("status", "-")))
print("Skutki domenowe:        {}".format(r.get("effectsState", "-")))
print("ID zamowienia:          #{}".format(r.get("orderId", "-")))
print("Data utworzenia:        {}".format(r.get("createdAt", "-")))
print("Data aktualizacji:      {}".format(r.get("updatedAt", "-")))
print("Klient:                 {} (ID klienta: {})".format(r.get("customerEmail", "-"), r.get("customerId", "-")))
print("Wersja optymistyczna:   {}".format(r.get("version", 0)))
rec = "TAK" if r.get("requiresReconciliation") else "NIE"
print("Wymaga uzgodnienia:     {}".format(rec))
if r.get("moderationNote"):
    print("Notatka moderatora:     {}".format(r.get("moderationNote")))
print("-" * 80)
print("POWOD ZGLOSZENIA:")
print("  {}".format(r.get("reason") or "-"))
if r.get("description"):
    print("\nOPIS KLIENTA:")
    for line in textwrap.wrap(r.get("description"), width=76):
        print("  {}".format(line))
print("-" * 80)
print("ZGLOSZONE POZYCJE:")
items = r.get("items") or []
if not items:
    print("  (brak pozycji przypisanych do zgloszenia)")
for it in items:
    print("  * {} (ID: {}) - ilosc zwracana: {} / zamowiono: {}".format(
        it.get("productName", "Produkt"),
        it.get("productId", "-"),
        it.get("quantity", 1),
        it.get("orderedQuantity", 1)
    ))
print("=" * 80)
'
}

returns_history() {
  [[ "${1:-}" =~ ^[1-9][0-9]{0,18}$ ]] || return 2
  require_command python3 || return 1
  local id="$1" raw_json
  raw_json="$(business_page "/returns/$id/history" "${2:-50}" "${3:-0}")" || return $?
  printf '%s' "$raw_json" | python3 -c '
import json, sys
raw = sys.stdin.read()
try:
    data = json.loads(raw)
except Exception:
    print(raw)
    sys.exit(0)
if not data:
    print("Brak wpisow historii decyzji dla zgloszenia.")
    sys.exit(0)
print("=" * 110)
print("{:<19} | {:<15} | {:<18} | {:<25} | {}".format("DATA", "OPERATOR", "DZIALANIE", "STARY -> NOWY STATUS", "NOTATKA"))
print("=" * 110)
for h in data:
    dt = (h.get("createdAt") or "")[:19].replace("T", " ")
    op = (h.get("operator") or "-")[:15]
    action = (h.get("action") or "-")[:18]
    prev_st = str(h.get("previousStatus", "-"))
    new_st = str(h.get("newStatus", "-"))
    st_flow = (prev_st + " -> " + new_st)[:25]
    note = (h.get("note") or "")[:35]
    print("{:<19} | {:<15} | {:<18} | {:<25} | {}".format(dt, op, action, st_flow, note))
print("-" * 110)
print("Wpisow w historii: {}".format(len(data)))
'
}
returns_reconcile() { returns_status "${1:-}" RECONCILE "${2:-}" "${3:-}" "${4:-}"; }


billing_invoice() {
  require_command curl || return 1
  require_command python3 || return 1
  local order_id="${1:-}" response order_info
  if [[ -z "$order_id" ]]; then printf 'ID zamowienia: '; read -r order_id || return 1; fi
  [[ "$order_id" =~ ^[0-9]+$ ]] || { printf '%sID zamowienia musi byc liczba.%s\n' "$C_RED" "$C_RESET" >&2; return 2; }

  printf '%sPobieranie faktury dla zamowienia #%s...%s\n' "$C_BLUE" "$order_id" "$C_RESET"
  
  response="$(operator_curl --silent --show-error --connect-timeout 3 --max-time 10 "http://127.0.0.1:18080/api/invoices/order/$order_id" 2>/dev/null)" || true
  
  if [[ -n "$response" ]] && printf '%s' "$response" | grep -q '"invoiceNumber"'; then
    printf '%s' "$response" | python3 -c '
import json, sys
data = json.load(sys.stdin)
print("\n" + "=" * 60)
print("📄 FAKTURA VAT: {}".format(data.get("invoiceNumber", "BRAK")))
print("=" * 60)
print("Status dokumentu:    {}".format(data.get("status", "-")))
print("ID zamówienia:       #{}".format(data.get("orderId", "-")))
print("Data wystawienia:    {}".format(data.get("createdAt", "-")))
print("Nabywca:             {}".format(data.get("companyName") or data.get("buyerName") or "-"))
if data.get("taxId"):
    print("NIP:                 {}".format(data.get("taxId")))
print("Adres:               {}, {} {}".format(data.get("street", ""), data.get("postalCode", ""), data.get("city", "")))
print("E-mail:              {}".format(data.get("customerEmail", "-")))
print("-" * 60)
print("POZYCJE FAKTURY:")
for item in data.get("items", []):
    print("  • {} (SKU: {}) x{} — {} {} (brutto)".format(
        item.get("productName", "-"), item.get("sku", "-"), item.get("quantity", 1),
        item.get("totalPrice", "-"), data.get("currency", "PLN")))
print("-" * 60)
print("Suma Netto:          {} {}".format(data.get("netAmount", "0.00"), data.get("currency", "PLN")))
print("Kwota VAT (23%):     {} {}".format(data.get("vatAmount", "0.00"), data.get("currency", "PLN")))
print("Dostawa:             {} {}".format(data.get("deliveryCost", "0.00"), data.get("currency", "PLN")))
if float(data.get("discountAmount", 0) or 0) > 0:
    print("Rabat:              -{} {}".format(data.get("discountAmount", "0.00"), data.get("currency", "PLN")))
print("SUMA BRUTTO:         {} {}".format(data.get("grossAmount", "0.00"), data.get("currency", "PLN")))
print("=" * 60)
print("📥 Pobierz plik PDF: http://127.0.0.1:18080/api/invoices/order/{}/pdf\n".format(data.get("orderId")))
'
    return 0
  fi

  # If invoice was not found, check order details
  order_info="$(operator_curl --silent --connect-timeout 2 --max-time 5 "http://127.0.0.1:18080/api/orders/$order_id" 2>/dev/null)" || true
  if [[ -n "$order_info" ]] && printf '%s' "$order_info" | grep -q '"id"'; then
    local inv_req cust_email ord_status
    inv_req="$(printf '%s' "$order_info" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("invoiceRequested", False))' 2>/dev/null || echo "False")"
    cust_email="$(printf '%s' "$order_info" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("customerEmail", "-"))' 2>/dev/null || echo "-")"
    ord_status="$(printf '%s' "$order_info" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", "-"))' 2>/dev/null || echo "-")"

    printf '\n%s[INFORMACJA] Brak faktury dla zamowienia #%s%s\n' "$C_YELLOW" "$order_id" "$C_RESET"
    printf '  • Status zamowienia: %s\n' "$ord_status"
    printf '  • Klient (e-mail):   %s\n' "$cust_email"
    if [[ "$inv_req" == "True" || "$inv_req" == "true" ]]; then
      printf '  • Zadanie faktury:  TAK (faktura nie zostala jeszcze wygenerowana przez Billing Service)\n'
    else
      printf '  • Zadanie faktury:  NIE (zamowienie zostalo zlozone bez zaznaczenia prosby o fakture VAT)\n'
    fi
    printf '\n%sWskazowka:%s Uzyj polecenia „Wszystkie zamowienia” (orders-list), aby znalezc zamowienie z kolumna FAKTURA = TAK (np. #46).\n' "$C_GREEN" "$C_RESET"
    return 1
  else
    printf '%s[BŁĄD] Zamowienie #%s nie istnieje w systemie.%s\n' "$C_RED" "$order_id" "$C_RESET" >&2
    return 1
  fi
}


orders_list() {
  require_command curl || return 1
  require_command python3 || return 1
  local limit="${1:-100}" response
  response="$(business_page '/orders' "$limit" "${2:-0}")" || return $?
  printf '%s' "$response" | ORDER_LIMIT="$limit" python3 -c '
import json, os, sys
orders = json.load(sys.stdin)
if not orders:
    print("Brak zamowien.")
    raise SystemExit
print("ID | STATUS | KWOTA | E-MAIL | FAKTURA | POZYCJE")
print("-" * 150)
for order in orders:
    items = ", ".join("{} x{}".format(item.get("productName", "produkt"), item.get("quantity", 0)) for item in order.get("items", [])) or "brak pozycji"
    print("#{} | {} | {} PLN | {} | {} | {}".format(order.get("id", "?"), order.get("status", "?"), order.get("totalPrice", "0.00"), order.get("customerEmail", "-"), "TAK" if order.get("invoiceRequested") else "NIE", items))
print("\nLiczba wyswietlonych zamowien: {}".format(len(orders)))
'
}


invoice_pdf() {
  require_command curl || return 1
  local order_id="${1:-}" output_dir="$PROJECT_ROOT/gitIgnoreOwn/runtime/invoices" output_file
  if [[ -z "$order_id" ]]; then printf 'ID zamowienia: '; read -r order_id || return 1; fi
  [[ "$order_id" =~ ^[0-9]+$ ]] || { printf '%sID zamowienia musi byc liczba.%s\n' "$C_RED" "$C_RESET" >&2; return 2; }
  mkdir -p "$output_dir"
  output_file="$output_dir/faktura-order-$order_id.pdf"
  if operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 15 "http://127.0.0.1:18080/api/invoices/order/$order_id/pdf" -o "$output_file"; then
    printf '%sPDF faktury zapisany: %s%s\n' "$C_GREEN" "$output_file" "$C_RESET"
  else
    rm -f "$output_file"
    if operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "http://127.0.0.1:18080/api/invoices/order/$order_id" -o /dev/null; then
      printf '%sFaktura dla zamowienia istnieje, ale uruchomiony Billing Service nie udostepnia endpointu PDF (HTTP 404). Zrestartuj BillingServiceApplication z aktualnego kodu i ponow probe.%s\n' "$C_RED" "$C_RESET" >&2
    else
      printf '%sBilling Service nie zwrocil faktury dla zamowienia albo jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    fi
    return 1
  fi
}


invoices_list() {
  require_command python3 || return 1
  local limit="${1:-50}"
  local raw_json
  raw_json="$(business_page '/invoices' "$limit" "${2:-0}")" || return $?

  printf '%s' "$raw_json" | python3 -c '
import json, sys
data = json.load(sys.stdin)
if not data:
    print("Brak wystawionych faktur.")
    raise SystemExit
print("NR FAKTURY       | ZAMÓWIENIE | KWOTA BRUTTO | KLIENT                  | DATA WYSTAWIENIA")
print("-" * 95)
for inv in data:
    dt = (inv.get("createdAt") or inv.get("created_at") or "")[:19].replace("T", " ")
    email = (inv.get("customerEmail") or inv.get("customer_email") or "-")[:23]
    inv_nr = inv.get("invoiceNumber") or inv.get("invoice_number") or "-"
    ord_id = inv.get("orderId") or inv.get("order_id") or "-"
    gross = float(inv.get("grossAmount") if inv.get("grossAmount") is not None else inv.get("gross_amount", 0) or 0)
    curr = inv.get("currency", "PLN")
    print(f"{inv_nr:<16} | #{ord_id:<10} | {gross:>9.2f} {curr} | {email:<23} | {dt}")
print(f"\nFaktury na tej stronie: {len(data)}")
'
}


invoices_export_zip() (
  require_command curl || return 1
  require_command python3 || return 1
  local output_file="${1:-$PROJECT_ROOT/gitIgnoreOwn/runtime/invoices/faktury.zip}"
  local query staged
  query="$(python3 -c 'from datetime import datetime; from urllib.parse import urlencode; import sys; p={k:v for k,v in zip(("from","to"),sys.argv[1:]) if v}; d={k:datetime.fromisoformat(v.replace("Z","+00:00")) for k,v in p.items()}; assert all(v.utcoffset() is not None for v in d.values()), "Dates require a timezone"; assert not ("from" in d and "to" in d and d["from"]>d["to"]), "Invalid date range"; print("?"+urlencode(p) if p else "")' "${2:-}" "${3:-}")" || return 2
  mkdir -p "$(dirname "$output_file")"
  staged="$(mktemp "$(dirname "$output_file")/.invoice-export.XXXXXX")" || return 1
  trap 'rm -f -- "$staged"' EXIT
  printf '%sPobieranie archiwum ZIP z fakturami...%s\n' "$C_BLUE" "$C_RESET"
  if operator_curl --silent --show-error --fail --connect-timeout 5 --max-time 330 "http://127.0.0.1:18080/api/invoices/export/zip${query}" -o "$staged" && \
     python3 -c 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1]); assert z.testzip() is None' "$staged"; then
    mv -f -- "$staged" "$output_file" || return 1
    local file_size
    file_size="$(wc -c < "$output_file" 2>/dev/null || stat -c%s "$output_file" 2>/dev/null || echo "0")"
    printf '%sArchiwum ZIP zapisane pomyslnie: %s (%s bajtow)%s\n' "$C_GREEN" "$output_file" "$file_size" "$C_RESET"
  else
    printf '%sNie udalo sie pobrac archiwum ZIP z fakturami.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  fi
)


invoice_balance() {
  [[ "${1:-}" =~ ^[1-9][0-9]*$ ]] || { printf 'Uzycie: invoice-balance <id>\n' >&2; return 2; }
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "http://127.0.0.1:18080/api/invoices/$1/correction-balance"
}
invoice_correct() {
  require_command curl || return 1
  require_command python3 || return 1
  local invoice_id="${1:-}" reason="${2:-}" delta="${3:-}" operation="${4:-}" version="${5:-}" payload response
  [[ -n "$operation" && "$version" =~ ^[0-9]+$ ]] || { printf 'Uzycie: invoice-correct <id> <powod> <delta> <staly-klucz> <wersja-z-invoice-balance>\nPonowienie zachowuje klucz, wersje i tresc.\n' >&2; return 2; }
  if [[ -z "$invoice_id" ]]; then printf 'ID faktury: '; read -r invoice_id || return 1; fi
  [[ "$invoice_id" =~ ^[0-9]+$ ]] || { printf '%sID faktury musi byc liczba.%s\n' "$C_RED" "$C_RESET" >&2; return 2; }
  if [[ -z "$reason" ]]; then printf 'Przyczyna korekty: '; read -r reason || return 1; fi
  if [[ -z "$delta" ]]; then printf 'Kwota zmiany brutto (np. -129.99): '; read -r delta || return 1; fi

  payload="$(CORR_REASON="$reason" CORR_DELTA="$delta" CORR_KEY="$operation" CORR_VERSION="$version" python3 -c '
import json,os,re
from decimal import Decimal
reason=os.environ["CORR_REASON"].strip(); amount=Decimal(os.environ["CORR_DELTA"]); key=os.environ["CORR_KEY"]
if not reason or len(reason)>255 or any(ord(c)<32 or ord(c)==127 for c in reason) or not re.fullmatch(r"[A-Za-z0-9_-]{8,100}",key): raise SystemExit("Nieprawidlowy powod lub klucz operacji")
if not amount.is_finite() or amount==0 or abs(amount)>Decimal("99999999999999999.99") or amount.as_tuple().exponent < -2: raise SystemExit("Nieprawidlowa kwota korekty")
print(json.dumps(dict(reason=reason,correctionGrossAmount=format(amount,".2f"),idempotencyKey=key,expectedVersion=int(os.environ["CORR_VERSION"]))))
')" || return 2
  response="$(operator_curl --silent --show-error --fail-with-body --connect-timeout 3 --max-time 10 -H 'Content-Type: application/json' -X POST "http://127.0.0.1:18080/api/invoices/$invoice_id/corrections" --data-binary "$payload")" || {
    printf '%sNie potwierdzono korekty. Ponow te same dane i klucz %s; sprawdz historie przed nowym zamiarem.%s\n' "$C_RED" "$operation" "$C_RESET" >&2
    return 1
  }
  printf '%s' "$response" | python3 -c '
import json, sys
c = json.load(sys.stdin)
print("\nWYSTAWIONO KOREKTE: {}".format(c.get("correctionNumber")))
for label, field in [("Faktura pierwotna", "originalInvoiceNumber"), ("Przyczyna", "reason"),
                     ("Saldo przed korekta", "originalGross"), ("Zmiana brutto", "correctionGross"),
                     ("Saldo po korekcie", "finalGross"), ("Wersja salda", "balanceVersion")]:
    print("{}: {}".format(label, c.get(field)))
print("PDF: http://127.0.0.1:18080/api/invoices/corrections/{}/pdf".format(c.get("id")))
'
}


corrections_list() {
  require_command curl || return 1
  require_command python3 || return 1
  local invoice_id="${1:-}" url
  if [[ -n "$invoice_id" ]]; then
    url="http://127.0.0.1:18080/api/invoices/$invoice_id/corrections"
  else
    url="http://127.0.0.1:18080/api/invoices/corrections"
  fi
  local response
  response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "$url" 2>/dev/null || \
              operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "${url/18080\/api/18095}" 2>/dev/null)" || {
    printf '%sNie udalo sie pobrac listy korekt.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s' "$response" | python3 -c '
import json, sys
items = json.load(sys.stdin)
if not items:
    print("Brak wystawionych korekt.")
    raise SystemExit
print("NR KOREKTY       | DO FAKTURY       | KOREKTA BRUTTO | KWOTA KOŃCOWA | PRZYCZYNA")
print("-" * 90)
for c in items:
    cn = c.get("correctionNumber", "-")
    oin = c.get("originalInvoiceNumber", "-")
    cg = float(c.get("correctionGross") or 0)
    fg = float(c.get("finalGross") or 0)
    r = (c.get("reason") or "-")[:25]
    print(f"{cn:<16} | {oin:<16} | {cg:>12.2f} zł | {fg:>11.2f} zł | {r}")
print(f"\nLiczba korekt: {len(items)}")
'
}


review_moderate() {
  require_command curl || return 1
  local review_id='' status='' note='' payload response
  [[ "$#" -ge 1 ]] && review_id="$1"
  [[ "$#" -ge 2 ]] && status="$2"
  [[ "$#" -ge 3 ]] && note="$3"
  if [[ -z "$review_id" ]]; then
    printf 'ID opinii: '
    read -r review_id || return 1
  fi
  if [[ -z "$status" ]]; then
    printf 'Status (PUBLISHED/REJECTED): '
    read -r status || return 1
  fi
  status="$(printf '%s' "$status" | tr '[:lower:]' '[:upper:]')"
  case "$status" in
    PUBLISHED|REJECTED) ;;
    *) printf '%sStatus musi byc PUBLISHED albo REJECTED.%s\n' "$C_RED" "$C_RESET" >&2; return 2 ;;
  esac
  if [[ -z "$note" ]]; then
    printf 'Notatka moderatora [opcjonalnie]: '
    read -r note || return 1
  fi
  payload="$(printf '{"status":"%s","note":"%s"}' "$status" "$note")"
  response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 -H 'Content-Type: application/json' -X PATCH "http://127.0.0.1:18080/api/reviews/$review_id/moderation" --data-binary "$payload")" || {
    printf '%sNie udalo sie zmienic statusu opinii.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s\n' "$response"
}


chat_messages() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local limit="${1:-50}" response
  response="$(business_page '/chat/messages' "$limit" "${2:-0}")" || return $?
  printf '%s' "$response" | CHAT_LIMIT="$limit" python3 -c 'import json, os, sys; data=json.load(sys.stdin) ; [(print("ID #{} | {} | {} <{}> | {} | powiadomienie={}".format(item.get("id"), item.get("status"), item.get("customerName"), item.get("customerEmail"), item.get("subject") or "Pytanie", item.get("notificationStatus"))), print("  Wiadomosc: {}".format(item.get("message") or "")), print("  Odpowiedz: {} | e-mail={}".format(item.get("replyMessage") or "-", item.get("replyNotificationStatus") or "-"))) for item in data]'
}


chat_reply() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local chat_id="${1:-}" reply="${2:-}" request_id="${3:-}" payload response
  if [[ -z "$chat_id" ]]; then
    printf 'ID wiadomosci (0 = powrot): '
    read -r chat_id || return 1
  fi
  case "$(platform_lowercase "$chat_id")" in
    0) return 0 ;;
    exit|quit) exit 0 ;;
  esac
  [[ "$chat_id" =~ ^[0-9]+$ && "$chat_id" -gt 0 ]] || {
    printf '%sID wiadomosci musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  if [[ -z "$reply" ]]; then
    printf 'Tresc odpowiedzi (0 = powrot): '
    IFS= read -r reply || return 1
  fi
  case "$(platform_lowercase "$reply")" in
    0) return 0 ;;
    exit|quit) exit 0 ;;
  esac
  [[ -n "${reply//[[:space:]]/}" ]] || {
    printf '%sOdpowiedz nie moze byc pusta.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  payload="$(CHAT_REPLY="$reply" CHAT_REQUEST_ID="$request_id" python3 -c 'import json,os,uuid; print(json.dumps({"message":os.environ["CHAT_REPLY"],"requestId":os.environ["CHAT_REQUEST_ID"] or str(uuid.uuid4())}))')"
  response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' -X POST \
    "$gateway/chat/messages/$chat_id/reply" --data-binary "$payload")" || {
    printf '%sNie udalo sie wyslac odpowiedzi. Sprawdz ID i dostepnosc Chat Service.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%sOdpowiedz zapisana. Powiadomienie jest w trwalej kolejce.%s\n' "$C_GREEN" "$C_RESET"
  printf '%s\n' "$response"
}

chat_history() {
  local id="${1:-}" before="${2:-0}"
  [[ "$id" =~ ^[1-9][0-9]*$ && "$before" =~ ^[0-9]+$ ]] || return 2
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "http://127.0.0.1:18080/api/chat/messages/$id/entries?before=$before&size=50" || return $?
  printf '\n'
}

chat_notification_retry() {
  local id="${1:-}" entry="${2:-}"
  [[ "$id" =~ ^[1-9][0-9]*$ && "$entry" =~ ^[1-9][0-9]*$ ]] || return 2
  operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 -X POST \
    "http://127.0.0.1:18080/api/chat/messages/$id/entries/$entry/retry-notification"
}


