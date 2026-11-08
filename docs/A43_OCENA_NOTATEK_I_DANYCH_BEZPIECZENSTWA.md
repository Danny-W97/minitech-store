# A43.29 — historia bezpieczeństwa i ręczna ocena treści

## Wybrane zasady

Decyzje użytkownika z 28.10.2026, podjęte w oknie opcji:

- automatyczny eksport nowych zdarzeń z jawnym ID podmiotu oraz starszych
  zdarzeń refresh z jednoznacznym, kanonicznym ID; bez dopasowania po e-mailu;
- bezpieczne metadane automatycznie, a notatki, etykiety i swobodne pola
  przez ręczną ocenę podmiotu i redakcję przed wydaniem.

Specyfikacja pól do kontroli zmian:
[security-export-policy.json](../scripts/qa/contracts/security-export-policy.json).
Ta procedura nie uruchamia odczytu danych użytkownika, retencji ani wysyłek.

## Automatyczne API

GET z JWT właściciela, przez `/api/v1` lub `/api`, albo bezpośredni port Customer:

| Ścieżka po `/customers/me/data-export/security/` | Zawartość |
| --- | --- |
| `methods` | Zapisane daty i stan TOTP lub totp=null |
| `events?size=50&cursor=0` | Bezpieczna historia zdarzeń związanych z kontem |
| `passkeys?size=50&cursor=0` | Daty i stałe flagi poświadczeń WebAuthn |
| `recovery-codes?size=50&cursor=0` | Daty utworzenia i wykorzystania rekordów kodów odzyskiwania |

Każde zapytanie kwalifikuje aktywne konto przez zweryfikowane id i aktualny
e-mail principal w tym samym SQL co wynik. E-mail jest kontrolą bieżącego
uwierzytelnienia, nigdy sposobem przypisania historycznych zdarzeń. Nie ma
wyboru podmiotu przez query/nagłówki, obejścia operatora/usługi ani dostępu
do danych konta wyłączonego/usuniętego. Customer i Gateway odrzucają
takie tożsamości oraz operatora/usługi kodem 401.

Strony zawierają 1–50 rekordów malejąco po id, tekstowy nextCursor (`0` na
końcu), complete=false i listę pominięć. Puste/wyczerpane strony nadal
sprawdzają konto. Odczyty mają timeout SQL 2 s. Stałe nazwy załączników,
private/no-store i nosniff; serwer nie zapisuje archiwum. Strony nie są
wspólną migawką, a historia usunięta przez istniejącą retencję nie jest
odtwarzana. Pobranie nie zmienia konfiguracji MFA, kodów, sesji ani historii.

## Klasyfikacja pól

| Źródło | Automatycznie | Po ręcznej ocenie | Wyłączone lub tylko do kwalifikacji |
| --- | --- | --- | --- |
| audit_events | id, occurred_at, stałe event_type/outcome z listy dopuszczonej, pochodzenie dowodu podmiotu | metadata, client_ip, user_agent, nieprzypisane/nieznane zdarzenia i swobodny kontekst actor/resource | subject_customer_id/actor/resource tylko do kwalifikacji; correlation_id i obce tożsamości nie trafiają do pliku |
| customer_totp_methods | enabled, created_at, updated_at, enrollment_expires_at | brak swobodnej treści | secret_ciphertext i last_used_time_step wyłączone; customer_id/id tylko do kwalifikacji |
| customer_recovery_codes | id rekordu, created_at, used_at | brak swobodnej treści | code_hash i surowe kody wyłączone; customer_id tylko do kwalifikacji |
| user_credentials | export_id, created, last_used, uv_initialized, backup_eligible, backup_state | label i swobodne authenticator_transports | credential_id, public_key, signature_count, attestation_object/client_data_json, operacyjny public_key_credential_type wyłączone; user_entity_user_id tylko do kwalifikacji |
| reviews | istniejący tekst klienta i stan publikacji jak zapisano | moderation_note | order_id poza tą projekcją; nie otwierać całej tabeli ani obcych recenzji |
| return_requests | istniejące własne zgłoszenie, reason/description i stan | moderation_note | klucze/wersje workflow wyłączone |
| return_events | istniejący bezpieczny typ/status/czas | note | actor_ref, operation_id, expected_version, revision i wewnętrzne stany kontroli wyłączone |
| chat_messages/chat_entries | istniejące publiczne wypowiedzi CUSTOMER/SUPPORT przez token rozmowy, jak zapisano | ręczna ocena treści, jeżeli konkretny wniosek jej wymaga | osobne notatki prywatne nie są przechowywane; brak rekonstrukcji; token/hash/request_key i workflow powiadomień wyłączone |

