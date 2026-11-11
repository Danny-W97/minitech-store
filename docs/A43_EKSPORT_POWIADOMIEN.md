# A43.27 — eksport powiadomień

## Stan etapu

Decyzja użytkownika z 28.10.2026: **tylko powiadomienia do rozmówcy**, z treścią
i statusem odpowiedzi dla klienta. Komunikaty do obsługi pozostają poza
eksportem, również ich metadane. Implementacja obejmuje powiadomienia
Order/Payment i osobny eksport czatu przez token rozmowy; odbiór opisuje audyt.

## Powiadomienia do rozmówcy

`GET /api/v1/notifications/chat/data-export/CHAT_REPLY_SENT/{conversationId}`

`GET /api/v1/notifications/chat/data-export/CHAT_ENTRY_REPLIED/{entryId}`

Obie ścieżki przyjmują `size=1..50` i `cursor=0`, wymagają nagłówka
`X-Chat-Access-Token` i działają także przez `/api` oraz bezpośredni port.
Identyfikatory rozmowy i wpisów można odczytać z eksportu A43.26.
Wariant historyczny używa id rozmowy, nowy wariant id wpisu autora SUPPORT.
Każde pobranie ponownie weryfikuje token i dokładny typ źródła w Chat.

Dostęp ma gość lub klient z tokenem danej rozmowy; konto i e-mail same nie
uprawniają do pobrania. Inny klient posiadający prawidłowy token ma dostęp
zgodnie z wybranym modelem capability. Operator i usługi nie mają obejścia.
Typy `CHAT_MESSAGE_RECEIVED` i `CHAT_ENTRY_RECEIVED` kierowane do obsługi
oraz nieznane typy zwracają 404. Metadane wewnętrznych powiadomień nie są
eksportowane. Po potwierdzeniu tokenu dodatkowy filtr SQL dopuszcza wyłącznie
odbiorcę zgodnego z adresem rozmówcy zapisanym w rozmowie. Nie udostępnia
wiadomości błędnie oznaczonej typem odpowiedzi, lecz wysłanej na inny adres.
Zgodność odbiorcy jest filtrem zakresu po dowodzie dostępu, nie podstawą
uprawnień. Niezgodne rekordy są jawnie wyłączone w manifeście. Nie zmienia
się adresów i nie stosuje dopasowania do e-maila zalogowanego konta.
Brak tokenu/niepoprawny format daje 401; niewłaściwy token,
inny autor wpisu, brak źródła lub hash null daje 404; awaria Chat daje 503.
Źródło z prawidłowym dowodem bez zapisanych powiadomień zwraca pustą stronę.

## Powiadomienia zamówienia i płatności

`GET /api/v1/notifications/me/data-export/orders/{orderId}?size=50&cursor=0`

Działają również `/api` i bezpośredni port Notification. Wymagany jest JWT
klienta z dodatnim identyfikatorem. Notification sprawdza właściciela przez
`GET /internal/orders/{id}/access` przy każdym pobraniu strony, wysyłając
własną tożsamość usługi. Nie przekazuje JWT klienta ani tokenu zamówienia.
Odbiorca wiadomości, e-mail w JWT, customerId w query i nagłówki podające
tożsamość nie zastępują dowodu własności.

Do tego grafu należą wyłącznie `ORDER_CREATED`, `ORDER_STATUS_CHANGED`,
`PAYMENT_PENDING`, `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED`. Dla zdarzeń
płatności `source_id` oznacza **orderId**, nie paymentId. Identyczny numer
rozmowy, wpisu czatu lub tokenu resetowania hasła nie kwalifikuje ich
powiadomień do eksportu zamówienia.

Operator i usługi nie mają obejścia kontroli właściciela. Zamówienie innej
osoby, gościa lub brakujące daje 404; niedostępne źródło uprawnień daje 503.
Puste powiadomienia własnego zamówienia zwracają poprawną pustą stronę.

## Projekcja i ograniczenia

Plik zawiera identyfikatory powiadomienia i źródła, typ, odbiorcę, kanał,
zachowany temat i treść, zapisany status oraz daty. `contentDisposition`
rozróżnia `RETAINED` i `COMPACTED`; treść skompaktowana pozostaje null,
z datą `contentCompactedAt`. Eksport nie rekonstruuje treści.

