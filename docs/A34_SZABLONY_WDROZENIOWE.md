# A34.1 — spójne i sprawdzalne szablony Kubernetes

Etap przygotowuje kod i szablony do wydania. Pełne A34 pozostaje częściowe:
nie przeprowadzono odbioru płatności i dostarczania e-maili w docelowym środowisku
przedprodukcyjnym, nie skonfigurowano jego infrastruktury ani dostępu do sekretów.
Profil `prod` w Control Center nadal jest niedostępny do lokalnego uruchamiania.
Odłożone A05 wymaga osobnego polecenia użytkownika.

## Wdrożony zakres

`deploy/components.json` zawiera 15 usług JVM, frontend, 14 powiązań baz oraz
graf komunikacji HTTP/Redis. Korzystają z niego generator manifestów,
budowanie obrazów, macierz CI, walidator i lista rolloutów. Walidator porównuje
spis z Maven/Compose i wykrywa pominięte adresy klientów HTTP lub klienta Redis.

Uzupełniono `chat-service`, `returns-service` i `billing-service`, ich Service,
obrazy, URL, dane połączeń i przykładowe nazwy haseł. Gateway ma limit pamięci
wewnątrz `resources`. Ingress otrzymuje namespace `minitech-store` z overlayu.
Zestaw zawiera 53 zasoby: namespace, ConfigMap, 16 Deploymentów, 16 Service’ów,
18 NetworkPolicy i Ingress.

Wszystkie aplikacje działają jako UID/GID 10001, z `runAsNonRoot`, profilem
seccomp `RuntimeDefault`, bez podnoszenia uprawnień i capabilities. System
plików kontenera jest tylko do odczytu, a `/tmp` jest osobnym `emptyDir`
z limitem 256 MiB. JVM używa `/tmp` także jako katalogu domowego dla cache.
Nginx ma PID i katalogi tymczasowe w `/tmp`; obraz frontendu uruchamia się
bez roota. Namespace egzekwuje standard `restricted` w wersji 1.35.
Podstawę ograniczeń opisują [Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/).

Provider e-maili `external-api` był atrapą zawsze zwracającą błąd. Szablon
i domyślne ustawienie profilu `prod` wybierają istniejący SMTP na porcie 587.
Konfiguracja wymaga uwierzytelniania, STARTTLS i sprawdzenia nazwy serwera
w certyfikacie. Rzeczywisty host, nadawca i dane logowania wymagają konfiguracji
operatora. `PAYMENT_PROVIDER=disabled` pozostaje w szablonie do zakończenia A05.

## Polityki sieciowe i infrastruktura

Domyślne blokowanie i DNS obejmują wyłącznie pody oznaczone jednocześnie
`app.kubernetes.io/part-of=mini-order-platform` oraz
`app.kubernetes.io/component=application`. Nie obejmują przypadkowo podów
infrastruktury oznaczonych jako `database` lub `broker`.

Graf ze spisu komponentów określa połączenia HTTP na 8080, własną bazę na 5432
oraz dostęp właściwych usług do Redis na 6379. DNS dopuszcza port 53 TCP/UDP
do `kube-system`. Do frontendu może wejść kontroler z namespace `ingress-nginx`
oznaczony `app.kubernetes.io/component=controller`.

Szablon zakłada Service baz o nazwach `<alias>-postgres` i pody baz z etykietą
`app.kubernetes.io/name=<alias>-postgres`; broker ma nazwę/etykietę
`event-broker`. Te zasoby infrastruktury są dostarczane osobno. Podlegają też
polityce bezpieczeństwa namespace i własnym regułom sieciowym.

SMTP notification ma wyjście na publiczny port 587, a payment na publiczny
443. Wykluczono sieci prywatne, loopback i link-local. Standardowe NetworkPolicy
nie ograniczają takich połączeń nazwą DNS; prywatny relay SMTP, inne nazwy
kontrolera, zewnętrzne bazy lub inny układ namespace wymagają osobnego overlayu
i odbioru reguł w docelowym CNI.

