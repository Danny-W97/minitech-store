# A14 — rezerwacje limitu kuponów

Pakiet 27 na `Audit_0.1`, 26.10.2026. Rabat z ograniczoną liczbą użyć
wymaga trwałej rezerwacji przed `CONFIRMED` i rozpoczęciem nowej płatności.
Walidacja kodu w koszyku pozostaje podglądem; o dostępności rozstrzyga rezerwacja.

## Limit i zapis zakupu

Promotion blokuje rekord kodu w SQL. Rezerwacje i wykorzystania wspólnie zajmują
limit: `redeemedCount + reservedCount <= maxRedemptions`. Przy jednym wolnym
użyciu i dwóch równoległych zamówieniach rabat otrzymuje jedno zamówienie.
Drugie dostaje konflikt i przechodzi przez kompensację, bez płatności.

Jeden Order ID ma jedną rezerwację, z niezmiennym kodem, wartością produktów
przed rabatem oraz kwotą rabatu. Ponowienie zwraca zapisany wynik. Zmieniona
wycena lub inny kod dają 409. Unikalny klucz i wersja encji chronią także
równoległe pierwsze zapisy dla tego samego Order ID pod różnymi kodami.

| Stan | Znaczenie |
| --- | --- |
| `RESERVED` | Użycie zajęte przez zaakceptowaną wycenę |
| `CONSUMED` | Zatwierdzony zakup; licznik i wpis wykorzystania zapisane atomowo |
| `RELEASED` | Trwała blokada spóźnionej rezerwacji dla anulowanego zamówienia |

Order rezerwuje kupon przed zapasem, poza transakcją SQL. Dowód potwierdzonej
rezerwacji zapisuje jako `couponReservationVerified` po powodzeniu tego etapu
sagi. Payment korzysta z tego dowodu, dzięki czemu niedostępność Promotion
nie zatrzymuje zapłaty za już zaakceptowane zamówienie. Anulowanie nadal
blokuje nową płatność przez istniejący mechanizm Payment cancellation fence.

Przy zatwierdzeniu kuponu Promotion odczytuje kanoniczne Order poza transakcją
i sprawdza kod, wycenę, stan zakupu oraz blokady finansowe. Wykorzystanie
zajętego użycia, zwiększenie licznika i wpis historii zatwierdzają się w jednej
transakcji. Ponowienie nie zwiększa licznika drugi raz. Pobranie zużywa kupon
przy przyjęciu realizacji, zanim operator zapisze wpłatę. Późniejsze wygaśnięcie
lub wyłączenie kodu nie odbiera wcześniej zarezerwowanego rabatu.

## Anulowanie i wygaśnięcie

Nowe zamówienie z kuponem ma domyślnie 40 minut do zapłaty lub przyjęcia
pobrania. Po terminie Order koordynuje: blokadę nowej płatności w Payment,
zwolnienie kuponu, zwolnienie zapasu i końcowy `CANCELLED`. Dopiero potwierdzenie
tych kroków kończy anulowanie. Utrata odpowiedzi po zapisie skutku prowadzi
do idempotentnego ponowienia sagi.

Promotion **nie zwalnia użycia na podstawie samego zegara**. Oczekująca lub
niejednoznaczna płatność uniemożliwia Payment fence, więc kupon pozostaje zajęty.
Worker Order odracza próbę o 120 sekund. Zakończona odmową płatność pozwala
ponowić anulowanie. Zamówienia opłacone, przyjęte pobrania, zwroty i sprawy
wymagające uzgodnienia są wykluczone z automatycznego wygaszania.

Worker wybiera najwyżej 20 rekordów, ma dwa wątki i kolejkę 40 zadań.
Indeks częściowy obejmuje tylko nieopłacone zamówienia kwalifikujące się
do wygaszenia, bez narastającej historii opłaconych zakupów.
Blokada Order chroni claim terminu między replikami; istniejący lease sagi
chroni wykonanie kompensacji. Potwierdzona rezerwacja przeżywa restart usług.

| Zmienna | Domyślnie | Znaczenie |
| --- | --- | --- |
| `COUPON_RESERVATION_TTL_SECONDS` | `2400` | Termin nowego zamówienia; zakres 60–86400 s |
| `COUPON_EXPIRY_ENABLED` | `true` | Automatyczna koordynacja wygaśnięcia w Order |
| `COUPON_EXPIRY_DELAY_MS` | `5000` | Częstotliwość skanu |

Szablony dev/test, Compose i ConfigMap zawierają te ustawienia. Wyłączenie
workera nie usuwa rezerwacji ani terminów. Anulowanie przez API nadal działa.

## Historia i operator

Order V19 dodaje terminy i dowód rezerwacji; Promotion V6 dodaje rejestr
rezerwacji. Migracje nie przepisują starych kwot, liczników ani dokumentów.
Historyczne Order nie dostają automatycznie terminu wygaśnięcia lub dowodu.

Nowa płatność starego zamówienia bez dowodu wymaga odczytu pasującej rezerwacji
w Promotion. Jej brak lub niezgodność daje 409 przed zapisem próby/autoryzacją;
niedostępność usługi daje 503. Odpowiedź Order bez nowego pola dowodu jest
czytelna i podlega tej samej weryfikacji; brak pola nie oznacza potwierdzenia.
Operator może uzgodnić zapisaną wycenę:

```http
GET /orders/123/coupon
POST /orders/123/coupon/reconcile
GET /promotions/codes/WELCOME10/capacity
GET /promotions/codes/reservations?code=WELCOME10&state=RESERVED&size=50&cursor=0
```

