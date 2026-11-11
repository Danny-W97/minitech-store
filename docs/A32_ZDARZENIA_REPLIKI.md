# A32 — trwałe zdarzenia i praca wielu replik

Zakres: Payment outbox, zdarzenia faktur/korekt, inbox Notification i Billing,
odzyskiwanie Redis pending, ochrona przed duplikatami oraz bezpieczna retencja.
Zmiany dotyczące samych zdjęć pozostają odłożone. Pakiet nie uruchamia wdrożenia
na dev/prod i nie zmienia sekretów.

## Publikacja po zatwierdzeniu SQL

Payment zapisuje płatność i outbox w jednej transakcji (granica wydzielona w
A31). Publisher pobiera do 50 identyfikatorów i przekazuje je do czterech
pracowników z kolejką 50. Każdy pracownik w krótkiej transakcji blokuje wiersz,
sprawdza status/termin i zapisuje token oraz czas rezerwacji. Po commicie wysyła
zdarzenie do Redis; wynik utrwala w kolejnej krótkiej transakcji, tylko gdy token
nadal należy do tego wykonawcy. Awaria procesu pozostawia pracę do przejęcia po
wygaśnięciu rezerwacji. Błąd brokera zapisuje FAILED z istniejącym backoffem.

`eventId` płatności to prefiks `payment-` i SHA-256 zapisanego `eventKey`.
Tożsamość nie zmienia się przy retry, restarcie lub przejęciu przez drugą replikę.
Pozostałe pola zdarzenia pozostają kompatybilne ze starym formatem.

Billing zapisuje `invoice_outbox_events` w tej samej transakcji co fakturę lub
korektę. `InvoiceEventPublisher` wymaga aktywnej transakcji i wyłącznie tworzy
wpis SQL; nie wywołuje Redis. Osobny publisher korzysta z rezerwacji, krótkich
transakcji i ograniczonych pracowników, analogicznie do Payment. Tożsamość to
`invoice-issued-<id>` lub `invoice-corrected-<id>`. Ponowienie wystawienia istniejącej
faktury nie generuje nowego zdarzenia. Scheduler Billing został jawnie włączony.

Awaria po XADD i przed zapisem PUBLISHED może spowodować ponowną publikację
tego samego zdarzenia. Jest to dostarczenie **co najmniej raz**; ochronę skutków
zapewnia trwały inbox odbiorcy. Nie ma wspólnej transakcji SQL/Redis.

## Odbiór i ACK

Każda instancja dodaje UUID do skonfigurowanego prefiksu konsumenta, także po
restarcie. Grupy tworzone są atomowo przez `XGROUP CREATE ... 0-0 MKSTREAM`,
bez dopisywania pomocniczych zdarzeń bootstrap. Stare wpisy bootstrap pozostają
obsługiwane. Każdy odczyt najpierw próbuje przejąć stare pending przez
`XAUTOCLAIM`, zachowując kursor skanowania pomiędzy wywołaniami, potem pobiera
nowe wpisy. Ponowne utworzenie brakującej grupy nie wymaga restartu aplikacji.
Mechanizm działa również dla pending pozostawionych przez stare, stałe nazwy.

Notification zapisuje w jednej transakcji:

1. trwały wpis `event_inbox` ze stabilnym ID i hashem treści;
2. powiązanie z jednym powiadomieniem i gotowość nowego zadania do wysłania.

Dopiero po commicie następuje ACK. Odbiorca Redis nie wysyła SMTP. Oddzielny
worker A31 pobiera zadanie z DB, rezerwuje je i dostarcza poza transakcją.
Równoległe inserty są chronione unikalnymi kluczami; konflikt insertu powoduje
ponowny odczyt po rollbacku. Powtórzenie zdarzenia nie zeruje retry i nie
uruchamia ponownie SENT/FAILED ani aktywnej próby. Dwa różne ID opisujące ten sam
istniejący klucz biznesowy powiadomienia również nie tworzą drugiej wiadomości.

Billing zapisuje własny inbox przed ACK. Zdarzenia płatności pozostają tam
obserwacją; nie zastępują weryfikacji opłaconego zamówienia przy wystawianiu
faktury. Błąd SQL lub ACK zostawia rekord pending. Restart po commicie i przed
ACK prowadzi do odczytu istniejącego inboxu i bezpiecznego ponowienia ACK.

