# A43.40 Krok 5 — Dowody środowiskowe dev/test i domknięcie obszarów aktywnych B, pakiet 163

Stan: **03.11.2026 · Audit_0.1 · Pakiet 163 · wdrożenie Kroku 5 A43.40 oraz techniczne domknięcie 8 aktywnych obszarów (9 pozycji)**.  
Punkt wznowienia: [Dokument przekazania](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md) oraz [rejestr postępu](AUDIT_0.1_POSTEP.md).

---

## 1. Zakres pakietu 163

Zgodnie z poleceniem użytkownika zrealizowano prace dla aktywnych obszarów z sekcji B (8 obszarów / 9 pozycji, z pominięciem świadomie odłożonych A05 i A41):

1. **A16 — Orkiestracja skutków domenowych zwrotów (`returns-service`)**:
   - Zweryfikowano działanie orkiestracji w `returns-service` po naprawach `R01`–`R04` z pakietu 162.
   - Wszystkie 35 testów modułu `returns-service` (`mvn -pl returns-service test`) zakończyły się sukcesem (**35/35 PASS**).
   - Potwierdzono deterministyczne klucze idempotencji (`return-restock-v1:...`, `return-corr-v1-...`, `return-ref-v1-...`), blokadę współbieżnych spraw w pamięci instancji (`inFlightCases`), ochronę salda faktur (`expectedVersion`), blokadę automatycznego rozliczania zgłoszeń typu `COMPLAINT` oraz wymóg rozliczenia wyłącznie potwierdzonych sukcesów refundacji Stripe (`SUCCEEDED`).

2. **A33 — Macierz krytycznych regresji przeglądarkowych i odzyskiwanie UI**:
   - Zweryfikowano działanie bramki `scripts/qa/critical-browser-check.py` w teście jednostkowym `scripts/qa/unit/test_critical_browser.py` (**8/8 PASS**).
   - Potwierdzono wymóg 28 unikalnych par (7 scenariuszy odzyskiwania × 4 przeglądarki) bez błędów, bez pominięć i bez ponowień (retry=0).

3. **A34 — Spójne szablony wdrożeniowe Kubernetes i walidacja admission**:
   - Zweryfikowano render Kustomize i ścisłą walidację schematów Kubernetes 1.35.0 (`scripts/deploy/validate-k8s.py`): **PASS** (16 deploymentów, 16 serwisów, 14 powiązań bazodanowych, 18 polityk sieciowych NetworkPolicy, 0 błędów walidacji schematu).

4. **A35 — Obserwowalność, alerty SLO, korelacja W3C i spójność między bazami**:
   - Zweryfikowano reguły alertów i heartbeat Watchdog (`scripts/qa/monitoring-check.py` — **PASS**, 15 targetów, 13 scenariuszy promtool, 9 zapytań dashboardów Redis).
   - Zweryfikowano audytora spójności międzybazowej (`scripts/qa/cross-database-consistency-check.py` — **PASS**, 8 scenariuszy, 6 baz danych, inwarianty I1–I4).
   - Zweryfikowano zachowanie aktywnego kontekstu `traceId` w zagnieżdżonych wywołaniach `CorrelationIdContext.open(String)` oraz sondy gotowości SQL (`scripts/qa/readiness-check.py` — **PASS**, 15 JVM, 14 SQL, 59 konfiguracji, 45 sond K8s).

5. **A36 — Odtwarzanie po awarii (Disaster Recovery), manifesty Age i procedury**:
   - Zweryfikowano mechanizmy odzyskiwania platformy w `scripts/qa/unit/test_platform_recovery.py` (**15/15 PASS**).
   - Potwierdzono szyfrowanie Age, uwierzytelniony manifest `manifest.json.age`, izolację prób odtworzenia oraz regułę usuwania całych zestawów kopii (`observe` / 14 dni).

6. **A40 — Techniczne HTTP, kompresja i indeksowalność**:
   - Zweryfikowano kompresję gzip, rewalidację skryptów/styli oraz `X-Robots-Tag: noindex` dla `order-detail.html` w `scripts/qa/unit/test_frontend_http.py` (**12/12 PASS**).
   - Wdrożenie publicznej domeny i kanonicznego hosta (`U40-02`) oraz modyfikacje binarne zdjęć pozostają odłożone zgodnie z wytycznymi użytkownika.

