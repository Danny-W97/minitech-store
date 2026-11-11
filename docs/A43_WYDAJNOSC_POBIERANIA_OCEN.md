# A43 — pobieranie ocen retencji na dużych zbiorach

Stan 31.10.2026, Audit_0.1, pakiet 116, rewizja a43.40-2026-10-31.13.
Zakres: pobieranie i dzierżawy metadanych ocen. A43.40 i A43.41 nadal otwarte.

## Zmiana

Dotychczasowe zapytanie łączyło wszystkie bieżące oceny z nagłówkami blokad
i dopiero filtrowało termin lub zmianę rewizji. Brak pracy nie gwarantował
małego odczytu. Przy wielu ocenach DUE_FOR_REVIEW i przyszłych datach zapytanie
musiało przeglądać duży zbiór, aby odnaleźć pojedynczy zmieniony zakres.
Podobny koszt mógł mieć wybór wygasłych dzierżaw po wyczerpaniu prób.

Nowy wybór globalny rozdziela cztery powody pobrania:

1. Zwykłe WAITING_PROOF/WAITING_AGE/FAILED/LEASED z osiągniętym terminem.
2. Zakupy klienta, dla których zapisana rewizja jest za bieżącą rewizją zakresu.
3. Zakup ze zmienioną rewizją własnego zakresu.
4. Historyczne zadania z nieodczytaną rewizją -1.

Nagłówki z rewizją zero nie uczestniczą w wyszukiwaniu zmian. Rewizje są
monotoniczne zgodnie z chronionym protokołem V31: przygotowanie i ocena
zapisują odczytaną wartość, a blokada lub kwarantanna ją zwiększa. Nie ma
interpretowania braku wpisu jako pozytywnej zgody na usunięcie.

Dla jednego zmienionego zakresu klienta rozpatrywane jest okno do 16 zadań,
od najstarszej nieodczytanej rewizji, potem daty i ID. Umożliwia pomijanie
rekordów zablokowanych przez inne transakcje. Globalny wybór porównuje
kandydatów czterech ścieżek; nie obiecuje pełnej kolejności wszystkich dat
w całym zbiorze zmienionych rewizji. Nową dzierżawę otrzymuje jedno zadanie.

Każda ścieżka blokuje najwyżej jednego kandydata z SKIP LOCKED: do czterech
kandydatów, z których tylko jeden jest przejmowany. Pozostałe blokady kończą
się z transakcją. Osobne, istniejące porządkowanie może zablokować/zmienić
najwyżej 25 wyczerpanych dzierżaw; łącznie do 29 wierszy zadań w transakcji.
To nie oznacza 29 wykonań oceny ani pobrania danych biznesowych.

Wybór globalny korzysta ze stabilnego czasu początku zapytania, co pozwala
użyć zakresu indeksu dla przyszłych terminów. Termin osiągnięty dopiero
podczas zapytania może wejść w następnym wywołaniu. Dzierżawa 15 s, sprawdzanie
jej własności i zegary danych nadal używają bieżącego czasu DB. Okresy 365/90
dni, przyszłe daty dowodów i budżet ośmiu błędów pozostają bez zmian.
Punktowe pobranie przebiegu jednego zakupu zachowuje dokładną tożsamość,
wersję/generację/fazę i dotychczasową walidację czasu; nie przejmuje innych zakupów.

## Migracja i koszt indeksów

Order V33 dodaje pięć indeksów częściowych: zmienione nagłówki, zwykłe terminy,
rewizje zakupów klienta, nieodczytane rewizje i wyczerpane dzierżawy. Usuwa
zastąpiony szeroki idx_retention_hold_claim. Nie dodaje tabel lub kolumn,
nie zmienia retencji, danych, TTL ani ustawień środowiska.
Indeksy zajmują miejsce i zwiększają koszt części zapisów; ten kompromis
zmniejsza odczyt przy pobieraniu pracy. Nie deklarujemy ogólnego spadku RAM.
Rejestr: 106 tabel/1016 pól, 184 migracje.

