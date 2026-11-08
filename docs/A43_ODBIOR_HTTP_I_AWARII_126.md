# A43.40 — odbiór HTTP, współbieżności i awarii, pakiet 126

**Nowszy punkt wznowienia:** [pakiet 127 — trwała historia obserwacji](A43_TRWALE_OBSERWACJE_ZRODEL_127.md).
Poniżej historyczny odbiór 126; archiwalne obserwacje dodane w 127 nie
zastępują potwierdzeń wykonania D02.

Stan: **01.11.2026, Audit_0.1, a43.40-2026-11-01.4**.
Zakres lokalny; A43.40/A43.41 pozostają otwarte, czyszczenie wyłączone.

## Zmiany kodu

Kolektor i `reviewSourceGuards` zamieniają lokalny błąd dostępu do SQL lub
transakcji na `UNAVAILABLE`. Dotyczy to także wstępnego/końcowego sprawdzania
zakresu. Nie zwracają dodatniej zgody ani komunikatu o wykonaniu czyszczenia.
Walidacja parametrów i zakaz wywołania w transakcji nadrzędnej pozostają.

Dowody są zapisywane w jednej lokalnej transakcji. Błąd przed COMMIT wycofuje
rozpoczęty zapis wraz ze zmianą rewizji zadania. Utrata potwierdzenia po
COMMIT jest odrębnym, niepewnym wynikiem: metadane mogły już zostać zapisane.
Krok zwraca UNAVAILABLE; jawne ponowienie z nowym identyfikatorem żądania
sprawdza aktualny zakres i rozpoznaje istniejący dowód bez dublowania.
Nie zakłada się automatycznie rollbacku po utracie odpowiedzi.

Poprawiono opis metody: odczyt jednego źródła nie oznacza oceny kompletnej
polityki, globalnych blokad lub aktualnego niezależnego rejestru.

## Zakres odbioru technicznego

Nowa fikstura uruchamia lokalne serwery HTTP, rzeczywisty filtr autoryzacji,
kontroler sześciu usług i ich osobne bazy PostgreSQL. Wywołania przechodzą
przez produkcyjny `PeerRetentionClient`. Dispatch serwletu używa MockMvc;
serwis ustalający tożsamość jest fiksturą testową. Nie jest to uruchomienie
sześciu wdrożonych aplikacji Spring Boot ani odbiór rzeczywistego dev/test.

| Scenariusz | Sprawdzany skutek |
|---|---|
| Pełny lokalny przebieg HTTP sześciu źródeł | Jeden odczyt na krok; nadal WAITING_DEPENDENCY, bez dodatnich GUARD lub zgody na wykonanie. |
| Klient, operator, inna usługa, brak logowania, GET i zbyt duży body | Odmowa przez rzeczywisty filtr/routing; podrobione X-Service-Name nie nadaje uprawnień; brak cache. |
| Rzeczywista zaległość w każdym źródle | BLOCKED i wycofanie generacji, zachowane dane biznesowe. |
| HTTP 503, błędny JSON lub przekroczony limit odpowiedzi | UNAVAILABLE, bez dowodu i automatycznego ponowienia; jawny retry używa nowego identyfikatora. |
| Powtórzenie poprzedniej odpowiedzi | Odrzucenie niezgodnego identyfikatora żądania. |
| Zmiana Order w czasie HTTP | STALE, bez zapisu zebranego dowodu. |
| Zmiana źródła już po jego odczycie | Wynik nadal UNKNOWN, bez globalnej migawki; następny odczyt wykrywa stary binding i wycofuje generację. |
| Dwa równoległe UNKNOWN | Oba pozostają oczekujące, bez dodatnich potwierdzeń. |
| Dwa równoległe dowody blokady | Jeden zapis, drugi odczyt STALE po zmianie rewizji. Kolejne świadome ponowienie rozpoznaje istniejący dowód. |
| Awaria SQL źródła | HTTP 503 → UNAVAILABLE; po odzyskaniu odczytu można powtórzyć krok. |
| Brak połączenia do SQL Order | UNAVAILABLE przed wywołaniem źródła. |
| Błąd aktualizacji zadania po INSERT dowodu | Cały zapis wycofany, rewizja bez zmian; po usunięciu awarii ponowienie wykrywa blokadę. |
| Brak potwierdzenia wykonanego COMMIT | UNAVAILABLE mimo zapisanego dowodu; kolejny odczyt nie dubluje wiersza ani rewizji. |

Kontrakt QA rozróżnia deklarację procedury od jej sprawdzenia. Sam checker
źródeł zwraca `null` dla potwierdzenia odbioru HTTP i odzyskiwania SQL;
dowodem zachowania są zachowane wyniki testów, nie stała `true` w skrypcie.

## Wyniki i granice

Raport, logi i XML: `tmp/a43-http-126/report.json`.
Końcowy odbiór: **132 Java PASS (15 jednostkowych / 117 PostgreSQL)**
i **80 unikalnych Python PASS**, bez pominięć. Java: protokół GUARD 7,
protokół peer 8, GlobalGuards 13, Peers 74, Workflow 30. Pierwszy przebieg
rozszerzono o scenariusz utraty potwierdzenia COMMIT, a potem powtórzono
cały wskazany zakres na końcowych źródłach. Próby i wcześniejsze pakiety
nie są sumowane. Bramka gotowości nadal **NOT_READY / kod 2**;
kontrole strukturalne i candidate PASS.
Bez nowych migracji: **106 tabel / 1031 kolumn / 198 migracji**.
Nie powtarzano pełnego drill 14 baz/Redis: procedury restore i układ tabel
nie zmieniły się. Regresja jest lokalnym odbiorem części A43.40.

Nadal brak kompletnego pokrycia globalnych blokad, trwałych potwierdzeń
wszystkich źródeł i pełnego protokołu wykonania D02. Odstęp między odczytami
nie jest globalną migawką. Wyniki UNKNOWN, odpowiedzi HTTP lub nawet komplet
obserwacji negatywnych nie zezwalają na mutację. Nie uruchomiono harmonogramu,
automatycznego retry, czyszczenia lub nowego widoku CC. Nie odczytywano
sekretów i nie zmieniano rzeczywistych dev/test/prod.

## Co dalej i co odłożono

Techniczny odbiór tego lokalnego przebiegu jest wykonany. Następna niezależna
praca to trwałe źródłowe potwierdzenia oraz techniczne części protokołu D02
chroniące zgodę, przerwanie i odzyskanie operacji. Rozdzielać obserwację od
upoważnienia do wykonania; nie przywracać starych mutujących entrypointów.

Zgodnie z poleceniem użytkownika zakres wymagający wartości lub decyzji
odłożyć i podjąć następny niezależny krok. D01/P01, D09/SAFE, P09 i rzeczywisty
odbiór ról/rejestru spraw nadal odłożone. Nie zastępować ich wartościami
domyślnymi i nie oznaczać całego A43 jako zamkniętego. A05, A41 oraz zmiany
samych zdjęć pozostają odłożone. Szczegóły braków są w
[raporcie 125](A43_OBSERWACJE_ZRODEL_125.md) i
[dokumencie przekazania](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md).
