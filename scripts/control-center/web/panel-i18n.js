/** Control Center dictionaries and static labels; no HTTP, session or polling. */
const I18N = {
    pl: {
      langCode: 'PL',
      viewTitles: {
        dashboard: 'Centrum operacyjne',
        services: 'Usługi i endpointy',
        operations: 'Operacje Centrali',
        data: 'Eksplorator Danych Biznesowych',
        events: 'Zdarzenia, Kolejki i Śledzenie Transakcji',
        chaos: 'Symulator Awarii & Chaos Engineering',
        chat: 'Obsługa klienta',
        assistant: 'Asystent Architektury AI',
        tests: 'Testy i jakość',
        jobs: 'Zadania i logi',
        terminal: 'Terminal WSL',
        about: 'O platformie MiniTech',
      },
      nav: {
        dashboard: 'Pulpit',
        services: 'Usługi',
        operations: 'Operacje',
        data: 'Dane i Sklep',
        events: 'Zdarzenia i DLQ',
        chaos: 'Symulator Awarii',
        chat: 'Czat klientów',
        assistant: 'Asystent AI',
        tests: 'Testy',
        jobs: 'Zadania i logi',
        terminal: 'Terminal WSL',
        about: 'O aplikacji',
        localOnly: 'Tylko lokalnie',
      },
      topbar: {
        storeLink: 'Otwórz sklep ↗',
        mailpitLink: '✉ Otwórz Mailpit ↗',
        swaggerLink: '📖 Swagger UI ↗',
        refresh: 'Odśwież dane',
        profile: 'Profil',
        langBtnTitle: 'Zmień język (PL / EN)',
        searchPlaceholder: 'Szukaj poleceń...',
        searchTitle: 'Otwórz paletę poleceń (Ctrl+K)',
        reportBtn: '📋 Raport',
        reportTitle: 'Raport diagnostyczny stanu platformy',
        palettePlaceholder: "Wpisz polecenie lub wyszukaj widok... (np. 'start', 'magazyn', 'sekrety', 'dlq', 'testy')",
      },
      dashboard: {
        heroEyebrow: 'LOKALNE ŚRODOWISKO DEV / TEST',
        heroH2: 'Wszystko pod kontrolą.<br><span>Wszystko w jednym miejscu.</span>',
        heroP: 'Uruchamiaj mikroserwisy, odpowiadaj klientom, sprawdzaj testy i analizuj logi w jednym, lokalnym panelu.',
        btnCheckEnv: 'Sprawdź środowisko',
        btnShowOps: 'Pokaż operacje',
        coreServices: 'usług',
        metricOnline: 'usług online',
        metricDbs: 'osobnych baz',
        metricBroker: 'Redis + Outbox',
        metricMail: 'Mailpit + SOPS',
        platformState: 'STAN PLATFORMY',
        microservices: 'Mikroserwisy',
        viewAll: 'Wszystkie →',
        quickStart: 'SZYBKI START',
        commonActions: 'Najczęstsze działania',
        quickInfra: 'Infrastruktura',
        quickDockerUp: 'Uruchom serwisy',
        quickDockerUpSub: 'Kontenery Docker',
        quickDockerDown: 'Zatrzymaj serwisy',
        quickDockerDownSub: 'Zatrzymaj kontenery',
        quickRunAll: 'Uruchom usługi',
        quickRunAllSub: 'Wszystkie aplikacje',
        quickFrontend: 'Frontend',
        quickFrontendSub: 'Sklep :18070',
        quickAppVerify: 'App Verify',
        quickAppVerifySub: 'Scenariusz zakupowy',
        quickSecretsDev: 'Sekrety DEV',
        quickSecretsTest: 'Sekrety TEST',
        quickSecretsEditSub: 'Bezpieczna edycja SOPS',
        quickSecretsSetupSub: 'Pierwsza konfiguracja',
        recentActivity: 'OSTATNIA AKTYWNOŚĆ',
        recentJobsTitle: 'Zadania',
        fullLog: 'Pełny log →',
        emptyJobs: 'Nie uruchomiono jeszcze żadnego zadania.',
        emptyData: 'Brak danych.',
        btnFreshStart: '🚀 Pełny start (1-Click)',
        btnDoctorFix: '🚑 Skanuj i napraw',
        btnShowAllOps: 'Wszystkie operacje →',
        quickFreshStart: 'Pełny start',
        quickFreshStartSub: '1-Click Fresh Start',
        quickSeedDemo: 'Zasil danymi demo',
        quickSeedDemoSub: 'Klienci, czat, recenzje',
        quickDoctorFix: 'Doktor & Naprawa',
        quickDoctorFixSub: 'Auto-Healing',
        quickInfraSub: 'Bazy Docker Compose',
      },
      services: {
        eyebrow: 'RUNTIME',
        title: 'Usługi i endpointy',
        desc: 'Zarządzaj działaniem mikroserwisów i sprawdzaj status endpointów.',
        btnRefresh: 'Sprawdź ponownie',
        liveBadge: 'Na żywo (4s)',
        ctrlEyebrow: 'KONTENERY DOCKER / STOS',
        ctrlTitle: 'Zarządzanie wszystkimi serwisami',
        ctrlDesc: 'Uruchamiaj lub zatrzymuj cały stos 15 mikroserwisów i frontend jednym kliknięciem.',
        btnDockerUp: 'Uruchom serwisy (Docker)',
        btnDockerDown: 'Zatrzymaj serwisy',
        btnInfraUp: 'Bazy danych',
        btnInfraDown: 'Zatrzymaj bazy',
        btnStart: 'Uruchom',
        btnStop: 'Zatrzymaj',
        btnLogs: 'Logi',
      },
      operations: {
        eyebrow: 'BIAŁA LISTA POLECEŃ',
        title: 'Operacje Centrali',
        desc: 'Każda operacja wywołuje istniejący skrypt Bash. Polecenia zmieniające dane wymagają dodatkowego potwierdzenia.',
        searchPlaceholder: 'Szukaj operacji, np. backup, JWT, faktura…',
        allGroups: 'Wszystkie grupy',
        swaggerBannerTitle: 'Swagger UI — Wszystkie 15 mikroserwisów',
        swaggerBannerDesc: 'Zintegrowana dokumentacja OpenAPI z rozwijaną listą serwisów. Wywołuj endpointy bezpośrednio przez API Gateway bez błędów CORS.',
        swaggerBannerBtn: 'Otwórz Swagger UI',
        btnRun: 'Uruchom',
        btnConfigure: 'Ustaw i uruchom',
        btnOpenEditor: 'Otwórz edytor',
        btnCreateFile: 'Utwórz plik',
        btnFileExists: 'Plik już istnieje',
      },
      chat: {
        eyebrow: 'OBSŁUGA KLIENTA',
        title: 'Wiadomości ze sklepu',
        desc: 'Odpowiedź pojawi się w widżecie klienta i zostanie przekazana do Notification Service jako e-mail.',
        btnRefresh: 'Odśwież rozmowy',
        searchPlaceholder: 'Szukaj / Search',
        statusAll: 'Wszystkie / All',
        statusNew: 'Nowe / New',
        statusAnswered: 'Odpowiedziano / Answered',
        emptyList: 'Pobieram rozmowy…',
        noMessages: 'Brak wiadomości.',
        selectChat: 'Wybierz rozmowę z listy.',
        replyFormLabel: 'Odpowiedź',
        replyPlaceholder: 'Napisz jasną i pomocną odpowiedź dla klienta…',
        btnSendReply: 'Wyślij odpowiedź',
        replySuccess: 'Odpowiedź zapisana. Powiadomienie oczekuje na przekazanie.',
        operatorUnavailable: 'Weryfikacja dostępu operatora jest niedostępna.',
        operatorForbidden: 'Wymagana zweryfikowana sesja operatora.',
        operatorCannotVerify: 'Nie można zweryfikować dostępu operatora.',
        loadOlder: 'Wczytaj starsze wiadomości',
        customerInquiry: 'Pytanie klienta',
      },
      assistant: {
        eyebrow: 'AI & WSPARCIE PROJEKTU',
        title: 'Asystent Architektury MiniTech',
        desc: 'Zadawaj pytania o architekturę 15 mikroserwisów, routing, bazy danych, przepływ zdarzeń i komendy.',
        btnSettings: 'Ustawienia AI',
        inputPlaceholder: 'Zadaj pytanie o projekt Mini Order Platform…',
        btnSend: 'Wyślij',
        welcomeTitle: 'Cześć! Jestem asystentem architektury MiniTech.',
        welcomeP1: 'Domyślnie pracuję w trybie wbudowanym (offline) w oparciu o przyciski z najczęściej zadawanymi pytaniami umieszczone na górze ekranu oraz autodiagnostykę logów.',
        welcomeP2: 'Kliknij jeden z powyższych przycisków, aby natychmiast uzyskać odpowiedź. Jeśli chcesz rozmawiać ze mną jak na swobodnym czacie konwersacyjnym, podepnij zewnętrznego agenta lub lokalny model (np. z komputera) w ⚙ Ustawieniach AI.',
        thinking: 'Asystent analizuje pytanie…',
        configSaved: 'Zapisano ustawienia silnika AI.',
        errorEmpty: 'Wpisz pytanie przed wysłaniem.',
      },
      tests: {
        eyebrow: 'QUALITY GATE',
        title: 'Testy i jakość',
        desc: 'Od szybkiego smoke do pełnego E2E i regresji Playwright w wielu przeglądarkach.',
        btnRun: 'Uruchom test',
      },
      jobs: {
        eyebrow: 'LOGI I PROCESY',
        title: 'Historia Zadań i Logi',
        desc: 'Podgląd procesów tła, statusów uruchomień oraz wyjść konsoli.',
        btnRefresh: 'Odśwież',
        emptyHistory: 'Brak zadań.',
        selectJobPrompt: 'Wybierz zadanie, aby zobaczyć log.',
        waitingOutput: 'Oczekiwanie na wynik…',
        noLogContent: 'Brak treści logu.',
      },
      about: {
        introEyebrow: 'ARCHITEKTURA & MONOREPO',
        introTitle: 'O architekturze platformy MiniTech',
        introDesc: 'Monorepo 15 mikroserwisów Java Spring Boot, Gateway, bazy PostgreSQL, szyna zdarzeń Redis i frontend.',
        stat1Title: '15 Mikroserwisów',
        stat1Sub: 'Dedykowany proces i port per domena',
        stat2Title: '14 Baz Danych',
        stat2Sub: 'Ścisła izolacja Database-per-Service',
        stat3Title: 'API Gateway',
        stat3Sub: 'Wersjonowany routing :18080',
        stat4Title: 'Event Broker',
        stat4Sub: 'Asynchroniczne zdarzenia & DLQ',
        stat5Title: 'Skarbiec SOPS + Age',
        stat5Sub: 'Szyfrowane sekrety DEV / TEST',
        stat6Title: 'Quality Gate & AI',
        stat6Sub: '31 testów Playwright · Asystent AI',
        purposeEyebrow: 'CEL I PRZEZNACZENIE',
        purposeTitle: 'Czym jest Mini Order Platform?',
        purposeContent: '<p>Mini Order Platform to zaawansowana platforma e-commerce zbudowana w architekturze <strong>prawdziwych mikroserwisów</strong> (monorepo). Każda usługa domenowa posiada niezależny proces, własny dedykowany port sieciowy oraz ściśle odizolowaną bazę danych PostgreSQL.</p><p>Platforma realizuje pełny cykl zakupowy — od przeglądania katalogu produktów z rabatami dnia, przez koszyk, wycenę dostawy, rezerwację magazynową (Saga), płatność, fakturowanie VAT, aż po asynchroniczne powiadomienia e-mail (Mailpit), tracking przesyłek, reklamacje i czat wsparcia na żywo.</p><p>Centrala Kontrolna (port <code>:18100</code>) integruje zarządzanie kontenerami, autodiagnostykę logów, bazę wiedzy o błędach (1-Click Auto-Fix), symulator awarii (Chaos Engineering) oraz wbudowanego Asystenta AI. Asystent AI bez wpięcia do zewnętrznego agenta działa w domyślnym trybie wbudowanym (offline) w oparciu o przyciski z najczęściej zadawanymi pytaniami umieszczone na górze widoku. Aby korzystać z asystenta jak ze swobodnego czatu konwersacyjnego, należy w Ustawieniach AI podpiąć zewnętrznego agenta lub lokalny model LLM (np. z komputera).</p>',
        patternsEyebrow: 'WZORCE PROJEKTOWE',
        patternsTitle: 'Kluczowe filary architektoniczne',
        feat1Badge: '🛡️ Database per Service',
        feat1Desc: 'Brak współdzielonych tabel SQL. Product Service nie czyta inventory_db, a Order Service komunikuje się z magazynem wyłącznie przez kontrakty HTTP API.',
        feat2Badge: '📦 Transactional Outbox Pattern',
        feat2Desc: 'Płatność i rekord zdarzenia są zapisywane atomowo w transakcji PostgreSQL (payment_outbox). Dedykowany publisher wysyła je do Redis Streams, gwarantując niezawodność.',
        feat3Badge: '🔄 Wzorzec Saga i Kompensacja',
        feat3Desc: 'Brak globalnej blokującej transakcji SQL. Jeśli rezerwacja magazynowa zawiedzie, saga wykonuje kompensację: zwalnia towar i oznacza zamówienie jako anulowane.',
        feat4Badge: '🔑 Idempotencja operacji',
        feat4Desc: 'Ponowienie żądania z nagłówkiem Idempotency-Key nie tworzy duplikatów zamówień ani podwójnych obciążeń konta.',
        feat5Badge: '⚡ Chaos Engineering & Resilience',
        feat5Desc: 'Dedykowany Symulator Awarii w Centrali, Circuit Breaker (Resilience4j), Bulkhead, Retry z wykładniczym backoffem oraz kolejka Dead Letter Queue (DLQ).',
        feat6Badge: '🤖 Asystent AI & Autodiagnostyka',
        feat6Desc: 'Zintegrowany Asystent Architektury AI działający domyślnie w trybie offline (przyciski z najczęstszymi pytaniami i diagnostyka logów) z opcją podpięcia zewnętrznego agenta LLM (czat), baza wiedzy o błędach i samonaprawa 1-Click Fix.',
        feat7Badge: '🔐 Bezpieczeństwo i Auth',
        feat7Desc: 'Krótkotrwały Access JWT RS256, rotowany Refresh Token w ciasteczku HttpOnly, uwierzytelnianie 2FA TOTP (Google Authenticator) i WebAuthn / Passkey.',
        feat8Badge: '🧪 Quality Gates & Testy',
        feat8Desc: 'Pełna piramida testów: 31 scenariuszy Playwright UI E2E, kontrakty OpenAPI v1, testy bezpieczeństwa, testy obciążeniowe oraz 17 modułów Maven.',
        modulesEyebrow: 'STRUKTURA MODUŁÓW',
        modulesTitle: 'Mapa 15 mikroserwisów platformy',
        grp1Title: 'Warstwa Brzegowa & Frontend',
        grp2Title: 'Katalog, Oferty & Opinie',
        grp3Title: 'Sprzedaż, Magazyn & Zwroty',
        grp4Title: 'Płatności, Finanse & Dostawa',
        grp5Title: 'Klienci, Poczta & Czat',
        flowEyebrow: 'PRZEPŁYW DANYCH',
        flowTitle: 'Cykl zamówienia krok po kroku',
        step1Title: 'Katalog i Koszyk',
        step1Desc: 'Klient przegląda produkty w :18070. Koszyk trzymany jest lokalnie w przeglądarce (nie blokuje magazynu).',
        step2Title: 'Wycena i Dostawa',
        step2Desc: 'Delivery Service wylicza koszt dostawy, a Promotion Service sprawdza kody rabatowe. Klient decyduje o fakturze VAT.',
        step3Title: 'Zamówienie (Saga)',
        step3Desc: 'Order Service tworzy snapshot cen i wywołuje Inventory Service w celu zablokowania sztuk towaru (status CONFIRMED).',
        step4Title: 'Płatność i Outbox',
        step4Desc: 'Payment Service autoryzuje płatność, potwierdza rezerwację w magazynie, tworzy fakturę VAT i zapisuje zdarzenie w payment_outbox.',
        step5Title: 'Redis, E-mail & PDF',
        step5Desc: 'Zdarzenie trafia do Redis Stream. Notification Service odbiera je i wysyła e-mail z załącznikiem faktury PDF do Mailpit (:18025).',
      },
      terminal: {
        eyebrow: 'PEŁNA ZGODNOŚĆ Z CLI',
        title: 'Terminal WSL',
        desc: 'Użyj go dla interaktywnych kreatorów SOPS, psql, Vim oraz każdej opcji starego menu.',
        btnStart: 'Uruchom sesję',
        notStarted: 'Sesja nie została uruchomiona.',
        inputPlaceholder: 'Wpisz opcję lub tekst i naciśnij Enter',
        btnSend: 'Wyślij',
      },
      modals: {
        dangerConfirm: 'Rozumiem, że ta operacja może zmienić dane lub stan środowiska.',
        btnCancel: 'Anuluj',
        btnRun: 'Uruchom',
        noParams: 'Operacja nie wymaga dodatkowych parametrów.',
        confirmOpToast: 'Potwierdź operację przed uruchomieniem.',
        secretSaveConfirm: 'Rozumiem, że zapis zmienia zaszyfrowane sekrety wybranego profilu.',
        secretSaveSubmit: 'Zapisz zaszyfrowane',
        secretSetupSubmit: 'Utwórz i zaszyfruj',
        dispatchEyebrow: 'WYSYŁKA DOKUMENTU (EMAIL / SMTP)',
        dispatchTitle: 'Wyślij dokument do klienta',
        dispatchRecipient: 'Adres e-mail odbiorcy *',
        dispatchSubject: 'Temat wiadomości *',
        dispatchMessage: 'Dedykowana wiadomość / Notatka dla klienta (opcjonalnie)',
        dispatchAttachment: 'Do wiadomości zostanie dołączony plik PDF pobrany z Billing Service.',
        dispatchSubmit: '📤 Wyślij e-mail',
        correctionEyebrow: 'FAKTURA KORYGUJĄCA (KOR)',
        correctionTitle: 'Wystawienie korekty faktury',
        correctionReason: 'Przyczyna korekty *',
        correctionDelta: 'Kwota zmiany brutto (PLN) *',
        correctionSubmit: 'Wystaw korektę',
        secretEyebrow: 'BEZPIECZNA EDYCJA SOPS',
        reportEyebrow: 'RAPORT DIAGNOSTYCZNY ŚRODOWISKA',
        reportTitle: '📋 Stan Platformy MiniTech',
        reportCopy: '📋 Kopiuj do schowka',
      },
      diagnostics: {
        unknownIssue: 'Nieznany błąd',
        detectedProblem: 'Wykryty problem',
        causeLabel: '👉 Przyczyna:',
        solutionLabel: '💡 Rozwiązanie:',
        askAiBtn: '✦ Zapytaj Asystenta AI',
        quickFix: 'Napraw automatycznie',
      },
      data: {
        eyebrow: 'BAZY DANYCH & ENCJE BIZNESOWE',
        title: 'Eksplorator Danych Biznesowych',
        desc: 'Podgląd na żywo zamówień, stanów magazynowych oraz kont klientów w bazach mikroserwisów.',
        tabOrders: '📦 Zamówienia (Orders)',
        tabInvoices: '📄 Faktury (Invoices)',
        tabInventory: '🏬 Magazyn (Inventory)',
        tabCustomers: '👥 Klienci (Customers)',
        tabDispatch: '✉️ Wysyłka dokumentów',
        refreshTitle: 'Odśwież dane',
        filterStatus: 'Filtruj status:',
        filterType: 'Filtruj typ:',
        filterAll: 'Wszystkie',
        sortLabel: 'Sortuj:',
        ordersSearchPlaceholder: 'ID zamówienia lub e-mail klienta...',
        invoicesSearchPlaceholder: 'Szukaj po nr faktury, kliencie, firmie, NIP, ID zamówienia...',
        dispatchSearchPlaceholder: 'Szukaj po nr dokumentu, ID zamówienia, nabywcy, adresie e-mail...',
        inventorySearchPlaceholder: 'ID produktu...',
        invoicesExportZip: '📦 Eksportuj ZIP',
        invoicesLoading: 'Ładowanie faktur...',
        ordersLoading: 'Ładowanie zamówień...',
        dispatchLoading: 'Ładowanie dokumentów do wysyłki...',
        inventoryLoading: 'Ładowanie magazynu...',
        invoicesKpiCount: 'Wystawionych faktur',
        invoicesKpiNet: 'Łącznie netto',
        invoicesKpiVat: 'Łącznie VAT',
        invoicesKpiGross: 'Łącznie brutto',
        dispatchCountAll: 'Dokumentów łącznie',
        dispatchCountFv: 'Faktury (FV)',
        dispatchCountKor: 'Korekty (KOR)',
        dispatchCountSent: 'Wysłanych e-maili',
        dispatchOpenMailpit: '📬 Otwórz Mailpit ↗',
        dispatchPillFv: '📄 Faktury (FV)',
        dispatchPillKor: '📜 Korekty (KOR)',
      },
      events: {
        eyebrow: 'ASYNC MESSAGING & RESILIENCE',
        title: 'Zdarzenia, Kolejki i Śledzenie Transakcji',
        desc: 'Monitoruj strumień Redis, tabelę Transactional Outbox, powiadomienia w Dead Letter Queue oraz wizualizuj flow po Correlation ID.',
        tabStream: '⚡ Redis Stream',
        tabOutbox: '📬 Outbox Events',
        tabDlq: '💀 Dead Letter Queue (DLQ)',
        tabTrace: '🔍 Trace Timeline',
        refreshTitle: 'Odśwież zdarzenia',
        streamEmpty: 'Ładowanie zdarzeń Redis...',
        outboxEmpty: 'Ładowanie tabeli Outbox...',
        dlqEmpty: 'Ładowanie wpisów DLQ...',
        traceInputPlaceholder: 'Wklej X-Correlation-Id (np. skopiuj z tabeli Outbox lub nagłówka odpowiedzi)...',
        traceBtnSearch: 'Szukaj śladu 🔍',
      },
      chaos: {
        eyebrow: 'CHAOS ENGINEERING & SYMULACJA AWARII',
        title: 'Symulator Awarii Mikroserwisów',
        desc: 'Sprawdź odporność systemu na żywo. Odłącz bazę lub serwis i obserwuj reakcję Sagi, Outboxa oraz Dead Letter Queue.',
        btnRestoreAll: '✅ Przywróć wszystko (Restore All)',
        inventoryDesc: 'Symuluje awarię lub zawieszenie magazynu. Podczas składania zamówienia krok rezerwacji zakończy się błędem/timeoutem, co wyzwala <strong>Saga Compensating Transaction</strong> w Order Service (zamówienie zostaje automatycznie anulowane).',
        inventoryPause: '⏸ Pauzuj serwis',
        inventoryResume: '▶ Wznów',
        mailpitDesc: 'Symuluje awarię serwera pocztowego. Po opłaceniu zamówienia wysyłka e-maila w Notification Service zakończy się błędem SMTP, powodując automatyczne przeniesienie wiadomości do <strong>Notification Dead Letter Queue (DLQ)</strong>.',
        mailpitPause: '⏸ Pauzuj SMTP',
        mailpitResume: '▶ Wznów',
        paymentDesc: 'Symuluje awarię bramki płatności. Testuje zabezpieczenia <strong>Circuit Breaker</strong> w API Gateway oraz zatrzymanie przetwarzania zamówień przed etapem fakturowania.',
        paymentPause: '⏸ Pauzuj serwis',
        paymentResume: '▶ Wznów',
        brokerDesc: 'Symuluje awarię brokera komunikatów. Zdarzenia transakcyjne nie przepadają — zostają bezpiecznie zachowane w bazie <code>payment_outbox_events</code> ze statusem <strong>PENDING</strong> i zostaną opublikowane po wznowieniu brokera.',
        brokerPause: '⏸ Pauzuj Redis',
        brokerResume: '▶ Wznów',
      },
      drawer: {
        showLog: 'Pokaż log',
      },
      toasts: {
        jobStarted: 'Uruchomiono',
        jobFinished: 'Zakończono',
        jobError: 'Błąd',
      },
    },
    en: {
      langCode: 'EN',
      viewTitles: {
        dashboard: 'Operations Center',
        services: 'Services & Endpoints',
        operations: 'Control Operations',
        data: 'Business Data Inspector',
        events: 'Events, Queues & Trace Timeline',
        chaos: 'Failure Simulator & Chaos Engineering',
        chat: 'Customer Support',
        assistant: 'AI Architecture Assistant',
        tests: 'Quality Gates & Tests',
        jobs: 'Jobs & Logs',
        terminal: 'WSL Terminal',
        about: 'About MiniTech Platform',
      },
      nav: {
        dashboard: 'Dashboard',
        services: 'Services',
        operations: 'Operations',
        data: 'Data & Store',
        events: 'Events & DLQ',
        chaos: 'Failure Simulator',
        chat: 'Customer Chat',
        assistant: 'AI Assistant',
        tests: 'Tests',
        jobs: 'Jobs & Logs',
        terminal: 'WSL Terminal',
        about: 'About App',
        localOnly: 'Local only',
      },
      topbar: {
        storeLink: 'Open store ↗',
        mailpitLink: '✉ Open Mailpit ↗',
        swaggerLink: '📖 Swagger UI ↗',
        refresh: 'Refresh data',
        profile: 'Profile',
        langBtnTitle: 'Switch language (PL / EN)',
        searchPlaceholder: 'Search commands...',
        searchTitle: 'Open command palette (Ctrl+K)',
        reportBtn: '📋 Report',
        reportTitle: 'Platform diagnostic report',
        palettePlaceholder: "Type a command or search view... (e.g. 'start', 'stock', 'secrets', 'dlq', 'tests')",
      },
      dashboard: {
        heroEyebrow: 'LOCAL DEV / TEST ENVIRONMENT',
        heroH2: 'Everything under control.<br><span>All in one place.</span>',
        heroP: 'Run microservices, reply to customers, run tests, and inspect logs in a single local dashboard.',
        btnCheckEnv: 'Check environment',
        btnShowOps: 'Show operations',
        coreServices: 'services',
        metricOnline: 'services online',
        metricDbs: 'separate databases',
        metricBroker: 'Redis + Outbox',
        metricMail: 'Mailpit + SOPS',
        platformState: 'PLATFORM STATUS',
        microservices: 'Microservices',
        viewAll: 'All →',
        quickStart: 'QUICK START',
        commonActions: 'Common actions',
        quickInfra: 'Infrastructure',
        quickDockerUp: 'Start Docker Apps',
        quickDockerUpSub: 'Docker containers',
        quickDockerDown: 'Stop Docker Apps',
        quickDockerDownSub: 'Stop containers',
        quickRunAll: 'Start services',
        quickRunAllSub: 'All applications',
        quickFrontend: 'Frontend',
        quickFrontendSub: 'Store :18070',
        quickAppVerify: 'App Verify',
        quickAppVerifySub: 'Checkout scenario',
        quickSecretsDev: 'DEV Secrets',
        quickSecretsTest: 'TEST Secrets',
        quickSecretsEditSub: 'Safe SOPS editing',
        quickSecretsSetupSub: 'Initial setup',
        recentActivity: 'RECENT ACTIVITY',
        recentJobsTitle: 'Jobs',
        fullLog: 'Full log →',
        emptyJobs: 'No jobs executed yet.',
        emptyData: 'No data.',
        btnFreshStart: '🚀 1-Click Fresh Start',
        btnDoctorFix: '🚑 Scan & repair',
        btnShowAllOps: 'All operations →',
        quickFreshStart: 'Fresh start',
        quickFreshStartSub: '1-Click Fresh Start',
        quickSeedDemo: 'Seed demo data',
        quickSeedDemoSub: 'Customers, chat, reviews',
        quickDoctorFix: 'Doctor & Repair',
        quickDoctorFixSub: 'Auto-Healing',
        quickInfraSub: 'Docker Compose databases',
      },
      services: {
        eyebrow: 'RUNTIME',
        title: 'Services & Endpoints',
        desc: 'Manage microservices lifecycle and monitor endpoint health.',
        btnRefresh: 'Recheck health',
        liveBadge: 'Live (4s)',
        ctrlEyebrow: 'DOCKER CONTAINERS / STACK',
        ctrlTitle: 'Manage All Services',
        ctrlDesc: 'Start or stop all 15 microservices and frontend with a single click.',
        btnDockerUp: 'Start Services (Docker)',
        btnDockerDown: 'Stop Services',
        btnInfraUp: 'Databases (Infra)',
        btnInfraDown: 'Stop Databases',
        btnStart: 'Start',
        btnStop: 'Stop',
        btnLogs: 'Logs',
      },
      operations: {
        eyebrow: 'ALLOW-LISTED COMMANDS',
        title: 'Control Operations',
        desc: 'Each operation invokes an existing Bash script. Commands mutating data require confirmation.',
        searchPlaceholder: 'Search operations, e.g. backup, JWT, invoice…',
        allGroups: 'All groups',
        swaggerBannerTitle: 'Swagger UI — All 15 Microservices',
        swaggerBannerDesc: 'Integrated OpenAPI documentation with service selector. Execute endpoints directly through API Gateway without CORS issues.',
        swaggerBannerBtn: 'Open Swagger UI',
        btnRun: 'Run',
        btnConfigure: 'Configure & Run',
        btnOpenEditor: 'Open editor',
        btnCreateFile: 'Create file',
        btnFileExists: 'File already exists',
      },
      chat: {
        eyebrow: 'CUSTOMER SUPPORT',
        title: 'Store Messages',
        desc: 'Replies appear in the customer widget and are forwarded to Notification Service as email.',
        btnRefresh: 'Refresh chat',
        searchPlaceholder: 'Search messages...',
        statusAll: 'All',
        statusNew: 'New',
        statusAnswered: 'Answered',
        emptyList: 'Loading conversations…',
        noMessages: 'No messages.',
        selectChat: 'Select a conversation from the list.',
        replyFormLabel: 'Reply',
        replyPlaceholder: 'Write a clear and helpful reply for the customer…',
        btnSendReply: 'Send reply',
        replySuccess: 'Reply saved. Notification is queued for handoff.',
        operatorUnavailable: 'Operator access verification is unavailable.',
        operatorForbidden: 'Verified operator session is required.',
        operatorCannotVerify: 'Could not verify operator access.',
        loadOlder: 'Load older messages',
        customerInquiry: 'Customer inquiry',
      },
      assistant: {
        eyebrow: 'AI & ARCHITECTURE SUPPORT',
        title: 'MiniTech Architecture Assistant',
        desc: 'Ask questions about the 15 microservices architecture, routing, databases, event flows, and commands.',
        btnSettings: 'AI Settings',
        inputPlaceholder: 'Ask a question about the Mini Order Platform…',
        btnSend: 'Send',
        welcomeTitle: 'Hello! I am the MiniTech Architecture Assistant.',
        welcomeP1: 'By default, I operate in embedded (offline) mode using the frequently asked question chips placed at the top of the screen and log diagnostics.',
        welcomeP2: 'Click any chip above to get an instant answer. To chat with me freely as a conversational assistant, connect an external agent or local LLM (e.g. from your machine) in ⚙ AI Settings.',
        thinking: 'Assistant is thinking…',
        configSaved: 'AI engine settings saved successfully.',
        errorEmpty: 'Please enter a question before sending.',
      },
      tests: {
        eyebrow: 'QUALITY GATES',
        title: 'Quality Gates & Tests',
        desc: 'From fast smoke tests to full E2E and multi-browser Playwright regressions.',
        btnRun: 'Run test',
      },
      jobs: {
        eyebrow: 'LOCAL HISTORY',
        title: 'Jobs & Logs',
        desc: 'Logs are stored in the private gitIgnoreOwn directory and are never sent to GitHub.',
        btnRefresh: 'Refresh',
        emptyHistory: 'No jobs.',
        selectJobPrompt: 'Select a job to view its log.',
        waitingOutput: 'Waiting for output…',
        noLogContent: 'No log content available.',
      },
      about: {
        introEyebrow: 'ARCHITECTURE & GUIDE',
        introTitle: 'About MiniTech Platform',
        introDesc: 'Comprehensive guide to the 15 microservices architecture, 14 PostgreSQL databases, 35 Docker containers, event-driven streaming, and resilience patterns.',
        stat1Title: '15 Microservices',
        stat1Sub: 'Java 25 · Spring Boot 3.4 · REST',
        stat2Title: '14 Isolated Databases',
        stat2Sub: 'PostgreSQL 16 · Flyway Migrations',
        stat3Title: '35 Docker Containers',
        stat3Sub: '1-Click full start · Cross-platform',
        stat4Title: 'Redis Streams & Outbox',
        stat4Sub: 'Async Event Broker & DLQ',
        stat5Title: 'SOPS + Age Vault',
        stat5Sub: 'Encrypted Secrets for DEV / TEST',
        stat6Title: 'Quality Gate & AI',
        stat6Sub: '31 Playwright tests · AI Assistant',
        purposeEyebrow: 'PURPOSE & OVERVIEW',
        purposeTitle: 'What is Mini Order Platform?',
        purposeContent: '<p>Mini Order Platform is an enterprise e-commerce system built upon a <strong>true microservices architecture</strong> (monorepo). Each domain service runs as an independent process with its own dedicated network port and isolated PostgreSQL database.</p><p>The platform delivers the entire shopping lifecycle — from daily rotating deals and cart management, to delivery quotes, warehouse stock reservations (Saga), payment processing, VAT invoicing, async email notifications (Mailpit), shipment tracking, returns, and live customer chat.</p><p>The Control Center (port <code>:18100</code>) integrates container orchestration, log auto-diagnostics, error knowledge base (1-Click Auto-Fix), chaos failure simulations, and an embedded AI Architecture Assistant. Without configuring an external CLI or agent, the AI assistant operates in its default embedded (offline) mode using the prompt chips with frequently asked questions placed at the top. To use the assistant as a full conversational chat, configure an external agent or local LLM (e.g. from your machine) in AI Settings.</p>',
        patternsEyebrow: 'DESIGN PATTERNS',
        patternsTitle: 'Core Architectural Pillars',
        feat1Badge: '🛡️ Database per Service',
        feat1Desc: 'Zero cross-database table joins. Product Service never reads inventory_db, and Order Service communicates with stock exclusively via HTTP API contracts.',
        feat2Badge: '📦 Transactional Outbox Pattern',
        feat2Desc: 'Payment execution and event records are persisted atomically in a single PostgreSQL transaction (payment_outbox). A dedicated publisher relays events to Redis Streams.',
        feat3Badge: '🔄 Saga Pattern & Compensations',
        feat3Desc: 'No distributed two-phase locking. If warehouse reservation fails, the saga triggers compensation: releasing items and marking the order as cancelled.',
        feat4Badge: '🔑 Request Idempotency',
        feat4Desc: 'Retrying requests with an Idempotency-Key header prevents duplicate orders, redundant reservations, and multiple customer charges.',
        feat5Badge: '⚡ Chaos Engineering & Resilience',
        feat5Desc: 'Built-in Chaos Simulator in Control Center, Resilience4j Circuit Breakers, Bulkheads, Exponential Backoff Retries, and Dead Letter Queue (DLQ).',
        feat6Badge: '🤖 AI Assistant & Auto-Diagnostics',
        feat6Desc: 'Integrated AI Architecture Assistant operating by default in embedded offline mode (frequently asked question chips and log diagnostics) with an option to connect external LLM agents (chat), Error Knowledge Base, and 1-Click Auto-Fix.',
        feat7Badge: '🔐 Security & Authentication',
        feat7Desc: 'Short-lived in-memory Access JWT (RS256), rotating Refresh Token in HttpOnly cookie, 2FA TOTP (Google Authenticator), and WebAuthn / Passkey support.',
        feat8Badge: '🧪 Quality Gates & Test Suite',
        feat8Desc: 'Full test pyramid: 31 Playwright UI E2E regression specs, OpenAPI v1 contract verification, security DAST scans, load tests, and 17 Maven modules.',
        modulesEyebrow: 'MODULE STRUCTURE',
        modulesTitle: 'Map of 15 Platform Microservices',
        grp1Title: 'Edge Layer & Frontend',
        grp2Title: 'Catalog, Deals & Reviews',
        grp3Title: 'Sales, Warehouse & Returns',
        grp4Title: 'Payments, Billing & Delivery',
        grp5Title: 'Customers, Mail & Support',
        flowEyebrow: 'DATA FLOW',
        flowTitle: 'Order Lifecycle Step by Step',
        step1Title: 'Catalog & Cart',
        step1Desc: 'Customer browses products at :18070. The cart is maintained locally in the browser (does not hold inventory stock).',
        step2Title: 'Pricing & Delivery',
        step2Desc: 'Delivery Service calculates shipping costs, and Promotion Service validates coupons. Customer selects VAT invoice option.',
        step3Title: 'Order Aggregate (Saga)',
        step3Desc: 'Order Service creates price snapshot and calls Inventory Service to lock requested items (status CONFIRMED).',
        step4Title: 'Payment & Outbox',
        step4Desc: 'Payment Service authorizes transaction, confirms stock reservation, generates VAT invoice, and writes event to payment_outbox.',
        step5Title: 'Redis, Email & PDF',
        step5Desc: 'Event is published to Redis Stream. Notification Service consumes it and sends email with PDF invoice attachment to Mailpit (:18025).',
      },
      terminal: {
        eyebrow: 'FULL CLI COMPATIBILITY',
        title: 'WSL Terminal',
        desc: 'Use for interactive SOPS setup wizards, psql, Vim, and full classic menu options.',
        btnStart: 'Start session',
        notStarted: 'Session has not been started.',
        inputPlaceholder: 'Enter option or command and press Enter',
        btnSend: 'Send',
      },
      modals: {
        dangerConfirm: 'I understand that this operation may modify data or environment state.',
        btnCancel: 'Cancel',
        btnRun: 'Run',
        noParams: 'This operation does not require additional parameters.',
        confirmOpToast: 'Please confirm operation before executing.',
        secretSaveConfirm: 'I understand that saving changes the encrypted secrets of the selected profile.',
        secretSaveSubmit: 'Save encrypted',
        secretSetupSubmit: 'Create and encrypt',
        dispatchEyebrow: 'DOCUMENT DISPATCH (EMAIL / SMTP)',
        dispatchTitle: 'Send document to customer',
        dispatchRecipient: 'Recipient email address *',
        dispatchSubject: 'Email subject *',
        dispatchMessage: 'Custom message / Note for customer (optional)',
        dispatchAttachment: 'PDF attachment downloaded from Billing Service will be attached.',
        dispatchSubmit: '📤 Send email',
        correctionEyebrow: 'CORRECTION INVOICE (COR)',
        correctionTitle: 'Issue invoice correction',
        correctionReason: 'Correction reason *',
        correctionDelta: 'Gross delta amount (PLN) *',
        correctionSubmit: 'Issue correction',
        secretEyebrow: 'SECURE SOPS EDITING',
        reportEyebrow: 'ENVIRONMENT DIAGNOSTIC REPORT',
        reportTitle: '📋 MiniTech Platform Status',
        reportCopy: '📋 Copy to clipboard',
      },
      diagnostics: {
        unknownIssue: 'Unknown Issue',
        detectedProblem: 'Detected Problem',
        causeLabel: '👉 Cause:',
        solutionLabel: '💡 Solution:',
        askAiBtn: '✦ Ask AI Assistant',
        quickFix: 'Quick Fix',
      },
      data: {
        eyebrow: 'DATABASES & BUSINESS ENTITIES',
        title: 'Business Data Inspector',
        desc: 'Live inspection of orders, invoices, inventory stock, and customer accounts across microservice databases.',
        tabOrders: '📦 Orders',
        tabInvoices: '📄 Invoices',
        tabInventory: '🏬 Inventory',
        tabCustomers: '👥 Customers',
        tabDispatch: '✉️ Document Dispatch',
        refreshTitle: 'Refresh data',
        filterStatus: 'Filter status:',
        filterType: 'Filter type:',
        filterAll: 'All',
        sortLabel: 'Sort:',
        ordersSearchPlaceholder: 'Order ID or customer email...',
        invoicesSearchPlaceholder: 'Search by invoice #, buyer, company, VAT ID, order ID...',
        dispatchSearchPlaceholder: 'Search by doc #, order ID, buyer, recipient email...',
        inventorySearchPlaceholder: 'Product ID or name...',
        invoicesExportZip: '📦 Export ZIP',
        invoicesLoading: 'Loading invoices...',
        ordersLoading: 'Loading orders...',
        dispatchLoading: 'Loading dispatchable documents...',
        inventoryLoading: 'Loading stock...',
        invoicesKpiCount: 'Invoices issued',
        invoicesKpiNet: 'Total net',
        invoicesKpiVat: 'Total VAT',
        invoicesKpiGross: 'Total gross',
        dispatchCountAll: 'Total documents',
        dispatchCountFv: 'Invoices (INV)',
        dispatchCountKor: 'Corrections (COR)',
        dispatchCountSent: 'Sent emails',
        dispatchOpenMailpit: '📬 Open Mailpit ↗',
        dispatchPillFv: '📄 Invoices (INV)',
        dispatchPillKor: '📜 Corrections (COR)',
      },
      events: {
        eyebrow: 'ASYNC MESSAGING & RESILIENCE',
        title: 'Events, Queues & Trace Timeline',
        desc: 'Monitor Redis streams, Transactional Outbox table, Dead Letter Queue notifications, and end-to-end trace by Correlation ID.',
        tabStream: '⚡ Redis Stream',
        tabOutbox: '📬 Outbox Events',
        tabDlq: '💀 Dead Letter Queue (DLQ)',
        tabTrace: '🔍 Trace Timeline',
        refreshTitle: 'Refresh events',
        streamEmpty: 'Loading Redis events...',
        outboxEmpty: 'Loading Outbox table...',
        dlqEmpty: 'Loading DLQ entries...',
        traceInputPlaceholder: 'Paste X-Correlation-Id (e.g. copied from Outbox table or response header)...',
        traceBtnSearch: 'Trace Search 🔍',
      },
      chaos: {
        eyebrow: 'CHAOS ENGINEERING & FAILURE SIMULATION',
        title: 'Microservices Failure Simulator',
        desc: 'Test system resilience live. Disconnect a database or service and watch Saga, Outbox, and Dead Letter Queue reaction.',
        btnRestoreAll: '✅ Restore All',
        inventoryDesc: 'Simulates inventory failure or freeze. During checkout, the reservation step times out or fails, triggering a <strong>Saga Compensating Transaction</strong> in Order Service (order is automatically cancelled).',
        inventoryPause: '⏸ Pause service',
        inventoryResume: '▶ Resume',
        mailpitDesc: 'Simulates mail server outage. After payment, email dispatch in Notification Service encounters an SMTP error, automatically routing the message to <strong>Notification Dead Letter Queue (DLQ)</strong>.',
        mailpitPause: '⏸ Pause SMTP',
        mailpitResume: '▶ Resume',
        paymentDesc: 'Simulates payment gateway outage. Tests API Gateway <strong>Circuit Breaker</strong> safeguards and stops order processing before the invoicing step.',
        paymentPause: '⏸ Pause service',
        paymentResume: '▶ Resume',
        brokerDesc: 'Simulates message broker outage. Transactional events are not lost — they are safely preserved in <code>payment_outbox_events</code> with status <strong>PENDING</strong> and published once the broker resumes.',
        brokerPause: '⏸ Pause Redis',
        brokerResume: '▶ Resume',
      },
      drawer: {
        showLog: 'Show log',
      },
      toasts: {
        jobStarted: 'Started',
        jobFinished: 'Completed',
        jobError: 'Error',
      },
    },
  };

