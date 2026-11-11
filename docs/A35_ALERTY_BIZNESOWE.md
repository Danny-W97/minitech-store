# A35.1 — alerty biznesowe (26.10.2026)

A35.1 dodaje lokalny tor metryki → Prometheus → Alertmanager oraz reguły
wykrywające utrwalone problemy biznesowe. Pełne A35 pozostaje częściowe.
Nie wdrożono tego pakietu na dev/prod. A05 i zmiany samych zdjęć pozostają odłożone.

## Aktualizacja 02.11.2026 — pakiet 159

[Spójność między bazami danych (Cross-Database Consistency) i Watchdog monitoringu](A35_SPOJNOSC_BAZ_I_WATCHDOG_159.md): Wdrożono standardowy alert Prometheus `Watchdog` (`DeadMansSwitch`, `expr: vector(1)`), stale aktywny w celu certyfikacji działania potoku powiadomień.
Zaimplementowano audytor spójności rozproszonej `scripts/qa/cross-database-consistency-check.py` badający inwarianty zamówień opłaconych, anulowanych, rekordów osieroconych (inversion check) oraz wyrównania kolejki reconciliacji w 6 bazach PostgreSQL.
W `order-service` dodano testy metryk biznesowych i reconciliacji `OrderBusinessMetricsAndConsistencyTest` (4/4 PASS) weryfikujące, że niespójności cyklu życia zamówień podnoszą stan `order_reconciliation` i wyzwalają alarm `BusinessDeadLetter`.
Zaktualizowano `scripts/qa/monitoring-check.py` (13 reguł alertów promtool + 9 zapytań PromQL dashboardów PASS).
Następne: Pakiet 160 — automatyzacja skutków domenowych zatwierdzonych zwrotów (A16) oraz domknięcie protokołu D02 (A43.40/41).

## Aktualizacja 02.11.2026 — pakiet 158

[Wdrożenie progów SLO, reguły alertów i rozstrzygnięcie decyzji audytowych](A35_SLO_ALERTY_I_ROZSTRZYGNIECIE_DECYZJI_158.md): Po formalnym zatwierdzeniu przez użytkownika rekomendacji decyzyjnych wdrożono reguły alertów SLO w Prometheus (`BusinessQueueSloBreach` dla 60 min przekroczenia wieku zwrotów/faktur oraz `RedisStreamLagHigh` dla lagu > 100 wiadomości w strumieniach Redis).
Zaktualizowano runbooki oraz scenariusze promtool (12 reguł alarmów + 9 zapytań PromQL dashboardów PASS).
Zatwierdzono i odnotowano parametry dla D01 (5 lat retencji finansowej), D09 (dedykowany wolumen append-only dla rejestru), D14 (lokalny Mailpit/AI), D17 (tryb audytu safe), A05 (odłożone ze symulatorem), A40 (kanoniczny URL), A41 (architektura 14 serwisów) i zdjęć.
Następne: audyt zgodności między bazami, finalizacja watchdog/readiness i przygotowanie do odbioru końcowego A43.41.

## Aktualizacja 02.11.2026 — pakiet 157

[Kontekst śledzenia W3C Trace Context (traceparent) i propagacja HTTP](A35_W3C_TRACE_CONTEXT_157.md): Wdrożono standard W3C Trace Context (`traceparent`) wraz z kluczami MDC SLF4J (`traceId`, `spanId`, `traceparent`) w `platform-security` (`CorrelationIdContext`, `CorrelationIdFilter`, `CorrelationIdClientInterceptor`) oraz w `api-gateway` (`GatewayProxy`, `ZipExportProxy`, `CorrelationIdFilter`).
Zachowano wsteczną kompatybilność z nagłówkiem `X-Correlation-Id`. Zapewniono automatyczne generowanie unikalnych `traceId` (32 hex) i `spanId` (16 hex) oraz tworzenie segmentów podrzędnych (`childTraceparent()`) przy wywołaniach wychodzących HTTP do serwisów downstream.
285 testów Java w `platform-security` (4 nowe), 35 testów Java w `api-gateway` (2 nowe), 109 testów Java w `payment-service` oraz 21 scenariuszy promtool PASS.
Następne: A35 — zgodność między bazami, reszta readiness/SLO, Kubernetes, watchdog i odbiór docelowy; wartości SLO i odbiorniki dyżurnego odłożyć na formularz użytkownika.

