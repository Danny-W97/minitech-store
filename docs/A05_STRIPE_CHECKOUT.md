# A05 — Stripe Checkout, pełne zwroty i odzyskiwanie operacji

**Status od 26.10.2026: pozostałe czynności odłożone decyzją użytkownika.**
Kod i testy lokalne są gotowe. Ręczne uzupełnienie obu zmiennych Stripe
w `dev.env.sops` i `test.env.sops`, konfiguracja webhooka oraz odbiór zewnętrznego
sandboxu zostają na później. Wznowienie wymaga osobnego polecenia; automatyczna
kontynuacja audytu obejmuje inne zadania. Przy zestawieniach pozostałych prac
wymieniać A05 jako odłożone. Odroczenie nie zamyka bramki gotowości P1.

## Wdrożono w bieżącym etapie

- Karta płatnicza jest obsługiwana przez hostowaną stronę Stripe Checkout.
  Numer karty nie trafia do Mini Order Platform ani jej logów.
- Kwota jest pobierana z zapisanego zamówienia. Checkout jest związany z ID
  zamówienia i lokalną płatnością, ma klucz ponowienia Stripe i zwraca klienta
  na bezpieczny adres strony sklepu.
- Zamówienie nie staje się opłacone po powrocie przeglądarki. Rozliczenie wymaga
  odczytu aktualnej sesji z API Stripe, uruchamianego przez podpisany webhook
  lub worker. Przed zmianą zamówienia system potwierdza rezerwację magazynową.
  Dowód zapłaty i trwałe skutki zapisują się w Payment Service.
- Przy rozpoczęciu Checkoutu aktywna rezerwacja Inventory jest wydłużana do
  40 minut. Sesja Stripe wygasa po 31 minutach. Endpoint wydłużający jest
  dostępny wyłącznie dla uwierzytelnionej usługi Payment.
- `checkout.session.expired` dla nieopłaconej sesji kończy lokalną próbę,
  anuluje oczekujące zadania po płatności i uruchamia trwałą kompensację A11.
- Potwierdzenie płatności jest sprawdzane przez odczyt aktualnej sesji z API
  Stripe. Dowód zapłaty, PaymentIntent i receipt zdarzenia zapisują się przed
  dalszą realizacją. Worker SQL odzyskuje operację także bez ponownego webhooka.
- Awaria po `PAID` w Order, ale przed zakończeniem Payment, jest odzyskiwana
  na podstawie tego dowodu i potwierdzonej rezerwacji, bez ponownego obciążenia.
- Checkout używa standardowego capture Stripe. Po zapłacie i definitywnej
  odmowie potwierdzenia zapasu (404/409) powstaje trwała operacja pełnego zwrotu.
  Order otrzymuje blokadę finansową przed zleceniem zwrotu. Dopiero potwierdzony
  zwrot, kompensacja zapasu i końcowy zapis Order dają `CANCELLED`.
- Rezerwacja RESERVED jest zwalniana, a CONFIRMED może zostać skompensowana
  przez odjęcie sprzedaży i przywrócenie dostępnego zapasu. Blokada rezerwacji
  i stan RELEASED zapewniają pojedynczy skutek ponowienia. Zwykły endpoint
  release nadal odrzuca cofanie potwierdzonej sprzedaży.
- Operator może zlecić pełny zwrot Stripe. Wartość musi dokładnie odpowiadać
  płatności, klucz ponowienia jest niezmienny, a klient i inne usługi nie mają
  dostępu do tej operacji. Częściowe zwroty i zwroty po wysyłce nie są oferowane
  przez ten endpoint.
- Zwrot operatora zatrzymuje dalszą realizację i zadania Payment. Po sukcesie
  finansowym Order pokazuje `REFUNDED` i pozostaje do uzgodnienia dokumentów,
  kuponu oraz pozostałych skutków zakupu. Nie zmienia pozornie całego zakupu na
  anulowany. Jest to granica wariantu MVP A11; pełne rozliczenie tych skutków
  należy do osobnego procesu domenowego, nie do adaptera Stripe.
