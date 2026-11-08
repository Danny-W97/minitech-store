# A16.1 — bezpieczne przyjęcie i decyzje zwrotów/reklamacji

**Status:** A16.1 wdrożono lokalnie i zweryfikowano 26.10.2026. Całe A16
pozostaje częściowe. Ten etap nie zwraca pieniędzy ani nie odwraca skutków
magazynowych, fakturowych lub promocyjnych.

Powiązane zapisy: [rejestr audytu](AUDYT_APLIKACJI_2026-10-23.md#a16),
[postęp Audit_0.1](AUDIT_0.1_POSTEP.md#pakiet-29--a161-bezpieczne-zgoszenia-zwrotu-i-reklamacji-26102026).

## Zakres

Returns weryfikuje zamówienie w Order jako usługa, zanim zapisze nowe zgłoszenie.
Utrwala pozycje i żądane ilości, wiąże treść z klientem i zamówieniem, egzekwuje
kontrolowane przejścia stanu i zapisuje wersjonowaną historię decyzji. Operator
może jawnie zweryfikować starszy, nieterminalny rekord względem aktualnego
zamówienia.

Zwykły klient musi być uwierzytelniony jako ten sam `customerId`, a podany adres
e-mail musi odpowiadać adresowi aktora i zamówienia (bez rozróżniania wielkości
liter). Dalsza obsługa klienta pozostaje ograniczona do zarejestrowanego
klienta. Nie dodano dostępu gościnnego ani nowej polityki prawnej lub terminu.

## Przyjęcie zgłoszenia

`POST /returns` przyjmuje obecne pola `orderId`, `customerId`, `customerEmail`,
`requestType`, `reason`, `description` i opcjonalną listę:

```json
{
  "orderId": 501,
  "customerId": 42,
  "customerEmail": "customer@example.test",
  "requestType": "RETURN",
  "reason": "Towar nie pasuje",
  "description": "Proszę o przyjęcie zwrotu wybranej pozycji.",
  "items": [
    { "orderItemId": 7301, "quantity": 1 }
  ]
}
```

`items` może zawierać od 1 do 100 wybranych wierszy. Brak pola oznacza pełny
zakres zamówienia. Każde ID musi należeć do kanonicznego zamówienia, ilość musi
być dodatnia i nie może przekraczać kupionej ilości. Powtórzone ID są sumowane
z kontrolą przepełnienia. Pusty wybór, niejednoznaczne pozycje i przekroczenie
ilości nie tworzą sprawy. Migawka pozycji przechowuje ID pozycji i produktu,
nazwę, ilość zakupioną i objętą zgłoszeniem; nie przechowuje kwoty refundacji.

Warunki przyjęcia:

- `RETURN`: zakup ma `paymentState=PAID`, `status=COMPLETED` i
  `lifecycleReviewRequired=false`.
- `COMPLAINT`: zakup ma `paymentState=PAID`, stan `PAID`, `SHIPPED` lub
  `COMPLETED` i `lifecycleReviewRequired=false`.

Zakup musi istnieć. Order musi zwrócić zgodne ID, klienta, e-mail i prawidłowe
pozycje. Brak zakupu daje 404, niezgodność właściciela 403, stan/pozycje
niezgodne z regułami 409, a niedostępność Order 503. W żadnym z tych przypadków
nie powstaje sprawa.

W bazie obowiązuje jeden rekord na `(order_id, customer_id, request_type)`.
Zgodne ponowienie identycznego zamiaru odczytuje utrwaloną sprawę, nawet gdy
Order jest chwilowo niedostępny. Inna treść pod tym samym kluczem zwraca 409.
Hash intencji obejmuje klienta, typ, tekst i wybór pozycji. Obecny limit oznacza
jedną sprawę typu `RETURN` i jedną typu `COMPLAINT` na dany zakup. Kolejne lub
korygowane częściowe sprawy wymagają odrębnej polityki i nie są obsługiwane w
tym etapie.

## Decyzje operatora i ponowienia

Przejścia stanu są ograniczone do:

```text
REQUESTED → UNDER_REVIEW → APPROVED
                         ↘ REJECTED
```

Nie ma bezpośredniego zatwierdzenia/odrzucenia z `REQUESTED`, ponownego otwarcia
`REJECTED` ani nowego przejścia do `COMPLETED`. Wszystkie nowe zapisy wymagają
operatora. `APPROVED` wykonuje świeży odczyt Order i ponownie sprawdza, czy
zakup nadal jest kwalifikowany. Jest to administracyjna decyzja o sprawie, a
nie blokada rozproszonego stanu płatności; przyszły proces finansowy musi
ponownie skoordynować stan globalny.

`PATCH /returns/{id}/status` wymaga pełnego polecenia, przykładowo:

```json
{
  "status": "UNDER_REVIEW",
  "expectedVersion": 0,
  "operationId": "2c2fa1a6-66f6-4bb4-bf37-7f7bc6b0717e",
  "note": "Rozpoczęto weryfikację zgłoszenia."
}
```

Wersja nie może być ujemna, `operationId` musi być UUID, a notatka nie może być
pusta i ma limit 1000 znaków. Blokada w SQL serializuje konkurencyjne decyzje.
Spóźniona wersja lub niedozwolone przejście kończy się 409. Unikalny UUID
operacji jest związany z poleceniem, stanem docelowym, wersją i notatką; reuse
UUID z inną treścią zwraca konflikt.

Po utracie odpowiedzi należy ponowić dokładnie tę samą komendę z tym samym UUID.
Replay zwraca **bieżącą** sprawę, więc wcześniejszy retry nie cofa późniejszej
decyzji. Dziennik nadal zachowuje pierwotny wynik i wersję operacji.
`GET /returns/{id}/history` pokazuje stronicowane zdarzenia z wersją, przejściem,
notatką, aktorem, zaobserwowanym stanem Order/płatności, UUID i czasem. Historia
jest wyłącznie dla operatora; gdy skonfigurowany token operatora nie zawiera
tożsamości osoby, zapisuje się `configured-operator`, a nie wymyśloną osobę.

Nowe `COMPLETED` jest odrzucane kodem `RETURN_DOMAIN_EFFECTS_NOT_RECONCILED`.
Zatwierdzona sprawa zwraca `effectsState=DOMAIN_RECONCILIATION_REQUIRED`; API
nie inicjuje płatności, refundacji Stripe, rezerwacji/zwolnienia zapasu,
korekty faktury ani rozliczenia kuponu.

## Starsze rekordy i V3

Migracja `V3__verified_return_workflow.sql` zachowuje wcześniejsze statusy,
teksty i notatki, dodaje wersję oraz opcjonalny dowód weryfikacji, tworzy tabele
pozycji i zdarzeń. Dla starych rekordów dodaje zdarzenie `LEGACY_SNAPSHOT` z
`unknown-legacy-actor` i czasem migracji. Ten czas oznacza migrację, nie
wcześniejszą decyzję.

Stary rekord bez dowodu ma `requiresReconciliation=true` oraz
`effectsState=LEGACY_UNVERIFIED`; nie można go zatwierdzać ani zmieniać, zanim
operator go nie zweryfikuje. `POST /returns/{id}/reconcile` wymaga
`expectedVersion`, stałego `operationId` i notatki. Ponownie pobiera Order,
weryfikuje właściciela i stan, przypisuje pełny zakres pozycji zgodny ze starym
modelem i dopisuje zdarzenie `LEGACY_VERIFIED`. Nie zmienia dawnego statusu ani
nie udaje, że zna wcześniejsze skutki finansowe. Stare rekordy terminalne
pozostają tylko do odczytu.

## Control Center i sklep

Control Center udostępnia odczyt sprawy/wersji, stronicowaną historię, decyzję
z wersją i stabilnym UUID oraz jawne uzgodnienie starej sprawy. CLI przy błędzie
niejednoznacznym przypomina, by zachować UUID i wersję oraz sprawdzić stan i
historię przed kolejnym poleceniem. Formularz sklepu pokazuje zgłoszenie tylko
dla kwalifikowanego, opłaconego zakupu; pozwala wybrać ilości z rzeczywistych
pozycji. Puste ilości nie wysyłają żądania. Komunikat po wysłaniu informuje,
że zgłoszenie nie uruchomiło zwrotu pieniędzy.

## Wydanie

V3 musi zostać zastosowane przez Returns Service, a wszystkie jego repliki
muszą używać tej samej wersji kodu. Zmiana jest kompatybilna z odczytem starych
rekordów, ale stare polecenia operatora zmiany statusu bez `expectedVersion`,
`operationId` i `note` nie przejdą walidacji. Przed aktywacją decyzji operatora
należy zaktualizować Control Center/CLI razem z API. Po migracji stare sprawy
pozostają wymagające jawnego uzgodnienia. Pakiet przygotowano i zweryfikowano
lokalnie; nie wdrożono go na dev/prod.

## Pozostały zakres pełnego A16

- Faktyczne rozliczenie pieniędzy oraz ponowna kontrola/koordynacja stanu
  płatności między Order, Payment i procesorem. Stripe A05 jest odłożone przez
  użytkownika i wymaga jego ręcznego przygotowania oraz odbioru sandboxu.
- Jawne skutki magazynowe, w tym reguły przyjęcia zwracanego produktu.
- Faktura/korekta i kupon oraz uzgodnienie wszystkich skutków zakupu zgodnie
  z pełnym procesem kompensacji A11.
- Trwały workflow wykonania/uzgodnienia, który kończy sprawę dopiero po
  potwierdzeniu wymaganych skutków.
- Polityka kolejnych spraw częściowych i korekt zakresu istniejącej sprawy.

Nie dodano ustawowego terminu ani nowej interpretacji prawnej. Pełne A16 będzie
można oznaczyć jako wykonane dopiero po zdefiniowaniu i odebraniu powyższego
procesu domenowego.

## Dowody weryfikacji

- 15 testów Returns (w tym 12 testów workflow), 98 testów Python/CC,
  10 testów przeglądarkowych Chromium i regresja CLI — poprawne.
- Regresja odizolowanego PostgreSQL zaliczyła 150 kontroli HTTP na dwóch
  replikach Returns i prawdziwych Order/Payment/Inventory/Delivery. Sprawdziła
  migrację V2→V3, niezmienność starych danych, właściciela, niedostępność Order,
  ilości częściowe, wyścigi INSERT i decyzji, utratę odpowiedzi/procesu,
  uprawnienia operatora i blokadę skutków finansowych.
- Raport: `tmp/returns-workflow-bbb728f9/report.json`; `refundsInitiated=0`.
- Regresję dodano do `.github/workflows/ci.yml`. Wspólny JAR security ma zgodną
  zawartość we wszystkich 15 zbudowanych aplikacjach.
