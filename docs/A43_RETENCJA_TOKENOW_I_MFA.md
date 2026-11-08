# A43.2 — retencja danych uwierzytelniania

Data: 27.10.2026. Pakiet 59 audytu, branch `Audit_0.1`.

Opis i wyniki poniżej dotyczą odbioru A43.2. Rozszerzenie o dwa rodzaje
znaczników weryfikacji, aktualny łączny budżet i metryki opisuje
[A43.3](A43_RETENCJA_WERYFIKACJI_KONT.md).

<!-- A43_2_STATUS -->
Wykonane i zweryfikowane lokalnie 27.10.2026. Całe A43 pozostaje otwarte.
<!-- /A43_2_STATUS -->

## Reguły usuwania

Customer Service otrzymał osobny moduł `retention`: konfigurację, repozytorium
partii SQL i worker. Obejmuje cztery kategorie danych. Wspólny okres
przechowywania po zakończeniu ich użyteczności wynosi domyślnie siedem dni.
Nie zmieniono TTL sesji, kodów/challenge, kluczy MFA/JWT ani uprawnień API.

| Kategoria / etykieta `store` | Warunek kwalifikacji |
| --- | --- |
| `refresh` | Cała rodzina tego klienta ma wygaśnięcie, rotację i unieważnienie starsze niż granica retencji |
| `password_reset` | Zarówno wygaśnięcie, jak i ewentualne użycie są starsze niż granica |
| `recovery_used` | Kod został użyty, a czas użycia jest starszy niż granica |
| `totp_inactive` | Metoda jest wyłączona, a koniec konfiguracji albo data aktualizacji starego rekordu bez terminu są starsze niż granica |

Porównanie jest ścisłe: rekord dokładnie na granicy pozostaje zachowany.
Aktywne TOTP, niewykorzystane kody odzyskiwania oraz ważne lub niedawno
zakończone dane pozostają dostępne niezależnie od daty utworzenia.

Historia rotacji refresh wymaga szczególnej ochrony. `AuthService` rozpoznaje
ponowne użycie starego tokenu i unieważnia jego rodzinę, także gdy stary token
sam już wygasł. Dlatego pojedynczy stary wpis nie jest usuwany, jeśli istnieje
jakikolwiek nowszy termin/rotacja/unieważnienie tej rodziny w chronionym oknie.
Dowody żywej rodziny pozostają tak długo, jak rodzina jest utrzymywana;
worker nie wprowadza absolutnego limitu długości sesji.

## Współbieżność i budżet

Repozytorium odczytuje wyłącznie identyfikatory właścicieli, bez pobierania
hashy tokenów i szyfrogramów do workera. Nazwy tabel i predykaty pochodzą
z zamkniętego enumu, a wartości są parametryzowane.

Każda partia ma własną transakcję. Najpierw są blokowane konta klientów przez
`FOR UPDATE OF c SKIP LOCKED`, zgodnie z porządkiem blokad istniejących operacji
refresh/MFA. Następne zapytanie, po uzyskaniu tych blokad, otrzymuje świeżą
migawkę READ COMMITTED i ponownie sprawdza warunki. Chroni to konfigurację TOTP
wznowioną w trakcie pracy cleanera. DELETE również pomija zajęte wiersze.
Nie ma zdalnych wywołań HTTP ani Redis w tej transakcji.

Domyślnie transakcja usuwa najwyżej 100 rekordów, a przebieg wykonuje najwyżej
cztery partie na kategorię, czyli do 1600 usunięć łącznie. Zapytania JDBC mają
timeout dwóch sekund poza transakcją; w transakcji Spring stosuje pozostały
czas jej pięciosekundowego limitu. Wyjątki dostępu do bazy oraz
tworzenia/przekroczenia czasu transakcji są liczone jako błędy danej kategorii;
pozostałe kategorie nadal są obsługiwane. Licznik już zatwierdzonych usunięć
pozostaje poprawny również po częściowej awarii.

Zajęte konta lub zmiany w trakcie odczytu mogą odroczyć partię. Zero usunięć
nie jest uznawane za brak zaległości: worker osobno odczytuje najstarszy
kwalifikujący się rekord. Następny przebieg ponawia czyszczenie w swoim budżecie.

Migracja V13 dodaje pięć indeksów dla kwalifikacji czasu i horyzontu rodziny.
Nie usuwa rekordów. Działający worker dotyczy aktywnej bazy; kopie zapasowe
mają osobny cykl życia w A36 i dalszym A43.

## Konfiguracja

Parametry są niesekretne, dostępne w aplikacji, Compose i ConfigMap Kubernetes.

