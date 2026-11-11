# A43.15 — rejestr cyklu życia danych i bramka kompletności

Aktualizacja zamknięcia A43.34 (29.10.2026): 70 tabel/666 kolumn,
145 migracji, 62 unikalności, 41 mechanizmów i 17 kategorii kopii.
Minimalny wniosek czatu zawiera tylko ID rozmowy/wniosku, MANUAL_REVIEW
i datę. Każdy odczyt wymaga tokenu rozmowy, bez danych konta lub treści.
Retencja A43.40 i ręczna procedura A43.39 pozostają wymagane; całościowe
możliwości pending. [Zakres](A43_USUWANIE_PROFILU_I_TRESCI.md).


Data: 27.10.2026. Pakiet 72 audytu, branch `Audit_0.1`.

## Zakres

Dodano [kontrakt danych](../scripts/qa/contracts/data-lifecycle.json) i
[bramkę kontroli](../scripts/qa/data-lifecycle-check.py). Rejestr łączy
właściciela bazy/tabeli, klasy danych, relację z klientem, istniejące
mechanizmy retencji i pozostały zakres eksportu/usunięcia. Obejmuje także
znane przestrzenie Redis i kopie poza bazami.

To kontrola metadanych źródeł. Nie otwiera baz, Redis, runtime, skarbców,
backupów ani plików klienta. Nie przyjmuje ID klienta, nie generuje SQL
usuwającego i nie wysyła żądań do dostawców. Zapisuje wyłącznie opcjonalny,
nowy raport we wskazanym katalogu workspace. Domyślny przebieg tylko wypisuje JSON.

**Eksport konta, jego usunięcie i selektywna obsługa kopii pozostają pending.**
PASS oznacza zgodność przeglądanego rejestru ze wspieranymi metadanymi
repozytorium, nie wykonanie tych operacji, zgodność prawną lub inwentaryzację
produkcyjnego środowiska. Nie wybrano okresów prawnych ani nie aktywowano purge/compact/trim.

## Stan rdzenia A43.34 przed domknięciem — 29.10.2026 (historyczny)

Bieżący spis: 69 tabel/662 kolumny, 144 migracje, 41 mechanizmów,
17 kategorii kopii i 11 źródeł Redis. Nowe minimalne klucze recenzji
oraz postęp/polityka czyszczenia są osobowe, nie anonimowe. Domyślnie
wyłączona aktywacja; całość A43.34 oczekuje na decyzję czatu. Globalne
możliwości pozostają pending. [Zakres](A43_USUWANIE_PROFILU_I_TRESCI.md).

## Aktualizacja A43.33 — 29.10.2026

Bieżący rejestr obejmuje 14 właścicieli, 68 tabel/653 kolumny, 142 migracje,
40 mechanizmów i 17 kategorii kopii. A43.33 dodało 14 minimalnych znaczników
oraz żądania i kroki Customer. Sekrety statusu nie są wydawane; minimalny
status jest dostępny po osobnym dowodzie przez 168h. Znaczniki i żądania
pozostają osobowe, bez automatycznego TTL do A43.40.
[Protokół i granice](A43_PROTOKOL_USUWANIA_KONTA.md).
Niższa tabela i pierwotny odbiór pokazują historyczny stan A43.15; aktualny
spis źródłowy znajduje się w kontrakcie JSON. Globalne możliwości pending.

## Mapa bazowa A43.15

| Baza — alias komponentu | Tabele |
| --- | ---: |
| product | 3 |
| order | 3 |
| inventory | 4 |
| payment | 6 |
| notification | 3 |
| customer | 7 |
| delivery | 3 |
| promotion | 5 |
| wishlist | 1 |
| review | 1 |
| recommendation | 1 |
| chat | 3 |
| returns | 3 |
| billing | 6 |

Łącznie **14 właścicieli baz, 49 tabel i 120 migracji**. Klasyfikacje mogą
się nakładać: osobowe, uwierzytelniające, biznesowe, deduplikacja, telemetria
i referencyjne. Rejestr nie przypisuje automatycznego usuwania całej tabeli
na podstawie samej obecności mechanizmu retencji wybranych kolumn.

Redis: **5 właścicieli usług, 5 przestrzeni i 10 plików Java z obsługiwanymi
odwołaniami** — streamy płatności/faktur, ochrona logowania, wyzwania MFA/
WebAuthn i limiter Gateway. Nazwy streamów mogą być zmieniane konfiguracją.

