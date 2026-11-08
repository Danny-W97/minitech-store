# A43.40 — macierz retencji i warunki aktywacji

Wersja `a43.40-2026-10-31.15`, 31.10.2026, branch Audit_0.1.
**Etap w toku.** Użytkownik pozostawił część polityk nierozstrzygniętą;
`policyReady=false`, `stageComplete=false`, `activationAllowed=false`.
Nie aktywowano czyszczenia, nie zmieniono konfiguracji usług, Redis,
backupów, SOPS, log driverów ani środowisk dev/test/prod.

## Zapisane decyzje

Użytkownik zatwierdził profil techniczny w poniższej tabeli, **bez aktywacji**.
Rewizja `.2` przeniosła uzupełnienia D01–D18/P01–P09 do maszynowego zapisu
`scripts/qa/contracts/retention-decisions.json`, powiązanego z macierzą.
Zapis obejmuje plany oraz jawne braki, nie konfigurację runtime.

**Rewizja `.3`:** [mapa i kwalifikator D02](A43_RETENCJA_DANYCH_HANDLOWYCH.md)
obejmują 10 tabel/127 pól: 20 do redakcji, 91 minimum i 16 protokołu bez
automatycznego TTL. Pięć tabel D01 wyłączono. D02-F01 zatwierdza redakcję
daty/przedziału dostawy i punktu odbioru. Kwalifikator ocenia osiem dowodów
usług oraz blokady, bez dostępu do danych i bez podłączenia do runtime.
Maszynowy zapis decyzji ma rewizję `a43-decisions-2026-10-30.2`.
Nie zaimplementowano jeszcze trwałych źródeł nowych dowodów i workerów.

**Rewizja `.4`:** przygotowano trwały magazyn i ograniczony wykonawca oceny
D02 w Order oraz migrację V27, sprawdzone na jednorazowym PostgreSQL.
Dodano dwie tabele/26 pól metadanych bez automatycznego TTL; inwentarz
obejmuje 87 tabel/775 pól. Wykonawca zapisuje tylko ocenę pod dzierżawą
i nie jest podłączony do harmonogramu lub API. Źródła potwierdzeń pozostałych
usług, aktualny rejestr, unieważnianie po restore oraz mutatory biznesowe
nadal blokują aktywację. Gotowość nie zmienia się na podstawie samego
istnienia trwałych wierszy.

**Rewizja 31.10.2026 `.1`:** dodano wyłączony domyślnie kanał unieważniania
dla siedmiu usług oraz klienta z uwierzytelnieniem i limitami transportu.
Kanał nie przyjmuje pozytywnych dowodów i nie zwalnia blokad. Restore
w istniejącej izolowanej kwarantannie unieważnia wszystkie odtworzone oceny
D02, także kont niewymienionych w dzienniku i gości, również przy pustym
dzienniku. Zachowuje dowody, historię i dane biznesowe; releaseAllowed=false.
Pełny drill 14 baz/Redis przeszedł. Nadal wymagane trwałe źródłowe outboxy,
zbieranie świeżych dowodów i wdrożeniowe potwierdzenie kwarantanny.

- D01: planowane 30 dni i cykl 7 dni; wyjątki/wymagane okresy nadal otwarte,
  dlatego źródłowe dokumenty finansowe i rejestry pozostają chronione.
- D02/P02: redakcja pozostałych danych osobowych i treści po 365 dniach od
  końcowego uzgodnienia całego wskazanego łańcucha, także dla aktywnych kont.
  Minimum ID/kwot/ilości/stanów/powiązań przez kolejne 90 dni od faktycznej,
  potwierdzonej redakcji. Wymagane zachowanie, sprawy/spory i zależności D01,
  odtworzenia, ponowień i deduplikacji blokują usuwanie; brak dowodu zwolnienia
  blokuje wykonanie, niejasne przypadki do ręcznej oceny. Mapowanie i mechanizm
  tej dodatkowej retencji nadal do wdrożenia — nie dodano nowego workera.
- D03: docelowe SQL 7 dni zatwierdzone. Macierz opisuje istniejące zegary
  GREATEST/COALESCE, ochronę rodzin refresh i aktywnych metod; bez zmiany
  repozytorium SQL, konfiguracji lub dotychczasowego włączenia workera.
