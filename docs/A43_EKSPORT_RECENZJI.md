# A43.19 — stronicowany eksport własnych recenzji

Zalogowany klient wywołuje
`GET /api/v1/reviews/me/data-export?size=50&cursor=0`. Obsługiwany jest
również alias `/api/reviews/me/data-export` i bezpośredni port Review.
Wymagany jest własny, ważny access JWT. Operator, anonim i token usługi nie
mają dostępu. Tożsamość pochodzi z uwierzytelnienia; query i nagłówki klienta
nie wybierają właściciela.

Odpowiedź jest załącznikiem JSON `reviews-data-page.json`, z nagłówkami
private/no-store i nosniff. Każda pozycja zawiera `reviewId`, `productId`,
`rating`, `title`, `content`, `status`, `createdAt` i `updatedAt`.
Eksport obejmuje własne wpisy PENDING, PUBLISHED i REJECTED. Nie zawiera
`moderationNote`, `orderId` ani `customerId`.

Wynik ma `schemaVersion: 1`, `scope: reviews-page`, `complete: false`,
`generatedAt`, `hasMore`, tekstowy `nextCursor` i `omissions`. Pierwsze
żądanie używa `cursor=0`; jeśli `hasMore` jest true, kolejna strona używa
otrzymanego `nextCursor`. Rozmiar strony wynosi od 1 do 50, a nieprawidłowe
parametry zwracają 400. Kursor jest nieujemną liczbą 64-bitową.

Treść autora jest zwracana tak, jak została zapisana, łącznie z własnym
formatowaniem, znakami Unicode czy dosłownym tekstem znaczników. Może
zawierać dane innych osób wpisane przez autora. Nie wykonano automatycznej
semantycznej redakcji tekstu i nie deklarujemy pełnej anonimizacji.
Informacja o tym jest obecna w `omissions`. Eksport jest udostępniany
wyłącznie właścicielowi, nie jako nowy publiczny widok opinii.

Strony nie tworzą wspólnej migawki: nowe recenzje i zmiany moderacji mogą
nastąpić między żądaniami. Plik profilu A43.16 nie zostaje rozszerzony o te
dane. Pełny eksport konta, pozostałe usługi, historia bezpieczeństwa,
redakcja audytu, usuwanie/anonimizacja i obsługa kopii pozostają otwarte.
Nie dodano przycisku sklepu ani widoku CC.

## Odczyt

Jedno zapytanie wybiera jawne kolumny, filtruje uwierzytelnionego właściciela
i odczytuje maksymalnie `size + 1` wierszy malejąco po ID. Dodatkowy wiersz
pozwala ustalić `hasMore`; timeout SQL wynosi dwie sekundy. Używany jest
istniejący indeks `(customer_id, id DESC)` z V2. Nie dodano nowej migracji
ani nie zmieniono hashy migracji/Redis w rejestrze.

Notatki moderatorów i identyfikatory zamówień nie są wybierane z bazy.
Osobne DTO nie serializuje encji Review. Pozostałe istniejące endpointy
Review zachowują dotychczasowy zakres; ten etap dotyczy nowej projekcji eksportu.
Pobrane strony są kopiami zarządzanymi przez użytkownika.

## Weryfikacja

```sh
./mvnw --batch-mode -pl review-service,api-gateway -am -Pintegration verify
python3 scripts/qa/review-data-export-e2e.py
python3 scripts/qa/data-lifecycle-check.py
python3 -m unittest discover -s scripts/qa/unit -p 'test_*.py'
```

Testy PostgreSQL działają w profilu integration na własnym kontenerze.
Sprawdzają 105 wpisów w trzech stronach, dwa konta, wszystkie statusy,
dosłowny Unicode/tekst znaczników, zmiany podczas stronicowania i brak zapisów.
Osobna rola bazy ma SELECT tylko do dozwolonych kolumn; nie może odczytać
moderation_note/order_id, a eksport nadal działa.

Odbiór JAR używa własnych Customer/Review/Gateway, baz i losowych poświadczeń.
Sprawdza port Review, `/api`, `/api/v1`, sześć odmów, limity, nagłówki,
wykluczone pola/notatki, dwie tożsamości i OpenAPI. CI uruchamia testy
integracyjne oraz ten odbiór HTTP.

<!-- A43_19_RESULTS -->
- **162 Java — PASS, bez pominiętych**: 110 wspólnego modułu, 33 Gateway,
  12 Review unit i siedem Review PostgreSQL. W sumie 11 nowych przypadków:
  jeden polityki uprawnień, trzy HTTP i siedem PostgreSQL.
- **246 Python — PASS, bez pominiętych**, w tym dotychczasowe 12 Kubernetes.
  Bramka rejestru PASS: 14 baz, 49 tabel, 121 migracji i 26 mechanizmów.
  Nie zmieniano hashy migracji/Redis. Układ Maven 17 modułów — PASS.
- **JAR HTTP — PASS**: port Review, `/api`, `/api/v1`, niezmienność danych
  i OpenAPI; 105 recenzji/trzy strony, dwa konta, wszystkie statusy, sześć
  odmów i cztery nieprawidłowe okna. Zweryfikowano dozwolone pola, notatki
  kontrolne nie występują w odpowiedzi, a UTF-8 i dosłowna treść są zachowane.

Raport końcowy: `tmp/audit-a43-19/report.json`. Logi:
`tmp/a43-19-maven-verify.log`, `tmp/a43-19-python.log`,
`tmp/a43-19-http-e2e.log`. Raport HTTP:
`tmp/review-export-62904bbd/report.json`.
<!-- /A43_19_RESULTS -->

Nie zmieniano danych kont użytkownika, nie uruchamiano zdalnego CI ani
wdrożenia dev/prod i nie restartowano CC. A05, całe A41 oraz zmiany samych
zdjęć pozostają odłożone.
