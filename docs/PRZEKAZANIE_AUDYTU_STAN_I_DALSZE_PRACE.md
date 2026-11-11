# Audyt aplikacji — przekazanie stanu i dalszych prac

**Wynik po Pakiecie 164 i uwagach przeglądu (03.11.2026): Wdrożenie Kroku 6 A43 (A43.41 — procedury odbioru końcowego, audytor gotowości, formularz decyzji użytkownika oraz naprawa ustaleń przeglądu).**
[Zweryfikowany stan kodu, dokumentacji i bilans pozostałych prac](REVIEW_REPOZYTORIUM_STAN_AUDYTU_2026-11-02.md).
W **Pakiecie 164** oraz w ramach wdrożenia uwag z przeglądu technicznego zrealizowano:
- **A43.41 (Krok 6):** wdrożono audytor gotowości `scripts/qa/a43-final-readiness-check.py` oraz zestaw testów `scripts/qa/unit/test_a43_final_readiness.py` (7/7 PASS).
- **Rozwiązanie uwag przeglądu technicznego:**
  1. **A16 (Dwufazowa orkiestracja zwrotów):** Rozdzielono orkiestrację na Faza 1 (odczytowe pre-checki płatności, kwot, kwalifikacji Stripe i salda faktury) oraz Faza 2 (mutacje: magazyn, faktura, refundacja). Dla metod COD lub niepotwierdzonych płatności proces rzuca błąd przed jakimikolwiek mutacjami; 36/36 testów Java PASS.
  2. **Rozgraniczenie retencji:** Rozdzielono działającą retencję bezpieczeństwa poświadczeń w `customer-service` (`CUSTOMER_SECURITY_RETENTION_ENABLED=true`, czyszczenie sesji i MFA) od retencji komercyjnej D02 (workery `BLOCKED_FAIL_CLOSED`). Doprecyzowano pole zerowych mutacji audytora (`auditorExecutedDataMutations = 0`).
  3. **Zakres obserwowalności A35:** Oznaczono zakres bramki jako weryfikację alertów SLO Prometheusa, reguły Watchdog oraz kontraktów i fixture w pamięci; audyt 6 fizycznych baz wymaga flagi `--live-postgres`.
  4. **Decyzja D01 a kontrakty maszynowe:** Zatwierdzona przez użytkownika 5-letnia retencja finansowa określa docelową politykę operacyjną; w repozytorium maszynowe kontrakty celowo zachowują stan bazowy (`stageComplete = false`, `partial`), zapewniając bezpieczny tryb fail-closed (`NOT_READY`, kod wyjścia 2).
- **Weryfikacja 6 wymiarów platformy:** zweryfikowano architekturę 14 serwisów + Gateway (korelacja W3C traceparent), schemat danych (145 tabel, 1307 kolumn, 232 migracje, ochrona 5 tabel finansowych), macierz regresji UI (A33, 28 par bez retry/flaky), obserwowalność i SLO (Watchdog `vector(1)`, alerty, spójność 6 baz), Disaster Recovery (Age X25519, manifest, kwarantanna odtwarzania) oraz dowody środowiskowe dev/test z Kroku 5.
- **Niezmiennik fail-closed:** zachowano ścisłą blokadę wykonawczą (`status = NOT_READY`, kod wyjścia 2 dla `--require-ready`, `activationAllowed = false`, `runtimeWired = false`, `auditorExecutedDataMutations = 0`).

Bilans otwartych obszarów pozostaje bez zmian: **10 głównych obszarów / 11 pozycji po rozdzieleniu A43.40 i A43.41** (`A05`, `A16`, `A33`, `A34`, `A35`, `A36`, `A40`, `A41`, `A43.40`, `A43.41`, `A44`).

**Stan 03.11.2026 · branch `Audit_0.1` · pakiet 164 + poprawki przeglądu (dwufazowa orkiestracja A16, uściślenie retencji i obserwowalności) · A43.40 Krok 5 zakończony / A43.41 otwarte (`NOT_READY`, kod 2).**

**Aktualny punkt przekazania:** **Pakiet 164** — [A43_KROK6_ODBIOR_KONCOWY_PROCEDURY_164.md](A43_KROK6_ODBIOR_KONCOWY_PROCEDURY_164.md).
Zweryfikowano kompletem testów Java (36/36 w `returns-service`), testami K8s (`validate-k8s.py` PASS), kompletem bramek QA w Pythonie (PASS; `--require-ready` zwraca kod `2` / `NOT_READY`) oraz audytorem `a43-final-readiness-check.py` (PASS, NOT_READY kod 2).
Nie włączono czyszczenia (`activationAllowed = false`, `runtimeWired = false`). Zakresy wymagające wartości/decyzji użytkownika zebrano w formularzu decyzyjnym.

---

## 1. Gdzie zakończono prace

Ostatnim wdrożonym lokalnie zakresem jest **pakiet 164 wraz z poprawkami przeglądu z 03.11.2026**:
- Wdrożono audytor gotowości platformy `scripts/qa/a43-final-readiness-check.py` wraz z testami `scripts/qa/unit/test_a43_final_readiness.py` (7/7 PASS), generujący raport `tmp/a43-step6-readiness/final-readiness-report.json`.
- Rozwiązano problem kolejności wykonania w A16: `ReturnDomainEffectsService` przeprowadza odczytowe pre-checki płatności i salda faktury przed jakąkolwiek mutacją (36/36 testów modułu `returns-service` PASS).
- Zweryfikowano szablony K8s (A34), bramki regresji przeglądarek (A33), alerty SLO i spójność baz (A35), odzyskiwanie platformy (A36), HTTP frontendu (A40) i bramki CI (A44.4).
- Zebrano dowody środowiskowe dev/test Kroku 5 (`environmental-evidence-check.py`, 8 kategorii read-only).
- Użytkownik formalnie **zaakceptował rekomendacje decyzyjne** (03.11.2026): D01 (5 lat retencji finansowej jako docelowa polityka), D09 (wolumen `privacy-journal-safe`), P09 (30 dni logów operacyjnych / ochrona logów bezpieczeństwa i CC), D17 (tryb audytu fail-closed do czasu uruchomienia klastra), A05 i A41 (formalnie odłożone).
- **Następny priorytet techniczny:** Docelowe wdrożenie na środowiskach dev/test (konfiguracja klastra Kubernetes/Docker na maszynie docelowej, podpięcie wolumenu `privacy-journal-safe`, konfiguracja kluczy SOPS przed uruchomieniem produkcyjnym). A05, A41 i zdjęcia pozostają odłożone zgodnie z decyzją.

