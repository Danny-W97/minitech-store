# A43 — pytania do uzupełnienia

Stan na **30.10.2026**, branch `Audit_0.1`. Pytania wynikają z braków
w [obowiązującym rejestrze decyzji](A43_POZOSTALE_PRACE_I_DECYZJE.md).
Odpowiadamy kolejno w czacie; asystent zapisuje odpowiedzi. Nie trzeba
edytować pliku ani ponownie wybierać zatwierdzonych okresów i opcji.

Oznaczenia P01–P09 służą wyłącznie identyfikacji pytań, nie są etapami audytu.
Pytania nie zlecają aktywacji, kasowania, generowania kluczy lub nadawania
uprawnień. Nie podawać sekretów, wartości wskaźników ani danych klientów.
Można odpowiedzieć „do ustalenia”; zależny zakres pozostanie wtedy otwarty.

## Wynik bieżącej rundy pytań

**Zapisano osiem odpowiedzi; P05 nie zadano, ponieważ zależy od wyboru
narzędzia w P04.** Nie ma teraz kolejnego niezależnego pytania z tej listy.
Powrót do pozostawionych decyzji wymaga nowych ustaleń; nie ponawiać ich
automatycznie. Odpowiedź „do ustalenia” nie oznacza gotowej polityki.

| Pytanie | Zapisany wynik | Co pozostaje |
|---|---|---|
| P01 / D01 | B — wyjątki i wymagane okresy finansowe do ustalenia | Dane źródłowe chronione przed usunięciem. |
| P02 / D02 | A — 90 dni od faktycznej, potwierdzonej redakcji, z zaakceptowanymi zabezpieczeniami | Decyzja uzupełniona; mapowanie zależności, implementacja i dowody. |
| P03 / D09 | E:\Klucze\Kopia3 — dodatkowa kopia lokalna | Niezależny nośnik/host wybrać później; nie potwierdzono żadnej konfiguracji kopii. |
| P04 / D09 | B — program/usługa dla SAFE do ustalenia | Narzędzie i faktyczna piecza wskaźników nadal otwarte. |
| P05 / D09 | Nie zadano — zależne od P04 | Wrócić po wyborze narzędzia; nie przypisano odpowiedzi użytkownikowi. |
| P06 / D14 | B — według wiedzy użytkownika tylko tryb wbudowany CC | Weryfikacja konfiguracji i historii osobno dla dev/test. |
| P07 / D15, D18 | Właściciel operator-1, zastępca operator-2, dev/test | Weryfikacja kont, rzeczywistej obsady i dostępu. |
| P08 / D15 | Dodatkową ocenę prowadzi tylko właściciel operator-1 | Przygotowanie kontroli dostępu i zapisu oceny; bez wymogu drugiej osoby. |
| P09 / D07, D08, D14, D15 | C — wyjątki nadal do ustalenia | Polityki tych zasobów nie są kompletne do aktywacji. |

Ta runda nie zmienia D17: można przygotowywać niezależne prace lokalne,
bez nowej aktywacji. A43.40/A43.41 i wcześniej odłożone zakresy pozostają
otwarte zgodnie z rejestrem. D12 wymaga najpierw rozpoznania konkretnych kopii,
a nie kolejnego pytania o okres dla całego katalogu.

**Postęp techniczny po tej rundzie:** rewizja A43.40 `.2` przeniosła powyższe
decyzje do `retention-decisions.json`, macierzy i walidatora. 15 testów bramki
przeszło; kontrola końcowej gotowości pozostaje NOT_READY. Nie zmieniono
odpowiedzi, nie rozwiązano za użytkownika P01/P03/P04/P05/P09 i nie
aktywowano czyszczenia. Dalsze mechanizmy i dowody nadal do przygotowania.

**Dalszy postęp `.3`:** mapowanie 127 pól i kwalifikator D02 odebrane lokalnie;
trwałe źródła dowodów, migracje i workery nadal do wdrożenia.

**Dalszy postęp `.4`:** dodano trwałe zadania i metadane dowodów D02 oraz
ograniczone wykonywanie oceny; migracja sprawdzona wyłącznie w jednorazowym
PostgreSQL. Brak podłączenia do runtime, zaufanych źródeł pozostałych usług,
integracji restore i workera danych biznesowych. Otwarte decyzje P01/P03/P04/P05/P09
pozostają bez zmian; nie ponawiano pytań ani nie nadano uprawnień.

