# A43.18 — stronicowany eksport zapisanych produktów

Zalogowany klient może pobrać stronę zapisanych produktów przez
`GET /api/v1/wishlists/me/data-export?size=50&cursor=0`. Dostępny jest też
alias `/api/wishlists/me/data-export` oraz bezpośredni port Wishlist.
Wymagany jest własny ważny access JWT. Operator, anonim i tokeny usług nie
mają dostępu; identyfikator klienta pochodzi z uwierzytelnienia, nie z query
ani nagłówków klienta.

Odpowiedź jest załącznikiem JSON `wishlist-data-page.json` z nagłówkami
`Cache-Control: no-store, private` i `X-Content-Type-Options: nosniff`.
Zawiera:

- `schemaVersion: 1`, `scope: wishlist-page` i `complete: false`;
- `generatedAt` oraz `items` z `productId` i `createdAt`;
- `hasMore` i `nextCursor`, zapisywany jako tekst dla dokładności dużych ID;
- `omissions`, obejmujące pozostałe dane konta i brak wspólnej migawki stron.

`size` może wynosić od 1 do 100, a `cursor` musi być nieujemną liczbą
64-bitową. Pierwsza strona używa `cursor=0`. Gdy `hasMore` jest true,
podaj otrzymany `nextCursor` w następnym żądaniu. Gdy `hasMore` jest false,
nie ma dalszej strony. Nieprawidłowe parametry zwracają 400.

To osobny częściowy eksport Wishlist; nie rozszerza pliku profilu A43.16
ani nie tworzy kompletnego eksportu konta. W tym etapie nie dodano przycisku
sklepu ani widoku CC. Zapisane strony są kopiami zarządzanymi przez użytkownika.

## Spójność i wydajność

Strony są czytane malejąco według wewnętrznego identyfikatora wiersza,
z filtrem uwierzytelnionego właściciela. Zapytanie pobiera maksymalnie
`size + 1` wierszy, aby ustalić `hasMore`, i ma dwusekundowy timeout SQL.
Odpowiedź nie zawiera innych pól wiersza ani danych innych klientów.

Każde żądanie ma osobną migawkę. Nowe pozycje dodane po pierwszej stronie
mogą nie znaleźć się w dalszych stronach; usunięte pozycje mogą zniknąć.
Dlatego odpowiedź jawnie informuje o braku wspólnej migawki. Pełny proces
eksportu między usługami i zasady odtwarzalnego kompletnego zestawu pozostają
otwarte.

Migracja `V2__index_wishlist_export_owner_id.sql` dodaje indeks
`(customer_id, id DESC)` wspierający ograniczone odczyty. Standardowe
`CREATE INDEX` może blokować zapisy do tabeli w czasie budowy; przed
wdrożeniem należy uwzględnić jej rozmiar i warunki operacyjne. Migrację
sprawdzono na izolowanej bazie, nie uruchomiono jej na bazach użytkownika.

## Weryfikacja

```sh
./mvnw --batch-mode -pl wishlist-service,api-gateway -am -Pintegration verify
python3 scripts/qa/wishlist-data-export-e2e.py
python3 scripts/qa/data-lifecycle-check.py
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
```

Testy PostgreSQL działają w profilu `integration` i używają własnego
kontenera; zwykły przebieg testów Wishlist nie wymaga Dockera. Odbiór HTTP
uruchamia własne JAR-y Customer/Wishlist/Gateway, dwie bazy testowe i
tymczasowe poświadczenia. Sprawdza dwa konta, 205 pozycji w trzech stronach,
limity, nagłówki, niedozwolone role, Gateway i kontrakt OpenAPI.

Rejestr opisuje tabelę Wishlist jako `partial-safe-projection`, a globalne
operacje eksportu, usunięcia i backupów nadal pozostają `pending`. Przejrzano
i zapisano hash nowej migracji; wcześniejszych hashy nie nadpisywano.
CI uruchamia testy integracyjne i odbiór HTTP. Zewnętrzne CI nie było uruchamiane.

<!-- A43_18_RESULTS -->
- **151 testów Java — PASS, bez pominiętych**: 109 wspólnego modułu,
  33 Gateway, 4 Wishlist unit i 5 Wishlist PostgreSQL. W sumie dziewięć
  nowych przypadków: jeden polityki uprawnień, trzy HTTP i pięć PostgreSQL.
- **246 testów Python — PASS, bez pominiętych**, w tym dotychczasowe
  12 Kubernetes. Bramka rejestru PASS: 14 baz, 49 tabel, 121 migracji,
  pięć przestrzeni Redis, 15 rodzajów kopii i 25 mechanizmów.
- **Odbiór JAR HTTP — PASS** w pięciu grupach: port Wishlist, `/api`,
  `/api/v1`, niezmienność danych i OpenAPI. Trzy strony/205 pozycji,
  dwie tożsamości, sześć odmów, cztery nieprawidłowe okna i nagłówki.
  Pierwszy test niepoprawnie oczekiwał 403 zamiast 405 z Gateway dla POST;
  poprawiono oczekiwanie i ponowiono odbiór z wynikiem PASS.

Raport końcowy: `tmp/audit-a43-18/report.json`. Logi:
`tmp/a43-18-maven-verify-final.log`, `tmp/a43-18-python.log`,
`tmp/a43-18-http-e2e-final.log`. Raport HTTP:
`tmp/wishlist-export-c41d6964/report.json`.
<!-- /A43_18_RESULTS -->

Nie aktywowano retencji ani nie zmieniono danych kont użytkownika. Nie
wdrażano dev/prod ani nie restartowano Control Center. A05, całe A41
i zmiany dotyczące samych zdjęć pozostają odłożone.
