# A43.26 — osobny eksport czatu przez token rozmowy

## Wybrany wariant

**Decyzja użytkownika z 28.10.2026, podjęta w oknie z opcjami:** osobny
eksport przez token rozmowy. Zachowano istniejący model bez przypisywania
rozmów do kont i bez dopasowania po e-mailu. Nie dodano customer_id,
migracji, wiązania rozmów historycznych ani listy rozmów konta.

Posiadacz tokenu może pobrać:

- `GET /api/v1/chat/conversations/{conversationId}/data-export` — nagłówek;
- `GET /api/v1/chat/conversations/{conversationId}/data-export/entries?size=50&cursor=0`
  — stronę zapisanych wiadomości.

Działają także `/api` i bezpośredni port Chat. W obu żądaniach wymagany jest
nagłówek `X-Chat-Access-Token` z tokenem danej rozmowy: 43–128 znaków
alfanumerycznych, `_` lub `-`, zgodnie z istniejącym formatem capability.
Nie przekazuje się tokenu w URL, query ani pobieranym pliku.

## Dostęp i powiązanie z kontem

Eksport jest dostępny gościowi lub zalogowanemu klientowi z prawidłowym
tokenem rozmowy. JWT sam nie uprawnia do pobrania. E-mail/nazwa w rozmowie,
parametry customerId i nagłówki tożsamości nie dowodzą własności.
Operator oraz token usługi są odrzucani również przy poprawnym capability;
dotychczasowe funkcje obsługi klienta pozostają osobnym kontraktem.

Token daje dostęp niezależnie od tożsamości zalogowanego konta: inny klient
z prawidłowym tokenem może pobrać rozmowę, zgodnie z wybranym modelem.
Nie jest to eksport konta. Wylogowanie nie unieważnia osobnego tokenu
rozmowy. Bez nagłówka Authorization odczyt capability nie wymaga działającego
Customer; przekazany JWT nadal podlega zwykłej weryfikacji platformy.

Brak tokenu lub nieprawidłowy format daje 401 przed odczytem bazy.
Błędny token o poprawnym formacie, brak rozmowy i historyczna rozmowa bez
zapisanego access_token_hash dają jednakowe 404. Nie ma odzyskiwania dostępu
na podstawie samego e-maila; brak dowodu wymaga procedury A43.39.

## Zakres danych

Nagłówek ma schemaVersion 1, scope chat-conversation, complete false,
generatedAt, authorizationMode conversation-capability, conversation i omissions.
Conversation zawiera ID, podaną nazwę i e-mail rozmówcy, temat/status,
zapisane kopie initialMessage/latestReply oraz czasy utworzenia/zmiany/odpowiedzi.
Nazwa i e-mail są danymi podanymi w rozmowie, nie potwierdzoną tożsamością konta.

Strona ma scope chat-entries-page, ten sam authorizationMode,
conversationId, entries, hasMore, nextCursor i omissions. Wpis obejmuje ID,
rolę author CUSTOMER/SUPPORT, flagę initialMessage, treść i createdAt.
SUPPORT oznacza publiczną wiadomość do klienta; eksport nie otwiera
prywatnych danych operatorów. SQL jawnie dopuszcza tylko te dwie role.

SQL używa hasha tokenu do kwalifikacji rozmowy, ale nie zwraca hasha
w projekcji. DTO nie zawiera tokenów/hashy, request_key, statusów wysyłek,
lease, błędów ani danych chat_notification_outbox. Kontrola dostępu i odczyt
strony wpisów odbywają się w jednym SQL przez CTE i lateral join.

Tekst pozostaje jak zapisano, bez semantycznej redakcji, i może zawierać
dane innych osób. Eksport nie usuwa sekretów wpisanych przez rozmówcę
w samej treści. Brakujące daty/odpowiedzi pozostają null. Starsze wpisy
migracyjne mogą mieć czas odpowiedzi oparty na historycznym updated_at;
zwracana jest zapisana wartość, bez odtwarzania pierwotnej daty.

Kopie nagłówka i historia są eksportowane osobno, bez dopisywania sztucznych
zdarzeń i deduplikowania treści. Pusta, prawidłowo chroniona rozmowa daje
200 z pustą listą wpisów; stare rozmowy mogą mieć niepełną historię.

## Odczyt i kopie

