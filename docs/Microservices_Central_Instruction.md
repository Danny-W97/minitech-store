# Microservices Central Instruction

> English version:
> [Microservices_Central_Instruction_EN.md](Microservices_Central_Instruction_EN.md)

## Status i zakres

Ten dokument opisuje obsługę `Control Center` od pierwszego uruchomienia do
codziennej diagnostyki projektu Mini Order Platform.

Control Center jest lokalną centralą operacyjną uruchamianą w WSL. Domyślnie
otwiera ciemny panel webowy pod `http://127.0.0.1:18100`, a pełny dotychczasowy
interfejs terminalowy pozostaje dostępny jako tryb zgodności. Centrala obsługuje
profile `dev` i `test`. Profil `prod` jest obecnie
niedostępny do uruchamiania — centrala udostępnia dla niego wyłącznie walidację
przygotowanych manifestów Kubernetes, OpenBao i TLS.

Opis architektury, odpowiedzialności mikroserwisów, przepływ zamówienia i
szczegóły domeny znajdują się w [General_Instruction.md](General_Instruction.md).

## 1. Czym jest Control Center

Control Center jest jedynym zalecanym punktem obsługi lokalnego środowiska.
Panel webowy zachowuje szatę graficzną MiniTech Store, ale używa ciemnego tła,
aby odróżnić widok operatora od sklepu i ograniczyć ilość bieli. Może:

- wykryć WSL/Linux/macOS i sprawdzić wymagane narzędzia,
- ustawić aktywny profil Spring `dev` albo `test`,
- walidować i ładować sekrety z SOPS + age,
- uruchamiać i zatrzymywać bazy PostgreSQL, Redis, Mailpit, mikroserwisy,
  frontend oraz monitoring,
- wyświetlać statusy, logi i adresy usług,
- uruchamiać testy jednostkowe, integracyjne, kontraktowe, bezpieczeństwa,
  obciążeniowe i E2E,
- wykonywać backupy i test odtworzenia PostgreSQL,
- obsługiwać JWT, reset hasła, audyt, MFA, promocje, opinie, dostawy i czat,
- przygotować diagnostykę profilu produkcyjnego bez jego uruchamiania.

Panel webowy zawiera widoki `Pulpit`, `Usługi`, `Operacje`, `Czat klientów`,
`Testy`, `Zadania i logi` oraz `Terminal WSL`. Operacje są uruchamiane z
kontrolowanej białej listy; backend nie przyjmuje dowolnych poleceń systemowych.
Serwer nasłuchuje wyłącznie na `127.0.0.1` i wymaga lokalnego tokenu sesji dla
żądań zmieniających stan.

Zasady terminalowego trybu zgodności:

- `0` wraca do poprzedniego menu,
- `exit` albo `quit` zamyka panel,
- Enter przy pytaniu o opcjonalną wartość wybiera wartość domyślną,
- nie należy uruchamiać kilku central jednocześnie,
- wartości sekretów nie są wyświetlane w statusach ani logach centrali.

### Standard obsługi menu

Panel stosuje układ warstwowy: menu główne wybiera obszar techniczny, a menu
obszaru wybiera konkretną operację. Jest to celowe rozdzielenie funkcji
operacyjnych od diagnostyki i operacji biznesowych:

| Obszar | Przeznaczenie |
|---|---|
| Bazy danych | uruchamianie, status, backup i reset danych demonstracyjnych |
| Projekty i aplikacje | procesy, testy, bezpieczeństwo, operacje sklepu i artefakty |
| Redis / streamy | broker zdarzeń i Dead Letter Queue |
| Profile i Mailpit | profile, sekrety, poczta testowa i DAST |
| Monitoring | Prometheus, Grafana i DLQ |
| Frontend | uruchomienie i status sklepu |
| Kontrole | wymagania, diagnostyka i kontrola publikacji |

W menu `Projekty i aplikacje` operacje są dalej podzielone na pięć grup.
Nazwy grup są stabilniejsze niż numery pojedynczych pozycji. Dla automatyzacji
używaj poleceń bezpośrednich, np. `returns-list`, `invoice-show` lub
`promotion-code-create`; menu interaktywne jest przeznaczone do ręcznej obsługi.

Operacje mogące zmienić dane wymagają jawnego potwierdzenia (`RESET`, status,
kod lub identyfikator). Wyniki powinny być sprawdzane przez status usługi albo
ponowne zapytanie listujące. Sekrety, tokeny i hasła są maskowane; jeśli
operacja zgłasza brak sekretu, uzupełnij go przez `secrets-edit`, a nie przez
zwykły plik w repozytorium.

## 2. Gdzie znajdują się pliki

Uruchamiaj polecenia z katalogu głównego projektu:

```text
Mini Order Platform/
├── compose.yaml
├── docker-compose.monitoring.yaml
├── frontend/
├── scripts/
│   ├── start.sh
│   ├── start.ps1
│   ├── control-center/control-center.sh
│   ├── control-center/web/
│   │   ├── server.py
│   │   ├── index.html
│   │   ├── styles.css
│   │   └── app.js
│   ├── frontend-server.py
│   ├── start-dev.ps1
│   └── stop-dev.ps1
├── docs/
│   ├── General_Instruction.md
│   └── Microservices_Central_Instruction.md
└── gitIgnoreOwn/
    └── runtime/
```

Najważniejsze lokalizacje:

| Element | Lokalizacja / port |
|---|---|
| Główny launcher Bash | `scripts/start.sh` |
| Launcher Windows → WSL | `scripts/start.ps1` |
| Skrypt centrali | `scripts/control-center/control-center.sh` |
| Panel operatora | `http://127.0.0.1:18100` |
| Backend panelu | `scripts/control-center/web/server.py` |
| Lokalne dane runtime | `gitIgnoreOwn/runtime/` |
| Logi aplikacji uruchomionych przez centralę | `gitIgnoreOwn/runtime/logs/` |
| Skarbiec SOPS + age w WSL | `/home/<użytkownik>/.config/mini-order-platform/` |
| Frontend | `http://127.0.0.1:18070` |
| API Gateway | `http://127.0.0.1:18080` |

