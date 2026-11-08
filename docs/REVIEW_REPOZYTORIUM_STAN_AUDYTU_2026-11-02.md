# Review lokalnego repozytorium — kod, dokumentacja i stan audytu

**Data: 02.11.2026 · branch: `Audit_0.1` · wynik: WYMAGA POPRAWEK.**
Przegląd obejmuje istniejący katalog roboczy, także pliki niezatwierdzone w Git.
Ostatni deklarowany pakiet to **161**. Nie oznacza to zakończenia A43.40.
Pakiet **160 / A16 wymaga napraw i ponownego odbioru integracyjnego**.
Nie zmieniano kodu aplikacji, konfiguracji, decyzji użytkownika ani aktywacji.
Dodano ten raport i oznaczenie wyniku review w dokumencie przekazania.

## 1. Najważniejsze ustalenia

| ID | Priorytet | Ustalenie | Obszar |
|---|---|---|---|
| R01 | P1 | Integracja zwrotów nie ma kompletnych adresów, uprawnień usługowych i zależności sieciowych; bramka Kubernetes nie przechodzi | A16/A34/A33 |
| R02 | P1 | Odpowiedź płatności nie zawiera pola użytego do rozpoznania Stripe; stan refundacji nie jest sprawdzany przed uzgodnieniem | A16 |
| R03 | P1 | Brak trwałej rezerwacji operacji przed skutkami zewnętrznymi; ponowienie może powtórnie zwiększyć zapas | A16 |
| R04 | P1 | Kwota zwrotu nie wynika z pełnego rozliczenia zakupu; istnieje fallback do całego zamówienia lub fikcyjnych 10,00 | A16 |
| R05 | P1 | Deklaracja, że wszystkie mechanizmy są dry-run, jest niezgodna z domyślną konfiguracją starszej retencji bezpieczeństwa | A43/dokumentacja |
| R06 | P2 | Pakiet 161 nie dostarcza wykonawczego D02; wskazane workery i weryfikator są identyczne jak w 155 | A43.40 |
| R07 | P2 | Deklarowane rozstrzygnięcie decyzji nie jest spójne z przypiętym rejestrem decyzji i polityką | A43/dokumentacja |
| R08 | P2 | Audyt między bazami jest testem modelu w pamięci, a Watchdog nie ma niezależnego odbiornika | A35/A36 |
| R09 | P2 | Zagnieżdżony zakres dostarczenia Notification rozpoczyna inny traceId niż trwające żądanie HTTP | A35 |
| R10 | P2/P3 | Dokumenty wskazują różne punkty zakończenia i błędny kod bramki; są też odnośniki zależne od komputera | dokumentacja |

P1 oznacza problem wymagający naprawy przed uznaniem danego procesu za odebrany.
Zielone testy lokalne poszczególnych komponentów nie zamykają tych ustaleń.
Część błędów finansowych jest obecnie przesłonięta przez niedziałające połączenia
z R01; muszą zostać naprawione przed dopuszczeniem rzeczywistych skutków domenowych.

### R01 — połączenia i autoryzacja nowej orkiestracji A16

`returns-service/.../returns/ReturnInventoryClient.java:18`,
`ReturnPaymentClient.java:21` i `ReturnBillingClient.java:23` mają fallbacki
18084, 18083 i 18086. W lokalnym mapowaniu są to odpowiednio Payment, Inventory
oraz Notification; Billing działa na 18095. W `compose.apps.yaml:470` Returns
nie otrzymuje nowych URL-i Inventory/Payment/Billing. Loopback w kontenerze
nie wskazuje innego serwisu.

Nawet po poprawieniu adresów tożsamość `SERVICE / returns-service` nie otrzymuje
zgody w `platform-security/.../RoutePolicy.java:92` na nowe operacje magazynowe,
fakturowe i płatnicze. `ServiceAuthenticationInterceptor` zastępuje poświadczenia
wywołującego tożsamością usługi. Operacje takie jak przyjęcie zapasu i refund
mają również jawne `Access.actor().operator()` w kontrolerach docelowych.
Reprodukcja potwierdziła odmowę wszystkich sześciu użytych tras.

