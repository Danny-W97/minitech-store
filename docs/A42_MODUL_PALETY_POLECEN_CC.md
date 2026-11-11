# A42.13 — moduł palety poleceń Control Center (27.10.2026)

`scripts/control-center/web/command-palette.js` wydziela z `app.js` katalog,
wyszukiwanie i dialog `Ctrl+K`. Część danych jest niezależna od DOM; kontroler
otrzymuje dokument, źródła bieżącego języka/katalogu oraz jawne callbacki.
Nie wykonuje HTTP i nie zarządza tokenem sesji ani poleceniami uruchamianymi
na serwerze. Aplikacja nadal obsługuje routing, formularze, potwierdzenia,
sekrety, raport i otwieranie zewnętrznych stron.

## Model i wyszukiwanie

- `buildPaletteItems()` tworzy 12 pozycji nawigacji z istniejących tłumaczeń,
  faktyczne operacje z bieżącego katalogu serwera oraz skróty dokumentacji/raportu.
  Usunięto drugi zestaw zakodowanych na stałe nazw operacji. Nie reklamuje się
  operacji nieobecnych w katalogu, których poprzedni skrót niczego nie otwierał.
- Tożsamość operacji wynika z ID. Różne ID pozostają dostępne nawet przy
  identycznych nazwach lub zawieraniu krótszej nazwy w dłuższej. Powtórzone ID
  są redukowane do pierwszej poprawnej pozycji. Swagger z katalogu zastępuje
  identyczny stały skrót URL.
- `filterPaletteItems()` wyszukuje wszystkie podane słowa w nazwie, grupie,
  ID, opisie i słowach dziedzinowych widoku. Normalizacja pomija wielkość liter
  i polskie znaki diakrytyczne. Kolejność źródłowa jest zachowana.
- Katalog i tekst do wyszukiwania powstają przy otwarciu/odświeżeniu.
  Strzałki zmieniają zaznaczenie istniejących węzłów; nie odtwarzają całego
  katalogu, listy HTML ani osobnych handlerów dla każdego wiersza. Odbiór
  potwierdza zachowanie tożsamości węzła przy nawigacji klawiaturą.
- Nazwy operacji pochodzą z aktualnej odpowiedzi serwera. Zmiana języka
  odświeża również paletę. Nawigacja używa istniejącego słownika CC.

## Dialog i granice operacji

`createCommandPalette()` udostępnia `open`, `close`, `refresh` i `dispose`.
Kontroler obsługuje Ctrl/Meta+K, wyszukiwanie, strzałki z zawijaniem, Enter,
Escape, kliknięcie i wybór wskaźnikiem. Puste wyniki nie mają aktywnego indeksu
i nie wykonują polecenia po Enter. Tab pozostaje w polu wyszukiwania.

Dialog i pole mają nazwy dostępności. Combobox wskazuje listbox i aktywną
opcję przez ARIA; zaznaczenie i stan otwarcia są synchronizowane. Zamknięcie
przywraca fokus do elementu otwierającego, a wybór innego widoku do widocznego
przycisku palety. Paleta nie otwiera się nad innym aktywnym dialogiem. Zdarzenie
zamknięcia z poprzedniego otwarcia nie resetuje dialogu otwartego ponownie.

Wybór operacji przekazuje wyłącznie ID do istniejącego `openAction`.
Otwiera formularz i zachowuje wymagane potwierdzenie operacji niebezpiecznej;
sam wybór nie wysyła `/api/jobs`. Callback raportu i adresów zewnętrznych
pozostaje w aplikacji. Adresy pozycji zewnętrznych muszą być absolutnym HTTP(S)
bez loginu/hasła; są otwierane z `noopener,noreferrer`.

Wiersze powstają przez DOM i `textContent`, bez interpolacji danych katalogu
do HTML. Tytuł wyglądający jak HTML pozostaje tekstem, także po tłumaczeniu.
Nie zmieniono API, autoryzacji serwera ani pól/zasad potwierdzania formularzy.

## Weryfikacja i dostarczenie

- **140 testów JavaScript — PASS**, w tym 10 nowych testów modelu: poprawne
  ID, podobne nazwy, duplikaty, katalog serwera, wyszukiwanie, język, błędne
  dane, adresy zewnętrzne, zawijanie indeksu i odzyskanie po pustym wyniku.
- **33 testy CC w Chromium — PASS**, pełna regresja z pięcioma nowymi
  scenariuszami palety. **16 testów Firefox/WebKit — PASS**: po pięć palety
  i trzy renderera Asystenta w każdym z obu silników.
- Pięć nowych scenariuszy wykonano łącznie 15 razy w trzech silnikach:
  klawiatura/fokus/puste wyniki i zachowanie DOM, przejście do widoku,
  rozróżnienie ID z potwierdzeniem operacji, nieaktywna treść i język oraz
  zamknięcie/ponowne otwarcie w tej samej turze zdarzeń.
- Lokalny pomiar axe dla otwartej palety nie wykazał poważnych/krytycznych
  naruszeń badanych reguł. Nie jest to deklaracja pełnej zgodności CC z WCAG.
- Składnia modułów i `git diff --check` — PASS. CI obejmuje nowe testy JS
  i scenariusze w trzech silnikach; zdalnego CI nie uruchamiano.

Przy przyszłym wdrożeniu należy dostarczyć razem `command-palette.js`, zmieniony
`app.js` i `index.html` CC. Wersje URL kodu są zaktualizowane; dotychczasowy
serwer statyczny obsługuje moduł. Bez bundlera, zależności i zmian CSS/zdjęć.

Raport: `tmp/audit-a42-13-6e439dc2/report.json`. Logi: `tmp/a42-13-node.log`,
`tmp/a42-13-chromium.log`, `tmp/a42-13-firefox.log`, `tmp/a42-13-webkit.log`.
Artefakty: `gitIgnoreOwn/runtime/playwright-a42-13-<silnik>`.

Testy używały własnego serwera fixture i testowych katalogów; nie uruchamiały
rzeczywistych zadań operatora. Backend i frontend sklepu bez zmian. Nie
powtarzano Maven, nie budowano obrazu i nie wdrażano na dev/prod. Pełne A42
pozostaje otwarte dla dalszego podziału kodu i duplikacji. A05, całe A41 oraz
zmiany samych zdjęć pozostają odłożone.