- Zwrot wykonany bezpośrednio w panelu Stripe jest odczytywany przez API,
  także bez lokalnego UUID operacji i przed webhookiem zapłaty. Payment zapisuje
  blokadę dalszej realizacji i potwierdzony pełny zwrot przed wywołaniem Order.
  Awaria Order pozostawia trwałe ponowienie; aplikacja nie zleca nowego zwrotu.
  Zdarzenie częściowego zwrotu zatrzymuje realizację i wymaga ręcznego uzgodnienia.
  Zwrot zewnętrzny po wysyłce zachowuje stan wysyłki i oznacza sprawę do rozliczenia.
- Sklep obsługuje `REFUNDED`/`REFUND_PENDING`; powrót zalogowanego klienta ze
  Stripe czeka na odtworzenie sesji i zachowuje uwierzytelnienie. Nieudany lub
  zwrócony Checkout zachowuje koszyk i kończy stary zamiar zakupu.

## Konfiguracja lokalnego sandboxu

**Otwarte zadanie użytkownika, odłożone na później:** podczas wdrażania A05
dodano `STRIPE_SECRET_KEY` i `STRIPE_WEBHOOK_SECRET`. Użytkownik musi ręcznie
dodać obie zmienne do każdego z zaszyfrowanych plików:

- [ ] `/home/danny/.config/mini-order-platform/secrets/dev.env.sops`;
- [ ] `/home/danny/.config/mini-order-platform/secrets/test.env.sops`.