Rozmiar strony 1–50, pierwsza strona cursor 0. Używać tekstowego nextCursor
bez zmiany, gdy hasMore jest true. Wpisy są czytane malejąco po ID,
maksymalnie size + 1, przez istniejący indeks (conversation_id, id DESC).
Timeout SQL wynosi 2 s. Token jest sprawdzany przy każdym żądaniu, również
po zmianie strony. Osobne strony nie stanowią wspólnej migawki.

Stałe załączniki: chat-conversation-data.json i chat-entries-data-page.json;
private/no-store, attachment i nosniff. Serwer nie tworzy archiwum na dysku,
a pobrany plik pozostaje kopią klienta. Brak nowego przycisku sklepu lub
widoku CC. Ten etap nie zmienia TTL/retencji i nie wysyła powiadomień.

## Weryfikacja

```sh
./mvnw --batch-mode -pl chat-service,api-gateway -am -Pintegration verify
python3 scripts/qa/chat-data-export-e2e.py
python3 scripts/qa/data-lifecycle-check.py
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
```

PostgreSQL: dwa tokeny i rozmowy ze wspólnym e-mailem/nazwą, 105 wpisów,
zmiana tokenu między stronami, puste i niechronione rozmowy, niezmienność
historii/outboxu. Rola projekcji nie ma SELECT request_key, statusów
powiadomień i outboxu; ma dostęp do access_token_hash wymagany w WHERE,
choć hash nie jest częścią wybieranych pól ani odpowiedzi.

Odbiór JAR używa własnych Customer/Chat/Gateway, losowych portów i osobnych
baz. CHAT_OUTBOX_ENABLED=false dotyczy tylko fixture; lokalna atrapa
Notification musi otrzymać zero żądań. Po zatrzymaniu własnego Customer
eksport anonimowy z capability nadal ma działać przez trzy ścieżki.

Pełny eksport konta nie będzie automatycznie dołączać tych rozmów przez
e-mail. Koordynator A43.31 musi jawnie rozliczyć osobny dowód capability.
Usuwanie, kopie, redakcja i procedury bez dowodu własności pozostają otwarte.
A05, całe A41 oraz zmiany samych zdjęć nadal odłożone.


<!-- A43_26_RESULTS -->
## Odbiór lokalny 28.10.2026

- **184 Java — PASS, bez pominiętych**: 125 platform, 33 Gateway,
  16 Chat unit i 10 PostgreSQL. W sumie 18 nowych testów.
- **246 Python — PASS, bez pominiętych**, w tym istniejące 12 Kubernetes.
- **JAR HTTP — PASS**, siedem grup: Chat, `/api`, `/api/v1`, brak zmian
  trzech tabel, OpenAPI, odczyt capability po zatrzymaniu Customer i zero
  żądań do lokalnej atrapy Notification. Pierwsze oczekiwanie POST gościa
  poprawiono z 403 na 401; ponowiono cały HTTP, bez zmian kodu Java/kontraktu.
- Rejestr — PASS: 14 baz, 49 tabel, 123 migracje, 33 mechanizmy.
  Maven — PASS, 17 modułów. Odbiór dodany do CI; bez zdalnego runu.

Raport: `tmp/audit-a43-26/report.json`. Logi:
`tmp/a43-26-maven-verify.log`, `tmp/a43-26-python.log`,
`tmp/a43-26-http-e2e.log`. HTTP: `tmp/chat-export-53be6172/report.json`.
Bez danych użytkownika, zmian historii, powiązań konta, wysyłek/retencji,
restartu CC, budowania obrazów aplikacji i wdrożenia dev/prod.
<!-- /A43_26_RESULTS -->


## Uzupełnienie A43.39 — 30.10.2026

Ręczna ocena alternatywnych dowodów konkretnej rozmowy, bez dopasowania po
e-mailu i odzyskiwania tokenu; niewystarczający dowód pozostawia sprawę otwartą.
Po fizycznym usunięciu rozmowy publiczne API traci dostęp (404), a status przekazuje
uprawniona obsługa po weryfikacji dowodu. Minimalny rejestr nie kopiuje hasha
tokenu i nie dowodzi wykonania usunięcia. [Procedura A43.39](A43_DOSTAWCY_PLIKI_I_DOWODY_CZATU.md).
