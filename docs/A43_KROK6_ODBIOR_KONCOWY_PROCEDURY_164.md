# A43.41 Krok 6 — Procedury odbioru końcowego, regresja platformowa i formularz decyzyjny, pakiet 164

Stan: **03.11.2026 · Audit_0.1 · Pakiet 164 · wdrożenie Kroku 6 A43 (A43.41)**.  
Punkt wznowienia: [Dokument przekazania](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md) oraz [rejestr postępu](AUDIT_0.1_POSTEP.md).

---

## 1. Zakres pakietu 164 (Krok 6 A43 / A43.41)

Zgodnie z poleceniem użytkownika wdrożono **Krok 6 A43**:
1. Opracowano kompleksowy audytor gotowości platformy i procedur odbioru końcowego: [`scripts/qa/a43-final-readiness-check.py`](../scripts/qa/a43-final-readiness-check.py).
2. Utworzono zestaw testów jednostkowych weryfikujących inwarianty bramki: [`scripts/qa/unit/test_a43_final_readiness.py`](../scripts/qa/unit/test_a43_final_readiness.py) (**7/7 PASS**).
3. Wygenerowano maszynowy raport gotowości: `tmp/a43-step6-readiness/final-readiness-report.json`.
4. Potwierdzono ścisłe zachowanie niezmiennika *fail-closed*:
   - `readinessStatus`: `NOT_READY`
   - Kod wyjścia dla `--require-ready`: **2**
   - `failClosed`: `true`
   - `activationAllowed`: `false`
   - `runtimeWired`: `false`
   - `executedDataMutations`: `0`
   - `secretValuesRead`: `false`
5. Opracowano formularz decyzyjny dla użytkownika z rekomendacjami, umożliwiający sprawne rozstrzygnięcie otwartych punktów blokujących formalną aktywację runtime.

---

## 2. Zweryfikowane wymiary platformy (A43.41)

Bramka `a43-final-readiness-check.py` weryfikuje 6 kluczowych wymiarów architektonicznych:

1. **Architektura 14 serwisów i routing Gateway**:
   - Wszystkie 14 mikroserwisów JVM (`customer-service`, `order-service`, `inventory-service`, `payment-service`, `billing-service`, `notification-service`, `delivery-service`, `promotion-service`, `product-service`, `wishlist-service`, `review-service`, `recommendation-service`, `chat-service`, `returns-service`) oraz `api-gateway` są obecne i skonfigurowane.
   - Propagacja kontekstu W3C Trace Context (`traceparent`, `traceId`, `spanId`) we wszystkich filtrach i klientach HTTP.

2. **Schemat bazodanowy i klasyfikacja danych**:
   - Spójność z kontraktem: **145 tabel, 1307 kolumn, 232 migracje** w 14 bazach PostgreSQL.
   - 10 tabel retencji handlowej D02 (127 kolumn, 8 uczestników).
   - Ochrona 5 tabel finansowych (`invoices`, `invoice_items`, `invoice_corrections`, `payments`, `payment_refunds`) przed jakimkolwiek automatycznym usuwaniem (`hard_block = true`).
   - Stan workerów retencji: fail-closed (`RETENTION_EXECUTION_NOT_CONFIGURED`).

3. **Macierz krytycznych regresji odzyskiwania UI (A33)**:
   - Wymóg 28 unikalnych kombinacji (7 scenariuszy odzyskiwania koszyka/zamówienia × 4 silniki: Chromium, Firefox, WebKit, Mobile).
   - Zero błędów, zero pominięć, zero ponowień (`retry=0`), blokada testów niestabilnych (`allowFlaky=false`).

4. **Obserwowalność, alerty SLO i audyt spójności międzybazowej (A35)**:
   - Heartbeat `Watchdog` (`expr: vector(1)`) w Prometheus stale aktywny do certyfikacji potoku powiadomień.
   - Reguły alertów SLO: `BusinessQueueSloBreach` (wiek zwrotów/faktur > 60 min) oraz `RedisStreamLagHigh` (lag > 100).
   - Audytor spójności rozproszonej `cross-database-consistency-check.py` badający 8 scenariuszy w 6 bazach PostgreSQL.

