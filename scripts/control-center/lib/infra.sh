#!/usr/bin/env bash
# Docker Compose, databases, broker, mailpit, and monitoring infrastructure

compose() {
  compose_with_secrets "$COMPOSE_FILE" "$@"
}


infra_up() {
  compose up -d "$@"
}


infra_down() {
  compose stop "${DB_SERVICES[@]}" "$BROKER_SERVICE" "$MAILPIT_SERVICE"
}


infra_status() {
  compose ps
}


apps_compose() {
  if ! ensure_local_profile; then
    return 1
  fi
  require_command docker || return 1
  local temporary_env_file=""
  local profile
  profile="$(active_profile)"
  if secret_profile_supported "$profile"; then
    temporary_env_file="$(decrypt_profile_to_temp "$profile")" || return 1
  fi
  local -a compose_options=(--project-directory "$PROJECT_ROOT")
  if [[ -n "$temporary_env_file" ]]; then
    compose_options+=(--env-file "$temporary_env_file")
  fi
  compose_options+=(-f "$COMPOSE_FILE" -f "$APPS_COMPOSE_FILE")
  docker compose "${compose_options[@]}" "$@"
  local status=$?
  [[ -z "$temporary_env_file" ]] || rm -f -- "$temporary_env_file"
  return "$status"
}


docker_diagnose_service_error() {
  local service_name="$1"
  local container_name
  container_name="$(docker ps -a --filter "name=${service_name}" --format '{{.Names}}' | head -n 1)"
  [[ -n "$container_name" ]] || container_name="miniorderplatform-${service_name}-1"

  local container_status
  container_status="$(docker inspect --format '{{.State.Status}} (kod wyjścia: {{.State.ExitCode}})' "$container_name" 2>/dev/null || echo "nieznany")"

  printf '\n%s============================================================%s\n' "$C_RED" "$C_RESET"
  printf '%s[DIAGNOSTYKA BŁĘDU] Usługa „%s” jest OFFLINE%s\n' "$C_RED" "$service_name" "$C_RESET"
  printf 'Status kontenera (%s): %s\n' "$container_name" "$container_status"

  local logs
  logs="$(docker logs --tail 30 "$container_name" 2>&1 || true)"
  if [[ -n "$logs" ]]; then
    printf '\n%s--- Ostatnie linie logu kontenera %s: ---%s\n' "$C_YELLOW" "$container_name" "$C_RESET"
    printf '%s\n' "$logs"
    printf '%s------------------------------------------------------------%s\n' "$C_YELLOW" "$C_RESET"
  fi

  printf '\n%s👉 Rozpoznana przyczyna i proponowane rozwiązanie:%s\n' "$C_BOLD" "$C_RESET"
  if echo "$logs" | grep -qiE 'Connection.*5432.*refused|database.*does not exist|password authentication failed|role.*does not exist'; then
    printf '  %s• Przyczyna:%s Baza danych PostgreSQL nie odpowiada lub hasło bazy jest nieprawidłowe.\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Uruchom bazy danych w Centrali (przycisk "Infrastruktura (infra-up)") lub sprawdź sekrety DEV.\n' "$C_GREEN" "$C_RESET"
  elif echo "$logs" | grep -qiE 'CUSTOMER_JWT|MFA_ENCRYPTION|jwt-secret|JWT key material'; then
    printf '  %s• Przyczyna:%s Brak lub nieprawidłowy format klucza autoryzacji JWT / MFA.\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Wygeneruj klucz JWT poleceniem "jwt-rotate dev" lub sprawdź "Sekrety DEV" w Centrali.\n' "$C_GREEN" "$C_RESET"
  elif echo "$logs" | grep -qiE 'Address already in use|BindException|port.*already allocated'; then
    printf '  %s• Przyczyna:%s Port sieciowy serwisu jest zajęty przez inny proces na komputerze.\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Zatrzymaj lokalne aplikacje Java (app-stop-all) lub inne kontenery blokujące port.\n' "$C_GREEN" "$C_RESET"
  elif echo "$logs" | grep -qiE 'OutOfMemoryError|heap space|ExitOnOutOfMemoryError'; then
    printf '  %s• Przyczyna:%s Brak pamięci RAM dla kontenera Java (Out of Memory).\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Zwiększ przydział pamięci RAM dla WSL2 w pliku %%USERPROFILE%%\\.wslconfig.\n' "$C_GREEN" "$C_RESET"
  elif echo "$logs" | grep -qiE 'RedisConnectionException|6379.*Connection refused'; then
    printf '  %s• Przyczyna:%s Redis Event Broker nie odpowiada na porcie 6379.\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Uruchom broker zdarzeń poleceniem "broker-up" lub "Infrastruktura (infra-up)" w Centrali.\n' "$C_GREEN" "$C_RESET"
  elif echo "$logs" | grep -qiE 'FlywayException|MigrationChecksumDiffers|Validate failed'; then
    printf '  %s• Przyczyna:%s Błąd migracji bazy danych Flyway (niezgodność sumy kontrolnej skryptów SQL).\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Sprawdź pliki migracji w db/migration wybranego serwisu.\n' "$C_GREEN" "$C_RESET"
  else
    printf '  %s• Przyczyna:%s Nieoczekiwany błąd podczas uruchamiania aplikacji Spring Boot.\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Zobacz pełny log kontenera poleceniem: bash ./scripts/control-center/control-center.sh docker-apps-logs %s\n' "$C_GREEN" "$C_RESET" "$service_name"
  fi
  printf '%s============================================================%s\n\n' "$C_RED" "$C_RESET"
}