`deploy/components.json` nadal deklaruje dla Returns tylko Customer i Order.
Bezpośrednie `validate-k8s.check_inventory()` zwraca:
`HTTP client missing from network inventory: returns-service`.
Pełne testy Python zatrzymują przygotowanie klasy testów Kubernetes z tym samym błędem.
Reguły oparte na tym inwentarzu nie dopuszczają nowych połączeń.

**Do zrobienia:** zdefiniować wąskie, dedykowane operacje między usługami,
uzupełnić adresy i inwentarz, wygenerować/zweryfikować odpowiednie reguły sieciowe.
Nie nadawać ogólnych praw operatora usłudze i nie usuwać kontroli dostępu.
Odbiór musi obejmować rzeczywiste HTTP i tożsamości, nie wyłącznie mocki klientów.

### R02 — pozorne rozliczenie zwrotu pieniędzy

`ReturnPaymentClient.PaymentSnapshot` oczekuje `checkoutSessionId`, którego
`payment-service/.../web/PaymentResponse.java` nie zwraca. Reprodukcja użyła
rzeczywistego `PaymentResponse`, lokalnego HTTP i rzeczywistego klienta Returns:
status `SUCCEEDED`, brak session ID, **0 wywołań refund** i wynik
`SETTLED_NON_STRIPE_CARD`. Sam brak tego pola nie jest dowodem rozliczenia gotówki.

W `ReturnDomainEffectsService.java:63–64` po wywołaniu refundacji wystarczy jej ID.
Stany `REQUESTED`, `PENDING`, `FAILED` i `REVIEW_REQUIRED` nie powodują odmowy.
Dla wszystkich czterech reprodukcja uzyskała wynik `REF-100`.
`ReturnService.java:73–74` przekazuje taki wynik do `completeWithEffects`,
a `ReturnStateStore.java:45` nie sprawdza potwierdzenia finansowego przed COMPLETED.

**Do zrobienia:** poprawić kontrakt odczytu płatności i wymagać trwałego,
jednoznacznego potwierdzenia skutku finansowego. Stan niepewny lub oczekujący
musi pozostawić proces w oczekiwaniu/ocenie. Płatności nie-Stripe wymagają
odrębnego dowodu rozliczenia; tekst `SETTLED_NON_STRIPE_*` go nie zastępuje.

### R03 — ponowienie po częściowym skutku może dublować zapas

`ReturnService.reconcileEffects` sprawdza stan i wersję przed HTTP, ale nie zapisuje
trwałego zamiaru wykonania ani rezerwacji operacji. Blokada rekordu jest dopiero
w `ReturnStateStore.completeWithEffects`, po wywołaniach zewnętrznych.
Klucze skutków w `ReturnDomainEffectsService.java:30–31,48,62` zawierają UUID
polecenia operatora. Nowy UUID dla tej samej nadal zatwierdzonej sprawy daje nowe klucze.

Reprodukcja: przyjęcie 1 sztuki przez idempotentny mock magazynu, błąd Billing,
ponowienie tej samej sprawy z nowym UUID. Wynik: **zapas +2 dla 1 zwracanej sztuki**.
Jest to kod produkcyjny orkiestratora z modelem magazynu deduplikującym po operationId;
nie był to eksperyment na rzeczywistych stanach użytkownika.

Także powtórka tego samego UUID po wystawieniu korekty nie ma trwałego zapisu
pierwotnej wersji salda. Ponowny odczyt nowszej wersji zmienia polecenie korekty;
Billing poprawnie odrzuca ponowne użycie klucza z inną wersją (`CORRECTION_KEY_REUSED`).

**Do zrobienia:** trwały proces z niezmiennym zakresem, rezerwacją przed HTTP,
stabilnymi kluczami kroków i potwierdzeniami rezultatów. Przetestować utratę
odpowiedzi i awarie po każdym skutku oraz współbieżne różne UUID.
Lokalny rollback Returns nie cofa zapisu w innej bazie.

