# A43.28 — eksport rezerwacji magazynu i kuponów

## Zakres i ścieżki

Eksport obejmuje wyłącznie zapisane dane przypisane do wskazanego własnego
zamówienia. Wymagany jest JWT klienta. Działają bezpośrednie porty usług oraz
oba prefiksy Gateway: `/api` i `/api/v1`.

| GET przez `/api/v1` | Wynik |
| --- | --- |
| `/inventory/me/data-export/orders/{orderId}` | Zapisany nagłówek rezerwacji magazynowej lub `reservation=null` |
| `/inventory/me/data-export/orders/{orderId}/items?size=50&cursor=0` | Strona pozycji rezerwacji tego zamówienia |
| `/promotions/me/data-export/orders/{orderId}` | Zapisana rezerwacja kuponu lub `reservation=null` |
| `/promotions/me/data-export/orders/{orderId}/redemptions?size=50&cursor=0` | Strona zapisanych wykorzystań kuponów tego zamówienia |

Nagłówki nie zawierają nieograniczonych list. Strony mają 1–50 rekordów,
sortowanie malejąco po id i tekstowy `nextCursor`; `0` oznacza koniec.
Przy kolejnym pobraniu należy przekazać otrzymany kursor bez konwersji do
liczby JavaScript. Każdy dokument zawiera scope, orderId, generatedAt,
`complete=false` i listę pominięć. Nie jest to jeszcze zintegrowany eksport
całego konta ani wspólna migawka między stronami i usługami.

## Kontrola dostępu

Przy każdym nagłówku i stronie, również pustej lub wyczerpanej, usługa
wywołuje istniejące `GET /internal/orders/{orderId}/access` z własną
tożsamością usługi. Order jest źródłem właściciela. JWT klienta i token
zamówienia nie są przekazywane do źródła. Brak cache, dopasowania po e-mailu
lub traktowania reservationId jako orderId.

Zamówienie innego klienta, gościa lub brakujące daje 404. Nawet poprawny
token zamówienia gościa nie uprawnia do eksportu konta. Brak uwierzytelnienia
daje 401; operator i usługi nie mają obejścia kontroli klienta. Niedostępne
lub błędne źródło uprawnień daje 503, bez odczytu domenowej bazy i fallbacku.
`customerId` w query oraz nagłówki podające tożsamość nie zmieniają właściciela.
Nieprawidłowe okna i przepełnienie liczbowego id/kursora dają 400.

Pozycje magazynu są kwalifikowane przez JOIN do rezerwacji o właściwym
order_id. Wykorzystania kuponów filtruje order_id. Ten sam kod kuponu albo
adres e-mail w dwóch zamówieniach nie łączy ich danych. Istniejące API
operacji magazynowych, rezerwacji technicznych i globalnej dostępności
kuponów pozostaje osobnym, chronionym kontraktem.

## Dane transakcji i pominięcia

Rezerwacja magazynu: reservationId, orderId, zapisany status, expiresAt,
createdAt, updatedAt. Pozycja: reservationItemId, reservationId, productId,
quantity. Nie zwraca globalnych stanów available/reserved/sold ani operacji
przyjęcia/korekty zapasu, operation_id i wersji magazynu.

Rezerwacja kuponu: orderId, wykorzystany kod transakcji, subtotal,
discountAmount, zapisany state, acceptedAt, expiresAt, createdAt, updatedAt.
Wykorzystanie: redemptionId, orderId, code, redeemedAt. Kwoty mają precyzję
dziesiętną z bazy; null dat i brakujące rezerwacje historyczne są zachowane.
Wykorzystanie starszego kuponu może istnieć bez rezerwacji — nie tworzymy
brakującego rekordu. Stan RELEASED pozostaje zapisanym tombstone.

Kod faktycznie użyty w transakcji jest informacją zamówienia. Całe definicje
promotion_codes/campaigns, opisy, limity, globalne liczniki wykorzystania,
historia cen oraz operacyjny version rezerwacji nie są eksportowane.
Katalog produktów/kategorii i globalny stan/operacje magazynu są jawnie
sklasyfikowane jako `not-account-scoped`: nie mają własności klienta przez
konkretne zamówienie. Nie udostępnia się całych tabel z powodu braku takiej
relacji. Dotychczasowe publiczne projekcje katalogu nie rozszerzają eksportu.

## Brak skutków biznesowych

Odczyty korzystają z osobnych zapytań JDBC i jawnych list pól. Nie wywołują
serwisów rezerwowania, zwalniania, zatwierdzania, wygaszania, wykorzystania
ani uzgodnienia. Nie zmieniają dat TTL, sald, limitów, wersji i dowodów
jednokrotnego wykorzystania. Nie blokują rekordów `FOR UPDATE`.

Wygasła rezerwacja o zapisanym stanie RESERVED jest pokazana dokładnie tak,
jak istnieje w bazie. Eksport nie oblicza stanu i nie uruchamia cleanup.
Normalne zadania w tle nadal mogą zmieniać stan niezależnie od pobrania;
strony nie stanowią migawki i historia nie jest odtwarzana.

Stałe nazwy załączników, `Cache-Control: private, no-store` i `nosniff`
chronią pobrane odpowiedzi. Serwer nie zapisuje archiwum. Pobrane JSON
pozostają kopiami zarządzanymi przez użytkownika. Usuwanie, kopie i docelowe
retencje wymagają odpowiednich późniejszych etapów A43.

## Indeksy, konfiguracja i odbiór

Zapytania mają timeout SQL 2 s. Nagłówki wykorzystują istniejącą unikalność
inventory_reservations.order_id i klucz główny coupon_reservations.order_id.
Dodano wyłącznie migracje indeksów:

- Inventory V9: `(reservation_id, id DESC)` dla stron pozycji;
- Promotion V7: `(order_id, id DESC)` dla stron wykorzystania kuponów.

Migracje nie przepisują danych i nie usuwają dowodów deduplikacji. Rejestr
obejmuje 49 tabel, 125 migracji i 35 mechanizmów; przejrzano tylko dwa nowe
pliki migracji, bez odnawiania hashy historycznych. Globalne możliwości
eksportu konta/usuwania/backupów nadal mają status pending.

Używany jest istniejący OrderAccess i konfiguracja PLATFORM_ORDER_URL /
ORDER_SERVICE_URL. Compose już wskazuje właściwy Order. Graf Kubernetes
dodaje Inventory → Order; Promotion miało tę relację. Nie dodano sekretów
ani zależności runtime. Testcontainers i profil Failsafe dodane w Promotion
dotyczą wyłącznie testów.

`scripts/qa/reservation-data-export-e2e.py` uruchamia własne bazy i pięć JAR
z losowymi portami/poświadczeniami. Tylko w fixture wydłuża okres cleanup
magazynu do godziny, po początkowym przebiegu na pustych rezerwacjach,
aby sprawdzić wpływ samych odczytów na wygasłe dane. Pozostałe zadania
biznesowe wyłączone są wyłącznie w fixture. Dziesięć tabel jest porównywanych
przed/po; lokalna atrapa wykrywa wywołania Product/Payment/Notification.
Test zatrzymuje własny Order i wymaga 503 z każdej ścieżki.

Wynik odbioru i raport podaje [checklista audytu](AUDIT_0.1_POSTEP.md).
Nie uruchomiono wdrożenia dev/prod, restartu CC ani operacji na danych
użytkownika. Brak nowego przycisku sklepu i widoku CC; integrację interfejsu
obejmuje A43.32. A05, całe A41 i zmiany samych zdjęć pozostają odłożone.