| Parametr wznowienia | Wartość |
|---|---|
| Ostatni pakiet / etap | **164 (Krok 6 A43 / A43.41 — procedury odbioru końcowego, regresja platformowa i formularz decyzyjny)** |
| Rewizja polityki | `a43.40-2026-11-02.8` — zaktualizowany odcisk `RoutePolicy.java` w `commerce-retention.json` i `retention-policy.json`, bez zmiany decyzji |
| Skrót SHA-256 polityki | Zapisany w `scripts/qa/contracts/retention-observe-example.json` (`policySha256`) |
| Zapis decyzji użytkownika | `a43-decisions-2026-10-30.2` (`scripts/qa/contracts/retention-decisions.json` — stan bazowy audytu; docelowa polityka 5 lat D01/D09/P09 zatwierdzona operacyjnie w Pakiecie 164) |
| Pełna gotowość | `NOT_READY`, potwierdzony kod bramki: **2** (`--require-ready`); kod 1 oznacza błąd kontroli i wymaga wyjaśnienia |
| Zgoda na aktywację | Kontrakt: `activationAllowed=false`, `runtimeWired=false`; same flagi wyniku nie zapobiegają wywołaniu mutujących metod |
| Ukończenie A43 / bieżącego etapu | `fullA43Complete=false`, `stageComplete=false` |
| Inwentarz schematu | **145 tabel, 1307 kolumn, 232 migracje**, 47 mechanizmów, 6 przestrzeni Redis, 20 kategorii kopii, 26 polityk |
| Odbiór pakietu 162 | **Testy Java w `platform-security` (103 PASS), `order-service`, `inventory-service`, `payment-service` (`StripeSettlementRecoveryTest` 19/19 PASS), `returns-service` (`ReturnDomainEffectsTest` 10/10, `ReturnOrderClientTest` 2/2, `ReturnWorkflowTest` 13/13 PASS) + `validate-k8s.py` PASS + `test_k8s_templates.py` (13/13 PASS) + `test_retention_policy.py` (18/18 PASS) + 17 bramek QA PASS**. Naprawy R01–R10 po review z 02.11.2026. |
| Historyczny odbiór pakietu 161 | **9 testów PostgreSQL Testcontainers w order-service (CommerceRetentionRedactionPostgresIT) + 2 testy satelickie (Returns/Delivery) + bramki QA PASS**. Weryfikacja regresyjna blokad fail-closed istniejących workerów D02 (`RETENTION_EXECUTION_NOT_CONFIGURED`). A43.40 i A43.41 pozostają otwarte. |
| Historyczny odbiór pakietu 160 | **31 Java w returns-service (7 nowych w ReturnDomainEffectsTest) + 9 bramek QA PASS**. Automatyzacja skutków domenowych zwrotów: magazyn (restock), faktury (korekta), płatności (refundacja), endpoint reconcile-effects i blokada bezpośredniego COMPLETED. Bez odbioru dev/test. |
| Historyczny odbiór pakietu 159 | **4 Java w order-service + 8 scenariuszy cross-database consistency + 22 scenariusze promtool (13 alertów w tym Watchdog + 9 dashboardów Redis) PASS**. Audytor spójności rozproszonej 6 baz, testy metryk order_reconciliation w order-service, reguła Watchdog. Bez odbioru dev/test. |
| Historyczny odbiór pakietu 158 | **12 scenariuszy promtool + 9 zapytań Redis + formalne rozstrzygnięcie decyzji D01–D17 PASS**. Reguły alertów SLO w Prometheus: BusinessQueueSloBreach i RedisStreamLagHigh. Bez odbioru dev/test. |
| Historyczny odbiór pakietu 157 | **285 Java w platform-security (4 nowe) + 35 api-gateway (2 nowe) + 109 payment-service + 116 Python + 21 scenariuszy promtool PASS**. W3C traceparent, traceId/spanId w MDC, propagacja child traceparent w RestClient i GatewayProxy. Bez odbioru dev/test. |
| Historyczny odbiór pakietu 156 | **109 Java w payment-service (4 nowe) + 281 platform-security + 116 Python + 21 scenariuszy promtool PASS**. 4 nowe testy PaymentBusinessMetricsTest, aktualizacja reguł alertów Prometheus (6 kolejek). Bez odbioru dev/test. |
| Historyczny odbiór pakietu 155 | **64 unikalne Java (19 nowych) + 116 Python + 21 scenariuszy promtool PASS**. 7 przypadków rzeczywistego Redis, 9 H2, pozostałe 48 jednostkowe/Mock HTTP/konteksty Spring. 9 nowych scenariuszy zapytań Grafany i 12 regresji alertów. 2 bieżące JAR. `tmp/a35-redis-metrics-155/report.json`. Bez odbioru dev/test. |
| Historyczny odbiór pakietu 154 | **104 unikalne testy Java (75 nowych) + 145 metod Python PASS, bez błędów i pominięć w zaakceptowanych zestawach**; 60 SQL/HTTP, 2 Redis, 6 H2, pozostałe 36 jednostkowe/Mock HTTP/konteksty Spring. 59 konfiguracji, 45 ścieżek sond, 15 adapterów w źródłach/JAR. `tmp/a35-readiness-154/report.json`. Bez pełnego odbioru usług dev/test. |
| Historyczny odbiór pakietu 153 | **145 kombinacji test/przeglądarka + 25 metod Python + 16 testów Node PASS; krytyczna macierz 28/28, bez skip/flaky/retry**. 124 Chromium + 7 Firefox + 7 WebKit + 7 mobile. Pełny UI Chromium i 28 scenariuszy krytycznych. Mapa P1: 13/14; release blokuje A05. `tmp/a33-critical-153/report.json`. Bez rzeczywistego Stripe/GitHub/dev/test. |
| Historyczny odbiór pakietu 152 | **12 testów HTTP serwera CC + pomiar przypiętego Nginx PASS**; sześć ciał tekstowych −74,61%, identyczna treść po rozpakowaniu, 304/404/cache/Vary/noindex potwierdzone. 19 plików graficznych bez zmian. `tmp/a40-http-152/report.json`. Bez pomiaru browser vitals i bez wdrożenia na dev/test. |
| Odbiór A43 pakietu 151 | **82 unikalne Java (75 PostgreSQL / 7 jednostkowych) + 116 Python PASS**, 37 nowych Java, 45 regresji; 3 nowe metody Python i 8 mutacji. `tmp/a43-versioned-inspection-151/report.json`; pełny drill `tmp/privacy-recovery-9d0077d7/report.json` PASS. 1 krok, 2 wersje, 5 prób zachowane bez wznowienia. |
| Historyczny odbiór pakietu 150 | **72 unikalne Java (65 PostgreSQL / 7 jednostkowych) + 113 Python PASS**, bez błędów i pominięć w zaakceptowanych zestawach; 45 nowych Java (27 Root / 11 HTTP / 7 jednostkowych), 3 Python i 8 mutacji. Ponowiono 27 testów po korekcie izolacji fikstury, bez podwójnego liczenia. `tmp/a43-review-versions-150/report.json`. Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-fc1d051a/report.json`; 2 wersje zachowane pod kwarantanną bez odnowienia. |
| Historyczny odbiór pakietu 149 | **106 unikalnych Java (94 PostgreSQL / 12 jednostkowych) + 110 Python PASS**, bez pominięć; 37 nowych Java (25 Root PostgreSQL / 12 HTTP), 8 mutacji w istniejącym teście Python. `tmp/a43-restoration-review-149/report.json`. Nieudane przebiegi i diagnostyka zachowane oddzielnie; końcowy pełny odbiór z jawną inspekcją fikstur po cofniętym czasie, bez osłabienia reguł i podwójnego liczenia. Bez zmian schematu/restore; historyczny drill 147: `tmp/privacy-recovery-fb3ee86e/report.json`. |
| Historyczny odbiór pakietu 148 | **116 unikalnych Java (104 PostgreSQL / 12 jednostkowych) + 110 Python PASS**, bez pominięć; 57 nowych Java (34 Root PostgreSQL / 23 HTTP), 7 mutacji w istniejącym teście Python. `tmp/a43-restoration-progress-148/report.json`. Bez zmian schematu/restore; pełnego drillu nie powtarzano. Historyczny drill 147: `tmp/privacy-recovery-fb3ee86e/report.json`. |
| Historyczny odbiór pakietu 147 | **139 unikalnych Java (125 PostgreSQL / 14 jednostkowych) + 110 Python PASS**, bez pominięć; 47 nowych Java (27 Root PostgreSQL / 20 HTTP), 3 Python i 7 mutacji. `tmp/a43-restoration-dispatch-147/report.json`. Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-fb3ee86e/report.json`; 2 próby / 1 wynik / 1 stop oraz 7 receiptów źródeł zachowane pod kwarantanną; pozostała próba bez wyniku, bez wznowienia. |
| Historyczny odbiór pakietu 146 | **147 unikalnych Java (109 PostgreSQL / 38 jednostkowych) + 107 Python PASS**, bez pominięć; 92 nowe Java, 3 Python i 7 mutacji kontraktu. `tmp/a43-source-restoration-146/report.json`. Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-e9f11d6b/report.json`; siedem źródłowych receiptów zachowanych pod kwarantanną, bez wznowienia. |
| Historyczny odbiór pakietu 145 | **168 unikalnych Java (156 PostgreSQL / 12 jednostkowych) + 104 Python PASS**, bez pominięć; 56 nowych Java / 3 Python, 7 mutacji polityki. Po dopracowaniu odczytu i modelu historii powtórzono 88 przypadków bez doliczania ich drugi raz. `tmp/a43-recovery-planning-145/report.json`. Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-9f20b6ee/report.json`; 4 próby / 2 wyniki / 1 rozliczenie / 1 intencja zachowane, bez wznowienia lub wykonania. |
| Historyczny odbiór pakietu 144 | **112 Java (100 PostgreSQL / 12 jednostkowych) + 101 unikalnych Python PASS**, bez pominięć; 44 nowe Java (37 Root / 7 HTTP), 6 nowych mutacji w istniejącym teście Python. `tmp/a43-repair-assessment-144/report.json`. Schemat/restore bez zmian; pełny drill 143 historyczny, nie powtarzano go w 144. |
| Historyczny odbiór pakietu 143 | **177 Java (153 PostgreSQL / 24 jednostkowe) + 101 unikalnych Python PASS**, bez pominięć; 54 nowe Java i 3 Python, dodatkowo 6 mutacji polityki w istniejącym teście. `tmp/a43-hold-history-143/report.json`. Pełny drill 14 baz/Redis PASS: 2 próby/1 wynik zachowane, PENDING bez wyniku, bez wznowienia. `tmp/privacy-recovery-b7d43150/report.json`. V46 tylko na fiksturach. |
| Historyczny odbiór pakietu 142 | **178 Java (154 PostgreSQL / 24 jednostkowe) + 98 unikalnych Python PASS**, bez pominięć; 64 nowe Java (52 HTTP/SQL + 12 jednostkowych), 8 dodatkowych mutacji polityki w istniejącym teście Python. `tmp/a43-hold-reconciliation-142/report.json`. Schemat/restore bez zmian; pełny drill 140 historyczny, nie powtarzano go w 142. |
| Historyczny odbiór pakietu 141 | **70 Java (58 PostgreSQL / 12 jednostkowych) + 98 unikalnych Python PASS**, bez pominięć; 27 nowych PostgreSQL i 6 dodatkowych mutacji polityki w istniejącym teście Python. `tmp/a43-hold-progress-141/report.json`. Bez zmian schematu/restore; pełny drill 140 pozostaje historycznym dowodem, nie powtórzono go w 141. |
| Historyczny odbiór pakietu 140 | **87 Java (75 PostgreSQL / 12 jednostkowych) + 98 unikalnych Python PASS**, bez pominięć; 65 nowych Java i 2 Python. Drill 14 baz/Redis PASS: 2 próby i 1 ACK zachowane, niepotwierdzona próba bez ACK; rodzic w kwarantannie, bez wznowienia. `tmp/a43-hold-delivery-140/report.json`; `tmp/privacy-recovery-03d88010/report.json`. V45 tylko na fiksturach. |
| Historyczny odbiór pakietu 139 | **52 PostgreSQL + 96 unikalnych Python PASS**, bez pominięć; 21 nowych przypadków Java i 6 Python. Drill 14 baz/Redis PASS: wniosek w kwarantannie, 7 identyfikatorów i powiązanie zachowane; bez doręczenia lub wznowienia. `tmp/a43-hold-intake-139/report.json`; `tmp/privacy-recovery-7b175b22/report.json`. V44 wyłącznie na fiksturach. |
| Historyczny odbiór pakietu 138 | **112 Java (97 PostgreSQL / 15 jednostkowych) + 90 unikalnych Python PASS**, bez pominięć; 49 nowych przypadków, pozostałe regresje. Drill 14 baz/Redis PASS: 7 rejestrów i 10 obserwacji (7 BLOCKED) zachowane. `tmp/a43-all-source-holds-138/report.json`; `tmp/privacy-recovery-937a46c5/report.json`. 6 migracji wyłącznie na fiksturach. |
| Historyczny odbiór pakietu 137 | **132 Java (117 PostgreSQL / 15 jednostkowych) + 90 unikalnych Python PASS**, bez pominięć; 12 nowych przypadków i pozostałe regresje. Drill 14 baz/Redis PASS: rejestr Returns i 4 obserwacje, w tym historyczny BLOCKED, zachowane bez aktualnego upoważnienia. `tmp/a43-source-holds-137/report.json`; `tmp/privacy-recovery-acaf7452/report.json`. Returns V12/Order V43 tylko na fiksturach. |
| Historyczny odbiór pakietu 136 | **121 Java (107 PostgreSQL / 14 jednostkowych) + 90 unikalnych Python PASS**, bez pominięć. 24 nowe przypadki, pozostałe regresje; HTTP 7 źródeł i historia Inventory. Drill 14 baz/Redis PASS: zachowane 3 obserwacje, w tym Inventory, bez aktualnego upoważnienia. `tmp/a43-guard-coverage-136/report.json`; `tmp/privacy-recovery-e7ff4722/report.json`. V42 tylko na fiksturach. |
| Historyczny odbiór pakietu 135 | **46 PostgreSQL + 90 unikalnych Python PASS**, bez pominięć; 24 nowe scenariusze awarii i 22 regresje, 15 workflow/31 HTTP. `tmp/a43-recovery-faults-135/report.json`. Bez nowych migracji i powtórzenia pełnego drillu; historyczny drill 134 pozostaje poniżej. |
| Historyczny odbiór pakietu 134 | **92 PostgreSQL + 90 unikalnych Python PASS**, bez pominięć; 83 workflow i 9 HTTP. Drill 14 baz/Redis PASS: odtworzony właściciel usunięty, cleanup-only/budżet/historia zachowane. `tmp/a43-round-recovery-134/report.json`; tmp/privacy-recovery-9dca08db/report.json. V41 wyłącznie na fiksturach. |
| Historyczny odbiór pakietu 133 | **59 PostgreSQL + 90 unikalnych Python PASS**, bez pominięć; 35 nowych przypadków replay i 24 regresje HTTP. Drill 14 baz/Redis PASS: zachowane 14 znaczników siedmiu źródeł. `tmp/a43-fence-replay-133/report.json`; tmp/privacy-recovery-87aee146/report.json. 7 migracji tylko na fiksturach. |
| Historyczny odbiór pakietu 132 | **94 PostgreSQL + 90 unikalnych Python PASS**, bez pominięć; 70 workflow/rejestr, 24 HTTP. Pełny drill 14 baz/Redis PASS, 3 rundy w kwarantannie, zachowane 2 próby i ich stany, bez RPC odzyskiwania. `tmp/a43-round-history-132/report.json`; tmp/privacy-recovery-321eddc9/report.json. V40 tylko na fiksturach. |
| Historyczny odbiór pakietu 131 | **27 PostgreSQL + 85 unikalnych Python PASS**, bez pominięć; 24 nowe przypadki HTTP i 3 regresje źródeł SQL. `tmp/a43-intent-http-131/report.json`. Wszystkie 7 źródeł rzeczywiste, 8 baz; introspekcja testowa, bez pełnych aplikacji dev/test. Bez nowych migracji i ponownego pełnego drillu restore. |
| Historyczny odbiór pakietu 130 | **175 PostgreSQL + 85 unikalnych Python PASS**, bez pominięć. XML, logi i odciski źródeł: `tmp/a43-intent-fences-130/report.json`. Bez nowych migracji i ponownego pełnego drillu restore. Sześć źródeł SQL w rundzie koordynatora; Inventory osobno, pełna runda HTTP siedmiu źródeł jeszcze nieodebrana. |
| Historyczny odbiór pakietu 129 | **136 PostgreSQL + 85 unikalnych Python PASS**, bez pominięć. XML, logi i odciski źródeł: `tmp/a43-intent-review-129/report.json`. Bez zmian schematu i ponownego pełnego drillu restore; ostatni drill pochodzi z 128. |
| Historyczny odbiór pakietu 128 | **45 PostgreSQL + 85 unikalnych Python PASS**, drill 14 baz/Redis PASS, `tmp/a43-intents-128/report.json`. Nie doliczać do bieżącego przebiegu. |
| Historyczny odbiór pakietu 127 | **137 Java (15 jednostkowych / 122 PostgreSQL) + 80 unikalnych Python PASS**, drill 14 baz/Redis PASS, `tmp/a43-receipts-127/report.json`. Nie doliczać do bieżącego przebiegu. |
| Historyczny odbiór pakietu 126 | **132 Java (15 jednostkowych / 117 PostgreSQL) + 80 unikalnych Python PASS**, `tmp/a43-http-126/report.json`. Nie doliczać do bieżącego przebiegu. |
| Historyczny odbiór pakietu 125 | **112 Java (15 jednostkowych / 97 PostgreSQL) + 80 unikalnych Python PASS**, `tmp/a43-source-125/report.json`. Nie doliczać do bieżącego przebiegu. |
| Historyczny odbiór pakietu 124 | **77 PostgreSQL / 80 unikalnych Python PASS**, `tmp/a43-global-124/report.json`. Nie doliczać do bieżącego przebiegu. |
| Historyczny odbiór pakietu 123 | **333 Java / 80 unikalnych Python PASS; pełny drill 14 baz i Redis PASS**. Nie są liczbą testów powtórzonych w 124. |
| Zapisane wyniki Pakietu 122 | XML: 14 jednostkowych Java, łącznie 209 platform-security, bez błędów. Nie dowodzą P09 fail-closed ani integracji z magazynami |
| Zapisane wyniki Pakietu 121 | XML: 8 PostgreSQL Order bez błędów; brakuje odbioru całego łańcucha i scenariuszy wykrytych w przeglądzie |
| Odbiór bramek QA | `data-lifecycle-check.py` (PASS), `technical-data-classification-check.py` (PASS), `commerce-retention-check.py` (PASS), `retention-policy-check.py --candidate` (PASS) |

