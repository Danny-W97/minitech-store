# A36.1 — zestaw kopii i izolowana próba odtworzenia

Pakiet obejmuje 14 baz PostgreSQL oraz Redis, szyfrowany manifest kompletności,
odtworzenie wyłącznie do nowych zasobów i kontrolę wskazanego opłaconego zamówienia.
Pełne A36 pozostaje otwarte: nie wdrożono globalnego checkpointu ani automatycznego
uzgadniania wszystkich skutków biznesowych po utracie części środowiska.
A05 i zmiany samych zdjęć pozostają odłożone.

**Aktualizacja A43.37:** retencja całych kopii i starych dumpów domyślnie
działa w trybie `observe` (podgląd). Okresy i aktywacja kasowania należą do
A43.40. Techniczny test restore zawsze pozostawia `releaseAllowed=false`.
Próba z aktualnym niezależnym rejestrem usunięć, unieważnieniem sesji i
kwarantanną: [instrukcja A43.37](A43_BACKUPY_I_REJESTR_USUNIEC.md).

## Polecenia Control Center

CLI oraz panel WWW, grupa kopii zapasowych:

```bash
bash scripts/control-center/control-center.sh platform-backup
bash scripts/control-center/control-center.sh platform-restore-drill latest 900001
bash scripts/control-center/control-center.sh platform-backup-prune 14
```

`900001` zastąpić ID znanego opłaconego zamówienia, które istnieje w wybranym
zestawie. Można podać pełną ścieżkę do zestawu zamiast `latest`.
Bez ID uruchamiana jest próba techniczna; raport ma `businessVerified: false`.
Nie jest to potwierdzenie poprawności zamówienia.

Zakres CC to obecnie lokalne profile **dev/test Compose**, PostgreSQL 16 i Redis 7.
Źródła są rozwiązywane do identyfikatorów działających kontenerów. Narzędzie nie
zatrzymuje aplikacji i nie modyfikuje źródłowych baz, zdarzeń ani statusów zamówień.
Snapshot Redis korzysta z protokołu replikacji `redis-cli --rdb -`, co może zużywać
zasoby brokera. Wymagane są Docker, Python 3 i age oraz istniejący klucz backupu
profilu. Klucz przygotowuje dotychczasowe `backup-key-init dev` albo `test`.
Wartości kluczy nie są wyświetlane ani zapisywane w zestawie.

Kopie trafiają do `<backup-root>/<profil>/checkpoints/checkpoint-<UTC>-<ID>/`.
Każdy zestaw zawiera 14 plików `<baza>.dump.age`, `redis.rdb.age` i
`manifest.json.age`. Katalog ma tryb 700, pliki 600 na systemach obsługujących
uprawnienia POSIX. Dumpy i RDB płyną przez pipe bez jawnych plików na hoście.
Prywatny klucz age i klucze MFA/JWT muszą mieć oddzielną, sprawdzoną kopię offline.

## Co potwierdza manifest

Manifest jest szyfrowany i uwierzytelniany przez age. Zawiera SHA256 i rozmiar
każdego zaszyfrowanego artefaktu, czas rozpoczęcia/zakończenia każdej części oraz
całego zestawu. Powstaje **dopiero po powodzeniu wszystkich procesów**, w tym
źródłowego `pg_dump` i procesu szyfrowania. Błąd dumpu nie może zostać ukryty
przez poprawne zaszyfrowanie pustego strumienia.

Brak manifestu oznacza zestaw niekompletny. Odtworzenie przed utworzeniem
kontenerów odrzuca brakujące bazy/Redis, inną liczbę części, niezgodny hash/rozmiar,
nieprawidłowy klucz, duplikaty pól JSON, dodatkowe pliki, dowiązania symboliczne
i ścieżki wychodzące poza ustalone nazwy artefaktów.

**`consistency: independent-snapshots` nie oznacza wspólnego punktu transakcyjnego.**
Każdy `pg_dump` reprezentuje spójny obraz jednej bazy; pozostałe bazy i Redis mogą
pochodzić z innej chwili. Podczas aktywnego ruchu między kopiami mogą powstać
płatność, faktura lub zdarzenie, których inny snapshot jeszcze nie zawiera.
Manifest dokumentuje to okno, ale go nie usuwa.

## Izolacja i kryteria próby

