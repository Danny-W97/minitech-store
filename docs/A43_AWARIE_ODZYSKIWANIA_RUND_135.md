# A43.40 — odbiór awarii odzyskiwania rund, pakiet 135

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.13**.
A43.40/.41 pozostają otwarte. Czyszczenie pozostaje nieaktywne.

## Zakres

Pakiet weryfikuje mechanizm RELEASE z pakietu 134. Nie dodaje migracji,
endpointów, harmonogramu ani wykonawcy redakcji/usuwania. Fikstura HTTP
pozwala zatrzymać odpowiedź po zatwierdzeniu transakcji źródła, przed
nagłówkami albo po nagłówkach i przed treścią. Wywołania korzystają
z produkcyjnego klienta i jego rzeczywistych limitów czasu.

| Awaria | Wymagany i sprawdzany skutek |
|---|---|
| Timeout po COMMIT źródła, przed nagłówkami — 7 źródeł | Brak automatycznego retry; niepotwierdzona próba pozostaje oczekująca. Jawne ponowienie RELEASE rozlicza ją idempotentnie. |
| Timeout odbioru treści — Inventory | Klient kończy wywołanie przy wstrzymanej odpowiedzi; brak fałszywego potwierdzenia. |
| Kwarantanna rundy podczas odpowiedzi RELEASE — 7 źródeł | Właściciel SQL zostaje usunięty; późna odpowiedź nie zapisuje potwierdzenia ani zakończenia. Następne wywołanie pozostaje zablokowane, bez HTTP. |
| Zmiana bindingu źródła po utracie odpowiedzi — 7 źródeł | RELEASE zachowuje pierwotny zakres. Próba pozostaje oczekująca, a dzierżawa nowej generacji nie jest usuwana. Zmiana epoki nie zastępuje potwierdzenia. |
| Rollback końcowego zapisu rozliczenia | Zachowane potwierdzenia źródeł nie znikają. Jawne ponowienie kończy rundę bez powtarzania potwierdzonych RPC. |
| COMMIT końcowego rozliczenia wykonany, odpowiedź SQL utracona | Pierwszy wynik UNAVAILABLE; odczyt trwałego rejestru pokazuje zakończenie, kolejne wywołanie FINISHED bez RPC. Nie zakładamy rollbacku. |

Utrata właściciela w HTTP jest tu wywołana rzeczywistą kwarantanną SQL
podczas żądania. Nie jest to symulacja upływu czasu ani pełnego restore.
Wygaśnięcie i przejęcie przez nowego właściciela sprawdza osobny test SQL
według zegara bazy, wraz z zakazem zapisu i zwalniania cudzej dzierżawy.

## Dowody i ograniczenia

Odbiór: **46 PostgreSQL + 90 unikalnych Python PASS**, bez pominięć;
24 nowe scenariusze i 22 regresje (15 workflow / 31 HTTP). Bramki
strukturalne i candidate PASS; gotowość NOT_READY / kod 2.
Bieżący raport: `tmp/a43-recovery-faults-135/report.json`.
Obejmuje tylko scenariusze wymienione w raporcie i wskazane regresje;
wcześniejsze wyniki nie są doliczane. Kontrole strukturalne nie wykonują
testów integracyjnych, dlatego ich pole acceptance nadal ma wartość null.

Brak zmian schematu, kodu produkcyjnego Java i restore. Inwentarz pozostaje:
**118 tabel / 1110 kolumn / 209 migracji**. Pełnego drillu 14 baz/Redis
nie powtarzano; historyczny dowód pakietu 134:
`tmp/privacy-recovery-9dca08db/report.json`.

HTTP korzysta z rzeczywistych kontrolerów, filtrów i ośmiu baz PostgreSQL,
z testowym serwerem introspekcji. To nie jest odbiór pełnych aplikacji ani
wdrożenia dev/test. Wynik SETTLED rozlicza zachowane próby i nie potwierdza
globalnego braku blokad, upoważnienia do mutacji lub zakończenia wykonawcy.
`activationAllowed=false`, `runtimeWired=false`, `executionTerminated=false`.

## Punkt wznowienia

Rozpocząć od kompletności źródeł globalnych blokad dalszego zachowania:
zmapować lokalne rejestry i ich luki wobec ośmiu uczestników D02, określić
zakres i rewizję wymaganego dowodu oraz odrzucać brakujące lub nieaktualne
potwierdzenia. Następnie kontynuować protokół upoważnienia i wykonania D02.
Zakończone odzyskiwanie rund nie zastępuje żadnego z tych dowodów.

D01/P01, D09/P03–P05/SAFE, P09 oraz rzeczywisty odbiór ról/rejestru spraw
pozostają odłożone. Etapy potrzebujące wartości/decyzji użytkownika odkładać
i podejmować kolejną niezależną pracę. A05, A41 i zakres samych zdjęć
A27–A31/A40 pozostają odłożone. Bez odczytu sekretów i operacji na danych
rzeczywistych środowisk.