Prawdziwych sekretów, kluczy age/JWT, certyfikatów prywatnych, backupów,
kubeconfigów i logów nie umieszczaj w repozytorium. Zgodnie z projektem należy
trzymać je poza repozytorium lub w `gitIgnoreOwn/`, zależnie od rodzaju danych.

## 3. Uruchomienie centrali

### WSL — zalecana ścieżka

W PowerShell z katalogu projektu:

```powershell
.\scripts\start.ps1
```

Launcher otworzy osobne okno WSL, uruchomi lokalny serwer Centrali i otworzy
panel w domyślnej przeglądarce. Można też wejść do
WSL ręcznie:

```bash
cd "/mnt/c/Dysk D/stuff/Mini Order Platform"
bash ./scripts/start.sh
```

Polecenie bez argumentów uruchamia panel graficzny. Okno terminala WSL, z
którego został uruchomiony serwer, musi pozostać otwarte. Zamknięcie go albo
`Ctrl+C` zatrzymuje wyłącznie panel Centrali — nie zatrzymuje automatycznie
działających kontenerów i mikroserwisów.

Tryb terminalowy uruchomisz poleceniem:

```bash
bash ./scripts/start.sh --cli
```

Bez automatycznego otwierania przeglądarki:

```bash
bash ./scripts/start.sh --no-browser
```

Port można zmienić lokalnie, np. `bash ./scripts/start.sh --port 18101`.
Panel zawsze pozostaje przypięty do interfejsu loopback.

### Obsługa panelu webowego

- `Pulpit` pokazuje profil, liczbę usług online i najczęstsze operacje.
- `Usługi` odpytują minimalne endpointy Actuator wszystkich 15 aplikacji.
- `Operacje` zawierają 112 dozwolonych operacji: infrastrukturę, bazy, backupy,
  bezpieczeństwo, pocztę, ustawienia języka menu, operacje sklepu i
  przygotowanie prod. Parametry są przekazywane jako osobne argumenty, bez
  wykonywania dowolnych poleceń powłoki.
- `Czat klientów` pokazuje rozmowy i pozwala odpowiedzieć bez wpisywania ID;
  odpowiedź trafia również do Notification Service.
- `Testy` uruchamiają te same skrypty smoke, E2E, ZAP i Playwright co CLI.
- `Zadania i logi` pokazują wynik procesu. Logi trafiają do
  `gitIgnoreOwn/runtime/control-center-web/jobs/`.
- `Terminal WSL` osadza stare menu w panelu. Służy przede wszystkim do
  `secrets-edit`, `db-shell`, Vim i innych operacji wymagających interaktywnego
  terminala. Pierwszą konfigurację sekretów można wykonać bezpośrednio w panelu
  webowym; terminalowy `secrets-setup` pozostaje dostępny jako alternatywa.

Zmiana języka w widoku `Operacje` dotyczy klasycznego menu terminalowego.
Graficzny panel operatora jest obecnie dostępny po polsku, natomiast jego pełna
instrukcja ma również wersję angielską.

Panel nie odczytuje i nie pokazuje istniejących wartości sekretów. Operacje
`Utwórz sekrety DEV/TEST` i edycja sekretów korzystają z dedykowanych formularzy
lokalnych — nie są zadaniami z logiem, nie przekazują wartości w URL-u ani w
argumentach procesu. Po zapisaniu dane jawne są szyfrowane SOPS-em, a plik
tymczasowy jest usuwany. Dla istniejących wolumenów PostgreSQL należy podać
dotychczasowe hasła.

### Nowe rozszerzenia obsługi sekretów w panelu

Panel rozpoznaje stan skarbca i pokazuje właściwą operację dla każdego profilu:

| Stan profilu | Operacja w `Operacje → Profile i sekrety` | Działanie |
|---|---|---|
| Brak `*.env.sops` | `Utwórz sekrety DEV` albo `Utwórz sekrety TEST` | Pierwsza konfiguracja, utworzenie kluczy age i szyfrowanego pliku |
| Istnieją klucz i plik | `Sekrety DEV` albo `Sekrety TEST` | Edycja wybranych pól bez odczytywania dotychczasowych wartości |
| Brak klucza lub narzędzi | `Inicjalizuj klucze age` / `Status skarbca` | Diagnostyka i przygotowanie środowiska |

Kreator pierwszej konfiguracji wymaga:

- czternastu haseł baz PostgreSQL,
- `CUSTOMER_JWT_SECRET` jako Base64 z minimum 32 bajtami po dekodowaniu,
- `GRAFANA_ADMIN_PASSWORD`.

Pozostałe pola mają bezpieczne wartości lokalne: użytkownik Grafany `admin`,
Mailpit `127.0.0.1:11025`, puste dane logowania SMTP, wyłączone SMTP Auth i
STARTTLS oraz adresy nadawcy/wsparcia zależne od profilu. Można je zmienić w
formularzu przed zapisaniem. Jeśli wolumen PostgreSQL już istnieje, wpisz
hasła użyte podczas jego pierwszej inicjalizacji — sama zmiana pliku sekretów
nie zmienia hasła zapisującego się w bazie.

Po zaznaczeniu potwierdzenia formularz przekazuje wartości do dedykowanego
procesu przez standardowe wejście w postaci Base64. Wartości nie występują w
URL-u, argumentach procesu, logach zadań ani odpowiedzi API. Jawny plik
tymczasowy jest szyfrowany SOPS-em z kluczem age profilu i usuwany. Kreator nie
uruchamia kontenerów ani mikroserwisów; po sukcesie wykonaj osobno:

```bash
bash scripts/control-center/control-center.sh secrets-validate dev
bash scripts/control-center/control-center.sh infra-up
```

Dla profilu `test` zamień `dev` na `test`. Jeżeli zaszyfrowany plik już istnieje,
kreator pierwszej konfiguracji odmówi nadpisania — użyj wtedy formularza edycji
albo terminalowego `secrets-edit`.

### Linux/macOS

```bash
cd "/ścieżka/do/Mini Order Platform"
bash ./scripts/start.sh
```

### IntelliJ IDEA

Konfiguracja typu Shell Script może uruchamiać:

```text
scripts/control-center/ide-environment.ps1 dev
```

