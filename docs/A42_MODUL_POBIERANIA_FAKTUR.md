# A42.9 — moduł pobierania faktur (26.10.2026)

`frontend/invoice-downloads.js` obsługuje pobieranie PDF w historii zamówień
sklepu oraz na stronie zamówienia. Transport binarny przechodzi teraz przez
`store-client.js`, który udostępnia `blob()` obok `request()`. Konfiguracja API
nie wykonuje już pobierania plików ani operacji DOM.

## Wspólny transport i dostęp

Odczyty JSON i Blob współdzielą kontrolę zaufanego origin/prefiksu API,
nagłówki, błędy, zakaz przekierowań oraz oczekujące odświeżenie sesji.
Udany PDF nie jest dekodowany jako tekst/JSON. Odpowiedzi błędów zachowują
komunikaty i metadane; klient może jednokrotnie ponowić odczyt po 401 i refresh,
zachowując format binarny oraz sygnał anulowania. Adapter ponowienia JSON
sklepu nie otrzymuje odpowiedzi PDF. Nie dodano retry błędów sieci ani 5xx.

Klient z kontem korzysta z aktualnego access tokenu. Gość korzysta z istniejącego
`X-Order-Access-Token`, odczytanego przez `MiniTechApi.orderAccess(id)`;
dla PDF gościa wymuszono anonimowy odczyt bez Authorization i cookies.
Token nie trafia do URL ani nazwy pliku. Zapis i zakres kluczy sessionStorage
pozostają zgodne. Autoryzacja dokumentów na serwerze nie zmieniła się.

## Cykl życia pobierania

Fabryka `createInvoiceDownloads()` przyjmuje transport Blob, odczyt właściciela
i capability oraz dokument i funkcje URL/zegara. Udostępnia `download(id)`
i `cancelAll()`. Import nie wymaga DOM; funkcje można testować osobno.

- Równoległe kliknięcia tej samej faktury dla tego samego konta/capability
  współdzielą oczekujące żądanie i uruchamiają jedno pobranie. Różne dokumenty
  pozostają niezależne. Po zakończeniu lub błędzie wpis jest usuwany; nie ma
  długotrwałego cache plików, a użytkownik może ponowić pobranie.
- Identyfikator jest dodatnią liczbą całkowitą lub dokładnym ciągiem cyfr bez
  zer wiodących. Nieprecyzyjne wartości number są odrzucane, a duże ID przekazane
  jako tekst pozostają dokładne. Nazwa ma format `invoice-<id>.pdf`.
- Wylogowanie, lokalne wygaśnięcie i czyszczenie sesji anulują oczekujące PDF.
  Zmiana konta w sklepie również wywołuje anulowanie. Po odbiorze Blob moduł
  dodatkowo sprawdza generację, właściciela i capability, więc spóźniona
  odpowiedź nie uruchamia pobrania nawet przy zignorowaniu sygnału przez transport.
- Tymczasowy URL powstaje dopiero dla zaakceptowanego Blob. Jest zwalniany
  po sekundzie, również przy błędzie wywołania kliknięcia; przy błędzie zegara
  zwalnianie następuje od razu. Anulowana starsza operacja nie usuwa nowszego
  pobrania tej samej faktury z rejestru oczekujących żądań.

Strona zamówienia pokazuje komunikat i ukrywa kartę przy odmowie odnowienia
aktywnej sesji, zamiast bez wyjaśnienia kończyć pobieranie. Przy błędzie sieci
lub 5xx odnowienia pokazuje komunikat o problemie i potrzebie ponowienia.
Anulowanie oczekującego żądania nie usuwa pliku pobranego wcześniej przez użytkownika.

## Weryfikacja

- **92 testy JavaScript — PASS**, w tym 15 nowych: pięć transportu Blob
  i dziesięć cyklu pobierania. Obejmują bajty, nagłówki, wspólny refresh JSON/Blob,
  błędy, anulowanie, łączenie kliknięć, zmianę tożsamości, ID i zwalnianie URL.
- **85 testów Chromium — PASS**, pełna regresja sklepu z pięcioma nowymi
  scenariuszami PDF. **69 testów Firefox/WebKit/mobilnego Chromium — PASS**:
  PDF, wspólny klient API, sesje/MFA, dostęp do zamówień i zgłoszenia zwrotu.
- Pięć nowych scenariuszy wykonano w czterech profilach (20 przypadków,
  zawartych w powyższych liczbach): refresh 401 i dokładne bajty pliku, capability
  gościa bez cookies, odmowa refresh z komunikatem, retry po 503 z łączeniem
  kliknięć oraz przerwanie pobierania po wylogowaniu.
- Kontrola składni pięciu modułów oraz klasycznego `api-config.js` i
  `git diff --check` — PASS. Konfiguracja CI obejmuje testy JS i scenariusze PDF
  w czterech profilach. CI nie uruchamiano zdalnie w tym pakiecie.

## Dostarczenie i status

Przy przyszłym wdrożeniu należy dostarczyć nowy moduł pobierania, zmieniony
`store-client.js`, skrypty sklepu/produktu/zamówienia, `api-config.js` oraz pięć
HTML (index, catalog, product, order-detail, delivery). Zaktualizowano wersje
URL kodu. Usunięty wewnętrzny helper `MiniTechApi.downloadInvoice` zastąpiły
importy modułu w obu miejscach użycia. Pozostałe funkcje `MiniTechApi` są zachowane.
Istniejący Dockerfile kopiuje cały frontend; nie dodano bundlera ani zależności.

Raport: `tmp/audit-a42-9-e76a4c31/report.json`. Logi: `tmp/a42-9-node.log`,
`tmp/a42-9-browser-final.log`, `tmp/a42-9-cross-browser-final.log`.
Artefakty: `gitIgnoreOwn/runtime/playwright-a42-9-final` i
`gitIgnoreOwn/runtime/playwright-a42-9-browsers-final`.

Testy miały własne sesje, porty i pliki PDF fixture. Backend, schematy baz i
konfiguracja kluczy bez zmian; nie powtarzano testów Maven, nie budowano obrazu
i nie wdrażano na dev/prod. Pełne A42 pozostaje otwarte dla transportu czatu,
dalszego podziału logiki stron i pozostałych duplikacji. A05, całe A41 oraz
zmiany samych zdjęć pozostają odłożone.
