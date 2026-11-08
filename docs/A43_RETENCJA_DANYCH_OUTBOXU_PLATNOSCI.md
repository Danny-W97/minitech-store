# A43.9 — retencja kopii danych odbiorcy w outboxie płatności

Data: 27.10.2026. Pakiet 66 audytu, branch `Audit_0.1`.

## Zakres

Payment Service otrzymał mechanizm kompaktowania starych, opublikowanych
rekordów `payment_outbox_events`. Ustawia `recipient` i `delivery_address`
na NULL oraz zapisuje `payload_compacted_at`. Zachowuje rekord, jego ID,
`event_key`, wyliczany z niego eventId, typ, zamówienie, kwotę, fakturę,
korelację, status, próby i daty. Nie usuwa płatności ani dokumentów.

**To ograniczenie kopii danych, nie anonimizacja.** Dotychczasowy `event_key`
zawiera adres e-mail odbiorcy. Pozostaje niezmieniony i unikalny, ponieważ
PaymentStateStore i FollowUpStateStore sprawdzają po nim duplikaty, a eventId
zależy od tego klucza. Usunięcie odbiorcy również z klucza wymaga osobnej
migracji tożsamości/deduplikacji. Inne bazy, Redis, inboxy, wiadomości,
kopie bezpieczeństwa i źródłowe dane zamówień zachowują własny cykl życia.

## Tryby i kwalifikacja

| Tryb | Działanie |
| --- | --- |
| `observe` — domyślny | Sprawdza najstarszego kandydata i publikuje metryki; bez modyfikacji danych |
| `compact` | Czyści wskazane pola w ograniczonych partiach i aktualizuje metryki |
| `off` | Nie wykonuje zapytań retencji |

30 dni jest początkowym progiem **obserwacji**. Docelowe okno diagnostyki
i odtwarzania oraz aktywację compact ustala użytkownik. Ogólne polecenie
realizacji następnego etapu nie włącza czyszczenia. Konfiguracja wymaga
zastosowania do procesu Payment.

Kwalifikują się tylko rekordy jednocześnie spełniające warunki:

- status PUBLISHED i niepusty `published_at`;
- obecny odbiorca, brak wcześniejszego znacznika kompaktowania;
- puste oba pola dzierżawy: `processing_token` i `processing_until`;
- późniejszy z czasów utworzenia i publikacji jest **ściśle starszy** od granicy.

PENDING/FAILED, świeża publikacja, równość granicy, przyszłe daty, nieznany
czas publikacji i dowolna pozostałość dzierżawy są chronione. Sam stary
`created_at` nie wystarcza. Obserwacja nie pobiera treści kontaktu do pamięci.

## Ochrona publikacji i migracja

V17 dodaje znacznik, dopuszcza NULL w recipient i wprowadza constraint
cyklu życia. Zwykły rekord ma odbiorcę i nie ma znacznika. Skrócony rekord
musi mieć oba pola kontaktowe puste, status PUBLISHED, czas publikacji,
znacznik i brak obu pól dzierżawy. Baza odrzuca przywrócenie pojedynczego
pola kontaktu, zakolejkowanie lub dzierżawę skróconego rekordu.

Unikalny `event_key` pozostaje trwałym zabezpieczeniem przed ponownym
utworzeniem zdarzenia. StateStore nie przejmuje PUBLISHED, a spóźnione
complete sprawdza aktualny token i nie zapisuje starej encji ticketu do bazy.
PaymentEventPublisher jawnie odrzuca załadowany skrócony payload przed
wywołaniem Redis. Nie wysyła zdarzenia z pustym odbiorcą.

Pozostawione pełne, wcześniej pobrane obiekty w pamięci nie są zdalnie
unieważniane. Dotychczasowy protokół dzierżaw oraz deduplikacja konsumentów
nadal odpowiadają za opóźnione próby publikacji i dostarczenie co najmniej raz.

Indeks częściowy `(GREATEST(published_at, created_at), id)` obejmuje tylko
kandydatów. Sama migracja nie czyści danych. V17 i kod należy wdrożyć razem;
przed aktywacją compact zaktualizować wszystkie instancje Payment. Starszy
kod zakłada niepustego odbiorcę. Nie cofać do niego po kompaktowaniu bez
procedury zgodności/odtworzenia.

Kompaktowanie nie archiwizuje zdarzenia. Po nim outbox nie zawiera już
pełnego adresu do ręcznej rekonstrukcji komunikatu. Deduplikacja i metadane
pozostają, ale liczba wierszy nadal rośnie. Odtwarzanie i kopie to dalszy
zakres A36/A43; etap nie wznawia odłożonego odbioru Stripe A05.

## Budżet i metryki

Każda partia ma osobną transakcję REQUIRES_NEW, limit 5 sekund i blokady
`FOR UPDATE SKIP LOCKED`. Materializowany CTE wyznacza listę kandydatów raz,
więc ponowienie podzapytania przez plan UPDATE nie zwiększa limitu partii.
Warunki kwalifikacji są powtórzone w UPDATE. Bez HTTP i brokera w transakcji.
JDBC poza transakcją ma limit 2 sekund; w transakcji stosuje jej pozostały czas.

Domyślnie do 500 rekordów w partii i cztery partie w przebiegu, łącznie
2000. Krótsza/zerowa partia kończy przebieg; zajęty rekord może nadal być
widoczny jako zaległość. Błąd wycofuje bieżącą partię, zachowując liczniki
wcześniejszych commitów. Log ostrzeżenia podaje klasę błędu, bez adresów,
parametrów SQL i treści wyjątku.

