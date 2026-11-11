# A43.40 — rejestr intencji i bezpiecznych przejść D02, pakiet 128

**Nowszy punkt wznowienia:** [pakiet 129 — ponowna ocena dowodów dla intencji](A43_WERYFIKACJA_DOWODOW_INTENCJI_129.md).
Poniżej historia rejestru 128; powiązanie z oceną dowodów dodano następnie
w 129, nadal bez upoważnienia do mutacji.

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.6**.
Wdrożenie lokalne; pełne A43.40/A43.41 nadal otwarte, czyszczenie wyłączone.

## Co wdrożono

Order V39 dodaje `commerce_retention_intents` i niezmienny dziennik
`commerce_retention_intent_events`. Intencja ma dokładny zakres zadania:
zamówienie, generację, wersję, fazę i politykę oraz zapis rewizji dowodów
i znanych blokad z przygotowania. To metadane oczekującej operacji, nie dowód
jej kwalifikacji lub upoważnienia. Zmiana dowodów nie aktualizuje zapisanego
przygotowania i nie nadaje zgody automatycznie.

| Stan | Znaczenie |
|---|---|
| WAITING_AUTHORIZATION | Zarejestrowano przygotowanie, bez prawa wykonania. |
| STOP_REQUESTED | Zapisano żądanie zatrzymania intencji. Nie potwierdza zakończenia wykonawcy. |
| CANCELLED | Anulowano nierozpoczętą intencję; nie potwierdza faktycznej mutacji lub zatrzymania procesu. |
| QUARANTINED | Odtworzona intencja zablokowana, bez przejścia do wznowienia. |

**Nie ma stanu ani metody AUTHORIZED, EXECUTING lub COMPLETED.** Wykonanie
D02, pełne globalne upoważnienie oraz potwierdzenia mutacji źródeł pozostają
niezaimplementowane. Starsze workery nadal zwracają
`RETENTION_EXECUTION_NOT_CONFIGURED`, również przy istniejącej intencji.

## Zakres i niezmienniki

- Jedna intencja na generację/fazę i najwyżej cztery zdarzenia na intencję:
  przygotowanie, opcjonalny stop, anulowanie, kwarantanna. Nowe identyfikatory
  ponowień nie produkują nieograniczonego dziennika.
- Przygotowanie sprawdza właściciela, wersję, bieżące zadanie, znane blokady
  i kwarantannę. Historyczna intencja nie jest przepisywana po zmianie zakresu.
- Stop/anulowanie pozostają możliwe dla tej samej historycznej generacji,
  nawet po jej wycofaniu; nie wolno użyć identyfikatora innej generacji lub osoby.
- Przejścia mają blokady SQL, porównanie rewizji i idempotencję żądań.
  Jeden identyfikator żądania nie może zostać zużyty w innej akcji/intencji.
- Trigger dopisuje zdarzenie w tej samej transakcji co nagłówek. Błąd zapisu
  zdarzenia wycofuje zmianę nagłówka. Ponowienie po niepewnym COMMIT odczytuje
  stan z SQL i nie dubluje przygotowania lub żądania stopu.
- SQL chroni niezmienność zakresu i historii, dozwolone stany i kierunki
  przejść. Kwarantanna jest monotoniczna; nie ma automatycznego wznowienia.
- Przygotowanie nie zmienia dowodów, dzierżawy lub budżetu prób oceny.
  Wynik zawsze ma `activationAllowed=false` i `executionTerminated=false`.
- Dane są pseudonimowe: techniczne ID, rewizje, stany i czasy. Bez kontaktu,
  treści, tokenów dostępu lub dodatkowej kopii identyfikatora klienta.
  Obowiązuje dotychczasowa ochrona metadanych protokołu bez automatycznego TTL.

API istnieje w kodzie procesu Order i kontrolowanym workflow. Nie dodano
endpointu klienta/operatora, widoku CC, harmonogramu, wykonawcy lub flagi
aktywującej. Rzeczywiste uprawnienia operatorów i konfiguracja pieczy nadal
wymagają odrębnego odbioru; oznaczenia operator-1/operator-2 nie nadają praw.

## Restore i odbiór

Odtwarzanie izolowanego środowiska obejmuje nowy rejestr. Znane tabele muszą
być obecne razem; historyczny brak obu nie tworzy fikcyjnych operacji.
Kwarantanna przetwarza po 100 intencji, z limitami czasu i liczbą partii.
Zachowuje wcześniejsze zdarzenia, dopisuje jedno zdarzenie kwarantanny i nie
potwierdza zakończenia wykonawcy. Powtórny replay nie dodaje zdarzeń.

Dowody, XML i logi: `tmp/a43-intents-128/report.json`.
Odbiór: **45 PostgreSQL PASS** (15 nowych scenariuszy intencji i 30 regresji
workflow) oraz **85 unikalnych Python PASS**, bez pominięć. Pierwszy przebieg
Python ujawnił nieaktualne fikstury pomijające nowy etap kwarantanny; po ich
uzupełnieniu całą wskazaną regresję powtórzono. Nie sumuje się prób ani wyników
pakietu 127. Kontrole strukturalne i candidate PASS; gotowość NOT_READY / kod 2.
Drill: `tmp/privacy-recovery-3d360cc0/report.json` — **PASS**, 14 baz i Redis,
3 intencje w kwarantannie, zachowana historia i idempotentny replay,
`releaseAllowed=false`. Nie jest to potwierdzenie zakończenia wykonawcy.
Inwentarz po V39: **109 tabel / 1065 kolumn / 200 migracji**.
Nowe migracje sprawdzono wyłącznie na izolowanych zasobach testowych,
bez ich zastosowania na rzeczywistych dev/test/prod. Nie odczytywano sekretów
użytkownika. Drill używa własnych, tymczasowych kluczy i danych testowych.

## Co odłożono i od czego zacząć

D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw nadal odłożone.
Zgodnie z poleceniem użytkownika etap wymagający konkretnych wartości lub
decyzji odkładać i podejmować następny niezależny zakres. Nie zastępować
decyzji wartością domyślną. A05, A41 i zmiany samych zdjęć pozostają odłożone.

Następny niezależny zakres: powiązać intencję z ponowną weryfikacją kompletu
bieżących dowodów oraz zakresem udziału źródeł. Weryfikacja przygotowania,
historyczna obserwacja i samo żądanie stopu nie mogą stać się upoważnieniem
lub potwierdzeniem wykonania. Warstwa rzeczywistej autoryzacji/mutacji nadal
musi odmawiać przy brakującej polityce lub dowodzie; nie dodawać flagi obejścia.
