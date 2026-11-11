# A42.8 — wspólny klient HTTP sklepu (26.10.2026)

`frontend/store-client.js` zastępuje trzy lokalne implementacje transportu JSON
w sklepie/katalogu (`app.js`), szczegółach produktu (`product-detail.js`) oraz
szczegółach zamówienia (`order-detail.js`). Nagłówki, dekodowanie odpowiedzi,
błędy, stronicowanie i decyzja o ponowieniu po 401 mają jedną implementację.
Moduł nie wymaga DOM ani storage i nie odczytuje refresh cookie.

## Kontrakt i odpowiedzialności

`createStoreClient()` przyjmuje zaufany `baseUrl`, funkcję odczytu aktualnego
access tokenu, dodatkowe nagłówki, funkcję odświeżenia sesji, regułę dopuszczania
odświeżenia oraz opcjonalny adapter ponowienia i implementację fetch do testów.
Udostępnia `request(path, options, retryAfterRefresh, signal)`.

Strony nadal zarządzają klientem, stanem sesji, idle timeoutem, cookie refresh,
logowaniem/wylogowaniem, koszykiem i renderowaniem. Sklep zachowuje blokadę
odświeżania między kartami przez `navigator.locks`. Moduł łączy równoległe
odświeżenia wywołane przez 401 w obrębie jednej instancji; nie dodaje blokady
między kartami do szczegółów produktu ani zamówienia.

- Adres bazowy nadal pochodzi z `MiniTechApi`, zgodnie z A03. Moduł wymaga
  HTTP(S), odrzuca login/hasło, query i fragment w konfiguracji oraz ścieżki
  wychodzące poza origin i prefiks skonfigurowanego API. Nie wprowadza konfiguracji
  API przez parametry adresu strony ani nowej listy zaufanych backendów.
- `Headers` obsługuje obiekty, instancje Headers i pary wartości. Access token
  jest pobierany na bieżąco i ustawiany przez klienta. Nagłówki wywołującego
  nie zastępują go, również przy innej wielkości liter. Opcje i body nie są mutowane.
- Dla `anonymous: true` klient usuwa Authorization i wymusza `credentials: omit`.
  Pozostałe wywołania zachowują `credentials: include`. Jawny nagłówek dostępu
  gościa do zamówienia pozostaje zachowany. Strona zamówienia nadal pobiera go
  z dotychczasowego magazynu przez `MiniTechApi.orderAccess(orderId)`.
- Wymuszono `mode: cors` i `redirect: error`. Dotyczy to odpowiedzi HTTP API;
  nie zmienia nawigacji do adresu płatności zwróconego w JSON. Konfiguracja CORS
  Gateway pozostaje bez zmian. Tokeny i dane logowania nie podążają za redirectem.
- Zachowano JSON, pustą odpowiedź jako `null`, tekstowe komunikaty backendu oraz
  błędy `message`, `code`, `orderId`, `status` i `path`. Opcja `paged` zwraca
  `items`, literalne `X-Has-More: true` i tekstowy `X-Next-Cursor`, również ponad
  bezpiecznym zakresem liczb JavaScript. Domyślny cache pozostaje `no-store`.

## Odświeżenie i anulowanie

Ponowienie jest dopuszczone raz, po 401 i skutecznym odświeżeniu, zgodnie z
regułą danej strony. Logowanie/rejestracja/refresh/logout zachowują dotychczasowe
wyłączenia w sklepie i na produkcie. Błędny dowód MFA (`MFA_*` w metadanych
lub kodzie odpowiedzi) nie uruchamia refresh ani ponowienia. Sam moduł domyślnie
nie włącza odświeżania bez przekazanej reguły.

Równoległe żądania współdzielą jedną oczekującą operację refresh. Spóźnione 401
starego tokenu może ponowić odczyt z już uzyskanym nowym tokenem, bez kolejnego
refresh. Ponowna odpowiedź 401 kończy operację błędem. Nie dodano retry dla utraty
odpowiedzi, błędów sieci, 403, 409, 429 lub 5xx. Ponowienie po uwierzytelnieniu
zachowuje body, metodę, klucz idempotencji i nagłówek dostępu gościa.

