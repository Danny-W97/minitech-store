# A06 — spójność zamówienia, sagi i odzyskiwania historii

Pakiet 21 na `Audit_0.1`, 25.10.2026. Zamówienie i początkowa saga są zapisywane
w jednej krótkiej transakcji. HTTP do magazynu następuje po jej commicie.
Uzupełniono odbiór awarii tego zapisu, kontrolę przejść i obsługę historycznych
rekordów bez sagi.

## Przejścia i odpowiedzi magazynu

- Zmiany wykonywane są pod blokadą sagi, następnie zamówienia. Order V16 dodaje
  również wersję optymistyczną: zapis starej encji nie może nadpisać nowszego stanu.
- API nie może już wykonać CREATED → CONFIRMED. Potwierdzenie należy do procesu
  rezerwacji, który sprawdza ID zamówienia i rezerwacji oraz odpowiedź RESERVED.
- Aktualizacja PAID wymaga zapisanej rezerwacji i zgodnego stanu sagi.
  Brak sagi, początkowa rezerwacja, kompensacja lub uzgodnienie blokują przejście.
- Wyniki wykonawców sprawdzają token, ważność lease oraz stan sagi. Stary lub
  wygasły wykonawca nie może zapisać wyniku ani błędu po przejęciu pracy.
- Spóźnione potwierdzenie albo odmowa rezerwacji nie cofa opłaconego, anulowanego
  ani przyjętego za pobraniem zamówienia do wcześniejszego stanu. Niezgodność
  kierowana jest do uzgodnienia.
- Anulowania respektują blokadę płatności i protokół A11. PAID i CANCELLED
  nie mogą niezależnie wygrać wyścigu na dwóch instancjach Order.

## Wykrywanie rekordów bez sagi

Osobny skaner czyta okna ID zamówień rosnąco. Każde wywołanie obejmuje najwyżej
`ORDER_SAGA_RECONCILIATION_BATCH_SIZE` rekordów (domyślnie 100, zakres 1–500).
Sprawdzenie braku sagi dotyczy tylko bieżącego okna. Nie wykonuje na każdym
tyknięciu pełnego antyzłączenia całej historii. Po końcu historii zaczyna nowy
cykl. Błąd pojedynczego rekordu pozostawia go do następnego cyklu.

Domyślny odstęp to `ORDER_SAGA_RECONCILIATION_FIXED_DELAY_MS=30000`.
Przy dużej historii wykrycie starego rekordu wymaga przejścia poprzednich okien;
czas pełnego cyklu zależy od liczby zamówień i ustawionego rozmiaru okna.
`order.saga.recovery-enabled=false` wyłącza również ten skaner.

Każdy znaleziony rekord jest ponownie sprawdzany pod blokadą. Równoległe skanery
mogą utworzyć tylko jedną sagę. Gdy inna instancja zdążyła ją dodać, sprawdzenie
po blokadzie Order nie pobiera blokady nowej sagi w odwróconej kolejności.

| Historyczny rekord bez sagi | Obsługa |
| --- | --- |
| CREATED z poprawną migawką i bez powiązania pobrania | Saga INVENTORY_RESERVATION_PENDING; zwykłe recovery ponawia idempotentną rezerwację |
| CONFIRMED | RECONCILIATION_REQUIRED, bez automatycznej płatności lub nowej rezerwacji |
| PAID, SHIPPED, COMPLETED, CANCELLED lub przyjęte pobranie | RECONCILIATION_REQUIRED; istniejące statusy i fakty finansowe pozostają zachowane |
| Brak pozycji, powtórzone produkty lub niespójne kwoty | RECONCILIATION_REQUIRED; brak zgadywania danych zakupu |

Skaner nie pobiera bieżących cen i nie rekonstruuje kupującego, dowodu wpłaty,
faktury czy skutków logistycznych z domysłów. Migawka musi mieć poprawne ilości,
unikalne produkty, wartości pozycji oraz sumę z dostawą i rabatem.

## Znacznik wymaganej weryfikacji

Order V16 dodaje `lifecycle_review_required`; API zwraca
`lifecycleReviewRequired`. Odczyt szczegółów i odtworzenie odpowiedzi po kluczu
idempotencji sprawdzają też brak sagi na bieżąco, zanim skaner zapisze znacznik.
Płatność nie musi zatem czekać na pełny cykl skanera, aby rozpoznać brak sagi.

Payment odrzuca nową autoryzację i rejestrację pobrania dla rekordu do uzgodnienia.
Już zapisany wynik płatności pozostaje odtwarzalny; znacznik nie cofa pieniędzy.
Workery blokują nowe skutki biznesowe; zadanie trafia do DLQ z konfliktem stanu
Order i wymaga jawnego replay po uzgodnieniu. Billing nie wystawia nowej faktury,
a Review nie uznaje takiego rekordu za dowód zakupu. Oznaczona historia nie
zasila agregatów opłaconych zakupów i bestsellerów.

Sklep pokazuje komunikat weryfikacji i nie proponuje nowej faktury ani zgłoszenia
zwrotu z takiego rekordu. Dotychczasowe dokumenty i statusy nie są usuwane.
Wyświetlenie znacznika nie oznacza, że zakup został anulowany lub zrefundowany.

## Obsługa operatora

```http
GET /api/v1/orders/sagas?state=RECONCILIATION_REQUIRED&size=50&cursor=0
POST /api/v1/orders/123/reconcile
```

