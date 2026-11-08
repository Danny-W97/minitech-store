# A43.40 — uwierzytelnione dostarczanie blokad, pakiet 140

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.18**.
Zakres lokalny; A43.40/.41 otwarte. Czyszczenie pozostaje wyłączone.

## Przebieg

`CommerceRetentionHoldDelivery.deliver` jawnie dostarcza jedno żądanie do
jednego z siedmiu źródeł. Zakup, referencja sprawy i request_id muszą pasować
do trwałego wniosku z 139. Źródłowy identyfikator, rodzaj i powód pobierane
są z SQL, a nie z dodatkowej deklaracji wywołującego.

Przed RPC Order zatwierdza niezmienną próbę. Niepewny COMMIT tej próby
nie uruchamia wysyłki. Każda para wniosek/źródło ma do 8 prób, łącznie
do 56 na wniosek. Brak automatycznego retry, skanowania i schedulera.
Współbieżne wywołania mogą wykonać ten sam idempotentny skutek; nie dodano
ekskluzywnej dzierżawy workera. Sekwencję i budżet egzekwuje SQL.

RPC odbywa się poza transakcją Root. Osobny POST
`/internal/privacy/commerce-retention/source-holds` przyjmuje tylko usługę
Order, z uwierzytelnieniem istniejącego klienta usługowego. Źródło sprawdza
swój typ i istnienie zakupu w source_state. Brak wpisu daje UNKNOWN_PURCHASE,
konflikt identyfikatora daje CONFLICT, kwarantanna daje QUARANTINED;
żaden z tych wyników nie jest potwierdzeniem.

RECORDED powstaje po lokalnym COMMIT źródła. Zewnętrzna transakcja
wywołującego jest odrzucana po stronie Root i odbiornika. Ponowienie używa
tego samego źródłowego request_id, bez dodatkowej blokady lub rewizji.
Protokół ma limit 2048 bajtów, ścisłą strukturę i kontrolę całego polecenia
oraz UUID blokady. Nie przekazuje kontaktu, treści lub materiałów sprawy.

## Potwierdzenia i niepewność

Order zapisuje potwierdzenie wyłącznie dla RECORDED dokładnego żądania,
z powiązaniem do planu, konkretnej próby i źródłowego UUID blokady.
SQL odrzuca pożyczenie identyfikatora innego źródła/próby. Historia jest
niezmienna dla zwykłego UPDATE/DELETE. Zapisane potwierdzenie kończy kolejne
wywołanie ALREADY_RECORDED bez nowego RPC.

Timeout, utrata odpowiedzi, konflikt i błąd SQL pozostawiają brak
potwierdzenia. Nie zakładamy rollbacku źródła. Jawne ponowienie jest
idempotentne. Utrata odpowiedzi COMMIT potwierdzenia w Order zwraca
UNAVAILABLE; kolejna próba odczytuje zachowany zapis bez ponownego RPC.
Kwarantanna rodzica/znanego zakresu jest sprawdzana przed i po RPC oraz
przy zapisie potwierdzenia. Późna odpowiedź nie odblokowuje wniosku.

Potwierdzenie jest historią negatywnego zapisu, nie aktualnym dowodem
globalnej kompletności, stanu odtworzonego oddzielnie źródła, własności
sprawy lub zgody na mutację. Wyniki nadal mają activationAllowed=false
i completeCaseCoverage=false. Lokalna rejestracja z 139 zachowuje własne
remoteDeliveryConfirmed=false; nie zmienia się automatycznie w dowód doręczenia.

## Konfiguracja i schemat

Nowy endpoint ma osobną flagę
`privacy.commerce-retention-source-hold-delivery.enabled`, domyślnie false.
Autokonfiguracja obsługuje wyłącznie siedem usług źródłowych. Nie dodano
wartości do konfiguracji środowisk i nie uruchomiono rzeczywistych aplikacji.
Root sender pozostaje adapterem dla zaufanego wywołania, bez endpointu
operatorskiego, przycisku CC lub automatycznego podłączenia workera.

Order V45 dodaje dwie tabele: `commerce_retention_hold_delivery_attempts`
(5 kolumn) i `commerce_retention_hold_delivery_receipts` (6 kolumn).
Inwentarz: **129 tabel / 1183 kolumny / 220 migracji**. Pozostałe liczby:
47 mechanizmów, 6 przestrzeni Redis, 20 kategorii kopii i 26 polityk.
Bez nowych migracji źródeł. Metadata są pseudonimowe, bez automatycznego
TTL lub surowego automatycznego eksportu; ręczna ocena po stabilnym ID zakupu.

## Restore i odbiór

Restore kwarantannuje rodzica z 139 i zachowuje próby, budżet oraz
potwierdzenia. Niepotwierdzona próba nie otrzymuje fikcyjnego potwierdzenia.
Niepełny schemat historii, nieciągła sekwencja lub niewłaściwe powiązanie
potwierdzenia blokują dalszy restore. Starszy schemat bez historii nie
udaje doręczenia. Brak automatycznego wznowienia.

**87 Java (75 PostgreSQL / 12 jednostkowych) + 98 unikalnych Python PASS**, bez błędów/pominięć;
65 nowych Java i 2 Python. Bramki/candidate PASS, NOT_READY / 2.
Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-03d88010/report.json`.
Zachowano 2 próby i 1 ACK, niepotwierdzona próba bez ACK; rodzic
w kwarantannie i brak automatycznego wznowienia.
Dowody: `tmp/a43-hold-delivery-140/report.json`.
Odbiór obejmuje 7 rzeczywistych kontrolerów/filtrów i SQL, klienta HTTP,
role, limity, błędne odpowiedzi, kwarantannę, timeout po COMMIT,
utratę odpowiedzi, rollback, współbieżność oraz niepewne COMMIT w Order.
Introspekcja jest testowa; nie jest to odbiór pełnych aplikacji/dev/test
lub mieszanych wersji. V45 zastosowano wyłącznie na fiksturach.

## Punkt wznowienia

Następne: kontrolowany odczyt postępu i obsługa wyczerpanego budżetu oraz
uzgodnienie stanu po utracie/odtworzeniu pojedynczego źródła. Nie odczytywać
historycznego ACK jako aktualnego zwolnienia blokady. Potem zweryfikowany
proces rzeczywistych wniosków/spraw, kompletność i wersjonowane dowody,
a następnie pełne upoważnienie, współbieżna mutacja i odzyskiwanie D02.

Istniejący dziennik usunięć nie potwierdza kompletności nowych blokad/spraw.
D01/P01, D09/P03–P05/SAFE, P09 i odbiór ról/rejestru pozostają odłożone.
Punkty wymagające wartości/decyzji użytkownika odkładać i wykonywać
niezależne prace. A05, A41 i same zdjęcia A27–A31/A40 odłożone.
Bez zmian sekretów, danych środowisk lub aktywacji czyszczenia.