5. **Disaster Recovery i kwarantanna odtwarzania (A36 / A43.37)**:
   - Kopie zapasowe 14 baz danych i Redis szyfrowane w formacie Age (X25519 + ChaCha20-Poly1305).
   - Kryptograficznie podpisany manifest `manifest.json.age`.
   - Pełna kwarantanna po odtworzeniu (unieważnienie starych ocen, brak automatycznego wznawiania).

6. **Dowody środowiskowe dev/test (A43.40 Krok 5)**:
   - Potwierdzone 8 kategorii środowiskowych w trybie *read-only* (zegary UTC, strumienie Redis, kopie 14 baz, SMTP Mailpit, historia AI CC, rotacja JWT i MFA, role operatorskie `operator-1`/`operator-2`, piecza rejestru `privacy-journal-safe`).

---

## 3. Blokady gotowości platformy (Blockers) i ich status

Bramka gotowości platformy `a43-final-readiness-check.py` oraz testy retencji identyfikują 7 punktów blokujących automatyczną aktywację wykonawczą. Dzielą się one na **decyzje strategiczno-polityczne** (rozstrzygnięte formalnie przez użytkownika w formularzu) oraz **zależności operacyjno-wdrożeniowe** (wymagające działań fizycznych / inwentaryzacyjnych na środowisku docelowym):

### A. Strategiczne decyzje polityki platformy (rozstrzygnięte w Sekcji 4):
1. `D01`: Retencja finansowa — zatwierdzono 5-letni okres retencji (od końca roku kalendarzowego) oraz `hard_block = true` na dev/test.
2. `D09`: Architektura wolumenu `privacy-journal-safe` — zatwierdzono dedykowany wolumen kontenerowy z uprawnieniami append-only; klucze w SOPS / K8s secrets.
3. `P09`: Retencja logów i monitoringu — zatwierdzono 30 dni dla logów kontenerowych/metryk oraz bezterminowe zachowanie certyfikowanych logów CC i incydentów bezpieczeństwa.
4. `D17`: Tryb aktywacji runtime — zatwierdzono utrzymanie bezpiecznego trybu fail-closed / assessment-only (`activationAllowed = false`, kod wyjścia 2) do czasu ustabilizowania klastra produkcyjnego.
5. `A05`: Płatności Stripe — zatwierdzono formalne odłożenie zewnętrznego webhooka i sandboxa do momentu wdrożenia środowiskowego.
6. `A41`: Architektura usług — zatwierdzono zachowanie docelowej architektury 14 niezależnych mikroserwisów Spring Boot (odrzucono konsolidację).

### B. Zależności operacyjne i inwentaryzacyjne (wymagające działań fizycznych / klastrowych):
7. `D12`: Inwentaryzacja fizycznych kopii zapasowych offline — punkt ten **nie był przedmiotem formularza decyzyjnego**; stanowi wymóg operacyjny weryfikacji i spisu fizycznych nośników kopii zapasowych offline przed produkcyjnym uruchomieniem procedur czyszczenia.
- **Granularne wyjątki zachowania danych (preservation exceptions):** Ewidencja konkretnych rekordów wyłączonych z retencji przed przejściem w fazę wykonawczą.
- **Środowiskowy montaż wolumenu SAFE i konfiguracja rotacji:** Fizyczne podpięcie wolumenu na klastrze produkcyjnym (np. k3s / chmura) oraz wdrożenie reguł rotacji w procesie log-shippera.

### Status rejestru maszynowego a decyzje operacyjne:
- **`retention-decisions.json` jako historyczny stan bazowy:** Plik `scripts/qa/contracts/retention-decisions.json` dokumentuje **stan bazowy audytu z dnia 30.10.2026** (wersja `a43-decisions-2026-10-30.2`), w którym wpis D01 oznaczony jest jako `partial` (plan 30-dniowy z nierozstrzygniętymi wyjątkami). Stały skrót SHA-256 tego pliku (`0710c316783ee62bc827b565dd6c1443d7e0b57a40598b2fd1deeb34d469e959`) jest trwale przypięty w bramkach testowych (`retention-policy-check.py:15`).
- **Niezmiennik fail-closed:** Zachowanie stanu `partial` w rejestrze bazowym oraz `stageComplete = false` w polityce jest celowym zabiegiem architektonicznym. Gwarantuje ono, że wywołanie `python scripts/qa/retention-policy-check.py --require-ready` deterministycznie kończy się kodem wyjścia **2** (`NOT_READY`), a workery domenowe D02 zwracają `BLOCKED_FAIL_CLOSED`, zabezpieczając system przed destrukcyjnymi mutacjami na środowiskach roboczych.
- **Rozstrzygnięcie decyzji polityki:** Wszystkie punkty decyzyjne formularza (grupa A: D01, D09, P09, D17, A05, A41) zostały formalnie rozstrzygnięte przez użytkownika (Sekcja 4 poniżej), stanowiąc wiążącą politykę dla wdrożenia produkcyjnego. Zależności z grupy B (w tym `D12`) pozostają wymogami checklisty operacyjnej dla etapu wdrożenia klastrowego.

