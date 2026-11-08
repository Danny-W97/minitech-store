# A30 — budżet cache PDF i strumieniowy eksport ZIP

Zakres obejmuje dokumenty i eksport. Zdjęcia pozostają odłożone na osobne
polecenie użytkownika. Zmiany dotyczą Billing, Gateway, CC i CLI; bez migracji DB.

## Cache dokumentów

Faktury i korekty korzystają z jednego cache LRU. Budżet bajtów jest wspólny
dla obu typów, a klucz zawiera typ dokumentu i ID. Domyślne limity to 32 MiB,
512 dokumentów i 15 minut od zapisu. Odczyt nie odnawia TTL. Dokument większy
niż cały budżet jest zwracany bez zapisu w cache; budżet 0 wyłącza cache.
Liczba wpisów ogranicza również narzut metadanych. Kopie bajtów chronią cache
przed modyfikacją przez odbiorcę. Limit bajtów opisuje przechowywane PDF-y,
nie całkowity heap procesu ani przejściowe bufory generowania/pobierania.

Metryki `billing.pdf.cache.bytes` i `billing.pdf.cache.entries` są dostępne
przez istniejący Actuator/Micrometer. Wygasłe wpisy usuwane są przy dostępie,
zapisie lub odczycie metryki; retencja zawsze pozostaje w budżecie cache.

## Eksport

`GET /invoices/export/zip?from=...&to=...` zachowuje uprawnienia operatora.
Daty ISO-8601 ze strefą czasową są opcjonalne, a oba końce zakresu włączne.
Plan eksportu ustala maksymalne ID i liczbę faktur w krótkiej transakcji
REPEATABLE READ. Daty trafiają do predykatów SQL tylko wtedy, gdy je podano.
Nowe faktury o większym ID nie są dołączane do rozpoczętego eksportu.

Odczyt obejmuje domyślnie 25 faktur na partię, uporządkowanych po ID malejąco.
Każda partia ma własną krótką transakcję i inicjalizuje pozycje przed jej
zakończeniem. Generowanie PDF oraz zapis ZIP odbywają się już bez transakcji
i bez zapełniania lub wypierania interaktywnego cache. Zużycie pamięci zależy
od partii, jednego aktualnie generowanego PDF i ograniczonych metadanych ZIP.
Archiwum używa ZIP z poziomem NO_COMPRESSION — PDF-y są już kompresowane.
Nazwy plików zawierają bezpieczny numer faktury oraz ID, bez kolizji po
normalizacji znaków i bez ścieżek katalogowych.

Domyślnie dozwolone są 2 równoległe eksporty na proces oraz maks. 10 tys.
faktur na archiwum. Limit dokumentów można zmniejszyć; dopuszczalny zakres
konfiguracji to 1–10000, aby ograniczyć również katalog centralny ZIP w RAM.
Przekroczenie limitu daje 413 z komunikatem o zawężeniu dat, a zajęta
przepustowość 429 z `Retry-After`. Walidacja zakresu, liczby faktur i rezerwacja
slotu następują przed rozpoczęciem odpowiedzi ZIP. Odwrócony zakres daje 400.

Gateway strumieniuje publiczną i legacy trasę eksportu przez bufor 32 KiB,
z przepływem ograniczanym przez odbiorcę. Nie tworzy `byte[]` całego archiwum,
nie podąża za przekierowaniami i nie ponawia tego wywołania automatycznie.
Osobny limit eksportu i bulkhead/circuit breaker `billing-export` izolują
operacje dokumentowe od zwykłych żądań Billing. Błędy 4xx pozostają 4xx.
Przerwanie po wysłaniu bajtów kończy transfer — nie dopisuje odpowiedzi JSON
ani kolejnego archiwum. Billing nie finalizuje poprawnego katalogu ZIP po
błędzie generowania któregoś PDF, więc częściowy eksport nie udaje kompletnego.

Gateway ma osobny timeout połączenia (1 s) i bezczynnego odczytu odpowiedzi
(30 s). Billing sprawdza budżet 5 minut między partiami/dokumentami i po
generowaniu PDF. Jest to budżet pracy, nie bezwarunkowy wall-clock timeout
każdego blokującego zapisu do powolnego klienta. Taki klient zajmuje slot
eksportu, lecz nie utrzymuje transakcji DB. Dalsze przekrojowe timeouty i
transakcje pozostają osobnym etapem A31.