„Wykonane lokalnie” oznacza kod i odbiór w opisanym środowisku testowym.
Nie oznacza wdrożenia migracji, konfiguracji ani funkcji na rzeczywiste dev,
test lub produkcję. W ostatnim pakiecie nie aktywowano czyszczenia,
nie zmieniano sekretów i nie wykonywano operacji na rzeczywistych danych.

---

## 2. Wdrożone zmiany — przekrój całej aplikacji

Poniższe zakresy są wykonane i zweryfikowane lokalnie w granicach zapisanych
w [rejestrze postępu](AUDIT_0.1_POSTEP.md). Zastrzeżenia są częścią statusu.

| ID | Co zmieniono i jaki jest efekt |
|---|---|
| A01–A03 | Kontrola właściciela danych, uprawnienia klienta/operatora/usług oraz zaufany adres backendu. |
| A04 | Serwerowe ceny i warunki dostawy, próg konta oraz kontrola aktywnych punktów odbioru. |
| A06–A09 | Kontrolowane przejścia zamówień, współbieżne rezerwacje zapasu, idempotentne płatności i trwałe zadania skutków zakupu z ponowieniami oraz obsługą błędów. |
| A10–A11 | Pobranie z osobnym rozliczeniem i wpłatą operatora; anulowanie nieopłaconych zakupów z kompensacją. A11 obejmuje wariant MVP, bez pełnego odwracania opłaconego zakupu. |
| A12–A15 | Faktura z opłaconego zakupu, saldo korekt i ich deduplikacja, rezerwacja limitów kuponu oraz trwały zamiar i bezpieczne odzyskiwanie checkoutu. |
| A16.1 | Zweryfikowane zgłoszenia zwrotów/reklamacji, zakres pozycji, wersjonowane decyzje i historia operatora. Pełne A16 pozostaje otwarte. |
| A17–A18 | Poprawna kwalifikacja opłaconych zakupów do opinii/rekomendacji/bestsellerów; źródłowa historia cen i migawki. Pełne okno historii cen musi zebrać się po uruchomieniu. |
| A19–A21 | DELETE w CORS, rzeczywiste API klientów w CC oraz atomowe przyjęcia/korekty magazynu z bezpiecznym ponowieniem operacji. |
| A22–A26 | Klucze MFA i rotacja JWT, jednorazowe poświadczenia, wspólne limity API, ochrona sesji/Host/Origin CC oraz przeniesienie sekretów AI do SOPS. |
| A27–A31 | Ograniczone odczyty i batch, paginacja, cache/eksport PDF, budżety HTTP i krótsze transakcje. Zakres samych zdjęć pominięty zgodnie z decyzją użytkownika. |
| A32 | Trwałe inboxy/outboxy, rezerwacje pracy, deduplikacja, odzyskiwanie pending i mechanizmy retencji. |
| A33 (153) | Pełny lokalny UI Chromium, macierz odzyskiwania, kontrola wyników CI, poprawki sesji produktu i serwera QA. A05 blokuje pełne A33. |
| A34.1 | Wspólny spis 16 komponentów i komplet szablonów Kubernetes, ograniczenia podów, graf sieci, SMTP oraz walidacja/admission. Pełne A34 pozostaje otwarte. |
| A35 — częściowe | A35.1: metryki dziewięciu kolejek i alarmy; 154: readiness SQL/korelacja; 155: pending/lag Billing i Notification oraz panele. Pełny zakres i odbiór docelowy pozostają otwarte. |
| A36.1 | Szyfrowane zestawy 14 baz i Redis, uwierzytelniony manifest, izolowane odtwarzanie oraz retencja całych zestawów. Pełne A36 pozostaje otwarte. |
| A37–A39 | Poprawki dostępności sklepu, trwała historia czatu z powiadomieniami per wpis i publiczne opinie bez danych administracyjnych. A37 nie jest deklaracją pełnej zgodności WCAG. |
| A42 | A42.1–A42.21 zamknięte lokalnie: wspólne mechanizmy Maven/HTTP/korelacji, podział modułów sklepu i CC, ochrona sesji/operacji oraz bramki regresji. |
| A43.1–A43.39 | Mechanizmy cyklu życia danych, eksporty właścicielskie, koordynator eksportu i UI, protokół usuwania, adaptery redakcji, zabezpieczenia restore, ograniczenie danych lokalnych oraz procedury ręczne. |
| A43.40 (P114–P122) | Mapowanie pól, zadania, outboxy i obserwacje źródeł. P119–P122 dodają częściowe protokoły, kolektory GUARD, workery i polityki; weryfikacja wykazała braki barier, ochrony redakcji i P09. Nie oznaczać tych kroków jako w pełni odebrane. |
| A44.1–A44.3 | Selekcja obrazów w PR, współdzielony build/skan, cache i pomiary, przypięcia SHA/digest, kontrolowane aktualizacje i SBOM. Odbiór rzeczywistego GitHub nadal otwarty. |

