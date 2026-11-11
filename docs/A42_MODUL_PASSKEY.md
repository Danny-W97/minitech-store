# A42.11 — moduł konwersji passkey/WebAuthn (27.10.2026)

`frontend/passkey-codec.js` wydziela z `app.js` konwersję opcji serwera na
opcje WebAuthn, kodowanie binarne i serializację poświadczeń. Moduł nie wykonuje
HTTP, nie korzysta z DOM/storage i nie wywołuje authenticatora. Strona nadal
obsługuje formularze, sesję, wywołania `navigator.credentials`, endpointy MFA
i komunikaty użytkownika. Nie dodano nowej metody uwierzytelniania.

## Kontrakt i poprawki

- `toAuthenticationOptions()` zachowuje challenge, RP ID, timeout, listę
  dozwolonych poświadczeń i domyślne `userVerification: required`.
- `toRegistrationOptions()` zachowuje dane użytkownika/RP, listę wykluczeń,
  timeout, `attestation: none` i domyślne `userVerification: preferred`.
  Algorytmy ES256/RS256 nadal są mapowane na -7/-257; numeryczne identyfikatory
  algorytmów zachowują dotychczasową konwersję.
- `serializeAssertion()` i `serializeAttestation()` zachowują pola przesyłane
  przez obecne API, w tym jawny challengeId i nullable userHandle. Nie przekazują
  pozostałych właściwości obiektu poświadczenia. Brak poświadczenia daje czytelny błąd.
- `encodeBase64Url()` koduje porcjami po 16384 bajty. Stary jednorazowy spread
  wszystkich bajtów powodował `RangeError` dla testowego bufora 1 MiB. Nowy
  wynik odpowiada niezależnemu kodowaniu Node Buffer i zachowuje dokładne bajty.
  Widoki typed array/DataView uwzględniają offset i długość; nie kodują sąsiednich
  danych. Nieprawidłowy typ wejścia jest odrzucany.
- `decodeBase64Url()` zachowuje padding i dotychczasową obsługę wariantu base64.
  Lokalna kontrola zgodności hosta RP dla portu 18070 pozostała bez zmian;
  pozostałe reguły origin/RP są nadal egzekwowane przez przeglądarkę i backend.

Logowanie przechwytuje ID konkretnego wyzwania przed operacjami asynchronicznymi.
Zamknięcie lub zmiana wyzwania blokuje wysłanie spóźnionego poświadczenia pod
nowym challengeId. Wynik/błąd starego wyzwania nie aktualizuje nowszego widoku
ani nie odblokowuje jego przycisku. Rejestracja sprawdza analogicznie, czy konto
pozostało to samo po pobraniu opcji i odpowiedzi urządzenia.

Nie dodano automatycznych ponowień dowodu MFA. Kontrole widoku nie cofają
operacji przyjętej już przez serwer i nie zmieniają serwerowego zużycia wyzwań,
podpisów, polityki RP, TTL ani konfiguracji kluczy MFA/JWT.

## Weryfikacja

- **116 testów JavaScript — PASS**, w tym 12 nowych testów modułu: round trip
  i bajty 0–255, bufor 1 MiB, zakresy widoków, nieprawidłowe dane, opcje obu
  operacji, algorytmy, niezmienność wejścia, payloady i kontrola lokalnego hosta.
- **95 testów Chromium — PASS**, pełna regresja sklepu z pięcioma nowymi
  scenariuszami passkey. **45 testów Firefox/WebKit/mobilnego Chromium — PASS**:
  passkey, dotychczasowe logowanie/MFA, blokada refresh między kartami i smoke.
- Pięć nowych scenariuszy wykonano w czterech profilach (20 przypadków,
  zawartych w powyższych liczbach): rejestracja z dokładnym buforem 1 MiB,
  poprawne opcje i payload logowania, anulowanie rejestracji bez zapisu,
  401 dowodu bez refresh/retry oraz wynik starego wyzwania przy otwartym nowym.
- Testy przeglądarkowe używały własnej implementacji `navigator.credentials`
  i odpowiedzi API fixture. Sprawdzają konwersję i cykl UI; nie zastępują
  serwerowych testów kryptograficznych ani odbioru fizycznego authenticatora.
- Składnia modułu i `app.js`, `git diff --check` — PASS. Konfiguracja CI
  obejmuje nowe testy JS oraz scenariusze w czterech profilach. CI nie uruchamiano zdalnie.

## Dostarczenie i status

Przy przyszłym wdrożeniu należy dostarczyć razem `passkey-codec.js`, zmieniony
`app.js`, `index.html` i `catalog.html`. Zaktualizowano wersje URL modułu i
skryptu wejściowego. Dockerfile nadal kopiuje cały frontend. Bez bundlera,
nowych zależności, zmian zdjęć i zmian API/backendu. Nie powtarzano Maven,
nie budowano obrazu i nie wdrażano na dev/prod.

Raport: `tmp/audit-a42-11-88d2cb16/report.json`. Logi: `tmp/a42-11-node.log`,
`tmp/a42-11-browser.log`, `tmp/a42-11-cross-browser.log`. Artefakty:
`gitIgnoreOwn/runtime/playwright-a42-11-final` i
`gitIgnoreOwn/runtime/playwright-a42-11-browsers`.

Pełne A42 pozostaje częściowo otwarte dla dalszego podziału logiki stron
i pozostałych duplikacji. A05, całe A41 oraz zmiany samych zdjęć pozostają odłożone.
