# A11 — bezpieczna granica anulowania i kompensacja nieopłaconego zamówienia

Pakiet 20 na `Audit_0.1`, 25.10.2026. Wdrożono wariant MVP wskazany w audycie:
**aplikacja blokuje pozorne anulowanie opłaconego lub przyjętego do realizacji
zamówienia**. Nie deklaruje zwrotu pieniędzy, cofnięcia sprzedaży, kuponu,
przesyłki ani dokumentu, jeśli tych operacji nie wykonała.

Adapter finansowy A05 obsługuje teraz trwałe pełne zwroty Stripe. Automatyczna
kompensacja niezrealizowanego Checkoutu może przywrócić zapas i zakończyć
anulowanie. Zwrot operatora blokuje dalszą realizację i pokazuje REFUNDED,
pozostawiając dokumenty, kupony i pozostałe skutki zakupu do uzgodnienia.
Pełne odwracanie tych skutków opłaconych zakupów pozostaje rozszerzeniem A11
wymagającym osobnych kroków domenowych. Procedura: [A05](A05_STRIPE_CHECKOUT.md).
Status zgłoszenia w Returns pozostaje statusem obsługi zgłoszenia, a nie dowodem
zwrotu pieniędzy. Nie dodano symulowanego zwrotu do produkcji.

## Zasady

| Przypadek | Wynik anulowania |
| --- | --- |
| CREATED/CONFIRMED bez próby płatności | Blokada przyszłych płatności, potwierdzone zwolnienie rezerwacji, następnie CANCELLED |
| Rzeczywista, zapisana odmowa adaptera płatności | Ten sam proces; odmowa musi być jawnie oznaczona przez nowy kod |
| PENDING, także po wygaśnięciu lease | 409; brak odpowiedzi nie dowodzi braku obciążenia lub przyjęcia pobrania |
| SUCCEEDED albo zapisana autoryzacja | 409; wymagany osobny proces finansowy |
| Historyczne FAILED o niejednoznacznym pochodzeniu | 409; migracja nie uznaje ich automatycznie za odmowę dostawcy |
| Przyjęte pobranie, PAID, SHIPPED, COMPLETED | 409; stan i skutki pozostają zachowane |
| Już CANCELLED | Idempotentna odpowiedź, bez kolejnego zwalniania zapasu |

## Protokół

1. Order pod blokadą sagi i zamówienia sprawdza dopuszczalność anulowania.
   Zapisuje COMPENSATION_PENDING oraz lease wykonawcy. Również starszy rekord
   bez sagi przechodzi ten protokół; usunięto skrót zmieniający wyłącznie status.
2. Poza transakcją SQL wywołuje
   `POST /internal/payments/orders/{orderId}/cancel`.
   Endpoint jest dostępny wyłącznie uwierzytelnionemu `order-service`.
3. Payment blokuje trwały rekord `payment_order_gates`, a następnie płatność.
   Pierwsza próba płatności korzysta z tego samego rekordu i kolejności blokad.
   Skuteczna blokada anulowania jest trwała również dla zamówienia, które nie
   miało jeszcze żadnego rekordu Payment. Nie wygasa po restarcie ani z TTL lease.
   Konflikt pierwszego INSERT jest ponawiany dopiero po rollbacku transakcji.
4. Order zwalnia rezerwację i sprawdza odpowiedź RELEASED wraz z ID zamówienia
   i rezerwacji. Jeśli odpowiedź wcześniejszej rezerwacji zaginęła, odnajduje ją
   przez `GET /inventory/reservations/order/{orderId}`. Brak rekordu (404) oznacza
   brak rezerwacji do zwolnienia; awaria odczytu nie jest traktowana jak 404.
5. Dopiero po obu potwierdzeniach krótka transakcja zapisuje CANCELLED.

W trakcie COMPENSATION_PENDING zwykłe przejścia realizacji i przyjęcie pobrania
są blokowane. Odmowa Payment przywraca stan sagi odpowiedni do stanu zamówienia;
nie zwalnia zapasu. Jeśli wpłata została właśnie autoryzowana, a uzgodnienie
Order dostało konflikt, ta sama próba płatności może zostać jawnie ponowiona
po rozstrzygnięciu anulowania — z zachowaniem zapisanej autoryzacji.

Awaria Payment lub Inventory pozostawia COMPENSATION_PENDING i termin ponowienia.
Istniejący mechanizm recovery i lease odtwarza operację po powrocie zależności
lub restarcie Order. Zdalne wywołania nie zajmują transakcji ani połączenia SQL.
Awaria po zdalnym zwolnieniu, lecz przed lokalnym commitem, kończy się bezpiecznym
ponowieniem blokady i idempotentnego zwolnienia tej samej rezerwacji.

Rezerwacja CONFIRMED nie jest automatycznie odwracana przez release. Jeśli
nieopłacony rekord ma taki niespójny stan, proces pozostaje do uzgodnienia,
bez fałszywego CANCELLED i bez dodania zapasu sprzedanego wcześniej innemu zakupowi.

## Błędy po autoryzacji