**15 opisanych rodzajów kopii** obejmuje szyfrowane zestawy odtwarzania,
dotychczasowe backupy baz, AOF/RDB, logi zadań CC, historię wysyłek CC,
ustawienia AI, logi aplikacyjne, storage monitoringu, pamięć cache PDF,
stan przeglądarki, pobrane pliki, kopie asystenta u dostawcy, SMTP/płatności,
skarbce/keyringi usług i sesje operatora CC.
Lokalizacje są opisami, nie listą istniejących plików. Bramka ich nie rozwija
i nie czyta. Rejestr wskazuje **22 istniejące mechanizmy/ograniczenia**,
w tym A43.1–A43.14; każda referencja ma istniejący plik źródłowy.

## Zasady powiązania z osobą

- customer_id i order_id wymagają zweryfikowanej tożsamości i grafu zamówień;
  powiązanie po samym e-mailu nie jest dowodem własności.
- W czacie trzeba zweryfikować własność rozmowy/capability. Konta i anonimowe
  rozmowy nie mogą być automatycznie łączone wyłącznie po nazwie/adresie.
- source_id Notification zależy od event_type: może oznaczać zamówienie lub
  wpis rozmowy. W Billing ACCEPTED related_id oznacza zamówienie; w Notification
  powiadomienie. Rejestr zachowuje to rozróżnienie.
- Profil Customer zawiera także hashe/znaczniki bezpieczeństwa. Wymaga
  bezpiecznej projekcji; tokeny, kody, sekrety i credential records są
  wykluczone z surowego eksportu. To nie wyklucza przyszłego eksportu
  bezpiecznych metadanych po osobnym zaprojektowaniu DTO.
- Identyfikatory outbox/inbox, rezerwacji, sag, follow-up i korekt blokują
  powtórne skutki. Usunięcie wymaga planu zachowania tej ochrony, nie DELETE
  całej historii klienta. W event_key Payment nadal może być e-mail.
- Audyt, notatki, logi i wiadomości mogą obejmować inne osoby. Potrzebna
  jest redakcja i kontrola uprawnień przy przyszłym eksporcie.

Nie określono prawnej podstawy przechowywania dokumentów ani okresów.
`pending-fence-design` oznacza techniczną zależność wymagającą projektu,
a nie prawną decyzję o odmowie usunięcia. `not-account-scoped` oznacza
brak relacji konta w opisanej tabeli, nie ogólny certyfikat braku danych osobowych.

## Co egzekwuje CI

```text
python3 scripts/qa/data-lifecycle-check.py
python3 scripts/qa/data-lifecycle-check.py --output tmp/lifecycle-review-new.json
```

Raport musi być nowym plikiem w workspace. Istniejący cel nie jest
nadpisywany. Nie ma opcji automatycznego zaakceptowania zmian rejestru.
Kontrakt znajduje się poza `docs/`, ponieważ CI pomija zmiany dokumentacji.
Zmiana samego kontraktu uruchamia normalny przebieg. Dodano osobny krok
bramki oraz test jej rejestracji i reguł paths-ignore.

Bramka sprawdza:

1. Zgodność właścicieli/aliasów z `deploy/components.json` i wszystkich tabel
   odtworzonych z kolejności migracji SQL z rejestrem. Nowa lub usunięta
   tabela nie przejdzie po samej aktualizacji hasha.
2. SHA-256 **każdej migracji**. Dodanie/zmiana kolumny, indeksu lub danych
   inicjalnych wymaga przeglądu. Bramka nie klasyfikuje automatycznie kolumn;
   przegląd hashy wymusza ocenę treści przez autora/reviewera.
3. Obsługiwane odwołania Java do klientów Redis i wspólnego stream engine.
   Nowy plik lub zmiana istniejącego źródła wymaga przeglądu; właściciel
   musi deklarować Redis w komponencie i mieć opis przestrzeni.
4. Klasyfikację, opis relacji z klientem, ograniczenia retencji, bezpieczną
   projekcję credential data, znane mechanizmy i kategorie kopii.
5. Istnienie referencji do kodu oraz bezpieczne ścieżki w workspace,
   limity rozmiaru źródeł i brak zduplikowanych kluczy JSON.

Lexer pomija komentarze/literały, wspiera zwykłe CREATE/DROP/RENAME tabel
i public schema. Dynamiczne DDL w blokach, obce tabele, materialized views,
niestandardowe nazwy migracji i Java migrations wymagają jawnego rozszerzenia
parsera/inwentaryzacji — nie są po cichu uznawane za pokryte. To nie jest
pełny parser PostgreSQL ani porównanie z uruchomioną bazą.
Pozostałe formy tworzenia storage, np. SELECT INTO lub rozszerzenia, wymagają
przeglądu treści migracji; bramka nie dowodzi ich automatycznej kompletności.

