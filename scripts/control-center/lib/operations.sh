#!/usr/bin/env bash
# Domain operations: orders, invoices, corrections, deliveries, promos, chat, reviews

delivery_smoke() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local methods method_code quote
  methods="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
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
    curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
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
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
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
  response="$(printf '%s' "$payload" | curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
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
  campaigns="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$gateway/promotions/active")" || {
    printf '%sPromotion Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  evaluation="$(printf '[{"productId":1,"basePrice":699.99,"catalogIndex":0,"catalogSize":10}]' | \
    curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
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
      curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
        -H 'Content-Type: application/json' -X POST "$gateway/promotions/codes/validate" --data-binary @-)" || {
      printf '%sNie udalo sie zweryfikowac kodu rabatowego.%s\n' "$C_RED" "$C_RESET" >&2
      return 1
    }
    printf 'Weryfikacja kodu %s: %s\n' "$code" "$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin))')"
    return 0
  fi
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
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
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' -X POST "$gateway/promotions/codes" --data-binary "$payload")" || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Nie udalo sie utworzyc kodu. Sprawdz, czy Promotion Service dziala i czy kod nie istnieje.' 'Could not create the code. Check Promotion Service and whether the code already exists.')" "$C_RESET" >&2
    return 1
  }
  printf '%s%s%s\n' "$C_GREEN" "$(printf '%s' "$response" | python3 -c 'import json,sys; d=json.load(sys.stdin); limit=d["maxRedemptions"] if d["maxRedemptions"] is not None else "bez limitu"; print("Utworzono kod {}: -{}%, wazny {}–{}, limit: {}".format(d["code"], d["discountPercent"], d["validFrom"], d["validTo"], limit))')" "$C_RESET"
}


review_pending() {
  require_command curl || return 1
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 'http://127.0.0.1:18080/api/reviews/moderation/pending' || {
    printf '%sReview Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '\n'
}


returns_list() {
  require_command curl || return 1
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 'http://127.0.0.1:18080/api/returns' || {
    printf '%sReturns Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '\n'
}


returns_status() {
  require_command curl || return 1
  local return_id="${1:-}" status="${2:-}" note="${3:-}" payload response
  if [[ -z "$return_id" ]]; then printf 'ID zwrotu/reklamacji: '; read -r return_id || return 1; fi
  if [[ -z "$status" ]]; then printf 'Status (UNDER_REVIEW/APPROVED/REJECTED/COMPLETED): '; read -r status || return 1; fi
  status="$(printf '%s' "$status" | tr '[:lower:]' '[:upper:]')"
  case "$status" in UNDER_REVIEW|APPROVED|REJECTED|COMPLETED) ;; *) printf '%sNiepoprawny status zgloszenia.%s\n' "$C_RED" "$C_RESET" >&2; return 2 ;; esac
  if [[ -z "$note" ]]; then printf 'Notatka [opcjonalnie]: '; read -r note || return 1; fi
  payload="$(RETURN_STATUS="$status" RETURN_NOTE="$note" python3 -c 'import json,os; print(json.dumps({"status":os.environ["RETURN_STATUS"],"note":os.environ["RETURN_NOTE"] or None}))')"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 -H 'Content-Type: application/json' -X PATCH "http://127.0.0.1:18080/api/returns/$return_id/status" --data-binary "$payload")" || {
    printf '%sNie udalo sie zmienic statusu zgloszenia.%s\n' "$C_RED" "$C_RESET" >&2; return 1;
  }
  printf '%s\n' "$response"
}


