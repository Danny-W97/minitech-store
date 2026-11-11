# A43.40 — D02: mapowanie i kwalifikacja retencji danych handlowych

**Aktualny punkt wznowienia (01.11.2026): [pakiet 124](A43_GLOBALNE_DOWODY_KOLEKCJA_124.md),
rewizja `a43.40-2026-11-01.2`.** Poniżej historia budowy kwalifikatora.
Globalne dodatnie dowody i wykonanie D02 pozostają niekompletne;
historyczny skrót planu lub podpis wpisu nie zastępuje brakującej zgody.

Stan na **31.10.2026**, branch `Audit_0.1`, rewizja macierzy
`a43.40-2026-10-31.15`. Zakres: jawne mapowanie pól, kwalifikator w pamięci,
magazyn dowodów, ograniczony wykonawca oceny, trwałe outboxy wszystkich siedmiu
źródeł, trwały koordynator komend bindingu oraz bramka kontroli źródeł.
**Transport HTTP i publikatory są podłączone w kodzie, domyślnie wyłączone.
Lokalny adapter dowodu Order oraz obserwacja Inventory są gotowe bez podłączenia
kolektorów do automatycznego runtime. Odbiór Inventory w Order oraz chroniony
HTTP są wdrożone za domyślnie wyłączoną flagą.
Sześć pozostałych adapterów i ich odbiór również wdrożono za domyślnie
wyłączoną flagą. Dodano rejestr znanych blokad zakupów i ich świeży odczyt
w ocenie; brak pełnego inwentarza globalnych blokad i workera danych biznesowych.** Migracje sprawdzono wyłącznie
w jednorazowych bazach testowych; nie zastosowano ich w dev/test/prod.
A43.40/A43.41 pozostają otwarte; obowiązuje D17 bez nowej aktywacji.

## Reguła D02/P02

- Wszystkie zakończone i uzgodnione zakupy, także klientów z aktywnym kontem.
- Redakcja pozostałych danych osobowych i treści po **dokładnie 365 dniach**
  od końcowego uzgodnienia całego powiązanego łańcucha.
- Zachowane minimum przez **90 dni od faktycznej, potwierdzonej redakcji**;
  opóźnienie redakcji przesuwa ten zegar. Restart/ponowne odczytanie dowodu
  nie może zmieniać utrwalonej daty wykonania.
- Otwarte/wznowione sprawy, spory, wymagane zachowanie i zależności D01,
  odtworzenia, ponowień i deduplikacji blokują usuwanie minimum. Brak dowodu
  zwolnienia zależności blokuje operację; niejasne przypadki do ręcznej oceny.
- Źródłowe dokumenty i rejestry D01, znaczniki protokołów, deduplikacja
  i niezależny rejestr usunięć zachowują odrębne zasady; nie otrzymują 90 dni.
- Wcześniej uzgodnione usuwanie na wniosek właściciela pozostaje niezależne.

## Mapa pól

Kontrakt: [commerce-retention.json](../scripts/qa/contracts/commerce-retention.json).
Obejmuje **10 tabel / 127 pól** z pięciu usług. Każde pole występuje dokładnie
w jednej kategorii. Zmiana DDL lub kategorii wymaga przeglądu; nowa kolumna
nie dziedziczy automatycznie zgody na usunięcie.

| Kategoria | Liczba pól | Znaczenie |
|---|---:|---|
| `redact` | 20 | Zakres planowanej redakcji danych osobowych i treści po 365 dniach, w tym trzy pola dostawy zatwierdzone w D02-F01. Nie jest listą działających poleceń UPDATE. |
| `minimum` | 91 | ID, kwoty, ilości, statusy, powiązania i metadane procesu. Warunkowy próg 90 dni od redakcji; przyszły wykonawca musi sprawdzić zależności i FK. |
| `protocol` | 16 | Klucze ponowień, hashe tożsamości i dostępu, referencje, tracking i kody używane przez procesy. Brak automatycznego TTL. |
| `review` | 0 | D02-F01 rozstrzygnięte: trzy pola dostawy przechodzą do redakcji. Nowe lub nieprzejrzane pola nadal blokują walidację. |

| Usługa / tabela | Planowana redakcja |
|---|---|
| Order / `orders` | `customer_email`, `delivery_first_name`, `delivery_last_name`, `delivery_street`, `delivery_postal_code`, `delivery_city`, `delivery_country`, `delivery_phone`, `delivery_date`, `delivery_slot`, `pickup_location` |
| Order / `order_items` | `product_name` |
| Delivery / `shipments` | `sync_error` |
| Inventory / obie tabele rezerwacji | Brak swobodnej treści do automatycznej redakcji w obecnym modelu; minimum i zależności pozostają. |
| Promotion / rezerwacje i wykorzystania kuponów | Kod w kategorii protokołu, pozostałe pola jako minimum; nie zwalniać wykorzystanych limitów. |
| Returns / `return_requests` | `customer_email`, `reason`, `description`, `moderation_note` |
| Returns / `return_events` | `actor_ref`, `note` |
| Returns / `return_lines` | `product_name` |

**Pięć tabel D01 wyłączonych z tej mapy:** Billing `invoices`, `invoice_items`,
`invoice_corrections` oraz Payment `payments`, `payment_refunds`. Zachowanie
dokumentów źródłowych nie oznacza dowodu zwolnienia ich zależności do minimum D02.

Istniejące triggery A43.35–36 dopuszczają tylko wcześniejszy zakres redakcji
w protokole usuwania konta. Nie wolno ich omijać, usuwać ani traktować jako
gotowej implementacji D02 dla aktywnych klientów. M.in. nazwy produktów
i ograniczenia NOT NULL wymagają osobnych migracji oraz testów.

**D02-F01 — odpowiedź użytkownika:** usuwać `delivery_date`, `delivery_slot`
i `pickup_location` podczas redakcji po 365 dniach, zachowując ID, kwoty,
ilości, statusy i wymagane powiązania. Decyzję dodano do maszynowego zapisu
`a43-decisions-2026-10-30.2`, z nowym odciskiem w walidatorze. Nie zmieniono
okresu 90 dni minimum ani warunków zachowania źródłowych dokumentów D01.

## Stany i dowody

Istniejące warunki w źródłach Order/Inventory/Promotion/Delivery/Returns
zapisano w `stateReview` kontraktu i związano hashami. To przesłanki
konieczne, a nie dowód końcowego uzgodnienia ani początek okresu.

- Order: znana tożsamość checkoutu, brak oznaczenia ręcznej oceny, jedno
  zakończone/nieudane zadanie sagi, brak dzierżawy oraz zgodne skutki płatności,
  zwrotu, kuponu, przesyłki i pobrania.
- Inventory: rezerwacja zgodna z zamówieniem, CONFIRMED dla zakupu zakończonego
  lub RELEASED dla anulowanego. RESERVED i stan nieznany blokują.
- Promotion: odpowiednio CONSUMED/RELEASED oraz zgodna liczba i kod wykorzystań;
  brak kuponu musi być jawnie potwierdzony, nie wywnioskowany z braku odpowiedzi.
- Delivery: końcowy stan zgodny z Order, SYNCED i brak tokenu/dzierżawy.
- Returns: zweryfikowane REJECTED albo jawny dowód braku zgłoszeń.
  COMPLETED bez uzgodnionych skutków domenowych nadal wymaga oceny; nie
  zamykamy w ten sposób brakującego zakresu A16.

Dotychczasowe `finance_privacy_jobs/receipts` są związane z żądaniem usunięcia
konta i nie zawierają całego nowego modelu zegara/pokoleń D02. Nie podłączono
ich jako dowodów dla aktywnych kont. `updated_at`, wiek zamówienia lub sam
status COMPLETED nie zastępują nowego trwałego dowodu końca całego procesu.

## Kwalifikator

[CommerceRetentionAssessment.java](../platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionAssessment.java)
jest klasą bez dostępu do SQL, HTTP, Redis, konfiguracji i harmonogramu.
Ocenia wyłącznie przekazany, ograniczony i niezmienny zestaw dowodów.

- Wymaga jawnego potwierdzenia ośmiu uczestników: Order, Returns, Payment,
  Inventory, Promotion, Delivery, Billing i Notification. Nieobecny zakres
  również musi mieć potwierdzenie usługi. Brak odpowiedzi nie oznacza braku danych.
- Dowody muszą dotyczyć tego samego zamówienia, klienta, wersji Order,
  generacji całego łańcucha, fazy oraz rewizji polityki. Generacja musi się
  zmienić po wznowieniu lub zmianie dowolnego powiązanego procesu.
- Zakup gościnny też podlega ocenie. W modelu w pamięci `customerId=0`
  oznacza brak konta; nadal wymagane są konkretne orderId, wersja i generacja.
  To nie wspólna tożsamość gości ani fikcyjny rekord klienta w SQL. Nie wiązać
  zakupów po e-mailu, nie zmieniać customer_id w bazie i nie używać dowodów
  jednego zamówienia gościnnego do innego.
- Zegar redakcji biegnie od najpóźniejszego potwierdzonego uzgodnienia;
  zegar minimum od najpóźniejszego potwierdzonego zakończenia redakcji
  właściwego zakresu. Potwierdzenie Billing/Payment nie oznacza usunięcia D01;
  wymagane jest potwierdzenie wykonania lub braku zakresu D02.
- Osobne dowody potwierdzają przegląd pól i polityki zachowania, brak blokad,
  spraw i retry/DLQ oraz aktualny niezależny rejestr. Faza minimum wymaga
  dodatkowo zwolnienia zależności finansowych, restore, retry i deduplikacji.
- Niepełne, sprzeczne, zduplikowane, przyszłe lub nieaktualne dowody blokują
  kwalifikację. Obliczenia mają ochronę przepełnienia i używają stałych dób.
