# A33 — odzyskiwanie checkoutu, sesja produktu i bramka CI, pakiet 153

Stan: **02.11.2026, Audit_0.1, odbiór lokalny częściowy**.
Poprzedni zakres: [A40, pakiet 152](A40_HTML_JS_HTTP_I_INDEKSOWALNOSC_152.md).
Ostatni A43: [151](A43_ATOMOWA_INSPEKCJA_POWIAZANA_Z_WERSJA_151.md),
polityka `a43.40-2026-11-02.6`, nadal NOT_READY.

## Poprawki aplikacji wykryte przez regresję

Strona produktu usuwała koszyk gościa podczas odtworzenia sesji konta
oraz jej wygaśnięcia. Było to sprzeczne z przyjętą decyzją A43.38:
niezależny koszyk pozostaje do jawnego działania użytkownika. Usunięto
te operacje i zbędny stan służący ich wywołaniu. Koszyki kont nadal
wybierane są po identyfikatorze właściciela.

Wygaśnięcie sesji produktu od razu unieważnia transport i lokalne
poświadczenia oraz ukrywa formularz opinii. Żądanie logout ma ograniczenie
5 s, a oczekiwanie na jego odpowiedź następuje po lokalnym czyszczeniu.
Regresja wstrzymuje odpowiedź logout i sprawdza ukrycie formularza,
usunięcie znacznika sesji oraz zachowanie koszyka gościa.

Zakres zdjęć pozostaje odłożony. Hashe 19 plików graficznych są zgodne
z odbiorem 152; polityka i obsługa zdjęć nie zostały zmienione.

## Nowe scenariusze odzyskiwania

Fixture potrafi zasymulować utratę odpowiedzi Order po zapisaniu zamówienia,
analogicznie do dotychczasowej utraty odpowiedzi Payment. Mapy zapisów
i liczniki skutków przetrwają przeładowanie strony w obrębie testu.

| ID | Weryfikowane zachowanie |
|---|---|
| A33-C01 | Gość: przeładowanie po utracie odpowiedzi Order zachowuje klucz i capability, jeden zakup. |
| A33-C02 | Gość: przeładowanie po utracie odpowiedzi Payment zachowuje klucze Order/Payment, jeden zakup. |
| A33-C03 | Konto: odpowiednik C01 z zachowaniem właściciela. |
| A33-C04 | Konto: odpowiednik C02 z zachowaniem właściciela. |
| A33-C05 | Brak możliwości zapisu próby w sessionStorage blokuje POST przed zakupem; koszyk pozostaje. |
| A33-C06 | Ponowne wysłanie formularza podczas oczekiwania nie uruchamia drugiego zamówienia. |
| A33-C07 | Konto B kupuje ze swoim kluczem, zachowując nierozstrzygniętą próbę A; A może potem wznowić swój zakup. |

Siedem przypadków wykonano w Chromium, Firefox, WebKit i mobilnym Chromium:
**28/28 PASS**, bez pominięć i ponowień. Są to deterministyczne fikstury API;
zewnętrzny odbiór Stripe pozostaje w odłożonym A05.

## Egzekwowanie CI

Główny Chromium zapisuje JSON. Dodatkowy krok CI uruchamia odzyskiwanie
w pozostałych trzech profilach z `--retries=0 --fail-on-flaky-tests`.
`critical-browser-check.py` wymaga wszystkich 28 par ID/profil i dokładnie
jednego wyniku passed z retry=0. Odrzuca braki, duplikaty, skip,
oczekiwany błąd, flaky, ponowienia, niewłaściwe źródło i błędy raportu.
`audit_readiness.py` wymaga wykonywanych komend tej macierzy i bramki;
obecność pliku lub komendy w komentarzu nie potwierdza wykonania.

