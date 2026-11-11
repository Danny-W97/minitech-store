# A43.10 — retencja diagnostyki odrzuconych zdarzeń Notification

Data: 27.10.2026. Pakiet 67 audytu, branch `Audit_0.1`.

## Zakres

Notification Service otrzymał mechanizm kompaktowania diagnostycznej kopii
odrzuconych komunikatów w `event_inbox`. Czyści `payload` oraz `error`,
zapisując `diagnostics_compacted_at`. Opis błędu również może zawierać fragmenty
wejścia, więc podlega temu samemu cyklowi co surowa treść.

Pozostawia cały rekord z jego `event_id`, `payload_hash`, statusem REJECTED
i czasem odbioru. Identyfikator nadal chroni ponowny odbiór tego samego
wpisu streamu, a hash pozwala rozpoznać ponowne użycie ID z inną treścią.
Kompaktowanie nie usuwa trwałych znaczników deduplikacji i nie zmniejsza
liczby rekordów inboxu.

Przyjęte komunikaty, powiadomienia, kolejka wysyłki/ponowień i DLQ pozostają
poza zakresem. Odrzucony komunikat w inboxie to diagnostyka błędnego wejścia,
a nie zadanie SMTP oczekujące na retry. Mechanizm nie zmienia potwierdzania
odbioru Redis ani dotychczasowych zasad obsługi duplikatów.

## Tryby i kwalifikacja

| Tryb | Zachowanie |
| --- | --- |
| `observe` — domyślny | Odczytuje najstarszego kandydata i publikuje metryki; bez modyfikacji danych |
| `compact` | Czyści diagnostykę kandydatów w ograniczonych partiach |
| `off` | Nie wykonuje zapytań retencji |

**30 dni to początkowy próg obserwacji.** Użytkownik wybiera docelowe okno
diagnostyki i jawnie aktywuje compact. Ogólne polecenie kolejnego etapu nie
włącza czyszczenia. Konfigurację trzeba zastosować do procesu Notification.

Wszystkie warunki kwalifikacji muszą być spełnione:

- status REJECTED;
- brak `related_id` — ochrona wpisów powiązanych z powiadomieniem;
- brak wcześniejszego znacznika kompaktowania;
- istnieje payload lub opis błędu; wystarczy dowolne z tych pól;
- `received_at` jest ściśle starszy od granicy retencji.

ACCEPTED, inne statusy, powiązane wpisy, świeże/przyszłe daty i równość
granicy pozostają chronione. Legacy REJECTED bez obu pól nie wymaga operacji.
Obserwacja nie pobiera payloadów ani błędów do pamięci i nie liczy całej tabeli.

## Migracja i ochrona ponowień

V11 dodaje znacznik, częściowy indeks `(received_at, event_id)` oraz CHECK:
oznaczona diagnostyka musi mieć puste payload/error, status REJECTED i brak
powiązania z powiadomieniem. Baza odrzuca osobne przywrócenie treści/błędu
lub zmianę oznaczonego wpisu na przyjęty/powiązany. Wpisy bez znacznika
zachowują wcześniejsze dopuszczalne postacie; migracja nie czyści danych.

RejectedEventStore nadal rozpoznaje istniejący ID przed zapisem. Ponowne
dostarczenie nie odtwarza usuniętej diagnostyki. NotificationEventInbox
zachowuje sprawdzenie hashy — użycie tego samego ID z inną treścią nadal
jest odrzucane. Klucz główny nie pozwala ponownie wstawić tego samego ID.
Współbieżne wstawienia pozostają obsługiwane przez istniejący retry konsumenta.

V11 i nowy kod należy wdrożyć razem, a przed aktywacją compact zaktualizować
wszystkie instancje Notification. Samo dodanie kolumny i indeksu nie aktywuje
czyszczenia. Kompaktowanie nie jest archiwizacją: oryginalna treść i pełny
opis błędu przestają być dostępne w tym inboxie. Przy wyborze okna należy
uwzględnić diagnostykę i ewentualne ręczne odtwarzanie z kopii.

Pozostawione identyfikatory/hashe i powiązane dane innych usług oznaczają,
że ten etap nie zamyka pełnego eksportu/usuwania/anonimizacji. Kopie
bezpieczeństwa i dane Redis zachowują własny cykl życia.

## Budżet i metryki

Partie mają własne transakcje REQUIRES_NEW z limitem 5 sekund. Materializowany
CTE wybiera kandydatów raz, po czasie i ID, z `FOR UPDATE SKIP LOCKED`.
Warunki są powtórzone przy UPDATE. Blokowany wpis nie zatrzymuje innych
workerów i nadal może być widoczny jako zaległość. Bez SMTP, HTTP ani Redis
w transakcji; JDBC poza transakcją ma limit 2 sekund.

Domyślnie 500 rekordów w partii i cztery partie w przebiegu — do 2000.
Krótka/zerowa partia kończy przebieg. Błąd wycofuje własną partię i zachowuje
liczniki wcześniejszych commitów. Ostrzeżenie zawiera klasę wyjątku, bez
surowej treści, parametrów SQL i komunikatu wyjątku.

