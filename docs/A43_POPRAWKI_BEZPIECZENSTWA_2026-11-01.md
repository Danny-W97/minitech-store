# A43 — poprawki po weryfikacji kodu, pakiet 123

Stan: **01.11.2026 · Audit_0.1 · A43.40**.
Rewizja macierzy: `a43.40-2026-11-01.1`.
Decyzje użytkownika i reguła biznesowa `D02-P02-2026-10-30.1` pozostają bez zmian.

Poprawki odnoszą się do [ustaleń R01–R08](A43_WERYFIKACJA_KODU_I_PRZEKAZANIA_2026-10-31.md).
Usuwają możliwość wykonywania niekompletnego protokołu oraz fałszywe deklaracje
gotowości. **Nie uruchamiają retencji. A43.40 i A43.41 pozostają otwarte.**

## Zmiany

| Ustalenie | Poprawka i obecna granica |
|---|---|
| R01 | P09 sprawdza pozytywny dowód dokładnego zakresu i wersji, jego identyfikator oraz okres ważności. Brak, błąd, inny zakres, wygasły lub przyszły dowód blokuje. Domyślnie dostawca dowodów nie jest skonfigurowany. Konstruktor z boolean nie wyłącza ochrony; zwolnienie blokady nie jest zgodą na czyszczenie. |
| R02 | Limit rozmiaru logów sprawdza blokadę pliku **i źródła**, tak samo jak limit wieku. Ocena odbywa się pod wspólną lokalną synchronizacją ze zmianą blokad. Niekompletny wykonawca plikowy został wyłączony: `dryRun=false` odmawia operacji. |
| R03 | Kwalifikacja sprawy sprawdza aktualne blokady ID i podmiotu oraz P09. Wynik `RetentionEligibility` rozdziela wiek dowodów i minimum. Nie ustawia znaczników faktycznego usunięcia; dotychczasowy odczyt nie udaje kasowania plików. |
| R04 | Dzierżawy siedmiu źródeł są zapisane w PostgreSQL, nie w mapie JVM. Triggery blokują zwykłe zapisy biznesowe, także do samych treści; inna instancja odczytuje ten sam token i odrzuca konflikt. Ponowne PREPARE nie przedłuża dzierżawy, wygasłego tokenu nie można wznowić. Inventory ma chroniony endpoint i bierze udział w rundzie. To ochrona obserwacji źródeł, **nie kompletny protokół mutacji całego łańcucha**. |
| R05 | Niekompletne ścieżki wykonawcze Order, Returns, Delivery i usuwania minimum zastąpiono odmową `RETENTION_EXECUTION_NOT_CONFIGURED`. Usunięto z nich SQL wykonujący mutacje. Nie otwiera ich zegar klienta, ręcznie wstawione CONFIRMED ani parametr fixture boolean. Wymagany jest nowy, kompletny protokół zgody i wykonania. |
| R06 | Migracje Order V36 i Returns V10 chronią nieodwracalność redakcji niezależnie od `commerce_privacy_locks`: cofnięcie flagi i przywrócenie treści dzieci jest odrzucane. Zachowano ograniczenia kontaktu i dat dostawy. Zwykłe zakupy bez redakcji nadal można aktualizować. |
| R07 | Niekompletna implementacja nie jest przedstawiana jako działające usuwanie całego minimum. Usunięto możliwość destrukcyjnego wykonania przez boolean i fałszywych potwierdzeń. Pełne potwierdzenia ośmiu źródeł, przejścia faz i workery **pozostają do implementacji**. |
| R08 | QA opisuje wyłącznie sprawdzane parametry i obecność źródeł. Nie zwraca już stałych potwierdzeń fail-closed, integracji magazynów, kasowania załączników ani zatrzymania workera. Stop w lokalnym modelu jest żądaniem: `executionTerminated=false`, `isWorkerStopped=false`, osobno `isStopRequested`. Trwałe wykonanie i potwierdzenie stopu oraz integracje magazynów pozostają otwarte. |

Przy okazji regresji przywrócono unieważnianie obserwacji po zwykłych zmianach
treści Returns i Delivery. Wcześniejsze wyjątki dla redakcji pomijały również
zmiany, które nie miały takiego upoważnienia.

## Zakres schematu i wdrożenia

Przygotowano **9 nowych migracji**, bez edycji historycznych migracji:

