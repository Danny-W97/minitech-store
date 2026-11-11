# A22 — niezależne klucze MFA i rotacja JWT

Customer Service wymaga osobnego klucza AES-256 do sekretów TOTP we wszystkich
profilach, także `dev` i `test`. Klucz nie jest już wyprowadzany z JWT.
Zmiana klucza podpisującego JWT nie zmienia szyfrowania MFA.

## Konfiguracja

| Zmienna | Znaczenie |
| --- | --- |
| `CUSTOMER_MFA_ENCRYPTION_KEY_BASE64` | Aktywny klucz: dokładnie 32 losowe bajty zakodowane standardowym Base64. Wymagany |
| `CUSTOMER_MFA_ACTIVE_KID` | Identyfikator aktywnego klucza; domyślnie `mfa-v1`. Znaki A–Z, a–z, 0–9, `_`, `-`; maks. 64 |
| `CUSTOMER_MFA_PREVIOUS_KEYS_JSON` | Obiekt `{kid: klucz Base64}` z poprzednimi kluczami potrzebnymi do odszyfrowania. Nie zawiera aktywnego kid. Maks. 32 wpisy |
| `CUSTOMER_MFA_LEGACY_KEY_BASE64` | Opcjonalny, zachowany stary klucz AES do szyfrogramów bez wersji; również dokładnie 32 bajty w Base64 |
| `CUSTOMER_MFA_KEY_FORMAT` | Metadane skarbca Control Center: `v1` oznacza wykonane przygotowanie niezależnego klucza. Nie usuwać podczas edycji profilu |

Klucze trafiają wyłącznie do Customer Service. Skrypty pozostałych usług
usuwają wszystkie zmienne `CUSTOMER_MFA_*`. Compose przekazuje klucze tylko
Customer Service; manifest Kubernetes zawiera wymagany aktywny sekret i
opcjonalne poprzednie klucze. Nie ma domyślnego klucza aplikacji.

## Aktualizacja istniejącego środowiska

1. Zachowaj szyfrowany backup bazy Customer i bieżący skarbiec sekretów.
2. Zatrzymaj **wszystkie instancje Customer Service** przed pierwszym startem
   nowej wersji. Migracja formatu MFA wymaga takiej przerwy; nie wykonywać
   równoległego uruchomienia starej i nowej wersji Customer Service.
3. Standardowe uruchomienie z Control Center przygotuje brakujący niezależny
   klucz w skarbcu SOPS. Przed zmianą JWT zachowa wynik starego wyprowadzenia:
   `Base64(SHA-256(UTF-8(stary materiał)))`. Dawny materiał wybierano w kolejności:
   dedykowane MFA, prywatny klucz RSA JWT, sekret HMAC JWT — pierwszy niepusty.
   Wyliczenie jest wykonywane tylko podczas przygotowania starego profilu;
   po zapisaniu `CUSTOMER_MFA_KEY_FORMAT=v1` nie wraca do kluczy JWT.
4. Uruchom przebudowany Customer Service z tym samym profilem. Przy starcie,
   przed przyjmowaniem żądań HTTP, usługa w jednej transakcji odszyfruje i
   zweryfikuje wszystkie zapisane metody TOTP, również nieaktywne, i zaszyfruje
   stare rekordy aktywnym kluczem. Pobiera rekordy partiami po 100 z blokadą.
5. Sprawdź gotowość usługi i komunikat `MFA key migration complete` z liczbami
   `verified`, `migrated` i `activeKid`. Potwierdź logowanie istniejącego konta
   kodem TOTP. Migracja zachowuje stan włączenia i ostatni zużyty krok TOTP.

W środowisku uruchamianym bez Control Center trzeba samodzielnie zapisać
dedykowany klucz i zachować stary klucz AES **przed** zmianą dawnego materiału.
Dotyczy to także istniejącego profilu `prod`, który wcześniej hashował tekst
dedykowanego klucza. Aplikacja nie odtwarza klucza z JWT ani nie zgaduje go.
Jeżeli materiał utracono przed tą aktualizacją, potrzebny jest jego bezpieczny
backup; nowy klucz nie odszyfruje istniejących danych.

