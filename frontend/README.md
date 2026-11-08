# Frontend sklepu

Dokumentacja frontendu znajduje się w głównym podręczniku projektu:

- [General_Instruction.md — Frontend sklepu](../docs/General_Instruction.md#frontend-sklepu)

Ten plik pozostaje jako wskaźnik dla osób szukających README w katalogu frontendu.

Pamięć koszyka ma wspólny moduł ES używany przez sklep i szczegóły produktu:
[A42.4 — moduł pamięci koszyka](../docs/A42_MODUL_PAMIECI_KOSZYKA.md).
Serwuj pliki przez dotychczasowy serwer HTTP(S), np. `frontend-up` w CC.

Sklep/katalog oraz szczegóły produktu i zamówienia korzystają ze wspólnego modułu
transportu JSON. Przy dostarczaniu zmian uwzględnij `store-client.js` i wszystkie
zmienione skrypty/HTML: [A42.8 — wspólny klient HTTP](../docs/A42_WSPOLNY_KLIENT_HTTP_SKLEPU.md).

Faktury PDF korzystają z tego samego transportu i osobnego modułu cyklu pobierania:
[A42.9 — pobieranie faktur](../docs/A42_MODUL_POBIERANIA_FAKTUR.md).

Widget czatu również używa modułów ES i wspólnego klienta HTTP, z niezależnym
kluczem rozmowy: [A42.10 — transport czatu](../docs/A42_MODUL_TRANSPORTU_CZATU.md).

Konwersja opcji i danych WebAuthn ma osobny moduł bez DOM:
[A42.11 — obsługa passkey](../docs/A42_MODUL_PASSKEY.md).