Istniejący endpoint Prometheus publikuje:

| Metryka | Znaczenie |
| --- | --- |
| `notification_rejected_inbox_retention_enabled` | 1 dla observe/compact |
| `notification_rejected_inbox_retention_compaction_enabled` | 1 tylko dla compact |
| `notification_rejected_inbox_retention_age_seconds` | Skonfigurowany próg wieku |
| `notification_rejected_inbox_retention_compacted_total` | Zatwierdzone skrócenia |
| `notification_rejected_inbox_retention_runs_total{outcome}` | Przebiegi success/failed |
| `notification_rejected_inbox_retention_pending` | Czy istnieje kwalifikujący się kandydat |
| `notification_rejected_inbox_retention_oldest_eligible_age_seconds` | Wiek najstarszego kandydata |
| `notification_rejected_inbox_retention_last_success_timestamp_seconds` | Czas ostatniej pełnej migawki |
| `notification_rejected_inbox_retention_last_compacted` | Skrócenia ostatniego pełnego przebiegu |

Nieznana migawka ma -1. Błąd zachowuje wcześniejszy stan i czas jego odczytu.
Jedynymi etykietami są dwa stałe wyniki przebiegu, bez identyfikatorów klientów
lub zdarzeń. Scrape nie odpytuje bazy. Observe pokazuje kandydatów, a nie
aktywne czyszczenie.

## Konfiguracja

| Zmienna | Domyślnie |
| --- | --- |
| `NOTIFICATION_REJECTED_INBOX_RETENTION_MODE` | `observe` |
| `NOTIFICATION_REJECTED_INBOX_RETENTION_AGE` | `30d` |
| `NOTIFICATION_REJECTED_INBOX_RETENTION_BATCH_SIZE` | `500` |
| `NOTIFICATION_REJECTED_INBOX_RETENTION_MAX_BATCHES` | `4` |
| `NOTIFICATION_REJECTED_INBOX_RETENTION_INTERVAL_MS` | `600000` |
| `NOTIFICATION_REJECTED_INBOX_RETENTION_INITIAL_DELAY_MS` | `60000` |

Parametry są niesekretne i spójne w aplikacji, Compose i ConfigMap.
Wymagane: tryb off/observe/compact, wiek minimum dzień, partia 1–1000,
liczba partii 1–20, odstęp minimum 1000 ms i opóźnienie minimum 0.
Niepoprawne ustawienia zatrzymują start. Profil test ma tryb "off";
fixture PostgreSQL wywołuje worker jawnie. Bez zmian sekretów/SOPS.

## Odbiór

<!-- A43_10_RESULTS -->
- **133 testy jednostkowe Java** (108 infrastruktury i 25 Notification) oraz
  **8 testów PostgreSQL — 141 łącznie, PASS**, bez pominiętych. Nowych
  przypadków: 18 (dziesięć jednostkowych i osiem PostgreSQL).
- PostgreSQL wykonał V1–V11 i walidację JPA. Sprawdzono ścisłą granicę
  czasu, inne statusy, powiązanie z powiadomieniem, przyszłe daty oraz
  payload-only/error-only i puste historyczne wpisy.
- Usunięto treść fixture ponad 5000 bajtów i opis błędu, zachowując tożsamość,
  hash, status i czas. Constraint oraz klucz główny odrzuciły niezgodne
  aktualizacje i duplikaty. Rzeczywisty RejectedEventStore nie przywrócił
  diagnostyki po redelivery; NotificationEventInbox zachował blokadę konfliktu
  hashy. Przyjęty receipt i oczekujące powiadomienie pozostały niezmienione.
- Równoległe partie zachowały budżet, blokady zostały pominięte z widoczną
  zaległością, a błąd późniejszej partii nie cofnął wcześniejszych commitów
  ani ich liczników. Po usunięciu awarii worker dokończył kompaktowanie.
- **12 testów Kubernetes**, zgodność sześciu parametrów w trzech formatach
  oraz bramki parenta Maven/korelacji/odporności — PASS.
- Kontrola 15 wykonywalnych JAR-ów — PASS. V11, klasy modułu i aktualna
  konfiguracja są w JAR-ze Notification; zależności testowe nie weszły do niego.
  Własny kontener PostgreSQL usunięty. Diff — PASS.

Pełna regresja: `tmp/a43-10-maven-verify.log`.
Raport: `tmp/audit-a43-10/report.json`.
<!-- /A43_10_RESULTS -->

```text
./mvnw -pl notification-service -am -Pintegration verify
```

Nowy profil integration używa Failsafe i testowych zależności JPA/Testcontainers
z istniejącego parenta. Bez nowych zależności runtime. Istniejący przebieg CI
`-Pintegration verify` obejmuje nowe testy; nie uruchamiano zdalnego CI.

Testy używają własnego kontenera PostgreSQL, fixture i triggera wymuszającego
rollback. Nie wysyłają wiadomości, nie wywołują rzeczywistego brokera i nie
zmieniają danych użytkownika. Bez nowego widoku CC, restartowania działających
usług i wdrożenia dev/prod. A05, całe A41 i zmiany samych zdjęć nadal odłożone.
