# A43 — plan zamknięcia pozostałego zakresu

**Niezależny pakiet 155 / A35:** [metryki Redis i panele](A35_REDIS_PENDING_LAG_155.md).
Bieżąca rewizja `a43.40-2026-11-02.8`: przegląd trzech odczytowych odwołań
do Redis (14 → 17), bez nowych magazynów, zmian polityki lub aktywacji.
Bramki PASS; pełna gotowość NOT_READY (kod 2). Ostatnia implementacja A43
i pełny drill nadal pochodzą z 151/rewizji `.6`; poniższe wyniki są historyczne.
Metryki lokalne nie zastępują odbioru rzeczywistych grup i dowodów D04/D05.
Następne niezależne A35: przegląd refund pending.

Stan bazowy: **28.10.2026, branch Audit_0.1, A43.1–A43.23 wykonane**.
Plan bazowy obejmuje **18 etapów: A43.24–A43.41**. Etapem końcowym jest A43.41.

**Status aktualny: A43.1–A43.39 wykonane; pozostają 2 etapy A43.40–A43.41.**
Tabela poniżej pokazuje pozostałe prace, bez zmiany numeracji planu bazowego.

**Punkt wznowienia po pakiecie 151:** [atomowa inspekcja](A43_ATOMOWA_INSPEKCJA_POWIAZANA_Z_WERSJA_151.md) i [przekazanie](PRZEKAZANIE_AUDYTU_STAN_I_DALSZE_PRACE.md).
Rewizja `a43.40-2026-11-02.6`. Order V50: atomowe sprawdzenie wersji i zapis próby inspekcji źródła. Rezerwacja zatwierdza się przed HTTP; zmiana stanu, stop, kwarantanna lub wygaśnięcie odrzucają wynik. Ten sam UUID odczytuje historię bez ponownej wysyłki i odnowienia czasu.
145 tabel / 1307 kolumn / 232 migracje. 82 unikalne Java (75 PostgreSQL / 7 jednostkowych) + 116 Python PASS.
Pełny drill: `tmp/privacy-recovery-9d0077d7/report.json` PASS; 1 krok / 2 wersje bez wznowienia.
Wyłącznie jawna diagnostyka jednego źródła, bez prawa do redakcji lub aktywacji. Pełny D02, rzeczywisty wystawca/sprawy, tożsamości i piecza kluczy pozostają otwarte.
Powiązany A40 HTTP i ocenę indeksowalności wykonano w pakiecie 152. Następne niezależne prace: A35; elementy D02 wymagające decyzji/wartości użytkownika pozostają odłożone.
Pełne globalne dowody i wykonanie D02 nadal niekompletne.
Zakresy wymagające wartości/decyzji użytkownika odłożyć i przejść do następnej
niezależnej pracy, zgodnie z poleceniem z 01.11.2026. Poniższe starsze rewizje
są historią planu, nie aktualną deklaracją gotowości. Czyszczenie nieaktywne.

[Co pozostało przed zakończeniem A43 — decyzje D01–D18, opcje i prace do wykonania](A43_POZOSTALE_PRACE_I_DECYZJE.md).
Lista z 30.10.2026 została uzupełniona o odpowiedzi użytkownika D01–D18 po A44.3.
D03 zatwierdza docelowe 7 dni; D04/D05 wybierają progi 7/30 dni z warunkami
potwierdzenia odbiorców. D02/P02: redakcja po dokładnie 365 dniach, następnie
minimum przez 90 dni od faktycznej, potwierdzonej redakcji, z ochroną zależności.
Bieżące parametry D01 i pozostałe braki opisuje rejestr decyzji.
D10/D13/D16 odłożone; P09 pozostawia wyjątki nierozstrzygnięte. D14: według
wiedzy użytkownika tylko tryb wbudowany AI, konfiguracja/historia do weryfikacji.
D17=A: najpierw odbiór lokalny, bez aktywacji. Rewizja macierzy i walidatora
`.2` zawiera maszynowy zapis 18 decyzji, zgodny przykład observe i blokady
otwartych zakresów; 15 testów bramki przeszło. Nie wdrożono nowego workera D02.
A43.40 nadal w toku; końcowa gotowość zwraca oczekiwane NOT_READY.
**Nowsza rewizja `.3`:** mapa 10 tabel/127 pól D02, zatwierdzona redakcja
20 pól (w tym trzech pól dostawy) i kwalifikator w pamięci; 159 testów Java
i 17 Python PASS. Następne wykonanie: trwałe źródła dowodów i generacji,
migracje i ograniczone workery, odbiór baz/współbieżności/restore. Nie zmieniono
runtime i nie aktywowano czyszczenia.

**Aktualna rewizja `.4`:** trwałe metadane i ograniczony wykonawca oceny D02;
migracja V27 wyłącznie na jednorazowym PostgreSQL. Rejestr 87 tabel/775 pól,
47 mechanizmów. Odbiór: 12 nowych i 10 regresyjnych testów PostgreSQL,
12 kwalifikatora Java, 54 Python PASS. Następne wykonanie: zaufane źródła
dowodów i globalne unieważnianie, restore oraz workery danych biznesowych.
Gotowość nadal NOT_READY; pozostałe decyzje i dowody bez zmian.

**Poprzednia aktualizacja 31.10.2026, rewizja `.1`:** transport unieważniania dla siedmiu
usług, domyślnie wyłączony, oraz integracja unieważniania ocen D02 z izolowaną
kwarantanną restore. Odbiór: 43 Java, 62 Python i pełny drill 14 baz/Redis PASS.
Nowych tabel brak; nadal 87 tabel/775 pól i 47 mechanizmów. Następny zakres:
trwałe źródłowe outboxy zawiadomień i świeże dowody procesów/blokad oraz
wykonawcy biznesowi. Brak aktywacji i brak zgody na dopuszczenie restore do ruchu.

**Poprzednia aktualizacja 31.10.2026, rewizja `.2`:** atomowy zapis unieważnień Returns
na zmianach zgłoszeń/pozycji/zdarzeń, binding z oczekiwaną epoką, ograniczony
nadawca bez harmonogramu i trwała blokada źródła po izolowanym restore.
Dwie nowe tabele/25 pól: rejestr **89 tabel/800 pól**, 47 mechanizmów.
Odbiór: 60 Java, 66 Python oraz pełny drill 14 baz/Redis PASS.
Raport: `tmp/audit-a43-40-source-outbox/report.json`.
Następny zakres: pozostałe sześć źródeł, trwały koordynator bindingów
i podłączenie publikatorów, świeże dowody procesów/blokad, workery biznesowe
i odbiór łańcucha. A43.40/A43.41 nadal otwarte; brak nowej aktywacji.

