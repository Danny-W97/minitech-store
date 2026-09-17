#!/usr/bin/env bash
# Interactive console menus (PL/EN) and CLI catalog rendering

print_catalog() {
  cat <<'EOF'
Mini Order Platform - Control Center

Infrastruktura PostgreSQL:
  infra-up                         Uruchom wszystkie bazy PostgreSQL
  infra-down                       Zatrzymaj kontenery baz bez usuwania danych
  infra-status                     Pokaz status kontenerow baz
  infra-logs [db]                  Pokaz ostatnie logi wszystkich baz lub jednej

Bazy per serwis:
  db-list                          Pokaz baze, uzytkownika i port kazdego serwisu
  db-up <service|all>              Uruchom baze wskazanego serwisu
  db-down <service|all>            Zatrzymaj baze wskazanego serwisu
  db-status <service|all>          Pokaz status bazy
  db-logs <service>                Pokaz logi bazy
  db-shell <service>               Otworz psql w kontenerze bazy
  db-down-all                      Zatrzymaj wszystkie bazy
  db-backup [service|all]           Wykonaj szyfrowany backup PostgreSQL
  db-backup-list [service|all]      Pokaz backupy aktywnego profilu
  db-backup-prune [days]            Usun backupy starsze niz podana retencja
  db-restore-test [service|plik]    Odtworz najnowszy lub wskazany backup testowo
  backup-key-init [dev|test|all]    Utworz osobny klucz age do szyfrowania backupow
  backup-schedule-install [hour]    Ustaw codzienny backup przez adapter platformy
  backup-schedule-remove            Usun harmonogram backupu z crontab
  backup-schedule-status            Pokaz status harmonogramu backupu
  inventory-reset                   Przywroc bazowe ilosci demo magazynu (dev/test)
  demo-reset                        Wyczysc dane zamowien i przywroc demo (dev/test)

Projekty / aplikacje:
  app-run <service> [dev|test]      Uruchom jeden mikroserwis przez Maven
  app-stop <service>                Zatrzymaj jeden wskazany mikroserwis
  app-run-all                      Uruchom wszystkie mikroserwisy w tle platformy
  app-stop-all                     Zatrzymaj mikroserwisy uruchomione przez centrale
  app-status-all                   Pokaz status procesow mikroserwisow
  app-logs <service>               Pokaz ostatnie logi mikroserwisu z centrali
  app-test [service|all]           Testy jednego serwisu lub calego monorepo
  app-package [service|all]        Pakietowanie jednego serwisu lub calego monorepo
  health [service|all]             Sprawdz dostepnosc endpointu kazdego serwisu
  smoke                            Sprawdz Product Service przez API Gateway
  api-docs-urls                    Pokaz adresy OpenAPI i Swagger UI
  contract-test                    Zweryfikuj kontrakt publicznego API v1
  security-test                    Wykonaj podstawowe testy bezpieczenstwa API
  zap-dast                         Uruchom pelny OWASP ZAP DAST dla API Gateway
  load-test                        Wykonaj lokalny test obciazeniowy API
  e2e                              Uruchom pelny scenariusz E2E z rejestracja i zakupem
  ui-test-status                   Sprawdz wymagania testow Playwright
  ui-test-install                  Zainstaluj zaleznosci i Chromium Playwright
  ui-test-smoke                    Szybki test przegladarkowy najwazniejszych ekranow
  ui-test                          Pelna regresja UI w Chromium (z mock API)
  ui-test-system                   Pelny checkout przez prawdziwe serwisy dev/test
  ui-test-browsers                 Regresja UI w Chromium, Firefox, WebKit i mobile
  ui-test-headed                   Uruchom regresje UI z widoczna przegladarka
  ui-test-debug                    Uruchom Playwright Inspector
  ui-test-report                   Pokaz raport HTML Playwright
  images-build                     Zbuduj lokalne obrazy Docker wszystkich aplikacji
  docker-apps-up                   Uruchom wszystkie serwisy i frontend w kontenerach Docker
  docker-apps-down                 Zatrzymaj kontenery serwisow i frontendu
  docker-apps-status               Pokaz status kontenerow aplikacji i frontendu
  docker-apps-logs [serwis]        Pokaz logi kontenerow aplikacji
  k8s-validate                     Zweryfikuj manifesty Kubernetes bez wdrazania
  k8s-status                       Pokaz status wdrozenia Kubernetes
  platform-info                    Pokaz wykryty system i adapter platformy
  resilience-status                Pokaz metryki timeoutow, Retry, Circuit Breaker i Bulkhead
  openbao-status                   Sprawdz status endpointu OpenBao bez ujawniania sekretow
  openbao-validate                 Zweryfikuj pliki przygotowania OpenBao/TLS prod
  tls-status                       Sprawdz certyfikat TLS wskazanego hosta
  jwt-status                       Pokaz status konfiguracji JWT bez sekretu
  jwt-rotate <dev|test>            Wygeneruj nowy klucz RSA JWT, zachowaj stare klucze publiczne
  jwt-smoke [email]                Przetestuj login, /me, refresh i logout JWT
  audit-list [limit]               Pokaz trwały dziennik audytowy Customer Service
  password-reset-request [email]   Wyślij żądanie resetu hasła przez API do Mailpit
  app-verify                       Automatyczny test katalogu, zakupu, platnosci i e-maila
  verify-mvp                       Sprawdz pelny scenariusz zamowienie -> platnosc -> e-mail
  delivery-smoke                  Sprawdz metody dostawy i wycene koszyka
  delivery-tracking [orderId]     Pokaz numer przesylki i status przewoznika
  delivery-tracking-status [orderId] [status]  Zmien symulowany status przesylki
  promotion-smoke                 Sprawdz aktywna kampanie i wycene promocji
  promotion-codes [kod]           Pokaz lub zweryfikuj kody rabatowe
  promotion-code-create           Utworz kod rabatowy dla profilu dev/test
  returns-list                    Pokaz zwroty i reklamacje
  returns-status <id> <status>    Zmien status zwrotu lub reklamacji
  orders-list [limit]             Pokaz wszystkie zamowienia z kwota, e-mailem, pozycjami i faktura
  invoices-list [limit]           Pokaz liste wszystkich wystawionych faktur VAT
  invoices-export-zip [plik]      Pobierz archiwum ZIP ze wszystkimi fakturami PDF
  invoice-correct <id>            Wystaw fakture korygujaca (KOR) do wskazanej faktury
  corrections-list [id]           Pokaz liste wystawionych faktur korygujacych
  invoice-show <orderId>          Pokaz fakture dla zamowienia
  invoice-pdf <orderId>            Pobierz PDF faktury do gitIgnoreOwn/runtime/invoices
  review-pending                  Pokaz opinie oczekujace na moderacje
  review-moderate <id> <status>   Opublikuj albo odrzuc opinie (PUBLISHED|REJECTED)
  chat-list [limit]               Pokaz wiadomosci z formularza czatu
  chat-reply <id> [message]       Odpowiedz klientowi przez e-mail
  doctor                            Pelna diagnostyka lokalnego srodowiska

Frontend sklepu:
  frontend-up                      Uruchom frontend pod 127.0.0.1:18070
  frontend-down                    Zatrzymaj frontend
  frontend-status                  Pokaz status frontendu i adres URL
  frontend-logs                    Pokaz logi frontendu

Centrala webowa:
  web-start [--port P] [--no-browser] Uruchom graficzny panel Centrali w przegladarce (alias: web, web-ui)


Diagnostyka:
  monitoring-up                   Uruchom Prometheus i Grafane
  monitoring-down                 Zatrzymaj Prometheus i Grafane
  monitoring-status               Pokaz status i endpointy monitoringu
  monitoring-logs [service]       Pokaz logi monitoringu
  monitoring-urls                 Pokaz adresy Prometheus/Grafana
  dlq-list                        Pokaz otwarte wpisy Notification DLQ
  dlq-replay <id>                 Ponow wysylke wpisu z Notification DLQ
  secrets-init                    Utworz klucze age dla profili dev/test
  secrets-setup <dev|test>        Utworz klucz i wprowadz sekrety kreatorem
  secrets-ensure-auth <dev|test>  Wygeneruj brakujacy sekret JWT bez wyswietlania
  secrets-validate <dev|test>     Sprawdz wymagane pola bez wyswietlania wartosci
  secrets-status                  Pokaz status skarbca bez wartosci sekretow
  secrets-edit <dev|test>         Edytuj zaszyfrowane sekrety profilu
  secrets-update-web <dev|test>   Zaktualizuj sekrety z formularza Centrali webowej
  secrets-setup-web <dev|test>    Utworz sekrety z kreatora Centrali webowej
  broker-up                        Uruchom Redis Event Broker
  broker-down                      Zatrzymaj Redis Event Broker
  broker-status                    Status kontenera i PING Redis
  broker-logs                      Pokaz logi Redis
  broker-cli <args...>             Wykonaj redis-cli w kontenerze
  stream-info                      Pokaz metryki streamu i consumer groups
  stream-read [count]              Pokaz ostatnie rekordy streamu
  broker-reset-group [group]       Usun consumer group bez kasowania streamu
  mailpit-up                       Uruchom Mailpit dla dev/test
  mailpit-down                     Zatrzymaj Mailpit
  mailpit-status                   Status Mailpit i adres panelu
  mailpit-logs                     Pokaz logi Mailpit
  mailpit-smoke                    Sprawdz wysylke Notification Service do Mailpit
  mailpit-send                     Napisz i wyslij wlasny e-mail do Mailpit
  mailpit-list [limit]             Pokaz ostatnie wiadomosci Mailpit
  mailpit-search <query>           Wyszukaj wiadomosci w Mailpit
  mailpit-clear                    Wyczysc skrzynke Mailpit po potwierdzeniu
  profile-show                     Pokaz aktywny profil
  profile-set <dev|test>           Ustaw lokalny profil uruchamiania aplikacji
  language-show                    Pokaz jezyk Control Center
  language-set <pl|en>             Ustaw jezyk Control Center
  prod-status                      Pokaz, dlaczego profil prod jest niedostepny
  environment-check                Sprawdz wymagania przed startem aplikacji
  start-check                      Alias kontroli wymagan przed startem
  environment-install              Zaproponuj instalacje brakow po potwierdzeniu ACCEPT
  github-check                     Sprawdz bezpieczenstwo plikow przed GitHub
  --list                           Pokaz ten katalog komend
  --check                          Alias environment-check
  --help                           Pokaz pomoc

   Alias serwisu: gateway, product, order, inventory, payment, notification, customer, delivery, promotion, wishlist, review, recommendation, chat, returns, billing.
EOF
}