- Wynik `DUE_FOR_REVIEW` oznacza osiągnięcie warunków oceny w dostarczonym
  modelu. `activationAllowed()` zawsze false.

**Granica zaufania:** klasa nie uwierzytelnia wystawcy i nie sprawdza podpisów.
Przyszła integracja musi pozyskiwać dowody z uprawnionych trwałych źródeł,
wiązać generację z każdym powiązanym zapisem i ponownie sprawdzać je w chwili
wykonania pod właściwymi blokadami. Nie wolno mapować żądania klienta lub
ręcznie podanego JSON bezpośrednio na pozytywne dowody.

## Trwałe zadania i dowody — rewizja `.4`

[CommerceRetentionJobs.java](../order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionJobs.java)
i migracja V27 przygotowują w Order dwie tabele, **26 nowych pól metadanych**:

- `commerce_retention_jobs`: ID zamówienia, opcjonalne ID klienta, wersja,
  niezmienna generacja i faza, polityka, numer rewizji dowodów, bezpieczny
  stan/kod oceny, zegary, limit prób i dzierżawa.
- `commerce_retention_evidence`: potwierdzenie uczestnika lub blokady,
  związane FK z generacją i fazą. Osiem nazw uczestników i dziesięć nazw
  warunków są zamkniętymi zbiorami. Brak dowolnego payloadu, kontaktu i treści.

Przygotowanie zadania odczytuje tylko ID klienta/wersję z Order, bez łączenia
po e-mailu. Brak konta zapisuje jako SQL NULL, zachowując w modelu w pamięci
umowną wartość 0. Samo przygotowanie niczego nie uzgadnia i nie tworzy
pozytywnych dowodów. Nie zmienia fazy istniejącego zadania ani limitu prób.

Dokładne ponowienie dowodu jest idempotentne i nie przesuwa zegara, rewizji
ani dzierżawy. Sprzeczny dowód unieważnia generację, zachowując stare wiersze;
nie nadpisuje poprzedniej blokady lub daty. Nowy dowód podnosi rewizję
i odwołuje stare przejęcie zadania. Stare odpowiedzi/unieważnienia nie mogą
zmienić nowej generacji.

Jedno `claim()` przejmuje jeden rekord przez SKIP LOCKED i losowy token na
15 sekund; odzyskiwanie ostatnich wygasłych prób jest ograniczone do 25 rekordów.
`assess()` w krótkiej transakcji blokuje Order i zadanie, sprawdza bieżącą
tożsamość/wersję/politykę/fazę/rewizję oraz czas dzierżawy, odczytuje maksymalnie
18 dowodów i zapisuje **wyłącznie wynik oceny**. Zapis wyniku ponownie sprawdza
token, rewizję i rzeczywisty `clock_timestamp()`. Czekanie na dowody/wiek nie
zużywa limitu błędów; osiem awarii/przerwanych przejęć zatrzymuje automatyczne
ponawianie. Zwykły restart ani `prepare()` nie resetuje tego limitu.

Zmiany Order, pozycji i sagi unieważniają bieżące generacje przez triggery,
również gdy aplikacja nie zwiększyła wersji zamówienia. Przeniesienie dziecka
unieważnia oba zamówienia. Zmiana samego updated_at nie rozpoczyna retencji
od nowa. Zmiany w pozostałych siedmiu usługach wymagają jeszcze własnych
wersjonowanych źródeł i mechanizmu unieważniania; same triggery Order nie
dowodzą spójności całego rozproszonego procesu.

Klasa nie jest beanem Spring, kontrolerem ani zadaniem z harmonogramu.
Nie utworzono API pozwalającego klientowi lub operatorowi dostarczać pozytywne
oświadczenia. Publiczne metody Java są granicą dla przyszłych zaufanych adapterów,
nie implementacją ich autoryzacji lub weryfikacji dowodów. Nie ma wykonawcy
UPDATE/DELETE danych biznesowych. Wynik DUE_FOR_REVIEW nadal nie jest zgodą
na czyszczenie.

Stare generacje i dowody pozostają pseudonimowymi metadanymi bez automatycznego
TTL; przyszłe wycofanie wymaga odrębnej oceny zależności. Przy restore przed
jakimkolwiek wznowieniem trzeba unieważnić odtworzone pozytywne potwierdzenia
i uzyskać aktualne dowody, w tym aktualność niezależnego rejestru. Integracja
z pozostałymi usługami oraz wdrożeniowe potwierdzenie kwarantanny i świeżych
dowodów pozostają blokadą aktywacji. Poniższa rewizja dodaje unieważnianie
w istniejącym izolowanym narzędziu restore, bez dopuszczania środowiska do ruchu.

## Unieważnianie przez źródła i po restore — rewizja 31.10.2026 `.1`

Przygotowano kanał `POST /internal/privacy/commerce-retention/invalidate`.
Jest nieobecny przy domyślnym ustawieniu
`privacy.commerce-retention-invalidation.enabled=false` (również przy braku
właściwości). Nie dodano włączenia w konfiguracji dev/test/prod.

Po przyszłym osobnym włączeniu wymaga tożsamości SERVICE z istniejącego
filtra i jednej z siedmiu usług: Returns, Payment, Inventory, Promotion,
Delivery, Billing, Notification. Oświadczenia o aktorze w nagłówkach lub body
nie nadają praw. Klient, operator, inne usługi i SERVICE bez nazwy nie mogą
wywołać operacji.

Komenda zawiera tylko orderId, generację, wersję Order i fazę. Store sprawdza
ją pod blokadą względem bieżącego zamówienia i generacji. Stare/powtórzone
wywołanie ani nieistniejące zamówienie nie zmieniają nowego zadania. Odpowiedź
202 jest zawsze pusta i no-store/private, bez ujawnienia istnienia rekordu.
Operacja jedynie unieważnia: nie przyjmuje pozytywnych dowodów, nie zwalnia
blokad i nie redaguje danych.

`CommerceRetentionInvalidationClient` wysyła komendę do skonfigurowanego
origin Order z istniejącym uwierzytelnieniem usługi i limitami transportu.
Nie przekazuje customer_id, kontaktu ani tokenów z żądania klienta. Origin
nie może zawierać poświadczeń, ścieżki, query ani fragmentu; przekierowania
nie są śledzone. Przyjmowana jest tylko pusta odpowiedź 202. Błędy są
bezpiecznymi kodami, bez treści odpowiedzi i sekretów.

**Stan poprzedniej rewizji `.1`:** klient transportowy nie był trwałym outboxem.
Żadna z siedmiu usług nie zaczęła automatycznie publikować zawiadomień.
Trzeba atomowo powiązać zmiany biznesowe ze zdarzeniem, utrwalić binding
zamówienia/generacji i ponawiać bez utraty informacji. Dopiero to może
uzasadniać globalną aktualność generacji. Zbieranie pozytywnych dowodów
uzgodnienia i blokad nadal wymaga osobnych adapterów.

`privacy-restore.py.replay()` przed sesjami i odtwarzaniem wniosków wywołuje
`invalidate_commerce_assessments()`, niezależnie od liczby wpisów dziennika.
Dotyczy wszystkich bieżących generacji w odtworzonej bazie Order: usuniętych
kont, innych kont i gości. Helper akceptuje wyłącznie zasoby należące do
izolowanego drillu Restored. W partiach po 100 oznacza zadania jako STALE,
odwołuje dzierżawy i zachowuje dowody, historię oraz dane zamówień.

Oczekiwanie na blokadę ma limit 2 sekund, instrukcja 5 sekund, a liczba partii
jest ograniczona. Nie pomija zablokowanych wierszy i po zakończeniu sprawdza
brak aktywnych zadań/dzierżaw. Limit, błąd, częściowy schemat lub brak
potwierdzenia wyniku pozostawia kwarantannę. Historyczny brak obu tabel jest
jawnie raportowany; nie dowodzi gotowości D02. `releaseAllowed` pozostaje false.

Nie dodano automatycznego usuwania starych metadanych ani nie uznano starego
pozytywnego potwierdzenia za aktualny dowód rejestru lub procesu po restore.

Odbiór tego zakresu: 43 testy Java (23 PostgreSQL, 12 kwalifikatora,
4 transportu i 4 autoryzacji/warunku włączenia) oraz 62 Python
(17 retencji, 37 klasyfikacji, 8 restore). Pełny drill szyfrowanego backupu
14 baz i Redis potwierdził unieważnienie wszystkich trzech generacji kont/gościa,
także przy pustym dzienniku, zachowanie dowodów i danych zamówień,
unieważnienie sesji, zachowanie trwałego MFA drugiego konta oraz biznesowych
streamów/grup/PEL/deduplikacji według wcześniejszej polityki.
Dowody: `tmp/audit-a43-40-invalidation-restore/report.json`,
`tmp/privacy-recovery-ecafcf45/report.json` i `tmp/a43-d02-invalidation-tests.log`.

## Trwałe zawiadomienia Returns — rewizja 31.10.2026 `.2`

Migracja Returns V6 dodaje dwie tabele, łącznie 25 pól:
`commerce_retention_source_state` i `commerce_retention_change_outbox`.
Są to pseudonimowe metadane powiązania zamówienia z generacją oceny,
rewizji źródła, blokady po restore oraz wysyłki. Nie zawierają e-maila,
customer_id, treści zgłoszenia ani pozytywnego dowodu uzgodnienia.
Nie mają automatycznego TTL i pozostają poza automatycznym eksportem
surowego stanu; kontekst osobowy podlega uprawnionej ocenie ręcznej.
Pełny rejestr obejmuje **89 tabel/800 pól**, nadal 47 mechanizmów.

