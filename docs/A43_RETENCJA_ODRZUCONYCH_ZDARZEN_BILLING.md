# A43.11 — retencja diagnostyki odrzuconych zdarzeń Billing

Data: 27.10.2026. Pakiet 68 audytu, branch `Audit_0.1`.

## Zakres

Billing Service otrzymał kompaktowanie diagnostycznej kopii odrzuconego
komunikatu w `event_inbox`. Mechanizm usuwa `payload` i `error`, zapisując
`diagnostics_compacted_at`. Opis błędu również może zawierać fragmenty wejścia.
Rekord zachowuje `event_id`, `payload_hash`, status i czas odbioru.

ID nadal blokuje ponowne przyjęcie tego samego wpisu streamu, a hash pozwala
wykryć użycie tego samego ID z inną treścią. RejectedEventStore rozpoznaje
istniejący receipt i nie odtwarza usuniętej diagnostyki przy redelivery.
PaymentEventInbox zachowuje porównanie hashy i klucz główny nadal chroni
przed ponownym wstawieniem ID. Dotychczasowa obsługa ACK/retry konsumenta
pozostaje bez zmian.

Przyjęte komunikaty i dokumenty zachowują dane. W Billing przyjęte zdarzenie
płatności jest obserwacją, a `related_id` przechowuje ID zamówienia; samo
zdarzenie nadal nie wystawia faktury. Nowa retencja nie wykonuje HTTP ani
publikacji zdarzeń. Nie usuwa rekordów inboxu, faktur, pozycji, korekt ani
ich outboxu. Oryginalna diagnostyka nie jest archiwizowana, a liczba trwałych
znaczników deduplikacji nadal rośnie.

## Tryby i kwalifikacja

| Tryb | Działanie |
| --- | --- |
| `observe` — domyślny | Pokazuje najstarszego kandydata i metryki; bez modyfikacji |
| `compact` | Czyści diagnostykę w ograniczonych partiach |
| `off` | Nie wykonuje zapytań retencji |

30 dni jest początkowym progiem obserwacji. Użytkownik wybiera docelowe okno
diagnostyki i osobno aktywuje compact. Ogólne polecenie kolejnego etapu nie
włącza czyszczenia. Konfigurację trzeba zastosować do procesu Billing.

Kwalifikują się wyłącznie wpisy spełniające wszystkie warunki:

- status REJECTED i puste `related_id`;
- brak znacznika wcześniejszego kompaktowania;
- istnieje payload lub opis błędu — wystarczy jedno pole;
- `received_at` jest ściśle starszy od granicy retencji.

ACCEPTED, inne statusy, powiązane wpisy, granica czasu oraz świeże/przyszłe
daty są chronione. Historyczny pusty wpis nie wymaga aktualizacji. Obserwacja
nie pobiera treści/błędów do pamięci i nie liczy całej tabeli.

Polityka jest niezależna od A43.5: `messaging.rejected-inbox-retention.*`
steruje diagnostyką odrzuceń, a `outbox.retention.*` treścią opublikowanych
zdarzeń faktur. Włączenie compact w jednym module nie aktywuje drugiego;
mają osobne okresy, budżety i metryki. Oba domyślnie tylko obserwują.

## Migracja, blokady i metryki

V11 dodaje znacznik, częściowy indeks `(received_at, event_id)` i CHECK:
oznaczony wpis musi mieć puste payload/error, status REJECTED i brak
powiązania. Baza odrzuca przywrócenie pojedynczego pola diagnostyki oraz
zmianę oznaczonego rekordu na przyjęty/powiązany. Wpisy bez znacznika
zachowują dopuszczalne wcześniej postacie. Sama migracja nie czyści danych.

V11 i kod należy wdrożyć razem, a przed aktywacją compact zaktualizować
wszystkie instancje Billing. Po kompaktowaniu treść i opis błędu nie będą
dostępne w tym inboxie do ręcznego odtwarzania. Pozostawione ID/hashe,
pozostałe bazy, Redis i kopie wymagają dalszego cyklu życia danych;
etap nie zamyka pełnej anonimizacji.

Każda partia ma osobną transakcję REQUIRES_NEW, limit 5 sekund i blokady
`FOR UPDATE SKIP LOCKED`. Materializowany CTE wyznacza kandydatów raz,
w kolejności czasu/ID. UPDATE ponownie sprawdza kwalifikację. Zajęty wpis
jest pomijany, ale może pozostawać widoczny jako zaległość. JDBC poza
transakcją ma limit 2 sekund; w transakcji stosuje jej pozostały czas.

Domyślnie 500 wpisów w partii i cztery partie w przebiegu, do 2000.
Krótka/zerowa partia kończy przebieg. Błąd wycofuje własną partię, zachowując
wcześniejsze commity i liczniki. Ostrzeżenie podaje tylko klasę wyjątku,
bez SQL, parametrów, treści i komunikatu błędu.

Istniejący endpoint Prometheus Billing publikuje:

