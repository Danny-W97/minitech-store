# A42.10 — moduł transportu czatu (27.10.2026)

`frontend/chat-client.js` wydziela odczyty i zapisy rozmowy z widgetu.
`chat-widget.js` korzysta z tego modułu oraz wspólnego `store-client.js`;
nie ma już własnych wywołań fetch ani lokalnego dekodowania odpowiedzi HTTP.
Formularz, szkice, historia, język, polling i dotychczasowe klucze localStorage
pozostają odpowiedzialnością widgetu.

## Kontrakt i dostęp

Fabryka `createChatClient({request})` przyjmuje transport JSON i udostępnia
`create(body, token)`, `reply(id, body, token)`, `conversation(id, token)`,
`history(id, token, before)` oraz `cancelReads()`. Moduł nie wymaga DOM,
pamięci przeglądarki ani timerów. Waliduje identyfikatory, format capability,
zgodność ID odpowiedzi z odczytywaną rozmową oraz tablicę wpisów historii.

Każde żądanie jest anonimowe dla sesji konta: `credentials: omit`, bez bearer
JWT i bez odświeżania sesji klienta. Dostęp pozostaje oparty na istniejącym
`X-Chat-Access-Token`, również gdy użytkownik ma zalogowane konto w sklepie.
Token jest wyłącznie w nagłówku. Format 43–128 znaków base64url odpowiada
obecnej walidacji serwera. Zapis ID/capability i odtwarzanie rozmowy po reloadzie
zachowują dotychczasowe klucze i zakres API.

Wspólny klient zapewnia zaufany origin/prefiks API, brak przekierowań, nagłówki
JSON i spójne błędy HTTP. Nie dodano automatycznego retry zapisów ani generowania
nowych kluczy poleceń w transporcie. Dalsza wiadomość zachowuje `requestId`
nadany przez widget i używa go ponownie dla tego samego zamiaru po utracie
odpowiedzi. Kontrakt tworzenia pierwszej wiadomości pozostaje bez zmian.

## Odczyty i cykl życia widoku

- Identyczne równoległe odczyty współdzielą jedno oczekujące żądanie. Klucz
  obejmuje rodzaj odczytu, ID rozmowy, capability i kursor. Różne rozmowy lub
  strony historii pozostają niezależne. Zakończone wyniki nie są cache'owane.
- Historia zachowuje limit 50 i dokładne identyfikatory przekazane jako tekst.
  Nieprecyzyjne wartości number, niewłaściwe kursory i ścieżki w miejscu ID
  są odrzucane przed HTTP.
- Zamknięcie czatu, ukrycie dokumentu i rozpoczęcie nowej rozmowy anulują
  oczekujące odczyty przez AbortController. Generacja dodatkowo odrzuca wynik
  i błąd funkcji ignorującej sygnał. Nie anuluje się wysłanego zapisu wiadomości.
- Widget nie stosuje błędów ani wyników starego ID/capability do nowego widoku.
  Znacznik konkretnego odczytu zapobiega odblokowaniu nowszego odczytu przez
  `finally` starego żądania. Natychmiastowe ponowne otwarcie nie czeka na stary
  odczyt, a niewysłany szkic pozostaje w formularzu.
- Aktualna odpowiedź 404 czyści ID i capability oraz przywraca formularz
  pierwszej wiadomości z wymaganym imieniem i e-mailem. Spóźnione 404 starej
  rozmowy nie czyści nowej rozmowy. Błąd historii pozostawia dotychczasowy widok.

Anulowanie odbioru przez przeglądarkę nie gwarantuje zatrzymania pracy serwera.
Nie zmieniono logiki backendu, trwałości wpisów, outboxu ani autoryzacji rozmów.

## Weryfikacja i dostarczenie

- **104 testy JavaScript — PASS**, w tym 12 nowych testów rzeczywistego modułu:
  capability, niezmienne polecenie, wspólne odczyty, izolacja stron/instancji,
  anulowanie i późne wyniki, błędy, walidacja odpowiedzi oraz integracja z
  rzeczywistym wspólnym klientem HTTP bez cookies/JWT/refresh.
- **90 testów Chromium — PASS**, pełna regresja sklepu z pięcioma nowymi
  scenariuszami czatu. **45 testów Firefox/WebKit/mobilnego Chromium — PASS**:
  transport, historia czatu, ponowienia dalszych wiadomości, dostępność na
  małym ekranie, capability i dostęp do dokumentów.
- Pięć nowych scenariuszy wykonano w czterech profilach (20 przypadków,
  zawartych w powyższych liczbach): anonimowy transport przy aktywnym koncie,
  zamknięcie/ponowne otwarcie ze szkicem, zmiana rozmowy i późne 404, aktualne
  404 zapisanej rozmowy oraz ładowanie/otwieranie widgetu na wszystkich pięciu stronach.
- Składnia obu modułów i `git diff --check` — PASS. Konfiguracja CI obejmuje
  nowe testy JS i scenariusze czatu w czterech profilach. CI nie uruchamiano zdalnie.

Pięć stron (index, catalog, product, order-detail, delivery) ładuje widget jako
moduł ES z nową wersją URL. Przy przyszłym wdrożeniu należy dostarczyć razem
`chat-client.js`, zmieniony widget i pięć HTML, zachowując `store-client.js`
z A42.9. Dockerfile już kopiuje cały frontend. Bez nowych zależności i bundlera.

Raport: `tmp/audit-a42-10-c49b318e/report.json`. Logi:
`tmp/a42-10-node.log`, `tmp/a42-10-browser-final.log`, `tmp/a42-10-cross-browser.log`.
Artefakty: `gitIgnoreOwn/runtime/playwright-a42-10-final` i
`gitIgnoreOwn/runtime/playwright-a42-10-browsers`.

Testy korzystały z własnych danych, sesji, capability i portów. Backend i schematy
bez zmian; nie powtarzano Maven, nie budowano obrazu ani nie wdrażano na dev/prod.
Pełne A42 nadal wymaga dalszego podziału logiki stron i pozostałych duplikacji.
A05, całe A41 oraz zmiany samych zdjęć pozostają odłożone.
