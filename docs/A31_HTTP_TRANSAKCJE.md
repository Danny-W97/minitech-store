# A31 — limity HTTP i krótkie transakcje

Zakres na `Audit_0.1`: Gateway i współdzielony transport HTTP, katalog produktów,
płatności, dostarczanie powiadomień oraz saga rezerwacji. Zmiany samych zdjęć
pozostają odłożone zgodnie z decyzją użytkownika.

## Budżety żądań

`BoundedHttpRequestFactory` obsługuje wszystkie metody używane przez aplikację,
w tym PATCH. Używa JDK HttpClient bez przekierowań. Jawnie ogranicza połączenie,
oczekiwanie na nagłówki, bezczynność odczytu i całkowity czas wywołania wraz
z odczytem treści. Po terminie anuluje żądanie i zamyka subskrypcję odpowiedzi;
regularne przesyłanie pojedynczych bajtów nie przedłuża budżetu.

`X-Request-Deadline-Ms` przenosi bezwzględny termin między usługami. Odebrany
termin może wyłącznie skrócić lokalny limit. Odmierzanie wewnątrz procesu używa
zegara monotonicznego; zegary maszyn należy synchronizować. Filtr usuwa kontekst
po obsłużeniu żądania. Błędny format daje 400, żądanie przeterminowane na wejściu
504. Pozostałe błędy zależności zachowują kontrakty poszczególnych API.

| Ustawienie | Domyślnie | Znaczenie |
| --- | ---: | --- |
| `RESILIENCE_HTTP_CONNECT_TIMEOUT_MS` | 1000 ms | Limit zestawienia połączenia |
| `RESILIENCE_HTTP_READ_TIMEOUT_MS` | 3000 ms | Nagłówki i bezczynność odczytu zależności |
| `GATEWAY_HTTP_READ_TIMEOUT_MS` | 15000 ms | Dłuższy odczyt Gateway dla wywołań przechodzących przez kilka usług |
| `RESILIENCE_HTTP_REQUEST_BUDGET_MS` | 15000 ms | Wspólny lokalny limit żądania |
| `ORDER_SAGA_PROCESSING_LEASE_MS` | 120000 ms | Rezerwacja zadania sagi w DB, minimum 60000 ms |
| `PAYMENT_PROCESSING_LEASE_MS` | 120000 ms | Rezerwacja przetwarzania płatności, minimum 60000 ms |
| `NOTIFICATION_PROCESSING_LEASE_MS` | 120000 ms | Rezerwacja dostarczenia powiadomienia, minimum 60000 ms |
| `NOTIFICATION_SMTP_CONNECT_TIMEOUT_MS` | 1000 ms | Zestawienie połączenia SMTP |
| `NOTIFICATION_SMTP_READ_TIMEOUT_MS` | 1000 ms | Bezczynność odczytu SMTP |
| `NOTIFICATION_SMTP_WRITE_TIMEOUT_MS` | 1000 ms | Zapis SMTP |

Pojedyncze wywołanie HTTP mieści się w pozostałym budżecie żądania i sumie
limitów connect/read. Każda próba retry ponownie sprawdza pozostały czas.
Wyczerpanie budżetu wyłącza kolejne próby. Istniejące backoffy pozostają
ograniczone konfiguracją retry; budżet nie przerywa samego oczekiwania backoff.
Nie dodano retry dla eksportu ZIP ani dowolnych zapisów Gateway.

Eksport `/invoices/export/zip` zachowuje osobny limit 5 minut i strumieniowy
transport A30 z limitem bezczynności 30 sekund. Zwykły limit 15 sekund nie
skraca eksportów; krótszy termin przekazany przez wywołującego nadal obowiązuje
przy wejściu i wywołaniach wspólnego klienta. Pełna obsługa eksportu pozostaje
opisana w [procedurze A30](A30_PDF_CACHE_EKSPORT.md).

## Granice transakcji

Orkiestracja płatności i dostarczania powiadomień oraz pojedyncza saga wyłączają
transakcję wywołującego. Oddzielne beany `PaymentStateStore`,
`NotificationStateStore`, `OrderSagaStateStore` i `ProductStore` tworzą krótkie
transakcje przez proxy Spring. Pobierają dane, zapisują rezerwację zadania albo
utrwalają wynik. Wywołania HTTP, SMTP i operatora płatności odbywają się po
zamknięciu tych transakcji. Dotyczy to również wstępnego odczytu płatności:
bezpośredni odczyt repozytorium w pustym kontekście synchronizacji Spring mógł
zatrzymywać EntityManager i jedyne połączenie puli.

- Katalog pobiera wyłącznie potrzebne dane, zamyka transakcję, a następnie
  oblicza oferty z Promotion Service. Pola zdjęć są przekazywane bez zmian.
- Płatność najpierw zapisuje PENDING i token rezerwacji, następnie wywołuje
  zależności. Wynik i zdarzenie outbox zapisuje atomowo w jednej lokalnej
  transakcji. Zakończona płatność jest idempotentna. Równoległa aktywna próba
  dostaje 409; powtórzenie po wygaśnięciu rezerwacji używa zapisanego klucza
  dostawcy. Sprzątanie po błędzie nie utrwala odłączonego SUCCEEDED bez outboxu.
