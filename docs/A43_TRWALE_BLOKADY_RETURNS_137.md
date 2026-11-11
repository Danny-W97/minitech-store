# A43.40 — trwałe blokady źródła Returns, pakiet 137

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.15**.
Zakres lokalny; A43.40/.41 otwarte, bez aktywacji czyszczenia.

## Wdrożenie

Returns V12 dodaje `commerce_retention_source_holds`: siedem pól
z minimalnym identyfikatorem zakupu, blokady i wniosku, rodzajem blokady,
kodem powodu, rewizją źródła oraz czasem zapisu. Bez kontaktu, swobodnej
treści i tokenów. To rejestr znanych blokad, nie kompletny rejestr spraw.

Wewnętrzny `CommerceRetentionSourceHolds.place` obsługuje wyłącznie Returns
i zakup ze stabilnym ID istniejącym w source_state. Nie dopasowuje kont
przez e-mail. Dopuszcza trzy negatywne blokady: dalsze zachowanie, otwarta
sprawa i aktywne retry/DLQ. Zaufany proces może je zarejestrować z kodem
powodu; nie dodano endpointu zapisu, przycisku CC ani nadania uprawnień.

Trigger blokuje zakres zakupu wspólną blokadą SQL, nadaje rewizję i w tej
samej transakcji unieważnia obserwacje oraz zapisuje outbox. Ponowienie
identycznego request_id zwraca tę samą blokadę bez kolejnej rewizji.
Sprzeczne użycie request_id jest odrzucane; rollback obejmuje cały skutek.
Wpisy nie mogą być zmieniane lub usuwane zwykłym UPDATE/DELETE.
Brak automatycznego TTL, zwalniania lub domyślnego zamknięcia sprawy.

Blokada jest związana ze stabilnym zakupem, dlatego przetrwa zmianę
generacji i epoki wiązania. Jej zapis unieważnia również aktywną obserwację,
ale nie usuwa tokenu dzierżawy i nie otwiera zapisów biznesowych. Odpowiedź
na stary zakres staje się STALE. Nie oznacza to rozliczenia RELEASE.

Odczyt Returns uwzględnia trwałe blokady i zwraca odpowiedni BLOCKED.
Brak wpisu nadal daje UNKNOWN, nigdy CONFIRMED. Dotychczasowa konserwatywna
blokada return_requests o statusie innym niż REJECTED pozostaje; nie rozstrzygnięto
uzgodnienia efektów A16 ani zasad zamknięcia pozostałych spraw.

## Historia Order i zgodność

Order V43 dodaje `hold_state` w historii obserwacji. Istniejące wpisy dostają
UNKNOWN. Dla UNKNOWN zachowany jest dokładnie dotychczasowy odcisk v1;
obserwacja BLOCKED używa v2, który uwzględnia stan blokady. Dzięki temu
stare obserwacje mogą być ponowione idempotentnie, a zmiana stanu blokady
przy tej samej rewizji nie zostaje uznana za identyczną odpowiedź.

Protokół nadal odrzuca dodatnie potwierdzenia i nieaktualne zakresy.
Root zapisuje historyczny stan i negatywny dowód w jednej transakcji,
bez utrzymywania SQL przez HTTP. Historia nie jest aktualnym upoważnieniem.

Inwentarz: **119 tabel / 1118 kolumn / 212 migracji**; dodano jedną tabelę,
siedem jej kolumn oraz jedno pole istniejącej tabeli i dwie migracje.
Pozostaje 47 mechanizmów, 6 przestrzeni Redis, 20 kategorii kopii i 26 polityk.
Metadata mają tę samą ochronę jak istniejące znaczniki protokołu: bez
automatycznego wygasania. Nie trafiają do surowego automatycznego eksportu;
ewentualne przypisanie i ocena odbywają się po stabilnym ID zakupu, bez
dopasowania przez e-mail. Nie zastępuje to polityki retencji materiałów spraw.

## Pokrycie i granice

Aktualizacja [macierzy ośmiu uczestników z 136](A43_POKRYCIE_BLOKAD_D02_136.md):
Returns odczytuje teraz także trwały rejestr znanych blokad i jego rewizję.
Pozostałe sześć usług zdalnych nie dostało tego rejestru w pakiecie 137.
Order nadal posiada własne blokady zakupu/konta z V31.

Nie wdrożono kompletności wszystkich źródeł wniosków i sporów, procesu
przyjmowania/rejestrowania wniosków przez obsługę, zwalniania blokad,
kontroli niezależnej aktualnej głowy D09 ani pełnego wykonawcy D02.
Rejestr przechowywany w backupie nie dowodzi braku późniejszych wniosków.
Restore zachowuje historię i kwarantannę; nie daje prawa wznowienia.
Nie przywracamy zgody z braku wpisów ani z samego wieku danych.

## Odbiór i punkt wznowienia

**132 Java (117 PostgreSQL / 15 jednostkowych) + 90 unikalnych Python PASS**, bez błędów i pominięć; 12 nowych przypadków
i pozostałe regresje. Bramki/candidate PASS; gotowość NOT_READY / kod 2.
Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-acaf7452/report.json`; rejestr
Returns i 4 historyczne obserwacje, w tym BLOCKED, zachowane bez upoważnienia.
Dowody: `tmp/a43-source-holds-137/report.json`.
Migracje dotyczą wyłącznie fikstur. Weryfikacja obejmuje odczyt trzech
rodzajów blokad po ponownym związaniu źródła, HTTP, idempotencję,
współbieżność, rollback, niezmienność, aktywną dzierżawę, zgodność v1
i konflikt zmienionej obserwacji. Pełny drill ma zachować rejestr Returns
oraz historyczny BLOCKED w Order, bez aktualnego upoważnienia.

Następny niezależny zakres: rozszerzać trwałe blokady na pozostałe źródła
według macierzy, z ich migracjami, kontrolą rewizji i odbiorem restore.
Następnie domknąć kompletność i proces obsługi spraw, a potem upoważnienie,
współbieżną mutację i odzyskiwanie D02. Nie włączać wykonania pojedynczą flagą.

D01/P01, D09/P03–P05/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw
pozostają odłożone. Zakresy wymagające wartości/decyzji użytkownika odkładać
i wykonywać niezależne prace. A05, A41 i same zdjęcia A27–A31/A40 bez zmian.
`activationAllowed=false`, `runtimeWired=false`; brak operacji na danych
rzeczywistych środowisk, sekretach lub konfiguracji runtime.
