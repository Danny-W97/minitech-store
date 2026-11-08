# A35 — metryki Redis pending/lag, pakiet 155

Stan: **02.11.2026 · Audit_0.1 · lokalny odbiór częściowego A35**.
[Dokument przekazania](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md) jest bieżącym punktem wznowienia.

## Wykonane

- [x] Wspólny `RedisStreamMetrics`: jeden odczyt `XINFO GROUPS` dla skonfigurowanego
  streamu co 30 s na instancję, z wyborem grupy używanej przez jej konsumenta.
  Kolektor korzysta z istniejących limitów połączenia/polecenia Redis.
- [x] Rejestracja w Billing i Notification używa tych samych właściwości
  `messaging.payment-stream` i `messaging.consumer.group` co konsumenci.
  Wyłączenie `messaging.consumer.enabled` usuwa też kolektor; brak serii nie jest zerem.
- [x] Sześć wskaźników w pamięci procesu, niezależne od SQL i sond dostępności.
  Scrape odczytuje tylko zapisany wynik; nie łączy się z Redis.
- [x] Trzy panele Grafany: pending, lag i stan odczytu per instancja.
  Wykresy wybierają maksimum z replik zamiast sumowania wspólnej kolejki.
- [x] Zapytania wymagają poprawnego odczytu, istniejącej grupy i wieku 0–120 s.
  Lag wymaga dodatkowo znanej wartości. Brak danych nie jest zastępowany zerem.
- [x] Izolacja MDC w czterech istniejących klasach testowych Notification:
  przygotowanie i sprzątanie kontekstu między testami, bez osłabienia asercji
  przywracania kontekstu i bez zmiany produkcyjnej korelacji.

Domyślna częstotliwość jest właściwością `observability.streams.fixed-delay-ms`
(30000 ms, także pierwsze opóźnienie). Limit świeżości panelu to techniczny
warunek ważności obserwacji, nie nowe SLO ani próg alarmu biznesowego.
Etykiety mają stałe wartości `stream=payments` i `recipient=billing|notification`.
Nie eksportujemy skonfigurowanych nazw streamów/grup, nazw konsumentów,
identyfikatorów rekordów, treści, e-maili ani identyfikatorów klientów.

## Znaczenie metryk

Nazwy poniżej są nazwami eksportowanymi w formacie Prometheus.

| Metryka `platform_redis_stream_…` | Znaczenie |
|---|---|
| `pending` | Rekordy dostarczone do grupy, bez ACK; NaN przy braku grupy lub błędzie |
| `lag` | Wartość zaległości niedostarczonych raportowana przez Redis; NaN, jeżeli Redis jej nie zna albo odczyt jest niedostępny |
| `lag_known` | 1 tylko dla dostępnej, poprawnej wartości lag; 0 także przed pierwszym pomiarem |
| `group_present` | 1: grupa istnieje; 0: poprawny odczyt bez tej grupy; NaN: brak poprawnego odczytu |
| `collector_up` | 1: poprawnie odczytano metadane; **nie oznacza** braku zaległości ani obecności grupy |
| `collector_last_success_timestamp_seconds` | Czas ostatniej poprawnej inspekcji; 0 przed pierwszą, bez odnowienia po błędzie |

Pending i lag opisują różne etapy. Zero jest potwierdzeniem pustego stanu tylko
przy poprawnym, świeżym odczycie istniejącej grupy; lag musi być dodatkowo znany.
Po awarii odrzucamy liczniki przez NaN, zachowując czas ostatniej dobrej inspekcji.
Poprawna inspekcja bez grupy odświeża czas, lecz nadal pozostawia liczniki nieznane.
Po restarcie procesu stan jest nieznany do pierwszego poprawnego pomiaru.

