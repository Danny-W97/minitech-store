# A43.12 — retencja treści wysłanych powiadomień

Data: 27.10.2026. Pakiet 69 audytu, branch `Audit_0.1`.

## Zakres i API

Notification Service otrzymał kompaktowanie `subject` i `message` starych,
potwierdzonych SENT w tabeli `notifications`. Zapisuje `content_compacted_at`,
pozostawiając rekord i jego pozostałe pola: ID, typ/sourceId, odbiorcę,
kanał, korelację, invoiceId, status, referencję dostawcy, próby i daty.
Zachowana unikalność `(event_type, source_id, recipient)` nadal blokuje
ponowne utworzenie powiadomienia dla tego samego klucza.

Odpowiedź API ma nowe pole `contentCompacted`. Dla pełnej treści jest false,
a temat i wiadomość są takie jak wcześniej. Po kompaktowaniu jest true,
`subject` ma tekst `Content removed`, a `message` — `Content removed by
retention policy.`. Dotyczy szczegółów, list i odpowiedzi na powtórzone
utworzenie/replay. API nie przedstawia usuniętej treści jako pełnego wyniku.
Endpointy, uprawnienia i status SENT pozostają zachowane. Nie dodano widoku CC
ani zmian JavaScript.

Odbiorca pozostaje częścią klucza deduplikacji. DLQ, inboxy, historia czatu,
zamówienia, dokumenty i kopie przechowują własne dane; ten etap nie usuwa
wszystkich kopii wiadomości ani nie zamyka anonimizacji. Liczba rekordów
powiadomień nadal rośnie. Kompaktowanie nie jest archiwizacją — oryginalny
temat i body nie będą dostępne w tej tabeli po operacji.

## Tryby i kwalifikacja

| Tryb | Zachowanie |
| --- | --- |
| `observe` — domyślny | Pokazuje kandydatów i metryki, bez modyfikacji |
| `compact` | Czyści temat/treść w ograniczonych partiach |
| `off` | Nie wykonuje zapytań retencji |

**30 dni jest początkowym progiem obserwacji.** Użytkownik ustala docelowe
okno historii wiadomości i osobno aktywuje compact. Ogólne polecenie kolejnego
etapu nie uruchamia czyszczenia. Konfigurację trzeba zastosować do procesu.

Kwalifikują się wyłącznie wpisy, dla których jednocześnie:

- status to SENT i `sent_at` jest znany;
- temat oraz body są obecne i brak znacznika wcześniejszego kompaktowania;
- oba pola dzierżawy są puste;
- `next_attempt_at` i `failure_reason` są puste;
- najpóźniejszy z `sent_at`, `created_at`, `updated_at` jest ściśle starszy od granicy.

PENDING/FAILED, nieznana wysyłka, jakakolwiek pozostałość dzierżawy, zapisany
termin retry/błąd, świeża aktualizacja, przyszłe daty i równość granicy są
chronione. Obserwacja nie pobiera treści i nie wykonuje pełnego COUNT tabeli.

Polityka jest niezależna od `messaging.rejected-inbox-retention.*` A43.10.
Moduły mają osobne tryby, okresy, budżety i metryki; aktywacja jednego nie
aktywuje drugiego. Profil test wyłącza oba automatyczne workery.

## Ochrona wysyłki i migracja

V12 dopuszcza NULL w subject/message i dodaje znacznik oraz CHECK:
zwykły rekord ma oba pola treści i nie ma znacznika; skrócony musi mieć
oba pola puste, status SENT, czas wysyłki i brak dzierżaw/retry/błędu.
Indeks częściowy `(GREATEST(sent_at, created_at, updated_at), id)` obejmuje
kandydatów. Sama migracja nie czyści treści.

Dotychczasowe StateStore.create/retry/replay odmawiają ponownej wysyłki SENT,
a complete sprawdza aktualny token. Powtórne utworzenie lub odbiór zdarzenia
nie odtwarza treści. Spóźnione complete nie zapisuje starej encji ticketu
do bazy. W metodach encji i dostawcach dodano jawne odrzucenie załadowanej
skróconej treści przed przygotowaniem e-maila, pobraniem PDF lub wysyłką.

FAILED i otwarte DLQ zachowują pełną treść do replay. Po udanej ponownej
wysyłce powiadomienie może się kwalifikować po okresie retencji; kopia DLQ
pozostaje. Replay już wysłanego, skróconego powiadomienia nadal kończy się
bez wywołania dostawcy. Dotychczasowe pełne obiekty w pamięci nie są zdalnie
unieważniane; dzierżawy i kontrola wyniku odpowiadają za stare próby.

V12 i kod należy wdrożyć razem; przed aktywacją compact zaktualizować
wszystkie instancje Notification. Starszy kod zakłada niepustą treść.
Po kompaktowaniu powrót do niego wymaga procedury zgodności/odtworzenia.

## Budżet i metryki

Każda partia ma własną transakcję REQUIRES_NEW, limit 5 sekund i blokady
`FOR UPDATE SKIP LOCKED`. Materializowany CTE wyznacza kandydatów raz,
w kolejności wieku/ID. UPDATE ponownie sprawdza kwalifikację. Bez SMTP,
HTTP/Redis i pobierania body w transakcji; JDBC poza nią ma limit 2 sekund.
Zajęty wpis jest pomijany, ale pozostaje widoczny jako zaległość.

