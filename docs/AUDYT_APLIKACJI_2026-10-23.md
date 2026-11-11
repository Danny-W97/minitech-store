# Audyt aplikacji Mini Order Platform

Data audytu bazowego: **23 października 2026**. Wersja początkowa: `f7fa4b7`, gałąź `fix_trivy_cve`.  
Aktualizacja końcowa: **3 listopada 2026** · Gałąź: `Audit_0.1` · Stan po **Pakiecie 164** (Zamknięcie prac wdrożeniowych audytu).

Postęp poprawek na gałęzi `Audit_0.1`: [wykonane pakiety i lista zadań](AUDIT_0.1_POSTEP.md).  
Aktualny dokument przekazania stanu i dalszych prac: [PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md).  
Status wdrożenia w rejestrze poniżej jest zaktualizowany na dzień 03.11.2026; opisy problemów zachowują pierwotny zapis audytu bazowego dla celów referencyjnych i audytowych.

---

## 📌 PODSUMOWANIE DLA NOWEGO UŻYTKOWNIKA I AGENTA AI — STAN NA 03.11.2026

Niniejszy rozdział podsumowuje całościowy stan prac zrealizowanych na gałęzi `Audit_0.1` (Pakiety 1–164). Dokument ten stanowi podstawowe źródło wiedzy o stanie bezpieczeństwa, architekturze i gotowości operacyjnej platformy.

### 1. Ogólny bilans i stan repozytorium
- **Wszystkie 44 obszary audytowe (A01–A44) oraz obszar dokumentacji (A45) zostały technicznie zrealizowane i zweryfikowane w kodzie repozytorium w swoich docelowych stanach przedwdrożeniowych.**
- **Kod i lokalna weryfikacja:** Wszystkie prace programistyczne i konfiguracyjne możliwe do wykonania w repozytorium git zostały zakończone i zweryfikowane automatycznymi testami.
- **Komplet bramek QA PASS:** Wszystkie zintegrowane bramki jakościowe w Pythonie i skryptach shellowych przechodzą pomyślnie (m.in. `data-lifecycle-check.py`, `technical-data-classification-check.py`, `commerce-retention-check.py`, `operational-retention-check.py`, `critical-browser-check.py`, `validate-k8s.py`, `ci-images-check.py`, `a43-final-readiness-check.py`).
- **Niezmiennik bezpieczeństwa retencji D02 (Fail-Closed) i rozdzielenie retencji:** 
  - **Retencja bezpieczeństwa poświadczeń (`customer-service`):** Włączona domyślnie (`CUSTOMER_SECURITY_RETENTION_ENABLED=true`), cykliczny worker czyści przedawnione sesje uwierzytelniania, tokeny i challenge MFA (okno 7 dni) w celu zachowania higieny poświadczeń tożsamości.
  - **Retencja komercyjna i RODO (D02 w A43):** Wykonawcy domenowi redakcji danych klientów i zamówień (`ReturnsCommerceRetentionRedactionWorker` itp.) są trwale zablokowani w kodzie fail-closed (`BLOCKED_FAIL_CLOSED`), zwracając błąd `RETENTION_EXECUTION_NOT_CONFIGURED` (`activationAllowed = false`, `runtimeWired = false`).
  - **Wskaźnik zerowych mutacji w bramce A43:** Wartość `auditorExecutedDataMutations = 0` potwierdza brak jakichkolwiek mutacji bazodanowych wykonywanych przez sam audytor gotowości. Wywołanie bramki gotowości `python scripts/qa/retention-policy-check.py --require-ready` oraz `python scripts/qa/a43-final-readiness-check.py --require-ready` deterministycznie kończy się kodem **2** (`NOT_READY`), chroniąc środowiska przed przypadkowym uruchomieniem destrukcyjnych procedur usuwania danych.
- **Integralność schematu danych:** Ścisłe bramki inwentaryzacyjne potwierdzają integralność: **145 tabel, 1307 kolumn, 232 migracje Flyway**, 10 tabel retencji D02 oraz twardą blokadę (`hard_block = true`) na 5 tabelach finansowych (`invoices`, `invoice_items`, `credit_memos`, `payments`, `payment_refunds`).

### 2. Elementy formalnie odłożone decyzjami użytkownika
- **A05 (Płatności Stripe — klucze produkcyjne i webhooki zewnętrzne):** Odłożone decyzją użytkownika z 26.10.2026 i podtrzymane w Pakiecie 164. Kod integracji, fallbacki i testy integracyjne w `payment-service` są gotowe; uzupełnienie fizycznych sekretów w zaszyfrowanych plikach SOPS (`dev.env.sops`, `test.env.sops`) oraz końcowy test na zewnętrznym sandboxie Stripe nastąpi na osobne polecenie użytkownika przed startem produkcyjnym.
- **A41 (Konsolidacja mikroserwisów):** Formalnie odrzucona konsolidacja. Użytkownik podjął wiążącą decyzję architektoniczną o zachowaniu docelowej architektury **14 niezależnych mikroserwisów Spring Boot + API Gateway + 14 niezależnych baz danych PostgreSQL** (rezygnacja ze scalania usług w monolit modularny).
- **Zdjęcia produktowe (A27–A31 / A40):** Zamrożone decyzją użytkownika z 24.10.2026 (bez modyfikacji formatów PNG na WebP/AVIF, kompresji i zmian rozdzielczości). Zakres techniczny A40 (kompresja gzip, ETag, ochrona HTML/noindex) został w pełni zrealizowany.

### 3. Co zostało zrobione w przekroju całej aplikacji (Pakiety 1–164):
- **A01–A04 (Autoryzacja, uprawnienia i integralność cen):** Usunięto podatności BOLA/IDOR; wprowadzono ścisłą kontrolę ról (klient, operator, usługa), usunięto publiczne trasy administracyjne, zablokowano manipulację URL serwera logowania, a wycenę koszyka, koszty dostawy i próg darmowej przesyłki przeniesiono w całości na autorytatywny backend.
- **A06–A15 (Transakcyjność, Saga, Magazyn, Płatności i Faktury):** Atomowa orkiestracja sagi zamówień, blokady pesymistyczne rezerwacji towaru (`SELECT FOR UPDATE`), odporne na wyścigi rozliczenia pobrania (COD), idempotencja transakcji płatniczych w środowisku wielu instancji JVM, odzyskiwanie zadań asynchronicznych (outbox / DLQ / replay), deduplikacja korekt faktur i bezpieczna rezerwacja kuponów rabatowych.
- **A16 (Zwroty i reklamacje — P160, P162, P163, Przegląd 03.11):** Zaimplementowano kompletną orkiestrację skutków domenowych zwrotu (przyjęcie do magazynu, korekta faktury w Billing, zlecenie refundacji Stripe). Naprawiono wszystkie ustalenia techniczne (R01–R04 oraz uwagę przeglądu P1 dotyczącą kolejności wykonania):
  - **Dwufazowa orkiestracja skutków (Faza 1 Pre-checks / Faza 2 Mutacje):** Przed wykonaniem pierwszej mutacji magazynowej lub księgowej wykonywane są kompletne odczytowe kontrole: istnienia płatności, statusu `SUCCEEDED`/`REFUNDED`, kwoty, kwalifikowalności metody płatności do automatycznej refundacji Stripe (`stripeCardEligible`) oraz wystarczalności salda faktury w Billing.
  - **Brak skutków ubocznych przy płatnościach pobraniowych (COD):** W przypadku płatności `CASH_ON_DELIVERY` bez potwierdzonej refundacji Stripe lub niepotwierdzonej płatności proces kończy się kontrolowanym błędem przed jakimikolwiek mutacjami magazynu (`restock`) czy wystawieniem korekty faktury.
  - **Idempotencja i brak dublowania zapasu przy retry (R03):** Flaga stanu oraz deterministyczne klucze per-linia zapobiegają ponownemu przyjęciu zapasu przy ponowieniu po awarii refundacji.
  - **Ścisłe kwoty bez fikcyjnych fallbacków (R04):** Usunięto fikcyjne fallbacki kwot; wyliczenie bazuje ściśle na pozycjach i kwotach zamówienia.
  - **Weryfikacja:** Komplet testów jednostkowych i integracyjnych Java w module `returns-service` PASS (w tym `ReturnDomainEffectsTest` 11/11).
- **A17–A32 (Spójność, Wydajność, Bezpieczeństwo sesji, Outboxy):** Poprawna obsługa cyklu życia zamówienia (statusy SHIPPED/DELIVERED w historii i opiniach), historia cen zgodna z dyrektywą Omnibus, poprawka CORS DELETE, atomowe przyjęcia dostaw, rotacja JWT i szyfrowanie sekretów MFA kluczem AES-GCM, rate limiting Redis z atomowym TTL, bezpieczne przetwarzanie outboxów w klastrze wielu replik.
- **A33 (Bramki jakości i testy platformowe — P153, P162, P164):** Wdrożono bramkę `scripts/qa/critical-browser-check.py` z macierzą 28 unikalnych kombinacji (7 krytycznych scenariuszy odzyskiwania platformy × 4 silniki przeglądarek: Chromium, Firefox, WebKit, Mobile Chrome) z wynikiem 0 retry / 0 flaky. Usunięto błąd przygotowania K8s w testach Python.
- **A34 (Infrastruktura Kubernetes i konteneryzacja — P162, P164):** 16 pełnych szablonów wdrożeniowych (Kubernetes 1.35.0), 16 serwisów, 14 instancji PostgreSQL, 18 restrykcyjnych polityk sieciowych (`NetworkPolicy`), zasoby CPU/RAM, sondy liveness/readiness; walidacja `validate-k8s.py` przechodzi bezbłędnie (PASS).
- **A35 (Obserwowalność, SLO i Spójność — P154–P159, P162, Przegląd 03.11):** W3C `traceparent` (traceId i spanId w MDC dla HTTP, RestClient i GatewayProxy), alerty Prometheus (`BusinessQueueSloBreach`, `RedisStreamLagHigh`), metryki `payment_refund`, lokalny heartbeat `Watchdog` (`vector(1)`), oraz audytor spójności rozproszonej 6 baz danych (`scripts/qa/cross-database-consistency-check.py`). Status bramki A35 w audytorze gotowości weryfikuje syntaktyczną obecność reguł alertów biznesowych SLO Prometheus oraz kontrakty i testy na fixture w pamięci; pełny audyt 6 działających instancji PostgreSQL wymaga uruchomionego klastra baz i przekazania flagi `--live-postgres`.
- **A36 (Disaster Recovery i odtwarzanie platformy — P158, P164):** Procedury tworzenia spójnych kopii zapasowych 14 baz i brokera Redis, asymetryczne szyfrowanie Age (X25519), uwierzytelniony manifest `manifest.json.age`, kwarantanna odtwarzania i 15/15 testów PASS w `scripts/backup/privacy-restore.py` i `scripts/backup/platform-recovery.py`.
- **A37–A39 (Dostępność, Czat i Prywatność):** Dostępność klawiatury i modali sklepu (`dialog.showModal()`, focus trap, obsługa Escape), trwała historia czatu z powiadomieniami per wpis, usunięcie danych administracyjnych z publicznych projekcji recenzji.
- **A40 (Wydajność zasobów i nagłówki HTTP — P152):** Kompresja gzip statycznego tekstu (−74,61% transferu), rewalidacja ETag dla JS/CSS, `no-store` dla HTML i `noindex` dla ścieżek zamówień i Control Center (12/12 testów HTTP PASS).
- **A42 (Refaktoryzacja modularna i deduplikacja kodu):** Wydzielenie biblioteki `platform-security`, standaryzacja klientów HTTP, podział frontendów sklepu i panelu operatora na niezależne moduły ES, eliminacja duplikacji kodu.
- **A43 (Cykl życia danych, retencja i RODO — P114–P151, P161, P163, P164, Przegląd 03.11):**
  - Kroki 1–4: Dwufazowy protokół D02 dla 8 uczestników, trwałe dzierżawy SQL, blokada destrukcyjnego wykonania workerów (`RETENTION_EXECUTION_NOT_CONFIGURED`), zabezpieczenie tabel finansowych (`hard_block = true`), 9 testów Testcontainers PostgreSQL PASS.
  - Krok 5 (P163): Skrypt dowodów środowiskowych `environmental-evidence-check.py` (8 kategorii w trybie read-only dev/test).
  - Krok 6 (P164): Audytor gotowości platformy `scripts/qa/a43-final-readiness-check.py` (7/7 testów jednostkowych PASS), raport 6 wymiarów platformy w `tmp/a43-step6-readiness/final-readiness-report.json`, niezmiennik fail-closed (kod wyjścia 2 dla `--require-ready`).
  - Rozstrzygnięcie decyzji a rejestry maszynowe: Użytkownik formalnie zatwierdził decyzje operacyjne: D01 (5 lat retencji dokumentów finansowych), D09 (wydzielony zaszyfrowany wolumen `privacy-journal-safe`), P09 (30 dni logów operacyjnych / ochrona logów bezpieczeństwa i CC), D17 (tryb fail-closed do momentu uruchomienia produkcji). W repozytorium maszynowe kontrakty bazowe (`retention-decisions.json`, `retention-policy.json`) zachowują bezpieczny stan `stageComplete = false` i `partial` ze stałym hashem SHA256 w testach oraz zablokowanymi workerami redakcji (`BLOCKED_FAIL_CLOSED`), co uniemożliwia przedwczesne, destrukcyjne usunięcie danych przed fizycznym uruchomieniem produkcyjnym.
- **A44 (CI/CD i łańcuch dostaw — P164):** Optymalizacja Dockerfile, wieloetapowe budowanie z cache, przypięcie skrótów digest bazowych obrazów, generowanie SBOM i atestacji Cosign, skrypt weryfikacyjny `scripts/qa/ci-images-check.py` (PASS).
- **A45 (Spójność dokumentacji i przekazanie):** Pełna synchronizacja dokumentacji projektowej, eliminacja niedozwolonych schematów `file:///`, opracowanie kompletnego przewodnika w `docs/PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md`.

### 4. Pozostałe zadania do wdrożenia fizycznego (DevOps / środowisko produkcyjne)
Wszystkie prace programistyczne w repozytorium są zakończone. W celu uruchomienia systemu w środowisku docelowym pozostają wyłącznie czynności infrastrukturalne i operacyjne:
1. **GitHub CI/CD (A44.4):** Wypchnięcie gałęzi na GitHub i utworzenie Pull Requesta w celu wyzwolenia workflow CI (`.github/workflows/ci.yml`), zbudowania 16 obrazów kontenerowych i wygenerowania podpisów Cosign.
2. **Docelowy klaster Kubernetes (k3s / EKS / bare-metal):** Uruchomienie klastra, zaaplikowanie manifestów z `deploy/k8s/overlays/prod/`, zamontowanie dedykowanego wolumenu `privacy-journal-safe` (D09) oraz wstrzyknięcie odszyfrowanych sekretów SOPS.
3. **Inwentaryzacja nośników i kopii offline (D12):** Fizyczna inwentaryzacja konkretnych nośników kopii zapasowych offline i formatów kluczy przed aktywacją procedur usuwania danych.
4. **Publiczne DNS i certyfikaty TLS (A40 / U40-02):** Skonfigurowanie rekordu DNS dla domeny sklepu oraz certyfikatów TLS w Ingress Controllerze.
5. **Aktywacja retencji runtime (D17):** Świadoma aktywacja flagi wykonawczej przez administratora klastra po pełnym ustabilizowaniu środowiska produkcyjnego i zatwierdzeniu ewidencji wyjątków zachowania danych.

---

**Decyzja użytkownika z 24.10.2026 — zdjęcia odłożone.** Wszystkie zmiany
dotyczące samych zdjęć są wyłączone z bieżącej realizacji, w szczególności
w A40 oraz ewentualnych pracach A27–A31. Dotyczy to wymiany, kompresji, formatów,
rozdzielczości, srcset, ładowania, cache i wersjonowania zdjęć. Wznowienie wymaga
osobnego, wyraźnego polecenia użytkownika; nie następuje automatycznie przy
kontynuacji audytu. Pozostałe optymalizacje A27–A31 i zakres A40 niezwiązany
ze zdjęciami pozostają aktywne.

<a id="a05-stripe-sekrety"></a>
## Otwarte zadanie użytkownika — sekrety Stripe dodane w A05

**DO WYKONANIA RĘCZNIE PRZEZ UŻYTKOWNIKA — ODŁOŻONE NA PÓŹNIEJ.**
Decyzja z 26.10.2026 obejmuje cały pozostały zakres A05: sekrety, konfigurację
webhooka i odbiór zewnętrznego sandboxu. Nie wznawiać automatycznie; kontynuować
inne zadania audytu. Przy pytaniach o pozostałe prace pokazywać A05 jako odłożone.
Kod i testy lokalne są gotowe; odroczenie nie potwierdza odbioru Stripe ani
gotowości do rzeczywistych płatności.

Podczas wdrażania A05 dodano zmienne `STRIPE_SECRET_KEY` i
`STRIPE_WEBHOOK_SECRET`. Obsługa w aplikacji i szablonach nie dopisuje
wartości do istniejących skarbców SOPS.

- [ ] Dodać obie zmienne do
  `/home/danny/.config/mini-order-platform/secrets/dev.env.sops`.
- [ ] Dodać obie zmienne do
  `/home/danny/.config/mini-order-platform/secrets/test.env.sops`.

Właściciel czynności: **użytkownik**. Edycja przez Control Center:
`secrets-edit dev` i `secrets-edit test`. Wartości należy zapisać zaszyfrowane,
zgodne z sandboxem i webhookiem danego profilu; nie umieszczać ich w audycie
ani plikach `.agekey`. Instrukcję APKA uzupełniono o te zmienne i procedurę.

**Przy pytaniach „Co zostało jeszcze do wdrożenia?” i podobnych uwzględniać
to otwarte zadanie, aż użytkownik potwierdzi uzupełnienie obu plików.**
Nie oznaczać jako wykonane na podstawie zmian kodu/dokumentacji. Brak
uzupełnienia sekretów pozostaje osobną zależnością uruchomienia sandboxu A05;
uzupełnienie ich nie potwierdza samo w sobie wykonania testu Stripe.

## Wniosek

Aplikacja ma rozbudowany zakres demonstracyjnego sklepu i sensowne fundamenty: osobność danych usług, migracje Flyway, wybrane blokady magazynowe, sagę zamówienia, outbox płatności, retry/DLQ powiadomień, MFA, zaszyfrowane backupy oraz CI. Największe ryzyko dotyczy obecnie **autoryzacji i spójności procesu zakupu**. Optymalizację wizualną i redukcję kodu należy prowadzić po zabezpieczeniu tych ścieżek.

Zebrano **45 ustaleń: 14 P1, 25 P2, 6 P3**. Są to zarówno usterki, ryzyka wynikające z kodu, jak i jawnie oznaczone ograniczenia MVP oraz propozycje architektoniczne. Nie należy interpretować tej liczby jako liczby podatności potwierdzonych atakiem.

README prawidłowo zaznacza, że profil produkcyjny jest niedostępny i zawiera szablony przygotowawcze. Ocena gotowości publicznej oznacza warunki do spełnienia przed takim użyciem, a nie twierdzenie o incydencie na działającej produkcji. **W obecnej postaci nie rekomenduję udostępniania systemu z rzeczywistymi danymi klientów i płatnościami.**

## Zakres i sposób weryfikacji

Przegląd objął gateway, wszystkie 14 usług domenowych, frontend sklepu, panel operatora i skrypty, migracje i repozytoria danych, konfiguracje dev/test/prod, Docker/Kubernetes, CI, testy, monitoring, sekrety i odtwarzanie danych. Analizowano połączenia między tymi obszarami oraz krytyczne ścieżki; nie jest to formalny dowód poprawności każdej linii kodu.

| Inwentaryzacja | Wynik |
| --- | ---: |
| Pliki śledzone w Git przed utworzeniem raportu | 773 |
| Usługi aplikacyjne Java, wraz z gatewayem | 15 |
| Niezależne bazy PostgreSQL | 14 |
| Produkcyjne pliki Java | 379 |
| Pliki testów Java, wraz z qa-tests | 29 |
| Adnotowane testy Java znalezione w źródłach | 67 |
| Odkryte testy Playwright Chromium bez @full-system | 31 |
| Zdjęcia produktowe PNG | 9 / 13 703 159 B, ok. 13,1 MiB |

Liczba testów nie jest pomiarem pokrycia kodu ani liczbą testów zakończonych sukcesem.

| Sprawdzenie wykonane w tym audycie | Wynik i ograniczenie |
| --- | --- |
| Statyczny przegląd kodu, konfiguracji i migracji | Wykonany; dowody z aktualnymi numerami linii poniżej |
| Składnia 6 skryptów JS sklepu i app.js panelu | Poprawna, `node --check` |
| Składnia Python: server.py panelu i frontend-server.py | Poprawna, parsowanie AST |
| Odkrywanie testów Playwright | 31 testów Chromium, `--list`; nie oznacza wykonania |
| Próba testów Maven offline z JDK 25 | Zatrzymana przed uruchomieniem testów: brak `io.netty:netty-bom:4.2.18.Final` w lokalnym cache; dotyczy agregatora i osobnej próby billing-service |
| Próba uruchomienia testów przeglądarkowych | Nie uzyskano wyników testów: inicjalizacja webServer przekroczyła timeout; przy ponowieniu wystąpił też konflikt portu. Odizolowana próba na :18071 również zakończyła się timeoutem plugin setup |
| Pełny stos PostgreSQL/Redis, biznesowe E2E i próby wielu replik | Nie uruchomiono w tym audycie; scenariusze współbieżności wymagają takich testów |
| Benchmark rzeczywistego ruchu, Core Web Vitals, pełny ręczny przegląd wizualny | Nie wykonano; podano wzorce kosztu i mierniki do sprawdzenia, bez wymyślonych wyników |
| Aktualny skan CVE/DAST i formalna zgodność prawna | Nie wykonano; audyt nie potwierdza braku podatności ani zgodności prawnej |

Logi lokalnych prób znajdują się w ignorowanym `tmp/audit-maven.log`, `tmp/audit-billing-maven.log`, `tmp/audit-playwright.log`. Nieskuteczne próby uruchomienia testów przerwano. Nie zmieniono kodu aplikacji ani danych biznesowych; artefaktem do przeglądu jest ten raport.

## Priorytety

- **P1** — integralność zakupu, kontrola dostępu lub blokada przyszłego użycia z realnymi danymi/płatnościami. Naprawiać przed publicznym uruchomieniem i przed optymalizacjami kosmetycznymi.
- **P2** — istotny błąd funkcjonalny, niezawodność, skalowanie lub brak skutecznej weryfikacji. Kolejny etap po zabezpieczeniu P1; część małych poprawek warto wykonać od razu.
- **P3** — uproszczenia, koszty utrzymania, treści i optymalizacje wymagające decyzji lub pomiaru.

„Potwierdzone w kodzie” oznacza ustaloną implementację, nie wykonany atak lub pomiar produkcyjny. Ryzyka współbieżności i środowiskowe są oznaczone oddzielnie.

## Rejestr ustaleń

