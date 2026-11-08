# A43.17 — pobieranie danych profilu w sklepie

Po zalogowaniu wybierz **Konto → Profil → Pobierz dane profilu (JSON)**.
Przycisk jest u góry formularza. Opis wyjaśnia, że plik zawiera zapisany
profil i podsumowanie zabezpieczeń; zamówienia i dane innych usług pozostają
poza tym pobraniem. Niezapisany szkic formularza nie trafia do pliku ani nie
jest automatycznie zapisywany.

Plik `customer-profile-export.json` pochodzi z częściowego eksportu A43.16.
Klient używa istniejącego transportu sklepu i bieżącej sesji. Zastosowano
istniejącą politykę odświeżenia access tokenu po 401; odmowa odnowienia
prowadzi do czytelnego komunikatu i formularza logowania.

Przycisk jest nieaktywny podczas żądania. Równoczesne wywołania tej samej
operacji współdzielą żądanie i pobranie; po błędzie można spróbować ponownie.
Po wylogowaniu, wygaśnięciu sesji, zmianie konta lub opuszczeniu strony
trwająca operacja jest anulowana. Spóźniona odpowiedź poprzedniej sesji nie
może utworzyć pliku, nawet po ponownym zalogowaniu na to samo konto.

Przed zapisaniem przeglądarka sprawdza typ JSON, rozmiar otrzymanego Blob
(maksymalnie 64 KiB), wersję/scope, znacznik niepełnego eksportu, manifest
pominięć i identyfikator aktualnego właściciela. Limit ten jest sprawdzany
po odebraniu Blob; nie jest limitem strumieniowania sieciowego. HTML, plik
innego konta i niezgodny kontrakt nie są zapisywane. Tymczasowy object URL
jest zwalniany po pobraniu albo natychmiast, gdy nie działa timer.
Niebezpiecznie duży identyfikator liczbowy JavaScript jest odrzucany z
komunikatem błędu, aby nie porównywać zaokrąglonych identyfikatorów kont.

Widok i komunikaty tej funkcji są dostępne po polsku i angielsku, a status
jest ogłaszany przez `role=status`. Treść błędów z serwera nie jest wstawiana
jako HTML. Dane eksportu i tokeny nie są zapisywane do localStorage ani
sessionStorage przez tę funkcję. Pobrany plik na urządzeniu jest kopią
zarządzaną przez użytkownika.

## Zakres otwarty

A43.17 udostępnia istniejący częściowy eksport: inne usługi, historia
bezpieczeństwa, redakcja audytu, usuwanie/anonimizacja i obsługa kopii
nadal wymagają osobnej realizacji. Rejestr pozostawia globalne operacje
eksportu/usunięcia/backupów jako `pending`. A05, całe A41 i zmiany
dotyczące samych zdjęć są odłożone zgodnie z decyzją użytkownika.

## Weryfikacja

- `node --test scripts/qa/unit/*.test.cjs`
- `node node_modules/@playwright/test/cli.js test e2e/tests/api/account-export.spec.ts --project=chromium --project=firefox --project=webkit --project=mobile-chrome`
- `python3 scripts/qa/data-lifecycle-check.py`
- `python3 -m unittest discover -s scripts/qa/unit -p test_data_lifecycle.py`

Testy przeglądarkowe używają fikcyjnego API i kont. Sprawdzają rzeczywiste
pobranie UTF-8, klawiaturę, PL/EN, odświeżenie i odmowę sesji, retry,
podwójne kliknięcia, wylogowanie, zmianę konta, zły plik i czyszczenie URL.
Odbiór axe dotyczy nowego obszaru profilu i poważnych/krytycznych naruszeń;
nie stanowi deklaracji pełnej zgodności WCAG.

<!-- A43_17_RESULTS -->
- **210 testów JavaScript — PASS, bez pominiętych**, w tym 12 nowych.
- **36 testów przeglądarkowych — PASS**: dziewięć scenariuszy w Chromium,
  Firefox, WebKit i mobilnym Chrome. Obejmują klawiaturę, widok PL/EN oraz
  kontrole axe nowego obszaru. Poprawiono oczekiwany token testowy w pierwszym
  przebiegu; testy następnie przeszły w czterech profilach.
- **35 testów regresyjnych Chromium — PASS**: logowanie, dostępność,
  faktury i podstawowe widoki. Wykonano je przed doprecyzowaniem błędu
  niebezpiecznego identyfikatora; po tym doprecyzowaniu ponowiono wszystkie
  testy jednostkowe i 36 scenariuszy eksportu.
- **27 testów bramki danych — PASS, bez pominiętych**; bieżący rejestr
  obejmuje 24 referencje mechanizmów. Globalny eksport/usunięcie/backup
  pozostają pending.
- Przejrzano zrzuty widoku profilu na komputerze i telefonie. Przycisk
  przeniesiono wyżej w formularzu, aby był widoczny po otwarciu panelu.

Raport końcowy: `tmp/audit-a43-17/report.json`. Logi:
`tmp/a43-17-node-final.log`, `tmp/a43-17-browsers-final-v2.log`,
`tmp/a43-17-ui-regression.log`, `tmp/a43-17-lifecycle-tests.log`.
<!-- /A43_17_RESULTS -->

Nie dodano endpointów, migracji, zmiennych ani zależności. Nie wykonywano
operacji na danych kont użytkownika, nie restartowano Control Center i nie
wdrażano zmian na dev/prod.
