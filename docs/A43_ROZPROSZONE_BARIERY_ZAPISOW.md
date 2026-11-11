# A43 — rozproszone bariery zapisów (Distributed Write Fence)

Stan 31.10.2026, Audit_0.1, pakiet 119, rewizja a43.40-2026-10-31.15.
Zakres: dwufazowy protokół bariery zapisów w usługach źródłowych dla D02;
ochrona przed spóźnionymi/równoległymi zapisami naruszającymi spójność
przed ewentualną mutacją; wygasanie dzierżawy i odzyskiwanie po awarii;
`activationAllowed=false`. A43.40/A43.41 nadal otwarte.

## Problem i motywacja

W pakiecie 118 runda ponownej weryfikacji (`revalidateAndAssess`) wprowadziła
sekwencyjny odczyt obserwacji z ośmiu źródeł. Test integracyjny
`sequentialRevalidationStillDoesNotFreezeAnAlreadyReadSource` wykazał jednak
kluczową lukę spójności:
zapis dokonany w usłudze źródłowej (np. Delivery) już **po** jej odczytaniu,
lecz **przed** zakończeniem całej rundy (np. podczas odczytu Billing),
nie był wykrywany ani blokowany przez sam sekwencyjny odczyt cache.

Przed dopuszczeniem jakichkolwiek operacji usuwania lub redakcji D02 konieczne
było wprowadzenie rzeczywistego mechanizmu barier zapisów (*write fence / freeze protocol*)
w usługach uczestniczących, powiązanego z generacją, wersją, fazą i rewizją dowodów.

## Protokół PeerRetentionFenceProtocol

Protokół `PeerRetentionFenceProtocol` implementuje dwufazową barierę zapisów
w architekturze rozproszonej:

```
OrderService (Koordynator)                     Peer Service (Źródło)
           |                                             |
           | ----- 1. PREPARE (acquire lease) ---------> | pg_advisory_xact_lock
           |                                             | Sprawdzenie rewizji/stanu
           | <---- FENCED (leaseToken, expiresAt) ------ | Zapis dzierżawy (15s)
           |                                             |
           | [Weryfikacja pozostałych uczestników]       | [Blokada nowych modyfikacji]
           |                                             |
           | ----- 2. VERIFY (confirm fence) ----------> | Sprawdzenie ważności dzierżawy
           |                                             | i braku spóźnionych zapisów
           | <---- FENCED (potwierdzenie) -------------- |
           |                                             |
     [Ocena/Bariera OK]                                  |
           |                                             |
           | ----- 3. RELEASE (cleanup/abort) ---------> | Zwolnienie bariery
           | <---- RELEASED ---------------------------- |
```

### 1. Fazy i akcje protokołu

- **`PREPARE`**:
  Żądanie ustanowienia bariery dla wskazanego zakupu (`orderId`), generacji (`generation`),
  wersji (`version`), fazy (`phase`), właściciela i oczekiwanej rewizji stanu źródła (`expectedSourceRevision`).
  Usługa źródłowa pobiera blokadę transakcyjną `pg_advisory_xact_lock(436, hashint8(orderId))`,
  weryfikuje aktualną rewizję danych oraz stan outboxu. Jeśli stan jest zgodny,
  zostaje przyznana dzierżawa na 15 sekund według bieżącego czasu bazy danych (`CURRENT_TIMESTAMP + 15s`),
  a odpowiedź zwraca status `FENCED` z unikalnym `leaseToken` i sygnaturą wygaśnięcia.
- **`VERIFY`**:
  Weryfikacja ważności wcześniej przyznanej bariery przed wykonaniem kolejnego kroku.
  Sprawdza, czy token dzierżawy pasuje, dzierżawa nie wygasła i czy od momentu fazy `PREPARE`
  nie nastąpił żaden spóźniony zapis ani zmiana rewizji w usłudze źródłowej.
  Jeśli wykryto spóźniony zapis — zwraca `STALE` lub `CONFLICT`.
- **`RELEASE`**:
  Jawne zwolnienie bariery po zakończeniu oceny lub w przypadku przerwania/błędu.
  Zwraca status `RELEASED`.

### 2. Statusy odpowiedzi

- `FENCED`: Bariera aktywna, brak konfliktów, dzierżawa ważna.
- `STALE`: Wykryto nowszą rewizję danych w źródle (spóźniony zapis po prepare).
- `CONFLICT`: Sprzeczne żądanie dzierżawy, niezgodna generacja lub inny wykonawca.
- `BLOCKED`: Zakup objęty aktywną blokadą prawną/biznesową uniemożliwiającą barierę.
- `RELEASED`: Bariera została pomyślnie zwolniona.

## Ochrona przed spóźnionymi zapisami

W integracji `CommerceRetentionWorkflow.fenceAndAssess`:
1. Koordynator wykonuje fazę `PREPARE` na wszystkich zaufanych peerach (Payment, Delivery,
   Returns, Promotion, Billing, Notification).
