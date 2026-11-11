# A43.40 — kontrolowany przebieg oceny jednego zakupu

Stan 31.10.2026, Audit_0.1, pakiet 115, rewizja a43.40-2026-10-31.12.
Zakres odebrany lokalnie; pełne A43.40/A43.41 nadal NOT_READY.

## Przygotowanie

`CommerceRetentionWorkflow` łączy istniejące adaptery w wewnętrzny przebieg
metadanych. Nie jest komponentem Spring, endpointem, zadaniem cyklicznym ani
wykonawcą redakcji. Nie dodaje funkcji lub uprawnień do CC. Przed przyszłym
udostępnieniem potrzebne są uwierzytelnienie i odbiór właściwego obiegu poleceń.
Transporty przekazuje zaufany kod usługowy, nie parametry klienta lub adresy URL.

Przygotowanie wskazuje jedno order_id, oczekiwaną wersję Order, UUID poprzednika
lub NULL przy pierwszym zadaniu oraz UUID żądanej generacji. customer_id pochodzi
wyłącznie z bieżącego Order; gość pozostaje zakresem konkretnego zakupu.
Pod blokadą zamówienia i w jednej transakcji wykonywane są:

1. Kontrola wersji, istniejącego zadania, znanych blokad, kwarantanny, bariery
   usunięcia konta i blokady/redakcji zakupu.
2. Kontrola wskazanego poprzednika: istniejąca bieżąca generacja musi się z nim
   zgadzać; przy jej braku poprzednik musi należeć do historii tego zakupu.
   Nie wyznaczamy poprzednika na podstawie created_at/updated_at.
3. Utworzenie dokładnie jednej żądanej generacji i siedmiu trwałych komend
   bindingu. Epoki usług pochodzą z zachowanych receipts koordynatora.
4. Wycofanie całej transakcji, jeśli skutki poprzednich komend są nierozstrzygnięte.
   Nie zostaje nowa generacja ani częściowe zastąpienie wcześniejszej.

Migracja Order V32 dodaje jedno pole preparation_predecessor, z odwołaniem
wyłącznie do generacji tego samego zakupu i zakazem wskazania siebie. Trigger
zabrania zmiany tego pola. Dane historyczne pozostają NULL, bez dopisywania
niepotwierdzonej historii. Ten zapis nie jest rozproszonym checkpointem.
Ponowienie tej samej generacji sprawdza także poprzednika, wersję, właściciela,
fazę i politykę; nie resetuje prób lub epok. Zużyta/stara generacja nie jest
wskrzeszana. Bieżącej generacji nie zastępuje się poleceniem bez zgodnego
poprzednika. Dwa równoległe pierwsze polecenia mają jednego zwycięzcę.

## Obserwacje i wznowienie

Obsługiwana jest wyłącznie faza REDACTION. Nie oznacza to wykonania redakcji;
MINIMUM_REMOVAL jest odrzucane, bo nie istnieją jeszcze dowody jej przesłanek.
Można rozpocząć śledzenie aktywnego zakupu; odczyty źródeł czekają jednak na
uzgodnienie lokalnego procesu i siedem prawidłowych potwierdzeń bindingu.
Sam status terminalny starego zakupu nie tworzy zegara retencji.

Jedno observeNext zbiera najwyżej jedną obserwację, w kolejności:
Order → Payment → Inventory → Returns → Promotion → Delivery → Billing → Notification.
Postęp wynika z trwałych dowodów generacji; nowa instancja kontynuuje bez
pamięci lokalnego kursora. Payment poprzedza odbiorców jego kanonicznych zdarzeń.
Bez wymaganego potwierdzenia Billing/Notification czekają, bez wywołania transportu.
Istniejącego UNKNOWN/BLOCKED nie nadpisuje się później optymistyczną obserwacją;
potrzebne jest uzgodnienie procesu i właściwa nowa generacja.

