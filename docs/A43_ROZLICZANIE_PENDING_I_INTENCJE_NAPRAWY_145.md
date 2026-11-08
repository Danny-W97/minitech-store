# A43.40 — rozliczanie PENDING i intencje naprawy, pakiet 145

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.23**.
Zakres lokalny; A43.40/.41 otwarte. Czyszczenie pozostaje wyłączone.

## Rozliczenie próby odczytu

`CommerceRetentionHoldRecoveryPlanning.supersede` zapisuje niezmienny,
idempotentny znacznik zastąpienia konkretnego PENDING. Wymaga dokładnego
zakupu, wniosku, referencji sprawy, źródła, UUID obu prób i UUID rozliczenia.
Dotyczy wyłącznie prób odczytu 142/143, nigdy doręczeń blokad, płatności
lub wykonawców usuwania danych. Nie wymaga RPC i nie modyfikuje źródeł.

Próba zastępująca musi być późniejsza i najnowsza dla tego samego
wniosku/źródła oraz zachowywać dokładne source_request_id i UUID blokady.
Musi mieć zakończony MATCH_AS_OF_READ albo MISSING_AT_SOURCE, o wieku
do 15 s i tolerancji przyszłości 5 s. Brak wyniku, konflikt, nieznany zakup,
kwarantanna, błąd lub przeterminowanie nie pozwalają rozliczyć PENDING.
Cel nie może mieć już wyniku lub innego rozliczenia. Powtórzenie tych
samych identyfikatorów odczytuje istniejący zapis; zmiana powiązań daje konflikt.

Znacznik nie potwierdza zatrzymania wykonawcy: `executionTerminated=false`.
Stary proces może nadal kończyć odczyt. Blokada rodzica w SQL rozstrzyga
wyścig wyniku i rozliczenia: wynik zapisany pierwszy uniemożliwia rozliczenie;
rozliczenie zapisane pierwsze blokuje późny INSERT wyniku, również przez
starszy kod. Nowy adapter historii zwraca wtedy SUPERSEDED, bez RPC lub
nowego wyniku. Nie nadpisuje dawnej próby i nie fabrykuje jej odpowiedzi.

Przegląd historii pokazuje `supersededBy`. Rozliczona próba bez własnego
wyniku nadal ma `outcomeUnknown=true`, lecz `unresolvedPending=false`.
Nie udajemy znajomości pierwotnej odpowiedzi. Kwalifikator 144 rozdziela
`pendingProbes` i `resolvedPendingProbes`: tylko nierozliczone PENDING
blokują kandydata. Każde rozliczenie jest walidowane względem późniejszego
zakończonego odczytu tej samej tożsamości. Budżet nadal obejmuje wszystkie
stare próby, również SUPERSEDED; nic nie zwalnia limitu 8/źródło.
Osiem PENDING bez dopuszczalnego późniejszego wyniku pozostaje do oceny,
bez automatycznego resetu lub rozliczenia na podstawie samego wieku.

## Intencja odtworzenia blokady

`prepare` odczytuje trwałe dane zamiast przyjmować obiekt Candidate od
wywołującego. Wymaga najnowszego świeżego MISSING_AT_SOURCE i braku
nierozliczonych PENDING. Intencja zachowuje bazowy probe, stabilny wniosek,
źródło oraz dokładne oryginalne source_request_id i UUID blokady z ACK.
SQL sprawdza warunki ponownie pod blokadą rodzica. Jedna intencja może
powstać dla jednego bazowego probe; maksymalnie 8 na źródło / 56 na wniosek.

PREPARED oznacza jedynie COMMIT niezmiennego planu. Nie potwierdza naprawy,
bieżącego braku danych, weryfikacji sprawy, upoważnienia operatora lub
globalnej kompletności. `repairAllowed=false`, `activationAllowed=false`,
`completeCaseCoverage=false`. Brak wykonawcy lub przejścia do AUTHORIZED.
Ponowienie tego samego intent_id nie przypisuje go do nowszej podstawy.
Nawet stary plan odczytany idempotentnie wymaga nowego atomowego sprawdzenia
źródła i zweryfikowanego upoważnienia przed przyszłą mutacją.