pause_menu() {
  [[ -t 0 ]] || return 0
  printf '\n%s: ' "$(cc_text 'Enter lub 0 = powrot do menu, exit = zamkniecie panelu' 'Enter or 0 = return to menu, exit = close panel')"
  read -r answer || exit 0
  case "$(platform_lowercase "$answer")" in
    exit|quit) exit 0 ;;
    0|"") return 0 ;;
    *) printf '%s\n' "$(cc_text 'Powrot do menu.' 'Returning to menu.')" ;;
  esac
}


run_menu_action() {
  printf '\n%s%s: %s%s\n\n' "$C_DIM" "$(cc_text 'Polecenie' 'Command')" "$*" "$C_RESET"
  "$@"
  pause_menu
}


select_service() {
  printf '%s: ' "$(cc_text 'Wybierz alias (product/order/inventory/payment/notification/customer/delivery/promotion/wishlist/review/recommendation/chat/returns/billing)' 'Choose alias (product/order/inventory/payment/notification/customer/delivery/promotion/wishlist/review/recommendation/chat/returns/billing)')" >&2
  read -r selected_service || exit 0
  case "$(platform_lowercase "$selected_service")" in
    exit|quit) exit 0 ;;
    0) return 1 ;;
  esac
  return 0
}


language_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Jezyk Control Center' 'Control Center language')" "$C_RESET"
    printf '1. %s%s\n' "$(cc_text 'Jezyk polski' 'Polish')" "$(if [[ "$UI_LANGUAGE" == "pl" ]]; then printf ' (domyslny)' ; fi)"
    printf '2. %s%s\n' "$(cc_text 'Jezyk angielski' 'English')" "$(if [[ "$UI_LANGUAGE" == "en" ]]; then printf ' (current)' ; fi)"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action language_set pl ;;
      2) run_menu_action language_set en ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