Triggery na `return_requests`, `return_lines` i `return_events` zapisują
unieważnienie w tej samej transakcji co INSERT/UPDATE/DELETE. Rollback
wycofuje oba skutki. Zmiana samego updated_at jest pomijana. Przeniesienie
rekordu unieważnia stare i nowe zamówienie; blokady dwóch zamówień są
pobierane w ustalonej kolejności. Bez istniejącego bindingu nie powstaje
domniemana generacja ani pozytywny dowód.

Wspólna klasa `CommerceRetentionSourceOutbox` nie jest beanem,
harmonogramem ani endpointem. `bind()` przyjmuje generację/wersję/fazę
i oczekiwaną lokalną epokę. Spóźniona komenda z dawną epoką albo niższą
wersją Order jest odrzucana. Powtórzenie tej samej generacji nie usuwa
unieważnienia. Nowa generacja wymaga właściwej epoki, a jej utworzenie
**nie jest dowodem kwalifikacji danych**. Przyszły koordynator musi trwale
zapisać niezmienną komendę i jej oczekiwaną epokę: nie wolno pobrać nowszej
epoki, aby wymusić wykonanie starego żądania.

`publishOne()` przejmuje najwyżej jeden rekord przez SKIP LOCKED,
utrwala losową dzierżawę na 15 sekund i zamyka transakcję przed HTTP.
Wysyła istniejącym uwierzytelnionym klientem wyłącznie zakres Order.
Limit to osiem prób, odstęp po błędzie 30 sekund; restart nie zeruje
budżetu. Wygasła ósma dzierżawa przechodzi do FAILED w ograniczonej partii.
ACK wymaga zgodności epoki, generacji, wersji, fazy, rewizji źródła,
tokenu i żywej dzierżawy. Kolejna zmiana cofa niepotwierdzoną wysyłkę
do PENDING; stary ACK nie może jej zamknąć. SENT nie jest ponawiany dla
już unieważnionej generacji, a potwierdzenie transportu nigdy nie czyści
flagi unieważnienia ani blokad zachowania. Historia starszych epok pozostaje.

Izolowany replay wywołuje dodatkowo `invalidate_commerce_sources()` przed
sesjami i wnioskami. Blokuje wszystkie bindingi Returns, także już brudne,
zwiększa epokę i rewizję raz, a odtworzone dzierżawy usuwa bez zerowania prób.
SENT i stare zawiadomienia zachowuje. Trwałe `restore_blocked` uniemożliwia
ponowny binding starym lub nowym poleceniem; w tym etapie brak API zwolnienia
tej blokady. Partie po 100, limity 2/5 sekund, częściowy schemat, błędny wynik
i limit partii utrzymują kwarantannę. Powtórny replay jest idempotentny;
pusty dziennik również uruchamia oba unieważnienia.

**Granica wykonania:** przygotowano źródłowy zapis i nadawcę jako lokalny
pilot Returns. Nie podłączono koordynatora rejestracji ani automatycznego
publikatora. Pozostałe sześć usług wymaga własnych triggerów/adaptacji
oraz testów. Aktualność dowodów, zaufane źródła uzgodnienia i blokad,
dopuszczenie środowiska po restore oraz workery redakcji/usuwania minimum
nadal nie są gotowe. Żadnej migracji nie zastosowano w dev/test/prod.
Nie włączono czyszczenia ani endpointu z rewizji `.1`.

Odbiór `.2`: **60 testów Java PASS**, w tym 12 nowych PostgreSQL źródła,
13 metadanych Order, 15 regresji czyszczenia Order/Returns oraz 20 testów
kwalifikatora/transportu/uprawnień; **66 Python PASS** (17 retencji,
37 klasyfikacji, 12 restore). **Pełny drill 14 baz i Redis PASS**:
czysty i wcześniej brudny binding są zablokowane po restore, ósma dzierżawa
przechodzi do FAILED bez resetu prób, zawiadomienia pozostają, powtórny
i pusty replay działają zgodnie z ograniczeniami. Biznesowe streamy, grupy,
PEL/deduplikacja i trwałe MFA drugiego konta zostały zachowane.

Dowody lokalnego odbioru: `tmp/audit-a43-40-source-outbox/report.json`,
`tmp/a43-d02-source-tests.log`, `tmp/a43-d02-source-restore.log`
oraz `tmp/a43-d02-source-retest.log`, raporty Surefire/Failsafe
i `tmp/privacy-recovery-68a7ed34/report.json`.
Gotowość całego A43 pozostaje NOT_READY / kod 2.

## Inventory, Promotion i Delivery — rewizja 31.10.2026 `.3`

Kolejne trzy źródła korzystają z tego samego protokołu bindingu i outboxa
co Returns. Część migracji tworząca metadane i kolejkująca unieważnienia
jest identyczna; bramka porównuje ją niezależnie od odcisków plików.
Nie dodano kolejnego wariantu nadawcy ani nowych okresów retencji.

| Źródło | Migracja | Zmiany objęte atomowym zawiadomieniem |
|---|---|---|
| Inventory | V12 | inventory_reservations oraz inventory_reservation_items; powiązanie dzieci przez reservation_id |
| Promotion | V10 | coupon_reservations oraz promotion_redemptions; zakres po order_id |
| Delivery | V9 | shipments, także statusy, postęp, błędy i dzierżawy synchronizacji |

Zapis obejmuje INSERT/UPDATE/DELETE, a przeniesienie powiązania unieważnia
stare i nowe zamówienie. Zmiana samego updated_at jest pomijana. Błąd lub
rollback transakcji biznesowej wycofuje także zawiadomienie. Bez bindingu
nie powstaje nowa generacja ani domniemane potwierdzenie. Dotychczasowe
blokady zapisów po usunięciu danych nadal działają.

To zakres rekordów powiązanych z zakupem. Globalny stan produktów,
konfiguracja promocji, limity kuponów i katalog metod dostawy nie otrzymują
tych triggerów ani TTL. Ewentualne zależności kwalifikacji od takich danych
muszą uwzględnić przyszłe zaufane adaptery. Outbox nie dowodzi zakończenia
procesu ani rozliczenia jego skutków. Nie uwalnia stanów magazynowych,
limitów wykorzystania kuponów, trackingu lub deduplikacji.

`invalidate_commerce_sources()` odwiedza teraz cztery jawnie dozwolone
źródła: Returns, Inventory, Promotion, Delivery. Wynik zawiera sumy i
osobny status każdego źródła. Częściowy schemat jednego źródła przerywa
cały przebieg przed sesjami i odtwarzaniem wniosków. Brak obu tabel w
historycznym schemacie jest raportowany osobno; nie oznacza dopuszczenia
środowiska. Nierozpoznane źródło jest odrzucane przed SQL. Obowiązują
dotychczasowe partie po 100, blokada restore, zachowanie historii i brak
zerowania prób. Pusty i powtórny replay obejmują wszystkie cztery źródła.

Dodano sześć tabel/75 pól metadanych. Rejestr obejmuje teraz **95 tabel,
875 pól i 168 migracji**, 47 mechanizmów, 6 przestrzeni Redis oraz 20
kategorii kopii. Wszystkie osiem tabel źródłowych pozostaje bez
automatycznego TTL i poza eksportem surowego stanu technicznego.

**Dalej:** outboxy Payment, Billing i Notification, trwały koordynator
bindingów i podłączenie publikatorów. Nadal brak pozytywnych adapterów
uzgodnienia/blokad/rejestru, workerów biznesowych i końcowego odbioru
łańcucha. Migracje wykonano wyłącznie w jednorazowych bazach QA;
nie aktywowano czyszczenia ani komunikacji w dev/test/prod.

Odbiór: **60 testów Java PASS** (18 nowych PostgreSQL, 12 źródła Returns,
14 regresji czyszczenia i 16 kwalifikatora/transportu) oraz **68 Python PASS**
(17 retencji, 37 klasyfikacji, 14 restore). **Pełny drill 14 baz + Redis PASS**:
osiem bindingów i cztery dzierżawy zablokowane/wycofane we wszystkich czterech
źródłach; powtórny i pusty replay zgodne z polityką, historia oraz biznesowe
streamy/grupy/PEL/deduplikacja zachowane. Zachowano trwałe MFA drugiego konta,
unieważniono sesje i zablokowano konflikty tożsamości oraz stary head dziennika.

Raport: `tmp/audit-a43-40-sources-ipd/report.json`; pełny drill
`tmp/privacy-recovery-529c185e/report.json`; logi
`tmp/a43-d02-sources-ipd-tests.log`, `tmp/a43-d02-sources-ipd-restore.log`
i raporty Surefire/Failsafe. A43.40/A43.41 pozostają otwarte.

## Payment, Billing i Notification — rewizja 31.10.2026 `.4`

Migracje Payment V21, Billing V16 i Notification V18 dodają ten sam protokół
bindingu/outboxa, bez zmiany okresów lub rozszerzania zakresu redakcji D02.
Wspólną część SQL porównuje bramka; odciski obejmują teraz wszystkie migracje
ośmiu uczestników łańcucha (Order oraz siedem źródeł).

| Źródło | Zmiany objęte zapisem unieważnienia |
|---|---|
| Payment | payments, payment_refunds, payment_follow_ups, payment_order_gates, payment_outbox_events, stripe_webhook_receipts |
| Billing | invoices, invoice_items, invoice_corrections, invoice_outbox_events, event_inbox, event_identity_aliases |
| Notification | notifications, notification_dead_letters, event_inbox, event_identity_aliases; tylko trwałe powiązania z zamówieniem |

INSERT/UPDATE/DELETE wiąże zawiadomienie atomowo z dozwoloną zmianą źródła.
Zmiana samego updated_at jest pomijana. Dotychczasowe triggery ochrony
prywatności wykonują się przed capture; odrzucona operacja nie zostawia
zawiadomienia. Powiązany inbox pozostaje chroniony przed usunięciem przez
istniejący protokół; nowy trigger nie omija tej ochrony.