billing_invoice() {
  require_command curl || return 1
  require_command python3 || return 1
  local order_id="${1:-}" response order_info
  if [[ -z "$order_id" ]]; then printf 'ID zamowienia: '; read -r order_id || return 1; fi
  [[ "$order_id" =~ ^[0-9]+$ ]] || { printf '%sID zamowienia musi byc liczba.%s\n' "$C_RED" "$C_RESET" >&2; return 2; }

  printf '%sPobieranie faktury dla zamowienia #%s...%s\n' "$C_BLUE" "$order_id" "$C_RESET"
  
  response="$(curl --silent --show-error --connect-timeout 3 --max-time 10 "http://127.0.0.1:18080/api/invoices/order/$order_id" 2>/dev/null)" || true
  
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
  order_info="$(curl --silent --connect-timeout 2 --max-time 5 "http://127.0.0.1:18080/api/orders/$order_id" 2>/dev/null)" || true
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
  [[ "$limit" =~ ^[0-9]+$ && "$limit" -gt 0 ]] || {
    printf '%sLimit musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 15 'http://127.0.0.1:18080/api/orders')" || {
    printf '%sOrder Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s' "$response" | ORDER_LIMIT="$limit" python3 -c '
import json, os, sys
orders = sorted(json.load(sys.stdin), key=lambda value: (value.get("createdAt") or "", value.get("id") or 0), reverse=True)
orders = orders[:int(os.environ["ORDER_LIMIT"])]
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
  if curl --silent --show-error --fail --connect-timeout 3 --max-time 15 "http://127.0.0.1:18080/api/invoices/order/$order_id/pdf" -o "$output_file"; then
    printf '%sPDF faktury zapisany: %s%s\n' "$C_GREEN" "$output_file" "$C_RESET"
  else
    rm -f "$output_file"
    if curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "http://127.0.0.1:18080/api/invoices/order/$order_id" -o /dev/null; then
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
  [[ "$limit" =~ ^[0-9]+$ && "$limit" -gt 0 ]] || {
    printf '%sLimit musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  local raw_json=""
  # Try REST API Gateway first
  raw_json="$(curl --silent --show-error --fail --connect-timeout 2 --max-time 5 "http://127.0.0.1:18080/api/invoices?size=$limit" 2>/dev/null)" || true

  # Fallback to direct DB query if REST API is not reachable
  if [[ -z "$raw_json" || "$raw_json" != \[* ]]; then
    local sql="SELECT coalesce(json_agg(t), '[]'::json) FROM (SELECT id, order_id, invoice_number, gross_amount, currency, customer_email, created_at FROM invoices ORDER BY id DESC LIMIT $limit) t;"
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q 'miniorderplatform-billing-db-1'; then
      raw_json="$(docker exec -i miniorderplatform-billing-db-1 psql -U billing_app -d billing_db -t -A -c "$sql" 2>/dev/null)"
    else
      ensure_local_profile || return 1
      raw_json="$(compose exec -T billing-db psql -U billing_app -d billing_db -t -A -c "$sql" 2>/dev/null)"
    fi
  fi

  if [[ -z "$raw_json" ]]; then
    printf '%sNie udalo sie pobrac listy faktur z serwisu ani z bazy billing_db.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  fi

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
print(f"\nLiczba wystawionych faktur: {len(data)}")
'
}


invoices_export_zip() {
  require_command curl || return 1
  local output_file="${1:-$PROJECT_ROOT/gitIgnoreOwn/runtime/invoices/faktury.zip}"
  mkdir -p "$(dirname "$output_file")"
  printf '%sPobieranie archiwum ZIP ze wszystkimi fakturami...%s\n' "$C_BLUE" "$C_RESET"
  if curl --silent --show-error --fail --connect-timeout 5 --max-time 60 "http://127.0.0.1:18080/api/invoices/export/zip" -o "$output_file" 2>/dev/null || \
     curl --silent --show-error --fail --connect-timeout 5 --max-time 60 "http://127.0.0.1:18095/invoices/export/zip" -o "$output_file"; then
    local file_size
    file_size="$(wc -c < "$output_file" 2>/dev/null || stat -c%s "$output_file" 2>/dev/null || echo "0")"
    printf '%sArchiwum ZIP zapisane pomyslnie: %s (%s bajtow)%s\n' "$C_GREEN" "$output_file" "$file_size" "$C_RESET"
  else
    printf '%sNie udalo sie pobrac archiwum ZIP z fakturami.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  fi
}


invoice_correct() {
  require_command curl || return 1
  require_command python3 || return 1
  local invoice_id="${1:-}" reason="${2:-}" delta="${3:-}" payload response
  if [[ -z "$invoice_id" ]]; then printf 'ID faktury: '; read -r invoice_id || return 1; fi
  [[ "$invoice_id" =~ ^[0-9]+$ ]] || { printf '%sID faktury musi byc liczba.%s\n' "$C_RED" "$C_RESET" >&2; return 2; }
  if [[ -z "$reason" ]]; then printf 'Przyczyna korekty: '; read -r reason || return 1; fi
  if [[ -z "$delta" ]]; then printf 'Kwota zmiany brutto (np. -129.99): '; read -r delta || return 1; fi

  payload="$(CORR_REASON="$reason" CORR_DELTA="$delta" python3 -c 'import json,os; print(json.dumps({"reason": os.environ["CORR_REASON"], "correctionGross": float(os.environ["CORR_DELTA"])}))')"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 -H 'Content-Type: application/json' -X POST "http://127.0.0.1:18080/api/invoices/$invoice_id/corrections" --data-binary "$payload" 2>/dev/null || \
              curl --silent --show-error --fail --connect-timeout 3 --max-time 10 -H 'Content-Type: application/json' -X POST "http://127.0.0.1:18095/invoices/$invoice_id/corrections" --data-binary "$payload")" || {
    printf '%sNie udalo sie wystawic korekty faktury.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s' "$response" | python3 -c '
import json, sys
c = json.load(sys.stdin)
print("\n" + "=" * 60)
print(f"📄 WYSTAWIONO FAKTURĘ KORYGUJĄCĄ: {c.get(\"correctionNumber\")}")
print("=" * 60)
print(f"Do faktury pierwotnej: {c.get(\"originalInvoiceNumber\")}")
print(f"Przyczyna:             {c.get(\"reason\")}")
print(f"Kwota pierwotna:       {c.get(\"originalGross\")} PLN")
print(f"Korekta brutto:        {c.get(\"correctionGross\")} PLN")
print(f"Nowa kwota brutto:     {c.get(\"finalGross\")} PLN")
print("-" * 60)
print(f"📥 Pobierz PDF korekty: http://127.0.0.1:18080/api/invoices/corrections/{c.get(\"id\")}/pdf\n")
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
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "$url" 2>/dev/null || \
              curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "${url/18080\/api/18095}" 2>/dev/null)" || {
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
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 -H 'Content-Type: application/json' -X PATCH "http://127.0.0.1:18080/api/reviews/$review_id/moderation" --data-binary "$payload")" || {
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
  [[ "$limit" =~ ^[0-9]+$ && "$limit" -gt 0 ]] || {
    printf '%sLimit musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$gateway/chat/messages")" || {
    printf '%sChat Service jest niedostepny. Uruchom serwis chat.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s' "$response" | CHAT_LIMIT="$limit" python3 -c 'import json, os, sys; data=json.load(sys.stdin)[:int(os.environ["CHAT_LIMIT"])] ; [(print("ID #{} | {} | {} <{}> | {} | powiadomienie={}".format(item.get("id"), item.get("status"), item.get("customerName"), item.get("customerEmail"), item.get("subject") or "Pytanie", item.get("notificationStatus"))), print("  Wiadomosc: {}".format(item.get("message") or "")), print("  Odpowiedz: {} | e-mail={}".format(item.get("replyMessage") or "-", item.get("replyNotificationStatus") or "-"))) for item in data]'
}


chat_reply() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local chat_id="${1:-}" reply="${2:-}" payload response
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
  payload="$(CHAT_REPLY="$reply" python3 -c 'import json,os; print(json.dumps({"message": os.environ["CHAT_REPLY"]}))')"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' -X POST \
    "$gateway/chat/messages/$chat_id/reply" --data-binary "$payload")" || {
    printf '%sNie udalo sie wyslac odpowiedzi. Sprawdz ID i dostepnosc Chat Service.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%sOdpowiedz wyslana. E-mail zostal przekazany do Notification Service.%s\n' "$C_GREEN" "$C_RESET"
  printf '%s\n' "$response"
}


