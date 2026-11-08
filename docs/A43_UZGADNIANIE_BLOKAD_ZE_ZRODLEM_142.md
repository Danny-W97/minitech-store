# A43.40 — porównanie potwierdzeń z odczytem źródła, pakiet 142

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.20**.
Odbiór lokalnego zakresu; A43.40/.41 pozostają otwarte.

## Przebieg

`CommerceRetentionHoldReconciliation.inspect` jawnie porównuje jeden
historyczny ACK z 140 z odczytem jednej z siedmiu usług. Wymaga dokładnego
zakupu, wniosku i referencji sprawy. Pobiera z SQL źródłowy request_id,
UUID potwierdzonej blokady, rodzaj i powód. Brak ACK lub niedopasowany
zakres kończy krok bez RPC. Referencja sprawy nie jest dowodem własności.

Order tworzy nowy losowy challenge i wykonuje najwyżej jeden RPC poza
swoją transakcją SQL. Źródło sprawdza własny typ i pod blokadami wspólnymi
z zapisującym rejestr odczytuje stan zakupu oraz blokadę po request_id.
Sprawdza UUID blokady, zakup, rodzaj i powód, bez zmian danych.
Zwraca czas odczytu, binding_epoch i source_revision tego lokalnego stanu.

| Wynik po stronie Order | Znaczenie |
|---|---|
| MATCH_AS_OF_READ | Dokładna blokada istniała w chwili odczytu źródła. |
| MISSING_AT_SOURCE | Źródło zna zakup, ale nie ma tej blokady. |
| SOURCE_CONFLICT | Źródłowy request_id nie odpowiada zapisanej tożsamości lub treści blokady. Nie ujawniamy obcego UUID. |
| SOURCE_UNKNOWN | Źródło nie zna zakupu; nie tworzymy bindingu i nie uznajemy braku danych za zgodę. |
| SOURCE_QUARANTINED | Zakup w źródle jest objęty kwarantanną, również gdy historyczna blokada nadal istnieje. |
| ROOT_QUARANTINED | Order wykrył kwarantannę rodzica, zakresu zakupu/klienta albo stanu Order przed lub po RPC. |
| NO_HISTORICAL_RECEIPT / UNKNOWN_REQUEST | Brak dokładnego wcześniejszego potwierdzenia lub zakresu; bez RPC. |
| UNAVAILABLE | Błąd SQL/transportu, timeout, niepoprawna, obca lub przeterminowana odpowiedź. Nie oznacza braku blokady. |

Challenge i całe polecenie muszą wrócić bez zmian. Odpowiedź ma wiek do
15 sekund i tolerancję czasu przyszłego do 5 sekund; walidacja jest
powtarzana po końcowym odczycie Root. Odpowiedź z poprzedniego wywołania
nie pasuje do nowego challenge. Źródłowa kwarantanna ma pierwszeństwo
przed dopasowaniem blokady. Order odrzuca obserwację, jeśli w czasie RPC
zmieni się jego znany zakres lub pojawi kwarantanna.

MATCH_AS_OF_READ nie gwarantuje niezmienności źródła po zakończeniu
odczytu. Test pokazuje późniejszą kwarantannę: pierwszy wynik pozostaje
obserwacją historyczną, następny probe zgłasza SOURCE_QUARANTINED.
Nie jest to globalny checkpoint, kompletność spraw lub upoważnienie D02.

## Ograniczenia i konfiguracja

Wynik zawsze ma `activationAllowed=false`, `completeCaseCoverage=false`,
`repairPerformed=false` i `persistentReconciliationRecorded=false`.
Porównanie nie nadpisuje ACK, nie dodaje prób, nie resetuje budżetu,
nie odtwarza blokady, nie znosi kwarantanny i nie ponawia RPC automatycznie.
Postęp z 141 nadal przedstawia historię, a nie aktualny dowód źródłowy.
Rozbieżność wymaga dalszej kontrolowanej obsługi; samo jej wykrycie nie
naprawia danych po utracie pojedynczej bazy.

Nowy POST `/internal/privacy/commerce-retention/source-hold-inspection`
jest dostępny tylko dla uwierzytelnionej usługi Order. Limity: 2048 B
żądania i 4096 B odpowiedzi, bez przekierowań i ujawniania treści błędów.
Osobna flaga `privacy.commerce-retention-source-hold-inspection.enabled`
jest domyślnie false. Nie zmieniono konfiguracji środowisk, nie dodano
operator API, schedulera, przycisku CC lub automatycznego wywoływania Root.
Źródło i Root odrzucają zewnętrzną transakcję wywołującego.

Schemat i restore są bez zmian: **129 tabel / 1183 kolumny / 220 migracji**;
47 mechanizmów, 6 przestrzeni Redis, 20 kategorii kopii i 26 polityk.
Nie dodano nowego magazynu obserwacji ani nowych kopii danych klientów.

## Odbiór i punkt wznowienia

**178 Java (154 PostgreSQL / 24 jednostkowe) + 98 unikalnych Python PASS**, bez błędów i pominięć;
64 nowe Java (52 HTTP/SQL + 12 jednostkowych), 8 mutacji kontraktu
w istniejącym teście Python. Bramki/candidate PASS, NOT_READY / kod 2.
Dowody: `tmp/a43-hold-reconciliation-142/report.json`.
Odbiór obejmuje rzeczywiste kontrolery/filtry HTTP i osiem testowych baz
PostgreSQL. Utrata rejestru i brak zakupu są symulowane wyłącznie przez
usunięcie odpowiednich danych izolowanych fikstur. Nie jest to pełny
odbiór uruchomionych aplikacji dev/test lub wdrożenia mieszanych wersji.
Pełnego drillu restore nie powtarzano; historyczny drill 140 pozostaje
w `tmp/privacy-recovery-03d88010/report.json`. Sprawdzamy niezmienność
źródeł restore i migracji zamiast doliczać dawny przebieg do obecnych testów.

Następne: trwała, ograniczona historia wyników porównania i przegląd
rozbieżności z zachowaniem tożsamości oraz kwarantanny. Potem kontrolowany
proces rozliczenia/naprawy bez nadpisywania historii lub resetowania prób,
zweryfikowany proces rzeczywistych spraw, kompletne aktualne dowody
i pełne upoważnienie/wykonanie/odzyskiwanie D02.

D01/P01, D09/P03–P05/SAFE, P09 i odbiór ról/rejestru pozostają odłożone.
A05, A41 i same zdjęcia A27–A31/A40 pozostają odłożone.
Czyszczenie wyłączone; bez sekretów i operacji na rzeczywistych danych.
