# A43.7 — ograniczony odczyt logów i cache diagnoz CC

Data: 27.10.2026. Pakiet 64 audytu, branch `Audit_0.1`.

Wykonane i zweryfikowane lokalnie. Ten etap ogranicza odczyt i pamięć potrzebną
do podglądu logów. Limit rozmiaru pliku podczas zapisu pozostaje osobnym etapem.

Limit zapisu został następnie wdrożony w [A43.8](A43_LIMIT_ZAPISU_LOGOW_CC.md).
Opis i wyniki poniżej zachowują zakres odbioru A43.7.

## Zmiana zachowania

Wcześniej `Job.public()` wczytywało cały plik dla każdego zadania na liście,
chociaż odpowiedź listy nie zawierała treści wyjścia. Dopiero po odczycie
szczegóły ograniczały tekst do końcowych 120000 znaków. Duży log obciążał RAM
i I/O przy kolejnych odświeżeniach.

Teraz podsumowania queued/running/success nie otwierają logu. Failed pobiera
ograniczoną próbkę do diagnozy i przechowuje tylko jej wynik w cache.
Szczegóły nadal podają tekst wyjścia, ale odczytują najwyżej ustaloną liczbę
bajtów. Krótki log jest podawany w całości, a duży jako początek i koniec
z wyraźnym, lokalizowanym komunikatem o pominięciu środka.

Duży log może zawierać przyczynę błędu poza próbką. Diagnoza ma wówczas
`sampled=true`, a jej widoczny opis PL/EN informuje o ograniczonej próbce.
Nie deklaruje się analizy całego pliku.

## Konfiguracja i budżet

Jedna nowa niesekretna zmienna hostowego procesu CC:

| Zmienna | Domyślnie | Zakres |
| --- | --- | --- |
| `CC_JOB_LOG_READ_MAX_BYTES` | `120000` | 4096–1048576 bajtów |

Niepoprawna konfiguracja zatrzymuje start. Zmiana wymaga restartu procesu CC.
Nie dotyczy Compose/Kubernetes ani plików SOPS. Limit działa niezależnie od
trybu retencji off/observe/purge z A43.6 i nie wymaga jej aktywacji.

Przy pliku większym niż limit początek zajmuje mniejszą z wartości 16384 oraz
jednej czwartej limitu; pozostały budżet przeznaczony jest na koniec. Reader
wykonuje seek i ograniczone odczyty, bez czytania pominiętego środka. Budżet
obejmuje dane pobrane z pliku; dekodowanie UTF-8, JSON i krótki komunikat mają
stały dodatkowy narzut. Nieprawidłowe lub urwane znaki UTF-8 są zastępowane.

Na liście może być do 50 zadań. Pierwsza diagnoza każdego failed mieści się
w limicie; ponowne niezmienione podsumowania nie czytają treści logu.
Nie dodano globalnego cache tekstu ani pełnych logów w pamięci.

## Cache i współbieżność

Cache jest własnością konkretnego Job i znika razem z nim. Zawiera wynik
diagnozy oraz klucz: device/inode, rozmiar, mtime, ctime, limit odczytu
i wersję pliku reguł diagnostyki. Zwykłe dopisanie, skrócenie, zamiana pliku,
zmiana limitu lub metadanych bazy reguł powoduje ponowną analizę. Cache opiera
się na metadanych; nie hashuje całej treści pliku.

Zapisany jest również wynik None, aby nie ponawiać analizy pustego/niepasującego
wyniku. Równoległe odczyty tego samego zadania współdzielą jeden cache miss.
Zwracane diagnozy są kopiami, więc klient nie zmienia zagnieżdżonego cache.
Szczegóły nadal pobierają aktualną próbkę, ale mogą ponownie wykorzystać diagnozę.

