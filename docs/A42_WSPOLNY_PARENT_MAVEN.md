# A42.1 — wspólny parent Maven (26.10.2026)

Wykonano lokalnie na `Audit_0.1`. Główny `pom.xml` jest teraz rzeczywistym
parentem i agregatorem dla 17 modułów: 15 aplikacji, biblioteki `platform-security`
i `qa-tests`. Spring Boot 4.1.0 jest wskazany w jednym miejscu.

## Jedno źródło ustawień

Root zarządza wersją Java, Tomcat, Netty, PostgreSQL, Springdoc, Resilience4j
i Testcontainers. Zależności Springdoc, Resilience4j i biblioteki platformy mają
wspólne dependencyManagement; wersje zarządzane przez Spring Boot korzystają
z jego BOM i właściwości root. Failsafe 3.5.3 jest w pluginManagement.
Moduły dziedziczą groupId/version oraz wspólne ustawienia zamiast je powielać.

Parent nie dodaje zależności aplikacji ani nie aktywuje pluginów dla wszystkich
modułów. Biblioteka pozostaje zwykłym JAR, a 15 usług zachowuje własne wykonywalne
JAR-y. Zależności lokalne, np. PDFBox w Billing i ZXing w Customer, pozostają
w odpowiednich modułach. Profile integracyjne i konfiguracja kompilacji Lombok
zachowują dotychczasowe działanie. Nie aktualizowano wersji bibliotek aplikacji.

## Budowanie i uruchamianie

Z katalogu głównego repozytorium:

```bash
python3 scripts/qa/maven-layout-check.py
./mvnw -pl chat-service -am package
./mvnw -pl platform-security -am -DskipTests install
./mvnw -f chat-service/pom.xml spring-boot:run
```

`-am` dołącza wymagane moduły, w tym parent. Przed osobnym uruchomieniem usługi
trzeba zainstalować parent oraz bibliotekę w lokalnym repozytorium Maven.
CC i przygotowanie E2E w CI wykonują już właściwą instalację. Błąd instalacji
blokuje dalsze uruchomienie. Pełne `./mvnw package` buduje cały reaktor;
`./mvnw -Pintegration verify` dodatkowo uruchamia istniejące testy Testcontainers
i wymaga Docker. Dotychczasowy Dockerfile kopiuje root i POM-y modułów, więc
nie wymaga dodania nowego pliku parenta.

Nie wdrażano usług ani migracji na dev/prod. Osobne aplikacje, JVM, bazy,
tożsamości usług i reguły dostępu pozostają zgodne z dotychczasowym podziałem.

## Kontrole

- Pełne `package`: 472 testy Java, 0 błędów i pominięć, wszystkie moduły zbudowane.
- 137 testów Python, w tym osiem mutacji modelu Maven: zły parent, powielona
  właściwość, lokalna wersja JDBC/Failsafe, brak modułu oraz przypadkowe
  dziedziczenie zależności aplikacji lub pluginu pakującego bibliotekę.
- Test powłoki potwierdza instalację przed uruchomieniem CC i przerwanie
  uruchomienia przy jej błędzie. CI wymaga obu nowych kontroli.
- Efektywne zależności i aktywne pluginy wszystkich 17 modułów są takie same
  jak przed refaktoryzacją. Pojedyncze nadpisanie właściwości PostgreSQL
  w diagnostycznym wywołaniu Maven dotarło do wszystkich 14 usług bazodanowych.
  Plików projektu ani docelowej wersji sterownika ta próba nie zmieniła.
- Skład bibliotek w 15 wykonywalnych JAR-ach jest zgodny ze stanem przed zmianą.
  Sprawdzono efektywne konfiguracje czterech profili integracyjnych, instalację
  parenta/biblioteki oraz osobne budowanie Chat. Nie uruchamiano ponownie
  pełnego profilu Testcontainers, testów przeglądarek ani obrazów Docker.

Dowody lokalne: `tmp/a42-parent-evidence.json`, `tmp/a42-build.log`,
`tmp/a42-python.log`, `tmp/a42-layout.json`, `tmp/a42-standalone.log`.
Porównania effective-pom i inwentarze bibliotek również są w `tmp/a42-*`.
Raporty i pliki wynikowe nie są przeznaczone do publikacji w repozytorium.

## Pozostały zakres

A42 pozostaje częściowo otwarte: dalsze usuwanie powielonych mechanizmów
infrastruktury i podział dużych plików sklepu/CC na testowalne moduły.
A42.1 zamyka wspólny model budowania. A41 jest odłożone decyzją użytkownika:
nie konsolidować usług, JVM ani PostgreSQL automatycznie. A05/Stripe i zmiany
samych zdjęć również pozostają odłożone.
