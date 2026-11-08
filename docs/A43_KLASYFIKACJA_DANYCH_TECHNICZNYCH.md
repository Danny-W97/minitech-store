# A43.30 — klasyfikacja danych technicznych i pominięć

Aktualizacja zamknięcia A43.34 (29.10.2026): 70 tabel/666 kolumn,
145 migracji, 62 unikalności, 41 mechanizmów i 17 kategorii kopii.
Minimalny wniosek czatu zawiera tylko ID rozmowy/wniosku, MANUAL_REVIEW
i datę. Każdy odczyt wymaga tokenu rozmowy, bez danych konta lub treści.
Retencja A43.40 i ręczna procedura A43.39 pozostają wymagane; całościowe
możliwości pending. [Zakres](A43_USUWANIE_PROFILU_I_TRESCI.md).


## Stan rdzenia A43.34 przed domknięciem — 29.10.2026 (historyczny)

Macierz obejmuje teraz 69 tabel/662 kolumny i 144 migracje. Dodano
klucze recenzji i bezpieczny postęp czyszczenia; 60 unikalności chroni
deduplikację. Polityka/pending/attempts pozostają stanem technicznym.
Redis: tylko ustawienia po ID są usuwane; challenge TTL i limity e-maila
pozostają bez zmian. Etap otwarty do decyzji czatu, aktywacja domyślnie
wyłączona. [Zakres](A43_USUWANIE_PROFILU_I_TRESCI.md).

## Aktualizacja A43.33 — 29.10.2026

Bieżąca macierz: 68 tabel/653 kolumny, 142 migracje i 17 kategorii kopii.
Nowe żądania, kroki i ID-only znaczniki otrzymały osobne klasyfikacje.
Hash statusu to materiał uwierzytelniający; run tokeny/lease i klucze
pozostają wyłączone. Wybrany przez użytkownika, ograniczony status jednego
żądania po capability jest nowym kontraktem A43.33; nie otwiera starych
stanów technicznych z decyzji A43.30. Znaczniki pozostają pseudonimowe,
a retencja oczekuje na A43.40. Zachowano 59 zobowiązań unikalności.
[Protokół i odbiór](A43_PROTOKOL_USUWANIA_KONTA.md).

## Decyzja i granice A43.30

Decyzja użytkownika z 28.10.2026: **istniejące eksporty danych biznesowych
+ ręczna ocena pozostałych danych osobowych**, bez nowych eksportów stanów
technicznych. Surowe kopie, klucze deduplikacji, sekrety i cudze dane nie
trafiają automatycznie do pliku klienta. Nie oznacza to usunięcia tych danych
ani uznania, że każdy rekord techniczny jest anonimowy.

Macierz [technical-data-classification.json](../scripts/qa/contracts/technical-data-classification.json)
przypisuje właściciela, dowód relacji, decyzję, uzasadnienie i źródło odbioru
każdej tabeli/kolumnie, pięciu magazynom Redis, piętnastu kategoriom kopii
oraz literalnym pominięciom istniejących eksportów. Nie zastępuje jej
sam status pending. Checker odtwarza kolumny z migracji, odrzuca nierozpoznane
DDL i wykrywa nieprzejrzane zmiany pól, pominięć oraz źródeł tożsamości zdarzeń.
Przejrzane źródła wszystkich projekcji są także chronione hashami, więc
zmiana pominięcia przez nowy helper lub dynamiczną konstrukcję wymaga oceny.

Decyzje macierzy:

| Decyzja | Znaczenie |
| --- | --- |
| exported | Pole należy do istniejącej bezpiecznej projekcji, z jej warunkami własności i zakresu |
| qualification-only | Pole służy relacji/kwalifikacji; jego odczyt nie nadaje samodzielnie uprawnień i nie oznacza wydania |
| covered-copy | Biznesowy odpowiednik jest dostępny w projekcji źródłowej; unikalne różnice kopii historycznej wymagają ręcznej oceny |
| manual-review | Weryfikacja podmiotu, ograniczony odczyt, ocena wszystkich osób i redakcja według procedury A43.29 i poniżej |
| excluded-secret | Sekrety, poświadczenia i ich hashe nie są wydawane, również jako załącznik ręczny |
| excluded-operational | Stan wykonawczy/klucz/lease/licznik nie jest nowym eksportem biznesowym; osobisty kontekst konkretnej sprawy podlega ograniczonej ocenie ręcznej |
| not-account-scoped | Globalna konfiguracja/katalog/licznik nie stanowi własnego rekordu klienta; brak relacji nie otwiera tabeli |
| separate-copy-procedure | Surowe kopie/backupy/dostawcy poza automatycznym eksportem; konkretna procedura i dalsze etapy A43.37–A43.39 pozostają wymagane |