### R04 — brak bezpiecznej polityki kwoty i częściowych zwrotów

`ReturnDomainEffectsService.java:79–101` sumuje unitPrice × quantity. Nie rozlicza
alokacji rabatu i kosztów całego zakupu, a przy braku kwoty pozycji używa całego
totalPrice. Ostateczny fallback to **10,00 bez dowodu należności**.
Reprodukcja częściowej sprawy potwierdziła przejście na całe 48,00 zamówienia,
a przy braku obu kwot na 10,00. Przykład rabatu pokazuje brak polityki alokacji;
nie jest tu zatwierdzana nowa metoda jej naliczania.

Dodatkowo `PaymentRefundStateStore.java:31–35` obsługuje zwrot równy całej płatności.
Częściowa selekcja dozwolona przez Returns nie ma zgodnego protokołu finansowego.
Nie domknięto też polityki wielu/korygowanych spraw, skutków kuponów i różnic
między reklamacją a rzeczywistym przyjęciem zwróconego towaru.

**Do zrobienia:** nie zgadywać kwot. Brak wiarygodnego rozliczenia ma blokować
skutek i kierować przypadek do oceny. Zakres częściowych zwrotów i reklamacji
uzgodnić, następnie spiąć go z kontraktami Payment/Billing i testami.

### R05 — D17 nie jest globalnym przełącznikiem wszystkich retencji

Dokument pakietu 158 deklaruje, że wszystkie mechanizmy są dry-run.
Tymczasem `customer-service/src/main/resources/application.yaml:93` domyślnie
ustawia `CUSTOMER_SECURITY_RETENTION_ENABLED:true` i 7 dni. `SecurityRetentionWorker.java:57`
wywołuje `cleanupBatch`; to nie jest jedynie ocena.
Rejestr D17 sam określa zakres jako brak nowej aktywacji przy zachowaniu starszych workerów.

To ustalenie dotyczy kodu i domyślnych parametrów. Nie odczytywano rzeczywistej
konfiguracji runtime, więc nie orzeka ono o wykonanych operacjach na dev/test.

**Do zrobienia:** poprawić zapewnienia w dokumentacji i wskazać tryb każdego
mechanizmu osobno. Zachować dotychczasowe decyzje użytkownika; nie wyłączać
ani nie włączać workerów zbiorczo na podstawie tego review.

### R06 — pakiet 161 nie zamyka wykonania D02

Cztery wskazane workery redakcji/usunięcia minimum oraz `JournalReceiptVerifier`
i test `CommerceRetentionRedactionPostgresIT` mają identyczne skróty jak w pakiecie 155.
Worker Order `redact` kończy się bezwarunkowo `Outcome.BLOCKED` /
`RETENTION_EXECUTION_NOT_CONFIGURED`. Pozostałe wskazane wejścia również odmawiają mutacji.
Komentarz klasy wprost wymaga nowego, zaufanego protokołu wykonania.

Odmowa jest właściwą ochroną. Dziewięć zielonych testów nie jest jednak dowodem
wdrożonego pełnego protokołu redakcji; potwierdza ochronę niekompletnej implementacji.
**A43.40 i A43.41 pozostają otwarte**. Można równolegle zbierać odczytowe dowody
środowiskowe, ale nie zastąpią one wykonawczego D02 i jego odbioru.
Nie należy usuwać blokad ani przełączać pojedynczej flagi w celu domknięcia etapu.

### R07 — sprzeczne deklaracje decyzji

Pakiety 158/161 deklarują rozstrzygnięcie D01–D17, 5-letni okres finansowy
oraz przygotowany SAFE. `scripts/qa/contracts/retention-decisions.json` nadal
zawiera D01 `partial`, plan 30 dni i nierozstrzygnięte wyjątki; D09 `partial`,
nieustalone narzędzie SAFE i niezależną kopię do ustalenia. D04/D05 i dalsze
wpisy także nie mają pełnego odbioru. Polityka `.8` ma `stageComplete=false`.
W przeglądanych konfiguracjach repozytorium nie ma deklaracji wolumenu
`privacy-journal-safe`, wskazanego jako zintegrowany w nowych dokumentach.