Obie operacje są operatorowe; klienci i usługi domenowe nie uzyskują nowych
uprawnień. Lista jest stronicowana i zwraca nagłówki `X-Has-More`/`X-Next-Cursor`.
Kursor dotyczy rekordu sagi; nie należy zastępować go numerem zamówienia.

CC → Operacje → Monitoring i DLQ: **Uzgodnienia zamówień** oraz
**Zweryfikuj starą rezerwację**. CLI:

```bash
bash scripts/control-center/control-center.sh order-sagas RECONCILIATION_REQUIRED 50 0
bash scripts/control-center/control-center.sh order-reconcile 123
```

Operacja może przywrócić wyłącznie historyczne CONFIRMED bez powiązania pobrania.
Poza transakcją SQL odczytuje rezerwację Inventory i sprawdza:

- zgodność ID zamówienia i znanego ID rezerwacji;
- dokładny zestaw produktów i ilości;
- RESERVED oraz niewygasły termin;
- niezmienioną wersję i status Order przy zapisie wyniku oraz aktualny lease.

Po pozytywnej weryfikacji zapisuje ID rezerwacji, WAITING_FOR_PAYMENT i usuwa
znacznik w jednej transakcji. Nie pobiera pieniędzy, nie potwierdza magazynowej
sprzedaży ani nie przelicza ceny. Powtórzenie zakończonej operacji jest odczytem
bieżącego wyniku. 409 pozostawia rekord do uzgodnienia, a 503 nie jest traktowane
jak brak rezerwacji. Przy braku odpowiedzi można bezpiecznie ponowić polecenie.

PAID i pozostała niejednoznaczna historia wymagają weryfikacji finansowej poza tym
mechanizmem. Sam wpis statusu nie pozwala odtworzyć brakującego potwierdzenia
dostawcy. Pełna integracja i refundacje pozostają w otwartym A05.

## Wydanie i testy

Najpierw zaktualizować konsumentów znacznika (Payment, Billing i Review), następnie
całą flotę Order z V16. Nie mieszać starych piszących instancji Order, które nie
respektują kolumny wersji, z nowymi. Na czas wymiany opróżnić aktywne operacje.
Compose i K8s otrzymały parametry skanera. Migracja nie oznacza historycznych
płatności jako udane i nie uruchamia masowego procesu finansowego.

- `OrderCreationTransactionTest`: atomowy commit i rollback zapisu zamówienia/sagi.
- `OrderLifecycleTest`: ograniczone okna, dwa skanery, weryfikacja rezerwacji,
  wersjonowanie, wygaśnięcie lease, późny wynik i wyścig PAID/CANCELLED.
- Testy Payment/Billing/Review: znacznik blokuje nowe autoryzacje, zadania,
  fakturę i uznanie zakupu za zweryfikowany.
- `order-lifecycle-e2e.py`: rzeczywisty PostgreSQL, dwie instancje Order,
  przerwanie między INSERT-ami oraz po commicie, odtworzenie przez drugi proces,
  stare rekordy, zachowanie historii i sześć wyścigów statusów. Test dodano do CI.

Nie wdrażano na dev/prod. Zmiany dotyczące samych zdjęć pozostają odłożone.

### Odbiór lokalny — 25.10.2026

- Czysty build całej aplikacji i bieżące zestawy: 335 testów Java, bez błędów
  i pominięć. Końcowy odczyt braku sagi i filtrów agregatów zweryfikowano ponownie
  w 56 testach Order i 55 platform-security. Końcowe pakowanie wszystkich modułów
  zakończyło się powodzeniem.
- 87 testów Python, testy CLI i 19 scenariuszy Chromium — poprawne.
- A06: 66 kontroli HTTP, dwie instancje Order, rollback pomiędzy INSERT-ami,
  przejęcie pracy po commicie, odzyskanie historycznego CREATED, kontrolowana
  weryfikacja CONFIRMED i sześć wyścigów PAID/CANCELLED — poprawne.
  Raport: `tmp/order-lifecycle-7fccb15a/report.json`.
- Cała platforma: 194 kontrole HTTP na 15 usługach i dodatkowej produkcyjnej
  instancji Payment; bramki kontraktów, bezpieczeństwa, obciążenia i awarii — poprawne.
  Obciążenie: 100/100 odpowiedzi 200, współbieżność 10, p95 346,761 ms,
  p99 416,219 ms, pending puli 0. Są to pomiary lokalnego scenariusza testowego.
  Raporty: `tmp/access-control-e2e-8ef2403f/quality-gates/`.
- Ponowiono natywne regresje A10 i A11: cztery pobrania i 52 kontrole HTTP A10,
  trzy wyścigi anulowania z checkoutem oraz awarie procesów — poprawne.
  Raporty: `tmp/deferred-payments-5157e612/report.json`
  i `tmp/cancellations-a3cd7e39/report.json`.
- Zgodność bieżącej biblioteki uprawnień potwierdzono w 15 JAR-ach. Sprawdzono
  składnię JS/Python/YAML, zakończenia LF skryptów Bash i `git diff --check`.
  Logi: `tmp/audit-a06-*.log`.

Wszystkie jednorazowe zasoby natywnych regresji usunięto. Mapa dowodów wskazuje
12/14 P1; bramka wydania nadal odrzuca A05 i A21. A33 pozostaje częściowy.

Następny priorytet: **A21 — uzupełnianie stanów magazynowych i idempotencja operacji**.