| Metryka | Znaczenie |
| --- | --- |
| `billing_rejected_inbox_retention_enabled` | 1 dla observe/compact |
| `billing_rejected_inbox_retention_compaction_enabled` | 1 wyłącznie dla compact |
| `billing_rejected_inbox_retention_age_seconds` | Skonfigurowany próg wieku |
| `billing_rejected_inbox_retention_compacted_total` | Zatwierdzone skrócenia |
| `billing_rejected_inbox_retention_runs_total{outcome}` | Przebiegi success/failed |
| `billing_rejected_inbox_retention_pending` | Czy istnieje kwalifikujący się kandydat |
| `billing_rejected_inbox_retention_oldest_eligible_age_seconds` | Wiek najstarszego kandydata |
| `billing_rejected_inbox_retention_last_success_timestamp_seconds` | Czas ostatniej pełnej migawki |
| `billing_rejected_inbox_retention_last_compacted` | Skrócenia ostatniego pełnego przebiegu |

Nieznana migawka ma -1. Błąd zachowuje poprzednią i czas jej odczytu.
Jedynymi etykietami są dwa stałe wyniki, bez klientów/zdarzeń. Scrape nie
odpytuje bazy. Observe pokazuje kandydatów, a nie aktywne czyszczenie.

## Konfiguracja

| Zmienna | Domyślnie |
| --- | --- |
| `BILLING_REJECTED_INBOX_RETENTION_MODE` | `observe` |
| `BILLING_REJECTED_INBOX_RETENTION_AGE` | `30d` |
| `BILLING_REJECTED_INBOX_RETENTION_BATCH_SIZE` | `500` |
| `BILLING_REJECTED_INBOX_RETENTION_MAX_BATCHES` | `4` |
| `BILLING_REJECTED_INBOX_RETENTION_INTERVAL_MS` | `600000` |
| `BILLING_REJECTED_INBOX_RETENTION_INITIAL_DELAY_MS` | `60000` |

Niesekretne parametry są w aplikacji, Compose i ConfigMap. Wymagane: tryb
off/observe/compact, wiek minimum dzień, partia 1–1000, liczba partii 1–20,
odstęp minimum 1000 ms, opóźnienie minimum 0. Niepoprawne ustawienia
zatrzymują start. Profil test wyłącza oba automatyczne moduły retencji;
fixture PostgreSQL wywołuje worker jawnie. Bez nowych sekretów/SOPS.

## Odbiór

<!-- A43_11_RESULTS -->
- **168 testów jednostkowych Java** (108 infrastruktury i 60 Billing) oraz
  **15 PostgreSQL — 183 łącznie, PASS**, bez pominiętych. Nowych przypadków:
  19 (11 jednostkowych i osiem PostgreSQL). Piętnaście PostgreSQL obejmuje
  siedem istniejących testów retencji outboxu A43.5; nie sumować ich ponownie.
- Oba własne kontenery wykonały V1–V11 i walidację JPA. Sprawdzono granicę
  czasu, ochronę statusów/powiązań, przyszłe daty, payload-only/error-only
  i puste historyczne wpisy. Usunięto diagnostykę fixture ponad 5000 bajtów,
  zachowując metadane i unikalność ID. CHECK odrzucił niezgodne zmiany.
- Rzeczywiste RejectedEventStore/PaymentEventInbox zachowały deduplikację
  i wykrywanie konfliktu hashy; redelivery nie odtworzyło diagnostyki.
  Przyjęty receipt, faktura z pozycją i outbox faktur pozostały niezmienione.
- Równoległe partie zachowały budżet, zajęty wpis pozostawał zaległością,
  rollback późniejszej partii zachował wcześniejsze commity/liczniki,
  a worker odzyskał działanie. Polityki inbox/outbox wiążą się niezależnie
  w obu kierunkach. Istniejący odbiór A43.5 również przeszedł z migracją V11.
- **12 testów Kubernetes**, zgodność sześciu parametrów w trzech formatach,
  bramki Maven/korelacji/odporności i kontrola 15 JAR-ów — PASS.

Pełna regresja: `tmp/a43-11-maven-verify.log`.
Raport: `tmp/audit-a43-11/report.json`.
<!-- /A43_11_RESULTS -->

```text
./mvnw -pl billing-service -am -Pintegration verify
```

Wykorzystano istniejący profil Failsafe i zależności testowe Billing.
Nie dodano zależności runtime ani testowych. Istniejący przebieg CI
`-Pintegration verify` obejmuje nowe testy; nie uruchamiano zdalnego CI.

Odbiór używa własnych kontenerów PostgreSQL i fixture. Nowe testy nie
wywołują rzeczywistego brokera/dostawcy płatności i nie modyfikują dokumentów
użytkownika. Bez nowego widoku CC, zmian UI, restartu działających usług
i wdrożenia dev/prod. A05, całe A41 i zmiany samych zdjęć nadal odłożone.