7. **A43.40 (Krok 5) — Zebranie dowodów środowiskowych dev/test**:
   - Opracowano i uruchomiono audytor środowiskowy: [`scripts/qa/environmental-evidence-check.py`](../scripts/qa/environmental-evidence-check.py).
   - Utworzono test jednostkowy: [`scripts/qa/unit/test_environmental_evidence.py`](../scripts/qa/unit/test_environmental_evidence.py) (**5/5 PASS**).
   - Wygenerowano raport weryfikowalny maszynowo: `tmp/a43-step5-evidence/environment-evidence-report.json`.
   - Raport potwierdza 8 kategorii środowiskowych:
     1. Zegary i strefa czasowa (UTC we wszystkich serwisach i bazach, flaga `-Duser.timezone=UTC`, serializacja ISO-8601 UTC).
     2. Streamy Redis (`payment-events`, `invoice-events`, grupy `billing-service`, `notification-service`, `notification-service-invoices`, progi lag).
     3. Zestawy kopii zapasowych (14 baz + Redis, format Age, brak haseł w plikach zrzutów).
     4. Konfiguracja SMTP (lokalny Mailpit w dev:11025 i test:12025, brak zewnętrznej wysyłki, limit 1000 wiadomości / 30 dni).
     5. Asystent AI i ochrona egressu (wbudowany tryb CC, jednorazowe upoważnienia w RAM, brak tokenów zewnętrznych).
     6. Klucze, rotacja JWT i szyfrowanie MFA (AES-256-GCM, rotacja JWT przez `CUSTOMER_JWT_ACTIVE_KID`, brak ekspozycji wartości sekretów).
     7. Role operatorskie i zarządzanie (właściciel `operator-1`, zastępca `operator-2`, wyłączność `operator-1` w spornych sprawach D15).
     8. Piecza rejestru i sejf (`SAFE` folder / wolumen `privacy-journal-safe`, ścieżki dev/test, algorytm Ed25519).
   - Ścisła zasada bezpieczeństwa: `secretValuesRead: false`, `executedMutations: 0`, `readOnly: true`.

8. **A43.41 — Końcowa bramka gotowości retencji**:
   - Zachowano niezmiennik *fail-closed*: `retention-policy-check.py --require-ready` zwraca kod **2** (`NOT_READY`), `activationAllowed = false`, `runtimeWired = false`.

9. **A44.4 — Granice CI/CD i bramka obrazów PR**:
   - Zweryfikowano odcięcie uprawnień i brak publikacji obrazów w PR (`scripts/qa/ci-images-check.py` — **PASS**).

---

## 2. Podsumowanie wyników bramek QA

Wszystkie bramki jakościowe repozytorium zakończyły się statusem **PASS**:
- `data-lifecycle-check.py` — **PASS** (14 baz, 145 tabel, 232 migracje, 47 mechanizmów).
- `technical-data-classification-check.py` — **PASS** (145 tabel, 1307 kolumn, 232 migracje).
- `commerce-retention-check.py` — **PASS** (10 tabel D02, 127 kolumn, 8 uczestników).
- `operational-retention-check.py` — **PASS** (5 klas operacyjnych, fail-closed P09).
- `retention-policy-check.py --candidate ...` — **PASS** (20 parametrów).
- `retention-policy-check.py --require-ready` — **kod wyjścia 2 (NOT_READY)**.
- `monitoring-check.py` — **PASS** (15 targetów, 13 alertów w tym Watchdog + 9 dashboardów Redis).
- `cross-database-consistency-check.py` — **PASS** (8 scenariuszy, 6 baz).
- `readiness-check.py` — **PASS** (15 JVM, 14 SQL, 59 konfiguracji, 45 sond K8s).
- `audit_readiness.py` — **PASS** (13/14 P1 zakończonych, otwarte wyłącznie A05).
- `validate-k8s.py` — **PASS** (16 deploymentów, 16 serwisów, 14 baz, 18 network policies).
- `ci-images-check.py` — **PASS** (A44.1–A44.3 granice publikacji i brak sekretów w PR).
- `environmental-evidence-check.py` — **PASS** (Krok 5 A43.40, 8 kategorii dev/test zweryfikowanych read-only).
