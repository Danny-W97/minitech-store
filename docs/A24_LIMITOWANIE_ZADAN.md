# A24 — adres klienta i limitowanie żądań

## Budżet klienta

Gateway stosuje jeden wspólny budżet dla adresu klienta. Zmiana identyfikatora
produktu/zamówienia, metody HTTP, parametru query lub aliasu `/api`/`/api/v1`
nie tworzy nowego licznika. Limit obejmuje również nieznane i zakodowane ścieżki.
Preflight `OPTIONS`, Actuator, OpenAPI i Swagger UI są wyłączone z limitowania.
Ochrona logowania konta działa oddzielnie w Customer Service.

Budżet dotyczy IP, więc użytkownicy wspólnego NAT mogą go współdzielić.
Dostosuj limit do rzeczywistego ruchu i topologii, szczególnie za proxy.
Gateway nie wyprowadza tożsamości konta z niezweryfikowanych danych klienta.

## Konfiguracja

| Zmienna | Wartość domyślna / znaczenie |
| --- | --- |
| `GATEWAY_RATE_LIMIT_ENABLED` | `true`; profil `prod` wymusza włączenie |
| `GATEWAY_RATE_LIMIT_REQUESTS_PER_WINDOW` | `120` w dev/test, `300` w prod |
| `GATEWAY_RATE_LIMIT_WINDOW_SECONDS` | `60` |
| `GATEWAY_RATE_LIMIT_FAIL_OPEN` | `true` w dev/test; prod wymusza `false` |
| `GATEWAY_TRUSTED_PROXY_CIDRS` | Puste — brak zaufanych proxy. Lista adresów IP lub CIDR rozdzielona przecinkami |
| `GATEWAY_REDIS_CONNECT_TIMEOUT` | `500ms` |
| `GATEWAY_REDIS_TIMEOUT` | `500ms`; ogranicza oczekiwanie na polecenie Redis |

`server.forward-headers-strategy=none` pozostawia rzeczywisty adres połączenia
dostępny dla resolvera. Ustawienie `native` albo `framework` zatrzymuje start
Gateway; zaufanie konfiguruje się przez dedykowaną listę proxy.

## Zaufanie do proxy

Domyślnie liczy się wyłącznie adres połączenia. Nagłówki `X-Forwarded-For`,
`Forwarded` i `X-Real-IP` od zwykłego klienta nie zmieniają jego budżetu.

Jeżeli bezpośredni nadawca należy do `GATEWAY_TRUSTED_PROXY_CIDRS`, Gateway
sprawdza `X-Forwarded-For` od prawej strony. Przechodzi przez znane proxy
i zatrzymuje się na pierwszym niezaufanym adresie. Nie wybiera bezwarunkowo
pierwszej pozycji z lewej. `Forwarded` i `X-Real-IP` nie są źródłem adresu limitera.

Lista musi obejmować wyłącznie rzeczywiste proxy, które dopisują adres
połączenia albo nadpisują niezaufane dane wejściowe. Repozytoryjny Nginx używa
`$proxy_add_x_forwarded_for`. Nie ufaj automatycznie całej sieci prywatnej,
wszystkim kontenerom ani adresom klientów. Nazwy DNS i zakresy `/0` są odrzucane.
Resolver obsługuje literalne IPv4/IPv6 bez zapytań DNS i normalizuje adresy.
Przy IPv4 mapowanym do IPv6 konfiguruj zakres IPv4 z jego maską.

Przykład zapisu konfiguracji dla dwóch konkretnych proxy — adresy poniżej są
przykładowe i trzeba je zastąpić rzeczywistymi:

```text
GATEWAY_TRUSTED_PROXY_CIDRS=192.0.2.10/32,2001:db8::10/128
```

Łańcuch od zaufanego proxy ma maksymalnie 16 adresów i 2048 znaków. Błędny,
niejednoznaczny lub nadmierny nagłówek daje 400 bez wywołania backendu, również
przy `fail-open`. Nie powoduje przełączenia do nowego, zastępczego budżetu.
Bez nagłówka liczy się adres proxy. Za nieskonfigurowanym proxy wszyscy jego
klienci współdzielą budżet proxy — jest to bezpieczna konfiguracja początkowa,
którą trzeba świadomie dostosować przed publicznym wdrożeniem.

## Redis i awarie

Jeden skrypt Lua wykonuje odczyt/zwiększenie licznika oraz ustawienie lub
naprawę TTL. Limit jest wspólny dla wszystkich instancji Gateway korzystających
z tego Redis i tej konfiguracji. Nowy licznik rośnie do `limit + 1`, a potem
nie zwiększa się przy kolejnych odmowach. Żądania nie przesuwają istniejącego
terminu wygaśnięcia. Licznik bez TTL otrzymuje termin; uszkodzona wartość tekstowa
jest blokowana na jedno okno zamiast otwierać nowy budżet.

Klucze mają prefiks `security:rate-limit:v2:` i kodowany adres. Stare liczniki
per ścieżka nie sterują już nowym limiterem. Zwykłe stare klucze wygasają według
dotychczasowego TTL. Historyczne klucze bez TTL można wygasić podczas kontrolowanej
konserwacji tego prefiksu; aktualizacja nie czyści wspólnej bazy Redis.

Przekroczenie limitu daje 429 oraz `Retry-After`, `X-RateLimit-Limit`,
`X-RateLimit-Remaining` i `X-RateLimit-Reset`. Reset oznacza pozostałą liczbę
sekund do końca okna, nie czas Unix. Nagłówki są dostępne dla przeglądarki przez
CORS; odpowiedzi API 400/429/503 zachowują CORS dla dozwolonego originu.

Przy awarii Redis:

- `fail-open=false`: odpowiedź 503, bez wywołania backendu;
- `fail-open=true`: żądanie wykonuje się raz, bez działającego limitu Redis.

Blok `catch` obejmuje wyłącznie decyzję limitera. Błąd dalszego handlera nie
powoduje ponowienia operacji. Timeouty ograniczają oczekiwanie na niedostępny Redis.

## Wdrożenie i testy

Przebuduj i uruchom ponownie wszystkie instancje Gateway. Wdrażaj konfigurację
limitów spójnie; stara wersja używa innego klucza i nie współdzieli nowego budżetu.
Nie ma migracji PostgreSQL. Compose i Kubernetes mają jawną, domyślnie pustą
konfigurację zaufanych proxy.

```bash
./mvnw -pl api-gateway -am package
python3 scripts/qa/rate-limit-e2e.py
python3 scripts/qa/access-control-e2e.py
```

Test A24 uruchamia trzy spakowane Gateway, własny Redis oraz backend HTTP
zliczający skutki. Sprawdza 64 równoległe żądania na dwóch instancjach, dokładnie
10 dopuszczonych operacji, wygaśnięcie/naprawę TTL, spoofing i łańcuch proxy,
warianty tras, CORS i oba tryby awarii Redis. Zasoby są odizolowane od dev.
