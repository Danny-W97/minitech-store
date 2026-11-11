# Audit_0.1 — postęp wdrożenia

[Przekazanie audytu — wykonane zmiany, pozostałe prace i punkt wznowienia](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md)
(stan lokalny po pakiecie 164, 03.11.2026; dokument dla użytkownika i agenta AI).

**Historia odbioru 119–122:** [weryfikacja kodu](A43_WERYFIKACJA_KODU_I_PRZEKAZANIA_2026-10-31.md)
wykazała R01–R08. Pakiet 123 poprawia P09 i blokady, dodaje trwałe dzierżawy
oraz wycofuje niekompletne mutacje. Pełny protokół D02 i integracje pozostają
otwarte. Chronologia zachowuje wcześniejsze wyniki i deklaracje; aktualny
punkt wznowienia jest w dokumencie przekazania i odbiorze końcowym.

**Aktualny odbiór 164 + poprawki przeglądu (03.11.2026), Krok 6 A43 / A43.41 oraz domknięcie ustaleń:** [procedury odbioru końcowego, regresja platformowa i formularz decyzyjny](A43_KROK6_ODBIOR_KONCOWY_PROCEDURY_164.md).
1. Zaimplementowano kompleksowy audytor gotowości platformy `scripts/qa/a43-final-readiness-check.py` oraz zestaw testów jednostkowych `test_a43_final_readiness.py` (**7/7 PASS**).
2. Wygenerowano raport weryfikujący 6 wymiarów platformy: `tmp/a43-step6-readiness/final-readiness-report.json`.
3. Zweryfikowano architekturę 14 mikroserwisów + Gateway (W3C traceparent), schemat 145 tabel / 1307 kolumn / 232 migracji, macierz regresji UI (28 par bez retry/flaky), alerty SLO i Watchdog `vector(1)`, audytor spójności 6 baz, Disaster Recovery (Age X25519) oraz dowody środowiskowe dev/test Kroku 5.
4. Rozwiązano 4 ustalenia przeglądu technicznego z 03.11.2026:
   - **A16:** Wprowadzono dwufazową orkiestrację w `ReturnDomainEffectsService`: odczytowe kontrole płatności (status `SUCCEEDED`/`REFUNDED`, kwota, Stripe) oraz salda faktury są wykonywane **przed jakąkolwiek mutacją domenową**. Dla transakcji COD lub nieopłaconych proces rzuca błąd przed restockiem i korektą faktury. Komplet 36/36 testów Java PASS (w tym `ReturnDomainEffectsTest` 11/11).
   - **Zakres retencji:** Rozdzielono retencję bezpieczeństwa poświadczeń w `customer-service` (`CUSTOMER_SECURITY_RETENTION_ENABLED=true`, czyszczenie sesji i MFA) od komercyjnej retencji RODO (D02, workery `BLOCKED_FAIL_CLOSED`). Doprecyzowano pole zerowych mutacji audytora (`auditorExecutedDataMutations = 0`).
   - **Obserwowalność A35:** Oznaczono zakres bramki jako weryfikację alertów SLO i kontraktów in-memory; audyt 6 fizycznych baz wymaga flagi `--live-postgres`.
   - **Decyzja D01 a kontrakty maszynowe:** Zatwierdzona polityka operacyjna (5 lat) z zachowaniem stanu bazowego kontraktów maszynowych (`stageComplete = false`, stan `partial`) dla zachowania trybu fail-closed (`NOT_READY`, kod wyjścia 2).
5. Potwierdzono zachowanie niezmiennika fail-closed (`status = NOT_READY`, kod wyjścia `--require-ready` = **2**, `activationAllowed = false`, `runtimeWired = false`, `auditorExecutedDataMutations = 0`).
6. Przygotowano formularz decyzyjny dla użytkownika (`D01`, `D09`, `P09`, `D12`, `D17`, `A05`, `A41`), a użytkownik formalnie zatwierdził wszystkie rekomendowane opcje (03.11.2026), rozstrzygając punkty otwarte audytu w trybie bezpiecznym fail-closed.

**Historyczny odbiór 163 (03.11.2026), Sekcja B (8 aktywnych obszarów / 9 pozycji) & Krok 5 A43.40:** [dowody środowiskowe dev/test i domknięcie obszarów aktywnych B](A43_DOWODY_SRODOWISKOWE_DEV_TEST_163.md).
1. **A16 (Returns):** pełna orkiestracja skutków zwrotów potwierdzona kompletem 35 testów modułu `returns-service` (35/35 PASS). Deterministyczne klucze idempotencji, blokada współbieżnych spraw w pamięci, bezpieczne kwoty, status refundacji `SUCCEEDED` i blokada `COMPLAINT`.
2. **A33 (UI & Odzyskiwanie):** bramka `critical-browser-check.py` zweryfikowana (8/8 PASS w testach jednostkowych), macierz 28 unikalnych par zachowana.
3. **A34 (Kubernetes):** render Kustomize i schemat 1.35.0 (`validate-k8s.py`) — PASS (16 deploymentów, 16 serwisów, 14 baz, 18 network policies).
4. **A35 (Obserwowalność):** alerty SLO i Watchdog (`monitoring-check.py` — PASS), audytor spójności rozproszonej (`cross-database-consistency-check.py` — PASS) oraz sondy gotowości SQL (`readiness-check.py` — PASS).
5. **A36 (Disaster Recovery):** szyfrowane zestawy 14 baz + Redis, manifest Age i procedury izolowanego odtwarzania (`test_platform_recovery.py` — 15/15 PASS).
6. **A40 (Frontend HTTP):** kompresja gzip, rewalidacja ETag skryptów/styli, noindex na szczegółach zamówienia (`test_frontend_http.py` — 12/12 PASS). Domena publiczna U40-02 i modyfikacje zdjęć odłożone.
7. **A43.40 (Krok 5):** wdrożono audytor środowiskowy `scripts/qa/environmental-evidence-check.py` i test `test_environmental_evidence.py` (5/5 PASS), zebrano maszynowy raport dowodowy dev/test (`tmp/a43-step5-evidence/environment-evidence-report.json`, 8 kategorii zweryfikowanych read-only bez czytania sekretów i bez mutacji).
8. **A43.41 (Gotowość retencji):** niezmiennik fail-closed zachowany (`NOT_READY`, kod wyjścia 2, `activationAllowed = false`, `runtimeWired = false`).
9. **A44.4 (CI/CD):** odcięcie uprawnień PR i brak publikacji obrazów w PR potwierdzone (`ci-images-check.py` — PASS).

**Historyczny odbiór 162 (02.11.2026), A16 / A34 / A35 / A43:** naprawy ustaleń `R01`–`R10` z [raportu review repozytorium](REVIEW_REPOZYTORIUM_STAN_AUDYTU_2026-11-02.md):
1. **R01 (A16/A34/A33):** naprawiono domyślne adresy URL klientów HTTP w `returns-service` (`ReturnInventoryClient` `18083`, `ReturnPaymentClient` `18084`, `ReturnBillingClient` `18095`), uzupełniono `INVENTORY_SERVICE_URL`, `PAYMENT_SERVICE_URL`, `BILLING_SERVICE_URL` w `compose.apps.yaml`, zaktualizowano `deploy/components.json` oraz `deploy/k8s/base/network-policies.yaml` (`validate-k8s.py` i `test_k8s_templates.py` 13/13 PASS) oraz nadano wąskie uprawnienia usługowe `returns-service` w `RoutePolicy` i docelowych kontrolerach (`StockOperationController`, `InvoiceController`, `PaymentRefundController`).
2. **R02 (A16):** dodano `checkoutSessionId` do `PaymentResponse`, zaostrzono weryfikację refundacji w `ReturnDomainEffectsService` wyłącznie do potwierdzonego statusu `"SUCCEEDED"` (`RETURN_PAYMENT_REFUND_NOT_SETTLED`) i usunięto fikcyjne potwierdzenia `SETTLED_NON_STRIPE_*` (`RETURN_NON_STRIPE_REFUND_UNCONFIRMED`).
3. **R03 (A16):** wprowadzono deterministyczne klucze idempotencji per sprawa (`return-restock-v1:<returnId>:<orderItemId>`, `return-corr-v1-<returnId>`, `return-ref-v1-<returnId>`) niezależne od `operationId` wywołującego, blokadę współbieżnych wywołań tej samej sprawy oraz zapamiętywanie wersji salda korekty przy ponowieniu po częściowej awarii.
4. **R04 (A16):** usunięto fallback kwoty zwrotu do `order.totalPrice()` oraz fikcyjnego `10.00`, dodano weryfikację dodatniej ceny jednostkowej i zgodności sumy pozycji z `order.totalPrice()` przed jakąkolwiek mutacją zewnętrzną, zablokowano automatyczną orkiestrację dla `COMPLAINT` (`RETURN_COMPLAINT_REQUIRES_MANUAL_RESOLUTION`) oraz udostępniono rozliczenie zwrotu dla `service:returns-service` na zamówieniach `COMPLETED` i kwotach częściowych w `payment-service` i `order-service`.
5. **R05–R10 (A35/A43/Dokumentacja):** zachowano aktywny `traceId` w `CorrelationIdContext.open(String)` (`R09`), dodano tryb `--live-postgres` w `scripts/qa/cross-database-consistency-check.py` (`R08`), skorygowano dokumentację pakietów 158–161 (kod wyjścia `2` dla `--require-ready`, stan workerów fail-closed D02, status `D01`/`D09` w `retention-decisions.json`, tryb `CUSTOMER_SECURITY_RETENTION_ENABLED`) oraz usunięto wszystkie 23 linki `file:///` i naprawiono link w `docs/APKA.md:2503`.

**Historyczny odbiór 161 (02.11.2026), A43.40:** [weryfikacja regresyjna blokad fail-closed workerów D02](A43_PROTOKOL_D02_WORKERY_161.md).
Potwierdzono w testach `CommerceRetentionRedactionPostgresIT` (9/9 PASS) oraz testach satelickich działanie blokad `Outcome.BLOCKED` / `RETENTION_EXECUTION_NOT_CONFIGURED`. Etapy A43.40 i A43.41 pozostają otwarte (`NOT_READY`, kod 2).

**Historyczny odbiór 160 (02.11.2026), A16:** [automatyzacja skutków domenowych zwrotów i reklamacji](A16_SKUTKI_DOMENOWE_ZWROTOW_160.md).
W `returns-service` zaimplementowano orkiestrator `ReturnDomainEffectsService`, klientów domenowych `ReturnInventoryClient`, `ReturnBillingClient`, `ReturnPaymentClient` oraz endpoint `POST /returns/{id}/reconcile-effects`.
Dla zatwierdzonych zwrotów (`APPROVED`) wykonywane jest:
1. Przyjęcie zwróconych towarów na stan magazynowy w `inventory-service` ze zdeterminowanym, idempotentnym UUID operacji.
2. Wystawienie korekty faktury (credit note) w `billing-service` z kwotą pomniejszającą saldo brutto (gdy faktura istnieje).
3. Zlecenie zwrotu środków w `payment-service` (dla transakcji Stripe) lub bezpieczne odnotowanie rozliczenia bez błędnych wywołań zewnętrznych dostawców.
4. Przejście do `status = COMPLETED` z `effectsState = DOMAIN_RECONCILED` oraz zapis audytowy w `return_events`.
Bezpośrednia zmiana statusu `PATCH ... COMPLETED` nadal jest zablokowana błędem 409 `RETURN_DOMAIN_EFFECTS_NOT_RECONCILED`.
Zweryfikowano zestawem 7 testów w `ReturnDomainEffectsTest` (31/31 Java w `returns-service` PASS) oraz 9 bramkami QA (PASS).
Następne: Domknięcie protokołu D02 w A43.40/A43.41 oraz wdrożenie bezpiecznych workerów redakcji na podstawie zatwierdzonych decyzji.

**Historyczny odbiór 159 (02.11.2026), A35:** [spójność między bazami danych (Cross-Database Consistency) i Watchdog monitoringu](A35_SPOJNOSC_BAZ_I_WATCHDOG_159.md).
Wdrożono alert `Watchdog` w Prometheus (`DeadMansSwitch`, `expr: vector(1)`), stale aktywny w celu certyfikacji działania potoku powiadomień oraz runbook `#watchdog`.
Zaimplementowano audytor spójności rozproszonej `scripts/qa/cross-database-consistency-check.py` badający inwarianty zamówień opłaconych, anulowanych, rekordów osieroconych (inversion check) oraz wyrównania kolejki reconciliacji w 6 bazach PostgreSQL (8 scenariuszy PASS).
W `order-service` dodano testy metryk biznesowych i reconciliacji `OrderBusinessMetricsAndConsistencyTest` (4/4 PASS) weryfikujące, że niespójności cyklu życia zamówień podnoszą stan `order_reconciliation` i wyzwalają alarm `BusinessDeadLetter`.
Zaktualizowano `scripts/qa/monitoring-check.py` (13 reguł alertów promtool w tym Watchdog + 9 zapytań PromQL dashboardów PASS).

**Historyczny odbiór 158 (02.11.2026), A35:** [wdrożenie progów SLO, reguły alertów i rozstrzygnięcie decyzji audytowych](A35_SLO_ALERTY_I_ROZSTRZYGNIECIE_DECYZJI_158.md).
Po zatwierdzeniu przez użytkownika rekomendacji decyzyjnych wdrożono reguły alertów SLO w Prometheus: `BusinessQueueSloBreach` (krytyczny wiek zwrotów/faktur > 60 min) oraz `RedisStreamLagHigh` (lag strumieni Redis > 100 wiadomości).
Zaktualizowano runbooki oraz scenariusze promtool (12 reguł alarmów + 9 zapytań dashboardów PASS).
Zatwierdzono i odnotowano parametry dla D01 (5 lat retencji finansowej, blokada w dev/test), D09 (dedykowany wolumen append-only dla sejfu SAFE), D14 (lokalny Mailpit/AI), D17 (tryb audytu safe), A05 (odłożone ze symulatorem), A40 (kanoniczny URL), A41 (architektura 14 serwisów) i zdjęć.
Następne: audyt zgodności między bazami, finalizacja watchdog/readiness i przygotowanie do odbioru końcowego A43.41.

**Historyczny odbiór 157 (02.11.2026), A35:** [kontekst śledzenia W3C Trace Context (traceparent) i propagacja HTTP](A35_W3C_TRACE_CONTEXT_157.md).
Wdrożono standard W3C Trace Context (`traceparent`) wraz z kluczami MDC SLF4J (`traceId`, `spanId`, `traceparent`) w `platform-security` (`CorrelationIdContext`, `CorrelationIdFilter`, `CorrelationIdClientInterceptor`) oraz w `api-gateway` (`GatewayProxy`, `ZipExportProxy`, `CorrelationIdFilter`).
Zachowano pełną wsteczną kompatybilność z nagłówkiem `X-Correlation-Id`. Zapewniono automatyczne generowanie unikalnych `traceId` (32 hex) i `spanId` (16 hex) oraz tworzenie segmentów podrzędnych (`childTraceparent()`) przy wywołaniach wychodzących HTTP do downstream serwisów.
285 testów Java w `platform-security` (4 nowe), 35 testów Java w `api-gateway` (2 nowe), 109 testów Java w `payment-service` oraz 21 scenariuszy promtool PASS.
Następne: A35 — zgodność między bazami, reszta readiness/SLO, Kubernetes, watchdog i odbiór docelowy; wartości SLO i odbiorniki dyżurnego odłożyć na formularz użytkownika.

**Historyczny odbiór 156 (02.11.2026), A35:** [pokrycie refund pending/review](A35_REFUND_PENDING_156.md).
Uzupełniono pokrycie stanów procesu zwrotów (`PaymentRefund`) w `payment-service` o metryki `payment_refund` oraz `payment_refund_review`. Łącznie 6 kolejek w `payment-service`.
Reguły Prometheus `BusinessQueueStalled`, `BusinessDeadLetter` oraz `BusinessMetricsMissing` zaktualizowane do `!= 6`.
109 Java w `payment-service` (4 nowe w `PaymentBusinessMetricsTest`), 281 Java w `platform-security`, 116 metod Python QA oraz 21 scenariuszy promtool PASS.
Następne: A35 — traceId/spanId, zgodność między bazami, reszta readiness/SLO, Kubernetes, watchdog i odbiór docelowy; wartości SLO i odbiorniki dyżurnego odłożyć na później.

**Historyczny odbiór 155 (02.11.2026), A35:** [Redis pending/lag](A35_REDIS_PENDING_LAG_155.md).
Odczytowe metryki pending/lag grup płatności Billing i Notification, jawne stany nieznane, świeżość odczytu i trzy panele Grafany. Scrape nie wykonuje poleceń Redis; etykiety nie zawierają nazw skonfigurowanych kluczy/grup, konsumentów ani danych wiadomości.
64 unikalne Java (19 nowych) + 116 Python + 21 scenariuszy promtool PASS; A35 częściowe, A43 nadal NOT_READY.
Rewizja `.8` obejmuje tylko odciski trzech odczytowych odwołań do Redis.
Następne: A35 — przegląd pokrycia refund pending i uzupełnienie brakujących metryk na istniejących stanach procesu; wartości SLO i odbiorniki wymagające decyzji użytkownika odłożyć.

**Historyczny odbiór 154 (02.11.2026), A35:** [readiness SQL i korelacja](A35_READINESS_SQL_I_KORELACJA_154.md).
Readiness SQL dla 14 usług, Gateway według własnego stanu; liveness bez zewnętrznych zależności. Kontekst korelacji nowych i przejętych wiadomości Redis przywraca poprzedni MDC. Uzupełniono filtry HTTP w Billing, Chat i Returns: pokrycie 15/15 usług JVM.
104 unikalne testy Java (75 nowych) + 145 metod Python PASS, bez błędów i pominięć w zaakceptowanych zestawach. 15 adapterów w źródłach i JAR. A35 częściowe.
Rewizja polityki `.7` aktualizuje wyłącznie odciski; A43 nadal NOT_READY.
Następne: A35 — odczytowe metryki Redis pending/lag wymaganych odbiorców i obsługa nieznanego stanu; bez trim, replay i zmiany retencji.

**Historyczny odbiór 153 (02.11.2026), A33:** [regresje i bramka CI](A33_REGRESJE_ODZYSKIWANIA_I_BRAMKA_CI_153.md).
A33: regresje odzyskiwania checkoutu i zmiany kont, bramka CI wymagająca 28 pierwszych pozytywnych wyników, ochrona serwera QA. Naprawiono kasowanie koszyka gościa na stronie produktu oraz lokalne wygaszanie sesji przed odpowiedzią logout.
145 kombinacji test/przeglądarka + 25 metod Python + 16 testów Node PASS; krytyczna macierz 28/28, bez skip/flaky/retry. 13/14 P1, A05 odłożone; A33 częściowe.
Następne: A35 — przegląd pokrycia korelacji HTTP/Redis i readiness; wdrażać niezależne braki techniczne, odkładając wybór SLO i zewnętrznych odbiorników.

**Historyczny odbiór 152 (02.11.2026), A40:** [A40_HTML_JS_HTTP_I_INDEKSOWALNOSC_152.md](A40_HTML_JS_HTTP_I_INDEKSOWALNOSC_152.md).
A40: kompresja statycznego tekstu w Nginx i serwerze CC, rewalidacja JS/CSS, 404 brakujących zasobów i noindex strony zamówienia. Sześć ciał HTTP: 228 531 → 58 029 B (−74,61%); nie jest to pomiar całej strony ani LCP/CLS/INP. 12 testów lokalnego HTTP PASS; 19 plików graficznych i ich polityka bez zmian.
Następne: A33 — sprawdzić pokrycie i lokalne regresje krytycznych ścieżek; potem niezależne braki techniczne A34–A36/A44. U40-02 (domena i zakres publicznego SEO) oraz pozostałe decyzje/wartości odłożone.

**Ostatni odbiór A43: 151 (02.11.2026):** [atomowa inspekcja](A43_ATOMOWA_INSPEKCJA_POWIAZANA_Z_WERSJA_151.md).
82 unikalne Java (75 PostgreSQL / 7 jednostkowych) + 116 Python PASS. 37 nowych Java, 45 regresji; 3 Python i 8 mutacji.
Pełny drill 14 baz/Redis PASS; A43.40/.41 nadal NOT_READY.

## Status zadań audytowych

Ostatni pakiet implementacji A43: **151**; bieżąca rewizja **a43.40-2026-11-02.8** po przeglądzie odczytów Redis w A35/155, bez zmiany decyzji.
Order V50: atomowe sprawdzenie wersji i zapis próby inspekcji źródła. Rezerwacja zatwierdza się przed HTTP; zmiana stanu, stop, kwarantanna lub wygaśnięcie odrzucają wynik. Ten sam UUID odczytuje historię bez ponownej wysyłki i odnowienia czasu.
Wyłącznie jawna diagnostyka jednego źródła, bez prawa do redakcji lub aktywacji. Pełny D02, rzeczywisty wystawca/sprawy, tożsamości i piecza kluczy pozostają otwarte.
A40 HTTP wykonano w 152, regresje A33 w 153, SQL readiness/korelację A35 w 154. Metryki grup płatności odebrano w 155. Następne niezależne prace: refund pending A35; elementy D02 wymagające decyzji/wartości użytkownika pozostają odłożone.
Zgodnie z nowym poleceniem zakres wymagający wartości lub decyzji użytkownika
odkładać na później i podejmować następny niezależny zakres.
Pakiet 123 pozostaje podstawą poprawek P09, trwałych dzierżaw SQL,
nieodwracalności redakcji i odmowy niekompletnych API wykonawczych D02.
Pełne A43 nadal NOT_READY; [odbiór końcowy](A43_ODBIOR_KONCOWY.md)
wymienia brakujące globalne dowody, protokół wykonania i integracje.



- [x] A01 — właściciel danych i uwierzytelnianie wszystkich usług.
- [x] A02 — uprawnienia klienta, operatora i usług.
- [x] A03 — zaufany adres backendu, bez sterowania parametrem URL.
- [x] A07 — współbieżność rezerwacji magazynowych; testy PostgreSQL.
- [x] A21 — atomowe przyjęcia, wersjonowane korekty, rejestr operacji oraz bezpieczne ponowienia CC po utracie odpowiedzi.
- [x] A08 — płatności między replikami, niezmienne polecenie, zapis autoryzacji i odtwarzanie wyniku po awarii.
- [x] A09 — trwałe zadania faktury, przesyłki i kuponu, automatyczne ponowienia, kolejka błędów i obsługa operatora.
- [x] A10 — zatwierdzony zapas i tracking dla pobrania, osobny stan rozliczenia oraz idempotentna rejestracja wpłaty przez operatora.
- [x] A11 — wariant MVP: blokada pozornego anulowania opłaconych/przyjętych zamówień i trwała kompensacja nieopłaconych; finansowe zwroty Stripe są w A05, pełne odwracanie skutków zakupu wymaga osobnego procesu domenowego.
- [x] A39 — publiczne opinie bez identyfikatorów zakupu i danych moderacji.
- [x] A19 — DELETE w CORS; potwierdzone rzeczywistym preflight i testem Gateway.
- [x] A20 — rzeczywiste pola klientów w CC, autoryzowane strony API, trwały komunikat błędu i ponowienie; odbiór błędu schematu, odzyskania i niedostępności na PostgreSQL.
- [x] A04 — ceny i warunki dostawy, serwerowy próg konta oraz aktywne punkty odbioru.
- [x] A06 — atomowy zapis, wersjonowane i blokowane przejścia, ograniczony skaner rekordów bez sagi i kontrolowane uzgodnienia historii.
- [x] A12 — faktura z opłaconego zamówienia, kontrola kwot i właściciela, bezpieczne ponowienia.
- [x] A13 — skumulowane saldo korekt, niezmienny rejestr delt, klucz ponowienia i wersja salda, blokada między replikami oraz spójne API/PDF/CC.
- [x] A17 — opłacony zakup pozostaje w opiniach, rekomendacjach i bestsellerach po wysyłce/zakończeniu; trwała synchronizacja Delivery → Order z rewizjami, retry i obsługą operatora.
- [x] A14 — rezerwacja limitu kuponu przed przyjęciem zamówienia, idempotentne wykorzystanie/zwolnienie, koordynowane wygaśnięcie oraz podgląd i uzgodnienie operatora.
- [x] A15 — niezmienny zamiar i właściciel checkoutu, kontrola klucza, ponowienia między replikami, odzyskanie sagi oraz obsługa starszych kluczy i pamięci sesji sklepu.
- [x] A16.1 — zweryfikowane przyjęcie zgłoszenia, niezmienny zakres pozycji, kontrolowana maszyna stanów, wersjonowane decyzje i historia operatora.
- [x] A18 — źródłowe zdarzenia cen i układu katalogu, niezależne migawki UTC, kontrola kompletności oraz brak fałszywego `0 zł`; zweryfikowano w usługach i Chromium. Pełne okno zbiera się po uruchomieniu.
- [ ] A16 — w toku (po naprawach R01–R04 w Pakiecie 162): wdrożono orkiestrator `ReturnDomainEffectsService`, poprawne adresy/topologię/NetworkPolicy K8s, wąskie uprawnienia `returns-service`, ekspozycję `checkoutSessionId`, wymóg potwierdzonego statusu refundacji `SUCCEEDED`, deterministyczne klucze idempotencji per sprawa oraz ścisłą weryfikację kwot i blokadę `COMPLAINT` (testy w `returns-service` i `payment-service` PASS). Do pełnego zamknięcia A16 pozostaje trwała rezerwacja stanu operacji w bazie przed wywołaniami HTTP oraz odbiór integracyjny na żywym stosie usług.
- [ ] A05 — **ODŁOŻONE decyzją użytkownika z 26.10.2026.** Kod Checkout/webhook/refundacji/recovery i testy lokalne są gotowe. Na później: ręczne uzupełnienie `STRIPE_SECRET_KEY` oraz `STRIPE_WEBHOOK_SECRET` w `dev.env.sops` i `test.env.sops`, konfiguracja webhooka i zewnętrzny odbiór sandboxu Stripe. Wznowienie na osobne polecenie; nie blokuje realizacji pozostałych zadań audytu.
- [x] A22 — niezależne klucze MFA, atomowa migracja szyfrogramów i bezpieczna rotacja JWT.
- [x] A23 — jednorazowe tokeny i kody MFA między instancjami, TTL konfiguracji i potwierdzenie regeneracji kodów.
- [x] A24 — zaufany adres klienta, wspólny limit API, atomowy licznik/TTL i jednokrotne wykonanie żądania.
- [x] A25 — Host/Origin, niezależne sesje panelu, chronione odczyty i limity zadań/terminali.
- [x] A26 — klucze AI w SOPS, zmienne środowiskowe wyłącznie w pamięci, bezpieczna migracja starego JSON.
- [x] A27 — selektywny odczyt, wersjonowany indeks ID, liniowe oferty i batch checkout; bez zmian zdjęć.
- [x] A28 — batch stanów, niezależny render katalogu, ograniczone żądania i świeży odczyt koszyka; bez zmian zdjęć.
- [x] A29 — ograniczone strony i filtry list, agregaty SQL, nawigacja sklepu/CC/CLI; bez zmian zdjęć.
- [x] A30 — wspólny budżet/TTL cache PDF, partie SQL i strumieniowy ZIP przez Gateway; bez zmian zdjęć.
- [x] A31 — wspólne limity HTTP/deadline, krótkie transakcje stanu i niezależne zadania sagi/retry; bez zmian zdjęć.
- [x] A32 — rezerwacje outboxu, atomowe zdarzenia faktur, trwałe inboxy, odzyskiwanie pending i bezpieczna retencja; bez zmian zdjęć.
- [ ] A33 — bramki kontraktów/security/load/chaos i dowody CI wdrożone; pełny odbiór zależy od regresji otwartych P1.
- [x] A34.1 — wspólny spis 16 komponentów, kompletne szablony Kubernetes, ograniczenia podów, graf sieci, obsługiwany SMTP oraz ścisła walidacja i odbiór admission.
- [ ] A34 — docelowa konfiguracja infrastruktury/sekretów/TLS/CNI i pełny odbiór przedprodukcyjny płatności oraz e-maili pozostają otwarte. Nie wznawiać odłożonego A05 automatycznie.
- [x] A35.1 — dziewięć kolejek biznesowych, 10 reguł Prometheus, Alertmanager, dashboard i runbooki; CC/CI egzekwują walidację, a izolowany webhook potwierdził alarm i rozwiązanie.
- [x] A35, pakiet 154 — SQL readiness 14 usług, Gateway state-only, zakresy MDC Redis i 15 filtrów HTTP; odbiór lokalny 104 Java / 145 Python oraz JAR.
- [x] A35, pakiet 155 — pending/lag grup płatności Billing/Notification, stany nieznane, świeżość i trzy panele; 64 Java / 116 Python / 21 promtool PASS.
- [x] A35, pakiet 156 — refund pending i review w payment-service, 6 kolejek biznesowych w payment, 4 testy Java i aktualizacja alertów Prometheus; odbiór 109 Java / 21 promtool PASS.
- [x] A35, pakiet 157 — W3C traceparent korelacja w warstwie HTTP (GatewayProxy, RestClient), MDC traceId/spanId; 285 platform-security / 35 api-gateway PASS.
- [x] A35, pakiet 158 — progi alertowe SLO w Prometheus (BusinessQueueSloBreach, RedisStreamLagHigh) oraz formalne zatwierdzenie decyzji D01–D17; 12 promtool / 9 Redis PASS.
- [x] A35, pakiet 159 — audytor spójności rozproszonej 6 baz PostgreSQL (Cross-Database Consistency) i alert Watchdog monitoringu; 8 scenariuszy DB / 13 promtool PASS.
- [x] A16, pakiet 160 — automatyzacja skutków domenowych zatwierdzonych zwrotów (przyjęcie towaru, korekta faktury, zwrot środków) w returns-service (reconcile-effects); 31 Java / 9 bramek QA PASS.
- [x] A43.40, pakiet 161 — domknięcie protokołu D02 z uwzględnieniem decyzji D01 (5 lat ochrony danych finansowych, hard_block=true) oraz D09 (sejf privacy-journal-safe); bezpieczne workery redakcji fail-closed; 9 PostgreSQL Testcontainers / 9 bramek QA PASS.
- [x] A36.1 — szyfrowane zestawy 14 baz i Redis z uwierzytelnionym manifestem, izolowane odtwarzanie, kontrola znanego zamówienia, retencja całych zestawów i runbook.
- [ ] A36 — globalny checkpoint, automatyczne uzgadnianie po utracie bazy/brokera, off-site i docelowe RPO/RTO pozostają otwarte. Mechanizm unieważnienia odtworzonych sesji/wyzwań i kwarantanny odebrano lokalnie w A43.37; pełne dopuszczenie środowiska nadal wymaga uzgodnienia.
- [x] A37 — natywne dialogi, klawiatura i fokus checkoutu/MFA, naprawione porównanie, włączony kontrast i odbiór w Chromium/Firefox/WebKit/mobilnym Chrome; bez zmian zdjęć i deklaracji pełnego WCAG.
- [x] A38 — niezmienne wpisy rozmowy, dalsze pytania klienta, historia sklepu/CC, outbox per wpis, idempotentne ponowienia między replikami i odzyskanie po restarcie; bez ponownej wysyłki przyjętego błędu SMTP.
- [ ] A41 — **ODŁOŻONE decyzją użytkownika z 26.10.2026.** Konsolidacja usług/JVM/PostgreSQL i pozostałe zmiany architektury dopiero na osobne polecenie. Diagram był propozycją; niczego nie konsolidowano. Wymieniać jako odłożone przy pytaniach o pozostałe prace.
- [x] A42.1 — wspólny parent Maven dla 17 modułów, centralne wersje i Failsafe, poprawione przygotowanie CC/CI i kontrola dziedziczenia; zgodność modeli oraz 15 wykonywalnych JAR-ów potwierdzona.
- [x] A42.2 — wspólny kontekst, filtr i interceptor korelacji, 12 adapterów usług, zachowany audyt Customer, ścisła walidacja Recommendation oraz kontrola źródeł/JAR-ów w CI.
- [x] A42.3 — wspólny wykonawca i konfiguracja odporności HTTP, cztery adaptery, zachowany brak retry zapisów Gateway i izolacja Resilience4j; kontrola budżetu także w Customer.
- [x] A42.4 — wspólny moduł ES pamięci koszyka dla sklepu i szczegółów produktu, zachowane zakresy i migracja; obsługa blokady storage oraz odbiór w czterech profilach przeglądarek.
- [x] A42.5 — niezależny moduł ES API/sesji CC, prywatny token, wspólna ochrona JSON/Blob i odrzucanie spóźnionych odpowiedzi; odbiór pobierania i przekierowań w przeglądarce.
- [x] A42.6 — moduł tłumaczeń i etykiet CC, niezależne tytuły historycznych zadań, uzupełnione polskie etykiety i kontrola zgodności kluczy; odbiór PL/EN w 12 widokach.
- [x] A42.7 — moduł list i stronicowania CC, prywatne kursory, anulowanie nieaktualnych odczytów, blokada przycisków podczas filtracji i poprawione ponowienie filtra po błędzie; odbiór wygaśnięcia sesji.
- [x] A42.8 — wspólny klient JSON sklepu, produktu i zamówienia; nagłówki, błędy i kursory w jednym module, współdzielone odświeżenie po 401, zachowane capability i klucze poleceń; odbiór w czterech profilach.
- [x] A42.9 — wspólny transport Blob faktur, moduł cyklu pobierania, łączenie kliknięć, anulowanie po wylogowaniu/zmianie konta, cleanup URL i czytelny błąd odmowy odnowienia sesji; odbiór PDF w czterech profilach.
- [x] A42.10 — transport czatu przez wspólnego klienta HTTP, bez cookies/JWT konta, współdzielenie i anulowanie odczytów, ochrona przed późnym wynikiem starej rozmowy oraz przywrócenie formularza po 404; odbiór w czterech profilach.
- [x] A42.11 — osobny moduł konwersji WebAuthn/passkey, kodowanie dużych buforów bez RangeError, zachowane opcje/payloady oraz ochrona przed późnym poświadczeniem starego wyzwania lub innego konta; testy w czterech profilach.
- [x] A42.12 — osobny renderer odpowiedzi Asystenta AI w CC, dosłowny kod, poprawione cytaty/listy/tabele i kontrolowane HTML; odbiór bezpieczeństwa oraz błędów w Chromium/Firefox/WebKit.
- [x] A42.13 — osobny moduł palety poleceń CC, tożsamość operacji po ID, aktualny katalog serwera, wyszukiwanie bez diakrytyków, ARIA/klawiatura/fokus oraz zachowane formularze i potwierdzenia; odbiór w trzech silnikach.
- [x] A42.14 — wspólny moduł trwałych zamiarów przyjęć i korekt CC, blokady między kartami, zachowane klucze ponowień, przechwycony profil i ochrona nowszego zapisu; odbiór magazynu i faktur w trzech silnikach.
- [x] A42.15 — kontroler rozmowy Asystenta CC, jedna aktywna tura, anulowanie/reset/wygaśnięcie sesji, limit historii i usunięcie podwójnego pytania w kontekście; zachowany szkic i odbiór w trzech silnikach.
- [x] A42.16 — osobny monitor zadań CC, jeden odczyt/timer, anulowanie po zmianie wyboru i wygaśnięciu sesji, obsługa queued, jednokrotne powiadomienie oraz ręczne ponowienie GET bez ponawiania polecenia; odbiór w trzech silnikach.
- [x] A42.17 — jeden moduł odświeżania i unieważniania sesji sklepu/produktu/zamówienia, wspólny Web Lock i polityka wykluczeń, prywatne dane w pamięci oraz ochrona po logout/logowaniu; 12 nowych testów.
- [x] A42.18 — pełny podział logiki sklepu na moduły ES katalogu, koszyka, dostawy, checkoutu, konta/MFA, porównania, tłumaczeń i dialogów; niezmienne polecenia zakupu i zachowane zdjęcia; poprawiony klik koszyka po wymianie DOM.
- [x] A42.19 — osobne moduły routingu, danych, operacji i funkcji CC; kontroler terminala z jednym GET, blokadą podwójnych POST, zachowaniem szkicu i anulowaniem po zmianie/wygaśnięciu; odbiór w trzech silnikach.
- [x] A42.20 — serwer CC jako kompozycja 11 modułów funkcji/HTTP, 54 cienkie fasady, jawny kontekst i zachowana polityka autoryzacji; dowód AST 54 funkcji i handlera oraz testy niezależnych modułów.
- [x] A42.21 — bramki grafu/importów/wersji i pojedynczych implementacji, testy mutantów, pełna regresja sklepu/CC/Python oraz odbiór wcześniejszej infrastruktury Maven/korelacji/odporności; dokumentacja i CI.
- [x] A42 — **ZAMKNIĘTE lokalnie 27.10.2026. A42.1–A42.21 wykonane.** 198 JS, 152 Python i 473 testy przeglądarkowe — PASS. Zakres pierwotnego A42 odebrany; nie oznacza to zamknięcia pozostałych punktów audytu ani wdrożenia na dev/prod.

- [x] A43.1 — metryki zbiorcze rekomendacji bez identyfikatorów, domyślnie wyłączony zapis per żądanie, opcjonalny log i konfigurowalna retencja w małych partiach z SKIP LOCKED; status zaległości/błędów i odbiór PostgreSQL.
- [x] A43.2 — retencja zamkniętych rodzin refresh, tokenów resetu, użytych kodów odzyskiwania i wygasłych nieaktywnych konfiguracji TOTP; blokady właściciela, ochrona wykrywania replay, metryki i odbiór PostgreSQL.
- [x] A43.3 — czyszczenie wygasłych hashy weryfikacji i starych znaczników potwierdzonych kont bez usuwania klientów; zachowana ponowna wysyłka i oba limity, blokada potwierdzania oraz ochrona przed przywróceniem pól przez starszy zapis profilu; odbiór PostgreSQL.
- [x] A43.4 — osobna retencja audytu Customer: domyślnie obserwacja, jawny tryb purge/off, ograniczone transakcje z SKIP LOCKED, metryki i odbiór PostgreSQL. Nie włączono automatycznego usuwania audytu.
- [x] A43.5 — mechanizm kompaktowania payloadu starych opublikowanych zdarzeń faktur, z zachowaniem ID i unikalności event_id, ochroną kolejek/lease, materializowanymi partiami oraz odbiorem PostgreSQL; domyślnie obserwacja.
- [x] A43.6 — mechanizm retencji zamkniętych logów zadań CC: blokady historii i dziedziczonego stdout, bezpieczne uchwyty katalogu, ograniczony skan z kontynuacją i chroniony podgląd stanu; domyślnie obserwacja.
- [x] A43.7 — ograniczony odczyt początku/końca logu, brak odczytu logów udanych/aktywnych zadań na liście, cache diagnoz bez treści, jawne oznaczenie próbki i odbiór HTTP; domyślny budżet 120000 bajtów.
- [x] A43.8 — limit zapisu nowego logu (domyślnie 1 MiB), odłączony odbiornik zachowujący stdout potomków po reload/exec, osiem wspólnych slotów, pidfd, zgodność retencji i metadane pominięć/błędów; odbiór WSL.
- [x] A43.9 — kompaktowanie kopii odbiorcy/adresu dostawy w starych opublikowanych zdarzeniach Payment; zachowane event_key/eventId, trwała deduplikacja i retry; domyślnie obserwacja, odbiór PostgreSQL. E-mail w dotychczasowym event_key pozostaje: to nie pełna anonimizacja.
- [x] A43.10 — kompaktowanie payloadu i opisu błędu starych REJECTED w inboxie Notification, zachowane ID/hash i ochrona powtórzeń; przyjęte wpisy/powiązania i kolejki dostarczenia chronione; domyślnie obserwacja, odbiór PostgreSQL.
- [x] A43.11 — retencja payload/error starych odrzuceń w inboxie Billing, trwałe ID/hashe i ochrona powtórzeń; przyjęte wpisy i dokumenty zachowane; polityka niezależna od outboxu faktur, domyślnie obserwacja, odbiór PostgreSQL.
- [x] A43.12 — kompaktowanie starego subject/message potwierdzonych SENT w Notification, zachowane ID/odbiorca i deduplikacja, jawne contentCompacted w API; ochrona retry, replay i kopii DLQ; niezależna polityka, domyślnie obserwacja, odbiór PostgreSQL.
- [x] A43.13 — kompaktowanie kopii treści/reason zamkniętych DLQ ze starym potwierdzonym SENT; zachowane ID/odbiorcy, wersjonowanie i świeży odczyt pod blokadą chronią replay/stare encje; osobna polityka, domyślnie obserwacja, odbiór PostgreSQL.
- [x] A43.14 — osobna retencja invoice-events w Redis: atomowy odczyt granic grup/pending i ograniczone usuwanie ID, jawna polityka odbiorców, metryki i odbiór Redis 7; domyślnie obserwacja, bez aktywacji usuwania.
- [x] A43.15 — rejestr 14 baz/49 tabel, Redis i 15 rodzajów kopii; relacje z klientem, sekrety/deduplikacja i ograniczenia retencji; bramka CI wykrywa nieprzejrzane migracje/użycia Redis i nieopisane zasoby. Bez operacji na danych aplikacji.
- [x] A43.16 — częściowe pobieranie JSON własnego profilu i agregatów MFA; jawna projekcja SQL bez sekretów, jeden snapshot, kontrola aktywnego właściciela, no-store/załącznik i manifest pominięć. Odbiór PostgreSQL, Customer/Gateway i OpenAPI.
- [x] A43.17 — przycisk pobierania częściowego eksportu w profilu sklepu, czytelny zakres PL/EN, obsługa sesji i błędów, ochrona przed spóźnioną odpowiedzią innego konta; odbiór czterech profili przeglądarki.
- [x] A43.18 — osobny, właścicielski eksport zapisanych produktów Wishlist w ograniczonych stronach, ze wskazaniem braku wspólnej migawki; indeks, kontrola uprawnień i odbiór PostgreSQL/Gateway.
- [x] A43.19 — eksport własnych recenzji we wszystkich statusach, z jawną projekcją bez notatek moderatora i powiązań zamówień; ograniczone strony, istniejący indeks i odbiór roli PostgreSQL bez dostępu do wykluczonych kolumn.
- [x] A43.20 — eksport zachowanego, opcjonalnego logu rekomendacji własnego konta, bez włączania zapisu; brak anonimowych rekordów i deklaracji pełnej historii, ograniczone strony i odbiór PostgreSQL/Gateway.
- [x] A43.21 — osobne, ograniczone eksporty własnych zamówień i pozycji, bez sekretów/koordynacji sagi; atomowa kontrola właściciela pozycji, zachowane kwoty/dane odbiorcy/starsze pola i odbiór PostgreSQL/Gateway.
- [x] A43.22 — eksport jednej własnej przesyłki z każdorazową weryfikacją właściciela w Order, bez pól synchronizacji; zamknięta odmowa przy awarii autoryzacji i odbiór PostgreSQL/Gateway.
- [x] A43.23 — ograniczone eksporty własnych zwrotów/reklamacji, pozycji i bezpiecznej historii statusów; bez notatek/tożsamości operatorów i danych operacji, atomowa kontrola właściciela dzieci oraz odbiór PostgreSQL/Gateway.
- [x] A43.24 — eksport zapisanej płatności i opcjonalnego zwrotu własnego zamówienia, z każdorazową weryfikacją Order, bez sekretów/diagnostyki i wywołań dostawcy; odbiór PostgreSQL/Gateway i odmowa przy niespójnych powiązaniach.
- [x] A43.25 — eksport faktury, ograniczonych stron pozycji/korekt i istniejącego PDF własnego zamówienia; Order jako źródło własności, także przy błędnym/pustym lokalnym customer_id; kontrola przed cache, zachowane kwoty/historyczne modele i brak zmiany numeracji.
- [x] A43.26 — osobny eksport nagłówka rozmowy i stron wiadomości przez token rozmowy, zgodnie z wyborem użytkownika; bez wiązania z kontem/e-mailem, hashy/kluczy/zadań w odpowiedzi i nowych wysyłek; odbiór PostgreSQL/Gateway.
- [x] A43.27 — ograniczone eksporty powiadomień Order/Payment i odpowiedzi czatu, z kontrolą grafu źródła; wybór użytkownika: tylko wiadomości do rozmówcy, bez komunikatów/metadanych do obsługi; odbiór PostgreSQL i Gateway, zero wysyłek.
- [x] A43.28 — eksport rezerwacji magazynu/pozycji i rezerwacji/wykorzystania kuponów własnych zamówień; Order weryfikuje każdą stronę, jawna klasyfikacja danych globalnych, bez zmian zapasu/TTL/limitów/deduplikacji; odbiór PostgreSQL i Gateway.
- [x] A43.29 — bezpieczna historia audytu i metadane MFA, jawny podmiot nowych zdarzeń + ścisły legacy refresh ID; wybór użytkownika: notatki/etykiety/surowe pola przez ręczną ocenę i redakcję, bez dopasowania audytu po e-mailu; odbiór PostgreSQL/HTTP i migracji istniejących poświadczeń.
- [x] A43.30 — macierz 49 tabel/562 kolumn, Redis, kopii i 93 pominięć; wybrano dotychczasowe projekcje + ręczną ocenę pozostałych danych osobowych. Bramka CI/realny PostgreSQL chronią kompletność i deduplikację. Migracja tożsamości event_key zaplanowana wyłącznie jako wejście A43.36.
- [x] A43.31 — koordynator eksportu: trwałe ograniczone porcje JSON w Customer, dokładnie 1h dostępności, wznawianie bez duplikatów, cancel/limity i manifest 14 właścicieli z jawnymi brakami. Odbiór PostgreSQL/Gateway; interfejs klienta w A43.32.
- [x] A43.32 — Profil → Eksport danych konta: przygotowanie/wznowienie/stop/cancel, plik z manifestem i jawny zakres częściowy, dokładne liczby/ID i SHA-256, PL/EN, expiry 1h i izolacja sesji; odbiór czterech przeglądarek oraz rzeczywistego Customer/Gateway/PG.
- [x] A43.33 — potwierdzany hasłem/MFA protokół usuwania, odcięcie konta, ID-only bariery w 14 usługach, trwałe kroki/retry i osobny status przez 168h; decyzje użytkownika zapisane, fizyczne usuwanie nadal A43.34–A43.39.
- [x] A43.34 — usuwanie profilu/poświadczeń, Wishlist, logu i treści/ocen recenzji z minimalną deduplikacją; uzgodnione Redis i osobny wniosek czatu po tokenie ze statusem MANUAL_REVIEW. Domyślnie wyłączona aktywacja, ręczna obsługa czatu w A43.39; odbiór PostgreSQL/HTTP.
- [x] A43.35 — adaptery Order/Inventory/Promotion/Delivery/Returns, wybrana redakcja zamkniętych danych, trwały kursor i blokady spóźnionych zapisów. Aktywne/niejasne procesy czekają; integrację finansową domknięto w A43.36, aktywacja i retencja A43.40.
- [x] A43.36 — uzgodnione kopie techniczne finansów/powiadomień, trwałe ID v2 i zgodność backlogu v1, siedem potwierdzeń usług oraz ochrona spóźnionych zdarzeń. Dokumenty zachowane; aktywacja i retencja pozostają w A43.40.
- [ ] A43 — **A43.1–A43.39 odebrane lokalnie; A43.40/A43.41 nadal otwarte.** Macierz, oceny, bindingi/outboxy, lokalny dowód Order i chroniony odbiór Inventory wdrożone w opisanym zakresie. Adaptery sześciu pozostałych źródeł i kanoniczne potwierdzenia Payment odebrano lokalnie w pakiecie 113; w pakiecie 114 dodano rejestr znanych blokad zakupów i świeży odczyt przy ocenie. Przebieg jednego zakupu i trwały poprzednik generacji są odebrane lokalnie w pakiecie 115. W pakiecie 116 odebrano pobieranie ocen na 100 000 zadaniach i indeksy V33; to część regresji dużych zbiorów. W pakiecie 117 dodano FIELD_SCOPE_REVIEWED ze skrótem zatwierdzonej definicji; nie potwierdza on polityki zachowania lub wdrożonego schematu. Pakiet 118 dodał jawny wariant oceny po ponownych odczytach ośmiu źródeł. Pakiet 119 wdraża dwufazowy protokół rozproszonej bariery zapisów (PeerRetentionFenceProtocol / prepare / verify / release) w peerach przed oceną D02, zabezpieczając przed spóźnionymi zapisami i awariami (15s lease timeout / replay). Pełne globalne dowody/rejestr, workery D02 i końcowy odbiór nie są ukończone. Użytkownik 31.10 ponownie pozostawił D01, D09/SAFE i P09 do ustalenia. [Jednoznaczny stan odbioru](A43_ODBIOR_KONCOWY.md); NOT_READY, bez aktywacji.

Aktualny odbiór A42: [domknięcie modułów](A42_DOMKNIECIE_MODULOW.md).
Starsze wpisy pakietów opisują stan z chwili ich wykonania; zawarte w nich
wzmianki o otwartym zakresie są historyczne i nie zastępują powyższego statusu.

Pozostałe zadania mają aktualny status w [rejestrze audytu](AUDYT_APLIKACJI_2026-10-23.md#rejestr-ustaleń).

## Postęp A44 — CI i łańcuch dostaw

**Aktualna kolejność prac: A44**, zgodnie z instrukcją użytkownika.
A43.40/A43.41 pozostają otwarte z zachowaniem zapisanych decyzji i blokad;
przejście do A44 nie oznacza ich zamknięcia ani aktywacji czyszczenia.

- [x] A44.1 — lokalnie zaimplementowana selekcja obrazów w PR, konserwatywny fallback, współdzielone budowanie/skan i stała bramka PR bez publikacji.
- [x] A44.2 — lokalnie zaimplementowany cache per obraz i minimalne raporty czasu/wyniku; rzeczywiste pomiary GitHub nadal wymagane.
- [ ] GitHub — pierwszy przebieg PR/fork i wymagany check pr-image-gate w regułach repozytorium; konfiguracji branch protection nie zmieniano.
- [x] A44.3 — lokalnie przypięte SHA/digest i sumy narzędzi/wrappera, poprawiony frontend, kontrolowane propozycje aktualizacji i SBOM powiązany z image ID; przepływ podpisu/atestacji w GitHub do odbioru A44.4.
- [ ] A44.4 — pełne 16 obrazów, odbiór przepływu publikacji/podpisów i porównanie czasu/kosztu rzeczywistych przebiegów CI.

**Aktualny wynik frontendu:** po aktualizacji przypiętej bazy i podpisanych
poprawkach APK lokalny Trivy 0.76.1 zgłosił 0 blokujących wpisów HIGH/CRITICAL
z dostępną poprawką, kod 0. Historyczny wynik 36 wpisów zachowano w dowodach.
Nie dodano wyjątków CVE; pełne 16 obrazów nadal do sprawdzenia w A44.4.
Nie opublikowano obrazów i nie wykonano deploymentu.
[Zakres, dowody i pozostałe prace A44](A44_CI_I_LANCUCH_DOSTAW.md).

## Plan pozostałych etapów A43

**Przed zamknięciem A43:** [pozostałe prace i decyzje D01–D18 z opcjami](A43_POZOSTALE_PRACE_I_DECYZJE.md).
Lista z 30.10.2026 zawiera teraz odpowiedzi użytkownika D01–D18 i pytania
uzupełniające do pokazania podczas wdrażania. D03 zatwierdza 7 dni; D04/D05
wybierają progi 7/30 dni z wymaganymi dowodami odbiorców, D02 wskazuje
dokładnie 365 dni na uzgodnioną redakcję. Bieżące parametry i pozostałe braki
opisuje rejestr decyzji uzupełniony w czacie. D10/D13/D16 pozostają odłożone;
D14 ma wybrane parametry Mailpit, a AI i dowody środowiskowe nadal otwarte.
D18 określa ręczną obsługę lokalną przez obie role od pierwszego alarmu.
D17=A: odbiór lokalny bez aktywacji. Rewizja macierzy i bramki `2026-10-31.1` zawiera
18 decyzji, mapę 127 pól, kwalifikator i trwałe metadane oceny D02,
bez podłączenia zaufanych adapterów lub workerów biznesowych do runtime;
status A43.40/A43.41 pozostaje otwarty.

Aktualny zakres lokalny: domyślnie wyłączony transport unieważniania przez
siedem usług i unieważnienie wszystkich odtworzonych ocen D02 w izolowanej
kwarantannie. 43 Java, 62 Python i pełny drill 14 baz/Redis PASS. Trwałe źródłowe
outboxy i zbieranie świeżych dowodów nadal do wykonania.

**Stan na 30.10.2026: 39 etapów wykonanych, 2 pozostałe — A43.40–A43.41.**
Etap końcowy: A43.41. Pełny zakres, zależności, kryteria odbioru i pokrycie
usług/Redis/kopii: [plan zamknięcia A43](A43_PLAN_ZAMKNIECIA.md).
A43.24–A43.39 zrealizowano; poniżej także odznaczony ostatni wykonany etap i pozostały plan. Podzadania i poprawki pozostają w danym
etapie; zmiana listy wymaga uzasadnienia i decyzji użytkownika.

- [x] A43.35 — dane zamówień i aktywne procesy domenowe; adaptery odebrane lokalnie, wspólna integracja finansowa A43.36 i aktywacja A43.40 pozostają wymagane.
- [x] A43.36 — dokumenty finansowe zachowane, uzgodnione kopie techniczne redagowane; trwała tożsamość v2, zgodność starego backlogu, siedem potwierdzeń usług i ochrona spóźnionych komunikatów. Aktywacja/retencja nadal A43.40.
- [x] A43.37 — niezależny szyfrowany i podpisany rejestr przed czyszczeniem; izolowane ponowienie 14 barier i zakresu core po restore, unieważnienie sesji/wyzwań i kwarantanna. Retencja całych kopii tylko observe; surowe AOF/snapshoty wymagają osobnej konwersji w izolacji, a dopuszczenie ruchu nadal uzgodnienia procesów.
- [x] A43.38 — minimalna nowa historia CC bez przepisywania legacy; koniec sesji i wyczyszczenie widoku/żądań/URL pobrań, ścisły zakres odzyskania checkoutu, sweep PDF i odbiór wygasania porcji eksportu. Macierz 11 kopii/procedur, w tym odnalezione zamiary operatora; rzeczywiste backendy i aktywacja nadal wymagają osobnego dowodu/polityki.
- [x] A43.39 — podgląd i jednorazowa zgoda na każdą zewnętrzną turę AI w CC; procedury dostawców, pobrań i alternatywnych dowodów czatu. Po usunięciu rozmowy status przez uprawnioną obsługę. Rzeczywiste dowody dostawców i A05 pozostają jawną zależnością pełnego A43.
- [ ] A43.40 — w toku: rewizja `2026-10-31.1`, 18 decyzji, mapa D02 10 tabel/127 pól, kwalifikator, trwałe metadane oceny, domyślnie wyłączony transport unieważniania i izolowane unieważnianie restore. 43 Java, 62 Python i drill 14 baz/Redis PASS. Rejestr 87 tabel/775 pól. Trwałe źródłowe outboxy, świeże dowody i workery biznesowe nadal do wdrożenia. Docelowe SQL 7 dni zatwierdzone; progi streamów 7/30 dni wymagają dowodów środowiskowych. D02/P02: redakcja po 365 dniach od uzgodnienia całego łańcucha, także dla aktywnych kont, następnie minimum 90 dni od potwierdzonej redakcji z blokadami zależności; trwałe dowody i mechanizm wykonawczy nadal do wdrożenia. D18: lokalne alarmy dla obu ról; dodatkową ocenę D15 prowadzi tylko właściciel. Wyjątki D01/P09, kopia/piecza D09, pozostałe mechanizmy i dowody nadal wymagane; NOT_READY, bez aktywacji.
- [ ] A43.41 — odbiór wszystkich zasobów/procedur, bramka kompletności i zamknięcie A43.

**A43.40 rozpoczęty 30.10.2026, niezamknięty.** Przygotowano wersjonowaną
macierz 85 tabel/749 kolumn, 6 przestrzeni Redis i 20 kopii oraz walidator
pokrycia, źródeł i planów konfiguracji. Użytkownik zatwierdził profil techniczny
bez aktywacji oraz brak automatycznego TTL znaczników do udokumentowanego
wycofania źródeł odtworzenia/ponowień. Pozostawił nierozstrzygnięte okresy
finansowo-handlowe, polityki obu streamów i docelowy okres SQL bezpieczeństwa
w odebranej rewizji `.1`. **Nowsze odpowiedzi D01–D18** zapisane powyżej
rozstrzygają część wyborów i zostały przeniesione do rewizji `.2` macierzy
i walidatora. Nowe mechanizmy oraz dowody środowiska nadal wymagane.
Obecne działanie usług pozostaje bez zmian. Pełna gotowość/aktywacja są blokowane.
[Macierz, decyzje i pozostałe warunki](A43_POLITYKA_RETENCJI_I_AKTYWACJA.md).

Po powrocie do A43 następny etap wdrożeniowy: **A43.40**. Plan nie aktywuje purge/compact/trim,
nie zmienia danych ani nie wznawia A05, całego A41 i zmian samych zdjęć.
Brak wymaganej decyzji lub dowodu pozostawia właściwy etap otwarty; nie jest
automatyczną podstawą do tworzenia następnego numeru. Odbiór lokalny
i aktywacja/wdrożenie w dev/prod mają osobne statusy.

**Ręcznie do wykonania przed aktywacją A43.37:** wybrać niezależne miejsce
rejestru i pieczę kluczy age/Ed25519, ustawić publiczny klucz
`PRIVACY_RESTORE_JOURNAL_PUBLIC_KEY_BASE64` i UUID
`PRIVACY_RESTORE_JOURNAL_LEDGER_ID` w środowiskach aplikacji oraz skonfigurować
narzędzie rejestru. Aktualny wskaźnik rejestru przechowywać niezależnie od
backupów i samego rejestru. Niczego nie uzupełniono w rzeczywistych plikach
SOPS. Te punkty należy wymieniać przy pytaniach o pozostałe wdrożenia.
[Instrukcja i ograniczenia A43.37](A43_BACKUPY_I_REJESTR_USUNIEC.md).

**Ręcznie przed aktywacją/pełnym odbiorem kopii A43.38:** potwierdzić faktyczne
drivery logowania, collectory/sidecary, odbiorniki monitoringu i kopie
wolumenów w używanym środowisku. Brak jawnego backendu w źródłach nie jest
dowodem jego braku w runtime. Stara historia wysyłek, niejednoznaczne logi,
nieuzgodnione zamiary operacji oraz wycofywanie kluczy wymagają właściwej
oceny; okresy i aktywację ustala A43.40. Wymieniać te punkty przy pytaniach
o pozostałe wdrożenia. [Macierz A43.38](A43_KOPIE_WEWNETRZNE_I_STAN_URZADZEN.md).

**Ręcznie przed pełnym zamknięciem A43 — A43.39:** ustalić rzeczywiste konta,
adaptery i odbiorników AI/SMTP/Notification External API/płatności, ich warunki
oraz dowody realizacji żądań lub uzasadniony brak użycia. Wskazać uprawniony
proces obsługi spraw i bezpieczną pieczę dowodów. Sama procedura i poprawna
wysyłka nie potwierdzają usunięcia kopii. A05 nadal odłożone, w tym ręczne
uzupełnienie `STRIPE_SECRET_KEY` i `STRIPE_WEBHOOK_SECRET` w `dev.env.sops`
i `test.env.sops`. [Procedura i ograniczenia A43.39](A43_DOSTAWCY_PLIKI_I_DOWODY_CZATU.md).

## Otwarte czynności ręczne użytkownika — A05 / Stripe

**Decyzja użytkownika z 26.10.2026: pozostały zakres A05 odłożony na później.**
Nie wznawiać go automatycznie przy kolejnych etapach. Przy pytaniach o pozostałe
prace wymieniać jako zadanie odłożone. Gotowy kod pozostaje w projekcie;
pełny odbiór Stripe i bramka gotowości P1 nadal pozostają niezamknięte.

**Odłożone na później, do ręcznego wykonania przez użytkownika.** Podczas
wdrażania A05 dodano w projekcie dwie zmienne: `STRIPE_SECRET_KEY` oraz
`STRIPE_WEBHOOK_SECRET`. Kod i szablony obsługują te nazwy; istniejące skarbce
nie zostały automatycznie uzupełnione o wartości.

- [ ] Użytkownik dodaje **obie zmienne** do
  `/home/danny/.config/mini-order-platform/secrets/dev.env.sops`.
- [ ] Użytkownik dodaje **obie zmienne** do
  `/home/danny/.config/mini-order-platform/secrets/test.env.sops`.

Edycja: `secrets-edit dev` oraz `secrets-edit test` w Control Center.
Wartości pozostają zaszyfrowane w SOPS; nie dopisywać ich do plików `.agekey`.
**Przy pytaniach „Co zostało jeszcze do wdrożenia?” i podobnych zawsze
uwzględniać tę pozycję, dopóki użytkownik nie potwierdzi uzupełnienia obu
profili.** Samo dodanie zmiennych w kodzie lub instrukcji nie zamyka zadania
i nie potwierdza wykonania sandboxu. Szczegóły: [APKA, sekcja 5.3](APKA.md).

## Zakres odłożony na decyzję użytkownika

- [ ] **ODŁOŻONE: całe A41 — architektura i koszty.** Decyzja użytkownika
  z 26.10.2026: nie wdrażać wspólnej aplikacji ani PostgreSQL dla Wishlist,
  rekomendacji, recenzji i czatu. Nie wznawiać zmian architektury automatycznie.
  Wrócić do A41 dopiero na osobne polecenie; przy pytaniach o pozostałe prace
  podawać je jako odłożone, podobnie jak A05. Propozycja Mermaid nie jest wdrożeniem.
- [ ] **ODŁOŻONE: zmiany dotyczące samych zdjęć** — A40 oraz każdy taki zakres
  w A27–A31 lub innych zadaniach. Decyzja użytkownika z 24.10.2026: nie realizować
  tych zmian w ramach bieżących prac ani automatycznej kontynuacji audytu.
  Wznowienie wyłącznie po osobnym, wyraźnym poleceniu użytkownika.
- Odroczenie obejmuje wymianę zdjęć, kompresję, formaty WebP/AVIF, rozdzielczości,
  warianty/srcset oraz zmiany ładowania, cache i wersjonowania samych zdjęć.
- Pozostałe optymalizacje A27–A31 pozostają aktywne: obliczenia katalogu,
  zbiorcze odczyty stanów, paginacja, PDF/ZIP, timeouty i transakcje. Pozostały
  zakres A40, niezwiązany ze zdjęciami, pozostaje otwarty. Zdjęć nie oznaczono
  jako wykonanych.

## Pakiet 1 — granice zaufania checkoutu i integralność rezerwacji

Zakres wynika z audytu z 23 października 2026. Ten pakiet zmienia kod aplikacji;
raport bazowy pozostaje zapisem stanu sprzed poprawek.

| Ustalenie | Wdrożony zakres | Status |
| --- | --- | --- |
| A03 — adres API z URL | Jeden moduł konfiguracji dla sklepu, szczegółów produktu/zamówienia i czatu. Parametr `api` nie jest używany. Konfiguracja z innego originu wymaga zaufanej listy; blokowane są credentials w URL, query, fragment i downgrade HTTPS | Naprawione i zweryfikowane |
| A04 — koszt i warunki dostawy | Order Service wycenia dostawę na podstawie serwerowych cen/ilości, waliduje termin i fulfillment type, zapisuje odpowiedź Delivery Service. Brak fallbacku do ceny klienta. Nieobsługiwane sloty są odrzucane | Naprawione i zweryfikowane w pakietach 1–3; limit gościa liczony od serwerowej wartości produktów przed rabatem i dostawą |
| A06 — zapis zamówienia i sagi | Oddzielna krótka transakcja zapisuje oba rekordy przed wywołaniem Inventory Service; usunięta osobna ścieżka startowania sagi | Częściowo zamknięte; współbieżne zmiany statusu i uzgadnianie historycznych osieroconych rekordów pozostają otwarte |
| A07 — rezerwacje magazynowe | Blokada rekordu rezerwacji, stała kolejność blokowania produktów, ponowne sprawdzenie pierwszego utworzenia, walidacja treści powtórzenia i ponowne sprawdzenie stanu przy cleanupie | Naprawione; sześć scenariuszy przeszło na H2 i PostgreSQL 16 |

## Zmiany zachowania i kompatybilność

- Lokalny frontend na `:18070` nadal używa Gateway na `:18080`. Inne wdrożenia
  HTTP(S) używają jednego originu, także strony produktu i zamówienia.
- `?api=...` nie konfiguruje backendu. Kontrolowane wdrożenie może ustawić
  `MINITECH_API_BASE` i `MINITECH_ALLOWED_API_ORIGINS` przed wczytaniem
  `api-config.js`; obie instrukcje projektu zostały zaktualizowane.
- Pole `deliveryCost` pozostaje w żądaniu dla kompatybilności, lecz cenę
  zamówienia określa wyłącznie wycena serwera. Niedostępna metoda/data daje 400,
  awaria Delivery Service daje 503 bez zapisu zamówienia. Dostawa jest więc
  wymaganą zależnością podczas tworzenia nowego zamówienia.
- Niezgodny `fulfillmentType` i niepusty, nieobsługiwany `deliverySlot` dają 400.
  Parametry metody i daty zapisywane są z odpowiedzi Delivery Service.
- Ponowienie zapisanej próby zamówienia nie przelicza ponownie ceny ani nie
  tworzy drugiej sagi. Pełne porównywanie requestu/zakresu klienta z kluczem
  idempotencji pozostaje zadaniem A15.
- Ponowione żądanie rezerwacji z innymi produktami/ilościami daje 409.
  Cleanup wybiera maksymalnie 100 identyfikatorów, blokuje rezerwację i ponownie
  sprawdza stan przed zwolnieniem. Nie utrzymuje wcześniej odczytanych,
  nieaktualnych encji rezerwacji.
- Pakiet nie wymaga migracji danych ani zmian istniejących sekretów.

## Weryfikacja

Zakończone sprawdzenia:

- 17 testów Order Service: klient HTTP dostaw, serwerowa cena, nieprawidłowa
  realizacja, brak fallbacku, ponowienia, przepełnienie ilości oraz transakcyjny
  zapis zamówienia/sagi z rollbackiem przy błędzie drugiego zapisu.
- 8 testów Inventory Service, w tym 6 scenariuszy rezerwacji na H2:
  osiem równoległych potwierdzeń, zwolnień i pierwszych utworzeń; odmienna treść
  ponowienia, odwrotna kolejność produktów oraz wyścig cleanupu z potwierdzeniem.
- 6 dodatkowych wykonań scenariuszy integracyjnych na PostgreSQL 16 przez
  Testcontainers w WSL: migracje Flyway, walidacja schematu i wszystkie
  scenariusze współbieżności zakończone sukcesem (`-Pintegration verify`).
- 4 testy Node konfiguracji adresu API.
- 32 testy Playwright Chromium z deterministycznym API, w tym logowanie ze
  złośliwym parametrem `api` i sprawdzenie, że hasło nie trafia do obcego originu.
- Składnia JavaScript, kolejność bootstrapu we wszystkich 5 stronach HTML,
  struktura POM i sprawdzenie białych znaków w diffie.

Do testów magazynowych dodano `InventoryConcurrencyPostgresIT`, który uruchamia
te same sześć scenariuszy na PostgreSQL 16 z migracjami Flyway i walidacją
schematu. Wykonanie lokalne zakończyło się `BUILD SUCCESS`, bez błędów i pominięć.
Profil `integration` Inventory Service został włączony do istniejącej
bramki CI `-Pintegration verify`.

Polecenia:

```bash
pnpm run test:unit
./mvnw -pl order-service,inventory-service -am test
pnpm exec playwright test --project=chromium --grep-invert '@full-system' --workers=2
./mvnw -pl inventory-service -am -Pintegration verify
```

Lokalne logi wykonania są ignorowane przez Git:
`tmp/audit-first-package-tests.log`, `tmp/audit-first-package-playwright.log`
i `tmp/audit-first-package-postgres.log`.

## Pakiet 2 — A01/A02: właściciel i uprawnienia

Wdrożono wspólny moduł kontroli dostępu we wszystkich 14 usługach biznesowych.
Customer Service sprawdza JWT i aktywność konta; usługi korzystają z chronionej
introspekcji. Każda usługa ma osobny sekret i ograniczoną listę operacji.
Operator ma oddzielne poświadczenie w SOPS. Gateway nie nadaje uprawnień.

Odczyty zasobów sprawdzają właściciela, także przez bezpośrednie porty. Dotyczy
to zamówień, płatności, faktur/PDF/korekt, dostaw, wishlist, zwrotów, opinii,
rekomendacji i czatu. Zmiany administracyjne, eksporty i DLQ wymagają operatora
lub wskazanej usługi. Klucz idempotencji nie daje dostępu do cudzego zamówienia.

Zakupy i rozmowy gościnne używają osobnych losowych kluczy 256-bitowych;
w bazach zapisuje się ich skróty. Starsze rekordy gościnne bez skrótu pozostają
dostępne operatorowi. PDF pobierany jest z uwierzytelnieniem przez frontend.
Publiczne opinie nie udostępniają identyfikatorów autora/zamówienia ani notatek
moderacji. Tożsamość właściciela faktury i odbiorcę powiadomienia ustala zamówienie.

Control Center uzupełnia brakujące sekrety przy starcie, przekazuje je tylko
odpowiednim procesom/kontenerom i korzysta z uprawnień operatora po stronie
serwera. Odczyty SQL awaryjne wymagają zweryfikowanej sesji operatora; usunięto
obejście autoryzacji przez bezpośrednią zmianę magazynu w SQL.

Szczegóły reguł, konfiguracji, migracji i polecenia weryfikacji:
[A01/A02 — kontrola dostępu](A01_A02_KONTROLA_DOSTEPU.md).

Walidacja: pełny build Maven zakończony sukcesem; 129 testów Javy po końcowym
sprawdzeniu Customer Service, 34 testy Playwright Chromium, testy konfiguracji
Node/Python i izolacji sekretów w Bash. Sprawdzono konfigurację Compose,
szablony Kubernetes oraz zachowanie poświadczeń po szyfrowaniu/odszyfrowaniu SOPS.
Osobny test uruchamia wszystkie 15 usług na tymczasowym PostgreSQL/Redis/Mailpit
i sprawdza uprawnienia oraz prawdziwe zakupy klienta i gościa bez dotykania danych dev.
Scenariusz i testy izolacji sekretów dodano do CI.

Logi: `tmp/audit-access-package.log`, `tmp/audit-access-customer-final.log`,
`tmp/audit-access-playwright.log`, `tmp/audit-access-real-e2e.log`.

Po pakiecie 2 oddzielnych zmian wymagały m.in. A05, A08–A12, ACL Redis,
współbieżność statusów i pozostałe części A21/A22. Późniejsze zmiany A05/A12
opisuje pakiet 4. Ochrona API nie zmienia symulowanej płatności w integrację
z operatorem płatniczym.

## Pakiet 3 — A04: reguły zakupu i punkty odbioru

- [x] Zamknąć serwerową walidację limitu gościa: wartość **produktów** powyżej
  1000,00 zł wymaga zamówienia przypisanego do zweryfikowanego konta. Dokładnie
  1000,00 zł pozostaje dozwolone gościowi. Próg jest liczony przed rabatem i
  dostawą, zgodnie z dotychczasową regułą interfejsu. Cena i ilości pochodzą
  z obliczeń backendu; żądanie jest odrzucane przed zapisaniem zamówienia i rezerwacją.
- [x] Dodać katalog aktywnych punktów w Delivery Service i publiczne
  `GET /deliveries/pickup-locations`. Migracja `V5__create_pickup_locations.sql`
  tworzy katalog z dotychczasowym punktem `WARSAW_STORE` / „MiniTech Store — Warszawa”.
- [x] Wycena sprawdza aktywny punkt; nieznany/nieaktywny punkt lub punkt wskazany
  dla kuriera daje 400. Pusty katalog uniemożliwia odbiór osobisty. Starszy klient
  bez wyboru punktu otrzymuje pierwszy aktywny rekord; dotychczasowa nazwa punktu
  jest obsługiwana jako alias, po czym backend zwraca kod i aktualną nazwę.
- [x] Order Service przekazuje wybór do Delivery Service i zapisuje wyłącznie
  potwierdzoną nazwę punktu. Przeglądarka wybiera kod z katalogu i pokazuje nazwę
  z wyceny; nie zapisuje dowolnego tekstu jako miejsca realizacji.
- [x] Przy braku katalogu frontend wyłącza odbiór osobisty, zachowując kuriera.
  Kod `ACCOUNT_REQUIRED` otwiera logowanie / przełącza zakup na konto, zachowując
  koszyk i wymagając ponownego sprawdzenia zamówienia, bez rozpoczęcia płatności.
- [x] Zamknąć A19 testem `OPTIONS` dla DELETE z nagłówkiem Authorization i
  originem `http://127.0.0.1:18070`; sprawdzić odpowiednie nagłówki CORS.

Weryfikacja zakończona sukcesem:

- **85 testów Java**: platform-security (40), API Gateway (11), Order (26), Delivery (8).
  Obejmuje granicę 1000/1000,01 zł, sumę ilości, konto ponad limitem, brak skutków
  ubocznych po odmowie, kanoniczny punkt, brak aktywnych punktów i preflight.
- **36 testów Playwright Chromium**, w tym obsługa odmowy limitu z backendu,
  podsumowanie odbioru z nazwą serwera i niedostępny katalog punktów.
- **142 kontrole HTTP** na 15 prawdziwych usługach, z osobnym PostgreSQL/Redis/Mailpit:
  brak zamówienia po przekroczeniu limitu przez gościa, konto z zakupem ponad
  limitem, odrzucenie fikcyjnego/nieaktywnego punktu, serwerowa cena odbioru,
  preflight i regresja wcześniejszych zabezpieczeń, płatności i dokumentów.
  Migracja Delivery V5 przeszła na PostgreSQL. Zasoby testowe usunięto.

```bash
./mvnw -pl order-service,delivery-service,api-gateway -am package
pnpm run test:ui
python3 scripts/qa/access-control-e2e.py
```

Logi: `tmp/audit-a04-tests.log`, `tmp/audit-a04-playwright.log`,
`tmp/audit-a04-real-e2e.log`. Działające środowisko dev wymaga przebudowania usług
i wykonania migracji przez standardowy start aplikacji. Testy nie zmieniały danych dev.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A19, A39**. Częściowe zadania,
w tym A06, pozostają nieodznaczone. Zakres A05/A12 opisuje pakiet 4 poniżej.


## Pakiet 4 — A05/A12: płatności i wiarygodność faktur

- [x] Zamknięty zbiór metod `CARD`, `BLIK`, `TRANSFER`, `CASH_ON_DELIVERY`,
  `CASH_AT_PICKUP`; nieznana metoda daje 400 przed skutkami ubocznymi.
- [x] Oddzielny adapter symulacji `PaymentGateway`; autoryzacja poprzedza zmianę
  statusu zamówienia. Adapter otrzymuje kwotę i walutę serwera oraz klucz idempotencji.
- [x] `PAYMENT_PROVIDER=simulation` dozwolone tylko w `dev`/`test`; `prod` blokuje
  symulację także przy połączeniu profili. Domyślnie poza `dev`/`test` dostawca
  jest `disabled`: tworzenie nowej płatności zwraca 503 bez zmian danych.
- [ ] A05: rzeczywisty dostawca, podpisy i zgodność kwoty/waluty potwierdzenia,
  idempotentne przetwarzanie webhooków. Nie wybrano dostawcy; płatności produkcyjne
  pozostają wyłączone. Jest to zakres pozostawiony otwarty, a nie gotowa integracja.
- [x] Billing sam pobiera snapshot zamówienia po uwierzytelnionym połączeniu.
  Wymaga `PAID`/`SHIPPED`/`COMPLETED`, zgodności właściciela, e-maila, PLN,
  wszystkich pozycji, ilości, cen, dostawy, rabatu i sum, bez ukrywania różnic
  zaokrągleniem. Zapisuje zweryfikowane wartości serwera.
- [x] Weryfikacja odbywa się przed krótką transakcją lokalną; 404/409/400/503
  nie tworzą dokumentu. Ponowienia, także równoległe, zwracają ten sam dokument.
- [x] Retry nie zmienia historycznej faktury. Usunięto uzupełnianie brakujących
  pozycji z requestu; historyczne braki wymagają osobnej, kontrolowanej migracji.
- [x] Control Center sprawdza opłacenie zamówienia przez API, nie korzysta przy
  wystawianiu z SQL fallbacku, nie tworzy fikcyjnych pozycji ani kwot i zwraca
  czytelny wynik odmowy Billing Service.

Weryfikacja zakończona sukcesem:

- **77 testów Java**: platform-security (40), Payment (19), Billing (18).
  Obejmuje profil `prod` i `prod,dev`, nieznanego dostawcę/metodę, odmowę zapłaty,
  zgodność kwot i danych właściciela, brak dokumentu po odmowie, PDF oraz
  równoległe ponowienie faktury.
- **8 testów Python**: regresja uprawnień i obsługi wystawiania w Control Center.
- **176 kontroli HTTP** na 15 rzeczywistych usługach oraz dodatkowej instancji
  Payment w profilu `prod`, z odrębnym PostgreSQL/Redis/Mailpit. Sprawdzono brak
  płatności i zmiany zamówienia przy wyłączonym dostawcy, błędne/nieopłacone
  zamówienia, sfałszowane kwoty/pozycje/właściciela, odmowę podczas awarii Order,
  faktury po wysyłce i zakończeniu oraz wspólny dokument z czterech równoległych
  żądań. Regresja zakupu konta/gościa, ról, dokumentów i czatu przeszła.
  Zasoby testowe usunięto; danych środowiska dev nie zmieniano.

```bash
./mvnw -pl payment-service,billing-service -am package
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
python3 scripts/qa/access-control-e2e.py
```

Logi: `tmp/audit-a05-a12-tests.log`, `tmp/audit-a05-a12-real-e2e.log`.
Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A39**.
**A05 pozostaje częściowo otwarte**, podobnie jak A06 i inne częściowe pozycje.
Zakres A22 opisuje pakiet 5 poniżej. Pakiet 4 nie zamyka A08–A11 ani A13.


## Pakiet 5 — A22: klucze MFA i rotacja JWT (24.10.2026)

- [x] Niezależny klucz AES-256 wymagany w każdym profilu; brak fallbacku do JWT.
  Nieprawidłowy/brakujący skonfigurowany klucz nie jest zastępowany nowym.
- [x] Wersjonowane szyfrogramy `v1:kid:…` z uwierzytelnieniem wersji i kid
  przez AES-GCM oraz nowym losowym IV. Rejestr zachowuje poprzednie klucze.
- [x] Przed obsługą HTTP jedna transakcja weryfikuje wszystkie rekordy i migruje
  stare szyfrogramy, pobierając partie po 100 z blokadą. Brak starego klucza lub
  uszkodzenie dowolnego rekordu wycofuje całość, także wcześniejsze partie.
  Włączenie MFA i ostatni zużyty krok TOTP pozostają bez zmian.
- [x] Przy pierwszym przygotowaniu starego skarbca zachowywany jest wynik dawnego
  wyprowadzenia klucza; następuje to przed podmianą materiału JWT. Dalsze rotacje
  JWT nie zmieniają MFA. Znacznik formatu zapobiega odtworzeniu wycofanego klucza
  z JWT; ładowanie profilu usuwa wycofane zmienne z wcześniejszego środowiska.
- [x] `mfa-rotate dev/test` w CLI i panelu tworzy nowy kid/klucz i zachowuje stare.
  Klucze nie są automatycznie usuwane. Zapis SOPS jest etapowany obok skarbca;
  błędy szyfrowania/podmiany nie zgłaszają sukcesu ani nie zastępują skarbca.
- [x] Naprawiono dodatkowy błąd generatora: jawna konwersja OpenSSL do PKCS#8,
  zamiast niezgodnego z Javą PKCS#1. Customer Service sprawdza parę RSA i co
  najmniej 2048 bitów. Poprzednie klucze publiczne nadal weryfikują stare tokeny.
- [x] Compose/Kubernetes i uruchamianie usług przekazują klucze wyłącznie do
  Customer Service. Izolowane testy i CI przekazują własny losowy klucz.
- [x] Dodano [procedurę wdrożenia, rotacji i wycofania](A22_MFA_KLUCZE_ROTACJA.md).
  Aktualizacja formatu i rotacja szyfrowania wymagają zatrzymania wszystkich
  instancji Customer Service; stary klucz wolno wycofać dopiero po migracji
  i zabezpieczeniu odtwarzania starszych backupów.

Weryfikacja zakończona sukcesem:

- **59 testów Java**: platform-security (40), Customer Service (19).
  Obejmują zgodność pary RSA, zachowanie/wycofanie poprzednich tokenów,
  walidację kluczy, integralność szyfrogramów, migrację i rollback po błędzie
  w późniejszej partii ponad 100 rekordów.
- **12 testów Python**: przygotowanie i rotacja MFA, dokładne zachowanie starego
  wyprowadzenia HMAC/RSA/dedykowanego materiału, idempotencja i brak regeneracji
  brakującego klucza skonfigurowanego profilu; regresja Control Center.
- **2 scenariusze Bash**: izolacja sekretów usług oraz rzeczywisty SOPS/age,
  trzy rotacje JWT, rotacja MFA, format PKCS#8, wycofanie starej zmiennej,
  brak sekretów w logach i zachowanie skarbca po symulowanej awarii szyfrowania.
- **16 kontroli HTTP A22** na prawdziwym Customer/Notification Service,
  odrębnym PostgreSQL 16, Redis i Mailpit: logowanie TOTP po migracji starego
  szyfrogramu i rotacji JWT, zachowanie tokenu RSA po kolejnej rotacji, blokada
  startu po zbyt wczesnym usunięciu klucza MFA bez zmiany danych, migracja na nowy
  klucz MFA i udane logowanie, wycofanie starych kluczy po migracji i restart.
- **176 kontroli HTTP regresji** na 15 usługach plus instancji płatności `prod`:
  role, zakupy konta/gościa, dokumenty, równoległe faktury, czat i dostawy.
- Manifesty YAML poprawne; klucze MFA występują tylko w Customer Service.
  `git diff --check` bez błędów.

Logi: `tmp/audit-a22-tests.log`, `tmp/audit-a22-rotation-e2e.log`,
`tmp/audit-a22-regression-e2e.log`. Wszystkie zasoby testowe usunięto.
Nie rotowano rzeczywistych sekretów ani danych środowiska deweloperskiego.
Migracja tych danych nastąpi podczas wdrożenia nowego Customer Service zgodnie
z procedurą. Materiał utracony przed aktualizacją wymaga odtworzenia z backupu.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A22, A39**.
Zakres A23 został wykonany w pakiecie 6 opisanym poniżej.


## Pakiet 6 — A23: sesje i jednorazowość MFA (24.10.2026)

- [x] Blokada klienta w PostgreSQL przed odczytem tokenu i stanu MFA. Wspólna
  kolejność dla logowania, refresh/logout, kodów i konfiguracji MFA oraz resetu
  hasła zapobiega tworzeniu potomnego refresh tokenu po unieważnieniu sesji.
- [x] Rodziny refresh tokenów i wykrywanie replay. Jedno poprawne odświeżenie
  wydaje jednego następcę; reuse unieważnia rodzinę i zapisuje audyt. Transakcja
  zatwierdza unieważnienie mimo 401. Inne rodziny logowania pozostają niezależne.
- [x] TOTP i recovery code są zużywane raz, również przez różne wyzwania na dwóch
  JVM. Stan kodu i utworzenie sesji są w jednej transakcji. Wyzwania WebAuthn
  też używają Redis GETDEL; asercje sprawdzane są pod blokadą konta.
- [x] Termin konfiguracji TOTP w DB oraz jednorazowy stan Redis. Po 10 minutach,
  utracie Redis albo dla starej konfiguracji bez terminu backend daje 410.
  Zły kod nie zużywa ważnej konfiguracji; ponowne potwierdzenie aktywnego TOTP
  nie generuje nowych kodów odzyskiwania.
- [x] Regeneracja recovery codes wymaga świeżego TOTP. Brak/błędne potwierdzenie
  nie zastępuje dotychczasowych kodów, a użytego TOTP nie można wykorzystać
  ponownie do logowania lub innej operacji.
- [x] Frontend czyści wygasły klucz/QR i umożliwia ponowne rozpoczęcie.
  Odrzucony dowód MFA nie wywołuje refresh i ponowienia. Odświeżenia mają wspólną
  obietnicę oraz Web Locks między dokumentami, jeśli przeglądarka je udostępnia.
- [x] Flyway V12, aktualizacja kontraktu API i instrukcji PL/EN oraz CI dla
  scenariusza dwóch instancji. [Procedura A23](A23_SESJE_I_MFA.md) opisuje
  koordynowaną aktualizację wszystkich instancji i ścisłą politykę replay.

Weryfikacja zakończona sukcesem:

- **70 testów Java**: platform-security (40), Customer (30), w tym wyścigi
  refresh/TOTP/recovery, niezależność rodzin, logout starego tokenu, rollback,
  TTL i nieponawialność wyzwania WebAuthn. Kryptografia WebAuthn w tym teście
  używa mocka biblioteki; nie jest testem wszystkich fizycznych authenticatorów.
- **39 testów Playwright Chromium**, w tym wygaśnięcie konfiguracji, brak
  ponawiania złego kodu i serializacja odświeżania w równoległych dokumentach.
- **38 kontroli HTTP A23 na dwóch instancjach Customer Service** ze wspólnym
  PostgreSQL 16 i Redis: wyścigi refresh, TOTP, recovery, regeneracji i
  potwierdzenia konfiguracji; replay, izolacja rodzin, logout i TTL DB/Redis.
- **16 kontroli HTTP regresji A22** z migracją dawnych szyfrogramów,
  restartami oraz rotacją i wycofaniem kluczy.
- **176 kontroli HTTP regresji całej aplikacji** na 15 usługach i instancji
  płatności `prod`: role, zakupy konta/gościa, dokumenty, dostawy i czat.
- **12 testów Python**, składnia JS i `git diff --check` bez błędów.

Logi: `tmp/audit-a23-tests.log`, `tmp/audit-a23-playwright.log`,
`tmp/audit-a23-auth-e2e.log`, `tmp/audit-a23-regression-e2e.log`.
Tymczasowe kontenery i procesy usunięto. Nie zmieniano baz ani rzeczywistych
sekretów dev. Dane tego środowiska otrzymają migrację przy wdrożeniu nowej wersji.

Istniejące access JWT nadal mają dotychczasowy TTL; replay/logout unieważnia
refresh, bez nowej listy unieważnień access JWT. Klienci bez Web Locks muszą
zapewnić serializację odświeżania albo liczyć się z ponownym logowaniem po wyścigu.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A22, A23, A39**.
Zakres A24 został wykonany w pakiecie 7 opisanym poniżej.


## Pakiet 7 — A24: adres klienta i atomowe limitowanie (24.10.2026)

- [x] Domyślnie liczony jest adres połączenia. XFF ma znaczenie wyłącznie dla
  jawnie zaufanych IP/CIDR, sprawdzanych od prawej do pierwszego niezaufanego
  adresu. Brak DNS, normalizacja IPv4/IPv6 i zakaz zaufania `/0`.
- [x] Wyłączone natywne przetwarzanie forwarded headers; sprzeczna konfiguracja
  zatrzymuje start. Łańcuchy mają limit 16 adresów / 2048 znaków, a błędny
  nagłówek od zaufanego proxy daje 400 bez obejścia przez fail-open.
- [x] Jeden wspólny budżet IP dla API i instancji, bez kluczy per surowa ścieżka.
  ID, metoda, alias `/api`/`/api/v1`, kodowanie ścieżki i query nie tworzą nowego
  limitu. OPTIONS i infrastruktura diagnostyczna są wyłączone.
- [x] Skrypt Lua atomowo zwiększa licznik i ustawia/naprawia TTL. Kolejne odmowy
  nie zwiększają nowego licznika powyżej `limit + 1` i nie przesuwają okna.
  Uszkodzona wartość tekstowa jest blokowana na okno z TTL.
- [x] Błąd Redis podlega jawnej polityce 503 albo pojedynczego wykonania.
  Dalszy chain jest poza catch; jego RuntimeException nie powiela operacji.
  Redis ma domyślne timeouty połączenia/polecenia 500 ms.
- [x] CORS jest przed limiterem, z widocznymi nagłówkami limitu i Retry-After
  także przy 429/503. Regresja rzeczywistego preflight DELETE przeszła.
- [x] Compose/Kubernetes mają jawną pustą listę zaufanych proxy. Dodano
  [procedurę konfiguracji i wdrożenia](A24_LIMITOWANIE_ZADAN.md), instrukcje PL/EN
  i test Gateway/Redis do CI. Budżet IP może być współdzielony za NAT/proxy;
  konfigurację trzeba dostosować do rzeczywistej topologii i ruchu.

Weryfikacja zakończona sukcesem:

- **26 testów Java Gateway**: adresy i łańcuchy proxy, różne formy IP,
  błędne ustawienia, TTL/odpowiedź Redis, fail-open/closed, dokładnie jedno
  wywołanie dalszego chain po błędzie, CORS i dotychczasowe proxy/docs.
- **98 kontroli HTTP A24** na trzech spakowanych Gateway, odrębnym Redis
  i backendzie zliczającym skutki: 64 równoległe żądania na dwóch JVM i dokładnie
  10 dopuszczonych; brak obejścia przez XFF/ID/metodę/alias/kodowanie; TTL,
  proxy z wieloma hopami, odrzucanie błędnych nagłówków oraz awaria Redis.
  Fail-closed nie wywołał backendu, a fail-open wykonał jeden POST.
- **176 kontroli HTTP regresji całej aplikacji**: role, zakupy klienta/gościa,
  płatności, faktury równoległe, dostawy, czat i CORS. Limiter w tym oddzielnym
  scenariuszu jest celowo wyłączony; sam limiter sprawdza poprzedni scenariusz.
- **12 testów Python** oraz `git diff --check` bez błędów.

Logi: `tmp/audit-a24-tests.log`, `tmp/audit-a24-rate-e2e.log`,
`tmp/audit-a24-regression-e2e.log`. Tymczasowe procesy i kontenery usunięto.
Nie zmieniano danych ani kluczy Redis środowiska dev. Wdrożenie wymaga
przebudowania/restartu Gateway i świadomej konfiguracji zaufanych proxy.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A22, A23, A24, A39**.
Zakres A25 został wykonany w pakiecie 8 opisanym poniżej.

## Pakiet 8 — A25: sesje i granice Control Center (24.10.2026)

- [x] Kontrola lokalnego połączenia, dokładnego Host/portu, Origin i metadanych
  przeglądarki przed bootstrapem oraz każdą operacją. Domena wskazująca na
  loopback nie jest dozwolonym Host. Wspierane IPv4 i IPv6, brak CORS.
- [x] Osobne losowe tokeny 256-bitowe, po stronie serwera wyłącznie hashe;
  przypisanie do hosta/portu, 30 min bezczynności HTTP, 8 h czasu życia,
  maksymalnie 64 sesje. Restart unieważnia wszystkie sesje.
- [x] Nagłówek sesji wymagany również dla odczytów logów, PTY i dokumentów.
  Publiczne GET bootstrap/ping nadal podlegają kontroli Host/Origin.
  Backendowa weryfikacja operatora dla danych biznesowych pozostaje aktywna.
- [x] Fetch w panelu dodaje nagłówek do GET/POST; PDF i ZIP są pobierane jako
  Blob bez tokenu w URL. Wygaśnięcie czyści token, zatrzymuje polling, blokuje
  wejście terminala i pokazuje komunikat. Odnowienie wymaga odświeżenia strony,
  bez automatycznego powtarzania POST.
- [x] Atomowe limity: 4 aktywne/oczekujące zadania, 100 wpisów historii,
  4 aktywne terminale; domyślnie zadanie i PTY do 6 h, bezczynność PTY 30 min.
  Limity konfigurowalne, przepełnienie daje 429. Retencja logów dyskowych
  pozostaje w A43.
- [x] Timeout i zamknięcie/przeładowanie panelu kończą jego aktywne procesy,
  w POSIX także własne grupy procesów. Zadanie oczekujące nie uruchomi się
  po rozpoczęciu zamykania. Dotyczy to również usług uruchomionych przez
  CC na pierwszym planie (`app-run`); usługi niezależne w tle pozostają poza
  cyklem życia zakończonego zadania.
- [x] Dodano procedurę [A25](A25_CONTROL_CENTER_SESJE.md), instrukcje PL/EN,
  `pnpm run test:cc` oraz testy CC do CI.

Weryfikacja zakończona sukcesem:

- **28 testów Python w WSL**, w tym rzeczywiste HTTP loopback, próby obcego
  Host/Origin, niejednoznaczne nagłówki, limity/wygaśnięcie sesji, rezerwacja
  zadań, timeout i zamknięcie procesów oraz rzeczywiste PTY: pojemność,
  bezczynność, maksymalny czas życia i zamknięcie mimo ignorowania TERM.
- Windows: **26 testów zaliczonych, 2 testy PTY pominięte** zgodnie z platformą.
- **7 testów Chromium** z rzeczywistym frontendem/serwerem CC i izolowanymi
  skutkami biznesowymi: localhost, tokeny w nagłówkach i pamięci, terminal,
  PDF/ZIP, odmowa po wygaśnięciu i odnowienie po reloadzie, domena testowa
  wskazująca na loopback oraz obcy origin localhost.
- Składnia JS i `git diff --check` bez błędów. Testy Java/API z pakietu 7
  pozostają ostatnią weryfikacją backendu; ten pakiet zmienia tylko CC i testy.

Logi: `tmp/audit-a25-python.log`, `tmp/audit-a25-wsl.log`,
`tmp/audit-a25-browser.log`. Testy nie uruchamiały operacji na środowisku dev
ani nie zmieniały sekretów. Wymagany restart procesu CC i odświeżenie kart;
brak migracji baz danych. Zakres dotyczący zdjęć nadal jest odłożony.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A22, A23, A24, A25, A39**.
Zakres A26 został wykonany w pakiecie 9 opisanym poniżej.

## Pakiet 9 — A26: szyfrowane klucze AI (24.10.2026)

- [x] Rozdzielono zwykłe ustawienia i sekrety. `ai-config.json` zawiera tylko
  dostawcę, model, endpoint i flagę kontekstu; klucz z otoczenia nigdy nie jest
  przepisywany do pliku przy zapisie ustawień.
- [x] Klucz wpisany w CC trafia do osobnego dokumentu SOPS/age
  `ai-dev.json.sops` lub `ai-test.json.sops` w istniejącym katalogu sekretów.
  Wpisy są oddzielne dla dostawców; nie zmieniono sekretów usług, JWT ani MFA.
- [x] Stary `apiKey` jest migrowany przy starcie/odczycie/zapisie: szyfrowanie,
  kontrolne odszyfrowanie i porównanie, atomowy zapis szyfrogramu, a następnie
  atomowa konfiguracja bez sekretu. Brak jawnego pliku tymczasowego/kopii `.bak`
  i sekretu w argumentach CLI. Ponowienie po przerwaniu nie gubi klucza.
- [x] Błąd lub konflikt migracji zachowuje stare pliki i zwraca bezpieczny
  komunikat. Publiczna konfiguracja zwraca maskę, źródło i profil klucza.
  Historyczne zewnętrzne kopie zapasowe nie są automatycznie usuwane.
- [x] Formularz: puste pole zachowuje klucz, osobny checkbox usuwa zapisany
  wpis; zamknięcie, poprawny zapis i zmiana dostawcy czyszczą pole. Usunięcie
  wpisu nie usuwa zmiennej środowiskowej procesu.
- [x] Klucz Gemini przeniesiono z URL do nagłówka. Klucze LLM nie są zastępowane
  tokenem operatora i nie przechodzą na przekierowane żądania. Odpowiedź po
  błędzie nie zawiera surowego wyjątku ani wyjścia SOPS.
- [x] Pliki otrzymują 0600 na systemach z prawami POSIX. Testy uprawnień działają
  w natywnym `/tmp`; na montowanym NTFS w WSL uprawnienia kontroluje Windows,
  co opisano w [procedurze A26](A26_KLUCZE_AI.md).
- [x] Dodano rzeczywiste testy SOPS/age do CI (narzędzia wymagane, bez pomijania),
  przypięte pobieranie SOPS 3.13.2 z SHA-256 i aktualizację instrukcji.

Weryfikacja zakończona sukcesem:

- **50 testów Python w WSL**, w tym 6 z rzeczywistym SOPS/age: migracja,
  restart, izolacja profili/dostawców, wymiana/usunięcie klucza, przerwanie
  migracji, błędny klucz age/uszkodzone dane oraz współbieżne zapisy dostawców.
  Pozostałe testy obejmują HTTP, brak kluczy z otoczenia na dysku, weryfikację
  wyniku szyfrowania, sanitację błędów, nagłówki/redirecty i regresję A25.
- Windows: **42 testy zaliczone, 8 pominiętych** (6 wymaga SOPS/age, 2 PTY).
  Jedna wcześniejsza próba miała systemowe przerwanie gniazda loopback;
  ponowienie całego zestawu zakończyło się sukcesem.
- **9 testów Chromium**, w tym zapis modelu z kluczem środowiskowym, brak
  sekretu w odpowiedzi/DOM, błąd zapisu bez klucza age i czyszczenie pola,
  a także 7 dotychczasowych scenariuszy bezpieczeństwa CC.

Logi: `tmp/audit-a26-python.log`, `tmp/audit-a26-wsl.log`,
`tmp/audit-a26-browser.log`. Testy używały własnych sekretów i atrap
dostawców, bez wywołań płatnych API i bez zmiany sekretów dewelopera.
Wymagany restart CC i odświeżenie kart; brak migracji baz. Zdjęcia pozostają
odłożone zgodnie z decyzją użytkownika.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A22, A23, A24, A25, A26, A39**.
Zakres A27 został wykonany w pakiecie 10 opisanym poniżej, bez zmian zdjęć.

## Pakiet 10 — A27: selektywny katalog i zbiorcza wycena (24.10.2026)

- [x] Szczegóły produktu pobierają jedną encję; nie wysyłają całego katalogu
  do Promotion Service. Pełna lista nadal zachowuje dotychczasowy kontrakt API.
- [x] Indeksy i rozmiar katalogu są ustalane raz. Usunięto `indexOf` w pętli
  i zachłanne obliczanie fallbacku; lokalna oferta powstaje tylko przy braku
  zdalnej oferty, w O(1) na produkt, z zachowaniem globalnej pozycji i UTC.
- [x] Cache przechowuje wyłącznie ID i pozycje, bez cen/ofert. Domyślnie 30 s
  i maksymalnie 10 000 produktów. Każdy odczyt sprawdza generację w bazie;
  zmiana ceny/aktywności jest widoczna od razu. Zimny indeks pobiera ID,
  a większe katalogi korzystają z ograniczonego pamięciowo fallbacku SQL.
- [x] Migracja V10 tworzy transakcyjną wersję/licznik katalogu i triggery dla
  INSERT, DELETE, TRUNCATE i zmiany ID. Działa także dla zapisów poza API.
  Instalacja jest chroniona przed równoległym zapisem. Odczyty używają
  REPEATABLE_READ; niezakończony zapis nie publikuje danych w cache.
- [x] Wewnętrzny batch 1–100 ID jest dostępny tylko dla `order-service`.
  Checkout pobiera cały koszyk jednym wywołaniem; waliduje odpowiedź, brakujące
  produkty, aktywność i cenę. Zachowano sumowanie duplikatów, kontrolę
  przepełnienia, uprawnienia, próg konta, wycenę dostawy i bezpieczne ponowienia.
- [x] Compose przekazuje właściwy adres Promotion Service do Product oraz
  ustawienia cache; parametry dodano także do szablonu Kubernetes. Do CI dodano
  test katalogu na osobnym PostgreSQL. Procedura:
  [A27 — odczyty katalogu i wdrożenie](A27_ODCZYTY_KATALOGU.md).

Weryfikacja zakończona sukcesem:

- **89 testów Java**: 41 wspólnego modułu bezpieczeństwa, 18 Product i 30
  Order. Testy H2 dla 1000 produktów potwierdziły ładowanie jednej/wybranych
  encji, dwa zapytania po rozgrzaniu indeksu, zgodność cen, lazy fallback,
  TTL/generacje/limit pamięci i brak publikacji niezatwierdzonych ID.
- **72 kontrole HTTP A27**: dwa spakowane Product JVM oraz rzeczywiste
  Promotion/Order na osobnym PostgreSQL z `pg_stat_statements`; 1105 produktów
  łącznie. Po rozgrzaniu indeksu szczegół pobierał 2 wiersze w 2 zapytaniach;
  koszyki 1/5/50/100 produktów wymagały jednego wywołania Product i jednego
  Promotion oraz 2 zapytań Product DB, zwracających K + 1 wierszy.
  Zweryfikowano ceny, role batch, create/delete/reorder na dwóch replikach,
  rollback/TRUNCATE, świeżą cenę, awarię promocji i blokadę brakującego lub
  nieaktywnego produktu przed rezerwacją. Pozostałe zależności tego scenariusza
  były deterministycznymi atrapami. Zaobserwowane p50 szczegółu: 20,7 ms;
  jest to pomiar lokalnej próbki, nie porównanie z wersją bazową ani SLA.
- **176 kontroli HTTP regresji** na wszystkich 15 rzeczywistych usługach
  oraz dodatkowej instancji płatności: role, zakupy klienta/gościa, dostawa,
  symulacje płatności, faktury, czat i CORS. Testowy limiter jest wyłączony
  w tym osobnym scenariuszu; jego weryfikację opisuje pakiet 7.

Logi: `tmp/audit-a27-tests.log`, `tmp/audit-a27-package.log`,
`tmp/audit-a27-catalog-e2e.log`, `tmp/audit-a27-regression-e2e.log`.
Tymczasowe procesy/kontenery zakończono. Bazy dev nie były modyfikowane.
Wdrożenie: przebudować Product/Order ze wspólnym modułem, najpierw zaktualizować
wszystkie repliki Product (Flyway V10), następnie Order. Zdjęcia pozostają
odłożone; ten pakiet nie zmienia ich plików, formatów, ładowania ani cache.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A22, A23, A24, A25, A26, A27, A39**.
Zakres A28 został wykonany w pakiecie 11 opisanym poniżej, bez zmian zdjęć.

## Pakiet 11 — A28: dostępność katalogu w batchu (24.10.2026)

- [x] Publiczny `GET /inventory/items/batch?productIds=...`, 1–100 dodatnich
  ID, deduplikacja/kolejność żądania, pomijanie nieistniejących rekordów.
  Jedno zapytanie SQL, bez zmian blokad rezerwacji i uprawnień zapisu.
- [x] Produkty renderują się przed magazynem, historią cen i rankingiem.
  Odczyt stanów dotyczy pierwszej grupy do 24 kart oraz kolejnych kart w widoku,
  dodatkowo produktów koszyka, hero i ofert dnia. Brak lawiny N żądań.
- [x] Wspólna kolejka i deduplikacja: maksymalnie 100 ID w żądaniu i dwa
  równoległe batche. Sortowanie po stanie pobiera przefiltrowany zestaw
  w ograniczonych grupach, a potem przestawia istniejące karty.
- [x] Deadline: 8 s dla katalogu, 3,5 s dla stanów/ceny historycznej/rankingu.
  Trzy ceny ofert dnia mają limit dwóch żądań równoległych. Odświeżenie
  anuluje starszą generację, jej kolejkę i żądania; stara odpowiedź nie
  nadpisuje nowszych stanów. Publiczne odczyty nie wysyłają JWT/cookies.
- [x] Błąd/brak rekordu daje nieznaną dostępność, nie fikcyjne zero.
  Koszyk wymusza świeży batch z priorytetem przed kolejką tła, przed dotychczasową
  walidacją i rezerwacją backendu. Awaria batchu nie uruchamia N żądań fallbacku.
  Po udanej płatności stany kupionych produktów są odświeżane w tle, bez
  opóźniania potwierdzenia zakupu.
- [x] Aktualizacja dostępności zmienia teksty/przyciski. Test potwierdza
  zachowanie istniejących elementów zdjęć. Nie zmieniano zdjęć ani ich
  formatów, rozdzielczości, adresów, srcset, ładowania czy polityki cache.
- [x] Zaktualizowano fixture sklepu, testy Node/Playwright i regresję HTTP
  uruchamianą w CI. Instrukcja: [A28 — dostępność katalogu](A28_DOSTEPNOSC_KATALOGU.md).

Weryfikacja zakończona sukcesem:

- **54 testy Java**: 42 wspólnego modułu bezpieczeństwa i 12 Inventory.
  Na 1000 rekordów H2 batch ładował tylko wybrane encje jednym zapytaniem;
  sprawdzono brakujące ID, prawdziwe zero, kolejność, duplikaty, błędne wejście
  i dotychczasowe scenariusze rezerwacji.
- **11 testów Node**: grupy 100 i dwie aktywne operacje dla 501 ID,
  deduplikacja, świeży odczyt koszyka z pierwszeństwem, nieznany stan,
  timeout, anulowanie kolejki i ograniczenie współbieżności cen historycznych,
  a także dotychczasowa kontrola zaufanego adresu API.
- **43 testy Chromium**: pełna regresja sklepu oraz 1000 produktów renderowanych
  przed odpowiedzią magazynu (początkowo do 2 batchy i do 32 różnych ID),
  brak indywidualnych odczytów stanów w katalogu, awaria zależności,
  starsza odpowiedź po refreshu, ograniczona współbieżność sortowania,
  zachowanie elementu zdjęcia, kontrole stanów przy zakupie i odświeżenie po
  płatności bez starych wywołań ani ostrzeżeń o błędzie.
- **192 kontrole HTTP** na 15 rzeczywistych usługach i dodatkowej instancji
  płatności. Nowy batch zweryfikowano przez Gateway i bezpośrednio: zgodność
  z pojedynczym odczytem, brakujące ID, duplikaty, walidacja i odmowa zapisu
  gościa. Przeszły dotychczasowe zakupy, role, dostawy, płatności i faktury.

Logi: `tmp/audit-a28-tests.log`, `tmp/audit-a28-js-tests.log`,
`tmp/audit-a28-browser.log`, `tmp/audit-a28-regression-e2e.log`.
Testowe procesy i kontenery zakończono; nie używano baz dev. Wdrożenie:
Inventory Service ze wspólnym modułem bezpieczeństwa, następnie spójny pakiet
HTML/JS frontendu i odświeżenie kart. Bez migracji bazy i zmian sekretów.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A22, A23, A24, A25, A26, A27, A28, A39**.
Zakres A29 wykonano w pakiecie 12 poniżej, bez zdjęć.

## Pakiet 12 — A29: paginacja list i agregaty SQL (24.10.2026)

- [x] Strony po ID: domyślnie 50, maks. 200; odczyt `size+1`, metadane
  `X-Has-More`, `X-Next-Cursor`, `X-Page-Size`, zachowane tablice JSON i CORS.
  Filtry statusu/tekstu/produktu działają w serwerze. Błędne parametry dają 400,
  również w Customer Service bez przekierowania do chronionej ścieżki błędów.
- [x] Zamówienia pobierają okno ID, potem pozycje tylko wybranych zamówień;
  jedna transakcja REPEATABLE READ, bez paginacji kolekcji w pamięci.
- [x] Płatności, powiadomienia, dead letters, klienci, czat, zwroty, opinie,
  moderacja, faktury/korekty i historie klientów mają ograniczone odczyty.
- [x] `COUNT/AVG` opinii i zbiorcze sumy/liczby korekt faktur liczone w SQL.
  Rekomendacje czytają wyłącznie kupione ID spośród grup do 100 kandydatów,
  z nowego endpointu wewnętrznego dostępnego tylko recommendation-service.
- [x] Sklep: starsze zamówienia, opinie i zakupy do opinii na przycisk;
  sprawdzanie uprawnionych zakupów z filtrami `PAID` i ID produktu.
- [x] CC: Początek/Poprzednia/Następna, wyszukiwanie/status na serwerze,
  opis zakresu KPI/sortowania, osobne strony korekt i dokumentów wysyłki,
  zachowanie danych po błędzie. Magazyn czyta tylko okno i potrzebne ID
  produktów; filtr po dokładnym ID. Próbki AI kont/opinii ograniczone do 100.
- [x] CLI: rozmiar strony i kursor, bez lokalnego przycinania pełnych list;
  kursor na stderr. Migracje indeksów oraz scenariusz dużych tabel w CI.

Weryfikacja zakończona sukcesem:

- Pełne Maven `package`: **220 testów Java**, bez błędów; dodatkowo
  **47 testów w ukierunkowanych przebiegach** po dodaniu testów A29/poprawce
  Customer. Sprawdzono brak hydratacji encji przez agregat i paginację
  zamówień przed pobieraniem kolekcji, granice rozmiaru i precyzję kursora.
- **148 kontroli paginacji HTTP** na rzeczywistych usługach, po 100 tys.
  rekordów w 11 jednorazowych tabelach; strony, filtry, błędne wejście,
  role/właściciel, CORS, moderacja/DLQ, insert pomiędzy stronami,
  pełne agregaty i prywatne DTO. SQL zwracał najwyżej 51 wierszy na
  zapytanie listy o rozmiarze 50; plany głębszych stron korzystały z indeksów.
- **192 kontrole regresji HTTP**: zakupy, dostawy, role, płatności, faktury,
  czat i awaria zależności. Razem **340 kontroli HTTP** w poprawnym przebiegu.
- **46 testów Chromium sklepu** (43 regresji + 3 A29) i **13 CC**, w tym
  nieprecyzyjny w JS zakres ID kursora, błąd następnej strony, agregaty korekt,
  pobieranie historii dopiero po otwarciu oraz niezależne strony dokumentów.
- Python: **53 testy**, 8 zależnych od SOPS pominiętych w natywnym środowisku
  Windows; **11 Node** i izolowany test Bash paginacji zakończone sukcesem.

Logi: `tmp/audit-a29-build.log`, `tmp/audit-a29-new-tests.log`,
`tmp/audit-a29-customer-tests.log`, `tmp/audit-a29-package.log`,
`tmp/audit-a29-http.log`, `tmp/audit-a29-store-browser.log`,
`tmp/audit-a29-store-pagination.log`, `tmp/audit-a29-cc-browser.log`,
`tmp/audit-a29-python.log`, `tmp/audit-a29-node.log`.
Raport SQL/HTTP: `tmp/access-control-e2e-fe84d6bc/`.
Wszystkie kontenery i procesy testowe zakończono; bazy dev nie były używane.

Procedura: [A29 — paginacja list](A29_PAGINACJA_LIST.md). Przebudować usługi
ze wspólną biblioteką; najpierw Order, potem Recommendation. Wdrożenie wymaga
koordynacji Gateway/usług/CC/CLI/frontendu oraz migracji indeksów Order V13,
Payment V7, Notification V6, Chat V3, Returns V2, Review V2 i Billing V5.
Nie uruchamiano wdrożenia na środowisku dev/prod ani zmian sekretów/zdjęć.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A22, A23, A24, A25, A26, A27, A28, A29, A39**.
Zakres A30 wykonano w pakiecie 13 poniżej, bez zdjęć.

## Pakiet 13 — A30: cache PDF i eksport ZIP (24.10.2026)

- [x] Faktury i korekty dzielą cache LRU o budżecie 32 MiB, limicie 512 wpisów
  i TTL 15 minut. Za duży PDF omija cache; kopie bajtów chronią zapisane dane.
  Udostępniono metryki liczby bajtów i wpisów oraz ustawienia środowiskowe.
- [x] Eksport filtruje daty w SQL, ustala maksymalne ID, czyta partie po 25
  faktur i generuje PDF po zamknięciu transakcji partii. Nie wypełnia cache.
- [x] ZIP ma limit do 10000 dokumentów i 2 równoległych eksportów na proces;
  walidacja/413/429 następują przed rozpoczęciem odpowiedzi. Błąd podczas
  generowania nie finalizuje pozornie poprawnego, częściowego ZIP-a.
- [x] Gateway przekazuje ZIP buforem 32 KiB bez odczytu całego archiwum,
  bez retry i przekierowań, z osobnym limitem oraz bulkhead/circuit breaker
  `billing-export`. Rozłączenie/timeout zamyka transfer i zwalnia slot.
- [x] CC: wybór dat i godzin, blokada przycisku w trakcie pobierania, czytelne
  błędy i możliwość ponowienia. Backend panelu nie ponawia eksportu przez
  bezpośredni fallback do Billing. CLI zapisuje plik tymczasowy, sprawdza
  ZIP/CRC i zachowuje poprzedni plik przy błędzie. Dispatcher przekazuje daty
  eksportu i kursory list do funkcji CLI.
- [x] Compose, ConfigMap i przykład env zawierają limity. CI uruchamia nowy
  scenariusz rzeczywistych usług oraz test CLI. Główne pliki YAML mają test
  ścisłego parsowania niezależnie od konfiguracji testowej.

Weryfikacja zakończona sukcesem:

- **103 testy Java**: 46 platform-security, 30 Gateway, 27 Billing, w tym LRU,
  wspólny budżet/TTL, współbieżność, filtry dat i brak zbędnej hydratacji,
  brak zapełniania cache eksportem, błąd PDF, przerwany zapis, rezerwacja/
  zwalnianie slotów, timeout odczytu, brak retry/redirect oraz główny YAML.
- Rzeczywisty Billing/PostgreSQL: **60 PDF-ów z 100000 faktur**, poprawny ZIP
  i CRC, filtry SQL, po **maks. 25 faktur w zapytaniu**, cache do **64 KiB**
  w konfiguracji testowej, wygaszenie TTL i brak zapisu eksportu do cache.
- Rzeczywisty Gateway: archiwum **160 MiB** przez proces z **heap 96 MiB**,
  zmierzony szczyt heap **46258936 B (ok. 44 MiB)**. Pierwszy fragment dotarł
  przed zakończeniem kontrolowanego producenta (0,103 s w tym lokalnym teście),
  dokładnie jedno wywołanie producenta, poprawny końcowy katalog ZIP i CRC.
  Producent dużego ZIP oraz introspekcja są izolowanymi fixture; Gateway,
  Billing i PostgreSQL w scenariuszu są rzeczywiste.
- **192 kontrole regresji HTTP** na 15 usługach i dodatkowej instancji
  płatności: role, właściciel, zakupy, dostawy, dokumenty, czat i awarie.
- **14 testów Chromium CC**, w tym wybór dat, komunikat 413 i ponowienie;
  **54 testy Python** (8 zależnych od SOPS pominiętych w natywnym Windows)
  oraz izolowany test Bash zapisu ZIP/CRC, bez ponowienia i utraty pliku.

Logi: `tmp/audit-a30-tests.log`, `tmp/audit-a30-main-config.log`,
`tmp/audit-a30-document-e2e.log`, `tmp/audit-a30-regression-e2e.log`,
`tmp/audit-a30-cc-browser.log`, `tmp/audit-a30-python.log`.
Raport pamięci i SQL: `tmp/document-export-315901a0/`.
Kontenery/procesy testowe zakończono; nie używano baz dev ani sekretów projektu.

Procedura: [A30 — PDF i eksport](A30_PDF_CACHE_EKSPORT.md). Wdrożyć nowe
Billing, Gateway oraz CC/CLI; brak migracji Flyway, rotacji sekretów i zmian
zdjęć. Nie wdrażano na środowisku dev/prod. Duże archiwa w przeglądarce nadal
korzystają z Blob; CLI zapisuje je na dysk strumieniowo.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A22, A23, A24, A25, A26, A27, A28, A29, A30, A39**.
Zakres A31 wykonano w pakiecie 14 poniżej, bez zdjęć.


## Pakiet 14 — A31: limity HTTP i krótkie transakcje (24.10.2026)

- [x] Wspólna fabryka HTTP obsługuje PATCH, jawne limity połączenia/odczytu,
  całego wywołania i treści odpowiedzi; nie śledzi przekierowań. Powolny strumień
  nie przedłuża deadline'u. Termin jest przenoszony między usługami i wyłącznie
  skraca lokalny budżet. Retry sprawdza pozostały czas przed każdą próbą.
- [x] Product pobiera dane w krótkiej transakcji przed Promotion HTTP.
  Payment/Notification/Saga rozdzielono na wywołania sieciowe oraz krótkie
  transakcje w oddzielnych beanach. Także wstępny odczyt płatności kończy własną
  transakcję, aby pusty kontekst synchronizacji nie zatrzymywał połączenia JPA.
- [x] Token i termin rezerwacji zadania w DB zabezpieczają płatność,
  powiadomienie i sagę przed równoległym przetwarzaniem. Wygasła rezerwacja może
  być przejęta; stary wykonawca nie nadpisuje nowego wyniku. Wynik płatności
  i outbox pozostają atomowe. Nie oznacza to dokładnie jednej wysyłki SMTP.
- [x] Saga ma czterech pracowników/kolejkę 100 i budżet zadania 5 s; retry
  powiadomień czterech/kolejkę 20 i budżet 8 s. Zablokowane zadanie nie zatrzymuje
  całej partii. Przepełniona kolejka zostawia rekord do następnego odczytu DB.
- [x] SMTP ma jawne limity connect/read/write, maksymalnie cztery równoległe
  wysyłki i anulowanie wątku wirtualnego po wyczerpaniu terminu, również gdy
  serwer nieustannie przesyła pojedyncze bajty.
- [x] Dodano migracje Order V14, Payment V8, Notification V7; ustawienia env,
  Compose i ConfigMap oraz izolowany scenariusz A31 w CI.

Weryfikacja:

- **251 testów Java**, pełny build reaktora, w tym realne proxy Spring/JPA i pula
  jednego połączenia dla Product, Payment, Notification i sagi. Sprawdzono
  równoległą próbę, odzyskanie rezerwacji, odrzucenie starego wykonawcy i rollback
  SUCCEEDED przy błędzie outboxu (także po sprzątaniu odłączonego obiektu).
- Rzeczywiste JAR-y Order/Payment/Notification i PostgreSQL, pula **1 połączenia
  na usługę**: szybka saga **0,532 s** przy zablokowanej drugiej; wolna próba
  zapisała retry po **4,533 s**. Zawieszony HTTP płatności zakończony po
  **1,360 s**, pobieranie PDF po **1,343 s**, SMTP przesyłający bajt co 100 ms
  po **1,222 s** przy terminie wejściowym 1,2 s. Inne odczyty DB działały podczas
  oczekiwania; brak sesji `idle in transaction`. Fixture potwierdził zamknięcie
  gniazda SMTP i brak kolejnej próby HTTP po wyczerpaniu terminu.
- **192 kontrole HTTP** na 15 usługach i dodatkowej instancji płatności:
  własność danych, role, checkout, dokumenty, czat i awarie.
- **72 kontrole HTTP katalogu**: selektywny odczyt, aktualne ceny, fallback,
  wersja indeksu między JVM oraz 1 wywołanie Product/Promotion dla checkoutu
  1/5/50/100 produktów. Rozłączenie fixture po deadline dało oczekiwany
  `BrokenPipeError` po stronie serwera testowego; scenariusz zakończył się sukcesem.
- Regresja A30: **60 PDF-ów z 100000 faktur**, partie SQL do 25, TTL i budżet
  cache oraz ZIP **160 MiB** przez Gateway z heap **96 MiB**; zmierzony szczyt
  **47217768 B (ok. 45 MiB)**, pierwszy fragment **0,090 s**, jedno wywołanie
  producenta. ZIP nadal ma osobny transport i limity strumieniowania A30.
- Składnia Compose/ConfigMap oraz przekazanie budżetów do wszystkich 15 JVM
  sprawdzone. Testy nie korzystały z baz developerskich; procesy i jednorazowe
  kontenery zakończono.

Logi: `tmp/audit-a31-final-build.log`, `tmp/audit-a31-boundaries-e2e.log`,
`tmp/audit-a31-regression-e2e.log`, `tmp/audit-a31-catalog-e2e.log`,
`tmp/audit-a31-documents-e2e.log`. Raporty:
`tmp/http-transactions-2b516e8e/`, `tmp/access-control-e2e-f41048d5/`,
`tmp/catalog-e2e-c0b1262b/`, `tmp/document-export-7c408436/`.

Procedura i limity: [A31 — HTTP i transakcje](A31_HTTP_TRANSAKCJE.md).
Przed uruchomieniem nowych Order/Payment/Notification zatrzymać stare instancje
wykonujące zadania: stary kod nie respektuje rezerwacji. Przebudować usługi
ze wspólną biblioteką i wykonać migracje. Nie wdrażano zmian na dev/prod.
Zdjęcia i sekrety pozostają bez zmian. A06/A15/A32 nie są zamknięte tym pakietem.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A22, A23, A24, A25, A26, A27, A28, A29, A30, A31, A39**.
Zakres A32 wykonano w pakiecie 15 poniżej.


## Pakiet 15 — A32: zdarzenia i wiele replik (25.10.2026)

- [x] Payment outbox ma trwały token/termin rezerwacji, krótkie transakcje
  claim/complete, odrzucenie spóźnionego wyniku i przejęcie po awarii procesu.
  Publikacja Redis odbywa się poza DB. Czterech pracowników/kolejka 50 chronią
  scheduler. Stabilny eventId pochodzi z zapisanego eventKey.
- [x] Faktura i korekta zapisują invoice outbox w tej samej transakcji.
  Obowiązkowy enqueuer wymaga aktywnej transakcji; Redis wywołuje osobny,
  replikowalny publisher z rezerwacją i ograniczoną pulą. Włączono scheduler Billing.
- [x] Notification i Billing mają trwałe inboxy ze stabilnym ID/hashem.
  Notification atomowo zapisuje inbox i gotowe zadanie, a ACK następuje dopiero
  po commicie. Duplikat nie zeruje retry ani nie uruchamia ponownie wysyłki.
  Kompatybilność obejmuje stare eventKey i pending poprzednich konsumentów.
- [x] Konsumenci mają nazwy z UUID, tworzą grupy bez bootstrapu, odzyskują pending
  przez XAUTOCLAIM z kursorem i ponawiają ACK po błędzie/restarcie. Błąd SQL nie
  jest potwierdzany. Wadliwy schemat i powtórzenie ID z inną treścią trafiają do
  trwałej kwarantanny SQL przed ACK.
- [x] Przerwana rozpoczęta wysyłka SMTP lub jej niepewny wynik trafiają do
  istniejącej DLQ, bez automatycznego ponownego e-maila. Nierozpoczęte zadanie
  jest automatycznie odzyskiwane. Ręczny replay wymaga rozstrzygnięcia operatora
  i może świadomie powtórzyć wiadomość. To doprecyzowuje odzyskiwanie z A31;
  SMTP nie zapewnia dokładnie jednego dostarczenia.
- [x] Retencja payment-events chroni wszystkie grupy, unread i pending;
  brak wymaganej grupy blokuje usuwanie. Wiek 7 dni, przebieg do 1000 wpisów/min.
  Invoice-events nie jest usuwany automatycznie bez określonych odbiorców.
  Retencja SQL i metadanych konsumentów pozostaje w A43.
- [x] Compose ma AOF/appendfsync always z istniejącym wolumenem. Dodano limity
  Redis, ustawienia env/Compose/ConfigMap, migracje Payment V9, Billing V6/V7,
  Notification V8 oraz test A32 i zachowanie jego logów w CI.

Weryfikacja zakończona sukcesem:

- **258 testów Java**, pełny build wszystkich modułów, bez błędów i pominięć.
  Testy obejmują atomowy rollback faktury/outboxu, obowiązkową transakcję,
  przejęcie rezerwacji/stare tokeny, równoległe inserty inboxu, commit przed ACK,
  brak ACK po błędzie DB, kwarantannę i ręczne rozstrzyganie niepewnej wysyłki.
- **192 kontrole HTTP** na 15 rzeczywistych usługach i dodatkowej instancji
  płatności: checkout, role, właściciel, dokumenty i czat.
- **Sześć rzeczywistych JVM** (po dwie Payment/Billing/Notification), PostgreSQL,
  Redis i Mailpit: 20 podstawowych zdarzeń bez dublowania, duplikaty streamu,
  restart Redis z AOF, awaria wydawcy po XADD/przed commitem, niedostępny broker,
  awaria przed commitem inboxu oraz po commicie/przed ACK. Nowe nazwy konsumentów
  przejęły pending; powtórzenia nie dodały wiadomości.
- Ten sam scenariusz: SMTP przyjął wiadomość przed śmiercią procesu, a nowa
  instancja skierowała zadanie do DLQ bez drugiej wysyłki. Faktura i korekta
  opublikowały **2 zdarzenia** po awarii brokera. Kwarantanna zachowała wadliwy
  payload i konflikt tożsamości przed ACK w obu bazach.
- Końcowo **22 PUBLISHED** w Payment, po **27 wpisów inboxu** w obu bazach
  (25 zaakceptowanych, 2 odrzucone) i **25 fizycznych wiadomości Mailpit**,
  w tym jedna o niepewnym wyniku w DB skierowana do DLQ. Retencja usunęła
  **1000 z 3000** testowych starych wpisów dopiero po zwolnieniu wszystkich
  chronionych granic; brak grupy, unread, pending i dodatkowa grupa blokowały trim.
- Regresja A31 z pulami po jednym połączeniu: szybka saga **0,610 s**, wolna
  zakończyła próbę po **4,747 s**, zawieszony HTTP płatności **1,332 s**, PDF
  **1,346 s**, SMTP **1,305 s** przy terminie 1,2 s. DB pozostawała dostępna.
- Compose, ConfigMap i CI YAML sprawdzone. Użyto wyłącznie jednorazowych baz,
  kontenerów, portów i losowych poświadczeń. Wszystkie zasoby testowe zakończono.

Logi: `tmp/audit-a32-build.log`, `tmp/audit-a32-events-e2e.log`,
`tmp/audit-a32-regression-e2e.log`, `tmp/audit-a32-http-boundaries-e2e.log`.
Raporty: `tmp/event-reliability-7704c260/`, `tmp/access-control-e2e-5bb040e1/`,
`tmp/http-transactions-6a22afb5/`.

Procedura: [A32 — zdarzenia i repliki](A32_ZDARZENIA_REPLIKI.md).
Przed wdrożeniem zatrzymać stare instancje trzech usług, wykonać migracje i
zapewnić trwałość Redis. AOF nie zastępuje backupu ani odporności na utratę dysku;
A09 (skutki płatności), A36 (backupy) i A43 pozostają otwarte. Nie wdrażano zmian na dev/prod ani nie zmieniano zdjęć
lub sekretów. CC zachowuje istniejący widok DLQ; nie dodano widoku inboxu.

Zakończone ustalenia: **A01, A02, A03, A04, A07, A12, A19, A22, A23, A24, A25, A26, A27, A28, A29, A30, A31, A32, A39**.
Pakiet bramek A33 opisano w pakiecie 16 poniżej; pełny odbiór pozostaje częściowy.


## Pakiet 16 — A33: bramki jakości i jawne dowody P1 (25.10.2026)

- [x] Wspólny silnik QA zastąpił płytkie asercje Bash. Kontrakt obejmuje routing
  14 usług, operacje/statusy/body OpenAPI, istotne typy DTO i schematy rzeczywistych
  odpowiedzi. Walidacja opinii odrzuca powrót pól administracyjnych; listy wymagają
  metadanych paginacji. Zależności JSON Schema przypięto w requirements.txt.
- [x] Security sprawdza anonimowe granice, zapis katalogu, niepoprawny JWT,
  pozytywny dostęp operatora, health bez szczegółów i wyciek prywatnych identyfikatorów
  w OpenAPI, także camelCase. Pełne testy właściciela i ról nadal uruchamia realny E2E.
- [x] Load ma egzekwowane p95/p99, udział błędów i próbkowanie active/max/pending
  DB. HTTP 200 z błędnym JSON/schematem jest błędem; brak metryk nie daje sukcesu.
  Raport zachowuje wyniki i limity również po naruszeniu progów.
- [x] Chaos ma realne fazy 200/503/200, sprawdza treść, izolację i czas odzyskania;
  400/500/000 i spóźniony sukces są odrzucane. Działa na własnych JVM/bazach,
  z SIGCONT w finally i sprzątaniem. Usunięto wykrywanie przypadkowego PID/kontenera
  i eval. CC opisuje teraz izolowany scenariusz, wymagający zbudowanych JAR-ów.
- [x] Chat ma pięć testów capability/błędów dostarczania. Gateway ma trzy testy
  rzeczywistych żądań HTTP: retry GET do trzeciej próby, wyczerpanie trzech prób,
  brak powtórzenia POST. A38 oraz pozostałe reguły biznesowe nie zostały zamknięte.
- [x] 23 testy bramek symulują także błędne zachowanie: pominiętą autoryzację,
  błędny kontrakt/status, przeciążenie, nieudane/spóźnione odzyskanie, odpowiedź
  przesyłaną bez końca i błędny kod wyjścia CLI. Sprawdzono rzeczywiste wejścia Bash.
- [x] Mapa dowodów wszystkich 14 P1 jest sprawdzana względem rejestru i wykonywanych
  komend CI; usunięcie testu lub zakomentowanie komendy jest błędem. Produkcyjny job
  sprawdza zamknięcie P1 przed konfiguracją klastra. Raporty nie zawierają tokenów.
  CC nie pobiera automatycznie tokenu profilu dla niestandardowego celu QA.
- [x] CI uruchamia nowe bramki we wspólnym izolowanym środowisku z paginacją,
  instaluje przypięte zależności, zbiera raporty i ma limit verify 60 minut.
- [ ] **Pełny odbiór A33:** wszystkie P1 muszą mieć ukończoną implementację i pełną
  regresję. A05/A06/A08/A09/A10/A11/A21 pozostają niedokończone; nie zamieniono ich
  na pominięte lub pozornie pozytywne testy. A33 zachowuje status częściowy.

Weryfikacja wykonanej części:

- **266 testów Java**, pełny build, bez błędów/pominięć.
- **87 testów Python w WSL**, z wymaganymi testami SOPS, bez błędów/pominięć.
  Obejmują też CC, poświadczenia i spójność mapy dowodów P1.
- **192 kontrole HTTP** całej platformy i **148 kontroli paginacji** na 100000
  rekordów w 11 tabelach, następnie kontrakt/security/load/chaos na tych samych
  rzeczywistych usługach. Nowy kontrakt wykrył brak pozycji w jednym nowo dodanym
  rekordzie fixtury paginacji; poprawiono fixturę, zachowując schemat biznesowy.
- Load: **100/100 HTTP 200**, współbieżność 10, zero błędów, **p95 337,949 ms**,
  **p99 358,609 ms**, 11 próbek zasobów, szczyt kolejki DB 0. Są to lokalne pomiary
  regresji, nie benchmark wydajności produkcyjnej.
- Chaos Inventory: **200 → 503 → 200**, degradacja po **15018,793 ms**, odzyskanie
  **0,111 s**, cztery kontrole niezależnego katalogu. Wcześniejszy przebieg dla
  Payment również zakończył się sukcesem.
- Wszystkie jednorazowe procesy i kontenery zakończono. Nie używano baz dev.

Logi: `tmp/audit-a33-final-build.log`, `tmp/audit-a33-python-tests.log`,
`tmp/audit-a33-final-platform-gates.log`. Raporty bramek:
`tmp/access-control-e2e-57121829/quality-gates/`; plan/paginacja w tym samym katalogu.
Procedura i ograniczenia: [A33 — bramki jakości](A33_BRAMKI_JAKOSCI.md).

Stan P1: **7 zakończonych / 14**, `p1Ready=false`. Kontrola `--release` ma obecnie
zwracać błąd wskazujący siedem pozostałych ustaleń; nie jest to awaria testów.
Nie uruchamiano GitHub Actions ani wdrożenia dev/prod. Brak nowych migracji DB.
Zdjęcia pozostają odłożone. Lista całkowicie zakończonych ID pozostaje bez A33.

Kolejnym priorytetem po pakiecie 16 był A08; jego realizację opisuje pakiet 17.

## Pakiet 17 — A08: współbieżne płatności i odzyskiwanie między replikami (25.10.2026)

- [x] Usunięto lokalny `PaymentRequestCoordinator`. SQL claim/lease chroni także
  wyścig pierwszego zapisu; kolizja klucza między zamówieniami kończy się 409.
- [x] Dodano skrót polecenia, kontrolę kwoty/metody i walidację klucza.
  Wznowienie zachowuje oryginalny klucz dostawcy, kwotę oraz referencję.
- [x] Zatwierdzenie dostawcy jest utrwalane przed zmianą zamówienia;
  gotowe SUCCEEDED/FAILED/PENDING są odtwarzane bez kolejnych skutków.
- [x] Równoległy klient czeka do 5 sekund bez transakcji; po zakończeniu
  dostaje identyczny wynik. Dłuższa praca daje 409/Retry-After, deadline — 504.
- [x] Migracja Payment V10 oznacza historyczne wyniki końcowe; stan PENDING
  bez wyniku pozostaje do odzyskania. Stary właściciel jest odcinany tokenem i TTL.
- [x] Dodano izolowaną regresję dwóch JVM z rzeczywistym PostgreSQL,
  idempotentnym dostawcą testowym i przerwaniem procesów. Test jest w CI.
- [x] Uaktualniono checkout E2E: identyczny POST odtwarza wynik; zmiana
  polecenia po pierwszym zapisie jest odrzucana HTTP 409.

Procedura, migracja, zakres testów i ograniczenia:
[A08 — płatności i repliki](A08_PLATNOSCI_REPLIKI.md).

Odbiór: pełny build 274 Java i końcowy czysty build Payment/platform-security
32+50 (aktualne zestawy łącznie 275), regresja dwóch JVM, 193 kontrole HTTP przez
Gateway, regresja HTTP/transakcji i 7 testów dowodów P1 — wszystkie poprawne.
Raport replik: `tmp/payment-replicas-b577db7d/report.json`. Mapa P1 potwierdza
8/14 i nadal blokuje wydanie produkcyjne. Wszystkie zasoby jednorazowe usunięto.

A08 zamyka protokół aplikacji wobec idempotentnego adaptera. Rzeczywisty dostawca
pozostaje w A05. Trwałe odtwarzanie skutków przy awarii Billing/Delivery/Promotion
pozostaje w A09, pobranie w A10, kompensacje w A11. A33 jest nadal częściowy;
pozostały P1 A05/A06/A09/A10/A11/A21. Nie wdrażano na dev/prod ani nie zmieniano zdjęć.

Następnym priorytetem po pakiecie 17 był A09; jego realizację opisuje pakiet 18.

## Pakiet 18 — A09: trwałe zadania po płatności (25.10.2026)

- [x] Payment V11: niezależne zadania faktury, przesyłki, kuponu i powiadomienia
  zapisywane w tej samej transakcji co SUCCEEDED. Skutki nie zależą od e-maila.
- [x] Ograniczone workery, claim/lease, ponowienia z backoffem, odcinanie starego
  właściciela, DLQ oraz replay wyłącznie dla operatora.
- [x] Zapisany zamiar faktury i serwerowa migawka zamówienia. Powiadomienie z PDF
  czeka na ID faktury; jego outbox i zakończenie zadania są atomowe.
- [x] Sprawdzenie aktualnego statusu przed nową przesyłką/kuponem. Równoległe
  rozliczenia kuponu blokują rekord kodu i zachowują dokładny licznik.
- [x] API operatora z filtrami i paginacją bez odczytu payloadów do listy.
  CC udostępnia listę zadań i ponowienie DLQ; sklep informuje o oczekiwanej fakturze.
- [x] Regresja dwóch Payment JVM i rzeczywistych Billing/Delivery/Promotion:
  wyłączenie trzech zależności podczas płatności, powrót usług i cztery awarie
  procesu po skutku/przed zapisem zakończenia. Nowy test dołączono do CI.

Procedura i granice zakresu: [A09 — trwałe skutki płatności](A09_TRWALE_SKUTKI_PLATNOSCI.md).

Odbiór: pełny build 286 Java i końcowe testy Payment/platform-security 44+50
(aktualne zestawy łącznie 288), 87 Python, testy CLI/JS/Bash/YAML i 195 kontroli
HTTP przez Gateway — poprawne. Regresje A09 i A08 przeszły; A09 obejmuje pięć
płatności przy awarii trzech zależności, 20 trwałych zadań i cztery przerwania
procesu po skutku/przed commitem. Raporty: `tmp/payment-follow-ups-b6af1e19/report.json`
i `tmp/payment-replicas-31ae78a5/report.json`. Mapa potwierdza 9/14 P1.
Wszystkie zasoby jednorazowe usunięto.

Migracja nie odtwarza brakujących danych faktury starych płatności; ich uzgodnienie
wymaga oryginalnych danych kupującego. A05/A06/A10/A11/A21 i pełny odbiór A33
pozostają otwarte. Zdjęcia nadal są odłożone. Nie wdrażano na dev/prod.

Następnym priorytetem po pakiecie 18 był A10; jego realizację opisuje pakiet 19.

## Pakiet 19 — A10: realizacja i rozliczenie pobrania (25.10.2026)

- [x] Pobranie potwierdza właściwą rezerwację Inventory i wiąże Order z płatnością
  przed zgłoszeniem przyjęcia. Potwierdzony zapas nie jest zwalniany po TTL.
- [x] Rozdzielono `paymentState` od statusu realizacji. Kurier i odbiór w punkcie
  mogą być realizowane przed otrzymaniem pieniędzy, z należnością COLLECTION_DUE.
- [x] Dodano operatorowe `POST /payments/{id}/collect`: dokładna kwota i PLN,
  dowód wpłaty, weryfikacja doręczenia/odbioru i unikalność dowodu między płatnościami.
- [x] Rozliczenie i trwałe zadanie ORDER_SETTLEMENT są atomowe. Identyczne
  ponowienia nie powielają zapisu, a zakończona realizacja nie jest cofana do PAID.
- [x] Faktura i potwierdzenie zapłaty czekają na rozliczenie. Billing/Review
  nie traktują wysłanego, nieopłaconego pobrania jako opłaconego zakupu.
- [x] CC rejestruje pobranie; sklep rozróżnia gotówkę u kuriera i w punkcie,
  pokazuje oczekującą należność i tracking przed wpłatą.
- [x] Order V15, Payment V12, testy Java i nowa regresja PostgreSQL w CI.
  Odtworzenie JAR-ów wymusza teraz `.mvn/maven.config`; wykryto i usunięto starą
  bibliotekę uprawnień w artefakcie Delivery.

Procedura, migracje, dowody i ograniczenia:
[A10 — pobranie i realizacja](A10_POBRANIE_I_REALIZACJA.md).

Odbiór: 303 testy Java w czystym buildzie, ponowione końcowe testy Payment
i platform-security (51+52), 87 Python, testy CLI oraz 18 scenariuszy Chromium
— poprawne. Regresja A10 objęła 52 kontrole HTTP i cztery rozliczone pobrania
na dwóch produkcyjnych instancjach Payment bez dostawcy online, w tym awarię
podczas rozliczenia oraz zapas po TTL. Pełna platforma przeszła 197 kontroli HTTP
i bramki kontraktów, bezpieczeństwa, obciążenia i awarii.
Raporty: `tmp/deferred-payments-2d5df732/report.json`
oraz `tmp/access-control-e2e-93752c3c/quality-gates/`.
Sprawdzono zgodność osadzonej biblioteki uprawnień we wszystkich 15 JAR-ach.
Końcowa regresja A08 na dwóch instancjach również przeszła: wyścigi, konflikty
kluczy, przerwania procesu, odcięcie starego właściciela i odtworzenie odpowiedzi.
Raport: `tmp/payment-replicas-035dc048/report.json`. Zasoby jednorazowe usunięto.
Mapa dowodów potwierdza 10/14 P1; bramka wydania nadal wskazuje cztery otwarte P1.

Historyczne PENDING muszą jawnie przejść nowy protokół przyjęcia; brakujących
danych nie odtwarza się z domysłów. Anulowanie przyjętego pobrania pozostaje
zablokowane do jawnych kompensacji A11. A05/A06/A11/A21 oraz pełny odbiór A33
są nadal otwarte. Nie wdrażano na dev/prod ani nie zmieniano zdjęć.

Następnym priorytetem po pakiecie 19 był A11; jego zakres MVP opisuje pakiet 20.

## Pakiet 20 — A11: bezpieczne anulowanie w wariancie MVP (25.10.2026)

- [x] Usunięto zmianę samego statusu PAID → CANCELLED, także dla zakończonej
  lub brakującej sagi. Przyjęte pobrania i rozpoczęta realizacja są chronione.
- [x] Payment V13: trwała blokada zamówienia wspólna dla anulowania i pierwszej
  próby płatności na wszystkich instancjach. PENDING z wygasłym lease oraz
  historyczne FAILED nie stanowią dowodu braku obciążenia.
- [x] Anulowanie nieopłaconego zamówienia: blokada płatności, potwierdzenie
  zwolnienia rezerwacji, dopiero potem CANCELLED. Zagubione ID rezerwacji
  odzyskiwane jest po ID zamówienia; brak odpowiedzi nie jest uznawany za sukces.
- [x] COMPENSATION_PENDING, lease i retry odtwarzają kroki po awarii zależności
  lub procesu. HTTP pozostaje poza transakcjami SQL; odmowa Payment przywraca sagę.
- [x] Błąd po autoryzacji zachowuje referencję dostawcy i umożliwia jawne ponowienie;
  nie jest deklarowany jako odmowa/refundacja i nie zwalnia zapasu z obejścia.
- [x] CC: „Anuluj nieopłacone zamówienie” i CLI `order-cancel`, kontrola ID
  i propagowanie odmowy API. Nowa regresja A11 jest uruchamiana w CI.

Wariant MVP jest wprost dopuszczony w zaleceniu audytu A11. **Pełne zwroty
pieniędzy i odwracanie skutków opłaconych zamówień nie zostały wdrożone**;
wymagają adaptera A05 i osobnego procesu domenowego. Status Returns nie jest
potwierdzeniem refundacji.

Zakres, protokół i kolejność wydania:
[A11 — bezpieczne anulowanie](A11_BEZPIECZNE_ANULOWANIE.md).

Odbiór: czysty build 315 Java; po końcowej ochronie starej rezerwacji dodatkowy
test i ponowiony zestaw Order/platform-security (bieżąca suma 316). Ponadto
87 Python, testy CLI, regresja A11 z trzema wyścigami i awarią po zwolnieniu,
52 kontrole HTTP A10 oraz 203 kontrole całej platformy — poprawne.
Raporty: `tmp/cancellations-f906ed57/report.json`,
`tmp/deferred-payments-68e8f108/report.json` i `tmp/audit-a11-platform-regression.log`.
Wszystkie zasoby jednorazowe usunięto. Sprawdzono zgodność biblioteki uprawnień
w 15 JAR-ach. Mapa dowodów potwierdza 11/14 P1, z zakresem MVP A11.

Następnym priorytetem po pakiecie 20 był A06; jego realizację opisuje pakiet 21.
A05, A06 i A21 pozostają otwarte, A33 nadal jest częściowy.
Zdjęcia pozostają odłożone. Nie wdrażano na dev/prod.

## Pakiet 21 — A06: spójny cykl zamówienia i stare rekordy bez sagi (25.10.2026)

- [x] Atomowy zapis zamówienia/sagi uzupełniono o odbiór przerwania procesu
  przed drugim INSERT-em i po commicie na dwóch instancjach Order.
- [x] Order V16: wersja optymistyczna i znacznik uzgodnienia. API nie potwierdza
  CREATED przed rezerwacją; sprawdzane są stan sagi, token i ważność lease.
- [x] Spóźniony wynik rezerwacji nie nadpisuje dalszego etapu realizacji.
  PAID/CANCELLED są serializowane pod wspólnymi blokadami i protokołem A11.
- [x] Ograniczony skaner wykrywa brakujące sagi bez pełnego odczytu historii.
  Poprawne CREATED wracają do rezerwacji, inne rekordy do jawnej weryfikacji.
- [x] Operator weryfikuje ID, pozycje, status i ważność rezerwacji poza SQL.
  Nie przelicza ceny i nie odtwarza dowodu zapłaty z domysłów.
- [x] CC: stronicowane `order-sagas` i `order-reconcile`. Sklep pokazuje weryfikację;
  Payment, Billing i Review blokują nowe skutki dla nieuzgodnionych rekordów.
- [x] Nowa regresja PostgreSQL A06 i testy granic uzgodnienia dodane do CI.

Procedura i ograniczenia: [A06 — spójność zamówień](A06_SPOJNOSC_ZAMOWIEN.md).
Niejednoznaczne PAID, anulowania i pobrania zachowują stan i wymagają danych
finansowych do rozliczenia. Ten pakiet nie wdraża refundacji ani integracji A05.

Odbiór: 335 Java, końcowe powtórzenie 56 Order + 55 platform-security, 87 Python,
testy CLI i 19 Chromium — poprawne. A06 na rzeczywistym PostgreSQL obejmuje
66 kontroli HTTP, dwie instancje Order, dwie granice awarii zapisu oraz sześć
wyścigów statusów. Pełna platforma przeszła 194 kontrole HTTP oraz wszystkie
cztery bramki jakości. Regresje pobrań A10 i anulowania A11 również przeszły.
Raporty: `tmp/order-lifecycle-7fccb15a/report.json`,
`tmp/access-control-e2e-8ef2403f/quality-gates/`,
`tmp/deferred-payments-5157e612/report.json`, `tmp/cancellations-a3cd7e39/report.json`.
Wszystkie zasoby jednorazowe usunięto. Końcowe pakowanie i kontrola zgodności
15 bibliotek w JAR-ach są poprawne. Mapa dowodów potwierdza 12/14 P1.

A21 ukończono w pakiecie 22. Pozostaje otwarty P1 A05; A33 nadal jest częściowy.
Zdjęcia są odłożone.
Nie wdrażano na dev/prod.

## Pakiet 22 — A21: atomowe przyjęcia i wersjonowane korekty zapasu (25.10.2026)

- [x] Przyjęcie zapasu jest deltą. UUID idempotencji, blokada produktu, zmiana
  stanu i niezmienny wpis rejestru zapisują się w jednej transakcji.
- [x] Korekta wymaga oczekiwanej wersji. Stary PATCH bez wersji i UUID został
  usunięty; rejestr ma ograniczony odczyt stronicowany i odtwarzanie wyniku.
- [x] CC zachowuje ten sam zamiar i UUID po utracie odpowiedzi/odświeżeniu.
  CLI i przygotowanie danych QA również przekazują jawny klucz ponowienia.
- [x] Dodano migrację Inventory V8, kontrakt, dokumentację, testy i odbiór
  awarii na dwóch replikach. Nie zmieniano obsługi zdjęć.

Procedura i ograniczenia: [A21 — przyjęcia magazynowe](A21_PRZYJECIA_MAGAZYNOWE.md).
Odbiór: 346 Java, 93 Python/CLI i 15 Chromium; pełna platforma zaliczyła
196 kontroli HTTP oraz cztery bramki jakości. Natywny test A21 na rzeczywistym
PostgreSQL i dwóch JVM zaliczył 79 kontroli, 12 równoległych żądań z tym samym
UUID, osiem wyścigów przyjęcia z rezerwacją, kolizję UUID między produktami,
rollback po ubiciu procesu przed zapisem rejestru i ponowienie po utracie
odpowiedzi po commicie. Raport: `tmp/inventory-receipts-033ec63c/report.json`.
Zasoby jednorazowe usunięto, a końcowe artefakty aplikacji pozostały zgodne.

Mapa dowodów potwierdza **13/14 P1**; jedynym otwartym P1 jest **A05** —
rzeczywisty operator płatności, webhooki i zwroty. A33 nadal jest częściowy.
Zdjęcia pozostają odłożone. Nie wdrażano na dev/prod.

## Pakiet 23 — A05: Stripe Checkout i ochrona rezerwacji (25.10.2026)

- [x] Dodano sesję hostowanego Checkoutu dla kart, z kwotą z zamówienia,
  identyfikatorem zamówienia, kluczem idempotencji oraz ograniczonym powrotem do sklepu.
- [x] Webhook weryfikuje surowe bajty, podpis HMAC/timestamp, tryb test/live,
  kwotę, walutę i referencje. Nie zapisuje surowych danych webhooka.
- [x] Sesja wygasa po 31 minutach. Aktywna rezerwacja jest atomowo przedłużana
  przed wyjściem do Stripe; potwierdzenie zapasu poprzedza oznaczenie PAID.
- [x] Nieopłacone `checkout.session.expired` zapisuje terminalny wynik i receipt,
  zatrzymuje oczekujące skutki płatności oraz rozpoczyna kompensację A11.
- [x] Dodano instrukcję konfiguracji sandboxu i testy podpisu/limitu rezerwacji.
- [x] Udokumentowano dodanie w A05 zmiennych `STRIPE_SECRET_KEY` oraz
  `STRIPE_WEBHOOK_SECRET` w APKA i rejestrze audytu.
- [ ] **Odłożone — użytkownik ręcznie uzupełnia obie zmienne w `dev.env.sops`
  oraz `test.env.sops`.** Uwzględniać w kolejnych zestawieniach pozostałych prac.
- [x] Trwały proces refundacji i uzgodnienia wdrożono w pakiecie 24 poniżej.
- [ ] Nie wykonano testu z kluczami Stripe i nie wdrażano tej integracji;
  ręczne uzupełnienie obu skarbców pozostaje odłożonym zadaniem użytkownika.

Weryfikacja pakietu: kompilacja Inventory i Payment; 4 ukierunkowane testy
Inventory (reguły wydłużenia i dostęp wewnętrzny), 10 testów Payment
(weryfikacja podpisu i konfiguracja bramki) oraz `node --check frontend/app.js`
— poprawne. Brak testu zewnętrznego Stripe bez testowych sekretów.

Dokumentacja operacyjna: [A05 — Stripe Checkout](A05_STRIPE_CHECKOUT.md).

## Pakiet 24 — A05: pełne zwroty Stripe i odzyskiwanie rozliczeń (25.10.2026)

- [x] Kanoniczny odczyt Stripe, trwały dowód zapłaty i ograniczony worker SQL
  odzyskują operacje bez kolejnego żądania klienta lub ponowienia webhooka.
- [x] Naprawiono przerwanie między `PAID` w Order a końcowym zapisem Payment.
  Sesja ma stałe parametry idempotencji. Metoda nieobsługiwana przez dostawcę
  i niepoprawna kwota są odrzucane przed zapisem próby.
- [x] Payment V15: rejestr pełnych refundacji, jeden zamiar na płatność,
  stały UUID dostawcy, autor i przyczyna, lease, retry i uzgodnienie przez API.
  Niejednoznaczny wynik po 20 h przechodzi do ręcznej weryfikacji bez nowego POST.
- [x] Order V17: trwała blokada finansowa przed zleceniem zwrotu. Nie można jej
  ominąć przez realizację, zwykłe anulowanie lub uzgodnienie historii A06.
- [x] Zapłacony, niezrealizowany Checkout z utraconą rezerwacją jest zwracany,
  Inventory kompensuje zapas raz, a Order anuluje zakup po potwierdzeniu kroków.
- [x] Operator może zlecić pełny zwrot i odczytać osobno wynik finansowy oraz
  wynik domenowy. Odmowa przed ruchem pieniędzy pozostawia zadania zakupu;
  sukces finansowy nie jest kasowany przez późniejszy błąd kompensacji.
- [x] Zwrot operatora zatrzymuje dalszą realizację i wymaga uzgodnienia faktur,
  kuponów oraz pozostałych skutków. Pełne odwracanie tych skutków pozostaje
  zakresem rozszerzenia A11; nie jest deklarowane jako wykonane przez sam refund.
- [x] CC/CLI: status Stripe, uzgodnienie płatności, rejestr zwrotów, pełny zwrot
  i uzgodnienie refundacji. Sklep obsługuje zwrócone płatności i poprawnie
  odtwarza uwierzytelnienie konta po powrocie ze Stripe.
- [x] Webhook refundacji bez lokalnego UUID (np. panel Stripe) weryfikuje dane
  w API i blokuje dalszą realizację, również przed webhookiem zapłaty. Pełny
  sukces finansowy przetrwa awarię Order; częściowy zwrot wymaga uzgodnienia.
  Obsługa obserwuje istniejący zwrot i nie wysyła nowego POST refundacji.
- [x] Migracja dopuszcza CANCELLED w zadaniach Payment; test PostgreSQL
  wykrył brak tego ograniczenia w poprzednim etapie. Dodano regresję A05 do CI.
- [ ] **Użytkownik ręcznie dodaje obie zmienne Stripe do `dev.env.sops`
  oraz `test.env.sops`. Zadanie odłożone na później; nadal wymieniać w statusie.**
- [ ] Skonfigurować endpoint/Stripe CLI i odebrać zewnętrzny sandbox po dodaniu
  sekretów. Próby lokalne na atrapie dostawcy nie zamykają tego odbioru.

Procedura, API, ograniczenia i kolejność wydania:
[A05 — Stripe, refundacje i recovery](A05_STRIPE_CHECKOUT.md).
Nie wdrażano aplikacji na dev/prod ani nie zmieniano rzeczywistych sekretów.
A05 pozostaje częściowe w bramce P1 do ręcznego przygotowania i zewnętrznego odbioru.

**Odbiór pakietu 24:** pełny build platformy — 382 testy Java; końcowa regresja
Payment/uprawnień — 142, a zmienionych usług — 60 (zestawy się pokrywają).
93 testy Python/CC, 17 JavaScript i 7 testów mapy gotowości — poprawne.
Regresja na rzeczywistym PostgreSQL i dwóch instancjach Payment zaliczyła
137 kontroli HTTP, sześć zwrotów w lokalnej atrapie dostawcy, sześć równoległych
zdarzeń, utratę odpowiedzi refundacji, przerwanie procesu po `PAID` i po zwrocie,
stan pending potwierdzany webhookiem, wygaśnięcie oraz automatyczne odzyskanie
bez webhooka. Zewnętrzny zwrot otrzymany przed potwierdzeniem zapłaty zatrzymał
realizację bez kolejnego POST refundacji. Raport: `tmp/stripe-payments-f64758f6/report.json`.
Usunięto zasoby jednorazowe; potwierdzono zgodność wspólnej biblioteki
w 15 JAR-ach. Mapa gotowości nadal pokazuje **13/14 P1**, ponieważ ręczne sekrety
i odbiór zewnętrznego sandboxu A05 nie zostały potwierdzone przez użytkownika.

## Pakiet 25 — A13: skumulowane saldo korekt faktur (25.10.2026)

- [x] Saldo faktury to kwota pierwotna plus wszystkie delty. Kolejne korekty
  100 − 10 − 20 dają 90 i 70 PLN, także w PDF oraz historii Control Center.
- [x] Stały klucz i wersja salda chronią ponowienia po utracie odpowiedzi.
  To samo polecenie zwraca ten sam dokument i nie publikuje kolejnego zdarzenia.
- [x] Blokada faktury w SQL serializuje zapis między replikami. Dwa różne
  polecenia dla tej samej wersji mają jednego zwycięzcę; nieaktualne daje 409.
- [x] Walidacja precyzji kwoty i długości powodu, odmowa ujemnego salda,
  wyliczanie różnic netto/VAT bez narastającego błędu zaokrągleń.
- [x] Billing V8 zachowuje istniejące dokumenty i oznacza ich stary model.
  Nowe saldo uwzględnia historyczne delty bez przepisywania wystawionych kwot.
- [x] CC odczytuje bieżące saldo i zachowuje nierozstrzygnięty zamiar korekty
  po przeładowaniu strony. CLI: `invoice-balance` i rozszerzone `invoice-correct`.
- [x] 98 testów Java, 98 Python/CC, 22 JavaScript oraz testy CLI przeszły.
  Regresja PostgreSQL: 47 kontroli HTTP, dwie instancje Billing, migracja V7→V8,
  kontrakt, sześć równoległych ponowień i wyścig różnych korekt.

Szczegóły, kontrakt, ograniczenia i kolejność wydania:
[A13 — saldo korekt](A13_SALDO_KOREKT.md). Raport integracyjny:
`tmp/invoice-corrections-9cfd2eae/report.json`. Regresję dodano do CI.
Nie wdrażano aplikacji na dev/prod. A13 nie uruchamia zwrotu pieniędzy ani
pełnych kompensacji A11; ręczne sekrety i odbiór zewnętrznego sandboxu A05
pozostają otwarte. Zmiany zdjęć nadal są odłożone na osobne polecenie użytkownika.

## Pakiet 26 — A17: opłacony zakup i synchronizacja dostawy (26.10.2026)

- [x] Historia zakupów i bestsellery używają wspólnego predykatu SQL dla
  PAID/SHIPPED/COMPLETED. Opinie zachowują uprawnienie po wysyłce i zakończeniu.
  Zwroty, uzgodnienia i nierozliczone pobranie pozostają wykluczone.
- [x] Delivery zapisuje status i oczekującą rewizję w jednej transakcji.
  Worker wywołuje Order poza transakcją; lease i retry działają między replikami.
- [x] Order V18 i Delivery V6: tożsamość trackingu, numer rewizji i trwały
  stan uzgodnienia. Stara rewizja ani ponowienie nie cofają realizacji.
  Migracja starszych przesyłek zachowuje kwoty/stany Order i dane przewoźnika.
- [x] Dostawa/odbiór aktualizują realizację, ale nie ustawiają zapłaty pobrania
  i nie zdejmują blokad finansowych. Anulowanie przesyłki wymaga uzgodnienia,
  zamiast automatycznego anulowania opłaconego zakupu.
- [x] Tylko Delivery może przesyłać wewnętrzny postęp dostawy. CC/CLI mają
  operatorskie `delivery-sync` i `delivery-reconcile` oraz kody diagnostyczne.
- [x] Regresja 153 testów usług/uprawnień i 13 końcowych testów modelu
  (zestawy się pokrywają), 98 Python/CC oraz build 15 aktualnych JAR-ów.
- [x] PostgreSQL: 112 kontroli HTTP, dwie repliki Delivery, migracja,
  role, utrata odpowiedzi, stara rewizja i przerwanie po commicie Order.
  Regresja A10: kolejne 52 kontrole HTTP, cztery pobrania i dwie repliki Payment.

Procedura i ograniczenia: [A17 — cykl opłaconego zakupu](A17_CYKL_OPLACONEGO_ZAKUPU.md).
Raporty: `tmp/purchased-lifecycle-04470eae/report.json` oraz
`tmp/deferred-payments-2bc299be/report.json`. Regresję A17 dodano do CI.
Nie wdrażano usług na dev/prod. Ręczne uzupełnienie obu zmiennych Stripe
w `dev.env.sops` i `test.env.sops` oraz odbiór sandboxu A05 pozostają otwarte.
Zakres zdjęć pozostaje odłożony; pełne kompensacje domenowe A11 są odrębnym zadaniem.

## Pakiet 27 — A14: rezerwacje limitu kuponów (26.10.2026)

- [x] Promotion rezerwuje użycie pod blokadą kodu przed przyjęciem zamówienia.
  Dwie repliki nie przyznają ostatniego użycia dwóm zamówieniom, nawet gdy
  oba podglądy kodu były poprawne. Konflikt nie uruchamia płatności.
- [x] Idempotentne wykorzystanie przenosi zajęte użycie do licznika i historii
  w jednej transakcji. Utrata odpowiedzi nie mnoży rezerwacji ani wykorzystań.
- [x] Anulowanie/wygaśnięcie najpierw blokuje płatność, następnie zwalnia kupon
  i zapas. Niepewna płatność utrzymuje limit; wpis RELEASED blokuje spóźniony zapis.
- [x] Order V19 i Promotion V6 zachowują starsze wyceny. Dowód rezerwacji
  pozwala opłacić zaakceptowane zamówienie podczas awarii Promotion; jego
  brak wymaga sprawdzenia przed nową płatnością i ewentualnego uzgodnienia.
- [x] CC/API/CLI: limit i rezerwacje kuponów, podgląd kuponu zamówienia
  oraz kontrolowane uzgodnienie starej wyceny. Zapis wewnętrzny tylko dla Order.
- [x] 236 testów usług/uprawnień, 9 końcowych testów rezerwacji i 153 końcowe
  testy Payment/uprawnień (zestawy się pokrywają), 98 Python/CC i testy CLI.
  Zbudowano 15 aplikacji ze wspólną aktualną biblioteką uprawnień.
- [x] PostgreSQL: 96 kontroli HTTP współbieżności/awarii A14 i 52 kontrole HTTP regresji
  pobrania A10. A09 potwierdza pięć zakupów podczas niedostępności zależności
  oraz cztery przerwania po skutku, bez duplikatów kuponu/przesyłki/faktury.
- [x] Regresje A08 (wyścigi i przerwania dwóch Payment) i A31 (budżety HTTP,
  niezależne sagi i zwalnianie połączeń SQL) zakończone poprawnie.

Szczegóły, konfiguracja, migracje i raporty: [A14 — rezerwacje kuponów](A14_REZERWACJE_KUPONOW.md).
Regresję A14 i jej artefakty dodano do CI. Nie wdrażano usług na dev/prod.
Odłożone A05 wymaga ręcznego uzupełnienia obu zmiennych Stripe w obu skarbcach,
webhooka i zewnętrznego odbioru. Zdjęcia i pełne kompensacje A11 pozostają
w odrębnym zakresie; mapa gotowości P1 nadal pokazuje 13/14.

## Pakiet 28 — A15: zamiar i właściciel ponowienia zamówienia (26.10.2026)

- [x] Wersjonowany skrót koszyka, danych dostawy i żądania faktury oraz zakres
  klienta/hash tokenu gościa zatwierdzają się atomowo z Order i sagą.
- [x] Zmieniona treść daje 409. Klucz nie autoryzuje cudzego zamówienia.
  Walidacja 1–100 znaków poprzedza wycenę/zapis i jest opisana w OpenAPI.
- [x] Równoległy pierwszy INSERT odczytuje zwycięzcę po wycofaniu przegranej
  transakcji; nie tworzy drugiej sagi ani rezerwacji. Zgodne ponowienie używa
  zapisanej wyceny i może dokończyć sagę po awarii procesu/utracie odpowiedzi.
- [x] Order V20 zachowuje historyczne kwoty i klucze bez zgadywania zamiaru.
  Starszy klucz daje kontrolowany konflikt i wymaga sprawdzenia zakupu;
  uprawniony GET oraz operacje po ID pozostają dostępne na swoich warunkach.
- [x] Sklep sprawdza zapis próby w pamięci sesji przed POST, zachowuje klucz
  mimo odświeżonego kosztu dostawy i pokazuje komunikaty/numer zamówienia przy 409.
- [x] 148 Java, 98 Python/CC, 26 JavaScript oraz 12 testów checkoutu Chromium
  zakończonych poprawnie. Uzupełniono atrapę konfiguracji metod płatności.
- [x] PostgreSQL: 44 kontrole HTTP i dwa Order, oba wyścigi, właściciel,
  migracja/OpenAPI, kupon i pobranie, utrata odpowiedzi zapasu i zabicie procesu.
  Regresja A14: kolejne 92 kontrole HTTP — poprawne. Scenariusz A15 i raporty w CI.

Kontrakt, ograniczenia, wydanie i dowody: [A15 — idempotencja zamówień](A15_IDEMPOTENCJA_ZAMOWIEN.md).
Raporty: `tmp/order-idempotency-c3d9caea/report.json` oraz
`tmp/coupon-reservations-7837cad1/report.json`. Zmiany przygotowano lokalnie.
Nie wdrażano usług na dev/prod ani nie wznawiano odłożonego A05 i zakresu zdjęć.
Ręczne dodanie obu zmiennych Stripe do obu skarbców, webhook i zewnętrzny
odbiór sandboxu nadal wymagają osobnego polecenia użytkownika.

## Pakiet 29 — A16.1: bezpieczne zgłoszenia zwrotu i reklamacji (26.10.2026)

- [x] Returns przed zapisem pobiera autorytatywny zakup z Order jako tożsamość
  usługi i potwierdza ID, klienta, e-mail oraz stan płatności/realizacji.
  Dla zwykłego konta klienta wymusza zgodność jego ID i e-maila; awaria Order
  kończy się bezpiecznym błędem, a obcy lub nieopłacony zakup nie tworzy sprawy.
- [x] Klient może wybrać rzeczywiste pozycje zamówienia i ilości. Brak listy
  oznacza cały zakup; pusty wybór, obca pozycja i ilość przekraczająca zakup
  są odrzucane. Jeden niezmienny zamiar na zamówienie/klienta/typ chroni przed
  zmianą treści pod tym samym kluczem biznesowym.
- [x] Zwrot przyjmuje wyłącznie opłacony `COMPLETED`; reklamacja — opłacony
  `PAID`, `SHIPPED` lub `COMPLETED`, bez aktywnej kontroli cyklu zamówienia.
  Formularz sklepu pokazuje tylko dopuszczone przypadki i wysyła wybrane ilości.
- [x] Operator przechodzi `REQUESTED → UNDER_REVIEW → APPROVED | REJECTED`.
  Wersja, UUID operacji i notatka są obowiązkowe. Blokada SQL, zapis wersji
  oraz niezmienny dziennik chronią przed wyścigiem, powtórzeniem po utracie
  odpowiedzi i nieuprawnioną zmianą stanu. Historia jest stronicowana.
- [x] `COMPLETED` jest odrzucane, a `APPROVED` jawnie wymaga uzgodnienia
  domenowego. Nie uruchomiono ani nie udowodniono zwrotu pieniędzy, ruchu
  magazynowego, faktury/korekty ani kuponu. Etap nie deklaruje pełnego A16.
- [x] Migracja Returns V3 zachowuje istniejące stany, treść i notatki.
  Starsze sprawy dostają wyłącznie wpis migawkowy z jawnym nieznanym aktorem;
  nie przypisuje im wcześniejszej weryfikacji ani wymyślonego czasu decyzji.
  Operator może jawnie zweryfikować nieterminalną starą sprawę względem Order.
- [x] 15 testów Returns przeszło. 98 testów Python/CC, 10 testów Chromium,
  regresja CLI i kontrola składni CC przeszły. Regresja na odizolowanym
  PostgreSQL, prawdziwych usługach i dwóch replikach Returns zaliczyła
  150 kontroli HTTP, migrację V2→V3, wyścigi, utratę odpowiedzi i replay;
  potwierdziła 0 zleconych refundacji. Wspólna biblioteka jest zgodna we
  wszystkich 15 artefaktach JAR.

Procedura, payloady, migracja, zachowanie starszych spraw, ograniczenia i kolejność
wydania: [A16.1 — zgłoszenia zwrotu i reklamacji](A16_ZWROTY_REKLAMACJE.md).
Raport regresji: `tmp/returns-workflow-bbb728f9/report.json`. Regresję dodano
do CI. Zmiany przygotowano lokalnie; nie wdrażano ich na dev/prod. A16 pozostaje
częściowe do czasu wdrożenia i odbioru rzeczywistego rozliczania skutków oraz
ustalenia obsługi wielu lub korygowanych zgłoszeń częściowych. Odłożone A05,
zakres zdjęć i pełne kompensacje A11 pozostają niezależne.

## Pakiet 30 — A18: źródłowa historia cen (26.10.2026)

- [x] Product V11 zachowuje rewizje ceny i układu katalogu oraz zdarzenia
  `CREATE`, `PRICE_CHANGE`, `CATALOG_LAYOUT_CHANGE` i `DAILY`. Migawki UTC
  zapisują ceny także przy braku ruchu, a układ zapisuje tylko zmienione oferty;
  baseline nie udaje wcześniejszej historii.
- [x] Promotion korzysta z historii Product przez endpointy wewnętrzne dostępne
  wyłącznie tożsamości usługi. Odczyt promocji nie dopisuje historii.
  `LEGACY_OBSERVATION` zachowuje stare wpisy, ale nie potwierdza kompletności.
- [x] Najniższa cena jest zwracana tylko po pokryciu wszystkich dni żądanego
  okna. `historyComplete`, `observedDays` i jawne `null` zapobiegają fałszywemu
  `0 zł`; widok produktu i Hot Shot pokazują informację o niepełnej historii.
- [x] Compose przekazuje Promotion adres Product; umowy API opisują historię
  oraz wskaźnik. Ograniczenie katalogu migawek jest zgodne z limitem układu.
- [x] Zmiany przygotowano lokalnie na `Audit_0.1`; nie wdrażano usług na dev/prod.
  Bez backfillu pełny wskaźnik 30-dniowy pojawi się po 30 kompletnych datach UTC;
  nieudane/brakujące migawki pozostawiają wskaźnik ukryty.

Szczegóły mechanizmu, migracji, zakresu, konfiguracji oraz wydania:
[A18 — wiarygodna historia cen](A18_HISTORIA_CEN.md). Nie wznawiano odłożonego
A05, zmian zdjęć ani pełnych kompensacji A11. A16 nadal pozostaje częściowe.

## Pakiet 31 — A20: poprawna lista klientów CC i odbiór A18 (26.10.2026)

- [x] CC prezentuje ID, e-mail, imię i nazwisko, miasto i datę rejestracji.
  Usunięto fikcyjne `username`/`balance` i ich sortowanie. Adapter przekazuje
  tylko potrzebne pola z API operatora i nie korzysta z fallbacku SQL.
- [x] Trwały komunikat i ponowienie odróżniają błąd, ładowanie, pusty filtr
  i brak klientów. Awaria nie pozostawia poprzedniej strony jako aktualnego
  wyniku; spóźniona odpowiedź nie zastępuje nowszego wyszukiwania.
- [x] Nieprawidłowe dane API kończą się 502, niedostępność — 503,
  a odmowa dostępu zachowuje swoje znaczenie. Customer dopuszcza wewnętrzny
  dispatcher błędów, co usuwa maskowanie awarii SQL jako 401; bezpośrednie
  anonimowe `/error` jest nadal chronione.
- [x] Uzupełniono brakującą weryfikację A18. Naprawiono również aktualizację
  Hot Shot, która nadpisywała nowy komunikat starym tekstem, typy w kontrakcie
  OpenAPI i atrapę metadanych katalogu w regresji ofert.
- [x] 139 testów Java łącznie w Product, Promotion, Customer i bibliotece
  uprawnień; 102 testy Python/CC; 21 scenariuszy Chromium (17 CC i 4 historii
  cen). Zbudowano aktualne artefakty wymagane przez scenariusze integracyjne.
- [x] Rzeczywiste Customer/Gateway/CC i jednorazowy PostgreSQL: 13 kontroli
  HTTP oraz kontrakt OpenAPI, zasiani klienci, uprawnienia, strony/filtry,
  błąd schematu bez pustego sukcesu, odzyskanie i awaria usługi.
- [x] A18/A27 na rzeczywistych usługach i dwóch Product: 103 kontrole HTTP,
  OpenAPI, migawka bez odczytów, rewizje cen/układu, niezależność od starych
  obserwacji, pełne i niepełne pokrycie oraz awaria źródła. Budżety odczytu
  i checkoutu A27 pozostały zachowane.
- [x] Regresje A18/A27 i A20 są w CI; raporty i logi objęto artefaktami awarii.

Szczegóły A20: [lista klientów CC](A20_LISTA_KLIENTOW_CC.md).
Raporty: `tmp/customer-list-95aa2b3a/report.json` i
`tmp/catalog-e2e-ceb970bc/report.json`. A18 zachowuje ograniczenie
zbierania rzeczywistych dat po uruchomieniu opisane w
[historii cen](A18_HISTORIA_CEN.md). Zmiany są lokalne na `Audit_0.1`.
Nie wdrażano ich na dev/prod. Odłożone A05, zdjęcia oraz otwarte skutki domenowe
A11/A16 pozostają w rejestrze.

## Pakiet 32 — A34.1: spójne szablony i odbiór Kubernetes (26.10.2026)

- [x] Wspólny `deploy/components.json` zasila 16 komponentów, generator,
  listę obrazów, macierz CI i rollout. Porównanie z Maven/Compose i klientami
  HTTP/Redis wykrywa pominięte usługi lub zależności.
- [x] Dodano brakujące chat/returns/billing, ich obrazy, Service, URL i 14
  wiązań DB. Poprawiono położenie `limits` Gateway oraz namespace Ingress.
- [x] Ograniczenia podów: UID/GID 10001, non-root, seccomp, brak podnoszenia
  uprawnień/capabilities, read-only root, ograniczone `/tmp` i brak automatycznego
  tokena Kubernetes. Frontend oraz katalogi JVM są dostosowane do tych ustawień.
- [x] 18 NetworkPolicy obejmuje domyślne blokowanie aplikacji, DNS i graf
  HTTP/DB/Redis. Etykieta komponentu chroni infrastrukturę DB/brokera przed
  przypadkowym objęciem polityką aplikacji. Sekrety są przypisane per usługa;
  inline Secret i pola sekretów w ConfigMap są odrzucane przed zapisem artefaktu.
- [x] Produkcyjny szablon wybiera zaimplementowany SMTP z wymaganym STARTTLS
  i weryfikacją nazwy serwera. Przykład CSI synchronizuje osobny sekret kryptografii
  Customer, a nie niepełny zamiennik wspólnego Secretu.
- [x] Walidacja z CC/CI używa narzędzi przypiętych wersją i SHA256. Wszystkie
  53 zasoby przeszły ścisłe schematy bez pominięć. Jednorazowy Kubernetes przyjął
  53 zasoby i 16 podów w dry-run, odrzucił niepoprawne `limits` i uprawnienia.
- [x] Frontend przeszedł rzeczywisty test health/static/API proxy przy non-root,
  read-only root i bez capabilities. 114 testów Python, 67 uprawnień, 12 regresji
  Notification oraz 2 nowe testy produkcyjnego SMTP przeszły; serwer bez STARTTLS
  nie otrzymał danych logowania ani wiadomości.
- [x] Release CI sprawdza otwarte P1 i docelowe ustawienia, wykonuje server
  dry-run i stosuje ten sam wyrenderowany artefakt. Domyślne placeholdery oraz
  wyłączone płatności blokują wydanie. Kubeconfig fixture nie trafia do artefaktów.
- [ ] Pełne A34: konfiguracja docelowego overlayu, infrastruktury, TLS, sekretów
  i CNI oraz rzeczywisty odbiór płatności i e-maila w środowisku przedprodukcyjnym.
  Nie udowodniono egzekwowania NetworkPolicy przez docelowy CNI.

Zakres, komendy i ograniczenia: [A34 — szablony wdrożeniowe](A34_SZABLONY_WDROZENIOWE.md).
Raport: `tmp/audit-a34-865a160d/report.json`. A34.1 oznaczono jako wykonane;
A34 pozostaje częściowe. Nie wdrażano na dev/prod ani nie wznawiano odłożonego
A05. Ręczne zmienne Stripe w obu skarbcach i zakres samych zdjęć nadal
pozostają odłożone na osobne polecenie użytkownika.

## Pakiet 33 — A35.1: wykrywanie awarii biznesowych (26.10.2026)

- [x] Order, Payment, Billing i Notification mierzą dziewięć trwałych kolejek:
  liczba, wiek najstarszej pozycji, stan i czas ostatniego poprawnego odczytu.
  SQL działa w tle, ma limit czasu i indeksy. Scrape nie odpytuje bazy.
- [x] Retry nie odmładza zaległości; błąd SQL zachowuje ostatni wynik oraz
  sygnalizuje awarię kolektora. Etykiety nie zawierają danych klientów/zamówień.
  Faktura jest monitorowana tylko przy istniejącym żądaniu po skutecznej płatności.
- [x] Dodano 10 reguł alertów, lokalny Alertmanager, dashboard biznesowy Grafany
  oraz osobne instrukcje reakcji. Sprawdzane są również brakujące metryki,
  wyczerpanie puli, błędy HTTP i błędy dostarczania alarmów.
- [x] CC CLI/WWW ma `monitoring-check` i `monitoring-alerts`; start wymaga
  poprawnej konfiguracji, zgodności 15 targetów z inwentarzem i testów PromQL.
  CI egzekwuje testy reguł oraz izolowany test usług i alarmowania.
- [x] 290 testów Java, 114 Python i 12 scenariuszy promtool przeszło. Cztery
  rzeczywiste usługi przeszły migracje na osobnym PostgreSQL i pomiary dziewięciu
  kolejek. Sprawdzono awarię oraz odzyskanie odczytu. Testowy webhook odebrał
  alarm z runbookiem po pełnych 2 min i rozwiązanie po naprawie.
- [ ] Pełne A35: tracing HTTP/Redis, zgodność danych między bazami, Redis pending/lag,
  refund pending, readiness, SLO, docelowy odbiornik i niezależny watchdog.
- [ ] Monitoring Kubernetes: discovery, uprawnienia/TLS i ograniczone NetworkPolicy
  wymagają osobnego overlayu i odbioru. Nie otwierano sieci A34.

Zakres i reakcje: [A35 — alerty biznesowe](A35_ALERTY_BIZNESOWE.md).
Raport: `tmp/a35-monitoring-7bab19fa/report.json`. A35.1 wykonane lokalnie,
A35 częściowe. Domyślny odbiornik jest lokalny, bez wysyłania wiadomości.
Nie wdrażano na dev/prod. A05, ręczne zmienne Stripe w obu skarbcach oraz
zmiany samych zdjęć pozostają odłożone na osobne polecenie użytkownika.

## Pakiet 34 — A36.1: zestawy kopii i izolowane odtwarzanie (26.10.2026)

- [x] Zestaw 14 baz według inwentarza komponentów oraz Redis RDB. Wszystkie
  artefakty i manifest są szyfrowane age. Manifest powstaje na końcu, zawiera
  SHA256, rozmiary i okna czasu; brak sukcesu pg_dump lub szyfrowania blokuje
  kompletność. Nie zapisuje się jawnych dumpów ani RDB na hoście.
- [x] Walidacja przed odtworzeniem odrzuca brakujące/cudze części, zły klucz,
  niezgodny hash/rozmiar, dodatkowe pliki, dowiązania i niebezpieczne ścieżki.
- [x] Odtwarzanie zawsze tworzy nowe kontenery bez sieci, portów hosta,
  bind mountów i istniejących wolumenów. Sprawdza tabele 14 usług oraz streamy,
  grupy i pending Redis. Kontenery i anonimowe wolumeny są sprzątane po próbie.
- [x] Wskazane opłacone zamówienie ma kontrolę płatności/kwoty, rezerwacji,
  powiadomienia, wymaganej faktury i przesyłki. Rozbieżność oznacza potrzebę
  uzgodnienia. Bez ID raport potwierdza wyłącznie odtworzenie techniczne.
- [x] CC CLI/WWW ma `platform-backup`, `platform-restore-drill` i retencję całych
  uwierzytelnionych zestawów. Stara retencja pojedynczych dumpów pomija zestawy.
  Istniejącego harmonogramu nie zmieniono automatycznie.
- [x] 128 testów Python (w tym 14 nowych) oraz izolowany test 14 rzeczywistych
  zestawów migracji przeszły. Po usunięciu źródeł odtworzono znane zamówienie,
  streamy, grupy i pending. Sprawdzono błędny dump, zły klucz, uszkodzoną/brakującą
  część, utratę płatności i schematu. CI egzekwuje próbę bez publikacji kopii/kluczy.
- [ ] Pełne A36: globalny checkpoint lub uzgadnianie różnych punktów czasu,
  automatyczna idempotentna naprawa z rzeczywistymi usługami po utracie bazy/brokera,
  sesje/MFA po cofnięciu danych, off-site oraz docelowy odbiór RPO/RTO i infrastruktury.

Instrukcja: [A36 — odtwarzanie platformy](A36_ODTWARZANIE_PLATFORMY.md).
Raporty: `tmp/audit-a36-ef0c450a/report.json`, `restore-report.json`.
Snapshoty są niezależne i nie są wspólnym checkpointem transakcyjnym.
Fixture SQL służy do kontroli odtworzonego stanu; automatycznego replay przez
usługi nie wykonywano. A36.1 wykonane lokalnie, pełne A36 częściowe.
Nie wdrażano ani nie odtwarzano dev/prod. A05 i same zdjęcia pozostają odłożone.

## Pakiet 35 — A37: klawiatura i kontrast sklepu (26.10.2026)

- [x] Osiem nakładek na stronie głównej i w katalogu zastąpiono natywnymi dialogami.
  Wspólny moduł zapewnia fokus, zamknięty Tab/Shift+Tab, Escape, inertne tło,
  blokadę przewijania i powrót fokusu także po ukryciu wywołującego menu.
- [x] Checkout kieruje fokus na etap i wynik. MFA obsługuje Enter oraz natywną
  walidację kodu. Poprawiono klawiaturę podglądu koszyka i powrót po Escape.
- [x] Przywrócono brakujące przyciski porównania po błędnym przekazywaniu indeksu
  map jako wariantu karty; zachowano dotychczasową klasę/rozmiary kart i zdjęć.
  Przewijana tabela ma nazwany region osiągalny przez Tab.
- [x] Test axe nie wyłącza już kontrastu. Poprawiono akcent, opisy, kategorie,
  oszczędności i ciemne sekcje. Dodano widoczny fokus i reduced-motion CSS.
- [x] 60 testów dostępności (15 × 4 profile), 53 pozostałe testy UI Chromium
  i 26 testów JS przeszło. Zakup gościa wykonano wyłącznie klawiaturą;
  sprawdzono błędy i sukces MFA, formularze konta/faktury oraz pięć stron sklepu.
  Obejrzano zrzuty desktop, 320 px i CSS zoom 200%.
- [x] CI instaluje Chromium/Firefox/WebKit i sprawdza dostępność także w profilu
  mobilnym. Testy mogą używać osobnego portu i katalogu raportu.

Zakres: [A37 — dostępność sklepu](A37_DOSTEPNOSC_SKLEPU.md).
Raport i logi: `tmp/audit-a37-1977a83a/report.json`. A37 oznaczono jako wykonane
w opisanym kryterium klawiatury/kontrastu. Fizyczny czytnik ekranu, sprzętowy
passkey i natywny zoom przeglądarki nie były odbierane; nie jest to certyfikacja WCAG.
Nie wdrażano na dev/prod. A05 i zmiany samych zdjęć pozostają odłożone.

## Pakiet 36 — A38: historia rozmowy i trwałe powiadomienia (26.10.2026)

- [x] Niezmienne wpisy klienta i obsługi, własne ID i atomowy zapis z zadaniem
  powiadomienia. Kolejna odpowiedź nie zastępuje historii. Migracja zachowuje
  pierwszą wiadomość oraz ostatnią odpowiedź dostępną w starszym modelu.
- [x] Historia z kursorem i kontrolą tokenu rozmowy, dalsze pytania klienta,
  operatorowe odpowiedzi i ponowienia. Stały klucz chroni dalszą wiadomość
  przed duplikatem również między replikami; zmiana treści z tym kluczem daje 409.
- [x] Outbox per wpis, krótkie transakcje, HTTP poza bazą, rezerwacje z tokenem,
  retry po restarcie i kontrolowany DEAD_LETTER. Odrębne typy nowych wpisów
  zachowują zgodność ze starszymi kluczami Notification.
- [x] Utrata potwierdzenia nie wymusza ponownej wysyłki e-maila. Przyjęty FAILED
  pozostaje w obsłudze Notification; ogólny błąd transportu podlega retry Chat.
  Treść do 4000 znaków z podpisem mieści się w rozszerzonym polu Notification.
- [x] Sklep i CC pokazują obie odpowiedzi, pobierają starsze wpisy i zachowują
  ostatni widok przy błędzie. Sklep zatrzymuje polling po zamknięciu/ukryciu;
  CC zachowuje osobne szkice rozmów i klucz ponowienia. Dodano polecenia historii i retry,
  metryki obu kolejek oraz reguły monitoringu.
- [x] 95 testów Java, 129 Python, 26 JS, 18 CC i 73 testy regresji Chromium.
  Łącznie 28 przypadków czatu obejmuje cztery profile przeglądarek i ekran 320 px.
  Test rzeczywistych usług potwierdził migrację, rollback, uprawnienia,
  wyścig replik, restart, utratę potwierdzenia i kontrolowane ponowienie.
  CI wymaga testu integracyjnego A38; kontrola reguł monitoringu przeszła.

Instrukcja: [A38 — historia czatu](A38_HISTORIA_CZATU.md).
Raport: `tmp/audit-a38-2b32be83/report.json` — PASS, 21 kontroli HTTP.
Nie odtworzono wcześniej nadpisanych odpowiedzi. ACCEPTED nie oznacza doręczenia
poczty; pierwsze utworzenie rozmowy nie ma gwarancji idempotencji.
Nie wdrażano na dev/prod. A05 i zmiany samych zdjęć pozostają odłożone.

## Pakiet 37 — A42.1: wspólny parent Maven (26.10.2026)

- [x] Root POM jest parentem i agregatorem 17 modułów. Wspólne wersje Java,
  Tomcat, Netty, PostgreSQL, Springdoc, Resilience4j i Testcontainers są wskazane
  raz; moduły dziedziczą group/version, dependencyManagement i wersję Failsafe.
- [x] Parent nie narzuca zależności aplikacji ani pakowania biblioteki jako Boot
  JAR. Zachowano lokalne zależności, kompilację Lombok i profile integracyjne.
- [x] CC i CI instalują bibliotekę razem z parentem (`-am`). Potwierdzono
  rzeczywistą instalację i samodzielne zbudowanie Chat oraz przerwanie
  uruchomienia CC przy nieudanej instalacji (test ze stubami, bez sekretów).
- [x] 472 testy Java i 137 Python przeszły. Pełny reaktor zbudował wszystkie
  moduły. Efektywne zależności/pluginy 17 modułów oraz skład bibliotek 15
  wykonywalnych JAR-ów są zgodne ze stanem sprzed zmiany. Jedna właściwość
  PostgreSQL steruje wersją we wszystkich 14 usługach bazodanowych.
- [x] Nowa kontrola CI i osiem testów mutacji wykrywają obejście parenta,
  lokalne wersje, pominięty moduł i przypadkowe dziedziczenie zależności/pluginów.
- [ ] Pełne A42: pozostałe duplikacje infrastruktury oraz podział dużych plików
  sklepu i CC na testowalne moduły wymagają dalszych pakietów.

Instrukcja: [A42.1 — wspólny parent Maven](A42_WSPOLNY_PARENT_MAVEN.md).
Raport: `tmp/a42-parent-evidence.json`. Profile Testcontainers sprawdzono jako
efektywne modele; nie uruchamiano ponownie całego profilu integracyjnego ani
obrazów Docker. Nie zmieniano funkcji biznesowych ani baz użytkownika.
Nie wdrażano na dev/prod. A05, całe A41 oraz zmiany samych zdjęć pozostają odłożone.

## Pakiet 38 — A42.2: wspólna korelacja żądań (26.10.2026)

- [x] W bibliotece infrastrukturalnej dodano wspólny kontekst, filtr i interceptor.
  Usunięto 12 lokalnych kontekstów i pięć interceptorów. Logika filtrów jest wspólna;
  12 adapterów zachowuje dotychczasową rejestrację i kolejność.
- [x] Zachowano nagłówek `X-Correlation-Id`, klucz MDC `correlationId` i format
  bezpiecznego ID do 100 znaków. Recommendation nie przyjmuje już dowolnego
  niepustego tekstu. Niewłaściwy nagłówek zastępuje UUID, bez zmiany uprawnień.
- [x] Customer zachowuje metadane IP/User-Agent audytu. Wspólne testy potwierdzają
  przekazywanie ID w HTTP i usuwanie MDC po sukcesie oraz błędzie.
- [x] Czysta przebudowa wszystkich modułów: 492 testy Java i 141 Python przeszły.
  Wszystkie 15 wykonywalnych JAR-ów ma wspólną bibliotekę i nie zawiera starych
  lokalnych klas. CI egzekwuje kontrolę źródeł, artefaktów i próbę HTTP.
- [x] Dwie rzeczywiste JVM Gateway/Recommendation, własne PostgreSQL/Redis
  i fixture HTTP: 17 kontroli, w tym osiem równoległych żądań, błędny nagłówek,
  propagacja historii klienta, błąd zależności i brak dostępu bez uwierzytelnienia.
- [ ] Pełne A42: pozostałe duplikacje wykonawców/konfiguracji HTTP oraz podział
  dużych plików sklepu i CC wymagają kolejnych pakietów. Pełne A35 nadal otwarte.

Instrukcja: [A42.2 — wspólna korelacja](A42_WSPOLNA_KORELACJA.md).
Raport: `tmp/audit-a42-2-aacccaa9/report.json` — PASS. Logi testów:
`tmp/a42-2-build.log`, `tmp/a42-2-python.log`. Test miał własne procesy,
kontenery, wolumeny i wygenerowane dane; nie używał profili ani skarbców użytkownika.
Nie wdrażano na dev/prod. A05, całe A41 oraz zmiany samych zdjęć pozostają odłożone.

## Pakiet 39 — A42.3: wspólna odporność wywołań HTTP (26.10.2026)

- [x] Wspólny wykonawca i konfiguracja w bibliotece infrastrukturalnej zastąpiły
  cztery kopie Gateway/Order/Payment/Customer. Małe adaptery zachowują nazwy
  i rejestrację beanów; ustawienia, wartości domyślne oraz trzy bindery metryk
  mają wspólne źródło. Rejestry pozostają lokalne dla każdej aplikacji.
- [x] Zachowano kolejność limit równoległości/circuit breaker/retry/operacja,
  klasyfikację 4xx/5xx i zakaz retry zapisu w Gateway. Customer również sprawdza
  budżet przed próbą i przestaje ponawiać po jego wygaśnięciu.
- [x] Zależności Resilience4j w bibliotece są opcjonalne. Zestaw bibliotek
  wszystkich 15 wykonywalnych JAR-ów jest zgodny z poprzednim pakietem;
  11 aplikacji nadal nie ma dodatkowych bibliotek Resilience4j.
- [x] Czysta przebudowa: 511 testów Java, bez błędów i pominięć. Regresja Python:
  145 testów. W tym pakiecie dodano 19 przypadków Java i cztery testy kontroli
  źródeł/zależności. CI wymaga kontroli adapterów i gotowych JAR-ów.
- [x] Regresja trzech JVM Order/Payment/Notification z jednorazową bazą i pulami
  po jednym połączeniu potwierdziła niezależną sagę, dostępność DB oraz budżety
  Payment/PDF/SMTP. Regresja Gateway/Recommendation: 17 kontroli HTTP i osiem
  równoległych żądań — PASS.
- [ ] Pełne A42: dalszy podział dużych plików sklepu/CC i pozostałych powielonych
  mechanizmów pozostaje do kolejnych pakietów. Pełnego A42 nie zamknięto.

Instrukcja: [A42.3 — wspólna odporność HTTP](A42_WSPOLNA_ODPORNOSC_HTTP.md).
Raporty: `tmp/a42-3-packaging-report.json`,
`tmp/http-transactions-b54c8a1e/report.json`, `tmp/audit-a42-2-a13c520d/report.json`.
Testy korzystały z własnych procesów, kontenerów, portów i danych; nie używały
profili ani skarbców użytkownika. Nie wdrażano na dev/prod.
A05, całe A41 oraz zmiany samych zdjęć pozostają odłożone.

## Pakiet 40 — A42.4: moduł pamięci koszyka (26.10.2026)

- [x] Wydzielono moduł ES `cart-storage.js`: klucze, migrację, zakres klienta,
  parsowanie tablic JSON i obsługę błędów storage. Sklep i szczegóły produktu
  korzystają z tej samej implementacji, zachowując lokalny stan i renderowanie.
- [x] Zachowano schemat `2`, dane klientów oraz brak łączenia koszyków przy
  logowaniu. Szczegóły produktu również usuwają stary klucz bez właściciela.
  Nowy moduł nie wykonuje HTTP ani nie zarządza tokenami/sesją.
- [x] Trzy strony używają `type="module"`. Zachowany komponent potwierdzenia
  ma jawny eksport; test dostępności nie zależy od funkcji globalnej `window`.
  Błąd zapisu preferencji porównania nie przerywa już renderu katalogu.
- [x] 35 testów JS, w tym dziewięć nowych testów rzeczywistego modułu ES,
  oraz 76 testów Chromium przeszło. 105 testów Firefox/WebKit/mobilnego Chrome
  potwierdziło koszyk, sesję, dostępność, czat i smoke. Nowe scenariusze koszyka
  wykonano w czterech profilach. Rzeczywisty serwer Python zwraca poprawny MIME.
- [x] CI obejmuje nowe testy JS i koszyka. Zmieniono wersje URL wyłącznie kodu.
- [ ] Pełne A42: dalszy podział dużych plików sklepu/CC i pozostałe duplikacje
  nadal wymagają kolejnych pakietów.

Instrukcja: [A42.4 — moduł pamięci koszyka](A42_MODUL_PAMIECI_KOSZYKA.md).
Raport: `tmp/audit-a42-4-de506c35/report.json` — PASS. Testy miały własne dane
przeglądarki i fixture API; nie zmieniano backendu ani nie powtarzano testów Maven.
Nie wdrażano na dev/prod. A05, całe A41 oraz zmiany samych zdjęć pozostają odłożone.

## Pakiet 41 — A42.5: moduł API i sesji Control Center (26.10.2026)

- [x] Wydzielono `panel-client.js`: klient JSON/Blob bez DOM i storage, token
  w prywatnym domknięciu; konfiguracja bootstrapu zwracana do UI bez tokenu.
- [x] Zachowano same-origin `/api/`, nagłówek sesji, brak cookies, zakaz
  przekierowań i blokadę prywatnych wywołań przed bootstrapem. Opcje wywołującego
  nie zastępują tokenu ani ograniczeń transportu. Serwer i autoryzacja bez zmian.
- [x] Spóźnione 401 starego tokenu nie usuwa nowej sesji. Bootstrap oraz body
  rozpoczęte przed wygaśnięciem nie przywracają dostępu ani pobierania plików.
  Zachowano metadane błędów i brak automatycznego ponawiania zapisów.
- [x] CC ładuje skrypt wejściowy jako moduł ES. UI nadal obsługuje renderowanie,
  polling i linki pobierania; callback sesji zatrzymuje polling i wejście terminala.
- [x] 52 testy JS (17 nowych) oraz 20 testów CC w Chromium (dwa nowe) — PASS.
  Potwierdzono brak pobrania późnego ZIP po wygaśnięciu i brak wyjścia na adres
  przekierowania. CI obejmuje nowe przypadki. Składnia i diff — PASS.
- [ ] Pełne A42: dalszy podział dużych plików sklepu/CC i pozostałe duplikacje
  wymagają kolejnych pakietów; tego zadania nie zamknięto w całości.

Instrukcja: [A42.5 — moduł API Control Center](A42_MODUL_API_CONTROL_CENTER.md).
Raport: `tmp/audit-a42-5-5db4af13/report.json` — PASS. Testy miały własny serwer
fixture i dane przeglądarki. Nie wdrażano na dev/prod ani nie zmieniano backendu.
A05, całe A41 oraz zmiany samych zdjęć pozostają odłożone.

## Pakiet 42 — A42.6: moduł tłumaczeń Control Center (26.10.2026)

- [x] Wydzielono `panel-i18n.js`: słowniki PL/EN, nakładanie etykiet na DOM
  i historyczne nazwy zadań. Język, profil, dokument, widok i metadane akcji
  są przekazywane jawnie. Import nie wymaga DOM, HTTP ani pamięci przeglądarki.
- [x] `app.js` ma 3265 linii, około tysiąca mniej. Stan, zapis języka,
  transport sesji, polling i dynamiczne renderowanie pozostają w aplikacji.
  Zachowano pierwszeństwo metadanych akcji i nieznane tytuły zadań operatora.
- [x] Uzupełniono pięć polskich etykiet historii/logów i naprawiono nazwy
  trzech kluczy nagłówka „O aplikacji”. Usunięto cztery nieużywane angielskie
  etykiety. Oba języki mają po 235 zgodnych, niepustych kluczy; CI sprawdza zgodność.
- [x] 55 testów JS (trzy nowe) i 22 testy CC w Chromium (dwa nowe) — PASS.
  Odbiór PL/EN w 12 widokach, teksty klientów i zadań, reload, zachowany nagłówek
  sesji oraz działający terminal ze szkicem po błędzie zapisu języka.
  Kontrola składni i diff — PASS.
- [ ] Pełne A42: dalszy podział dużych plików i pozostałe duplikacje nadal
  wymagają kolejnych pakietów. Nie deklaruje się pełnego tłumaczenia wszystkich
  tekstów wpisanych w pozostałym kodzie ani poprawy wydajności na podstawie liczby linii.

Instrukcja: [A42.6 — moduł tłumaczeń CC](A42_MODUL_TLUMACZEN_CONTROL_CENTER.md).
Raport: `tmp/audit-a42-6-c812f942/report.json` — PASS. Testy korzystały z własnego
serwera fixture i testowych odpowiedzi zapisu języka; nie zmieniały preferencji
użytkownika. Backend bez zmian. Nie wdrażano na dev/prod.
A05, całe A41 oraz zmiany samych zdjęć pozostają odłożone.

## Pakiet 43 — A42.7: moduł list Control Center (26.10.2026)

- [x] Wydzielono `panel-lists.js`: prywatne kursory i generacje list, debounce
  filtrów, obsługę odczytów i przyciski stronicowania. Widoki zachowują własne
  dane i operacje zapisu; sesję nadal obsługuje wspólny klient CC.
- [x] Nowy odczyt lub filtr anuluje poprzedni odczyt tej samej listy. Generacja
  odrzuca także spóźnione wyniki funkcji ignorującej sygnał. Niezależne listy,
  historia korekt faktur oraz oddzielne panele mają osobne stany.
- [x] Błąd następnej strony zachowuje wyświetlany kursor. Nowy filtr usuwa
  migawkę starego zapytania, więc jego nieudane pobranie i ponowienie nie
  przywracają starej dalszej strony. Ponowienie filtra zaczyna od `0`.
- [x] Przyciski są blokowane podczas debounce/odczytu; kontener ma `aria-busy`.
  Wygaśnięcie sesji anuluje listy i zaplanowane odczyty. Zmiana PL/EN odświeża
  również istniejące kontrolki bez dodatkowego HTTP.
- [x] 63 testy JS (osiem nowych) i 25 testów CC w Chromium (trzy nowe) — PASS.
  Potwierdzono rzeczywiste anulowanie żądań, tekstowe kursory, ponowienia po
  błędach i brak przyjęcia późnych wyników po wygaśnięciu. Składnia i diff — PASS.
- [ ] Pełne A42: dalszy podział dużych plików sklepu/CC i pozostałe duplikacje
  nadal wymagają kolejnych pakietów.

Instrukcja: [A42.7 — moduł list CC](A42_MODUL_LIST_CONTROL_CENTER.md).
Raport: `tmp/audit-a42-7-7d908ca1/report.json` — PASS. Testy miały własny serwer
fixture i kontrolowane opóźnienia/odpowiedzi. Backend i polecenia zapisu bez zmian.
Nie wdrażano na dev/prod. A05, całe A41 i zmiany samych zdjęć pozostają odłożone.

## Pakiet 44 — A42.8: wspólny klient HTTP sklepu (26.10.2026)

- [x] Wydzielono `store-client.js`: JSON, puste/tekstowe odpowiedzi, błędy,
  nagłówki, stronicowanie i ponowienie po 401. Sklep/katalog, produkt i zamówienie
  korzystają z jednej implementacji. Moduł nie zależy od DOM ani storage.
- [x] Zachowano źródło adresu API, reguły odświeżania stron, anonimowość,
  capability gościa, body i klucze poleceń. Wymuszono brak przekierowań API;
  nagłówki wywołującego nie podmieniają bieżącego tokenu. Bez zmian serwerów.
- [x] Równoległe 401 współdzielą refresh; stary token może wykorzystać już
  odnowioną sesję. Najwyżej jedno ponowienie; bez retry błędnego MFA, sieci,
  403/409/429/5xx. Sygnał deadline przechodzi do próby po odświeżeniu.
- [x] Szczegóły zamówienia ładują skrypt jako moduł ES. Zaktualizowano wersje
  URL kodu; Dockerfile już kopiuje cały frontend. Bez bundlera i nowych zależności.
- [x] 77 testów JS (14 nowych) oraz 80 testów Chromium — PASS. Pozostałe
  78 scenariuszy Firefox/WebKit/mobile i 16 prób API w czterech profilach — PASS.
  Początkowy błąd mockowania redirectu w WebKit usunięto, zastępując go własnym
  serwerem HTTP; próby API powtórzono we wszystkich profilach. Składnia i diff — PASS.
- [x] CI obejmuje nowe testy JS, Chromium i API w pozostałych profilach.
- [ ] Pełne A42: dalszy podział dużych plików, transport PDF i czatu oraz
  pozostałe duplikacje wymagają kolejnych pakietów.

Instrukcja: [A42.8 — wspólny klient HTTP sklepu](A42_WSPOLNY_KLIENT_HTTP_SKLEPU.md).
Raport: `tmp/audit-a42-8-26a9f17c/report.json` — PASS. Testy używały własnych
sesji, odpowiedzi fixture i portów; nie korzystały z kluczy ani danych użytkownika.
Bez zmian backendu, migracji, testów Maven i wdrożenia na dev/prod.
A05, całe A41 oraz zmiany samych zdjęć pozostają odłożone.

## Pakiet 45 — A42.9: moduł pobierania faktur (26.10.2026)

- [x] Transport Blob w `store-client.js` współdzieli nagłówki, kontrolę adresu,
  błędy i refresh z JSON. Sukces binarny nie przechodzi przez dekoder tekstu
  ani adapter ponowienia JSON. Zachowano jednokrotne retry po 401 i brak retry sieci/5xx.
- [x] Wydzielono `invoice-downloads.js` z konfiguracji API. Historia sklepu
  i szczegóły zamówienia używają jednego modułu, aktualnego JWT lub capability
  gościa; PDF gościa nie wysyła bearer credentials ani cookies.
- [x] Równoległe kliknięcia jednej faktury współdzielą żądanie. Wylogowanie
  i wygaśnięcie sesji anulują oczekujące pobrania. Zmiana konta/capability oraz
  generacji blokuje późny wynik. URL jest zwalniany również przy błędzie kliknięcia.
- [x] Odmowa odnowienia aktywnej sesji na stronie zamówienia pokazuje komunikat
  i ukrywa kartę. Błąd pobrania pozwala na jawne ponowienie z historii zamówień.
- [x] 92 testy JS (15 nowych), 85 testów Chromium oraz 69 testów pozostałych
  profili — PASS. Pięć nowych scenariuszy PDF wykonano w czterech profilach
  (20 przypadków): bajty, refresh, gość bez cookies, ponowienie/łączenie kliknięć, logout
  i odmowa odnowienia. Składnia i diff — PASS. CI obejmuje nowe przypadki.
- [x] Zaktualizowano wersje URL modułów i pięciu HTML, bez zmian zdjęć,
  zależności, bundlera ani Dockerfile kopiującego cały frontend.
- [ ] Pełne A42: transport czatu, dalszy podział logiki stron i pozostałe
  duplikacje wymagają kolejnych pakietów.

Instrukcja: [A42.9 — moduł pobierania faktur](A42_MODUL_POBIERANIA_FAKTUR.md).
Raport: `tmp/audit-a42-9-e76a4c31/report.json` — PASS. Testy miały własne sesje,
porty i dokumenty fixture. Backend i schematy bez zmian; nie powtarzano Maven
ani nie wdrażano na dev/prod. A05, całe A41 i zdjęcia nadal odłożone.

## Pakiet 46 — A42.10: moduł transportu czatu (27.10.2026)

- [x] Wydzielono `chat-client.js`, a widget używa wspólnego `store-client.js`.
  Usunięto lokalne fetch/dekodery HTTP widgetu. Transport nie wymaga DOM/storage;
  widok zachowuje szkice, historię, polling i dotychczasowe klucze rozmowy.
- [x] Wszystkie żądania czatu używają capability w nagłówku, bez cookies,
  bearer JWT i refresh sesji konta. Zachowano `requestId` dalszej wiadomości
  przy jawnym ponowieniu. Nie dodano automatycznego retry ani anulowania zapisów.
- [x] Identyczne odczyty współdzielą żądanie. Zamknięcie/ukrycie czatu i nowa
  rozmowa anulują odczyty, a generacja blokuje późne wyniki i błędy.
  Szkic przetrwa ponowne otwarcie; stary odczyt nie odblokowuje nowszego.
- [x] Aktualne 404 czyści ID/capability i przywraca wymagane pola pierwszej
  wiadomości. Stare 404 nie czyści nowej rozmowy. Błąd historii zachowuje widok.
- [x] 104 testy JS (12 nowych), 90 Chromium oraz 45 testów pozostałych
  profili — PASS. Pięć nowych scenariuszy wykonano w czterech profilach
  (20 przypadków), w tym otwieranie widgetu na pięciu stronach. Składnia i diff — PASS.
- [x] Pięć HTML ładuje widget jako moduł ES z nową wersją URL. CI obejmuje
  nowe testy JS i przeglądarkowe; bez bundlera, zależności i zmian zdjęć.
- [ ] Pełne A42: dalszy podział dużych plików sklepu/CC i pozostałe duplikacje
  nadal wymagają kolejnych pakietów.

Instrukcja: [A42.10 — moduł transportu czatu](A42_MODUL_TRANSPORTU_CZATU.md).
Raport: `tmp/audit-a42-10-c49b318e/report.json` — PASS. Testy miały własne sesje,
capability i porty; backend i schematy bez zmian. Nie powtarzano Maven ani nie
wdrażano na dev/prod. A05, całe A41 i zdjęcia nadal odłożone.

## Pakiet 47 — A42.11: moduł konwersji passkey (27.10.2026)

- [x] Wydzielono `passkey-codec.js`: konwersję opcji rejestracji/logowania,
  base64url, serializację poświadczeń i dotychczasową kontrolę lokalnego hosta.
  Moduł nie wykonuje HTTP, nie używa DOM/storage ani nie uruchamia urządzenia.
- [x] Kodowanie porcjami usuwa potwierdzony `RangeError` dla bufora 1 MiB.
  Widoki bufora zachowują offset/długość, a payloady i domyślne opcje API pozostają zgodne.
- [x] ID wyzwania jest przechwytywane przed operacjami asynchronicznymi.
  Spóźniony wynik starego wyzwania nie jest wysyłany pod nowym ID i nie
  odblokowuje nowego widoku. Rejestracja sprawdza niezmienność konta.
- [x] 116 testów JS (12 nowych), 95 Chromium oraz 45 testów pozostałych
  profili — PASS. Pięć nowych scenariuszy passkey wykonano w czterech profilach
  (20 przypadków), z buforem 1 MiB, anulowaniem, 401 bez ponowień i wyścigiem wyzwań.
  Użyto symulowanego authenticatora; nie deklaruje się testu sprzętowego.
- [x] Zaktualizowano wersje URL kodu i konfigurację CI. Składnia i diff — PASS.
  Bez nowych zależności, bundlera, API, kluczy MFA/JWT i zmian zdjęć.
- [ ] Pełne A42: dalszy podział dużych plików sklepu/CC i pozostałe duplikacje
  nadal wymagają kolejnych pakietów.

Instrukcja: [A42.11 — moduł passkey](A42_MODUL_PASSKEY.md).
Raport: `tmp/audit-a42-11-88d2cb16/report.json` — PASS. Testy miały własne
poświadczenia fixture i porty; backend bez zmian. Nie powtarzano Maven ani nie
wdrażano na dev/prod. A05, całe A41 i zdjęcia nadal odłożone.

## Pakiet 48 — A42.12: renderer odpowiedzi Asystenta CC (27.10.2026)

- [x] Wydzielono `assistant-markdown.js`: renderer podzbioru Markdown bez
  DOM, HTTP, storage i dostępu do kluczy. UI nadal obsługuje pytania i historię.
- [x] Kod jest dosłowny, zachowuje wcięcia i nie jest przetwarzany jako
  nagłówki/listy/tabele. Cytaty rozpoznawane przed escapowaniem działają poprawnie.
  Listy są rozdzielane od akapitów, a dane tabel zawierające `---` nie znikają.
- [x] Cały tekst źródłowy jest escapowany, a nazwa języka kodu walidowana.
  HTML, skrypty, SVG, iframe, linki i media pozostają nieaktywne. Bez zmian
  zdjęć, ustawień AI, kluczy, transportu i autoryzacji panelu.
- [x] 130 testów JS (14 nowych), 28 testów CC w Chromium (trzy nowe)
  i sześć prób renderera w Firefox/WebKit — PASS. Potwierdzono brak wykonania
  HTML i żądań do osadzonych adresów, bezpieczne błędy API i odzyskanie.
- [x] Testy sprawdzają rzeczywisty silnik; konfiguracja ma osobne wyjścia
  raportów. CI obejmuje nowe przypadki. Składnia i diff — PASS.
- [ ] Pełne A42: dalszy podział dużych plików sklepu/CC i pozostałe duplikacje
  nadal wymagają kolejnych pakietów.

Instrukcja: [A42.12 — renderer odpowiedzi CC](A42_RENDERER_ODPOWIEDZI_AI_CC.md).
Raport: `tmp/audit-a42-12-fb380ae7/report.json` — PASS. Testy miały własny serwer
fixture i kontrolowane odpowiedzi; nie łączyły się z zewnętrznymi modelami AI.
Backend bez zmian; nie powtarzano Maven ani nie wdrażano na dev/prod.
A05, całe A41 i zdjęcia nadal odłożone.

## Pakiet 49 — A42.13: moduł palety poleceń CC (27.10.2026)

- [x] Wydzielono `command-palette.js`: niezależny model katalogu/wyszukiwania
  oraz kontroler dialogu. Nawigacja korzysta ze słownika CC, a nazwy i dostępność
  operacji z bieżącego katalogu serwera. Bez HTTP i dostępu do tokenu w module.
- [x] ID zastępuje porównywanie fragmentów nazw; różne operacje o tych samych
  nazwach nie znikają. Usunięto podwójne, stałe nazwy i nieaktywne skróty operacji.
  Wyszukiwanie obejmuje słowa z wielu pól i normalizuje polskie znaki.
- [x] Strzałki aktualizują istniejące węzły. Dialog ma combobox/listbox,
  aktywną opcję, obsługę pustych wyników, Tab/Escape i powrót fokusu.
  Nie nakłada się na inny dialog ani nie traci stanu po późnym zdarzeniu close.
- [x] Wybór operacji otwiera dotychczasowy formularz; potwierdzenie działania
  niebezpiecznego pozostaje wymagane. Dane katalogu są wstawiane przez textContent;
  linki palety wymagają HTTP(S) bez loginu/hasła.
- [x] 140 testów JS (10 nowych), 33 testy CC w Chromium i 16 w Firefox/WebKit
  — PASS. Pięć nowych scenariuszy palety wykonano w trzech silnikach (15 przypadków).
  Odbiór obejmuje lokalny pomiar axe, brak automatycznego POST zadania,
  nieaktywne nazwy HTML, język, fokus i zachowanie DOM. Składnia i diff — PASS.
- [x] CI obejmuje nowe przypadki; zaktualizowano wersje URL, bez zmian CSS,
  zdjęć, zależności i bundlera.
- [ ] Pełne A42: dalszy podział dużych plików sklepu/CC i pozostałe duplikacje
  nadal wymagają kolejnych pakietów.

Instrukcja: [A42.13 — paleta poleceń CC](A42_MODUL_PALETY_POLECEN_CC.md).
Raport: `tmp/audit-a42-13-6e439dc2/report.json` — PASS. Testy miały własny katalog
i serwer fixture; nie uruchamiały rzeczywistych zadań operatora. Backend bez zmian.
Nie powtarzano Maven ani nie wdrażano na dev/prod. A05, całe A41 i zdjęcia odłożone.

## Pakiet 50 — A42.14: wspólny zapis operacji operatora CC (27.10.2026)

- [x] Wydzielono `operator-intents.js`: wspólne blokady, walidacja, trwały
  zapis przed HTTP i sprzątanie po ID dla przyjęć magazynowych oraz korekt.
  Moduł nie wykonuje HTTP i nie odczytuje tokenu panelu.
- [x] Zachowano istniejące nazwy kluczy i poprawne zapisy v1, niezmienne
  payloady, identyfikatory ponowień oraz normalizację kwoty przez BigInt.
  Niepewny wynik pozostawia zapis; błędny storage blokuje nowe polecenie.
- [x] Profil i klucz są przechwytywane przed blokadą. Zmiana profilu nie
  przekierowuje zapisu ani cleanup, a późne sprzątanie nie usuwa nowszej operacji.
- [x] Osiem nowych testów JS i aktualizacja pięciu wcześniejszych testów
  korekt do rzeczywistego modułu. Trzy scenariusze operacji w każdym z trzech
  silników — PASS: reload i ponowienie przyjęcia/korekty oraz blokada błędnych
  zapisów bez POST. Dwa scenariusze przeglądarkowe są nowe.
- [x] Wspólny odbiór A42.14/A42.15: 157 JS, 39 CC Chromium, 15 Firefox
  i 15 WebKit — PASS. Składnia i diff — PASS. CI i wersje URL zaktualizowane.
- [ ] Pełne A42: dalszy podział dużych plików sklepu/CC i pozostałe duplikacje
  nadal wymagają kolejnych pakietów.

Instrukcja: [A42.14 — operacje operatora CC](A42_MODUL_OPERACJI_OPERATORA_CC.md).
Wspólny raport: `tmp/audit-a42-14-15/report.json` — PASS. Własne dane i serwer
fixture, bez rzeczywistych przyjęć i korekt. Backend bez zmian; bez Maven,
obrazu i wdrożenia dev/prod. A05, całe A41 i zdjęcia pozostają odłożone.

## Pakiet 51 — A42.15: kontroler rozmowy Asystenta CC (27.10.2026)

- [x] Wydzielono `assistant-conversation.js`: prywatna historia i stan tury,
  wstrzykiwany transport oraz callbacki widoku. Bez dostępu do kluczy i storage.
- [x] Jedna aktywna tura blokuje ponowne wysłanie przez Enter/formularz/skrót;
  szkic następnego pytania pozostaje w polu. Przyciski i aria-busy odzwierciedlają
  oczekiwanie. Czyszczenie anuluje fetch i historię.
- [x] Stara odpowiedź/błąd nie wracają po resecie, również gdy transport ignoruje
  anulowanie. Stare finally nie odblokowuje nowszej tury. Wygaśnięcie sesji
  anuluje odpowiedź i blokuje wysyłkę do reload; czyszczenie nie omija blokady.
- [x] Historia jest ograniczona do dziesięciu wiadomości zakończonych par.
  Bieżące pytanie trafia do message, bez duplikacji w history. Błędy i niepoprawne
  odpowiedzi nie trafiają do przyszłego kontekstu; ręczne ponowienie pozostaje możliwe.
- [x] Dziewięć nowych testów JS i cztery nowe scenariusze rozmowy wykonane
  w trzech silnikach (12 przypadków). Wspólny odbiór obu etapów: 157 JS,
  39 CC Chromium, 15 Firefox i 15 WebKit — PASS. Składnia i diff — PASS.
- [x] CI i wersje URL zaktualizowane. Renderer oraz ustawienia dostawców AI
  pozostają zgodne; testy sprawdziły też ochronę sesji i obsługę kluczy.
- [ ] Pełne A42: dalszy podział dużych plików sklepu/CC i pozostałe duplikacje
  nadal wymagają kolejnych pakietów.

Instrukcja: [A42.15 — rozmowa Asystenta CC](A42_KONTROLER_ROZMOWY_ASYSTENTA_CC.md).
Wspólny raport: `tmp/audit-a42-14-15/report.json` — PASS. Testy używały
kontrolowanych odpowiedzi, bez połączeń z modelami zewnętrznymi. Backend bez
zmian; bez Maven, obrazu i wdrożenia dev/prod. A05, całe A41 i zdjęcia odłożone.

## Pakiet 52 — A42.16: monitor zadań CC (27.10.2026)

- [x] Wydzielono `job-monitor.js`: wybór obserwacji, odczyty i timer oraz decyzja
  o zakończeniu w jednym module. Transport i callbacki są wstrzykiwane;
  moduł nie ma dostępu do DOM, storage ani tokenu i nie wykonuje POST.
- [x] Powtarzane kliknięcia nie tworzą dodatkowego GET/timera. Zmiana zadania
  anuluje poprzedni odczyt; stary sukces/błąd i finally nie zmieniają nowego
  logu, diagnozy, szuflady ani stanu oczekiwania. Poprzedni timer jest usuwany.
- [x] Zadania queued i running są odczytywane co sekundę, bez nakładania.
  Weryfikowane są ID/status odpowiedzi. Błąd zatrzymuje pętlę; ponowny wybór
  odzyskuje odczyt bez ponownego uruchamiania polecenia.
- [x] Powiadomienie o zakończeniu jest jednokrotne, również gdy nowo utworzone
  zadanie kończy się przed pierwszym odczytem. Samo otwarcie zakończonego
  zadania z historii nie powiadamia. Wygaśnięcie sesji trwale zatrzymuje monitor.
- [x] Log ma aria-busy, a zmiana wyboru czyści poprzednią treść i diagnozę.
  Zachowano podążanie za końcem logu; tekst HTML pozostaje nieaktywny.
  Asynchroniczny callback odświeżenia języka kontroluje aktualność obserwacji.
- [x] 169 testów JS (12 nowych), 44 CC Chromium, 20 Firefox i 20 WebKit
  — PASS. Pięć nowych scenariuszy monitora wykonano 15 razy w trzech silnikach.
  Odbiór sprawdzał też brak dodatkowych POST i pojedyncze powiadomienie.
- [x] Składnia i diff — PASS. Zaktualizowano CI i wersję URL; bez nowych
  zależności, zmian backendu, API, kluczy i zdjęć.
- [ ] Pełne A42: dalszy podział dużych plików sklepu/CC i pozostałe duplikacje
  nadal wymagają kolejnych pakietów.

Instrukcja: [A42.16 — monitor zadań CC](A42_MONITOR_ZADAN_CC.md).
Raport: `tmp/audit-a42-16/report.json` — PASS. Testy używały własnego serwera
fixture i kontrolowanych odpowiedzi, bez rzeczywistych poleceń operatora.
Nie powtarzano Maven ani testów sklepu; bez obrazu i wdrożenia dev/prod.
Monitor dotyczy wybranego zadania; nie zatrzymuje jego wykonania na serwerze.
A05, całe A41 i zdjęcia nadal odłożone.

## Pakiet 53 — A42.17: wspólna sesja klienta (27.10.2026)

- [x] Jeden moduł `customer-session.js` dla trzech stron: odświeżanie cookie, opcje HTTP, walidacja, współdzielenie obietnicy i Web Lock.
- [x] Wylogowanie, wygaśnięcie lub nowe logowanie unieważniają starszy refresh. Późny odczyt i finally nie przywracają sesji ani nie usuwają nowszej próby.
- [x] Wspólne wykluczenia publicznych endpointów; zachowane lokalne komunikaty i cleanup. Dwanaście nowych testów oraz regresja sesji/MFA/PDF w czterech profilach.

Instrukcja: [domknięcie A42](A42_DOMKNIECIE_MODULOW.md).
Raport wspólny: `tmp/audit-a42-complete/report.json` — PASS. Odbiór lokalny,
bez budowania obrazów i wdrożenia dev/prod. A05, całe A41 oraz fragmenty
dotyczące samych zdjęć pozostają odłożone.

## Pakiet 54 — A42.18: moduły ES sklepu (27.10.2026)

- [x] Wydzielono moduły katalogu, ofert, koszyka, dostawy/kuponów, checkoutu, konta, danych klienta, MFA, porównania, stanu, DOM, usług i formatowania.
- [x] Słowniki mają niezależny moduł danych (172 zgodne klucze PL/EN); i18n i dialogi są modułami ES. Zachowano fasady kompatybilności i eksport openSuccessModal.
- [x] Zachowano fingerprint/wersję zamiaru, capability i niezmienne klucze zakupów. Testy ponowień korzystają z rzeczywistego modułu checkoutu.
- [x] Poprawiono rozpoznanie kliknięcia w koszyku po wymianie przycisku przez render (composedPath) i kontrolę hover/fokusu przy opóźnionym zamknięciu. Trzy powtórzenia w WebKit i pełne 380 prób sklepu — PASS.
- [x] Bez zmian formatów, pobierania, cache i rotacji zdjęć; porównanie AST pięciu funkcji renderowania potwierdza zachowanie.

Instrukcja: [domknięcie A42](A42_DOMKNIECIE_MODULOW.md).
Raport wspólny: `tmp/audit-a42-complete/report.json` — PASS. Odbiór lokalny,
bez budowania obrazów i wdrożenia dev/prod. A05, całe A41 oraz fragmenty
dotyczące samych zdjęć pozostają odłożone.

## Pakiet 55 — A42.19: moduły CC i kontroler terminala (27.10.2026)

- [x] Wejście CC składa kontrolery; routing, operacje, dane, dokumenty, czat, Asystent, zadania, zdrowie, zdarzenia, chaos i diagnostyka mają osobne moduły.
- [x] Kontroler terminala blokuje podwójne tworzenie/wejście, współdzieli GET, anuluje stary odczyt i chroni nowy log/szkic. POST nie są automatycznie ponawiane.
- [x] Zakończony terminal blokuje wejście, nieudane zastąpienie zachowuje poprzednią sesję, a wygaśnięcie wyłącza tworzenie do reload.
- [x] Osiem nowych testów jednostkowych i trzy scenariusze terminala w trzech silnikach. Regresja CC: 47 Chromium + 23 Firefox + 23 WebKit — PASS.

Instrukcja: [domknięcie A42](A42_DOMKNIECIE_MODULOW.md).
Raport wspólny: `tmp/audit-a42-complete/report.json` — PASS. Odbiór lokalny,
bez budowania obrazów i wdrożenia dev/prod. A05, całe A41 oraz fragmenty
dotyczące samych zdjęć pozostają odłożone.

## Pakiet 56 — A42.20: moduły serwera CC (27.10.2026)

- [x] Wydzielono 11 modułów HTTP, operacji, transportu, sekretów, zdrowia, danych/dokumentów, zdarzeń, chaosu, procesów, diagnostyki i AI.
- [x] Serwer jest punktem kompozycji ze współdzielonym stanem i 54 cienkimi fasadami. Funkcje otrzymują kontekst, bez zwrotnych importów i efektów uruchamianych przy imporcie.
- [x] Dowód AST 54 funkcji i handlera potwierdza zachowanie po podstawieniu kontekstu. Kolejność Host/Origin/sesji i weryfikacji operatora pozostaje zachowana.
- [x] Siedem nowych testów granic/mutantów i niezależnych kontekstów; pełne 152 testy Python w WSL — PASS, z lokalnymi HTTP/PTY i własnymi fixture.

Instrukcja: [domknięcie A42](A42_DOMKNIECIE_MODULOW.md).
Raport wspólny: `tmp/audit-a42-complete/report.json` — PASS. Odbiór lokalny,
bez budowania obrazów i wdrożenia dev/prod. A05, całe A41 oraz fragmenty
dotyczące samych zdjęć pozostają odłożone.

## Pakiet 57 — A42.21: odbiór i zamknięcie całego A42 (27.10.2026)

- [x] Kontrola linkuje pięć grafów ES (58 modułów) bez wykonywania DOM/HTTP. Wykrywa brak modułu/eksportu, sprzeczne wersje stanu i kopie polityki refresh.
- [x] Kontrola Python egzekwuje cienkie fasady, pojedynczy HTTP i brak importów zwrotnych/efektów importu. Testy mutantów sprawdzają skuteczność bramek.
- [x] 198 JS, 152 Python, 380 sklep i 93 CC — PASS. Nowych testów: 29 JS, siedem Python i dziewięć wykonań trzech scenariuszy terminala.
- [x] Ponownie potwierdzono parent 17 modułów Maven, 12 adapterów korelacji i strukturę korelacji/odporności 15 JAR-ów (cztery aktywne + 11 bez dodatkowych bibliotek). Bez ponownej kompilacji i testów Java, bo ich kod nie zmienił się w tej turze.
- [x] Składnia i diff — PASS. Zaktualizowano wersje URL, dokumentację i CI. Pierwotne warunki odbioru A42 są spełnione; A42.1–A42.21 i pełne A42 oznaczono jako wykonane.

Instrukcja: [domknięcie A42](A42_DOMKNIECIE_MODULOW.md).
Raport wspólny: `tmp/audit-a42-complete/report.json` — PASS. Odbiór lokalny,
bez budowania obrazów i wdrożenia dev/prod. A05, całe A41 oraz fragmenty
dotyczące samych zdjęć pozostają odłożone.

## Pakiet 58 — A43.1: ograniczenie i retencja logu rekomendacji (27.10.2026)

- [x] Zastąpiono domyślny INSERT każdego odczytu metrykami zbiorczymi dwóch
  strategii i liczbą wyników. Etykiety nie zawierają klientów, produktów ani tokenów.
- [x] Jawnie włączany log diagnostyczny pozostaje dostępny; awaria jego zapisu
  nie zmienia poprawnego wyniku. Usunięto transakcję obejmującą odczyty HTTP.
- [x] Worker usuwa rekordy starsze niż konfigurowalna granica UTC: domyślnie
  siedem dni, po 500 wierszy, najwyżej cztery transakcje w przebiegu. Granica
  czasu i nowsze rekordy są chronione. SKIP LOCKED umożliwia współpracę instancji.
- [x] Migracja V2 zastępuje indeks czasu indeksem czasu/ID. Sama migracja nie
  usuwa danych. Workera można wyłączyć; błędne limity blokują start.
- [x] Liczniki zapisów/usunięć/błędów oraz migawka najstarszego rekordu,
  zaległości i czasu sukcesu umożliwiają monitoring. Nieznany stan ma -1,
  a częściowy błąd nie ogłasza sukcesu ani nie gubi liczby zakończonych usunięć.
- [x] Parametry są w aplikacji, Compose i ConfigMap; bez nowych sekretów.
  Istniejący krok CI `-Pintegration verify` obejmuje nowe testy PostgreSQL.
- [x] 125 testów jednostkowych Java + cztery PostgreSQL = 129 — PASS, w tym
  20 nowych. Odbiór obejmuje migrację, granicę czasu, blokadę wiersza, równoległe
  transakcje, budżet i nadrabianie zaległości. Ranking i pola obrazów zachowane.
- [x] 12 testów Kubernetes, parent 17 modułów i kontrola 15 JAR-ów — PASS.
  YAML oraz diff — PASS. Nie powtarzano UI ani niezmienionych usług.
- [ ] Całe A43: retencja pozostałych danych/zdarzeń, polityki i procedury
  eksportu/usuwania/anonimizacji we wszystkich usługach i kopiach nadal otwarte.

Instrukcja: [A43.1 — retencja logu rekomendacji](A43_RETENCJA_LOGU_REKOMENDACJI.md).
Raport: `tmp/audit-a43-1/report.json` — PASS. Własny kontener PostgreSQL i dane
fixture; bez czyszczenia bazy developerskiej lub produkcyjnej. Bez obrazu,
zdalnego CI i wdrożenia dev/prod. A05, całe A41 i zmiany zdjęć nadal odłożone.

## Pakiet 59 — A43.2: retencja tokenów i danych MFA (27.10.2026)

- [x] Osobny moduł Customer czyści zamknięte rodziny refresh, stare tokeny
  resetu, użyte recovery i wygasłe nieaktywne konfiguracje TOTP. Domyślna
  retencja siedem dni; ścisła granica chroni rekordy dokładnie na niej.
- [x] Zachowano historię replay żywych rodzin, aktywne TOTP i niewykorzystane
  recovery. Testy wywołują rzeczywisty refresh i weryfikację MFA po czyszczeniu.
- [x] Najpierw blokada klienta, następnie świeża migawka kwalifikacji; SKIP
  LOCKED, maksymalnie 100 rekordów × cztery partie × cztery kategorie na przebieg.
  Wznowienie i potwierdzenie TOTP oraz współbieżne czyszczenie odebrane na PostgreSQL.
- [x] Osobne krótkie transakcje, obsługa awarii bazy i transakcji, zachowane
  liczniki zatwierdzonych usunięć; migawki zaległości i błędów bez danych klienta.
  Stan nieznany ma -1, a zero usunięć nie maskuje rekordów pominiętych przez blokadę.
- [x] Migracja V13 dodaje pięć indeksów bez usuwania danych. Sześć parametrów
  w aplikacji, Compose i ConfigMap; automatyczne czyszczenie wyłączone w testowym H2.
- [x] 152 jednostkowe Java + dziewięć PostgreSQL = **161 — PASS**, bez pominiętych;
  17 nowych przypadków. Dodatkowo 12 testów Kubernetes, parent 17 modułów,
  kontrola korelacji/odporności 15 JAR-ów i pakietu Customer — PASS.
- [x] YAML, dokumentacja i diff — PASS. Bez nowych zależności i zmian UI.
- [ ] Całe A43: znaczniki weryfikacji kont, dalsza retencja audytu/telemetrii/
  zdarzeń i eksport/usuwanie/anonimizacja obejmujące wszystkie usługi oraz kopie.

Instrukcja: [A43.2 — retencja tokenów i MFA](A43_RETENCJA_TOKENOW_I_MFA.md).
Raport: `tmp/audit-a43-2/report.json` — PASS. Własne kontenery i dane fixture;
nie czyszczono bazy developerskiej ani produkcyjnej. Bez budowania obrazów,
zdalnego CI i wdrożenia dev/prod. A05, całe A41 i zmiany zdjęć nadal odłożone.

## Pakiet 60 — A43.3: retencja znaczników weryfikacji kont (27.10.2026)

- [x] Worker czyści wygasłe hashe niepotwierdzonych rejestracji i stare pola
  weryfikacji aktywnych kont. Wykonuje UPDATE wybranych pól, bez usuwania kont,
  zmiany hasła/profilu/aktywacji ani biznesowego `updated_at`.
- [x] Niepotwierdzona rejestracja zachowuje znacznik oczekiwania i oba limity
  wysyłki. Rzeczywiste ponowienie i potwierdzenie działają po wyczyszczeniu
  hasha; ponowna rejestracja nie zastępuje oczekującego konta.
- [x] Potwierdzenie linku blokuje konto i ponownie sprawdza hash po oczekiwaniu
  na równoległe ponowienie wysyłki. Potwierdzenie jest jednokrotne. PostgreSQL
  potwierdza faktyczne oczekiwanie starego zapytania na blokadę i jego odrzucenie.
- [x] `@DynamicUpdate` Customer zapobiega odtworzeniu wyczyszczonych pól przez
  starszą transakcję profilu. Test obejmuje oba rodzaje znaczników i zapis adresu.
- [x] Wspólne limity i blokady właściciela/SKIP LOCKED; granica czasu, świeże
  linki, nieznany termin i niedawne metadane chronione. Dwie nowe kategorie
  dodają do 800 aktualizacji do domyślnego przebiegu, łącznie do 2400 zmian.
- [x] V14 dodaje dwa indeksy bez czyszczenia danych. Metryki `cleared` i
  `last_cleared` odróżniają aktualizacje pól od usuniętych rekordów. Łącznie
  44 metryki, sześć stałych kategorii, brak identyfikatorów klienta w etykietach.
- [x] **157 jednostkowych Java + 18 PostgreSQL = 175 — PASS**, bez pominiętych;
  14 nowych przypadków (pięć jednostkowych i dziewięć PostgreSQL).
- [x] Parent 17 modułów, 12 adapterów korelacji, struktura korelacji/odporności
  15 JAR-ów i V14 w pakiecie Customer — PASS. Dokumentacja i diff — PASS.
  UI/manifesty nie zmieniły się; nie powtarzano testów przeglądarek/Kubernetes.
- [ ] Całe A43: dalsza retencja audytu, telemetrii i zdarzeń oraz techniczne
  procedury eksportu/usuwania/anonimizacji obejmujące wszystkie usługi i kopie.

Instrukcja: [A43.3 — retencja weryfikacji kont](A43_RETENCJA_WERYFIKACJI_KONT.md).
Raport: `tmp/audit-a43-3/report.json` — PASS. Własne kontenery i dane fixture,
powiadomienia mockowane, bez wysyłki e-maili i czyszczenia prawdziwych baz.
Bez nowych zależności/sekretów, budowania obrazów, zdalnego CI i wdrożenia
dev/prod. A05, całe A41 i zmiany samych zdjęć nadal odłożone.

## Pakiet 61 — A43.4: retencja audytu bezpieczeństwa Customer (27.10.2026)

- [x] Osobny moduł audytu, niezależny od siedmiodniowej retencji tokenów/MFA.
  Domyślnie `observe`: metryki wieku i zaległości, bez DELETE. `purge` włącza
  ograniczone usuwanie; `off` wyłącza również odczyty. Próg obserwacji 90 dni
  nie jest uzgodnioną docelową polityką przechowywania audytu.
- [x] W trybie purge do 500 rekordów w partii i cztery partie w przebiegu,
  osobne transakcje, ścisła granica UTC, kolejność czasu/ID i SKIP LOCKED.
  Zachowano świeże wpisy i dotychczasowy mechanizm zapisu audytu.
- [x] Dziesięć metryk, brak danych klienta w etykietach/logach workera,
  migawka nieznana -1, widoczna zaległość zajętych wpisów oraz błędy.
  Licznik zachowuje wcześniejsze commity po rollbacku kolejnej partii.
- [x] V15 zastępuje indeks samego czasu indeksem czasu/ID; zachowuje pozostałe
  indeksy i nie usuwa danych. Istniejące `audit-list` nadal korzysta z prefiksu czasu.
- [x] Sześć niesekretnych parametrów w aplikacji, Compose i ConfigMap;
  testowe H2 ma `off`. Nie dodano zależności ani nowego widoku CC.
- [x] **167 jednostkowych Java + 24 PostgreSQL = 191 — PASS**, bez pominiętych;
  16 nowych przypadków (dziesięć jednostkowych i sześć PostgreSQL).
  Odbiór obejmuje również rollback, częściowy sukces, odzyskanie i rzeczywisty
  zapis przez AuditService podczas obserwacji/blokady starego wiersza.
- [x] 12 testów Kubernetes, spójność konfiguracji, parent 17 modułów,
  korelacja/odporność 15 JAR-ów i pakiet Customer z V15 — PASS. Diff — PASS.
- [ ] Użytkownik ustala docelowy okres audytu i ewentualnie aktywuje `purge`.
  Nie robić tego automatycznie w ramach ogólnego polecenia kolejnego etapu.
- [ ] Całe A43: dalsza retencja telemetrii/zdarzeń oraz techniczne procedury
  eksportu/usuwania/anonimizacji obejmujące wszystkie usługi i kopie.

Instrukcja: [A43.4 — retencja audytu bezpieczeństwa](A43_RETENCJA_AUDYTU_BEZPIECZENSTWA.md).
Raport: `tmp/audit-a43-4/report.json` — PASS. Własne kontenery PostgreSQL,
testowe wpisy i triggery; bez czyszczenia prawdziwych audytów. Nie budowano
obrazów, nie uruchamiano zdalnego CI ani wdrożenia dev/prod. A05, całe A41
i zmiany samych zdjęć pozostają odłożone.

## Pakiet 62 — A43.5: retencja payloadu opublikowanych zdarzeń faktur (27.10.2026)

- [x] Moduł Billing z trybami off/observe/compact. Domyślny próg 30 dni służy
  obserwacji; automatyczne skracanie danych nie jest włączone.
- [x] Kompaktowanie czyści wyłącznie JSON starego PUBLISHED bez dzierżawy,
  zachowując ID, unikalny event_id, status, daty i pozostałe metadane.
  PENDING/FAILED, świeże wpisy, granice, niepełne daty i pozostałości lease chronione.
- [x] V10 dodaje znacznik czasu, constraint cyklu życia i częściowy indeks.
  Skrócony wpis nie może wrócić do kolejki bez odtworzenia pełnego cyklu.
  Sama migracja nie zmienia treści istniejących zdarzeń.
- [x] StateStore nadal odmawia przejęcia PUBLISHED; spóźnione complete starego
  ticketu nie przywraca treści. fields() jawnie odrzuca odczyt skróconego wpisu.
  Faktury, PDF, korekty, outbox płatności, inboxy i Redis poza zakresem.
- [x] Kandydaci materializowani raz w partii, SKIP LOCKED, osobne transakcje,
  maksymalnie 500 × cztery aktualizacje w przebiegu. Testy wykryły i po poprawce
  potwierdziły usunięcie problemu wielokrotnego doboru kandydatów ponad limit.
- [x] Dziesięć metryk i sześć niesekretnych parametrów w aplikacji/Compose/
  Kubernetes. Błędy nie gubią liczby wcześniejszych commitów ani migawki.
- [x] **157 jednostkowych Java + siedem PostgreSQL = 164 — PASS**, bez pominiętych;
  17 nowych przypadków. Regresja faktur/PDF/korekt/eksportu i publikacji — PASS.
- [x] Profil integracyjny Billing i cztery zależności wyłącznie testowe;
  istniejący krok CI obejmuje nowe IT. Bez dodatkowych zależności runtime.
- [x] 12 testów Kubernetes, konfiguracja, parent 17 modułów, korelacja/
  odporność 15 JAR-ów, V10 i klasy w pakiecie Billing — PASS. Diff — PASS.
- [ ] Użytkownik ustala okno diagnostyki/odtwarzania treści faktur i ewentualnie
  aktywuje compact. Nie włączać go automatycznie przy kolejnym etapie.
- [ ] Całe A43: polityki/aktywacje audytu i treści faktur, dalsza retencja
  zdarzeń/logów (w tym plików zadań CC) oraz eksport/usuwanie/anonimizacja
  obejmujące wszystkie usługi i kopie.

Instrukcja: [A43.5 — retencja treści outboxu faktur](A43_RETENCJA_TRESCI_OUTBOXU_FAKTUR.md).
Raport: `tmp/audit-a43-5/report.json` — PASS. Własny kontener PostgreSQL
i dane fixture; bez prawdziwego brokera i kompaktowania danych użytkownika.
Bez budowania obrazów, zdalnego CI i wdrożenia dev/prod. A05, całe A41
i zmiany samych zdjęć pozostają odłożone.

## Pakiet 63 — A43.6: retencja zamkniętych logów zadań CC (27.10.2026)

- [x] Osobny moduł CC z trybami off/observe/purge i pięcioma parametrami.
  Domyślny próg 14 dni służy obserwacji; nie aktywowano usuwania.
- [x] Nowe zadanie otrzymuje znacznik zamknięcia pliku. Lease chroni kolejkę,
  zapis oraz przechowywaną historię także między procesami CC; blokada logu
  pozostaje utrzymana przez potomka dziedziczącego stdout.
- [x] Kwalifikacja wymaga starych: zamknięcia, mtime logu i mtime znacznika,
  zwykłych plików z pojedynczym linkiem oraz wolnych blokad. Legacy, awarie
  bez zamknięcia, nieznane metadane/wersje, linki i podkatalogi są chronione.
- [x] Bez rekurencyjnego usuwania: wyłącznie pary UUID w stałym katalogu jobs,
  O_EXCL/O_NOFOLLOW, weryfikacja wszystkich komponentów ścieżki i ponowna
  kontrola katalogu/plików przed unlink. Kolizja i podmiana nie nadpisują logu.
- [x] Do 100 zmian i 500 wpisów katalogu na przebieg; miękki budżet pętli 2 s,
  odstęp 600 s, iterator kontynuowany do końca. Brak odczytu treści logów.
  Częściowy błąd zachowuje zatwierdzone liczniki i poprzednią udaną migawkę.
- [x] GET /api/jobs/retention udostępnia bezpieczny stan pod istniejącą sesją
  oraz Host/Origin; nie uruchamia czyszczenia. Bez nowego widoku UI i POST.
- [x] Start/stop/reload modułu podłączone do cyklu CC; timeout, błędy otwarcia
  i zwalnianie historii mają odbiór na rzeczywistym runnerze i procesach fixture.
- [x] **179 testów Python — PASS, bez pominiętych**, w tym 27 nowych
  (26 modułu i jeden HTTP). Wynik obejmuje 12 istniejących testów Kubernetes.
  FIFO i podmianę katalogu z blokadą sprawdzono w temp Linux; pozostałe przypadki
  także na DrvFS projektu. Retencja z blokadami wymaga POSIX/WSL.
- [x] Bramka struktury: 12 modułów, 54 cienkie fasady, jedna implementacja HTTP
  i aktywne testy mutantów — PASS. Diff — PASS. Bez nowych zależności i zmian
  Java/JS/UI; nie powtarzano Maven ani przeglądarek.
- [ ] Użytkownik ustala docelowy okres logów i ewentualnie aktywuje purge.
  Nie włączać automatycznie przy ogólnym poleceniu kolejnego etapu.
- [ ] Całe A43: pozostałe polityki/aktywacje, limit rozmiaru pojedynczego logu
  i koszt jego odczytu, dalsza retencja zdarzeń oraz procedury danych usług/kopii.

Instrukcja: [A43.6 — retencja logów zadań CC](A43_RETENCJA_LOGOW_ZADAN_CC.md).
Raport: `tmp/audit-a43-6/report.json` — PASS. Własne pliki, procesy, HTTP i PTY
fixture; nie skanowano ani nie czyszczono prawdziwych logów użytkownika.
Nie restartowano działającego CC i nie wdrażano na dev/prod. A05, całe A41
oraz zmiany samych zdjęć pozostają odłożone.

## Pakiet 64 — A43.7: ograniczony odczyt logów i cache diagnoz CC (27.10.2026)

- [x] Zastąpiono pełny read_text logu ograniczonym seek/read: domyślnie do
  120000 bajtów, początek i koniec, bez odczytywania pominiętego środka.
  Krótkie logi pozostają kompletne; duże mają lokalizowany komunikat pominięcia.
- [x] Lista queued/running/success nie otwiera logów. Failed używa cache
  diagnozy po metadanych pliku/limicie/wersji reguł, bez surowej treści w pamięci.
  Równoległe żądania współdzielą analizę; zwracane dane są kopiami.
- [x] API oznacza ograniczony lub zmieniający się odczyt i diagnozę próbki;
  zmieniona w trakcie próbka nie trafia do cache. Cause PL/EN informuje o próbkowaniu.
- [x] Bezpieczne otwieranie bez linków, zwykłe pliki z jednym dowiązaniem,
  kontrola inode runnera i ochrona jeszcze nieutworzonych zarezerwowanych logów.
  Błędy nie ujawniają ścieżek ani treści wyjątków.
- [x] Jedna niesekretna zmienna CC_JOB_LOG_READ_MAX_BYTES, zakres 4096–1048576,
  walidacja przy starcie; limit odczytu niezależny od trybu retencji.
- [x] **198 testów Python — PASS, bez pominiętych**, w tym 19 nowych
  (17 modułu/Job i dwa HTTP). Wynik obejmuje istniejące 12 Kubernetes.
  Plik sparse 1 GiB był odczytany w budżecie 4096 bajtów; potwierdzono też
  działanie pod blokadą aktywnego pisarza A43.6 i sześć żądań jednego cache miss.
- [x] Bramka struktury: 13 modułów, 54 fasady i jeden HTTP — PASS; mutanty
  nadal aktywne. Diff — PASS. Bez zależności i zmian JS/Java; nie ponawiano UI/Maven.
- [x] Limit rozmiaru pliku podczas zapisu wdrożono później w A43.8 (pakiet 65),
  z zachowaniem stdout potomków po zakończeniu zadania/reload CC. Sam pakiet 64
  nie zmieniał przekazywania stdout, procesów ani sposobu zamykania logu.
- [ ] Całe A43: polityki/aktywacje, dalsza retencja zdarzeń
  i eksport/usuwanie/anonimizacja obejmujące wszystkie usługi oraz kopie.

Instrukcja: [A43.7 — odczyt logów i cache diagnoz](A43_ODCZYT_LOGOW_I_CACHE_DIAGNOZ_CC.md).
Raport: `tmp/audit-a43-7/report.json` — PASS. Własne pliki i lokalne HTTP/PTY
fixture, bez czytania prawdziwych logów użytkownika. Nie restartowano CC
i nie wdrażano dev/prod. A05, całe A41 i zmiany samych zdjęć nadal odłożone.

## Pakiet 65 — A43.8: limit zapisu logów z zachowaniem stdout po reload CC (27.10.2026)

- [x] Auto wybiera bounded na obsługiwanym Linux/WSL; direct zachowuje dawny
  zapis. Dwie zmienne hostowego CC, domyślnie 1 MiB, zakres 64 KiB–16 MiB.
  Limit obejmuje nowe zarządzane logi, bez przerabiania istniejących plików.
- [x] Odbiornik czyta do 8192 bajtów, zachowuje nagłówek do 4096 i ograniczony
  bufor końcówki do 65536. Rotacje nie przekraczają limitu pliku; starsza treść
  jest jawnie pomijana. Nie gromadzi całego stdout w RAM.
- [x] Odłączony proces odbiornika przeżywa normalny shutdown/reload/exec CC,
  aż ostatni pisarz zamknie stdout. Pidfd zapobiega pomyleniu procesów po PID;
  bootstrap jest zbierany, a odbiornik nie zostaje nieobsługiwanym dzieckiem CC.
- [x] Osiem wspólnych, blokowanych slotów przetrzymywanych przez odbiorniki;
  pełna pula daje 429 przed startem polecenia. Żaden pisarz nie jest ubijany
  w celu zwolnienia miejsca. Procesy powstają tylko dla przechwytywanych zadań.
- [x] Zachowane stdin/stderr, kod wyjścia i timeout. Błąd dysku w trakcie zapisu
  nie zatrzymuje drenażu stdout; błędy/pominięcia są widoczne. Wymuszona śmierć
  odbiornika jest wykrywana jako SinkExited i może przerwać rurę pisarza.
- [x] Helper ma minimalne środowisko bez kluczy zadania. Certyfikuje v1 state
  dopiero po EOF; retencja chroni żywy zapis i pamiętaną historię, także po exec.
  Tryb bounded utrzymuje znaczniki nawet przy wyłączonym skanie retencji.
- [x] API zadania podaje outputWrite, a GET stanu outputWriter z efektywnym
  trybem. Widoczny tekst i diagnoza oznaczają niepełny zapis także przy pełnym
  odczycie już skróconego pliku. Bez nowego widoku i zmian JS.
- [x] **219 testów Python — PASS, bez pominiętych**, w tym 21 nowych
  (19 writer/runner/procesy i dwa HTTP). Wynik obejmuje 12 istniejących Kubernetes.
  Dodatkowe 19 końcowego odbioru potwierdziło usunięcie logu dopiero po EOF;
  nie sumować ich ponownie do 219. Rzeczywisty reload/exec i zapis potomka — PASS.
- [x] Struktura: 15 modułów, 54 fasady, jeden HTTP — PASS. Diff — PASS.
  Bez zależności i zmian Java/JS; nie ponawiano Maven ani przeglądarek.
- [ ] Całe A43: wybór polityk/aktywacje audytu, payloadu faktur i retencji CC,
  dalsza retencja zdarzeń i eksport/usuwanie/anonimizacja wszystkich usług/kopii.

Instrukcja: [A43.8 — limit zapisu logów CC](A43_LIMIT_ZAPISU_LOGOW_CC.md).
Raport: `tmp/audit-a43-8/report.json` — PASS. Własne pliki/procesy, bez zmiany
prawdziwych logów i restartu działającego CC. Bez wdrożenia dev/prod. A05,
całe A41 i zmiany samych zdjęć nadal odłożone.

## Pakiet 66 — A43.9: retencja kopii danych outboxu płatności (27.10.2026)

- [x] Osobne tryby observe/compact/off, domyślnie tylko obserwacja z progiem
  30 dni. Sześć niesekretnych parametrów w aplikacji, Compose i Kubernetes;
  walidacja startu i wyłączenie automatycznych zapytań w profilu test.
- [x] Czyszczenie wyłącznie recipient/delivery_address starych PUBLISHED
  bez obu pól dzierżawy. Późniejszy czas utworzenia/publikacji musi być
  ściśle starszy od granicy. PENDING/FAILED, nieznane daty, granica i lease
  pozostają chronione; obserwacja nie pobiera danych kontaktowych.
- [x] V17 dodaje znacznik, constraint cyklu życia i częściowy indeks.
  ID, event_key, eventId, zamówienie, kwota, faktura i metadane pozostają.
  Pełne kopie zdarzeń nie są archiwizowane; liczba rekordów nadal rośnie.
- [x] Trwała deduplikacja po event_key i ochrona ponownego claimu PUBLISHED.
  Stary complete nie przywraca danych. Publisher odrzuca załadowany skrócony
  event przed Redis; FAILED nadal można ponowić z pełnym kontaktem.
- [x] Ograniczone, materializowane partie z SKIP LOCKED i REQUIRES_NEW;
  krótkie transakcje, limit przebiegu, liczniki wcześniejszych commitów
  po błędzie i odzyskanie po rollbacku. Dziesięć metryk bez etykiet klientów.
- [x] **206 jednostkowych Java + 7 PostgreSQL = 213 — PASS**, bez pominiętych,
  w tym 19 nowych (12 jednostkowych i siedem PostgreSQL). PostgreSQL wykonał
  V1–V17 i walidację JPA; sprawdzono blokady, równoległe partie, tożsamość,
  constraint, prawdziwy StateStore i rollback. Cztery testy dodatkowego
  odbioru konfiguracji są częścią wcześniejszej puli, nie sumować ponownie.
- [x] 12 Kubernetes, spójność sześciu parametrów w trzech formatach i bramki
  struktury Maven/korelacji/odporności — PASS. Zależności JPA/Testcontainers
  wyłącznie testowe; nowy profil integration jest objęty istniejącym CI.
  Kontrola 15 JAR-ów i pakowania V17/klas/konfiguracji — PASS. Własny kontener
  PostgreSQL usunięty; diff — PASS.
- [ ] Docelowy okres i aktywacja compact — do decyzji użytkownika. Nie włączono
  kompaktowania prawdziwych danych. Przed aktywacją wdrożyć V17 i kod do
  wszystkich instancji Payment.
- [ ] Pełna anonimizacja: event_key nadal zawiera e-mail odbiorcy; zmiana
  tożsamości wymaga osobnej migracji. Dalsza retencja inboxów/zdarzeń oraz
  eksport/usuwanie/anonimizacja usług i kopii pozostają w całym A43.

Instrukcja: [A43.9 — dane outboxu płatności](A43_RETENCJA_DANYCH_OUTBOXU_PLATNOSCI.md).
Raport: `tmp/audit-a43-9/report.json`. Własny kontener PostgreSQL i fixture,
bez rzeczywistego brokera/dostawcy płatności, danych użytkownika, restartu CC
i wdrożenia dev/prod. Nie dodano UI ani sekretów i nie ponawiano przeglądarek.
A05, całe A41 i zmiany samych zdjęć nadal odłożone.

## Pakiet 67 — A43.10: retencja diagnostyki odrzuconych zdarzeń Notification (27.10.2026)

- [x] Niezależne tryby observe/compact/off i sześć parametrów w aplikacji,
  Compose i Kubernetes. Domyślnie tylko obserwacja z progiem 30 dni;
  walidacja startu i wyłączenie zapytań automatycznych w profilu test.
- [x] Czyszczenie payload/error wyłącznie starych REJECTED bez powiązania
  z powiadomieniem. Ścisła granica czasu, ochrona innych statusów,
  przyszłych dat i przyjętych komunikatów. Wystarczy jedno pole diagnostyki;
  puste historyczne wpisy nie wymagają aktualizacji.
- [x] V11 dodaje znacznik, CHECK i częściowy indeks; migracja nie czyści
  danych. ID, hash, status i czas pozostają; rekord jest trwałym znacznikiem
  deduplikacji. Liczba wierszy nadal rośnie, pełna diagnostyka nie jest archiwizowana.
- [x] Rzeczywisty RejectedEventStore nie przywraca treści po redelivery;
  NotificationEventInbox nadal odrzuca konflikt hashy. Przyjęty receipt
  i oczekująca wiadomość zachowują dane i ochronę duplikatów. Protokół ACK,
  kolejka wysyłki/ponowień i DLQ nie zostały zmienione.
- [x] Materializowane, ograniczone partie z SKIP LOCKED i REQUIRES_NEW;
  zaległość widoczna mimo blokady, rollback własnej partii, zachowane liczniki
  wcześniejszych commitów i odzyskanie po awarii. Dziesięć metryk bez etykiet klientów.
- [x] **133 jednostkowe Java + 8 PostgreSQL = 141 — PASS**, bez pominiętych,
  w tym 18 nowych (dziesięć jednostkowych i osiem PostgreSQL). V1–V11 i JPA
  zweryfikowane na PostgreSQL. Testy granic, ograniczeń, redelivery, blokad,
  współbieżności, budżetu, rollbacku i niezmienności przyjętej wiadomości — PASS.
- [x] 12 Kubernetes, spójność sześciu parametrów w trzech formatach i bramki
  struktury Maven/korelacji/odporności — PASS. Nowe zależności są wyłącznie
  testowe; profil integration jest objęty dotychczasowym przebiegiem CI.
  Kontrola 15 JAR-ów oraz pakowania V11/klas/konfiguracji — PASS.
  Własny kontener PostgreSQL usunięty; diff — PASS.
- [ ] Okno diagnostyki i aktywacja compact — do decyzji użytkownika.
  Nie włączono kompaktowania prawdziwych danych. Przed aktywacją wdrożyć
  V11 i nowy kod do wszystkich instancji Notification.
- [ ] Całe A43: polityki/aktywacje, dalsza retencja zdarzeń, przyjętych
  inboxów i powiadomień; pełny eksport/usuwanie/anonimizacja usług i kopii.
  Identyfikatory i hashe pozostają, więc etap nie zamyka anonimizacji.

Instrukcja: [A43.10 — odrzucone zdarzenia Notification](A43_RETENCJA_ODRZUCONYCH_ZDARZEN_NOTIFICATION.md).
Raport: `tmp/audit-a43-10/report.json`. Własny PostgreSQL i fixture, bez
rzeczywistego brokera, wysyłki wiadomości, danych użytkownika i restartu CC.
Bez nowych sekretów, UI, przeglądarek i wdrożenia dev/prod. A05, całe A41
i zmiany samych zdjęć nadal odłożone.

## Pakiet 68 — A43.11: retencja diagnostyki odrzuconych zdarzeń Billing (27.10.2026)

- [x] Osobne observe/compact/off i sześć parametrów w aplikacji, Compose
  i Kubernetes. Domyślnie obserwacja z progiem 30 dni; walidacja startu.
  Profil test wyłącza automatyczne zapytania obu retencji Billing.
- [x] Kompaktowanie payload/error wyłącznie starych REJECTED bez related_id.
  Ścisła granica czasu, ochrona przyjętych/innych statusów, powiązań i dat
  przyszłych; obsługa pojedynczego pola diagnostyki i pustych historycznych wpisów.
- [x] V11: znacznik, CHECK i częściowy indeks, bez czyszczenia danych przez
  migrację. ID, hash, status i czas pozostają. Rekordy są nadal trwałymi
  znacznikami deduplikacji; liczba wpisów rośnie, oryginalna treść nie jest archiwizowana.
- [x] Rzeczywiste store/inbox zachowują ochronę powtórzeń/konfliktu hashy;
  redelivery nie przywraca diagnostyki. Przyjęty receipt, faktura z pozycją
  i outbox faktur pozostały niezmienione. Protokół ACK/retry bez zmian.
- [x] Polityka i okres inboxu niezależne od retencji outboxu faktur w obu
  kierunkach. Odrębne metryki, budżety i aktywacja. Dotychczasowy mechanizm
  A43.5 zachowany i jego testy PostgreSQL przeszły z migracją V11.
- [x] Materializowane partie z SKIP LOCKED i REQUIRES_NEW; limity, widoczna
  zaległość mimo blokady, rollback własnej partii, liczniki wcześniejszych
  commitów i odzyskanie po awarii. Dziesięć metryk bez etykiet klientów/zdarzeń.
- [x] **168 jednostkowych Java + 15 PostgreSQL = 183 — PASS**, bez pominiętych,
  w tym 19 nowych (11 jednostkowych i osiem PostgreSQL). Piętnaście zawiera
  siedem istniejących PostgreSQL A43.5 — nie sumować ponownie. Obie własne
  bazy wykonały V1–V11 i walidację JPA. Granice, constraints, redelivery,
  niezależność polityk, blokady, współbieżność, budżet i rollback — PASS.
- [x] 12 Kubernetes, spójność sześciu parametrów w trzech formatach,
  bramki struktury Maven/korelacji/odporności i kontrola 15 JAR-ów — PASS.
  Wykorzystano istniejące zależności/profil integration; bez nowych zależności.
- [ ] Okno diagnostyki i aktywacja compact — do decyzji użytkownika.
  Nie uruchomiono kompaktowania prawdziwych danych. Przed aktywacją wdrożyć
  V11 i kod do wszystkich instancji Billing.
- [ ] Całe A43: polityki/aktywacje, dalsza retencja zdarzeń, przyjętych
  inboxów i powiadomień; pełny eksport/usuwanie/anonimizacja usług i kopii.

Instrukcja: [A43.11 — odrzucone zdarzenia Billing](A43_RETENCJA_ODRZUCONYCH_ZDARZEN_BILLING.md).
Raport: `tmp/audit-a43-11/report.json`. Własne bazy/fixture, bez brokera,
dostawcy płatności, dokumentów użytkownika i restartu CC. Bez nowych
sekretów, UI, przeglądarek i wdrożenia dev/prod. A05, całe A41 i zmiany
samych zdjęć nadal odłożone.

## Pakiet 69 — A43.12: retencja treści wysłanych powiadomień (27.10.2026)

- [x] Osobne observe/compact/off, sześć parametrów w aplikacji, Compose
  i Kubernetes; domyślnie obserwacja z progiem 30 dni. Walidacja startu,
  wyłączenie automatycznych zapytań w profilu test i niezależność od A43.10.
- [x] Kompaktowanie tylko starego subject/message SENT z czasem wysyłki,
  bez dzierżaw, błędu i terminu retry. Najpóźniejsza z dat utworzenia,
  aktualizacji i wysyłki musi być ściśle starsza od granicy. Pozostałe stany chronione.
- [x] V12: znacznik, CHECK i częściowy indeks; migracja nie czyści treści.
  ID, odbiorca, unikalność klucza, referencja dostawcy, status i daty zostają.
  Liczba rekordów nadal rośnie; DLQ/inboxy/czat i kopie mają osobny cykl życia.
- [x] API dodaje contentCompacted oraz jawny tekst po usunięciu treści.
  Pełne rekordy zachowują dotychczasowy temat/body. Zachowane uprawnienia,
  trasy i SENT; bez nowego widoku CC ani zmian JS.
- [x] Powtórzone create, retry, redelivery i stare complete nie przywracają
  treści ani wysyłki. Załadowana skrócona encja jest odrzucana przed SMTP/PDF.
  FAILED zachowuje treść do replay; po sukcesie SENT można kompaktować,
  kopia DLQ zostaje. Replay SENT nie wywołuje dostawcy.
- [x] Materializowane partie z SKIP LOCKED i REQUIRES_NEW, limity i widoczna
  zaległość przy blokadzie, rollback własnej partii, liczniki wcześniejszych
  commitów i odzyskanie. Dziesięć metryk bez etykiet odbiorców/zdarzeń.
- [x] **148 jednostkowych Java + 17 PostgreSQL = 165 — PASS**, bez pominiętych,
  w tym 24 nowe (15 jednostkowych i dziewięć PostgreSQL). Siedemnaście
  zawiera osiem wcześniejszych PostgreSQL A43.10 — nie sumować ponownie.
  Obie własne bazy wykonały V1–V12 i JPA; granice, constraints, deduplikacja,
  API, dostawcy, replay/DLQ, polityki, blokady, budżet i rollback — PASS.
- [x] 12 Kubernetes, spójność sześciu parametrów w trzech formatach,
  bramki Maven/korelacji/odporności i kontrola 15 JAR-ów — PASS.
  Bez nowych zależności; istniejący profil integration/CI obejmuje nowe testy.
- [ ] Docelowe okno historii treści i compact — do decyzji użytkownika.
  Nie aktywowano kompaktowania prawdziwych danych. Przed aktywacją wdrożyć
  V12 i kod do wszystkich instancji Notification.
- [ ] Całe A43: polityki/aktywacje, dalsza retencja inboxów/DLQ/zdarzeń,
  eksport/usuwanie/anonimizacja usług i kopii. Odbiorca i kopie treści pozostają.

Instrukcja: [A43.12 — treść wysłanych powiadomień](A43_RETENCJA_TRESCI_WYSLANYCH_POWIADOMIEN.md).
Raport: `tmp/audit-a43-12/report.json`. Własne bazy/fixture, bez wysyłki
wiadomości, rzeczywistego brokera, danych użytkownika i restartu CC.
Bez nowych sekretów, przeglądarek i wdrożenia dev/prod. A05, całe A41
i zmiany samych zdjęć nadal odłożone.

## Pakiet 70 — A43.13: retencja treści zamkniętych wpisów DLQ (27.10.2026)

- [x] Osobne observe/compact/off i sześć parametrów w aplikacji, Compose
  i Kubernetes. Domyślnie obserwacja/30 dni; walidacja startu i wyłączenie
  automatycznych zapytań w profilu test. Polityki DLQ/SENT/REJECTED niezależne.
- [x] Kompaktowanie subject/message/failure_reason wyłącznie starych RESOLVED
  ze starym potwierdzonym SENT bez dzierżaw/retry/błędu. Ścisłe granice
  sześciu dat rodzica i kopii; OPEN, niewysłane, osierocone/niespójne i świeże wpisy chronione.
- [x] V13: znacznik, CHECK, częściowy indeks i row_version/@Version.
  Retencja atomowo zwiększa wersję; stary detached merge jest odrzucany.
  Rekord, ID, notificationId, odbiorca, status i daty pozostają; liczba wpisów nadal rośnie.
- [x] Replay/resolve/record odświeżają aktualny wpis pod blokadą; resolve
  nie scala starej encji i nie przesuwa ponownie daty RESOLVED. SENT nie
  wysyła ponownie. Niespójny replay skróconej kopii daje 409; OPEN/FAILED
  i replay w toku zachowują pełne dane. DTO ma contentCompacted/tekst pominięcia.
- [x] Materializowane partie z SKIP LOCKED i REQUIRES_NEW, limity i jawna
  zaległość, rollback własnej partii/liczniki wcześniejszych commitów oraz
  odzyskanie. Collector blokuje tylko DLQ i obserwuje terminalny SENT przez
  EXISTS, bez odwracania blokad rodzic/dziecko. Dziesięć metryk bez danych odbiorców.
- [x] **163 jednostkowe Java + 29 PostgreSQL = 192 — PASS**, bez pominiętych,
  w tym 27 nowych (15 jednostkowych i 12 PostgreSQL). Wynik obejmuje
  wcześniejsze 17 PostgreSQL A43.10/A43.12 — nie sumować ponownie.
  Trzy własne bazy wykonały V1–V13/JPA. Granice, constraints, wersjonowanie,
  stare encje, replay, blokady, budżet, rollback i obie kolejności retencji — PASS.
- [x] Pierwszy odbiór wykrył konflikt starej zarządzanej wersji przy zapytaniu
  blokującym przed refresh. Poprawka: zwykły odczyt i refresh z PESSIMISTIC_WRITE.
  Wskazany PostgreSQL i dziesięć jednostkowych przeszły, następnie pełna
  regresja powyżej — PASS. Przypadków powtórnych nie sumować do 192.
- [x] 12 Kubernetes, spójność sześciu parametrów w trzech formatach,
  bramki Maven/korelacji/odporności i kontrola 15 JAR-ów — PASS.
  Bez nowych zależności; dotychczasowy profil integration/CI obejmuje nowe testy.
- [ ] Okno historii/diagnostyki DLQ i compact — do decyzji użytkownika.
  Nie aktywowano czyszczenia prawdziwych danych. Przed aktywacją wdrożyć
  V13 i kod do wszystkich instancji Notification, także ze względu na @Version.
- [ ] Całe A43: docelowe polityki/aktywacje, pozostały cykl życia inboxów,
  zdarzeń i znaczników, eksport/usuwanie/anonimizacja usług i kopii.
  Odbiorcy i pozostałe kopie treści nadal wymagają własnych zasad.

Instrukcja: [A43.13 — treść zamkniętych DLQ](A43_RETENCJA_TRESCI_ZAMKNIETYCH_DLQ.md).
Raport: `tmp/audit-a43-13/report.json`. Własne bazy/fixture, bez wysyłki
wiadomości, rzeczywistego brokera, danych użytkownika i restartu CC.
Bez nowych sekretów, widoku CC/JS, przeglądarek i wdrożenia dev/prod.
A05, całe A41 i zmiany samych zdjęć nadal odłożone.

## Pakiet 71 — A43.14: retencja streamu zdarzeń faktur w Redis (27.10.2026)

- [x] Osobne off/observe/trim, osiem parametrów w aplikacji, Compose
  i Kubernetes; domyślnie tylko obserwacja/30 dni. Brak migracji SQL,
  nowych zależności i zmian platform-security. Profil test wyłącza moduł.
- [x] Wymagana jawna polityka: deklaracja wymaganych grup lub świadome
  ALLOW_NO_GROUPS=true. Domyślnie pusta lista/false blokuje usuwanie.
  Brak zadeklarowanej grupy blokuje nawet po dopuszczeniu braku grup.
- [x] Atomowy skrypt chroni last-delivered-id i najstarszy pending wszystkich
  faktycznych grup, także dodatkowych. Zachowuje samą granicę, unread/PEL,
  klucz i metadane. Nie tworzy grup, nie ACK-uje i nie zmienia offsetów.
- [x] XRANGE z wyłączną granicą/COUNT i XDEL wybranych ID dają twardy limit
  rekordów. Do 500/partię i cztery partie; limit grup 32 sprawdzany przed
  rozwinięciem listy. Do Java wraca siedem pól metadanych, bez payloadu/nazw grup.
- [x] Osobne informacje o starych i możliwych do usunięcia wpisach, kod
  blokady i 14 metryk bez etykiet zdarzeń/grup. Błąd zachowuje poprzednią
  migawkę i liczniki potwierdzonych odpowiedzi; po odzyskaniu wraca do stanu.
- [x] **181 jednostkowych Java + 15 PostgreSQL + 12 Redis = 208 — PASS**,
  bez pominiętych. Nowych: 25 (13 jednostkowych i 12 Redis). Wcześniejsze
  15 PostgreSQL A43.5/A43.11 jest już uwzględnione — nie sumować ponownie.
  Redis 7.4.9: polityki, missing/extra groups, pending/unread/ACK, granica
  wieku, duże ID, makrowęzły, pusty stream, prawdziwy publisher, współbieżność
  i błędny typ/odzyskanie — PASS. Bazy wykonały V1–V11/JPA.
- [x] Pierwszy odbiór wykrył dwa błędy zakresu poniżej 0-0. Jawne pominięcie
  usuwania na zerowej granicy naprawiło przypadek. Powtórne 13 jednostkowych
  i 12 Redis, następnie pełna regresja powyżej — PASS; nie sumować powtórzeń.
- [x] 12 Kubernetes, spójność ośmiu parametrów w trzech formatach,
  bramki Maven/korelacji/odporności i kontrola 15 JAR-ów — PASS.
  Istniejący profil integration/CI obejmuje nowe testy.
- [ ] Użytkownik ustala okno historii/odtwarzania streamu i wymaganych
  odbiorców (REQUIRED_GROUPS albo jawne ALLOW_NO_GROUPS), potem aktywuje trim.
  Nie włączono usuwania na brokerze aplikacji. Nowa grupa/odczyt XRANGE
  nie odzyska wcześniej usuniętej historii; to część decyzji polityki.
- [ ] Całe A43: pozostałe polityki/aktywacje, cykl życia inboxów/zdarzeń/
  znaczników oraz eksport/usuwanie/anonimizacja usług i kopii. XDEL jest
  usunięciem logicznym; fizyczna pamięć, AOF/RDB, SQL i backupy mają osobne zasady.

Instrukcja: [A43.14 — stream faktur](A43_RETENCJA_STREAMU_FAKTUR.md).
Raport: `tmp/audit-a43-14/report.json`. Własne kontenery PostgreSQL/Redis
i fixture, bez brokera/dokumentów użytkownika i restartu CC. Bez nowych
sekretów, UI/JS, przeglądarek i wdrożenia dev/prod. A05, całe A41 i zmiany
samych zdjęć nadal odłożone.

## Pakiet 72 — A43.15: rejestr cyklu życia danych i bramka kompletności (27.10.2026)

- [x] Kontrakt `scripts/qa/contracts/data-lifecycle.json`: 14 właścicieli baz,
  49 tabel i 120 migracji; klasy danych, relacje z klientem, istniejące
  mechanizmy i ograniczenia retencji oraz status planu eksportu/usunięcia.
- [x] Pięć właścicieli i przestrzeni Redis, dziesięć źródeł Java Redis;
  15 rodzajów kopii obejmuje backupy, AOF/RDB, runtime/logi/CC, cache,
  przeglądarkę/pobrania, dostawców, keyringi i sesje operatora. Referencje
  22 mechanizmów istnieją w repozytorium. Nie czytano danych tych magazynów.
- [x] Bramka CI wykrywa nieprzejrzane migracje (także zmiany kolumn przez hash),
  nieopisane/usunięte tabele, nowe/zmienione odwołania Redis, brak właściciela,
  niebezpieczny eksport credential data i brak kategorii kopii. Opisy relacji
  pozostają do ręcznego przeglądu; bramka nie jest pełnym parserem ani oceną prawną.
- [x] Ochrona źródeł i wyjścia: workspace, limity rozmiaru, duplikaty JSON,
  symlink wychodzący poza root, brak nadpisania istniejącego raportu.
  CLI działa z wyłączonymi socketami/procesami i nie zmienia wejść fixture.
- [x] Nowy krok CI; kontrakt poza docs, więc zmiana samego JSON nie jest
  ukryta przez paths-ignore. Brak opcji automatycznej akceptacji snapshotów.
  Po przeglądzie SQL/Redis aktualizować opisy i hashe ręcznie.
- [x] **245 Python — PASS, bez pominiętych**, w tym 26 nowych. Wynik zawiera
  dotychczasowe 12 Kubernetes — nie sumować ponownie. Osobne 26 nowych
  także PASS, bez zwiększania sumy. Mutanty, lexer, ścieżki, CLI i CI sprawdzone.
  Po dopisaniu metadanych keyringów/sesji ponowiono bramkę — PASS.
- [x] Struktura CC: 15 modułów, 54 fasady i jeden HTTP — PASS. Bez nowych
  zależności, zmian Java/JS, migracji SQL, endpointów i widoku CC; nie
  ponawiano Maven/przeglądarek ani zdalnego CI.
- [ ] Eksport konta, usunięcie/anonimizacja i selektywna obsługa backupów
  nadal **pending**. Kontrakt nie stanowi ich implementacji. Do wykonania:
  zweryfikowane rozwiązanie podmiotu we wszystkich usługach, bezpieczne DTO/
  redakcja, uzgodnione skutki domenowe, kopie i ponowne zastosowanie żądań po restore.
- [ ] Docelowe okresy i aktywacje istniejących retencji oraz dalszy cykl
  życia rekordów/znaczników pozostają do uzgodnienia. Nie włączono czyszczenia.

Instrukcja: [A43.15 — rejestr i bramka](A43_REJESTR_CYKLU_ZYCIA_DANYCH.md).
Raport: `tmp/audit-a43-15/report.json`. Zero operacji nowej bramki na danych
aplikacji; wyłącznie metadane źródeł i własne fixture. Bez czytania sekretów,
runtime/backupów użytkownika, restartu CC i wdrożenia dev/prod. A05,
całe A41 oraz zmiany samych zdjęć nadal odłożone.

## Pakiet 73 — A43.16: eksport profilu konta i podsumowania zabezpieczeń (28.10.2026)

- [x] `GET /customers/me/data-export`, dostępny przez `/api` i `/api/v1`:
  JSON profilu właściciela i agregatów TOTP/passkeyów/nieużytych kodów.
  `complete: false` oraz `omissions` wskazują pozostały zakres.
- [x] Tożsamość wyłącznie z uwierzytelnionego principal; parametry i nagłówki
  nie wybierają klienta. Operator/usługa/anonim i nieważne konta nie mają dostępu.
  SQL ponownie sprawdza aktualne ID, e-mail i aktywność.
- [x] Jawne kolumny i DTO, jedno zapytanie/snapshot, jeden wynik i timeout 2 s.
  Bez hasła/hasha, tokenów, sekretu TOTP, kodów/hashy odzyskiwania,
  identyfikatorów/kluczy passkeyów i attestation. Bez pobierania list encji.
- [x] Załącznik ze stałą nazwą, private/no-store i nosniff; Gateway zachowuje
  nagłówki. Serwer nie zapisuje archiwum ani nie tworzy zadania eksportu.
- [x] PostgreSQL potwierdza eksport na roli bez prawa odczytu kolumn
  credential, zakres właściciela, kanoniczne kodowanie WebAuthn, puste MFA,
  null telefonu, zużyte kody i niezmienność danych. Pierwszy fixture powielał
  unikalny hash; naprawiono dane testowe i ponowiono pełny odbiór Maven.
- [x] **239 Java — PASS, bez pominiętych**, w tym 15 nowych (8 unit/HTTP,
  7 PostgreSQL): 108 platform, 33 Gateway, 67 Customer unit, 31 Customer PG.
- [x] **246 Python — PASS, bez pominiętych**, w tym jeden nowy; istniejące
  12 Kubernetes zawarte w wyniku. Osobne 27 lifecycle PASS — nie sumować ponownie.
- [x] Uruchomione JAR-y: port Customer i oba aliasy Gateway — PASS;
  odseparowane konta, próby podmiany właściciela, sześć odmów, nagłówki,
  pominięcia/sekrety, brak zmian danych i kontrakt OpenAPI.
- [x] CI uruchamia nowy odbiór HTTP. Rejestr: cztery `partial-safe-projection`,
  23 referencje mechanizmów i pobrany JSON jako kopia użytkownika. Bramka PASS;
  nie zmieniano hashy migracji/Redis. Globalne operacje nadal pending.
- [ ] Pełny eksport między usługami, bezpieczna historia zabezpieczeń
  i redakcja audytu; usuwanie/anonimizacja, skutki domenowe/deduplikacja,
  backupy/kopie zewnętrzne i ponowne zastosowanie żądań po restore.
- [ ] Docelowe okresy i aktywacje retencji oraz pozostały cykl życia danych
  i znaczników pozostają otwarte. Nie włączono nowego czyszczenia.

Instrukcja: [A43.16 — eksport profilu](A43_EKSPORT_PROFILU_KONTA.md).
Raport: `tmp/audit-a43-16/report.json`; HTTP:
`tmp/account-data-export-ab21b1dc/report.json`. Wyłącznie własne konta/klucze
fixture i własne kontenery, bez odczytu/zmian kont użytkownika lub sekretów.
W pakiecie 73 bez nowych zależności, migracji, zmiennych i widoku CC/sklepu. Bez restartu
działającego CC, obrazów, zdalnego CI i wdrożenia dev/prod. A05, całe A41
oraz zmiany samych zdjęć nadal odłożone.

## Pakiet 74 — A43.17: pobieranie danych profilu w sklepie (28.10.2026)

- [x] Przycisk **Pobierz dane profilu (JSON)** u góry panelu profilu; opis
  wskazuje zapisany profil/podsumowanie zabezpieczeń i brak zamówień oraz
  danych innych usług. Pobranie nie zapisuje niezapisanych pól formularza.
- [x] Istniejący transport i jedno odświeżenie sesji; błąd odnowienia
  pokazuje logowanie. Stan pobierania, błąd i ponowienie mają komunikaty PL/EN.
- [x] Podwójne kliknięcia współdzielą operację. Logout, zmiana konta,
  wygaśnięcie i pagehide przerywają pobieranie; spóźniona odpowiedź nie
  tworzy pliku. Tokeny i wynik nie są zapisywane przez funkcję do Web Storage.
- [x] Kontrola typu/rozmiaru otrzymanego Blob, wersji, zakresu, niepełnego
  eksportu i właściciela; niebezpieczne identyfikatory liczbowe odrzucane.
  Object URL jest zwalniany; treść błędów serwera nie jest renderowana.
- [x] **210 JavaScript — PASS**, w tym 12 nowych. **36 przeglądarkowych — PASS**:
  9 scenariuszy w Chromium/Firefox/WebKit/mobilnym Chrome. **35 regresyjnych
  Chromium — PASS**: logowanie, dostępność, faktury i podstawowe strony.
- [x] Kontrole klawiatury, dostępnej nazwy/opisu i axe obszaru profilu;
  przegląd widoku desktop/mobile. Nie stanowi to deklaracji pełnego WCAG.
  Po doprecyzowaniu błędu niebezpiecznego ID ponowiono jednostkowe i 36 testów
  eksportu; wcześniejsza regresja 35 dotyczy stanu przed tym doprecyzowaniem.
- [x] **27 testów rejestru — PASS**, bieżąca bramka PASS, 24 mechanizmy;
  pobranie JSON pozostaje kopią zarządzaną przez użytkownika. CI obejmuje
  nowe scenariusze w czterech profilach przeglądarki.
- [ ] Pełny eksport danych innych usług, historia bezpieczeństwa/redakcja
  audytu, usuwanie/anonimizacja, skutki domenowe oraz backupy/kopie i restore.
  Polityki/aktywacje retencji i dalszy cykl znaczników pozostają otwarte.

Instrukcja: [A43.17 — pobieranie w sklepie](A43_POBIERANIE_PROFILU_W_SKLEPIE.md).
Raport: `tmp/audit-a43-17/report.json`. Odbiór na fikcyjnych danych i własnym
serwerze testowym, bez zmian w bazach lub danych kont użytkownika. Bez nowych
endpointów, zależności, migracji i zmiennych; bez restartu CC, zdalnego CI i
wdrożenia dev/prod. A05, całe A41 oraz zmiany samych zdjęć nadal odłożone.

## Pakiet 75 — A43.18: stronicowany eksport Wishlist (28.10.2026)

- [x] `GET /wishlists/me/data-export`, także przez `/api` i `/api/v1`:
  strona maksymalnie 100 zapisanych produktów własnego konta, z `productId`,
  `createdAt`, `hasMore` i tekstowym `nextCursor`. Parametry/nagłówki nie
  wybierają właściciela; operator, usługi i anonim nie mają dostępu.
- [x] Jawny częściowy zakres `complete: false`, pozostałe dane konta i brak
  spójnej migawki pomiędzy stronami wskazane w `omissions`. Strony nie są
  dołączane do pliku profilu A43.16 i nie tworzą pełnego eksportu konta.
- [x] Ograniczone zapytanie po właścicielu i ID, `size + 1`, timeout 2 s,
  nagłówki załącznika/private-no-store/nosniff. Migracja V2 dodaje indeks;
  standardowy CREATE INDEX może blokować zapisy podczas budowy i wymaga
  uwzględnienia przy wdrożeniu istniejącej bazy.
- [x] **151 Java — PASS, bez pominiętych**, w tym dziewięć nowych: 109
  platform, 33 Gateway, 4 Wishlist unit i 5 Wishlist PostgreSQL w profilu
  integration. Zwykłe testy Wishlist nie wymagają Dockera.
- [x] **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
  Bramka rejestru PASS: 121 migracji, 25 mechanizmów; nowy hash tylko po
  przeglądzie indeksu, tabela Wishlist jako częściowa projekcja.
- [x] JAR-y Wishlist/Customer/Gateway na izolowanych bazach — PASS:
  205 pozycji/trzy strony, dwa konta, sześć odmów, walidacja okien, nagłówki,
  niezmienność danych i OpenAPI. Gateway zwraca 405 dla nieobsługiwanego POST,
  bezpośrednia usługa 403; oba warianty zostały uwzględnione w odbiorze.
- [x] CI uruchamia odbiór HTTP i profil integracyjny. Bez dodania przycisku
  sklepu ani nowego widoku CC; strony JSON są kopiami zarządzanymi przez klienta.
- [ ] Pełny eksport między usługami, wspólna migawka/obsługa zmian, historia
  bezpieczeństwa/redakcja audytu, usunięcie/anonimizacja i kopie/restore.
  Polityki retencji i pozostały cykl życia danych nadal wymagają decyzji/prac.

Instrukcja: [A43.18 — eksport Wishlist](A43_EKSPORT_WISHLISTY.md).
Raport: `tmp/audit-a43-18/report.json`; HTTP:
`tmp/wishlist-export-c41d6964/report.json`. Wyłącznie własne konta i bazy
fixture; brak migracji na bazach użytkownika, restartu CC, zdalnego CI i
wdrożenia dev/prod. A05, całe A41 oraz zmiany samych zdjęć nadal odłożone.

## Pakiet 76 — A43.19: stronicowany eksport własnych recenzji (28.10.2026)

- [x] `GET /reviews/me/data-export`, dostępny przez `/api` i `/api/v1`:
  własne treści, oceny i statusy PENDING/PUBLISHED/REJECTED, maksymalnie
  50 pozycji na stronę. Podmiot wyłącznie z uwierzytelnienia; brak dostępu
  dla operatora/usługi/anonima i brak sterowania właścicielem przez query/header.
- [x] Jawne SQL/DTO bez `moderation_note`, `order_id` i `customer_id` w wyniku.
  Rola PostgreSQL bez prawa SELECT notatek i powiązań zamówień może eksportować;
  próby bezpośredniego odczytu tych kolumn są odrzucane.
- [x] Tekst autora jest zwracany dosłownie, może zawierać dane innych osób
  i nie podlega semantycznej redakcji. Manifest wskazuje tę granicę, pozostałe
  dane konta oraz brak wspólnej migawki stron. Eksport pozostaje częściowy.
- [x] Zapytanie `size + 1`, timeout 2 s, tekstowy kursor, prywatny załącznik
  JSON/no-store/nosniff. Wykorzystano istniejący indeks właściciela i ID;
  nie dodano migracji ani nie zmieniono hashy migracji/Redis.
- [x] **162 Java — PASS, bez pominiętych**, w tym 11 nowych: 110 platform,
  33 Gateway, 12 Review unit i siedem PostgreSQL w profilu integration.
- [x] **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
  Bramka rejestru PASS: 26 mechanizmów, recenzje jako częściowa projekcja.
  Wspólny układ Maven 17 modułów — PASS.
- [x] Uruchomione JAR-y na izolowanych bazach — PASS: 105 recenzji/trzy strony,
  dwa konta, wszystkie statusy, sześć odmów, cztery błędne okna, nagłówki,
  wykluczone pola, UTF-8, niezmienność danych i OpenAPI; port Review i oba aliasy.
- [x] CI obejmuje nowy odbiór HTTP i testy PostgreSQL. Bez nowego przycisku
  sklepu/widoku CC; plik profilu A43.16 pozostaje osobnym eksportem.
- [ ] Pełny eksport usług/wspólna migawka, historia bezpieczeństwa/redakcja
  audytu, usunięcie/anonimizacja, skutki domenowe i kopie/restore. Polityki
  retencji i pozostały cykl danych nadal wymagają decyzji/prac.

Instrukcja: [A43.19 — eksport recenzji](A43_EKSPORT_RECENZJI.md).
Raport: `tmp/audit-a43-19/report.json`; HTTP:
`tmp/review-export-62904bbd/report.json`. Wyłącznie własne fixture i kontenery,
bez danych kont użytkownika, nowych zależności/sekretów/zmiennych, restartu CC,
zdalnego CI i wdrożenia dev/prod. A05, całe A41 oraz zmiany samych zdjęć
pozostają odłożone.

## Pakiet 77 — A43.20: eksport zachowanego logu rekomendacji (28.10.2026)

- [x] `GET /recommendations/me/data-export`, także przez `/api` i `/api/v1`:
  zachowane wiersze przypisane do zalogowanego klienta, do 100 na stronę.
  Właściciel pochodzi z uwierzytelnienia; query/header nie wybierają innego
  konta, a operator/usługi/anonim nie mają dostępu.
- [x] Jawny częściowy zakres, `recordingEnabled` i `historyCoverage` oraz
  manifest wyjaśniają opcjonalny log, niedostępne/purged wpisy i brak wspólnej
  migawki. Puste dane nie oznaczają braku wcześniejszej aktywności.
- [x] Wyłączony zapis nie ukrywa zachowanych wierszy; eksport nie włącza
  zapisu, nie zmienia retencji ani nie wylicza nowych rekomendacji. NULL
  sourceProductId pozostaje null; anonimowe customer_id nie jest odgadywane.
- [x] Jawne kolumny, `size + 1`, timeout 2 s, tekstowy kursor i prywatny
  załącznik JSON/no-store/nosniff. Migracja V3 dodaje indeks właściciela/ID;
  standardowa budowa indeksu może czasowo blokować zapisy przy wdrożeniu.
- [x] **175 Java — PASS, bez pominiętych**, w tym 11 nowych: 111 platform,
  33 Gateway, 20 Recommendation unit i 11 PostgreSQL. Cztery dotychczasowe
  testy PostgreSQL retencji są zawarte w 11 i również PASS.
- [x] **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
  Bramka rejestru PASS: 122 migracje i 27 mechanizmów. Nowy hash wpisano po
  przeglądzie indeksu; pozostałe hashe zachowane. Maven 17 modułów — PASS.
- [x] JAR-y na izolowanych bazach — PASS: 205 wpisów/trzy strony, dwa konta
  z danymi, konto bez logu, wpis anonimowy, nullable product ID, sześć odmów,
  cztery błędne okna, nagłówki, niezmienność danych i OpenAPI, trzy ścieżki HTTP.
- [x] CI obejmuje nowy odbiór i profil integracyjny. Bez przycisku sklepu/
  widoku CC i bez dołączania logów do pliku profilu A43.16.
- [ ] Pełny eksport między usługami/wspólna migawka, historia bezpieczeństwa,
  redakcja audytu, usunięcie/anonimizacja, skutki domenowe i kopie/restore.
  Polityki oraz dalszy cykl życia danych nadal wymagają decyzji/prac.

Instrukcja: [A43.20 — eksport logu rekomendacji](A43_EKSPORT_LOGU_REKOMENDACJI.md).
Raport: `tmp/audit-a43-20/report.json`; HTTP:
`tmp/recommendation-export-7e868efe/report.json`. Wyłącznie własne fixture;
bez migracji na bazach użytkownika, zmian polityk zapisu/retencji, nowych
zależności/sekretów/zmiennych, restartu CC, zdalnego CI i wdrożenia dev/prod.
A05, całe A41 oraz zmiany samych zdjęć nadal odłożone.

## Pakiet 78 — A43.21: eksport własnych zamówień i pozycji (28.10.2026)

- [x] `/orders/me/data-export` i `/orders/me/data-export/{orderId}/items`,
  także `/api` i `/api/v1`: osobne strony do 50 wierszy, bez wczytywania
  wszystkich pozycji dużego zamówienia przy pobieraniu nagłówków.
- [x] Właściciel z uwierzytelnienia; operator/usługa/anonim odrzuceni.
  Query/header nie zmienia podmiotu. Zamówienia gościa nie są łączone
  z kontem przez zgodny e-mail ani poprawny guest capability.
- [x] Jedno zapytanie pozycji sprawdza własność i pobiera ograniczoną stronę
  w tej samej migawce. Własne puste zamówienie daje pustą listę; cudze,
  gościnne i brakujące zamówienie jednakowe 404.
- [x] Jawne kolumny/DTO bez kluczy idempotencji, hashy dostępu/checkoutu,
  koordynacji sagi i kolejek. Rola PostgreSQL bez SELECT tych pól i tabel
  sagi może eksportować oba zakresy. Zapisane kwoty, dane odbiorcy i
  historyczne pola pojedynczej pozycji zachowane; brak semantycznej redakcji.
- [x] `size + 1`, timeout 2 s, kursor tekstowy, prywatne załączniki/no-store/
  nosniff. Istniejący indeks nagłówków, V22 dodaje indeks stron pozycji;
  standardowa budowa może blokować zapisy podczas wdrożenia.
- [x] **242 Java — PASS, bez pominiętych**, w tym 16 nowych: 112 platform,
  33 Gateway, 86 Order unit i 11 PostgreSQL (dziesięć nowych, jeden sagi).
- [x] **246 Python — PASS, bez pominiętych**, w tym dotychczasowe 12 Kubernetes.
  Po legacyLine ponowiono pełne Java/Python na ustalonym kontrakcie; wcześniejsza
  niezgodna kopia testowa kontraktu nie jest liczona jako PASS.
- [x] JAR-y na izolowanych bazach — PASS: 55 nagłówków/105 pozycji, dwa konta
  i gość, dane historyczne, empty/not-found, guest capability, sześć odmów,
  błędne okna, nagłówki, brak zmian i OpenAPI; port Order i oba aliasy Gateway.
- [x] Rejestr/CI obejmują eksport; bramka PASS, 123 migracje/28 mechanizmów.
  Dwie tabele jako częściowe projekcje, saga pozostaje poza eksportem.
  Maven 17 modułów — PASS. Bez nowego przycisku sklepu/widoku CC.
- [ ] Pełny eksport między usługami/wspólna migawka, pozostałe dane procesów,
  historia bezpieczeństwa/redakcja audytu, usunięcie/anonimizacja, skutki
  domenowe, kopie/restore i docelowe polityki retencji pozostają otwarte.

Instrukcja: [A43.21 — eksport zamówień](A43_EKSPORT_ZAMOWIEN.md).
Raport: `tmp/audit-a43-21/report.json`; HTTP:
`tmp/order-export-039469f0/report.json`. Wyłącznie własne fixture/kontenery,
bez danych kont użytkownika, migracji na ich bazach, nowych zależności/
sekretów/zmiennych, zmian domenowych/retencji, restartu CC, zdalnego CI i
wdrożenia dev/prod. A05, całe A41 i zmiany samych zdjęć nadal odłożone.


## Pakiet 79 — A43.22: eksport danych własnej przesyłki (28.10.2026)

- [x] `/deliveries/me/data-export/orders/{orderId}`, także `/api` i `/api/v1`:
  jedna przesyłka, jawna projekcja bieżących danych, complete false i omissions.
- [x] Wyłącznie uwierzytelniony klient; operator/usługa/anonim odrzuceni.
  Delivery sprawdza właściciela w Order na każde żądanie, własnym tokenem
  usługi, bez przesyłania JWT klienta i capability gościa. E-mail/query/header
  nie przypisują zamówienia do konta. Dotychczasowy dostęp do trackingu bez zmian.
- [x] Cudze/gościnne/brakujące zamówienie daje jednakowe 404, własne bez
  przesyłki także 404. Awaria, błędne uprawnienia, pusta/uszkodzona odpowiedź
  autoryzacji dają 503, bez dostępu do bazy przesyłek i bez fallbacku/cache.
- [x] Jedna odpowiedź przez istniejący unikalny indeks order_id, maksymalnie
  jeden wiersz i timeout SQL 2 s. Bez SELECT pól synchronizacji/tokenów,
  lease, retry/błędów i rewizji; potwierdzone rolą PostgreSQL bez tych praw.
  Bez zmiany statusu, prób synchronizacji i danych zamówienia.
- [x] Prywatny załącznik/no-store/nosniff. Weryfikacja Order i odczyt Delivery
  są osobne, bez wspólnej migawki; wcześniejsze stany nie są przechowywane
  w tabeli. Pobrany JSON jest kopią zarządzaną przez użytkownika.
- [x] **275 Java — PASS, bez pominiętych**, w tym 19 nowych: 121 platform,
  33 Gateway, 86 Order unit, 11 Order PostgreSQL, 19 Delivery unit i pięć
  Delivery PostgreSQL. Nowe: osiem kontroli właściciela, jedna polityki ról,
  cztery HTTP, jedna kolejności autoryzacja/SQL i pięć PostgreSQL.
- [x] **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
  Maven 17 modułów i rejestr — PASS: 49 tabel/123 migracje/29 mechanizmów.
- [x] JAR HTTP — PASS: trzy ścieżki, dwa konta i gość z tym samym e-mailem,
  capability, brak przesyłki/zamówienia, sześć odmów, nagłówki/UTF-8/OpenAPI
  i niezmienność danych. Po zatrzymaniu własnego Order wszystkie ścieżki
  zwracają 503 bez ujawnienia danych przesyłki. Odbiór dodany do CI.
- [x] Dwa błędy przygotowania atrap poprawiono; PASS dotyczy końcowego,
  pełnego przebiegu Java. Nie liczymy nieudanych prób jako dowodu odbioru.
- [ ] Pełny eksport między usługami, historia bezpieczeństwa/redakcja audytu,
  usunięcie/anonimizacja, kopie/restore i docelowe polityki retencji nadal otwarte.

Instrukcja: [A43.22 — eksport przesyłek](A43_EKSPORT_PRZESYLEK.md).
Raport: `tmp/audit-a43-22/report.json`; HTTP: `tmp/shipment-export-ea0046b0/report.json`.
Wyłącznie własne fixture/kontenery. Bez migracji, nowych zależności runtime,
sekretów/zmiennych, zmian retencji/danych użytkownika, restartu CC i wdrożenia
dev/prod. Delivery otrzymało trzy zależności Testcontainers i profil Failsafe
wyłącznie do testów. A05, całe A41 i zmiany samych zdjęć nadal odłożone.


## Pakiet 80 — A43.23: eksport własnych zwrotów i reklamacji (28.10.2026)

- [x] `/returns/me/data-export`, osobne `/{returnId}/items` i
  `/{returnId}/events`, również `/api` i `/api/v1`: strony maksymalnie
  50 zgłoszeń, pozycji lub statusów; complete false i jawne omissions.
- [x] Właściciel po uwierzytelnionym customer_id; operator/usługa/anonim
  odrzuceni. E-mail, query, nagłówki i capability gościa nie wybierają konta.
  Pełna dotychczasowa historia operatora pozostaje prywatna.
- [x] CTE i lateral join sprawdzają właściciela dzieci w tej samej migawce
  SQL. Cudza/brakująca sprawa daje jednakowe 404, własna pusta lub strona
  po końcu listy daje 200. Eksport zapisanej sprawy nie wymaga Order.
- [x] Jawne kolumny bez notatek i identyfikatorów operatorów, hashy,
  wersji, UUID/oczekiwanej wersji operacji i wewnętrznych stanów Order/Payment.
  Potwierdzone rolą PostgreSQL bez SELECT wykluczonych kolumn.
- [x] Oba typy spraw i wszystkie zapisane statusy zachowane, także starsze
  COMPLETED; tekst autora jak zapisano, bez semantycznej redakcji.
  LEGACY_SNAPSHOT oznacza migawkę migracyjną, nie datę pierwotnej decyzji;
  starsze pozycje/historia mogą być niepełne i nie są odtwarzane.
- [x] Size + 1, timeout SQL 2 s i istniejące indeksy. Kursor pozycji używa
  orderItemId, pozostałe ID rekordu; nextCursor jest tekstowy.
  Brak nowej migracji. Prywatne załączniki/no-store/nosniff.
- [x] **186 Java — PASS, bez pominiętych**, w tym 17 nowych: 122 platform,
  33 Gateway, 21 Returns unit (sześć nowych HTTP) i dziesięć nowych PostgreSQL.
  Istniejący workflow zwrotów również przeszedł.
- [x] **246 Python — PASS, bez pominiętych**, w tym dotychczasowe 12 Kubernetes.
  Bramka rejestru — PASS: 14 baz, 49 tabel, 123 migracje i 30 mechanizmów.
  Maven 17 modułów — PASS. Trzy tabele Returns jako częściowe projekcje.
- [x] JAR HTTP — PASS: 55 zgłoszeń, po 105 pozycji/zdarzeń, dwa konta ze
  wspólnym zapisanym e-mailem, legacy/empty/not-found, sześć odmów, błędne
  okna, nagłówki/UTF-8/OpenAPI i niezmienność danych; trzy ścieżki dostępu.
  Pierwszy skrypt odbioru miał kolizję nazwy zmiennej klucza JWT z kursorem;
  poprawiono sam skrypt i ponowiono cały HTTP. Nieudana próba nie jest PASS.
- [x] Odbiór dodany do CI. Bez nowego przycisku sklepu/widoku CC i bez
  zmian statusu, zwrotu pieniędzy/zapasu lub tworzenia historii.
- [ ] Pełny eksport między usługami/wspólna migawka, ocena notatek i historii
  bezpieczeństwa, usunięcie/anonimizacja, kopie/restore i polityki retencji
  nadal otwarte.

Instrukcja: [A43.23 — eksport zwrotów](A43_EKSPORT_ZWROTOW.md).
Raport: `tmp/audit-a43-23/report.json`; HTTP: `tmp/return-export-ec94682d/report.json`.
Wyłącznie własne fixture/kontenery; bez danych kont użytkownika, nowych
zależności runtime/sekretów/zmiennych, migracji, restartu CC i wdrożenia
dev/prod. Trzy zależności Testcontainers i profil Failsafe dotyczą tylko
testów Returns. A05, całe A41 i zmiany samych zdjęć nadal odłożone.


## Pakiet 81 — A43.24: eksport zapisanej płatności i zwrotu (28.10.2026)

- [x] `/payments/me/data-export/orders/{orderId}`, także `/api` i `/api/v1`:
  płatność i opcjonalny zwrot z bazy, complete false, manifest pominięć.
- [x] Tylko aktywny klient będący właścicielem w Order; sprawdzenie na każde
  żądanie własnym tokenem usługi, bez JWT/capability klienta w żądaniu
  wewnętrznym. Operator/usługa/anonim odrzuceni; e-mail/query/header nie
  zmieniają podmiotu. Istniejące operatorowe endpointy zwrotów pozostają prywatne.
- [x] Cudze/gościnne/brakujące zamówienie i brak płatności dają 404.
  Niedostępny Order daje 503 przed odczytem Payment. Nieprawidłowy order_id
  powiązanego zwrotu również blokuje cały eksport kodem 503.
- [x] Jedna jawna projekcja SQL, maksymalnie jeden zestaw i timeout 2 s,
  istniejące unikalne indeksy; bez migracji i nowych zależności/zmiennych.
  Brak zwrotu/czasów pozostaje null, zapisane kwoty/waluty/statusy zachowane.
- [x] Bez odczytu sekretów checkoutu, referencji dostawcy, danych operatora,
  hashy/kluczy operacji, diagnostyki i koordynacji. Odbiór PostgreSQL z rolą
  bez SELECT wykluczonych pól i tabel kolejek/webhooków.
- [x] Prywatny załącznik/no-store/nosniff. Payment i refund czytane w jednej
  migawce SQL, ale brak wspólnej migawki z Order. Eksport nie uzgadnia
  zapisanych statusów ze Stripe ani innymi usługami.
- [x] **276 Java — PASS, bez pominiętych**, w tym 16 nowych: 123 platform,
  33 Gateway, 103 Payment unit i 17 PostgreSQL (10 nowych eksportu i siedem
  istniejących retencji). Nowe: jedna polityki ról, cztery HTTP, jedna
  kolejności autoryzacja/SQL i dziesięć PostgreSQL.
- [x] **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
  Rejestr — PASS: 14 baz, 49 tabel, 123 migracje, 31 mechanizmów.
  Maven 17 modułów — PASS; płatności i zwroty jako częściowe projekcje.
- [x] HTTP JAR — PASS, siedem grup: Payment, `/api`, `/api/v1`, niezmienność
  sześciu tabel Payment i zamówień, OpenAPI, 503 po zatrzymaniu własnego Order,
  lokalna atrapa dostawcy z zerem żądań. Dwa konta/gość, matching capability,
  wszystkie statusy płatności, null/brak danych, gotówka/datowanie, niespójny
  zwrot, sześć odmów i błędny identyfikator. Bez operacji finansowych.
- [x] Odbiór zarejestrowany w CI, dokumentacja i plan zaktualizowane:
  **pozostaje 17 etapów A43.25–A43.41**, bez nowych numerów/podetapów.
- [x] Preferencja użytkownika zapisana: przy wymaganej decyzji dotyczącej
  danych wyświetlić okno z opcjami i skutkami; czekać na odpowiedź przed
  pracą zależną. A43.24 nie wymaga nowej decyzji retencji/aktywacji.
- [ ] Pełny eksport konta, pozostałe dane techniczne, usuwanie/anonimizacja,
  kopie/restore i docelowe polityki retencji nadal otwarte.

Instrukcja: [A43.24 — eksport płatności](A43_EKSPORT_PLATNOSCI.md).
Raport: `tmp/audit-a43-24/report.json`; HTTP: `tmp/payment-export-ff2d7710/report.json`.
Wyłącznie własne fixture/kontenery i lokalne atrapy, bez danych użytkownika,
kluczy Stripe, nowych sekretów, zmian retencji, restartu CC i wdrożenia dev/prod.
A05 nadal odłożone: użytkownik ręcznie uzupełnia STRIPE_SECRET_KEY oraz
STRIPE_WEBHOOK_SECRET w dev.env.sops i test.env.sops; odbiór sandboxu pozostaje
do osobnego wznowienia. Całe A41 i zmiany samych zdjęć nadal odłożone.


## Pakiet 82 — A43.25: eksport faktur, pozycji, korekt i istniejącego PDF (28.10.2026)

- [x] `/invoices/me/data-export/orders/{orderId}` — nagłówek, `/items` i
  `/corrections` — osobne strony do 50; `/pdf` oraz
  `/corrections/{correctionId}/pdf` — istniejący rendering dokumentów.
  Wszystko także przez `/api` i `/api/v1`, wyłącznie dla aktywnego klienta.
- [x] Order potwierdza właściciela na każde żądanie, przed jakimkolwiek
  odczytem lokalnym/cache PDF. Puste/błędne invoices.customer_id, e-mail,
  query/header i capability gościa nie zmieniają podmiotu.
- [x] Cudze/gościnne/brakujące zamówienie, faktura lub korekta poza fakturą
  dają 404; własne puste kolekcje 200. Niedostępny Order i niespójne order_id
  korekty 503. Cache rozgrzany przez inne konto/operatora nie omija kontroli.
- [x] Jawne projekcje JSON, size + 1 i timeout SQL 2 s. Kursor pozycji po
  positionNo, korekt po ID; istniejące indeksy, bez migracji/zależności.
  CTE/lateral sprawdzają powiązanie faktury z dziećmi w tym samym SQL.
- [x] Zapisane kwoty netto/VAT/brutto/dostawy/rabatu, dane nabywcy i treść
  korekty pozostają jak zapisano, bez semantycznej redakcji. Oba modele
  legacy/cumulative i ich migawki kwot zachowane bez przeliczania.
- [x] JSON wyklucza lokalne customer_id, idempotency_key i surową wartość
  ledger_version. Rola PostgreSQL bez SELECT tych pól (poza ledger_version
  używanym do etykiety modelu), sekwencji i inbox/outbox obsługuje projekcje
  oraz SQL kontroli PDF. Renderer PDF zachowuje obecne repozytoria/prezentację,
  w tym drukowaną wersję salda; nie jest archiwalnym oryginałem pliku.
- [x] Prywatne załączniki/no-store/nosniff; bez wspólnej migawki między
  Order, stronami i PDF. Limit 50 dotyczy JSON, PDF obejmuje cały dokument.
  Bez wystawiania nowych dokumentów, zmiany numerów, zdarzeń i kwot.
- [x] **276 Java — PASS, bez pominiętych**, w tym 20 nowych: 124 platform,
  33 Gateway, 81 Billing unit oraz 38 integracyjnych: 26 PostgreSQL
  (11 nowych eksportu + 15 wcześniejszych) i 12 wcześniejszych Redis.
  Nowe: jedna polityki ról, siedem HTTP/PDF, jedna autoryzacja-przed-SQL,
  jedenaście PostgreSQL. Dotychczasowa księga/retencja również PASS.
- [x] **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
  Rejestr — PASS: 14 baz, 49 tabel, 123 migracje, 32 mechanizmy.
  Maven 17 modułów — PASS; trzy tabele Billing jako częściowe projekcje.
- [x] HTTP JAR — PASS, sześć grup: trzy ścieżki dostępu, brak zmian sześciu
  tabel Billing/sekwencji i zamówień, OpenAPI, awaria Order. Na każdej
  ścieżce 105 pozycji i 105 korekt, dwa konta/gość, błędne/puste customer_id,
  firma/NIP/status CANCELLED, oba modele, UTF-8, sześć odmów i błędne okna.
  PDF zgodny z istniejącym renderingiem; obcy/niespójny/rozgrzany cache
  oraz niedostępny Order nie ujawniają dokumentu.
- [x] CI, instrukcje i plan zaktualizowane: **pozostaje 16 etapów
  A43.26–A43.41**, bez zmiany numeracji. Następny: A43.26 — czat.
- [x] Nie była potrzebna nowa decyzja retencji/aktywacji. Preferencja okna
  z opcjami przy wymaganych decyzjach danych pozostaje w planie.
- [ ] Pełny eksport konta, pozostałe projekcje, usuwanie/anonimizacja,
  kopie/restore i polityki retencji nadal otwarte.

Instrukcja: [A43.25 — eksport faktur](A43_EKSPORT_FAKTUR.md).
Raport: `tmp/audit-a43-25/report.json`; HTTP: `tmp/invoice-export-6ce1125b/report.json`.
Wyłącznie własne fixture/kontenery, bez danych użytkownika, nowych sekretów/
zmiennych, migracji, zmian retencji, restartu CC i wdrożenia dev/prod.
A05 nadal odłożone, w tym ręczne uzupełnienie STRIPE_SECRET_KEY i
STRIPE_WEBHOOK_SECRET w dev.env.sops/test.env.sops oraz odbiór sandboxu.
Całe A41 i zmiany samych zdjęć nadal odłożone.


## Pakiet 83 — A43.26: osobny eksport czatu przez token rozmowy (28.10.2026)

- [x] Decyzja użytkownika, podjęta w oknie opcji: **osobny eksport przez
  token rozmowy, bez przypisywania rozmów do kont i bez dopasowania po e-mailu**.
  Wybrany wariant obejmuje rozmowy nowe i historyczne; nie dodano customer_id
  ani funkcji wiązania. Decyzja zapisana także w planie i raporcie odbioru.
- [x] `/chat/conversations/{conversationId}/data-export` oraz `/entries`,
  także `/api` i `/api/v1`: nagłówek i strony do 50 zapisanych wiadomości.
  Jawny authorizationMode conversation-capability, complete false, omissions.
- [x] Wymagany X-Chat-Access-Token w nagłówku, także dla zalogowanego klienta.
  JWT/e-mail/nazwa/query/customerId nie zastępują dowodu. Posiadacz dowodu
  może czytać jako gość albo dowolne konto klienta, bez przypisania rozmowy.
  Operator/usługa z tymi rolami nie omijają kontroli, nawet z capability.
- [x] Brak/niepoprawny format dowodu daje 401 przed SQL; błędny token,
  brak rozmowy i brak zapisanego hasha jednakowe 404. Własna pusta lub
  wyczerpana strona 200. Dowód sprawdzany przy każdym żądaniu w tym samym
  SQL co dane, bez cache autoryzacji. Zmiana tokenu blokuje dalsze stare strony.
- [x] Jawne kolumny bez zwracania tokenu/hasha, request_key, statusów/lease/
  błędów powiadomień i outboxu. Wpisy wyłącznie CUSTOMER/SUPPORT. Tekst/kopie
  nagłówka i czasy jak zapisano, bez semantycznej redakcji/rekonstrukcji.
  Brak dowodu do historii wymaga osobnej procedury A43.39.
- [x] Size + 1, tekstowy kursor ID, timeout 2 s i istniejący indeks wpisów;
  prywatne załączniki/no-store/nosniff. Brak wspólnej migawki między stronami.
  Rola projekcji bez SELECT request_key, statusów wysyłek i outboxu; odczyt
  access_token_hash wymagany do WHERE, hash nie jest zwracany w projekcji/DTO.
- [x] **184 Java — PASS, bez pominiętych**, w tym 18 nowych: 125 platform,
  33 Gateway, 16 Chat unit (sześć nowych HTTP i jedna walidacji przed SQL)
  oraz dziesięć nowych PostgreSQL. Dotychczasowe testy historii/outboxu PASS.
- [x] **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
  Rejestr — PASS: 14 baz, 49 tabel, 123 migracje, 33 mechanizmy.
  Maven 17 modułów — PASS; dwie tabele Chat jako częściowe projekcje.
- [x] HTTP JAR — PASS, siedem grup: trzy ścieżki, niezmienność trzech tabel
  Chat, OpenAPI, działanie anonimowego capability po zatrzymaniu własnego
  Customer oraz lokalna atrapa Notification z zerem żądań. Dwie rozmowy ze
  wspólnym e-mailem, 105 wpisów, JWT niewystarczający, puste/niechronione
  rozmowy, odmowy ról, brak wycieku hashy/kluczy i nieprawidłowe okna.
- [x] Poprawiono wyłącznie oczekiwanie pierwszego testu HTTP: zabroniony
  POST gościa daje 401, a zalogowanego klienta 403. Cały HTTP ponowiono;
  nieudana pierwsza próba nie jest liczona jako PASS. Kod Java/kontrakt bez zmian.
- [x] CI, instrukcje i plan zaktualizowane: **pozostaje 15 etapów A43.27–A43.41**.
  Następny: A43.27 — powiadomienia. Brak nowego przycisku sklepu i widoku CC.
- [ ] Pełny eksport konta nie łączy automatycznie rozmów przez e-mail;
  A43.31 uwzględni osobny dowód. Redakcja, brak dowodu, usuwanie i kopie
  pozostają we właściwych etapach planu.

Instrukcja: [A43.26 — eksport czatu](A43_EKSPORT_CZATU.md).
Raport: `tmp/audit-a43-26/report.json`; HTTP: `tmp/chat-export-53be6172/report.json`.
Wyłącznie własne fixture/kontenery i lokalna atrapa, bez danych użytkownika,
nowych zależności runtime/sekretów/zmiennych, migracji, zmian retencji,
restartu CC i wdrożenia dev/prod. Trzy zależności Testcontainers i profil
Failsafe dotyczą wyłącznie testów Chat. A05 nadal odłożone, w tym ręczne
STRIPE_SECRET_KEY/STRIPE_WEBHOOK_SECRET w dev.env.sops i test.env.sops
oraz odbiór sandboxu. Całe A41 i zmiany samych zdjęć nadal odłożone.


## Pakiet 84 — A43.27: bezpieczny eksport powiadomień

**Wykonane lokalnie 28.10.2026, branch Audit_0.1.**

- [x] Eksport powiadomień własnego zamówienia: Order potwierdza właściciela
  przy każdej stronie. Event type + source_id kwalifikują wyłącznie typy
  Order/Payment; dla płatności source_id oznacza orderId. Recipient i e-mail
  nie uprawniają do odczytu. Operator/usługi/gość bez dostępu do eksportu konta.
- [x] **Decyzja użytkownika w oknie opcji:** tylko powiadomienia do rozmówcy.
  CHAT_REPLY_SENT używa id rozmowy, CHAT_ENTRY_REPLIED id wpisu SUPPORT.
  Token rozmowy wymagany niezależnie od JWT; nie powiązano czatu z kontem.
  Komunikaty do obsługi oraz ich metadane całkowicie poza eksportem.
- [x] Minimalny dowód źródła Chat wymaga Notification SERVICE i capability;
  nie ujawnia tekstu, jawnych adresów, tokenu ani jego hashu. Serwerowy skrót
  odbiorcy filtruje wiadomości dopiero po poprawnym dowodzie dostępu;
  nie trafia do publicznego eksportu. Wyłączono także odpowiedzi błędnie
  oznaczone typem klienta, lecz skierowane na inny adres. Każda strona sprawdza źródło
  ponownie. Brak źródła/zła własność 404; niedostępne źródło 503, bez fallbacku.
- [x] Strony 1–50, kursor id DESC, timeout SQL 2 s, istniejący indeks źródła,
  bez migracji. Zachowany stan i treść, jawne RETAINED/COMPACTED; prywatny
  załącznik/no-store/nosniff, complete=false i lista wyłączeń.
- [x] Nie odczytuje diagnostyki, provider_reference, correlation_id, leases,
  inbox/DLQ ani linków weryfikacji/resetu. PASSWORD_RESET_REQUESTED wskazuje
  token resetu, nie customerId. Klasyfikacja pominięć pozostaje w A43.30.
  Treść/odbiorca jak zapisano, bez semantycznej redakcji i wspólnej migawki.
- [x] **297 Java — PASS**, bez pominiętych, w tym 29 nowych testów
  polityki, HTTP-klienta, kontrolerów i PostgreSQL. Pełny finalny przebieg
  obejmuje istniejące testy Platform/Gateway/Notification/Chat oraz retencje.
  Po rozszerzeniu testu niekompletnego dowodu wykonano dodatkowo pięć testów
  klienta Chat — PASS; nie doliczono ponowienia do liczby 297.
- [x] **246 Python — PASS**, w tym 12 Kubernetes; rejestr PASS:
  14 baz, 49 tabel, 123 migracje, 34 mechanizmy. Maven 17 modułów PASS.
- [x] HTTP JAR — PASS, 11 grup. Port Notification i obie ścieżki Gateway;
  105 powiadomień, kolizje ID i odbiorców, kompaktacja, izolacja ról/tokenów,
  dokładny graf źródła/autor, wyłączone typy wewnętrzne, OpenAPI, puste źródło,
  awarie Order i Chat. Bazy Notification/Chat/Order niezmienione,
  zero połączeń SMTP. Wyłącznie własne fixture i losowe poświadczenia/porty.
- [x] Pierwsze próby wykryły błędne oczekiwania 403 dla anonimowego gościa
  w dwóch nowych testach; poprawiono na platformowe 401. Kod autoryzacji
  bez zmiany. Po korekcie i wyborze użytkownika wykonano pełny finalny Maven.
- [x] Pierwsza próba HTTP zatrzymała się na sondzie zdrowia poczty, ponieważ
  lokalna atrapa celowo odmawia SMTP. Wyłącznie w fixture wyłączono mail
  health check, tak aby licznik wykrywał wysyłki testowanych operacji.
  Nieudana próba nie jest liczona jako PASS; finalny HTTP wykonano ponownie.
- [x] CI, kontrakt, rejestr, plan i instrukcje zaktualizowane. Compose używa
  istniejącego CHAT_SERVICE_URL, graf sieci Kubernetes dodaje jedynie
  Notification → Order/Chat. Brak nowych sekretów i zależności runtime.
- [ ] A43 pozostaje otwarte: **14 etapów A43.28–A43.41**.
  Następny: A43.28 — rezerwacje magazynowe i wykorzystanie kuponów.

Instrukcja: [A43.27 — eksport powiadomień](A43_EKSPORT_POWIADOMIEN.md).
Raport: `tmp/audit-a43-27/report.json`; HTTP: `tmp/notification-export-fb2f09a1/report.json`.
Logi: `tmp/a43-27-maven-final.log`, `tmp/a43-27-python-final.log`,
`tmp/a43-27-http-e2e.log`. Nie wdrażano na dev/prod, nie restartowano CC,
nie zmieniono polityk retencji ani danych użytkownika. A05 (w tym ręczne
klucze Stripe w dev.env.sops/test.env.sops i sandbox), całe A41 i zmiany
samych zdjęć pozostają odłożone. Brak nowego przycisku sklepu lub widoku CC.


## Pakiet 85 — A43.28: eksport rezerwacji magazynu i kuponów

**Wykonane lokalnie 28.10.2026, branch Audit_0.1.**

- [x] Cztery GET: nagłówek rezerwacji Inventory, osobna strona pozycji,
  nagłówek rezerwacji kuponu oraz strona wykorzystania kuponów.
  Każdy odczyt, także pustej/wyczerpanej strony, weryfikuje właściciela
  w Order przy użyciu tożsamości usługi. Brak cache, dopasowania po e-mailu,
  obejścia operatora/usługi i dostępu gościa przez token zamówienia.
- [x] Pozycje kwalifikuje JOIN rezerwacji po order_id; redemption filtruje
  order_id. Id rezerwacji nie jest id zamówienia. Zapisane stany i daty
  wygaśnięcia, kwoty dziesiętne i faktyczne kody transakcji są zachowane,
  także RELEASED tombstone i historyczne wykorzystania bez rezerwacji.
- [x] Strony 1–50, id DESC, timeout SQL 2 s i jawne pola. Brak historii
  odtwarzanej i wspólnej migawki. Stałe prywatne załączniki/no-store/nosniff,
  complete=false i pominięcia. Serwer nie zapisuje archiwum.
- [x] Globalny katalog, stany/operacje magazynowe, definicje promocji,
  historia cen, limity/liczniki i operacyjne version/operation_id jawnie
  poza eksportem konta. Brak relacji własności nie otwiera całych tabel.
- [x] Osobne odczyty JDBC nie wywołują cleanup/release/consume/reconcile.
  Nie zmieniają TTL, zapasu, wersji, limitów ani trwałej ochrony wykorzystania.
  Normalne zadania w tle pozostają osobnym procesem, bez zmiany ich ustawień.
- [x] Dwie migracje indeksów: Inventory V9 (reservation_id,id DESC),
  Promotion V7 (order_id,id DESC). Bez przepisywania/usuwania rekordów,
  hashów historycznych i osłabienia unikalności kuponu/order.
- [x] **238 Java — PASS**, bez pominiętych: Platform 131, Gateway 33,
  Inventory 31 + 14 PostgreSQL, Promotion 21 + 8 PostgreSQL.
  **29 nowych testów**, w tym 16 PostgreSQL. Testy ograniczonych uprawnień
  SQL potwierdzają brak odczytu globalnych danych i operacyjnej wersji.
- [x] **246 Python — PASS**, w tym istniejące 12 Kubernetes. Rejestr PASS:
  14 baz, 49 tabel, 125 migracji, 35 mechanizmów; Maven 17 modułów PASS.
- [x] HTTP JAR — PASS, 10 grup: dwa porty usług i oba prefiksy Gateway,
  po 105 rekordów na ograniczonych stronach, wspólne kody/e-maile i odrębni
  właściciele, gość, puste/wygasłe/zamknięte/legacy dane, role, okna,
  dokładne pola OpenAPI, awaria własnego Order z 503 na każdej ścieżce.
  Dziesięć tabel Inventory/Promotion/Order identycznych przed/po;
  zero wywołań lokalnej atrapy Product/Payment/Notification.
- [x] CI, kontrakt, kopie pobrań i cztery częściowe projekcje w rejestrze.
  Graf Kubernetes dodaje Inventory → Order; Promotion miało tę relację.
  Compose korzysta z istniejącego PLATFORM_ORDER_URL. Brak nowych sekretów
  i zależności runtime; Testcontainers/Failsafe w Promotion tylko do testów.
- [x] Wszystkie pięć eksportów domenowych planu bazowego A43.24–A43.28
  odebrane w opisanym zakresie. Całe A43 pozostaje otwarte: **13 etapów
  A43.29–A43.41**. Następny A43.29 — historia bezpieczeństwa, MFA i notatki.

Instrukcja: [A43.28 — rezerwacje i kupony](A43_EKSPORT_REZERWACJI_KUPONOW.md).
Raport: `tmp/audit-a43-28/report.json`; HTTP: `tmp/reservation-export-b5aeb468/report.json`.
Logi: `tmp/a43-28-maven-verify.log`, `tmp/a43-28-python.log`,
`tmp/a43-28-http-e2e.log`. Wyłącznie własne fixture, losowe porty i poświadczenia.
Cleanup Inventory wydłużono do godziny wyłącznie w fixture, po początkowym
przebiegu na pustych rezerwacjach; umożliwia to kontrolę wpływu odczytów
na wygasłe rekordy. Nie czytano danych użytkownika ani sekretów, nie wdrażano
dev/prod i nie restartowano CC. Bez nowego przycisku sklepu/widoku CC;
interfejs eksportu obejmuje A43.32. A05 (ręczne STRIPE_SECRET_KEY i
STRIPE_WEBHOOK_SECRET w dev.env.sops/test.env.sops oraz sandbox), całe A41
i zmiany samych zdjęć pozostają odłożone.


## Pakiet 86 — A43.29: historia bezpieczeństwa, metadane MFA i notatki

**Wykonane lokalnie 28.10.2026, branch Audit_0.1.**

- [x] Dwie odpowiedzi użytkownika w oknie opcji: nowe zdarzenia z jawnym ID
  klienta + wąski wyjątek historycznych refresh ze stabilnym ID; swobodne
  notatki/etykiety/metadata/IP/User-Agent przez ręczną ocenę podmiotu i redakcję.
  Brak przypisywania historii przez e-mail i automatycznego wydawania notatek.
- [x] Cztery GET własnego aktywnego konta: metadane TOTP, ograniczone strony
  audytu, passkeys i metadanych kodów odzyskiwania. Principal kwalifikuje
  konto w tym samym SQL co dane, także dla pustych/wyczerpanych stron.
  Operator/usługi bez obejścia, brak wyboru podmiotu przez query/nagłówki.
- [x] AuditService zapisuje subject_customer_id osobno od actor; znane ścieżki
  Customer/Auth/MFA/WebAuthn przekazują ID z obiektu/tokena konta. Nieznany
  cel pozostaje nieprzypisany. ID oznacza podmiot zdarzenia, nie udowodnioną
  tożsamość osoby wykonującej nieudaną próbę. Bez przepisywania starego audytu.
- [x] Legacy: wyłącznie NULL subject + kanoniczny actor równy ID konta +
  REFRESH_TOKEN + TOKEN_REFRESH/SUCCESS lub REFRESH_TOKEN_REUSE/REJECTED.
  Jawny subject ma pierwszeństwo; e-mail, anonymous, 01 i nieznane typy/
  resource/outcome nie trafiają automatycznie do pliku. Wynik ma tylko
  id, dopuszczony typ/wynik, czas i stały rodzaj dowodu podmiotu.
- [x] Daty/stany TOTP, created/last_used i flagi WebAuthn oraz created/used
  kodów odzyskiwania bez sekretów, hashy kodów, credential_id, kluczy,
  attestation, liczników uwierzytelniania, label i raw transports.
  Passkeys mają osobny niesekretny export_id do stron; nie zmieniono ich
  identyfikatorów uwierzytelniania. Strony 1–50, timeout 2 s, no-store/
  private/nosniff, complete=false i jawne pominięcia. Brak wspólnej migawki.
- [x] Review/Returns mają konkretny status ręcznej oceny notatek zamiast
  pending reviewed projection. Actor_ref/klucze workflow nie są wydawane.
  W Chat prywatne notatki nie istnieją; nie rekonstruujemy ich i zachowujemy
  dotychczasowy token rozmowy/publiczne wiadomości. Nie wiążemy czatu z kontem.
- [x] Macierz pól w security-export-policy.json i procedura: weryfikacja
  podmiotu, minimalny odczyt, ocena każdego fragmentu/osoby, redakcja,
  kontrola wyniku, manifest decyzji i przekazanie sprawdzonej kopii.
  Przykłady wielu osób; brak regexu udającego ocenę znaczenia i brak
  nowego endpointu automatycznego wydawania surowych notatek.
- [x] Customer V16: nullable subject, warunek dodatniości i dwa indeksy
  audytu, bez FK/CASCADE/backfillu po e-mailu. V17: export_id WebAuthn
  i indeksy okien. Polityki retencji i zachowania Redis bez zmian.
- [x] **360 unikalnych testów Java — PASS, bez pominiętych**: pełna regresja
  359 (Platform/Gateway/Customer/Review/Chat/Returns) i jeden dodatkowy test
  aktualizacji z wypełnionego V15. **18 nowych testów**, w tym dziesięć PG.
  Aktualizacja zachowuje credential_id, klucze/attestation, liczniki i cały
  stary audyt; nowe subject pozostają NULL, nowe inserty kluczy dostają ID.
  Testy ograniczonych kolumn potwierdzają brak odczytu sekretów/swobodnych
  pól; przypadki wielu podmiotów nie ujawniają ich tożsamości.
- [x] **246 Python — PASS**, w tym istniejące 12 Kubernetes; rejestr PASS:
  14 baz, 49 tabel, 127 migracji, 36 mechanizmów. Maven 17 modułów PASS.
  Zaktualizowano wyłącznie dwa nowe hashe migracji i dwa przejrzane hashe
  źródeł Redis zmienionych tylko przez przekazanie podmiotu audytu.
- [x] HTTP JAR — PASS, siedem grup: Customer, oba prefiksy Gateway,
  po 105 zdarzeń/kluczy/kodów, ścisłe pola i dowód legacy, brak sekretów/
  innych tożsamości, prywatne nagłówki, role, błędne okna i aktywne konto.
  Siedem tabel niezmienionych przez odczyty, zero Notification. Kontrolowany
  zapis profilu wyłącznie w fixture potwierdza nowy subject; osobne wyłączenie
  konta w fixture potwierdza natychmiastową odmowę każdej ścieżki.
- [x] CI, kontrakty, rejestr, manifesty i instrukcje zaktualizowane.
  Całe A43 pozostaje otwarte: **12 etapów A43.30–A43.41**.
  Następny A43.30 — klasyfikacja danych technicznych, deduplikacji i pominięć.

Instrukcja i procedura: [A43.29 — ocena treści i dane bezpieczeństwa](A43_OCENA_NOTATEK_I_DANYCH_BEZPIECZENSTWA.md).
Raport: `tmp/audit-a43-29/report.json`; HTTP: `tmp/security-export-8f2136ac/report.json`.
Logi: `tmp/a43-29-maven-verify.log`, `tmp/a43-29-upgrade-verify.log`,
`tmp/a43-29-python.log`, `tmp/a43-29-http-e2e.log`. Wyłącznie własne fixture,
bez danych/sekretów użytkownika, wdrożenia dev/prod, restartu CC, nowych
sekretów, zależności runtime i aktywacji czyszczenia. Brak nowego widoku CC;
zbiorczy interfejs eksportu obejmuje A43.32. A05 (ręczne klucze Stripe w
dev.env.sops/test.env.sops i sandbox), całe A41 i zmiany zdjęć nadal odłożone.


## Pakiet 87 — A43.30: klasyfikacja danych technicznych i ochrona deduplikacji

**Wykonane lokalnie 28.10.2026, branch Audit_0.1.**

- [x] Decyzja użytkownika w oknie opcji: istniejące projekcje biznesowe +
  ręczna ocena pozostałych danych osobowych, bez nowych eksportów stanów
  technicznych. Surowe kopie/klucze/sekrety poza automatycznym plikiem;
  wyłączenie nie oznacza zgody na kasowanie ani anonimowości danych.
- [x] Maszynowa macierz **49 tabel / 562 kolumn**, pięciu magazynów Redis,
  piętnastu kategorii kopii i **93 pominięć w 14 źródłach eksportów**.
  Każde pole ma decyzję, uzasadnienie, właściciela/graf podmiotu, źródło
  dowodu oraz procedurę. Sprawdzono także 11 tabel not-account-scoped;
  brak relacji konta nie otwiera globalnej tabeli klientowi.
- [x] Rozstrzygnięcia kolumn: 215 pól istniejących projekcji (warunkowo
  według ich zakresu), 12 kopii pokrytych biznesowo, 37 do oceny ręcznej,
  36 kwalifikujących relację, 150 operacyjnych poza automatycznym plikiem,
  14 materiału uwierzytelniającego/kluczy i 98 globalnych referencyjnych.
  Kopia historyczna nie jest bezwarunkowo uznana za identyczną z bieżącą.
- [x] Zdefiniowano grafy order_sagas, follow-ups/gates/outbox/webhook
  receipts Payment, inbox/outbox Billing, inbox/DLQ Notification oraz
  chat_notification_outbox. Billing ACCEPTED related_id oznacza orderId;
  Notification related_id wskazuje notificationId. REJECTED payload/recipient
  nie dowodzą własności. Chat nadal wymaga tokenu konkretnej rozmowy.
- [x] Powiadomienia bezpieczeństwa: registration.source_id=customerId,
  reset.source_id=tokenId → customerId. Tylko ręczna ocena po dowodzie;
  linki/sekrety wyłączone. Po usunięciu tokenu brak relacji wymaga wyjaśnienia,
  nie dopasowania po e-mailu. Metadane audytu A43.29 nie są pełną historią
  doręczeń. Wybór wyłączenia komunikatów do obsługi z A43.27 zachowany.
- [x] Procedura ograniczonego odczytu i redakcji konkretnej sprawy:
  potwierdzenie podmiotu, graf typu/statusu, najpierw projekcja domenowa,
  minimalny niesekretny fragment, ocena innych osób i manifest decyzji.
  Bez replay/reconcile, provider calls, publikacji, wysyłek i rekonstrukcji
  skompaktowanego payloadu. Obsługa kopii nadal w A43.37–A43.39.
- [x] Plan wejściowy A43.36 opisuje e-mail w payment_outbox.event_key,
  zależność eventId/fingerprint, czytniki v1/v2, aliasy, backlog/PEL,
  konflikty payloadu, backup/rollback i jeden skutek. **Migracja nie jest
  wdrożona**; pięć źródeł tożsamości jest chronionych hashami. Nie zmieniono
  ID, payload_hash, unikalności, bramek, tombstones ani retencji.
- [x] Bramka CI odtwarza kolumny z 127 migracji; odrzuca nieznane DDL,
  nowe/brakujące pole, niesklasyfikowane pominięcie, dynamiczne obejście,
  nieprzejrzaną zmianę źródła projekcji/tożsamości, błędnego właściciela
  i próbę ujawnienia materiału uwierzytelniającego/swobodnej treści.
- [x] **274 Python — PASS**, w tym **28 nowych testów** parsera i mutantów.
  CLI działa w teście z zablokowaną siecią/uruchamianiem procesów; sam
  klasyfikator wykonuje zero operacji na danych. Rejestr: 14 baz,
  49 tabel, 127 migracji, 37 mechanizmów; Maven 17 modułów PASS.
- [x] Oracle PostgreSQL — PASS: własny izolowany kontener, 14 baz,
  wszystkie 127 migracji z danymi seed wyłącznie ze źródeł. Rzeczywiste
  562 kolumny zgodne z parserem; **22 wymagania unikalności/deduplikacji**
  zachowane, trigger niezmienności historii Chat aktywny. Żadnej bazy
  środowiska użytkownika nie odczytywano ani nie migrowano.
- [x] **37 ukierunkowanych testów regresji Java — PASS**, w tym istniejące
  15 PostgreSQL dla zmienionych manifestów Customer/Notification.
  HTTP — PASS: pięć grup profilu i jedenaście grup powiadomień, oba
  prefiksy Gateway; finalne testy sprawdzają dokładne nowe decyzje pominięć,
  izolację właścicieli i brak surowych reset links/payloadów. Dane bez zmian
  przez pobrania; zero SMTP w odbiorze powiadomień.
- [x] CI publikuje raport klasyfikacji i oracle; manifesty Customer/
  Notification mają konkretne granice/procedury zamiast pending dla kopii.
  Rejestr technicznych zasobów ma reviewed procedure; globalne możliwości
  eksportu/usuwania/backupów pozostają pending zgodnie z otwartym zakresem.
- [x] Oba etapy klasyfikacji A43.29–A43.30 wykonane. Pozostaje **11 etapów
  A43.31–A43.41**. Następny A43.31 — koordynator eksportu i manifest kompletności.

Dokumenty: [klasyfikacja i grafy](A43_KLASYFIKACJA_DANYCH_TECHNICZNYCH.md),
[plan zgodności A43.36](A43_PLAN_MIGRACJI_TOZSAMOSCI_ZDARZEN.md).
Raport: `tmp/audit-a43-30/report.json`; oracle: `tmp/classification-postgres-9c4f1f7e/report.json`.
HTTP: `tmp/account-data-export-ce28a6e6/report.json`, `tmp/notification-export-ae424880/report.json`.
Logi: `tmp/a43-30-python-final.log`, `tmp/a43-30-maven-verify.log`,
`tmp/a43-30-postgres-e2e.log`, `tmp/a43-30-account-http-final.log`,
`tmp/a43-30-notification-http-final.log`. Nie dodano migracji/sekretów/
zależności runtime ani nowego API stanów technicznych lub widoku CC.
Nie uruchamiano operacji na rzeczywistym Redis, danych użytkownika,
wdrożenia dev/prod, restartu CC i aktywacji retencji. A05 (ręczne klucze
Stripe w dev.env.sops/test.env.sops oraz sandbox), całe A41 i zdjęcia odłożone.


## Pakiet 88 — A43.31: trwały koordynator eksportu i manifest kompletności

**Wykonane i odebrane lokalnie 28.10.2026, branch Audit_0.1.**

- [x] Decyzje użytkownika: tymczasowe zadanie i ograniczone porcje JSON w
  bazie Customer; dokładnie **1 godzina od utworzenia**, bez przedłużania.
  Bez Redis i plików eksportu na dysku serwera. Trzy nowe tabele V18;
  brak zmiany wcześniejszych migracji i danych domenowych.
- [x] API tworzenia, listy własnych niewygasłych zadań, postępu, advance,
  retry, cancel, stron części i manifestu. Principal aktywnego konta w SQL;
  obce UUID 404, wygasłe 410, operator/usługa/gość bez obejścia. Private/
  no-store/nosniff, brak trwałego JWT i surowych błędów dostawcy.
- [x] Trwała idempotencja utworzenia i kroków, unikalne deskryptory stron
  i części, lease/token wykonania, transakcyjny zapis części + fanout +
  postęp. Restart nie powtarza zapisanej strony; późna odpowiedź po
  przejęciu/anulowaniu/wygaśnięciu nie dopisuje danych. HTTP poza transakcją.
- [x] 25 rodzajów źródeł: bezpieczne dane Customer/MFA, Wishlist, recenzje,
  zachowany log rekomendacji, zamówienia/pozycje, zwroty/zdarzenia,
  rezerwacje i kupony, płatność/refund, przesyłka, faktury/pozycje/korekty/
  istniejące PDF oraz powiadomienia Order. Stałe endpointy i aktualny Bearer;
  opcjonalny brak nagłówka Payment/Shipment/Invoice wymaga dowodu Order.
- [x] Ścisłe pola/zagnieżdżenia, rodzice i kursory, ograniczone odpowiedzi
  HTTP/JSON/PDF i deadline. Maksymalnie 512 KiB części, 256 KiB surowego PDF,
  16 MiB treści/4096 kroków zadania, osiem prób kroku, trzy utworzenia na
  godzinę i jedno niezakończone zadanie konta. Limit/błąd nie daje READY.
- [x] Manifest obejmuje wszystkich 14 właścicieli baz, decyzje klasyfikacji,
  93 przejrzane pominięcia, 16 kategorii kopii, czasy i hashe części.
  Osobny dowód Chat i ręczna ocena treści pozostają jawne. READY potwierdza
  tylko zakres automatyczny; **complete=false**, bez globalnej migawki.
  Zmiana zapakowanej polityki blokuje mieszanie wznowionego wyniku.
- [x] Anulowanie i godzina dostępności odcinają wynik od razu. Scheduler
  usuwa w ograniczonych transakcjach: 50 części/100 kroków/25 nagłówków,
  do czterech partii na minutę. Fizyczne usuwanie asynchroniczne; kopie
  backupów i dalsze procedury A43.37–A43.39 pozostają otwarte.
- [x] Rejestr i macierz: **52 tabele / 592 kolumny / 128 migracji**,
  38 mechanizmów, 16 kategorii kopii. Nowe pola sklasyfikowane jawnie;
  bramki chronią również deduplikację koordynatora. Globalne możliwości
  usuwania/backupów i cały A43 nadal pending.
- [x] **113 ukierunkowanych testów Java — PASS**, w tym 14 nowych
  PostgreSQL (wyścigi, replay, expiry, cancel, limity i porcjowe usuwanie),
  testy źródeł/schematu/deadline/uprawnień i regresje RoutePolicy.
  **274 Python — PASS**. Oracle 14 izolowanych baz potwierdził wszystkie
  128 migracji, 592 kolumny i **28 wymagań unikalności**.
- [x] HTTP Customer/Gateway/PG — **6 grup PASS**: prawdziwy restart,
  wznowienie po 503, 26 części/51 Wishlist, fanout faktury i obu PDF,
  kontrola wszystkich endpointów właściciela, oba prefiksy Gateway i
  OpenAPI, brak JWT/błędów w DB, cancel/expiry/limit i wyłączenie konta.
  Źródła domenowe w tym odbiorze są ograniczonymi fixture HTTP według
  produkcyjnych DTO, po wcześniejszych odbiorach domen A43.16–A43.30.
  Siedem tabel biznesowych/bezpieczeństwa bez zmian przez eksport.
- [x] CI uruchamia nowy odbiór; Compose/Kubernetes mają wymagane zależności
  Customer. Obraz pakuje wyłącznie jawnie wskazaną niesekretną politykę.
  Interfejs sklepu/CC nie został rozszerzony w tym etapie.

Pozostaje **10 etapów A43.32–A43.41**. Następny **A43.32** — interfejs
zbiorczego pobierania. [Kontrakt i ograniczenia](A43_KOORDYNATOR_EKSPORTU.md).
Raport: `tmp/audit-a43-31/report.json`.
Dowody HTTP: `tmp/export-jobs-25ad8145/report.json`; oracle: `tmp/classification-postgres-7f5bd0f0/report.json`.
Logi: `tmp/a43-31-java-final.log`, `tmp/a43-31-python-final.log`,
`tmp/a43-31-http-final.log`, `tmp/a43-31-oracle.log`.
Bez danych użytkownika, rzeczywistego Redis, wdrożenia dev/prod i restartu CC.
A05 (ręczne STRIPE_SECRET_KEY/STRIPE_WEBHOOK_SECRET w dev.env.sops i
test.env.sops oraz sandbox), całe A41 i zmiany samych zdjęć pozostają odłożone.


## Pakiet 89 — A43.32: zbiorczy eksport danych konta w sklepie

**Wykonane i odebrane lokalnie 28.10.2026, branch Audit_0.1.**

- [x] Profil → Eksport danych konta: lista własnych zapisanych zadań,
  przygotowanie, wznowienie, ręczne ponowienie błędnych źródeł, wstrzymanie
  w karcie i anulowanie na serwerze. Otwarcie profilu tylko odczytuje
  metadane. Eksport nie zapisuje niezapisanych zmian formularza.
- [x] Pobranie JSON z manifestem oraz osobne pobranie manifestu. READY
  oznacza ukończony zakres automatyczny, a PARTIAL/LIMIT_REACHED mają
  jawny komunikat i nazwę pliku częściowego. **complete=false** pozostaje:
  Chat potrzebuje osobnego tokenu, swobodna treść ręcznej oceny, kopie
  odrębnych procedur. Nie obiecuje się wspólnej migawki.
- [x] Zachowano zatwierdzone ustawienia A43.31: Customer DB, godzina od
  utworzenia, bez wydłużania i bez plików roboczych/Redis. Timer, kontrola
  API, AbortSignal i generacja sesji blokują późne pobrania po expiry,
  wylogowaniu i zmianie konta, także przy ponownym logowaniu na to samo ID.
  Odrzucony refresh otwiera logowanie; ręczny logout nie pokazuje tego błędu.
- [x] Bez zapisu danych eksportu, manifestu ani ID zadania w localStorage,
  sessionStorage/IndexedDB. Ograniczone bufory RAM i krótkotrwały Blob URL;
  URL zwalniany po sekundzie oraz przy opuszczeniu strony/sesji/wygaśnięciu.
  Pobrana kopia pozostaje na urządzeniu użytkownika — informacja PL/EN.
  Serwer nie może usunąć pliku Downloads; A43.38–A43.39 pozostają otwarte.
- [x] Strumieniowy limit odpowiedzi i deadline, maksymalnie trzy części
  na żądanie, do 16 MiB treści/4096 kroków i plik do 24 MiB. Test 16 MiB
  przechodzi we wszystkich czterech profilach. Limit pliku nie jest limitem
  heap: kodowanie/parsing/Blob mogą tworzyć dodatkowe ograniczone kopie RAM.
- [x] Rozszerzenie kontraktu: ownerId jako tekst i chunks?raw=true z
  dokładnym dataJson oraz tekstowymi ID. SHA-256 i rozmiar sprawdzane przed
  pobraniem. Składanie bez ponownej serializacji kwot/dużych ID; zachowano
  domyślny kontrakt data A43.31. Brak nowych tabel, migracji i sekretów.
- [x] PL/EN, klawiatura, role/statusy dostępności, brak wstawiania raw
  błędów do HTML, brak poziomego przepełnienia. Krótkie etykiety listy zadań
  poprawiają WebKit; przyciski obsługują wielowierszowy tekst. Wspólny graf
  modułów ma jedną wersję i nie dubluje instancji sesji. Bez nowego widoku CC.
- [x] **227 Node, 274 Python, 38 unikalnych Java — PASS** (41 wykonań Java,
  w tym 14 PostgreSQL; kontroler uruchomiono ponownie z nowym testem raw).
  Bramka klasyfikacji i 60 powiązanych modułów JS — PASS. Rejestr:
  52 tabele/592 kolumny/128 migracji, 39 mechanizmów i 16 kategorii kopii.
- [x] **44 scenariusze Playwright — PASS**: Chromium, Firefox, WebKit,
  mobilny Chrome; sukces/częściowy wynik/retry, pause/reload/cancel,
  wylogowanie/zmiana konta/rejected refresh, expiry, checksum i plik 16 MiB.
  Cztery zrzuty sprawdzone; zero poważnych/krytycznych naruszeń axe w
  panelu profilu. Dodatkowo **54 regresje Chromium — PASS**: sesja,
  uwierzytelnianie, koszyk, dostępność, transport i wcześniejsze pobrania.
- [x] Rzeczywisty przepływ przeglądarka → Gateway → Customer → izolowany
  PostgreSQL — **PASS**, w tym 26 części, wznowienie po 503, 14 właścicieli
  w manifeście, download i anulowanie. Cały odbiór HTTP ma siedem grup,
  część przeglądarkowa trzy kontrole i 45 przekazanych żądań. Domenowe
  źródła są kontrolowanymi fixture HTTP według produkcyjnych DTO;
  sesja przeglądarki fixture, podpis JWT weryfikuje prawdziwy Customer.
  Siedem tabel biznesowych/bezpieczeństwa bez zmian przez pobrania.
- [x] CI uruchamia nowe scenariusze w czterech profilach i rzeczywisty
  przepływ z fixture; raporty są objęte artefaktami. Zaktualizowano rejestr,
  macierz i dokumentację. Nie uruchamiano wdrożenia dev/prod ani restartu CC.

Pozostaje **9 etapów A43.33–A43.41**. Następny **A43.33** — protokół
usuwania konta, trwałe zadania i ochrona przed nowymi zapisami; decyzje
dotyczące klas danych będą zbierane w oknie opcji przed zależnym działaniem.
Instrukcja: [eksport konta w sklepie](A43_POBIERANIE_EKSPORTU_KONTA.md).
Raport: `tmp/audit-a43-32/report.json`; HTTP/przeglądarka: `tmp/export-jobs-6d1d5337/report.json`.
Logi: `tmp/a43-32-node-final.log`, `tmp/a43-32-python.log`,
`tmp/a43-32-java.log`, `tmp/a43-32-java-raw.log`,
`tmp/a43-32-browser-release.log`, `tmp/a43-32-regression.log`,
`tmp/a43-32-real-browser-final.log`.
A05 (ręczne STRIPE_SECRET_KEY/STRIPE_WEBHOOK_SECRET w dev.env.sops i
test.env.sops oraz sandbox), całe A41 i zmiany samych zdjęć pozostają odłożone.


## Pakiet 90 — A43.33: protokół usuwania i bariery zapisów

**Wykonane i odebrane lokalnie 29.10.2026, branch Audit_0.1.**

- [x] Podgląd planu 14 usług, 10 minut na potwierdzenie, aktualny właściciel,
  hasło i podpisane potwierdzenie nadal skonfigurowanego MFA. Jawna fraza,
  związany hash planu, kontrola prób oraz jedno żądanie na konto.
- [x] Atomowe odcięcie konta i refresh, wyczyszczenie weryfikacji/cookie,
  trwałe zadanie i 14 kroków. JWT dawnego konta przestaje być akceptowany.
- [x] Decyzja użytkownika: minimalny znacznik customer ID + request ID +
  BLOCKED, bez e-maila/hashu e-maila i automatycznego TTL do A43.40.
  Pseudonimowy znacznik chroni stare ID, nie zakazuje na zawsze adresu.
- [x] Osobny losowy, tylko do odczytu token statusu, dokładnie 168 godzin
  od potwierdzenia. Tylko hash w bazie, bez odzyskiwania przez e-mail,
  przedłużania, undo i prawa do eksportu. Po expiry status dla obsługi.
- [x] Bariery PostgreSQL w 14 bazach: wspólna blokada transakcji klienta,
  wyłączna instalacja znacznika, odrzucenie spóźnionych zapisów. Dodatkowe
  triggery nowych przypisań, reaktywacji/profilu i poświadczeń WebAuthn.
  Poprawny odczyt CTE działa; CTE z zapisem podlega barierze.
- [x] Ograniczone kroki worker bez transakcji wokół HTTP, lease/token,
  retry/backoff, obsługa utraconego wykonawcy i jawne NEEDS_ATTENTION.
  Wyłącznie customer-service instaluje bariery; operator ma wąski odczyt/retry.
- [x] WAITING_ADAPTERS oznacza wyłącznie potwierdzone bariery.
  **complete=false, dataErased=false**. Profil i treści pozostają; procesy
  rozliczeń/dostaw mogą dobiec końca. Ochrona replay treści i adaptery
  usuwania wymagają A43.34–A43.37. Chat nadal wymaga osobnego dowodu.
- [x] **769 jednostkowych Java (0 pominiętych), 14 PostgreSQL i
  276 Python — PASS**. Kontrolery/JWT i polityka dostępu powtórzone po
  poprawkach. Test granicy DST sprawdza stałe 604800 sekund i różne sesje TZ.
- [x] **6 grup HTTP — PASS**, cztery rzeczywiste aplikacje, trzy bazy
  PostgreSQL i własny Redis fixture, 11 kontrolowanych peerów HTTP.
  Równoległe potwierdzenie, MFA, odmowy ról, niedostępny peer, spóźniony
  DELETE Wishlist, restart Customer, 14 ACK i wygaśnięcie statusu.
  Regresja koordynatora eksportu: **6 grup — PASS**.
- [x] Oracle: 14 baz, 68 tabel/653 kolumny/142 migracje, 59 zobowiązań
  unikalności i 11 odmów nowych przypisań/reaktywacji. Rejestr: 40 mechanizmów,
  17 kategorii kopii. Historyczne migracje nie zostały zmienione.
- [x] CI uruchamia nowy odbiór i zachowuje raporty. Manifesty Kubernetes:
  53 poprawne zasoby, bez błędów; 16 komponentów i 14 powiązań baz.
  Wszystkie 15 JAR-ów aplikacji zawierają aktualny wspólny moduł ochrony.

Instrukcja i granice: [protokół A43.33](A43_PROTOKOL_USUWANIA_KONTA.md).
Raport: `tmp/audit-a43-33/report.json`; logi `tmp/a43-33-unit-final.log`,
`tmp/a43-33-unit-remaining.log`, `tmp/a43-33-protocol-accepted.log`, `tmp/a43-33-python-final.log`,
`tmp/a43-33-http-accepted.log`, `tmp/a43-33-export-accepted.log`,
`tmp/a43-33-oracle-final.log`, `tmp/a43-33-package-final.log`.
Pozostaje **8 etapów A43.34–A43.41**; następny **A43.34**.
Bez danych użytkownika, fizycznego usuwania, wdrożenia dev/prod i restartu CC.
A05 (ręczne STRIPE_SECRET_KEY/STRIPE_WEBHOOK_SECRET w dev.env.sops i
test.env.sops oraz sandbox), całe A41 i zmiany samych zdjęć pozostają odłożone.


## Pakiet 91 — A43.34: usuwanie rdzenia konta i osobny wniosek czatu

**Wykonane i odebrane lokalnie 29.10.2026, branch Audit_0.1.**

- [x] Decyzje użytkownika: profil i poświadczenia usuwane, adres zwolniony
  do nowego ID; recenzje bez tekstu/notatki/oceny i publikacji, tylko trzy
  klucze deduplikacji. Redis: ustawienia po ID usuwane, losowe wyzwania
  pozostają do istniejącego TTL bez logowania, limity e-maila zachowane.
- [x] Adaptery Customer/Wishlist/Recommendation/Review: do 100
  usunięć na partię, idempotencja, rollback i wznowienia. Customer czyści
  też własne porcje/plany anulowanych eksportów; nowe konto nie dziedziczy
  danych ani tokenów dawnego ID. Znaczniki i minimalne klucze pozostają
  pseudonimowe do A43.40. Audyt i pozostałe kopie mają odrębny zakres.
- [x] Wszystkie 14 barier i aktualna polityka są wymagane przez koordynator.
  Bez cichego rozszerzania starych potwierdzeń, bez retry poza ośmioma
  błędami i bez przedłużenia statusu. Indeks aktywnych spraw omija historię.
  `PRIVACY_ACCOUNT_CLEANUP_ENABLED` pozostaje domyślnie false.
- [x] **Wybór czatu: osobny wniosek po tokenie + ręczna ocena.** POST/GET
  `/chat/conversations/{conversationId}/privacy-request`: jawna fraza,
  związany UUID, jedna sprawa na rozmowę i aktualny dowód przy odczycie.
  Zapis tylko ID rozmowy/wniosku, MANUAL_REVIEW i daty. Bez konta/e-maila,
  sekretu, treści i automatycznego kasowania rozmowy lub powiadomień.
- [x] Równoległe zgłoszenia/replay zwracają ten sam wniosek; klucz nie
  przechodzi do innej rozmowy. Operator/usługi nie omijają dowodu. Ręczną
  procedurę i kopie obsługuje A43.39; status nie ogłasza usunięcia danych.
- [x] **327 unikalnych testów Java, w tym 38 PostgreSQL, 277 Python — PASS.**
  Sześć grup HTTP na sześciu rzeczywistych aplikacjach/pięciu bazach i
  własnym Redis, dziewięć peerów kontrolowanych. 205 wierszy na domenę,
  awaria Review, rollback SQL, restart, nowe ID tego samego adresu,
  izolacja rozmów/kont, DTO/OpenAPI i obie ścieżki Gateway odebrane.
- [x] Oracle 14 baz — PASS: 70 tabel/666 kolumn/145 migracji i 62
  unikalności; 41 mechanizmów/17 kopii. CI zawiera krok odbioru i zapis
  artefaktów. Wszystkie 15 JAR-ów zawiera aktualny wspólny moduł ochrony.

Zakres A43.34 zamknięty zgodnie z wybraną polityką. Cała sprawa nadal
ma complete=false/dataErased=false: zamówienia, finanse, audyt i kopie
wymagają A43.35–A43.41. Pozostaje **7 etapów A43.35–A43.41**, następny
**A43.35**. Bez danych użytkownika, wdrożenia dev/prod i restartu CC.
A05 (ręczne klucze Stripe w dev.env.sops/test.env.sops i sandbox), całe
A41 oraz zmiany samych zdjęć pozostają odłożone.
[Instrukcja](A43_USUWANIE_PROFILU_I_TRESCI.md); raport `tmp/audit-a43-34/report.json`.
Logi: `tmp/a43-34-java.log`, `tmp/a43-34-protocol-final.log`,
`tmp/a43-34-chat-java.log`, `tmp/a43-34-chat-python.log`,
`tmp/a43-34-chat-http.log`, `tmp/a43-34-chat-oracle.log`,
`tmp/a43-34-package-final.log`.

## Pakiet 92 — A43.35: dane zamówień i ochrona procesów domenowych

**Wykonane i odebrane lokalnie 29.10.2026, branch Audit_0.1.**

- [x] Decyzje użytkownika: kontakt/adres w Order i swobodne treści oraz
  autorzy zweryfikowanych zamkniętych Returns do NULL. Stabilne ID,
  kwoty, ilości, stany, klucze, tracking, kody kuponów i hashe pozostają
  pseudonimowe do A43.40. Bez anulowania zakupu, zwalniania limitów i zapasu.
- [x] Pięć adapterów, kanoniczny graf Order.customer_id bez e-maila,
  wymagane 14 barier, oddzielny hash zgody, trwały kursor per usługa.
  Stare potwierdzenia nie są automatycznie rozszerzane. Do jednego
  zamówienia lub 100 wierszy na wywołanie, cztery kroki na przebieg.
- [x] Stany oczekiwania na proces, finanse i ręczną ocenę; nie zużywają
  budżetu błędów i nie przesuwają kursora. Osiem błędów, dzierżawa,
  restart i jawne ponowienie bez zmiany zgody ani przedłużenia statusu.
  Order jest czyszczony dopiero po DONE czterech adapterów domenowych.
- [x] Minimalne znaczniki per order_id i triggery na rodzicach/dzieciach.
  Przyjęte zapisy kończą się przed barierą; późne zapisy i odtworzenie
  treści są odrzucane. Zredagowane Returns są tylko do odczytu, także
  przy ponowieniu operacji. Niejednoznaczne COMPLETED/legacy do oceny.
- [x] Domyślnie false: `PRIVACY_COMMERCE_CLEANUP_ENABLED`. Rzeczywista
  bramka finansowa pozostaje zamknięta do A43.36; brak operatorowego lub
  konfiguracyjnego obejścia. Kontakt/adres pozostaje do tego uzgodnienia.
  Testy fizycznej redakcji używają wyłącznie dowodu wstrzykniętego w testach
  jednorazowych baz. Odbiór integracji finansów i starych zdarzeń to A43.36.
- [x] 458 unikalnych testów Java, w tym 46 PostgreSQL; 278 Python — PASS.
  4 grupy HTTP: siedem rzeczywistych aplikacji, sześć baz i własny Redis,
  osiem kontrolowanych peerów. Role/własność, wyłączenie domyślne, aktywne
  procesy, brak zgody legacy, restart, ponowienie i zachowanie kontaktu
  przy WAITING_A43_36 oraz obie ścieżki Gateway odebrane.
- [x] Oracle 14 baz — PASS: 75 tabel/686 kolumn/151 migracji/67 unikalności,
  42 mechanizmy/17 kopii. Inwentarz i CI obejmują nowe znaczniki i test HTTP.
  Wszystkie 15 JAR-ów zawiera aktualny wspólny moduł ochrony.

Pozostaje **6 etapów A43.36–A43.41**, następny **A43.36**. Globalne
complete=false/dataErased=false/fullErasureComplete=false. Bez dostępu
do rzeczywistych danych, wdrożenia dev/prod i restartu CC. A05 (ręczne
klucze Stripe w dev.env.sops/test.env.sops i sandbox), całe A41 i same
zdjęcia pozostają odłożone.
[Zakres i przekazanie do finansów](A43_USUWANIE_DANYCH_ZAMOWIEN.md).
Raport: `tmp/audit-a43-35/report.json`; logi i dokładne dowody w raporcie.

## Pakiet 93 — A43.36: finanse, kopie techniczne i zgodność zdarzeń

**Wykonane i odebrane lokalnie 29.10.2026, branch Audit_0.1.**

- [x] Trzy decyzje użytkownika: zachować dokumenty/rejestry finansowe; czyścić uzgodnione kopie kontaktu i treści; zachować trwałe losowe ID v2 i pseudonimowe aliasy/fingerprinty bez TTL do A43.40.
- [x] Stary backlog v1 zachowuje swój protokół. Alias oraz zgodne wpisy obu wersji inboxu i skutek mają wspólny commit/rollback. Indeksy zgodności chronią przed duplikatami po zmianie klucza i usunięciu kontaktu. Producent v2 czeka na gotowość obu czytników.
- [x] Customer wymaga nowego hasha zgody i 14 barier. Order prowadzi trwałe zadania per zamówienie/wersja, niezależne odkrywanie rekordów i siedem potwierdzeń usług. Osiem błędów wymaga jawnego ponowienia; WAIT/MORE nie wyczerpują limitu. Dowód finansowy odblokowuje pięć adapterów A43.35.
- [x] Payment/Billing/Notification redagują do 100 wierszy na tabelę i krok. Dokumenty, kwoty, waluty, ID, wyniki i deduplikacja pozostają. Niezgodne dokumenty, Stripe, aktywne procesy, otwarte DLQ i niejasne powiązania czekają bez anulowania i operacji u dostawcy.
- [x] Nowe powiadomienia konta mają jawne ID klienta. ID tokenu resetu nie staje się ID klienta; stare resety bez dowodu oraz czat pozostają do odrębnej oceny. Ponowienie starego resetu nie dopisuje automatycznie właściciela.
- [x] Atomowa blokada publikacji w Redis, ograniczony skan i usuwanie wyłącznie po dostarczeniu/ACK wszystkich faktycznych grup. Brak grup/polityki, unread i PEL chronią rekordy. SQL odrzuca przywrócenie treści i stare zapisy; późne komunikaty zachowują minimalne SUPPRESSED. Nowe korekty nadal zapisują dokument i publikują wyłącznie bezpieczne metadane.
- [x] 763 unikalnych testów Java, w tym 80 PostgreSQL i 6 Redis; 283 unikalne testy Python — PASS. 7 grup HTTP na 10 rzeczywistych aplikacjach/9 bazach oraz własnym Redis/Mailpit, 5 kontrolowanych peerów barier i 2 rzeczywiste czytniki z A43.35. Oracle: 84 tabele/740 kolumn/161 migracji, 77 unikalności i 2 indeksy zgodności. Wszystkie 15 JAR-ów zawiera aktualne wspólne klasy i klasy aplikacji.
- [x] Inwentarz: 43 mechanizmy, 6 przestrzeni Redis, 17 kopii. CI, Compose i polityki Kubernetes uwzględniają połączenia koordynatora oraz testy.

Czyszczenie finansowe i producent v2 pozostają domyślnie wyłączone. Okresy i aktywacja należą do A43.40; backup/restore i kopie do A43.37–A43.39. Globalne complete/dataErased/fullErasureComplete=false. Bez wdrożenia dev/prod, rzeczywistych danych i restartu CC.

Pozostaje **5 etapów A43.37–A43.41**, następny **A43.37**. A05 (ręczne klucze Stripe w dev.env.sops/test.env.sops oraz sandbox), całe A41 i zmiany samych zdjęć pozostają odłożone.
[Instrukcja A43.36](A43_FINANSE_I_ZDARZENIA.md); raport `tmp/audit-a43-36/report.json` zawiera dokładne dowody i logi.

## Pakiet 94 — A43.37: niezależny rejestr usunięć i restore w kwarantannie

**Status: wykonane i odebrane lokalnie 29.10.2026, branch Audit_0.1.**
Zrealizowano wszystkie trzy decyzje użytkownika: osobny rejestr poza zestawami
backupów, unieważnienie odtworzonych sesji i krótkich wyzwań dla wszystkich
kont oraz podgląd retencji bez uruchamiania kasowania.

- Łańcuch podpisów Ed25519 i szyfrowanie age, blokada współbieżnych zapisów,
  fsync przed potwierdzeniem, ochrona tożsamości/polityki i niezależny aktualny
  wskaźnik wymagany do restore. Bez e-maili, treści, tokenów i automatycznego TTL.
- Operatorowe strony i potwierdzenia w Customer; bez poprawnego podpisu nie
  rusza czyszczenie. Podpis przechodzi z koordynatora do właściwych adapterów;
  zachowano wymagane role usług i wszystkie wcześniejsze bariery.
- Po utracie źródeł odtworzono 14 baz i RDB w kontenerach bez sieci/portów,
  ponowiono 14 barier i usunięcie profilu/poświadczeń/wishlisty/logu rekomendacji/
  recenzji tymi samymi porcjowanymi zasobami SQL co aplikacja. Klucze recenzji
  pozostają minimalne. Drugi profil, TOTP/passkey, streamy/grupy/PEL i dedup
  pozostały zachowane; wszystkie odtworzone sesje i wyzwania unieważniono.
- Root pozostaje w kwarantannie; procesy handlowe i finansowe wymagają ponownego
  uzgodnienia przez istniejące adaptery. Brak polecenia otwarcia ruchu.
  `releaseAllowed/fullA36Accepted/fullA43Complete/fullErasureComplete=false`.
- CC/CLI otrzymały zapis rejestru i izolowaną próbę prywatności. Retencja
  zestawów i starych dumpów domyślnie `observe`. Surowe AOF i zewnętrzne
  snapshoty wymagają osobnej konwersji/ewidencji; brak deklaracji ich wymazania.

Odbiór: pełne testy i pakietowanie Maven; 33 testy protokołu PostgreSQL,
3 testy podpisów, 2 testy operatora/kwarantanny, 297 testów Python oraz
37 testów klasyfikacji po dodaniu kontroli źródeł restore. Macierz PostgreSQL:
14 baz, 749 kolumn, 78 zwykłych i 2 wyrażeniowe obowiązki unikalności.
Odebrano oba testy odzyskania po utracie źródeł, rzeczywisty przepływ HTTP
10 aplikacji/9 baz z 7 usługami finansowymi oraz usuwanie core po 205 rekordów,
rollback Review i ponowną rejestrację z nowym ID. Sprawdzono bieżące klasy
i zasoby w 15 JAR-ach oraz 53 zasoby Kubernetes. Dowody i dokładne ścieżki:
`tmp/audit-a43-37/report.json`.

**Nie aktywowano dev/prod, nie zmieniono rzeczywistych sekretów ani danych,
nie restartowano CC i nie uruchomiono usuwania rzeczywistych backupów.**
Ręczna konfiguracja miejsca rejestru, kluczy, publicznej konfiguracji aplikacji
i pieczy aktualnego wskaźnika pozostaje do wykonania przed aktywacją;
[instrukcja A43.37](A43_BACKUPY_I_REJESTR_USUNIEC.md). Pełne A36 nadal otwarte;
mechanizm sesji/wyzwań odebrano tu w izolacji, bez globalnego checkpointu
i odbioru wszystkich procesów.

Pozostają **4 etapy A43.38–A43.41**, następny **A43.38**. A05 (ręczne
`STRIPE_SECRET_KEY`/`STRIPE_WEBHOOK_SECRET` w `dev.env.sops` i `test.env.sops`
oraz sandbox), całe A41 i zmiany samych zdjęć pozostają odłożone.

## Pakiet 95 — A43.38: kopie wewnętrzne, sesje i stan urządzeń

**Status: wykonane i odebrane lokalnie 30.10.2026, branch Audit_0.1.**
Zrealizowano trzy wybory użytkownika, zachowując dotychczasową ochronę
nieuzgodnionych operacji przed duplikacją.

- Nowa historia `dispatch-history-v2.json` przechowuje do 200 minimalnych
  metadanych, bez odbiorcy/tematu/treści/numeru dokumentu. Ma blokadę pisarzy,
  bezpieczny odczyt i atomowy zapis. Legacy nie jest przepisywane ani usuwane;
  API wydaje wyłącznie projekcję metadanych i oznacza potrzebę oceny.
  Błąd historii po sukcesie SMTP nie zmienia go w błąd wysyłki i nie ponawia jej.
- CC otrzymało „Zakończ sesję” i backend unieważniający tylko bieżący token.
  Wyczyściliśmy widok, formularze, cache i URL pobrań; żądania są anulowane,
  a późne dane nie wracają. Inne sesje i zadania serwera pozostają aktywne.
  Naprawiono także `Origin: null` dla POST w Firefox/WebKit bez wysyłania cookies
  i bez rozluźnienia walidacji docelowego adresu ani kontroli pochodzenia.
- Sklep usuwa dane konta, formularze kontaktowe/rozliczeniowe, QR i bufory
  przed odpowiedzią wylogowania. Zachowuje koszyk gościa i czat. Zapis checkoutu
  pozostaje do uzgodnienia, ale wyszukiwanie nie przekracza bieżącego zakresu
  konta/gościa. Zachowano dotychczasowe oddzielne koszyki kont A15.
- Cache PDF ma cykliczny sweep wygasłych wpisów. Odbiór Spring potwierdza
  fizyczne zwolnienie wpisów bez kolejnego żądania; dokumenty SQL pozostają.
  PostgreSQL potwierdził usunięcie 120 wygasłych porcji eksportu w partiach
  po 50, bez naruszania aktywnego eksportu drugiego konta i profili.
- Nowe logi HTTP CC nie zawierają request line, parametrów, IP ani tokenów.
  Rotację logów odebrano na własnym jednorazowym kontenerze, bez zmiany demona.
  Macierz obejmuje 11 zasobów/procedur. Odnalezioną istniejącą kopię zamiarów
  korekt i przyjęć (`cc-browser-intents`) dopisano do rejestru: 19 kategorii kopii,
  45 mechanizmów, nadal 85 tabel/749 kolumn/163 migracje.

Odbiór: regresja **311 testów Python**, następnie **16 bieżących testów kopii**
(w tym 2 nowe przypadki sukcesu SMTP/błędu historii i niejednoznaczne legacy; zestawy się pokrywają),
**230 JavaScript**, **33 Java** z PostgreSQL, keyringami i schedulerem.
**28 prób sklepu** w Chromium/Firefox/WebKit/mobilnym Chrome oraz **52 próby CC**
(48 Chromium, po 2 Firefox/WebKit). WebKit odebrano w izolowanym kontenerze
z bibliotekami, bez instalacji zależności na hoście i bez dostępu do rzeczywistych
sekretów/runtime. Sprawdzono aktualne klasy w 15 JAR-ach, macierz w pakiecie
Customer, grafy modułów i ograniczenia logów. Raport: `tmp/audit-a43-38/report.json`.

**Nie restartowano rzeczywistego CC, nie wdrażano dev/prod, nie zmieniano sekretów
ani nie aktywowano kasowania.** Zewnętrzne backendy logowania/monitoringu nadal
wymagają dowodu z rzeczywistego środowiska; brak ich jawnej konfiguracji w
sprawdzonych źródłach nie oznacza automatycznego pokrycia. Stare treści do oceny,
retencja/aktywacja/wycofanie kluczy w A43.40; pełne A43 nadal otwarte.

Pozostają **3 etapy A43.39–A43.41**, następny **A43.39**.
Konfiguracja rejestru A43.37 pozostaje ręczna. A05 (Stripe: dwa klucze w
`dev.env.sops` i `test.env.sops` oraz sandbox), całe A41 i zmiany samych zdjęć
pozostają odłożone. [Instrukcja A43.38](A43_KOPIE_WEWNETRZNE_I_STAN_URZADZEN.md).


## Pakiet 96 — A43.39: dostawcy, pobrania i odrębne dowody czatu

**Wykonane i odebrane lokalnie 30.10.2026, branch Audit_0.1.** Decyzje
użytkownika: jawny podgląd i potwierdzenie każdej zewnętrznej tury AI;
historyczny czat bez tokenu tylko do ręcznej oceny alternatywnych dowodów
konkretnej rozmowy; po fizycznym usunięciu rozmowy status przez uprawnioną
obsługę, bez nowego sekretu i bez kopii hasha tokenu.

- CC: podgląd dokładnego JSON, celu, modelu i rzeczywiście dołączonej historii;
  obejmuje wcześniejsze lokalne odpowiedzi. Bez pobierania nowych danych
  biznesowych podczas podglądu i bez ujawniania nagłówków/kluczy dostawcy.
- API: jednorazowa zgoda przypisana do sesji, treści/historii/języka i konfiguracji
  z kluczem, ważna 5 minut; rejestr RAM do 64 wpisów, tylko skróty. Brak/stara/obca
  zgoda daje 409 przed wysyłką. Zapis konfiguracji i wylogowanie unieważniają
  zgody; zmieniony plan też odrzuca zatwierdzenie. Brak automatycznych retry
  i przekierowań, limit odpowiedzi 256 KiB. Błąd po wysyłce nie oznacza, że
  odbiorca nie otrzymał danych (`externalDelivery=unconfirmed`).
- Interfejs: anulowanie/Escape i wygaśnięcie sesji zamykają i czyszczą podgląd,
  bez wysyłki i bez zapisu zgody w storage. Odbiór wyglądu oraz dostępności
  nowego okna; poprawiony kontrast jego przycisku.
- Macierz sześciu zasobów/procedur obejmuje AI, rejestr zgód, SMTP CC,
  SMTP/External API Notification, płatności, pobrane kopie i dwa przypadki czatu.
  Nie deklaruje zdalnego usunięcia plików ani wykonania operacji u dostawców.
- Czat: alternatywny dowód wymaga oceny, sam e-mail/nazwa/ID nie wystarcza.
  API nadal opiera się na aktualnym tokenie rozmowy. Test PostgreSQL potwierdza
  404 po usunięciu rozmowy i zachowanie jedynie minimalnego wniosku MANUAL_REVIEW;
  wniosek nie jest dowodem wykonania. Nie dodano automatycznego kasowania.
- Rejestr i klasyfikacja: 46 mechanizmów, 20 kopii; nowy ograniczony zasób
  `assistant-egress-approvals` rozliczono w A43.39, poza historycznym odbiorem
  A43.38. Schemat bez zmian: 85 tabel/749 kolumn/163 migracje, 6 przestrzeni Redis.
  Nowa bramka CI sprawdza właścicieli/procedury/ograniczenia i jawne blokady dowodów.

Odbiór: 330 unikalnych testów Python w łącznym zakresie (pełna regresja 324
oraz 6 dodanych później; końcowo powtórzone 12 bramki AI, 4 kontraktu i 7 układu
modułów), 232 JS, 12 Java (5 kontrolera i 7 PostgreSQL) — PASS. CC: 50 Chromium
oraz po 2 nowe przypadki Firefox i WebKit — PASS; po poprawie wyglądu/kontrastu
powtórzono oba nowe przypadki we wszystkich trzech silnikach z axe. WebKit działał
w kontenerze bez sieci, tylko na kopii publicznych źródeł. Raport końcowy:
`tmp/audit-a43-39/report.json`.

Dowody rzeczywistych dostawców, odbiorników i realizacji żądań pozostają
REQUIRED_MANUAL_EVIDENCE; A05_DEFERRED jest jawną zależnością pełnego A43.
Nie kontaktowano dostawców, nie wysyłano danych aplikacji, nie czytano sekretów
runtime ani nie aktywowano czyszczenia. Brak restartu CC i wdrożenia dev/prod.
`fullA43Complete=false`, `fullErasureComplete=false`.

Pozostają **2 etapy A43.40–A43.41**, następny **A43.40** — docelowa macierz
retencji i decyzje użytkownika przed zależnymi operacjami. A05, całe A41 i zakres
samych zdjęć A27–A31/A40 pozostają odłożone.
[Procedura A43.39](A43_DOSTAWCY_PLIKI_I_DOWODY_CZATU.md).


## Pakiet 97 — A43.40: macierz i bramka oceny; etap nadal otwarty

**Przygotowana i wdrożona lokalnie część A43.40 — 30.10.2026, Audit_0.1.**
Nie odznaczono całego etapu. Użytkownik świadomie pozostawił wskazane poniżej
polityki nierozstrzygnięte; nie rozpoczynamy na tej podstawie zamknięcia A43.41.

Decyzje użytkownika zapisane w wersji `a43.40-2026-10-30.1`:

- Zatwierdzono profil techniczny bez aktywacji: rekomendacje 7 dni, certyfikowane
  logi zadań CC 14 dni, audyt 90 dni, kwalifikujące się treści outboxów/inboxów,
  SENT i RESOLVED DLQ 30 dni, całe rozpoznane szyfrowane backupy 14 dni.
- Finansowo-handlowe okresy/początki naliczania i wyjątki pozostawiono
  nierozstrzygnięte; chronić dane, A43.40 ma pozostać otwarty.
- Minimalne znaczniki, deduplikację i niezależny rejestr zachować bez
  automatycznego TTL do udokumentowanego wycofania wszystkich właściwych kopii
  i źródeł ponowień. Nie są anonimowe; sam wiek nie daje prawa kasowania.
- **Oba** streamy Redis pozostawiono do osobnego przeglądu, bez uznania
  źródłowych 7d/30d i deklarowanych grup za zatwierdzoną politykę.
- Docelowy okres zakończonych danych bezpieczeństwa SQL pozostawiono
  nierozstrzygnięty. Dotychczasowe 7d, enabled i warunki ochrony nie zmieniły się.

Implementacja obejmuje 26 polityk i każdą z 85 tabel/749 kolumn, 6 przestrzeni
Redis i 20 kopii. Są właściciele, cele, zegary, skutki, wyjątki/warunki,
niezależne zasady kopii oraz 43 odciski przejrzanych źródeł. Jawne zakresy pól
nie pozwalają uznać czyszczenia subject/message za usunięcie odbiorcy lub
rekordu; aktywny Redis/AOF i rejestr nie dziedziczą 14 dni backupów.
Rejestr danych nadal ma 46 mechanizmów i 20 kopii; nie dodano magazynu danych,
tabeli, migracji ani nowego wykonawcy czyszczenia.

`retention-policy-check.py` sprawdza pokrycie, decyzje, wersję/hash, źródła
oraz jawny przykład 19 ustawień do oceny. Strukturalny PASS oznacza poprawną
macierz, **nie** gotowość polityki. `--require-ready` zwraca NOT_READY / kod 2;
`intent=activate` jest odrzucane. Narzędzie nie stosuje konfiguracji i nie
przełącza istniejących usług w observe. Przykład nie jest plikiem .env ani
pełnym odbiorem środowiska. Dodano bramkę strukturalną CI oraz procedurę
monitorowania i zatrzymania odpowiednich istniejących workerów.

W regresji istniejących testów retencji wykryto nieaktualne konteksty testowe
po wcześniejszych etapach prywatności: brak rzeczywistych beanów PrivacySql /
NotificationPrivacyGuard oraz stare syntetyczne payloady i niepełne zdarzenia
płatności. Uzupełniono cztery klasy integracyjne; zabezpieczenia PostgreSQL
pozostały aktywne, bez mockowania/barier obchodzonych na potrzeby testu.
Kod produkcyjny i konfiguracja runtime nie zmieniły się.

Odbiór części technicznej: **132 Python** (91 macierz/rejestry/bramki i 41
syntetyczne backupy/logi) oraz **181 Java**, w tym **78 PostgreSQL** w 29
wybranych klasach — PASS. Kontrola YAML/CI i pięć bramek źródłowych — PASS;
`--require-ready` — oczekiwane NOT_READY / kod 2. Po naprawie danych/kontekstów
testów oraz nałożeniu się dwóch kompilacji wykonano końcowy odbiór Billing
sekwencyjnie. Nie uznano błędnego przebiegu za sukces.

Wyniki odbioru części technicznej i jawne blokady są w
`tmp/audit-a43-40/report.json`. [Polityka, zakres i procedura aktywacji](A43_POLITYKA_RETENCJI_I_AKTYWACJA.md).

**Stan pozostaje: 39 etapów wykonanych, 2 niezamknięte — A43.40 i A43.41.**
A43.40 jest w toku; do rozstrzygnięcia pozostają wskazane polityki oraz końcowe
reguły/dowody kopii, rzeczywistego środowiska i dostawców. A05 (ręczne zmienne
Stripe w dev.env.sops/test.env.sops i sandbox), całe A41 i zakres samych zdjęć
A27–A31/A40 pozostają odłożone. Nie kontaktowano dostawców, nie odczytywano
sekretów runtime, nie restartowano CC i nie aktywowano czyszczenia ani wdrożenia
dev/test/prod. `stageComplete=false`, `policyReady=false`, `fullA43Complete=false`.


## Pakiet 98 — A44.1/A44.2: obrazy w PR, cache i pomiary

**Zaimplementowane i sprawdzone lokalnie 30.10.2026, Audit_0.1. A44 nadal w toku.**
Zgodnie z planem rozpoczęto od PR i cache/pomiarów, bez zamykania A43 i bez
wznawiania odłożonych A05/A41 ani zmian samych zdjęć.

- PR ma readonly plan oraz macierz budowania/skanu równoległą do verify.
  Wybór wynika z lokalnej różnicy baza–testowany merge i zależności Maven;
  rename/delete, biblioteka, wszystkie POM-y, pakowana polityka Customer i
  konfiguracje Docker/CI mają jawne reguły. Nieznane/brakujące dane dają
  pełny zestaw, nie pominięcie; niepoprawny spis komponentów blokuje plan.
- Stały pr-image-gate sprawdza również pustą macierz i propaguje błędy.
  Usunięto filtr ścieżek wyłącznie dla PR, aby check nie znikał przy dokumentacji;
  verify pozostaje pełny, co zwiększa koszt PR wcześniej pomijanych.
- Jedna composite action buduje lokalnie i skanuje dokładnie wskazany obraz.
  PR nie ma logowania, push, podpisu ani cache export. Dla main/jawnych
  wywołań zachowano pełne 16 obrazów po verify i kolejność scan → push → Cosign;
  deployment nadal ręczny i ograniczony bramką P1.
- Cache rozdzielono per obraz/architekturę. Minimalne metadane czasu, statusów,
  commita i image ID są artefaktem na 1 dzień; surowy build record upload
  wyłączono. Nie obiecano przyspieszenia ani automatycznego cache mount Maven.

Odbiór: 28 celowanych Python — PASS; actionlint v1.7.12 z kontrolą sumy
oficjalnego wydania — PASS; kontrola YAML i bramka źródłowa — PASS.
Rzeczywisty Docker zbudował publiczny frontend i odrzucił wariant z błędnym COPY.
Lokalny Trivy 0.74.0 zgłosił 36 blokujących wpisów pakiet–CVE (27 różnych ID,
35 HIGH / 1 CRITICAL), kod 1. Bramka prawidłowo blokuje taki wynik; nie oznaczono
obrazu jako bezpiecznego i nie poluzowano reguł. Wskazania i aktualizacja baz/
warstw pozostają do rozliczenia w A44.3; nie skanowano całych 16 obrazów.

Rzeczywiste GitHub Actions, fork i branch protection nie były obsługiwane.
Właściciel repozytorium musi po pierwszym przebiegu ustawić wymagany check;
plik workflow sam nie narzuca zakazu merge. Pomiar cold/warm i kosztu CI oraz
pełny odbiór należą do A44.4. Nie publikowano ani nie wdrażano obrazów.
Raport: `tmp/audit-a44/report.json`.
[Instrukcja i plan dalszych kroków](A44_CI_I_LANCUCH_DOSTAW.md).

## Pakiet 99 — A44.3: przypięcia, poprawki frontendu i SBOM

**Zaimplementowane i sprawdzone lokalnie 30.10.2026, Audit_0.1. A44 pozostaje
w toku; następny odbiór A44.4.**

- Osiem bezpośrednich akcji przypięto SHA, cztery bazy/BuildKit digestem.
  Trivy 0.76.1, Cosign 3.1.4 i dystrybucja wrappera Maven mają sprawdzane sumy.
  Nowa bramka offline kontroluje zgodność źródeł z plikiem blokady.
- Frontend ma świeżą przypiętą bazę nginx i trzy podpisane poprawki APK,
  z osobnymi URL/SHA256 i instalacją bez sieci. Usunięto ruchome upgrade.
  Baza JRE zawiera wymagane fonty; Dockerfile wymaga ich obecności zamiast
  instalacji z ruchomego apt. Nie zmieniano samych zdjęć ani funkcji ich obsługi.
- Skan i CycloneDX dotyczą dokładnego lokalnego image ID; dowody wiążą commit,
  plik blokady, wersję/binarkę skanera, jego bazę i skróty skanu/SBOM.
  Brak/poprawienie dowodu, błędny skan i niejednoznaczny digest blokują krok.
  Zaufana ścieżka publikacji ma preflight, odczyt registry digest oraz podpis
  i atestację SBOM tego digestu. Rzeczywisty OIDC/GHCR pozostaje do A44.4.
- Tygodniowy/ręczny workflow tylko do odczytu przygotowuje kandydat i patch;
  nie stosuje zmian, nie tworzy PR i nie scala. Lokalnie sprawdzono publiczne
  źródła — brak nowej propozycji. Test niepustego patcha działa na sztucznym
  repozytorium; nie stosowano go do projektu.

Odbiór: **42 celowane testy A44/P1 + 27 regresji inwentarza A43 = 69 Python —
PASS**; actionlint obu workflow, bramki CI/przypięć i układu Maven — PASS.
Kontrola polityki retencji nadal `policyReady=false`, bez operacji na danych.
Rzeczywisty Docker potwierdził frontend non-root/read-only/healthz, obecność
fontów w JRE i SBOM. Historia wyników skanu: 36 blokujących wpisów poprzedniego
obrazu → 3 po świeżej bazie → **0 po poprawkach**, przy zachowanym progu
HIGH/CRITICAL z dostępną poprawką. To wynik jednego obrazu, nie całej aplikacji.

Raport: `tmp/audit-a44-3/report.json`, zbiorczy `tmp/audit-a44/report.json`;
końcowy Docker: `tmp/a44-3-docker-4a18a62c/report.json` i `evidence/`.
Nie publikowano, nie podpisywano w rejestrze i nie wdrażano aplikacji.
A43.40/A43.41 pozostają otwarte, A05 i całe A41 oraz zakres samych zdjęć
A27–A31/A40 pozostają odłożone. Wymagany check, rzeczywiste PR/fork,
pełne 16 obrazów i pomiary czasu/kosztu CI wymagają A44.4.

## Pakiet 100 — zapis odpowiedzi D01–D18 i wyjaśnienie D14

**30.10.2026, Audit_0.1. Aktualizacja dokumentacji, bez wdrożenia polityk
wykonawczych i bez operacji na danych.**

W [dokumencie decyzji](A43_POZOSTALE_PRACE_I_DECYZJE.md) zapisano wszystkie
odpowiedzi użytkownika: D01=A, D02=B (1 rok), D03–D05=A, D06=B, D07–D09=A,
D10=C, D11=B, D12=A, D13=C, D14=wyjaśnić, D15=A (planowane usunięcie
historycznego czatu), D16=B, D17–D18=A.
D01 zachowuje dosłowne „co 7 dni” i „do 30 dni” jako niejednoznaczne;
nie zamieniono ich w regułę usuwania dokumentów. D03 to zatwierdzony docelowy
okres 7 dni. D04/D05 nadal wymagają potwierdzenia odbiorców i stanu streamów.
D14 wyjaśniono per AI/proxy, SMTP CC/Notification, External API, Stripe,
skrzynki i lokalne odbiorniki. Nie odczytywano ich konfiguracji ani sekretów.

Podczas wdrażania pokazywać okna z pytaniami o brakujące parametry; zachować
już wybrane opcje. D10/D13/A05 pozostają odłożone. D15 oznacza plan czyszczenia,
nie wykonanie ani zgodę na nieokreślony purge rozmów. D17 zachowuje odbiór
lokalny bez aktywacji. Macierz/walidator rewizji `.1` pozostają dotychczasowym
stanem wykonawczym do jawnej aktualizacji w A43.40; A43.40/A43.41 są otwarte.
A44.1–A44.3 pozostają odebrane lokalnie, A44.4 nadal do wykonania.

**Późniejsze doprecyzowanie D01 w czacie:** użytkownik wybrał „Sprawdzanie
co 7 dni; przechowywanie przez 30 dni”. Wyjaśniono znaczenie obu liczb;
następnie wskazano dla każdej klasy początek liczenia 30 dni: końcowe
zamknięcie i uzgodnienie całego powiązanego procesu, w tym zwrotów i reklamacji.
Pozostałe parametry, w szczególności wyjątki dłuższego zachowania, nadal
wymagają odpowiedzi; przy wdrażaniu potrzebne będzie mapowanie stanów/zdarzeń.
Kolejne pytania wskazują ID/opcję i brakujący parametr, a asystent zapisuje
odpowiedzi w rejestrze. Bez zmiany polityki wykonawczej i aktywacji.

**Wyjątki D01 — kolejna odpowiedź w czacie:** użytkownik nie ma jeszcze
polityki wyjątków i wymaganych dłuższych okresów. Do ich ustalenia źródłowe
faktury, korekty i rejestry rozliczeń pozostają chronione przed usunięciem;
planowanego progu 30 dni nie stosować do ich kasowania. Nie zmieniono
odrębnych redakcji kopii technicznych ani ustawień wykonawczych.

**D02 — długość roku doprecyzowana w czacie:** 1 rok oznacza dokładnie
365 dni, stały okres również wtedy, gdy obejmuje rok przestępny. Nie przyjmować
12 miesięcy kalendarzowych. W kolejnej odpowiedzi potwierdzono zakres:
**cały łańcuch — zamówienia i pozycje, przesyłki, zwroty/reklamacje oraz
powiązane rezerwacje magazynu i rezerwacje/wykorzystania kuponów**.
Dokumenty finansowe D01 i chronione znaczniki deduplikacji pozostają wyłączone.
W kolejnej odpowiedzi wybrano skutek po 365 dniach: **usuwać pozostałe dane
osobowe i swobodne treści; zachować ID, kwoty, ilości, statusy i powiązania**.
Końcowy okres zachowania tego minimum pozostaje do osobnego ustalenia;
365 dni nie jest zgodą na usuwanie całych rekordów lub minimum/deduplikacji.
Następnie użytkownik wskazał stosowanie reguły do **wszystkich zakończonych
i uzgodnionych zakupów, także klientów z aktywnym kontem**. Nie jest ona
ograniczona do wniosków o usunięcie i nie opóźnia wcześniej uzgodnionej obsługi
takich wniosków. W kolejnym wyborze ustalono **automatyczną kwalifikację
wyłącznie przy potwierdzonym zakończeniu całego łańcucha i potwierdzonym
braku blokad dalszego zachowania; przypadki niejednoznaczne do ręcznej oceny**.
Aktywne/sporne/nieuzgodnione procesy są chronione. Konkretne źródła dowodów,
mapowanie stanów/zdarzeń i pól oraz obsługa blokad/ręcznej oceny wymagają
wykonania podczas wdrażania. Końcowy okres zachowanego minimum nadal otwarty.
Bez aktywacji i zmian danych.

**D04 — wymagane grupy streamu płatności zatwierdzone w czacie:**
`billing-service` (faktury) i `notification-service` (powiadomienia).
Jest to wybór wymaganej listy do polityki, nie dowód rzeczywistych grup,
ich postępu/pending lub braku innych odbiorców. Dla D04 i D05 użytkownik
następnie wybrał **dev i test, każde środowisko sprawdzić i opisać osobno**.
Przegląd publicznych źródeł i metadanych dostępnego Dockera: projektowy
`miniorderplatform-event-broker-1` jest zatrzymany, więc faktycznych grup,
postępu/pending ani osobnej konfiguracji dev/test nie potwierdzono.
Źródła zawierają publisher `invoice-events`, lecz w przejrzanym kodzie
aplikacji nie znaleziono konsumenta; nie dowodzi to braku zewnętrznych
odbiorców ani historycznych grup. Oba przeglądy runtime pozostają otwarte.
Bez uruchamiania usług, odczytu treści wiadomości/sekretów, zmiany konfiguracji
i aktywacji trim.

**D05 — zamierzeni odbiorcy doprecyzowani w czacie:** użytkownik nie planuje
odbiorców `invoice-events` w dev i test. Faktyczny brak odbiorców/grup oraz
sposób czyszczenia wymagają jeszcze osobnej weryfikacji każdego środowiska.
Próg 30 dni pozostaje planowany; nie uznano braku odbiorców za potwierdzony,
nie pominięto kontroli grup, nie zmieniono publishera ani nie aktywowano trim.

**D06 — warunek wycofania zatwierdzony w czacie:** wymagane łącznie zakończenie
i uzgodnienie procesu, trwałe potwierdzenie skutków, brak aktywnych ponowień
i otwartych DLQ oraz brak potrzeby usuwanej treści do odzyskania procesu.
Brak dowodu blokuje czyszczenie, niejasne przypadki do ręcznej oceny.
Minimalne ID i deduplikacja podlegają odrębnym zasadom, bez ogólnego TTL.
Konkretne mapowanie pól/stanów/dowodów i odpowiedzialny pozostają do wdrożenia.

**D07 — częściowe rozpoznanie miejsc logowania:** kod CC zapisuje logi usług
i frontendu do `RUNTIME_DIRECTORY/logs/`; Compose nie określa własnego drivera.
Odczytano wyłącznie nazwę domyślnego drivera dostępnego Dockera oraz drivera
istniejącego brokera projektu — w obu przypadkach `json-file`. Nie odczytywano
treści logów ani pełnej konfiguracji/sekretów. W kolejnych odpowiedziach
użytkownik wybrał **logi plikowe usług/frontendu uruchamianych z CC oraz logi
kontenerów Docker w dev i test; każdy magazyn zweryfikować osobno** oraz
**maksymalny okres 30 dni od zapisu wpisu**. Rotacja, kompresja i restart
nie rozpoczynają okresu od nowa. Użytkownik dopuścił **wcześniejsze usuwanie
najstarszych logów po przekroczeniu limitu rozmiaru**; pełne 30 dni historii
nie jest gwarantowane. Następnie wybrano **100 MiB na jedno źródło logów,
łącznie z rotacjami, osobno w danym środowisku**; to nie wspólny limit projektu.
Kopie, wyjątki i opiekun pozostają do ustalenia, a konfiguracja
poszczególnych środowisk do potwierdzenia. D10 i certyfikowane logi zadań CC
mają osobne zasady; ich polityk nie zmieniano.
Bez zmiany ustawień i bez aktywacji czyszczenia.

**D08 — rozpoznanie publicznej konfiguracji monitoringu:** Compose deklaruje
osobne wolumeny Prometheus/Grafana/Alertmanager, provisioning Grafany i źródło
Prometheus. Polecenia Prometheus/Alertmanager nie ustawiają jawnego okresu
retencji. Nie wywnioskowano z tego ustawień runtime ani nie przeniesiono
30 dni/100 MiB z D07 na dane monitoringu. Następnie użytkownik wybrał
**obecny lokalny stos projektu: Prometheus, Grafana i Alertmanager; pozostałe
odbiorniki i kopie do osobnego przeglądu** oraz osobno **30 dni dla próbek
metryk Prometheus**. W kolejnych odpowiedziach ustalono: **nie skracać 30 dni
metryk z powodu limitu miejsca; przekroczenie budżetu wywołuje alarm i ocenę
obsługi**. Dla Grafany wybrano **przygotowanie okresowego usuwania wyłącznie
jawnie wycofanych dashboardów**. Następnie wybrano **próg alarmowy 1 GiB dla
katalogu danych Prometheus** oraz **90 dni od potwierdzonego wycofania dashboardu
Grafany**, przy ochronie nierozliczonych zależności i blokad zachowania.
Kopie, wyjątki i właściciele pozostają otwarte. W źródłach
wersji 0.28.1 sprawdzono znaczenie natywnej retencji wyciszeń/rejestru wysyłek;
nie jest to pełne archiwum wszystkich alertów. W następnej odpowiedzi
użytkownik wybrał **natywne data.retention Alertmanagera: 5 dni (120h)**,
bez usuwania aktywnych wyciszeń po samym wieku utworzenia. Odbiór konfiguracji
pozostaje do wykonania. Punktowy odczyt łącznego rozmiaru `/prometheus` w lokalnym
kontenerze: 48 104 KiB, około 47 MiB; nie jest to prognoza 30 dni ani dowód
retencji. Nie odczytywano próbek metryk/zawartości plików, nie zmieniano konfiguracji i nie
aktywowano czyszczenia.

**D09 — lokalizacja wskazana w czacie:** `E:\Klucze` jako katalog nadrzędny.
Plan techniczny rozdziela rejestry na `E:\Klucze\privacy-journal\dev`
i `E:\Klucze\privacy-journal\test`. Odczyt samego istnienia ścieżek potwierdził
root i brak podkatalogu rejestrów; nie wyliczano zawartości i nie odczytywano
kluczy. Następnie użytkownik wybrał osobne katalogi kluczy szyfrowania i podpisu:
**`E:\Klucze\privacy-journal-keys\dev` oraz
`E:\Klucze\privacy-journal-keys\test`**. To plan lokalizacji, bez wskazania
zawartości lub ponownego użycia kluczy aplikacji. Następnie wskazano katalog
kopii **`E:\Klucze\Kopia`**; planowany podział rejestrów pod nim:
`privacy-journal\dev` i `privacy-journal\test`, bez prywatnych kluczy.
Sprawdzono wyłącznie istnienie i metadane katalogów — root oraz Kopia istnieją,
bez oznaczenia dowiązania/punktu ponownej analizy. Nie wyliczano zawartości.
Nie uznano innego folderu na E za niezależną kopię na wypadek utraty E;
użytkownik następnie wybrał **zachowanie E:\Klucze\Kopia jako kopii lokalnej
i ustalenie miejsca dodatkowej kopii na innym nośniku lub hoście później**.
Niezależna kopia pozostaje otwartym wymaganiem, bez pozornego oznaczenia wykonania.
Następnie wybrano model pieczy aktualnego wskaźnika: **osobne wpisy dev/test
w niezależnym sejfie lub menedżerze haseł, pod kontrolą uprawnionego opiekuna**.
W odpowiedzi o konkretne miejsce użytkownik podał **`SAFE`**, a następnie
doprecyzował, że **to nazwa sejfu lub folderu; nazwa programu/usługi nie została
jeszcze podana**. Nie przypisano producenta lub konta na podstawie samej nazwy.
Na kolejne pytanie o program/usługę użytkownik odpowiedział **„do ustalenia”**.
Nie wybrano narzędzia i nie odznaczono tej pozycji. Konkretna lokalizacja wpisów,
proces aktualizacji i odpowiedzialny (powiązany z D18) pozostają do uzupełnienia.
Nie utworzono wpisów ani nie
odczytywano zawartości sejfu; model nie jest dowodem aktualności/gotowości.
Przed użyciem wymagane mapowanie Linux/WSL, uprawnienia,
rozdzielenie od rzeczywistych backupów i próba operacji plikowych.
Nie tworzono katalogów/kluczy, nie kopiowano danych, nie inicjalizowano rejestrów
i nie zmieniano konfiguracji ani istniejących plików w E:\Klucze.

**D11 — zakres planu migracji wybrany w czacie:** wszystkie rozpoznane obszary:
**trwałe MFA, zaszyfrowane ustawienia AI CC, backupy i rejestr usunięć**,
z osobnym planem dla każdego obszaru i środowiska. Następnie wybrano kierunek:
**wykorzystać poprawne istniejące aktywne klucze danego obszaru i środowiska;
brakujące klucze zaplanować oddzielnie**. Konkretne klucze/przypisania wymagają
weryfikacji; nie wybrano jednego wspólnego klucza lub wymiany wszystkich na nowe.
Każdy format wymaga własnej inwentaryzacji i próby odtworzenia;
wybór nie upoważnia do nadpisywania historycznych podpisanych kopii/rejestru
ani usunięcia starych kluczy przed rozliczeniem wszystkich zależności.
Nie zmieniano danych, kluczy, SOPS ani konfiguracji runtime.

**D12 — wykaz dodatkowych kopii w trakcie uzupełniania:** użytkownik wskazał,
że zna dodatkowe miejsca lub systemy, a następnie podał **`E:\Klucze\Kopia2`**.
Ograniczony przegląd metadanych pierwszego poziomu potwierdził istnienie
zwykłego katalogu i 0 plików/podkatalogów/dowiązań, bez obcięcia wyniku
(budżet 200 wpisów). Nie odczytywano treści, nie odszyfrowywano i nie kasowano.
Następnie użytkownik wybrał przeznaczenie: **kopie baz danych i Redis — dumpy,
RDB/AOF lub pełne zestawy backupów**, dla **dev i test z osobną ewidencją;
wspólne kopie oznaczać jako obejmujące oba**. To deklarowany zakres, nie dowód
formatu/integralności istniejących artefaktów. Następnie użytkownik wybrał:
**nie ustalać jeszcze okresu dla tej lokalizacji; najpierw rozpoznać i ocenić
konkretne kopie**. Nie wybrano warunkowych 14 dni. Potwierdzono też deklarację,
że **na tę chwilę Kopia2 to jedyna dodatkowa znana użytkownikowi lokalizacja**
poza standardowymi backupami i E:\Klucze\Kopia. To deklaracja wiedzy, nie dowód
braku kopii historycznych/innych miejsc. Właściciel, ocena rzeczywistych
artefaktów, okres i dowody wygasania nadal wymagane. Nie zmieniono wcześniejszej
polityki rozpoznanych zasobów ani nie nadano TTL aktywnemu AOF.

**D14 — pierwsze wybory per odbiornik w czacie (zakres dev/test): AI asystenta
CC — C, do ustalenia; SMTP CC — A; SMTP Notification — A.** Dla SMTP zebrać
rzeczywistych i historycznych odbiorców, konfigurację, zasady i dowody osobno
dla środowisk. A nie oznacza zakończonego odbioru lub uzyskanych dowodów;
C dla AI nie wyłącza adaptera. Pozostali odbiorcy wymagają kolejnych ustaleń.
Bieżący kod CC wskazuje Mailpit `127.0.0.1:11025`, a Compose Notification
deklaruje `mailpit:1025`; nie uznano tego za potwierdzenie całego runtime/historii.
Nie odczytywano treści poczty, SOPS ani sekretów, nie wysyłano wiadomości
i nie kontaktowano dostawców. A05 pozostaje odłożone.

**D14 — dalsze odpowiedzi:** dla SMTP CC i SMTP Notification użytkownik
zadeklarował **według swojej wiedzy wyłącznie lokalny Mailpit, do weryfikacji
dev/test i historii**. Dla Notification External API wybrał **A — używany
obecnie lub historycznie; zebrać dane odbiornika i dowody**.
Sprawdzenie bieżącego `ExternalApiNotificationDeliveryService` wykazało,
że adapter nie wysyła HTTP, tylko zwraca błąd o planowanej integracji.
Użytkownik następnie wyjaśnił, że **A dotyczyło REST API samej usługi
Notification; nie potwierdza wysyłki do zewnętrznego dostawcy**. Nie przypisano
zewnętrznego odbiornika ani dowodu całej historii i nie wdrażano integracji.
Mailpit znanego projektu jest zatrzymany — odczytano
wyłącznie metadane kontenera, bez wiadomości lub uruchamiania usługi.
Użytkownik zadeklarował też **według swojej wiedzy tylko lokalne pozostawanie
poczty w Mailpit, bez znanego przekazywania do innych skrzynek**. To nie dowód
całej historii lub braku dalszych kopii; D13 nadal odłożone. Przejrzano oficjalne
opcje retencji Mailpit (wiek i liczba wiadomości). Następnie użytkownik
wybrał **maksymalnie 30 dni od przyjęcia wiadomości dla poczty i załączników
w dev/test, z możliwością wcześniejszego usuwania najstarszych po przekroczeniu
limitu liczby**. Następnie wybrano **1000 wiadomości na instancję**:
wspólny limit, jeżeli dev/test współdzielą instancję, lub osobny dla każdej
z oddzielnych instancji. To wybór użytkownika, nie przeniesienie domyślnych 500.
Ochrona źródłowych dokumentów D01 pozostaje odrębna. Nie zmieniono
parametrów Mailpit i nie aktywowano nowej retencji.

**D15 — zakres czatu i miejsce dowodów wybrane w czacie:** wyłącznie rozmowy
objęte zweryfikowanym indywidualnym wnioskiem, z ręczną oceną zakresu usunięcia;
nie wybrano ogólnej retencji/porządkowania rozmów po wieku. Miejsce rejestru
spraw i dowodów: **`E:\Klucze\Rejestr`**, odrębne od minimalnego rejestru D09.
Test istnienia katalogu pozytywny; nie wyliczano zawartości ani nie odczytywano
dowodów. Następnie wybrano **90 dni od bezpiecznego zamknięcia sprawy dla
materiałów dowodowych i załączników**, z ochroną aktywnych/spornych spraw
i wymaganych blokad zachowania. Następnie dla **minimalnego zapisu sprawy
(ID, daty, wynik, bezpieczne kody), bez dowodów/załączników, wybrano 365 dni
od bezpiecznego zamknięcia z ochroną wymaganych wyjątków**. Nie rozszerzono
90/365 dni na D09, protokół usunięcia lub deduplikację.
We wspólnej odpowiedzi D15/D18 wskazano role **„właściciel”** i następnie
**„zastępca”**; nie wskazano odrębnej obsługi D15. Następnie dla D18 wybrano
**lokalne panele/statusy oraz raport/log, sprawdzane ręcznie przez obsługę,
bez powiadomień poza aplikacją**. Sprawa wymagająca reakcji ma być kierowana
**jednocześnie do właściciela i zastępcy od pierwszego alarmu**. To reguła
organizacyjna, bez deklarowania automatycznego doręczenia lub reakcji.
Mapowanie uprawnionych osób, lokalna ścieżka obsługi i zatrzymanie konkretnego
workera nadal wymagają przygotowania i sprawdzenia. Źródłowy Alertmanager ma
odbiornik `local-console`; nie dodawano integracji zewnętrznej.
Nie tworzono rejestru, nie nadano praw i nie usuwano rozmów, dowodów ani kopii.

W dokumencie decyzji zestawiono stan po uzupełnieniach: pytania dotychczas
zadane mają zapisane odpowiedzi. Nadal otwarte pozostają wyjątki D01,
końcowy okres minimum D02, niezależna kopia i narzędzie SAFE D09,
ocena konkretnych kopii D12 oraz AI i dalsze dowody odbiorników D14.
D10/D13/D16 pozostają odłożone. Przeniesienie uzgodnień do nowej rewizji
wykonawczej, implementacja brakujących mechanizmów i odbiór nadal przed nami;
nie zamknięto A43.40/A43.41 i nie uruchomiono nowego czyszczenia.

**Kolejne pytania do uzupełnienia A43:** przygotowano
[listę P01–P09](A43_PYTANIA_UZUPELNIAJACE.md), powiązaną z D01/D02,
D09, D14, D15/D18 i wyjątkami D07/D08/D14/D15. Zawiera opcje oraz konkretne
parametry do odpowiedzi w czacie. Odrębnie wskazano zadania techniczne,
w tym przegląd Redis i inwentaryzację kopii D12 przed pytaniem o ich okresy.
Nie zmieniono wcześniejszych decyzji, nie wznowiono odłożonych zakresów
i nie zlecono aktywacji.

**P01/D01 — odpowiedź użytkownika:** nadal do ustalenia; zachować ochronę
tych danych przed usunięciem. Zapisano B dla pytania P01, bez zmiany
wybranego wcześniej D01=A. Nie ustalono wyjątków/dłuższych okresów,
nie aktywowano usuwania dokumentów finansowych ani rejestrów rozliczeń.
Następne pytanie: P02/D02 — końcowy okres zachowanego minimum handlowego.

**P02/D02 — odpowiedź użytkownika:** A — konkretny okres. Następnie podano
**90 dni** dla zachowanego minimum handlowego, a następnie wskazano początek:
**faktyczne, potwierdzone zakończenie redakcji**. Minimum pozostaje jeszcze
przez 90 dni; opóźnienie redakcji przesuwa początek okresu. Następnie
zaakceptowano wszystkie przedstawione zabezpieczenia: otwarte/wznowione
sprawy i spory, wymagane zachowanie oraz zależności D01, odtworzenia,
ponowień i deduplikacji blokują usuwanie. Brak dowodu zwolnienia zależności
blokuje czyszczenie; niejasne przypadki kierować do ręcznej oceny.
Użytkownik nie zna dodatkowych wyjątków — do weryfikacji przy wdrażaniu.
Chronionych znaczników i rejestru usunięć nie obejmuje 90 dni.
P02 uzupełnione na poziomie decyzji; wdrożenie i dowody nadal przed nami.
Wybór P02=A nie zmienia D02=B i progu redakcji 365 dni. Zachowane minimum
nie jest kwalifikowane do usunięcia wyłącznie po wieku; nie uruchomiono jego
usuwania ani nowego czyszczenia.

Następne pytanie: P03/D09 — dodatkowa kopia rejestru na innym nośniku lub
hoście; dotychczasowa E:\Klucze\Kopia pozostaje lokalna.

**P03/D09 — odpowiedź użytkownika:** A — poda lokalizację na innym nośniku
lub hoście dla dev/test. Następnie podano **E:\Klucze\Kopia3** jako proponowaną
lokalizację. Użytkownik potwierdził następnie: **zwykły folder na E,
dodatkowa kopia lokalna; lokalizację niezależną ustalić później**.
Nie uznano Kopia3 za kopię na niezależnym nośniku/hoście. Podział lokalnej
kopii na dev/test do przygotowania przy konfiguracji. P03 ma odpowiedź,
ale niezależna lokalizacja pozostaje odłożona. Bez tworzenia katalogów lub kopiowania danych;
gotowość niezależnej kopii nadal niepotwierdzona.

Następne pytanie: P04/D09 — konkretne narzędzie dla sejfu/folderu SAFE.

**P04/D09 — odpowiedź użytkownika:** B — narzędzie nadal do ustalenia.
Nie przypisano programu/usługi do nazwy SAFE. P05, dotyczące obsługi
aktualizacji niezależnego wskaźnika, pozostaje zależne od wyboru narzędzia;
nie zadano go ani nie oznaczono jako rozstrzygnięte. Nie zmieniono konfiguracji,
nie utworzono wpisów w sejfie i nie uznano rejestru/restore za gotowe.
Następne niezależne pytanie: P06/D14 — użycie osobnego endpointu AI w dev/test.

**P06/D14 — odpowiedź użytkownika:** B — według jego wiedzy tylko tryb
wbudowany CC; konfigurację i historię trzeba zweryfikować. Deklaracja dotyczy
dev/test, bez wskazanych różnic; każdy zakres nadal wymaga osobnego dowodu.
W rejestrze zastąpiono wcześniejsze AI=C aktualną deklaracją; nie uznano tego
za dowód braku osobnego endpointu lub historycznej wysyłki. Nie zmieniono
konfiguracji AI, nie wyłączono adaptera i nie kontaktowano dostawców.
Następne pytanie: P07/D15 i D18 — przypisanie ról do uprawnionej obsługi.

**P07/D15 i D18 — odpowiedź użytkownika:** „tak” do przykładu oznaczeń.
Przyjęto **właściciel: operator-1; zastępca: operator-2; środowiska: dev i test**
jako przypisanie organizacyjne. Istnienie kont, obsada dwóch osób, ich dostęp
i niezależność nie zostały potwierdzone; zweryfikować podczas wdrażania.
Nie tworzono kont i nie nadano uprawnień. Następne pytanie: P08/D15 —
dodatkowa ocena niejednoznacznych spraw.

**P08/D15 — odpowiedź własna użytkownika:** dodatkową ocenę niejednoznacznych
dowodów lub treści prowadzi **wyłącznie właściciel (operator-1)** w dev/test.
Zapis zastępuje proponowany wariant z drugim oceniającym; nie wymagać udziału
drugiej osoby i nie przekazywać tej oceny zastępcy. Przy braku właściciela lub
wystarczającego dowodu sprawa pozostaje nierozstrzygnięta. Weryfikacja dostępu
i przygotowanie kontrolowanego zapisu oceny nadal do wykonania. Nie nadano
praw, nie zmieniono wspólnej obsługi alarmów i nie usunięto danych.
Następne pytanie: P09 — dodatkowe wyjątki retencji D07/D08/D14/D15.

**P09 — odpowiedź użytkownika:** C — wyjątki nadal do ustalenia.
Nie uznano polityk logów, monitoringu, poczty Mailpit i materiałów spraw
za kompletne do aktywacji. Nie domniemywano braku wyjątków; wcześniej
uzgodnione okresy, limity i ochrona spraw/zależności/blokad pozostają.

**Bieżąca runda zakończona:** osiem pytań otrzymało zapisane odpowiedzi;
P05 nie zadano, bo zależy od nierozstrzygniętego wyboru narzędzia P04.
W dokumencie pytań dodano tabelę wyników i dalszych zależności. P02 jest
uzupełnione jako decyzja; D01, niezależna lokalizacja i narzędzie D09,
obsługa wskaźnika P05 oraz wyjątki P09 nadal otwarte. P06/P07 wymagają
weryfikacji środowiska i obsady, a D12 — najpierw inwentaryzacji artefaktów.
Nie wznowiono zakresów odłożonych, nie zamknięto A43.40/A43.41 i nie
zmieniono konfiguracji, uprawnień ani działania istniejących mechanizmów.
Obowiązuje D17: lokalny odbiór bez nowej aktywacji czyszczenia.

## Pakiet 101 — A43.40: rewizja macierzy i bramki po uzupełnieniu decyzji

**30.10.2026, Audit_0.1. Lokalny zakres niezależny od otwartych decyzji.**

- [x] Wersja `a43.40-2026-10-30.2` oraz maszynowy zapis wszystkich 18 pozycji
  w `scripts/qa/contracts/retention-decisions.json`; odróżnienie planu,
  decyzji częściowej, odłożenia i brakującego wykonania/dowodu.
- [x] D03: zatwierdzone SQL 7 dni w macierzy; utrzymane zegary SQL, ochrona
  rodzin refresh, aktywnych metod i dotychczasowe włączenie workera.
- [x] D02/P02: plan redakcji 365 dni, minimum 90 dni od potwierdzonej redakcji
  i przyjęte blokady. Polityka tabel handlowych pozostaje zachowawcza do
  mapowania pól/stanów i wdrożenia mechanizmu; nie dodano nowego workera.
- [x] D04/D05 i D07–D18: zapisane wybrane progi, zakresy, role, lokalne kopie
  i otwarte zależności. P08 respektuje ocenę wyłącznie przez właściciela.
- [x] Walidator wiąże macierz z odciskiem przejrzanych odpowiedzi; odrzuca
  podmianę decyzji nawet po przeliczeniu hasha źródła, dawną rewizję/kandydata,
  zmianę zegarów/ochrony SQL oraz próby aktywacji. Przykład observe ma nową
  wersję/hash i jawne SQL 7d, bez zmiany enabled.
- [x] **15 testów jednostkowych bramki PASS**, obejmujących negatywne próby
  rozszerzenia retencji na chronione dane, zmianę właściciela oceny,
  uznanie Kopia3 za niezależną, pominięcie wyjątków i wymuszenie aktywacji.
- [x] Ocena przykładu observe PASS; `--require-ready` zwraca **NOT_READY / 2**,
  zgodnie z otwartymi decyzjami i dowodami. Oba raporty: `activationAllowed=false`,
  `executedDataOperations=0`; brak odczytu sekretów i inspekcji runtime.

Dowody: `tmp/audit-a43-40-revision2/observe.json` oraz
`tmp/audit-a43-40-revision2/readiness.json`.

**Dalej w A43.40:** mapowanie pól, stanów i dowodów kwalifikacji D02,
przygotowanie mechanizmu i izolowanych prób; równolegle możliwe przeglądy
pozostałych zasobów. D01/P09, niezależna kopia/narzędzie/piecza D09 i zależne
P05 pozostają otwarte. A43.40/A43.41 niezamknięte. Nie zmieniono usług, SQL,
konfiguracji dev/test/prod, Redis, SOPS ani uprawnień; nie wykonano czyszczenia.
A05, całe A41 i zakres samych zdjęć A27–A31/A40 nadal odłożone.

## Pakiet 102 — A43.40: D02, mapa pól i kwalifikator retencji

**30.10.2026, Audit_0.1, rewizja `a43.40-2026-10-30.3`.**

- [x] Jawne mapowanie **10 tabel/127 pól**: **20 redakcji, 91 minimum,
  16 protokołu bez automatycznego TTL**, z wyłączeniem pięciu tabel D01.
  Kontrakt `scripts/qa/contracts/commerce-retention.json` sprawdza pokrycie
  DDL, przejrzane źródła stanów, uczestników i odciski kodu/migracji.
- [x] **D02-F01 — nowa odpowiedź użytkownika:** redagować także datę dostawy,
  przedział dostawy i punkt odbioru po 365 dniach. Decyzja zapisana w dokumencie
  pytań, rejestrze i maszynowej rewizji `a43-decisions-2026-10-30.2` z nowym
  odciskiem walidatora. Minimum, zabezpieczenia i D01 zachowane.
- [x] `CommerceRetentionAssessment`: osobna ocena D02 dla aktywnych kont,
  bez używania account fences jako automatycznego dowodu retencji. Wymagane
  potwierdzenia ośmiu usług, zgodna tożsamość/wersja/generacja/faza/polityka,
  pozytywne dowody braku blokad, aktualny rejestr i osobne zwolnienie zależności
  minimum. Brak, sprzeczność, stan aktywny lub zegar z przyszłości blokują ocenę.
- [x] Zegar 365 dni od ostatniego potwierdzonego uzgodnienia oraz 90 dni od
  ostatniej potwierdzonej redakcji właściwego zakresu, bez resetowania przez
  restart/odczyt. Wynik DUE_FOR_REVIEW nie jest zgodą na zapis;
  activationAllowed zawsze false.
- [x] Zakupy gościnne: brak konta reprezentowany wyłącznie w modelu w pamięci;
  dowody nadal wiąże konkretne zamówienie/wersja/generacja. Dwa zamówienia
  gościnne nie mogą wymieniać potwierdzeń. Bez łączenia po e-mailu i bez
  zapisywania fikcyjnego klienta lub zmiany customer_id w SQL.
- [x] **159 testów Java platform-security PASS**, w tym **12 nowych testów
  kwalifikatora**; **17 testów Python bramki PASS**, w tym ochrona mapy pól,
  protokołów, D01, zegarów i brakujących dowodów/implementacji.
- [x] CLI nowej kontroli używa pełnej bramki A43.40. Przykład observe PASS;
  gotowość **NOT_READY / kod 2**, bez aktywacji i operacji na rzeczywistych danych.

Dowody: `tmp/audit-a43-40-d02-assessment/report.json`, `observe.json`,
`readiness.json`; testy Java: `tmp/a43-d02-java-tests.log` i raporty Surefire.
[Zakres, mapa i granice zaufania kwalifikatora](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Pozostałe wykonanie D02:** trwały model generacji i dowodów całego łańcucha,
źródła potwierdzeń i blokad, migracje oraz ograniczone workery redakcji/minimum,
ponowna kontrola w transakcji i odbiór baz/współbieżności/awarii/restore.
Obecne triggery i adaptery usuwania konta nie są gotowym wykonawcą tej retencji.
Nie podłączono kwalifikatora do usług/endpointów, nie zmieniono SQL, schematów,
SOPS, Redis, uprawnień lub konfiguracji środowisk. A43.40/A43.41 pozostają
otwarte wraz z wcześniejszymi decyzjami i dowodami; odłożone zakresy bez zmian.

## Pakiet 103 — A43.40: D02, trwałe dowody i ograniczony wykonawca oceny

**30.10.2026, Audit_0.1, rewizja `a43.40-2026-10-30.4`.**

- [x] Przygotowano migrację V27 Order z tabelami `commerce_retention_jobs`
  i `commerce_retention_evidence`: **26 pól metadanych**, ograniczone enumy,
  FK generacji/fazy, jedna bieżąca generacja na zamówienie i indeks kolejki.
  Bez kontaktu, dowolnego payloadu lub automatycznego TTL.
- [x] `CommerceRetentionJobs`: idempotentne dowody, niezmienne zegary,
  unieważnianie sprzecznej generacji bez kasowania historii, rewizja dowodów,
  losowa dzierżawa 15 sekund i SKIP LOCKED po jednym zadaniu. Opóźnione wyniki,
  stary token/faza/tożsamość lub dowody o innej rewizji nie zapisują oceny.
- [x] Zapis wyniku ponownie sprawdza czas rzeczywisty dzierżawy, token i rewizję.
  Oczekiwanie nie zużywa prób; osiem przerwanych/błędnych przejęć zatrzymuje
  automatyczne ponawianie, bez resetu przez restart/prepare. Odzyskiwanie
  wygasłego ostatniego przejęcia ograniczone do 25 rekordów.
- [x] Triggery lokalnych zmian Order/pozycji/sagi unieważniają generację,
  także przy zmianie bez podbicia wersji oraz przy przeniesieniu powiązania.
  Dowody zakupów gościnnych pozostają po jednym order_id z SQL NULL klienta.
- [x] Uzupełniono inwentarz, klasyfikację i macierz: **87 tabel/775 pól,
  47 mechanizmów**, 6 przestrzeni Redis i 20 kategorii kopii. Nowe metadane
  mają jawny zakres operacyjny, bez surowego eksportu i bez automatycznego TTL.
- [x] **12 nowych testów PostgreSQL + 10 regresyjnych PostgreSQL + 12 testów
  kwalifikatora Java PASS**, bez pominięć. Testy sprawdziły rzeczywiste
  migracje, restart, idempotencję, gości, stare wyniki, równoległe przejęcia,
  wygaśnięcie podczas oczekiwania na blokadę, błędy i nienaruszone dane zakupu.
- [x] **54 testy Python PASS**: 17 retencji i 37 klasyfikacji. Bramki
  inwentarza oraz kopii wewnętrznych/zewnętrznych PASS. Przykład observe PASS,
  gotowość **NOT_READY / kod 2**; brak aktywacji i operacji w rzeczywistych bazach.

Dowody: `tmp/audit-a43-40-d02-store/report.json`, `observe.json`,
`readiness.json` oraz raporty czterech bramek; `tmp/a43-d02-store-tests.log`
i raporty Failsafe/Surefire. [Zakres i dalsze blokady](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Dalej:** zaufane adaptery dowodów usług, unieważnianie generacji całego
łańcucha i odtworzonych potwierdzeń, aktualny rejestr/blokady, migracje
redakcji i workery biznesowe, pełny odbiór współbieżności/awarii/restore.
Store nie jest beanem, endpointem ani harmonogramem. Wynik DUE_FOR_REVIEW
pozostaje oceną, nie zgodą na usunięcie. W dev/test/prod nie wykonano migracji,
nie podłączono workera, nie zmieniono SOPS/Redis/uprawnień i nie czyszczono danych.
A43.40/A43.41 i wcześniejsze braki pozostają otwarte; odłożone zakresy bez zmian.

## Pakiet 104 — A43.40: unieważnianie przez źródła i izolowany restore

**31.10.2026, Audit_0.1, rewizja `a43.40-2026-10-31.1`.**

- [x] Przygotowano kanał `POST /internal/privacy/commerce-retention/invalidate`,
  domyślnie nieobecny. Istniejąca tożsamość SERVICE i allowlista siedmiu usług
  są wymagane; klient, operator, inne usługi i SERVICE bez nazwy są odrzucane.
  Kanał nie przyjmuje pozytywnych dowodów ani zwolnienia blokad.
- [x] Komenda wiąże orderId/generację/wersję/fazę; Store ponownie sprawdza
  zakres pod blokadą. Nieistniejący, stary lub powtórzony zakres ma tę samą
  pustą odpowiedź 202 bez ujawniania istnienia danych. Nowa generacja pozostaje
  chroniona przed opóźnionym zawiadomieniem.
- [x] Wspólny klient używa tożsamości usługi i ograniczonego HTTP, nie kopiuje
  customer_id ani danych kontaktowych. Sprawdza origin i pustą odpowiedź 202,
  odrzuca poświadczenia/ścieżkę/query/fragment w origin oraz błędny protokół.
  Trwałe źródłowe outboxy i automatyczne wywołania w usługach nadal niepodłączone.
- [x] Replay po restore unieważnia wszystkie bieżące generacje D02 przed
  sesjami i odtwarzaniem wniosków, również dla zdrowych kont/gości i przy pustym
  dzienniku. Tylko zasoby własnego drillu, partie po 100, limity blokad/instrukcji,
  końcowe sprawdzenie; błąd/limit/częściowy schemat utrzymuje kwarantannę.
  Dowody, historia i dane biznesowe pozostają; releaseAllowed zawsze false.
- [x] **43 testy Java PASS**: 23 PostgreSQL (13 metadanych, 10 regresji),
  12 kwalifikatora, 4 transportu i 4 autoryzacji/warunku włączenia. **62 Python
  PASS**: 17 retencji, 37 klasyfikacji i 8 unieważniania restore.
- [x] **Pełny drill szyfrowanego backupu 14 baz + Redis PASS**: utrata własnych
  źródeł testowych, odtworzenie, niezależny podpisany dziennik, unieważnienie
  ocen wszystkich kont/gościa, pusty dziennik, idempotencja, zachowanie dowodów
  i zamówień oraz biznesowych streamów/grup/PEL/deduplikacji i trwałego MFA.
- [x] Macierz, odciski źródeł i przykład observe zaktualizowane; bramki
  inwentarza, klasyfikacji i kopii PASS. Gotowość **NOT_READY / kod 2**.
  Bez nowych tabel/migracji: 87 tabel/775 pól, 47 mechanizmów i 20 kategorii kopii.

Dowody: `tmp/audit-a43-40-invalidation-restore/report.json`, `observe.json`,
`readiness.json` i raporty bramek; pełny drill
`tmp/privacy-recovery-ecafcf45/report.json`; logi
`tmp/a43-d02-invalidation-tests.log`, `tmp/a43-d02-restore-tests.log`
oraz raporty Surefire/Failsafe. [Zakres i pozostałe wykonanie](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Dalej:** trwałe źródłowe zawiadomienia związane atomowo ze zmianą biznesową,
pełne bindingi generacji, zaufane świeże potwierdzenia procesu/blokad/rejestru,
workery redakcji i minimum oraz odbiór całego łańcucha. Izolowany restore nie
stanowi wdrożeniowego dopuszczenia środowiska do ruchu. Nie włączono endpointu
w dev/test/prod ani nie uruchomiono czyszczenia lub nowych wywołań biznesowych.
Nie odczytywano rzeczywistych sekretów, nie zmieniono uprawnień i nie wznowiono
A05/A41/zdjęć. A43.40/A43.41 i wcześniej otwarte decyzje pozostają otwarte.

## Pakiet 105 — A43.40: trwały outbox unieważnień Returns

**31.10.2026, Audit_0.1, rewizja `a43.40-2026-10-31.2`.**

- [x] Przygotowano migrację V6 Returns: binding generacji/wersji/fazy,
  rewizja źródła, blokada po restore i trwały outbox. Dwie tabele/25 pól;
  pełny rejestr **89 tabel/800 pól**, 47 mechanizmów i 20 kategorii kopii.
  Bez kontaktu, customer_id i treści; metadane pseudonimowe bez automatycznego TTL.
- [x] INSERT/UPDATE/DELETE zgłoszeń, pozycji i zdarzeń zapisuje unieważnienie
  w tej samej transakcji. Rollback wycofuje oba skutki; przeniesienie rekordu
  obejmuje oba zamówienia. Zmiana samego updated_at jest pomijana.
- [x] Wspólny store źródła rejestruje binding przez oczekiwaną epokę i wersję;
  odrzuca spóźnione komendy. Powtórzenie tej samej generacji nie czyści
  unieważnienia. Rejestracja nie jest pozytywnym dowodem uzgodnienia.
- [x] Nadawca bez harmonogramu: jeden rekord SKIP LOCKED, losowa dzierżawa
  15 sekund, HTTP poza transakcją, osiem prób i odstęp błędu 30 sekund.
  ACK wymaga bieżącej rewizji i żywej dzierżawy; równoległa zmiana odrzuca
  stary ACK. Restart nie zeruje budżetu, SENT nie zwalnia blokad źródła.
- [x] Izolowany restore blokuje wszystkie bindingi Returns, także wcześniej
  brudne. Epoka/rewizja zwiększana raz; dzierżawy wycofane bez resetu prób,
  historia/SENT zachowane. Brak API zwolnienia trwałej blokady restore.
  Partie po 100, limity 2/5 sekund, częściowy schemat/błąd utrzymują kwarantannę.
- [x] **60 testów Java PASS**: 12 nowych źródła PostgreSQL, 13 metadanych,
  15 regresji czyszczenia Order/Returns, 12 kwalifikatora, 4 klienta transportu
  i 4 autoryzacji/warunku włączenia. Pierwsza próba testu zdarzenia wykryła
  brak wymaganego autora w fixture; uzupełniono dane testowe, odbiór końcowy PASS.
- [x] **66 testów Python PASS**: 17 retencji, 37 klasyfikacji, 12 restore.
  Bramki inwentarza, klasyfikacji i kopii wewnętrznych/zewnętrznych PASS;
  źródłowe metadane chronione przed automatycznym TTL i fałszywą gotowością.
- [x] **Pełny szyfrowany drill 14 baz + Redis PASS**: utrata własnych źródeł,
  podpisany dziennik, zablokowanie źródeł, wycofanie ósmej dzierżawy bez resetu,
  zachowanie outboxa, pusty/powtórny replay, konflikty i stary head blokowane.
  Biznesowe streamy/grupy/PEL/deduplikacja oraz trwałe MFA zachowane.
- [x] Macierz, odciski źródeł, przykład observe i dokumenty zaktualizowane.
  Gotowość nadal **NOT_READY / kod 2**, activationAllowed=false.

Dowody: `tmp/audit-a43-40-source-outbox/report.json`, `observe.json`,
`readiness.json`, raporty bramek oraz `tmp/privacy-recovery-68a7ed34/report.json`.
Logi: `tmp/a43-d02-source-tests.log`, `tmp/a43-d02-source-retest.log`,
`tmp/a43-d02-source-restore.log` i raporty Surefire/Failsafe.
[Szczegóły i pozostała implementacja](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Dalej:** sześć pozostałych źródeł, trwały koordynator bindingów i podłączenie
publikatorów, zaufane świeże dowody procesów/blokad/rejestru, workery redakcji
i minimum oraz końcowy odbiór łańcucha/restore. Nie podłączono nowego beana,
harmonogramu ani endpointu. V6 wykonano tylko w jednorazowych bazach QA;
dev/test/prod, SOPS, rzeczywiste klucze i dane nie zostały zmienione.
Nie aktywowano czyszczenia i nie dopuszczono odtworzonego środowiska do ruchu.
A43.40/A43.41 pozostają otwarte; D01/P09, D09 i dowody środowiskowe nadal
wymagają uzupełnienia. A05, całe A41 i zakres samych zdjęć pozostają odłożone.

## Pakiet 106 — A43.40: zawiadomienia Inventory, Promotion i Delivery

**31.10.2026, Audit_0.1, rewizja `a43.40-2026-10-31.3`.**

- [x] Dodano migracje Inventory V12, Promotion V10 i Delivery V9:
  wspólny z Returns protokół bindingu/outboxa; bramka sprawdza zgodność
  wspólnej części SQL oraz odciski każdej migracji. Sześć tabel/75 pól;
  rejestr **95 tabel/875 pól, 168 migracji**, 47 mechanizmów i 20 kategorii kopii.
- [x] Trwały zapis unieważnienia jest częścią transakcji zmian rezerwacji
  magazynowych i ich pozycji, rezerwacji/wykorzystań kuponów oraz przesyłek,
  w tym stanu synchronizacji. INSERT/UPDATE/DELETE i przeniesienie rekordu
  obejmują właściwe zamówienia; rollback wycofuje także zawiadomienie.
  Zmiana samego updated_at jest pomijana. Globalne stany produktów, reguły
  promocji i katalog dostaw pozostają poza tym zakresem.
- [x] Zachowano sprawdzanie epoki/wersji/fazy, blokadę po restore,
  ograniczone ponowienia i ochronę przed starym ACK. Brak danych kontaktowych,
  customer_id i treści w outboxach; wszystkie osiem tabel źródłowych bez TTL.
  Surowe stany wyłączone z automatycznego eksportu, kontekst osobowy do
  uprawnionej oceny ręcznej.
- [x] Izolowany restore odwiedza cztery źródła i raportuje je osobno.
  Częściowy schemat dowolnego źródła zatrzymuje przebieg przed sesjami/wnioskami;
  nieznane źródło odrzucone przed SQL. Historyczny brak obu tabel raportowany
  osobno, bez dopuszczenia środowiska. Bez resetowania budżetu prób i kasowania
  historii/SENT; partie po 100 oraz dotychczasowe limity 2/5 sekund zachowane.
- [x] **60 testów Java PASS**: 18 nowych PostgreSQL trzech źródeł,
  12 źródła Returns, 14 regresji czyszczenia czterech usług,
  12 kwalifikatora oraz 4 transportu. Pełne migracje na jednorazowych bazach.
- [x] **68 testów Python PASS**: 17 retencji, 37 klasyfikacji, 14 restore.
  W tym ochrona zakresu nowych źródeł, brak TTL/aktywacji, błąd późniejszego
  źródła zatrzymuje cały przebieg i brak SQL dla nieznanej nazwy.
- [x] Inwentarz, klasyfikacja i bramki kopii wewnętrznych/zewnętrznych PASS;
  observe PASS, gotowość **NOT_READY / kod 2**, activationAllowed=false.
- [x] **Pełny szyfrowany drill 14 baz + Redis PASS**: osiem bindingów
  i cztery dzierżawy objęte blokadą we wszystkich czterech źródłach.
  Powtórny i pusty replay, utrata własnych źródeł, konflikty i stary head
  sprawdzone; historia/outboxy, biznesowe streamy/grupy/PEL/deduplikacja
  oraz trwałe MFA drugiego konta zachowane, sesje unieważnione.

Dowody: `tmp/audit-a43-40-sources-ipd/report.json`, `observe.json`,
`readiness.json` i raporty bramek; logi `tmp/a43-d02-sources-ipd-tests.log`,
`tmp/a43-d02-sources-ipd-restore.log` oraz raporty Surefire/Failsafe.
Pełny drill: `tmp/privacy-recovery-529c185e/report.json`.
[Szczegóły i dalsze prace](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Dalej:** źródła Payment, Billing i Notification, trwały koordynator
bindingów/publikacji, świeże zaufane dowody procesów/blokad/rejestru,
workery biznesowe i końcowy odbiór. Nie podłączono harmonogramu, beana ani
endpointu. Migracje wykonano tylko w jednorazowych bazach QA; nie zmieniono
dev/test/prod, SOPS, kluczy i uprawnień. Nie aktywowano czyszczenia ani
automatycznej publikacji, nie zwolniono kwarantanny restore.
A43.40/A43.41 pozostają otwarte; decyzje i odłożone A05/A41/zdjęcia bez zmian.

## Pakiet 107 — A43.40: zawiadomienia Payment, Billing i Notification

**31.10.2026, Audit_0.1, rewizja `a43.40-2026-10-31.4`.**

- [x] Dodano migracje Payment V21, Billing V16 i Notification V18.
  Wspólny protokół metadanych/outboxa jest zgodny z Returns; odciski obejmują
  migracje wszystkich ośmiu uczestników łańcucha. Sześć nowych tabel/75 pól:
  rejestr **101 tabel/950 pól, 171 migracji**, 47 mechanizmów i 20 kategorii kopii.
- [x] Zmiany płatności, refundów, follow-up/gates/outboxów i webhooków,
  faktur/pozycji/korekt oraz powiązanych powiadomień/inboxów/identyfikatorów
  zdarzeń zapisują zawiadomienie atomowo z dozwoloną operacją. Rollback
  wycofuje oba skutki; sama zmiana updated_at jest pomijana.
- [x] Refund i korekta uwzględniają zapisane order_id i kanonicznego rodzica;
  wszystkie stare/nowe zakresy są deduplikowane i blokowane w stałej kolejności.
  To unieważnienie, nie naprawa niespójnej historii lub dowód własności.
  Pozycje i webhooki używają rodzica; brak przypisania przez nazwę zdarzenia.
- [x] Notification rozróżnia typy zamówienia, konta i czatu. Sama zgodność
  source_id nie wiąże konta/rozmowy z zakupem. DLQ uwzględnia zamówieniowy
  zapis i rodzica; inbox używa trwałego order_id, powiązania identyfikatorów
  zdarzeń korzystają wyłącznie z istniejących rekordów inboxa.
- [x] Zachowano istniejące guardy i ochronę powiązanych inboxów przed
  usunięciem. Brak powiązania nie tworzy dowodu kompletności; historyczne
  kopie i niejednoznaczności nadal do oceny. Ochrona D01, kwoty, numeracja,
  klucze i deduplikacja bez nowego czyszczenia. Czternaście źródłowych tabel
  metadanych bez TTL i poza automatycznym eksportem surowego stanu.
- [x] **103 testy Java PASS**: 27 nowych PostgreSQL (Payment 8, Billing 10,
  Notification 9), 12 źródła Returns, 48 regresji inboxów/retencji,
  12 kwalifikatora i 4 transportu. Pierwsza fixture Payment nie miała
  wymaganego idempotency_key; uzupełniono dane testowe, końcowy odbiór PASS.
- [x] **68 testów Python PASS**: 17 retencji, 37 klasyfikacji, 14 restore.
  Sprawdzono także przerwanie całego replay przy częściowym schemacie
  ostatniego źródła Notification. Inwentarz, klasyfikacja i kopie PASS;
  observe PASS, gotowość **NOT_READY / kod 2**.
- [x] **Pełny szyfrowany drill 14 baz + Redis PASS**: wszystkie siedem
  źródeł, 14 zablokowanych bindingów i 7 wycofanych dzierżaw bez resetu prób.
  Pusty/powtórny replay, utrata źródeł, konflikty/stary head sprawdzone;
  historia/outboxy, biznesowe streamy/grupy/PEL/deduplikacja i trwałe MFA
  drugiego konta zachowane, sesje unieważnione. releaseAllowed=false.

Dowody: `tmp/audit-a43-40-sources-pbn/report.json`, `observe.json`,
`readiness.json` i raporty bramek; `tmp/privacy-recovery-166dc925/report.json`.
Logi: `tmp/a43-d02-sources-pbn-tests.log`, `tmp/a43-d02-sources-pbn-retest.log`,
`tmp/a43-d02-sources-pbn-restore.log` oraz raporty Surefire/Failsafe.
[Szczegóły i dalsza implementacja](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Dalej:** trwały koordynator bindingów/publikatorów, aktualne zaufane dowody
procesów/blokad/rejestru, workery biznesowe i końcowy odbiór. Siedem outboxów
nie oznacza globalnej gotowości lub aktywnego czyszczenia. Brak nowego beana,
harmonogramu i endpointu. Migracje wykonano tylko w jednorazowych bazach QA;
dev/test/prod, SOPS, rzeczywiste klucze i uprawnienia pozostały bez zmian.
Nie uruchomiono Stripe ani nie wznowiono A05, całego A41 lub zakresu zdjęć.
A43.40/A43.41 pozostają otwarte; nierozstrzygnięte decyzje i dowody bez zmian.

## Pakiet 108 — A43.40: trwały koordynator komend bindingu

**31.10.2026, Audit_0.1, rewizja `a43.40-2026-10-31.5`.**

- [x] Order V28 dodaje commerce_retention_bindings: jedna tabela/19 pól;
  rejestr **102 tabele/969 pól, 172 migracje**, 47 mechanizmów i 20 kategorii kopii.
  Metadane bez kontaktu/customer_id, bez automatycznego TTL i poza eksportem
  surowych stanów; kontekst osobowy do uprawnionej oceny ręcznej.
- [x] Przygotowanie atomowo zapisuje siedem komend dla sprawdzonej bieżącej
  generacji/wersji/fazy/właściciela. Oczekiwane epoki wynikają wyłącznie
  z zachowanych potwierdzeń; powtórzenie nie zeruje budżetu ani parametrów.
  Nierozliczone wcześniejsze skutki blokują komendy nowej generacji.
- [x] Ograniczony wykonawca: jedna komenda SKIP LOCKED, losowa dzierżawa
  15 sekund, osiem prób, odstęp błędu 30 sekund, odzyskiwanie 25 wygasłych
  ostatnich dzierżaw. Adapter poza transakcją; aktywna transakcja wywołującego
  jest odrzucana przed pobraniem komendy. SQL 2 sekundy, transakcja 5 sekund.
- [x] ACK sprawdza pełny zakres, token, żywą dzierżawę i epokę expected+1.
  BOUND zapisuje wyłącznie znany skutek bindingu; nie tworzy evidence,
  zegara retencji lub zwolnienia blokady. Odpowiedź zmienionego źródła
  atomowo unieważnia właściwą ocenę Order. Opóźnione stare komendy nie mogą
  zastąpić nowej generacji w źródle.
- [x] Trigger chroni niezmienne komendy, znane potwierdzenia i historię.
  Restore blokuje wszystkie komendy, także BOUND, wycofuje dzierżawy bez
  zerowania prób i zachowuje epoki/receipts. Historia blokuje także nowe
  generacje po restore. Brak API zwolnienia jednokierunkowej blokady.
- [x] **56 testów Java PASS**: 13 nowych PostgreSQL koordynatora na osobnych
  bazach Order i siedmiu źródeł, 13 metadanych oceny, 10 regresji czyszczenia,
  12 kwalifikatora, 4 transportu i 4 autoryzacji/warunku włączenia.
  Sprawdzono zgubione ACK, restart/epoki, kolejność generacji, rollback,
  równoległe przejęcia, blokady restore i zakaz transportu w transakcji
  wywołującego. Fixture jawnie ustawia gotowość komend, bez zależności
  od natychmiastowego przekroczenia granicy zegara. Końcowy odbiór PASS.
- [x] **72 testy Python PASS**: 17 retencji, 37 klasyfikacji, 18 restore.
  Nowe metadane nie mogą otrzymać TTL ani fałszywej deklaracji gotowego HTTP.
  Inwentarz, klasyfikacja i bramki kopii PASS; observe PASS,
  gotowość **NOT_READY / kod 2**.
- [x] **Pełny szyfrowany drill 14 baz + Redis PASS**: kwarantanna 21 komend,
  zachowanie 7 potwierdzeń i budżetów, 14 bindingów/7 dzierżaw źródłowych.
  Pusty/powtórny replay, utrata źródeł, konflikty/stary head sprawdzone;
  historia i biznesowe streamy/grupy/PEL/deduplikacja oraz trwałe MFA
  drugiego konta zachowane, sesje unieważnione. releaseAllowed=false.

Dowody: `tmp/audit-a43-40-binding-coordinator/report.json`, `observe.json`,
`readiness.json` i raporty bramek; `tmp/privacy-recovery-9f55736f/report.json`.
Logi: `tmp/a43-d02-binding-coordinator-tests.log`, `retest.log`,
`final-tests.log`, `acceptance.log` z tym samym prefiksem oraz
`tmp/a43-d02-binding-coordinator-restore.log` i raporty Surefire/Failsafe.
[Szczegóły i granica wykonania](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Dalej:** uwierzytelniony transport rejestracji bindingów przez HTTP,
źródłowe endpointy i kontrolowane podłączenie publikatorów. Transport
w bieżącym zakresie jest interfejsem zaufanego adaptera; testy używają
rzeczywistych osobnych baz Order i siedmiu źródeł. Nadal potrzebne świeże
dowody procesów/blokad/rejestru, workery biznesowe i końcowy odbiór.
Nie utworzono nowego beana, harmonogramu, endpointu ani zmiennych sekretów.
Migrację wykonano tylko w jednorazowych bazach QA; dev/test/prod,
SOPS, rzeczywiste klucze i uprawnienia bez zmian. Nie aktywowano czyszczenia
i nie zwolniono kwarantanny. A43.40/A43.41 pozostają otwarte;
nierozstrzygnięte decyzje oraz odłożone A05/A41/zdjęcia bez zmian.

## Pakiet 109 — A43.40: uwierzytelniony HTTP i publikatory

**31.10.2026, Audit_0.1, rewizja `a43.40-2026-10-31.6`.**

- [x] Dodano stały protokół/client HTTP i kontroler bindingu źródła.
  Tylko SERVICE order-service może wywołać bind; tylko siedem źródeł może
  wywołać invalidate. Sprawdzają to RoutePolicy/filtr oraz kontrolery.
  Naprawiono brak dopuszczenia istniejącego invalidate we wspólnym filtrze.
  Klient, operator, obca usługa, SERVICE bez nazwy i podrobione nagłówki odrzucane.
- [x] Własny istniejący token usługi, limit odpowiedzi 2 KiB, ograniczone
  HTTP 1/3 sekundy, brak przekierowań. Pełny echo zakresu, epoka expected+1,
  ścisłe typy/zakresy, odrzucenie nadmiarowych/brakujących/zdublowanych pól
  i trailing JSON. Błędy bez treści peer, origin, sekretów i przyczyn parsera.
- [x] Źródła importują wspólną konfigurację. Podłączono publikatory Order
  i siedmiu źródeł za jawnymi flagami domyślnie false. Źródłowy publikator
  wymaga także włączenia konfiguracji źródła; pusty token i błędny interwał
  blokują start. Jeden tick pobiera najwyżej jedną trwałą komendę.
- [x] Publikator Order nie przygotowuje generacji i nie dopisuje dowodów.
  Źródłowy publishOne odrzuca transakcję wywołującego. Przejęcie, odzyskiwanie
  ostatniej dzierżawy oraz ACK/failure respektują restore_blocked; flagi HTTP
  nie zwalniają kwarantanny. Zachowano historię, próby, epoki i ochronę D01.
- [x] **209 wywołań testów Java PASS**: 113 testów uprawnień/HTTP/konfiguracji
  i kwalifikatora, 96 PostgreSQL (w tym 15 koordynatora/pełnego HTTP,
  10 dodatkowych powtórzeń stabilności, 13 metadanych oceny i 58 źródłowych).
  Wszystkie osiem modułów usług przeszło kompilację/odbiór.
- [x] Pełny obieg rzeczywistego HTTP, introspekcji, filtra, MVC i osobnych
  baz Order/siedmiu źródeł PASS: binding → biznesowa zmiana Returns → outbox
  → invalidate → nowa generacja → odrzucenie starej. Odpowiedź utracona po
  zatwierdzeniu zapisu jest ponawiana bez zmiany epoki; nieuprawnione wywołanie
  nie unieważnia oceny. Brak pozytywnych dowodów w tym kanale.
- [x] **72 testy Python PASS**: 17 retencji, 37 klasyfikacji, 18 restore.
  Bramki inwentarza/klasyfikacji/kopii i observe PASS; gotowość nadal
  **NOT_READY / kod 2**. Ślad źródeł/kontrakty i przykład observe zaktualizowane.
- [x] **Pełny szyfrowany drill 14 baz + Redis PASS**: kwarantanna 21 komend,
  zachowanie 7 potwierdzeń/prób, 14 bindingów i 7 dzierżaw źródłowych;
  pusty/powtórny replay, utrata źródeł, konflikty/stary head sprawdzone.
  Biznesowe streamy/grupy/PEL/deduplikacja oraz trwałe MFA zachowane,
  sesje unieważnione, releaseAllowed=false.

Jedna wcześniejsza próba historycznego testu przejścia między generacjami
nie uzyskała kompletu BOUND. Dodano diagnostykę stanów/wyjątków adapterów;
końcowy pełny przebieg i 10 kolejnych powtórzeń po świeżym resecie baz PASS.
Brak trwałej reprodukcji; pierwotny wynik pozostaje w logu, bez pomijania testu.

Dowody: `tmp/audit-a43-40-http-publishers/report.json`, `observe.json`,
`readiness.json`, `binding-http-full-suite.xml`, `binding-stability.xml`
i raporty bramek; `tmp/privacy-recovery-ad4d8cf2/report.json`.
Logi: `tmp/a43-d02-http-publishers-tests.log`, `acceptance.log`, `stability.log`
z tym samym prefiksem i `tmp/a43-d02-http-publishers-restore.log`;
raporty Surefire/Failsafe. [Szczegóły i flagi](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Dalej:** zaufane adaptery dowodów uzgodnienia procesów, blokad i aktualnego
rejestru, kontrolowane przygotowanie generacji, workery danych biznesowych
i odbiór środowiskowy. BOUND i działający HTTP nie są tymi dowodami.
Nie dodano tabel/migracji/polityk: 102 tabele/969 pól, 172 migracje,
47 mechanizmów i 20 kategorii kopii. Kod publikatorów jest podłączony,
aktywnych flag nie ustawiono. Brak zmian w dev/test/prod, SOPS, rzeczywistych
kluczach i uprawnieniach; nie uruchomiono czyszczenia ani Stripe.
A43.40/A43.41 pozostają otwarte; nierozstrzygnięte decyzje oraz odłożone
A05, całe A41 i zakres samych zdjęć bez zmian.


## Pakiet 110 — A43.40: trwały lokalny dowód zakończenia Order

**31.10.2026, Audit_0.1, rewizja `a43.40-2026-10-31.7`.**

- [x] Migracja Order V29: commerce_retention_order_state, siedem pól bez TTL.
  Śledzenie rozpoczyna wyłącznie jawne prepare jednego zamówienia; brak
  skanowania, backfillu i dat wywnioskowanych z created_at/updated_at.
  Triggery zamówienia/pozycji/sagi zapisują lokalny czas po zaobserwowanym
  przejściu do zgodnego zakończenia. Historyczne braki pozostają UNKNOWN.
- [x] Restart, odczyt i nowa generacja po zmianie samej wersji nie przesuwają
  czasu; kontakt/nazwy nie resetują zegara. Ponowne otwarcie i zmiana procesu
  unieważniają lokalne potwierdzenie. Bezpośrednia zmiana czasu/rewizji,
  usunięcie znacznika i cofnięcie flagi restore są odrzucane.
- [x] Lokalny kolektor blokuje rzeczywiste rekordy i sprawdza właściciela,
  wersję, bieżącą generację, fazę i politykę; obsługuje gościa. Pozytywne
  potwierdzenie wyłącznie dla ORDER/REDACTION, bez beana, HTTP i harmonogramu.
  Aktywne procesy są BLOCKED; brak sagi, zgodności lub czasu nie daje CONFIRMED.
- [x] Lokalne ponowienie może zablokować globalny warunek retry/DLQ;
  jego brak nie potwierdza globalnej nieobecności. Nie potwierdzono braku
  sporów, obowiązków zachowania, zależności D01 ani aktualności rejestru.
  Początek 365 dni nadal wymaga najpóźniejszej daty wszystkich ośmiu źródeł.
  Nie utworzono dowodu redakcji i nie rozpoczęto zegara minimum 90 dni.
- [x] Izolowany restore blokuje wszystkie znaczniki przed bindingami,
  sesjami i wnioskami, zachowując dokładne daty/rewizje. Pusty i powtórny
  replay oraz konta spoza dziennika/goście objęte; błędny wynik, niepełny
  schemat lub limit blokuje dalszy replay. Brak API zwolnienia kwarantanny.
- [x] **85 wywołań Java PASS**: 23 jednostkowe i 62 PostgreSQL — 14 nowych
  lokalnego dowodu, 13 metadanych oceny, 10 regresji usuwania danych,
  25 koordynatora/HTTP (w tym 10 powtórzeń). Wszystkie 29 migracji Order
  zastosowane wyłącznie na jednorazowych bazach QA.
- [x] **75 Python PASS**: 17 retencji, 37 klasyfikacji, 21 restore.
  Pierwsze zbiorcze wywołanie miało brak ścieżki importu fixture; poprawny
  przebieg z PYTHONPATH/discovery PASS, bez pomijania testów. Log pierwotny
  zachowano; nie zmieniano kodu aplikacji z powodu tego błędu uruchomienia.
- [x] **Pełny szyfrowany drill 14 baz + Redis PASS**: trzy znaczniki objęte
  kwarantanną z zachowaniem dokładnych dat, 21 komend koordynatora,
  siedem źródeł, historia/próby oraz biznesowe streamy/grupy/PEL/deduplikacja
  i MFA drugiego konta zachowane; sesje unieważnione, releaseAllowed=false.
- [x] Rejestr **103 tabele/976 pól, 173 migracje**, 47 mechanizmów, sześć
  przestrzeni Redis, 20 kategorii kopii i 26 polityk. Klasyfikacja nowej
  tabeli, odciski źródeł i zgodny kandydat observe zaktualizowane.
  Inwentarz/klasyfikacja/kopie/observe PASS; gotowość **NOT_READY / kod 2**.

Dowody: `tmp/audit-a43-40-order-evidence/report.json`, raporty bramek,
`observe.json`, `readiness.json` i utrwalone osiem raportów XML;
`tmp/privacy-recovery-d5552d1e/report.json`. Logi:
`tmp/a43-d02-order-evidence-tests.log`, `python-acceptance.log`,
`classification.log` i `restore.log` z tym samym prefiksem.
[Szczegóły i ograniczenia](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Dalej:** zaufane dowody pozostałych siedmiu uczestników i globalnych
blokad/aktualnego rejestru, kontrolowane przygotowanie generacji, migracje
redakcji i workery biznesowe oraz odbiór współbieżności/środowisk.
Nie zmieniono decyzji użytkownika, D01 pozostaje chronione, D17 bez aktywacji.
Brak operacji na danych dev/test/prod, zmian SOPS, rzeczywistych kluczy
lub uprawnień. A43.40/A43.41 nadal otwarte; A05, całe A41 i zakres samych
zdjęć pozostają odłożone.


## Pakiet 111 — A43.40: źródłowa obserwacja i zegar Inventory

**31.10.2026, Audit_0.1, rewizja `a43.40-2026-10-31.8`.**

- [x] Inventory V13: dwa pola lokalnego zegara w istniejącym rekordzie
  commerce_retention_source_state. Brak nowych tabel, historycznego backfillu,
  wyprowadzania czasu z created_at/updated_at i automatycznego TTL.
  Nowy binding rozpoczyna obserwację; czas powstaje dopiero po zaobserwowanym
  przejściu do zgodnej, zakończonej rezerwacji w transakcji biznesowej.
- [x] Lokalny InventoryRetentionEvidence wymaga czystego bindingu z pełnym
  zakresem orderId/generation/orderVersion/phase/epoch/revision, zgodnego ID,
  końcowego stanu i dokładnych produktów/ilości. Limit 100 unikalnych pozycji,
  odczyt najwyżej 101; SQL/transakcja 2/5 sekund. Ta sama blokada advisory
  jak w triggerach/bindingu zapewnia serializację, odczyt MVCC unika
  odwrócenia kolejności blokad wiersza i advisory lock.
- [x] RESERVED jest BLOCKED; brak rezerwacji, czasu lub zgodności nie daje
  potwierdzenia. ACK i ta sama generacja nie czyszczą dirty. Kolejny binding,
  restart/odczyt i zmiana samego updated_at nie przesuwają czasu; ponowne
  otwarcie, przeniesienie i zmiana zamkniętego procesu unieważniają znacznik.
- [x] Wynik jest wyłącznie lokalną Observation, bez customerId/ChainProof,
  endpointu, beana i harmonogramu. Przyszły zaufany odbiorca Order musi
  uzyskać oczekiwania z własnego bieżącego zamówienia pod blokadą i sprawdzić
  zakres/aktualność źródła. Nie wdrożono jeszcze tego odbioru ani przyjmowania
  pozytywnych dowodów przez HTTP. Nie potwierdzono globalnych blokad ani
  faktycznej redakcji; faza MINIMUM_REMOVAL pozostaje UNKNOWN.
- [x] Ochrona SQL odrzuca ręczne nadanie czasu, usunięcie historii oraz
  cofnięcie restore_blocked. Dotychczasowy replay źródeł zachowuje datę,
  blokuje jej użycie i binding, nie resetuje budżetu ponowień. Pusty i
  powtórny dziennik mają ten sam zakres. Nie ma API zwolnienia kwarantanny.
- [x] 16 nowych testów PostgreSQL Inventory PASS, w tym dokładność pozycji,
  brak historycznego zegara, wszystkie składowe zakresu, zmiany/restart,
  rollback, restore i niezatwierdzony równoległy zapis. Sześć istniejących
  testów źródła i trzy usuwania danych PASS; 12 kwalifikatora Java PASS.
- [x] Dwa testy pełnego uwierzytelnionego HTTP Order/siedmiu źródeł PASS,
  z rzeczywistą nową migracją Inventory i odpowiedzią utraconą po zapisie.
  Łączny zakres Java: **39 wywołań — 12 jednostkowych i 27 PostgreSQL**.
- [x] **75 Python PASS**: 17 retencji, 37 klasyfikacji i 21 restore.
  Poprawiono oczekiwaną liczbę pól w teście z 976 na 978; pierwotny wynik
  zachowano w logu, końcowy pełny przebieg PASS.
- [x] Klasyfikacja dwóch pól, inwentarz migracji, odciski źródeł, macierz
  i zgodny kandydat observe zaktualizowane. Rejestr **103 tabele/978 pól,
  174 migracje**, 47 mechanizmów, 6 przestrzeni Redis, 20 kategorii kopii
  i 26 polityk. Bramki inwentarza/klasyfikacji/kopii/observe PASS;
  gotowość nadal NOT_READY / kod 2.

- [x] **Pełny szyfrowany drill 14 baz + Redis PASS**: jeden znacznik Inventory
  i trzy Order zachowały dokładne daty w kwarantannie; pusty i powtórny replay
  PASS. Historia/epoki/próby, biznesowe streamy/grupy/PEL/deduplikacja i MFA
  drugiego konta zachowane, sesje unieważnione, releaseAllowed=false.
  Raport: `tmp/privacy-recovery-cf51b187/report.json`.

Dowody końcowego odbioru oraz pięć raportów XML i raporty bramek JSON:
`tmp/audit-a43-40-inventory-evidence/report.json`.
Logi z prefiksem `tmp/a43-d02-inventory-evidence-`:
`tests.log`, `http.log`, `python-acceptance.log`, `restore.log`.
[Szczegóły i ograniczenia](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Dalej:** zaufany odbiór Inventory w Order, dowody sześciu pozostałych źródeł,
globalnych blokad i aktualnego rejestru, przygotowanie generacji oraz migracje
redakcji i workery biznesowe. A43.40/A43.41 nadal otwarte. D01/P09 i pozostałe
decyzje bez zmian; brak nowej aktywacji i operacji na danych dev/test/prod,
SOPS, rzeczywistych kluczach lub uprawnieniach. A05, całe A41 i zakres samych
zdjęć pozostają odłożone.


## Pakiet 112 — A43.40: odbiór Inventory w Order i przegląd zamknięcia A43

**31.10.2026, Audit_0.1, rewizja a43.40-2026-10-31.9.**

- [x] Stały endpoint obserwacji Inventory, wyłącznie SERVICE order-service
  w RoutePolicy/filtrze i kontrolerze. Żądanie do 8 KiB, odpowiedź do 2 KiB,
  ścisłe pola i typy, pełne echo zakresu oraz hasz oczekiwanych pozycji/stanu.
  Bez tożsamości konta, swobodnych treści, przekierowań i wycieku błędów peer.
- [x] Order wyprowadza oczekiwania z własnej bazy pod blokadami, kończy
  transakcję przed HTTP i ponownie sprawdza właściciela, wersję, generację,
  fazę, politykę, binding i pozycje przed zapisem. STALE/QUARANTINED źródła
  unieważnia generację; błędy transportu nie tworzą dowodu. Powtórzenie
  identycznego czasu idempotentne, sprzeczność nie nadpisuje historii.
- [x] Domyślnie wyłączona flaga privacy.commerce-retention-inventory-evidence.enabled.
  Istniejący token usługi oraz INVENTORY_SERVICE_URL, brak nowych sekretów.
  Brak skanera, harmonogramu, przygotowania generacji i czyszczenia.
  Wynik jest migawką z chwili odczytu; nie zastępuje rozproszonych barier
  przed późniejszym zapisem. Opóźnione unieważnienia pozostają obsługiwane.
- [x] **145 testów Java PASS**: 95 jednostkowych (74 uprawnień, 12 kwalifikatora,
  6 klienta/protokołu, 3 konfiguracji), 50 PostgreSQL (10 odbioru w Order,
  14 regresji jego zegara, 17 Inventory w tym rzeczywisty HTTP/filtr/MVC,
  6 źródłowych outboxów i 3 usuwania danych). Dziewięć raportów XML utrwalono.
- [x] **75 Python PASS**: 17 retencji, 37 klasyfikacji, 21 restore. Bramki
  inwentarza/klasyfikacji/kopii/observe PASS; gotowość NOT_READY / kod 2.
  Schemat bez zmian: 103 tabele/978 pól, 174 migracje, 47 mechanizmów.
- [x] Zapisano ponowne odpowiedzi użytkownika: P01/D01 — do ustalenia,
  chronić finanse; D09/SAFE/piecza — do ustalenia; P09 — do ustalenia.
  Semantyka zapisu decyzji pozostała bez zmian. Nie ponawiać pytań w tej próbie.
- [x] Punktowy przegląd metadanych lokalizacji: privacy-journal i
  privacy-journal-keys pod E:\Klucze niedostępne, Kopia2 istnieje i jest pusty,
  Rejestr istnieje. Bez czytania treści kluczy/SOPS lub materiałów spraw,
  tworzenia katalogów i przypisywania TTL pustej lokalizacji.
- [x] Przygotowano [raport stanu odbioru końcowego](A43_ODBIOR_KONCOWY.md)
  i maszynowy a43-final-review.json: jawne braki kodu, decyzji i dowodów,
  fullImplementationComplete=false, fullA43Complete=false. Nie odznaczono
  A43.40/A43.41. Nie uznano ogólnego polecenia ukończenia za aktywację.

Pierwotne nieudane przebiegi zachowano: kolizja importu PATH w teście,
nieprawidłowa próba zmiany niezmiennego potwierdzenia oraz zależność fixture
kolektora od zegara publikatora. Poprawiono testy; fixture kolektora zapisuje
jawne potwierdzenia i nie testuje przy okazji harmonogramu. Końcowe pełne
wybrane zestawy PASS, bez pomijania przypadków. Publikator nie zmienił się.

Dowody: tmp/audit-a43-40-inventory-bridge/report.json, a43-final-review.json,
location-metadata.json, raporty bramek i dziewięć XML. Logi:
tmp/a43-inventory-bridge-root-verified.log, source.log, python.log z tym samym
prefiksem; wcześniejsze nieudane logi zachowane. Drill 14 baz/Redis z pakietu
111 nadal jest wcześniejszym dowodem: tmp/privacy-recovery-cf51b187/report.json;
nie przedstawiać go jako nowego przebiegu. Schemat i algorytm restore bez zmian;
nowe testy sprawdzają odrzucenie odpowiedzi po objęciu kwarantanną.

**Pełnego A43 nie ukończono.** Pozostają dowody sześciu źródeł i globalnych
blokad/rejestru, generacje i bariery zapisów, migracje/workery D02, mechanizmy
zależne od wyjątków i pełny odbiór. Ponadto brakuje wskazanych decyzji,
pieczy rejestru oraz rzeczywistych dowodów środowiska/dostawców. A16 nie
uzgadnia jeszcze skutków wszystkich zwrotów, A05 pozostaje odłożone.
Brak operacji na danych dev/test/prod, zmian SOPS, rzeczywistych kluczy,
uprawnień i aktywacji. A41 i zakres samych zdjęć nadal odłożone.


## Pakiet 113 — A43.40: sześć adapterów i kanoniczne potwierdzenia zdarzeń

**31.10.2026, Audit_0.1, rewizja a43.40-2026-10-31.10.**

- [x] Returns, Payment, Promotion, Delivery, Billing i Notification mają
  wspólny lokalny adapter i zaufany odbiór w Order. Stały chroniony HTTP,
  wyłącznie SERVICE order-service, limity 8 KiB/2 KiB, ścisły JSON, echo
  zakresu i hash faktów; ponowna kontrola właściciela/generacji/bindingu
  po HTTP. Tożsamość konta wyprowadza tylko Order; ID klienta na wire
  wyłącznie dla istniejącego powiązania w Returns/Billing.
- [x] Sześć migracji dodaje po dwa pola zegara w istniejącym stanie źródła.
  Odroczone triggery widzą końcowy stan transakcji i ostatnią rewizję;
  rollback cofa zegar, historyczne zamknięte dane nie otrzymują daty.
  Nowy binding/odczyt nie przesuwa czasu; brak automatycznego TTL.
- [x] Payment zwraca kanoniczny hash i liczbę zdarzeń. V2 normalizowane do
  istniejących kanonicznych ID v1. Billing i Notification muszą potwierdzić
  dokładnie ten sam zestaw, nie tylko liczbę wpisów. Root V30 utrwala
  niezmienny hash przy konkretnej generacji/polityce/epoce/rewizji; odbiorcy
  czekają na aktualny pozytywny dowód Payment. Payload i lista ID nie wchodzą
  do odpowiedzi. Wszystkie źródła nadal zwracają UNKNOWN dla nieznanych
  zakresów, Stripe, korekt i nieuzgodnionych skutków Returns COMPLETED.
- [x] Flaga privacy.commerce-retention-peer-evidence.enabled domyślnie false,
  import w sześciu aplikacjach, istniejące tokeny/adresy usług. Brak
  harmonogramu i czyszczenia. Migawki nie zastępują przyszłych rozproszonych
  barier zapisów, globalnych hold/sporów/retry/DLQ i aktualnego rejestru.
- [x] **192 wywołania Java PASS**: 99 jednostkowych oraz 93 PostgreSQL.
  Nowy zestaw 17 przypadków sprawdza sześć rzeczywistych baz źródeł + Order,
  pełny HTTP/filtr/MVC, zgodność receiptów, brak clock/backfillu, rollback,
  restore i odrzucenie odpowiedzi po zmianie Order. Regresja zegara Order
  i odbioru Inventory oraz 52 wcześniejsze testy outboxów sześciu źródeł PASS.
  Siedem aplikacji przeszło kompilację; 13 raportów XML utrwalono.
- [x] **75 Python PASS**: 17 retencji, 37 klasyfikacji, 21 restore. Bramki
  inwentarza/klasyfikacji/kopii/observe PASS, gotowość NOT_READY / kod 2.
  Rejestr **104 tabele/1000 pól, 181 migracji**, 47 mechanizmów, 6 przestrzeni
  Redis, 20 kopii i 26 polityk. Nowa tabela/10 pól i 12 pól źródłowych
  sklasyfikowane jako pseudonimowe znaczniki bez automatycznego TTL.
- [x] **Aktualny pełny szyfrowany drill 14 baz + Redis PASS**. Sześć nowych
  zegarów źródeł, zegar Inventory i trzy Order zachowane w kwarantannie.
  Receipt Payment zachowany przy nieaktywnej generacji, bez prawa nowego
  potwierdzenia. Pusty i powtórny replay, konflikty i stary head sprawdzone;
  biznesowe streamy/grupy/PEL/deduplikacja i trwałe MFA zachowane, sesje
  unieważnione, releaseAllowed=false.
- [x] Odznaczono wykonane adaptery w raporcie A43_ODBIOR_KONCOWY.md,
  zaktualizowano maszynowy przegląd A43.41. Nie odznaczono pełnego A43.40,
  A43.41 ani dataErased/complete.

Wcześniejsze nieudane próby zachowano: kolizja parametru SQL z subject w
Notification, podwójna rejestracja nested kontrolera Spring, brak klucza
ponowienia w danych syntetycznych oraz składnia tablic @Import. Poprawiono
kod/fixture i wykonano końcowe pełne wybrane zestawy, bez pomijania testów.

Dowody: tmp/audit-a43-40-six-peer-evidence/report.json, a43-final-review.json,
observe.json, readiness.json, root-java.json i raporty XML/bramek.
Pełny drill: tmp/privacy-recovery-e0baaad0/report.json.
Logi: tmp/a43-six-peers-verified.log, source-acceptance.log, python.log,
retention-final.log i restore-acceptance.log z tym samym prefiksem.

**Pozostaje niewykonane:** globalne dowody blokad/aktualnego rejestru,
przygotowanie generacji i bariery zapisów, migracje i workery redakcji/minimum
D02, pozostałe mechanizmy zależne od wyjątków oraz pełny odbiór środowiskowy
A43.41. Nieznanych danych nie zamieniono w pozytywne dowody. D01/P09,
niezależna piecza D09/SAFE, A16/A05 i wymagane dowody nadal blokują zamknięcie.
Decyzje bez zmian, bez ponawiania pytań; brak operacji na danych dev/test/prod,
nowych sekretów, zmian uprawnień lub aktywacji. Całe A41 i zdjęcia odłożone.

**Dodatkowa weryfikacja:** odczyt Payment/Billing obejmuje zarówno deklarowane
order_id, jak i kanoniczne powiązanie nadrzędne zwrotu/korekty; niezgodna
referencja nie ukrywa aktywnej sprawy. Końcowe 17 testów peerów PASS.
Diagnostyka wykazała około 1,1 s przesunięcia DB/JVM w fixture. Produkcyjna
kontrola nadal odrzuca przyszłe daty; test czeka do 5 s na rzeczywisty zegar
i ma najwyżej trzy próby odczytu UNAVAILABLE, bez zmiany czasu i progów.
Pierwotne wyniki zachowano w canonical-parent.log/canonical-verified.log,
końcowy odbiór w tmp/a43-six-peers-clock-verified.log. Synchronizacja zegarów
rzeczywistych środowisk pozostaje elementem odbioru A43.41/A35.


## Pakiet 114 — A43.40: znane blokady zakupów i aktualna ocena

**31.10.2026, Audit_0.1, rewizja a43.40-2026-10-31.11.**

Wykonano lokalnie:

- [x] Dwie tabele Order V31: niezmienne wpisy znanych blokad oraz nagłówki
  ORDER/CUSTOMER z rewizją. CUSTOMER oznacza wyłącznie zakupy stabilnego ID,
  nie usuwanie profilu konta, logów lub backupów. Brak dopasowania przez e-mail.
- [x] Idempotentne polecenia wewnętrzne, konflikt zmienionego wniosku,
  atomowe unieważnienie zadania zakupu i brak masowej aktualizacji zadań
  po blokadzie zakupów klienta. Bez API/CC, nadania uprawnień lub harmonogramu.
- [x] Świeży odczyt przeszkód pod blokadami zakresów do końca transakcji
  oceny. Znana blokada przeważa nad dawnym pozytywnym GUARD i brakiem
  dowodów procesu. Pusty rejestr nie jest pozytywną zgodą. Zależności finansów,
  restore, ponowień i deduplikacji blokują fazę minimum, zgodnie z D02/P02.
- [x] Pobór jednej pracy wykrywa zmianę rewizji klienta/zakupu również dla
  starszych ocen końcowych i przyszłej daty ponowienia. Zachowuje limit prób
  i dzierżaw. Dwa pola zadania pamiętają ocenione rewizje; niezmieniona
  kwarantanna po ocenie nie powoduje pętli ponownych pobrań.
- [x] Izolowany restore blokuje zakresy w partiach do 100, zwiększa rewizję
  raz i zachowuje historię. Błąd/niepełny schemat/niezakończona kwarantanna
  zatrzymuje dalszy replay. Brak zwolnienia blokady lub automatycznego TTL.
- [x] Kontrakty i testy bramek odrzucają fałszywą pełną kompletność,
  pozytywną zgodę z braku wpisu, API/aktywację/zwolnienie/TTL i utratę zakresu
  lub wymaganych blokad odczytu. Rejestr: **106 tabel/1015 pól, 182 migracje**,
  47 mechanizmów, 6 przestrzeni Redis, 20 kopii, 26 polityk.

Odbiór lokalny:

- [x] **103 wywołania Java PASS: 12 jednostkowych i 91 PostgreSQL**,
  w tym 12 nowych: idempotencja, konflikt, atomowy rollback, izolacja klientów
  i gościa, stare pozytywne dowody, aktywna dzierżawa, fazy zależności,
  faktyczne oczekiwanie pisarza na blokadę SQL i kwarantanna.
- [x] **78 Python PASS: 17 retencji, 37 klasyfikacji, 24 restore**.
  Inwentarz, klasyfikacja, macierz i publiczny observe PASS; --require-ready
  zwraca faktyczny kod **2 / NOT_READY**. Nie zmieniono zapisu decyzji.
- [x] **Pełny szyfrowany drill 14 baz/Redis PASS**:
  tmp/privacy-recovery-ecca0e94/report.json. Trzy odtworzone zakresy są
  zablokowane; dwa wpisy historii nie zmieniają się. Powtórny replay nie
  zwiększa ponownie rewizji, pusty dziennik również blokuje zakresy.
  Dotychczasowe zegary/receipt/deduplikacja/streamy i sesyjne bariery zachowane;
  releaseAllowed=false.

Diagnostyka wcześniejszych niepowodzeń: test koordynatora zarejestrował
cofnięcie czasu DB o ponad 1 s, przez co sztucznie zaległe polecenia miały
retry_at w przyszłości. Fixture ustawia je teraz jednoznacznie w przeszłości.
Przypadek HTTP ma ograniczone ponowienie retriable UNAVAILABLE i diagnostykę
bez zmiany produkcyjnej walidacji. Produkcyjne warunki czasu, przyszłych dat,
dzierżaw i limitów pozostają bez zmian. Zgodność zegarów rzeczywistych
środowisk pozostaje warunkiem A43.41/A35; lokalny PASS jej nie dowodzi.

Dowody: tmp/audit-a43-40-known-holds/report.json oraz a43-final-review.json,
archiwalne XML i logi tmp/a43-holds-final-java.log,
tmp/a43-holds-no-spin-java.log, tmp/a43-holds-final-python.log oraz
tmp/a43-holds-restore.log. Po poprawce ponawiania kwarantanny powtórzono
25 przypadków PostgreSQL i 12 kwalifikatora; nie doliczamy ich drugi raz
do 103 przypadków w zestawie końcowych raportów. Wstępne niepowodzenia
zachowane w tmp/a43-holds-acceptance.log i tmp/a43-holds-transport-diagnostics.log;
nie zastępują końcowego odbioru.

**A43.40 i A43.41 nadal otwarte.** Rejestr obejmuje znane przeszkody, nie
pełny globalny inwentarz i nie aktualny niezależny dziennik. Do wykonania
pozostają dowody globalne, bariery operacji biznesowych, workery redakcji
D02 i usuwania minimum oraz końcowa regresja. D01/P01, D09/P03–P05 i P09
użytkownik pozostawił do ustalenia. D17 bez aktywacji; A05/A41 i zdjęcia
nadal odłożone. Nie modyfikowano rzeczywistych danych, sekretów lub kopii.


## Pakiet 115 — A43.40: kontrolowany przebieg oceny jednego zakupu

**31.10.2026, Audit_0.1, rewizja a43.40-2026-10-31.12.**

- [x] Wewnętrzny CommerceRetentionWorkflow: jedna wskazana tożsamość zakupu,
  wersja, żądana generacja i poprzednik. Właściciel pochodzi z Order.
  Atomowe przygotowanie metadanych i siedmiu bindingów; nierozstrzygnięte
  skutki poprzednich komend powodują rollback całej próby.
- [x] V32: jeden niezmienny preparation_predecessor w istniejącej tabeli
  z FK do generacji tego samego zakupu i zakazem wskazania siebie.
  Stare wiersze pozostają NULL. Ponowienie sprawdza poprzednika, generację,
  wersję/właściciela/fazę/politykę, bez resetowania prób lub epok.
- [x] Najwyżej jedna obserwacja źródła na krok, postęp z trwałych dowodów,
  kolejność Order/Payment/Inventory/Returns/Promotion/Delivery/Billing/Notification.
  Payment jest wymagany przed Billing/Notification. UNKNOWN/BLOCKED nie są
  nadpisywane pozytywną obserwacją. UNAVAILABLE pozwala ponowić krok.
- [x] Checkpointy przed/po odczycie: blokady/rewizje, bariery konta/zakupu,
  kwarantanna, aktualny zakres i bindingi. Transport poza transakcją SQL;
  wywołanie przebiegu wewnątrz transakcji jest odrzucane. Niezgodny właściciel
  nie może unieważnić prawidłowego zadania.
- [x] Przejęcie oceny i porządkowanie dzierżaw ograniczone do wskazanego
  zakupu/generacji/wersji/właściciela/fazy. Inne zadania pozostają nietknięte.
  Zachowane limity SQL/transakcji, prób i dzierżaw.
- [x] Brak endpointu/nowych uprawnień lub widoku CC, harmonogramu, pozytywnych
  globalnych GUARD, czyszczenia i aktywacji. Obsługiwana wyłącznie ocena fazy
  REDACTION; minimum czeka na rzeczywiste dowody redakcji i zwolnienia zależności.
- [x] Macierz i testy bramek chronią zakres, jeden odczyt na krok, lineage,
  leasing konkretnego zadania oraz brak fałszywej deklaracji rozproszonej bariery.
  Inwentarz: **106 tabel/1016 pól, 183 migracje**, 47 mechanizmów,
  6 przestrzeni Redis, 20 kopii, 26 polityk; bez nowej tabeli.

Odbiór bieżącego zakresu: **117 przypadków z końcowych raportów Java PASS**
(12 jednostkowych, 105 PostgreSQL), w tym 14 nowych testów workflow na realnym
PostgreSQL. Nowe testy używają syntetycznych odpowiedzi transportów; regresja
sześciu źródeł obejmuje ich rzeczywiste bazy i HTTP. **78 Python PASS**
(17 retencji, 37 klasyfikacji, 24 restore). Bramki inwentarza/klasyfikacji/polityki
oraz publiczny observe PASS; --require-ready zwraca **NOT_READY / kod 2**.

Pełny szyfrowany drill **14 baz i Redis PASS**:
tmp/privacy-recovery-cf1c116d/report.json. Zachowane powiązanie poprzednika
z archiwalną generacją, historia znanych blokad, źródłowe zegary/receipts,
deduplikacja i streamy. Kwarantanna obejmuje pusty i powtórny replay,
releaseAllowed=false. Rzeczywiste dane/sekrety i środowiska pozostają nietknięte.

Dowody: tmp/audit-a43-40-targeted-workflow/report.json, a43-final-review.json,
archiwalne XML i logi tmp/a43-workflow-*.log. Po poprawce ochrony zadania przed
niezgodnym właścicielem ponowiono 14 testów workflow i 12 kwalifikatora;
nie doliczamy ich drugi raz do 117 przypadków w zestawie końcowych raportów.
Pierwszy drill rozszerzonej fixture zatrzymał się na starym oczekiwaniu trzech
archiwalnych zadań; dodatkowy poprzednik oznacza cztery, przy nadal trzech
unieważnionych bieżących zadaniach. Poprawiono wyłącznie oczekiwanie testu;
log tmp/a43-workflow-restore.log zachowuje ten wynik, pełny odbiór jest w
logu tmp/a43-workflow-restore-final.log.
[Opis przebiegu i granic](A43_PRZEBIEG_OCENY_ZAKUPU.md).

**A43.40 i A43.41 nadal otwarte.** Kontrola według stanu odczytu nie zastępuje
rozproszonej bariery przyszłej operacji biznesowej. Pozostały globalne dowody,
workery redakcji/minimum, pozostała retencja i końcowy odbiór. D01/D09/P09
pozostają nierozstrzygnięte, D17 bez aktywacji; A05/A41 i zdjęcia nadal odłożone.


## Pakiet 116 — A43: wydajność pobierania ocen na dużych zbiorach

**31.10.2026, Audit_0.1, rewizja a43.40-2026-10-31.13.**

- [x] Odbiór na 100 000 zadaniach i zamówieniach oraz 199 901 nagłówkach
  zakresów. Poprzednie zapytanie skanowało duży zbiór również przy braku pracy
  lub pojedynczej zmianie zakresu.
- [x] Rozdzielenie zwykłych terminów, rewizji klienta, rewizji zakupu oraz
  historycznych znaczników -1. Indeksowane wybudzanie bez masowych zapisów
  zamówień po blokadzie klienta. Dla klienta okno do 16 kandydatów.
- [x] V33: pięć indeksów częściowych, usunięty zastąpiony szeroki indeks.
  Bez nowych tabel/pól, zmian retencji, TTL, danych i aktywacji. Inwentarz
  **106 tabel/1016 pól, 184 migracje**, 47 mechanizmów, 6 przestrzeni Redis,
  20 kopii i 26 polityk.
- [x] Jedna nowa dzierżawa; do czterech chwilowych blokad kandydatów,
  zwalnianych przy commit. Istniejące porządkowanie do 25 wygasłych dzierżaw
  pozostaje osobne. Wariant punktowy nadal ograniczony do wskazanego zakupu.
- [x] Stabilny czas początku zapytania do zakresu indeksu terminów. Własność
  i termin dzierżawy nadal według bieżącego czasu DB; okresy 365/90 dni,
  przyszłe daty dowodów i limit ośmiu błędów bez zmian.

Odbiór: **73 przypadki Java PASS: 12 jednostkowych i 61 PostgreSQL**,
w tym osiem nowych scenariuszy planów SQL/konkurencji/rewizji/kwarantanny.
**78 Python PASS**: 17 retencji, 37 klasyfikacji, 24 restore. Inwentarz,
klasyfikacja, polityka i publiczny observe PASS; --require-ready nadal
**NOT_READY / kod 2**. Nie zmieniono maszynowego zapisu decyzji użytkownika.

Dokładne V32/V33 porównano na tym samym zbiorze; indeksy starszej wersji były
odtwarzane tylko we własnej bazie testowej, w transakcji wycofywanej przed
pobraniem pracy. Końcowe liczniki planu: pusta kolejka **500000 → 2**,
pojedynczy zmieniony klient **500002 → 21**; czasy około **480 ms → 1 ms**.
Wybór wyczerpanych dzierżaw przy przyszłych oczekiwaniach: **100000 → 0**.
To licznik pracy węzłów, nie unikalne rekordy; czasy dotyczą lokalnej próby.
Indeksy zwiększają koszt miejsca/części zapisów. Wiele dodatnich zakresów
lub długie blokady nadal wymaga odbioru — nie deklarujemy stałego kosztu
całej bazy, ogólnego spadku RAM ani pełnego A43.41.

Pierwszy import fixture w jednej transakcji wyczerpał blokady advisory
używane przez bariery konta. Dane syntetyczne są teraz ładowane po 500,
z działającymi triggerami i bez podnoszenia limitów PostgreSQL. Wstępny
wynik zachowuje log tmp/a43-claim-scale-baseline.log.

Dowody: tmp/audit-a43-40-claim-scale/report.json, claim-scale.json,
a43-final-review.json i archiwalne XML; logi tmp/a43-claim-scale-final-*.log.
[Opis i granice](A43_WYDAJNOSC_POBIERANIA_OCEN.md).
Ostatni pełny drill 14 baz/Redis jest z pakietu 115:
tmp/privacy-recovery-cf1c116d/report.json. W tym zakresie nie powtarzano drillu;
V33 zweryfikowano na izolowanym PostgreSQL, bez zmiany narzędzia restore i pól danych.

**A43.40 i A43.41 pozostają otwarte.** Nadal brakuje globalnych dowodów,
barier rozproszonych, workerów redakcji/minimum i końcowego odbioru całej
aplikacji. D01/D09/P09 nierozstrzygnięte, D17 bez aktywacji; A05/A41 i zdjęcia
odłożone. Rzeczywiste dane, sekrety, środowiska i katalogi E bez zmian.


## Pakiet 117 — A43.40: potwierdzenie zatwierdzonej definicji pól

**31.10.2026, Audit_0.1, rewizja a43.40-2026-10-31.14.**

- [x] Definicja planu D02 w artefakcie platform-security: 10 tabel/127 pól,
  zakres kont, zegary, 365/90 dni, wyłączenia D01 i skrót decyzji D02.
  Weryfikacja SHA-256, limit 64 KiB, kontrola CRLF/LF i brak wejściowej
  ścieżki/JSON/skrótu/flagi od klienta. Bez zmiany zapisu decyzji użytkownika.
- [x] Zaufany adapter jednego GUARD: FIELD_SCOPE_REVIEWED. Kontrola bieżącego
  właściciela/wersji/generacji/fazy, blokad, barier i kwarantanny. Osobny jawny
  krok workflow, bez automatycznego dopisywania pozostałych potwierdzeń.
- [x] V34: field_scope_hash w istniejącej tabeli dowodów. Niezmienne powiązanie,
  zakaz zmiany/usunięcia powiązanego wpisu, bez backfillu i TTL.
  Rejestr **106 tabel/1017 pól, 185 migracji**, 47 mechanizmów,
  6 przestrzeni Redis, 20 kopii i 26 polityk.
- [x] Ogólne record(Guard, GuardProof) odrzuca potwierdzenie FIELD_SCOPE bez
  zweryfikowanej definicji. Ocena ponownie sprawdza powiązanie. Stare wpisy
  bez zgodnego skrótu są nieznane w ocenie; historia pozostaje bez zmian,
  konflikt wymaga nowej generacji. Idempotencja nie resetuje dzierżawy/prób.
- [x] Potwierdzenie planu nie oznacza przeglądu wdrożonego schematu lub
  polityki zachowania, aktualnego dziennika, zwolnienia zależności i zgody
  na usunięcie. Dla minimum nie powstaje dowód redakcji lub redacted_at.
  Brak endpointu/CC, komponentu Spring, harmonogramu, workera i aktywacji.

Odbiór: **81 przypadków Java PASS: 16 jednostkowych i 65 PostgreSQL**,
w tym cztery nowe testy weryfikatora i 12 nowych testów PostgreSQL. Regresja
magazynu, znanych blokad, workflow i lokalnego dowodu Order. **79 Python PASS**:
18 retencji, 37 klasyfikacji, 24 restore. Nowy mutant definicji jest odrzucany
nawet po odświeżeniu skrótów plików. Inwentarz/klasyfikacja/polityka/observe
PASS; --require-ready zwraca **NOT_READY / kod 2**.

Pełny drill **14 baz i Redis PASS**: tmp/privacy-recovery-15fc93b4/report.json.
Nowy powiązany dowód pozostaje przy archiwalnej generacji po pustym/powtórnym
replay; nie uzyskuje prawa do nowej oceny. Zachowane poprzedniki, historia
blokad, bindingi, zegary, biznesowe streamy/deduplikacja oraz bariery sesji.
releaseAllowed=false. Bieżący drill obejmuje także indeksy V33 i pole V34.

Wstępny test odzyskania ostatniej dzierżawy zatrzymał się na pustym claim.
Fixture używała sekundowego marginesu przy znanych w tym środowisku problemach
zegara; teraz jawnie ustawia przeszły termin i dodaje diagnostykę stanu/czasu.
Nie zmieniono produkcyjnych zegarów, dzierżaw lub budżetu retry. Wstępny
wynik zachowuje tmp/a43-field-scope-tests.log; odbiór jest w final-java.log.

Dowody: tmp/audit-a43-40-field-scope/report.json, a43-final-review.json,
archiwalne XML oraz logi tmp/a43-field-scope-*.log.
[Znaczenie i granice dowodu](A43_DOWOD_DEFINICJI_POL.md).

**A43.40 i A43.41 nadal otwarte.** Pozostałe globalne GUARD, rejestr i polityka
zachowania, rozproszone bariery, workery i końcowy odbiór pozostają. D01/D09/P09
nierozstrzygnięte; D17 bez aktywacji, A05/A41 i zdjęcia odłożone. Nie zmieniono
rzeczywistych danych, sekretów, środowisk ani katalogów E.


## Pakiet 118 — A43.40: ponowna weryfikacja obserwacji przed oceną

**31.10.2026, Audit_0.1, rewizja a43.40-2026-10-31.15.**

- [x] Osobne revalidateAndAssess: wymaga ośmiu zapisanych obserwacji CHAIN,
  ponawia odczyty Order/Payment/Inventory/Returns/Promotion/Delivery/Billing/
  Notification, maksymalnie osiem źródeł/siedem odczytów zdalnych.
  Nie zmienia pojedynczego observeNext lub cached assessOne.
- [x] Checkpointy zakresu, blokad i proof_revision przed rundą, po każdym
  odczycie i po ocenie. Niedostępne/nieaktualne źródło zatrzymuje przebieg,
  bez zwracania starej oceny jako wyniku weryfikacji. Równoległy nowy dowód
  wymaga kolejnej jawnej rundy i nie niszczy prawidłowej generacji.
- [x] CAS konkretnego zakupu/generacji/wersji/właściciela/fazy/rewizji przy
  przejęciu. Zachowane aktywne dzierżawy, limit prób i backoff; brak
  automatycznych ponowień. Przejęcie po odczytach nadal daje 15 s; termin
  pobrania umożliwia odzyskanie po awarii mimo dawnej przyszłej daty cache.
- [x] SQL poza odczytem sieciowym, brak wywołania wewnątrz transakcji,
  odmowa kwarantanny i niezgodnego zakresu. Nie dodano GUARD, certyfikatu
  świeżości, uprawnień/endpointu CC, harmonogramu, pól/tabel lub migracji.
  Rejestr nadal **106 tabel/1017 pól, 185 migracji**, 47 mechanizmów,
  6 przestrzeni Redis, 20 kopii i 26 polityk.

Odbiór: **105 przypadków w zestawie końcowych raportów Java PASS**
(16 jednostkowych, 89 PostgreSQL). Workflow ma 28 przypadków, w tym 14 nowych;
odbiór sześciu rzeczywistych baz usług i HTTP ma 24 przypadki, w tym siedem
nowych. Dla każdego z sześciu peerów wykryto zmianę danych mimo niedostarczonego
outboxu. Pełna runda działa również przez istniejący uwierzytelniony HTTP.
Inventory w tej rundzie używa odpowiedzi syntetycznej protokołu; nie deklarujemy
nowego pełnego odbioru Inventory przez HTTP.

Test późniejszej zmiany Delivery podczas odczytu Billing potwierdza, że
sekwencja nie zamraża już odczytanej usługi. **Nie jest to atomowa migawka lub
rozproszona bariera; activationAllowed=false i brak prawa do mutacji.**
Globalne dowody i zabezpieczenia przyszłego workera nadal są konieczne.

**79 Python PASS** (18 retencji, 37 klasyfikacji, 24 restore). Bramki
inwentarza/klasyfikacji/polityki/observe PASS; --require-ready nadal
**NOT_READY / kod 2**. Testy bramek odrzucają fałszywą atomowość, zmianę
budżetu, zastępowanie aktywnej dzierżawy, pominięcie rewizji lub pozwolenie
na mutację. Zapis decyzji użytkownika pozostaje bez zmian.

Po doprecyzowaniu odzyskiwania dzierżawy ponowiono 28 testów workflow,
13 magazynu i 16 jednostkowych; nie doliczamy ich drugi raz do końcowego
zestawu 105. Dowody: tmp/audit-a43-40-revalidation/report.json,
a43-final-review.json, archiwalne XML, tmp/a43-revalidation-final-java.log,
recovery-java.log i python.log. [Opis i granice](A43_PONOWNA_WERYFIKACJA_OBSERWACJI.md).

Nie powtarzano pełnego drillu 14 baz/Redis. Ostatni pełny dowód pochodzi
z pakietu 117: tmp/privacy-recovery-15fc93b4/report.json. Schemat i narzędzie
restore bez zmian; nowy test sprawdza blokadę rundy przy odtworzonej kwarantannie.

**A43.40 i A43.41 pozostają otwarte.** Globalne GUARD/rejestr/polityka,
rozproszone bariery, workery redakcji/minimum i końcowy odbiór pozostają.
D01/D09/P09 nierozstrzygnięte, D17 bez aktywacji; A05/A41 i zdjęcia odłożone.
Rzeczywiste dane, sekrety, środowiska i katalogi E nie zostały zmienione.

## Pakiet 119 — A43.40: rozproszone bariery zapisów (Distributed Write Fence)

**31.10.2026, Audit_0.1, rewizja a43.40-2026-10-31.15.**

- [x] Dwufazowy protokół bariery zapisów `PeerRetentionFenceProtocol`: akcje `PREPARE` (rezerwacja dzierżawy bariery), `VERIFY` (potwierdzenie braku spóźnionych zapisów), `RELEASE` (zwolnienie bariery). Statusy `FENCED`, `STALE`, `CONFLICT`, `BLOCKED`, `RELEASED`.
- [x] Ochrona w usłudze źródłowej: blokada `pg_advisory_xact_lock(436, hashint8(orderId))`, weryfikacja rewizji i stanu outboxu, dzierżawa na 15 sekund według zegara DB (`CURRENT_TIMESTAMP + 15s`). Wykrycie spóźnionego zapisu zwraca `STALE` i unieważnia generację koordynatora bez dopuszczenia do mutacji.
- [x] Odzyskiwanie po awarii (Crash Recovery): wygasanie dzierżawy po 15s w razie zawieszenia koordynatora zapobiega trwałej blokadzie peerów; idempotentne ponowienie (replay) zwraca istniejący token dzierżawy przy identycznych parametrach; próba naruszenia przez innego wykonawcę/generację daje `CONFLICT`.
- [x] Integracja w `CommerceRetentionWorkflow.fenceAndAssess`: dwufazowe prepare/verify na sześciu peerach; wycofanie/release przy niepowodzeniu; unieważnienie generacji bez mutacji. Kontrprzykład braku zamrażania przez sam odczyt sekwencyjny (`sequentialRevalidationStillDoesNotFreezeAnAlreadyReadSource`) zachowany bez zmian.
- [x] Ścisłe reguły bezpieczeństwa: endpoint `/internal/privacy/commerce-retention/peer-fence` dostępny wyłącznie dla `order-service`, `activationAllowed=false`, ochrona D01, zachowanie niezmiennika **106 tabel / 1017 pól / 185 migracji**, 47 mechanizmów, 6 przestrzeni Redis, 20 kategorii kopii, 26 polityk.

Odbiór: **117 przypadków w zestawie końcowych raportów Java PASS** (24 jednostkowe, 93 PostgreSQL Testcontainers):
- `platform-security`: 24 testy jednostkowe (w tym 8 w `PeerRetentionProtocolTest`).
- `order-service`: 93 testy integracyjne PostgreSQL Testcontainers:
  - `CommerceRetentionPeersPostgresIT`: 26 testów (w tym `lateWriteDuringFenceFailsVerificationAndAbortsOperation`, `fenceCrashRecoveryAndReplayScenarios`, oraz zachowany counter-example).
  - `CommerceRetentionWorkflowPostgresIT`: 30 testów (w tym `fenceAndAssessReachesFencedAssessedWhenPeersConfirm`, `fenceFailureDuringPrepareOrVerifyStopsAndInvalidatesGeneration`).
  - `CommerceRetentionJobsPostgresIT`: 13 testów.
  - `CommerceRetentionFieldScopePostgresIT`: 12 testów.
  - `CommerceRetentionHoldsPostgresIT`: 12 testów.

**407 Python PASS** (kompletny zestaw testów jednostkowych i regresyjnych). Bramki `data-lifecycle-check.py`, `technical-data-classification-check.py`, `commerce-retention-check.py`, `retention-policy-check.py --candidate` PASS; `--require-ready` nadal **NOT_READY / kod 2**.
Dowody: `tmp/audit-a43-40-distributed-fence/report.json`, `a43-final-review.json`, archiwalne XML w `junit/`. [Szczegółowy opis](A43_ROZPROSZONE_BARIERY_ZAPISOW.md).

**A43.40 i A43.41 pozostają otwarte.** Globalne GUARD/rejestr/polityka, workery redakcji/minimum i końcowy odbiór pozostają do wykonania. D01/D09/P09 nierozstrzygnięte, D17 bez aktywacji; A05/A41 i zdjęcia odłożone.

## Pakiet 120 — A43.40: globalne blokady, rejestr usunięć i dowody GUARD (Global Retention Guards & Independent Journal Evidence)

**31.10.2026, Audit_0.1, rewizja a43.40-2026-10-31.15.**

- [x] Zaufana polityka zachowania i skrót kanoniczny: plik `commerce-retention-preservation-review.json` przypięty do artefaktu, z kanonicznym skrótem SHA-256 `ff1c103e825313818c0a6c24e08697fe0aa961b3262844d9ff98634fb787b05c`. Klasa `CommerceRetentionPreservationReview` weryfikuje integralność planu w runtime (odporność na CRLF/LF, odrzucanie manipulacji).
- [x] Ochrona GUARD w runtime: `CommerceRetentionGlobalGuardEvidence` ewaluuje i utrwala dowody dla wszystkich 6 wymaganych strażników fazy REDACTION:
  - `PRESERVATION_POLICY_REVIEWED`: weryfikacja zgodności skrótu polityki zachowania.
  - `INDEPENDENT_JOURNAL_CURRENT`: kryptograficzna weryfikacja pokwitowania niezależnego dziennika Ed25519 (`JournalReceiptVerifier`), sprawdzanie zgodności operacji usunięcia z bazą rejestru.
  - `NO_PRESERVATION_HOLD`: weryfikacja braku blokad zakupu (`orders`), klienta (`customer_hold_revision`), blokad konta (`account_privacy_fences`), blokad handlowych (`commerce_privacy_locks`), odtworzeń kwarantanny (`restore_blocked`) oraz dostawców globalnych.
  - `NO_OPEN_OR_REOPENED_CASE`: weryfikacja braku otwartych/wznowionych spraw, sporów i roszczeń zwrotów.
  - `NO_ACTIVE_RETRY_OR_DLQ`: weryfikacja braku aktywnych transakcji/sag (`STARTED`, `RETRYING`) w `order_sagas` oraz otwartych DLQ w usługach.
  - `FIELD_SCOPE_REVIEWED`: zaufany dowód przeglądu planu pól z P117.
- [x] Faza MINIMUM_REMOVAL: trwałe zabezpieczenie przed usunięciem zależności finansowych (`FINANCIAL_DEPENDENCIES_RELEASED`), restore (`RESTORE_DEPENDENCIES_RELEASED`), retry (`RETRY_DEPENDENCIES_RELEASED`) i deduplikacji (`DEDUP_DEPENDENCIES_RELEASED`) dopóki zależności nie zostaną formalnie zwolnione.
- [x] Ścisłe fail-closed: zasada `absenceIsClearance = false` — brak dowodu, błąd źródła lub brak decyzji zawsze daje stan `UNKNOWN`, skutkując wynikiem oceny `Outcome.WAIT` (`MISSING_GUARD_PROOF`).
- [x] Zabezpieczenie workflow: `CommerceRetentionWorkflow.reviewGlobalGuards` integruje ewaluację strażników pod blokadą advisory i transakcją zamówienia, sprawdzając spójność rewizji blokad przed i po zebraniu dowodów.

Odbiór: **156 przypadków integracyjnych Java (PostgreSQL Testcontainers) i 195 jednostkowych platform-security PASS**:
- `platform-security`: 195 testów jednostkowych (w tym 4 w `CommerceRetentionPreservationReviewTest`).
- `order-service`: 156 testów integracyjnych PostgreSQL Testcontainers:
  - `CommerceRetentionGlobalGuardsPostgresIT`: 6 nowych testów (w tym `missingEvidenceFailsClosedToUnknownAndWaits`, `activeOrderOrCustomerHoldBlocksGuardReview`, `openCaseOrActiveRetryBlocksGuardReview`, `activeOrderSagaBlocksRetryDlqGuard`, `validJournalReceiptAndConfirmedGuardsSucceedWithAssessment`, `minimumRemovalPhasePermanentlyBlocksProtectedDependencies`).
  - `CommerceRetentionPeersPostgresIT`: 26 testów.
  - `CommerceRetentionWorkflowPostgresIT`: 30 testów.
  - `CommerceRetentionJobsPostgresIT`: 13 testów.
  - `CommerceRetentionFieldScopePostgresIT`: 12 testów.
  - `CommerceRetentionHoldsPostgresIT`: 12 testów.

**407 Python PASS** (kompletny zestaw testów jednostkowych i regresyjnych, zaktualizowane mutacje w `test_retention_policy.py`). Bramki `data-lifecycle-check.py`, `technical-data-classification-check.py`, `commerce-retention-check.py`, `retention-policy-check.py --candidate` PASS; `--require-ready` nadal **NOT_READY / kod 2**.
Dowody: `tmp/audit-a43-40-global-guards/report.json`, `commerce-retention.json`, archiwalne XML w `junit/`. [Szczegółowy opis](A43_GLOBALNE_BLOKADY_I_REJESTR.md).

**A43.40 i A43.41 pozostają otwarte.** Workery redakcji/minimum (Step 3), mechanizmy logów/monitoringu/Mailpit (Step 4), dowody środowiskowe (Step 5) i końcowy odbiór (Step 6) pozostają do wykonania. D01/D09/P09 nierozstrzygnięte, D17 bez aktywacji; A05/A41 i zdjęcia odłożone.

## Pakiet 121 — A43.40: workery redakcji D02 i kontrolowanego usuwania minimum (D02 Redaction & Minimum Removal Workers)

**31.10.2026, Audit_0.1, rewizja a43.40-2026-10-31.15.**

- [x] Precyzyjne migracje bazy danych bez rozrostu tabel i kolumn (106 tabel zachowane, 1017 kolumn zachowane, 188 zrecenzowanych migracji):
  - `order-service` V35: `DROP NOT NULL` na `order_items.product_name`, więz `ck_privacy_delivery_schedule_removed` na `orders`, autoryzacja redakcji pól prywatnych (`delivery_date`, `delivery_slot`, `pickup_location`, `product_name`) w `commerce_privacy_write_guard()`, ochrona przed fałszywym unieważnieniem zadania retencji kodem `ORDER_CHANGED` w `invalidate_commerce_retention()`.
  - `returns-service` V8: `DROP NOT NULL` na `return_lines.product_name`, autoryzacja nullifikacji `product_name`, `actor_ref` i `note` w `commerce_privacy_write_guard()`, ignorowanie redakcji w `capture_return_retention_change()`.
  - `delivery-service` V11: ignorowanie legalnej redakcji `sync_error` na `shipments` w `capture_commerce_retention_change()`.
- [x] Ograniczony worker redakcji D02 (`CommerceRetentionRedactionWorker`):
  - Weryfikacja blokady advisory lock (`436, hash(orderId)`), stanu zadania (`DUE_FOR_REVIEW`/`AGE_REACHED`), braku kwarantanny restore i obecności kompletu 8 potwierdzonych uczestników łańcucha.
  - Sprawdzenie reguły 365 dni od uzgodnienia całego procesu: `clock >= reconciledAt + 365 dni`; przed tym terminem zadanie przechodzi w `AGE_PENDING` z wynikiem `Outcome.AGE_NOT_REACHED`.
  - Atomowa redakcja danych osobowych i treści (`customer_email`, `delivery_*`, `delivery_date`, `delivery_slot`, `pickup_location`, `privacy_redacted = true` w `orders` oraz `product_name = null` w `order_items`).
  - Zachowanie nienaruszonych znaczników protokołu (`checkout_principal`, `checkout_request_hash`, `idempotency_key`, `collection_reference`, `delivery_tracking_number`, `promotion_code`, `inventory_reservation_id`).
  - Utrwalenie trwałego potwierdzenia faktycznej redakcji `redacted_at = clock_timestamp()` w `commerce_retention_evidence` i przejście do fazy `MINIMUM_REMOVAL`.
- [x] Ograniczony worker usuwania minimum (`CommerceRetentionMinimumRemovalWorker`):
  - Weryfikacja fazy `MINIMUM_REMOVAL` i trwałych dowodów `redacted_at` dla wszystkich 8 uczestników.
  - Sprawdzenie reguły 90 dni od najpóźniejszej faktycznej redakcji: `clock >= max(redacted_at) + 90 dni`.
  - Fail-closed weryfikacja zwolnienia zależności (`FINANCIAL_DEPENDENCIES_RELEASED`, `RESTORE_DEPENDENCIES_RELEASED`, `RETRY_DEPENDENCIES_RELEASED`, `DEDUP_DEPENDENCIES_RELEASED`).
  - Domyślny brak zwolnienia dokumentów finansowych D01 trwale blokuje usuwanie (`Outcome.BLOCKED`, `PRESERVATION_OR_DEPENDENCY_BLOCK`), gwarantując ochronę faktur, korekt i płatności.
  - W odizolowanym środowisku testowym (po jawnym zwolnieniu) usuwa pozycje zamówienia z zachowaniem rekordu zamówienia ze znacznikami protokołu (`MINIMUM_RETAINED`).
- [x] Workery redakcji w usługach dziedzinowych:
  - `ReturnsCommerceRetentionRedactionWorker`: redakcja pól opisowych zgłoszeń, pozycji i zdarzeń zwrotu z zachowaniem `request_hash` i `operation_id`.
  - `DeliveryCommerceRetentionRedactionWorker`: czyszczenie `sync_error` z zachowaniem `tracking_number` i `sync_token`.
- [x] Ochrona przed odtworzeniem danych: triggery bazodanowe uniemożliwiają ponowne wprowadzenie danych osobowych lub cofnięcie flagi w rekordach ze statusem `privacy_redacted = true`.

Odbiór: **8 nowych testów integracyjnych w PostgreSQL Testcontainers (wszystkie 8 PASS)**:
- `CommerceRetentionRedactionPostgresIT`:
  1. `redactionRejectedWhenAgeNotReached` (PASS)
  2. `redactionBlockedWhenHoldPresent` (PASS)
  3. `redactionBlockedWhenGuardMissing` (PASS)
  4. `redactionExecutesAfter365DaysAndPreservesProtocolMarkersAndFinancialIntegrity` (PASS)
  5. `triggersPreventRestorationOfRedactedData` (PASS)
  6. `minimumRemovalCannotExecuteBefore90Days` (PASS)
  7. `minimumRemovalPermanentlyBlockedWhenFinancialDependenciesNotReleased` (PASS)
  8. `minimumRemovalExecutesInIsolatedFixtureWhenDependenciesReleased` (PASS)

Bramki jakościowe QA:
- `data-lifecycle-check.py`: PASS (14 baz, 106 tabel, 1017 kolumn, 188 migracji, 47 mechanizmów, `fullA43Complete = false`).
- `technical-data-classification-check.py`: PASS (106 tabel, 1017 kolumn, 93 pominięcia, `identityMigrationImplemented = true`).
- `commerce-retention-check.py`: PASS (tryb `assessment-only`, `runtimeWired = false`, `activationAllowed = false`, `blockers` zachowane).
- `retention-policy-check.py`: PASS (`policyReady = false`, `activationAllowed = false`).
Dowody: `docs/A43_WORKERY_REDAKCJI_I_MINIMUM.md`, `order-service/src/test/java/.../CommerceRetentionRedactionPostgresIT.java`.

**A43.40 i A43.41 pozostają otwarte.** Mechanizmy logów/monitoringu/Mailpit (Step 4), dowody środowiskowe (Step 5) i końcowy odbiór (Step 6) pozostają do wykonania. D01/D09/P09 nierozstrzygnięte, D17 bez aktywacji; A05/A41 i zdjęcia odłożone.

## Pakiet 122 — A43.40: mechanizmy logów, monitoringu, Mailpit i procedur ręcznych (Operational Retention & Manual Procedures)

**31.10.2026, Audit_0.1, rewizja a43.40-2026-10-31.15.**

- [x] Ochrona wyjątków P09 (Fail-Closed Preservation Guard):
  - Klasa `OperationalRetentionGuard` w module `platform-security`: trwały rejestr blokad zachowania `PreservationHold` dla 4 zakresów: `LOGS`, `MONITORING`, `MAILPIT`, `CASES`.
  - Ścisłe fail-closed: brak zdefiniowanych wyjątków lub nierozstrzygnięte wyjątki P09 trwale blokują nieuzgodnione usunięcia (`isCleanupBlocked = true`).
  - Zwolnienie blokady wymaga podania tożsamości uprawnionego operatora i znacznika czasu.
- [x] Rzeczywista retencja logów D07 (`OperationalLogRetention`):
  - Retencja wiekowa: maksymalnie 30 dni od faktycznego zapisu wpisu (`entryWrittenAt`), nie od rotacji czy restartu.
  - Retencja rozmiarowa: limit 100 MiB (104 857 600 B) na źródło wraz z rotacjami; przekroczenie usuwa najstarsze zrotowane segmenty (`sizeMayRemoveOldestEarlier = true`).
  - Pełna ochrona plików legacy D10 (`PROTECTED_LEGACY`) oraz certyfikowanych logów zadań CC (`PROTECTED_CERTIFIED`).
  - Blokady zachowania P09 bezwzględnie wstrzymują czyszczenie.
- [x] Powiązanie z magazynami monitoringu D08 (`MonitoringRetentionPolicy` & `docker-compose.monitoring.yaml`):
  - Prometheus TSDB: flaga `--storage.tsdb.retention.time=30d` w `docker-compose.monitoring.yaml`.
  - Alarm budżetowy przy 1 GiB (1 073 741 824 B), bez wcześniejszego usuwania przez sam rozmiar (`sizeMayRemoveEarlier = false`).
  - Alertmanager: flaga `--data.retention=120h` (5 dni) w `docker-compose.monitoring.yaml`.
  - Grafana: retencja wycofanych dashboardów przez 90 dni od daty wycofania, blokada przy aktywnych zależnościach lub holdach P09.
- [x] Rzeczywista retencja Mailpit D14 (`MailpitRetentionManager` & `compose.yaml`):
  - Pojemność: `MP_MAX_MESSAGES: 1000` w kontenerze `mailpit` w `compose.yaml`.
  - Wiek: maksymalnie 30 dni od przyjęcia wiadomości; przekroczenie limitu 1000 usuwa najstarsze wiadomości wraz z załącznikami.
  - Ochrona spraw: wiadomości ze sprawami / dowodami oraz objęte blokadą P09 są trwale chronione przed usunięciem.
- [x] Procedury ręczne, role i zatrzymanie workera D15/D18 (`ManualCaseProcedureService`):
  - Podział ról: właściciel `operator-1`, zastępca `operator-2`.
  - Wyłączność oceny niejednoznacznych spraw: wyłącznie właściciel `operator-1`; próba rozstrzygnięcia przez `operator-2` lub automat rzuca `SecurityException` (P08 fail-closed).
  - Zegary spraw: dowody 90 dni, minimalny rekord 365 dni od bezpiecznego zamknięcia. Sprawy w toku (`OPEN`, `UNDER_AMBIGUOUS_REVIEW`, `DISPUTED`) trwale blokują usuwanie.
  - Scoped worker stop procedure: audytowane natychmiastowe zatrzymanie konkretnego workera retencji z podpisem operatora (`operator-1` lub `operator-2`) i powodem.

Odbiór: **14 nowych testów Java PASS (łącznie 209 testów platform-security PASS)**:
- `OperationalRetentionAndManualProceduresTest`:
  1. `defaultGuardIsFailClosed` (PASS)
  2. `registeredHoldBlocksCleanupUntilExplicitlyReleased` (PASS)
  3. `wildcardPatternMatchesMatchingTargets` (PASS)
  4. `prunesLogsOlderThan30DaysFromEntryWrite` (PASS)
  5. `prunesOldestRotatedSegmentsWhenTotalSizeExceeds100MiB` (PASS)
  6. `protectsD10LegacyAndCertifiedJobLogsAndHeldFiles` (PASS)
  7. `verifiesPrometheusAndAlertmanagerParameters` (PASS)
  8. `evaluatesRetiredGrafanaDashboardsRetention` (PASS)
  9. `prunesMessagesOlderThan30DaysAndKeepsHeldEvidence` (PASS)
  10. `prunesOldestMessagesWhenExceeding1000MessagesCapacity` (PASS)
  11. `ambiguousCaseReviewRequiresPrimaryOwnerOperator1` (PASS)
  12. `evaluatesCaseRetention90DaysEvidenceAnd365DaysMinimalRecord` (PASS)
  13. `activeOrDisputedCasesBlockRetentionPruning` (PASS)
  14. `scopedWorkerStopExecutedByBothOwnerAndDeputyWithAuditReceipt` (PASS)

Bramki jakościowe QA:
- `operational-retention-check.py`: PASS (`p09FailClosedEnforced: true`, monitoring, mailpit, logi i procedury zweryfikowane).
- `data-lifecycle-check.py`: PASS (14 baz, 106 tabel, 1017 kolumn, 188 migracji).
- `technical-data-classification-check.py`: PASS (106 tabel, 1017 kolumn, 93 pominięcia).
- `commerce-retention-check.py`: PASS (`mode: assessment-only`, `activationAllowed: false`).
- `retention-policy-check.py --candidate`: PASS (kod 0, `policySha256: 22c7c3785c1a21b6a6049b6e61119e1502f14a0fda4ac5c7cac5779e39139d88`).
- `retention-policy-check.py --require-ready`: NOT_READY (kod 1 / NOT_READY, zgodnie z kontraktem).
- `monitoring-check.py`: PASS (10 reguł, 15 targetów, promtool/amtool).
- `internal-copies-check.py`: PASS (11 zasobów).
Dowody: `docs/A43_MECHANIZMY_LOGOW_MONITORINGU_I_PROCEDUR.md`.

**A43.40 i A43.41 pozostają otwarte.** Dowody środowiskowe dev/test (Step 5) i końcowy odbiór (Step 6) pozostają do wykonania. D01/D09/P09 nierozstrzygnięte, D17 bez aktywacji; A05/A41 i zdjęcia odłożone.




## Pakiet 123 — poprawki po weryfikacji kodu (01.11.2026)

- P09 wymaga dowodu zakresu/wersji/ważności; boolean nie wyłącza odmowy.
- Wiek i rozmiar logów uwzględniają blokadę źródła. Kwalifikacja spraw nie
  ignoruje blokad i nie udaje faktycznego usunięcia danych.
- Siedem źródeł ma trwałe dzierżawy SQL oraz triggery egzekwujące zakaz
  zapisów między instancjami. Inventory uczestniczy w rundzie i ma chroniony endpoint.
- Niekompletne wykonawcze entrypointy D02 odmawiają operacji; nie zawierają
  już SQL mutacji ani obejścia przez fixture boolean.
- Nowe migracje chronią nieodwracalność redakcji Order/Returns bez dodatkowego
  znacznika usunięcia konta. Zwykłe zmiany treści Returns/Delivery unieważniają obserwacje.
- QA raportuje zakres konfiguracji, a model stopu nie potwierdza zakończenia workera.
- 9 przygotowanych migracji; 106 tabel / 1031 kolumn / 197 migracji.
- Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-3a4f5b99/report.json`,
  także wycofanie odtworzonych aktywnych tokenów; `releaseAllowed=false`.
- Końcowe wyniki i zakres: [pakiet 123](A43_POPRAWKI_BEZPIECZENSTWA_2026-11-01.md),
  `tmp/a43-fixes/report.json`. A43.40/.41 nadal otwarte, bez aktywacji lub zmian dev/test.

Odbiór pakietu 123: **333 unikalne Java / 80 unikalnych Python PASS**; pełny drill 14 baz/Redis PASS.
Kontrole strukturalne PASS, pełna gotowość NOT_READY / 2.

### Uzupełnienie statusu A05 — deklaracja użytkownika 01.11.2026

Użytkownik zadeklarował dodanie nazw `STRIPE_SECRET_KEY` i
`STRIPE_WEBHOOK_SECRET` do obu plików `dev.env.sops` oraz `test.env.sops`.
Nie odczytywano plików sekretów i nie potwierdzono niepustych/poprawnych
wartości. Jeżeli wpisy kończą się na samym `=`, wartości wymagają uzupełnienia.
A05 nadal odłożone; webhook i odbiór zewnętrznego sandboxu pozostają otwarte.

CC WWW ma stały katalog pól edycji sekretów, obecnie bez tych dwóch pól Stripe.
Backend powłoki je dopuszcza, a terminalowe `secrets-edit dev/test` uruchamia
pełny edytor SOPS. Samo dodanie nazwy do pliku nie rozszerza formularza WWW.
Nie wdrażano nowej funkcji CC ani nie zmieniano plików sekretów.

### A43.40 — pakiet 124: obserwacje Order i wiarygodność dowodów (01.11.2026)

- [x] Rzeczywiste stany sagi oraz znaczniki retry/przetwarzania blokują ocenę;
  brak lokalnego wpisu nadal nie daje globalnego potwierdzenia.
- [x] Dostawcy wywoływani poza SQL; ponowny odczyt właściciela, generacji,
  fazy/polityki oraz rewizji dowodów, procesu i blokad przed zapisem.
- [x] Goły callback `CONFIRMED`, skrót planu D02 i podpis starego wpisu
  rejestru nie potwierdzają kompletnej polityki lub aktualnej pieczy D09.
- [x] Ogólny zapis nie przyjmuje dodatnich potwierdzeń polityki/rejestru;
  historyczne wiersze zostają zachowane, lecz przy ocenie są `UNKNOWN`.
- [x] Order V37 usuwa zbyt szerokie wyjątki invalidacji dla kontaktu/treści.
  Błąd ujawniła szersza regresja; zachowano test wykrywający problem.
- [x] Kontrakt i bramka QA opisują zakres lokalnych obserwacji negatywnych;
  dodano próby odrzucania fałszywych deklaracji kompletności.

Rewizja `a43.40-2026-11-01.2`; **106 tabel / 1031 kolumn / 198 migracji**.
Wyniki końcowe, XML i logi: `tmp/a43-global-124/report.json`.
Końcowo **77 PostgreSQL / 80 unikalnych Python PASS**, bez pominięć;
kontrole strukturalne PASS, gotowość NOT_READY / kod 2.
Pierwszy przebieg Java wykazał lukę invalidacji; nie jest zaliczany do PASS.
Zakres i dalsze kroki: [pakiet 124](A43_GLOBALNE_DOWODY_KOLEKCJA_124.md).
Następne: źródłowe obserwacje spraw/retry/DLQ pozostałych usług,
kompletne dowody globalne i pełny protokół wykonania D02. A43.40/.41 nadal
otwarte; D01, D09/SAFE i P09 nierozstrzygnięte. A05, A41 i zdjęcia odłożone.
Nie aktywowano czyszczenia, nie zmieniano sekretów ani rzeczywistych dev/test.

### A43.40 — pakiet 125: obserwacje spraw/retry/DLQ źródeł (01.11.2026)

- [x] Odczyty kanonicznych stanów Returns, Payment, Promotion, Delivery,
  Billing i Notification; również follow-up DEAD_LETTER i otwarte DLQ po wysyłce.
- [x] Wewnętrzny POST tylko dla Order; istniejąca flaga domyślnie wyłączona,
  ścisły JSON i ograniczenia 2048 bajtów, prywatna odpowiedź bez cache.
- [x] Zakres bindingu/generacji/rewizji, polityka i nowy losowy identyfikator
  żądania; ograniczona ważność odpowiedzi oraz kontrola po oczekiwaniu na SQL.
- [x] Jeden odczyt poza transakcją SQL na krok, ponowna kontrola zakresu;
  zapisywane tylko blokady. Terminalne stany lub brak wpisów dają UNKNOWN.
- [x] Kontrolowany krok `reviewSourceGuards`; stare/odtworzone bindingi nie
  potwierdzają danych, zablokowana generacja jest wycofywana przez workflow.
- [x] Kontrakt QA określa niekompletne globalne pokrycie i brak aktywacji;
  testy odrzucają deklaracje dodatniej zgody i przyjmowania odpowiedzi bez zakresu.

Dowody końcowe: `tmp/a43-source-125/report.json`; zakres i punkt wznowienia:
[pakiet 125](A43_OBSERWACJE_ZRODEL_125.md). Bez migracji i zmian sekretów;
nadal 106 tabel / 1031 kolumn / 198 migracji. Pełne A43.40/.41 otwarte.
Końcowo **112 Java (15 jednostkowych / 97 PostgreSQL) + 80 unikalnych
Python PASS**, bez pominięć; kontrole strukturalne PASS, gotowość NOT_READY / 2.

**Polecenie użytkownika:** prace zależne od podania wartości/decyzji odłożyć
i podjąć następny niezależny krok. D01/P01, D09/SAFE i P09 pozostają odłożonymi
zależnościami bez zmiany wcześniejszych odpowiedzi, bez przyjmowania wartości
domyślnych lub zgody na czyszczenie. A05/A41 i zdjęcia nadal odłożone.
Następna praca: techniczny odbiór przebiegu HTTP/współbieżności obserwacji
oraz niezależne części protokołu D02. Brak pełnych globalnych dowodów nadal
blokuje wykonanie i zamknięcie A43. Nie zmieniono rzeczywistych dev/test/prod.

### A43.40 — pakiet 126: HTTP, współbieżność i awarie obserwacji (01.11.2026)

- [x] Lokalny przebieg klient HTTP → rzeczywisty filtr/kontroler → PostgreSQL
  sześciu źródeł; ograniczony zakres fikstur, bez wdrożenia aplikacji dev/test.
- [x] Odmowa dla klienta/operatora/innej usługi, braku logowania, GET i za
  dużego żądania; podszyty nagłówek nazwy usługi nie nadaje uprawnień.
- [x] HTTP 503/błędny JSON/przekroczony limit nie zapisują dowodu i nie
  uruchamiają automatycznego retry. Jawne ponowienie ma nowy identyfikator.
- [x] Zmiana Order i powtórzenie poprzedniej odpowiedzi nie przechodzą odbioru.
  Zmiana źródła po jego odczycie nie staje się fałszywą globalną zgodą.
- [x] Równoległe UNKNOWN pozostają oczekujące; konkurujące dowody blokady
  nie nadpisują rewizji. Istniejący dowód jest rozpoznawany idempotentnie.
- [x] Kolektor/workflow normalizują awarie SQL do UNAVAILABLE. Błąd przed
  COMMIT wycofuje zapis i rewizję; utrata potwierdzenia po COMMIT pozostawia
  wynik niepewny. Ponowienie nie dubluje już zatwierdzonego dowodu.
- [x] QA strukturalne nie deklaruje odbioru zachowania: pola weryfikacji
  HTTP/odzyskiwania są null; dowodem są zachowane XML i wyniki testów.

Dowody końcowe: `tmp/a43-http-126/report.json`;
[zakres i granice 126](A43_ODBIOR_HTTP_I_AWARII_126.md). Bez nowych migracji,
nadal 106 tabel / 1031 kolumn / 198 migracji. Bez pełnego drill restore.
Końcowo **132 Java (15 jednostkowych / 117 PostgreSQL) + 80 unikalnych
Python PASS**, bez pominięć; kontrole strukturalne PASS, gotowość NOT_READY / 2.
Następne: trwałe źródłowe potwierdzenia i niezależne techniczne części
protokołu D02. A43.40/.41 nadal otwarte, globalna zgoda i wykonanie niegotowe.
D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw nadal odłożone.
Zgodnie z poleceniem użytkownika brakujące decyzje/wartości odkładać i podejmować
następny niezależny krok; bez domyślnych zgód. A05/A41 i zdjęcia nadal odłożone.
Nie odczytywano sekretów i nie zmieniano rzeczywistych dev/test/prod.

### A43.40 — pakiet 127: trwała historia obserwacji źródeł (01.11.2026)

- [x] Order V38: niezmienne historyczne obserwacje, najwyżej jeden wpis na
  źródło/generację; sześć na zadanie. Bez kontaktu, treści i tokenów.
- [x] Świeża odpowiedź nadal wymagana; semantyczny skrót porównuje zakres,
  politykę i stany, a ponowienie zachowuje pierwszy wiersz i jego czasy.
- [x] Sprzeczna obserwacja wycofuje generację bez nadpisania historii.
  Zmiana źródła/bindingu nie jest ukrywana przez stary wpis.
- [x] Historia i blokada są we wspólnej transakcji. Błąd przed COMMIT
  wycofuje obie; utrata potwierdzenia po COMMIT nie dubluje danych przy retry.
- [x] Sama historia UNKNOWN nie zmienia efektywnych dowodów, rewizji, prób,
  dzierżawy lub zegara kwalifikacji. Nie stanowi potwierdzenia redakcji.
- [x] Inwentarz, klasyfikacja i polityki nowej tabeli: 107 tabel / 1045 kolumn /
  199 migracji. Ochrona protokołu bez automatycznego TTL lub aktywacji.
- [x] Pełny drill 14 baz/Redis zachował wpisy bieżącej i starszej generacji
  oraz wycofał odtworzone generacje; brak dopuszczenia środowiska.

Raport końcowy i XML/logi: `tmp/a43-receipts-127/report.json`.
Końcowo **137 Java (15 jednostkowych / 122 PostgreSQL) + 80 unikalnych
Python PASS**, bez pominięć; kontrole strukturalne PASS, gotowość NOT_READY / 2.
Drill: `tmp/privacy-recovery-6579b675/report.json` — PASS, releaseAllowed=false.
[Zakres, granice i punkt wznowienia](A43_TRWALE_OBSERWACJE_ZRODEL_127.md).
Następne: techniczny rejestr intencji/przejść stanu D02, bez zastępowania
obserwacją upoważnienia lub potwierdzenia faktycznej mutacji ośmiu źródeł.
A43.40/.41 nadal otwarte. D01/P01, D09/SAFE, P09 i odbiór ról/rejestru spraw
odłożone; etapy wymagające wartości/decyzji pomijać do następnej niezależnej pracy.
A05/A41 oraz zdjęcia nadal odłożone. Nie odczytywano sekretów użytkownika;
V38 nie zastosowano na rzeczywistych dev/test/prod, czyszczenie wyłączone.

### A43.40 — pakiet 128: rejestr intencji i bezpiecznych przejść D02 (01.11.2026)

- [x] Order V39: jedna intencja na zadanie/generację i do czterech zdarzeń.
  Zakres i historia niezmienne; metadane bez kontaktu, treści i tokenów dostępu.
- [x] Przygotowanie, stop i anulowanie nierozpoczętej intencji: blokady SQL,
  porównanie rewizji i idempotencja. Ponowienie po niepewnym COMMIT bez duplikatu.
- [x] Trigger dopisuje zdarzenie w tej samej transakcji co nagłówek; błąd
  dopisania zdarzenia wycofuje całe przejście.
- [x] Właściciel, wersja, generacja i faza nie mogą być podmienione. Stop
  historycznej intencji nie oznacza wznowienia lub mutacji danych biznesowych.
- [x] Nie ma stanów/metod AUTHORIZED, EXECUTING, COMPLETED. Wykonawcze API
  nadal odmawia operacji; executionTerminated=false także dla stopu/anulowania.
- [x] Replay kwarantanny intencji jest ograniczony partiami i monotoniczny;
  zachowuje wcześniejsze zdarzenia, bez automatycznego wznowienia lub TTL.
- [x] Inwentarz i klasyfikacja dwóch tabel/20 kolumn: 109 tabel / 1065 kolumn /
  200 migracji; istniejąca ochrona protokołu pozostaje bez zmian.

Dowody końcowe, XML i logi: `tmp/a43-intents-128/report.json`.
Odbiór: **45 PostgreSQL + 85 unikalnych Python PASS**, bez pominięć;
kontrole strukturalne PASS, gotowość NOT_READY / 2. Drill 14 baz/Redis PASS:
`tmp/privacy-recovery-3d360cc0/report.json`, 3 intencje w kwarantannie,
zachowana historia i idempotentny replay; releaseAllowed=false.
[Zakres, granice i punkt wznowienia](A43_REJESTR_INTENCJI_D02_128.md).
Nowy krok restore poprzedza dalsze zakresy i sesje; starsze fikstury uzupełniono
o tę kolejność, bez osłabiania kontroli brakującego lub częściowego schematu.
Następne: ponowna weryfikacja kompletu bieżących dowodów dla intencji i zakresu
udziału źródeł. Historia/oczekiwanie/stop nie są upoważnieniem lub wykonaniem.
D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw odłożone;
brakujące wartości/decyzje odkładać i podejmować kolejną niezależną pracę.
A05/A41 i zdjęcia nadal odłożone. Bez zastosowania V39 na rzeczywistych
dev/test/prod, bez sekretów użytkownika i bez aktywacji czyszczenia.

### A43.40 — pakiet 129: ponowna ocena dowodów dla intencji (01.11.2026)

- [x] Przygotowanie jest sprawdzane przed/po każdym odczycie: dokładny
  właściciel, wersja, generacja, faza, intencja/rewizja i rewizje dowodów/blokad.
- [x] Porównanie zakresu siedmiu bindingów i rewizji lokalnego procesu Order;
  osiem odczytów, najwyżej siedem zdalnych wywołań, poza transakcją SQL.
- [x] Stop/anulowanie w czasie RPC nie czeka na zdalną transakcję i blokuje
  dalszy przebieg; nie jest potwierdzeniem zakończenia wykonawcy.
- [x] Końcowa kontrola intencji, przejęcie i ocena w jednej transakcji.
  Błąd zapisu oceny wycofuje także nową dzierżawę/próbę; istniejąca jest zachowana.
- [x] Brak pełnego cache, zmiana przygotowania, obcy zakres, blokada lub
  kwarantanna nie uruchamiają nieuprawnionego odczytu/oceny.
- [x] Wynik diagnostyczny opisuje zakres obserwacji z chwili COMMIT, nie
  globalną migawkę. Intencja pozostaje WAITING_AUTHORIZATION, bez nowego zdarzenia.

Dowody końcowe i XML/logi: `tmp/a43-intent-review-129/report.json`.
Odbiór: **136 PostgreSQL + 85 unikalnych Python PASS**, bez pominięć;
kontrole strukturalne PASS, gotowość NOT_READY / 2.
[Zakres i granice](A43_WERYFIKACJA_DOWODOW_INTENCJI_129.md).
Bez nowych migracji: 109 tabel / 1065 kolumn / 200 migracji.
Nie powtarzano pełnego drillu; procedury restore i schemat pozostają bez zmian.
Następne: powiązać intencję z koordynacją i zwalnianiem dzierżaw obserwacji
źródeł po stopie/błędzie/utracie zakresu. Nadal bez zgody lub bariery mutacji.
D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw odłożone;
brakujące wartości/decyzje pomijać do następnej niezależnej pracy.
A05/A41 i zdjęcia nadal odłożone. Bez sekretów użytkownika, rzeczywistych
operacji dev/test/prod, nowego endpointu/CC i aktywacji czyszczenia.


### A43.40 — pakiet 130: dzierżawy obserwacji intencji (01.11.2026)

- [x] Ponowne odczyty ośmiu źródeł i PREPARE/VERIFY siedmiu dzierżaw,
  w tym Inventory; kontrola intencji i zakresu przed/po RPC, także po błędzie.
- [x] `finally` zwalnia każdy wysłany PREPARE, również po utracie odpowiedzi.
  Zachowuje pierwotny token/zakres; nie przejmuje nowej dzierżawy.
- [x] Walidacja akcji, zakresu i statusu RELEASED; błąd jednego zwolnienia
  nie pomija pozostałych. UNCONFIRMED raportowane osobno od stopu/błędu.
- [x] Brak SQL transakcji obejmującej RPC; końcowa kontrola, claim i ocena
  w jednej transakcji, z kontrolą terminów i rollbackiem po błędzie.
- [x] Rzeczywiste bazy sześciu źródeł: zapis i zwolnienie po ocenie,
  stopie i utracie odpowiedzi po COMMIT. Inventory SQL odebrano osobno;
  w rundzie koordynatora jego transport jest symulowany.
- [x] Intencja nadal WAITING_AUTHORIZATION; wynik nie daje zgody na mutację,
  nie potwierdza zakończenia wykonawcy ani pełnego globalnego snapshotu.

Dowody końcowe i XML/logi: `tmp/a43-intent-fences-130/report.json`.
Odbiór: **175 PostgreSQL + 85 unikalnych Python PASS**, bez pominięć;
kontrole strukturalne i candidate PASS, gotowość NOT_READY / 2.
[Zakres i punkt wznowienia](A43_DZIERZAWY_OBSERWACJI_INTENCJI_130.md).
Bez nowych migracji: 109 tabel / 1065 kolumn / 200 migracji.
Nie powtarzano pełnego drillu; restore i schemat bez zmian.
Następne: odbiór rundy przez uwierzytelniony HTTP wszystkich siedmiu źródeł
z rzeczywistymi bazami, w tym Inventory, ze stopem i utratą odpowiedzi
PREPARE/RELEASE. Następnie trwałe rozliczanie niepewnych rund; bez mutacji.
D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw odłożone;
brakujące wartości/decyzje odkładać i podejmować kolejną niezależną pracę.
A05/A41 i zmiany samych zdjęć nadal odłożone. Bez sekretów użytkownika,
rzeczywistych operacji dev/test/prod, nowego endpointu/CC i aktywacji czyszczenia.


### A43.40 — pakiet 131: odbiór HTTP dzierżaw siedmiu źródeł (01.11.2026)

- [x] Pełna runda koordynatora przez rzeczywiste HTTP siedmiu źródeł,
  kontrolery i filtry uwierzytelniania oraz osiem odrębnych baz PostgreSQL.
- [x] Inventory kompilowane ze swoich źródeł, bez starszych klas lub atrapy;
  brak zależności produkcyjnej Maven między usługami.
- [x] Osobna utrata odpowiedzi PREPARE i RELEASE po COMMIT każdego źródła.
  Zwolniony token SQL nie udaje potwierdzenia otrzymanego przez koordynatora.
- [x] Równoległy stop podczas odpowiedzi Inventory na PREPARE/VERIFY;
  koordynator nie trzyma SQL przez RPC, zwalnia wszystkie wysłane PREPARE.
- [x] Każdy endpoint odrzuca klienta/operatora/inną usługę, brak tokenu,
  niedozwoloną metodę, błędny JSON i nadmiarowy rozmiar; private/no-store.
- [x] Inventory zwraca 413 dla przekroczenia limitu żądania dzierżawy,
  spójnie z sześcioma pozostałymi źródłami; błędna treść nadal 400.

Dowody końcowe i XML/logi: `tmp/a43-intent-http-131/report.json`.
Odbiór: **27 PostgreSQL + 85 unikalnych Python PASS**, bez pominięć;
24 nowe przypadki HTTP, 3 regresje SQL. Kontrole strukturalne i candidate
PASS; gotowość NOT_READY / 2.
[Zakres i punkt wznowienia](A43_ODBIOR_DZIERZAW_HTTP_SIEDMIU_ZRODEL_131.md).
Awaria odpowiedzi jest modelowana przez 503 po COMMIT; nie jest to odbiór
zerwania TCP lub awarii procesu. Introspekcja jest fiksturą, nie pełnym
Account/JWT. Bez nowych migracji: 109 tabel / 1065 kolumn / 200 migracji.
Nie powtarzano pełnego drillu; restore i schemat bez zmian.
Następne: trwałe rozliczanie niepewnych rund obserwacji i odzyskiwanie stanu
po awarii, bez automatycznego wznowienia mutacji. Pełne wykonanie D02 otwarte.
D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw odłożone;
brakujące wartości/decyzje odkładać i kontynuować niezależne prace.
A05/A41 i zmiany samych zdjęć nadal odłożone. Bez sekretów użytkownika,
rzeczywistych operacji dev/test/prod i aktywacji czyszczenia.


### A43.40 — pakiet 132: trwała historia rund obserwacji (01.11.2026)

- [x] Order V40: dwie tabele/13 kolumn rejestru rund i prób; limit 8 rund
  na intencję i 7 prób na rundę, bez TTL lub nadpisywania historii.
- [x] Zapis próby przed PREPARE; brak wysyłki po błędzie lub niepewnym
  COMMIT. Nieukończona runda blokuje kolejną rundę tej samej intencji.
- [x] Osobne potwierdzenie RELEASE w SQL; diagnoza sieciowa nie udaje
  trwałego zapisu. Błąd zapisu pozostawia brakujące potwierdzenie.
- [x] Odczyt po restarcie w oryginalnym zakresie, bez RPC, retry lub TTL
  udającego potwierdzenie. Triggery chronią zakres, historię i monotoniczność.
- [x] Restore nakłada kwarantannę przed intencjami/sesjami, w ograniczonych
  partiach. Zachowuje zakończone i nieuzgodnione rundy oraz historię prób.
- [x] Pełny drill 14 baz/Redis: 3 rundy w kwarantannie, 2 próby zachowane,
  brakujące potwierdzenie nadal brakujące, powtórny replay idempotentny.

Dowody końcowe i XML/logi: `tmp/a43-round-history-132/report.json`.
Odbiór: **94 PostgreSQL + 90 unikalnych Python PASS**, bez pominięć;
70 workflow/rejestr, 24 HTTP. Kontrole strukturalne i candidate PASS,
gotowość NOT_READY / 2. Pełny drill 14 baz/Redis PASS:
`tmp/privacy-recovery-321eddc9/report.json`.
[Zakres i punkt wznowienia](A43_TRWALY_REJESTR_RUND_OBSERWACJI_132.md).
Inwentarz: 111 tabel / 1078 kolumn / 201 migracji; 47 mechanizmów,
6 przestrzeni Redis, 20 kategorii kopii i 26 polityk.
Następne: źródłowa ochrona przed późnym/replayowanym PREPARE po RELEASE.
Dopiero potem kontrolowane RPC rozliczania niepewnych rund; bez mutacji.
`finished_at` oznacza tylko rozliczenie zapisanych prób, nie wykonanie
usuwania, globalne odblokowanie lub zatrzymanie wykonawcy.
D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw odłożone;
brakujące wartości/decyzje odkładać i kontynuować niezależne prace.
A05/A41 i zmiany samych zdjęć nadal odłożone. V40 wyłącznie na fiksturach;
bez sekretów użytkownika, operacji na rzeczywistych dev/test/prod i aktywacji.


### A43.40 — pakiet 133: źródłowa ochrona przed replay PREPARE (01.11.2026)

- [x] Znaczniki tokenów w bazach wszystkich 7 źródeł: 7 tabel/28 kolumn,
  limit 8 na zamówienie/epokę. Bez TTL i kasowania przy zmianie bindingu.
- [x] RELEASE zamyka token także przed PREPARE; atomowość z usunięciem
  dzierżawy pod dotychczasową blokadą PostgreSQL współdzieloną przez repliki.
- [x] Zamknięty lub wygasły wydany token nie pozyskuje dzierżawy ponownie.
  Aktywny retry ma ten sam termin; stary RELEASE nie usuwa nowszego tokenu.
- [x] Testy wszystkich 7 źródeł HTTP/SQL, w tym współbieżność, limit,
  monotoniczność i utrzymanie dotychczasowych scenariuszy intencji.
- [x] Drill 14 baz/Redis zachowuje 14 markerów w pierwotnych epokach,
  usuwa aktywne dzierżawy i utrzymuje kwarantannę/historię Order.

Dowody: `tmp/a43-fence-replay-133/report.json`.
Odbiór: **59 PostgreSQL + 90 unikalnych Python PASS**, bez pominięć;
35 przypadków replay i 24 regresje HTTP. Kontrole strukturalne i candidate
PASS; NOT_READY / 2. Pełny drill 14 baz/Redis PASS:
`tmp/privacy-recovery-87aee146/report.json`.
[Zakres i punkt wznowienia](A43_OCHRONA_POWTORZEN_PREPARE_133.md).
Inwentarz: 118 tabel / 1106 kolumn / 208 migracji; 47 mechanizmów,
6 przestrzeni Redis, 20 kategorii kopii i 26 polityk.
Migracje: Returns V11, Payment V24, Inventory V15, Promotion V13,
Delivery V13, Billing V19 i Notification V21; wyłącznie na fiksturach.
Ochrona wymaga nowego adaptera wszystkich replik; wersji mieszanych
i rzeczywistych dev/test nie odebrano. Bez mutacji lub RPC odzyskiwania.
Następne: kontrolowane przejęcie/rozliczanie niepewnej rundy Order,
z ochroną przed równoległą starą instancją; bez automatycznego wznowienia.
D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw odłożone;
brakujące wartości/decyzje odkładać i kontynuować niezależne prace.
A05/A41 i zmiany samych zdjęć nadal odłożone. Bez sekretów użytkownika,
operacji na rzeczywistych danych i aktywacji czyszczenia.


### A43.40 — pakiet 134: kontrolowane odzyskiwanie rund tylko RELEASE (01.11.2026)

- [x] Order V41: 4 kolumny przejęcia w istniejącym rejestrze. Monotoniczny
  cleanup-only, losowy właściciel SQL/15 s i maksymalnie 8 przejęć.
- [x] Jawny przebieg do 7 RELEASE dla pierwotnych zachowanych prób.
  Brak PREPARE/VERIFY, schedulera, automatycznego retry lub mutacji.
- [x] Kontrola właściciela przed/po RPC i w SQL zapisu; stara instancja
  nie dopisuje prób, nie zapisuje w imieniu nowej i nie przechodzi do oceny.
- [x] Niepewny COMMIT przejęcia nie uruchamia RPC; negatywna/brakująca
  odpowiedź pozostawia historię oczekującą. Tylko jawne kolejne wywołanie.
- [x] Wygasły właściciel nie zapisuje, nie zamyka ani nie zwalnia nowszego
  przejęcia. Stop/anulowanie nie uniemożliwia sprzątania oryginalnego zakresu.
- [x] HTTP rzeczywistych 7 źródeł i przejęcie podczas Inventory PREPARE;
  Source 133 blokuje późny PREPARE, a Root nie wykonuje nowej oceny.
- [x] Restore usuwa token/termin właściciela, zachowuje cleanup-only,
  budżet i historię, utrzymuje kwarantannę bez automatycznego wznowienia.

Dowody: `tmp/a43-round-recovery-134/report.json`.
Odbiór: **92 PostgreSQL + 90 unikalnych Python PASS**, bez pominięć;
83 workflow i 9 HTTP. Kontrole strukturalne i candidate PASS; NOT_READY / 2.
Pełny drill 14 baz/Redis PASS:
`tmp/privacy-recovery-9dca08db/report.json`.
[Zakres i punkt wznowienia](A43_ODZYSKIWANIE_RUND_TYLKO_RELEASE_134.md).
Inwentarz: 118 tabel / 1110 kolumn / 209 migracji; 47 mechanizmów,
6 przestrzeni Redis, 20 kategorii kopii i 26 polityk. V41 tylko na fiksturach.
Następne: timeout/utrata właściciela w locie, zmieniony binding i niepewny
COMMIT końcowego rozliczenia. Potem globalne źródła blokad i protokół D02.
SETTLED nie oznacza zgody na usuwanie lub zakończenia wykonawcy.
D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw odłożone;
brakujące wartości/decyzje odkładać i kontynuować niezależne prace.
A05/A41 i zmiany samych zdjęć nadal odłożone. Bez sekretów użytkownika,
operacji na rzeczywistych danych i aktywacji czyszczenia.


### A43.40 — pakiet 135: awarie odzyskiwania rund (01.11.2026)

- [x] Rzeczywisty timeout klienta przed nagłówkami (7 źródeł) i podczas
  treści (Inventory); COMMIT źródła nie udaje potwierdzenia Root.
- [x] Kwarantanna SQL podczas odpowiedzi RELEASE: 7 źródeł; późna
  odpowiedź nie zapisuje potwierdzenia lub zakończenia, bez wznowienia.
- [x] Zmiana bindingu w 7 źródłach: oryginalny zakres pozostaje oczekujący,
  nowsza dzierżawa zachowana; brak wnioskowania sukcesu z nowej epoki.
- [x] Rollback rozliczenia zachowuje potwierdzenia; jawne ponowienie
  bez RPC do potwierdzonych źródeł. Niepewny COMMIT: UNAVAILABLE,
  odczyt trwałego zakończenia i FINISHED bez ponownego RPC.
- [x] Kontrakt QA utrwala reguły awarii; test mutacji odrzuca ich zmianę.

Odbiór: **46 PostgreSQL + 90 unikalnych Python PASS**, bez pominięć;
24 nowe scenariusze awarii + 22 regresje (15 workflow / 31 HTTP).
Dowody: `tmp/a43-recovery-faults-135/report.json`. [Zakres i punkt wznowienia](A43_AWARIE_ODZYSKIWANIA_RUND_135.md).
Bramki i candidate PASS; pełna gotowość NOT_READY / 2.
Bez zmian Java produkcyjnego, schematu lub restore. Inwentarz 118/1110/209;
pełny drill niepowtarzany, historyczny dowód 134 pozostaje właściwy.
Następne: kompletne źródła globalnych blokad, ich zakresy i rewizje,
następnie upoważnienie/wykonanie D02. SETTLED nie zezwala na mutację.
D01/P01, D09/SAFE, P09 i odbiór rzeczywistych ról/rejestru odłożone.
A05, A41 i same zdjęcia odłożone. Bez sekretów i danych dev/test,
migracji środowiskowych lub aktywacji czyszczenia.


### A43.40 — pakiet 136: Inventory i macierz blokad D02 (01.11.2026)

- [x] Chroniony odczyt blokad Inventory; aktywna rezerwacja blokuje,
  zakończona lub brak rekordu daje UNKNOWN. Brak globalnego CONFIRMED.
- [x] Dokładny zakres, challenge i świeżość odpowiedzi; walidacja Root
  przed/po RPC; bez SQL utrzymywanego przez wywołanie zdalne.
- [x] Order V42: Inventory jako siódme źródło niezmiennej historii;
  limit jednej obserwacji na źródło/generację/fazę, bez nadpisywania.
- [x] Macierz ośmiu uczestników i rozpisane niepokryte źródła/ograniczenia.
- [x] Pełny restore zachowuje trzy obserwacje, w tym Inventory, jako
  historię; bez aktualnego upoważnienia i automatycznego wznowienia.

Odbiór: **121 Java (107 PostgreSQL / 14 jednostkowych) + 90 unikalnych Python PASS**, bez pominięć. 24 nowe przypadki;
pozostałe regresje. Pierwotny test próbował zmienić niezmienny BOUND;
po korekcie do legalnej kwarantanny powtórzono zestaw HTTP.
Dowody: `tmp/a43-guard-coverage-136/report.json`; drill: `tmp/privacy-recovery-e7ff4722/report.json`.
Bramki/candidate PASS; NOT_READY / 2. Inwentarz 118/1110/210;
V42 tylko na fiksturach. Bez zmian konfiguracji, sekretów, danych dev/test
lub aktywacji. Następne: trwałe pokrycie luk blokad/spraw i protokół D02.
D01/P01, D09/SAFE, P09 i odbiór rzeczywistych ról/rejestru odłożone;
A05, A41 i same zdjęcia nadal odłożone.


### A43.40 — pakiet 137: trwałe blokady źródła Returns (01.11.2026)

- [x] Returns V12: 1 tabela / 7 kolumn; znane blokady trwałe przez
  zmiany generacji i epoki, bez treści, kontaktu, TTL lub zwalniania.
- [x] Rewizja SQL, atomowe unieważnienie/outbox, idempotentne request_id,
  konflikt i rollback; negatywny wpis nie usuwa tokenu aktywnej dzierżawy.
- [x] Odczyt 3 rodzajów blokad po rebind przez rzeczywisty HTTP;
  brak wpisu daje UNKNOWN, a dodatnie potwierdzenia są odrzucane.
- [x] Order V43: hold_state, zachowana zgodność v1 UNKNOWN, v2 BLOCKED
  rozróżnia zmianę blokady przy tej samej rewizji. Niezmienna historia.
- [x] Pełny restore zachowuje rejestr Returns i 4 obserwacje, w tym
  BLOCKED; kwarantanna i brak aktualnego upoważnienia pozostają.

Odbiór: **132 Java (117 PostgreSQL / 15 jednostkowych) + 90 unikalnych Python PASS**, bez pominięć; 12 nowych przypadków,
pozostałe regresje. Bramki/candidate PASS; NOT_READY / 2.
Dowody: `tmp/a43-source-holds-137/report.json`; drill: `tmp/privacy-recovery-acaf7452/report.json`.
Inwentarz 119/1118/212; nowe migracje tylko na fiksturach. Katalog cyklu
życia i testy liczników uzupełniono po wykryciu pominięcia nowej tabeli;
finalny przebieg Python dotyczy poprawionych źródeł.
Pozostałe 6 usług bez rejestrów; proces obsługi spraw, kompletność i D02
otwarte. Brak endpointu zapisu, przycisku CC, sekretów, danych środowisk
i aktywacji. D01/P01, D09/SAFE, P09 i odbiór ról/rejestru odłożone;
A05, A41 i same zdjęcia nadal odłożone.


### A43.40 — pakiet 138: rejestry blokad siedmiu źródeł (01.11.2026)

- [x] Sześć migracji według wzorca Returns: Payment V25, Inventory V16,
  Promotion V14, Delivery V14, Billing V20, Notification V22.
- [x] Wszystkie 7 źródeł odczytuje lokalne trwałe blokady; brak wpisu
  daje UNKNOWN. Brak dodatniej zgody, zwalniania, TTL lub endpointu zapisu.
- [x] 49 przypadków dla 7 źródeł: 21 odczytów 3 rodzajów blokad przez HTTP
  po rebind, po 7 dla rollback/replay/konfliktu, niezmienności/rewizji,
  aktywnej dzierżawy i współbieżnych powtórzeń.
- [x] Regresje Returns, zgodności historii v1/v2, uprawnień, braku globalnej
  zgody, Inventory i rozliczania RELEASE.
- [x] Pełny restore zachowuje 7 rejestrów i 10 obserwacji (3 starsze UNKNOWN,
  7 BLOCKED), kwarantannę i brak aktualnego upoważnienia/wznowienia.

Odbiór: **112 Java (97 PostgreSQL / 15 jednostkowych) + 90 unikalnych Python PASS**, bez pominięć; 49 nowych przypadków,
pozostałe regresje. Bramki/candidate PASS; NOT_READY / 2.
Dowody: `tmp/a43-all-source-holds-138/report.json`; drill: `tmp/privacy-recovery-937a46c5/report.json`.
Inwentarz 125/1160/218; dodano 6 tabel / 42 kolumny / 6 migracji,
wyłącznie na fiksturach. Następne: trwały proces przyjmowania/powiązania
rzeczywistych spraw i kompletność; następnie D02. Nie powtarzać 6 rejestrów
jako brakującego zakresu. Bez CC, sekretów, konfiguracji, danych środowisk
lub aktywacji. D01/P01, D09/SAFE, P09 i odbiór ról/rejestru odłożone;
A05, A41 i same zdjęcia nadal odłożone.


### A43.40 — pakiet 139: lokalne przyjmowanie wniosków blokad (01.11.2026)

- [x] Order V44: 2 tabele / 12 pól. Jeden COMMIT dla negatywnej blokady,
  wniosku, referencji sprawy i siedmiu planowanych identyfikatorów.
- [x] Odraczana kompletność SQL, dokładny zakres, idempotencja, konflikt,
  rollback i współbieżność; odrzucenie transakcji wywołującego.
- [x] Niepewny COMMIT nie zwraca sukcesu; retry zachowuje identyfikatory.
- [x] Niezmienna historia i odczyt po zakupie/request_id/referencji sprawy;
  referencja nie dowodzi własności lub zweryfikowania sprawy.
- [x] Jawny test mapowania do 7 znanych źródeł po lokalnym COMMIT,
  bez udawania trwałych zdalnych potwierdzeń lub działającego transportu.
- [x] Restore kwarantannuje wniosek w ograniczonych partiach, zachowuje
  tożsamości i plan; awaria/niekompletność blokuje dalsze odtworzenie.

Odbiór: **52 PostgreSQL + 96 unikalnych Python PASS**, bez pominięć; 21 nowych przypadków Java
i 6 Python. Po uzupełnieniu starszych atrap kolejności restore powtórzono
Python; produkcyjny kod restore pozostał bez zmiany względem pierwszego
przebiegu. Bramki/candidate PASS; NOT_READY / 2.
Dowody: `tmp/a43-hold-intake-139/report.json`; drill: `tmp/privacy-recovery-7b175b22/report.json`.
Inwentarz 127/1172/219; V44 tylko na fiksturach. Transport, potwierdzenia,
zweryfikowany proces rzeczywistych spraw i kompletność nadal otwarte.
Brak operator API, nowych praw, CC, sekretów, konfiguracji, danych środowisk
lub aktywacji. D01/P01, D09/SAFE, P09 i odbiór ról/rejestru odłożone;
A05, A41 i same zdjęcia nadal odłożone.


### A43.40 — pakiet 140: dostarczanie blokad do źródeł (01.11.2026)

- [x] Osobny chroniony POST, ścisły protokół 2048 B, 7 odbiorników; źródło potwierdza po swoim COMMIT.
- [x] Order V45: 2 tabele / 11 kolumn. Niezmienna próba przed RPC i historyczny ACK dokładnego żądania; max. 8 prób/źródło.
- [x] Bez transakcji Root przez RPC, odrzucenie transakcji wywołującego; idempotencja, konflikt, rollback, timeout i utrata odpowiedzi/COMMIT.
- [x] Nieznany zakup bez domyślnego bindingu; kwarantanna przed/po RPC blokuje ACK.
- [x] Restore zachowuje próby, budżet i ACK, kwarantannuje rodzica i nie wznawia wysyłki.

Odbiór: **87 Java (75 PostgreSQL / 12 jednostkowych) + 98 unikalnych Python PASS**, bez pominięć; 65 nowych Java
i 2 Python. Bramki/candidate PASS; NOT_READY / 2.
Dowody: `tmp/a43-hold-delivery-140/report.json`; drill: `tmp/privacy-recovery-03d88010/report.json`.
Inwentarz 129/1183/220, V45 tylko na fiksturach. Endpoint domyślnie
wyłączony; brak schedulera, operator API, zmian sekretów lub danych środowisk.
Historyczny ACK nie jest aktualnym dowodem kompletności po utracie źródła.
Następne: kontrolowany odczyt postępu, obsługa wyczerpanego budżetu i uzgodnienie po utracie/odtworzeniu pojedynczego źródła; potem zweryfikowany proces spraw, kompletność i pełne D02.
D01/P01, D09/SAFE, P09 i odbiór ról/rejestru odłożone; A05, A41
i same zdjęcia pozostają odłożone. Bez aktywacji czyszczenia.


### A43.40 — pakiet 141: postęp doręczania blokad (01.11.2026)

- [x] Dokładny zakup/wniosek/referencja sprawy; nieznany zakres bez liczników.
- [x] Ograniczony snapshot 7 źródeł, wspólna blokada rodzica z piszącymi historię.
- [x] NOT_ATTEMPTED, OUTCOME_UNKNOWN, REVIEW_REQUIRED, HISTORICAL_RECEIPT; bez udawania aktualnej kompletności.
- [x] Kwarantanna zachowuje stan QUARANTINED i historyczne liczniki; błąd SQL bez częściowego wyniku.
- [x] Brak RPC, DML, retry, resetu budżetu, nowej migracji lub operator API.

Odbiór: **70 Java (58 PostgreSQL / 12 jednostkowych) + 98 unikalnych Python PASS**, bez pominięć; 27 nowych PostgreSQL.
Dodano 6 mutacji kontraktu w istniejącym teście Python. Bramki/candidate
PASS; NOT_READY / 2. Dowody: `tmp/a43-hold-progress-141/report.json`.
Inwentarz 129/1183/220, restore/migracje bez zmian. Pełny drill 140
pozostaje historyczny; w 141 go nie powtarzano.
Następne: uzgodnienie historycznych potwierdzeń z aktualnym źródłem po jego utracie/odtworzeniu; potem zweryfikowany proces spraw, kompletność i pełne D02.
D01/P01, D09/SAFE, P09 i odbiór ról/rejestru odłożone; A05, A41
i same zdjęcia nadal odłożone. Bez sekretów, rzeczywistych danych
lub aktywacji czyszczenia.


### A43.40 — pakiet 142: porównanie blokad ze źródłem (01.11.2026)

- [x] Chroniony POST tylko dla Order; osobna flaga domyślnie false, limity 2048/4096 B.
- [x] Dokładny zakup/wniosek/sprawa i trwały ACK wymagane przed pojedynczym RPC.
- [x] Odczyt źródła pod wspólnymi blokadami SQL; dokładny UUID, zakup, rodzaj i powód.
- [x] Nowy challenge, ścisła struktura, wiek 15 s i tolerancja przyszłości 5 s; ponowna walidacja po odczycie Root.
- [x] Wykrycie brakującej blokady, konfliktu, nieznanego zakupu i kwarantanny w 7 źródłach.
- [x] Kwarantanna Root podczas RPC odrzuca obserwację; timeout/błąd SQL nie daje fałszywego braku lub dopasowania.
- [x] Bez nadpisywania ACK, resetu prób, naprawy, nowej migracji lub automatycznego zapisu uzgodnienia.

Odbiór: **178 Java (154 PostgreSQL / 24 jednostkowe) + 98 unikalnych Python PASS**, bez pominięć; 64 nowe Java
(52 HTTP/SQL i 12 jednostkowych), 8 mutacji kontraktu w istniejącym
teście Python. Bramki/candidate PASS; NOT_READY / 2. Dowody: `tmp/a43-hold-reconciliation-142/report.json`.
Inwentarz 129/1183/220, restore/migracje bez zmian. Drillu 140 nie
powtarzano; pozostaje historycznym dowodem z niezmiennymi odciskami.
Następne: trwała ograniczona historia porównań i przegląd rozbieżności; potem kontrolowane rozliczenie/naprawa bez resetu prób, zweryfikowane sprawy, kompletne aktualne dowody i pełne D02.
D01/P01, D09/SAFE, P09 i odbiór ról/rejestru odłożone; A05, A41
i same zdjęcia nadal odłożone. Bez sekretów, danych środowisk
lub aktywacji czyszczenia.


### A43.40 — pakiet 143: trwała historia porównań blokad (01.11.2026)

- [x] Order V46: 2 tabele / 13 pól, niezmienna próba przed RPC i wynik po odczycie.
- [x] UUID próby jako challenge, dokładna tożsamość ACK, max. 8 prób/źródło.
- [x] Idempotentny odczyt wyniku/PENDING, bez ponownego RPC, resetu lub automatycznego retry.
- [x] Utrata COMMIT próby bez RPC; utrata COMMIT wyniku rozpoznawana odczytem.
- [x] Ograniczony przegląd jednego źródła; kwarantanna zachowuje historię i blokuje nowe próby.
- [x] Późna obserwacja przy kwarantannie Root tylko jako ROOT_QUARANTINED.
- [x] Restore zachowuje próby/wyniki/budżet, weryfikuje tożsamości i nie tworzy wyniku dla PENDING.

Odbiór: **177 Java (153 PostgreSQL / 24 jednostkowe) + 101 unikalnych Python PASS**, bez pominięć; 54 nowe Java
(39 Root PostgreSQL / 15 rzeczywistych HTTP/SQL), 3 nowe Python
i 6 mutacji kontraktu w istniejącym teście. Bramki/candidate PASS;
NOT_READY / 2. Dowody: `tmp/a43-hold-history-143/report.json`.
Pełny drill: `tmp/privacy-recovery-b7d43150/report.json`, 2 próby/1 wynik zachowane
pod kwarantanną, bez wznowienia. Inwentarz 131/1196/221; V46 tylko na
fiksturach. Następne: kontrolowane rozliczenie rozbieżności i kwalifikacja do naprawy z zachowaniem historii, kwarantanny i budżetów; potem zweryfikowane sprawy, kompletne wersjonowane dowody i pełne D02.
D01/P01, D09/SAFE, P09 i odbiór ról/rejestru odłożone; A05, A41
i same zdjęcia nadal odłożone. Bez sekretów, rzeczywistych danych
lub aktywacji czyszczenia.


### A43.40 — pakiet 144: kwalifikacja rozbieżności blokad (01.11.2026)

- [x] Dokładny zakup/wniosek/sprawa, snapshot pod blokadą rodzica; do 7 źródeł i 56 prób.
- [x] Rozróżnienie braku ACK/porównania, PENDING, przeterminowania, konfliktu, nieznanego zakupu i kwarantanny.
- [x] Każde PENDING blokuje kandydata; późniejszy MATCH nie usuwa licznika wcześniejszych rozbieżności.
- [x] Kandydat z dokładnymi UUID/guard/reason/probe/wersją wymaga nowego sprawdzenia źródła i sprawy; executionAllowed=false.
- [x] Kwarantanna odbiera wszystkich kandydatów, niespójność historii lub błąd SQL bez częściowych kwalifikacji.
- [x] Bez RPC, DML, resetu budżetu, nowej migracji, operator API lub aktywacji.

Odbiór: **112 Java (100 PostgreSQL / 12 jednostkowych) + 101 unikalnych Python PASS**, bez pominięć; 44 nowe Java
(37 Root PostgreSQL / 7 HTTP), 6 mutacji w istniejącym teście Python.
Bramki/candidate PASS; NOT_READY / 2. Dowody: `tmp/a43-repair-assessment-144/report.json`.
Inwentarz 131/1196/221 bez zmian. Pełny drill 143 pozostaje historyczny;
nie powtarzano go w 144, sprawdzono odciski źródeł restore i migracji.
Następne: trwały kontrolowany protokół rozliczenia PENDING i intencji odtworzenia brakującej blokady; przed wykonaniem atomowe sprawdzenie źródła i zweryfikowane upoważnienie sprawy, potem pełne D02.
D01/P01, D09/SAFE, P09 i odbiór ról/rejestru odłożone; A05, A41
i same zdjęcia nadal odłożone. Bez sekretów lub danych środowisk.


### A43.40 — pakiet 145: rozliczanie PENDING i intencje naprawy (01.11.2026)

- [x] Order V47: 2 tabele / 11 pól. Niezmienny znacznik zastąpienia odczytu PENDING późniejszym wynikiem tej samej tożsamości.
- [x] Najnowszy zakończony MATCH/MISSING, wiek 15 s / tolerancja przyszłości 5 s; bez rozliczenia przez sam TTL.
- [x] SQL rozstrzyga wyścig: wynik pierwszy blokuje znacznik, znacznik pierwszy blokuje późny wynik starego kodu.
- [x] Historia SUPERSEDED i licznik resolvedPendingProbes; brak ponownego RPC i resetu budżetu.
- [x] Intencja z najnowszego świeżego MISSING bez aktywnego PENDING, z oryginalnymi UUID; idempotencja bez zmiany podstawy.
- [x] Ograniczony przegląd, kwarantanna, odporność na utratę odpowiedzi COMMIT; bez wykonania lub operator API.
- [x] Restore zachowuje znaczniki/intencje pod kwarantanną, bez ponownej aprobaty według dzisiejszego czasu i bez wykonania.

Odbiór: **168 unikalnych Java (156 PostgreSQL / 12 jednostkowych) + 104 Python PASS**, bez pominięć; 56 nowych Java
(49 Root PostgreSQL / 7 HTTP), 3 Python i 7 mutacji polityki. Po zmianie
odczytu na powiązanie z próbami źródła powtórzono 88 przypadków,
bez podwójnego liczenia. Bramki/candidate PASS; NOT_READY / 2.
Dowody: `tmp/a43-recovery-planning-145/report.json`; drill: `tmp/privacy-recovery-9f20b6ee/report.json`.
Inwentarz 133/1207/222; V47 tylko na fiksturach. Zachowane 4 próby,
2 wyniki, 1 rozliczenie i 1 intencja, bez wykonania; pozostałe PENDING
bez wyniku. Następne: atomowy protokół sprawdzenia źródła i odtworzenia brakującej negatywnej blokady z oryginalnym UUID, trwałymi próbami/potwierdzeniami oraz ochroną replay/restore; wykonanie wymaga zweryfikowanego upoważnienia sprawy, potem pełne D02.
D01/P01, D09/SAFE, P09 i odbiór ról/rejestru odłożone; A05, A41
i same zdjęcia nadal odłożone. Bez sekretów lub danych środowisk.


### A43.40 — pakiet 146: atomowe odtworzenie blokady źródłowej (02.11.2026)

- [x] Siedem źródeł: advisory lock + FOR UPDATE, CAS epoch/revision i dokładna tożsamość; oryginalny UUID blokady.
- [x] Blokada, unieważnienie obserwacji/outbox i niezmienny receipt w jednej transakcji; RESTORED dopiero po COMMIT.
- [x] Podpis Ed25519 dokładnego zakresu; ważność do 15 s / tolerancja przyszłości 5 s; bez boolean approval, tokenu w SQL lub odpowiedzi.
- [x] Idempotentne potwierdzenie tej samej intencji/upoważnienia i obecności blokady; REPLAY_ONLY nie zapisuje nowej blokady.
- [x] Kwarantanna blokuje zapis i replay; konflikt nie nadpisuje danych, nieznany zakup nie tworzy bindingu.
- [x] Order przygotowuje żądanie z trwałej intencji i MISSING, bez RPC/DML lub weryfikacji sprawy.
- [x] Endpoint tylko dla Order, ograniczone treści i sanitacja błędów; domyślnie wyłączony, włączenie bez klucza blokuje startup.
- [x] Restore zachowuje siedem receiptów i weryfikuje powiązania pod kwarantanną, bez odnowienia upoważnień.
- [ ] Dispatcher Root, jego trwałe próby/potwierdzenia i jawne odzyskanie tylko przez replay.
- [ ] Rzeczywisty zaufany wystawca, jego proces weryfikacji, klucz publiczny, rotacja/odwoływanie i odbiór tożsamości.

Odbiór: **147 unikalnych Java (109 PostgreSQL / 38 jednostkowych) + 107 Python PASS**, bez błędów i pominięć; 92 nowe Java
(14 jednostkowych / 19 Root PostgreSQL / 59 HTTP), 3 Python i 7 mutacji.
Bramki/candidate PASS, NOT_READY / 2. Dowody: `tmp/a43-source-restoration-146/report.json`;
pełny drill: `tmp/privacy-recovery-e9f11d6b/report.json`. Inwentarz 140/1270/229, siedem migracji
tylko na fiksturach. Nowy zakres do odbioru użytkownika: zaufany wystawca upoważnień spraw, jego proces weryfikacji/tożsamości, publiczny klucz Ed25519, rotacja i odwoływanie upoważnień. Nie dodano wystawcy lub prywatnego klucza produkcyjnego; konfiguracja pozostaje wyłączona.
Następne: trwałe próby i potwierdzenia dispatchera Root, jawne odzyskiwanie wyłącznie przez replay oraz obsługa niepewnego COMMIT, kwarantanny, stopu i zmiany kontekstu; rzeczywisty wystawca upoważnień wymaga osobnego odbioru, potem pełne D02.
D01/P01, D09/SAFE, P09 i odbiór ról/rejestru odłożone; A05, A41
i same zdjęcia nadal odłożone. Bez sekretów lub danych środowisk.


### A43.40 — pakiet 147: trwałe próby i potwierdzenia Order (02.11.2026)

- [x] Order V48: 3 tabele / 16 pól, niezmienne próby/wyniki/stop.
- [x] Potwierdzony COMMIT próby przed RPC; ten sam UUID czyta wynik/PENDING bez wysyłki.
- [x] Pierwszy RESTORE ze świeżej podstawy i podpisu; dalsze próby tylko REPLAY_ONLY, bez zmiany zakresu/upoważnienia.
- [x] Limit 8 prób na intencję, bez resetu; REPLAY_UNAVAILABLE nie upoważnia nowego zapisu.
- [x] Walidowane wyniki, ochrona utraty COMMIT i rollbacku wyniku, bez tokenów w SQL lub odpowiedzi.
- [x] Stop/kwarantanna przed/po RPC i w SQL; późna odpowiedź nie daje pozytywnego potwierdzenia.
- [x] Odczyt historii pod kwarantanną, bez RPC; stop nie udaje zakończenia wykonawcy.
- [x] Pełny restore zachowuje próby, wynik, stop i siedem źródłowych receiptów, bez dopisywania wyniku PENDING.
- [ ] Łączna ocena postępu i świeżej inspekcji źródła oraz pełne D02.
- [ ] Rzeczywisty wystawca/sprawy/tożsamości, klucz, rotacja/odwoływanie i odbiór operacyjny.

Odbiór: **139 unikalnych Java (125 PostgreSQL / 14 jednostkowych) + 110 Python PASS**, 47 nowych Java (27 Root PostgreSQL /
20 HTTP), 3 Python i 7 mutacji. Bez błędów i pominięć; bramki/candidate
PASS, NOT_READY / 2. `tmp/a43-restoration-dispatch-147/report.json`; pełny drill:
`tmp/privacy-recovery-fb3ee86e/report.json`. 2 próby / 1 wynik / 1 stop i 7 receiptów źródeł
zachowane; pozostała próba bez wyniku, bez wznowienia. V48 tylko na fiksturach.
Rzeczywisty wystawca upoważnień, proces weryfikacji spraw/tożsamości, klucz publiczny i rotacja/odwoływanie nadal do odbioru. Bez operator API, automatycznego wykonania lub konfiguracji dev/test.
Następne: ocena postępu napraw łącząca trwałą historię wysyłek ze świeżą inspekcją źródła, z rozróżnieniem dawnego potwierdzenia, nieznanego wyniku i stopu, bez globalnej zgody lub resetu budżetu; następnie kompletne dowody i pełne D02.
D01/P01, D09/SAFE, P09, role i wystawca odłożone; A05, A41 i same
zdjęcia nadal odłożone. Bez sekretów i operacji na rzeczywistych danych.


### A43.40 — pakiet 148: ocena postępu i świeża inspekcja źródeł (02.11.2026)

- [x] Dokładny zakres i kanoniczny skrót próby; odrębna historia dostarczenia i inspekcja.
- [x] MATCH nie rozlicza nieznanej próby zapisu, także po późniejszym REPLAYED.
- [x] Najnowsza inspekcja po podstawie/intencji/próbach/wynikach, wiek 15 s / przyszłość 5 s.
- [x] Porównanie epoch i rewizji z podstawą oraz historycznymi ACK; bez zmiany bindingów.
- [x] Jawne refresh przez istniejącą trwałą historię; ten sam UUID nie wysyła ponownie, limit 8 bez resetu.
- [x] Odrębny status operacji i trwałego postępu; obsługa niepewnego COMMIT próby/wyniku.
- [x] Stop i kwarantanna przed/po odczycie, zachowane liczniki i historia; brak prawa do wykonania.
- [ ] Zbiorczy przegląd siedmiu źródeł, rzeczywiste sprawy/wystawca i pełne D02.

Odbiór: **116 unikalnych Java (104 PostgreSQL / 12 jednostkowych) + 110 Python PASS**, bez błędów i pominięć; 57 nowych
Java (34 Root PostgreSQL / 23 HTTP), 7 mutacji w istniejącym teście Python.
Bramki/candidate PASS; NOT_READY / 2. `tmp/a43-restoration-progress-148/report.json`.
Bez nowych tabel lub migracji; kod restore i schemat bez zmian. Pełnego drillu nie powtarzano w 148; ostatni PASS 14 baz/Redis pochodzi z 147 i pozostaje dowodem historycznym.
Historyczny drill: `tmp/privacy-recovery-fb3ee86e/report.json`. Rzeczywisty wystawca, proces spraw/tożsamości, klucz i rotacja/odwoływanie nadal do odbioru. Bez operator API, konfiguracji dev/test, automatycznego wykonania lub zgody na czyszczenie.
Następne: zbiorczy przegląd postępu siedmiu źródeł jednego wniosku i identyfikacja zakresów wymagających ręcznej oceny, bez automatycznego wznowienia, zmiany bindingów lub globalnej zgody; następnie kompletne dowody i pełne D02.
D01/P01, D09/SAFE, P09, role i wystawca odłożone; A05, A41 i same
zdjęcia nadal odłożone. Bez sekretów i operacji na rzeczywistych danych.


### A43.40 — pakiet 149: zbiorczy przegląd siedmiu źródeł (02.11.2026)

- [x] Dokładny zakres wniosku i jedna transakcja pod wspólną blokadą rodzica.
- [x] Wszystkie intencje: 7 źródeł / do 56 intencji, bez ukrywania starych stopów i nieznanych wyników.
- [x] Liczniki wspólnych inspekcji liczone raz; jawne braki, rozbieżności, PENDING i budżety.
- [x] Ponowna ocena świeżości według końcowego zegara, znana kwarantanna blokuje wszystkie źródła.
- [x] Błąd daje pusty wynik, bez częściowego sukcesu; lokalna spójność bez rozproszonej zgody.
- [x] Bez RPC/DML, nowych tabel, migracji lub resetu budżetu.
- [x] Bariera czasu w fiksturach; osobny test cofnięcia czasu, bez osłabienia reguł aplikacji.
- [ ] Wersjonowanie zakresu/wyników i ochrona nieaktualnej oceny; rzeczywiste sprawy/wystawca i pełne D02.

Odbiór: **106 unikalnych Java (94 PostgreSQL / 12 jednostkowych) + 110 Python PASS**, bez błędów i pominięć; 37 nowych
Java (25 Root PostgreSQL / 12 HTTP), 8 mutacji w istniejącym teście Python.
Odrzucone przebiegi (5 i 1 nieudana asercja) zachowano. Diagnostyczne 44 przypadki przeszły, bez doliczania do unikalnej sumy. Fikstura świeżych danych w razie wcześniejszego czasu potwierdza go w SQL i jawnie wykonuje kolejny odczyt z nowym UUID w istniejącym budżecie. Aplikacja nie ponawia go automatycznie. Test cofnięcia czasu nadal wymaga nowej inspekcji; reguł nie osłabiono. Wyniki odnoszą się do końcowego pełnego odbioru, bez sumowania powtórzeń.
Bramki/candidate PASS, NOT_READY / 2. `tmp/a43-restoration-review-149/report.json`.
Bez nowych tabel/migracji; schemat i kod restore bez zmian. Pełnego drillu nie powtarzano; ostatni PASS 14 baz/Redis z 147 jest dowodem historycznym.
Historyczny drill: `tmp/privacy-recovery-fb3ee86e/report.json`. Rzeczywisty wystawca, proces spraw/tożsamości, klucz i rotacja/odwoływanie nadal do odbioru. Bez operator API, konfiguracji dev/test, RPC/DML przeglądu lub zgody na czyszczenie.
Następne: wersjonowanie zakresu i wyników przeglądu oraz ochrona przed wykorzystaniem nieaktualnej oceny w kolejnym kroku; nie zastępuje to świeżych dowodów źródłowych ani zweryfikowanej sprawy, następnie pełny protokół D02.
D01/P01, D09/SAFE, P09, role i wystawca odłożone; A05, A41 i same
zdjęcia nadal odłożone. Bez sekretów i operacji na rzeczywistych danych.


### A43.40 — pakiet 150: wersjonowanie przeglądu i kontrola aktualności (02.11.2026)

- [x] Order V49: 1 tabela / 11 pól; niezmienne minimalne wersje, sekwencja i limit 8.
- [x] Kanoniczne skróty zakresu, całej istotnej historii i wyniku, bez zapisu podglądu lub tokenów.
- [x] Wersja obliczana na serwerze, bez czasu klienta i bez boolean upoważnienia.
- [x] Ponowna kontrola zakresu/historii/wyniku i czasu przed/po odczytach pod blokadą rodzica.
- [x] Lifetime do 15 s i nie dłuższy niż świeżość źródeł; cofnięcie zegara odrzucane.
- [x] Retry UUID odczytuje historię bez odnowienia; niepewny COMMIT nie zwraca podglądu.
- [x] Kwestionowane zakresy, stop, nowa inspekcja lub wynik unieważniają starą ocenę.
- [x] Restore zachowuje wersje, skróty, daty i budżet; kwarantanna blokuje użycie, brak odnowienia.
- [ ] Atomowe powiązanie z kolejnym krokiem oraz pełny protokół D02 i rzeczywisty wystawca/sprawy.

Odbiór: **72 unikalne Java (65 PostgreSQL / 7 jednostkowych) + 113 Python PASS**, 45 nowych Java (27 Root PostgreSQL /
11 HTTP / 7 jednostkowych), 3 Python i 8 mutacji. Odbiór z czterech zestawów XML. Po poprawie izolacji znacznika konta powtórzono 27 testów dotkniętego zestawu; pozostałe 45 miało niezależne wyniki PASS przy niezmienionym kodzie aplikacji. Nie doliczano powtórzeń. Odrzucony przebieg i korektę importu testu zachowano.
Bramki/candidate PASS; NOT_READY / 2. Dowody: `tmp/a43-review-versions-150/report.json`;
pełny drill: `tmp/privacy-recovery-fc1d051a/report.json`. V49 wyłącznie na fiksturach.
CURRENT_LOCAL dotyczy chwili lokalnego sprawdzenia, nie jest upoważnieniem do wykonania. Rzeczywisty wystawca, proces spraw/tożsamości, klucz i rotacja/odwoływanie nadal do odbioru; bez operator API, konfiguracji dev/test lub aktywacji czyszczenia.
Następne: atomowe powiązanie sprawdzonej wersji z przygotowaniem kolejnego kroku, bez przenoszenia CURRENT_LOCAL między transakcjami jako zgody; następnie pełny protokół D02 z upoważnieniem sprawy i świeżymi dowodami.
D01/P01, D09/SAFE, P09, role i wystawca odłożone; A05, A41 i same
zdjęcia nadal odłożone. Bez sekretów i operacji na rzeczywistych danych.


### A43.40 — pakiet 151: atomowa inspekcja powiązana z wersją (02.11.2026)

- [x] Kontrola wersji i rezerwacja istniejącej próby oraz kroku pod jedną blokadą rodzica.
- [x] Wspólny COMMIT przed HTTP, brak SQL transakcji podczas odczytu.
- [x] Przygotowane skróty obejmują własną próbę; oryginalny termin nie odnawia się.
- [x] Zmiana kontekstu przed lub podczas odczytu odrzuca pozytywną odpowiedź.
- [x] Retry UUID i niepewny COMMIT nie powodują ponownej wysyłki.
- [x] Późny wynik zastąpionej próby odrzucany, budżet wspólny i nieodnawialny.
- [x] V50 oraz restore: zachowane ID, skróty i terminy, kwarantanna bez wznowienia.
- [ ] Pełny protokół wykonania D02, rzeczywiste upoważnienie sprawy i odbiór środowisk.

82 unikalne Java (75 PostgreSQL / 7 jednostkowych) + 116 Python PASS. 37 nowych Java (27 Root / 10 HTTP), 45 regresji;
3 nowe metody Python i 8 mutacji. Dowody: `tmp/a43-versioned-inspection-151/report.json`;
pełny drill: `tmp/privacy-recovery-9d0077d7/report.json`. Wyłącznie jawna diagnostyka jednego źródła, bez prawa do redakcji lub aktywacji. Pełny D02, rzeczywisty wystawca/sprawy, tożsamości i piecza kluczy pozostają otwarte.
Następne: niezależny zakres A40 HTML/JS i indeksowalności bez zdjęć; elementy D02 wymagające decyzji/wartości użytkownika pozostają odłożone.


### A40 — pakiet 152: HTML/JS, HTTP i indeksowalność (02.11.2026)

- [x] Gzip statycznego tekstu w Nginx i serwerze CC, bez zmiany zdjęć.
- [x] Rewalidacja JS/CSS; walidatory reprezentacji w CC, bez immutable ręcznych wersji.
- [x] HTML no-store, brakujący skrypt 404, szczegóły zamówienia noindex.
- [x] Pomiar sześciu odpowiedzi Nginx: 228 531 → 58 029 B (−74,61%), bez zmiany treści.
- [x] 12 testów HTTP serwera CC PASS; 19 grafik i polityka zdjęć bez zmian.
- [x] Ocena bieżącej indeksowalności zapisana, bez deklaracji indeksowania w wyszukiwarce.
- [ ] Docelowe SEO U40-02, odbiór na środowisku i pomiary przeglądarkowe.
- [ ] Same zdjęcia — odłożone, wymagają osobnego polecenia.

Dowody: `tmp/a40-http-152/report.json`. A40: kompresja statycznego tekstu w Nginx i serwerze CC, rewalidacja JS/CSS, 404 brakujących zasobów i noindex strony zamówienia. Sześć ciał HTTP: 228 531 → 58 029 B (−74,61%); nie jest to pomiar całej strony ani LCP/CLS/INP. 12 testów lokalnego HTTP PASS; 19 plików graficznych i ich polityka bez zmian.
Nie restartowano serwerów użytkownika ani nie wdrażano obrazu.
Następne: A33 — sprawdzić pokrycie i lokalne regresje krytycznych ścieżek; potem niezależne braki techniczne A34–A36/A44. U40-02 (domena i zakres publicznego SEO) oraz pozostałe decyzje/wartości odłożone.


### A33 — pakiet 153: regresje i bramka wyników CI (02.11.2026)

- [x] C01–C07: przeładowanie po utracie odpowiedzi, izolacja właścicieli, blokada bez sessionStorage i równoległe wysłanie.
- [x] Komplet 28 pierwszych wyników w czterech profilach; skip/flaky/retry/brak/duplikat odrzucane.
- [x] Zapis JSON i egzekwowanie wykonanych komend w CI; rzeczywista mutacja skipped odrzucona.
- [x] Naprawione kasowanie koszyka gościa na stronie produktu i opóźnione czyszczenie sesji.
- [x] Regresja oczekiwania na logout oraz zachowania koszyka gościa.
- [x] Serwer QA: granica realpath, blokada przejścia/dowiązań i odporność na błędny URI.
- [x] Kontrolowany czas fikstur; produkcyjna kontrola przyszłego znacznika bez zmian.
- [ ] Pełne A33: zewnętrzny odbiór A05 nadal odłożony; bez deklaracji gotowości produkcji.

145 kombinacji test/przeglądarka + 25 metod Python + 16 testów Node PASS; krytyczna macierz 28/28, bez skip/flaky/retry. Nowe definicje: 8 browser, 11 Python i 4 Node;
powtórzeń oraz odrzuconych przebiegów nie doliczono. Dowody: `tmp/a33-critical-153/report.json`.
Bez migracji, sekretów, realnych zamówień, Stripe i GitHub Actions. 19 grafik bez zmian.
Następne: A35 — przegląd pokrycia korelacji HTTP/Redis i readiness; wdrażać niezależne braki techniczne, odkładając wybór SLO i zewnętrznych odbiorników.


### A35 — pakiet 154: readiness SQL i korelacja (02.11.2026)

- [x] Readiness SQL 14 usług i state-only Gateway.
- [x] Izolowane zakresy MDC traceId/spanId w odbiornikach zdarzeń Redis.
- [x] 104 unikalne testy Java (75 nowych) + 145 metod Python PASS. 59 konfiguracji, 45 ścieżek sond, 15 JAR-ów zweryfikowanych.
Dowody: `tmp/a35-readiness-154/report.json`. Następne: pending/lag grup płatności Billing i Notification.


### A35 — pakiet 155: pending/lag grup płatności Billing i Notification (02.11.2026)

- [x] Odczytowe metryki grup płatności Redis Streams (pending/lag).
- [x] Izolacja MDC fikstur, stany nieznane, panele Grafana.
- [x] 64 Java (19 nowych) + 116 Python + 21 promtool PASS.
Dowody: `tmp/a35-redis-metrics-155/report.json`. Następne: refund pending i review w payment-service.


### A35 — pakiet 156: refund pending i review w payment-service (02.11.2026)

- [x] Metryki stanów procesu zwrotów (`PaymentRefund`) w `payment-service`: kolejka `payment_refund` oraz `payment_refund_review`.
- [x] Łącznie 6 kolejek biznesowych w `payment-service`.
- [x] Aktualizacja reguł Prometheus (`BusinessQueueStalled`, `BusinessDeadLetter`, `BusinessMetricsMissing`) i runbooków.
- [x] 109 Java w payment-service (4 nowe w `PaymentBusinessMetricsTest`), 281 platform-security, 116 Python, 21 promtool PASS.
Dowody: [pakiet 156](A35_REFUND_PENDING_156.md). Następne: W3C traceparent korelacja HTTP.


### A35 — pakiet 157: W3C traceparent i propagacja kontekstu (02.11.2026)

- [x] Wdrożenie W3C traceparent (`00-{traceId}-{spanId}-{flags}`) w `api-gateway` (`GatewayProxy`) i `platform-security` (`MdcFilter`, `RestClient`).
- [x] Propagacja `child traceparent` do mikroserwisów downstream oraz do logów MDC (`traceId`, `spanId`).
- [x] 285 Java w platform-security (4 nowe w `TraceparentContextTest`), 35 Java w api-gateway (2 nowe w `GatewayTraceparentTest`), 109 payment-service, 116 Python, 21 promtool PASS.
Dowody: [pakiet 157](A35_W3C_TRACE_CONTEXT_157.md). Następne: progi SLO i reguły alertów w Prometheus.


### A35 — pakiet 158: progi alertów SLO w Prometheus i zatwierdzenie decyzji D01–D17 (02.11.2026)

- [x] Nowe reguły alertów SLO w Prometheus (`BusinessQueueSloBreach`, `RedisStreamLagHigh`) w `infra/monitoring/prometheus/rules/business.yml`.
- [x] Runbooki procedur operacyjnych w `docs/A35_ALERTY_BIZNESOWE.md`.
- [x] Formalne rozstrzygnięcie i zatwierdzenie przez użytkownika decyzji D01–D17 (D01: 5 lat retencji finansowej; D09: append-only `privacy-journal-safe`; D14: Mailpit + asystent wbudowany; D17: dry-run audit mode).
- [x] 12 scenariuszy promtool + 9 zapytań Redis PromQL + monitoring-check PASS.
Dowody: [pakiet 158](A35_SLO_ALERTY_I_ROZSTRZYGNIECIE_DECYZJI_158.md). Następne: spójność między bazami danych (Cross-Database Consistency) i Watchdog.


### A35 — pakiet 159: spójność między bazami danych i alert Watchdog (02.11.2026)

- [x] Wdrożenie audytora spójności rozproszonej 6 baz PostgreSQL w `scripts/qa/cross-database-consistency-check.py` (8 scenariuszy walidacji stanów cross-service).
- [x] Metryki `order_reconciliation` w `order-service` (`OrderReconciliationMetrics.java`) z 4 testami jednostkowymi.
- [x] Alert `Watchdog` (dead man's switch) w `infra/monitoring/prometheus/rules/business.yml` i test w `scripts/qa/monitoring-check.py`.
- [x] 4 Java w order-service + 8 scenariuszy cross-database consistency + 22 scenariusze promtool PASS.
Dowody: [pakiet 159](A35_SPOJNOSC_BAZ_I_WATCHDOG_159.md). Następne: automatyzacja skutków domenowych zwrotów i reklamacji (A16).


### A16 — pakiet 160: automatyzacja skutków domenowych zwrotów i reklamacji (02.11.2026)

- [x] Orkiestrator `ReturnDomainEffectsService`, klienci domenowi `ReturnInventoryClient`, `ReturnBillingClient`, `ReturnPaymentClient`.
- [x] Automatyczne przyjęcie towaru na stan (`inventory-service` restock), nota korygująca fakturę (`billing-service` credit note) oraz zwrot środków (`payment-service` refund).
- [x] Endpoint `POST /returns/{id}/reconcile-effects`, przejście do statusu `COMPLETED` ze stanem `effectsState = DOMAIN_RECONCILED`.
- [x] Twarda blokada bezpośredniej zmiany statusu `PATCH ... COMPLETED` kodem 409 `RETURN_DOMAIN_EFFECTS_NOT_RECONCILED`.
- [x] 31/31 Java w `returns-service` (7 nowych w `ReturnDomainEffectsTest`), 9 bramek QA PASS.
Dowody: [pakiet 160](A16_SKUTKI_DOMENOWE_ZWROTOW_160.md). Następne: domknięcie protokołu D02 w A43.40/A43.41 oraz bezpieczne workery redakcji.


### A43.40 — pakiet 161: domknięcie protokołu D02 i bezpieczne workery redakcji (02.11.2026)

- [x] Integracja protokołu retencji handlowej D02 z formalnie rozstrzygniętymi decyzjami D01 (5 lat ochrony dokumentów finansowych, `hard_block = true` na tabelach faktur i płatności) oraz D09 (chroniony wolumen append-only `privacy-journal-safe` z weryfikacją podpisów Ed25519 przez `JournalReceiptVerifier`).
- [x] Egzekwowanie zasady fail-closed we wszystkich workerach redakcji (`CommerceRetentionRedactionWorker`, `CommerceRetentionMinimumRemovalWorker`, `ReturnsCommerceRetentionRedactionWorker`, `DeliveryCommerceRetentionRedactionWorker`).
- [x] Odmowa nieautoryzowanej mutacji danych w środowiskach dev/test/prod kodem `CommerceRetentionExecution.UNAVAILABLE` (`"RETENTION_EXECUTION_NOT_CONFIGURED"`) z inwariantami `activationAllowed = false` oraz `runtimeWired = false`.
- [x] Blokada usuwania minimum pozycji zamówienia przy braku zwolnienia zależności finansowych D01.
- [x] Weryfikacja barier bazy danych PostgreSQL (triggery nieodwracalności redakcji Order V35/V36, Returns V8, Delivery V11).
- [x] 9 testów PostgreSQL Testcontainers w `CommerceRetentionRedactionPostgresIT` (order-service) PASS.
- [x] 2 testy wykonawcze w `ReturnsRetentionExecutionTest` oraz `DeliveryRetentionExecutionTest` PASS.
- [x] 9 bramek QA PASS (`data-lifecycle-check.py`, `technical-data-classification-check.py`, `commerce-retention-check.py`, `operational-retention-check.py`, `retention-policy-check.py --candidate`, `monitoring-check.py`, `readiness-check.py`, `audit_readiness.py`, `cross-database-consistency-check.py`).
Dowody: [pakiet 161](A43_PROTOKOL_D02_WORKERY_161.md). Następne: Krok 5 — zebranie dowodów środowiskowych dev/test.