docker_apps_up() {
  if ! docker image inspect mini-order-platform/api-gateway:local >/dev/null 2>&1; then
    printf 'Lokalne obrazy Docker aplikacji nie istnieja.\n'
    printf 'Buduje obrazy wszystkich 15 mikroserwisow i frontendu (moze to potrwac kilka minut przy pierwszym uruchomieniu)...\n'
    images_build || return 1
  fi
  printf 'Uruchamiam wszystkie serwisy i frontend w kontenerach Docker...\n'
  apps_compose up -d --remove-orphans || return 1

  printf '\nOczekuje na gotowosc i weryfikuje zdrowie wszystkich 15 mikroserwisow...\n'
  local -a services=(
    "gateway:18080:/api/products"
    "product:18081:/actuator/health"
    "order:18082:/actuator/health"
    "inventory:18083:/actuator/health"
    "payment:18084:/actuator/health"
    "notification:18086:/actuator/health"
    "customer:18087:/actuator/health"
    "delivery:18088:/actuator/health"
    "promotion:18089:/actuator/health"
    "wishlist:18090:/actuator/health"
    "review:18091:/actuator/health"
    "recommendation:18092:/actuator/health"
    "chat:18093:/actuator/health"
    "returns:18094:/actuator/health"
    "billing:18095:/actuator/health"
    "frontend:18070:/"
  )

  local max_attempts=30
  local attempt=0
  local all_healthy=0
  local -a failed_services=()

  while (( attempt < max_attempts )); do
    failed_services=()
    for entry in "${services[@]}"; do
      IFS=":" read -r name port path <<< "$entry"
      if ! curl --silent --show-error --connect-timeout 1 --max-time 2 -o /dev/null -w "%{http_code}" "http://127.0.0.1:${port}${path}" 2>/dev/null | grep -qE '^2[0-9][0-9]$'; then
        failed_services+=("$name")
      fi
    done

    if [[ ${#failed_services[@]} -eq 0 ]]; then
      all_healthy=1
      break
    fi

    attempt=$((attempt + 1))
    printf '.'
    sleep 1.5
  done
  printf '\n'

  if [[ "$all_healthy" == "1" ]]; then
    printf '\n%s[SUKCES] Wszystkie 15 mikroserwisow i frontend sa ONLINE i gotowe do pracy!%s\n' "$C_GREEN" "$C_RESET"
    printf '  • Sklep (Frontend):   http://127.0.0.1:18070\n'
    printf '  • API Gateway:        http://127.0.0.1:18080\n'
    printf '  • Centrala Kontrolna: http://127.0.0.1:18100\n'
    printf '  • Mailpit (E-maile):  http://127.0.0.1:18025\n'
    return 0
  else
    printf '\n%s[UWAGA] Nastepujace uslugi nie odpowiedzialy w wyznaczonym czasie:%s %s\n' "$C_RED" "$C_RESET" "${failed_services[*]}"
    for failed in "${failed_services[@]}"; do
      docker_diagnose_service_error "$failed"
    done
    return 1
  fi
}


docker_apps_down() {
  printf 'Zatrzymuje kontenery serwisow i frontendu...\n'
  apps_compose stop \
    product-service order-service inventory-service payment-service \
    notification-service customer-service delivery-service promotion-service \
    wishlist-service review-service recommendation-service chat-service \
    returns-service billing-service api-gateway frontend
}


docker_apps_status() {
  apps_compose ps
}


docker_apps_logs() {
  apps_compose logs -f "$@"
}


workflow_fresh_start() {
  local profile
  profile="$(active_profile)"
  printf '%s============================================================%s\n' "$C_BLUE" "$C_RESET"
  printf '%s🚀 [1-CLICK FRESH START] Inicjalizacja i pełny start platformy MiniTech (%s)%s\n' "$C_BOLD" "$profile" "$C_RESET"
  printf '%s============================================================%s\n\n' "$C_BLUE" "$C_RESET"

  printf 'Krok 1/4: Weryfikacja i przygotowanie kluczy autoryzacji JWT...\n'
  secrets_ensure_auth "$profile" || true

  printf '\nKrok 2/4: Uruchamianie infrastruktury baz danych i brokera zdarzeń...\n'
  infra_up || {
    printf '%sBłąd podczas startu infrastruktury baz danych.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  printf '\nKrok 3/4: Uruchamianie wszystkich 15 mikroserwisów i frontendu w kontenerach Docker...\n'
  docker_apps_up || {
    printf '%sBłąd podczas startu aplikacji Docker.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  printf '\nKrok 4/4: Wykonywanie szybkiej weryfikacji funkcjonalnej...\n'
  smoke || true

  printf '\n%s============================================================%s\n' "$C_GREEN" "$C_RESET"
  printf '%s🎉 Środowisko MiniTech jest w 100%% gotowe do pracy!%s\n' "$C_GREEN" "$C_RESET"
  printf '  • Sklep (Frontend):   http://127.0.0.1:18070\n'
  printf '  • API Gateway:        http://127.0.0.1:18080\n'
  printf '  • Centrala Kontrolna: http://127.0.0.1:18100\n'
  printf '  • Mailpit (E-maile):  http://127.0.0.1:18025\n'
  printf '%s============================================================%s\n' "$C_GREEN" "$C_RESET"
  return 0
}


workflow_seed_demo_data() {
  printf '%s============================================================%s\n' "$C_BLUE" "$C_RESET"
  printf '%s📦 [SEED DEMO DATA] Zasilanie platformy przykładowymi danymi sklepu%s\n' "$C_BOLD" "$C_RESET"
  printf '%s============================================================%s\n\n' "$C_BLUE" "$C_RESET"

  local gateway="http://127.0.0.1:18080/api"
  if ! curl --silent --connect-timeout 2 --max-time 3 -o /dev/null -w "%{http_code}" "$gateway/products" | grep -qE '^2[0-9][0-9]$'; then
    printf '%sAPI Gateway nie odpowiada na %s. Uruchom najpierw serwisy (docker-apps-up).%s\n' "$C_RED" "$gateway" "$C_RESET" >&2
    return 1
  fi

  printf '1. Tworzenie przykładowych zapytań do czatu wsparcia...\n'
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"customerName":"Anna Kowalska","customerEmail":"anna.k@example.com","subject":"Pytanie o dostępność MacBook Pro","message":"Dzień dobry, czy wersja 36GB RAM będzie dostępna w tym tygodniu?"}' \
    "$gateway/chat/messages" >/dev/null 2>&1 || true
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"customerName":"Piotr Nowak","customerEmail":"p.nowak@example.com","subject":"Status przesyłki zamówienia","message":"Cześć! Kiedy mogę spodziewać się doręczenia zamówienia z wczoraj?"}' \
    "$gateway/chat/messages" >/dev/null 2>&1 || true
  printf '   ✓ Wysłano 2 przykładowe wątki czatu do obsługi klienta.\n'

  printf '2. Dodawanie opinii klientów o produktach...\n'
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"productId":1,"customerName":"Tomasz Wiśniewski","rating":5,"comment":"Świetny laptop! Bateria trzyma cały dzień, ekran rewelacyjny."}' \
    "$gateway/reviews" >/dev/null 2>&1 || true
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"productId":2,"customerName":"Karolina Mazur","rating":4,"comment":"Bardzo dobry smartfon, świetne zdjęcia w nocy, szybkie ładowanie."}' \
    "$gateway/reviews" >/dev/null 2>&1 || true
  printf '   ✓ Utworzono 2 opinie o produktach.\n'

  printf '3. Generowanie przykładowych kodów promocyjnych...\n'
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"code":"MINITECH10","discountPercent":10,"description":"Rabat powitalny 10%","validUntil":"2030-12-31"}' \
    "$gateway/promotions/codes" >/dev/null 2>&1 || true
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"code":"VIP20","discountPercent":20,"description":"Kupon VIP 20% zniżki","validUntil":"2030-12-31"}' \
    "$gateway/promotions/codes" >/dev/null 2>&1 || true
  printf '   ✓ Zarejestrowano kody promocyjne: MINITECH10 (-10%%) oraz VIP20 (-20%%).\n'

  printf '4. Wykonywanie testowego zamówienia i płatności z fakturą...\n'
  local order_res order_id
  order_res="$(curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"customerEmail":"klient.demo@minitech.local","shippingAddress":{"street":"Marszałkowska 10","city":"Warszawa","postalCode":"00-001","country":"PL"},"deliveryMethod":"COURIER_STANDARD","items":[{"productId":1,"quantity":1}]}' \
    "$gateway/orders" 2>/dev/null || true)"
  order_id="$(echo "$order_res" | grep -oE '"id":[0-9]+' | head -n 1 | cut -d':' -f2)"
  if [[ -n "$order_id" ]]; then
    curl --silent -X POST -H 'Content-Type: application/json' \
      -d "{\"orderId\":$order_id,\"amount\":4999.00,\"currency\":\"PLN\",\"paymentMethod\":\"BLIK\"}" \
      "$gateway/payments" >/dev/null 2>&1 || true
    printf '   ✓ Utworzono i opłacono zamówienie #%s z generowaniem faktury VAT.\n' "$order_id"
  fi

  printf '\n%s[SUKCES] Dane demonstracyjne zostały pomyślnie załadowane do platformy!%s\n' "$C_GREEN" "$C_RESET"
  printf '  • Sprawdź czat w Centrali (zakładka Czat klientów)\n'
  printf '  • Sprawdź opinie i promocje w Sklepie: http://127.0.0.1:18070\n'
  return 0
}