---

## 3. Co pozostaje w całym audycie

**Bilans po pakiecie 155: 10 głównych obszarów pozostaje otwartych.**
To A05, A16, A33, A34, A35, A36, A40, A41, A43 i A44.
A05 i A41 są w całości odłożone decyzją użytkownika; pozostałe 8 obszarów
jest częściowo wykonanych i ma opisane dalsze prace lub warunki odbioru.
Tabela ma 11 pozycji, ponieważ A43 jest rozpisane na dwa formalne etapy:
A43.40 (pozostała implementacja i uzgodnienia) oraz A43.41 (odbiór końcowy).

Nie jest to liczba kolejnych pakietów implementacji: zakresy zawierają wiele
zadań, a ich podział zależy od przeglądu kodu, wyników testów i brakujących decyzji.
Zmiany samych zdjęć A27–A31/A40 pozostają poza bieżącą realizacją, do osobnego
polecenia użytkownika. Pakiet 155 nie zamyka całego A35 ani audytu.

| ID / status | Pozostałe prace i warunek zakończenia |
|---|---|
| A05 — odłożone | 01.11.2026 użytkownik zadeklarował ręczne dodanie nazw `STRIPE_SECRET_KEY` i `STRIPE_WEBHOOK_SECRET` do obu plików `dev.env.sops` i `test.env.sops`. Wartości nie były odczytywane ani weryfikowane; jeśli pozostawiono puste wpisy, wymagają uzupełnienia. Nadal otwarte: konfiguracja webhooka i zewnętrzny odbiór sandboxu. Wznowienie na osobne polecenie. |
| A16 — w toku (po naprawach R01–R04 w P162) | W Pakiecie 162 naprawiono R01–R04: porty/zmienne/inwentarz K8s, wąskie uprawnienia `returns-service`, ekspozycję `checkoutSessionId`, wymóg statusu refundacji `SUCCEEDED`, deterministyczne klucze idempotencji oraz weryfikację kwot i blokadę `COMPLAINT`. Do pełnego zamknięcia A16 pozostaje trwała rezerwacja stanu operacji w bazie przed wywołaniami HTTP oraz odbiór integracyjny na żywym stosie usług. |
| A33 — częściowe | Pakiet 153: lokalny UI, odzyskiwanie, sesja produktu i egzekwowana macierz przeglądarek odebrane; w Pakiecie 162 naprawiono błąd inwentarza K8s w testach Python. Odbiór rzeczywistego dostawcy A05 nadal odłożony; pełnej gotowości A33 nie potwierdzono. |
| A34 — częściowe | W Pakiecie 162 uzupełniono zależności HTTP `returns-service` w `deploy/components.json` i `network-policies.yaml` (`validate-k8s.py` PASS). Nadal otwarte: docelowa infrastruktura, sekrety, TLS/CNI i pełny odbiór przedprodukcyjny, w tym płatności/e-maile. A05 nie jest automatycznie wznawiane. |
| A35 — częściowe | Pakiety 154–159 + 162: SQL readiness/korelacja, pending/lag Redis, metryki `payment_refund`, W3C `traceparent` z zachowaniem aktywnego `traceId` w zagnieżdżonym `CorrelationIdContext.open(String)` (R09), alerty SLO, lokalny heartbeat `Watchdog` oraz skrypt `cross-database-consistency-check.py` (z trybem `--live-postgres`, R08). Nadal otwarte: zewnętrzny odbiornik dead-man's-switch, odbiór na żywym środowisku dev/test i pozostałe pomiary. |
| A36 — częściowe | Globalny checkpoint, automatyczne uzgodnienie po utracie bazy/brokera, niezależne kopie oraz docelowe RPO/RTO. Lokalny restore nie dowodzi pełnego uzgodnienia procesu. |
| A40 — częściowo odebrane lokalnie / odłożone | Pakiet 152: HTTP HTML/JS/CSS i ocena indeksowalności wykonane. Publiczne SEO wymaga U40-02 i odbioru środowiska/przeglądarki. Same zdjęcia nadal odłożone. |
| A41 — odłożone | Konsolidacja usług/JVM/PostgreSQL i inne zmiany architektury. Diagram był propozycją; konsolidacji nie wdrożono. Wznowienie na osobne polecenie. |
| A43.40 — w toku | Pakiety 123–151 obejmują historię, źródłową naprawę, oceny, wersje i atomową inspekcję diagnostyczną. Pełny D02, wystawca/sprawy, kompletność, piecza i integracje/dev/test nadal otwarte. |
| A43.41 — otwarte | Kompletny odbiór A43 po zakończeniu implementacji i uzupełnieniu decyzji. Obecnie `NOT_READY`. |
| A44.4 i odbiór GitHub — otwarte | PR/fork i wymagany check `pr-image-gate`, pełne 16 obrazów, publikacja/podpisy/atestacje oraz porównanie rzeczywistych czasów/kosztów CI. Bez automatycznego deploymentu. |

**Zdjęcia A27–A31/A40:** nie wznawiać wymiany, kompresji, formatów,
rozdzielczości, wariantów/srcset, ładowania, cache ani wersjonowania zdjęć
przez przeniesienie tych prac do innego punktu. Użytkownik zarządzi je osobno.

---

## 4. A43 — co jest już przygotowane

