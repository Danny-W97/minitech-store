# A43.4 — retencja dziennika bezpieczeństwa

Data: 27.10.2026. Pakiet 61 audytu, branch `Audit_0.1`.

<!-- A43_4_STATUS -->
Mechanizm wykonany i zweryfikowany lokalnie 27.10.2026. Domyślnie obserwacja;
wybór docelowej polityki i aktywacja usuwania pozostają otwarte.
<!-- /A43_4_STATUS -->

## Zakres i tryby

Customer Service ma osobny moduł retencji tabeli `audit_events`. Nie korzysta
z siedmiodniowej polityki tokenów/MFA. Zapis audytu przez `AuditService`, jego
zawartość i polecenie CC `audit-list` pozostają dostępne.

| Tryb | Działanie |
| --- | --- |
| `observe` — domyślny | Odczyt najstarszego wpisu starszego niż skonfigurowany próg, publikacja wieku i zaległości; bez DELETE |
| `purge` | Usuwanie takich wpisów w małych transakcjach, następnie odczyt stanu |
| `off` | Brak odczytów i usunięć; metryki pokazują wyłączony moduł i nieznaną migawkę |

Próg 90 dni jest początkowym ustawieniem obserwacji, nie ustaloną polityką
przechowywania audytu. **Przed aktywacją `purge` użytkownik ustala okres
przechowywania dziennika i ustawia go jawnie.** Kod i migracja same nie
włączają usuwania. Powrót z `purge` do `observe` zatrzymuje dalsze czyszczenie
po zastosowaniu konfiguracji i zachowuje obserwację; nie odtwarza usuniętych wpisów.

Dotyczy to wyłącznie audytu bezpieczeństwa Customer, nie zdarzeń biznesowych,
outboxów, inboxów, dokumentów, płatności ani danych klientów. Ten audyt nie jest
wykorzystywany jako rejestr idempotencji procesów zakupowych. Etap nie dodaje
archiwum ani eksportu audytu; dalszy cykl życia kopii pozostaje częścią A43/A36.

## Reguły i limity

Kwalifikacja to `occurred_at < teraz_UTC - retention`. Równość granicy, świeże
i przyszłe wpisy są chronione. Jeden przebieg używa jednego czasu i granicy.
Nie filtruje po typie, aktorze ani wyniku; wybrany okres dotyczy całego dziennika.

Domyślnie partia obejmuje do 500 wpisów, przebieg do czterech partii, łącznie do
2000 usunięć. Każda partia ma własną transakcję `REQUIRES_NEW`. Wybór wierszy
jest uporządkowany po czasie i ID oraz używa `FOR UPDATE SKIP LOCKED`, dzięki
czemu instancje mogą pracować równolegle i omijać zajęte wpisy. Zapis nowego
audytu nie wymaga blokady konta ani zakończenia czyszczenia starych wierszy.

Krótka/zerowa partia kończy przebieg, ale osobny odczyt stanu nadal wykazuje
zajęte kwalifikujące się rekordy jako zaległość. Worker ponowi pracę w następnym
przebiegu. Błąd wycofuje swoją partię, a licznik zachowuje wcześniejsze
zatwierdzone usunięcia. Awarie bazy i transakcji są widoczne jako błędy.

JDBC ma limit dwóch sekund poza transakcją; wewnątrz niej Spring stosuje
pozostały czas pięciosekundowego limitu transakcji. Worker nie pobiera aktora,
metadanych, adresu IP ani treści audytu. Odczyt migawki pobiera jedynie datę
jednego najstarszego kwalifikującego się wpisu. Nie ma pełnego COUNT ani
odczytu bazy przy każdym pobraniu metryk Prometheus.

V15 zastępuje indeks samego `occurred_at` indeksem `(occurred_at, id)`.
Wiodąca kolumna nadal obsługuje sortowanie istniejącego `audit-list`. Zachowano
indeksy aktora, typu i korelacji; nie pozostawiono dwóch indeksów tego samego
prefiksu czasu. Sama migracja nie usuwa rekordów.

## Konfiguracja

Wartości niesekretne są spójne w aplikacji, Compose i ConfigMap Kubernetes.

| Zmienna | Domyślnie |
| --- | --- |
| `CUSTOMER_AUDIT_RETENTION_MODE` | `observe` |
| `CUSTOMER_AUDIT_RETENTION_AGE` | `90d` |
| `CUSTOMER_AUDIT_RETENTION_BATCH_SIZE` | `500` |
| `CUSTOMER_AUDIT_RETENTION_MAX_BATCHES` | `4` |
| `CUSTOMER_AUDIT_RETENTION_INTERVAL_MS` | `600000` |
| `CUSTOMER_AUDIT_RETENTION_INITIAL_DELAY_MS` | `60000` |

