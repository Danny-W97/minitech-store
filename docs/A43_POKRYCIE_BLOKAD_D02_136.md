# A43.40 — pokrycie źródeł blokad D02, pakiet 136

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.14**.
Zakres lokalny, A43.40/.41 otwarte; czyszczenie pozostaje nieaktywne.

## Wdrożona zmiana

Dodano Inventory do protokołu i kolektora negatywnych obserwacji blokad.
Odczyt wykrywa rezerwacje o statusie innym niż CONFIRMED lub RELEASED.
Zwraca wtedy BLOCKED dla NO_ACTIVE_RETRY_OR_DLQ. Rezerwacja zakończona
albo brak rezerwacji daje UNKNOWN, podobnie jak informacja o sprawach
i blokadach dalszego zachowania. Nie potwierdza globalnego braku blokad.

Inventory udostępnia chroniony POST `/internal/privacy/commerce-retention/peer-guards`
wyłącznie Order, z limitem żądania/odpowiedzi 2048 bajtów i private/no-store.
Odczyt działa pod istniejącą blokadą transakcyjną i sprawdza wiązanie źródła.
Kwarantanna, zmieniona rewizja, generacja lub epoka nie daje poprawnej obserwacji.
Flaga `privacy.commerce-retention-inventory-evidence.enabled` pozostaje
domyślnie wyłączona; nie zmieniano konfiguracji środowisk.

Order V42 rozszerza dozwolone źródła niezmiennej historii o Inventory.
Limit wynika z klucza głównego i siedmiu dopuszczonych źródeł: jedna obserwacja
na źródło/generację/fazę, maksymalnie siedem. Ponowienie z nowym challenge
nie dopisuje kolejnego rekordu. Historia nie jest bieżącym upoważnieniem.
Nie zmieniono biznesowego protokołu PeerRetentionProtocol, który nadal
obsługuje sześć usług; Inventory ma własny protokół danych biznesowych.

## Macierz ośmiu uczestników

Poniżej jest pokrycie kanonicznych odczytów, a nie kompletna ewidencja
wszystkich miejsc blokad lub deklaracja odbioru rzeczywistych środowisk.

| Uczestnik | Odczytywane lokalne źródło | Wykrywana blokada | Pozostająca luka / wynik braku blokady |
|---|---|---|---|
| ORDER | commerce_retention_holds i hold_scopes dla zakupu/konta; order_sagas | Jawnie zapisana blokada; aktywna saga, lease lub retry | Lokalny rejestr nie dowodzi kompletności wszystkich wniosków/sporów. Brak wpisu nie daje globalnego CONFIRMED. |
| RETURNS | return_requests | Status inny niż REJECTED konserwatywnie blokuje sprawę | Status COMPLETED nie dowodzi uzgodnienia efektów A16. Historia, ponowienia i ręczne sprawy nadal wymagają pełnego adaptera. |
| PAYMENT | payments, payment_follow_ups, payment_outbox_events | Nieterminalny stan, hold/kompensacja Stripe, aktywna dzierżawa lub nieopublikowany skutek | Stan operatora i niezależne kopie nie są potwierdzone. D01 chroni rozliczenia; sam brak aktywnych rekordów daje UNKNOWN. |
| INVENTORY | inventory_reservations pod blokadą źródła — nowy zakres 136 | Status spoza CONFIRMED/RELEASED | Brak rekordu lub stan terminalny nie potwierdza braku historycznych, zewnętrznych i przyszłych ponowień. UNKNOWN. |
| PROMOTION | coupon_reservations | Stan spoza CONSUMED/RELEASED | Limity, historia użyć i deduplikacja nie są zwalniane; brak aktywnej rezerwacji daje UNKNOWN. |
| DELIVERY | shipments | Nieterminalna przesyłka, brak SYNCED, token/termin synchronizacji lub retry | Zewnętrzny przewoźnik, tracking i kopie nie są objęte kompletnym dowodem. UNKNOWN. |
| BILLING | invoice_outbox_events, event_inbox | Niepotwierdzona publikacja lub niezaakceptowany inbox | Faktury/korekty i zależności finansowe pozostają chronione D01. UNKNOWN. |
| NOTIFICATION | notifications, notification_dead_letters, event_inbox dla zdefiniowanych zdarzeń zakupu | Niewysłane powiadomienie, aktywna dzierżawa/retry, otwarty DLQ lub niezaakceptowany inbox | Inne typy komunikatów, SMTP/Mailpit i kopie nie tworzą kompletnego dowodu. UNKNOWN. |

