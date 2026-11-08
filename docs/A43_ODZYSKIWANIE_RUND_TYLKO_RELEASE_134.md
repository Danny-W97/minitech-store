# A43.40 — kontrolowane odzyskiwanie rund wyłącznie przez RELEASE, pakiet 134

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.12**.
Wdrożenie lokalne; A43.40/.41 nadal otwarte, bez aktywacji czyszczenia.

## Implementacja

Order V41 dodaje cztery kolumny istniejącego nagłówka rundy: `cleanup_only`,
`recovery_token`, `recovery_until`, `recovery_attempts`. Nie dodaje tabel.
Inwentarz: **118 tabel / 1110 kolumn / 209 migracji**.

`CommerceRetentionRoundRecovery.recover` wykonuje jeden jawnie wywołany,
ograniczony przebieg dla konkretnego właściciela/zadania, intencji i rundy.
Przejęcie włącza nieodwracalny tryb cleanup-only i nadaje losowy token
właściciela na 15 sekund, według zegara PostgreSQL. Najwyżej osiem przejęć
na rundę; brak automatycznego retry, skanowania wszystkich rund lub harmonogramu.
Wygaśnięcie dotyczy uprawnienia procesu do zapisu, nie retencji historii.

Przebieg wysyła wyłącznie RELEASE dla zachowanych prób bez zapisanego
potwierdzenia — najwyżej siedem żądań, z pierwotnym zakresem i tokenem rundy.
Nie tworzy nowej próby, PREPARE, VERIFY lub źródłowej dzierżawy. Stan stop,
anulowanie albo zakończenie bieżącej generacji nie zabraniają sprzątania
oryginalnego zakresu; obcy zakres i kwarantanna restore są odrzucane.

Każde RPC ma kontrolę właściciela przed i po; odbywa się poza SQL.
Potwierdzenie musi odpowiadać zakresowi, akcji i statusowi RELEASED. SQL
sprawdza token właściciela, ważność dzierżawy i kontekst lokalny transakcji.
Nieaktualny proces nie może dopisać potwierdzenia, zamknąć rundy ani zwolnić
przejęcia nowszego procesu. Niepewny COMMIT przejęcia nie uruchamia RPC
i nie jest traktowany jako rollback; zachowany claim wygasa według zegara bazy.

Wynik SETTLED oznacza wyłącznie zapisane rozliczenie wszystkich zachowanych
prób. Błąd transportu, negatywna odpowiedź lub inna generacja źródła pozostawiają
próbę oczekującą. TTL i zmiana epoki nie udają RELEASE. Niedokończony przebieg
zwalnia tylko własny claim albo pozostawia go do wygaśnięcia. Kolejny przebieg
wymaga jawnego wywołania i dostępnego budżetu; nie powstaje nowa generacja.

## Ochrona starej instancji

Po przejęciu nie można dodać nowych prób do tej rundy. Pierwotny workflow
sprawdza tryb rundy przed/po RPC, po zapisie próby i w końcowej transakcji
obejmującej claim/ocenę. Ta ostatnia utrzymuje blokadę nagłówka przez ocenę.
Stary proces kończy przebieg kodem INTENT_ROUND_TAKEN_OVER, bez nowej oceny.
Nie może zapisać potwierdzenia w imieniu procesu odzyskiwania.

Jeżeli PREPARE było już zapisane i znajduje się w drodze, RELEASE może dotrzeć
do źródła wcześniej. Ochrona tokenów z pakietu 133 odrzuci późny PREPARE.
Źródła i wszystkie repliki Order muszą mieć nowe migracje/adaptery. Nie jest
to gwarancja działania ze starymi replikami ignorującymi te kontrole.
Odpowiedź już wysłanego żądania może dotrzeć po utracie właściciela; nie daje
to prawa zapisu. Samo przejęcie nie jest potwierdzeniem zakończenia procesu.

## Restore i odbiór

Dotychczasowy krok kwarantanny rund uruchamia nowy trigger V41: usuwa token
i termin odzyskiwania, zachowując cleanup-only, budżet, próby i potwierdzenia.
Odtworzony claim nie wznawia pracy i nie pozwala wysyłać żądań do źródeł.
Wymagany jest pełny drill, także ze stanem po przejęciu rundy.

Dowody: `tmp/a43-round-recovery-134/report.json`.
Odbiór: **92 PostgreSQL PASS** (83 workflow, 9 HTTP) oraz **90 unikalnych
Python PASS**, bez pominięć. Kontrole strukturalne i candidate PASS; pełna
gotowość **NOT_READY / kod 2**. W HTTP sprawdzono odzyskiwanie w 7 źródłach
i przejęcie podczas rzeczywistego Inventory PREPARE. Wygaśnięcie właściciela
w testach jest ustalane przez zegar DB z ograniczonym oczekiwaniem.
Drill 14 baz/Redis PASS: `tmp/privacy-recovery-9dca08db/report.json`. Odtworzona własność
usunięta, cleanup-only/budżet/historia i 14 markerów źródeł zachowane;
releaseAllowed=false, bez automatycznego wznowienia. Wcześniejszych
pakietów i powtórnych przebiegów nie dolicza się do tych liczb.

Nie dodano endpointu, przycisku CC, schedulera ani aktywacji na dev/test/prod.
To wewnętrzny mechanizm z jawnym wywołaniem; bez odbioru wdrożenia aplikacji
lub mieszanego zestawu wersji. `activationAllowed=false`, `runtimeWired=false`,
`executionTerminated=false`. Nie uruchamia redakcji, usuwania danych ani
pełnego protokołu wykonania D02. D01/P01, D09/SAFE i P09 pozostają odłożone.

## Punkt wznowienia

Kolejny niezależny zakres: domknięcie dowodów awarii odzyskiwania — timeouty,
utrata własności w czasie żądania do rzeczywistego źródła, zmieniony binding
i niepewny zapis końcowego rozliczenia. Następnie kompletne źródła globalnych
blokad i protokół upoważnienia/wykonania D02, przy pozostawieniu nierozstrzygniętych
polityk w stanie odmowy. Sam SETTLED nie jest zgodą na usuwanie.
Zakresy wymagające decyzji/wartości odkładać; A05, A41 i zdjęcia nadal odłożone.