**Poprzednia aktualizacja 31.10.2026, rewizja `.3`:** atomowe zawiadomienia rozszerzono
na Inventory (rezerwacje/pozycje), Promotion (rezerwacje/wykorzystania kuponów)
i Delivery (przesyłki i synchronizacja). Izolowany restore blokuje cztery
źródła, z osobnym wynikiem dla każdego; częściowy schemat zatrzymuje przebieg.
Rejestr **95 tabel/875 pól, 168 migracji**, 47 mechanizmów.
Odbiór: 60 Java, 68 Python oraz pełny drill 14 baz/Redis PASS.
Raport: `tmp/audit-a43-40-sources-ipd/report.json`.
Następne źródła: Payment, Billing, Notification; następnie koordynacja
bindingów/publikacji i świeże dowody. A43.40/A43.41 nadal otwarte, bez aktywacji.

**Poprzednia aktualizacja 31.10.2026, rewizja `.4`:** zawiadomienia Payment, Billing
i Notification, także powiązane inboxy/outboxy i identyfikatory zdarzeń.
Trwałe i typowane powiązania zamówień, bez przypisywania konta/czatu przez
kolizję numeru lub e-mail. Restore obejmuje wszystkie siedem źródeł.
Rejestr **101 tabel/950 pól, 171 migracji**, 47 mechanizmów.
Odbiór: 103 Java, 68 Python i pełny drill 14 baz/Redis PASS.
Raport: `tmp/audit-a43-40-sources-pbn/report.json`.
Dalej: trwała koordynacja bindingów/publikatorów, świeże zaufane dowody,
workery biznesowe i odbiór łańcucha. Ochrona D01 i brak aktywacji bez zmian.

**Poprzednia aktualizacja 31.10.2026, rewizja `.5`:** trwały koordynator siedmiu komend
bindingu, niezmienne oczekiwane epoki z potwierdzeń i blokowanie nowej generacji
przy nierozliczonym skutku poprzedniej. BOUND nie jest pozytywnym dowodem.
Restore blokuje komendy i zachowuje ich historię/próby. Jedna tabela/19 pól;
rejestr **102 tabele/969 pól, 172 migracje**, 47 mechanizmów.
Odbiór: 56 Java, 72 Python i pełny drill 14 baz/Redis PASS.
Raport: `tmp/audit-a43-40-binding-coordinator/report.json`.
Dalej: uwierzytelniony transport bindingów i podłączenie publikatorów,
zaufane dowody i workery biznesowe. Nadal lokalnie, bez aktywacji czyszczenia.

**Poprzednia aktualizacja 31.10.2026, rewizja `.6`:** uwierzytelniony HTTP bindingu,
kontroler źródła, poprawiona trasa unieważnień w filtrze i opt-in publikatory
Order/siedmiu źródeł. Ścisły echo zakresu i limit 2 KiB, brak nowego sekretu.
Domyślne flagi false; claim i ACK źródła respektują restore_blocked.
Schemat bez zmian: **102 tabele/969 pól, 172 migracje**, 47 mechanizmów.
Raport: `tmp/audit-a43-40-http-publishers/report.json`.
Odbiór: 209 wywołań Java, 72 Python i pełny drill 14 baz/Redis PASS.
Dalej: zaufane dowody procesów/blokad/rejestru, przygotowanie generacji,
workery biznesowe i odbiór środowiskowy. Bez aktywacji i bez czyszczenia.

**Poprzednia aktualizacja 31.10.2026, rewizja `.7`:** pierwszy zaufany lokalny dowód
Order i źródłowy znacznik zamknięcia V29, bez historycznego backfillu i bez
podłączenia kolektora do runtime. Zmiany procesu unieważniają czas; restart,
zmiany kontaktu i ponowna generacja po samej wersji go nie przesuwają.
Pozytywny dowód obejmuje tylko ORDER; pozostałe siedem źródeł oraz globalne
blokady/rejestr nadal wymagają implementacji. Odtworzone znaczniki są
blokowane z zachowaniem dat, także przy pustym dzienniku.
Rejestr **103 tabele/976 pól, 173 migracje**, 47 mechanizmów.
Odbiór: **85 wywołań Java, 75 Python i pełny drill 14 baz/Redis PASS**.
Raport: `tmp/audit-a43-40-order-evidence/report.json`.
Gotowość NOT_READY / kod 2; A43.40/A43.41 otwarte, D01 i D17 bez zmian.
Brak aktywacji i czyszczenia rzeczywistych danych.

**Poprzednia aktualizacja 31.10.2026, rewizja `.8`:** lokalna obserwacja Inventory
z dokładną zgodnością ID/stanu i produktów/ilości oraz źródłowym zegarem V13.
Dwa nowe pola istniejącego rekordu, bez backfillu, TTL i podłączenia runtime.
Odczyt wymaga czystego bindingu w tej samej generacji/wersji/fazie/epoce/rewizji;
restore blokuje użycie czasu, zachowując jego wartość. Wynik nie jest jeszcze
ChainProof w Order i nie zawiera customerId. Rejestr **103 tabele/978 pól,
174 migracje**, 47 mechanizmów. Odbiór: **39 Java, 75 Python i pełny drill
14 baz/Redis PASS**. Raport:
`tmp/audit-a43-40-inventory-evidence/report.json`; gotowość NOT_READY / kod 2.
Dalej: zaufany odbiór Inventory w Order, dowody sześciu pozostałych źródeł,
globalnych blokad/rejestru i workery biznesowe. Bez aktywacji i zmian decyzji.

**Aktualizacja 31.10.2026, rewizja `.9`:** chroniony HTTP Inventory i
zaufany odbiór w Order z oczekiwaniami z własnej bazy, HTTP poza transakcją
oraz ponowną kontrolą właściciela/wersji/generacji/bindingu przed zapisem.
Flaga domyślnie false; brak automatycznego kolektora i czyszczenia. Dowód
jest migawką do oceny, nie barierą przed przyszłymi zapisami. Schemat bez
zmian: 103 tabele/978 pól, 174 migracje. Raport:
`tmp/audit-a43-40-inventory-bridge/report.json`.
Polecenie ukończenia A43 nie zmieniło decyzji: D01 nadal chronione,
D09/SAFE i P09 nadal do ustalenia. [Raport odbioru końcowego — NOT_READY](A43_ODBIOR_KONCOWY.md).

**Aktualizacja 31.10.2026, rewizja `.10`:** wspólne adaptery i chroniony
odbiór sześciu pozostałych źródeł. Trwałe zegary po końcu transakcji,
kanoniczny hash/liczba zdarzeń Payment powiązane z generacją i wymagane
w Billing/Notification. Brak automatycznego potwierdzania nieobecności,
historii bez dat, Stripe i skutków Returns COMPLETED. Flaga domyślnie false;
brak globalnych potwierdzeń blokad i workerów D02. Rejestr 104 tabele/
1000 pól, 181 migracji. Raport tmp/audit-a43-40-six-peer-evidence/report.json.
D01/D09/P09 i odłożone zakresy bez zmian; A43.40/A43.41 nadal otwarte.