Pole exported nie oznacza wydawania wszystkich wierszy. Szczególnie audyt
ma listę dopuszczonych zdarzeń, a Notification obejmuje tylko odpowiednie
typy Order/Payment lub odpowiedzi do rozmówcy z tokenem i zgodnym odbiorcą.
Pozostałe rodzaje nie dziedziczą tych uprawnień przez zgodny numer/e-mail.

## Grafy źródła i obsługa pozostałych danych

| Źródło | Dowód i decyzja |
| --- | --- |
| order_sagas | order_id → aktualny właściciel Order; stan biznesowy zamówienia w eksporcie Order, wykonawcze próby/lease/wersje wyłączone, unikalny opis błędu tylko ręcznie |
| payment_follow_ups / payment_order_gates | order_id → Order; skutki w Payment, Billing, Delivery i Promotion. Nie wydawać całego payloadu zadania ani nie uruchamiać go do sporządzenia eksportu |
| payment_outbox_events | order_id → Order; dane biznesowe odpowiednio Payment/Order/Notification. Event key zawiera e-mail, a hashe/ID są linkowalne; nie wydawać i nie zmieniać ich w tym etapie |
| stripe_webhook_receipts | payment_id → payments.order_id → Order, jeśli payment_id istnieje i pochodzi ze zweryfikowanej obsługi. Brak ID/payload po odrzuceniu nie jest dowodem własności |
| Billing event_inbox | ACCEPTED: related_id jest orderId, nie notificationId. Wymaga sprawdzenia typu/statusu oraz Order. REJECTED: payload i error mogą być niezweryfikowane; bez automatycznego dopasowania |
| invoice_outbox_events | Payload sprawdzany względem konkretnej istniejącej faktury → order_id → Order. Same event_id lub adres odbiorcy nie dowodzą własności. Faktura/pozycje/korekty/PDF mają istniejące projekcje |
| Notification event_inbox / DLQ | ACCEPTED inbox: related_id → notifications.id, następnie graf source typu wiadomości. DLQ: notification_id i źródło wiadomości; treść/error mogą mieszać osoby, więc tylko ograniczona ocena ręczna |
| chat_notification_outbox | entry_id → chat_entries.conversation_id → dowód tokenu rozmowy. Legacy source_id oznacza rozmowę, nowe typy wpis. Nie zastępować capability przez JWT/e-mail; nie ujawniać adresów/treści obsługi |

Powiązanie jest wskazówką dla uprawnionego operatora, nie nowym publicznym
API. Nie należy nadawać prawa dostępu przez event_id, operation_id, checksum,
payload_hash, provider reference, sam related_id lub zgodność recipient.
Obecne biznesowe stany to zapis domenowy, nie gwarancja zakończenia każdego
zadania. Nie symulujemy brakujących skutków ani nie rekonstruujemy treści
skompaktowanej/usuniętej. W razie rozbieżności kopii sprawdza się konkretny
fragment i czas, zamiast twierdzić, że dowolna historyczna kopia jest identyczna.

## Powiadomienia weryfikacji i resetu

CUSTOMER_REGISTERED.source_id oznacza customerId. PASSWORD_RESET_REQUESTED
wskazuje **id tokenu resetowania**, nie customerId: relacja prowadzi przez
password_reset_tokens.customer_id. Wpis mógł zostać już usunięty przez
retencję — brak rekordu nie uprawnia do odgadywania właściciela z adresu.
Po potwierdzeniu relacji operator może ocenić typ, datę i stan doręczenia
konkretnego powiadomienia. Tych metadanych nie dodano do automatycznego API.

Linki weryfikacji/resetu, tokeny i ich hashe są wyłączone. Surowy subject/
message może zawierać aktywny sekret; nie kopiować go do pliku roboczego
ani manifestu dla klienta. Istniejący audyt A43.29 pokrywa bezpieczne zdarzenia
bez obietnicy pełnej historii doręczenia. Nie przypisuje się historycznych
powiadomień po e-mailu i nie tworzy nowego subject ID na podstawie odbiorcy.
Wybór A43.27 nadal wyłącza automatyczne komunikaty i metadane do obsługi.

## Redis

- **payment-stream / invoice-stream:** duplikaty payloadów i trwałe
  pending/consumer groups są częścią protokołu. Biznesowe dane mają
  projekcje domenowe, a unikalny kontekst sprawy wymaga ręcznego dowodu
  Order/faktury/wiadomości. Nie wykonywać dumpu streamu jako eksportu konta.
- **login-protection:** klucz zawiera znormalizowany e-mail, liczniki/TTL
  dotyczą ochrony. Bieżący adres konta nie dowodzi całej historii tego
  adresu. Unikalny kontekst ocenia się ręcznie bez ujawniania cudzych prób.
