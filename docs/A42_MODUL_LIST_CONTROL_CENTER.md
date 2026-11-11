# A42.7 — moduł list i stronicowania Control Center (26.10.2026)

`scripts/control-center/web/panel-lists.js` wydziela zarządzanie kursorami,
filtrami, zaplanowanymi odczytami i przyciskami stronicowania z `app.js` CC.
Wspólny mechanizm obsługuje zamówienia, faktury, magazyn, klientów, rozmowy,
dokumenty do wysyłki, ich listę korekt oraz osobną historię korekt każdej faktury.
Renderowanie danych i operacje zapisu pozostają w dotychczasowych widokach.

## API i granice

Fabryka `createPanelLists()` przyjmuje funkcję odczytu, dokument, bieżący język,
obsługę błędu oraz opcjonalne funkcje zegara dla testów. Import i same odczyty
nie wymagają DOM. Publiczne API obejmuje:

- `fetch(kind, url, filters)` — odczyt strony, limit 50, filtry URL i metadane.
- `render(kind, anchor, reload)` — przyciski początku, poprzedniej i następnej
  strony, umieszczane jak dotychczas przy danej tabeli lub historii korekt.
- `filter(kind, reload)` — unieważnienie starego wyniku, powrót do początku
  i pojedynczy odczyt po 300 ms od ostatniej zmiany filtra.
- `refresh()` — odświeżenie etykiet i stanu przycisków, również po zmianie PL/EN.
- `dispose()` — anulowanie odczytów i timerów, blokada kolejnych odczytów.
  CC wywołuje je przy wygaśnięciu sesji; nową instancję tworzy reload strony.

Każda lista ma prywatny stan. Kursor pozostaje tekstem, także dla wartości ponad
bezpieczny zakres liczb JavaScript. Korekty dokumentów i poszczególnych faktur
nie współdzielą kursora. Transport nadal przechodzi przez `panel-client.js`:
ten sam origin i `/api/`, nagłówek sesji, brak cookies i zakaz przekierowań.

## Poprawione zachowanie

Nowy odczyt tej samej listy anuluje poprzedni przez `AbortController`. Zmiana
filtra robi to od razu, przed upływem opóźnienia wyszukiwania. Spóźniona odpowiedź
lub błąd są dodatkowo odrzucane przez numer generacji, również gdy funkcja
odczytu ignoruje sygnał anulowania. Niezależne listy nadal mogą pobierać równolegle.
Anulowanie po stronie przeglądarki nie gwarantuje zatrzymania pracy już rozpoczętej
na serwerze. Pakiet nie zmienia ani nie anuluje poleceń zapisu.

Przyciski stronicowania są wyłączone na czas wyszukiwania i pobierania; kontener
otrzymuje `aria-busy`. Po błędzie następnej strony pozostają dotychczasowe dane
i kursor wyświetlanej strony. Zmiana filtra usuwa natomiast migawkę starego
kursora: błąd nowego wyszukiwania nie może przywrócić pozycji z innego zapytania.
Ponowienie nowego filtra zaczyna od `0`. Wcześniej mogło zacząć od starej drugiej
lub dalszej strony i pominąć początek wyników.

Wygaśnięcie sesji usuwa zaplanowane wyszukiwania i anuluje trwające odczyty.
Spóźnione wyniki nie uzupełniają już zatrzymanego panelu. Zmiana języka odświeża
także etykiety istniejących kontrolek bez wymuszania nowego odczytu danych.

## Weryfikacja i dostarczenie plików

- **63 testy JavaScript — PASS**, w tym osiem nowych testów rzeczywistego modułu:
  filtry i limit, tekstowe kursory, anulowanie i spóźniony sukces/błąd, izolacja
  list i paneli, debounce, wygaśnięcie/dispose, błędy przeładowania oraz błędne payloady.
- **25 testów CC w Chromium — PASS**, w tym trzy nowe scenariusze: blokada
  starego kursora podczas nowego wyszukiwania, ponowienie filtra po błędzie
  z kursorem `0` i anulowanie listy po wygaśnięciu sesji. Istniejący test wyścigu
  wyszukiwań potwierdza teraz także rzeczywiste anulowanie starszego żądania.
- Dotychczasowa regresja potwierdza tekstowe kursory, błąd następnej strony,
  niezależną historię korekt, szkice czatu, ponowienia zapisów, PDF/ZIP, terminal,
  Host/Origin i ustawienia AI. Test językowy sprawdza również etykiety stronicowania.
- Składnia obu modułów i `git diff --check` — PASS. CI uruchamia nowe przypadki
  w dotychczasowych zestawach JS i CC. Testy miały własny serwer fixture oraz
  kontrolowane odpowiedzi i opóźnienia; nie korzystały z danych użytkownika.

Przy przyszłym wdrożeniu należy dostarczyć razem `panel-lists.js`, zmieniony
`app.js` i `index.html` CC. Import modułu i skrypt wejściowy mają wersje URL.
Testy korzystały z rzeczywistej obsługi zasobów statycznych serwera CC.
Bez nowych zależności, zmian serwera, backendu, schematów baz lub testów Maven.
Nie wykonywano wdrożenia na dev/prod.

Raport: `tmp/audit-a42-7-7d908ca1/report.json`. Logi:
`tmp/a42-7-node-final.log`, `tmp/a42-7-cc-browser-final.log`.
Artefakty Playwright: `gitIgnoreOwn/runtime/playwright/cc-results`.

Pełne A42 pozostaje częściowo otwarte dla dalszego podziału dużych plików
i pozostałych duplikacji. A05, całe A41 oraz zmiany samych zdjęć pozostają odłożone.
