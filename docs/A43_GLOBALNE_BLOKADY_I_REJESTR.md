# A43 — globalne blokady zachowania i rejestr dowodów

**Dokument historyczny pakietu 120. Bieżący stan: [pakiet 124](A43_GLOBALNE_DOWODY_KOLEKCJA_124.md).**
Poniższe deklaracje dodatnich dowodów zostały skorygowane: skrót D02 nie
zatwierdza kompletnej polityki, a podpis pojedynczego wpisu nie dowodzi
aktualności niezależnego rejestru. Goły callback `CONFIRMED` jest niewystarczający.
Kolektor przyjmuje obecnie lokalne obserwacje negatywne; pełne źródła globalne
pozostają do implementacji i odbioru. Pakiet 123 zastąpił znaczniki w pamięci
trwałymi dzierżawami SQL, nadal bez pełnego protokołu mutacji.

Stan na 31.10.2026 · branch `Audit_0.1` · pakiet 120 · rewizja polityki `a43.40-2026-10-31.15`.  
Zakres: krok 2 rozdziału 5 dokumentu przekazania audytu — implementacja zaufanych dowodów
globalnych blokad zachowania, otwartych spraw/sporów, aktywnych ponowień/DLQ oraz
aktualnego wpisu do niezależnego rejestru prywatności dla retencji komercyjnej D02.  
`activationAllowed=false`. A43.40 i A43.41 pozostają otwarte ze statusem `NOT_READY`.

---

## 1. Problem i cel kroku 2

W pakiecie 119 wdrożono dwufazowy protokół bariery zapisów (`PeerRetentionFenceProtocol`),
chroniący przed spóźnionymi i współbieżnymi zapisami w usługach źródłowych.
Zgodnie z wymaganiami audytu sam pusty rejestr lokalny nie stanowił jednak dowodu braku blokad
w skali całego klastra (`absenceIsClearance = false`).

W krok 2 należało wdrożyć rygorystyczne, fail-closed dowody spełnienia wszystkich
wymaganych strażników (*guards*) przed dopuszczeniem jakiejkolwiek kwalifikacji D02:
1. `PRESERVATION_POLICY_REVIEWED` — kryptograficzne potwierdzenie zaakceptowanej rewizji polityki zachowania.
2. `INDEPENDENT_JOURNAL_CURRENT` — dowód obecności podpisanego (Ed25519) wpisu w niezależnym rejestrze prywatności.
3. `NO_PRESERVATION_HOLD` — brak blokad prawnych/biznesowych na poziomie zamówienia i klienta.
4. `NO_OPEN_OR_REOPENED_CASE` — brak otwartych spraw, reklamacji lub sporów (np. w `returns-service`).
5. `NO_ACTIVE_RETRY_OR_DLQ` — brak aktywnych ponowień, nieobsłużonych DLQ lub trwających sag.
6. Ochrona zależności fazy `MINIMUM_REMOVAL` — trwałe blokowanie usuwania rekordów finansowych D01.

---

## 2. Architektura i komponenty

### 2.1. Recenzja polityki zachowania (`CommerceRetentionPreservationReview`)

W module `platform-security` utworzono kanoniczną definicję:
- Plik zasobu: `/privacy/commerce-retention-preservation-review.json`
- Suma kontrolna SHA-256: `ff1c103e825313818c0a6c24e08697fe0aa961b3262844d9ff98634fb787b05c`
- Klasa weryfikująca: [`CommerceRetentionPreservationReview.java`](../platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionPreservationReview.java)
- Dowiązanie do decyzji użytkownika: `a43-decisions-2026-10-30.2`, polityka `D02-P02-2026-10-30.1`,
  365 dni do redakcji, minimum 90 dni, ochrona wyjątków finansowych.

Wszelkie próby modyfikacji, usunięcia lub podania niezatwierdzonej treści powodują natychmiastowe
rzucenie błędu i przejście strażnika w stan `UNKNOWN` / `UNAVAILABLE`.

### 2.2. Zbieranie dowodów globalnych strażników (`CommerceRetentionGlobalGuardEvidence`)

W module `order-service` wdrożono klasę:
[`CommerceRetentionGlobalGuardEvidence.java`](../order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionGlobalGuardEvidence.java).