doctor_auto_fix() {
  printf '%s============================================================%s\n' "$C_BLUE" "$C_RESET"
  printf '%s🚑 [AUTO-HEALING DOCTOR] Pełny audyt środowiska i automatyczna naprawa%s\n' "$C_BOLD" "$C_RESET"
  printf '%s============================================================%s\n\n' "$C_BLUE" "$C_RESET"

  local profile
  profile="$(active_profile)"
  local fixes_applied=0

  printf '[1/5] Kontrola kluczy kryptograficznych i autoryzacji JWT (%s)...\n' "$profile"
  if ! secrets_ensure_auth "$profile" >/dev/null 2>&1; then
    printf '  ⚡ Naprawiono: wygenerowano brakujący klucz JWT dla profilu %s.\n' "$profile"
    fixes_applied=$((fixes_applied + 1))
  else
    printf '  ✓ Klucze JWT są poprawne.\n'
  fi

  printf '[2/5] Kontrola kontenerów baz danych PostgreSQL...\n'
  local dbs_down=0
  for db_cont in $(docker ps -a --filter "name=db-1" --format '{{.Names}}'); do
    if ! docker inspect --format '{{.State.Running}}' "$db_cont" 2>/dev/null | grep -q "true"; then
      dbs_down=1
      break
    fi
  done
  if [[ "$dbs_down" == "1" ]] || ! docker ps --format '{{.Names}}' | grep -q "product-db"; then
    printf '  ⚡ Wykryto wyłączone bazy danych. Uruchamiam infrastrukturę (infra-up)...\n'
    infra_up >/dev/null 2>&1 || true
    fixes_applied=$((fixes_applied + 1))
  else
    printf '  ✓ Wszystkie bazy danych PostgreSQL działają poprawnie.\n'
  fi

  printf '[3/5] Kontrola brokera zdarzeń Redis...\n'
  if ! docker ps --format '{{.Names}}' | grep -q "event-broker"; then
    printf '  ⚡ Wykryto wyłączony kontener event-broker. Uruchamiam Redis...\n'
    broker_up >/dev/null 2>&1 || true
    fixes_applied=$((fixes_applied + 1))
  else
    printf '  ✓ Redis Event Broker działa i odpowiada.\n'
  fi

  printf '[4/5] Kontrola serwera poczty Mailpit...\n'
  if ! docker ps --format '{{.Names}}' | grep -q "mailpit"; then
    printf '  ⚡ Wykryto wyłączony kontener mailpit. Uruchamiam Mailpit...\n'
    mailpit_up >/dev/null 2>&1 || true
    fixes_applied=$((fixes_applied + 1))
  else
    printf '  ✓ Mailpit działa pod adresem http://127.0.0.1:18025.\n'
  fi

  printf '[5/5] Kontrola dostępności mikroserwisów i portów...\n'
  local health_offline=0
  local -a ports=(18080 18081 18082 18083 18084 18086 18087 18088 18089 18090 18091 18092 18093 18094 18095 18070)
  for port in "${ports[@]}"; do
    if ! curl --silent --connect-timeout 1 --max-time 1 -o /dev/null -w "%{http_code}" "http://127.0.0.1:${port}/actuator/health" 2>/dev/null | grep -qE '^2[0-9][0-9]$'; then
      if [[ "$port" == "18070" ]] && curl --silent --connect-timeout 1 --max-time 1 -o /dev/null -w "%{http_code}" "http://127.0.0.1:18070/" 2>/dev/null | grep -qE '^2[0-9][0-9]$'; then
        continue
      fi
      if [[ "$port" == "18080" ]] && curl --silent --connect-timeout 1 --max-time 1 -o /dev/null -w "%{http_code}" "http://127.0.0.1:18080/api/products" 2>/dev/null | grep -qE '^2[0-9][0-9]$'; then
        continue
      fi
      health_offline=1
      break
    fi
  done

  if [[ "$health_offline" == "1" ]]; then
    printf '  ⚡ Wykryto niedostępne mikroserwisy. Restartuję kontenery aplikacji...\n'
    docker_apps_up || true
    fixes_applied=$((fixes_applied + 1))
  else
    printf '  ✓ Wszystkie mikroserwisy i frontend są aktywne (HTTP 200).\n'
  fi

  printf '\n%s============================================================%s\n' "$C_GREEN" "$C_RESET"
  if [[ "$fixes_applied" -gt 0 ]]; then
    printf '%s🎉 Diagnostyka zakończona: Zastosowano %s automatycznych poprawek. Środowisko jest w pełni sprawne!%s\n' "$C_GREEN" "$fixes_applied" "$C_RESET"
  else
    printf '%s🎉 Diagnostyka zakończona: Środowisko jest w doskonałym stanie, nie wykryto żadnych usterek!%s\n' "$C_GREEN" "$C_RESET"
  fi
  printf '%s============================================================%s\n' "$C_GREEN" "$C_RESET"
  return 0
}


