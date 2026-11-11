# A33 — bramki jakości i dowody regresji

**Aktualizacja 02.11.2026 — pakiet 153:** [regresje i kontrola wyników CI](A33_REGRESJE_ODZYSKIWANIA_I_BRAMKA_CI_153.md).
A33: regresje odzyskiwania checkoutu i zmiany kont, bramka CI wymagająca 28 pierwszych pozytywnych wyników, ochrona serwera QA. Naprawiono kasowanie koszyka gościa na stronie produktu oraz lokalne wygaszanie sesji przed odpowiedzią logout.
145 kombinacji test/przeglądarka + 25 metod Python + 16 testów Node PASS; krytyczna macierz 28/28, bez skip/flaky/retry. 13/14 P1 wykonanych; A05 pozostaje odłożone.
Następne niezależne prace: A35 — przegląd pokrycia korelacji HTTP/Redis i readiness; wdrażać niezależne braki techniczne, odkładając wybór SLO i zewnętrznych odbiorników. Poniżej historia pierwotnych bramek.

Pakiet z 25.10.2026 na `Audit_0.1` wzmacnia testy i CI. Nie zmienia zdjęć,
modelu danych ani schematu bazy. **Status A33 pozostaje częściowy**: bramki są
wdrożone i zweryfikowane, lecz pełny warunek odbioru wymaga regresji wszystkich P1.
Niedokończone A05 jest jawnie wykazane jako zależność.
A08 zakończono w późniejszym [pakiecie 17](A08_PLATNOSCI_REPLIKI.md).
A09 zakończono w [pakiecie 18](A09_TRWALE_SKUTKI_PLATNOSCI.md).
A10 zakończono w [pakiecie 19](A10_POBRANIE_I_REALIZACJA.md).
A11 wdrożono w zalecanym wariancie blokady MVP w [pakiecie 20](A11_BEZPIECZNE_ANULOWANIE.md).
A06 zakończono w [pakiecie 21](A06_SPOJNOSC_ZAMOWIEN.md).
A21 zakończono w [pakiecie 22](A21_PRZYJECIA_MAGAZYNOWE.md).
Nie oznaczono całego A33 jako zakończonego. Refundacja pozostaje poza wdrożonym MVP.

## Wykonane bramki

Wspólny `scripts/qa/quality_gates.py` obsługuje kontrakt, bezpieczeństwo,
obciążenie i fazy chaos. Dotychczasowe polecenia Bash/CC korzystają z tego silnika.
Naruszenie asercji kończy proces kodem niezerowym i zapisuje raport FAILED.
Raport zawiera status, czas, wyniki i limity; nie zapisuje tokenów, nagłówków ani
odpowiedzi zawierających dane klientów. HTTP nie śledzi przekierowań i ogranicza
cały czas odpowiedzi wraz z powolną treścią, a także jej rozmiar do 8 MiB.

### Kontrakty i bezpieczeństwo

`contracts/api-gateway-v1.json` definiuje:

- routing wszystkich **14 usług**, w tym Chat/Returns/Billing;
- wymagane operacje OpenAPI, odpowiedzi 200, schematy odpowiedzi oraz obowiązkowe
  schematy body wybranych zapisów;
- typy istotnych pól DTO, m.in. cen, kwot, identyfikatorów i statusów;
- niezależne JSON Schema rzeczywistych odpowiedzi katalogu, magazynu, dostaw,
  publicznych opinii oraz sześciu list operatora;
- statusy 400/404 i nagłówki paginacji. Publiczne opinie mają zamknięty schemat,
  który odrzuca powrót pól administracyjnych.

Bramka bezpieczeństwa sprawdza anonimowe granice list i zapisu katalogu,
nieprawidłowy JWT, pozytywną kontrolę operatora, diagnostykę health bez szczegółów,
brak `/actuator/env` i prywatnych identyfikatorów sekretów w OpenAPI. Oddzielny,
istniejący rzeczywisty E2E obejmuje właściciela danych, role klientów/operatora/usług,
zakupy, dokumenty i czat — sam odczyt OpenAPI nie zastępuje tych testów.