Refund jest rozpoznawany przez payment_id → payments.order_id oraz własne
order_id. Analogicznie korekta używa invoice_id → invoices.order_id oraz
własnego order_id. Zmiana z niespójnymi powiązaniami unieważnia wszystkie
odnalezione stare i nowe zamówienia, w ustalonej kolejności bez duplikatów.
To ostrożne unieważnienie, a nie naprawa danych ani potwierdzenie własności
lub uzgodnienia procesu. Pozycje faktury i webhooki używają rodzica; usunięcie
faktury unieważnia jej zakres również przy kaskadowym usuwaniu pozycji.
Webhook bez payment_id nie jest przypisywany przez nazwę zdarzenia.

W Notification source_id oznacza zamówienie wyłącznie dla pięciu typów:
ORDER_CREATED, ORDER_STATUS_CHANGED, PAYMENT_PENDING, PAYMENT_SUCCEEDED,
PAYMENT_FAILED. Typy konta i czatu nie są łączone z zakupem przez zgodność
numeru. DLQ uwzględnia zarówno zapisany zakres typu zamówieniowego, jak
i zamówieniowy rekord nadrzędnego powiadomienia. Zmiana typu lub rodzica
unieważnia właściwe stare i nowe zakresy.

Billing/Notification używają trwałego order_id inboxa, ustalanego wcześniej
przez istniejące guardy. Powiązania identyfikatorów zdarzeń są rozpoznawane
wyłącznie przez istniejące rekordy event_inbox po alias_id/canonical_id.
Nie wprowadzono nowego parsowania kluczy zdarzeń ani dopasowania po e-mailu.
Brak jednoznacznego trwałego powiązania nie tworzy bindingu ani pozytywnego
dowodu. Takie historyczne kopie i niejednoznaczności nadal wymagają oceny
oraz zaufanych adapterów; outbox nie dowodzi kompletności usunięcia danych.

Źródłowe faktury, korekty i płatności pozostają objęte ochroną D01.
Triggery jedynie obserwują zmiany i zapisują metadane unieważnienia.
Nie wprowadzają usuwania dokumentów, zmian kwot, numeracji, zwrotów,
statusów zakupów ani TTL powiązań deduplikacji. Globalna numeracja faktur
nie jest rekordem zamówienia i pozostaje poza zakresem triggerów.

Izolowany restore blokuje teraz wszystkie siedem źródeł, z osobnym wynikiem
każdego. Jedna częściowa lub błędna baza przerywa cały przebieg; brak obu
tabel w historycznym schemacie jest raportowany bez zwolnienia kwarantanny.
Brak zerowania prób, usuwania historii/SENT i samoczynnego zwalniania
restore_blocked. Pusty i ponowny replay obejmują wszystkie siedem źródeł.

Sześć nowych tabel/75 pól daje **101 tabel/950 pól, 171 migracji**,
47 mechanizmów, 6 przestrzeni Redis i 20 kategorii kopii. Czternaście tabel
źródłowych ma osobną klasyfikację, brak automatycznego TTL i wyłączenie
surowego stanu z automatycznego eksportu, z uprawnioną oceną kontekstu osobowego.

**Dalej:** trwały koordynator bindingów i automatycznej publikacji,
zaufane świeże dowody uzgodnienia/blokad/rejestru, workery biznesowe
i końcowy odbiór łańcucha. Siedem źródłowych outboxów nie oznacza gotowej
globalnej bariery ani działającego czyszczenia. Migracje wykonano tylko
w jednorazowych bazach QA, bez aktywacji w dev/test/prod.

Odbiór: **103 testy Java PASS** (27 nowych PostgreSQL źródeł, 12 Returns,
48 regresji inboxów/retencji i 16 kwalifikatora/transportu) oraz **68 Python
PASS** (17 retencji, 37 klasyfikacji, 14 restore). **Pełny szyfrowany drill
14 baz + Redis PASS**: 14 bindingów i 7 dzierżaw objęto blokadą we wszystkich
siedmiu źródłach. Pusty/powtórny replay, konflikty i stary head sprawdzone;
historia, outboxy, biznesowe streamy/grupy/PEL/deduplikacja oraz trwałe MFA
drugiego konta zachowane, sesje unieważnione. releaseAllowed=false.
Pełny drill: `tmp/privacy-recovery-166dc925/report.json`.

Raport: `tmp/audit-a43-40-sources-pbn/report.json`; logi
`tmp/a43-d02-sources-pbn-tests.log`, `tmp/a43-d02-sources-pbn-retest.log`,
`tmp/a43-d02-sources-pbn-restore.log` oraz raporty Surefire/Failsafe.
A43.40/A43.41 pozostają otwarte.

## Trwały koordynator komend — rewizja 31.10.2026 `.5`

Order V28 dodaje `commerce_retention_bindings` — jedną tabelę/19 pól.
`CommerceRetentionBindings` jest zwykłą klasą z ograniczonym wykonawcą,
bez beana, harmonogramu lub endpointu. Koordynuje znane skutki rejestracji
generacji w siedmiu źródłach; nie zbiera pozytywnych dowodów procesu ani
zwolnienia blokad. Rejestr obejmuje **102 tabele/969 pól, 172 migracje**,
47 mechanizmów, 6 przestrzeni Redis i 20 kategorii kopii.

Przygotowanie sprawdza pod blokadą Order zgodność właściciela, wersji,
bieżącej generacji, fazy i polityki. Atomowo zapisuje komplet siedmiu komend.
Powtórzenie nie zeruje prób, dzierżaw ani terminów. Oczekiwana epoka pierwszego
bindingu wynosi 0; kolejne są pobierane wyłącznie z ostatnich utrwalonych
potwierdzeń danego źródła. Indeks pozwala odczytać ostatnie potwierdzenie
bez skanowania całej historii. Nie ma odczytu nowej epoki ze źródła w celu
wymuszenia ponowienia starej komendy.

Nowa generacja czeka na rozliczenie wszystkich wcześniejszych komend tego
zamówienia. Komenda starej, już unieważnionej oceny może zostać dokończona,
aby ustalić jej nieznany skutek w źródle. Nowe komendy nie mogą jej wyprzedzić.
Po otrzymaniu wszystkich potwierdzeń kolejna generacja używa kolejnych epok;
opóźnione wywołanie starej generacji jest odrzucane przez CAS źródła.

| Stan komendy | Znaczenie |
|---|---|
| PENDING | Utrwalone niezmienne parametry, oczekiwanie na próbę |
| LEASED | Jedna losowa dzierżawa na 15 sekund |
| BOUND | Znany skutek bindingu i zapisana epoka/rewizja; nie jest to dowód uzgodnienia lub kwalifikacji danych |
| FAILED | Błąd/nieznany skutek, ponowienie tej samej komendy lub ręczna ocena po wyczerpaniu prób |

Wykonawca pobiera najwyżej jedną komendę przez SKIP LOCKED. Adapter jest
wywoływany po zatwierdzeniu przejęcia, poza transakcją Order; ACK zapisuje
osobna transakcja. Wywołanie publikatora wewnątrz aktywnej transakcji jest
odrzucane przed pobraniem komendy i zużyciem próby. Osiem prób,
odstęp błędu 30 sekund, ograniczona obsługa
25 wygasłych ostatnich dzierżaw. Limity SQL 2 sekundy, transakcji 5 sekund.
Zgubiona odpowiedź nie zmienia komendy i nie zwiększa epoki w źródle.
Wyczerpanie prób zatrzymuje postęp, w tym przygotowanie następnej generacji.

ACK wymaga właściwej generacji, źródła, wersji, fazy, polityki, oczekiwanej
epoki, tokenu i żywej dzierżawy. Przyjmowana epoka musi wynosić expected+1.
Jeżeli źródło potwierdzi binding już oznaczony jako zmieniony, zapis ACK
atomowo unieważnia odpowiadającą ocenę Order. Publikacja zawiadomienia przez
źródło pozostaje osobnym, idempotentnym krokiem i nigdy nie czyści tej flagi.
ACK nie dopisuje evidence ani dat uzgodnienia/redakcji.

Parametry komendy, znany skutek BOUND i historia są chronione triggerem
przed nadpisaniem lub usunięciem. Nie ma automatycznego TTL. Tabela nie
zawiera e-maila, customer_id, treści lub adresów usług; surowe stany/tokeny
pozostają poza automatycznym eksportem, z uprawnioną oceną kontekstu osobowego.

Izolowany restore przed źródłami, sesjami i wnioskami wywołuje dodatkowo
`invalidate_commerce_bindings()`. Obejmuje wszystkie komendy, także BOUND,
przez trwałe restore_blocked. Wycofuje dzierżawy bez resetu prób i zachowuje
oczekiwane epoki oraz potwierdzenia. Dotychczasowa historia blokuje także
nową generację zamówienia po restore. Flagi nie można samoczynnie zwolnić.
Partie po 100 i limity 2/5 sekund; błąd, limit lub aktywne pozostałości
utrzymują kwarantannę. Pusty i ponowny replay obejmują ten krok.

**Granica wykonania:** `Transport` jest interfejsem zaufanego adaptera.
Testy łączą rzeczywiste bazy Order i siedmiu źródeł lokalnym adapterem JDBC.
Uwierzytelniony transport bindingów przez HTTP, źródłowe endpointy oraz
podłączenie harmonogramów/publikatorów nadal wymagają osobnego zakresu.
Nie wystawiono nowego kanału przyjmującego dowody od klienta lub operatora,
nie dodano konfiguracji sekretów i nie aktywowano komunikacji lub czyszczenia.
Przyszły adapter HTTP musi ograniczać czas/rozmiar i weryfikować tożsamość
oraz pełny zakres odpowiedzi. Sam BOUND nie jest globalną barierą aktualności.

