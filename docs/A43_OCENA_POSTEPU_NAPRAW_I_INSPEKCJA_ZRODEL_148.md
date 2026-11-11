# A43.40 — ocena postępu napraw i świeża inspekcja źródeł, pakiet 148

Stan: **02.11.2026, Audit_0.1, a43.40-2026-11-02.3**.
Zakres lokalny; A43.40/.41 otwarte. Czyszczenie wyłączone.

## Dwa niezależne wyniki

`CommerceRetentionHoldRestorationProgress` ocenia jeden dokładny zakres:
zakup, wniosek, referencję sprawy, źródło i trwałą intencję naprawy.
Weryfikuje oryginalne ID, podstawę MISSING i odtwarza kanoniczny skrót
zakresu zapisanej próby. Nie weryfikuje własności sprawy ani podpisu
operatora; do tego nadal potrzebny jest odebrany proces wystawcy.

Ocena rozdziela historię dostarczenia od inspekcji źródła. Dostarczenie
może być niepodjęte, nieznane, historycznie potwierdzone lub zakończone
jedynie wynikiem diagnostycznym. Inspekcja osobno wskazuje MATCH z chwili
odczytu, brak blokady, konflikt, nieznany zakup, kwarantannę źródła,
zmianę wersji, stary odczyt, PENDING lub niedostępność.

MATCH nie dopisuje wyniku próby naprawy. Licznik nieznanych prób zapisu
pozostaje zachowany również po późniejszym REPLAYED. Stop nie dowodzi
zakończenia wykonawcy. Żaden wynik nie daje aktualnej globalnej zgody,
kompletności spraw, prawa do naprawy lub redakcji ani zgody na aktywację.

## Świeży odczyt i kolejność zdarzeń

`assess` czyta wyłącznie lokalne SQL pod blokadą rodzica. Nie robi RPC
lub DML. Przegląd obejmuje najwyżej 8 prób danej intencji oraz 8 prób
inspekcji danego wniosku/źródła. Stop i kwarantanna mają pierwszeństwo
przed pozytywną oceną; historia i liczniki pozostają dostępne.

Jawne `refresh` korzysta z istniejącego protokołu trwałych inspekcji 143:
COMMIT próby przed RPC, UUID próby jako challenge, walidacja zakresu,
wieku 15 s i tolerancji przyszłości 5 s. Ten sam UUID nigdy nie ponawia
RPC. Nie ma automatycznego odświeżania, nowych limitów lub resetu budżetu.
Obowiązuje wspólny dotychczasowy limit 8 inspekcji na wniosek/źródło.
Wyczerpanie limitu zwraca LIMIT_REACHED, bez wysyłki.

Liczy się wyłącznie najnowsza inspekcja. Nie ma powrotu do starszego
MATCH po nowym PENDING, błędzie lub starym wyniku. Podstawa intencji
nie jest odczytem po naprawie. Próba inspekcji musi mieć późniejszy numer
od podstawy i powstać nie wcześniej niż intencja, wszystkie próby naprawy
oraz ich zapisane wyniki. Odczyt rozpoczęty przed późniejszym potwierdzeniem
replay wymaga nowej inspekcji. Kolejność wynika z zegara tej samej bazy Order.

Dla MATCH/MISSING wymagany jest epoch podstawy i rewizja nie mniejsza
niż rewizja podstawy lub któregokolwiek historycznego pozytywnego
potwierdzenia naprawy. Zmiana epoch albo cofnięcie rewizji daje
VERSION_CHANGED, bez aktualizacji bindingu, nowego planu lub naprawy.
Brak zakupu pozostaje diagnozą, bez tworzenia powiązania lub zgody.

`Refresh.probe` opisuje wynik operacji inspekcji, a `Refresh.progress`
najnowszy trwały stan. Niepewny COMMIT wyniku może dać UNAVAILABLE
dla operacji, mimo że późniejszy odczyt SQL rozpoznaje zapisany MATCH.
LIMIT_REACHED może współistnieć z wcześniejszym, nadal świeżym odczytem.
Nie oznacza to wykonania nowego RPC. PENDING nie jest potwierdzany przez TTL.

## Współbieżność i granice

Zewnętrzna transakcja wywołującego jest odrzucana; żaden RPC nie odbywa
się w transakcji Order. Końcowa ocena ponownie odczytuje stop, kwarantannę
i najnowszą historię. Zmiana w trakcie odpowiedzi daje BLOCKED.
Inspekcja jest odczytem: jej historia może zachować MATCH po równoległym
stopie, ale postęp pozostanie STOPPED, bez prawa do dalszego wykonania.
Kwarantannę dodatkowo egzekwuje istniejący protokół i SQL inspekcji.

Nie dodano tabel, migracji, sekretów, endpointów operatora, przycisków CC,
schedulera lub konfiguracji dev/test. Nie zmieniono implementacji zapisu
blokad, dispatchera, protokołu inspekcji lub odtwarzania. Weryfikator jest
niewpiętym adapterem do późniejszej kontrolowanej integracji.

Inwentarz pozostaje **143 tabele / 1286 kolumn / 230 migracji**;
47 mechanizmów, 6 przestrzeni Redis, 20 kategorii kopii, 26 polityk.
Pełny drill 14 baz/Redis z pakietu 147 pozostaje dowodem historycznym;
nie powtarzano go w 148, bo schemat i kod restore są bez zmian. Nie jest
odbiorem nowej oceny na pełnym odtworzonym środowisku. Testy nowej oceny
obejmują blokady kwarantanny i zatrzymanie odczytów.

**116 unikalnych Java (104 PostgreSQL / 12 jednostkowych) + 110 Python PASS**, bez błędów i pominięć; 57 nowych
Java (34 Root PostgreSQL / 23 HTTP), 59 regresji. Siedem mutacji kontraktu
w istniejącym teście Python; bez nowych metod Python. Bramki/candidate
PASS, gotowość NOT_READY / kod 2.
Dowody: `tmp/a43-restoration-progress-148/report.json`.
Testy używają syntetycznych spraw, izolowanych baz i rzeczywistego
transportu HTTP/kontrolerów; introspekcja i wystawca są testowe.
Bez odbioru pełnych aplikacji, realnych spraw i mieszanych wersji dev/test.

## Punkt wznowienia

Następne: zbiorczy przegląd postępu siedmiu źródeł jednego wniosku,
z identyfikacją brakujących lub niejednoznacznych zakresów do ręcznej
oceny. Bez automatycznego wznowienia, zmiany bindingów lub globalnej zgody.
Następnie kompletne wersjonowane dowody i pełne wykonanie/odzyskiwanie D02.

Rzeczywisty wystawca, proces spraw/tożsamości, klucz i rotacja/odwoływanie
nadal odłożone do odbioru. D01/P01, D09/P03–P05/SAFE, P09 i odbiór ról/
rejestru również odłożone. A05, A41 i same zdjęcia A27–A31/A40 bez zmian.
