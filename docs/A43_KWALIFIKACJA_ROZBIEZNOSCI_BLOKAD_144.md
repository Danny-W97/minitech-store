# A43.40 — kwalifikacja rozbieżności blokad, pakiet 144

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.22**.
Zakres lokalny; A43.40/.41 otwarte. Czyszczenie pozostaje wyłączone.

## Kwalifikator

`CommerceRetentionHoldRepairAssessment.assess` ocenia lokalną historię
jednego dokładnego zakupu, wniosku i referencji sprawy. Odczytuje siedem
planowanych źródeł, potwierdzenia doręczeń i do 56 prób porównania z 143,
czyli do 8 na źródło. Blokada rodzica jest wspólna z zapisującymi tę
historię, dzięki czemu wynik opisuje jeden lokalny stan. Nie ma RPC,
DML, schedulera, nowego endpointu lub uprawnień operatorskich.
Zewnętrzna transakcja wywołującego jest odrzucana.

| Stan źródła | Znaczenie |
|---|---|
| NO_HISTORICAL_RECEIPT | Brak ACK z 140; wymagane kontrolowane doręczenie, bez domyślnego tworzenia bindingu. |
| PROBE_REQUIRED | ACK istnieje, ale brak prób porównania. |
| PENDING_REVIEW | Co najmniej jedna próba nie ma wyniku. Również starsze PENDING blokuje wskazanie kandydata po nowszym zakończonym odczycie. |
| RECHECK_REQUIRED | Wynik niepewny albo obserwacja MATCH/MISSING jest za stara lub nadmiernie przyszła. |
| MATCH_AS_OF_PROBE | Ostatnia obserwacja dopasowała blokadę; nie potwierdza jej dalszego istnienia ani zamknięcia wcześniejszych rozbieżności. |
| MISSING_HOLD_CANDIDATE | Ostatni świeży wynik to MISSING_AT_SOURCE, bez PENDING. Dostępny opis potencjalnej naprawy, bez prawa wykonania. |
| CONFLICT_REVIEW | Niezgodna tożsamość/treść blokady; wymagana kontrolowana ocena. |
| UNKNOWN_PURCHASE_REVIEW | Nieznany zakup w źródle; brak automatycznego bindingu lub zgody wynikającej z nieobecności danych. |
| SOURCE_QUARANTINED | Historyczny wynik zgłasza kwarantannę źródła. |
| ROOT_QUARANTINED | Root jest objęty kwarantanną albo ostatni zapisany wynik ją zgłasza. |

Kwalifikator sprawdza wiek MATCH/MISSING: do 15 sekund, z tolerancją
przyszłego czasu do 5 sekund. Kandydat zachowuje dokładne source_request_id,
oczekiwany UUID blokady, rodzaj i powód oraz bazowy probe_id, czas, epoch
i revision. Zawsze wymaga nowego atomowego sprawdzenia źródła,
zweryfikowanej sprawy i kontrolowanego protokołu wykonania.
`Candidate.executionAllowed()` zawsze zwraca false. Sam obiekt lub UUID
nie jest tokenem upoważniającym; przyszły wykonawca musi ponownie sprawdzić
trwały zakres i aktualne warunki zamiast ufać przekazanej strukturze.

Licznik dawnych rozbieżności pozostaje widoczny także po późniejszym MATCH.
Kwalifikator nie zamyka spraw, nie dopisuje wyniku dla PENDING i nie resetuje
wyczerpanego budżetu. PENDING wymaga odrębnego kontrolowanego rozliczenia;
nie można go pominąć przez dopisanie nowej obserwacji.

Kwarantanna rodzica, zakresu zakupu/klienta lub stanu Order daje ogólne
QUARANTINED i odbiera kandydatów wszystkim siedmiu źródłom. Liczniki
historyczne są zachowane. Niespójna sekwencja, tożsamość lub kształt
wyniku daje INCONSISTENT_HISTORY bez częściowych kandydatów. Błąd SQL
daje UNAVAILABLE bez częściowych danych; obcy zakres — UNKNOWN_REQUEST.

Wynik zawsze ma `historyOnly=true`, `repairAllowed=false`,
`activationAllowed=false` i `completeCaseCoverage=false`. Referencja
sprawy nie dowodzi własności. To adapter dla zaufanego procesu, bez
akceptacji rzeczywistych kont/rol operatorów lub konfiguracji środowisk.

## Odbiór i punkt wznowienia

Schemat i restore bez zmian: **131 tabel / 1196 kolumn / 221 migracji**;
47 mechanizmów, 6 przestrzeni Redis, 20 kategorii kopii i 26 polityk.
**112 Java (100 PostgreSQL / 12 jednostkowych) + 101 unikalnych Python PASS**, bez błędów i pominięć;
44 nowe Java (37 Root / 7 HTTP), 6 mutacji w istniejącym teście Python.
Bramki/candidate PASS, gotowość NOT_READY / kod 2.
Dowody: `tmp/a43-repair-assessment-144/report.json`.
Pełnego drillu nie powtarzano: historyczny drill 143 znajduje się
w `tmp/privacy-recovery-b7d43150/report.json`. Źródła restore, migracje
i protokół źródłowy pozostały bez zmian. Lokalne testy SQL/HTTP używają
izolowanych danych, a introspekcja jest testowa, bez odbioru całych
aplikacji lub mieszanych wersji w dev/test.

Następne: trwały, kontrolowany protokół rozliczenia PENDING i intencji
odtworzenia brakującej negatywnej blokady, z zachowaniem starych UUID,
historii, kwarantanny i budżetów. Przed mutacją musi istnieć atomowe
sprawdzenie aktualnego źródła oraz zweryfikowane upoważnienie sprawy;
sam MISSING_HOLD_CANDIDATE nie wystarcza.
Potem rzeczywiste sprawy, kompletne wersjonowane dowody i pełne
upoważnienie/wykonanie/odzyskiwanie D02. D01/P01, D09/P03–P05/SAFE,
P09 i odbiór ról/rejestru odłożone; A05, A41 i same zdjęcia
A27–A31/A40 nadal odłożone. Bez sekretów lub operacji na danych środowisk.
