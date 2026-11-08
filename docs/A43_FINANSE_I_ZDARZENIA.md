# A43.36 — finanse, kopie techniczne i spóźnione zdarzenia

Decyzje użytkownika z 29.10.2026, branch Audit_0.1. Aktywacja pozostaje
wyłączona. Dokument opisuje implementację i zakres odbioru lokalnego;
końcowe wyniki są w `tmp/audit-a43-36/report.json`.

## Wybrana polityka

Faktury, korekty oraz niezbędne rejestry płatności, kwoty, waluty,
identyfikatory dostawcy i klucze procesów pozostają do A43.40. Nie
przeliczamy ani nie usuwamy dokumentów, nie anulujemy zakupów, nie
uruchamiamy zwrotu pieniędzy ani zwalniania zapasu lub limitów kuponów.

Po bezpiecznym uzgodnieniu usuwamy zbędny kontakt/adres i swobodne treści
z zakończonych kopii technicznych: opublikowanych outboxów, zakończonych
follow-up, wysłanych powiadomień i rozwiązanych DLQ. Minimalne ID, wyniki,
fingerprinty i powiązania tożsamości pozostają pseudonimowe; nie są anonimowe.
Nie mają automatycznego TTL przed decyzją A43.40. Otwarty, aktywny lub
niejednoznaczny proces czeka, bez wymuszenia anulowania albo replay.

Stripe wymaga odrębnego potwierdzenia dostawcy: rekordy ze śladami Stripe,
nieuzgodnione zwroty lub niepewny wynik zostają oczekujące. A05 nadal jest
odłożone; ten etap nie wykonuje żadnej operacji w zewnętrznym Stripe.

## Nowa i historyczna tożsamość zdarzeń

Nowe zdarzenie v2 ma trwałe losowe `payment-v2-<UUID>`, zapisane raz przed
publikacją. `event_key` zawiera skrót starego klucza, bez jawnego e-maila.
Unikalny indeks zgodności zachowuje semantykę deduplikacji starego klucza,
również gdy równocześnie działają stare i nowe ścieżki zapisu. UTF-8 jest
częścią protokołu; nie zmieniamy wielkości liter ani treści starego klucza
przed wyprowadzeniem skrótu.

Czytnik weryfikuje powiązanie nowego UUID ze starym ID i odtwarza jedynie
kanoniczną reprezentację do porównania fingerprintu otrzymanego komunikatu.
Nie odtwarza skompaktowanego outboxu. Zachowuje stary fingerprint w inboxie,
wiąże UUID w `event_identity_aliases` i zapisuje kompatybilny wpis dla starego
czytnika. Alias, oba wpisy inboxu i skutek biznesowy zatwierdzają się w jednej
transakcji. Ponowne użycie ID ze zmienioną kwotą, odbiorcą, zamówieniem lub
inną treścią pozostaje konfliktem; różnice reprezentacji nie są ignorowane.

`messaging.payment-identity-v2.enabled` /
`MESSAGING_PAYMENT_IDENTITY_V2_ENABLED` jest domyślnie false. Najpierw
migracje i czytniki, potem producent. Publisher v2 sprawdza zdolność obu
odbiorców, aktywność konsumentów i obecność wymaganych tabel/kolumn. Do
tego czasu nowe zdarzenie v2 pozostaje trwałe w outboxie, bez publikacji.
Stary pending/retry zachowuje v1 i swoje dotychczasowe ID. Nie requeue'ujemy
opublikowanego lub skompaktowanego zdarzenia. Historyczne ścieżki bez
eventId/eventKey nadal respektują stream+recordId; nie przypisujemy im
nowej tożsamości przez zgadywanie po e-mailu.

## Potwierdzenie finansów dla A43.35

`PRIVACY_FINANCE_CLEANUP_ENABLED` jest domyślnie false w Customer, Order
i siedmiu adapterach. Potrzebna jest również aktywacja adapterów A43.35
oraz polityka środowiska A43.40. Wyłączenie przełącznika po rozpoczęciu
nie usuwa istniejących blokad i nie pozwala przywrócić danych.

Nowy podgląd wiąże oddzielny hash polityki A43.36. Dawne potwierdzenia
nie są rozszerzane automatycznie. Customer po wszystkich 14 barierach
przekazuje zgodę do Order. Order prowadzi krótkie, wznawialne zadanie
na konkretne zamówienie i wersję, w kolejności: Returns, Payment,
Inventory, Promotion, Delivery, Billing, Notification. Każdy adapter
sprawdza własną barierę, kanoniczną własność i wersję oraz lokalne procesy.

Po zamknięciu procesów adapter zapisuje trwałą blokadę lokalnych zapisów
i usuwa wyłącznie dozwolone kopie. Transakcje SQL nie obejmują HTTP ani
Redis. Maksymalnie 100 wierszy na tabelę i krok, 100 rekordów streamu na
stronę; do czterech kroków na przebieg workera. Historia obejmująca ponad
100 zdarzeń płatności jednego zamówienia wymaga oceny zamiast ucięcia
listy dowodów. Billing i Notification sprawdzają wymagane wpisy inboxu,
a Notification również status wysłania. Własne dokumenty Billing pozostają.

Dopiero siedem trwałych potwierdzeń, ponowna kontrola wersji i lokalnej
kwalifikacji Order pozwalają zatwierdzić stan READY oraz zamrozić Order.
`VerifiedCommerceFinanceGate` czyta ten dowód lokalnie, bez HTTP w
transakcji. Nie przyjmuje flagi „uzgodniono” od operatora ani klienta.
Zmiana wersji kończy się oceną, nie automatycznym zatwierdzeniem.

