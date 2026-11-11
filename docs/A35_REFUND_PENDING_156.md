# A35 — pokrycie procesu zwrotów i metryki refund pending/review, pakiet 156

Stan: **02.11.2026 · Audit_0.1 · lokalny odbiór częściowego A35**.
[Dokument przekazania](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md) jest bieżącym punktem wznowienia.

## Wykonane

- [x] Przegląd stanów procesu zwrotów (`PaymentRefund`) oraz obecnego pokrycia kolejek w `BusinessMetricsConfiguration` w `payment-service`.
- [x] Identyfikacja brakujących stanów procesu: zwroty w toku (`payment_refund`) oraz zwroty wymagające interwencji operatora (`payment_refund_review`).
- [x] Dodanie metryki `payment_refund` w `payment-service`:
  `SELECT COUNT(*), MIN(created_at) FROM payment_refunds WHERE (status IN ('REQUESTED','PENDING') OR (status = 'SUCCEEDED' AND business_completed = FALSE)) AND (business_outcome IS NULL OR business_outcome <> 'REVIEW_REQUIRED')`.
- [x] Dodanie metryki `payment_refund_review` w `payment-service`:
  `SELECT COUNT(*), MIN(created_at) FROM payment_refunds WHERE status = 'REVIEW_REQUIRED' OR business_outcome = 'REVIEW_REQUIRED'`.
- [x] Wzbogacenie reguł alertów Prometheus w `infra/monitoring/prometheus/rules/business.yml`:
  - `BusinessQueueStalled`: dodanie kolejki `payment_refund` do sprawdzanych kolejek procesowych (próg wieku > 300 s przez 2 min).
  - `BusinessDeadLetter`: dodanie kolejki `payment_refund_review` do sprawdzanych kolejek wymagających operatora (liczba > 0 przez 1 min).
  - `BusinessMetricsMissing`: aktualizacja sprawdzania liczby kolejek w instancji `payment-service:8080` z 4 na 6 (`!= 6`).
- [x] Aktualizacja testu w `scripts/qa/monitoring-check.py` sprawdzającego brak jednej kolejki dla `payment-service:8080`.
- [x] Nowy zestaw testów w `payment-service`: `PaymentBusinessMetricsTest.java` (4 testy weryfikujące rejestrację wszystkich 6 kolejek, cykl życia zwrotu, przejście do review required, rozstrzygnięcie przez operatora oraz poprawność wieku od `created_at`).

## Znaczenie metryk i stanów

| Kolejka | Filtr SQL | Przeznaczenie |
|---|---|---|
| `payment_refund` | `(status IN ('REQUESTED','PENDING') OR (status = 'SUCCEEDED' AND business_completed = FALSE)) AND (business_outcome IS NULL OR business_outcome <> 'REVIEW_REQUIRED')` | Zwroty w toku (oczekiwanie na realizację przez bramkę Stripe lub dokończenie zadań biznesowych w Order/Inventory). Objęte alarmem `BusinessQueueStalled`. |
| `payment_refund_review` | `status = 'REVIEW_REQUIRED' OR business_outcome = 'REVIEW_REQUIRED'` | Zwroty zatrzymane z powodu konfliktu tożsamości, przekroczenia okna czasowego lub błędu dostawcy wymagającego ręcznej analizy. Objęte alarmem `BusinessDeadLetter`. |

Wiek obu kolejek liczony jest od `created_at`, co zapobiega sztucznemu odmładzaniu zaległych procesów przy ponowieniach.
Kolejka `payment_refund` wyklucza stany review, dzięki czemu nie dubluje alarmów z `BusinessDeadLetter`.

## Odbiór lokalny

- **109 testów Java w `payment-service` (w tym 4 nowe w `PaymentBusinessMetricsTest`) — PASS.**
- **281 testów Java w `platform-security` — PASS.**
- **116 metod testowych Python QA — PASS.**
- **21 scenariuszy promtool w `monitoring-check.py` — PASS.**
- Kontrakty QA cyklu życia danych i retencji (`data-lifecycle-check.py`, `technical-data-classification-check.py`, `commerce-retention-check.py`, `operational-retention-check.py`, `retention-policy-check.py`, `internal-copies-check.py`) — PASS.
- Kod wyjścia `retention-policy-check.py --require-ready` potwierdzony: **2** (`NOT_READY`).
- Inwarianty zachowane: brak aktywacji czyszczenia (`activationAllowed = false`, `runtimeWired = false`), brak zmian w odłożonym Stripe A05 i architekturze A41.
