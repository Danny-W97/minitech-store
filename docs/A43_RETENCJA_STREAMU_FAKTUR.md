# A43.14 — retencja streamu zdarzeń faktur w Redis

Data: 27.10.2026. Pakiet 71 audytu, branch `Audit_0.1`.

## Zakres

Billing Service otrzymał osobny mechanizm retencji streamu określonego przez
`messaging.invoice-stream`/`INVOICE_EVENT_STREAM` — domyślnie `invoice-events`.
Dotychczasowy publisher, outbox SQL, inboxy i stream płatności zachowują swoje
protokoły. Mechanizm nie zmienia ACK, PEL, grup ani offsetów konsumentów.
Nie tworzy klucza ani grup, jeśli ich nie ma, i nie usuwa klucza streamu.

Domyślnie observe — odczyt stanu, bez usuwania. Próg 30 dni jest ustawieniem
początkowym obserwacji. Użytkownik wybiera okres oraz politykę odbiorców
i osobno aktywuje trim. Ogólne polecenie następnego etapu nie aktywuje usuwania.
Zmiany konfiguracji należy zastosować do procesu Billing.

## Polityka odbiorców

Do zezwolenia na usuwanie wymagana jest jawna polityka:

- `REQUIRED_GROUPS` zawiera nazwy wymaganych grup, które muszą istnieć;
- albo `ALLOW_NO_GROUPS=true` jawnie dopuszcza działanie bez deklaracji grup.

Domyślnie lista jest pusta i allow-no-groups=false: usuwanie pozostaje
zablokowane także wtedy, gdy istnieje jakaś grupa. Użytkownik powinien
wskazać wszystkich oczekiwanych trwałych odbiorców przed aktywacją.
ALLOW_NO_GROUPS nie pomija wymaganej, ale nieistniejącej grupy.

Każda faktycznie istniejąca grupa jest chroniona, także niewymieniona
w konfiguracji. Granica usuwania to najwcześniejszy z progu wieku,
last-delivered-id wszystkich grup i najstarszego pending ID każdej z nich.
Wpis na samej granicy również zostaje. Brak wymaganej grupy zatrzymuje
usuwanie, a nie uznaje jej za odbiorcę, który już przeczytał dane.

Grupy czytające od 0-0 i pending mogą więc zatrzymać retencję. Nie usuwać
ich ani nie przesuwać offsetów tylko w celu odblokowania czyszczenia.
Nawet w pełni zatwierdzony rekord na last-delivered-id pozostaje do czasu
przesunięcia granicy. Przyszłe dołączenie nowej grupy nie odzyska już
usuniętej historii. Odczyty XRANGE bez grupy nie mają trwałego offsetu
chronionego przez ten mechanizm; ich okno historii wymaga decyzji użytkownika.

## Atomowość i budżet

Jeden skrypt Lua sprawdza typ klucza, metadane, wszystkie grupy i pending,
a następnie wybiera i usuwa ograniczoną listę ID. Redis nie przeplata go
z XREADGROUP/ACK/publikacją. ID są porównywane jako części dziesiętne,
bez konwersji do liczb zmiennoprzecinkowych — również dla dużych sekwencji.

