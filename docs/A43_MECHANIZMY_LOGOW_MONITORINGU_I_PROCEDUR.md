# A43 — mechanizmy retencji logów, monitoringu, Mailpit i procedur ręcznych

> **Korekta odbioru 31.10.2026:** [weryfikacja kodu R01–R03/R08](A43_WERYFIKACJA_KODU_I_PRZEKAZANIA_2026-10-31.md)
> wykazała nieskuteczną domyślną blokadę P09 i braki integracji. Poniższy
> opis zachowuje deklaracje pakietu 122; nie potwierdza ich pełnego wykonania.
> Krok 4 wymaga poprawek i ponownego odbioru, bez aktywacji.

Stan na **31.10.2026**, branch `Audit_0.1`, Pakiet 122 (A43.40 Krok 4).
Dokumentacja techniczna wdrożenia mechanizmów retencji i procedur ręcznych
powiązanych z rzeczywistymi magazynami, blokadami zachowania i regułami ról.
Wszystkie operacje pozostają w stanie **fail-closed** zgodnie z D17 i P09
(`activationAllowed = false`, `runtimeWired = false`).

---

## 1. Zakres i cel wdrożenia

Krok 4 realizuje wymagania określone w rozdziale 5 `docs/PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md`:
> *„Dokończyć mechanizmy logów, monitoringu, Mailpit i procedur ręcznych, powiązane z rzeczywistymi magazynami i blokadami. Rozstrzygnięte wyjątki P09 i dowody skutków w odpowiednich magazynach; sama wartość w kontrakcie nie wystarcza.”*

Dotychczasowe pozycje D07, D08, D14, D15, D18 w kontraktach JSON opierały się na deklaracjach.
Pakiet 122 wdraża rzeczywiste klasy wykonawcze, integrację parametrów magazynów
oraz testy dowodowe weryfikujące faktyczne zachowanie magazynów, blokady i prawa ról.

---

## 2. Architektura i wdrożone komponenty

### 2.1 Strażnik wyjątków i blokad zachowania P09 (`OperationalRetentionGuard`)
- **Lokalizacja:** [`platform-security/.../privacy/OperationalRetentionGuard.java`](../platform-security/src/main/java/org/demo/platformsecurity/privacy/OperationalRetentionGuard.java).
- **Zasada działania:**
  - Reprezentuje trwały rejestr blokad zachowania (`PreservationHold`) w 4 zakresach: `LOGS`, `MONITORING`, `MAILPIT`, `CASES`.
  - Ścisłe **fail-closed**: w stanie P09=C (wyjątki formalnie nierozstrzygnięte przez użytkownika) jakiekolwiek usunięcie bez dowodu braku blokad jest blokowane (`isCleanupBlocked = true`).
  - Obsługuje blokady precyzyjne (po ścieżce/identyfikatorze) oraz wzorce (prefix `case-disputed-*`).
  - Zwolnienie blokady (`releaseHold`) wymaga podania tożsamości uprawnionego operatora i znacznika czasu.

### 2.2 Retencja logów operacyjnych D07 (`OperationalLogRetention`)
- **Lokalizacja:** [`platform-security/.../privacy/OperationalLogRetention.java`](../platform-security/src/main/java/org/demo/platformsecurity/privacy/OperationalLogRetention.java).
- **Reguły i powiązanie z magazynem:**
  - **Wiek:** maksymalnie 30 dni od faktycznego zapisu wpisu (`entryWrittenAt`, nie od rotacji lub restartu). Pliki starsze niż 30 dni podlegają wycofaniu (`PRUNED_BY_AGE`).
  - **Rozmiar:** limit 100 MiB (104 857 600 B) na źródło wraz z rotacjami. Przekroczenie limitu powoduje bezpieczne usunięcie najstarszych zrotowanych segmentów (`PRUNED_BY_SIZE`), realizując regułę `sizeMayRemoveOldestEarlier = true`.
  - **Wyłączenia i ochrona:**
    - Pliki legacy D10 są trwale wyłączone z automatycznego czyszczenia (`PROTECTED_LEGACY`).
    - Certyfikowane logi zadań CC podlegają wyłącznie dedykowanemu protokołowi A43.8 (`PROTECTED_CERTIFIED`).
    - Aktywne blokady P09 bezwzględnie wstrzymują czyszczenie danego źródła lub pliku (`BLOCKED_BY_HOLD`).

