# A21 — atomowe przyjęcia zapasu i rejestr operacji

Pakiet 22 na `Audit_0.1`, 25.10.2026. Przyjęcie dostawy dodaje ilość pod blokadą
produktu; nie nadpisuje licznika odczytanego wcześniej przez panel. Zmiana zapasu
i niezmienny wynik operacji są zapisywane w tej samej transakcji SQL.

## Przyjęcie dostawy

```http
POST /api/v1/inventory/items/101/receipts
Content-Type: application/json

{"quantity":10,"operationId":"18c9f539-6141-4a6a-8052-17b557d7cc93"}
```

Operacja jest dostępna wyłącznie operatorowi, również bezpośrednio na porcie
Inventory. Klient, anonimowy użytkownik i usługi Order/Payment nie mogą dodawać
dostaw ani odczytywać rejestru operacji. UUID należy zapisać **przed pierwszym
wysłaniem** i zachować przy każdym ponowieniu tej samej dostawy.

Ilość musi być dodatnią liczbą całkowitą. Backend blokuje rekord produktu,
ponownie sprawdza rejestr i dodaje ilość do aktualnej dostępności. Rezerwacja
korzysta z tej samej blokady. Przy równoczesnym +10 i rezerwacji 3 sztuk wynik
zachowuje oba ruchy: dostępność rośnie łącznie o 7, a reserved o 3.

Wynik 201 zawiera między innymi:

- `operationId`, `productId`, `kind=RECEIPT`, `status=COMMITTED`;
- `quantity`, `availableBefore`, `availableQuantity`, `reservedQuantity`, `soldQuantity`;
- `beforeVersion`, `version` i czas zapisu `createdAt`.

Ten sam UUID z tym samym produktem, rodzajem i ilością zwraca oryginalny wynik.
Zmiana polecenia lub użycie UUID do innego produktu kończy się 409. Przegrana
kolizja pierwszego INSERT-u między produktami wycofuje również zmianę zapasu;
zwycięski zapis odczytywany jest dopiero po zakończeniu rollbacku.

Wynik operacji jest **historyczną migawką**, a nie bieżącym stanem magazynu.
Jeśli po przyjęciu 10 sztuk inny zakup zarezerwuje 3, ponowienie przyjęcia nadal
zwraca pierwotną migawkę. Aktualne liczniki należy odczytać przez GET produktu.
Nie ma automatycznego TTL kluczy operacji. Dwa różne UUID oznaczają dwa odrębne
zamiary przyjęcia; ilość i produkt nie zastępują identyfikatora dostawy.

## Status i rejestr

```http
GET /api/v1/inventory/operations/18c9f539-6141-4a6a-8052-17b557d7cc93
GET /api/v1/inventory/operations?productId=101&size=50&cursor=0
```

Rejestr przyjęć i korekt jest stronicowany kluczem liczbowym, z nagłówkami
`X-Has-More` i `X-Next-Cursor`. UUID wskazuje operację; kursor wskazuje wiersz
rejestru i powinien być przepisywany z odpowiedzi. Nie rekonstruowano wstecznie
nieznanych ruchów magazynowych sprzed Inventory V8. Zapas początkowy i dotychczasowy
rejestr rezerwacji pozostają odrębnymi faktami.

404 po utracie odpowiedzi oznacza jedynie, że nie znaleziono zatwierdzonego
wpisu w chwili odczytu. Pierwotny POST może nadal pracować. Nie wolno interpretować
tego jako podstawy do automatycznego utworzenia drugiego UUID dla tej samej dostawy.
Ponowienie pierwotnego UUID jest bezpieczne zarówno przed, jak i po commicie.

## Korekta dostępności

Inventory V8 dodaje `version` do odpowiedzi GET produktu. Zwykłe przyjęcia powinny
korzystać z delty. Ręczna korekta bezwzględnego stanu wymaga odczytanej wersji:

```http
PATCH /api/v1/inventory/items/101
Content-Type: application/json

{"availableQuantity":20,"expectedVersion":7,"operationId":"ef0ab242-465b-415a-86aa-b18763e62aab"}
```

Jeśli zapas, rezerwacja albo sprzedaż zmieniły wersję, korekta dostaje 409 i nie
jest zapisywana. Operator musi ponownie zweryfikować stan; aplikacja nie dopasowuje
automatycznie starej korekty do nowej wersji. Korekta również otrzymuje niezmienny
wpis `ADJUSTMENT` i idempotentny UUID. Jej `quantity` może być ujemne albo zerowe.
Ponowienie po dalszych zmianach zapasu odtwarza pierwotny wynik.

**Stary PATCH zawierający wyłącznie availableQuantity jest teraz odrzucany 400.**
To zamierzona zmiana kontraktu eliminująca nadpisanie rezerwacji. Korekta zmienia
tylko dostępność; nie rozlicza zwrotu, nie cofa sprzedaży ani nie zmienia rezerwacji.
Połączenie dostępnego i zarezerwowanego zapasu nie może przekroczyć zakresu int32;
przepełnienie liczników jest odrzucane bez zapisu.

