# A25 — sesje i granice dostępu Control Center

Panel pozostaje lokalnym narzędziem operatora. Nasłuchuje wyłącznie na
`127.0.0.1`, `localhost` lub `::1`. Zabezpieczenia HTTP nie zastępują konta
systemowego: lokalny program działający jako użytkownik nadal może uzyskać
własną sesję panelu.

## Żądania HTTP

Przed wydaniem tokenu i wykonaniem dowolnego endpointu serwer sprawdza:

- lokalny adres połączenia;
- dokładnie jeden `Host`, z dozwoloną nazwą/adresem i rzeczywistym portem CC;
- `Origin`, jeżeli występuje: identyczny protokół HTTP, host i port;
- metadane przeglądarki: blokada `cross-site` i `same-site`, również pomiędzy
  aliasami localhost lub różnymi portami; API nie służy do nawigacji strony.

Adresy domen wskazujących na loopback, inne porty, niejednoznaczne nagłówki,
adresy absolutne w celu żądania i `Origin: null` są odrzucane. Brak CORS,
`no-store`, `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`
i `Cross-Origin-Resource-Policy: same-origin` obowiązują także przy błędach.

## Sesja panelu

`GET /api/bootstrap` wydaje niezależny, losowy token 256-bitowy. Token jest
przechowywany w pamięci strony; serwer przechowuje tylko jego SHA-256.
Wszystkie pozostałe API wymagają `X-Control-Center-Token`, także odczyty
logów, terminala i pobieranie dokumentów. Wyjątkiem jest `GET /api/ping`,
używany przez skrypt uruchomieniowy do sprawdzenia gotowości.

Sesja jest przypisana do hosta i portu, wygasa po **30 minutach bez żądań HTTP**
albo po **8 godzinach od wydania**. Automatyczne odświeżanie panelu przedłuża
czas bezczynności, lecz nie limit bezwzględny. Serwer utrzymuje maksymalnie
**64 sesje**; przy przekroczeniu limitu usuwa najdawniej używaną. Restart CC
unieważnia wszystkie sesje. Poszczególne karty otrzymują osobne tokeny.

Po `401 CC_SESSION_EXPIRED` interfejs czyści token, zatrzymuje odpytywanie,
blokuje wpisywanie do terminala i pokazuje komunikat o odświeżeniu strony.
Nie ponawia automatycznie operacji POST. Ponowne załadowanie strony wydaje
nową sesję. Token nie trafia do cookies, localStorage ani URL; pobieranie
lokalnych PDF/ZIP korzysta z uwierzytelnionego fetch i adresu Blob.

Klient natywny może nie wysyłać `Origin` i metadanych przeglądarki, lecz musi
użyć poprawnego Host oraz tokenu z bootstrapu w nagłówku kolejnych żądań.
Istniejąca weryfikacja operatora przez Customer Service nadal obowiązuje
dla danych biznesowych. Sesja CC sama nie nadaje roli operatora backendu.

## Limity zasobów

Wartości można zmienić dodatnimi liczbami całkowitymi w środowisku procesu CC:

| Zmienna | Domyślnie | Znaczenie |
| --- | ---: | --- |
| `CC_MAX_RUNNING_JOBS` | 4 | Razem zadania oczekujące i wykonywane |
| `CC_MAX_JOB_HISTORY` | 100 | Wpisy historii w pamięci |
| `CC_JOB_TIMEOUT_SECONDS` | 21600 | Maksymalny czas jednego zadania: 6 h |
| `CC_MAX_TERMINALS` | 4 | Aktywne terminale PTY |
| `CC_TERMINAL_IDLE_SECONDS` | 1800 | Bezczynność PTY: 30 min |
| `CC_TERMINAL_LIFETIME_SECONDS` | 21600 | Maksymalny czas PTY: 6 h |

Rezerwacja miejsca na zadanie/terminal jest atomowa. Przekroczenie pojemności
daje `429 CC_RESOURCE_LIMIT`. Historia usuwa najstarsze zakończone wpisy;
nie usuwa logów z dysku — ich retencja pozostaje osobnym zadaniem A43.
Historia zakończonych terminali jest ograniczona do czterokrotności limitu
aktywnych sesji, a ich bufor wyjścia ma ograniczony rozmiar.

Wejście lub wyjście PTY odnawia jego aktywność; samo odpytywanie HTTP jej nie
odnawia. Timeout zadania i zamknięcie/przeładowanie CC kończą jego aktywne
procesy. W WSL/Linux/macOS obejmuje to własną grupę procesów, z eskalacją
TERM → KILL. Zamknięcie CC nie uruchamia komend zatrzymujących całą platformę.
**Usługa uruchomiona jako zadanie na pierwszym planie (`app-run`) podlega temu
samemu limitowi czasu i zatrzymaniu przy przeładowaniu panelu.** Zakończone
zadania uruchamiające niezależne usługi w tle nie są ponownie zatrzymywane.
Terminale PTY wymagają WSL/Linux/macOS; testy HTTP działają również w Windows.

## Uruchomienie i weryfikacja

Zmiana dotyczy serwera Python i skryptu JS panelu. Wymaga restartu procesu CC
i odświeżenia otwartych kart; nie wymaga migracji baz ani zmiany sekretów.

```bash
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
pnpm run test:cc
```

Testy przeglądarkowe uruchamiają osobny panel z rzeczywistą kontrolą HTTP,
sesjami i frontendem oraz zastąpionymi skutkami biznesowymi. Nie uruchamiają
operacji na środowisku dev ani nie odczytują jego sekretów. Test PTY na POSIX
uruchamia wyłącznie tymczasowy skrypt; na Windows jest jawnie pomijany.
W Windows interpreter dla testu przeglądarkowego wskazuje `CC_TEST_PYTHON`.
