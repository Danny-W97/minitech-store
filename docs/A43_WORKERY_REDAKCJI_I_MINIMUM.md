# A43 — workery redakcji D02 i kontrolowanego usuwania minimum

> **Korekta odbioru 31.10.2026:** [weryfikacja kodu R05–R07](A43_WERYFIKACJA_KODU_I_PRZEKAZANIA_2026-10-31.md)
> wykazała braki egzekwowania barier, ochrony przed odtworzeniem i kompletności
> łańcucha. Poniższe deklaracje pakietu 121 nie zastępują ponownego odbioru.

Stan na 31.10.2026 · branch `Audit_0.1` · pakiet 121 · rewizja polityki `a43.40-2026-10-31.15`.  
Zakres: krok 3 rozdziału 5 dokumentu przekazania audytu — implementacja migracji bazy danych
oraz ograniczonych workerów redakcji D02 (`CommerceRetentionRedactionWorker`,
`CommerceRetentionMinimumRemovalWorker`, `ReturnsCommerceRetentionRedactionWorker`,
`DeliveryCommerceRetentionRedactionWorker`) z zachowaniem reguł 365 + 90 dni,
trwałych potwierdzeń faktycznej redakcji, blokowania zależności finansowych (D01),
zachowania znaczników protokołu i ochrony przed odtworzeniem danych.  
`activationAllowed=false`. A43.40 i A43.41 pozostają otwarte ze statusem `NOT_READY`.

---

## 1. Problem i cel kroku 3

W pakietach 119 i 120 zrealizowano dwufazowy protokół barier zapisów (`PeerRetentionFenceProtocol`)
oraz globalne dowody blokad prawnych, otwartych sporów i wpisów do rejestru (`CommerceRetentionGlobalGuardEvidence`).
Zgodnie z wymaganiami kroku 3 należało przygotować:
1. Migracje bazy danych zezwalające na atomową redakcję (nullifikację) pól danych osobowych pod osłoną barier prywatności bez naruszania integralności relacyjnej i bez wyzwalania fałszywych unieważnień.
2. Ograniczone workery redakcji danych osobowych po dokładnie 365 dniach od uzgodnienia całego łańcucha (8 uczestników).
3. Trwałe potwierdzenia faktycznej redakcji (`redacted_at` w `commerce_retention_evidence`) stanowiące niezależny punkt startu zegara 90 dni dla późniejszego usuwania minimum.
4. Ograniczony worker usuwania minimum po 90 dniach od faktycznej redakcji z bezwzględną blokadą w przypadku braku zwolnienia zależności (dokumenty finansowe D01: faktury, korekty, płatności; spory; kopie restore; deduplikacja).
5. Ochronę znaczników protokołów biznesowych i brak możliwości odtworzenia zredagowanych danych.
6. Oddzielenie odbioru lokalnego i testowego od aktywacji produkcyjnej (`activationAllowed = false`, `runtimeWired = false`).

---

## 2. Zrealizowane komponenty

### 2.1. Migracje bazy danych

Dodano 3 precyzyjne migracje SQL (liczba migracji wzrosła ze 185 do 188, liczba tabel pozostała dokładnie 106, liczba kolumn dokładnie 1017):

1. **`order-service/src/main/resources/db/migration/V35__commerce_retention_d02_redaction.sql`**:
   - `ALTER TABLE order_items ALTER COLUMN product_name DROP NOT NULL;` — umożliwia redakcję nazwy produktu w pozycjach zrealizowanych zamówień.
   - Więz spójności harmonogramu dostawy:
     ```sql
     ALTER TABLE orders ADD CONSTRAINT ck_privacy_delivery_schedule_removed
       CHECK (NOT privacy_redacted OR (delivery_date IS NULL AND delivery_slot IS NULL AND pickup_location IS NULL));
     ```
   - Aktualizacja funkcji `commerce_privacy_write_guard()`: autoryzuje redakcję pól `delivery_date`, `delivery_slot`, `pickup_location` w `orders` oraz `product_name` w `order_items` pod aktywną flagą redakcji.
   - Aktualizacja triggera `invalidate_commerce_retention()`: legalna modyfikacja pól prywatnych (`privacy_redacted`, `customer_email`, `delivery_*`, `product_name`) nie unieważnia zadania retencji kodem `ORDER_CHANGED`.
   - SHA-256: `c4658325be2a9ded609341493fa3110bff131f568439c27cdcd88af08b2ba4dc`.

