# A44 — CI i łańcuch dostaw

Stan 30.10.2026, branch `Audit_0.1`. **A44.1–A44.3 zaimplementowane i sprawdzone
lokalnie; całe A44 w toku.** Dodano kontrolę obrazów PR, cache, pomiary,
przypięcia wejść i SBOM. Nie uruchomiono
workflow w GitHub, nie opublikowano obrazów i nie wdrożono aplikacji.

## Plan i aktualny zakres

| Część planu | Stan |
|---|---|
| A44.1 — budowanie i skanowanie zmienionych obrazów w PR | Zaimplementowane i sprawdzone lokalnie; rzeczywisty przebieg PR/fork i ustawienie wymaganego checku nadal do potwierdzenia. |
| A44.2 — cache per obraz i pomiary | Mechanizm wdrożony; pomiary zaczną powstawać w GitHub po zastosowaniu workflow. Brak jeszcze porównania kosztu/czasu rzeczywistych przebiegów CI. |
| A44.3 — przypięcie wejść SHA/digest, aktualizacje i SBOM | Zaimplementowane i sprawdzone lokalnie. Poprawiony frontend: zero blokujących wpisów HIGH/CRITICAL z poprawką; nie oznacza to odbioru pozostałych 15 obrazów. |
| A44.4 — pełny odbiór i porównanie | Do wykonania: rzeczywiste PR, pełne 16 obrazów, skan przed publikacją/podpisem i pomiary CI; bez automatycznej zgody na deployment. |

To podział uzgodnionego planu A44, nie zmiana numeracji ani statusu A43.
A43.40/A43.41 pozostają otwarte, A05 nadal odłożone. Zachowano istniejące
bramki A43 oraz kontrolę P1 przed wdrożeniem produkcyjnym.

## Nowa ścieżka PR

`pr-image-plan` pobiera testowany merge i historię Git z `fetch-depth: 0`,
bez pozostawiania poświadczeń checkout. Lokalny planer porównuje bazę PR
z commitem merge wskazanym przez `GITHUB_SHA`; nie używa API GitHub, tytułu PR
ani listy plików obcinanej przez API. Usunięcie i zmiana nazwy uwzględnia
także starą ścieżkę. W raporcie są liczby, obrazy i stałe kody przyczyn,
bez treści plików, payloadu zdarzenia i surowych nazw zmienionych plików.

`pr-images` buduje wybrane obrazy i skanuje je równolegle z istniejącym `verify`,
maksymalnie cztery obrazy naraz. Każdy runner używa obrazu lokalnego, bez push,
logowania do GHCR i podpisywania. Uprawnienia PR to `contents: read`.

`pr-image-gate` ma stałą nazwę niezależnie od wielkości macierzy. Wymaga
poprawnego planu i sukcesu wszystkich wybranych budów/skanów. Dla pustej
macierzy dopuszcza wyłącznie poprawny plan oraz pominięte budowanie. Błąd,
anulowanie lub brak poprawnego wyniku nie daje pozytywnego checku.

PR nie ma już filtra `paths-ignore`, aby check nie pozostawał bez wyniku dla
zmian dokumentacji. PR obejmujący wyłącznie dokumentację może mieć zero obrazów;
**istniejący verify nadal działa**, nie ograniczono jego testów. To może zwiększyć
koszt PR wcześniej całkowicie pomijanych. Filtr dokumentacji dla push pozostał.

## Reguły wyboru obrazów

| Zmiana | Wybór |
|---|---|
| Źródła jednego modułu JVM | Ten obraz oraz jego przechodni konsumenci Maven. Zależności są odczytywane z POM-ów, z uwzględnieniem wspólnej biblioteki. |
| `platform-security`, root POM, wrapper, dowolny POM reaktora, Dockerfile JVM | Wszystkie 15 obrazów JVM. Dockerfile JVM kopiuje wszystkie POM-y. |
| `frontend/`, Dockerfile frontendu, znane konfiguracje nginx | Frontend. |
| `technical-data-classification.json` | Customer, który pakuje tę politykę jako zasób. |
| `.dockerignore`, spis komponentów, workflow/composite action, skrypty deploymentu | Pełne 16 obrazów. |
| Dokumentacja, testy E2E/QA i pozostałe skrypty QA poza pakowaną polityką | Bez obrazów, przy zachowaniu verify. Plik Markdown wewnątrz źródeł modułu nadal wybiera jego obraz. |
| Nieznane wejście / nietypowa ścieżka | Pełny zestaw. |
| Brak wiarygodnego zakresu Git lub uszkodzony graf POM | Konserwatywny pełny zestaw; nie ciche pominięcie obrazów. Niepoprawny spis komponentów kończy plan błędem. |