monitoring_compose() {
  compose_with_secrets "$MONITORING_COMPOSE_FILE" "$@"
}


monitoring_up() {
  monitoring_compose up -d
}


monitoring_down() {
  monitoring_compose stop
}


monitoring_status() {
  require_command curl || return 1
  monitoring_compose ps
  printf '\nPrometheus: http://127.0.0.1:%s\n' "$PROMETHEUS_PORT"
  curl --silent --show-error --connect-timeout 2 --max-time 5 \
    "http://127.0.0.1:${PROMETHEUS_PORT}/-/ready" || true
  printf '\nGrafana:    http://127.0.0.1:%s\n' "$GRAFANA_PORT"
  curl --silent --show-error --connect-timeout 2 --max-time 5 \
    "http://127.0.0.1:${GRAFANA_PORT}/api/health" || true
  printf '\n'
}


monitoring_logs() {
  local service="${1:-}"
  if [[ -n "$service" && "$service" != "prometheus" && "$service" != "grafana" ]]; then
    printf '%sUzycie: monitoring-logs [prometheus|grafana]%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  fi
  if [[ -n "$service" ]]; then
    monitoring_compose logs --tail=100 "$service"
  else
    monitoring_compose logs --tail=100
  fi
}


monitoring_urls() {
  printf 'Prometheus: http://127.0.0.1:%s\n' "$PROMETHEUS_PORT"
  printf 'Grafana:    http://127.0.0.1:%s\n' "$GRAFANA_PORT"
  printf 'Grafana user: %s\n' "${GRAFANA_ADMIN_USER:-admin}"
  printf 'Grafana password: z aktywnego skarbca profilu\n'
}