### 2.3 Retencja monitoringu D08 (`MonitoringRetentionPolicy` & Docker Compose)
- **Lokalizacja:** [`platform-security/.../privacy/MonitoringRetentionPolicy.java`](../platform-security/src/main/java/org/demo/platformsecurity/privacy/MonitoringRetentionPolicy.java) oraz [`docker-compose.monitoring.yaml`](../docker-compose.monitoring.yaml).
- **Reguły i konfiguracja:**
  - **Prometheus TSDB:** parametr `--storage.tsdb.retention.time=30d` skonfigurowany w `docker-compose.monitoring.yaml`.
  - **Próg budżetowy:** alarm rozmiaru przy 1 GiB (1 073 741 824 B). Zgodnie z decyzją użytkownika: `sizeMayRemoveEarlier = false` (przekroczenie rozmiaru wyzwala alarm, lecz NIE usuwa danych przed upływem 30 dni).
  - **Alertmanager:** parametr `--data.retention=120h` (5 dni) skonfigurowany w `docker-compose.monitoring.yaml` dla wygasłych uciszeń i logu powiadomień.
  - **Grafana (wycofane dashboardy):** retencja 90 dni od potwierdzonego wycofania (`retiredAt + 90 days`). Wycofanie blokują aktywne zależności (np. aktywne alerty powiązane z metrykami) oraz blokady dochodzeniowe P09.

### 2.4 Retencja poczty Mailpit D14 (`MailpitRetentionManager` & Compose)
- **Lokalizacja:** [`platform-security/.../privacy/MailpitRetentionManager.java`](../platform-security/src/main/java/org/demo/platformsecurity/privacy/MailpitRetentionManager.java) oraz [`compose.yaml`](../compose.yaml).
- **Reguły i konfiguracja:**
  - **Pojemność:** parametr `MP_MAX_MESSAGES: 1000` w kontenerze `mailpit` w `compose.yaml`.
  - **Wiek:** maksymalnie 30 dni od przyjęcia wiadomości (`receivedAt + 30 days`).
  - **Skutek przekroczenia pojemności:** przekroczenie 1000 wiadomości usuwa najstarsze wiadomości wraz z załącznikami (`countMayRemoveOldestAndAttachmentsEarlier = true`).
  - **Ochrona spraw:** wiadomości powiązane ze sprawą (`isCaseEvidence = true`) lub objęte blokadą P09 są bezwzględnie chronione przed usunięciem (nawet przy przekroczeniu limitu 1000 wiadomości).

### 2.5 Procedury ręczne, role i zatrzymanie workera D15/D18 (`ManualCaseProcedureService`)
- **Lokalizacja:** [`platform-security/.../privacy/ManualCaseProcedureService.java`](../platform-security/src/main/java/org/demo/platformsecurity/privacy/ManualCaseProcedureService.java).
- **Reguły ról i odpowiedzialności:**
  - Właściciel: `operator-1`.
  - Zastępca: `operator-2`.
  - Zwykła obsługa i zatwierdzanie jednoznacznych wniosków: dostępne dla obu ról (`operator-1` i `operator-2`).
  - **Wyłączność oceny niejednoznacznych spraw (P08/D15):** dodatkową ocenę spraw wątpliwych (np. dowody czatu bez tokenu, dane innych osób, sprzeczne wnioski) może podjąć **wyłącznie właściciel (`operator-1`)**. Próba rozstrzygnięcia przez `operator-2` lub automat kończy się rzuceniem `SecurityException`, a sprawa pozostaje nierozstrzygnięta (`UNRESOLVED` / `UNDER_AMBIGUOUS_REVIEW`).
- **Zegary retencji spraw:**
  - Dowody i załączniki: 90 dni od bezpiecznego zamknięcia sprawy (`closedAt + 90 days`).
  - Minimalny rekord sprawy: 365 dni od zamknięcia sprawy (`closedAt + 365 days`).
  - Sprawy w toku (`OPEN`, `UNDER_AMBIGUOUS_REVIEW`, `DISPUTED`) trwale blokują czyszczenie.
- **Procedura natychmiastowego zatrzymania workera (Scoped Worker Stop, D18):**
  - Autoryzowana dla obu ról (`operator-1` i `operator-2`).
  - Umożliwia selektywne zatrzymanie konkretnego workera retencji (np. `COMMERCE_REDACTION`, `MINIMUM_REMOVAL`, `LOG_RETENTION`, `MAILPIT_RETENTION`).
  - Generuje audytowany rekord zatrzymania (`WorkerStopReceipt`) z podpisem operatora, powodem i znacznikiem czasu.

---

## 3. Weryfikacja i odbiór testowy

