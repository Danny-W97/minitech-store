#!/usr/bin/env bash
# QA testing, Playwright UI, verification, and environment doctor diagnostics

run_tests() {
  local alias="${1:-all}"
  export SPRING_PROFILES_ACTIVE="test"
  printf '%sUruchamiam testy z profilem test.%s\n' "$C_BLUE" "$C_RESET"
  if [[ "$alias" == "all" ]]; then
    maven test
  else
    local project
    project="$(app_project "$alias")" || { printf '%sNieznany projekt: %s%s\n' "$C_RED" "$alias" "$C_RESET" >&2; return 2; }
    maven -pl "$project" -am test
  fi
}


run_package() {
  local alias="${1:-all}"
  if [[ "$alias" == "all" ]]; then
    maven package
  else
    local project
    project="$(app_project "$alias")" || { printf '%sNieznany projekt: %s%s\n' "$C_RED" "$alias" "$C_RESET" >&2; return 2; }
    maven -pl "$project" -am package
  fi
}


check_health_one() {
  local alias="$1"
  local port path url status
  port="$(app_port "$alias")" || { printf '%sNieznany projekt: %s%s\n' "$C_RED" "$alias" "$C_RESET" >&2; return 2; }
  path="$(app_health_path "$alias")" || return 2
  url="http://127.0.0.1:${port}${path}"
  if ! status="$(curl --silent --show-error --connect-timeout 2 --max-time 5 \
      -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)"; then
    status='000'
  fi
  if [[ "$status" == "000" ]]; then
    printf '%-18s %sOFFLINE%s (%s)\n' "$alias" "$C_RED" "$C_RESET" "$url"
    return 1
  fi
  printf '%-18s %sHTTP %s%s (%s)\n' "$alias" "$C_GREEN" "$status" "$C_RESET" "$url"
}


health() {
  require_command curl || return 1
  local alias="${1:-all}"
  local failures=0
  if [[ "$alias" == "all" ]]; then
     for app in gateway product order inventory payment notification customer delivery promotion wishlist review recommendation chat returns billing; do
      check_health_one "$app" || failures=$((failures + 1))
    done
  else
    check_health_one "$alias" || failures=$((failures + 1))
  fi
  return "$failures"
}


swagger_ui() {
  printf 'Główny zagregowany Swagger UI (wszystkie 15 mikroserwisów w 1 oknie):\n'
  printf '  -> http://127.0.0.1:18080/swagger-ui.html\n'
}


api_docs_urls() {
  printf 'GŁÓWNY ZAGREGOWANY SWAGGER UI (wszystkie 15 mikroserwisów w 1 oknie):\n'
  printf '  -> http://127.0.0.1:18080/swagger-ui.html\n\n'
  printf 'Bezpośrednie adresy dokumentacji OpenAPI/Swagger poszczególnych usług (dev/test):\n\n'
  local service port
  for service in gateway product order inventory payment notification customer delivery promotion wishlist review recommendation chat returns billing; do
    port="$(app_port "$service")"
    printf '%-14s Swagger UI: http://127.0.0.1:%s/swagger-ui.html\n' "$service" "$port"
    printf '%-14s OpenAPI JSON: http://127.0.0.1:%s/v3/api-docs\n' "$service" "$port"
  done
  printf '\nPubliczne wersjonowane API Gateway: http://127.0.0.1:18080/api/v1\n'
  printf 'Profil prod ma dokumentacje wylaczona domyslnie.\n'
}


qa_authenticated_gate() (
  local gate_script="$1" configured
  if [[ -z "${QA_OPERATOR_TOKEN:-}" ]]; then
    for configured in "${CONTRACT_API_BASE_URL:-http://127.0.0.1:18080}" "${SECURITY_API_BASE_URL:-http://127.0.0.1:18080}" "${LOAD_API_BASE_URL:-http://127.0.0.1:18080}"; do
      case "${configured%/}" in
        http://127.0.0.1:18080|http://localhost:18080|http://127.0.0.1:18080/api/v1|http://localhost:18080/api/v1) ;;
        *) printf 'Custom QA target requires an explicitly supplied QA_OPERATOR_TOKEN.\n' >&2; return 2 ;;
      esac
    done
    case "${LOAD_METRICS_URL:-http://127.0.0.1:18081}" in
      http://127.0.0.1:18081|http://localhost:18081) ;;
      *) printf 'Custom metrics target requires an explicitly supplied QA_OPERATOR_TOKEN.\n' >&2; return 2 ;;
    esac
    load_profile_secrets "$(active_profile)" || return 1
    export QA_OPERATOR_TOKEN="$PLATFORM_OPERATOR_TOKEN"
  fi
  bash "$PROJECT_ROOT/scripts/qa/$gate_script"
)

qa_contract_test() { qa_authenticated_gate contract-test.sh; }
qa_security_test() { qa_authenticated_gate security-test.sh; }


qa_zap_dast() {
  bash "$PROJECT_ROOT/scripts/qa/zap-dast.sh"
}


qa_load_test() {
  qa_authenticated_gate load-test.sh
}


qa_e2e() {
  bash "$PROJECT_ROOT/scripts/qa/e2e.sh"
}


qa_resilience_test() {
  bash "$PROJECT_ROOT/scripts/qa/resilience-test.sh" "$@"
}


ui_package_manager() {
  if command -v pnpm >/dev/null 2>&1; then
    printf 'pnpm'
  elif command -v npm >/dev/null 2>&1; then
    printf 'npm'
  else
    return 1
  fi
}


ui_node_supported() {
  command -v node >/dev/null 2>&1 || return 1
  local major
  major="$(node --version 2>/dev/null | sed -E 's/^v([0-9]+).*/\1/')"
  [[ "$major" =~ ^[0-9]+$ ]] && (( major >= 20 ))
}


