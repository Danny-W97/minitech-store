# A10 — realizacja i rozliczenie pobrania

Pakiet 19 na `Audit_0.1`, 25.10.2026. Przyjęcie zamówienia za pobraniem jest
oddzielone od otrzymania pieniędzy. `CASH_ON_DELIVERY` obsługuje kuriera
`STANDARD_COURIER`, a `CASH_AT_PICKUP` — `STORE_PICKUP`. Pobranie nie korzysta
z symulacji zatwierdzenia karty ani nie wymaga adaptera płatności internetowej.

## Przyjęcie do realizacji

Przed zapisaniem zakończonego wyniku PENDING Payment Service:

1. sprawdza metodę i wycenę z Order Service;
2. zatwierdza istniejącą rezerwację w Inventory i weryfikuje ID rezerwacji,
   zamówienia oraz odpowiedź CONFIRMED;
3. wiąże zamówienie z ID płatności i metodą pobrania przez autoryzowany endpoint
   wewnętrzny Order Service;
4. zapisuje wynik PENDING razem z trwałymi zadaniami realizacji i rozliczenia.

Po potwierdzeniu zapasu cleanup TTL nie zwalnia rezerwacji CONFIRMED. Przesyłka
i rozliczenie kuponu są wykonywane przez kolejkę A09, także przy nieodebranej
odpowiedzi lub awarii zależności. Powiadomienie `PAYMENT_PENDING` informuje
o płatności przy odbiorze. Faktura, końcowe potwierdzenie zapłaty i zadanie
rozliczenia zamówienia pozostają WAITING.

Jeśli zapas nie został potwierdzony lub Order nie zaakceptował powiązania,
aplikacja nie zgłasza udanego przyjęcia do realizacji. Ponowienie tej samej próby
zachowuje zabezpieczenia A08 i oryginalny zamiar zakupu. Wymuszenie innej metody
albo pominięcie rezerwacji nie daje gotowego wyniku PENDING.

## Realizacja a rozliczenie

Order API dodaje `paymentState`, `deferredPaymentMethod` i `deferredPaymentId`.

| `paymentState` | Znaczenie |
| --- | --- |
| `UNPAID` | Zamówienie nie zostało opłacone ani przyjęte za pobraniem |
| `COLLECTION_DUE` | Pobranie przyjęte do realizacji; należność nie jest rozliczona |
| `PAID` | Płatność internetowa potwierdzona albo pobranie rozliczone |

Status realizacji zachowuje dotychczasowe wartości. Potwierdzone pobranie może
przejść CONFIRMED → SHIPPED → COMPLETED, a odbiór w punkcie również
CONFIRMED → COMPLETED. Nie oznacza to automatycznie zapłaty. Billing sprawdza
`paymentState` i odrzuca wystawienie faktury dla wysłanego lub zakończonego
pobrania, którego należność nadal jest do rozliczenia. Review nie uznaje go
za opłacony zakup. Dla starszych odpowiedzi bez tego pola zachowano obsługę
dotychczasowych statusów.

**Uzupełnienie A17, 26.10.2026:** Delivery przekazuje postęp do Order przez
trwałą synchronizację z rewizjami. Doręczenie lub odbiór może automatycznie
ustawić `COMPLETED`, zachowując `COLLECTION_DUE` do osobnego potwierdzenia
wpłaty przez Payment. Opinie, rekomendacje i bestsellery uwzględniają taki zakup
dopiero po rozliczeniu. [Protokół i obsługa błędów](A17_CYKL_OPLACONEGO_ZAKUPU.md).

Sklep oferuje osobno gotówkę u kuriera i w punkcie. Nieprawidłowa para metody
dostawy i płatności jest odrzucana przed utworzeniem zamówienia oraz ponownie
przez backend. Szczegóły zamówienia pokazują tracking także przed pobraniem
i odrębną informację „Do zapłaty przy odbiorze”. Historia nie proponuje pobrania
PDF przed rozliczeniem należności.

## Rejestracja pobrania

Wyłącznie operator może wywołać:

```http
POST /api/v1/payments/123/collect
Content-Type: application/json

{"amount":123.45,"currency":"PLN","receiptReference":"CARRIER-RECEIPT-123"}
```