Odbiór: **56 testów Java PASS** (13 nowych PostgreSQL na ośmiu osobnych
bazach, 23 regresji PostgreSQL i 20 kwalifikatora/transportu/uprawnień)
oraz **72 Python PASS** (17 retencji, 37 klasyfikacji, 18 restore).
**Pełny szyfrowany drill 14 baz + Redis PASS**: kwarantanna 21 komend,
zachowanie 7 potwierdzeń i budżetów, 14 bindingów/7 dzierżaw źródłowych.
Pusty i powtórny replay, konflikty i stary head sprawdzone; historia,
biznesowe streamy/grupy/PEL/deduplikacja i trwałe MFA drugiego konta
zachowane, sesje unieważnione. releaseAllowed=false.
Pełny drill: `tmp/privacy-recovery-9f55736f/report.json`.

Raport: `tmp/audit-a43-40-binding-coordinator/report.json`; logi
`tmp/a43-d02-binding-coordinator-tests.log`, `tmp/a43-d02-binding-coordinator-retest.log`,
`tmp/a43-d02-binding-coordinator-restore.log`
i `tmp/a43-d02-binding-coordinator-acceptance.log` (końcowy odbiór Java).
Dalsze prace to transport i kontrolowane podłączenie, świeże dowody procesów/blokad/rejestru,
workery danych biznesowych i odbiór łańcucha. A43.40/A43.41 nadal otwarte.

## Uwierzytelniony HTTP i publikatory — rewizja 31.10.2026 `.6`

Dodano `CommerceRetentionBindingClient` i wspólny kontroler źródła,
konfigurację siedmiu usług źródłowych oraz publikator Order. Wszystkie
źródła importują tę samą konfigurację. Nie zmieniono schematu ani okresów:
**102 tabele/969 pól, 172 migracje**, 47 mechanizmów i 20 kategorii kopii.

`POST /internal/privacy/commerce-retention/bind` przyjmuje tylko zweryfikowaną
tożsamość SERVICE `order-service`. `POST .../invalidate` przyjmuje wyłącznie
siedem źródeł. Reguły obowiązują zarówno we wspólnym RoutePolicy/filtrze,
jak i w kontrolerach. Uzupełniono brak wcześniejszej trasy invalidate
w RoutePolicy; sama kontrola w kontrolerze nie wystarczała do przejścia filtra.
Klient, operator, inne usługi, brak nazwy SERVICE i podrobione nagłówki
nie nadają prawa wywołania. Metody poza POST są odrzucane.

Komenda bindingu zawiera wyłącznie orderId, generation, orderVersion, phase
i expectedEpoch. Wymaga wszystkich pól; brak epoki lub wersji nie staje się
domyślnym zerem. Odpowiedź powtarza ten zakres oraz bindingEpoch,
sourceRevision i invalidated. Wynik ma no-store/private. Konflikt i blokada
restore dają bezpieczny błąd; nie zwracają treści SQL, kontaktów lub danych konta.

Klient używa własnego istniejącego PLATFORM_SERVICE_TOKEN i introspekcji
tożsamości usług. Nie przekazuje tokenu klienta. Wysyła do stałej ścieżki
w skonfigurowanym origin http/https bez poświadczeń, ścieżek, query, fragmentu
lub niepoprawnego portu; nie śledzi przekierowań. Korzysta z istniejącego
ograniczonego HTTP 1/3 sekundy i limitu całego żądania. Czyta najwyżej 2049
bajtów, odrzucając odpowiedź powyżej **2048 bajtów**. Weryfikuje dokładnie
osiem pól, typy liczb całkowitych/boolean, zakresy i pełny echo zakresu.
Brakujące/dodatkowe/zdublowane pola, trailing JSON, inny identyfikator/faza,
epoka inna niż expected+1, ujemna rewizja lub overflow są odrzucane.
Błędy klienta są stałymi kodami bez odpowiedzi, origin, sekretu lub przyczyny parsera.

| Flaga | Domyślnie | Zakres po jawnym włączeniu |
|---|---|---|
| privacy.commerce-retention-binding.enabled | false | Order: bean koordynatora i publikator już przygotowanych komend |
| privacy.commerce-retention-source.enabled | false | Źródło: magazyn metadanych i endpoint bind |
| privacy.commerce-retention-source.publisher-enabled | false | Źródło: publikator unieważnień; wymaga również poprzedniej flagi |
| privacy.commerce-retention-invalidation.enabled | false | Order: istniejący endpoint invalidate |

Nie ustawiono tych flag w dev/test/prod. Włączenie publikatora wymaga
niepustego istniejącego tokenu usługi; brak powoduje błąd konfiguracji.
Interwały `...publish-delay-ms` mają domyślnie 1000 ms, zakres 100–60000 ms.
Jeden tick wykonuje najwyżej jedną trwałą komendę; zachowuje limity prób
i dzierżawy. Start ma ten sam odstęp. Błąd ticka zapisuje stały kod w logu.
Wywołanie publikatora źródła wewnątrz aktywnej transakcji jest odrzucane
przed przejęciem rekordu, podobnie jak w koordynatorze Order.

Origin każdego źródła w Order można określić przez
`privacy.commerce-retention-binding.origins.RETURNS` (analogicznie PAYMENT,
INVENTORY, PROMOTION, DELIVERY, BILLING, NOTIFICATION), następnie istniejące
`RETURNS_SERVICE_URL` itd. Domyślne lokalne porty: Returns 18094, Payment 18084,
Inventory 18083, Promotion 18089, Delivery 18088, Billing 18095, Notification
18086. Źródła kierują unieważnienia do istniejącego PLATFORM_ORDER_URL /
ORDER_SERVICE_URL, domyślnie localhost:18082. Kontenery wymagają właściwych
adresów środowiska; parametrów nie odczytywano ani nie zmieniano w runtime.
Nie dodano sekretów do SOPS ani nie generowano nowych kluczy.

Publikator Order wywołuje HTTP przez zachowaną niezmienną komendę, weryfikuje
odpowiedź i dopiero wtedy zapisuje ACK. Nie skanuje kont, nie tworzy nowych
generacji i nie dopisuje dowodów uzgodnienia. Publikator źródła odsyła
unieważnienie przez istniejący kanał i zachowuje idempotencję 202.
Zgubiona odpowiedź po zapisie jest ponawiana z tą samą epoką, bez nadpisywania
nowej generacji. BOUND nadal oznacza tylko znany skutek powiązania.

Źródłowy claim/odzyskiwanie dzierżaw oraz ACK/failure pomijają zamówienie
objęte restore_blocked. Publikator nie podejmuje ani nie zamyka takich
rekordów, nawet przy włączonych flagach. Root ma wcześniejszą równoważną
ochronę komend. Żadna flaga nie usuwa kwarantanny, blokad zachowania,
historii, deduplikacji lub ochrony D01.

Test pełnego obiegu używa rzeczywistego HTTP na losowych portach, introspekcji,
filtra, MVC i osobnych baz Order/siedmiu źródeł: binding, biznesowa zmiana
Returns, trwały outbox, powrót invalidate, nowa generacja i odrzucenie starej.
Osobno sprawdza odpowiedź utraconą po zatwierdzeniu zapisu. Klucze i tożsamości
są wyłącznie syntetyczne; nie łączono się z działającymi usługami.

