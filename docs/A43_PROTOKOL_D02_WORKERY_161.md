# A43 — protokół D02 i weryfikacja regresji fail-closed workerów redakcji, pakiet 161

Stan: **02.11.2026 · Audit_0.1 · weryfikacja regresji fail-closed istniejących klas wykonawczych D02 (z korektą review R05/R06/R07/R10 z pakietu 162)**.  
[Dokument przekazania](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md) oraz [raport review z 02.11.2026](REVIEW_REPOZYTORIUM_STAN_AUDYTU_2026-11-02.md) opisują bieżący stan.

> [!IMPORTANT]
> **Korekta zakresu pakietu 161 (R06/R07):** Pakiet 161 potwierdził w testach integracyjnych i bramkach QA działanie blokad *fail-closed* istniejących od pakietu 155 klas wykonawczych (`Outcome.BLOCKED` / `"RETENTION_EXECUTION_NOT_CONFIGURED"`). Pakiet 161 **nie wdraża** docelowego wykonywania redakcji D02 ani nie zamyka etapów **A43.40** i **A43.41**, które pozostają w statusie `OPEN` / `NOT_READY`. Ponadto w kontrakcie `scripts/qa/contracts/retention-decisions.json` pozycje `D01` i `D09` zachowują status `partial`, a wolumen `privacy-journal-safe` nie jest jeszcze zamontowany w manifestach `compose*.yaml` ani `deploy/k8s/**`.

---

## 1. Cel i rzeczywisty zakres pakietu 161

Celem pakietu 161 było wykonanie weryfikacji regresyjnej blokad ochronnych protokołu **D02** w ramach otwartych etapów **A43.40 / A43.41** po zapisaniu docelowych decyzji użytkownika **D01–D17** w pakiecie 158:

1. **D01 (Finanse — decyzja docelowa vs kontrakt):** Docelowa decyzja użytkownika określa 5 lat retencji dokumentów finansowych (`invoices`, `invoice_items`, `invoice_corrections`, `payments`, `payment_refunds`) od końca roku kalendarzowego z `hard_block = true` w dev/test. W pliku `scripts/qa/contracts/retention-decisions.json` pozycja `D01` pozostaje na etapie `partial` (z blokadą usuwania tabel finansowych).
2. **D02 (Zakupy i pozycje — stan fail-closed):** Docelowy model przewiduje 365 dni od ostatecznego uzgodnienia całego łańcucha (8 uczestników: Order, Payment, Inventory, Returns, Promotion, Delivery, Billing, Notification) do redakcji danych osobowych oraz kolejne 90 dni od `redacted_at` do usunięcia minimum. W obecnym kodzie workery D02 działają wyłącznie jako bezpieczne strażniki *fail-closed* i odmawiają wykonania mutacji (`Outcome.BLOCKED`, `"RETENTION_EXECUTION_NOT_CONFIGURED"`).
3. **D09 (Sejf rejestru usunięć — decyzja docelowa vs stan wdrożenia):** W kodzie istnieje weryfikator podpisów Ed25519 (`JournalReceiptVerifier`), natomiast dedykowany wolumen `privacy-journal-safe` nie jest jeszcze skonfigurowany w `compose*.yaml` ani `deploy/k8s/**`, a `D09` w `retention-decisions.json` ma status `partial`.
4. **D17 & Zasada Fail-Closed (z korektą R05):** Dla workerów A43.40/A43.41 utrzymane są inwarianty `activationAllowed = false`, `runtimeWired = false`. Starsze techniczne mechanizmy retencji posiadają odrębne konfiguracje (np. `CUSTOMER_SECURITY_RETENTION_ENABLED: true` w `customer-service/src/main/resources/application.yaml`).

---

## 2. Zweryfikowane komponenty strażników fail-closed D02

### 2.1. Strażnicy wykonawczy w `order-service`
- [`CommerceRetentionRedactionWorker.java`](../order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionRedactionWorker.java):
  - Weryfikuje subject (zamówienie, wersję, generację łańcucha).
  - Ze względu na brak uprawnień wykonawczych w runtime (`activationAllowed() == false`), bezwarunkowo zwraca `Outcome.BLOCKED` z kodem `"RETENTION_EXECUTION_NOT_CONFIGURED"`.
  - Zapobiega mutacjom w bazie danych nawet przy obecności potwierdzonych metadanych `CONFIRMED`.
- [`CommerceRetentionMinimumRemovalWorker.java`](../order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionMinimumRemovalWorker.java):
  - Bezwarunkowo blokuje usunięcie pozycji (`Outcome.BLOCKED`, `"RETENTION_EXECUTION_NOT_CONFIGURED"`).
  - Flagi wywołania fikstur (`allowDestructiveExecutionInIsolatedFixture`) nie mogą wymusić destrukcyjnego wykonania.

