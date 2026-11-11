# A09 — trwałe skutki udanej płatności

Pakiet 18 na `Audit_0.1`, 25.10.2026. **Zakończono A09** w zakresie nowych
płatności zapisywanych przez tę wersję: awaria Billing, Delivery lub Promotion
nie usuwa zamiaru wystawienia faktury, utworzenia przesyłki ani rozliczenia kuponu.
Zadania są ponawiane bez kolejnego kliknięcia płatności przez klienta.

## Zapis i wykonanie

Payment V11 tworzy `payment_follow_ups`. W tej samej transakcji co wynik
SUCCEEDED powstają osobne zadania dla każdego potrzebnego skutku:

| Rodzaj | Zapisany zamiar |
| --- | --- |
| `PROMOTION_REDEMPTION` | Kod kuponu z opłaconego zamówienia i jego ID |
| `SHIPMENT_TRACKING` | ID zamówienia i metoda dostawy |
| `INVOICE_ISSUE` | Dane faktury zaakceptowane podczas zakupu i serwerowa migawka zamówienia |
| `PAYMENT_NOTIFICATION` | Metadane potwierdzenia; dla faktury początkowo czeka na jej ID |

Unikalność `(order_id, kind)` chroni zadania przed powieleniem. Klucz obcy wiąże
je z zapisaną płatnością. Błąd zapisu któregokolwiek zadania cofa całą transakcję
wyniku. Zadania biznesowe powstają także bez odbiorcy powiadomienia.

Po commicie aplikacja próbuje wykonać zadania od razu, w budżecie do 8 sekund,
skracanym przez deadline żądania. Błąd skutku nie zmienia zapisanego sukcesu
płatności w pozorną odmowę ani nie powoduje ponownego obciążenia. Dostępna
faktura nadal zwykle powstaje przed odpowiedzią, ale API dopuszcza jej późniejsze
wystawienie. Odpowiedź POST płatności nie oznacza zakończenia wszystkich zadań.
Sklep pokazuje informację o zamówionej fakturze, gdy dokument jeszcze nie istnieje.

Niezależny scheduler odczytuje do 50 należnych ID. Czterech pracowników ma kolejkę
50 zadań i budżet 10 sekund na wykonanie. Claim, ukończenie, błąd i replay to
krótkie transakcje; HTTP odbywa się poza nimi. Token i TTL rezerwacji odcinają
starego właściciela. Po awarii procesu zadanie staje się dostępne po wygaśnięciu
rezerwacji. Wysłane żądanie HTTP może wykonać się mimo śmierci klienta, dlatego
docelowe usługi zachowują własną idempotencję.

Faktury mają unikalność zamówienia i bezpieczny odczyt po konflikcie inserta;
przesyłki mają unikalność zamówienia. Promotion dodatkowo blokuje rekord kodu
przed sprawdzeniem wcześniejszego rozliczenia i zwiększeniem licznika. Równoległe
ponowienia tego samego zamówienia nie podnoszą licznika, a różne zamówienia go
nie nadpisują. To nie zamyka A14 dotyczącego rezerwacji limitu kuponu w checkout.

Przed nowym żądaniem przesyłki lub kuponu worker ponownie odczytuje zamówienie.
Akceptuje PAID/SHIPPED/COMPLETED; inny status kieruje zadanie do DLQ z kodem
`ORDER_STATE_CONFLICT`. Billing sam weryfikuje opłacone zamówienie i jego dane.
Ta kontrola nie cofa operacji już wysłanej ani nie wdraża pełnych kompensacji A11.

## Faktura i powiadomienie

Zadanie powiadomienia dla faktury ma stan WAITING. Wystawienie faktury zapisuje
dodatnie ID dokumentu i budzi powiadomienie w jednej transakcji. Brak ID w HTTP
200 z Billing jest błędem, a nie zakończeniem zadania.

Worker powiadomienia zapisuje istniejący `PAYMENT_SUCCEEDED` w outboxie wraz
z ID faktury i oznacza zadanie SUCCEEDED w tej samej transakcji. Zatrzymanie procesu
w dowolnym miejscu tego zapisu nie gubi zdarzenia i nie tworzy drugiej tożsamości.
SMTP pozostaje w Notification Service, zgodnie z retry/inbox/DLQ z A32.
Awaria kuponu albo przesyłki nie blokuje samego wystawienia faktury. Trwale
nieudana faktura pozostawia potwierdzenie z PDF w WAITING do naprawy i replay.