Wykrywanie Redis jest konserwatywną analizą obsługiwanych nazw typów/klientów
i hashy, nie analizą wszystkich możliwych ścieżek wykonania. Znane kopie są
deklarowane; nowy backend logów, klient lub dostawca wymaga ręcznego dopisania.
Rejestr nie wykryje samodzielnie nieznanego dysku/off-site ani danych,
których aplikacja nie opisuje w repozytorium.

## Praca przy następnej zmianie

Po błędzie bramki sprawdzić wskazane pliki. Zweryfikować właściciela,
relację podmiotu, sekrety, nowe kopie i wpływ na deduplikację/odtwarzanie.
Uzupełnić wpisy i ograniczenia, potem ręcznie zaktualizować odpowiednie
hashy. Ponowić bramkę i testy. Samo przepisanie hashy bez przeglądu nie
wykonuje procesu prywatności. Skrypt jednorazowego przygotowania w tmp
nie jest narzędziem do automatycznej akceptacji przyszłych zmian.

Dalsze etapy A43 muszą dostarczyć uwierzytelniony proces żądania, plan
rozwiązania podmiotu we wszystkich usługach, bezpieczny eksport z redakcją,
uzgodnione skutki usunięcia/anonimizacji oraz postępowanie z kopiami i restore.
Okresy/aktywacje istniejących retencji pozostają decyzją użytkownika.
Nie wznawiać automatycznie A05, całego A41 ani zmian samych zdjęć.

## Odbiór

<!-- A43_15_RESULTS -->
- **245 testów Python — PASS, bez pominiętych**, w tym 26 nowych.
  Wynik obejmuje istniejące 12 Kubernetes — nie sumować ich ponownie.
  Osobny odbiór nowych 26 także przeszedł; nie zwiększa sumy 245.
- Potwierdzono 14 baz/49 tabel/120 migracji, 5 właścicieli i przestrzeni Redis,
  10 źródeł Java Redis, 15 rodzajów kopii i 22 referencje mechanizmów.
  Po uzupełnieniu metadanych keyringów i sesji CC ponowiono bramkę — PASS.
- Mutanty wykryły nową/usuniętą tabelę, samo przepisanie hasha bez wpisu,
  zmiany kolumn, nowe/zmienione użycie Redis, brak właściciela/kopii,
  niebezpieczną projekcję credential data i nieuzasadnione oznaczenie eksportu/
  usunięcia jako wykonanych. Komentarze, literały, quoted identifiers,
  DROP/RENAME i nieobsługiwane formy migracji sprawdzone osobno.
- CLI z zablokowanymi socketami/procesami potomnymi przeszedł i nie zmienił
  wejściowych plików fixture. Ścieżki poza workspace, symlink wychodzący
  poza fixture, zbyt duży plik, duplikaty JSON oraz nadpisanie raportu są odrzucane.
- CI ma nowy krok; test potwierdził, że jego paths-ignore nie pomija kontraktu.
  Bramka struktury CC: 15 modułów, 54 fasady i jedna implementacja HTTP — PASS.

Raport: `tmp/audit-a43-15/report.json`; regresja:
`tmp/a43-15-python-full.log`; odbiór nowych przypadków:
`tmp/a43-15-lifecycle-final-tests.log`; bieżący stan rejestru:
`tmp/a43-15-inventory-check.json`.
<!-- /A43_15_RESULTS -->

Bez nowych zależności, zmian Java/JS, migracji baz, endpointów i widoku CC.
Nie uruchamiano Maven, przeglądarek, realnych usług, zdalnego CI ani wdrożenia
dev/prod. Testy używają własnych metadata fixture i metadanych źródeł;
przypadek CLI działa z zablokowaną siecią i uruchamianiem procesów potomnych.


## Uzupełnienie A43.39 — 30.10.2026

Bieżąca macierz maszynowa obejmuje 46 mechanizmów i 20 kategorii kopii.
Dodano `assistant-egress-approvals`: ograniczony rejestr skrótów zgód w RAM CC,
jedna zgoda na sesję, 64 wpisy, 5 minut, bez treści i kluczy. Materiał autoryzacji
wyłączony z eksportu; nie dowodzi własności danych osób w wiadomości.
`assistant-provider-copy`, `mail-payment-providers`, `browser-downloads` mają
[procedurę A43.39](A43_DOSTAWCY_PLIKI_I_DOWODY_CZATU.md). Wymagane dowody
runtime i dostawców pozostają ręczne; A05 odłożone. Schemat bez zmian:
85 tabel, 749 kolumn, 163 migracje. Pełne A43 i usunięcie nadal niepotwierdzone.
