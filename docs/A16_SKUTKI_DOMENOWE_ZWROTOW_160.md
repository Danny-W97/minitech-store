# A16 — Pakiet 160 (z poprawkami R01–R04 z Pakietu 162): Automatyzacja skutków domenowych zwrotów

**Data:** 02.11.2026  
**Status:** Wdrożono w Pakiecie 160 i naprawiono po review w Pakiecie 162 (`R01`–`R04`: porty i topologia usług, manifesty Kubernetes, wąskie uprawnienia `returns-service`, ekspozycja `checkoutSessionId` w `PaymentResponse`, odrzucanie nieukończonych i niepotwierdzonych refundacji, deterministyczne klucze idempotencji odporne na ponowienie po częściowej awarii oraz ścisła polityka kwotowa bez zgadywania rabatów/dostawy i bez automatycznego rozliczania `COMPLAINT`). Obszar A16 pozostaje otwarty do pełnego odbioru end-to-end na żywym stosie.  
**Powiązane dokumenty:** [PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md), [REVIEW_REPOZYTORIUM_STAN_AUDYTU_2026-11-02.md](REVIEW_REPOZYTORIUM_STAN_AUDYTU_2026-11-02.md), [AUDIT_0.1_POSTEP.md](AUDIT_0.1_POSTEP.md), [A16_ZWROTY_REKLAMACJE.md](A16_ZWROTY_REKLAMACJE.md).

---

## 1. Kontekst i cel wdrożenia

W ramach etapu A16.1 (Pakiet 29) zaimplementowano bezpieczne przyjmowanie zgłoszeń zwrotów i reklamacji, powiązanie pozycji, wersjonowaną historię decyzji operatora oraz maszynę stanów:
```text
REQUESTED → UNDER_REVIEW → APPROVED
                         ↘ REJECTED
```
W etapie A16.1 bezpośrednie przejście do stanu `COMPLETED` było zablokowane błędem 409 `RETURN_DOMAIN_EFFECTS_NOT_RECONCILED`, a zatwierdzona sprawa pozostawała w stanie `APPROVED` z flagą `effectsState = DOMAIN_RECONCILIATION_REQUIRED`. Zgodnie z rejestrem audytu A16 sam status `COMPLETED` nie mógł udawać rozliczenia bez rzeczywistych skutków w powiązanych serwisach domenowych.

W **Pakiecie 160** wprowadzono orkiestrację skutków domenowych w `returns-service`, a w **Pakiecie 162** usunięto luki integracyjne i domenowe wykryte podczas niezależnego review (`R01`–`R04`).

---

## 2. Architektura orkiestracji skutków domenowych (po naprawach R01–R04)

W `returns-service` działa orkiestrator `ReturnDomainEffectsService` oraz klienci HTTP komunikujący się z podsystemami platformy:

```text
                  ┌────────────────────────────────────────┐
                  │            returns-service             │
                  │   POST /returns/{id}/reconcile-effects │
                  └───────────────────┬────────────────────┘
                                      │
            ┌─────────────────────────┼─────────────────────────┐
            ▼                         ▼                         ▼
   ┌─────────────────┐       ┌─────────────────┐       ┌─────────────────┐
   │inventory-service│       │ billing-service │       │ payment-service │
   │ :18083          │       │ :18095          │       │ :18084          │
   │ POST /inventory/│       │ POST /invoices/ │       │ POST /payments/ │
   │ items/{id}/     │       │ {id}/corrections│       │ {id}/refunds    │
   │ receipts        │       │                 │       │                 │
   └─────────────────┘       └─────────────────┘       └─────────────────┘
     Przyjęcie towaru         Korekta faktury           Zwrot płatności
      na stan zapasu           (credit note)          (potwierdzony SUCCEEDED)
```

### 2.1. Topologia, uprawnienia i sieć (`R01`)
- **Domyślne adresy i zmienne środowiskowe:**
  - `ReturnInventoryClient` -> `http://localhost:18083` (`INVENTORY_SERVICE_URL: http://inventory-service:8080` w `compose.apps.yaml`).
  - `ReturnPaymentClient` -> `http://localhost:18084` (`PAYMENT_SERVICE_URL: http://payment-service:8080` w `compose.apps.yaml`).
  - `ReturnBillingClient` -> `http://localhost:18095` (`BILLING_SERVICE_URL: http://billing-service:8080` w `compose.apps.yaml`).