- Siedem migracji dzierżaw źródłowych: Inventory V14, Returns V9, Payment V23,
  Promotion V12, Delivery V12, Billing V18 i Notification V20.
- Dwie migracje ochrony redakcji: Order V36 i Returns V10.

Dwie nowe kolumny w każdym z siedmiu rejestrów źródłowych to
`retention_fence_token` i `retention_fence_until`. Nie wprowadzają TTL usuwania
historii, deduplikacji lub rejestru usunięć. Przy restore kwarantanna wycofuje
odtworzone tokeny, zachowując źródłowe zegary i historię.

Inwentarz repozytorium po zmianie: **106 tabel / 1031 kolumn / 197 migracji**.
Klasyfikacja, macierz i skróty źródeł zostały zaktualizowane. Nie zastosowano
migracji na rzeczywistych dev/test/prod i nie zmieniano sekretów ani kluczy.

## Odbiór

Bieżące logi, wyniki kontroli i raport: `tmp/a43-fixes/`.
Wyniki końcowe należy czytać z `report.json`; starszy `java.log` zawiera też
usterkę wykrytą i poprawioną podczas regresji, a nie wynik końcowy.
**Odbiór poprawek: 333 unikalne testy Java PASS** (216 jednostkowych,
117 PostgreSQL), w tym pełne 214 platform-security. **80 unikalnych testów
Python PASS w końcowym pełnym przebiegu.** Nie doliczano ponownie
powtórzonych testów workerów i modułu bezpieczeństwa.

Pełne 80 testów Python zakończyło się sukcesem na kopii identycznych bajtowo
źródeł w Linux /tmp, wraz z rzeczywistym plikiem CI. Raport zawiera manifest
1451 skopiowanych plików i ich skrótów. Kopia ogranicza koszt operacji
plikowych WSL na dysku Windows; nie zmienia semantyki kontroli ani danych.
Końcowy przebieg obejmuje również poprawkę synchronizacji redakcji z zapisem
potomnym: zapis starej treści nie może przekroczyć zatwierdzenia redakcji.

Kontrole inwentarza, klasyfikacji, polityki/kandydata i parametrów operacyjnych:
PASS. Pełna gotowość: **NOT_READY, kod 2**. Kontrola operacyjna potwierdza
konfigurację, a nie integrację magazynów.

Pełny drill 14 baz i Redis: **PASS**, także z aktywnymi tokenami źródeł w kopii:
`tmp/privacy-recovery-3a4f5b99/report.json`. Kwarantanna wycofała tokeny;
`releaseAllowed=false`. Nie dopuszczono rzeczywistego środowiska do działania.

Testy obejmują decyzje P09, blokadę źródła przy przekroczeniu rozmiaru,
blokadę sprawy po zamknięciu, konflikt instancji, zapis przy aktywnej dzierżawie,
wygaśnięcie, uprawnienia endpointu Inventory, odmowę mutacji, nieodwracalność
redakcji oraz zgodność z dotychczasowym usuwaniem konta. Drill odtwarzania
sprawdza także odtworzone aktywne tokeny źródeł.

**Granice odbioru:** dzierżawy obserwacji i odmowa niekompletnej mutacji nie
zastępują zatwierdzonej polityki lub kompletnego protokołu redakcji. PASS
konfiguracji nie dowodzi wykonania czyszczenia w Mailpit, Grafanie lub plikach.

## Co dalej

1. Dokończyć zaufane globalne źródła blokad/spraw/retry i aktualny niezależny
   rejestr. Brak dowodu pozostaje UNKNOWN, nie dodatnią zgodą.
2. Zaprojektować i wdrożyć pełne upoważnienie oraz wykonanie D02 dla ośmiu
   źródeł, utrzymujące ochronę przez mutację i odzyskanie po awarii. Dopiero
   takie API może zastąpić zablokowane starsze entrypointy.
3. Dokończyć trwałe procedury spraw/stopu i rzeczywiste integracje magazynów;
   natywne limity Compose nie konsultują lokalnego strażnika Java.
4. Uzupełnić pozostawione decyzje D01, D09/SAFE i P09 oraz dowody dev/test,
   następnie przeprowadzić pełny A43.41. Aktywacja pozostaje odrębną decyzją.

A05, A41 oraz zdjęcia nadal odłożone. Nie otwarto tych zakresów automatycznie.
