# A43.40 — trwały rejestr rund obserwacji, pakiet 132

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.10**.
Odbiór lokalny; A43.40/.41 nadal otwarte. Czyszczenie wyłączone.

## Zakres i granice

Order V40 dodaje dwie tabele i 13 kolumn: nagłówki rund oraz próby źródeł.
Rejestr jest podłączony do `fenceIntentAndAssess`. Trwały zapis próby następuje
przed PREPARE; nie dowodzi dotarcia żądania do źródła. Odpowiedź RELEASE jest
sprawdzana co do zakresu, akcji i statusu, a jej potwierdzenie zapisywane osobno.
Identyfikator rundy jest zarazem wewnętrznym tokenem dzierżawy, nadal wymagającym
uwierzytelnienia usługi. Rejestr nie zawiera kontaktu, treści ani tokenów dostępu
do konta, czatu lub statusu usunięcia.

Wynik rozdziela diagnozę sieciową (`cleanup`) i trwały zapis (`historyRecorded`).
Nawet potwierdzony zdalny RELEASE nie zamyka historii, jeśli zapis jego
potwierdzenia w SQL jest niepewny. Utrata potwierdzenia COMMIT nie jest
traktowana jako rollback. `roundId` pozwala odczytać zachowany stan po awarii.
Brak wiersza nie jest dowodem wykonania lub cofnięcia operacji.

Techniczny limit to osiem zachowanych rund na intencję i siedem prób na rundę;
odpowiada limitowi prób obecnego koordynatora. Nieukończona runda blokuje nową
rundę tej samej intencji. Nie nadpisuje się starszych rund ani nie kasuje ich
po wieku. Wyczerpanie budżetu nie powoduje automatycznego tworzenia generacji.
Nie ma nowej polityki usuwania tych znaczników; zachowują ochronę D03.

`finished_at` oznacza wyłącznie rozliczenie prób danej rundy przez zapisane
potwierdzenia RELEASE (lub zakończenie pustej rundy bez prób), nigdy wykonanie
usuwania danych, globalne odblokowanie źródeł lub zatrzymanie wykonawcy.
Odczyt po awarii jest ograniczony do pierwotnego zakresu właściciela/zadania,
intencji i rundy. Nie wykonuje RPC, nie wznawia PREPARE i nie zmienia TTL
w potwierdzenie RELEASE. Sam rejestr nie orzeka o wygaśnięciu źródłowych blokad.

## Dlaczego nie ponawiamy RELEASE po awarii

Obecny protokół źródłowy po RELEASE może ponownie przyjąć ten sam PREPARE,
jeśli żądanie dotrze z opóźnieniem. Wysłanie RELEASE na podstawie historii
nie dowodzi więc zatrzymania starego koordynatora lub braku późnego PREPARE.
Ten pakiet nie dodaje takiego wznowienia. Następny krok musi najpierw chronić
źródła przed odtworzeniem zwolnionej dzierżawy i rozliczyć współbieżność rund.

## Restore

Nowy krok `invalidate_observation_rounds` oznacza nagłówki jako zablokowane
przed kwarantanną intencji, odtwarzaniem sesji i pozostałych zakresów. Pracuje
w partiach do 100, z limitem całości oraz timeoutami zapytań/blokad. Niepełny
schemat, błędny wynik lub pozostałe rekordy blokują dalszy restore.
Zachowane zostają próby i ich dotychczasowe potwierdzenia, w tym brakujące.
Ponowne wykonanie kroku jest idempotentne, nie wysyła żądań do źródeł i nie
upoważnia do udostępnienia odtworzonego środowiska.

Inwentarz: **111 tabel / 1078 kolumn / 201 migracji**. Nowy schemat nie został
zastosowany na rzeczywistych dev/test/prod. Rewizja decyzji użytkownika bez zmian.

## Odbiór

Dowody końcowe: `tmp/a43-round-history-132/report.json`.
Odbiór: **94 PostgreSQL PASS** (70 workflow/rejestr, 24 HTTP) oraz
**90 unikalnych Python PASS**, bez pominięć. Kontrole strukturalne i candidate
PASS; pełna gotowość **NOT_READY / kod 2**. Pełny drill 14 baz/Redis PASS:
`tmp/privacy-recovery-321eddc9/report.json`. Zachowano 3 rundy i 2 próby z ich rzeczywistymi stanami;
wszystkie nagłówki w kwarantannie, brakujące potwierdzenie nadal brakujące,
ponowna kwarantanna idempotentna, releaseAllowed=false i zero RPC odzyskiwania.
Poprzednich pakietów i powtórnych przebiegów nie dolicza się do tych liczb.
Fikstura HTTP czeka najwyżej 5 s na wyrównanie zaobserwowanego zegara bazy
z JVM; kontrole czasu w kodzie aplikacji pozostają bez zmian.

Nadal `activationAllowed=false`, `runtimeWired=false`, `executionTerminated=false`.
Brak nowego endpointu, widoku CC, harmonogramu, automatycznego retry lub wykonania
mutacji. Starsza runda bez intencji (`fenceAndAssess`) nie korzysta z tego rejestru
i nadal nie daje upoważnienia. A43 pozostaje NOT_READY.

## Punkt wznowienia

Najpierw zabezpieczyć źródłowy protokół dzierżaw przed opóźnionym/replayowanym
PREPARE po RELEASE. Dopiero później kontrolowane rozliczanie niepewnych rund
z żądaniami do źródeł i ochroną przed działającą równolegle starą instancją.
Nie uruchamiać mutacji lub wznowienia na podstawie zapisanej historii albo TTL.

D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw pozostają odłożone.
Zakresy wymagające wartości/decyzji odkładać i wykonywać niezależne prace.
A05, A41 i zmiany samych zdjęć pozostają odłożone.
