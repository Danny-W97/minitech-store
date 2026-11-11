# A37 — klawiatura, dialogi i kontrast sklepu (26.10.2026)

A37 wykonano w zakresie obsługi klawiatury i pomiarów kontrastu opisanych poniżej.
Weryfikacja odbyła się lokalnie na deterministycznych odpowiedziach API.
Nie wdrażano na dev/prod; A05 i zmiany samych zdjęć pozostają odłożone.

## Zmiany

Na stronie głównej i w katalogu cztery typy okien — logowanie/konto, wybór zakupu,
porównanie i zachowane potwierdzenie — korzystają z natywnego `<dialog>` oraz
`showModal()`. Wspólna obsługa w `frontend/dialogs.js`:

- przenosi fokus na tytuł po przygotowaniu widoku, a przy MFA na kod/passkey;
- zamyka cykl Tab/Shift+Tab, z pominięciem ukrytych i wyłączonych kontrolek;
- korzysta z natywnego inertnego tła, blokuje przewijanie dokumentu i obsługuje Escape;
- przywraca fokus do wywołującego elementu albo właściwego przycisku zastępczego,
  jeśli wywołujący element zniknął lub był w zamkniętym menu;
- obsługuje przeglądarki, w których kliknięcie przycisku nie nadaje mu fokusu;
- zachowuje logikę zamykania konta, w tym usuwanie aktywnego wyzwania MFA;
- nie ukrywa ponownie otwartego dialogu przez opóźnione zdarzenie `close`.

Wszystkie przyciski zamknięcia pozostają dostępne. Nagłówki wewnętrzne mają
`tabindex="-1"`; sam element `<dialog>` nie otrzymuje tabindex.
Przejście z wyboru zakupu do logowania nie pozostawia zablokowanego tła.

Podgląd koszyka reaguje na Enter po uzyskaniu fokusu i oddaje fokus po Escape.
Nie otwiera się już samym otrzymaniem fokusu, co wcześniej prowadziło do
natychmiastowego zamknięcia przy naciśnięciu Enter. Opóźnione zamykanie po
opuszczeniu myszą nie jest planowane, gdy fokus nadal znajduje się w koszyku.

Etapy dostawy i podsumowania kierują fokus na odpowiedni nagłówek. Wynik zakupu
jest komunikatem statusu z fokusem. Pole MFA jest wymagane i obsługuje Enter;
wyłączony przycisk nie wysyła kolejnej weryfikacji. Formularze zachowują natywną
walidację i dostęp do pierwszego niepoprawnego pola.

Test porównania ujawnił, że `visible.map(productCard)` przekazywało indeks tablicy
jako wariant karty. Przywrócono jawne przekazanie wariantu katalogu i generowanie
przycisków porównania. Zachowano dotychczasową klasę kart i geometrię zdjęć.
Przewijany obszar tabeli porównania jest nazwanym regionem dostępnym klawiaturą.

## Kolory, reflow i ruch

Usunięto wyłączenie reguły axe `color-contrast`. Poprawiono akcent, opisy,
etykiety kategorii, informacje o oszczędności, znaczniki promocji i tekst na
ciemnych sekcjach. Przykładowe pomiary jednolitych kolorów:

| Para | Przed zmianą | Po zmianie |
| --- | --- | --- |
| Biały tekst na głównym akcencie | 3,04:1 | 5,28:1 (`#c13e1e`) |
| Szary opis na `#f5f6f8` | 4,35:1 | 5,70:1 (`#586273`) |

Dodano wyraźny fokus klawiatury. Okna mieszczą przewijany formularz przy szerokości
320 px; aktywne kontrolki są osiągalne i widoczne po Tab. Sprawdzono także
powiększenie CSS 200%. Preferencja reduced-motion ogranicza animacje, przejścia
i płynne przewijanie CSS. Ten pakiet nie zmienia plików, wymiarów, ładowania,
formatów, cache ani automatycznej rotacji samych zdjęć.

## Testy i dowody

Raport: `tmp/audit-a37-1977a83a/report.json`.

- **60 testów dostępności:** 15 scenariuszy × Chromium, Firefox, WebKit i mobilny
  Chrome — PASS (`accessibility.log`).
- **53 pozostałe testy UI Chromium:** konto, katalog, koszyk, checkout, dokumenty,
  historia zamówień, zwroty i czat — PASS (`regression.log`).
- **26 testów jednostkowych JavaScript** — PASS (`unit.log`).
- Obejrzano zrzuty logowania desktop, rejestracji przy 320 px i powiększenia CSS.
  Są zapisane obok raportu: `login-desktop.png`, `registration-320px.png`,
  `login-zoom-200.png`.

Scenariusze obejmują zakup gościa wykonany wyłącznie Tab/Shift+Tab/Enter/Space/Escape,
walidację pustej dostawy, aktywne pola faktury, podsumowanie i pojedyncze utworzenie
zamówienia/płatności. Logowanie MFA obejmuje błąd, anulowanie i ponowne poprawne
logowanie. Pomiar axe obejmuje także rejestrację, reset hasła, profile konta,
zamówienia, ulubione, bezpieczeństwo, porównanie oraz wszystkie pięć stron sklepu.
Reguły używają tagów `wcag2a`, `wcag2aa`, `wcag21aa`, z włączonym kontrastem;
test odrzuca naruszenia serious/critical. Wyniki i reguły wymagające ręcznej
oceny są dołączane do raportu Playwright.

CI instaluje trzy silniki i egzekwuje testy dostępności we wszystkich czterech
profilach. Standardowe testy UI zawierają Chromium; dodatkowy krok uruchamia
Firefox, WebKit i mobilny Chrome. Lokalnie można użyć:

```bash
pnpm exec playwright test e2e/tests/accessibility/accessibility.spec.ts
pnpm run test:unit
```

`PLAYWRIGHT_FRONTEND_PORT` pozwala uruchomić własny serwer na osobnym porcie;
wtedy test nie wykorzystuje już istniejącego serwera. `PLAYWRIGHT_OUTPUT_ROOT`
pozwala zachować oddzielny raport. Domyślne ustawienia pozostają zgodne z CI.
Test bezpieczeństwa adresu API uwzględnia kontrakt same-origin dla takich portów
i nadal odrzuca przekierowanie przez parametr URL do domeny atakującego.

## Granice odbioru

Wynik dotyczy opisanych scenariuszy i danych fixture. Nie jest deklaracją zgodności
całej aplikacji z WCAG. Nie wykonano odbioru fizycznym czytnikiem ekranu, passkey
na sprzęcie użytkownika ani natywnego zoomu interfejsu przeglądarki. Pomiar 200%
dotyczy CSS zoom, a reduced-motion — zachowania CSS. Ręczny przegląd z NVDA/VoiceOver
oraz pełna ocena WCAG pozostają osobnym zakresem; test axe nie zastępuje ich.

Źródła techniczne: [WAI-ARIA — wzorzec dialogu](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/),
[natywny dialog HTML](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/dialog).