Dla starszego zdarzenia bez `eventId` odbiorca używa tego samego hasha `eventKey`.
Gdy brak również klucza, tożsamość pochodzi ze streamu i ID wpisu Redis; powiązanie
biznesowe powiadomienia nadal chroni przed powieleniem wiadomości.

## Wadliwe zdarzenia i niepewny wynik wysyłki

Niepoprawny schemat lub próba użycia istniejącego ID z inną treścią trafiają do
lokalnego `event_inbox` jako REJECTED, z powodem i kopią payloadu. Ten zapis musi
zostać zatwierdzony przed ACK. Usterka DB podczas kwarantanny pozostawia pending.
Poprawnego, wcześniej zaakceptowanego wpisu nie nadpisuje się błędnym payloadem.

SMTP nie ma atomowej transakcji z bazą ani ogólnego klucza idempotencji dostawcy.
Serwer mógł przyjąć wiadomość, choć proces nie zdążył zapisać SENT. Dlatego:

- PENDING, którego wysyłki nie rozpoczęto (`attempt_count=0`), może zostać
  automatycznie przejęte i wysłane przez inną instancję.
- PENDING po rozpoczętej, przerwanej próbie trafia po wygaśnięciu rezerwacji do
  FAILED bez kolejnego terminu oraz do istniejącej DLQ.
- Błąd/timeout rozpoczętej operacji SMTP jest traktowany konserwatywnie jako
  niepewny wynik i trafia do DLQ. Błąd przygotowania wiadomości lub pobrania PDF
  nadal może otrzymać zwykłe retry.
- Operator rozstrzyga przypadek w istniejącym widoku DLQ Control Center lub
  przez endpoint replay, sprawdzając wcześniej dostarczenie u dostawcy.
  Świadome replay może wysłać drugi e-mail. Nie obiecuje się dokładnie jednego
  dostarczenia przez SMTP.

To doprecyzowuje odzyskiwanie PENDING opisane w A31. Inbox, zdarzenie i zadanie
pozostają trwałe; brak automatycznej ponownej wysyłki nie usuwa niepewnego zadania.
Nie dodano nowego widoku CC do kwarantanny zdarzeń — REJECTED jest dostępne w SQL.

## Limity i konfiguracja

| Ustawienie | Domyślnie | Znaczenie |
| --- | --- | --- |
| `OUTBOX_PUBLISHER_ENABLED` | `true` | Publisher Payment/Billing |
| `OUTBOX_PUBLISHER_FIXED_DELAY_MS` | `5000` | Odstęp pobierania gotowych ID |
| `OUTBOX_PUBLISHER_LEASE_MS` | `30000` | Rezerwacja publikacji, minimum 10000 ms |
| `EVENT_REDIS_CONNECT_TIMEOUT` | `1000ms` | Połączenie z brokerem |
| `EVENT_REDIS_TIMEOUT` | `1000ms` | Limit komendy Redis |
| `EVENT_CONSUMER_CLAIM_IDLE_MS` | `60000` | Minimalna bezczynność pending przed przejęciem, minimum 1000 ms |
| `PAYMENT_STREAM_RETENTION_AGE_MS` | `604800000` | Minimalny wiek wpisu do retencji: 7 dni |
| `PAYMENT_STREAM_REQUIRED_GROUPS` | `notification-service,billing-service` | Grupy wymagane do uruchomienia retencji |
| `NOTIFICATION_CONSUMER_NAME`, `BILLING_CONSUMER_NAME` | nazwa usługi | Prefiks; proces dopisuje własny UUID |

Każda pula outboxu ma czterech pracowników/kolejkę 50 i budżet zadania 5 s;
komenda Redis ma niezależny timeout. Limit rezerwacji musi pozostawać dłuższy niż
najdłuższa próba publikacji. Pool schedulera tych trzech usług ma cztery wątki.
Ustawienia są przekazywane przez przykład env, Compose i ConfigMap.

## Retencja i trwałość brokera

Retencja `payment-events` wykonuje co najwyżej jeden przebieg na minutę i usuwa
do 1000 starych wpisów w przebiegu. Jedna atomowa operacja Lua wyznacza granicę
z wieku wpisów, pozycji odczytu **wszystkich** istniejących grup i najstarszego
pending w każdej grupie. Chroni też grupy spoza zadanej listy. Brak choćby jednej
wymaganej grupy blokuje usuwanie. Nie stosuje się ślepego MAXLEN.
Przy wolnym odbiorcy stream może rosnąć: należy monitorować lag/pending i pojemność.
Zmiana nazwy grupy wymaga również aktualizacji listy wymaganych grup.