Raport: `tmp/audit-a43-40-http-publishers/report.json`; logi
`tmp/a43-d02-http-publishers-tests.log`, `tmp/a43-d02-http-publishers-acceptance.log`,
`tmp/a43-d02-http-publishers-restore.log` i raporty Surefire/Failsafe.
Odbiór: **209 wywołań testów Java PASS** (113 uprawnień/HTTP/konfiguracji,
96 PostgreSQL, w tym 10 powtórzeń stabilności) i **72 Python PASS**.
Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-ad4d8cf2/report.json`.
Końcowy obieg HTTP i wszystkie siedem źródeł PASS; dodatkowe powtórzenia
opisuje `tmp/a43-d02-http-publishers-stability.log`. Pierwotną nieudaną
próbę historycznego testu zachowano w logu; brak trwałej reprodukcji.
Pola kontraktu producersWired/coordinatorWired/automaticPublisherWired=true
oznaczają podłączenie w kodzie; defaultEnabled/deploymentActivated=false
oddzielnie chronią brak aktywacji. Gotowość całego A43 nadal NOT_READY.

**Dalej:** zaufane adaptery dowodów uzgodnienia procesów, blokad i rejestru,
kontrolowane przygotowanie nowych generacji, workery danych biznesowych
i odbiór środowiskowy. Endpoint bind i BOUND nie są takimi dowodami.
A43.40/A43.41 i pozostawione decyzje nadal otwarte, bez aktywacji czyszczenia.

## Trwały lokalny dowód Order — rewizja 31.10.2026 `.7`

[CommerceRetentionOrderEvidence.java](../order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionOrderEvidence.java)
czyta wyłącznie własną bazę Order, pod blokadami zamówienia, bieżącej generacji,
znacznika i sagi. Nie jest beanem ani zadaniem cyklicznym, nie ma endpointu
przyjmującego pozytywne dowody. Dotyczy jednego zamówienia, także gościa,
w tej samej wersji, generacji, fazie i polityce. Limity SQL/transakcji: 2/5 sekund.

Migracja Order V29 dodaje `commerce_retention_order_state` — siedem pól,
bez automatycznego TTL. Jawne `prepare` rozpoczyna śledzenie tylko wskazanego
zamówienia; nie skanuje ani nie uzupełnia historycznych zakupów. Triggery
Order/pozycji/sagi zapisują czas bazy dopiero po zaobserwowanym przejściu
z niegotowego do lokalnie zakończonego i zgodnego procesu. Wymagają znanej
tożsamości checkoutu, braku ręcznej oceny i dzierżaw/ponowień, zgodnych kwot
oraz lokalnych stanów sagi, dostawy, pobrania, zwrotu i kuponu. To ocena
zapisów Order; nie potwierdza rzeczywistych stanów siedmiu odrębnych źródeł.

- Historycznie zamknięte zamówienie bez takiego znacznika pozostaje UNKNOWN.
  Nie używamy `created_at`, `updated_at`, daty odczytu ani czasu restartu.
- Restart, ponowny odczyt, nowa generacja po zmianie samej wersji oraz
  zmiany kontaktu/nazw nie przesuwają znacznika. Zmiana procesu/pozycji
  zamkniętego zakupu usuwa lokalne potwierdzenie; ponowne otwarcie zeruje
  zegar. Nowy czas wymaga nowego zaobserwowanego zamknięcia.
- Aktywny proces daje BLOCKED; brak sagi, zgodności lub znacznika nie daje
  CONFIRMED. Wykryte lokalne ponowienie może wyłącznie zablokować globalny
  warunek braku retry/DLQ; jego lokalny brak nie potwierdza globalnego braku.
- Pozytywny dowód dotyczy wyłącznie uczestnika ORDER i fazy REDACTION.
  Początek 365 dni nadal wymaga najpóźniejszego potwierdzenia **wszystkich
  ośmiu** uczestników i wszystkich wymaganych blokad/rejestru. Nie dodano
  potwierdzeń braku sporów, obowiązku zachowania lub zależności D01.
- MINIMUM_REMOVAL pozostaje UNKNOWN bez dowodu faktycznej redakcji. Nie
  rozpoczynamy jego 90 dni od lokalnego zakończenia zamówienia.
- Bezpośrednia zmiana czasu/rewizji, usunięcie znacznika i cofnięcie
  restore_blocked są odrzucane. Role DDL pozostają zaufaną granicą bazy.
- Izolowany replay ustawia jednokierunkową blokadę wszystkich znaczników,
  także przy pustym dzienniku; zachowuje daty, rewizje i historię. Odtworzony
  czas nie może sam stać się świeżym dowodem. Błąd/limit blokuje dalszy replay.

Odbiór: **85 wywołań Java PASS** — 23 jednostkowe i 62 PostgreSQL, w tym
14 nowych przypadków lokalnego dowodu, 13 magazynu oceny, 10 regresji
usuwania danych i 25 koordynatora/HTTP (w tym 10 powtórzeń). **75 Python
PASS** — 17 retencji, 37 klasyfikacji, 21 restore. Pełny szyfrowany drill
14 baz/Redis PASS: trzy znaczniki objęte kwarantanną z zachowaniem dokładnych
dat, pusty/powtórny replay oraz wcześniejsze bariery i biznesowy Redis.

Rejestr: **103 tabele/976 pól, 173 migracje**, 47 mechanizmów, 6 przestrzeni
Redis, 20 kategorii kopii i 26 polityk. Bramki strukturalne/observe PASS;
`--require-ready` nadal NOT_READY / kod 2. Raport:
`tmp/audit-a43-40-order-evidence/report.json`; pełny drill:
`tmp/privacy-recovery-d5552d1e/report.json`. Logi mają prefiks
`tmp/a43-d02-order-evidence-`. Migracje wyłącznie w jednorazowych bazach QA;
brak nowej aktywacji, zmian sekretów i operacji na danych dev/test/prod.

**Dalej:** zaufane dowody pozostałych siedmiu uczestników i globalnych
blokad/rejestru, kontrolowane przygotowanie generacji, migracje redakcji,
workery biznesowe oraz testy współbieżności i odbiór środowiskowy. Nie ma
podstaw do zamknięcia A43.40/A43.41 ani do zwolnienia ochrony D01/P09.

## Lokalna obserwacja Inventory — rewizja 31.10.2026 `.8`

[InventoryRetentionEvidence.java](../inventory-service/src/main/java/org/demo/inventoryservice/privacy/InventoryRetentionEvidence.java)
czyta źródłową rezerwację i jej pozycje pod tą samą blokadą transakcyjną
436, którą stosują istniejące triggery zapisu i bindingu. Zwykły odczyt MVCC
po tej blokadzie unika odwrócenia kolejności blokad wiersza i advisory lock.
Wynik dotyczy chwili odczytu; przyszły wykonawca musi ponownie sprawdzić
aktualność pod blokadami. SQL 2 sekundy, transakcja 5 sekund, najwyżej 101
odczytanych pozycji z odrzuceniem zakresu powyżej limitu 100 aplikacji.

Migracja Inventory V13 dodaje do istniejącej commerce_retention_source_state
**dwa pola**: local_open_seen oraz local_reconciled_at. Binding nowego
zamówienia zaczyna obserwację lokalnego procesu. Istniejące historyczne
bindingi i zamknięte rezerwacje nie otrzymują wywnioskowanej daty. Znacznik
powstaje dopiero po zaobserwowanym przejściu z niegotowego procesu do
CONFIRMED/RELEASED ze zgodnymi, niepustymi pozycjami bez duplikatów produktu.
Triggery pracują w transakcji biznesowej: rollback cofa też czas i outbox.

- Rezerwacja RESERVED jest BLOCKED. Brak rezerwacji, nieznany stan,
  niezgodność ID/stanu/produktów/ilości i brak czasu nie dają potwierdzenia.
  Nie ma automatycznego dowodu braku danych dla zamówienia bez rezerwacji.
- Kolektor wymaga dokładnego orderId, generation, orderVersion, phase,
  bindingEpoch i sourceRevision; dirty/stary binding jest STALE. ACK outboxu
  ani ponowienie tej samej generacji nie zwalnia blokady. Nowy poprawny
  binding nie zmienia zegara i nie dowodzi zakończenia procesu.
- Oczekiwania obejmują ID rezerwacji, końcowy stan i pełną listę maksymalnie
  100 unikalnych produktów/ilości. Przyszły zaufany odbiorca Order musi
  pobrać je ze swojego bieżącego zamówienia pod blokadą, sprawdzić zakres
  odpowiedzi oraz aktualność źródła. Nie wolno przyjmować ich od klienta.
- Wynik to lokalna Observation, **bez ChainProof i customerId**. Nie
  przypisuje konta, nie potwierdza braku globalnych blokad i nie trafia jeszcze
  do magazynu dowodów Order. Nie wdrożono HTTP pozytywnych dowodów, beana
  ani harmonogramu tego kolektora.
- Restart, odczyt, kolejny binding i zmiana samego updated_at nie przesuwają
  daty. Ponowne otwarcie, przeniesienie pozycji/rezerwacji lub zmiana
  zamkniętego procesu unieważnia lokalny czas. Bezpośrednie podanie czasu,
  usuwanie historii oraz cofanie restore_blocked są odrzucane.
- MINIMUM_REMOVAL pozostaje UNKNOWN: czas zakończenia rezerwacji nie jest
  czasem faktycznej redakcji. Zasady 365/90 dni, D01 i wszystkie globalne
  wymagania pozostają bez zmian. Brak automatycznego TTL obu pól.
- Dotychczasowy izolowany replay źródeł obejmuje też rozszerzony rekord:
  zachowuje czas, blokuje binding, zwiększa epokę/rewizję, odwołuje dzierżawy
  bez resetu budżetu. Dotyczy także pustego/powtórnego dziennika. Kolektor
  odrzuca odtworzony znacznik niezależnie od oczekiwanego zakresu.

Odbiór: **39 wywołań Java PASS** — 12 kwalifikatora, 16 nowych PostgreSQL
Inventory, sześć regresji źródła, trzy usuwania danych i dwa pełnego HTTP.
**75 Python PASS** — 17 retencji, 37 klasyfikacji, 21 restore. Pełny szyfrowany
drill **14 baz + Redis PASS**: jeden znacznik Inventory i trzy Order
zachowały dokładne daty w kwarantannie także przy pustym/powtórnym dzienniku;
historia, budżety, biznesowy Redis i trwałe MFA zachowane, sesje unieważnione.
Raport drillu: `tmp/privacy-recovery-cf51b187/report.json`.

Raport pakietu: `tmp/audit-a43-40-inventory-evidence/report.json`;
logi z prefiksem `tmp/a43-d02-inventory-evidence-`. Rejestr obejmuje
**103 tabele/978 pól, 174 migracje**, 47 mechanizmów, 6 przestrzeni Redis,
20 kategorii kopii i 26 polityk. Bramki inwentarza/klasyfikacji/kopii/observe
PASS; końcowa gotowość nadal NOT_READY / kod 2. Migracje wyłącznie w
jednorazowych bazach; brak aktywacji czyszczenia i zmian dev/test/prod.

**Dalej:** zaufany odbiór obserwacji Inventory w Order z kontrolą generacji,
epok i aktualności, dowody pozostałych sześciu źródeł oraz globalnych
blokad/rejestru, przygotowanie generacji i workery biznesowe. D01/P09 i
pozostałe otwarte decyzje nadal blokują pełne A43.40/A43.41.

## Zaufany odbiór Inventory w Order — rewizja 31.10.2026 `.9`

Wdrożono stały `POST /internal/privacy/commerce-retention/inventory-evidence`.
Filtr RoutePolicy i kontroler dopuszczają wyłącznie tożsamość SERVICE
order-service. Klient/operator, obca usługa, brak uwierzytelnienia i podrobione
nagłówki nie nadają dostępu. Kontroler oraz komponenty Order powstają tylko
przy jawnym privacy.commerce-retention-inventory-evidence.enabled=true;
domyślnie false. Wymagany istniejący PLATFORM_SERVICE_TOKEN, bez nowego sekretu.
Order korzysta z INVENTORY_SERVICE_URL, domyślnie localhost:18083.

Żądanie ma dziewięć pól: zakres generacji/wersji/fazy/epoki/rewizji, ID
rezerwacji, końcowy stan i pozycje. Bez customerId, e-maila i twierdzeń klienta.
Kontroler czyta najwyżej 8193 bajty i odrzuca ponad 8192; odpowiedź najwyżej
2048 bajtów. Ścisłe JSON: typy liczb całkowitych, zakresy, brak duplikatów,
nadmiarowych/brakujących pól i trailing JSON. Hasz oczekiwania wiąże ID,
stan i posortowane produkty/ilości. Klient sprawdza pełne echo i zgodność
stanu/kodu/czasu. Nie śledzi przekierowań; błędy nie ujawniają odpowiedzi peer,
SQL, origin lub sekretów. HTTP używa istniejącego budżetu 1/3 sekundy.

CommerceRetentionInventoryCollector pobiera oczekiwania pod blokadą bieżącego
Order, zadania i nieobjętego kwarantanną BOUND. Weryfikuje właściciela, wersję,
generację, fazę i politykę oraz lokalne zakończenie procesu. COMPLETED wymaga
CONFIRMED, CANCELLED — RELEASED; ID/produkty/ilości pochodzą z własnej bazy.
Brak oczekiwanej rezerwacji pozostaje WAITING, bez zgadywania braku danych.

Transakcja kończy się przed HTTP. Po odpowiedzi druga krótka transakcja
ponownie pobiera ten sam zakres i oczekiwania. Zmiana zamówienia/pozycji,
bindingu lub restore odrzuca odpowiedź. STALE/QUARANTINED źródła unieważnia
generację; transport lub zły protokół niczego nie dopisuje. Inne stany źródła
są zachowane, a pozytywny dowód dotyczy wyłącznie INVENTORY tej generacji.
Ponowienie z tym samym czasem jest idempotentne; sprzeczny dowód nie nadpisuje
poprzedniego. Tożsamość konta nadaje tylko ponownie zweryfikowany Order.
MINIMUM_REMOVAL nadal nie otrzymuje wymyślonej daty redakcji.

**Dowód jest migawką z chwili odczytu źródła, nie blokadą rozproszonych
zapisów.** Późniejsza zmiana źródła uruchamia wcześniejszy trwały outbox
unieważnień; doręczenie może być opóźnione. Przyszły worker musi ponownie
sprawdzić aktualność i uzyskać wymagane bariery przed zmianą danych. Ten
kolektor nie uruchamia czyszczenia, skanowania ani przygotowania generacji.

Nie dodano tabel ani migracji: **103 tabele/978 pól, 174 migracje**.
Raport pakietu: `tmp/audit-a43-40-inventory-bridge/report.json`.
Bramki inwentarza/klasyfikacji/kopii/observe PASS; końcowa gotowość nadal
NOT_READY / kod 2. [Stan końcowego odbioru A43](A43_ODBIOR_KONCOWY.md).

## Sześć źródeł i potwierdzenia zdarzeń — rewizja 31.10.2026 `.10`

PeerRetentionEvidence/Protocol/Client oraz CommerceRetentionPeerCollector
obejmują Returns, Payment, Promotion, Delivery, Billing i Notification.
Stały POST /internal/privacy/commerce-retention/peer-evidence dopuszcza
wyłącznie SERVICE order-service w filtrze i kontrolerze. Flaga
privacy.commerce-retention-peer-evidence.enabled domyślnie false; bez
harmonogramu, przygotowania generacji lub operacji na danych biznesowych.
Sześć aplikacji importuje wspólną konfigurację. W Order wymagany istniejący
token i adresy *_SERVICE_URL; brak nowych sekretów.

Komenda zawiera dokładny zakres source/order/generation/version/phase/epoch/
revision i ograniczone fakty z własnej bazy Order. Kwoty są ścisłymi ciągami
dziesiętnymi z dwoma miejscami. Stabilne ID klienta trafia tylko do Returns
oraz Billing do kontroli już istniejącego powiązania; pozostałe źródła
otrzymują zero. Brak e-maili i dopasowania po kontakcie. Limity 8 KiB żądania,
2 KiB odpowiedzi, HTTP 1/3 sekundy, SQL/transakcja 2/5 sekund. Bez przekierowań,
przymusowej konwersji typów, dodatkowych/zdublowanych pól i trailing JSON.

Każde źródło ma dwa pola lokalnego zegara w istniejącym bindingu. Sześć nowych
migracji liczy go po zaobserwowanym lokalnym zakończeniu, bez backfillu.
Odroczony trigger patrzy na końcowy stan transakcji; tylko ostatnia rewizja
źródła może ustawić czas. Dzięki temu zapis nagłówka, pozycji i potwierdzenia
w jednej transakcji nie rozbija zegara. Rollback cofa także metadane.
Nowy binding lub odczyt nie przesuwa daty. Zmiana zamkniętego procesu
konserwatywnie usuwa potwierdzenie; przyszły worker redakcji musi uwzględnić
zachowanie rzeczywistego zegara i osobne potwierdzenie wykonania redakcji.

| Źródło | Warunki i ograniczenia obserwacji |
|---|---|
| Returns | Zweryfikowane, odrzucone zgłoszenia właściwego klienta, pozycje oraz zdarzenie końcowe zgodne z wersją. Aktywne BLOCKED; COMPLETED bez skutków A16 i niejednoznaczna historia UNKNOWN. |
| Promotion | Kod, subtotal i rabat zgodne z Order; CONSUMED z jednym zgodnym wykorzystaniem albo RELEASED bez wykorzystania. RESERVED blokuje. |
| Delivery | Tracking, końcowy stan i rewizja zgodne z Order, SYNCED, brak dzierżawy i ponowienia. |
| Payment | Zgodna kwota PLN, utrwalony wynik i tożsamość polecenia; symulacja/pobranie lub potwierdzona odmowa. Zamknięte follow-upy i opublikowany outbox. Stripe, refundacje i zewnętrzne blokady wymagają oceny — bez operacji u operatora. |
| Billing | Zgodna faktura/pozycje/kwoty i właściciel oraz końcowy status; brak nieuzgodnionych korekt/aktywnego outboxu. Dokładny zestaw przyjętych zdarzeń Payment. Dokumenty D01 pozostają bez zmian. |
| Notification | Właściwe typy zamówienia, SENT, brak retry/dzierżaw oraz nierozliczonych/osieroconych DLQ. Dokładny zestaw przyjętych zdarzeń Payment powiązanych z właściwym powiadomieniem. Konto/czat o tym samym ID nie wchodzi w zakres. |

Payment zwraca hash i liczbę kanonicznych ID zakończonych zdarzeń (maks. 100).
V2 jest normalizowane do kanonicznego ID v1 zgodnie z istniejącymi aliasami;
nie przekazujemy payloadu, event_key, kontaktu ani listy ID. Order wiąże hash
z własną generacją/polityką/epoką/rewizją i niezmiennym dowodem Payment w
nowej commerce_retention_payment_evidence (V30, 10 pól, bez TTL). Dopiero wtedy
pobiera Billing/Notification. Obaj odbiorcy muszą wykazać identyczny zestaw,
a nie tylko podobną liczbę wpisów. Nieznane legacy bez trwałego order_id,
korekty finansowe lub brak wymaganych potwierdzeń pozostają do oceny.

Przed HTTP i po nim Order ponownie sprawdza właściciela, wersję, generację,
politykę, binding i fakty/receipt Payment. Odpowiedź jest wyłącznie migawką
do oceny; nie zastępuje przyszłej bariery rozproszonych zapisów. Nie
potwierdza globalnego braku hold/sporów/retry/DLQ. Brak rekordu lub
historycznego zegara nie jest automatycznym dowodem braku danych.
MINIMUM_REMOVAL nie otrzymuje daty faktycznej redakcji.

Restore zachowuje czasy i hashe, blokuje wszystkie źródła, a stare generacje
Order są nieaktywne. Taki receipt nie może posłużyć do nowego potwierdzenia;
FK i kontrola current wiążą go z archiwalnym zadaniem. Nie ma automatycznego
TTL, zwolnienia kwarantanny ani pełnego A43 complete.

Odbiór: **192 wywołania Java PASS** (99 jednostkowych/93 PostgreSQL),
**75 Python PASS**, kompilacja siedmiu aplikacji i pełny drill 14 baz/Redis
PASS: tmp/privacy-recovery-e0baaad0/report.json. Sześć nowych zegarów i hash
Payment pozostają zachowane, ale zablokowane po restore; stara generacja
nie może potwierdzić nowych danych. Pusty i powtórny replay też sprawdzone.

Rejestr: **104 tabele/1000 pól, 181 migracji**, 47 mechanizmów, 6 przestrzeni
Redis, 20 kopii i 26 polityk. Raport:
tmp/audit-a43-40-six-peer-evidence/report.json; logi tmp/a43-six-peers-*.
Bramki strukturalne/observe PASS; gotowość nadal NOT_READY / kod 2.

## Odbiór `.4`

- **12 nowych testów PostgreSQL PASS**: 27 rzeczywistych migracji, restart
  i zegary, idempotencja/sprzeczne dowody, goście, generacje/fazy/tożsamość,
  zmiany zamówienia/dzieci/sagi, dwa równoległe przejęcia, wygaśnięcie podczas
  oczekiwania na blokadę, budżet ośmiu błędów i ograniczenia tabel.
- **10 istniejących testów PostgreSQL usuwania danych Order PASS** jako
  regresja nowej migracji/triggerów; **12 testów kwalifikatora Java PASS**.
- **54 testy Python PASS**: 17 bramki retencji i 37 klasyfikacji danych.
  Inwentarz i bramki kopii wewnętrznych/zewnętrznych również PASS.
- Rejestr obejmuje teraz **87 tabel/775 pól**, 47 mechanizmów, 6 przestrzeni
  Redis i 20 kategorii kopii. Nowe metadane mają osobną klasyfikację i brak TTL.
- Dowody: `tmp/audit-a43-40-d02-store/report.json`, `observe.json`,
  `readiness.json`, `tmp/a43-d02-store-tests.log` i raporty Failsafe/Surefire.
  Gotowość nadal NOT_READY / kod 2; realne środowiska pozostają nietknięte.

## Odbiór poprzedniego zakresu `.3`

- **159 testów Java modułu platform-security PASS**, w tym 12 nowych testów
  kwalifikatora: granice 365/90 dni, rok przestępny, opóźniona redakcja,
  najpóźniejsze potwierdzenie, wszystkie brakujące/blokujące dowody,
  tożsamość/wersja/generacja/faza, zegary i niezmienność danych wejściowych.
- **17 testów Python bramki PASS**: kompletność DDL, ochrona ID i protokołów,
  wyłączenie D01, zatwierdzony zakres pól dostawy, uczestnicy i brak fałszywych
  deklaracji o gotowym workerze.
- Raporty: `tmp/audit-a43-40-d02-assessment/observe.json`, `readiness.json`
  i `report.json`; kontrola gotowości nadal NOT_READY / kod 2.

Pozostają: trwałe źródła zawiadomień i potwierdzeń/blokad całego łańcucha,
migracje zakresu redakcji, ograniczony worker danych biznesowych,
kontrola równoległych zapisów i starego backlogu, testy PostgreSQL/awarii/restore
oraz pozostałe decyzje i dowody A43.40. Bieżący zakres nie wykonuje czyszczenia,
nie zakłada kont, nie zmienia SOPS ani środowisk i nie wznawia A05.


## Znane blokady zakupów — rewizja 31.10.2026 `.11`

Dodano wewnętrzny `CommerceRetentionHolds` i migrację Order V31. Dwie tabele
`commerce_retention_hold_scopes` oraz `commerce_retention_holds` przechowują
wyłącznie typ zakresu, stabilne ID, rewizję, UUID wniosku/blokady, bezpieczny
kod przyczyny i daty. Dwa pola w zadaniu oceny pamiętają odczytane rewizje.
Rejestr całej aplikacji: **106 tabel/1015 pól i 182 migracje**.

Zakres ORDER dotyczy jednego zakupu, również gościa; CUSTOMER dotyczy zakupów
konkretnego stabilnego ID klienta. Nie oznacza blokowania usunięcia profilu
Customer ani obejmowania logów, backupów lub innych magazynów. Nie wiążemy
kont po adresie e-mail. Brak potwierdzonego zamówienia/zakupów odrzuca polecenie.
Nie ma publicznego API, panelu CC, komponentu Spring ani harmonogramu dla
nowego rejestru. Weryfikacja operatora i uprawnionego obiegu spraw pozostaje
przed przyszłym udostępnieniem poleceń; sama klasa nie nadaje uprawnień.

Można utrwalić znaną przeszkodę: wymagane zachowanie, otwartą/wznowioną
sprawę, aktywne ponowienie/DLQ lub zależność dokumentów, restore, ponowień
i deduplikacji. Cztery ostatnie rodzaje dotyczą fazy usuwania minimum;
nie blokują wcześniejszej redakcji wyłącznie z tytułu tej zależności.
Wpisy są niezmienne, bez swobodnych treści, automatycznego TTL, zwolnienia
lub cofania blokady. UUID wniosku zapewnia idempotencję; ponowienie z innym
zakresem/rodzajem/przyczyną jest konfliktem. Ponowienie identycznego wniosku
nie unieważnia kolejnej generacji zadania.

Nowa blokada zakupu atomowo unieważnia jego bieżące zadanie. Blokada zakupów
klienta zwiększa wyłącznie rewizję tego zakresu, bez masowego zapisu zadań.
Ograniczony pobór jednej pracy wykrywa zmianę rewizji także dla wcześniejszych
ocen DUE_FOR_REVIEW/BLOCKED/REVIEW i przyszłego next_attempt_at. Zachowuje
limity dzierżaw, prób i czasu SQL/transakcji. Kwarantanna także zwiększa
rewizję; po jej uwzględnieniu niezmieniony znacznik nie wywołuje kolejnych
prób pobrania zablokowanej oceny. Duże zbiory wymagają jeszcze
odbioru A43.41; indeks i limity nie zastępują testu obciążenia.

Przed zatwierdzeniem oceny wykonawca odczytuje bieżące blokady pod blokadami
FOR SHARE zakresu klienta i zakupu, utrzymanymi do końca transakcji. Zapis
nowej blokady jest z tym odczytem serializowany. Znana przeszkoda daje BLOCKED,
także przy braku dowodów łańcucha lub wcześniejszym pozytywnym GUARD.
**Pusty rejestr nigdy nie tworzy pozytywnego dowodu braku blokad.** Pełny
inwentarz, potwierdzenia spraw/retry/DLQ i bieżącego niezależnego dziennika
pozostają otwarte. Ta ocena nadal nie uruchamia operacji biznesowej.

Izolowany restore obejmuje nowe zakresy również przy pustym dzienniku.
W partiach do 100 ustawia restore_blocked, a trigger zwiększa rewizję raz;
historia blokad zostaje bez zmian. Ponowny replay jest idempotentny. Niepełny
schemat, błąd lub pozostały aktywny zakres zatrzymuje dalszy replay. Nie ma
zwalniania kwarantanny; przygotowanie oceny dla takiego zakresu jest odrzucane,
a istniejąca ocena nie może ominąć blokady.

Dowody odbioru bieżącego zakresu:
`tmp/audit-a43-40-known-holds/report.json`. Brak aktywacji D17, usuwania danych
D01 lub znaczników. A43.40/A43.41 nadal NOT_READY; aktualne braki i decyzje
podaje [odbiór końcowy](A43_ODBIOR_KONCOWY.md).


## Kontrolowany przebieg jednego zakupu — rewizja 31.10.2026 `.12`

Dodano wewnętrzny CommerceRetentionWorkflow: atomowe przygotowanie wskazanej
generacji i siedmiu bindingów, idempotencję z niezmiennym poprzednikiem (V32),
jeden odczyt źródła na krok oraz przejęcie wyłącznie wskazanego zadania do oceny.
Checkpointy przed/po odczycie sprawdzają znane blokady, bariery, kwarantannę,
właściciela, wersję i bindingi. HTTP pozostaje poza SQL; globalnych GUARD nie
uznaje się za potwierdzone. Brak automatycznego runtime, publicznego API/CC,
rozproszonej bariery zapisów lub workera czyszczenia. REDACTION to faza oceny,
nie wykonywanie redakcji; faza minimum pozostaje niedostępna w tym przebiegu.
Rejestr: 106 tabel/1016 pól, 183 migracje. Szczegóły i granice:
[A43_PRZEBIEG_OCENY_ZAKUPU.md](A43_PRZEBIEG_OCENY_ZAKUPU.md).
Dowody: tmp/audit-a43-40-targeted-workflow/report.json. A43.40/A43.41 nadal
NOT_READY, D17 bez aktywacji, D01/D09/P09 bez nowych rozstrzygnięć.


**Aktualizacja 31.10.2026, rewizja `.13`, pakiet 116:** odbiór pobierania ocen
na 100 000 zadaniach i optymalizacja planów SQL. Cztery ścieżki kandydatów,
jedna nowa dzierżawa, częściowe indeksy V33, okno klienta do 16 i zachowane
punktowe przejęcie. Bez danych/nowych pól, retencji, aktywacji lub zmian CC.
Rejestr 106 tabel/1016 pól, 184 migracje. [Zakres i ograniczenia pomiaru](A43_WYDAJNOSC_POBIERANIA_OCEN.md).
Dowody tmp/audit-a43-40-claim-scale/report.json; ostatni pełny drill pozostaje
z pakietu 115 i nie był w tym zakresie powtarzany. Pełne A43.40/A43.41 nadal
otwarte; D01/D09/P09 i D17 bez nowych rozstrzygnięć.


**Aktualizacja 31.10.2026, rewizja `.14`, pakiet 117:** przypięta definicja
D02 i adapter FIELD_SCOPE_REVIEWED. Nowe potwierdzenie ma niezmienny skrót;
stare wpisy bez zgodnego powiązania pozostają w historii i są nieznane dla
oceny. Nie potwierdza to polityki zachowania, aktualnego dziennika lub
wdrożonych schematów. Bez aktywacji i innych pozytywnych GUARD. Rejestr
106 tabel/1017 pól, 185 migracji. [Dowód i ograniczenia](A43_DOWOD_DEFINICJI_POL.md).
Raport tmp/audit-a43-40-field-scope/report.json; pełne A43.40/A43.41 i decyzje
D01/D09/P09 nadal otwarte, D17 bez aktywacji.


**Aktualizacja 31.10.2026, rewizja `.15`, pakiet 118:** osobna runda ponownej
weryfikacji ośmiu zapisanych obserwacji przed oceną, z CAS zakresu/rewizji,
kontrolą blokad i zachowaniem dzierżaw, budżetu prób oraz backoffu. Brak
pozwolenia na mutację, atomowej migawki lub rozproszonej bariery. Bez zmian
schematu, retencji, CC i aktywacji. [Zakres i granice](A43_PONOWNA_WERYFIKACJA_OBSERWACJI.md).
Raport tmp/audit-a43-40-revalidation/report.json; ostatni pełny drill pozostaje
z pakietu 117. Pełne A43.40/A43.41 i D01/D09/P09 nadal otwarte; D17 bez aktywacji.