- Powiadomienie zapisuje próbę i token, dostarcza poza DB i zapisuje wynik oraz
  retry/DLQ w krótkiej transakcji. Aktualizacja A32: po
  wygaśnięciu rezerwacji nierozpoczęte zadanie jest odzyskiwane; przerwana próba
  o nieznanym wyniku trafia do DLQ, bez automatycznego ponownego SMTP. Stary
  wykonawca nie nadpisuje wyniku nowego właściciela.
- Saga pobiera odłączony zestaw pozycji, wywołuje Inventory i utrwala wynik.
  Token rezerwacji zabezpiecza zapis przed spóźnionym wykonawcą.

Płatność ma dodatkowy pułap orkiestracji 30 sekund, saga 5 sekund,
powiadomienie 8 sekund; krótszy limit żądania nadrzędnego wygrywa. SMTP ma
maksymalnie cztery równoległe wysyłki. Wysyłka działa na wątku wirtualnym;
przekroczenie terminu przerywa go i zamyka blokujące operacje gniazda JDK.
Timeout po przyjęciu wiadomości przez serwer oznacza niepewny wynik dostarczenia;
SMTP nie zapewnia dokładnie jednego dostarczenia.

## Praca harmonogramów

Harmonogram sagi pobiera do 100 identyfikatorów i przekazuje je do czterech
pracowników z kolejką maksymalnie 100 zadań. Retry powiadomień pobiera do 20 ID,
ma czterech pracowników i kolejkę 20. Ten sam ID trafia najwyżej raz do lokalnej
kolejki; przepełnienie zostawia rekord do następnego odczytu DB. Nie ma
wykonywania przeciążonych zadań na wątku harmonogramu. Tokeny w DB rozdzielają
pracę pomiędzy instancjami; kolejka nie jest źródłem trwałości.

`ORDER_SAGA_RECOVERY_ENABLED=false` wyłącza odzyskiwanie sagi przez scheduler
(np. w izolowanych testach), bez wyłączania obsługi nowego zamówienia.

## Uruchomienie i migracje

1. Zbudować cały reaktor (`./mvnw package`) lub wybrane moduły z `-am`.
   Każda usługa korzystająca ze wspólnej biblioteki wymaga nowego JAR-a/obrazu;
   Gateway również zależy teraz od biblioteki, ale zachowuje dotychczasowe
   przekazywanie uwierzytelnienia do usług.
2. Zatrzymać lub opróżnić stare instancje Order/Payment/Notification wykonujące
   zadania. Stara wersja nie respektuje nowych rezerwacji, więc nie uruchamiać
   starych i nowych pracowników równocześnie.
3. Uruchomić migracje Flyway: Order **V14**, Payment **V8**, Notification **V7**
   (nowe kolumny są nullable i nie zmieniają dotychczasowych rekordów).
4. Wdrożyć nowe usługi i Gateway ze spójnymi ustawieniami z przykładu env,
   Compose lub ConfigMap. Sprawdzić health, płatność, retry i stan sag.
5. Monitorować czas zależności, wykorzystanie puli DB i wiek aktywnych rezerwacji.
   Dostosowanie budżetów wymaga uwzględnienia liczby kolejnych wywołań/retry.

Pakiet nie uruchamia wdrożenia dev/prod i nie zmienia sekretów. A06, A15 i A32
pozostają otwarte w swoich pozostałych zakresach: uzgadnianie skutków płatności,
pełna idempotencja żądań, outbox publikowany przez wiele replik, billing outbox,
inbox, odzyskiwanie Redis pending, ACK i retencja nie są zamknięte przez A31.

## Weryfikacja

Testy JPA z pulą **jednego połączenia** obejmują Payment, Notification, sagę i
katalog. Podczas zablokowanej zależności brak aktywnej transakcji, a zapytania do
DB nadal działają. Testy sprawdzają równoległą próbę, przejęcie wygasłej rezerwacji
i odrzucenie zapisu starego wykonawcy.

`scripts/qa/http-transactions-e2e.py` uruchamia rzeczywiste JAR-y
Order/Payment/Notification z jednorazowym PostgreSQL i pulami po jednym
połączeniu. Kontrolowane zależności HTTP i SMTP zawieszają odpowiedź lub stale
przesyłają bajty. Scenariusz mierzy limit zadania, dostępność DB i niezależne
wykonanie szybkiej sagi. Zasoby są usuwane po teście; bazy developerskie nie są
używane. CI uruchamia ten scenariusz po zbudowaniu usług.

Weryfikacja pakietu: **251 testów Java** bez błędów/pominięć, **192 kontrole HTTP**
pełnej platformy, **72 kontrole HTTP** katalogu oraz regresja strumieniowania
ZIP 160 MiB przy heap Gateway 96 MiB. Wynik i lokalne pomiary awarii znajdują
się w [pakiecie 14 checklisty](AUDIT_0.1_POSTEP.md#pakiet-14--a31-limity-http-i-krótkie-transakcje-24102026).

Zakres zdarzeń wskazany powyżej jako pozostający po A31 wykonano następnie w
[A32 — zdarzenia i repliki](A32_ZDARZENIA_REPLIKI.md).