---

## 4. Formalne rozstrzygnięcie formularza decyzyjnego przez użytkownika (03.11.2026)

Wszystkie punkty formularza decyzyjnego zostały formalnie rozstrzygnięte przez użytkownika (akceptacja rekomendacji — **Opcja A dla wszystkich punktów**):

1. **D01 / P01 (Retencja dokumentów finansowych):**
   - **Zatwierdzono:** 5-letni okres retencji (od końca roku kalendarzowego) dla dokumentów finansowych (`invoices`, `payments`, `payment_refund`) zgodnie z art. 74 ust. 2 Ustawy o Rachunkowości.
   - **Zabezpieczenie:** Utrzymanie trybu `hard_block = true` na środowiskach dev/test — brak fizycznego usuwania danych źródłowych.

2. **D09 / P03–P05 (Piecza nad rejestrem usunięć i wolumen SAFE):**
   - **Zatwierdzono:** Docelowy dedykowany wolumen kontenerowy `privacy-journal-safe` z uprawnieniami *append-only* dla procesu rotacji i *read-only* dla usług platformowych; klucze zarządzane w SOPS / K8s Secret (bez konieczności zakupu fizycznego HSM).

3. **P09 / D07, D08 (Retencja logów operacyjnych i monitoringu):**
   - **Zatwierdzono:** 30 dni retencji dla logów kontenerowych Docker/CC oraz danych metryk Prometheus/Alertmanager; 90 dni dla wycofanych dashboardów Grafany.
   - **Wyjątki trwałego zachowania:** Bezterminowe zachowanie certyfikowanych logów zleceń Control Center (`cc-job-output`) oraz logów zdarzeń bezpieczeństwa i incydentów.

4. **D17 (Tryb aktywacji retencji):**
   - **Zatwierdzono:** Zakończenie fazy audytowej w bezpiecznym trybie **FAIL-CLOSED / ASSESSMENT-ONLY** (`activationAllowed = false`, `runtimeWired = false`).
   - Kod, procedury i audytory są w 100% zweryfikowane. Fizyczna aktywacja czyszczenia nastąpi osobnym, świadomym poleceniem operatorskim po wdrożeniu na docelowym klastrze produkcyjnym z kwarantanną.

5. **A05 i A41 (Zakresy odłożone):**
   - **Zatwierdzono:** Utrzymanie formalnego odłożenia A05 (wbudowany symulator Stripe pokrywa wszystkie lokalne testy i przepływy; konfiguracja kluczy i zewnętrznego webhooka nastąpi przed uruchomieniem produkcyjnym) oraz A41 (formalne zatwierdzenie docelowej architektury 14 mikroserwisów Spring Boot bez konieczności ich konsolidacji).

---

## 5. Podsumowanie statusu audytu

Wszystkie techniczne etapy audytu z dokumentu przekazania zostały wdrożone i zweryfikowane:
- **Krok 5 A43.40:** Dowody środowiskowe dev/test zebrane maszynowo (`environmental-evidence-check.py` — PASS).
- **Krok 6 A43.41:** Procedury odbioru końcowego, audytor gotowości (`a43-final-readiness-check.py` — PASS, `test_a43_final_readiness.py` — 7/7 PASS) oraz formularz decyzyjny rozstrzygnięty.
- **Niezmiennik fail-closed:** Zachowany (`NOT_READY`, kod 2). Platforma jest bezpieczna przed przypadkowym zniszczeniem danych.

