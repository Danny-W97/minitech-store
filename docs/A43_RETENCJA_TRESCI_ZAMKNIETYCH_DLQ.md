# A43.13 — retencja treści zamkniętych wpisów DLQ

Data: 27.10.2026. Pakiet 70 audytu, branch `Audit_0.1`.

## Zakres

Notification Service otrzymał kompaktowanie `subject`, `message` oraz
`failure_reason` starych RESOLVED w `notification_dead_letters`. Zapisuje
`content_compacted_at`, zachowując rekord, ID, notificationId, typ/sourceId,
odbiorcę, kanał, status, próby i daty. Kopia powiadomienia w notifications
ma własną politykę A43.12; mechanizmy działają niezależnie, w obu kolejnościach.

Wpis dostaje także `row_version` używany przez JPA jako @Version. Retencja
zwiększa go atomowo z usunięciem treści. Stary obiekt nie może nadpisać
zmienionego rekordu przez merge i odtworzyć payloadu.

Lista OPEN nadal zwraca pełne dane. DTO DLQ dodaje `contentCompacted`;
mapper oznaczonego RESOLVED pokazuje `Content removed` w temacie,
`Content removed by retention policy.` w wiadomości oraz `Diagnostic removed
by retention policy.` zamiast błędu. Nie dodano endpointu historii RESOLVED
ani widoku CC. Istniejący replay zwraca odpowiedź powiadomienia.

Pozostawione rekordy i odbiorcy, inne bazy, inboxy/czat oraz kopie nadal
wymagają własnego cyklu życia. Kompaktowanie nie archiwizuje treści,
nie zmniejsza liczby wierszy i nie zamyka pełnej anonimizacji.

## Tryby i kwalifikacja

| Tryb | Zachowanie |
| --- | --- |
| `observe` — domyślny | Pokazuje kandydatów i metryki; bez modyfikacji |
| `compact` | Czyści treść w ograniczonych partiach |
| `off` | Nie wykonuje zapytań retencji |

**30 dni jest początkowym progiem obserwacji.** Użytkownik wybiera docelowe
okno historii DLQ i jawnie aktywuje compact. Ogólne polecenie kolejnego etapu
nie włącza czyszczenia. Konfigurację trzeba zastosować do procesu Notification.

Wymagane są wszystkie warunki:

- DLQ ma status RESOLVED, znany `resolved_at`, pełne trzy pola treści i brak znacznika;
- późniejszy z jego `created_at`, `updated_at`, `resolved_at` jest ściśle starszy od granicy;
- istnieje powiązane powiadomienie w terminalnym stanie SENT ze znanym `sent_at`;
- powiadomienie nie ma dzierżaw, błędu ani terminu retry;
- późniejszy z jego `created_at`, `updated_at`, `sent_at` również jest ściśle starszy od granicy.

OPEN, nieznane daty, granica czasu, świeże/przyszłe dane, brak rodzica,
FAILED/PENDING i aktywne lub niespójne stany pozostają chronione. Retencja
nie uznaje samego ręcznego RESOLVED przy niewysłanej wiadomości za podstawę
usunięcia treści. Obserwacja nie pobiera body/błędu i nie liczy całej tabeli.

## Replay, blokady i migracja

V13 dodaje znacznik, wersję rekordu (0 dla istniejących wpisów), częściowy
indeks wieku/ID i CHECK. Wpis bez znacznika ma pełne subject/message/reason;
skrócony musi mieć wszystkie trzy pola puste, status RESOLVED i czas zamknięcia.
Sama migracja nie czyści danych. ID i unikalność notification_id pozostają.

Replay nadal najpierw blokuje powiadomienie. Przed użyciem kopii DLQ jest
ona odświeżana pod blokadą, również gdy była wcześniej zarządzana przez JPA.
Resolve odczytuje aktualny wpis po ID, zamiast scalać przekazaną starą encję.
Ponowne zamknięcie RESOLVED jest idempotentne: nie przesuwa czasu retencji.
Rejestrowanie kolejnego błędu również odświeża istniejący wpis pod blokadą;
oznaczonej kopii nie można otworzyć metodą encji.

SENT replay nie wysyła ponownie. OPEN/FAILED zachowują pełną treść do
ponowienia. Gdyby powiadomienie po kompaktowaniu DLQ zostało poza protokołem
zmienione na niewysłane, replay zwraca 409 zamiast przygotować pustą wiadomość.
Stare pełne obiekty w pamięci nie są zdalnie unieważniane; @Version chroni
ich późniejszy zapis do bazy.

Collector blokuje wyłącznie wiersze DLQ przez `FOR UPDATE SKIP LOCKED`.
Powiadomienie sprawdza przez EXISTS; SENT jest terminalnym stanem obecnego
StateStore. Nie próbuje pobrać blokady rodzica po dziecku, więc nie odwraca
kolejności operacji wysyłki/replay. Ten protokół zakłada zmiany stanu przez
aplikację; ręczne, równoległe modyfikowanie SQL nie jest jego uczestnikiem.

Partia ma REQUIRES_NEW, limit 5 sekund i materializowany wybór kandydatów.
UPDATE powtarza kwalifikację, usuwa treść i zwiększa row_version. Bez SMTP,
HTTP/Redis i pobierania treści do pamięci; JDBC poza transakcją ma limit 2 sekund.
Domyślnie 500 rekordów w partii i cztery partie — do 2000 w przebiegu.
Krótka/zerowa partia kończy przebieg. Błąd wycofuje własną partię, zachowując
poprzednie commity/liczniki. Log zawiera klasę wyjątku, bez SQL/treści błędu.

