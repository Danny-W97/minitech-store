# A28 — zbiorcza dostępność katalogu

Katalog renderuje produkty po pobraniu oferty, bez oczekiwania na magazyn,
historię cen i ranking. Dane dodatkowe uzupełniane są niezależnie. Awaria
magazynu lub brak historii ceny nie usuwa produktów z widoku.

## Odczyt magazynowy

`GET /inventory/items/batch?productIds=1,2,3` jest publicznym odczytem tak jak
dotychczasowe `GET /inventory/items/{productId}`. Przyjmuje od 1 do 100 dodatnich
ID. Powtórzenia są usuwane, a znalezione rekordy wracają w kolejności pierwszego
wystąpienia ID. Brakujące pozycje są pomijane; pusta lista odpowiedzi nie jest
informacją o zerowym stanie.

Odpowiedź jest listą obiektów o tym samym kształcie co odczyt pojedynczy:
`productId`, `availableQuantity`, `reservedQuantity`, `soldQuantity`,
`createdAt`, `updatedAt`. Jeden batch wykonuje jedno zapytanie SQL z `IN`,
korzystając z istniejącego unikalnego indeksu `product_id`. Nie blokuje wierszy
do zapisu i nie zmienia rezerwacji. Niepoprawne ID/format lub ponad 100 pozycji
dają 400. Uprawnienia operacji zapisu pozostają dotychczasowe.

## Ładowanie w przeglądarce

- Pierwsza grupa obejmuje do 24 nowo wyrenderowanych kart. Kolejne karty są
  uzupełniane, gdy wchodzą do widoku (`IntersectionObserver`, margines 80 px).
  Osobno uwzględniane są produkty koszyka, hero i bieżących ofert promocyjnych.
- Żądania są łączone i deduplikowane, po maksymalnie 100 ID. W danej generacji
  katalogu najwyżej dwa żądania magazynowe działają równocześnie.
- Początkowa liczba odczytów magazynowych nie zależy liniowo od całego katalogu.
  Przewijanie, filtrowanie i nowe sekcje dociągają potrzebne ID. Jawny wybór
  sortowania po dostępności pobiera dane całego przefiltrowanego zestawu,
  nadal w grupach i z limitem równoległości.
- Karty są renderowane przed stanami. Późniejsze uzupełnienie zmienia teksty,
  przyciski i pasek dostępności; nie odtwarza elementów zdjęć. Sortowanie po
  stanie przestawia istniejące karty po zakończeniu odczytów.
- Trzy ceny historyczne ofert dnia są pobierane z limitem dwóch równoległych
  żądań. Ranking również nie blokuje katalogu.

Lista produktów i jej DOM nadal obejmują dotychczasowy zestaw katalogu.
Ta zmiana ogranicza dodatkowe odczyty stanów; paginacja list pozostaje osobnym
zakresem A29. Bez `IntersectionObserver` działa pierwsza grupa oraz kolejne
grupy po przefiltrowaniu/przerysowaniu listy.

## Deadline, anulowanie i spójność

| Odczyt | Limit czasu |
| --- | ---: |
| Produkty katalogu | 8 s |
| Jeden batch stanów | 3,5 s |
| Jedna cena historyczna lub ranking | 3,5 s |

Deadline obejmuje fetch i odczyt treści. Odświeżenie katalogu anuluje jego
poprzednią generację, kolejkę i żądania w toku. Stara odpowiedź nie nadpisuje
nowszych danych. Te limity dotyczą publicznych odczytów; nie zmieniają
ponowień ani zachowania zapisów zamówień/płatności.

Publiczne odczyty nie przesyłają JWT ani cookies klienta i nie uruchamiają
odświeżania sesji po błędzie metadanych. Nie ma fallbacku uruchamiającego N
indywidualnych żądań do magazynu po awarii batchu.

Nieznana, brakująca lub niepoprawna dostępność jest przedstawiana jako brak
danych, nie jako zero. Przycisk dodawania jest blokowany przy potwierdzonym
zerowym stanie. Widok dostępności jest migawką danej generacji katalogu.
Przed sprawdzeniem koszyka frontend wymusza świeży batch, z pominięciem
wcześniejszego wyniku i z priorytetem przed kolejką danych tła.
Po potwierdzonej płatności odczyt stanów kupionych produktów działa w tle,
bez opóźniania komunikatu o udanym zakupie i bez odtwarzania kart.
Ostateczną ochronę przed równoległym wykupieniem towaru
nadal zapewnia rezerwacja w Inventory Service; odczyt dostępności sam nie
rezerwuje sztuk. Awaria odczytu nie tworzy fikcyjnej ilości dostępnej.

## Wdrożenie i testy

Najpierw przebuduj i uruchom Inventory Service ze wspólnym modułem bezpieczeństwa:

```bash
./mvnw -pl inventory-service -am package
```

Następnie opublikuj spójny frontend: `index.html`, `catalog.html`,
`catalog-data.js` i `app.js`, oraz odśwież otwarte karty. Wersjonowanie JS
uwzględnia tę aktualizację. Starszy pojedynczy odczyt magazynu pozostaje
dostępny. Ten pakiet nie wymaga migracji baz danych ani zmiany sekretów.

```bash
pnpm run test:unit
pnpm run test:ui
python3 scripts/qa/access-control-e2e.py
```

Testy Java mierzą pojedyncze zapytanie i liczbę załadowanych wierszy na 1000
rekordów H2 oraz zachowują regresję współbieżnych rezerwacji. Testy przeglądarki
obejmują 1000 produktów, blokowany/zepsuty magazyn, stare odpowiedzi,
sortowanie, niezmienność elementów zdjęć i cały proces zakupu. Regresja HTTP
sprawdza batch przez Gateway i bezpośrednio na rzeczywistych usługach oraz
odrębnych bazach PostgreSQL.

Zakres dotyczący zdjęć nadal jest odłożony. Nie zmieniono ich plików, formatów,
rozdzielczości, adresów, srcset, atrybutów ładowania ani polityki cache.
