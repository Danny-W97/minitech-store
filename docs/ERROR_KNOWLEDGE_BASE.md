# 📚 Lokalna Baza Wiedzy o Błędach (Error Knowledge Base)
> **Projekt:** Mini Order Platform (MiniTech)  
> **Lokalizacja:** `docs/ERROR_KNOWLEDGE_BASE.md` oraz `scripts/control-center/data/error_database.json`  
> **Przeznaczenie:** Lokalny katalog typowych błędów deweloperskich i środowiskowych wraz z automatyczną diagnostyką w Centrali Kontrolnej.

---

## 📑 Spis Treści
1. [Bazy danych i infrastruktura PostgreSQL](#1-bazy-danych-i-infrastruktura-postgresql)
2. [Autoryzacja, JWT i Szyfrowanie MFA](#2-autoryzacja-jwt-i-szyfrowanie-mfa)
3. [Broker zdarzeń Redis i asynchroniczność](#3-broker-zdarzeń-redis-i-asynchroniczność)
4. [Porty sieciowe i konflikty adresów](#4-porty-sieciowe-i-konflikty-adresów)
5. [Migracje Flyway i integralność schematu](#5-migracje-flyway-i-integralność-schematu)
6. [Zasoby systemowe i środowisko WSL2/Docker](#6-zasoby-systemowe-i-środowisko-wsl2docker)
7. [Zarządzanie sekretami SOPS i Age](#7-zarządzanie-sekretami-sops-i-age)
8. [Komunikacja wewnętrzna i API Gateway](#8-komunikacja-wewnętrzna-i-api-gateway)
9. [Testy automatyczne i Playwright](#9-testy-automatyczne-i-playwright)

---

## 1. Bazy danych i infrastruktura PostgreSQL

### `DB_CONNECTION_REFUSED`
* **Identyfikator:** `DB_CONNECTION_REFUSED`
* **Wzorce w logu:** `Connection to .*5432 refused`, `Connection to localhost:1543.*refused`, `org.postgresql.util.PSQLException: Connection to`
* **Przyczyna:** Mikroserwis nie może nawiązać połączenia TCP z kontenerem bazy PostgreSQL (baza jest wyłączona lub jeszcze się uruchamia).
* **Rozwiązanie:** 
  1. Uruchom infrastrukturę baz danych w Centrali: kliknij **„Infrastruktura (infra-up)”**.
  2. Z poziomu CLI: `bash ./scripts/control-center/control-center.sh infra-up`
* **Szybka akcja w Centrali:** `infra-up`

### `DB_PASSWORD_AUTH_FAILED`
* **Identyfikator:** `DB_PASSWORD_AUTH_FAILED`
* **Wzorce w logu:** `password authentication failed for user`, `FATAL: password authentication failed`
* **Przyczyna:** Hasło podane w zmiennej środowiskowej mikroserwisu nie zgadza się z hasłem użytkownika w kontenerze bazy danych PostgreSQL.
* **Rozwiązanie:**
  1. Sprawdź i zsynchronizuj hasła w Centrali: zakładka **Operacje** -> **Sekrety DEV**.
  2. Jeśli bazy były już zainicjalizowane z innymi hasłami, zresetuj wolumeny baz: `bash ./scripts/control-center/control-center.sh infra-down && docker volume prune -f && bash ./scripts/control-center/control-center.sh infra-up`.
* **Szybka akcja w Centrali:** `secrets-edit-dev`

### `DB_DOES_NOT_EXIST`
* **Identyfikator:** `DB_DOES_NOT_EXIST`
* **Wzorce w logu:** `database ".*" does not exist`, `FATAL: database .* does not exist`
* **Przyczyna:** Baza danych dla danego serwisu nie została utworzona w kontenerze PostgreSQL.
* **Rozwiązanie:**
  1. Uruchom ponowną inicjalizację bazy: `bash ./scripts/control-center/control-center.sh infra-up`.
* **Szybka akcja w Centrali:** `infra-up`

---

## 2. Autoryzacja, JWT i Szyfrowanie MFA

### `JWT_SECRET_MISSING`
* **Identyfikator:** `JWT_SECRET_MISSING`
* **Wzorce w logu:** `CUSTOMER_JWT_SECRET is required`, `CUSTOMER_MFA_ENCRYPTION_KEY_BASE64 or JWT key material is required`, `CUSTOMER_JWT_SECRET must be valid Base64`
* **Przyczyna:** Brak skonfigurowanego klucza symetrycznego JWT (lub kluczy RSA) w pliku sekretów aktywnego profilu (`.env.dev.sops`).
* **Rozwiązanie:**
  1. Wygeneruj automatycznie brakujące klucze autoryzacji: `bash ./scripts/control-center/control-center.sh secrets-ensure-auth dev`.
  2. Lub wykonaj rotację kluczy RSA: `bash ./scripts/control-center/control-center.sh jwt-rotate dev`.
* **Szybka akcja w Centrali:** `secrets-ensure-auth-dev`

### `JWT_RSA_MISCONFIGURED`
* **Identyfikator:** `JWT_RSA_MISCONFIGURED`
* **Wzorce w logu:** `CUSTOMER_JWT_ACTIVE_KID, CUSTOMER_JWT_PRIVATE_KEY_BASE64 and .* must be configured together`, `Active JWT key is missing from CUSTOMER_JWT_PUBLIC_KEYS_JSON`
* **Przyczyna:** Niespójna konfiguracja kluczy asymetrycznych RSA (np. podano klucz prywatny bez identyfikatora KID lub bez wpisu w JWKS).
* **Rozwiązanie:**
  1. Wykonaj rotację kluczy poleceniem: `bash ./scripts/control-center/control-center.sh jwt-rotate dev`.
* **Szybka akcja w Centrali:** `jwt-rotate-dev`

---

## 3. Broker zdarzeń Redis i asynchroniczność

### `REDIS_CONNECTION_REFUSED`
* **Identyfikator:** `REDIS_CONNECTION_REFUSED`
* **Wzorce w logu:** `RedisConnectionException`, `Unable to connect to Redis`, `6379.*Connection refused`, `16379.*Connection refused`
* **Przyczyna:** Kontener `event-broker` (Redis) jest wyłączony lub port 6379/16379 jest niedostępny. Zdarzenia transakcyjne (Outbox) nie mogą zostać opublikowane.
* **Rozwiązanie:**
  1. Uruchom broker zdarzeń w Centrali: kliknij **„Infrastruktura (infra-up)”** lub poleceniem `broker-up`.
  2. Sprawdź stan strumienia: `bash ./scripts/control-center/control-center.sh stream-info`.
* **Szybka akcja w Centrali:** `broker-up`

---

## 4. Porty sieciowe i konflikty adresów

### `PORT_ALREADY_IN_USE`
* **Identyfikator:** `PORT_ALREADY_IN_USE`
* **Wzorce w logu:** `Address already in use`, `listen EADDRINUSE`, `BindException: Address already in use`, `port is already allocated`, `failed: port is already allocated`
* **Przyczyna:** Wybrany port TCP (np. 18080 dla Gateway lub 18100 dla Centrali) jest zajęty przez wcześniej uruchomiony proces w tle lub inną aplikację.
* **Rozwiązanie:**
  1. Zatrzymaj lokalne aplikacje uruchomione w tle: `bash ./scripts/control-center/control-center.sh app-stop-all`.
  2. Zatrzymaj kontenery Docker: `bash ./scripts/control-center/control-center.sh docker-apps-down`.
  3. Sprawdź proces blokujący dany port: `lsof -i :<PORT>` lub `netstat -ano | findstr :<PORT>`.
* **Szybka akcja w Centrali:** `docker-apps-down`

---

## 5. Migracje Flyway i integralność schematu

### `FLYWAY_CHECKSUM_MISMATCH`
* **Identyfikator:** `FLYWAY_CHECKSUM_MISMATCH`
* **Wzorce w logu:** `FlywayException`, `MigrationChecksumDiffersException`, `Validate failed: Migrations have failed validation`, `Checksum mismatch`
* **Przyczyna:** Treść wcześniej wykonanego skryptu migracji SQL w katalogu `db/migration` została zmodyfikowana po jej zaaplikowaniu w bazie danych.
* **Rozwiązanie:**
  1. Przywróć oryginalną treść zmienionego skryptu migracji i utwórz nowy plik migracji z kolejnym numerem wersji (np. `V2__...sql`).
  2. W środowisku lokalnym (DEV) możesz zresetować bazę danego mikroserwisu.
* **Szybka akcja w Centrali:** `infra-up`

---

## 6. Zasoby systemowe i środowisko WSL2/Docker

### `JVM_OUT_OF_MEMORY`
* **Identyfikator:** `JVM_OUT_OF_MEMORY`
* **Wzorce w logu:** `java.lang.OutOfMemoryError`, `Java heap space`, `GC overhead limit exceeded`, `Container killed by OOM killer`
* **Przyczyna:** Maszyna wirtualna Java wyczerpała przydzielony limit pamięci RAM. W środowisku monorepo z 15 mikroserwisami pamięć WSL2 może ulec wyczerpaniu.
* **Rozwiązanie:**
  1. Zwiększ limit pamięci w pliku konfiguracyjnym Windows `%USERPROFILE%\.wslconfig` (np. `memory=12GB`).
  2. Zrestartuj WSL: w PowerShell uruchom `wsl --shutdown`.
* **Szybka akcja w Centrali:** `environment-check`

### `DOCKER_DAEMON_NOT_RUNNING`
* **Identyfikator:** `DOCKER_DAEMON_NOT_RUNNING`
* **Wzorce w logu:** `Cannot connect to the Docker daemon`, `Is the docker daemon running?`, `docker.sock.*connect: no such file or directory`
* **Przyczyna:** Usługa Docker Desktop / Docker Engine nie jest uruchomiona na komputerze.
* **Rozwiązanie:**
  1. Uruchom aplikację Docker Desktop w systemie Windows.
  2. Upewnij się, że w ustawieniach Docker Desktop włączona jest integracja z dystrybucją WSL2 (*Settings -> Resources -> WSL Integration*).
* **Szybka akcja w Centrali:** `environment-check`

### `DOCKER_APPS_STARTUP_TIMEOUT`
* **Identyfikator:** `DOCKER_APPS_STARTUP_TIMEOUT`
* **Wzorce w logu:** `nie odpowiedzia.*w wyznaczonym czasie`, `Did not respond in time`, `Usluga .* jest OFFLINE`, `Nastepujace uslugi nie odpowiedzialy`
* **Przyczyna:** Równoległe uruchamianie 15 aplikacji Spring Boot (Java 25) oraz 14 kontenerów PostgreSQL na maszynie deweloperskiej w WSL2/Docker mocno obciąża procesor (CPU) i operacje wejścia/wyjścia (I/O) dysku. Czas potrzebny na start maszyn wirtualnych JVM, załadowanie kontekstu Spring Boot, inicjalizację pul HikariCP i migracje baz danych Flyway może wynieść od 60 do 95 sekund. Jeśli limit czasu oczekiwania w skrypcie był zbyt krótki (np. dawne 45 sekund), system zgłaszał błąd timeoutu, mimo że kontenery żyły (`status: running`), nie uległy awarii i wciąż się poprawnie inicjalizowały.
* **Rozwiązanie:**
  1. Kontenery w tle nadal pracują i za chwilę zakończą inicjalizację. Odczekaj kilkanaście sekund, a następnie kliknij przycisk **„Status kontenerów (Docker)”** (`docker-apps-status`) lub **„Sprawdź zdrowie (health)”** w Centrali.
  2. Sprawdź bieżące logi uruchamiania wybranego serwisu poleceniem w terminalu:
     ```bash
     bash ./scripts/control-center/control-center.sh docker-apps-logs order
     ```
  3. Jeśli start regularnie trwa zbyt długo z powodu braku zasobów komputera, zwiększ przydział pamięci RAM i rdzeni CPU dla maszyny wirtualnej WSL2 w pliku `%USERPROFILE%\.wslconfig` (np. `memory=12GB`, `processors=6`).
* **Szybka akcja w Centrali:** `docker-apps-status`

---

## 7. Zarządzanie sekretami SOPS i Age

### `SOPS_AGE_KEY_MISSING`
* **Identyfikator:** `SOPS_AGE_KEY_MISSING`
* **Wzorce w logu:** `Brak klucza .*\.agekey`, `Brak sekretow .*sops`, `SOPS_AGE_KEY_FILE`, `could not decrypt`
* **Przyczyna:** Brak klucza prywatnego Age w katalogu `~/.config/mini-order-platform/` lub niezaszyfrowany plik `.env.dev.sops`.
* **Rozwiązanie:**
  1. Zainicjalizuj klucze Age: `bash ./scripts/control-center/control-center.sh secrets-init`.
  2. Przeprowadź pierwszą konfigurację w Centrali lub poleceniem: `bash ./scripts/control-center/control-center.sh secrets-setup dev`.
* **Szybka akcja w Centrali:** `secrets-init`

---

## 8. Komunikacja wewnętrzna i API Gateway

### `UPSTREAM_SERVICE_UNAVAILABLE`
* **Identyfikator:** `UPSTREAM_SERVICE_UNAVAILABLE`
* **Wzorce w logu:** `503 Service Unavailable`, `502 Bad Gateway`, `CallNotPermittedException`, `ConnectException.*1808`, `ResourceAccessException`
* **Przyczyna:** Serwis zależny (np. Inventory, Payment lub Order) nie jest dostępny lub został zablokowany przez mechanizm Circuit Breaker (Resilience4j).
* **Rozwiązanie:**
  1. Sprawdź status wszystkich serwisów w Centrali: zakładka **Mikroserwisy**.
  2. Uruchom ponownie brakujący mikroserwis lub wszystkie usługi: `docker-apps-up`.
  3. Sprawdź stan Circuit Breakerów poleceniem: `bash ./scripts/control-center/control-center.sh resilience-status`.
* 📖 **Zastosowanie w projekcie:** Zobacz konfigurację Circuit Breakerów w [docs/APKA.md](APKA.md#111-maszyna-stanów-circuit-breaker-resilience4j).
* **Szybka akcja w Centrali:** `docker-apps-up`

---

## 9. Testy automatyczne i Playwright

### `PLAYWRIGHT_MISSING_HOST_DEPENDENCIES`
* **Identyfikator:** `PLAYWRIGHT_MISSING_HOST_DEPENDENCIES`
* **Wzorce w logu:** `Host system is missing dependencies to run browsers`, `Missing libraries:.*libgtk`, `Missing system dependencies`, `browserType.launch:.*Host system is missing dependencies`
* **Przyczyna:** Silnik WebKit w Playwright (emulacja Safari na Linuksie) wymaga 115 dodatkowych bibliotek systemowych (GTK4, GStreamer, X11, kodeki wideo/audio), których domyślnie brak w minimalistycznej instalacji WSL2 (Ubuntu). Silniki Chromium, Firefox oraz Mobile-Chrome działają w 100% poprawnie.
* **Rozwiązanie:**
  1. Jeśli chcesz testować WebKit w WSL, zainstaluj brakujące pakiety systemowe komendą w terminalu WSL:
     ```bash
     sudo npx playwright install-deps webkit
     ```
  2. Alternatywnie uruchom pełny zestaw testów na silnikach bez dodatkowych zależności: **Regresja UI Chromium** (`ui-test-regression`) lub zaktualizowany tryb wieloprzeglądarkowy `ui-test-browsers` (który automatycznie weryfikuje Chromium, Firefox i Mobile-Chrome).
* **Szybka akcja w Centrali:** `ui-test-regression`

### `PLAYWRIGHT_TEST_ASSERTION_FAILED`
* **Identyfikator:** `PLAYWRIGHT_TEST_ASSERTION_FAILED`
* **Wzorce w logu:** `\d+\s+failed`, `Error:\s*expect\(.*failed`, `Error:\s*expect\(received\)\.toBe`, `Error:\s*expect\(locator\)\.toBeVisible\(\) failed`, `TimeoutError:\s*locator\..*waiting for`, `Test timeout of \d+ms exceeded`
* **Przyczyna:** Jeden lub więcej kroków testów automatycznych interfejsu (UI) zakończyło się błędem asercji (np. rozbieżność stanu koszyka w `localStorage`, niewidoczny element DOM po kliknięciu lub wyścig asynchroniczny z API/sesją w przeglądarce).
* **Rozwiązanie:**
  1. Sprawdź szczegóły błędu w logu zadania powyżej lub obejrzyj nagrania wideo i zrzuty ekranu w `gitIgnoreOwn/runtime/playwright/test-results/`.
  2. Uruchom interaktywny raport Playwright w Centrali przyciskiem **„Raport Playwright (ui-test-report)”** lub poleceniem `bash ./scripts/control-center/control-center.sh ui-test-report`.
  3. Jeśli błąd wynikał z wyścigu asynchronicznego (race condition) przy szybkiej interakcji (np. kliknięcie dodania do koszyka przed zresolwowaniem sesji klienta w `product-detail.js` lub przejście do podsumowania przed pobraniem wyceny `deliveryQuote` w `store-checkout.js`), upewnij się, że obietnice asynchroniczne (`restoreSession()`, `loadDeliveryQuote()`) są oczekiwane przed interakcją z formularzem.
* **Szybka akcja w Centrali:** `ui-test-report`

---

## 10. Zamówienia i płatności (Order Service)

### `GUEST_ORDER_LIMIT_EXCEEDED`
* **Identyfikator:** `GUEST_ORDER_LIMIT_EXCEEDED`
* **Wzorce w logu:** `Orders with a product subtotal above PLN 1000 require a customer account`, `ACCOUNT_REQUIRED`, `Nie udalo sie utworzyc zamowienia testowego`
* **Przyczyna:** Próba złożenia zamówienia jako gość (`customerId: null`) na kwotę powyżej 1000 PLN. Polityka platformy (`GUEST_SUBTOTAL_LIMIT = 1000.00 PLN`) chroni sklep przed oszustwami i wymaga posiadania zarejestrowanego i zweryfikowanego konta klienta dla transakcji o wysokiej wartości.
* **Rozwiązanie:**
  1. W testach automatycznych (`app-verify`, `e2e`): użyj produktu o cenie poniżej 1000 PLN (np. produkt #1 Samsung 990 PRO - 699,99 PLN lub #2 RAM - 499,99 PLN), ustawiając `APP_VERIFY_PRODUCT_ID=1`.
  2. W sklepie webowym: zaloguj się na konto klienta przed przejściem do kasy dla koszyka powyżej 1000 PLN.
* **Szybka akcja w Centrali:** `app-verify`
