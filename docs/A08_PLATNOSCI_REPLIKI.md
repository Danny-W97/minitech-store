# A08 — płatności przy równoległych żądaniach i wielu replikach

Pakiet 17 z 25.10.2026 na `Audit_0.1` usuwa zależność koordynacji płatności od
blokady w jednej JVM. Odbiór dotyczy protokołu aplikacji przy idempotentnym
dostawcy. Rzeczywisty adapter dostawcy i jego testy sandbox pozostają w A05.

## Zachowanie API

- Próba jest rezerwowana w krótkiej transakcji SQL. Unikalność zamówienia i
  klucza chroni także pierwsze równoległe inserty. Konflikt klucza między
  różnymi zamówieniami zwraca 409 zamiast nieobsłużonego błędu bazy.
- Równoległe żądanie tego samego polecenia czeka do 5 sekund bez transakcji
  i po zakończeniu pierwszego odczytuje identyczny zapisany wynik. Jeśli próba
  nadal trwa, zwraca 409 z `Retry-After: 1`; klient ponawia to samo polecenie.
  Krótszy deadline ogranicza oczekiwanie i kończy je HTTP 504.
- `PAYMENT_CONCURRENT_WAIT_MS` ustawia oczekiwanie, ograniczone w kodzie do
  przedziału 0–10000 ms. Nie blokuje ono innych zamówień przypisanych dawniej
  do tego samego segmentu lokalnej blokady. Dotychczasowy coordinator usunięto.
- Zapisane wyniki SUCCEEDED, FAILED i zakończone PENDING dla pobrania są
  odtwarzane bez ponownego wywołania dostawcy i skutków biznesowych.
  Odtwarzanie PENDING nie oznacza wdrożenia rozliczenia pobrania — to A10.

## Tożsamość i odzyskiwanie próby

Nowa próba zapisuje SHA-256 polecenia: zamówienie, znormalizowaną metodę,
symulację odmowy, odbiorcę, klienta, wybór faktury i dane faktury. Kodowanie
długości pól odróżnia null, pusty tekst i granice pól. W bazie nie powstaje
kopia danych faktury w tej kolumnie. Klucz idempotencji nie jest częścią skrótu:
ponowienie tego samego zamówienia z innym, wolnym kluczem nadal używa klucza
oryginalnej próby.

Zmiana polecenia zwraca 409, również po zakończeniu. Kwota z Order Service jest
ponownie porównywana pod blokadą przy odzyskiwaniu niedokończonej próby; nie
można wysłać nowej kwoty lub metody z poprzednim kluczem dostawcy. Nagłówek
klucza ma limit 100 znaków. Rozbieżne klucze w body i nagłówku zwracają 409.
Niepoprawny nagłówek lub znaki sterujące zwracają 400.

Zatwierdzenie dostawcy jest zapisywane osobną krótką transakcją przed zmianą
statusu zamówienia. Po awarii procesu następna replika zachowuje tę referencję
i pomija ponowną autoryzację. Jeśli proces umarł przed lokalnym zapisem odpowiedzi,
wywołuje dostawcę z zapisanym oryginalnym kluczem. Kontrakt `PaymentGateway`
wymaga od adaptera zwrócenia pierwotnego wyniku dla tego klucza. Adapter
symulacyjny generuje teraz stabilną referencję w niezależnych procesach.

Token i ważność rezerwacji są sprawdzane przed kolejnymi skutkami oraz zapisem
autoryzacji i wyniku. Wznowiony stary proces nie może nadpisać wyniku następcy.
Lokalny token nie cofa już wysłanego żądania HTTP. Dostawca musi mieć trwałą
idempotencję, a zdalne operacje biznesowe zachować własną idempotencję. Test
liczy osobno wywołania dostawcy i rzeczywiste zatwierdzenia: po awarii mogą
wystąpić dwa wywołania, lecz tylko jedno zatwierdzenie.

## Migracja i uruchomienie

Payment V10 dodaje `request_fingerprint` i `result_recorded_at`. Istniejące
SUCCEEDED/FAILED są oznaczane jako gotowe wyniki; niedokończone PENDING pozostają
do odzyskania. Historyczny rekord nie ma pełnego skrótu starego polecenia,
więc zachowuje ochronę metody/kwoty, ale nie pozwala odtworzyć dawnych danych
faktury. Nowe próby mają pełny skrót. Znaczniki czasu odpowiedzi są zapisywane
z precyzją mikrosekund odpowiadającą PostgreSQL, aby pierwsza odpowiedź i jej
odczyt z innej repliki były zgodne także w tych polach.

Wdrożenie wymaga skoordynowanej wymiany wszystkich replik Payment Service:
zatrzymać przyjmowanie płatności, poczekać na zakończenie obsługi, zatrzymać
stare repliki, wykonać migrację i uruchomić nowe. Nie mieszać wersji:
stary kod nie respektuje skrótu, zapisanego wyniku ani zatwierdzenia po awarii.
Porzuconą próbę przejmuje się po wygaśnięciu dotychczasowej rezerwacji
(`PAYMENT_PROCESSING_LEASE_MS`, domyślnie 120 sekund, minimum 60 sekund).
Skrypt QA skraca wyłącznie własne rekordy testowe, by nie czekać 120 sekund.

