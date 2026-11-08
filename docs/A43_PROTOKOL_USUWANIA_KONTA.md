# A43.33 — żądanie usunięcia i bariery zapisów

Decyzje użytkownika z **29.10.2026**:

- trwały znacznik zawiera tylko ID klienta, ID żądania i stan BLOCKED;
  bez e-maila ani jego skrótu, bez automatycznego TTL do ustalenia A43.40;
- status jednego żądania można czytać osobnym losowym tokenem przez **7 dni
  od potwierdzenia (dokładnie 168 godzin, niezależnie od zmiany czasu)**;
  bez wydawania danych konta, odzyskiwania przez e-mail
  i prawa cofania blokady. Później status sprawdza uprawniona obsługa.

Znacznik jest pseudonimowy, nie anonimowy. Chroni dawny identyfikator konta.
Nie wprowadzono trwałego zakazu ponownej rejestracji tego adresu pod nowym ID.
Obecny rekord Customer pozostaje zablokowany; zwolnienie/anonimizacja profilu
i treści są osobnym zakresem A43.34. Nie zgadujemy okresów dokumentów ani
reguł redakcji treści — odpowiednie adaptery i decyzje pozostają A43.34–A43.40.

## Aktualizacja A43.34 — wykonane lokalnie

Dodano domyślnie wyłączone adaptery lokalnego czyszczenia i nowy jawny
zakres podglądu. Stare potwierdzenia nie otrzymują automatycznie rozszerzenia
polityki. Czat wymaga osobnego dowodu; wybrano i wdrożono wniosek po tokenie
ze statusem MANUAL_REVIEW, bez automatycznego kasowania.
[Decyzje, aktywacja i granice](A43_USUWANIE_PROFILU_I_TRESCI.md).

## Protokół klienta

Wszystkie ścieżki działają również przez Gateway z `/api` i `/api/v1`.
Odpowiedzi są prywatnym JSON z no-store/nosniff.

1. POST `/customers/me/privacy-requests/preview`, aktualny Bearer klienta
   i UUID w `Idempotency-Key`. Podgląd jest ważny 10 minut. Pokazuje plan
   dla 14 usług, skutki, granice oraz hash planu; nie blokuje konta.
2. Klient generuje i bezpiecznie zachowuje losowy token statusu: co najmniej
   32 bajty entropii, zapis base64url (43–128 znaków). Token nie może być
   umieszczany w URL, logu ani wysyłany do innej usługi. Serwer przechowuje
   tylko SHA-256 powiązany z UUID żądania; klient zna sekret przed wysłaniem
   potwierdzenia, więc utrata odpowiedzi nie pozbawia go odczytu statusu.
3. POST `/customers/me/privacy-requests/{id}/confirm`, Bearer i JSON
   `planHash`, `password`, `statusToken`, `confirmation="DELETE_MY_ACCOUNT"`.
   Weryfikacja aktualnego właściciela i hasła pod blokadą wiersza Customer;
   przy aktywnym MFA sesja musi zawierać podpisane potwierdzenie odpowiedniej
   nadal skonfigurowanej metody. Nagłówek/claim klienta nie zastępuje MFA.
4. Atomowo powstają znacznik i 14 trwałych kroków, konto staje się disabled,
   refresh tokens są unieważniane, weryfikacja konta nie może go reaktywować.
   Odpowiedź 202 wygasza cookie refresh. Klient odrzuca tokeny dostępu w RAM.
   Nie ma samoobsługowego cofania potwierdzenia. Nie uruchamia się anulowania
   zakupu, zwrotu pieniędzy, wysyłki ani fizycznego kasowania danych domen.
5. GET `/customers/privacy-requests/{id}/status` z
   `X-Privacy-Status-Token`, bez JWT konta. Tylko bezpieczne stany usług i
   kody oraz daty; bez customer ID, adresów, dokumentów, hashy i sekretów.
   Token nie uprawnia do eksportu konta, retry, anulowania lub zmiany planu.
   Brak/błędny dowód nie ujawnia statusu. Odczyt/retry nie wydłużają 7 dni.

Po utracie odpowiedzi potwierdzenia należy odczytać status tym samym tokenem,
zamiast tworzyć drugie żądanie. Unikalne customer_id i (request_id,service)
chronią przed dublowaniem. Przed potwierdzeniem można ponowić podgląd;
stary hash, zły właściciel, hasło lub wygasły podgląd nie zakładają bariery.

Obsługa: GET `/customers/privacy-requests/{id}/operator-status` i POST
`/customers/privacy-requests/{id}/retry`, wyłącznie aktualna tożsamość
operatora. Retry ponawia niepotwierdzone bariery, nie usuwa znacznika,
nie zmienia planu ani nie przedłuża tokenu. Nie dodano widoku CC.

## Co oznacza stan

| Stan | Znaczenie |
| --- | --- |
| PREVIEW | Podgląd, żadnego odcięcia konta. |
| FENCING | Dostęp został unieważniony; nie wszystkie lokalne bariery są potwierdzone. |
| NEEDS_ATTENTION | Próby wyczerpane; wymagana naprawa przyczyny i świadome ponowienie przez obsługę. |
| WAITING_ADAPTERS | Wszystkie bariery potwierdzone; nadal oczekiwanie na adaptery A43.34–A43.39 i polityki. |