Ta konfiguracja przygotowuje profil i środowisko pod uruchamianie usług z IDE.
Jeżeli logi mają być widoczne w osobnych konsolach IntelliJ, uruchamiaj usługi
pojedynczo przez konfiguracje korzystające z `scripts/control-center/ide-run.ps1`.
Szczegóły integracji IDE opisuje rozdział IntelliJ w instrukcji ogólnej.

## 4. Kontrola wymagań przed startem

Przed pierwszym uruchomieniem lub po zmianie komputera wykonaj:

```bash
bash scripts/control-center/control-center.sh platform-info
bash scripts/control-center/control-center.sh environment-check
bash scripts/control-center/control-center.sh secrets-status
bash scripts/control-center/control-center.sh doctor
```

`environment-check` sprawdza m.in.:

- Bash, `curl`, `python3`, `git`, Java/JDK 25 i `javac`,
- Docker daemon i `docker compose`,
- pliki Compose, Maven Wrapper i frontend,
- `sops`, `age`, `age-keygen`,
- aktywny profil i komplet wymaganych sekretów,
- mapę baz, portów i katalog runtime.

`doctor` wykonuje szerszą kontrolę już uruchomionych baz, aplikacji, Mailpit i
opcjonalnego monitoringu.

Jeżeli brakuje pakietu systemowego, można wyświetlić plan instalacji:

```bash
bash scripts/control-center/control-center.sh environment-install
```

Instalator niczego nie zmienia bez potwierdzenia `ACCEPT`. Nie instaluje
automatycznie runtime Docker–WSL ani nie konfiguruje uprawnień systemowych.

## 5. Profile dev i test

### Zmiana profilu

```bash
bash scripts/control-center/control-center.sh profile-show
bash scripts/control-center/control-center.sh profile-set dev
bash scripts/control-center/control-center.sh profile-set test
```

W panelu interaktywnym:

```text
Menu główne → 4. Profile i Mailpit → 2. Ustaw profil dev
Menu główne → 4. Profile i Mailpit → 3. Ustaw profil test
```

Aktywny profil jest zapisywany lokalnie w `gitIgnoreOwn/runtime/active-profile`.
Control Center ustawia go jako `SPRING_PROFILES_ACTIVE` dla uruchamianych usług.

### Sekrety SOPS + age

Skarbiec jest lokalny dla WSL i nie jest częścią repozytorium:

```text
/home/<użytkownik>/.config/mini-order-platform/
├── keys/dev.agekey
├── keys/test.agekey
└── secrets/
    ├── dev.env.sops
    └── test.env.sops
```

Pierwsza konfiguracja:

```bash
bash scripts/control-center/control-center.sh secrets-init
bash scripts/control-center/control-center.sh secrets-setup dev
bash scripts/control-center/control-center.sh secrets-setup test
```

To samo można wykonać w panelu webowym: `Operacje → Profile i sekrety →
Utwórz sekrety DEV/TEST`. Formularz tworzy klucze age, przyjmuje wymagane
wartości, szyfruje plik profilu i nie uruchamia usług automatycznie. Terminalowy
kreator pozostaje dostępny dla użytkowników preferujących CLI.

Jeżeli klucz istnieje, ale brakuje pliku profilu:

```bash
bash scripts/control-center/control-center.sh secrets-edit dev
bash scripts/control-center/control-center.sh secrets-edit test
```

Walidacja bez wyświetlania haseł:

```bash
bash scripts/control-center/control-center.sh secrets-validate dev
bash scripts/control-center/control-center.sh secrets-validate test
```

Pełne środowisko `dev/test` wymaga następujących wartości:

| Obszar | Wymagane zmienne |
| --- | --- |
| Bazy głównego procesu zakupu | `PRODUCT_DB_PASSWORD`, `ORDER_DB_PASSWORD`, `INVENTORY_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD`, `CUSTOMER_DB_PASSWORD` |
| Bazy funkcji sklepu | `DELIVERY_DB_PASSWORD`, `PROMOTION_DB_PASSWORD`, `WISHLIST_DB_PASSWORD`, `REVIEW_DB_PASSWORD`, `RECOMMENDATION_DB_PASSWORD` |
| Bazy obsługi klienta i dokumentów | `CHAT_DB_PASSWORD`, `RETURNS_DB_PASSWORD`, `BILLING_DB_PASSWORD` |
| Monitoring | `GRAFANA_ADMIN_PASSWORD` |
| Uwierzytelnianie Customer Service | `CUSTOMER_JWT_SECRET` albo komplet RS256: `CUSTOMER_JWT_ACTIVE_KID`, `CUSTOMER_JWT_PRIVATE_KEY_BASE64`, `CUSTOMER_JWT_PUBLIC_KEYS_JSON` |

Standardowe `secrets-validate`, `infra-up` i `app-run-all` wymagają kompletu
14 haseł baz — także wtedy, gdy użytkownik nie zamierza od razu otwierać widoku
Chat, Returns lub Billing. Kreator początkowo zapisuje wariant JWT HMAC;
`jwt-rotate dev` albo `jwt-rotate test` może później utworzyć wariant RS256.

W lokalnym Mailpit `MAIL_USERNAME` i `MAIL_PASSWORD` pozostają puste.
`MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`, `MAIL_SMTP_AUTH`,
`MAIL_SMTP_STARTTLS_ENABLE`, `GRAFANA_ADMIN_USER` i `CHAT_SUPPORT_EMAIL` są
konfiguracją profilu, a nie obowiązkowymi sekretami walidowanymi przez
`secrets-validate`. Dla `CHAT_SUPPORT_EMAIL` można pozostawić wartość kreatora:
`company@minitech.local` w `dev` albo `company@minitech.test` w `test`.

Wartości edytuj wyłącznie przez kreator lub `secrets-edit`. Nie twórz zwykłego
`.env` z prawdziwymi hasłami w katalogu projektu.

## 6. Uruchomienie infrastruktury

### Bazy PostgreSQL

Mapa baz i portów:

```bash
bash scripts/control-center/control-center.sh db-list
```

Uruchomienie wszystkich baz:

```bash
bash scripts/control-center/control-center.sh infra-up
```

Operacje na pojedynczej bazie:

```bash
bash scripts/control-center/control-center.sh db-up chat
bash scripts/control-center/control-center.sh db-status chat
bash scripts/control-center/control-center.sh db-logs chat
bash scripts/control-center/control-center.sh db-shell chat
```

Zatrzymanie baz bez usuwania wolumenów:

```bash
bash scripts/control-center/control-center.sh infra-down
```

Pełne zatrzymanie głównego Compose (`docker compose down`, obejmuje również
Redis i Mailpit, ale nie usuwa wolumenów):

```bash
bash scripts/control-center/control-center.sh db-down-all
```

### Redis Event Broker

```bash
bash scripts/control-center/control-center.sh broker-up
bash scripts/control-center/control-center.sh broker-status
bash scripts/control-center/control-center.sh stream-info
bash scripts/control-center/control-center.sh stream-read 20
```

Redis obsługuje stream `payment-events`, grupę konsumenta Notification Service,
limity logowania/rate limiting i wyzwania MFA. Nie należy usuwać consumer group
bez potrzeby. Jeżeli trzeba odtworzyć jej metadane:

```bash
bash scripts/control-center/control-center.sh broker-reset-group notification-service
```

### Mailpit

```bash
bash scripts/control-center/control-center.sh mailpit-up
bash scripts/control-center/control-center.sh mailpit-status
bash scripts/control-center/control-center.sh mailpit-list 20
bash scripts/control-center/control-center.sh mailpit-search "subject:MiniTech"
bash scripts/control-center/control-center.sh mailpit-smoke
```

Panel Mailpit: `http://127.0.0.1:18025`.
SMTP Mailpit: `127.0.0.1:11025`.

Mailpit przechwytuje e-maile w `dev/test` i nie wysyła ich do prawdziwych
odbiorców. `mailpit-send` służy do ręcznego testu wysyłki przez Notification
Service, a `mailpit-clear` wymaga dodatkowego potwierdzenia.

### Monitoring i DLQ

```bash
bash scripts/control-center/control-center.sh monitoring-up
bash scripts/control-center/control-center.sh monitoring-status
bash scripts/control-center/control-center.sh monitoring-urls
bash scripts/control-center/control-center.sh dlq-list
bash scripts/control-center/control-center.sh dlq-replay <id>
```

Prometheus i Grafana działają w osobnym Compose. Ich porty są celowo inne niż
standardowe porty zajęte przez inne projekty. Hasło administratora Grafany jest
ładowane ze skarbca profilu.

## 7. Uruchamianie mikroserwisów

### Wszystkie usługi w tle

Po uruchomieniu infrastruktury:

```bash
bash scripts/control-center/control-center.sh app-run-all
bash scripts/control-center/control-center.sh app-status-all
bash scripts/control-center/control-center.sh health all
```

`app-run-all` uruchamia wszystkie piętnaście aplikacji: Gateway, Product,
Order, Inventory, Payment, Notification, Customer, Delivery, Promotion,
Wishlist, Review, Recommendation, Chat, Returns i Billing Service.

### Jedna usługa i logi w bieżącej konsoli

```bash
bash scripts/control-center/control-center.sh app-run product dev
bash scripts/control-center/control-center.sh app-logs product
```

Aliasy usług: `gateway`, `product`, `order`, `inventory`, `payment`,
`notification`, `customer`, `delivery`, `promotion`, `wishlist`, `review`,
`recommendation`, `chat`, `returns`, `billing`.

Zatrzymanie procesów uruchomionych przez centralę:

```bash
bash scripts/control-center/control-center.sh app-stop-all
```

Nie zatrzymuje to kontenerów baz, Redis ani Mailpit.

### Frontend

```bash
bash scripts/control-center/control-center.sh frontend-up
bash scripts/control-center/control-center.sh frontend-status
```

Sklep: `http://127.0.0.1:18070`.

## 8. Menu główne — mapa funkcji

Menu uruchamia się przez `bash ./scripts/start.sh`.

### 1. Bazy danych PostgreSQL

Najważniejsze opcje:

1. mapa baz i portów,
2. uruchom wszystkie bazy,
3. zatrzymaj wszystkie bazy (`docker compose down`),
4. status baz,
5. logi jednej bazy,
6. `psql` dla wybranej bazy,
7. reset ilości magazynowych,
8. reset danych demonstracyjnych,
9. backup jednej bazy,
10. backup wszystkich baz,
11. lista backupów,
12. test odtworzenia,
13. usunięcie backupów starszych niż retencja,
14. przygotowanie klucza szyfrowania backupów,
15. instalacja harmonogramu backupów,
16. usunięcie harmonogramu,
17. status harmonogramu.

### 2. Projekty i aplikacje

Menu jest podzielone na obszary operacyjne zamiast jednej długiej listy:

1. `Uruchamianie i status` — start/stop aplikacji, procesy, logi i health check,
2. `Testy i jakość API` — testy Maven, smoke, App Verify, contract, security,
   load, E2E, odporność i adresy OpenAPI,
3. `Bezpieczeństwo i konta` — JWT, rotacja kluczy, audyt i reset hasła,
4. `Operacje sklepu` — promocje, opinie, dostawy, czat, zwroty, reklamacje i
   faktury,
5. `Budowanie i artefakty` — pakietowanie oraz obrazy Docker.

Numery wewnątrz tych podmenu mogą być rozwijane niezależnie. Polecenia terminalowe
(`promotion-code-create`, `returns-list`, `invoice-show` itd.) pozostają stabilne
i są właściwym interfejsem dla automatyzacji oraz CI.

Przykładowe ścieżki obsługi:

- `Projekty i aplikacje → 4. Operacje sklepu → 1` — lista i walidacja kodów,
- `→ 4. Operacje sklepu → 2` — utworzenie kodu rabatowego,
- `→ 4. Operacje sklepu → 9` — lista zwrotów i reklamacji,
- `→ 4. Operacje sklepu → 10` — zmiana statusu zgłoszenia,
- `→ 4. Operacje sklepu → 11` — lista wszystkich zamówień z kwotą, e-mailem,
  pozycjami i informacją o fakturze,