## Retry, DLQ i operator

Stany zadań: PENDING, WAITING, RETRY, SUCCEEDED i DEAD_LETTER. Błędy przejściowe
mają backoff od 2 sekund do 300 sekund. Domyślnie po 20 próbach, także claimach
przerwanych awarią procesu, zadanie trafia do DEAD_LETTER. Niepoprawny payload
i trwałe HTTP 4xx trafiają tam bez dalszych automatycznych prób; 408/409/425/429
pozostają ponawialne. Przechowywany jest bezpieczny kod błędu, bez odpowiedzi
usługi, danych kupującego lub komunikatu wyjątku.

Wyłącznie operator ma dostęp do:

```text
GET  /api/v1/payments/follow-ups?status=DEAD_LETTER&orderId=123&size=50&cursor=0
POST /api/v1/payments/follow-ups/456/replay
```

Filtry status/orderId są opcjonalne. Lista ma keyset pagination, maksymalnie 200
pozycji i nagłówki `X-Has-More`, `X-Next-Cursor`, `X-Page-Size`. Zapytanie wybiera
wyłącznie metadane DTO; nie pobiera payloadu faktury do listy ani do JSON.
Anonimowy klient, klient sklepu i tożsamość innej usługi nie mają dostępu.
Replay jest możliwy wyłącznie z DEAD_LETTER: zwiększa licznik replay, zeruje
próby i zachowuje pierwotny zamiar. Zakończonego zadania nie można uruchomić drugi raz.

W **Control Center → Operacje → Monitoring i DLQ** dostępne są „Zadania po płatności”
i „Ponów zadanie po płatności”. CLI używa tokenu operatora z aktywnego profilu:

```bash
bash scripts/control-center/control-center.sh payment-follow-ups DEAD_LETTER 50 0
bash scripts/control-center/control-center.sh payment-follow-ups ALL 50 0
bash scripts/control-center/control-center.sh payment-follow-up-replay 456
```

Przed replay należy usunąć przyczynę błędu. Replay nie edytuje danych faktury
ani nie zastępuje ustalenia przyczyny niezgodności z Order Service.

## Konfiguracja i wdrożenie

| Zmienna | Domyślnie | Znaczenie |
| --- | ---: | --- |
| `PAYMENT_FOLLOW_UPS_ENABLED` | `true` | Automatyczne odzyskiwanie przez scheduler |
| `PAYMENT_FOLLOW_UPS_FIXED_DELAY_MS` | `1000` | Odstęp odczytu kolejnej partii |
| `PAYMENT_FOLLOW_UPS_LEASE_MS` | `45000` | TTL rezerwacji; minimum 30000 ms |
| `PAYMENT_FOLLOW_UPS_MAX_ATTEMPTS` | `20` | Limit claimów do DLQ; 1–100 |

Wyłączenie schedulera nie usuwa zadań ani pierwszej próby wykonywanej po commicie.
Jeśli pierwsza próba zawiedzie, wyłączony scheduler nie naprawi jej automatycznie.
Po włączeniu kontynuuje zapisane zadania. Payload ma limit 1 MiB na zadanie;
po ukończeniu jest usuwany z rekordu, a ID faktury i metadane diagnostyczne zostają.
Payload RETRY/WAITING/DLQ pozostaje potrzebny do odzyskiwania. Długoterminowa
retencja historii i obsługa danych osobowych całej platformy pozostają w A43.

Wdrożyć nowy Promotion Service, następnie skoordynowanie wymienić repliki
Payment Service z migracją V11. Stare Payment nadal wykonują skutki bez kolejki,
dlatego należy odprowadzić ruch i zakończyć stare procesy przed uruchomieniem
nowych. Nie pozostawiać mieszanego zestawu starych i nowych replik. Po starcie
sprawdzić listę RETRY/WAITING/DEAD_LETTER oraz wynik płatności z żądaną fakturą.

Migracja nie odtwarza brakujących danych faktury historycznych płatności, które
ich wcześniej nie zapisały. Nie wolno tworzyć dokumentu z wymyślonym nabywcą.
Dla takich rekordów operator musi uzgodnić oryginalne dane zakupu i użyć
istniejącego, weryfikowanego API Billing; dotychczasowe API przesyłki i kuponu
pozostają idempotentne. Nowe zadania mają kompletny zamiar zapisany w transakcji.
Odzyskiwanie przerwanej autoryzacji przed zapisem sukcesu płatności pozostaje
protokołem A08/A05; ten pakiet nie wdraża nowego dostawcy ani pełnych refundów.

