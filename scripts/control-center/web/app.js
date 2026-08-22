(() => {
  const state = { token: '', profile: 'dev', language: 'pl', actions: [], health: [], jobs: [], secretProfiles: {}, secretFields: [], secretSetupFields: [], selectedJob: null, selectedChat: null, terminalId: null, terminalTimer: null, jobTimer: null };
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
        chat: 'Obsługa klienta',
        tests: 'Testy i jakość',
        jobs: 'Zadania i logi',
        terminal: 'Terminal WSL',
      },
      nav: {
        dashboard: 'Pulpit',
        services: 'Usługi',
        operations: 'Operacje',
        chat: 'Czat klientów',
        tests: 'Testy',
        jobs: 'Zadania i logi',
        terminal: 'Terminal WSL',
        localOnly: 'Tylko lokalnie',
      },
      topbar: {
        storeLink: 'Otwórz sklep ↗',
        refresh: 'Odśwież dane',
        profile: 'Profil',
        langBtnTitle: 'Zmień język (PL / EN)',
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
        desc: 'Health check ogranicza się do statusu UP/OFFLINE; szczegóły Actuator nie są ujawniane.',
        btnRefresh: 'Sprawdź ponownie',
      },
      operations: {
        eyebrow: 'BIAŁA LISTA POLECEŃ',
        title: 'Operacje Centrali',
        desc: 'Każda operacja wywołuje istniejący skrypt Bash. Polecenia zmieniające dane wymagają dodatkowego potwierdzenia.',
        searchPlaceholder: 'Szukaj operacji, np. backup, JWT, faktura…',
        allGroups: 'Wszystkie grupy',
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
      tests: {
        eyebrow: 'QUALITY GATE',
        title: 'Testy i jakość',
        desc: 'Od szybkiego smoke do pełnego E2E i regresji Playwright w wielu przeglądarkach.',
        btnRun: 'Uruchom test',
      },
      jobs: {
        eyebrow: 'HISTORIA LOKALNA',
        title: 'Zadania i logi',
        desc: 'Logi są zapisywane w prywatnym katalogu gitIgnoreOwn i nie trafią do GitHuba.',
        btnRefresh: 'Odśwież',
        emptyHistory: 'Brak zadań.',
        selectJobPrompt: 'Wybierz zadanie, aby zobaczyć log.',
        waitingOutput: 'Oczekiwanie na dane…',
        noLogContent: 'Brak treści logu.',
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
        chat: 'Customer Support',
        tests: 'Quality Gates & Tests',
        jobs: 'Jobs & Logs',
        terminal: 'WSL Terminal',
      },
      nav: {
        dashboard: 'Dashboard',
        services: 'Services',
        operations: 'Operations',
        chat: 'Customer Chat',
        tests: 'Tests',
        jobs: 'Jobs & Logs',
        terminal: 'WSL Terminal',
        localOnly: 'Local only',
      },
      topbar: {
        storeLink: 'Open store ↗',
        refresh: 'Refresh data',
        profile: 'Profile',
        langBtnTitle: 'Switch language (PL / EN)',
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
        desc: 'Health check reports UP/OFFLINE status; Actuator details are not exposed.',
        btnRefresh: 'Recheck health',
      },
      operations: {
        eyebrow: 'ALLOW-LISTED COMMANDS',
        title: 'Control Operations',
        desc: 'Each operation invokes an existing Bash script. Commands mutating data require confirmation.',
        searchPlaceholder: 'Search operations, e.g. backup, JWT, invoice…',
        allGroups: 'All groups',
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
      chat: dict.nav.chat,
      tests: dict.nav.tests,
      jobs: dict.nav.jobs,
      terminal: dict.nav.terminal,
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

    const storeLink = $('#store-link');
    if (storeLink) storeLink.textContent = dict.topbar.storeLink;
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
    if (metricCards[0]) $('small', metricCards[0]).textContent = d.metricOnline;
    if (metricCards[1]) $('small', metricCards[1]).textContent = d.metricDbs;
    if (metricCards[2]) $('small', metricCards[2]).textContent = d.metricBroker;
    if (metricCards[3]) $('small', metricCards[3]).textContent = `${state.profile.toUpperCase()} · ${d.metricMail}`;

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
    if (quickInfra) $('strong', quickInfra).textContent = d.quickInfra;
    const quickRun = $('button[data-action="app-run-all"]', $('#view-dashboard'));
    if (quickRun) { $('strong', quickRun).textContent = d.quickRunAll; $('small', quickRun).textContent = d.quickRunAllSub; }
    const quickFront = $('button[data-action="frontend-up"]', $('#view-dashboard'));
    if (quickFront) { $('strong', quickFront).textContent = d.quickFrontend; $('small', quickFront).textContent = d.quickFrontendSub; }
    const quickVerify = $('button[data-action="app-verify"]', $('#view-dashboard'));
    if (quickVerify) { $('strong', quickVerify).textContent = d.quickAppVerify; $('small', quickVerify).textContent = d.quickAppVerifySub; }

    const s = dict.services;
    const sEye = $('#view-services .section-intro .eyebrow'); if (sEye) sEye.textContent = s.eyebrow;
    const sH2 = $('#view-services .section-intro h2'); if (sH2) sH2.textContent = s.title;
    const sP = $('#view-services .section-intro p'); if (sP) sP.textContent = s.desc;
    const sRef = $('#health-refresh'); if (sRef) sRef.textContent = s.btnRefresh;

    const o = dict.operations;
    const oEye = $('#view-operations .section-intro .eyebrow'); if (oEye) oEye.textContent = o.eyebrow;
    const oH2 = $('#view-operations .section-intro h2'); if (oH2) oH2.textContent = o.title;
    const oP = $('#view-operations .section-intro p'); if (oP) oP.textContent = o.desc;
    const oSearch = $('#operation-search'); if (oSearch) oSearch.placeholder = o.searchPlaceholder;

    const c = dict.chat;
    const cEye = $('#view-chat .section-intro .eyebrow'); if (cEye) cEye.textContent = c.eyebrow;
    const cH2 = $('#view-chat .section-intro h2'); if (cH2) cH2.textContent = c.title;
    const cP = $('#view-chat .section-intro p'); if (cP) cP.textContent = c.desc;
    const cRef = $('#chat-refresh'); if (cRef) cRef.textContent = c.btnRefresh;

    const t = dict.tests;
    const tEye = $('#view-tests .section-intro .eyebrow'); if (tEye) tEye.textContent = t.eyebrow;
    const tH2 = $('#view-tests .section-intro h2'); if (tH2) tH2.textContent = t.title;
    const tP = $('#view-tests .section-intro p'); if (tP) tP.textContent = t.desc;

    const j = dict.jobs;
    const jEye = $('#view-jobs .section-intro .eyebrow'); if (jEye) jEye.textContent = j.eyebrow;
    const jH2 = $('#view-jobs .section-intro h2'); if (jH2) jH2.textContent = j.title;
    const jP = $('#view-jobs .section-intro p'); if (jP) jP.textContent = j.desc;
    const jRef = $('#jobs-refresh'); if (jRef) jRef.textContent = j.btnRefresh;

    const term = dict.terminal;
    const termEye = $('#view-terminal .section-intro .eyebrow'); if (termEye) termEye.textContent = term.eyebrow;
    const termH2 = $('#view-terminal .section-intro h2'); if (termH2) termH2.textContent = term.title;
    const termP = $('#view-terminal .section-intro p'); if (termP) termP.textContent = term.desc;
    const termStart = $('#terminal-start'); if (termStart) termStart.textContent = term.btnStart;
    const termInp = $('#terminal-input'); if (termInp) termInp.placeholder = term.inputPlaceholder;
    const termBtn = $('#terminal-form button'); if (termBtn) termBtn.textContent = term.btnSend;

    const dangerConfirm = $('#danger-confirm');
    if (dangerConfirm) dangerConfirm.innerHTML = `<input type="checkbox"> ${escapeHtml(dict.modals.dangerConfirm)}`;
    $$('.modal-cancel').forEach(b => b.textContent = dict.modals.btnCancel);
    const actionSubmit = $('#action-form button[type="submit"]');
    if (actionSubmit) actionSubmit.textContent = dict.modals.btnRun;
    const drawerOpen = $('#drawer-open');
    if (drawerOpen) drawerOpen.textContent = dict.drawer.showLog;
  }

  function setView(name) {
    $$('.view').forEach(node => node.classList.toggle('active', node.id === `view-${name}`));
    $$('.nav-item').forEach(node => node.classList.toggle('active', node.dataset.view === name));
    const dict = I18N[state.language] || I18N.pl;
    const viewTitle = $('#view-title');
    if (viewTitle) viewTitle.textContent = dict.viewTitles[name] || 'MiniTech Control';
    location.hash = name;
    if (name === 'chat') loadChat();
    if (name === 'jobs') loadJobs();
  }

  function renderHealth() {
    const dict = I18N[state.language] || I18N.pl;
    const online = state.health.filter(item => item.online).length;
    $('#metric-online').textContent = `${online}/${state.health.length || 15}`;
    $('#service-preview').innerHTML = state.health.slice(0, 7).map(item => `<div class="service-preview-row"><span class="state-light ${item.online ? 'online' : ''}"></span><strong>${escapeHtml(item.name)}</strong><small>${item.online ? `${item.latencyMs} ms` : 'OFFLINE'}</small></div>`).join('') || `<div class="empty-state">${escapeHtml(dict.dashboard.emptyData)}</div>`;
    $('#service-grid').innerHTML = state.health.map(item => `<article class="service-card"><header><h3>${escapeHtml(item.name)} Service</h3><span class="status-tag ${item.online ? 'online' : ''}">${item.online ? 'UP' : 'OFFLINE'}</span></header><p>127.0.0.1:${item.port} · ${item.online ? `${item.latencyMs} ms` : (state.language === 'en' ? 'no response' : 'brak odpowiedzi')}</p></article>`).join('');
  }

  async function loadHealth() {
    $('#health-refresh').disabled = true;
    try { const data = await api('/api/health'); state.health = data.services; renderHealth(); }
    catch (error) { toast(error.message, true); }
    finally { $('#health-refresh').disabled = false; }
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
          $('strong', quickAction).textContent = title;
          $('small', quickAction).textContent = subtitle;
        }
      });
      if (state.actions.length) renderOperations();
    } catch (error) { toast(error.message, true); }
  }

  function renderActionCard(item) {
    const dict = I18N[state.language] || I18N.pl;
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

  function openAction(actionId) {
    const dict = I18N[state.language] || I18N.pl;
    const item = state.actions.find(entry => entry.id === actionId); if (!item) return;
    if (item.kind === 'secret-edit') return openSecretEditor(item.profile);
    if (item.kind === 'secret-setup') return openSecretSetup(item.profile);
    const dialog = $('#action-dialog'); dialog.dataset.action = item.id;
    $('#dialog-group').textContent = item.group; $('#dialog-title').textContent = item.title; $('#dialog-description').textContent = item.description;
    $('#dialog-fields').innerHTML = item.fields.map(fieldHtml).join('') || `<p class="muted">${escapeHtml(dict.modals.noParams)}</p>`;
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

  function submitSecretForm(event) {
    if ($('#secret-dialog').dataset.mode === 'setup') return submitSecretSetup(event);
    return submitSecretEdit(event);
  }

  function showDrawer(job) {
    $('#job-drawer').classList.add('visible'); $('#drawer-title').textContent = job.title; $('#drawer-status').className = `status-dot ${job.status}`;
  }

  async function pollJob(id) {
    clearTimeout(state.jobTimer);
    const dict = I18N[state.language] || I18N.pl;
    try {
      const job = await api(`/api/jobs/${id}`); showDrawer(job);
      if (state.selectedJob === id && $('#view-jobs').classList.contains('active')) $('#job-output').textContent = job.output || dict.jobs.waitingOutput;
      if (job.status === 'running' || job.status === 'queued') state.jobTimer = setTimeout(() => pollJob(id), 1000);
      else {
        toast(job.status === 'success' ? `${dict.toasts.jobFinished}: ${job.title}` : `${dict.toasts.jobError}: ${job.title}`, job.status !== 'success');
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

  async function loadJobs() {
    const dict = I18N[state.language] || I18N.pl;
    try {
      const data = await api('/api/jobs'); state.jobs = data.jobs;
      const html = data.jobs.map(job => `<button class="job-row ${state.selectedJob === job.id ? 'active' : ''}" data-job="${job.id}"><span class="job-status ${job.status}">${job.status}</span><strong>${escapeHtml(job.title)}</strong><small>${formatTime(job.createdAt)} · ${escapeHtml(job.command)}</small></button>`).join('');
      $('#job-history').className = 'job-history'; $('#job-history').innerHTML = html || `<div class="empty-state">${escapeHtml(dict.jobs.emptyHistory)}</div>`;
      $('#recent-jobs').className = 'job-list'; $('#recent-jobs').innerHTML = html ? data.jobs.slice(0,4).map(job => `<div class="service-preview-row"><span class="state-light ${job.status === 'success' ? 'online' : ''}"></span><strong>${escapeHtml(job.title)}</strong><small>${escapeHtml(job.status)}</small></div>`).join('') : `<div class="empty-state">${escapeHtml(dict.dashboard.emptyJobs)}</div>`;
    } catch (error) { toast(error.message, true); }
  }

  async function selectJob(id) {
    const dict = I18N[state.language] || I18N.pl;
    state.selectedJob = id; $$('.job-row').forEach(row => row.classList.toggle('active', row.dataset.job === id));
    try { const job = await api(`/api/jobs/${id}`); $('#job-output').textContent = job.output || dict.jobs.noLogContent; if (job.status === 'running') pollJob(id); }
    catch (error) { toast(error.message, true); }
  }

  function statusLabel(value) {
    if (state.language === 'en') return value === 'NEW' ? 'Pending' : value === 'ANSWERED' ? 'Answered' : value || '—';
    return value === 'NEW' ? 'Oczekuje' : value === 'ANSWERED' ? 'Odpowiedziano' : value || '—';
  }

  async function loadChat() {
    const dict = I18N[state.language] || I18N.pl;
    try {
      const data = await api('/api/chat/messages'); const messages = data.messages || []; const pending = messages.filter(item => item.status === 'NEW').length;
      $('#chat-badge').hidden = pending === 0; $('#chat-badge').textContent = pending;
      $('#conversation-list').innerHTML = messages.map(item => `<button class="conversation-card ${state.selectedChat?.id === item.id ? 'active' : ''}" data-chat="${item.id}"><header><strong>${escapeHtml(item.customerName)}</strong><time>${escapeHtml(statusLabel(item.status))}</time></header><p>${escapeHtml(item.subject || (state.language === 'en' ? 'Customer inquiry' : 'Pytanie klienta'))}</p><p>${escapeHtml(item.message)}</p></button>`).join('') || `<div class="empty-state">${escapeHtml(dict.chat.noMessages)}</div>`;
      if (state.selectedChat) { state.selectedChat = messages.find(item => item.id === state.selectedChat.id) || null; renderConversation(); }
      state.chatMessages = messages;
    } catch (error) { $('#conversation-list').innerHTML = `<div class="empty-state">${escapeHtml(error.message)}</div>`; }
  }

  function renderConversation() {
    const item = state.selectedChat; if (!item) return;
    const dict = I18N[state.language] || I18N.pl;
    $('#conversation-detail').innerHTML = `<p class="eyebrow accent">${state.language === 'en' ? 'CONVERSATION' : 'ROZMOWA'} #${item.id}</p><h3>${escapeHtml(item.subject || (state.language === 'en' ? 'Customer inquiry' : 'Pytanie klienta'))}</h3><p class="customer-meta">${escapeHtml(item.customerName)} · ${escapeHtml(item.customerEmail)}</p><div class="message-bubble">${escapeHtml(item.message)}</div>${item.replyMessage ? `<div class="message-bubble reply"><strong>MiniTech Store</strong><br>${escapeHtml(item.replyMessage)}</div>` : ''}<form class="reply-form"><label class="field-label">${escapeHtml(dict.chat.replyFormLabel)}<textarea name="message" required maxlength="4000" placeholder="${escapeHtml(dict.chat.replyPlaceholder)}"></textarea></label><button class="primary-button">${escapeHtml(dict.chat.btnSendReply)}</button></form>`;
  }

  async function sendReply(event) {
    if (!event.target.matches('.reply-form')) return; event.preventDefault(); const message = new FormData(event.target).get('message');
    const dict = I18N[state.language] || I18N.pl;
    try { await api(`/api/chat/${state.selectedChat.id}/reply`, {method:'POST', body:JSON.stringify({message})}); toast(dict.chat.replySuccess); await loadChat(); }
    catch (error) { toast(error.message, true); }
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
      await loadSecrets();
      toast(state.language === 'en' ? 'Language switched to English.' : 'Język zmieniony na polski.');
    } catch (error) {
      toast(error.message, true);
    }
  }

  function bind() {
    document.addEventListener('click', event => {
      const choice = event.target.closest('[data-choice-value]');
      if (choice) {
        event.preventDefault();
        selectChoice(choice);
        return;
      }
      const view = event.target.closest('[data-view]'); if (view) setView(view.dataset.view);
      const target = event.target.closest('[data-view-target]'); if (target) setView(target.dataset.viewTarget);
      const actionButton = event.target.closest('button[data-action]');
      if (actionButton) openAction(actionButton.dataset.action);
      const job = event.target.closest('[data-job]'); if (job) selectJob(job.dataset.job);
      const chat = event.target.closest('[data-chat]'); if (chat) { state.selectedChat = state.chatMessages.find(item => String(item.id) === chat.dataset.chat); renderConversation(); loadChat(); }
    });
    $('#action-form').addEventListener('submit', submitAction); $$('#action-dialog .modal-close,#action-dialog .modal-cancel').forEach(button => button.addEventListener('click', () => $('#action-dialog').close()));
    $('#secret-form').addEventListener('submit', submitSecretForm); $$('#secret-dialog .modal-close,#secret-dialog .modal-cancel').forEach(button => button.addEventListener('click', () => $('#secret-dialog').close()));
    $('#operation-search').addEventListener('input', filterOperations); $('#operation-group').addEventListener('change', filterOperations);
    $('#health-refresh').addEventListener('click', loadHealth); $('#refresh-button').addEventListener('click', () => { loadHealth(); loadJobs(); if ($('#view-chat').classList.contains('active')) loadChat(); });
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
      $('#store-link').href = data.links.store;
      applyLanguage(state.language);
      renderOperations();
      const hash = location.hash.slice(1); if (['dashboard','services','operations','chat','tests','jobs','terminal'].includes(hash)) setView(hash);
      await Promise.all([loadHealth(), loadJobs(), loadSecrets()]);
    } catch (error) { toast(`Panel nie może pobrać konfiguracji: ${error.message}`, true); }
  }
  init();
})();