Plan oparto na [rejestrze danych](../scripts/qa/contracts/data-lifecycle.json):
14 właścicieli baz, 49 tabel, 123 migracje, pięć przestrzeni Redis i 15
rodzajów kopii. Aktualizacja A43.30: 127 migracji,
37 mechanizmów; macierz obejmuje 562 kolumny, Redis, kopie i pominięcia; liczba tabel i kopii bez zmian. Aktualizacja A43.31: 52 tabele/592 kolumny, 128 migracji, 38 mechanizmów i 16 kopii. A43.32: 39 mechanizmów, bez nowych tabel/migracji/kategorii kopii. A43.33: 68 tabel/653 kolumny, 142 migracje, 40 mechanizmów i 17 kopii. A43.34: 70 tabel/666 kolumn, 145 migracji, 41 mechanizmów i 17 kopii. A43.35: 75 tabel/686 kolumn, 151 migracji, 42 mechanizmy i 17 kopii. A43.36: 84 tabele/740 kolumn, 161 migracji, 43 mechanizmy, 6 przestrzeni Redis i 17 kopii. Zasoby dodane podczas realizacji muszą
otrzymać wpis w rejestrze i zostać odebrane w etapie, który je wprowadza.

## Podział i sposób prowadzenia prac

| Obszar | Etapy | Liczba | Wynik |
| --- | --- | ---: | --- |
| 1. Brakujące eksporty domenowe | A43.24–A43.28 wykonane | 0 | Pokrycie płatności, faktur, czatu, powiadomień, rezerwacji i kuponów |
| 2. Dane wymagające oceny i redakcji | A43.29–A43.30 wykonane | 0 | Bezpieczna historia oraz decyzja dla danych technicznych i pominiętych pól |
| 3. Zintegrowany eksport konta | A43.31–A43.32 wykonane | 0 | Jeden proces pobrania z manifestem, kontrolą kompletności i interfejsem klienta |
| 4. Usuwanie i anonimizacja | A43.33–A43.36 wykonane | 0 | Wznawialna procedura z ochroną trwających procesów i przed odtworzeniem danych |
| 5. Kopie i odtwarzanie | A43.37–A43.39 wykonane lokalnie; rzeczywiste dowody nadal wymagane | 0 | Dostawcy i kopie poza kontrolą aplikacji |
| 6. Polityki i odbiór | A43.40–A43.41 | 2 | Docelowe reguły, warunki aktywacji i dowód zamknięcia A43 |
| **Razem** | **A43.40–A43.41** | **2** | **Zakończenie zakresu A43** |

Domyślna kolejność jest numeryczna; zależności podano przy etapach.
Zależności wdrożeniowe dotyczą kodu i testów na danych izolowanych. Decyzje
o okresach i aktywacji zbiera się przed zależną operacją, a A43.40 konsoliduje
je i odbiera konfigurację. Odwołanie do A43.40 nie wymaga wcześniejszego
włączenia czyszczenia, aby przygotować i przetestować mechanizm.

Podzadania, migracje, testy, poprawki i dokumentacja mieszczą się w danym
etapie. Nie otrzymują automatycznie kolejnych numerów. Zmiana listy wymaga
zapisania powodu, wpływu na liczbę etapów i decyzji użytkownika.

**Sposób zbierania decyzji — polecenie użytkownika z 28.10.2026:** gdy etap
wymaga wyboru dotyczącego danych, wyświetlić okno z konkretnymi opcjami,
ich skutkami i wskazaną rekomendacją. Odpowiedź jest wymagana przed pracą
zależną od wyboru; w tym czasie można wykonywać prace niezależne. Brak
odpowiedzi nie jest zgodą. Zwykłe wybory implementacyjne rozstrzyga wykonawca.

**Doprecyzowanie po odpowiedziach D01–D18:** podczas wdrażania danego zakresu
pokazać pytania o brakujące parametry i decyzje w oknie z opcjami. Zachować już
wybrane A/B/C; pytać o nieuzupełniony zakres, zegar, lokalizację, opiekuna lub
wyjątek, po sprawdzeniu dostępnych źródeł. D15=A obejmuje zamiar późniejszego
usunięcia historycznego czatu, ale wymaga doprecyzowania rozmów/kryterium wieku,
kopii i trybu realizacji przed operacją. Nie uruchamia automatycznego purge.

Polecenie kontynuacji oznacza realizację całego następnego otwartego etapu,
wraz z jego testami i odbiorem. Nie oznacza aktywacji czyszczenia na danych
użytkownika ani wykonania odłożonych prac. Stan wykonania wskazują adnotacje etapów i checklista audytu.

## 1. Brakujące eksporty domenowe — pięć etapów planu bazowego, wszystkie wykonane

### A43.24 — płatności i zapisane zwroty finansowe

**Wykonane lokalnie 28.10.2026:** 276 Java, 246 Python i odbiór HTTP — PASS.
[Szczegóły zakresu i odbioru](A43_EKSPORT_PLATNOSCI.md).

- Zakres: bezpieczne dane `payments` i `payment_refunds`: kwoty, statusy,
  czasy i powiązanie z własnym zamówieniem. Właściciel potwierdzany w Order;
  brak dopasowania po e-mailu, dostępu operatora/usługi do eksportu konta
  i dołączania zamówień gościa.
- Wykluczenia: sekrety checkoutu, tokeny, klucze idempotencji, surowe webhooki,
  diagnostyka operatora i dane koordynacji. Pozostałe tabele Payment ocenia A43.30.
- Odbiór: ograniczone odczyty, testy dwóch kont/gościa i niedostępnego Order,
  projekcja PostgreSQL bez praw do wykluczonych kolumn, Gateway/OpenAPI,
  brak zmian płatności. Odczyt wyłącznie zapisanych danych, bez żądań do Stripe.
- Zależność: istniejąca kontrola właściciela A43.22; nie wymaga wznowienia A05.

### A43.25 — faktury, pozycje i korekty

**Wykonane lokalnie 28.10.2026:** 276 Java, 246 Python i odbiór HTTP — PASS.
[Szczegóły zakresu i odbioru](A43_EKSPORT_FAKTUR.md).

- Zakres: `invoices`, `invoice_items`, `invoice_corrections`, historyczne dane
  nabywcy i wartości dokumentów. Weryfikacja przez Order, również przy
  brakującym lub niespójnym lokalnym customer_id; nie przypisywać dokumentu
  na podstawie samego adresu odbiorcy.
- Ująć istniejące PDF-y w kontrakcie eksportu bez wystawiania nowych
  dokumentów, korekt i zmiany numeracji. Inbox/outbox ocenia A43.30.
- Odbiór: izolacja kont, odmowa przy nieweryfikowalnej własności, strony
  pozycji/korekt, zachowanie zapisanych kwot i historii; brak nowych zdarzeń
  oraz wpływu na `invoice_sequences`.
- Zależność: istniejąca kontrola właściciela i kontrakt dokumentów Billing.

### A43.26 — rozmowy i wiadomości czatu

**Wykonane lokalnie 28.10.2026 w wybranym wariancie:** 184 Java, 246 Python
i odbiór HTTP — PASS. [Zakres, decyzja i odbiór](A43_EKSPORT_CZATU.md).

**Decyzja użytkownika z 28.10.2026, wybrana w oknie opcji:** osobny eksport
przez token rozmowy. Zachować obecny model bez przypisywania rozmów do kont
i bez dopasowania po e-mailu. Dotyczy rozmów historycznych i nowych;
powiązanie z kontem nie jest częścią przyjętego wariantu A43.26.