Przyjęcie obiektu NetworkPolicy nie dowodzi egzekwowania ruchu. Wymagany jest
CNI obsługujący polityki; test kind sprawdza schemat i admission, a nie
enforcement. Zob. [Kubernetes Network Policies](https://kubernetes.io/docs/concepts/services-networking/network-policies/).

## Sekrety

Domyślny Kustomize nie zawiera Secretów ani przykładów CSI/TLS. Walidator
odrzuca inline Secret, pole sekretu w ConfigMap, przekazanie całego Secretu
do wszystkich usług, dosłowne wartości env i przypisanie sekretu innej usłudze.
Sprawdza nazwy referencji na podstawie przykładu; nie odczytuje skarbców ani
wartości istniejących sekretów w klastrze.

Wymagane są osobno przygotowane `minitech-store-secrets` i certyfikat TLS.
Opcjonalny przykład CSI synchronizuje tylko `minitech-customer-crypto`:
nie zastępuje wspólnego Secretu z hasłami DB i tokenami usług. Włączenie CSI
wymaga instalacji providera, roli/ścieżek OpenBao, montowania oraz jawnej
zmiany referencji Customer. Nie jest częścią domyślnego overlayu.

## Generowanie i walidacja lokalna

Instalator narzędzi dla Linux amd64/WSL używa przypiętych wersji i sum SHA256
z `scripts/deploy/tools.lock.json`. Pliki trafiają do ignorowanego cache
projektu; nie zmienia on globalnej instalacji ani kubeconfig użytkownika.

```bash
python3 -m venv gitIgnoreOwn/deploy-venv
gitIgnoreOwn/deploy-venv/bin/python -m pip install -r scripts/qa/requirements.txt
export PATH="$PWD/gitIgnoreOwn/deploy-venv/bin:$PATH"
python3 scripts/deploy/install-validation-tools.py
export PATH="$PWD/gitIgnoreOwn/tools/deploy/bin:$PATH"
python3 scripts/deploy/generate-k8s.py
bash scripts/control-center/control-center.sh k8s-validate
```

Pliki `deployments.yaml`, `services.yaml`, `network-policies.yaml` i lista
obrazów w overlayu są generowane. Zmiany wprowadza się w spisie lub generatorze;
`generate-k8s.py --check` blokuje nieaktualne pliki. ConfigMap, Ingress i
konfiguracja docelowego środowiska pozostają jawnymi źródłami ustawień.

Walidacja renderuje Kustomize i sprawdza ścisłe schematy Kubernetes 1.35.0,
bez pomijania nieznanych typów. Sprawdza również powiązania, sekrety,
ograniczenia podów i graf sieci. Raport rozróżnia poprawność szablonów od
odbioru przedprodukcyjnego.

Jednorazowy odbiór API Kubernetes i kontenera frontendu:

```bash
python3 scripts/deploy/install-validation-tools.py --with-kind
python3 scripts/qa/k8s-templates-e2e.py
```

Scenariusz używa osobnego kubeconfig, losowego klastra i kontenerów. Sprawdza
`--dry-run=server --validate=strict`, 16 podów zgodnych z `restricted`, odrzucenie
błędnego `limits` i podwyższonych uprawnień, a następnie health/static/proxy
frontendu przy read-only root. Nie uruchamia aplikacji w klastrze użytkownika.
Semantykę trybu opisuje [kubectl apply](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_apply/).

## Pozostały odbiór pełnego A34

- Docelowy overlay z rzeczywistą domeną, TLS, zaufanymi zakresami proxy,
  SMTP, DB/Redis i politykami właściwego CNI.
- Dostarczenie sekretów i potwierdzenie dostępu aplikacji, w tym integracji
  OpenBao/CSI, jeżeli zostanie wybrana.
- Rzeczywisty odbiór płatności z odłożonego A05 oraz dostarczenia wiadomości
  do docelowej skrzynki przy wymaganym TLS.
- Uruchomienie wszystkich usług, migracji i scenariuszy zakupu w docelowym
  środowisku przedprodukcyjnym; test egzekwowania polityk sieciowych.

CI ma bramkę otwartych P1 oraz oddzielną walidację `--release`, która odrzuca
wyłączone płatności, placeholdery, brak zaufanych proxy i obrazy bez tagu commitu.
Zmienna repozytorium `PROD_KUSTOMIZE_OVERLAY` pozwala wskazać docelowy overlay;
domyślny szablon nie spełnia odbioru produkcyjnego. Server dry-run i apply
korzystają z tego samego zweryfikowanego artefaktu. Nie uruchamiano wdrożenia
na dev/prod i nie wznawiano odłożonych działań Stripe.

## Odbiór lokalny — 26.10.2026

- Wszystkie 53 wyrenderowane zasoby są poprawne względem ścisłych schematów
  Kubernetes 1.35.0; wynik: 53 valid, 0 invalid, 0 errors, 0 skipped.
- Jednorazowy klaster przyjął server dry-run tych zasobów oraz 16 podów
  zgodnych z `restricted`. Odrzucił błędne umieszczenie `limits` i próbę
  podwyższenia uprawnień. Aplikacje nie zostały uruchomione w tym klastrze.
- Kontener frontendu przeszedł healthcheck, odczyt strony i proxy API przy
  UID 10001, read-only root i bez capabilities. Zapis do katalogu statycznego
  był odrzucony. Klastry, kontenery i kubeconfig fixture zostały usunięte.
- Przeszło 114 testów Python/CC, w tym 12 testów kontraktu szablonów i błędnych
  konfiguracji. Walidacja uruchomiona z CC i kontrola świeżości generatora
  również przeszły.
- Regresja obejmuje 67 testów uprawnień i 12 istniejących testów Notification.
  Dwa nowe testy profilu prod potwierdziły wybór SMTP, wymaganie STARTTLS
  i weryfikacji nazwy certyfikatu oraz brak wysłania danych logowania/wiadomości
  do serwera bez STARTTLS. Nie jest to odbiór zewnętrznej skrzynki.

Raporty: `tmp/audit-a34-865a160d/report.json` oraz
`tmp/audit-a34-865a160d/validation/report.json`. Scenariusz i artefakty są w CI;
kubeconfig nie jest publikowany jako artefakt. Mapa P1 nadal ma stan 13/14,
z otwartym, odłożonym A05. Pełnego A34 nie oznaczono jako wykonane.