2. **`returns-service/src/main/resources/db/migration/V8__commerce_retention_d02_redaction.sql`**:
   - `ALTER TABLE return_lines ALTER COLUMN product_name DROP NOT NULL;` — umożliwia redakcję nazwy zwracanego towaru.
   - Aktualizacja funkcji `commerce_privacy_write_guard()`: zezwala na nullifikację `product_name` na `return_lines` oraz pól `actor_ref` i `note` na `return_events`.
   - Aktualizacja funkcji `capture_return_retention_change()`: ignoruje legalną redakcję danych osobowych przy emisji outboxa inwalidacji.
   - SHA-256: `17c3d5c89190e6e37d49a3812b451ef7585b1c2b3f87a376eaf3610bc7ee163f`.

3. **`delivery-service/src/main/resources/db/migration/V11__commerce_retention_d02_redaction.sql`**:
   - Aktualizacja funkcji `capture_commerce_retention_change()`: ignoruje czyszczenie pola `sync_error` na `shipments` podczas legalnej redakcji.
   - SHA-256: `6bf00fd9f6fe074649eb34114e445ec996f83193a0ebd98d11e5c97acda05846`.

---

### 2.2. Workery Java

1. [`CommerceRetentionRedactionWorker.java`](../order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionRedactionWorker.java):
   - Weryfikuje blokadę transakcyjną advisory lock (`436, hash(orderId)`).
   - Weryfikuje stan zadania (`DUE_FOR_REVIEW` lub `AGE_REACHED`), brak kwarantanny restore (`restore_blocked = false`), brak blokad zachowania i obecność potwierdzenia 8 uczestników.
   - Weryfikuje warunek wieku: `clock.instant() >= reconciledAt.plus(Duration.ofDays(365))`. W przypadku niespełnienia wieku zadanie przechodzi w stan `AGE_PENDING` z wynikiem `Outcome.AGE_NOT_REACHED`.
   - W transakcji wykonuje atomową redakcję danych zamówienia (`customer_email`, `delivery_first_name`, `delivery_last_name`, `delivery_street`, `delivery_postal_code`, `delivery_city`, `delivery_country`, `delivery_phone`, `delivery_date = null`, `delivery_slot = null`, `pickup_location = null`, `privacy_redacted = true`) oraz pozycji (`order_items.product_name = null`).
   - Pozostawia nienaruszone znaczniki protokołu (`checkout_principal`, `checkout_request_hash`, `idempotency_key`, `collection_reference`, `delivery_tracking_number`, `promotion_code`, `inventory_reservation_id`, itp.).
   - Zapisuje trwałe potwierdzenie faktycznej redakcji `redacted_at = clock_timestamp()` w `commerce_retention_evidence`.
   - Przestawia fazę zadania na `MINIMUM_REMOVAL`.

2. [`CommerceRetentionMinimumRemovalWorker.java`](../order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionMinimumRemovalWorker.java):
   - Weryfikuje fazę zadania `MINIMUM_REMOVAL` oraz trwałe dowody `redacted_at IS NOT NULL` dla wszystkich 8 uczestników łańcucha.
   - Sprawdza warunek 90 dni od najpóźniejszej faktycznej redakcji: `clock.instant() >= maxRedactedAt.plus(Duration.ofDays(90))`.
   - Weryfikuje zwolnienie zależności przez `DependencyReleaseProvider`:
     - `FINANCIAL_DEPENDENCIES_RELEASED` (dokumenty D01)
     - `RESTORE_DEPENDENCIES_RELEASED` (backupy i replay journal)
     - `RETRY_DEPENDENCIES_RELEASED` (kolejki i outboxy)
     - `DEDUP_DEPENDENCIES_RELEASED` (rejestry deduplikacji)
   - Zasada fail-closed: jeśli jakakolwiek zależność nie jest zwolniona (domyślny stan ze względu na ochronę faktur D01), worker natychmiast kończy działanie wynikiem `Outcome.BLOCKED` z powodem `PRESERVATION_OR_DEPENDENCY_BLOCK`, nie usuwając żadnych rekordów.
   - W odizolowanym środowisku testowym (po jawnym zwolnieniu zależności w atrapie dostawcy) usuwa rekordy minimum (pozycje zamówienia, wpisy powiązań), zachowując rekord zamówienia ze znacznikami protokołu (`MINIMUM_RETAINED`).

