# A26 — klucze AI w Control Center

Ustawienia silnika AI i klucze API są przechowywane oddzielnie. Zmiana modelu,
dostawcy lub kontekstu nie kopiuje klucza ze zmiennej środowiskowej do pliku.

## Miejsca przechowywania

| Dane | Miejsce |
| --- | --- |
| Dostawca, model, endpoint, dołączanie kontekstu | `gitIgnoreOwn/runtime/control-center-web/ai-config.json` |
| Klucze wpisane w panelu dla DEV | `<secret-root>/secrets/ai-dev.json.sops` |
| Klucze wpisane w panelu dla TEST | `<secret-root>/secrets/ai-test.json.sops` |
| Klucz age do danego profilu | `<secret-root>/keys/dev.agekey` lub `test.agekey` |
| Klucz ze zmiennej środowiskowej | Pamięć procesu CC, bez zapisu na dysk |

`secret-root` to `MINI_ORDER_PLATFORM_SECRETS_DIR`, a gdy go nie ustawiono:
`$XDG_CONFIG_HOME/mini-order-platform` albo `~/.config/mini-order-platform`.
Jest to ten sam katalog kluczy age, z którego korzystają profile platformy.
Magazyn AI jest osobnym dokumentem SOPS; nie modyfikuje haseł baz ani JWT/MFA.

Skarbiec przechowuje osobne klucze dostawców `codex`, `antigravity` i `cursor`.
Klucz jednego dostawcy nie jest używany po przełączeniu na innego. Profile
DEV i TEST nie współdzielą zapisanych kluczy. Ustawienia modelu są wspólne
dla panelu, a skarbiec jest wybierany według aktywnego profilu.

Dla aktywnego dostawcy pierwszeństwo ma klucz zapisany w SOPS. Jeżeli go nie
ma, CC sprawdza kolejno:

- `codex`: `OPENAI_API_KEY`, `CODEX_API_KEY`;
- `antigravity`: `GEMINI_API_KEY`, `GOOGLE_API_KEY`, `ANTIGRAVITY_API_KEY`;
- `cursor`: brak automatycznego dziedziczenia kluczy innych dostawców.

Zmienne środowiskowe dotyczą procesu CC, więc mogą być dostępne w obu
profilach. Klucz jest pobierany wyłącznie dla aktualnie wybranego dostawcy.

## Obsługa formularza

1. Otwórz `Asystent AI → Ustawienia AI`. Panel pokazuje źródło klucza,
   aktywny profil i maskę. Pole hasła jest puste.
2. Pozostaw puste pole, aby zachować obecny klucz. Sama zmiana modelu nie
   zmienia sekretu. Starsza otwarta karta wysyłająca maskę również zachowuje klucz.
3. Wpisz nowy klucz, aby zapisać go zaszyfrowanego dla wybranego dostawcy
   i aktywnego profilu. Wymagane są SOPS, `age-keygen` i istniejący klucz age
   tego profilu; można go przygotować standardowym kreatorem sekretów DEV/TEST.
4. Zaznacz `Usuń zapisany klucz wybranego dostawcy ze skarbca`, aby usunąć
   tylko ten wpis. Zmienna środowiskowa nie jest usuwana — jeżeli istnieje,
   ponownie staje się źródłem klucza. Inni dostawcy/profil pozostają bez zmian.

Nie można jednocześnie wpisać nowego klucza i zaznaczyć jego usunięcia.
Anulowanie, zamknięcie formularza, zmiana dostawcy i poprawny zapis czyszczą
pole klucza. Endpoint połączenia nie przyjmuje hasła, query ani fragmentu;
klucz należy wpisać w dedykowane pole. Publiczna konfiguracja API CC zwraca
wyłącznie maskę i metadane, nigdy wartość `apiKey`.

## Migracja starej konfiguracji

Przy starcie CC oraz odczycie/zapisie konfiguracji serwer wykrywa stare pole
`apiKey`. Dla niepustej wartości:

1. Odczytuje dotychczasowego dostawcę i aktywny profil.
2. Szyfruje klucz do osobnego dokumentu SOPS, używając publicznego odbiorcy
   wyprowadzonego z klucza age tego profilu.
3. Odszyfrowuje wynik i porównuje zawartość przed zastąpieniem pliku.
4. Zapisuje szyfrogram atomowo, a następnie atomowo zastępuje konfigurację
   wersją zawierającą wyłącznie ustawienia.

Jawne dane dla SOPS przechodzą przez stdin/stdout w pamięci; nie trafiają
do argumentów procesu ani plików tymczasowych. Tymczasowe pliki zapisu
zawierają wyłącznie szyfrogram albo ustawienia bez klucza. Nie powstaje
jawna kopia `.bak`. Puste stare pole jest usuwane bez potrzeby użycia SOPS.

Jeżeli zapis ustawień zostanie przerwany po zapisaniu szyfrogramu, migracja
może być ponowiona. Błąd szyfrowania, weryfikacji lub brak klucza age zachowuje
stary wpis do czasu udanej migracji; panel zgłasza problem zamiast po cichu
kasować klucz. Konflikt dwóch różnych kluczy dla tego samego dostawcy
zachowuje oba pliki i wymaga świadomego rozstrzygnięcia przed migracją.
Start pozostałych funkcji CC nie jest blokowany przez niedostępny magazyn AI.

Migracja obejmuje kanoniczny `ai-config.json`. Nie usuwa zewnętrznych,
historycznych kopii zapasowych; jeżeli zawierają dawny jawny klucz, należy
uwzględnić je podczas porządkowania sekretów i rotacji klucza u dostawcy.

## Uprawnienia i granice

Pliki mają tryb `0600`, a nowo tworzone katalogi `0700` na systemach plików
obsługujących uprawnienia POSIX. Montowanie NTFS w WSL może ignorować chmod;
tam dostęp kontrolują uprawnienia Windows. Domyślny skarbiec w katalogu domowym
WSL korzysta z linuksowych praw dostępu. Ustawienia po migracji nie zawierają
klucza API także wtedy, gdy projekt znajduje się na NTFS.

Serwer odrzuca dowiązania symboliczne plików konfiguracji/magazynu/klucza age.
Operacje zapisu w jednym procesie są serializowane; równoległe aktualizacje
dostawców nie nadpisują wzajemnie swoich wpisów. Współdzielenie tego samego
skarbca przez wiele procesów CC nie ma blokady między procesami — uruchamiaj
jeden panel dla danego katalogu sekretów.

Klucze LLM nie są zastępowane poświadczeniem operatora platformy i nie są
przekazywane dalej przez przekierowania HTTP. Błędy dostawcy/SOPS nie zwracają
surowego wyjątku ani wyjścia CLI. Gemini używa nagłówka `x-goog-api-key`,
zgodnie z [dokumentacją uwierzytelniania Google](https://ai.google.dev/api#authentication),
zamiast klucza w URL. Testy nie wykonują płatnych wywołań LLM.

## Wdrożenie i testy

Wymagany jest restart procesu CC i odświeżenie kart. Nie ma migracji baz danych.
Szyfrowanie sprawdzono na SOPS 3.13.2 oraz age; w CI jest przypięta ta wersja
SOPS z weryfikacją sumy SHA-256 pobranego pliku, na podstawie
[oficjalnego wydania](https://github.com/getsops/sops/releases/tag/v3.13.2).
Tryb offline i klucze pobierane wyłącznie z otoczenia nie wymagają SOPS,
jeżeli nie istnieje stary jawny wpis ani zapisany magazyn.

```bash
CC_REQUIRE_SOPS_TESTS=1 python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
pnpm run test:cc
```

Bez zainstalowanych SOPS/age testy rzeczywistego szyfrowania są jawnie
pomijane; `CC_REQUIRE_SOPS_TESTS=1` zamienia brak narzędzi w błąd, aby CI nie
pomijało tej weryfikacji. Testy szyfrowania tworzą własne klucze i skarbce
w katalogu tymczasowym systemu; nie odczytują sekretów dewelopera.