## Aktualizacja 02.11.2026 — pakiet 156

[Pokrycie procesu zwrotów i metryki refund pending/review](A35_REFUND_PENDING_156.md): Uzupełniono brakujące pokrycie stanów procesu zwrotów (`PaymentRefund`) w `payment-service` o metryki `payment_refund` (zwroty w toku) oraz `payment_refund_review` (zwroty wymagające interwencji operatora). Łącznie 6 kolejek w `payment-service`.
Zaktualizowano reguły alertów w Prometheus (`BusinessQueueStalled`, `BusinessDeadLetter`, `BusinessMetricsMissing != 6`) oraz scenariusze promtool.
109 Java w `payment-service` (4 nowe testy weryfikujące cykl życia i Micrometer), 281 Java w `platform-security`, 116 metod Python QA oraz 21 scenariuszy promtool PASS.
Następne: A35 — traceId/spanId, zgodność między bazami, reszta readiness/SLO, Kubernetes, watchdog i odbiór docelowy; wartości SLO i odbiorniki dyżurnego odłożyć na później.

## Aktualizacja 02.11.2026 — pakiet 155

[Metryki Redis pending/lag](A35_REDIS_PENDING_LAG_155.md): Odczytowe metryki pending/lag grup płatności Billing i Notification, jawne stany nieznane, świeżość odczytu i trzy panele Grafany. Scrape nie wykonuje poleceń Redis; etykiety nie zawierają nazw skonfigurowanych kluczy/grup, konsumentów ani danych wiadomości.
64 unikalne Java (19 nowych) + 116 Python + 21 scenariuszy promtool PASS. Są to wskaźniki diagnostyczne, bez nowych progów alarmu.
Następne: A35 — przegląd pokrycia refund pending i uzupełnienie brakujących metryk na istniejących stanach procesu; wartości SLO i odbiorniki wymagające decyzji użytkownika odłożyć.

## Aktualizacja 02.11.2026 — pakiet 154

[Readiness SQL i korelacja HTTP/Redis](A35_READINESS_SQL_I_KORELACJA_154.md): Readiness SQL dla 14 usług, Gateway według własnego stanu; liveness bez zewnętrznych zależności. Kontekst korelacji nowych i przejętych wiadomości Redis przywraca poprzedni MDC. Uzupełniono filtry HTTP w Billing, Chat i Returns: pokrycie 15/15 usług JVM.
104 unikalne testy Java (75 nowych) + 145 metod Python PASS, bez błędów i pominięć w zaakceptowanych zestawach. Szczegóły fikstur i granic dowodu zawiera dokument pakietu.
To częściowy odbiór A35; correlation ID nie zastępuje traceId/spanId.
Następne: A35 — odczytowe metryki Redis pending/lag wymaganych odbiorców i obsługa nieznanego stanu; bez trim, replay i zmiany retencji.

## Uruchomienie i odbiór

`bash scripts/control-center/control-center.sh monitoring-check` sprawdza konfigurację,
zgodność 15 targetów JVM z `deploy/components.json` oraz rzeczywiste reguły PromQL
w `promtool`. Wymaga Docker i Python z zależnościami `scripts/qa/requirements.txt`.
`monitoring-up` wykonuje tę kontrolę przed uruchomieniem stosu.
`monitoring-alerts` pokazuje aktywne alarmy; `monitoring-status` sprawdza również
Alertmanager. Interfejsy lokalne: Prometheus `127.0.0.1:19090`, Grafana
`127.0.0.1:13000`, Alertmanager `127.0.0.1:19093`.