Relacje HTTP nie są zależnościami kompilacji; zgodność usług nadal sprawdzają
dotychczasowe testy integracyjne. Dla `main` i dotychczasowych jawnych wywołań
budowy/wdrożenia zachowano pełną macierz po `verify`.

## Jedna implementacja budowania i skanu

PR i dotychczasowy job publikacji używają
`.github/actions/build-scan-image/action.yml`. Oba typy obrazów są ładowane
lokalnie (`push: false`, `load: true`) i skanowane przed ewentualną publikacją.
Zachowano próg Trivy HIGH/CRITICAL, kod błędu 1 i dotychczasowe pomijanie
podatności bez poprawki (`--ignore-unfixed`); nie dodano ignorowania błędów
skanu ani wyjątków CVE. Skan obejmuje pakiety systemowe i biblioteki aplikacji.

Push do GHCR i Cosign pozostają osobnymi krokami wyłącznie w dotychczasowym
jobie `build-images`, po sukcesie wspólnego budowania/skanu. Deployment nadal
wymaga ręcznego workflow z `deploy=true`, środowiska production i bramki P1.
Samo zlecenie prac nad A44 nie uruchomiło żadnego z tych kroków.

## Cache, metryki i raporty

Scope cache: `a44-<komponent>-linux-amd64`, osobny dla każdego obrazu.
PR odczytuje cache, ale skonfigurowany workflow nie eksportuje go; eksport
pozostaje w zaufanej ścieżce budowania do publikacji. Nie oznacza to utworzenia
nowego trwałego cache Maven `.m2`: cache mounts BuildKit wymagają odrębnego
rozwiązania, jeżeli mają przetrwać nowe runnery.

`image-evidence.py` mierzy monotonicznie czas budowania i skanu oraz zapisuje
wynik, commit, komponent i lokalny image ID. Czas obejmuje też przygotowanie
narzędzi wewnątrz odpowiednich kroków; nie jest samym czasem CPU. Błąd nie
tworzy dowodu PASS; sukces wymaga obu wyników i identyfikatora obrazu.

Selekcja, raport czasu/wyniku, skan JSON, SBOM CycloneDX i powiązania ich
skrótów z obrazem trafiają do artefaktów GitHub na **1 dzień**.
Automatyczny upload surowych build records wyłączono. Nie zapisujemy pełnego
środowiska, sekretów ani danych aplikacji w tych raportach. Zachowane zwykłe
logi kroków CI mają dotychczasowy cykl repozytorium.

Nie deklarujemy jeszcze skrócenia CI o konkretną wartość. Potrzebne będą
porównywalne przebiegi GitHub: cold/warm cache, zmiana jednego obrazu, zmiana
wspólna i pełne wydanie, wraz z liczbą runnerów/czasem macierzy.

## A44.3 — identyfikowalne wejścia i aktualizacje

`scripts/deploy/supply-chain.lock.json` jest przeglądanym źródłem przypięć:

- Osiem bezpośrednio używanych akcji wskazuje pełny SHA commita. Cztery obrazy
  bazowe/BuildKit wskazują digest manifestu. Kontrola offline wykrywa rozjazd
  pliku blokady, Dockerfile i workflow.
- Trivy 0.76.1 i Cosign 3.1.4 są pobierane z oficjalnych wydań dla Linux amd64.
  Instalator sprawdza zapisany SHA256 przed instalacją; odrzuca błędne sumy,
  inne źródła i dowiązania w archiwum. Wrapper Maven 3.9.16 ma sumę dystrybucji.
- Frontend używa przypiętego nginx stable-alpine (badane wydanie: 1.30.6).
  Poprawki brotli-libs 1.2.1-r0 i curl/libcurl 8.23.0-r0 mają osobne URL i SHA256.
  Instalacja lokalnych APK działa bez sieci i wymaga zaufanych podpisów Alpine;
  nie używa `--allow-untrusted` ani ruchomego `apk upgrade`.