Domyślnie 500 rekordów w partii i cztery partie w przebiegu — do 2000.
Krótka/zerowa partia kończy przebieg. Błąd wycofuje własną partię; wcześniej
zatwierdzone skrócenia i ich liczniki pozostają. Ostrzeżenie podaje klasę
wyjątku, bez SQL, treści i komunikatu błędu.

Metryki istniejącego endpointu Prometheus:

| Nazwa | Znaczenie |
| --- | --- |
| `notification_sent_retention_enabled` | 1 dla observe/compact |
| `notification_sent_retention_compaction_enabled` | 1 tylko dla compact |
| `notification_sent_retention_age_seconds` | Skonfigurowany próg wieku |
| `notification_sent_retention_compacted_total` | Zatwierdzone skrócenia |
| `notification_sent_retention_runs_total{outcome}` | Przebiegi success/failed |
| `notification_sent_retention_pending` | Czy istnieje kandydat |
| `notification_sent_retention_oldest_eligible_age_seconds` | Wiek najstarszego kandydata |
| `notification_sent_retention_last_success_timestamp_seconds` | Czas ostatniej pełnej migawki |
| `notification_sent_retention_last_compacted` | Skrócenia ostatniego pełnego przebiegu |

Nieznana migawka ma -1; błąd zachowuje poprzednią i jej czas. Etykiety to
wyłącznie dwa stałe wyniki. Scrape nie odpytuje bazy. Observe pokazuje
kandydatów, a nie aktywne czyszczenie.

## Konfiguracja

| Zmienna | Domyślnie |
| --- | --- |
| `NOTIFICATION_SENT_RETENTION_MODE` | `observe` |
| `NOTIFICATION_SENT_RETENTION_AGE` | `30d` |
| `NOTIFICATION_SENT_RETENTION_BATCH_SIZE` | `500` |
| `NOTIFICATION_SENT_RETENTION_MAX_BATCHES` | `4` |
| `NOTIFICATION_SENT_RETENTION_INTERVAL_MS` | `600000` |
| `NOTIFICATION_SENT_RETENTION_INITIAL_DELAY_MS` | `60000` |

Niesekretne parametry są w aplikacji, Compose i ConfigMap. Wymagane:
off/observe/compact, wiek minimum dzień, partia 1–1000, liczba partii 1–20,
odstęp minimum 1000 ms i opóźnienie minimum 0. Błąd zatrzymuje start.
Testy PostgreSQL wywołują worker jawnie. Bez nowych sekretów/SOPS.

## Odbiór

<!-- A43_12_RESULTS -->
- **148 testów jednostkowych Java** (108 infrastruktury i 40 Notification)
  oraz **17 PostgreSQL — 165 łącznie, PASS**, bez pominiętych. Nowych
  przypadków: 24 (15 jednostkowych i dziewięć PostgreSQL). Siedemnaście
  obejmuje osiem wcześniejszych PostgreSQL A43.10 — nie sumować ich ponownie.
- Obie własne bazy wykonały V1–V12 i walidację JPA. Sprawdzono wszystkie trzy
  granice czasu, statusy, brak czasu wysyłki, dzierżawy i terminy/błędy retry.
  Kompaktowanie usunęło ponad 5000 bajtów body fixture, zachowując pozostałe
  pola, unikalność klucza i ograniczenia cyklu życia.
- Rzeczywiste StateStore/Service/Inbox potwierdziły brak ponownej wysyłki
  lub przywrócenia treści przez duplikat, redelivery i spóźnione complete.
  Szczegóły/lista/powtórzone create oraz replay zwracają jawne oznaczenie
  kompaktowania. Serializacja JSON pokazuje contentCompacted.
- FAILED zachował treść do rzeczywistego replay; po sukcesie SENT można
  kompaktować, a kopia DLQ pozostała niezmieniona. Replay SENT nie wywołał
  dostawcy. Załadowana skrócona encja nie mogła dotrzeć do SMTP ani pobrania PDF.
- Równoległe partie, blokady/zaległość, budżet, rollback i odzyskanie — PASS.
  Oba workery nie zmieniają danych drugiej polityki; tryby/okresy wiążą się
  niezależnie. Dotychczasowy odbiór diagnostyki REJECTED również przeszedł z V12.
- **12 Kubernetes**, spójność sześciu parametrów w trzech formatach oraz
  bramki Maven/korelacji/odporności i kontrola 15 JAR-ów — PASS.

Pełna regresja: `tmp/a43-12-maven-verify.log`.
Raport: `tmp/audit-a43-12/report.json`.
<!-- /A43_12_RESULTS -->

```text
./mvnw -pl notification-service -am -Pintegration verify
```

Wykorzystano istniejące zależności i profil Failsafe. Standardowe CI
`-Pintegration verify` obejmuje nowe testy; nie uruchamiano zdalnego CI.
Odbiór używa własnych kontenerów i fixture; nowe przypadki nie wysyłają
wiadomości i nie wywołują rzeczywistego brokera/dostawcy płatności.
Bez danych użytkownika, restartu CC i wdrożenia dev/prod. A05, całe A41
oraz zmiany samych zdjęć nadal odłożone.
