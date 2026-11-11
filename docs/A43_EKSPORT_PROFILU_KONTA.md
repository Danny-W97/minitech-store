# A43.16 — eksport profilu konta i podsumowania zabezpieczeń

Zakres etapu: częściowe pobranie danych z Customer Service. Pełny eksport
wszystkich danych klienta, usunięcie/anonimizacja i obsługa kopii pozostają
otwarte. Pole `complete: false` i `omissions` są częścią odpowiedzi API.

## Pobieranie

Zalogowany klient wywołuje `GET /api/v1/customers/me/data-export` przez
Gateway. Działa też dotychczasowy alias `/api/customers/me/data-export`
i bezpośredni endpoint Customer `/customers/me/data-export`.
Wymagany jest własny, ważny access JWT w nagłówku Bearer. Od A43.17
pobieranie jest też dostępne w panelu profilu sklepu; szczegóły:
[A43.17 — pobieranie w sklepie](A43_POBIERANIE_PROFILU_W_SKLEPIE.md).

Odpowiedź to załącznik JSON `customer-profile-export.json`:

- `schemaVersion: 1`, `scope: customer-profile-and-security-summary`,
  `complete: false`, czas odczytu `generatedAt`;
- `profile`: ID, e-mail, imię/nazwisko, adres, telefon lub null, aktywność
  konta i czasy utworzenia/ostatniej zmiany;
- `security`: boolean `totpEnabled`, liczba passkeyów `passkeyCount`
  i liczba nieużytych kodów `unusedRecoveryCodeCount`;
- `omissions`: sekrety uwierzytelniania wykluczone; historia bezpieczeństwa,
  audyt, inne usługi, Redis/runtime oraz backupy/kopie zewnętrzne — pending.

Liczba nieużytych kodów nie oznacza, że TOTP jest włączone ani że kod może
zostać zaakceptowany w danym procesie. Nie eksportujemy wartości kodów.
Historia passkeyów, ich etykiety i metadane logowań nie należą do tego etapu.

Tożsamość pochodzi z uwierzytelnionego `CustomerPrincipal`; parametry `id`,
`customerId`, `email` i nagłówki klienta nie wybierają podmiotu. Brak wariantu
eksportu z ID w ścieżce. Operator i token usługi nie mogą pobrać tego pliku.
Nieaktualne, wyłączone lub usunięte konto otrzymuje 401; zapytanie ponownie
sprawdza ID/e-mail i aktywność, także po wcześniejszym uwierzytelnieniu.

Odpowiedź ma `Cache-Control: no-store, private`, `Content-Disposition:
attachment` ze stałą nazwą i `X-Content-Type-Options: nosniff`. Gateway
przekazuje te nagłówki. Zapisany przez użytkownika plik stanowi osobną kopię,
której serwer nie usuwa z urządzenia użytkownika.

## Odczyt i zakres danych

`AccountDataExportService` wykonuje jedno parametryzowane zapytanie SQL:
jawne kolumny profilu, EXISTS dla włączonego TOTP i dwa COUNT z filtrami
właściciela. Identyfikator WebAuthn jest kodowany tym samym mechanizmem
base64url co rejestracja/logowanie. Nie porównujemy go z adresem e-mail
ani tekstową liczbą ID.

Jeden statement daje jedną migawkę odczytu PostgreSQL; odpowiedź zawiera
jeden profil i skalary. Limit wyniku wynosi jeden wiersz, a timeout SQL
dwie sekundy. Nie pobieramy list credential entities, nie tworzymy ZIP,
trwałego zadania ani archiwum eksportu. Awaria odczytu nie daje częściowej
odpowiedzi 200.

Warstwa eksportu nie wybiera `password_hash`, tokenów/hashy weryfikacji,
resetu lub refresh, szyfrogramu TOTP, hashy kodów, identyfikatorów/kluczy
passkeyów ani attestation. Istniejący mechanizm uwierzytelniania pozostaje
bez zmian; ten etap nie przebudowuje jego odczytów.

Nie eksportujemy surowego audytu: `actor/resource/metadata` mogą odnosić
się do innych osób. Dalszy etap wymaga bezpiecznego rozwiązania podmiotu
i redakcji. Dane zamówień, faktur, płatności, dostawy, opinii, wishlisty,
rekomendacji, zwrotów i czatu nie zostały dołączone do tego pliku.
Nie wznawiać odłożonych A05, A41 ani zmian samych zdjęć.

