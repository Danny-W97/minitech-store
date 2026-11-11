# A38 — historia rozmowy i trwałe powiadomienia (26.10.2026)

Wykonano lokalnie na `Audit_0.1`. Dwie odpowiedzi operatora pozostają w historii
i tworzą odrębne powiadomienia. Nie wdrażano na dev/prod.

## Zachowanie

`chat_entries` przechowuje niezmienne wpisy klienta i obsługi. Oryginalna wiadomość
oraz ostatnia odpowiedź pozostają też w dotychczasowym DTO jako podsumowanie dla
starszych klientów. Trigger PostgreSQL odrzuca UPDATE/DELETE wpisu historii.
Zapis wpisu, podsumowania i zadania powiadomienia jest jedną transakcją.

Każdy nowy wpis ma własne `sourceId=entryId` i typ `CHAT_ENTRY_RECEIVED` lub
`CHAT_ENTRY_REPLIED`. Klucz `requestId` dla dalszej wiadomości/odpowiedzi jest
unikalny w rozmowie i roli autora. Ponowienie tej samej treści nie tworzy nowego
wpisu; inna treść z tym samym kluczem zwraca 409. Blokada rozmowy działa między
replikami. Pierwsze utworzenie rozmowy nie ma gwarancji idempotencji.

Sklep i CC pokazują chronologiczną historię i pobierają starsze strony na żądanie.
Sklep pozwala dopisać pytanie do tej samej rozmowy lub rozpocząć nową.
Odpytywanie trwa również po pierwszej odpowiedzi; zatrzymuje się przy zamknięciu
panelu i ukryciu karty. Błąd historii zachowuje ostatni widok, treść jest renderowana
bez wykonywania HTML. CC zachowuje szkic i klucz ponowienia po błędzie wysyłania;
szkice są oddzielne dla każdej rozmowy. Panel mieści się na 320 px; poprawiono
zawijanie paska statystyk przesuwającego przycisk czatu poza ekran. Ukryte pola
pozostają ukryte mimo reguł CSS, a komunikaty mają sprawdzony kontrast.

## API i dostęp

| Operacja | Endpoint | Dostęp |
| --- | --- | --- |
| Historia | `GET /chat/messages/{id}/entries?before=0&size=50` | Token rozmowy lub operator |
| Dalsze pytanie | `POST /chat/messages/{id}/entries` | Token rozmowy; operator ma dostęp administracyjny |
| Odpowiedź obsługi | `POST /chat/messages/{id}/reply` | Operator |
| Ponowienie przekazania | `POST /chat/messages/{id}/entries/{entryId}/retry-notification` | Operator, pasująca rozmowa i DEAD_LETTER |

Token rozmowy jest przekazywany w `X-Chat-Access-Token`. Sam e-mail lub zalogowanie
klienta nie zastępuje tego tokenu. Historia zwraca `entries`, `nextCursor`, `hasMore`;
wpisy jednej strony są rosnąco według ID, a kursor służy pobieraniu starszych wpisów.
Rozmiar strony wynosi 1–100. Żądania POST zawierają `message` do 4000 znaków
i opcjonalny `requestId` (1–100 znaków: litery, cyfry, `.`, `_`, `:`, `-`).
Brak klucza oznacza nowe żądanie; własny klient powinien zachować go do ponowienia.

## Powiadomienia i obsługa awarii

Outbox Chat ma PENDING, RETRY, ACCEPTED i DEAD_LETTER. Dwie lokalne jednostki
wykonawcze obsługują ograniczone partie; rezerwacja ma token i domyślnie 30 s.
Wywołanie HTTP odbywa się poza transakcją bazy. Restart odzyskuje wygasłą
rezerwację, a stary wykonawca nie nadpisuje wyniku nowszego. Domyślnie po 20
nieudanych próbach zadanie wymaga operatora. Odstęp ponowień rośnie do 300 s.

ACCEPTED oznacza trwałe przyjęcie przez Notification, a nie doręczenie e-maila.
SENT jest pokazywany, gdy otrzymane potwierdzenie zawierało SENT; Chat nie śledzi
późniejszych zmian statusu przyjętego zadania. Receipt musi pasować do typu,
źródła i odbiorcy. Poprawny receipt FAILED z HTTP 503 również kończy przekazanie.
Ogólny błąd 503 bez pasującego potwierdzenia podlega retry.

