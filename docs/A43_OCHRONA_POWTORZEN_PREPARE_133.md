# A43.40 — ochrona źródeł przed ponownym PREPARE, pakiet 133

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.11**.
Wdrożenie lokalne; A43.40/.41 nadal otwarte, czyszczenie wyłączone.

## Wdrożony zakres

Wspólny `CommerceRetentionWriteFence` utrzymuje w bazie każdego z siedmiu
źródeł minimalny znacznik tokenu: ID zamówienia, epokę bindingu, UUID tokenu
i nieodwracalny stan released. Dodano siedem migracji i 28 kolumn łącznie:
Returns V11, Payment V24, Inventory V15, Promotion V13, Delivery V13,
Billing V19 i Notification V21. Nie zmieniono formatu żądania HTTP.

RELEASE zapisuje zamknięcie tokenu i usuwa jego aktywną dzierżawę w jednej
transakcji pod dotychczasową blokadą zamówienia współdzieloną między replikami.
Dotyczy to również RELEASE, który dotarł **przed PREPARE**. Późniejszy PREPARE
lub VERIFY zamkniętego tokenu zwraca STALE. Ponowny RELEASE jest idempotentny
i nie usuwa dzierżawy o innym, nowszym tokenie.

Raz wydany token nie pozyskuje dzierżawy ponownie po wygaśnięciu lub zastąpieniu
go innym tokenem. Powtórzenie PREPARE nadal aktywnego tokenu zwraca ten sam
termin wygaśnięcia; nie odnawia go. Nowe, poprawne tokeny pozostają możliwe
w dostępnym budżecie. Dane źródła po restore w kwarantannie zwracają BLOCKED.

Limit to osiem znaczników na zamówienie i epokę, zgodnie z budżetem rund Order.
Wyczerpanie limitu dla nowego tokenu oznacza BLOCKED, także dla RELEASE —
nie udajemy potwierdzenia bez możliwości trwałego zapisania znacznika.
Istniejące znaczniki nadal można odczytywać i domykać. Triggery chronią limit,
tożsamość i nieodwracalność; nie ma TTL ani kasowania przy zmianie bindingu.
Stare epoki są zachowane, a niezgodny zakres jest odrzucany przed użyciem tokenu.

## Restore i granice wdrożenia

Backup obejmuje nową tabelę każdego źródła. Restore zachowuje znaczniki
historycznych epok, podnosi epokę źródła, nakłada dotychczasową kwarantannę
i usuwa odtworzone aktywne dzierżawy. Nie przepisuje markerów jako bieżących
i nie używa ich do wznowienia żądań. Brak schematu przy wywołaniu nowego
adaptera powoduje błąd/dostępność 503, bez przyjęcia nowej dzierżawy.

Ochrona wymaga nowych migracji i nowego adaptera we **wszystkich replikach**.
Stara replika ignorująca znaczniki może nadal przyjąć stary PREPARE. Ten
pakiet nie jest odbiorem mieszanego wdrożenia lub uruchomionych dev/test/prod.
Chroni tokeny zapisane przez nowy protokół; nie odtwarza nieznanej historii
dawnych tokenów. W istniejącej konfiguracji automatyczne działanie jest wyłączone.

Nadal brak kontrolowanego przejęcia rundy przez proces odzyskiwania. Nowy
mechanizm zabezpiecza token przed odtworzeniem, lecz nie dowodzi zakończenia
starego wykonawcy lub pełnej globalnej zgody. Rejestr Order pozostaje odczytem
diagnostycznym, bez automatycznego wysyłania RELEASE/PREPARE po awarii.

## Odbiór

Dowody: `tmp/a43-fence-replay-133/report.json`.
Odbiór: **59 PostgreSQL PASS** (35 nowych przypadków replay, 24 regresje
HTTP) i **90 unikalnych Python PASS**, bez pominięć. Kontrole strukturalne
i candidate PASS; pełna gotowość **NOT_READY / kod 2**. Drill 14 baz/Redis
PASS: `tmp/privacy-recovery-87aee146/report.json`. Zachowano 14 znaczników siedmiu źródeł wraz
z ich pierwotnymi epokami; kwarantanna źródeł i istniejąca historia Order
pozostają zachowane. releaseAllowed=false. Wcześniejszych pakietów
i powtórnych przebiegów nie dolicza się do tych liczb.
Inwentarz: **118 tabel / 1106 kolumn / 208 migracji**. Wszystkie nowe migracje
wyłącznie na fiksturach. Bez odczytu sekretów i danych rzeczywistych środowisk.

## Punkt wznowienia

Następny krok: kontrolowane przejęcie i rozliczanie niepewnej rundy Order
z ochroną przed równoległą starą instancją. Sam znacznik released i odczyt
historii nie mogą uruchomić nowego PREPARE, mutacji lub automatycznego wznowienia.
Odbiór rzeczywistych środowisk oraz pełne upoważnienie/wykonanie D02 pozostają otwarte.

D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw pozostają odłożone.
Zakresy wymagające wartości lub decyzji odkładać i wykonywać niezależne prace.
A05, A41 i zmiany samych zdjęć pozostają odłożone. A43 nadal NOT_READY;
`activationAllowed=false`, `runtimeWired=false`, `executionTerminated=false`.