- Zakres: `chat_messages`, `chat_entries`, treść i czasy rozmowy po
  potwierdzeniu dostępu przez capability. Zgodnie z wybranym wariantem
  żadna rozmowa nie jest przypisywana do konta, e-maila ani nazwy.
- Dla historycznej rozmowy bez powiązania z kontem zapewnić osobny eksport
  po jej capability. Wiązanie z kontem nie jest wdrażane w przyjętym
  wariancie; ewentualna późniejsza zmiana wymaga osobnego polecenia użytkownika.
- Odbiór: ograniczone strony, izolacja kont/rozmów, wykluczenie token_hash,
  brak wycieku notatek wewnętrznych i brak wysłania nowych powiadomień.
  Rozmowy bez dowodu własności są jawnie opisane w procedurze A43.39.
- Zależność: istniejący model uprawnień czatu; outbox klasyfikuje A43.30.

### A43.27 — powiadomienia powiązane z klientem

**Wykonane lokalnie 28.10.2026:** 297 Java, 246 Python i 11 grup HTTP — PASS.
Decyzja użytkownika: wyłącznie powiadomienia do rozmówcy; bez komunikatów
i metadanych do obsługi. JWT i e-mail nie zastępują tokenu rozmowy.
[Zakres i odbiór](A43_EKSPORT_POWIADOMIEN.md).

- Zakres: bezpieczna projekcja `notifications`, stanu dostarczenia i zachowanej
  treści, z rozróżnieniem treści skompaktowanej. Własność przez event_type
  i graf źródła: Order/Payment lub wpis rozmowy; recipient sam nie wystarcza.
- Inbox i DLQ ocenić z A43.30: nie kopiować surowej diagnostyki ani traktować
  ponownej kopii komunikatu jako nowej wiadomości klienta.
- Odbiór: dwa konta ze wspólnym adresem odbiorcy, powiadomienie z czatu,
  brakujący/nieznany typ źródła, wyczyszczona treść i niedostępne źródło
  uprawnień; brak wysyłek/replay podczas pobierania.
- Zależność: A43.24 i A43.26, istniejący graf zamówienia.

### A43.28 — rezerwacje magazynowe i wykorzystanie kuponów

**Wykonane lokalnie 28.10.2026:** 238 Java, 246 Python i 10 grup HTTP — PASS.
Własność z Order na każdy odczyt, ograniczone projekcje i brak skutków
biznesowych. Dwie migracje indeksów; rejestr 49 tabel / 125 migracji.
[Zakres i odbiór](A43_EKSPORT_REZERWACJI_KUPONOW.md).

- Zakres: `inventory_reservations`, `inventory_reservation_items`,
  `coupon_reservations`, `promotion_redemptions` przypisane do własnych zamówień.
  Zwracać dane dotyczące transakcji, bez kluczy operacyjnych i obcych rezerwacji.
- Dane katalogu, konfiguracja promocji, stany globalne i operacje magazynowe
  otrzymują jawną klasyfikację zakresu; brak relacji z kontem nie uzasadnia
  udostępnienia całej tabeli klientowi.
- Odbiór: indeksowane strony i kontrola właściciela, brak wpływu na stan
  magazynu, TTL rezerwacji, limity kuponów i trwałą ochronę ich wykorzystania.
- Zależność: graf własności Order; brak zmian architektury A41.

## 2. Dane wymagające oceny i redakcji — dwa etapy

### A43.29 — historia bezpieczeństwa, metadane MFA i notatki

**Wykonane lokalnie 28.10.2026:** 360 unikalnych Java (359 regresja + 1
aktualizacja V15), 246 Python i 7 grup HTTP — PASS. Decyzje użytkownika:
jawny podmiot + wąski legacy refresh ID; swobodna treść przez ręczną ocenę
i redakcję. Brak przypisywania historii po e-mailu i wydawania sekretów.
[Zakres, macierz i procedura](A43_OCENA_NOTATEK_I_DANYCH_BEZPIECZENSTWA.md).

- Zakres: własna historia `audit_events`, bezpieczne metadane MFA/WebAuthn
  pominięte przez A43.16 oraz notatki w Review/Returns i czacie. Dla każdego
  pola określić: do eksportu, redakcja, wyłączenie z uzasadnieniem lub obsługa
  ręczna. Nie udostępniać surowych metadata zdarzeń obejmujących wiele osób.
- Oddzielić dane klienta od identyfikatorów operatorów, innych klientów,
  sekretów, kluczy, kodów odzyskiwania i czynników uwierzytelniania.
- Odbiór: przypadki wielu podmiotów w jednym rekordzie i testy negatywne
  redakcji; każda dotychczasowa deklaracja „pending reviewed projection”
  otrzymuje konkretną decyzję oraz obsługę w manifeście/procedurze.
- Zależność: A43.16, A43.19, A43.23 i A43.26.

### A43.30 — dane techniczne, deduplikacja i kompletność klasyfikacji

**Wykonane lokalnie 28.10.2026:** macierz 49 tabel/562 kolumn, 5 magazynów
Redis, 15 kopii i 93 pominięć; 274 Python, 37 regresji Java, oracle 14 baz
i odbiory HTTP — PASS. Wybrano projekcje biznesowe + ręczną ocenę pozostałych
danych, bez nowych eksportów stanów technicznych. Deduplikacja zachowana;
plan tożsamości jest wejściem do A43.36, nie implementacją migracji.
[Macierz i procedura](A43_KLASYFIKACJA_DANYCH_TECHNICZNYCH.md).

- Zakres: `order_sagas`, follow-up/gates/outbox/webhook receipts Payment,
  inbox/outbox Billing, inbox/DLQ Notification, `chat_notification_outbox`,
  streamy Redis oraz pominięte pola wcześniejszych eksportów.
  A43.27 wyłącza wiadomości bezpieczeństwa konta (linki i osobny graf tokenu
  resetu); ocenić pokrycie/bezpieczne metadane bez dopasowania po odbiorcy.
- Dla każdego zasobu/pola ustalić bezpieczną projekcję, pokrycie przez inny
  eksport lub uzasadnione wyłączenie. Zweryfikować pozostałe tabele
  `not-account-scoped`; nie otwierać surowych payloadów lub identyfikatorów
  deduplikacji klientowi. Sam status „pending” nie kończy tej oceny.
- Uwzględnić e-mail w `payment_outbox_events.event_key`: plan migracji
  identyfikatorów i zgodności czytników jest wejściem do A43.36. Samo
  hashowanie/pseudonimizacja nie stanowi dowodu pełnej anonimizacji.
- Odbiór: macierz wszystkich tabel, Redis i pominięć z decyzją, uzasadnieniem,
  właścicielem oraz testem lub udokumentowaną procedurą; ochrona deduplikacji.
- Zależność: A43.24–A43.29 i rejestr A43.15.

## 3. Zintegrowany eksport konta — dwa etapy

### A43.31 — koordynator eksportu i manifest kompletności