Walidacja korzysta z JSON Schema Draft 2020-12 i przypiętych zależności Python
w `scripts/qa/requirements.txt`. Schematy QA są lokalne, bez automatycznego
pobierania zewnętrznych `$ref`. [API walidatora](https://python-jsonschema.readthedocs.io/en/stable/validate/)
opisuje użyty `Draft202012Validator`. Nie jest to pełna certyfikacja wszystkich
operacji biznesowych ani dowód zamknięcia otwartych ustaleń.

### Obciążenie

Test wykonuje pięć żądań rozgrzewki i ruch zamkniętej pętli o zadanej liczbie
klientów. Wymaga HTTP 200 i poprawnego schematu treści. Błąd transportu, inny status
lub błędne JSON pod HTTP 200 liczą się jako błąd. Percentyle używają zmierzonych
czasów całych odpowiedzi i metody nearest-rank.

| Zmienna | Domyślnie | Znaczenie |
| --- | ---: | --- |
| `LOAD_REQUESTS` | 100 | Liczba żądań, 1–10000 |
| `LOAD_CONCURRENCY` | 10 | Współbieżność, maks. 64 i nie więcej niż liczba żądań |
| `LOAD_P95_MS` | 1500 | Maks. p95 |
| `LOAD_P99_MS` | 3000 | Maks. p99 |
| `LOAD_MAX_ERROR_RATE` | 0 | Maks. udział błędów, liczba 0–1 |
| `LOAD_MAX_DB_PENDING` | 10 | Maks. zaobserwowana kolejka Hikari |
| `LOAD_METRICS_URL` | `http://127.0.0.1:18081` | Bezpośredni Product do metryk puli |
| `QA_REPORT_DIRECTORY` | `gitIgnoreOwn/runtime/qa` | Katalog raportów JSON |

Samodzielne `load-test` respektuje powyższe zmienne. Izolowany zestaw CI używa
jawnych, domyślnych budżetów.

Próbkowane są active/max/pending puli DB. Brak dostępu do metryk lub brak próbek
nie jest traktowany jako sukces. Raport pokazuje limity, p95/p99, błędy, RPS oraz
liczbę próbek i szczyty puli; metryki pozostają również w raporcie przekroczonych
progów. Są to budżety regresji środowiska testowego. Krótki test zamkniętej pętli
nie wyznacza przepustowości produkcyjnej ani produkcyjnego SLO; A35 pozostaje otwarte.

### Awaria i odzyskanie

`resilience-test.sh` uruchamia **jednorazową platformę** przez istniejący runner
E2E. Wymaga Docker, zbudowanych JAR-ów, Java 25 i zależności QA. Wybrany proces
pochodzi z listy własnych `Popen` tego runnera. Nie używa `pgrep`, dopasowania
nazwy kontenera, `eval` ani wstrzymywania istniejącej aplikacji dev.

Bramka wymaga kolejno:

1. HTTP 200 i poprawnego schematu biznesowego przed awarią;
2. rzeczywistego SIGSTOP wybranego, własnego procesu;
3. działającego niezależnego zasobu w czasie awarii, również ze sprawdzeniem treści;
4. dokładnie HTTP 503/JSON w limicie 18 s dla zależnej operacji;
5. SIGCONT w `finally` i HTTP 200 z poprawnym schematem w limicie odzyskania 45 s;
6. dalszego działania niezależnego zasobu po odzyskaniu.

400, 500, brak połączenia (dawne 000) i 200 w fazie symulowanej awarii oznaczają
błąd. Nieoczekiwany status lub nieudane odzyskanie nie kończy się ostrzeżeniem
ze statusem sukcesu. Sprzątanie wznawia własne procesy również po błędzie, następnie
je kończy i usuwa własne kontenery. Scenariusz obejmuje dodatkowo rzeczywisty
checkout/uprawnienia, więc trwa dłużej niż pojedynczy probe HTTP.

`GatewayRetryWireTest` używa rzeczywistego serwera HTTP, konfiguracji retry,
GatewayProxy i transportu aplikacji. Oczekuje trzech prób GET po dwóch 503,
dokładnie trzech prób po wyczerpaniu retry i jednej próby POST, który mógł być
już wykonany. Usunięcie wykonywania retry lub włączenie retry dla zapisów łamie
te asercje. Dotychczasowy test samej listy metod pozostaje uzupełnieniem.

## Uruchamianie

Przykład w WSL/Linux, z katalogu projektu:

```bash
python3 -m venv --system-site-packages gitIgnoreOwn/qa-venv
source gitIgnoreOwn/qa-venv/bin/activate
python3 -m pip install -r scripts/qa/requirements.txt

bash scripts/control-center/control-center.sh contract-test
bash scripts/control-center/control-center.sh security-test
LOAD_REQUESTS=100 LOAD_CONCURRENCY=10 bash scripts/control-center/control-center.sh load-test
bash scripts/control-center/control-center.sh resilience-test inventory-service
```

Pierwsze trzy polecenia sprawdzają działające dev/test. CC pobiera poświadczenie
operatora z profilu wyłącznie dla znanych lokalnych adresów. Niestandardowy adres
wymaga jawnie dostarczonego `QA_OPERATOR_TOKEN`; skrypt nie odczytuje wtedy
automatycznie tokenu z profilu dla obcego celu. Bezpośrednie skrypty QA wymagają
tej zmiennej środowiskowej. Token nie jest argumentem procesu ani polem raportu.
Chaos używa własnych danych/poświadczeń i nie potrzebuje sekretów aplikacji dev.
`QA_PORT_BASE` może zmienić domyślny zakres 36180 dla samodzielnego chaos.

Pełny odbiór w osobnym środowisku:

```bash
./mvnw package
python3 scripts/qa/access-control-e2e.py --pagination --quality-gates --chaos-service inventory-service --port-base 39180
CC_REQUIRE_SOPS_TESTS=1 python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
```

`LOAD_API_BASE_URL` przyjmuje origin Gateway lub końcówkę `/api/v1`;
`CONTRACT_API_BASE_URL` i `SECURITY_API_BASE_URL` wskazują origin Gateway.
Porty, bazy i kontenery testu izolowanego są jego własnością. Zajęty zakres portów
powoduje błąd przed uruchomieniem fixture. Nie przebudowywać JAR-ów w trakcie E2E.

## Dowody P1 i CI

`contracts/p1-evidence.json` łączy wszystkie 14 ustaleń P1 z bieżącym statusem,
źródłami testów, komendami CI i opisem brakującej regresji. `audit_readiness.py`
wykrywa usunięte źródło testu, brak komendy w wykonywanym kroku CI (komentarz
nie wystarcza), pominięty P1 albo zmianę statusu audytu bez aktualizacji dowodów.

```bash
python3 scripts/qa/audit_readiness.py
python3 scripts/qa/audit_readiness.py --release
```

Pierwsze sprawdzenie potwierdza spójność rejestru, jawnie raportując `p1Ready=false`.
Drugie obecnie **ma kończyć się błędem**: A05 pozostaje niedokończone.
Bieżący stan to 13/14 P1; pozostałe wcześniejsze pozycje zamknięto w opisanych pakietach.
To konieczna kontrola P1, a nie pełne potwierdzenie gotowości produkcji. A34,
backupy i pozostałe wymagania produkcyjne nadal mają własne statusy.

| P1 poza pełnym odbiorem | Brakujący zakres |
|---|---|
| A05 | Zewnętrzny odbiór Stripe, konfiguracja i wartości użytkownika; odłożone. |

A06, A11 w przyjętym MVP oraz A21 mają późniejsze odbiory i regresje
w rejestrze P1. Dalsze rozszerzenia domenowe nie są automatycznie zgodą
na wznowienie odłożonego A05 lub pełną gotowością produkcji.

Nie zamieniono tych scenariuszy na pominięte albo pozornie pozytywne testy.
CI wykonuje bramki w istniejącym izolowanym scenariuszu `--pagination`, testy
samych bramek i spójność dowodów, zachowuje raporty i ma limit 60 minut dla verify.
Przed ładowaniem konfiguracji klastra produkcyjny job wymaga zamkniętych P1.
To konfiguracja pipeline; w tym pakiecie nie uruchamiano GitHub Actions ani deploya.

## Wyniki odbioru pakietu

- **266 testów Java**, pełny build, bez błędów/pominięć; pięć nowych testów czatu
  i trzy testy retry HTTP. Czat sprawdza capability, brak zapisu/wysyłki bez tokenu,
  404 i utrwalenie informacji o błędzie dostarczenia; nie zamyka A38.
- **87 testów Python w WSL**, z wymaganym SOPS, bez błędów/pominięć. W tym 23 testy
  bramek na kontrolowanym HTTP oraz testy mapy P1 i przekazywania poświadczeń.
  Przypadki negatywne obejmują m.in. błędny schemat/status, obejście autoryzacji,
  przeciążenie puli, p95/p99, nieudane/spóźnione odzyskanie, powolną treść,
  zewnętrzne schema-ref i kod wyjścia CLI. To kontrolowane mutacje zachowania
  fixture, a nie deklaracja pełnego pomiaru mutacyjnego aplikacji.
- **192 kontrole rzeczywistych HTTP** i **148 kontroli paginacji** na PostgreSQL
  (po 100000 rekordów w 11 tabelach), a następnie cztery bramki na tej platformie.
  Kontrakt wykrył niekompletny rekord w starej fixturze paginacji; dodano jego
  pozycję zamówienia zamiast rozluźniać schemat poprawnej odpowiedzi.
- Obciążenie: **100/100 HTTP 200**, zero błędów, współbieżność 10,
  **p95 337,949 ms**, **p99 358,609 ms**, 11 próbek puli, szczyt pending 0.
- Awaria magazynu: **200 → 503 → 200**, odpowiedź przy awarii **15018,793 ms**,
  odzyskanie **0,111 s**, cztery kontrole izolacji. Są to lokalne pomiary testowe.

Raporty: `tmp/access-control-e2e-57121829/quality-gates/`.
Logi: `tmp/audit-a33-final-build.log`, `tmp/audit-a33-python-tests.log`,
`tmp/audit-a33-final-platform-gates.log`. Wszystkie zasoby jednorazowe zakończono.

Pozostały warunek A33 to pełne regresje po wdrożeniu otwartych P1. Po zakończeniu
A06/A08/A09/A10, zabezpieczenia MVP A11 i A21 pozostaje **A05 — rzeczywisty dostawca płatności i refundacji**.
