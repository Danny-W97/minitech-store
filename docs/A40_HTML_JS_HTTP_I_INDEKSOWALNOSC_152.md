# A40 — HTML/JS, HTTP i indeksowalność, pakiet 152

Stan: **02.11.2026, Audit_0.1, odbiór lokalny częściowy**.
Poprzedni pakiet A43: [151](A43_ATOMOWA_INSPEKCJA_POWIAZANA_Z_WERSJA_151.md),
polityka `a43.40-2026-11-02.6`, A43.40/.41 nadal NOT_READY.

## Wdrożone w kodzie

Nginx kompresuje statyczne HTML/JS/CSS. Odpowiedzi proxy API nie otrzymały
tej kompresji. JS i CSS mają `Cache-Control: no-cache, must-revalidate`:
przeglądarka może użyć walidatora, ale musi sprawdzić aktualność pliku.
Ręczne parametry `?v=` nie są hashami treści i nie uzyskały `immutable`.
HTML zachowuje `no-store`, zgodnie z istniejącą polityką lokalnych stron.
Brakujący JS/CSS zwraca 404 i `no-store`, zamiast strony index.html z 200.
`order-detail.html` otrzymuje `X-Robots-Tag: noindex, nofollow`.

Serwer `scripts/frontend-server.py`, używany lokalnie przez CC, również
kompresuje tekst i rewaliduje JS/CSS. Silny ETag identyfikuje wybraną
reprezentację, więc gzip i tekst nieskompresowany mają osobne walidatory.
Zmiana pliku nawet przy tym samym mtime zmienia ETag. Obsługiwane są
If-None-Match i 304 bez treści, HEAD, Vary oraz jakość Accept-Encoding;
jawne gzip;q=0 wyklucza kompresję także przy wildcardzie.
Buforowanie kompresji ograniczono do 5 MiB; większy plik obsługuje
dotychczasowy strumieniowy handler z no-store. HTML i błędy nie otrzymują
cache skryptów. Strona szczegółów zamówienia ma noindex także przy
zakodowanym adresie. Nie zmieniono mechanizmów uwierzytelniania.

Zmiany dotyczą kodu i szablonu Nginx. Do zastosowania w uruchomionej
aplikacji potrzebny jest zwykły restart serwera lokalnego albo przebudowa
i wdrożenie obrazu frontendowego. Nie wykonywano tych operacji na dev/test.
Serwer Playwright `scripts/qa/frontend-server.mjs` zachowuje własną politykę
testową; pomiar niżej używa rzeczywistego Nginx i osobno serwera CC.

## Pomiar i testy

Przypięty obraz Nginx z Dockerfile projektu, kontener bez sieci, bez portu
hosta, pliki tylko do odczytu. Dodatkowy listener Unix istnieje wyłącznie
w fiksturze pomiarowej. Upstream API jest nieosiągalny w tej fiksturze;
502 nie jest testem funkcjonalnym działającej bramy aplikacji.

| Zasób | Bez kompresji [B] | Gzip [B] |
|---|---:|---:|
| index.html | 47 318 | 11 633 |
| catalog.html | 36 466 | 8 857 |
| app.js | 17 367 | 4 548 |
| store-account.js | 23 582 | 6 351 |
| store-checkout.js | 30 092 | 9 168 |
| styles.css | 73 706 | 17 472 |
| **Suma** | **228 531** | **58 029** |

Rozmiar ciał tych sześciu odpowiedzi zmniejszył się o **74,61%**.
Nie jest to wynik całej strony, czasu ładowania, LCP, CLS ani INP.
Rozpakowane ciała są identyczne z bazą. Walidacja Nginx potwierdziła 304
bez treści, Vary, nagłówki cache, brak fallbacku HTML dla JS/CSS oraz
noindex szczegółów zamówienia. Dodatkowo **12 testów rzeczywistego HTTP
serwera CC PASS**, bez błędów i pominięć. Te same 12 metod przeszło w WSL oraz w Windows, bez podwójnego
liczenia. Windows wymagał katalogu tymczasowego projektu i uruchomienia
poza ograniczeniem loopback sandboxa; dotyczy wyłącznie fikstury testowej.
Wstępne błędy klienta pomiarowego/testowego zachowano w logach, nie
doliczono odrzuconych przebiegów do zaakceptowanego wyniku.

**Zdjęcia pozostają odłożone.** Potwierdzono niezmienione hashe 19 plików
graficznych; HEAD przykładowego PNG potwierdza tę samą politykę cache,
ETag, typ i brak kompresji. Nie zmieniono formatów, ładowania, srcset,
wersji URL, wymiarów ani treści zdjęć.

Dowody: `tmp/a40-http-152/report.json`, `http-report.json`,
`local-http-accepted-final.log`, `local-http-windows-accepted.log`
i `photo-hashes-before.json` w tym katalogu.
Ponowienie pomiaru: `python scripts/qa/frontend-http-check.py --output tmp/frontend-http-report.json`
w Linux/WSL z lokalnym przypiętym obrazem Nginx. Skrypt nie pobiera obrazu
i usuwa wyłącznie własny nowo utworzony kontener.

## Ocena indeksowalności i pozostały zakres

Katalog jest wypełniany przez JS; `product.html?id=…` pobiera dane przez
API. Serwer początkowo zwraca wspólny HTML z ogólnym tytułem i opisem.
Tytuł produktu jest aktualizowany w `product-detail.js` po odczycie danych.
Nie ma renderowania produktu po stronie serwera/prerenderu, kanonicznego
hosta ani danych strukturalnych produktu w początkowym HTML.
Niepoprawny ID lub brak produktu są pokazywane w JS; sam HTML zwykle
nadal ma status 200. To nie jest odbiór indeksowania w wyszukiwarce.

Do docelowego SEO pozostaje **U40-02**: publiczna domena/kanoniczny host
i wybór publicznych stron oraz wariantów, które mają być indeksowane.
Wtedy należy określić obsługę parametrów, canonical, sitemap, błędów
produktów i ewentualnego renderowania, a następnie sprawdzić wynik na
docelowym środowisku. Nie przyjęto domeny ani decyzji publikacyjnej za
użytkownika. Zdjęcia wymagają osobnego polecenia i nie wchodzą w ten odbiór.

## Punkt wznowienia

Pakiet 152 kończy opisany zakres technicznego HTTP i lokalną ocenę
indeksowalności. **Nie zamyka całego A40, A43 ani audytu.**
Następne niezależne prace: A33 — przegląd pokrycia i lokalne regresje
krytycznych ścieżek, następnie wykryte braki techniczne A34–A36/A44.
Wymagające wartości/decyzji wdrożenie SEO, pełny D02 i odbiory operacyjne
odkładać zgodnie z poleceniem użytkownika. Dokładne blokady i pozostałe
obszary: [przekazanie audytu](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md).
