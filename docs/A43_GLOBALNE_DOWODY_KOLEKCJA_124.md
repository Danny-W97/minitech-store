# A43.40 — obserwacje blokad i wiarygodność dowodów, pakiet 124

**Nowszy punkt wznowienia:** [pakiet 125 — obserwacje sześciu źródeł](A43_OBSERWACJE_ZRODEL_125.md).
Poniższy dokument opisuje historyczny odbiór 124.

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.2**.
Zakres wdrożony lokalnie. **A43.40/A43.41 nadal otwarte, NOT_READY.**

## Co zmieniono

- Kolektor Order sprawdza rzeczywiste stany sagi: oczekiwanie na rezerwację
  i płatność, kompensację, wymaganą rekonsyliację oraz nieznane stany. Blokadą
  są także zapisane znaczniki ponowienia i przetwarzania, nawet po upływie
  dzierżawy. Samo `FAILED`, `COMPLETED` lub brak sagi nie potwierdza globalnego
  braku retry/DLQ.
- Odczytuje znane blokady Order/Customer oraz bariery konta i restore.
  Pusty lokalny rejestr nadal daje `UNKNOWN`, bez globalnego zezwolenia.
- Wywołania dostawców są poza transakcją SQL. Przed i po nich kolektor
  sprawdza właściciela, wersję zamówienia, bieżącą generację, fazę, politykę,
  rewizję dowodów, lokalnego procesu i zakresów blokad. Zmiana przerywa zapis
  zebranych dowodów. Wywołanie kolektora w transakcji nadrzędnej jest odrzucane.
- Dotychczasowe callbacki zwracające samo `State` mogą zgłosić blokadę.
  `CONFIRMED`, `null` i awaria dostawcy nie potwierdzają braku blokad: wynik
  to `UNKNOWN`. Te interfejsy nie zawierają pokrycia źródeł, rewizji ani
  aktualności potrzebnych do przyjęcia dodatniego dowodu.
- Migracja Order **V37** przywraca unieważnianie oceny także po zwykłych
  zmianach kontaktu i treści pozycji, bez podbicia wersji. Wyjątki dodane
  wcześniej dla redakcji dopuszczały również takie niezwiązane zmiany.
  Sam zapis `updated_at` nadal nie unieważnia generacji.
- Skrót zasobu D02 potwierdza integralność zapisanego planu. Nie zatwierdza
  kompletnej polityki zachowania przy otwartych D01/P09. Podpis pojedynczego
  wpisu rejestru potwierdza jego autentyczność, ale nie aktualną niezależną
  pieczę nad rejestrem. Obie bramki pozostają `UNKNOWN`.
- Ogólne API zapisu odrzuca dodatnie potwierdzenia tych dwóch bramek.
  Historyczne wiersze `CONFIRMED` nie są przepisywane: przy ocenie zostają
  potraktowane jako `UNKNOWN`. Istniejące `BLOCKED` zachowują znaczenie.
- Kontrakt QA jawnie ogranicza zakres do lokalnych obserwacji negatywnych;
  testy odrzucają deklaracje kompletnej polityki, aktualnego rejestru lub
  dodatniej zgody z niepowiązanych callbacków.

## Odbiór i granice

Wyniki bieżącego pakietu: `tmp/a43-global-124/report.json` i zachowane XML
oraz logi w tym katalogu. Raport pakietu 123 w `tmp/a43-fixes/report.json`
pozostaje dowodem historycznym; jego 333 przypadki Java nie są liczbą
ponownie wykonanych testów pakietu 124.

Końcowa regresja: **77 przypadków PostgreSQL / 80 unikalnych testów Python
PASS, bez pominięć**. Java: Cleanup 10, GlobalGuards 13, Jobs 15, Redaction 9,
Workflow 30. Python obejmuje również dodatkowe mutacje kontraktu w ramach
istniejącego testu. Pierwszy przebieg Java wykazał lukę V35; po dodaniu V37
powtórzono cały wskazany zakres. Wyników pierwszego przebiegu nie sumuje się
z końcowymi. Bramka gotowości nadal zwraca **NOT_READY / kod 2**.

Dodano jedną migrację triggera: **106 tabel / 1031 kolumn / 198 migracji**.
V37 sprawdzono wraz z migracjami i regresją Order na izolowanych bazach;
nie zastosowano jej na rzeczywistych dev/test/prod. Nie powtarzano pełnego
drill 14 baz/Redis: nie zmieniano procedur odtwarzania ani układu tabel.
Poprzedni drill pozostaje dowodem historycznym i nie odbiera kompletnego D02.

To nie jest pełny katalog globalnych blokad ani nowy adapter HTTP do
rzeczywistego rejestru spraw. Nie potwierdzono konfiguracji dev/test, limitów
czasowych rzeczywistych dostawców ani ich kompletności. Nie powstały nowe
endpointy, harmonogramy lub widoki CC. Mutujące API D02 pozostają zablokowane.
Nie zmieniano sekretów, rzeczywistych danych ani aktywacji czyszczenia.

## Punkt wznowienia dla użytkownika i agenta

1. Kontynuować krok 2 planu: źródłowe obserwacje spraw, retry/DLQ i blokad
   w pozostałych usługach. Dodatni dowód musi zawierać zaufane źródło, zakres,
   wersję/generację, fazę, politykę, rewizję i aktualność oraz pokrycie
   wszystkich wymaganych źródeł. Brak źródła lub nieznana historia blokuje
   potwierdzenie. Nie przywracać przyjmowania gołego `State.CONFIRMED`.
2. Po rozstrzygnięciu D09 przygotować weryfikację bieżącego niezależnego
   wskaźnika rejestru i jego pieczy. Nie zastępować tego podpisem starego wpisu.
   D01/P09 wymagają nadal kompletnej polityki; nie zadawać ponownie tych samych
   pytań bez nowego powodu, użytkownik świadomie pozostawił je do ustalenia.
3. Wdrożyć pełne upoważnienie i protokół wykonania ośmiu źródeł z ochroną
   przez mutację, zatwierdzenie, stop i odzyskanie po awarii. Dzierżawy odczytów
   z pakietu 123 oraz wyniki obecnego kolektora nie są zezwoleniem na wykonanie.
4. Następnie rzeczywiste magazyny/procedury, dowody dev/test i pełne A43.41.

A05/Stripe, A41 i zmiany samych zdjęć pozostają odłożone. Samo dodanie nazw
zmiennych Stripe do SOPS nie oznacza niepustych wartości lub odbioru sandboxu.