**Wykonane lokalnie 28.10.2026:** wybrano Customer DB i godzinę dostępności;
trwałe, ograniczone części, kontrola właściciela, wznowienia/cancel/limity
i manifest 14 właścicieli z jawnymi brakami. Java/Python/PostgreSQL/HTTP — PASS.
[Kontrakt i odbiór](A43_KOORDYNATOR_EKSPORTU.md). Interfejs wykonano w A43.32.

- Zakres: jeden proces eksportu wykorzystujący istniejące oraz nowe
  projekcje, z weryfikacją właściciela, ograniczeniami czasu/pamięci/rozmiaru,
  kontrolą stron, powtórzeniami i możliwością wznowienia bez dublowania danych.
- Manifest obejmuje każdą usługę i decyzje A43.29–A43.30: pobrane dane,
  wyłączenia, braki historyczne, źródła wymagające osobnego dowodu i błędy.
  Awaria usługi nie może zostać oznaczona jako udany pełny eksport.
- Zapisać kontrakt spójności: czasy/granice odczytów i zachowanie przy zmianach
  danych. Pokrycie pełnego zakresu nie daje samo w sobie jednej migawki
  wszystkich baz; nie deklarować takiej gwarancji bez jej implementacji i testu.
- Odbiór: wszystkie źródła rozliczone w manifeście, awaria i wznowienie,
  anulowanie, zmiana konta, limity zasobów; nowe zadania/pliki mają własną
  retencję i wpisy w rejestrze.
- Zależność: A43.24–A43.30 i dotychczasowe A43.16–A43.23.

### A43.32 — pobranie pełnego zakresu w sklepie i odbiór eksportu

**Wykonane lokalnie 28.10.2026:** interfejs PL/EN, wznowienia/cancel/expiry,
bezstratny JSON i manifest, jawne granice częściowego zakresu, izolacja sesji.
227 Node, 274 Python, 38 unikalnych Java, 44 scenariusze czterech przeglądarek,
54 regresje oraz rzeczywisty przepływ Gateway/Customer/PostgreSQL — PASS.
Godzina z A43.31 zachowana; kopia pobrana na urządzenie pozostaje użytkownika.
[Instrukcja i ograniczenia](A43_POBIERANIE_EKSPORTU_KONTA.md).

- Zakres: połączenie interfejsu profilu z koordynatorem, stan przygotowania,
  pobranie wyniku, czytelne PL/EN, błędy i wygaśnięcie. Zachować możliwość
  zrozumienia zakresu oraz odróżnienia eksportu częściowego od zakończonego.
- Pliki dostępne tylko właścicielowi; ustalić czas dostępności i usuwanie
  plików roboczych. Wylogowanie, zmiana konta i spóźniona odpowiedź nie mogą
  ujawnić wcześniejszego pobrania. Nie dodawać zbędnego widoku CC.
- Odbiór: przeglądarki desktop/mobile, sesje, anulowanie/błędy, duży eksport,
  manifest, wykluczenia sekretów i usunięcie plików po wygaśnięciu.
- Zależność wdrożeniowa: A43.31. Politykę dostępności przed aktywacją
  zatwierdza A43.40.

## 4. Usuwanie i anonimizacja — cztery etapy

### A43.33 — protokół żądania i ochrona przed nowymi zapisami

**Wykonane lokalnie 29.10.2026.** Wybrano znacznik tylko z ID, bez TTL
do A43.40, oraz osobny token statusu przez 168h. Bariery 14 usług, trwały
postęp i wznowienia; bez fizycznego usuwania i bez statusu ukończenia.
[Decyzje, API i odbiór](A43_PROTOKOL_USUWANIA_KONTA.md).

- Zakres: zweryfikowane żądanie klienta, podgląd planowanych skutków,
  potwierdzenie, trwały stan zadania, ponowienia i status per usługa.
  Ustalić klasy: usuń, anonimizuj, zachowaj do terminu, zablokuj do zakończenia
  procesu, obsłuż ręcznie. Nie implementować ogólnego DELETE wszystkich relacji.
- Najpierw zablokować nowe operacje powiązane z usuwanym kontem i unieważnić
  dostęp. Zachować minimalny znacznik chroniący przed ponownym utworzeniem
  danych, z określonym zakresem/retencją i dostępem; nie nazywać go anonimowym.
- Odbiór: wymagane potwierdzenie, równoległy zapis/logowanie, awaria procesu,
  wznowienie, powtórne żądanie, brak fałszywego statusu „usunięto wszystko”.
  Decyzje dotyczące zachowania dokumentów są jawne, bez zgadywania okresów.
- Zależność: macierz A43.30 i zakres danych ustalony do A43.32.

### A43.34 — profil, uwierzytelnianie i treści klienta

**Wykonane lokalnie 29.10.2026.** Cztery adaptery rdzenia, wybrana
obsługa Redis i osobny wniosek czatu po tokenie ze statusem MANUAL_REVIEW.
Ręczną realizację czatu domyka A43.39; nie uruchomiono automatycznego
kasowania rozmów/powiadomień. Aktywacja czyszczenia domyślnie wyłączona.
[Zakres, decyzje i wyniki](A43_USUWANIE_PROFILU_I_TRESCI.md).

- Zakres: Customer/profil, tokeny, MFA/WebAuthn, wyzwania i blokady Redis,
  Wishlist, log rekomendacji, recenzje oraz czat. Usuwać lub anonimizować
  zgodnie z macierzą, zachowując ochronę przed logowaniem/replay.
- Ująć publiczne treści/oceny i rozmowy zawierające dane innych osób.
  Nie utożsamiać usunięcia profilu z usunięciem wszystkich treści i kopii.
  Kolejki powiadomień obsługuje także A43.36.
- Odbiór: adaptery wszystkich wymienionych usług, ponowienia po przerwaniu,
  brak aktywnych sekretów i odtworzenia konta przez stary token/zapis.
- Zależność wdrożeniowa: A43.29 i A43.33. Aktywacja wymaga decyzji retencji
  konsolidowanych w A43.40.

### A43.35 — dane zamówień i procesy domenowe

**Wykonane lokalnie 29.10.2026.** Wybrana polityka kontaktu/adresu i zamkniętych
treści Returns, pięć adapterów, trwały postęp i blokady zapisów. Aktywne lub
niejasne procesy czekają. Rzeczywista bramka finansowa pozostaje zamknięta
do wspólnego odbioru w A43.36; aktywacja/retencja A43.40.
[Zakres i dowody](A43_USUWANIE_DANYCH_ZAMOWIEN.md).

- Zakres: Order, Inventory, Promotion, Delivery, Returns: identyfikatory
  klienta, kopie kontaktu/adresu i treści zgłoszeń, powiązania oraz znaczniki
  techniczne. Realizować reguły po zamknięciu wymaganych procesów lub jawnie
  utrzymywać zadanie oczekujące na ich zakończenie.
- Chronić aktywne rezerwacje, sagi, dostawy, reklamacje i limity kuponów.
  Żądanie prywatności nie uruchamia automatycznie anulowania zakupu,
  zwrotu pieniędzy ani przywrócenia zapasu.