| Zmienna | Domyślnie |
| --- | --- |
| `CUSTOMER_SECURITY_RETENTION_ENABLED` | `true` |
| `CUSTOMER_SECURITY_RETENTION_AGE` | `7d` |
| `CUSTOMER_SECURITY_RETENTION_BATCH_SIZE` | `100` |
| `CUSTOMER_SECURITY_RETENTION_MAX_BATCHES_PER_STORE` | `4` |
| `CUSTOMER_SECURITY_RETENTION_INTERVAL_MS` | `600000` |
| `CUSTOMER_SECURITY_RETENTION_INITIAL_DELAY_MS` | `60000` |

Pierwszy przebieg następuje po minucie, kolejne po dziesięciu minutach od końca
poprzedniego. Retencja musi wynosić minimum sekundę, partia 1–1000, liczba
partii na kategorię 1–20, odstęp minimum 1000 ms, opóźnienie minimum 0.
Błędna konfiguracja zatrzymuje start. Profil `test` wyłącza automatyczny worker
dla H2; izolowane testy PostgreSQL jawnie wywołują repozytorium/workera.

## Metryki

Są publikowane przez istniejący endpoint Prometheus Customer Service.
Etykiety są stałe: cztery wartości `store` i dwa wyniki `outcome`; nie zawierają
klienta, tokenu, adresu e-mail, identyfikatora rodziny ani klucza.

| Metryka Prometheus | Znaczenie |
| --- | --- |
| `customer_security_retention_enabled` | Czy worker działa |
| `customer_security_retention_age_seconds` | Okres ochronny |
| `customer_security_retention_deleted_total{store}` | Zatwierdzone usunięcia |
| `customer_security_retention_runs_total{store,outcome}` | Udane/nieudane przebiegi kategorii |
| `customer_security_retention_pending{store}` | Czy pozostały kwalifikujące się stare rekordy |
| `customer_security_retention_oldest_eligible_age_seconds{store}` | Wiek najstarszego kwalifikującego się rekordu w migawce |
| `customer_security_retention_last_success_timestamp_seconds{store}` | Czas ostatniego pełnego odczytu stanu |
| `customer_security_retention_last_deleted{store}` | Usunięcia w ostatnim pełnym przebiegu kategorii |

Stan `-1` oznacza brak skutecznej migawki. Po błędzie zachowywana jest poprzednia
migawka wraz z jej czasem; licznik błędów rośnie. `pending=0` oznacza brak
kwalifikującej się zaległości, a nie pustą tabelę — mogą pozostawać chronione
dowody rotacji lub aktywne dane. Nie ma pełnego COUNT tabel ani zapytań do bazy
przy każdym scrape. Ostrzeżenie zawiera tylko kategorię i klasę wyjątku.

## Odbiór

<!-- A43_2_RESULTS -->
- **152 testy jednostkowe Java** (108 infrastruktury + 44 Customer) i **9 testów
  PostgreSQL** — PASS; łącznie **161**, bez pominiętych. Nowych przypadków: 17
  (dziewięć jednostkowych i osiem PostgreSQL).
- **12 testów Kubernetes**, zgodność sześciu parametrów w trzech formatach
  konfiguracji oraz parent 17 modułów — PASS.
- Struktura korelacji/odporności 15 JAR-ów oraz obecność nowego modułu i migracji
  V13 w pakiecie Customer — PASS. Kontenery fixture zostały usunięte po testach.
- Bez nowych zależności i zmian UI; nie powtarzano testów przeglądarkowych.
  Składnia konfiguracji i kontrola diff — PASS.

Raport: `tmp/audit-a43-2/report.json`. Log głównej regresji:
`tmp/a43-2-maven-verify-final.log`.
<!-- /A43_2_RESULTS -->

Testy PostgreSQL używają własnych kontenerów i danych: sprawdzają również
rzeczywisty `AuthService.refresh`, weryfikację kodu odzyskiwania oraz wznowienie
i potwierdzenie TOTP. Wszystkie klucze, hasła i tokeny są fixture. Testy nie
odczytują skarbców użytkownika ani nie łączą się z bazami developerskimi.

```text
./mvnw -pl customer-service -am -Pintegration verify
```

Istniejący krok CI uruchamia ten profil i nowe przypadki IT. Przy przyszłym
wdrożeniu dostarczyć migrację V13, kod i konfigurację razem. Nie wdrażano na
dev/prod i nie uruchamiano czyszczenia na prawdziwej bazie.

Znaczniki weryfikacji kont obsłużono w A43.3. Dalsza retencja audytu, telemetrii
i zdarzeń oraz eksport/usuwanie/anonimizacja obejmujące usługi i kopie wymagają
kolejnych etapów. Worker nie usuwa kont ani poświadczeń WebAuthn. A05, całe A41
oraz zmiany dotyczące samych zdjęć nadal są odłożone.