- D04/D05: wybrane progi 7/30 dni i zamierzeni odbiorcy zapisani; rzeczywiste
  grupy/pending/ponowienia dev/test oraz projekt streamu bez odbiorców nadal
  wymagają dowodów. Nie omijać ochrony pustych/brakujących grup.
- D07/D08/D14/D15: wybrane okresy i limity zapisane; P09=C pozostawia wyjątki
  nierozstrzygnięte. Narzędzie SAFE, niezależna kopia i procedura wskaźnika
  pozostają otwarte. Kopia3 na E jest dodatkową kopią lokalną.
- P06/P07/P08: deklarowany tylko tryb wbudowany AI do weryfikacji; właściciel
  operator-1 i zastępca operator-2 w dev/test jako oznaczenia organizacyjne.
  Dodatkową ocenę spraw prowadzi wyłącznie właściciel, bez wymogu drugiej osoby.
  Konta, dostęp i ścieżka obsługi nadal wymagają weryfikacji.

Pełny zapis: [rejestr decyzji](A43_POZOSTALE_PRACE_I_DECYZJE.md) oraz
[wyniki pytań](A43_PYTANIA_UZUPELNIAJACE.md). Dotychczasowe mechanizmy
zachowują własne ustawienia; rewizja nie wyłącza ani nie aktywuje ich działania.

Minimalne znaczniki kont, blokady procesów, deduplikacja, aliasy starych zdarzeń
i niezależny rejestr usunięć pozostają **bez automatycznego TTL**. Wycofanie
wymaga udokumentowanego wycofania wszystkich właściwych backupów, producentów,
spóźnionych zdarzeń i źródeł ponowień, a następnie osobnej oceny konkretnego
zakresu. Sam wiek, wygaśnięcie dostępu do statusu lub brak konta nie wystarcza.
Są to nadal dane pseudonimowe; nie deklarujemy ich anonimizacji.

## Zatwierdzony profil techniczny

| Zakres | Okres | Początek i warunki kwalifikacji | Faktyczny skutek istniejącego adaptera |
|---|---:|---|---|
| Szczegółowy log rekomendacji | 7 dni | `requested_at`; zapis szczegółowy jest osobno konfigurowany | usunięcie kwalifikujących się wierszy, bez zmiany metryk agregowanych |
| Certyfikowane logi zadań CC | 14 dni | `closedAt` i wiek pliku certyfikatu/logu; brak pisarza, referencji i aktywnej historii | tylko kwalifikująca się zamknięta para; legacy i niejasne pliki wymagają oceny |
| Audyt bezpieczeństwa | 90 dni | `occurred_at` zdarzenia; nie od odczytu/eksportu | ograniczony `purge` kwalifikujących się zdarzeń |
| Payload outboxu faktur | 30 dni | `GREATEST(published_at, created_at)`, PUBLISHED, brak aktywnego przetwarzania | `payload=NULL`; event ID i rekord pozostają |
| Kontakt w outboxie płatności | 30 dni | `GREATEST(published_at, created_at)`, PUBLISHED, brak aktywnego przetwarzania | `recipient/delivery_address=NULL`; klucz zdarzenia i fingerprint pozostają |
| Diagnostyka odrzuconych inboxów Billing/Notification | 30 dni | `received_at`, REJECTED, `related_id IS NULL` | usunięcie payload/error, zachowanie tożsamości i deduplikacji |
| Treść wysłanych powiadomień | 30 dni | `GREATEST(sent_at, created_at, updated_at)`, SENT, brak retry, lease i błędu | tylko subject/message; **nie** ogólne usunięcie odbiorcy i całego rekordu |
| Treść zamkniętych DLQ | 30 dni | `GREATEST(resolved_at, created_at, updated_at)` oraz uzgodnione, odpowiednio stare SENT | subject/message/failure_reason, przy zachowaniu rekordu; otwarte DLQ chronione |
| Całe rozpoznane szyfrowane backupy | 14 dni | zestaw: `finishedAt` uwierzytelnionego manifestu; legacy dump: dotychczasowa semantyka `find -mtime +14` | całe kwalifikujące się kopie; brak selektywnej edycji danych osoby |

