# A43.5 — retencja treści opublikowanych zdarzeń faktur

Data: 27.10.2026. Pakiet 62 audytu, branch `Audit_0.1`.

<!-- A43_5_STATUS -->
Mechanizm wykonany i zweryfikowany lokalnie 27.10.2026. Domyślnie obserwacja;
docelowe okno przechowywania treści i aktywacja compact pozostają otwarte.
<!-- /A43_5_STATUS -->

## Zakres

Billing Service otrzymał moduł kompaktowania `invoice_outbox_events`.
Wpis pozostaje w bazie z tym samym ID, unikalnym `event_id`, statusem PUBLISHED,
czasem publikacji, próbami i pozostałymi metadanymi. Usuwana jest wyłącznie
treść JSON `payload`; `payload_compacted_at` zapisuje czas tej operacji.
Pozostawiony wpis nadal blokuje ponowne użycie identyfikatora zdarzenia.

Dotyczy to treści technicznej kopii zdarzenia po publikacji. Faktury, pozycje,
korekty, PDF, outbox płatności, inboxy i dane Redis pozostają poza zakresem.
Ich trwałe znaczniki deduplikacji są potrzebne do ochrony przed powtórzeniem
operacji. Ten etap nie usuwa całych wpisów outbox ani inbox.

## Tryby i warunki

| Tryb | Zachowanie |
| --- | --- |
| `observe` — domyślny | Odczytuje najstarszego kandydata i publikuje metryki; nie modyfikuje treści |
| `compact` | Czyści treść kwalifikujących się wpisów partiami i odczytuje stan |
| `off` | Nie wykonuje zapytań retencji |

**Próg 30 dni jest początkowym ustawieniem obserwacji.** Użytkownik ustala
docelowe okno przechowywania treści na potrzeby diagnostyki/odtwarzania
i jawnie aktywuje `compact`. Ogólne polecenie kolejnego etapu nie włącza go.
Zmiana konfiguracji wymaga jej zastosowania do procesu Billing.

Wszystkie warunki kwalifikacji muszą być spełnione jednocześnie:

- status `PUBLISHED` i niepusty czas publikacji;
- obecny payload i brak poprzedniego znacznika kompaktowania;
- oba pola dzierżawy `processing_token` i `processing_until` są puste;
- późniejszy z czasów utworzenia i publikacji jest starszy niż granica retencji.

Równość granicy, PENDING/FAILED, niedawna publikacja, przyszła data oraz
jakakolwiek pozostałość dzierżawy są chronione. Worker nie zgaduje, czy
niepełny lub niespójny wpis można już skrócić. W trybie obserwacji nie zmienia
rekordów ani nie pobiera ich JSON do pamięci.

## Ochrona ponowień i migracja

V10 dodaje `payload_compacted_at`, dopuszcza NULL w `payload` i wprowadza
CHECK cyklu życia: pełny payload bez znacznika albo brak payloadu ze
znacznikiem i statusem PUBLISHED. Nie można ustawić skróconego wpisu jako
PENDING/FAILED ani pozostawić jednocześnie payloadu i znacznika.
Unikalność `event_id` nadal obowiązuje, także po kompaktowaniu.

`InvoiceOutboxEvent.fields()` odrzuca próbę odczytania skróconej treści zamiast
zwracać pusty komunikat. Istniejący `InvoiceOutboxStateStore.claim()` odmawia
ponownego przejęcia PUBLISHED, a `complete()` sprawdza token aktualnej
dzierżawy. Spóźnione zakończenie starego zadania nie zapisuje jego dawnego
payloadu do skróconego rekordu. Przejścia publikacji i model ticketów zachowano.

Migracja dodaje częściowy indeks `(GREATEST(published_at, created_at), id)`
obejmujący wyłącznie możliwych kandydatów. Sama migracja nie czyści treści.
Wdrożyć V10 i kod Billing razem; przed ewentualnym włączeniem `compact`
zaktualizować wszystkie instancje Billing.

Kompaktowanie nie jest archiwizacją. Po nim SQL nie ma już oryginalnej treści
tego zdarzenia do diagnostyki lub ręcznej rekonstrukcji brokera. Okno trzeba
dobrać przed aktywacją; zasady odtwarzania i kopii pozostają częścią A36/A43.
Identyfikatory i metadane są zachowane, ale liczba wierszy nadal może rosnąć.

## Budżet i metryki

Partia ma własną transakcję REQUIRES_NEW. Kandydaci są uporządkowani po czasie
i ID, blokowani przez `FOR UPDATE SKIP LOCKED`, a warunki są powtórzone przy
UPDATE. CTE `MATERIALIZED` ustala listę dokładnie raz w partii; plan wykonania
nie może dobierać kolejnych kandydatów podczas aktualizacji. Zajęty rekord
nie blokuje innych instancji i pozostaje widoczny jako
zaległość. Nie ma wywołań brokera/HTTP w transakcji ani pełnego COUNT tabeli.
JDBC ma limit dwóch sekund poza transakcją; w transakcji Spring stosuje
pozostały czas jej pięciosekundowego limitu.

Domyślnie do 500 aktualizacji w partii i cztery partie w przebiegu — 2000
skróconych wpisów. Krótka/zerowa partia kończy przebieg, a następny wraca do
zaległości. Błąd wycofuje własną partię i zachowuje licznik wcześniejszych
commitów. Ostrzeżenie podaje klasę wyjątku, bez JSON i parametrów zdarzenia.

Istniejący endpoint Prometheus Billing publikuje dziesięć metryk Micrometer:

