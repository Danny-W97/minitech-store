# A42.12 — renderer odpowiedzi Asystenta AI w CC (27.10.2026)

`scripts/control-center/web/assistant-markdown.js` wydziela formatowanie
odpowiedzi Asystenta z dużego `app.js`. Funkcja `renderAssistantMarkdown(value)`
zwraca HTML z kontrolowanego zestawu znaczników. Nie korzysta z DOM, HTTP,
storage, konfiguracji dostawców ani kluczy AI. Główny skrypt nadal obsługuje
historię, wysyłanie pytań, stan formularza i błędy API.

## Zakres i poprawki

Renderer obsługuje akapity, przejścia linii, nagłówki prezentowane jako h3,
pogrubienie/kursywę, kod inline, bloki kodu, cytaty, proste listy i tabele
z wierszem separatora. Jest to jawny podzbiór Markdown, bez deklaracji pełnej
zgodności z CommonMark lub GFM.

- Bloki kodu są rozpoznawane przed pozostałymi blokami, a kod inline chroni
  własną zawartość przed interpretacją wyróżnień. `**tekst**`, listy, nagłówki
  i pionowe kreski wewnątrz kodu pozostają dosłowne. Zachowano wcięcia i puste
  linie; obsługiwane są również niedomknięte bloki i dłuższe ogrodzenia kodu.
- Cytat jest rozpoznawany na surowym tekście, zanim zostanie zastosowane
  escapowanie. Stary renderer escapował `>` przed próbą rozpoznania cytatu,
  przez co ten element nie był formatowany.
- Listy grupują tylko sąsiadujące elementy. Akapity i dalsze listy nie są już
  obejmowane jednym zachłannym zastąpieniem. Bloki nie są opakowywane w akapity.
- Tabela wymaga pasującego wiersza separatora. Tylko ten wiersz jest pomijany;
  dane zawierające `---`, np. `release---candidate`, pozostają widoczne.
  Pionowa kreska w kodzie lub poprzedzona backslashem nie dzieli komórki.
- Znormalizowano CRLF. Parser ogranicza zagnieżdżenie formatowania inline;
  test długiego niepoprawnego wejścia nie powoduje przepełnienia stosu.

## Granice renderowania

Tekst źródłowy jest escapowany w każdym kontekście: akapicie, nagłówku,
cytacie, liście, tabeli i kodzie. Znaczniki są tworzone wyłącznie przez renderer.
Nazwa języka bloku kodu trafia do klasy CSS tylko po sprawdzeniu ograniczonego
formatu. HTML użytkownika/modelu, skrypty, iframe, SVG, linki i składnia mediów
pozostają tekstem. Nie powstają href/src ani żądania pobrania osadzonych zasobów.

Nie włączono renderowania zdjęć ani nie zmieniono plików, formatów lub ładowania
zdjęć aplikacji. Transport i bezpieczeństwo sesji CC pozostają bez zmian,
podobnie jak ustawienia dostawców, SOPS, klucze i `/api/ai/chat`.

## Weryfikacja i dostarczenie

- **130 testów JavaScript — PASS**, w tym 14 nowych testów rzeczywistego
  modułu: bloki, kod dosłowny, wyróżnienia, cytaty, listy, separatory i komórki
  tabel, CRLF, złośliwy HTML, inertne linki/media oraz długie niepoprawne wejście.
- **28 testów CC w Chromium — PASS**, pełna regresja panelu z trzema nowymi
  scenariuszami odpowiedzi Asystenta: formatowanie, złośliwa treść bez aktywnych
  elementów/żądań oraz bezpieczny błąd API i odzyskanie po jawnym ponowieniu.
  Sprawdzono zachowany nagłówek sesji i brak błędów ładowania modułu.
- **6 dodatkowych testów Firefox/WebKit — PASS**: te same trzy scenariusze
  w obu silnikach. Test sprawdza rzeczywisty `browserType().name()`.
  Nowe scenariusze wykonano łącznie dziewięć razy w trzech silnikach.
- Konfiguracja testów CC pozwala wybrać `CC_TEST_BROWSER` (domyślnie Chromium)
  i osobny `CC_TEST_OUTPUT`. Pozostałe testy Host/Origin nadal domyślnie działają
  w Chromium; nie deklaruje się pełnego odbioru CC w pozostałych silnikach.
- Składnia modułów i `git diff --check` — PASS. CI obejmuje nowe testy JS,
  pełny zestaw CC i wybrane scenariusze renderera w Firefox/WebKit.
  CI nie uruchamiano zdalnie. Odpowiedzi AI pochodziły z fixture; nie wysyłano
  pytań do zewnętrznych modeli ani nie korzystano z sekretów użytkownika.

Przy przyszłym wdrożeniu należy dostarczyć razem `assistant-markdown.js`,
zmieniony `app.js` i `index.html` CC. Import i skrypt wejściowy mają nowe wersje
URL. Dotychczasowy serwer statyczny obsługuje nowy moduł. Bez bundlera i zależności.

Raport: `tmp/audit-a42-12-fb380ae7/report.json`. Logi:
`tmp/a42-12-node.log`, `tmp/a42-12-chromium-final.log`,
`tmp/a42-12-firefox-final.log`, `tmp/a42-12-webkit-final.log`.
Artefakty: `gitIgnoreOwn/runtime/playwright-a42-12-<silnik>-final`.

Backend, schematy danych i frontend sklepu bez zmian. Nie powtarzano Maven,
nie budowano obrazu i nie wdrażano na dev/prod. Pełne A42 nadal wymaga dalszego
podziału dużych plików i pozostałych duplikacji. A05, całe A41 oraz zmiany
samych zdjęć pozostają odłożone.
