# A35 — spójność między bazami danych (Cross-Database Consistency) i Watchdog monitoringu, pakiet 159 (z uzupełnieniem R08 z pakietu 162)

Stan: **02.11.2026 · Audit_0.1 · lokalny odbiór częściowego A35 (Cross-Database Consistency & Watchdog, rozszerzony w pakiecie 162 o tryb `--live-postgres`)**.
[Dokument przekazania](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md) jest bieżącym punktem wznowienia.

## Wykonane

- [x] **Watchdog monitoringu (sygnał żywotności silnika reguł Prometheus — doprecyzowanie R08)**:
  - Wdrożono regułę alertu `Watchdog` w `infra/monitoring/prometheus/rules/business.yml` (`expr: vector(1)`, `severity: none`, `for: 0m`).
  - W środowisku lokalnym dev/test (`infra/monitoring/alertmanager/alertmanager.yml`) alert trafia do odbiornika `local-console`. Reguła potwierdza lokalnie działanie ewaluacji reguł Prometheus i routingu do Alertmanagera, natomiast wykrycie martwego zewnętrznego kanału powiadomień wymaga podłączenia zewnętrznego odbiornika dead-man's-switch poza lokalnym stosem.
  - Dodano runbook operacyjny `#watchdog` w `docs/A35_ALERTY_BIZNESOWE.md`.
  - Zaktualizowano `scripts/qa/monitoring-check.py` do obsługi alertu ciągłego bez wygaszania (13 scenariuszy alertów promtool + 9 zapytań PromQL dashboardów Redis — PASS).

- [x] **Audytor spójności rozproszonej (Cross-Database Consistency Auditor — rozszerzony o `--live-postgres` w pakiecie 162)**:
  - Zaimplementowano skrypt audytowy `scripts/qa/cross-database-consistency-check.py` weryfikujący inwarianty biznesowe między 6 bazami danych mikroserwisów (`order_db`, `payment_db`, `inventory_db`, `billing_db`, `delivery_db`, `notification_db`).
  - Tryby pracy skryptu:
    - **Tryb self-test (domyślny bez flag):** uruchamia 8 deterministycznych scenariuszy w pamięci (`run_self_tests()`), weryfikujących poprawność reguł detekcji anomalii.
    - **Tryb żywych baz PostgreSQL (`--live-postgres`, dodany w pakiecie 162 dla R08):** łączy się w trybie tylko do odczytu z 6 działającymi bazami PostgreSQL (`15434` `order_db`, `15436` `payment_db`, `15435` `inventory_db`, `15446` `billing_db`, `15442` `delivery_db`, `15437` `notification_db`), pobiera rzeczywiste wiersze z tabel i weryfikuje inwarianty `I1`–`I4`.
  - Weryfikowane inwarianty:
    1. **Inwariant I1 (Zgodność zamówień opłaconych / zrealizowanych)**: każde zamówienie w stanie `PAID`, `SHIPPED`, `COMPLETED` musi posiadać:
       - potwierdzoną płatność w `payments` ze statusem `SUCCEEDED` i kwotą odpowiadającą sumie zamówienia,
       - potwierdzoną rezerwację w `inventory_reservations` (`CONFIRMED`),
       - wystawioną fakturę w `invoices` (`ISSUED`), jeżeli zamówienie wymagało faktury (`invoice_requested == true`),
       - zlecenie dostawy w `shipments`, jeżeli zamówienie jest doręczane przez kuriera,
       - zarejestrowane powiadomienie `PAYMENT_SUCCEEDED` w `notifications`,
       - spójny stan sagi w `order_sagas` (`COMPLETED` lub `RECONCILIATION_REQUIRED`).
    2. **Inwariant I2 (Kompensacja zamówień anulowanych)**: zamówienia w stanie `CANCELLED` nie mogą posiadać aktywnych niezwolnionych rezerwacji w magazynie ani nierozliczonych sukcesów płatności bez powiązanego zwrotu (`payment_refund`).
    3. **Inwariant I3 (Wykrywanie rekordów osieroconych / Inversion Check)**: wykrywanie płatności, faktur, rezerwacji lub przesyłek bez istniejącego zamówienia nadrzędnego.
    4. **Inwariant I4 (Wyrównanie flagi reconciliacji)**: potwierdzenie, że zamówienia wymagające interwencji (`lifecycle_review_required`) posiadają sagę w stanie `RECONCILIATION_REQUIRED`.

- [x] **Weryfikacja metryk biznesowych w `order-service`**:
  - Utworzono test integracyjny `OrderBusinessMetricsAndConsistencyTest` (4 testy, PASS) w `order-service`:
    - `cleanStateExposesZeroMetricsAndHealthyCollector`: stan czysty generuje `count == 0` oraz `up == 1`.
    - `pendingOrderSagasAreTrackedInMetrics`: sagi oczekujące na rezerwację/kompensację raportowane są w `order_saga`.
    - `crossDatabaseDivergenceFlagsReconciliationQueueAndPublishesDeadLetterMetrics`: zamówienie ze statusem `PAID` bez sagi zostaje automatycznie wykryte przez skaner reconciliacji, oflagowane jako `RECONCILIATION_REQUIRED`, a metryka `order_reconciliation` podnosi wartość i wiek, co uruchamia alert `BusinessDeadLetter`.
    - `resolvingReconciliationReturnsMetricToZero`: rozwiązanie problemu przez operatora zeruje kolejkę `order_reconciliation`.

## Wyniki weryfikacji

- **`OrderBusinessMetricsAndConsistencyTest` (4/4 testy Java w `order-service`) — PASS.**
- **`cross-database-consistency-check.py` (8 scenariuszy self-test + obsługa `--live-postgres` dla 6 baz danych) — PASS.**
- **`monitoring-check.py` (13 reguł alertów promtool w tym Watchdog + 9 zapytań Redis) — PASS.**
- **`data-lifecycle-check.py` (14 baz, 145 tabel, 232 migracje) — PASS.**
- **`technical-data-classification-check.py` (145 tabel, 1307 kolumn) — PASS.**
- **`commerce-retention-check.py` — PASS.**
- **`operational-retention-check.py` — PASS.**
- **`retention-policy-check.py --candidate ...` — PASS.**
- **`retention-policy-check.py --require-ready` — kod 2 / NOT_READY (zgodnie z kontraktem audytu).**
- **`readiness-check.py` (15 JVM, 14 SQL, 59 konfiguracji, 45 sond K8s) — PASS.**
- **`audit_readiness.py` (14 P1 zdefiniowanych w skrypcie, 13 zakończonych, openP1: ["A05"]) — PASS.**
- Inwarianty A43.40/A43.41 zachowane: brak aktywacji workerów D02 (`activationAllowed = false`, `runtimeWired = false`), brak nieautoryzowanych modyfikacji binarnych.
