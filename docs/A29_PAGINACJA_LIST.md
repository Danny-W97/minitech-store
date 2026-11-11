# A29 — paginacja list biznesowych

Zakres: zamówienia i pozycje, płatności, powiadomienia i otwarte dead letters,
klienci, czat, zwroty, opinie i moderacja, faktury oraz korekty. Sklep, CC i CLI
korzystają z ograniczonych stron. Zdjęcia pozostają odłożone na osobne polecenie.

## Kontrakt API

Odpowiedź nadal jest tablicą JSON. `size` ma zakres 1–200 (domyślnie 50),
`cursor=0` rozpoczyna odczyt. Niepoprawny rozmiar/kursor daje HTTP 400.
Metadane są w nagłówkach, dostępnych również przez CORS Gateway:

| Nagłówek | Znaczenie |
| --- | --- |
| `X-Has-More` | Czy istnieje kolejna strona |
| `X-Next-Cursor` | ID ostatniego wiersza jako **tekst**; `0` na końcu |
| `X-Page-Size` | Żądany rozmiar strony |

Większość list ma porządek `id DESC`, a kolejki moderacji i dead letters
`id ASC`. Kolejne żądanie powtarza te same filtry i przekazuje otrzymany kursor.
Nowy filtr wymaga `cursor=0`. Kursor nie jest numerem strony ani offsetem.
W JavaScript nie należy zamieniać go na `Number` — ID mogą przekraczać 2^53.
Dodanie nowszego rekordu nie przesuwa kolejnej strony. Nie jest to snapshot
całego przebiegu: usunięcia i zmiany statusu mogą zmieniać zbiór pomiędzy
żądaniami. Nowe rekordy są dostępne po odświeżeniu pierwszej strony.

| Lista | Filtry serwera |
| --- | --- |
| `/orders` | `status`, `search` (ID/e-mail) |
| `/orders/customer/{id}` | `status`, `productId`; sprawdzany właściciel |
| `/payments` | `status` |
| `/notifications`, `/notifications/source/{id}` | `status` (lista główna), źródło w ścieżce |
| `/chat/messages` | `status`, `search` (e-mail, temat, treść) |
| `/returns` | `status` |
| `/customers` | `search` (e-mail, imię, nazwisko) |
| `/invoices` | `search` (numer, kupujący, firma, NIP, e-mail, zamówienie) |
| Historie klientów, opinie produktu, korekty faktury | zakres w ścieżce |

W Billing zachowana jest wcześniejsza, jawna ścieżka `page=N&size=M`
z `X-Total-Count`; otrzymała rozstrzygnięcie remisów po ID. Nowi klienci
używają kursora bez parametru `page`. Nie ma globalnego COUNT dla każdej
strony kursora. Wyszukiwanie tekstowe ma maks. 200 znaków; wzorce LIKE mogą
wymagać skanowania indeksu/tabeli — limit dotyczy pobieranych wyników,
nie gwarantuje stałego czasu dowolnego filtra tekstowego.

## Odczyty i agregaty

Zamówienia pobierają `size+1` samych ID, następnie encje z pozycjami wyłącznie
dla `size` wybranych zamówień. Oba zapytania są w jednej transakcji
REPEATABLE READ. Nie ma paginacji w pamięci Hibernate nad kolekcją JOIN FETCH.
Pozostałe listy pobierają najwyżej `size+1` rekordów i odrzucają sentinel.

Podsumowanie opinii korzysta z SQL `COUNT/AVG` dla opublikowanych opinii.
Nie zależy od bieżącej strony. Publiczne DTO nadal nie zawierają identyfikatora
klienta/zamówienia ani notatek moderacji (A39).

Rekomendacje odczytują wyłącznie zbiór kupionych ID spośród maks. 100 kandydatów
w żądaniu `GET /internal/orders/customer/{id}/purchased-products?productIds=...`.
Ten endpoint jest dostępny tylko recommendation-service. Zachowana jest
dotychczasowa definicja zakupu (`PAID`), bez pobierania historii zamówień.

CC pobiera jedną stronę i pokazuje Początek/Poprzednia/Następna. Wyszukiwanie
i statusy są przekazywane do serwera; sortowanie kolumn i KPI dotyczą bieżącej
strony, co opisuje pasek nawigacji. Historia korekt otwiera się dla wskazanej
faktury, z własną paginacją. Liczba i suma wszystkich korekt faktury pochodzi
z osobnej agregacji SQL dla ID faktur z bieżącej strony (do 200).
Zakładka wysyłki dokumentów ma osobne kursory faktur i korekt. Magazyn CC
czyta ograniczone okno SQL i dane produktów tylko dla jego ID; filtr magazynu
przyjmuje dokładne ID produktu. Próbki kont/opinii dla lokalnego asystenta
mają limit 100 i są opisane jako próbki. Błędy odczytu nie uruchamiają
nieograniczonego odczytu awaryjnego.

CLI `orders-list`, `invoices-list`, `chat-messages`, `review-pending` i
`returns-list` przyjmują rozmiar strony oraz kursor jako dwa argumenty.
Kursor następnej strony trafia na stderr, dane pozostają dostępne na stdout.
W sklepie starsze zamówienia, opinie i zakupy do opinii pobiera osobny przycisk;
zakupy są filtrowane po `PAID` i ID aktualnego produktu już na serwerze.

## Weryfikacja

```sh
./mvnw --batch-mode package
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
bash scripts/qa/unit/list-pages-test.sh
pnpm run test:ui
pnpm run test:cc
python3 scripts/qa/access-control-e2e.py --pagination
```

Scenariusz HTTP uruchamia rzeczywiste JAR-y i własne kontenery z losowymi
sekretami. Dodaje po 100 tys. rekordów do 11 tabel, sprawdza limity, następne
strony, ostatnią/krótką stronę, stabilność po insercie, filtry, role,
podsumowanie opinii i prywatność DTO. `pg_stat_statements` sprawdza wiersze
zwracane z SQL, a `EXPLAIN (ANALYZE, BUFFERS)` indeksy dla głębszych stron.
Raport, plany i logi pozostają w `tmp/access-control-e2e-*`.
Testy JPA dodatkowo sprawdzają brak hydratacji encji przez agregat i wymuszają
błąd przy próbie paginacji kolekcji w pamięci.

## Wdrożenie

Przebudować wszystkie usługi ze wspólnym `platform-security`. Najpierw
zaktualizować repliki Order, potem Recommendation (nowy wewnętrzny kontrakt).
Przed włączeniem stronicowania w UI/API należy zaktualizować wszystkich
odbiorców, które wcześniej zakładały pełne listy. Zalecane jedno okno
wdrożenia Gateway, usług, CC/CLI i frontendu oraz odświeżenie kart.

Flyway dodaje indeksy: Order V13, Payment V7, Notification V6, Chat V3,
Returns V2, Review V2 i Billing V5. Zwykłe CREATE INDEX wymaga zaplanowanego
okna dla dużych tabel; nie zakładać braku blokad przy migracji produkcyjnej.
Zmiany nie wymagają rotacji sekretów ani migracji zdjęć.
Eksport ZIP i cache PDF pozostają osobnym otwartym zadaniem A30.