settings_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Ustawienia' 'Settings')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Jezyk' 'Language')"
    printf '2. %s\n' "$(cc_text 'Pokaz aktualny jezyk' 'Show current language')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) language_menu ;;
      2) run_menu_action language_show ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


database_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Bazy danych' 'PostgreSQL databases')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Pokaz mape baz i portow' 'Show database and port map')"
    printf '2. %s\n' "$(cc_text 'Uruchom wszystkie bazy' 'Start all databases')"
    printf '3. %s\n' "$(cc_text 'Zatrzymaj wszystkie bazy' 'Stop all databases')"
    printf '4. %s\n' "$(cc_text 'Pokaz status baz' 'Show database status')"
    printf '5. %s\n' "$(cc_text 'Pokaz logi jednej bazy' 'Show logs for one database')"
    printf '6. %s\n' "$(cc_text 'Otworz psql dla jednej bazy' 'Open psql for one database')"
    printf '7. %s\n' "$(cc_text 'Resetuj demo stan magazynowy' 'Reset demo inventory')"
    printf '8. %s\n' "$(cc_text 'Resetuj dane demonstracyjne' 'Reset demo data')"
    printf '9. %s\n' "$(cc_text 'Wykonaj backup jednej bazy' 'Back up one database')"
    printf '10. %s\n' "$(cc_text 'Wykonaj backup wszystkich baz' 'Back up all databases')"
    printf '11. %s\n' "$(cc_text 'Pokaz backupy aktywnego profilu' 'Show backups for active profile')"
    printf '12. %s\n' "$(cc_text 'Test odtworzenia backupu' 'Test backup restore')"
    printf '13. %s\n' "$(cc_text 'Usun backupy starsze niz retencja' 'Delete backups older than retention')"
    printf '14. %s\n' "$(cc_text 'Przygotuj klucz szyfrowania backupow' 'Prepare backup encryption key')"
    printf '15. %s\n' "$(cc_text 'Ustaw automatyczny backup przez crontab WSL' 'Schedule automatic backup with WSL crontab')"
    printf '16. %s\n' "$(cc_text 'Usun harmonogram automatycznego backupu' 'Remove automatic backup schedule')"
    printf '17. %s\n' "$(cc_text 'Pokaz status harmonogramu backupu' 'Show backup schedule status')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action print_database_catalog ;;
      2) run_menu_action db_action up all ;;
      3) run_menu_action db_down_all ;;
      4) run_menu_action db_action status all ;;
      5) if select_service; then run_menu_action db_action logs "$selected_service"; fi ;;
      6) if select_service; then run_menu_action db_shell "$selected_service"; fi ;;
      7)
        printf 'Wpisz RESET, aby przywrocic ilosci magazynowe demo: '
        read -r confirmation || exit 0
        case "$(platform_lowercase "$confirmation")" in
          exit|quit) exit 0 ;;
          0) ;;
          reset) run_menu_action inventory_reset ;;
          *) printf 'Anulowano.\n' ;;
        esac
        ;;
      8)
        run_menu_action demo_reset
        ;;
      9)
        if select_service; then run_menu_action db_backup "$selected_service"; fi
        ;;
      10) run_menu_action db_backup all ;;
      11) run_menu_action db_backup_list all ;;
      12)
        if select_service; then run_menu_action db_restore_test "$selected_service"; fi
        ;;
      13) run_menu_action db_backup_prune ;;
      14) run_menu_action backup_key_init all ;;
      15) run_menu_action backup_schedule_install ;;
      16) run_menu_action backup_schedule_remove ;;
      17) run_menu_action backup_schedule_status ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