Surowe hasła, sekrety TOTP, klucze/attestation, tokeny i hashe kodów nie są
materiałem do wydania również w procedurze ręcznej. Nie należy odczytywać
ich przy ocenie notatek. Identyfikator export_id WebAuthn jest nowym,
niesekretnym identyfikatorem stronicowania; nie zastępuje credential_id
w uwierzytelnianiu i nie daje prawa do odczytu. Nadany przy migracji export_id
nie jest chronologią utworzenia klucza; rzeczywisty czas pozostaje w created.

## Powiązanie historii z kontem

Nowe znane zdarzenia Customer/Auth/MFA/WebAuthn zapisują subject_customer_id
z obiektu konta, tokenu lub zweryfikowanego argumentu serwisu, osobno od
actor. Nie przepisano wcześniejszych zdarzeń. Wpisy bez rozpoznanego konta
(np. blokada przed odczytem konta, reset dla nieznanego adresu lub niepoprawny
refresh) pozostają nieprzypisane. Wskazanie podmiotu oznacza konto, którego
dotyczy zdarzenie; nie dowodzi tożsamości osoby wykonującej nieudaną próbę.

Automatyczny wyjątek dla starych wpisów wymaga jednocześnie:

1. subject_customer_id IS NULL;
2. actor dokładnie równy kanonicznemu dziesiętnemu ID bieżącego konta;
3. resource = REFRESH_TOKEN;
4. TOKEN_REFRESH/SUCCESS albo REFRESH_TOKEN_REUSE/REJECTED.

`01`, e-mail, anonymous, inny resource/type/outcome nie kwalifikują wpisu.
Jawny subject ma pierwszeństwo: actor wskazujący inne konto nie przenosi
wpisu do jego eksportu. Dwie gałęzie są rozłączne. Zdarzenia o nieznanym
typie/resource/outcome podlegają ręcznej ocenie, bez eksportowania dowolnego
tekstu z przyszłych rozszerzeń. Aktor, surowe metadata i IP/User-Agent nie
są wybierane do automatycznej projekcji. Lista dopuszczona obejmuje istniejące
zdarzenia konta/logowania, resetu/weryfikacji, MFA i rotacji/wylogowania;
dodanie nowego typu wymaga oceny jego znaczenia i aktualizacji kontraktu.

## Procedura ręcznej oceny i wydania

1. **Zarejestruj zakres wniosku.** Potwierdź tożsamość i aktualne konto
   dotychczasowym mechanizmem aplikacji. Dla czatu sprawdź token danej
   rozmowy; JWT/e-mail go nie zastępują. Zapisz zakres źródeł, okres i
   identyfikator sprawy w kontrolowanym rejestrze operatora. Nie proś o
   hasło, sekret TOTP, kod odzyskiwania ani klucz prywatny.
2. **Ustal relację przed odczytem treści.** Review: customer_id. Returns:
   return_requests.customer_id oraz JOIN return_events.return_id do własnej
   sprawy. Chat: dowód capability do konkretnej rozmowy. Audyt historyczny:
   stabilne dowody z konkretnego procesu i osi czasu konta, nie sam e-mail,
   imię, metadata lub podobny numer rekordu. Jeśli dowód jest niewystarczający,
   zapisz brak potwierdzenia i skieruj sprawę do dalszego wyjaśnienia;
   nie przypisuj wpisu automatycznie. Nie zmieniaj bazy dla uproszczenia oceny.
3. **Pobierz minimalny materiał w trybie tylko do odczytu.** Korzystaj z
   uprawnionego stanowiska operatora, jawnych kolumn i ograniczonych stron.
   Warunki customer_id/return_id/conversation_id wynikają z poprzedniego
   kroku. Nie wykonuj SELECT * z tabel poświadczeń, nie łącz po adresie
   odbiorcy i nie kopiuj pełnych baz, logów, Redis ani backupów do notatki.
