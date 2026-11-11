# A35 — kontekst śledzenia W3C Trace Context (traceparent) i propagacja HTTP, pakiet 157

Stan: **02.11.2026 · Audit_0.1 · lokalny odbiór częściowego A35**.
[Dokument przekazania](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md) jest bieżącym punktem wznowienia.

## Wykonane

- [x] Implementacja specyfikacji W3C Trace Context w module wspólnym `platform-security` (`CorrelationIdContext.java`):
  - Stałe nagłówka `traceparent` (`TRACEPARENT_HEADER`) oraz klucze MDC SLF4J (`traceId`, `spanId`, `traceparent`).
  - Parsowanie, walidacja i sanitizacja formatu W3C `00-{traceId}-{spanId}-{flags}` (odrzucenie identyfikatorów złożonych z samych zer).
  - Bezpieczne generowanie 32-znakowych `traceId` (16 bajtów hex) oraz 16-znakowych `spanId` (8 bajtów hex) z użyciem `SecureRandom`.
  - Tworzenie identyfikatorów podrzędnych (`childTraceparent()`) przy wywołaniach wychodzących HTTP — zachowanie oryginalnego `traceId`, wygenerowanie nowego `spanId` i propagacja flag śledzenia.
  - Automatyczne zarządzanie zasięgiem i czyszczenie w `CorrelationIdContext.Scope`.
- [x] Integracja w `platformobservability.CorrelationIdFilter`:
  - Odczyt nagłówka `traceparent` lub wygenerowanie świeżego identyfikatora W3C w przypadku jego braku.
  - Propagacja do MDC i zapisanie w nagłówkach odpowiedzi (`traceparent` oraz wstecznie kompatybilny `X-Correlation-Id`).
- [x] Propagacja w wywołaniach klientów REST `CorrelationIdClientInterceptor`:
  - Dołączanie `traceparent` (`childTraceparent()`) do wszystkich wychodzących żądań RestClient.
- [x] Integracja w `api-gateway`:
  - `GatewayProxy`: propagacja nagłówka `traceparent` (child span) do wywołań downstream oraz zwracanie `traceparent` w odpowiedzi klienta.
  - `ZipExportProxy`: propagacja `traceparent` i nagłówek odpowiedzi w strumieniowanych eksportach ZIP.
- [x] Pokrycie testami:
  - 4 nowe testy jednostkowe w `platform-security/CorrelationIdTest.java` (walidacja W3C, czyszczenie MDC, wykluczenie identyfikatorów zerowych, propagacja child span).
  - 2 nowe testy integracyjne w `api-gateway/GatewayTracingTest.java` (weryfikacja propagacji nagłówków, zachowanie korelacji i poprawność formatów).

## Znaczenie i korzyści

| Element | Wartość / Format | Przeznaczenie |
|---|---|---|
| `traceparent` | `00-{trace_id}-{span_id}-{flags}` | Standardowy nagłówek W3C Distributed Tracing, umożliwiający korelację żądań między wszystkimi mikroserwisami, API Gateway oraz zewnętrznymi systemami observability (np. Grafana Tempo / OpenTelemetry). |
| `traceId` | 32 znaki hex | Globalny unikalny identyfikator całej transakcji rozproszonej, propagowany przez cały łańcuch wywołań. |
| `spanId` | 16 znaków hex | Identyfikator konkretnego wywołania / segmentu w danym serwisie. |
| `X-Correlation-Id` | ASCII / UUID | Wstecznie kompatybilny identyfikator korelacji zachowany dla dotychczasowych logów i kontraktów klientów. |

## Odbiór lokalny

- **285 testów Java w `platform-security` (4 nowe) — PASS.**
- **35 testów Java w `api-gateway` (2 nowe) — PASS.**
- **109 testów Java w `payment-service` — PASS.**
- **21 scenariuszy promtool w `scripts/qa/monitoring-check.py` — PASS.**
- Kontrakty QA cyklu życia danych i retencji (`data-lifecycle-check.py`, `technical-data-classification-check.py`, `commerce-retention-check.py`, `operational-retention-check.py`, `retention-policy-check.py`) — PASS.
- Kod wyjścia `retention-policy-check.py --require-ready` potwierdzony: **2** (`NOT_READY`).
- Inwarianty zachowane: brak aktywacji czyszczenia (`activationAllowed = false`, `runtimeWired = false`), brak zmian w odłożonym Stripe A05 i architekturze A41.
