# A43.1 — ograniczenie i retencja logu rekomendacji

Data: 27.10.2026. Pakiet 58 audytu, branch `Audit_0.1`.

**A43.1 wykonane i zweryfikowane lokalnie. Całe A43 pozostaje otwarte.**

## Zachowanie

Domyślnie Recommendation Service nie zapisuje rekordu dla każdego wywołania.
Wcześniejszy `recommendation_requests` był technicznym logiem; ranking korzysta
z katalogu i opłaconych zakupów w Order Service, a nie z tej tabeli.
Wyniki i kontrakty rekomendacji pozostają zgodne, włącznie z polem `imageUrl`.

`RecommendationTelemetry` zlicza udane odpowiedzi i liczbę wyników dla dwóch
strategii. Metryki mają stałą liczbę serii: etykiety nie zawierają klienta,
produktu, adresu e-mail, tokenu ani identyfikatora żądania. Liczniki i summary
nie przechowują listy pojedynczych wywołań.

Log diagnostyczny można jawnie włączyć. Zapis ma własną krótką transakcję
repozytorium. Awaria bazy w tym opcjonalnym zapisie zwiększa licznik błędów
i nie zmienia poprawnie wyznaczonej rekomendacji. Komponent telemetryczny nie wypisuje ostrzeżenia dla każdego
wywołania ani parametrów SQL. Usunięto transakcję obejmującą zdalne
odczyty katalogu/historii i ranking.

## Retencja

`RecommendationLogCleanup` czyści wyłącznie `recommendation_requests`, także
starsze rekordy sprzed tej zmiany. Domyślny techniczny okres przechowywania to
siedem dni; można go zmienić w konfiguracji. Granica UTC jest ustalana raz
na przebieg. Rekord równy granicy i rekordy nowsze pozostają zachowane.
Dotyczy to aktywnej bazy; retencja kopii pozostaje w A36 i dalszym A43.

Jedna transakcja usuwa najwyżej 500 najstarszych rekordów. Przebieg wykonuje
najwyżej cztery takie transakcje, po czym kończy pracę. `FOR UPDATE SKIP LOCKED`
pozwala równoległym instancjom pominąć zajęte wiersze; nie ma globalnego locka
ani odczytu całego logu do JVM. Zapytanie DELETE ma timeout dwóch sekund.
Pierwszy przebieg jest po minucie, kolejne po dziesięciu minutach od zakończenia
poprzedniego. Nie wykonuje się czyszczenia przy każdym żądaniu.

Migracja V2 dodaje indeks `(requested_at, id)` i usuwa zastępowany indeks
samego czasu. Nie usuwa danych. Usuwanie starych rekordów wykonuje dopiero
worker z własnymi, małymi transakcjami. Pozostaje indeks klienta dla jawnie
włączonej diagnostyki. Jeśli starszych rekordów jest więcej niż budżet partii,
zaległość pozostaje widoczna; worker kontynuuje podczas kolejnych przebiegów.
Siedem dni jest progiem kwalifikacji do czyszczenia, a nie deklaracją, że
zaległość nigdy nie wystąpi podczas awarii lub dużego napływu danych.

## Konfiguracja

Parametry są zwykłą konfiguracją aplikacji. Są przekazywane również przez
Compose i ConfigMap Kubernetes; nie wymagają nowych sekretów.

| Zmienna | Domyślnie | Znaczenie |
| --- | --- | --- |
| `RECOMMENDATION_REQUEST_LOG_ENABLED` | `false` | Jawny zapis pojedynczych wywołań do bazy |
| `RECOMMENDATION_REQUEST_LOG_RETENTION` | `7d` | Wiek kwalifikujący rekord do czyszczenia |
| `RECOMMENDATION_REQUEST_LOG_CLEANUP_ENABLED` | `true` | Włączenie workera retencji |
| `RECOMMENDATION_REQUEST_LOG_CLEANUP_BATCH_SIZE` | `500` | Wiersze w transakcji, od 1 do 1000 |
| `RECOMMENDATION_REQUEST_LOG_CLEANUP_MAX_BATCHES` | `4` | Transakcje w przebiegu, od 1 do 20 |
| `RECOMMENDATION_REQUEST_LOG_CLEANUP_INTERVAL_MS` | `600000` | Odstęp po zakończeniu przebiegu, minimum 1000 ms |
| `RECOMMENDATION_REQUEST_LOG_CLEANUP_INITIAL_DELAY_MS` | `60000` | Opóźnienie pierwszego przebiegu, minimum 0 |