### 3.1 Testy jednostkowe i integracyjne Java
- **Plik testowy:** [`platform-security/.../privacy/OperationalRetentionAndManualProceduresTest.java`](../platform-security/src/test/java/org/demo/platformsecurity/privacy/OperationalRetentionAndManualProceduresTest.java).
- **Wynik:** **14/14 testów PASS** (łączna liczba testów `platform-security` wzrosła do **209 PASS**, 0 błędów):
  - `defaultGuardIsFailClosed` (PASS)
  - `registeredHoldBlocksCleanupUntilExplicitlyReleased` (PASS)
  - `wildcardPatternMatchesMatchingTargets` (PASS)
  - `prunesLogsOlderThan30DaysFromEntryWrite` (PASS)
  - `prunesOldestRotatedSegmentsWhenTotalSizeExceeds100MiB` (PASS)
  - `protectsD10LegacyAndCertifiedJobLogsAndHeldFiles` (PASS)
  - `verifiesPrometheusAndAlertmanagerParameters` (PASS)
  - `evaluatesRetiredGrafanaDashboardsRetention` (PASS)
  - `prunesMessagesOlderThan30DaysAndKeepsHeldEvidence` (PASS)
  - `prunesOldestMessagesWhenExceeding1000MessagesCapacity` (PASS)
  - `ambiguousCaseReviewRequiresPrimaryOwnerOperator1` (PASS)
  - `evaluatesCaseRetention90DaysEvidenceAnd365DaysMinimalRecord` (PASS)
  - `activeOrDisputedCasesBlockRetentionPruning` (PASS)
  - `scopedWorkerStopExecutedByBothOwnerAndDeputyWithAuditReceipt` (PASS)

### 3.2 Sprawdzian kontraktowy i konfiguracyjny QA
- **Skrypt:** [`scripts/qa/operational-retention-check.py`](../scripts/qa/operational-retention-check.py).
- **Wynik:** **PASS** (kod 0):
  - `p09FailClosedEnforced: true`
  - `monitoringStorageVerified: {prometheusTsdbRetention: 30d, alertmanagerRetention: 120h, storageBudgetAlarmBytes: 1073741824, sizeMayRemoveEarlier: false}`
  - `mailpitStorageVerified: {maxMessagesPerInstance: 1000, maxAgeDays: 30, attachmentsPrunedWithMessages: true}`
  - `logStorageVerified: {maxAgeDays: 30, maxBytesPerSource: 104857600, legacyD10Protected: true, certifiedJobLogsProtected: true}`
  - `manualProceduresVerified: {owner: operator-1, deputy: operator-2, ambiguousCaseReviewExclusiveToOwner: true, evidenceRetentionDays: 90, minimalRecordRetentionDays: 365, scopedWorkerStopSupported: true}`

### 3.3 Bramki kontraktowe A43.40
- `python3 scripts/qa/data-lifecycle-check.py`: **PASS** (106 tabel, 1017 kolumn, 188 migracji, 47 mechanizmów).
- `python3 scripts/qa/technical-data-classification-check.py`: **PASS** (106 tabel, 1017 kolumn, 93 pominięcia).
- `python3 scripts/qa/commerce-retention-check.py`: **PASS** (`mode: assessment-only`, `activationAllowed: false`).
- `python3 scripts/qa/retention-policy-check.py --candidate scripts/qa/contracts/retention-observe-example.json`: **PASS** (kod 0, `applied: false`).
- `python3 scripts/qa/retention-policy-check.py --require-ready`: **NOT_READY** (kod 2 / NOT_READY, zgodnie z kontraktem).
- `python3 scripts/qa/monitoring-check.py`: **PASS** (wszystkie 10 reguł biznesowych i promtool/amtool checks).
- `python3 scripts/qa/internal-copies-check.py`: **PASS** (11 zasobów, brak nieuzgodnionych loggerów).

---

## 4. Podsumowanie niezmienników

| Niezmiennik | Stan po Pakiecie 122 |
|---|---|
| Aktywacja w runtime (`activationAllowed`, `runtimeWired`) | Bezwzględnie `false` |
| Liczba tabel i kolumn | Ściśle 106 tabel, 1017 kolumn, 188 migracji |
| Ochrona dokumentów finansowych D01 | W pełni zachowana |
| Granice ról (D15/D18) | Sprawy niejednoznaczne: wyłącznie `operator-1`; alarmy i stop: `operator-1` i `operator-2` |
| Zasada fail-closed P09 | Wyjątki nieustalone trwale blokują automatyczne usuwanie |
