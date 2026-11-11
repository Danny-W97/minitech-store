# A43.40 — ponowna ocena dowodów dla intencji, pakiet 129

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.7**.
Wdrożenie lokalne; A43.40/A43.41 nadal otwarte, czyszczenie wyłączone.

## Wdrożony zakres

`revalidateIntentAndAssess` wiąże ponowny odczyt ośmiu źródeł z konkretną
intencją, jej zakresem i rewizją. Przygotowanie musi odpowiadać bieżącej
wersji/właścicielowi zamówienia, generacji, fazie i polityce oraz zapisanym
rewizjom dowodów i znanych blokad. Zmiana nie jest nadpisywana w intencji:
wynik to INTENT_STALE. Ponowne przygotowanie zmienionego zakresu wymaga
kontrolowanego nowego zadania/generacji, nie obejścia niezmienności intencji.

Przed rozpoczęciem wymagane są dotychczasowe obserwacje ośmiu uczestników,
bieżące bindingi i dostępność zadania do oceny. W trakcie wykonywane są
ponowne odczyty Order, Payment, Inventory, Returns, Promotion, Delivery,
Billing i Notification — najwyżej siedem wywołań transportu zdalnego.

## Kontrole przebiegu

- Stan i rewizje intencji są sprawdzane przed i po każdym odczycie. Stop
  lub anulowanie przerywa dalsze odczyty i nie dopuszcza do nowej oceny.
  Odpowiedź już wysłanego żądania może pozostawić obserwację w magazynie;
  nie jest to wykonanie ani potwierdzenie zatrzymania wykonawcy.
- Porównywany jest zakres udziału siedmiu bindingów: epoka i rewizja źródła.
  Dla lokalnego Order używa się rewizji procesu; epoka 0 oznacza brak zdalnego
  bindingu, nie dzierżawę lub upoważnienie.
- SQL nie obejmuje zdalnych wywołań. Równoległe żądanie stopu może zostać
  zapisane w czasie oczekiwania na odpowiedź źródła.
- Końcowa kontrola intencji, przejęcie zadania i zapis oceny są w jednej
  lokalnej transakcji. Stop nie może wejść między tę kontrolę i zapis.
  Błąd SQL wycofuje także przejęcie zadania, bez pozostawienia nowej dzierżawy
  lub zużycia próby. Istniejąca dzierżawa innego procesu nie jest przejmowana.
- Wynik jest ważny jako zapis/diagnoza z chwili COMMIT. Późniejszy stop
  obowiązuje przy następnej pracy i nie jest cofany przez wynik oceny.
- Zwracany zakres obserwacji jest niezmiennym wynikiem diagnostycznym.
  Częściowy wynik po błędzie nie oznacza pełnego pokrycia lub aktualnej zgody.

Stan intencji pozostaje WAITING_AUTHORIZATION; przegląd nie dopisuje jej
przejścia stanu. Nawet wynik INTENT_REVALIDATED_ASSESSED oznacza wykonanie
oceny i może zawierać WAIT lub nieuzgodniony proces. Zawsze ma
`activationAllowed=false` i `executionTerminated=false`.

**Sekwencyjne odczyty nie są globalną migawką lub barierą mutacji.** Nie
pozyskuje się tu dzierżaw źródeł. Brakujące D01/D09/P09, aktualna piecza
rejestru i pełne globalne upoważnienie nie są zastępowane tym wynikiem.
Stare wykonawcze API nadal odmawia działania. Brak nowego endpointu,
widoku CC, harmonogramu lub automatycznego retry.

## Odbiór i granice

Wyniki, XML i odciski źródeł: `tmp/a43-intent-review-129/report.json`.
Odbiór: **136 PostgreSQL PASS** (42 workflow, 15 intencji, 79 źródeł)
i **85 unikalnych Python PASS**, bez pominięć. Kontrole strukturalne i candidate
PASS, pełna gotowość **NOT_READY / kod 2**. Wyniki poprzednich pakietów
nie są doliczane do tego przebiegu.
Testy obejmują właściciela/fazę/rewizje, brak kompletu obserwacji, zmianę
dowodów po przygotowaniu, stop/anulowanie, stop w czasie zdalnego odczytu,
awarię źródła, kwarantannę, budżet/dzierżawy i rollback końcowej oceny.
Przebieg HTTP sześciu usług używa rzeczywistego filtra/kontrolera i ich baz;
Inventory w tej konkretnej fiksturze jest transportem symulowanym. To nie
jest odbiór uruchomionych aplikacji dev/test lub pełne A43.41.

Bez nowych migracji: **109 tabel / 1065 kolumn / 200 migracji**.
Nie zmieniono procedury restore ani schematu; pełnego drillu 14 baz/Redis
nie powtarzano. Ostatni dowód z pakietu 128 pozostaje historyczny:
`tmp/privacy-recovery-3d360cc0/report.json`, releaseAllowed=false.
Nie odczytywano sekretów i nie zmieniano rzeczywistych dev/test/prod.

## Punkt wznowienia

Następny niezależny zakres: powiązać intencję z koordynacją dzierżaw
obserwacji źródeł i ich zwalnianiem po stopie, błędzie lub utracie zakresu.
Dzierżawy odczytów nadal nie zezwalają na mutację; pełne upoważnienie,
wykonanie i jego potwierdzenia wymagają osobnego kompletnego protokołu.

D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw pozostają
odłożone. Zgodnie z poleceniem użytkownika zakres wymagający wartości/decyzji
odkładać i podejmować następny niezależny krok. A05, A41 i zmiany samych zdjęć
nadal odłożone. Nie przyjmować wartości domyślnych zamiast brakującej zgody.
