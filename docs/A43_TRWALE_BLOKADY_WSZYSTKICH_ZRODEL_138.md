# A43.40 — trwałe blokady siedmiu źródeł, pakiet 138

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.16**.
Zakres lokalny; A43.40/.41 nadal otwarte, czyszczenie wyłączone.

## Zmiana

Wzorzec rejestru Returns z pakietu 137 rozszerzono na sześć pozostałych usług.
Każda ma teraz lokalny `commerce_retention_source_holds` i odczytuje go
w chronionym protokole obserwacji blokad. Order nadal ma osobne blokady
zakupu/konta z V31. Obejmuje to techniczne rejestry ośmiu uczestników,
ale nie dowodzi kompletności wszystkich rzeczywistych wniosków i spraw.

| Źródło | Migracja rejestru | Stan |
|---|---|---|
| Returns | V12 — pakiet 137 | Zachowany wzorzec i regresje |
| Payment | V25 | Nowy rejestr |
| Inventory | V16 | Nowy rejestr |
| Promotion | V14 | Nowy rejestr |
| Delivery | V14 | Nowy rejestr |
| Billing | V20 | Nowy rejestr |
| Notification | V22 | Nowy rejestr |

Sześć nowych migracji ma tę samą treść co odebrany wzorzec Returns;
QA sprawdza zgodność i odciski każdego pliku. Dodano 6 tabel / 42 kolumny
i 6 migracji. Inwentarz: **125 tabel / 1160 kolumn / 218 migracji**.
Bez nowej migracji Order. Nadal 47 mechanizmów, 6 przestrzeni Redis,
20 kategorii kopii i 26 polityk.

## Działanie i ograniczenia

Wewnętrzny adapter `CommerceRetentionSourceHolds` obsługuje siedem usług.
Wpis dotyczy stabilnego ID zakupu istniejącego w source_state, niezależnie
od aktualnej generacji/epoki. Nie jest zakładany na podstawie e-maila.
Rodzaje: dalsze zachowanie, otwarta sprawa i aktywne retry/DLQ; powód
jest kodem, bez swobodnej treści, kontaktu lub tokenu.

SQL nadaje rewizję i atomowo unieważnia źródło oraz zapisuje outbox.
Identyczne request_id jest idempotentne; konflikt, rollback i równoległe
ponowienie nie tworzą dodatkowej blokady/rewizji. Historia pozostaje
niezmienna dla zwykłego UPDATE/DELETE. Nie dodano zwalniania blokad ani TTL.

Negatywna blokada może unieważnić aktywną obserwację, ale nie usuwa tokenu
dzierżawy ani nie otwiera zapisów biznesowych. Odczyt na stary zakres
staje się STALE. Po ponownym związaniu źródła znana blokada nadal daje
BLOCKED; brak wpisu daje UNKNOWN. Żaden rejestr nie daje CONFIRMED.
W Order pozostaje hold_state oraz zgodność odcisków v1 UNKNOWN/v2 BLOCKED.

Nie dodano endpointu zapisu, przycisku CC, schedulera, nowego procesu
przyjmowania wniosków przez obsługę ani konfiguracji środowiskowej.
To adapter dla zaufanego procesu. Przyjmowanie, przypisanie i zamykanie
rzeczywistych spraw oraz odpowiedzialność obsługi wymagają dalszego zakresu.

Nowy kod wymaga odpowiedniej migracji każdego źródła. Brak tabeli nie jest
traktowany jak pusty rejestr. Nie odebrano wdrożenia dev/test ani mieszanych
wersji; migracje wykonuje się teraz wyłącznie na odizolowanych fiksturach.
Źródłowe znaczniki protokołu pozostają poza surowym automatycznym eksportem;
ocena własności opiera się na stabilnym ID zakupu i procedurze ręcznej.

## Odbiór

**112 Java (97 PostgreSQL / 15 jednostkowych) + 90 unikalnych Python PASS**, bez błędów i pominięć; 49 nowych przypadków
i pozostałe regresje. Bramki/candidate PASS; gotowość NOT_READY / kod 2.
Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-937a46c5/report.json`; 7 rejestrów
i 10 historycznych obserwacji (7 BLOCKED) zachowane bez upoważnienia.
Wyniki i odciski źródeł: `tmp/a43-all-source-holds-138/report.json`.
Testy obejmują trzy rodzaje blokad w siedmiu usługach, faktyczny HTTP po
ponownym związaniu, atomowość, rollback, konflikt, niezmienność, rewizję,
równoległe ponowienia i aktywną dzierżawę. Starsze scenariusze Returns,
odciski v1/v2, brak globalnej zgody, uprawnienia i odczyty Inventory mają regresje.

Drill 14 baz/Redis ma zachować siedem rejestrów i dziesięć historycznych
obserwacji: trzy UNKNOWN poprzedniej generacji i siedem BLOCKED. Pozostają
kwarantanna, usunięty właściciel odzyskiwania i brak aktualnego upoważnienia.
Rejestr w backupie nie potwierdza braku późniejszych wniosków poza kopią.
Aktualna niezależna głowa i piecza D09 nadal są wymagane osobno.

## Punkt wznowienia

Nie wdrażać ponownie rejestrów sześciu usług jako brakującego zakresu.
Następne: trwały proces przyjmowania i powiązania rzeczywistych spraw/blokad
z rejestrami, ich kompletność oraz wersjonowane dowody. Zwolnienie blokady
nie może wynikać z wieku, statusu COMPLETED lub braku lokalnego rekordu.
Potem pełne upoważnienie, współbieżna mutacja i odzyskiwanie D02.

Nie oznacza to zamknięcia A16, A43.40/.41 ani całego audytu. D01/P01,
D09/P03–P05/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw pozostają
odłożone. Punkty potrzebujące decyzji/wartości użytkownika odkładać
i wykonywać niezależne prace. A05, A41 i same zdjęcia A27–A31/A40 odłożone.
`activationAllowed=false`, `runtimeWired=false`, bez operacji na sekretach
lub danych rzeczywistych środowisk.
