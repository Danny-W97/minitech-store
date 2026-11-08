# A17 — opłacony zakup i synchronizacja dostawy

Pakiet 26 na `Audit_0.1`, 26.10.2026. Wdrożono wspólną interpretację zakupu
po wysyłce i zakończeniu oraz trwałą synchronizację Delivery → Order.

## Zakup pozostaje opłacony po realizacji

Opinie, historia zakupionych produktów dla rekomendacji i bestsellery obejmują
`PAID`, `SHIPPED` oraz `COMPLETED`. Wysyłka i zakończenie nie usuwają zakupu
z tych odczytów. Zapytania historii i rankingu korzystają z tego samego
predykatu SQL; nie pobierają całej historii klienta do pamięci.

Wykluczone są anulowane zamówienia, blokady finansowe/uzgodnienia oraz pobranie
bez zapisanego potwierdzenia wpłaty. Review nadal sprawdza właściciela i produkt
z zamówienia oraz wymaga `paymentState=PAID`. Dla starszych odpowiedzi bez tego
pola stosuje jawny zbiór trzech stanów zakupionych. Znane stany `REFUND_PENDING`,
`REFUNDED` i `COLLECTION_DUE` nie korzystają z tego fallbacku.

Model korzysta z istniejącego stanu finansowego Order, odrębnego od realizacji
pobrania i blokad zwrotów. Nie wprowadzono połączeń do baz innych usług ani
ukrytej rekonstrukcji płatności historycznych.

## Przewoźnik aktualizuje realizację, a nie pieniądze

| Status dostawy | Skutek w Order |
| --- | --- |
| `LABEL_CREATED`, `READY_FOR_PICKUP` | Samo utworzenie trackingu nie rozpoczyna wysyłki ani nie potwierdza zapłaty |
| `IN_TRANSIT`, `OUT_FOR_DELIVERY` | `SHIPPED`, jeśli zamówienie nie było już zakończone |
| `DELIVERED` | `COMPLETED` dla dostawy do klienta |
| `PICKED_UP` | `COMPLETED` dla odbioru w sklepie, bez wymyślonego etapu wysyłki |
| `CANCELLED` | Sprawa do ręcznego uzgodnienia; bez automatycznego anulowania zakupu i zwrotu pieniędzy |

Potwierdzony końcowy stan dostawy może od razu doprowadzić Order do
`COMPLETED`, również jeśli pośredni stan nie został dostarczony. Nie odtwarza
fikcyjnej historii wszystkich etapów. Nowszy status nie cofa zakończonego Order;
Delivery odrzuca cofanie postępu, zmianę trybu dostawy i zmianę stanu terminalnego.
Powtórzenie tego samego statusu nie tworzy nowej rewizji.

Delivery nie może ustawić `PAID`, zapisać wpłaty ani zdjąć blokady zwrotu.
Dostarczone pobranie może mieć `status=COMPLETED` oraz
`paymentState=COLLECTION_DUE`; faktura, opinia i zakup w rekomendacjach nadal
wymagają osobnego rozliczenia przez Payment. Potwierdzenie magazynu, stan sagi,
blokady finansowe i tożsamość przesyłki są sprawdzane przed zmianą Order.
Brak zapłaty za kartę daje tymczasową odmowę 425; niespójna saga lub blokada
finansowa daje 409 i wymaga rozstrzygnięcia.

## Trwałe ponowienia i ochrona między replikami

Zmiana statusu przewoźnika i zapis oczekującej synchronizacji zatwierdzają się
w jednej transakcji Delivery. Rejestr przechowuje **bieżący status i rewizję**,
stan ponowienia, liczbę prób i kod diagnostyczny; nie jest pełną historią
wszystkich komunikatów przewoźnika. Nowszy postęp zastępuje oczekujący snapshot.

Worker rezerwuje rekord w krótkiej transakcji, wywołuje Order poza transakcją,
a następnie zapisuje wynik. Lease trwa 120 sekund. Skan wybiera do 20 rekordów,
pula ma dwa wątki i kolejkę 40 zadań. Retry ma opóźnienie do 60 sekund;
po 20 próbach sprawa wymaga operatora. Utrata procesu nie usuwa oczekującego
rekordu. Stary worker nie może potwierdzić nowszej rewizji.

Order zapisuje numer trackingu, rewizję i status pod blokadami sagi i zamówienia.
Starsza rewizja jest potwierdzana bez cofania stanu. Ten sam numer rewizji
z innym statusem, obcy tracking albo inna metoda dostawy są odrzucane.
Po utracie odpowiedzi można ponowić ten sam snapshot bez kolejnego skutku.
Synchronizacja nie zmienia daty ostatniego zdarzenia przewoźnika.

Wewnętrzny `POST /internal/orders/{id}/shipment-progress` jest dostępny
wyłącznie dla tożsamości `delivery-service`. Operator, klient i pozostałe
usługi nie mogą go wywołać. Delivery nie otrzymało uprawnienia do ogólnej
zmiany statusu Order ani rejestracji pobrania.

