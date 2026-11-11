# A43.6 — retencja zamkniętych logów zadań Control Center

Data: 27.10.2026. Pakiet 63 audytu, branch `Audit_0.1`.

Mechanizm wykonany i zweryfikowany lokalnie. Domyślnie działa obserwacja;
docelowy okres przechowywania i aktywacja `purge` pozostają do ustalenia.

Opis dotyczy odbioru A43.6. [A43.8](A43_LIMIT_ZAPISU_LOGOW_CC.md) rozszerza
cykl stdout o ograniczonego odbiornika, własne znaczniki także przy retencji
off i certyfikację zamknięcia dopiero po ostatnim pisarzu.

## Zakres

Moduł `cc_job_logs.py` obsługuje wyłącznie katalog
`gitIgnoreOwn/runtime/control-center-web/jobs`. Rozpoznaje pary
`<12 małych znaków hex>.log` i odpowiadający im `.state`. Nie ma rekurencyjnego
usuwania ani możliwości wskazania innego katalogu przez API lub zmienną env.

Nowy log otrzymuje mały znacznik wersji 1 z ID i czasem zamknięcia. Znacznik
nie zawiera polecenia, danych wejściowych ani treści logu. Czas zamknięcia jest
zapisywany po zamknięciu strumienia pliku przez runner. Nie jest to podpis
kryptograficzny; jest to format własnych plików procesu CC w jego katalogu.

Retencja wymaga jednocześnie starego czasu zamknięcia, starego mtime znacznika
i starego mtime logu. Wszystkie porównania są ścisłe; granica i przyszłe daty
są zachowane. Wymagane są zwykłe pliki z pojedynczym dowiązaniem.

Starsze logi bez znacznika, niezamknięte pliki po awarii, niepoprawne metadane,
nieznane wersje, linki, FIFO i podkatalogi są pomijane. Nie dokonuje się
wstecznego oznaczania starych plików na podstawie samej daty modyfikacji.
Logi usług, backupy, skarbce, logi aplikacji Java i pliki poza jobs nie są objęte.

## Ochrona zadań i historii

Rejestracja zadania rezerwuje znacznik i utrzymuje blokadę `flock`. Blokada
trwa także po zakończeniu zadania, dopóki zadanie jest przechowywane w historii
CC. Usunięcie zakończonego zadania z ograniczonej historii zwalnia jego lease.
Drugie CC korzystające z tego samego katalogu również respektuje tę blokadę.

Plik logu ma dodatkową blokadę związaną z otwartym opisem pliku. Proces potomny
dziedziczący stdout utrzymuje ją również po zakończeniu rodzica. Collector
przejmuje bez czekania blokadę znacznika oraz logu; zajęta para jest pomijana.
Ochrona nie wymaga odczytywania treści ani wyszukiwania procesów po PID.

Zadanie usunięte z historii podczas zamykania strumienia zachowuje lease do
zakończenia zapisu znacznika. Timeout nadal kończy własne procesy zadania.
Awaria zapisu/bezpiecznego otwarcia logu daje stan failed, a nie zawieszone
running. Plik podstawiony po rejestracji nie jest nadpisywany ani oznaczany
jako zamknięty wynik tego zadania.

Tworzenie i skanowanie używają uchwytów katalogów, `O_NOFOLLOW` i `O_EXCL`.
Każdy komponent ścieżki jest otwierany bez podążania za symlinkiem. Przed
usuwaniem są ponownie sprawdzane tożsamość katalogu i metadane plików.
Collector nie kontynuuje pracy przez stary uchwyt po zamianie katalogu.
Usuwanie dotyczy pojedynczych nazw względem zweryfikowanego uchwytu.

Ochrona obejmuje otwarte opisy plików dziedziczone z runnera. Zewnętrzne
narzędzie samodzielnie ponownie otwierające nazwę logu poza cyklem CC nie
uczestniczy w tym protokole; świeże mtime nadal odracza kwalifikację.

## Tryby i konfiguracja

| Zmienna | Domyślnie | Zakres |
| --- | --- | --- |
| `CC_JOB_LOG_RETENTION_MODE` | `observe` | `off`, `observe`, `purge` |
| `CC_JOB_LOG_RETENTION_DAYS` | `14` | 1–3650 |
| `CC_JOB_LOG_RETENTION_BATCH_SIZE` | `100` | 1–1000 par/znaczników na przebieg |
| `CC_JOB_LOG_RETENTION_SCAN_LIMIT` | `500` | 1–10000 wpisów katalogu na przebieg |
| `CC_JOB_LOG_RETENTION_INTERVAL_SECONDS` | `600` | 1–86400 sekund |

Konfiguracja jest walidowana przy uruchamianiu głównego procesu CC. Błąd
zatrzymuje start. Zmienne dotyczą hostowego procesu CC, nie aplikacji w
Compose/Kubernetes; nie są sekretami i nie wymagają zmian SOPS.