broker_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Redis Event Broker i streamy' 'Redis Event Broker and streams')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Uruchom broker' 'Start broker')"
    printf '2. %s\n' "$(cc_text 'Zatrzymaj broker' 'Stop broker')"
    printf '3. %s\n' "$(cc_text 'Status brokera i PING' 'Broker status and PING')"
    printf '4. %s\n' "$(cc_text 'Pokaz logi brokera' 'Show broker logs')"
    printf '5. %s\n' "$(cc_text 'Pokaz informacje o streamie i consumer group' 'Show stream and consumer group info')"
    printf '6. %s\n' "$(cc_text 'Pokaz rekordy streamu' 'Show stream records')"
    printf '7. %s\n' "$(cc_text 'Zresetuj consumer group' 'Reset consumer group')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action broker_up ;;
      2) run_menu_action broker_down ;;
      3) run_menu_action broker_status ;;
      4) run_menu_action broker_logs ;;
      5) run_menu_action stream_info ;;
      6) run_menu_action stream_read 20 ;;
      7)
        printf 'Wpisz RESET, aby usunac consumer group %s: ' "$NOTIFICATION_CONSUMER_GROUP"
        read -r confirmation || exit 0
        confirmation_lower="$(printf '%s' "$confirmation" | tr '[:upper:]' '[:lower:]')"
        if [[ "$confirmation_lower" == "exit" || "$confirmation_lower" == "quit" ]]; then
          exit 0
        elif [[ "$confirmation_lower" == "0" ]]; then
          return
        elif [[ "$confirmation" == "RESET" ]]; then
          run_menu_action broker_reset_group
        else
          printf 'Anulowano.\n'
        fi
        ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