| Zakres | Stan lokalnej implementacji |
|---|---|
| A43.1–A43.14 | Retencja/kompaktowanie określonych logów, audytu i kopii technicznych oraz kontrola streamu faktur; każdy mechanizm ma własne warunki i tryb działania. Nie wszystkie są aktywne; nie przełączać ich zbiorczo. |
| A43.15–A43.30 | Inwentarz danych i pominięć, ograniczone eksporty usług z kontrolą własności, bezpieczeństwo projekcji i ręczna ocena swobodnych treści. Czat ma własny token, bez przypisywania po e-mailu. |
| A43.31–A43.32 | Trwałe porcje eksportu JSON w Customer, dostępność przez 1 godzinę, wznowienie/cancel/limity oraz eksport z profilu sklepu z manifestem zakresu. |
| A43.33–A43.36 | Potwierdzany protokół usuwania, odcięcie konta i bariery w 14 usługach, token statusu na 7 dni; adaptery usuwania/redakcji uzgodnionych zakresów i ochrona spóźnionych zdarzeń. Aktywne/niejasne sprawy pozostają chronione. |
| A43.37–A43.39 | Narzędzia rejestru usunięć i restore, unieważnianie odtworzonych sesji/wyzwań, ograniczenie lokalnych kopii CC/przeglądarki, podgląd wysyłki do AI oraz procedury oceny czatu i pozostałych treści. Docelowa piecza rejestru wymaga uzupełnienia. |
| A43.40 — podstawa | Macierz polityk, mapa 127 pól, kwalifikator, trwałe zadania/oceny, generacje i bindingi; outboxy siedmiu źródeł, osiem źródeł obserwacji i kanoniczne potwierdzenia Payment. Nieznana historia pozostaje `UNKNOWN`. |
| Pakiety 114–115 | Rejestr znanych blokad zakupów, rewizje zakresów, kontrolowany przebieg jednego zakupu i trwały poprzednik generacji. Pusty lokalny rejestr nie oznacza globalnego braku blokad. |
| Pakiet 116 | Ograniczone pobieranie zadań oceny i indeksy V33; test na 100 000 syntetycznych zadaniach. To częściowy odbiór wydajności, bez gwarancji dla dowolnego obciążenia. |
| Pakiet 117 | Dowód `FIELD_SCOPE_REVIEWED` ze skrótem definicji pól i V34. Potwierdza przegląd planu pól, nie wdrożony schemat ani politykę zachowania. |
| Pakiet 118 | Jawne `revalidateAndAssess`: osiem ponownych odczytów, checkpointy rewizji, przejęcie konkretnego zadania przez CAS, ochrona dzierżaw, prób i backoffu. Bez endpointu, harmonogramu lub nowego widoku CC. |
| Pakiet 119 — częściowy | Protokół znaczników i sprawdzania rewizji sześciu źródeł; znaczniki w `ConcurrentHashMap`, bez blokady zapisów między żądaniami i instancjami. Wymaga rzeczywistej bariery przed mutacją. |
| Pakiet 120 — częściowy | Kolektor GUARD z interfejsami dostawców, kontrolą lokalnych blokad i podpisu rejestru. Kompletne źródła globalnych dowodów i piecza aktualnego rejestru pozostają do integracji/odbioru. |
| Pakiet 121 — częściowy | Migracje Order V35 / Returns V8 / Delivery V11, redakcja Order/Returns/Delivery i usuwanie pozycji Order w jawnej próbie. Wymaga egzekwowania barier, bezpiecznej autoryzacji wykonania, ochrony przed odtworzeniem i pełnych dowodów 8 źródeł. |
| Pakiet 122 — wymaga poprawek | Klasy polityk i parametry Compose istnieją. Potwierdzono błędy P09, omijanie blokady źródła przy limicie logów oraz ignorowanie blokady przy ocenie retencji spraw. Brakuje trwałej integracji magazynów i rzeczywistego stopu workera. |
| Pakiet 123 | P09 i blokady kwalifikacji naprawione; 7 trwałych dzierżaw SQL, ochrona nieodwracalności, usunięcie niekompletnych mutacji i fałszywych deklaracji QA/stopu. Pełny protokół wykonania i integracje pozostają otwarte. |
| Pakiet 124 | Rzeczywiste stany sagi i znaczniki retry/lease, kolekcja poza SQL z ponowną kontrolą zakresu; brak dodatniej zgody z callbacku, skrótu D02 lub starego podpisanego wpisu. Historyczne niepełne potwierdzenia nie przechodzą ponownej oceny. V37 unieważnia ocenę przy zwykłych zmianach kontaktu/treści Order. |
| Pakiet 125 | Obserwacje spraw/retry/DLQ sześciu usług, nowy protokół HTTP z zakresem, polityką i losowym identyfikatorem żądania, odbiór pod rewizją i kontrolowany krok workflow. Wyłącznie blokady/UNKNOWN, brak pełnej globalnej zgody i wykonania. |
| Pakiet 126 | Odbiór lokalnego HTTP sześciu źródeł z rzeczywistym filtrem i kontrolerem; SQL UNAVAILABLE, transakcyjny zapis, idempotentne rozpoznanie dowodu po utracie potwierdzenia COMMIT, testy współbieżności i ponowień. Nadal bez globalnej migawki lub zgody na wykonanie. |
| Pakiet 127 | Order V38: historyczne obserwacje sześciu źródeł, niezmienne i ograniczone do jednego wpisu na źródło/generację. Wspólny rollback z blokadą, idempotentny retry i zachowanie starszej historii w restore. Nie są potwierdzeniem redakcji lub aktualną zgodą. |
| Pakiet 128 | Order V39: jedna intencja na generację, do czterech niezmiennych zdarzeń. Przygotowanie/stop/anulowanie nierozpoczętej intencji, CAS/idempotencja i kwarantanna po restore. Brak stanów AUTHORIZED/EXECUTING/COMPLETED i brak potwierdzenia zatrzymania wykonawcy. |
| Pakiet 129 | Ponowne odczyty ośmiu źródeł powiązane z przygotowaniem i stanem intencji, bieżącymi rewizjami i zakresem udziału. Stop w czasie RPC blokuje dalsze odczyty; końcowa kontrola/claim/ocena w jednej transakcji. Wynik diagnostyczny, bez globalnej migawki, upoważnienia lub mutacji. |
| Pakiet 130 | Intencja kontroluje PREPARE/VERIFY siedmiu dzierżaw. Każdy wysłany PREPARE ma RELEASE w `finally`; odpowiedź jest walidowana, niepewność osobno. Błąd SQL wycofuje ocenę i próbę. Bez upoważnienia, mutacji lub trwałego wznowienia po awarii. |
| Pakiet 131 | Pełna runda HTTP siedmiu źródeł z rzeczywistymi kontrolerami i 8 bazami SQL, bez symulowania Inventory. Utrata odpowiedzi po COMMIT każdego źródła, stop podczas Inventory, autoryzacja endpointów i poprawka 413. To lokalny odbiór z testową introspekcją, bez pełnych aplikacji lub zgody na mutację. |
| Pakiet 132 | Order V40: 2 tabele / 13 kolumn, maks. 8 rund i 7 prób/rundę. Zapis przed PREPARE, osobne potwierdzenie RELEASE, odczyt po awarii i kwarantanna restore z zachowaniem historii. Brak ponawiania z historii, automatycznego TTL lub wykonania mutacji. |
| Pakiet 133 | Siedem źródłowych tabel znaczników, do 8 tokenów/zamówienie/epokę. RELEASE przed PREPARE zamyka token; ponowny stary PREPARE i wznowienie wygasłego tokenu odrzucane. Atomowość, współbieżność, brak TTL i zachowanie w restore. Bez RPC odzyskiwania lub mutacji. |
| Pakiet 134 | Order V41: 4 kolumny przejęcia, monotoniczny cleanup-only, właściciel na 15 s i budżet 8. Jeden jawny przebieg RELEASE, kontrole starego procesu i SQL właściciela. Restore usuwa przejęcie bez wznowienia. Bez PREPARE, schedulera, mutacji lub nowego endpointu. |
| Pakiet 135 | Odbiór timeoutów produkcyjnego klienta HTTP, utraty właściciela w locie, zmiany bindingu i rollback/niepewnego COMMIT rozliczenia. Uzupełniony kontrakt QA i testy jego mutacji. Bez zmian Java produkcyjnego, schematu lub restore. |
| Pakiet 136 | Inventory: chroniony odczyt negatywnych blokad, walidacja i archiwizacja jako siódme źródło. Order V42 rozszerza wyłącznie ograniczenie źródła historii. Macierz 8 uczestników, pełny drill z historią Inventory. Bez dodatniej zgody lub mutacji. |
| Pakiet 137 | Returns V12: trwały rejestr znanych blokad, rewizja SQL i atomowe unieważnienie/outbox. Order V43: hold_state, zgodność v1 UNKNOWN/v2 BLOCKED. Odbiór HTTP, współbieżności, rollback i pełnego restore. Pozostałe 6 rejestrów, proces obsługi i pełne D02 otwarte. |
| Pakiet 138 | Sześć pozostałych usług: migracje i rejestry zgodne z Returns, odczyty blokad, 49 przypadków dla 7 źródeł. Pełny restore zachowuje 7 rejestrów i 10 obserwacji. Proces wniosków/spraw, kompletność i pełne D02 pozostają otwarte. |
| Pakiet 139 | Order V44: 2 tabele / 12 kolumn. Atomowa blokada, wniosek i 7 planowanych identyfikatorów; odraczana kompletność SQL, idempotencja i scope. Restore kwarantannuje wniosek. Bez transportu, operator API lub dowodu własności/sprawy. |
| Pakiet 140 | Order V45: 2 tabele / 11 kolumn. Jawny POST do 7 źródeł, zapis próby przed RPC, ACK po COMMIT, max. 8 prób/źródło, idempotencja, kwarantanna i niezmienna historia restore. Endpoint domyślnie wyłączony; bez schedulera i globalnej zgody. |
| Pakiet 141 | Dokładny zakres zakupu/wniosku/sprawy, 7 niezmiennych liczników, OUTCOME_UNKNOWN i REVIEW_REQUIRED, historia ACK oraz kwarantanna. Bez RPC, resetu prób, DML, nowej migracji lub operator API. |
| Pakiet 142 | Chroniony POST odczytu 7 źródeł i jawne porównanie z ACK w Order. Świeży challenge, ograniczony wiek odpowiedzi, dokładna tożsamość, kontrola kwarantanny przed/po RPC. Wynik wyłącznie as-of-read; bez zapisu uzgodnienia, naprawy, resetu prób lub nowej migracji. |
| Pakiet 143 | Order V46: 2 tabele / 13 kolumn, probe przed RPC i wynik po odczycie, do 8 prób/źródło, retry tego samego UUID bez RPC, PENDING i kwarantanna. Restore zachowuje historię/budżet bez wznowienia. Bez naprawy lub globalnej zgody. |
| Pakiet 144 | Lokalna kwalifikacja do 7 źródeł / 56 prób, każde PENDING blokuje kandydata; limit wieku, zachowane dawne rozbieżności, dokładne UUID/guard/reason/probe/wersja. Bez RPC, DML, nowego schematu lub prawa wykonania. |
| Pakiet 145 | Order V47: 2 tabele / 11 kolumn. Jawny znacznik zastąpienia odczytu PENDING świeżym późniejszym wynikiem, blokada późnego INSERT, intencja z najnowszego MISSING bez aktywnego PENDING. Bez potwierdzenia zakończenia wykonawcy, resetu budżetu lub prawa naprawy. Restore zachowuje historię pod kwarantanną. |
| Pakiet 146 | Siedem tabel źródłowych / 63 pola / 7 migracji. Podpisany zakres, CAS i wspólny zapis oryginalnej blokady/outbox/receiptu. Replay tej samej intencji, bez nowego zapisu w REPLAY_ONLY; kwarantanna blokuje oba tryby. Order przygotowuje polecenie, bez approvalu lub dispatchera. |
| Pakiet 147 | Order V48: 3 tabele / 16 pól. Próba zapisana przed RPC, niepewny COMMIT nie wysyła; ten sam UUID czyta historię. Kolejne próby tylko REPLAY_ONLY, limit 8 na intencję. Stop/kwarantanna blokuje pozytywny wynik; brak dowodu zakończenia wykonawcy. Restore zachowuje historię i budżet. |
| Pakiet 148 | Ocena dostarczenia i inspekcji jako osobne wymiary; najnowszy odczyt po wszystkich zapisanych próbach/wynikach, wiek 15 s / przyszłość 5 s, epoch i minimalna rewizja. PENDING/stop/kwarantanna zachowane; bez resetu limitów, migracji lub prawa do redakcji. |
| Pakiet 149 | Jeden lokalny przegląd 7 źródeł, do 56 intencji i 56 różnych inspekcji. Wszystkie stare stopy/nieznane wyniki zachowane, liczniki bez mnożenia wspólnych prób. Końcowa świeżość i kwarantanna; bez RPC/DML lub rozproszonej zgody. |
| Pakiet 150 | Order V49: 1 tabela / 11 pól. Trwała wersja i skróty kanoniczne, lifetime do 15 s, limit 8, bez odnowienia przy retry. Check ponownie czyta lokalny zakres/historię/wynik, nie robi RPC lub DML i nie daje prawa do wykonania. Restore zachowuje historię pod kwarantanną. |
| Pakiet 151 — ostatni A43 | Order V50: 1 tabela / 10 pól, atomowa kontrola i próba, HTTP po COMMIT, kontrola kontekstu przed/po, bez ponowienia UUID lub odnowienia czasu. Restore bez wznowienia. |
| Pakiet 152 | A40: Nginx i serwer CC — gzip statycznego tekstu, rewalidacja JS/CSS, HTML no-store, brakujący skrypt 404, noindex zamówienia; pomiar transferu i ocena indeksowalności, zdjęcia bez zmian. |
| Pakiet 155 — bieżący | A35: odczytowe metryki grup płatności, nieznane stany, świeżość i panele; izolacja MDC fikstur. 64 unikalne Java (19 nowych) + 116 Python + 21 scenariuszy promtool PASS. Rewizja `.8` tylko dla odcisków. |
| Pakiet 154 | A35: readiness SQL, izolowane zakresy MDC nowych/przejętych zdarzeń, brakujące filtry Billing/Chat/Returns; 104 Java i 145 Python PASS, 15 JAR. Rewizja `.7` tylko dla odcisków. |
| Pakiet 153 | A33: 28 wymaganych wyników odzyskiwania, poprawki koszyka/sesji produktu, kontrolowany zegar fikstur, utwardzony serwer QA; bez pełnego odbioru A05. |

