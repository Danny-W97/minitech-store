# A42.14 — wspólny zapis operacji operatora CC

Data: 27.10.2026. Pakiet 50 audytu, zrealizowany razem z A42.15.

`scripts/control-center/web/operator-intents.js` przejmuje zapis zamiaru
przyjęcia magazynowego i korekty faktury. Wspólny mechanizm odpowiada za
blokadę między kartami, walidację, odczyt, utrwalenie przed HTTP i usuwanie
wyłącznie rekordu o pasującym identyfikatorze. Reguły obu poleceń pozostają
oddzielne. Moduł nie wykonuje HTTP i nie ma dostępu do tokenu CC.

Zachowano nazwy kluczy `minitech-stock-receipt-v1:<profil>:<produkt>` oraz
`minitech-invoice-correction-v1:<profil>:<faktura>`. Dotychczasowe poprawne
zapisy działają bez migracji. Ponowienie zachowuje ID, ilość albo przyczynę,
kwotę i wersję korekty. Zmiana nierozstrzygniętego polecenia jest blokowana.
Kwoty są normalizowane przez BigInt, bez przeliczania na float.

Profil i klucz są przechwytywane przed oczekiwaniem na Web Lock. Zakończenie
operacji usuwa zapis z tego samego profilu, nawet po zmianie bieżącego profilu.
Spóźnione sprzątanie nie usuwa nowszej operacji. Błędny zapis, brak blokad/UUID
albo odmowa zapisu storage blokują nowe polecenie; nie zastępują go innym ID.
Moduł nie zawiera automatycznego retry ani automatycznego czyszczenia błędów.

UI nadal weryfikuje odpowiedź serwera przed sprzątaniem: COMMITTED, rodzaj,
ID i ilość przyjęcia; ID korekty oraz kolejną wersję salda. Niepewna odpowiedź
pozostawia zapis do ponowienia. Dotychczasowa obsługa definitywnego odrzucenia
korekty jest zachowana. Backend, uprawnienia i kontrakty API nie zmieniły się.

## Weryfikacja

- Osiem nowych testów JS sprawdza współbieżność kart, reload, niezależne
  zakresy, zmianę profilu podczas blokady, późne sprzątanie, uszkodzony storage,
  brak wymaganych API, walidację danych i niezależność kopii polecenia.
- Pięć wcześniejszych testów korekt korzysta teraz z rzeczywistego modułu;
  pozostawiono odbiór formularza i bieżącego salda serwera.
- Trzy scenariusze operacji w każdym z trzech silników: przyjęcie po utracie
  odpowiedzi/reload, analogiczne ponowienie korekty oraz blokada uszkodzonego
  zapisu bez POST. Dwa scenariusze są nowe, magazynowy jest regresją.
- Wspólny odbiór A42.14/A42.15: **157 JS, 39 CC Chromium, 15 Firefox
  i 15 WebKit — PASS**. Składnia modułów i diff — PASS. CI rozszerzono
  o przypadki operacji we wszystkich trzech silnikach.

Raport: `tmp/audit-a42-14-15/report.json`. Logi:
`tmp/a42-14-15-node.log`, `tmp/a42-14-15-chromium.log`,
`tmp/a42-14-15-firefox.log`, `tmp/a42-14-15-webkit.log`.

Przy przyszłym wdrożeniu dostarczyć nowy moduł, `app.js` i `index.html`
CC razem z plikami A42.15. URL wejścia ma nową wersję. Bez nowych zależności,
bundlera i zmian zdjęć. Testy używały danych fixture; nie wykonywały operacji
na rzeczywistych usługach. Nie uruchamiano Maven ani wdrożenia dev/prod.
Pełne A42 pozostaje otwarte. A05, całe A41 i zmiany samych zdjęć są odłożone.
