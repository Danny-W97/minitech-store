# A43.40 — trwała historia porównań blokad, pakiet 143

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.21**.
Zakres lokalny; A43.40/.41 pozostają otwarte. Czyszczenie wyłączone.

## Przebieg i granice

`CommerceRetentionHoldReconciliationHistory.observe` jest jawnym adapterem
dla zaufanego procesu, opartym na porównaniu z 142. Wymaga dokładnego
zakupu, wniosku, referencji sprawy i źródła oraz UUID próby. Zapisuje próbę
przed RPC w osobnym COMMIT. UUID próby staje się challenge protokołu 142.
Niepewny COMMIT tej próby nie uruchamia RPC. Zewnętrzna transakcja
wywołującego jest odrzucana, a Root nie utrzymuje transakcji przez HTTP.

Próba zachowuje dokładne source_request_id i UUID blokady z historycznego
ACK. SQL sprawdza to powiązanie, sekwencję i maksymalnie 8 prób na źródło,
czyli 56 na wniosek. Ten budżet jest osobny od prób doręczania z 140.
Zwykły UPDATE/DELETE nie zmienia historii lub limitu.

Wynik jest dopisywany raz, z bezpiecznym kodem i ewentualnymi czasem,
binding_epoch oraz source_revision obserwacji. Bez treści spraw, kontaktu,
tokenów, IP lub dowolnych błędów. RECORDED potwierdza COMMIT wpisu
diagnostycznego, również gdy wynik oznacza błąd, rozbieżność lub kwarantannę.
Nie jest potwierdzeniem naprawy lub aktualnej kompletności źródeł.

Powtórzenie tego samego UUID odczytuje zapisany wynik albo PENDING.
PENDING oznacza trwałą próbę bez wyniku: RPC może trwać, proces mógł
upaść lub zapis wyniku mógł się nie powieść. Nie następuje ponowne RPC
tego UUID. Nowe jawne porównanie wymaga nowego UUID i wolnego miejsca
w budżecie; brak automatycznych ponowień, schedulera lub resetowania limitu.
Utrata odpowiedzi COMMIT wyniku daje UNAVAILABLE; późniejszy odczyt
rozpoznaje zachowany wynik albo pozostawione PENDING.

Kwarantanna blokuje nowe próby. Jeżeli pojawi się podczas RPC, zapisany
wynik może być wyłącznie ROOT_QUARANTINED, bez dodatniej obserwacji.
Historyczne wyniki pozostają niezmienne. Zaufany `inspect` zwraca najwyżej
8 wpisów jednego źródła w jednym dokładnym zakresie, bez RPC lub DML.
Cały wynik ma status QUARANTINED, jeśli zakres jest zablokowany.
PENDING i kody inne niż MATCH_AS_OF_READ wskazują potrzebę oceny.
Historyczne MATCH_AS_OF_READ nadal nie potwierdza aktualnego stanu.
Pod kwarantanną `observe`, także dla wcześniej użytego UUID, zwraca
QUARANTINED bez RPC; zachowany wynik jest dostępny w przeglądzie historii.

Każdy wynik pozostaje bez prawa aktywacji lub globalnej kompletności.
Brak automatycznej naprawy, odblokowania lub zwalniania blokad. Referencja
sprawy nie potwierdza własności; nie dodano operator API, CC lub nowych
uprawnień i nie podłączono adaptera do rzeczywistych aplikacji.

## Schemat i restore

Order V46 dodaje `commerce_retention_hold_probes` (7 kolumn) oraz
`commerce_retention_hold_probe_results` (6 kolumn). Inwentarz:
**131 tabel / 1196 kolumn / 221 migracji**. Pozostałe liczby bez zmian:
47 mechanizmów, 6 przestrzeni Redis, 20 kategorii kopii i 26 polityk.
Metadane pozostają pseudonimowe, bez automatycznego TTL do rozliczenia
źródeł ponowień i kopii; bez surowego automatycznego eksportu.
Procedura oceny dostępu wymaga stabilnego ID zakupu i weryfikacji zakresu.

Restore kwarantannuje rodzica, zachowuje próby, wyniki i budżet.
Weryfikuje dokładne powiązania z ACK, ciągłość sekwencji i brak osieroconych
wyników. Niepełny schemat lub niezgodność blokują dalsze odtworzenie.
Historyczny schemat bez tych tabel nie udaje wykonanego porównania.
Nie dopisuje wyniku PENDING i nie wznawia wywołań.

**177 Java (153 PostgreSQL / 24 jednostkowe) + 101 unikalnych Python PASS**, bez błędów i pominięć;
54 nowe Java (39 Root / 15 HTTP/SQL), 3 Python i 6 mutacji kontraktu
w istniejącym teście. Bramki/candidate PASS, NOT_READY / kod 2.
Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-b7d43150/report.json`;
2 próby/1 wynik zachowane pod kwarantanną, PENDING bez wyniku
i bez automatycznego wznowienia.
Dowody odbioru: `tmp/a43-hold-history-143/report.json`.
V46 stosowana wyłącznie na izolowanych fiksturach; bez operacji
na rzeczywistych danych dev/test lub odczytu sekretów użytkownika.

## Punkt wznowienia

Następne: kontrolowany proces rozliczenia rozbieżności i kwalifikacji
do naprawy po utracie źródła, z zachowaniem starych tożsamości, historii,
kwarantanny i budżetów. Sama diagnoza lub zapis nie odtwarzają blokady.
Potem zweryfikowane rzeczywiste sprawy, kompletne wersjonowane dowody
i pełne upoważnienie/wykonanie/odzyskiwanie D02.
D01/P01, D09/P03–P05/SAFE, P09 i odbiór ról/rejestru odłożone;
A05, A41 i same zdjęcia A27–A31/A40 nadal odłożone.