Sklep zachowuje `MiniTechCatalogData.withDeadline` i opcje timeoutów. Adapter
ponowienia przekazuje efektywny sygnał deadline do kolejnej próby. Anulowanie
przed wysłaniem, podczas refresh lub odbioru body blokuje późniejszy wynik
i ponowienie. Zatrzymanie odbioru w przeglądarce nie cofa operacji na serwerze.

## Weryfikacja

- **77 testów JavaScript — PASS**, w tym 14 nowych testów rzeczywistego modułu:
  zaufany adres i ścieżki, nagłówki, anonimowość, capability gościa, parsowanie,
  błędy, kursory, równoległe 401, stary token, niezmienne polecenie, limit retry,
  MFA, błędy sieci, anulowanie oraz przenoszenie sygnału deadline.
- **80 testów Chromium — PASS**, pełna regresja sklepu wraz z czterema nowymi
  przypadkami API. Obejmuje checkout, sesje/koszyki, dostępność, katalog, czat,
  dokumenty i zgłoszenia zwrotu. Bez rzeczywistych płatności ani kluczy Stripe.
- **78 pozostałych przypadków Firefox/WebKit/mobilnego Chromium — PASS**:
  dotychczasowe scenariusze sesji, logowania/MFA, zamówień, capability, PDF,
  zgłoszeń zwrotu, stronicowania i smoke.
- **16 prób API w czterech profilach — PASS**: odnowienie 401 w historii sklepu
  i na produkcie, dostęp gościa bez tworzenia sesji klienta oraz brak wysłania
  logowania na origin przekierowania. Pierwszy przebieg ujawnił ograniczenie
  `route.fulfill` dla redirectów w WebKit; test używa teraz własnego serwera
  HTTP z odpowiedzią 307 i przeszedł ponownie we wszystkich profilach.
- Kontrola składni czterech modułów i `git diff --check` — PASS. Konfiguracja
  CI obejmuje nowe testy JS, Chromium i próby API w trzech pozostałych profilach.
  CI nie uruchamiano zdalnie w tym pakiecie. Testy mają własne sesje, dane fixture
  i porty; nie korzystają z profili ani sekretów użytkownika.

Pełna regresja Chromium zawiera cztery próby API; nie należy dodawać ich drugi
raz przy liczeniu unikalnych scenariuszy. Początkowy przebieg trzech pozostałych
profili miał 89/90 sukcesów z opisanym błędem fixture; 78 pozostałych przypadków
przeszło, a wszystkie próby API zweryfikowano ponownie po poprawce fixture.

## Dostarczenie plików i status audytu

Przy przyszłym wdrożeniu należy dostarczyć `store-client.js`, trzy zmienione
skrypty stron i cztery HTML: index, catalog, product i order-detail. Import ma
wersję URL. Szczegóły zamówienia również używają `type="module"`; wersje URL
skryptów wejściowych zostały zmienione. Nie dodano bundlera ani zależności.
Istniejący Dockerfile kopiuje cały `frontend/`, więc obejmuje nowy plik.

Backend, schematy danych, refresh cookie, klucze MFA/JWT i reguły właściciela
na serwerach nie zmieniły się. Testów Maven nie powtarzano; obrazu nie budowano
i nie wdrażano na dev/prod. Pobieranie PDF w `api-config.js`, transport czatu
oraz dalszy podział logiki stron pozostają do kolejnych pakietów A42.

Raport: `tmp/audit-a42-8-26a9f17c/report.json`. Logi: `tmp/a42-8-node.log`,
`tmp/a42-8-browser-final.log`, `tmp/a42-8-cross-browser.log`,
`tmp/a42-8-api-browsers.log`. Artefakty Playwright:
`gitIgnoreOwn/runtime/playwright-a42-8-final`, `playwright-a42-8-browsers`
i `playwright-a42-8-api` w katalogu `gitIgnoreOwn/runtime/`.

Pełne A42 pozostaje częściowo otwarte. A05, całe A41 oraz zmiany samych zdjęć
pozostają odłożone decyzją użytkownika; nie wznawiano ich w tym pakiecie.
