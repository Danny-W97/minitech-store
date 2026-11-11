# A42.2 — wspólna korelacja żądań (26.10.2026)

Wspólna implementacja znajduje się w pakiecie `org.demo.platformobservability`
biblioteki `platform-security`. Zastępuje 12 lokalnych klas kontekstu,
pięć interceptorów HTTP i powieloną logikę 12 filtrów. Nie dodaje nowej usługi.

## Zachowanie i granice

Nagłówek pozostaje `X-Correlation-Id`, a klucz MDC — `correlationId`.
Identyfikator musi mieć 1–100 znaków ASCII: pierwszy to litera lub cyfra,
pozostałe mogą dodatkowo zawierać `.`, `_`, `:` i `-`. Otaczające białe znaki
są usuwane. Pusta, brakująca, zbyt długa lub niebezpieczna wartość jest zastępowana
UUID. Recommendation wcześniej przyjmował dowolny niepusty tekst; teraz korzysta
z tej samej walidacji co pozostałe usługi.

Filtr używa jednego identyfikatora w odpowiedzi i MDC oraz usuwa go po obsłużeniu
żądania, także po wyjątku. Wspólny interceptor przekazuje bieżący identyfikator
w istniejących klientach Customer, Order, Payment, Review i Recommendation.
Gateway i zapis/odczyt identyfikatorów w powiadomieniach oraz outboxie korzystają
z tego samego kontekstu. Pola zapisanych zdarzeń i schematy baz pozostają zgodne.

W usługach pozostają małe adaptery `@Component` dziedziczące wspólny filtr.
Zachowują dotychczasową rejestrację, nazwę i kolejność filtrów. Adapter Customer
dodatkowo zachowuje `auditClientIp` i `auditUserAgent`; testy sprawdzają ich
dostępność w czasie żądania i czyszczenie po sukcesie oraz błędzie.

Nie dodano automatycznej rejestracji filtra w usługach, które wcześniej go nie
miały, ani nie zmieniono kolejności względem autoryzacji. Odrzucenie żądania przed
tym filtrem nadal może nie zawierać identyfikatora. To nie jest pełny tracing A35,
propagacja kontekstu wszystkich zadań asynchronicznych ani standard W3C Trace Context.
Identyfikator służy diagnostyce i nie nadaje żadnych uprawnień.

## Budowanie i kontrola

Po usunięciu lokalnych klas konieczna jest czysta przebudowa; stare pliki `.class`
nie mogą pozostać w paczkach. Dla całego repozytorium:

```bash
./mvnw clean package
python3 scripts/qa/correlation-layout-check.py --jars
python3 scripts/qa/correlation-e2e.py
```

Pierwsza kontrola bez `--jars` działa również przed budowaniem. Wykrywa lokalne
kopie kontekstu/interceptora, stare odwołania i filtry pomijające wspólną logikę.
Wariant `--jars` sprawdza bibliotekę we wszystkich 15 wykonywalnych JAR-ach
oraz brak pozostałości lokalnych klas. Obie kontrole i próba HTTP są wymagane w CI.

Test E2E wymaga Java 25, Docker oraz gotowych JAR-ów. Uruchamia własny PostgreSQL,
Redis i dwie JVM: Gateway oraz Recommendation. Pozostałe zależności HTTP są
izolowanymi fixture. Używa losowych portów i wygenerowanych danych; nie odczytuje
profili ani skarbców użytkownika. Sprząta własne procesy, kontenery i wolumeny.
Sprawdza zgodność ID na kolejnych wywołaniach, dwa wywołania historii klienta,
równoległe żądania, niebezpieczne nagłówki, błąd zależności oraz odmowę dostępu.

## Odbiór lokalny

- Czyste `package`: 492 testy Java — bez błędów i pominięć, cały reaktor zbudowany.
- 20 nowych przypadków Java obejmuje kontrakt nagłówka, MDC, wyjątki i audyt Customer.
- 141 testów Python, w tym cztery testy kontroli duplikacji — przeszły.
- Wszystkie 15 wykonywalnych JAR-ów zawiera wspólną implementację i nie zawiera
  starych lokalnych klas kontekstu ani interceptora.
- Próba dwóch JVM: PASS, 17 kontroli HTTP, w tym osiem równoległych żądań.
  Raport: `tmp/audit-a42-2-aacccaa9/report.json`. Potwierdzono zgodność ID
  na wszystkich wywołaniach fixture, walidację bezpośredniego nagłówka,
  ponowne poprawne żądanie po błędzie oraz odmowę dostępu bez uwierzytelnienia.
- Logi: `tmp/a42-2-build.log`, `tmp/a42-2-python.log`; próby HTTP:
  `tmp/audit-a42-2-*/report.json`. Raporty pozostają lokalnymi artefaktami QA.

Nie wdrażano na dev/prod ani nie uruchamiano migracji na bazach użytkownika.
Pełne A42 nadal obejmuje pozostałe duplikacje konfiguracji/wykonawców HTTP
i podział dużych plików sklepu oraz CC. A05, całe A41 i zmiany samych zdjęć
pozostają odłożone; nie konsolidowano aplikacji, JVM ani baz.