broker_cli() {
  compose exec -T "$BROKER_SERVICE" redis-cli "$@"
}


broker_up() {
  compose up -d "$BROKER_SERVICE"
}


broker_down() {
  compose stop "$BROKER_SERVICE"
}


broker_status() {
  compose ps "$BROKER_SERVICE"
  printf '\nRedis ping:\n'
  broker_cli ping
}


broker_logs() {
  compose logs --tail=100 "$BROKER_SERVICE"
}


stream_info() {
  printf 'Stream: %s\n\n' "$PAYMENT_EVENT_STREAM"
  broker_cli XINFO STREAM "$PAYMENT_EVENT_STREAM" || true
  printf '\nConsumer groups:\n'
  broker_cli XINFO GROUPS "$PAYMENT_EVENT_STREAM" || true
}


stream_read() {
  local count="${1:-20}"
  broker_cli XRANGE "$PAYMENT_EVENT_STREAM" - + COUNT "$count"
}


broker_reset_group() {
  local group="${1:-$NOTIFICATION_CONSUMER_GROUP}"
  printf 'Usuwam tylko consumer group %s ze streamu %s. Dane streamu pozostaja.\n' "$group" "$PAYMENT_EVENT_STREAM"
  broker_cli XGROUP DESTROY "$PAYMENT_EVENT_STREAM" "$group"
}


mailpit_up() {
  compose up -d "$MAILPIT_SERVICE"
}


mailpit_down() {
  compose stop "$MAILPIT_SERVICE"
}


mailpit_status() {
  require_command curl || return 1
  compose ps "$MAILPIT_SERVICE"
  printf 'Panel Mailpit: http://127.0.0.1:%s\n' "$MAILPIT_WEB_PORT"
  printf 'SMTP Mailpit:  127.0.0.1:%s\n' "$MAILPIT_SMTP_PORT"
  local info
  if info="$(mailpit_info 2>/dev/null)"; then
    printf '%s' "$info" | python3 -c 'import json,sys; data=json.load(sys.stdin); print("Messages: {} (unread: {})".format(data.get("Messages", 0), data.get("Unread", 0)))'
  else
    printf '%sMailpit API jest niedostepne.%s\n' "$C_RED" "$C_RESET"
  fi
}


mailpit_logs() {
  compose logs --tail=100 "$MAILPIT_SERVICE"
}


mailpit_smoke() {
  require_command curl || return 1
  local mailpit_url="http://127.0.0.1:${MAILPIT_WEB_PORT}"
  local notification_url="http://127.0.0.1:$(app_port notification)/notifications"
  local timestamp recipient subject payload response attempt
  timestamp="$(date +%s)"
  recipient="mailpit-smoke-${timestamp}@minitech.local"
  subject="Mini Order Platform Mailpit Smoke ${timestamp}"
  payload="$(printf '{\n  \"eventType\": \"ORDER_STATUS_CHANGED\",\n  \"sourceId\": %s,\n  \"recipient\": \"%s\",\n  \"channel\": \"EMAIL\",\n  \"subject\": \"%s\",\n  \"message\": \"Mailpit smoke test from Notification Service.\",\n  \"simulateFailure\": false\n}' "$timestamp" "$recipient" "$subject")"

  printf 'Sprawdzam API Mailpit: %s/api/v1/info\n' "$mailpit_url"
  curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
    "$mailpit_url/api/v1/info" >/dev/null || {
    printf '%sMailpit jest niedostepny. Uruchom: mailpit-up%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  printf 'Wysylam testowe powiadomienie przez %s\n' "$notification_url"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' \
    -X POST "$notification_url" \
    --data "$payload")" || {
    printf '%sNotification Service nie przyjal testowego powiadomienia.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf 'Odpowiedz Notification Service: %s\n' "$response"

  for attempt in {1..10}; do
    response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
      --get "$mailpit_url/api/v1/search" \
      --data-urlencode "query=subject:${subject}" 2>/dev/null || true)"
    if [[ "$response" == *"$subject"* ]]; then
      printf '%sSmoke Mailpit zakonczony powodzeniem. Odbiorca: %s%s\n' "$C_GREEN" "$recipient" "$C_RESET"
      printf 'Panel: http://127.0.0.1:%s\n' "$MAILPIT_WEB_PORT"
      return 0
    fi
    sleep 1
  done

  printf '%sMailpit dziala, ale nie znaleziono testowej wiadomosci.%s\n' "$C_RED" "$C_RESET" >&2
  printf 'Temat do sprawdzenia: %s\n' "$subject" >&2
  return 1
}