Nie rozstrzygam, jaka późniejsza decyzja mogła paść poza tym repozytorium.
**Do zrobienia:** doprowadzić zatwierdzenia, rejestr decyzji, wersję polityki
oraz dowody konfiguracji do jednej spójnej postaci. Nie wyprowadzać prawa do
czyszczenia z samej narracji dokumentu. Nie oceniano tutaj prawidłowości prawnej okresów.

### R08 — ograniczenia audytu baz i watchdoga

`scripts/qa/cross-database-consistency-check.py:454` uruchamia wyłącznie
`run_self_tests()`. Osiem scenariuszy tworzy `DatabaseDataset` w pamięci.
Skrypt nie odczytuje sześciu PostgreSQL ani ich aktualnego stanu. Jest to użyteczny
model inwariantów, ale komunikat „6 databases” i opis odbioru nie dowodzą integracji.

Alert `Watchdog` to `vector(1)` w tym samym Prometheus. Alertmanager nadal ma
pusty odbiornik `local-console`. Nie ma tu potwierdzenia niezależnego wykrycia
zatrzymania całego stosu. Brak działania Prometheus nie uruchomi jego własnej reguły.
Nie należy dodawać zewnętrznych powiadomień wbrew wybranemu kanałowi lokalnemu.

**Do zrobienia:** odrębny odczyt rzeczywistych źródeł i test integracyjny ich
spójności; osobny odbiór mechanizmu nadzorującego monitoring. A35/A36 nadal otwarte.

### R09 — traceId zmienia się wewnątrz tego samego przepływu

`CorrelationIdContext.open(String)` przekazuje null do wariantu z traceparent,
co generuje nowy root trace. `NotificationService.deliver` korzysta z tego wariantu
wewnątrz istniejącego kontekstu HTTP. Reprodukcja potwierdziła zmianę traceId
podczas dostarczenia i prawidłowe odtworzenie zewnętrznego kontekstu po wyjściu.
Odtworzenie MDC nie zapewnia ciągłości śledzenia wewnętrznej operacji.
RedisEventStreams nadal przekazuje tylko correlationId, bez W3C kontekstu zdarzenia.

**Do zrobienia:** rozdzielić nowy kontekst wiadomości od zagnieżdżenia w ramach
trwającego trace; określić propagację HTTP/Redis i sprawdzić całe przejście.
Dotychczasowe korelacja i kontrole uprawnień pozostają odrębnymi mechanizmami.

### R10 — dokument przekazania wymaga ujednolicenia

Nagłówek przekazania wskazywał 161, sekcja 1 nadal 156, rejestr postępu 160,
a opis „bieżącego odbioru” A43 jednocześnie 151. Nowsze opisy A35 nie zostały
przeniesione do tabeli pozostałych prac. Dokumenty 160/161 podają NOT_READY / 1,
podczas gdy rzeczywista bramka zwraca **2**. Kod 1 oznacza błąd kontroli.

Przejrzano odnośniki w 152 dokumentach: 849 lokalnych odnośników, jeden rzeczywiście
brakujący plik (`docs/APKA.md:2503` → `Git_Instruction.md`) oraz 23 odnośniki
`file:///` zależne od komputera. Pięć poprawnych odnośników do katalogów nie jest błędem.

W dokumencie przekazania dodano ostrzeżenie o wyniku tego review i skorygowano
status A16. Pozostałe historyczne deklaracje zachowano jako materiał do uzgodnienia.

## 2. Na jakim etapie są prace

