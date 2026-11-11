# A43 — co pozostało do wykonania i uzgodnienia

Stan na **31.10.2026**, branch `Audit_0.1`. Zaktualizowano o odpowiedzi użytkownika
D01–D18 przekazane po lokalnym odbiorze A44.3. Dokument zawiera wybory i braki;
samo wskazanie opcji w tym pliku nie aktywuje mechanizmów ani nie zmienia danych.
Wybrane opcje są zapisane w rejestrze poniżej. Pozostałe warianty są propozycjami,
nie dodatkowymi ustaleniami. Sam wybór kierunku nie uzupełnia brakujących parametrów.

**39 etapów wykonanych lokalnie; dwa niezamknięte: A43.40 i A43.41.**
A43.40 ma wdrożoną część mechanizmów, ale kod i polityka nadal są niekompletne.
A43.41 — końcowy odbiór — pozostaje do wykonania. Aktualny raport częściowy:
`tmp/audit-a43-40-revalidation/report.json`; 105 Java (89 PostgreSQL)
i 79 Python PASS; ostatni pełny drill 14 baz/Redis PASS z pakietu 117; w pakiecie 118 niepowtarzany. Gotowość NOT_READY / kod 2.
[Stan pełnego odbioru i pozostałe warunki](A43_ODBIOR_KONCOWY.md).

**Poprzedni odbiór A43.40, rewizja `.2`:** przeniesiono 18 decyzji do maszynowego
zapisu i walidatora; 15 testów bramki przeszło. Raporty bieżącego zakresu:
`tmp/audit-a43-40-revision2/observe.json` — PASS oraz
`tmp/audit-a43-40-revision2/readiness.json` — NOT_READY (kod 2).
W obu `activationAllowed=false`, `executedDataOperations=0`.
To odbiór macierzy/bramki, nie pełna regresja ani wdrożenie nowych workerów.

