# A43 — ponowna weryfikacja zapisanych obserwacji

Stan 31.10.2026, Audit_0.1, pakiet 118, rewizja a43.40-2026-10-31.15.
Zakres: jawna runda odczytów przed oceną; bez aktywacji, mutacji danych
biznesowych lub deklaracji rozproszonej bariery. A43.40/A43.41 nadal otwarte.

## Problem i zachowanie

Niezmienne obserwacje w generacji są zapisem stanu z chwili ich zebrania.
Zmiana w usłudze źródłowej tworzy zawiadomienie, lecz przed jego dostarczeniem
Order może nadal mieć wcześniej potwierdzoną obserwację. Sam odczyt cache
nie wykrywa takiej zaległej zmiany.

CommerceRetentionWorkflow.revalidateAndAssess wprowadza osobny wariant oceny:

1. Sprawdza bieżący zakup/właściciela/wersję/generację/fazę/politykę, znane
   blokady, bariery, kwarantannę i bindingi. Wymaga ośmiu istniejących obserwacji
   CHAIN oraz dostępnego budżetu i terminu; nie uzupełnia braków zamiast weryfikacji.
2. Ponownie odczytuje Order, Payment, Inventory, Returns, Promotion, Delivery,
   Billing i Notification. Najwyżej osiem odczytów źródeł, w tym siedem zdalnych.
   Używa istniejących adapterów, protokołów i kontroli kanonicznych zdarzeń.
3. Przed rundą, po każdym odczycie i po ocenie sprawdza aktualny zakres,
   rewizję dowodów oraz znanych blokad. Nieaktualny zakres/źródło, kwarantanna,
   brak zależności albo niedostępność zatrzymują przebieg; nie jest zwracana
   stara ocena jako wynik udanej ponownej weryfikacji.
4. Jeśli podczas rundy dodano nowy dowód, zwraca RETRY_REVALIDATION i pozostawia
   prawidłową generację do następnej, jawnej rundy. Konflikt zmienionej treści
   istniejącego dowodu nadal unieważnia generację zgodnie z niezmiennością.
5. Dopiero po odczytach przejmuje dokładnie to zadanie przez CAS zakresu
   i proof_revision, a następnie ocenia wszystkie wymagane dowody i GUARD.
   Nowy wariant nie potwierdza brakujących GUARD.

observeNext nadal zbiera najwyżej jedną obserwację na krok. assessOne pozostaje
wariantem oceny zapisanych dowodów. Nowa runda jest osobnym, jawnym wywołaniem,
bez harmonogramu, endpointu, widoku CC lub nadawania nowych uprawnień.
Nie zmieniono obsługiwanej fazy workflow: tylko REDACTION, jako faza oceny,
nie wykonanie redakcji. Nie przyjmuje się adresów URL lub dowodów klienta.

## Transakcje i odzyskiwanie

Odczyty HTTP odbywają się poza transakcją SQL. Wywołanie całego przebiegu
wewnątrz transakcji jest odrzucane. Transporty dostarcza zaufany kod; istniejące
klienty mają własne limity połączenia/odczytu. Limit liczby wywołań nie jest
nowym, wspólnym deadline całej rundy. Wewnątrz nie ma automatycznych ponowień.

Aktywna dzierżawa innego wykonawcy, wyczerpane osiem prób oraz przyszły termin
po błędzie pozostają chronione; nie zeruje się licznika ani okresu oczekiwania.
Dla DUE_FOR_REVIEW/BLOCKED/REVIEW można jawnie zażądać ponownej oceny mimo
przyszłej daty cache, po wykonaniu rundy. Przejęcie dotyczy jednego dokładnego
zakupu/generacji/wersji/właściciela/fazy i sprawdzonej rewizji dowodów.

Dzierżawa powstaje dopiero po zakończeniu odczytów i zachowuje 15 s według
bieżącego czasu DB. Jej termin pobrania jest ustawiany na początek zapytania,
aby awaria nie pozostawiła odzyskiwania zablokowanego dawną przyszłą datą cache.
Zmiana dowodów lub przejęcie zadania przez innego wykonawcę przed CAS nie daje
prawa do nadpisania jego dzierżawy. Nie są zmieniane inne zakupy.

## Granica spójności

**Runda nie jest atomową migawką ani barierą zapisów w wielu usługach.**
Zapis dokonany w usłudze po jej ponownym odczycie może jeszcze nie być widoczny
w Order. Test na rzeczywistej bazie Delivery pokazuje taką zmianę podczas
późniejszego odczytu Billing. Wynik pozostaje oceną według kolejnych odczytów,
z activationAllowed=false; nie jest dowodem prawa do mutacji.

Nie zapisuje się nowego certyfikatu świeżości lub czasu retencji; nie przesuwa
się źródłowych zegarów, nie tworzy się nowej tabeli ani kolumny. Ponowne zgodne
odczyty są idempotentne. Nadal potrzebne są rozproszone bariery przyszłych
operacji, aktualny niezależny dziennik, komplet globalnych GUARD i właściwa
polityka zachowania. D01/D09/P09 oraz D17 pozostają bez nowych rozstrzygnięć.

## Odbiór

Nowe testy obejmują odczyt ośmiu obserwacji, niekompletny cache, niedostępność,
równoległe dodanie dowodu, zmianę blokad, aktywną/nową dzierżawę innego
wykonawcy, backoff, limit prób, odzyskanie po awarii, niezgodnego właściciela,
fazę i kwarantannę. Sprawdzają siedem wywołań zdalnych, brak dodatnich GUARD
z samej rundy oraz brak przejmowania obcych zadań.

Sześć rzeczywistych baz źródeł potwierdza wykrycie zmiany danych przed
odczytem, mimo niedostarczonego zawiadomienia. Istniejący test HTTP z rzeczywistą
autoryzacją obejmuje też pełną rundę. Inventory w tych scenariuszach używa
syntetycznej odpowiedzi istniejącego protokołu; nie jest to nowy pełny odbiór
Inventory przez HTTP. Test późnego zapisu potwierdza opisany brak globalnej
migawki, zamiast przedstawiać sekwencję jako gwarancję spójności atomowej.

Dowody: tmp/audit-a43-40-revalidation/report.json i a43-final-review.json.
Rejestr pozostaje 106 tabel/1017 pól, 185 migracji. Nie powtarzano pełnego
drillu 14 baz/Redis; jego ostatni dowód jest w pakiecie 117:
tmp/privacy-recovery-15fc93b4/report.json. Schemat i narzędzie restore bez zmian;
nowy test sprawdza odmowę rundy przy odtworzonej kwarantannie bindingów.
Nie zmieniono rzeczywistych baz, sekretów/SOPS, środowisk lub katalogów E.
A05/A41 i zdjęcia nadal odłożone.