- Obraz JRE zawiera fonty DejaVu używane przez PDF. Zamiast instalować je
  z ruchomych indeksów apt, Dockerfile wymaga obecności regular/bold w bazie
  przypiętej digestem. Lokalnie sprawdzono te pliki i uruchomienie Java 25;
  pełna regresja PDF w zbudowanym obrazie Billing należy do A44.4.

`image-input-updates.yml` raz w tygodniu (poniedziałek 06:17 UTC) lub na ręczne
wywołanie przygotowuje kandydat pliku blokady i `proposed.patch` na podstawie
publicznych metadanych GitHub, Docker Hub i Alpine. Ma tylko `contents: read`;
nie stosuje zmian, nie tworzy PR, nie publikuje i nie scala automatycznie.
Właściciel przegląda patch, stosuje go na branch i ponawia testy/build/skany.
Wariant lokalny:

```bash
python3 scripts/deploy/refresh-supply-chain-lock.py --output tmp/a44-update-candidate
git apply --check --allow-empty tmp/a44-update-candidate/proposed.patch
```

Pusty patch oznacza brak zmian względem sprawdzonych źródeł. Aktualizator
pozostaje w obecnych głównych wersjach akcji i gałęzi Alpine 3.24. Przejście
nginx na inną gałąź Alpine wymaga jawnego przeglądu zestawu APK; kontrola skanu
odrzuca niespójną gałąź bazy i poprawek. Wrapper ma oddzielną ręczną aktualizację
wersji/URL/sumy. Usunięte upstream artefakty powodują błąd, bez fallbacku do latest.

Przypięcia dotyczą wskazanych wejść; nie oznaczają pełnego audytu zależności
przechodnich akcji, pakietów ani niezmienności runnera `ubuntu-latest`.
Baza podatności jest odświeżana przez Trivy; raport przechowuje jej metadane.

## A44.3 — SBOM i powiązanie z publikacją

`image-supply-chain.py` porównuje lokalny tag z image ID zwróconym przez build,
skanuje ten ID i dla niego generuje osobny SBOM CycloneDX. W raporcie zapisuje
commit, image ID, skrót pliku blokady, wersję/sumę binarki skanera oraz SHA256
skanu i SBOM. Niespójność, brak dowodu lub nieudany skan blokuje dalszy krok.
SBOM może powstać również dla obrazu odrzuconego; sam plik nie stanowi zgody
na publikację.

Osobny SBOM jest potrzebny, ponieważ obecne `load: true` nie zachowuje
atestacji BuildKit w klasycznym magazynie Docker. W zaufanej ścieżce po udanym
skanie `preflight` ponownie sprawdza dowody, następuje push, a następnie odczyt
jednoznacznego digestu repozytorium. Cosign podpisuje ten digest i dołącza
atestację CycloneDX do tego samego digestu. Brak digestu blokuje podpis;
nie ma fallbacku do podpisywania tagu. PR nie otrzymuje tej ścieżki ani OIDC.
Rzeczywista publikacja, OIDC i odczyt podpisu/atestacji wymagają jeszcze A44.4.

## Odbiór lokalny i historia wyników skanu

- A44.1/A44.2: 28 celowanych testów Python: selekcja, przechodnie zależności Maven, Git
  z rename/delete, fallback, wykonanie skryptu końcowej bramki, mutacje
  uprawnień/cache/skanu/publikacji i regresja dotychczasowych bramek — PASS.
- Oficjalny `actionlint` v1.7.12, pobrany do katalogu QA i sprawdzony sumą
  wydania — PASS dla workflow. Nie zmieniano globalnych narzędzi systemu.
- Kontrola YAML, `ci-images-check.py` i `supply-chain-check.py` — PASS; oba
  walidatory są w CI. A44.3 dodaje testy sum, podmienionego SBOM/commita,
  błędów skanera, niejednoznacznego digestu, przypięć i uprawnień aktualizatora.
- Rzeczywisty Docker: frontend z kopii wyłącznie publicznych źródeł i konfiguracji
  zbudowany lokalnie; wariant z brakującym plikiem COPY poprawnie odrzucony.
- Historyczny lokalny Trivy 0.74.0 przed A44.3: **36 wpisów pakiet–CVE, 27 różnych ID CVE:
  35 HIGH i 1 CRITICAL**, z informacją o poprawkach, przy użyciu wskazanego progu.
  Skan zakończył się kodem 1. To wynik narzędzia dla konkretnego badanego obrazu,
  nie ręczne potwierdzenie podatności ani skan wszystkich 16 obrazów.

