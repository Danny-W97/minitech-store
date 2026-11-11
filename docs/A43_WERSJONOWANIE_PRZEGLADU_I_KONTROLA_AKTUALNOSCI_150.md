# A43.40 — wersjonowanie przeglądu i kontrola aktualności, pakiet 150

Stan: **02.11.2026, Audit_0.1, a43.40-2026-11-02.5**.
Zakres lokalny; A43.40/.41 otwarte. Czyszczenie wyłączone.

## Zapis wersji

`CommerceRetentionHoldReviewVersions` zapisuje w Order niezmienny rekord
wersji konkretnego zakupu, wniosku i referencji sprawy. Klient podaje
UUID operacji; zakres, historia i wynik są pobierane z SQL pod blokadą
tego samego rodzica co przegląd 149. Nie przyjmujemy podglądu lub czasu
klienta jako dowodu. Wynik CAPTURED wraca dopiero po COMMIT.

Rekord zawiera identyfikatory i ordinal, wersję protokołu, skróty zakresu,
historii, wyniku i wersji oraz czasy. Nie przechowuje pełnego przeglądu,
treści sprawy, kontaktu lub poświadczeń. Skróty są pseudonimowe, nie anonimowe.
Powtórzenie UUID odczytuje zapisane metadane (ALREADY_CAPTURED), bez nowego
podglądu i odnowienia czasu. Niepewny COMMIT daje UNAVAILABLE bez podglądu;
ponowienie rozpoznaje zapis. Limit: 8 wersji na wniosek, bez resetu budżetu.

Protokół `HOLD_RESTORATION_REVIEW_V1` obejmuje semantykę przeglądu. Jego
istotna zmiana wymaga jawnej zmiany wersji protokołu. SHA-256 ma osobne
domeny i typowane prefiksy długości, stabilną kolejność map i zbiorów
oraz normalizację dat do Instant. Nie zależy od toString, strefy czasu
lub kolejności powodów oceny. To identyfikator stanu, nie podpis lub upoważnienie.

## Co unieważnia ocenę

Zakres obejmuje rodzica, oryginalne plany i potwierdzenia, ID klienta,
wersję zamówienia, znane rewizje blokad, stan Order i znacznik konta.
Historia obejmuje pierwotne próby doręczenia, inspekcje/wyniki/zastąpienia,
intencje, próby napraw/wyniki i stopy. Ograniczone odczyty korzystają
z kluczy wniosku, źródła, podstawy i intencji; zachowują limity 7/56/8.
Szczegóły historii są uwzględniane także wtedy, gdy liczniki i widoczne
podsumowanie pozostają takie same. Czas odczytu reviewedAt przeglądu
nie jest wersją dowodu — nie powoduje pozornej zmiany przy samym odczycie.

`check` wyszukuje wersję w dokładnym zakresie i sprawdza oczekiwany skrót.
W tej samej transakcji ponownie oblicza skróty. Zmiana daje odpowiednio
SCOPE_CHANGED, HISTORY_CHANGED lub RESULT_CHANGED. Obce ID/zakresy,
niezgodny protokół, uszkodzone skróty i błędy SQL nie dają potwierdzenia.
Znana kwarantanna blokuje capture i check, również ponowienie starego UUID.

Ważność trwa najwyżej 15 s od czasu bazy. Dla nadal świeżych odczytów
źródłowych kończy się najpóźniej 15 s po najstarszej obserwacji. Nowy
zapis nie rozpoczyna jej wieku od nowa. Czas sprawdzamy przed i po
odczytach; spóźniona kontrola daje EXPIRED. Cofnięcie zegara przed czas
wystawienia daje CLOCK_CHANGED. Klient nie może przesłać nowej daty.
Nowy UUID wymaga nowego odczytu i zużywa kolejny slot; nie przedłuża
poprzedniego rekordu. Wygaśnięcie nie usuwa historii ani nie zwalnia slotu.

## Granice bezpieczeństwa

