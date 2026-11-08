# A42 — zakończenie podziału modułów i kontroli duplikacji

Data: 27.10.2026. Branch: `Audit_0.1`.

**Status: A42.1–A42.21 wykonane i zweryfikowane lokalnie. Pełne A42 zamknięte.**
Pakiety 53–57 obejmują A42.17–A42.21. Nie wykonano wdrożenia na dev/prod.

Dokument obejmuje pozostały zakres pierwotnego A42. Pakiety A42.1–A42.16
pozostają częścią wdrożenia. Zamknięcie dotyczy wspólnej infrastruktury,
jednego źródła obsługi sesji i podziału funkcji na moduły. Nie oznacza zamknięcia
całego audytu ani wymogu usunięcia każdej podobnej linijki i danych domenowych.
Liczba linii jest informacją pomocniczą, a nie kryterium odbioru.

## A42.17 — jedna obsługa sesji klienta

`frontend/customer-session.js` obsługuje odświeżenie cookie dla sklepu,
produktu i szczegółów zamówienia. Wspólne są: opcje HTTP, walidacja odpowiedzi,
blokada `minitech-refresh-session` między kartami, współdzielenie trwającego
żądania, wykluczenia publicznych endpointów i ograniczone ponowienia startowe.
Przekierowania są odrzucane. Token nie jest zapisywany do storage.

Wylogowanie, wygaśnięcie lub jawne logowanie unieważniają wcześniejszą próbę;
późny odczyt odpowiedzi nie przywraca starej sesji. Stare finally nie usuwa
nowszej obietnicy. Po oczekiwaniu na Web Lock ponownie sprawdzana jest świeżość.
Lokalne adaptery zachowują różne komunikaty i cleanup UI, koszyka oraz PDF.
Publiczny refresh/login/logout nie uruchamia rekurencyjnego odnowienia.

Odbiór: 12 nowych testów modułu oraz istniejące scenariusze logowania,
odświeżania 401, uprawnień, MFA, PDF i wylogowania w czterech profilach sklepu.

## A42.18 — moduły sklepu

`frontend/app.js` wiąże zdarzenia i uruchamia aplikację. Implementacje mają
osobne pliki:

| Odpowiedzialność | Moduły |
| --- | --- |
| Stan aplikacji i uchwyty DOM | `store-state.js`, `store-dom.js` |
| Transport i kompozycja sesji/PDF | `store-services.js` |
| Katalog i prezentacja ofert | `store-catalog.js`, `store-highlights.js` |
| Koszyk i preferencje porównania | `store-cart.js`, `store-comparison.js` |
| Dostawa, kupony i dane faktury | `store-delivery.js` |
| Zakup, ponowienia i powrót płatności | `store-checkout.js`, `checkout-intent.js` |
| Konto, dane klienta i MFA/passkey | `store-account.js`, `store-account-data.js`, `store-security.js` |
| Teksty, formatowanie i dialogi | `store-messages.js`, `i18n.js`, `store-format.js`, `store-feedback.js`, `dialogs.js` |

Importy są jawne i korzystają ze wspólnego stanu ES. Moduły funkcji nie
uruchamiają kolejnej kopii aplikacji. Wzajemne wywołania UI korzystają z żywych
wiązań funkcji ES; uruchomienie i podpięcie zdarzeń odbywa się w punkcie wejścia.
Transport, stan i DOM mają własne moduły kompozycji. Nie wprowadzono bundlera
ani zależności produkcyjnych. Wspólne adaptery konfiguracji API, czasu
bezczynności i odczytów katalogu zachowują dotychczasowe interfejsy.

Słowniki są niezależnym modułem danych z 172 zgodnymi kluczami PL/EN.
Tłumaczenia i dialogi mają eksporty ES; dotychczasowe fasady `MiniTechI18n`
i `MiniTechDialogs` wskazują tę samą implementację. HTML ładuje je jako moduły,
a zależności funkcji są importowane bez polegania na kolejności pobierania plików.
Zachowano publiczny eksport `openSuccessModal` wejścia sklepu.

Checkout zachowuje wersję zapisu, fingerprint, klucze idempotencji, capability
i kontrakty płatności. Nie wznowiono odbioru Stripe. Dwa testy czystego modułu
fingerprintu uzupełniają wcześniejsze scenariusze ponowień i powrotu płatności.