„Od zakończenia procesu” oznacza powyższe konkretne zegary kodu; audyt
i rekomendacje liczone są od zdarzenia. `find -mtime` ma rozdzielczość pełnych
dób, więc nie obiecuje usunięcia dokładnie po 14×24 godzinach. Próg kwalifikacji
nie jest gwarancją terminu fizycznego usunięcia: obowiązują cykle, ograniczone
partie, blokady, opóźnienia i awarie. Aktywne/nieuzgodnione dane pozostają
chronione według warunków konkretnego adaptera. Nie dopisano ogólnej obsługi
hold do adapterów, które jej nie mają; przed użyciem należy sprawdzić wyjątki.

Retencja całych backupów nie obejmuje aktywnego AOF, streamów i wolumenu Redis,
niezależnego rejestru, kluczy ani nieznanych snapshotów. Każda taka kopia ma
osobną pozycję. Usunięcie lokalnego payloadu nie dowodzi usunięcia kopii w
backupu, u dostawcy lub na urządzeniu. Zmiana okresów nie zmienia zaakceptowanych
protokołów indywidualnego usuwania A43.33–A43.39.

## Zakres macierzy i nierozstrzygnięte dane

Maszynowy kontrakt: `scripts/qa/contracts/retention-policy.json`.
Obejmuje 26 polityk, **106 tabel / 1017 kolumn, 6 przestrzeni Redis i 20 kategorii
kopii**. Każda tabela ma właściciela, utrwaloną listę kolumn i politykę domyślną
oraz jawne wyjątki pól. Każda polityka zawiera cel, zegar, skutek, warunki,
wyjątki, zasady kopii i źródła. Reguły eksportu z A43.30 nie są automatycznie
regułami kasowania. Zmiana tabeli, kolumny, właściciela, namespace, kopii lub
przejrzanego źródła wymaga ponownego przeglądu; nowa kolumna nie dziedziczy
po cichu dotychczasowego odbioru.

| Klasa | Utrwalona reguła | Brak do zamknięcia |
|---|---|---|
| Profil i trwałe poświadczenia | istniejący protokół konta, rejestr i bariery; bez nowej retencji bezczynności | aktywacja i dowody środowiska |
| Wishlist i recenzje | istniejące działania właściciela i cleanup; minimalne ID recenzji osobno chronione | aktywacja i dowody środowiska |
| Wynik eksportu | dotychczasowa 1 h od utworzenia; anulowanie od razu blokuje dostęp; porcjowe usuwanie zadań/treści | osobne potwierdzenie środowiska; brak nowej aktywacji kolektora |
| Minimalny protokół usunięcia i rejestr | brak automatycznego TTL; 7 dni tokenu statusu nie jest okresem przechowywania wniosku | piecza rejestru/kluczy i dowody wycofania źródeł przed zmianą tej reguły |
| Dokumenty i rejestry finansowe D01 | chronić przed nowym kasowaniem po wieku; redakcje kopii technicznych A43.36 pozostają odrębne | wyjątki i wymagane dłuższe okresy nadal do ustalenia |
| Pozostałe dane handlowe D02/P02 | mapa 127 pól i kwalifikator, trwałe metadane oraz ograniczony wykonawca oceny odebrane lokalnie; 365 dni redakcji i 90 dni minimum według uzgodnionych zegarów | globalne dowody blokad/rejestru oraz rozliczenie nieznanych zakresów źródłowych, kontrolowane przygotowanie generacji, migracje redakcji i workery biznesowe, testy całego łańcucha/restore; obecna polityka danych biznesowych nadal zachowawcza |
| Zakończone dane bezpieczeństwa SQL | zatwierdzone 7d, obecne enabled i warunki GREATEST/COALESCE bez zmian | dowody środowiska; aktywne rodziny refresh i metody MFA nadal chronione |
| Payment/invoice Redis Streams | wybrane 7d/30d, wymagane grupy płatności i brak zamierzonych odbiorców faktur dev/test zapisane jako plan | rzeczywiste grupy/pending/replay oraz projekt bez odbiorców; bez aktywacji trim |
| Wywołania i limity techniczne RAM/Redis | obecne ograniczenia TTL/pojemności bez zmiany; m.in. PDF 15 min, zgoda AI 5 min, sesja CC 30 min idle / 8 h absolute | nie są podstawą retencji dokumentów; runtime wymaga potwierdzenia |
| Czat | osobny dowód i ręczna procedura A43.39; bez automatycznego dopasowania po e-mailu | konkretna ocena i dowody operacji; brak nowego purge |
| Katalog/sekwencje/stany wspólne | dotychczasowy cykl domenowy, bez nowego kasowania po wieku | nie utożsamiać z profilem klienta ani nie resetować sekwencji |
| Pozostałe stany techniczne i swobodna treść | ręczna ocena i ochrona aktywnych procesów; brak wymyślonego TTL | zakres, zegar i końcowa polityka kopii/operacji |
| Logi aplikacji i monitoring | brak zmiany driverów i wolumenów | faktyczny backend, okresy, odbiorcy, wyjątki i dowody konfiguracji |
| Legacy, skarbce i klucze | brak automatycznego purge; zależne szyfrogramy i kopie muszą pozostać czytelne | ocena/migracja i plan wycofania |
| Urządzenia/Downloads/checkout/zamiary operatora | kopie użytkownika i ochrona nieuzgodnionych ponowień | osobna polityka i potwierdzenie konkretnego zakresu, bez zdalnego kasowania |
| AI/SMTP/External API/płatności | procedura A43.39 i jawne ograniczenia | dowody rzeczywistych dostawców; A05 nadal odłożone |