Wymagana jest dokładna kwota należności i waluta PLN, a także identyfikator
dowodu wpłaty lub rozliczenia przewoźnika: 3–80 znaków alfanumerycznych oraz
`-`, `_`, `.`, `/`. Pole nie służy do wpisywania nazwiska ani swobodnego opisu.
Nie zastępuje dowodu rzeczywistego pobrania pieniędzy.

Payment sprawdza powiązanie zamówienia z płatnością i potwierdzenie handover
w Delivery: DELIVERED dla kuriera albo PICKED_UP dla punktu. Inna metoda lub
brak potwierdzenia odbioru kończy się 409. Klient sklepu, anonimowy użytkownik
i usługi domenowe nie mogą rejestrować pobrania.

Krótka transakcja SQL zapisuje SUCCEEDED, `collectionReference`, `collectedAt`
i uruchamia `ORDER_SETTLEMENT`. Dowód wpłaty jest unikalny między płatnościami.
Identyczne ponowienie z tą samą kwotą, walutą i dowodem zwraca ten sam wynik;
zmiana dowodu lub użycie go do innej płatności jest odrzucane 409. Operacja nie
wykonuje drugiego obciążenia ani nie wywołuje bramki płatniczej.

Worker rozlicza zamówienie przez uwierzytelniony endpoint Order Service,
zachowuje zakończoną realizację, ustawia paymentState PAID i kończy sagę.
Dopiero wtedy budzi wystawienie żądanej faktury i potwierdzenie zapłaty.
Zapis lokalnego pobrania oraz trwałego zadania jest atomowy; awaria po zdalnym
rozliczeniu Order, lecz przed ukończeniem zadania, jest ponawiana idempotentnie.
Nie trzeba drugi raz rejestrować pieniędzy. Błędy i replay pozostają w mechanizmie A09.

W **CC → Operacje → Monitoring i DLQ** dodano „Zarejestruj pobranie”. CLI:

```bash
bash scripts/control-center/control-center.sh payment-collect 123 123.45 CARRIER-RECEIPT-123
bash scripts/control-center/control-center.sh payment-follow-ups ALL 50 0
```

ID płatności i kwotę można odczytać z operatorowego `GET /api/v1/payments?status=PENDING`.
Nie należy utożsamiać ID płatności z ID zamówienia. Statusy trackingu zmienia
dotychczasowa operacja Delivery. Wynik rejestracji pieniędzy może poprzedzać
zakończenie asynchronicznego rozliczenia Order i wystawienia dokumentu.

## Granice i wdrożenie

- Order V15 dodaje trwałe powiązanie pobrania i identyfikator rozliczenia.
- Payment V12 dodaje dowód i czas pobrania oraz nowe rodzaje zadań kolejki.
- Ogólna zmiana statusu PAID nie rozlicza pobrania. Wewnętrzne operacje przyjęcia
  i rozliczenia Order są dostępne wyłącznie Payment Service, również na porcie usługi.
- Anulowanie już przyjętego pobrania jest blokowane 409 do czasu wdrożenia
  jawnych kompensacji A11. Nie uruchamia pozornego zwolnienia zatwierdzonego zapasu.
- Historyczne PENDING ze starej ścieżki nie są automatycznie uznawane za przyjęte.
  V12 usuwa ich znacznik gotowego wyniku. Wymagają jawnego ponowienia oryginalnego
  zamiaru; zwolniona rezerwacja nie może zostać udawana jako potwierdzona.
  Brakujących danych faktury nie rekonstruuje się z domysłów.

Wdrożyć najpierw zgodne wersje Billing i Review (obsługujące stan rozliczenia),
potem Order z V15, następnie skoordynowanie wymienić wszystkie repliki Payment
z V12. Zaktualizować wszystkie artefakty zawierające `platform-security`,
w szczególności Delivery, aby Payment miał wąskie uprawnienie do odczytu trackingu.
Nie mieszać starych workerów Payment z nowymi rodzajami zadań.

Po zmianie wspólnej biblioteki wykonywać **czysty build**:

```bash
./mvnw clean package
```

Testy wykryły, że zwykłe przyrostowe package pozostawiło w niezmienionym JAR-ze
Delivery starszą bibliotekę uprawnień. Dodano `.mvn/maven.config` z
`-Dmaven.jar.forceCreation=true`, aby ponowne pakowanie nie używało poprzedniego
przepakowanego archiwum. Nazwę opcji zweryfikowano w deskryptorze zainstalowanego
maven-jar-plugin 3.5.0. W odbiorze sprawdzono zgodność osadzonych bibliotek
z bieżącym modułem. CI zaczyna od czystego checkoutu. Nie jest to
wdrożenie na dev/prod; migracje uruchamiano tylko w jednorazowych bazach testowych.

## Regresja

```bash
python3 scripts/qa/deferred-payments-e2e.py
python3 scripts/qa/payment-replicas-e2e.py
python3 scripts/qa/access-control-e2e.py --quality-gates --port-base 43180
```

Scenariusz A10 uruchamia rzeczywiste Order, Inventory, Delivery, Billing,
Promotion i Review oraz dwie repliki Payment w profilu prod bez dostawcy online.
Używa losowych portów/tokenów i własnego PostgreSQL. Kontroluje zapas po TTL,
tracking przed wpłatą, rozróżnienie wysłania od zapłaty, uprawnienia, równoległe
rozliczenie, unikalność dowodu, odbiór w punkcie, fakturę dopiero po pobraniu,
awarię procesu podczas rozliczenia oraz nieudane potwierdzenie zapasu.

Testy Java sprawdzają te przejścia przy pulach jednego i trzech połączeń, bez transakcji
podczas HTTP, a Chromium — poprawny wybór metody oraz brak zamówienia po
niepoprawnym zestawieniu dostawy i pobrania. Regresję dodano do CI.

### Odbiór lokalny — 25.10.2026

- Czysty build: 303 testy Java, bez błędów i pominięć. Po końcowej poprawce
  blokowania zapisu pobrania ponowiono 51 testów Payment i 52 platform-security;
  końcowe pakowanie całego reaktora zakończyło się powodzeniem.
- 87 testów Python, testy CLI Control Center i 18 scenariuszy Chromium: poprawne.
- Regresja A10: 52 kontrole HTTP, cztery rozliczone zamówienia gotówkowe,
  dwie produkcyjne instancje Payment bez dostawcy online; potwierdzono zachowanie
  zapasu po TTL i odtworzenie rozliczenia po awarii procesu.
  Raport: `tmp/deferred-payments-2d5df732/report.json`.
- Regresja A08 na końcowych artefaktach: dwie instancje Payment,
  10 wyścigów pierwszego zapisu, konflikty kluczy, awarie przed i po zapisie,
  odcięcie starego właściciela, ponowienia PENDING, limit czasu oraz utrata
  odpowiedzi HTTP — poprawne. Raport: `tmp/payment-replicas-035dc048/report.json`.
- Cała platforma: 197 kontroli HTTP przez Gateway oraz bramki kontraktów,
  bezpieczeństwa, obciążenia i awarii — poprawne. Test obciążeniowy:
  100/100 odpowiedzi 200, współbieżność 10, p95 426 ms. Raporty:
  `tmp/access-control-e2e-93752c3c/quality-gates/`.
- Zgodność bieżącej biblioteki uprawnień sprawdzono w 15 JAR-ach aplikacji:
  `tmp/audit-a10-artifact-check.log`. Końcowe logi testów znajdują się
  w `tmp/audit-a10-final-*.log`.

Mapa dowodów potwierdza 10/14 zakończonych P1; bramka wydania nadal odrzuca
A05, A06, A11 i A21. Wszystkie testy używały własnych jednorazowych zasobów,
usuwanych po zakończeniu. Nie wdrażano na dev/prod.

Następny priorytet: **A11 — jawne anulowanie i kompensacje po rozpoczęciu realizacji**.
A05, A06 i A21 pozostają otwarte; A33 nadal wymaga pełnego odbioru pozostałych P1.
Zmiany dotyczące samych zdjęć pozostają odłożone.
