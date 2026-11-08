# A43.38 — kopie wewnętrzne i stan urządzeń

Zakres wdrożenia lokalnego z 30.10.2026. Okresy i aktywację retencji ustala
A43.40. Nie uruchomiono czyszczenia rzeczywistych logów, wolumenów, skarbców,
sekretów ani danych przeglądarki użytkownika. Pełne A43 pozostaje otwarte.

## Decyzje użytkownika

Nowe wpisy historii wysyłek CC zawierają tylko identyfikator operacji,
czas, typ dokumentu, ID dokumentu i zamówienia oraz wynik. Nie zapisują
e-maila, tematu, numeru dokumentu, nazwy pliku ani swobodnej treści.
Stara historia pozostaje bez zmian do ręcznej oceny.

Wylogowanie czyści widoczne dane konta/panelu, poświadczenia w pamięci,
formularze i bufory eksportu. Koszyk gościa i token rozmowy pozostają
niezależne. Zapis odzyskania niedokończonego checkoutu zachowuje swój
zakres konta; nie usuwać go przed uzgodnieniem operacji. Nie tworzymy
nowej kopii tego zapisu ani nie udostępniamy go innemu kontu.

## Macierz zasobów

| Zasób | Ograniczenie i obsługa | Pozostawiony zakres |
| --- | --- | --- |
| `cc-job-output` | A43.6/7/8: ograniczony zapis i odczyt, observe/purge wyłącznie certyfikowanych zamkniętych par poza aktywną historią i bez pisarzy; diagnostyka jest próbką. Odbiór blokad/limitów na własnych plikach. | Aktywne, historyczne bez certyfikatu i niejednoznaczne pliki do ręcznej oceny; okresy/aktywacja A43.40. |
| `cc-dispatch-history` | Nowe `dispatch-history-v2.json`: maks. 200 metadanych, blokada pisarzy i atomowa podmiana; stary `dispatch-history.json` wyłącznie do odczytu. API projektuje także stare wpisy do bezpiecznych metadanych i oznacza ręczną ocenę. | Stara zawartość fizyczna pozostaje; nie kasować jej automatycznie. Przy uszkodzonym/za dużym pliku API zwraca 503 zamiast pustej historii. Sukces SMTP z błędem zapisu historii nie uprawnia do ponownej wysyłki. |
| `cc-ai-settings` | A26: klucze w SOPS, projekcja stanu bez klucza; historia rozmowy asystenta jest ograniczona w RAM i resetowana przy końcu sesji. | Zaszyfrowanych ustawień i profilu współdzielonego nie usuwa żądanie klienta; provider i stare kopie A43.39/40. |
| `application-logs` | Nowe logi HTTP CC zawierają metodę, stałą kategorię trasy i status; bez request line, query, IP, wyszukiwanych e-maili i tokenów. Logi Java/Compose/Kubernetes obsługiwane przez mechanizm hosta. | Istniejące logi wymagają ograniczonej oceny; brak automatycznego dopasowania całego logu przez e-mail, IP albo sam numer. Zewnętrzny backend/konfiguracja demona wymaga dowodu ze środowiska. |
| `monitoring-storage` | Prometheus/Grafana/Alertmanager mają osobne wolumeny; dane agregowane nie są automatycznie rekordem jednego klienta. | Brak zgody na usunięcie całego wolumenu lub zmiany okresu. Etykiety, dashboardy, alerty i dodatkowe odbiorniki podlegają przeglądowi; polityki A43.40. |
| `pdf-memory-cache` | Wspólny budżet i TTL A30; cykliczny sweep co 60 s zwalnia przeterminowane wpisy także bez kolejnego żądania. Nie usuwa źródłowych faktur/korekt. | Odczyt przeterminowanego wpisu nadal blokowany natychmiast. Kopie w locie/kliencie i pobrane pliki mają osobny cykl; brak obietnicy zerowania RAM JVM. |
| `browser-state` | Sklep: unieważnienie generacji żądań, anulowanie pobrań/eksportów, usunięcie profilu/list/sekretów formularzy/QR; późna odpowiedź nie przywraca danych. Koszyk gościa i czat pozostają. | Oddzielne koszyki kont zachowują dotychczasową izolację A15 i możliwość ponownego logowania; ich zawartość użytkownik czyści w swoim koszyku. Istniejące klucze odzyskania checkoutu pozostają w swoim zakresie. Nie deklarować usunięcia ze wszystkich urządzeń ani skanowania całego storage. |
| `cc-operator-sessions` | `POST /api/session/logout` unieważnia tylko bieżący token sesji CC. Przycisk „Zakończ sesję” czyści stan widoku i przerywa pobieranie; ponowne połączenie jest jawne. Druga sesja i zadania serwera pozostają. | Przy braku potwierdzenia serwera panel wskazuje ograniczenie; token ma dotychczasowy TTL. Globalny token operatora i współdzielony cache procesu nie są własnością tej sesji. |
| `cc-browser-intents` | Zidentyfikowana dodatkowa kopia lokalna: istniejące zamiary korekt i przyjęć z A13/A21, kluczowane profilem i dokumentem/produktem. Zakończenie potwierdzonej operacji usuwa tylko zgodny klucz. Wylogowanie usuwa widoczną treść formularza, ale nie zrywa ochrony nieuzgodnionej operacji przed duplikacją. | Zamiar korekty może zawierać swobodny powód. Nie eksportować surowego localStorage ani nie czyścić wszystkich profili; ocena/redakcja według konkretnej operacji. Wycofanie nieuzgodnionego klucza wymaga wcześniejszego uzgodnienia; okresy A43.40. |
| `account-export-chunks` | A43.31: 1 h od utworzenia, bez przedłużania, brak dostępu po expiry/cancel, porcjowe fizyczne czyszczenie; późny zapis odrzucony. A43.32 zwalnia Blob URL i RAM. | Flaga automatycznego kolektora wymaga osobnej aktywacji. Backupy A43.37, pobrane pliki A43.39. |
| `service-keyrings` | A22/A26: rotacja z zachowaniem dostępu do starszych szyfrogramów; nowe klucze są niezależne od JWT. Rejestr A43.37 ma osobną pieczę. | Brak automatycznego usuwania klucza na podstawie wieku albo żądania klienta. Wycofanie po dowodzie migracji i ocenie kopii A43.40. Stripe nadal odłożone w A05. |

