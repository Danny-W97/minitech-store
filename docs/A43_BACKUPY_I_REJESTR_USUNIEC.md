# A43.37 — backupy, rejestr usunięć i izolowane odtworzenie

Decyzje użytkownika: osobny szyfrowany i podpisany rejestr poza zestawami
backupów; unieważnienie wszystkich odtworzonych sesji i krótkich wyzwań
logowania/MFA; okresy oraz aktywacja kasowania pełnych kopii dopiero w A43.40.
Nie zmieniono rzeczywistych sekretów, środowisk ani harmonogramów użytkownika.

## Rejestr przed czyszczeniem

Customer udostępnia wyłącznie operatorowi ograniczone strony
`GET /customers/privacy-journal/requests?after=0&size=50` i potwierdzenie
`POST /customers/privacy-journal/requests/{requestId}/ack`.
Kapsuła zawiera stabilne ID klienta, UUID żądania, czas potwierdzenia i trzy
hashe zaakceptowanych polityk. Bez e-maila, treści i tokenu statusu.
Są to dane pseudonimowe. Brak automatycznego TTL do A43.40.

Rejestr tworzy łańcuch rekordów podpisanych Ed25519 i zaszyfrowanych age,
z podpisaną głową. Dopiero zapis i fsync rekordu oraz głowy pozwalają wysłać
podpisane potwierdzenie do Customer. Brak konfiguracji lub potwierdzenia
blokuje destrukcyjne kroki, również przy włączonych flagach czyszczenia.
Zakładanie barier ID działa od razu. Poszczególne adaptery weryfikują
tożsamość żądania i zaakceptowaną politykę w nagłówku `X-Privacy-Journal`;
wymagana jest również dotychczasowa tożsamość właściwej usługi.
Retry nie zatwierdza nowej polityki i nie przedłuża tokenu statusu.

## Konfiguracja użytkownika przed aktywacją

1. Wybrać niezależne miejsce rejestru, poza katalogami pełnych backupów,
   oraz osobne miejsce kluczy poza oboma katalogami. Zapewnić niezależną
   kopię i kontrolę dostępu. Rejestr z odtwarzanego backupu nie wystarcza.
2. Przygotować własną tożsamość age i parę Ed25519. Nie używać kluczy QA.
   Prywatny podpis i tożsamość deszyfrowania dostępne tylko narzędziu
   rejestru; aplikacje otrzymują publiczny klucz w formacie X509 DER base64.
3. Ustawić dla wszystkich usług w używanych zaszyfrowanych plikach env
   `PRIVACY_RESTORE_JOURNAL_PUBLIC_KEY_BASE64` oraz
   `PRIVACY_RESTORE_JOURNAL_LEDGER_ID` (ten sam UUID rejestru).
   Przykłady Compose i Kubernetes zawierają puste ustawienia, bez sekretów.
4. Dla procesu CC/CLI ustawić `PRIVACY_JOURNAL_DIRECTORY`,
   `PRIVACY_JOURNAL_IDENTITY_FILE`, `PRIVACY_JOURNAL_PUBLIC_KEY_FILE`,
   `PRIVACY_JOURNAL_SIGNING_KEY_FILE`, `PRIVACY_JOURNAL_AGE_RECIPIENT`.
   Opcjonalny `PRIVACY_JOURNAL_CUSTOMER_ORIGIN` wskazuje Customer;
   domyślnie `http://127.0.0.1:18087`. Poza loopback wymagane HTTPS.
   Token operatora pobierany jest ze środowiska/SOPS, nigdy z argumentu CLI.
5. Jawnie zainicjować rejestr poleceniem `init` poniżej i wykonać
   `privacy-journal-sync` w CC/CLI. Wynik `currentHead` przechowywać
   niezależnie od backupów i samego rejestru. Aktualizować po każdym zapisie.
   Bez niezależnego, aktualnego `ledgerUUID:sequence:entryHash` podpis
   nie pozwala odróżnić poprawnej starej kopii od aktualnego rejestru.

Przykład inicjalizacji w WSL, po własnym ustawieniu zmiennych:

```bash
python3 scripts/backup/privacy-journal.py init \
  --directory "$PRIVACY_JOURNAL_DIRECTORY" \
  --identity "$PRIVACY_JOURNAL_IDENTITY_FILE" \
  --public-key "$PRIVACY_JOURNAL_PUBLIC_KEY_FILE" \
  --signing-key "$PRIVACY_JOURNAL_SIGNING_KEY_FILE" \
  --recipient "$PRIVACY_JOURNAL_AGE_RECIPIENT" \
  --backup-root "$BACKUP_ROOT_DIRECTORY" \
  --ledger-id "$PRIVACY_RESTORE_JOURNAL_LEDGER_ID"
```

Narzędzie nie generuje ani nie podmienia kluczy automatycznie. Customer
wiąże UUID rejestru i fingerprint publicznego klucza z bazą. Ich zmiana
wymaga jawnej migracji i ponownej próby; nie wyłączać kontroli dla rotacji.
Nieobsługiwany historyczny schemat albo backup sprzed powiązania rejestru
zostaje zablokowany do migracji w izolacji. Przerwany zapis, dodatkowy ogon,
brak rekordu, niezgodny podpis lub wskaźnik także blokują pracę. Zachować
oba artefakty awarii i uzgodnić aktualny łańcuch z niezależną pieczą;
nie usuwać ogona i nie zerować rejestru w celu obejścia blokady.
Limit bezpieczeństwa: 100000 wpisów; brak automatycznej kompakcji/retencji.

Klucze szyfrowania trwałego MFA i poprzednie klucze potrzebne do starszych
szyfrogramów wymagają oddzielnej kopii zgodnie z A22/A36. Zachowanie rekordów
SQL nie odtwarza utraconego klucza. Brak wymaganej tożsamości age blokuje
próbę; brak keyringu MFA pozostaje przeszkodą przed rzeczywistym dopuszczeniem
aplikacji. Nie generować zastępczych kluczy ani nie wyłączać MFA w celu
obejścia tej przeszkody.

## Odtworzenie

CC/CLI `privacy-restore-drill latest <aktualny-wskaźnik>` wymaga klucza
backupów i niezależnego rejestru. Polecenie tworzy własne kontenery bez
sieci, portów i podpięć hosta. Nigdy nie przyjmuje docelowej bazy produkcyjnej.

Po sprawdzeniu podpisów, łańcucha, aktualnego wskaźnika i powiązania z bazą:

- zakłada ponownie wszystkie 14 barier po stabilnym ID i odtwarza minimalne
  żądania bez odzyskiwania tokenów statusu;
- zmienia globalną epokę JWT, unieważnia wszystkie refresh i resety hasła,
  ustawia kwarantannę Customer i usuwa krótkotrwałe wyzwania MFA po czterech
  jawnych prefiksach; publiczna rejestracja/logowanie/reset również czekają;
- ponawia usunięcie profilu/poświadczeń, wishlisty, logu rekomendacji
  i osobistych recenzji według tej samej zaakceptowanej polityki i tych
  samych porcjowanych zasobów SQL co aplikacja; zachowuje minimalne klucze
  recenzji. Nieznana/stara polityka pozostaje do oceny;
- zachowuje trwałe MFA pozostałych kont oraz biznesowe streamy, grupy,
  pending, deduplikację i czasowe limity logowania;
- ustawia ponowną kwalifikację procesów handlowych i finansowych dla
  istniejących adapterów A43.35/A43.36, zachowując ich klucze deduplikacji.

Raport `PASS` oznacza poprawną próbę w izolacji i ponowienie zakresu core.
`releaseAllowed=false`, `fullA36Accepted=false`, `fullA43Complete=false`.
Narzędzie nie uruchamia adapterów finansowych, Stripe ani wysyłki, nie
zatwierdza uzgodnienia wszystkich procesów i nie otwiera ruchu.
Przed rzeczywistym dopuszczeniem środowiska wymagane jest oddzielne
uzgodnienie procesów A43.36 i procedur A43.38–A43.40. Nie zmieniać ręcznie
fazy na NORMAL jako substytutu odbioru. Obecny zakres nie zawiera polecenia
publikacji odtworzonego środowiska. Kontenery próby są usuwane po zakończeniu.

## Wygasanie kopii