**Poprzedni odbiór `.3`:** mapa 10 tabel/127 pól D02 i kwalifikator w pamięci,
159 testów Java (w tym 12 nowych kwalifikatora) oraz 17 Python PASS.
D02-F01 zatwierdza redakcję trzech pól dostawy; zakres ma 20 pól redakcji,
91 minimum, 16 protokołu, zero nierozstrzygniętych pól tej mapy.
Raporty: `tmp/audit-a43-40-d02-assessment/report.json`, `observe.json`
i `readiness.json`. Nadal NOT_READY, runtimeWired=false i brak operacji na
rzeczywistych danych. [Zakres oraz pozostałe wykonanie](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Poprzedni odbiór `.4`:** trwały magazyn metadanych i ograniczony wykonawca
oceny D02, dwie nowe tabele/26 pól; pełny rejestr **87 tabel/775 pól**.
12 nowych i 10 regresyjnych testów PostgreSQL, 12 kwalifikatora Java i
54 Python PASS. Migrację V27 zastosowano wyłącznie w jednorazowych bazach.
Nie podłączono źródeł dowodów pozostałych usług ani workera biznesowego.
Raport: `tmp/audit-a43-40-d02-store/report.json`; gotowość nadal NOT_READY,
activationAllowed=false, bez operacji w rzeczywistych środowiskach.

**Poprzedni odbiór 31.10.2026 `.1`:** wyłączony domyślnie kanał unieważniania
z tożsamością siedmiu usług i klient transportowy; brak przyjmowania pozytywnych
dowodów. Izolowany restore unieważnia wszystkie odtworzone generacje D02,
także zdrowych kont i gości oraz przy pustym dzienniku. 43 Java, 62 Python
i pełny drill szyfrowanego backupu 14 baz/Redis PASS. Bez nowego schematu
(nadal 87 tabel/775 pól) i bez włączenia usług. Raport:
`tmp/audit-a43-40-invalidation-restore/report.json`. Nadal NOT_READY;
trwałe źródłowe outboxy i świeże dowody/wykonawcy pozostają do wdrożenia.

**Poprzedni odbiór 31.10.2026 `.2`:** pilot Returns zapisuje unieważnienie
atomowo ze zmianą zgłoszenia/pozycji/zdarzenia. Przygotowano binding z epoką,
ograniczone ponowienia nadawcy i blokadę źródła po izolowanym restore.
Nowe dwie tabele/25 pól: pełny rejestr **89 tabel/800 pól**.
Odbiór: 60 Java, 66 Python oraz pełny drill 14 baz/Redis PASS.
Raport: `tmp/audit-a43-40-source-outbox/report.json`.
Bez podłączenia koordynatora i automatycznej publikacji. Pozostałe sześć źródeł,
świeże potwierdzenia/blokady, workery biznesowe i dopuszczenie po restore
nadal wymagają wdrożenia i odbioru. Decyzje użytkownika pozostają bez zmian;
brak nowej aktywacji, A43.40/A43.41 nadal otwarte.

**Poprzedni odbiór 31.10.2026 `.3`:** trwałe zawiadomienia Inventory,
Promotion i Delivery oraz restore czterech źródeł. Nowe sześć tabel/75 pól;
rejestr **95 tabel/875 pól**. Raport: `tmp/audit-a43-40-sources-ipd/report.json`.
Odbiór: 60 Java, 68 Python oraz pełny drill 14 baz/Redis PASS.
Następne źródła: Payment, Billing, Notification. Koordynator/publikatory,
świeże dowody i workery biznesowe nadal do wdrożenia; decyzje bez zmian,
czyszczenie nieaktywne, A43.40/A43.41 nadal otwarte.

**Poprzedni odbiór 31.10.2026 `.4`:** zawiadomienia Payment, Billing,
Notification i restore siedmiu źródeł. Rejestr **101 tabel/950 pól**.
Odbiór: 103 Java, 68 Python i pełny drill 14 baz/Redis PASS.
Raport: `tmp/audit-a43-40-sources-pbn/report.json`. Ochrona D01, brak TTL
metadanych i brak aktywacji zachowane. Następny zakres: trwały koordynator
bindingów/publikatorów, zaufane świeże dowody, workery biznesowe i odbiór.
A43.40/A43.41 pozostają otwarte; nieznane powiązania/kopie do oceny.

**Poprzedni odbiór 31.10.2026 `.5`:** trwały rejestr siedmiu komend bindingu
i ograniczony wykonawca koordynacji; oczekiwane epoki z zachowanych
potwierdzeń, bez odczytu nowej epoki przy ponowieniu. Nierozliczone stare
skutki blokują nową generację; restore blokuje komendy także po BOUND.
Rejestr **102 tabele/969 pól**. Raport:
`tmp/audit-a43-40-binding-coordinator/report.json`.
Odbiór: 56 Java, 72 Python i pełny drill 14 baz/Redis PASS.
Transport HTTP i podłączenie publikatorów, pozytywne dowody i workery
biznesowe nadal do wdrożenia. Ochrona D01 i brak aktywacji bez zmian.

**Poprzedni odbiór 31.10.2026 `.6`:** uwierzytelniony HTTP bindingu i opt-in
publikatory Order/siedmiu źródeł, domyślnie wyłączone; poprawiona trasa
invalidate w filtrze. Kwestionowane/odtworzone rekordy pozostają chronione.
Schemat bez zmian. Raport: `tmp/audit-a43-40-http-publishers/report.json`.
Odbiór: 209 wywołań Java, 72 Python i pełny drill 14 baz/Redis PASS.
Dalej: zaufane dowody procesów/blokad/rejestru, przygotowanie generacji,
workery biznesowe i odbiór środowiskowy. D01, D17 i pozostałe decyzje bez zmian.

**Poprzednia aktualizacja 31.10.2026, rewizja `.7`:** pierwszy zaufany lokalny dowód
Order i źródłowy znacznik zamknięcia V29, bez historycznego backfillu i bez
podłączenia kolektora do runtime. Zmiany procesu unieważniają czas; restart,
zmiany kontaktu i ponowna generacja po samej wersji go nie przesuwają.
Pozytywny dowód obejmuje tylko ORDER; pozostałe siedem źródeł oraz globalne
blokady/rejestr nadal wymagają implementacji. Odtworzone znaczniki są
blokowane z zachowaniem dat, także przy pustym dzienniku.
Rejestr **103 tabele/976 pól, 173 migracje**, 47 mechanizmów.
Odbiór: **85 wywołań Java, 75 Python i pełny drill 14 baz/Redis PASS**.
Raport: `tmp/audit-a43-40-order-evidence/report.json`.
Gotowość NOT_READY / kod 2; A43.40/A43.41 otwarte, D01 i D17 bez zmian.
Brak aktywacji i czyszczenia rzeczywistych danych.

**Poprzednia aktualizacja 31.10.2026, rewizja `.8`:** lokalna obserwacja Inventory
z dokładną zgodnością ID/stanu i produktów/ilości oraz źródłowym zegarem V13.
Dwa nowe pola istniejącego rekordu, bez backfillu, TTL i podłączenia runtime.
Odczyt wymaga czystego bindingu w tej samej generacji/wersji/fazie/epoce/rewizji;
restore blokuje użycie czasu, zachowując jego wartość. Wynik nie jest jeszcze
ChainProof w Order i nie zawiera customerId. Rejestr **103 tabele/978 pól,
174 migracje**, 47 mechanizmów. Odbiór: **39 Java, 75 Python i pełny drill
14 baz/Redis PASS**. Raport:
`tmp/audit-a43-40-inventory-evidence/report.json`; gotowość NOT_READY / kod 2.
Dalej: zaufany odbiór Inventory w Order, dowody sześciu pozostałych źródeł,
globalnych blokad/rejestru i workery biznesowe. Bez aktywacji i zmian decyzji.

**Aktualizacja 31.10.2026, rewizja `.9`:** chroniony HTTP Inventory i
zaufany odbiór w Order z oczekiwaniami z własnej bazy, HTTP poza transakcją
oraz ponowną kontrolą właściciela/wersji/generacji/bindingu przed zapisem.
Flaga domyślnie false; brak automatycznego kolektora i czyszczenia. Dowód
jest migawką do oceny, nie barierą przed przyszłymi zapisami. Schemat bez
zmian: 103 tabele/978 pól, 174 migracje. Raport:
`tmp/audit-a43-40-inventory-bridge/report.json`.
Polecenie ukończenia A43 nie zmieniło decyzji: D01 nadal chronione,
D09/SAFE i P09 nadal do ustalenia. [Raport odbioru końcowego — NOT_READY](A43_ODBIOR_KONCOWY.md).

**Aktualizacja 31.10.2026, rewizja `.10`:** wspólne adaptery i chroniony
odbiór sześciu pozostałych źródeł. Trwałe zegary po końcu transakcji,
kanoniczny hash/liczba zdarzeń Payment powiązane z generacją i wymagane
w Billing/Notification. Brak automatycznego potwierdzania nieobecności,
historii bez dat, Stripe i skutków Returns COMPLETED. Flaga domyślnie false;
brak globalnych potwierdzeń blokad i workerów D02. Rejestr 104 tabele/
1000 pól, 181 migracji. Raport tmp/audit-a43-40-six-peer-evidence/report.json.
D01/D09/P09 i odłożone zakresy bez zmian; A43.40/A43.41 nadal otwarte.

## Odpowiedzi użytkownika — obowiązujący rejestr wyborów

**Uzupełnianie decyzji:** kolejne brakujące parametry ustalamy na bieżąco
w czacie. Asystent wskazuje ID i wybraną opcję oraz konkretny parametr,
pokazuje pytanie i zapisuje odpowiedź w tym rejestrze; użytkownik nie musi
ręcznie edytować dokumentu.

**Nowe ustalenie dotyczące prowadzenia prac:** podczas wdrażania danego zakresu
pokazać użytkownikowi okno z konkretnymi pytaniami i opcjami dla brakujących
parametrów. Nie pytać ponownie o już wybraną opcję; w pierwszej kolejności
ustalić brakujące fakty ze źródeł projektu lub wskazanego środowiska.
Pracę zależną od brakującej decyzji wykonać po odpowiedzi, a niezależną prowadzić
dalej. Brak odpowiedzi nie zastępuje decyzji. Nie zbierać kluczy ani tokenów
w pytaniach, dokumencie lub repozytorium.

| ID | Wybrana opcja / podana wartość | Co pozostaje przed wdrożeniem |
|---|---|---|
| D01 | **A**. Doprecyzowania w czacie: **sprawdzanie co 7 dni; przechowywanie przez 30 dni**. Dla każdej klasy (faktury, korekty, rejestry płatności i zwrotów) licznik zaczyna się od **końcowego zamknięcia i uzgodnienia całego powiązanego procesu, w tym zwrotów i reklamacji**. **Wyjątki i wymagane dłuższe okresy nie są jeszcze ustalone — do ich ustalenia te dane pozostają chronione przed usunięciem.** | Polityka D01 nadal niekompletna; planowanego progu 30 dni nie stosować do usuwania tych danych. Ustalić wyjątki/dłuższe okresy, cele i zatwierdzającego; przy wdrażaniu powiązać koniec procesu z konkretnymi stanami/zdarzeniami. Cykl sprawdzania jest odrębny od okresu zachowania. Zapis decyzji, bez aktywacji i bez zmiany odrębnych redakcji kopii technicznych. |
| D02 | **B**, próg **1 rok = dokładnie 365 dni**, również gdy obejmuje rok przestępny. **Zakres: cały łańcuch — zamówienia i pozycje, przesyłki, zwroty/reklamacje oraz powiązane rezerwacje magazynu i rezerwacje/wykorzystania kuponów. Stosować do wszystkich zakończonych i uzgodnionych zakupów, także klientów z aktywnym kontem. Skutek: usuwać pozostałe dane osobowe i swobodne treści, w tym datę/przedział dostawy i punkt odbioru (D02-F01); zachować ID, kwoty, ilości, statusy i powiązania. P02=A: minimum zachować przez 90 dni od faktycznego, potwierdzonego zakończenia redakcji; zaakceptowano blokady dla spraw/sporów, wymaganego zachowania i zależności D01/odtworzenia/ponowień/deduplikacji, z wymaganym dowodem ich zwolnienia i ręczną oceną niejasnych przypadków. Brak dodatkowych znanych wyjątków do weryfikacji przy wdrażaniu; chronione znaczniki i rejestr usunięć poza tym okresem. Kwalifikacja automatyczna wyłącznie przy potwierdzonym zakończeniu całego łańcucha i potwierdzonym braku blokad dalszego zachowania; przypadki niejednoznaczne do ręcznej oceny.** | Mapa 127 pól, kwalifikator oraz trwałe metadane oceny odebrane lokalnie. Podłączyć zaufane źródła dowodów i blokad, globalne unieważnianie/restore oraz wykonawców biznesowych. Sam brak informacji o blokadzie nie potwierdza jej braku. Ręczna ocena i dowody wymagają wskazanego procesu. D01 i deduplikacja pozostają chronione; 365 dni nie jest TTL całych rekordów ani zachowanego minimum. Dodatkowa retencja nie opóźnia wcześniej uzgodnionej obsługi potwierdzonych wniosków o usunięcie. Bez aktywacji. |
| D03 | **A** — docelowe **7 dni** dla kwalifikujących się zakończonych danych bezpieczeństwa SQL. | Decyzja o okresie przeniesiona do macierzy/walidatora od rewizji `.2`; zachować obecne zegary, ochronę aktywnych danych i bieżące włączenie mechanizmu. Nie wymaga ponownego wyboru 7 dni. |
| D04 | **A** — stream płatności: próg **7 dni**. **W polityce wymagane obie grupy: billing-service (faktury) i notification-service (powiadomienia)**. **Przegląd dev i test — każde środowisko sprawdzić i opisać osobno.** | Potwierdzić rzeczywistą nazwę streamu, wszystkie faktyczne grupy, postęp/pending i potrzeby ponowień osobno dla dev i test. Lista wymagana nie jest dowodem runtime. W dostępnym Dockerze broker projektu jest zatrzymany, więc grup/postępu/pending obu środowisk nie potwierdzono. Bez aktywacji trim. |
| D05 | **A** — planowany próg **30 dni**. **Dev i test sprawdzić i opisać osobno. Użytkownik nie planuje odbiorców invoice-events w obu środowiskach; brak odbiorców i sposób czyszczenia wymagają jeszcze weryfikacji.** | Potwierdzić rzeczywisty brak odbiorców/grup, zaległości i potrzeby ponowień osobno dla dev i test oraz ocenić cykl streamu bez konsumentów. Deklaracja zamierzonego użycia nie jest dowodem pustego streamu/braku grup i nie pozwala ominąć kontroli wymaganych grup. Broker projektu jest zatrzymany; runtime niepotwierdzony. Bez aktywacji trim i bez zmiany publishera. |
| D06 | **B** — warunek zakończenia zamiast samego wieku. **Wszystkie warunki łącznie: proces zakończony i uzgodniony, wymagane skutki trwale potwierdzone, brak aktywnych ponowień i otwartych DLQ, a usuwana treść nie jest potrzebna do odzyskania procesu. Brak dowodu blokuje czyszczenie; niejasne przypadki do ręcznej oceny.** | Podczas wdrażania przypisać kryteria, pola/stany i trwałe źródła dowodów do konkretnych klas; wskazać proces ręcznej oceny i odpowiedzialnego. Nie dodawać ogólnego TTL ani kasowania całych rekordów na podstawie samego wieku. Minimalne ID, deduplikacja i źródła replay mają odrębne zasady. Bez aktywacji. |
| D07 | **A** — polityka istniejących miejsc zapisu. **Dev i test: logi plikowe usług/frontendu uruchamianych z CC oraz logi kontenerów Docker; każdy magazyn zweryfikować osobno. Maksymalny okres: 30 dni od zapisu wpisu; rotacja, kompresja i restart nie zerują wieku. Budżet: 100 MiB na źródło, łącznie z rotacjami, osobno w danym środowisku. Po przekroczeniu limitu można wcześniej usuwać najstarsze logi.** | Kopie, wyjątki i opiekun oraz potwierdzenie backendów/driverów/odbiorników i wykonalności obu ograniczeń osobno dla środowisk nadal wymagane. 100 MiB nie jest łącznym budżetem całego projektu, a 30 dni nie jest gwarantowanym minimum historii. D10 i certyfikowane logi zadań CC mają osobne zasady. Bez aktywacji i bez zmiany dotychczasowych ustawień. |
| D08 | **A** — okresy według magazynu. **Obecny lokalny stos: Prometheus, Grafana i Alertmanager; pozostali odbiorcy i kopie do osobnego przeglądu. Metryki Prometheus: 30 dni bez skracania z powodu rozmiaru; próg alarmowy 1 GiB, przekroczenie wymaga alarmu i oceny obsługi. Grafana: okresowe usuwanie wyłącznie jawnie wycofanych dashboardów po 90 dniach od wycofania; zależności i blokady wstrzymują usunięcie. Alertmanager: natywne data.retention = 5 dni (120h), zgodnie z semantyką wyciszeń i rejestru wysyłek.** | Właściciele, wyjątki, dowody runtime i pozostałe odbiorniki/kopie nadal wymagane; przy wdrażaniu rozliczyć także pozostałe dane Grafany. 30 dni nie jest wspólnym TTL monitoringu, a 1 GiB nie uprawnia do wcześniejszego kasowania metryk. 5 dni Alertmanagera nie gwarantuje pełnego archiwum alertów i nie usuwa aktywnych wyciszeń po wieku utworzenia. Bez aktywacji i bez kasowania wolumenów. |
| D09 | **A** — interfejs plikowy. **Katalog nadrzędny: E:\Klucze; rejestry planowane w privacy-journal\dev i privacy-journal\test. Klucze: E:\Klucze\privacy-journal-keys\dev oraz E:\Klucze\privacy-journal-keys\test. Zachować E:\Klucze\Kopia jako kopię lokalną; dodatkowy nośnik/host ustalić później. Aktualne wskaźniki: osobne wpisy dev/test w niezależnym sejfie lub menedżerze haseł, pod kontrolą uprawnionego opiekuna. SAFE to nazwa sejfu/folderu; program/usługa — do ustalenia, zgodnie z odpowiedzią użytkownika.** | Konkretne narzędzie/lokalizacja i proces aktualizacji nadal otwarte. Odpowiedzialny, niezależna kopia, rozdzielenie od backupów, uprawnienia i operacje Linux/WSL nadal wymagane. Plan, bez tworzenia wpisów/kluczy, kopiowania lub konfiguracji. |
| D10 | **C — ODŁOŻONE**: legacy historia CC, stare logi i swobodne treści. | Nadal chronione i do ręcznej oceny; nie oznaczać usunięcia ani zakończenia wymaganej polityki. |
| D11 | **B** — zaplanować migrację/ponowne szyfrowanie. **Zakres: trwałe MFA, zaszyfrowane ustawienia AI CC, backupy i rejestr usunięć; osobny plan dla każdego obszaru i środowiska. Kierunek: wykorzystać poprawne istniejące aktywne klucze danego obszaru i środowiska; brakujące klucze zaplanować oddzielnie.** | Zweryfikować konkretne aktywne klucze i ich przypisania, zinwentaryzować szyfrogramy/kopie, przygotować kolejność, próbę odtworzenia i dowody wycofania wszystkich zależności przed usunięciem starego klucza. Wybór nie oznacza użycia jednego wspólnego klucza ani przepisywania wszystkich formatów jednakowo. Bez zmiany danych, kluczy i konfiguracji. |
| D12 | **A** — inwentaryzacja przed przypisaniem polityki. **E:\Klucze\Kopia2: deklarowane kopie baz i Redis (dumpy, RDB/AOF lub zestawy), dev i test opisać osobno, kopie wspólne oznaczyć jako obejmujące oba. Nie ustalać jeszcze okresu — najpierw rozpoznać i ocenić konkretne kopie. Według bieżącej wiedzy użytkownika to jedyna dodatkowa lokalizacja poza standardowymi backupami i E:\Klucze\Kopia.** | Katalog istnieje, metadane 30.10.2026: 0 plików/podkatalogów. Deklaracja wiedzy nie dowodzi braku innych/historycznych kopii. Właściciel, ocena rzeczywistych artefaktów, okres i dowody wygasania nadal wymagane. Bez odszyfrowywania/kasowania; nie przypisano 14 dni do lokalizacji ani aktywnego AOF. |
| D13 | **C — ODŁOŻONE**: pobrane pliki i stan urządzeń. | Jawne ograniczenie pozostaje; brak potwierdzenia usunięcia Downloads, synchronizacji i kopii urządzeń. |
| D14 | **Dev/test: P06=B — według wiedzy użytkownika tylko tryb wbudowany CC; konfiguracja i historia AI do weryfikacji. Oba SMTP — A, według wiedzy użytkownika tylko lokalny Mailpit, bez znanego przekazywania dalej. Mailpit: maksymalnie 30 dni od przyjęcia oraz 1000 wiadomości na instancję; limit liczby może wcześniej usuwać najstarsze wraz z załącznikami. Wcześniejsze A dla External API dotyczyło REST API samej usługi Notification, bez potwierdzenia zewnętrznej wysyłki.** | Wyjątki Mailpit, konfiguracja środowisk/historia, dalsze kopie i dowody nadal wymagane. Deklaracja o AI zastępuje wcześniejsze C, ale nie potwierdza braku osobnego endpointu lub historycznej wysyłki. Bieżący adapter external-api nie wysyła HTTP; nie dopisano dostawcy ani dowodu całej historii. A05 odłożone. Bez zmiany parametrów i bez aktywacji nowego czyszczenia. |
| D15 | **A** — rejestr E:\Klucze\Rejestr. **Czat: tylko zweryfikowane indywidualne wnioski, zakres po ręcznej ocenie. Dowody/załączniki: 90 dni od bezpiecznego zamknięcia sprawy. Minimalny zapis (ID, daty, wynik, bezpieczne kody): 365 dni od bezpiecznego zamknięcia, z ochroną wymaganych wyjątków. Role (P07): właściciel — operator-1; zastępca — operator-2, dla dev i test; oznaczenia organizacyjne do weryfikacji dostępu (D18).** | P08: dodatkową ocenę prowadzi wyłącznie właściciel (operator-1), bez wymogu drugiej osoby. Weryfikacja dostępu/mapowania ról, konkretne wyjątki, zapis ocen i wykonanie retencji nadal wymagane. Sprawy aktywne/sporne i blokady zachowania chronione; okresów nie przenosić na D09/deduplikację. Istnienie katalogu nie dowodzi gotowego procesu. Bez ogólnego purge czatu po wieku lub dopasowania po e-mailu. |
| D16 | **B — A05 nadal ODŁOŻONE**. | Obie zmienne Stripe w obu SOPS, sandbox i dowody operatora nadal do późniejszego ręcznego uzupełnienia; nie wznawiać A05. |
| D17 | **A** — najpierw odbiór lokalny, **bez aktywacji**. | Można przygotowywać kod, polityki i izolowane testy po uzupełnieniu zależnych decyzji. Zastosowanie w dev/test/prod pozostaje osobnym zleceniem. |
| D18 | **A** — jeden właściciel z zastępstwem. **Role (P07): właściciel — operator-1; zastępca — operator-2, dla dev i test, wspólnie z D15; oznaczenia organizacyjne do weryfikacji dostępu. Kanał: lokalne panele/statusy oraz raport/log, sprawdzane ręcznie, bez powiadomień poza aplikacją. Od pierwszego alarmu sprawa dotyczy jednocześnie obu ról.** | Przygotować i sprawdzić lokalną obsługę alarmów oraz możliwość zatrzymania konkretnego workera; powiązać role z rzeczywistymi uprawnionymi osobami/kontami. Równoczesne skierowanie sprawy jest regułą organizacyjną, nie dowodem automatycznego doręczenia lub potwierdzenia reakcji. Same oznaczenia nie nadają praw ani nie potwierdzają gotowego zastępstwa. |

**Zapis decyzji nie jest wdrożeniem w usługach:** rewizja
`a43.40-2026-10-31.15` w `retention-policy.json` i walidator zawierają aktualne
uzgodnienia, z maszynowym zapisem 18 pozycji w `retention-decisions.json`.
SQL 7 dni jest zatwierdzone; plany D02 i pozostałych obszarów mają osobne
blokady wykonania/dowodów. `.3` dodaje mapę i kwalifikator D02, a `.4`
trwałe metadane i wykonawcę oceny. Zaufane źródła potwierdzeń, unieważnianie
całego łańcucha i worker biznesowy nadal do wdrożenia. Izolowany restore
unieważnia już odtworzone oceny; nie jest to dopuszczenie środowiska do ruchu.
Nie zmieniono ustawień runtime.
Starsze wpisy rewizji `.1` opisują poprzedni odbiór; nie cofają nowych wyborów.
Nie wolno oznaczyć całej polityki jako gotowej tylko na podstawie listy decyzji.
D01/P09, D09, D10/D13/D16 i pozostałe brakujące dowody nadal ograniczają pełny odbiór.

## Co jest już uzgodnione

| Ustalenie | Obowiązująca decyzja |
|---|---|
| Profil techniczny | Rekomendacje 7 dni; certyfikowane logi zadań CC 14 dni; audyt 90 dni; kwalifikujące się treści zakończonych outboxów/inboxów, SENT i RESOLVED DLQ 30 dni; rozpoznane całe szyfrowane backupy 14 dni. Zatwierdzone **bez aktywacji**. |
| Znaczniki i deduplikacja | Bez automatycznego TTL do udokumentowanego wycofania wszystkich właściwych kopii i źródeł ponowień. Dotyczy też niezależnego rejestru. Są nadal pseudonimowe. |
| Eksport i status konta | Wynik eksportu dostępny 1 godzinę od utworzenia; osobny token statusu usunięcia ważny 7 dni. Czas dostępu nie oznacza TTL wszystkich zapisów procesu. |
| Czat | Osobny dowód konkretnej rozmowy, bez łączenia po e-mailu. Bez tokenu — ręczna ocena alternatywnego dowodu. Po fizycznym usunięciu rozmowy status przez uprawnioną obsługę, bez nowego sekretu i kopii hasha tokenu. |
| Asystent CC | Każda zewnętrzna tura wymaga podglądu i jednorazowego potwierdzenia. Nie oznacza to usunięcia danych u dostawcy. |
| Przeglądarka | Wylogowanie czyści dane konta i bufory. Koszyk gościa/czat są osobne; nieuzgodniony checkout i zamiary operatora zachowują ochronę przed duplikacją. |
| Aktualizacja wyborów D01–D18 | D03: docelowe SQL 7 dni zatwierdzone. D04/D05: wybrane 7/30 dni z nadal wymaganym potwierdzeniem odbiorców. D02: po 365 dniach redakcja pozostałych danych osobowych/swobodnej treści całego wskazanego łańcucha wszystkich zakończonych i uzgodnionych zakupów, także aktywnych kont; ID/kwoty/ilości/statusy/powiązania zachowane przez 90 dni od faktycznego, potwierdzonego zakończenia redakcji; zaakceptowano blokady dla spraw/sporów, wymaganego zachowania i zależności D01/odtworzenia/ponowień/deduplikacji, z wymaganym dowodem ich zwolnienia i ręczną oceną niejasnych przypadków. Brak dodatkowych znanych wyjątków do weryfikacji przy wdrażaniu; chronione znaczniki i rejestr usunięć poza tym okresem. D01: wybrane cykl 7 dni i planowany próg 30 dni od uzgodnionego końca procesu; dane chronione przed usunięciem do ustalenia wyjątków i wymaganych okresów. D10/D13/D16 odłożone. D14: P06=B — według wiedzy użytkownika tylko tryb wbudowany CC, konfiguracja i historia AI do weryfikacji; SMTP CC=A, SMTP Notification=A. Pozostali odbiorcy i dowody do ustalenia. Nowej aktywacji brak. |

Tych decyzji nie trzeba ponownie podejmować, jeśli użytkownik nie chce ich
zmienić. Zatwierdzone okresy obejmują dokładne pola i warunki adapterów opisane
w [polityce A43.40](A43_POLITYKA_RETENCJI_I_AKTYWACJA.md), nie całe bazy.
Przykładowo 30 dni dla treści SENT nie oznacza usunięcia odbiorcy ani deduplikacji.

## Zakres i znaczenie opcji D01–D18

Każdy punkt ma własne ID **D01–D18** — są to pozycje tej listy, nie nowe etapy
audytu. Dla D01/D02 podaje się osobne odpowiedzi dla różnych klas dokumentów,
jeżeli ich zasady się różnią. Opcja „odłożyć” zachowuje ochronę danych, ale
**nie pozwala odznaczyć brakującej polityki ani zamknąć pełnego A43**.

### D01 — faktury, korekty i rejestry rozliczeń

Dotyczy źródłowych dokumentów, niezbędnych danych rozliczeniowych oraz rejestrów
płatności i zwrotów finansowych. Wybrano sprawdzanie co 7 dni i przechowywanie
przez 30 dni od końcowego zamknięcia i uzgodnienia całego powiązanego procesu,
w tym zwrotów i reklamacji, dla każdej klasy danych. Nadal wymagane są cele,
wyjątki i zatwierdzający oraz wykonawcze mapowanie końca procesu.
Użytkownik nie ma jeszcze polityki wyjątków/dłuższych okresów i zdecydował,
że do ich ustalenia dane D01 pozostają chronione przed usunięciem. Próg 30 dni
jest zapisanym planem, nie dopuszczeniem kasowania źródłowych dokumentów
i rejestrów. Nie zmienia to osobnych uzgodnionych redakcji kopii technicznych.
Nie uruchomiono usuwania.

| Opcja | Krótki opis |
|---|---|
| A — osobna polityka dla każdej klasy | Użytkownik podaje okres i zdarzenie startowe dla faktur, korekt i odpowiednich rejestrów oraz wyjątki zachowania. Pozwala dopasować reguły do rzeczywistych potrzeb. |
| B — wspólna polityka dla wskazanego zakresu | Jeden okres i zegar tylko dla tych klas, dla których użytkownik potwierdzi wspólne zasady; pozostałe mają osobne wpisy. |
| C — odłożyć | Chronić dokumenty i pozostawić terminy nierozstrzygnięte. A43.40 pozostaje otwarty. |

Do wpisania: **klasa danych → cel → okres → od jakiego zdarzenia → wyjątki → osoba zatwierdzająca**.
Nie wyprowadzamy okresów dokumentów z technicznych 30 dni. Nie proponujemy
tu okresów ustawowych; użytkownik dostarcza właściwą politykę z odpowiednim wsparciem.

### D02 — zamówienia, przesyłki, zwroty/reklamacje i pozostałe dane handlowe

Obejmuje też rezerwacje magazynu, kupony i związane dane zakupowe. Odrębne
redakcje kontaktu z A43.35–36 już mają uzgodniony zakres. Użytkownik wybrał
wspólny próg czyszczenia pozostałych danych osobowych i swobodnych treści: dokładnie **365 dni**,
również w roku przestępnym. W czacie potwierdzono zakres: **cały łańcuch —
zamówienia i pozycje, przesyłki, zwroty/reklamacje oraz powiązane rezerwacje
magazynu i rezerwacje/wykorzystania kuponów**. Dokumenty finansowe D01
i chroniona deduplikacja są wyłączone. Użytkownik wybrał **usuwanie pozostałych
danych osobowych i swobodnych treści, przy zachowaniu ID, kwot, ilości,
statusów i powiązań**. Końcowy okres zachowania tego minimum będzie ustalony
osobno; nie otrzymuje ono automatycznie TTL 365 dni. Zachowane dane mogą
pozostać pseudonimowe — nie jest to deklaracja pełnej anonimizacji.
Reguła obejmuje **wszystkie zakończone i uzgodnione zakupy, także klientów
z aktywnym kontem**; nie jest ograniczona do wniosków o usunięcie konta.
Nie oznacza usuwania samego aktywnego konta i nie opóźnia wcześniej uzgodnionej
obsługi potwierdzonych wniosków o usunięcie.

**Sposób kwalifikacji wybrany w czacie:** automatycznie kwalifikować tylko
zakupy z potwierdzonym zakończeniem całego łańcucha i potwierdzonym brakiem
blokad dalszego zachowania; przypadki niejednoznaczne kierować do ręcznej
oceny. Aktywne, sporne i nieuzgodnione procesy pozostają chronione.
Brak dowodu zakończenia lub brak informacji o blokadach nie oznacza
potwierdzenia warunków czyszczenia. Wdrożenie musi wskazać konkretne
stany/zdarzenia i źródła dowodów oraz sposób odnotowania blokad i ręcznej
oceny; sama decyzja nie tworzy tych mechanizmów ani nie aktywuje redakcji.

| Opcja | Krótki opis |
|---|---|
| A — okresy według procesu | Osobny termin od uzgodnionego zamknięcia zamówienia, przesyłki, zwrotu/reklamacji itd.; aktywne i sporne procesy są wyłączone. |
| B — wspólny termin po końcowym uzgodnieniu | Jeden okres dla wskazanego łańcucha handlowego, liczony dopiero po zakończeniu wszystkich wskazanych procesów; dokumenty finansowe nadal podlegają D01. |
| C — odłożyć | Brak nowego kasowania po wieku, terminy pozostają otwarte. |

Potrzebne są dokładne stany oznaczające zamknięcie, wyjątki i wykonawcze
przypisanie pól do wybranego zakresu redakcji/zachowania. Minimalna deduplikacja nie dostaje
przy tej okazji nowego TTL.

### D03 — zakończone dane bezpieczeństwa SQL

Zamknięte rodziny refresh, wygasłe/użyte resety, użyte kody odzyskiwania,
nieaktywne TOTP i zbędne znaczniki weryfikacji. Aktywne metody są chronione.

| Opcja | Krótki opis |
|---|---|
| A — przyjąć obecne 7 dni jako docelowe | Zachować istniejące zegary GREATEST/COALESCE i warunki ochrony całych rodzin; zatwierdzić sam okres. |
| B — podać inne okresy | Użytkownik wskazuje okres dla każdej klasy i ewentualne wyjątki; potem walidujemy zgodność z kodem. |
| C — odłożyć | Docelowy okres nierozstrzygnięty; obecne 7d i włączenie workera bez zmian. |

### D04 — Redis: stream płatności

Źródła deklarują `payment-events`, próg 7 dni oraz grupy `billing-service`
i `notification-service`. Użytkownik w czacie zatwierdził **obie grupy jako
wymagane w polityce**: Billing obsługuje faktury, Notification powiadomienia.
Wybrany próg 7 dni i lista wymagana nadal potrzebują dowodów rzeczywistej
konfiguracji, postępu/pending oraz potrzeb ponowień we wskazanym środowisku.
Nie potwierdzono runtime ani braku dodatkowych grup i nie uruchomiono trim.
Wybrany w czacie zakres przeglądu: **dev i test, każde środowisko osobno**.

| Opcja | Krótki opis |
|---|---|
| A — 7 dni i potwierdzeni odbiorcy | Przyjąć próg po sprawdzeniu rzeczywistej nazwy streamu, wszystkich grup, ich postępu i pending oraz potrzeb ponowień. |
| B — inny próg i lista odbiorców | Podać okres oraz pełną listę odbiorców dla danego środowiska; sprawdzić zgodność z uzgodnieniem procesów i odtwarzaniem. |
| C — odłożyć | Docelowa polityka otwarta, bez zmiany obecnego działania. |

Okres nie wystarcza do trim. Brak odbiorcy/postępu lub nieuzgodnione pending
muszą być rozliczone. Nie wybiera się globalnego TTL Redis, FLUSH ani kasowania grup.

### D05 — Redis: stream faktur

Źródła deklarują `invoice-events` i próg 30 dni. Użytkownik wskazał w czacie:
**w dev i test nie planuje odbiorców tego streamu; brak odbiorców i sposób
czyszczenia wymagają jeszcze weryfikacji**. Jest to zamiar użycia, nie dowód
rzeczywistego braku grup, danych lub historycznych/zewnętrznych konsumentów.
Próg 30 dni pozostaje planem do zweryfikowania dla tego wariantu. Pusta lista
konfiguracji nie stanowi potwierdzenia braku odbiorców i nie pozwala ominąć
obecnej kontroli wymaganych grup. Potrzebna osobna ocena cyklu streamu bez
konsumentów; nie wyłączono publikowania i nie aktywowano trim.
Wybrany w czacie zakres przeglądu: **dev i test, każde środowisko osobno**.

| Opcja | Krótki opis |
|---|---|
| A — 30 dni po ustaleniu odbiorców | Wskazać i potwierdzić wszystkie wymagane grupy/odbiorców oraz warunki postępu i pending. |
| B — własna polityka streamu | Podać inny okres i odbiorców albo udokumentować rzeczywisty brak odbiorców oraz zamierzony cykl streamu; wymaga osobnej oceny, nie automatycznego pominięcia kontroli grup. |
| C — odłożyć | Polityka otwarta, bez nowej aktywacji trim i bez zmiany bieżących ustawień. |

#### D04/D05 — częściowy przegląd źródeł i dostępności, 30.10.2026

Sprawdzono publiczną konfigurację i metadane kontenerów w dostępnym Dockerze
WSL. Usługa projektu to `event-broker`; `compose.yaml` deklaruje Redis 7,
port hosta 16379 i trwały wolumen. Jedyny znaleziony kontener z etykietą usługi
Compose `event-broker`, `miniorderplatform-event-broker-1`, jest zatrzymany.
Nie potwierdzono jego przypisania do dev albo test; nie traktować go jako
dwóch osobno zweryfikowanych środowisk. Nie uruchamiano kontenera, nie
odczytywano treści wiadomości, konfiguracji SOPS ani sekretów.

| Środowisko | Co potwierdzono | Co pozostaje |
|---|---|---|
| dev | Wybrany osobny zakres przeglądu; publiczne deklaracje konfiguracji. | Faktyczna instancja/konfiguracja, nazwy streamów, grupy, postęp/pending i potrzeby ponowień niepotwierdzone. |
| test | Wybrany osobny zakres przeglądu; publiczne deklaracje konfiguracji, m.in. wyłączona retencja streamu faktur w `application-test.yaml`. | Faktyczna instancja/konfiguracja, nazwy streamów, grupy, postęp/pending i potrzeby ponowień niepotwierdzone. Wartość w źródle nie dowodzi działania runtime. |

Billing i Notification deklarują konsumentów `payment-events` oraz wymagane
grupy wybrane w D04. Dla `invoice-events` przegląd produkcyjnych źródeł Java
i konfiguracji znalazł publisher Billing oraz obsługę retencji/uzgodnienia,
lecz nie znalazł konsumenta tego streamu. Nie jest to dowód braku konsumentów
zewnętrznych ani historycznych grup w Redis. W późniejszej odpowiedzi D05
użytkownik wskazał brak planowanych odbiorców w dev i test. Potwierdzenie
rzeczywistego stanu i sposobu czyszczenia pozostaje otwarte.
Oba przeglądy runtime pozostają otwarte; brak dostępu do działającej instancji
nie stanowi potwierdzenia pustego streamu lub zerowego pending.

### D06 — pozostałe stany techniczne i kopie treści

Dotyczy pól/rekordów nieobjętych zatwierdzonym kompaktowaniem: pozostałych
stanów sag, kolejek, outbox/inbox, błędów i kopii komunikatów. Trzeba rozdzielić
zbędną treść od nadal potrzebnego stanu procesu i minimalnej deduplikacji.

**Uzgodniony w czacie warunek wycofania:** wszystkie poniższe warunki muszą
być spełnione łącznie: proces zakończony i uzgodniony, wymagane skutki trwale
potwierdzone, brak aktywnych ponowień i otwartych DLQ, a usuwana treść nie
jest już potrzebna do odzyskania procesu. Brak dowodu blokuje czyszczenie;
niejasne przypadki trafiają do ręcznej oceny. Minimalne ID i deduplikacja
zachowują odrębne zasady; brak ogólnego TTL. Wdrożenie musi wskazać konkretne
pola/stany i źródła dowodów dla każdej klasy, a nie tylko oznaczyć ją jako
„zakończoną”. Decyzja nie uruchamia czyszczenia ani nie tworzy nowych adapterów.

| Opcja | Krótki opis |
|---|---|
| A — końcowy okres dla zbędnych pól | Dla konkretnych pól podać czas od bezpiecznego zakończenia i uzgodnienia; ID i konieczne potwierdzenia zachować zgodnie z już przyjętą ochroną. |
| B — warunek zakończenia zamiast samego wieku | Podać udokumentowany warunek wycofania konkretnego stanu/kopii oraz sposób ręcznej oceny; nie traktować wszystkich pól jako minimalnego znacznika. |
| C — odłożyć | Chronić nieuzgodniony zakres; bez dopisywania przypadkowego TTL. |

Nie rozszerzać uzgodnionych 30 dni na całe rekordy ani otwarte DLQ.

### D07 — logi aplikacji poza certyfikowanymi logami zadań CC

**Wybrane w czacie: dev i test, logi plikowe usług/frontendu uruchamianych
z CC oraz logi kontenerów Docker; każdy magazyn zweryfikować osobno.
Maksymalny okres: 30 dni.** Stare nieocenione pliki D10 i certyfikowane logi
zadań CC pozostają przy osobnych zasadach. Ustalenie nie obejmuje automatycznie
monitoringu D08 ani nieznanych kopii.

**Kolejne odpowiedzi w czacie:** wiek liczyć od zapisu wpisu; rotacja,
kompresja i restart nie rozpoczynają okresu od nowa. **30 dni to maksimum,
a limit rozmiaru może wcześniej usuwać najstarsze logi**. Nie oznacza to
gwarantowanego zachowania pełnych 30 dni historii. Ustalenie nie rozszerza
zakresu na D10 ani inne chronione kopie.

**Limit rozmiaru wybrany w czacie: 100 MiB (104 857 600 bajtów) na jedno
źródło** — logi jednej usługi/frontendu uruchamianych z CC albo jednego
kontenera, łącznie z rotacjami, osobno w danym środowisku. To budżet per źródło,
nie wspólny limit wszystkich usług ani obu środowisk. Sposób podziału na
pliki/segmenty należy dopasować podczas wdrażania do tego budżetu.

Nadal potrzebne są kopie, wyjątki i opiekun oraz potwierdzenie driverów/collectorów
i docelowych odbiorników w każdym środowisku. Wdrożenie
musi zachować wiek wpisów przy rotacji/kompresji i bezpiecznie obsługiwać
aktywnych pisarzy. Sam limit rozmiaru nie egzekwuje okresu wieku, a wybór
polityki nie dowodzi obsługi obu ograniczeń przez obecny backend.

**Częściowe rozpoznanie 30.10.2026:** kod `scripts/control-center/lib/apps.sh`
zapisuje logi lokalnie uruchamianych usług i frontendu do
`RUNTIME_DIRECTORY/logs/`. Pliki Compose nie deklarują osobnego `logging`.
W sprawdzonym Dockerze domyślny driver to `json-file`; ten sam typ ma istniejący
kontener brokera projektu. To nie potwierdza driverów/limitów wszystkich usług,
odrębnej konfiguracji dev/test ani braku collectorów lub kopii. Nie odczytywano
treści logów i nie zmieniano ustawień demona/kontenerów. Certyfikowane logi
zadań CC mają odrębną, już uzgodnioną politykę.

| Opcja | Krótki opis |
|---|---|
| A — polityka istniejącego backendu | Wskazać rzeczywisty backend i okres od zapisu/rotacji, limit rozmiaru, kopie oraz zakres wymagający zachowania. |
| B — zmiana sposobu przechowywania | Zaplanować inny driver/backend i jego okresy; to dodatkowa konkretna zmiana do wdrożenia i sprawdzenia, nie gotowa funkcja tego dokumentu. |
| C — odłożyć | Zachować obecne działanie; brak dowodu konfiguracji nadal blokuje pełny odbiór tego zakresu. |

14 dni zatwierdzone dla certyfikowanych logów zadań CC nie rozciąga się
automatycznie na wszystkie logi aplikacji; wybór 30 dni dla D07 nie zmienia
odrębnych 14 dni certyfikowanych logów CC. Nie zmieniono ustawień ani nie
aktywowano czyszczenia.

### D08 — monitoring i jego wolumeny

Prometheus/Grafana/Alertmanager oraz dodatkowe odbiorniki mogą mieć różne
dane i okresy. Trzeba sprawdzić etykiety, dashboardy, alerty, kopie i snapshoty.

**Wybory w czacie:** teraz objąć ustalaniem polityki **obecny lokalny stos
projektu: Prometheus, Grafana i Alertmanager**, a pozostałe odbiorniki i kopie
przejrzeć osobno. **Docelowy okres przechowywania próbek metryk Prometheus:
30 dni.** Nie jest to wspólny TTL wszystkich wolumenów.

**Kolejne wybory w czacie:** nie skracać 30 dni metryk z powodu limitu miejsca;
przekroczenie budżetu ma wywołać alarm i ocenę obsługi. Budżet jest progiem
alarmowym, a nie zgodą na wcześniejsze usuwanie metryk. **Wybrany próg:
1 GiB (1 073 741 824 bajty) dla katalogu danych Prometheus**. To docelowa
polityka do przygotowania i odbioru, nie potwierdzenie
pojemności ani gwarancja obecnego środowiska.

Dla Grafany **przygotować okresowe usuwanie wyłącznie jawnie wycofanych
dashboardów po 90 dniach od potwierdzonego wycofania**. Nierozliczone
zależności lub blokady zachowania wstrzymują usunięcie. Nie kwalifikować dashboardu wyłącznie
na podstawie braku odczytów lub jego wieku. Nie jest to zgoda na usunięcie
bieżącej konfiguracji, wszystkich dashboardów, całego wolumenu ani kopii
i snapshotów. Stany Alertmanagera, kopie, właściciele, wyjątki i konfiguracja
runtime pozostają odrębne.

**Znaczenie retencji Alertmanagera — sprawdzenie źródeł wersji 0.28.1:**
parametr `--data.retention` ma domyślnie 120 godzin (5 dni). **W późniejszej
odpowiedzi użytkownik wybrał tę wartość jako docelową: 5 dni (120h).**
Nie jest to dowód ustawienia runtime ani aktywacja.
[Źródło parametru](https://github.com/prometheus/alertmanager/blob/v0.28.1/cmd/alertmanager/main.go).
Zakończone wyciszenia są kwalifikowane do usunięcia po okresie liczonym od ich
końca; nie oznacza to usuwania aktywnych wyciszeń po samym wieku utworzenia.
[Źródło wyciszeń](https://github.com/prometheus/alertmanager/blob/v0.28.1/silence/silence.go).
Rejestr wysyłek przechowuje ostatni stan dla pary grupa/odbiorca; jego termin
wygaśnięcia może być krótszy od `data.retention`, zgodnie z natywnym `expiry`.
To stan obsługi powiadomień, nie pełne archiwum wszystkich alertów.
[Źródło rejestru wysyłek](https://github.com/prometheus/alertmanager/blob/v0.28.1/nflog/nflog.go).
Przy wdrażaniu trzeba zachować zgodność retencji z powtarzaniem powiadomień
(źródłowy `repeat_interval: 4h`), ciągłość stanu i obsługę restartów. Nie resetować
wolumenu ani deduplikacji; nie rozciągać tej polityki na biznesowe znaczniki
deduplikacji z innych decyzji A43. Wybrane 5 dni stosować zgodnie z natywną
semantyką wyciszeń i rejestru wysyłek, a nie jako TTL wszystkich alertów lub
zgodę na usuwanie całego wolumenu. Konfiguracja i odbiór nadal do wykonania.

**Pomiar metadanych 30.10.2026:** `du -sk /prometheus` w lokalnym kontenerze
`mini-order-platform-prometheus` zwrócił 48 104 KiB, około 47 MiB zajętego
miejsca. Odczytano tylko łączny rozmiar, bez próbek metryk i zawartości plików.
To punktowy pomiar katalogu, nie prognoza zapotrzebowania na 30 dni ani dowód
konfiguracji retencji.

**Rozpoznanie publicznych źródeł 30.10.2026:** `docker-compose.monitoring.yaml`
deklaruje osobne wolumeny `prometheus_data`, `grafana_data` i `alertmanager_data`.
Polecenia Prometheus i Alertmanager wskazują konfigurację i ścieżki danych,
bez jawnych parametrów okresu retencji. Grafana ma provisioning dashboardów
i źródła Prometheus. Nie ustalono na tej podstawie rzeczywistych okresów,
konfiguracji runtime, dodatkowych odbiorników ani snapshotów; nie odczytywano
zawartości wolumenów. Wybór 30 dni/100 MiB w D07 dotyczy logów i nie rozciąga
się automatycznie na metryki, konfiguracje dashboardów lub stan alertów.
30 dni dla metryk Prometheus wybrano osobną odpowiedzią D08; nie aktywowano
tej polityki i nie zmieniono poleceń uruchomienia ani wolumenów.

| Opcja | Krótki opis |
|---|---|
| A — okresy według magazynu | Osobny zakres i okres dla metryk, dashboardów, alertów i ich kopii, z ustalonym właścicielem. |
| B — ograniczyć zbierany zakres | Zmniejszyć zakres danych/etykiet i ustalić okresy pozostałych; wymaga przeglądu i ewentualnej zmiany konfiguracji. |
| C — odłożyć | Nie zmieniać wolumenów; końcowe zasady pozostają nierozstrzygnięte. |

Nie wybiera się usunięcia całego wolumenu jako domyślnej obsługi wniosku jednej osoby.

### D09 — miejsce rejestru usunięć i piecza kluczy/wskaźnika

Sam model jest wybrany: niezależny szyfrowany i podpisany rejestr poza
backupami. **Użytkownik podał lokalizację nadrzędną `E:\Klucze`.** W planie
technicznym wydzielono osobne rejestry:

- dev: `E:\Klucze\privacy-journal\dev`;
- test: `E:\Klucze\privacy-journal\test`.

To zaplanowany podział wskazanego katalogu, nie informacja o istniejących
rejestrach lub ich inicjalizacji. **Użytkownik wybrał w czacie oddzielne
katalogi kluczy szyfrowania i podpisu rejestru:**

- dev: `E:\Klucze\privacy-journal-keys\dev`;
- test: `E:\Klucze\privacy-journal-keys\test`.

Klucze pozostają poza katalogami rejestrów i mają być poza zestawami backupów
aplikacji; rzeczywiste rozdzielenie, uprawnienia i konfiguracja wymagają odbioru.
Nie wskazano zawartości kluczy ani nie zdecydowano o ponownym użyciu istniejących
kluczy aplikacji. Nie tworzono katalogów ani kluczy.

**Lokalizacja kopii podana w kolejnej odpowiedzi: `E:\Klucze\Kopia`.**
Planowany podział kopii rejestrów: `E:\Klucze\Kopia\privacy-journal\dev`
i `E:\Klucze\Kopia\privacy-journal\test`, bez dołączania prywatnych kluczy.
`Test-Path` potwierdził istnienie wskazanego katalogu nadrzędnego kopii;
metadane `E:\Klucze` i `E:\Klucze\Kopia` wskazują zwykłe katalogi, bez
oznaczenia dowiązania/punktu ponownej analizy. Nie wyliczano zawartości.

Ta lokalizacja pozostaje na E, podobnie jak planowane rejestry i klucze.
Nie traktować jej jako potwierdzonej niezależnej kopii na wypadek utraty E.
**Kolejna decyzja użytkownika: zachować `E:\Klucze\Kopia` jako kopię lokalną;
miejsce dodatkowej kopii na innym nośniku lub hoście ustalić później.**
Niezależna kopia na wypadek utraty E pozostaje otwartym wymaganiem, a nie
wykonaną pozycją. Nie potwierdzono niezależności przez samo wskazanie innego
folderu. Konkretny zasób pieczy aktualnego wskaźnika, proces aktualizacji
i opiekun także nadal wymagają ustaleń; wybrany model opisano poniżej.
Nie kopiowano danych/kluczy i nie zmieniano istniejącej zawartości.

**Ograniczone sprawdzenie 30.10.2026:** `Test-Path` potwierdził istnienie
`E:\Klucze` oraz brak podkatalogu `E:\Klucze\privacy-journal`.
Nie wyliczano zawartości, nie odczytywano kluczy i nie utworzono katalogów.
Przed konfiguracją potrzebne są potwierdzenie rozdzielenia od rzeczywistych
backupów, mapowanie ścieżek do Linux/WSL, uprawnienia i próba wymaganych
operacji plikowych. Samo istnienie katalogu nie dowodzi tych warunków.

| Opcja | Krótki opis |
|---|---|
| A — skonfigurować obecny interfejs plikowy | Wskazać chroniony zasób/katalog poza zestawami backupów, osobne miejsca kluczy, niezależną kopię i pieczę aktualnego wskaźnika oraz osobę odpowiedzialną. Sprawdzić wymagane operacje plikowe w tym środowisku. |
| B — inny magazyn lub usługa | Opisać docelowe miejsce i wymagania; jeśli nie jest zgodne z istniejącym interfejsem, potrzebna jest osobna integracja i próba. |
| C — odłożyć | Bez potwierdzonego aktualnego rejestru destrukcyjne kroki/odbiór restore pozostają zablokowane. |

Do ustawienia m.in. `PRIVACY_RESTORE_JOURNAL_PUBLIC_KEY_BASE64`,
`PRIVACY_RESTORE_JOURNAL_LEDGER_ID` oraz ścieżki `PRIVACY_JOURNAL_*` narzędzia.
Prywatne klucze pozostają poza dokumentem i repozytorium. Wskaźnik
`ledgerUUID:sequence:entryHash` musi mieć niezależną, aktualizowaną pieczę;
kopia rejestru i wskaźnika wyłącznie w odtwarzanym backupie nie wystarcza.
Wskaźnik identyfikuje aktualną wersję rejestru i ma umożliwić wykrycie
podstawienia starszej, nadal poprawnie podpisanej wersji. Potrzebne są osobna
piecza dla dev/test i aktualizowanie wraz z zatwierdzanymi zmianami rejestru;
samo wskazanie miejsca nie dowodzi świeżości ani spójności tego procesu.
Brak potwierdzonego aktualnego wskaźnika nie może być zastąpiony kopią głowy
odczytaną wyłącznie z właśnie odtwarzanego rejestru/backupu.

**Model pieczy wskaźnika wybrany w czacie:** osobne wpisy dev/test
w niezależnym sejfie lub menedżerze haseł, pod kontrolą uprawnionego opiekuna.
**Użytkownik podał `SAFE`, a następnie doprecyzował: jest to nazwa sejfu
lub folderu; nazwa programu/usługi nie została jeszcze podana.**
Nie przypisano na tej podstawie producenta ani konkretnego konta.
**W kolejnym pytaniu o program/usługę użytkownik odpowiedział „do ustalenia”.**
Nie wybierać narzędzia na podstawie nazwy SAFE i nie uznawać tej pozycji za
wykonaną. Program/usługa, konkretna lokalizacja i wykonawczy proces
aktualizacji pozostają otwarte; można przejść do niezależnych decyzji.
Odpowiedzialną
rolę i zastępstwo należy powiązać z D18, bez ponownego pytania o już wybrany
model. Nie utworzono wpisów, nie połączono się z sejfem i nie pobierano jego
zawartości. Wybrany model nie dowodzi aktualności wskaźnika ani gotowości restore.
Szczegóły: [A43.37](A43_BACKUPY_I_REJESTR_USUNIEC.md).

### D10 — legacy historia CC, stare logi i swobodne treści

Stara historia wysyłek i niejednoznaczne treści pozostały do ręcznej oceny.
Nowa historia jest ograniczona do metadanych, ale nie przepisała starych kopii.

| Opcja | Krótki opis |
|---|---|
| A — ocena i redakcja wskazanego zakresu | Wybrać zasoby, opiekuna, zasady zachowania i termin; usuwać lub redagować dopiero po ocenie i sprawdzeniu kopii. |
| B — kontrolowana migracja minimum | Przenieść potrzebne metadane do uzgodnionego formatu i rozliczyć oryginały/kopie; sama migracja nie dowodzi ich usunięcia. |
| C — odłożyć | Stare pliki pozostają chronione i oznaczone do oceny, bez automatycznego kasowania. |

### D11 — wycofywanie kluczy i starszych szyfrogramów

Dotyczy keyringów usług, trwałego MFA, ustawień AI i kluczy backupu/rejestru.
Nie wystarczy określić wieku klucza: trzeba ustalić, co nadal nim odszyfrowujemy.

**Zakres wybrany w czacie:** wszystkie rozpoznane obszary — trwałe MFA,
zaszyfrowane ustawienia AI CC, backupy i rejestr usunięć. Przygotować **osobny
plan dla każdego obszaru i środowiska**. Wybór obejmuje planowanie, nie
uruchomienie migracji ani usunięcie kluczy.

**Kierunek kluczy docelowych wybrany w kolejnej odpowiedzi:** wykorzystać
poprawne istniejące aktywne klucze danego obszaru i środowiska; brakujące
klucze zaplanować oddzielnie. Nie wybrano wymiany wszystkich kluczy na nowe
ani jednego wspólnego klucza dla obszarów/środowisk. Konkretne klucze,
identyfikatory i przypisania wymagają bezpiecznej weryfikacji podczas
przygotowania planów; sam wybór nie potwierdza stanu konfiguracji runtime.

Każdy plan wymaga inwentaryzacji szyfrogramów i zależnych kopii, weryfikacji
kluczy docelowych, kolejności i dowodu odtworzenia. Nie zakładać możliwości
jednakowego przepisywania MFA, plików SOPS, backupów i podpisanego rejestru.
W szczególności plan musi zachować integralność manifestów, podpisów,
łańcucha rejestru i niezależnego wskaźnika; nie nadpisywać historycznych kopii
lub wpisów na podstawie samego ogólnego wyboru. Stare klucze pozostają
potrzebne do czasu udokumentowanego rozliczenia wszystkich zależnych
szyfrogramów i kopii; sam wiek nie uprawnia do ich usunięcia.

| Opcja | Krótki opis |
|---|---|
| A — zachować klucz do wycofania zależności | Zinwentaryzować szyfrogramy i kopie, potwierdzić ich migrację/wycofanie, a dopiero potem ocenić usunięcie starego klucza. |
| B — zaplanować migrację/ponowne szyfrowanie | Wskazać zakres, docelowy keyring i próbę odtworzenia; to konkretna praca do wykonania, nie już wdrożony automatyzm. |
| C — odłożyć | Bez automatycznego usuwania kluczy; zasady i dowody wycofania pozostają otwarte. |

### D12 — nieujęte snapshoty i historyczne kopie Redis/urządzeń

14 dni dotyczy rozpoznanych zestawów/dumpów. Nie obejmuje automatycznie
nieznanych snapshotów wolumenów i surowych historycznych AOF.

**Lokalizacja wskazana przez użytkownika w czacie: `E:\Klucze\Kopia2`.**
Przegląd ma dotyczyć metadanych wskazanego zakresu, bez odszyfrowywania,
odczytu treści i kasowania. **W kolejnej odpowiedzi użytkownik zadeklarował,
że według jego bieżącej wiedzy to jedyna dodatkowa lokalizacja poza standardowymi
backupami projektu i `E:\Klucze\Kopia`.** To deklaracja wiedzy, nie wynik
pełnego przeglądu środowiska ani dowód braku innych kopii.

**Wstępny przegląd 30.10.2026:** katalog istnieje i nie jest punktem ponownej
analizy. Ograniczony odczyt pierwszego poziomu (budżet 200 wpisów) wykazał
0 plików, 0 katalogów, 0 dowiązań i 0 bajtów plików; wynik nie był obcięty.
Nie odczytywano treści ani nie skanowano innych lokalizacji. To stan katalogu
w chwili obserwacji, nie dowód braku historycznych kopii, kopii poza nim ani
braku innych snapshotów. Nie przypisano typu na podstawie samej nazwy Kopia2.

**Przeznaczenie wybrane w kolejnych odpowiedziach:** kopie baz danych
i Redis — dumpy, RDB/AOF lub pełne zestawy backupów. **Środowiska: dev
i test, opisać osobno; kopie wspólne oznaczyć jako obejmujące oba**.
Jest to deklarowany zakres miejsca, nie potwierdzenie istnienia lub poprawności
konkretnych artefaktów. Pusty katalog nie pozwala zweryfikować formatów,
integralności, zegarów retencji ani możliwości odtworzenia.

**Decyzja o okresie:** nie ustalać jeszcze retencji dla tej lokalizacji;
najpierw rozpoznać i ocenić konkretne kopie. Nie wybrano zastosowania 14 dni,
nawet warunkowo dla przyszłych kopii tego miejsca. Dotychczasowa polityka
wcześniej rozpoznanych zasobów pozostaje bez zmian. Właściciel, rzeczywiste
artefakty, ich ocena, okres i dowody wygasania nadal wymagają uzupełnienia.
Nie uznano całej inwentaryzacji za zakończoną i nie uruchomiono kasowania.

| Opcja | Krótki opis |
|---|---|
| A — zinwentaryzować i dopiero przypisać politykę | Podać lokalizację, właściciela, zakres, okres i dowód wygasania każdej dodatkowej klasy kopii; ocenić, czy można zastosować już przyjęte 14 dni. |
| B — własna polityka dla dodatkowych kopii | Ustalić osobne okresy, wyjątki i procedurę konwersji/odtworzenia, gdy format nie jest obsługiwany. |
| C — potwierdzić brak takiej klasy kopii | Dopuszczalne tylko na podstawie przeglądu konkretnego środowiska; brak wpisu w repozytorium nie jest dowodem. |

Aktywnego AOF/Redis nie usuwa się jako starego backupu. Nieznana kopia bez
oceny pozostaje otwartą pozycją, także gdy żadna opcja nie zostanie wybrana.

### D13 — pobrane pliki i stan urządzeń

Downloads, załączniki, synchronizacja, kopie urządzenia i lokalne zapisy
odzyskania mają odrębny cykl. Serwer nie może potwierdzić ich zdalnego usunięcia.

| Opcja | Krótki opis |
|---|---|
| A — procedura właściciela urządzenia | Właściciel ustala okres i ręcznie rozlicza wskazane pliki/kopie; wynik odróżnia deklarację od zweryfikowanego zakresu. |
| B — procedura administratora urządzeń | Wskazać administratora, zarządzane urządzenia, zakres polityki i dowody wykonania; potrzebne narzędzia nie są tu automatycznie wdrażane. |
| C — odłożyć | Pozostawić jawne ograniczenie i otwartą politykę, bez obietnicy usunięcia wszystkich kopii. |

Nie zmienia to uzgodnionego zachowania wylogowania. Nie usuwać nieuzgodnionego
checkoutu lub zamiaru korekty po samym wieku; najpierw rozliczyć operację.

### D14 — rzeczywiści dostawcy i odbiorniki danych

**Aktualizacja P06, 30.10.2026:** użytkownik wybrał **B — według jego wiedzy
tylko tryb wbudowany CC; konfigurację i historię trzeba zweryfikować**.
To zastępuje wcześniejsze C dla deklaracji użycia AI w dev/test. Nie wskazano
różnic między środowiskami; weryfikację wykonać dla każdego osobno.
Nie jest to dowód nieużywania osobnego endpointu ani kompletności historii.
Wcześniejsze wpisy C poniżej opisują poprzedni stan odpowiedzi.

Osobno: AI i ewentualne proxy, SMTP CC, SMTP Notification, Notification External
API, płatności, końcowe skrzynki i ich kopie. Potrzebne są konta/środowiska,
zakres, warunki retencji i dowody obsługi wniosków lub braku użycia.

**Wybory przekazane w czacie:**

| Odbiornik | Zakres pytania | Wybrana opcja | Stan do uzupełnienia |
|---|---|---|---|
| Osobny endpoint AI asystenta CC | dev/test, dostawca zewnętrzny lub lokalny LLM | **C — do ustalenia** | Nie potwierdzono użycia ani braku użycia. Proxy i dalsi odbiorcy także wymagają rozliczenia po identyfikacji konfiguracji. |
| SMTP Control Center | dev/test | **A; według wiedzy użytkownika wyłącznie lokalny Mailpit; zweryfikować dev/test oraz historię** | Deklaracja nie dowodzi konfiguracji całej historii, braku przekazywania lub innych kopii. Retencja, opiekun i dowody nadal wymagane. |
| SMTP Notification | dev/test | **A; według wiedzy użytkownika wyłącznie lokalny Mailpit; zweryfikować dev/test oraz historię** | Rozliczać osobno od SMTP CC; to samo deklarowane narzędzie nie dowodzi wspólnej instancji lub identycznej konfiguracji. |
| Notification External API | dev/test | **Wyjaśnienie wcześniejszego A: chodziło o REST API samej usługi Notification; użytkownik nie potwierdza wysyłki do zewnętrznego dostawcy** | Bieżący adapter nie wysyła HTTP i zwraca błąd o planowanej integracji. Nie przypisano rzeczywistego dostawcy zewnętrznego; nie uznano wyjaśnienia za pełny dowód braku historycznych kopii poza aplikacją. |

Odpowiedzi dotyczą zakresu dev/test i nie dowodzą identycznego użycia w obu
środowiskach. **A nie oznacza już uzyskanych dowodów**, a C dla AI nie oznacza
wyłączenia istniejącego adaptera. Pozostali odbiorcy nadal wymagają osobnych
wyborów; zależność Stripe/A05 pozostaje odłożona zgodnie z D16=B.

**Dalsi odbiorcy Mailpit — odpowiedź użytkownika:** według jego wiedzy
wiadomości pozostawały wyłącznie lokalnie; brak znanego ręcznego lub
automatycznego przekazywania do innych skrzynek. To deklaracja wiedzy,
nie dowód całej konfiguracji/historii. Nie rozstrzyga pobranych plików ani
innych kopii urządzeń; zakres D13 pozostaje odłożony.

**Wskazówki z publicznych źródeł:** bieżący kod SMTP CC w `cc_data.py`
wskazuje `127.0.0.1:11025` jako Mailpit. Compose Notification deklaruje
`MAIL_HOST: mailpit` i port 1025. To identyfikacja źródłowych ustawień,
nie dowód rzeczywistego/historycznego odbiornika lub konfiguracji każdego
środowiska. Nie odczytywano SOPS, treści poczty ani sekretów i nie wysyłano wiadomości.

**Dalsze sprawdzenie źródeł 30.10.2026:**
`ExternalApiNotificationDeliveryService.deliver(...)` po walidacji treści
zwraca wynik niepowodzenia z informacją o planowanej integracji; nie wykonuje
wywołania HTTP. `NOTIFICATION_EXTERNAL_API_URL` jest deklarowane w profilu prod,
ale samo ustawienie nie dowodzi działającej wysyłki. Użytkownik następnie
wyjaśnił, że jego A dotyczyło REST API samej usługi Notification i nie potwierdza
wysyłki do zewnętrznego dostawcy. Nie przypisano potwierdzonego użycia
zewnętrznego API ani nie zamieniono deklaracji na dowód całej historii.
Nie wdrażać nowej integracji na podstawie tej odpowiedzi i nie utożsamiać
REST API Notification z przekazywaniem danych do dostawcy powiadomień.

**Dostępność Mailpit:** przegląd metadanych kontenera znanego projektu znalazł
`miniorderplatform-mailpit-1` w stanie zatrzymanym. Nie odczytywano wiadomości,
nie uruchamiano kontenera i nie potwierdzono odrębnych instancji dev/test
ani historii przekazywania/eksportu. Te dane nadal wymagają rozliczenia.

**Retencja Mailpit — rozpoznanie możliwości i wybory użytkownika:** oficjalna
dokumentacja opisuje osobne ograniczenia wieku (`MP_MAX_AGE`) i liczby
wiadomości (`MP_MAX_MESSAGES`), które mogą działać łącznie. Dokumentacja podaje
domyślnie 500 wiadomości, ale użytkownik osobno wybrał **1000 na instancję**;
nie potwierdza to konfiguracji zatrzymanego kontenera. Przed wdrożeniem zweryfikować
wersję obrazu i faktyczne parametry; dokumentacja bieżącego wydania może
różnić się od starszej wersji. Obsługa wyjątków i dowody konfiguracji nadal
do uzupełnienia. Polityki D07/D08 nie rozciągają się automatycznie na pocztę.
[Mailpit — opcje uruchomienia](https://mailpit.axllent.org/docs/configuration/runtime-options/),
[Mailpit — usuwanie wiadomości](https://mailpit.axllent.org/docs/usage/deleting-messages/).

**W kolejnych odpowiedziach wybrano dla dev/test:** przechwycone wiadomości
i załączniki Mailpit przechowywać **maksymalnie 30 dni od przyjęcia wiadomości**.
Limit liczby może wcześniej usuwać najstarsze wiadomości wraz z załącznikami;
nie gwarantuje się pełnych 30 dni historii. **Limit wybrany w kolejnej odpowiedzi:
1000 wiadomości na instancję.** Jeśli dev i test współdzielą instancję,
limit jest wspólny; przy odrębnych instancjach obowiązuje każdą osobno.
Nie jest to osobny limit dla SMTP CC i SMTP Notification trafiających do tej
samej instancji. Kopie pocztowe nie zastępują źródłowych faktur lub
rejestrów D01 i nie zmieniają ich ochrony. Pozostałe kopie i wyjątki mają
odrębny zakres; sam limit liczby nie stanowi budżetu rozmiaru bazy.
Nie zmieniono istniejących parametrów Mailpit ani nie aktywowano nowej retencji.

| Opcja | Krótki opis |
|---|---|
| A — używany odbiornik: zebrać dowody | Wskazać usługę/konto, odpowiedzialnego, rzeczywistą konfigurację i zasady oraz sposób uzyskania wyniku konkretnego żądania. |
| B — nieużywany odbiornik: potwierdzić brak | Udokumentować brak użycia w określonym środowisku i ocenić ewentualne historyczne kopie; wyłączenie dzisiaj nie usuwa wcześniejszych danych. |
| C — odłożyć | Status REQUIRED_MANUAL_EVIDENCE pozostaje blokadą pełnego odbioru wymaganego zakresu. |

Wyboru dokonuje się **dla każdego odbiornika**, nie jednym oświadczeniem dla
wszystkich. Procedura A43.39 jest gotowa, ale nie zastępuje odpowiedzi dostawcy.
Dokument nie upoważnia do kontaktowania dostawców ani wysyłania im danych.

#### D14 — dokładne wyjaśnienie

**D14 odpowiada na pytanie: co dzieje się z kopią danych po przekazaniu jej
poza komponent aplikacji, który tę kopię utworzył?** Usunięcie rekordu z naszej
bazy nie usuwa automatycznie wiadomości w skrzynce, załącznika u operatora
poczty, danych przekazanych do AI ani rejestru transakcji u operatora płatności.
Odbiornik może być publicznym dostawcą albo lokalnym Mailpit/serwerem/proxy.
Liczy się faktyczny przepływ i magazynowanie, a nie sama nazwa technologii.

Przykład: aplikacja wysyła powiadomienie z PDF przez SMTP. Oddzielne kopie mogą
istnieć w Notification, kolejce/przekaźniku SMTP, skrzynce końcowej i jej
backupach. Wyczyszczenie treści w Notification potwierdza tylko zakres lokalny.
Usunięcie rozmowy z widoku CC również nie cofa wcześniejszej tury wysłanej do AI.

**Najpierw powstaje lista rzeczywistych odbiorników — osobno dla dev, test
i prod, jeżeli te środowiska są używane.** Obecność adaptera w kodzie oznacza
możliwość użycia; nie dowodzi wysłania danych. W tej aktualizacji nie odczytywano
konfiguracji runtime, sekretów ani kont dostawców.

| Odbiornik do rozliczenia | Co może do niego trafiać | Co trzeba ustalić |
|---|---|---|
| Dostawca/model AI asystenta CC | Wiadomość, dołączona historia i dane aplikacji, które operator umieścił w zatwierdzonej turze. | Faktyczny endpoint/konto, środowisko, zakres przechowywania treści/logów, dalsze wykorzystanie danych oraz dostępna procedura eksportu/usunięcia. Tryb wbudowany lokalny trzeba odróżnić od osobnego endpointu. |
| Proxy/bramka przed AI, jeśli występuje | Przekazywane żądania/odpowiedzi i ewentualne logi. | Czy działa i zapisuje dane; kto je kontroluje, okres i kopie. Polityka końcowego modelu nie rozlicza automatycznie proxy. |
| SMTP używane przez CC | Odbiorca, temat, treść i wysyłane dokumenty/załączniki. | Rzeczywisty przekaźnik, jego konto/kolejki/logi, zasady zachowania i obsługa żądania. |
| SMTP używane przez Notification | Powiadomienia konta/zamówienia/czatu, odbiorcy i ewentualne załączniki. | Osobna weryfikacja konfiguracji; nie zakładać, że to ten sam serwer i polityka co w CC. |
| Notification External API, jeśli używane | Dane przekazane konkretnemu adapterowi zewnętrznej wysyłki. | Dostawca/konto, zakres faktycznych wywołań, przechowywanie treści i możliwość wykonania wniosku. Samo istnienie adaptera nie oznacza użycia. |
| Stripe/operator płatności | Dane faktycznie przekazane przez integrację i identyfikatory/rejestry transakcji. | Rozróżnić sandbox i rzeczywiste konto; ustalić zakres, zasady zachowania, ograniczenia i dowody operatora. **D16=B: A05 pozostaje odłożone.** |
| Skrzynki końcowe, kopie wysłane i ich backupy | Doręczone wiadomości i dokumenty. | Które skrzynki kontroluje sklep, a które odbiorca; dostępna procedura i jej granice. Brak kontroli nad skrzynką klienta pozostaje jawnym ograniczeniem. |
| Lokalny Mailpit lub inny lokalny odbiornik, jeśli używany | Przechwycone wiadomości, treści i załączniki. | Faktyczny magazyn/wolumen, okres, kopie i osoba odpowiedzialna. „Lokalny” nie oznacza, że kopii nie ma. |

**Opcję wybiera się dla każdego odbiornika, a nie dla całego D14 jednym razem:**

- **A — używany:** wskazać niepoufny identyfikator usługi/konta i środowisko,
  odpowiedzialnego, przekazywane kategorie danych, zasady przechowywania
  (okres + początek liczenia + wyjątki), zakres kopii oraz procedurę obsługi
  wniosku. Potwierdzenie ustawienia retencji opisuje politykę; dowód wykonania
  konkretnego żądania wymaga odrębnego wyniku dla właściwego zakresu.
- **B — nieużywany:** potwierdzić brak użycia we wskazanym środowisku i okresie.
  Sprawdzić historię: „wyłączony od dziś” nie znaczy „nigdy nie otrzymał danych”.
  Jeżeli kiedyś był używany, stare kopie wymagają osobnego rozliczenia mimo B.
- **C — odłożony/nieustalony:** niczego nie uznawać za usunięte. Pozostaje
  brak dowodów dla tego odbiornika i ograniczenie pełnego odbioru A43.

Minimalny zapis odpowiedzi, bez sekretów:

```text
Odbiornik: [AI / SMTP CC / SMTP Notification / External API / Stripe / inne]
Środowisko i zakres czasu: [...]
Wybór: A / B / C
Nazwa usługi, niepoufny identyfikator konfiguracji, odpowiedzialna rola: [...]
Przekazywane dane i znane kopie: [...]
Okres, zdarzenie startowe i wyjątki albo dowód braku użycia: [...]
Procedura wniosku i kontrolowane miejsce dowodu: [...]
Ograniczenia lub historyczne kopie do rozliczenia: [...]
```

**Gdzie będzie potrzebna odpowiedź użytkownika podczas wdrażania:** najpierw
potwierdzenie docelowego środowiska i wyboru A/B/C dla rzeczywistych odbiorników;
potem brakujące ustawienia/okresy, opiekun i miejsce dowodów dla wskazanej usługi.
Nie pytamy o hasło SMTP, klucz API, token rozmowy ani dane karty. Gdy rzeczywisty
dostawca zostanie wskazany, jego aktualne warunki trzeba sprawdzić dla konkretnej
usługi/konta — nie wpisujemy jednej domyślnej retencji dla wszystkich dostawców.
Nie wysyłamy wiadomości, próśb do dostawców ani danych w celu uzyskania dowodu
bez odrębnego zlecenia. Można natomiast przygotować lokalną procedurę i wykazać
braki, zgodnie z D17=A.

**Warunek domknięcia:** lista odbiorników jest kompletna dla zadeklarowanego
zakresu, każdy ma potwierdzone zasady i wynik albo udokumentowany brak użycia,
a ograniczenia/cudze kopie są jawne. Sama deklaracja A/B, poprawny sandbox,
usunięcie lokalnego rekordu lub potwierdzenie wysyłki nie jest dowodem
usunięcia wszystkich kopii. Szczegółowa istniejąca procedura:
[A43.39 — dostawcy i dowody](A43_DOSTAWCY_PLIKI_I_DOWODY_CZATU.md).

### D15 — obsługa wniosków i przechowywanie dowodów

**Aktualizacja P07:** przyjęto oznaczenia organizacyjne **właściciel:
operator-1; zastępca: operator-2**, wspólne dla dev i test. Użytkownik
odpowiedział „tak” do tego przykładu przypisania. Istnienie kont, rzeczywista
obsada, dostęp i niezależność oceniających wymagają weryfikacji; nie nadano praw.

Trzeba wskazać uprawnioną obsługę, miejsce minimalnego rejestru spraw/dowodów,
zasady dostępu, retencję oraz sposób oceny i redakcji danych innych osób.
Obejmuje historyczny czat bez tokenu i status po usunięciu rozmowy.

**Zakres czatu wybrany w czacie:** wyłącznie rozmowy objęte zweryfikowanym
indywidualnym wnioskiem; zakres usunięcia po ręcznej ocenie. Nie wybrano
ogólnego porządkowania zakończonych rozmów po wieku. Okresy D02 ani D14
nie stają się regułą kasowania historii czatu. Weryfikacja dowodu konkretnej
rozmowy i ocena treści, powiązań, kopii oraz danych innych osób pozostają
wymagane. Sam wniosek i jego wpis nie są dowodem wykonania usunięcia.

**Miejsce rejestru spraw i dowodów wskazane przez użytkownika:**
`E:\Klucze\Rejestr`. To osobny zakres od minimalnego rejestru technicznego D09;
surowe materiały spraw nie trafiają do dziennika usunięć, repozytorium lub QA.
Odczyt `Test-Path` 30.10.2026 potwierdził istnienie katalogu; nie wyliczano
zawartości, nie odczytywano dowodów ani nie weryfikowano uprawnień i formatu.
Miejsce samo w sobie nie dowodzi gotowego kontrolowanego rejestru.
**Retencja materiałów dowodowych i załączników wybrana w czacie: 90 dni od
bezpiecznego zamknięcia sprawy.** Aktywne/sporne sprawy i wymagane blokady
zachowania wstrzymują usunięcie. Nie liczyć okresu od daty pliku lub samego
wpłynięcia wniosku. **W kolejnej odpowiedzi wybrano dla minimalnego zapisu
sprawy (ID, daty, wynik i bezpieczne kody), bez dowodów/załączników, 365 dni
od bezpiecznego zamknięcia sprawy z ochroną wymaganych wyjątków**.
90 ani 365 dni nie stają się TTL dziennika D09, protokołu usunięcia lub deduplikacji.

**Wspólna odpowiedź D15/D18:** wskazana rola to dosłownie „właściciel”.
**W kolejnym pytaniu o zastępcę użytkownik podał rolę „zastępca”.**
P07 przypisuje oznaczenia operator-1/operator-2. **P08: dodatkową ocenę
niejednoznacznych spraw prowadzi wyłącznie właściciel — operator-1**,
w dev i test; nie wymagać drugiego oceniającego ani oceny przez zastępcę.
Brak właściciela lub wystarczającego dowodu pozostawia sprawę nierozstrzygniętą.
Dostęp, powiązanie ról z rzeczywistą obsługą, zapis ocen i szczegóły wyjątków
nadal wymagają weryfikacji/przygotowania przy wdrażaniu.
Nie nadano uprawnień i nie uruchomiono retencji.

| Opcja | Krótki opis |
|---|---|
| A — wskazana osoba i kontrolowany rejestr | Nazwać rolę/osobę, miejsce dowodów i zasady; niepewne dowody przekazywać do dodatkowej uprawnionej oceny. |
| B — zespół i istniejący system obsługi spraw | Wskazać role, kontrolę dostępu i okresy w posiadanym systemie; nowa integracja, jeżeli potrzebna, wymaga osobnego wdrożenia. |
| C — odłożyć organizację procesu | Nie wykonywać niezweryfikowanych operacji; brak opiekuna/miejsca dowodów pozostaje nierozliczonym wymaganiem. |

Sam e-mail, ID sprawy lub dostęp do CC nie dowodzi własności rozmowy. Nie
tworzyć nowego tokenu statusu po usunięciu czatu ani dodatkowej kopii jego hasha.
Sekrety i surowe osobowe dowody nie trafiają do repozytorium ani raportów QA.

### D16 — zależność A05 / Stripe

A05 jest odłożone i nie zostaje wznowione przez przygotowanie tej listy.

| Opcja | Krótki opis |
|---|---|
| A — późniejsze, jawne wznowienie A05 | Osobną instrukcją wznowić ręczną konfigurację i odbiór sandbox oraz zebrać wymagane dowody dotyczące operatora. |
| B — pozostawić odłożone | Obowiązujący wybór. Gdy dowód dotyczący Stripe jest wymagany w zakresie A43, zależność nadal blokuje pełne zamknięcie; nie oznaczać jej jako wykonaną. |

Przy wznowieniu użytkownik musi ręcznie uzupełnić **obie** zmienne
`STRIPE_SECRET_KEY` i `STRIPE_WEBHOOK_SECRET` w **obu** plikach `dev.env.sops`
oraz `test.env.sops`. Nie wpisywać wartości do tego dokumentu ani do `.agekey`.
Sam pozytywny sandbox nie dowodzi usunięcia kopii danych u operatora.

### D17 — zakres, środowisko i kolejność aktywacji

Należy wskazać środowisko, konkretne mechanizmy/parametry, osobę odpowiedzialną,
monitoring, warunki zatrzymania i oddzielić odbiór lokalny od zastosowania ustawień.

| Opcja | Krótki opis |
|---|---|
| A — najpierw odbiór lokalny, bez aktywacji | Domknąć możliwy zakres kodu/polityk/testów i jawnie wykazać brak zastosowania w środowiskach; nie usuwa to obowiązkowych blokad brakujących decyzji/dowodów. |
| B — później kontrolowana aktywacja testowa | Po spełnieniu warunków wybrać mechanizmy i środowisko testowe, obserwację, limity partii i kryteria zatrzymania; potrzebna osobna instrukcja do zastosowania konfiguracji. |
| C — plan etapowy dla dev/test/prod | Osobny zakres i moment dla każdego środowiska, z dowodami i zatrzymaniem; nie jest to zgoda na automatyczne uruchomienie produkcji. |

Teraz obowiązuje brak nowej aktywacji. Niektóre dotychczasowe mechanizmy mają
własne włączenie — brak nowej aktywacji nie oznacza wyłączenia istniejącego
cleanup. Kontrola tego planu jest tylko oceną i nie zmienia runtime.

### D18 — odpowiedzialność za odbiór i eksploatację

**Aktualizacja P07:** właściciel — **operator-1**, zastępca — **operator-2**,
dla **dev i test**. To przyjęte oznaczenia organizacyjne po odpowiedzi „tak”
do przykładu; nie dowód istnienia kont, uprawnień lub dwóch różnych osób.
Poniższe wcześniejsze odpowiedzi opisują wybór nazw ról; weryfikacja dostępu
i rzeczywistej obsady pozostaje zadaniem wdrożeniowym.

Trzeba nazwać osobę/rolę, która zaakceptuje raport, będzie śledziła zaległości,
błędy i nieoczekiwane zmiany oraz będzie mogła zatrzymać konkretny worker.

**Odpowiedź użytkownika w czacie:** „właściciel”. Zapisano jako oznaczenie
roli odpowiedzialnej, wspólne dla D15 i D18; nie przypisano z imienia osoby
lub konta i nie nadano uprawnień. Następnie jako zastępcę wskazano dosłownie
rolę **„zastępca”**. Oznaczenia ról nie potwierdzają tożsamości ani dostępu —
wymaga to powiązania z rzeczywistą obsługą przed użyciem. **P08 powierza
dodatkową ocenę spraw wyłącznie właścicielowi (operator-1)**; nie wymaga
drugiej osoby do tej oceny. Pozostałe
odwołania do właściciela/opiekuna A43 powiązać z tym modelem, bez powtarzania
pytania o już wskazaną rolę, chyba że obszar wymaga innej szczególnej obsługi.
**Kolejne odpowiedzi użytkownika:** kanałem są **lokalne panele/statusy oraz
raport/log, sprawdzane ręcznie przez obsługę, bez powiadomień poza aplikacją**.
**Od pierwszego alarmu wymagającego reakcji sprawę kierować jednocześnie do
właściciela i zastępcy**; nie ustalono opóźnienia przed włączeniem zastępcy.
Jest to reguła wspólnej obsługi, nie potwierdzenie automatycznej wysyłki,
odczytania alarmu lub reakcji obu osób. Wykonawcze przypisanie uprawnień
i sprawdzenie tej ścieżki pozostają do wykonania.
Docelową obsługę zatrzymania konkretnego workera przygotować dla tego modelu
właściciela/zastępstwa, zgodnie z istniejącą autoryzacją; nie nadawać przez sam
zapis organizacyjny dodatkowego dostępu do danych lub sekretów.

**Stan źródeł monitoringu:** konfiguracja Alertmanagera wskazuje odbiornik
`local-console`, bez zewnętrznych integracji. Nie jest to potwierdzenie kanału
docierającego do osób poza lokalnym panelem ani dowód runtime. Kanał operacyjny
wybrany w D18 musi zostać konkretnie przygotowany i sprawdzony. Wybór lokalnej
obsługi nie zleca dodania zewnętrznego odbiornika ani wysyłania powiadomień.

| Opcja | Krótki opis |
|---|---|
| A — jeden właściciel z zastępstwem | Jedna odpowiedzialna rola, wskazany zastępca, kanał alarmowania i reguły eskalacji. |
| B — właściciele według obszarów | Osobne role dla danych konta, handlu/finansów, kopii i infrastruktury, z jedną osobą zatwierdzającą wspólny odbiór. |
| C — odłożyć | Nie uznawać warunków operacyjnych za spełnione; brak opiekuna i ścieżki zatrzymania musi pozostać jawny. |

## Stan po uzupełnieniach w czacie

**Pytania do kolejnego uzupełnienia:** [lista P01–P09](A43_PYTANIA_UZUPELNIAJACE.md).
To pytania do brakujących parametrów, nie nowe decyzje ani ponowny wybór
uzgodnionych opcji. P05 zależy od wyboru narzędzia w P04; D12 najpierw wymaga
rozpoznania konkretnych kopii. Odpowiedzi nadal zapisujemy w tym rejestrze.

**P01/D01 — odpowiedź 30.10.2026:** użytkownik ponownie potwierdził
„nadal do ustalenia — zachować ochronę tych danych przed usunięciem”.
To odpowiedź B na pytanie uzupełniające P01, bez zmiany pierwotnego wyboru
D01=A. Wyjątki i wymagane dłuższe okresy nadal otwarte; źródłowe dokumenty
finansowe i rejestry rozliczeń pozostają chronione. Przejść do P02 bez
ponawiania P01, dopóki nie pojawią się nowe ustalenia.

**P02/D02 — odpowiedź 30.10.2026:** użytkownik wybrał **A — konkretny okres**
dla zachowanego minimum handlowego (ID, kwoty, ilości, statusy, powiązania).
**Następnie podał 90 dni, liczone od faktycznego, potwierdzonego zakończenia
redakcji.** Minimum pozostaje jeszcze przez 90 dni; okres nie biegnie od
samego upływu wcześniejszego progu 365 dni. **Zaakceptowano wszystkie
przedstawione zabezpieczenia:** usuwanie blokują otwarte lub wznowione
sprawy i spory, wymagane dalsze zachowanie oraz zależności potrzebne
chronionym dokumentom D01, odtworzeniu, ponowieniom lub deduplikacji.
Brak dowodu zwolnienia zależności blokuje usuwanie, a niejasne przypadki
trafiają do ręcznej oceny. Użytkownik nie zna dodatkowych wyjątków;
deklarację zweryfikować przy wdrażaniu. Okres 90 dni nie obejmuje chronionych
znaczników ani rejestru usunięć. Sam wiek nie potwierdza kwalifikacji.
P02=A nie zmienia D02=B ani wcześniejszej redakcji po 365 dniach.
P02 uzupełniono na poziomie decyzji; mapowanie reguły, wykonanie i dowody
pozostają do przygotowania. Nie zlecono usuwania minimum;
zależności D01, deduplikacji i rejestru usunięć pozostają chronione.

**P03/D09 — odpowiedź 30.10.2026:** użytkownik wybrał **A — poda lokalizację
na innym nośniku lub hoście dla dev/test**. Następnie podał
**`E:\Klucze\Kopia3`**. W odpowiedzi uzupełniającej potwierdził: **zwykły
folder na E, dodatkowa kopia lokalna; lokalizację niezależną ustalić później**.
Nie jest to niezależny nośnik/host. P03 ma zapisaną odpowiedź z odłożonym
wyborem niezależnej lokalizacji; nie ponawiać pytania bez nowych ustaleń.
Podział dodatkowej kopii lokalnej na dev/test do przygotowania przy konfiguracji.
E:\Klucze\Kopia nadal jest planowaną kopią lokalną;
nie uznano jej za niezależną od utraty dysku E. Nie tworzono katalogów,
nie kopiowano danych i nie potwierdzono gotowości dodatkowej kopii.

**P04/D09 — odpowiedź 30.10.2026:** **B — narzędzie nadal do ustalenia**.
SAFE pozostaje nazwą sejfu/folderu, bez wybranego programu/usługi dla
niezależnych wskaźników dev/test. **P05 pozostaje zależne od P04**; nie zadano
go i nie przypisano odpowiedzi za użytkownika. Procedura aktualizacji
wskaźnika i gotowość rejestru/restore nadal wymagają przygotowania oraz dowodu.
Przejść do P06; nie ponawiać P04 bez nowych ustaleń użytkownika.

**P06/D14 — odpowiedź 30.10.2026:** **B — według wiedzy użytkownika tylko
tryb wbudowany CC; konfigurację i historię trzeba zweryfikować**.
Deklaracja dla dev/test, bez podania różnic między środowiskami. Wcześniejsze
„AI=C” jest historyczne; dowody braku osobnego endpointu i wysyłki nadal
wymagają przeglądu każdego środowiska. Nie kontaktowano dostawców,
nie wysyłano treści i nie zmieniono konfiguracji lub adaptera AI.

**P07/D15 i D18 — odpowiedź 30.10.2026:** „tak” do przykładu przypisania;
przyjęto **właściciel: operator-1; zastępca: operator-2; środowiska: dev i test**
jako oznaczenia organizacyjne. Istnienie kont, tożsamość obsługi i faktyczne
uprawnienia nadal wymagają weryfikacji. P07 uzupełnione na tym poziomie;
nie utworzono kont, nie nadano praw i nie potwierdzono niezależnego recenzenta.
Następne pytanie P08 dotyczy dodatkowej oceny niejednoznacznych spraw.

**P08/D15 — odpowiedź 30.10.2026:** **tylko właściciel — operator-1**,
dla dev i test. Wybrano własną odpowiedź zamiast proponowanego wariantu
z drugim oceniającym. Dodatkowej oceny nie powierzać zastępcy i nie uzależniać
jej od udziału drugiej osoby. Zachować uprawnioną ręczną ocenę i zapis wyniku;
brak właściciela lub wystarczającego dowodu pozostawia sprawę nierozstrzygniętą.
Nie zmienia to współodpowiedzialności za alarmy D18 ani okresów retencji D15.
P08 uzupełnione jako decyzja; nie nadano nowych praw i nie usuwano rozmów.

**D02-F01 — uzupełnienie podczas wdrażania:** użytkownik zdecydował usuwać
`delivery_date`, `delivery_slot`, `pickup_location` podczas redakcji po
365 dniach, zachowując ID, kwoty, ilości, statusy i wymagane powiązania.
Zapisano w rewizji decyzji `a43-decisions-2026-10-30.2`, mapie i walidatorze;
nie zmieniono 90 dni minimum, zabezpieczeń D02 ani ochrony D01.

**P09/D07, D08, D14 i D15 — odpowiedź 30.10.2026:** **C — wyjątki nadal
do ustalenia**. Nie przyjęto braku dodatkowych wyjątków i nie oznaczono
polityk logów, monitoringu, Mailpit lub materiałów spraw jako kompletnych
do aktywacji. Wybrane okresy/limity i dotychczasowe blokady pozostają.
Zakończono bieżącą rundę: osiem odpowiedzi zapisanych, P05 nie zadano z powodu
zależności od P04. P02 uzupełnione jako decyzja; pozostałe dowody i wykonanie
nadal wymagane. Nie ponawiać odłożonych pytań bez nowych ustaleń.

Zapisano odpowiedzi na dotychczas zadane pytania D01–D18, w tym kanał
i regułę obsługi alarmów D18. Nie trzeba ponownie wybierać tych samych opcji.
Nadal rozróżniać świadomie pozostawione decyzje od wykonania technicznego:

| Otwarte uzgodnienie | Obecna reguła / kiedy wrócić do pytania |
|---|---|
| D01 — wyjątki i wymagane dłuższe okresy finansowe | Użytkownik pozostawił do ustalenia; źródłowe dokumenty i rejestry chronione. Planowane 30 dni nie pozwala ich usuwać. |
| P09 / D07, D08, D14 i D15 — dodatkowe wyjątki retencji | C — nadal do ustalenia. Nie domniemywać braku wyjątków; polityki tych zasobów nadal niekompletne do aktywacji. Dotychczasowe zabezpieczenia i wybrane okresy/limity pozostają. |
| D09 — niezależna kopia i narzędzie SAFE | Inny nośnik/host oraz program/usługa sejfu do ustalenia. E:\Klucze\Kopia i dodatkowa E:\Klucze\Kopia3 są lokalne; użytkownik potwierdził odłożenie wyboru niezależnej lokalizacji. Nie uznawać tej zależności za gotową. |
| D12 — konkretne kopie i ich okresy | Najpierw rozpoznać artefakty; nie przypisywać okresu całemu E:\Klucze\Kopia2. |
| D14 — dowody użycia AI i dalsze kopie/odbiorniki | P06=B: według wiedzy użytkownika tylko tryb wbudowany CC. Zweryfikować konfigurację, historię i kopie osobno dla dev/test; deklaracja nie dowodzi braku wysyłki. Nie pytać ponownie o już wybrane parametry Mailpit. |
| D10, D13 i D16 | Świadomie odłożone: legacy, pliki/urządzenia oraz A05. Nie wznawiać przy samym przenoszeniu pozostałych decyzji do planu. |

Szczegółowe mapowanie pól, dowody uzgodnienia procesów i blokad zachowania,
konfiguracja środowisk oraz przypisanie ról do uprawnionej obsługi wymagają
pracy wdrożeniowej. Brak dowodu nadal wstrzymuje zależne czyszczenie. Jeśli
ta praca ujawni nowy wybór użytkownika, pokazać pytanie o konkretny parametr.
A43.40/A43.41 pozostają otwarte; obowiązuje lokalny odbiór bez nowej aktywacji.

## Prace do wykonania po uzgodnieniach — to nie są kolejne pytania o zgodę

| Obszar | Pozostałe wykonanie / wymagany dowód |
|---|---|
| Polityka A43.40 | Uzupełnić brakujące cele, okresy/warunki końca, zegary, wyjątki, właścicieli i kopie. Powiązać D01–D18 z rzeczywistymi zasobami, nie tylko z ogólnymi nazwami. |
| Kod i bramki | Rewizja `2026-10-31.10`: decyzje, mapa 127 pól, kwalifikator, metadane oceny, outboxy/koordynator, domyślnie wyłączony HTTP/publikatory oraz lokalny dowód ORDER oraz obserwacja Inventory z trwałymi zegarami i kwarantanną restore, walidator/odciski/observe. Odbiór Inventory w Order wdrożony za domyślnie wyłączoną flagą. Adaptery sześciu źródeł i odbiór z kontrolą kanonicznych potwierdzeń Payment wdrożone. Globalne dowody blokad/rejestru, przygotowanie generacji, workery biznesowe i odbiór środowiskowy nadal do wdrożenia. Kolejne zmiany mechanizmów lub decyzji wymagają dalszego przeglądu. Otwarta wersja nadal nie pozwala uzyskać gotowości samą zmianą etykiety. |
| Brakujące możliwości | Sprawdzić, czy wybrane reguły mają istniejący adapter. Jeśli nie, wdrożyć i odebrać konkretny mechanizm w zakresie A43.40; samo dodanie okresu do dokumentu go nie tworzy. |
| Dowody środowiska | Potwierdzić odbiorców/grupy/pending, rzeczywiste loggery, wolumeny, kopie, dostawców i właścicieli. Dowód braku użycia ma dotyczyć konkretnego środowiska. |
| Rejestr i klucze | Skonfigurować wskazane miejsca i pieczę, publiczny weryfikator/UUID, inicjalizację/synchronizację oraz aktualny niezależny wskaźnik; wykazać blokowanie błędnego/starego rejestru. |
| Próba w izolacji | Observe i czyszczenie tylko na izolowanych danych; sprawdzić zakres pól, aktywne procesy, ograniczone partie, blokady, deduplikację, awarie, ponowienia i metryki. |
| Restore w zakresie A43 | Sprawdzić aktualny rejestr, ponowienie 14 barier i uzgodnionego cleanup, unieważnienie odtworzonych sesji/wyzwań, zachowanie biznesowych streamów i deduplikacji oraz uzgodnienie części handlowo-finansowej przed dopuszczeniem ruchu. Sam wcześniejszy drill core nie kończy całego A36 ani wszystkich zależności. |
| Zastosowanie ustawień | Jeżeli zostanie osobno zlecone: konkretne parametry i środowisko, kontrolowany start, monitoring i dowód zatrzymania. Powrót do observe nie odzyskuje usuniętych danych. |
| Końcowy A43.41 | Zestawić wszystkie zasoby z kodem, polityką, procedurami i dowodami; wykonać odpowiednią regresję Java/Python/JS/przeglądarek, PostgreSQL/Redis, Gateway/OpenAPI, dwóch kont, współbieżności, dużych zbiorów, opóźnionych zdarzeń i restore. |
| Statusy i jeden raport | Rozszerzyć model statusów/bramkę A43.15 dopiero na podstawie dowodów. Oddzielić gotowość kodu, lokalny odbiór, konfigurację środowiska i faktyczne wykonanie. Nie ustawiać globalnego complete/dataErased przy nierozstrzygniętych kopiach. |

Wymagany wynik końcowy: jeden spójny raport A43.41 z checklistą dowodów
i ograniczeń. Gdy wymagane decyzje lub dowody pozostają otwarte, A43 również
pozostaje otwarte — nie tworzymy w ich miejsce dodatkowych numerów etapów.

## Jak przekazać wybór

Można podać `D03=A`, `D04=B: okres …, grupy …`, `D09=A: lokalizacja …,
opiekun …` itd. Dla D01/D02, D06–D08 i D10–D15 sam symbol A/B zwykle nie
wystarcza: trzeba dopisać konkretne okresy, zakres, miejsce lub odpowiedzialnego.
Można rozstrzygać obszary osobno. **Nie przesyłać prywatnych kluczy ani tokenów.**

| Pole do uzupełnienia | Wymagana treść |
|---|---|
| Decyzja | ID Dxx i wybrana opcja |
| Zakres | klasy danych/zasoby i środowisko |
| Reguła | okres albo jednoznaczny warunek zakończenia oraz moment rozpoczęcia |
| Wyjątki | aktywne/sporne procesy, zależne kopie i wymagane zachowanie |
| Odpowiedzialny | rola/osoba i miejsce kontrolowanego dowodu |
| Aktywacja | osobno: brak / plan testowy / konkretne zlecenie dla środowiska |

## Zakres nadal odłożony

A05 pozostaje odłożone, zgodnie z D16. **Całe A41** (konsolidacja usług)
i zmiany samych zdjęć z A27–A31/A40 pozostają poza bieżącym wdrożeniem.
**A43.41** to końcowy odbiór A43 i jest innym zadaniem niż odłożone A41.
Nie oznaczać tych odłożonych prac jako wykonanych przy zamknięciu A43.

Źródła stanu: [postęp audytu](AUDIT_0.1_POSTEP.md),
[plan zamknięcia](A43_PLAN_ZAMKNIECIA.md),
[macierz retencji](../scripts/qa/contracts/retention-policy.json),
[polityka i aktywacja](A43_POLITYKA_RETENCJI_I_AKTYWACJA.md),
[kopie wewnętrzne](A43_KOPIE_WEWNETRZNE_I_STAN_URZADZEN.md),
[dostawcy i dowody czatu](A43_DOSTAWCY_PLIKI_I_DOWODY_CZATU.md),
[backupy i rejestr](A43_BACKUPY_I_REJESTR_USUNIEC.md).

## Potwierdzenie podczas próby ukończenia — 31.10.2026

Użytkownik ponownie wybrał: P01/D01 — pozostawić do ustalenia i chronić dane;
P03/P04/P05/D09 — nadal do ustalenia; P09 — wyjątki nadal do ustalenia.
Nie zmienia to semantyki zapisu decyzji a43-decisions-2026-10-30.2, okresów
ani D17. Nie traktować polecenia „skończyć wdrożenie” jako zastąpienia tych
odpowiedzi lub zgody na aktywację. Nie ponawiać tych pytań bez nowej informacji.

Sprawdzenie wyłącznie metadanych: E:\Klucze\privacy-journal oraz
E:\Klucze\privacy-journal-keys nie były dostępne pod zapisanymi ścieżkami.
E:\Klucze\Kopia2 istnieje i w chwili sprawdzenia nie zawiera wpisów;
E:\Klucze\Rejestr istnieje, jego materiałów nie odczytywano. Pusty Kopia2
nie dowodzi braku innych kopii ani kompletności historii i nie otrzymuje TTL.
Nie odczytywano kluczy/SOPS, nie inicjalizowano rejestru ani katalogów.
Pełne warunki i brakujący kod: [odbiór końcowy A43](A43_ODBIOR_KONCOWY.md).


**Aktualizacja 31.10.2026, rewizja `.11`:** lokalny rejestr znanych blokad
zakupu lub zakupów stabilnego ID klienta, idempotentne polecenia, świeży
odczyt pod blokadami zakresów i ponowna kwalifikacja po zmianie rewizji.
Rejestr nie jest pełnym inwentarzem i nie potwierdza globalnej nieobecności
przeszkód. Brak API/nowych uprawnień CC, zwolnienia/TTL lub harmonogramu.
Izolowany restore zachowuje historię i blokuje odtworzone zakresy. Rejestr
obejmuje 106 tabel/1015 pól, 182 migracje. Dowody bieżącego odbioru:
`tmp/audit-a43-40-known-holds/report.json`; szczegóły w
[A43_RETENCJA_DANYCH_HANDLOWYCH.md](A43_RETENCJA_DANYCH_HANDLOWYCH.md).
A43.40 i A43.41 pozostają otwarte; D01, D09, P09 nadal nierozstrzygnięte,
D17 bez aktywacji. Workery danych biznesowych i końcowa regresja pozostają.


**Aktualizacja 31.10.2026, rewizja `.12`, pakiet 115:** kontrolowany wewnętrzny
przebieg jednego zakupu: przygotowanie generacji z niezmiennym poprzednikiem,
jedna obserwacja na krok i ocena wyłącznie wskazanego zadania. Kontrola znanych
blokad i aktualności przed/po odczycie nie jest rozproszoną barierą danych.
Brak publicznego API/CC, pozytywnych globalnych GUARD, harmonogramu i czyszczenia.
Rejestr: 106 tabel/1016 pól, 183 migracje. [Zakres i ograniczenia](A43_PRZEBIEG_OCENY_ZAKUPU.md).
Dowody: tmp/audit-a43-40-targeted-workflow/report.json. A43.40/A43.41 pozostają
otwarte; D01/D09/P09 oraz D17 bez nowych decyzji i bez aktywacji.


**Aktualizacja 31.10.2026, rewizja `.13`, pakiet 116:** odbiór pobierania ocen
na 100 000 zadaniach i optymalizacja planów SQL. Cztery ścieżki kandydatów,
jedna nowa dzierżawa, częściowe indeksy V33, okno klienta do 16 i zachowane
punktowe przejęcie. Bez danych/nowych pól, retencji, aktywacji lub zmian CC.
Rejestr 106 tabel/1016 pól, 184 migracje. [Zakres i ograniczenia pomiaru](A43_WYDAJNOSC_POBIERANIA_OCEN.md).
Dowody tmp/audit-a43-40-claim-scale/report.json; ostatni pełny drill pozostaje
z pakietu 115 i nie był w tym zakresie powtarzany. Pełne A43.40/A43.41 nadal
otwarte; D01/D09/P09 i D17 bez nowych rozstrzygnięć.


**Aktualizacja 31.10.2026, rewizja `.14`, pakiet 117:** przypięta definicja
D02 i adapter FIELD_SCOPE_REVIEWED. Nowe potwierdzenie ma niezmienny skrót;
stare wpisy bez zgodnego powiązania pozostają w historii i są nieznane dla
oceny. Nie potwierdza to polityki zachowania, aktualnego dziennika lub
wdrożonych schematów. Bez aktywacji i innych pozytywnych GUARD. Rejestr
106 tabel/1017 pól, 185 migracji. [Dowód i ograniczenia](A43_DOWOD_DEFINICJI_POL.md).
Raport tmp/audit-a43-40-field-scope/report.json; pełne A43.40/A43.41 i decyzje
D01/D09/P09 nadal otwarte, D17 bez aktywacji.


**Aktualizacja 31.10.2026, rewizja `.15`, pakiet 118:** osobna runda ponownej
weryfikacji ośmiu zapisanych obserwacji przed oceną, z CAS zakresu/rewizji,
kontrolą blokad i zachowaniem dzierżaw, budżetu prób oraz backoffu. Brak
pozwolenia na mutację, atomowej migawki lub rozproszonej bariery. Bez zmian
schematu, retencji, CC i aktywacji. [Zakres i granice](A43_PONOWNA_WERYFIKACJA_OBSERWACJI.md).
Raport tmp/audit-a43-40-revalidation/report.json; ostatni pełny drill pozostaje
z pakietu 117. Pełne A43.40/A43.41 i D01/D09/P09 nadal otwarte; D17 bez aktywacji.
