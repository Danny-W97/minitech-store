# A42.3 — wspólna odporność wywołań HTTP (26.10.2026)

Pakiet `org.demo.platformhttp` w bibliotece `platform-security` zawiera wspólny
`ResilientHttpExecutor` i `ResilienceConfiguration`. Cztery dotychczasowe zestawy
w Gateway, Order, Payment i Customer są małymi adapterami. Nie dodano nowej usługi
ani automatycznej konfiguracji w pozostałych aplikacjach.

## Zachowanie

Wspólny wykonawca zachowuje kolejność: limit równoległości → circuit breaker →
retry → kontrola budżetu czasu → operacja. Circuit breaker mierzy wynik całego
wywołania z ponowieniami, a limit równoległości obejmuje także jego ponowienia.
Rejestry są lokalne dla procesu aplikacji, a stany rozdzielone nazwą zależności.

Wariant `execute(name, operation, false)` nie uruchamia retry. Gateway nadal
stosuje dotychczasową decyzję według metody HTTP, w tym brak automatycznego
ponawiania POST, który mógł już zapisać dane. Wywołania wewnętrznych klientów
pozostają zgodne z dotychczasowymi miejscami użycia; nie wprowadzono globalnego
ponawiania wszystkich żądań ani nowej gwarancji idempotencji.

Błędy HTTP 4xx nie podlegają retry ani klasyfikacji jako awaria zależności.
Przejściowe błędy HTTP/5xx mogą podlegać skonfigurowanym ponowieniom. Otwarty
circuit breaker, pełny limit równoległości, nie-HTTP błąd aplikacji i wygaśnięcie
budżetu nie są przejściową awarią do ponowienia.

Customer wcześniej nie sprawdzał budżetu przed każdą próbą i nie wykluczał
wygaśnięcia z klasyfikacji retry. Teraz również zatrzymuje próby po jego upływie.
Pozostałe trzy implementacje miały już tę kontrolę. Nie zmieniono mechanizmu
timeoutów połączenia, odczytu i całego body ani jego propagacji z A31.

## Konfiguracja i zależności

Nazwy beanów, rejestrów i ustawień `resilience.*` pozostają zgodne. Domyślne wartości:

| Ustawienie | Wartość |
| --- | ---: |
| `circuit-breaker.sliding-window-size` | 20 |
| `circuit-breaker.minimum-number-of-calls` | 5 |
| `circuit-breaker.failure-rate-threshold` | 50% |
| `circuit-breaker.wait-duration-open-seconds` | 10 s |
| `circuit-breaker.half-open-permitted-calls` | 2 |
| `retry.max-attempts` | 3 łącznie z pierwszą próbą |
| `retry.initial-backoff-ms` | 200 ms |
| `retry.backoff-multiplier` | 2 |
| `bulkhead.max-concurrent-calls` | 50 |
| `bulkhead.max-wait-duration-ms` | 0 ms |

Każdy klucz w tabeli ma prefiks `resilience.`. Zachowano trzy istniejące bindery
metryk circuit breaker/retry/bulkhead. Test kontekstu Spring sprawdza dziedziczenie
ustawień i osobne rejestry dwóch aplikacji.

Cztery zależności Resilience4j w bibliotece mają `optional=true`. Cztery aplikacje
nadal jawnie deklarują własne zależności wykonawcze. Pozostałe 11 nie dostaje
bibliotek Resilience4j ani aktywnej konfiguracji. Wersje nie zostały podniesione.

## Kontrole i budowanie

```bash
./mvnw clean package
python3 scripts/qa/resilience-layout-check.py --jars
python3 scripts/qa/http-transactions-e2e.py
```

Czysta przebudowa usuwa m.in. stare lokalne klasy zagnieżdżonego wyjątku.
Kontrola bez `--jars` wykrywa duplikację polityki, globalną aktywację i utratę
opcjonalności zależności. Wariant z `--jars` sprawdza 15 aplikacji i izolację
Resilience4j. CI wymaga obu kontroli; istniejący test A31 pozostaje egzekwowany.

Testy obejmują 19 nowych przypadków Java: HTTP 4xx/5xx, brak retry, liczenie
wyników circuit breakera, niezależne nazwy zależności, pełny limit równoległości,
oddanie pozwolenia, wygasły budżet oraz konfigurację Spring. Istniejące testy
Gateway sprawdzają na rzeczywistym HTTP brak powtórzenia POST i ponowienia GET.
Cztery nowe testy Python sprawdzają kontrolę źródeł i zależności.

Odbiór lokalny: **511 testów Java i 145 Python — PASS**, bez błędów i pominięć
Java. Pełny reaktor zbudowano od czystych katalogów `target`. Porównanie wykazało
zgodny zestaw bibliotek w każdym z 15 wykonywalnych JAR-ów. Cztery aplikacje
mają dotychczasowe biblioteki Resilience4j; pozostałe 11 nadal ich nie ma.
Raport: `tmp/a42-3-packaging-report.json`.

Regresja A31 na trzech rzeczywistych JVM Order/Payment/Notification i własnym
PostgreSQL przeszła. Przy puli po jednym połączeniu szybka saga zakończyła się,
gdy druga czekała na zależność; test potwierdził zwolnienie DB oraz ograniczenie
zawieszonych wywołań Payment/PDF/SMTP. Raport:
`tmp/http-transactions-b54c8a1e/report.json`. Ponownie przeszła też regresja
korelacji Gateway/Recommendation: 17 kontroli HTTP i osiem równoległych żądań,
raport `tmp/audit-a42-2-a13c520d/report.json`. Użyto własnych procesów, kontenerów,
portów i wygenerowanych danych; nie używano profili ani sekretów użytkownika.

Logi lokalne: `tmp/a42-3-build.log`, `tmp/a42-3-python.log`,
`tmp/a42-3-targeted.log`, `tmp/a42-3-http-e2e.log`.

Pełne A42 pozostaje częściowo otwarte: dalszy podział dużych plików sklepu i CC
oraz pozostałych powielonych mechanizmów wymaga kolejnych pakietów.
Nie wdrażano na dev/prod ani nie zmieniano baz użytkownika. A05, całe A41
i zmiany samych zdjęć pozostają odłożone. Nie konsolidowano aplikacji ani JVM.