- `→ 4. Operacje sklepu → 12` — podgląd faktury zamówienia,
- `→ 4. Operacje sklepu → 13` — pobranie PDF faktury do katalogu lokalnego,
- `→ 4. Operacje sklepu → 7/8` — odczyt i odpowiedź w czacie.

### 3. Redis Event Broker i streamy

Uruchomienie, zatrzymanie, PING, logi, informacje o streamie, odczyt rekordów i
reset consumer group.

### Nowe usługi domenowe

`infra-up` uruchamia również `returns-db` na `127.0.0.1:15444` oraz
`billing-db` na `127.0.0.1:15445`. Aplikacje są dostępne odpowiednio na
`127.0.0.1:18094` i `127.0.0.1:18095`, a ich endpointy są wystawione przez
Gateway pod `/api/returns` i `/api/invoices`.

Do startu obu baz potrzebne są sekrety `RETURNS_DB_PASSWORD` i
`BILLING_DB_PASSWORD` w aktywnym skarbcu. Po ich dodaniu użyj `infra-up`, a
następnie `app-run-all`; pojedynczo można uruchomić `app-run returns dev` albo
`app-run billing dev`.

### 4. Profile i Mailpit

Zmiana profilu, Mailpit, test SMTP, lista/wyszukiwanie/czyszczenie wiadomości,
status skarbca, inicjalizacja kluczy, kreator sekretów, edycja sekretów, JWT,
audyt, reset hasła i test DAST.

### 5. Monitoring i Dead Letter Queue

Prometheus/Grafana, statusy, logi, adresy oraz listowanie i replay wpisów DLQ.

### 6. Frontend sklepu

Start, stop, status, logi i wyświetlenie adresu frontendu.

### 7. Status infrastruktury Docker Compose

Skrócony status kontenerów głównego Compose.

### 8. Ustawienia

`1. Język` otwiera wybór polskiego lub angielskiego. `2` pokazuje aktualny
język. Ustawienie jest lokalne i trafia do runtime, nie do repozytorium.

### 9–12. Kontrole

- `9` — kontrola wymagań przed startem,
- `10` — opcjonalny instalator brakujących składników po wpisaniu `ACCEPT`,
- `11` — kontrola plików przed publikacją na GitHub,
- `12` — pełna diagnostyka środowiska.

### 13. Profil prod — przygotowanie (NIEDOSTĘPNY)

Menu nie uruchamia produkcji. Tylko sprawdza manifesty Kubernetes, przygotowanie
OpenBao/TLS, status wskazanego hosta TLS i lokalizacje szablonów.

## 9. Testy, diagnostyka i jakość

Testy uruchamiaj z katalogu głównego projektu. Przed testami HTTP wykonaj
`environment-check`, walidację sekretów, `infra-up`, `broker-up`, `mailpit-up`
i `app-run-all`. `app-test` oraz testy integracyjne Maven mogą działać bez
uruchomionych mikroserwisów HTTP.

### 9.1. Mapa testów i przygotowanie

| Test | Sprawdza | Wymagania | Skutek |
| --- | --- | --- | --- |
| `app-test` / Maven `test` | Testy jednostkowe i kontekst Spring Boot wszystkich modułów | JDK 25, Maven Wrapper | Nie zmienia lokalnych danych |
| Maven `-Pintegration verify` | PostgreSQL, sagę Order oraz Redis/Mailpit przez Testcontainers | JDK 25 i Docker daemon | Tworzy i usuwa tymczasowe kontenery |
| `smoke` | Gateway → Product Service → katalog | Gateway i Product | Odczyt |
| `contract-test` | OpenAPI Gateway zgodne z kontraktem API v1 | Gateway | Odczyt |
| `security-test` | JWT, błędny Bearer, minimalny payload Actuator i ekspozycję sekretów | Gateway i Customer | Odczyt |
| `load-test` | 100 żądań katalogu, domyślnie 10 równoległych | Gateway i Product | Odczyt, obciążenie lokalne |
| `resilience-test` | Izolację awarii mikroserwisu (np. payment-service) oraz kontrolowaną degradację | Uruchomione środowisko (profil dev lub test) | Chwilowo wyłącza serwis, a po teście sam go przywraca |
| `delivery-smoke` / `promotion-smoke` | Wyceny dostaw i aktywną kampanię | Delivery/Promotion | Odczyt |
| `mailpit-smoke` | Notification Service → SMTP Mailpit | Notification, Redis, Mailpit | Dodaje testową wiadomość |
| `app-verify` | Zamówienie → płatność → magazyn → e-mail | Wszystkie mikroserwisy, Redis, Mailpit | Tworzy zamówienie i zmniejsza magazyn |
| `e2e` | Rejestrację, Mailpit, JWT, refresh/logout, rekomendacje i zakup | Komplet środowiska | Tworzy konto, zamówienie i wiadomość |
| `ui-test-smoke` | Trzy krytyczne ścieżki frontendowe w Chromium | Node.js ≥ 20, Playwright | API mockowane, bez zmian w bazach |
| `ui-test` | 24 scenariusze UI: katalog, konto, koszyk, limit bezczynności sesji, checkout, czat i responsywność | Node.js ≥ 20, Playwright i Chromium | API mockowane, bez zmian w bazach |
| `ui-test-system` | Checkout w przeglądarce przez prawdziwe mikroserwisy | Komplet środowiska, frontend i Chromium | Tworzy zamówienie i e-mail |
| `zap-dast` | Aktywny skan OWASP ZAP API Gateway | Gateway i Docker | Raport w `gitIgnoreOwn/runtime/zap` |

Wymagane przygotowanie dla testów systemowych:

```bash
bash scripts/control-center/control-center.sh environment-check
bash scripts/control-center/control-center.sh secrets-validate dev
bash scripts/control-center/control-center.sh profile-set dev
bash scripts/control-center/control-center.sh infra-up
bash scripts/control-center/control-center.sh broker-up
bash scripts/control-center/control-center.sh mailpit-up
bash scripts/control-center/control-center.sh app-run-all
bash scripts/control-center/control-center.sh health all
```

Jeżeli aktywny jest profil `test`, zamień `dev` na `test` i ponów walidację.
Komplet sekretów musi zawierać również `CHAT_DB_PASSWORD`,
`RETURNS_DB_PASSWORD` i `BILLING_DB_PASSWORD`; pełna walidacja profilu oraz
standardowe uruchomienie infrastruktury wymagają wszystkich trzech wartości.

