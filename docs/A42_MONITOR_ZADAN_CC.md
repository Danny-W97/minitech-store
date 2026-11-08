# A42.16 — monitor zadań Control Center

Data: 27.10.2026. Pakiet 52 audytu.

`scripts/control-center/web/job-monitor.js` przejmuje wybór śledzonego zadania,
cykl odczytów i decyzję o powiadomieniu. Moduł ma wstrzykiwany transport,
timer i callbacki widoku; nie korzysta z DOM, storage ani tokenu CC. Wysyła
wyłącznie odczyty przez adapter istniejącego klienta panelu. Utworzenie
zadania i formularze potwierdzeń pozostają w aplikacji.

## Zachowanie

- W danej chwili monitorowane jest jedno wybrane zadanie. Powtarzane kliknięcia
  współdzielą trwający odczyt lub istniejący timer. Nie tworzą dodatkowej pętli.
- Zmiana zadania usuwa poprzedni timer i anuluje fetch. Tożsamość obserwacji
  odrzuca również późny wynik/błąd transportu ignorującego anulowanie.
  Stare finally nie zmienia stanu oczekiwania nowego odczytu.
- Stany `queued` i `running` są odczytywane co sekundę, z odstępem po odpowiedzi,
  bez nakładania żądań. Wcześniej wybór zadania `queued` nie kontynuował śledzenia.
- Odpowiedź musi mieć ID wybranego zadania i obsługiwany status. Błąd odczytu
  zatrzymuje pętlę; kliknięcie wybranego zadania ponawia GET. Nie ma automatycznego
  ponowienia POST ani ponownego wykonania polecenia.
- Zakończenie obserwowanego aktywnego zadania powiadamia raz. Zadanie utworzone
  w panelu może zakończyć się przed pierwszym GET i nadal otrzyma powiadomienie.
  Samo otwarcie zakończonego zadania z historii nie zgłasza nowego zakończenia.
- Wygaśnięcie sesji anuluje odczyt, usuwa timer i trwale wyłącza monitor tej
  strony. Kliknięcie innego zadania nie przywraca pętli; wymagane jest przeładowanie.

Adapter UI czyści poprzedni log i diagnozę przy zmianie wyboru. Aktualizuje
`aria-busy`, tytuł/status szuflady oraz log jako tekst. Zachowuje podążanie za
końcem logu, gdy użytkownik był blisko dołu. Odświeżenie katalogu po zakończeniu
polecenia zmiany języka sprawdza, czy obserwacja nadal jest aktualna.

Monitor nie anuluje ani nie zatrzymuje zadania serwera po zmianie wyboru.
Jest monitorem jednego wybranego zadania, a nie nową usługą powiadomień
o wszystkich zadaniach w tle. Historia i ręczne odświeżanie pozostają dostępne.

## Weryfikacja

- **169 testów JS — PASS**, w tym 12 nowych testów rzeczywistego modułu:
  kolejka/praca/zakończenie, wspólny odczyt, zmiana wyboru, późne sukcesy/błędy,
  stary timer, historia, szybkie zakończenie i błąd zadania, odzyskanie odczytu,
  walidacja odpowiedzi, wygaśnięcie sesji, zatrzymanie i aktualność callbacków.
- **44 testy CC Chromium, 20 Firefox i 20 WebKit — PASS**. Pięć nowych
  scenariuszy monitora wykonano w trzech silnikach (15 przypadków). Odbiór
  obejmuje brak dodatkowego POST podczas śledzenia/ponowienia, pojedyncze
  powiadomienie, anulowanie starego GET oraz log zawierający nieaktywny HTML.
- Składnia modułów i `git diff --check` — PASS. CI obejmuje monitor w trzech
  silnikach. Zdalnego CI nie uruchamiano.

Raport: `tmp/audit-a42-16/report.json`. Logi: `tmp/a42-16-node.log`,
`tmp/a42-16-chromium.log`, `tmp/a42-16-firefox.log`, `tmp/a42-16-webkit.log`.
Artefakty przeglądarek: `gitIgnoreOwn/runtime/playwright-a42-16-<silnik>`.

Przy przyszłym wdrożeniu dostarczyć razem `job-monitor.js`, `app.js`
i `index.html` CC. Wersja URL wejścia jest zmieniona. Bez nowych zależności,
bundlera, zmian backendu, API, kluczy i zdjęć. Testy używały własnego serwera
fixture i kontrolowanych odpowiedzi, bez wykonywania rzeczywistych poleceń
operatora. Nie powtarzano Maven i testów sklepu; nie budowano obrazu ani nie
wdrażano na dev/prod. Pełne A42 pozostaje otwarte. A05, całe A41 i zmiany
dotyczące samych zdjęć pozostają odłożone.
