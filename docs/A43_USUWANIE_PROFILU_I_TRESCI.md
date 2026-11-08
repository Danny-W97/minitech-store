# A43.34 — usuwanie profilu, uwierzytelniania i treści konta

**Stan: A43.34 wykonane i odebrane lokalnie 29.10.2026.**
Wybrano osobny wniosek po tokenie i ręczną ocenę, bez automatycznego
kasowania rozmów lub powiadomień. A43.35 wykonano później lokalnie; aktualny zakres i przekazanie do finansów
opisuje [instrukcja A43.35](A43_USUWANIE_DANYCH_ZAMOWIEN.md).

Decyzje użytkownika z 29.10.2026:

- usunąć profil Customer i poświadczenia, pozostawiając ID-only barierę
  A43.33; ponowna rejestracja adresu otrzymuje nowe ID;
- usunąć tytuł, treść, ocenę i notatkę recenzji oraz publikację; zachować
  tylko customer_id/order_id/product_id do deduplikacji, do polityki A43.40;
- usunąć klucze ustawień TOTP/passkey po ID. Losowe wyzwania logowania
  pozostają do istniejącego TTL 5 minut, bez możliwości logowania na dawne
  konto; czasowe limity e-maila pozostają. Bez SCAN/KEYS/FLUSH Redis;
- czat: osobny wniosek po tokenie konkretnej rozmowy i bezpieczny status;
  bez automatycznego kasowania. Ręczną procedurę obsługi domyka A43.39.

## Aktywacja i zgoda na zakres

Mechanizm jest domyślnie wyłączony: `privacy.account-cleanup.enabled=false`
(`PRIVACY_ACCOUNT_CLEANUP_ENABLED`). Dotyczy Customer oraz każdego adaptera
Wishlist, Recommendation, Review i Chat. Uzgodniona aktywacja na konkretnym
środowisku należy do A43.40; testy włączają go tylko we własnych fixture.

Nowy podgląd i hash planu opisują dokładny zakres A43.34 oraz granice Redis.
Potwierdzenie nadal wymaga aktualnego właściciela, hasła i skonfigurowanego
MFA. Potwierdzone wcześniej żądania nie dostają nowej zgody: brak zgodnej
wersji polityki daje `policyReviewed=false`, bez wywołania czyszczenia.
Operator retry nie zatwierdza polityki, nie włącza funkcji i nie zmienia TTL.

Koordynator dopuszcza pracę dopiero przy potwierdzeniu wszystkich 14 barier
oraz zgodnym hashu polityki. Każdy lokalny adapter ponownie sprawdza własny
znacznik i UUID żądania pod blokadą. Wewnętrzny POST
`/internal/privacy/account-cleanup/{customerId}` jest dostępny wyłącznie
zaufanemu customer-service, pod osobnym przełącznikiem aktywacji.

## Lokalne partie

Każde wywołanie wykonuje co najwyżej 100 usunięć z jednej tabeli. Transakcja
ma ograniczony czas i nie obejmuje HTTP ani Redis. Wynik MORE oznacza następną
partię, a nie błąd; poprawny postęp nie wyczerpuje ośmiu prób awarii.
Lease/token wykonania i idempotencja chronią wznowienie po utracie odpowiedzi.

- Customer: usuwa powiązane refresh/reset/TOTP/kody odzyskiwania oraz
  poświadczenia WebAuthn o stabilnym ID (kanoniczne i kodowania z paddingiem).
  Następnie anuluje zadania eksportu, czyści ich porcje/plan i usuwa wyłączony
  profil. Sekrety Redis po ID usuwa przed partią SQL; awaria Redis zatrzymuje
  czyszczenie. Historycznych poświadczeń bez dowodu stabilnego ID nie łączy
  po e-mailu — pozostają do ręcznej oceny A43.39.
- Wishlist: usuwa tylko wiersze dawnego customer_id.
- Recommendation: usuwa zachowany log dawnego customer_id, bez zmian
  agregatów, danych innych kont i żądań bez przypisania do konta.
- Review: atomowo zapisuje trzy ID w `review_privacy_keys` i usuwa cały
  wiersz recenzji. Nie zachowuje treści, notatki, oceny, statusu ani dat
  recenzji. Klucz nie jest anonimowy; ma unikalność i trigger blokujący
  ponowne wstawienie. Publiczne listy i średnia nie widzą usuniętej recenzji.
- Chat: zwraca `SEPARATE_PROOF_REQUIRED`, bez odczytu/wyszukiwania treści
  po e-mailu lub koncie. Token konta nie jest tokenem rozmowy. Odrębne
  zgłoszenie po tokenie rozmowy zapisuje MANUAL_REVIEW; bez automatycznego
  kasowania treści i powiadomień. Procedurę ręcznej oceny domyka A43.39,
  zgodnie z A43.26/A43.29.

Bariera Customer nadal blokuje odtworzenie dawnego ID po usunięciu profilu.
Nie resetujemy sekwencji ID. Nowe konto nie dziedziczy danych dawnego konta.

## Osobny wniosek czatu

POST `/chat/conversations/{conversationId}/privacy-request` wymaga
`X-Chat-Access-Token`, UUID w `Idempotency-Key` i JSON
`{"confirmation":"REQUEST_CHAT_DELETION_REVIEW"}`. Zwraca 202 i bezpieczny
status. GET tej samej ścieżki wymaga aktualnego tokenu rozmowy przy każdym
odczycie. Obie ścieżki działają również przez `/api` i `/api/v1` Gateway.

