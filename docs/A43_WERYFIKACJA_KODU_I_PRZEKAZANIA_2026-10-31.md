# A43 — weryfikacja kodu i dokumentu przekazania

**Aktualizacja 01.11.2026:** [pakiet 123](A43_POPRAWKI_BEZPIECZENSTWA_2026-11-01.md)
zawiera poprawki i wycofanie niekompletnych ścieżek mutacji. Poniższy raport
zachowuje ustalenia historyczne; aktualny punkt wznowienia jest w przekazaniu.

Stan: **31.10.2026, Audit_0.1**. Przedmiot: zgodność
[dokumentu przekazania](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md)
z bieżącą implementacją, ze szczególnym uwzględnieniem pakietów 119–122.

**Wynik: deklaracje ukończenia kroków 1–4 są przedwczesne. A43.40 i A43.41
pozostają NOT_READY. Następna praca to poprawki kodu i ponowny odbiór;
sam przegląd środowisk dev/test nie wystarczy.**

Nie zmieniano kodu produkcyjnego, sekretów ani rzeczywistych środowisk.
Próby obejmują sztuczne metadane i izolowane PostgreSQL Testcontainers.
Poniższe problemy są blokadami odbioru i podłączenia nowych mechanizmów;
nie są dowodem, że obecny runtime wykonywał już nieuprawnione czyszczenie.

## Ustalenia wymagające poprawy

### R01 — P1: domyślna blokada P09 nie jest egzekwowana

Źródło: [OperationalRetentionGuard.java](../platform-security/src/main/java/org/demo/platformsecurity/privacy/OperationalRetentionGuard.java),
`isCleanupBlocked`, linie 102–114.

Konstruktor ustawia `defaultFailClosed=true`, ale sprawdzenie operacji nie
korzysta z tej flagi ani z pozytywnego dowodu zgody. Gdy brak pasującego wpisu
na lokalnej mapie, metoda zwraca `false`. Nierozstrzygnięte wyjątki P09
nie blokują więc kwalifikacji do usunięcia.

Próba wykonania: `defaultFlag=true`, `unresolvedP09ActuallyBlocks=false`;
Mailpit kwalifikuje starą wiadomość do usunięcia (`unresolvedP09MailPruned=1`).
Istniejący test `defaultGuardIsFailClosed` sprawdza wyłącznie getter flagi.

**Poprawka i odbiór:** sprawdzać zaufaną zgodę właściwego zakresu i wersji
polityki, blokować przy jej braku; testować decyzję każdej ścieżki operacji,
a nie wartość pola konfiguracyjnego. Samo wyłączenie flagi nie jest dowodem zgody.

### R02 — P1: limit rozmiaru logów omija blokadę całego źródła

Źródło: [OperationalLogRetention.java](../platform-security/src/main/java/org/demo/platformsecurity/privacy/OperationalLogRetention.java),
linie 116–117 oraz 143–146.

Pierwsza faza sprawdza blokadę pliku i `sourceName`. Zablokowany plik trafia
jednak do `activeSourceFiles`. Faza redukcji rozmiaru sprawdza tylko ścieżkę
pliku i może ponownie wybrać go do usunięcia, pomijając blokadę źródła.

Próba bez zapisu na dysk: źródło ma aktywną blokadę i segment 150 MiB;
raport jednocześnie zwraca `filesBlocked=1` oraz `filesPruned=1`.
Przy `dryRun=false` ta sama ścieżka wywołuje `Files.deleteIfExists`.

**Poprawka i odbiór:** jeden warunek kwalifikacji dla wieku i rozmiaru,
ponowne sprawdzenie blokad przed mutacją i test z blokadą źródła oraz
przekroczonym budżetem. Zablokowany segment nie może trafić do usuwania.

### R03 — P1: retencja zamkniętej sprawy ignoruje nową blokadę zachowania

Źródło: [ManualCaseProcedureService.java](../platform-security/src/main/java/org/demo/platformsecurity/privacy/ManualCaseProcedureService.java),
`evaluateCaseRetention`, linia 137.

Po zamknięciu sprawy metoda sprawdza wiek, ale nie konsultuje `guard`.
Nowa blokada zachowania nie zapobiega oznaczeniu materiałów i minimum jako
usuniętych. Próba z aktywną blokadą `CASES` potwierdziła oba znaczniki `true`.
To obecnie zmiana lokalnego rekordu, nie rzeczywiste skasowanie plików.

**Poprawka i odbiór:** uwzględnić aktualne blokady i P09 przed każdą fazą;
trwale rozróżnić kwalifikację, wykonanie i potwierdzenie operacji.
Nowa blokada po zamknięciu ma wstrzymać dalsze czyszczenie.

### R04 — P1: protokół sześciu źródeł nie stanowi bariery zapisów

Źródła: [PeerRetentionEvidence.java](../platform-security/src/main/java/org/demo/platformsecurity/privacy/PeerRetentionEvidence.java),
linie 19–56; [CommerceRetentionWorkflow.java](../order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionWorkflow.java),
`fenceAndAssess`, linie 136–211.

