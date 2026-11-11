# A43.3 — retencja znaczników weryfikacji kont

Data: 27.10.2026. Pakiet 60 audytu, branch `Audit_0.1`.

<!-- A43_3_STATUS -->
Wykonane i zweryfikowane lokalnie 27.10.2026. Całe A43 pozostaje otwarte.
<!-- /A43_3_STATUS -->

## Zakres i reguły

Istniejący worker Customer Service z A43.2 obsługuje dwie dodatkowe kategorie.
Wykonuje ograniczone UPDATE pól weryfikacji. Nie usuwa kont ani nie zmienia
hasła, adresu, zgód lub stanu aktywacji klienta. Migracja V14 dodaje wyłącznie
dwa indeksy częściowe, bez czyszczenia danych w migracji.

| `store` | Kwalifikacja | Efekt |
| --- | --- | --- |
| `verification_expired` | Nieaktywne konto, niepusty hash oraz termin weryfikacji starszy niż granica retencji | Usunięcie wyłącznie `verification_token_hash` |
| `verification_confirmed` | Aktywne konto ze znacznikami weryfikacji; najnowszy termin, czas wysyłki lub początek okna starszy niż granica; przy braku wszystkich dat używane jest `updated_at` | Wyzerowanie hasha, terminu, obu dat wysyłki/okna i licznika ponowień |

Granica jest ścisła: rekord dokładnie na niej pozostaje. Domyślne siedem dni
to okres dodatkowego przechowywania po dacie kwalifikacji, a nie nowy czas
ważności linku. Ważny link oraz świeże znaczniki są chronione. Nieaktywny
rekord z hashem, ale bez terminu, jest pomijany: worker nie zgaduje jego stanu.

Nieaktywne konto zachowuje wygasły termin jako znacznik oczekującej rejestracji
oraz licznik i daty ograniczania wysyłki. Te pola zajmują stałe miejsce w jednym
rekordzie; nie wymagają utrzymywania hasha wygasłego linku. Rejestracja tego
samego adresu nadal nie zastępuje konta, a ponowna wysyłka działa przez istniejący
endpoint. Po potwierdzeniu konto przechodzi do drugiej kategorii retencji.

## Ponowna wysyłka i współbieżność

`canResendVerification` rozpoznaje oczekującą rejestrację po zachowanym terminie.
Brak hasha po retencji nie blokuje odnowienia linku. Konto aktywne i konto bez
znacznika oczekiwania pozostają niekwalifikujące się do ponownej wysyłki.
Minimalny odstęp i limit w oknie nadal obowiązują, także gdy okres retencji
skonfigurowano na krótszy niż okno ograniczania wysyłki.

Potwierdzanie linku używa teraz zapytania z blokadą klienta, tak jak ponowna
wysyłka i retencja. Zapytanie po hashu ponownie sprawdza warunek po oczekiwaniu
na blokadę. Stary link nie aktywuje konta po zastąpieniu go przez równoległe
ponowienie wysyłki; równoległe potwierdzenia przyjmują token najwyżej raz.

Encja Customer używa `@DynamicUpdate`: zapis profilu wysyła zmienione kolumny.
Starsza transakcja profilu nie odtwarza wyczyszczonego hasha ani metadanych,
które wczytała przed czyszczeniem. Retencja nie zmienia biznesowego `updated_at`.

Każda partia blokuje najpierw konta i pomija zajęte przez `SKIP LOCKED`.
Następnie świeże zapytanie ponownie sprawdza kwalifikację oraz ogranicza
liczbę aktualizacji. Nowy link zapisany podczas ponowienia jest chroniony.
Budżety, limity czasu i obsługa błędów pozostają wspólne z A43.2.

## Konfiguracja i metryki

Nie dodano zmiennych ani sekretów. Obie kategorie używają sześciu parametrów
`CUSTOMER_SECURITY_RETENTION_*` opisanych w [A43.2](A43_RETENCJA_TOKENOW_I_MFA.md).
Domyślnie: siedem dni, partia 100, najwyżej cztery partie na kategorię,
odstęp dziesięć minut i pierwsze uruchomienie po minucie. Dla dwóch nowych
kategorii to do 800 aktualizacji na przebieg, dla wszystkich sześciu kategorii
łącznie do 2400 zmienionych rekordów. To limit operacji, nie gwarantowane tempo.
Wyłączenie workera wyłącza wszystkie sześć kategorii; profil `test` nadal
wyłącza automatyczne czyszczenie H2.