Jeżeli metadane pliku zmienią się podczas pobierania próbki, odpowiedź ma
`outputReadChanged=true`, diagnoza jest oznaczona jako próbka, a wynik nie
wchodzi do cache. Następne odświeżenie ponawia odczyt. Nie blokuje się procesu
piszącego ani nie zmienia sposobu przekazania mu stdout.

## Bezpieczeństwo i API

Nowy moduł `cc_job_output.py` otwiera tylko zwykłe pliki z jednym dowiązaniem.
W POSIX/WSL korzysta z bezpiecznego przejścia katalogów A43.6 i O_NOFOLLOW;
w natywnym Windows sprawdza reparse points oraz tożsamość otwartego pliku.
FIFO i linki są odrzucane bez odczytu treści celu. Dla zadań ze znacznikiem
porównuje również inode z plikiem rzeczywiście utworzonym przez runner.
Zarezerwowany, ale jeszcze nieutworzony log nie jest odczytywany.

Nie dodano nowego endpointu. Dotychczasowe GET `/api/jobs` i `/api/jobs/<id>`
zachowują kontrolę sesji, Host i Origin. Do odpowiedzi dochodzą:

| Pole | Znaczenie |
| --- | --- |
| `outputReadLimitBytes` | Skonfigurowany budżet |
| `outputReadLimited` | Czy pominięto środek; null, gdy podsumowanie nie czytało pliku |
| `outputReadChanged` | Czy metadane zmieniły się podczas odczytu; null bez odczytu |
| `outputReadError` | null, missing albo unavailable; bez ścieżki/tekstu wyjątku |
| `diagnosisSampled` | Czy diagnoza jest oparta na niepełnej/zmieniającej się próbce; null bez diagnozowania |

Obiekt diagnozy ma także `sampled`. Istniejący widok używa dotychczasowych
pól tekstowych: komunikat pominięcia jest w output, a informacja o próbce
w cause PL/EN. Kod JS, układ UI i polecenia zadań nie zmieniły się.

## Odbiór

**198 testów Python — PASS, bez pominiętych**, w tym 19 nowych: 17 modułu
odczytu/cache/Job oraz dwa HTTP listy i szczegółów. Wynik obejmuje również
27 przypadków A43.6, testy PTY i 12 istniejących testów Kubernetes.

Test rzadkiego pliku o logicznym rozmiarze 1 GiB potwierdził odczyt najwyżej
4096 bajtów i zachowanie obu końców. Pozostałe przypadki sprawdzają mały
UTF-8/ANSI, wszystkie klucze unieważnienia cache, wynik None, sześć równoległych
żądań, brak surowej treści w cache, kopie wyniku, zmianę w trakcie odczytu,
błędy bez ścieżek, linki/FIFO i tożsamość własnego logu.

Rzeczywisty Job i HTTP potwierdziły brak otwierania logów sukcesów na liście,
ograniczone szczegóły, cache diagnoz i odczyt aktywnego pliku pod blokadą
pisarza A43.6. Duże testy i specjalne typy plików korzystają wyłącznie
z własnych katalogów fixture. Nie czytano prawdziwych logów użytkownika.

Bramka struktury: **13 modułów, 54 cienkie fasady, jedna implementacja HTTP
— PASS**; testy mutantów zachowane. Bez nowych zależności, zmian Java i JS;
nie powtarzano Maven ani przeglądarek. Odbiór wykonano w WSL.

Raport: `tmp/audit-a43-7/report.json`. Regresja: `tmp/a43-7-python-full.log`.
Nie restartowano działającego CC i nie wdrażano na dev/prod.

Całe A43 nadal otwarte: limit zapisu pojedynczego logu z zachowaniem stdout
procesów potomnych po zakończeniu zadania/reload CC, wybór polityk/aktywacje
audytu, payloadu faktur i retencji CC, dalsza retencja zdarzeń i procedury
eksportu/usuwania/anonimizacji obejmujące usługi oraz kopie. A05, całe A41
i zmiany samych zdjęć pozostają odłożone.