W tym etapie zmieniono kod i uruchomiono wyłącznie izolowane środowiska testowe.
Nie wykonywano migracji ani wdrożenia na dev/prod. Zmiany samych zdjęć pozostają
odłożone zgodnie z decyzją użytkownika.

## Regresja

Po `./mvnw package`, w Linux/WSL z Java 25 i Docker:

```bash
python3 scripts/qa/payment-follow-ups-e2e.py
python3 scripts/qa/payment-replicas-e2e.py
python3 scripts/qa/access-control-e2e.py --port-base 41180
```

Test A09 używa dwóch JVM Payment oraz rzeczywistych Billing, Delivery i Promotion
na jednorazowych bazach PostgreSQL. Wyłącza trzy zależności podczas pięciu
płatności, sprawdza 20 trwałych zadań i późniejsze odzyskanie bez kolejnego POST.
Sprawdza brak odbiorcy e-maila, cztery awarie procesu po skutku/przed commitem,
dokładny licznik kuponu, pojedynczą fakturę/przesyłkę, ID PDF w zdarzeniu,
trwałe błędy walidacji, uprawnienia operatora i replay.

Testy Java sprawdzają atomowość wyniku/zadań i powiadomienia/outboxu, bezpieczny
rollback, sieć poza transakcją przy puli jednego połączenia, wygasłe tokeny,
retry/DLQ/replay, niepoprawny payload, brak ID faktury, anulowane zamówienie,
metadane i paginację. Promotion ma test 40 równoległych żądań dla 20 zamówień
z dokładnym końcowym licznikiem 20. CI wykonuje nową regresję i zachowuje raport
oraz logi przy błędzie.

### Wyniki odbioru 25.10.2026

- Pełny build: **286 testów Java**, bez błędów i pominięć. Po końcowych zmianach
  projekcji metadanych i kontroli anulowania ponownie przeszły **44 testy Payment
  i 50 platform-security**. Aktualne zestawy modułów obejmują łącznie **288 testów**,
  wszystkie zakończone sukcesem.
- **87 testów Python** w WSL z wymaganym SOPS, testy CLI paginacji i propagacji
  błędu replay, składnia Bash/JS/Python i YAML CI oraz `git diff --check`: poprawne.
- **195 kontroli HTTP** przez Gateway na 15 rzeczywistych usługach i dodatkowej
  instancji produkcyjnej bez dostawcy: checkout konta/gościa, faktury/PDF,
  autoryzacja oraz odczyt metadanych zadań. Test czeka na zakończenie zadań faktury.
- Końcowa regresja A09: **PASSED** — 5 płatności podczas awarii trzech zależności,
  20 zapisanych zadań, 4 punkty przerwania procesu i 11 zamówień z dokładnym
  rozliczeniem kuponu. Raport: `tmp/payment-follow-ups-b6af1e19/report.json`.
  Oczekiwanie uwzględnia 45-sekundowe lease pozostałych zadań przerwanego procesu.
- Końcowa regresja A08: **PASSED**, w tym 10 wyścigów pierwszego zapisu,
  utrata odpowiedzi i odzyskiwanie między replikami.
  Raport: `tmp/payment-replicas-31ae78a5/report.json`.
- Mapa dowodów: **9/14 P1 zakończonych**. `--release` prawidłowo odrzuca
  pozostałe A05/A06/A10/A11/A21; A33 nie zostało oznaczone jako zakończone.

Logi: `tmp/audit-a09-full-build.log`, `tmp/audit-a09-final-payment-build.log`,
`tmp/audit-a09-final-follow-ups.log`, `tmp/audit-a09-final-replicas.log`,
`tmp/audit-a09-platform.log`, `tmp/audit-a09-final-checks.log`,
`tmp/audit-a09-p1-readiness.log`. Wszystkie jednorazowe procesy i kontenery
zakończono. Nie uruchamiano zdalnie GitHub Actions ani wdrożenia dev/prod.

Pobranie i rejestrowanie należności wykonano w późniejszym [pakiecie A10](A10_POBRANIE_I_REALIZACJA.md).
Następny priorytet: **A11 — anulowanie i kompensacje**. A05/A06/A11/A21 oraz końcowy odbiór A33
pozostają otwarte.