Wartości to testowy klucz API z prefiksem `sk_test_` i sekret podpisywania
właściwego webhooka z prefiksem `whsec_`. Edycja: `secrets-edit dev` oraz
`secrets-edit test` przez Control Center. Istniejące skarbce nie są
automatycznie uzupełniane. Czynność wymieniać w zestawieniach pozostałych prac,
aż użytkownik potwierdzi uzupełnienie obu profili; [status w audycie](AUDYT_APLIKACJI_2026-10-23.md#a05-stripe-sekrety).

W konfiguracji uruchomienia należy ustawić `PAYMENT_PROVIDER=stripe` i
`PAYMENT_RETURN_BASE_URL=http://127.0.0.1:18070/index.html`. Sekretów nie
wklejać do rozmowy, README, repozytorium ani logów. Po uruchomieniu lokalnego
Payment Service Stripe CLI może przekazywać skonfigurowane zdarzenia na
`http://127.0.0.1:18080/api/payments/webhooks/stripe`.

W formularzu Stripe Checkout wpisuje się testową kartę `4242 4242 4242 4242`,
przyszłą datę ważności, dowolne CVC i ZIP. Numer jest używany tylko na stronie
Stripe; Stripe wymaga testowego klucza API dla testowych kart.

## Trwały rejestr i uzgadnianie zwrotów

Payment V15 dodaje dowód zapłaty, indeks odzyskiwania oraz `payment_refunds`.
Jedna płatność może mieć jedną pełną operację zwrotu. Rejestr zawiera kwotę,
walutę, lokalny UUID operacji, stały klucz klienta, PaymentIntent, ID zwrotu
Stripe, autora, przyczynę, status finansowy i wynik dalszych kroków. Statusy:
REQUESTED, PENDING, SUCCEEDED, FAILED, REVIEW_REQUIRED. Dane kart i surowe
payloady dostawcy nie są zapisywane.

Odpowiedź Stripe `pending` nie jest sukcesem zwrotu. Po utracie odpowiedzi POST
worker najpierw szuka zwrotu PaymentIntent z lokalnym UUID w metadata. Znane ID
jest uzgadniane przez GET, a `refund.created`, `refund.updated`, `refund.failed`
uruchamiają ten sam odczyt. Zdarzenie nie może cofnąć potwierdzonego sukcesu.
Jeśli wynik pierwszego POST pozostaje nieznany przez ponad 20 godzin, worker
nie wysyła nowego zwrotu: ustawia REVIEW_REQUIRED. Także nieskojarzony zwrot
wykonany poza aplikacją wymaga uzgodnienia zamiast kolejnego obciążenia salda.
Jego ID jest widoczne jako `externalRefundId` w statusie Stripe. Kod
`EXTERNAL_REFUND_ORDER_REVIEW` oznacza pełny zwrot i sprawę domenową do rozliczenia;
`EXTERNAL_PARTIAL_REFUND_REVIEW` nie oznacza zwrotu całej płatności.
Powód: [Stripe może usunąć klucz idempotencji API v1 po 24 godzinach](https://docs.stripe.com/api/idempotent_requests).

Sukces finansowy i kompensacja są rozdzielone: awaria po zwrocie pieniędzy
zachowuje SUCCEEDED, a następne wykonanie ponawia wyłącznie kroki Order/Inventory.
Stały błąd kompensacji pokazuje wynik REVIEW_REQUIRED, bez fałszywego CANCELLED.
Blokada finansowa nie jest zdejmowana przez zwykłe uzgodnienie starej sagi A06.
Przerwane próby mają lease 120 s; skan odzyskiwania pobiera po 20 ID i korzysta
z osobnych ograniczonych pul dla Checkoutu oraz zwrotów.

## API i Control Center

Wyłącznie operator:

- `GET /payments/{id}/stripe-status` — Payment, kod uzgodnienia, ID operacji lokalnej i zwrotu zewnętrznego;
- `POST /payments/{id}/reconcile` — odczyt Stripe i odzyskanie rozliczenia;
- `GET /payments/{id}/refunds` — rejestr pełnego zwrotu dla płatności;
- `POST /payments/{id}/refunds` — `amount`, `idempotencyKey`, `reason`;
- `GET /payments/refunds/{id}` — wynik finansowy i wynik dalszych kroków;
- `POST /payments/refunds/{id}/reconcile` — uzgodnienie istniejącej operacji.

Powody operatora: CUSTOMER_REQUEST, DUPLICATE_PAYMENT, OPERATOR_RECONCILIATION.
Ponowienie zachowuje ten sam klucz, kwotę i przyczynę. Inny zamiar daje 409.
Aktywne zadanie realizacji również daje 409: należy poczekać na jego zakończenie.
REFUNDED_ORDER_REVIEW_REQUIRED oznacza potwierdzony zwrot i zatrzymaną realizację,
ale nadal wymaga rozliczenia dokumentów oraz innych skutków zakupu.

CC udostępnia status, uzgodnienie, listę zwrotów, pełny zwrot i uzgodnienie
zwrotu. Odpowiadają im polecenia CLI:

```bash
bash scripts/control-center/control-center.sh payment-stripe-status 123
bash scripts/control-center/control-center.sh payment-reconcile 123
bash scripts/control-center/control-center.sh payment-refunds 123
bash scripts/control-center/control-center.sh payment-refund 123 10.00 staly-klucz-operacji CUSTOMER_REQUEST
bash scripts/control-center/control-center.sh payment-refund-reconcile 456
```

Webhook publiczny pozostaje na `/payments/webhooks/stripe`; Gateway udostępnia
go również pod `/api/v1`. Weryfikacja obejmuje surowe bajty, podpis, timestamp
i tryb test/live. Utworzenie sesji i zwrot używają osobnego klienta HTTP bez
wewnętrznych poświadczeń usług. Parametry ponowienia Checkoutu, w tym czas
wygaśnięcia, są stałe dla zapisanej próby płatności.

## Migracje i kolejność wydania

1. Zbudować wszystkie usługi ze wspólną aktualną polityką dostępu. Wdrożyć
   nowy Inventory i Order z V17 przed uruchomieniem nowego przepływu Payment.
2. Na czas zmiany floty wstrzymać nowe checkouty i operacje zwrotu, opróżnić
   aktywne żądania. Nie mieszać starych workerów z nowymi stanami REFUNDED
   i CANCELLED. Następnie zaktualizować całą flotę Payment z V14/V15.
3. Uaktualnić sklep i CC; uzupełnić sekrety oraz skonfigurować webhook wybranego
   sandboxu. Compose przekazuje obie zmienne, Kubernetes pobiera je z Secret
   wyłącznie do Payment. Brak sekretów z wybranym dostawcą Stripe blokuje start.
4. Przed rzeczywistymi płatnościami wykonać odbiór zewnętrznego sandboxu.
   Zmiana `PAYMENT_PROVIDER` na disabled zatrzymuje również odzyskiwanie Stripe;
   istniejące operacje finansowe należy wcześniej uzgodnić i zachować ich rejestr.

Nie cofać zapisu potwierdzonego zwrotu ani nie usuwać jego danych podczas
rollbacku kodu: operacja dostawcy została już wykonana. W tym etapie nie
wdrażano aplikacji na dev/prod i nie zmieniano rzeczywistych sekretów.

## Odbiór i czynności pozostające po stronie użytkownika

**Odbiór lokalny 25.10.2026 — zakończony.** Pełna regresja platformy zaliczyła
382 testy Java. Po końcowych zmianach ponowiono 142 testy Payment/wspólnej
kontroli dostępu oraz wcześniej 60 testów zmienionych usług; zestawy częściowo
się pokrywają. Ponadto przeszły 93 testy Python/CC, 17 JavaScript i 7 kontroli
mapy gotowości. Końcowe pakowanie potwierdziło identyczną bibliotekę uprawnień
we wszystkich 15 JAR-ach aplikacji.

Regresja PostgreSQL na dwóch instancjach Payment zaliczyła **137 kontroli HTTP**,
sześć zwrotów w lokalnej atrapie Stripe, sześć równoległych zdarzeń, utratę
odpowiedzi zwrotu, przerwanie po `PAID`, przerwanie po finansowym zwrocie,
potwierdzenie pending przez webhook, wygaśnięcie i rzeczywisty worker odzyskiwania
bez webhooka. Odmowa zwrotu po wysyłce nie wysłała pieniędzy ani nie anulowała
zadań zakupu. Zwrot z panelu Stripe, otrzymany przed potwierdzeniem zapłaty,
zatrzymał realizację bez nowego POST zwrotu.
Raport: `tmp/stripe-payments-f64758f6/report.json`.
Jednorazowe bazy, procesy i kopie JAR-ów testu zostały usunięte.

Logi: `tmp/a05-final-platform-package.log`, `tmp/a05-payment-final-regression.log`,
`tmp/a05-python-wsl-tests.log`, `tmp/a05-js-tests.log`,
`tmp/a05-final-document-checks.log`, `tmp/a05-package-verification.json`.

Regresje: `StripeSettlementRecoveryTest`, `StripeGatewayContractTest`,
`StripeRefundHoldTest`, `StripeStockCompensationTest`, `stripe-return.test.cjs`
oraz `scripts/qa/stripe-payments-e2e.py`. Ostatni scenariusz używa prawdziwego
PostgreSQL i dwóch instancji Payment z lokalną atrapą protokołu Stripe. Jest
uruchamiany w CI bez sekretów konta Stripe; nie jest testem jego sandboxu.

Po zakończeniu odbioru lokalnego pozostają otwarte:

- [ ] Ręczne uzupełnienie przez użytkownika **obu** zmiennych Stripe w
  `dev.env.sops` oraz `test.env.sops`, zgodnie z sekcją konfiguracji powyżej.
- [ ] Konfiguracja endpointu/Stripe CLI i test zewnętrznego sandboxu: poprawna
  płatność, odmowa, wygaśnięcie, powtórzenie webhooka i pełny zwrot.

Do czasu wykonania tych czynności A05 pozostaje częściowe w bramce gotowości
P1. Obsługa programistyczna i próby lokalne nie są potwierdzeniem zewnętrznego
odbioru. Przy pytaniu o pozostałe prace nadal wymieniać oba zadania użytkownika.