| Zakres | Stan po weryfikacji |
|---|---|
| A01–A03, A22–A26 — granice zaufania i poświadczenia | Istnieje wspólna kontrola tożsamości/własności, bramki i testy; wybrane regresje security PASS. Nowa integracja A16 nie jest poprawnie włączona do tych zasad. |
| Zamówienia, magazyn, płatności, faktury, kupony | Znaczna część lokalnej implementacji i testów istnieje; nowa automatyzacja zwrotów ma R01–R04. Odbiór Stripe pozostaje odłożony. |
| Sklep i CC / A27–A31, A37–A42 | Architektura modułów i testy Node PASS. Wejścia objęte odciskami browser pakietu 153 poza CI nie zmieniły się; przeglądarek w tym review ponownie nie uruchamiano. Same zdjęcia pozostają odłożone. |
| A35 — obserwowalność | Są metryki kolejek/Redis/refund, alerty, nagłówki trace i dashboardy. Pełne śledzenie, spójność rzeczywistych źródeł i niezależny watchdog nie są odebrane. |
| A34/A44 — wdrożenie i CI | Wspólne wersje, piny i zabezpieczenia pipeline istnieją; bieżący inwentarz K8s FAIL. Rzeczywiste środowisko i GitHub wymagają osobnego odbioru. |
| A36/A43 — backup i cykl życia danych | Lokalne mechanizmy, historia i zabezpieczenia są rozbudowane. Wykonawcze D02, spójne decyzje, piecza i pełny odbiór pozostają otwarte. |
| Pakiety 156–159 | Potwierdzono obecność zmian metryk/trace/alertów i modelu spójności; ich zakres jest częściowy i opisany powyżej. |
| Pakiet 160 | Kod istnieje, lecz **nie powinien być oznaczony jako zakończony funkcjonalnie**. |
| Pakiet 161 | Najnowsza deklaracja dokumentacyjna; potwierdzenie istniejących odmów nie stanowi pełnego domknięcia D02. |

## 3. Ile zostało do zakończenia audytu

**10 głównych obszarów do formalnego zamknięcia; 11 pozycji, gdy dwa etapy A43 liczymy osobno.**
Nowe ustalenia R01–R10 są zadaniami w tych obszarach, nie nowymi numerami audytu.

| ID | Co jeszcze jest wymagane |
|---|---|
| A05 | Odłożony odbiór Stripe: wartości/konfiguracja i zewnętrzny sandbox, na osobne polecenie. |
| A16 | Naprawy R01–R04, uzgodniony zakres zwrotów/reklamacji i rzeczywisty odbiór całego łańcucha. |
| A33 | Usunąć błąd CI/K8s, dodać brakujące testy kontraktowe i awaryjne, odbiór zależny od A05. |
| A34 | Spójne zależności i NetworkPolicy, docelowa infrastruktura, sekrety/TLS/CNI oraz pełny odbiór środowiska. |
| A35 | Ciągłość śledzenia, pozostałe metryki i prawdziwa spójność źródeł, watchdog i odbiór monitoringu/SLO. |
| A36 | Globalny checkpoint/uzgodnienie procesu, niezależne kopie i uzgodnione RPO/RTO. |
| A40 | Uzgodnione i wdrożone publiczne SEO oraz pomiary/odbiór środowiska; zdjęcia nadal poza realizacją. |
| A41 | Zakres architektury odłożony; deklarację utrzymania obecnej architektury trzeba spójnie potwierdzić w rejestrze zamiast samodzielnie zmieniać status. |
| A43 | **A43.40:** wykonanie D02, decyzje i integracje; **A43.41:** pełny odbiór. Nadal NOT_READY. |
| A44 | A44.4, realny PR/fork, wymagane checki, 16 obrazów, publikacja/podpisy/atestacje i pomiary CI. |

Liczba kolejnych pakietów implementacji nie jest ustalona. Jeden obszar wymaga
więcej niż jednej zmiany lub decyzji; 10 obszarów nie oznacza 10 kolejnych wdrożeń.
Do jednoznacznego uzgodnienia nowszych deklaracji A05/A41 pozostają odłożone.
Zmiany samych zdjęć A27–A31/A40 nadal wymagają osobnego polecenia.

