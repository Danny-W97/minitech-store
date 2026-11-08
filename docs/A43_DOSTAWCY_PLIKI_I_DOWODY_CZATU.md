# A43.39 — dostawcy, pobrane pliki i dowody własności czatu

Stan kodu i procedur: 30.10.2026. Nie kontaktowano dostawców, nie wysłano danych
biznesowych i nie uruchomiono kasowania. `fullA43Complete=false`,
`fullErasureComplete=false`. Okresy i aktywacja pozostają w A43.40, odbiór w A43.41.

## Decyzje użytkownika

1. Każda zewnętrzna tura asystenta CC wymaga podglądu faktycznego komunikatu
   i jawnego potwierdzenia. Zmiana wiadomości, historii lub konfiguracji unieważnia
   potwierdzenie. Nie dołączamy automatycznie nowych danych biznesowych.
2. Rozmowy bez prawidłowego tokenu: tylko ręczna ocena alternatywnych dowodów
   konkretnej rozmowy przez uprawnioną obsługę. Bez wiązania po e-mailu,
   odzyskiwania tokenu lub automatycznego eksportu/usuwania. Niewystarczający dowód
   oznacza sprawę nierozstrzygniętą.
3. Po fizycznym usunięciu rozmowy status przekazuje uprawniona obsługa po
   weryfikacji dowodu. Minimalny rejestr żądania pozostaje; nie powstaje kopia
   hasha tokenu ani dodatkowy sekret statusu.

## Macierz odpowiedzialności

| Kategoria | Odpowiedzialny | Realizacja i dowód | Stan / ograniczenie |
|---|---|---|---|
| assistant-provider-copy | administrator platformy + uprawniony operator | podgląd i potwierdzenie przed wysłaniem; po wysłaniu identyfikacja konkretnej konfiguracji dostawcy i jego procedury obsługi żądania | bramka wdrożona; faktyczne konto, warunki, lokalizacja, retencja i dowód wykonania u dostawcy: REQUIRED_MANUAL_EVIDENCE |
| assistant-egress-approvals | administrator CC | ograniczony rejestr skrótów w RAM, jednorazowe zatwierdzenie w danej sesji | 64 wpisy, 5 minut; nieważne wpisy niedostępne, usuwane przy kolejnej emisji lub zakończeniu procesu; wylogowanie usuwa własny, zapis konfiguracji wszystkie |
| mail-payment-providers | administrator powiadomień/płatności + obsługa sprawy | konkretne ID dokumentu/transakcji i odbiorcy; osobno SMTP CC, SMTP Notification, External API Notification oraz płatności | brak zdalnego kasowania; rzeczywiste odbiorniki, konta i potwierdzenia: REQUIRED_MANUAL_EVIDENCE; zależność A05_DEFERRED |
| browser-downloads | użytkownik urządzenia / administrator zarządzanego urządzenia | osobna kontrola Downloads, kopii, załączników, synchronizacji i kopii urządzenia | USER_MANAGED; aplikacja nie potwierdza zdalnego usunięcia plików |
| historical-chat-proof | uprawniona obsługa | ocena alternatywnych dowodów konkretnej rozmowy i redakcja danych innych osób | MANUAL_REVIEW; brak dowodu blokuje ujawnienie i usuwanie |
| deleted-chat-status | uprawniona obsługa | minimalny rekord żądania i osobno zweryfikowany dowód realizacji | MANUAL_REVIEW; publiczne API traci dostęp po usunięciu rozmowy |

## Bramka AI w CC

`POST /api/ai/chat/preview` waliduje wiadomość i maksymalnie 10 elementów
ukończonej historii. Nie uruchamia lokalnych zapytań biznesowych ani połączenia
z dostawcą. Pokazuje adres docelowy, dostawcę, model i cały faktyczny JSON
wysyłany w danej turze, w tym statyczny prompt systemowy. Nagłówki autoryzacji
i klucze konfiguracji nie są zwracane. Dotychczasowy adapter Gemini nie wysyła
historii — podgląd jawnie to wskazuje; adapter zgodny z OpenAI pokazuje historię.
Historia może zawierać wcześniejsze lokalne odpowiedzi z danymi aplikacji.

