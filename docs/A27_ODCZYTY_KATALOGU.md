# A27 — odczyty produktów i zbiorcza wycena koszyka

Odczyt szczegółów pobiera pojedynczy produkt. Checkout pobiera wszystkie
produkty koszyka jednym żądaniem do Product Service, który przekazuje do
Promotion Service tylko wybrane pozycje. Pełny katalog nadal jest dostępny
przez dotychczasowe `GET /products`.

## Koszt odczytów

Dla katalogu mieszczącego się w skonfigurowanym limicie cache:

| Operacja | Encje produktów | SQL w Product DB po rozgrzaniu indeksu | Wywołania wyceny promocji |
| --- | ---: | ---: | ---: |
| Szczegóły jednego produktu | 1 | 2 zapytania, 2 zwrócone wiersze | 1, z jednym produktem |
| Koszyk z K różnymi produktami | K | 2 zapytania, K + 1 zwróconych wierszy | 1, z K produktami |
| Pełny katalog | N | 1 zapytanie zwracające N produktów | 1, z N produktami |

Pierwszy selektywny odczyt po starcie, zmianie wersji katalogu lub wygaśnięciu
indeksu wykonuje dodatkowe zapytanie o posortowane identyfikatory. Pobiera
tylko ID, bez opisów i parametrów pozostałych produktów. Indeks jest wspólny
dla odczytów w danej instancji. Przeliczanie listy i budowanie payloadu są
liniowe; fallback jest wykonywany tylko dla brakującej oferty, w czasie O(1)
na produkt. Usunięto `indexOf` w pętli i zachłanne `getOrDefault`.

Liczby SQL dotyczą Product DB. Promotion Service nadal prowadzi historię
wybranych produktów we własnej bazie. Podane wywołania HTTP dotyczą wyceny,
bez osobnej weryfikacji tożsamości, dostawy, rezerwacji i ewentualnych ponowień.
W zwykłym checkoutcie jest jedno wywołanie Product oraz jedno Promotion,
niezależnie od liczby pozycji do limitu 100. Polityka ograniczonych ponowień
Order Service nadal obowiązuje przy awarii.

## Indeks i zgodność cen

Cache zawiera wyłącznie mapę ID → pozycja oraz rozmiar katalogu. Ceny, oferty,
aktywność produktu i dane prezentacyjne są odczytywane na bieżąco. Zmiana ceny
jest widoczna przy następnym odczycie, bez czekania na TTL. Rotacja nadal
uwzględnia wszystkie produkty w kolejności ID, również nieaktywne, zgodnie
z dotychczasowym algorytmem. Dzień rotacji jest ustalany według UTC.

Migracja `V10__version_catalog_layout.sql` dodaje jeden wiersz metadanych
z numerem generacji i liczbą produktów. Triggery INSERT, DELETE, TRUNCATE
i UPDATE identyfikatora aktualizują je w tej samej transakcji co katalog,
także przy zapisie SQL poza API. Zmiany cen/opisów nie przebudowują indeksu ID.
Inicjalizacja licznika i instalacja triggerów odbywają się pod blokadą zapisów
do tabeli produktów, aby istniejąca instancja nie wprowadziła pominiętego wpisu.

Przy każdym selektywnym odczycie instancja sprawdza wersję w bazie. Inna
generacja unieważnia lokalny indeks także w pozostałych replikach, bez oczekiwania
na TTL. Odczyty produktu i koszyka działają w `REPEATABLE_READ`, więc dane,
wersja i pozycje pochodzą z jednego obrazu bazy. Transakcje zapisujące nie
publikują niezatwierdzonych ID w cache, a starszy obraz bazy nie zastępuje
współdzielonej nowszej generacji.

| Zmienna | Domyślnie | Znaczenie |
| --- | ---: | --- |
| `CATALOG_LAYOUT_TTL_MS` | 30000 | Maksymalny wiek indeksu ID; 0 wyłącza cache, maksimum 3600000 |
| `CATALOG_LAYOUT_MAX_PRODUCTS` | 10000 | Limit liczby ID w cache; zakres 1–1000000 |

Po przekroczeniu limitu liczby ID albo po wyłączeniu cache serwer oblicza
pozycje w SQL i zwraca tylko metadane wybranych produktów. Ten tryb ogranicza
pamięć aplikacji, ale koszt wyznaczania pozycji w bazie rośnie wraz z katalogiem.
Zimny indeks również wymaga jednorazowego odczytu ID; pomiary dwóch zapytań
dotyczą kolejnych odczytów przy ważnej, zapełnionej generacji.

Parametry są dostępne w konfiguracji aplikacji, Compose i szablonie Kubernetes.
TTL dotyczy metadanych ID, nie jest okresem przechowywania ceny.

## Kontrakt batch i błędy

`GET /internal/products/batch?ids=1,2,3` jest dostępny wyłącznie dla
uwierzytelnionej usługi `order-service`. Klient, gość, operator i inne usługi
nie uzyskują dostępu. Wymagane jest 1–100 dodatnich ID. Duplikaty są usuwane,
a znalezione produkty wracają w kolejności pierwszego wystąpienia w żądaniu.
Brakujące ID są pomijane; Order Service wskazuje właściwy brakujący produkt
i zwraca 404 przed zapisem zamówienia oraz rezerwacją.

Order Service odrzuca zduplikowane/obce ID w odpowiedzi, nieprawidłowe ceny
i pustą odpowiedź transportową. Produkt nieaktywny nadal daje 409. Awaria
Product Service daje 503, bez przejścia na cenę przesłaną przez klienta.
Przy awarii Promotion Service działa dotychczasowa rotacja lokalna, z pełną
pozycją i rozmiarem katalogu. Limit wejścia checkoutu wynosi 100 wierszy;
powtarzające się produkty są sumowane z ochroną przed przepełnieniem.

## Wdrożenie i weryfikacja

1. Przebuduj Product i Order ze wspólnym modułem zabezpieczeń:
   `./mvnw -pl product-service,order-service -am package`.
2. Uruchom nowy Product Service. Flyway zastosuje V10 do `product_db`.
   Migracja dodaje metadane i triggery; nie zmienia cen, zdjęć ani danych
   istniejących produktów. Na czas instalacji blokuje zapisy katalogu.
3. Po aktualizacji wszystkich replik Product uruchom nowy Order Service.
   Nowy Order wymaga endpointu batch; jego brak powoduje 503 przy checkoutcie.
   Starszy Order jest zgodny z nowym publicznym odczytem pojedynczego produktu.

Compose wskazuje Product Service na właściwy adres Promotion Service
(`http://promotion-service:8080`). Wcześniejsza wartość domyślna localhost
w kontenerze prowadziła do fallbacku zamiast zdalnej wyceny.

Testy:

```bash
./mvnw -pl product-service,order-service -am test
python3 scripts/qa/catalog-pricing-e2e.py
python3 scripts/qa/access-control-e2e.py
```

Scenariusz A27 wymaga spakowanych JAR-ów Product/Order/Promotion, Java 25
i Dockera. Tworzy własny PostgreSQL z `pg_stat_statements`, dwie instancje
Product i rzeczywiste usługi Promotion/Order. Pozostałe zależności mają
deterministyczne atrapy. Liczy wywołania HTTP oraz zapytania i zwrócone wiersze
bazy dla katalogu powiększonego o 1000 produktów. Pełny scenariusz regresji
osobno weryfikuje rzeczywiste usługi i ich poświadczenia. Nie używa baz dev.