## Control Center i CLI

CC ma pola Od/Do z lokalną datą i godziną; przesyła je jako UTC. W trakcie
pobierania blokuje ponowne kliknięcie, pokazuje komunikat błędu i umożliwia
ponowienie po jego zakończeniu. Backend CC przekazuje status błędu i nie
uruchamia automatycznej próby bezpośrednio do Billing. Nadal używa sesji
panelu i zweryfikowanego operatora. Pobieranie w przeglądarce tworzy Blob;
dla dużych archiwów CLI zapisuje strumień bezpośrednio na dysk.

```sh
bash scripts/control-center/control-center.sh invoices-export-zip \
  /sciezka/faktury.zip 2026-01-01T00:00:00Z 2026-01-31T23:59:59Z
```

CLI nie ponawia nieudanego strumienia. Zapisuje go do pliku tymczasowego
w folderze docelowym, weryfikuje katalog ZIP/CRC i dopiero wtedy zastępuje
plik docelowy. Błąd lub rozłączenie zachowuje poprzedni plik.

## Konfiguracja i wdrożenie

| Zmienna | Domyślnie |
| --- | --- |
| `BILLING_PDF_CACHE_MAX_BYTES` | 33554432 |
| `BILLING_PDF_CACHE_MAX_ENTRIES` | 512 |
| `BILLING_PDF_CACHE_TTL` | PT15M |
| `BILLING_EXPORT_PAGE_SIZE` | 25 (1–200) |
| `BILLING_EXPORT_MAX_DOCUMENTS` | 10000 (1–10000) |
| `BILLING_EXPORT_MAX_CONCURRENT` | 2 |
| `BILLING_EXPORT_MAX_DURATION` | PT5M |
| `GATEWAY_EXPORT_MAX_CONCURRENT` | 2 |
| `GATEWAY_EXPORT_CONNECT_TIMEOUT_MS` | 1000 |
| `GATEWAY_EXPORT_READ_TIMEOUT_MS` | 30000 |

Wartości są dostępne w Compose, ConfigMap K8s i przykładzie środowiska dev.
Limity dotyczą procesu, więc liczba replik mnoży łączny budżet. Zachowaj zapas
heap dla JPA/PDFBox i generowania dokumentów. Limit archiwum nie jest limitem
całej bazy — większą historię należy podzielić zakresem dat.

Przebudować Billing i Gateway wraz z `platform-security`, wdrożyć wszystkie
repliki obu usług oraz zaktualizować CC/CLI. Najpierw Billing (partie/cache),
potem Gateway (strumień), następnie restart CC i odświeżenie panelu.
Nie ma nowej migracji Flyway, zmiany sekretów ani przebudowy zdjęć.

## Weryfikacja

```sh
./mvnw --batch-mode -pl billing-service,api-gateway -am package
python3 scripts/qa/document-export-e2e.py
python3 scripts/qa/access-control-e2e.py
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
bash scripts/qa/unit/export-zip-test.sh
pnpm run test:cc
```

Nowe testy obejmują wspólny budżet/LRU/TTL i współbieżność cache, granice dat,
brak zapełniania cache eksportem, ograniczoną hydratację JPA, błąd generowania,
zwalnianie slotów, rozłączenie, brak ponowień, timeout bezczynnego odczytu,
przekazanie pierwszych bajtów przed końcem produkcji oraz ścisły odczyt
głównych konfiguracji YAML z kontrolą duplikatów kluczy.

Scenariusz `document-export-e2e.py` używa własnego PostgreSQL, losowych
sekretów i rzeczywistych JAR-ów. Wybiera 60 PDF-ów ze 100 tys. faktur,
kontroluje SQL i cache/TTL, a następnie przesyła poprawny ZIP 160 MiB przez
Gateway z heap 96 MiB. Kontrolowany producent ZIP i introspekcja to izolowane
fixture; uprawnienia prawdziwego systemu sprawdza także pełna regresja HTTP.
Logi, zestawienia zapytań i pomiar heap znajdują się w `tmp/document-export-*`.