**Postęp 31.10.2026 `.1`:** transport unieważniania dla siedmiu usług jest
przygotowany i domyślnie wyłączony. Istniejący izolowany replay po restore
unieważnia odtworzone oceny wszystkich kont i gości, także dla pustego dziennika,
z zachowaniem dowodów i danych biznesowych. Pełny drill przeszedł; nie zwalnia
kwarantanny. Trwałe źródłowe outboxy, świeże dowody i właściwe workery nadal
do wdrożenia. Nie zmieniono wcześniejszych decyzji ani otwartych parametrów.

## D02-F01 — uzupełnienie podczas wdrażania

Pytanie: jak potraktować historyczne `delivery_date`, `delivery_slot`
i `pickup_location`? **Odpowiedź użytkownika: usuwać te trzy wartości
podczas redakcji po 365 dniach, zachowując ID, kwoty, ilości, statusy
i wymagane powiązania.** Zapisano 30.10.2026 w maszynowej rewizji decyzji
`a43-decisions-2026-10-30.2`; mapa ma teraz 20 pól redakcji, 91 minimum
i 16 protokołu, bez pól oczekujących na decyzję. Pytanie rozstrzygnięte.
Bez zmiany ochrony D01 i bez uruchamiania czyszczenia.

## P01 / D01 — wymagane zachowanie danych finansowych

**Odpowiedź użytkownika: B — nadal do ustalenia; zachować ochronę tych
danych przed usunięciem.** Zapisano 30.10.2026. Pytanie otrzymało odpowiedź,
ale polityka D01 pozostaje niekompletna; nie wracać do tego samego pytania
bez nowych ustaleń lub zmiany decyzji użytkownika.

**Czy jest już dostępna zatwierdzona polityka wyjątków i dłuższych okresów
dla faktur, korekt oraz rejestrów płatności/zwrotów?**

| Opcja | Znaczenie |
|---|---|
| A — tak, podam politykę lub jej niepoufny opis | Dla każdej klasy podać cel zachowania, wymagany okres, początek liczenia, wyjątki oraz rolę zatwierdzającą. Jeśli reguły różnią się między środowiskami lub danymi syntetycznymi i rzeczywistymi, wskazać różnice. |
| B — nadal do ustalenia | Zachować uzgodnioną ochronę przed usunięciem. Planowane 30 dni od uzgodnienia całego procesu i sprawdzanie co 7 dni nie stają się zgodą na usuwanie źródłowych dokumentów. |

Nie trzeba ponownie wybierać 30 dni ani cyklu 7 dni. Nie zastępować brakującej
polityki finansowej przykładową liczbą dni.

## P02 / D02 — końcowy okres zachowanego minimum handlowego

**Odpowiedź użytkownika: A — konkretny okres.** Zapisano 30.10.2026.
**Doprecyzowanie: 90 dni od faktycznego, potwierdzonego zakończenia
redakcji.** Minimum pozostaje jeszcze przez 90 dni; opóźnienie redakcji
przesuwa początek tego okresu.

**Wyjątki zaakceptowane:** usuwanie blokują otwarte lub wznowione sprawy
i spory, wymagane dalsze zachowanie oraz zależności potrzebne chronionym
dokumentom D01, odtworzeniu, ponowieniom lub deduplikacji. Brak dowodu
zwolnienia zależności blokuje usuwanie; niejasne przypadki do ręcznej oceny.
Użytkownik nie zna dodatkowych wyjątków — do weryfikacji przy wdrażaniu.
Chronionych znaczników i rejestru usunięć nie obejmuje okres 90 dni.
**P02 uzupełnione na poziomie decyzji; wykonanie i dowody nadal wymagane.**
Sam upływ okresu nie uruchamia usuwania.
Nie przypisywać minimum automatycznie
365 dni — wcześniejszy próg dotyczy redakcji danych osobowych i treści.
Do wdrożenia reguły i sprawdzenia zależności minimum pozostaje chronione.

**Co ma kończyć przechowywanie ID, kwot, ilości, statusów i powiązań,
pozostających po redakcji danych osobowych i treści?**

| Opcja | Znaczenie |
|---|---|
| A — konkretny okres | Podać liczbę dni i zdarzenie rozpoczynające liczenie oraz wyjątki wymagające dalszego zachowania. Doprecyzować, czy okres biegnie od końcowego uzgodnienia łańcucha, czy dopiero od redakcji po 365 dniach. |
| B — udokumentowany warunek zakończenia | Opisać warunek i dowód, np. zakończenie konkretnego celu oraz rozliczenie zależnych odtworzeń i ponowień; sam opis „niepotrzebne” nie pozwala przygotować kwalifikacji. |
| C — końcowy okres nadal do ustalenia | Wdrożenie redakcji po 365 dniach można przygotowywać niezależnie; zachowanego minimum nie usuwać na podstawie tego progu. |

