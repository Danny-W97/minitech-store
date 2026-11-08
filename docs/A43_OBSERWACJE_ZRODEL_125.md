# A43.40 — sprawy, ponowienia i DLQ źródeł, pakiet 125

**Nowszy punkt wznowienia:** [pakiet 126 — lokalny odbiór HTTP i awarii](A43_ODBIOR_HTTP_I_AWARII_126.md).
Poniżej historia wdrożenia i odbioru 125; lokalny przebieg HTTP z jego listy
dalszych prac został następnie sprawdzony w 126.

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.3**.
Wdrożenie lokalne; A43.40/A43.41 nadal otwarte. Czyszczenie nieaktywne.

## Wdrożony zakres

Dodano źródłowe obserwacje sześciu usług, niezależne od wcześniej wdrożonej
oceny zegara zakończenia procesu. Dzięki temu można wykryć zaległość także
wtedy, gdy brakuje dowodów zakończenia całego łańcucha.

| Źródło | Sygnały blokujące lokalną ocenę |
|---|---|
| Returns | Sprawa o statusie innym niż REJECTED. COMPLETED również chronione do uzgodnienia skutków A16; brak spraw nie potwierdza globalnego braku spraw. |
| Payment | Niezakończona płatność, znaczniki przetwarzania, hold/kompensacja Stripe, niezakończony follow-up lub DEAD_LETTER, nieuzgodniony outbox płatności. |
| Promotion | Rezerwacja kuponu w stanie innym niż CONSUMED/RELEASED. |
| Delivery | Niezakończona przesyłka lub synchronizacja, znaczniki przetwarzania i ponowień. |
| Billing | Nieuzgodniony outbox faktur oraz inbox o statusie innym niż ACCEPTED, przypisane do danego zamówienia. |
| Notification | Niewysłane powiadomienia zakupowe, retry/lease, otwarte DLQ także po wysłaniu powiadomienia, nieuzgodniony inbox przypisany do zamówienia. Identyfikatory czatu nie są utożsamiane z zamówieniem. |

Odczyty używają kanonicznych kolumn i zapytań EXISTS. Nie czytają treści
wiadomości, adresów odbiorców, sekretów, tokenów rozmów lub payloadów JSON.
Nie próbują przypisywać historycznych rekordów przez e-mail. Rekordy bez
wiarygodnego powiązania z zamówieniem nie stają się dodatnim dowodem.

## Protokół i przebieg

- Wewnętrzny POST `/internal/privacy/commerce-retention/peer-guards`, tylko
  uwierzytelniona usługa Order. Istniejąca flaga
  `privacy.commerce-retention-peer-evidence.enabled` pozostaje domyślnie wyłączona.
- Polecenie zawiera źródło, zamówienie, generację, wersję, fazę, epokę bindingu,
  rewizję źródła, politykę i nowy losowy identyfikator konkretnego żądania.
  Źródło sprawdza aktualny binding i kwarantannę pod blokadą SQL.
- Ścisły JSON, limit żądania/odpowiedzi po 2048 bajtów; odrzucane są duplikaty
  i dodatkowe pola, zmienione zakresy, polityki, identyfikatory i `CONFIRMED`.
- Czas obserwacji pochodzi z zegara PostgreSQL. Odbiorca dopuszcza najwyżej
  15 sekund wieku i 5 sekund wyprzedzenia zegara. To kontrola ważności
  odpowiedzi protokołu, nie okres przechowywania danych. Większa rozbieżność
  daje UNAVAILABLE; nie jest automatycznie uznawana za zgodę.
- Root wykonuje najwyżej jeden zdalny odczyt na krok, poza transakcją SQL,
  po czym ponownie sprawdza właściciela, zakres, rewizję dowodów i blokad.
  Sprawdza ważność odpowiedzi także po oczekiwaniu na lokalne blokady.
- `reviewSourceGuards` w kontrolowanym workflow zapisuje wyłącznie wykryte
  blokady i wycofuje zablokowaną generację. Sam odczyt bez wykrytej blokady
  zwraca WAITING_DEPENDENCY; nie zapisuje pozytywnych dowodów ani zegara redakcji.
  Awaria daje UNAVAILABLE, niezgodny binding/restore — STALE.