### 9.2. Testy z poziomu Centrali

Najczęściej używane polecenia:

```bash
bash scripts/control-center/control-center.sh app-test all
bash scripts/control-center/control-center.sh app-package all
bash scripts/control-center/control-center.sh smoke
bash scripts/control-center/control-center.sh app-verify
bash scripts/control-center/control-center.sh e2e
bash scripts/control-center/control-center.sh contract-test
bash scripts/control-center/control-center.sh security-test
bash scripts/control-center/control-center.sh load-test
bash scripts/control-center/control-center.sh resilience-test
bash scripts/control-center/control-center.sh zap-dast
bash scripts/control-center/control-center.sh ui-test-status
bash scripts/control-center/control-center.sh ui-test-smoke
bash scripts/control-center/control-center.sh ui-test
bash scripts/control-center/control-center.sh ui-test-system
bash scripts/control-center/control-center.sh resilience-status
bash scripts/control-center/control-center.sh api-docs-urls
```

Te same operacje są dostępne w menu interaktywnym:

- `Menu główne → 2. Projekty i aplikacje → 2. Testy i jakość API → 1` — test jednego serwisu,
- `→ 2` — test całego monorepo,
- `→ 3` — smoke przez API Gateway,
- `→ 4` — App Verify,
- `→ 5` — contract test,
- `→ 6` — security test,
- `→ 7` — load test,
- `→ 8` — pełny E2E,
- `→ 11` — status Node.js, zależności i raportu Playwright,
- `→ 12` — instalacja zależności oraz Chromium,
- `→ 13` — szybki Playwright Smoke,
- `→ 14` — pełna regresja UI w Chromium,
- `→ 15` — checkout przez prawdziwe mikroserwisy,
- `→ 16` — regresja Chromium/Firefox/WebKit/mobile,
- `→ 17` — test z widoczną przeglądarką,
- `→ 18` — inspektor i debug Playwright,
- `→ 19` — raport HTML pod `http://127.0.0.1:19325`,
- `Menu główne → 2. Projekty i aplikacje → 1. Uruchamianie i status → 6` — health wszystkich aplikacji,
- `Menu główne → 4. Profile i Mailpit → 8` — Mailpit smoke,
- `Menu główne → 4. Profile i Mailpit → 24` — OWASP ZAP/DAST.

### 9.2.1. Test odporności na awarię mikroserwisu (Chaos / Resilience Test)

**Co robi i co sprawdza test:**
- Symuluje awarię pojedynczego mikroserwisu (domyślnie `payment-service`).
- **Izolacja awarii (Fault Isolation)**: Weryfikuje, że wyłączenie serwisu płatności **nie wpływa** na pozostałe działające usługi (katalog produktów `GET /api/products` i API Gateway odpowiadają bez przeszkód).
- **Kontrolowana degradacja (Graceful Degradation)**: Upewnia się, że próba wywołania wyłączonego serwisu zwraca kontrolowany błąd (HTTP 503 Service Unavailable / 500) zamiast zablokowania lub unieruchomienia całego systemu.
- **Automatyczne przywracanie**: Po wykonaniu pomiarów test samoczynnie włącza i przywraca sprawność zatrzymanego serwisu.

**Przygotowanie i uruchomienie krok po kroku:**
1. **Status aplikacji przed testem**:
   - **NIE trzeba ręcznie wyłączać aplikacji ani restartować środowiska przed uruchomieniem testu.**
   - Test jest przeznaczony do uruchomienia na **działającym środowisku w profilu `test`** (lub `dev`). Test sam wykryje uruchomione procesy/kontenery i sam obsłuży symulację awarii oraz automatyczne przywrócenie.
2. **Krok 1 — Ustawienie profilu `test`**:
   - W przeglądarkowym Control Center (`http://127.0.0.1:18100`): kliknij menu w prawym górnym rogu lub w zakładce *Profile i sekrety*, wybierz profil **`test`** i zatwierdź.
   - W terminalu: `bash scripts/control-center/control-center.sh profile-set test`.
3. **Krok 2 — Uruchomienie aplikacji (jeśli nie działają)**:
   - W Control Center: uruchom infrastrukturę i mikroserwisy (`Uruchom aplikacje`).
   - W terminalu: `bash scripts/control-center/control-center.sh app-run-all`.
4. **Krok 3 — Uruchomienie testu odporności**:
   - W przeglądarkowym Control Center: Przejdź do zakładki **`Testy`** i kliknij **`Uruchom test`** na kafelku **Chaos / Test odporności**.
   - W terminalu: `bash scripts/control-center/control-center.sh resilience-test` (lub z podaniem nazwy serwisu, np. `resilience-test payment-service`).

### 9.3. Smoke a App Verify

`smoke` jest szybkim testem dostępności krytycznego routingu. Wysyła żądanie
`GET /api/products` do Gateway, który przekazuje je do Product Service. Sukces
oznacza, że punkt wejścia i katalog odpowiadają; nie oznacza poprawności
płatności, magazynu ani e-maili.

`app-verify` jest pełnym testem MVP. Najpierw sprawdza zdrowie usług, potem
pobiera metody dostawy, tworzy zamówienie, uruchamia symulowaną płatność,
potwierdza rezerwację, sprawdza zmianę ilości magazynowej, zapis dostawy,
Notification Service i Mailpit. Test jest celowo mutujący:

```bash
APP_VERIFY_PRODUCT_ID=4 APP_VERIFY_QUANTITY=1 \
  bash scripts/control-center/control-center.sh app-verify
```

Po testach demonstracyjnych możesz wykonać `demo-reset` i wpisać `RESET`.

### 9.4. Pełny E2E — dokładny przebieg

```bash
bash scripts/control-center/control-center.sh e2e
```

E2E generuje unikalne konto `e2e-...@minitech.local`, rejestruje je przez
Gateway, odczytuje link potwierdzający z Mailpit, potwierdza konto, wykonuje
login JWT, `/customers/me`, refresh i logout, sprawdza rekomendacje, a następnie
wywołuje App Verify. Hasło i tokeny są ukryte, pliki tymczasowe są usuwane.
Zostają natomiast dane zamówienia i wiadomości w lokalnym środowisku — po
serii testów użyj `demo-reset`.