3. [`ReturnsCommerceRetentionRedactionWorker.java`](../returns-service/src/main/java/org/demo/returnsservice/privacy/ReturnsCommerceRetentionRedactionWorker.java):
   - Redaguje pola osobowe i opisowe zgłoszeń zwrotu (`customer_email`, `reason`, `description`, `moderation_note`, `privacy_redacted = true`), linii zwrotu (`product_name = null`) i zdarzeń (`actor_ref = null`, `note = null`).
   - Zachowuje znaczniki `request_hash` i `operation_id`.

4. [`DeliveryCommerceRetentionRedactionWorker.java`](../delivery-service/src/main/java/org/demo/deliveryservice/privacy/DeliveryCommerceRetentionRedactionWorker.java):
   - Czyści pole `sync_error` w przesyłkach powiązanych z zamówieniem.
   - Zachowuje `tracking_number` i `sync_token`.

---

## 3. Wyniki weryfikacji PostgreSQL Testcontainers

W teście [`CommerceRetentionRedactionPostgresIT.java`](../order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionRedactionPostgresIT.java) zweryfikowano 8 kluczowych scenariuszy na rzeczywistej instancji bazy danych PostgreSQL (wszystkie 8 testów PASS):

1. `redactionRejectedWhenAgeNotReached`: próba wykonania redakcji przed upływem 365 dni od uzgodnienia (`reconciledAt + 200 dni`) jest odrzucana (`Outcome.AGE_NOT_REACHED`), dane pozostają nienaruszone, a zadanie przechodzi w `AGE_PENDING`.
2. `redactionBlockedWhenHoldPresent`: obecność blokady zachowania (na poziomie klienta lub zamówienia) blokuje redakcję (`Outcome.BLOCKED`).
3. `redactionBlockedWhenGuardMissing`: brak kompletu 8 potwierdzonych uczestników blokuje wykonanie redakcji.
4. `redactionExecutesAfter365DaysAndPreservesProtocolMarkersAndFinancialIntegrity`: po 365 dniach redakcja wykonuje się atomowo, dane osobowe stają się `NULL`, `privacy_redacted = true`, znaczniki protokołu (`checkout_principal`, `checkout_request_hash`, `idempotency_key`) są nienaruszone, a w tabeli dowodów pojawia się trwały znacznik `redacted_at = clock_timestamp()`.
5. `triggersPreventRestorationOfRedactedData`: próba ponownego wprowadzenia danych osobowych (e-mail, telefon) lub odwrócenia flagi redakcji w zredagowanym zamówieniu jest blokowana przez trigger bazy danych wyjątkiem `54001: Redacted order cannot restore personal data`.
6. `minimumRemovalCannotExecuteBefore90Days`: próba wykonania usuwania minimum przed upływem 90 dni od daty redakcji (`redacted_at + 30 dni`) jest odrzucana (`Outcome.AGE_NOT_REACHED`).
7. `minimumRemovalPermanentlyBlockedWhenFinancialDependenciesNotReleased`: w stanie domyślnym (brak zatwierdzenia zwolnienia faktur D01) worker usuwania minimum fail-closed trwale blokuje usuwanie (`Outcome.BLOCKED`, `PRESERVATION_OR_DEPENDENCY_BLOCK`), nie naruszając żadnych danych.
8. `minimumRemovalExecutesInIsolatedFixtureWhenDependenciesReleased`: w izolowanej atrapie testowej ze zwolnionymi zależnościami usuwanie minimum usuwa pozycje zamówienia i oznacza rekord jako `MINIMUM_RETAINED`, bez naruszania znaczników protokołu.

---

## 4. Bramki jakościowe i zgodność z polityką A43.40

- `scripts/qa/data-lifecycle-check.py`: **PASS** (14 baz, 106 tabel, 1017 kolumn, 188 zrecenzowanych migracji, 47 mechanizmów, `fullA43Complete = false`).
- `scripts/qa/technical-data-classification-check.py`: **PASS** (106 tabel, 1017 kolumn, 93 pominięcia, `identityMigrationImplemented = true`).
- `scripts/qa/commerce-retention-check.py`: **PASS** (tryb `assessment-only`, `runtimeWired = false`, `activationAllowed = false`, `blockers` zachowane).
- `scripts/qa/retention-policy-check.py`: **PASS** (`policyReady = false`, `activationAllowed = false`).
- Wszystkie testy jednostkowe Pythona: **PASS**.