Nowy format ma postać `v1:<kid>:<Base64URL(IV + szyfrogram + tag)>`.
AES-GCM uwierzytelnia również wersję i kid; każda operacja używa nowego losowego
IV. Nieznany klucz, błędny tag, uszkodzony rekord lub brak klucza zatrzymują start.
Wszystkie wcześniejsze zmiany migracji są wycofywane, także z wcześniejszych partii.
Nie ma automatycznego resetowania MFA. Ponowiony start po udanej migracji
weryfikuje dane, ale nie zmienia aktualnych szyfrogramów.

## Rotacja JWT

```bash
bash scripts/control-center/control-center.sh jwt-rotate dev
```

Polecenie najpierw zabezpiecza materiał do migracji MFA, następnie generuje
RSA-2048 w formacie PKCS#8 i zapisuje aktywny kid oraz klucz prywatny. Poprzednie
klucze publiczne pozostają w rejestrze do weryfikacji wydanych tokenów.
Zapis SOPS powstaje w pliku obok skarbca i zastępuje go po udanym szyfrowaniu;
błąd szyfrowania lub podmiany nie zastępuje dotychczasowego skarbca.
Customer Service przy starcie sprawdza zgodność aktywnej pary kluczy.

Po rotacji uruchom ponownie Customer Service, aby wczytać konfigurację.
Tokeny RS256 podpisane poprzednim kluczem są akceptowane do ich wygaśnięcia,
jeżeli ich klucz publiczny pozostaje w rejestrze. Usuwaj go dopiero po upływie
maksymalnego TTL wcześniej wydanych tokenów. Pierwsze przejście z HS256 na
RS256 wymaga ponownego logowania istniejących sesji HS256; konfiguracja TOTP
pozostaje dostępna. Domyślny TTL access tokenu wynosi 900 sekund.

## Rotacja szyfrowania MFA

```bash
bash scripts/control-center/control-center.sh app-stop customer-service
bash scripts/control-center/control-center.sh mfa-rotate dev
bash scripts/control-center/control-center.sh app-run customer-service dev
```

Zatrzymaj również pozostałe instancje Customer Service, jeśli działają poza
Control Center. Wykonuj operacje na skarbcu kolejno. Nowy losowy klucz ma nowy
kid, a poprzedni zostaje w `CUSTOMER_MFA_PREVIOUS_KEYS_JSON`. Stary klucz legacy
też pozostaje. Start wykonuje atomową migrację i sprawdza czytelność wszystkich
rekordów. Polecenie jest dostępne również jako „Rotuj klucz MFA” w panelu.

## Wycofanie starego materiału

Skrypty nie usuwają automatycznie kluczy potrzebnych do odszyfrowania danych.
Po udanym starcie i sprawdzeniu logowania skontroluj bazę, np. w `db-shell customer`:

```sql
SELECT CASE WHEN secret_ciphertext LIKE 'v1:%'
            THEN split_part(secret_ciphertext, ':', 2)
            ELSE 'legacy' END AS key_id,
       count(*)
FROM customer_totp_methods
GROUP BY 1;
```

Stary wpis można usunąć ze skarbca dopiero, gdy żaden rekord go nie potrzebuje,
wszystkie instancje korzystają z nowej konfiguracji i zabezpieczono możliwość
odtworzenia starszych backupów. Usuń odpowiedni wpis z rejestru poprzednich
kluczy lub zmienną legacy; zachowaj `CUSTOMER_MFA_KEY_FORMAT=v1`. Po restarcie
aplikacja ponownie sprawdzi wszystkie rekordy. Nie usuwaj bieżącego klucza
ani nie zmieniaj jego wartości przy zachowanym kid.

Zakres A22 nie zmienia współbieżności zużywania TOTP, recovery codes i refresh
tokenów ani TTL enrollmentu — to osobne ustalenie A23.

## Testy

```bash
./mvnw -pl customer-service -am package
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
bash scripts/qa/unit/platform-auth-test.sh
bash scripts/qa/unit/mfa-key-rotation-test.sh
python3 scripts/qa/mfa-key-rotation-e2e.py
python3 scripts/qa/access-control-e2e.py
```

Testy skarbca i E2E używają wyłącznie tymczasowych kluczy, baz i kontenerów.
Nie wykonują rotacji rzeczywistych sekretów środowiska deweloperskiego.