Operator czyta podgląd i potwierdza przyciskiem „Potwierdzam i wysyłam tę turę”.
Anulowanie/Escape nie wysyła niczego. Aby zmienić treść lub usunąć historię,
anulować, poprawić pytanie albo wyczyścić rozmowę i wykonać nowy podgląd.
Potwierdzenie jest ważne 5 minut, jednorazowe, przypisane do sesji CC oraz
skrótów całego wejścia, konfiguracji (także klucza) i dokładnych bajtów wysyłki.
Ponowny podgląd zastępuje poprzedni. Brak/stary/obcy token daje 409, bez wysyłki.
Także endpoint lokalnego LLM wymaga podglądu, bo opuszcza proces CC.
Tryb wbudowany pozostaje lokalny. Samo `confirmed=true` nie daje uprawnienia.

Przekierowania nie są śledzone. Odpowiedź ma limit 256 KiB. Nie ma automatycznej
ponownej wysyłki. Błąd sieci nie dowodzi, że dostawca nie otrzymał danych:
`externalDelivery=unconfirmed`, `providerErasureConfirmed=false`.
Zmiana konfiguracji unieważnia oczekujące zgody, lecz nie cofa już rozpoczętej
wysyłki. Wylogowanie/wyczyszczenie rozmowy przerywa żądanie przeglądarki i usuwa
podgląd; nie jest to gwarancja cofnięcia danych już przekazanych odbiorcy.
Serwer nie zapisuje treści podglądów ani kluczy w rejestrze zgód. Rejestr zawiera
tylko skróty, identyfikator sesji i termin; przeglądarka usuwa podgląd z DOM po
zamknięciu okna. Nie tworzymy trwałej historii zgód ani nowego eksportu sekretów.

## Procedura dostawców — osobno dla każdego środowiska i odbiornika

1. Administrator ustala faktycznie używany adapter, konto/tenant, endpoint,
   podmiot odbierający, dalszych odbiorców i region. Sprawdza również przekaźnik
   SMTP, skrzynkę końcową, kopie wysłane/załączniki, kolejki operatora i ewentualne
   proxy AI. Domyślny adres w repozytorium nie jest dowodem konfiguracji runtime.
2. Obsługa sprawy weryfikuje tożsamość i zakres poprzez istniejący protokół konta
   lub odrębny dowód rozmowy. Sam e-mail, numer dokumentu lub dostęp do panelu
   nie dowodzi prawa do wszystkich powiązanych danych. Dane innych osób wymagają
   oceny i redakcji zgodnie z procedurą A43.29.
3. Ustala zakres danych u odbiorcy i faktyczną możliwość eksportu, redakcji,
   usunięcia lub zachowania. Wymaga aktualnych warunków i ustawień konkretnego
   konta oraz zasad kopii/backupów i wyjątków. Ten dokument nie przypisuje
   dostawcom domyślnych okresów retencji ani obietnic usuwania.
4. Dopiero uprawniona osoba, w ramach konkretnego zlecenia, składa minimalny
   wniosek przez zatwierdzony kanał. Nie przesyła całych eksportów, sekretów,
   kart, tokenów rozmów ani surowych logów jako „dowodu”. Nie wysyła ponownie
   wiadomości/płatności w celu sprawdzenia, czy poprzednia kopia istnieje.
5. Do kontrolowanego rejestru obsługi sprawy należy odnotować minimalne ID
   sprawy/transakcji, zakres i bezpieczny kod wyniku, datę oraz odwołanie do
   odpowiedzi dostawcy. Dowody osobowe udostępnia się tylko oceniającej obsłudze;
   nie trafiają do repozytorium, logów CC, automatycznego eksportu ani rejestru
   zgód AI. Taki system obsługi sprawy i jego retencję trzeba wskazać przed
   rzeczywistym użyciem; ten etap nie tworzy nowego magazynu dowodów.
6. Brak odpowiedzi lub brak potwierdzenia zakresu to `UNCONFIRMED`, odmowa lub
   dalsze zachowanie to jawne ograniczenie. Potwierdzenie wysyłki wiadomości,
   usunięcie lokalnego rekordu i poprawny sandbox nie dowodzą usunięcia kopii
   u odbiorcy. Nie oznaczać globalnego `complete/dataErased=true`.

**Zależność A05 pozostaje odłożona:** użytkownik musi ręcznie uzupełnić
`STRIPE_SECRET_KEY` i `STRIPE_WEBHOOK_SECRET` w `dev.env.sops` i `test.env.sops`;
rzeczywisty odbiór sandbox i dowody dotyczące operatora pozostają niewykonane.
Nie odczytywano ani nie zmieniano tych plików w A43.39. Kod Notification ma
również adapter External API; jego ewentualny odbiornik wymaga osobnej oceny.
Mailpit/lokalny endpoint też przechowuje kopie, nawet jeśli nie jest dostawcą
publicznym. Brak użycia danego adaptera trzeba potwierdzić dowodem konfiguracji,
bez publikowania jej sekretów.

