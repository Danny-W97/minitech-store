# A13 — saldo korekt faktur

## Wdrożony model

Korekta jest kolejną, niezmienną deltą. Saldo to kwoty faktury pierwotnej
plus wszystkie zapisane delty. Dla 100 PLN, −10 PLN i −20 PLN kolejne salda
wynoszą 90 PLN oraz 70 PLN. Pola `originalNet/Vat/Gross` nowej korekty opisują
saldo **przed tą korektą**, a `finalNet/Vat/Gross` saldo bezpośrednio po niej.
Dokument identyfikuje model `CUMULATIVE_DELTA` i wynikową `balanceVersion`.
Nie jest zmieniany po wystawieniu następnego dokumentu.

Billing blokuje rekord faktury w bazie na czas odczytu salda, sprawdzenia
polecenia i zapisu dokumentu wraz ze zdarzeniem outbox. Ta sama blokada działa
między replikami. Dwa różne polecenia dla tej samej wersji salda mają jednego
zwycięzcę; drugie otrzymuje 409 i wymaga ponownego odczytu kwot.

Każde polecenie zawiera `idempotencyKey` oraz `expectedVersion`. Powtórzenie
tego samego polecenia zwraca ten sam dokument i nie publikuje nowego zdarzenia,
również jeśli później wystawiono inne korekty. Ten sam klucz z inną kwotą,
przyczyną lub wersją daje 409. Klucz jest związany z konkretną fakturą.

Kwota ma maksymalnie 17 cyfr całkowitych i dwa miejsca po przecinku; zero,
ujemne saldo końcowe i przepełnienie są odrzucane. Powód ma 1–255 znaków bez
znaków sterujących. Obecny model aplikacji nadal stosuje jedną stawkę 23%:
netto wyliczane jest z końcowego brutto, a delta netto i VAT z różnic sald.
Dzięki temu seria korekt po jednym groszu może zakończyć dokument zerem
bez pozostawienia groszowego salda netto/VAT.

## API i Control Center

Nowy odczyt dostępny wyłącznie operatorowi:

```http
GET /invoices/123/correction-balance
```

Zwraca `invoiceId`, `version`, `netAmount`, `vatAmount`, `grossAmount`,
`currency` i liczbę `legacyCorrections`.

Przykład nowego polecenia:

```http
POST /invoices/123/corrections
Content-Type: application/json

{"reason":"Rabat","correctionGrossAmount":-20.00,"idempotencyKey":"staly-klucz-operacji","expectedVersion":1}
```

Alias `correctionGross` pozostaje obsługiwany. Brak klucza/wersji daje 400.
`CORRECTION_BALANCE_CHANGED` oznacza nieaktualne saldo;
`CORRECTION_KEY_REUSED` oznacza próbę zmiany wcześniej użytego polecenia.

Zwykły formularz CC odczytuje bieżące saldo przed otwarciem zapisu. Zapisuje
klucz i dane próby lokalnie przed POST; po utracie odpowiedzi lub przeładowaniu
strony przywraca tę samą próbę. Nierozstrzygnięta próba nie może być zastąpiona
inną kwotą. Po potwierdzeniu dokumentu zapis lokalny jest usuwany. Odmowa z powodu
nieaktualnej wersji pozwala ponownie otworzyć formularz i odczytać saldo.
Brak dostępu do lokalnego zapisu lub świeżego salda blokuje nową operację.

Historia CC rozróżnia saldo po konkretnej korekcie od historycznego snapshotu.
Lista faktur nadal korzysta ze zbiorczych sum delt. PDF nowej korekty pokazuje
skumulowane saldo przed/po i numer wersji; jego kwoty nie zmieniają się później.

CLI i formularze akcji CC:

```bash
bash scripts/control-center/control-center.sh invoice-balance 123
bash scripts/control-center/control-center.sh invoice-correct 123 "Rabat" -20.00 staly-klucz-operacji 1
```

Po nieznanym wyniku należy ponowić **ten sam klucz, wersję i dane**.
Nowy klucz oznacza nową korektę. Klient i pozostałe usługi nie mogą wystawiać
korekt ani odczytywać operatorskiego endpointu salda.

## Historia i wdrożenie

Migracja Billing **V8** dodaje klucz polecenia i wersję rejestru, wraz
z ograniczeniami unikalności. Nie przepisuje kwot, numerów ani przyczyn
już wystawionych dokumentów. Stare rekordy mają model `LEGACY_BASE_DELTA`
i pustą wersję; ich indywidualne `finalGross` może nadal odzwierciedlać stary
algorytm. API salda sumuje ich delty, a PDF i historia oznaczają je jako
historyczne. Nie wolno interpretować ostatniego starego snapshotu jako
aktualnego salda całej faktury. Niepoprawne historyczne saldo wymaga osobnego
uzgodnienia; nie jest naprawiane ukrytą zmianą wystawionych dokumentów.

Przy wydaniu wstrzymać wystawianie korekt, opróżnić aktywne żądania, wdrożyć
V8 i nowy kod na wszystkich replikach Billing, następnie uaktualnić CC oraz
klientów API. Dopiero wtedy wznowić zapis. Nie mieszać starych i nowych
procesów wystawiających korekty. Nie cofać migracji przez usunięcie rejestru.

Korekta dokumentu nie wykonuje zwrotu Stripe, nie zmienia płatności ani
zamówienia. Finansowe zwroty A05 oraz pełne odwracanie skutków zakupu A11
pozostają odrębnymi procesami. Ręczne sekrety i zewnętrzny odbiór A05 nadal
pozostają otwarte. W tym etapie nie wdrażano usług na dev/prod.

## Weryfikacja — 25.10.2026

- 98 testów Java: 39 Billing, w tym osiem nowych scenariuszy rejestru,
  oraz 59 wspólnej kontroli dostępu. Log: `tmp/a13-billing-tests.log`.
- 98 testów Python/CC i 22 JavaScript. Logi:
  `tmp/a13-qa-final.log`, `tmp/a13-js-tests.log`.
- Testy CLI: korekty, paginacja i eksport ZIP oraz składnia zmienionych
  skryptów Bash. Log: `tmp/a13-cli-tests.log`.
- PostgreSQL: 47 kontroli HTTP, migracja V7→V8 z zachowaniem zapisanych
  dokumentów, dwie rzeczywiste instancje Billing, kontrakt OpenAPI, odmowy
  uprawnień, ponowienia, sześć równoczesnych duplikatów i wyścig różnych korekt.
  Raport: `tmp/invoice-corrections-9cfd2eae/report.json`.

Regresje `InvoiceCorrectionLedgerTest`, `invoice-corrections.test.cjs`,
`test_invoice_corrections.py`, `invoice-corrections-test.sh` oraz
`scripts/qa/invoice-corrections-e2e.py` są uruchamiane w CI. Jednorazowa baza,
procesy i kopia JAR-a zostały usunięte po teście integracyjnym.