`MINI_ORDER_PLATFORM_BACKUP_RETENTION_MODE=observe` jest domyślne.
Podgląd obejmuje całe szyfrowane zestawy oraz stare pojedyncze dumpy;
zestaw jest kwalifikowany po manifestach, bez selektywnej edycji zawartości.
Techniczne 14 dni pozostaje wyłącznie progiem podglądu. Nie stanowi decyzji
o okresie przechowywania. Tryb `purge` wymaga osobnego ustawienia po decyzji
A43.40. Rejestr i klucze nie podlegają temu mechanizmowi. W tym etapie nie
uruchomiono kasowania rzeczywistych kopii.

Aktywny wolumen `event-broker-data` korzysta z AOF (`appendfsync always`).
Nie usuwać jego plików ani nie ustawiać im TTL: zawierają także streamy,
grupy, pending i deduplikację. Zestawy platformy zapisują logiczny RDB
obejmujący te metadane, a próba odtwarza go w nowym wolumenie.
Historyczny surowy AOF lub snapshot wolumenu nie jest obsługiwanym wejściem
tego importera; wymaga osobnej konwersji w izolacji, sprawdzenia kompletności
baz i ponownej próby z aktualnym rejestrem. Nie dopuszczać takiej kopii
bezpośrednio do ruchu. Zewnętrzne snapshoty administrator ujmuje w ewidencji
całych kopii; ich docelowy termin i potwierdzenie wygaśnięcia należą do
A43.40. Podgląd narzędzia obejmuje wyłącznie rozpoznane zestawy i dumpy,
nie deklaruje skanowania ani usunięcia nieznanych kopii wolumenów.

## Odbiór lokalny

Testy używają wyłącznie własnych jednorazowych kluczy, 14 baz i Redis.
Próba obejmuje backup sprzed żądania, utratę baz źródłowych, ponowienie
usunięcia i barier, zachowanie drugiego konta i jego MFA, unieważnienie
sesji, zachowanie streamów/grup/PEL/deduplikacji, ponowienie oraz konflikty.
Osobne testy sprawdzają podpisy, strony API, polityki, epokę JWT i brak
kasowania w domyślnym podglądzie. To dowód implementacji tego etapu,
nie ukończenia całej procedury usunięcia konta ani wszystkich kopii.

## Aktualizacja 31.10.2026 — odtworzone oceny retencji D02

Replay w kwarantannie unieważnia teraz wszystkie bieżące generacje
`commerce_retention_jobs`, odwołuje ich dzierżawy i zachowuje dowody/historię.
Dotyczy również kont niewymienionych w dzienniku oraz gości; wykonuje się
także dla pustego dziennika. Operacja jest ograniczona do własnych zasobów
izolowanego drillu, po 100 rekordów, z limitami blokad/instrukcji i końcowym
sprawdzeniem braku aktywnych ocen. Błąd, niepełny schemat lub limit utrzymuje
kwarantannę. Brak obu tabel historycznych jest raportowany osobno.

Pełna próba `tmp/privacy-recovery-ecafcf45/report.json` potwierdziła dla
usuwanego konta, zdrowego konta i gościa: stare oceny są STALE, dowody i dane
zamówień pozostały, ponowienie jest idempotentne, a pusty dziennik też
unieważnia oceny. Biznesowe streamy/grupy/pending/deduplikacja oraz trwałe
MFA drugiego konta są zachowane. Nadal `releaseAllowed=false` i pełne A43/A36
nie są ukończone; świeże dowody po restore i wdrożenie pozostają odrębne.

**Uzupełnienie D02 31.10.2026 `.2`:** pilot Returns ma dodatkowe źródłowe
bindingi i trwały outbox. Izolowany replay blokuje wszystkie bindingi przez
`restore_blocked`, raz zwiększa epokę/rewizję i odwołuje dzierżawy bez resetu
prób. Stary ani nowy binding nie może sam zwolnić kwarantanny. Zawiadomienia
i SENT pozostają; brak automatycznego TTL. Działanie jest niezależne od wpisów
dziennika, także pustego, i idempotentne. Częściowy schemat lub brak potwierdzenia
wyniku blokuje dalszy replay. Dotyczy wyłącznie zasobów własnego drillu;
nie dopuszcza rzeczywistego środowiska do ruchu. Szczegóły i ograniczenia:
[retencja handlowa](A43_RETENCJA_DANYCH_HANDLOWYCH.md).