## Control Center

Przyciski +10/+50 zapisują UUID i ilość lokalnie przed POST-em. Po błędzie
pozostaje „Ponów +10” lub „Ponów +50”, także po odświeżeniu strony. Inna ilość dla
tego produktu jest zablokowana do rozstrzygnięcia pierwszego zamiaru. Krótkie
blokady przeglądarki synchronizują zapis zamiaru między kartami. Identyfikator
usuwany jest dopiero po potwierdzonym COMMITTED dla właściwego polecenia.
Nie jest to zapis tokenu sesji; sesja panelu nadal pozostaje wyłącznie w pamięci.

Serwer CC wysyła deltę. Po timeoutcie lub niejednoznacznym błędzie odczytuje
status tego samego UUID. Nie wykonuje odczyt–dodanie–PATCH ani zapisu SQL.
Brak potwierdzenia daje 503 z identyfikatorem; jawna odmowa REST pozostaje odmową.
Brak możliwości trwałego zapisania zamiaru w przeglądarce blokuje wysłanie,
zamiast wysyłać dostawę bez klucza ponowienia.

CC → Monitoring i DLQ udostępnia rejestr, status, odczyt zapasu oraz przyjęcie
i korektę z jawnym UUID. CLI:

```bash
bash scripts/control-center/control-center.sh inventory-item 101
bash scripts/control-center/control-center.sh inventory-receive 101 10 18c9f539-6141-4a6a-8052-17b557d7cc93
bash scripts/control-center/control-center.sh inventory-operation 18c9f539-6141-4a6a-8052-17b557d7cc93
bash scripts/control-center/control-center.sh inventory-operations 101 50 0
bash scripts/control-center/control-center.sh inventory-adjust 101 20 7 ef0ab242-465b-415a-86aa-b18763e62aab
```

CLI nie generuje nowego UUID przy błędzie. Automatyczne przygotowanie zapasu
w scenariuszu QA również korzysta teraz z przyjęcia z identyfikatorem, bez PATCH
opartego na starej wartości. Identyfikator powinien być wspólny dla ponowień
tej samej fizycznej dostawy, niezależnie od narzędzia wykonującego operację.

## Migracja i wydanie

Inventory V8 dodaje wersję zapasu i `inventory_stock_operations`. Indeks po
produkcie i ID wspiera ograniczone odczyty. Klucz obcy chroni produktowy rekord
magazynowy, do którego odnosi się historia. Rejestr aplikacyjny jest niezmienny.

Na czas wymiany wstrzymać operacje zapasu i opróżnić aktywne transakcje; wymienić
całą flotę Inventory, następnie CC/CLI i klientów starego PATCH. Nie mieszać
piszących instancji, które nie inkrementują wersji, z nowym protokołem korekt.
Odświeżyć panel, aby nowy zamiar był zapisywany przed wywołaniem API.
Sama migracja nie dodaje towaru i nie zmienia dotychczasowych liczników.

## Regresja

- `StockOperationTest`: osiem ponowień przy puli czterech połączeń, rezerwacja
  równoległa z przyjęciem, kolizja UUID między produktami z rollbackiem,
  utrata zapisu rejestru, korekta po starej wersji, przepełnienia i strony historii.
- `test_stock_receipts.py`: niejednoznaczna odpowiedź, odczyt statusu, 403,
  niezgodna operacja i błędne dane bez kontaktu z backendem.
- Chromium CC: utrata odpowiedzi po przyjęciu, odświeżenie strony i zachowanie
  tego samego UUID przy ponowieniu.
- `inventory-receipts-e2e.py`: dwie rzeczywiste JVM i PostgreSQL, role, równoległe
  przyjęcia/rezerwacje, śmierć przed zapisem rejestru oraz po commicie, ale przed
  przekazaniem odpowiedzi klientowi. Scenariusz dodano do CI.

Odbiór pakietu 22: 346 testów Java, 93 Python/CLI, 15 Chromium CC; pełna platforma
zaliczyła 196 kontroli HTTP i wszystkie cztery bramki jakości. Natywny odbiór
A21 na PostgreSQL i dwóch JVM zaliczył 79 kontroli HTTP, 12 równoległych żądań
z tym samym UUID, osiem wyścigów przyjęcia z rezerwacją, kolizję klucza między
produktami oraz obie granice utraty procesu/odpowiedzi. Raport:
`tmp/inventory-receipts-033ec63c/report.json`. Po teście zasoby tymczasowe usunięto.

Nie wdrażano na dev/prod. Zdjęcia pozostają odłożone. Następny otwarty P1:
**A05 — integracja rzeczywistego dostawcy płatności, webhooków i refundacji**.
