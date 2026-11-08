# A43.25 — eksport własnych faktur, pozycji, korekt i PDF

Aktywny klient może pobrać dane istniejącej faktury własnego zamówienia:

- `GET /api/v1/invoices/me/data-export/orders/{orderId}` — nagłówek i adres PDF;
- `GET /api/v1/invoices/me/data-export/orders/{orderId}/items?size=50&cursor=0`
  — strona pozycji;
- `GET /api/v1/invoices/me/data-export/orders/{orderId}/corrections?size=50&cursor=0`
  — strona korekt z adresami ich PDF;
- `GET /api/v1/invoices/me/data-export/orders/{orderId}/pdf` — PDF faktury;
- `GET /api/v1/invoices/me/data-export/orders/{orderId}/corrections/{correctionId}/pdf`
  — PDF konkretnej korekty.

Działają także alias `/api` i bezpośredni port Billing. Identyfikatory własnych
zamówień można pobrać przez A43.21. Ścieżki PDF w JSON są względne wobec API
usługi; przy pobieraniu przez Gateway trzeba zastosować prefiks `/api/v1` lub `/api`.

## Własność i odmowy

Wymagany jest access JWT aktywnego klienta. Operator, usługa i anonim nie
mogą używać endpointów eksportu konta. Order jest źródłem własności: Billing
sprawdza ją na każde żądanie własnym tokenem usługi, bez przekazywania JWT
klienta i capability gościa. E-mail, parametry/nagłówki i lokalne
invoices.customer_id nie przyznają dostępu. Dotyczy to również dokumentów
z pustym lub historycznie niespójnym customer_id; eksport ich nie naprawia.

Cudze, gościnne i brakujące zamówienie dają jednakowe 404. Brak faktury albo
korekty należącej do wybranej faktury daje 404. Niedostępna/błędna weryfikacja
Order daje 503 przed odczytem Billing. Niezgodne order_id korekty i faktury
blokuje pobieraną stronę lub PDF kodem 503; rekord nie jest po cichu pomijany.

Każdy PDF wymaga weryfikacji właściciela oraz powiązań dokumentów **przed
dostępem do cache**, także po jego wcześniejszym rozgrzaniu przez inne konto
lub operatora. Awaria Order nie pozwala pobrać już zapamiętanego PDF.
Dotychczasowy eksport ZIP operatora nie staje się dostępny klientowi.

## Zakres i spójność

Nagłówek zawiera ID faktury/zamówienia, historyczny e-mail, numer, rodzaj
nabywcy, zapisane dane nabywcy/adres/NIP, kwoty netto/VAT/brutto, dostawę,
rabat, walutę, status i czas wystawienia. Pozycja: ID, numer pozycji, produkt,
SKU/nazwa, ilość, cena i suma. Korekta: ID/powiązania, numer, powód,
zapisane kwoty przed/zmiany/po, czas, model salda i odnośnik PDF.

Kwoty nie są przeliczane ani uzgadniane z Order/Payment. Zachowane modele
LEGACY_BASE_DELTA i CUMULATIVE_DELTA opisują historyczny sposób zapisu;
eksport nie zamienia starszych korekt w nowe. Dane nabywcy i powód korekty
są zwracane jak zapisano, bez semantycznej redakcji, i mogą dotyczyć innych osób.

JSON ma schemaVersion 1, complete false, generatedAt i omissions. Zakresy:
invoice-for-order, invoice-items-page, invoice-corrections-page. Projekcje
JSON nie zwracają lokalnego customer_id, kluczy idempotencji, surowej wersji
księgi ani danych inbox/outbox/numeracji. Etykieta modelu salda jest wyliczana
z obecności ledger_version, bez zwracania jego wartości.

PDF wykorzystuje istniejący renderer, zapisane dokumenty i dotychczasowy
cache z limitem/TTL. To reprezentacja dokumentu, **nie archiwalny oryginał
pliku**. Nie zmieniono wyglądu/treści PDF; dotychczasowa prezentacja korekty
może zawierać numer wersji salda. Renderowanie nie wystawia faktury/korekty,
nie zużywa numeru dokumentu i nie publikuje zdarzenia.