### 9.5. Playwright — obsługa z Centrali

Playwright jest frameworkiem testów przeglądarkowych projektu. Cypress nie
jest instalowany, ponieważ pokrywałby ten sam zakres drugim zestawem fixture’ów.
Regresja korzysta ze statycznego serwera `scripts/qa/frontend-server.mjs` i
mockuje żądania `/api/**`, dlatego do zwykłego `ui-test` nie trzeba uruchamiać
Dockera, baz, sekretów ani mikroserwisów.

1. Wejdź do `Projekty i aplikacje → Testy i jakość API`.
2. Wybierz `11`, aby sprawdzić Node.js, menedżer pakietów, zależności i liczbę
   plików spec.
3. Przy pierwszym użyciu wybierz `12`. Centrala instaluje pakiety z
   `package.json` i Chromium.
4. Wybierz `13` dla szybkiego smoke albo `14` dla pełnej regresji Chromium.
5. Po teście wybierz `19`, a następnie otwórz
   `http://127.0.0.1:19325`; serwer raportu kończy się przez `Ctrl+C`.

Wymagany jest Node.js 20 lub nowszy; zalecany jest Node.js 22 LTS. Jeżeli WSL
ma Node.js 18, pozycje `11` i `12` pokażą czytelny komunikat i nie rozpoczną
instalacji. Aktualizację runtime wykonuje się jednorazowo poza Centralą, po czym
cała dalsza obsługa testów odbywa się z menu.

Pozycja `15` nie używa mocków. Przed jej uruchomieniem wykonaj:

```bash
bash scripts/control-center/control-center.sh secrets-validate dev
bash scripts/control-center/control-center.sh infra-up
bash scripts/control-center/control-center.sh broker-up
bash scripts/control-center/control-center.sh mailpit-up
bash scripts/control-center/control-center.sh app-run-all
bash scripts/control-center/control-center.sh frontend-up
bash scripts/control-center/control-center.sh health all
```

Test systemowy otwiera katalog przez frontend, pobiera produkt przez Gateway,
sprawdza Inventory, przechodzi koszyk i checkout gościa, uruchamia Order oraz
Payment i czeka na potwierdzenie w Mailpit. Tworzy dane lokalne. Pozycja `16`
pobiera również Firefox i WebKit i uruchamia 24 testy w czterech projektach.
Pozycje `17` i `18` wymagają działającego WSLg/GUI. Artefakty są w
`gitIgnoreOwn/runtime/playwright/{report,test-results}`.

### 9.6. Uruchomienie bez menu, bezpośrednio przez Maven/skrypty

Testy można uruchamiać także z IntelliJ IDEA. W oknie **Maven** wybierz projekt
główny i `Lifecycle → test`, albo uruchom klasę z `src/test/java` przyciskiem
**Run**. Testy integracyjne uruchamiaj przez profil Maven `integration` i cel
`verify`; samo uruchomienie klasy `*IT` jako JUnit może pominąć konfigurację
profilu oraz Testcontainers.

```bash
./mvnw test
./mvnw -Pintegration verify
bash scripts/qa/contract-test.sh
bash scripts/qa/security-test.sh
LOAD_REQUESTS=100 LOAD_CONCURRENCY=10 bash scripts/qa/load-test.sh
bash scripts/qa/e2e.sh
bash scripts/qa/zap-dast.sh
pnpm run test:ui:smoke
pnpm run test:ui
pnpm run test:ui:browsers
```

W CI pełny zestaw jest zebrany w `scripts/qa/ci-e2e.sh`; workflow znajduje się
w `.github/workflows/ci.yml`. Skrypt CI uruchamia izolowany Compose, testy
kontraktowe, bezpieczeństwa, ZAP, obciążeniowe, API E2E oraz pełny test
Playwright. Nie uruchamiaj go równolegle
z lokalnym środowiskiem używającym tych samych portów.

Jeżeli serwis nie odpowiada:

```bash
bash scripts/control-center/control-center.sh app-status-all
bash scripts/control-center/control-center.sh health all
bash scripts/control-center/control-center.sh app-logs <service>
bash scripts/control-center/control-center.sh doctor
```

`health all` sprawdza endpointy HTTP wszystkich usług. `smoke` sprawdza katalog
przez API Gateway. `app-verify` wykonuje scenariusz katalog → zamówienie →
rezerwacja → płatność → stan magazynowy → e-mail.

### 9.7. Ostatni wynik testów lokalnych

Przebieg z `2026-08-19`:

- Maven `test`: 49 testów, wszystkie zaliczone,
- Maven `-Pintegration verify`: 3 testy integracyjne, wszystkie zaliczone,
- smoke, contract, security, load `100/100`, delivery, promotion i Mailpit:
  zaliczone,
- App Verify i pełny E2E: zaliczone,
- regresja bezpieczeństwa API Gateway: 6/6 testów zaliczonych,
- ZAP: zaliczony. Raport zawiera sygnaturę `Spring Actuator Information Leak`,
  ale polityka DAST automatycznie potwierdziła, że dokładnie
  `/actuator/health` zwraca wyłącznie `status` i `groups`, bez `components` ani
  `details`. Każda bogatsza odpowiedź nadal kończy test błędem. Raporty są w
  `gitIgnoreOwn/runtime/zap/`.
- Playwright Chromium (`2026-08-20`): 24/24 scenariusze regresji z mockowanym
  API zaliczone. Scenariusz przez prawdziwe serwisy pozostaje osobnym
  poleceniem `ui-test-system`.

## 10. Operacje domenowe z terminala

