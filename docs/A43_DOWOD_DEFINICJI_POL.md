# A43.40 — dowód zatwierdzonej definicji pól D02

Stan 31.10.2026, Audit_0.1, pakiet 117, rewizja a43.40-2026-10-31.14.
Zakres: zaufane potwierdzenie wyłącznie FIELD_SCOPE_REVIEWED.
Pełne A43.40/A43.41 nadal NOT_READY; D17 bez aktywacji.

## Znaczenie dowodu

Definicja obejmuje uzgodnioną mapę 10 tabel/127 pól: 20 pól planowanej redakcji,
91 minimum i 16 protokołu. Wiąże zakres kont/gości, zegary, okresy 365/90 dni,
wyłączenie pięciu tabel finansowych oraz skrót decyzji D02 z zapisu użytkownika.
Zapis decyzji nie został zmieniony przez implementację.

Zasób commerce-retention-field-scope.json jest częścią artefaktu platform-security.
CommerceRetentionFieldScope sprawdza jego przypięty SHA-256 i limit 64 KiB;
normalizowane są wyłącznie zakończenia linii CRLF/LF. Brak, zmiana lub błędny
zasób blokuje potwierdzenie. Adapter nie przyjmuje ścieżki, JSON, skrótu lub
flagi potwierdzenia od klienta. Bramka QA porównuje zawartość z rzeczywistą
mapą pól i decyzją D02; samo odświeżenie skrótów plików nie zatwierdza zmiany.

**Dowód oznacza przegląd planu pól.** Nie potwierdza wdrożonego schematu każdej
bazy, nullowalności kolumn, ukończenia procesu, braku spraw/ponowień/DLQ,
polityki zachowania, aktualności niezależnego dziennika lub prawa do usunięcia.
PRESERVATION_POLICY_REVIEWED, INDEPENDENT_JOURNAL_CURRENT i pozostałe wymagane
GUARD nie stają się potwierdzone. D01/D09/P09 pozostają otwarte.

## Zapis i weryfikacja

Wewnętrzny CommerceRetentionFieldScopeEvidence sprawdza bieżący zakup,
właściciela/wersję/generację/fazę/politykę, znane blokady, bariery usuwania
konta/zakupu i kwarantannę. Pod blokadami zapisuje jeden GUARD wraz ze skrótem
definicji. Wyłącznie ten adapter używa wewnętrznej ścieżki zatwierdzonego zapisu;
ogólne record(Guard, GuardProof) odrzuca CONFIRMED dla FIELD_SCOPE_REVIEWED.
Brak endpointu, komponentu Spring, harmonogramu i nowych uprawnień lub widoku CC.

Order V34 dodaje field_scope_hash do istniejącej commerce_retention_evidence.
CHECK ogranicza powiązanie do CONFIRMED/FIELD_SCOPE_REVIEWED, a trigger zabrania
zmiany skrótu, zmiany powiązanego wpisu i usunięcia takiego potwierdzenia.
Nie ma nowej tabeli, automatycznego TTL lub backfillu. Nie zmienia się starej
historii, nie wpisuje aktualnego skrótu do dawnego niezweryfikowanego dowodu.
Rejestr: 106 tabel/1017 pól, 185 migracji.

Ocena ponownie weryfikuje definicję z artefaktu i jej powiązanie z dowodem.
Historyczny FIELD_SCOPE_REVIEWED=CONFIRMED bez skrótu albo z inną definicją
jest traktowany w ocenie jako UNKNOWN; rekord historyczny pozostaje bez zmian.
Próba potwierdzenia innej treści w tej samej generacji daje konflikt, unieważnia
generację i wymaga właściwej nowej. Nie nadpisuje się dawnego potwierdzenia.

Pierwszy nowy dowód zwiększa rewizję dowodów i unieważnia starą dzierżawę,
jak dotychczasowy protokół nowych dowodów. Idempotentne powtórzenie nie resetuje
rewizji, prób lub prawidłowej nowej dzierżawy. Gość pozostaje zakresem jednego
zakupu, bez dopasowania przez e-mail.

Sam adapter może potwierdzić plan pól w obu fazach. Dla MINIMUM_REMOVAL nie
zapisuje redacted_at ani dowodu zwolnienia zależności. Istniejący workflow
nadal obsługuje tylko REDACTION i ma osobny, jawny krok reviewFieldScope,
z checkpointami przed/po oraz activationAllowed=false. observeNext nie
potwierdza tego GUARD automatycznie i zachowuje dotychczasową kolejność usług.

## Testy i ograniczenia odbioru

Cztery nowe testy jednostkowe sprawdzają przypięty zasób, CRLF/LF, zmianę pól,
okresu/rodzaju potwierdzenia, brak/pusty/przekroczony/nieprawidłowy zasób i brak
odbijania zawartości w błędach. Dwanaście nowych testów PostgreSQL sprawdza
pochodzenie, historię, odmowę ogólnego zapisu, zakres właściciela/fazy, bariery,
kwarantannę, gościa, dzierżawy oraz jawny krok workflow.

Regresja obejmuje magazyn ocen, znane blokady, workflow i lokalny dowód Order.
Testy logicznego kwalifikatora nadal używają abstrakcyjnych dowodów; testy
trwałego zapisu potwierdzają teraz FIELD_SCOPE przez zweryfikowaną definicję.
Poprzedni dowód bez pochodzenia nie kwalifikuje się mimo innych pozytywnych
przesłanek. Nie jest to nowy odbiór Gateway/przeglądarek ani całego A43.41.

Pełny drill 14 baz i Redis obejmuje zachowanie skrótu przy archiwalnej generacji,
pusty i powtórny replay oraz dotychczasowe zegary, bindingi, bariery i streamy.
Restore nie nadaje dowodowi bieżącej generacji i nie zwalnia środowiska do ruchu.
Dowody: tmp/audit-a43-40-field-scope/report.json i a43-final-review.json.
Aktualny stan i braki: [A43_ODBIOR_KONCOWY.md](A43_ODBIOR_KONCOWY.md).

Rzeczywiste dane, sekrety/SOPS, środowiska i katalogi E bez zmian.
Nie wdrożono workera czyszczenia lub rozproszonej bariery biznesowych zapisów.
A05/A41 i zdjęcia nadal odłożone.