Jeden wniosek na rozmowę; ponowienia i równoległe zgłoszenia zwracają ten
sam ID i pierwotną datę. Klucza wniosku nie można przypisać innej rozmowie.
Brak tokenu daje 401, zły token/nieznana rozmowa/brak wniosku — 404,
przypisany gdzie indziej klucz — 409. Brak frazy lub UUID — 400.
Operator i usługa nie mogą zastąpić dowodu ani użyć tego API jako bypass.

Tabela `chat_privacy_requests` zawiera tylko ID rozmowy, UUID wniosku,
stały stan MANUAL_REVIEW i datę. Nie kopiuje treści, e-maila, identyfikatora
konta, tokenu ani jego hasha. Odpowiedź zawiera tylko metadane i
contentErased=false/complete=false, ma private/no-store/nosniff.

Zgłoszenie nie zmienia wiadomości, wpisów ani kolejki powiadomień; nie
wysyła wiadomości i nie blokuje dalszej rozmowy. Działa niezależnie od
logowania/usunięcia konta oraz przełącznika automatycznego czyszczenia.
Nie tworzy zgody na automatyczne kasowanie. Ręczna ocena, sposób realizacji
i obsługa danych innych osób/kopii pozostają A43.39; retencja minimalnego
wniosku — A43.40. Nie dodano widoku CC ani sklepu.

Dowodem statusu jest nadal aktualny token istniejącej rozmowy, bez nowego
sekretu statusu i bez odzyskiwania przez e-mail. Nie zmienia się TTL tokenów.
Sposób wydania statusu po ewentualnym ręcznym usunięciu samej rozmowy
rozstrzyga A43.39; obecny etap zapisuje wyłącznie żądanie do oceny.

## Co pozostaje i co pokazuje status

`cleanup_state` i bezpieczne `cleanup_code` pokazują postęp usług. Zakres
`accountCleanup.coreComplete` dotyczy wyłącznie czterech adapterów danych
konta. Chat jest jawnie oddzielony. **complete=false i dataErased=false**
nadal dotyczą całej sprawy: zamówienia, finanse, audyt, powiadomienia,
kopie/backupy i urządzenia wymagają A43.35–A43.41.

Minimalne żądanie, bariery i klucze recenzji pozostają pseudonimowe do
ustalenia retencji A43.40. Nagłówki anulowanych eksportów podlegają ich
istniejącemu cyklowi życia, bez przedłużania dostępności. Usunięcie danych
źródłowych nie usuwa dawnych wpisów audytu ani kopii u dostawcy/przeglądarki.
Wyzwania z losowym kluczem są nieskuteczne przez brak starego konta i jego
poświadczeń; istniejące TTL i limity logowania nie są wydłużane ani zerowane.

Nie wznawia się A05, całego A41 ani zmian samych zdjęć. Odbiór lokalny
nie oznacza aktywacji na danych użytkownika lub wdrożenia dev/prod.

## Odbiór rdzenia 29.10.2026

305 unikalnych testów Java, w tym 22 PostgreSQL, oraz 276 Python — PASS.
Sześć aplikacji, pięć własnych baz i Redis; 9 kontrolowanych peerów.
Pięć grup HTTP sprawdza również brak działania przy wyłączonej aktywacji,
restart, awarię usługi, rollback SQL recenzji, 205 wierszy na domenę,
ponowną rejestrację adresu pod nowym ID i zachowanie limitów Redis.
Regresja A43.33: sześć grup — PASS. Oracle 14 baz: 69 tabel/662 kolumny,
144 migracje, 60 zobowiązań unikalności. Rejestr: 41 mechanizmów i 17 kopii.
Raport rdzenia sprzed domknięcia: `tmp/audit-a43-34/report-core-partial.json`.


## Domknięcie A43.34 — 29.10.2026

Wariant czatu wybrany przez użytkownika wdrożono i odebrano: osobny
wniosek po tokenie oraz status, wyłącznie MANUAL_REVIEW. Procedura ręczna
nadal należy do A43.39; nie uruchomiono kasowania rozmów/powiadomień.

327 unikalnych testów Java (w tym 38 PostgreSQL), 277 Python i 6 grup
HTTP — PASS. Nowe testy sprawdzają współbieżność, dwie rozmowy o tym samym
e-mailu, odmowy ról, związanie UUID, brak kopiowania sekretów/treści i
niezmienność rozmowy, wpisów oraz outboxu. Kontrakt OpenAPI i obie ścieżki
Gateway odebrane; wcześniejszy eksport czatu ma 10 regresji PostgreSQL.

Bieżący rejestr: 70 tabel/666 kolumn/145 migracji, 62 zobowiązania
unikalności, 41 mechanizmów i 17 kategorii kopii. Odbiór wszystkich 14
schematów i aktualnego modułu ochrony w 15 JAR-ach — PASS.
Stan po A43.34: pozostawało 7 etapów. Aktualnie po A43.35 pozostaje 6 etapów A43.36–A43.41; następny A43.36. Całościowe możliwości
usuwania/kopii pozostają pending, aktywacja czyszczenia domyślnie wyłączona.
Raport: `tmp/audit-a43-34/report.json`.


## Uzupełnienie A43.39 — 30.10.2026

Ręczna ocena alternatywnych dowodów konkretnej rozmowy, bez dopasowania po
e-mailu i odzyskiwania tokenu; niewystarczający dowód pozostawia sprawę otwartą.
Po fizycznym usunięciu rozmowy publiczne API traci dostęp (404), a status przekazuje
uprawniona obsługa po weryfikacji dowodu. Minimalny rejestr nie kopiuje hasha
tokenu i nie dowodzi wykonania usunięcia. [Procedura A43.39](A43_DOSTAWCY_PLIKI_I_DOWODY_CZATU.md).