Okres retencji musi wynosić co najmniej sekundę. Nieprawidłowe wartości
zatrzymują start zamiast uruchamiać nieograniczone albo ciągłe czyszczenie.
Przy wyłączonym czyszczeniu metryki stanu pozostają nieznane, aż do skutecznego
odczytu; nie prezentują fałszywego zera.

## Monitoring

Metryki są dostępne przez istniejący endpoint Prometheus Recommendation Service.
Stan logu jest migawką z ostatniego skutecznego przebiegu. `-1` oznacza brak
takiej migawki, a nie pustą tabelę.

| Metryka Prometheus | Interpretacja |
| --- | --- |
| `recommendation_responses_total{strategy}` | Udane odpowiedzi, bez identyfikatorów użytkowników |
| `recommendation_result_count_count` / `_sum` | Liczba odpowiedzi i suma zwróconych wyników |
| `recommendation_request_log_enabled` | Czy zapis diagnostyczny jest włączony |
| `recommendation_request_log_writes_total{outcome}` | Udane/nieudane zapisy diagnostyczne |
| `recommendation_request_log_deleted_total` | Rzeczywiście zakończone usunięcia, także przed częściowym błędem przebiegu |
| `recommendation_request_log_cleanup_runs_total{outcome}` | Udane i nieudane przebiegi |
| `recommendation_request_log_oldest_age_seconds` | Wiek najstarszego wpisu w ostatniej migawce |
| `recommendation_request_log_cleanup_pending` | 1: pozostała zaległość, 0: brak, -1: nieznane |
| `recommendation_request_log_cleanup_last_success_timestamp_seconds` | Czas ostatniego pełnego odczytu stanu |
| `recommendation_request_log_cleanup_last_deleted` | Liczba usunięć w ostatnim pełnym przebiegu |
| `recommendation_request_log_cleanup_enabled` | Czy worker jest włączony |
| `recommendation_request_log_retention_seconds` | Skonfigurowany próg wieku |

Podczas problemu sprawdzić `cleanup.enabled`, świeżość `last.success`, wzrost
nieudanych przebiegów i `pending`. Przy zaległości ograniczyć zapis diagnostyczny
lub dostosować budżet workera do wydajności bazy. Monitorowanie nie wykonuje
pełnego COUNT tabeli ani zapytań do bazy przy każdym scrape.

## Odbiór i zakres pozostały

**PASS**:

- **125 testów jednostkowych Java**: 108 wspólnej biblioteki i 17 Recommendation
  Service. Szesnaście testów jest nowych: metryki/etykiety, opcjonalny zapis,
  błędy, konfiguracja, budżet partii i zgodność rankingu.
- **Cztery nowe testy na PostgreSQL 16**: V1/V2 i indeksy, małe partie ze ścisłą
  granicą czasu, pomijanie zablokowanego wiersza, dwie równoległe transakcje
  oraz nadrabianie zaległości bez usuwania świeżych danych. Łącznie 129 Java,
  bez pominiętych przypadków i błędów.
- **12 testów szablonów Kubernetes** i kontrola parenta 17 modułów — PASS.
  Potwierdzono również strukturę korelacji i odporności 15 JAR-ów.
- YAML Compose/ConfigMap oraz `git diff --check` — PASS.

Raport: `tmp/audit-a43-1/report.json`. Logi: `tmp/a43-1-maven-verify-final.log`
i `tmp/a43-1-layout.log`. Raport zawiera hashe kodu, migracji, konfiguracji,
artefaktu i dowodów testowych. Wcześniejsze próby są zapisane osobno.
Nie powtarzano testów UI ani pozostałych usług, ponieważ ich kod nie zmienił
się w tym etapie. Nie budowano obrazu i nie wdrażano na dev/prod.

Testy używają własnego kontenera PostgreSQL i danych fixture. Profil
`integration` uruchamia je przez istniejący krok Maven w CI:

```text
./mvnw -pl recommendation-service -am -Pintegration verify
```

Przy przyszłym wdrożeniu dostarczyć kod, migrację V2 i konfigurację razem.
Nie uruchamiano workera na bazie developerskiej ani produkcyjnej.

Całe A43 pozostaje otwarte: potrzebne są dalsza retencja tokenów/MFA, pozostałej
telemetrii, audytu i zdarzeń oraz uzgodniony eksport/usuwanie/anonimizacja danych
obejmujące usługi i kopie. Obecny etap nie zmienia dokumentów biznesowych,
płatności, tokenów, niewysłanych zdarzeń ani architektury usług. A05, całe A41
oraz zmiany dotyczące samych zdjęć nadal są odłożone.