qa_ui_exec() {
  require_command node || return 1
  ui_node_supported || {
    printf '%sPlaywright wymaga Node.js 20 lub nowszego. Zainstaluj Node.js 22 LTS w WSL.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  local manager
  manager="$(ui_package_manager)" || {
    printf '%sBrak pnpm albo npm. Zainstaluj Node.js LTS z npm lub wlacz Corepack/pnpm.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  [[ -d "$PROJECT_ROOT/node_modules/@playwright/test" ]] || {
    printf '%sBrak zaleznosci Playwright. Uruchom: ui-test-install%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  (
    cd "$PROJECT_ROOT"
    if [[ "$manager" == 'pnpm' ]]; then
      pnpm exec playwright "$@"
    else
      npx --no-install playwright "$@"
    fi
  )
}


qa_ui_status() {
  printf '%sPlaywright UI E2E - status%s\n\n' "$C_BLUE" "$C_RESET"
  if command -v node >/dev/null 2>&1; then
    if ui_node_supported; then
      printf '%-24s OK (%s)\n' 'node' "$(node --version)"
    else
      printf '%-24s ZA STARY (%s; wymagany >= 20)\n' 'node' "$(node --version)"
    fi
  else
    printf '%-24s BRAK\n' 'node'
  fi
  local manager=''
  manager="$(ui_package_manager 2>/dev/null || true)"
  [[ -n "$manager" ]] && printf '%-24s OK (%s)\n' 'package manager' "$manager" || printf '%-24s BRAK (pnpm/npm)\n' 'package manager'
  [[ -f "$PROJECT_ROOT/package.json" ]] && printf '%-24s OK\n' 'package.json' || printf '%-24s BRAK\n' 'package.json'
  [[ -f "$PROJECT_ROOT/playwright.config.ts" ]] && printf '%-24s OK\n' 'playwright.config.ts' || printf '%-24s BRAK\n' 'playwright.config.ts'
  [[ -d "$PROJECT_ROOT/node_modules/@playwright/test" ]] && printf '%-24s OK\n' 'Playwright package' || printf '%-24s BRAK (ui-test-install)\n' 'Playwright package'
  local test_count
  test_count="$(find "$PROJECT_ROOT/e2e/tests" -type f -name '*.spec.ts' 2>/dev/null | wc -l | tr -d ' ')"
  printf '%-24s %s plikow spec\n' 'testy UI' "${test_count:-0}"
  printf '%-24s %s\n' 'raport' "$PROJECT_ROOT/gitIgnoreOwn/runtime/playwright/report/index.html"
}


qa_ui_install() {
  require_command node || return 1
  ui_node_supported || {
    printf '%sPlaywright wymaga Node.js 20 lub nowszego. Zainstaluj Node.js 22 LTS w WSL i ponow operacje.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  local manager
  manager="$(ui_package_manager)" || {
    printf '%sBrak pnpm albo npm. W WSL zainstaluj Node.js LTS z npm.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  (
    cd "$PROJECT_ROOT"
    if [[ "$manager" == 'pnpm' ]]; then
      pnpm install
      pnpm exec playwright install chromium
    else
      if [[ -f package-lock.json ]]; then npm ci; else npm install --no-package-lock; fi
      npx playwright install chromium
    fi
  )
  printf '\nPlaywright i Chromium sa gotowe. Pozostale silniki zainstalujesz przez ui-test-browsers.\n'
}


qa_ui_smoke() {
  qa_ui_exec test --project=chromium --grep '@smoke' --grep-invert '@full-system'
}


qa_ui_regression() {
  qa_ui_exec test --project=chromium --grep-invert '@full-system'
}


qa_ui_system() {
  printf 'Sprawdzam frontend i serwisy przed pelnym testem przegladarkowym...\n'
  frontend_status || return 1
  health all || return 1
  (
    export PLAYWRIGHT_FULL_SYSTEM=1
    qa_ui_exec test e2e/tests/full-system --project=chromium
  )
}


qa_ui_browsers() {
  local manager
  manager="$(ui_package_manager)" || return 1
  local has_webkit=0
  if ldconfig -p 2>/dev/null | grep -q 'libgtk-4'; then
    has_webkit=1
  fi

  (
    cd "$PROJECT_ROOT"
    if (( has_webkit )); then
      if [[ "$manager" == 'pnpm' ]]; then
        pnpm exec playwright install chromium firefox webkit
      else
        npx --no-install playwright install chromium firefox webkit
      fi
    else
      if [[ "$manager" == 'pnpm' ]]; then
        pnpm exec playwright install chromium firefox
      else
        npx --no-install playwright install chromium firefox
      fi
    fi
  ) || return 1

  if (( ! has_webkit )); then
    printf '\n%s[WSL Info] Silnik WebKit wymaga dodatkowych pakietow systemowych GTK4/GStreamer (sudo npx playwright install-deps webkit).%s\n' "$C_YELLOW" "$C_RESET"
    printf '%sUruchamiam testy dla w pelni wspieranych silnikow: Chromium, Firefox oraz Mobile Chrome.%s\n\n' "$C_BLUE" "$C_RESET"
    qa_ui_exec test --project=chromium --project=firefox --project=mobile-chrome --grep-invert '@full-system'
  else
    qa_ui_exec test --grep-invert '@full-system'
  fi
}


qa_ui_headed() {
  qa_ui_exec test --project=chromium --grep-invert '@full-system' --headed
}


qa_ui_debug() {
  (
    export PWDEBUG=1
    qa_ui_exec test --project=chromium --grep-invert '@full-system' --debug
  )
}


qa_ui_report() {
  local report_directory="$PROJECT_ROOT/gitIgnoreOwn/runtime/playwright/report"
  [[ -f "$report_directory/index.html" ]] || {
    printf '%sBrak raportu. Najpierw uruchom ui-test-smoke albo ui-test.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  local stale_pid
  stale_pid="$(ss -tulpn 2>/dev/null | grep ':19325 ' | grep -o 'pid=[0-9]*' | cut -d= -f2 || true)"
  if [[ -n "$stale_pid" ]]; then
    kill "$stale_pid" 2>/dev/null || true
    sleep 0.5
  fi
  printf 'Raport: http://127.0.0.1:19325\nZakoncz serwer raportu przez Ctrl+C.\n'
  qa_ui_exec show-report "$report_directory" --host 127.0.0.1 --port 19325
}


images_build() {
  bash "$PROJECT_ROOT/scripts/deploy/build-images.sh"
}


k8s_validate() {
  require_command python3 || return 1
  PATH="$PROJECT_ROOT/gitIgnoreOwn/tools/deploy/bin:$PATH" \
    python3 "$PROJECT_ROOT/scripts/deploy/validate-k8s.py" || return 1
  printf 'Renderowanie, scisle schematy i powiazania Kubernetes sa poprawne. Odbior przedprodukcyjny pozostaje otwarty.\n'
}


k8s_status() {
  require_command kubectl || return 1
  kubectl -n minitech-store get deployments,pods,services
}


openbao_status() {
  printf '%sOpenBao — status profilu prod%s\n' "$C_BLUE" "$C_RESET"
  printf 'Adres: %s\n' "$OPENBAO_ADDR"
  if command -v bao >/dev/null 2>&1; then
    local bao_args=(status -format=json)
    [[ -n "$OPENBAO_CACERT" ]] && bao_args+=("-ca-cert=$OPENBAO_CACERT")
    if ! BAO_ADDR="$OPENBAO_ADDR" bao "${bao_args[@]}" 2>/tmp/mini-order-platform-openbao-status.err; then
      printf '%sOpenBao jest niedostepny, zainicjalizowany lub zapieczetowany. Szczegoly techniczne:%s\n' "$C_DIM" "$C_RESET"
      sed -E 's/(token|secret|password|key)[^:]*:[^,}]*/\1: [ukryte]/Ig' \
        /tmp/mini-order-platform-openbao-status.err | head -n 5
      rm -f -- /tmp/mini-order-platform-openbao-status.err
      return 1
    fi
    rm -f -- /tmp/mini-order-platform-openbao-status.err
    return 0
  fi
  if ! command -v curl >/dev/null 2>&1; then
    printf '%sBrak bao i curl — nie mozna sprawdzic endpointu.%s\n' "$C_RED" "$C_RESET"
    return 1
  fi
  local curl_args=(--silent --show-error --connect-timeout 3 --max-time 5)
  [[ -n "$OPENBAO_CACERT" ]] && curl_args+=(--cacert "$OPENBAO_CACERT") || {
    printf '%sBrak OPENBAO_CACERT. Dla produkcji nie pomijam weryfikacji TLS.%s\n' "$C_RED" "$C_RESET"
    return 1
  }
  curl "${curl_args[@]}" "$OPENBAO_ADDR/v1/sys/health" | python3 -c \
    'import json,sys; d=json.load(sys.stdin); print("initialized=%s sealed=%s standby=%s" % (d.get("initialized"), d.get("sealed"), d.get("standby")))'
}


openbao_validate() {
  local failures=0
  printf '%sWalidacja przygotowania OpenBao/TLS prod%s\n' "$C_BLUE" "$C_RESET"
  for file in \
    "$PROJECT_ROOT/infra/openbao/openbao-prod.hcl.example" \
    "$PROJECT_ROOT/infra/openbao/secret-paths.env.example" \
    "$PROJECT_ROOT/infra/openbao/policies/customer-service.hcl.example" \
    "$PROJECT_ROOT/deploy/k8s/overlays/prod/ingress.yaml" \
    "$PROJECT_ROOT/deploy/k8s/overlays/prod/openbao-csi.example.yaml"; do
    if [[ -f "$file" ]]; then
      printf 'OK   %s\n' "${file#"$PROJECT_ROOT/"}"
    else
      printf '%sBRAK %s%s\n' "$C_RED" "${file#"$PROJECT_ROOT/"}" "$C_RESET"
      failures=$((failures + 1))
    fi
  done
  if [[ -n "$OPENBAO_CACERT" && -f "$OPENBAO_CACERT" ]]; then
    printf 'OK   OPENBAO_CACERT (sciezka, wartosc ukryta)\n'
  else
    printf '%sINFO OPENBAO_CACERT nie jest ustawiony — wymagany dopiero przy polaczeniu z produkcyjnym OpenBao.%s\n' "$C_DIM" "$C_RESET"
  fi
  printf 'Nie wykonano inicjalizacji, unseal, tworzenia polityk ani wdrozenia klastra.\n'
  return "$failures"
}


tls_status() {
  require_command openssl || return 1
  printf '%sTLS — kontrola certyfikatu%s\n' "$C_BLUE" "$C_RESET"
  printf 'Cel: %s:%s\n' "$TLS_CHECK_HOST" "$TLS_CHECK_PORT"
  local output openssl_args=(-connect "$TLS_CHECK_HOST:$TLS_CHECK_PORT" -servername "$TLS_CHECK_HOST" -verify_return_error -brief)
  [[ -n "$TLS_CHECK_CACERT" ]] && openssl_args+=(-CAfile "$TLS_CHECK_CACERT")
  output="$(printf '' | openssl s_client "${openssl_args[@]}" 2>&1)" || {
    printf '%sNie mozna potwierdzic certyfikatu TLS. Sprawdz DNS, ingress i CA.%s\n' "$C_RED" "$C_RESET"
    printf '%s\n' "$output" | grep -E 'Verification|verify error|subject=|issuer=' | head -n 5 || true
    return 1
  }
  printf '%s\n' "$output" | grep -E 'Protocol|Ciphersuite|Verification|Peer certificate' || true
}


resilience_status() {
  require_command curl || return 1
  local failures=0
  local service port metrics cb retry bulkhead
  printf '%-14s %-8s %-12s %-12s %-12s %-12s\n' \
    'Serwis' 'HTTP' 'CB metryki' 'Retry metryki' 'Bulkhead' 'Endpoint'
  printf '%-14s %-8s %-12s %-12s %-12s %-12s\n' \
    '------' '----' '----------' '-------------' '--------' '--------'
  for service in gateway order payment customer promotion; do
    port="$(app_port "$service")"
    if ! metrics="$(curl --silent --show-error --fail --connect-timeout 2 --max-time 5 \
        "http://127.0.0.1:${port}/actuator/prometheus" 2>/dev/null)"; then
      printf '%-14s %-8s %-12s %-12s %-12s %s\n' \
        "$service" 'OFFLINE' '-' '-' '-' "http://127.0.0.1:${port}/actuator/prometheus"
      failures=$((failures + 1))
      continue
    fi
    cb="$(printf '%s\n' "$metrics" | grep -c '^resilience4j_circuitbreaker_' || true)"
    retry="$(printf '%s\n' "$metrics" | grep -c '^resilience4j_retry_' || true)"
    bulkhead="$(printf '%s\n' "$metrics" | grep -c '^resilience4j_bulkhead_' || true)"
    printf '%-14s %-8s %-12s %-12s %-12s %s\n' \
      "$service" 'UP' "$cb" "$retry" "$bulkhead" "http://127.0.0.1:${port}/actuator/prometheus"
  done
  printf '\nWartosci oznaczaja liczbe aktualnie wystawianych serii metryk. Nazwy uslug sa widoczne w etykiecie name.\n'
  return "$failures"
}


smoke() {
  require_command curl || return 1
  local url='http://127.0.0.1:18080/api/products'
  printf 'Smoke: %s\n' "$url"
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "$url"
  printf '\n%sSmoke zakonczony powodzeniem.%s\n' "$C_GREEN" "$C_RESET"
}


verify_mvp_create_order() {
  local gateway="$1" correlation_id="$2" guest_access_token="$3" order_payload="$4"
  local idempotency_key attempt max_attempts retry_delay raw='' status body code
  max_attempts="${APP_VERIFY_ORDER_ATTEMPTS:-15}"
  retry_delay="${APP_VERIFY_ORDER_RETRY_SECONDS:-1}"
  idempotency_key="$(python3 -c 'import uuid; print(uuid.uuid4())')" || return 1
  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    raw="$(operator_curl --silent --show-error --connect-timeout 3 --max-time 15 \
      -H "X-Correlation-Id: $correlation_id" \
      -H "X-Order-Access-Token: $guest_access_token" \
      -H "Idempotency-Key: $idempotency_key" \
      -H 'Content-Type: application/json' \
      -w '\n%{http_code}' \
      -X POST "$gateway/orders" --data "$order_payload")" || {
      printf '%sNie udalo sie utworzyc zamowienia testowego: %s%s\n' "$C_RED" "$raw" "$C_RESET" >&2
      return 1
    }
    status="${raw##*$'\n'}"
    body="${raw%$'\n'*}"
    if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
      printf '%s' "$body"
      return 0
    fi
    if [[ "$status" == '409' && "$attempt" -lt "$max_attempts" ]]; then
      code="$(printf '%s' "$body" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("details",{}).get("code",""))' 2>/dev/null || true)"
      if [[ "$code" == 'ORDER_IN_PROGRESS' ]]; then
        sleep "$retry_delay"
        continue
      fi
    fi
    printf '%sNie udalo sie utworzyc zamowienia testowego (HTTP %s): %s%s\n' "$C_RED" "$status" "$body" "$C_RESET" >&2
    return 1
  done
}


verify_mvp() {
  require_command curl || return 1
  require_command python3 || return 1
  local mode="${1:-auto}"
  if [[ "$mode" == 'interactive' && ! -t 0 ]]; then
    mode='auto'
  fi
  local profile gateway timestamp correlation_id product_id quantity recipient
  local guest_access_token
  guest_access_token="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  profile="$(active_profile)"
  case "$profile" in
    dev|test) ;;
    prod)
      printf '%sVerify MVP jest dostepny tylko dla profilu dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
      return 1
      ;;
  esac

  gateway='http://127.0.0.1:18080/api'
  timestamp="$(date +%s)"
  correlation_id="app-verify-${timestamp}-$$"
  if [[ "$mode" == 'auto' || ! -t 0 ]]; then
    product_id="${2:-${APP_VERIFY_PRODUCT_ID:-1}}"
    quantity="${3:-${APP_VERIFY_QUANTITY:-1}}"
    recipient="${4:-${APP_VERIFY_RECIPIENT:-app-verify-${timestamp}@minitech.local}}"
    printf 'Tryb automatyczny: produkt %s, ilosc %s, odbiorca %s\n' \
      "$product_id" "$quantity" "$recipient"
  else
    printf 'Produkt ID [1]: '
    read -r product_id || product_id=''
    product_id="${product_id:-1}"
    printf 'Ilosc [1]: '
    read -r quantity || quantity=''
    quantity="${quantity:-1}"
    recipient="mvp-verify-${timestamp}@minitech.local"
    printf 'Odbiorca [%s]: ' "$recipient"
    read -r entered_recipient || entered_recipient=''
    recipient="${entered_recipient:-$recipient}"
  fi

  [[ "$product_id" =~ ^[0-9]+$ && "$product_id" -gt 0 ]] || {
    printf '%sProdukt ID musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  [[ "$quantity" =~ ^[0-9]+$ && "$quantity" -gt 0 ]] || {
    printf '%sIlosc musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  [[ "$recipient" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || {
    printf '%sNieprawidlowy adres odbiorcy.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }

  printf 'Sprawdzam zdrowie wszystkich mikroserwisow...\n'
  health all || {
    printf '%sNie mozna uruchomic weryfikacji: co najmniej jeden mikroserwis jest niedostepny.%s\n' \
      "$C_RED" "$C_RESET" >&2
    return 1
  }
  mailpit_info >/dev/null || {
    printf '%sMailpit jest niedostepny. Uruchom: mailpit-up%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  local product_response stock_before stock_after order_response payment_response
  local delivery_methods delivery_method_code delivery_quote delivery_quote_payload delivery_cost
  local delivery_subtotal unit_price order_delivery_method order_delivery_cost
  local order_after reservation_response notification_response mailpit_response
  local active available_before available_after order_id reservation_id
  local order_status payment_status reservation_status notification_status mail_count
  product_response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" -H "X-Order-Access-Token: $guest_access_token" \
    "$gateway/products/${product_id}")" || {
    printf '%sNie mozna pobrac produktu z API Gateway.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  active="$(printf '%s' "$product_response" | python3 -c 'import json,sys; print(str(json.load(sys.stdin).get("active", False)).lower())')"
  [[ "$active" == 'true' ]] || {
    printf '%sProdukt nie jest aktywny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  stock_before="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" -H "X-Order-Access-Token: $guest_access_token" \
    "$gateway/inventory/items/${product_id}")" || {
    printf '%sNie mozna pobrac stanu magazynowego.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  available_before="$(printf '%s' "$stock_before" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("availableQuantity", -1))' 2>/dev/null || echo 0)"
  if ! [[ "$available_before" =~ ^[0-9]+$ ]] || (( available_before < quantity )); then
    if [[ "$mode" == 'auto' || ! -t 0 ]]; then
      local qa_stock_operation
      qa_stock_operation="$(python3 -c 'import uuid; print(uuid.uuid4())')" || return 1
      inventory_receive "$product_id" 50 "$qa_stock_operation" >/dev/null || return 1
      stock_before="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
        -H "X-Correlation-Id: $correlation_id" -H "X-Order-Access-Token: $guest_access_token" \
        "$gateway/inventory/items/${product_id}")" || true
      available_before="$(printf '%s' "$stock_before" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("availableQuantity", -1))' 2>/dev/null || echo 0)"
    fi
  fi
  if ! [[ "$available_before" =~ ^[0-9]+$ ]] || (( available_before < quantity )); then
    printf '%sBrak wystarczajacej ilosci. Dostepne: %s, wymagane: %s.%s\n' \
      "$C_RED" "$available_before" "$quantity" "$C_RESET" >&2
    return 1
  fi

  delivery_methods="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" -H "X-Order-Access-Token: $guest_access_token" \
    "$gateway/deliveries/methods")" || {
    printf '%sNie mozna pobrac metod dostawy z Delivery Service.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  delivery_method_code="$(printf '%s' "$delivery_methods" | python3 -c 'import json,sys; data=json.load(sys.stdin); print(data[0]["code"] if data else "")')"
  [[ -n "$delivery_method_code" ]] || {
    printf '%sDelivery Service nie zwrocil aktywnej metody dostawy.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  unit_price="$(printf '%s' "$product_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("price", 0))')"
  delivery_subtotal="$(UNIT_PRICE="$unit_price" QUANTITY="$quantity" python3 -c 'from decimal import Decimal; import os; print(Decimal(os.environ["UNIT_PRICE"]) * int(os.environ["QUANTITY"]))')"
  delivery_quote_payload="$(DELIVERY_METHOD_CODE="$delivery_method_code" DELIVERY_QUANTITY="$quantity" DELIVERY_SUBTOTAL="$delivery_subtotal" \
    python3 -c 'import json,os; print(json.dumps({"methodCode": os.environ["DELIVERY_METHOD_CODE"], "postalCode": "00-001", "itemCount": int(os.environ["DELIVERY_QUANTITY"]), "subtotal": float(os.environ["DELIVERY_SUBTOTAL"])}))')"
  delivery_quote="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" -H "X-Order-Access-Token: $guest_access_token" -H 'Content-Type: application/json' \
    -X POST "$gateway/deliveries/quote" --data "$delivery_quote_payload")" || {
    printf '%sNie mozna wyliczyc kosztu dostawy w Delivery Service.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  delivery_cost="$(printf '%s' "$delivery_quote" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("deliveryCost", 0))')"
  [[ "$delivery_cost" =~ ^[0-9]+([.][0-9]+)?$ ]] || {
    printf '%sDelivery Service zwrocil nieprawidlowy koszt dostawy.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  local order_payload payment_payload
  order_payload="$(printf '{\n  \"productId\": %s,\n  \"quantity\": %s,\n  \"customerId\": null,\n  \"customerEmail\": \"%s\",\n  \"deliveryAddress\": {\n    \"firstName\": \"MVP\",\n    \"lastName\": \"Verifier\",\n    \"street\": \"Control Center 1\",\n    \"postalCode\": \"00-001\",\n    \"city\": \"Warszawa\",\n    \"country\": \"Polska\"\n  },\n  \"deliveryMethod\": \"%s\",\n  \"deliveryCost\": %s\n}' \
    "$product_id" "$quantity" "$(json_escape "$recipient")" "$delivery_method_code" "$delivery_cost")"
  printf 'Tworze zamowienie testowe...\n'
  order_response="$(verify_mvp_create_order "$gateway" "$correlation_id" "$guest_access_token" "$order_payload")" || return 1
  order_id="$(printf '%s' "$order_response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
  reservation_id="$(printf '%s' "$order_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("inventoryReservationId", ""))')"
  order_delivery_method="$(printf '%s' "$order_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("deliveryMethod", ""))')"
  order_delivery_cost="$(printf '%s' "$order_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("deliveryCost", ""))')"
  [[ -n "$reservation_id" ]] || {
    printf '%sZamowienie nie zawiera identyfikatora rezerwacji Inventory.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  payment_payload="$(printf '{\n  \"orderId\": %s,\n  \"paymentMethod\": \"CARD\",\n  \"simulateFailure\": false,\n  \"notificationRecipient\": \"%s\",\n  \"idempotencyKey\": \"mvp-%s\"\n}' \
    "$order_id" "$(json_escape "$recipient")" "$order_id")"
  printf 'Przetwarzam platnosc testowa...\n'
  payment_response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 15 \
    -H "X-Correlation-Id: $correlation_id" -H "X-Order-Access-Token: $guest_access_token" \
    -H 'Content-Type: application/json' -X POST "$gateway/payments" --data "$payment_payload")" || {
    printf '%sPlatnosc testowa nie powiodla sie dla zamowienia #%s.%s\n' "$C_RED" "$order_id" "$C_RESET" >&2
    return 1
  }

  order_after="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" -H "X-Order-Access-Token: $guest_access_token" \
    "$gateway/orders/${order_id}")" || return 1
  reservation_response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" -H "X-Order-Access-Token: $guest_access_token" \
    "$gateway/inventory/reservations/${reservation_id}")" || return 1
  stock_after="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" -H "X-Order-Access-Token: $guest_access_token" \
    "$gateway/inventory/items/${product_id}")" || return 1
  order_status="$(printf '%s' "$order_after" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", ""))')"
  payment_status="$(printf '%s' "$payment_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", ""))')"
  reservation_status="$(printf '%s' "$reservation_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", ""))')"
  available_after="$(printf '%s' "$stock_after" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("availableQuantity", -1))')"

  notification_status='PENDING'
  for attempt in {1..20}; do
    notification_response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
      -H "X-Correlation-Id: $correlation_id" -H "X-Order-Access-Token: $guest_access_token" \
      "$gateway/notifications/source/${order_id}" 2>/dev/null || true)"
    if [[ -n "$notification_response" ]]; then
      notification_status="$(printf '%s' "$notification_response" | python3 -c 'import json,sys; print("SENT" if any(item.get("status") == "SENT" for item in json.load(sys.stdin)) else "PENDING")')"
      [[ "$notification_status" == 'SENT' ]] && break
    fi
    sleep 1
  done

  mail_count='0'
  for attempt in {1..10}; do
    mailpit_response="$(operator_curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
      --get "http://127.0.0.1:${MAILPIT_WEB_PORT}/api/v1/search" \
      --data-urlencode "query=to:${recipient}" 2>/dev/null || true)"
    if [[ -n "$mailpit_response" ]]; then
      mail_count="$(printf '%s' "$mailpit_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("count", 0))')"
      (( mail_count > 0 )) && break
    fi
    sleep 1
  done

  printf '\n%sWynik Verify MVP%s\n' "$C_BLUE" "$C_RESET"
  printf '%-24s %s\n' 'Profil' "$profile"
  printf '%-24s #%s\n' 'Zamowienie' "$order_id"
  printf '%-24s %s\n' 'Order status' "$order_status"
  printf '%-24s %s\n' 'Payment status' "$payment_status"
  printf '%-24s %s\n' 'Reservation status' "$reservation_status"
  printf '%-24s %s (%s zl)\n' 'Delivery' "$order_delivery_method" "$order_delivery_cost"
  printf '%-24s %s -> %s\n' 'Dostepna ilosc' "$available_before" "$available_after"
  printf '%-24s %s\n' 'Notification' "$notification_status"
  printf '%-24s %s\n' 'Mailpit messages' "$mail_count"

  local failures=0
  [[ "$order_status" == 'PAID' ]] || failures=$((failures + 1))
  [[ "$payment_status" == 'SUCCEEDED' ]] || failures=$((failures + 1))
  [[ "$reservation_status" == 'CONFIRMED' ]] || failures=$((failures + 1))
  [[ "$order_delivery_method" == "$delivery_method_code" ]] || failures=$((failures + 1))
  [[ "$available_after" =~ ^[0-9]+$ && "$available_before" =~ ^[0-9]+$ ]] \
    && (( available_after == available_before - quantity )) || failures=$((failures + 1))
  [[ "$notification_status" == 'SENT' ]] || failures=$((failures + 1))
  (( mail_count > 0 )) || failures=$((failures + 1))
  if (( failures == 0 )); then
    printf '%sVerify MVP zakonczony powodzeniem.%s\n' "$C_GREEN" "$C_RESET"
    return 0
  fi
  printf '%sVerify MVP wykryl %s problemow.%s\n' "$C_RED" "$failures" "$C_RESET" >&2
  return 1
}


