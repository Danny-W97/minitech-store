# A43.20 — eksport zachowanego logu rekomendacji

Zalogowany właściciel wywołuje
`GET /api/v1/recommendations/me/data-export?size=50&cursor=0`.
Obsługiwany jest też alias `/api/recommendations/me/data-export` i port
Recommendation. Operator, anonim i token usługi nie mają dostępu; parametr
klienta i nagłówki nie wybierają innego podmiotu.

Eksport zwraca **tylko istniejące, zachowane wpisy przypisane do konta**.
Zapis logu pozostaje opcjonalny i domyślnie wyłączony, a retencja może
usuwać stare wiersze. Pusty wynik nie oznacza braku wcześniejszej aktywności.
Eksport nie uruchamia zapisu ani wyliczania nowych rekomendacji i nie
odtwarza usuniętych lub nigdy niezapisanych zdarzeń.

Odpowiedź jest załącznikiem `recommendation-log-page.json` z nagłówkami
private/no-store i nosniff. Zawiera:

- `schemaVersion: 1`, `scope: recommendation-log-page`, `complete: false`
  oraz czas przygotowania `generatedAt`;
- `recordingEnabled`, czyli bieżący stan zapisywania logu, oraz
  `historyCoverage: optional-retained-log`;
- `items`: `requestId`, opcjonalny `sourceProductId`, `strategy`,
  `resultCount`, `requestedAt`;
- `hasMore`, tekstowy `nextCursor` oraz manifest `omissions`.

`recordingEnabled: false` nie ukrywa istniejących wierszy, a true nie
potwierdza kompletności historii. `sourceProductId: null` pozostaje null.
Rekordy z `customer_id IS NULL` nie są przypisywane do konta na podstawie
produktu, daty, IP ani innych domysłów i są pomijane.

Rozmiar strony wynosi od 1 do 100. Pierwsza strona używa `cursor=0`.
Przy `hasMore: true` następne żądanie przekazuje `nextCursor`. Gdy
`hasMore` jest false, dalszej strony nie ma. Parametry ujemne, przekraczające
limit lub zakres kursora 64-bitowego są odrzucane kodem 400.

Każda strona jest osobnym odczytem; zapis lub retencja mogą zmieniać dane
między żądaniami. Manifest wskazuje brak wspólnej migawki, brak niedostępnych
zdarzeń i brak powiązania wpisów anonimowych. Nie jest to pełny eksport
konta i nie rozszerza pliku profilu A43.16. Nie dodano przycisku sklepu
ani widoku CC. Pobrany plik stanowi kopię zarządzaną przez użytkownika.

## Odczyt i migracja

Zapytanie wybiera jawne kolumny i maksymalnie `size + 1` wierszy po
uwierzytelnionym właścicielu, malejąco po ID. Timeout SQL wynosi dwie sekundy.
Nie ma wywołań do Product/Order ani odczytu zbiorczych metryk jako historii
pojedynczego konta. Polityki zapisywania i retencji z A43.1 nie są zmieniane.

Migracja V3 dodaje indeks `(customer_id, id DESC)`. Standardowy CREATE INDEX
może blokować zapisy podczas budowy; przy wdrożeniu istniejącej bazy trzeba
uwzględnić jej wielkość. Migrację uruchomiono tylko na bazach testowych.
Pozostałe hashe migracji/Redis w rejestrze nie zostały zmienione.

## Weryfikacja

```sh
./mvnw --batch-mode -pl recommendation-service,api-gateway -am -Pintegration verify
python3 scripts/qa/recommendation-data-export-e2e.py
python3 scripts/qa/data-lifecycle-check.py
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
```

Testy PostgreSQL obejmują strony właściciela, inne konto i wpisy anonimowe,
nullable product ID, odczyt przy wyłączonym zapisie, puste/usunięte logi,
zmiany między stronami oraz brak zapisów na roli SELECT-only. Odbiór JAR
korzysta z izolowanych Customer/Recommendation/Gateway i własnych baz.
Sprawdza także nagłówki, sześć odmów, walidację parametrów i OpenAPI.

<!-- A43_20_RESULTS -->
- **175 Java — PASS, bez pominiętych**: 111 wspólnego modułu, 33 Gateway,
  20 Recommendation unit i 11 PostgreSQL. W sumie 11 nowych przypadków:
  jeden polityki uprawnień, trzy HTTP i siedem PostgreSQL. Cztery istniejące
  testy PostgreSQL retencji są już zawarte w 11 i przeszły bez zmian.
- **246 Python — PASS, bez pominiętych**, w tym dotychczasowe 12 Kubernetes.
  Bramka rejestru PASS: 14 baz, 49 tabel, 122 migracje i 27 mechanizmów.
  Układ Maven 17 modułów — PASS.
- **JAR HTTP — PASS**: port Recommendation, `/api`, `/api/v1`, brak zmian
  danych i OpenAPI; 205 wpisów/trzy strony, dwa konta z danymi i konto
  bez logu, rekordy anonimowe, nullable product ID, sześć odmów i cztery
  błędne okna. Zapis zdarzeń pozostawał wyłączony podczas odbioru.

Raport końcowy: `tmp/audit-a43-20/report.json`. Logi:
`tmp/a43-20-maven-verify.log`, `tmp/a43-20-python.log`,
`tmp/a43-20-http-e2e.log`. Raport HTTP:
`tmp/recommendation-export-7e868efe/report.json`.
<!-- /A43_20_RESULTS -->

Pełny eksport usług, historia bezpieczeństwa/redakcja audytu, usunięcie/
anonimizacja, kopie i procedury restore pozostają otwarte. Nie zmieniano
danych użytkownika ani ustawień zapisu/retencji, nie restartowano CC i nie
wdrażano dev/prod. A05, całe A41 i zmiany samych zdjęć pozostają odłożone.