Domyślny odbiornik `local-console` przechowuje alarmy w interfejsie Alertmanagera;
nie wysyła wiadomości. Nie jest to skonfigurowane powiadamianie dyżurnego.
Test `python3 scripts/qa/monitoring-e2e.py` tworzy własne kontenery, sieć, bazy,
porty i odbiornik webhook; sprawdza otrzymanie alarmu i jego rozwiązanie.
Nie używa profili, skarbców ani baz użytkownika. CI egzekwuje test reguł i toru.

## Metryki i proponowane cele

Order, Payment, Billing, Notification i Chat publikują `platform_business_queue_count`,
`platform_business_queue_oldest_age_seconds`, `platform_business_collector_up`
oraz `platform_business_collector_last_success_timestamp_seconds`, z ograniczoną
etykietą `queue`. Bez numerów zamówień, adresów, e-maili i treści zdarzeń.
Każda replika mierzy ten sam stan bazy: przy agregacji używać **max**, nie sumy.

Kolektor co 30 s odczytuje COUNT/MIN z lokalnej bazy; każde zapytanie ma limit 3 s.
Pobranie połączenia podlega limitowi puli Hikari. Scrape czyta wyłącznie pamięć.
Nieudany odczyt zachowuje poprzedni wynik i ustawia stan kolektora na 0;
nieaktualny wynik po 120 s również generuje alarm. Wiek pochodzi z `created_at`,
więc retry nie odmładza zaległości. Zwykłe oczekiwanie klienta na płatność
(`WAITING_FOR_PAYMENT`, zadania `WAITING`) nie jest zaległą sagą.

Początkowe progi operacyjne: saga/outbox/retry do 5 min (alarm po dodatkowych 2 min),
żądana faktura po skutecznej płatności do 10 min (alarm po 2 min), DLQ lub ręczne
uzgodnienie po 1 min. Pula pełna z oczekującymi połączeniami przez 2 min jest krytyczna.
HTTP 5xx >5% przez 5 min wymaga co najmniej 20 żądań w oknie 5 min.
To propozycja SLI/progów; formalne SLO, okres rozliczenia i budżet błędów wymagają
uzgodnienia oraz pomiarów ruchu docelowego.

## Instrukcje reakcji

<a id="businessqueuestalled"></a>
### BusinessQueueStalled
Zidentyfikować instancję i `queue`. Sprawdzić dostępność zależności, broker,
logi z correlation ID i stan retry. Dla `order_saga` sprawdzić rezerwację i kompensację;
dla outbox — połączenie z Redis; dla powiadomień — SMTP. Przywrócić zależność,
obserwować spadek wieku/liczby. Nie usuwać rekordów i nie omijać blokad idempotencji.
Dla `chat_notification` sprawdzić HTTP i uwierzytelnienie Chat → Notification.
Kolejka mierzy przekazanie zadania; przyjęte zadanie nie oznacza doręczenia e-maila.
Dla `payment_refund` sprawdzić stan komunikacji z operatorem płatności (Stripe) lub webhooków oraz dokończenie zadań biznesowych (Order/Inventory). Zweryfikować `payment_refunds` pod kątem zawieszonych rekordów `REQUESTED`/`PENDING`.

<a id="businessqueuebacklog"></a>
### BusinessQueueBacklog
Ponad 1000 rekordów przez 5 min. Ustalić tempo napływu/odpływu i przyczynę opóźnień.
Nie zwiększać współbieżności bez sprawdzenia limitów bazy i usług zewnętrznych.