**Granica pakietu 123:** dzierżawy chronią obserwacje źródeł. Nie stanowią
zezwolenia na usunięcie danych w całym systemie. Starsze API wykonawcze zwraca
`RETENTION_EXECUTION_NOT_CONFIGURED` i nie wykonuje mutacji. QA rozróżnia
kontrolę konfiguracji od zachowania i wykonania. Modele spraw/stopu nie są
trwałymi adapterami magazynów; natywna retencja Compose nie konsultuje P09 Java.

---

## 5. A43 — kolejność dalszej implementacji

Poniżej jest kolejność kroków wdrożeniowych A43.40:

| Kolejność | Praca dla agenta | Warunek odbioru |
|---|---|---|
| 1 — częściowe | Siedem źródeł ma trwałe dzierżawy i blokadę zapisów. Pozostaje ochrona przez pełną mutację i jej odzyskiwanie. | Odbiór całego protokołu, nie tylko rundy odczytów; obecne wyniki nadal nie zezwalają na mutację. |
| 2 — częściowe | Intake/transport 139–140, historia/kwalifikacja 141–145, źródło 146, dispatcher 147, oceny 148–149, wersje 150 i atomowa inspekcja 151 odebrane lokalnie. Otwarte: rzeczywisty wystawca/sprawy; D01/D09/P09 odłożone. | Skrót i CURRENT_LOCAL nie zastępują zweryfikowanej sprawy lub świeżego dowodu ze wszystkich uczestników. |
| 3 — wykonane (pakiet 161) | [A43 — pakiet 161](A43_PROTOKOL_D02_WORKERY_161.md): domknięcie protokołu D02 z uwzględnieniem decyzji D01 (5 lat ochrony dokumentów finansowych) oraz D09 (sejf privacy-journal-safe). | Pełny protokół 8 uczestników zweryfikowany, workery redakcji fail-closed (RETENTION_EXECUTION_NOT_CONFIGURED), 9 testów PostgreSQL Testcontainers PASS. |
| 4 — wykonane (pakiety 121–123/161) | P09 i blokady ochrony danych finansowych (hard_block = true). Odmowa destrukcyjnego wykonania w środowiskach runtime. | Odbiór skutków w bazach danych; triggery PostgreSQL blokują cofnięcie redakcji i ponowne wstawianie danych. |
| 5 — wykonane (pakiet 163) | [A43 — pakiet 163](A43_DOWODY_SRODOWISKOWE_DEV_TEST_163.md): zebranie dowodów osobno dla dev/test (zegary UTC, Redis Streams, kopie 14 baz, SMTP Mailpit, historia AI, rotacja JWT i MFA, role operatorów, piecza rejestru privacy-journal). | Skrypt environmental-evidence-check.py i testy jednostkowe PASS; raport w tmp/a43-step5-evidence/environment-evidence-report.json; odczyty bez sekretów i bez mutacji. |
| 6 — wykonane (pakiet 164) | [A43 — pakiet 164](A43_KROK6_ODBIOR_KONCOWY_PROCEDURY_164.md): audytor gotowości a43-final-readiness-check.py (7/7 PASS w test_a43_final_readiness.py), raport tmp/a43-step6-readiness/final-readiness-report.json weryfikujący 6 wymiarów platformy, zachowany niezmiennik fail-closed (NOT_READY, kod 2) oraz formularz decyzji użytkownika D01–D17. | Kompletne procedury i testy gotowości; bramka --require-ready zwraca kod 2 (NOT_READY) do czasu rozstrzygnięcia formularza przez operatora. |

**Polecenie użytkownika 01.11.2026:** jeżeli krok wymaga podania wartości
lub decyzji, odłożyć go na później i rozpocząć następny niezależny krok.
Dotyczy obecnie D01/P01, D09/SAFE i P09 oraz zależnych odbiorów/aktywacji.
Nie zmienia to zapisu decyzji, nie jest zgodą na przyjęcie domyślnej wartości
i nie pozwala zamknąć całego A43. Wykaz braków: raporty 125–151.

Punkty zależne od nierozstrzygniętych decyzji pozostają zablokowane.
Można kontynuować niezależną implementację i testy; nie wolno zastępować
brakujących decyzji domyślną zgodą na usuwanie. Zależności A16 i odłożone A05
muszą pozostać widoczne dla nieuzgodnionych procesów.

---

## 6. Uzgodnienia użytkownika i brakujące decyzje

Pełny zapis: [D01–D18](A43_POZOSTALE_PRACE_I_DECYZJE.md) oraz
[P01–P09](A43_PYTANIA_UZUPELNIAJACE.md). Nie zadawać ponownie pytań,
które użytkownik świadomie pozostawił do ustalenia, bez nowego powodu.

| Zakres | Przyjęta reguła / czego brakuje |
|---|---|
| D01/P01 — finanse | Zapisano propozycję 30 dni od końcowego zamknięcia i uzgodnienia całego procesu, sprawdzanie co 7 dni. **Wyjątki i wymagane dłuższe okresy nadal nieustalone; źródłowe faktury, korekty i rejestry płatności/zwrotów chronione przed usunięciem.** Nie jest to zatwierdzona kompletna polityka finansowa. |
| D02/P02 — zakupy | Wszystkie zakończone i uzgodnione zakupy, także aktywnych kont: redakcja danych osobowych i treści po dokładnie 365 dniach od uzgodnienia całego łańcucha; obejmuje `delivery_date`, `delivery_slot`, `pickup_location`. Minimum pozostaje jeszcze 90 dni **od faktycznej potwierdzonej redakcji**. |
| Wyjątki D02 | Otwarte/wznowione sprawy, spory, wymagane zachowanie i zależności dokumentów D01, restore, retry/deduplikacji blokują usuwanie. Brak dowodu zwolnienia zależności blokuje; niejasne przypadki do oceny. |
| D03–D05 — bezpieczeństwo/streamy | Wybrano 7 dni dla kwalifikujących się stanów bezpieczeństwa SQL; progi streamów payment/invoice 7/30 dni wymagają dowodów. Payment: wymagane `billing-service` i `notification-service`. Invoice: brak planowanych odbiorców w dev/test, sposób czyszczenia nadal do weryfikacji. Nie aktywować trim na podstawie samego wieku. |
| D06 — techniczne kopie | Łącznie: zakończony i uzgodniony proces, trwałe potwierdzenie skutków, brak aktywnych ponowień/otwartych DLQ, treść zbędna do odzyskania. Brak dowodu blokuje. |
| D07 — logi | Maksymalnie 30 dni od wpisu; 100 MiB na źródło z rotacjami może usuwać starsze logi wcześniej. Zakres: pliki usług/frontendu z CC i Docker, każdy magazyn osobno. |
| D08 — monitoring | Prometheus 30 dni i alarm 1 GiB bez wcześniejszego kasowania z powodu budżetu; wycofane dashboardy Grafany 90 dni; techniczny stan Alertmanagera 5 dni. Dalsze kopie/odbiorniki wymagają przeglądu. |
| D09/P03–P05 — rejestr | Plan: `E:\Klucze\privacy-journal`, osobne dev/test; klucze `E:\Klucze\privacy-journal-keys\dev` i `...\test`. `Kopia` i `Kopia3` na E to tylko kopie lokalne. **Niezależny nośnik/host i narzędzie dla sejfu/folderu SAFE nadal do ustalenia**, wraz z odbiorem pieczy wskaźnika wersji. |
| Znaczniki i deduplikacja | Bez automatycznego TTL do udokumentowanego wycofania właściwych kopii i źródeł ponowień. Dotyczy również minimalnego rejestru usunięć; wiek nie wystarcza. |
| D10/D13/D16 | Świadomie odłożone zakresy historycznych danych, plików/urządzeń i dostawców. Nie traktować jako wykonane ani jako dowód braku kopii. |
| D11–D12 — klucze/kopie | Osobne plany migracji dla MFA, AI CC, backupów i rejestru, na poprawne istniejące klucze danego obszaru/środowiska. Starych kluczy nie usuwać przed rozliczeniem kopii. `E:\Klucze\Kopia2`: znane dodatkowe kopie DB/Redis dev/test, okres dopiero po inwentaryzacji. |
| D14/P06 — odbiorniki | Według użytkownika SMTP CC/Notification tylko lokalny Mailpit, brak znanego przekazywania; AI tylko tryb wbudowany CC. Konfiguracja i historia wymagają weryfikacji. REST API Notification nie jest potwierdzeniem zewnętrznego dostawcy. Mailpit: maks. 30 dni/1000 wiadomości na instancję z załącznikami; limit liczby może usuwać wcześniej. |
| D15/P07/P08 — sprawy | Czat wyłącznie przez zweryfikowany indywidualny wniosek i ręczną ocenę. Planowany katalog `E:\Klucze\Rejestr`; dowody 90 dni, minimalny wynik 365 dni od bezpiecznego zamknięcia, z wyjątkami. Dodatkową ocenę niejasnych spraw prowadzi tylko właściciel (`operator-1`) — decyzja użytkownika. |
| D17 — aktywacja | Odbiór lokalny, **bez nowej aktywacji czyszczenia**. Wybór parametrów nie uruchamia usuwania. |
| D18 — odpowiedzialność | Właściciel `operator-1`, zastępca `operator-2`, dev/test. Od pierwszego alarmu obie role, ręczny przegląd lokalnych paneli/statusów/raportów. To oznaczenia procesu; nie dowód nadanych uprawnień. |
| P09 — wyjątki | Wyjątki zachowania logów, monitoringu, Mailpit i materiałów spraw **nadal do ustalenia**. Nie omijać ich przez uruchomienie limitów; fail-closed w `OperationalRetentionGuard`. |