## Odbiór lokalny i granice

Fixture zawiera 100 000 zadań, 100 000 zamówień i 199 901 nagłówków zakresów.
Syntetyczne zamówienia są ładowane w partiach po 500, z działającymi barierami
konta; nie wyłączano triggerów ani nie zwiększano limitów PostgreSQL.
Pierwszy import całego zbioru w jednej transakcji wyczerpał pulę blokad
advisory — log zachowuje ten wynik, a fixture poprawiono przez podział importu.

Porównanie dotyczy tego samego zbioru: poprzedniego zapytania z dokładnym
zestawem indeksów V32 oraz nowego zapytania/indeksów V33. Przełączenie indeksów
jest wykonywane wyłącznie we własnej bazie testowej, w transakcji wycofywanej
przed rzeczywistymi próbami claim. Nie dotyczy środowisk użytkownika.

EXPLAIN ANALYZE/BUFFERS i plany JSON obejmują pustą kolejkę, pojedynczą zmianę
klienta oraz przyszłe zwykłe oczekiwania i wygasanie dzierżaw. Weryfikacja
licznika pracy planu nie opiera się na sztywnym czasie zależnym od obciążenia WSL.
Licznik sumuje zwrócone i odfiltrowane wiersze węzłów razy liczbę iteracji;
nie jest liczbą unikalnych rekordów. Końcowe wartości i czasy są w raporcie,
nie stanowią obietnicy SLO dla innej instalacji.

Końcowa próba porównawcza dała następujące wyniki (pojedynczy lokalny pomiar):

| Przypadek | Licznik pracy V32 | Licznik pracy V33 | Czas V32 / V33 |
|---|---:|---:|---:|
| Brak pracy | 500000 | 2 | 483.336 / 0.847 ms |
| Jedna zmiana klienta | 500002 | 21 | 477.556 / 1.031 ms |
| Wygasanie przy przyszłych oczekiwaniach | 100000 | 0 | pomiar i plan w JSON |

Osiem scenariuszy PostgreSQL sprawdza też równoległe przejęcia jednego
zmienionego klienta, brak ponownego wybudzania odczytanych rewizji, znaczniki
historyczne, jednorazowe uwzględnienie kwarantanny, punktową kontrolę zakresu,
limit czterech blokad kandydatów i ich zwolnienie po commit. Regresja obejmuje
oceny, znane blokady, workflow i lokalny dowód Order.

**Liczba dodatnich nagłówków i rzeczywistej zaległej pracy nadal może zwiększać
koszt.** Okno 16 nie jest stałym limitem pracy całej bazy ani gwarancją postępu,
gdy wszystkie rekordy okna są zablokowane. Wtedy wywołanie może nie przejąć
pracy, a następne może spróbować ponownie. Pozostają testy wielu dodatnich
zakresów, długich blokad, docelowego obciążenia i pozostałych składników A43.
Pełnego A43.41 nie oznaczono jako ukończonego.

Raport: tmp/audit-a43-40-claim-scale/report.json, plany claim-scale.json,
maszynowy stan a43-final-review.json. Brak aktywacji D17, endpointu/zmian CC
lub nowych operacji na danych biznesowych. D01/D09/P09 i odłożone zakresy
pozostają bez nowych rozstrzygnięć. Nie modyfikowano rzeczywistych sekretów,
SOPS, baz ani katalogów E.

Pełny drill 14 baz i Redis pozostaje dowodem pakietu 115:
tmp/privacy-recovery-cf1c116d/report.json. W pakiecie 116 nie powtarzano tego
drillu. V33 sprawdzono na izolowanym PostgreSQL; mechanizm restore i schemat
pól danych nie zmieniły się. Nie przedstawiamy wcześniejszego drillu jako
nowego odbioru całej aplikacji z V33.