Raport negatywnej kontroli powstał z rzeczywistego JSON: jeden przypadek
zmieniono na skipped, pozostawiając zerowe liczniki błędów. Bramka odrzuciła
go kodem 1. Jej wynik zawiera bezpieczne ID/profil, bez treści żądań.
Raporty mieszczą się w limicie odczytu 16 MiB.

Serwer statyczny QA sprawdza granicę katalogu po normalizacji i realpath.
Zakodowane przejście do sąsiedniego katalogu z tym samym prefiksem oraz
dowiązanie poza frontend zwracają 404. Niepoprawne kodowanie URI zwraca
400 bez zakończenia procesu. HEAD i zwykłe zasoby pozostają obsługiwane;
błąd strumienia pliku nie staje się nieobsłużonym wyjątkiem procesu.

## Odbiór i środowisko

**145 kombinacji test/przeglądarka PASS**: 124 Chromium, 7 Firefox,
7 WebKit i 7 mobilnego Chromium. Obejmuje pełny lokalny zestaw UI Chromium
oraz macierz odzyskiwania. Nowe definicje: 7 odzyskiwania i 1 wygaśnięcia
sesji produktu. Dodatkowo **25 metod Python + 16 testów Node PASS**:
11 nowych metod Python i 4 nowe testy Node, reszta regresje. Kod A44
przeszedł kontrolę granic publikacji po zmianie workflow.

Przeglądarki korzystają z jawnego zegara fikstury, który można przesuwać
przy badaniu wygaśnięcia. Kontrola sesji w kodzie produkcyjnym zachowuje
odrzucanie przyszłego znacznika aktywności. Scenariusze checkoutu używają
istniejącego trybu ograniczonego ruchu, aby animacja przewijania nie
decydowała o wyniku testu ponowienia płatności.

WSL nie miał bibliotek WebKit. Odbiór tego silnika wykonano w istniejącym
lokalnym obrazie z bibliotekami, przypiętym digestem, bez sieci i portu hosta.
Zamontowano tylko publiczne źródła, katalog wyników oraz pakiety Playwright
1.62.1 i odpowiadające im silniki, w tym WebKit 2336. Obraz bazowy dostarcza
biblioteki systemowe; jego własne silniki nie zastępują wersji projektu.
Nie instalowano bibliotek globalnie w WSL.

Wcześniejsze nieudane przebiegi zachowano w logach: ujawniły ukryte pola
i nawigację w helperze testu, nieaktualne oczekiwanie koszyka, rzeczywisty
błąd na stronie produktu, niestabilny zegar fikstury, przewijanie oraz
brak bibliotek WebKit. Do odbioru wchodzą wyłącznie końcowe raporty przy
aktualnym kodzie; przebiegów nie dodaje się ponownie do liczb testów.

Dowody: `tmp/a33-critical-153/report.json`, `browser-gate.json`,
`chromium-accepted.json`, `matrix-accepted.json`, `webkit-accepted/results.json`,
logi jednostkowe i `source-hashes.json`. Brak katalogu tmp po przeniesieniu
projektu oznacza brak artefaktów, a nie nowy PASS.

## Status i punkt wznowienia

Mapa P1: **13/14 wykonanych, otwarte A05**. Zwykła kontrola mapy przechodzi;
`audit_readiness.py --release` nadal odmawia kodem 1. A33 pozostaje częściowe.
Nie wykonano GitHub Actions, zmian branch protection ani wdrożenia dev/test.
Bez migracji, sekretów, rzeczywistych zamówień i wywołań operatora płatności.

Następne: **A35 — przegląd pokrycia korelacji HTTP/Redis i readiness**,
następnie wdrożenie braków technicznych niewymagających wyboru SLO lub
zewnętrznego odbiornika. Zacząć od [A35](A35_ALERTY_BIZNESOWE.md) i
[przekazania](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md).
Wymagające decyzji/wartości odbiory A34/A35/A36/A44 oraz A43 pozostają
odłożone. A05, A41 i same zdjęcia nie są automatycznie wznawiane.