Przed odczytem i po nim sprawdzane są właściciel/wersja/generacja/faza/polityka,
znane blokady i ich rewizje, kwarantanna, bariery konta/zakupu i bindingi.
Transport wykonuje się między zatwierdzonymi transakcjami SQL. Wywołanie całego
przebiegu wewnątrz transakcji jest odrzucane. Zmiana blokady podczas odczytu
zatrzymuje przebieg i unieważnia jego generację; stary odczyt nie staje się
bieżącą oceną. UNAVAILABLE nie tworzy dowodu i pozwala ponowić ten sam krok.
Równoległe odczyty mogą powtórzyć tę samą obserwację; niezmienne receipts
rozstrzygają duplikat lub konflikt. Nie jest to dzierżawa zdalnego wykonania.

## Ocena i ograniczenia

assessOne przejmuje wyłącznie wskazany zakup/generację/właściciela/wersję/fazę.
Przejęcie i porządkowanie wygasłych dzierżaw nie zmieniają innych zakupów.
Zachowane są limity prób, czasu zapytań, transakcji i dzierżaw. Po ocenie jest
ponowny checkpoint. Wynik i stan przepływu zawsze mają activationAllowed=false.

**Brak wpisu blokady lub komplet ośmiu obserwacji nie tworzy globalnej zgody.**
Zbieranie obserwacji nie zapisuje pozytywnych GUARD. Od pakietu 117 osobny
reviewFieldScope potwierdza wyłącznie plan pól FIELD_SCOPE_REVIEWED, z przypiętą
definicją; nie potwierdza polityki zachowania lub pozostałych GUARD. Przebieg
nie zastępuje aktualnego niezależnego rejestru, nie zwalnia zależności,
nie usuwa danych i nie przyjmuje dowodów klienta.
Checkpoint jest ochroną według stanu odczytu, a nie barierą blokującą późniejszy
zapis we wszystkich usługach. Rozproszona bariera przyszłej operacji biznesowej,
pełna polityka zachowania i dowody globalne pozostają do wdrożenia/odbioru.

Restore zachowuje niezmienne wskazanie poprzednika przy archiwalnych generacjach.
Dotychczasowa kwarantanna nadal unieważnia oceny, blokuje bindingi i zakresy;
workflow nie omija tych blokad i nie zwalnia odtworzonego środowiska do ruchu.
D17 pozostaje bez aktywacji. Rzeczywiste dev/test/prod, sekrety i katalogi E
nie zostały zmienione. D01/D09/P09, A05/A41 oraz zdjęcia zachowują dotychczasowy stan.

## Dowody

Rejestr obejmuje 106 tabel/1016 pól i 183 migracje; bez nowej tabeli.
Bieżące raporty: tmp/audit-a43-40-targeted-workflow/report.json oraz
 a43-final-review.json. Testy przebiegu używają rzeczywistego PostgreSQL i
syntetycznych odpowiedzi źródeł; osobna regresja adapterów obejmuje sześć
rzeczywistych baz źródeł i HTTP, a Inventory ma własny odbiór PostgreSQL/HTTP.
Zakresy dowodów nie są utożsamiane. Pełny drill odtwarza 14 baz i Redis,
zachowuje poprzednika generacji oraz dotychczasowe blokady, zegary i deduplikację.
Aktualne braki: [A43_ODBIOR_KONCOWY.md](A43_ODBIOR_KONCOWY.md).


Aktualizacja pakietu 117: [dowód definicji pól](A43_DOWOD_DEFINICJI_POL.md).
Jawny reviewFieldScope działa z checkpointami i nie zmienia automatycznego
observeNext, obsługiwanej fazy workflow lub activationAllowed=false.


Aktualizacja pakietu 118: osobne revalidateAndAssess weryfikuje osiem już
zapisanych obserwacji (do siedmiu odczytów zdalnych) przed oceną konkretnego
zadania. Nie zmienia limitu pojedynczego observeNext lub cached assessOne.
[Znaczenie i brak gwarancji atomowej](A43_PONOWNA_WERYFIKACJA_OBSERWACJI.md).