Regresja wykryła zamykanie podglądu koszyka po wymianie klikniętego węzła DOM.
Kliknięcie jest teraz klasyfikowane według `composedPath`, a opóźnione zamknięcie
sprawdza aktualny hover/fokus. Scenariusz WebKit powtórzono trzy razy, następnie
wykonano końcową regresję wszystkich profili. Nie zmieniono formatów, ładowania,
cache ani rotacji zdjęć. Porównanie AST potwierdza zachowanie pięciu funkcji
zawierających renderowanie obrazów po normalizacji dostępu do stanu/importu i18n.

## A42.19 — moduły Control Center i terminal

Wejście CC składa kontrolery i inicjalizuje aplikację. `panel-routing.js`
odpowiada za widoki/język, `panel-operations.js` za katalog i formularze operacji,
`panel-data.js` za nawigację danych, a osobne moduły obsługują zamówienia,
faktury, magazyn, klientów, zdarzenia, chaos, rozmowy, Asystenta, zadania,
diagnostykę, zdrowie usług i terminal. `panel-bindings.js` wiąże zdarzenia.
Transport z prywatnym tokenem pozostaje w `panel-client.js`; adaptery usług
korzystają z niego przez `panel-services.js`.

`terminal-session.js` ma niezależnie testowalny cykl sesji: jedną pętlę GET,
blokadę podwójnego utworzenia/wysłania, anulowanie starego GET przy zastąpieniu
sesji i po jej wygaśnięciu. Późny wynik nie zastępuje logu nowej sesji.
Potwierdzenie wejścia usuwa tylko dokładnie ten szkic, który został wysłany.
Nowy szkic zostaje zachowany. Żaden POST nie jest ponawiany automatycznie.
Zakończony terminal blokuje wejście; nieudane zastąpienie zachowuje poprzednią
sesję. Wygasła sesja wyłącza także przycisk utworzenia do przeładowania strony.

Odbiór: osiem nowych testów kontrolera oraz trzy nowe scenariusze przeglądarkowe
w trzech silnikach. Wcześniejsze testy wszystkich wydzielonych funkcji nadal
sprawdzają ich rzeczywiste moduły; nie usunięto asercji autoryzacji ani ponowień.

## A42.20 — moduły serwera CC

`server.py` jest punktem kompozycji, konfiguracji, współdzielonego stanu procesów
i uruchomienia. `cc_http.py` zawiera jeden handler HTTP, politykę odczytu
żądań i routing. Pozostałe moduły: `cc_actions`, `cc_diagnostics`, `cc_transport`,
`cc_secrets`, `cc_health`, `cc_data`, `cc_events`, `cc_chaos`, `cc_runtime`, `cc_ai`.

Funkcje otrzymują jawny kontekst usług. Nie importują zwrotnie serwera i nie
uruchamiają operacji podczas importu. Zachowano 54 cienkie fasady funkcji
serwera oraz klasę `Handler`, aby istniejące wywołania i izolowane fixture
korzystały z tego samego stanu i blokad. Klasa handlera otrzymuje ten kontekst
przez adapter w punkcie kompozycji.

Porównanie AST 54 funkcji i handlera potwierdza zgodność logiki po podstawieniu
kontekstu. Nie zmieniono kolejności Host/Origin/sesji CC ani weryfikacji
operatora przed dostępem do danych. Nie przeniesiono modelu domenowego usług
Java do wspólnej biblioteki. Dodano siedem testów: granic modułów, mutacji
wykrywających kopie oraz niezależnego użycia danych, walidacji transportu i AI
offline z małym kontekstem fixture. Testy nie potrzebują prawdziwych sekretów.

## A42.21 — kontrola i odbiór całego A42

`scripts/qa/check-a42-modules.cjs` linkuje pięć rzeczywistych grafów ES bez
wykonywania kodu DOM/HTTP. Odrzuca brakujące moduły/eksporty, sprzeczne wersje
tego samego pliku stanu, kopię polityki odświeżania i przenoszenie funkcji z
powrotem do wejść. Testy mutantów potwierdzają, że kontrola wykrywa te regresje.
Eksperymentalna flaga VM Modules dotyczy wyłącznie narzędzia QA w Node;
aplikacja korzysta z natywnych modułów przeglądarki.