Metryki z istniejącego endpointu Prometheus Payment:

| Nazwa | Znaczenie |
| --- | --- |
| `payment_outbox_retention_enabled` | 1 dla observe/compact |
| `payment_outbox_retention_compaction_enabled` | 1 wyłącznie dla compact |
| `payment_outbox_retention_age_seconds` | Skonfigurowany próg wieku |
| `payment_outbox_retention_compacted_total` | Zatwierdzone skrócenia |
| `payment_outbox_retention_runs_total{outcome}` | Przebiegi success/failed |
| `payment_outbox_retention_pending` | Czy istnieje kwalifikujący się kandydat |
| `payment_outbox_retention_oldest_eligible_age_seconds` | Wiek najstarszego kandydata |
| `payment_outbox_retention_last_success_timestamp_seconds` | Czas ostatniej pełnej migawki |
| `payment_outbox_retention_last_compacted` | Skrócenia ostatniego pełnego przebiegu |

Nieznana migawka to -1. Błąd zachowuje poprzedni stan i jego czas.
Obserwacja pokazuje kandydatów, a nie aktywne usuwanie. Jedynymi etykietami
są dwa stałe wyniki. Scrape nie odpytuje bazy; nie ma pełnego COUNT tabeli.

## Konfiguracja

| Zmienna | Domyślnie |
| --- | --- |
| `PAYMENT_OUTBOX_RETENTION_MODE` | `observe` |
| `PAYMENT_OUTBOX_RETENTION_AGE` | `30d` |
| `PAYMENT_OUTBOX_RETENTION_BATCH_SIZE` | `500` |
| `PAYMENT_OUTBOX_RETENTION_MAX_BATCHES` | `4` |
| `PAYMENT_OUTBOX_RETENTION_INTERVAL_MS` | `600000` |
| `PAYMENT_OUTBOX_RETENTION_INITIAL_DELAY_MS` | `60000` |

Niesekretne parametry są w aplikacji, Compose i ConfigMap. Tryb musi być
off/observe/compact, wiek minimum dzień, partia 1–1000, liczba partii 1–20,
odstęp minimum 1000 ms, opóźnienie minimum 0. Niepoprawne ustawienia zatrzymują
start. Profil test wyłącza automatyczne zapytania retencji; fixture PostgreSQL
wywołuje worker jawnie. Nie dodano sekretów ani zmian SOPS.

## Odbiór

<!-- A43_9_RESULTS -->
- **206 testów jednostkowych Java** (108 infrastruktury i 98 Payment) oraz
  **7 testów PostgreSQL — 213 łącznie, PASS**, bez pominiętych. Nowych
  przypadków: 19 (12 jednostkowych i siedem PostgreSQL).
- PostgreSQL wykonał wszystkie migracje V1–V17 oraz walidację modelu JPA.
  Potwierdzono ścisłe granice czasu, PENDING/FAILED, nieznaną publikację,
  pozostałości dzierżaw, ochronę metadanych i niezmienną tożsamość eventId.
- Testy potwierdziły usunięcie tylko pól recipient/delivery_address,
  zachowanie event_key i odmowę ponownego użycia jego wartości. Constraint
  odrzucił przywrócenie pojedynczego pola, zmianę statusu/dzierżawy lub
  usunięcie wymaganego czasu/znacznika. Załadowany skrócony event nie
  wywołuje brokera; rzeczywisty StateStore odrzuca ponowny claim i stary
  complete nie przywraca kontaktu. FAILED nadal udostępnia pełną treść do retry.
- Równoległe partie przestrzegają limitu, blokady są pomijane bez ukrywania
  zaległości, a rollback późniejszej partii zachowuje wcześniejsze commity
  i liczniki. Po usunięciu awarii worker wraca do zaległości.
- **12 testów Kubernetes**, zgodność sześciu parametrów w trzech formatach,
  parent 17 modułów i bramki korelacji/odporności — PASS.
- Kontrola 15 wykonywalnych JAR-ów — PASS. V17, nowe klasy i aktualna
  konfiguracja są w JAR-ze Payment; zależności testowe nie weszły do niego.
  Własny kontener PostgreSQL został usunięty. Diff — PASS.

Pełna regresja: `tmp/a43-9-maven-verify.log`; dodatkowy odbiór konfiguracji
po ujednoliceniu cytowania trybu off: `tmp/a43-9-config-acceptance.log`.
Te same cztery testy startu/bindowania nie zwiększają liczby 213.
Raport końcowy: `tmp/audit-a43-9/report.json`.
<!-- /A43_9_RESULTS -->

```text
./mvnw -pl payment-service -am -Pintegration verify
```

Profil integration używa Failsafe i testowych zależności JPA/Testcontainers
zarządzanych przez istniejący parent. Bez nowych zależności runtime. Standardowe
CI `-Pintegration verify` obejmuje nowe testy; nie uruchamiano zdalnego CI.

Testy korzystają z własnego kontenera PostgreSQL, adresów example.test oraz
triggera wymuszającego rollback. Nie wymagają kluczy Stripe, nie wywołują
rzeczywistego brokera ani dostawcy płatności i nie zmieniają danych użytkownika.
Bez nowego widoku CC, zmian UI, restartowania działających usług i wdrożenia
dev/prod. A05, całe A41 i zmiany samych zdjęć pozostają odłożone.