Strony JSON mają limit 1–50, pierwszą stronę cursor 0 i tekstowy nextCursor.
Przy hasMore true należy przekazać nextCursor bez zmiany. Kursor pozycji
dotyczy positionNo, a nie ID wiersza; kursor korekt dotyczy ID korekty.
Odczyty są malejące, po maksymalnie size + 1 rekordów, timeout SQL 2 s.
Istniejące indeksy i unikalności wystarczają; brak nowej migracji/zależności.

Pusta własna faktura i strona po końcu kolekcji dają 200 z pustą listą.
Powiązanie faktury i dzieci jest sprawdzane w jednym SQL, natomiast
potwierdzenie właściciela w Order oraz kolejne strony/PDF to osobne odczyty,
bez wspólnej migawki. Limit 50 dotyczy JSON; PDF obejmuje cały dokument
i zachowuje dotychczasowe zachowanie renderera.

Stałe nazwy załączników: invoice-data.json, invoice-items-data-page.json,
invoice-corrections-data-page.json, invoice.pdf, invoice-correction.pdf.
Odpowiedzi mają private/no-store, attachment i nosniff. Pobrane pliki są
kopiami zarządzanymi przez klienta; ten etap nie dodaje archiwum na dysku,
przycisku sklepu ani widoku CC.

## Weryfikacja

```sh
./mvnw --batch-mode -pl billing-service,api-gateway -am -Pintegration verify
python3 scripts/qa/invoice-data-export-e2e.py
python3 scripts/qa/data-lifecycle-check.py
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
```

Testy obejmują dwie tożsamości/gościa, błędne/puste lokalne customer_id,
105 pozycji i 105 korekt, oba modele historii, kwoty/UTF-8 i brak zmian.
Projekcje JSON i SQL sprawdzające dostęp do PDF działają na PostgreSQL z rolą
bez SELECT lokalnego customer_id, idempotency_key, sekwencji oraz inbox/outbox.
Sam renderer PDF korzysta z dotychczasowych repozytoriów i ich uprawnień.

Odbiór JAR używa własnych Customer/Order/Billing/Gateway, losowych portów,
poświadczeń i baz. Sprawdza rozgrzany cache PDF, zgodność z istniejącym
renderingiem i odmowę po zatrzymaniu własnego Order. Snapshot obejmuje
wszystkie sześć tabel Billing, w tym sekwencje, oraz zamówienia.

Etap odczytu nie wymaga nowego wyboru okresów retencji ani aktywacji usuwania.
Gdy następny etap wymaga decyzji o danych, użytkownik otrzyma okno z opcjami
i skutkami; praca zależna czeka na odpowiedź. A05, całe A41 i zmiany samych
zdjęć nadal odłożone. Pełny eksport konta, usuwanie i kopie pozostają otwarte.


<!-- A43_25_RESULTS -->
## Odbiór lokalny 28.10.2026

- **276 Java — PASS, bez pominiętych**: 124 platform, 33 Gateway,
  81 Billing unit i 38 integracyjnych (26 PostgreSQL i 12 Redis).
  W sumie 20 nowych testów, w tym 11 PostgreSQL.
- **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
- **JAR HTTP — PASS**, sześć grup: Billing, `/api`, `/api/v1`, niezmienność
  danych, OpenAPI i odmowa po zatrzymaniu Order dla JSON/rozgrzanych PDF.
- Rejestr — PASS: 14 baz, 49 tabel, 123 migracje, 32 mechanizmy.
  Maven — PASS, 17 modułów. Odbiór dodany do CI; bez zdalnego runu.

Raport: `tmp/audit-a43-25/report.json`. Logi:
`tmp/a43-25-maven-verify.log`, `tmp/a43-25-python.log`,
`tmp/a43-25-http-e2e.log`. HTTP: `tmp/invoice-export-6ce1125b/report.json`.
Bez danych użytkownika, zmian numeracji/dokumentów/zdarzeń/retencji,
restartu CC, budowania obrazów i wdrożenia dev/prod.
<!-- /A43_25_RESULTS -->
