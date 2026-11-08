# A35 — wdrożenie progów SLO, reguły alertów i rozstrzygnięcie decyzji audytowych, pakiet 158

Stan: **02.11.2026 · Audit_0.1 · lokalny odbiór częściowego A35 i zapis decyzji docelowych D01–D17 (z korektą review R05/R07/R08 z pakietu 162)**.
[Dokument przekazania](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md) jest bieżącym punktem wznowienia.

## Wykonane

- [x] Rozstrzygnięcie i formalne zatwierdzenie przez użytkownika (02.11.2026) 9 docelowych punktów decyzyjnych (uwaga R07: zapis decyzji docelowych użytkownika nie oznacza automatycznego przepisania kontraktu `scripts/qa/contracts/retention-decisions.json`, w którym `D01` i `D09` zachowują status kontraktowy `partial` do czasu pełnego wdrożenia w A43.40/A43.41):
  1. **D01/P01 (Finanse — decyzja docelowa):** 5 lat retencji dokumentów finansowych (`invoices`, `payments`, `payment_refund`) od końca roku kalendarzowego (zgodnie z art. 74 ust. 2 polskiej Ustawy o Rachunkowości i Ordynacją Podatkową), weryfikacja co 7 dni; `hard_block = true` na tabelach finansowych w dev/test. W bieżącym kontrakcie `retention-decisions.json` pozycja `D01` pozostaje oznaczona jako `partial` (z zachowaniem dotychczasowego okna testowego i blokady usuwania) do czasu pełnego odbioru A43.40.
  2. **D09/P03–P05 (Rejestr usunięć — decyzja docelowa):** Docelowy dedykowany wolumen o uprawnieniach *append-only* dla procesu rotacji i *read-only* dla usług (`privacy-journal-safe` z kopią lustrzaną na `E:\Klucze\privacy-journal`), klucze w SOPS/K8s Secret. W obecnym stanie repozytorium (`compose*.yaml`, `deploy/k8s/**`) wolumen `privacy-journal-safe` nie jest jeszcze zamontowany, a pozycja `D09` w `retention-decisions.json` ma status `partial`.
  3. **D14/P06 (Odbiorniki):** W dev/test wyłącznie lokalny Mailpit (`localhost:1025`) z limitem 1000 wiadomości / 30 dni oraz wbudowany silnik asystenta w Control Center (brak zewnętrznych wywołań i zero wycieku promptów/PII).
  4. **D17 (Aktywacja czyszczenia A43.40/A43.41 — korekta R05):** Utrzymanie trybu bezpiecznego audytu dla nowych workerów retencji prywatności i handlowej D02: `activationAllowed = false`, `runtimeWired = false` (workery D02 pozostają zablokowane w trybie `assessment-only` / fail-closed bez fizycznego usuwania danych D02). **Uwaga techniczna (R05):** starsze, techniczne mechanizmy retencji posiadają własne, odrębne flagi konfiguracyjne — w szczególności `customer-service` ma w `customer-service/src/main/resources/application.yaml` domyślnie włączone czyszczenie 7-dniowych technicznych zdarzeń bezpieczeństwa (`CUSTOMER_SECURITY_RETENTION_ENABLED: true`), dlatego nie należy formułować twierdzenia, że wszystkie mechanizmy retencji w całej platformie działają w trybie `dry-run`.
  5. **A05 (Stripe):** Formalnie ODŁOŻONE w audycie lokalnym; platforma używa wbudowanego symulatora płatności w `payment-service`.
  6. **A35 (SLO):** Wdrożenie przyjętych progów operacyjnych i biznesowych w regułach Prometheus.
  7. **A40 / U40-02 (SEO):** Kanoniczny adres wewnętrzny ze zmiennej `APP_CANONICAL_URL` (domyślnie `http://localhost:8080`), `noindex, nofollow` dla zamówień i panelu operatora CC, otwarte indeksowanie dla produktów.
  8. **A41 (Architektura):** Utrzymanie obecnej architektury mikroserwisów Spring Boot (pełna separacja domen, niezależne bazy PostgreSQL).
  9. **Zdjęcia:** Pozostawienie plików graficznych bez modyfikacji binarnych; optymalizacja wyłącznie nagłówkami cache HTTP i kompresją Nginx.
- [x] Rozszerzenie reguł Prometheus w `infra/monitoring/prometheus/rules/business.yml`:
  - `BusinessQueueSloBreach`: alert o krytycznym priorytecie (`severity: critical`), gdy wiek najstarszego elementu w kolejce zwrotów (`payment_refund`) lub faktur po opłaceniu (`paid_without_invoice`) przekracza 3600 sekund (60 minut) przez ponad 2 minuty.
  - `RedisStreamLagHigh`: alert ostrzegawczy (`severity: warning`), gdy liczba nieprzetworzonych wiadomości (lag) w strumieniach płatności Redis przekracza 100 wiadomości przez ponad 2 minuty.
  - Uwaga dot. reguły `Watchdog` (R08): `Watchdog` (`vector(1)` z routingiem do `local-console` w dev/test) jest lokalnym sygnałem heartbeat silnika reguł Prometheus; pełne wykrycie awarii kanału powiadomień wymaga zewnętrznego odbiornika dead-man's-switch poza lokalnym stosem.
- [x] Aktualizacja procedur operacyjnych (runbooków) w `docs/A35_ALERTY_BIZNESOWE.md`:
  - Sekcje `#businessqueueslobreach` oraz `#redisstreamlaghigh` z instrukcjami diagnostyki dla operatora.
- [x] Aktualizacja testów weryfikacyjnych promtool w `scripts/qa/monitoring-check.py`:
  - Dodanie scenariuszy symulujących wyzwolenie (`firing`) i rozwiązanie (`resolved`) dla obu nowych alertów SLO. Łącznie 12 scenariuszy alertów promtool + 9 scenariuszy zapytań dashboardów Redis.

## Wyniki weryfikacji

- **12 scenariuszy testowych reguł Prometheus promtool — PASS.**
- **9 scenariuszy zapytań PromQL dashboardów Redis — PASS.**
- **`scripts/qa/monitoring-check.py` — PASS.**
- **`scripts/qa/readiness-check.py` (15 komponentów JVM, 14 baz SQL, 59 konfiguracji, 45 sond K8s) — PASS.**
- **`scripts/qa/audit_readiness.py` (13/14 P1 wykonane, A05 odłożone) — PASS.**
- Inwarianty A43.40/A43.41 zachowane: brak aktywacji workerów D02 (`activationAllowed = false`, `runtimeWired = false`), brak nieautoryzowanych zmian binarnych.
