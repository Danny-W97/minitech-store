# A18 — wiarygodna historia cen

## Zakres wdrożenia lokalnego

Źródłem historii obowiązującej ceny jest teraz `product-service`. Migracja
Product V11 dodaje wersję ceny produktu i tabelę niezmiennych zdarzeń. Utworzenie
produktu zapisuje zdarzenie `CREATE`, każda zmiana kwoty zapisuje osobne
`PRICE_CHANGE` w tej samej transakcji co produkt. Utworzenie produktu zmienia
też pozycje katalogu. Product porównuje oferty przed i po zmianie układu i
zapisuje tylko zmienione ceny pod nową rewizją `layoutRevision`. Zmiany innych
pól nie tworzą zdarzeń cenowych.

Product zapisuje też migawkę ceny efektywnej raz dziennie o 00:05 UTC i przy
uruchomieniu. Migawka jest idempotentna dla replik i zapisuje się nawet wtedy,
gdy klienci nie odwiedzają produktu. Błąd zadania albo brak dnia pozostawia lukę
w historii. Limit katalogu dla migawek korzysta z `catalog.layout.max-products`
(domyślnie 10 000); przekroczenie limitu przerywa zapis danego dnia zamiast
tworzyć niepełną migawkę.

Ten sam limit chroni utworzenie produktu, które może zmienić oferty całego
katalogu. Przekroczenie limitu wycofuje transakcję tworzenia. Operator może
ustawić osobny `price-history.snapshot.max-products` dla obsługiwanego katalogu.

Istniejące produkty dostają przy migracji wpis `BASELINE` z bieżącą ceną bazową.
Nie jest on uznawany za wcześniejszą obserwację ani za cenę promocyjną. Historia
sprzed instalacji nie jest odtwarzana ani dopowiadana.

Promotion udostępnia dotychczasowe endpointy historii. Łączy nowe zdarzenia
Product z wcześniejszymi wierszami oznaczonymi `LEGACY_OBSERVATION`. Odczyt
historii i przeliczenie promocji nie zapisują nowych obserwacji. Wskaźnik
najniższej ceny pobiera cenę i pokrycie wyłącznie z Product. Odpowiedź zawiera
`observedDays` i `historyComplete`; pole `lowestPrice` jest `null`, jeżeli
brakuje choć jednego dnia w żądanym zakresie 1–30 dni albo źródło jest niedostępne.
Pokrycie daty daje migawka `DAILY` lub zdarzenie utworzenia nowego produktu;
zmiana ceny i układu aktualizuje najniższą cenę dnia, ale nie maskuje braku
dziennej migawki istniejącego katalogu. Historyczne odczyty Promotion nie
uzupełniają pokrycia.

Odczyt historii zwraca do 100 najnowszych wpisów. Tabela przechowuje wszystkie
zdarzenia, a najniższa cena jest obliczana ze wszystkich wpisów żądanego okna.

Sklep pokazuje najniższą cenę tylko przy `historyComplete=true` i skończonej,
niepustej wartości. W innym przypadku wyświetla informację o niepełnej historii;
`null` nie jest zamieniane na `0 zł`.

## Konfiguracja i wydanie

- Promotion wywołuje chronione endpointy Product `/internal/products/price-history/{id}`
  i `/internal/products/price-history/{id}/lowest` jako tożsamość usługi.
- Lokalny Compose wskazuje `PRODUCT_SERVICE_URL=http://product-service:8080`.
  W Kubernetes URL Product jest już obecny we wspólnym ConfigMap.
- Przy wydaniu najpierw należy zastosować migrację Product V11, a następnie
  uruchomić zaktualizowane Product i Promotion. Niezgodność lub niedostępność
  Product pozostawia wskaźnik jako niekompletny.
- Po uruchomieniu potrzebne jest 30 kompletnych dat UTC, zanim wskaźnik 30-dniowy
  będzie widoczny. Brakujący dzień pozostaje poza pełnym oknem do czasu, aż
  wypadnie z kroczącego zakresu. Krótsze zapytanie (np. `days=1`) może być
  kompletne wcześniej.

## Status

Implementacja A18 została przygotowana lokalnie na `Audit_0.1`; nie wdrażano jej
na dev/prod. Historyczne dane przed migracją są nieodtwarzalne, więc wskaźnik
nie będzie dostępny od razu dla pełnych 30 dni. To zamierzone zachowanie
zapobiega prezentowaniu nieudowodnionej wartości.

## Odbiór uzupełniający w pakiecie 31

23 testy Product i 16 testów Promotion (w tym siedem nowych scenariuszy źródła
i pokrycia) przeszły. Cztery scenariusze Chromium sprawdziły pustą, błędną,
niepełną i kompletną odpowiedź w katalogu oraz na stronie produktu. Naprawiono
także osobną aktualizację Hot Shot, która wcześniej nadpisywała nowy komunikat.

Regresja rzeczywistych usług na jednorazowym PostgreSQL przeszła 103 kontrole
HTTP i sprawdziła OpenAPI. Potwierdziła migawkę bez ruchu po uruchomieniu
repliki, brak zapisu przy odczytach, uprawnienia do źródła, kolejne rewizje
ceny tego samego dnia, wpływ zmiany układu, wyłączenie starych obserwacji
z pokrycia oraz brak ceny przy luce lub awarii źródła. Datowane dane do
sprawdzenia agregacji 30 dni są syntetyczną fixture testową; nie dodano backfillu
historycznych cen w aplikacji.

Raport: `tmp/catalog-e2e-ceb970bc/report.json`. Scenariusz A18/A27 jest uruchamiany
w CI. Kod nadal wymaga wydania, a rzeczywiste okno zbiera się od uruchomienia.