Działanie pod blokadą wiersza zamówienia (`SELECT ... FOR NO KEY UPDATE`) i blokadą zadania (`SELECT ... FOR UPDATE`):
1. **`PRESERVATION_POLICY_REVIEWED`**:
   Weryfikuje skrót `CommerceRetentionPreservationReview.verifiedDigest()`. Jeśli zgodny z przypiętym SHA-256 — `CONFIRMED`; w razie błędu — `UNKNOWN`.
2. **`INDEPENDENT_JOURNAL_CURRENT`**:
   Weryfikuje token wpisu rejestru za pomocą `JournalReceiptVerifier.verify(...)` pod kątem identyfikatora klienta, identyfikatora żądania i zakresu `"commerce"`. W przypadku braku konfiguracji, braku tokenu lub błędnego podpisu Ed25519 — zwraca stan `UNKNOWN` (brak milczącej zgody).
3. **`NO_PRESERVATION_HOLD`**:
   Weryfikuje `CommerceRetentionHolds.Snapshot` (blokady ORDER i CUSTOMER), `account_privacy_fences`, `commerce_privacy_locks`, `restore_blocked`.
   Wykrycie jakiejkolwiek blokady natychmiast ustawia stan `BLOCKED`.
   W przypadku braku blokad lokalnych odpytuje `GlobalHoldProvider`. Brak dostawcy lub brak potwierdzenia daje `UNKNOWN` (`absenceIsClearance = false`).
4. **`NO_OPEN_OR_REOPENED_CASE`**:
   Weryfikuje stan spraw i zwrotów. Jeśli `CaseProvider` wykryje aktywne zgłoszenie (np. status zwrotu inny niż odrzucony/zamknięty) lub istnieje lokalna blokada — ustawia stan `BLOCKED`. Brak dowodu daje `UNKNOWN`.
5. **`NO_ACTIVE_RETRY_OR_DLQ`**:
   Sprawdza lokalne sagi zamówień (`order_sagas` ze statusem `STARTED`, `RETRYING` lub `PENDING`) oraz zewnętrzne kolejki (`payment_follow_ups`, `notifications` w toku, `event_inbox`). Aktywne ponowienie daje `BLOCKED`. Brak dowodu daje `UNKNOWN`.
6. **Zależności `MINIMUM_REMOVAL`**:
   Dla fazy usuwania minimalnego strażnicy `FINANCIAL_DEPENDENCIES_RELEASED`, `RESTORE_DEPENDENCIES_RELEASED`, `RETRY_DEPENDENCIES_RELEASED` i `DEDUP_DEPENDENCIES_RELEASED` otrzymują bezwzględny stan `BLOCKED`, chroniąc dokumenty finansowe D01 i znaczniki odtwarzania.

Każdy oceniony strażnik jest utrwalany w tabeli `commerce_retention_evidence` powiązany z generacją, wersją zamówienia i fazą retencji.

### 2.3. Integracja z przepływem pracy (`CommerceRetentionWorkflow`)

W [`CommerceRetentionWorkflow.java`](../order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionWorkflow.java):
- Dodano metodę `reviewGlobalGuards(Subject subject, Phase phase, GuardInputs inputs)`.
- Dodano kod statusu kroku: `Code.GLOBAL_GUARDS_REVIEWED`.
- Wykonywana poza transakcją zewnętrzną, z weryfikacją stanu blokad i rewizji przed i po utrwaleniu dowodów.
- W przypadku wykrycia stanu `BLOCKED` zadanie retencji jest unieważniane (`current = false`), a operacja przerywana.
- Przepływ `fenceAndAssess` wywołany po pomyślnym przeglądzie pól i strażników osiąga kwalifikację:
  - `WAIT` / `AGE_NOT_REACHED` — gdy wiek zamówienia < 365 dni.
  - `DUE_FOR_REVIEW` / `AGE_REACHED` — gdy wiek zamówienia >= 365 dni.
  - W obu przypadkach flaga `activationAllowed()` ma bezwzględną wartość `false`.

---

## 3. Wyniki weryfikacji i testów

### 3.1. Testy jednostkowe i integracyjne Java