`scripts/qa/module-layout-check.py` kontroluje cienkie fasady, brak zwrotnych
importów i efektów importu oraz pojedynczą implementację HTTP. Obie kontrole
działają w CI, razem z wcześniejszymi kontrolami parenta, korelacji i odporności.

Końcowy odbiór — **PASS**:

- **198 testów JS**, w tym 29 nowych: sesja (12), terminal (8),
  fingerprint (2), słowniki (2) i granice modułów z mutantami (5).
- **152 testy Python w WSL**, w tym siedem nowych testów granic i niezależnych
  modułów. Obejmują rzeczywiste lokalne HTTP/PTY i własne dane SOPS fixture.
- **380 testów sklepu**: po 95 w Chromium, Firefox, WebKit i mobilnym Chrome.
  Dodatkowo trzy kolejne próby poprawionego scenariusza koszyka w WebKit.
- **93 testy CC**: 47 Chromium oraz po 23 wybrane Firefox i WebKit.
  Trzy nowe scenariusze terminala wykonano w każdym z trzech silników.
- **Pięć grafów ES, 58 linkowanych modułów**, bez wykonywania kodu podczas
  kontroli. Moduły Python mają 54 cienkie fasady i jedną implementację HTTP.
- Parent **17 modułów Maven**, **12 adapterów korelacji**, kontrola **15 JAR-ów**
  korelacji i odporności — PASS. Potwierdzono cztery aplikacje z odpornością
  oraz 11 bez dodatkowych bibliotek runtime. Nie powtarzano testów Java ani
  przebudowy, ponieważ ich źródła i konfiguracja nie zmieniły się w tej turze.
- Składnia, granice modułów oraz `git diff --check` — PASS.

Raport: `tmp/audit-a42-complete/report.json`. Dowody ekstrakcji:
`tmp/audit-a42-complete/server-extraction-proof.json` i
`tmp/audit-a42-complete/image-behavior-proof.json`. Logi:
`tmp/a42-complete-node.log`, `tmp/a42-complete-python-final.log`,
`tmp/a42-complete-store-final.log`, `tmp/a42-complete-cc-chromium-final.log`,
`tmp/a42-complete-cc-firefox.log`, `tmp/a42-complete-cc-webkit.log`.
Raport zawiera hashe źródeł i dowodów. Wstępne próby są zachowane osobno;
za wynik odbioru uznaje się końcowe przebiegi po poprawce koszyka i aktualizacji
asercji wygasłej sesji do celowo wyłączonego przycisku utworzenia terminala.

Polecenia odbioru:

```text
node scripts/qa/check-a42-modules.cjs
python3 scripts/qa/module-layout-check.py
node --test scripts/qa/unit/*.test.cjs
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
pnpm exec playwright test --grep-invert '@full-system'
pnpm run test:cc
python3 scripts/qa/maven-layout-check.py
python3 scripts/qa/correlation-layout-check.py --jars
python3 scripts/qa/resilience-layout-check.py --jars
```

CC ma też wybrane scenariusze Firefox/WebKit, w tym nowy tag
`@terminal-session`. CI zostało zaktualizowane; zdalnego pipeline nie uruchamiano.

Przy przyszłym wdrożeniu należy dostarczyć cały zestaw nowych JS/Python i
zmienione HTML razem. Nowe wersje URL są spójne; statyczny serwer i obrazy
kopiujące katalog frontend obsługują natywne moduły. Nie wystarczy dostarczyć
samego `app.js` lub `server.py`. Lokalne testy używają fixture, osobnych portów
i własnych katalogów. Nie budowano nowych obrazów ani nie wdrażano na dev/prod.

A05 nadal wymaga ręcznych `STRIPE_SECRET_KEY` i `STRIPE_WEBHOOK_SECRET` w
`dev.env.sops` i `test.env.sops` oraz zewnętrznego odbioru. Całe A41 i fragmenty
A27–A31/A40 dotyczące samych zdjęć pozostają odłożone na osobne polecenie.