`PREPARE` zapisuje znacznik w `ConcurrentHashMap`. Blokada advisory i blokada
wiersza są transakcyjne: wygasają wraz z zakończeniem tego żądania.
Ścieżki zapisów biznesowych nie sprawdzają mapy. Inna instancja ma własną mapę,
a restart usuwa znaczniki. `VERIFY` może wykryć zmianę, która już wystąpiła,
lecz nie blokuje kolejnej zmiany po jego wykonaniu. Workflow pomija Inventory
w rundzie barier i zwalnia znaczniki przed zwróceniem wyniku oceny.

Próba PostgreSQL: pierwszy `PREPARE=FENCED`, druga instancja przyjmuje inny
token tego samego zakresu (`FENCED`), a zapis `shipments.sync_state` przy
przygotowanej barierze przechodzi (1 zmieniony wiersz).

**Poprawka i odbiór:** trwały, wspólny protokół egzekwowany przez ścieżki
zapisów i wykonawców mutacji, z odzyskiwaniem/replay. Testy powinny obejmować
zapis po VERIFY, drugą instancję, restart i utrzymanie ochrony przez całą
mutację. Dotychczasowe wykrycie zmiany przed VERIFY nie odbiera tej gwarancji.

### R05 — P1: workery nie egzekwują kompletnej zgody na wykonanie

Źródła: [CommerceRetentionRedactionWorker.java](../order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionRedactionWorker.java),
linia 51; [ReturnsCommerceRetentionRedactionWorker.java](../returns-service/src/main/java/org/demo/returnsservice/privacy/ReturnsCommerceRetentionRedactionWorker.java),
linia 33; [DeliveryCommerceRetentionRedactionWorker.java](../delivery-service/src/main/java/org/demo/deliveryservice/privacy/DeliveryCommerceRetentionRedactionWorker.java),
linia 31.

Order czyta zapisany stan zadania, obserwacje i GUARD, ale nie wymaga
aktywnej rozproszonej bariery ani jej tokenu. Sprawdza nazwy/stan GUARD,
bez weryfikacji skrótu `FIELD_SCOPE_REVIEWED` stosowanej w ścieżce oceny.
Wiek jest liczony względem przekazanego `Instant now`. Returns i Delivery
przyjmują tylko `orderId`: nie sprawdzają 365 dni, generacji, globalnych
dowodów ani uzgodnienia sprawy. Lokalna blokada advisory tego nie zastępuje.

`activationAllowed=false` jest wartością w wyniku, a nie warunkiem wykonania
SQL. W izolowanej próbie Order zwrócił `REDACTED` bez wywołania protokołu barier.

**Poprawka i odbiór:** wymagać zaufanego, bieżącego upoważnienia i ochrony
zakresu w każdym wykonawcy; walidować plan pól, czas i rewizje. Wykazać brak
mutacji bez bariery, przy starej zgodzie, otwartej sprawie i kwarantannie.
Testy korzystające z ręcznie wstawionych `CONFIRMED` nie zastępują tej walidacji.

### R06 — P1: redakcja nie ustanawia kompletnej ochrony przed odtworzeniem

Źródła: [CommerceRetentionRedactionWorker.java](../order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionRedactionWorker.java),
część wykonawcza od linii 167;
[V35__commerce_retention_d02_redaction.sql](../order-service/src/main/resources/db/migration/V35__commerce_retention_d02_redaction.sql),
linia 24; [CommerceRetentionRedactionPostgresIT.java](../order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionRedactionPostgresIT.java),
`triggersPreventRestorationOfRedactedData`.

Trigger blokuje zapisy w tym zakresie, gdy istnieje rekord
`commerce_privacy_locks`. Worker go nie zakłada. Test deklarujący ochronę
przed odtworzeniem sam dodaje taki rekord już po redakcji, więc odbiera
silniejszy stan niż ten pozostawiany przez wykonawcę.

Istniejące CHECK poprawnie blokuje przywrócenie samego e-maila przy
`privacy_redacted=true`; nie należy opisywać go jako nieskutecznego.
Próba PostgreSQL potwierdziła jednak przywrócenie pełnego kontaktu wraz
z cofnięciem flagi (`1` zmieniony wiersz) oraz `order_items.product_name`
bez dodatkowej blokady (`1` zmieniony wiersz). Sama redakcja nie zapobiegła
odtworzeniu starego stanu. Nie dodawano ręcznie blokady prywatności.

**Poprawka i odbiór:** atomowo ustanowić właściwą ochronę nieodwracalnego
stanu i sprawdzić stare procesy/zdarzenia/replay. Nie używać znacznika usunięcia
konta jako domyślnej zgody do retencji aktywnego konta.

### R07 — P2: częściowy zakres workerów i dowodów opisano jako ukończony krok

Worker Order zapisuje `redacted_at` tylko dla ORDER. Returns i Delivery zwracają
czas, lecz nie utrwalają analogicznego potwierdzenia redakcji w swoim protokole.
Nie ma kompletnego przejścia ośmiu źródeł do fazy MINIMUM_REMOVAL. `PeerRetentionEvidence.collect`
dla innej fazy niż REDACTION nadal zwraca brak dowodu redakcji.