Wyłączono provider_reference, failure_reason, liczniki prób, correlation_id,
leases, inbox, DLQ i dane załączników. PDF faktury ma własny eksport A43.25.
Wiadomości bezpieczeństwa konta są wyłączone: zawierają aktywne lub
historyczne linki weryfikacji/resetowania, a źródłem resetu jest identyfikator
tokenu, nie konta. Nie wnioskujemy właściciela przez recipient. Surowe payloady
i kopie workflow podlegają ocenie w A43.30.

Treść i odbiorca są zwracane tak, jak zapisano. Nie ma semantycznej redakcji
tekstu ani założenia, że odbiorca jest adresem konta. `complete=false` i lista
pominięć określają częściowy zakres. Pobieranie nie wysyła ani nie ponawia
wiadomości, nie uruchamia retencji i nie zapisuje archiwum po stronie serwera.

Strona zawiera 1–50 rekordów, posortowanych malejąco po id, z kursorem
ostatniego id. `nextCursor` jest tekstem; `0` kończy listę. Użyto istniejącego
indeksu `(source_id,id DESC)` i dwusekundowego limitu SQL, bez migracji.
Strony nie są wspólną migawką. Pobranie ma `private, no-store`, `nosniff`
i stałą nazwę załącznika. Pobrane kopie pozostają pod kontrolą użytkownika.

## Weryfikacja źródła czatu

Wewnętrzny `GET /internal/chat/notification-sources/{eventType}/{sourceId}/access`
wymaga jednocześnie tożsamości `notification-service` oraz nagłówka
`X-Chat-Access-Token`. Nie służy do wyliczania rozmów konta. Zwraca tylko
eventType, sourceId, conversationId i recipientHash. Skrót SHA-256 adresu
rozmówcy służy wyłącznie serwerowemu filtrowi SQL; nie jest zwracany przez
publiczny eksport i nie daje prawa dostępu. Wewnętrzna projekcja nie zawiera
tekstu, jawnego adresu ani hasha tokenu. Skrót odbiorcy jest danymi
pseudonimizowanymi, nie anonimowymi. Wykorzystano wbudowane funkcje
PostgreSQL SHA-256/UTF8, bez rozszerzeń i migracji.

`CHAT_REPLY_SENT` wskazuje historyczny identyfikator rozmowy.
`CHAT_ENTRY_REPLIED` wskazuje wpis; sprawdzany jest jego autor SUPPORT
oraz hash tokenu rozmowy. Inne typy są odrzucane. Brak źródła,
niezgodny autor, zły token i rozmowa bez hasha dają 404. Brak lub niepoprawny
format tokenu daje 401. Inne role/usługi nie mają dostępu.

Notification korzysta z ograniczonego klienta HTTP i własnego tokenu usługi;
źródło jest sprawdzane ponownie przy każdym żądaniu, bez cache i dopasowania
po e-mailu. Jest to dowód między usługami, nie transakcja obejmująca dwie bazy.
Bezpośredni eksport rozmowy A43.26 zachowuje swój istniejący kontrakt.

## Konfiguracja i testy

Nie dodano sekretów ani migracji. Używane są istniejące `ORDER_SERVICE_URL`,
`PLATFORM_ORDER_URL` i `CHAT_SERVICE_URL`; Compose otrzymał adres Chat dla
Notification, a graf Kubernetes krawędzie Notification → Order i Chat.
Nie uruchomiono wdrożenia ani restartu środowiska użytkownika.

Testy uruchamiają własne bazy PostgreSQL i procesy JAR z losowymi portami
oraz poświadczeniami. Skrypt `scripts/qa/notification-data-export-e2e.py`
wyłącza wyłącznie w fixture health check poczty, aby licznik połączeń SMTP
mierzył wysyłki z testowanych operacji, bez technicznych sond dostępności.
Sprawdza obie domeny, bezpośredni port i obie ścieżki Gateway, kolizje źródeł,
brak wpływu na dane, zero połączeń SMTP oraz awarie źródeł uprawnień.
Jest zarejestrowany w CI. Wynik odbioru i logi znajdują się w audycie.
