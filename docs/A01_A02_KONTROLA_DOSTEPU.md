# A01/A02 — kontrola dostępu

Wdrożenie na `Audit_0.1`, 23 października 2026. Reguły działają w usługach,
także przy pominięciu Gateway. Wspólny moduł `platform-security` zapewnia
uwierzytelnianie i listę dozwolonych wywołań; kontrolery i Order Service
sprawdzają właściciela konkretnego rekordu przed odczytem lub zmianą.

## Tożsamości i uprawnienia

| Tożsamość | Dostęp |
| --- | --- |
| Anonim | Katalog, dostępność, publiczne promocje/opinie/rekomendacje, wycena dostawy; utworzenie zakupu lub czatu z losowym kluczem dostępu |
| Klient | Własny profil, historia zamówień, płatności, faktury i ich PDF/korekty, dostawy, wishlist, zwroty, opinie i rekomendacje |
| Operator | Listy operacyjne, dane klientów, zmiany produktów/magazynu/statusów, moderacja, odpowiedzi czatu, faktury/korekty/eksport, powiadomienia i DLQ; bez przejmowania profilu i MFA klienta |
| Usługa | Wyłącznie wywołania wymienione dla jej nazwy w `RoutePolicy`; nie otrzymuje ogólnych praw operatora |

Zwykły JWT klienta nigdy nie nadaje roli operatora. Operator korzysta z osobnego,
losowego poświadczenia zapisanego w skarbcu. Nie dodano pola roli do rejestracji.
Nagłówki `X-Customer-Id`, `X-Role` i podobne nie ustanawiają tożsamości.

Customer Service weryfikuje podpis i termin JWT oraz aktywność konta. Pozostałe
usługi korzystają z uwierzytelnionej introspekcji `/internal/auth/introspect`.
Wyłącznie Customer Service otrzymuje rejestr poświadczeń i prywatny materiał
podpisujący JWT. Usługi nie otrzymują klucza pozwalającego im wystawiać tokeny klientów.
Klient HTTP usługi zastępuje nagłówek Authorization jej własnym poświadczeniem.
Gateway przekazuje tożsamość wywołującego, bez nadawania dodatkowych uprawnień.

| Usługa | Dodatkowe dozwolone operacje wewnętrzne |
| --- | --- |
| Product | Wyliczenie promocji |
| Order | Utworzenie, odczyt i zwolnienie rezerwacji |
| Payment | Odczyt/weryfikacja zamówienia; status PAID/CANCELLED; potwierdzenie/zwolnienie rezerwacji; realizacja kuponu; faktura; utworzenie śledzenia |
| Review, Returns, Delivery, Billing | Odczyt/weryfikacja zamówienia |
| Recommendation | Historia zamówień klienta |
| Notification | Pobranie PDF faktury |
| Customer, Chat | Utworzenie powiadomienia |
| Pozostałe | Publiczne operacje oraz możliwość uwierzytelnienia własnego klienta API; bez uprawnień administracyjnych |

## Właściciel i zakupy gościnne

- Podany `customerId` musi odpowiadać zweryfikowanemu klientowi. Gość nie może
  przypisać zamówienia do dowolnego konta. Konto podaje swój zweryfikowany e-mail.
- Sam numer zamówienia, płatności, faktury, korekty, zwrotu lub rozmowy nie
  uprawnia do odczytu danych. Listy z `/customer/{id}` sprawdzają podmiot tokena.
- Klucz idempotencji nie daje dostępu do istniejącego zamówienia innego właściciela.
- Płatność sprawdza zamówienie przed obsługą idempotencji i skutkami ubocznymi.
  Właściciel faktury i adres powiadomienia pochodzą z zamówienia, a nie z pól płatności.
- Zwroty sprawdzają również właściciela powiązanego zamówienia. Opinie wymagają
  tożsamości autora i jego opłaconego zakupu. Publiczne opinie nie zawierają
  `customerId`, `orderId` ani notatek moderacyjnych.
- Przeglądarka generuje przez `crypto.getRandomValues` osobny klucz 256-bitowy
  dla zakupu gościnnego i osobny dla rozmowy. Przesyła go w
  `X-Order-Access-Token` lub `X-Chat-Access-Token`. Serwer zapisuje wyłącznie SHA-256
  i porównuje skróty w stałym czasie. Klucze nie trafiają do URL ani odpowiedzi listujących.
