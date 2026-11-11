# A43.36 — wejście: migracja identyfikatorów zdarzeń z e-mailem

**Plan bazowy przygotowany w A43.30; implementacja A43.36 odebrana lokalnie 29.10.2026.** Producent v2 pozostaje domyślnie wyłączony, backlog v1 nie jest przepisywany, aliasy/fingerprinty pozostają bez TTL do A43.40. [Bieżąca implementacja i ograniczenia](A43_FINANSE_I_ZDARZENIA.md). Dalsza treść zachowuje historyczne wejście projektowe; bez wznowienia Stripe A05.

## Historyczny protokół v1 i ryzyko

PaymentOutboxEvent tworzy event_key jako `eventType:orderId:recipient`.
EventIdentity.payment wyprowadza eventId jako `payment-` + SHA-256 tego
klucza. Publisher wysyła jednocześnie eventId, eventKey i dane odbiorcy.
EventIdentity.id preferuje eventId, w starszych wiadomościach używa
eventKey, a w dalszej zgodności stream+recordId. Fingerprint obejmuje
wszystkie pola poza eventId, w tym eventKey i recipient.

Billing i Notification sprawdzają zapisany payload_hash przed ponownym
przyjęciem zdarzenia. Zmiana eventKey przy zachowaniu eventId może dać
konflikt fingerprintu; zmiana obu może wyglądać jak nowe zdarzenie i
powtórzyć skutek. Usunięcie inboxu/aliasu/unikalności dla „anonimizacji”
osłabia ochronę. Kompaktowanie opublikowanego outboxu nie usuwa e-maila
z historycznego event_key. Hash nadal łączy się ze sprawą i nie dowodzi
anonimowości.

## Sekwencja do zaprojektowania i odebrania w A43.36

1. Ustal wersjonowany, trwały event_id bez e-maila w kluczu: zapisany UUID
   albo jednoznaczny klucz biznesowy z typem/rewizją. Nie losuj nowej
   tożsamości przy każdym publish/retry. Rozstrzygnij politykę aliasów,
   wersjonowania i ich utrzymania przed zależną operacją na danych.
2. Oddziel tożsamość i fingerprint biznesowy od przejściowych aliasów.
   Zachowaj ochronę przed ponownym użyciem ID ze zmienioną kwotą/order/
   odbiorcą. Nie ignoruj globalnie różnic w payloadzie. Macierz wersji
   v1/v2 musi określać, które różnice są aliasem, a które konfliktem.
3. Najpierw wdroż czytniki obsługujące starą i nową wersję oraz trwałe
   powiązania tożsamości/fingerprintów. Istniejące rekordy muszą nadal
   trafiać do tych samych inboxów i skutków. Nie usuwać starych hashy,
   bramek/receiptów ani zmieniać unikalności przed odbiorem czytników.
4. Dopiero potem włącz producenta v2 dla nowych zdarzeń. Stare pending/
   retry publikuj w ich dotychczasowej wersji i tożsamości. Nie odtwarzaj
   payloadu skompaktowanego outboxu i nie requeue'uj zakończonych rekordów.
5. Odbierz jednocześnie backlog streamów/PEL, grupy konsumentów, inboxy,
   outboxy, DLQ, lease i rekordy follow-up. Zakończenie pracy jednego
   konsumenta nie uprawnia do usunięcia aliasu potrzebnego innemu.
6. Dopiero przy uzgodnionej polityce i trwałym zabezpieczeniu przed
   odtworzeniem można usuwać/anonimizować stare dane identyfikujące.
   Uwzględnij backupy/RDB/AOF, rollback, stare obrazy aplikacji i
   odtworzenie w A43.37–A43.39. Potrzebny jest test powrotu starej kopii
   bez odtworzenia usuniętych danych i bez ponowienia skutku biznesowego.

## Minimalny odbiór zgodności

- v1 z eventId, v1 tylko z eventKey i historyczna ścieżka stream+recordId;
- v2 oraz mieszanina wersji w jednym streamie i backlogu;
- duplikat bez zmian i odpowiednik v1/v2: jeden skutek, bez fałszywego konfliktu;
- to samo ID ze zmienioną kwotą/order/odbiorcą: zachowany konflikt;
- awaria między trwałym commitem a ACK, ponowienie po restarcie i stare lease;
- opublikowany/skompaktowany outbox, zamknięty follow-up i DLQ bez odtwarzania;
- aktualizacja czytników/producentów w różnej kolejności, rollback i odtworzenie;
- potwierdzenie liczby skutków domenowych, nie tylko liczby komunikatów.

Eksport ani obecna klasyfikacja nie wykonują tych operacji. Do A43.36
przenosi się projekt, zależności i wymagane decyzje, a nie zgodę na usuwanie,
zmianę hashy lub replay. Źródła tożsamości są sprawdzane przez bramkę A43.30.