### 2.2. Strażnicy w serwisach satelickich
- [`DeliveryCommerceRetentionRedactionWorker.java`](../delivery-service/src/main/java/org/demo/deliveryservice/privacy/DeliveryCommerceRetentionRedactionWorker.java):
  - Odmawia mutacji przesyłek, zwracając `Outcome.BLOCKED` z `redactedAt = null`.
- [`ReturnsCommerceRetentionRedactionWorker.java`](../returns-service/src/main/java/org/demo/returnsservice/privacy/ReturnsCommerceRetentionRedactionWorker.java):
  - Odmawia mutacji zgłoszeń zwrotów/reklamacji, zwracając `Outcome.BLOCKED` z `redactedAt = null`.

### 2.3. Baza danych i nieodwracalność redakcji
- Migracje PostgreSQL (Order V35/V36, Returns V8, Delivery V11) pilnują na poziomie triggerów i więzów `CHECK`, że rekordy oznaczone jako `privacy_redacted = TRUE` nie mogą odzyskać danych osobowych (`UPDATE privacy_redacted = FALSE` lub ponowny zapis tekstu swobodnego jest blokowany przez bazę danych).

---

## 3. Wyniki weryfikacji testowej

### 3.1. Testy integracyjne PostgreSQL Testcontainers i jednostkowe
- **`CommerceRetentionRedactionPostgresIT` (order-service):** **9/9 testów PASS** (pełna baza PostgreSQL 16-alpine z 50 migracjami Flyway):
  1. `noExecutionAuthorityNeverMutatesDataEvenWithAllConfirmedMetadata` — PASS
  2. `callerClockAndFixtureBooleanCannotEnableDestructiveExecution` — PASS
  3. `irreversibleFlagDoesNotRequireAnAccountDeletionLock` — PASS
  4. `redactedItemCannotBeRestoredByLateUpdate` — PASS
  5. `redactedItemCannotBeReinsertedByOldCommand` — PASS
  6. `redactedSagaCannotRegainFreeText` — PASS
  7. `contactAndScheduleConstraintsRemainEnforced` — PASS
  8. `concurrentOldChildWriteCannotCrossRedactionCommit` — PASS
  9. `ordinaryUnredactedPurchasesStillAllowBusinessUpdates` — PASS
- **`DeliveryRetentionExecutionTest` (delivery-service):** **1/1 test PASS**.
- **`ReturnsRetentionExecutionTest` (returns-service):** **1/1 test PASS**.

### 3.2. Komplet bramek QA
1. `python3 scripts/qa/data-lifecycle-check.py` — **PASS** (14 baz, 145 tabel, 232 migracje, 47 mechanizmów).
2. `python3 scripts/qa/technical-data-classification-check.py` — **PASS** (145 tabel, 1307 kolumn, 232 migracje).
3. `python3 scripts/qa/commerce-retention-check.py` — **PASS** (10 tabel D02, 127 kolumn, 8 uczestników łańcucha, 5 tabel finansowych D01 ściśle wyłączonych).
4. `python3 scripts/qa/operational-retention-check.py` — **PASS** (5 klas operacyjnych, fail-closed P09).
5. `python3 scripts/qa/retention-policy-check.py --candidate scripts/qa/contracts/retention-observe-example.json` — **PASS** (20 parametrów).
6. `python3 scripts/qa/retention-policy-check.py --require-ready` — Kod wyjścia **2** (`NOT_READY`, zgodnie z kontraktem braku aktywacji w audycie — skorygowano opis kodu wyjścia zgodnie z `R10`).
7. `python3 scripts/qa/monitoring-check.py` — **PASS** (15 targetów, 13 alertów w tym lokalny heartbeat Watchdog + 9 dashboardów Redis).
8. `python3 scripts/qa/readiness-check.py` — **PASS** (15 komponentów JVM, 14 SQL readiness, 59 konfiguracji, 45 sond K8s).
9. `python3 scripts/qa/audit_readiness.py` — **PASS** (skrypt sprawdza jawny podzbiór zagadnień P1 skonfigurowany w `audit_readiness.py`; pełna lista otwartych obszarów znajduje się w `PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md`).
10. `python3 scripts/qa/cross-database-consistency-check.py` — **PASS** (w pakiecie 162 rozszerzony dodatkowo o tryb `--live-postgres` odczytujący 6 żywych baz PostgreSQL zgodnie z `R08`).

---

## 4. Wnioski

- Blokady *fail-closed* workerów D02 (`activationAllowed = false`, `runtimeWired = false`) działają poprawnie i uniemożliwiają nieautoryzowane usunięcie lub redakcję danych.
- Etapy **A43.40** oraz **A43.41** pozostają **otwarte (`NOT_READY`)** — wymagają pełnego wdrożenia kontraktowego D01/D09, docelowego protokołu wykonawczego workerów D02 oraz zebrania dowodów środowiskowych dev/test.