Oba zapisy są odporne na utratę potwierdzenia COMMIT: API zwraca UNAVAILABLE,
a ponowienie rozpoznaje istniejący zapis albo ponownie sprawdza warunki,
jeżeli transakcja nie została zatwierdzona. Nie powstaje druga tożsamość.
Kwarantanna rodzica, zakupu/klienta lub stanu Order blokuje wszystkie
polecenia, także idempotentne. Osobny zaufany `inspect` zachowuje odczyt
historyczny pod QUARANTINED, do 8 rozliczeń i 8 intencji jednego źródła.
Zewnętrzna transakcja wywołującego jest odrzucana. Brak publicznego API,
przycisku CC, nowych uprawnień, schedulera lub automatycznej naprawy.

## Schemat i restore

Order V47 dodaje `commerce_retention_hold_probe_resolutions` (4 kolumny)
i `commerce_retention_hold_repair_intents` (7 kolumn). Inwentarz:
**133 tabele / 1207 kolumn / 222 migracje**; 47 mechanizmów,
6 przestrzeni Redis, 20 kategorii kopii i 26 polityk.
Metadane są pseudonimowe, bez treści/kontaktu/sekretów i automatycznego
TTL do rozliczenia kopii/źródeł ponowień; bez surowego automatycznego eksportu.
Ręczna ocena dostępu wymaga stabilnego ID zakupu i weryfikacji zakresu.

Restore zachowuje znaczniki i intencje oraz kwarantannuje rodzica.
Sprawdza tożsamości, kierunek zastąpienia, brak wyniku rozliczonego PENDING
i kod podstawy intencji. Nie ocenia historycznych planów ponownie według
dzisiejszego czasu lub najnowszej próby i nie nadaje im aktualności.
Niepełny schemat lub niespójność blokują dalszy restore. Nie ma wykonania,
wznowienia, resetu limitu ani dopisywania wyniku pozostałemu PENDING.

**168 unikalnych Java (156 PostgreSQL / 12 jednostkowych) + 104 Python PASS**, bez błędów i pominięć;
56 nowych Java (49 Root / 7 HTTP), 3 Python i 7 mutacji kontraktu.
Po dopracowaniu odczytu i modelu historii powtórzono 88 przypadków bez doliczania ich
do unikalnej sumy. Bramki/candidate PASS, gotowość NOT_READY / kod 2.
Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-9f20b6ee/report.json`;
4 próby / 2 wyniki / 1 rozliczenie / 1 intencja zachowane, bez wykonania
i bez dopisania wyniku pozostałemu PENDING.
Dowody: `tmp/a43-recovery-planning-145/report.json`.
V47 stosowana wyłącznie na fiksturach. Testy SQL/HTTP i pełny drill używają
izolowanych danych; introspekcja jest testowa, bez odbioru pełnych aplikacji
dev/test lub mieszanych wersji. Bez sekretów i danych rzeczywistych środowisk.

## Punkt wznowienia

Następne: protokół sprawdzenia źródła i odtworzenia brakującej negatywnej
blokady z oryginalnym UUID, z atomową kontrolą wersji i konfliktów,
trwałymi próbami/potwierdzeniami oraz ochroną przed replay i restore.
Wykonanie nadal wymaga zweryfikowanego upoważnienia sprawy; sam plan
lub obiekt Candidate nie wystarcza. Potem rzeczywiste sprawy, kompletne
wersjonowane dowody i pełne upoważnienie/wykonanie/odzyskiwanie D02.
D01/P01, D09/P03–P05/SAFE, P09 i odbiór ról/rejestru odłożone;
A05, A41 i same zdjęcia A27–A31/A40 nadal odłożone.