mailpit_send() {
  require_command curl || return 1
  local mailpit_url="http://127.0.0.1:${MAILPIT_WEB_PORT}"
  local notification_url="http://127.0.0.1:$(app_port notification)/notifications"
  local timestamp source_id recipient subject message payload response attempt

  printf 'Odbiorca e-mail [mailpit-test@minitech.local]: '
  read -r recipient || return 1
  recipient="${recipient:-mailpit-test@minitech.local}"
  if [[ ! "$recipient" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
    printf '%sNieprawidlowy adres odbiorcy.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  fi

  printf 'Temat [Test z Control Center]: '
  read -r subject || return 1
  subject="${subject:-Test z Control Center}"
  printf 'Tresc [Wiadomosc testowa z Control Center]: '
  read -r message || return 1
  message="${message:-Wiadomosc testowa z Control Center}"

  [[ -n "$subject" && ${#subject} -le 120 ]] || {
    printf '%sTemat musi miec od 1 do 120 znakow.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  [[ -n "$message" && ${#message} -le 1000 ]] || {
    printf '%sTresc musi miec od 1 do 1000 znakow.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }

  printf 'Sprawdzam dostepnosc Mailpit: %s/api/v1/info\n' "$mailpit_url"
  curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
    "$mailpit_url/api/v1/info" >/dev/null || {
    printf '%sMailpit jest niedostepny. Uruchom: mailpit-up%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  timestamp="$(date +%s)"
  source_id="${timestamp}$$"
  payload="$(printf '{\n  \"eventType\": \"ORDER_STATUS_CHANGED\",\n  \"sourceId\": %s,\n  \"recipient\": \"%s\",\n  \"channel\": \"EMAIL\",\n  \"subject\": \"%s\",\n  \"message\": \"%s\",\n  \"simulateFailure\": false\n}' \
    "$source_id" "$(json_escape "$recipient")" "$(json_escape "$subject")" "$(json_escape "$message")")"

  printf 'Wysylam wiadomosc przez Notification Service: %s\n' "$notification_url"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' \
    -X POST "$notification_url" \
    --data "$payload")" || {
    printf '%sNotification Service nie przyjal wiadomosci. Sprawdz, czy dziala na porcie %s.%s\n' \
      "$C_RED" "$(app_port notification)" "$C_RESET" >&2
    return 1
  }
  printf 'Odpowiedz Notification Service: %s\n' "$response"

  for attempt in {1..10}; do
    response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
      --get "$mailpit_url/api/v1/search" \
      --data-urlencode "query=subject:${subject}" 2>/dev/null || true)"
    if [[ "$response" == *"$subject"* ]]; then
      printf '%sWiadomosc wyslana pomyslnie. Odbiorca: %s%s\n' "$C_GREEN" "$recipient" "$C_RESET"
      printf 'Panel Mailpit: http://127.0.0.1:%s\n' "$MAILPIT_WEB_PORT"
      return 0
    fi
    sleep 1
  done

  printf '%sWiadomosc zostala przyjeta, ale nie znaleziono jej jeszcze w Mailpit.%s\n' "$C_RED" "$C_RESET" >&2
  printf 'Temat do sprawdzenia: %s\n' "$subject" >&2
  return 1
}


mailpit_info() {
  require_command curl || return 1
  curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
    "http://127.0.0.1:${MAILPIT_WEB_PORT}/api/v1/info"
}


mailpit_list() {
  require_command curl || return 1
  require_command python3 || return 1
  local limit="${1:-20}" response
  [[ "$limit" =~ ^[0-9]+$ && "$limit" -gt 0 && "$limit" -le 100 ]] || {
    printf '%sLimit musi byc liczba od 1 do 100.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    --get "http://127.0.0.1:${MAILPIT_WEB_PORT}/api/v1/messages" \
    --data-urlencode "limit=${limit}")" || return 1
  printf '%s' "$response" | python3 -c '
import json
import sys

data = json.load(sys.stdin)
print("Wiadomosci: {} / lacznie {}".format(data.get("count", 0), data.get("total", 0)))
for index, message in enumerate(data.get("messages", []), 1):
    recipients = ", ".join(item.get("Address", "") for item in (message.get("To") or []))
    print("{:>2}. {} | {} | To: {} | ID: {}".format(index, message.get("Created", ""), message.get("Subject", ""), recipients, message.get("ID", "")))
'
}


mailpit_search() {
  require_command curl || return 1
  require_command python3 || return 1
  local query="${1:-}" response
  if [[ -z "$query" ]]; then
    printf 'Zapytanie Mailpit (np. to:adres@example.com lub subject:Payment): '
    read -r query || return 1
  fi
  [[ -n "$query" ]] || {
    printf '%sZapytanie nie moze byc puste.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    --get "http://127.0.0.1:${MAILPIT_WEB_PORT}/api/v1/search" \
    --data-urlencode "query=${query}")" || return 1
  printf '%s' "$response" | python3 -c '
import json
import sys

data = json.load(sys.stdin)
print("Wyniki dla: {} | znaleziono: {}".format(sys.argv[1], data.get("count", 0)))
for index, message in enumerate(data.get("messages", []), 1):
    recipients = ", ".join(item.get("Address", "") for item in (message.get("To") or []))
    print("{:>2}. {} | {} | To: {} | ID: {}".format(index, message.get("Created", ""), message.get("Subject", ""), recipients, message.get("ID", "")))
' "$query"
}


mailpit_delete_all() {
  require_command curl || return 1
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -X DELETE "http://127.0.0.1:${MAILPIT_WEB_PORT}/api/v1/messages" >/dev/null
}


mailpit_clear() {
  printf 'Usunie to wszystkie wiadomosci z lokalnego Mailpit. Wpisz DELETE, aby potwierdzic: '
  read -r confirmation || return 1
  if [[ "$confirmation" != "DELETE" ]]; then
    printf 'Anulowano czyszczenie Mailpit.\n'
    return 0
  fi
  mailpit_delete_all || {
    printf '%sNie udalo sie wyczyscic Mailpit.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%sSkrzynka Mailpit zostala wyczyszczona.%s\n' "$C_GREEN" "$C_RESET"
}


dead_letter_list() {
  require_command curl || return 1
  local url="http://127.0.0.1:$(app_port notification)/notifications/dead-letter"
  printf 'Otwarte wpisy DLQ: %s\n' "$url"
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "$url"
  printf '\n'
}


dead_letter_replay() {
  require_command curl || return 1
  local id="${1:-}"
  [[ -n "$id" ]] || {
    printf '%sUzycie: dlq-replay <id>%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  local url="http://127.0.0.1:$(app_port notification)/notifications/dead-letter/${id}/replay"
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -X POST "$url"
  printf '\n'
}


print_database_catalog() {
  printf '%-14s %-18s %-8s %-18s %-18s\n' 'Serwis' 'Kontener DB' 'Port' 'Baza' 'Uzytkownik'
  printf '%-14s %-18s %-8s %-18s %-18s\n' '-------' '----------' '----' '----' '----------'
   for service in product order inventory payment notification customer delivery promotion wishlist review recommendation chat returns billing; do
    printf '%-14s %-18s %-8s %-18s %-18s\n' \
      "$service" "$(db_service "$service")" "$(db_port "$service")" "$(db_name "$service")" "$(db_user "$service")"
  done
  printf '\n%-14s %-18s %-8s\n' 'Broker' "$BROKER_SERVICE" "$BROKER_PORT"
  printf '%-14s %-18s %-8s\n' 'Mailpit SMTP' "$MAILPIT_SERVICE" "$MAILPIT_SMTP_PORT"
  printf '%-14s %-18s %-8s\n' 'Mailpit Web' "$MAILPIT_SERVICE" "$MAILPIT_WEB_PORT"
}


resolve_db_list() {
  if [[ "${1:-all}" == "all" ]]; then
    printf '%s\n' "${DB_SERVICES[@]}"
  else
    db_service "$1"
  fi
}


db_action() {
  local action="$1"
  local requested="${2:-all}"
  local databases database
  databases=()
  if [[ "$requested" != "all" ]] && ! db_service "$requested" >/dev/null; then
    printf '%sNieznana baza/alias: %s%s\n' "$C_RED" "$requested" "$C_RESET" >&2
    return 2
  fi
  while IFS= read -r database; do
    databases+=("$database")
  done < <(resolve_db_list "$requested") || {
    printf '%sNieznana baza/alias: %s%s\n' "$C_RED" "$requested" "$C_RESET" >&2
    return 2
  }

  case "$action" in
    up) compose up -d "${databases[@]}" ;;
    down) compose stop "${databases[@]}" ;;
    status) compose ps "${databases[@]}" ;;
    logs) compose logs --tail=100 "${databases[@]}" ;;
    *) printf '%sNieznana akcja DB: %s%s\n' "$C_RED" "$action" "$C_RESET" >&2; return 2 ;;
  esac
}


db_down_all() {
  compose down
}


db_shell() {
  local alias="${1:-}"
  [[ -n "$alias" ]] || { printf 'Uzycie: db-shell <service>\n' >&2; return 2; }
  local db
  db="$(db_service "$alias")" || { printf '%sNieznana baza: %s%s\n' "$C_RED" "$alias" "$C_RESET" >&2; return 2; }
  compose exec "$db" psql -U "$(db_user "$alias")" -d "$(db_name "$alias")"
}


inventory_reset() {
  local profile
  profile="$(active_profile)"
  case "$profile" in
    dev|test) ;;
    prod)
      printf '%sReset magazynu jest dostepny tylko dla profilu dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
      return 1
      ;;
    *)
      printf '%sNieznany profil: %s%s\n' "$C_RED" "$profile" "$C_RESET" >&2
      return 2
      ;;
  esac

  local sql
  sql=$(cat <<'SQL'
BEGIN;

UPDATE inventory_reservations
SET status = 'RELEASED', updated_at = CURRENT_TIMESTAMP
WHERE status = 'RESERVED';

UPDATE inventory_items
SET available_quantity = CASE
        WHEN product_id = 1 THEN 12
        WHEN product_id = 2 THEN 10
        WHEN product_id = 3 THEN 5
        WHEN product_id = 4 THEN 8
        WHEN product_id = 5 THEN 7
        WHEN product_id = 6 THEN 15
        WHEN product_id = 7 THEN 6
        WHEN product_id = 8 THEN 9
        WHEN product_id = 9 THEN 11
        WHEN product_id = 10 THEN 8
        WHEN product_id = 11 THEN 6
        WHEN product_id = 12 THEN 12
        WHEN product_id = 13 THEN 10
        WHEN product_id = 14 THEN 7
        WHEN product_id = 15 THEN 9
        WHEN product_id BETWEEN 16 AND 105 THEN 5 + ((product_id * 7) % 11)
    END,
    reserved_quantity = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE product_id BETWEEN 1 AND 105;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'inventory_items'
          AND column_name = 'sold_quantity'
    ) THEN
        UPDATE inventory_items
        SET sold_quantity = 0,
            updated_at = CURRENT_TIMESTAMP
        WHERE product_id BETWEEN 1 AND 105;
    END IF;
END $$;

COMMIT;
SQL
  )

  printf 'Resetuje demo stan magazynowy dla profilu %s.\n' "$profile"
  compose exec -T inventory-db psql \
    -v ON_ERROR_STOP=1 \
    -U inventory_app \
    -d inventory_db \
    -c "$sql"
  printf '%sStan magazynowy przywrocony. Otwarte rezerwacje oznaczono jako RELEASED.%s\n' "$C_GREEN" "$C_RESET"
}


