# A43.8 — ograniczony zapis stdout zadań CC

Data: 27.10.2026. Pakiet 65 audytu, branch `Audit_0.1`.

Wykonane i zweryfikowane lokalnie w WSL. Limit dotyczy nowych zadań obsługiwanych
w trybie bounded. Nie przerabia istniejących logów ani już uruchomionych pisarzy.

## Zapis i konfiguracja

| Zmienna | Domyślnie | Zakres |
| --- | --- | --- |
| `CC_JOB_LOG_WRITE_MODE` | `auto` | auto, bounded, direct |
| `CC_JOB_LOG_WRITE_MAX_BYTES` | `1048576` — 1 MiB | 65536–16777216 |

W auto CC wybiera bounded, gdy Linux/WSL obsługuje wymagane uchwyty katalogów,
flock, SOCK_SEQPACKET i pidfd; na pozostałych hostach używa direct. Jawne bounded
na nieobsługiwanym hoście zatrzymuje start. Direct zachowuje dotychczasowy zapis
bez limitu. Błędne wartości zatrzymują start; konfiguracja wymaga restartu CC.
Są to ustawienia hostowego CC, bez sekretów, Compose i zmian SOPS.

W bounded stdout/stderr polecenia trafiają do prywatnej rury. Osobny proces
odbiornika zapisuje dane do własnego, bezpiecznie utworzonego logu. Czyta do
8192 bajtów naraz i przed każdym zapisem sprawdza limit. Nie zbiera całego
wyjścia w RAM ani nie korzysta z communicate do przechwytywania stdout.
Komunikacja stdin, kod wyjścia i timeout pozostają po stronie polecenia.

Po przekroczeniu limitu odbiornik przepisuje ten sam plik: zachowuje nagłówek
do 4096 bajtów, komunikat pominięcia i ostatnią przechowaną końcówkę, następnie
dopisuje nowe dane. Pamięta najwyżej 65536 bajtów końcówki; przy małym limicie
odpowiednio mniej. Plik po rotacji może być znacznie mniejszy od limitu.
Nie jest to obietnica zachowania całego ostatniego 1 MiB; limit oznacza
maksymalny rozmiar zarządzanego pliku. Zbyt długi nagłówek również jest skracany.

Starsze dane są świadomie pomijane, a log ma wyraźny komunikat. Rozmiar nie
rośnie ponad limit przy wyjściu obsługiwanym wyłącznie przez odbiornik.
Zewnętrzne narzędzie samodzielnie dopisujące do nazwy pliku nie uczestniczy
w protokole. Limit nie obejmuje sumy wszystkich logów w katalogu.

## Potomkowie, reload i zasoby

Odbiornik jest odłączony od sesji/procesu CC i przyjęty przez systemowy reaper.
Podczas reload CC zamyka własne połączenia, a odbiornik dalej czyta stdout,
aż ostatni pisarz zamknie rurę. Dzięki temu potomek, który przeżywa zakończenie
zadania lub exec CC, może nadal pisać. Samo zakończenie rodzica nie certyfikuje
jeszcze zamknięcia logu.

Pidfd pozwala obserwować konkretny proces bez pomylenia go z późniejszym
użytkownikiem tego samego PID. Krótki bootstrap jest zbierany od razu;
odbiornik po exec CC nie pozostaje nieznanym dzieckiem nowego interpretera.
Nie stosuje się waitpid(-1), który mógłby przejąć status innego zadania.

Jest **osiem wspólnych slotów na katalog jobs**. Blokady slotów przechodzą
do odbiorników i obowiązują pomiędzy instancjami oraz reload CC. Obejmują
również zarezerwowane zadania i potomków nadal utrzymujących stdout.
Pełna pula daje HTTP 429 przed uruchomieniem polecenia i utworzeniem logu.
Odbiorniki nie są zabijane w celu zrobienia miejsca.

Odbiornik jest dodatkowym procesem Python i zużywa pamięć interpretera oraz
mały, ograniczony bufor. Procesy powstają dopiero dla zadań, a po EOF kończą się.
Osiem slotów ogranicza ich liczbę; długowieczni pisarze mogą je utrzymywać.
Skrypty startujące usługi już przekierowują ich wyjście do logów usług.
Przy pełnej puli trzeba zakończyć/odpowiednio przekierować pisarzy lub wybrać
direct, świadomie rezygnując z limitu zapisu.

## Retencja i awarie

Odbiornik przejmuje opis logu oraz znacznik/lease A43.6. Utrzymuje blokady
podczas całego zapisu. Po EOF i zakończeniu komunikacji kontrolera zapisuje
czas zamknięcia w dotychczasowym znaczniku wersji 1. Pamięć historii CC nadal
chroni zakończone zadania; po jej zwolnieniu log kwalifikuje się do retencji
dopiero według istniejących reguł wieku i wolnych blokad.