app_verify() {
  verify_mvp auto
}


environment_add_requirement() {
  local requirement="$1"
  local existing
  for existing in "${ENVIRONMENT_MISSING_REQUIREMENTS[@]}"; do
    [[ "$existing" == "$requirement" ]] && return 0
  done
  ENVIRONMENT_MISSING_REQUIREMENTS+=("$requirement")
}


environment_jdk25_available() {
  local java_version java_major
  command -v java >/dev/null 2>&1 || return 1
  command -v javac >/dev/null 2>&1 || return 1
  java_version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  java_major="${java_version%%.*}"
  [[ "$java_major" == '25' || "$java_major" == 25-* ]]
}


environment_scan_requirements() {
  ENVIRONMENT_MISSING_REQUIREMENTS=()

  for command in bash curl python3 git; do
    command -v "$command" >/dev/null 2>&1 || environment_add_requirement "$command"
  done

  environment_jdk25_available || environment_add_requirement jdk25

  command -v sops >/dev/null 2>&1 || environment_add_requirement sops
  if ! command -v age >/dev/null 2>&1 || ! command -v age-keygen >/dev/null 2>&1; then
    environment_add_requirement age
  fi

  if command -v docker >/dev/null 2>&1; then
    docker compose version >/dev/null 2>&1 || environment_add_requirement compose
  else
    environment_add_requirement docker
    environment_add_requirement compose
  fi

  if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    environment_add_requirement runtime
  fi
}