- Wiersz GUARD jest niezmienny. Jeżeli w tej generacji zapisano już UNKNOWN,
  późniejszy sprzeczny dowód wycofuje generację zamiast nadpisania historii.
  Kolejna ocena wymaga nowej generacji i nowych odczytów.

Trwała historia GUARD przechowuje zakres oceny i blokujący strażnik.
Nie jest pełnym archiwum odpowiedzi sześciu źródeł. Trwałe, kompletne
potwierdzenia źródeł potrzebne do przyszłego wykonania pozostają otwarte.

## Granice odbioru

To obserwacje lokalnych sygnałów negatywnych. Nie stanowią kompletnego
inwentarza globalnych blokad, spraw innych systemów lub danych niepowiązanych.
`NO_PRESERVATION_HOLD` pozostaje UNKNOWN. Brak wierszy, terminalny status
albo sześć poprawnych odpowiedzi nie potwierdzają globalnego braku blokad.
Inventory ma wcześniejszy adapter obserwacji procesu, ale nie otrzymało
nowego endpointu GUARD w tym pakiecie. Nie ma nowego API klienta/operatora,
harmonogramu, widoku CC, automatycznego zbierania lub wykonywania czyszczenia.

Zachowane dowody i XML: `tmp/a43-source-125/report.json`.
Końcowa regresja: **112 Java PASS (15 jednostkowych / 97 PostgreSQL)**
oraz **80 unikalnych Python PASS**, bez pominięć. PostgreSQL: GlobalGuards 13,
Peers 54, Workflow 30; jednostkowe: nowy protokół 7, poprzedni protokół 8.
Kontrole strukturalne i candidate PASS; pełna gotowość NOT_READY / kod 2.
Pierwsza próba wykryła kolizję importów w nowym teście; po poprawce przebieg
powtórzono. Nie sumuje się prób ani historycznych wyników 123/124.
Bez nowych migracji: **106 tabel / 1031 kolumn / 198 migracji**.
Odbiór lokalny nie potwierdza konfiguracji ani uruchomienia rzeczywistych dev/test.
Nie odczytywano sekretów i nie zmieniano danych rzeczywistych środowisk.

## Odłożone zależności i kolejność dalszych prac

Zgodnie z poleceniem użytkownika z 01.11.2026 zakres wymagający wartości
lub decyzji odkładamy i przechodzimy do niezależnej implementacji.
Nie oznacza to usunięcia blokady ani zamknięcia etapu.

| Odłożony zakres | Co jest potrzebne od użytkownika | Co pozostaje chronione |
|---|---|---|
| D01/P01 | Kompletna polityka finansowa, wyjątki i wymagane dłuższe okresy. | Dokumenty finansowe i ich zależności; brak zgody na usunięcie. |
| D09/P03–P05/SAFE | Niezależny nośnik/host kopii, narzędzie SAFE i rzeczywista piecza bieżącego wskaźnika. | Brak potwierdzenia aktualnego niezależnego rejestru. |
| P09 | Wyjątki zachowania logów, monitoringu, Mailpit i materiałów spraw. | Brak aktywacji automatycznego czyszczenia tych magazynów. |
| D15/D18 — odbiór rzeczywistych ról i rejestru spraw | Wiążąca obsada/uprawnienia rzeczywistych kont i zabezpieczenie wybranego miejsca rejestru. Same oznaczenia operator-1/operator-2 nie nadają uprawnień. | Brak automatycznej obsługi spraw w imieniu niezweryfikowanej osoby. |
| A05 | Wartości Stripe i odbiór sandboxu, jeśli użytkownik wznowi odłożone A05. | Brak automatycznego wznowienia integracji Stripe. |

Następna niezależna praca: domknąć techniczny odbiór źródłowych obserwacji
przez rzeczywisty HTTP całego przebiegu oraz współbieżność, awarie i ponowienia;
rozszerzać zakres wyłącznie na podstawie kanonicznych powiązań. Następnie
implementować techniczne części protokołu D02, które nie wymagają brakującej
polityki. Pełna zgoda i wykonanie pozostają zablokowane do otrzymania dowodów.
Prace wymagające konfiguracji rzeczywistych rejestrów lub magazynów również
odłożyć, jeśli brakuje wartości. Nie dopytywać ponownie o świadomie odłożone
kwestie bez nowego powodu. A41 i zmiany samych zdjęć nadal odłożone.