```bash
# JWT, audyt i reset hasła
bash scripts/control-center/control-center.sh jwt-status
bash scripts/control-center/control-center.sh jwt-smoke
bash scripts/control-center/control-center.sh jwt-rotate dev
bash scripts/control-center/control-center.sh audit-list 50
bash scripts/control-center/control-center.sh password-reset-request user@example.test

# Dostawy
bash scripts/control-center/control-center.sh delivery-smoke
bash scripts/control-center/control-center.sh delivery-tracking 1
bash scripts/control-center/control-center.sh delivery-tracking-status 1 DELIVERED
# Odbiór osobisty: READY_FOR_PICKUP -> PICKED_UP
bash scripts/control-center/control-center.sh delivery-tracking-status 1 READY_FOR_PICKUP
bash scripts/control-center/control-center.sh delivery-tracking-status 1 PICKED_UP

# Promocje i opinie
bash scripts/control-center/control-center.sh promotion-smoke
bash scripts/control-center/control-center.sh promotion-codes
bash scripts/control-center/control-center.sh promotion-code-create
bash scripts/control-center/control-center.sh review-pending
bash scripts/control-center/control-center.sh review-moderate 1 PUBLISHED

# Czat
bash scripts/control-center/control-center.sh chat-list 50
bash scripts/control-center/control-center.sh chat-reply 1 "Dziękujemy za kontakt."

# Zwroty i reklamacje
bash scripts/control-center/control-center.sh returns-list
bash scripts/control-center/control-center.sh returns-status 1 UNDER_REVIEW "Weryfikacja zgłoszenia"

# Faktury
bash scripts/control-center/control-center.sh orders-list 100
bash scripts/control-center/control-center.sh invoice-show 1
bash scripts/control-center/control-center.sh invoice-pdf 1
```

`orders-list` pobiera dane przez API Gateway i pokazuje ID, status, kwotę,
odbiorcę, pozycje z ilościami oraz flagę zamówienia faktury. `invoice-show`
wyświetla dane faktury, a `invoice-pdf` zapisuje plik w
`gitIgnoreOwn/runtime/invoices/`. Po nowym opłaconym zamówieniu wiadomość w
Mailpit powinna zawierać ten sam PDF jako załącznik. Stare zamówienia utworzone
przed migracją pola `invoiceRequested` mogą mieć wartość `NIE`, jeśli nie da się
już odtworzyć historycznej decyzji klienta z danych Order Service.

Odpowiedź czatu musi przejść przez Chat Service. Ręczne napisanie wiadomości w
Mailpit nie zapisze jej w rozmowie ani nie pokaże jej użytkownikowi.

Wybór dnia dostawy lub odbioru odbywa się w sklepie, w formularzu finalizacji.
Control Center nie ustala terminów ręcznie: `delivery-smoke` pokazuje wycenę i
listę terminów zwracaną przez Delivery Service. Centrala służy do późniejszego
sprawdzenia numeru przesyłki oraz demonstracyjnej zmiany jej statusu. Przy
odbiorze osobistym należy używać statusów `READY_FOR_PICKUP` i `PICKED_UP`;
przy dostawie domowej `LABEL_CREATED`, `IN_TRANSIT`, `OUT_FOR_DELIVERY` i
`DELIVERED`. Gotówka przy dostawie/odbiorze pozostaje `PENDING` do czasu
obsługi płatności, więc nie tworzy jeszcze numeru przesyłki.

## 11. Backupy PostgreSQL

Przygotuj klucz age do szyfrowania backupów:

```bash
bash scripts/control-center/control-center.sh backup-key-init all
```

Backup ręczny:

```bash
bash scripts/control-center/control-center.sh db-backup all
bash scripts/control-center/control-center.sh db-backup product
bash scripts/control-center/control-center.sh db-backup-list all
```

Test odtworzenia i retencja:

```bash
bash scripts/control-center/control-center.sh db-restore-test product
bash scripts/control-center/control-center.sh db-backup-prune 30
```

Harmonogram WSL/Linux:

```bash
bash scripts/control-center/control-center.sh backup-schedule-install 2
bash scripts/control-center/control-center.sh backup-schedule-status
bash scripts/control-center/control-center.sh backup-schedule-remove
```

Backupy są lokalne i szyfrowane. Przed publikacją projektu sprawdź, czy katalog
backupów nie znajduje się w obszarze śledzonym przez Git.

## 12. Integracja z GitHub

Pełna instrukcja pierwszego commita, konfiguracji `origin`, pracy na gałęziach,
Pull Requestów, konfliktów i bezpiecznego cofania zmian znajduje się w
[Git_Instruction.md](Git_Instruction.md).

Przed `git add` uruchom:

```bash
bash scripts/control-center/control-center.sh github-check
```

Kontrola sprawdza m.in. pliki `.env`, sekrety SOPS, klucze age/JWT, prywatne
certyfikaty, kubeconfig, backupy i logi. Dopiero po pozytywnym wyniku można
wykonać przegląd zmian i commit.

## 13. Profil prod — tylko przygotowanie

Nie uruchamiaj:

```bash
bash scripts/control-center/control-center.sh profile-set prod
bash scripts/control-center/control-center.sh app-run-all
```

Profil produkcyjny wymaga docelowego klastra Kubernetes, registry obrazów,
OpenBao, TLS, zewnętrznej poczty, rotacji kluczy i osobnej infrastruktury.
Obecnie można tylko wykonać:

```bash
bash scripts/control-center/control-center.sh prod-status
bash scripts/control-center/control-center.sh k8s-validate
bash scripts/control-center/control-center.sh openbao-validate
bash scripts/control-center/control-center.sh openbao-status
bash scripts/control-center/control-center.sh tls-status example.test
bash scripts/control-center/control-center.sh k8s-status
```

## 14. Szybka procedura codzienna

```bash
cd "/mnt/c/Dysk D/stuff/Mini Order Platform"
bash scripts/control-center/control-center.sh secrets-validate dev
bash scripts/control-center/control-center.sh profile-set dev
bash scripts/control-center/control-center.sh infra-up
bash scripts/control-center/control-center.sh broker-up
bash scripts/control-center/control-center.sh mailpit-up
bash scripts/control-center/control-center.sh app-run-all
bash scripts/control-center/control-center.sh frontend-up
bash scripts/control-center/control-center.sh health all
```

Po pracy:

```bash
bash scripts/control-center/control-center.sh frontend-down
bash scripts/control-center/control-center.sh app-stop-all
bash scripts/control-center/control-center.sh infra-down
```

Jeśli chcesz zatrzymać cały główny Compose razem z Redis i Mailpit, użyj
`db-down-all` zamiast `infra-down`.