W tym pakiecie nie uruchamiano migracji ani wdrożenia na dev/prod. Brak
rzeczywistego dostawcy nadal blokuje płatności produkcyjne.

## Regresja

Po `./mvnw package` uruchomić w Linux/WSL z Java 25 i Docker:

```bash
python3 scripts/qa/payment-replicas-e2e.py
python3 scripts/qa/http-transactions-e2e.py
```

Pierwszy skrypt używa dwóch niezależnych JVM, wspólnego jednorazowego
PostgreSQL, losowych portów/tokenów i lokalnego idempotentnego dostawcy.
Adapter fixture jest kompilowany z `scripts/qa/fixtures/PaymentReplicaProbe.java`
poza dystrybucyjnym JAR-em. Aplikacja nie otrzymała przełącznika pozwalającego
podmienić dostawcę w produkcji. Skrypt kończy własne procesy i usuwa kontener.

Scenariusze obejmują:

- dziesięć wymuszonych wyścigów pierwszego zapisu, identyczne odpowiedzi
  i pojedynczy zestaw skutków;
- równoległy wspólny klucz dla różnych zamówień, HTTP 409 i brak drugiej autoryzacji;
- zabicie procesu po zatwierdzeniu zdalnym, przed lokalnym zapisem odpowiedzi;
- zabicie procesu po lokalnym zapisie zatwierdzenia, przed odpowiedzią zmiany zamówienia;
- zatrzymanie i wznowienie starego procesu po zakończeniu przez następcę;
- odrzucenie zmian metody/polecenia/kwoty i za długiego klucza;
- odtwarzanie odmowy i wyniku PENDING między replikami;
- deadline podczas konfliktu, brak bezczynnej transakcji i obsługę innego zamówienia;
- śmierć procesu po zakończeniu oraz zerwanie połączenia klienta przed odczytem odpowiedzi;
- wykonanie rzeczywistej migracji V10 na historycznych wynikach w tabeli tymczasowej PostgreSQL.

Regresję replik dodano do CI; raport JSON i logi są zachowywane przy awarii.
Testy Java obejmują także atomowość wyniku/outboxu, brak niejawnego zatwierdzenia
po rollbacku, przejęcie rezerwacji, blokadę starego właściciela i odtworzenie
identycznej odpowiedzi przy pojedynczym połączeniu SQL.

### Wyniki odbioru 25.10.2026

- Pełny build wszystkich modułów: **274 testy Java**, bez błędów i pominięć.
  Po dodaniu ostatniej kontroli właściciela w ścieżce PAID wykonano czysty build
  Payment/platform-security: **32 + 50 testów**. Aktualne zestawy modułów obejmują
  łącznie **275 różnych testów**, wszystkie zakończone sukcesem.
- Regresja dwóch JVM: **PASSED**, 10 wyścigów pierwszego zapisu oraz scenariusze
  konfliktów, przerwania procesu, utraty odpowiedzi, starego właściciela i migracji.
  Raport: `tmp/payment-replicas-b577db7d/report.json`.
- Cała platforma z Gateway i dodatkową instancją produkcyjną bez dostawcy:
  **193 kontrole HTTP**, w tym checkout, faktura, role i zmienione ponowienie.
  Nie uruchamiano ponownie dużej paginacji ani całego zestawu bramek A33.
- Regresja HTTP/transakcji: **PASSED**. Płatność kończy oczekiwanie w 1,301 s,
  PDF w 1,270 s, SMTP w 1,273 s; niezależna saga kończy pracę podczas zablokowania
  innej. To lokalne pomiary fixture, a nie produkcyjne SLO.
- **7 testów mapy dowodów P1**, parsowanie YAML/Python i `git diff --check`:
  poprawne. Mapa potwierdza **8/14 P1**, a próba `--release` prawidłowo odrzuca
  pozostałe A05/A06/A09/A10/A11/A21. GitHub Actions nie uruchamiano zdalnie.

Logi: `tmp/audit-a08-full-build.log`, `tmp/audit-a08-clean-payment-build.log`,
`tmp/audit-a08-final-replicas.log`, `tmp/audit-a08-platform.log`,
`tmp/audit-a08-http-boundaries.log`, `tmp/audit-a08-python.log`,
`tmp/audit-a08-p1-readiness.log`. Zasoby jednorazowe usunięto. Potwierdzono brak
adaptera testowego i starego coordinatora w dystrybucyjnym JAR-ze.

## Pozostały zakres

A09 — trwałe odtwarzanie faktury, przesyłki i kuponu po awarii ich usług —
zakończono w późniejszym [pakiecie 18](A09_TRWALE_SKUTKI_PLATNOSCI.md).
A10/A11 nadal dotyczą pobrania i kompensacji,
A05 rzeczywistego dostawcy. A33 pozostaje częściowy do zakończenia pozostałych
P1. Zmiany dotyczące samych zdjęć pozostają odłożone.