- **Zależności sieciowe Kubernetes:** w `deploy/components.json` dodano `billing-service`, `inventory-service` i `payment-service` do listy `returns-service.http`, oraz zsynchronizowano `deploy/k8s/base/network-policies.yaml` (`generate-k8s.py --check` i `validate-k8s.py` — PASS).
- **Wąskie uprawnienia usługowe (`RoutePolicy` + kontrolery):** aktor wewnętrzny `Actor("SERVICE", "returns-service", ...)` otrzymał dostęp wyłącznie do 6 tras potrzebnych do uzgodnienia zwrotu:
  - `POST /inventory/items/{productId}/receipts` (`StockOperationController`),
  - `GET /invoices/order/{orderId}`, `GET /invoices/{id}/correction-balance`, `POST /invoices/{id}/corrections` (`InvoiceController`),
  - `GET /payments/order/{orderId}`, `POST /payments/{paymentId}/refunds` (`PaymentRefundController`),
  przy zachowaniu blokady (`403`) na wszystkich pozostałych trasach operacyjnych.

### 2.2. Skutek magazynowy i trwała idempotencja przy ponowieniach (`R03`)
- **Klient:** `ReturnInventoryClient`.
- Dla każdej pozycji zwróconej w sprawie (`request.items()`) wysyłane jest żądanie przyjęcia na stan magazynowy:
  `POST /inventory/items/{productId}/receipts` z ciałem `{ "quantity": item.quantity, "operationId": <UUID> }`.
- **Klucz deterministyczny per sprawa i pozycja (niezależny od zmiennego `operationId` wywołania):**  
  `UUID.nameUUIDFromBytes(("return-restock-v1:" + returnId + ":" + orderItemId).getBytes(UTF_8))`.
- Dzięki temu ponowienie `reconcileEffects` po częściowej awarii (np. gdy magazyn przyjął towar, ale `billing-service` lub `payment-service` zwrócił błąd i operator ponowił żądanie z nowym `operationId`) nie dubluje przyjęcia zapasu ani przy powtórnym wywołaniu lokalnym, ani w `inventory-service` (`StockReceiptIdempotencyRecord`).

### 2.3. Skutek fakturowy i bezpieczna obsługa wersji korekty (`R03`)
- **Klient:** `ReturnBillingClient`.
- Weryfikacja obecności faktury: `GET /invoices/order/{orderId}`.
- Jeśli faktura nie została wystawiona (kod 404), krok zostaje oznaczony jako `NOT_INVOICED`.
- Jeśli faktura istnieje:
  - Pobierane jest aktualne saldo i wersja (`GET /invoices/{id}/correction-balance`), a `expectedVersion` dla sprawy jest zapamiętywane na wypadek ponowienia po błędzie kolejnego kroku.
  - Wystawiana jest korekta faktury (`POST /invoices/{id}/corrections`) ze stałym kluczem idempotencji `return-corr-v1-{returnId}` (niezależnym od `operationId` wywołania).

### 2.4. Skutek finansowy i wymóg potwierdzonego statusu `SUCCEEDED` (`R02`)
- **Klient i kontrakt HTTP:** `PaymentResponse` w `payment-service` eksponuje `checkoutSessionId` (obok `providerReference`, `collectionReference` i `checkoutUrl`), dzięki czemu `ReturnPaymentClient` widzi rzeczywiste metadane sesji Stripe przez HTTP.
- **Rozpoznawanie i weryfikacja refundacji:**
  - Płatności kartowe/Stripe (rozpoznawane po `checkoutSessionId`, `paymentMethod` `CARD`/`STRIPE`, prefiksie `pi_`/`cs_` w `providerReference` lub `checkoutUrl`) wymagają wywołania `POST /payments/{paymentId}/refunds` ze stałym kluczem `return-ref-v1-{returnId}`.
  - Zwrot przechodzi do `COMPLETED` **wyłącznie wtedy**, gdy odpowiedź z `payment-service` ma status `"SUCCEEDED"` i niepusty identyfikator refundacji. Statusy `"REQUESTED"`, `"PENDING"`, `"FAILED"`, `"REVIEW_REQUIRED"` itp. są odrzucane błędem 409 `RETURN_PAYMENT_REFUND_NOT_SETTLED`.
  - Płatności inne niż Stripe/karta (np. `CASH`, `PICKUP`), dla których brak potwierdzonego dowodu zwrotu środków w `payment-service`, nie otrzymują już fikcyjnego statusu `SETTLED_NON_STRIPE_*`, lecz są zatrzymywane błędem 409 `RETURN_NON_STRIPE_REFUND_UNCONFIRMED`.