`platform-restore-drill` nie ma parametru istniejącego celu. Zawsze tworzy nowe
kontenery z `--network none`, bez portów hosta, bind mountów i podłączania
istniejących wolumenów. Nie uruchamia usług aplikacyjnych, SMTP, webhooków ani
operatora płatności. PostgreSQL otrzymuje losowe hasło tylko dla tej próby.
Po zakończeniu, także po błędzie, narzędzie usuwa własne kontenery i ich anonimowe
wolumeny. Błąd sprzątania kończy próbę niepowodzeniem.

PostgreSQL odtwarza 14 baz z `pg_restore --exit-on-error --single-transaction`,
bez uprawnień/właścicieli źródła. Sprawdzane są tabele aplikacji odpowiednie dla
każdej bazy. Redis przechodzi `redis-check-rdb`, startuje z RDB i pozwala odczytać
liczbę kluczy oraz stan `payment-events`/`invoice-events`, grup i pending.
Snapshot obejmuje cały Redis, nie tylko te dwa streamy. AOF nie jest kopiowany;
w tej próbie Redis pracuje z odtworzonym RDB bez AOF.

Dla wskazanego zamówienia sprawdzane są:

- status PAID/SHIPPED/COMPLETED;
- skuteczna płatność zgodna z kwotą zamówienia;
- powiązana rezerwacja o statusie CONFIRMED;
- wysłane powiadomienie PAYMENT_SUCCEEDED;
- wystawiona faktura, jeżeli zamówienie jej wymaga;
- przesyłka dla metody innej niż STORE_PICKUP.

Rozbieżność daje `RECONCILIATION_REQUIRED` i kod zakończenia 2, błąd techniczny —
`FAILED` i kod 1. PASS dotyczy zakresu opisanej próby. Raport zawsze ma
`fullA36Accepted: false`, `distributedCheckpoint: false` oraz
`automaticReconciliationTested: false`. Plik raportu jest prywatny, w
`gitIgnoreOwn/runtime/recovery-drills/` (lub skonfigurowanym katalogu runtime).
Nie wykonuje naprawy, replay ani ponownego obciążenia klienta.

## Procedura odtwarzania całej platformy

To plan odbioru docelowego, a nie automatyczne przywracanie dev/prod:

1. Wyznaczyć osobę prowadzącą incydent. Zablokować nowy ruch mutujący i płatności,
   zatrzymać wszystkie procesy piszące, także schedulery, natywne JVM, Compose
   oraz integracje zewnętrzne. Sam brak ruchu HTTP nie oznacza braku zapisów.
2. Zachować pozostałe wolumeny i logi do analizy. Ustalić ostatni kompletny zestaw
   i zakres brakujących danych na podstawie czasu snapshotów. Nie mieszać części
   różnych zestawów ani nie wybierać niezależnie „najnowszej” kopii każdej bazy.
3. Sprawdzić dostępność klucza age, konfiguracji usług, kluczy MFA/JWT, sekretów,
   wersji aplikacji/migracji i obrazu infrastruktury. Zestaw nie zawiera tych
   sekretów, plików konfiguracyjnych ani całej infrastruktury. Utrata klucza MFA
   nie zostanie naprawiona samym odtworzeniem zaszyfrowanej tabeli.
4. Wykonać izolowany drill zestawu z kilkoma znanymi zamówieniami. Dla dużych
   baz zarezerwować miejsce na pełne kopie i czas odtwarzania; nie stosować tej
   próby jako procedury PITR czy dowodu RTO użytkowego.
5. Na odizolowanym środowisku docelowym odtworzyć dane i konfigurację przez
   zatwierdzoną procedurę infrastrukturalną. Uzgodnić DB ↔ outbox ↔ inbox ↔ Redis:
   PUBLISHED nie dowodzi obecności zdarzenia w starszym snapshotcie brokera,
   a pusty pending nie dowodzi wykonania skutku biznesowego.
6. Sprawdzić kwoty/statusy płatności, zapasy, przesyłki, faktury/korekty i wysyłkę
   wiadomości. Zdarzenia do ponowienia muszą zachować tożsamość i idempotencję.
   Nie resetować masowo statusów PUBLISHED/SUCCEEDED i nie ponawiać pobrania środków.
   Brak potwierdzenia oznacza ręczne uzgodnienie, nie założenie sukcesu.
7. Uzgodnić skutki odtworzenia starszego stanu sesji, unieważnień tokenów i wyzwań
   MFA w Redis/Customer. Przed otwarciem logowania musi istnieć sprawdzona procedura
   ich unieważnienia; przywrócenie RDB samo nie potwierdza bezpieczeństwa sesji.
