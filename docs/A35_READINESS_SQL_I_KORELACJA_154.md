# A35 — readiness SQL i korelacja HTTP/Redis, pakiet 154

Stan: **02.11.2026 · Audit_0.1 · odbiór lokalny częściowego A35**.
Pełne A35 i cały audyt pozostają otwarte. Punkt wznowienia:
[przekazanie audytu](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md).

## Wdrożone i sprawdzone

- [x] Readiness obejmuje `readinessState,db` w 14 usługach z PostgreSQL.
  Gateway obejmuje `readinessState`. Liveness wszystkich 15 usług obejmuje
  wyłącznie `livenessState`. Profile dziedziczą grupy z konfiguracji bazowej.
- [x] Nowa bramka `readiness-check.py` w CI kontroluje 59 publicznych konfiguracji,
  role usług i 45 ścieżek sond Kubernetes; odrzuca wyłączenie SQL, ujawnianie
  szczegółów health, zmianę DOWN na HTTP 200 i nieprzejrzane nadpisania profili.
- [x] Wspólny `CorrelationIdContext.Scope` przywraca poprzedni bezpieczny MDC
  po sukcesie lub błędzie, także przy zagnieżdżeniu; wymaga zamykania na tym
  samym wątku w odwrotnej kolejności. Brak własnego ID tworzy nowy identyfikator.
- [x] `RedisEventStreams` otwiera zakres dla każdego nowego i przejętego przez
  XAUTOCLAIM komunikatu. Obsługa i dostarczenie Notification zachowują zewnętrzny
  kontekst po zamknięciu własnego zakresu.
- [x] Billing, Chat i Returns mają brakujące adaptery wspólnego filtra HTTP.
  Bramka porównuje pokrycie z inwentarzem komponentów: 15/15 usług JVM.
  Kontrola JAR wymaga też obecności adaptera w każdej aplikacji.

Zachowano istniejące formaty `X-Correlation-Id` i pola `correlationId`, payloady,
zasady ACK, pending, deduplikacji i retencji. ID korelacji nie daje uprawnień.
Nie wprowadzono W3C traceId/spanId, eksportera ani pełnego distributed tracing.

## Odbiór i granice dowodów

| Zakres | Rzeczywiście wykonany odbiór |
|---|---|
| SQL readiness | 60 przypadków Java: 59 kombinacji bazowych/profilowych i jeden scenariusz stanów dostępności; rzeczywisty Boot 4.1.0, HTTP Actuator i własny PostgreSQL 16 |
| Utrata SQL | Wstrzymanie własnego kontenera: SQL readiness 503, liveness 200, powrót readiness 200; Gateway pozostaje zależny od własnego stanu |
| Health i zależności | Dokładne ciała UP/DOWN bez szczegółów; niedostępne fikstury Redis/SMTP nie zmieniają wybranych grup; REFUSING_TRAFFIC i BROKEN nadal działają |
| Korelacja | 18 regresji HTTP, 6 nowych testów zakresów, 2 nowe testy prawdziwego Redis 7; sprawdzono nowe/przejęte wiadomości, błąd, niezmieniony payload i zachowany pending |
| Notification | 15 przypadków: 2 inbox, 5 konsumenta, 4 dostarczania, 4 granicy transakcji; w tym 4 nowe sprawdzenia przywracania MDC po sukcesie/błędzie; część integracyjna używa H2 |
| Adaptery HTTP | 3 nowe testy rejestracji komponentów w rzeczywistych wąskich kontekstach Spring; bez uruchamiania pełnych trzech aplikacji |
| Python | 116 regresji kontraktów + 8 readiness + 6 układu korelacji + 8 Maven + 7 CI = 145 metod PASS |
| Artefakty | Lokalny build bieżących JAR i kontrola 15 adapterów; wynik w `tmp/a35-readiness-154/packaged-correlation.log` |

Łącznie **104 unikalne testy Java, w tym 75 nowych**, oraz **145 metod Python**.
Powtórzone uruchomienia nie zwiększają tych liczb. Końcowe zaakceptowane zestawy
nie mają błędów ani pominięć. Początkowy test Billing skanował też konfigurację
metryk wymagającą JDBC; ograniczono kontekst testu adaptera do komponentów,
bez zmiany kodu produkcyjnego metryk lub dodawania fikcyjnego zdrowia SQL.

Test readiness ładuje wyłącznie publiczne właściwości health do izolowanej
aplikacji testowej. Nie oznacza uruchomienia wszystkich 15 pełnych usług ani
odbioru dev/test. Krótkie limity Hikari/JDBC dotyczą tylko fikstury; produkcyjne
czasy odpowiedzi zależą od obecnych limitów puli i sond, nie zostały skrócone.
StartupProbe nadal wskazuje readiness: długotrwały brak SQL podczas startu może
wyczerpać istniejący budżet startowy. Pełny przegląd budżetów i zależności Redis,
SMTP oraz HTTP według ról usług pozostaje otwarty.

## A43 i bezpieczeństwo danych

Bieżąca rewizja techniczna to `a43.40-2026-11-02.7`. Odświeżono odciski pięciu
przypiętych konfiguracji YAML oraz dwóch plików obsługi Redis objętych przeglądem.
Nie zmieniono odpowiedzi użytkownika, okresów, ochrony danych ani zgody na aktywację.
Bramki lifecycle, klasyfikacji, commerce i kandydata polityki przechodzą.
`--require-ready` nadal zwraca **NOT_READY, kod 2**; release P1 nadal blokuje A05.

Schemat pozostaje: 145 tabel, 1307 kolumn, 232 migracje. Pełnego drillu restore
nie powtarzano; historyczny dowód pakietu 151 zachowuje swoje granice.
Nie wykonywano operacji na danych dev/test, nie odczytywano sekretów i nie
uruchamiano czyszczenia. 19 plików graficznych i końcowy załącznik decyzji
użytkownika pozostają bez zmian.

Raport, XML, logi i skróty: `tmp/a35-readiness-154/report.json`, `java-xml/`,
`source-hashes.json`, `delivery-verification.json` w tym samym katalogu.
Brak artefaktów po przeniesieniu repozytorium oznacza konieczność ponownej
weryfikacji, nie nowy wynik PASS.

## Od czego zacząć dalej

Następny niezależny zakres: **A35 — odczytowe metryki Redis pending/lag wymaganych
odbiorców, z jawnym stanem nieznanym po błędzie lub braku grupy**. Nie wykonywać
trim, replay, tworzenia grup ani zmiany retencji. Najpierw sprawdzić istniejący
kod i pokrycie, potem dodać brakujące metryki oraz testy rzeczywistego Redis.

Osobno pozostają pełny tracing, zgodność skutków między bazami, refund pending,
pozostałe role readiness, monitoring Kubernetes i docelowy odbiór alarmów.
Wybór odbiorników, SLO i inne wymagane wartości/decyzje użytkownika odkładać.
A05, A41, same zdjęcia i otwarte decyzje A43 nadal odłożone.

Podstawa rozdziału sond: [oficjalna dokumentacja Spring Boot — Actuator probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes).
Dokumentacja pod opisanym adresem może przechodzić na nowsze wydania; wykonany
test używa wersji projektu 4.1.0.