To pytanie nie zmienia okresu 365 dni, uzgodnionego zakresu zakupów ani
odrębnych zasad D01, deduplikacji i rejestru usunięć. Odpowiedź A/B wymaga
jeszcze sprawdzenia zależności przed zaprojektowaniem usuwania minimum.

## P03 / D09 — kopia rejestru poza dyskiem E

**Odpowiedź użytkownika: A — poda lokalizację na innym nośniku lub hoście
dla dev/test.** Zapisano 30.10.2026. Następnie wskazano
**`E:\Klucze\Kopia3`**. Użytkownik następnie potwierdził: **zwykły folder
na E — zapisać jako dodatkową kopię lokalną; lokalizację niezależną ustalić
później**. P03 ma odpowiedź, ale wymaganie niezależnej kopii pozostaje
otwarte. Nie ponawiać pytania o lokalizację bez nowych ustaleń użytkownika.
Podział dodatkowej kopii lokalnej na dev/test pozostaje do przygotowania
przy konfiguracji. Nie uznawać żadnej z tych kopii za skonfigurowaną lub
zweryfikowaną; nie tworzono katalogów i nie kopiowano danych.

**Gdzie ma znajdować się dodatkowa kopia zaszyfrowanego i podpisanego
rejestru usunięć na innym nośniku lub hoście?**

- A — podam ścieżkę lub nazwę chronionego zasobu, z rozdzieleniem dev/test.
- B — lokalizacja nadal do ustalenia; `E:\Klucze\Kopia` pozostaje kopią lokalną.

Potrzebna jest tylko lokalizacja/oznaczenie zasobu. Inny folder na E nie
rozwiązuje utraty tego dysku. Kopii nie łączyć ze zwykłymi backupami aplikacji
ani z prywatnymi kluczami. Nie trzeba ponownie podawać wybranych ścieżek na E.

## P04 / D09 — konkretne narzędzie dla SAFE

**Odpowiedź użytkownika: B — narzędzie nadal do ustalenia.** Zapisano
30.10.2026. SAFE pozostaje nazwą sejfu/folderu; nie przypisano programu
ani usługi. Nie ponawiać pytania bez nowych ustaleń użytkownika.

**Jaki program lub usługa będzie przechowywać niezależne wskaźniki bieżącej
wersji rejestru w sejfie/folderze SAFE, osobno dla dev i test?**

- A — podam nazwę istniejącego lub wybranego narzędzia i niepoufne oznaczenia wpisów dev/test.
- B — narzędzie nadal do ustalenia.

SAFE jest już zapisaną nazwą sejfu/folderu. Nie podawać loginu, hasła,
tokenów, zawartości sejfu ani wartości wskaźnika.

## P05 / D09 — obsługa aktualizacji niezależnego wskaźnika

**Status: zależne od nierozstrzygniętego P04 — pytania teraz nie zadajemy.**
Nie jest to odpowiedź użytkownika na P05 ani potwierdzenie gotowej procedury.
Wrócić do P05 po wyborze narzędzia; obecnie przejść do niezależnego P06.

**Po wyborze narzędzia P04: jak organizacyjnie obsługiwać aktualizację
wskaźnika przy każdej zatwierdzonej zmianie rejestru?**

| Opcja | Znaczenie |
|---|---|
| A — kontrolowana obsługa ręczna przez właściciela/zastępcę | Przygotować instrukcję aktualizacji i potwierdzenia zgodności, z bezpieczną obsługą przerwanej operacji. Nie uznawać zapisu za potwierdzony bez aktualnego niezależnego wskaźnika. |
| B — integracja z wybranym narzędziem | Najpierw zweryfikować możliwości narzędzia, autoryzację i sposób potwierdzania aktualności; implementację zaplanować na tej podstawie. |
| C — sposób obsługi do ustalenia | Gotowość rejestru i restore pozostaje niepotwierdzona. |

Pytać dopiero po ustaleniu P04. Wykonalność, kolejność zapisu i zachowanie
po awarii są zadaniem technicznym; użytkownik nie musi projektować protokołu.
Żaden wariant nie pozwala zastąpić aktualnego wskaźnika kopią ze starego backupu.

## P06 / D14 — użycie osobnego endpointu AI

**Odpowiedź użytkownika: B — według jego wiedzy tylko tryb wbudowany CC;
konfigurację i historię trzeba zweryfikować.** Zapisano 30.10.2026 dla
zakresu dev/test, bez wskazania różnic między środowiskami. To aktualizuje
wcześniejsze „do ustalenia” w zakresie deklaracji użytkownika, nie jest
dowodem braku wysyłki historycznej lub braku osobnego endpointu w runtime.
Nie wyłączono adaptera i nie zmieniono konfiguracji AI.

