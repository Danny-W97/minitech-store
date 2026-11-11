# A43.40 — odbiór HTTP dzierżaw siedmiu źródeł, pakiet 131

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.9**.
Wdrożenie i odbiór lokalne; A43.40/A43.41 nadal otwarte, czyszczenie wyłączone.

## Wdrożony zakres

Dodano `CommerceRetentionIntentHttpPostgresIT` i `RetentionIntentHttpFixture`.
Koordynator Order wykonuje pełną rundę odczytów, PREPARE, VERIFY i RELEASE
przez rzeczywiste HTTP dla Payment, Inventory, Returns, Promotion, Delivery,
Billing i Notification. Każde źródło ma własną bazę PostgreSQL, a Order ósmą
bazę. Fikstura korzysta z jednego jednorazowego kontenera z ośmioma odrębnymi
bazami; nie łączy magazynów aplikacji ani nie wdraża odłożonego A41.

Sześć źródeł używa rzeczywistego kontrolera współdzielonego, Inventory —
własnego kontrolera i kolektora. Te dwa pliki Inventory są kompilowane
bezpośrednio ze źródeł projektu przez JDK 25 w tymczasowym katalogu.
Brak kompilatora lub próba użycia starszych klas z nadrzędnego classloadera
kończy test błędem; nie ma pominięcia lub zastąpienia Inventory atrapą.
Nie dodano zależności produkcyjnej między modułami Maven.

Kontrolery są uruchamiane przez MockMvc z rzeczywistym filtrem
uwierzytelniania, za serwerami HTTP na loopback. Introspekcja tożsamości
jest fiksturą. To odbiór transportu, kontrolerów i SQL, bez uruchamiania
pełnych aplikacji Spring Boot lub rzeczywistej usługi Account/JWT.
Warunkowe włączenie endpointów w dev/test nie jest w ten sposób potwierdzone.

## Sprawdzone scenariusze

- Pełna runda: osiem obserwacji, siedem odczytów HTTP, siedem PREPARE,
  siedem VERIFY i siedem RELEASE. Rzeczywiste dzierżawy blokują sprawdzane
  zapisy Inventory i Delivery; po RELEASE tokeny wszystkich siedmiu źródeł
  są puste. Intencja pozostaje WAITING_AUTHORIZATION, bez nowego zdarzenia.
- Utrata odpowiedzi PREPARE po zatwierdzeniu SQL: osobny przypadek dla każdego
  źródła. Koordynator otrzymuje UNAVAILABLE, nie przechodzi do VERIFY/oceny,
  ale zwalnia również dzierżawę źródła, którego odpowiedź nie dotarła.
- Utrata odpowiedzi RELEASE po zatwierdzeniu SQL: osobny przypadek dla każdego
  źródła. Mimo rzeczywistego usunięcia tokenu wynik pozostaje UNCONFIRMED.
  Zwolnienie pozostałych źródeł jest kontynuowane. Test zna stan źródła;
  koordynator nie wykorzystuje tej wiedzy do udawania potwierdzenia.
- Równoległy stop podczas odpowiedzi Inventory na PREPARE i VERIFY. Stop
  zapisuje się, zanim zwolnimy oczekującą odpowiedź; dalsza ocena nie startuje,
  a wszystkie wysłane PREPARE są rozliczane przez RELEASE.
- Każdy endpoint odrzuca klienta, operatora, inną usługę i brak uwierzytelnienia,
  także przy podszywającym się nagłówku nazwy usługi. Sprawdzane są metoda,
  błędny JSON, limit rozmiaru, prawidłowy zakres oraz private/no-store.

Awarię odpowiedzi modeluje pusty **HTTP 503 po rzeczywistym COMMIT** źródła.
To deterministyczny brak użytecznego potwierdzenia; nie jest testem zerwania
TCP, dowolnej awarii sieci, timeoutu połączenia lub zatrzymania procesu.
Nie dodano automatycznego retry. Wywołania zdalne koordynatora są poza SQL.

## Poprawka Inventory

Ujednolicono endpoint dzierżaw Inventory: przekroczenie limitu 4096 bajtów
zwraca **413**, tak jak w sześciu pozostałych źródłach. Dotychczas zwracał 400.
Niepoprawny JSON lub zakres nadal zwraca 400. Limit, kontrola dostępu i
domyślne wyłączenie funkcji pozostają zgodne z istniejącym protokołem.

## Odbiór i granice

Raport, XML i odciski źródeł: `tmp/a43-intent-http-131/report.json`.
Odbiór: **27 PostgreSQL PASS** (24 nowe przypadki HTTP, 3 regresje
źródeł SQL) i **85 unikalnych Python PASS**, bez pominięć. Kontrole
strukturalne i candidate PASS; pełna gotowość **NOT_READY / kod 2**.
Nie sumuje się testów wcześniejszych pakietów i powtórnych przebiegów.

Brak nowych migracji: **109 tabel / 1065 kolumn / 200 migracji**.
Nie zmieniono restore ani schematu; pełnego drillu 14 baz/Redis nie
powtarzano. Ostatni drill z pakietu 128 pozostaje historyczny:
`tmp/privacy-recovery-3d360cc0/report.json`, releaseAllowed=false.
Nie odczytywano sekretów ani danych rzeczywistych dev/test/prod i nie
aktywowano endpointów, harmonogramów lub czyszczenia w tych środowiskach.

Odbiór HTTP nie stanowi globalnej migawki lub upoważnienia do mutacji.
`activationAllowed=false`, `runtimeWired=false`, `executionTerminated=false`.
Nie wdrożono trwałego koordynatora rundy, pełnego protokołu wykonania D02,
potwierdzeń redakcji ani niezależnej pieczy rejestru D09.

## Punkt wznowienia

Następny niezależny zakres: trwałe rozliczanie niepewnych rund obserwacji
i odzyskiwanie ich stanu po awarii, bez automatycznego wznowienia mutacji.
Oddzielić historię próby, stan potwierdzenia RELEASE i wygaśnięcie TTL;
żaden z nich nie jest zgodą na usuwanie danych lub zakończeniem wykonawcy.
Pełne globalne upoważnienie i wykonanie D02 pozostają osobnym protokołem.

D01/P01, D09/SAFE, P09 i rzeczywisty odbiór ról/rejestru spraw pozostają
odłożone. Zakresy wymagające konkretnych wartości lub decyzji odkładać i
kontynuować niezależne prace. A05, A41 i zmiany samych zdjęć nadal odłożone.