- **Autoryzacja zwrotów częściowych i zrealizowanych zamówień w `payment-service` / `order-service`:** dla wewnętrznego aktora `service:returns-service` dozwolone jest rozliczenie zwrotu na zamówieniu w stanie `COMPLETED` oraz dla częściowej kwoty zwróconych pozycji (`0 < amount <= payment.amount`), przy zachowaniu dotychczasowych restrykcji dla zwykłych ręcznych refundacji operatorskich przed wysyłką.

### 2.5. Ścisła polityka kwotowa i rozróżnienie `RETURN` vs `COMPLAINT` (`R04`)
- Automatyczna orkiestracja `reconcileEffects` jest dozwolona wyłącznie dla zgłoszeń typu `ReturnType.RETURN`. Zgłoszenia reklamacyjne (`ReturnType.COMPLAINT`) są blokowane błędem 409 `RETURN_COMPLAINT_REQUIRES_MANUAL_RESOLUTION` (reklamacja może wymagać naprawy, wymiany lub indywidualnej obniżki ceny zamiast automatycznego przyjęcia pełnowartościowego zapasu na stan).
- Wyliczenie kwoty zwrotu (`calculateVerifiedRefundAmount`) wymaga:
  - dodatniej ceny jednostkowej (`unitPrice > 0`) na każdej pozycji zamówienia w `order-service` (brak fallbacku do `order.totalPrice()` oraz brak fikcyjnej kwoty `10.00`),
  - ścisłej zgodności sumy pozycji zamówienia z `order.totalPrice()` (`sum(unitPrice * orderedQty) == order.totalPrice()`). Jeżeli zamówienie zawiera nierozpisany na pozycje rabat lub koszt dostawy, automatyczne rozliczenie jest blokowane błędem 409 `RETURN_ORDER_TOTAL_MISMATCH_REQUIRES_ALLOCATION` **przed** wykonaniem jakichkolwiek mutacji w magazynie, fakturowaniu czy płatnościach.

---

## 3. Weryfikacja testowa (Pakiet 160 + Pakiet 162)

1. **Testy jednostkowe i integracyjne w `ReturnDomainEffectsTest.java`:**
   - Pozytywne uzgodnienie `APPROVED` → `COMPLETED` z potwierdzonym `SUCCEEDED` w `payment-service`.
   - Idempotentne ponowienie dla tego samego `operationId`.
   - Ponowienie z **nowym** `operationId` po częściowej awarii (np. błąd płatności po udanym przyjęciu na stan i korekcie faktury) — brak duplikacji przyjęcia towaru i brak błędu `CORRECTION_KEY_REUSED`.
   - Odrzucenie nieukończonych refundacji (`REQUESTED`, `PENDING`, `FAILED`, `REVIEW_REQUIRED`) — sprawa pozostaje w `APPROVED`.
   - Odrzucenie niepotwierdzonych płatności nie-Stripe (`CASH`) bez fikcyjnego `SETTLED_NON_STRIPE_CASH`.
   - Odrzucenie zamówień bez ceny jednostkowej lub z rabatem/kosztem dostawy nierozpisanym na pozycje (`RETURN_ORDER_UNIT_PRICE_MISSING`, `RETURN_ORDER_TOTAL_MISMATCH_REQUIRES_ALLOCATION`) przed jakąkolwiek mutacją zewnętrzną.
   - Odrzucenie automatycznego rozliczenia dla `COMPLAINT` (`RETURN_COMPLAINT_REQUIRES_MANUAL_RESOLUTION`).
2. **Testy uprawnień i polityk sieciowych:**
   - `AccessControlTest` (`platform-security`) — weryfikacja wąskich uprawnień `returns-service`.
   - `scripts/deploy/validate-k8s.py` i pełny zestaw testów Python K8s — **PASS**.
   - ` retention-policy-check.py --require-ready` — kod wyjścia **2** (`NOT_READY`, zgodnie z kontraktem braku aktywacji fizycznej w A43).