## Operator i Control Center

Wyłącznie operator:

```http
GET /deliveries/orders/123/synchronization
POST /deliveries/orders/123/synchronization/reconcile
```

Status pokazuje rewizję, stan przewoźnika, stan synchronizacji, kod błędu,
liczbę prób, informację o aktywnym przetwarzaniu i termin ponowienia.
Nie ujawnia tokena rezerwacji.

| Stan synchronizacji | Znaczenie |
| --- | --- |
| `IDLE` | Brak postępu przewoźnika wymagającego wysłania |
| `PENDING` | Bieżąca rewizja czeka na przetworzenie lub trwa jej rezerwacja |
| `RETRY` | Tymczasowy błąd; zaplanowane kolejne wykonanie |
| `SYNCED` | Order potwierdził tę lub nowszą rewizję tej samej przesyłki |
| `REVIEW_REQUIRED` | Konflikt, nieprawidłowy receipt lub limit prób; potrzebne uzgodnienie |

CC udostępnia akcje „Synchronizacja dostawy” i „Uzgodnij dostawę”. CLI:

```bash
bash scripts/control-center/control-center.sh delivery-sync 123
bash scripts/control-center/control-center.sh delivery-reconcile 123
```

Uzgodnienie ponawia bieżący snapshot. Nie usuwa blokady finansowej ani
nie naprawia niespójnej sagi. Aktywny lease daje 409; po śmierci procesu
należy poczekać na jego wygaśnięcie. Po rozstrzygnięciu przyczyny konfliktu
operator może ponownie uruchomić synchronizację.

## Migracje i wydanie

1. Zaktualizować wspólną politykę dostępu i wszystkie repliki Order z **V18**.
   Na czas mieszania wersji zatrzymać synchronizację i zmiany statusu przewoźnika.
2. Wdrożyć wszystkie repliki Delivery z **V6**, nowy Review i CC. Delivery używa
   istniejącego `ORDER_SERVICE_URL`; Compose i Kubernetes już przekazują adres
   Order. Nie kierować połączenia kontenera na jego własny localhost.
3. Uruchomić worker i sprawdzić kolejkę oraz `REVIEW_REQUIRED`. Domyślnie
   `DELIVERY_SYNC_ENABLED=true`, `DELIVERY_SYNC_DELAY_MS=5000`.
   Wyłączenie workera nie usuwa kolejki ani nie blokuje uzgodnienia operatora.

V6 zachowuje tracking i statusy starszych przesyłek. Starsze stany
`IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `PICKED_UP` i `CANCELLED`
otrzymują oczekujące uzgodnienie z rewizją 0. Historyczne dane niespójne
z Order nie są automatycznie uznawane za zapłatę. Jeden Order nadal ma jedną
tożsamość przesyłki; wymiana trackingu wymaga odrębnego procesu.

Nie wdrażano usług na dev/prod. A17 nie zamyka pełnych kompensacji A11 ani
ręcznego przygotowania i zewnętrznego odbioru Stripe A05. Zmiany zdjęć pozostają
odłożone do osobnego polecenia użytkownika.

## Weryfikacja — 26.10.2026

- 153 testy Order/Delivery/Review/Recommendation i kontroli dostępu przeszły.
  Po końcowych doprecyzowaniach przeszło 13 testów modelu i ponowień,
  w tym dodatkowa regresja niespójnego pobrania; zestawy częściowo się pokrywają.
  Logi: `tmp/a17-services-tests.log`, `tmp/a17-final-focused.log`.
- 98 testów Python/CC i składnia Bash: `tmp/a17-cc-tests.log`.
- Wszystkie 15 JAR-ów zbudowano z tą samą aktualną biblioteką uprawnień:
  `tmp/a17-platform-package.log`, `tmp/a17-package-verification.json`.
- **112 kontroli HTTP** na PostgreSQL: rzeczywiste Order/Review oraz dwie
  instancje Delivery, migracja V5→V6, V18 Order, OpenAPI, opinie w trzech
  etapach zakupu, rekomendacje i bestsellery, odmowy ról, pobranie,
  blokady finansowe, utrata odpowiedzi, stara rewizja, śmierć procesu po
  commicie Order i odzyskanie przez worker bez operatora.
  Raport: `tmp/purchased-lifecycle-04470eae/report.json`.
- Regresja A10: **52 kontrole HTTP**, dwie instancje Payment, cztery pobrania,
  zapas po TTL, faktury i awaria po rozliczeniu Order.
  Raport: `tmp/deferred-payments-2bc299be/report.json`.

Regresje modelu i uprawnień oraz `scripts/qa/purchased-lifecycle-e2e.py`
uruchamia CI. Bazy i procesy obu regresji były jednorazowe i zostały usunięte.