1. **`CommerceRetentionPreservationReviewTest`** (`platform-security`):
   - 4 przypadki testowe — **PASS**.
   - Weryfikacja kanonicznego skrótu SHA-256, odporność na znaki końca linii Windows (`CRLF`), odrzucenie manipulacji treścią, odrzucenie brakujących/pustych zasobów.
2. **`CommerceRetentionGlobalGuardsPostgresIT`** (`order-service`):
   - 6 przypadków testowych w kontenerze PostgreSQL — **PASS**.
   - `missingEvidenceFailsClosedToUnknownAndWaits`: brak dowodów zewnętrznych skutkuje stanem `UNKNOWN` i statusem `WAIT` / `MISSING_GUARD_PROOF`.
   - `activeOrderOrCustomerHoldBlocksGuardReview`: aktywna blokada klienta/zamówienia blokuje przepływ i unieważnia generację.
   - `openCaseOrActiveRetryBlocksGuardReview`: wykrycie otwartej sprawy zwrotu blokuje operację.
   - `activeOrderSagaBlocksRetryDlqGuard`: aktywna saga (`STARTED`) blokuje strażnika ponowień.
   - `validJournalReceiptAndConfirmedGuardsSucceedWithAssessment`: podpisany token Ed25519 i potwierdzeni strażnicy doprowadzają do prawidłowej oceny wieku (`AGE_REACHED` / `AGE_NOT_REACHED`) przy zachowaniu `activationAllowed=false`.
   - `minimumRemovalPhasePermanentlyBlocksProtectedDependencies`: faza usunięcia blokuje zależności finansowe D01.
3. **Łączny stan testów Java**:
   - `platform-security`: **195 testów PASS**.
   - `order-service` PostgreSQL IT: **156 testów PASS** (w tym 6 nowych dla globalnych strażników).

### 3.2. Testy i bramki jakościowe Python

- **`commerce-retention-check.py`**: **PASS**.
  - Weryfikacja definicji i skrótu `PRESERVATION_POLICY_REVIEWED`.
  - Sprawdzenie planu przepływu: `globalGuardWrites: true`, `globalPreservationReviewed: true`.
- **`retention-policy-check.py`**: **PASS**.
  - `policySha256`: `c9e482140cf08a6f0c314d42e21afc54cf463a0d07011a976c720bce68de1a5f`.
  - Inwentarz nienaruszony: 106 tabel, 1017 kolumn, 185 migracji, 6 magazynów Redis, 20 magazynów kopii, 26 polityk.
  - Sprawdzenie kandydata `retention-observe-example.json`: **PASS**.
  - Sprawdzenie bramki gotowości `--require-ready`: kod wyjścia `2` (`NOT_READY`).
- **`data-lifecycle-check.py`** i **`technical-data-classification-check.py`**: **PASS**.
- **Zestaw testów jednostkowych Python (`test_*.py`)**: **407 testów PASS**.

---

## 4. Artefakty dowodowe

Artefakty etapu zapisano w katalogu:
- `tmp/audit-a43-40-global-guards/report.json` — raport oceny polityki retencji z kandydatem.
- `tmp/audit-a43-40-global-guards/commerce-retention.json` — kontrakt retencji komercyjnej D02.
- `tmp/audit-a43-40-global-guards/junit/TEST-org.demo.orderservice.privacy.CommerceRetentionGlobalGuardsPostgresIT.xml` — raport JUnit z testów integracyjnych PostgreSQL.
- `tmp/audit-a43-40-global-guards/junit/TEST-org.demo.platformsecurity.privacy.CommerceRetentionPreservationReviewTest.xml` — raport JUnit z testów recenzji zachowania.

---

## 5. Zgodność z niezmiennikami

- `activationAllowed = false` — żadna operacja nie dopuszcza usunięcia ani mutacji danych produkcyjnych/testowych.
- Liczba tabel (106), kolumn (1017) i migracji (185) pozostała ściśle zachowana (zero schema creep).
- Brak domyślnego przyznawania uprawnień przy braku danych (`absenceIsClearance = false`).
- Dane finansowe (D01), logi i znaczniki deduplikacji pozostają chronione.
