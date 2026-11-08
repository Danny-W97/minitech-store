# A43.40 — lokalne przyjmowanie i powiązanie wniosków blokad, pakiet 139

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.17**.
Zakres lokalny, A43.40/.41 nadal otwarte, czyszczenie wyłączone.

## Wdrożenie

`CommerceRetentionHoldIntake.register` zapisuje w jednej transakcji Order:

1. Negatywną blokadę konkretnego zakupu w istniejącym rejestrze V31.
2. Wniosek: request_id, stabilne ID zakupu, niepoufną referencję sprawy,
   rodzaj i powód blokady, powiązanie z blokadą Root oraz czas zapisu.
3. Siedem niezmiennych identyfikatorów planowanych żądań do źródeł.

To adapter dla zaufanego procesu wewnętrznego. Referencja UUID nie jest
dowodem własności, poprawności wniosku lub wyniku oceny sprawy. Nie dodano
endpointu operatorskiego, uprawnień, przycisku CC, zapisu materiałów dowodowych
ani odczytu plików z rejestru użytkownika. D15/D18 nadal wymagają odbioru.

Order V44 dodaje dwie tabele: `commerce_retention_hold_requests` (8 pól)
i `commerce_retention_hold_source_requests` (4 pola). Inwentarz:
**127 tabel / 1172 kolumny / 219 migracji**. Bez zmian schematów siedmiu źródeł.
Nadal 47 mechanizmów, 6 przestrzeni Redis, 20 kategorii kopii i 26 polityk.

## Granice transakcji i identyfikatorów

Nie można zatwierdzić niepełnego planu: odraczany trigger SQL wymaga siedmiu
źródeł przed COMMIT. Błąd któregokolwiek zapisu wycofuje wniosek, blokadę,
plan i unieważnienie bieżącego zadania. API odrzuca istniejącą transakcję
wywołującego, aby nie zwrócić REGISTERED przed rzeczywistym lokalnym COMMIT.

Ponowienie dokładnie tego samego request_id i zakresu zachowuje blokadę
oraz wszystkie źródłowe identyfikatory. Zmiana zakupu, referencji sprawy,
rodzaju lub powodu daje konflikt i nie pozostawia dodatkowej blokady.
Równoległe powtórzenia są idempotentne. Utrata odpowiedzi COMMIT jest błędem
niepewnego wyniku, bez zwracania sukcesu; kolejne wywołanie odczytuje trwały zapis.

API tworzy osobną blokadę Root i nie przypisuje do wniosku dowolnej starszej
blokady o tym samym request_id. SQL sprawdza zakres ORDER, ID zakupu,
rodzaj i powód powiązanej blokady; nie wolno pożyczyć blokady konta lub innego
zakupu. Wniosek i plan pozostają niezmienne poza monotoniczną kwarantanną.
Odczyt wymaga dokładnej trójki: zakup, request_id i referencja sprawy.
Zakupy gości pozostają indywidualnymi zakupami; nie dopasowujemy e-maili.

## Co oznacza wynik

REGISTERED oznacza wyłącznie zatwierdzoną rejestrację w Order. Nie wywołuje
RPC, nie potwierdza siedmiu blokad zdalnych i nie daje zgody na mutację.
`remoteDeliveryConfirmed=false`, `activationAllowed=false`. Nie ma schedulera,
rezerwacji pracy lub automatycznych ponowień. `inspect` jest odczytem historii,
nie upoważnieniem. Wyniku ani siedmiu identyfikatorów nie wolno przyjąć jako
zastępstwa trwałych potwierdzeń źródeł.

Test integracyjny jawnie stosuje zapisane identyfikatory do siedmiu znanych
źródłowych rejestrów po zakończeniu transakcji Root. Powtórzenie jest idempotentne,
ale lokalny wniosek nadal nie udaje potwierdzenia dostarczenia. To kontrolowany
test adapterów, nie działający transport. Dla źródła bez znanego source_state
nie zakłada się doręczenia ani potwierdzonej nieobecności danych.

## Restore i dane

Odtworzone wnioski są oznaczane restore_blocked w partiach do 100,
z limitami czasu SQL i kontrolą kompletności planu. Identyfikatory, powiązanie
blokady i siedem żądań pozostają zachowane; brak automatycznego wysyłania.
Niekompletny schemat/plan lub błąd kwarantanny blokuje dalszy restore.
Starszy schemat bez obu tabel nie daje fikcyjnego potwierdzenia rejestracji.

Ponowienie odtworzonego wniosku zwraca QUARANTINED, bez cofania blokady.
Nowy wniosek dla znanej odtworzonej blokady zakresu lub stanu Order jest
odrzucany. Nie jest to odbiór globalnego odblokowania środowiska po restore.
Aktualna niezależna głowa i piecza D09 pozostają osobnym wymaganiem.
Bieżący dziennik usunięć nie jest dziennikiem nowych blokad/spraw i sam
nie potwierdza kompletności tych wniosków po utracie starszej kopii.

Tabele zawierają pseudonimowe identyfikatory protokołu, bez e-maila,
notatek, materiałów dowodowych lub tokenów dostępu. Bez automatycznego TTL
i surowego automatycznego eksportu; ręczna ocena własności po stabilnym
ID zakupu. Nie zastępują polityki retencji rzeczywistych materiałów spraw.

## Odbiór i punkt wznowienia

**52 PostgreSQL + 96 unikalnych Python PASS**, bez błędów i pominięć; 21 nowych Java
i 6 Python. Bramki/candidate PASS; gotowość NOT_READY / kod 2.
Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-7b175b22/report.json`; lokalny wniosek
w kwarantannie, identyfikatory i siedem planowanych żądań zachowane, bez
automatycznego doręczenia. Wyniki: `tmp/a43-hold-intake-139/report.json`.
V44 tylko na fiksturach. Odbiór obejmuje transakcję, konflikty zakresu,
współbieżność, niepełny plan, idempotencję, niezmienność, utratę odpowiedzi
COMMIT, mapowanie do siedmiu rejestrów i kwarantannę restore.

Następne: ograniczony i uwierzytelniony transport planowanych żądań,
rozliczenie niepewnych wyników i trwałe potwierdzenia źródeł. Nie potwierdzać
źródła nieznającego zakupu. Potem odbiór weryfikacji, przyjmowania i powiązania
rzeczywistych spraw przez obsługę, kompletność i wersjonowane dowody,
a następnie upoważnienie, współbieżna mutacja i odzyskiwanie D02.

D01/P01, D09/P03–P05/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw
pozostają odłożone. Punkty wymagające wartości/decyzji użytkownika odkładać
i wykonywać niezależne prace. A05, A41 i same zdjęcia A27–A31/A40 odłożone.
Brak zmian sekretów, konfiguracji lub danych rzeczywistych środowisk.