Pierwszy przebieg po minucie, następne dziesięć minut od końca poprzedniego.
Walidacja: tryb z powyższej listy, okres minimum jeden dzień, partia 1–1000,
liczba partii 1–20, odstęp minimum 1000 ms i opóźnienie minimum 0. Niepoprawne
ustawienie blokuje start. Profil `test` ma jawne `mode: "off"`, również w H2.
Testy integracyjne uruchamiają moduł ręcznie we własnej bazie PostgreSQL.
Nie trzeba dodawać żadnych sekretów do SOPS.

## Metryki

Istniejący endpoint Prometheus Customer publikuje dziesięć metryk Micrometer:

| Nazwa Prometheus | Znaczenie |
| --- | --- |
| `customer_audit_retention_enabled` | 1 dla `observe`/`purge`, 0 dla `off` |
| `customer_audit_retention_purge_enabled` | 1 wyłącznie dla `purge` |
| `customer_audit_retention_age_seconds` | Skonfigurowany próg wieku |
| `customer_audit_retention_deleted_total` | Zatwierdzone usunięcia |
| `customer_audit_retention_runs_total{outcome}` | Sukces/błąd przebiegu, także obserwacji |
| `customer_audit_retention_pending` | Czy istnieje wpis starszy niż próg |
| `customer_audit_retention_oldest_eligible_age_seconds` | Wiek najstarszego takiego wpisu w migawce |
| `customer_audit_retention_last_success_timestamp_seconds` | Czas ostatniej pełnej migawki |
| `customer_audit_retention_last_deleted` | Usunięcia w ostatnim pełnym przebiegu; 0 przy obserwacji |

`pending=1` w trybie obserwacji jest informacją o wieku danych, nie deklaracją
włączonego czyszczenia. Stan początkowy `-1` oznacza brak migawki. Błąd
zachowuje poprzednią migawkę i jej czas; licznik błędów rośnie. Etykiety mają
wyłącznie dwa stałe wyniki, bez danych klienta i zdarzenia. Ostrzeżenie zawiera
jedynie klasę wyjątku, bez SQL, treści i parametrów wpisu.

## Odbiór

<!-- A43_4_RESULTS -->
- **167 testów jednostkowych Java** (108 infrastruktury + 59 Customer) i
  **24 testy PostgreSQL** — **191 łącznie, PASS**, bez pominiętych. Nowych
  przypadków: 16 (dziesięć jednostkowych i sześć PostgreSQL).
- Sprawdzono tryby, domyślną obserwację, niezależny okres i błędną konfigurację,
  budżet, granicę czasu, kolejność ID, zachowanie wszystkich pól świeżych wpisów,
  row lock, współbieżne partie i opróżnienie zaległości.
- PostgreSQL potwierdził rollback całej odrzuconej partii, zachowanie licznika
  wcześniejszych commitów, odzyskanie po błędzie oraz rzeczywisty zapis audytu
  w czasie obserwacji i blokady starego wpisu. Dotychczasowe testy sesji,
  MFA, weryfikacji kont i retencji również przeszły.
- **12 testów Kubernetes** i zgodność sześciu parametrów w trzech formatach
  konfiguracji — PASS. Parent 17 modułów, 12 adapterów korelacji i struktura
  korelacji/odporności 15 JAR-ów — PASS.
- V15 i cztery klasy modułu są w pakiecie Customer. Kontrola diff — PASS.
  Nie powtarzano przeglądarek, ponieważ UI się nie zmienił.

Raport: `tmp/audit-a43-4/report.json`. Regresja:
`tmp/a43-4-maven-verify.log`.
<!-- /A43_4_RESULTS -->

```text
./mvnw -pl customer-service -am -Pintegration verify
```

Testy korzystają z własnych kontenerów PostgreSQL, wpisów fixture i testowych
triggerów odrzucających DELETE. Sprawdzają wycofanie całej nieudanej partii,
zachowanie liczników wcześniejszych commitów oraz rzeczywisty zapis przez
`AuditService` podczas obserwacji i blokady starego wiersza. Nowe testy obejmuje
istniejący profil integracyjny i krok CI; nie dodano zależności.

Odbiór jest lokalny: bez obrazu i wdrożenia dev/prod, czyszczenia prawdziwych
audytów lub zmian kluczy. Nie dodano nowego widoku CC; dostępne są konfiguracja,
metryki i dotychczasowe `audit-list`.

Całe A43 nadal otwarte. Pozostaje wybór docelowego okresu audytu i ewentualna
aktywacja `purge`, dalsza retencja telemetrii/zdarzeń oraz eksport, usuwanie
i anonimizacja obejmujące usługi i kopie. A05, całe A41 i zmiany samych zdjęć
nadal odłożone; ten etap ich nie wznawia.