Pakiety wskazane przez lokalny skaner: brotli-libs, curl/libcurl, libexpat,
libuuid, pcre2 i tiff. Obraz miał ID
`sha256:edfbe4f3ef5505b582503049e4b7f846e54bd29238a0f490e7ee5ef6d9c450f7`.
Nie opublikowano go ani nie wdrożono. Test użył istniejących mechanizmów cache;
ten pomiar nie dowodził świeżego przebudowania wszystkich pakietów systemowych.

W A44.3 świeża przypięta baza zmniejszyła liczbę blokujących wpisów do 3
(brotli-libs, curl i libcurl). Po dodaniu wskazanych podpisanych poprawek
końcowy Trivy 0.76.1 zgłosił **0 blokujących wpisów**, kod 0, przy zachowaniu
HIGH/CRITICAL i `--ignore-unfixed`. Zbudowany obraz
`sha256:7ef4e88b42681b8b64ce0eeeb0a629613d5ec59f961f195df70657a766b1742c`
przeszedł test healthz jako non-root z read-only i bez sieci zewnętrznej;
powstał SBOM CycloneDX i zapis metadanych bazy skanera. To odbiór jednego
lokalnego obrazu, nie deklaracja braku wszystkich CVE ani skan 16 obrazów.

Aktualny raport zbiorczy: `tmp/audit-a44/report.json`;
odbiór etapu: `tmp/audit-a44-3/report.json`.
Dowody budowy/skanu: `tmp/a44-docker-9ed36fb4/report.json` oraz
`tmp/a44-docker-9ed36fb4/scan-output/scan.json`. Obrazy o własnych nazwach QA
usunięto po teście; nie używano sekretów, danych klientów, socketu Docker
w kontenerze skanera ani usług aplikacji.
Końcowe dowody A44.3: `tmp/a44-3-docker-4a18a62c/report.json` i jego katalog
`evidence/`. Odbiór aktualizatora: `tmp/a44-3/refresh.log`, kandydat i patch
w `tmp/a44-3/update-candidate/` (bez zmian), pozytywny test zastosowalności
niepustego patcha na sztucznym repozytorium. Nie stosowano patcha do projektu.

## Pozostałe prace i czynności w GitHub

- [ ] Po zapisaniu zmian w repozytorium uruchomić rzeczywiste PR: pojedynczy
  moduł, zmiana wspólna, pusty wybór obrazów, fork oraz błędny build/skan.
- [ ] Właściciel repozytorium dodaje check `pr-image-gate` do wymaganych
  statusów/rulesetu; rzeczywistą nazwę potwierdza po pierwszym przebiegu.
  Samo dodanie joba do pliku **nie ustawia ochrony branch**. Bez tej reguły
  ujemny job może być widoczny, ale nie musi uniemożliwić merge.
- [x] A44.3: lokalnie przypięte wejścia, poprawiony frontend, kontrolowane
  propozycje aktualizacji, SBOM i identyfikacja narzędzi/bazy skanera.
- [ ] A44.4: pełny zestaw 16 obrazów, scan → push → podpis oraz odczyt dowodów
  w GitHub, pomiary cold/warm i kosztu macierzy. Wdrożenie produkcyjne jest
  osobnym zleceniem; A05 nadal blokuje gotowość P1.

Źródła techniczne: [Docker — cache w GitHub Actions](https://docs.docker.com/build/ci/github-actions/cache/),
[Docker — build-push-action](https://github.com/docker/build-push-action),
[GitHub — bezpieczne użycie Actions](https://docs.github.com/en/actions/reference/security/secure-use),
[Docker — SBOM i provenance](https://docs.docker.com/build/ci/github-actions/attestations/),
[Trivy — SBOM](https://github.com/aquasecurity/trivy/blob/main/docs/guide/supply-chain/sbom.md),
[Sigstore — podpisywanie kontenerów](https://docs.sigstore.dev/cosign/signing/signing_with_containers/).

Audyt źródłowy: [A44](AUDYT_APLIKACJI_2026-10-23.md#a44).
Postęp: [rejestr audytu](AUDIT_0.1_POSTEP.md).