environment_print_install_locations() {
  local command
  printf '\nLokalizacje wykrytych narzedzi:\n'
  for command in java javac python3 curl git sops age age-keygen docker; do
    if command -v "$command" >/dev/null 2>&1; then
      printf '  %-12s %s\n' "$command" "$(command -v "$command")"
    fi
  done
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    printf '  %-12s docker CLI plugin\n' 'compose'
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    printf '  %-12s %s\n' 'JAVA_HOME' "$JAVA_HOME"
  fi
  printf '  %-12s %s\n' 'konfiguracja' "$CONFIG_DIRECTORY"
  printf '  %-12s %s\n' 'sekrety' "$SECRET_ROOT_DIRECTORY"
}


environment_install() {
  if [[ "$PLATFORM_ID" == 'windows' ]]; then
    printf '%sInstalator jest dostepny tylko w Bashu uruchomionym w WSL.%s\n' \
      "$C_RED" "$C_RESET" >&2
    printf 'Uruchom .\\scripts\\start.ps1 jako most do WSL albo wejdz do swojej dystrybucji WSL i uruchom:\n' >&2
    printf '  bash scripts/control-center/control-center.sh environment-install\n' >&2
    return 2
  fi

  environment_scan_requirements
  local manager="$(platform_package_manager)"
  local -a installable_requirements=()
  local -a manual_requirements=()
  local requirement package

  printf '%sMini Order Platform - instalator brakujacych skladnikow%s\n\n' "$C_BLUE" "$C_RESET"
  platform_print_info
  printf 'Menedzer pakietow: %s\n' "$manager"
  printf 'Docelowe miejsce instalacji: %s\n\n' "$(platform_install_target)"

  if ((${#ENVIRONMENT_MISSING_REQUIREMENTS[@]} == 0)); then
    printf '%sNie wykryto brakujacych skladnikow systemowych.%s\n' "$C_GREEN" "$C_RESET"
    environment_print_install_locations
    return 0
  fi

  printf 'Wykryte braki:\n'
  for requirement in "${ENVIRONMENT_MISSING_REQUIREMENTS[@]}"; do
    package="$(platform_install_package_name "$manager" "$requirement" 2>/dev/null || true)"
    if [[ -n "$package" ]]; then
      installable_requirements+=("$requirement")
      printf '  - %-30s pakiet: %s\n' \
        "$(platform_install_requirement_label "$requirement")" "$package"
    else
      manual_requirements+=("$requirement")
      printf '  - %-30s konfiguracja reczna / poza zakresem instalatora\n' \
        "$(platform_install_requirement_label "$requirement")"
    fi
    printf '    miejsce: %s\n' "$(platform_install_requirement_location "$requirement")"
  done

  if ((${#manual_requirements[@]} > 0)); then
    printf '\nElementy, ktorych Centrala nie zainstaluje automatycznie:\n'
    for requirement in "${manual_requirements[@]}"; do
      if [[ "$requirement" == 'runtime' ]]; then
        printf '  - %s: uruchom lub skonfiguruj runtime dla linuksowych kontenerow.\n' \
          "$(platform_install_requirement_label "$requirement")"
        printf '    Centrala nie wybiera za uzytkownika Docker Engine, integracji Docker-WSL ani runtime macOS.\n'
      else
        printf '  - %s: brak obslugi w wykrytym menedzerze pakietow.\n' \
          "$(platform_install_requirement_label "$requirement")"
      fi
    done
  fi

  if ((${#installable_requirements[@]} == 0)); then
    printf '\n%sBrak skladnikow, ktore mozna bezpiecznie zainstalowac automatycznie.%s\n' \
      "$C_RED" "$C_RESET" >&2
    return 1
  fi

  printf '\nInstalator wykona tylko powyzsze operacje pakietowe.\n'
  printf 'Moze poprosic o haslo sudo i pobierze pakiety z systemowego repozytorium.\n'
  printf 'Aby kontynuowac, wpisz dokladnie ACCEPT. Enter lub inna wartosc = anulowanie: '
  local confirmation
  IFS= read -r confirmation || return 1
  if [[ "$confirmation" != 'ACCEPT' ]]; then
    printf 'Anulowano instalacje. Nie zmieniono systemu.\n'
    return 0
  fi

  printf '\nRozpoczynam instalacje przez %s...\n' "$manager"
  platform_install_packages "$manager" "${installable_requirements[@]}" || {
    printf '%sInstalacja zakonczona bledem. Uruchom ponownie environment-install po usunieciu problemu.%s\n' \
      "$C_RED" "$C_RESET" >&2
    return 1
  }
  platform_post_install_environment
  environment_print_install_locations
  printf '\nPonawiam diagnostyke po instalacji:\n\n'
  environment_check
}


doctor() {
  local failures=0 profile
  profile="$(active_profile)"
  printf '%sMini Order Platform - doctor%s\n\n' "$C_BLUE" "$C_RESET"
  printf 'Aktywny profil: %s\n\n' "$profile"
  preflight || failures=$((failures + 1))

  if [[ "$profile" == 'dev' || "$profile" == 'test' ]]; then
    printf '\nWalidacja sekretow profilu %s:\n' "$profile"
    secrets_validate "$profile" || failures=$((failures + 1))
  fi

  printf '\nKontenery infrastruktury:\n'
  compose ps || failures=$((failures + 1))
  printf '\nZdrowie aplikacji:\n'
  health all || failures=$((failures + 1))

  printf '\nMailpit:\n'
  if mailpit_info >/dev/null; then
    printf '%sMailpit API OK%s (panel: http://127.0.0.1:%s)\n' "$C_GREEN" "$C_RESET" "$MAILPIT_WEB_PORT"
  else
    printf '%sMailpit niedostepny%s\n' "$C_RED" "$C_RESET"
    failures=$((failures + 1))
  fi

  printf '\nProcesy aplikacji uruchamiane przez centrale:\n'
  app_status_all
  printf '\nFrontend:\n'
  frontend_status || true
  printf '\nMonitoring (opcjonalny):\n'
  if curl --silent --show-error --fail --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:${PROMETHEUS_PORT}/-/ready" >/dev/null 2>&1; then
    printf '%sPrometheus OK%s\n' "$C_GREEN" "$C_RESET"
  else
    printf '%sPrometheus niedostepny (ostrzezenie)%s\n' "$C_DIM" "$C_RESET"
  fi
  if curl --silent --show-error --fail --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:${GRAFANA_PORT}/api/health" >/dev/null 2>&1; then
    printf '%sGrafana OK%s\n' "$C_GREEN" "$C_RESET"
  else
    printf '%sGrafana niedostepna (ostrzezenie)%s\n' "$C_DIM" "$C_RESET"
  fi

  if (( failures == 0 )); then
    printf '\n%sDoctor: srodowisko gotowe.%s\n' "$C_GREEN" "$C_RESET"
    return 0
  fi
  printf '\n%sDoctor: wykryto %s problemow wymagajacych uwagi.%s\n' "$C_RED" "$failures" "$C_RESET" >&2
  return 1
}


github_check() {
  local failures=0 tracked_sensitive disk_sensitive private_probe file
  printf '%sMini Order Platform - kontrola gotowosci do GitHub%s\n\n' "$C_BLUE" "$C_RESET"

  if [[ -f "$PROJECT_ROOT/.gitignore" ]]; then
    printf '%-24s OK\n' '.gitignore'
  else
    printf '%-24s BRAK\n' '.gitignore'
    failures=$((failures + 1))
  fi

  if tr -d '\r' < "$PROJECT_ROOT/.gitignore" 2>/dev/null | grep -Fxq '/docs/APKA.md'; then
    printf '%-24s OK (wykluczony)\n' 'docs/APKA.md'
  else
    printf '%-24s BRAK reguly wykluczenia\n' 'docs/APKA.md'
    failures=$((failures + 1))
  fi

  for file in '/output/' '/tmp/'; do
    if tr -d '\r' < "$PROJECT_ROOT/.gitignore" 2>/dev/null | grep -Fxq "$file"; then
      printf '%-24s OK (ignorowany)\n' "$file"
    else
      printf '%-24s BRAK reguly ignorowania\n' "$file"
      failures=$((failures + 1))
    fi
  done

  if [[ -f "$PROJECT_ROOT/gitIgnoreOwn/README.md" ]]; then
    printf '%-24s OK\n' 'gitIgnoreOwn/README.md'
  else
    printf '%-24s BRAK\n' 'gitIgnoreOwn/README.md'
    failures=$((failures + 1))
  fi

  for file in "$PROJECT_ROOT/.gitattributes" "$PROJECT_ROOT/.github/workflows/ci.yml"; do
    if [[ -f "$file" ]]; then
      printf '%-24s OK\n' "${file#"$PROJECT_ROOT/"}"
    else
      printf '%-24s BRAK\n' "${file#"$PROJECT_ROOT/"}"
      failures=$((failures + 1))
    fi
  done

  disk_sensitive="$(find "$PROJECT_ROOT" -type f \( -name '.env' -o -name '.env.*' -o -name '*.env.sops' -o -name '*.agekey' -o -name '*.key' -o -name '*.pem' -o -name '*.crt' -o -name '*.cer' -o -name '*.p12' -o -name '*.pfx' -o -name '*.jks' -o -name '*.kubeconfig' -o -name 'kubeconfig' -o -name '*.dump' -o -name '*.dump.age' \) ! -path "$PROJECT_ROOT/gitIgnoreOwn/*" ! -path '*/target/*' ! -path '*/.git/*' ! -name '*.example' -print 2>/dev/null || true)"
  if [[ -n "$disk_sensitive" ]]; then
    printf '%sWrazliwe pliki poza gitIgnoreOwn:%s\n%s\n' "$C_RED" "$C_RESET" "$disk_sensitive" >&2
    failures=$((failures + 1))
  else
    printf '%-24s OK (brak prywatnych plikow poza gitIgnoreOwn)\n' 'pliki lokalne'
  fi

  require_command git || {
    printf '%sGit jest potrzebny do kontroli repozytorium przed publikacja.%s\n' "$C_RED" "$C_RESET" >&2
    failures=$((failures + 1))
  }

  if ! git -C "$PROJECT_ROOT" rev-parse --show-toplevel >/dev/null 2>&1; then
    printf '%sRepozytorium Git nie jest jeszcze zainicjalizowane; wykonano kontrole statyczne.%s\n' \
      "$C_DIM" "$C_RESET"
  else
    tracked_sensitive="$(git -C "$PROJECT_ROOT" ls-files | grep -Ei \
      '(^|/)\.env($|\.[^/]+$)|\.(agekey|key|pem|crt|cer|p12|pfx|jks|kubeconfig)$|(^|/)kubeconfig$' \
      | grep -Ev '(/\.gitkeep$|\.example$)' || true)"
    if [[ -n "$tracked_sensitive" ]]; then
      printf '%sWykryto wrazliwe pliki sledzone przez Git:%s\n%s\n' \
        "$C_RED" "$C_RESET" "$tracked_sensitive" >&2
      failures=$((failures + 1))
    else
      printf '%-24s OK (brak sledzonych sekretow)\n' 'pliki wrazliwe'
    fi

    private_probe='gitIgnoreOwn/secrets/example.env'
    if git -C "$PROJECT_ROOT" check-ignore --no-index -q "$private_probe"; then
      printf '%-24s OK (ignorowany)\n' 'gitIgnoreOwn/secrets'
    else
      printf '%-24s BRAK reguly ignorowania\n' 'gitIgnoreOwn/secrets'
      failures=$((failures + 1))
    fi

    if git -C "$PROJECT_ROOT" ls-files --error-unmatch 'docs/APKA.md' >/dev/null 2>&1; then
      printf '%sdocs/APKA.md jest sledzony przez Git.%s\n' "$C_RED" "$C_RESET" >&2
      failures=$((failures + 1))
    elif git -C "$PROJECT_ROOT" check-ignore --no-index -q 'docs/APKA.md'; then
      printf '%-24s OK (niesledzony)\n' 'prywatny APKA.md'
    else
      printf '%-24s BRAK ochrony\n' 'prywatny APKA.md'
      failures=$((failures + 1))
    fi
  fi

  if (( failures == 0 )); then
    printf '\n%sGitHub check: nie wykryto problemow.%s\n' "$C_GREEN" "$C_RESET"
    return 0
  fi
  printf '\n%sGitHub check: wykryto %s problemow.%s\n' "$C_RED" "$failures" "$C_RESET" >&2
  return 1
}


environment_check() {
  local failures=0 profile java_version java_major
  printf '%sMini Order Platform - kontrola przed startem%s\n\n' "$C_BLUE" "$C_RESET"
  preflight || failures=$((failures + 1))

  profile="$(active_profile)"
  printf '\nProfil: %s\n' "$profile"
  if ensure_local_profile; then
    if secrets_validate "$profile"; then
      printf '%-24s OK\n' 'sekrety profilu'
    else
      printf '%-24s BRAK lub blad\n' 'sekrety profilu'
      failures=$((failures + 1))
    fi
  else
    failures=$((failures + 1))
  fi

  if command -v java >/dev/null 2>&1; then
    java_version="$(java -version 2>&1 | awk -F '\"' '/version/ {print $2; exit}')"
    java_major="${java_version%%.*}"
    if [[ "$java_major" == '25' ]]; then
      printf '%-24s OK (%s)\n' 'JDK' "$java_version"
    else
      printf '%-24s BRAK JDK 25 (wykryto %s)\n' 'JDK' "${java_version:-nieznana}"
      failures=$((failures + 1))
    fi
  fi

  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    printf '%-24s OK (daemon)\n' 'Docker'
    if compose config --quiet >/dev/null 2>&1; then
      printf '%-24s OK\n' 'Compose + sekrety'
    else
      printf '%-24s BRAK lub blad konfiguracji\n' 'Compose + sekrety'
      failures=$((failures + 1))
    fi
  else
    printf '%-24s BRAK lub daemon nie dziala\n' 'Docker'
    failures=$((failures + 1))
  fi

  if (( failures == 0 )); then
    printf '\n%sKontrola przed startem: srodowisko gotowe.%s\n' "$C_GREEN" "$C_RESET"
    printf 'Mozesz uruchomic: infra-up, broker-up, mailpit-up, app-run-all, frontend-up\n'
    return 0
  fi
  printf '\n%sKontrola przed startem: wykryto %s problemow. Nie uruchamiaj aplikacji przed ich usunieciem.%s\n' \
    "$C_RED" "$failures" "$C_RESET" >&2
  printf 'Jesli problemy dotycza brakujacych narzedzi systemowych, mozesz uruchomic opcjonalny instalator:\n' >&2
  printf '  bash scripts/control-center/control-center.sh environment-install\n' >&2
  return 1
}


preflight() {
  local failures=0
  printf 'Mini Order Platform - kontrola wymagan\n\n'
  platform_print_info
  printf '\n'
  for command in bash curl java javac python3; do
    if command -v "$command" >/dev/null 2>&1; then
      printf '%-12s OK\n' "$command"
    else
      printf '%-12s BRAK\n' "$command"
      failures=$((failures + 1))
    fi
  done

  if command -v docker >/dev/null 2>&1; then
    printf '%-12s OK\n' 'docker'
    if docker compose version >/dev/null 2>&1; then
      printf '%-12s OK\n' 'compose'
    else
      printf '%-12s BRAK\n' 'compose'
      failures=$((failures + 1))
    fi
  else
    printf '%-12s BRAK\n' 'docker'
    printf '%-12s BRAK\n' 'compose'
    failures=$((failures + 2))
  fi

  if command -v git >/dev/null 2>&1; then
    printf '%-12s OK (GitHub)\n' 'git'
  else
    printf '%-12s OSTRZEZENIE (potrzebny do GitHub)\n' 'git'
  fi

  for file in "$COMPOSE_FILE" "$MONITORING_COMPOSE_FILE" "$MAVEN_WRAPPER" "$PROJECT_ROOT/pom.xml" "$FRONTEND_DIRECTORY/index.html"; do
    if [[ -f "$file" ]]; then
      printf '%-12s OK %s\n' 'plik' "$file"
    else
      printf '%-12s BRAK %s\n' 'plik' "$file"
      failures=$((failures + 1))
    fi
  done

  printf '\nSkarbiec sekretow WSL:\n'
  if secrets_tools_check; then
    printf '%-12s OK\n' 'secrets'
  else
    printf '%-12s BRAK narzedzi SOPS/age\n' 'secrets'
    failures=$((failures + 1))
  fi

  printf '\nMapa baz:\n'
  print_database_catalog
  return "$failures"
}