CURRENT_LOCAL oznacza zgodność **lokalnego** stanu w chwili sprawdzenia.
Może dotyczyć przeglądu z brakami lub stopem; nadal jest tylko diagnozą.
Nie potwierdza tożsamości, sprawy, pełnej polityki danych, zakończenia
wykonawców, kompletności ani aktualnego globalnego braku blokad.
Zmiana w usłudze po ostatniej inspekcji, jeszcze niezapisana w Order,
nie jest wykrywana przez samo porównanie lokalnej wersji. Potrzebne są
nowe dowody źródłowe i pełny protokół ośmiu uczestników.

Po zwolnieniu transakcji stan może się zmienić. Nie podłączono wyniku
check do workerów i nie dodano callbacku wykonującego mutację. Przyszły
krok musi ponowić kontrolę w swojej transakcji, z właściwymi blokadami,
upoważnieniem sprawy i aktualnymi dowodami; CURRENT_LOCAL nie jest
tokenem wykonania. Publiczne metody odrzucają transakcję wywołującego;
pakietowy snapshot współdzieli wyłącznie wewnętrzną transakcję i DataSource.

Bez endpointu operatora, przycisku CC, schedulera, nowych kluczy lub
konfiguracji dev/test. Rzeczywisty wystawca, tożsamości, klucz publiczny,
rotacja i odwoływanie pozostają do osobnego odbioru użytkownika.

## Schemat, restore i odbiór

Order V49 dodaje `commerce_retention_hold_review_versions`: 11 pól,
niezmienność, sekwencję 1–8, kontrolę czasu i kwarantanny w SQL.
Inwentarz: **144 tabele / 1297 kolumn / 231 migracji**; 47 mechanizmów,
6 przestrzeni Redis, 20 kategorii kopii, 26 polityk. Metadane protokołu
mają zasady D03: brak automatycznego TTL do rozliczenia kopii i ponowień;
surowy eksport automatyczny wyłączony, dostęp wymaga ręcznej oceny zakresu.

Restore zachowuje UUID, skróty, czasy i budżet. Wymaga kwarantanny
rodzica i weryfikuje strukturę, sekwencję oraz kanoniczny skrót wersji.
Nie odnawia ważności ani nie uznaje dawnych skrótów za aktualne dowody.
Historyczny brak tabeli nie udaje istnienia wersji; nieznany lub niespójny
schemat blokuje odbiór. Nie rekwalifikujemy rekordów według dzisiejszego wieku.

**72 unikalne Java (65 PostgreSQL / 7 jednostkowych) + 113 Python PASS**; 45 nowych Java (27 Root PostgreSQL /
11 HTTP / 7 jednostkowych), 27 regresji. Trzy nowe metody Python i osiem
mutacji kontraktu. Bramki/candidate PASS; gotowość NOT_READY / kod 2.
Odbiór z czterech zestawów XML. Po poprawie izolacji znacznika konta powtórzono 27 testów dotkniętego zestawu; pozostałe 45 miało niezależne wyniki PASS przy niezmienionym kodzie aplikacji. Nie doliczano powtórzeń. Odrzucony przebieg i korektę importu testu zachowano.
Pełny drill 14 baz/Redis PASS: `tmp/privacy-recovery-fc1d051a/report.json`;
2 wersje zachowane pod kwarantanną bez odnowienia.
Dowody: `tmp/a43-review-versions-150/report.json`.
Testy używają syntetycznych spraw i izolowanych baz, z rzeczywistym HTTP
siedmiu źródeł. Introspekcja i wystawca są testowe. Migracja wyłącznie
na fiksturach; bez operacji na danych środowisk lub sekretach.

## Punkt wznowienia

Następne: atomowe powiązanie sprawdzonej wersji z przygotowaniem kolejnego
kroku, bez przenoszenia wyniku CURRENT_LOCAL między transakcjami jako
zgody. Następnie kontrolowany protokół wykonania/odzyskiwania D02;
rzeczywiste sprawy, źródłowe dowody i wystawca nadal wymagają odbioru.
D01/P01, D09/P03–P05/SAFE, P09 i role/rejestr odłożone; A05, A41
i same zdjęcia A27–A31/A40 nadal odłożone.
