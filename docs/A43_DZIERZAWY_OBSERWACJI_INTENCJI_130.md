# A43.40 — dzierżawy obserwacji powiązane z intencją, pakiet 130

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.8**.
Wdrożenie lokalne; A43.40/A43.41 nadal otwarte, czyszczenie wyłączone.

## Wdrożony zakres

`CommerceRetentionWorkflow.fenceIntentAndAssess` ponownie odczytuje osiem
źródeł, a następnie zakłada i weryfikuje dzierżawy obserwacji w siedmiu
źródłach zdalnych, w tym Inventory. Wykorzystuje istniejące dzierżawy SQL
z limitem 15 sekund, bez nowych tabel, migracji lub zmian czasu wygaśnięcia.
Order jest kontrolowany przez lokalne rewizje i końcową transakcję, bez
udawania ósmej zdalnej dzierżawy.
Powiązanie z ID/rewizją intencji jest egzekwowane w koordynatorze Order;
protokół źródła nadal niesie generację, fazę, epokę, rewizję i token rundy.
Nie dodano źródłowego rejestru upoważnień intencji lub trwałej historii rund.

Każdy odczyt, PREPARE i VERIFY poprzedza i kończy kontrola dokładnej
intencji/rewizji, właściciela, wersji i generacji zamówienia, fazy, polityki,
przygotowania dowodów/blokad oraz zakresu udziału źródeł. Kontrola po RPC
obowiązuje również przy błędnej lub utraconej odpowiedzi. Stop, anulowanie,
zmiana zakresu, blokada lub kwarantanna zatrzymują dalszą ocenę.

Na rundę przypada nowy losowy token. VERIFY musi odpowiadać dokładnemu
zakresowi, akcji i tokenowi oraz pierwotnemu terminowi wygaśnięcia;
odświeżenie dzierżawy w odpowiedzi jest odrzucane. Przed kolejnymi próbami
sprawdzane jest wygaśnięcie już uzyskanych dzierżaw. Końcowa kontrola
intencji, przejęcie zadania, ocena i kontrole czasu są w jednej lokalnej
transakcji. Wygaśnięcie wykryte po ocenie wycofuje także przejęcie/próbę.
Porównuje się zegar procesu i bazy Order; nie jest to dowód synchronizacji
zegarów wszystkich usług ani gwarancja utrzymania dzierżawy do COMMIT.

Wywołania zdalne pozostają poza SQL. Runda ma najwyżej siedem odczytów
zdalnych, siedem PREPARE, siedem VERIFY i siedem RELEASE. Nie ma nowego
endpointu, harmonogramu, automatycznego retry lub widoku CC. Intencja nie
otrzymuje nowego stanu lub zdarzenia i nadal oczekuje na upoważnienie.

## Zwalnianie i niepewność

- `finally` podejmuje RELEASE dla **każdego wysłanego PREPARE**, także gdy
  źródło mogło zatwierdzić dzierżawę, a odpowiedź zaginęła lub była niepoprawna.
- Zwalniany jest pierwotny zakres/token. Stop, kwarantanna i zmiana bindingu
  nie powodują przejęcia lub zwolnienia nowej dzierżawy innej rundy.
- Odpowiedź RELEASE musi mieć zgodny zakres, akcję i status RELEASED.
  Wyjątek, odpowiedź dla innego tokenu/akcji lub status negatywny oznaczają
  UNCONFIRMED. Nie przerywają zwalniania pozostałych źródeł.
- Wynik oddziela ocenę od mapy potwierdzeń zwolnienia i stanu NOT_REQUIRED,
  RELEASED lub UNCONFIRMED. Niepewność sprzątania nie zastępuje kodu stopu
  lub błędu. RELEASED dotyczy wyłącznie tokenu tej rundy, nie wszystkich
  wcześniejszych dzierżaw lub braku innych blokad w źródle.
- Wygaśnięcie TTL nie jest potwierdzeniem RELEASE ani zatrzymania wykonawcy.
  Awaria procesu może uniemożliwić `finally`; istniejący TTL ogranicza czas
  blokowania zapisów. Nie wdrożono trwałego koordynatora rundy ani wznowienia
  po awarii. Zmiana zakresu może uniemożliwić potwierdzenie RELEASE starego
  zakresu; wynik pozostaje niepewny, bez obchodzenia kontroli źródła.

**Dzierżawy chronią obserwacje, nie upoważniają do mutacji.** Wynik
INTENT_FENCED_ASSESSED nadal może zawierać WAIT lub nieuzgodniony proces.
`activationAllowed=false`, `executionTerminated=false`; stare wykonawcze
API nadal odmawia SQL mutacji. Braku globalnych dowodów, polityki D01/P09
lub aktualnej niezależnej pieczy D09 nie można zastąpić tą rundą.

## Odbiór i granice

Raport, XML i odciski źródeł: `tmp/a43-intent-fences-130/report.json`.
Odbiór: **175 PostgreSQL PASS** (61 workflow, 15 intencji, 81 źródeł,
18 Inventory) oraz **85 unikalnych Python PASS**, bez pominięć. Kontrole
strukturalne i candidate PASS; pełna gotowość **NOT_READY / kod 2**.
Poprzednich pakietów i powtórnych przebiegów nie dolicza się do tych liczb.

Test koordynatora z bazami sześciu źródeł obejmuje rzeczywisty zapis
dzierżaw SQL, blokadę zmiany danych i usunięcie tokenów po ocenie, stopie
oraz utracie odpowiedzi po COMMIT źródła. Inventory jest w tym teście
symulowane; osobny test Inventory sprawdza jego rzeczywistą dzierżawę SQL,
konflikt między instancjami, blokadę zapisu i RELEASE. Dotychczasowy odbiór
HTTP źródeł jest regresją ich kontrolerów, nie pełną nową rundą siedmiu
dzierżaw przez sieć. To nie jest odbiór uruchomionych aplikacji dev/test.

Bez nowych migracji: **109 tabel / 1065 kolumn / 200 migracji**.
Nie zmieniono restore ani schematu; pełnego drillu 14 baz/Redis nie
powtarzano. Ostatni drill z pakietu 128 jest dowodem historycznym:
`tmp/privacy-recovery-3d360cc0/report.json`, releaseAllowed=false.
Nie odczytywano sekretów ani danych rzeczywistych środowisk.

## Punkt wznowienia

Następny niezależny zakres: odbiór tej rundy przez uwierzytelniony transport
HTTP wszystkich siedmiu źródeł z ich rzeczywistymi bazami, w tym Inventory,
z zatrzymaniem i utratą odpowiedzi PREPARE/RELEASE. Następnie rozpatrzyć
trwałe rozliczanie niepewnych rund; nie deklarować wykonania lub wznowienia
mutacji na podstawie samej obserwacji i TTL. Pełne upoważnienie i wykonanie
D02 nadal wymagają osobnego kompletnego protokołu.

D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw pozostają
odłożone. Zgodnie z poleceniem użytkownika zakres wymagający wartości lub
decyzji odkładać i podejmować następną niezależną pracę. A05, A41 oraz zmiany
samych zdjęć pozostają odłożone. Czyszczenia nie aktywować.
