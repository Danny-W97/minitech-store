# A42.6 — moduł tłumaczeń Control Center (26.10.2026)

`scripts/control-center/web/panel-i18n.js` wydziela z głównego skryptu panelu
słowniki PL/EN, nakładanie etykiet na DOM i tłumaczenie historycznych tytułów
zadań. Główny `app.js` ma teraz 3265 linii, około tysiąca mniej. Korzyścią jest
osobna odpowiedzialność modułu i możliwość kontroli tłumaczeń bez uruchamiania CC.
Nie jest to deklaracja szybszego ładowania strony ani pomiar redukcji RAM.

## Podział odpowiedzialności

- `normalizePanelLanguage(language)` wybiera `en`, a dla innych wartości `pl`.
- `getPanelMessages(language)` zwraca słownik wybranego języka. Oba języki
  zawierają teraz ten sam zestaw 235 niepustych kluczy tekstowych.
- `renderPanelLanguage({language, profile, document, currentView})` aktualizuje
  etykiety, placeholdery, tytuły, teksty dostępności i nagłówki tabel. Dokument
  i bieżący widok są przekazywane jawnie. Sam import nie wymaga DOM.
- `getPanelJobTitle(job, language, actions)` preferuje aktualne metadane akcji;
  następnie używa istniejących map historycznych tytułów. Nieznane nazwy
  operatora pozostają bez zmian. Funkcja nie korzysta ze stanu aplikacji.

Moduł nie wykonuje HTTP, nie zapisuje preferencji i nie zarządza sesją ani
pollingiem. `app.js` nadal zapisuje język przez chronione `/api/language`,
aktualizuje stan i uruchamia renderowanie danych klientów, zadań, diagnostyki
oraz wskaźnika AI. Odpowiedź bootstrapu nadal wybiera początkowy język panelu.

## Poprawione braki

Polski słownik nie zawierał pięciu używanych etykiet historii/logów zadań:
odświeżania, pustej historii, wyboru zadania, oczekiwania i braku treści logu.
Uzupełniono je. Nazwy trzech polskich kluczy wprowadzenia „O aplikacji”
ujednolicono z kluczami używanymi przez renderer (`introEyebrow`, `introTitle`,
`introDesc`), dzięki czemu tekst nie znika po nałożeniu języka.
Usunięto cztery nieużywane angielskie etykiety linków z sekcji `about`;
etykiety linków używane przez topbar pozostały w swoim słowniku.

Kontrola zgodności kluczy wykrywa przyszłe braki między PL i EN. Ten pakiet nie
stanowi pełnej korekty wszystkich opisów ani tłumaczenia tekstów wpisanych
bezpośrednio w pozostałych funkcjach i HTML. Dalsze porządkowanie pozostaje otwarte.

## Weryfikacja i dostarczenie plików

- **55 testów JavaScript — PASS**, w tym trzy nowe: zgodność kluczy i poprawny
  fallback języka bez DOM, pierwszeństwo metadanych akcji i historyczne nazwy zadań.
- **22 testy CC w Chromium — PASS**, w tym dwa nowe scenariusze językowe.
  Sprawdzono PL → EN → PL w 12 widokach, nagłówki klientów, sortowanie,
  teksty „O aplikacji” i pustej historii, a następnie EN po reloadzie. Zapis
  języka ma nadal ten sam nagłówek sesji. Błąd zapisu nie zmienia języka ani
  szkicu terminala i pozwala dalej korzystać z istniejącej sesji.
- Dotychczasowa regresja obejmuje pobieranie PDF/ZIP, wygaśnięcie sesji,
  Host/Origin, ustawienia AI i bezpieczne ponowienia operacji biznesowych.
  Brak błędów JavaScript przy ładowaniu i przełączaniu widoków.
- Składnia obu modułów i `git diff --check` — PASS. CI już uruchamia nowe testy
  przez istniejące zestawy JS i CC. Serwer, backend oraz Maven bez zmian;
  ich testów nie powtarzano dla tego pakietu przeglądarkowego.

Przy przyszłym wdrożeniu należy dostarczyć razem nowy `panel-i18n.js`, zmieniony
`app.js` i `index.html` CC. Import ma wersję URL, a adres skryptu wejściowego
został zaktualizowany. Testy użyły rzeczywistej obsługi statycznej serwera CC
w izolowanym fixture; `/api/language` było zastąpione testową odpowiedzią,
bez zapisu preferencji użytkownika. Nie dodano zależności ani bundlera.

Raport: `tmp/audit-a42-6-c812f942/report.json`. Logi:
`tmp/a42-6-node-final.log`, `tmp/a42-6-cc-browser-final.log`.
Artefakty Playwright: `gitIgnoreOwn/runtime/playwright/cc-results`.

Pełne A42 pozostaje częściowo otwarte dla dalszego podziału dużych plików i
pozostałych duplikacji. A05, całe A41 i zmiany samych zdjęć pozostają odłożone.
Nie wykonywano wdrożenia na dev/prod.