4. **Oceń każdy fragment i każdy podmiot.** Oddziel informacje o wnioskującym
   od nazwisk, adresów, kontaktów, identyfikatorów operatora/innych klientów,
   spraw innych osób i technicznych kluczy. Zostaw uzasadniony kontekst
   dotyczący wnioskującego. Fragmenty innych osób usuń lub zastąp np.
   `[dane innej osoby usunięte]`. Nie publikuj actor_ref/credential_id.
   Wyrażenie regularne nie zastępuje oceny znaczenia tekstu.
5. **Sprawdź przypadki mieszane.** Notatka „klient A zadzwonił o zamówieniu
   klienta B, obsługiwała osoba C” nie może być wydana w całości klientowi A.
   Zachowaj jego część zdarzenia, usuń dane B/C oraz identyfikatory ich spraw.
   Etykieta klucza „telefon Anny, numer ...” wymaga usunięcia danych innej
   osoby. IP/User-Agent mogą dotyczyć wspólnego urządzenia, pośrednika lub
   innej osoby; nie traktuj ich jako dowodu własności konta.
6. **Wykonaj kontrolę wyniku.** Sprawdź plik po redakcji, jego manifest,
   wszystkie załączniki i metadane pliku. Upewnij się, że nie pozostały
   sekrety, obce tożsamości, ukryte surowe payloady lub odnośniki do nich.
   Zapisz per źródło/pole: wydano / zredagowano / wyłączono / brak dowodu,
   z uzasadnieniem i datą. Wyłączenie nie może być ukrytym „pending”.
7. **Przekaż wyłącznie sprawdzoną kopię.** Potwierdź odbiorcę i uzgodniony
   dla tej sprawy bezpieczny kanał. Surowy materiał i dane operatora nie
   trafiają do klienta. Nie wysyłaj automatycznie e-maili, nie umieszczaj
   materiału w Git, zwykłych logach zadań ani niekontrolowanym storage.
   Dostęp do roboczej kopii oraz jej zakończenie obsłuż zgodnie z uzgodnioną
   polityką środowiska; ten etap nie ustala nowego terminu retencji.

Przykładowy manifest ręczny: źródło/pole, zweryfikowane powiązanie ze sprawą,
decyzja, uzasadnienie, zakres redakcji i data. Rejestr wewnętrzny może wskazać
uprawnioną osobę oceniającą; jej tożsamości i surowych dowodów nie dodaje się
do pliku dla klienta. To procedura operatorska, bez nowego endpointu wydawania
notatek i bez automatycznego uznania, że cała swobodna treść należy do konta.

## Migracje, testy i ograniczenia

Customer V16 dodaje nullable subject_customer_id z warunkiem dodatniości,
bez backfillu po e-mailu i bez FK/CASCADE do konta. Zachowanie audytu po
usunięciu konta rozstrzygną etapy usuwania i polityk; ta migracja nie
zmienia istniejącej retencji. Indeksy obejmują jawny podmiot i wąski wyjątek
historycznego refresh. V17 dodaje export_id WebAuthn oraz indeksy stron
poświadczeń i metadanych kodów odzyskiwania. Nie zmienia credential_id,
sekretów, liczników uwierzytelniania ani istniejących unikalności.

Odbiór: izolowane PostgreSQL, ograniczone uprawnienia kolumnowe bez sekretów
i notatek, przypadki wielu podmiotów, strony 105 rekordów, błędne/stare konta,
wyłączenie konta między stronami i niezmienność danych przy pobieraniu.
Istniejące testy Review/Returns/Chat sprawdzają brak odczytu prywatnych pól.
Wyniki i raport: [checklista audytu](AUDIT_0.1_POSTEP.md).

Nie zmieniono Redis, aktywacji retencji, środowiska dev/prod, UI sklepu/CC
ani architektury. Nie odczytano danych i sekretów użytkownika. Pełny eksport
między usługami, obsługa kopii/usuwania i pozostałe klasyfikacje techniczne
pozostają w A43.30–A43.41. A05, całe A41 oraz zmiany zdjęć nadal odłożone.