## Ręczna ocena i redakcja

Najpierw potwierdzić konkretny podmiot i zasób według źródła biznesowego:
stabilne ID konta/żądania lub łańcuch zamówienie–dokument. Sam e-mail,
adres IP, timestamp i przypadkowo równy numer nie wystarczają.
Wybrać tylko związany z wnioskiem ograniczony fragment, z uprawnieniami
operatora; nie wydawać klientowi całego logu, historii lub metryk.

Pracować na izolowanej kopii do oceny, zachowując dowód źródła i decyzji
bez kopiowania sekretów do notatki. Zredagować dane innych osób, tokeny,
klucze, pełne requesty i swobodne treści wymagające osobnej podstawy oceny.
Nie modyfikować aktywnego logu, otwartego stdout lub współdzielonego pliku
za plecami pisarzy. Niejednoznaczne przypisanie oznaczyć jako wymagające
oceny, a nie automatycznie wykonane usunięcie. Fizyczne postępowanie ze
starą historią, logami i ich kopiami czeka na politykę A43.40.

## Środowisko, log rotation i niewykorzystywane backendy

`scripts/qa/internal-copies-check.py` sprawdza pokrycie 11 zasobów i zapisuje
SHA256 publicznych konfiguracji źródłowych. Nie otwiera runtime, skarbców,
SOPS ani zmiennych środowiska. Wynik braku jawnej konfiguracji zewnętrznego
loggera dotyczy wyłącznie sprawdzonych źródeł. Nie oznacza, że konfiguracja
demona Docker, klastra albo dostawcy jest pusta.

Przed aktywacją operator ustala faktyczny driver logów, sidecary/collectory,
odbiorniki alertów, wolumeny i snapshoty. Dla nieużywanego backendu zapisuje
datę, środowisko, źródło konfiguracji i potwierdzenie braku; dla używanego
przypisuje właściciela, zakres i procedurę A43.39/40. Brak dowodu pozostaje
widoczną przeszkodą przed odbiorem A43.41. Nie uznawać nieznanego backendu
za automatycznie obsłużony.

Rotację logów sprawdza się na jednorazowym kontenerze i jego własnych
limitach drivera. Ten test nie ustawia retencji realnego demona i nie
uprawnia do usuwania wolumenów Prometheus/Grafana/Alertmanager.

## Odbiór i aktywacja

Odbiór obejmuje pliki syntetyczne, konkurencyjnych pisarzy i niezmienność
legacy, unieważnienie jednej sesji przy zachowaniu drugiej i zadań,
wylogowanie w przeglądarce oraz niedopuszczenie późnej odpowiedzi,
izolację checkoutu, sweep PDF i wygaśnięcie/anulowanie porcji eksportu
na PostgreSQL. Testy nie korzystają z rzeczywistych danych użytkownika.

Wdrożenie kodu nie aktywuje purge, kolektora eksportów ani nowych okresów.
Nadal wymagane są A43.39–A43.41 i konfiguracja rejestru z A43.37.
