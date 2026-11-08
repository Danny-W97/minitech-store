# A43.32 — eksport konta w sklepie

Widok **Profil → Eksport danych konta** korzysta z koordynatora A43.31.
Pozostawiono istniejące, osobne pobranie samego profilu. Nie dodano widoku CC.

Przygotowanie, odszukanie do trzech własnych zadań, wznowienie, ponowienie
niedostępnych źródeł, wstrzymanie w bieżącej karcie i anulowanie są oddzielnymi
działaniami. Otwarcie profilu odczytuje metadane; nie rozpoczyna zbierania.
Wstrzymanie, zamknięcie strony i wylogowanie przerywają pracę przeglądarki,
zachowując zapisany postęp. Anulowanie odcina wynik na serwerze.

Decyzje A43.31 pozostają wiążące: części w bazie Customer, **dokładnie godzina
od utworzenia**, bez Redis i plików roboczych na serwerze. Nie przedłuża się
terminu przez wznowienie/pobranie. Fizyczne czyszczenie DB jest ograniczone
i asynchroniczne. Timer przeglądarki i kontrola serwera odcinają dostęp po
wygaśnięciu. Nie aktywowano retencji danych użytkownika w dev/prod.

## Plik i granice zakresu

`account-data-export.json` zawiera manifest i części z zakończonego zakresu
automatycznego. Dla dostępnego wyniku częściowego powstaje
`account-data-export-partial.json`; manifest można pobrać osobno jako
`account-export-manifest.json`, także przy braku części. Błędy i osiągnięcie
limitu są jawne. READY oznacza ukończenie automatycznego zbierania,
**complete=false** pozostaje prawdziwą informacją o pozostałym zakresie.

Manifest opisuje wszystkie 14 usług, pominięcia, awarie, odrębny token Chat,
ręczną ocenę swobodnych treści i procedury kopii. Nie przypisuje rozmów przez
e-mail, nie eksportuje sekretów i nie obiecuje wspólnej migawki baz.
PDF pozostaje istniejącą reprezentacją w base64 w części JSON.

Kopia pobrana na urządzenie jest zarządzana przez użytkownika. Wygaśnięcie
wyniku w bazie, wylogowanie i anulowanie nie usuwają ukończonego pobrania
z folderu Downloads, kopii pliku ani backupów urządzenia. Widok podaje tę
informację w PL/EN; dalsza procedura kopii i urządzeń pozostaje A43.38–A43.39.

## Integralność, zasoby i sesja

- Koordynator zwraca `ownerId` jako tekst. Klient sprawdza właściciela,
  UUID, wersję, stan, hash polityki, limity i dokładną godzinę dostępności.
- `chunks?raw=true` zachowuje zapisany JSON jako `dataJson` oraz tekstowe
  identyfikatory rodzica/szczegółu. Domyślny kontrakt `data` A43.31 pozostaje.
  Przeglądarka weryfikuje bajty UTF-8 i SHA-256, a dane wstawia do pliku
  bez ponownej serializacji liczb. Zapobiega to zaokrągleniu kwot i ID przez JS.
- Pobieranie idzie po stabilnej sekwencji, do trzech części na żądanie.
  Zbierany jest prefiks określony manifestem na początku pobrania; nowe części
  z równoległej karty nie są dopisywane do tego prefiksu. Nie jest to migawka
  domenowa. Przed utworzeniem pliku ponownie sprawdzane są zadanie i sesja.
- Treść źródłowa do 16 MiB, do 4096 części/kroków, część do 512 KiB,
  plik z opakowaniem do 24 MiB. Strumieniowy transport przerywa odpowiedź
  metadanych ponad 256 KiB, strony części ponad 4 MiB; żądania mają deadline.
  Limit 24 MiB dotyczy bajtów pliku, nie całego heap przeglądarki: tekst UTF-16,
  parsowanie i Blob mogą tymczasowo tworzyć dodatkowe kopie w RAM.
- W przeglądarce tylko ograniczone bufory RAM i krótkotrwały Blob URL.
  Dane/manifest/ID zadania nie trafiają do localStorage, sessionStorage,
  IndexedDB ani service worker. Po odświeżeniu zadanie znajduje się przez API.
- Blob URL zwalniany po sekundzie i natychmiast przy zmianie sesji,
  wylogowaniu, opuszczeniu strony lub wygaśnięciu. Bufory części są zwalniane
  także przy błędzie. Nie da się wycofać już ukończonego pobrania systemowego.
- Kontrola generacji i AbortSignal odrzucają spóźnione odpowiedzi, także po
  wylogowaniu i ponownym logowaniu na to samo konto. Wspólny transport
  zachowuje jeden refresh sesji i klucze idempotencji kroków.
- Widok używa wyłącznie tekstu i lokalnych tłumaczeń. Nie pokazuje surowych
  komunikatów błędu ani treści danych domenowych. Przyciski mają type=button;
  eksport nie zapisuje niezapisanych zmian formularza profilu.

Odbiór i odznaczenia: [checklista audytu](AUDIT_0.1_POSTEP.md).
Usuwanie danych konta, kopie i docelowe polityki nie są zamknięte przez dodanie
interfejsu eksportu. A05, całe A41 i zmiany samych zdjęć pozostają odłożone.
