# A15 — niezmienny zamiar zamówienia

Pakiet 28 na `Audit_0.1`, 26.10.2026. Klucz checkoutu jest związany
z właścicielem i treścią biznesową; równoległe ponowienia wskazują jedno
zamówienie, sagę i rezerwację magazynową.

## Kontrakt API

Klient ustala `Idempotency-Key` przed pierwszym POST i zachowuje go przy utracie
odpowiedzi, timeoutach i restartach. Nagłówek ma **1–100 widocznych znaków ASCII,
bez spacji i znaków sterujących**, z rozróżnieniem wielkości liter. Błędny klucz
daje 400 przed wyceną/zapisem. OpenAPI opisuje te ograniczenia.

Brak nagłówka pozostaje obsługiwany: serwer generuje niezależny klucz, więc dwa
takie żądania są osobnymi zakupami. Klient potrzebujący bezpiecznych ponowień
musi przesyłać własny stały klucz. Przestrzeń kluczy pozostaje globalna,
zgodnie z istniejącym UNIQUE; zalecane są losowe UUID.

| Sytuacja | Odpowiedź |
| --- | --- |
| Pierwszy zapis albo zgodne ponowienie | 201 i kanoniczne ID z bieżącym zapisanym stanem |
| Zmieniony zamiar | 409, `ORDER_IDEMPOTENCY_CONFLICT` |
| Aktywny lease/przetwarzane `CREATED` | 409, `ORDER_IN_PROGRESS`, `Retry-After: 1`; ponowić identyczne żądanie |
| Historyczny klucz bez zapisanego zamiaru | 409, `ORDER_IDEMPOTENCY_LEGACY_REVIEW`; najpierw sprawdzić zakup |
| Obcy właściciel/token gościa | 401/403, bez identyfikatora cudzego zakupu |

Kody znajdują się w `details.code`; po kontroli dostępu błędy idempotencji
zawierają `details.orderId`. Klucz nie zastępuje uwierzytelnienia lub tokenu
gościa. Sprawdzenie klienta i e-maila względem uwierzytelnionej tożsamości
nadal poprzedza checkout: cudzy e-mail konta może dać 403 przed porównaniem zamiaru.

## Kanoniczne żądanie

Order zapisuje wersjonowany SHA-256 zamiaru oraz zakres właściciela: ID klienta
albo istniejący hash losowego tokenu gościa. Nie zapisuje dodatkowego JSON
z adresem ani surowego tokenu. Kodowanie pól rozróżnia null i długości tekstu.

Skrót obejmuje koszyk, e-mail, wszystkie pola adresu i telefon, metodę dostawy,
kupon, rodzaj realizacji, wybraną datę, slot, punkt odbioru i żądanie faktury.
Dane faktury i polecenie płatności nadal podlegają osobnej idempotencji A08.

- Pozycje są agregowane po ID i sortowane. Kolejność, podzielenie ilości
  na duplikaty i starszy format `productId/quantity` są równoważne. Niepuste
  `items` zachowuje pierwszeństwo przed starszymi polami. Sprawdzane są dodatnie
  ID/ilości, limit produktów i przepełnienia przed wyceną.
- E-mail nie rozróżnia wielkości liter. Metoda, kupon i rodzaj realizacji
  mają znormalizowaną postać. Brak metody odpowiada `STANDARD_COURIER`, a brak
  rodzaju realizacji wartości dla metody. Brak i false dla faktury są równoważne;
  puste opcjonalne pola oznaczają brak. Pozostały tekst adresu zachowuje treść żądania.
- Przesłane `deliveryCost` pozostaje informacyjne i nie definiuje zamiaru:
  koszt wyznacza Delivery. Bieżące ceny, zapas, naliczony rabat i automatycznie
  wybrana data nie tworzą nowego zamiaru. Jawnie wybrana data jest związana
  z kluczem; null oznacza pierwotny wybór automatyczny również przy ponowieniu.

Zgodne ponowienie czyta zapisaną wycenę bez ponownego pobierania katalogu,
wyceny dostawy lub walidacji kuponu. Może zwrócić zakup już opłacony, przyjęte
pobranie albo anulowany Order; nie tworzy nowego zakupu ani nie przywraca anulowanego.

## Wyścigi i awarie

Skrót, właściciel, Order i saga zatwierdzają się atomowo. Rezerwacje kuponu
i zapasu następują później. UNIQUE rozstrzyga równoległy pierwszy zapis.
Po przegranej transakcji odczyt zwycięzcy następuje poza wycofaną transakcją,
z ponowną kontrolą właściciela i skrótu. Nie powstaje druga saga/rezerwacja.

