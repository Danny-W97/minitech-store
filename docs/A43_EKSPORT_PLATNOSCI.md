# A43.24 — eksport zapisanej płatności i zwrotu własnego zamówienia

`GET /api/v1/payments/me/data-export/orders/{orderId}` pobiera JSON zapisanej
płatności oraz opcjonalnego zapisanego zwrotu finansowego dla własnego
zamówienia. Działają także alias `/api` i bezpośredni port Payment.
Własne identyfikatory zamówień można pobrać przez eksport A43.21.

Wymagany jest access JWT aktywnego klienta. Operator, token usługi i anonim
są odrzucani. Payment na każde żądanie sprawdza właściciela w Order przez
istniejące `/internal/orders/{id}/access`, własnym tokenem usługi.
Nie przekazuje JWT klienta ani capability gościa. E-mail i parametry/nagłówki
customerId nie przypisują zamówienia do konta. Dotychczasowe operatorowe
endpointy zwrotów pozostają prywatne.

Cudze, gościnne i brakujące zamówienie dają jednakowe 404. Własne zamówienie
bez zapisanej płatności także daje 404. Niedostępna lub nieprawidłowa
weryfikacja Order daje 503 przed odczytem bazy Payment, bez fallbacku/cache.
Klient wewnętrzny zachowuje istniejące limity czasu i budżet żądania.

## Dane i ograniczenia

Załącznik `payment-data.json` zawiera schemaVersion 1, scope
`payment-for-order`, complete false, generatedAt, payment, refund i omissions.

Payment obejmuje paymentId, orderId, amount, currency, paymentMethod, status,
collectedAt, createdAt i updatedAt. Refund obejmuje refundId, paymentId,
orderId, amount, currency, status, submittedAt, createdAt i updatedAt.
Przy braku rekordu zwrotu refund jest null. Brakujące czasy pozostają null.

Kwoty, waluty, metody i statusy są wartościami zapisanymi w bazie.
Eksport nie sprawdza faktycznego rozliczenia u dostawcy ani nie uzgadnia
stanu domeny. Nie inicjuje płatności, zwrotu, pobrania gotówki czy retry.
Status zwrotu nie zastępuje potwierdzenia jego skutków w innych usługach.

SQL nie wybiera checkout URL/session, kluczy idempotencji, fingerprintów,
referencji dostawcy i pobrania gotówki, payment intent/refund ID operatora,
danych osoby inicjującej zwrot, powodów/diagnostyki, lease, prób i terminów
retry ani metadanych uzgodnienia. Pozostałe tabele oraz pominięte dane
ocenia etap A43.30. Surowe webhooki i kolejki nie są eksportowane.

Własność w Order i odczyt Payment są oddzielnymi operacjami, bez wspólnej
migawki między usługami. Sama płatność i opcjonalny zwrot są czytane w jednym
zapytaniu SQL. Jeśli order_id zwrotu jest niezgodne z order_id płatności,
cały eksport zwraca 503 zamiast ujawniać albo po cichu pomijać taki rekord.

Unikalność payments.order_id i payment_refunds.payment_id ogranicza wynik
do jednego zestawu. Używane są istniejące indeksy, maxRows 1 i timeout SQL
2 s. Brak nowych migracji, zależności i zmiennych środowiskowych.

Nagłówki odpowiedzi: private/no-store, attachment i nosniff. Serwer nie
przechowuje archiwum; pobrany plik pozostaje kopią zarządzaną przez klienta.
Ten etap nie dodaje przycisku sklepu ani widoku CC i nie zmienia retencji.

## Weryfikacja

```sh
./mvnw --batch-mode -pl payment-service,api-gateway -am -Pintegration verify
python3 scripts/qa/payment-data-export-e2e.py
python3 scripts/qa/data-lifecycle-check.py
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
```

Testy obejmują kontrolę ról, autoryzację przed odczytem bazy, zapisane statusy,
kwoty i daty, brak zwrotu/płatności, niezgodność order_id oraz PostgreSQL
z rolą bez SELECT sekretów i diagnostyki. Wspólne testy OrderAccess sprawdzają
potwierdzanie własności na każde żądanie i błędne odpowiedzi źródła.

Odbiór JAR używa własnych Customer/Order/Payment/Gateway, losowych
portów/poświadczeń i odrębnych baz. Dostawca płatności jest wyłączony,
recovery/follow-up/publisher/retencja wyłączone wyłącznie w fixture.
Adres operatora wskazuje lokalną atrapę z licznikiem żądań. Odbiór wymaga
zera wywołań operatora oraz niezmienności wszystkich sześciu tabel Payment
i zamówień. Zatrzymanie własnego Order ma dać 503 na trzech ścieżkach.

## Decyzje użytkownika i dalszy zakres

A43.24 realizuje uzgodnioną projekcję odczytu i nie wymaga wyboru okresów
zachowania danych ani aktywacji usuwania. Gdy przyszły etap wymaga decyzji,
użytkownik otrzyma okno z konkretnymi opcjami i skutkami; prace zależne
poczekają na odpowiedź. Preferencję zapisano w planie zamknięcia A43.

A05 pozostaje odłożone, w tym ręczne uzupełnienie STRIPE_SECRET_KEY
i STRIPE_WEBHOOK_SECRET w dev.env.sops oraz test.env.sops i odbiór sandboxu.
Ten eksport nie używa tych kluczy. Całe A41 oraz zmiany samych zdjęć nadal
odłożone. Pełny eksport konta, usuwanie/anonimizacja i kopie pozostają otwarte.


<!-- A43_24_RESULTS -->
## Odbiór lokalny 28.10.2026

- **276 Java — PASS, bez pominiętych**: 123 platform, 33 Gateway,
  103 Payment unit i 17 PostgreSQL. W sumie 16 nowych testów; siedem
  istniejących PostgreSQL retencji jest już w wyniku.
- **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
- **JAR HTTP — PASS**, siedem grup: trzy ścieżki, niezmienność danych,
  OpenAPI, niedostępny Order i zero wywołań lokalnej atrapy dostawcy.
- Rejestr — PASS: 14 baz, 49 tabel, 123 migracje, 31 mechanizmów.
  Maven — PASS, 17 modułów. Odbiór HTTP dodany do CI; bez zdalnego runu.

Raport: `tmp/audit-a43-24/report.json`. Logi:
`tmp/a43-24-maven-verify.log`, `tmp/a43-24-python.log`,
`tmp/a43-24-http-e2e.log`. HTTP: `tmp/payment-export-ff2d7710/report.json`.
Bez danych użytkownika, sekretów Stripe, operacji finansowych, zmian
polityk runtime, restartu CC, budowy obrazów i wdrożenia dev/prod.
<!-- /A43_24_RESULTS -->