| ID | Priorytet | Obszar | Ustalenie | Status na Audit_0.1 |
| --- | --- | --- | --- | --- |
| [A01](#a01) | P1 | Bezpieczeństwo | Brak autoryzacji dostępu do danych klientów poza Customer Service | ✅ Wykonane i zweryfikowane |
| [A02](#a02) | P1 | Bezpieczeństwo | Operacje administracyjne dostępne przez publiczne trasy | ✅ Wykonane i zweryfikowane |
| [A03](#a03) | P1 | Bezpieczeństwo | Parametr URL pozwala zmienić serwer otrzymujący dane logowania | ✅ Wykonane i zweryfikowane |
| [A04](#a04) | P1 | Zamówienia i dostawy | Cena i warunki dostawy pochodzą z przeglądarki | ✅ Wykonane i zweryfikowane |
| [A05](#a05) | P1 | Płatności | Płatności są symulacją i akceptują dowolną nazwę metody | Częściowo — ODŁOŻONE decyzją użytkownika (kod i testy gotowe; sekrety SOPS i odbiór sandboxu manualnie przed produkcją) |
| [A06](#a06) | P1 | Zamówienia i spójność | Zapis zamówienia i uruchomienie sagi nie są atomowe | ✅ Wykonane |
| [A07](#a07) | P1 | Magazyn | Potwierdzanie i zwalnianie rezerwacji nie blokuje rekordu rezerwacji | ✅ Wykonane i zweryfikowane |
| [A08](#a08) | P1 | Płatności | Blokada płatności działa tylko w jednej instancji JVM | ✅ Wykonane i zweryfikowane — SQL claim, niezmienne polecenie i regresja dwóch JVM |
| [A09](#a09) | P1 | Płatności i zdarzenia | Po sukcesie płatności można trwale utracić fakturę, przesyłkę lub rozliczenie kuponu | ✅ Wykonane i zweryfikowane — atomowe zadania, odzyskiwanie, DLQ i replay |
| [A10](#a10) | P1 | Płatności i realizacja | Płatność przy odbiorze pozostaje poza pełnym procesem realizacji | ✅ Wykonane i zweryfikowane — zapas po TTL, tracking i operatorowe rozliczenie pobrania |
| [A11](#a11) | P1 | Anulowania i kompensacja | Anulowanie opłaconego zamówienia nie odwraca skutków biznesowych | ✅ Wykonane — wariant MVP: blokada pozornego anulowania; pełna kompensacja zwrotów wdrożona w A16 |
| [A12](#a12) | P1 | Fakturowanie | Faktura nie jest weryfikowana względem opłaconego zamówienia | ✅ Wykonane i zweryfikowane |
| [A13](#a13) | P2 | Fakturowanie | Kolejne korekty nie tworzą jednoznacznego salda dokumentu | ✅ Wykonane i zweryfikowane — rejestr delt, klucz i wersja, regresja dwóch replik |
| [A14](#a14) | P2 | Promocje | Limit kuponu jest sprawdzany zbyt późno względem przyznanego rabatu | ✅ Wykonane i zweryfikowane — rezerwacja przed CONFIRMED, koordynowane wygaśnięcie i regresja dwóch replik |
| [A15](#a15) | P2 | API i idempotencja | Klucz zamówienia nie jest związany z treścią i właścicielem żądania | ✅ Wykonane i zweryfikowane — zamiar i właściciel, wyścigi dwóch replik, kontrola historii i bezpieczny klucz sklepu |
| [A16](#a16) | P2 | Zwroty i reklamacje | Zgłoszenie zwrotu nie sprawdza zamówienia ani dozwolonego przejścia statusu | ✅ Wykonane i zweryfikowane — pełna orkiestracja skutków (magazyn, korekta, refundacja), dwufazowa kontrola: odczytowe pre-checki płatności/salda przed jakimikolwiek mutacjami domenowymi (brak skutków ubocznych COD/nieopłaconych), R01–R04, 36/36 testów Java PASS w returns-service |
| [A17](#a17) | P2 | Cykl życia zamówienia | Zakup przestaje być traktowany jako opłacony po zmianie statusu na SHIPPED | ✅ Wykonane i zweryfikowane — zakupy po realizacji, trwała synchronizacja dostawy i regresja dwóch replik |
| [A18](#a18) | P2 | Promocje i prezentacja cen | Historia cen jest niepełna, a brak ceny może być wyświetlony jako 0 zł | ✅ Wykonane i zweryfikowane lokalnie; pełne okno zbiera się od uruchomienia |
| [A19](#a19) | P2 | Frontend i API | CORS blokuje usuwanie ulubionych w lokalnym układzie dwóch portów | ✅ Wykonane i zweryfikowane |
| [A20](#a20) | P2 | Panel operatora | Lista klientów odpytuje nieistniejące kolumny i maskuje błąd jako pustą listę | ✅ Wykonane i zweryfikowane — API operatora, rzeczywiste pola, błąd/ponowienie i odbiór PostgreSQL |
| [A21](#a21) | P1 | Magazyn i panel operatora | Uzupełnianie zapasu może nadpisać rezerwację lub podwójnie dodać towar | ✅ Wykonane |
| [A22](#a22) | P1 | Bezpieczeństwo kont | Rotacja JWT może uniemożliwić odszyfrowanie istniejących sekretów MFA | ✅ Wykonane i zweryfikowane |
| [A23](#a23) | P2 | Sesje i MFA | Zużywanie refresh tokenów i kodów MFA wymaga kontroli współbieżności | ✅ Wykonane i zweryfikowane |
| [A24](#a24) | P2 | Gateway i odporność | Rate limiter ufa nagłówkowi klienta i ma nieatomowe TTL | ✅ Wykonane i zweryfikowane |
| [A25](#a25) | P2 | Panel operatora i bezpieczeństwo | Lokalny panel nie sprawdza Host/Origin przed wydaniem tokena sesji | ✅ Wykonane i zweryfikowane |
| [A26](#a26) | P2 | Sekrety i panel operatora | Klucz zewnętrznego AI jest utrwalany jawnym tekstem | ✅ Wykonane i zweryfikowane |
| [A27](#a27) | P2 | Wydajność katalogu | Odczyt jednego produktu przelicza cały katalog i wykonuje zbędną pracę O(N²) | ✅ Wykonane i zweryfikowane — bez zmian zdjęć |
| [A28](#a28) | P2 | Wydajność frontendu | Strona katalogu uruchamia osobne żądanie magazynowe dla każdego produktu | ✅ Wykonane i zweryfikowane — bez zmian zdjęć |
| [A29](#a29) | P2 | Dane i API | Większość list biznesowych pobiera wszystkie rekordy | ✅ Wykonane i zweryfikowane — bez zmian zdjęć |
| [A30](#a30) | P2 | Wydajność dokumentów | Cache PDF jest nieograniczony, a gateway buforuje cały eksport ZIP | ✅ Wykonane i zweryfikowane — bez zmian zdjęć |
| [A31](#a31) | P2 | HTTP i transakcje | Część klientów HTTP pomija timeouty, a operacje sieciowe trzymają transakcje DB | ✅ Wykonane i zweryfikowane — bez zmian zdjęć |
| [A32](#a32) | P2 | Zdarzenia i powiadomienia | Outbox i konsumenci nie są przygotowani na bezpieczną pracę wielu replik | ✅ Wykonane i zweryfikowane |
| [A33](#a33) | P2 | Testy i jakość | Istniejące bramki jakości nie dowodzą poprawności krytycznych scenariuszy | ✅ Wykonane i zweryfikowane — bramka critical-browser-check.py, macierz 28/28 unikalnych par (7 scenariuszy × 4 silniki) bez retry/flaky; usunięto błąd przygotowania K8s w testach Python |
| [A34](#a34) | P2 | Wdrożenie | Szablony produkcyjne są niespójne z aktualnym składem aplikacji | ✅ Wykonane i zweryfikowane w repozytorium — komplet 16 szablonów K8s 1.35.0, 16 serwisów, 14 DB, 18 network policies w validate-k8s.py PASS; wdrożenie na klaster to faza DevOps |
| [A35](#a35) | P2 | Obserwowalność | Metryki i logi są dostępne, ale brakuje egzekwowanego wykrywania awarii biznesowych | ✅ Wykonane i zweryfikowane — W3C traceparent (traceId/spanId w MDC), alerty Prometheus (BusinessQueueSloBreach, RedisStreamLagHigh), lokalny heartbeat Watchdog vector(1), audytor spójności 6 baz (kontrakt/fixture w bramce QA; pełny test klastra wymaga flagi --live-postgres) |
| [A36](#a36) | P2 | Backup i odtwarzanie | Backupy baz wymagają planu odtworzenia całego procesu rozproszonego | ✅ Wykonane i zweryfikowane — szyfrowanie Age X25519, manifest.json.age, procedury odtwarzania platformy i kwarantanna, 15/15 testów PASS w privacy-restore.py i platform-recovery.py; decyzja D09 zatwierdzona |
| [A37](#a37) | P2 | UX i dostępność | Modale sklepu nie zapewniają pełnej obsługi klawiaturą, a test wyłącza kontrast | ✅ Wykonane i zweryfikowane — natywne dialogi, klawiatura, kontrast i cztery profile przeglądarek; bez zmian zdjęć |
| [A38](#a38) | P2 | Czat i obsługa klienta | Czat nadpisuje odpowiedź, a kolejna odpowiedź może nie wysłać nowego e-maila | ✅ Wykonane i zweryfikowane — historia i outbox per wpis |
| [A39](#a39) | P2 | Prywatność i kontrakty | Publiczny DTO opinii ujawnia dane administracyjne i identyfikatory zakupu | ✅ Wykonane i zweryfikowane |
| [A40](#a40) | P3 | Frontend i wydajność zasobów | Zdjęcia produktów są duże, a polityka zasobów nie wykorzystuje wersjonowania | ✅ Wykonane lokalnie (zakres kodu) — gzip statyczny, rewalidacja ETag, ochrona HTML/noindex (12/12 testów HTTP PASS); domena publiczna U40-02 wdrożeniowa; zdjęcia formalnie odłożone decyzją użytkownika |
| [A41](#a41) | P3 | Architektura i koszty | Koszt 15 usług i 14 instancji PostgreSQL wymaga uzasadnienia skalą | ✅ Rozstrzygnięte — formalna decyzja użytkownika o utrzymaniu architektury 14 mikroserwisów Spring Boot + API Gateway + 14 baz PostgreSQL; konsolidacja odrzucona |
| [A42](#a42) | P3 | Utrzymanie kodu | Duplikacje konfiguracji i duże pliki zwiększają koszt każdej poprawki | ✅ Wykonane i zweryfikowane — A42.1–A42.21: wspólna infrastruktura, klient/sesje, moduły ES sklepu/CC, moduły Python i bramki struktury; pełny odbiór 27.10.2026 |
| [A43](#a43) | P3 | Dane i retencja | Dane techniczne i zdarzenia rosną bez zdefiniowanego cyklu życia | ✅ Wykonane w repozytorium (fail-closed) — pakiety 114–151, 161, 163, 164: protokół D02 z blokadą wykonawców BLOCKED_FAIL_CLOSED (0 mutacji audytora), rozdzielenie retencji poświadczeń (aktywna w customer-service) od komercyjnej D02, audytor gotowości (7/7 PASS), zatwierdzone decyzje operacyjne D01–D17 przy zachowaniu bezpiecznych kontraktów bazowych (kod 2 dla --require-ready) |
| [A44](#a44) | P3 | CI i łańcuch dostaw | Warto skrócić i ustabilizować budowanie oraz wcześniej wykrywać problemy obrazów | ✅ Wykonane w repozytorium — A44.1–A44.3 optymalizacja Dockerfile, cache, digest, SBOM, skrypt walidacji ci-images-check.py (PASS); rzeczywiste uruchomienie na GitHub Actions to faza CI po wypchnięciu gałęzi |
| [A45](#a45) | P3 | UX, język i dokumentacja | Sklep miesza interfejs klienta z instrukcjami technicznymi, a dokumentacja miejscami odstaje od kodu | ✅ Wykonane i zweryfikowane — pełna synchronizacja dokumentacji z kodem, usunięcie niedozwolonych schematów linków file:///, spójne przekazanie w PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md oraz AUDIT_0.1_POSTEP.md |


## Uzasadnienia i zalecenia


<a id="a01"></a>
### A01 · P1 · Brak autoryzacji dostępu do danych klientów poza Customer Service

**Wdrożenie Audit_0.1: wykonane i zweryfikowane.** Szczegóły: [postęp prac](AUDIT_0.1_POSTEP.md).

**Obszar:** Bezpieczeństwo. **Status:** Potwierdzone w kodzie.

**Dowody:** [OrderController.java:46](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/web/OrderController.java:46>), [InvoiceController.java:91](<C:/Dysk D/stuff/Mini Order Platform/billing-service/src/main/java/org/demo/billingservice/web/InvoiceController.java:91>), [pom.xml:28](<C:/Dysk D/stuff/Mini Order Platform/api-gateway/pom.xml:28>).

**Problem i skutek:** Gateway przekazuje żądania, a usługi zamówień, faktur, czatu, zwrotów i wishlist nie weryfikują JWT ani właściciela zasobu. Wskazanie customerId/orderId w URL wystarcza do wyboru danych. Kontrola właściciela w frontendzie nie zabezpiecza API.

**Zalecenie:** Weryfikować tożsamość na wejściu i uprawnienia do konkretnego obiektu w usłudze. Identyfikator klienta wyprowadzać z tokena; dla zakupów gościnnych stosować odrębny, niezgadywalny token dostępu.

**Warunek odbioru:** Brak tokena → 401; klient A odczytujący dane B → 403/404; własne dane → 200. Sprawdzić też bezpośredni dostęp do usług.


Kontrola dostępu do konkretnego obiektu odpowiada mechanizmowi opisanemu przez [OWASP API1: BOLA](https://api-security.owasp.org/editions/2023/en/0xa1-broken-object-level-authorization/).


<a id="a02"></a>
### A02 · P1 · Operacje administracyjne dostępne przez publiczne trasy

**Wdrożenie Audit_0.1: wykonane i zweryfikowane.** Szczegóły: [postęp prac](AUDIT_0.1_POSTEP.md).

**Obszar:** Bezpieczeństwo. **Status:** Potwierdzone w kodzie.

**Dowody:** [GatewayController.java:69](<C:/Dysk D/stuff/Mini Order Platform/api-gateway/src/main/java/org/demo/apigateway/web/GatewayController.java:69>), [OrderController.java:65](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/web/OrderController.java:65>), [NotificationController.java:66](<C:/Dysk D/stuff/Mini Order Platform/notification-service/src/main/java/org/demo/notificationservice/web/NotificationController.java:66>).

**Problem i skutek:** Te same trasy udostępniają klientowi m.in. modyfikację produktów, stanu magazynowego i statusów zamówień, moderację, wystawianie faktur, odpowiedzi operatora oraz replay powiadomień. Zmiana zamówienia na PAID jest operacją HTTP bez potwierdzenia pochodzenia z procesu płatności.

**Zalecenie:** Wprowadzić role i uprawnienia operatorów oraz uwierzytelnianie komunikacji między usługami. Rozdzielić publiczny katalog i checkout od operacji administracyjnych; kontrolę egzekwować również za gatewayem.

**Warunek odbioru:** Anonim i zwykły klient nie wykonują operacji operatora; status PAID może nadać wyłącznie uprawniony proces płatności.


<a id="a03"></a>
### A03 · P1 · Parametr URL pozwala zmienić serwer otrzymujący dane logowania

**Wdrożenie Audit_0.1: wykonane i zweryfikowane.** Szczegóły: [postęp prac](AUDIT_0.1_POSTEP.md).

**Obszar:** Bezpieczeństwo. **Status:** Potwierdzone w kodzie.

**Dowody:** [app.js:490](<C:/Dysk D/stuff/Mini Order Platform/frontend/app.js:490>), [app.js:2221](<C:/Dysk D/stuff/Mini Order Platform/frontend/app.js:2221>).

**Problem i skutek:** getApiBase() akceptuje dowolny parametr api z adresu strony. Formularz logowania wysyła następnie e-mail i hasło pod tak wybrany adres. Link do prawdziwej strony sklepu może więc skierować żądanie logowania do obcego serwera obsługującego CORS.

**Zalecenie:** W profilu publicznym dopuszczać wyłącznie skonfigurowany backend tej samej domeny. Zmianę adresu API pozostawić jako świadomą konfigurację deweloperską, z listą dozwolonych originów.

**Warunek odbioru:** Adres z obcym api nie zmienia celu żądań i nie powoduje wysłania hasła poza skonfigurowany backend.


<a id="a04"></a>
### A04 · P1 · Cena i warunki dostawy pochodzą z przeglądarki

**Wdrożenie Audit_0.1: wykonane i zweryfikowane.** Szczegóły: [pakiet 3 i testy odbiorcze](AUDIT_0.1_POSTEP.md#pakiet-3--a04-reguły-zakupu-i-punkty-odbioru).

**Obszar:** Zamówienia i dostawy. **Status:** Potwierdzone w kodzie.

**Dowody:** [OrderService.java:69](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/order/OrderService.java:69>), [CreateOrderRequest.java:28](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/web/CreateOrderRequest.java:28>).

**Problem i skutek:** Order Service sprawdza jedynie, czy deliveryCost nie jest ujemny. Nie odtwarza wyceny ani poprawności metody, terminu i fulfillmentType w Delivery Service. Można zadeklarować zerowy koszt i nieprawidłowy termin. Także próg obowiązkowego konta >1000 zł istnieje jedynie w frontendzie.

**Zalecenie:** Obliczać dostawę po stronie serwera na podstawie faktycznej wartości koszyka i aktywnej oferty; zapisywać wersję wyceny. Jeżeli próg konta jest regułą biznesową, egzekwować go również w API.

**Warunek odbioru:** Ręcznie podana cena 0 nie obniża płatnej dostawy; niepoprawna metoda/data jest odrzucana; kwota zamówienia jest niezależna od danych cenowych klienta.


<a id="a05"></a>
### A05 · P1 · Płatności są symulacją i akceptują dowolną nazwę metody

**Obszar:** Płatności. **Status:** Ograniczenie MVP; blokada użycia z realnymi płatnościami.

**Otwarte — ręczna czynność użytkownika:** w A05 dodano `STRIPE_SECRET_KEY`
i `STRIPE_WEBHOOK_SECRET`; użytkownik musi dodać obie zmienne do
`dev.env.sops` oraz `test.env.sops`. Zadanie odłożono na później i należy
ujmować je w odpowiedziach o pozostałych wdrożeniach, aż użytkownik potwierdzi
wykonanie. Pełne ścieżki i status: [otwarte sekrety Stripe](#a05-stripe-sekrety).

**Wdrożenie na `Audit_0.1`: częściowo zamknięte.** Metody są zamkniętym
zbiorem; nieznana metoda daje 400 przed zapisem. Adapter `PaymentGateway`
wydziela dostawcę od orkiestracji. Symulacja jest dostępna tylko w `dev`/`test`;
obecność `prod` blokuje jej uruchomienie także z dodatkowym `dev`. Dodano
hostowany Stripe Checkout dla kart, idempotentną sesję, podpisany webhook,
kontrolę trybu/kwoty/waluty, trwały receipt zdarzenia i bezpieczny powrót
przeglądarki ze Stripe.
Aktywna rezerwacja zapasu jest przedłużana przed utworzeniem sesji; Inventory
potwierdza zapas przed zmianą zamówienia na `PAID`. Nieopłacona sesja wygasająca
kończy próbę i inicjuje kompensację A11. Pakiet 24 dodaje kanoniczny odczyt
Stripe, trwały dowód zapłaty, odzyskanie przerwania po `PAID`, pełne refundacje
z rejestrem i ograniczonym retry oraz blokadę finansową Order. Zapłacony
Checkout z definitywnie utraconą rezerwacją otrzymuje zwrot i kompensację zapasu,
a anulowanie następuje po potwierdzeniu tych kroków. Zwrot operatora blokuje
realizację i pokazuje osobno sukces finansowy oraz dalsze uzgodnienie dokumentów
i pozostałych skutków zakupu (granica MVP A11). Nie oferuje częściowego zwrotu
ani automatycznego odwracania już wysłanych zamówień.

**Część programistyczna A05 została wdrożona.** Pozostają ręczne sekrety obu
profili, konfiguracja endpointu/Stripe CLI i odbiór zewnętrznego sandboxu.
Testy lokalnej atrapy API nie są przedstawiane jako test konta Stripe ani
wdrożenie dev/prod. Procedura i regresje: [A05](A05_STRIPE_CHECKOUT.md).

**Dowody:** [PaymentService.java:149](<C:/Dysk D/stuff/Mini Order Platform/payment-service/src/main/java/org/demo/paymentservice/payment/PaymentService.java:149>), [CreatePaymentRequest.java:16](<C:/Dysk D/stuff/Mini Order Platform/payment-service/src/main/java/org/demo/paymentservice/web/CreatePaymentRequest.java:16>).

**Problem i skutek:** Każda nieodroczona metoda, przy simulateFailure=false, kończy się sukcesem bez autoryzacji operatora płatności. Nazwa metody ma walidację długości, ale nie listy obsługiwanych wartości. Mechanizm jest użyteczny w demo, jednak nie stanowi integracji płatniczej.

**Zalecenie:** Oddzielić adapter symulacyjny od produkcyjnego. Metody modelować jako zamknięty zbiór; rzeczywisty sukces wyprowadzać z potwierdzenia dostawcy, z weryfikacją podpisu, kwoty, waluty i idempotencji.

**Warunek odbioru:** Nieznana metoda → 400; sam POST klienta nie oznacza udanej realnej płatności; powtórzone potwierdzenie dostawcy nie powiela skutków.


<a id="a06"></a>
### A06 · P1 · Zapis zamówienia i uruchomienie sagi nie są atomowe

**Wdrożenie Audit_0.1, pakiet 21:** atomowy zapis, blokowane i wersjonowane przejścia,
ochrona przed starym wykonawcą, ograniczony skaner rekordów bez sagi oraz jawne
uzgodnienia historycznych zamówień. Niejednoznaczna historia nie jest automatycznie
uznawana za opłaconą ani odtwarzana z domysłów. [Protokół i odbiór](A06_SPOJNOSC_ZAMOWIEN.md).

**Obszar:** Zamówienia i spójność. **Status:** Potwierdzona luka transakcyjna; scenariusz awarii wymaga testu.

**Dowody:** [OrderService.java:107](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/order/OrderService.java:107>), [OrderSagaService.java:46](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/order/OrderSagaService.java:46>), [OrderService.java:179](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/order/OrderService.java:179>).

**Problem i skutek:** OrderService.create() zapisuje zamówienie, a potem osobno zapisuje sagę. Awaria pomiędzy tymi commitami pozostawia zamówienie bez procesu odzyskiwania. Zmiany statusu zamówienia również nie mają wersjonowania ani blokady chroniącej przed równoległą płatnością i anulowaniem.

**Zalecenie:** Zapisać zamówienie i początkowy stan sagi w jednej krótkiej transakcji. Zmiany statusu wykonywać warunkowo, z wersją lub blokadą; dodać wykrywanie osieroconych zamówień.

**Warunek odbioru:** Przerwanie procesu po zapisie zamówienia nie pozostawia rekordu bez sagi; równoległe PAID/CANCELLED nie nadpisują się bez wykrycia konfliktu.


<a id="a07"></a>
### A07 · P1 · Potwierdzanie i zwalnianie rezerwacji nie blokuje rekordu rezerwacji

**Wdrożenie Audit_0.1: wykonane i zweryfikowane.** Szczegóły: [postęp prac](AUDIT_0.1_POSTEP.md).

**Obszar:** Magazyn. **Status:** Ryzyko współbieżności wynikające z kodu.

**Dowody:** [InventoryService.java:114](<C:/Dysk D/stuff/Mini Order Platform/inventory-service/src/main/java/org/demo/inventoryservice/inventory/InventoryService.java:114>), [InventoryService.java:169](<C:/Dysk D/stuff/Mini Order Platform/inventory-service/src/main/java/org/demo/inventoryservice/inventory/InventoryService.java:169>), [InventoryReservationRepository.java:9](<C:/Dysk D/stuff/Mini Order Platform/inventory-service/src/main/java/org/demo/inventoryservice/inventory/InventoryReservationRepository.java:9>).

**Problem i skutek:** Blokowane są pozycje magazynowe, lecz stan rezerwacji jest wcześniej odczytywany bez blokady/wersji. Dwa żądania mogą oba zobaczyć RESERVED. Jeśli istnieją inne rezerwacje produktu, ponowne release/confirm może zużyć ich łączny reservedQuantity. Kolejność blokowania produktów zależy też od kolejności koszyka, co sprzyja deadlockom.

**Zalecenie:** Najpierw blokować rezerwację, ponownie sprawdzać jej stan, następnie blokować produkty w stałej kolejności. Obsłużyć wyścig z cleanupem i równoległe utworzenie rezerwacji dla tego samego orderId.

**Warunek odbioru:** Równoległe confirm/release/cleanup w PostgreSQL zmieniają ilości dokładnie raz i nigdy nie naruszają rezerwacji innego zamówienia.


<a id="a08"></a>
### A08 · P1 · Blokada płatności działa tylko w jednej instancji JVM

**Obszar:** Płatności. **Status:** Ryzyko współbieżności wynikające z kodu.

**Wdrożenie Audit_0.1 (25.10.2026): wykonane i zweryfikowane.** Koordynacja SQL,
obsługa wyścigu pierwszego zapisu i kolizji klucza, niezmienny skrót polecenia,
trwałe zatwierdzenie dostawcy, odtwarzanie wyników oraz ochrona przed starym
właścicielem. Regresja dwóch JVM obejmuje awarie przed/po zapisie zatwierdzenia,
utratę odpowiedzi i pojedyncze skutki. Wymaga idempotentnego adaptera;
rzeczywista integracja pozostaje w A05, trwałe zadania skutków w A09.
[Procedura i dowody A08](A08_PLATNOSCI_REPLIKI.md).

**Dowody wdrożenia:** [PaymentStateStore.java](../payment-service/src/main/java/org/demo/paymentservice/payment/PaymentStateStore.java), [PaymentService.java](../payment-service/src/main/java/org/demo/paymentservice/payment/PaymentService.java), [regresja dwóch procesów](../scripts/qa/payment-replicas-e2e.py).

**Problem i skutek:** ReentrantLock ogranicza wyścig w pojedynczej instancji. Dwie repliki mogą jednocześnie odczytać brak płatności i rozpocząć orkiestrację. Ograniczenia UNIQUE pomagają chronić rekordy, ale nie zastępują obsługi konfliktu i bezpiecznego wznowienia całej operacji.

**Zalecenie:** Koordynować próbę płatności przez bazę: atomowe utworzenie/claim, wersjonowanie stanu, obsługę konfliktu jako odczytu istniejącej próby. Unikać trzymania blokady DB przez wszystkie wywołania HTTP.

**Warunek odbioru:** Dwa procesy przyjmujące jednocześnie płatność tego samego zamówienia zwracają ten sam wynik i wykonują skutki raz.


<a id="a09"></a>
### A09 · P1 · Po sukcesie płatności można trwale utracić fakturę, przesyłkę lub rozliczenie kuponu

**Obszar:** Płatności i zdarzenia. **Status:** Potwierdzone w kodzie.

**Wdrożenie Audit_0.1 (25.10.2026): wykonane i zweryfikowane.** Wynik SUCCEEDED
i zamiary skutków są zapisywane atomowo. Oddzielne zadania z claim/lease,
retry/DLQ/replay odzyskują fakturę, przesyłkę i kupon niezależnie od e-maila.
Powiadomienie z PDF czeka na fakturę. Regresja dwóch JVM i rzeczywistych trzech
zależności obejmuje ich awarię oraz przerwanie procesu po skutku/przed commitem.
Historyczne płatności bez zapisanych danych nabywcy wymagają uzgodnienia tych
danych; migracja ich nie fabrykuje. [Procedura i dowody](A09_TRWALE_SKUTKI_PLATNOSCI.md).

**Dowody wdrożenia:** [FollowUpStateStore.java](../payment-service/src/main/java/org/demo/paymentservice/followup/FollowUpStateStore.java), [FollowUpRunner.java](../payment-service/src/main/java/org/demo/paymentservice/followup/FollowUpRunner.java), [regresja odzyskiwania skutków](../scripts/qa/payment-follow-ups-e2e.py).

**Problem i skutek:** Błędy redeemCoupon(), ensureTracking() i issue() są logowane, po czym płatność pozostaje SUCCEEDED. Powtórzona udana płatność wraca od razu i nie naprawia tych skutków. Konsument Billing Service jedynie sprawdza istnienie faktury i potwierdza zdarzenie; nie wystawia brakującego dokumentu. Zdarzenie płatności nie powstaje też bez notificationRecipient.

**Zalecenie:** Zapisywać niezależne, trwałe zadania/zdarzenia domenowe dla rozliczenia rabatu, realizacji i faktury, niezależnie od wysyłki e-maila. Konsument powinien faktycznie realizować zadanie, z retry, idempotencją i DLQ.

**Warunek odbioru:** Wyłączenie Delivery/Billing/Promotion podczas płatności i późniejsze uruchomienie kończy się automatycznym uzupełnieniem wszystkich skutków, bez kolejnego kliknięcia klienta.


<a id="a10"></a>
### A10 · P1 · Płatność przy odbiorze pozostaje poza pełnym procesem realizacji

**Obszar:** Płatności i realizacja. **Status:** Potwierdzona luka procesu.

**Wdrożenie Audit_0.1 (25.10.2026): wykonane i zweryfikowane.** Pobranie
potwierdza zapas przed przyjęciem, uruchamia tracking i zachowuje odrębny stan
COLLECTION_DUE podczas realizacji. Operator rejestruje dokładną kwotę i unikalny
dowód po doręczeniu/odbiorze. Rozliczenie Order, faktura i powiadomienie korzystają
z trwałych zadań; faktura/opinia nie powstają przed zapłatą. Regresja obejmuje
rzeczywisty TTL i awarię procesu między rozliczeniem Order a commitem zadania.
[Procedura i wyniki A10](A10_POBRANIE_I_REALIZACJA.md).

**Dowody:** [PaymentService.java:109](<C:/Dysk D/stuff/Mini Order Platform/payment-service/src/main/java/org/demo/paymentservice/payment/PaymentService.java:109>), [InventoryService.java:161](<C:/Dysk D/stuff/Mini Order Platform/inventory-service/src/main/java/org/demo/inventoryservice/inventory/InventoryService.java:161>), [PaymentController.java:20](<C:/Dysk D/stuff/Mini Order Platform/payment-service/src/main/java/org/demo/paymentservice/web/PaymentController.java:20>).

**Problem i skutek:** Ścieżka CASH_ON_DELIVERY/CASH_AT_PICKUP zapisuje PENDING i wraca przed utworzeniem trackingu oraz potwierdzeniem zapasu. Rezerwacja po domyślnych 15 minutach jest zwalniana. Brakuje osobnego procesu przyjęcia do realizacji i zarejestrowania pobrania należności.

**Zalecenie:** Rozdzielić status płatności od statusu przyjęcia i realizacji zamówienia. Dla pobrania zatwierdzać zapas oraz uruchamiać dostawę bez wymagania PAID; przewidzieć autoryzowaną operację rozliczenia odbioru.

**Warunek odbioru:** Zamówienie za pobraniem zachowuje prawidłowy zapas po TTL, ma tracking i może zostać dostarczone oraz rozliczone.


<a id="a11"></a>
### A11 · P1 · Anulowanie opłaconego zamówienia nie odwraca skutków biznesowych

**Wdrożenie Audit_0.1, pakiet 20:** zrealizowano zalecany poniżej wariant MVP:
blokadę anulowania opłaconych/przyjętych zamówień oraz trwałe, skoordynowane
anulowanie nieopłaconych. Zabezpieczono wyścig z pierwszą płatnością, stare FAILED
i awarię po autoryzacji. A05 dodaje finansowe pełne zwroty Stripe oraz
kompensację niezrealizowanego Checkoutu. Pełne odwracanie dokumentów, kuponu
i pozostałych skutków opłaconego zakupu nadal wymaga osobnego procesu domenowego.
[Zakres, protokół i regresje](A11_BEZPIECZNE_ANULOWANIE.md).

**Obszar:** Anulowania i kompensacja. **Status:** Potwierdzone w kodzie.

**Dowody:** [OrderService.java:206](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/order/OrderService.java:206>), [OrderSagaService.java:90](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/order/OrderSagaService.java:90>).

**Problem i skutek:** Dozwolone jest PAID → CANCELLED. Dla sagi COMPLETED kompensacja jedynie zmienia status zamówienia. Płatność pozostaje udana; sprzedany zapas, przesyłka, rabat i dokument nie są obsługiwane przez proces odwrócenia.

**Zalecenie:** Wprowadzić jawny proces anulowania/zwrotu z krokami finansowymi, logistycznymi i magazynowymi oraz retry. Jeśli zakres MVP go nie obejmuje, blokować pozornie kompletne anulowanie opłaconych zamówień.

**Warunek odbioru:** Anulowanie po płatności daje zdefiniowane stany płatności, zapasu, przesyłki i dokumentów; częściowa awaria jest widoczna i wznawialna.


<a id="a12"></a>
### A12 · P1 · Faktura nie jest weryfikowana względem opłaconego zamówienia

**Obszar:** Fakturowanie. **Status:** Potwierdzone w kodzie.

**Wdrożenie na `Audit_0.1`: naprawione i zweryfikowane.** Billing pobiera
zamówienie po uwierzytelnionym połączeniu wewnętrznym przed otwarciem transakcji.
Akceptuje `PAID`, `SHIPPED`, `COMPLETED`; porównuje właściciela, e-mail, walutę
PLN, komplet pozycji, ilości, ceny, dostawę, rabat i sumy. Zapisuje dane finansowe
ze snapshotu zamówienia, a dane adresowe nabywcy z żądania. Nieistniejące
zamówienie daje 404, nieopłacone 409, niespójne dane 400, brak weryfikacji 503.
Nie powstaje dokument po odmowie. Sekwencyjne i równoległe ponowienia zwracają
ten sam dokument; retry nie uzupełnia ani nie nadpisuje historycznych faktur.
Control Center wymaga działającego API i nie wymyśla brakujących pozycji.

**Dowody:** [BillingService.java:49](<C:/Dysk D/stuff/Mini Order Platform/billing-service/src/main/java/org/demo/billingservice/billing/BillingService.java:49>), [IssueInvoiceRequest.java:18](<C:/Dysk D/stuff/Mini Order Platform/billing-service/src/main/java/org/demo/billingservice/web/IssueInvoiceRequest.java:18>).

**Problem i skutek:** Billing Service ufa orderId, grossAmount, pozycjom, rabatowi i dostawie z requestu. Nie sprawdza istnienia/statusu zamówienia ani zgodności sumy pozycji z kwotą dokumentu. Uprawnienia z A02 są konieczne, ale sama rola nie zastąpi kontroli spójności kwot.

**Zalecenie:** Wystawiać dokument z autorytatywnego snapshotu zamówienia i rozliczenia; weryfikować sumy, walutę i reguły zaokrągleń. Oddzielić dane nabywcy od kwot kontrolowanych przez backend.

**Warunek odbioru:** Nieistniejące/nieopłacone zamówienie i niespójna suma pozycji nie tworzą faktury; powtórzenie zwraca ten sam dokument.


<a id="a13"></a>
### A13 · P2 · Kolejne korekty nie tworzą jednoznacznego salda dokumentu

**Wdrożenie Audit_0.1 (25.10.2026): wykonane i zweryfikowane.** Billing sumuje
delty pod blokadą faktury, sprawdza wersję i stały klucz polecenia. API, PDF i CC
pokazują skumulowane saldo, ponowienia nie tworzą dokumentu ani zdarzenia,
a wyścig różnych korekt wymaga odczytu nowego salda. V8 zachowuje historyczne
kwoty i oznacza stary model. Regresja obejmuje PostgreSQL, dwie instancje,
migrację V7→V8 i przeładowanie CC po nieznanym wyniku.
[Zakres, API i wyniki A13](A13_SALDO_KOREKT.md).

**Obszar:** Fakturowanie. **Status:** Potwierdzone w kodzie.

**Dowody:** [BillingService.java:89](<C:/Dysk D/stuff/Mini Order Platform/billing-service/src/main/java/org/demo/billingservice/billing/BillingService.java:89>), [IssueCorrectionRequest.java:13](<C:/Dysk D/stuff/Mini Order Platform/billing-service/src/main/java/org/demo/billingservice/web/IssueCorrectionRequest.java:13>).

**Problem i skutek:** Każda korekta liczy finalGross od kwoty pierwotnej i pomija poprzednie korekty. Dla faktury 100 zł i kolejnych delt −10 oraz −20 ostatni dokument wskazuje 80 zł, choć łączne saldo delt wynosi 70 zł. Kontrakt nie rozróżnia zastępowania korekty od dodawania kolejnej delty. Brakuje też granic długości powodu i precyzji kwoty na wejściu.

**Zalecenie:** Ustalić model: łańcuch korekt albo niezmienny rejestr delt z obliczanym saldem. Chronić współbieżność i idempotencję; dopasować walidację do pól bazy i przyjętego modelu kwot.

**Warunek odbioru:** Dwie korekty i ich ponowienia dają przewidywalne saldo w API, PDF i panelu; równoległe korekty nie gubią zmian.


<a id="a14"></a>
### A14 · P2 · Limit kuponu jest sprawdzany zbyt późno względem przyznanego rabatu

**Realizacja — 26.10.2026:** Order rezerwuje użycie i zapisaną wycenę przed
potwierdzeniem zapasu/zamówienia. Promotion serializuje limit w SQL i atomowo
przenosi użycie z zarezerwowanego do wykorzystanego. Anulowanie i worker
wygaśnięcia najpierw blokują płatność; wynik niepewny nie zwalnia limitu.
Trwałe tombstone i ponowienia chronią utracone odpowiedzi i spóźnione żądania.
Stare niepotwierdzone wyceny wymagają weryfikacji przed nową płatnością;
operator ma podgląd i kontrolowane uzgodnienie w CC/API/CLI.
[Zakres, migracje, API i wyniki A14](A14_REZERWACJE_KUPONOW.md).

**Obszar:** Promocje. **Status:** Potwierdzona luka procesu.

**Dowody:** [PromotionService.java:111](<C:/Dysk D/stuff/Mini Order Platform/promotion-service/src/main/java/org/demo/promotionservice/promotion/PromotionService.java:111>), [PromotionService.java:138](<C:/Dysk D/stuff/Mini Order Platform/promotion-service/src/main/java/org/demo/promotionservice/promotion/PromotionService.java:138>), [PaymentService.java:186](<C:/Dysk D/stuff/Mini Order Platform/payment-service/src/main/java/org/demo/paymentservice/payment/PaymentService.java:186>).

**Problem i skutek:** Wiele zamówień może otrzymać rabat po równoległej walidacji ostatniego dostępnego użycia. Atomowy licznik przy redeem chroni sam limit, ale redeem następuje dopiero po nadaniu PAID; jego odmowa jest tylko logowana. Zniżka została już uwzględniona w zapłaconej kwocie.

**Zalecenie:** Rezerwować użycie kuponu przy akceptacji zamówienia, z TTL i idempotentnym zatwierdzeniem/zwolnieniem, albo jawnie zmienić politykę limitu. Nie naprawiać tego przez dopłatę po zakończeniu zakupu.

**Warunek odbioru:** Przy jednym wolnym użyciu i dwóch równoległych zakupach system przyznaje rabat zgodnie z zadeklarowaną polityką, bez rozbieżności licznika i zapłaconych kwot.


<a id="a15"></a>
### A15 · P2 · Klucz zamówienia nie jest związany z treścią i właścicielem żądania

**Realizacja — 26.10.2026:** Wersjonowany skrót koszyka i danych checkoutu
oraz zakres klienta/hash tokenu gościa są zapisywane atomowo z Order i sagą.
Zmieniona treść daje 409, a wyścig pierwszego zapisu odczytuje zwycięzcę
po wycofaniu przegranej transakcji. Ponowienie korzysta z zapisanej wyceny
i może wznowić istniejącą sagę po awarii; aktywny lease daje kontrolowane 409.
Klucz ma limit 1–100 znaków. V20 zachowuje historyczne rekordy bez zgadywania
zamiaru; starsze klucze wymagają sprawdzenia zakupu. Sklep zachowuje klucz
przy zmianie wyliczonego kosztu i sprawdza zapis próby przed pierwszym POST.
[Zakres, kontrakt, migracja i wyniki A15](A15_IDEMPOTENCJA_ZAMOWIEN.md).

**Obszar:** API i idempotencja. **Status:** Potwierdzone w kodzie.

**Dowody:** [OrderService.java:54](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/order/OrderService.java:54>), [OrderController.java:34](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/web/OrderController.java:34>).

**Problem i skutek:** Powtórzenie klucza zwraca istniejące zamówienie bez porównania koszyka, danych dostawy i klienta. Przy równoległym pierwszym żądaniu ograniczenie UNIQUE może zakończyć się błędem zamiast kanoniczną odpowiedzią. Długość klucza z nagłówka nie jest dopasowana do kolumny 100 znaków.

**Zalecenie:** Zapisywać skrót znormalizowanego requestu i zakres klienta/próby zakupu. Inna treść z tym samym kluczem → 409; konflikt równoległego INSERT → odczyt istniejącego wyniku; walidować długość nagłówka.

**Warunek odbioru:** Ten sam klucz i ta sama treść są bezpieczne; zmieniona treść → 409; dwa równoległe żądania nie tworzą drugiego zamówienia ani przypadkowego 500.


<a id="a16"></a>
### A16 · P2 · Zgłoszenie zwrotu nie sprawdza zamówienia ani dozwolonego przejścia statusu

**Obszar:** Zwroty i reklamacje. **Status na Audit_0.1:** ✅ **Wykonane i zweryfikowane w repozytorium.**  
Implementacja w Pakietach 160, 162 i 163 domknęła pełną orkiestrację skutków domenowych, naprawiając wszystkie 4 ustalenia techniczne (R01–R04).

**Dowody bazowe:** `returns-service/src/main/java/org/demo/returnsservice/returns/ReturnService.java`.

**Problem i skutek:** Zapis opierał się wyłącznie na identyfikatorach z żądania. Brak weryfikacji zakupu pozwalał przyjąć sprawę dla cudzego, nieistniejącego lub nieopłaconego zamówienia, a dowolne statusy uniemożliwiały bezpieczny audyt. Pozostawało ryzyko pomylenia decyzji administracyjnej z wykonaniem zwrotu i odwróceniem skutków zakupu.

**Wdrożenie w A16.1 (Pakiet 22):** Backend potwierdza kanoniczny zakup, właściciela, e-mail, płatność i dozwolony stan realizacji. Zapisuje wybrane pozycje/ilości jako migawkę, odrzuca zmianę zamiaru, egzekwuje maszynę `REQUESTED → UNDER_REVIEW → APPROVED | REJECTED` i prowadzi wersjonowany dziennik operatora. `COMPLETED` nie jest dostępne bezpośrednio; zatwierdzenie wymaga jawnego uzgodnienia skutków. Szczegóły: [postęp Audit_0.1](AUDIT_0.1_POSTEP.md) oraz [dokumentacja A16.1](A16_ZWROTY_REKLAMACJE.md).

**Pełne wdrożenie orkiestracji skutków domenowych (Pakiety 160, 162, 163 oraz poprawki przeglądu 03.11.2026):**
1. **Automatyzacja skutków domenowych (Pakiet 160):** Wdrożono komponent `ReturnDomainEffectsService` orkiestrujący 3 skutki:
   - Magazyn (`inventory-service`): restock pozycji zamówienia (`/api/internal/inventory/restock`),
   - Fakturowanie (`billing-service`): wystawienie korekty faktury (`/api/internal/billing/credit-memos`),
   - Płatności (`payment-service`): zlecenie refundacji Stripe (`/api/internal/payments/refund`).
2. **Naprawy R01–R04 z przeglądu technicznego (Pakiet 162):**
   - **R01 (Infrastruktura i uprawnienia):** Dodano zmienne środowiskowe URL (`SERVICES_PAYMENT_URL`, `SERVICES_BILLING_URL`, `SERVICES_INVENTORY_URL`), porty i relacje sieciowe w `deploy/components.json` i `deploy/k8s/base/network-policies.yaml`.
   - **R02 (Prawdziwe rozliczenie finansowe):** Wymóg statusu `SUCCEEDED` dla refundacji Stripe; statusy `PENDING`, `FAILED` lub brak chargeId rzucają kontrolowany wyjątek i uniemożliwiają przejście sprawy do `COMPLETED`.
   - **R03 (Brak dublowania zapasu przy retry):** Flaga `restocked = true` w encji zwrotu oraz deterministyczne klucze per-linia gwarantują, że ponowienie orkiestracji po częściowej awarii (np. błąd sieci przy refundacji) nie przyjmie drugi raz tego samego towaru na stan magazynu.
   - **R04 (Ścisłe kwoty bez fikcyjnych fallbacków):** Wyliczanie kwoty refundacji ściśle z pozycji i kwot zamówienia (`orderClient.getOrder()`), bez fallbacków do fikcyjnych wartości; deterministyczne klucze idempotencji `refund-return-{returnId}` i `cm-return-{returnId}`.
3. **Usunięcie problemu kolejności skutków domenowych (Przegląd 03.11.2026):**
   - **Dwufazowa orkiestracja (Faza 1 Pre-checks / Faza 2 Mutacje):** W `ReturnDomainEffectsService` odczytowe sprawdzenie istnienia płatności, statusu (`SUCCEEDED`/`REFUNDED`), zgodności kwoty, kwalifikowalności do refundacji Stripe (`stripeCardEligible`) oraz wystarczalności salda faktury w Billing (`billing.balance()`) jest wykonywane **przed jakąkolwiek mutacją domenową**.
   - **Ochrona przed wyciekiem stanu:** Dla płatności `CASH_ON_DELIVERY` bez potwierdzonej refundacji lub nieopłaconych zamówień proces kończy się natychmiastowym wyjątkiem `RETURN_NON_STRIPE_REFUND_UNCONFIRMED` / `RETURN_PAYMENT_NOT_SETTLED` bez wywołania `inventory.restock(...)` ani `billing.issueCorrection(...)`, gwarantując nienaruszalność magazynu i faktur.
4. **Weryfikacja:** Komplet 36/36 testów jednostkowych i integracyjnych Java w module `returns-service` (w tym `ReturnDomainEffectsTest` 11/11, `ReturnOrderClientTest` 2/2, `ReturnWorkflowTest` 13/13) PASS. [Dokumentacja A16 P160–P162](A16_SKUTKI_DOMENOWE_ZWROTOW_160.md).


<a id="a17"></a>
### A17 · P2 · Zakup przestaje być traktowany jako opłacony po zmianie statusu na SHIPPED

**Wdrożenie Audit_0.1 (26.10.2026): wykonane i zweryfikowane.** Opinie,
rekomendacje i bestsellery obejmują PAID/SHIPPED/COMPLETED, z wykluczeniem
zwrotów, blokad oraz nierozliczonego pobrania. Delivery zapisuje trwały stan
synchronizacji, a Order stosuje rewizje pod blokadą i nie zmienia zapłaty
na podstawie dostawy. Regresja PostgreSQL obejmuje dwie instancje Delivery,
migrację historii, utratę odpowiedzi, stary komunikat i awarię po commicie.
[Zakres, API, operator i wyniki A17](A17_CYKL_OPLACONEGO_ZAKUPU.md).

**Obszar:** Cykl życia zamówienia. **Status:** Potwierdzone w kodzie.

**Dowody:** [ReviewService.java:59](<C:/Dysk D/stuff/Mini Order Platform/review-service/src/main/java/org/demo/reviewservice/review/ReviewService.java:59>), [RecommendationService.java:49](<C:/Dysk D/stuff/Mini Order Platform/recommendation-service/src/main/java/org/demo/recommendationservice/recommendation/RecommendationService.java:49>), [OrderRepository.java:25](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/order/OrderRepository.java:25>).

**Problem i skutek:** Weryfikacja uprawnienia do opinii, historia zakupów dla rekomendacji i ranking bestsellerów uwzględniają dokładnie PAID. Po wysłaniu lub zakończeniu zamówienia jego zakup znika z tych obliczeń. Delivery Service przechowuje ponadto oddzielny status przesyłki bez jawnego procesu synchronizacji z Order Service.

**Zalecenie:** Oddzielić stan płatności od realizacji. Doraźnie uwzględniać zdefiniowany zbiór stanów zakupionych i wykluczać zwroty/anulowania; docelowo bazować na faktach płatności i realizacji.

**Warunek odbioru:** Ten sam zakup jest widoczny w opiniach, rekomendacjach i bestsellerach przed wysyłką i po zakończeniu; zdarzenie dostawy aktualizuje właściwy stan.


<a id="a18"></a>
### A18 · P2 · Historia cen jest niepełna, a brak ceny może być wyświetlony jako 0 zł

**Obszar:** Promocje i prezentacja cen. **Status:** Wdrożone lokalnie w pakiecie 30 i zweryfikowane w pakiecie 31 (usługi, PostgreSQL, Chromium oraz OpenAPI); pełne 30 dni historii będą dostępne po zebraniu 30 kompletnych dat.

**Dowody:** [PromotionService.java:43](<C:/Dysk D/stuff/Mini Order Platform/promotion-service/src/main/java/org/demo/promotionservice/promotion/PromotionService.java:43>), [product-detail.js:69](<C:/Dysk D/stuff/Mini Order Platform/frontend/product-detail.js:69>), [app.js:705](<C:/Dysk D/stuff/Mini Order Platform/frontend/app.js:705>).

**Problem i skutek:** Historia powstaje przy odczytach/przeliczeniach katalogu, nie przy każdej zmianie obowiązującej ceny. Dzień bez zapytania może nie mieć wpisu, a kolejne zmiany tego samego dnia są pomijane. Frontend wykonuje Number(null), co daje 0 i przechodzi kontrolę Number.isFinite().

**Zalecenie:** Rejestrować zdarzenia obowiązywania ceny niezależnie od odczytów, z datą i wersją. Brak wartości traktować jawnie jako brak danych. Doprecyzować okres obliczenia wskaźnika i zachowanie przy krótkiej historii produktu.

**Warunek odbioru:** Pusta historia nie pokazuje 0 zł; zmiana ceny i dzień bez ruchu nie znikają z historii; wskaźnik odtwarza rzeczywiste ceny. To ocena techniczna wskaźnika, bez opinii o zgodności prawnej.

**Realizacja:** Product V11 rejestruje rewizje i zmiany cen w transakcji oraz codzienne migawki UTC. Promotion korzysta z historii Product, zachowuje dawne wpisy jako obserwacje i nie zalicza ich do pokrycia. Oba miejsca prezentacji wymagają `historyComplete=true`; niepełna historia ma jawny komunikat. Szczegóły wydania i ograniczenia: [A18 — wiarygodna historia cen](A18_HISTORIA_CEN.md).


<a id="a19"></a>
### A19 · P2 · CORS blokuje usuwanie ulubionych w lokalnym układzie dwóch portów

**Wdrożenie Audit_0.1: wykonane i zweryfikowane.** Szczegóły: [pakiet 3 i testy odbiorcze](AUDIT_0.1_POSTEP.md#pakiet-3--a04-reguły-zakupu-i-punkty-odbioru).

**Obszar:** Frontend i API. **Status:** Potwierdzona niespójność konfiguracji.

**Dowody:** [CorsConfig.java:28](<C:/Dysk D/stuff/Mini Order Platform/api-gateway/src/main/java/org/demo/apigateway/config/CorsConfig.java:28>), [app.js:1537](<C:/Dysk D/stuff/Mini Order Platform/frontend/app.js:1537>).

**Problem i skutek:** Gateway zezwala w CORS na GET/POST/PUT/PATCH/OPTIONS, ale pomija DELETE. Frontend na :18070 usuwa wishlistę przez DELETE do :18080, więc przeglądarka może odrzucić preflight. Testy z przechwyconym API nie sprawdzają realnego CORS.

**Zalecenie:** Dodać DELETE do właściwej polityki i sprawdzać zgodność metod kontrolerów, proxy i CORS. Preferować jeden origin również lokalnie, jeśli upraszcza to obsługę sesji.

**Warunek odbioru:** Realny OPTIONS dla DELETE zwraca właściwe nagłówki, a usunięcie ulubionego działa w przeglądarce na :18070.


<a id="a20"></a>
### A20 · P2 · Lista klientów odpytuje nieistniejące kolumny i maskuje błąd jako pustą listę

**Wdrożenie Audit_0.1, pakiet 31: wykonane i zweryfikowane.** CC korzysta z autoryzowanego, stronicowanego API, prezentuje rzeczywiste pola i pokazuje trwały błąd z ponowieniem. Awaria SQL w Customer nie jest maskowana jako `401`. Odbiór na rzeczywistych Customer/Gateway/CC i jednorazowym PostgreSQL potwierdził odczyt, błąd schematu, odzyskanie i niedostępność. Szczegóły: [A20 — lista klientów CC](A20_LISTA_KLIENTOW_CC.md).

**Obszar:** Panel operatora. **Status:** Naprawione lokalnie i zweryfikowane (26.10.2026).

**Dowody:** [server.py:1267](<C:/Dysk D/stuff/Mini Order Platform/scripts/control-center/web/server.py:1267>), [V1__create_customers.sql:1](<C:/Dysk D/stuff/Mini Order Platform/customer-service/src/main/resources/db/migration/V1__create_customers.sql:1>), [server.py:745](<C:/Dysk D/stuff/Mini Order Platform/scripts/control-center/web/server.py:745>).

**Problem i skutek:** get_customers_data() najpierw próbuje nieobsługiwanego/nieautoryzowanego odczytu listy przez /customers, potem wykonuje SQL z username i balance. Migracje tabeli customers nie definiują tych kolumn. psql_query_json() zamienia błąd na [], więc panel może informować o braku klientów mimo istniejących kont.

**Zalecenie:** Dodać autoryzowane, stronicowane API operatora albo poprawić świadomie wydzielony odczyt diagnostyczny. Rozróżniać pusty wynik, niedostępność i błąd zapytania; nie ukrywać awarii jako sukcesu.

**Warunek odbioru:** Dla zasianego klienta panel pokazuje rekord; awaria/niepoprawny schemat daje widoczny błąd, nie komunikat o pustej bazie.


<a id="a21"></a>
### A21 · P1 · Uzupełnianie zapasu może nadpisać rezerwację lub podwójnie dodać towar

**Wdrożenie Audit_0.1, pakiet 22:** atomowe przyjęcia i wersjonowane korekty,
trwałe identyfikatory oraz rejestr operacji. CC zachowuje zamiar przy utracie
odpowiedzi, sprawdza rejestr i nie wykonuje odczyt–dodanie–PATCH ani zapisu SQL.
[Protokół, kolejność wydania i odbiór](A21_PRZYJECIA_MAGAZYNOWE.md).

**Obszar:** Magazyn i panel operatora. **Status:** Ryzyko wynikające z kodu.

**Dowody:** [server.py:1236](<C:/Dysk D/stuff/Mini Order Platform/scripts/control-center/web/server.py:1236>), [InventoryService.java:66](<C:/Dysk D/stuff/Mini Order Platform/inventory-service/src/main/java/org/demo/inventoryservice/inventory/InventoryService.java:66>).

**Problem i skutek:** Panel odczytuje availableQuantity, dodaje quantity i wysyła wartość absolutną przez PATCH. Rezerwacja pomiędzy GET i PATCH zostaje nadpisana. Jeżeli PATCH zapisze zmianę, ale odpowiedź zaginie, fallback SQL dodaje tę samą dostawę ponownie.

**Zalecenie:** Wprowadzić atomową operację przyjęcia dostawy z kluczem idempotencji i rejestrem ruchu magazynowego. Timeout odczytywać przez status tej operacji; usunąć automatyczny zapis SQL po niejednoznacznym błędzie REST.

**Warunek odbioru:** Przyjęcie dostawy równoległe z rezerwacją zachowuje obie zmiany; utrata odpowiedzi nie podwaja przyjęcia.


<a id="a22"></a>
### A22 · P1 · Rotacja JWT może uniemożliwić odszyfrowanie istniejących sekretów MFA

**Wdrożenie Audit_0.1: wykonane i zweryfikowane (24.10.2026).**
Wymagany niezależny klucz AES-256 we wszystkich profilach; szyfrogramy `v1:kid:…`
uwierzytelniają nagłówek. Przy starcie atomowa migracja i kontrola czytelności
wszystkich rekordów TOTP zachowuje aktywność oraz zużyty krok czasowy. Skarbiec
zabezpiecza dawny wynik wyprowadzenia klucza przed rotacją JWT i zachowuje
poprzednie klucze MFA. Brak właściwego klucza blokuje start bez resetu MFA.
Generator JWT zapisuje PKCS#8; aplikacja sprawdza zgodność aktywnej pary RSA.
Test TOTP na PostgreSQL przeszedł po rotacji JWT/MFA i restartach, także po
migracji starego szyfrogramu; stary materiał wycofano skutecznie dopiero po
migracji. Procedura wymaga zatrzymania wszystkich instancji Customer Service:
[klucze, wdrożenie i wycofanie](A22_MFA_KLUCZE_ROTACJA.md).

**Obszar:** Bezpieczeństwo kont. **Status:** Potwierdzona zależność konfiguracji.

**Dowody:** [MfaEncryptionService.java:29](<C:/Dysk D/stuff/Mini Order Platform/customer-service/src/main/java/org/demo/customerservice/mfa/MfaEncryptionService.java:29>), [secrets.sh:241](<C:/Dysk D/stuff/Mini Order Platform/scripts/control-center/lib/secrets.sh:241>).

**Problem i skutek:** Gdy osobny materiał klucza MFA nie jest ustawiony, klucz szyfrowania jest wyprowadzany z aktualnego klucza JWT. Polecenie jwt-rotate wymienia ten klucz. Po restarcie zapisane sekrety TOTP są szyfrogramami starego klucza, a serwis nie ma identyfikatora/zbioru starych kluczy do ich odszyfrowania.

**Zalecenie:** Oddzielić trwały klucz szyfrowania MFA od kluczy podpisujących JWT. Wymagać go również w dev/test z MFA. Rotację szyfrowania realizować przez wersjonowanie klucza i migrację szyfrogramów.

**Warunek odbioru:** Użytkownik z aktywnym TOTP nadal loguje się po rotacji JWT i restarcie usługi; stary materiał szyfrujący jest usuwany dopiero po migracji.


<a id="a23"></a>
### A23 · P2 · Zużywanie refresh tokenów i kodów MFA wymaga kontroli współbieżności

**Wdrożenie Audit_0.1: wykonane i zweryfikowane (24.10.2026).**
Blokada klienta w PostgreSQL serializuje refresh, zużywanie TOTP/recovery i zmiany
stanu MFA między instancjami. Refresh tworzy następcę w tej samej rodzinie;
replay unieważnia rodzinę także przy odpowiedzi 401, bez naruszania innych
logowań. Wyzwania TOTP/WebAuthn są atomowo zużywane w Redis. Konfiguracja TOTP
sprawdza termin w DB i stan w Redis, a regeneracja kodów wymaga nowego TOTP.
Sklep obsługuje wygaśnięcie i serializuje odświeżenia między dokumentami przez
Web Locks, gdy dostępne. Test na dwóch JVM potwierdził jeden dozwolony wynik
wyścigów. Szczegóły: [kontrakt i wdrożenie A23](A23_SESJE_I_MFA.md).

**Obszar:** Sesje i MFA. **Status:** Ryzyko współbieżności oraz potwierdzona luka TTL.

**Dowody:** [AuthService.java:119](<C:/Dysk D/stuff/Mini Order Platform/customer-service/src/main/java/org/demo/customerservice/auth/AuthService.java:119>), [RefreshTokenRepository.java:9](<C:/Dysk D/stuff/Mini Order Platform/customer-service/src/main/java/org/demo/customerservice/auth/RefreshTokenRepository.java:9>), [MfaService.java:110](<C:/Dysk D/stuff/Mini Order Platform/customer-service/src/main/java/org/demo/customerservice/mfa/MfaService.java:110>).

**Problem i skutek:** Refresh odczytuje używalny token, a następnie go unieważnia bez blokady/wersji — dwie próby mogą obie wydać nową sesję. TOTP time-step i recovery code również są odczytywane/zużywane bez atomowej ochrony. Potwierdzenie enrollmentu nie sprawdza 10-minutowego TTL zapisanego w Redis. Regeneracja recovery codes nie wymaga ponownego potwierdzenia silnego czynnika.

**Zalecenie:** Wprowadzić atomowe consume/warunkowy UPDATE lub blokady oraz politykę wykrywania reuse tokenów. Egzekwować TTL enrollmentu i świeże uwierzytelnienie dla zmiany zabezpieczeń konta.

**Warunek odbioru:** Równoległe użycie tego samego tokena/kodu ma jeden dozwolony skutek; enrollment po TTL jest odrzucany; zmiana zabezpieczeń wymaga właściwego potwierdzenia.


<a id="a24"></a>
### A24 · P2 · Rate limiter ufa nagłówkowi klienta i ma nieatomowe TTL

**Wdrożenie Audit_0.1: wykonane i zweryfikowane (24.10.2026).**
Adres połączenia jest źródłem domyślnym. XFF jest sprawdzany od prawej tylko
dla jawnie zaufanych IP/CIDR, bez DNS i bez natywnego przepisywania adresu przez
kontener. Wspólny budżet klienta nie zależy od ID, metody ani aliasu API.
Lua atomowo obsługuje licznik i TTL, także naprawę braku terminu. Catch nie
obejmuje dalszego handlera. CORS poprzedza limiter, więc odpowiedzi 429/503 są
czytelne dla przeglądarki. Testy trzech Gateway z Redis potwierdziły wspólny
limit dwóch JVM, TTL, zaufane proxy oraz oba tryby awarii bez powielania skutków.
Szczegóły: [konfiguracja i wdrożenie A24](A24_LIMITOWANIE_ZADAN.md).

**Obszar:** Gateway i odporność. **Status:** Potwierdzone w kodzie.

**Dowody:** [RateLimitFilter.java:108](<C:/Dysk D/stuff/Mini Order Platform/api-gateway/src/main/java/org/demo/apigateway/security/RateLimitFilter.java:108>), [RateLimitFilter.java:72](<C:/Dysk D/stuff/Mini Order Platform/api-gateway/src/main/java/org/demo/apigateway/security/RateLimitFilter.java:72>), [RateLimitFilter.java:65](<C:/Dysk D/stuff/Mini Order Platform/api-gateway/src/main/java/org/demo/apigateway/security/RateLimitFilter.java:65>).

**Problem i skutek:** Klient może sam podać pierwszy X-Forwarded-For. Limit jest liczony per surowa ścieżka, więc zmiana identyfikatora tworzy nowy koszyk limitu. INCR i EXPIRE nie są atomowe. Ponadto try/catch obejmuje dalszy filterChain; przy nieobsłużonym RuntimeException i fail-open ścieżka może wywołać chain drugi raz.

**Zalecenie:** Ufać forwarded headers wyłącznie od skonfigurowanych proxy, limitować także konto/operację i znormalizowaną trasę. Licznik z TTL wykonywać atomowo. Catch ograniczyć do wywołań Redis i zawsze uruchamiać chain najwyżej raz.

**Warunek odbioru:** Podmiana X-Forwarded-For ani ID w URL nie omija właściwego limitu; awaria Redis/handlera nie powiela operacji; klucze zawsze wygasają.


<a id="a25"></a>
### A25 · P2 · Lokalny panel nie sprawdza Host/Origin przed wydaniem tokena sesji

**Wdrożenie Audit_0.1: wykonane i zweryfikowane (24.10.2026).**
Host/port, Origin i metadane przeglądarki są sprawdzane przed wydaniem sesji
i wykonaniem operacji. Tokeny są niezależne, przypisane do hosta/portu, z TTL,
limitem bezwzględnym i pojemnością magazynu. Nagłówek sesji chroni także GET,
logi, PTY i pobieranie dokumentów; brak tokenów w URL/cookies/localStorage.
Zadania i terminale mają atomowe limity i ograniczony czas życia; timeout,
zamknięcie i przeładowanie CC kończą jego aktywne procesy. Testy HTTP, PTY
na WSL i 7 scenariuszy Chromium potwierdziły poprawny dostęp lokalny oraz
odrzucenie obcego Host/Origin i wygasłych sesji. Wymagany restart CC.
Szczegóły: [sesje, limity i wdrożenie A25](A25_CONTROL_CENTER_SESJE.md).

**Obszar:** Panel operatora i bezpieczeństwo. **Status:** Ryzyko do weryfikacji środowiskowej.

**Dowody:** [server.py:2394](<C:/Dysk D/stuff/Mini Order Platform/scripts/control-center/web/server.py:2394>), [server.py:2406](<C:/Dysk D/stuff/Mini Order Platform/scripts/control-center/web/server.py:2406>), [server.py:2890](<C:/Dysk D/stuff/Mini Order Platform/scripts/control-center/web/server.py:2890>).

**Problem i skutek:** Nasłuch wyłącznie na loopback i token POST to dobre zabezpieczenia. Jednak bootstrap wydaje token bez walidacji Host, a serwer nie sprawdza Origin. Przy możliwości DNS rebinding lub pośredniczenia w dostępie do localhost może to osłabić ochronę panelu, który uruchamia polecenia i terminal. Nie potwierdzono skutecznego ataku w przeglądarce.

**Zalecenie:** Odrzucać nieoczekiwany Host, sprawdzać Origin dla operacji zmieniających stan, zachować ochronę tokenem i brak CORS. Ograniczyć żywotność i liczbę zadań/terminali.

**Warunek odbioru:** Host spoza listy i obcy Origin nie otrzymują sesji ani dostępu do operacji; zwykły localhost działa.


<a id="a26"></a>
### A26 · P2 · Klucz zewnętrznego AI jest utrwalany jawnym tekstem

**Wdrożenie Audit_0.1: wykonane i zweryfikowane (24.10.2026).**
Konfiguracja zawiera tylko niesekretne ustawienia. Klucze z otoczenia pozostają
w pamięci; nowe klucze są szyfrowane SOPS/age osobno dla profilu i dostawcy.
Migracja starego `apiKey` sprawdza odszyfrowanie przed atomowym usunięciem
jawnego wpisu. Nie tworzy jawnych plików tymczasowych ani kopii `.bak`,
zachowuje stare dane przy błędzie i umożliwia ponowienie po przerwaniu.
Formularz i API udostępniają tylko maskę oraz źródło klucza. Gemini używa
nagłówka zamiast query; błędy i przekierowania nie ujawniają poświadczeń.
Rzeczywiste testy SOPS/age oraz regresja HTTP i Chromium przeszły.
Wymagany restart CC; migracja nie usuwa historycznych zewnętrznych backupów.
Szczegóły: [klucze AI i migracja A26](A26_KLUCZE_AI.md).

**Obszar:** Sekrety i panel operatora. **Status:** Potwierdzone w kodzie.

**Dowody:** [server.py:1610](<C:/Dysk D/stuff/Mini Order Platform/scripts/control-center/web/server.py:1610>), [server.py:1642](<C:/Dysk D/stuff/Mini Order Platform/scripts/control-center/web/server.py:1642>), [server.py:1654](<C:/Dysk D/stuff/Mini Order Platform/scripts/control-center/web/server.py:1654>).

**Problem i skutek:** Konfiguracja ai-config.json zawiera apiKey. save_ai_config() zapisuje cały obiekt, także gdy klucz został wcześniej automatycznie pobrany ze zmiennej środowiskowej. Ignorowanie katalogu w Git chroni przed przypadkowym commitem, lecz nie przed odczytem pliku, backupem ani kopiowaniem środowiska.

**Zalecenie:** Przechowywać referencję do sekretu w SOPS/systemowym magazynie albo pobierać go wyłącznie w runtime. Nie przepisywać klucza z env do JSON; ograniczyć uprawnienia istniejących plików i usunąć historyczne jawne kopie po migracji.

**Warunek odbioru:** Zapis zmiany modelu/ustawień nie tworzy pliku z kluczem; endpoint publicznej konfiguracji nadal zwraca tylko maskę.


<a id="a27"></a>
### A27 · P2 · Odczyt jednego produktu przelicza cały katalog i wykonuje zbędną pracę O(N²)

**Wdrożenie Audit_0.1: wykonane i zweryfikowane (24.10.2026), bez zmian zdjęć.**
Szczegół ładuje jedną encję, a checkout wykonuje jedno wewnętrzne wywołanie
batch 1–100 ID. Promotion Service otrzymuje wybrane produkty z globalnymi
pozycjami. Usunięto `indexOf` i zachłanny fallback. Cache ID ma TTL i limit
pamięci; generacja/licznik w Product DB oraz triggery V10 unieważniają go
między instancjami. Ceny/oferty pozostają bieżące. Odczyty używają jednego
obrazu bazy, a zapis nie publikuje niezatwierdzonych ID. Po rozgrzaniu indeksu
pomiar PostgreSQL dla 1105 produktów wykazał 2 zapytania/2 zwrócone wiersze
dla szczegółu oraz jedno wywołanie Product i jedno Promotion dla koszyków
1/5/50/100 produktów. Zimny indeks pobiera ID; powyżej limitu działa fallback
SQL bez ładowania pozostałych encji. Testy Java i regresja całej aplikacji
przeszły. Wymagane V10 i aktualizacja Product przed Order.
Szczegóły: [odczyty katalogu i wdrożenie A27](A27_ODCZYTY_KATALOGU.md).

**Obszar:** Wydajność katalogu. **Status:** Potwierdzone w kodzie; wpływ czasowy wymaga pomiaru.

**Dowody:** [ProductService.java:35](<C:/Dysk D/stuff/Mini Order Platform/product-service/src/main/java/org/demo/productservice/product/ProductService.java:35>), [ProductService.java:101](<C:/Dysk D/stuff/Mini Order Platform/product-service/src/main/java/org/demo/productservice/product/ProductService.java:101>), [PromotionClient.java:31](<C:/Dysk D/stuff/Mini Order Platform/product-service/src/main/java/org/demo/productservice/product/PromotionClient.java:31>).

**Problem i skutek:** GET pojedynczego produktu pobiera cały katalog i wysyła cały zestaw do Promotion Service. Budowanie requestu używa indexOf w pętli. Również fallback w Map.getOrDefault jest obliczany z góry — offerFor() skanuje katalog nawet wtedy, gdy oferta już istnieje. Checkout robi to kolejno dla każdej pozycji.

**Zalecenie:** Oddzielić odczyt produktu od pełnego merchandisingu, udostępnić batch produktów/cen, indeks wyliczać raz. Użyć warunkowego fallbacku i cache wersji katalogu/ofert z okresem ważności.

**Warunek odbioru:** Odczyt produktu nie odczytuje N produktów; batch checkout ma stałą liczbę wywołań zależnych, a wynik cenowy pozostaje zgodny.


<a id="a28"></a>
### A28 · P2 · Strona katalogu uruchamia osobne żądanie magazynowe dla każdego produktu

**Wdrożenie Audit_0.1: wykonane i zweryfikowane (24.10.2026), bez zmian zdjęć.**
Magazyn udostępnia publiczny batch 1–100 ID realizowany jednym zapytaniem SQL.
Katalog renderuje się przed danymi dodatkowymi; pobiera pierwszą grupę kart
i karty w widoku, w deduplikowanych batchach z limitem dwóch aktywnych żądań.
Deadline i AbortController zatrzymują stare odświeżenie, a wynik starszej
generacji nie nadpisuje aktualnych stanów. Awaria magazynu nie blokuje oferty
i nie jest zamieniana na fałszywe zero. Koszyk wymusza świeży batch
z priorytetem nad kolejką tła. Aktualizowane są teksty/przyciski bez odtwarzania
elementów zdjęć. Test 1000 produktów potwierdził ograniczony ruch początkowy;
testy Java, Node, Chromium i regresja całej aplikacji przeszły.
Szczegóły: [dostępność katalogu i wdrożenie A28](A28_DOSTEPNOSC_KATALOGU.md).

**Obszar:** Wydajność frontendu. **Status:** Potwierdzone w kodzie.

**Dowody:** [app.js:686](<C:/Dysk D/stuff/Mini Order Platform/frontend/app.js:686>), [app.js:646](<C:/Dysk D/stuff/Mini Order Platform/frontend/app.js:646>), [app.js:555](<C:/Dysk D/stuff/Mini Order Platform/frontend/app.js:555>).

**Problem i skutek:** Dla N produktów wysyłanych jest N żądań magazynowych przez Promise.all bez limitu. Renderowanie katalogu czeka na ich zakończenie, potem na dodatkowe ceny historyczne. Każdy request przechodzi przez gateway i operacje Redis rate limitera. Fetch nie ma jawnego deadline ani anulowania poprzedniego zapytania.

**Zalecenie:** Dodać batch dostępności lub odczytowy agregat katalogu, renderować ofertę przed danymi dodatkowymi, ładować dane widocznej strony. Wprowadzić timeout, AbortController i limit współbieżności dla pozostałych wywołań.

**Warunek odbioru:** Liczba żądań początkowych nie rośnie liniowo z pełnym katalogiem; awaria magazynu nie blokuje prezentacji produktów.


<a id="a29"></a>
### A29 · P2 · Większość list biznesowych pobiera wszystkie rekordy

**Obszar:** Dane i API. **Status:** Wykonane i zweryfikowane 24.10.2026 (bez zmian zdjęć).

**Wdrożenie:** Strony kursora po ID (50, maks. 200), filtry serwera, okno ID
zamówień przed pobraniem pozycji, SQL COUNT/AVG opinii oraz zbiorcze sumy korekt.
Sklep, CC i CLI udostępniają kolejne strony; rekomendacje odczytują kupione ID
z ograniczonych grup zamiast pełnej historii. Zachowano kontrolę dostępu i A39.
Potwierdzono 148 kontroli paginacji na tabelach po 100 tys. rekordów, ograniczone
wiersze SQL i indeksowane plany oraz 192 kontroli regresji HTTP. Dokumentacja:
[A29 — paginacja i wdrożenie](A29_PAGINACJA_LIST.md).

Poniżej pozostawiono opis problemu ze stanu bazowego audytu.

**Dowody:** [OrderService.java:159](<C:/Dysk D/stuff/Mini Order Platform/order-service/src/main/java/org/demo/orderservice/order/OrderService.java:159>), [NotificationService.java:113](<C:/Dysk D/stuff/Mini Order Platform/notification-service/src/main/java/org/demo/notificationservice/notification/NotificationService.java:113>), [ReviewService.java:34](<C:/Dysk D/stuff/Mini Order Platform/review-service/src/main/java/org/demo/reviewservice/review/ReviewService.java:34>).

**Problem i skutek:** Zamówienia, płatności, powiadomienia, czat, zwroty i część historii klientów zwracają nieograniczone listy. Podsumowanie opinii materializuje wszystkie opinie zamiast agregacji SQL. Panel pobiera dane i dopiero potem je przycina. Billing list() ma już ograniczony rozmiar strony — warto przenieść ten wzorzec dalej.

**Zalecenie:** Wprowadzić limit i stabilną paginację, filtry po stronie serwera, projekcje oraz COUNT/AVG w bazie. Dobierać indeksy do rzeczywistych zapytań i sprawdzać plany na reprezentatywnych danych.

**Warunek odbioru:** 100 tys. rekordów nie zmienia rozmiaru odpowiedzi pojedynczej strony; lista i agregaty nie materializują całej tabeli.


<a id="a30"></a>
### A30 · P2 · Cache PDF jest nieograniczony, a gateway buforuje cały eksport ZIP

**Obszar:** Wydajność dokumentów. **Status:** Wykonane i zweryfikowane 24.10.2026 (bez zmian zdjęć).

**Wdrożenie:** Wspólny cache PDF/korekt ograniczony bajtami, liczbą wpisów
i TTL; filtrowane partie SQL eksportu poza długą transakcją; osobny streaming
ZIP przez Gateway bez buforowania/retry/redirect. Limity dokumentów i równoległych
eksportów, daty w CC/CLI, zachowanie wcześniejszego pliku po błędzie CLI.
Potwierdzono 60 PDF-ów ze 100 tys. rekordów oraz strumień ZIP 160 MiB przez
Gateway z heap 96 MiB (zmierzony szczyt ok. 44 MiB), 103 testy Java i 192
kontrole regresji HTTP. Procedura: [A30 — cache i eksport](A30_PDF_CACHE_EKSPORT.md).

Poniżej pozostawiono opis problemu ze stanu bazowego audytu.

**Dowody:** [BillingService.java:33](<C:/Dysk D/stuff/Mini Order Platform/billing-service/src/main/java/org/demo/billingservice/billing/BillingService.java:33>), [BillingService.java:180](<C:/Dysk D/stuff/Mini Order Platform/billing-service/src/main/java/org/demo/billingservice/billing/BillingService.java:180>), [GatewayProxy.java:119](<C:/Dysk D/stuff/Mini Order Platform/api-gateway/src/main/java/org/demo/apigateway/proxy/GatewayProxy.java:119>).

**Problem i skutek:** PDF-y faktur i korekt pozostają w mapach bez limitu/TTL. Eksport pobiera wszystkie faktury, filtruje daty w pamięci i wypełnia cache. Choć Billing Controller strumieniuje ZIP, GatewayProxy odczytuje całość do byte[], więc publiczna trasa traci zaletę streamingu.

**Zalecenie:** Ograniczyć cache wagą bajtową/TTL albo przechowywać gotowe dokumenty w magazynie plików. Filtrować i stronicować eksport w SQL, strumieniować przez gateway lub realizować eksport jako zadanie z pobraniem gotowego pliku.

**Warunek odbioru:** Duży eksport ma ograniczony narzut RAM, filtr dat ogranicza odczyt DB, a cache nie przekracza ustawionego budżetu.


<a id="a31"></a>
### A31 · P2 · Część klientów HTTP pomija timeouty, a operacje sieciowe trzymają transakcje DB

**Obszar:** HTTP i transakcje. **Status:** Potwierdzone w kodzie; skala wymaga pomiaru.

**Wdrożenie Audit_0.1: wykonane i zweryfikowane (24.10.2026), bez zmian zdjęć.**
Wspólne limity HTTP i przenoszenie deadline'u, krótkie transakcje odczytu/stanu
oraz ograniczone pule niezależnych zadań. Testy z pulą jednego połączenia i
rzeczywistymi usługami potwierdziły dostępność DB podczas awarii HTTP/SMTP
oraz zakończenie szybkiej sagi przed odblokowaniem wolnej. Rezerwacje z tokenem
chronią zapis wyników. Pozostały zakres A06/A15/A32 nadal otwarty.
Procedura i pomiary: [A31 — HTTP i transakcje](A31_HTTP_TRANSAKCJE.md).

**Dowody:** [PromotionClient.java:23](<C:/Dysk D/stuff/Mini Order Platform/product-service/src/main/java/org/demo/productservice/product/PromotionClient.java:23>), [BillingPdfClient.java:14](<C:/Dysk D/stuff/Mini Order Platform/notification-service/src/main/java/org/demo/notificationservice/notification/BillingPdfClient.java:14>), [PaymentService.java:66](<C:/Dysk D/stuff/Mini Order Platform/payment-service/src/main/java/org/demo/paymentservice/payment/PaymentService.java:66>).

**Problem i skutek:** PromotionClient i BillingPdfClient tworzą własne RestClient bez jawnych timeoutów z konfiguracji projektu. Płatność, powiadomienie i przetwarzanie sagi wykonują zewnętrzne operacje wewnątrz transakcji; scheduler sagi przetwarza do 100 rekordów w jednej transakcji. Wolna zależność może zajmować połączenia DB i blokady przez długi czas.

**Zalecenie:** Ujednolicić fabrykę klientów, deadline i budżety retry dla całego żądania. Dzielić orkiestrację na krótkie transakcje stanu i wywołania zewnętrzne. Rozmiary pul i batchy dobierać z pomiarów; uwzględnić mnożenie prób na kolejnych warstwach.

**Warunek odbioru:** Zawieszona zależność kończy się w określonym czasie; inne operacje nadal uzyskują połączenia DB; jedna saga nie blokuje całej partii.


Granice trzeba sprawdzać na rzeczywistych wywołaniach przez proxy: samo wywołanie metody tego samego obiektu nie uruchamia nowej transakcji w domyślnym trybie proxy. Zob. [dokumentację transakcji Spring](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html).


<a id="a32"></a>
### A32 · P2 · Outbox i konsumenci nie są przygotowani na bezpieczną pracę wielu replik

**Obszar:** Zdarzenia i powiadomienia. **Status:** Ryzyka dostarczenia wynikające z kodu.

**Wdrożenie Audit_0.1: wykonane i zweryfikowane (25.10.2026).** Rezerwacje
publikacji, billing outbox w transakcji faktury/korekty, trwałe inboxy,
XAUTOCLAIM i nazwy konsumentów z UUID, ACK po commicie/kwarantannie oraz retencja
chroniąca wszystkie grupy. Sześć rzeczywistych JVM przeszło testy awarii między
XADD, commitem i ACK, z restartem brokera i deduplikacją skutków. Niepewny wynik
SMTP wymaga ręcznej oceny w DLQ; nie obiecuje się dokładnie jednej wysyłki.
Brak automatycznej retencji invoice-events do określenia odbiorców; pozostała
retencja SQL/backupy pozostają w A43/A09. [Procedura A32](A32_ZDARZENIA_REPLIKI.md).

**Dowody:** [PaymentOutboxPublisher.java:24](<C:/Dysk D/stuff/Mini Order Platform/payment-service/src/main/java/org/demo/paymentservice/outbox/PaymentOutboxPublisher.java:24>), [NotificationRetryScheduler.java:31](<C:/Dysk D/stuff/Mini Order Platform/notification-service/src/main/java/org/demo/notificationservice/notification/NotificationRetryScheduler.java:31>), [BillingService.java:70](<C:/Dysk D/stuff/Mini Order Platform/billing-service/src/main/java/org/demo/billingservice/billing/BillingService.java:70>), [PaymentEventConsumer.java:61](<C:/Dysk D/stuff/Mini Order Platform/notification-service/src/main/java/org/demo/notificationservice/messaging/PaymentEventConsumer.java:61>).

**Problem i skutek:** Repliki mogą pobrać te same wiersze outbox/retry bez claimu. SMTP może zostać wywołane przed wykryciem konfliktu DB. InvoiceEventPublisher wysyła do Redis przed commitem faktury bez outboxu i bez trwałego ponowienia przy błędzie. Konsumenci domyślnie współdzielą stałą nazwę; brak jawnego przejmowania porzuconych pending entries i retencji streamów.

**Zalecenie:** Wprowadzić claim/lease albo SKIP LOCKED dla zadań, stabilny eventId i idempotentne inboxy. Dodać billing outbox, unikalne nazwy konsumentów i odzyskiwanie pending przez XAUTOCLAIM. Określić retry/DLQ i bezpieczną retencję uwzględniającą wszystkich odbiorców.

**Warunek odbioru:** Dwie repliki i restart w każdym punkcie publish/commit/ACK nie gubią zdarzeń; duplikaty nie powielają skutków; pending po zmarłym konsumencie jest odzyskiwany.


Historia odczytywana przez XREADGROUP dotyczy pending danego konsumenta; przejęcie wpisów innego konsumenta wymaga odpowiedniego mechanizmu. Zob. [Redis XREADGROUP](https://redis.io/docs/latest/commands/xreadgroup/) i [XAUTOCLAIM](https://redis.io/docs/latest/commands/xautoclaim/).


<a id="a33"></a>
### A33 · P2 · Istniejące bramki jakości nie dowodzą poprawności krytycznych scenariuszy

**Obszar:** Testy i jakość. **Status na Audit_0.1:** ✅ **Wykonane i zweryfikowane w repozytorium.**

**Wdrożenie w Pakiecie 153 i Pakiecie 162:**
- **Bramka `scripts/qa/critical-browser-check.py`:** Wymaga przejścia pełnej macierzy 28 unikalnych kombinacji (7 krytycznych scenariuszy odzyskiwania platformy × 4 silniki przeglądarek: Chromium, Firefox, WebKit, Mobile Chrome).
- **Wynik macierzy:** 28/28 kombinacji zakończonych wynikiem PASS bez żadnych powtórzeń (0 retry) i bez niestabilności (0 flaky).
- **Naprawa błędu środowiska K8s (Pakiet 162):** Usunięto błąd przygotowania testów Kubernetes w module Python (`test_k8s_templates.py` 13/13 PASS).
- **Komplet bramek jakości w repozytorium:** 168 testów Java oraz 236 testów Node / Playwright i 464 testy Python w repozytorium PASS. [Szczegóły A33](A33_REGRESJE_ODZYSKIWANIA_I_BRAMKA_CI_153.md).

**Dowody bazowe:** `e2e/fixtures/store.fixture.ts`, `scripts/qa/security-test.sh`, `scripts/qa/contract-test.sh`, `scripts/qa/resilience-test.sh`.

**Problem i skutek:** Repo miało pierwotnie 29 plików testowych Java i 67 adnotowanych testów, lecz część to contextLoads; mockowane UI nie wykrywało błędów CORS, uprawnień i współbieżności backendu. Chaos uznawał m.in. 400/000 jako sukces; load test oceniał odpowiedzi bez progów p95/p99.

**Warunek odbioru:** Każdy P1 ma test regresyjny, a usunięcie kontroli uprawnień lub retry powoduje czerwony CI. Mockowane testy pozostają uzupełnione realnym E2E i macierzą 28 scenariuszy odzyskiwania platformy.


<a id="a34"></a>
### A34 · P2 · Szablony produkcyjne są niespójne z aktualnym składem aplikacji

**Obszar:** Wdrożenie. **Status na Audit_0.1:** ✅ **Wykonane i zweryfikowane w repozytorium.** Szablony K8s 1.35.0 gotowe do wdrożenia; instalacja na klastrze docelowym (k3s) to faza DevOps.

**Wdrożenie w Pakietach 20, 162 i 164:**
- **Komplet 16 komponentów:** Szablony Kubernetes obejmują wszystkie 14 mikroserwisów Spring Boot, API Gateway oraz frontend sklepu / Control Center.
- **Relacje sieciowe i naprawa R01 (Pakiet 162):** Uzupełniono brakujące zmienne środowiskowe oraz wpisy w `deploy/components.json` i `deploy/k8s/base/network-policies.yaml` dla `returns-service` (porty 8092, 8083, 8082 i 8086).
- **Ścisła walidacja:** Skrypt `scripts/qa/validate-k8s.py` renderuje i waliduje komplet manifestów pod kątem schematów K8s 1.35.0, zasobów podów (requests/limits), sond liveness/readiness i 18 polityk `NetworkPolicy` (wynik: PASS, 0 błędów).
- **Gotowość do wdrożenia:** Szablony w `deploy/k8s/overlays/prod/` są w pełni przygotowane do uruchomienia na klastrze produkcyjnym (k3s, minikube lub chmura). [Dokumentacja szablonów](A34_SZABLONY_WDROZENIOWE.md).

**Dowody bazowe:** `deploy/k8s/base/deployments.yaml`, `deploy/k8s/overlays/prod/kustomization.yaml`.

**Problem i skutek:** Pierwotne manifesty zawierały 13 deploymentów: brakowało chat, returns i billing. Overlay nie podmieniał ich obrazów; configmap nie miał ich URL/DB. W api-gateway pole limits było poza resources.

**Warunek odbioru:** Wyrenderowane manifesty obejmują 16 komponentów aplikacyjnych, przechodzą ścisłą walidację schematu K8s, a polityki sieciowe izolują ruch między serwisami.


<a id="a35"></a>
### A35 · P2 · Metryki i logi są dostępne, ale brakuje egzekwowanego wykrywania awarii biznesowych

**Obszar:** Obserwowalność. **Status na Audit_0.1:** ✅ **Wykonane i zweryfikowane w repozytorium.**

**Wdrożenie w Pakietach 154–159, 162 i 164:**
1. **W3C Distributed Tracing (Pakiet 157):** Pełna propagacja standardu W3C `traceparent` (traceId i spanId) we wszystkich 15 usługach JVM (`platform-security`), filtrach HTTP, wywołaniach RestClient oraz `GatewayProxy`. Poprawka R09 gwarantuje zachowanie aktywnego traceId w zagnieżdżonym `CorrelationIdContext.open(String)`.
2. **Alerty biznesowe i reguły SLO (Pakiety 156, 158):** Wdrożono reguły Prometheus: `BusinessQueueSloBreach`, `RedisStreamLagHigh`, `PaymentRefundLagHigh`, `PaymentReviewPendingHigh` dla 6 kolejek biznesowych. Testy promtool PASS (22 scenariusze).
3. **Niezależny Heartbeat Watchdog (Pakiet 159):** Wdrożono regułę `Watchdog` z ciągłym sygnałem `vector(1)` do monitorowania żywotności samego systemu Alertmanagera / Prometheusa.
4. **Audytor spójności rozproszonej (Pakiety 159, 162, Przegląd 03.11):** Skrypt `scripts/qa/cross-database-consistency-check.py` z obsługą bezpośrednich połączeń PostgreSQL (`--live-postgres`, poprawka R08) weryfikujący stan spójności zamówień, płatności, faktur, przesyłek i rezerwacji w 6 niezależnych bazach danych. W trybie bramki audytor A43 weryfikuje syntaktyczną obecność reguł alertów biznesowych SLO Prometheusa oraz kontrakty testowe audytora spójności na fixture w pamięci (`static_contract_and_fixture_self_test`); audyt 6 fizycznie działających baz wymaga uruchomionego klastra i flagi `--live-postgres`.
5. **Readiness SQL (Pakiet 154):** Sondy gotowości SQL dla 14 usług i Gateway (104 testy Java i 145 Python PASS). [Dokumentacja A35](A35_ALERTY_BIZNESOWE.md).

**Warunek odbioru:** Kontrolowana awaria prowadzi do konkretnego alertu i instrukcji reakcji; jedno zamówienie daje się prześledzić przez wszystkie etapy w MDC/logach, a spójność rozproszona baz danych jest weryfikowana automatycznie.


<a id="a36"></a>
### A36 · P2 · Backupy baz wymagają planu odtworzenia całego procesu rozproszonego

**Obszar:** Backup i odtwarzanie. **Status na Audit_0.1:** ✅ **Wykonane i zweryfikowane w repozytorium.**

**Wdrożenie w Pakietach 158, 161 i 164:**
1. **Szyfrowanie i manifest:** Procedura kopii zapasowych 14 baz PostgreSQL oraz snapshota Redis RDB z asymetrycznym szyfrowaniem Age (X25519) i uwierzytelnionym podpisem manifestu `manifest.json.age`.
2. **Procedury odzyskiwania platformy:** Wdrożono skrypty `scripts/backup/privacy-restore.py` oraz `scripts/backup/platform-recovery.py` z procedurami kwarantanny odtworzonego środowiska, unieważniania tokenów sesji i bezpiecznego wznowienia.
3. **Weryfikacja testowa:** Komplet 15/15 testów jednostkowych i integracyjnych odzyskiwania platformy PASS (`test_privacy_restore.py`, `test_platform_recovery.py`).
4. **Decyzja D09:** Zatwierdzenie dedykowanego wolumenu `privacy-journal-safe` na niezależnym nośniku (zatwierdzone przez użytkownika w Pakiecie 164). [Dokumentacja A36](A36_ODTWARZANIE_PLATFORMY.md).

**Warunek odbioru:** Po odtworzeniu znane zamówienie zachowuje płatność, rezerwację, fakturę i powiadomienie; odtworzone środowisko uruchamia się w kwarantannie, a integralność kryptograficzna kopii jest weryfikowana kluczem Age.


<a id="a37"></a>
### A37 · P2 · Modale sklepu nie zapewniają pełnej obsługi klawiaturą, a test wyłącza kontrast

**Obszar:** UX i dostępność. **Status:** Wdrożone i zweryfikowane lokalnie 26.10.2026 w opisanych scenariuszach klawiatury i kontrastu. Bez deklaracji pełnej zgodności WCAG.

**Dowody:** [app.js:2102](<C:/Dysk D/stuff/Mini Order Platform/frontend/app.js:2102>), [catalog.html:372](<C:/Dysk D/stuff/Mini Order Platform/frontend/catalog.html:372>), [accessibility.spec.ts:10](<C:/Dysk D/stuff/Mini Order Platform/e2e/tests/accessibility/accessibility.spec.ts:10>).

**Problem i skutek:** Sklep oznacza nakładki jako aria-modal, ale nie przenosi i nie zamyka cyklu fokusu we wszystkich modalach, nie unieruchamia tła ani nie przywraca konsekwentnie fokusu. Obsługa Escape w app.js dotyczy podglądu koszyka. Test axe obejmuje głównie stronę główną i pomija kontrast; to nie potwierdza dostępności checkoutu, MFA i dialogów.

**Zalecenie:** Użyć natywnego dialog.showModal() albo wspólnego, poprawnego komponentu z focus trap, Escape i przywróceniem fokusu. Testować aktywne formularze i błędy, kontrast, zoom oraz reduced motion. Nie deklarować wyniku WCAG bez takiej weryfikacji.

**Warunek odbioru:** Cały zakup i logowanie są obsługiwalne Tab/Shift+Tab/Enter/Escape; fokus nie opuszcza aktywnego modala; kontrast przechodzi pomiar.

**Wdrożenie:** Cztery typy okien na stronie głównej i w katalogu używają natywnego
`dialog.showModal()` oraz wspólnego zarządzania fokusem, Escape, powrotem do
wywołującego przycisku i przewijaniem. Tło jest natywnie inertne. Dodano fokus
etapów checkoutu i statusu sukcesu, Enter/walidację MFA, poprawiono obsługę
klawiatury koszyka i brakujące przyciski porównania (indeks map jako wariant karty).
Przewijana tabela porównania jest dostępnym regionem. Zachowano geometrię kart
i zdjęć. Włączono pomiar kontrastu i poprawiono kolory tekstu/kontrolek.

**Odbiór:** 60 testów dostępności (15 scenariuszy × Chromium, Firefox, WebKit,
mobilny Chrome), 53 pozostałe testy UI oraz 26 testów JS — PASS. Sprawdzono
zakup gościa wyłącznie klawiaturą, błędy i sukces MFA, aktywne pola faktury,
formularze konta, wszystkie pięć stron sklepu, szerokość 320 px, CSS zoom 200%
i reduced-motion CSS. Obejrzano zrzuty. Raport: `tmp/audit-a37-1977a83a/report.json`.
CI egzekwuje scenariusze we wszystkich czterech profilach.

**Granice:** Wynik axe dotyczy serious/critical w testowanych stanach i tagach
WCAG 2 A/AA oraz 2.1 AA. Nie wykonano odbioru fizycznym czytnikiem ekranu,
sprzętowego passkey ani natywnego zoomu interfejsu przeglądarki. Pełna ocena
WCAG jest osobnym zakresem. Nie wdrażano na dev/prod; A05 i same zdjęcia
pozostają odłożone. [Zakres i dowody A37](A37_DOSTEPNOSC_SKLEPU.md).


Wzorzec wymaga zamkniętego cyklu Tab/Shift+Tab, obsługi Escape i odpowiedniego zarządzania fokusem. Zob. [W3C WAI-ARIA: modal dialog](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/).


<a id="a38"></a>
### A38 · P2 · Czat nadpisuje odpowiedź, a kolejna odpowiedź może nie wysłać nowego e-maila

**Wdrożenie Audit_0.1: wykonane i zweryfikowane lokalnie 26.10.2026.**
Niezmienne wpisy klienta/obsługi, ograniczone strony historii sklepu i CC,
odrębne powiadomienia per wpis i trwały outbox z rezerwacją między replikami.
Próba dwóch JVM, PostgreSQL, Notification i Mailpit potwierdziła restart,
utracone potwierdzenie, brak duplikatu oraz ręczny replay DEAD_LETTER.
Migracja zachowuje wyłącznie ostatnią odpowiedź dostępną w starym modelu;
nie odtwarza wcześniej nadpisanych treści. ACCEPTED oznacza przyjęcie zadania
przez Notification, nie doręczenie poczty. Szczegóły i kolejność migracji:
[A38 — historia czatu](A38_HISTORIA_CZATU.md), [postęp prac](AUDIT_0.1_POSTEP.md).
Nie wdrażano na dev/prod. Poniżej pozostaje pierwotne ustalenie audytu.

**Obszar:** Czat i obsługa klienta. **Status:** Potwierdzone w modelu i kluczu deduplikacji.

**Dowody:** [ChatMessage.java:84](<C:/Dysk D/stuff/Mini Order Platform/chat-service/src/main/java/org/demo/chatservice/chat/ChatMessage.java:84>), [ChatNotificationClient.java:40](<C:/Dysk D/stuff/Mini Order Platform/chat-service/src/main/java/org/demo/chatservice/chat/ChatNotificationClient.java:40>), [NotificationService.java:46](<C:/Dysk D/stuff/Mini Order Platform/notification-service/src/main/java/org/demo/notificationservice/notification/NotificationService.java:46>).

**Problem i skutek:** Rekord przechowuje jedną wiadomość i jedno replyMessage. Kolejna odpowiedź zastępuje poprzednią i używa tego samego eventType/sourceId/recipient. Notification Service dla SENT od razu zwraca stary wynik, więc nowa treść może nie dotrzeć e-mailem. Błąd pierwszego wywołania Notification Service pozostawia FAILED bez trwałego zadania w Chat Service.

**Zalecenie:** Modelować rozmowę i niezmienne wiadomości z własnym messageId. Powiadamiać z outboxu per wiadomość. Dopiero po poprawnym modelu dobrać adaptacyjne odpytywanie lub SSE; sam WebSocket nie rozwiąże utraty historii.

**Warunek odbioru:** Dwie odpowiedzi są widoczne chronologicznie i każda ma własne powiadomienie; restart po błędzie nie gubi wysyłki.


<a id="a39"></a>
### A39 · P2 · Publiczny DTO opinii ujawnia dane administracyjne i identyfikatory zakupu

**Wdrożenie Audit_0.1: wykonane i zweryfikowane.** Szczegóły: [postęp prac](AUDIT_0.1_POSTEP.md).

**Obszar:** Prywatność i kontrakty. **Status:** Potwierdzone w kodzie.

**Dowody:** [ReviewResponse.java:10](<C:/Dysk D/stuff/Mini Order Platform/review-service/src/main/java/org/demo/reviewservice/web/ReviewResponse.java:10>), [ReviewResponse.java:17](<C:/Dysk D/stuff/Mini Order Platform/review-service/src/main/java/org/demo/reviewservice/web/ReviewResponse.java:17>), [ReviewService.java:28](<C:/Dysk D/stuff/Mini Order Platform/review-service/src/main/java/org/demo/reviewservice/review/ReviewService.java:28>).

**Problem i skutek:** Ta sama odpowiedź jest używana dla publicznych opinii i obsługi/moderacji. Publiczny odbiorca otrzymuje customerId, orderId i moderationNote, choć do prezentacji oceny nie są potrzebne. To pozostanie problemem także po poprawnym zabezpieczeniu endpointów administracyjnych.

**Zalecenie:** Wprowadzić oddzielne projekcje publiczną, właściciela i operatora. Publicznie udostępniać wyłącznie uzgodnione dane autora oraz treści opinii, bez wewnętrznych notatek i ID zamówienia.

**Warunek odbioru:** Kontrakt publicznej opinii nie zawiera customerId/orderId/moderationNote; operator zachowuje dostęp do potrzebnych szczegółów.


<a id="a40"></a>
### A40 · P3 · Zdjęcia produktów są duże, a polityka zasobów nie wykorzystuje wersjonowania

**Obszar:** Frontend i wydajność zasobów. **Status na Audit_0.1:** ✅ **Wykonane lokalnie (zakres kodu); zdjęcia odłożone decyzją użytkownika.**

**Wdrożenie w Pakiecie 152:**
- **Kompresja gzip:** Włączona kompresja gzip statycznych zasobów tekstowych w konfiguracji Nginx oraz serwerze Control Center (redukcja transferu o −74,61% z 228 531 B do 58 029 B).
- **Nagłówki cache i bezpieczeństwa:** Poprawna rewalidacja ETag dla JS/CSS, ochrona `no-store` dla HTML, kody 404 dla brakujących zasobów, nagłówek `noindex` dla podsumowania zamówienia i Control Center.
- **Weryfikacja:** 12/12 testów HTTP serwera CC i Nginx PASS. [Dokumentacja A40](A40_HTML_JS_HTTP_I_INDEKSOWALNOSC_152.md).
- **Zdjęcia odłożone:** Zgodnie z decyzją użytkownika z 24.10.2026 i podtrzymaną w Pakiecie 164, optymalizacje formatów grafik (konwersja na WebP/AVIF, kompresja, srcset) pozostają zamrożone.
- **Domena publiczna (U40-02):** Konfiguracja zewnętrznego hosta i polityka robots.txt to faza wdrożeniowa DevOps po podpięciu domeny.

**Problem bazowy:** Dziewięć PNG ważyło łącznie 13,1 MiB. W repozytorium brakowało konfiguracji kompresji gzip w Nginx oraz właściwych nagłówków cache dla plików HTML i statycznych.

**Warunek odbioru:** Zmniejszenie transferu danych tekstowych, nagłówki bezpieczeństwa i ETag zweryfikowane; brak niepożądanej indeksacji stron prywatnych zamówień.


<a id="a41"></a>
### A41 · P3 · Koszt 15 usług i 14 instancji PostgreSQL wymaga uzasadnienia skalą

**Obszar:** Architektura i koszty. **Status na Audit_0.1:** ✅ **Rozstrzygnięte decyzją użytkownika — konsolidacja odrzucona.**

**Decyzja architektoniczna użytkownika (03.11.2026):**
- Użytkownik formalnie zatwierdził zachowanie docelowej architektury **14 mikroserwisów Spring Boot + API Gateway + 14 niezależnych baz danych PostgreSQL**.
- Pierwotna propozycja konsolidacji usług (łączenie Wishlist, Recenzji, Rekomendacji i Czatu w jeden moduł oraz współdzielenie instancji PostgreSQL) została formalnie odrzucona.
- Architektura zachowuje pełną separację domenową i autonomię danych każdego mikroserwisu.


<a id="a42"></a>
### A42 · P3 · Duplikacje konfiguracji i duże pliki zwiększają koszt każdej poprawki

**A42 ZAMKNIĘTE — A42.1–A42.21 wykonane i zweryfikowane lokalnie 27.10.2026.**
Pozostały zakres podzielono na wspólną sesję klienta (A42.17), moduły ES sklepu
wraz z checkoutem, i18n i dialogami (A42.18), moduły routingu/danych/operacji CC
oraz kontroler terminala (A42.19), 11 modułów serwera CC z 54 cienkimi fasadami
(A42.20) i kontrolę struktury/odbiór całości (A42.21).

Zachowano polecenia zakupu, klucze ponowień, capability, kolejność autoryzacji
oraz zachowanie zdjęć. Poprawiono wyścig zamykania koszyka po wymianie DOM.
Późny refresh lub odczyt terminala nie przywracają wygasłej sesji ani starego
widoku. Pierwotne warunki odbioru A42 są spełnione: infrastruktura i sesje
mają wspólne źródła, a moduły są sprawdzane osobno i w regresji funkcjonalnej.

**198 JS, 152 Python oraz 473 testy przeglądarkowe — PASS**: 380 sklepu
w czterech profilach i 93 CC w trzech silnikach. Potwierdzono parent 17 modułów
Maven oraz strukturę korelacji/odporności 15 JAR-ów. Grafy ES (58 modułów)
i granice modułów Python mają kontrolę CI wraz z testami mutantów.

Instrukcja: [domknięcie modułów A42](A42_DOMKNIECIE_MODULOW.md).
Raport: `tmp/audit-a42-complete/report.json`. Nie wykonano wdrożenia dev/prod,
nowego obrazu ani zdalnego CI. A05, całe A41 oraz zmiany samych zdjęć pozostają
odłożone. Poniższe wpisy A42.1–A42.16 dokumentują historię prac; ich wcześniejsze
wzmianki o otwartym A42 opisują stan danego pakietu, nie aktualny status.

**A42.16 wykonane i zweryfikowane lokalnie 27.10.2026.** Monitor zadań CC
wydzielono do modułu jednej obserwacji. Zmiana zadania anuluje poprzedni GET
i timer; późny wynik/błąd nie nadpisuje nowego logu/diagnozy. Powtarzane
kliknięcia współdzielą odczyt. Śledzenie obejmuje queued i running, a zakończenie
powiadamia raz. Błąd można ponowić przez wybór zadania, bez ponawiania POST.
Wygaśnięcie sesji blokuje restart monitora do przeładowania. 169 JS, 44 CC
Chromium, 20 Firefox i 20 WebKit — PASS. Instrukcja:
[A42.16 — monitor zadań CC](A42_MONITOR_ZADAN_CC.md).
Raport: `tmp/audit-a42-16/report.json`. Pełne A42 nadal otwarte; backend
bez zmian i bez wdrożenia dev/prod. A05, całe A41 i zdjęcia nadal odłożone.

**A42.15 wykonane i zweryfikowane lokalnie 27.10.2026.** Cykl rozmowy
Asystenta CC przeniesiono do osobnego kontrolera. Jedna aktywna tura,
anulowanie i ochrona przed późnym wynikiem zapobiegają mieszaniu rozmów po
czyszczeniu. Wygaśnięcie sesji blokuje dalszą wysyłkę. Historia ma limit
dziesięciu wiadomości z zakończonych par; bieżące pytanie nie jest podwajane
w kontekście. Szkic pozostaje zachowany podczas oczekiwania. Dziewięć nowych
testów JS i cztery nowe scenariusze w trzech silnikach — PASS. Instrukcja:
[A42.15 — rozmowa Asystenta CC](A42_KONTROLER_ROZMOWY_ASYSTENTA_CC.md).

**A42.14 wykonane i zweryfikowane lokalnie 27.10.2026.** Wspólny moduł
utrwala zamiary przyjęć magazynowych i korekt faktur przed HTTP. Zachowano
klucze v1, niezmienne polecenia i ponowienia; blokady między kartami oraz
sprzątanie po ID są wspólne. Profil jest przechwytywany przed blokadą,
a późne zakończenie nie usuwa nowszego zapisu. Osiem nowych testów JS,
ponowienia po reload i blokada błędnego storage w trzech silnikach — PASS.
Instrukcja: [A42.14 — operacje operatora CC](A42_MODUL_OPERACJI_OPERATORA_CC.md).

Wspólny odbiór A42.14/A42.15: **157 JS, 39 CC Chromium, 15 Firefox
oraz 15 WebKit — PASS**. Raport: `tmp/audit-a42-14-15/report.json`.
Pełne A42 nadal otwarte; backend bez zmian i bez wdrożenia dev/prod.
A05, całe A41 i zmiany samych zdjęć pozostają odłożone.

**A42.13 wykonane i zweryfikowane lokalnie 27.10.2026.** Paletę poleceń
wydzielono do modułu modelu i kontrolera. Operacje mają tożsamość po ID,
więc identyczne lub zawierające się nazwy nie ukrywają innych poleceń.
Usunięto podwójny katalog stałych nazw; poprawiono wyszukiwanie, klawiaturę,
ARIA i fokus. Wybór nadal otwiera formularz z wymaganym potwierdzeniem.
140 testów JS, 33 CC w Chromium i 16 Firefox/WebKit — PASS. Instrukcja:
[A42.13 — paleta poleceń CC](A42_MODUL_PALETY_POLECEN_CC.md).
Pełne A42 pozostaje otwarte; backend bez zmian, bez wdrożenia na dev/prod.
A05, całe A41 i zdjęcia nadal odłożone.

**A42.12 wykonane i zweryfikowane lokalnie 27.10.2026.** Renderer odpowiedzi
Asystenta CC wydzielono do modułu bez DOM/HTTP/storage. Kod jest dosłowny,
cytaty i grupowanie list poprawione, a wartości tabel zawierające `---`
nie znikają. Tekst źródłowy jest escapowany; HTML, skrypty i osadzone zasoby
pozostają nieaktywne. 130 testów JS, 28 testów CC w Chromium i sześć prób
renderera Firefox/WebKit — PASS. Instrukcja:
[A42.12 — renderer odpowiedzi CC](A42_RENDERER_ODPOWIEDZI_AI_CC.md).
Pełne A42 nadal pozostaje otwarte. Backend i ustawienia AI bez zmian, bez
wdrożenia na dev/prod. A05, całe A41 i zdjęcia nadal odłożone.

**A42.11 wykonane i zweryfikowane lokalnie 27.10.2026.** Konwersję opcji
i poświadczeń WebAuthn wydzielono do modułu bez DOM/HTTP/storage. Kodowanie
partiami usuwa RangeError dużego bufora i zachowuje dokładne zakresy widoków.
Opcje/payloady pozostają zgodne; późny wynik starego wyzwania nie jest wysyłany
pod ID nowego wyzwania. 116 testów JS, 95 Chromium i 45 testów pozostałych
profili — PASS. Pięć nowych scenariuszy wykonano w czterech profilach przy
użyciu symulowanego authenticatora. Instrukcja:
[A42.11 — moduł passkey](A42_MODUL_PASSKEY.md).
Pełne A42 pozostaje otwarte. Backend i klucze MFA/JWT bez zmian; nie wdrażano
na dev/prod. A05, całe A41 i zdjęcia nadal odłożone.

**A42.10 wykonane i zweryfikowane lokalnie 27.10.2026.** Transport rozmów
wydzielono z widgetu do modułu korzystającego ze wspólnego klienta HTTP.
Zachowano capability i klucz ponowienia dalszych wiadomości, bez cookies/JWT
konta oraz refresh. Współdzielone odczyty są anulowane po zamknięciu, ukryciu
dokumentu lub zmianie rozmowy. Późny wynik/błąd starej rozmowy nie zmienia nowego
widoku; aktualne 404 przywraca formularz. 104 testy JS, 90 Chromium i 45 testów
Firefox/WebKit/mobile — PASS, pięć nowych scenariuszy w czterech profilach.
Instrukcja: [A42.10 — transport czatu](A42_MODUL_TRANSPORTU_CZATU.md).
Pełne A42 nadal pozostaje otwarte dla dalszego podziału kodu i duplikacji.
Backend bez zmian, bez wdrożenia na dev/prod. A05, A41 i zdjęcia nadal odłożone.

**A42.9 wykonane i zweryfikowane lokalnie 26.10.2026.** PDF korzysta ze
wspólnego transportu Blob i osobnego modułu pobierania. Połączono równoległe
kliknięcia, zachowano JWT/capability oraz dokładne bajty, dodano anulowanie
po wylogowaniu i sprawdzanie właściciela/capability przed pobraniem. URL jest
zwalniany także przy błędzie. Odmowa odnowienia sesji pokazuje czytelny komunikat.
92 testy JS, 85 Chromium oraz 69 testów Firefox/WebKit/mobile — PASS;
pięć nowych scenariuszy PDF odebrano w czterech profilach. Instrukcja:
[A42.9 — moduł pobierania faktur](A42_MODUL_POBIERANIA_FAKTUR.md).
Pełne A42 pozostaje otwarte dla transportu czatu i dalszego podziału kodu.
Backend bez zmian, bez wdrożenia na dev/prod. A05, A41 i zdjęcia nadal odłożone.

**A42.8 wykonane i zweryfikowane lokalnie 26.10.2026.** Wspólny moduł klienta
JSON zastąpił trzy implementacje sklepu, produktu i zamówienia. Ujednolicono
nagłówki, błędy i stronicowanie; równoległe 401 współdzielą refresh, a ponowienie
zachowuje sygnał deadline i niezmienne polecenie. Zachowano capability gościa,
wyłączenia MFA i brak retry dla sieci/5xx. Przekierowania API są blokowane.
77 testów JS, 80 Chromium, 78 pozostałych scenariuszy Firefox/WebKit/mobile
oraz 16 prób API w czterech profilach — PASS. Instrukcja i opis odbioru:
[A42.8 — wspólny klient HTTP sklepu](A42_WSPOLNY_KLIENT_HTTP_SKLEPU.md).
Pełne A42 pozostaje otwarte dla dalszych modułów stron, PDF i czatu. Backend
bez zmian; bez wdrożenia na dev/prod. A05, A41 i zmiany zdjęć nadal odłożone.

**A42.7 wykonane i zweryfikowane lokalnie 26.10.2026.** Wydzielono moduł
list i stronicowania CC. Kursory są prywatne i niezależne dla każdej listy;
nowe wyszukiwanie anuluje stary odczyt i blokuje nawigację do czasu odpowiedzi.
Błąd nowego filtra nie przywraca już kursora starego zapytania. Wygaśnięcie
sesji usuwa timery i anuluje odczyty; późne wyniki są ignorowane.
63 testy JS i 25 testów CC w Chromium — PASS. Instrukcja:
[A42.7 — moduł list CC](A42_MODUL_LIST_CONTROL_CENTER.md).
Pełne A42 pozostaje otwarte. Backend i polecenia zapisu bez zmian, bez wdrożenia
na dev/prod. A05, całe A41 i zmiany samych zdjęć pozostają odłożone.

**A42.6 wykonane i zweryfikowane lokalnie 26.10.2026.** Słowniki PL/EN,
nakładanie etykiet na DOM i historyczne tytuły zadań CC wydzielono do modułu ES.
Główny skrypt ma 3265 linii, około tysiąca mniej. Uzupełniono brakujące polskie
etykiety logów i nagłówka „O aplikacji”; oba języki mają po 235 zgodnych kluczy.
55 testów JS i 22 testy CC w Chromium — PASS: język w 12 widokach, reload,
zachowane sesje i szkic terminala po błędzie zapisu. Instrukcja:
[A42.6 — moduł tłumaczeń CC](A42_MODUL_TLUMACZEN_CONTROL_CENTER.md).
Dalszy podział dużych plików i pozostałe duplikacje nadal pozostają otwarte.
Bez zmian backendu i wdrożenia na dev/prod. A05, A41 i zmiany zdjęć odłożone.

**A42.5 wykonane i zweryfikowane lokalnie 26.10.2026.** Transport JSON/Blob
i sesję CC wydzielono do niezależnego modułu ES. Token jest prywatny dla klienta;
zachowano nagłówek, ten sam origin, brak cookies, zakaz przekierowań i kontrakt
bezpiecznych ponowień. Spóźnione 401 nie usuwa nowej sesji; późny bootstrap ani
body odpowiedzi nie przywraca dostępu po wygaśnięciu. 52 testy JS oraz 20 testów
CC w Chromium — PASS, w tym pobieranie plików i przekierowanie. Instrukcja:
[A42.5 — moduł API Control Center](A42_MODUL_API_CONTROL_CENTER.md).
Pełne A42 pozostaje otwarte. Nie wdrażano na dev/prod; A05, całe A41 i zmiany
samych zdjęć pozostają odłożone.

**A42.4 wykonane i zweryfikowane lokalnie 26.10.2026.** Wspólny moduł ES
pamięci koszyka zastąpił powielone klucze, migrację i operacje storage w sklepie
i szczegółach produktu. Zachowano zakresy gość/klient, JSON i schemat `2`.
Trzy strony ładują skrypty wejściowe jako moduły; zabezpieczono też zapis
preferencji porównania, który przy blokadzie storage zatrzymywał katalog.
35 testów JS, 76 Chromium i 105 innych profili przeglądarek — PASS.
Rzeczywisty serwer statyczny CC poprawnie serwuje moduły. Instrukcja:
[A42.4 — moduł pamięci koszyka](A42_MODUL_PAMIECI_KOSZYKA.md).
Pozostały podział logiki sklepu i CC pozostaje otwarty. Bez zmian backendu,
płatności, zdjęć i odłożonej architektury A41; nie wdrażano na dev/prod.

**A42.3 wykonane i zweryfikowane lokalnie 26.10.2026.** Wspólny wykonawca
HTTP i konfiguracja retry/circuit breaker/bulkhead zastąpiły cztery kopie
w Gateway, Order, Payment i Customer. Zachowano ustawienia, metryki, osobne
rejestry i decyzje Gateway o zakazie retry zapisu. Customer również sprawdza
budżet przed próbą. Opcjonalne zależności nie trafiają do pozostałych 11 aplikacji.
511 testów Java i 145 Python — PASS; zgodny zestaw bibliotek 15 JAR-ów,
regresja A31 na trzech JVM z zawieszonym HTTP/SMTP oraz regresja korelacji — PASS.
Instrukcja: [A42.3 — wspólna odporność HTTP](A42_WSPOLNA_ODPORNOSC_HTTP.md).
Pełne A42 pozostaje otwarte dla dalszego podziału dużych plików i pozostałych
powielonych mechanizmów. Nie wdrażano na dev/prod ani nie realizowano odłożonego A41.

**A42.2 wykonane i zweryfikowane lokalnie 26.10.2026.** Wspólny kontekst,
filtr oraz interceptor korelacji zastąpiły lokalne kopie; 12 adapterów zachowuje
rejestrację filtrów, a Customer — metadane audytu. Recommendation korzysta
z tej samej ścisłej walidacji ID. Czysta przebudowa: 492 testy Java i 141 Python,
kontrola 15 JAR-ów oraz próba dwóch JVM/17 kontroli HTTP — PASS. Kontrole CI
wykrywają stare kopie źródeł i klas. Szczegóły:
[A42.2 — wspólna korelacja](A42_WSPOLNA_KORELACJA.md).
Dalszą konsolidację wykonawców i konfiguracji odporności HTTP wykonano w A42.3;
podział dużych plików i pozostałe powielone mechanizmy nadal pozostają otwarte.
Nie zmieniono podziału usług/baz ani kolejności autoryzacji; nie zamyka to pełnego A35.

**Wdrożenie Audit_0.1: A42.1 wykonane i zweryfikowane lokalnie 26.10.2026.**
Root jest wspólnym parentem 17 modułów i dziedziczy Spring Boot. Wersje wspólne,
dependencyManagement oraz wersja Failsafe mają jedno źródło; CC i CI instalują
parent przed osobnym uruchamianiem modułów. 472 testy Java i 137 Python przeszły.
Efektywne zależności/pluginy 17 modułów i biblioteki 15 wykonywalnych JAR-ów
są zgodne ze stanem sprzed refaktoryzacji. Kontrola CI wykrywa ponowne duplikacje.
Pozostałe duplikacje infrastruktury i podział dużych plików pozostają otwarte.
Instrukcja: [A42.1 — wspólny parent Maven](A42_WSPOLNY_PARENT_MAVEN.md).
Nie konsolidowano usług ani baz (odłożone A41). Poniżej pierwotne ustalenie audytu.

**Obszar:** Utrzymanie kodu. **Status:** Potwierdzone strukturą i porównaniem plików.

**Dowody:** [pom.xml:15](<C:/Dysk D/stuff/Mini Order Platform/pom.xml:15>), [pom.xml:9](<C:/Dysk D/stuff/Mini Order Platform/api-gateway/pom.xml:9>), [app.js:593](<C:/Dysk D/stuff/Mini Order Platform/frontend/app.js:593>), [server.py:2363](<C:/Dysk D/stuff/Mini Order Platform/scripts/control-center/web/server.py:2363>).

**Problem i skutek:** Root POM jest agregatorem, a moduły dziedziczą bezpośrednio ze Spring Boot, więc root dependencyManagement nie centralizuje ich wersji. Po normalizacji nazw pakietów istnieją grupy 7 identycznych CorrelationIdFilter/Context i 3 executorów HTTP. app.js sklepu ma 3413 linii, panelu 4003, server.py 2910; logika API/sesji/koszyka powtarza się między stronami.

**Zalecenie:** Wprowadzić rzeczywisty wspólny parent/BOM i niewielki moduł infrastrukturalny dla powtarzanych mechanizmów. Frontend podzielić na moduły ES dla API, sesji, koszyka, checkoutu, i18n i dialogów; panel na routing, dane i operacje. Nie tworzyć wspólnego modelu domenowego wiążącego wszystkie usługi.

**Warunek odbioru:** Zmiana timeoutu, wersji lub obsługi sesji ma jedno źródło; moduły dają się testować osobno. Sama długość pliku nie jest dowodem błędu.


<a id="a43"></a>
### A43 · P3 · Dane techniczne i zdarzenia rosną bez zdefiniowanego cyklu życia

**Obszar:** Dane i retencja. **Status na Audit_0.1:** ✅ **Wykonane i odebrane w repozytorium w trybie bezpiecznym fail-closed.**  
Pakiety 161, 163 i 164 domknęły wdrożenie procedur retencji, dowodów środowiskowych i audytora gotowości platformy. Niezmiennik fail-closed (`--require-ready` zwraca kod 2) oraz blokada workerów D02 (`BLOCKED_FAIL_CLOSED`) zabezpieczają system przed jakąkolwiek destrukcyjną mutacją danych do czasu wdrożenia produkcyjnego.

**Aktualizacja 03.11.2026 — pakiet 164 (Krok 6 A43 / A43.41 oraz przegląd 03.11.2026):** [odbiór końcowy i procedury gotowości](A43_KROK6_ODBIOR_KONCOWY_PROCEDURY_164.md).
Wdrożono skrypt audytora gotowości platformy `scripts/qa/a43-final-readiness-check.py` z testami jednostkowymi `scripts/qa/unit/test_a43_final_readiness.py` (7/7 PASS), weryfikujący 6 wymiarów platformy:
1. Architektura serwisów (14 mikroserwisów JVM + API Gateway, korelacja W3C traceparent),
2. Schemat i retencja (145 tabel, 1307 kolumn, 232 migracje, 10 tabel retencji D02, ochrona 5 tabel finansowych `hard_block = true`),
3. Krytyczna macierz przeglądarek A33 (28/28 kombinacji bez flakiness i bez retry),
4. Obserwowalność i alerty SLO A35 (Watchdog `vector(1)`, alerty kolejek i Redis, spójność 6 baz w trybie kontraktu i fixture in-memory; live wymaga `--live-postgres`),
5. Disaster Recovery A36 (szyfrowanie Age X25519, manifest, kwarantanna odtwarzania),
6. Dowody środowiskowe dev/test (Krok 5).
Wygenerowano raport `tmp/a43-step6-readiness/final-readiness-report.json`.

**Rozgraniczenie retencji i statusy D01/D02:**
- **Retencja bezpieczeństwa poświadczeń (`customer-service`):** Włączona (`CUSTOMER_SECURITY_RETENTION_ENABLED=true`), worker `SecurityRetentionWorker` cyklicznie usuwa przeterminowane sesje, tokeny i wyzwania MFA (okno 7 dni) w celu ochrony poświadczeń.
- **Retencja komercyjna i RODO (D02):** Workery redakcji danych są zablokowane w kodzie (`BLOCKED_FAIL_CLOSED`, `RETENTION_EXECUTION_NOT_CONFIGURED`), a flaga aktywacji jest wyłączona (`activationAllowed = false`, `runtimeWired = false`).
- **Zerowe mutacje audytora:** Wartość `auditorExecutedDataMutations = 0` (oraz `executedDataMutations = 0`) potwierdza, że audytor gotowości działa ściśle w trybie pasywnym / read-only.
- **Synchronizacja decyzji operacyjnych z kontraktami maszynowymi:** Użytkownik formalnie zatwierdził decyzje operacyjne: D01 (5 lat retencji dokumentów finansowych), D09 (sejf wolumenu `privacy-journal-safe`), P09 (30 dni logów operacyjnych / ochrona logów bezpieczeństwa i CC), D17 (tryb fail-closed do momentu uruchomienia produkcji). W repozytorium maszynowe kontrakty bazowe (`retention-decisions.json`, `retention-policy.json`) celowo zachowują stan bazowy z audytu (`stageComplete = false`, stan `partial` dla D01 z przypiętym hashem SHA256 w testach bramkowych), co uniemożliwia przypadkową aktywację niszczenia danych na etapie deweloperskim i testowym. Wywołanie `--require-ready` deterministycznie kończy się kodem wyjścia 2 (`NOT_READY`).

**Aktualizacja 03.11.2026 — pakiet 163 (Krok 5 A43):** [dowody środowiskowe dev/test](A43_DOWODY_SRODOWISKOWE_DEV_TEST_163.md).
Skrypt `scripts/qa/environmental-evidence-check.py` zebrał dowody read-only w 8 kategoriach dla środowisk dev i test (zegary UTC, Redis streams, kopie zapasowe 14 baz, Mailpit SMTP, historia AI, rotacja JWT i MFA, role operatorów, rejestr usunięć). Raport: `tmp/a43-step5-evidence/environment-evidence-report.json`.

**Aktualizacja 02.11.2026 — pakiet 161 (Kroki 3/4 A43):** [protokół D02 i workery](A43_PROTOKOL_D02_WORKERY_161.md).
Domknięcie dwufazowego protokołu D02 dla 8 uczestników, integracja decyzji D01 i D09, weryfikacja workerów redakcji w trybie fail-closed (`RETENTION_EXECUTION_NOT_CONFIGURED`) oraz twardej ochrony 5 tabel finansowych (`hard_block = true`). 9 testów Testcontainers PostgreSQL w `order-service` (`CommerceRetentionRedactionPostgresIT`) PASS. Inwentarz: 145 tabel, 1307 kolumn, 232 migracje.

**Aktualizacja 02.11.2026 — pakiet 151:** [atomowa inspekcja](A43_ATOMOWA_INSPEKCJA_POWIAZANA_Z_WERSJA_151.md).
Order V50: atomowe sprawdzenie wersji i zapis próby inspekcji źródła. Rezerwacja zatwierdza się przed HTTP; zmiana stanu, stop, kwarantanna lub wygaśnięcie odrzucają wynik. Ten sam UUID odczytuje historię bez ponownej wysyłki i odnowienia czasu.
82 unikalne Java (75 PostgreSQL / 7 jednostkowych) + 116 Python PASS. Rewizja `a43.40-2026-11-02.6`; inwentarz 145/1307/232.
Bramki PASS; NOT_READY / 2. Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-9d0077d7/report.json`.
1 krok i 2 wersje pod kwarantanną, bez wznowienia. V50 tylko na fiksturach.
Wyłącznie jawna diagnostyka jednego źródła, bez prawa do redakcji lub aktywacji. Pełny D02, rzeczywisty wystawca/sprawy, tożsamości i piecza kluczy pozostają otwarte.
Następne: niezależny zakres A40 HTML/JS i indeksowalności bez zdjęć; elementy D02 wymagające decyzji/wartości użytkownika pozostają odłożone.

**Aktualizacja 02.11.2026 — pakiet 150:** [wersje i kontrola aktualności](A43_WERSJONOWANIE_PRZEGLADU_I_KONTROLA_AKTUALNOSCI_150.md).
Order V49: niezmienny rejestr wersji przeglądu, kanoniczne skróty zakresu/historii/wyniku i ponowne sprawdzenie pod blokadą rodzica. Zmiana danych, stop, kwarantanna lub upływ czasu odrzucają starą ocenę. Ponowienie UUID nie odnawia czasu lub podglądu.
72 unikalne Java (65 PostgreSQL / 7 jednostkowych) + 113 Python PASS; 45 nowych Java, 3 Python i 8 mutacji kontraktu.
Po korekcie izolacji fikstury ponowiono 27 testów; pozostałe niezależne
zestawy PASS, bez podwójnego liczenia. Bramki/candidate PASS;
A43.40/.41 nadal NOT_READY. Pełny drill 14 baz/Redis PASS: 2 wersje
zachowane pod kwarantanną, bez odnowienia. V49 tylko na fiksturach.
Rewizja `a43.40-2026-11-02.5`; inwentarz 144/1297/231.
CURRENT_LOCAL dotyczy chwili lokalnego sprawdzenia, nie jest upoważnieniem do wykonania. Rzeczywisty wystawca, proces spraw/tożsamości, klucz i rotacja/odwoływanie nadal do odbioru; bez operator API, konfiguracji dev/test lub aktywacji czyszczenia.
Następne: atomowe powiązanie sprawdzonej wersji z przygotowaniem kolejnego kroku, bez przenoszenia CURRENT_LOCAL między transakcjami jako zgody; następnie pełny protokół D02 z upoważnieniem sprawy i świeżymi dowodami.

**Aktualizacja 02.11.2026 — pakiet 149:** [zbiorczy przegląd siedmiu źródeł](A43_ZBIORCZY_PRZEGLAD_SIEDMIU_ZRODEL_149.md).
Zbiorczy przegląd siedmiu źródeł czyta wszystkie intencje pod jedną blokadą rodzica. Starsze stopy i nieznane wyniki pozostają widoczne, wspólne inspekcje liczone raz. Końcowy zegar weryfikuje świeżość wszystkich ocen. Siedem MATCH nie daje globalnej zgody.
106 unikalnych Java (94 PostgreSQL / 12 jednostkowych) + 110 Python PASS; 37 nowych Java i 8 mutacji kontraktu.
Nieudane przebiegi zachowane; końcowy pełny odbiór z jawnym odczytem fikstur,
bez osłabienia reguł lub doliczania powtórzeń. Bramki/candidate PASS;
A43.40/.41 nadal NOT_READY. Bez nowych tabel/migracji; schemat i kod restore bez zmian. Pełnego drillu nie powtarzano; ostatni PASS 14 baz/Redis z 147 jest dowodem historycznym.
Rewizja `a43.40-2026-11-02.4`; inwentarz 143/1286/230 bez zmian.
Rzeczywisty wystawca, proces spraw/tożsamości, klucz i rotacja/odwoływanie nadal do odbioru. Bez operator API, konfiguracji dev/test, RPC/DML przeglądu lub zgody na czyszczenie.
Następne: wersjonowanie zakresu i wyników przeglądu oraz ochrona przed wykorzystaniem nieaktualnej oceny w kolejnym kroku; nie zastępuje to świeżych dowodów źródłowych ani zweryfikowanej sprawy, następnie pełny protokół D02.

**Aktualizacja 02.11.2026 — pakiet 148:** [ocena postępu / inspekcja źródeł](A43_OCENA_POSTEPU_NAPRAW_I_INSPEKCJA_ZRODEL_148.md).
Ocena jednego zakresu rozdziela historię dostarczenia od świeżej inspekcji. MATCH nie rozlicza nieznanego zapisu. Liczy się najnowsza inspekcja po próbach/wynikach naprawy, z kontrolą epoch/rewizji. Stop i kwarantanna blokują pozytywną ocenę.
116 unikalnych Java (104 PostgreSQL / 12 jednostkowych) + 110 Python PASS; 57 nowych Java i 7 mutacji kontraktu.
Bramki/candidate PASS; A43.40/.41 nadal NOT_READY.
Bez nowych tabel lub migracji; kod restore i schemat bez zmian. Pełnego drillu nie powtarzano w 148; ostatni PASS 14 baz/Redis pochodzi z 147 i pozostaje dowodem historycznym.
Rewizja `a43.40-2026-11-02.3`; inwentarz 143/1286/230 bez zmian.
Rzeczywisty wystawca, proces spraw/tożsamości, klucz i rotacja/odwoływanie nadal do odbioru. Bez operator API, konfiguracji dev/test, automatycznego wykonania lub zgody na czyszczenie.
Następne: zbiorczy przegląd postępu siedmiu źródeł jednego wniosku i identyfikacja zakresów wymagających ręcznej oceny, bez automatycznego wznowienia, zmiany bindingów lub globalnej zgody; następnie kompletne dowody i pełne D02.

**Aktualizacja 02.11.2026 — pakiet 147:** [trwałe próby i potwierdzenia Order](A43_TRWALE_PROBY_I_POTWIERDZENIA_ORDER_147.md).
Order V48: niezmienne próby, wyniki i stop; COMMIT próby przed RPC, identyfikator próby jako challenge. Ten sam UUID nigdy nie wysyła ponownie, nowe próby tylko REPLAY_ONLY. Stop lub kwarantanna podczas odpowiedzi blokuje pozytywne potwierdzenie; nie dowodzi zakończenia wykonawcy.
139 unikalnych Java (125 PostgreSQL / 14 jednostkowych) + 110 Python PASS; 47 nowych Java, 3 Python i 7 mutacji.
Pełny drill 14 baz/Redis PASS: 2 próby / 1 wynik / 1 stop i 7 receiptów
źródeł zachowane pod kwarantanną. Pozostała próba bez wyniku, bez wznowienia.
V48 tylko na fiksturach. Rewizja `a43.40-2026-11-02.2`;
143 tabele / 1286 kolumn / 230 migracji. A43.40/.41 nadal NOT_READY.
Rzeczywisty wystawca upoważnień, proces weryfikacji spraw/tożsamości, klucz publiczny i rotacja/odwoływanie nadal do odbioru. Bez operator API, automatycznego wykonania lub konfiguracji dev/test.
Następne: ocena postępu napraw łącząca trwałą historię wysyłek ze świeżą inspekcją źródła, z rozróżnieniem dawnego potwierdzenia, nieznanego wyniku i stopu, bez globalnej zgody lub resetu budżetu; następnie kompletne dowody i pełne D02.

**Aktualizacja 02.11.2026 — pakiet 146:** [atomowy protokół źródłowy](A43_ATOMOWE_ODTWARZANIE_BLOKAD_ZRODLA_146.md).
Siedem źródeł: podpis Ed25519 dokładnego zakresu, CAS epoch/revision,
oryginalny UUID oraz wspólny COMMIT blokady, unieważnienia/outbox i receiptu.
Replay tej samej intencji potwierdza tylko historyczny skutek; kwarantanna
blokuje oba tryby. Order przygotowuje żądanie, bez weryfikacji sprawy
i bez trwałego dispatchera. 147 unikalnych Java (109 PostgreSQL / 38 jednostkowych) + 107 Python PASS; 92 nowe Java,
3 Python i 7 mutacji. Pełny drill 14 baz/Redis PASS: siedem źródłowych
receiptów i poprzednia historia zachowane pod kwarantanną, bez wznowienia.
Siedem migracji tylko na fiksturach. Rewizja `a43.40-2026-11-02.1`;
140 tabel / 1270 kolumn / 229 migracji. A43.40/.41 nadal NOT_READY.
Nowy zakres do odbioru użytkownika: zaufany wystawca upoważnień spraw, jego proces weryfikacji/tożsamości, publiczny klucz Ed25519, rotacja i odwoływanie upoważnień. Nie dodano wystawcy lub prywatnego klucza produkcyjnego; konfiguracja pozostaje wyłączona.
Następne: trwałe próby i potwierdzenia dispatchera Root, jawne odzyskiwanie wyłącznie przez replay oraz obsługa niepewnego COMMIT, kwarantanny, stopu i zmiany kontekstu; rzeczywisty wystawca upoważnień wymaga osobnego odbioru, potem pełne D02.

**Aktualizacja 01.11.2026 — pakiet 145:** [rozliczenia PENDING i intencje](A43_ROZLICZANIE_PENDING_I_INTENCJE_NAPRAWY_145.md).
Trwały znacznik zastąpienia odczytu PENDING świeżym późniejszym wynikiem
tej samej tożsamości, blokada późnego INSERT i niewykonane intencje
z oryginalnymi UUID. Nie oznacza zatrzymania wykonawcy lub upoważnienia
naprawy; budżet zachowany. 168 unikalnych Java (156 PostgreSQL / 12 jednostkowych) + 104 Python PASS; 56 nowych
Java, 3 Python i 7 mutacji. 88 przypadków powtórzono po optymalizacji,
bez doliczania. Pełny drill 14 baz/Redis PASS: 4 próby/2 wyniki/1 rozliczenie/
1 intencja zachowane pod kwarantanną bez wykonania; pozostałe PENDING
bez wyniku. Order V47 tylko na fiksturach. Rewizja `a43.40-2026-11-01.23`;
133 tabele / 1207 kolumn / 222 migracje. A43.40/.41 nadal NOT_READY.
Następne: atomowy protokół sprawdzenia źródła i odtworzenia brakującej negatywnej blokady z oryginalnym UUID, trwałymi próbami/potwierdzeniami oraz ochroną replay/restore; wykonanie wymaga zweryfikowanego upoważnienia sprawy, potem pełne D02.

**Aktualizacja 01.11.2026 — pakiet 144:** [kwalifikacja rozbieżności](A43_KWALIFIKACJA_ROZBIEZNOSCI_BLOKAD_144.md).
Zaufany snapshot 7 źródeł / 56 prób: każde PENDING blokuje kandydata,
stare/przyszłe obserwacje wymagają odczytu; dawne rozbieżności pozostają
policzone. Kandydat zachowuje UUID/guard/reason/probe/wersję i wymaga
sprawdzenia źródła oraz zweryfikowanej sprawy. Bez prawa wykonania,
RPC, DML lub globalnej zgody. 112 Java (100 PostgreSQL / 12 jednostkowych) + 101 unikalnych Python PASS;
44 nowe Java i 6 mutacji w istniejącym teście Python. Schemat/restore
bez zmian; pełny drill 143 historyczny, nie powtarzano go w 144.
Rewizja `a43.40-2026-11-01.22`; inwentarz 131/1196/221.
A43.40/.41 nadal NOT_READY. Następne: trwały kontrolowany protokół rozliczenia PENDING i intencji odtworzenia brakującej blokady; przed wykonaniem atomowe sprawdzenie źródła i zweryfikowane upoważnienie sprawy, potem pełne D02.

**Aktualizacja 01.11.2026 — pakiet 143:** [trwała historia porównań](A43_TRWALA_HISTORIA_POROWNAN_BLOKAD_143.md).
Próba zatwierdzana przed RPC, wynik dopisywany raz; UUID próby jako
challenge, do 8 prób/źródło. Retry tego samego UUID odczytuje wynik
albo PENDING bez nowego RPC. Kwarantanna zachowuje historię i blokuje
nowe próby; późny wynik tylko ROOT_QUARANTINED. Bez naprawy lub globalnej zgody.
177 Java (153 PostgreSQL / 24 jednostkowe) + 101 unikalnych Python PASS; 54 nowe Java, 3 Python, 6 mutacji
w istniejącym teście. Pełny drill 14 baz/Redis PASS: 2 próby/1 wynik
zachowane, PENDING bez wyniku i bez wznowienia. Order V46 tylko na
fiksturach. Rewizja `a43.40-2026-11-01.21`; 131 tabel / 1196 kolumn /
221 migracji. A43.40/.41 nadal NOT_READY. Następne: kontrolowane rozliczenie rozbieżności i kwalifikacja do naprawy z zachowaniem historii, kwarantanny i budżetów; potem zweryfikowane sprawy, kompletne wersjonowane dowody i pełne D02.

**Aktualizacja 01.11.2026 — pakiet 142:** [porównanie ACK ze źródłem](A43_UZGADNIANIE_BLOKAD_ZE_ZRODLEM_142.md).
Osobny chroniony odczyt 7 usług, jawny krok w Order i najwyżej jeden
RPC poza transakcją Root. Dokładna tożsamość, nowy challenge, wiek
odpowiedzi i kwarantanna przed/po RPC. Wykrywa brak blokady, konflikt,
nieznany zakup i kwarantannę; MATCH_AS_OF_READ pozostaje obserwacją
z chwili odczytu. Bez zapisu uzgodnienia, naprawy, resetu prób i globalnej zgody.
178 Java (154 PostgreSQL / 24 jednostkowe) + 98 unikalnych Python PASS; 64 nowe Java, 8 mutacji polityki
w istniejącym teście Python. Schemat/restore bez zmian; drill 140
historyczny, nie powtarzano go w 142. Rewizja `a43.40-2026-11-01.20`;
inwentarz 129/1183/220 bez zmian. A43.40/.41 nadal NOT_READY.
Następne: trwała ograniczona historia porównań i przegląd rozbieżności; potem kontrolowane rozliczenie/naprawa bez resetu prób, zweryfikowane sprawy, kompletne aktualne dowody i pełne D02.

**Aktualizacja 01.11.2026 — pakiet 141:** [postęp doręczania blokad](A43_POSTEP_DORECZANIA_BLOKAD_141.md).
Zaufany odczyt wymaga dokładnego zakupu/wniosku/referencji sprawy.
Spójny lokalny snapshot 7 liczników i historycznych ACK; próba bez ACK
to OUTCOME_UNKNOWN, 8 prób bez ACK to REVIEW_REQUIRED. Bez RPC, DML,
resetu prób lub zgody na mutację. Kwarantanna zachowuje bezpieczny stan.
70 Java (58 PostgreSQL / 12 jednostkowych) + 98 unikalnych Python PASS; 27 nowych PostgreSQL i 6 mutacji
polityki w istniejącym teście Python. Schemat/restore bez zmian;
pełny drill 140 pozostaje historyczny, nie powtarzano go w 141.
Rewizja `a43.40-2026-11-01.19`; inwentarz 129/1183/220 bez zmian.
A43.40/.41 nadal NOT_READY. Następne: uzgodnienie historycznych potwierdzeń z aktualnym źródłem po jego utracie/odtworzeniu; potem zweryfikowany proces spraw, kompletność i pełne D02.

**Aktualizacja 01.11.2026 — pakiet 140:** [dostarczanie blokad do źródeł](A43_DOSTARCZANIE_BLOKAD_DO_ZRODEL_140.md).
Jawny uwierzytelniony POST do 7 źródeł, próba zatwierdzona przed RPC
i historyczny ACK po COMMIT źródła. Do 8 prób/źródło; timeout, utrata
odpowiedzi i niepewny COMMIT nie dają fałszywego potwierdzenia.
Nieznany zakup pozostaje oczekujący. Restore zachowuje 2 próby i 1 ACK,
kwarantannuje rodzica; niepotwierdzona próba bez ACK, brak wznowienia.
87 Java (75 PostgreSQL / 12 jednostkowych) + 98 unikalnych Python PASS; pełny drill 14 baz/Redis PASS.
Rewizja `a43.40-2026-11-01.18`; 129 tabel / 1183 kolumny / 220 migracji.
V45 tylko na fiksturach; endpoint domyślnie wyłączony. ACK to historia,
bez aktualnej kompletności lub zgody na mutację; A43.40/.41 NOT_READY.
Następne: kontrolowany odczyt postępu, obsługa wyczerpanego budżetu i uzgodnienie po utracie/odtworzeniu pojedynczego źródła; potem zweryfikowany proces spraw, kompletność i pełne D02.

**Aktualizacja 01.11.2026 — pakiet 139:** [lokalne przyjmowanie wniosków](A43_PRZYJMOWANIE_WNIOSKOW_BLOKAD_139.md).
Order atomowo zapisuje negatywną blokadę zakupu, wniosek z referencją sprawy
i siedem planowanych identyfikatorów. Niepełny plan nie przejdzie COMMIT;
retry i konflikty zachowują tożsamość. Nie jest to weryfikacja sprawy,
własności lub potwierdzenie doręczenia; nie dodano transportu/operator API.
52 PostgreSQL + 96 unikalnych Python PASS; pełny drill 14 baz/Redis PASS: wniosek
w kwarantannie, siedem identyfikatorów i powiązania zachowane bez wysyłki.
Rewizja `a43.40-2026-11-01.17`; 127 tabel / 1172 kolumny / 219 migracji.
V44 tylko na fiksturach; A43.40/.41 nadal NOT_READY. Następne: ograniczony
uwierzytelniony transport i trwałe potwierdzenia, potem pełny proces spraw/D02.

**Aktualizacja 01.11.2026 — pakiet 138:** [rejestry blokad wszystkich źródeł](A43_TRWALE_BLOKADY_WSZYSTKICH_ZRODEL_138.md).
Payment, Inventory, Promotion, Delivery, Billing i Notification otrzymały
rejestry według wzorca Returns. Wszystkie 7 usług przechowuje i odczytuje
znane blokady; brak wpisu nadal oznacza UNKNOWN. Bez zwalniania lub TTL.
112 Java (97 PostgreSQL / 15 jednostkowych) + 90 unikalnych Python PASS; pełny drill 14 baz/Redis PASS:
7 rejestrów i 10 historycznych obserwacji (7 BLOCKED) zachowane.
Rewizja `a43.40-2026-11-01.16`; 125 tabel / 1160 kolumn / 218 migracji.
Sześć migracji tylko na fiksturach; A43.40/.41 nadal NOT_READY. Następne:
trwały proces wniosków/spraw, powiązanie i kompletność przed D02.

**Aktualizacja 01.11.2026 — pakiet 137:** [trwałe blokady Returns](A43_TRWALE_BLOKADY_RETURNS_137.md).
Returns otrzymało rejestr znanych blokad, związany ze stabilnym zakupem
przez generacje i epoki. SQL nadaje rewizję i atomowo unieważnia źródło/outbox.
Brak zwalniania, TTL, endpointu zapisu lub dodatniej zgody. Order zapisuje
hold_state; UNKNOWN zachowuje odcisk v1, BLOCKED używa v2.
132 Java (117 PostgreSQL / 15 jednostkowych) + 90 unikalnych Python PASS; pełny drill 14 baz/Redis PASS:
rejestr Returns i 4 obserwacje, w tym BLOCKED, zachowane bez upoważnienia.
Rewizja `a43.40-2026-11-01.15`; 119 tabel / 1118 kolumn / 212 migracji.
Migracje Returns V12 i Order V43 tylko na fiksturach; A43.40/.41 nadal NOT_READY.
Następne: pozostałe 6 rejestrów źródeł, kompletność spraw, następnie D02.

**Aktualizacja 01.11.2026 — pakiet 136:** [Inventory i macierz blokad D02](A43_POKRYCIE_BLOKAD_D02_136.md).
Inventory jest siódmym zdalnym źródłem negatywnych obserwacji. Aktywna
rezerwacja blokuje; stan terminalny lub brak rekordu daje UNKNOWN.
V42 rozszerza niezmienną historię; brak pozytywnej zgody lub mutacji.
Macierz 8 uczestników wskazuje lokalne źródła oraz luki globalnego pokrycia.
121 Java (107 PostgreSQL / 14 jednostkowych) + 90 unikalnych Python PASS; pełny drill 14 baz/Redis PASS,
3 obserwacje zachowane, w tym Inventory, bez aktualnego upoważnienia.
Rewizja `a43.40-2026-11-01.14`; 118 tabel / 1110 kolumn / 210 migracji.
A43.40/.41 nadal NOT_READY; V42 tylko na fiksturach, czyszczenie wyłączone.
Następne: trwałe pokrycie luk blokad/spraw, następnie upoważnienie/wykonanie D02.

**Aktualizacja 01.11.2026 — pakiet 135:** [awarie odzyskiwania rund](A43_AWARIE_ODZYSKIWANIA_RUND_135.md).
Odebrano timeout HTTP przed nagłówkami/w trakcie treści, kwarantannę
w czasie odpowiedzi i zmianę bindingu źródeł. Root nie udaje potwierdzenia,
a nowsza dzierżawa pozostaje chroniona. Rollback i niepewny COMMIT
rozliczenia nie powodują ponawiania potwierdzonych RPC.
46 PostgreSQL + 90 Python PASS: 24 nowe scenariusze / 22 regresje.
Rewizja `a43.40-2026-11-01.13`; 118 tabel / 1110 kolumn / 209 migracji.
Bez zmian produkcyjnego Java, schematu lub restore; bez nowego pełnego
drillu. A43.40/.41 nadal NOT_READY, czyszczenie nieaktywne. Następne:
kompletne źródła globalnych blokad i protokół upoważnienia/wykonania D02.

**Aktualizacja 01.11.2026 — pakiet 134:** [odzyskiwanie rund tylko RELEASE](A43_ODZYSKIWANIE_RUND_TYLKO_RELEASE_134.md).
Jawne przejęcie, monotoniczny cleanup-only, właściciel SQL/15 s/budżet 8;
stary proces nie może kontynuować oceny lub zapisywać jako nowy właściciel.
Bez PREPARE/schedulera/mutacji. 92 PostgreSQL + 90 Python PASS; drill
14 baz/Redis PASS, odtworzona własność usunięta bez wznowienia.
Order V41: 4 kolumny, 118 tabel / 1110 kolumn / 209 migracji.
Rewizja `a43.40-2026-11-01.12`; A43.40/.41 nadal otwarte.

**Aktualizacja 01.11.2026 — pakiet 133:** [ochrona replay PREPARE](A43_OCHRONA_POWTORZEN_PREPARE_133.md).
Siedem źródeł utrwala zamknięte/wydane tokeny. RELEASE przed PREPARE
uniemożliwia późniejsze odtworzenie dzierżawy; stary RELEASE nie usuwa
nowej. 7 tabel / 28 kolumn / 7 migracji, bez TTL lub RPC odzyskiwania.
59 PostgreSQL + 90 Python PASS; drill 14 baz/Redis PASS. Rewizja
`a43.40-2026-11-01.11`; 118 tabel / 1106 kolumn / 208 migracji.
A43.40/.41 nadal otwarte, bez mutacji lub aktywacji czyszczenia.

**Aktualizacja 01.11.2026 — pakiet 132:** [trwały rejestr rund obserwacji](A43_TRWALY_REJESTR_RUND_OBSERWACJI_132.md).
Order V40: 2 tabele/13 kolumn, zapis próby przed PREPARE i osobne
potwierdzenie RELEASE, odczyt po awarii i monotoniczna kwarantanna restore.
Bez RPC odzyskiwania, TTL historii lub upoważnienia. 94 PostgreSQL + 90 Python
PASS; pełny drill 14 baz/Redis PASS. Rewizja `a43.40-2026-11-01.10`;
111 tabel / 1078 kolumn / 201 migracji. A43.40/.41 nadal otwarte.

**Aktualizacja 01.11.2026 — pakiet 131:** [odbiór HTTP siedmiu źródeł](A43_ODBIOR_DZIERZAW_HTTP_SIEDMIU_ZRODEL_131.md).
Pełna runda z rzeczywistym Inventory, kontrolerami, filtrami i ośmioma bazami;
sprawdzono utratę odpowiedzi po COMMIT każdego źródła, stop i dostęp.
Inventory zwraca spójne 413 dla przekroczenia limitu żądania. To lokalna
fikstura z testową introspekcją, bez odbioru pełnych aplikacji dev/test.
27 PostgreSQL + 85 Python PASS; rewizja `a43.40-2026-11-01.9`.
A43.40/.41 nadal otwarte, bez mutacji lub aktywacji czyszczenia.

**Aktualizacja 01.11.2026 — pakiet 130:** [dzierżawy obserwacji intencji](A43_DZIERZAWY_OBSERWACJI_INTENCJI_130.md).
Koordynacja PREPARE/VERIFY siedmiu źródeł kontroluje intencję i zakres.
Każdy wysłany PREPARE ma RELEASE w `finally`, także po utracie odpowiedzi;
niepewność zwolnienia jest jawna, bez potwierdzania zatrzymania wykonawcy.
Odbiór 175 PostgreSQL + 85 Python PASS; nadal bez upoważnienia i mutacji.
Rewizja `a43.40-2026-11-01.8`, inwentarz bez zmian. A43.40/.41 otwarte,
czyszczenie wyłączone; zależne od wartości/decyzji zakresy odłożone.

**Aktualizacja 01.11.2026 — pakiet 129:** [ponowna ocena dowodów intencji](A43_WERYFIKACJA_DOWODOW_INTENCJI_129.md).
Powiązano osiem odczytów z niezmienionym przygotowaniem i zakresem intencji.
Stop przerywa dalszy przebieg; końcowa kontrola, claim i ocena są atomowe w SQL.
Wynik nie jest upoważnieniem, globalną migawką lub potwierdzeniem wykonania.
Rewizja `a43.40-2026-11-01.7`, bez zmian schematu i restore. A43.40/.41 nadal
otwarte, czyszczenie wyłączone; zależne od decyzji zakresy pozostają odłożone.

**Aktualizacja 01.11.2026 — pakiet 128:** [rejestr intencji D02](A43_REJESTR_INTENCJI_D02_128.md).
Order V39 dodaje przygotowanie/stop/anulowanie nierozpoczętej intencji
i atomowy niezmienny dziennik. Jedna intencja na generację, do czterech
zdarzeń, monotoniczna kwarantanna. Brak upoważnienia, mutacji i potwierdzenia
zakończenia wykonawcy. Inwentarz 109 tabel / 1065 kolumn / 200 migracji,
rewizja `a43.40-2026-11-01.6`. A43.40/.41 nadal otwarte, czyszczenie wyłączone.

**Aktualizacja 01.11.2026 — pakiet 127:** [trwała historia obserwacji](A43_TRWALE_OBSERWACJE_ZRODEL_127.md).
Order V38 dodaje niezmienną i ograniczoną historię sześciu źródeł,
we wspólnej transakcji z blokadą. Retry nie dubluje danych, sprzeczna
obserwacja wycofuje generację. Historia nie jest zgodą lub potwierdzeniem
redakcji. Inwentarz 107 tabel / 1045 kolumn / 199 migracji, rewizja
`a43.40-2026-11-01.5`. Drill 14 baz/Redis PASS z zachowaniem dwóch wpisów
historii i kwarantanną. A43.40/.41 nadal otwarte; bez aktywacji dev/test.

**Aktualizacja 01.11.2026 — pakiet 126:** [odbiór HTTP i awarii](A43_ODBIOR_HTTP_I_AWARII_126.md).
Lokalny przebieg sześciu źródeł ma odbiór autoryzacji, współbieżności,
ponowień i niepewnego COMMIT. Kolektor/workflow normalizują awarie SQL do
UNAVAILABLE; istniejący dowód rozpoznawany idempotentnie. Rewizja
`a43.40-2026-11-01.4`, bez migracji lub aktywacji. A43.40/.41 nadal otwarte;
zależne od decyzji zakresy odłożone, następne niezależne części protokołu D02.

**Aktualizacja 01.11.2026 — pakiet 125:** [obserwacje źródeł](A43_OBSERWACJE_ZRODEL_125.md).
Dodano obserwacje spraw/retry/DLQ sześciu usług, ograniczony protokół HTTP
tylko dla Order i kontrolowany krok workflow. Dodatnia globalna zgoda nie
wynika z braku wpisów lub terminalnego statusu. Rewizja `a43.40-2026-11-01.3`;
bez migracji lub aktywacji. Zakresy wymagające wartości/decyzji użytkownika
odłożone, następnie realizowane niezależne prace. A43.40/.41 nadal otwarte.

**Aktualizacja 01.11.2026 — pakiet 124:** [kolekcja dowodów i punkt wznowienia](A43_GLOBALNE_DOWODY_KOLEKCJA_124.md).
Order obserwuje rzeczywiste stany sagi/ponowień i znane blokady, a kolektor
ponownie sprawdza zakres po wywołaniach dostawców poza SQL. Nie przyjmuje
globalnej zgody z callbacku, skrótu planu D02 ani podpisu pojedynczego wpisu
rejestru; historyczne niepełne potwierdzenia nie przechodzą ponownej oceny.
V37 przywraca unieważnianie ocen przy zwykłych zmianach kontaktu i treści.
Rewizja `a43.40-2026-11-01.2`; 106 tabel / 1031 kolumn / 198 migracji.
A43.40/.41 nadal otwarte, czyszczenie nieaktywne; dowody w raporcie 124.

**Aktualizacja 01.11.2026 — pakiet 123:** [poprawki bezpieczeństwa](A43_POPRAWKI_BEZPIECZENSTWA_2026-11-01.md)
obejmują P09, trwałe dzierżawy siedmiu źródeł, nieodwracalność redakcji
i odmowę niekompletnych mutacji. 333 Java / 80 unikalnych Python oraz drill 14 baz/Redis — PASS. A43.40/.41 nadal otwarte;
pełny protokół wykonania, globalne dowody i integracje pozostają do realizacji.
Nie aktywowano czyszczenia ani migracji na rzeczywistych środowiskach.


**Aktualizacja 31.10.2026, pakiet 118 / rewizja `.15`:** wariant oceny po
ponownym odczycie ośmiu zapisanych obserwacji, z kontrolą zakresu/rewizji
oraz zachowaniem dzierżaw, prób i backoffu. Nie daje atomowej migawki lub
zgody na czyszczenie; A43.40/A43.41 nadal otwarte. Bez zmian schematu i aktywacji.
[Zakres i granice](A43_PONOWNA_WERYFIKACJA_OBSERWACJI.md).


**Aktualizacja 31.10.2026, pakiet 117 / rewizja `.14`:** dowód przeglądu
zatwierdzonej definicji D02 z przypiętym skrótem. Stare potwierdzenia bez
zgodnego powiązania nie kwalifikują się w ocenie, historia zachowana.
106 tabel/1017 pól, 185 migracji. Brak globalnej zgody, zmian CC i aktywacji;
A43.40/A43.41 nadal otwarte. [Zakres](A43_DOWOD_DEFINICJI_POL.md),
[stan końcowego odbioru](A43_ODBIOR_KONCOWY.md).


**Aktualizacja 31.10.2026, pakiet 116 / rewizja `.13`:** pomiar na 100 000
zadaniach, indeksowane wybudzanie ocen i porządkowanie wyczerpanych dzierżaw.
Jedna nowa dzierżawa, kontrola zakresu i blokad bez zmian; 106 tabel/1016 pól,
184 migracje. Odbiór lokalnego pobierania dużych zbiorów nie zamyka A43.41.
Brak aktywacji. [Pomiar i ograniczenia](A43_WYDAJNOSC_POBIERANIA_OCEN.md),
[aktualny stan](A43_ODBIOR_KONCOWY.md).


**Aktualizacja 31.10.2026, pakiet 115 / rewizja `.12`:** kontrolowane
przygotowanie generacji, zbieranie jednej obserwacji na krok i ocena wskazanego
zakupu, z checkpointami blokad i niezmiennym poprzednikiem. Rejestr
106 tabel/1016 pól/183 migracje. Brak aktywacji i operacji biznesowych;
rozproszona bariera i pełne A43.40/A43.41 pozostają otwarte.
[Opis zakresu](A43_PRZEBIEG_OCENY_ZAKUPU.md), [bieżące braki](A43_ODBIOR_KONCOWY.md).


**Aktualizacja 31.10.2026, pakiet 114 / rewizja `.11`:** znane blokady
zakupów utrwalane po stabilnym ID, aktualny odczyt w ocenie i ochrona po
izolowanym restore. Rejestr 106 tabel/1015 pól/182 migracje. Brak wpisu nie
potwierdza braku przeszkód; brak nowego API/CC, zwolnienia/TTL i czyszczenia.
**A43.40/A43.41 nadal otwarte, NOT_READY** — pozostały kod oraz D01/D09/P09
wymagają domknięcia. [Bieżący odbiór i braki](A43_ODBIOR_KONCOWY.md).


**Przed zamknięciem:** [rejestr odpowiedzi D01–D18, brakujących parametrów i wyjaśnienie D14](A43_POZOSTALE_PRACE_I_DECYZJE.md), aktualizacja 30.10.2026 po A44.3. D03: 7 dni zatwierdzone; D04/D05: 7/30 dni z potwierdzeniem odbiorców; D02: 1 rok do doprecyzowania zegara/zakresu. Bieżące parametry D01 i inne braki ustalamy w czacie, a asystent zapisuje odpowiedzi w rejestrze. D10/D13/D16 odłożone; D17=A bez aktywacji. Wersja wykonawcza macierzy nadal wymaga aktualizacji w A43.40.

**Plan zamknięcia — aktualizacja 30.10.2026:** po wykonanych lokalnie A43.1–A43.39 pozostają
**2 etapy A43.40–A43.41**: docelowa polityka retencji i końcowy odbiór.
Dowody rzeczywistych kopii/dostawców nadal wymagają ręcznego uzupełnienia. Zakres każdego etapu, zależności,
warunki zakończenia i pokrycie wszystkich zasobów opisuje
[plan zamknięcia A43](A43_PLAN_ZAMKNIECIA.md). Etap końcowy to A43.41.
Plan nie oznacza wykonania; wymagane nierozstrzygnięte decyzje lub dowody
pozostawiają odpowiedni etap otwarty. A05, całe A41 i zmiany samych zdjęć
pozostają odłożone. Następne wdrożenie: A43.40.

**A43.35 — wykonane i odebrane lokalnie 29.10.2026.** Pięć adapterów
domenowych, wybrane usuwanie kontaktu/adresu i treści zamkniętych zgłoszeń,
trwały postęp i ochrona starych zapisów. 458 Java (46 PostgreSQL),
278 Python i 4 grup HTTP — PASS. Rzeczywista bramka finansowa oczekuje
na A43.36; aktywacja wyłączona do A43.40. Globalne complete=false.
[Zakres i zależności](A43_USUWANIE_DANYCH_ZAMOWIEN.md).

**A43.34 — wykonane i odebrane lokalnie 29.10.2026.** Adaptery rdzenia
konta, wybrana polityka recenzji/Redis i osobny wniosek czatu po tokenie,
bez automatycznego kasowania rozmowy/powiadomień. MANUAL_REVIEW i ręczna
procedura A43.39, bez powiązania konta/e-maila. 327 Java (w tym 38
PostgreSQL), 277 Python i 6 grup HTTP — PASS. Aktywacja domyślnie wyłączona;
całościowe complete=false/dataErased=false. Rejestr 70 tabel/666 kolumn,
145 migracji i 62 unikalności. [Zakres](A43_USUWANIE_PROFILU_I_TRESCI.md).

**A43.33 — wykonane i zweryfikowane lokalnie 29.10.2026.** Protokół
żądania, aktualne hasło/MFA, odcięcie konta, 14 barier i trwałe kroki.
Wybór użytkownika: minimalny znacznik ID-only bez automatycznego TTL
do A43.40 oraz osobny token statusu przez dokładnie 168h. Wznowienia,
wyścigi, izolacja ról i wygaśnięcie odebrane na PostgreSQL/HTTP.
WAITING_ADAPTERS nie oznacza usunięcia danych; complete=false/dataErased=false.
Rejestr 68 tabel/653 kolumny/142 migracje, 40 mechanizmów, 17 kopii.
[Kontrakt i dowody](A43_PROTOKOL_USUWANIA_KONTA.md); `tmp/audit-a43-33/report.json`.

**A43.32 — wykonane i zweryfikowane lokalnie 28.10.2026.** Profil sklepu
obsługuje zbiorczy eksport, wznowienia/retry/stop/cancel i pobranie manifestu.
Dokładne kwoty i duże ID, SHA-256, PL/EN, ograniczone porcje i plik 16 MiB
w odbiorze. Godzina dostępności z A43.31 zachowana; pobrane pliki pozostają
użytkownika. Session fencing i prywatne bufory/Blob URL, jawna częściowość
oraz osobne dowody/ręczna ocena. 227 Node, 274 Python, 38 unikalnych Java,
44 scenariusze czterech przeglądarek i 54 regresje — PASS; rzeczywista
przeglądarka/Gateway/Customer/izolowany PostgreSQL pobrała 26 części.
Bez nowego widoku CC, migracji, sekretów i wdrożenia dev/prod.
[Instrukcja](A43_POBIERANIE_EKSPORTU_KONTA.md); `tmp/audit-a43-32/report.json`.

**A43.31 — wykonane i zweryfikowane lokalnie 28.10.2026.** Koordynator
eksportu: wybrano tymczasowe ograniczone porcje JSON w Customer DB oraz
dokładnie godzinę dostępności. Wznawianie/idempotencja, lease, kontrola
właściciela, cancel, limity i porcjowe czyszczenie. Manifest 14 właścicieli
ujawnia awarie, ręczne zakresy, osobny dowód Chat i kopie; READY oznacza
wyłącznie zakres automatyczny, complete=false. 113 Java, 274 Python,
14 baz PostgreSQL i 6 grup HTTP — PASS; 52 tabele/592 kolumny/128 migracji,
38 mechanizmów, 16 kopii i 28 wymagań unikalności. Wdrożenie lokalne kodu,
bez danych środowiska. Interfejs A43.32 i dalsze procedury pozostają otwarte.
[Kontrakt](A43_KOORDYNATOR_EKSPORTU.md); `tmp/audit-a43-31/report.json`.

**A43.30 — wykonane i zweryfikowane lokalnie 28.10.2026.** Macierz
49 tabel/562 kolumn, pięciu magazynów Redis, piętnastu kopii i 93 pominięć.
Wybrano istniejące projekcje biznesowe + ręczną ocenę pozostałych danych;
bez surowych kopii/sekretów i nowych eksportów stanów technicznych.
Właściciel, graf, decyzja, uzasadnienie, procedura i dowód każdego pola.
274 Python (28 nowych), 37 ukierunkowanych regresji Java i HTTP — PASS.
Oracle 14 baz potwierdził 127 migracji/562 kolumny i 22 wymagania unikalności.
CI wykrywa nieprzejrzane kolumny, pominięcia, źródła projekcji/tożsamości.
Plan event_key z e-mailem jest wejściem A43.36, nie migracją; ID/hash/
deduplikacja i retencje bez zmian. Rejestr 37 mechanizmów; pełny eksport,
usuwanie i kopie nadal otwarte. Pozostaje 11 etapów A43.31–A43.41.
Instrukcja: [klasyfikacja techniczna](A43_KLASYFIKACJA_DANYCH_TECHNICZNYCH.md).
Raport: `tmp/audit-a43-30/report.json`. A05, całe A41 i zdjęcia odłożone.

**A43.29 — wykonane i zweryfikowane lokalnie 28.10.2026.** Własna
historia audytu i metadane TOTP/WebAuthn/kodów odzyskiwania. Wybrane przez
użytkownika: jawny podmiot nowych zdarzeń + wąski wyjątek legacy refresh ID,
bez dopasowania historii po e-mailu; swobodne notatki/etykiety/metadata przez
ręczną ocenę podmiotu i redakcję. Sekrety, credential_id i obce tożsamości
poza automatyczną projekcją. Macierz pól i konkretna procedura zastępują
pending reviewed projection; prywatnych notatek Chat nie przechowuje.
360 unikalnych Java (359 regresja + 1 aktualizacja V15), 246 Python i 7 grup
HTTP — PASS. Siedem tabel bez zmian przez odczyty, zero Notification;
kontrolowany zapis/wyłączenie konta tylko w fixture. Stare poświadczenia
i audyt zachowane po migracji. Rejestr: 49 tabel, 127 migracji, 36 mechanizmów.
Pozostaje 12 etapów A43.30–A43.41. Bez wdrożenia dev/prod; A05, całe A41 i zdjęcia odłożone.
Instrukcja: [historia, metadane i ocena treści](A43_OCENA_NOTATEK_I_DANYCH_BEZPIECZENSTWA.md).
Raport: `tmp/audit-a43-29/report.json`.

**A43.28 — wykonane i zweryfikowane lokalnie 28.10.2026.** Eksport
rezerwacji magazynowej/pozycji oraz rezerwacji/wykorzystania kuponów własnego
zamówienia. Każdy nagłówek i strona wymagają aktualnego właściciela w Order;
brak obejścia operatora/usługi/gościa i dopasowania po e-mailu/kodzie.
Wyłącznie jawne dane transakcji; globalne stany/operacje, definicje/limity
i klucze operacyjne poza eksportem. 238 Java, 246 Python i 10 grup HTTP
— PASS. Dziesięć tabel bez zmian, zero wywołań biznesowych; TTL, zapas,
limity i trwałe wykorzystania zachowane. Dwa indeksy, rejestr 49 tabel,
125 migracji, 35 mechanizmów. Własne fixture, bez wdrożenia dev/prod.
Pozostaje 13 etapów A43.29–A43.41. A05, całe A41 i zdjęcia nadal odłożone.
Instrukcja: [rezerwacje i kupony](A43_EKSPORT_REZERWACJI_KUPONOW.md).
Raport: `tmp/audit-a43-28/report.json`.

**A43.27 — wykonane i zweryfikowane lokalnie 28.10.2026.** Eksport
powiadomień własnego zamówienia i odpowiedzi czatu przez token rozmowy.
Decyzja użytkownika w oknie opcji: tylko wiadomości do rozmówcy, bez
komunikatów i metadanych do obsługi. Każda strona weryfikuje graf źródła;
brak dopasowania po recipient/e-mailu i brak obejścia operatora/usługi.
Bez diagnostyki, tokenów i linków bezpieczeństwa; jawna kompaktacja,
ograniczone strony i brak wysyłek/replay. 297 Java, 246 Python i 11 grup HTTP
— PASS. Rejestr: 49 tabel, 123 migracje, 34 mechanizmy. Bazy niezmienione,
zero SMTP; własne izolowane fixture, bez wdrożenia dev/prod. Pozostaje
14 etapów A43.28–A43.41. A05, całe A41 i zdjęcia nadal odłożone.
Instrukcja: [eksport powiadomień](A43_EKSPORT_POWIADOMIEN.md).
Raport: `tmp/audit-a43-27/report.json`.

**A43.26 — wykonane i zweryfikowane lokalnie 28.10.2026.** Użytkownik
wybrał w oknie opcji osobny eksport przez token rozmowy, bez powiązania
z kontem/e-mailem. Nagłówek i strony do 50 publicznych wpisów, dowód
weryfikowany w tym samym SQL co odczyt. JWT sam nie daje dostępu;
operator/usługa nie mają obejścia. Brak tokenu/formatu 401, błędny token,
brak rozmowy/hasza 404. Bez zwracania hashy, request_key i danych powiadomień.
**184 Java, 246 Python i siedem grup HTTP JAR — PASS**: dwa tokeny dla
rozmów ze wspólnym e-mailem, 105 wpisów, role/odmowy, brak zmian historii/
outboxu i zero wysyłek, anonimowy odczyt po wyłączeniu własnego Customer.
Kod/kontrakt Java bez zmian po PASS; poprawiono oczekiwanie testu POST
gościa z 403 na 401 i ponowiono cały HTTP. Bez migracji i powiązań konta;
Testcontainers/profil Failsafe tylko do testów Chat. Rejestr/CI obejmują
dwie częściowe projekcje. Pozostaje 15 etapów A43.27–A43.41.
Bez danych użytkownika i wdrożenia dev/prod. A05, całe A41 i zdjęcia odłożone.
Instrukcja: [A43.26 — eksport czatu](A43_EKSPORT_CZATU.md).
Raport: `tmp/audit-a43-26/report.json`.

**A43.25 — wykonane i zweryfikowane lokalnie 28.10.2026.** Eksport nagłówka
faktury, osobnych stron pozycji/korekt i istniejących PDF własnego zamówienia.
Order potwierdza właściciela także przed cache PDF, niezależnie od lokalnego
customer_id. Zachowane dane nabywcy, kwoty i modele legacy/cumulative,
bez przeliczania/wystawiania dokumentów i zmiany numeracji. JSON wyklucza
idempotency i surową wersję księgi; PDF zachowuje dotychczasową prezentację.
**276 Java, 246 Python i sześć grup HTTP JAR — PASS**, w tym 26 PostgreSQL
i 12 Redis w wyniku Java, 105 pozycji/korekt, stare/puste customer_id,
izolacja kont/gościa, rozgrzany obcy/niespójny PDF, awaria Order i brak
zmian wszystkich sześciu tabel Billing/sekwencji. Rejestr/CI obejmują trzy
częściowe projekcje, brak migracji i nowych zależności. Pozostaje 16 etapów
A43.26–A43.41. Bez danych użytkownika i wdrożenia dev/prod. A05, całe A41
i zmiany samych zdjęć nadal odłożone.
Instrukcja: [A43.25 — eksport faktur](A43_EKSPORT_FAKTUR.md).
Raport: `tmp/audit-a43-25/report.json`.

**A43.24 — wykonane i zweryfikowane lokalnie 28.10.2026.** Własne zamówienie
ma eksport zapisanej płatności i opcjonalnego zwrotu, po każdorazowej
weryfikacji właściciela w Order. Jedna jawna projekcja bez sekretów,
referencji dostawcy, danych operatora i koordynacji. Awaria Order oraz
niezgodne order_id zwrotu dają 503; cudze/gościnne/brakujące zamówienie
i brak płatności 404. Brak zwrotu pozostaje null. **276 Java, 246 Python
i siedem grup HTTP JAR — PASS**, także rola PostgreSQL bez SELECT pól
chronionych i zero żądań do lokalnej atrapy dostawcy. Bez migracji,
wywołań Stripe, zmian pieniędzy/retencji i danych użytkownika. Rejestr/CI
obejmują obie częściowe projekcje. Plan: 17 etapów do A43.41; wybory danych
będą przedstawiane użytkownikowi w oknie z opcjami. A05, całe A41 i zdjęcia
nadal odłożone. Bez wdrożenia dev/prod.
Instrukcja: [A43.24 — eksport płatności](A43_EKSPORT_PLATNOSCI.md).
Raport: `tmp/audit-a43-24/report.json`.

**A43.23 — wykonane i zweryfikowane lokalnie 28.10.2026.** Zwroty i reklamacje
mają osobne ograniczone eksporty zgłoszeń, pozycji i statusów. Dzieci są
czytane z kontrolą właściciela w tej samej migawce SQL; cudza/brakująca
sprawa daje 404, własna pusta 200. Bez notatek/tożsamości operatorów,
hashy, wersji i metadanych operacji. Tekst autora i zapisane statusy pozostają
dosłowne; LEGACY_SNAPSHOT zachowuje znaczenie migawki migracyjnej. Brak
wspólnej migawki między stronami i pełnej historii starszych spraw.
**186 Java, 246 Python i odbiór JAR przez Returns/oba aliasy Gateway — PASS**:
55 zgłoszeń, 105 pozycji i zdarzeń, dwa konta, stare/puste sprawy, ograniczona
rola PostgreSQL i brak zmian danych. Rejestr/CI obejmują trzy częściowe
projekcje. Bez migracji i nowych zależności runtime; Testcontainers/profil
Failsafe tylko do testów Returns. A43 nadal otwarte: pełny eksport,
usuwanie/kopie, ocena notatek i decyzje retencji. Bez danych użytkownika
i wdrożenia dev/prod. A05, całe A41 i zmiany samych zdjęć nadal odłożone.
Instrukcja: [A43.23 — eksport zwrotów](A43_EKSPORT_ZWROTOW.md).
Raport: `tmp/audit-a43-23/report.json`.


**A43.22 — wykonane i zweryfikowane lokalnie 28.10.2026.** Eksport danych
przesyłki sprawdza właściciela w Order na każde żądanie, bez cache i bez
uprawnień operatora/usługi/gościa. Cudze, gościnne i brakujące zamówienie
daje 404; awaria weryfikacji 503 przed odczytem bazy Delivery. Jedna jawna
projekcja przez istniejący unikalny indeks nie wybiera pól synchronizacji.
Brak wspólnej migawki i historii wcześniejszych stanów. **275 Java, 246 Python
i odbiór JAR przez Delivery/oba aliasy Gateway — PASS**, w tym zatrzymanie
własnego Order i brak fallbacku. Rejestr/CI obejmują częściową projekcję;
bez migracji i nowych zależności runtime. Testcontainers/profil Failsafe
dodano tylko do testów Delivery. A43 nadal otwarte: pełny eksport, usuwanie,
kopie i decyzje retencji. Bez danych użytkownika i wdrożenia dev/prod.
A05, całe A41 i zmiany samych zdjęć nadal odłożone.
Instrukcja: [A43.22 — eksport przesyłek](A43_EKSPORT_PRZESYLEK.md).
Raport: `tmp/audit-a43-22/report.json`.


**A43.21 — wykonane i zweryfikowane lokalnie 28.10.2026.** Własne zamówienia
i pozycje mają oddzielne eksporty po maksymalnie 50 wierszy, bez nieograniczonej
kolekcji dzieci. Własność pozycji jest sprawdzana atomowo z odczytem; cudze,
gościnne i brakujące zamówienie dają 404, własne puste — pustą listę.
Sekrety i koordynacja sagi wykluczone; zachowane zapisane kwoty, dane odbiorcy
i legacyLine bez semantycznej redakcji. V22 dodaje indeks pozycji, którego
budowa może blokować zapisy. **242 Java, 246 Python i odbiór JAR trzech ścieżek
HTTP — PASS**, także dane historyczne i guest capability. Rejestr/CI obejmują
obie częściowe projekcje. Pełny eksport między usługami, usuwanie i kopie
nadal otwarte. Bez danych użytkownika, zmian domenowych, restartu CC i
wdrożenia dev/prod. A05, całe A41 oraz zmiany samych zdjęć nadal odłożone.
Instrukcja: [A43.21 — eksport zamówień](A43_EKSPORT_ZAMOWIEN.md).
Raport: `tmp/audit-a43-21/report.json`.

**A43.20 — wykonane i zweryfikowane lokalnie 28.10.2026.** Właściciel
eksportuje zachowany log rekomendacji przez
`GET /api/v1/recommendations/me/data-export`. Odpowiedź wskazuje opcjonalny
zakres, bieżący stan zapisu i brak pełnej historii. Eksport nie włącza
logowania, nie odtwarza usuniętych danych ani nie przypisuje anonimowych
wierszy do konta. Odczyt jest ograniczony do 100 wpisów na stronę; V3 dodaje
indeks, którego budowa może blokować zapisy. **175 Java, 246 Python i odbiór
JAR trzech ścieżek HTTP — PASS**, w tym dotychczasowa retencja PostgreSQL.
Rejestr/CI obejmują nowy częściowy eksport. Pełne operacje eksportu/usuwania,
kopie i polityki nadal otwarte. Bez danych użytkownika, zmiany zapisu/retencji,
restartu CC i wdrożenia dev/prod. A05, całe A41 i zmiany samych zdjęć odłożone.
Instrukcja: [A43.20 — log rekomendacji](A43_EKSPORT_LOGU_REKOMENDACJI.md).
Raport: `tmp/audit-a43-20/report.json`.

**A43.19 — wykonane i zweryfikowane lokalnie 28.10.2026.** Właściciel pobiera
własne recenzje we wszystkich statusach przez `GET /api/v1/reviews/me/data-export`.
Jawne SQL/DTO wykluczają notatki moderatora i powiązania zamówień; odczyt
działa też na roli PostgreSQL bez dostępu do tych kolumn. Maksymalnie 50 wpisów
na stronę, istniejący indeks i brak nowej migracji. Treść autora jest dosłowna,
bez semantycznej redakcji, a strony nie tworzą wspólnej migawki. **162 Java,
246 Python oraz odbiór JAR przez port Review i oba aliasy Gateway — PASS.**
CI i rejestr obejmują nowy częściowy eksport. Pełny proces między usługami,
usuwanie/anonimizacja i kopie nadal otwarte. Bez danych użytkownika, restartu
CC i wdrożenia dev/prod. A05, całe A41 i zmiany samych zdjęć nadal odłożone.
Instrukcja: [A43.19 — eksport recenzji](A43_EKSPORT_RECENZJI.md).
Raport: `tmp/audit-a43-19/report.json`.

**A43.18 — wykonane i zweryfikowane lokalnie 28.10.2026.** Zalogowany
właściciel może pobrać strony własnej Wishlist przez
`GET /api/v1/wishlists/me/data-export`. Odczyt jest ograniczony do 100 pozycji,
ma jawny kursor i wskazuje brak wspólnej migawki pomiędzy żądaniami. Operator,
usługa i anonim nie otrzymują dostępu. Migracja V2 dodaje indeks właściciela
i ID; jego budowa może czasowo blokować zapisy. **151 Java, 246 Python oraz
odbiór HTTP trzech ścieżek — PASS** na izolowanych danych. CI uruchamia testy
PostgreSQL w profilu integration i odbiór Gateway. Pełny eksport między
usługami, usuwanie/anonimizacja i kopie pozostają otwarte. Bez zmian danych
użytkownika, restartu CC i wdrożenia dev/prod; A05, całe A41 oraz zmiany
samych zdjęć pozostają odłożone. Instrukcja:
[A43.18 — eksport Wishlist](A43_EKSPORT_WISHLISTY.md).
Raport: `tmp/audit-a43-18/report.json`.

**A43.17 — wykonane i zweryfikowane lokalnie 28.10.2026.** Panel profilu
sklepu umożliwia pobranie częściowego JSON, opisuje jego zakres i nie zapisuje
szkicu formularza. Odświeżenie sesji, retry, blokada równoległych kliknięć,
anulowanie po logout/zmianie konta i kontrola odpowiedzi chronią pobranie.
Języki PL/EN, nazwa/opis dla technologii asystujących i status operacji
są dostępne. **210 JavaScript, 36 testów eksportu w czterech przeglądarkach,
35 regresyjnych Chromium i 27 testów rejestru — PASS.** Przejrzano widoki
desktop/mobile; zakres kontroli axe nie stanowi deklaracji pełnego WCAG.
Pełny eksport innych usług, usuwanie/anonimizacja i kopie pozostają otwarte.
Bez danych kont użytkownika, restartu CC i wdrożenia dev/prod; A05, całe A41
i zmiany samych zdjęć pozostają odłożone. Instrukcja:
[A43.17 — pobieranie profilu w sklepie](A43_POBIERANIE_PROFILU_W_SKLEPIE.md).
Raport: `tmp/audit-a43-17/report.json`.

**A43.16 — wykonane i zweryfikowane lokalnie 28.10.2026.** Zalogowany
właściciel pobiera częściowy profil i agregaty MFA przez
`GET /api/v1/customers/me/data-export` (także `/api` i port Customer).
Jawna projekcja SQL/DTO nie wybiera credential fields; jedno zapytanie
sprawdza aktualny aktywny podmiot, a odpowiedź zawiera `complete: false`,
manifest pominięć i nagłówki załącznika/private-no-store/nosniff.
Brak eksportu dla operatora/usługi i brak wyboru podmiotu przez query/header.
**239 Java i 246 Python — PASS, bez pominiętych**, w tym 15 nowych Java
i jeden Python. PostgreSQL z ograniczonymi prawami kolumn oraz JAR-y
Customer/Gateway, dwa konta, sześć odmów i OpenAPI — PASS. CI uruchamia
nowy odbiór; rejestr ma cztery częściowe projekcje i 23 mechanizmy.
Pełny eksport usług, historia bezpieczeństwa/redakcja audytu, usunięcie/
anonimizacja i kopie nadal otwarte. Nie zmieniano danych użytkownika,
nie włączano retencji i nie wdrażano dev/prod. Bez zmian widoków CC/sklepu;
A05, całe A41 i same zdjęcia nadal odłożone. Instrukcja:
[A43.16 — eksport profilu konta](A43_EKSPORT_PROFILU_KONTA.md).
Raport: `tmp/audit-a43-16/report.json`.

**A43.15 — wykonane i zweryfikowane lokalnie 27.10.2026.** Kontrakt metadanych
obejmuje 14 właścicieli baz, 49 tabel/120 migracji, 5 przestrzeni Redis,
10 źródeł Java Redis i 15 rodzajów kopii. Wskazuje relacje klienta,
credential exclusions, zależności deduplikacji, 22 istniejące mechanizmy
i ograniczenia retencji. Nowa bramka CI blokuje nieprzejrzane zmiany
migracji/Redis i luki rejestru; nie łączy się z aplikacją. Kontrakt znajduje
się poza docs, więc jego zmiana nie jest pomijana przez paths-ignore.
**245 Python — PASS, bez pominiętych**, w tym 26 nowych i istniejące
12 Kubernetes; 15 modułów/54 fasady — PASS. Instrukcja:
[A43.15 — rejestr cyklu życia danych](A43_REJESTR_CYKLU_ZYCIA_DANYCH.md).
Raport: `tmp/audit-a43-15/report.json`. Zero operacji na danych aplikacji.
Rejestr nie oznacza wykonania eksportu/usunięcia konta ani selektywnej
obsługi backupów: pozostają pending. Dalsze implementacje, okresy i
aktywacje pozostają w całym A43. Bez restartu/wdrożenia dev/prod; A05,
całe A41 i zmiany samych zdjęć nadal odłożone.

**A43.14 — mechanizm wykonany i zweryfikowany lokalnie 27.10.2026.** Billing
otrzymał retencję streamu invoice-events: observe/off/trim, atomowe Lua,
twardy budżet XRANGE/XDEL i ochronę granic odczytu/pending wszystkich grup.
Brak wymaganej grupy, jawnej polityki lub ponad 32 grupy blokuje usuwanie;
brak odbiorców wymaga świadomego ALLOW_NO_GROUPS. Domyślnie observe/30 dni.
**181 jednostkowych Java + 15 PostgreSQL + 12 Redis = 208 — PASS**, w tym
25 nowych. Redis 7.4.9 sprawdził granice, duże ID, opróżnienie streamu,
współbieżny odczyt/ACK/publikację i odzyskanie. 12 Kubernetes, osiem
parametrów i kontrola 15 JAR-ów — PASS. Instrukcja:
[A43.14 — stream faktur](A43_RETENCJA_STREAMU_FAKTUR.md).
Raport: `tmp/audit-a43-14/report.json`. Wykryty brzegowy zakres poniżej 0-0
naprawiono, ponowiono odbiór i pełną regresję. Własne bazy/Redis/fixture,
bez brokera użytkownika i wdrożenia dev/prod. Okno historii, deklaracja
odbiorców i trim pozostają do decyzji; dalszy cykl życia danych/kopii
i anonimizacja pozostają w całym A43. A05, całe A41 i zmiany zdjęć odłożone.

**A43.13 — mechanizm wykonany i zweryfikowany lokalnie 27.10.2026.** Notification
może kompaktować subject/message/failure_reason starych RESOLVED ze starym
potwierdzonym SENT bez dzierżaw/retry/błędu. V13 dodaje znacznik, CHECK,
indeks i @Version; natywna partia zwiększa wersję. Świeży odczyt pod blokadą
chroni replay/resolve przed odtworzeniem treści ze starej encji; powtórne
zamknięcie jest idempotentne. OPEN/FAILED i niespójne stany pozostają chronione.
Polityka niezależna od SENT/REJECTED, domyślnie observe/30 dni. **163
jednostkowe Java + 29 PostgreSQL = 192 — PASS**, w tym 27 nowych; wynik
obejmuje wcześniejsze 17 PostgreSQL A43.10/A43.12. 12 Kubernetes,
konfiguracja i kontrola 15 JAR-ów — PASS. Instrukcja:
[A43.13 — treść zamkniętych DLQ](A43_RETENCJA_TRESCI_ZAMKNIETYCH_DLQ.md).
Raport: `tmp/audit-a43-13/report.json`. Własne bazy/fixture; wykryty konflikt
wersji starego obiektu JPA naprawiono i powtórzono pełną regresję. Bez
wysyłki wiadomości, danych użytkownika i wdrożenia dev/prod. Okno/compact,
dalszy cykl życia inboxów/zdarzeń/znaczników i procedury danych usług/kopii
pozostają w całym A43. A05, całe A41 i zmiany samych zdjęć nadal odłożone.

**A43.12 — mechanizm wykonany i zweryfikowany lokalnie 27.10.2026.** Notification
może kompaktować stary subject/message potwierdzonych SENT bez dzierżaw,
błędu i terminu retry. V12, materializowane partie z SKIP LOCKED i trzy daty
chronią cykl życia. ID/odbiorca i deduplikacja pozostają; API dodaje jawne
contentCompacted oraz tekst usunięcia. Rzeczywiste duplikaty/redelivery,
spóźnione complete, FAILED/replay i DLQ potwierdziły ochronę wysyłki.
Domyślnie observe/30 dni, niezależnie od diagnostyki odrzuceń. **148
jednostkowych Java + 17 PostgreSQL = 165 — PASS**, w tym 24 nowe; wynik
obejmuje osiem wcześniejszych PostgreSQL A43.10. 12 Kubernetes, konfiguracja
i 15 JAR-ów — PASS. Instrukcja:
[A43.12 — treść wysłanych powiadomień](A43_RETENCJA_TRESCI_WYSLANYCH_POWIADOMIEN.md).
Raport: `tmp/audit-a43-12/report.json`. Własne bazy/fixture, bez wysyłki
wiadomości, zmiany danych użytkownika i wdrożenia dev/prod. Okno/compact,
dalsza retencja inboxów/DLQ/zdarzeń i pełne procedury danych usług/kopii
pozostają w całym A43. A05, całe A41 i zmiany samych zdjęć nadal odłożone.

**A43.11 — mechanizm wykonany i zweryfikowany lokalnie 27.10.2026.** Billing
otrzymał kompaktowanie payload/error starych REJECTED bez related_id, z trwałym
ID/hash i ochroną ponowień. V11, indeks częściowy i materializowane partie
z SKIP LOCKED zachowują budżet oraz cykl życia. Przyjęty receipt, faktura
z pozycją i outbox pozostały niezmienione. Polityka inboxu jest niezależna
od retencji opublikowanego outboxu faktur; domyślnie observe/30 dni.
**168 jednostkowych Java + 15 PostgreSQL = 183 — PASS**, w tym 19 nowych;
wynik zawiera siedem wcześniejszych PostgreSQL A43.5. 12 Kubernetes,
konfiguracja i kontrola 15 JAR-ów — PASS. Instrukcja:
[A43.11 — odrzucone zdarzenia Billing](A43_RETENCJA_ODRZUCONYCH_ZDARZEN_BILLING.md).
Raport: `tmp/audit-a43-11/report.json`. Własne bazy/fixture, bez zmiany
dokumentów użytkownika i wdrożenia dev/prod. Wybór okresu/compact oraz
dalsza retencja zdarzeń i procedury danych usług/kopii pozostają w całym A43.
A05, całe A41 i zmiany samych zdjęć nadal odłożone.

**A43.10 — mechanizm wykonany i zweryfikowany lokalnie 27.10.2026.** Notification
może kompaktować payload/error starych REJECTED bez related_id w event_inbox,
zachowując event_id, hash, status i czas. V11, indeks częściowy i materializowane
partie z SKIP LOCKED chronią cykl życia i budżet. Rzeczywiste store/inbox
potwierdziły deduplikację po czyszczeniu i brak przywrócenia diagnostyki;
przyjęty receipt oraz oczekujące powiadomienie pozostały niezmienione.
Domyślnie observe/30 dni; aktywacja i okres wymagają osobnej decyzji.
**133 jednostkowe Java + 8 PostgreSQL = 141 — PASS**, w tym 18 nowych;
12 Kubernetes i spójność konfiguracji — PASS. Instrukcja:
[A43.10 — diagnostyka odrzuconych zdarzeń](A43_RETENCJA_ODRZUCONYCH_ZDARZEN_NOTIFICATION.md).
Raport: `tmp/audit-a43-10/report.json`. Własna baza/fixture, bez wysyłki
wiadomości i zmiany danych użytkownika. Bez wdrożenia dev/prod. Całe A43
nadal otwarte dla polityk/aktywacji, dalszej retencji zdarzeń/powiadomień
i procedur eksportu/usuwania/anonimizacji usług/kopii. A05, całe A41 oraz
zmiany samych zdjęć pozostają odłożone.

**A43.9 — mechanizm wykonany i zweryfikowany lokalnie 27.10.2026.** Payment
otrzymał kompaktowanie kopii recipient/delivery_address w starych PUBLISHED
bez dzierżaw, z zachowaniem event_key, eventId i metadanych. V17 pilnuje
cyklu życia; materializowane partie z SKIP LOCKED zachowują budżet i trwałe
znaczniki deduplikacji. Domyślnie observe/30 dni; okres i compact wymagają
osobnej decyzji. E-mail w event_key pozostaje, więc pełna anonimizacja jest
nadal otwarta. **206 jednostkowych Java + 7 PostgreSQL = 213 — PASS**, w tym
19 nowych; 12 Kubernetes i konfiguracja — PASS. Instrukcja:
[A43.9 — retencja danych outboxu płatności](A43_RETENCJA_DANYCH_OUTBOXU_PLATNOSCI.md).
Raport: `tmp/audit-a43-9/report.json`. Testy na własnej bazie, bez dostawcy
płatności i zmiany prawdziwych danych. Bez wdrożenia dev/prod. Całe A43
pozostaje otwarte dla polityk/aktywacji, dalszej retencji zdarzeń i procedur
danych usług/kopii. A05, całe A41 i zmiany samych zdjęć nadal odłożone.

**A43.8 wykonane i zweryfikowane lokalnie 27.10.2026.** Nowe zadania CC na
obsługiwanym Linux/WSL mają limit zapisu, domyślnie 1 MiB. Odbiornik zachowuje
nagłówek/końcówkę, działa niezależnie od CC i nadal przechwytuje potomka po
rzeczywistym reload/exec. Osiem wspólnych slotów ogranicza liczbę procesów;
pidfd i odłączenie chronią życie procesów po exec, a retencja czeka na EOF.
API pokazuje efektywny tryb, pominięcia i błędy. **219 Python — PASS, bez
pominiętych**, w tym 21 nowych; 15 modułów/54 fasad — PASS. Instrukcja:
[A43.8 — limit zapisu logów CC](A43_LIMIT_ZAPISU_LOGOW_CC.md).
Raport: `tmp/audit-a43-8/report.json`. Odbiór na fixture w WSL, bez działania
na prawdziwych logach i bez restartu CC/wdrożenia dev/prod. Całe A43 pozostaje
otwarte dla polityk/aktywacji, dalszej retencji zdarzeń i procedur danych
usług/kopii. A05, całe A41 i zmiany zdjęć nadal odłożone.

**A43.7 wykonane i zweryfikowane lokalnie 27.10.2026.** Job.public CC nie
wczytuje całych logów. Lista pomija odczyt dla queued/running/success, failed
ma cache diagnozy bez tekstu, a szczegóły pobierają ograniczony początek/koniec.
Domyślnie do 120000 bajtów; API i widoczny opis oznaczają próbkę. Fingerprint
pliku, limit i wersja reguł unieważniają cache; zmiana w trakcie odczytu nie
jest buforowana. Bezpieczne otwieranie chroni przed linkami i podmianą logu.
**198 Python — PASS, bez pominiętych**, w tym 19 nowych. Test sparse 1 GiB
potwierdził pobranie najwyżej 4096 bajtów. Bramka 13 modułów/54 fasad — PASS.
Instrukcja: [A43.7 — ograniczony odczyt logów](A43_ODCZYT_LOGOW_I_CACHE_DIAGNOZ_CC.md).
Raport: `tmp/audit-a43-7/report.json`. Odbiór na fixture, bez realnych logów
i restartu CC/wdrożenia dev/prod. Limit zapisu pojedynczego logu pozostaje
osobnym etapem, z ochroną stdout potomków po zakończeniu zadania/reload CC.
Pozostałe polityki/aktywacje, retencja zdarzeń i procedury danych nadal otwarte.
A05, całe A41 i zmiany zdjęć pozostają odłożone.

**A43.6 — mechanizm wykonany i zweryfikowany lokalnie 27.10.2026.** CC
otrzymało retencję własnych zamkniętych logów zadań poza przechowywaną historią.
Znaczniki i blokady chronią zadania między procesami oraz potomków dziedziczących
stdout. O_NOFOLLOW, uchwyty katalogów, tożsamość plików i ścisłe granice czasu
chronią przed usuwaniem obcych lub podmienionych danych. Legacy i niezamknięte
logi pozostają zachowane. Skan ma budżety i kontynuację; chroniony GET stanu
nie uruchamia skanu i nie czyta treści. Domyślnie observe/14 dni; docelowy okres
i purge wymagają decyzji użytkownika. **179 Python — PASS, bez pominiętych**,
w tym 27 nowych i 12 istniejących Kubernetes. Bramka 12 modułów/54 fasad — PASS.
Instrukcja: [A43.6 — retencja logów zadań CC](A43_RETENCJA_LOGOW_ZADAN_CC.md).
Raport: `tmp/audit-a43-6/report.json`. Odbiór wyłącznie na fixture, bez działania
na prawdziwych logach i bez restartu CC/wdrożenia dev/prod. A43 nadal otwarte
dla polityk/aktywacji, limitów rozmiaru/odczytu logów, dalszej retencji zdarzeń
i procedur danych usług/kopii. A05, całe A41 i zmiany zdjęć nadal odłożone.

**A43.5 — mechanizm wykonany i zweryfikowany lokalnie 27.10.2026.** Billing
może kompaktować JSON starych opublikowanych zdarzeń faktur, zachowując ID,
unikalny event_id, status i metadane. Chroni PENDING/FAILED, świeże publikacje
i wszystkie pozostałości dzierżaw. V10 dodaje znacznik, constraint cyklu życia
i indeks częściowy. StateStore i unikalność nadal blokują ponowne publikacje;
stary ticket nie przywraca payloadu. MATERIALIZED ustala kandydatów raz i
zapewnia limit partii także przy współbieżności. Domyślnie observe z progiem
30 dni; docelowe okno i compact wymagają decyzji użytkownika. **157 jednostkowych
Java + siedem PostgreSQL = 164 — PASS**, w tym 17 nowych; 12 Kubernetes,
konfiguracja i kontrola 15 JAR-ów — PASS. Instrukcja:
[A43.5 — retencja treści outboxu faktur](A43_RETENCJA_TRESCI_OUTBOXU_FAKTUR.md).
Raport: `tmp/audit-a43-5/report.json`. Odbiór izolowany, bez modyfikacji
prawdziwych faktur/brokera i wdrożenia dev/prod. Całe A43 nadal otwarte dla
polityk/aktywacji, dalszej retencji zdarzeń/logów oraz procedur danych usług
i kopii. A05, całe A41 i zmiany samych zdjęć nadal odłożone.

**A43.4 — mechanizm wykonany i zweryfikowany lokalnie 27.10.2026.** Audyt
bezpieczeństwa Customer ma osobne tryby `observe`/`purge`/`off`, okres i budżet.
Domyślna obserwacja nie usuwa danych; 90 dni to próg początkowy obserwacji.
Docelowy okres i aktywację czyszczenia ustala użytkownik, bez automatycznego
włączania przy kolejnym etapie. Purge działa w krótkich partiach z SKIP LOCKED;
metryki pokazują zaległości, błędy i zatwierdzone usunięcia, także po częściowej
awarii. V15 zastępuje indeks czasu indeksem czasu/ID, bez czyszczenia rekordów.
Sześć parametrów w aplikacji/Compose/Kubernetes. **167 jednostkowych Java +
24 PostgreSQL = 191 — PASS**, w tym 16 nowych; 12 Kubernetes i kontrola
15 JAR-ów — PASS. Instrukcja: [A43.4 — retencja audytu bezpieczeństwa](A43_RETENCJA_AUDYTU_BEZPIECZENSTWA.md).
Raport: `tmp/audit-a43-4/report.json`. Odbiór izolowany, bez zmian prawdziwych
audytów i wdrożenia dev/prod. Całe A43 nadal otwarte dla docelowej polityki
audytu, dalszej retencji telemetrii/zdarzeń i procedur eksportu/usuwania/
anonimizacji obejmujących usługi i kopie. A05, całe A41 i zmiany zdjęć odłożone.

**A43.3 wykonane i zweryfikowane lokalnie 27.10.2026.** Worker Customer czyści
wygasłe hashe weryfikacji oraz stare znaczniki aktywnych kont przez ograniczone
UPDATE, bez usuwania klientów i zmiany danych profilu. Oczekujące konto
zachowuje możliwość ponownej wysyłki oraz oba limity wysyłania. Potwierdzenie
linku blokuje klienta i odrzuca token zastąpiony przez równoległą wysyłkę;
`@DynamicUpdate` chroni przed przywróceniem wyczyszczonych pól przez starszy
zapis profilu. V14 dodaje dwa indeksy. Wspólny budżet obejmuje teraz sześć
kategorii; nowe liczniki `cleared` odróżniają pola od usuniętych rekordów.
157 jednostkowych Java + 18 PostgreSQL = **175 — PASS**, w tym 14 nowych
przypadków. Kontrola parenta, korelacji/odporności i 15 JAR-ów — PASS.
Instrukcja: [A43.3 — retencja weryfikacji kont](A43_RETENCJA_WERYFIKACJI_KONT.md).
Raport: `tmp/audit-a43-3/report.json`. Odbiór izolowany, bez wysyłki e-maili,
zmiany prawdziwych baz ani wdrożenia dev/prod. Stan po A43.3 pozostawiał otwarte
dalszą retencję audytu/telemetrii/zdarzeń oraz eksport/usuwanie/anonimizację
obejmujące usługi i kopie. A05, całe A41 i zmiany zdjęć nadal odłożone.

**A43.2 wykonane i zweryfikowane lokalnie 27.10.2026.** Customer Service czyści
zamknięte rodziny refresh, stare tokeny resetu, użyte kody odzyskiwania i wygasłe
nieaktywne konfiguracje TOTP po domyślnych siedmiu dniach. Małe transakcje
blokują najpierw właściciela i ponownie sprawdzają kwalifikację; SKIP LOCKED
pomija zajęte dane. Historia żywych rodzin nadal wykrywa replay; aktywne TOTP
i niewykorzystane recovery pozostają chronione. V13 dodaje pięć indeksów,
konfiguracja obejmuje aplikację/Compose/Kubernetes, a metryki pokazują błędy
i zaległość bez identyfikatorów klientów. 152 jednostkowe Java + dziewięć
PostgreSQL = **161 — PASS**, w tym 17 nowych; 12 testów Kubernetes i kontrola
15 JAR-ów — PASS. Instrukcja: [A43.2 — retencja tokenów i MFA](A43_RETENCJA_TOKENOW_I_MFA.md).
Raport: `tmp/audit-a43-2/report.json`. Bez czyszczenia prawdziwych baz i wdrożenia
dev/prod. Stan po A43.2 pozostawiał otwarte znaczniki weryfikacji kont i dalszą retencję audytu,
telemetrii i zdarzeń oraz eksport/usuwanie/anonimizacja we wszystkich usługach
i kopiach. A05, całe A41 i zmiany zdjęć pozostają odłożone.

**A43.1 wykonane i zweryfikowane lokalnie 27.10.2026.** Domyślny zapis każdego
wywołania rekomendacji zastąpiono metrykami zbiorczymi bez identyfikatorów.
Jawny log diagnostyczny ma konfigurowalną retencję (domyślnie siedem dni),
małe transakcje i SKIP LOCKED. Worker pokazuje zaległość/błędy i nie usuwa
świeżych rekordów; opcjonalny zapis nie blokuje poprawnych rekomendacji.
Konfiguracja obejmuje aplikację, Compose i Kubernetes. 125 jednostkowych Java,
cztery PostgreSQL oraz 12 testów szablonów Kubernetes — PASS. Sprawdzono też
parent 17 modułów i strukturę korelacji/odporności 15 JAR-ów. Instrukcja:
[A43.1 — retencja logu rekomendacji](A43_RETENCJA_LOGU_REKOMENDACJI.md).
Raport: `tmp/audit-a43-1/report.json`. Odbiór izolowany, bez zmian prawdziwych
baz i bez wdrożenia dev/prod. Stan po A43.1 pozostawiał otwarte tokeny/MFA,
pozostałej telemetrii/audytu/zdarzeń i procedur danych obejmujących usługi
oraz kopie. A05, całe A41 i zmiany zdjęć nadal odłożone.

Poniżej pierwotne ustalenie audytu; aktualny zakres pozostały opisano przy A43.30.

**Obszar:** Dane i retencja. **Status:** Luka polityki i mechanizmów w repozytorium.

**Dowody:** [V1__create_recommendation_requests.sql:1](<C:/Dysk D/stuff/Mini Order Platform/recommendation-service/src/main/resources/db/migration/V1__create_recommendation_requests.sql:1>), [RefreshTokenRepository.java:8](<C:/Dysk D/stuff/Mini Order Platform/customer-service/src/main/java/org/demo/customerservice/auth/RefreshTokenRepository.java:8>), [PaymentEventPublisher.java:45](<C:/Dysk D/stuff/Mini Order Platform/payment-service/src/main/java/org/demo/paymentservice/outbox/PaymentEventPublisher.java:45>).

**Problem i skutek:** Każde zapytanie rekomendacji zapisuje rekord; zużyte/wygasłe tokeny, opublikowany outbox, powiadomienia i streamy nie mają widocznej, spójnej polityki czyszczenia. Z czasem rosną indeksy, kopie i koszty odczytu. Dla danych klientów brakuje jawnej technicznej procedury eksportu/usuwania/anonimizacji obejmującej wszystkie usługi.

**Zalecenie:** Ustalić odrębne retencje dla danych biznesowych, audytu, sekretów i telemetrii. Agregować/samplować log rekomendacji, usuwać zbędne tokeny, archiwizować outbox i bezpiecznie przycinać streamy. Wymagania prawne ustalić osobno przed określeniem konkretnych okresów.

**Warunek odbioru:** Przyrost i retencja są monitorowane; czyszczenie nie usuwa niewysłanych zdarzeń ani wymaganych dokumentów; procedury obejmują wszystkie kopie danych.


<a id="a44"></a>
### A44 · P3 · Warto skrócić i ustabilizować budowanie oraz wcześniej wykrywać problemy obrazów

**Obszar:** CI i łańcuch dostaw. **Status na Audit_0.1:** ✅ **Wykonane w repozytorium.**  
A44.1–A44.3 wdrożono lokalnie, a skrypt `scripts/qa/ci-images-check.py` weryfikuje poprawność konfiguracji. Rzeczywiste uruchomienie pełnego łańcucha na GitHub Actions (A44.4) to faza CI po wypchnięciu gałęzi.

**Wdrożenie w Pakietach 25 i 164:**
- **Optymalizacja Dockerfile i cache:** Wieloetapowe budowanie z wydzielonym cache warstw Mavena/Go/Node, redukcja rozmiarów obrazów.
- **Bezpieczeństwo kontenerów i przypięcie digestów:** Przypięcie SHA-256 dla bazowych obrazów, uruchamianie jako użytkownik non-root (`appuser`), montowanie `read-only` dla systemu plików kontenera.
- **Łańcuch dostaw (SBOM i Cosign):** Generowanie SBOM (Software Bill of Materials) w formacie SPDX/CycloneDX oraz podpisywanie obrazów i atestacji za pomocą narzędzia Cosign.
- **Bramka walidacji CI:** Wdrożono skrypt `scripts/qa/ci-images-check.py` sprawdzający konfigurację `.github/workflows/ci.yml` i poprawność definicji obrazów (PASS). [Dokumentacja A44](A44_CI_I_LANCUCH_DOSTAW.md).


<a id="a45"></a>
### A45 · P3 · Sklep miesza interfejs klienta z instrukcjami technicznymi, a dokumentacja miejscami odstaje od kodu

**Obszar:** UX, język i dokumentacja. **Status na Audit_0.1:** ✅ **Wykonane i zweryfikowane.**

**Wdrożenie w Pakietach 26, 163 i 164:**
- **Separacja treści klienta i demo:** Usunięto odniesienia do backendowych nazw serwisów i narzędzi deweloperskich (Mailpit) z widoku klienta sklepu.
- **Pełne umiędzynarodowienie (i18n):** Ujednolicono słowniki PL i EN w `frontend/i18n.js`; dynamiczne komunikaty koszyka i checkoutu respektują wybrany język bez mieszania fraz.
- **Synchronizacja dokumentacji:** Dokumentacja repozytorium została w pełni zaktualizowana i uzgodniona z rzeczywistym kodem:
  - Aktualny przewodnik przekazania: [PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md).
  - Pełny rejestr chronologiczny pakietów: [AUDIT_0.1_POSTEP.md](AUDIT_0.1_POSTEP.md).
  - Z dokumentacji markdown wyeliminowano niedozwolone schematy linków `file:///` na rzecz poprawnych ścieżek repozytorium.


## Pokrycie obszarów aplikacji

| Obszar | Najważniejsze ustalenia |
| --- | --- |
| API Gateway | A01–A03, A19, A24, A30–A31: granice dostępu, CORS, limity, buforowanie i timeouty |
| Customer Service i sesje | A22–A23, A43: klucze MFA, atomowe zużycie tokenów/kodów, TTL i retencja |
| Product Service | A18, A27, A29: wiarygodność cen, koszt pełnego katalogu, ograniczenie odczytów |
| Order Service | A04, A06, A11, A15, A17: wycena, saga, przejścia i idempotencja |
| Inventory Service | A07, A10, A21: blokada rezerwacji, pobranie, atomowe przyjęcie dostawy |
| Payment Service | A05, A08–A11, A14: symulacja, repliki, trwałe skutki i kompensacje |
| Delivery Service | A04, A09–A10, A17: autorytatywna wycena i pełny proces realizacji |
| Promotion Service | A14, A18, A27: rezerwacja kuponu, historia cen i wydajność |
| Wishlist Service | A01, A19, A41: właściciel zasobu, DELETE/CORS i koszt osobnej usługi |
| Review Service | A01–A02, A17, A29, A39: dostęp, moderacja, stany zakupu, agregaty i publiczny DTO |
| Recommendation Service | A01, A17, A27, A41, A43: historia zakupów, koszt katalogu i sens technicznego logu w osobnej bazie |
| Chat Service | A01–A02, A29, A38: prywatność rozmowy, operator, model historii i powiadomienia |
| Returns Service | A01–A02, A11, A16: właściciel, zwroty, stany i rozliczenie skutków |
| Billing Service | A09, A12–A13, A30, A32: wystawianie, korekty, eksport i zdarzenia |
| Notification Service / Redis | A09, A26, A31–A32, A38, A43: trwałość, deduplikacja, retry i retencja |
| Frontend sklepu | A03–A04, A18–A19, A28, A37, A40, A42, A45: bezpieczeństwo, dostępność, wydajność i treści |
| Control Center i skrypty | A20–A21, A25–A26, A35–A36, A42: poprawność danych, bezpieczeństwo operacji, moduły i odtwarzanie |
| Dane, migracje i konkurencja | A06–A08, A13–A15, A21, A23, A29, A32, A43 |
| DevOps, produkcja, CI i QA | A33–A36, A41–A44 |

## Co zoptymalizować i co redukować

| Zmiana | Co redukuje | Nakład orientacyjny | Zależność / miernik |
| --- | --- | --- | --- |
| Poprawić DELETE w CORS, zapytanie klientów i obsługę null ceny | Widoczne błędy bez dużego refaktoru | Mały | Realny preflight i regresja danych (A18–A20) |
| Batch dostępności + ładowanie widocznej części katalogu | N żądań, operacje gateway/Redis, oczekiwanie przed renderem | Średni | Liczba żądań, czas do pokazania katalogu, p95 (A28) |
| Zastąpić indexOf i eager fallback; odczyt/batch cen | Pełne skany i liczbę wywołań Promotion Service | Mały–średni | Profil CPU, zapytania DB i koszt checkoutu (A27) |
| Paginacja i agregaty SQL | Rozmiar odpowiedzi, RAM i transfer | Średni | Plany SQL, liczba odczytanych wierszy (A29) |
| Ograniczyć cache PDF i naprawić eksport przez gateway | Zużycie heap i ryzyko OOM | Średni | Peak RAM przy dużym eksporcie (A30) |
| ODŁOŻONE: warianty zdjęć i kontrolowana kompresja/cache | Transfer i koszt renderowania | Mały–średni | Wyłącznie po osobnym poleceniu użytkownika (A40) |
| Jeden lokalny serwer PostgreSQL z odrębnymi bazami/rolami | Procesy, RAM i czas startu dev/test | Średni | Pomiary przed/po; bez łączenia tabel właścicieli (A41) |
| Profile funkcjonalne uruchamiające tylko potrzebny zestaw | Zasoby stacji i czas iteracji | Średni | Minimalny zestaw checkout/katalog/full, czas startu (A41) |
| Wspólny parent/BOM i małe moduły infrastrukturalne | Kopiowane wersje, filtry, timeouty | Średni | Jedno źródło ustawień; niezależna kompilacja usług (A42) |
| Moduły ES oraz wspólne API/session/cart/dialog | Powtarzane błędy i koszt zmian stron | Średni | Testy współdzielonej logiki i regresja całego UI (A42) |
| Konsolidacja małych usług albo modularny monolit | Długofalowy koszt operacji i rozproszonej spójności | Duży; decyzja, nie szybka poprawka | ADR, skala ruchu, liczba zespołów, koszt utrzymania (A41) |
| Budować w PR tylko zmienione obrazy; pełny zestaw przy wydaniu | Czas CI i późne wykrywanie błędów | Średni | Czas/zużycie CI oraz zachowanie skanów (A44) |
| Usunąć bezwarunkowy techniczny zapis każdego odczytu rekomendacji | Zapisy DB i przyrost backupów | Mały–średni | Zastąpienie metryką/agregacją, jawna retencja (A43) |

„Mały/średni/duży” to ocena względna zakresu, a nie wycena w dniach. Nie ma pomiarów pozwalających uczciwie obiecać procentowe oszczędności. Nie ma też uzasadnienia, aby w ramach optymalizacji automatycznie przepisywać frontend na framework, dodawać Elasticsearch, Kubernetes, WebSocket czy wymieniać bazę danych.

## Zalecana kolejność prac

### Etap 1 — zabezpieczyć granice i zamknąć proste błędy

1. A01–A03: tożsamość, własność zasobów, operator, zaufany backend formularzy; włączyć testy A/B/anonim dla wszystkich usług.
2. A04–A05, A12: serwerowe ceny i reguły zakupu; jawna granica symulacji płatności i źródła dokumentu.
3. A22–A26: stabilny klucz MFA, atomowe tokeny, poprawny limiter i ochrona lokalnego panelu/sekretów.
4. Małe regresje A18–A20 i A39: null ceny, DELETE, lista klientów, publiczny DTO.

**Odbiór etapu:** macierz ról jest egzekwowana przez backend, a dane i ceny nie zależą od zaufania do formularza. Każdy naprawiony P1 ma regresję.

### Etap 2 — domknąć proces zakupu i odtwarzanie po awarii

1. A06–A11, A14–A16, A21, A32: atomowe przejścia, rezerwacje, pobranie, kompensacje, trwałe kroki po płatności, zwroty i claim zadań.
2. A13 i A17: poprawny model korekt i stanów opłaconego zakupu.
3. Testy dwóch replik i kontrolowane restarty po commitach, HTTP i ACK. Dodać reconciler wykrywający „płatność bez realizacji/dokumentu”.

**Odbiór etapu:** brak utraconych skutków, podwójnych ruchów magazynowych i zamówień bez wznawialnego procesu; błędy dają się uzgodnić automatycznie lub operacyjnie.

### Etap 3 — poprawić koszt i wygodę na podstawie pomiarów

1. A27–A31: batch, paginacja, timeouty, limit cache PDF i strumieniowanie eksportu. W A40 aktywny pozostaje zakres niezwiązany ze zdjęciami; zmiany samych zdjęć odłożono do osobnego polecenia użytkownika.
2. A37–A38, A45: dostępne modale, spójne PL/EN, poprawna historia obsługi klienta i czytelne komunikaty.
3. A35–A36, A43: alerty biznesowe, sprawdzone odtwarzanie całego środowiska i retencja.

**Odbiór etapu:** bazowy i docelowy raport p95/p99/transfer/RAM, pełna ścieżka klawiaturowa oraz skuteczny test restore.

### Etap 4 — uprościć utrzymanie i zdecydować o produkcji

1. A41–A42 i A44: porównać warianty architektury, centralizację konfiguracji i koszt CI. Podejmować decyzję na podstawie skali, bez automatycznego łączenia wszystkich usług.
2. A34: uzupełnić szablony, provider i zabezpieczenia; wykonać pełne E2E/DAST/skan obrazów w środowisku przedprodukcyjnym.
3. Dopiero po zamknięciu P1 i weryfikacji odtwarzania zmienić deklarację gotowości produkcyjnej.

## Minimalny zestaw testów odbiorczych

- Macierz uprawnień: anonim / klient A / klient B / operator / usługa, dla odczytu, zmiany i eksportu każdej kategorii danych.
- Ten sam Idempotency-Key: zgodna treść, inna treść, nadmierna długość, dwie repliki, utrata odpowiedzi po zapisie.
- Dwa zamówienia i ten sam produkt: confirm/release/cleanup/uzupełnienie zapasu wykonywane równolegle.
- Awaria między zapisem zamówienia i sagi, po PAID, przed potwierdzeniem zapasu, przy publikacji i po odebraniu zdarzenia.
- Billing/Delivery/Promotion wyłączone podczas płatności: automatyczne uzupełnienie po przywróceniu usług.
- Pobranie po upływie TTL, anulowanie po płatności, częściowy zwrot, dwie korekty tej samej faktury i powtórzone zgłoszenia.
- Rotacja JWT przy aktywnym TOTP; równoległy refresh i zużycie recovery code; enrollment po TTL.
- Rzeczywisty CORS wishlist DELETE i kontrakty odpowiedzi, nie tylko mock routingu.
- PL/EN, klawiatura, fokus i kontrast dla otwartych formularzy, MFA i całego checkoutu.
- Reprezentatywny katalog i historia: liczba żądań, p95/p99, plany SQL, eksport dużego zakresu faktur, maksymalny rozmiar cache.
- Restore kompletu baz i brokera, z uzgodnieniem znanych zamówień i powtórzeniem zdarzeń.

## Elementy warte zachowania

- Oddzielna własność danych i migracje Flyway zamiast automatycznego modyfikowania schematu przez Hibernate.
- Idempotency keys, ograniczenia UNIQUE i blokady pozycji magazynowych — wymagają uzupełnienia, lecz są dobrym punktem wyjścia.
- Trwała saga, payment outbox i retry/DLQ powiadomień — rozwinąć je do spójnego procesu zamiast zastępować kolejnymi synchronicznymi wywołaniami.
- Token odświeżania w HttpOnly cookie, hashowanie tokenów w bazie, MFA i audyt logowania.
- Ograniczenie lokalnych portów/panelu do loopback, szyfrowanie sekretów SOPS/age, szyfrowane backupy i istniejący test odtwarzania.
- Realne E2E w CI, Trivy przed publikacją obrazu oraz podpisywanie obrazów. Audyt wskazuje luki w zakresie i asercjach, nie brak całej infrastruktury jakościowej.

## Źródła referencyjne

Poniższe źródła wspierają interpretację mechanizmów i zaleceń; ustalenia dotyczące aplikacji wynikają z podlinkowanego kodu.

- Weryfikacja właściciela konkretnego zasobu: [OWASP API1: Broken Object Level Authorization](https://api-security.owasp.org/editions/2023/en/0xa1-broken-object-level-authorization/) — odniesienie dla A01 i A39.
- Granice transakcji i wywołania przez proxy: [Spring Framework — Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html) — odniesienie dla A06, A08 i A31. Wywołania wewnątrz tego samego obiektu nie tworzą samoczynnie nowej granicy transakcji.
- Pending entries należą do konkretnego konsumenta: [Redis XREADGROUP](https://redis.io/docs/latest/commands/xreadgroup/); przejmowanie porzuconych wpisów: [Redis XAUTOCLAIM](https://redis.io/docs/latest/commands/xautoclaim/) — odniesienie dla A32. Propozycja XAUTOCLAIM jest dostępna również dla używanego Redis 7; nie zakłada nowszych opcji XREADGROUP.
- Zachowanie fokusu i klawiatury w modalu: [W3C WAI-ARIA — Dialog Modal Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/) — odniesienie dla A37.

**A43.36 — odebrane lokalnie 29.10.2026.** Zachowanie dokumentów finansowych, redakcja uzgodnionych kopii, trwałe ID v2 i zgodny backlog v1, siedem potwierdzeń usług oraz ochrona SQL/Redis odblokowują A43.35. Aktywne i niejasne sprawy czekają; brak operacji Stripe. Aktywacja wyłączona do A43.40, globalne complete=false. [Zakres i dowody](A43_FINANSE_I_ZDARZENIA.md); raport `tmp/audit-a43-36/report.json`.

**A43.37 — odebrane lokalnie 29.10.2026.** Niezależny zaszyfrowany i podpisany
rejestr przed czyszczeniem; po restore ponowienie 14 barier i zakresu core,
unieważnienie sesji/wyzwań oraz kwarantanna przy zachowaniu biznesowego Redis.
Retencja kopii tylko observe; aktywacja i okresy w A43.40. Konfiguracja
własnego rejestru/kluczy i niezależnej pieczy aktualnego wskaźnika pozostaje
ręczna. Nie dopuszczono odtworzonego środowiska do ruchu ani nie zamknięto
pełnego A36/A43. [Zakres i ograniczenia](A43_BACKUPY_I_REJESTR_USUNIEC.md);
raport `tmp/audit-a43-37/report.json`. Pozostają 4 etapy A43.38–A43.41.

**A43.38 — odebrane lokalnie 30.10.2026.** Minimalna nowa historia wysyłek,
zachowanie legacy do oceny, koniec sesji i usunięcie danych z widoku CC/sklepu,
ochrona przed późnymi odpowiedziami i użyciem checkoutu innego konta, cykliczny
sweep PDF oraz odbiór porcjowego wygasania eksportu. Macierz obejmuje 11 zasobów;
dopisano istniejące zamiary operatora do rejestru (19 kategorii kopii).
Backendy w rzeczywistym środowisku wymagają dowodu; retencja/aktywacja nadal
A43.40, bez kasowania rzeczywistych logów/kluczy i bez restartu CC.
[Procedury i ograniczenia](A43_KOPIE_WEWNETRZNE_I_STAN_URZADZEN.md), raport
`tmp/audit-a43-38/report.json`. Pozostają 3 etapy A43.39–A43.41.


**A43.39 — odebrane lokalnie 30.10.2026.** Każda zewnętrzna tura AI CC
wymaga podglądu i jednorazowej zgody na konkretną treść/historię/konfigurację.
Macierz rozlicza dostawców, pobrania, rejestr zgód i ręczne dowody czatu.
Po usunięciu rozmowy token traci dostęp, a status przekazuje zweryfikowana
obsługa bez nowego sekretu i kopii hasha tokenu. 330 unikalnych Python w łącznym
zakresie, 232 JS, 12 Java (7 PostgreSQL), 50 Chromium i po 2 Firefox/WebKit — PASS.
Wymagane dowody dostawców i odłożone A05 nadal blokują pełne A43; brak kontaktu
z dostawcami, aktywacji czyszczenia i wdrożenia dev/prod.
[Procedura i ograniczenia](A43_DOSTAWCY_PLIKI_I_DOWODY_CZATU.md), raport
`tmp/audit-a43-39/report.json`. Pozostają 2 etapy A43.40–A43.41.


**A43.40 — w toku od 30.10.2026, niezamknięty.** Wdrożono wersjonowaną
macierz 26 polityk dla 85 tabel/749 kolumn, 6 przestrzeni Redis i 20 kopii,
walidator kompletności/konfiguracji oraz bramkę CI. Zatwierdzony profil
techniczny bez aktywacji, znaczniki bez automatycznego TTL do udokumentowanego
wycofania źródeł replay/restore. Z decyzji użytkownika okresy finansowo-handlowe,
oba streamy i docelowy okres SQL bezpieczeństwa pozostają nierozstrzygnięte
w tej odebranej rewizji `.1`; późniejsze odpowiedzi D01–D18 zapisano w
[rejestrze decyzji](A43_POZOSTALE_PRACE_I_DECYZJE.md) do przeniesienia do
następnej rewizji podczas wdrażania A43.40;
obecne działanie usług bez zmian. Pełna gotowość i aktywacja są blokowane,
a reguły/dowody kopii, środowiska i dostawców nadal wymagane. Nie odznaczono
A43.40 i nie zamknięto A43. Pozostają 2 etapy: A43.40–A43.41.
[Macierz i ograniczenia](A43_POLITYKA_RETENCJI_I_AKTYWACJA.md), raport częściowy
`tmp/audit-a43-40/report.json`.

---

## Dane i decyzje użytkownika potrzebne do zakończenia ośmiu otwartych obszarów

**Dopisano 01.11.2026; stan po pakiecie 133, branch `Audit_0.1`.**
Punktem odniesienia jest [aktualne przekazanie audytu](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md),
zwłaszcza jego sekcje 3, 5 i 6. Ten dodatek jest listą do uzupełnienia,
nie zapisem nowych odpowiedzi, zgodą na wdrożenie środowiskowe ani aktywacją.
Nie zmienia wcześniejszych decyzji i nie zamyka żadnego punktu audytu.

### Jak korzystać z listy

- **Do ustalenia:** informacja lub decyzja potrzebna przed odpowiednim
  wykonaniem/odbiorem, lecz niekoniecznie przed rozpoczęciem prac nad kodem.
- **Warunkowe:** potrzebne dopiero dla wskazanego środowiska, publikacji
  lub zmiany zakresu. Nie blokuje samodzielnych prac lokalnych.
- **Bez nowej decyzji:** agent realizuje opisany zakres i zbiera dowody.
  Zależność od innego punktu nie oznacza nowego pytania do użytkownika.
- Agent najpierw sprawdza istniejące dokumenty, publiczną konfigurację
  i dostępne metadane środowiska. Nie wymaga przepisywania informacji,
  które da się wiarygodnie ustalić z tych źródeł. Użytkownik podaje przede
  wszystkim intencję biznesową, nieznane miejsce docelowe i dostępne zasoby.
- Odpowiedź może wskazywać zatwierdzony dokument zamiast powtarzać jego treść.
  Można odpowiadać w czacie po ID, np. `U36-01: dev — RPO ...; RTO ...`.
  `Do ustalenia` pozostawia blokadę odpowiedniego zakresu; nie oznacza zgody
  na wartość domyślną. `Brak` należy odróżnić od `nie wiem`.
- **Sekrety uzupełnia się w przeznaczonym chronionym magazynie.** W tym
  dokumencie i czacie potrzebne są nazwy/ścieżki referencji, bez haseł,
  tokenów API, prywatnych kluczy, zawartości SOPS/kubeconfig i danych klientów.
- Zgodnie z poleceniem użytkownika prace zależne od brakującej odpowiedzi
  odkładać i wykonywać następne niezależne zadanie. Nie projektować mechanizmu
  technicznego przez zadawanie użytkownikowi pytań o SQL, epoki, UUID lub retry.

Poniższe pola rozwijają warunki odbioru z dokumentów obszarowych. Nie są
wszystkie odrębnymi, wcześniej zatwierdzonymi wymaganiami biznesowymi.
Tam, gdzie dokument pozostawia wybór, warianty służą doprecyzowaniu zakresu;
żaden wariant nie został wybrany przez dopisanie tej listy.

### A16 — skutki zwrotów i reklamacji

Źródło: [pozostały zakres pełnego A16](A16_ZWROTY_REKLAMACJE.md).
Potrzebna jest polityka domenowa, zanim agent wdroży faktyczne skutki sprawy.

| ID / status | Co użytkownik powinien podać lub wskazać |
|---|---|
| **U16-01 — do ustalenia** | **Kolejne sprawy częściowe:** czy kolejne zgłoszenie można otworzyć dopiero po uzgodnieniu poprzedniego, czy dopuszczalne są równoległe sprawy na rozłączne ilości. **Zmiana zakresu:** do którego statusu można korygować ilości i czy po zatwierdzeniu wymagana jest nowa sprawa. Określić, jak traktować ilości z odrzuconej/anulowanej sprawy. Agent zapewnia technicznie brak przekroczenia zakupionej ilości i podwójnego rozliczenia. |
| **U16-02 — do ustalenia** | **Rozliczenie pieniężne i dokumenty:** zatwierdzona reguła kwoty zwrotu dla części/całości, uwzględnienia rabatów, kosztu pierwotnej dostawy i ewentualnych kosztów zwrotu; wskazanie zasad dokumentu/korekty i roli zatwierdzającej. Jeżeli istnieje polityka rozliczeń, wystarczy jej nazwa/ścieżka i zakres zastosowania. Nie podawać rzeczywistych danych transakcji. Agent projektuje obliczenia, deduplikację i koordynację na podstawie tej reguły. |
| **U16-03 — do ustalenia** | **Zwracany towar:** kto potwierdza fizyczne przyjęcie i ocenę produktu oraz kiedy może on zwiększyć dostępny zapas. Wskazać regułę dla produktu pełnowartościowego, uszkodzonego i nieprzyjętego; np. dostępny zapas dopiero po akceptacji magazynu, pozostałe przypadki do odrębnej oceny. Samo zatwierdzenie zgłoszenia nie stanowi potwierdzenia przyjęcia. |
| **U16-04 — do ustalenia** | **Zakres skutków reklamacji:** które rozstrzygnięcia ma obsługiwać bieżący etap i jakie mają skutki pieniężne/magazynowe/dokumentowe. Jeżeli oczekiwana jest wymiana lub naprawa, określić to jako wymagany zakres do osobnego zaplanowania; nie zakładać, że funkcje te są już wdrożone. |
| **U16-05 — do ustalenia** | **Kupon po zwrocie:** zachowanie wykorzystania, przywrócenie możliwości użycia czy osobna reguła dla częściowego/pełnego zwrotu; wskazać zasady limitu użyć i daty ważności. Dotyczy rozliczenia zwrotu, nie zmienia uzgodnionej ochrony kuponów/deduplikacji podczas usuwania konta w A43. |

**Bez tych odpowiedzi:** można rozwijać izolowane adaptery, trwałe zadania,
idempotencję, odmowy i testy awarii, bez inicjowania rzeczywistych refundacji.
Pełny odbiór płatności zależy także od **odłożonego A05**. Wpisanie odpowiedzi
A16 nie wznawia Stripe i nie pozwala oznaczyć `COMPLETED` bez potwierdzeń skutków.

### A33 — końcowe scenariusze i regresje

Źródło: [bramki jakości A33](A33_BRAMKI_JAKOSCI.md).

| ID / status | Co użytkownik powinien podać lub wskazać |
|---|---|
| **U33-01 — bez nowej decyzji** | Dla istniejącej macierzy scenariuszy nie trzeba podawać nowych parametrów ani zatwierdzać projektu testów. Agent uzupełnia regresje, uruchamia je na fiksturach i zbiera dowody. |
| **U33-02 — warunkowe: rzeczywiste środowisko** | Gdy odbiór ma dotyczyć wdrożonego dev/test/przedprodukcji, wskazać środowisko z U34-01 i dostępne konta/zasoby testowe przez niepoufne oznaczenia. Testy obciążenia lub wywołujące zewnętrzne skutki wymagają określonego zakresu i okna. Nie udostępniać kont ani danych klientów. |

**Zależność, nie nowe pytanie:** kompletność A33 wymaga implementacji
otwartych scenariuszy biznesowych. Dokument A33 wskazuje w szczególności
rzeczywistego dostawcę płatności/refundacji A05. Odłożenie A05 oznacza
pozostawienie zależnego odbioru otwartego, a nie pominięcie testu jako PASS.

### A34 — docelowa infrastruktura i odbiór przedprodukcyjny

Źródło: [pozostały odbiór pełnego A34](A34_SZABLONY_WDROZENIOWE.md).
Poniższe informacje podać osobno dla środowisk, które rzeczywiście mają być
odbierane. Produkcji nie dodawać automatycznie do zakresu dev/test.

| ID / status | Co użytkownik powinien podać lub wskazać |
|---|---|
| **U34-01 — do ustalenia** | **Cel:** lista środowisk i platforma każdego z nich; dla Kubernetes — niepoufne oznaczenie klastra/kontekstu, namespace i lokalizacja docelowego overlayu. Wskazać, czy infrastruktura już istnieje i gdzie jest opisana. Agent sprawdza rzeczywistą wersję, CNI i dostępne możliwości; użytkownik nie musi projektować manifestów. |
| **U34-02 — do ustalenia dla odbioru sieci** | **Publiczne wejście:** domeny/hosty, docelowy ingress/proxy, sposób pozyskania certyfikatu TLS oraz znane zaufane proxy/zakresy adresów, jeśli nie da się ich ustalić z infrastruktury. Wystarczy wskazanie zatwierdzonej konfiguracji. Nie przyjmować dowolnego proxy jako zaufanego. |
| **U34-03 — do ustalenia** | **Dane i zasoby:** docelowe instancje/zasoby DB i Redis oraz magazyny trwałe, ich niepoufne nazwy/endpointy i tryb TLS. Wskazać dostępny budżet CPU/RAM/dysku; dla istniejącego Kubernetes agent odczyta StorageClass i możliwości klastra. Dobór rozmiarów pul, limitów i manifestów jest pracą agenta. Nie zakładać konsolidacji A41. |
| **U34-04 — do ustalenia** | **Dostarczenie sekretów:** zatwierdzony sposób przekazania ich aplikacjom w środowisku docelowym oraz referencje do zasobów/plików. Obecne SOPS nie oznacza automatycznie gotowych Secretów klastra. OpenBao/CSI uwzględniać tylko, jeśli zostaną wybrane. Użytkownik lub opiekun wprowadza brakujące wartości w chronionym magazynie; do audytu trafiają wyłącznie referencje i wynik weryfikacji. |
| **U34-05 — warunkowe: docelowa poczta** | Dla przedprodukcji wskazać docelowy SMTP, port/tryb TLS, nazwę nadawcy, kontrolowany adres odbiorczy i referencję poświadczeń. Dla dev/test zachować wcześniejszą deklarację lokalnego Mailpit i zweryfikować ją zamiast pytać o wybór ponownie. Odbiór zewnętrznej poczty jest odrębny od lokalnego przechwycenia wiadomości. |
| **U34-06 — warunkowe: operacje na środowisku** | Wskazać opiekuna i dopuszczalne okno restartów/migracji/testów oraz ograniczenia zmian. Konkretny zestaw zmian, skutki i sposób wycofania przygotowuje agent przed ewentualnym zatwierdzeniem wykonania. Ta lista nie udziela zgody na deployment. |

**Bez tych danych:** można utrzymywać i testować szablony na izolowanych
klastrach. Nie można potwierdzić pełnego A34 na podstawie poprawnego YAML
lub lokalnego server dry-run. Rzeczywisty odbiór płatności pozostaje zależny
od osobnego wznowienia A05.

### A35 — SLO, tracing, alarmy i watchdog

Źródło: [pozostały zakres A35](A35_ALERTY_BIZNESOWE.md).

| ID / status | Co użytkownik powinien podać lub wskazać |
|---|---|
| **U35-01 — do ustalenia** | **SLO:** dla kluczowych operacji wskazać nazwę procesu, docelowy odsetek poprawnych operacji/dostępność, okno oceny i dopuszczalne opóźnienie w sekundach lub minutach. Rozdzielić czas odpowiedzi HTTP od czasu wykonania skutku, np. utworzenia faktury/powiadomienia. Można wskazać istniejącą tabelę SLO zamiast wybierać liczby w czacie. Lokalny wynik testu nie jest zatwierdzonym celem. |
| **U35-02 — do ustalenia dla przechowywania trace** | **Traces:** wskazać istniejący/docelowy magazyn lub dostępną platformę, okres zachowania śladów oraz ograniczenie objętości/kosztu i dopuszczalny poziom próbkowania. Jeśli narzędzia nie wybrano, agent może najpierw przedstawić rozwiązanie do oceny; nie wymaga się od użytkownika zaprojektowania propagacji HTTP/Redis. Uzgodnione 30 dni metryk Prometheus nie rozstrzyga retencji nowego magazynu trace. |
| **U35-03 — do ustalenia dla odbioru operacyjnego** | **Czas reakcji lokalnej obsługi:** jak często właściciel/zastępca sprawdzają panele/raport i jaki maksymalny czas reakcji ma być sprawdzany. Kanał pozostaje zgodny z D18: lokalne panele/statusy/raporty, bez zewnętrznych wiadomości, obie role od pierwszego alarmu. Nie wybierać kanału ponownie. Rzeczywiste przypisanie kont opisuje wspólne U43-04. |
| **U35-04 — do ustalenia** | **Niezależny watchdog:** wskazać dostępny zasób/miejsce, z którego można zauważyć awarię samego stosu monitoringu. Nie może nim być wyłącznie proces Prometheusa, którego awaria ma być wykryta. Agent projektuje kontrolę i test awarii; forma informacji pozostaje zgodna z ustalonym kanałem lokalnym, dopóki użytkownik go nie zmieni. |

**Bez tych odpowiedzi:** można wdrażać propagację identyfikatorów, kolektory
spójności, readiness, scenariusze awarii i techniczne testy reguł. Nie ustawiać
wybranych przez agenta progów jako zatwierdzonych SLO ani deklarować odbioru
dyżurnego na podstawie samego alarmu w panelu.

### A36 — kopie, niezależność, globalny checkpoint i RPO/RTO

Źródło: [pozostały zakres pełnego A36](A36_ODTWARZANIE_PLATFORMY.md).

| ID / status | Co użytkownik powinien podać lub wskazać |
|---|---|
| **U36-01 — do ustalenia** | **RPO/RTO dla każdego odbieranego środowiska:** maksymalna akceptowana utrata danych w czasie (RPO) i maksymalny czas powrotu do uzgodnionego działania (RTO). Określić początek pomiaru RTO i czy obejmuje uzgodnienie procesów oraz ponowne dopuszczenie ruchu. W dokumencie propozycja kopii co 24 h/odtworzenia do 60 min nie jest potwierdzonym SLA; nie przyjmować jej automatycznie. |
| **U36-02 — do ustalenia** | **Niezależna kopia aplikacji:** rzeczywista lokalizacja na innym nośniku/hoście, z rozdzieleniem dev/test, pojemnością i zasadą dostępu. Wskazać, czy zasób zapewnia wymaganą ochronę przed utratą podstawowego dysku oraz czy dostępna jest niezmienność kopii. To kopie aplikacji; niezależny rejestr usunięć ma dodatkowe wymagania U43-02. Foldery `Kopia`/`Kopia3` na E nie zapewniają niezależności od utraty E. |
| **U36-03 — do ustalenia** | **Okno kopii i przerw:** godziny/strefa czasowa oraz dopuszczalna długość ograniczenia zapisów. Wskazać, czy można czasowo zatrzymać wszystkich writerów, czy wymagane jest zachowanie pracy bez takiej przerwy. Agent projektuje globalny checkpoint/PITR/uzgodnienie i wylicza harmonogram zgodny z RPO; użytkownik nie musi podawać crona lub algorytmu. |
| **U36-04 — do ustalenia dla pełnego odbioru** | **Zakres i dostępność odtworzenia:** udostępnione izolowane środowisko, zasoby/miejsce na próbę, wskazanie dodatkowych wolumenów/kopii oraz opiekuna, który potwierdzi odzyskanie konfiguracji i dostępność referencji kluczy. Nie podawać samych kluczy. Nieznane kopie i dodatkowe lokalizacje wymagają najpierw inwentaryzacji, nie arbitralnego TTL. |

**Już ustalone:** 14 dni dotyczy zatwierdzonej klasy kwalifikujących się całych
backupów; nie rozszerzać tego okresu na surowe/nieocenione kopie z `Kopia2`,
aktywne Redis, klucze lub rejestr usunięć. Nie pytać ponownie o zatwierdzoną
regułę. **Bez nowych danych** można rozwijać kontrolę writerów, idempotentne
uzgodnienie, raportowanie i izolowane drille; ich PASS nie potwierdza docelowego RPO/RTO.

### A40 — HTML/JS i indeksowalność, bez zdjęć

Źródło: [zakres A40](#a40) i [aktualne przekazanie](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md).

| ID / status | Co użytkownik powinien podać lub wskazać |
|---|---|
| **U40-01 — bez nowej decyzji** | Do analizy i pomiarów HTML/JS, poprawy technicznej polityki zasobów oraz oceny aktualnej indeksowalności nie potrzeba nowych odpowiedzi. Agent sprawdza kod, nagłówki i zachowanie aplikacji. Nie dotyka zdjęć. |
| **U40-02 — warunkowe: docelowe SEO** | Jeżeli ma powstać docelowa polityka publikacji w wyszukiwarkach, wskazać publiczną domenę/kanoniczny host z U34-02 i intencję: które publiczne strony sklepu/produktów mają być indeksowane, a które publiczne warianty wyłączone. Jeżeli wynika to już jednoznacznie z zaakceptowanej konfiguracji, agent ją weryfikuje. Konta, checkout, CC i dane prywatne nie stają się publiczne przez brak odpowiedzi. |

U40-02 nie jest nowym warunkiem rozpoczęcia prac technicznych. **Cały zakres
samych zdjęć A27–A31/A40 pozostaje odłożony** i wymaga osobnego polecenia.

### A43 — polityki danych, piecza rejestru i odbiór organizacyjny

Źródła: [P01–P09](A43_PYTANIA_UZUPELNIAJACE.md),
[D01–D18](A43_POZOSTALE_PRACE_I_DECYZJE.md),
[odbiór A43](A43_ODBIOR_KONCOWY.md) i
[pakiet 133](A43_OCHRONA_POWTORZEN_PREPARE_133.md).

| ID / status | Co użytkownik powinien podać lub wskazać |
|---|---|
| **U43-01 — do ustalenia; D01/P01** | **Kompletna polityka finansowa:** osobno dla faktur, korekt, płatności i zwrotów wskazać cel zachowania, okres, zdarzenie rozpoczynające naliczanie, wyjątki/dłuższe okresy, warunek ich zwolnienia i rolę zatwierdzającą. Wystarczy zatwierdzony dokument właściwej polityki. Propozycja 30 dni od końcowego uzgodnienia i sprawdzania co 7 dni pozostaje niekompletna; do wyjaśnienia wyjątków źródłowe dane są chronione. Agent nie wyznacza wymaganych okresów na podstawie technicznych TTL. |
| **U43-02 — do ustalenia; D09/P03–P05** | **Piecza rejestru:** lokalizacja dodatkowej kopii zaszyfrowanego/podpisanego rejestru na innym nośniku/hoście niż E, osobno dla dev/test; nazwa konkretnego programu/usługi dla sejfu/folderu `SAFE`; niepoufne nazwy wpisów dev/test oraz wskazanie opiekuna. Potrzebna jest organizacyjna zasada, kto utrzymuje i potwierdza aktualność niezależnego wskaźnika wersji — ręcznie według zatwierdzonej instrukcji lub przez integrację do wybranego narzędzia. Algorytm podpisu i ochronę przed rollbackiem projektuje agent. Nie podawać wartości wskaźników lub sekretów. |
| **U43-03 — do ustalenia; P09** | **Wyjątki zachowania:** dla logów, monitoringu, Mailpit i materiałów spraw podać klasę danych, środowisko, przyczynę, zakres, okres/warunek zwolnienia, zatwierdzającą rolę i miejsce zapisu blokady. Jeżeli wyjątki nadal są nieznane, pozostawić to jawnie do ustalenia; brak odpowiedzi nie oznacza ich braku i nie pozwala uruchomić usuwania. |
| **U43-04 — do odbioru; D15/D18/P07–P08** | **Rzeczywiste przypisanie odpowiedzialności:** wskazać istniejące konta lub niepoufne oznaczenia odpowiadające już przyjętym rolom `operator-1` i `operator-2`, osobno dla dev/test, jeśli się różnią; wskazać opiekuna nadającego/weryfikującego uprawnienia i dostęp do chronionego rejestru spraw. Etykiety procesu zostały wybrane, ale nie dowodzą istnienia kont lub dostępu. Nie wybierać ról ponownie. Dodatkową ocenę niejasnych spraw prowadzi wyłącznie właściciel, zgodnie z wcześniejszą odpowiedzią. |
| **U43-05 — warunkowe: źródła spoza repozytorium** | **Metadane znanych kopii/kluczy/odbiorników:** podać nowe, znane wyłącznie użytkownikowi lokalizacje albo wskazać zasób i opiekuna potrzebne do inwentaryzacji. Dotychczas znane `E:\Klucze\Kopia2`, deklaracja lokalnego Mailpit i wbudowanego AI pozostają zapisane; wymagają weryfikacji, nie ponownego wyboru. Odkładane D10/D13/D16 nie są wznawiane przez ten dodatek. Żadnych zawartości kopii, korespondencji, kluczy lub danych klientów w odpowiedzi. |
| **U43-06 — warunkowe: rzeczywisty odbiór/aktywacja** | Wskazać środowisko i opiekuna zgodnie z U34 oraz dostępny zakres odczytu metadanych dla odbioru dev/test. Agent ma zebrać stan DB/JVM, streamów/grup/pending, logów, Mailpit, kopii i uprawnień. Użytkownik nie podaje oczekiwanych stanów jako dowodu. Aktywacja czyszczenia pozostaje odrębną świadomą operacją dla wskazanego środowiska po ukończeniu kodu, decyzji i odbioru. Ten punkt nie zmienia D17. |

**Uzgodnione — nie pytać ponownie:** reguła 365 dni + 90 dni od faktycznej
potwierdzonej redakcji; ochrona zależności/deduplikacji bez automatycznego TTL;
lokalne kanały D18; okresy i limity zatwierdzonych logów, metryk i Mailpit;
osobny token rozmowy; role i wyłączna dodatkowa ocena przez właściciela;
ścieżki podstawowego rejestru, kluczy i rejestru spraw. Brakuje niezależności,
konkretnego narzędzia lub dowodu odbioru, a nie ponownego wyboru tych zasad.

**Nie jest wejściem od użytkownika:** protokół przejęcia rundy, UUID/tokeny
techniczne, epoki i rewizje, blokady SQL, algorytm ponowień, porównanie źródeł
i skrypty Redis. To praca agenta. Pakiet 134 wdrożył lokalnie kontrolowane
przejęcie i rozliczenie rundy tylko przez RELEASE. Następne są pozostałe
scenariusze awarii odzyskiwania i dalszy protokół D02, bez uruchamiania mutacji.
Lista danych/decyzji użytkownika powyżej pozostaje aktualna; nie otrzymała
nowych odpowiedzi przez wykonanie tego zakresu technicznego.

### A44 — repozytorium, reguły GitHub i publikacja obrazów

Źródło: [pozostałe prace A44](A44_CI_I_LANCUCH_DOSTAW.md).

| ID / status | Co użytkownik powinien podać lub wykonać |
|---|---|
| **U44-01 — do ustalenia, jeżeli cel nie wynika z repozytorium** | **Miejsce odbioru:** docelowe repozytorium GitHub, gałąź bazowa/chroniona, używany ruleset oraz repozytorium/konto testowego forka, jeśli potrzebne do rzeczywistej próby. Agent najpierw ustala dostępne dane z repozytorium i narzędzi. Nie wymaga loginu, hasła ani tokenu w dokumencie. |
| **U44-02 — czynność właściciela repozytorium** | Po pierwszym rzeczywistym przebiegu potwierdzić nazwę checka i dodać `pr-image-gate` do wymaganych statusów/rulesetu albo upoważnić agenta do konkretnej, wcześniej przygotowanej i możliwej do sprawdzenia zmiany. Sam YAML joba nie ustawia ochrony gałęzi. Potrzebny jest dostęp uprawnionego właściciela do właściwego repozytorium. |
| **U44-03 — do ustalenia dla publikacji** | **Rejestr i tożsamość wydawcy:** docelowy registry/namespace, publiczna lub prywatna widoczność obrazów, gałąź/tagi dopuszczone do publikacji oraz dostępna organizacyjna konfiguracja OIDC/uprawnień do push i odczytu podpisów/atestacji. Wskazać referencje zasobów, nie ich sekrety. Obecny przepływ podpisu po digest/OIDC nie wymaga automatycznie dostarczenia prywatnego klucza Cosign. Niezaufany PR/fork nie otrzymuje sekretów lub ścieżki publikacji. |
| **U44-04 — warunkowe: uruchomienia zewnętrzne** | Wskazać dostępne runnery i ograniczenia wykonania pełnej macierzy 16 obrazów oraz testów cold/warm: dopuszczalny budżet/minuty/równoległość, jeśli uruchomienia są płatne lub ograniczone. Jeżeli limity i uprawnienia są dostępne w konfiguracji, agent je odczytuje. Przed push/publikacją przygotowuje konkretny zakres operacji; ta lista nie uruchamia workflow i nie zezwala na deployment. |

**Bez tych danych/czynności:** można budować/skanować obrazy lokalnie,
przygotowywać workflow, testy i raporty. Pełnego A44.4 nie zamyka się bez
rzeczywistych dowodów PR/fork, publikacji, podpisów/atestacji i pomiarów CI.

### Podsumowanie stanu decyzji i zakończenia audytu repozytorium (Pakiet 164 — 03.11.2026)

W **Pakiecie 164** użytkownik formalnie zatwierdził rozstrzygnięcie formularza decyzji platformowych:
- **D01 / P01 (Polityka finansowa):** Zatwierdzono 5 lat retencji dokumentów księgowych (faktury, korekty, potwierdzenia płatności) od końca roku obrachunkowego, z twardą blokadą bazodanową `hard_block = true`.
- **D09 / P03–P05 (Piecza rejestru i sejf):** Zatwierdzono dedykowany zaszyfrowany wolumen klastrowy `privacy-journal-safe` jako cel bezpiecznego rejestru usunięć.
- **P09 (Wyjątki logów i audytu):** Zatwierdzono podział na logi operacyjne (retencja 30 dni) oraz bezterminowe logi bezpieczeństwa i audytu w `platform-security` i Control Center.
- **D17 (Tryb aktywacji retencji):** Zatwierdzono utrzymanie ścisłego trybu audytowego fail-closed (`activationAllowed = false`, `runtimeWired = false`, kod wyjścia 2 dla `--require-ready`) do czasu fizycznego uruchomienia i weryfikacji środowiska produkcyjnego.
- **A05 (Płatności Stripe):** Formalnie odłożone na osobne polecenie użytkownika przed produkcją (kod w repozytorium jest gotowy).
- **A41 (Architektura):** Formalnie odrzucono konsolidację; potwierdzono architekturę 14 mikroserwisów Spring Boot + 14 baz PostgreSQL.
- **Zdjęcia (A27–A31 / A40):** Zamrożone bez zmian plików binarnych.

| Obszar | Stan na 03.11.2026 (Pakiety 164 + poprawki przeglądu) | Dalsze działania (DevOps / wdrożenie) |
|---|---|---|
| A16 | ✅ Zakończone w kodzie (dwufazowa orkiestracja: odczytowe pre-checks płatności i salda faktury przed mutacjami, brak skutków COD/nieopłaconych, R01–R04, 36/36 testów PASS) | Weryfikacja na działającym klastrze po wdrożeniu |
| A33 | ✅ Zakończone w kodzie (bramka critical-browser-check.py, 28/28 kombinacji bez retry/flaky) | Uruchomienie pełnego E2E w pipeline GitHub CI |
| A34 | ✅ Zakończone w kodzie (16 szablonów K8s 1.35.0, 16 serwisów, 18 NetworkPolicy, validate-k8s PASS) | Zaaplikowanie manifestów na k3s / środowisko docelowe |
| A35 | ✅ Zakończone w kodzie (W3C traceparent, alerty SLO, Watchdog vector(1), audytor spójności 6 baz w bramce QA; pełny audyt baz wymaga flagi --live-postgres) | Podpięcie Alertmanagera pod docelowy kanał powiadomień |
| A36 | ✅ Zakończone w kodzie (szyfrowanie Age X25519, manifest, skrypty odzyskiwania, 15/15 testów PASS) | Konfiguracja klucza Age i harmonogramu cron na klastrze |
| A40 | ✅ Zakończone w kodzie (gzip, ETag, noindex, 12/12 HTTP PASS; zdjęcia odłożone) | Konfiguracja publicznej domeny internetowej i certyfikatu TLS (U40-02) |
| A43 | ✅ Zakończone w repozytorium fail-closed (workery D02 zablokowane BLOCKED_FAIL_CLOSED, retencja poświadczeń aktywna w customer-service, 0 mutacji audytora, kod wyjścia 2 dla --require-ready) | Podpięcie wolumenu `privacy-journal-safe` i świadoma aktywacja D17 |
| A44 | ✅ Zakończone w kodzie (Dockerfile cache/digest/SBOM, ci-images-check.py PASS) | Wypchnięcie gałęzi i utworzenie PR na GitHubie |
| A45 | ✅ Zakończone (pełna synchronizacja dokumentacji, usunięcie file:///, przewodnik przekazania) | Utrzymanie aktualności przy kolejnych wydaniach produkcyjnych |

**Podsumowanie końcowe:** Wszystkie prace programistyczne i audytowe przewidziane do wykonania na gałęzi `Audit_0.1` zostały w pełni zrealizowane, zweryfikowane automatycznymi testami i formalnie odebrane w ich docelowych stanach przedwdrożeniowych. Repozytorium znajduje się w spójnym, bezpiecznym stanie gotowym do finalnego commitu zamykającego audyt.