- Klucz zakupu jest zachowany w `sessionStorage` dla tej instancji API i zamówienia;
  klucz rozmowy w `localStorage` dla tej instancji API. Usunięcie tych danych usuwa
  dostęp gościa; operator zachowuje dostęp do obsługi.
- Migracje `V12` Order Service i `V2` Chat Service nie dopisują kluczy do starych
  rekordów. Historyczne zakupy gościnne i rozmowy bez skrótu są dostępne operatorowi.
- PDF jest pobierany przez uwierzytelnione `fetch`; nie wymaga publicznego linku.

## Konfiguracja i uruchomienie

Control Center przy następnym uruchomieniu lokalnych usług uzupełnia zaszyfrowany
profil SOPS o brakujące poświadczenia, zachowując istniejące wartości. Nie wypisuje
sekretów. Panel web odczytuje poświadczenie operatora na serwerze; nie przesyła go
do przeglądarki ani do usług zewnętrznych. Odczyt SQL awaryjny wymaga uprzedniej
weryfikacji sesji operatora przez API; uzupełnianie magazynu nie omija API przez SQL.

| Konfiguracja | Odbiorca |
| --- | --- |
| `PLATFORM_<NAZWA_USŁUGI>_TOKEN` | Skarbiec/Compose; osobna losowa wartość dla każdej z 14 usług |
| `PLATFORM_SERVICE_TOKEN` | Proces/kontener konkretnej usługi: wyłącznie jej własna wartość |
| `PLATFORM_SERVICE_CREDENTIALS_JSON` | Tylko Customer Service: mapa nazwa usługi → poświadczenie |
| `PLATFORM_OPERATOR_TOKEN` | Customer Service i backend Control Center |
| `PLATFORM_AUTH_URL` | Prywatny adres Customer Service |
| `PLATFORM_ORDER_URL` | Prywatny adres Order Service do sprawdzania właściciela |

Compose i istniejące szablony Kubernetes przekazują poświadczenia per usługa.
Skrypty uruchamiania usuwają cudze poświadczenia, klucz operatora i klucze podpisu
JWT ze środowiska innych procesów. Dla samodzielnego uruchamiania poza Control
Center trzeba dostarczyć te zmienne z własnego secret managera. Generator
`scripts/security/credentials.py <prywatny-plik.env>` tworzy brakujące wartości.
Rejestr i poświadczenia muszą być zgodne; rotację należy wdrożyć do odbiorców.

Moduły usług pozostają osobno wdrażalne, ale mają wspólną zależność budowania:

```bash
./mvnw -pl order-service -am test
./mvnw -pl platform-security -am -DskipTests install
# Następnie możliwe jest samodzielne spring-boot:run z dostarczoną konfiguracją.
```

Brak lub niepoprawny token daje 401; zweryfikowana tożsamość bez uprawnień 403.
Brak wymaganej konfiguracji lub awaria introspekcji daje 503 i blokuje dostęp.
W Customer Service poświadczenie operatora jest akceptowane wyłącznie na
przewidzianych odczytach; próba jego użycia do profilu/MFA nie uwierzytelnia klienta.
Walidacja danych żądania może zwrócić 400 przed sprawdzeniem właściciela.

Produkcja pozostaje niedostępna zgodnie z dotychczasową konfiguracją projektu.
Transport sekretów poza lokalnym środowiskiem wymaga przygotowanego TLS/prywatnej
sieci. Zakres nie zamyka osobnych ustaleń dotyczących płatności symulowanych,
ACL Redis, metryk/dokumentacji ani pozostałych problemów z audytu.

## Weryfikacja

```bash
./mvnw package
python3 -m unittest discover -s scripts/qa/unit -p 'test_platform_credentials.py'
bash scripts/qa/unit/platform-auth-test.sh
pnpm run test:unit
pnpm run test:ui
python3 scripts/qa/access-control-e2e.py
```

Ostatnie polecenie uruchamia 15 prawdziwych JAR-ów na innych portach oraz osobny
PostgreSQL, Redis i Mailpit w tymczasowych kontenerach. Tworzy własne bazy,
poświadczenia i dane. Sprawdza konta A/B, anonima, operatora, usługę, zakup klienta
i gościa, PDF/korekty/eksport, wishlist, zwroty, moderację i czat. Usuwa własne
procesy i kontenery po zakończeniu. Logi trafiają do ignorowanego `tmp/`.

Testy włączono do CI wraz z testami izolacji sekretów i regresją przeglądarkową.