profile_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Profile i Mailpit' 'Profiles and Mailpit')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Pokaz aktywny profil' 'Show active profile')"
    printf '2. %s\n' "$(cc_text 'Ustaw profil dev' 'Set dev profile')"
    printf '3. %s\n' "$(cc_text 'Ustaw profil test' 'Set test profile')"
    printf '4. %s\n' "$(cc_text 'Uruchom Mailpit' 'Start Mailpit')"
    printf '5. %s\n' "$(cc_text 'Zatrzymaj Mailpit' 'Stop Mailpit')"
    printf '6. %s\n' "$(cc_text 'Status Mailpit' 'Mailpit status')"
    printf '7. %s\n' "$(cc_text 'Pokaz logi Mailpit' 'Show Mailpit logs')"
    printf '8. %s\n' "$(cc_text 'Smoke Notification Service -> Mailpit' 'Notification Service -> Mailpit smoke test')"
    printf '9. %s\n' "$(cc_text 'Napisz i wyslij e-mail do Mailpit' 'Compose and send an e-mail to Mailpit')"
    printf '10. %s\n' "$(cc_text 'Pokaz ostatnie wiadomosci Mailpit' 'Show latest Mailpit messages')"
    printf '11. %s\n' "$(cc_text 'Wyszukaj wiadomosci Mailpit' 'Search Mailpit messages')"
    printf '12. %s\n' "$(cc_text 'Wyczysc skrzynke Mailpit' 'Clear Mailpit mailbox')"
    printf '13. %s\n' "$(cc_text 'Status skarbca sekretow dev/test' 'Show dev/test secrets vault status')"
    printf '14. %s\n' "$(cc_text 'Inicjalizuj klucze skarbca dev/test' 'Initialize dev/test vault keys')"
    printf '15. %s\n' "$(cc_text 'Kreator sekretow dev (hasla bez wyswietlania)' 'Dev secrets wizard (passwords hidden)')"
    printf '16. %s\n' "$(cc_text 'Kreator sekretow test (hasla bez wyswietlania)' 'Test secrets wizard (passwords hidden)')"
    printf '17. %s\n' "$(cc_text 'Edytuj sekrety dev' 'Edit dev secrets')"
    printf '18. %s\n' "$(cc_text 'Edytuj sekrety test' 'Edit test secrets')"
    printf '19. %s\n' "$(cc_text 'Uzupelnij brakujacy sekret JWT dev' 'Complete missing dev JWT secret')"
    printf '20. %s\n' "$(cc_text 'Uzupelnij brakujacy sekret JWT test' 'Complete missing test JWT secret')"
    printf '21. %s\n' "$(cc_text 'Rotuj klucze JWT RSA dla aktywnego profilu dev/test' 'Rotate RSA JWT keys for active dev/test profile')"
    printf '22. %s\n' "$(cc_text 'Pokaz dziennik audytowy Customer Service' 'Show Customer Service audit log')"
    printf '23. %s\n' "$(cc_text 'Wyslij zadanie resetu hasla przez Mailpit' 'Send password reset request through Mailpit')"
    printf '24. %s\n' "$(cc_text 'Pelny test OWASP ZAP/DAST API Gateway' 'Full OWASP ZAP/DAST API Gateway test')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action profile_show ;;
      2) run_menu_action profile_set dev ;;
      3) run_menu_action profile_set test ;;
      4) run_menu_action mailpit_up ;;
      5) run_menu_action mailpit_down ;;
      6) run_menu_action mailpit_status ;;
      7) run_menu_action mailpit_logs ;;
      8) run_menu_action mailpit_smoke ;;
      9) run_menu_action mailpit_send ;;
      10) run_menu_action mailpit_list 20 ;;
      11) run_menu_action mailpit_search ;;
      12) run_menu_action mailpit_clear ;;
      13) run_menu_action secrets_status ;;
      14) run_menu_action secrets_init ;;
      15) run_menu_action secrets_setup dev ;;
      16) run_menu_action secrets_setup test ;;
      17) run_menu_action secrets_edit dev ;;
      18) run_menu_action secrets_edit test ;;
      19) run_menu_action secrets_ensure_auth dev ;;
      20) run_menu_action secrets_ensure_auth test ;;
      21) run_menu_action jwt_rotate "$(active_profile)" ;;
      22) run_menu_action audit_list 50 ;;
      23) run_menu_action password_reset_request ;;
      24) run_menu_action qa_zap_dast ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


production_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Profil prod - przygotowanie (NIEDOSTEPNY)' 'Production profile - preparation (UNAVAILABLE)')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Status profilu prod' 'Production profile status')"
    printf '2. %s\n' "$(cc_text 'Waliduj manifesty Kubernetes' 'Validate Kubernetes manifests')"
    printf '3. %s\n' "$(cc_text 'Status wdrozenia Kubernetes' 'Kubernetes deployment status')"
    printf '4. %s\n' "$(cc_text 'Waliduj przygotowanie OpenBao/TLS' 'Validate OpenBao/TLS preparation')"
    printf '5. %s\n' "$(cc_text 'Status OpenBao' 'OpenBao status')"
    printf '6. %s\n' "$(cc_text 'Status TLS wskazanego hosta' 'TLS status for selected host')"
    printf '7. %s\n' "$(cc_text 'Pokaz lokalizacje szablonow prod' 'Show production template locations')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action prod_status ;;
      2) run_menu_action k8s_validate ;;
      3) run_menu_action k8s_status ;;
      4) run_menu_action openbao_validate ;;
      5) run_menu_action openbao_status ;;
      6) run_menu_action tls_status ;;
      7)
        printf 'Manifesty: %s\n' "$PROJECT_ROOT/deploy/k8s/overlays/prod"
        printf 'OpenBao:   %s\n' "$PROJECT_ROOT/infra/openbao"
        printf 'TLS:       %s\n' "$PROJECT_ROOT/deploy/k8s/overlays/prod/tls-secret.example.yaml"
        ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