**Czy w dev i test używano lub używa się osobnego endpointu AI asystenta CC,
zewnętrznego albo lokalnego LLM działającego jako osobny proces?**

- A — tak; podać osobno dla środowisk niepoufną nazwę dostawcy/usługi, oznaczenie endpointu i informację „obecnie/historycznie”.
- B — według mojej wiedzy tylko tryb wbudowany CC, bez wysyłki do osobnego endpointu; zweryfikować konfigurację i historię.
- C — nadal do ustalenia.

Deklaracja nie zastępuje sprawdzenia środowiska. Po potwierdzeniu odbiornika
zebrać jego zasady zachowania treści, logów, dalszego wykorzystania i obsługi
wniosków. Brakujące ustawienia zależne od użytkownika pokażemy wtedy jako
konkretne pytania. Nie przesyłać klucza API ani adresu URL zawierającego sekret.

## P07 / D15 i D18 — przypisanie ról do uprawnionej obsługi

**Odpowiedź użytkownika: „tak” do wskazanego przykładu przypisania.**
Zapisano 30.10.2026 jako przyjęte oznaczenia organizacyjne:
**właściciel: operator-1; zastępca: operator-2; środowiska: dev i test**.
Nie jest to potwierdzenie istnienia kont, tożsamości dwóch różnych osób ani
ich uprawnień; sprawdzić to podczas wdrażania. Nie utworzono kont i nie nadano
praw. P07 uzupełnione w zakresie oznaczeń organizacyjnych.

**Jakie niepoufne oznaczenia osób lub istniejących kont mają odpowiadać rolom
„właściciel” oraz „zastępca” w dev/test?**

Format: `właściciel: …; zastępca: …; środowiska: …`.
Można podać oznaczenia organizacyjne zamiast danych osobowych. Jeśli faktyczna
obsada lub konta nie są jeszcze ustalone, wskazać brak dla danej roli.

Role i wspólna obsługa alarmu od początku są już wybrane. Odpowiedź nie nadaje
nowych praw; istniejący dostęp i możliwość zatrzymania workera wymagają
weryfikacji. Nie wywodzić uprawnień ani obsady z nazwy użytkownika Windows.

## P08 / D15 — dodatkowa ocena niejednoznacznych spraw

**Odpowiedź własna użytkownika: wyłącznie właściciel — operator-1,
w dev i test.** Zapisano 30.10.2026. To obowiązujący wybór zamiast
proponowanych wariantów A–C poniżej: nie wymagać drugiego oceniającego
ani nie przekazywać tej oceny zastępcy. W razie niedostępności właściciela
lub niewystarczających dowodów sprawa pozostaje nierozstrzygnięta.
Zachować ręczną ocenę, zapis wyniku i weryfikację uprawnień właściciela.
Wspólna obsługa alarmów D18 pozostaje bez zmian. Nie nadano nowych praw.

**Kto ma prowadzić dodatkową ocenę dowodów własności rozmowy lub treści
mogącej zawierać dane innych osób?**

Proponowane pierwotnie opcje (użytkownik wybrał własną odpowiedź powyżej):

- A — druga uprawniona osoba w modelu właściciel/zastępca; przy braku niezależnego oceniającego sprawa pozostaje oczekująca.
- B — inna wskazana uprawniona rola; podać jej niepoufne oznaczenie i sposób przekazania sprawy bez publikowania dowodów w repozytorium.
- C — obsługa dodatkowej oceny nadal do ustalenia; niejednoznacznych spraw nie rozstrzygać automatycznie.

Nie zmienia to wymagania indywidualnego dowodu rozmowy, ręcznego ustalenia
zakresu ani okresów 90 dni dla dowodów i 365 dni dla minimalnego zapisu sprawy.

## P09 / D07, D08, D14 i D15 — konkretne wyjątki zachowania

**Odpowiedź użytkownika: C — wyjątki nadal do ustalenia.** Zapisano
30.10.2026. Nie oznaczać polityk tych zasobów jako kompletnych do aktywacji;
nie domniemywać braku wyjątków i nie zmieniać uzgodnionych okresów/limitów.
Dotychczasowa ochrona aktywnych/spornych spraw, zależności i istniejących
blokad pozostaje. Nie ponawiać pytania bez nowych ustaleń użytkownika.

**Czy są już określone dodatkowe wyjątki wymagające zachowania logów,
danych monitoringu, kopii poczty Mailpit lub materiałów spraw ponad wybrane
okresy/limity?**

