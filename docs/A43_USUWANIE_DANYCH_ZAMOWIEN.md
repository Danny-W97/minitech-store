# A43.35 — dane zamówień i procesy domenowe

Polityka wybrana przez użytkownika 29.10.2026. Adaptery dotyczą Order,
Inventory, Promotion, Delivery i Returns. Aktywacja i docelowa retencja
wymagają A43.40; pełna integracja finansowa należy do A43.36.

## Zakres i decyzje

Po bezpiecznym zamknięciu i uzgodnieniu procesu usuwamy e-mail oraz imię,
nazwisko, ulicę, kod pocztowy, miasto, kraj i telefon odbiorcy z Order.
Z uzgodnionych zgłoszeń Returns usuwamy e-mail, powód, opis, notatkę
moderatora, notatki zdarzeń i odwołania do autora. W obecnym modelu można
automatycznie zakwalifikować wyłącznie zweryfikowane zgłoszenia REJECTED.
COMPLETED bez zaimplementowanego uzgodnienia skutków domenowych pozostaje
do oceny; REQUESTED, UNDER_REVIEW i APPROVED czekają na zakończenie.

Zachowujemy stabilne ID klienta, zamówienia i pozycji, kwoty, ilości, stany,
klucze procesów i hashe deduplikacji. Tracking i kody kuponów także pozostają.
Są to dane pseudonimowe, nie anonimowe; retencję ustala A43.40. Nie zwalniamy
limitów kuponu, nie przywracamy zapasu, nie anulujemy zakupu ani płatności.
Nazwy produktów i pozostałe swobodne kopie wymagają przeglądu w kolejnych
etapach; adapter nie deklaruje całkowitego usunięcia danych osoby.

## Sterowanie i ochrona zapisów

`privacy.commerce-cleanup.enabled` / `PRIVACY_COMMERCE_CLEANUP_ENABLED`
jest domyślnie false w Customer i pięciu usługach. Ustawienie true nie
zastępuje potwierdzenia planu ani dowodu uzgodnienia finansów.
Nowe potwierdzenie wiąże oddzielny hash polityki A43.35. Historyczne
potwierdzenia bez tego hasha nie są automatycznie rozszerzane.

Worker Customer przetwarza maksymalnie cztery krótkie kroki na przebieg.
Wszystkie 14 barier konta muszą być potwierdzone. Każda usługa przechowuje
postęp w Customer: jeden kursor zamówienia i stan. Jedno wywołanie obejmuje
jedno zamówienie, a Returns najwyżej 100 nagłówków albo 100 zdarzeń.
Kursor nie przechodzi ponad oczekujący proces. Oczekiwanie nie wyczerpuje
limitu błędów. Błędy transportu mają osiem prób, dzierżawę i jawne ponowienie
przez operatora; ponowienie nie zmienia zgody ani warunków kwalifikacji.
Postęp przeżywa restart, a spóźniona odpowiedź starej dzierżawy jest odrzucana.

Źródłem własności jest stabilny customer_id w Order, bez dopasowania po
e-mailu. Prywatny endpoint `POST /internal/privacy/commerce-orders/{id}`
przyjmuje wyłącznie Customer i cztery adaptery domenowe. Zwraca metadane
jednego zamówienia bez kontaktu/adresu/treści. Adaptery cleanup przyjmują
wyłącznie SERVICE customer-service; operator i klient nie mogą ich wywołać.

Po pozytywnej kwalifikacji trwały `commerce_privacy_locks` w każdej bazie
zawiera wyłącznie order_id, customer_id i request_id. Blokada doradcza per
order_id pozwala zakończyć już przyjęty zapis, zanim zostanie zapisany
znacznik. Triggery blokują późniejsze zmiany biznesowe rodzica i dzieci,
usunięcie i przenoszenie powiązań. Dopuszczają wyłącznie dokładną redakcję
do NULL oraz techniczne zmiany czasu/wersji. Zredagowane Returns nie mogą
być ponownie modyfikowane ani odtwarzane przez powtórzenie operacji.
Znacznik nie ma automatycznego TTL do A43.40.
Dla zwykłych wierszy zachowano wymagany kontakt/adres oraz autora zdarzenia
Returns; dopuszczenie NULL na potrzeby redakcji nie usuwa tych wymagań.

## Przekazanie do A43.36

Rzeczywisty `PendingCommerceFinanceGate` zawsze zwraca false. Dlatego obecna
aplikacja po aktywacji adapterów może przetwarzać puste zakresy i rozpoznawać
oczekujące procesy, ale zamówienie wymagające finansów pozostaje
`WAITING_FINANCE` / `WAITING_A43_36`, z zachowanymi danymi źródłowymi.
Nie ma przełącznika ani zgody operatora omijających tę bramkę.

A43.36 musi dostarczyć trwały lokalny dowód uzgodnienia finansów i wygaszenia
wszystkich powiązanych procesów, związany z konkretnym ID i wersją Order.
Callback SQL nie może wykonywać HTTP w transakcji. Dopiero taki dowód
pozwoli zamrozić Order i zredagować kopie kontaktu. Czyszczenie Order jest
ostatnim krokiem, po DONE czterech adapterów domenowych. Wspólny odbiór
Payment/Billing/Notification, starych komunikatów i restartów pozostaje A43.36.

Testy fizycznej redakcji używają wyłącznie wstrzykniętego dowodu testowego
w jednorazowych bazach PostgreSQL. Test HTTP rzeczywistych aplikacji musi
potwierdzać oczekiwanie na A43.36, nie pozorne uzgodnienie finansów.
Globalne `complete`, `dataErased` i `fullErasureComplete` pozostają false.

## Odbiór lokalny

Wyniki bieżącego odbioru: `tmp/audit-a43-35/report.json`. Obejmują migracje,
redakcję w ograniczonych porcjach, aktywne i niejednoznaczne procesy, zachowanie
FK/kwot/ilości/kluczy, blokadę późnych zapisów, własność, ponowienia i restart.
Nie uruchamiamy czyszczenia rzeczywistych danych, usług Control Center,
retencji ani zewnętrznych operacji Stripe.

Aktualizacja A43.36, 29.10.2026: rzeczywista bramka czyta teraz trwały dowód siedmiu usług i zgodną wersję Order. [Integracja finansowa](A43_FINANSE_I_ZDARZENIA.md) została odebrana lokalnie; wcześniejszy opis zamkniętej bramki jest stanem historycznego odbioru A43.35. Aktywacja nadal wyłączona do A43.40.
