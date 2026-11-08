# A20 — lista klientów w Control Center

Lista korzysta z autoryzowanego `GET /customers` przez Gateway. API operatora,
filtrowanie SQL i stronicowanie istniały po A29; ten etap domyka prezentację
oraz obsługę błędów w CC.

Tabela pokazuje rzeczywiste pola: ID, e-mail, imię i nazwisko, miasto oraz datę
rejestracji. Usunięto kolumny i sortowanie `username` i `balance`, ponieważ
model klienta nie zawiera takich danych. Wyszukiwanie po e-mailu, imieniu lub
nazwisku działa po stronie usługi. CC pobiera jedną stronę po 50 rekordów,
a sortowanie dotyczy wyświetlanej strony. Kursor kolejnej strony pozostaje
ciągiem znaków.

Panel przekazuje do widoku tylko potrzebne pola. Nie używa zapytań SQL jako
zastępstwa dla nieudanego odczytu API. Nieprawidłowy format odpowiedzi lub
rekordu oznacza błąd odczytu, a nie pustą listę.

Nieudane pobranie pozostawia trwały, widoczny komunikat i przycisk „Ponów
odczyt”. Tabela rozróżnia ładowanie, niedostępność, pusty wynik wyszukiwania
i rzeczywisty brak klientów. Nie pokazuje poprzedniej strony jako aktualnego
wyniku po błędzie. Starszy, spóźniony błąd wyszukiwania nie zastępuje wyniku
nowszego zapytania. Sukces po ponowieniu usuwa komunikat błędu.

CC wymaga własnej sesji panelu i potwierdzenia operatora przez usługę Customer.
Brak uprawnień pozostaje odmową dostępu, błąd odpowiedzi API ma status 502,
a niedostępność usługi lub konfiguracji operatora — 503. Szczegóły odpowiedzi
upstream nie są kopiowane do komunikatu użytkownika.

W Customer wewnętrzny dispatcher `ERROR` jest dopuszczony do obsługi błędów.
Usuwa to maskowanie awarii SQL jako `401`. Bezpośrednie, nieuwierzytelnione
żądanie `/error` nadal podlega zabezpieczeniom.

Zmiana nie wymaga migracji bazy ani nowych sekretów. Do wydania potrzebne są
aktualny panel CC oraz Customer/Gateway z wdrożonym API listy operatora.

## Weryfikacja

- Test zapytania na bazie H2 sprawdza rzeczywiste kolumny klienta, filtr,
  ograniczenie strony i brak duplikatów między stronami.
- Test API potwierdza kontrakt strony operatora, brak pól uwierzytelniania
  oraz odmowę odczytu dla zwykłego klienta.
- Testy HTTP panelu sprawdzają autoryzację, format odpowiedzi, statusy błędów,
  brak fallbacku SQL oraz brak wrażliwych szczegółów w komunikatach.
- Chromium sprawdza błąd, ponowienie, rzeczywisty pusty wynik, właściwe kolumny
  i wyścig między starszym a nowszym wyszukiwaniem.
- `scripts/qa/customer-list-e2e.py` uruchamia prawdziwe Customer i Gateway,
  handler HTTP CC oraz jednorazowy PostgreSQL. Scenariusz obejmuje zasianych
  klientów, uprawnienia, strony i filtr, chwilowy błąd kolumny, odzyskanie
  działania i wyłączenie Customer. Wyłącznie dołączenie tokena operatora jest
  dostosowane do losowego portu Gateway w izolowanym teście.

Regresja została dodana do CI. Zmiany przygotowano lokalnie na `Audit_0.1`.

Odbiór zakończył się poprawnie: 33 testy Customer i 67 wspólnej biblioteki,
102 testy Python/CC oraz 17 scenariuszy Chromium (w tym dwa nowe A20).
Izolowany PostgreSQL i rzeczywiste usługi przeszły 13 kontroli HTTP oraz
sprawdzenie OpenAPI. Raport: `tmp/customer-list-95aa2b3a/report.json`.
Zmian nie wdrażano na dev/prod.
