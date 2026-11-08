# A43.40 — trwałe próby i potwierdzenia Order, pakiet 147

Stan: **02.11.2026, Audit_0.1, a43.40-2026-11-02.2**.
Zakres lokalny; A43.40/.41 otwarte. Czyszczenie wyłączone.

## Zachowanie koordynatora

`CommerceRetentionHoldRestorationDispatch` wykonuje jawny krok jednego
źródła. Parametry zakupu, sprawy, źródła, oryginalnej blokady i wersji
wynikają z niezmiennej intencji i jej podstawy SQL. Pierwsza próba RESTORE
wymaga nadal kwalifikującego MISSING oraz aktywnego podpisu Ed25519
dokładnego zakresu z pakietu 146. Nie dodano wystawcy upoważnień.

Próba ma identyfikator podany przez wywołującego; ten sam UUID jest
challenge odpowiedzi. INSERT i potwierdzony COMMIT poprzedzają RPC.
Niepewny COMMIT nie pozwala wysłać żądania. Ponowienie tego samego
identyfikatora odczytuje zapisany wynik lub PENDING i nigdy nie wysyła
ponownie. Obce zakresy i zmiana trybu tej samej próby są odrzucane.

Po pierwszej próbie każda nowa może użyć tylko REPLAY_ONLY, tego samego
zakresu i ID podpisanego upoważnienia. Wygasły poprawny podpis może
odczytać istniejący skutek; nie pozwala na nowy zapis w źródle. Wynik
REPLAY_UNAVAILABLE pozostaje diagnozą i nie pozwala ponowić RESTORE.
Źródło 146 egzekwuje ten tryb niezależnie od koordynatora.

Wynik odpowiedzi jest walidowany pod względem źródła, scopeHash, UUID,
ID upoważnienia, czasu i wersji; REPLAY_ONLY nie przyjmuje RESTORED.
Wynik w SQL jest dopisywany tylko raz. Utrata odpowiedzi po źródłowym
COMMIT, rollback zapisu wyniku lub brak odpowiedzi pozostawia próbę
niepotwierdzoną. Utrata potwierdzenia COMMIT wyniku wymaga odczytu
historii. W żadnym przypadku timeout nie udaje rollbacku lub sukcesu.

Maksymalnie 8 prób na intencję, przy istniejącym limicie 8 intencji na
źródło. Nie ma resetu budżetu, automatycznych ponowień lub harmonogramu.
Odczyt historii obejmuje najwyżej 8 wpisów dokładnego zakresu i nie robi
RPC. Historia jest dostępna także pod kwarantanną jako historia.

## Stop, kwarantanna i granice

Niezmienny znacznik stopu jest idempotentny w obrębie intencji. Zatrzymuje
dalsze kroki. Sprawdzenia pod blokadą rodzica poprzedzają zapis próby i
RPC oraz następują przed zapisem wyniku. Stop lub kwarantanna w trakcie
wywołania pozwala zapisać jedynie ROOT_STOPPED / ROOT_QUARANTINED,
bez pozytywnego potwierdzenia. Znacznik nie dowodzi zakończenia procesu
lub zatrzymania żądania, które już opuściło Order. Źródło mogło atomowo
zapisać negatywną blokadę. Nie jest ona usuwana, a historii nie przepisujemy.

Zewnętrzna transakcja wywołującego jest odrzucana. Żaden RPC nie odbywa
się w transakcji Order. Wynik nigdy nie oznacza aktualnej globalnej zgody,
kompletności spraw, wykonania redakcji lub zgody na aktywację.

Nie dodano endpointu operatora, przycisku CC, schedulera lub konfiguracji
dev/test. Koordynator jest niewpiętym adapterem do kontrolowanej integracji.
Wymaga osobno skonfigurowanego weryfikatora Ed25519; token podpisu
pozostaje tylko w pamięci żądania i nie trafia do SQL ani wyniku.
Po awarii wywołujący musi ponownie przedstawić właściwy podpis dla
tego samego zakresu i ID upoważnienia. Sam odczyt lokalnej historii nie
wymaga tokenu i nie wykonuje RPC; brak podpisu blokuje nową próbę do źródła.

**Nadal odłożone:** rzeczywisty zaufany wystawca i jego proces weryfikacji,
tożsamości operatorów, klucz publiczny, rotacja/odwoływanie oraz odbiór
operacyjny. Testowy wystawca jest wyłącznie fiksturą w pamięci.
Bez tych ustaleń nie uruchamiać zapisów w środowiskach.

## Schemat, restore i odbiór

Order V48 dodaje trzy tabele: `commerce_retention_hold_restoration_attempts`
(7 pól), `commerce_retention_hold_restoration_results` (6) i
`commerce_retention_hold_restoration_stops` (3). Triggery egzekwują
niezmienność, kolejność i limit, pierwszy RESTORE / dalsze REPLAY_ONLY,
zgodność zakresu/upoważnienia i wersji wyniku oraz ochronę stopu/kwarantanny.
SQL nie wykonuje kryptograficznej weryfikacji podpisu — robi ją adapter
i niezależnie źródło; samo dodanie rekordu SQL nie potwierdza sprawy.

Inwentarz: **143 tabele / 1286 kolumn / 230 migracji**; 47 mechanizmów,
6 przestrzeni Redis, 20 kategorii kopii, 26 polityk. Pseudonimowe metadane
mają politykę znaczników ponowień, bez automatycznego TTL. Bez surowego
automatycznego eksportu; ręczna ocena dostępu po stabilnych ID zakresu.

Restore zachowuje próby, wyniki i stop, sprawdza powiązania, sekwencję,
tryby i wersje, wymagając kwarantanny rodzica. Nie odświeża upoważnień,
nie dopisuje wyników PENDING i nie wznawia RPC. Niepełny lub niespójny
schemat blokuje odbiór; historyczny brak wszystkich trzech tabel nie
udaje istnienia historii. Siedem źródeł zachowuje ochronę z pakietu 146.

**139 unikalnych Java (125 PostgreSQL / 14 jednostkowych) + 110 Python PASS**, bez błędów i pominięć; 47 nowych
Java (27 Root PostgreSQL / 20 HTTP), pozostałe 92 to regresje.
3 nowe metody Python oraz 7 mutacji kontraktu. Bramki/candidate PASS,
gotowość NOT_READY / kod 2. Pełny drill 14 baz/Redis PASS:
`tmp/privacy-recovery-fb3ee86e/report.json`; 2 próby / 1 wynik / 1 stop oraz 7 źródłowych
receiptów zachowane pod kwarantanną. Pozostała próba bez wyniku, bez wznowienia.
Dowody: `tmp/a43-restoration-dispatch-147/report.json`.
Testy dotyczą syntetycznych spraw, rzeczywistych kontrolerów/filtrów,
SQL i izolowanych baz; introspekcja i wystawca są testowe. Migracja
wyłącznie na fiksturach. Nie jest to odbiór pełnych aplikacji, realnych
spraw lub mieszanych wersji w dev/test.

## Punkt wznowienia

Następne: ocena postępu napraw z powiązaniem trwałych prób, wyników i
świeżej inspekcji źródła; rozróżnienie historycznego potwierdzenia,
nieznanego wyniku, stopu i potrzeby ponownej oceny. Bez automatycznego
odblokowania kwarantanny, resetu budżetu lub zgody na redakcję.
Następnie kompletne wersjonowane dowody i pełne wykonanie/odzyskiwanie D02.
D01/P01, D09/P03–P05/SAFE, P09, odbiór ról/rejestru i wystawcy odłożone;
A05, A41 i same zdjęcia A27–A31/A40 nadal odłożone.