Wartości TTL istniejących wyzwań MFA, logowania i limitów Gateway pozostają
zgodne z ich własnymi źródłami. Nie wprowadzono skanowania Redis, globalnego
TTL ani kasowania grup/pending. Nie potwierdzono konfiguracji działającego
środowiska na podstawie domyślnych wartości repozytorium.

## Walidacja i przykładowa konfiguracja do oceny

`python3 scripts/qa/retention-policy-check.py` sprawdza strukturę, pokrycie,
właścicieli, decyzje i SHA256 źródeł. Dodatkowy odcisk kanonicznego JSON zapisu
decyzji jest utrwalony w walidatorze: samo przeliczenie hashy macierzy nie
pozwala zmienić odpowiedzi użytkownika. Zmiana decyzji wymaga przeglądu nowej
rewizji zapisu i walidatora. Raport pokazuje statusy 18 pozycji oraz osobne
blokady wykonania, wyjątków i dowodów. Nie utożsamia zatwierdzonego planu
z gotowym adapterem. Zwraca `status=PASS` dla poprawnej
**niekompletnej** macierzy oraz osobno `policyReady=false` i listę blokad.
`--require-ready` zwraca kod 2 / `NOT_READY`; nie wolno interpretować
strukturalnego PASS jako zgody na aktywację lub zamknięcie A43.40.

Rewizja `.3` dodatkowo sprawdza `commerce-retention.json` przez
`commerce-retention-check.py`: pełne pokrycie kolumn, wyłączenie D01,
zatwierdzony zakres redakcji/protokołów, uczestników, zegary i hashe DDL/kodu.
Raport `commerceAssessment` jawnie wskazuje brak podłączenia runtime i dalsze
blokady. Bezpośrednie wywołanie nowego skryptu również używa pełnej bramki
A43.40, w tym ochrony zapisu decyzji i kandydatów.

`--candidate scripts/qa/contracts/retention-observe-example.json` sprawdza
wyłącznie wymienione jawne ustawienia przykładu, przypisane do wersji i SHA256
polityki. Nie otwiera SOPS, skarbców, zmiennych środowiska, logów użytkownika
ani usług. Odrzuca nieznane ustawienia, zmienione okresy, niezgodny hash i tryb
kasujący w planie obserwacji. Zawsze odrzuca żądanie aktywacji w tej otwartej
wersji polityki. Nie jest importerem .env ani pełną walidacją środowiska.

**Narzędzie jest tylko bramką oceny.** Nie stosuje ustawień i nie przełącza
działających usług w observe. Dotychczasowy cleanup SQL bezpieczeństwa,
rekomendacji czy praca konsumentów mogą mieć własne ustawienia; pozostawiono
je bez zmian. Administrator może ręcznie ustawić zmienne poza tą bramką,
dlatego rzeczywista aktywacja wymaga również kontroli wdrożenia i uprawnień.