## 4. Co uruchomiono w tym review

- **19 kontroli/bramek:** 16 PASS, 1 FAIL (inwentarz K8s), 2 oczekiwane blokady:
  A43 NOT_READY / 2 oraz release P1 blokowany przez A05 / 1.
- **168 unikalnych przypadków Java PASS**, bez pominięć w wybranym zestawie:
  security, korelacja/Redis, Gateway, metryki Order, PostgreSQL D02 oraz workflow Returns.
  Jest to wybrana regresja, nie wszystkie testy wszystkich 15 usług.
- **236 testów Node PASS w WSL.** Próba natywna Windows miała błąd uprawnień
  symlink w fiksturze; nie uznano go za defekt aplikacji. Powtórki nie zwiększają liczby.
- **Pełny zestaw Python: 464 uruchomione testy, wynik FAIL**, jeden błąd setUpClass
  Kubernetes. Dalsze przypadki tej klasy nie zostały wykonane. Błąd reprodukuje się
  bezpośrednio w repozytorium. Początkowy błąd izolowanej kopii źródeł naprawiono;
  nie zaliczono brakujących plików kopii jako defektów aplikacji.
- **Promtool/config: PASS**, 13 reguł, testy nieświeżych metryk i 9 zapytań dashboardów.
- **6 grup reprodukcji defektów** na klasach produkcyjnych: odmowa tras,
  rzeczywisty DTO płatności, nieskończone refundacje, ponowienie zwiększające zapas,
  kwoty bez dowodu i zagnieżdżony traceId. Reprodukcje potwierdzają błędy, nie gotowość.
- Przejrzano topologię 16 komponentów (15 JVM + frontend), 14 baz i odwołania
  do migracji; rejestr pozostaje 145 tabel / 1307 kolumn / 232 migracje.
- Zweryfikowano dokumenty bieżące i 152 pliki Markdown pod kątem odnośników.
  W pełnej izolowanej kopii testowej jest 1990 publicznych plików tekstowych/konfiguracji.

Nie uruchamiano rzeczywistych dev/test, Stripe, GitHub, pełnego drillu backupów
ani nowej macierzy przeglądarek. Nie odczytywano sekretów środowisk użytkownika.
Fikstury używały własnych H2/PostgreSQL/Redis, danych i serwera loopback.
Nie wykonano zmian na danych użytkownika ani aktywacji retencji.

## 5. Punkt wznowienia dla użytkownika i agenta AI

1. Zachować bieżący katalog roboczy, w tym zmiany niezatwierdzone i pliki nieśledzone.
   Nie cofać wcześniejszych prac. Ostatnim opisanym pakietem pozostaje 161.
2. **Najpierw R01–R04 / A16**: adresy/topologia, wąskie uprawnienia, kontrakt płatności,
   trwała idempotencja i dopiero potwierdzone skutki. Dodać HTTP kontrakty i testy
   częściowych awarii przed oznaczeniem procesu jako ukończonego.
3. Ujednolicić dokumenty 158–161 i rejestr decyzji, zachowując ochronę istniejących danych.
4. D02 nadal wymaga protokołu wykonania i odbioru. Odczytowe dowody dev/test można
   zbierać równolegle po wskazaniu środowisk; nie zastępują one implementacji.
5. Zadania wymagające wartości/decyzji użytkownika odkładać i kontynuować niezależne
   prace. Samo review nie jest zgodą na aktywację, zmianę retencji lub wznowienie zakresów odłożonych.

Dowody znajdują się w `tmp/review-repository-2026-11-02/`: `report.json`,
`gate-results.json`, `k8s-inventory.json`, `java-results.json`, `java-xml/`,
`python-full.log`, `node-wsl.log`, `monitoring.log`, `probes.json`, `probe-src/`,
`documentation-links.json`, `unchanged-since155.json`, odciski źródeł i logi.
Raporty historyczne pakietów 156–161 pozostają odrębnymi deklaracjami z własnymi granicami.
