# A43.23 — eksport własnych zwrotów i reklamacji

Aktywny klient może pobrać osobne strony JSON:

- `GET /api/v1/returns/me/data-export?size=50&cursor=0` — zgłoszenia;
- `GET /api/v1/returns/me/data-export/{returnId}/items?size=50&cursor=0`
  — pozycje wybranej własnej sprawy;
- `GET /api/v1/returns/me/data-export/{returnId}/events?size=50&cursor=0`
  — ograniczona projekcja historii statusów.

Działają także alias `/api` i bezpośredni port Returns. Wymagany jest własny
access JWT; operator, token usługi i anonim są odrzucani. Podmiot pochodzi
z uwierzytelnienia, a nie parametrów/nagłówków lub zapisanego e-maila.
Istniejący pełny podgląd historii operatora nadal wymaga operatora.

Limit wynosi 1–50 rekordów, początek to cursor 0. Następną stronę pobiera się
przez niezmieniony tekstowy nextCursor, gdy hasMore jest true. Kursor
zgłoszeń i zdarzeń odnosi się do ID rekordu; kursor pozycji do orderItemId,
wykorzystując istniejący unikalny indeks (return_id, order_item_id).
Nie jest numerem strony ani ID wiersza return_lines. Nieprawidłowe okna
dają 400. Odpowiedzi mają schemaVersion 1, complete false, generatedAt,
omissions i scope returns-page, return-items-page lub return-events-page.

Zgłoszenie zawiera returnId, orderId, historyczny customerEmail, requestType,
reason, description, status, createdAt i updatedAt. Eksport zachowuje oba
rodzaje spraw i wszystkie zapisane statusy, także starsze COMPLETED.
Tekst autora jest zwracany dosłownie i może zawierać dane innych osób;
eksport nie wykonuje semantycznej redakcji.

Pozycja zawiera lineId, orderItemId, productId, productName, orderedQuantity
i quantity. Zdarzenie zawiera wyłącznie eventId, action, fromStatus,
toStatus i createdAt. Nie są wybierane notatki operatorów, actor_ref,
request_hash, wersje, identyfikatory operacji, termin weryfikacji zamówienia
i wewnętrzne migawki stanów Order/Payment. Notatki i tożsamości wymagają
odrębnej oceny projekcji; ten eksport ich nie obejmuje.

LEGACY_SNAPSHOT zachowuje oryginalne oznaczenie i zapisaną datę migawki
migracyjnej. **Nie jest datą pierwotnej decyzji operatora.** Starsze sprawy
mogą nie mieć pozycji lub pełnej historii. Eksport nie odtwarza brakujących
danych i nie tworzy sztucznych zdarzeń. Te ograniczenia są w omissions.

## Odczyt i izolacja

Każde zapytanie odczytuje maksymalnie size + 1 wierszy i ma timeout 2 s.
Nagłówki korzystają z indeksu (customer_id, id DESC), zdarzenia z istniejącego
(return_id, id DESC), a pozycje z unikalnego indeksu powiązania z pozycją
zamówienia. Nie dodano migracji ani zależności runtime; Testcontainers
i profil Failsafe dotyczą tylko testów Returns.

Własność jest ustalana po return_requests.customer_id. Przy odczycie dzieci
jest sprawdzana w tej samej migawce SQL przez CTE i lateral join. Cudza
i brakująca sprawa dają jednakowe 404. Własna sprawa bez dzieci oraz strona
po końcu listy zwracają 200 i pustą listę. Eksport danych już zapisanej
sprawy nie wymaga działającego Order; nie weryfikuje ponownie uprawnień
do zakupu ani warunków przyjęcia/rozpatrzenia zwrotu.

Strony są osobnymi migawkami: zmiany między żądaniami mogą zmienić wynik.
Nie ma wspólnej migawki eksportu konta. Pobieranie nie zmienia statusu,
wersji, pozycji ani historii i nie uruchamia zwrotu pieniędzy lub zapasu.

Stałe nazwy plików: returns-data-page.json, return-items-data-page.json,
return-events-data-page.json. Nagłówki: private/no-store, attachment,
nosniff. Serwer nie przechowuje archiwum; pobrane pliki są kopiami
zarządzanymi przez klienta. Brak nowego przycisku sklepu lub widoku CC.

## Weryfikacja

```sh
./mvnw --batch-mode -pl returns-service,api-gateway -am -Pintegration verify
python3 scripts/qa/return-data-export-e2e.py
python3 scripts/qa/data-lifecycle-check.py
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
```

Testy obejmują ograniczone strony, indeksowany kursor pozycji, dwa konta,
stare/puste sprawy, wszystkie statusy, brak zapisów i PostgreSQL z rolą bez
SELECT notatek, tożsamości operatorów oraz danych operacji. Odbiór JAR używa
własnych Customer/Returns/Gateway, losowych portów/poświadczeń i osobnych baz.

Pełny eksport między usługami, ocena notatek/historii bezpieczeństwa,
usunięcie/anonimizacja, kopie i decyzje retencji nadal otwarte. A05, całe
A41 i zmiany samych zdjęć pozostają odłożone.


<!-- A43_23_RESULTS -->
## Odbiór lokalny 28.10.2026

- **186 Java — PASS, bez pominiętych**: 122 platform, 33 Gateway,
  21 Returns unit i 10 Returns PostgreSQL. W sumie 17 nowych testów;
  istniejące testy procesu zwrotów również przeszły.
- **246 Python — PASS, bez pominiętych**, w tym dotychczasowe 12 Kubernetes.
- **JAR HTTP — PASS**, pięć grup: Returns, `/api`, `/api/v1`, niezmienność
  danych i OpenAPI. Po 55 zgłoszeń, 105 pozycji i zdarzeń na każdej ścieżce;
  izolacja kont, stare/puste sprawy, odmowy i błędne okna. Po poprawieniu
  kolizji nazwy zmiennej w skrypcie HTTP ponowiono cały odbiór HTTP.
- Rejestr — PASS: 14 baz, 49 tabel, 123 migracje, 30 mechanizmów.
  Maven — PASS, 17 modułów. Odbiór zarejestrowany w CI; bez zdalnego runu.

Raport: `tmp/audit-a43-23/report.json`. Logi:
`tmp/a43-23-maven-verify.log`, `tmp/a43-23-python.log`,
`tmp/a43-23-http-e2e.log`. HTTP: `tmp/return-export-ec94682d/report.json`.
Bez danych użytkownika, migracji na jego bazach, zmian domenowych/retencji,
restartu CC, budowania obrazów aplikacji i wdrożenia dev/prod.
<!-- /A43_23_RESULTS -->