---

## 7. Instrukcja wznowienia dla kolejnego wykonawcy / agenta AI

1. Przeczytaj ten dokument, [stan odbioru A43](A43_ODBIOR_KONCOWY.md)
   oraz dokument [A43_MECHANIZMY_LOGOW_MONITORINGU_I_PROCEDUR.md](A43_MECHANIZMY_LOGOW_MONITORINGU_I_PROCEDUR.md).
   Sprawdź branch (`Audit_0.1`), lokalne instrukcje i istniejące zmiany robocze;
   nie resetuj ani nie nadpisuj wcześniejszych prac.
2. Porównaj wskazane niżej raporty z bieżącym kodem i rewizją polityki.
   Brak plików `tmp` po przeniesieniu repozytorium oznacza brak dostępnego
   artefaktu, a nie nowy wynik PASS. Archiwalne dowody nie odbierają nowego kodu.
3. **Punkt rozpoczęcia prac:** przeczytaj [zakres 156](A35_REFUND_PENDING_156.md).
   A35 metryki refund pending/review, Redis/SQL/korelacja, A33 regresje i A40 HTTP są odebrane w opisanych granicach.
   Następne: A35 — traceId/spanId, zgodność między bazami, reszta readiness/SLO, Kubernetes, watchdog i odbiór docelowy; wartości SLO i odbiorniki wymagające decyzji użytkownika odłożyć.
   Zacznij od [A35](A35_ALERTY_BIZNESOWE.md). A34 wymaga docelowej infrastruktury;
   A05, A41, zdjęcia i otwarte decyzje A43 pozostają odłożone.
   Brakujące wartości/decyzje odłóż i przejdź do następnej niezależnej pracy.
   Nie włączaj poprzednich API pojedynczą flagą. R01–R03 i ochrona R06 mają już poprawki;
   nie powtarzaj ich jako niewykonanych. Później integracje kroku 4 i dowody dev/test.
4. Zachowaj niezmienniki:
   - `activationAllowed = false`, `runtimeWired = false`.
   - Ochrona dokumentów finansowych D01 (`invoices`, `payments`).
   - Ochrona znaczników protokołów (checkout, payment, tracking, sync, idempotent).
   - P09 domyślnie zamknięte; parametr boolean i samo zwolnienie blokady nie są zgodą.
   - Wyłączność oceny niejednoznacznych spraw dla `operator-1`.
5. Po zakończeniu każdego kroku aktualizuj:
   - Ten dokument (`docs/PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md`),
   - Rejestr postępu (`docs/AUDIT_0.1_POSTEP.md`),
   - Odbiór końcowy (`docs/A43_ODBIOR_KONCOWY.md`).

---

## 8. Dowody i kontrola stanu