Jeżeli zwycięzca zatwierdził Order podczas wyceny przegranego, również błąd tej
wyceny może zakończyć się odczytem zgodnego wyniku. Dotyczy to fazy przygotowania
i zapisu; błąd późniejszego wywołania sagi pozostaje błędem do ponowienia.
Zapisane `CREATED` może wznowić istniejącą sagę pod tym samym kluczem.
Aktywny lease daje kontrolowane 409. Utrata odpowiedzi Inventory po skutku
pozwala drugiej replice wykorzystać tę samą rezerwację. HTTP odbywa się poza SQL.

## Sklep i historia

Klucz, fingerprint przeglądarki i token gościa są zapisywane przed POST.
Sklep blokuje wysłanie, jeżeli pamięć sesji nie potwierdza zachowania próby.
Odświeżony koszt dostawy i kolejność koszyka nie obracają klucza. Starsze
fingerprinty przeglądarki w wersji 2 są porównywane po normalizacji bez ceny.
Jawna zmiana pozostałej treści checkoutu nadal może rozpocząć nową próbę.

Przy konflikcie, przetwarzaniu lub wymaganej kontroli historii sklep zachowuje
koszyk i klucz, pokazuje komunikat i numer zamówienia. Nie wysyła płatności
ani nie zastępuje klucza z powodu błędu. Ponowienie nadal podlega wstępnym
kontrolom dostępności i formularza sklepu; jeśli zablokują wcześniejszy zakup,
należy sprawdzić jego zapisany stan. Pakiet nie dodaje procesu ręcznego
odblokowywania zamiaru w CC.

V20 dodaje opcjonalną parę `checkout_request_hash`/`checkout_principal`, bez
zgadywania zamiaru z ceny, wybranej przez serwer daty lub zapisanej nazwy punktu.
Zachowuje historyczne kwoty, klucze, sagi i płatności. Rekord bez skrótu wymaga
sprawdzenia przed ponowieniem POST. Uprawniony GET i operacje Payment po ID
pozostają dostępne na swoich warunkach. Nie obchodzić 409 przez automatyczny
nowy klucz; najpierw ustalić, czy zakup/płatność istnieją. Nie resetować kluczy
lub hashy w bazie w celu ich ponownego użycia.

## Wydanie i weryfikacja

Wstrzymać tworzenie zamówień na czas migracji i wymiany wszystkich replik
Order: stary proces zapisywałby nowe rekordy bez zamiaru. Po V20 uruchomić
aktualny Order i sklep, sprawdzić zgodne ponowienie i konflikt zmienionej treści,
a następnie wznowić checkout. Zmiana kanonizacji wymaga zgodności wersji zamiaru.

- 148 testów Java: 81 Order i 67 uprawnień — `tmp/a15-order-tests.log`.
  Końcowy JAR z OpenAPI — `tmp/a15-order-package.log`.
- 26 JavaScript: utrata odpowiedzi, odświeżony koszt, starszy fingerprint,
  brak pamięci sesji i zachowanie klucza przy 409 — `tmp/a15-js-tests.log`.
- 12 testów checkoutu Chromium na osobnym serwerze z atrapami API —
  `tmp/a15-ui.log`. Uzupełniono brakującą konfigurację metod płatności w atrapie.
- 98 Python/CC — `tmp/a15-python.log`.
- **44 kontrole HTTP**, PostgreSQL i dwa Order: oba wyścigi, właściciel,
  zmienione dane, brak wyceny po akceptacji, granice klucza, migracja, OpenAPI,
  ostatni kupon, pobranie, utrata odpowiedzi zapasu i zabicie procesu Order —
  `tmp/order-idempotency-c3d9caea/report.json`.
- Regresja A14: **92 kontrole HTTP** rezerwacji, wygaśnięcia, awarii i ról —
  `tmp/coupon-reservations-7837cad1/report.json`.

Regresja A15 i raporty są w CI. Zasoby prób były jednorazowe i zostały usunięte.
Zmiany przygotowano lokalnie; nie wdrażano usług na dev/prod ani rzeczywistych
płatności. A05 (sekrety obu profili, webhook i zewnętrzny odbiór Stripe) oraz
zdjęcia pozostają odłożone. Mapa gotowości P1 nadal pokazuje 13/14.
