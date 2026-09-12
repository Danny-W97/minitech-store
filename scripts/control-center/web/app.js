(() => {
  const state = { token: '', profile: 'dev', language: 'pl', actions: [], health: [], jobs: [], secretProfiles: {}, secretFields: [], secretSetupFields: [], selectedJob: null, selectedJobData: null, selectedChat: null, terminalId: null, terminalTimer: null, jobTimer: null, aiConfig: {}, aiHistory: [] };
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const escapeHtml = (value) => String(value ?? '').replace(/[&<>'"]/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[char]));
  const formatTime = (seconds) => seconds ? new Date(seconds * 1000).toLocaleString(state.language === 'en' ? 'en-US' : 'pl-PL') : '—';

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
        heroH2: 'Wszystko pod kontrolą.<br><span>Bez wychodzenia z WSL.</span>',
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
        emptyList: 'Pobieram rozmowy…',
        noMessages: 'Brak wiadomości.',
        selectChat: 'Wybierz rozmowę z listy.',
        replyFormLabel: 'Odpowiedź',
        replyPlaceholder: 'Napisz jasną i pomocną odpowiedź dla klienta…',
        btnSendReply: 'Wyślij odpowiedź',
        replySuccess: 'Odpowiedź wysłana do klienta i Notification Service.',
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
      },
      about: {
        eyebrow: 'ARCHITEKTURA & MONOREPO',
        title: 'O architekturze platformy MiniTech',
        desc: 'Monorepo 15 mikroserwisów Java Spring Boot, Gateway, bazy PostgreSQL, szyna zdarzeń Redis i frontend.',
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
        refreshTitle: 'Odśwież dane',
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
      },
      chaos: {
        eyebrow: 'CHAOS ENGINEERING & SYMULACJA AWARII',
        title: 'Symulator Awarii Mikroserwisów',
        desc: 'Sprawdź odporność systemu na żywo. Odłącz bazę lub serwis i obserwuj reakcję Sagi, Outboxa oraz Dead Letter Queue.',
        btnRestoreAll: '✅ Przywróć wszystko (Restore All)',
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
        heroH2: 'Everything under control.<br><span>Without leaving WSL.</span>',
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
        emptyList: 'Loading conversations…',
        noMessages: 'No messages.',
        selectChat: 'Select a conversation from the list.',
        replyFormLabel: 'Reply',
        replyPlaceholder: 'Write a clear and helpful reply for the customer…',
        btnSendReply: 'Send reply',
        replySuccess: 'Reply sent to customer and Notification Service.',
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
        linkSwagger: '📖 Swagger API ↗',
        linkStore: '🛍️ Open Store ↗',
        linkMailpit: '✉ Mailpit ↗',
        linkAssistant: '✦ AI Assistant',
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
        refreshTitle: 'Refresh data',
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
      },
      chaos: {
        eyebrow: 'CHAOS ENGINEERING & FAILURE SIMULATION',
        title: 'Microservices Failure Simulator',
        desc: 'Test system resilience live. Disconnect a database or service and watch Saga, Outbox, and Dead Letter Queue reaction.',
        btnRestoreAll: '✅ Restore All',
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

  async function api(path, options = {}) {
    const headers = {Accept: 'application/json', ...(options.headers || {})};
    if (options.body) headers['Content-Type'] = 'application/json';
    if (options.method && options.method !== 'GET') headers['X-Control-Center-Token'] = state.token;
    const response = await fetch(path, {...options, headers});
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
    return payload;
  }

  function toast(message, error = false) {
    const node = $('#toast'); node.textContent = message; node.className = `toast visible${error ? ' error' : ''}`;
    clearTimeout(node.timer); node.timer = setTimeout(() => node.className = 'toast', 3600);
  }

  function applyLanguage(lang) {
    state.language = lang === 'en' ? 'en' : 'pl';
    const dict = I18N[state.language] || I18N.pl;
    document.documentElement.lang = state.language;
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
    if (refreshBtn) {
      refreshBtn.title = dict.topbar.refresh;
      refreshBtn.setAttribute('aria-label', dict.topbar.refresh);
    }
    const profilePill = $('.profile-pill');
    if (profilePill) profilePill.innerHTML = `${dict.topbar.profile} <strong id="profile-value">${escapeHtml(state.profile)}</strong>`;
    const langSwitchBtn = $('#lang-switch-button');
    if (langSwitchBtn) langSwitchBtn.title = dict.topbar.langBtnTitle;

    const currentView = location.hash.slice(1) || 'dashboard';
    const viewTitle = $('#view-title');
    if (viewTitle) viewTitle.textContent = dict.viewTitles[currentView] || dict.viewTitles.dashboard;

    const d = dict.dashboard;
    const heroEyebrow = $('#view-dashboard .hero-panel .eyebrow');
    if (heroEyebrow) heroEyebrow.textContent = d.heroEyebrow;
    const heroH2 = $('#view-dashboard .hero-panel h2');
    if (heroH2) heroH2.innerHTML = d.heroH2;
    const heroP = $('#view-dashboard .hero-panel p:not(.eyebrow)');
    if (heroP) heroP.textContent = d.heroP;
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
    if (metricCards[3]) { const sm = $('small', metricCards[3]); if (sm) sm.textContent = `${state.profile.toUpperCase()} · ${d.metricMail}`; }

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

    const quickInfra = $('button[data-action="infra-up"]', $('#view-dashboard'));
    if (quickInfra) { const st = $('strong', quickInfra); if (st) st.textContent = d.quickInfra; }
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
      updateAiModeBadge();
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
      const dtStk = $('#data-tab-inventory-btn'); if (dtStk) dtStk.textContent = dt.tabInventory;
      const dtCst = $('#data-tab-customers-btn'); if (dtCst) dtCst.textContent = dt.tabCustomers;
      const dtRef = $('#data-refresh-btn'); if (dtRef) dtRef.title = dt.refreshTitle;
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
    }

    const ch = dict.chaos;
    if (ch) {
      const chEye = $('#chaos-intro-eyebrow'); if (chEye) chEye.textContent = ch.eyebrow;
      const chH2 = $('#chaos-intro-title'); if (chH2) chH2.textContent = ch.title;
      const chP = $('#chaos-intro-desc'); if (chP) chP.textContent = ch.desc;
      const chRest = $('#chaos-restore-all-btn'); if (chRest) chRest.textContent = ch.btnRestoreAll;
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
    const isEn = state.language === 'en';
    const sortLbl = isEn ? 'Sort:' : 'Sortuj:';
    const oSortLbl = $('#orders-sort-label'); if (oSortLbl) oSortLbl.textContent = sortLbl;
    const iSortLbl = $('#inventory-sort-label'); if (iSortLbl) iSortLbl.textContent = sortLbl;
    const cSortLbl = $('#customers-sort-label'); if (cSortLbl) cSortLbl.textContent = sortLbl;

    const oFilterLbl = $('#orders-filter-label'); if (oFilterLbl) oFilterLbl.textContent = isEn ? 'Filter status:' : 'Filtruj status:';
    const oSearchInp = $('#orders-search-input'); if (oSearchInp) oSearchInp.placeholder = isEn ? 'Search by order ID or customer...' : 'Szukaj po ID zamówienia lub klienta...';
    const iSearchInp = $('#inventory-search-input'); if (iSearchInp) iSearchInp.placeholder = isEn ? 'Filter by product name, SKU or category...' : 'Filtruj produkty po nazwie, SKU lub kategorii...';
    const cSearchInp = $('#customers-search-input'); if (cSearchInp) cSearchInp.placeholder = isEn ? 'Search by username or email address...' : 'Szukaj po nazwie użytkownika lub adresie e-mail...';

    if (typeof renderJobsList === 'function') {
      renderJobsList();
    }
    const diagSlot = $('#job-diagnostic-slot');
    if (diagSlot) {
      if (state.selectedJobData && state.selectedJobData.diagnosis) {
        diagSlot.innerHTML = renderDiagnosticBanner(state.selectedJobData.diagnosis, state.selectedJobData);
      } else {
        diagSlot.innerHTML = '';
      }
    }
    const jobOutput = $('#job-output');
    if (jobOutput && !state.selectedJobData) {
      jobOutput.textContent = dict.jobs.selectJobPrompt;
    }
  }

  function setView(name) {
    $$('.view').forEach(node => node.classList.toggle('active', node.id === `view-${name}`));
    $$('.nav-item').forEach(node => node.classList.toggle('active', node.dataset.view === name));
    const dict = I18N[state.language] || I18N.pl;
    const viewTitle = $('#view-title');
    if (viewTitle) viewTitle.textContent = dict.viewTitles[name] || 'MiniTech Control';
    location.hash = name;
    if (name === 'chat') loadChat();
    if (name === 'assistant') loadAiConfig();
    if (name === 'jobs') loadJobs();
    if (name === 'data') loadDataView();
    if (name === 'events') loadEventsView();
    if (name === 'chaos') loadChaosView();
  }

  function renderHealth() {
    const dict = I18N[state.language] || I18N.pl;
    const online = state.health.filter(item => item.online).length;
    const total = state.health.length || 15;
    $('#metric-online').textContent = `${online}/${total}`;
    updateFaviconAndTitle(online, total);
    $('#service-preview').innerHTML = state.health.map(item => `<div class="service-preview-row"><span class="state-light ${item.online ? 'online' : ''}"></span><strong>${escapeHtml(item.name)}</strong><small>${item.online ? `${item.latencyMs} ms` : 'OFFLINE'}</small></div>`).join('') || `<div class="empty-state">${escapeHtml(dict.dashboard.emptyData)}</div>`;
    const isEn = state.language === 'en';
    const s = dict.services;
    $('#service-grid').innerHTML = state.health.map(item => `
      <article class="service-card">
        <div>
          <header>
            <h3>${escapeHtml(item.name)} Service</h3>
            <span class="status-tag ${item.online ? 'online' : ''}">${item.online ? 'UP' : 'OFFLINE'}</span>
          </header>
          <p>127.0.0.1:${item.port} · ${item.online ? `${item.latencyMs} ms` : (isEn ? 'no response' : 'brak odpowiedzi')}</p>
          ${item.memory ? `<div class="service-stats-pill" title="${item.cpu ? `CPU: ${escapeHtml(item.cpu)}` : ''}">🧠 ${escapeHtml(item.memory.split('/')[0].trim())}${item.cpu ? ` • ${escapeHtml(item.cpu)}` : ''}</div>` : ''}
        </div>
        <div class="service-card-actions">
          <button class="service-action-btn run-btn" data-action="app-run" data-service="${escapeHtml(item.alias || item.name.toLowerCase())}" title="${isEn ? 'Start service' : 'Uruchom serwis'}">▶ ${escapeHtml(s.btnStart || 'Start')}</button>
          <button class="service-action-btn stop-btn" data-action="app-stop" data-service="${escapeHtml(item.alias || item.name.toLowerCase())}" title="${isEn ? 'Stop service' : 'Zatrzymaj serwis'}">⏹ ${escapeHtml(s.btnStop || 'Stop')}</button>
          <button class="service-action-btn log-btn" data-action="app-logs" data-service="${escapeHtml(item.alias || item.name.toLowerCase())}" title="${isEn ? 'Show logs' : 'Pokaż logi'}">📄 ${escapeHtml(s.btnLogs || 'Logi')}</button>
        </div>
      </article>
    `).join('');
  }

  let isHealthLoading = false;
  let dynamicPollTimer = null;

  async function loadHealth(silent = false) {
    if (isHealthLoading) return;
    isHealthLoading = true;
    const btn = $('#health-refresh');
    if (!silent && btn) btn.disabled = true;
    try {
      const data = await api('/api/health');
      state.health = data.services;
      renderHealth();
    } catch (error) {
      if (!silent) toast(error.message, true);
    } finally {
      isHealthLoading = false;
      if (!silent && btn) btn.disabled = false;
    }
  }

  function startDynamicPolling() {
    if (dynamicPollTimer) clearInterval(dynamicPollTimer);
    dynamicPollTimer = setInterval(() => {
      if (!document.hidden) {
        loadHealth(true);
        if ($('#view-chat')?.classList.contains('active')) {
          loadChat();
        }
      }
    }, 4000);
  }

  async function loadSecrets() {
    try {
      const data = await api('/api/secrets');
      state.secretProfiles = data.profiles || {};
      state.secretFields = data.fields || [];
      state.secretSetupFields = data.setupFields || [];
      const d = (I18N[state.language] || I18N.pl).dashboard;
      ['dev', 'test'].forEach(profile => {
        const quickAction = $(`button[data-action="secrets-edit-${profile}"], button[data-action="secrets-setup-${profile}"]`);
        if (quickAction) {
          const ready = state.secretProfiles[profile]?.file;
          quickAction.dataset.action = ready ? `secrets-edit-${profile}` : `secrets-setup-${profile}`;
          const title = profile === 'dev' ? d.quickSecretsDev : d.quickSecretsTest;
          const subtitle = ready ? d.quickSecretsEditSub : d.quickSecretsSetupSub;
          const st = $('strong', quickAction); if (st) st.textContent = title;
          const sm = $('small', quickAction); if (sm) sm.textContent = subtitle;
        }
      });
      if (state.actions.length) renderOperations();
    } catch (error) { toast(error.message, true); }
  }

  function renderActionCard(item) {
    const dict = I18N[state.language] || I18N.pl;
    if (item.externalUrl) {
      const buttonLabel = state.language === 'en' ? 'Open in new tab ↗' : 'Otwórz w nowej karcie ↗';
      return `<article class="operation-card external-operation-card" style="border-left: 2px solid var(--accent);" data-search="${escapeHtml(`${item.title} ${item.description} ${item.group}`.toLowerCase())}"><h4>${escapeHtml(item.title)}</h4><p>${escapeHtml(item.description)}</p><a href="${escapeHtml(item.externalUrl)}" target="_blank" rel="noopener noreferrer" class="operation-card-link"><span>📖</span> ${buttonLabel}</a></article>`;
    }
    const setup = item.kind === 'secret-setup';
    const secret = setup || item.kind === 'secret-edit';
    const alreadyCreated = setup && state.secretProfiles[item.profile]?.file;
    const buttonLabel = setup ? (alreadyCreated ? dict.operations.btnFileExists : dict.operations.btnCreateFile) : item.kind === 'secret-edit' ? dict.operations.btnOpenEditor : item.fields.length ? dict.operations.btnConfigure : dict.operations.btnRun;
    const disabled = alreadyCreated ? 'disabled' : '';
    return `<article class="operation-card ${item.danger ? 'danger' : ''} ${secret ? 'secret-operation-card' : ''}" data-search="${escapeHtml(`${item.title} ${item.description} ${item.group}`.toLowerCase())}"><h4>${escapeHtml(item.title)}</h4><p>${escapeHtml(item.description)}</p><button data-action="${item.id}" ${disabled}>${buttonLabel}</button></article>`;
  }

  function renderOperations() {
    const dict = I18N[state.language] || I18N.pl;
    const groups = [...new Set(state.actions.filter(item => item.group !== 'Testy' && item.group !== 'Tests').map(item => item.group))];
    $('#operation-group').innerHTML = `<option value="">${escapeHtml(dict.operations.allGroups)}</option>` + groups.map(group => `<option>${escapeHtml(group)}</option>`).join('');
    $('#operation-groups').innerHTML = groups.map(group => `<section class="operation-section" data-group="${escapeHtml(group)}"><h3>${escapeHtml(group)}</h3><div class="operation-grid">${state.actions.filter(item => item.group === group).map(renderActionCard).join('')}</div></section>`).join('');
    const tests = state.actions.filter(item => item.group === 'Testy' || item.group === 'Tests');
    $('#test-cards').innerHTML = tests.map(item => `<article class="test-card"><span class="test-icon">✓</span><h3>${escapeHtml(item.title)}</h3><p>${escapeHtml(item.description)}</p><button class="secondary-button" data-action="${item.id}">${escapeHtml(dict.tests.btnRun)}</button></article>`).join('');
  }

  function filterOperations() {
    const query = $('#operation-search').value.trim().toLowerCase(); const group = $('#operation-group').value;
    $$('.operation-section').forEach(section => {
      let visible = 0;
      $$('.operation-card', section).forEach(card => { const show = (!query || card.dataset.search.includes(query)) && (!group || section.dataset.group === group); card.hidden = !show; if (show) visible++; });
      section.hidden = visible === 0;
    });
  }

  function fieldHtml(field) {
    const attrs = `${field.required ? 'required' : ''} ${field.min !== undefined ? `min="${field.min}"` : ''} ${field.max !== undefined ? `max="${field.max}"` : ''}`;
    const defaultValue = field.name === 'profile' ? state.profile : field.default;
    let control;
    if (field.type === 'select' && field.name === 'language') {
      const languageLabels = {pl: ['Polski', 'PL'], en: ['English', 'EN']};
      const options = field.options.map(value => {
        const [label, code] = languageLabels[value] || [value, value.toUpperCase()];
        const selected = String(defaultValue) === String(value);
        return `<button type="button" class="choice-option ${selected ? 'selected' : ''}" data-choice-value="${escapeHtml(value)}" aria-pressed="${selected}"><strong>${escapeHtml(label)}</strong><small>${escapeHtml(code)}</small></button>`;
      }).join('');
      return `<fieldset class="choice-field"><legend>${escapeHtml(field.label)}</legend><input type="hidden" name="${field.name}" value="${escapeHtml(defaultValue || field.options[0])}"><div class="choice-grid">${options}</div></fieldset>`;
    }
    if (field.type === 'select') control = `<select name="${field.name}" ${attrs}>${field.options.map(value => `<option value="${escapeHtml(value)}" ${String(defaultValue) === String(value) ? 'selected' : ''}>${escapeHtml(value)}</option>`).join('')}</select>`;
    else if (field.type === 'textarea') control = `<textarea name="${field.name}" ${attrs}>${escapeHtml(defaultValue || '')}</textarea>`;
    else control = `<input name="${field.name}" type="${field.type || 'text'}" value="${escapeHtml(defaultValue || '')}" ${attrs}>`;
    return `<label class="field-label">${escapeHtml(field.label)}${control}</label>`;
  }

  function selectChoice(choice) {
    const fieldset = choice.closest('.choice-field');
    const value = $('input[type="hidden"]', fieldset);
    if (value) value.value = choice.dataset.choiceValue;
    $$('.choice-option', fieldset).forEach(option => {
      const selected = option === choice;
      option.classList.toggle('selected', selected);
      option.setAttribute('aria-pressed', String(selected));
    });
  }

  function secretFieldHtml(field) {
    const placeholder = field.type === 'password'
      ? (state.language === 'en' ? 'New value · leave empty to keep unchanged' : 'Nowa wartość · puste = bez zmiany')
      : (state.language === 'en' ? 'Leave empty to keep unchanged' : 'Puste = bez zmiany');
    let control;
    if (field.type === 'select') {
      const emptyOption = state.language === 'en' ? '— keep unchanged —' : '— pozostaw bez zmian —';
      control = `<select name="${escapeHtml(field.name)}"><option value="">${emptyOption}</option>${field.options.map(value => `<option value="${escapeHtml(value)}">${escapeHtml(value)}</option>`).join('')}</select>`;
    } else {
      control = `<input name="${escapeHtml(field.name)}" type="${escapeHtml(field.type || 'text')}" autocomplete="new-password" placeholder="${escapeHtml(placeholder)}" maxlength="4096">`;
    }
    const note = field.note ? `<small class="field-note">${escapeHtml(field.note)}</small>` : '';
    return `<label class="field-label">${escapeHtml(field.label)}${control}${note}</label>`;
  }

  function secretSetupFieldHtml(field) {
    const attrs = `${field.required ? 'required' : ''} ${field.min !== undefined ? `min="${field.min}"` : ''} ${field.max !== undefined ? `max="${field.max}"` : ''}`;
    const defaultValue = field.default ?? '';
    let control;
    if (field.type === 'select') {
      control = `<select name="${escapeHtml(field.name)}" ${attrs}>${field.options.map(value => `<option value="${escapeHtml(value)}" ${String(defaultValue) === String(value) ? 'selected' : ''}>${escapeHtml(value)}</option>`).join('')}</select>`;
    } else {
      const placeholder = field.type === 'password'
        ? (state.language === 'en' ? 'Value will be encrypted' : 'Wartość zostanie zaszyfrowana')
        : (state.language === 'en' ? 'Optional — use default' : 'Opcjonalne — użyj wartości domyślnej');
      control = `<input name="${escapeHtml(field.name)}" type="${escapeHtml(field.type || 'text')}" value="${escapeHtml(defaultValue)}" ${attrs} autocomplete="new-password" placeholder="${escapeHtml(placeholder)}" maxlength="4096">`;
    }
    const note = field.note ? `<small class="field-note">${escapeHtml(field.note)}</small>` : '';
    return `<label class="field-label">${escapeHtml(field.label)}${control}${note}</label>`;
  }

  function openSecretEditor(profile) {
    const dict = I18N[state.language] || I18N.pl;
    const status = state.secretProfiles[profile] || {};
    const fields = state.secretFields;
    if (!fields.length) return toast(state.language === 'en' ? 'Could not load secret fields.' : 'Nie udało się pobrać pól sekretów.', true);
    const dialog = $('#secret-dialog');
    dialog.dataset.mode = 'edit';
    dialog.dataset.profile = profile;
    $('#secret-dialog-title').textContent = state.language === 'en' ? `${profile.toUpperCase()} Profile Secrets` : `Sekrety profilu ${profile.toUpperCase()}`;
    const keyOk = status.key ? 'OK' : (state.language === 'en' ? 'MISSING' : 'BRAK');
    const fileOk = status.file ? 'OK' : (state.language === 'en' ? 'MISSING' : 'BRAK');
    $('#secret-dialog-status').textContent = state.language === 'en' ? `Age key: ${keyOk} · Encrypted file: ${fileOk}` : `Klucz age: ${keyOk} · plik zaszyfrowany: ${fileOk}`;
    $('#secret-dialog-warning').textContent = status.key && status.file
      ? (state.language === 'en' ? 'Current values are not displayed. Fill in only the fields you wish to update; empty fields remain unchanged.' : 'Aktualne wartości są niewidoczne. Wypełnij tylko pola, które chcesz zmienić; puste pola pozostaną bez zmian.')
      : (state.language === 'en' ? 'Vault is incomplete for this profile. Use age init and secret creator first.' : 'Brak kompletnego skarbca tego profilu. Najpierw użyj inicjalizacji i kreatora sekretów w Operacjach albo Terminalu WSL.');
    const sections = [...new Set(fields.map(field => field.section || 'Sekrety'))];
    $('#secret-fields').innerHTML = sections.map(section => `<fieldset class="secret-fieldset"><legend>${escapeHtml(section)}</legend>${fields.filter(field => field.section === section).map(secretFieldHtml).join('')}</fieldset>`).join('');
    $('#secret-confirm').checked = false;
    $('#secret-confirm-label').textContent = dict.modals.secretSaveConfirm;
    $('#secret-submit').textContent = dict.modals.secretSaveSubmit;
    $('#secret-submit').disabled = !(status.key && status.file);
    dialog.showModal();
  }

  function openSecretSetup(profile) {
    const dict = I18N[state.language] || I18N.pl;
    const status = state.secretProfiles[profile] || {};
    const fields = state.secretSetupFields;
    if (!fields.length) return toast(state.language === 'en' ? 'Could not load setup fields.' : 'Nie udało się pobrać pól pierwszej konfiguracji.', true);
    if (status.file) return toast(state.language === 'en' ? `Secrets file for ${profile.toUpperCase()} already exists. Use the editor.` : `Plik sekretów profilu ${profile.toUpperCase()} już istnieje. Użyj edytora.`, true);
    const dialog = $('#secret-dialog');
    dialog.dataset.mode = 'setup';
    dialog.dataset.profile = profile;
    $('#secret-dialog-title').textContent = state.language === 'en' ? `Create ${profile.toUpperCase()} Secrets` : `Utwórz sekrety ${profile.toUpperCase()}`;
    const keyStatus = status.key ? 'OK' : (state.language === 'en' ? 'will be generated' : 'zostanie utworzony');
    const fileStatus = state.language === 'en' ? 'will be created' : 'zostanie utworzony';
    $('#secret-dialog-status').textContent = state.language === 'en' ? `Age key: ${keyStatus} · Encrypted file: ${fileStatus}` : `Klucz age: ${keyStatus} · plik zaszyfrowany: ${fileStatus}`;
    $('#secret-dialog-warning').textContent = state.language === 'en'
      ? 'Initial setup wizard. For existing PostgreSQL volumes, enter existing passwords. For new instances, you may provide custom passwords. Passwords are never logged.'
      : 'To jest pierwsza konfiguracja. Dla istniejących wolumenów PostgreSQL wpisz dokładnie dotychczasowe hasła; dla nowego środowiska możesz ustawić własne silne wartości. Hasła nie trafią do logu zadania.';
    const sections = [...new Set(fields.map(field => field.section || 'Sekrety'))];
    $('#secret-fields').innerHTML = sections.map(section => `<fieldset class="secret-fieldset"><legend>${escapeHtml(section)}</legend>${fields.filter(field => field.section === section).map(secretSetupFieldHtml).join('')}</fieldset>`).join('');
    $('#secret-confirm').checked = false;
    $('#secret-confirm-label').textContent = dict.modals.secretSaveConfirm;
    $('#secret-submit').textContent = dict.modals.secretSetupSubmit;
    $('#secret-submit').disabled = false;
    dialog.showModal();
  }

  function openAction(actionOrId, presetValues = null) {
    const actionId = typeof actionOrId === 'object' && actionOrId !== null ? actionOrId.id : actionOrId;
    const dict = I18N[state.language] || I18N.pl;
    const item = state.actions.find(entry => entry.id === actionId); if (!item) return;
    if (item.kind === 'secret-edit') return openSecretEditor(item.profile);
    if (item.kind === 'secret-setup') return openSecretSetup(item.profile);
    const dialog = $('#action-dialog'); dialog.dataset.action = item.id;
    $('#dialog-group').textContent = item.group; $('#dialog-title').textContent = item.title; $('#dialog-description').textContent = item.description;
    const fieldsToRender = item.fields.map(f => {
      if (presetValues && presetValues[f.name] !== undefined) {
        return { ...f, default: presetValues[f.name] };
      }
      return f;
    });
    $('#dialog-fields').innerHTML = fieldsToRender.map(fieldHtml).join('') || `<p class="muted">${escapeHtml(dict.modals.noParams)}</p>`;
    const danger = $('#danger-confirm');
    danger.hidden = !item.danger;
    danger.innerHTML = `<input type="checkbox"> ${escapeHtml(dict.modals.dangerConfirm)}`;
    const submitBtn = $('#action-form button[type="submit"]');
    if (submitBtn) submitBtn.textContent = dict.modals.btnRun;
    const cancelBtn = $('#action-dialog .modal-cancel');
    if (cancelBtn) cancelBtn.textContent = dict.modals.btnCancel;
    dialog.showModal();
  }

  async function submitAction(event) {
    event.preventDefault(); const dialog = $('#action-dialog'); const item = state.actions.find(entry => entry.id === dialog.dataset.action);
    const dict = I18N[state.language] || I18N.pl;
    const values = Object.fromEntries(new FormData(event.currentTarget).entries()); const confirmed = !item.danger || $('input', $('#danger-confirm')).checked;
    if (item.danger && !confirmed) return toast(dict.modals.confirmOpToast, true);
    try {
      const job = await api('/api/jobs', {method:'POST', body:JSON.stringify({action:item.id, values, confirmed})});
      dialog.close(); state.selectedJob = job.id; showDrawer(job); pollJob(job.id); toast(`${dict.toasts.jobStarted}: ${item.title}`);
    } catch (error) { toast(error.message, true); }
  }

  async function submitSecretEdit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const dialog = $('#secret-dialog');
    if (!$('#secret-confirm').checked) return toast(state.language === 'en' ? 'Please confirm saving changed secrets.' : 'Potwierdź zapis zmienionych sekretów.', true);
    const values = Object.fromEntries([...new FormData(form).entries()].filter(([name, value]) => name !== 'confirm' && value !== ''));
    try {
      const result = await api('/api/secrets/update', {method: 'POST', body: JSON.stringify({profile: dialog.dataset.profile, values, confirmed: true})});
      dialog.close();
      await loadSecrets();
      toast(result.message || (state.language === 'en' ? 'Secrets saved encrypted.' : 'Sekrety zapisano zaszyfrowane.'));
    } catch (error) { toast(error.message, true); }
  }

  async function submitSecretSetup(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const dialog = $('#secret-dialog');
    if (!$('#secret-confirm').checked) return toast(state.language === 'en' ? 'Please confirm creating encrypted secrets file.' : 'Potwierdź utworzenie zaszyfrowanego pliku sekretów.', true);
    const values = Object.fromEntries([...new FormData(form).entries()].filter(([name]) => name !== 'confirm'));
    try {
      const result = await api('/api/secrets/setup', {method: 'POST', body: JSON.stringify({profile: dialog.dataset.profile, values, confirmed: true})});
      dialog.close();
      await loadSecrets();
      toast(result.message || (state.language === 'en' ? 'Secrets created and encrypted.' : 'Sekrety utworzono zaszyfrowane.'));
    } catch (error) { toast(error.message, true); }
  }

  const EXTERNAL_APPS = {
    swagger: {
      name: 'Swagger UI',
      icon: '📖',
      url: 'http://127.0.0.1:18080/swagger-ui.html',
      eyebrow: { pl: 'DOKUMENTACJA OPENAPI & TESTY API', en: 'OPENAPI DOCS & API TESTING' },
      title: { pl: 'Przejście do Swagger UI', en: 'Navigate to Swagger UI' },
      desc: {
        pl: 'Za chwilę przejdziesz do interaktywnej dokumentacji <strong>Swagger UI</strong> (port :18080) z zagregowaną listą wszystkich 15 mikroserwisów. Czy chcesz otworzyć ją w nowej karcie?',
        en: 'You are about to navigate to <strong>Swagger UI</strong> documentation (port :18080) aggregated for all 15 microservices. Would you like to open it in a new browser tab?'
      }
    },
    grafana: {
      name: 'Grafana',
      icon: '📊',
      url: 'http://127.0.0.1:13000',
      eyebrow: { pl: 'PULPITY METRYK & MONITORING', en: 'METRICS & MONITORING' },
      title: { pl: 'Przejście do panelu Grafana', en: 'Navigate to Grafana Dashboard' },
      desc: {
        pl: 'Za chwilę przejdziesz na zewnętrzną stronę narzędzia <strong>Grafana</strong> (port :13000) do wizualizacji metryk i wykresów mikroserwisów. Czy chcesz otworzyć ją w nowej karcie?',
        en: 'You are about to navigate to external <strong>Grafana</strong> monitoring dashboard (port :13000). Would you like to open it in a new browser tab?'
      }
    },
    prometheus: {
      name: 'Prometheus',
      icon: '🔥',
      url: 'http://127.0.0.1:19090',
      eyebrow: { pl: 'ZBIERACZ METRYK & ALERTING', en: 'METRICS SCRAPER & ALERTS' },
      title: { pl: 'Przejście do panelu Prometheus', en: 'Navigate to Prometheus' },
      desc: {
        pl: 'Za chwilę przejdziesz na zewnętrzną stronę narzędzia <strong>Prometheus</strong> (port :19090) do badania metryk i celów (Targets/PromQL). Czy chcesz otworzyć ją w nowej karcie?',
        en: 'You are about to navigate to external <strong>Prometheus</strong> metrics scraper (port :19090). Would you like to open it in a new browser tab?'
      }
    }
  };

  let currentExternalAppUrl = '';

  function openExternalAppDialog(appKey) {
    const app = EXTERNAL_APPS[appKey];
    if (!app) return;
    const lang = state.language === 'en' ? 'en' : 'pl';
    currentExternalAppUrl = app.url;

    const dialog = $('#external-link-dialog');
    if (!dialog) return;

    $('#external-modal-icon').textContent = app.icon;
    $('#external-modal-eyebrow').textContent = app.eyebrow[lang];
    $('#external-modal-title').textContent = app.title[lang];
    $('#external-modal-desc').innerHTML = app.desc[lang];
    $('#external-modal-url').textContent = app.url;
    $('#external-modal-cancel').textContent = lang === 'en' ? 'Stay (Cancel)' : 'Zostań (Anuluj)';
    $('#external-modal-confirm').textContent = lang === 'en' ? 'Open in new tab ↗' : 'Otwórz stronę ↗';

    dialog.showModal();
  }

  function submitSecretForm(event) {
    if ($('#secret-dialog').dataset.mode === 'setup') return submitSecretSetup(event);
    return submitSecretEdit(event);
  }

  function getJobTitle(job) {
    if (!job) return '';
    const isEn = state.language === 'en';
    if (job.actionId && state.actions && state.actions.length) {
      const act = state.actions.find(a => a.id === job.actionId);
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

  function renderDiagnosticBanner(diag, job) {
    if (!diag) return '';
    const lang = state.language || 'pl';
    const dict = I18N[lang] || I18N.pl;
    const dDiag = dict.diagnostics || {};
    const title = diag.title?.[lang] || diag.title?.pl || dDiag.detectedProblem || (lang === 'en' ? 'Detected Problem' : 'Wykryty problem');
    const cause = diag.cause?.[lang] || diag.cause?.pl || '';
    const solution = diag.solution?.[lang] || diag.solution?.pl || '';
    const fixLabel = diag.fixButtonLabel?.[lang] || diag.fixButtonLabel?.pl || dDiag.quickFix || (lang === 'en' ? 'Quick Fix' : 'Napraw automatycznie');
    const isUnknown = !diag.matched;
    const tagLabel = isUnknown ? (dDiag.unknownIssue || (lang === 'en' ? 'Unknown Issue' : 'Nieznany błąd')) : (diag.category?.toUpperCase() || 'DIAGNOSTIC');
    const icon = isUnknown ? '❓' : '🚨';

    let fixButtonHtml = '';
    if (diag.fixAction) {
      fixButtonHtml = `<button type="button" class="btn-fix-action" data-fix-action="${escapeHtml(diag.fixAction)}"><span>🛠</span> ${escapeHtml(fixLabel)}</button>`;
    }

    const askAiLabel = dDiag.askAiBtn || (lang === 'en' ? '✦ Ask AI Assistant' : '✦ Zapytaj Asystenta AI');
    const askAiHtml = `<button type="button" class="btn-ask-ai" data-ask-ai-job="${escapeHtml(job?.id || '')}"><span>✦</span> ${escapeHtml(askAiLabel.replace(/^✦\s*/, ''))}</button>`;

    const causeHeading = dDiag.causeLabel || (lang === 'en' ? '👉 Cause:' : '👉 Przyczyna:');
    const solHeading = dDiag.solutionLabel || (lang === 'en' ? '💡 Solution:' : '💡 Rozwiązanie:');

    return `
      <div class="diagnostic-banner">
        <div class="diagnostic-header">
          <div class="diagnostic-title-group">
            <span class="diagnostic-icon">${icon}</span>
            <h4 class="diagnostic-title">${escapeHtml(title)}</h4>
          </div>
          <span class="diagnostic-tag ${isUnknown ? 'unknown' : ''}">${escapeHtml(tagLabel)}</span>
        </div>
        <div class="diagnostic-content">
          <div class="diagnostic-field">
            <strong>${causeHeading}</strong>
            <span>${escapeHtml(cause)}</span>
          </div>
          <div class="diagnostic-field">
            <strong>${solHeading}</strong>
            <span>${escapeHtml(solution)}</span>
          </div>
        </div>
        <div class="diagnostic-actions">
          ${fixButtonHtml}
          ${askAiHtml}
        </div>
      </div>
    `;
  }

  async function triggerDirectAction(actionOrId) {
    const actionId = typeof actionOrId === 'object' && actionOrId !== null ? actionOrId.id : actionOrId;
    const item = state.actions.find(entry => entry.id === actionId);
    if (!item) return toast(state.language === 'en' ? `Action not found: ${actionId}` : `Nie znaleziono akcji: ${actionId}`, true);

    if (item.danger || item.kind?.startsWith('secret-')) {
      return openAction(item.id);
    }

    const values = {};
    let hasRequiredEmptyField = false;
    if (item.fields?.length) {
      for (const field of item.fields) {
        if (field.default !== undefined && field.default !== null && field.default !== '') {
          values[field.name] = String(field.default);
        } else if (field.required) {
          hasRequiredEmptyField = true;
        }
      }
    }

    if (hasRequiredEmptyField) {
      return openAction(item.id);
    }

    const dict = I18N[state.language] || I18N.pl;
    try {
      const job = await api('/api/jobs', {method: 'POST', body: JSON.stringify({action: item.id, values, confirmed: true})});
      state.selectedJob = job.id;
      showDrawer(job);
      pollJob(job.id);
      toast(`${dict.toasts.jobStarted}: ${item.title}`);
    } catch (error) {
      toast(error.message, true);
    }
  }

  function playSound(type) {
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (!AudioCtx) return;
      const ctx = new AudioCtx();
      const now = ctx.currentTime;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.connect(gain);
      gain.connect(ctx.destination);
      if (type === 'success') {
        osc.type = 'sine';
        osc.frequency.setValueAtTime(587.33, now);
        osc.frequency.exponentialRampToValueAtTime(880.00, now + 0.12);
        gain.gain.setValueAtTime(0.12, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.35);
        osc.start(now);
        osc.stop(now + 0.35);
      } else if (type === 'error') {
        osc.type = 'triangle';
        osc.frequency.setValueAtTime(240, now);
        osc.frequency.setValueAtTime(180, now + 0.14);
        gain.gain.setValueAtTime(0.18, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.4);
        osc.start(now);
        osc.stop(now + 0.4);
      }
    } catch (_) {}
  }

  function notifyDesktop(title, body) {
    if (typeof Notification !== 'undefined') {
      if (Notification.permission === 'granted') {
        try { new Notification(title, { body }); } catch (_) {}
      } else if (Notification.permission === 'default') {
        Notification.requestPermission();
      }
    }
  }

  function updateFaviconAndTitle(online, total) {
    if (total > 0 && online === total) {
      document.title = `🟢 (${online}/${total}) MiniTech Control`;
    } else if (total > 0 && online < total) {
      document.title = `🔴 (${online}/${total}) MiniTech Control`;
    } else {
      document.title = `MiniTech Control`;
    }
  }

  let currentReportMarkdown = '';

  async function openDiagnosticReportDialog() {
    const dialog = $('#report-dialog');
    if (!dialog) return;

    const isEn = state.language === 'en';
    const eyebrow = $('#report-eyebrow');
    if (eyebrow) eyebrow.textContent = isEn ? 'ENVIRONMENT DIAGNOSTIC REPORT' : 'RAPORT DIAGNOSTYCZNY ŚRODOWISKA';
    const title = $('#report-title');
    if (title) title.textContent = isEn ? '📋 MiniTech Platform Status' : '📋 Stan Platformy MiniTech';
    const okBtn = $('#report-ok-btn');
    if (okBtn) okBtn.textContent = 'OK';
    const cancelBtn = $('#report-cancel-btn');
    if (cancelBtn) cancelBtn.textContent = isEn ? 'Cancel' : 'Anuluj';
    const copyBtn = $('#report-copy-md');
    if (copyBtn) copyBtn.textContent = isEn ? '📋 Copy to clipboard' : '📋 Kopiuj do schowka';

    $('#report-body').innerHTML = '<div class="skeleton" style="height: 220px; border-radius: 12px;"></div>';
    $('#report-chip-time').textContent = isEn ? 'Fetching…' : 'Pobieranie…';
    
    dialog.showModal();

    try {
      const data = await api('/api/system/diagnostic-report');
      currentReportMarkdown = data.markdown || '';

      const online = data.onlineCount ?? 0;
      const total = data.totalCount ?? (data.services?.length || 15);
      const isAllUp = online === total;

      $('#report-chip-profile').textContent = `${isEn ? 'Profile' : 'Profil'}: ${escapeHtml(data.profile || state.profile).toUpperCase()}`;
      const healthChip = $('#report-chip-health');
      healthChip.textContent = `${online}/${total} ONLINE ${isAllUp ? '🟢' : '🟡'}`;
      healthChip.className = `report-chip ${isAllUp ? 'online' : ''}`;
      $('#report-chip-time').textContent = data.timestamp || new Date().toLocaleString();

      const sTitle = isEn ? 'Microservices & Gateways (15 services)' : 'Mikroserwisy i bramki (15 usług)';
      const secTitle = isEn ? 'SOPS & Age Secrets Vault' : 'Skarbiec Sekretów SOPS / Age';
      const jTitle = isEn ? 'Recent Operations & Session Jobs' : 'Ostatnie Zadania i Logi Sesji';

      const servicesHtml = (data.services || []).map(s => `
        <div class="report-service-item">
          <span class="state-light ${s.online ? 'online' : ''}"></span>
          <strong>${escapeHtml(s.name)}</strong>
          <small>${s.online ? `${s.latencyMs} ms` : 'OFFLINE'}</small>
        </div>
      `).join('') || `<div class="muted">${isEn ? 'No service data' : 'Brak danych o serwisach'}</div>`;

      const profiles = data.secretProfiles || {};
      const secretsHtml = Object.keys(profiles).map(k => {
        const p = profiles[k] || {};
        const keyOk = p.key ? '🟢 OK' : '🔴 BRAK';
        const fileOk = p.file ? '🟢 OK' : '🔴 BRAK';
        return `
          <div class="report-secret-card">
            <header>Profil ${escapeHtml(k).toUpperCase()}</header>
            <div class="report-secret-row"><span>Klucz Age:</span><strong>${keyOk}</strong></div>
            <div class="report-secret-row"><span>Plik SOPS:</span><strong>${fileOk}</strong></div>
          </div>
        `;
      }).join('') || `<div class="muted">${isEn ? 'No secrets data' : 'Brak danych o sekretach'}</div>`;

      const jobs = data.recentJobs || [];
      const jobsHtml = jobs.length > 0 ? jobs.map(j => `
        <div class="report-job-row">
          <div>
            <strong>${escapeHtml(j.title)}</strong>
            <small style="color:var(--muted); margin-left: 6px;">(${escapeHtml(j.actionId)})</small>
          </div>
          <div>
            <span class="status-tag ${j.status === 'success' ? 'online' : ''}">${escapeHtml(j.status)}</span>
          </div>
        </div>
      `).join('') : `<div class="muted" style="font-size:12px; padding: 4px 0;">${isEn ? 'No jobs recorded in current session.' : 'Brak zarejestrowanych zadań w bieżącej sesji.'}</div>`;

      $('#report-body').innerHTML = `
        <section class="report-section">
          <div class="report-section-title">
            <span>🌐 ${escapeHtml(sTitle)}</span>
            <small style="color:${isAllUp ? 'var(--green)' : 'var(--accent)'}; font-weight:700;">${online}/${total} UP</small>
          </div>
          <div class="report-services-grid">${servicesHtml}</div>
        </section>

        <section class="report-section">
          <div class="report-section-title">
            <span>🔒 ${escapeHtml(secTitle)}</span>
          </div>
          <div class="report-secrets-grid">${secretsHtml}</div>
        </section>

        <section class="report-section">
          <div class="report-section-title">
            <span>🐳 ${escapeHtml(jTitle)}</span>
          </div>
          <div class="report-jobs-list">${jobsHtml}</div>
        </section>
      `;

      playSound('open');
    } catch (err) {
      $('#report-body').innerHTML = `<div class="danger-confirm">${escapeHtml(err.message)}</div>`;
      playSound('error');
    }
  }

  function showDrawer(job) {
    const title = getJobTitle(job);
    $('#job-drawer').classList.add('visible');
    $('#drawer-title').textContent = title;
    $('#drawer-status').className = `status-dot ${job.status}`;
  }

  async function pollJob(id) {
    clearTimeout(state.jobTimer);
    const dict = I18N[state.language] || I18N.pl;
    try {
      const job = await api(`/api/jobs/${id}`);
      state.selectedJobData = job;
      showDrawer(job);
      if (state.selectedJob === id && $('#view-jobs').classList.contains('active')) {
        const out = $('#job-output');
        const wasNearBottom = (out.scrollHeight - out.scrollTop - out.clientHeight) < 80;
        out.textContent = job.output || dict.jobs.waitingOutput;
        const diagSlot = $('#job-diagnostic-slot');
        if (diagSlot) {
          diagSlot.innerHTML = job.diagnosis ? renderDiagnosticBanner(job.diagnosis, job) : '';
        }
        if (wasNearBottom) {
          out.scrollTop = out.scrollHeight;
        }
      }
      if (job.status === 'running' || job.status === 'queued') state.jobTimer = setTimeout(() => pollJob(id), 1000);
      else {
        const jobTitle = getJobTitle(job);
        playSound(job.status === 'success' ? 'success' : 'error');
        notifyDesktop(job.status === 'success' ? `✅ ${jobTitle}` : `❌ ${jobTitle}`, job.status === 'success' ? (state.language === 'en' ? 'Job completed successfully.' : 'Zadanie zakończone powodzeniem.') : (state.language === 'en' ? 'Job failed.' : 'Zadanie zakończyło się błędem.'));
        toast(job.status === 'success' ? `${dict.toasts.jobFinished}: ${jobTitle}` : `${dict.toasts.jobError}: ${jobTitle}`, job.status !== 'success');
        if (job.action === 'language-set' || job.command?.includes('language-set')) {
          const bootstrapData = await api('/api/bootstrap');
          state.actions = bootstrapData.actions;
          applyLanguage(bootstrapData.language);
          renderOperations();
          loadSecrets();
        }
        loadJobs(); loadHealth();
      }
    } catch (error) { toast(error.message, true); }
  }

  function renderJobsList() {
    const dict = I18N[state.language] || I18N.pl;
    const historyEl = $('#job-history');
    const recentEl = $('#recent-jobs');
    if (!state.jobs || !state.jobs.length) {
      if (historyEl) {
        historyEl.className = 'job-history empty-state';
        historyEl.innerHTML = `<div class="empty-state">${escapeHtml(dict.jobs.emptyHistory)}</div>`;
      }
      if (recentEl) {
        recentEl.className = 'job-list';
        recentEl.innerHTML = `<div class="empty-state">${escapeHtml(dict.dashboard.emptyJobs)}</div>`;
      }
      return;
    }
    const html = state.jobs.map(job => {
      const title = getJobTitle(job);
      return `<button class="job-row ${state.selectedJob === job.id ? 'active' : ''}" data-job="${job.id}"><span class="job-status ${job.status}">${job.status}</span><strong>${escapeHtml(title)}</strong><small>${formatTime(job.createdAt)} · ${escapeHtml(job.command)}</small></button>`;
    }).join('');

    if (historyEl) {
      historyEl.className = 'job-history';
      historyEl.innerHTML = html;
    }
    if (recentEl) {
      recentEl.className = 'job-list';
      recentEl.innerHTML = state.jobs.slice(0, 4).map(job => {
        const title = getJobTitle(job);
        return `<div class="service-preview-row"><span class="state-light ${job.status === 'success' ? 'online' : ''}"></span><strong>${escapeHtml(title)}</strong><small>${escapeHtml(job.status)}</small></div>`;
      }).join('');
    }
  }

  async function loadJobs() {
    try {
      const data = await api('/api/jobs');
      state.jobs = data.jobs || [];
      renderJobsList();
    } catch (error) { toast(error.message, true); }
  }

  async function selectJob(id) {
    const dict = I18N[state.language] || I18N.pl;
    state.selectedJob = id;
    $$('.job-row').forEach(row => row.classList.toggle('active', row.dataset.job === id));
    try {
      const job = await api(`/api/jobs/${id}`);
      state.selectedJobData = job;
      const out = $('#job-output');
      out.textContent = job.output || dict.jobs.noLogContent;
      const diagSlot = $('#job-diagnostic-slot');
      if (diagSlot) {
        diagSlot.innerHTML = job.diagnosis ? renderDiagnosticBanner(job.diagnosis, job) : '';
      }
      out.scrollTop = out.scrollHeight;
      if (job.status === 'running') pollJob(id);
    }
    catch (error) { toast(error.message, true); }
  }

  function statusLabel(value) {
    if (state.language === 'en') return value === 'NEW' ? 'Pending' : value === 'ANSWERED' ? 'Answered' : value || '—';
    return value === 'NEW' ? 'Oczekuje' : value === 'ANSWERED' ? 'Odpowiedziano' : value || '—';
  }

  async function loadChat(silent = false) {
    const dict = I18N[state.language] || I18N.pl;
    try {
      const data = await api('/api/chat/messages');
      const messages = data.messages || [];
      const pending = messages.filter(item => item.status === 'NEW').length;
      const badge = $('#chat-badge');
      if (badge) {
        badge.hidden = pending === 0;
        badge.textContent = pending;
      }

      const list = $('#conversation-list');
      if (list) {
        list.innerHTML = messages.map(item => `
          <button class="conversation-card ${state.selectedChat?.id === item.id ? 'active' : ''}" data-chat="${item.id}">
            <header>
              <strong>${escapeHtml(item.customerName)}</strong>
              <time>${escapeHtml(statusLabel(item.status))}</time>
            </header>
            <p>${escapeHtml(item.subject || (state.language === 'en' ? 'Customer inquiry' : 'Pytanie klienta'))}</p>
            <p>${escapeHtml(item.message)}</p>
          </button>
        `).join('') || `<div class="empty-state">${escapeHtml(dict.chat.noMessages)}</div>`;
      }

      if (state.selectedChat) {
        const updated = messages.find(item => item.id === state.selectedChat.id);
        if (updated) {
          const changed = updated.status !== state.selectedChat.status || updated.replyMessage !== state.selectedChat.replyMessage;
          state.selectedChat = updated;
          if (changed) {
            renderConversation(true);
          }
        }
      }
      state.chatMessages = messages;
    } catch (error) {
      if (!silent) {
        const list = $('#conversation-list');
        if (list) list.innerHTML = `<div class="empty-state">${escapeHtml(error.message)}</div>`;
      }
    }
  }

  function renderConversation(preserveDraft = true) {
    const item = state.selectedChat; if (!item) return;
    const dict = I18N[state.language] || I18N.pl;
    const currentTa = $('#conversation-detail textarea[name="message"]');
    const isFocused = document.activeElement === currentTa;
    const draft = currentTa ? currentTa.value : '';
    const selStart = currentTa ? currentTa.selectionStart : null;
    const selEnd = currentTa ? currentTa.selectionEnd : null;

    $('#conversation-detail').innerHTML = `
      <p class="eyebrow accent">${state.language === 'en' ? 'CONVERSATION' : 'ROZMOWA'} #${item.id}</p>
      <h3>${escapeHtml(item.subject || (state.language === 'en' ? 'Customer inquiry' : 'Pytanie klienta'))}</h3>
      <p class="customer-meta">${escapeHtml(item.customerName)} · ${escapeHtml(item.customerEmail)}</p>
      <div class="message-bubble">${escapeHtml(item.message)}</div>
      ${item.replyMessage ? `<div class="message-bubble reply"><strong>MiniTech Store</strong><br>${escapeHtml(item.replyMessage)}</div>` : ''}
      <form class="reply-form">
        <label class="field-label">${escapeHtml(dict.chat.replyFormLabel)}
          <textarea name="message" required maxlength="4000" placeholder="${escapeHtml(dict.chat.replyPlaceholder)}"></textarea>
        </label>
        <button class="primary-button">${escapeHtml(dict.chat.btnSendReply)}</button>
      </form>
    `;

    if (preserveDraft && draft) {
      const newTa = $('#conversation-detail textarea[name="message"]');
      if (newTa) {
        newTa.value = draft;
        if (isFocused) {
          newTa.focus();
          if (selStart !== null && selEnd !== null) {
            newTa.setSelectionRange(selStart, selEnd);
          }
        }
      }
    }
  }

  async function sendReply(event) {
    if (!event.target.matches('.reply-form')) return;
    event.preventDefault();
    const form = event.target;
    const message = new FormData(form).get('message');
    const dict = I18N[state.language] || I18N.pl;
    try {
      await api(`/api/chat/${state.selectedChat.id}/reply`, {method:'POST', body:JSON.stringify({message})});
      toast(dict.chat.replySuccess);
      const ta = $('textarea[name="message"]', form);
      if (ta) ta.value = '';
      await loadChat();
    } catch (error) {
      toast(error.message, true);
    }
  }

  async function startTerminal() {
    try { const data = await api('/api/terminal', {method:'POST', body:'{}'}); state.terminalId = data.id; $('#terminal-input').disabled = false; $('#terminal-form button').disabled = false; $('#terminal-input').focus(); pollTerminal(); }
    catch (error) { toast(error.message, true); }
  }
  async function pollTerminal() {
    if (!state.terminalId) return; clearTimeout(state.terminalTimer);
    try { const data = await api(`/api/terminal/${state.terminalId}`); const output = $('#terminal-output'); output.textContent = data.output || ''; output.scrollTop = output.scrollHeight; if (data.alive) state.terminalTimer = setTimeout(pollTerminal, 500); }
    catch (error) { toast(error.message, true); }
  }
  async function sendTerminal(event) {
    event.preventDefault(); const input = $('#terminal-input'); if (!state.terminalId || !input.value) return;
    try { await api(`/api/terminal/${state.terminalId}/input`, {method:'POST', body:JSON.stringify({data:`${input.value}\n`})}); input.value = ''; pollTerminal(); }
    catch (error) { toast(error.message, true); }
  }

  async function toggleLanguage() {
    const nextLang = state.language === 'en' ? 'pl' : 'en';
    try {
      const data = await api('/api/language', {method: 'POST', body: JSON.stringify({language: nextLang})});
      state.language = data.language || nextLang;
      state.actions = data.actions || state.actions;
      applyLanguage(state.language);
      renderOperations();
      renderHealth();
      renderJobsList();
      await Promise.all([loadJobs(), loadSecrets()]);
      toast(state.language === 'en' ? 'Language switched to English.' : 'Język zmieniony na polski.');
    } catch (error) {
      toast(error.message, true);
    }
  }

  function formatMarkdown(text) {
    if (!text) return '';
    let html = escapeHtml(text);
    html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => `<pre><code class="lang-${lang}">${code.trim()}</code></pre>`);
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
    html = html.replace(/^### (.*$)/gim, '<h3>$1</h3>');
    html = html.replace(/^## (.*$)/gim, '<h3>$1</h3>');
    html = html.replace(/^# (.*$)/gim, '<h3>$1</h3>');
    html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    html = html.replace(/^> (.*$)/gim, '<blockquote>$1</blockquote>');
    if (html.includes('|')) {
      const lines = html.split('\n');
      let inTable = false;
      let tableHtml = '';
      const newLines = [];
      for (const line of lines) {
        const trimmed = line.trim();
        if (trimmed.startsWith('|') && trimmed.endsWith('|')) {
          if (!inTable) {
            inTable = true;
            tableHtml = '<table>';
          }
          if (trimmed.includes('---')) continue;
          const cells = trimmed.split('|').slice(1, -1).map(c => c.trim());
          const tag = tableHtml === '<table>' ? 'th' : 'td';
          tableHtml += `<tr>${cells.map(c => `<${tag}>${c}</${tag}>`).join('')}</tr>`;
        } else {
          if (inTable) {
            inTable = false;
            tableHtml += '</table>';
            newLines.push(tableHtml);
          }
          newLines.push(line);
        }
      }
      if (inTable) newLines.push(tableHtml + '</table>');
      html = newLines.join('\n');
    }
    html = html.replace(/^\s*[\-\*]\s+(.*$)/gim, '<li>$1</li>');
    html = html.replace(/(<li>.*<\/li>)/gs, '<ul>$1</ul>');
    html = html.split(/\n\n+/).map(para => {
      const p = para.trim();
      if (!p) return '';
      if (p.startsWith('<h3') || p.startsWith('<pre') || p.startsWith('<table') || p.startsWith('<ul') || p.startsWith('<blockquote')) return p;
      return `<p>${p.replace(/\n/g, '<br>')}</p>`;
    }).join('');
    return html;
  }

  function updateAiModeBadge() {
    const pill = $('#ai-mode-pill');
    const label = $('#ai-mode-label');
    if (!pill || !label) return;

    const cfg = state.aiConfig || {};
    const prov = cfg.provider || 'antigravity';
    const isEn = state.language === 'en';

    if (prov === 'cursor') {
      const modelName = cfg.model || 'Local';
      pill.className = 'ai-mode-pill custom-agent';
      label.textContent = isEn ? `Custom Agent: Ollama (${modelName})` : `Własny Agent: Ollama (${modelName})`;
      pill.title = isEn 
        ? `Connected to local LLM via endpoint: ${cfg.customEndpoint || 'http://localhost:11434/v1'}. Click to configure.`
        : `Połączono z lokalnym modelem Ollama pod adresem: ${cfg.customEndpoint || 'http://localhost:11434/v1'}. Kliknij, aby zmienić.`;
    } else if (prov === 'codex' && cfg.hasApiKey) {
      const modelName = cfg.model || 'gpt-4o';
      pill.className = 'ai-mode-pill custom-agent';
      label.textContent = isEn ? `Custom Agent: OpenAI (${modelName})` : `Własny Agent: OpenAI (${modelName})`;
      pill.title = isEn ? 'Connected to OpenAI API. Click to configure.' : 'Połączono z API OpenAI. Kliknij, aby zmienić.';
    } else if (prov === 'antigravity' && cfg.hasApiKey) {
      const modelName = cfg.model && cfg.model !== 'antigravity-expert' ? cfg.model : 'gemini-2.5-flash';
      pill.className = 'ai-mode-pill custom-agent';
      label.textContent = isEn ? `AI Model: Gemini (${modelName})` : `Model AI: Gemini (${modelName})`;
      pill.title = isEn ? 'Connected to Google Gemini API. Click to configure.' : 'Połączono z Google Gemini API. Kliknij, aby zmienić.';
    } else {
      pill.className = 'ai-mode-pill built-in';
      label.textContent = isEn ? 'Tryb wbudowany (Offline)' : 'Tryb wbudowany (Offline)';
      pill.title = isEn 
        ? 'Using built-in MiniTech architecture knowledge base (offline, no API keys). Click to configure external LLM.'
        : 'Używasz wbudowanej bazy wiedzy o architekturze 15 mikroserwisów (offline, bez kluczy API). Kliknij, aby podpiąć własnego Agenta.';
    }
  }

  async function loadAiConfig() {
    try {
      const data = await api('/api/ai/config');
      state.aiConfig = data;
      updateAiModeBadge();
    } catch (e) {
      console.error(e);
    }
  }

  function openAiSettings() {
    const dialog = $('#ai-settings-dialog');
    const cfg = state.aiConfig || {};
    const providerSelect = $('#ai-provider-select');
    if (providerSelect) providerSelect.value = cfg.provider || 'antigravity';
    const modelInput = $('#ai-model-input');
    if (modelInput) modelInput.value = cfg.model || 'antigravity-expert';
    const endpointInput = $('#ai-endpoint-input');
    if (endpointInput) endpointInput.value = cfg.customEndpoint || 'http://localhost:11434/v1';
    const keyInput = $('#ai-key-input');
    if (keyInput) keyInput.value = cfg.maskedApiKey || '';
    const ctxCb = $('#ai-context-checkbox');
    if (ctxCb) ctxCb.checked = cfg.includeContext !== false;
    dialog.showModal();
  }

  async function saveAiSettings(event) {
    event.preventDefault();
    const dict = I18N[state.language] || I18N.pl;
    const form = event.currentTarget;
    const formData = new FormData(form);
    const body = {
      provider: formData.get('provider'),
      model: formData.get('model'),
      customEndpoint: formData.get('customEndpoint'),
      apiKey: formData.get('apiKey'),
      includeContext: formData.get('includeContext') === 'on',
    };
    try {
      const saved = await api('/api/ai/config', { method: 'POST', body: JSON.stringify(body) });
      state.aiConfig = saved;
      updateAiModeBadge();
      $('#ai-settings-dialog').close();
      toast(dict.assistant.configSaved);
    } catch (error) {
      toast(error.message, true);
    }
  }

  function renderAiBubble(role, htmlContent) {
    const messages = $('#ai-messages');
    if (!messages) return null;
    const bubble = document.createElement('div');
    bubble.className = `assistant-bubble ${role === 'user' ? 'user-msg' : 'bot-msg'}`;
    bubble.innerHTML = `
      <span class="bubble-avatar">${role === 'user' ? '👤' : '✦'}</span>
      <div class="bubble-content">${htmlContent}</div>
    `;
    messages.appendChild(bubble);
    messages.scrollTop = messages.scrollHeight;
    return bubble;
  }

  async function sendAiMessage(promptText) {
    const dict = I18N[state.language] || I18N.pl;
    const input = $('#ai-input');
    const text = (promptText || (input ? input.value : '') || '').trim();
    if (!text) return toast(dict.assistant.errorEmpty, true);
    if (input && !promptText) input.value = '';

    renderAiBubble('user', `<p>${escapeHtml(text)}</p>`);
    state.aiHistory.push({ role: 'user', content: text });

    const thinkingBubble = renderAiBubble('assistant', `<p class="muted"><em>${escapeHtml(dict.assistant.thinking)}</em></p>`);
    const sendBtn = $('#ai-send-btn');
    if (sendBtn) sendBtn.disabled = true;

    try {
      const res = await api('/api/ai/chat', {
        method: 'POST',
        body: JSON.stringify({ message: text, history: state.aiHistory.slice(-10) }),
      });
      state.aiHistory.push({ role: 'assistant', content: res.reply });
      if (thinkingBubble) {
        thinkingBubble.querySelector('.bubble-content').innerHTML = formatMarkdown(res.reply);
      }
      const messages = $('#ai-messages');
      if (messages) messages.scrollTop = messages.scrollHeight;
    } catch (error) {
      if (thinkingBubble) {
        thinkingBubble.querySelector('.bubble-content').innerHTML = `<p style="color:var(--danger)">⚠️ ${escapeHtml(error.message)}</p>`;
      }
    } finally {
      if (sendBtn) sendBtn.disabled = false;
    }
  }

  function clearAiChat() {
    state.aiHistory = [];
    const dict = I18N[state.language] || I18N.pl;
    const a = dict.assistant;
    const messages = $('#ai-messages');
    if (messages) {
      messages.innerHTML = `
        <div class="assistant-bubble bot-msg">
          <span class="bubble-avatar">✦</span>
          <div class="bubble-content">
            <strong>${escapeHtml(a.welcomeTitle)}</strong>
            <p>${escapeHtml(a.welcomeP1)}</p>
            <p>${escapeHtml(a.welcomeP2)}</p>
          </div>
        </div>
      `;
    }
  }

  /* ==========================================================================
     1. Data View (Orders, Inventory, Customers)
     ========================================================================== */
  let dataSubTab = 'orders';
  let cachedOrders = [];
  let cachedInvoices = [];
  let cachedDispatchHistory = [];
  let cachedInventory = [];
  let cachedCustomers = [];
  let ordersStatusFilter = 'ALL';
  let dispatchFilter = 'ALL';
  let dataViewSetupDone = false;

  let ordersSortField = 'date';
  let ordersSortDir = 'desc';

  let invoicesSortField = 'date';
  let invoicesSortDir = 'desc';
  const expandedInvoiceCorrections = new Set();

  let inventorySortField = 'id';
  let inventorySortDir = 'asc';

  let customersSortField = 'id';
  let customersSortDir = 'asc';

  function updateTableSortHeaders(tableSelector, sortField, sortDir) {
    $$(`${tableSelector} th.th-sortable`).forEach(th => {
      const f = th.dataset.sort;
      const isSorted = f === sortField;
      th.classList.toggle('sorted-asc', isSorted && sortDir === 'asc');
      th.classList.toggle('sorted-desc', isSorted && sortDir === 'desc');
      const ind = $('.sort-indicator', th);
      if (ind) {
        ind.textContent = isSorted ? (sortDir === 'asc' ? '▲' : '▼') : '⇅';
      }
    });
  }

  function handleSortHeaderClick(tableSelector, sortSelectSelector, field, currentFieldGetter, currentDirGetter, sortSetter, renderFn) {
    const curF = currentFieldGetter();
    const curD = currentDirGetter();
    let newD = 'asc';
    if (curF === field) {
      newD = curD === 'asc' ? 'desc' : 'asc';
    } else {
      newD = (field === 'date' || field === 'amount' || field === 'id' || field === 'invoice' || field === 'available' || field === 'reserved' || field === 'price' || field === 'balance') ? 'desc' : 'asc';
    }
    sortSetter(field, newD);
    const sel = $(sortSelectSelector);
    if (sel) {
      const match = `${field}_${newD}`;
      if ([...sel.options].some(opt => opt.value === match)) {
        sel.value = match;
      }
    }
    updateTableSortHeaders(tableSelector, field, newD);
    renderFn();
  }

  function setupDataViewOnce() {
    if (dataViewSetupDone) return;
    dataViewSetupDone = true;

    $$('.sub-tab-btn[data-subtab]').forEach(btn => {
      btn.onclick = () => {
        dataSubTab = btn.dataset.subtab;
        $$('.sub-tab-btn[data-subtab]').forEach(b => b.classList.toggle('active', b === btn));
        $$('#view-data .subpane').forEach(p => p.classList.toggle('active', p.id === `data-pane-${dataSubTab}`));
        loadActiveDataTab();
      };
    });

    const refBtn = $('#data-refresh-btn');
    if (refBtn) refBtn.onclick = () => loadActiveDataTab(true);

    $$('.filter-pill[data-status-filter]').forEach(pill => {
      pill.onclick = () => {
        ordersStatusFilter = pill.dataset.statusFilter;
        $$('.filter-pill[data-status-filter]').forEach(p => p.classList.toggle('active', p === pill));
        renderOrdersTable();
      };
    });

    const ordersSearch = $('#orders-search-input');
    if (ordersSearch) ordersSearch.oninput = () => renderOrdersTable();

    const ordersSortSelect = $('#orders-sort-select');
    if (ordersSortSelect) {
      ordersSortSelect.onchange = () => {
        const val = ordersSortSelect.value;
        const [f, d] = val.split('_');
        ordersSortField = f;
        ordersSortDir = d;
        updateTableSortHeaders('#orders-table', ordersSortField, ordersSortDir);
        renderOrdersTable();
      };
    }

    $$('#orders-table th.th-sortable').forEach(th => {
      th.onclick = () => {
        handleSortHeaderClick('#orders-table', '#orders-sort-select', th.dataset.sort,
          () => ordersSortField, () => ordersSortDir,
          (f, d) => { ordersSortField = f; ordersSortDir = d; },
          renderOrdersTable
        );
      };
    });

    const invoicesSearch = $('#invoices-search-input');
    if (invoicesSearch) invoicesSearch.oninput = () => renderInvoicesTable();

    const invoicesSortSelect = $('#invoices-sort-select');
    if (invoicesSortSelect) {
      invoicesSortSelect.onchange = () => {
        const val = invoicesSortSelect.value;
        const [f, d] = val.split('_');
        invoicesSortField = f;
        invoicesSortDir = d;
        updateTableSortHeaders('#invoices-table', invoicesSortField, invoicesSortDir);
        renderInvoicesTable();
      };
    }

    const invoicesExportZipBtn = $('#invoices-export-zip-btn');
    if (invoicesExportZipBtn) {
      invoicesExportZipBtn.onclick = () => {
        toast(state.language === 'en' ? 'Preparing ZIP archive with invoices...' : 'Pobieranie archiwum ZIP ze wszystkimi fakturami...');
        window.location.href = '/api/data/invoices/export-zip';
      };
    }

    $$('#invoices-table th.th-sortable').forEach(th => {
      th.onclick = () => {
        handleSortHeaderClick('#invoices-table', '#invoices-sort-select', th.dataset.sort,
          () => invoicesSortField, () => invoicesSortDir,
          (f, d) => { invoicesSortField = f; invoicesSortDir = d; },
          renderInvoicesTable
        );
      };
    });

    const invSearch = $('#inventory-search-input');
    if (invSearch) invSearch.oninput = () => renderInventoryTable();

    const invSortSelect = $('#inventory-sort-select');
    if (invSortSelect) {
      invSortSelect.onchange = () => {
        const val = invSortSelect.value;
        const [f, d] = val.split('_');
        inventorySortField = f;
        inventorySortDir = d;
        updateTableSortHeaders('#inventory-table', inventorySortField, inventorySortDir);
        renderInventoryTable();
      };
    }

    $$('#inventory-table th.th-sortable').forEach(th => {
      th.onclick = () => {
        handleSortHeaderClick('#inventory-table', '#inventory-sort-select', th.dataset.sort,
          () => inventorySortField, () => inventorySortDir,
          (f, d) => { inventorySortField = f; inventorySortDir = d; },
          renderInventoryTable
        );
      };
    });

    const custSearch = $('#customers-search-input');
    if (custSearch) custSearch.oninput = () => renderCustomersTable();

    const custSortSelect = $('#customers-sort-select');
    if (custSortSelect) {
      custSortSelect.onchange = () => {
        const val = custSortSelect.value;
        const [f, d] = val.split('_');
        customersSortField = f;
        customersSortDir = d;
        updateTableSortHeaders('#customers-table', customersSortField, customersSortDir);
        renderCustomersTable();
      };
    }

    $$('#customers-table th.th-sortable').forEach(th => {
      th.onclick = () => {
        handleSortHeaderClick('#customers-table', '#customers-sort-select', th.dataset.sort,
          () => customersSortField, () => customersSortDir,
          (f, d) => { customersSortField = f; customersSortDir = d; },
          renderCustomersTable
        );
      };
    });

    const dispatchSearch = $('#dispatch-search-input');
    if (dispatchSearch) dispatchSearch.oninput = () => renderDispatchTable();

    $$('.filter-pill[data-dispatch-filter]').forEach(pill => {
      pill.onclick = () => {
        dispatchFilter = pill.dataset.dispatchFilter;
        $$('.filter-pill[data-dispatch-filter]').forEach(p => p.classList.toggle('active', p === pill));
        renderDispatchTable();
      };
    });
  }

  function loadActiveDataTab(force = false) {
    if (dataSubTab === 'orders') loadOrders(force);
    else if (dataSubTab === 'invoices') loadInvoices(force);
    else if (dataSubTab === 'dispatch') loadDispatch(force);
    else if (dataSubTab === 'inventory') loadInventory(force);
    else if (dataSubTab === 'customers') loadCustomers(force);
  }
  window.minitechReloadDataTab = () => loadActiveDataTab(true);

  async function loadDataView() {
    setupDataViewOnce();
    loadActiveDataTab();
  }

  async function loadOrders(force = false) {
    const tbody = $('#orders-tbody');
    if (!tbody) return;
    if (force || !cachedOrders.length) {
      tbody.innerHTML = `<tr><td colspan="6" class="table-empty">${state.language === 'en' ? 'Loading orders...' : 'Ładowanie zamówień...'}</td></tr>`;
      try {
        const res = await api('/api/data/orders');
        cachedOrders = res.orders || [];
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="6" class="table-empty error">⚠️ ${escapeHtml(err.message)} <button class="ghost-button btn-xs" style="margin-left:8px; vertical-align:middle;" onclick="window.minitechReloadDataTab()">🔄 ${state.language === 'en' ? 'Retry' : 'Ponów próbę'}</button></td></tr>`;
        return;
      }
    }
    renderOrdersTable();
  }

  function renderOrdersTable() {
    const tbody = $('#orders-tbody');
    if (!tbody) return;
    const query = ($('#orders-search-input')?.value || '').toLowerCase().trim();
    let list = cachedOrders;
    if (ordersStatusFilter !== 'ALL') {
      list = list.filter(o => String(o.status || '').toUpperCase() === ordersStatusFilter);
    }
    if (query) {
      list = list.filter(o => String(o.id).includes(query) || String(o.customer_id || o.customerId || '').includes(query));
    }
    if (!list.length) {
      tbody.innerHTML = `<tr><td colspan="6" class="table-empty">${state.language === 'en' ? 'No orders match criteria.' : 'Brak zamówień spełniających kryteria.'}</td></tr>`;
      return;
    }

    // Sort orders
    list = [...list].sort((a, b) => {
      if (ordersSortField === 'id') {
        const idA = Number(a.id) || 0;
        const idB = Number(b.id) || 0;
        return ordersSortDir === 'asc' ? idA - idB : idB - idA;
      }
      if (ordersSortField === 'amount') {
        const amtA = Number(a.total_price ?? a.totalPrice ?? a.total_amount ?? a.totalAmount ?? 0);
        const amtB = Number(b.total_price ?? b.totalPrice ?? b.total_amount ?? b.totalAmount ?? 0);
        return ordersSortDir === 'asc' ? amtA - amtB : amtB - amtA;
      }
      if (ordersSortField === 'date') {
        const dtA = (a.created_at || a.createdAt) ? new Date(a.created_at || a.createdAt).getTime() : 0;
        const dtB = (b.created_at || b.createdAt) ? new Date(b.created_at || b.createdAt).getTime() : 0;
        return ordersSortDir === 'asc' ? dtA - dtB : dtB - dtA;
      }
      if (ordersSortField === 'client') {
        const clA = String(a.customer_email || a.customerEmail || (a.customer_id ? `Klient #${a.customer_id}` : '') || (a.customerId ? `Klient #${a.customerId}` : '')).toLowerCase();
        const clB = String(b.customer_email || b.customerEmail || (b.customer_id ? `Klient #${b.customer_id}` : '') || (b.customerId ? `Klient #${b.customerId}` : '')).toLowerCase();
        return ordersSortDir === 'asc' ? clA.localeCompare(clB) : clB.localeCompare(clA);
      }
      if (ordersSortField === 'status') {
        const stA = String(a.status || '').toLowerCase();
        const stB = String(b.status || '').toLowerCase();
        return ordersSortDir === 'asc' ? stA.localeCompare(stB) : stB.localeCompare(stA);
      }
      if (ordersSortField === 'invoice') {
        const invA = Boolean(a.hasInvoice || a.has_invoice || a.invoiceNumber || a.invoice_number);
        const invB = Boolean(b.hasInvoice || b.has_invoice || b.invoiceNumber || b.invoice_number);
        return ordersSortDir === 'asc' ? (invA === invB ? 0 : invA ? 1 : -1) : (invA === invB ? 0 : invA ? -1 : 1);
      }
      return 0;
    });

    updateTableSortHeaders('#orders-table', ordersSortField, ordersSortDir);

    tbody.innerHTML = list.map(o => {
      const orderId = o.id;
      const custEmail = o.customer_email || o.customerEmail || '';
      const custId = o.customer_id || o.customerId;
      const clientLabel = custEmail ? escapeHtml(custEmail) : (custId ? `Klient #${escapeHtml(custId)}` : 'Klient anonimowy');
      const amt = Number(o.total_price ?? o.totalPrice ?? o.total_amount ?? o.totalAmount ?? 0);
      const cur = o.currency || 'PLN';
      const st = (o.status || 'PENDING').toUpperCase();
      const dt = o.created_at || o.createdAt ? new Date(o.created_at || o.createdAt).toLocaleString(state.language === 'en' ? 'en-US' : 'pl-PL') : '—';
      const isPaid = st === 'PAID' || st === 'COMPLETED';
      const hasInvoice = Boolean(o.hasInvoice || o.has_invoice || o.invoiceNumber || o.invoice_number);
      const invNum = o.invoiceNumber || o.invoice_number || '';

      let invoiceBtn = '';
      if (hasInvoice) {
        invoiceBtn = `<a href="http://127.0.0.1:18080/api/invoices/order/${orderId}/pdf" target="_blank" class="btn-mini" style="background:rgba(34,197,94,0.12); color:#4ade80; border-color:rgba(34,197,94,0.3); font-weight:700;" title="${invNum ? `Pobierz ${invNum}` : 'Pobierz PDF'}">📄 ${invNum ? escapeHtml(invNum) : 'PDF'} ↗</a>`;
      } else if (isPaid) {
        invoiceBtn = `
          <div style="display:inline-flex; align-items:center; gap:6px;">
            <span style="color:var(--muted); font-size:11px;">${state.language === 'en' ? 'No invoice' : 'Brak FV'}</span>
            <button class="btn-mini generate-invoice-btn" data-order-id="${orderId}" style="border-color:#f59e0b; color:#fbbf24; padding:2px 6px;" title="${state.language === 'en' ? 'Issue VAT invoice for this paid order' : 'Wystaw fakturę VAT dla tego opłaconego zamówienia'}">➕ Wystaw</button>
          </div>
        `;
      } else {
        invoiceBtn = `<span style="color:var(--muted); font-size:11px;">—</span>`;
      }
      const stClass = st.toLowerCase();
      return `
        <tr>
          <td><strong>#${orderId}</strong></td>
          <td title="${clientLabel}">${clientLabel}</td>
          <td><strong>${amt.toFixed(2)} ${escapeHtml(cur)}</strong></td>
          <td><span class="badge-status ${stClass}">${escapeHtml(st)}</span></td>
          <td style="font-size:11px; color:var(--muted);">${escapeHtml(dt)}</td>
          <td>${invoiceBtn}</td>
        </tr>
      `;
    }).join('');

    $$('.generate-invoice-btn', tbody).forEach(btn => {
      btn.onclick = async () => {
        const orderId = btn.dataset.orderId;
        btn.disabled = true;
        btn.textContent = state.language === 'en' ? 'Issuing...' : 'Wystawianie...';
        try {
          const res = await api('/api/data/orders/generate-invoice', {
            method: 'POST',
            body: JSON.stringify({ orderId }),
          });
          toast(state.language === 'en' ? `Invoice ${res.invoiceNumber} issued for order #${orderId}!` : `Wystawiono fakturę ${res.invoiceNumber} dla zamówienia #${orderId}!`);
          await loadOrders(true);
          window.open(`http://127.0.0.1:18080/api/invoices/order/${orderId}/pdf`, '_blank');
        } catch (err) {
          toast(err.message, true);
          btn.disabled = false;
          btn.textContent = state.language === 'en' ? '➕ Issue' : '➕ Wystaw';
        }
      };
    });
  }

  async function loadInvoices(force = false) {
    const tbody = $('#invoices-tbody');
    if (!tbody) return;
    if (force || !cachedInvoices.length) {
      tbody.innerHTML = `<tr><td colspan="10" class="table-empty">${state.language === 'en' ? 'Loading invoices...' : 'Ładowanie faktur...'}</td></tr>`;
      try {
        const res = await api('/api/data/invoices?limit=500');
        cachedInvoices = res.invoices || [];
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="10" class="table-empty error">⚠️ ${escapeHtml(err.message)} <button class="ghost-button btn-xs" style="margin-left:8px; vertical-align:middle;" onclick="window.minitechReloadDataTab()">🔄 ${state.language === 'en' ? 'Retry' : 'Ponów próbę'}</button></td></tr>`;
        return;
      }
    }
    renderInvoicesTable();
  }

  function renderInvoicesTable() {
    const tbody = $('#invoices-tbody');
    if (!tbody) return;
    const query = ($('#invoices-search-input')?.value || '').toLowerCase().trim();
    let list = cachedInvoices;
    if (query) {
      list = list.filter(inv =>
        String(inv.invoiceNumber || '').toLowerCase().includes(query) ||
        String(inv.orderId || '').includes(query) ||
        String(inv.buyerName || '').toLowerCase().includes(query) ||
        String(inv.companyName || '').toLowerCase().includes(query) ||
        String(inv.taxId || '').toLowerCase().includes(query) ||
        String(inv.customerEmail || '').toLowerCase().includes(query)
      );
    }

    // Update KPI metrics
    let count = list.length;
    let net = 0;
    let vat = 0;
    let gross = 0;
    for (const inv of list) {
      net += Number(inv.netAmount || 0);
      vat += Number(inv.vatAmount || 0);
      gross += Number(inv.grossAmount || 0);
    }
    const loc = state.language === 'en' ? 'en-US' : 'pl-PL';
    const kpiCount = $('#invoices-kpi-count');
    if (kpiCount) kpiCount.textContent = count;
    const kpiNet = $('#invoices-kpi-net');
    if (kpiNet) kpiNet.textContent = `${net.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} PLN`;
    const kpiVat = $('#invoices-kpi-vat');
    if (kpiVat) kpiVat.textContent = `${vat.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} PLN`;
    const kpiGross = $('#invoices-kpi-gross');
    if (kpiGross) kpiGross.textContent = `${gross.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} PLN`;

    const badge = $('#invoices-count-badge');
    if (badge) badge.textContent = `${count} ${state.language === 'en' ? (count === 1 ? 'invoice' : 'invoices') : (count === 1 ? 'faktura' : (count > 1 && count < 5 ? 'faktury' : 'faktur'))}`;

    if (!list.length) {
      tbody.innerHTML = `<tr><td colspan="10" class="table-empty">${state.language === 'en' ? 'No invoices match criteria.' : 'Brak faktur spełniających kryteria.'}</td></tr>`;
      return;
    }

    // Sort
    list = [...list].sort((a, b) => {
      if (invoicesSortField === 'number') {
        const nrA = String(a.invoiceNumber || '');
        const nrB = String(b.invoiceNumber || '');
        return invoicesSortDir === 'asc' ? nrA.localeCompare(nrB) : nrB.localeCompare(nrA);
      }
      if (invoicesSortField === 'order') {
        const oA = Number(a.orderId || 0);
        const oB = Number(b.orderId || 0);
        return invoicesSortDir === 'asc' ? oA - oB : oB - oA;
      }
      if (invoicesSortField === 'buyer') {
        const bA = String(a.companyName || a.buyerName || '').toLowerCase();
        const bB = String(b.companyName || b.buyerName || '').toLowerCase();
        return invoicesSortDir === 'asc' ? bA.localeCompare(bB) : bB.localeCompare(bA);
      }
      if (invoicesSortField === 'amount') {
        const gA = Number(a.grossAmount || 0);
        const gB = Number(b.grossAmount || 0);
        return invoicesSortDir === 'asc' ? gA - gB : gB - gA;
      }
      if (invoicesSortField === 'date') {
        const dtA = a.createdAt ? new Date(a.createdAt).getTime() : 0;
        const dtB = b.createdAt ? new Date(b.createdAt).getTime() : 0;
        return invoicesSortDir === 'asc' ? dtA - dtB : dtB - dtA;
      }
      if (invoicesSortField === 'id') {
        const idA = Number(a.id || 0);
        const idB = Number(b.id || 0);
        return invoicesSortDir === 'asc' ? idA - idB : idB - idA;
      }
      return 0;
    });

    updateTableSortHeaders('#invoices-table', invoicesSortField, invoicesSortDir);

    tbody.innerHTML = list.map(inv => {
      const dt = inv.createdAt ? new Date(inv.createdAt).toLocaleString(loc) : '—';
      const netStr = Number(inv.netAmount || 0).toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      const vatStr = Number(inv.vatAmount || 0).toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      const grossStr = Number(inv.grossAmount || 0).toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      const buyerDisplay = inv.companyName ? `<strong>${escapeHtml(inv.companyName)}</strong><br><small style="color:var(--muted);">${escapeHtml(inv.buyerName)}</small>` : escapeHtml(inv.buyerName || '—');
      const taxDisplay = inv.taxId ? `<code style="font-size:11px;background:rgba(255,255,255,0.06);padding:2px 6px;border-radius:4px;">${escapeHtml(inv.taxId)}</code>` : '<span style="color:var(--muted);">—</span>';
      const pdfUrl = `http://127.0.0.1:18080/api/invoices/order/${inv.orderId}/pdf`;
      const jsonUrl = `http://127.0.0.1:18080/api/invoices/order/${inv.orderId}`;
      const corrs = inv.corrections || [];
      const hasCorrs = corrs.length > 0;
      const totalDelta = corrs.reduce((acc, c) => acc + (Number(c.correctionGross) || 0), 0);
      const finalGross = (Number(inv.grossAmount) || 0) + totalDelta;
      const finalGrossStr = finalGross.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      const totalDeltaStr = `${totalDelta > 0 ? '+' : ''}${totalDelta.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
      const isExpanded = expandedInvoiceCorrections.has(Number(inv.id));

      return `
        <tr>
          <td>
            <div style="display:flex;align-items:center;">
              <strong style="font-family:monospace;color:var(--primary);">${escapeHtml(inv.invoiceNumber || '—')}</strong>
              ${hasCorrs ? `
                <button type="button" class="btn-corr-toggle ${isExpanded ? 'expanded' : ''}" data-invoice-id="${inv.id}" title="${state.language === 'en' ? 'Click to show/hide corrections history' : 'Kliknij, aby rozwinąć/zwinąć historię korekt'}">
                  <span class="corr-icon">📜</span>
                  <span>${corrs.length} ${state.language === 'en' ? (corrs.length === 1 ? 'corr.' : 'corrs.') : (corrs.length === 1 ? 'korekta' : (corrs.length < 5 ? 'korekty' : 'korekt'))}</span>
                  <span class="corr-chevron">${isExpanded ? '▲' : '▼'}</span>
                </button>
              ` : ''}
            </div>
          </td>
          <td><span class="badge" style="font-weight:700;">#${inv.orderId}</span></td>
          <td>${buyerDisplay}</td>
          <td>${taxDisplay}</td>
          <td style="font-size:12px;">${escapeHtml(inv.customerEmail || '—')}</td>
          <td style="text-align:right;font-variant-numeric:tabular-nums;color:var(--muted);">${netStr} ${inv.currency || 'PLN'}</td>
          <td style="text-align:right;font-variant-numeric:tabular-nums;color:var(--muted);">${vatStr} ${inv.currency || 'PLN'}</td>
          <td style="text-align:right;font-variant-numeric:tabular-nums;">
            <strong>${grossStr} ${inv.currency || 'PLN'}</strong>
            ${hasCorrs ? `<div style="font-size:11px;color:#fbbf24;font-weight:600;margin-top:2px;" title="${state.language === 'en' ? 'Final amount after corrections' : 'Kwota końcowa po uwzględnieniu korekt'}">${state.language === 'en' ? 'After corr.:' : 'Po kor.:'} ${finalGrossStr} ${inv.currency || 'PLN'}</div>` : ''}
          </td>
          <td style="font-size:11px;color:var(--muted);">${dt}</td>
          <td style="text-align:right;white-space:nowrap;">
            <a href="${jsonUrl}" target="_blank" class="btn-mini" style="margin-right:4px;" title="${state.language === 'en' ? 'View invoice JSON' : 'Podgląd JSON faktury'}">📄 JSON ↗</a>
            <a href="${pdfUrl}" target="_blank" download class="btn-mini" style="background:rgba(34,197,94,0.12);color:#4ade80;border-color:rgba(34,197,94,0.3);font-weight:700;margin-right:4px;" title="${state.language === 'en' ? 'Download PDF invoice' : 'Pobierz plik PDF faktury'}">📥 PDF ↗</a>
            <button type="button" class="btn-mini btn-correct-invoice" data-invoice-id="${inv.id}" data-invoice-number="${escapeHtml(inv.invoiceNumber || '')}" data-gross="${inv.grossAmount}" style="background:rgba(245,158,11,0.12);color:#fbbf24;border-color:rgba(245,158,11,0.35);margin-right:4px;" title="${state.language === 'en' ? 'Issue correction invoice (credit note)' : 'Wystaw fakturę korygującą (KOR)'}">📝 Korekta</button>
            <button type="button" class="btn-mini btn-send-doc" data-type="INVOICE" data-id="${inv.id}" data-order-id="${inv.orderId}" data-doc-num="${escapeHtml(inv.invoiceNumber || '')}" data-buyer="${escapeHtml(inv.companyName || inv.buyerName || '')}" data-email="${escapeHtml(inv.customerEmail || '')}" data-amount="${inv.grossAmount}" style="background:rgba(14,165,233,0.12);color:#38bdf8;border-color:rgba(14,165,233,0.35);" title="${state.language === 'en' ? 'Send invoice via email' : 'Wyślij fakturę na e-mail'}">✉️</button>
          </td>
        </tr>
        ${hasCorrs ? `
          <tr id="sub-corrs-${inv.id}" class="correction-subrow" style="display:${isExpanded ? 'table-row' : 'none'};">
            <td colspan="10">
              <div class="correction-subrow-wrap">
                <div class="correction-subrow-header">
                  <div class="correction-subrow-title">
                    <span>↳</span>
                    <span>${state.language === 'en' ? `Corrections history for ${escapeHtml(inv.invoiceNumber || '')}` : `Historia korekt do faktury ${escapeHtml(inv.invoiceNumber || '')}`}</span>
                    <span style="font-size:11px;color:var(--muted);font-weight:normal;">(${state.language === 'en' ? 'Total delta:' : 'Łączna zmiana:'} <strong class="${totalDelta < 0 ? 'correction-delta-neg' : 'correction-delta-pos'}">${totalDeltaStr} ${inv.currency || 'PLN'}</strong>)</span>
                  </div>
                  <div class="correction-subrow-meta">
                    ${corrs.length} ${state.language === 'en' ? (corrs.length === 1 ? 'document' : 'documents') : (corrs.length === 1 ? 'dokument korygujący' : (corrs.length < 5 ? 'dokumenty korygujące' : 'dokumentów'))}
                  </div>
                </div>
                <table class="correction-subtable">
                  <thead>
                    <tr>
                      <th style="width:150px;">${state.language === 'en' ? 'Correction No. (KOR)' : 'Nr korekty (KOR)'}</th>
                      <th style="width:140px;">${state.language === 'en' ? 'Issue Date' : 'Data wystawienia'}</th>
                      <th>${state.language === 'en' ? 'Correction Reason' : 'Przyczyna / Powód'}</th>
                      <th style="text-align:right;width:120px;">${state.language === 'en' ? 'Delta Gross' : 'Różnica brutto'}</th>
                      <th style="text-align:right;width:130px;">${state.language === 'en' ? 'Amount After' : 'Kwota po korekcie'}</th>
                      <th style="text-align:right;width:140px;">${state.language === 'en' ? 'Actions' : 'Pobierz'}</th>
                    </tr>
                  </thead>
                  <tbody>
                    ${corrs.map(c => {
                      const cDt = c.createdAt ? new Date(c.createdAt).toLocaleString(loc) : '—';
                      const deltaNum = Number(c.correctionGross) || 0;
                      const deltaFormatted = `${deltaNum > 0 ? '+' : ''}${deltaNum.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${inv.currency || 'PLN'}`;
                      const deltaClass = deltaNum < 0 ? 'correction-delta-neg' : (deltaNum > 0 ? 'correction-delta-pos' : '');
                      const afterNum = Number(c.finalGross) || 0;
                      const afterFormatted = `${afterNum.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${inv.currency || 'PLN'}`;
                      const corrPdfUrl = `/api/data/invoices/corrections/${c.id}/pdf`;
                      const corrJsonUrl = `http://127.0.0.1:18080/api/invoices/${inv.id}/corrections`;
                      return `
                        <tr>
                          <td>
                            <strong style="font-family:monospace;color:#fbbf24;">📜 ${escapeHtml(c.correctionNumber || `KOR #${c.id}`)}</strong>
                          </td>
                          <td style="font-size:11px;color:var(--muted);">${cDt}</td>
                          <td style="color:var(--text);font-size:12px;">${escapeHtml(c.reason || '—')}</td>
                          <td style="text-align:right;font-variant-numeric:tabular-nums;"><span class="${deltaClass}">${deltaFormatted}</span></td>
                          <td style="text-align:right;font-variant-numeric:tabular-nums;color:var(--muted);">${afterFormatted}</td>
                          <td style="text-align:right;white-space:nowrap;">
                            <a href="${corrJsonUrl}" target="_blank" class="btn-mini" style="margin-right:4px;" title="${state.language === 'en' ? 'View correction JSON' : 'Podgląd JSON korekty'}">📄 JSON ↗</a>
                            <a href="${corrPdfUrl}" target="_blank" download class="btn-mini" style="background:rgba(245,158,11,0.15);color:#fbbf24;border-color:rgba(245,158,11,0.4);font-weight:700;margin-right:4px;" title="${state.language === 'en' ? 'Download correction PDF' : 'Pobierz plik PDF korekty'}">📥 PDF ↗</a>
                            <button type="button" class="btn-mini btn-send-doc" data-type="CORRECTION" data-id="${c.id}" data-order-id="${c.orderId || inv.orderId}" data-doc-num="${escapeHtml(c.correctionNumber || `KOR #${c.id}`)}" data-buyer="${escapeHtml(inv.companyName || inv.buyerName || '')}" data-email="${escapeHtml(inv.customerEmail || '')}" data-amount="${c.correctionGross}" style="background:rgba(14,165,233,0.12);color:#38bdf8;border-color:rgba(14,165,233,0.35);" title="${state.language === 'en' ? 'Send correction via email' : 'Wyślij korektę na e-mail'}">✉️</button>
                          </td>
                        </tr>
                      `;
                    }).join('')}
                  </tbody>
                </table>
              </div>
            </td>
          </tr>
        ` : ''}
      `;
    }).join('');

    tbody.querySelectorAll('.btn-corr-toggle').forEach(btn => {
      btn.onclick = (e) => {
        e.stopPropagation();
        const invId = Number(btn.dataset.invoiceId);
        const subrow = $(`#sub-corrs-${invId}`);
        if (!subrow) return;
        const isHidden = subrow.style.display === 'none';
        subrow.style.display = isHidden ? 'table-row' : 'none';
        btn.classList.toggle('expanded', isHidden);
        const chevron = btn.querySelector('.corr-chevron');
        if (chevron) {
          chevron.textContent = isHidden ? '▲' : '▼';
        }
        if (isHidden) {
          expandedInvoiceCorrections.add(invId);
        } else {
          expandedInvoiceCorrections.delete(invId);
        }
      };
    });

    tbody.querySelectorAll('.btn-correct-invoice').forEach(btn => {
      btn.onclick = () => {
        const invId = btn.dataset.invoiceId;
        const invNum = btn.dataset.invoiceNumber;
        const gross = parseFloat(btn.dataset.gross) || 0;
        openCorrectionModal(invId, invNum, gross);
      };
    });

    tbody.querySelectorAll('.btn-send-doc').forEach(btn => {
      btn.onclick = () => {
        openDispatchModal({
          type: btn.dataset.type,
          id: btn.dataset.id,
          orderId: btn.dataset.orderId,
          docNumber: btn.dataset.docNum,
          buyerName: btn.dataset.buyer,
          email: btn.dataset.email,
          amount: btn.dataset.amount
        });
      };
    });
  }

  function openCorrectionModal(invId, invNum, gross) {
    const dialog = $('#correction-dialog');
    if (!dialog) return;
    const invIdInput = $('#correction-invoice-id');
    const origGrossInput = $('#correction-orig-gross');
    if (invIdInput) invIdInput.value = invId;
    if (origGrossInput) origGrossInput.value = gross;
    const title = $('#correction-dialog-title');
    if (title) title.textContent = state.language === 'en' ? `Issue Correction for ${invNum}` : `Wystawienie korekty faktury ${invNum}`;
    const subtitle = $('#correction-dialog-subtitle');
    if (subtitle) subtitle.textContent = state.language === 'en' ? `Original invoice amount: ${Number(gross).toFixed(2)} PLN` : `Pierwotna kwota faktury: ${Number(gross).toFixed(2)} PLN`;
    const reasonInput = $('#correction-reason');
    if (reasonInput) reasonInput.value = '';
    const deltaInput = $('#correction-delta');
    if (deltaInput) deltaInput.value = '';
    const errEl = $('#correction-error');
    if (errEl) { errEl.textContent = ''; errEl.hidden = true; }
    const origEl = $('#calc-orig-text');
    if (origEl) origEl.textContent = `${Number(gross).toFixed(2)} PLN`;
    const deltaEl = $('#calc-delta-text');
    if (deltaEl) { deltaEl.textContent = '0.00 PLN'; deltaEl.style.color = '#f87171'; }
    const finalEl = $('#calc-final-text');
    if (finalEl) { finalEl.textContent = `${Number(gross).toFixed(2)} PLN`; finalEl.style.color = '#4ade80'; }
    dialog.showModal();
  }

  function openDispatchModal(doc) {
    const dialog = $('#dispatch-dialog');
    if (!dialog) return;

    const docTypeInp = $('#dispatch-doc-type');
    const docIdInp = $('#dispatch-doc-id');
    const orderIdInp = $('#dispatch-order-id');
    const docNumberInp = $('#dispatch-doc-number');
    const buyerNameInp = $('#dispatch-buyer-name');

    if (docTypeInp) docTypeInp.value = doc.type || 'INVOICE';
    if (docIdInp) docIdInp.value = doc.id;
    if (orderIdInp) orderIdInp.value = doc.orderId || '';
    if (docNumberInp) docNumberInp.value = doc.docNumber || '';
    if (buyerNameInp) buyerNameInp.value = doc.buyerName || '';

    const isCorr = doc.type === 'CORRECTION';
    const docTitle = isCorr ? 'Faktura Korygująca (KOR)' : 'Faktura VAT';

    const title = $('#dispatch-dialog-title');
    if (title) {
      title.textContent = state.language === 'en'
        ? `Send ${docTitle} to customer`
        : `Wysyłka dokumentu: ${doc.docNumber}`;
    }
    const subtitle = $('#dispatch-dialog-subtitle');
    if (subtitle) {
      subtitle.textContent = state.language === 'en'
        ? 'Send PDF document with optional message via Mailpit / SMTP'
        : 'Wysyłka pliku PDF z opcjonalną notatką do klienta (SMTP → Mailpit)';
    }

    const infoDoc = $('#dispatch-info-doc');
    if (infoDoc) infoDoc.textContent = `${isCorr ? '📜' : '📄'} ${doc.docNumber}`;
    const infoOrder = $('#dispatch-info-order');
    if (infoOrder) infoOrder.textContent = `#${doc.orderId || '—'}`;
    const infoBuyer = $('#dispatch-info-buyer');
    if (infoBuyer) infoBuyer.textContent = doc.buyerName || '—';
    const infoAmount = $('#dispatch-info-amount');
    if (infoAmount) {
      const amtNum = parseFloat(doc.amount);
      infoAmount.textContent = !isNaN(amtNum) ? `${amtNum.toFixed(2)} PLN` : '—';
      infoAmount.style.color = isCorr && amtNum < 0 ? '#f87171' : '#4ade80';
    }

    const recipientInp = $('#dispatch-recipient');
    if (recipientInp) recipientInp.value = doc.email || '';

    const subjectInp = $('#dispatch-subject');
    if (subjectInp) {
      subjectInp.value = isCorr
        ? `[MiniTech] Faktura korygująca nr ${doc.docNumber} do zamówienia #${doc.orderId}`
        : `[MiniTech] Faktura VAT nr ${doc.docNumber} do zamówienia #${doc.orderId}`;
    }

    const msgInp = $('#dispatch-custom-message');
    if (msgInp) msgInp.value = '';

    const errEl = $('#dispatch-error');
    if (errEl) { errEl.textContent = ''; errEl.hidden = true; }

    const safeNr = String(doc.docNumber || '').replace(/[^\w\-_.]/g, '_');
    const safeFile = `${isCorr ? 'korekta' : 'faktura'}-${safeNr}.pdf`;
    const hintEl = $('#dispatch-attachment-hint');
    if (hintEl) {
      hintEl.textContent = state.language === 'en'
        ? `PDF attachment: ${safeFile} (downloaded live from Billing Service)`
        : `Do wiadomości zostanie dołączony plik PDF: ${safeFile} (pobrany z Billing Service).`;
    }

    dialog.showModal();
  }

  async function loadDispatch(force = false) {
    const tbody = $('#dispatch-tbody');
    if (!tbody) return;
    if (force || !cachedInvoices.length || !cachedDispatchHistory.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="table-empty">${state.language === 'en' ? 'Loading dispatch documents...' : 'Ładowanie dokumentów do wysyłki...'}</td></tr>`;
      try {
        const [invRes, histRes] = await Promise.all([
          cachedInvoices.length && !force ? Promise.resolve({ invoices: cachedInvoices }) : api('/api/data/invoices?limit=500'),
          api('/api/data/invoices/dispatch-history')
        ]);
        if (invRes.invoices) cachedInvoices = invRes.invoices;
        cachedDispatchHistory = histRes.history || [];
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="9" class="table-empty error">⚠️ ${escapeHtml(err.message)} <button class="ghost-button btn-xs" style="margin-left:8px; vertical-align:middle;" onclick="window.minitechReloadDataTab()">🔄 ${state.language === 'en' ? 'Retry' : 'Ponów próbę'}</button></td></tr>`;
        return;
      }
    }
    renderDispatchTable();
  }

  function renderDispatchTable() {
    const tbody = $('#dispatch-tbody');
    if (!tbody) return;

    const query = ($('#dispatch-search-input')?.value || '').toLowerCase().trim();
    const loc = state.language === 'en' ? 'en-US' : 'pl-PL';

    // 1. Flatten all dispatchable documents: invoices and corrections
    const docs = [];
    for (const inv of cachedInvoices) {
      docs.push({
        type: 'INVOICE',
        id: inv.id,
        orderId: inv.orderId,
        docNumber: inv.invoiceNumber || `FV #${inv.id}`,
        buyerName: inv.companyName || inv.buyerName || '—',
        email: inv.customerEmail || '',
        amount: inv.grossAmount,
        currency: inv.currency || 'PLN',
        createdAt: inv.createdAt,
        pdfUrl: `http://127.0.0.1:18080/api/invoices/order/${inv.orderId}/pdf`
      });

      for (const c of (inv.corrections || [])) {
        docs.push({
          type: 'CORRECTION',
          id: c.id,
          parentInvoiceId: inv.id,
          orderId: c.orderId || inv.orderId,
          docNumber: c.correctionNumber || `KOR #${c.id}`,
          originalDocNumber: inv.invoiceNumber,
          reason: c.reason || '',
          buyerName: inv.companyName || inv.buyerName || '—',
          email: inv.customerEmail || '',
          amount: c.correctionGross,
          finalGross: c.finalGross,
          currency: inv.currency || 'PLN',
          createdAt: c.createdAt,
          pdfUrl: `/api/data/invoices/corrections/${c.id}/pdf`
        });
      }
    }

    // Calculate overall stats before search filtering
    const totalAll = docs.length;
    const totalFv = docs.filter(d => d.type === 'INVOICE').length;
    const totalKor = docs.filter(d => d.type === 'CORRECTION').length;
    const totalSent = cachedDispatchHistory.length;

    const elCountAll = $('#dispatch-count-all');
    if (elCountAll) elCountAll.textContent = totalAll;
    const elCountFv = $('#dispatch-count-fv');
    if (elCountFv) elCountFv.textContent = totalFv;
    const elCountKor = $('#dispatch-count-kor');
    if (elCountKor) elCountKor.textContent = totalKor;
    const elCountSent = $('#dispatch-count-sent');
    if (elCountSent) elCountSent.textContent = totalSent;

    // Filter by type
    let list = docs;
    if (dispatchFilter === 'FV') list = list.filter(d => d.type === 'INVOICE');
    else if (dispatchFilter === 'KOR') list = list.filter(d => d.type === 'CORRECTION');

    // Filter by search query
    if (query) {
      list = list.filter(d =>
        String(d.docNumber || '').toLowerCase().includes(query) ||
        String(d.orderId || '').includes(query) ||
        String(d.buyerName || '').toLowerCase().includes(query) ||
        String(d.email || '').toLowerCase().includes(query) ||
        String(d.reason || '').toLowerCase().includes(query) ||
        String(d.originalDocNumber || '').toLowerCase().includes(query)
      );
    }

    // Sort by createdAt descending
    list.sort((a, b) => {
      const dtA = a.createdAt ? new Date(a.createdAt).getTime() : 0;
      const dtB = b.createdAt ? new Date(b.createdAt).getTime() : 0;
      return dtB - dtA;
    });

    const badge = $('#dispatch-count-badge');
    if (badge) {
      badge.textContent = `${list.length} ${state.language === 'en' ? (list.length === 1 ? 'document' : 'documents') : (list.length === 1 ? 'dokument' : (list.length < 5 ? 'dokumenty' : 'dokumentów'))}`;
    }

    if (!list.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="table-empty">${state.language === 'en' ? 'No documents match criteria.' : 'Brak dokumentów spełniających kryteria.'}</td></tr>`;
      return;
    }

    // Map history for fast lookup
    const sentMap = new Map();
    for (const h of cachedDispatchHistory) {
      const key = `${h.documentType}_${h.documentId}`;
      if (!sentMap.has(key)) {
        sentMap.set(key, h);
      }
      if (h.documentNumber && !sentMap.has(h.documentNumber)) {
        sentMap.set(h.documentNumber, h);
      }
    }

    tbody.innerHTML = list.map(d => {
      const isCorr = d.type === 'CORRECTION';
      const dt = d.createdAt ? new Date(d.createdAt).toLocaleString(loc) : '—';
      const typeBadge = isCorr
        ? '<span class="dispatch-doc-badge kor">📜 KOR</span>'
        : '<span class="dispatch-doc-badge fv">📄 FV</span>';

      const docDisplay = isCorr
        ? `<strong>${escapeHtml(d.docNumber)}</strong><br><small style="color:var(--muted); font-size:11px;">do: ${escapeHtml(d.originalDocNumber || 'FV')}</small>`
        : `<strong style="font-family:monospace; color:var(--primary);">${escapeHtml(d.docNumber)}</strong>`;

      let amtDisplay = '—';
      if (isCorr) {
        const delta = Number(d.amount) || 0;
        const deltaStr = `${delta > 0 ? '+' : ''}${delta.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${d.currency || 'PLN'}`;
        const finalNum = Number(d.finalGross) || 0;
        const finalStr = `${finalNum.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${d.currency || 'PLN'}`;
        amtDisplay = `<span class="${delta < 0 ? 'correction-delta-neg' : 'correction-delta-pos'}" style="font-weight:700;">${deltaStr}</span><br><small style="color:var(--muted); font-size:11px;">po: ${finalStr}</small>`;
      } else {
        const gross = Number(d.amount) || 0;
        amtDisplay = `<strong>${gross.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${d.currency || 'PLN'}</strong>`;
      }

      const sentRecord = sentMap.get(`${d.type}_${d.id}`) || sentMap.get(d.docNumber);
      const isSent = !!sentRecord;
      const statusDisplay = isSent
        ? `<span class="badge-status completed" title="Wysłano: ${escapeHtml(sentRecord.timestamp)} na ${escapeHtml(sentRecord.recipient)}">🟢 Wysłano (${escapeHtml(sentRecord.timestamp.split(' ')[0])})</span>`
        : `<span class="badge-status pending" style="background:transparent; color:var(--muted); border-color:var(--line);">⚪ Gotowy</span>`;

      return `
        <tr>
          <td>${typeBadge}</td>
          <td>${docDisplay}</td>
          <td><span class="badge" style="font-weight:700;">#${d.orderId || '—'}</span></td>
          <td style="font-size:12px;"><strong>${escapeHtml(d.buyerName)}</strong></td>
          <td style="font-size:12px; font-family:monospace; color:var(--muted);">${escapeHtml(d.email || '—')}</td>
          <td style="text-align:right; font-variant-numeric:tabular-nums;">${amtDisplay}</td>
          <td style="font-size:11px; color:var(--muted);">${dt}</td>
          <td>${statusDisplay}</td>
          <td style="text-align:right; white-space:nowrap;">
            <button type="button" class="btn-mini btn-dispatch-send"
              data-type="${d.type}"
              data-id="${d.id}"
              data-order-id="${d.orderId || ''}"
              data-doc-num="${escapeHtml(d.docNumber)}"
              data-buyer="${escapeHtml(d.buyerName)}"
              data-email="${escapeHtml(d.email)}"
              data-amount="${escapeHtml(String(d.amount || ''))}"
              title="${isSent ? 'Wyślij ponownie na e-mail' : 'Wyślij dokument na e-mail klienta'}">
              ✉️ ${isSent ? (state.language === 'en' ? 'Resend' : 'Wyślij pon.') : (state.language === 'en' ? 'Send email' : 'Wyślij e-mail')}
            </button>
            <a href="${d.pdfUrl}" target="_blank" download class="btn-mini" style="margin-left:4px;" title="Pobierz PDF">📥 PDF</a>
          </td>
        </tr>
      `;
    }).join('');

    tbody.querySelectorAll('.btn-dispatch-send').forEach(btn => {
      btn.onclick = () => {
        openDispatchModal({
          type: btn.dataset.type,
          id: btn.dataset.id,
          orderId: btn.dataset.orderId,
          docNumber: btn.dataset.docNum,
          buyerName: btn.dataset.buyer,
          email: btn.dataset.email,
          amount: btn.dataset.amount
        });
      };
    });
  }

  async function loadInventory(force = false) {
    const tbody = $('#inventory-tbody');
    if (!tbody) return;
    if (force || !cachedInventory.length) {
      tbody.innerHTML = `<tr><td colspan="8" class="table-empty">${state.language === 'en' ? 'Loading stock...' : 'Ładowanie stanu magazynu...'}</td></tr>`;
      try {
        const res = await api('/api/data/inventory');
        cachedInventory = res.inventory || [];
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="8" class="table-empty error">⚠️ ${escapeHtml(err.message)} <button class="ghost-button btn-xs" style="margin-left:8px; vertical-align:middle;" onclick="window.minitechReloadDataTab()">🔄 ${state.language === 'en' ? 'Retry' : 'Ponów próbę'}</button></td></tr>`;
        return;
      }
    }
    renderInventoryTable();
  }

  function renderInventoryTable() {
    const tbody = $('#inventory-tbody');
    if (!tbody) return;
    const query = ($('#inventory-search-input')?.value || '').toLowerCase().trim();
    let list = cachedInventory;
    if (query) {
      list = list.filter(i => (i.name || '').toLowerCase().includes(query) || (i.sku || '').toLowerCase().includes(query) || (i.category || '').toLowerCase().includes(query));
    }
    const badge = $('#inventory-count-badge');
    if (badge) badge.textContent = `${list.length} ${state.language === 'en' ? 'items' : 'pozycji'}`;
    if (!list.length) {
      tbody.innerHTML = `<tr><td colspan="8" class="table-empty">${state.language === 'en' ? 'No inventory items found.' : 'Brak pozycji magazynowych.'}</td></tr>`;
      return;
    }

    // Sort inventory
    list = [...list].sort((a, b) => {
      if (inventorySortField === 'id') {
        const idA = Number(a.product_id || a.productId || a.id) || 0;
        const idB = Number(b.product_id || b.productId || b.id) || 0;
        return inventorySortDir === 'asc' ? idA - idB : idB - idA;
      }
      if (inventorySortField === 'name') {
        const nA = String(a.name || '').toLowerCase();
        const nB = String(b.name || '').toLowerCase();
        return inventorySortDir === 'asc' ? nA.localeCompare(nB) : nB.localeCompare(nA);
      }
      if (inventorySortField === 'sku') {
        const sA = String(a.sku || '').toLowerCase();
        const sB = String(b.sku || '').toLowerCase();
        return inventorySortDir === 'asc' ? sA.localeCompare(sB) : sB.localeCompare(sA);
      }
      if (inventorySortField === 'category') {
        const cA = String(a.category || '').toLowerCase();
        const cB = String(b.category || '').toLowerCase();
        return inventorySortDir === 'asc' ? cA.localeCompare(cB) : cB.localeCompare(cA);
      }
      if (inventorySortField === 'available') {
        const avA = Number(a.available_quantity != null ? a.available_quantity : a.availableQuantity ?? 0);
        const avB = Number(b.available_quantity != null ? b.available_quantity : b.availableQuantity ?? 0);
        return inventorySortDir === 'asc' ? avA - avB : avB - avA;
      }
      if (inventorySortField === 'reserved') {
        const rA = Number(a.reserved_quantity != null ? a.reserved_quantity : a.reservedQuantity ?? 0);
        const rB = Number(b.reserved_quantity != null ? b.reserved_quantity : b.reservedQuantity ?? 0);
        return inventorySortDir === 'asc' ? rA - rB : rB - rA;
      }
      if (inventorySortField === 'price') {
        const pA = Number(a.price ?? 0);
        const pB = Number(b.price ?? 0);
        return inventorySortDir === 'asc' ? pA - pB : pB - pA;
      }
      return 0;
    });

    updateTableSortHeaders('#inventory-table', inventorySortField, inventorySortDir);

    tbody.innerHTML = list.map(item => {
      const pid = item.product_id || item.productId || item.id;
      const name = item.name || `Produkt #${pid}`;
      const sku = item.sku || '—';
      const cat = item.category || '—';
      const avail = item.available_quantity != null ? item.available_quantity : item.availableQuantity ?? 0;
      const res = item.reserved_quantity != null ? item.reserved_quantity : item.reservedQuantity ?? 0;
      const price = item.price ? `${Number(item.price).toFixed(2)} PLN` : '—';
      const lowStock = avail < 5;
      return `
        <tr>
          <td><strong>#${pid}</strong></td>
          <td><strong>${escapeHtml(name)}</strong></td>
          <td><code>${escapeHtml(sku)}</code></td>
          <td><span style="font-size:11px; color:var(--muted);">${escapeHtml(cat)}</span></td>
          <td><strong style="color: ${lowStock ? 'var(--danger)' : 'var(--green)'};">${avail} szt.</strong></td>
          <td style="color:var(--muted);">${res} szt.</td>
          <td>${escapeHtml(price)}</td>
          <td>
            <div class="restock-btn-group">
              <button class="btn-mini restock-btn" data-product-id="${pid}" data-qty="10" title="Dodaj 10 sztuk">+10</button>
              <button class="btn-mini restock-btn" data-product-id="${pid}" data-qty="50" title="Dodaj 50 sztuk">+50</button>
            </div>
          </td>
        </tr>
      `;
    }).join('');

    $$('.restock-btn', tbody).forEach(btn => {
      btn.onclick = async () => {
        const prodId = parseInt(btn.dataset.productId, 10);
        const qty = parseInt(btn.dataset.qty, 10);
        btn.disabled = true;
        try {
          await api('/api/data/inventory/restock', { method: 'POST', body: JSON.stringify({ productId: prodId, quantity: qty }) });
          toast(state.language === 'en' ? `Stock replenished (+${qty} pcs)` : `Doładowano stan magazynowy (+${qty} szt.)`);
          await loadInventory(true);
        } catch (err) {
          toast(err.message, true);
        } finally {
          btn.disabled = false;
        }
      };
    });
  }

  async function loadCustomers(force = false) {
    const tbody = $('#customers-tbody');
    if (!tbody) return;
    if (force || !cachedCustomers.length) {
      tbody.innerHTML = `<tr><td colspan="6" class="table-empty">${state.language === 'en' ? 'Loading customers...' : 'Ładowanie kont klientów...'}</td></tr>`;
      try {
        const res = await api('/api/data/customers');
        cachedCustomers = res.customers || [];
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="6" class="table-empty error">⚠️ ${escapeHtml(err.message)} <button class="ghost-button btn-xs" style="margin-left:8px; vertical-align:middle;" onclick="window.minitechReloadDataTab()">🔄 ${state.language === 'en' ? 'Retry' : 'Ponów próbę'}</button></td></tr>`;
        return;
      }
    }
    renderCustomersTable();
  }

  function renderCustomersTable() {
    const tbody = $('#customers-tbody');
    if (!tbody) return;
    const query = ($('#customers-search-input')?.value || '').toLowerCase().trim();
    let list = cachedCustomers;
    if (query) {
      list = list.filter(c => (c.username || '').toLowerCase().includes(query) || (c.email || '').toLowerCase().includes(query) || (c.first_name || '').toLowerCase().includes(query) || (c.last_name || '').toLowerCase().includes(query));
    }
    if (!list.length) {
      tbody.innerHTML = `<tr><td colspan="6" class="table-empty">${state.language === 'en' ? 'No customers found.' : 'Brak zarejestrowanych klientów.'}</td></tr>`;
      return;
    }

    // Sort customers
    list = [...list].sort((a, b) => {
      if (customersSortField === 'id') {
        const idA = Number(a.id) || 0;
        const idB = Number(b.id) || 0;
        return customersSortDir === 'asc' ? idA - idB : idB - idA;
      }
      if (customersSortField === 'username') {
        const uA = String(a.username || '').toLowerCase();
        const uB = String(b.username || '').toLowerCase();
        return customersSortDir === 'asc' ? uA.localeCompare(uB) : uB.localeCompare(uA);
      }
      if (customersSortField === 'email') {
        const eA = String(a.email || '').toLowerCase();
        const eB = String(b.email || '').toLowerCase();
        return customersSortDir === 'asc' ? eA.localeCompare(eB) : eB.localeCompare(eA);
      }
      if (customersSortField === 'name') {
        const nA = `${a.first_name || ''} ${a.last_name || ''}`.trim().toLowerCase();
        const nB = `${b.first_name || ''} ${b.last_name || ''}`.trim().toLowerCase();
        return customersSortDir === 'asc' ? nA.localeCompare(nB) : nB.localeCompare(nA);
      }
      if (customersSortField === 'balance') {
        const bA = Number(a.balance ?? 0);
        const bB = Number(b.balance ?? 0);
        return customersSortDir === 'asc' ? bA - bB : bB - bA;
      }
      if (customersSortField === 'date') {
        const dA = a.created_at ? new Date(a.created_at).getTime() : 0;
        const dB = b.created_at ? new Date(b.created_at).getTime() : 0;
        return customersSortDir === 'asc' ? dA - dB : dB - dA;
      }
      return 0;
    });

    updateTableSortHeaders('#customers-table', customersSortField, customersSortDir);

    tbody.innerHTML = list.map(c => {
      const cid = c.id;
      const uname = c.username || '—';
      const email = c.email || '—';
      const name = `${c.first_name || ''} ${c.last_name || ''}`.trim() || '—';
      const bal = c.balance != null ? `${Number(c.balance).toFixed(2)} PLN` : '—';
      const dt = c.created_at ? new Date(c.created_at).toLocaleDateString(state.language === 'en' ? 'en-US' : 'pl-PL') : '—';
      return `
        <tr>
          <td><strong>#${cid}</strong></td>
          <td><strong>${escapeHtml(uname)}</strong></td>
          <td><a href="mailto:${escapeHtml(email)}" style="color:#38bdf8;">${escapeHtml(email)}</a></td>
          <td>${escapeHtml(name)}</td>
          <td><strong>${escapeHtml(bal)}</strong></td>
          <td style="font-size:11px; color:var(--muted);">${escapeHtml(dt)}</td>
        </tr>
      `;
    }).join('');
  }

  /* ==========================================================================
     2. Events View (Redis Stream, Outbox, DLQ, Trace)
     ========================================================================== */
  let eventSubTab = 'stream';
  let activeRedisStream = 'payment-events';
  let cachedStream = [];
  let cachedOutbox = [];
  let cachedDlq = [];
  let eventsViewSetupDone = false;

  function setupEventsViewOnce() {
    if (eventsViewSetupDone) return;
    eventsViewSetupDone = true;

    $$('.sub-tab-btn[data-eventtab]').forEach(btn => {
      btn.onclick = () => {
        eventSubTab = btn.dataset.eventtab;
        $$('.sub-tab-btn[data-eventtab]').forEach(b => b.classList.toggle('active', b === btn));
        $$('#view-events .subpane').forEach(p => p.classList.toggle('active', p.id === `events-pane-${eventSubTab}`));
        loadActiveEventTab();
      };
    });

    $$('.stream-pill[data-stream]').forEach(pill => {
      pill.onclick = () => {
        activeRedisStream = pill.dataset.stream;
        $$('.stream-pill[data-stream]').forEach(p => p.classList.toggle('active', p === pill));
        const badge = $('#active-stream-badge');
        if (badge) badge.textContent = activeRedisStream;
        loadStream(true);
      };
    });

    const refBtn = $('#events-refresh-btn');
    if (refBtn) refBtn.onclick = () => loadActiveEventTab(true);

    const traceBtn = $('#trace-search-btn');
    if (traceBtn) traceBtn.onclick = () => runTrace();
    const traceInput = $('#trace-cid-input');
    if (traceInput) traceInput.onkeydown = (e) => { if (e.key === 'Enter') runTrace(); };
  }

  function loadActiveEventTab(force = false) {
    if (eventSubTab === 'stream') loadStream(force);
    else if (eventSubTab === 'outbox') loadOutbox(force);
    else if (eventSubTab === 'dlq') loadDlq(force);
  }

  async function loadEventsView() {
    setupEventsViewOnce();
    loadActiveEventTab();
  }

  async function loadStream(force = false) {
    const tbody = $('#stream-tbody');
    if (!tbody) return;
    if (force || !cachedStream.length) {
      tbody.innerHTML = `<tr><td colspan="6" class="table-empty">${state.language === 'en' ? `Reading Redis stream ${activeRedisStream}...` : `Odczytywanie strumienia Redis ${activeRedisStream}...`}</td></tr>`;
      try {
        const res = await api(`/api/events/stream?stream=${encodeURIComponent(activeRedisStream)}`);
        cachedStream = res.events || [];
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="6" class="table-empty error">${escapeHtml(err.message)}</td></tr>`;
        return;
      }
    }
    renderStreamTable();
  }

  function renderStreamTable() {
    const tbody = $('#stream-tbody');
    if (!tbody) return;
    if (!cachedStream.length) {
      tbody.innerHTML = `<tr><td colspan="6" class="table-empty">${state.language === 'en' ? `No events in ${activeRedisStream} stream yet.` : `Brak zdarzeń w strumieniu ${activeRedisStream}. Złóż zamówienie w sklepie, aby wygenerować zdarzenia.`}</td></tr>`;
      return;
    }
    tbody.innerHTML = cachedStream.map(entry => {
      const id = entry.id;
      const ts = entry.timestamp || '—';
      const d = entry.data || {};
      const evType = d.eventType || d.type || (d._bootstrap ? 'BOOTSTRAP' : 'Event');
      const orderId = d.orderId || '—';
      let amt = '—';
      if (d.amount) {
        amt = `${d.amount} PLN`;
      } else if (d.grossAmount) {
        amt = `${d.grossAmount} PLN`;
      } else if (d.correctionGross) {
        const delta = Number(d.correctionGross) || 0;
        const sign = delta > 0 ? '+' : '';
        const finalStr = d.finalGross ? ` (po: ${d.finalGross} PLN)` : '';
        amt = `${sign}${d.correctionGross} PLN${finalStr}`;
      }
      const payloadStr = Object.entries(d).map(([k, v]) => `${k}=${v}`).join(', ');
      let badgeCls = 'published';
      if (d.eventType === 'PAYMENT_SUCCEEDED') badgeCls = 'paid';
      else if (d.eventType === 'INVOICE_ISSUED') badgeCls = 'completed';
      else if (d.eventType === 'INVOICE_CORRECTED') badgeCls = 'warning';
      else if (d._bootstrap) badgeCls = 'pending';
      return `
        <tr>
          <td><code style="font-size:11px;color:var(--primary);">${escapeHtml(id)}</code></td>
          <td style="font-size:11px; color:var(--muted);">${escapeHtml(ts)}</td>
          <td><span class="badge-status ${badgeCls}">${escapeHtml(evType)}</span></td>
          <td>${orderId !== '—' ? `<span class="badge" style="font-weight:700;">#${escapeHtml(orderId)}</span>` : '<span style="color:var(--muted);">—</span>'}</td>
          <td style="font-variant-numeric:tabular-nums;"><strong>${escapeHtml(amt)}</strong></td>
          <td style="font-size:11px; font-family:ui-monospace, monospace; color:var(--muted); max-width:360px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${escapeHtml(payloadStr)}">${escapeHtml(payloadStr)}</td>
        </tr>
      `;
    }).join('');
  }

  async function loadOutbox(force = false) {
    const tbody = $('#outbox-tbody');
    if (!tbody) return;
    if (force || !cachedOutbox.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="table-empty">${state.language === 'en' ? 'Loading outbox events...' : 'Odpytywanie payment_outbox_events...'}</td></tr>`;
      try {
        const res = await api('/api/events/outbox');
        cachedOutbox = res.outbox || [];
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="9" class="table-empty error">${escapeHtml(err.message)}</td></tr>`;
        return;
      }
    }
    renderOutboxTable();
  }

  function renderOutboxTable() {
    const tbody = $('#outbox-tbody');
    if (!tbody) return;
    if (!cachedOutbox.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="table-empty">${state.language === 'en' ? 'Outbox table is empty.' : 'Tabela Outbox jest pusta.'}</td></tr>`;
      return;
    }
    tbody.innerHTML = cachedOutbox.map(o => {
      const id = o.id;
      const evType = o.event_type || o.eventType || '—';
      const orderId = o.order_id || o.orderId || '—';
      const amt = o.amount ? `${o.amount} PLN` : '—';
      const st = (o.status || 'PENDING').toUpperCase();
      const stClass = st.toLowerCase();
      const attempts = o.attempts != null ? o.attempts : 0;
      const cid = o.correlation_id || o.correlationId || '';
      const err = o.last_error || o.lastError || '—';
      const dt = o.created_at ? new Date(o.created_at).toLocaleString(state.language === 'en' ? 'en-US' : 'pl-PL') : '—';
      const cidBtn = cid ? `<button class="btn-mini jump-trace-btn" data-cid="${escapeHtml(cid)}" title="${state.language === 'en' ? 'Trace this correlation ID' : 'Śledź ten identyfikator korelacji'}">🔍 ${escapeHtml(cid.slice(0, 8))}…</button>` : '—';
      return `
        <tr>
          <td><strong>#${id}</strong></td>
          <td><strong>${escapeHtml(evType)}</strong></td>
          <td>#${escapeHtml(orderId)}</td>
          <td>${escapeHtml(amt)}</td>
          <td><span class="badge-status ${stClass}">${escapeHtml(st)}</span></td>
          <td>${attempts}</td>
          <td>${cidBtn}</td>
          <td style="font-size:11px; color:${err !== '—' ? 'var(--danger)' : 'var(--muted)'}; max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${escapeHtml(err)}">${escapeHtml(err)}</td>
          <td style="font-size:11px; color:var(--muted);">${escapeHtml(dt)}</td>
        </tr>
      `;
    }).join('');

    $$('.jump-trace-btn', tbody).forEach(b => {
      b.onclick = () => {
        const cid = b.dataset.cid;
        const traceTabBtn = $('#events-tab-trace-btn');
        if (traceTabBtn) traceTabBtn.click();
        const inp = $('#trace-cid-input');
        if (inp) inp.value = cid;
        runTrace();
      };
    });
  }

  async function loadDlq(force = false) {
    const tbody = $('#dlq-tbody');
    if (!tbody) return;
    if (force || !cachedDlq.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="table-empty">${state.language === 'en' ? 'Loading Dead Letter Queue...' : 'Ładowanie kolejki martwych powiadomień (DLQ)...'}</td></tr>`;
      try {
        const res = await api('/api/events/dlq');
        cachedDlq = res.dlq || [];
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="9" class="table-empty error">${escapeHtml(err.message)}</td></tr>`;
        return;
      }
    }
    renderDlqTable();
  }

  function renderDlqTable() {
    const tbody = $('#dlq-tbody');
    if (!tbody) return;
    if (!cachedDlq.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="table-empty" style="color:var(--green);">🎉 ${state.language === 'en' ? 'Dead Letter Queue is empty. All notifications delivered successfully!' : 'Dead Letter Queue jest pusta. Wszystkie powiadomienia dostarczono pomyślnie!'}</td></tr>`;
      return;
    }
    tbody.innerHTML = cachedDlq.map(d => {
      const id = d.id;
      const evType = d.event_type || d.eventType || '—';
      const recip = d.recipient || '—';
      const chan = d.channel || 'EMAIL';
      const subj = d.subject || '—';
      const reason = d.failure_reason || d.failureReason || '—';
      const st = (d.status || 'FAILED').toUpperCase();
      const attempts = d.attempt_count != null ? d.attempt_count : (d.attempts ?? 1);
      const isReplayed = st === 'RESOLVED' || st === 'REPLAYED';
      const replayBtn = isReplayed 
        ? `<span style="color:var(--green); font-size:11px;">✓ Ponowiono</span>`
        : `<button class="btn-mini replay-dlq-btn" data-dlq-id="${id}" style="border-color:#38bdf8; color:#38bdf8;" title="Ponów wysyłkę z DLQ">⚡ Ponów</button>`;
      return `
        <tr>
          <td><strong>#${id}</strong></td>
          <td><strong>${escapeHtml(evType)}</strong></td>
          <td><code>${escapeHtml(recip)}</code></td>
          <td><span style="font-size:11px; color:var(--muted);">${escapeHtml(chan)}</span></td>
          <td style="max-width:180px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${escapeHtml(subj)}">${escapeHtml(subj)}</td>
          <td style="color:var(--danger); font-size:11px; max-width:220px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${escapeHtml(reason)}">${escapeHtml(reason)}</td>
          <td><span class="badge-status ${st.toLowerCase()}">${escapeHtml(st)}</span></td>
          <td>${attempts}</td>
          <td>${replayBtn}</td>
        </tr>
      `;
    }).join('');

    $$('.replay-dlq-btn', tbody).forEach(btn => {
      btn.onclick = async () => {
        const dlqId = btn.dataset.dlqId;
        btn.disabled = true;
        try {
          await api('/api/events/dlq/replay', { method: 'POST', body: JSON.stringify({ id: dlqId }) });
          toast(state.language === 'en' ? `Replayed DLQ #${dlqId}` : `Ponowiono wpis DLQ #${dlqId}`);
          await loadDlq(true);
        } catch (err) {
          toast(err.message, true);
        } finally {
          btn.disabled = false;
        }
      };
    });
  }

  async function runTrace() {
    const input = $('#trace-cid-input');
    const container = $('#trace-timeline-container');
    if (!input || !container) return;
    const cid = input.value.trim();
    if (!cid) {
      toast(state.language === 'en' ? 'Please enter a correlation ID.' : 'Wprowadź identyfikator X-Correlation-Id.', true);
      return;
    }
    container.innerHTML = `<div class="timeline-empty-state"><span class="timeline-empty-icon">⏳</span><h3>${state.language === 'en' ? 'Searching logs across services...' : 'Przeszukiwanie logów mikroserwisów...'}</h3><p>Identyfikator: <code>${escapeHtml(cid)}</code></p></div>`;
    try {
      const res = await api(`/api/trace?correlationId=${encodeURIComponent(cid)}`);
      const events = res.timeline || [];
      if (!events.length) {
        container.innerHTML = `
          <div class="timeline-empty-state">
            <span class="timeline-empty-icon">🔍</span>
            <h3>${state.language === 'en' ? 'No log entries found for this Correlation ID.' : 'Nie znaleziono wpisów w logach dla podanego Correlation ID.'}</h3>
            <p>${state.language === 'en' ? 'Ensure services are running or place an order via the store or App Verify to generate logs.' : 'Upewnij się, że serwisy działają lub złóż zamówienie w sklepie, aby wygenerować logi.'}</p>
          </div>
        `;
        return;
      }
      container.innerHTML = `
        <div style="margin-bottom: 20px; display: flex; justify-content: space-between; align-items: center;">
          <div>
            <strong>X-Correlation-Id:</strong> <code>${escapeHtml(cid)}</code>
          </div>
          <span class="toolbar-badge">${events.length} ${state.language === 'en' ? 'events detected' : 'zarejestrowanych zdarzeń'}</span>
        </div>
        <div class="timeline-flow">
          ${events.map(ev => {
            const srv = ev.service || 'UNKNOWN';
            const lvl = (ev.level || 'INFO').toLowerCase();
            const ts = ev.timestamp || '—';
            const msg = ev.message || '';
            return `
              <div class="timeline-item ${lvl}">
                <div class="timeline-item-meta">
                  <span class="timeline-srv-name">${escapeHtml(srv)} <span class="level-pill ${lvl}">${lvl.toUpperCase()}</span></span>
                  <span class="timeline-time">${escapeHtml(ts)}</span>
                </div>
                <div class="timeline-msg">${escapeHtml(msg)}</div>
              </div>
            `;
          }).join('')}
        </div>
      `;
    } catch (err) {
      container.innerHTML = `<div class="timeline-empty-state error"><span class="timeline-empty-icon">⚠️</span><h3>Błąd wyszukiwania śladu</h3><p>${escapeHtml(err.message)}</p></div>`;
    }
  }

  /* ==========================================================================
     3. Chaos View (Failure Simulator)
     ========================================================================== */
  let chaosViewSetupDone = false;

  function setupChaosViewOnce() {
    if (chaosViewSetupDone) return;
    chaosViewSetupDone = true;

    $$('.chaos-toggle-btn').forEach(btn => {
      btn.onclick = async () => {
        const target = btn.dataset.target;
        const action = btn.dataset.action;
        btn.disabled = true;
        try {
          await api('/api/chaos/toggle', { method: 'POST', body: JSON.stringify({ target, action }) });
          toast(state.language === 'en' ? `Chaos action applied: ${action} on ${target}` : `Zastosowano akcję chaosu: ${action} na ${target}`);
          await loadChaosView();
        } catch (err) {
          toast(err.message, true);
        } finally {
          btn.disabled = false;
        }
      };
    });

    const restoreAllBtn = $('#chaos-restore-all-btn');
    if (restoreAllBtn) {
      restoreAllBtn.onclick = async () => {
        restoreAllBtn.disabled = true;
        try {
          await api('/api/chaos/toggle', { method: 'POST', body: JSON.stringify({ target: 'all', action: 'restore_all' }) });
          toast(state.language === 'en' ? 'All services restored.' : 'Przywrócono wszystkie serwisy.');
          await loadChaosView();
        } catch (err) {
          toast(err.message, true);
        } finally {
          restoreAllBtn.disabled = false;
        }
      };
    }
  }

  async function loadChaosView() {
    setupChaosViewOnce();
    try {
      const res = await api('/api/chaos/status');
      const targets = res.targets || {};
      for (const [key, info] of Object.entries(targets)) {
        const badge = $(`#chaos-badge-${key}`);
        if (badge) {
          if (info.paused) {
            badge.className = 'chaos-status-badge warning';
            badge.textContent = state.language === 'en' ? 'PAUSED (Chaos)' : 'WSTRZYMANY (Chaos)';
          } else if (info.running) {
            badge.className = 'chaos-status-badge running';
            badge.textContent = state.language === 'en' ? 'RUNNING (Healthy)' : 'DZIAŁA (Zdrowy)';
          } else {
            badge.className = 'chaos-status-badge stopped';
            badge.textContent = state.language === 'en' ? 'STOPPED' : 'ZATRZYMANY';
          }
        }
      }
    } catch (err) {
      console.warn('Chaos status check failed:', err);
    }
  }

  /* ==========================================================================
     4. Command Palette (Ctrl+K)
     ========================================================================== */
  function setupCommandPalette() {
    const dialog = $('#command-palette-dialog');
    const input = $('#cmd-palette-input');
    const list = $('#cmd-palette-list');
    const trigger = $('#cmd-palette-btn');
    if (!dialog || !input || !list) return;

    let selectedIdx = 0;
    let filteredItems = [];

    const getPaletteItems = () => {
      const isEn = state.language === 'en';
      const items = [
        { title: isEn ? 'View: Dashboard' : 'Widok: Pulpit operacyjny', category: isEn ? 'Navigation' : 'Nawigacja', icon: '◫', action: () => setView('dashboard') },
        { title: isEn ? 'View: Services & Endpoints' : 'Widok: Usługi i endpointy', category: isEn ? 'Navigation' : 'Nawigacja', icon: '●', action: () => setView('services') },
        { title: isEn ? 'View: Control Operations' : 'Widok: Operacje Centrali', category: isEn ? 'Navigation' : 'Nawigacja', icon: '⌘', action: () => setView('operations') },
        { title: isEn ? 'View: Business Data Inspector' : 'Widok: Eksplorator Danych Biznesowych (Zamówienia, Magazyn, Klienci)', category: isEn ? 'Data' : 'Dane', icon: '📊', action: () => setView('data') },
        { title: isEn ? 'View: Events & DLQ' : 'Widok: Zdarzenia, Kolejki i DLQ (Redis, Outbox, Trace)', category: isEn ? 'Events' : 'Zdarzenia', icon: '⚡', action: () => setView('events') },
        { title: isEn ? 'View: Chaos Engineering Simulator' : 'Widok: Symulator Awarii & Chaos Engineering', category: isEn ? 'Testing' : 'Testy', icon: '💥', action: () => setView('chaos') },
        { title: isEn ? 'View: Customer Chat' : 'Widok: Czat klientów sklepu', category: isEn ? 'Support' : 'Wsparcie', icon: '◌', action: () => setView('chat') },
        { title: isEn ? 'View: AI Architecture Assistant' : 'Widok: Asystent AI Architektury', category: isEn ? 'AI' : 'Asystent', icon: '✦', action: () => setView('assistant') },
        { title: isEn ? 'View: Quality Gates & Tests' : 'Widok: Testy i bramki jakości', category: isEn ? 'Testing' : 'Testy', icon: '✓', action: () => setView('tests') },
        { title: isEn ? 'View: Jobs & Logs' : 'Widok: Zadania i logi', category: isEn ? 'Runtime' : 'Logi', icon: '≡', action: () => setView('jobs') },
        { title: isEn ? 'View: WSL Terminal' : 'Widok: Terminal interaktywny WSL', category: isEn ? 'CLI' : 'Terminal', icon: '>_', action: () => setView('terminal') },
        { title: isEn ? 'View: About MiniTech Platform' : 'Widok: O platformie i architekturze', category: isEn ? 'Info' : 'Informacje', icon: 'ℹ', action: () => setView('about') },
        { title: isEn ? '1-Click Fresh Start' : 'Pełny start platformy (1-Click Fresh Start)', category: isEn ? 'Automation' : 'Automatyzacja', icon: '🚀', action: () => runActionById('fresh-start') },
        { title: isEn ? 'Seed Demo Data' : 'Zasil bazę danymi demonstracyjnymi (Seed Demo Data)', category: isEn ? 'Data' : 'Dane', icon: '📦', action: () => runActionById('seed-demo-data') },
        { title: isEn ? 'Doctor & Auto-Healing' : 'Doktor & Automatyczna naprawa (Doctor Fix)', category: isEn ? 'Diagnostics' : 'Diagnostyka', icon: '🚑', action: () => runActionById('doctor-fix') },
        { title: isEn ? 'Start Infrastructure (Databases + Redis)' : 'Uruchom infrastrukturę (Bazy danych + Redis)', category: isEn ? 'Infra' : 'Infrastruktura', icon: '◫', action: () => runActionById('infra-up') },
        { title: isEn ? 'Start All Microservices (Docker)' : 'Uruchom wszystkie mikroserwisy (Docker Apps Up)', category: isEn ? 'Apps' : 'Aplikacje', icon: '▶', action: () => runActionById('docker-apps-up') },
        { title: isEn ? 'Stop All Microservices' : 'Zatrzymaj mikroserwisy (Docker Apps Down)', category: isEn ? 'Apps' : 'Aplikacje', icon: '⏹', action: () => runActionById('docker-apps-down') },
        { title: isEn ? 'Start Store Frontend (:18070)' : 'Uruchom sklep internetowy (Frontend Up)', category: isEn ? 'Frontend' : 'Frontend', icon: '⌁', action: () => runActionById('frontend-up') },
        { title: isEn ? 'Run App Verify (Purchase Flow E2E)' : 'Wykonaj test ścieżki zakupowej (App Verify E2E)', category: isEn ? 'Testing' : 'Testy', icon: '✓', action: () => runActionById('app-verify') },
        { title: isEn ? 'Edit DEV Secrets (SOPS)' : 'Edytuj sekrety DEV w skarbcu SOPS', category: isEn ? 'Secrets' : 'Sekrety', icon: '🔒', action: () => runActionById('secrets-edit-dev') },
        { title: isEn ? 'Open Swagger UI (:18080)' : 'Otwórz Swagger UI (:18080)', category: isEn ? 'Docs' : 'Dokumentacja', icon: '📖', action: () => window.open('http://127.0.0.1:18080/swagger-ui.html', '_blank', 'noopener,noreferrer') },
        { title: isEn ? 'Copy Diagnostic Report' : 'Wygeneruj i skopiuj raport diagnostyczny', category: isEn ? 'Diagnostics' : 'Raport', icon: '📋', action: () => $('#copy-report-btn')?.click() },
      ];

      (state.actions || []).forEach(act => {
        if (!items.some(it => it.title.includes(act.title))) {
          items.push({
            title: act.title,
            category: act.group || 'Operacje',
            icon: '⌘',
            action: () => runActionById(act.id),
          });
        }
      });
      return items;
    };

    const renderPalette = () => {
      const q = input.value.toLowerCase().trim();
      const all = getPaletteItems();
      filteredItems = q ? all.filter(it => it.title.toLowerCase().includes(q) || it.category.toLowerCase().includes(q)) : all;
      if (selectedIdx >= filteredItems.length) selectedIdx = Math.max(0, filteredItems.length - 1);

      if (!filteredItems.length) {
        list.innerHTML = `<div style="text-align:center; padding:20px; color:var(--muted); font-size:13px;">${state.language === 'en' ? 'No matching commands or views.' : 'Nie znaleziono pasujących poleceń ani widoków.'}</div>`;
        return;
      }

      list.innerHTML = filteredItems.map((item, idx) => `
        <div class="cmd-palette-item ${idx === selectedIdx ? 'active' : ''}" data-idx="${idx}">
          <div class="cmd-palette-item-left">
            <span class="cmd-palette-item-icon">${item.icon}</span>
            <span class="cmd-palette-item-title">${escapeHtml(item.title)}</span>
          </div>
          <span class="cmd-palette-item-category">${escapeHtml(item.category)}</span>
        </div>
      `).join('');

      $$('.cmd-palette-item', list).forEach(node => {
        node.onclick = () => {
          const idx = parseInt(node.dataset.idx, 10);
          executePaletteItem(idx);
        };
        node.onmouseenter = () => {
          selectedIdx = parseInt(node.dataset.idx, 10);
          $$('.cmd-palette-item', list).forEach((n, i) => n.classList.toggle('active', i === selectedIdx));
        };
      });

      const activeEl = $('.cmd-palette-item.active', list);
      if (activeEl) activeEl.scrollIntoView({ block: 'nearest' });
    };

    const openPalette = () => {
      dialog.showModal();
      input.value = '';
      selectedIdx = 0;
      renderPalette();
      input.focus();
    };

    const closePalette = () => {
      dialog.close();
    };

    const executePaletteItem = (idx) => {
      const item = filteredItems[idx];
      if (item && item.action) {
        closePalette();
        item.action();
      }
    };

    if (trigger) trigger.onclick = () => openPalette();

    window.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        if (dialog.open) closePalette();
        else openPalette();
      }
    });

    dialog.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        closePalette();
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (selectedIdx < filteredItems.length - 1) selectedIdx++;
        else selectedIdx = 0;
        renderPalette();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (selectedIdx > 0) selectedIdx--;
        else selectedIdx = filteredItems.length - 1;
        renderPalette();
      } else if (e.key === 'Enter') {
        e.preventDefault();
        executePaletteItem(selectedIdx);
      }
    });

    input.addEventListener('input', () => {
      selectedIdx = 0;
      renderPalette();
    });

    dialog.addEventListener('click', (e) => {
      const rect = dialog.getBoundingClientRect();
      const isInDialog = (rect.top <= e.clientY && e.clientY <= rect.top + rect.height && rect.left <= e.clientX && e.clientX <= rect.left + rect.width);
      if (!isInDialog) closePalette();
    });
  }

  function runActionById(actionId) {
    openAction(actionId);
  }

  function bind() {
    document.addEventListener('click', event => {
      const choice = event.target.closest('[data-choice-value]');
      if (choice) {
        event.preventDefault();
        selectChoice(choice);
        return;
      }
      const chip = event.target.closest('.ai-chip');
      if (chip) {
        event.preventDefault();
        sendAiMessage(chip.dataset.prompt);
        return;
      }
      const fixBtn = event.target.closest('[data-fix-action]');
      if (fixBtn) {
        event.preventDefault();
        triggerDirectAction(fixBtn.dataset.fixAction);
        return;
      }
      const askAiBtn = event.target.closest('[data-ask-ai-job]');
      if (askAiBtn) {
        event.preventDefault();
        const jobId = askAiBtn.dataset.askAiJob;
        const job = state.jobs.find(j => j.id === jobId) || (state.selectedJob === jobId ? { id: jobId, title: $('#drawer-title')?.textContent || 'Zadanie', exitCode: 1, output: $('#job-output')?.textContent || '' } : null);
        if (job) {
          setView('assistant');
          const lang = state.language || 'pl';
          const promptText = lang === 'en'
            ? `An error occurred during job "${job.title}".\n\nRecent logs:\n\`\`\`\n${(job.output || '').slice(-1200)}\n\`\`\`\n\nPlease analyze what caused this error and provide step-by-step instructions to fix it.`
            : `Wystąpił błąd w zadaniu „${job.title}”.\n\nOstatnie logi:\n\`\`\`\n${(job.output || '').slice(-1200)}\n\`\`\`\n\nProszę o analizę przyczyny błędu i podanie kroków do jego naprawy.`;
          const aiInput = $('#ai-input');
          if (aiInput) {
            aiInput.value = promptText;
            aiInput.focus();
            sendAiMessage(promptText);
          }
        }
        return;
      }
      const extBtn = event.target.closest('[data-external-app]');
      if (extBtn) {
        openExternalAppDialog(extBtn.dataset.externalApp);
        return;
      }
      const view = event.target.closest('[data-view]'); if (view) setView(view.dataset.view);
      const target = event.target.closest('[data-view-target]'); if (target) setView(target.dataset.viewTarget);
      const actionButton = event.target.closest('button[data-action]');
      if (actionButton) {
        const actionId = actionButton.dataset.action;
        const presetService = actionButton.dataset.service;
        if (presetService) {
          openAction(actionId, { service: presetService });
        } else {
          openAction(actionId);
        }
      }
      const job = event.target.closest('[data-job]'); if (job) selectJob(job.dataset.job);
      const chat = event.target.closest('[data-chat]'); if (chat) { state.selectedChat = state.chatMessages.find(item => String(item.id) === chat.dataset.chat); renderConversation(); loadChat(); }
    });
    $('#action-form').addEventListener('submit', submitAction); $$('#action-dialog .modal-close,#action-dialog .modal-cancel').forEach(button => button.addEventListener('click', () => $('#action-dialog').close()));
    $('#secret-form').addEventListener('submit', submitSecretForm); $$('#secret-dialog .modal-close,#secret-dialog .modal-cancel').forEach(button => button.addEventListener('click', () => $('#secret-dialog').close()));
    $('#ai-settings-form')?.addEventListener('submit', saveAiSettings); $$('#ai-settings-dialog .modal-close,#ai-settings-dialog .modal-cancel').forEach(button => button.addEventListener('click', () => $('#ai-settings-dialog').close()));
    $('#external-link-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      $('#external-link-dialog')?.close();
      if (currentExternalAppUrl) {
        window.open(currentExternalAppUrl, '_blank', 'noopener,noreferrer');
      }
    });
    $$('#external-link-dialog .modal-close, #external-link-dialog .modal-cancel').forEach(button => {
      button.addEventListener('click', () => $('#external-link-dialog')?.close());
    });

    const deltaInput = $('#correction-delta');
    if (deltaInput) {
      deltaInput.addEventListener('input', () => {
        const orig = parseFloat($('#correction-orig-gross')?.value) || 0;
        const delta = parseFloat(deltaInput.value) || 0;
        const finalGross = orig + delta;
        const deltaEl = $('#calc-delta-text');
        const finalEl = $('#calc-final-text');
        if (deltaEl) {
          deltaEl.textContent = `${delta >= 0 ? '+' : ''}${delta.toFixed(2)} PLN`;
          deltaEl.style.color = delta < 0 ? '#f87171' : '#4ade80';
        }
        if (finalEl) {
          finalEl.textContent = `${finalGross.toFixed(2)} PLN`;
          finalEl.style.color = finalGross < 0 ? '#f87171' : '#4ade80';
        }
      });
    }

    const corrForm = $('#correction-form');
    if (corrForm) {
      corrForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const invId = $('#correction-invoice-id')?.value;
        const reason = ($('#correction-reason')?.value || '').trim();
        const deltaVal = parseFloat($('#correction-delta')?.value);
        const errEl = $('#correction-error');
        const submitBtn = $('#correction-submit-btn');

        if (!reason) {
          if (errEl) { errEl.textContent = state.language === 'en' ? 'Reason is required.' : 'Wymagana jest przyczyna korekty.'; errEl.hidden = false; }
          return;
        }
        if (isNaN(deltaVal)) {
          if (errEl) { errEl.textContent = state.language === 'en' ? 'Valid amount is required.' : 'Wymagana jest prawidłowa kwota.'; errEl.hidden = false; }
          return;
        }

        try {
          if (submitBtn) {
            submitBtn.disabled = true;
            submitBtn.textContent = state.language === 'en' ? 'Issuing...' : 'Wystawianie...';
          }
          const res = await api('/api/data/invoices/correct', {
            method: 'POST',
            body: JSON.stringify({
              invoiceId: parseInt(invId, 10),
              reason: reason,
              correctionGross: deltaVal
            })
          });

          $('#correction-dialog')?.close();
          toast(state.language === 'en' 
            ? `Correction ${res.correction?.correctionNumber || ''} issued successfully!` 
            : `Wystawiono fakturę korygującą ${res.correction?.correctionNumber || ''}!`);
          playSound('success');
          await loadInvoices(true);
        } catch (err) {
          if (errEl) {
            errEl.textContent = err.message || (state.language === 'en' ? 'Failed to issue correction.' : 'Błąd podczas wystawiania korekty.');
            errEl.hidden = false;
          }
        } finally {
          if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.textContent = state.language === 'en' ? 'Issue correction' : 'Wystaw korektę';
          }
        }
      });
    }
    $$('#correction-dialog .modal-close, #correction-dialog .modal-cancel').forEach(button => {
      button.addEventListener('click', () => $('#correction-dialog')?.close());
    });

    const dispatchForm = $('#dispatch-form');
    if (dispatchForm) {
      dispatchForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const errEl = $('#dispatch-error');
        if (errEl) { errEl.textContent = ''; errEl.hidden = true; }

        const docType = $('#dispatch-doc-type')?.value || 'INVOICE';
        const docId = $('#dispatch-doc-id')?.value;
        const orderId = $('#dispatch-order-id')?.value;
        const docNumber = $('#dispatch-doc-number')?.value;
        const buyerName = $('#dispatch-buyer-name')?.value;
        const recipient = $('#dispatch-recipient')?.value?.trim();
        const subject = $('#dispatch-subject')?.value?.trim();
        const customMessage = $('#dispatch-custom-message')?.value?.trim() || '';

        if (!recipient) {
          if (errEl) {
            errEl.textContent = state.language === 'en' ? 'Recipient email is required.' : 'Wymagany jest adres e-mail odbiorcy.';
            errEl.hidden = false;
          }
          return;
        }

        const submitBtn = $('#dispatch-submit-btn');
        try {
          if (submitBtn) {
            submitBtn.disabled = true;
            submitBtn.textContent = state.language === 'en' ? 'Sending...' : 'Wysyłanie...';
          }
          const res = await api('/api/data/invoices/send-email', {
            method: 'POST',
            body: JSON.stringify({
              documentType: docType,
              documentId: docId,
              orderId: orderId,
              documentNumber: docNumber,
              buyerName: buyerName,
              recipient: recipient,
              subject: subject,
              customMessage: customMessage
            })
          });

          $('#dispatch-dialog')?.close();
          toast(state.language === 'en'
            ? `Document ${docNumber} sent to ${recipient}! Check Mailpit.`
            : `Dokument ${docNumber} wysłany do ${recipient}! Sprawdź w Mailpit.`);
          playSound('success');

          try {
            const histRes = await api('/api/data/invoices/dispatch-history');
            cachedDispatchHistory = histRes.history || [];
          } catch (_) {}

          if (dataSubTab === 'dispatch') {
            renderDispatchTable();
          } else if (dataSubTab === 'invoices') {
            await loadInvoices(true);
          }
        } catch (err) {
          if (errEl) {
            errEl.textContent = err.message || (state.language === 'en' ? 'Failed to send document.' : 'Błąd podczas wysyłki dokumentu.');
            errEl.hidden = false;
          }
        } finally {
          if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.textContent = state.language === 'en' ? '📤 Send email' : '📤 Wyślij e-mail';
          }
        }
      });
    }
    $$('#dispatch-dialog .modal-close, #dispatch-dialog .modal-cancel').forEach(button => {
      button.addEventListener('click', () => $('#dispatch-dialog')?.close());
    });
    $('#ai-settings-button')?.addEventListener('click', openAiSettings);
    $('#ai-chat-form')?.addEventListener('submit', (e) => { e.preventDefault(); sendAiMessage(); });
    $('#ai-clear-btn')?.addEventListener('click', clearAiChat);
    $('#ai-input')?.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendAiMessage();
      }
    });
    $('#ai-provider-select')?.addEventListener('change', (e) => {
      const val = e.target.value;
      const modelInp = $('#ai-model-input');
      if (modelInp) {
        if (val === 'codex') modelInp.placeholder = 'gpt-4o / codex';
        else if (val === 'cursor') modelInp.placeholder = 'cursor-small / default';
        else modelInp.placeholder = 'antigravity-expert / gemini-2.5-flash';
      }
    });
    $('#operation-search').addEventListener('input', filterOperations); $('#operation-group').addEventListener('change', filterOperations);
    $('#health-refresh').addEventListener('click', loadHealth); $('#refresh-button').addEventListener('click', () => { loadHealth(); loadJobs(); if ($('#view-chat').classList.contains('active')) loadChat(); if ($('#view-assistant').classList.contains('active')) loadAiConfig(); });
    $('#copy-report-btn')?.addEventListener('click', openDiagnosticReportDialog);
    $('#report-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      $('#report-dialog')?.close();
    });
    $$('#report-dialog .modal-close, #report-dialog .modal-cancel, #report-cancel-btn').forEach(button => {
      button.addEventListener('click', () => $('#report-dialog')?.close());
    });
    $('#report-copy-md')?.addEventListener('click', async () => {
      if (currentReportMarkdown) {
        await navigator.clipboard.writeText(currentReportMarkdown);
        toast(state.language === 'en' ? '📋 Report copied to clipboard!' : '📋 Raport skopiowano do schowka!');
        playSound('success');
      }
    });
    const langBtn = $('#lang-switch-button'); if (langBtn) langBtn.addEventListener('click', toggleLanguage);
    $('#jobs-refresh').addEventListener('click', loadJobs); $('#chat-refresh').addEventListener('click', loadChat); $('#conversation-detail').addEventListener('submit', sendReply);
    $('#drawer-open').addEventListener('click', () => { setView('jobs'); if (state.selectedJob) selectJob(state.selectedJob); });
    $('#terminal-start').addEventListener('click', startTerminal); $('#terminal-form').addEventListener('submit', sendTerminal);
  }

  async function init() {
    bind();
    $('#local-address').textContent = location.host;
    try {
      const data = await api('/api/bootstrap');
      state.token = data.token;
      state.profile = data.profile;
      state.language = data.language || 'pl';
      state.actions = data.actions;
      $('#profile-value').textContent = data.profile;
      if (data.links?.store) $('#store-link').href = data.links.store;
      if (data.links?.mailpit) $('#mailpit-link').href = data.links.mailpit;
      applyLanguage(state.language);
      renderOperations();
      setupCommandPalette();
      const hash = location.hash.slice(1); if (['dashboard','services','operations','data','events','chaos','chat','assistant','tests','jobs','terminal','about'].includes(hash)) setView(hash);
      await Promise.all([loadHealth(), loadJobs(), loadSecrets(), loadAiConfig()]);
      startDynamicPolling();
    } catch (error) { toast(state.language === 'en' ? `Dashboard failed to load configuration: ${error.message}` : `Panel nie może pobrać konfiguracji: ${error.message}`, true); }
  }
  init();
})();