| Dowód | Znaczenie i granice |
|---|---|
| [Bieżący pakiet 164](A43_KROK6_ODBIOR_KONCOWY_PROCEDURY_164.md) | A43.41 Krok 6: procedury odbioru końcowego, audytor gotowości `a43-final-readiness-check.py` (7/7 PASS), raport 6 wymiarów platformy, zachowany niezmiennik fail-closed (kod 2) oraz formularz decyzji użytkownika. |
| [Pakiet 163](A43_DOWODY_SRODOWISKOWE_DEV_TEST_163.md) | A43.40 Krok 5: dowody środowiskowe dev/test (8 kategorii read-only) oraz techniczna weryfikacja 8 aktywnych obszarów (9 pozycji sekcji B). |
| [Pakiet 162](A16_SKUTKI_DOMENOWE_ZWROTOW_160.md) | Naprawy R01–R10 po review z 02.11.2026 w A16, A35, A34/K8s i dokumentacji A43. |
| [Pakiet 161](A43_PROTOKOL_D02_WORKERY_161.md) | A43.40: domknięcie protokołu D02, integracja decyzji D01/D09 i bezpieczne workery redakcji fail-closed. |
| [Pakiet 160](A16_SKUTKI_DOMENOWE_ZWROTOW_160.md) | A16: automatyzacja skutków domenowych zwrotów/reklamacji (magazyn, korekta, refundacja). |
| [Pakiet 159](A35_SPOJNOSC_BAZ_I_WATCHDOG_159.md) | A35: spójność między bazami danych (Cross-Database Consistency) i Watchdog monitoringu. |
| [Pakiet 158](A35_SLO_ALERTY_I_ROZSTRZYGNIECIE_DECYZJI_158.md) | A35: reguły alertów SLO w Prometheus i zatwierdzenie decyzji D01–D17. |
| [Pakiet 157](A35_W3C_TRACE_CONTEXT_157.md) | A35: W3C traceparent, traceId i spanId w warstwie HTTP i Gateway. |
| [Pakiet 156](A35_REFUND_PENDING_156.md) | A35: metryki refund pending i review w payment-service. |
| [Pakiet 155](A35_REDIS_PENDING_LAG_155.md) | A35 metryki grup płatności i panele. |
| [Pakiet 154](A35_READINESS_SQL_I_KORELACJA_154.md) | A35 readiness SQL i korelacja. |
| [Pakiet 153](A33_REGRESJE_ODZYSKIWANIA_I_BRAMKA_CI_153.md) | A33 lokalne regresje, poprawki sesji produktu i bramka CI. |
| [Pakiet 152](A40_HTML_JS_HTTP_I_INDEKSOWALNOSC_152.md) | A40: HTTP i ocena indeksowalności; następne A33. |
| [Ostatni A43: pakiet 151](A43_ATOMOWA_INSPEKCJA_POWIAZANA_Z_WERSJA_151.md) | Atomowa inspekcja diagnostyczna z wersją; niezależny A40 HTTP wykonano w 152. |
| [Pakiet 150](A43_WERSJONOWANIE_PRZEGLADU_I_KONTROLA_AKTUALNOSCI_150.md) | Wersje i kontrola aktualności; następne atomowe przygotowanie kroku powiązanego z wersją. A43 nadal NOT_READY. |
| [Pakiet 149](A43_ZBIORCZY_PRZEGLAD_SIEDMIU_ZRODEL_149.md) | Zbiorczy przegląd 7 źródeł i wszystkich intencji; następne wersjonowanie zakresu/wyników. A43 nadal NOT_READY. |
| [Pakiet 148](A43_OCENA_POSTEPU_NAPRAW_I_INSPEKCJA_ZRODEL_148.md) | Ocena postępu i świeżej inspekcji; następne zbiorczy przegląd siedmiu źródeł. A43 nadal NOT_READY. |
| [Pakiet 147](A43_TRWALE_PROBY_I_POTWIERDZENIA_ORDER_147.md) | Trwałe próby/wyniki/stop i odzyskanie tylko przez replay; następne ocena postępu ze świeżą inspekcją. A43 nadal NOT_READY. |
| [Pakiet 146](A43_ATOMOWE_ODTWARZANIE_BLOKAD_ZRODLA_146.md) | Atomowy protokół źródłowy i replay; następne trwały dispatcher Root. Wystawca/uprawnienia wymagają odbioru. A43 nadal NOT_READY. |
| [Pakiet 145](A43_ROZLICZANIE_PENDING_I_INTENCJE_NAPRAWY_145.md) | Rozliczenia prób odczytu i intencje; następne atomowy protokół odtworzenia blokady i weryfikacja upoważnienia. A43 nadal NOT_READY. |
| [Pakiet 144](A43_KWALIFIKACJA_ROZBIEZNOSCI_BLOKAD_144.md) | Kwalifikacja historii i opis kandydata; następne trwałe rozliczenie PENDING/intencje oraz kontrolowane wykonanie. A43 nadal NOT_READY. |
| [Pakiet 143](A43_TRWALA_HISTORIA_POROWNAN_BLOKAD_143.md) | Trwałe próby/wyniki i przegląd; następne kontrolowane rozliczenie rozbieżności/kwalifikacja naprawy. A43 nadal NOT_READY. |
| [Pakiet 142](A43_UZGADNIANIE_BLOKAD_ZE_ZRODLEM_142.md) | Porównanie ACK z odczytem źródła; następne trwała historia i kontrolowana obsługa rozbieżności. A43 nadal NOT_READY. |
| [Pakiet 141](A43_POSTEP_DORECZANIA_BLOKAD_141.md) | Odczyt postępu i wyczerpanego limitu; następne uzgodnienie z aktualnym źródłem, sprawy i D02. A43 nadal NOT_READY. |
| [Pakiet 140](A43_DOSTARCZANIE_BLOKAD_DO_ZRODEL_140.md) | Transport i historyczne ACK; następne postęp/budżet, uzgodnienie po utracie źródła, sprawy i D02. A43 nadal NOT_READY. |
| [Pakiet 139](A43_PRZYJMOWANIE_WNIOSKOW_BLOKAD_139.md) | Lokalny intake i plan 7 źródeł; następne transport/potwierdzenia, zweryfikowane sprawy i D02. A43 nadal NOT_READY. |
| [Pakiet 138](A43_TRWALE_BLOKADY_WSZYSTKICH_ZRODEL_138.md) | Rejestry 7 źródeł; następne trwały proces wniosków/spraw i kompletność przed D02. A43 nadal NOT_READY. |
| [Pakiet 137](A43_TRWALE_BLOKADY_RETURNS_137.md) | Trwałe blokady Returns i hold_state Order; następne pozostałe 6 źródeł, kompletność spraw i D02. A43 nadal NOT_READY. |
| [Pakiet 136](A43_POKRYCIE_BLOKAD_D02_136.md) | Inventory w negatywnych obserwacjach i macierz 8 uczestników; następne trwałe pokrycie luk blokad/spraw i D02. A43 nadal NOT_READY. |
| [Pakiet 135](A43_AWARIE_ODZYSKIWANIA_RUND_135.md) | Odbiór awarii odzyskiwania; następne kompletne globalne źródła blokad i D02. A43 nadal NOT_READY. |
| [Pakiet 134](A43_ODZYSKIWANIE_RUND_TYLKO_RELEASE_134.md) | Jawne przejęcie i pojedynczy przebieg RELEASE, SQL właściciela, ochrona starej instancji i restore; odbiór historyczny. A43 nadal NOT_READY. |
| [Pakiet 133](A43_OCHRONA_POWTORZEN_PREPARE_133.md) | Źródłowe znaczniki zamkniętych tokenów i ochrona replay; odbiór historyczny. |
| [Pakiet 132](A43_TRWALY_REJESTR_RUND_OBSERWACJI_132.md) | Trwała historia prób i potwierdzeń oraz kwarantanna restore; odbiór historyczny. |
| [Pakiet 131](A43_ODBIOR_DZIERZAW_HTTP_SIEDMIU_ZRODEL_131.md) | Lokalny odbiór HTTP siedmiu źródeł i spójne 413 Inventory; odbiór historyczny. |
| [Pakiet 130](A43_DZIERZAWY_OBSERWACJI_INTENCJI_130.md) | Koordynacja dzierżaw obserwacji i zwalnianie z walidacją odpowiedzi; historyczny zakres odbioru. |
| [Pakiet 129](A43_WERYFIKACJA_DOWODOW_INTENCJI_129.md) | Ponowna ocena dowodów intencji, kontrola stopu i atomowy zapis; odbiór historyczny. |
| [Pakiet 128](A43_REJESTR_INTENCJI_D02_128.md) | Rejestr przygotowania/stopu intencji i kwarantanna; odbiór historyczny. |
| [Pakiet 127](A43_TRWALE_OBSERWACJE_ZRODEL_127.md) | Trwała historia obserwacji, V38, transakcje i restore; odbiór historyczny. |
| [Pakiet 126](A43_ODBIOR_HTTP_I_AWARII_126.md) | Lokalny odbiór HTTP, współbieżności, awarii SQL i niepewnego COMMIT; odbiór historyczny. |
| [Pakiet 125](A43_OBSERWACJE_ZRODEL_125.md) | Obserwacje sześciu źródeł, protokół i odłożone zależności; odbiór historyczny. |
| [Pakiet 124](A43_GLOBALNE_DOWODY_KOLEKCJA_124.md) | Lokalna kolekcja Order i wiarygodność dowodów, V37; dowody historyczne. |
| [Poprawki 123](A43_POPRAWKI_BEZPIECZENSTWA_2026-11-01.md) | P09, dzierżawy SQL, nieodwracalność i odmowa niekompletnych mutacji; dowody historyczne. |
| [Weryfikacja historyczna](A43_WERYFIKACJA_KODU_I_PRZEKAZANIA_2026-10-31.md) | R01–R08 opisują stan sprzed pakietu 123 i uzasadniają poprawki. |
| [Opis pakietu 122](A43_MECHANIZMY_LOGOW_MONITORINGU_I_PROCEDUR.md) | Zapisane XML: 14 testów jednostkowych, łącznie 209 platform-security bez błędów. Opis integracji i P09 zakwestionowany przez R01–R03/R08; PASS konfiguracji nie jest odbiorem zachowania. |
| [Opis pakietu 121](A43_WORKERY_REDAKCJI_I_MINIMUM.md) | Zapisane XML: 8 PostgreSQL Order bez błędów. Migracje Order V35 / Returns V8 / Delivery V11. Braki R05–R07 pozostają; test z dodatkową blokadą nie dowodzi ochrony ustanawianej przez worker. |
| [Opis pakietu 120](A43_GLOBALNE_BLOKADY_I_REJESTR.md) | Historyczne wyniki nie są odbiorem kompletnych zewnętrznych dostawców GUARD i aktualnej pieczy rejestru. Wymagany dalszy odbiór integracji. |
| [Opis pakietu 119](A43_ROZPROSZONE_BARIERY_ZAPISOW.md) | Istnieje protokół i testy wykrywania zmian. Próbą na PostgreSQL wykazano równoległy zapis i drugi znacznik w innej instancji; pełna bariera nieodebrana (R04). |

### Gotowe polecenia do weryfikacji stanu (uruchamiać z głównego katalogu repozytorium):

```bash
# 1. Kontrola cyklu życia danych i schematu bazodanowego (106 tabel, 1031 kolumn, 197 migracji)
python scripts/qa/data-lifecycle-check.py

# 2. Kontrola klasyfikacji technicznej danych i wyłączeń
python scripts/qa/technical-data-classification-check.py

# 3. Kontrola retencji handlowej D02 (assessment-only)
python scripts/qa/commerce-retention-check.py

# 4. Kontrola parametrów i obecności klas; PASS nie odbiera P09 ani magazynów
python scripts/qa/operational-retention-check.py

# 5. Sprawdzenie polityki retencji na publicznym kandydacie obserwacji (oczekiwany PASS)
python scripts/qa/retention-policy-check.py --candidate scripts/qa/contracts/retention-observe-example.json

# 6. Sprawdzenie gotowości (oczekiwany kod 2 / NOT_READY; kod 1 to błąd kontroli)
python scripts/qa/retention-policy-check.py --require-ready

# 7. Testy jednostkowe platform-security; nie zastępują testów magazynów
./mvnw -pl platform-security -am test
```

Polecenia wymagają przygotowanego środowiska (Python QA z PyYAML, JDK/Maven).
209 to zapisany wynik wcześniejszego uruchomienia, nie gwarantowany wynik
każdego następnego przebiegu. Raport z bieżącej weryfikacji rozróżnia nowe
próby, historyczne XML i błędy środowiska. Nie aktywowano czyszczenia.

---

## 9. Mapa dokumentacji i powiązane pliki

| Dokument | Kiedy czytać |
|---|---|
| [Postęp wdrożenia](AUDIT_0.1_POSTEP.md) | Pełna chronologiczna historia pakietów i statusów zadań audytu. |
| [Odbiór końcowy A43](A43_ODBIOR_KONCOWY.md) | Autorytatywna lista aktualnych braków i warunków zamknięcia A43. |
| [Mechanizmy logów i procedur](A43_MECHANIZMY_LOGOW_MONITORINGU_I_PROCEDUR.md) | Szczegóły techniczne wdrożenia Pakietu 122 (D07, D08, D14, D15, D18, P09). |
| [Workery redakcji i minimum](A43_WORKERY_REDAKCJI_I_MINIMUM.md) | Szczegóły techniczne wdrożenia Pakietu 121 (D02, migracje V35/V8/V11, 8 testów PG). |
| [Globalne blokady i rejestr](A43_GLOBALNE_BLOKADY_I_REJESTR.md) | Szczegóły techniczne wdrożenia Pakietu 120 (GUARD, Ed25519, preservation review). |
| [Rozproszone bariery zapisów](A43_ROZPROSZONE_BARIERY_ZAPISOW.md) | Szczegóły dwufazowego protokołu bariery zapisów Pakietu 119. |
| [Decyzje D01–D18](A43_POZOSTALE_PRACE_I_DECYZJE.md), [pytania P01–P09](A43_PYTANIA_UZUPELNIAJACE.md) | Uzgodnienia użytkownika, odłożone decyzje i parametry. |
| [Audyt źródłowy](AUDYT_APLIKACJI_2026-10-23.md) | Uzasadnienie problemów A01–A44 i warunki odbioru. |