export function normalizePanelLanguage(language) {
  return language === 'en' ? 'en' : 'pl';
}

export function getPanelMessages(language) {
  return I18N[normalizePanelLanguage(language)];
}

export function renderPanelLanguage({ language, profile, document, currentView = 'dashboard' }) {
  language = normalizePanelLanguage(language);
  const dict = getPanelMessages(language);
  const isEn = language === 'en';
  const $ = (selector, root = document) => (root ? root.querySelector(selector) : null);
  const $$ = (selector, root = document) => (root ? [...root.querySelectorAll(selector)] : []);
  const escapeHtml = (value) => String(value ?? '').replace(/[&<>'"]/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[char]));
  document.documentElement.lang = language;
  const langVal = $('#lang-value');
  if (langVal) langVal.textContent = dict.langCode;

  const navMap = {
    dashboard: dict.nav.dashboard,
    services: dict.nav.services,
    operations: dict.nav.operations,
    data: dict.nav.data,
    events: dict.nav.events,
    chaos: dict.nav.chaos,
    chat: dict.nav.chat,
    assistant: dict.nav.assistant,
    tests: dict.nav.tests,
    jobs: dict.nav.jobs,
    terminal: dict.nav.terminal,
    about: dict.nav.about,
  };
  $$('.nav-item').forEach(btn => {
    const view = btn.dataset.view;
    if (navMap[view]) {
      const icon = $('span', btn)?.outerHTML || '';
      const badge = $('#chat-badge', btn)?.outerHTML || '';
      btn.innerHTML = `${icon}${escapeHtml(navMap[view])} ${badge}`;
    }
  });
  const localOnly = $('.sidebar-footer strong');
  if (localOnly) localOnly.textContent = dict.nav.localOnly;

  const swaggerLink = $('#swagger-link');
  if (swaggerLink) swaggerLink.textContent = dict.topbar.swaggerLink || '📖 Swagger UI ↗';
  const storeLink = $('#store-link');
  if (storeLink) storeLink.textContent = dict.topbar.storeLink;
  const mailpitLink = $('#mailpit-link');
  if (mailpitLink) mailpitLink.textContent = dict.topbar.mailpitLink || '✉ Otwórz Mailpit ↗';
  const refreshBtn = $('#refresh-button');
  const logoutBtn = $('#panel-logout');
  if(logoutBtn)logoutBtn.textContent=language==='en'?'End session':'Zakończ sesję';
  if (refreshBtn) {
    refreshBtn.title = dict.topbar.refresh;
    refreshBtn.setAttribute('aria-label', dict.topbar.refresh);
  }
  const profilePill = $('.profile-pill');
  if (profilePill) profilePill.innerHTML = `${dict.topbar.profile} <strong id="profile-value">${escapeHtml(profile)}</strong>`;
  const langSwitchBtn = $('#lang-switch-button');
  if (langSwitchBtn) langSwitchBtn.title = dict.topbar.langBtnTitle;

  const viewTitle = $('#view-title');
  if (viewTitle) viewTitle.textContent = dict.viewTitles[currentView] || dict.viewTitles.dashboard;

  const d = dict.dashboard;
  const heroEyebrow = $('#view-dashboard .hero-panel .eyebrow');
  if (heroEyebrow) heroEyebrow.textContent = d.heroEyebrow;
  const heroH2 = $('#view-dashboard .hero-panel h2');
  if (heroH2) heroH2.innerHTML = d.heroH2;
  const heroP = $('#view-dashboard .hero-panel p:not(.eyebrow)');
  if (heroP) heroP.textContent = d.heroP;
  const heroFreshStart = $('button[data-action="fresh-start"]', $('#view-dashboard .hero-panel'));
  if (heroFreshStart) heroFreshStart.textContent = d.btnFreshStart;
  const heroDoctorFix = $('button[data-action="doctor-fix"]', $('#view-dashboard .hero-panel'));
  if (heroDoctorFix) heroDoctorFix.textContent = d.btnDoctorFix;
  const heroShowOps = $('button[data-view-target="operations"]', $('#view-dashboard .hero-panel'));
  if (heroShowOps) heroShowOps.textContent = d.btnShowAllOps || d.btnShowOps;
  const checkEnvBtn = $('button[data-action="environment-check"]', $('#view-dashboard'));
  if (checkEnvBtn) checkEnvBtn.textContent = d.btnCheckEnv;
  const showOpsBtn = $('button[data-view-target="operations"]', $('#view-dashboard'));
  if (showOpsBtn) showOpsBtn.textContent = d.btnShowOps;
  const coreServices = $('.hero-orbit .core small');
  if (coreServices) coreServices.textContent = d.coreServices;

  const metricCards = $$('.metric-grid article');
  if (metricCards[0]) { const sm = $('small', metricCards[0]); if (sm) sm.textContent = d.metricOnline; }
  if (metricCards[1]) { const sm = $('small', metricCards[1]); if (sm) sm.textContent = d.metricDbs; }
  if (metricCards[2]) { const sm = $('small', metricCards[2]); if (sm) sm.textContent = d.metricBroker; }
  if (metricCards[3]) { const sm = $('small', metricCards[3]); if (sm) sm.textContent = `${profile.toUpperCase()} · ${d.metricMail}`; }

  const panels = $$('#view-dashboard .panel');
  if (panels[0]) {
    const pEye = $('.eyebrow', panels[0]); if (pEye) pEye.textContent = d.platformState;
    const pH3 = $('h3', panels[0]); if (pH3) pH3.textContent = d.microservices;
    const pBtn = $('button[data-view-target="services"]', panels[0]); if (pBtn) pBtn.textContent = d.viewAll;
  }
  if (panels[1]) {
    const pEye = $('.eyebrow', panels[1]); if (pEye) pEye.textContent = d.quickStart;
    const pH3 = $('h3', panels[1]); if (pH3) pH3.textContent = d.commonActions;
  }
  if (panels[2]) {
    const pEye = $('.eyebrow', panels[2]); if (pEye) pEye.textContent = d.recentActivity;
    const pH3 = $('h3', panels[2]); if (pH3) pH3.textContent = d.recentJobsTitle;
    const pBtn = $('button[data-view-target="jobs"]', panels[2]); if (pBtn) pBtn.textContent = d.fullLog;
  }

  const qFreshStart = $('button.quick-tile[data-action="fresh-start"]', $('#view-dashboard'));
  if (qFreshStart) {
    const st = $('strong', qFreshStart); if (st) st.textContent = d.quickFreshStart;
    const sm = $('small', qFreshStart); if (sm) sm.textContent = d.quickFreshStartSub;
  }
  const qSeedDemo = $('button.quick-tile[data-action="seed-demo-data"]', $('#view-dashboard'));
  if (qSeedDemo) {
    const st = $('strong', qSeedDemo); if (st) st.textContent = d.quickSeedDemo;
    const sm = $('small', qSeedDemo); if (sm) sm.textContent = d.quickSeedDemoSub;
  }
  const qDoctorFix = $('button.quick-tile[data-action="doctor-fix"]', $('#view-dashboard'));
  if (qDoctorFix) {
    const st = $('strong', qDoctorFix); if (st) st.textContent = d.quickDoctorFix;
    const sm = $('small', qDoctorFix); if (sm) sm.textContent = d.quickDoctorFixSub;
  }
  const quickInfra = $('button[data-action="infra-up"]', $('#view-dashboard'));
  if (quickInfra) {
    const st = $('strong', quickInfra); if (st) st.textContent = d.quickInfra;
    const sm = $('small', quickInfra); if (sm) sm.textContent = d.quickInfraSub;
  }
  const quickDockerUp = $('button[data-action="docker-apps-up"]', $('#view-dashboard'));
  if (quickDockerUp) {
    const st = $('strong', quickDockerUp); if (st) st.textContent = d.quickDockerUp;
    const sm = $('small', quickDockerUp); if (sm) sm.textContent = d.quickDockerUpSub;
  }
  const quickDockerDown = $('button[data-action="docker-apps-down"]', $('#view-dashboard'));
  if (quickDockerDown) {
    const st = $('strong', quickDockerDown); if (st) st.textContent = d.quickDockerDown;
    const sm = $('small', quickDockerDown); if (sm) sm.textContent = d.quickDockerDownSub;
  }
  const quickRun = $('button[data-action="app-run-all"]', $('#view-dashboard'));
  if (quickRun) {
    const st = $('strong', quickRun); if (st) st.textContent = d.quickRunAll;
    const sm = $('small', quickRun); if (sm) sm.textContent = d.quickRunAllSub;
  }
  const quickFront = $('button[data-action="frontend-up"]', $('#view-dashboard'));
  if (quickFront) {
    const st = $('strong', quickFront); if (st) st.textContent = d.quickFrontend;
    const sm = $('small', quickFront); if (sm) sm.textContent = d.quickFrontendSub;
  }
  const quickVerify = $('button[data-action="app-verify"]', $('#view-dashboard'));
  if (quickVerify) {
    const st = $('strong', quickVerify); if (st) st.textContent = d.quickAppVerify;
    const sm = $('small', quickVerify); if (sm) sm.textContent = d.quickAppVerifySub;
  }

  const s = dict.services;
  const sEye = $('#view-services .section-intro .eyebrow'); if (sEye) sEye.textContent = s.eyebrow;
  const sH2 = $('#view-services .section-intro h2'); if (sH2) sH2.textContent = s.title;
  const sP = $('#view-services .section-intro p:not(.eyebrow)'); if (sP) sP.textContent = s.desc;
  const sRef = $('#health-refresh'); if (sRef) sRef.textContent = `↻ ${s.btnRefresh}`;
  const liveText = $('#live-pulse-text'); if (liveText) liveText.textContent = s.liveBadge || 'Na żywo (4s)';

  const ctrlEye = $('#view-services .services-control-panel .eyebrow'); if (ctrlEye) ctrlEye.textContent = s.ctrlEyebrow;
  const ctrlH3 = $('#view-services .services-control-panel h3'); if (ctrlH3) ctrlH3.textContent = s.ctrlTitle;
  const ctrlDesc = $('#view-services .services-control-panel p.muted'); if (ctrlDesc) ctrlDesc.textContent = s.ctrlDesc;
  const btnDockUp = $('#view-services button[data-action="docker-apps-up"]'); if (btnDockUp) btnDockUp.innerHTML = `<span>▶</span> ${escapeHtml(s.btnDockerUp)}`;
  const btnDockDown = $('#view-services button[data-action="docker-apps-down"]'); if (btnDockDown) btnDockDown.innerHTML = `<span>⏹</span> ${escapeHtml(s.btnDockerDown)}`;
  const btnInfUp = $('#view-services button[data-action="infra-up"]'); if (btnInfUp) btnInfUp.innerHTML = `<span>◫</span> ${escapeHtml(s.btnInfraUp)}`;
  const btnInfDown = $('#view-services button[data-action="infra-down"]'); if (btnInfDown) btnInfDown.innerHTML = `<span>⏹</span> ${escapeHtml(s.btnInfraDown)}`;

  const o = dict.operations;
  const oEye = $('#view-operations .section-intro .eyebrow'); if (oEye) oEye.textContent = o.eyebrow;
  const oH2 = $('#view-operations .section-intro h2'); if (oH2) oH2.textContent = o.title;
  const oP = $('#view-operations .section-intro p:not(.eyebrow)'); if (oP) oP.textContent = o.desc;
  const oSearch = $('#operation-search'); if (oSearch) oSearch.placeholder = o.searchPlaceholder;
  const oSwTitle = $('#swagger-banner-title'); if (oSwTitle && o.swaggerBannerTitle) oSwTitle.textContent = o.swaggerBannerTitle;
  const oSwDesc = $('#swagger-banner-desc'); if (oSwDesc && o.swaggerBannerDesc) oSwDesc.textContent = o.swaggerBannerDesc;
  const oSwBtn = $('#swagger-banner-link span:first-child'); if (oSwBtn && o.swaggerBannerBtn) oSwBtn.textContent = o.swaggerBannerBtn;

  const c = dict.chat;
  const cEye = $('#view-chat .section-intro .eyebrow'); if (cEye) cEye.textContent = c.eyebrow;
  const cH2 = $('#view-chat .section-intro h2'); if (cH2) cH2.textContent = c.title;
  const cP = $('#view-chat .section-intro p:not(.eyebrow)'); if (cP) cP.textContent = c.desc;
  const cRef = $('#chat-refresh'); if (cRef) cRef.textContent = c.btnRefresh;
  const cSearch = $('#chat-page-search'); if (cSearch && c.searchPlaceholder) cSearch.placeholder = c.searchPlaceholder;
  const cStatus = $('#chat-page-status');
  if (cStatus && c.statusAll && cStatus.options && cStatus.options.length >= 3) {
    cStatus.options[0].textContent = c.statusAll;
    cStatus.options[1].textContent = c.statusNew;
    cStatus.options[2].textContent = c.statusAnswered;
  }
  const chatDetailEmpty = $('#conversation-detail .empty-state');
  if (chatDetailEmpty) {
    chatDetailEmpty.textContent = c.selectChat;
  }

  const a = dict.assistant;
  if (a) {
    const aEye = $('#view-assistant .section-intro .eyebrow'); if (aEye) aEye.textContent = a.eyebrow;
    const aH2 = $('#ai-view-h2'); if (aH2) aH2.textContent = a.title;
    const aP = $('#ai-view-desc'); if (aP) aP.textContent = a.desc;
    const aInp = $('#ai-input'); if (aInp) aInp.placeholder = a.inputPlaceholder;
    const aSetBtn = $('#ai-settings-button');
    if (aSetBtn) {
      aSetBtn.innerHTML = `⚙ ${escapeHtml(a.btnSettings || 'Ustawienia AI')}`;
    }
  }

  const t = dict.tests;
  const tEye = $('#view-tests .section-intro .eyebrow'); if (tEye) tEye.textContent = t.eyebrow;
  const tH2 = $('#view-tests .section-intro h2'); if (tH2) tH2.textContent = t.title;
  const tP = $('#view-tests .section-intro p:not(.eyebrow)'); if (tP) tP.textContent = t.desc;

  const j = dict.jobs;
  const jEye = $('#view-jobs .section-intro .eyebrow'); if (jEye) jEye.textContent = j.eyebrow;
  const jH2 = $('#view-jobs .section-intro h2'); if (jH2) jH2.textContent = j.title;
  const jP = $('#view-jobs .section-intro p:not(.eyebrow)'); if (jP) jP.textContent = j.desc;
  const jRef = $('#jobs-refresh'); if (jRef) jRef.textContent = j.btnRefresh;

  const term = dict.terminal;
  const termEye = $('#view-terminal .section-intro .eyebrow'); if (termEye) termEye.textContent = term.eyebrow;
  const termH2 = $('#view-terminal .section-intro h2'); if (termH2) termH2.textContent = term.title;
  const termP = $('#view-terminal .section-intro p:not(.eyebrow)'); if (termP) termP.textContent = term.desc;
  const termStart = $('#terminal-start'); if (termStart) termStart.textContent = term.btnStart;
  const termInp = $('#terminal-input'); if (termInp) termInp.placeholder = term.inputPlaceholder;
  const termBtn = $('#terminal-form button'); if (termBtn) termBtn.textContent = term.btnSend;

  const dt = dict.data;
  if (dt) {
    const dtEye = $('#data-intro-eyebrow'); if (dtEye) dtEye.textContent = dt.eyebrow;
    const dtH2 = $('#data-intro-title'); if (dtH2) dtH2.textContent = dt.title;
    const dtP = $('#data-intro-desc'); if (dtP) dtP.textContent = dt.desc;
    const dtOrd = $('#data-tab-orders-btn'); if (dtOrd) dtOrd.textContent = dt.tabOrders;
    const dtInv = $('#data-tab-invoices-btn'); if (dtInv) dtInv.textContent = dt.tabInvoices;
    const dtDsp = $('#data-tab-dispatch-btn'); if (dtDsp) dtDsp.textContent = dt.tabDispatch;
    const dtStk = $('#data-tab-inventory-btn'); if (dtStk) dtStk.textContent = dt.tabInventory;
    const dtCst = $('#data-tab-customers-btn'); if (dtCst) dtCst.textContent = dt.tabCustomers;
    const dtRef = $('#data-refresh-btn'); if (dtRef) dtRef.title = dt.refreshTitle;

    // Orders filters & headers
    const oFilterLbl = $('#orders-filter-label'); if (oFilterLbl) oFilterLbl.textContent = dt.filterStatus;
    const oPillAll = $('button.filter-pill[data-status-filter="ALL"]', $('#view-data'));
    if (oPillAll) oPillAll.textContent = dt.filterAll;
    const oSortLbl = $('#orders-sort-label'); if (oSortLbl) oSortLbl.textContent = dt.sortLabel;
    const oSearchInp = $('#orders-search-input'); if (oSearchInp) oSearchInp.placeholder = dt.ordersSearchPlaceholder;
    const ordersSortOptions = isEn ? {
      date_desc: '📅 Date (newest)', date_asc: '📅 Date (oldest)',
      id_desc: '🔢 ID (descending)', id_asc: '🔢 ID (ascending)',
      amount_desc: '💰 Amount (highest)', amount_asc: '💰 Amount (lowest)',
      client_asc: '👤 Customer (A → Z)', client_desc: '👤 Customer (Z → A)',
      status_asc: '🏷️ Status (A → Z)', status_desc: '🏷️ Status (Z → A)',
      invoice_desc: '📄 Invoice (with invoice)'
    } : {
      date_desc: '📅 Data (najnowsze)', date_asc: '📅 Data (najstarsze)',
      id_desc: '🔢 ID (malejąco)', id_asc: '🔢 ID (rosnąco)',
      amount_desc: '💰 Wartość (najwyższa)', amount_asc: '💰 Wartość (najniższa)',
      client_asc: '👤 Klient (A → Z)', client_desc: '👤 Klient (Z → A)',
      status_asc: '🏷️ Status (A → Z)', status_desc: '🏷️ Status (Z → A)',
      invoice_desc: '📄 Faktura (z fakturą)'
    };
    $$('#orders-sort-select option').forEach(opt => { if (ordersSortOptions[opt.value]) opt.textContent = ordersSortOptions[opt.value]; });
    const ordersLabels = isEn
      ? { id: 'ID', client: 'Customer', amount: 'Amount', status: 'Status', date: 'Creation date', invoice: 'Invoice' }
      : { id: 'ID', client: 'Klient', amount: 'Wartość', status: 'Status', date: 'Data utworzenia', invoice: 'Faktura' };
    $$('#orders-table th[data-sort]').forEach(th => {
      const field = th.dataset.sort;
      if (ordersLabels[field]) {
        const ind = $('span.sort-indicator', th)?.outerHTML || '⇅';
        th.innerHTML = `${escapeHtml(ordersLabels[field])} ${ind}`;
        th.title = `${isEn ? 'Sort by' : 'Sortuj według'}: ${ordersLabels[field]}`;
      }
    });

    // Invoices KPIs & headers
    const invCountLbl = $('#invoices-kpi-count')?.parentElement?.querySelector('small');
    if (invCountLbl) invCountLbl.textContent = dt.invoicesKpiCount;
    const invNetLbl = $('#invoices-kpi-net')?.parentElement?.querySelector('small');
    if (invNetLbl) invNetLbl.textContent = dt.invoicesKpiNet;
    const invVatLbl = $('#invoices-kpi-vat')?.parentElement?.querySelector('small');
    if (invVatLbl) invVatLbl.textContent = dt.invoicesKpiVat;
    const invGrossLbl = $('#invoices-kpi-gross')?.parentElement?.querySelector('small');
    if (invGrossLbl) invGrossLbl.textContent = dt.invoicesKpiGross;
    const invSearch = $('#invoices-search-input'); if (invSearch) invSearch.placeholder = dt.invoicesSearchPlaceholder;
    const invSortLbl = $('#invoices-sort-label'); if (invSortLbl) invSortLbl.textContent = dt.sortLabel;
    const invoicesSortOptions = isEn ? {
      date_desc: '📅 Date (newest)', date_asc: '📅 Date (oldest)',
      id_desc: '🔢 ID (descending)', id_asc: '🔢 ID (ascending)',
      amount_desc: '💰 Gross amount (highest)', amount_asc: '💰 Gross amount (lowest)',
      buyer_asc: '👤 Buyer (A → Z)', buyer_desc: '👤 Buyer (Z → A)',
      number_asc: '📄 Invoice # (A → Z)', number_desc: '📄 Invoice # (Z → A)'
    } : {
      date_desc: '📅 Data (najnowsze)', date_asc: '📅 Data (najstarsze)',
      id_desc: '🔢 ID (malejąco)', id_asc: '🔢 ID (rosnąco)',
      amount_desc: '💰 Kwota brutto (najwyższa)', amount_asc: '💰 Kwota brutto (najniższa)',
      buyer_asc: '👤 Nabywca (A → Z)', buyer_desc: '👤 Nabywca (Z → A)',
      number_asc: '📄 Numer faktury (A → Z)', number_desc: '📄 Numer faktury (Z → A)'
    };
    $$('#invoices-sort-select option').forEach(opt => { if (invoicesSortOptions[opt.value]) opt.textContent = invoicesSortOptions[opt.value]; });
    const invZipBtn = $('#invoices-export-zip-btn'); if (invZipBtn) invZipBtn.textContent = dt.invoicesExportZip;
    const invoicesLabels = isEn
      ? { number: 'Invoice #', order: 'Order', buyer: 'Buyer / Company', nip: 'Tax ID', email: 'Email', net: 'Net amount', vat: 'VAT amount', amount: 'Gross amount', date: 'Issue date', actions: 'Actions' }
      : { number: 'Nr faktury', order: 'Zamówienie', buyer: 'Nabywca / Firma', nip: 'NIP', email: 'E-mail', net: 'Kwota netto', vat: 'Kwota VAT', amount: 'Kwota brutto', date: 'Data wystawienia', actions: 'Akcje' };
    const invThs = $$('#invoices-table thead th');
    if (invThs.length >= 10) {
      const keys = ['number', 'order', 'buyer', 'nip', 'email', 'net', 'vat', 'amount', 'date', 'actions'];
      invThs.forEach((th, idx) => {
        const k = keys[idx];
        if (invoicesLabels[k]) {
          const ind = $('span.sort-indicator', th)?.outerHTML || '';
          th.innerHTML = `${escapeHtml(invoicesLabels[k])} ${ind}`.trim();
        }
      });
    }

    // Dispatch subpane stats, pills, headers
    const dCountAllLbl = $('#dispatch-count-all')?.parentElement?.querySelector('.dispatch-stat-lbl');
    if (dCountAllLbl) dCountAllLbl.textContent = dt.dispatchCountAll;
    const dCountFvLbl = $('#dispatch-count-fv')?.parentElement?.querySelector('.dispatch-stat-lbl');
    if (dCountFvLbl) dCountFvLbl.textContent = dt.dispatchCountFv;
    const dCountKorLbl = $('#dispatch-count-kor')?.parentElement?.querySelector('.dispatch-stat-lbl');
    if (dCountKorLbl) dCountKorLbl.textContent = dt.dispatchCountKor;
    const dCountSentLbl = $('#dispatch-count-sent')?.parentElement?.querySelector('.dispatch-stat-lbl');
    if (dCountSentLbl) dCountSentLbl.textContent = dt.dispatchCountSent;
    const dMailpitLink = $('.dispatch-summary-box a[target="_blank"]');
    if (dMailpitLink) dMailpitLink.textContent = dt.dispatchOpenMailpit;
    const dFilterLbl = $('#data-pane-dispatch .toolbar-label');
    if (dFilterLbl) dFilterLbl.textContent = dt.filterType;
    const dPillAll = $('button.filter-pill[data-dispatch-filter="ALL"]'); if (dPillAll) dPillAll.textContent = dt.filterAll;
    const dPillFv = $('button.filter-pill[data-dispatch-filter="FV"]'); if (dPillFv) dPillFv.textContent = dt.dispatchPillFv;
    const dPillKor = $('button.filter-pill[data-dispatch-filter="KOR"]'); if (dPillKor) dPillKor.textContent = dt.dispatchPillKor;
    const dSearch = $('#dispatch-search-input'); if (dSearch) dSearch.placeholder = dt.dispatchSearchPlaceholder;
    const dispatchLabels = isEn
      ? ['Type', 'Doc #', 'Order', 'Buyer / Company', 'Recipient email', 'Gross amount / Delta', 'Issue date', 'Dispatch status', 'Actions']
      : ['Typ', 'Nr dokumentu', 'Zamówienie', 'Nabywca / Firma', 'E-mail odbiorcy', 'Kwota brutto / Zmiana', 'Data wystawienia', 'Status wysyłki', 'Akcje'];
    $$('#dispatch-table thead th').forEach((th, idx) => {
      if (dispatchLabels[idx]) th.textContent = dispatchLabels[idx];
    });

    // Inventory search, sort, headers
    const iSearch = $('#inventory-search-input'); if (iSearch) iSearch.placeholder = dt.inventorySearchPlaceholder;
    const iSort = $('#inventory-sort-label'); if (iSort) iSort.textContent = dt.sortLabel;
    const inventorySortOptions = isEn ? {
      id_asc: '🔢 ID (ascending)', id_desc: '🔢 ID (descending)',
      name_asc: '🔤 Name (A → Z)', name_desc: '🔤 Name (Z → A)',
      available_desc: '📦 Available (highest)', available_asc: '📦 Available (lowest)',
      price_desc: '💰 Price (highest)', price_asc: '💰 Price (lowest)',
      category_asc: '🏷️ Category (A → Z)'
    } : {
      id_asc: '🔢 ID (rosnąco)', id_desc: '🔢 ID (malejąco)',
      name_asc: '🔤 Nazwa (A → Z)', name_desc: '🔤 Nazwa (Z → A)',
      available_desc: '📦 Dostępne (najwięcej)', available_asc: '📦 Dostępne (najmniej)',
      price_desc: '💰 Cena (najwyższa)', price_asc: '💰 Cena (najniższa)',
      category_asc: '🏷️ Kategoria (A → Z)'
    };
    $$('#inventory-sort-select option').forEach(opt => { if (inventorySortOptions[opt.value]) opt.textContent = inventorySortOptions[opt.value]; });
    const inventoryLabels = isEn
      ? { id: 'ID', name: 'Product name', sku: 'SKU', category: 'Category', available: 'Available', reserved: 'Reserved', price: 'Price', quick: 'Quick restock' }
      : { id: 'ID', name: 'Nazwa produktu', sku: 'SKU', category: 'Kategoria', available: 'Dostępne', reserved: 'Zarezerwowane', price: 'Cena', quick: 'Szybkie doładowanie' };
    const invThsList = $$('#inventory-table thead th');
    const invKeys = ['id', 'name', 'sku', 'category', 'available', 'reserved', 'price', 'quick'];
    invThsList.forEach((th, idx) => {
      const k = invKeys[idx];
      if (inventoryLabels[k]) {
        const ind = $('span.sort-indicator', th)?.outerHTML || '';
        th.innerHTML = `${escapeHtml(inventoryLabels[k])} ${ind}`.trim();
      }
    });
  }

  const ev = dict.events;
  if (ev) {
    const evEye = $('#events-intro-eyebrow'); if (evEye) evEye.textContent = ev.eyebrow;
    const evH2 = $('#events-intro-title'); if (evH2) evH2.textContent = ev.title;
    const evP = $('#events-intro-desc'); if (evP) evP.textContent = ev.desc;
    const evStr = $('#events-tab-stream-btn'); if (evStr) evStr.textContent = ev.tabStream;
    const evOut = $('#events-tab-outbox-btn'); if (evOut) evOut.textContent = ev.tabOutbox;
    const evDlq = $('#events-tab-dlq-btn'); if (evDlq) evDlq.textContent = ev.tabDlq;
    const evTrc = $('#events-tab-trace-btn'); if (evTrc) evTrc.textContent = ev.tabTrace;
    const evRef = $('#events-refresh-btn'); if (evRef) evRef.title = ev.refreshTitle;

    const streamThs = isEn ? ['Entry ID', 'Time', 'Event Type', 'Order ID', 'Amount', 'Payload / Fields'] : ['Entry ID', 'Czas', 'Typ zdarzenia', 'Order ID', 'Kwota', 'Payload / Pola'];
    $$('#stream-table thead th').forEach((th, i) => { if (streamThs[i]) th.textContent = streamThs[i]; });
    const outboxThs = isEn ? ['ID', 'Type', 'Order ID', 'Amount', 'Status', 'Attempts', 'Correlation ID', 'Error / Notes', 'Date'] : ['ID', 'Typ', 'Order ID', 'Kwota', 'Status', 'Próby', 'Correlation ID', 'Błąd / Uwagi', 'Data'];
    $$('#outbox-table thead th').forEach((th, i) => { if (outboxThs[i]) th.textContent = outboxThs[i]; });
    const dlqThs = isEn ? ['ID', 'Event', 'Recipient', 'Channel', 'Subject', 'Failure Reason', 'Status', 'Attempts', 'Action'] : ['ID', 'Zdarzenie', 'Odbiorca', 'Kanał', 'Temat', 'Powód błędu', 'Status', 'Próby', 'Akcja'];
    $$('#dlq-table thead th').forEach((th, i) => { if (dlqThs[i]) th.textContent = dlqThs[i]; });
    const trInp = $('#trace-cid-input'); if (trInp) trInp.placeholder = ev.traceInputPlaceholder;
    const trBtn = $('#trace-search-btn'); if (trBtn) trBtn.textContent = ev.traceBtnSearch;
  }

  const ch = dict.chaos;
  if (ch) {
    const chEye = $('#chaos-intro-eyebrow'); if (chEye) chEye.textContent = ch.eyebrow;
    const chH2 = $('#chaos-intro-title'); if (chH2) chH2.textContent = ch.title;
    const chP = $('#chaos-intro-desc'); if (chP) chP.textContent = ch.desc;
    const chRest = $('#chaos-restore-all-btn'); if (chRest) chRest.textContent = ch.btnRestoreAll;

    const cInv = $('#chaos-card-inventory');
    if (cInv) {
      const p = $('.chaos-desc', cInv); if (p) p.innerHTML = ch.inventoryDesc;
      const bPause = $('button[data-action="pause"]', cInv); if (bPause) bPause.textContent = ch.inventoryPause;
      const bUnpause = $('button[data-action="unpause"]', cInv); if (bUnpause) bUnpause.textContent = ch.inventoryResume;
    }
    const cMail = $('#chaos-card-mailpit');
    if (cMail) {
      const p = $('.chaos-desc', cMail); if (p) p.innerHTML = ch.mailpitDesc;
      const bPause = $('button[data-action="pause"]', cMail); if (bPause) bPause.textContent = ch.mailpitPause;
      const bUnpause = $('button[data-action="unpause"]', cMail); if (bUnpause) bUnpause.textContent = ch.mailpitResume;
    }
    const cPay = $('#chaos-card-payment');
    if (cPay) {
      const p = $('.chaos-desc', cPay); if (p) p.innerHTML = ch.paymentDesc;
      const bPause = $('button[data-action="pause"]', cPay); if (bPause) bPause.textContent = ch.paymentPause;
      const bUnpause = $('button[data-action="unpause"]', cPay); if (bUnpause) bUnpause.textContent = ch.paymentResume;
    }
    const cBrok = $('#chaos-card-broker');
    if (cBrok) {
      const p = $('.chaos-desc', cBrok); if (p) p.innerHTML = ch.brokerDesc;
      const bPause = $('button[data-action="pause"]', cBrok); if (bPause) bPause.textContent = ch.brokerPause;
      const bUnpause = $('button[data-action="unpause"]', cBrok); if (bUnpause) bUnpause.textContent = ch.brokerResume;
    }
  }

  const cmdPaletteBtn = $('#cmd-palette-btn');
  if (cmdPaletteBtn && dict.topbar.searchTitle) cmdPaletteBtn.title = dict.topbar.searchTitle;
  const cmdPaletteText = $('#cmd-palette-btn-text');
  if (cmdPaletteText && dict.topbar.searchPlaceholder) cmdPaletteText.textContent = dict.topbar.searchPlaceholder;
  const cmdPaletteInput = $('#cmd-palette-input');
  if (cmdPaletteInput && dict.topbar.palettePlaceholder) cmdPaletteInput.placeholder = dict.topbar.palettePlaceholder;
  const copyReportBtn = $('#copy-report-btn');
  if (copyReportBtn) {
    if (dict.topbar.reportBtn) copyReportBtn.textContent = dict.topbar.reportBtn;
    if (dict.topbar.reportTitle) copyReportBtn.title = dict.topbar.reportTitle;
  }
  const drawerOpenBtn = $('#drawer-open');
  if (drawerOpenBtn && dict.drawer?.showLog) drawerOpenBtn.textContent = dict.drawer.showLog;

  const ab = dict.about;
  if (ab) {
    const el = (id) => $(`#${id}`);
    const setT = (id, text) => { const node = el(id); if (node) node.textContent = text; };
    const setH = (id, html) => { const node = el(id); if (node) node.innerHTML = html; };

    setT('about-intro-eyebrow', ab.introEyebrow);
    setT('about-intro-title', ab.introTitle);
    setT('about-intro-desc', ab.introDesc);

    setT('about-stat-1-title', ab.stat1Title); setT('about-stat-1-sub', ab.stat1Sub);
    setT('about-stat-2-title', ab.stat2Title); setT('about-stat-2-sub', ab.stat2Sub);
    setT('about-stat-3-title', ab.stat3Title); setT('about-stat-3-sub', ab.stat3Sub);
    setT('about-stat-4-title', ab.stat4Title); setT('about-stat-4-sub', ab.stat4Sub);
    setT('about-stat-5-title', ab.stat5Title); setT('about-stat-5-sub', ab.stat5Sub);
    setT('about-stat-6-title', ab.stat6Title); setT('about-stat-6-sub', ab.stat6Sub);

    setT('about-purpose-eyebrow', ab.purposeEyebrow);
    setT('about-purpose-title', ab.purposeTitle);
    setH('about-purpose-content', ab.purposeContent);

    setT('about-patterns-eyebrow', ab.patternsEyebrow);
    setT('about-patterns-title', ab.patternsTitle);

    setT('about-feat-1-badge', ab.feat1Badge); setT('about-feat-1-desc', ab.feat1Desc);
    setT('about-feat-2-badge', ab.feat2Badge); setT('about-feat-2-desc', ab.feat2Desc);
    setT('about-feat-3-badge', ab.feat3Badge); setT('about-feat-3-desc', ab.feat3Desc);
    setT('about-feat-4-badge', ab.feat4Badge); setT('about-feat-4-desc', ab.feat4Desc);
    setT('about-feat-5-badge', ab.feat5Badge); setT('about-feat-5-desc', ab.feat5Desc);
    setT('about-feat-6-badge', ab.feat6Badge); setT('about-feat-6-desc', ab.feat6Desc);
    setT('about-feat-7-badge', ab.feat7Badge); setT('about-feat-7-desc', ab.feat7Desc);
    setT('about-feat-8-badge', ab.feat8Badge); setT('about-feat-8-desc', ab.feat8Desc);

    setT('about-modules-eyebrow', ab.modulesEyebrow);
    setT('about-modules-title', ab.modulesTitle);
    setT('about-grp-1-title', ab.grp1Title);
    setT('about-grp-2-title', ab.grp2Title);
    setT('about-grp-3-title', ab.grp3Title);
    setT('about-grp-4-title', ab.grp4Title);
    setT('about-grp-5-title', ab.grp5Title);

    setT('about-flow-eyebrow', ab.flowEyebrow);
    setT('about-flow-title', ab.flowTitle);
    setT('about-step-1-title', ab.step1Title); setT('about-step-1-desc', ab.step1Desc);
    setT('about-step-2-title', ab.step2Title); setT('about-step-2-desc', ab.step2Desc);
    setT('about-step-3-title', ab.step3Title); setT('about-step-3-desc', ab.step3Desc);
    setT('about-step-4-title', ab.step4Title); setT('about-step-4-desc', ab.step4Desc);
    setT('about-step-5-title', ab.step5Title); setT('about-step-5-desc', ab.step5Desc);
  }

  const dangerConfirm = $('#danger-confirm');
  if (dangerConfirm) dangerConfirm.innerHTML = `<input type="checkbox"> ${escapeHtml(dict.modals.dangerConfirm)}`;
  $$('.modal-cancel').forEach(b => b.textContent = dict.modals.btnCancel);
  const actionSubmit = $('#action-form button[type="submit"]');
  if (actionSubmit) actionSubmit.textContent = dict.modals.btnRun;

  const m = dict.modals;
  const dEye = $('#dispatch-dialog-eyebrow'); if (dEye && m.dispatchEyebrow) dEye.textContent = m.dispatchEyebrow;
  const dTitle = $('#dispatch-dialog-title'); if (dTitle && m.dispatchTitle) dTitle.textContent = m.dispatchTitle;
  const dSub = $('#dispatch-submit-btn'); if (dSub && m.dispatchSubmit) dSub.textContent = m.dispatchSubmit;
  const dHint = $('#dispatch-attachment-hint'); if (dHint && m.dispatchAttachment) dHint.textContent = m.dispatchAttachment;
  const corrEye = $('#correction-dialog .eyebrow'); if (corrEye && m.correctionEyebrow) corrEye.textContent = m.correctionEyebrow;
  const corrTitle = $('#correction-dialog-title'); if (corrTitle && m.correctionTitle) corrTitle.textContent = m.correctionTitle;
  const corrSub = $('#correction-submit-btn'); if (corrSub && m.correctionSubmit) corrSub.textContent = m.correctionSubmit;

  const secEye = $('#secret-dialog .eyebrow'); if (secEye && m.secretEyebrow) secEye.textContent = m.secretEyebrow;
  const secCancel = $('#secret-dialog .modal-cancel'); if (secCancel && m.btnCancel) secCancel.textContent = m.btnCancel;
  const repEye = $('#report-eyebrow'); if (repEye && m.reportEyebrow) repEye.textContent = m.reportEyebrow;
  const repTitle = $('#report-title'); if (repTitle && m.reportTitle) repTitle.textContent = m.reportTitle;
  const repCopy = $('#report-copy-md'); if (repCopy && m.reportCopy) repCopy.textContent = m.reportCopy;
  const repCancel = $('#report-cancel-btn'); if (repCancel && m.btnCancel) repCancel.textContent = m.btnCancel;
  $$('.modal-close').forEach(btn => {
    btn.setAttribute('aria-label', language === 'en' ? 'Close' : 'Zamknij');
  });
  const sortLbl = isEn ? 'Sort:' : 'Sortuj:';
  const oSortLbl = $('#orders-sort-label'); if (oSortLbl) oSortLbl.textContent = sortLbl;
  const iSortLbl = $('#inventory-sort-label'); if (iSortLbl) iSortLbl.textContent = sortLbl;
  const cSortLbl = $('#customers-sort-label'); if (cSortLbl) cSortLbl.textContent = sortLbl;

  const oFilterLbl = $('#orders-filter-label'); if (oFilterLbl) oFilterLbl.textContent = isEn ? 'Filter status:' : 'Filtruj status:';
  const oSearchInp = $('#orders-search-input'); if (oSearchInp) oSearchInp.placeholder = isEn ? 'Order ID or customer email...' : 'ID zamówienia lub e-mail klienta...';
  const iSearchInp = $('#inventory-search-input'); if (iSearchInp) iSearchInp.placeholder = isEn ? 'Product ID...' : 'ID produktu...';
  const cSearchInp = $('#customers-search-input'); if (cSearchInp) cSearchInp.placeholder = isEn ? 'Email, first or last name...' : 'E-mail, imię lub nazwisko...';
  const customerLabels = isEn
    ? { id: 'ID', email: 'Email', name: 'Full name', city: 'City', date: 'Registration date' }
    : { id: 'ID', email: 'E-mail', name: 'Imię i nazwisko', city: 'Miasto', date: 'Data rejestracji' };
  $$('#customers-table th[data-sort]').forEach(th => {
    th.firstChild.nodeValue = `${customerLabels[th.dataset.sort]} `;
    th.title = `${isEn ? 'Sort by' : 'Sortuj według'}: ${customerLabels[th.dataset.sort]}`;
  });
  const customerOptions = isEn
    ? { id_asc: 'ID (ascending)', id_desc: 'ID (descending)', email_asc: 'Email (A → Z)', name_asc: 'Full name (A → Z)', city_asc: 'City (A → Z)', date_desc: 'Registration (newest)' }
    : { id_asc: 'ID (rosnąco)', id_desc: 'ID (malejąco)', email_asc: 'E-mail (A → Z)', name_asc: 'Imię i nazwisko (A → Z)', city_asc: 'Miasto (A → Z)', date_desc: 'Rejestracja (najnowsze)' };
  $$('#customers-sort-select option').forEach(option => { option.textContent = customerOptions[option.value]; });
  const customersRetry = $('#customers-retry'); if (customersRetry) customersRetry.textContent = isEn ? 'Retry' : 'Ponów odczyt';
}

export function getPanelJobTitle(job, language, actions = []) {
  if (!job) return '';
  const isEn = language === 'en';
  if (job.actionId && actions && actions.length) {
    const act = actions.find(a => a.id === job.actionId);
    if (act && act.title) return act.title;
  }
  const title = job.title || '';
  if (isEn) {
    const plToEn = {
      'UI wiele przeglądarek': 'Multi-Browser UI Test',
      'Test obciążeniowy': 'Load Test',
      '🚀 Pełny start (1-Click)': '1-Click Fresh Start',
      'Pełny start (1-Click)': '1-Click Fresh Start',
      'Weryfikacja aplikacji': 'App Verify',
      'App Verify': 'App Verify',
      'Regresja UI Chromium': 'Chromium UI Regression',
      'Zasil danymi demo': 'Seed Demo Data',
      '📦 Zasil danymi demo': 'Seed Demo Data',
      'Doktor i autonaprawa': 'Auto-Healing Doctor',
      '🚑 Doktor i autonaprawa': 'Auto-Healing Doctor',
      'Doctor środowiska': 'Environment Doctor',
      'Kontrola przed startem': 'Preflight check',
      'Kontrola przed GitHub': 'Pre-GitHub check',
      'Informacje o platformie': 'Platform info',
      'Aktywny profil': 'Active profile',
      'Zmień profil': 'Change profile',
      'Status skarbca': 'Vault status',
      'Waliduj sekrety': 'Validate secrets',
      'Inicjalizuj klucze age': 'Initialize age keys',
      'Utwórz sekrety DEV': 'Create DEV secrets',
      'Utwórz sekrety TEST': 'Create TEST secrets',
      'Uzupełnij sekret JWT': 'Generate JWT secret',
      'Edytuj sekrety DEV': 'Edit DEV secrets',
      'Edytuj sekrety TEST': 'Edit TEST secrets',
      'Status JWT': 'JWT status',
      'Rotuj klucze JWT': 'Rotate JWT keys',
      'Smoke JWT': 'JWT smoke',
      'Dziennik audytowy': 'Audit log',
      'Reset hasła': 'Password reset',
      'Uruchom infrastrukturę': 'Start infrastructure',
      'Zatrzymaj infrastrukturę': 'Stop infrastructure',
      'Status Docker Compose': 'Docker Compose status',
      'Logi infrastruktury': 'Infrastructure logs',
      'Mapa baz i portów': 'Databases & ports map',
      'Uruchom bazę/bazy': 'Start database(s)',
      'Zatrzymaj bazę/bazy': 'Stop database(s)',
      'Status baz': 'Database status',
      'Logi bazy': 'Database logs',
      'Wykonaj backup': 'Create backup',
      'Lista backupów': 'Backup list',
      'Test odtworzenia': 'Restore test',
      'Retencja backupów': 'Prune backups',
      'Klucz backupów': 'Backup key init',
      'Harmonogram backupu': 'Install backup schedule',
      'Status harmonogramu': 'Schedule status',
      'Usuń harmonogram': 'Remove backup schedule',
      'Reset magazynu demo': 'Reset demo stock',
      'Uruchom serwisy (Docker)': 'Start Docker Apps',
      'Zatrzymaj serwisy (Docker)': 'Stop Docker Apps',
      'Status kontenerów (Docker)': 'Docker Apps Status',
      'Uruchom serwis (Maven)': 'Start service (Maven)',
      'Zatrzymaj serwis (proces)': 'Stop service (process)',
      'Uruchom serwisy (procesy WSL)': 'Start all services (WSL processes)',
      'Zatrzymaj serwisy (procesy WSL)': 'Stop all services (WSL processes)',
      'Status procesów WSL': 'WSL Process status',
      'Logi procesu serwisu': 'Service process logs',
      'Dostępność usług': 'Service health',
      'Uruchom sklep': 'Start storefront',
      'Zatrzymaj sklep': 'Stop storefront',
      'Status frontendu': 'Storefront status',
      'Logi frontendu': 'Storefront logs',
      'Uruchom Redis': 'Start Redis',
      'Zatrzymaj Redis': 'Stop Redis',
      'Status Redis': 'Redis status',
      'Logi Redis': 'Redis logs',
      'Informacje o streamie': 'Stream info',
      'Ostatnie zdarzenia': 'Recent events',
      'Reset consumer group': 'Reset consumer group',
      'Uruchom monitoring': 'Start monitoring',
      'Zatrzymaj monitoring': 'Stop monitoring',
      'Status monitoringu': 'Monitoring status',
      'Logi monitoringu': 'Monitoring logs',
      'Adresy monitoringu': 'Monitoring URLs',
      'Wpisy Dead Letter Queue': 'DLQ entries',
      'Ponów wpis DLQ': 'Replay DLQ entry',
      'Uruchom Mailpit': 'Start Mailpit',
      'Zatrzymaj Mailpit': 'Stop Mailpit',
      'Status Mailpit': 'Mailpit status',
      'Logi Mailpit': 'Mailpit logs',
      'Ostatnie wiadomości': 'Recent messages',
      'Szukaj wiadomości': 'Search messages',
      'Smoke e-mail': 'Mailpit smoke',
      'Wyślij e-mail testowy': 'Send test email',
      'Wyczyść Mailpit': 'Clear Mailpit',
      'Wszystkie zamówienia': 'All orders',
      'Kody rabatowe': 'Promotion codes',
      'Utwórz kod rabatowy': 'Create promotion code',
      'Opinie do moderacji': 'Pending reviews',
      'Moderuj opinię': 'Moderate review',
      'Śledzenie dostawy': 'Delivery tracking',
      'Zmień status dostawy': 'Update delivery status',
      'Zwroty i reklamacje': 'Returns & complaints',
      'Zmień status zgłoszenia': 'Update return status',
      'Lista faktur': 'Invoices list',
      'Eksportuj faktury ZIP': 'Export invoices ZIP',
      'Wystaw fakturę korygującą': 'Issue correction invoice',
      'Lista korekt faktur': 'Corrections list',
      'Pokaż fakturę': 'Show invoice',
      'Pobierz PDF faktury': 'Download invoice PDF',
      'Smoke API Gateway': 'API Gateway Smoke',
      'Pełna weryfikacja MVP': 'Full MVP Verification',
      'Smoke Delivery Service': 'Delivery Service Smoke',
      'Smoke Promotion Service': 'Promotion Service Smoke',
      'Contract test API v1': 'API v1 Contract Test',
      'Test bezpieczeństwa API': 'API Security Test',
      'OWASP ZAP DAST': 'OWASP ZAP DAST',
      'Pełny E2E API': 'Full E2E API Test',
      'Status Playwright': 'Playwright Status',
      'Instalacja Playwright': 'Install Playwright',
      'Playwright Smoke': 'Playwright Smoke',
      'Systemowy checkout UI': 'Full System UI Checkout',
      'UI headed': 'Headed UI Test',
      'Playwright Inspector': 'Playwright Inspector',
      'Raport Playwright': 'Playwright Report',
      'Chaos / Test odporności': 'Chaos / Resilience Test',
      'Testy Maven': 'Maven Tests',
      'Pakietowanie Maven': 'Maven Package',
      'Obrazy Docker': 'Docker Images',
      'Odporność usług': 'Service Resilience Status',
      'Status profilu prod': 'Prod Profile Status',
      'Waliduj Kubernetes': 'Validate Kubernetes',
      'Status Kubernetes': 'Kubernetes Status',
      'Waliduj OpenBao/TLS': 'Validate OpenBao/TLS',
      'Status OpenBao': 'OpenBao Status',
      'Status TLS': 'TLS Status'
    };
    return plToEn[title] || title;
  } else {
    const enToPl = {
      'Multi-Browser UI Test': 'UI wiele przeglądarek',
      'Load Test': 'Test obciążeniowy',
      '1-Click Fresh Start': '🚀 Pełny start (1-Click)',
      'App Verify': 'Weryfikacja aplikacji',
      'Chromium UI Regression': 'Regresja UI Chromium',
      'Seed Demo Data': '📦 Zasil danymi demo',
      'Auto-Healing Doctor': '🚑 Doktor i autonaprawa',
      'Environment Doctor': 'Doctor środowiska',
      'Preflight check': 'Kontrola przed startem',
      'Pre-GitHub check': 'Kontrola przed GitHub',
      'Platform info': 'Informacje o platformie',
      'Start Docker Apps': 'Uruchom serwisy (Docker)',
      'Stop Docker Apps': 'Zatrzymaj serwisy (Docker)',
      'Start all services (WSL processes)': 'Uruchom serwisy (procesy WSL)',
      'Stop all services (WSL processes)': 'Zatrzymaj serwisy (procesy WSL)',
      'Start infrastructure': 'Uruchom infrastrukturę',
      'Stop infrastructure': 'Zatrzymaj infrastrukturę',
      'Quality Gates & Tests': 'Testy i jakość',
      'Jobs & Logs': 'Zadania i logi'
    };
    return enToPl[title] || title;
  }
}