Każde źródło zdalne zwraca NO_PRESERVATION_HOLD=UNKNOWN. Rejestr Order
przyjmuje lokalne blokady, ale nie ma potwierdzenia kompletnego pokrycia
wszystkich źródeł wniosków i sporów. Siedem obserwacji UNKNOWN nie składa
się na jeden dowód CONFIRMED. Podpis wpisu rejestru D09 i skrót planu
również nie potwierdzają pełnej polityki lub aktualnej niezależnej głowy.

## Zakres i świeżość dowodu

- Root wiąże odczyt z konkretnym Subject: ID zakupu i właściciela (0 dla
  zakupu gościa), wersją zamówienia, generacją łańcucha oraz fazą i polityką.
- Żądanie źródła zawiera ID zakupu, generację, wersję, fazę, epokę wiązania,
  rewizję źródła, politykę i nowy losowy challenge. Nie jest to żądanie po e-mailu.
- Odpowiedź musi odtwarzać cały zakres/challenge i mieścić się w oknie
  15 sekund wieku / 5 sekund tolerancji przyszłości. Root ponownie sprawdza
  świeżość po uzyskaniu lokalnych blokad SQL.
- Przed i po RPC Root kontroluje bieżącą generację, proof_revision,
  binding i rewizje blokad zakupu/konta. Zmiana unieważnia wynik; SQL
  nie pozostaje otwarte przez zdalne wywołanie.
- Potwierdzenie pozytywne jest odrzucane. Niezmienny zapis historyczny
  obejmuje zakres i semantyczny skrót stanów, bez tokenu, e-maila lub treści.
  Nie zastępuje przyszłego dowodu kompletności, wspólnego punktu czasu
  ani kontroli zależności podczas faktycznej mutacji.

## Odbiór

**121 Java (107 PostgreSQL / 14 jednostkowych) + 90 unikalnych Python PASS**, bez błędów i pominięć. 24 nowe przypadki
(7 jednostkowych protokołu, 17 PostgreSQL/HTTP), pozostałe regresje.
Bramki strukturalne/candidate PASS; gotowość NOT_READY / kod 2.
Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-e7ff4722/report.json`; zachowano
3 historyczne obserwacje, w tym Inventory, bez aktualnego upoważnienia.
Pierwotny test zmiany BOUND skorygowano do legalnej kwarantanny
i powtórzono zestaw HTTP. Wyniki i odciski źródeł:
`tmp/a43-guard-coverage-136/report.json`.
Inwentarz pozostaje **118 tabel / 1110 kolumn**; V42 zwiększa liczbę
migracji do **210**, bez nowej tabeli lub kolumny. Migrację zastosowano
wyłącznie na fiksturach. Drill restore ma obejmować nowy historyczny wpis
Inventory i zachować go bez nadawania mu aktualnego upoważnienia.

HTTP używa rzeczywistych kontrolerów/filtrów i baz, z testową introspekcją.
Nie jest to odbiór uruchomionych aplikacji dev/test, mieszanych wersji
lub pełnego A43.41. Brak mutacji, schedulera, zmiany sekretów i aktywacji.

## Punkt wznowienia

Kontynuować od trwałego, wersjonowanego pokrycia źródeł blokad i spraw:
rozliczyć luki macierzy przed dopuszczeniem jakiegokolwiek pozytywnego
dowodu. Potem protokół upoważnienia, współbieżnej mutacji i jej odzyskiwania
D02 oraz integracje magazynów. Sam odczyt siedmiu źródeł ani zakończone
odzyskiwanie RELEASE nie pozwalają uruchomić mutacji.

Decyzje D01/P01, D09/P03–P05/SAFE, P09 oraz rzeczywisty odbiór ról/rejestru
pozostają odłożone. Zakresy wymagające wartości/decyzji użytkownika odkładać
i wykonywać kolejne niezależne prace. A05, A41 i same zdjęcia A27–A31/A40
pozostają odłożone. `activationAllowed=false`, `runtimeWired=false`.
