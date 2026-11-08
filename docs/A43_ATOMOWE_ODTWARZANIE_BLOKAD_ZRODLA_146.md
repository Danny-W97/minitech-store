# A43.40 — atomowe odtwarzanie blokad w źródle, pakiet 146

Stan: **02.11.2026, Audit_0.1, a43.40-2026-11-02.1**.
Zakres lokalny; A43.40/.41 otwarte. Czyszczenie wyłączone.

## Protokół źródłowy

`SourceHoldRestorer` obsługuje siedem źródeł. Pod wspólną blokadą advisory
436 i FOR UPDATE stanu zakupu sprawdza epoch/revision oraz tożsamości.
Odtwarza brakującą negatywną blokadę z oryginalnym UUID i request_id,
rodzajem i powodem. INSERT korzysta z istniejących triggerów: podnosi
rewizję, unieważnia obserwację i zapisuje outbox. Niezmienny receipt
powstaje w tej samej transakcji. RESTORED wraca dopiero po COMMIT.
Zewnętrzna transakcja wywołującego jest odrzucana.

Źródło wymaga usługi Order i osobnego podpisanego upoważnienia sprawy.
Weryfikator Ed25519 sprawdza dokładny zakres: źródło/zakup, oryginalne
request_id/hold_id, guard/reason, intent_id, referencję sprawy, epoch/revision,
ID upoważnienia i podpisany czas. Ważność wynosi najwyżej 15 s od wystawienia,
z tolerancją przyszłego wystawienia 5 s. Sam UUID, boolean, Candidate,
PREPARED ani podpis starego dziennika usunięć nie zastępują tego dowodu.
Nie dodano wystawcy upoważnień ani prywatnego klucza do kodu produkcyjnego.

Zmiana epoch/revision daje STALE_SOURCE; konflikt istniejącego UUID/request_id
daje CONFLICT. Nieznany zakup nie tworzy bindingu. Brak prawidłowego podpisu
daje AUTHORIZATION_REQUIRED, wygasłe upoważnienie nie pozwala na nowy zapis.
Kwarantanna blokuje odtworzenie i odczyt dawnego potwierdzenia przez replay.

Receipt wiąże intent_id, case_authorization_id, skrót pełnego zakresu,
zakup, request_id, UUID blokady i wersję po zapisie. Powtórzenie rozpoznaje
ten sam zakres/upoważnienie oraz sprawdza obecność dokładnej blokady.
REPLAYED potwierdza historyczny skutek bez zwiększania licznika. Nie daje
globalnej zgody lub aktualnego dowodu kompletności. Wygasły podpis może
służyć wyłącznie odczytowi takiego istniejącego skutku. REPLAY_ONLY nigdy
nie tworzy blokady. Brak potwierdzenia lub konflikt nie uruchamia naprawy.
Utrata odpowiedzi HTTP lub COMMIT nie oznacza rollbacku; ponowienie
rozpoznaje atomowo zapisany receipt.

Odpowiedzi wiążą scopeHash, challenge, intencję, źródło, kod, czas i wersję
oraz ID upoważnienia. Nie zawierają podpisanego poświadczenia; klient
sprawdza również zgodność ID z deklaracją przesłanego tokenu. Dowód nie
jest zapisywany w SQL lub ujawniany przez toString. Nie podążamy za
przekierowaniami; błędy klienta i kontrolera nie zawierają treści dowodu.
Limity: 8192 B żądania / 2048 B odpowiedzi; świeży challenge i kontrola
wieku odpowiedzi 15 s / tolerancja przyszłości 5 s.

## Order, konfiguracja i granice

`CommerceRetentionHoldRestorationPreparation` pobiera parametry z trwałej
intencji i jej podstawy SQL. RESTORE wymaga nadal aktualnego kwalifikującego
MISSING i braku aktywnego PENDING. Odtworzone/obce zakresy są blokowane.
REPLAY_ONLY pozwala zbudować historyczny odczyt istniejącej intencji,
lecz źródło nadal wymaga podpisu i odmawia nowego zapisu. Adapter nie
weryfikuje sprawy: `caseAuthorizationVerified=false`, nie robi RPC lub DML.
Nie dodano jeszcze dispatchera Root, trwałych prób i potwierdzeń Root,
publicznego operator API, przycisku CC lub automatycznego wykonania.