8. Uruchamiać konsumentów i schedulery etapami. Obserwować zaległości i alerty A35;
   ponownie prześledzić znane zamówienia. Otworzyć ruch dopiero po odbiorze.

## RPO, RTO i retencja

Propozycja do uzgodnienia dla lokalnego dev/test: kompletna kopia co 24 h,
techniczne odtworzenie do 60 min i retencja 14 dni. To cele, nie potwierdzone SLA.
Dla produkcji RPO/RTO wymagają osobnej decyzji, pomiarów, pojemności i próby
całego procesu, w tym sekretów i integracji. Raport mierzy czas odtwarzania
technicznego; nie obejmuje czasu wykrycia incydentu ani uzgodnienia danych.

`platform-backup-prune` usuwa cały stary zestaw dopiero po uwierzytelnieniu
manifestu i sprawdzeniu zamkniętego zbioru nazw. Nie używa usuwania rekursywnego.
Niekompletne zestawy pozostawia do analizy; nie są kandydatami do odtworzenia.
`db-backup-prune` pomija zawartość `checkpoints`, aby nie uszkadzać zestawów.

Dotychczasowy harmonogram `postgres-backup.sh` nadal dotyczy pojedynczych kopii
baz. Nie został automatycznie przełączony na nowe zestawy. Harmonogram,
off-site/immutability, niezależny magazyn i alert wieku ostatniej kompletnej kopii
pozostają częścią dalszego odbioru. Nowe polecenia nie instalują zadania w tle.

## Pozostało w pełnym A36

- Egzekwowane zatrzymanie wszystkich writerów lub inny uzgodniony globalny
  checkpoint; ewentualnie PITR i procedura uzgadniania różnych punktów czasu.
- Automatyczna, idempotentna naprawa brakujących skutków po utracie bazy/brokera,
  z rzeczywistymi usługami i testem braku podwójnej płatności/faktury/wiadomości.
- Uzgodnienie starszego stanu sesji/MFA, pełna konfiguracja i klucze oraz odbiór
  na docelowej infrastrukturze, łącznie z potwierdzonym RPO/RTO i off-site.
- Backup pozostałych wolumenów (np. wiadomości Mailpit) i innych zasobów według
  polityki danych. Ten pakiet nie zmienia sposobu przechowywania samych zdjęć.

## Dowody lokalnej próby (26.10.2026)

`tmp/audit-a36-ef0c450a/report.json` oraz `restore-report.json` potwierdzają:

- migracje wszystkich 14 baz z rzeczywistych plików projektu (z transakcjami
  odpowiadającymi Flyway); dane znanego zamówienia przygotowano jako fixture SQL;
- poprawny zestaw 15 zaszyfrowanych artefaktów i zaszyfrowanego manifestu;
- odmowę uznania nieudanego pg_dump za kompletny backup, mimo poprawnego age;
- odrzucenie złego klucza, zmienionego szyfrogramu i brakującej bazy/Redis;
- odtworzenie po usunięciu źródeł: 14 schematów, sześć kontroli znanego zamówienia,
  oba streamy, grupy konsumentów i pending;
- wykrycie brakującej płatności i utraty schematu Payment; brak sieci, portów hosta
  i bind mountów w kontenerach odtworzenia oraz ich sprzątnięcie.

Okno zebrania fixture: około 17 s; techniczne odtworzenie: około 32 s. To mały
zestaw testowy — wyniki nie potwierdzają RPO/RTO rzeczywistych danych. Nie
uruchamiano odtworzonych usług aplikacyjnych ani automatycznego uzgadniania.
CI egzekwuje tę próbę przez `scripts/qa/platform-recovery-e2e.py`. Artefakty CI
obejmują wyłącznie raporty i logi fixture, bez kluczy age i kopii danych.

Końcowa regresja: 128 testów Python (w tym 14 nowych testów zestawów, pipe,
retencji, raportowania błędów i przekazania źródeł przez CC) — PASS,
`tmp/a36-final-tests.log`. Składnia zmienionych skryptów Bash także przeszła.

Źródła techniczne: [spójność pojedynczego pg_dump](https://www.postgresql.org/docs/16/backup-dump.html),
[pg_restore](https://www.postgresql.org/docs/16/app-pgrestore.html),
[snapshot RDB przez redis-cli](https://redis.io/docs/latest/develop/tools/cli/).