- Odbiór: aktywny i zamknięty proces każdego rodzaju, blokada/stare zapisy,
  częściowa awaria i powtórzenie; brak naruszenia FK, kwot i deduplikacji.
- Zależność wdrożeniowa: A43.28, A43.30 i A43.33. Wspólny odbiór integracji
  z finansami następuje w A43.36.

### A43.36 — finanse, komunikaty i opóźnione zdarzenia

**Wykonane i odebrane lokalnie 29.10.2026.** Wybrana polityka zachowuje dokumenty i rejestry finansowe, usuwa uzgodnione kopie techniczne i utrzymuje pseudonimowe aliasy bez TTL do A43.40. Siedem trwałych potwierdzeń oraz zgodna wersja Order odblokowują A43.35. Produkcja v2 i czyszczenie pozostają domyślnie wyłączone; dostawcy, aktywne i niejasne procesy czekają. [Implementacja i ograniczenia](A43_FINANSE_I_ZDARZENIA.md). Raport: `tmp/audit-a43-36/report.json`.

Wejście z A43.30: [plan migracji tożsamości](A43_PLAN_MIGRACJI_TOZSAMOSCI_ZDARZEN.md),
w tym event_key z e-mailem, zgodność v1/v2 i zachowanie deduplikacji.
Plan nie oznacza wdrożenia ani zgody na rehash/usuwanie/replay.

- Zakres: Payment, Billing, Notification, outbox/inbox/follow-up/DLQ,
  outbox czatu i streamy płatności/faktur. Ograniczyć dane osobowe w treści,
  odbiorcach i identyfikatorach zgodnie z polityką, chroniąc wymagane dokumenty.
- Zrealizować zgodną migrację kluczy zawierających e-mail, trwałą ochronę
  przed duplikatami i sprawdzanie żądania usunięcia przez producentów,
  konsumentów oraz replay. Chronić wymagane grupy i pending Redis.
- Odbiór: stary komunikat po anonimizacji, utrata odpowiedzi, ponowienie,
  mieszane wersje czytników i restart workerów nie odtwarzają danych,
  nie dublują faktur/płatności/wysyłek i nie usuwają potrzebnego zdarzenia.
  Pozostawione pseudonimowe identyfikatory mają jawne uzasadnienie/retencję.
- Zależność: A43.24–A43.25, A43.27, A43.30, A43.33 i A43.35.
  Bez zewnętrznych operacji Stripe i bez wznowienia A05.

## 5. Kopie i odtwarzanie — trzy etapy

### A43.37 — backupy, trwałość Redis i odtworzenie po usunięciu

**Wykonane i odebrane lokalnie 29.10.2026.** Niezależny szyfrowany i podpisany
rejestr oraz potwierdzenie przed czyszczeniem; izolowane odtworzenie 14 baz
i RDB, ponowienie barier i zakresu core, unieważnienie JWT/refresh/resetów
i krótkich wyzwań MFA, zachowanie trwałego MFA innych kont oraz biznesowych
streamów/grup/PEL/deduplikacji. Domyślnie wyłącznie podgląd wygasania kopii.
Odtworzenie pozostaje w kwarantannie (`releaseAllowed=false`), bez odbioru
wszystkich procesów finansowych i bez automatycznej obsługi surowego AOF.
Konfiguracja własnego miejsca, kluczy i niezależnej pieczy aktualnego
wskaźnika pozostaje ręczna; okresy i aktywacja w A43.40.
[Instrukcja](A43_BACKUPY_I_REJESTR_USUNIEC.md), raport
`tmp/audit-a43-37/report.json`. Rejestr po A43.37: 85 tabel, 749 kolumn,
163 migracje, 44 mechanizmy, 6 przestrzeni Redis i 18 kategorii kopii.

- Zakres: `platform-recovery-bundles`, `legacy-database-backups`,
  `redis-durability`. Zdefiniować wygaszanie całych kopii i ponowne zastosowanie
  żądań usunięcia po restore, przed udostępnieniem odtworzonego środowiska.
- Minimalny rejestr wykonanych żądań musi przetrwać odtworzenie starszej kopii;
  jego dostępność, integralność, retencja i ochrona są częścią projektu.
  Nie obiecywać selektywnego wymazania osoby z istniejącego archiwum/AOF/RDB.
- Odbiór: kopia sprzed usunięcia przywrócona w izolacji, zastosowanie zmian
  i brak ponownego ujawnienia danych; brakujący rejestr blokuje udostępnienie.
  Udokumentować stare formaty, terminy kopii i brakujące klucze.
- Zależność: A43.33–A43.36 oraz istniejące procedury odtwarzania A36.

### A43.38 — kopie wewnętrzne, cache i stan urządzeń

**Wykonane i odebrane lokalnie 30.10.2026.** Przyjęto trzy decyzje użytkownika:
nowe metadane wysyłek bez treści/osób, zachowanie legacy do oceny oraz
niezależnych danych gościa i zapisów odzyskania checkoutu. CC unieważnia
bieżącą sesję, usuwa widoczne dane i nie przyjmuje spóźnionych odpowiedzi;
zachowuje inne sesje/zadania. Cache PDF zwalnia wygasłe wpisy także bez odczytu,
a porcjowe wygaśnięcie eksportu odebrano na PostgreSQL. Ewidencja obejmuje
11 zasobów; dodano istniejącą wcześniej kopię `cc-browser-intents` z A13/A21.
Obecnie 19 kategorii kopii i 45 mechanizmów; tabele/kolumny/migracje bez zmian.
Źródłowy brak konfiguracji zewnętrznych loggerów nie zastępuje dowodu runtime.
Okresy/aktywacja i wycofanie kluczy pozostają A43.40; pełne A43 otwarte.
[Macierz, procedury i ograniczenia](A43_KOPIE_WEWNETRZNE_I_STAN_URZADZEN.md),
raport `tmp/audit-a43-38/report.json`.

- Zakres: logi zadań CC, historia wysyłek CC, logi aplikacyjne, monitoring,
  cache PDF, stan przeglądarki, sesje operatorów oraz nowe pliki/zadania eksportu.
  Ustawienia AI i keyringi uwzględnić bez eksportowania ich sekretów.
- Ustalić procedury ograniczania, wygaszania, redakcji i czyszczenia kopii,
  z uwzględnieniem blokad pisarzy, aktywnych sesji i kluczy potrzebnych do
  starszych szyfrogramów. Nie przypisywać całego logu/IP do jednego klienta.
- Odbiór: rozliczenie każdego zasobu, czyszczenie w izolacji, rotacja logów,
  wygasły cache/eksport i wylogowanie. Dla nieużywanego backendu zapisać
  dowód braku konfiguracji; nie uznawać go za automatycznie pokryty.
- Zależność: A43.31–A43.34 i dotychczasowe limity/retencje CC.

### A43.39 — dostawcy i kopie poza kontrolą aplikacji