`CommerceRetentionMinimumRemovalWorker` czyta ręcznie przygotowane dowody,
a po jawnym parametrze boolean usuwa jedynie `order_items`; nie usuwa całego
minimum handlowego i nie zapisuje trwałego potwierdzenia tej operacji.
W dokumentacji P121 opisano nieobecne elementy: `DependencyReleaseProvider`,
automatyczną zmianę fazy oraz status `MINIMUM_RETAINED`.

**Poprawka i odbiór:** doprecyzować zakres i domknąć trwałe potwierdzenia,
przejścia faz, ponowienia i zwalnianie zależności dla całego wymaganego
łańcucha; zachować chronione dokumenty i znaczniki. Testować rzeczywiste
adaptery i źródła dowodów zamiast wyłącznie wstawiania ich SQL-em.

### R08 — P2: QA i procedury zwracają deklaracje bez potwierdzenia skutków

Źródła: [operational-retention-check.py](../scripts/qa/operational-retention-check.py),
linie 94–120; [ManualCaseProcedureService.java](../platform-security/src/main/java/org/demo/platformsecurity/privacy/ManualCaseProcedureService.java),
linie 73–74 i 167–178; klasy Mailpit/Monitoring.

Sprawdzian QA czyta kilka parametrów Compose/JSON i obecność plików klas.
`p09FailClosedEnforced`, obsługę załączników i wsparcie stopu zwraca jako stałe.
W tej weryfikacji skrypt dał PASS mimo odtworzonych R01–R03.

`MailpitRetentionManager` przetwarza listę metadanych, bez wywołania API Mailpit.
Monitoring ocenia przekazane parametry/listy, bez integracji z magazynem.
Natywne limity w Compose nie konsultują strażnika Java P09.
Rejestry blokad, spraw i stopów są w pamięci. `executeScopedWorkerStop`
wpisuje `executionTerminated=true`, ale nie sygnalizuje wykonawcy; w kodzie
produkcyjnym nie znaleziono odbiorcy `isWorkerStopped`. To nie jest trwały
audyt, podpis operatora ani dowód zatrzymania workera.

**Poprawka i odbiór:** rozdzielić kontrolę konfiguracji od testu zachowania
i rzeczywistego skutku. Zapewnić trwałość, uprawnienia z uwierzytelnionego
kontekstu, integrację stopu i potwierdzenie przez wykonawcę. Retencję natywną
podłączyć do uzgodnionej ochrony albo blokować jej zastosowanie. P09 pozostaje otwarte.

## Co potwierdzono i czego nie sprawdzano ponownie

- Aktualny hash polityki: `22c7c3785c1a21b6a6049b6e61119e1502f14a0fda4ac5c7cac5779e39139d88`;
  rewizja `a43.40-2026-10-31.15` i zapis decyzji pozostają jak w przekazaniu.
- Ponowione kontrole lifecycle, klasyfikacji, retencji handlowej i kandydata
  polityki: PASS. Inwentarz: 106 tabel / 1017 kolumn / 188 migracji.
- `retention-policy-check.py --require-ready`: **kod 2, NOT_READY**.
  Kod 1 jest błędem kontroli i nie powinien być akceptowany zamiennie z 2.
- `operational-retention-check.py`: PASS w przygotowanym Python WSL.
  W natywnym Python brakuje PyYAML; ten błąd środowiska zapisano oddzielnie.
- Zapisane XML: 209 platform-security, w tym 14 testów jednostkowych pakietu
  122, oraz 8 testów PostgreSQL Order pakietu 121 — bez błędów. Nie uruchamiano
  ponownie całego tego zestawu; nowe próby behawioralne są opisane osobno.
- Nie powtarzano pełnej regresji wszystkich A01–A44, przeglądarek ani pełnego
  drillu 14 baz/Redis. To przegląd przekazania i kodu ostatnich pakietów,
  a nie nowy końcowy odbiór całej aplikacji.

Artefakty bieżącej weryfikacji: [katalog raportu](../tmp/audit-handover-code-review/report.json),
[próby operacyjne](../tmp/audit-handover-code-review/operational-behavior-wsl.log),
[próby PostgreSQL](../tmp/audit-handover-code-review/postgres-behavior.log).
Pliki `tmp` są lokalnymi dowodami i mogą nie być obecne po przeniesieniu repozytorium.

## Punkt wznowienia

1. Naprawić R01 i dodać regresję decyzji o blokowaniu przy nierozstrzygniętym P09.
2. Naprawić R02/R03 oraz zastąpić deklaracje bezpieczeństwa w QA testami zachowania.
3. Dokończyć R04–R07 przed podłączeniem workerów do runtime; odebrać R08.
4. Następnie dowody dev/test i pełny A43.41, po uzupełnieniu wymaganych decyzji.

A05, A41 i zdjęcia pozostają odłożone. D01, niezależna kopia/SAFE/D09 i P09
nadal wymagają decyzji użytkownika. Weryfikacja nie zmienia tych ustaleń.