monitoring_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Monitoring i Dead Letter Queue' 'Monitoring and Dead Letter Queue')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Uruchom Prometheus i Grafane' 'Start Prometheus and Grafana')"
    printf '2. %s\n' "$(cc_text 'Zatrzymaj Prometheus i Grafane' 'Stop Prometheus and Grafana')"
    printf '3. %s\n' "$(cc_text 'Status monitoringu' 'Monitoring status')"
    printf '4. %s\n' "$(cc_text 'Pokaz logi Prometheus/Grafana' 'Show Prometheus/Grafana logs')"
    printf '5. %s\n' "$(cc_text 'Pokaz adresy monitoringu' 'Show monitoring URLs')"
    printf '6. %s\n' "$(cc_text 'Pokaz otwarte wpisy DLQ' 'Show open DLQ entries')"
    printf '7. %s\n' "$(cc_text 'Ponow wysylke wpisu DLQ' 'Replay DLQ entry')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action monitoring_up ;;
      2) run_menu_action monitoring_down ;;
      3) run_menu_action monitoring_status ;;
      4) run_menu_action monitoring_logs ;;
      5) run_menu_action monitoring_urls ;;
      6) run_menu_action dead_letter_list ;;
      7)
        printf '%s: ' "$(cc_text 'Podaj ID wpisu DLQ' 'Enter DLQ entry ID')"
        read -r dead_letter_id || exit 0
        case "$(platform_lowercase "$dead_letter_id")" in
          exit|quit) exit 0 ;;
          0) ;;
          *) run_menu_action dead_letter_replay "$dead_letter_id" ;;
        esac
        ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


application_runtime_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Uruchamianie i status' 'Runtime and status')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Uruchom jeden projekt' 'Start one project')"
    printf '2. %s\n' "$(cc_text 'Uruchom wszystkie projekty w tle' 'Start all projects in background')"
    printf '3. %s\n' "$(cc_text 'Zatrzymaj wszystkie projekty z centrali' 'Stop all projects started by Control Center')"
    printf '4. %s\n' "$(cc_text 'Status procesow projektow' 'Project process status')"
    printf '5. %s\n' "$(cc_text 'Pokaz logi jednego projektu' 'Show logs for one project')"
    printf '6. %s\n' "$(cc_text 'Sprawdz dostepnosc wszystkich aplikacji' 'Check availability of all applications')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) if select_service; then run_menu_action run_app "$selected_service"; fi ;;
      2) run_menu_action app_run_all ;;
      3) run_menu_action app_stop_all ;;
      4) run_menu_action app_status_all ;;
      5) if select_service; then run_menu_action app_logs "$selected_service"; fi ;;
      6) run_menu_action health all ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


application_quality_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Testy i jakosc API' 'Testing and API quality')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Testy jednego projektu' 'Test one project')"
    printf '2. %s\n' "$(cc_text 'Testy calego monorepo' 'Test entire monorepo')"
    printf '3. %s\n' "$(cc_text 'Smoke przez API Gateway' 'Smoke test through API Gateway')"
    printf '4. %s\n' "$(cc_text 'App Verify - zamowienie, platnosc, magazyn, e-mail' 'App Verify - order, payment, inventory and e-mail')"
    printf '5. %s\n' "$(cc_text 'Test kontraktowy publicznego API v1' 'Public API v1 contract test')"
    printf '6. %s\n' "$(cc_text 'Test bezpieczenstwa API' 'API security test')"
    printf '7. %s\n' "$(cc_text 'Test obciazeniowy API' 'API load test')"
    printf '8. %s\n' "$(cc_text 'Pelny E2E: rejestracja, JWT, zakup i e-mail' 'Full E2E: registration, JWT, purchase and e-mail')"
    printf '9. %s\n' "$(cc_text 'Status odpornosci: timeouty, Retry, Circuit Breaker, Bulkhead' 'Resilience status: timeouts, Retry, Circuit Breaker, Bulkhead')"
    printf '10. %s\n' "$(cc_text 'Adresy dokumentacji OpenAPI/Swagger' 'OpenAPI/Swagger documentation URLs')"
    printf '11. %s\n' "$(cc_text 'Status testow Playwright UI' 'Playwright UI test status')"
    printf '12. %s\n' "$(cc_text 'Zainstaluj Playwright i Chromium' 'Install Playwright and Chromium')"
    printf '13. %s\n' "$(cc_text 'Playwright Smoke - najwazniejsze ekrany' 'Playwright Smoke - critical screens')"
    printf '14. %s\n' "$(cc_text 'Pelna regresja UI w Chromium' 'Full UI regression in Chromium')"
    printf '15. %s\n' "$(cc_text 'Pelny checkout przez prawdziwe serwisy' 'Full checkout through real services')"
    printf '16. %s\n' "$(cc_text 'Regresja w wielu przegladarkach' 'Multi-browser regression')"
    printf '17. %s\n' "$(cc_text 'Test UI z widoczna przegladarka' 'Headed UI test')"
    printf '18. %s\n' "$(cc_text 'Debug Playwright' 'Playwright debug')"
    printf '19. %s\n' "$(cc_text 'Pokaz raport Playwright' 'Show Playwright report')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) if select_service; then run_menu_action run_tests "$selected_service"; fi ;;
      2) run_menu_action run_tests all ;;
      3) run_menu_action smoke ;;
      4) run_menu_action app_verify ;;
      5) run_menu_action qa_contract_test ;;
      6) run_menu_action qa_security_test ;;
      7) run_menu_action qa_load_test ;;
      8) run_menu_action qa_e2e ;;
      9) run_menu_action resilience_status ;;
      10) run_menu_action api_docs_urls ;;
      11) run_menu_action qa_ui_status ;;
      12) run_menu_action qa_ui_install ;;
      13) run_menu_action qa_ui_smoke ;;
      14) run_menu_action qa_ui_regression ;;
      15) run_menu_action qa_ui_system ;;
      16) run_menu_action qa_ui_browsers ;;
      17) run_menu_action qa_ui_headed ;;
      18) run_menu_action qa_ui_debug ;;
      19) run_menu_action qa_ui_report ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