**Wykonane lokalnie 30.10.2026.** Podgląd i jednorazowe potwierdzenie zewnętrznej
tury AI w CC, ograniczony rejestr zgód w RAM, procedury pobrań/dostawców i oceny
alternatywnych dowodów czatu oraz status przez obsługę po usunięciu rozmowy.
[Zakres, decyzje i ograniczenia](A43_DOSTAWCY_PLIKI_I_DOWODY_CZATU.md).
Raport `tmp/audit-a43-39/report.json`; 46 mechanizmów, 20 kopii, schemat bez zmian.
Wymagane dowody rzeczywistych odbiorników/realizacji i A05_DEFERRED nadal blokują
pełne zamknięcie A43. Poniżej zachowany zakres i warunki tego etapu.

- Zakres: `assistant-provider-copy`, `mail-payment-providers`,
  `browser-downloads` oraz osobne dowody własności historycznych rozmów.
  Ustalić odpowiedzialność, sposób realizacji żądania i potwierdzenie wyniku
  albo jawne ograniczenie; nie deklarować zdalnego kasowania pobranych plików.
- Dla usług zewnętrznych przygotować procedurę oraz listę wymaganych decyzji
  i dowodów. Jej napisanie nie jest potwierdzeniem usunięcia danych u dostawcy.
- Odbiór: każda kategoria ma właściciela, procedurę, ograniczenia i status.
  Zależność od odłożonego A05 pozostaje jawna; wymagany, niedostępny dowód
  blokuje pełne zamknięcie, zamiast być ukryty w nowym etapie.
- Zależność: A43.26, A43.30, A43.32, A43.37–A43.38; decyzje użytkownika
  dotyczące dostawców. Bez kontaktowania dostawców w ramach samego planu.

## 6. Polityki i odbiór — dwa etapy

### A43.40 — docelowa macierz retencji i warunki aktywacji

**W toku od 30.10.2026, niezamknięty.** Przygotowano macierz 26 polityk
obejmującą 85 tabel/749 kolumn, 6 przestrzeni Redis i 20 kopii oraz bramkę
pokrycia/konfiguracji. Profil techniczny zatwierdzony bez aktywacji; znaczniki
bez automatycznego TTL do udokumentowanego wycofania źródeł replay/restore.
Użytkownik pozostawił nierozstrzygnięte okresy finansowo-handlowe, polityki
obu streamów i docelowy okres bezpieczeństwa SQL, bez zmiany obecnego działania.
Pozostałe reguły i dowody kopii/środowiska nadal wymagane. `--require-ready`
zwraca NOT_READY; strukturalny PASS nie kończy etapu ani nie aktywuje usług.
[Macierz i decyzje](A43_POLITYKA_RETENCJI_I_AKTYWACJA.md), raport częściowy
`tmp/audit-a43-40/report.json`. Numeracja i kryteria poniżej pozostają bez zmian.

- Zakres: każda klasa danych otrzymuje właściciela, cel zachowania, okres
  i początek jego naliczania, sposób usunięcia/kompaktowania, wyjątki,
  blokady procesu oraz zasady kopii. Okresy dokumentów i wymagania prawne
  określa użytkownik z odpowiednim wsparciem; nie wyprowadzać ich z domyślnych TTL.
- Uzgodnić parametry istniejących observe/purge/compact/trim, odbiorców
  streamów, retencję znaczników deduplikacji, zadań eksportu/usunięcia i rejestru
  odtworzeń. Decyzje można zbierać wcześniej; ten etap je konsoliduje.
- Odbiór: wersjonowana macierz bez nierozstrzygniętych okresów/odbiorców,
  walidacja konfiguracji, obserwacja/test czyszczenia w izolacji, monitoring
  i procedura zatrzymania. Dokument wskazuje dokładnie, co nadal wymaga
  aktywacji w danym środowisku. Ogólna kontynuacja nie włącza czyszczenia.
- Zależność: A43.30–A43.39 i decyzje użytkownika; brak decyzji pozostawia
  ten etap otwarty, bez dopisywania nowego numeru.

### A43.41 — końcowy odbiór i zamknięcie A43

- Zakres: uzgodnienie wszystkich wpisów aktualnego rejestru z kodem,
  polityką i procedurami; eksport, usunięcie/anonimizacja, kopie/restore,
  retencja i ograniczenia. Brak nieopisanych zasobów i nierozstrzygniętych
  pominięć. Nowe zasoby wprowadzone przez wcześniejsze etapy też są objęte.
- Odbiór: regresja odpowiednich modułów Java/Python/JS i przeglądarek,
  PostgreSQL/Redis, Gateway/OpenAPI, testy dwóch kont, współbieżności,
  awarii/wznowienia, opóźnionych zdarzeń, dużych zbiorów i restore.
- Rozszerzyć model statusów oraz bramkę A43.15: obecnie dopuszczają wyłącznie
  pending/partial i celowo nie potwierdzają ukończenia. Nowe statusy wymagają
  dowodów implementacji i odbioru; sama zmiana etykiety nie kończy zadania.
- Wynik: jeden raport i checklisty z dowodami. Zamknięcie lokalne jest
  oddzielone od zastosowania konfiguracji/migracji i aktywacji w dev/prod.
  Wymagane nierozstrzygnięte decyzje/dowody z A43.39–A43.40 pozostawiają
  A43 otwarte. Żaden odłożony zakres nie jest oznaczany jako wykonany.
- Zależność: odbiór wszystkich A43.24–A43.40 i regresja A43.1–A43.23.

## Pokrycie usług, Redis i kopii

| Zasób | Eksport/klasyfikacja | Usuwanie/retencja i kopie |
| --- | --- | --- |
| customer-service | A43.16, A43.29 | A43.33–A43.34, A43.38, A43.40 |
| order-service | A43.21, A43.30 | A43.33, A43.35–A43.37, A43.40 |
| inventory-service | A43.28, A43.30 | A43.35, A43.37, A43.40 |
| payment-service | A43.24, A43.30 | A43.36–A43.37, A43.39–A43.40 |
| billing-service | A43.25, A43.30 | A43.36–A43.38, A43.40 |
| notification-service | A43.27, A43.30 | A43.36–A43.39, A43.40 |
| delivery-service | A43.22, A43.30 | A43.35, A43.37, A43.40 |
| promotion-service | A43.28, A43.30 | A43.35, A43.37, A43.40 |
| wishlist-service | A43.18 | A43.34, A43.37, A43.40 |
| review-service | A43.19, A43.29 | A43.34, A43.37, A43.40 |
| recommendation-service | A43.20 | A43.34, A43.37, A43.40 |
| chat-service | A43.26, A43.29–A43.30 | A43.34, A43.36–A43.39, A43.40 |
| returns-service | A43.23, A43.29 | A43.35, A43.37, A43.40 |
| product-service | A43.30: klasyfikacja danych katalogowych | A43.37, A43.40: polityka kopii/danych referencyjnych |
| Redis payment-stream, invoice-stream | A43.30 | A43.36–A43.37, A43.40 |
| Redis login-protection, mfa-challenges | A43.29–A43.30 | A43.34, A43.37, A43.40 |
| Redis gateway-rate-limit | A43.30: bez utożsamiania IP z kontem | A43.37–A43.38, A43.40 |

