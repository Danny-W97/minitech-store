# A43.40 — atomowa inspekcja powiązana z wersją, pakiet 151

Stan implementacji: 02.11.2026, Audit_0.1. Rewizja polityki
`a43.40-2026-11-02.6`. A43.40/.41 pozostają otwarte, czyszczenie wyłączone.

## Zachowanie

`CommerceRetentionVersionedHoldInspection.inspect` łączy sprawdzenie
serwerowej wersji przeglądu 150 z rezerwacją jednej inspekcji źródła.
W jednej transakcji, pod blokadą rodzica, ponownie sprawdza zakres,
historię, wynik, czas oraz dokładne powiązanie źródła i intencji.
Stop blokuje przygotowanie nawet wtedy, gdy wersja lokalna jest aktualna.
Nie przyjmuje podglądu lub czasu klienta jako dowodu.

Rezerwuje istniejącą próbę odczytu oraz niezmienny rekord kroku w Order.
Oba zapisy zatwierdzają się razem; błąd zapisu kroku wycofuje też próbę.
Własna nowa próba zmienia historię, więc krok zapisuje skróty stanu
po jej dodaniu. Zakres musi pozostać zgodny ze sprawdzoną wersją.
Ważność jest kopiowana ze starego przeglądu, bez przedłużenia.

Po potwierdzonym COMMIT wywołuje istniejący protokół inspekcji jednego
źródła. UUID kroku jest UUID próby i wyzwaniem protokołu. Nie utrzymuje
transakcji SQL podczas HTTP. Sprawdza przygotowany stan bezpośrednio
przed odczytem i przed przyjęciem odpowiedzi. Zmiana zakresu, historii,
wyniku, stop, kwarantanna, wygaśnięcie lub cofnięcie zegara blokują
przyjęcie pozytywnej odpowiedzi. To punktowe kontrole lokalne; nie tworzą
rozproszonej blokady ani gwarancji niezmienności źródeł po inspekcji.

Niepewny COMMIT rezerwacji zwraca UNAVAILABLE i nie uruchamia odczytu.
Ponowienie tego samego UUID odczytuje PENDING, zapisany wynik lub
SUPERSEDED; nigdy nie wysyła ponownie. Niepewnego wyniku nie uznaje się
za dowód braku blokady. Niepewny COMMIT wyniku można rozpoznać przez
ponowienie UUID, bez kolejnego HTTP. Późna odpowiedź zastąpionej próby
nie zapisuje wyniku. Zastąpienie nie dowodzi zakończenia starego wykonawcy.

Historyczny RECORDED pozostaje historią także po zmianie danych lub
wygaśnięciu. Wszystkie wyniki mają diagnosticOnly=true i uprawnienia
do wykonania/aktywacji/pełnego pokrycia sprawy ustawione na false.
Odczyt źródła może zwrócić brak, konflikt lub kwarantannę; nie odtwarza
blokady, nie redaguje danych i nie potwierdza pełnej sprawy.

## Ograniczenia i schemat

Order V50: `commerce_retention_hold_review_steps`, 10 kolumn. Zawiera
UUID kroku, wersji, intencji i próby, źródło, trzy przygotowane skróty,
odziedziczony termin oraz czas utworzenia. Bez podglądu, treści spraw,
kontaktu, tokenów i kluczy. Skróty i ID są pseudonimowe.

SQL sprawdza niezmienność, zakres, powiązanie próby, brak wyniku/stopu,
znaną kwarantannę i oryginalny termin. Jedna inspekcja na źródło/wersję;
wspólny dotychczasowy budżet 8 prób na źródło/wniosek, bez resetu.
Inwentarz: 145 tabel / 1307 kolumn / 232 migracje; pozostałe mechanizmy,
przestrzenie Redis, kategorie kopii i polityki bez zmiany liczby.
Metadane mają ochronę D03, bez automatycznego TTL; automatyczny surowy
eksport jest wyłączony, obowiązuje ręczna ocena zakresu.

Restore zachowuje UUID, powiązania, skróty, czas i budżet pod kwarantanną
rodzica. Sprawdza strukturę oraz oryginalne powiązanie i termin; nie
porównuje dawnych skrótów z odtworzonym bieżącym stanem jako zgody.
Nie odnawia ważności, nie wznawia próby i nie dopisuje brakującego wyniku.
Brak historycznej tabeli nie udaje istnienia kroku; niespójność blokuje odbiór.

Brak endpointu operatora, przycisku CC, schedulera lub konfiguracji dev/test.
Wywołanie jest jawne i typowane wyłącznie jako odczyt źródła, bez ogólnego
callbacku wykonującego zmianę biznesową. Publiczna metoda odrzuca cudzą
transakcję. Pakietowe odczyty wymagają transakcji wewnętrznego koordynatora.

## Odbiór i dalsze prace

**82 unikalne Java (75 PostgreSQL / 7 jednostkowych) + 116 Python PASS**: 37 nowych Java (27 Root / 10 HTTP), 45 regresji;
3 nowe metody Python i 8 mutacji kontraktu. Końcowy przebieg wszystkich
czterech zestawów PASS, bez błędów lub pominięć; wstępny przebieg 25 testów
nowej klasy nie jest doliczany ponownie. Bramki PASS, NOT_READY / 2.
Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-9d0077d7/report.json`; 1 krok, 2 wersje i 5 prób
zachowane pod kwarantanną, bez wznowienia.
Wyniki weryfikacji: `tmp/a43-versioned-inspection-151/report.json`.
Migracja i próby odtworzenia dotyczą wyłącznie izolowanych fikstur.

Atomowe przygotowanie diagnostycznej inspekcji jest zakresem tego pakietu.
Pełny protokół D02 nadal wymaga powiązania upoważnienia sprawy, świeżych
dowodów wszystkich ośmiu uczestników oraz bezpiecznej mutacji/odzyskania.
Rzeczywisty wystawca, tożsamości, klucze, rotacja i odwoływanie wymagają
odbioru użytkownika. D01/P01, D09/P03–P05/SAFE, P09 i role/rejestr pozostają
odłożone. Nie traktować lokalnego sukcesu diagnostyki jako zamknięcia A43.
A05, A41 i zmiany samych zdjęć A27–A31/A40 nadal odłożone.

Po tym pakiecie można wykonywać niezależne prace A40 dotyczące HTML/JS
i indeksowalności oraz lokalne regresje A33. Wymagające decyzji elementy
należy odkładać zgodnie z poleceniem użytkownika.