application_security_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Bezpieczenstwo i konta' 'Security and accounts')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Status JWT (sekret i tokeny ukryte)' 'JWT status (secrets and tokens hidden)')"
    printf '2. %s\n' "$(cc_text 'Smoke JWT: login, /me, refresh, logout' 'JWT smoke: login, /me, refresh, logout')"
    printf '3. %s\n' "$(cc_text 'Rotuj klucze JWT RSA dla aktywnego profilu dev/test' 'Rotate RSA JWT keys for active dev/test profile')"
    printf '4. %s\n' "$(cc_text 'Pokaz dziennik audytowy Customer Service' 'Show Customer Service audit log')"
    printf '5. %s\n' "$(cc_text 'Wyslij zadanie resetu hasla przez Mailpit' 'Send password reset request through Mailpit')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action jwt_status ;;
      2) run_menu_action jwt_smoke ;;
      3) run_menu_action jwt_rotate "$(active_profile)" ;;
      4) run_menu_action audit_list 50 ;;
      5) run_menu_action password_reset_request ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


application_store_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Operacje sklepu' 'Store operations')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Pokaz i sprawdz kody rabatowe' 'List and validate promotion codes')"
    printf '2. %s\n' "$(cc_text 'Wygeneruj kod rabatowy' 'Create promotion code')"
    printf '3. %s\n' "$(cc_text 'Pokaz opinie oczekujace na moderacje' 'Show reviews pending moderation')"
    printf '4. %s\n' "$(cc_text 'Opublikuj albo odrzuc opinie' 'Publish or reject a review')"
    printf '5. %s\n' "$(cc_text 'Pokaz numer przesylki i status przewoznika' 'Show tracking number and carrier status')"
    printf '6. %s\n' "$(cc_text 'Zmien symulowany status przesylki' 'Change simulated shipment status')"
    printf '7. %s\n' "$(cc_text 'Pokaz wiadomosci z czatu' 'Show customer chat messages')"
    printf '8. %s\n' "$(cc_text 'Odpowiedz klientowi przez e-mail' 'Reply to customer by e-mail')"
    printf '9. %s\n' "$(cc_text 'Pokaz zwroty i reklamacje' 'Show returns and complaints')"
    printf '10. %s\n' "$(cc_text 'Zmien status zwrotu lub reklamacji' 'Change return or complaint status')"
    printf '11. %s\n' "$(cc_text 'Pokaz liste wszystkich zamowien' 'Show all submitted orders')"
    printf '12. %s\n' "$(cc_text 'Pokaz fakture dla zamowienia' 'Show invoice for an order')"
    printf '13. %s\n' "$(cc_text 'Pobierz PDF faktury dla zamowienia' 'Download invoice PDF for an order')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action promotion_codes ;;
      2) run_menu_action promotion_code_create ;;
      3) run_menu_action review_pending ;;
      4) run_menu_action review_moderate ;;
      5) run_menu_action delivery_tracking ;;
      6) run_menu_action delivery_tracking_status ;;
      7) run_menu_action chat_messages 50 ;;
      8) run_menu_action chat_reply ;;
      9) run_menu_action returns_list ;;
      10) run_menu_action returns_status ;;
      11) run_menu_action orders_list 100 ;;
      12) run_menu_action billing_invoice ;;
      13) run_menu_action invoice_pdf ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


application_build_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Budowanie i artefakty' 'Builds and artifacts')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Pakietowanie calego monorepo' 'Package entire monorepo')"
    printf '2. %s\n' "$(cc_text 'Zbuduj lokalne obrazy Docker aplikacji' 'Build local Docker images')"
    printf '3. %s\n' "$(cc_text 'Uruchom aplikacje i frontend w kontenerach Docker' 'Start apps and frontend in Docker')"
    printf '4. %s\n' "$(cc_text 'Zatrzymaj kontenery aplikacji' 'Stop Docker apps')"
    printf '5. %s\n' "$(cc_text 'Status kontenerow aplikacji' 'Docker apps status')"
    printf '6. %s\n' "$(cc_text 'Logi kontenerow aplikacji' 'Docker apps logs')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action run_package all ;;
      2) run_menu_action images_build ;;
      3) run_menu_action docker_apps_up ;;
      4) run_menu_action docker_apps_down ;;
      5) run_menu_action docker_apps_status ;;
      6) run_menu_action docker_apps_logs ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


