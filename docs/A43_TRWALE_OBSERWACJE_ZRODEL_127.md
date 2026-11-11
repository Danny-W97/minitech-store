# A43.40 — trwała historia obserwacji źródeł, pakiet 127

**Nowszy punkt wznowienia:** [pakiet 128 — rejestr intencji D02](A43_REJESTR_INTENCJI_D02_128.md).
Poniżej historyczny odbiór obserwacji 127; przygotowanie/stop intencji
wdrożono następnie w 128, nadal bez upoważnienia do wykonania.

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.5**.
Wdrożenie lokalne; pełne A43.40/A43.41 nadal otwarte, czyszczenie wyłączone.

## Zmiana i znaczenie danych

Order zapisuje historyczne obserwacje sześciu źródeł w nowej tabeli
`commerce_retention_source_receipts` (migracja V38). Wiersz wiąże zamówienie,
generację, fazę, źródło, wersję i politykę z epoką/rewizją bindingu,
bezpiecznymi stanami spraw/ponowień, skrótem semantycznym i czasem pierwszego
odczytu oraz zapisu. Wiersze są pseudonimowe, nie anonimowe.

Nie zapisuje się e-maili, treści, payloadów, indywidualnych tokenów ani
losowego identyfikatora żądania. Identyfikator ten nadal musi pasować przy
każdym nowym odczycie. Historia nie potwierdza aktualności kolejnej odpowiedzi.

**To historia odebranej obserwacji, nie potwierdzenie faktycznej redakcji,
usunięcia danych, kompletnej polityki lub zgody na wykonanie D02.**
Nie zastępuje brakujących trwałych potwierdzeń wykonania ośmiu źródeł.

## Właściwości implementacji

- Najwyżej jeden wiersz na źródło i generację/fazę, czyli sześć na zadanie.
  Wielokrotne odczyty nie dodają kolejnych wierszy.
- UPDATE i DELETE są blokowane triggerem. Wersja/faza/polityka muszą
  odpowiadać zachowanemu zadaniu. Starsze, wycofane generacje są dozwolone
  podczas restore: historia pozostaje historią, zamiast blokować odtworzenie.
- Ponowienie wymaga świeżej odpowiedzi i aktualnego zakresu. Semantyczny
  skrót obejmuje zakres, politykę i stany; czas/identyfikator nowej próby
  nie zmieniają tożsamości historycznej obserwacji. Zachowywany jest pierwszy wpis.
- Sprzeczna obserwacja przy tej samej rewizji wycofuje generację, bez
  nadpisywania historii i bez przenoszenia dowodu do innej generacji.
- Historia i ewentualny dowód blokady zapisują się w jednej lokalnej
  transakcji. Błąd przed COMMIT wycofuje oba. Po utracie potwierdzenia COMMIT
  wynik jest UNAVAILABLE; jawne ponowienie rozpoznaje zatwierdzone wiersze.
- Sam zapis historii UNKNOWN nie zmienia rewizji dowodów oceny, jej dzierżawy,
  prób lub zegara kwalifikacji. Zmianę efektywnego dowodu BLOCKED nadal
  obsługuje dotychczasowy magazyn GUARD. Kwalifikator nie czyta historii
  obserwacji jako dowodu aktualnej zgody.
- Nie ma nowego endpointu, widoku CC, harmonogramu lub automatycznego TTL.
  To minimalne metadane protokołu objęte istniejącą ochroną identyfikatorów
  i skrótów do rozliczenia kopii/powtórzeń. Nie uruchomiono retencji.

## Odbiór

Bieżące XML, logi, odciski źródeł i wyniki: `tmp/a43-receipts-127/report.json`.
Końcowo **137 Java PASS (15 jednostkowych / 122 PostgreSQL)** oraz
**80 unikalnych Python PASS**, bez pominięć. Java: protokół GUARD 7,
protokół peer 8, GlobalGuards 13, Peers 79, Workflow 30. Pierwszy przebieg
rozszerzono o wariant niepewnego COMMIT dla UNKNOWN i powtórzono cały zakres;
nie sumuje się prób ani poprzednich pakietów. Kontrole strukturalne i candidate
PASS; pełna gotowość nadal **NOT_READY / kod 2**.
Drill: `tmp/privacy-recovery-6579b675/report.json` — **PASS**, 14 baz i Redis,
zachowane dwa wpisy historii, `releaseAllowed=false`.
Inwentarz po V38: **107 tabel / 1045 kolumn / 199 migracji**.
V38 nie została zastosowana na rzeczywistych dev/test/prod.

Testy obejmują ograniczenie liczby wierszy, niezmienność i powiązanie zakresu,
sprzeczną obserwację, odrzucenie starego bindingu, wspólny rollback, ponowienie
po niepewnym COMMIT oraz dotychczasowy przebieg HTTP/współbieżności.
Drill restore obejmuje dwa wpisy historii: generację bieżącą i starszą.
Sprawdza zachowanie ich zawartości oraz wycofanie odtworzonych generacji.
Nie jest odbiorem prawdziwego dev/test, pełnego A36 lub pełnego A43.41.

## Odłożone zakresy i punkt wznowienia

Odłożone pozostają D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru
spraw. Zgodnie z poleceniem użytkownika nie przyjmujemy za nich wartości
domyślnych; przechodzimy do następnej niezależnej implementacji. A05, A41
oraz zmiany samych zdjęć nadal odłożone. Nie odczytywano sekretów użytkownika.

Następna praca: techniczny rejestr intencji i przejść stanu protokołu D02,
z rozróżnieniem obserwacji, upoważnienia, faktycznej mutacji, przerwania
i odzyskania po awarii. Wykonanie musi pozostać niedostępne bez kompletnych
globalnych dowodów, zatwierdzonych polityk i potwierdzeń wszystkich źródeł.
Nie używać nowej historii jako upoważnienia i nie przywracać starych workerów
pojedynczą flagą. Globalna migawka, kompletna zgoda i pełny protokół wykonania
pozostają niegotowe.