**Rozszerzenie 31.10.2026 `.3`:** blokowanie źródeł obejmuje teraz także
Inventory, Promotion i Delivery, z oddzielnym statusem każdego z czterech
źródeł i sumami zmian. Częściowy schemat w dowolnym źródle zatrzymuje całą
operację przed sesjami/wnioskami. Nieznana nazwa jest odrzucana przed SQL.
Historyczny brak obu tabel jest raportowany, bez dopuszczenia środowiska.
Pusty i powtórny replay obejmują wszystkie cztery źródła. Payment, Billing
i Notification wymagają dalszej integracji; releaseAllowed pozostaje false.

**Rozszerzenie 31.10.2026 `.4`:** Payment, Billing i Notification domykają
zestaw siedmiu źródłowych outboxów. Replay obejmuje wszystkie siedem baz
źródeł niezależnie od wpisów dziennika, nadal przed sesjami/wnioskami.
Wynik zawiera osobny status każdego źródła; częściowy schemat dowolnej bazy
przerywa przebieg, historyczny brak obu tabel jest jawny i nie dopuszcza ruchu.
Żaden binding nie może sam zwolnić restore_blocked. Trwałe dowody i koordynacja
wymagają dalszej implementacji; releaseAllowed pozostaje false.

**Koordynator 31.10.2026 `.5`:** replay obejmuje także trwałe komendy Order
w commerce_retention_bindings. Przed źródłami/sesjami/wnioskami oznacza je
restore_blocked, wycofuje dzierżawy bez zerowania prób i zachowuje znane
potwierdzenia BOUND oraz oczekiwane epoki. Takie wpisy blokują ponowienia
i przygotowanie nowej generacji tego samego zamówienia. Brak API zwolnienia;
flaga jest jednokierunkowa. Partia 100, limity 2/5 sekund, błędny wynik,
aktywny rekord lub limit partii utrzymują kwarantannę. Historyczny brak
tabeli jest jawnie raportowany; nie pozwala dopuścić środowiska.

**Publikatory 31.10.2026 `.6`:** źródłowe przejęcie, odzyskiwanie dzierżaw
i ACK/failure wymagają braku restore_blocked dla danego zamówienia.
Nie podejmują ani nie zamykają objętych kwarantanną rekordów. W Order istnieje
analogiczna blokada komend. Nowe flagi HTTP/publikacji są domyślnie false
i nie zwalniają kwarantanny, nawet po ich jawnym włączeniu. Generacje,
epoki, potwierdzenia i próby pozostają zachowane. Dopuszczenie środowiska
nadal wymaga odrębnych świeżych dowodów i odbioru.

**Lokalny zegar Order 31.10.2026 `.7`:** replay obejmuje również wszystkie
rekordy commerce_retention_order_state przed bindingami, sesjami i wnioskami.
Ustawia restore_blocked, zachowując dokładny reconciled_at, process_revision
i open_seen. Limit partii 100, SQL 2/5 sekund; częściowy schemat, zły licznik,
niezablokowany rekord lub wyczerpanie limitu przerywa dalszy replay. Brak
tabeli w starym backupie jest jawny i nie daje prawa dopuszczenia do ruchu.
Dotyczy kont z dziennika, innych kont i gości, także przy pustym dzienniku.
Kolektor odrzuca zablokowany znacznik; sam zachowany czas nie jest nowym
dowodem. Flaga jednokierunkowa, bez API zwolnienia i bez automatycznego TTL.
Pełny drill `tmp/privacy-recovery-d5552d1e/report.json`: trzy zablokowane
znaczniki z zachowanymi datami, pusty i powtórny replay PASS, 14 baz + Redis,
releaseAllowed=false. Nie zmieniono rzeczywistych backupów ani kluczy.