| Rodzaj kopii z rejestru | Etap odpowiedzialny |
| --- | --- |
| platform-recovery-bundles | A43.37 |
| legacy-database-backups | A43.37 |
| redis-durability | A43.37 |
| cc-job-output | A43.38 |
| cc-dispatch-history | A43.38 |
| cc-ai-settings | A43.38 |
| application-logs | A43.38 |
| monitoring-storage | A43.38 |
| pdf-memory-cache | A43.38 |
| browser-state | A43.38 |
| browser-downloads | A43.39 |
| assistant-provider-copy | A43.39 |
| assistant-egress-approvals | A43.39: jednorazowe skróty w ograniczonym RAM, bez treści i kluczy |
| mail-payment-providers | A43.39 |
| service-keyrings | A43.38 |
| cc-operator-sessions | A43.38 |
| cc-browser-intents | A43.38: istniejące zamiary A13/A21, zachowanie do uzgodnienia; polityka A43.40 |

A43.40 zatwierdza polityki wszystkich powyższych zasobów, a A43.41 sprawdza
ich odbiór. Kopie dodane podczas realizacji trafiają do odpowiedniego etapu
i rejestru, bez pozostawiania ich poza warunkiem zamknięcia.

## Decyzje i zakres odłożony

Decyzje potrzebne do realizacji: okresy zachowania dokumentów/danych,
zasady anonimizacji treści publicznych i rozmów, zakres redakcji notatek,
czas dostępności eksportu, wymagani odbiorcy streamów, cykl backupów,
odpowiedzialność za dostawców i aktywacja na konkretnych środowiskach.
Każdą należy zebrać przed zależną operacją; nie zastępuje jej upływ czasu.

**A05 pozostaje odłożone.** Ręczne uzupełnienie `STRIPE_SECRET_KEY` i
`STRIPE_WEBHOOK_SECRET` w `dev.env.sops` oraz `test.env.sops`, odbiór
sandboxu i operacje Stripe nie są uruchamiane przez A43.24 ani A43.36.
Szczegóły: [otwarte czynności A05](AUDIT_0.1_POSTEP.md#otwarte-czynności-ręczne-użytkownika--a05--stripe).
Jeżeli dowód dotyczący dostawcy jest wymagany do pełnego zamknięcia A43,
pozostaje wskazaną zależnością w A43.39–A43.41 do osobnej decyzji użytkownika.

Całe A41 oraz zmiany samych zdjęć w A27–A31/A40 pozostają odłożone.
Plan nie zmienia infrastruktury, danych aplikacji, polityk runtime ani
statusu wykonanych etapów. Następny etap wdrożeniowy: **A43.40**.


**Aktualizacja 31.10.2026, rewizja `.11`:** lokalny rejestr znanych blokad
zakupu lub zakupów stabilnego ID klienta, idempotentne polecenia, świeży
odczyt pod blokadami zakresów i ponowna kwalifikacja po zmianie rewizji.
Rejestr nie jest pełnym inwentarzem i nie potwierdza globalnej nieobecności
przeszkód. Brak API/nowych uprawnień CC, zwolnienia/TTL lub harmonogramu.
Izolowany restore zachowuje historię i blokuje odtworzone zakresy. Rejestr
obejmuje 106 tabel/1015 pól, 182 migracje. Dowody bieżącego odbioru:
`tmp/audit-a43-40-known-holds/report.json`; szczegóły w
[A43_RETENCJA_DANYCH_HANDLOWYCH.md](A43_RETENCJA_DANYCH_HANDLOWYCH.md).
A43.40 i A43.41 pozostają otwarte; D01, D09, P09 nadal nierozstrzygnięte,
D17 bez aktywacji. Workery danych biznesowych i końcowa regresja pozostają.


**Aktualizacja 31.10.2026, rewizja `.12`, pakiet 115:** kontrolowany wewnętrzny
przebieg jednego zakupu: przygotowanie generacji z niezmiennym poprzednikiem,
jedna obserwacja na krok i ocena wyłącznie wskazanego zadania. Kontrola znanych
blokad i aktualności przed/po odczycie nie jest rozproszoną barierą danych.
Brak publicznego API/CC, pozytywnych globalnych GUARD, harmonogramu i czyszczenia.
Rejestr: 106 tabel/1016 pól, 183 migracje. [Zakres i ograniczenia](A43_PRZEBIEG_OCENY_ZAKUPU.md).
Dowody: tmp/audit-a43-40-targeted-workflow/report.json. A43.40/A43.41 pozostają
otwarte; D01/D09/P09 oraz D17 bez nowych decyzji i bez aktywacji.


**Aktualizacja 31.10.2026, rewizja `.13`, pakiet 116:** odbiór pobierania ocen
na 100 000 zadaniach i optymalizacja planów SQL. Cztery ścieżki kandydatów,
jedna nowa dzierżawa, częściowe indeksy V33, okno klienta do 16 i zachowane
punktowe przejęcie. Bez danych/nowych pól, retencji, aktywacji lub zmian CC.
Rejestr 106 tabel/1016 pól, 184 migracje. [Zakres i ograniczenia pomiaru](A43_WYDAJNOSC_POBIERANIA_OCEN.md).
Dowody tmp/audit-a43-40-claim-scale/report.json; ostatni pełny drill pozostaje
z pakietu 115 i nie był w tym zakresie powtarzany. Pełne A43.40/A43.41 nadal
otwarte; D01/D09/P09 i D17 bez nowych rozstrzygnięć.


**Aktualizacja 31.10.2026, rewizja `.14`, pakiet 117:** przypięta definicja
D02 i adapter FIELD_SCOPE_REVIEWED. Nowe potwierdzenie ma niezmienny skrót;
stare wpisy bez zgodnego powiązania pozostają w historii i są nieznane dla
oceny. Nie potwierdza to polityki zachowania, aktualnego dziennika lub
wdrożonych schematów. Bez aktywacji i innych pozytywnych GUARD. Rejestr
106 tabel/1017 pól, 185 migracji. [Dowód i ograniczenia](A43_DOWOD_DEFINICJI_POL.md).
Raport tmp/audit-a43-40-field-scope/report.json; pełne A43.40/A43.41 i decyzje
D01/D09/P09 nadal otwarte, D17 bez aktywacji.


**Aktualizacja 31.10.2026, rewizja `.15`, pakiet 118:** osobna runda ponownej
weryfikacji ośmiu zapisanych obserwacji przed oceną, z CAS zakresu/rewizji,
kontrolą blokad i zachowaniem dzierżaw, budżetu prób oraz backoffu. Brak
pozwolenia na mutację, atomowej migawki lub rozproszonej bariery. Bez zmian
schematu, retencji, CC i aktywacji. [Zakres i granice](A43_PONOWNA_WERYFIKACJA_OBSERWACJI.md).
Raport tmp/audit-a43-40-revalidation/report.json; ostatni pełny drill pozostaje
z pakietu 117. Pełne A43.40/A43.41 i D01/D09/P09 nadal otwarte; D17 bez aktywacji.
