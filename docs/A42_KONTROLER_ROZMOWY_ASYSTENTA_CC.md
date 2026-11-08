# A42.15 — kontroler rozmowy z Asystentem CC

Data: 27.10.2026. Pakiet 51 audytu, zrealizowany razem z A42.14.

`scripts/control-center/web/assistant-conversation.js` przejmuje stan rozmowy,
historię i cykl żądania. Moduł nie korzysta z DOM, storage, konfiguracji modeli
ani kluczy AI. Transport jest wstrzykiwany; aplikacja nadal używa chronionego
klienta CC, a odpowiedź renderuje modułem z A42.12.

W danej rozmowie trwa najwyżej jedno żądanie. Kolejny Enter, formularz lub
skrót nie uruchamia równoległego pytania i nie usuwa wpisanego szkicu.
Przycisk wysyłania i skróty są blokowane na czas odpowiedzi, a obszar wiadomości
ma aktualizowany stan `aria-busy`. Pole tekstowe pozwala przygotować szkic.

Wyczyszczenie rozmowy anuluje fetch i usuwa historię. Tożsamość aktywnej tury
chroni również przed transportem ignorującym anulowanie: stara odpowiedź ani
błąd nie wrócą do UI/historii, a stare finally nie odblokuje nowego żądania.
Wygaśnięcie sesji kończy rozmowę i blokuje dalszą wysyłkę do przeładowania
strony. Przycisk czyszczenia nie przywraca wygasłej sesji.

Historia zawiera najwyżej dziesięć wiadomości z zakończonych par pytanie–odpowiedź.
Wcześniej rosła bez ograniczenia w pamięci. Bieżące pytanie jest przekazywane
w `message`, bez powtórzenia w `history`, ponieważ serwer dopisuje je sam.
Błędy transportu i niepoprawne/puste odpowiedzi nie trafiają do przyszłego
kontekstu; pozostają widoczne jako bezpieczny tekst, a wysyłkę można ponowić
ręcznie. Moduł nie wykonuje automatycznych ponowień. Treść poprzednich
wiadomości nadal pozostaje w widoku do wyczyszczenia rozmowy.

## Weryfikacja

- Dziewięć nowych testów JS: jedna aktywna tura, oddzielne pole wiadomości,
  limit historii, reset i ignorowanie anulowania, późny błąd i finally,
  wygaśnięcie sesji, błędy/niepoprawne odpowiedzi, kopie historii i pusty input.
- Cztery nowe scenariusze w Chromium, Firefox i WebKit: blokowanie równoległych
  pytań bez utraty szkicu, czyszczenie podczas odpowiedzi i nowa rozmowa,
  wygaśnięcie sesji podczas odpowiedzi oraz odzyskanie po błędnym payloadzie.
- Wspólny odbiór A42.14/A42.15: **157 JS, 39 CC Chromium, 15 Firefox
  i 15 WebKit — PASS**. Regresja obejmuje renderer, ochronę sesji oraz ustawienia
  AI. Składnia modułów i diff — PASS. CI uwzględnia nowe przypadki.

Raport: `tmp/audit-a42-14-15/report.json`. Logi:
`tmp/a42-14-15-node.log`, `tmp/a42-14-15-chromium.log`,
`tmp/a42-14-15-firefox.log`, `tmp/a42-14-15-webkit.log`.

Przy przyszłym wdrożeniu dostarczyć nowy moduł, `app.js` i `index.html` CC
razem z A42.14. Zmieniono wersję URL wejścia; bez nowych zależności, zmian
backendu, ustawień dostawców, kluczy i zdjęć. Testy korzystały z kontrolowanych
odpowiedzi na własnym serwerze fixture, bez połączeń z modelami zewnętrznymi.
Nie uruchamiano Maven ani wdrożenia dev/prod. Pełne A42 pozostaje otwarte;
A05, całe A41 oraz zmiany dotyczące samych zdjęć pozostają odłożone.
