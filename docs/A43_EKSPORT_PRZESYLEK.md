# A43.22 — eksport danych własnej przesyłki

`GET /api/v1/deliveries/me/data-export/orders/{orderId}` pobiera JSON jednej
przesyłki własnego zamówienia. Działa również alias `/api` i bezpośredni
port Delivery. Wymagany jest access JWT aktywnego klienta; operator, token
usługi oraz anonim nie mają dostępu.

Delivery nie przechowuje właściciela w tabeli przesyłek. Na każde żądanie
odpytuje Order przez istniejące `/internal/orders/{id}/access`, korzystając
z własnej tożsamości usługi. Porównuje customerId z uwierzytelnionym klientem.
Nie przekazuje JWT klienta ani capability gościa. Parametr customerId,
nagłówki, zgodny e-mail i poprawny dostęp gościa nie przypisują zamówienia
do konta. Dotychczasowe reguły zwykłego podglądu trackingu pozostają osobne.

Cudze, gościnne i brakujące zamówienie dają jednakowe 404. Własne zamówienie
bez przesyłki również daje 404. Niedostępna weryfikacja, nieprawidłowa/pusta
odpowiedź i błędne uprawnienia między usługami dają 503. Wtedy baza przesyłek
nie jest odczytywana. Weryfikacja nie jest cache'owana. Klient wewnętrzny
ma istniejące limity połączenia/odczytu i budżet żądania.

Załącznik `shipment-data.json` zawiera schemaVersion 1, scope
`shipment-for-order`, complete false, generatedAt, shipment i omissions.
Jawne pola shipment: shipmentId, orderId, trackingNumber, carrier,
methodCode, status, createdAt, updatedAt. Są to wartości zapisane w bazie,
bez odpytywania przewoźnika i bez zmiany statusu.

Pola lease/token, retry, błędów, terminów i rewizji synchronizacji nie są
wybierane z SQL. Historia wcześniejszych stanów nie jest przechowywana
w tej tabeli; eksport zwraca tylko bieżący stan. Pozostałe dane konta
pozostają pending. Weryfikacja w Order i odczyt w Delivery to osobne
operacje: **nie tworzą wspólnej migawki między usługami**. Odpowiedni wpis
jest również w omissions.

Odczyt korzysta z istniejącego unikalnego indeksu order_id, limitu jednego
wiersza i timeoutu zapytania 2 s. Nie dodano migracji ani konfiguracji
runtime. Trzy zależności Testcontainers i profil Failsafe dotyczą wyłącznie
testów Delivery i dziedziczą wersje z nadrzędnego POM.

Odpowiedź ma nagłówki private/no-store, attachment i nosniff. Serwer nie
zapisuje archiwum eksportu; pobrany plik pozostaje kopią zarządzaną przez
użytkownika. Ten etap nie dodaje przycisku sklepu ani widoku CC.

## Weryfikacja

```sh
./mvnw --batch-mode -pl delivery-service,order-service,api-gateway -am -Pintegration verify
python3 scripts/qa/shipment-data-export-e2e.py
python3 scripts/qa/data-lifecycle-check.py
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
```

Testy obejmują politykę ról, weryfikację właściciela przy każdym odczycie,
brak przekazywania sekretów klienta/gościa, awarie źródła uprawnień,
projekcję PostgreSQL z rolą bez SELECT pól synchronizacji i niezmienność
danych. Odbiór JAR używa własnych Customer/Order/Delivery/Gateway, losowych
portów/poświadczeń i osobnych baz. Po zatrzymaniu własnego Order wszystkie
trzy ścieżki eksportu muszą odmówić dostępu kodem 503.

Pełny eksport między usługami, usunięcie/anonimizacja i procedury kopii
pozostają otwarte. A05, całe A41 i zmiany samych zdjęć nadal odłożone.


<!-- A43_22_RESULTS -->
## Odbiór lokalny 28.10.2026

- **275 Java — PASS, bez pominiętych**: 121 platform, 33 Gateway,
  86 Order unit, 11 Order PostgreSQL, 19 Delivery unit i pięć Delivery
  PostgreSQL. W sumie 19 nowych testów. Dwa błędy konfiguracji atrap
  poprawiono, następnie ponowiono pełny przebieg Java.
- **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
- **HTTP JAR — PASS**, sześć grup: bezpośredni Delivery, `/api`, `/api/v1`,
  niezmienność danych, OpenAPI i odmowa po zatrzymaniu własnego Order.
- Bramka rejestru — PASS: 14 baz, 49 tabel, 123 migracje, 29 mechanizmów.
  Maven — PASS, 17 modułów. Odbiór HTTP zarejestrowany w CI; zdalnego CI
  nie uruchamiano w tej sesji.

Raport: `tmp/audit-a43-22/report.json`. Logi:
`tmp/a43-22-maven-verify-final.log`, `tmp/a43-22-python.log`,
`tmp/a43-22-http-e2e.log`. Raport HTTP: `tmp/shipment-export-ea0046b0/report.json`.
Nie odczytywano ani nie zmieniano danych kont użytkownika, nie wdrażano
dev/prod, nie restartowano CC i nie budowano obrazów aplikacji.
<!-- /A43_22_RESULTS -->