CI uruchamia kontrolę strukturalną, a nie `--require-ready`, aby uczciwie
raportować otwarty etap bez blokowania wszystkich niezwiązanych zmian.
Zmiana polityki tworzy nową wersję, nowy hash i unieważnia stare kandydaty.

## Warunki przed osobną aktywacją

1. Rozstrzygnięte okresy i zdarzenia startowe, wyjątki zachowania i zakres
   każdego adaptera. Nie zastępować polityki dokumentów technicznymi 30 dniami.
2. Potwierdzony aktualny niezależny rejestr A43.37, piecza kluczy i wskaźnika,
   kontrola odtworzenia i zabezpieczenie przed późnymi zapisami.
3. Ustaleni rzeczywiści odbiorcy streamów, grupy, pending i uzgodnienie
   procesów; brak ignorowania pustej listy odbiorców.
4. Rzeczywiste kopie/loggery/monitoring oraz dowody dostawców i A05 rozliczone
   według A43.38–39. Brak dowodu pozostaje blokadą, nie potwierdzeniem sukcesu.
5. Wynik observe dla konkretnego środowiska i próbka do oceny; testy ograniczeń,
   blokad i powtórzeń na danych izolowanych. Bez surowych danych w raporcie.
6. Osoba odpowiedzialna za środowisko, dokładny zestaw parametrów i osobna
   instrukcja użytkownika do aktywacji w danym środowisku. Ogólne „kontynuuj”
   nie spełnia tego warunku.

## Monitoring i zatrzymanie

Przed aktywacją sprawdzić istniejące metryki zaległości, wieku najstarszego
kwalifikującego się wpisu, ostatniego sukcesu/błędu i liczby/skali zmian
odpowiedniego workera. Dla CC dostępny jest bezpieczny stan retencji logów;
dla backupu — raport kwalifikacji całych kopii. Nie dopisano nowego kolektora
i nie ogłoszono rzeczywistej obserwacji środowiska. Kontrolować zgodność wersji
polityki, blokady źródeł, tempo partii, błędy i niespodziewany wzrost kandydatów.

Przy niezgodności zatrzymać **konkretny** worker jego istniejącym `off`/flagą
enabled albo wrócić do `observe` tam, gdzie taki tryb istnieje; wykonać to
przez osobno autoryzowaną konfigurację i wymagany cykl wdrożenia. Nie wyłączać
brokera i nie usuwać grup/pending w celu „zatrzymania retencji”. Nie dopisywać
fikcyjnego trybu observe do workerów mających tylko enabled. Po zatrzymaniu
zweryfikować brak nowych zmian i zachować minimalny raport bez danych osobowych.
Powrót do observe nie odtwarza skasowanych danych; restore wymaga procedury
kwarantanny i aktualnego rejestru A43.37, nie bezpośredniego przywrócenia ruchu.

## Co pozostaje do zrobienia

A43.40 pozostaje otwarty zgodnie z odpowiedziami użytkownika. Rewizja `.2`
przeniosła decyzje do macierzy, a `.3` dodała mapę pól i kwalifikator D02,
odebrane lokalnie: 159 testów Java (12 nowych kwalifikatora) i 17 Python.
Rewizja `.4` dodała magazyn i wykonawcę oceny: 12 nowych i 10 regresyjnych
testów PostgreSQL, 12 kwalifikatora Java i 54 Python PASS. Następna praca
dotyczy zaufanych adapterów źródłowych, unieważniania zmian całego łańcucha
i restore, aktualnych dowodów rejestru/blokad oraz migracji i workerów
biznesowych z testami całego procesu.

