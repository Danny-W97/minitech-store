# A42.5 — moduł API i sesji Control Center (26.10.2026)

`scripts/control-center/web/panel-client.js` wydziela transport HTTP i sesję
z dużego `app.js` panelu. Moduł ES nie zależy od DOM ani storage. Fabryka
`createControlClient()` udostępnia `json(path, options)` i `blob(path, options)`;
pozwala wstrzyknąć adres bazowy, fetch, język i callback wygaśnięcia sesji.
Renderowanie, routing, polling i tworzenie linków pobierania pozostają w UI.

## Kontrakt i ochrona sesji

- Token istnieje w prywatnym domknięciu instancji klienta. Odpowiedź bootstrapu
  zwracana do UI zawiera konfigurację bez tokenu; token nie trafia do stanu UI,
  storage, cookies ani URL. Kontrakt sieciowy bootstrapu serwera jest zachowany.
- Bootstrap wymaga tokenu zgodnego z obecnym formatem serwera: 43 znaki
  base64url. Nieprawidłowa odpowiedź nie otwiera dostępu do prywatnych endpointów.
- Wszystkie żądania wymagają tego samego origin i ścieżki `/api/`; adresy z
  loginem/hasłem są odrzucane. Bez sesji dopuszczone są tylko bootstrap i ping.
- Klient ustawia `X-Control-Center-Token` oraz wymusza `credentials: omit`,
  `mode: same-origin` i `redirect: error`. Opcje wywołującego nie mogą zastąpić
  tokenu ani zmienić tych ustawień. Obsługiwane są również obiekty `Headers`.
- Tylko HTTP 401 z `CC_SESSION_EXPIRED` dotyczący aktualnego tokenu unieważnia
  sesję. Zwykłe 401/403 nie usuwają działającej sesji. Callback UI zatrzymuje
  polling i blokuje wejście terminala; komunikat PL/EN prosi o odświeżenie strony.
- Spóźnione 401 starego tokenu nie usuwa nowego tokenu z bootstrapu. Bootstrap
  rozpoczęty przed unieważnieniem nie przywraca wygasłej sesji. Wyniki JSON i
  pliki binarne kończące się po unieważnieniu są odrzucane, również gdy body
  dociera później niż nagłówki odpowiedzi.
- Zachowano `error.definitive === true` wyłącznie dla jawnej wartości boolean
  w odpowiedzi serwera. Błąd sieci ani spóźniony wynik nie potwierdzają odrzucenia
  operacji zapisu. Nie dodano automatycznych ponowień ani odnawiania sesji.

PDF i ZIP przechodzą przez tę samą ochronę. UI tworzy tymczasowy URL dopiero
po otrzymaniu zaakceptowanego Blob i zwalnia go jak dotychczas. Anulowanie odbioru
w przeglądarce nie oznacza wycofania operacji rozpoczętej na serwerze.

## Ładowanie

`index.html` CC ładuje `app.js` przez `type="module"`, a skrypt importuje klienta
z jawną wersją URL. Dotychczasowy serwer statyczny panelu obsługuje nowy plik;
testy przeglądarkowe używają jego rzeczywistej obsługi zasobów. Nie dodano bundlera
ani zależności. Przy przyszłym wdrożeniu należy dostarczyć razem `index.html`,
`app.js` i `panel-client.js`. `server.py` i reguły autoryzacji serwera nie zmieniły
się w tym pakiecie. Nie wykonywano wdrożenia na dev/prod.

## Weryfikacja

- **52 testy JavaScript — PASS**, w tym 17 nowych przypadków importujących
  rzeczywisty moduł ES: prywatność tokenu, ograniczenia URL/opcji, błędy,
  niezależne instancje oraz kontrolowana współbieżność odpowiedzi i body.
- **20 testów CC w Chromium — PASS**, w tym dwa nowe scenariusze: brak pobrania
  spóźnionego ZIP po wygaśnięciu sesji i brak żądania do adresu przekierowania.
  Regresja obejmuje także chronione odczyty, PDF/ZIP, terminal, ponowienia operacji,
  reload sesji, Host/Origin oraz ustawienia AI. Brak błędów ładowania modułu.
- CI już uruchamia cały zestaw JS i CC, więc obejmuje również nowe przypadki.
  Testy korzystają z własnego serwera fixture, sesji i danych przeglądarki.
- Kontrola składni modułów i `git diff --check` — PASS. Backend i Maven bez zmian;
  ich testów nie powtarzano dla wydzielenia modułu przeglądarkowego.

Raport: `tmp/audit-a42-5-5db4af13/report.json`. Logi: `tmp/a42-5-node.log`,
`tmp/a42-5-cc-browser-final.log`. Artefakty Playwright:
`gitIgnoreOwn/runtime/playwright/cc-results`.

Pełne A42 pozostaje częściowo otwarte: dalszy podział dużych plików i pozostałe
duplikacje wymagają kolejnych pakietów. A05, całe A41 oraz zmiany samych zdjęć
pozostają odłożone decyzją użytkownika.
