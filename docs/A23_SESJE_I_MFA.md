# A23 — sesje i jednorazowe użycie MFA

## Refresh tokeny

Każde logowanie tworzy niezależną rodzinę refresh tokenów. Odświeżenie zużywa
dotychczasowy token i wydaje jeden nowy token w tej samej rodzinie. Operacje
blokują rekord klienta w PostgreSQL; blokada działa między procesami i instancjami.
Token jest odczytywany dopiero po uzyskaniu blokady, bez używania starego
snapshotu z kontekstu JPA. Wylogowanie i reset hasła korzystają z tej samej
kolejności blokowania klienta.

Ponowne użycie już obróconego tokenu jest traktowane jako replay:

- żądanie otrzymuje 401;
- wszystkie aktywne refresh tokeny tej rodziny są unieważniane;
- unieważnienie jest zatwierdzane pomimo odpowiedzi 401;
- zapisywane jest zdarzenie `REFRESH_TOKEN_REUSE`, bez wartości tokenu;
- inne, niezależne logowania tego klienta pozostają aktywne.

Przy dwóch jednoczesnych żądaniach z tym samym cookie jedno może zwrócić 200,
a drugie 401 i unieważnić również nowy refresh token pierwszego żądania.
To celowa, ścisła polityka replay bez okresu tolerancji. Po takim zdarzeniu
potrzebne jest ponowne logowanie. Klient powinien wysyłać jedno odświeżenie naraz.
Sklep używa wspólnej obietnicy w dokumencie oraz Web Locks między kartami tego
samego originu, jeśli przeglądarka udostępnia ten mechanizm. Bez Web Locks
pozostaje blokada lokalna dokumentu i egzekwowanie polityki przez serwer.

Wylogowanie przy użyciu starszego tokenu też unieważnia jego rodzinę, więc nie
może pozostawić aktywnego następcy wydanego w wyścigu z odświeżeniem.
Istniejące JWT access tokeny nadal podlegają dotychczasowej walidacji i TTL
(domyślnie 900 s); ta zmiana nie dodaje listy natychmiastowego unieważniania JWT.

## TOTP i kody odzyskiwania

Logowanie MFA, konfiguracja i wyłączenie TOTP oraz regeneracja kodów blokują
ten sam rekord klienta przed odczytem stanu czynnika. Kod TOTP zużywa zapisany
krok czasowy tylko raz, także przy dwóch różnych wyzwaniach logowania i dwóch
instancjach. Kod odzyskiwania analogicznie otrzymuje `used_at` tylko w jednej
udanej operacji. Zużycie kodu i wydanie sesji są objęte transakcją bazodanową.

Wyzwania logowania TOTP i WebAuthn są pobierane przez atomowe Redis `GETDEL`.
Jedno wyzwanie ma jedną próbę weryfikacji. WebAuthn blokuje konto podczas
sprawdzania asercji, aby równoległe wyzwania nie aktualizowały stanu poświadczenia
na podstawie tego samego odczytu. Test jednostkowy WebAuthn sprawdza jednorazowość
wyzwania; nie zastępuje pełnej weryfikacji wszystkich fizycznych authenticatorów.

## Wygasanie konfiguracji TOTP

Konfiguracja ma ważność 10 minut. Termin jest zapisany w bazie, a Redis zachowuje
jednorazowy sekret oczekującej konfiguracji z TTL. Potwierdzenie wymaga jednocześnie:

1. wyłączonego jeszcze TOTP;
2. niewygasłego terminu w bazie;
3. poprawnego, niezużytego kodu TOTP;
4. obecnego, zgodnego sekretu oczekującej konfiguracji w Redis.

Wygasła lub utracona konfiguracja zwraca 410 z kodem
`MFA_ENROLLMENT_EXPIRED`. Sklep usuwa tymczasowy klucz i QR oraz pozwala rozpocząć
konfigurację od nowa. Błędny kod zwraca 401 `MFA_INVALID_CODE` i pozostawia ważną
konfigurację do ponowienia. Ponowne potwierdzenie już aktywnego TOTP daje 409
i nie wydaje nowego zestawu kodów odzyskiwania.

Awaria po zużyciu stanu Redis, lecz przed commitem bazy, nie włącza TOTP;
użytkownik musi rozpocząć konfigurację ponownie. Nie ma fallbacku akceptującego
sam rekord bazy po wygaśnięciu/utracie Redis.

## Regeneracja kodów odzyskiwania — zmiana kontraktu API

`POST /customers/mfa/recovery-codes/regenerate` wymaga uwierzytelnionej sesji,
aktywnego TOTP oraz nowego kodu z aplikacji Authenticator:

```json
{"code":"123456"}
```

Kod jest sprawdzany i zużywany w tej samej transakcji co zastąpienie zestawu
ośmiu kodów odzyskiwania. Kod użyty wcześniej do logowania/wyłączenia/konfiguracji
nie może zostać użyty ponownie do regeneracji. Brak treści lub zły format daje
400, niepoprawny/zużyty TOTP daje 401. W obu przypadkach dotychczasowe kody
pozostają bez zmian. Kod odzyskiwania nie zastępuje TOTP przy regeneracji.

Odpowiedzi `MFA_INVALID_CODE` i `MFA_CHALLENGE_EXPIRED` nie uruchamiają w sklepie
automatycznego odświeżenia sesji i ponowienia żądania z zużytym dowodem.

## Migracja i wdrożenie

Flyway `V12__secure_refresh_and_mfa_lifecycle.sql` dodaje identyfikator rodziny
i czas rotacji refresh tokenu oraz termin konfiguracji TOTP. Istniejące tokeny
dostają odrębne rodziny `legacy-<id>` i mogą zostać użyte zgodnie z dotychczasową
ważnością. Niedokończone konfiguracje starej wersji nie mają terminu i trzeba
rozpocząć je ponownie. Aktywny TOTP i jego sekret pozostają zachowane.

Zaktualizuj wszystkie instancje Customer Service w jednej przerwie: stare
instancje nie stosują nowego protokołu blokowania i nie zapisują rodziny tokenu.
Nie utrzymuj jednocześnie starej i nowej wersji. Nadal obowiązuje
[procedura kluczy A22](A22_MFA_KLUCZE_ROTACJA.md). Aktualizacja nie wymaga
rotowania rzeczywistych kluczy JWT ani MFA.

## Weryfikacja

```bash
./mvnw -pl customer-service -am package
pnpm run test:ui
python3 scripts/qa/mfa-key-rotation-e2e.py --with-session-checks
python3 scripts/qa/access-control-e2e.py
```

Scenariusz `--with-session-checks` uruchamia dwie instancje Customer Service
na wspólnych, tymczasowych PostgreSQL i Redis. Sprawdza wyścigi TOTP, recovery,
refresh, potwierdzenia konfiguracji i regeneracji kodów oraz izolację rodzin
sesji, logout i TTL. Zasoby nie korzystają z baz ani sekretów środowiska dev.