Notification deduplikuje wszystkie typy CHAT także przy FAILED/PENDING.
Po utracie odpowiedzi HTTP ponowienie odbiera istniejący wynik i nie wymusza
ponownej wysyłki SMTP. Dalsze błędy, wynik niepewny i ręczne ponowienia należą do
istniejącego retry/DLQ Notification. Nie deklaruje się dokładnie jednokrotnego
doręczenia przez zewnętrzny serwer pocztowy.

W CC WWW dostępne są historia rozmowy i akcje „Historia rozmowy” oraz
„Ponów przekazanie powiadomienia czatu”. Polecenia CLI:

```bash
bash scripts/control-center/control-center.sh chat-history 77
bash scripts/control-center/control-center.sh chat-history 77 123
bash scripts/control-center/control-center.sh chat-reply 77 'Odpowiedź' 'staly-klucz-ponowienia'
bash scripts/control-center/control-center.sh chat-notification-retry 77 123
```

`chat-notification-retry` dotyczy tylko nieprzekazanego DEAD_LETTER. Przekazanego
zadania nie tworzyć ponownie. Metryki `chat_notification` i `chat_notification_dlq`
podlegają istniejącym regułom zaległości, DLQ i brakującego kolektora.
Instrukcje: [A35 — alerty](A35_ALERTY_BIZNESOWE.md).

## Migracja i kolejność uruchomienia

Przed wdrożeniem zatrzymać zapis ze starych instancji Chat. Najpierw zaktualizować
Notification (nowe typy zdarzeń i migracja V10 rozszerzająca treść do 6000 znaków),
następnie Chat z V4 i dopiero dopuścić nowe zapisy oraz nowy frontend/CC.
Nie mieszać starych i nowych instancji zapisujących rozmowy. Nie wykonano tego
wdrożenia automatycznie ani nie uruchamiano migracji na bazach użytkownika.

V4 przenosi pierwszą wiadomość i ostatnią zachowaną odpowiedź. Wcześniejszych,
nadpisanych odpowiedzi nie można odtworzyć. Stare SENT nie są wysyłane ponownie;
pozostałe zadania zachowują stary typ/sourceId, aby odebrać istniejący wynik
Notification bez tworzenia duplikatu. Nowe wpisy używają oddzielnych typów.
Pełna treść do 4000 znaków wraz z nagłówkiem i podpisem mieści się w Notification.

## Dowody odbioru

- 95 testów Java: 71 platform-security, 15 Notification, 9 Chat.
- 129 testów Python, 26 testów JavaScript i 18 testów przeglądarkowych CC.
- Regresja sklepu: 73 testy Chromium, w tym 7 czatu; dodatkowo 21 testów czatu
  w Firefox, WebKit i mobilnym Chromium. Sprawdzono uzupełnianie luki po przerwie,
  kontrast otwartego panelu i szerokość 320 px; zrzuty znajdują się w raportach
  Playwright w `gitIgnoreOwn/runtime/playwright-a38-final` i `playwright-a38-other-final`.
- `scripts/qa/chat-history-e2e.py`: dwa rzeczywiste JVM Chat, Notification,
  PostgreSQL i własny Mailpit. 21 kontroli HTTP, migracja starszych danych,
  uprawnienia, równoległe klucze, niezmienność, rollback wpisu i outboxu,
  awaria/restart, utrata potwierdzenia, pełna treść, zachowanie ręcznego błędu
  SMTP oraz ponowienie DEAD_LETTER przez operatora.
- Raport: `tmp/audit-a38-2b32be83/report.json` — PASS. Nie wysłano poczty do
  zewnętrznych odbiorców. Własne JVM, kontenery i wolumeny testu posprzątano.
- `monitoring-check.py`: konfiguracje, 15 targetów i 12 scenariuszy PromQL — PASS.
  CI egzekwuje próbę A38 i archiwizuje raport/logi.

A05/Stripe oraz zmiany samych zdjęć pozostają odłożone decyzją użytkownika.