V13 i kod należy wdrożyć razem. Przed aktywacją compact zaktualizować
wszystkie instancje Notification — starszy kod nie używa wersji i zakłada
niepuste pola DLQ. Po kompaktowaniu powrót do niego wymaga procedury
zgodności/odtworzenia. Okno musi uwzględniać potrzebę diagnostyki i kopie.

## Konfiguracja i metryki

| Zmienna | Domyślnie |
| --- | --- |
| `NOTIFICATION_RESOLVED_DLQ_RETENTION_MODE` | `observe` |
| `NOTIFICATION_RESOLVED_DLQ_RETENTION_AGE` | `30d` |
| `NOTIFICATION_RESOLVED_DLQ_RETENTION_BATCH_SIZE` | `500` |
| `NOTIFICATION_RESOLVED_DLQ_RETENTION_MAX_BATCHES` | `4` |
| `NOTIFICATION_RESOLVED_DLQ_RETENTION_INTERVAL_MS` | `600000` |
| `NOTIFICATION_RESOLVED_DLQ_RETENTION_INITIAL_DELAY_MS` | `60000` |

Sześć niesekretnych parametrów jest w aplikacji, Compose i ConfigMap.
Tryb off/observe/compact, wiek minimum dzień, partia 1–1000, liczba partii
1–20, odstęp minimum 1000 ms, opóźnienie minimum 0; błąd zatrzymuje start.
Profil test wyłącza wszystkie trzy automatyczne retencje Notification.
Polityki DLQ, SENT i REJECTED mają niezależne tryby/okresy/budżety.

Istniejący Prometheus udostępnia:

| Metryka | Znaczenie |
| --- | --- |
| `notification_resolved_dlq_retention_enabled` | 1 dla observe/compact |
| `notification_resolved_dlq_retention_compaction_enabled` | 1 tylko dla compact |
| `notification_resolved_dlq_retention_age_seconds` | Próg wieku |
| `notification_resolved_dlq_retention_compacted_total` | Zatwierdzone skrócenia |
| `notification_resolved_dlq_retention_runs_total{outcome}` | Przebiegi success/failed |
| `notification_resolved_dlq_retention_pending` | Czy istnieje kandydat |
| `notification_resolved_dlq_retention_oldest_eligible_age_seconds` | Wiek najstarszej kwalifikującej się kopii DLQ |
| `notification_resolved_dlq_retention_last_success_timestamp_seconds` | Czas ostatniej pełnej migawki |
| `notification_resolved_dlq_retention_last_compacted` | Skrócenia ostatniego pełnego przebiegu |

Nieznana migawka ma -1; błąd zachowuje poprzednią i jej czas. Etykiety
to wyłącznie dwa wyniki, bez klientów/zdarzeń. Scrape nie odpytuje bazy.
Observe pokazuje kandydatów, a nie aktywne czyszczenie.

## Odbiór

<!-- A43_13_RESULTS -->
- **163 testy jednostkowe Java** (108 infrastruktury i 55 Notification)
  oraz **29 PostgreSQL — 192 łącznie, PASS**, bez pominiętych. Nowych
  przypadków: 27 (15 jednostkowych i 12 PostgreSQL). Wynik zawiera
  wcześniejsze 17 PostgreSQL A43.10/A43.12 — nie sumować ich ponownie.
- Trzy własne bazy wykonały V1–V13 i walidację JPA. Sprawdzono sześć granic
  czasu rodzica/DLQ, OPEN/FAILED/PENDING, brak rodzica/czasu wysyłki,
  dzierżawy/retry/błąd i przyszłe dane. Zniknęło ponad 5000 bajtów body
  fixture oraz temat/reason; wersja wzrosła o jeden, pozostałe metadane
  i rodzic pozostały niezmienione. CHECK i unikalność notification_id — PASS.
- Rzeczywiste failure/replay/success/compact nie wysłały duplikatu ani nie
  przywróciły treści przez stare complete. OPEN i replay w toku zachowały
  pełny payload. DTO oznacza pominięcie; niespójny replay zwrócił 409.
- Stary detached merge został odrzucony przez @Version; resolve/record
  nie odtworzyły treści. Rzeczywista druga transakcja skróciła wpis już
  załadowany do JPA; późniejszy replay odświeżył go bez utraty ochrony.
- Pierwszy odbiór wykrył konflikt starej wersji podczas zapytania blokującego,
  jeszcze przed refresh. Poprawka używa zwykłego odczytu i atomowego
  refresh z PESSIMISTIC_WRITE. Wskazany test i dziesięć jednostkowych
  przeszły, następnie cała regresja powyżej — PASS; nie sumować tych powtórzeń.
- Blokady dziecka, brak odwrócenia blokad przy zajętym rodzicu, równoległe
  partie, budżet, rollback/odzyskanie i obie kolejności retencji SENT/DLQ — PASS.
- **12 Kubernetes**, spójność sześciu parametrów w trzech formatach oraz
  bramki Maven/korelacji/odporności i kontrola 15 JAR-ów — PASS.

Raport: `tmp/audit-a43-13/report.json`. Końcowa regresja:
`tmp/a43-13-maven-verify-final.log`; pierwszy odbiór:
`tmp/a43-13-maven-verify.log`; wskazany wyścig:
`tmp/a43-13-managed-replay-check.log`.
<!-- /A43_13_RESULTS -->

```text
./mvnw -pl notification-service -am -Pintegration verify
```

Wykorzystano istniejące zależności/profil Failsafe. Standardowe CI obejmuje
nowe przypadki; bez uruchamiania zdalnego CI. Odbiór używa własnych kontenerów
i fixture, bez wysyłki wiadomości, rzeczywistego brokera i danych użytkownika.
Bez nowych sekretów, widoku CC, zmian JS, restartu usług i wdrożenia dev/prod.
A05, całe A41 i zmiany samych zdjęć nadal odłożone.