Nowy POST `/internal/privacy/commerce-retention/source-hold-restoration`
przyjmuje wyłącznie uwierzytelnione Order. Flaga
`privacy.commerce-retention-source-hold-restoration.enabled` jest domyślnie
false. Włączenie wymaga poprawnego klucza publicznego Ed25519 w
`privacy.commerce-retention-source-hold-restoration.case-public-key`;
brak klucza blokuje startup endpointu. Nie zmieniano konfiguracji dev/test.

**Odłożone do użytkownika / odbioru operacyjnego:** wybór zaufanego wystawcy,
jego procesu weryfikacji spraw i tożsamości operatorów, klucza publicznego,
rotacji oraz procedury odwoływania upoważnień. Klucz prywatny wystawcy
pozostaje poza aplikacją i repozytorium. Brak natychmiastowej revokacji
pojedynczego tokenu; ważny podpis ma krótkie okno, a zmiana klucza lub
kwarantanna blokuje użycie. Bez tego odbioru aktywacja pozostaje niedopuszczona.
Podpis poświadcza twierdzenie zaufanego wystawcy, nie wykonuje samodzielnej
oceny dowodów własności przez usługę źródłową. Testowy wystawca i klucze
powstają wyłącznie w pamięci procesu testowego i nie są wdrożeniem tego procesu.

## Schemat i odtworzenie

Siedem identycznych migracji źródeł dodaje po jednej tabeli
`commerce_retention_hold_restoration_receipts` z 9 polami. Inwentarz:
**140 tabel / 1270 kolumn / 229 migracji**; 47 mechanizmów,
6 przestrzeni Redis, 20 kategorii kopii i 26 polityk. Metadane pseudonimowe,
bez tokenów, treści lub kontaktu; brak automatycznego TTL do rozliczenia
kopii i ponowień. Surowy eksport automatyczny wyłączony; ręczna ocena
dostępu po stabilnym ID zakupu i dowodach właściwego zakresu.

Restore zachowuje źródłowe receipty oraz blokady, kwarantannuje stany
źródeł i weryfikuje zakup/hold_id/request_id. Nie traktuje historycznych
wersji jako aktualnych. Kwarantanna uniemożliwia RESTORE i REPLAYED;
nie ma automatycznego wznowienia. Nowy schemat przyjmowany wyłącznie
po weryfikacji; starszy brak tabeli nie udaje istnienia receiptów.

**147 unikalnych Java (109 PostgreSQL / 38 jednostkowych) + 107 Python PASS**, bez błędów i pominięć; 92 nowe
Java (14 jednostkowych, 19 Root PostgreSQL, 59 HTTP), 3 Python i 7 mutacji
kontraktu. Pozostałe 55 Java to regresje. Bramki/candidate PASS, gotowość
NOT_READY / kod 2. Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-e9f11d6b/report.json`;
siedem źródłowych receiptów i wcześniejsza historia zachowane pod
kwarantanną, bez wznowienia lub odnowienia upoważnień.
Dowody: `tmp/a43-source-restoration-146/report.json`.
Lokalne testy używają rzeczywistych kontrolerów/filtrów, SQL siedmiu źródeł
i syntetycznych podpisanych spraw; introspekcja jest testowa. Nie jest to
odbiór pełnych aplikacji, realnych spraw lub mieszanych wersji w dev/test.
Migracje zastosowano tylko na fiksturach; bez sekretów i danych środowisk.

## Punkt wznowienia

Następne: trwałe próby/potwierdzenia dispatchera Root, jawny tryb odzyskania
wyłącznie przez replay i ochrona przed niepewnym COMMIT, kwarantanną,
stopem oraz zmianą kontekstu. Podpięcie rzeczywistego wystawcy i weryfikacji
spraw pozostaje do odbioru; bez niego nie uruchamiać zapisów w środowiskach.
Potem kompletne wersjonowane dowody i pełne wykonanie/odzyskiwanie D02.
D01/P01, D09/P03–P05/SAFE, P09 i odbiór ról/rejestru odłożone;
A05, A41 i same zdjęcia A27–A31/A40 nadal odłożone.