Dzierżawa odrzuca spóźnione odpowiedzi. MORE/WAIT nie wyczerpują budżetu
błędów. Osiem błędów wymaga jawnego ponowienia; kolejne automatyczne
przekazanie tej samej zgody nie resetuje limitu. Dopiero nowa generacja
ponowienia operatora może wznowić błędy, bez zmiany zgody albo kwalifikacji.
Order odkrywa zamówienia niezależnie od kursorów A43.35: jedno ID na
wywołanie statusu/zgody, z trwałym scan_after i ponownym obiegiem po końcu.
Aktywne zamówienie nie zatrzymuje odkrywania kolejnych; błędy i zmiany
wersji nadal wymagają odpowiedniego dowodu lub jawnej oceny.

Gotowy dowód budzi oczekujące kroki A43.35, które ponownie sprawdzają
warunki i czyszczą Order jako ostatni.

## Redis i spóźnione kopie

Publikacja używa atomowej kontroli `privacy:stream:<stream>:order:<id>`.
Znacznik zawiera tylko request ID i granicę skanu, bez kontaktu i bez TTL.
Po jego zapisaniu prywatny payload nie może zostać dodany przez aktualny
publisher. Nowa niezbędna korekta faktury może nadal opublikować wyłącznie
minimalne pola finansowe: bez e-maila, nazwy nabywcy i swobodnego powodu.
Triggery odrzucają prywatny outbox także przy próbie zapisu starym kodem.

Skan obejmuje jawny stream i zamkniętą granicę, bez SCAN całego Redis.
XDEL jest atomowo dopuszczony tylko dla własnego orderId, po dostarczeniu
i ACK wszystkich faktycznych grup oraz obecności grup wymaganych. Nie
przesuwamy offsetów, nie usuwamy PEL, grup ani klucza streamu. Brak polityki
lub grupy daje WAITING_STREAM_POLICY, a pending/unread WAITING_STREAM_ACK.
Przy pustej polityce odbiorców faktur rekord pozostaje do decyzji A43.40;
brak pasujących rekordów nie wymaga tworzenia fikcyjnej grupy.

Aktualny konsument po trwałym commicie inboxu i ACK sprawdza również
spóźnione kopie dla już zablokowanego orderId. Usuwa je dopiero po ACK
wszystkich odbiorców. Nowy lub niepoprawny komunikat dotyczący zablokowanej
sprawy nie tworzy powiadomienia ani surowego payloadu błędu: zostaje minimalny
fingerprint/ID i SUPPRESSED. Istniejący fingerprint nadal wykrywa konflikt.
Kod działa dla samodzielnego Redis 7; wdrożenie Redis Cluster wymaga osobnego
projektu wspólnego hash slotu.

Przed aktywacją wszystkie repliki producentów i odbiorców muszą obsługiwać
blokady. Nie wolno po aktywacji przywracać starego obrazu pomijającego tę
ochronę. Próby jego zapisu blokuje również SQL, a odtwarzanie starych
streamów/RDB/AOF i obrazów wymaga bariery restore w A43.37–A43.39. Ten etap
nie deklaruje zakończenia procedur backupów ani usunięcia kopii u dostawcy.

## Konto, reset hasła i osobny czat

CUSTOMER_REGISTERED ma historyczny source_id klienta. PASSWORD_RESET_REQUESTED
ma source_id tokenu resetu, który może liczbowo pokrywać się z cudzym kontem.
Nowe komunikaty konta niosą jawny customerId od uwierzytelnionego
customer-service. Nowy reset bez tego dowodu jest odrzucany. Powiązanie
pozostaje oddzielone od klucza deduplikacji i ID tokenu.

Automatyczne czyszczenie obejmuje pewne wpisy rejestracji i nowe resety
po jawnym ID klienta oraz DLQ po zweryfikowanym powiązaniu z powiadomieniem.
Stare resety bez tego ID pozostają do oceny w A43.39, bez przypisywania przez
e-mail lub przypadkowo równy numer. Aktywna/niepewna wysyłka także czeka.
Nowe konto pod tym samym adresem ma inne ID i nie przejmuje starej historii.

Chat nadal wymaga tokenu rozmowy i wybranej w A43.34 ręcznej oceny. Jego
outbox zawiera ID/stan, a treść pobiera z rozmowy dopiero przy wysyłce;
nie tworzymy powiązania z kontem. Typy CHAT nie wchodzą do czyszczenia Order
nawet przy takim samym source_id lub adresie. Nie kasujemy rozmów ani ich
powiadomień przez żądanie konta. Kopie i niekwalifikowane dane są w A43.39.

## Status i odbiór

Status właściciela pokazuje tylko bezpieczne kody/liczniki i zachowanie
dokumentów. DONE dotyczy uzgodnionych, znanych kopii technicznych, nie
całkowitego usunięcia wszystkich danych. Historyczne niepowiązane resety,
niekwalifikowane payloady, czat i dostawcy nadal mają osobne procedury.
Globalne complete/dataErased/fullErasureComplete pozostają false.

Odbiór lokalny: macierz v1/v2 i rzeczywistych starych obrazów, konflikty,
rollback i restart, utrata odpowiedzi/ACK, PEL/unread/brak grup, blokady
starych zapisów i replay, izolacja dwóch kont oraz zachowanie dokumentów,
kwot, ilości, limitów i kluczy. Testy używają wyłącznie jednorazowych baz,
Redis i lokalnego Mailpit. Brak wdrożenia dev/prod, restartu CC, dostępu
do rzeczywistych danych i operacji Stripe. Aktywacja/okresy należą do A43.40.

A43.37 dodaje wymagane podpisane potwierdzenie niezależnego rejestru przed
czyszczeniem oraz odtworzenie w kwarantannie. Konfiguracja i ograniczenia:
[backupy i rejestr usunięć](A43_BACKUPY_I_REJESTR_USUNIEC.md).