- **mfa-challenges:** sekrety/challenge i aktywne konfiguracje nie są
  eksportowane. Bezpieczne zapisane metadane są w Customer A43.29.
- **gateway-rate-limit:** klucze IP obejmują współdzielone adresy/NAT i
  nie są relacją konta. Brak globalnego skanu/eksportu liczników dla klienta.

Nie wykonano SCAN/KEYS, XRANGE, XACK, XTRIM, DEL, zmiany TTL ani odczytu
rzeczywistego Redis. Odbiorcy streamów, okresy i aktywacje pozostają decyzjami
późniejszych etapów. RDB/AOF i backup to kopie, których nie usuwa TTL klucza.

## Procedura ograniczonej oceny technicznej

1. Potwierdź konto lub token rozmowy i zakres konkretnego wniosku zgodnie
   z [A43.29](A43_OCENA_NOTATEK_I_DANYCH_BEZPIECZENSTWA.md).
2. Sprawdź graf z tabeli powyżej z uwzględnieniem typu, statusu i czasu.
   Dla odrzuconego lub niepowiązanego payloadu zapisz brak dowodu; nie
   dopasowuj konta po e-mailu, nazwisku, numerze z raw JSON ani hashu.
3. Najpierw użyj istniejącej projekcji biznesowej. Jeżeli kopia zawiera
   unikalne informacje o wnioskującym, pobierz tylko potrzebne niesekretne
   pola w trybie odczytu, przy ograniczonym zakresie/stronach. Nie eksportuj
   całego zadania, inboxu, DLQ, logu, Redis ani backupu.
4. Usuń sekrety, identyfikatory/części dotyczące innych osób i informacje
   operacyjne niepotrzebne do wyjaśnienia. Zastosuj semantyczną ocenę
   i kontrolę wynikowego pliku z A43.29; sam regex lub hash nie wystarcza.
5. Zapisz w manifeście per pole: źródło, dowód podmiotu, pokrycie przez
   projekcję / wydanie po redakcji / wyłączenie z powodem / brak dowodu.
   Przekaż wyłącznie sprawdzoną kopię uzgodnionym bezpiecznym kanałem.

Nie wolno uruchamiać replay/reconcile/refundacji, wysyłać e-maila, odnawiać
lease ani usuwać rekordu, aby ustalić jego treść lub „sprawdzić” deduplikację.
Ocena i odmowa wydania klucza nie oznaczają zgody na jego usunięcie.

## Kopie i tożsamość zdarzeń

Każda z piętnastu kategorii kopii ma decyzję w macierzy. Backupy/keyrings/
sesje i ustawienia sekretów nie są surowym eksportem osoby. Logi, AI/provider,
monitoring, cache PDF i pobrania wymagają weryfikacji podmiotu oraz właściwej
procedury A43.37–A43.39; nie deklarujemy ich selektywnego usunięcia lub
automatycznego pozyskania. Pseudonimizacja nie oznacza anonimowości.

Osobny [plan zgodności identyfikatorów A43.36](A43_PLAN_MIGRACJI_TOZSAMOSCI_ZDARZEN.md)
opisuje e-mail w event_key, fingerprinty, czytniki v1/v2, kopie i dowody
jednokrotnego skutku. Plan nie jest wdrożeniem migracji. Istniejące ID,
payload_hash, unikalności, bramki, tombstones i retencje pozostają bez zmian.

Ten etap nie tworzy koordynatora konta (A43.31), UI (A43.32), procedury
usuwania (A43.33–A43.36), obsługi kopii (A43.37–A43.39) ani polityk/
końcowego odbioru (A43.40–A43.41). Globalne możliwości pozostają pending.
A05, całe A41 i zmiany zdjęć nadal są odłożone.


## Uzupełnienie A43.39 — 30.10.2026

Bieżąca macierz maszynowa obejmuje 46 mechanizmów i 20 kategorii kopii.
Dodano `assistant-egress-approvals`: ograniczony rejestr skrótów zgód w RAM CC,
jedna zgoda na sesję, 64 wpisy, 5 minut, bez treści i kluczy. Materiał autoryzacji
wyłączony z eksportu; nie dowodzi własności danych osób w wiadomości.
`assistant-provider-copy`, `mail-payment-providers`, `browser-downloads` mają
[procedurę A43.39](A43_DOSTAWCY_PLIKI_I_DOWODY_CZATU.md). Wymagane dowody
runtime i dostawców pozostają ręczne; A05 odłożone. Schemat bez zmian:
85 tabel, 749 kolumn, 163 migracje. Pełne A43 i usunięcie nadal niepotwierdzone.