Użyto XRANGE z wyłączną górną granicą i COUNT oraz XDEL wskazanych ID,
żeby liczba usunięć nie zależała od rozmiaru makrowęzła. Redis obsługuje
wyłączne zakresy od 6.2; odbiór wykonano na obrazie Redis 7.
[Dokumentacja XRANGE](https://redis.io/docs/latest/commands/xrange/).

Domyślnie 500 rekordów w partii i cztery partie w przebiegu — do 2000.
Każda partia ma oddzielny atomowy snapshot; wiele instancji może pracować
równolegle. Krótka partia, brak dalszych kandydatów lub blokada kończą przebieg.
XINFO STREAM sprawdza liczbę grup przed odczytem pełnej listy. Więcej niż
32 grupy blokuje usuwanie bez rozwijania list pending; summary XPENDING
nie pobiera całego PEL.

Limity dotyczą liczby wpisów i grup, nie łącznej liczby bajtów. XRANGE
wewnątrz Lua odczytuje wybraną partię treści. Do Java wracają tylko siedem
pól metadanych, bez wiadomości i nazw grup; nie ma pełnego odczytu streamu.
Połączenie Redis zachowuje istniejące limity connect/command timeout Billing.

XDEL usuwa wpis logicznie. Fizyczne bloki są zwalniane, gdy makrowęzeł
jest pusty; nie jest to obietnica natychmiastowego spadku RSS ani pełnego
zatarcia treści. [Dokumentacja XDEL](https://redis.io/docs/latest/commands/xdel/).
Kopie SQL, AOF/RDB i backupy mają osobny cykl życia; trwałe ID/hashe inboxów
nadal blokują powtórne skutki. Etap nie zamyka anonimizacji ani odtwarzania historii.

Nie ma transakcji SQL/brokera ani odtwarzania zdarzeń w workerze. Po błędzie
pozostaje poprzednia migawka i jej czas; liczniki zachowują usunięcia
potwierdzone przez wcześniejsze odpowiedzi. Gdy Redis wykonał skrypt, ale
odpowiedź zginęła, klient nie zna liczby tych usunięć. Counter obejmuje
potwierdzone odpowiedzi; długość streamu i następna migawka pokazują stan.
Log ostrzeżenia podaje klasę wyjątku, bez payloadu, nazw grup i komunikatu błędu.

## Konfiguracja

| Zmienna | Domyślnie |
| --- | --- |
| `BILLING_INVOICE_STREAM_RETENTION_MODE` | `observe` — off/observe/trim |
| `BILLING_INVOICE_STREAM_RETENTION_AGE` | `30d` |
| `BILLING_INVOICE_STREAM_RETENTION_BATCH_SIZE` | `500` |
| `BILLING_INVOICE_STREAM_RETENTION_MAX_BATCHES` | `4` |
| `BILLING_INVOICE_STREAM_RETENTION_INTERVAL_MS` | `600000` |
| `BILLING_INVOICE_STREAM_RETENTION_INITIAL_DELAY_MS` | `60000` |
| `BILLING_INVOICE_STREAM_RETENTION_REQUIRED_GROUPS` | pusta lista |
| `BILLING_INVOICE_STREAM_RETENTION_ALLOW_NO_GROUPS` | `false` |

Osiem niesekretnych parametrów jest w aplikacji, Compose i ConfigMap.
Walidacja: wiek 1–36500 dni, partia 1–1000, liczba partii 1–20, odstęp
minimum 1000 ms i opóźnienie minimum 0. CSV zawiera do 32 unikalnych nazw
po 1–128 znaków A–Z/a–z/0–9/`.`/`_`/`:`/`-`; bez pustych elementów.
Niepoprawne ustawienia zatrzymują start. Profil test wyłącza moduł.
Polityka jest niezależna od kompaktowania outboxu i diagnostyki inboxu Billing.
Bez nowych sekretów, migracji SQL i zmian wspólnego modułu platform-security.
Nazwa streamu musi być niepusta, mieć do 200 znaków i nie zawierać znaków kontrolnych.

## Metryki

Istniejący Prometheus Billing publikuje prefix `billing_invoice_stream_retention_`:

| Sufiks | Znaczenie |
| --- | --- |
| `enabled` / `deletion_enabled` | Włączenie obserwacji/trim oraz samego trim |
| `age_seconds` | Skonfigurowany próg wieku |
| `deleted_total` | Usunięcia z potwierdzonych odpowiedzi |
| `runs_total{outcome}` | Przebiegi success/failed |
| `length` | XLEN ostatniej migawki |
| `oldest_stored_age_seconds` | Wiek pierwszego zachowanego ID względem zegara Billing |
| `pending` | Czy pozostał wpis starszy od progu wieku |
| `eligible` | Czy pozostał wpis możliwy do usunięcia według wszystkich granic |
| `blocked_code` | Przyczyna ochrony/blokady, poniżej |
| `group_count` | Liczba istniejących grup |
| `last_success_timestamp_seconds` | Czas ostatniej pełnej migawki |
| `last_deleted` | Potwierdzone usunięcia ostatniego pełnego przebiegu |

Kod 0 oznacza gotowość/brak blokady; 1 brak streamu; 2 brak wymaganej grupy;
3 brak jawnej polityki odbiorców; 4 ponad 32 grupy; 5 stare dane chronione
granicą konsumenta/pending. Błędny typ klucza jest błędem przebiegu i nie
jest modyfikowany. Nieznana migawka ma -1. Czternaście metryk ma wyłącznie
dwa stałe wyniki jako etykiety; scrape nie odpytuje Redis.

## Odbiór

<!-- A43_14_RESULTS -->
- **181 testów jednostkowych Java** (108 infrastruktury i 73 Billing),
  **15 PostgreSQL i 12 Redis — 208 łącznie, PASS**, bez pominiętych.
  Nowych: 25 (13 jednostkowych i 12 Redis). Wcześniejsze 15 PostgreSQL
  A43.5/A43.11 jest już w wyniku — nie sumować ponownie.
- Rzeczywisty Redis **7.4.9** potwierdził brak tworzenia klucza/grup,
  readonly observe, wymaganą politykę, brakujące i dodatkowe grupy,
  granicę wieku, pending/ACK/unread oraz ochronę przy ponad 32 grupach.
- Duże sąsiednie sekwencje >2^53 i przyszły unsigned ID zachowały precyzję.
  Budżet jednego usunięcia działał przy powiększonych makrowęzłach.
  Opróżniony stream zachował klucz i ostatni ID; starszy ID nie mógł być użyty ponownie.
- Równoległe kolektory i rzeczywisty InvoiceStreamPublisher zachowały
  świeże zdarzenie. Równoległe XREADGROUP/ACK nie utraciły unread/pending.
  Błędny typ pozostał nietknięty, a worker odzyskał działanie po usunięciu
  błędu fixture. Potwierdzone usunięcia i poprzednia migawka przetrwały błąd późniejszej partii.
- Pierwszy odbiór wykrył dwa błędy: wyłączny zakres poniżej `0-0` jest
  niepoprawny. Poprawka jawnie pomija usuwanie na zerowej granicy. Wszystkie
  13 jednostkowych i 12 Redis przeszły ponownie, następnie pełna regresja
  powyżej — PASS. Tych powtórzeń nie sumować do 208.
- Dwie własne bazy wykonały V1–V11/JPA; wcześniejsze retencje SQL i odbiór
  faktur, korekt, PDF/eksportu — PASS. **12 Kubernetes**, spójność ośmiu
  parametrów w trzech formatach, bramki Maven/korelacji/odporności
  i kontrola 15 JAR-ów — PASS.

Raport: `tmp/audit-a43-14/report.json`. Pełna regresja końcowa:
`tmp/a43-14-maven-verify-final.log`; pierwszy odbiór:
`tmp/a43-14-maven-verify.log`; ponowny odbiór Redis:
`tmp/a43-14-redis-check.log`.
<!-- /A43_14_RESULTS -->

```text
./mvnw -pl billing-service -am -Pintegration verify
```

Wykorzystano istniejące testowe zależności JPA/Testcontainers i profil Failsafe.
Standardowe CI obejmuje nowe przypadki; bez uruchamiania zdalnego CI.
Redis testowy jest własnym kontenerem z dynamicznym portem. FLUSHDB jest
wykonywany tylko przez klienta utworzonego z tego kontenera, nigdy na
konfiguracji brokera aplikacji. Bez rzeczywistych zdarzeń, dokumentów,
restartu usług i wdrożenia dev/prod. A05, całe A41 i zmiany samych zdjęć
pozostają odłożone.
