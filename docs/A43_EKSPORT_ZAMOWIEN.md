# A43.21 — eksport własnych zamówień i pozycji

Zalogowany właściciel może pobrać osobne, ograniczone strony:

- `GET /api/v1/orders/me/data-export?size=50&cursor=0` — dane zamówień;
- `GET /api/v1/orders/me/data-export/{orderId}/items?size=50&cursor=0`
  — pozycje wybranego własnego zamówienia.

Obsługiwany jest również alias `/api` i bezpośredni port Order. Wymagany jest
własny access JWT. Operator, token usługi i anonim nie mają dostępu. Podmiot
pochodzi z uwierzytelnienia, a nie parametrów/nagłówków. Nie przypisujemy
zamówień gościa na podstawie zgodności e-maila ani capability zamówienia.

Oba endpointy mają limit od 1 do 50. Pierwsza strona używa `cursor=0`.
Jeśli `hasMore` jest true, następna strona używa tekstowego `nextCursor`.
Nieprawidłowe okna są odrzucane kodem 400. Odpowiedzi mają `complete: false`,
czas `generatedAt`, `omissions` i zakres odpowiednio `orders-page` lub
`order-items-page`.

Dane zamówienia zawierają jego ID, sumę/status, zapisany kontakt i adres
odbiorcy, sposób/koszt dostawy, rabat/kupon, wybór dostawy i flagę faktury,
czasy utworzenia/zmiany. Opcjonalne `legacyLine` zachowuje dawne pola
pojedynczej pozycji (`productId`, `quantity`, `unitPrice`), jeśli nadal są
zapisane w nagłówku. Nie zamienia ich w sztuczny rekord współczesnych pozycji.

Pozycje obejmują ID pozycji, produkt, SKU/nazwę, ilość i ceny. Dane są
kopią zapisanych wartości; eksport nie przelicza sum ani nie uzgadnia
płatności/zwrotów. Nie wybiera kluczy idempotencji, hashy dostępu gościa,
tożsamości/hashu checkoutu, technicznych danych sagi, kolejek i lease.
Pozostałe dane usług i szczegóły procesów domenowych pozostają poza zakresem.

Odbiorca zamówienia może być inną osobą. Dane dostawy są zwracane jak
zapisano, bez semantycznej redakcji; wskazuje to manifest. Strony mają osobne
migawki, więc nie tworzą spójnego zestawu między żądaniami. Plik profilu
A43.16 nie jest rozszerzany o te dane. W tym etapie nie dodano przycisku
sklepu ani widoku CC. Pobrane pliki pozostają kopiami zarządzanymi przez klienta.

Załączniki mają stałe nazwy `orders-data-page.json` i
`order-items-data-page.json`, nagłówki private/no-store i nosniff.

## Odczyt i uprawnienia

Dane zamówień są czytane w jednym zapytaniu po właścicielu i ID, maksymalnie
`size + 1` wierszy. Pozycje są pobierane oddzielnie, aby jedno duże zamówienie
nie wymuszało wczytania całej kolekcji do pamięci.

Zapytanie pozycji w tej samej migawce sprawdza własność zamówienia i czyta
ograniczoną stronę przez lateral join. Własne zamówienie bez pozycji lub
strona po końcu listy daje 200 z pustą listą. Cudze, gościnne i nieistniejące
zamówienie dają jednakowe 404. Timeout obu zapytań wynosi dwie sekundy.

Używany jest istniejący indeks właściciela nagłówków; migracja V22 dodaje
indeks `(order_id, id DESC)` dla pozycji. Standardowy CREATE INDEX może
czasowo blokować zapisy; przy wdrożeniu trzeba uwzględnić wielkość tabeli.
Migrację sprawdzono wyłącznie na bazie testowej. Polityki retencji i dane
domenowe nie zostały zmienione.

## Weryfikacja

```sh
./mvnw --batch-mode -pl order-service,api-gateway -am -Pintegration verify
python3 scripts/qa/order-data-export-e2e.py
python3 scripts/qa/data-lifecycle-check.py
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
```

Testy obejmują oddzielne strony nagłówków i 105 pozycji, dwa konta,
zamówienie gościa z tym samym e-mailem, puste własne zamówienie, błędy,
historię starszych pól i brak zapisów. Rola PostgreSQL bez SELECT do
sekretów i tabel sagi potrafi odczytać oba eksporty. Odbiór JAR działa na
własnych Customer/Order/Gateway i bazach testowych; nie wykonuje zakupu,
płatności, anulowania ani odłożonych operacji A05.

<!-- A43_21_RESULTS -->
- **242 Java — PASS, bez pominiętych**: 112 wspólnego modułu, 33 Gateway,
  86 Order unit i 11 PostgreSQL. W sumie 16 nowych: jeden polityki uprawnień,
  pięć HTTP i dziesięć PostgreSQL. Dotychczasowy test sagi jest już zawarty
  w 11 PostgreSQL i również przeszedł.
- **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
  Bramka rejestru PASS: 14 baz, 49 tabel, 123 migracje i 28 mechanizmów.
  Układ Maven 17 modułów — PASS.
- **JAR HTTP — PASS**: port Order, `/api`, `/api/v1`, niezmienność danych
  i OpenAPI. Sprawdzono 55 własnych nagłówków i 105 pozycji w ograniczonych
  stronach, dwa konta, zamówienie gościa o tym samym e-mailu, matching guest
  capability, puste i brakujące zamówienie, sześć odmów oraz błędne okna.
  Kwoty, UTF-8 i legacyLine zachowane; sekrety i dane sagi wykluczone.

Po rozszerzeniu o legacyLine ponowiono pełne Java/Python na ustalonym
kontrakcie. Pierwszy przebieg Python wczytał wcześniejszą kopię kontraktu
w trakcie jego zmiany; tego nieudanego przebiegu nie traktujemy jako dowodu PASS.

Raport: `tmp/audit-a43-21/report.json`. Logi:
`tmp/a43-21-maven-verify-final.log`, `tmp/a43-21-python-final.log`,
`tmp/a43-21-http-e2e.log`. Raport HTTP:
`tmp/order-export-039469f0/report.json`.
<!-- /A43_21_RESULTS -->

Pełny eksport między usługami, historia bezpieczeństwa/redakcja audytu,
usuwanie/anonimizacja i kopie/restore pozostają otwarte. Nie zmieniano danych
kont użytkownika, nie restartowano CC i nie wdrażano dev/prod. A05, całe A41
oraz zmiany samych zdjęć nadal odłożone.