## Rejestr i CI

W rejestrze A43.15 cztery tabele Customer mają `partial-safe-projection`
z opisem eksportowanych pól/agregatów i ograniczeń. Globalne
`customerExport`, `customerErase` i `backupErasure` pozostają `pending`.
Dodano referencję mechanizmu A43.16 i znaną kopię pobranego JSON; nie
zmieniono hashy migracji/Redis ani nie zaakceptowano ich automatycznie.

OpenAPI ma jednoznaczne nazwy DTO: `AccountDataExport`,
`AccountExportProfile`, `AccountExportSecuritySummary`, `AccountExportOmission`.
Kontrakt QA obejmuje nową operację. Nowy krok CI uruchamia
`python3 scripts/qa/account-data-export-e2e.py` po zbudowaniu JAR-ów.

Odbiór lokalny:

```sh
./mvnw --batch-mode -pl customer-service,api-gateway -am -Pintegration verify
python3 scripts/qa/account-data-export-e2e.py
python3 scripts/qa/data-lifecycle-check.py
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
```

Test PostgreSQL tworzy własny kontener i rolę z prawem SELECT wyłącznie do
bezpiecznych kolumn. Eksport działa mimo braku prawa odczytu hashy/sekretów;
bezpośrednia próba ich odczytu przez rolę jest odrzucona. Pozostałe testy
sprawdzają zakres właściciela, różne kodowania WebAuthn, zużyte kody,
brak MFA/null telefonu, wyłączone/zmienione/usunięte konta i brak zapisów.

Odbiór JAR sprawdza bezpośredni port Customer oraz oba aliasy Gateway,
ważny JWT, inne konto, sześć odmów, nagłówki, pominięcia/sekrety i OpenAPI.
Używa wyłącznie własnych danych i losowych kluczy fixture; kończy procesy
i usuwa własny kontener. Raport zawiera wyniki, bez kopii profili/tokenów.

<!-- A43_16_RESULTS -->
- **239 Java — PASS, bez pominiętych**: 108 wspólnego modułu, 33 Gateway,
  67 Customer unit i 31 Customer PostgreSQL. W sumie 15 nowych: 8 HTTP/filter
  i 7 PostgreSQL. Nowych przypadków nie doliczać ponownie do 239.
- **246 Python — PASS, bez pominiętych**, w tym jeden nowy test częściowej
  projekcji. Wynik zawiera dotychczasowe 12 Kubernetes. Osobne 27 przypadków
  bramki A43 także PASS — są już w sumie 246.
- **JAR HTTP — PASS**, pięć grup odbioru: port Customer, `/api`, `/api/v1`,
  niezmienność danych i OpenAPI. Dla każdej ścieżki sprawdzono zakres dwóch
  kont, próby podmiany podmiotu, sekrety, nagłówki i sześć odmów.
- **Rejestr — PASS**: 14 baz, 49 tabel, 120 migracji, 5 przestrzeni Redis,
  10 źródeł Redis, 15 rodzajów kopii i 23 mechanizmy; cztery częściowe
  projekcje. Globalny eksport/usunięcie/backup nadal pending.

Raport końcowy: `tmp/audit-a43-16/report.json`. Logi:
`tmp/a43-16-maven-verify-final.log`, `tmp/a43-16-python-full.log`,
`tmp/a43-16-lifecycle-tests.log`, `tmp/a43-16-http-e2e.log`.
Raport HTTP: `tmp/account-data-export-ab21b1dc/report.json`.
Pierwszy przebieg PostgreSQL wykrył powtórzony unikalny hash weryfikacji
w fixture dwóch kont. Poprawiono sentinel per konto; pełny ponowiony odbiór
Maven zakończył się sukcesem. Produkcyjny schemat nie wymagał zmiany.
<!-- /A43_16_RESULTS -->

Bez nowych zależności, migracji, zmiennych środowiskowych i sekretów.
Bez aktywacji retencji, wysyłki e-maili/Stripe i operacji na kontach użytkownika.
Zmiana kodu i CI nie oznacza wdrożenia na dev/prod ani restartu działającego CC.