| Nazwa Prometheus | Znaczenie |
| --- | --- |
| `billing_outbox_retention_enabled` | 1 dla observe/compact, 0 dla off |
| `billing_outbox_retention_compaction_enabled` | 1 wyłącznie dla compact |
| `billing_outbox_retention_age_seconds` | Skonfigurowany próg wieku |
| `billing_outbox_retention_compacted_total` | Zatwierdzone skrócenia wpisów |
| `billing_outbox_retention_runs_total{outcome}` | Sukces/błąd przebiegu |
| `billing_outbox_retention_pending` | Czy istnieją kwalifikujący się kandydaci |
| `billing_outbox_retention_oldest_eligible_age_seconds` | Wiek najstarszego kandydata w migawce |
| `billing_outbox_retention_last_success_timestamp_seconds` | Czas ostatniej pełnej migawki |
| `billing_outbox_retention_last_compacted` | Skrócenia ostatniego pełnego przebiegu; 0 w obserwacji |

Migawka nieznana ma `-1`; błąd zachowuje poprzednią i jej czas. Zajęty wpis
nadal może dawać `pending=1`. W obserwacji jest to informacja o kandydatach,
nie o aktywnym czyszczeniu. Jedynymi etykietami są dwa stałe wyniki — bez
identyfikatorów zdarzeń, dokumentów i klientów. Scrape nie odpytuje bazy.

## Konfiguracja

| Zmienna | Domyślnie |
| --- | --- |
| `BILLING_OUTBOX_RETENTION_MODE` | `observe` |
| `BILLING_OUTBOX_RETENTION_AGE` | `30d` |
| `BILLING_OUTBOX_RETENTION_BATCH_SIZE` | `500` |
| `BILLING_OUTBOX_RETENTION_MAX_BATCHES` | `4` |
| `BILLING_OUTBOX_RETENTION_INTERVAL_MS` | `600000` |
| `BILLING_OUTBOX_RETENTION_INITIAL_DELAY_MS` | `60000` |

Parametry niesekretne są w aplikacji, Compose i ConfigMap. Walidacja wymaga
trybu off/observe/compact, okresu minimum jeden dzień, partii 1–1000, liczby
partii 1–20, odstępu minimum 1000 ms i opóźnienia minimum 0. Błąd zatrzymuje
start. Pierwszy przebieg po minucie, kolejne dziesięć minut od końca
poprzedniego. Profil test wyłącza automatyczny moduł, w tym zapytania H2.

## Odbiór

<!-- A43_5_RESULTS -->
- **157 testów jednostkowych Java** (108 infrastruktury + 49 Billing) i
  **7 testów PostgreSQL** — **164 łącznie, PASS**, bez pominiętych. Nowych
  przypadków: 17 (dziesięć jednostkowych i siedem PostgreSQL).
- Odbiór sprawdza wszystkie warunki kwalifikacji, obie granice czasu,
  niepełne daty, pozostałości dzierżaw, zachowanie metadanych i unikalności
  `event_id`, blokadę ponownego zakolejkowania oraz jawne odrzucenie odczytu
  skróconego JSON. Fixture potwierdza usunięcie treści ponad 5000 bajtów
  przy zachowaniu pozostałych pól i samego wpisu.
- Rzeczywisty StateStore potwierdził działanie ponowień FAILED i ochronę
  PUBLISHED: stary ticket nie przywraca payloadu po kompaktowaniu.
  Testy obejmują blokady, równoległe partie, budżet, rollback późniejszej
  partii, zachowanie liczników commitów i odzyskanie po błędzie.
- Pierwszy odbiór wykrył przekraczanie limitu przez ponawiany podwybór
  kandydatów UPDATE. `MATERIALIZED` usunęło problem; ponowny pełny odbiór
  i wszystkie siedem przypadków PostgreSQL przeszły.
- **12 testów Kubernetes**, spójność sześciu parametrów w trzech formatach,
  parent 17 modułów oraz korelacja/odporność 15 JAR-ów — PASS.
- V10 i nowy moduł są w pakiecie Billing. Zależności testowe nie weszły do
  wykonywalnego JAR-a. Diff — PASS. UI się nie zmienił, bez ponawiania przeglądarek.

Raport: `tmp/audit-a43-5/report.json`. Końcowa regresja:
`tmp/a43-5-maven-verify-final.log`. Pierwszy odbiór zachowano w
`tmp/a43-5-maven-verify.log`.
<!-- /A43_5_RESULTS -->

```text
./mvnw -pl billing-service -am -Pintegration verify
```

Billing ma profil Failsafe oraz testowe zależności JPA/Testcontainers zgodne
z parentem. Nie dodano zależności runtime. Istniejące CI `-Pintegration verify`
obejmuje nowe przypadki; nie uruchamiano zdalnego CI.

Testy PostgreSQL używają własnego kontenera, fixture JSON i triggera odrzucającego
UPDATE. Nie publikują do rzeczywistego brokera i nie dotykają prawdziwych faktur.
Odbiór obejmuje rzeczywisty StateStore, unikalność zdarzeń, constraint cyklu
życia, blokady, współbieżne partie i odzyskanie po częściowej awarii.

Nie wdrażano na dev/prod i nie aktywowano kompaktowania prawdziwych danych.
Nie dodano widoku CC ani zmian API faktur. Całe A43 pozostaje otwarte dla
docelowych polityk/aktywacji, dalszej retencji zdarzeń i logów oraz procedur
eksportu/usuwania/anonimizacji obejmujących usługi i kopie. A05, całe A41
i zmiany samych zdjęć pozostają odłożone.