application_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Projekty i aplikacje' 'Projects and applications')" "$C_RESET"
    printf '%s\n' "$(cc_text 'Wybierz obszar operacyjny:' 'Choose an operational area:')"
    printf '1. %s\n' "$(cc_text 'Uruchamianie i status' 'Runtime and status')"
    printf '2. %s\n' "$(cc_text 'Testy i jakosc API' 'Testing and API quality')"
    printf '3. %s\n' "$(cc_text 'Bezpieczenstwo i konta' 'Security and accounts')"
    printf '4. %s\n' "$(cc_text 'Operacje sklepu' 'Store operations')"
    printf '5. %s\n' "$(cc_text 'Budowanie i artefakty' 'Builds and artifacts')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) application_runtime_menu ;;
      2) application_quality_menu ;;
      3) application_security_menu ;;
      4) application_store_menu ;;
      5) application_build_menu ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


frontend_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Frontend sklepu' 'Store frontend')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Uruchom frontend' 'Start frontend')"
    printf '2. %s\n' "$(cc_text 'Zatrzymaj frontend' 'Stop frontend')"
    printf '3. %s\n' "$(cc_text 'Status frontendu' 'Frontend status')"
    printf '4. %s\n' "$(cc_text 'Pokaz logi frontendu' 'Show frontend logs')"
    printf '5. %s\n' "$(cc_text 'Otworz adres frontendu (pokaz URL)' 'Open frontend address (show URL)')"
    printf '6. %s\n' "$(cc_text 'Uruchom Centrale webowa w przegladarce' 'Start Web Control Center in browser')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action frontend_up ;;
      2) run_menu_action frontend_down ;;
      3) run_menu_action frontend_status ;;
      4) run_menu_action frontend_logs ;;
      5) printf '%s: http://127.0.0.1:%s\n' "$(cc_text 'Frontend' 'Frontend')" "$FRONTEND_PORT" ;;
      6) run_menu_action web_start ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


main_menu() {
  while true; do
    printf '\n%s============================================================%s\n' "$C_BLUE" "$C_RESET"
    printf '%s                 Mini Order Platform%s\n' "$C_BLUE" "$C_RESET"
    printf '%s============================================================%s\n' "$C_BLUE" "$C_RESET"
    printf '%s: %s\n\n' "$(cc_text 'Projekt' 'Project')" "$PROJECT_ROOT"
    printf '1. %s\n' "$(cc_text 'Bazy danych PostgreSQL' 'PostgreSQL databases')"
    printf '2. %s\n' "$(cc_text 'Projekty i aplikacje' 'Projects and applications')"
    printf '3. %s\n' "$(cc_text 'Redis Event Broker i streamy' 'Redis Event Broker and streams')"
    printf '4. %s\n' "$(cc_text 'Profile i Mailpit' 'Profiles and Mailpit')"
    printf '5. %s\n' "$(cc_text 'Monitoring i Dead Letter Queue' 'Monitoring and Dead Letter Queue')"
    printf '6. %s\n' "$(cc_text 'Frontend sklepu' 'Store frontend')"
    printf '7. %s\n' "$(cc_text 'Status infrastruktury Docker Compose' 'Docker Compose infrastructure status')"
    printf '8. %s\n' "$(cc_text 'Ustawienia' 'Settings')"
    printf '9. %s\n' "$(cc_text 'Kontrola wymagan przed startem' 'Environment check before startup')"
    printf '10. %s\n' "$(cc_text 'Instalator brakujacych skladnikow (wymaga ACCEPT)' 'Install missing components (requires ACCEPT)')"
    printf '11. %s\n' "$(cc_text 'Kontrola plikow przed publikacja na GitHub' 'Check files before publishing to GitHub')"
    printf '12. %s\n' "$(cc_text 'Doctor srodowiska' 'Environment doctor')"
    printf '13. %s\n' "$(cc_text 'Profil prod - przygotowanie (NIEDOSTEPNY)' 'Production profile - preparation (UNAVAILABLE)')"
    printf '14. %s\n' "$(cc_text 'Centrala webowa (Web UI w przegladarce)' 'Web Control Center (Web UI in browser)')"
    printf '0. %s\n' "$(cc_text 'Wyjscie' 'Exit')"
    choice_prompt
    read -r choice || exit 0
    case "$choice" in
      1) database_menu ;;
      2) application_menu ;;
      3) broker_menu ;;
      4) profile_menu ;;
      5) monitoring_menu ;;
      6) frontend_menu ;;
      7) run_menu_action compose ps ;;
      8) settings_menu ;;
      9) run_menu_action environment_check ;;
      10) run_menu_action environment_install ;;
      11) run_menu_action github_check ;;
      12) run_menu_action doctor ;;
      13) production_menu ;;
      14) run_menu_action web_start ;;
      0|exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}