## Pobrane pliki

Eksport konta, JSON/CSV/PDF/ZIP, załącznik i kopia pobrana z CC stają się osobnymi
kopiami na urządzeniu. Wygaśnięcie eksportu, usunięcie konta i zwolnienie Blob URL
nie usuwa Downloads. Użytkownik sam wskazuje pliki, kopie, kosz, synchronizację,
załączniki i backupy urządzenia do właściwej obsługi; administrator urządzenia
stosuje uzgodnioną politykę i wyjątki zachowania. Nie skanujemy dysku, nie
kasujemy automatycznie plików i nie obiecujemy wycofania udostępnionych kopii.
Wynik procedury rozróżnia deklarację właściciela od zweryfikowanego zakresu;
nie deklaruje usunięcia cudzych/nieznanych kopii. Okresy pozostają A43.40.

## Historyczny czat bez tokenu i ręczna realizacja

Obsługa najpierw ustala konkretną rozmowę i źródło żądania. Wiedza o nazwie,
e-mailu, temacie czy podobnej treści nie wystarcza. Alternatywne dowody muszą
niezależnie wykazać związek osoby z tą rozmową, np. zweryfikowane pochodzenie
zachowanej korespondencji wraz z niezależnie potwierdzonym powiązaniem sprawy.
To przykłady do oceny, nie automatyczne warunki udzielenia dostępu. W razie
wątpliwości weryfikację ocenia drugi uprawniony pracownik; nierozstrzygnięta
sprawa pozostaje bez eksportu, zmiany tokenu i usunięcia. Nie odzyskujemy tokenu
przez e-mail ani nie przypisujemy rozmów do konta po e-mailu.

Po wystarczającym dowodzie obsługa ocenia zakres wiadomości, wpisów, powiadomień
do rozmówcy i do sklepu, kopii technicznych i danych innych osób. Eksport po
tokenie A43.26–27 obejmuje tylko jego uzgodniony zakres; ręczna ocena nie rozszerza
automatycznie tego API. Otwarte procesy/deduplikacja/obowiązki zachowania wymagają
uzgodnienia. Wniosek A43.34 ma nadal stan `MANUAL_REVIEW` i sam nie usuwa treści.
Przed fizycznym usunięciem trzeba mieć zatwierdzony zakres, ocenione powiązania
i kopie oraz plan wykonania i weryfikacji. Nie uruchomiono takiej operacji na
rzeczywistych danych w tym etapie i nie dodano automatycznego purge rozmów.

`chat_privacy_requests` nie ma FK do `chat_messages`, więc zachowuje wyłącznie
`conversation_id`, `request_id`, `state`, `created_at` po usunięciu rozmowy.
Nie zawiera treści, e-maila ani hasha tokenu. Publiczne GET/POST z dawnym tokenem
zwraca wtedy 404, ponieważ dowód jest sprawdzany w aktualnym rekordzie rozmowy.
Obsługa, po niezależnej weryfikacji dowodu, może sprawdzić minimalny rekord po
konkretnym ID w uprawnionym trybie administracyjnym; nie ma nowego publicznego
endpointu i samo ID nie uprawnia do ujawnienia. Wiersz `MANUAL_REVIEW` nie jest
dowodem wykonania. Wynik przekazuje się dopiero na podstawie zweryfikowanego
dowodu operacji; brak dowodu oznacza brak potwierdzenia. Nie dodajemy nowego
sekretu statusu, hasha tokenu ani pozornej odpowiedzi o sukcesie.

## Dowody potrzebne przed pełnym zamknięciem A43

Kontrakt `scripts/qa/contracts/external-copies.json` rozlicza wszystkie powyższe
pozycje. Statusy REQUIRED_MANUAL_EVIDENCE i A05_DEFERRED są **blokadą pełnego
odbioru A43**, a nie potwierdzeniem wykonania operacji. Użytkownik/administrator
musi dostarczyć faktyczną macierz odbiorników i konfiguracji, właściciela procesu
obsługi spraw i bezpiecznego magazynu dowodów oraz dowody dla konkretnego zakresu
lub uzasadniony brak użycia. A43.40 ustala okresy i aktywację. A43.41 wymaga
zweryfikowania tych zależności; nie tworzymy dodatkowego numeru etapu dla braku
dowodów. Sama gotowość kodu i niniejszej procedury pozwala odznaczyć A43.39,
lecz nie kończy A43 ani nie aktywuje czyszczenia.