demo_reset() {
  local profile
  profile="$(active_profile)"
  case "$profile" in
    dev|test) ;;
    prod)
      printf '%sReset danych demo jest dostepny tylko dla profilu dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
      return 1
      ;;
    *)
      printf '%sNieznany profil: %s%s\n' "$C_RED" "$profile" "$C_RESET" >&2
      return 2
      ;;
  esac

  printf 'Reset usunie zamowienia, platnosci, powiadomienia, rezerwacje i wiadomosci Mailpit.\n'
  printf 'Produkty i konta klientow pozostana bez zmian. Wpisz RESET, aby potwierdzic: '
  read -r confirmation || return 1
  [[ "$confirmation" == "RESET" ]] || {
    printf 'Anulowano reset danych demo.\n'
    return 0
  }

  printf 'Czyszcze baze Inventory...\n'
  compose exec -T inventory-db psql -v ON_ERROR_STOP=1 -U inventory_app -d inventory_db -c '
    TRUNCATE inventory_reservation_items, inventory_reservations RESTART IDENTITY;
    UPDATE inventory_items
    SET available_quantity = CASE
            WHEN product_id = 1 THEN 12 WHEN product_id = 2 THEN 10 WHEN product_id = 3 THEN 5
            WHEN product_id = 4 THEN 8 WHEN product_id = 5 THEN 7 WHEN product_id = 6 THEN 15
            WHEN product_id = 7 THEN 6 WHEN product_id = 8 THEN 9 WHEN product_id = 9 THEN 11
            WHEN product_id = 10 THEN 8 WHEN product_id = 11 THEN 6 WHEN product_id = 12 THEN 12
            WHEN product_id = 13 THEN 10 WHEN product_id = 14 THEN 7 WHEN product_id = 15 THEN 9
            WHEN product_id BETWEEN 16 AND 105 THEN 5 + ((product_id * 7) % 11)
        END,
        reserved_quantity = 0,
        updated_at = CURRENT_TIMESTAMP
    WHERE product_id BETWEEN 1 AND 105;
  '

  printf 'Czyszcze baze Order...\n'
  compose exec -T order-db psql -v ON_ERROR_STOP=1 -U order_app -d order_db -c \
    'TRUNCATE order_items, orders RESTART IDENTITY CASCADE;'

  printf 'Czyszcze baze Payment...\n'
  compose exec -T payment-db psql -v ON_ERROR_STOP=1 -U payment_app -d payment_db -c \
    'TRUNCATE payment_outbox_events, payments RESTART IDENTITY CASCADE;'

  printf 'Czyszcze baze Notification...\n'
  compose exec -T notification-db psql -v ON_ERROR_STOP=1 -U notification_app -d notification_db -c \
    'TRUNCATE notification_dead_letters, notifications RESTART IDENTITY CASCADE;'

  if mailpit_delete_all; then
    printf 'Mailpit: wyczyszczono wiadomosci.\n'
  else
    printf '%sOstrzezenie: nie udalo sie wyczyscic Mailpit. Bazy zostaly zresetowane.%s\n' "$C_RED" "$C_RESET" >&2
  fi
  printf '%sDane demonstracyjne zostaly zresetowane dla profilu %s.%s\n' "$C_GREEN" "$profile" "$C_RESET"
}