<a id="businessdeadletter"></a>
### BusinessDeadLetter
Odczytać `dlq-list` lub `payment-follow-ups` w CC. Sprawdzić kod błędu i rzeczywisty
stan skutku przed pojedynczym replay przez istniejące polecenia operatora.
`order_reconciliation` wymaga ręcznego uzgodnienia płatności/rezerwacji;
nie zmieniać statusu masowo w SQL. Po naprawie alarm powinien się rozwiązać.
`chat_notification_dlq` obsługiwać przez `chat-history <id>` oraz
`chat-notification-retry <id> <entry-id>`. Przyjęty przez Notification błąd SMTP
obsługuje jego własne retry/DLQ; nie ponawiać go jako nowego zadania czatu.
`payment_refund_review` sygnalizuje zwroty zatrzymane w stanie `REVIEW_REQUIRED` (konflikt tożsamości refundu, przekroczenie 20h okna niejednoznaczności lub konflikt blokady zamówienia). Wymaga zbadania sprawy w `payment_refunds` i podjęcia decyzji przez operatora.

<a id="paidwithoutinvoice"></a>
### PaidWithoutInvoice
Obejmuje opłacone zamówienie z istniejącym żądaniem `INVOICE_ISSUE`, które nie ma
statusu `SUCCEEDED`; nie obejmuje zamówień bez żądania faktury. Sprawdzić Billing,
zadanie i ewentualną już wystawioną fakturę przed ponowieniem. Wiek od chwili
zapisania wyniku płatności (dla starszych rekordów `updated_at`). Ten sygnał
nie jest audytem zgodności między bazami: nie wykrywa usunięcia całego zadania
ani nie potwierdza, że wynik SUCCEEDED wskazuje istniejący dokument.

<a id="businesscollectorunavailable"></a>
### BusinessCollectorUnavailable
Wyniki kolejki są nieznane lub nieaktualne. Sprawdzić bazę, migracje, pulę Hikari
i zasoby usługi. Nie interpretować zerowego count jako potwierdzenia poprawności.

<a id="businessmetricsmissing"></a>
### BusinessMetricsMissing
Scrape działa, ale brakuje metryk biznesowych. Sprawdzić wersję JAR, rejestrację
kolektora i etykiety targetów. Nie wyciszać alarmu przez usunięcie targetu.

<a id="platformtargetdown"></a>
### PlatformTargetDown
Sprawdzić proces, readiness, DNS, sieć i endpoint `/actuator/prometheus`.
Alarm dotyczy także Prometheusa/Alertmanagera, gdy monitorowane są przez ten serwer.
Awarii samego Prometheusa nie wykryje on sam — potrzebny niezależny watchdog.

<a id="databasepoolexhausted"></a>
### DatabasePoolExhausted
Sprawdzić długie transakcje, blokady SQL, liczbę połączeń i budżety HTTP.
Usunąć przyczynę blokady; nie podnosić limitu puli bez weryfikacji pojemności bazy.

<a id="platformhttperrors"></a>
### PlatformHttpErrors
Powiązać usługę i okno błędów z logami/correlation ID, zmianą wydania oraz zależnościami.
Zweryfikować poprawność checkoutu bez ponawiania obciążenia karty klienta.

<a id="alertdeliveryfailed"></a>
### AlertDeliveryFailed
Sprawdzić łączność Prometheus → Alertmanager oraz skonfigurowany odbiornik.
Sam aktywny alarm w UI nie dowodzi odebrania wiadomości przez dyżurnego.


<a id="businessqueueslobreach"></a>
### BusinessQueueSloBreach
Wiek najstarszego elementu w kolejce zwrotów (`payment_refund`) lub faktur po opłaceniu (`paid_without_invoice`) przekroczył krytyczny próg SLO 60 minut.
Sprawdzić łączność z bramką płatności oraz ewentualne blokady transakcyjne w `payment-service` i `billing-service`. Wymaga natychmiastowej analizy operatora.

<a id="redisstreamlaghigh"></a>
### RedisStreamLagHigh
Liczba nieprzetworzonych wiadomości (lag) w strumieniu Redis przekroczyła bezpieczny próg 100 wiadomości.
Sprawdzić stan i obciążenie konsumentów `billing-service` oraz `notification-service`, a także ewentualne błędy deserializacji lub spowolnienia w przetwarzaniu zdarzeń.