`invoice-events` nie ma obecnie aplikacyjnego odbiorcy. Automatyczne usuwanie
tego streamu jest wyłączone do jawnego określenia odbiorców i wymagań archiwizacji.
Retencja SQL outboxu/inboxu, kwarantanny i metadanych nieaktywnych konsumentów
pozostaje w zakresie A43; nie kasować dowolnie inboxu, bo stanowi historię deduplikacji.

Compose włącza Redis AOF z `appendfsync always` i zachowuje istniejący wolumen.
Dla zewnętrznego Redis/Kubernetes administrator musi zapewnić równoważną trwałość,
pojemność i procedurę odtwarzania. AOF `everysec` dopuszcza okno utraty danych po
awarii hosta ([opis trwałości Redis](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)). Pakiet nie zamyka trwałego uzgadniania skutków płatności (A09), backupów (A36)
ani odporności na utratę dysku. Odtworzenie pustego brokera z SQL wymaga kontrolowanego replay
z zachowaniem eventId i trwałych inboxów; nie usuwać ich podczas odtwarzania.

Mechanika Redis: [XAUTOCLAIM](https://redis.io/docs/latest/commands/xautoclaim/)
przejmuje bezczynne pending; [XINFO GROUPS](https://redis.io/docs/latest/commands/xinfo-groups/)
i [XPENDING](https://redis.io/docs/latest/commands/xpending/) dostarczają granice
używane przez implementację retencji. Rozwiązanie korzysta z poleceń Redis 7.

## Wdrożenie i diagnostyka

1. Opróżnić/zatrzymać stare instancje Payment, Billing i Notification wykonujące
   zadania. Stare publishery nie respektują nowych rezerwacji i inboxów.
2. Wykonać migracje: Payment **V9**, Billing **V6/V7**, Notification **V8**.
   Zaktualizować i uruchomić brokera ze świadomie wybranym poziomem trwałości;
   zachować dane i grupy. Zmiana szablonu Compose nie aktualizuje działającego
   brokera bez jego ponownego uruchomienia.
3. Przebudować reaktor/wspólną bibliotekę i wdrożyć nowe obrazy usług.
4. Sprawdzić dwie grupy, różne nazwy konsumentów, opróżnianie pending,
   PUBLISHED w obu outboxach oraz akceptowane/odrzucone wpisy inboxu.
5. Monitorować również DLQ powiadomień z niepewnym wynikiem SMTP.

Przykładowe odczyty wykonywane w odpowiedniej bazie:

```sql
SELECT status, count(*) FROM payment_outbox_events GROUP BY status;
SELECT status, count(*) FROM invoice_outbox_events GROUP BY status;
SELECT status, count(*) FROM event_inbox GROUP BY status;
SELECT event_id, error, received_at FROM event_inbox
WHERE status = 'REJECTED' ORDER BY received_at DESC LIMIT 50;
```

Starsze faktury, dla których nie zapisano outboxu, nie dostają automatycznie
nowych zdarzeń podczas migracji. Wymagają osobnego, kontrolowanego backfillu.
Pozostałe zakresy A05/A06/A09/A15/A43 pozostają otwarte.

## Testy

`scripts/qa/event-reliability-e2e.py` uruchamia dwie rzeczywiste JVM każdej
z trzech usług oraz jednorazowe PostgreSQL, Redis i Mailpit. Kontrolowane
wyzwalacze DB i uprawnienia tymczasowego Redis zatrzymują przepływ w punktach
publish/commit/ACK, bez dodawania produkcyjnych przełączników awarii do aplikacji.
Testy korzystają wyłącznie z losowych poświadczeń i własnych kontenerów.
Weryfikacja zakończona sukcesem: 258 testów Java, 192 kontrole regresji HTTP,
scenariusze awarii sześciu JVM oraz regresja deadline'ów A31. Wyniki i ścieżki
raportów zawiera [pakiet 15 checklisty](AUDIT_0.1_POSTEP.md#pakiet-15--a32-zdarzenia-i-wiele-replik-25102026).
CI uruchamia scenariusz A32 i zachowuje jego logi przy błędzie.