Znaczniki są potrzebne także przy wyłączonym skanowaniu retencji, jeśli
włączony jest bounded. `CC_JOB_LOG_RETENTION_MODE=off` wyłącza skan/usuwanie,
ale nie wyłącza ochrony właściciela potrzebnej do ograniczonego zapisu.
W direct/off bez ograniczonego odbiornika obowiązuje wcześniejsze zachowanie.

Odbiornik nie otrzymuje środowiska zadania ani skarbca: ma minimalne środowisko
UTF-8, uchwyty własnych plików i prywatne IPC. Polecenie zachowuje dotychczasowe
środowisko. IPC nie otwiera portu sieciowego i przesyła małe pakiety ze stanem.

Po błędzie zapisu, np. braku miejsca, odbiornik dalej drenuje stdout i odrzuca
dane zamiast blokować polecenie na pełnej rurze. API wskazuje błąd oraz
niepełność wyniku. Przy błędzie I/O licznik droppedBytes jest ostrożnym
oszacowaniem pominięć; podczas zwykłej rotacji zlicza pomijaną treść.
Niepoprawny lub niedopisany znacznik pozostaje chroniony przez retencję.

Nagłe zabicie/awaria odbiornika zamyka rurę i może dać pisarzowi EPIPE.
Stan pokazuje SinkExited; CC nie zabija odbiorników przy zwykłym shutdown/reload.
Przypadek awarii jest sprawdzony testem. Timeout polecenia nadal kończy jego
własną grupę procesów i dopisuje komunikat przez odbiornik.

Rotacja nie jest transakcją odczytu. Podgląd A43.7 może chwilowo zobaczyć
fragment przepisywanego pliku; wykryta zmiana nie jest utrwalana w cache,
a następne odświeżenie pobiera nową próbkę.

## Stan i UI

Chroniony GET `/api/jobs/retention` ma teraz `outputWriter`:
configuredMode, effectiveMode, limitBytes i slotCapacity. Jest to odczyt
konfiguracji, bez rezerwacji slotów, startowania procesów i skanowania plików.

Dla przechwytywanego zadania GET `/api/jobs` oraz `/api/jobs/<id>` dodają
`outputWrite`: truncated, droppedBytes, error, complete i limitBytes.
Są to asynchroniczne statystyki odbiornika; complete oznacza zamknięty zapis,
a nie wynik biznesowy polecenia. Kod wyjścia nadal pochodzi od polecenia.

Widok logu dostaje tekstowy komunikat o pominięciu starszych danych lub błędzie
zapisu. Diagnoza failed jest oznaczona jako próbka także wtedy, gdy cały
już skrócony plik mieści się w limicie odczytu A43.7. Nie dodano nowego widoku
ani zmian JavaScript; istniejące UI wyświetla komunikaty tekstowe.

## Odbiór

**219 testów Python — PASS, bez pominiętych**, w tym 21 nowych: 19 konfiguracji,
odbiornika/runnera/IPC/życia procesów i dwa HTTP. Wynik obejmuje także 12
istniejących testów Kubernetes. Dodatkowy końcowy odbiór 19 przypadków zapisu
potwierdził ochronę retencji podczas żywego pisarza i usunięcie po jego EOF.
Nie sumować ponownie tych przypadków do 219.

Sprawdzono wielokrotne rotacje i limit na każdym zapisie, zachowanie
nagłówka/końca, stdin/stderr/UTF-8/kodu wyjścia, timeout, nieudany spawn,
konkurencję slotów między managerami, nasycenie przez HTTP i kolejkowany shutdown.
Rzeczywisty proces kontrolera wykonał reload_server i exec nowego Pythona,
podczas gdy odłączony potomek nadal pisał. Nowa instancja respektowała zajęty
slot, końcowy log zawierał AFTER_RELOAD_FIXTURE i mieścił się w 65536 bajtach.

Testy obejmują również brak przejęcia środowiska z danymi uwierzytelniającymi
fixture, błąd dysku, wymuszoną awarię odbiornika, limit nagłówka i diagnozę
skróconej treści. Wszystkie procesy/pliki były własnymi fixture.

Bramka struktury: **15 modułów, 54 cienkie fasady, jedna implementacja HTTP
— PASS**. Bez nowych zależności, zmian Java/JS i ponawiania Maven/przeglądarek.
Raport: `tmp/audit-a43-8/report.json`. Pełna regresja:
`tmp/a43-8-python-full-final.log`; końcowy odbiór: `tmp/a43-8-writer-acceptance.log`.

Nie zmieniano prawdziwych logów, nie restartowano działającego CC i nie wdrażano
na dev/prod. Całe A43 pozostaje otwarte dla wyboru polityk/aktywacji audytu,
payloadu faktur i retencji CC, dalszej retencji zdarzeń oraz procedur
eksportu/usuwania/anonimizacji obejmujących usługi oraz kopie.
A05, całe A41 i zmiany samych zdjęć pozostają odłożone.