Dotychczasowe cztery kategorie zachowują nazwy metryk usuwania. Dla dwóch
nowych kategorii publikowane są:

- `customer_security_retention_cleared_total{store}` — liczba rekordów, w których
  zatwierdzono wyczyszczenie pól; nie liczba usuniętych kont ani liczba pól;
- `customer_security_retention_last_cleared{store}` — liczba takich rekordów
  w ostatnim pełnym przebiegu kategorii;
- istniejące `runs_total`, `pending`, `oldest_eligible_age_seconds` i
  `last_success_timestamp_seconds` z nowymi, stałymi wartościami `store`.

Cały moduł ma 44 metryki Micrometer, sześć etykiet kategorii i dwa wyniki.
Nie ma identyfikatorów klientów, adresów e-mail ani tokenów w etykietach.
Nieznana migawka ma `-1`. Błąd nie kasuje poprzedniej migawki, nie udaje sukcesu
i nie gubi liczby wcześniej zatwierdzonych zmian. Zajęty rekord nadal jest
widoczny jako zaległość. `pending=0` oznacza brak kwalifikujących się danych,
a nie brak kont oczekujących na potwierdzenie.

## Odbiór

<!-- A43_3_RESULTS -->
- **157 testów jednostkowych Java** (108 infrastruktury + 49 Customer) i
  **18 testów PostgreSQL** — łącznie **175, PASS**, bez pominiętych.
  Nowych przypadków: 14 (pięć jednostkowych i dziewięć PostgreSQL).
- Odbiór obejmuje granicę retencji, zachowanie nieznanego terminu, brak usuwania
  kont/profili i zmiany biznesowych dat, oba limity wysyłki po wyczyszczeniu
  hasha, nowy link i jego potwierdzenie, blokadę zastąpienia oczekującej rejestracji,
  stare metadane aktywnych kont, starszy zapis profilu, zajęte wiersze,
  równoległe partie, budżet workera i opróżnienie zaległości.
- Równoległe potwierdzenie jest jednokrotne. Potwierdzenie starego linku
  rzeczywiście czeka na blokadę ponowienia wysyłki, po czym zostaje odrzucone.
  Po czyszczeniu metadanych aktywne konto nadal się uwierzytelnia.
- Parent 17 modułów, 12 adapterów korelacji oraz korelacja/odporność 15 JAR-ów
  — PASS. V14 i zmienione klasy są w zbudowanym pakiecie Customer.
- Kontrola diff — PASS. Nie powtarzano testów przeglądarek ani Kubernetes,
  ponieważ UI i manifesty nie zmieniły się w tym etapie.

Raport: `tmp/audit-a43-3/report.json`. Główna regresja:
`tmp/a43-3-maven-verify-final.log`.
<!-- /A43_3_RESULTS -->

Testy PostgreSQL korzystają wyłącznie z własnych kontenerów i danych fixture.
Potwierdzenie, ponowienie wysyłki i uwierzytelnienie używają rzeczywistego
CustomerService; klient powiadomień jest mockiem, więc test nie wysyła e-maili.
Test wyścigu potwierdzenie/ponowienie sprawdza w PostgreSQL rzeczywiste
oczekiwanie zapytania na blokadę, a nie tylko kolejność wywołań w kodzie.

```text
./mvnw -pl customer-service -am -Pintegration verify
```

Istniejący profil integracyjny i krok CI obejmują nowe przypadki. Przy wdrożeniu
dostarczyć jednocześnie kod Customer i V14, po wcześniejszych migracjach.
Nie wdrażano na dev/prod i nie czyszczono prawdziwych danych. Nie zmieniono UI,
API żądań/odpowiedzi, kluczy JWT/MFA ani zależności.

Całe A43 pozostaje otwarte: dalsza retencja audytu, telemetrii i zdarzeń oraz
eksport/usuwanie/anonimizacja obejmujące wszystkie usługi i kopie. Nieaktywnych
kont nie usuwa się automatycznie w tym etapie. A05, całe A41 i zmiany samych
zdjęć pozostają odłożone zgodnie z decyzją użytkownika.
