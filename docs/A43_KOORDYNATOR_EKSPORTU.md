# A43.31 — koordynator eksportu i manifest

Decyzja użytkownika z 28.10.2026: tymczasowe zadanie i ograniczone porcje JSON
w bazie Customer. Dostępność wynosi **dokładnie godzinę od utworzenia**,
bez przedłużania przez odczyt, ponowienie lub wznowienie. Bez Redis i plików
eksportu na dysku serwera. Migracja V18 dodaje wyłącznie trzy nowe tabele;
nie zmienia danych domenowych ani tożsamości/deduplikacji zdarzeń.

## API właściciela

Prefiks `/customers/me/data-export/jobs` (przez Gateway również `/api` i `/api/v1`).
Każde żądanie wymaga bieżącego Bearer klienta, aktywnego konta i zgodnego
id/e-maila w Customer. Operator, usługa i gość nie mogą korzystać z API.
Obce UUID zwraca 404, wygasłe 410. Odpowiedzi mają private/no-store i nosniff.

| Metoda i ścieżka | Zachowanie |
| --- | --- |
| POST `/` | Utworzenie; nagłówek `Idempotency-Key` UUID. Powtórzenie zwraca to samo zadanie. |
| GET `/` | Do trzech własnych niewygasłych zadań; odzyskanie ID i wznowienie po utracie lokalnego stanu. |
| GET `/{id}` | Stan, liczniki, limity, daty, hash polityki; bez tokenów wykonania. |
| POST `/{id}/advance` | Jeden odczyt źródła; `Idempotency-Key` UUID identyfikuje ten krok. Nowy krok wymaga nowego UUID. |
| POST `/{id}/retry` | Przywrócenie nieudanych części do kolejki (maksymalnie osiem prób); kolejne advance z nowymi UUID. |
| POST `/{id}/cancel` | Natychmiast odbiera dostęp do treści i blokuje późny zapis. |
| GET `/{id}/chunks?after=0&size=1` | Od jednej do trzech zapisanych części po stabilnym numerze sekwencji. |
| GET `/{id}/manifest` | Pokrycie, błędy, granice automatycznego zakresu, ocena ręczna i osobny dowód Chat. |

Przerwane wywołanie advance można ponowić z tym samym kluczem. Zapis danych,
zadania potomne, numer części i zakończenie kroku są jedną transakcją.
Unikalność deskryptora zadania i części zabezpiecza przed dublowaniem stron.
Lease zadania wynosi 20 sekund, a token wykonania odrzuca późną odpowiedź
po przejęciu, anulowaniu i wygaśnięciu. Nie trzymamy transakcji podczas HTTP.
Wznowienie po restarcie korzysta wyłącznie z zapisanych zadań i części.

JWT jest używany tylko w bieżącym żądaniu. Koordynator przekazuje go do
stałych istniejących endpointów eksportowych; nie przyjmuje URL od klienta,
nie podąża za URL dokumentu z odpowiedzi ani redirectami. Wąska tożsamość
usługi Customer służy tylko potwierdzeniu własności Order przy braku
opcjonalnego nagłówka Payment/Shipment/Invoice. Błąd autoryzacji, zmiana
źródła i niedostępność nie są uznawane za pusty, poprawny eksport.

## Zakres i znaczenie kompletności

Automatyczne korzenie to profil, bezpieczne metadane i strony historii
bezpieczeństwa, Wishlist, recenzje, zapisany log rekomendacji, zamówienia
i zwroty. Każde zamówienie rozwija pozycje, rezerwacje magazynu i kuponów,
wykorzystania kuponów, płatność/refund, przesyłkę, fakturę, pozycje/korekty,
istniejące reprezentacje PDF i powiadomienia Order/Payment. Zwroty rozwijają
pozycje i zdarzenia. Dane zawsze pochodzą z istniejących projekcji właściciela.
Nie uruchamiamy płatności, reconcile, wysyłek ani rekonstrukcji historii.

JSON przechodzi ścisłą kontrolę wersji, zakresu, pól i zagnieżdżeń; nieznane
pola odrzucają całą część. Sprawdzane są rodzice, malejące kursory i granice
stron. PDF jest ograniczoną reprezentacją istniejącego dokumentu w base64.
Manifest udostępnia hash zapakowanej, przejrzanej polityki klasyfikacji,
liczby decyzji pól według wszystkich 14 właścicieli baz oraz granice kopii.
Zmiana polityki blokuje mieszanie wyniku przy wznowieniu; anulowanie pozostaje możliwe.

`ACTIVE` oznacza pozostałe kroki, `PARTIAL` zakończone przeglądanie z błędami,
`LIMIT_REACHED` osiągnięcie limitu, `READY` przejście całego zakresu automatycznego.
Tylko READY ustawia `automaticScopeComplete=true`. Pole **`complete=false`**
pozostaje zawsze: Chat i powiadomienia rozmówcy wymagają osobnego capability,
swobodne notatki/etykiety/metadata oraz niepokryte dane osobowe wymagają
[ręcznej oceny](A43_KLASYFIKACJA_DANYCH_TECHNICZNYCH.md), a kopie zewnętrzne
i backupy własnych procedur. Brak globalnej migawki; każda część zawiera
czas odczytu i SHA-256 treści. Powtórne pobranie daje zapisany wynik.
Manifest nie przypisuje czatu przez e-mail i nie wydaje sekretów ani kluczy workflow.

## Limity i usuwanie kopii

- 512 KiB UTF-8 na część JSON; PDF do 256 KiB przed base64.
- 16 MiB treści i 4096 kroków na zadanie, źródłowe strony do 50 rekordów.
- Jedno niezakończone zadanie na konto, maksymalnie trzy utworzenia w godzinę.
- Maksymalnie osiem prób kroku; HTTP ma ograniczenie połączenia i całego odczytu,
  budżet kroku osiem sekund oraz propagowany krótszy deadline.
- Scheduler co minutę wykonuje do czterech transakcji. Każda oznacza do 25
  wygasłych zadań, usuwa do 50 części, 100 pustych kroków i 25 pustych,
  wygasłych nagłówków. `SKIP LOCKED` pozwala współpracować replikom.
- Anulowanie odcina treść od razu, fizyczne usuwanie jest porcjowe. Nagłówek
  anulowania pozostaje do pierwotnego wygaśnięcia dla limitu utworzeń.
- Granica godziny dotyczy dostępności API; usuwanie fizyczne jest asynchroniczne
  i zależy od działania Customer. Kopie DB w backupach podlegają A43.37–A43.39.
- Wyłączenie `customer.export.cleanup.enabled` służy izolowanym testom;
  środowiska docelowe domyślnie mają scheduler aktywny. Wygasanie dostępu
  działa w SQL niezależnie od schedulera. Nie zapisuje się JWT ani haseł.

Interfejs klienta A43.32 wykonano: [pobieranie konta w sklepie](A43_POBIERANIE_EKSPORTU_KONTA.md).
Kontrakt uzupełniono o ownerId oraz raw=true/dataJson dla dokładnego SHA-256
i zachowania liczb/ID; domyślne pole data pozostaje obsługiwane. A05, całe A41 i zmiany
samych zdjęć pozostają odłożone. Implementacja nie oznacza wdrożenia dev/prod
ani zakończenia usuwania danych, kopii, Redis lub całego A43.