Redis może zwrócić NULL dla lag, m.in. przy niejednoznacznym liczniku odczytu.
Nie wyliczamy wtedy zastępczej wartości z długości streamu lub pending.
Opis polecenia i ograniczeń: [Redis — XINFO GROUPS](https://redis.io/docs/latest/commands/xinfo-groups/).

## Odbiór lokalny

**64 unikalne testy Java, w tym 19 nowych; 116 metod Python; 21 scenariuszy promtool — PASS.**
Powtórki nie zwiększają liczb. Końcowe zaakceptowane XML nie zawierają błędów ani pominięć.

- 8 nowych przypadków jednostkowych kolektora: stan początkowy, brak grupy,
  nieznany lag, poprawne zero, oddzielne liczniki, błąd i powrót, niepoprawne
  metadane oraz brak operacji Redis podczas odczytu wskaźników.
- 5 nowych testów rzeczywistego Redis 7: dwie niezależne grupy, PEL i ACK,
  lag NULL i jego powrót po normalnej konsumpcji, brak tworzenia streamów/grup,
  rzeczywiste wstrzymanie kontenera oraz konto ACL dopuszczające tylko metadane
  grup i polecenia połączenia. Odczyt payloadu i ACK przez to konto są odrzucane.
  Wielokrotne pomiary zachowują identyczny DUMP badanego streamu.
- 6 nowych przypadków Spring Billing/Notification: właściwości konsumenta,
  flaga wyłączenia i domyślne włączenie. Użyto prawdziwego rejestru Prometheus:
  sprawdzono format scrape, NaN, stałe etykiety i brak operacji Redis przy scrape.
- 45 regresji korelacji HTTP/Redis, kolektora SQL, obsługi wiadomości,
  idempotencji i granic transakcji. Odbiór SQL w tym pakiecie używa H2;
  nie powtarzano 60 testów PostgreSQL readiness z pakietu 154.
- 116 regresji kontraktów A43 na odrębnej kopii publicznych źródeł w Linux /tmp.
- Promtool: 12 dotychczasowych scenariuszy alertów i 9 nowych scenariuszy
  rzeczywistych zapytań z JSON dashboardu. Sprawdzono m.in. brak serii, błąd,
  brak grupy, stary/przyszły czas, nieznany lag, poprawne zero i wiele replik.
  Walidacja Prometheus/Alertmanager oraz 15 zadeklarowanych targetów także PASS.
- Zbudowano JAR-y Billing i Notification ze wspólną biblioteką; kontrola bajtów
  potwierdza bieżące klasy kolektora i konfiguracji. Nie jest to odbiór działających
  15 pełnych usług ani test HTTP Actuator nowych metryk na dev/test.

Początkowe nieudane przebiegi zachowano w katalogach `diagnostic-*`.
Poprawiono zagnieżdżone stubowanie Mockito, zamykanie rejestru w fiksturze,
rozróżnienie callbacków Springa od operacji Redis i izolację MDC starszych testów.
Nie wyłączono testów i nie poluzowano zasad korelacji w aplikacji.

Dowody: `tmp/a35-redis-metrics-155/report.json`, `java-xml/`, `promtool/`,
`source-hashes.json`, `packaged-artifacts.json`, `delivery-verification.json`.
Brak raportów po przeniesieniu projektu wymaga odtworzenia odpowiednich kontroli.

## Granice i dalsze prace

Metryki są przygotowane w kodzie i rejestrowane po starcie aplikacji z włączonym
konsumentem. Nie uruchamiano usług lub nowych pomiarów na rzeczywistym dev/test.
Nie potwierdzają rzeczywistych grup, postępu i dowodów środowiskowych D04/D05 A43.
Nie wykonują ACK, konsumpcji, claim, tworzenia grup, trim, replay lub odczytu payloadów.
Nie zmieniono retencji, deduplikacji, schematu i zasad działania procesów.
Nie dodano automatycznych napraw ani nowych progów alarmów biznesowych.
Nie obejmują wieku poszczególnych wpisów PEL i wszystkich pozostałych streamów/DLQ.

Rewizja `a43.40-2026-11-02.8` aktualizuje techniczne odciski trzech odczytowych
odwołań do Redis (14 → 17). Nadal 6 przestrzeni Redis, 47 mechanizmów,
145 tabel, 1307 kolumn i 232 migracje. Odpowiedzi użytkownika, okresy i zgoda
na aktywację pozostają bez zmian. Bramki lifecycle/klasyfikacji/commerce/kandydata
PASS; pełne A43 nadal **NOT_READY, kod 2**. P1 nadal 13/14; A05 blokuje release.
Nie powtarzano pełnego restore; historyczny dowód pozostaje w pakiecie 151.
Zdjęcia, publiczne application YAML i końcowy załącznik decyzji użytkownika bez zmian.

Następny niezależny krok: **przegląd refund pending i uzupełnienie brakujących
metryk na istniejących stanach procesu**. Przed zmianą sprawdzić obecne pokrycie,
aby nie dublować kolejek. Wybór SLO, odbiorników i innych wartości użytkownika
odkładać. Pełny tracing, zgodność między bazami, pozostałe role readiness,
Kubernetes i docelowy odbiór alarmów pozostają otwarte. A05/A41 i zdjęcia odłożone.