**Źródłowy zegar Inventory 31.10.2026 `.8`:** dwa nowe pola w istniejącym
commerce_retention_source_state nie zmieniają zakresu replay. Kwarantanna
źródła zachowuje local_reconciled_at/local_open_seen, zwiększa epokę/rewizję,
blokuje ponowne wiązanie i użycie lokalnej obserwacji. Nowa ochrona SQL nie
dopuszcza cofnięcia restore_blocked lub ręcznego nadania czasu. Pusty dziennik
również obejmuje wszystkie źródła; powtórzenie nie przesuwa zachowanej daty.
Nie ma automatycznego TTL, nowego klucza ani zwolnienia środowiska do ruchu.
Dowody aktualnego drillu podaje raport pakietu 111:
`tmp/audit-a43-40-inventory-evidence/report.json`.
Pełny drill `tmp/privacy-recovery-cf51b187/report.json` PASS: jeden znacznik
Inventory zachował dokładną datę pod restore_blocked, także po pustym i
powtórnym replay; pozostałe bariery Order/siedmiu źródeł oraz biznesowy
Redis zachowane. releaseAllowed pozostaje false.

**Odbiór Inventory w Order 31.10.2026 `.9`:** nowe testy źródła i odbiorcy
odrzucają obserwację po restore_blocked, także gdy kwarantanna zaczyna się
w trakcie HTTP. Nie zmieniono schematu ani narzędzia restore; dowodem pełnego
drillu pozostaje pakiet 111, nie nowy przebieg. Rejestr/klucze nie były dostępne
pod zapisanymi ścieżkami privacy-journal/privacy-journal-keys; piecza i SAFE
nadal nierozstrzygnięte. Pełny odbiór A43 opisuje A43_ODBIOR_KONCOWY.md.

**Sześć źródeł 31.10.2026 `.10`:** odroczone zegary źródeł respektują
restore_blocked; replay zwiększający rewizję w kwarantannie zachowuje czas.
Payment receipt w nowej tabeli Order pozostaje przy archiwalnej generacji;
kontrola current i źródłowego bindingu uniemożliwia użycie go jako nowego
dowodu. Brak automatycznego TTL oraz API zwolnienia. Rozszerzony pełny drill
obejmuje zegary sześciu źródeł i zachowanie nieaktywnego hasha potwierdzeń;
jego wynik podaje raport pakietu 113.


**Znane blokady zakupów 31.10.2026 `.11`:** replay w należącym do drillu
odizolowanym zestawie obejmuje commerce_retention_hold_scopes i zachowuje
niezmienne commerce_retention_holds. Każdy zakres otrzymuje restore_blocked,
z jednokrotnym zwiększeniem rewizji przez trigger; brak automatycznego
zwolnienia i TTL. Pełny lub pusty dziennik nie omija tej ochrony. Błąd,
częściowy schemat lub nieprzetworzone zakresy zatrzymują dalszy replay.
Zakres CUSTOMER nadal dotyczy zakupów, nie usuwania profilu konta.
Dowody: tmp/audit-a43-40-known-holds/report.json. Rzeczywisty niezależny
rejestr, nośnik, SAFE i piecza kluczy/wskaźnika nadal do odbioru.


**Kontrolowane generacje 31.10.2026 `.12`:** migracja Order V32 utrwala
niezmienne preparation_predecessor przy zadaniu oceny. Restore zachowuje
powiązanie, a unieważnienie generacji nie usuwa jej historii. Nowy przebieg
wewnętrzny sprawdza kwarantannę źródeł, ocen i znanych blokad i nie zwalnia
środowiska do ruchu. Drill pakietu 115 obejmuje zachowanie poprzednika przy
pustym/powtórnym replay; dowody tmp/audit-a43-40-targeted-workflow/report.json.


**Definicja pól 31.10.2026 `.14`:** pole field_scope_hash pozostaje przy
niezmiennym dowodzie i archiwalnej generacji. Replay nie dopisuje aktualnego
skrótu do historii, nie awansuje starego potwierdzenia i nie zwalnia środowiska.
Pełny drill pakietu 117 obejmuje nowy powiązany wpis przy pustym/powtórnym
replay oraz dotychczasowe blokady, zegary, bindingi i biznesowy Redis.
Dowody tmp/audit-a43-40-field-scope/report.json. Rzeczywista piecza rejestru,
nośnik niezależny, SAFE i okresy zachowania nadal do odbioru.