`observe` tworzy znaczniki nowych zadań i pokazuje kandydatów bez usuwania.
`purge` usuwa wyłącznie kwalifikujące się zamknięte pary poza historią i bez
pisarzy. `off` wyłącza skan oraz tworzenie nowych znaczników. Pliki powstałe
bez znacznika pozostają chronione także po późniejszym włączeniu modułu.

**14 dni to początkowy próg obserwacji.** Przed `purge` użytkownik ustala
docelowy okres diagnostyki. Ogólna kontynuacja kolejnego etapu nie aktywuje
usuwania. Po zmianie konfiguracji wymagany jest restart procesu CC.

Retencja z blokadami działa w POSIX/WSL. W natywnym Windows obserwacja nie
uruchamia skanu ani znaczników, a status ma `supported=false`. Żądanie trybu
purge na nieobsługiwanej platformie zatrzymuje start z błędem konfiguracji.

## Budżet i podgląd

Pierwszy przebieg rozpoczyna się przy starcie CC, kolejne po konfigurowalnym
odstępie od zakończenia poprzedniego. Oprócz limitów rekordów i wpisów katalogu
jest dwusekundowy budżet pętli, sprawdzany między operacjami plikowymi. Nie
jest to gwarancja przerwania zablokowanej operacji jądra/systemu plików.

Iterator katalogu jest kontynuowany między przebiegami i zamykany na końcu,
przy błędzie, shutdown i reload. Dzięki temu świeże lub nieznane pliki na
początku katalogu nie zagłodzą późniejszych kandydatów. Stary zamknięty znacznik
bez logu również może zostać usunięty, zużywając budżet jednej zmiany.

Licznik plików/bajtów rośnie po zatwierdzonym unlink logu. Jeżeli usunięcie
znacznika potem zawiedzie, licznik zachowuje wynik; następny przebieg może
dokończyć osierocony znacznik. Błąd zachowuje poprzednią udaną migawkę i jej
czas. API pokazuje klasę błędu, bez ścieżki, ID, polecenia i treści.

Nowy **`GET /api/jobs/retention`** wymaga dotychczasowego nagłówka sesji CC
oraz poprawnego Host/Origin. Zwraca tryb i limity, `supported`, ostatnią udaną
migawkę (`lastRun`), czasy sukcesu/awarii i liczniki procesu. Nie wywołuje
skanu, nie odczytuje logów i nie zmienia trybu. POST nie jest obsługiwany.
Nie dodano nowego widoku UI.

`lastRun` podaje liczbę przejrzanych wpisów, kandydatów/bajtów, usunięć,
znaczników i pominięć. `scanComplete` informuje, czy osiągnięto koniec
iteratora. Liczby dotyczą tego przebiegu, również gdy stanowi tylko fragment
katalogu; nie są deklaracją globalnego rozmiaru zaległości. Przed pierwszym
odbiorem migawka/czas mają null. Liczniki są lokalne dla procesu i zerują się
po restarcie. Dwa collectory nie zliczają ponownie tego samego unlink.

## Odbiór

**179 testów Python — PASS, bez pominiętych**, w tym 27 nowych: 26 konfiguracji,
plików, blokad i runnera oraz jeden HTTP sesji/Origin i braku mutacji endpointu.
Pełna regresja obejmuje również istniejące HTTP, PTY, sekrety fixture, transport,
operacje i 12 przypadków Kubernetes. Nie sumować ponownie tych 12 do wyniku 179.

Sprawdzono procesy CC niezależne od siebie, potomka dziedziczącego stdout,
historię i timeout, trzy granice czasu, błędne znaczniki, symlinki/hardlinki,
FIFO, zamianę katalogu/pliku, kolizję ID, kontynuację skanu i równoległe
collectory oraz częściową awarię unlink z odzyskaniem. Test potwierdza, że
collector odczytuje tylko ograniczony znacznik, a nie treść logu.

Testy korzystają wyłącznie z własnych katalogów fixture. FIFO i zamiana
katalogu z otwartą blokadą są wykonywane w lokalnym temp Linux: DrvFS nie
obsługuje pierwszej operacji i blokuje drugą. Pozostałe przypadki sprawdzają
również rzeczywisty katalog projektu zamontowany w WSL.

Bramka modułów: **12 modułów, 54 cienkie fasady i jedna implementacja HTTP —
PASS**; testy mutantów pozostały aktywne. Import nie uruchamia workera.
Nie dodano zależności, zmian JS/UI ani Java; nie ponawiano Maven i przeglądarek.

Raport: `tmp/audit-a43-6/report.json`. Pełna regresja:
`tmp/a43-6-python-full-final.log`. Nie uruchamiano retencji na katalogu logów
użytkownika, restartu działającego CC ani wdrożenia dev/prod.

Całe A43 pozostaje otwarte: polityki i aktywacje audytu/payloadu faktur/logów
CC, limit rozmiaru pojedynczego logu i koszt jego odczytu, dalsza retencja
zdarzeń oraz eksport/usuwanie/anonimizacja we wszystkich usługach i kopiach.
A05, całe A41 i zmiany samych zdjęć nadal odłożone.