Uzgodnienie rezerwuje dokładnie zapisaną wycenę, jeśli pozwalają na to reguły
kodu i dostępny limit. Potwierdza dowód w Order, a starszemu nieopłaconemu
zamówieniu nadaje termin. Zajęty limit lub zmieniona oferta dają 409; operator
nie wymusza przekroczenia limitu ani nie zmienia ceny lub stanu płatności.
Istniejące wykorzystanie pozostaje idempotentne. Blokady finansowe i sagi
wymagają własnego procesu uzgodnienia.

Te odczyty i uzgodnienie są dostępne wyłącznie operatorowi. Lista ma ograniczoną
stronę 1–200 i kursor Order ID oraz filtry kodu/stanu. Capacity pokazuje limit,
zajęte użycia i wykorzystania; `availableUses=null` oznacza brak limitu.
Control Center udostępnia cztery akcje odpowiadające poleceniom:

```bash
bash scripts/control-center/control-center.sh coupon-capacity WELCOME10
bash scripts/control-center/control-center.sh coupon-reservations '*' RESERVED 50 0
bash scripts/control-center/control-center.sh order-coupon 123
bash scripts/control-center/control-center.sh order-coupon-reconcile 123
```

Wewnętrzne `/internal/promotions/orders/{id}/coupon/reserve` i `/release`
może wywołać tylko Order. Odczyt rezerwacji mają Order i Payment. Promotion
otrzymuje odczyt kanonicznego Order do potwierdzenia wykorzystania.
Klient ani operator nie mogą bezpośrednio tworzyć i zwalniać tych rezerwacji.

## Wydanie i ograniczenia

1. Wstrzymać nowe zakupy z kuponami i stare procesy zapisujące ich wykorzystania.
2. Zastosować Promotion V6 i Order V19, zaktualizować Order, Promotion, Payment
   oraz wspólną politykę uprawnień w usługach i Gateway. Unikać mieszanego
   działania starych ścieżek `redeem` i nowego mechanizmu.
3. Zweryfikować rezerwację, płatność/pobranie i anulowanie, włączyć worker
   oraz wznowić checkout. Stare nieopłacone wyceny uzgadniać indywidualnie.

Nie usuwać wpisów `RELEASED`: chronią spóźnione żądania. Pełne odwracanie
wykorzystanego kuponu po refundacji pozostaje rozszerzeniem procesu A11.
Brak ręcznego odbioru Stripe A05 nadal pozostaje otwartą zależnością;
ten pakiet nie zmienia sekretów ani odłożonego zakresu zdjęć.
Nie wdrażano usług na dev/prod.

## Weryfikacja — 26.10.2026

- 236 testów Order/Payment/Promotion i uprawnień; dodatkowe 9 końcowych testów
  rezerwacji, w tym wyścig różnych kodów dla tego samego Order ID. Zestawy
  częściowo się pokrywają. Logi: `tmp/a14-services-tests.log`,
  `tmp/a14-promotion-final.log`. Końcowa regresja Payment/uprawnień:
  153 testy, w tym zgodność JSON ze starszą odpowiedzią bez dowodu rezerwacji
  (`tmp/a14-payment-final.log`); zestaw pokrywa się z powyższym.
- 98 testów Python/CC, składnia Bash oraz sprawdzenie poleceń i formularzy
  PL/EN na atrapach HTTP: `tmp/a14-python-final.log`, `tmp/a14-cli.log`.
- Build wszystkich 15 aplikacji i zgodność dołączonej biblioteki uprawnień:
  `tmp/a14-platform-package.log`, `tmp/a14-package-verification.json`.
- **96 kontroli HTTP** na jednorazowym PostgreSQL, dwóch Order, dwóch Promotion
  i dwóch Payment: ostatnie użycie, role, OpenAPI, ograniczenie strony,
  wygaśnięcie, niepewna płatność, utrata odpowiedzi rezerwacji/zwolnienia,
  stara wycena, uzgodnienie operatora oraz wykorzystanie po wyłączeniu kodu.
  Awaria odczytu rezerwacji starszej wyceny daje 503 przed zapisem płatności.
  Raport końcowy: `tmp/coupon-reservations-7ba17547/report.json`.
- Regresja A10: **52 kontrole HTTP**, pobranie, TTL zapasu, wpłata, faktura
  i odzyskanie po przerwaniu procesu. Raport:
  `tmp/deferred-payments-b499116a/report.json`.
- Regresja A09: pięć zakupów podczas niedostępności wszystkich trzech usług
  skutków, cztery przerwania po skutku/przed zapisem zadania, kolejka błędów
  i odtworzenie przez operatora; 11 pojedynczych wykorzystań kodu. Raport:
  `tmp/payment-follow-ups-e3493444/report.json`.
- Regresja A08: wyścigi dwóch Payment, stały klucz dostawcy, przerwania procesu,
  odrzucenie starego workera i odtworzenie odpowiedzi — poprawne. Raport:
  `tmp/payment-replicas-9423ec21/report.json`. Atrapy odpowiedzi Order
  i deklaracji metod operatora testowego dostosowano do bieżącego kontraktu.
- Regresja A31: niezależny postęp sagi przy jednym połączeniu SQL,
  timeouty płatności/PDF/SMTP i brak pozostawionych transakcji — poprawne.
  Raport: `tmp/http-transactions-a47a8b5d/report.json`.

Regresja A14 jest dodana do CI wraz z raportami i logami.