| Opcja | Znaczenie |
|---|---|
| A — tak, podam wykaz | Dla każdego wyjątku: klasa danych i środowisko, przyczyna/cel, zakres, okres lub warunek zwolnienia, rola zatwierdzająca i miejsce kontrolowanego zapisu blokady. Bez treści spraw i danych osób. |
| B — nie znam dodatkowych wyjątków | To deklaracja wiedzy do weryfikacji; nie znosi już uzgodnionej ochrony aktywnych/spornych spraw, zależności i istniejących blokad. |
| C — wyjątki do ustalenia | Nie uznawać polityki zależnego zasobu za kompletną. |

Reguły narzędzi mają różną semantykę. Po wskazaniu wyjątków sprawdzić,
czy konkretny mechanizm potrafi je respektować; brak tej możliwości wymaga
rozwiązania przed aktywacją. Nie przedłużać automatycznie wszystkich danych
i nie zakładać, że natywna rotacja lub retencja ma już obsługę blokad.

## Prace techniczne poprzedzające ewentualne dalsze pytania

| Obszar | Co ustalić ze źródeł i środowiska zamiast pytać użytkownika o projekt kodu |
|---|---|
| D04/D05 — Redis | Rzeczywiste instancje dev/test, streamy, grupy, postęp i pending oraz potrzeby ponowień. Dla invoice-events ocenić działanie bez planowanych odbiorców. |
| D06 — stany techniczne | Przypisać uzgodnione warunki zakończenia do pól, trwałych skutków, ponowień i DLQ. |
| D07/D08/D14 — magazyny | Zweryfikować backendy, wersje, odbiorników i kopie oraz możliwość egzekwowania wybranych limitów i wyjątków. |
| D11 — klucze | Rozpoznać formaty i zależności szyfrogramów oraz przygotować osobne plany i testy odtworzenia; nie prosić o treść kluczy. |
| D12 — dodatkowe kopie | Najpierw zinwentaryzować konkretne artefakty. Pytanie o ich retencję przygotować dopiero z listą klas i ograniczeń; nie przypisywać TTL pustemu katalogowi. |
| D15/D18 — obsługa | Zweryfikować dostęp, kontrolowany zapis blokad i ocen, lokalne alarmy i zatrzymanie konkretnego workera. |

## Decyzje, których teraz nie otwieramy ponownie

- D03: SQL 7 dni; D04/D05: progi streamów 7/30 dni i przyjęte zamierzenie odbiorców.
- D07: logi maksymalnie 30 dni i 100 MiB na źródło.
- D08: Prometheus 30 dni i alarm 1 GiB; wycofane dashboardy 90 dni; Alertmanager 5 dni według natywnej semantyki.
- D14: Mailpit maksymalnie 30 dni i 1000 wiadomości na instancję.
- D15: indywidualne wnioski czatu, E:\Klucze\Rejestr, okresy 90/365 dni.
- D18: lokalne panele/statusy i raport/log sprawdzane ręcznie; obie role od pierwszego alarmu.
- D10/D13/D16: odłożone. A05, całe A41 i zmiany samych zdjęć A27–A31/A40 nie są wznawiane przez tę listę.
- D17: lokalny odbiór bez nowej aktywacji. Nie pytamy teraz o uruchomienie czyszczenia.

**Aktualizacja wykonania 31.10.2026 `.8`:** lokalne zegary Order/Inventory
nie wymagają nowego wyboru okresu. Brak daty lub źródłowej rezerwacji nadal
nie jest potwierdzeniem; Inventory wymaga jeszcze zaufanego odbioru w Order.
Pozostałe sześć źródeł oraz globalne blokady/rejestr wymagają implementacji.
P01/P03/P04/P05/P09 i dowody środowiskowe pozostają otwarte. Nie ponawiamy
odłożonych pytań i nie uruchamiamy nowego czyszczenia.

A43.40/A43.41 pozostają otwarte do uzupełnienia wymaganych decyzji,
wdrożenia i zebrania dowodów odbioru. Przygotowanie pytań nie zamyka tych etapów.

**Potwierdzenie 31.10.2026 przy poleceniu ukończenia:** P01 — nadal chronić
finanse do ustalenia; P03/P04/P05 — nadal do ustalenia; P09 — nadal do
ustalenia. Trzy odpowiedzi zachowują wcześniejsze decyzje. Nie pytamy o nie
ponownie w tej samej próbie. Odbiór Inventory w Order wdrożony lokalnie;
pozostały kod i wymagane dowody opisuje A43_ODBIOR_KONCOWY.md.