**complete=false i dataErased=false zawsze.** FENCED w usłudze oznacza tylko
barierę nowych operacji konta, nigdy „dane usunięte”. Plan rozróżnia:
usunięcie po adapterze, ręczną ocenę/anonimizację treści, zachowanie do
ustalenia retencji, oczekiwanie na zamknięcie procesów, osobny dowód Chat
i dane nieprzypisane do konta. Bez podjętej decyzji dane nie są kasowane.

## Wyścigi i granice bariery

- Każda z 14 baz otrzymuje identyczny minimalny `account_privacy_fences`.
  Wyłącznie customer-service może zainstalować barierę przez wąski POST
  `/internal/privacy/fences/{customerId}`. Pozostałe role/metody są odrzucone.
- Wspólny adapter DataSource na PostgreSQL przechwytuje zapisy aktualnego
  aktora CUSTOMER. Pierwszy zapis transakcji bierze współdzieloną blokadę
  doradczą `-customerId`, sprawdza lokalny znacznik i trzyma blokadę do commit/
  rollback. Nie otwiera transakcji wokół HTTP. Zapis klienta w autocommit
  jest odrzucany. Odczyt i SQL pomocniczy nie są nowym pozwoleniem na zapis.
- Instalacja znacznika bierze wyłączną blokadę tego samego klucza, czeka na
  wcześniej dopuszczone transakcje i zapisuje znacznik atomowo. Po ACK stare
  żądanie z dawnym aktorem nie wykona kolejnego zapisu. Inny klient i inne
  bazy nie otrzymują globalnej blokady. H2 w jednostkowych testach nie jest
  dowodem bariery — odbiór konkurencji wymaga rzeczywistego PostgreSQL.
  Odbiór obejmuje obecne zapisy usług w READ COMMITTED i ich zapytania SQL.
  Adapter nie analizuje dowolnego SQL; nowy zapis przez funkcję SELECT lub
  zapis klienta w innym poziomie izolacji wymaga rozszerzenia i odbioru
  przed użyciem. Obecne transakcje REPEATABLE READ dotyczą odczytów oraz
  operacji katalogu wykonywanych poza rolą CUSTOMER.
- Triggery dodatkowo blokują nowe przypisania customer_id dla Customer/
  tokenów/MFA, Order, Wishlist, Review, Recommendation i Returns, także
  przy tożsamości usługi. Reaktywacja/zmiana profilu zablokowanego Customer
  jest niedozwolona. Istniejące rozliczenia/statusy i procesy domenowe mogą
  dobiegać końca; nie jest to przełącznik zatrzymania zakupów.
- W fazie FENCING wcześniej dopuszczone operacje mogą jeszcze zakończyć
  zapis. Żaden etap fizycznego usuwania nie może ruszyć przed potwierdzeniem
  wszystkich barier. Dostęp JWT/refresh jest odcięty w Customer od razu.
- To nie implementuje anonimizacji payloadów, zatrzymania historycznego
  replay ani migracji tożsamości zdarzeń. Takie odtworzenie treści przez
  istniejące procesy i wiadomości rozliczają A43.35–A43.37, nadal otwarte.
  Chat z capability zachowuje odrębny dowód, bez dopasowania po e-mailu.

## Wznowienia, limity i wdrożenie

Worker wykonuje do czterech kroków na przebieg, bez trzymania transakcji
podczas HTTP. Claim ma 15 sekund i token wykonania, odpowiedzi maksymalnie
4 KiB i ograniczony czas; stan zapisywany jest osobno. Do ośmiu prób kroku
z odstępem; spóźniony wynik nie zastępuje nowego claim. Powtórny POST tej samej
bariery jest idempotentny, inny UUID dla tego customerId daje konflikt.
Przerwanie po ACK usługi nie dubluje skutków. Brak usługi, migracji lub
wiarygodnego potwierdzenia pozostawia jawny brak, nie sukces.

Czyszczenie usuwa do 25 wygasłych podglądów i do 25 hashy wygasłych tokenów
statusu na przebieg. Potwierdzone żądania i znaczniki nie mają automatycznego
TTL zgodnie z decyzją użytkownika; retencja/kopie wymagają A43.37–A43.40.
Minimalne kopie są zasobami osobowymi rejestru, a nie anonimowymi tombstones.

Przed użyciem w środowisku wszystkie 14 migracji i wersje usług z barierami
muszą być wdrożone. Starszy peer bez endpointu nie otrzyma ACK, a fizyczne
usuwanie pozostaje zablokowane. W tym etapie uruchamiamy tylko izolowane
fixture, bez zmian baz użytkownika i bez wdrożenia dev/prod.

A05 (klucze Stripe/sandbox), całe A41 i zmiany samych zdjęć pozostają odłożone.

## Odbiór lokalny 29.10.2026

769 testów jednostkowych Java (0 pominiętych), 14 integracyjnych
PostgreSQL i 276 Python — PASS. Sześć grup HTTP na rzeczywistych Customer,
Gateway, Wishlist i Order, trzech bazach i własnym Redis; 11 peerów
kontrolowanych. Regresja eksportu: 6 grup — PASS.
Oracle sprawdził 14 schematów i 11 odmów nowych przypisań/reaktywacji;
53 zasoby manifestów Kubernetes przeszły walidację. Test DST wymusza stałe 604800
sekund przy zmianie strefy sesji. Test CTE odróżnia odczyt od zapisu.
Szczegóły: `tmp/audit-a43-33/report.json`. Pozostaje 8 etapów; następny A43.34.