Rewizja 31.10.2026 `.1` odebrała lokalnie transport unieważniania i integrację
izolowanego restore: **43 testy Java** (23 PostgreSQL, 12 kwalifikatora,
4 klienta HTTP i 4 autoryzacji/warunku włączenia), **62 Python** i pełny drill
szyfrowanego backupu 14 baz/Redis PASS. Nie podłączono producentów zdarzeń
w usługach i nie przyjęto pozytywnych dowodów z żądań operatora/klienta.
Następne wykonanie to trwałe źródłowe zawiadomienia i dowody, ich powiązanie
z każdą zmianą procesu oraz właściwe workery biznesowe. Gotowość nadal NOT_READY.
Wyjątki D01/P09, kopie/piecza D09, środowiskowe dowody streamów i pozostałe
mechanizmy/dowody nadal wymagane. Żadna z rewizji nie aktywuje czyszczenia.
Rewizja 31.10.2026 `.2` dodaje trwały outbox Returns: zapis atomowy ze
zmianą biznesową, binding z oczekiwaną epoką, ograniczony nadawca bez
harmonogramu i trwałe zablokowanie odtworzonego źródła. Rejestr rozszerzono
o dwie tabele/25 pól. Brak automatycznego TTL tych metadanych; nadal bez
koordynatora/publikatora w runtime i bez pozostałych sześciu źródeł.
Raport: `tmp/audit-a43-40-source-outbox/report.json`. Nie stanowi to zgody
na czyszczenie ani dowodu gotowości całego łańcucha.

Rewizja 31.10.2026 `.3` rozszerza trwałe zawiadomienia na Inventory,
Promotion i Delivery. Sześć nowych tabel/75 pól, wspólny protokół metadanych
zgodny z Returns; bez zmiany okresów i bez TTL znaczników. Izolowany restore
blokuje cztery źródła i raportuje je osobno. Payment/Billing/Notification,
koordynator/publikatory, pozytywne dowody i workery biznesowe nadal wymagane.
Raport: `tmp/audit-a43-40-sources-ipd/report.json`. Gotowość nadal NOT_READY.

Rewizja 31.10.2026 `.4` dodaje źródła Payment, Billing i Notification,
bez zmiany ochrony D01 i bez aktywacji czyszczenia. Sześć tabel/75 pól,
czternaście tabel źródłowych bez TTL. Restore obejmuje siedem źródeł.
Trwałe i typowane powiązania zapobiegają przypisywaniu konta/czatu do zakupu
przez sam numer; niejasne kopie nadal wymagają oceny. Koordynator/publikatory,
pozytywne dowody i workery biznesowe pozostają do wdrożenia.
Raport: `tmp/audit-a43-40-sources-pbn/report.json`; gotowość nadal NOT_READY.

Rewizja 31.10.2026 `.5` dodaje trwałe komendy koordynatora Order V28:
komplet siedmiu źródeł, oczekiwane epoki z zachowanych potwierdzeń,
blokowanie nowych generacji przy nierozliczonych starych skutkach i trwałą
kwarantannę po restore. Jedna tabela/19 pól bez TTL. BOUND nie jest dowodem
uzgodnienia. Transport pozostaje interfejsem zaufanego adaptera, bez HTTP,
endpointów, harmonogramów i aktywacji. Raport:
`tmp/audit-a43-40-binding-coordinator/report.json`; gotowość nadal NOT_READY.

Rewizja 31.10.2026 `.6` dodaje uwierzytelniony transport HTTP i podłączenie
publikatorów za jawnymi flagami, domyślnie false. Tokeny usług pozostają
w istniejącym mechanizmie, odpowiedź wymaga echo zakresu i mieści się w 2 KiB.
RoutePolicy oraz kontroler rozdzielają uprawnienia Order i siedmiu źródeł.
Publikacja respektuje kwarantannę i nie przygotowuje nowych generacji,
dowodów ani danych biznesowych. Nie zmieniono schematu i środowisk.
Raport: `tmp/audit-a43-40-http-publishers/report.json`; nadal NOT_READY.

**Aktualizacja 31.10.2026, rewizja `.7`:** pierwszy zaufany lokalny dowód
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

**Aktualizacja 31.10.2026, rewizja `.8`:** lokalna obserwacja Inventory
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

Nie tworzymy dodatkowego numeru, nie rozpoczynamy końcowego zamknięcia A43.41
na podstawie samej kontroli strukturalnej. A05 (ręczne `STRIPE_SECRET_KEY`
i `STRIPE_WEBHOOK_SECRET` w `dev.env.sops` i `test.env.sops` oraz sandbox), całe
A41 i zmiany samych zdjęć A27–A31/A40 pozostają odłożone.


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