2. Jeśli jakikolwiek peer odmówi bariery (zwróci status inny niż `FENCED`), koordynator
   natychmiast przerywa procedurę, wysyła `RELEASE` do pozostałych usług i unieważnia
   bieżącą generację w `CommerceRetentionJobs` z powodu naruszenia spójności.
3. Następnie koordynator wykonuje fazę `VERIFY`. Jeśli w międzyczasie w którejkolwiek
   z baz źródłowych nastąpił równoległy lub opóźniony zapis (np. zarejestrowano nowe
   zdarzenie outbox, jak symulowane w teście `lateWriteDuringFenceFailsVerificationAndAbortsOperation`),
   weryfikacja kończy się niepowodzeniem (`STALE`/`CONFLICT`). Generacja jest natychmiast
   unieważniana, a operacja zostaje bezpiecznie zatrzymana.

## Odzyskiwanie po awarii (Crash Recovery i Replay)

1. **Wygasanie dzierżawy (Lease Timeout)**:
   Dzierżawa bariery w bazach źródłowych jest ograniczona czasowo (15 sekund).
   W razie awarii lub zawieszenia koordynatora przed wysłaniem `RELEASE`, dzierżawa
   wygasa samoistnie. Kolejne operacje w usłudze źródłowej nie zostają trwale zablokowane.
2. **Idempotentny Replay**:
   W przypadku ponowienia identycznego żądania `PREPARE` (ten sam zakup, generacja, wersja, faza,
   rewizja i właściciel) przed upływem terminu ważności, usługa źródłowa zwraca ten sam
   `leaseToken` i potwierdza status `FENCED` bez generowania konfliktu.
3. **Konflikt współbieżności**:
   Próba nałożenia bariery z inną generacją lub przez innego koordynatora przy aktywnej dzierżawie
   skutkuje natychmiastowym statusem `CONFLICT`.

## Granice i zachowane niezmienniki

- **Brak atomowej migawki całego klastra**:
  Protokół barier jest dwufazowym mechanizmem blokującym i weryfikującym spójność,
  nie jest jednak rozproszoną migawką transakcyjną (global snapshot).
  Kontrprzykład `sequentialRevalidationStillDoesNotFreezeAnAlreadyReadSource` został
  zachowany w kodzie testów bez żadnych zmian jako dowód, że sam nieskorelowany
  odczyt obserwacji nie gwarantuje spójności.
- **Bezpieczeństwo finansowe (D01)**:
  Wszelkie zapisy finansowe (faktury, korekty) pozostają nienaruszone.
- **Niezmiennik inwentarza**:
  Zero nowych tabel, kolumn czy migracji: dokładnie **106 tabel, 1017 kolumn, 185 migracji**,
  6 przestrzeni Redis, 20 kategorii kopii, 26 polityk.
- **Brak aktywacji (`activationAllowed=false`)**:
  Endpoint `/internal/privacy/commerce-retention/peer-fence` podlega ścisłej autoryzacji
  (`order-service-only`), domyślnie nowe funkcje są nieaktywne w środowiskach runtime.
  Status bramki gotowości: `NOT_READY` (kod 2).

## Odbiór i dowody

- **117 testów Java PASS**:
  - `platform-security`: 24 testy jednostkowe (w tym 8 w `PeerRetentionProtocolTest`).
  - `order-service`: 93 testy integracyjne PostgreSQL Testcontainers:
    - `CommerceRetentionPeersPostgresIT`: 26 testów (w tym `lateWriteDuringFenceFailsVerificationAndAbortsOperation`, `fenceCrashRecoveryAndReplayScenarios`, oraz zachowany counter-example).
    - `CommerceRetentionWorkflowPostgresIT`: 30 testów (w tym `fenceAndAssessReachesFencedAssessedWhenPeersConfirm`, `fenceFailureDuringPrepareOrVerifyStopsAndInvalidatesGeneration`).
    - `CommerceRetentionJobsPostgresIT`: 13 testów.
    - `CommerceRetentionFieldScopePostgresIT`: 12 testów.
    - `CommerceRetentionHoldsPostgresIT`: 12 testów.
- **407 testów Python PASS** (kompletny zestaw jednostkowy i regresyjny).
- Wszystkie bramki QA: `data-lifecycle-check.py` (PASS), `technical-data-classification-check.py` (PASS), `commerce-retention-check.py` (PASS), `retention-policy-check.py --candidate` (PASS), `--require-ready` (kod 2 / NOT_READY).
- Artefakty odbioru:
  - `tmp/audit-a43-40-distributed-fence/report.json`
  - `tmp/audit-a43-40-distributed-fence/a43-final-review.json`
  - `tmp/audit-a43-40-distributed-fence/junit/*.xml`
