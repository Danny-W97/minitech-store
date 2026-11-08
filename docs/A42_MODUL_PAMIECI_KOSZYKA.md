# A42.4 — moduł pamięci koszyka (26.10.2026)

`frontend/cart-storage.js` jest niezależnym od DOM modułem ES. Współdzielą go
sklep (`app.js`, używany przez stronę główną i katalog) oraz szczegóły produktu
(`product-detail.js`). Klucze, migracja i obsługa JSON/localStorage mają jedno
źródło. Moduł nie wykonuje zapytań HTTP, nie zarządza sesją ani nie renderuje UI.

## Kontrakt danych

| Klucz | Zastosowanie |
| --- | --- |
| `minitech-cart:guest` | Koszyk gościa |
| `minitech-cart:customer:<id>` | Osobny koszyk danego klienta |
| `minitech-cart:schema-version` | Dotychczasowa wersja `2` |
| `minitech-cart` | Stary klucz bez właściciela, usuwany przy inicjalizacji |

Zachowano format tablicy JSON, klucze, wersję schematu i sposób wyboru zakresu.
Nie ma fallbacku z pustego koszyka klienta do danych gościa lub starego klucza.
Migracja usuwa stary koszyk gościa, gdy wersja jest inna niż `2`; nie usuwa
koszyków klientów. Przy wersji `2` zachowuje aktualny koszyk gościa.

Szczegóły produktu usuwają teraz również stary klucz `minitech-cart` przy
wersji `2`, zgodnie z dotychczasowym zachowaniem strony głównej/katalogu.
Reguły przełączania zakresu przy logowaniu i wylogowaniu pozostają w stronach.
Klucz klienta w przeglądarce nie jest dowodem uprawnienia do danych na serwerze.

API fabryki `createCartStorage()` obejmuje `key(customer)`, `initialize()`,
`clearGuest()`, `load(key)` i `save(key, cart)`. Dostęp do storage jest leniwy
i może być wstrzyknięty w teście. Błędny JSON, wartość inna niż tablica lub błąd
odczytu dają pustą tablicę. Błąd zapisu/usuwania nie przerywa obsługi strony;
zapisane dane nie są gwarantowane, gdy przeglądarka blokuje pamięć. Bieżący stan
sklepu pozostaje w pamięci strony.

Test blokady storage ujawnił też niezabezpieczony zapis preferencji porównania,
który przerywał render katalogu. Zapis ma teraz ochronę przed wyjątkiem; wybór
porównania może pozostać w pamięci strony bez utrwalania.

## Ładowanie i wdrożenie

`index.html`, `catalog.html` i `product.html` ładują odpowiedni skrypt wejściowy
przez `type="module"`. Import modułu koszyka jest jawny; wewnętrzne funkcje stron
nie stają się automatycznie funkcjami globalnymi. Zachowany komponent dialogu
potwierdzenia ma nazwany eksport do testu komponentu zamiast wywołania przez
`window`. Interfejsy `MiniTechApi`, sesji, języka i dialogów pozostały zgodne.

Nie dodano bundlera ani zależności npm. Pliki nadal są serwowane z `frontend/`.
Należy używać dotychczasowego serwera HTTP(S), np. `frontend-up` w CC.
Sprawdzono, że rzeczywisty serwer Python używany przez CC zwraca poprawny MIME
JavaScript dla nowego modułu i obu skryptów wejściowych.

Przy przyszłym wdrożeniu należy dostarczyć razem nowy plik, oba skrypty wejściowe
i trzy pliki HTML. Zmieniono wersje URL kodu, bez zmian wersji/cache/ładowania zdjęć.
Nie wykonywano wdrożenia na dev/prod ani budowania nowego obrazu.

## Weryfikacja

- **35 testów JavaScript — PASS**, w tym dziewięć nowych testów bez DOM,
  importujących rzeczywisty moduł ES: zakresy klientów, migracja, brak fallbacku,
  odczyt/zapis, błędny JSON, niedostępny storage i przekroczona quota.
- **76 testów Chromium — PASS**: pełna dotychczasowa regresja z trzema nowymi
  scenariuszami koszyka.
- **105 testów Firefox/WebKit/mobilnego Chromium — PASS**: koszyk, sesja,
  dostępność, czat i smoke. Nowe scenariusze koszyka wykonano w czterech profilach
  (12 przypadków): przejście katalog/produkt/reload, rozdzielenie gościa i klienta
  oraz praca przy zablokowanym localStorage.
- Rzeczywisty serwer statyczny: trzy zasoby modułów, poprawne odpowiedzi i MIME.
  Kontrola składni JS oraz `git diff --check` — PASS.
- CI uruchamia nowe testy JS oraz scenariusze koszyka w czterech profilach.
  Wykorzystano izolowane dane przeglądarki i deterministyczne fixture API.

Raport: `tmp/audit-a42-4-de506c35/report.json` z hashami sześciu plików źródłowych.
Logi: `tmp/a42-4-node.log`, `tmp/a42-4-browser-final.log`,
`tmp/a42-4-cross-browser.log`, `tmp/a42-4-static-server.log`.
Raporty Playwright: `gitIgnoreOwn/runtime/playwright-a42-4-final` i
`gitIgnoreOwn/runtime/playwright-a42-4-browsers`.

Backendu, migracji ani płatności nie zmieniano; nie powtarzano testów Maven.
Pełne A42 pozostaje częściowo otwarte: dalszy podział logiki sklepu i CC oraz
pozostałe duplikacje wymagają kolejnych pakietów. A05, całe A41 i zmiany samych
zdjęć pozostają odłożone.