<a id="watchdog"></a>
### Watchdog
Alert typu Dead Man's Switch / Heartbeat, który jest stale w stanie `firing` (`expr: vector(1)`).
Służy do ciągłego potwierdzania, że cały potok powiadamiania (Prometheus → Alertmanager → kanały zewnętrzne) jest sprawny i zdolny do wysyłania powiadomień.
Zewnętrzne systemy monitorowania (np. Dead Man's Snitch, Healthchecks.io, PagerDuty Heartbeat) oczekują cyklicznego sygnału; jeśli sygnał z Watchdoga nie dotrze w określonym oknie czasowym (np. 15 minut), zewnętrzny system podnosi alarm o awarii całego węzła lub procesu Prometheusa/Alertmanagera.
W środowisku lokalnym stan `firing` w interfejsie Alertmanagera potwierdza poprawne ładowanie i ewaluację reguł. Runbook nie wymaga interwencji operatora, dopóki alert jest aktywny. Jeśli alert zniknie, oznacza to zatrzymanie silnika reguł Prometheusa.

## Pozostało w pełnym A35

- Konfiguracja własnego odbiornika dyżurnego oraz odbiór alarmu i rozwiązania
  w środowisku docelowym; niezależny watchdog dla całego stosu monitoringu.
- Osobny overlay monitoringu Kubernetes, discovery, TLS/uprawnienia scrape i wąskie
  NetworkPolicy. Domyślne A34 blokuje dodatkowy ingress; ten pakiet nie otwiera sieci.
- Rozproszone traceId/spanId na całej drodze HTTP/Redis, bez danych osobowych,
  retencja i polityka próbkowania. Correlation ID nie zastępuje pełnego tracingu.
- Audyt zgodności opłaconych zamówień z faktyczną realizacją w innych bazach,
  pozostałe pomiary Redis/DLQ, refund pending oraz pozostałe role i budżety readiness.
  Pending/lag dwóch grup płatności odebrano lokalnie w 155.
  SQL readiness i pokrycie korelacji HTTP/Redis odebrano w pakiecie 154.
- Uzgodnione SLO i próby awarii na docelowej infrastrukturze.

## Wyniki lokalne

- 290 testów Java wspólnego modułu i czterech usług, w tym 3 nowe testy kolektora:
  `tmp/a35-regression.log` — PASS.
- 114 testów Python: `tmp/a35-python-tests.log` — PASS.
- 12 scenariuszy promtool: aktywacja/rozwiązanie 10 reguł, nieaktualność odczytu
  i częściowa utrata metryk; walidacja konfiguracji Prometheus/Alertmanager oraz
  15 targetów JVM — PASS. Faktyczne polecenie CC: `tmp/a35-cc-check.log`.
- Rzeczywiste Order/Payment/Billing/Notification i migracje na własnym PostgreSQL;
  dziewięć kolektorów, stare zadania, oczekiwanie na płatność, żądanie faktury,
  awaria SQL i powrót — PASS. Oryginalna reguła `for: 2m` doprowadziła do
  otrzymania webhooka `firing`, potem `resolved`, z linkiem do runbooka.
  Raport: `tmp/a35-monitoring-7bab19fa/report.json`, zdarzenia:
  `tmp/a35-monitoring-7bab19fa/webhook-events.json`.

W teście toru HTTP niezmieniony tekst metryk rzeczywistej usługi Order jest
przekazywany do fixture w prywatnej sieci kontenerów, aby portów Java nie wiązać
poza loopback hosta. Walidacja konfiguracji docelowego Compose jest osobna;
test nie potwierdza produkcyjnego discovery, CNI ani dostarczenia do dyżurnego.

Źródła techniczne: [reguły alertów](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/),
[testowanie reguł](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/),
[konfiguracja Alertmanagera](https://prometheus.io/docs/alerting/latest/configuration/).