Payment nie zamienia błędu aktualizacji Order/Inventory po autoryzacji na FAILED
i nie uruchamia wówczas pozornego anulowania. Zachowuje PENDING i trwałą referencję
autoryzacji; ponowienie używa tego samego zamiaru i nie autoryzuje ponownie.
Spóźniona odmowa rezerwacji starszej sagi również nie nadpisuje stanu zamówienia,
które opuściło CREATED, w szczególności zamówienia opłaconego.
Usunięto także bezpośrednie zwalnianie Inventory jako obejście błędu anulowania Order.
Jawna odmowa dostawcy jest zapisywana przed próbą anulowania, aby mogła zostać
poprawnie rozpoznana przez drugi węzeł Payment.

## API i Control Center

Publiczne API pozostaje `PATCH /api/v1/orders/{id}/status` z treścią
`{"status":"CANCELLED"}`, wyłącznie dla dotychczas uprawnionych operatorów/usług.
Klient nie otrzymuje dodatkowych uprawnień do zmiany statusu.

CC → Operacje → Sklep: **Anuluj nieopłacone zamówienie**. CLI:

```bash
bash scripts/control-center/control-center.sh order-cancel 123
```

409 oznacza odmowę, a 503 brak potwierdzenia zależności. Żadna z tych odpowiedzi
nie potwierdza anulowania. Stan sagi można sprawdzić dotychczasowym
`GET /api/v1/orders/{id}/saga`; COMPENSATION_PENDING jest odzyskiwany automatycznie.

## Migracja i kolejność wydania

Payment V13 dodaje trwałe blokady oraz `provider_declined`, domyślnie false.
Stare FAILED nie są przepisywane jako pewne odmowy. Dane historyczne wymagają
uzgodnienia z dokumentacją dostawcy, jeśli mają zostać rozliczone finansowo.

Najpierw zaktualizować Inventory i całą flotę Payment z V13, następnie Order.
W czasie wymiany floty wstrzymać nowe checkouty/anulowania i opróżnić aktywne
próby; nie mieszać workerów, które respektują blokadę, z kodem, który ją ignoruje.
Order potrzebuje `PAYMENT_SERVICE_URL` (Compose i K8s już go definiują).
Zmiana wspólnej polityki wymaga ponownego zbudowania wszystkich aplikacji.
Nie usuwać blokad przy rollbacku bez uzgodnienia rozpoczętych operacji.

## Regresja

- `CancellationFenceTest`: trwała blokada, wyścig dwóch transakcji, jawna odmowa
  oraz odrzucanie stanów niepewnych i historycznych.
- `CancellationSafetyTest`: zamówienie opłacone także bez sagi, kolejność kroków,
  odmowa Payment, ponowienie po utracie odpowiedzi i odzyskanie ID rezerwacji.
- `PaymentServiceTest`: błąd po autoryzacji zachowuje referencję i nie zwalnia zapasu.
- `cancellations-e2e.py`: rzeczywiste usługi i PostgreSQL, dwie instancje Payment,
  równoległy checkout/anulowanie, uprawnienia, awaria zależności i przerwanie Order
  po zwolnieniu rezerwacji, przed lokalnym commitem. Scenariusz dodano do CI.

### Odbiór lokalny — 25.10.2026

- Czysty build całej aplikacji: 315 testów Java, bez błędów i pominięć.
  Końcowa ochrona przed spóźnioną odmową starej rezerwacji ma dodatkowy test;
  po jej dodaniu ponowiono testy Order i platform-security. Bieżące zestawy
  obejmują łącznie 316 testów Java.
- 87 testów Python oraz testy CLI Control Center — poprawne.
- A11: dwie instancje Payment, trzy wyścigi pierwszej płatności z anulowaniem,
  odmowy stanów niepewnych, uprawnienia, odzyskanie ID rezerwacji, przerwa Payment
  i restart Order po zwolnieniu zapasu/przed lokalnym commitem — poprawne.
  Raport: `tmp/cancellations-f906ed57/report.json`.
- A10: 52 kontrole HTTP i cztery rozliczone pobrania na dwóch instancjach,
  w tym TTL i awaria w czasie rozliczenia — poprawne.
  Raport: `tmp/deferred-payments-68e8f108/report.json`.
- Cała platforma: 203 kontrole HTTP na 15 usługach i dodatkowym produkcyjnym
  Payment — poprawne. Log: `tmp/audit-a11-platform-regression.log`;
  szczegóły usług: `tmp/access-control-e2e-d5bc57bd/`.
- Sprawdzono bieżącą wspólną bibliotekę uprawnień we wszystkich 15 JAR-ach,
  składnię Python i zakończenia LF zmienionych skryptów Bash.
  Logi odbioru: `tmp/audit-a11-*.log`.

Wszystkie zasoby natywnych regresji usunięto. Mapa potwierdza 11/14 P1
(A11 jako opisana blokada MVP); bramka wydania nadal odrzuca A05, A06 i A21.

Następny priorytet: **A06 — odbiór spójności cyklu zamówienia i stare rekordy bez sagi**.
A05 i A21 pozostają otwarte. Zdjęcia nadal są odłożone. Brak wdrożenia na dev/prod.
