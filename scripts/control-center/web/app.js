(() => {
  const state = { token: '', profile: 'dev', actions: [], health: [], jobs: [], secretProfiles: {}, secretFields: [], secretSetupFields: [], selectedJob: null, selectedChat: null, terminalId: null, terminalTimer: null, jobTimer: null };
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const escapeHtml = (value) => String(value ?? '').replace(/[&<>'"]/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[char]));
  const formatTime = (seconds) => seconds ? new Date(seconds * 1000).toLocaleString('pl-PL') : '—';

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

  function setView(name) {
    $$('.view').forEach(node => node.classList.toggle('active', node.id === `view-${name}`));
    $$('.nav-item').forEach(node => node.classList.toggle('active', node.dataset.view === name));
    const titles = {dashboard:'Centrum operacyjne', services:'Usługi i endpointy', operations:'Operacje Centrali', chat:'Obsługa klienta', tests:'Testy i jakość', jobs:'Zadania i logi', terminal:'Terminal WSL'};
    $('#view-title').textContent = titles[name] || 'MiniTech Control';
    location.hash = name;
    if (name === 'chat') loadChat();
    if (name === 'jobs') loadJobs();
  }

  function renderHealth() {
    const online = state.health.filter(item => item.online).length;
    $('#metric-online').textContent = `${online}/${state.health.length || 15}`;
    $('#service-preview').innerHTML = state.health.slice(0, 7).map(item => `<div class="service-preview-row"><span class="state-light ${item.online ? 'online' : ''}"></span><strong>${escapeHtml(item.name)}</strong><small>${item.online ? `${item.latencyMs} ms` : 'OFFLINE'}</small></div>`).join('') || '<div class="empty-state">Brak danych.</div>';
    $('#service-grid').innerHTML = state.health.map(item => `<article class="service-card"><header><h3>${escapeHtml(item.name)} Service</h3><span class="status-tag ${item.online ? 'online' : ''}">${item.online ? 'UP' : 'OFFLINE'}</span></header><p>127.0.0.1:${item.port} · ${item.online ? `${item.latencyMs} ms` : 'brak odpowiedzi'}</p></article>`).join('');
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
      ['dev', 'test'].forEach(profile => {
        const quickAction = $(`button[data-action="secrets-edit-${profile}"], button[data-action="secrets-setup-${profile}"]`);
        if (quickAction) {
          const ready = state.secretProfiles[profile]?.file;
          quickAction.dataset.action = ready ? `secrets-edit-${profile}` : `secrets-setup-${profile}`;
          $('strong', quickAction).textContent = ready ? `Sekrety ${profile.toUpperCase()}` : `Utwórz sekrety ${profile.toUpperCase()}`;
          $('small', quickAction).textContent = ready ? 'Bezpieczna edycja SOPS' : 'Pierwsza konfiguracja';
        }
      });
      if (state.actions.length) renderOperations();
    } catch (error) { toast(error.message, true); }
  }

  function renderActionCard(item) {
    const setup = item.kind === 'secret-setup';
    const secret = setup || item.kind === 'secret-edit';
    const alreadyCreated = setup && state.secretProfiles[item.profile]?.file;
    const buttonLabel = setup ? (alreadyCreated ? 'Plik już istnieje' : 'Utwórz plik') : item.kind === 'secret-edit' ? 'Otwórz edytor' : item.fields.length ? 'Ustaw i uruchom' : 'Uruchom';
    const disabled = alreadyCreated ? 'disabled' : '';
    return `<article class="operation-card ${item.danger ? 'danger' : ''} ${secret ? 'secret-operation-card' : ''}" data-search="${escapeHtml(`${item.title} ${item.description} ${item.group}`.toLowerCase())}"><h4>${escapeHtml(item.title)}</h4><p>${escapeHtml(item.description)}</p><button data-action="${item.id}" ${disabled}>${buttonLabel}</button></article>`;
  }

  function renderOperations() {
    const groups = [...new Set(state.actions.filter(item => item.group !== 'Testy').map(item => item.group))];
    $('#operation-group').innerHTML = '<option value="">Wszystkie grupy</option>' + groups.map(group => `<option>${escapeHtml(group)}</option>`).join('');
    $('#operation-groups').innerHTML = groups.map(group => `<section class="operation-section" data-group="${escapeHtml(group)}"><h3>${escapeHtml(group)}</h3><div class="operation-grid">${state.actions.filter(item => item.group === group).map(renderActionCard).join('')}</div></section>`).join('');
    const tests = state.actions.filter(item => item.group === 'Testy');
    $('#test-cards').innerHTML = tests.map(item => `<article class="test-card"><span class="test-icon">✓</span><h3>${escapeHtml(item.title)}</h3><p>${escapeHtml(item.description)}</p><button class="secondary-button" data-action="${item.id}">Uruchom test</button></article>`).join('');
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
    const placeholder = field.type === 'password' ? 'Nowa wartość · puste = bez zmiany' : 'Puste = bez zmiany';
    let control;
    if (field.type === 'select') {
      control = `<select name="${escapeHtml(field.name)}"><option value="">— pozostaw bez zmian —</option>${field.options.map(value => `<option value="${escapeHtml(value)}">${escapeHtml(value)}</option>`).join('')}</select>`;
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
      const placeholder = field.type === 'password' ? 'Wartość zostanie zaszyfrowana' : 'Opcjonalne — użyj wartości domyślnej';
      control = `<input name="${escapeHtml(field.name)}" type="${escapeHtml(field.type || 'text')}" value="${escapeHtml(defaultValue)}" ${attrs} autocomplete="new-password" placeholder="${escapeHtml(placeholder)}" maxlength="4096">`;
    }
    const note = field.note ? `<small class="field-note">${escapeHtml(field.note)}</small>` : '';
    return `<label class="field-label">${escapeHtml(field.label)}${control}${note}</label>`;
  }

  function openSecretEditor(profile) {
    const status = state.secretProfiles[profile] || {};
    const fields = state.secretFields;
    if (!fields.length) return toast('Nie udało się pobrać pól sekretów.', true);
    const dialog = $('#secret-dialog');
    dialog.dataset.mode = 'edit';
    dialog.dataset.profile = profile;
    $('#secret-dialog-title').textContent = `Sekrety profilu ${profile.toUpperCase()}`;
    $('#secret-dialog-status').textContent = `Klucz age: ${status.key ? 'OK' : 'BRAK'} · plik zaszyfrowany: ${status.file ? 'OK' : 'BRAK'}`;
    $('#secret-dialog-warning').textContent = status.key && status.file
      ? 'Aktualne wartości są niewidoczne. Wypełnij tylko pola, które chcesz zmienić; puste pola pozostaną bez zmian.'
      : 'Brak kompletnego skarbca tego profilu. Najpierw użyj inicjalizacji i kreatora sekretów w Operacjach albo Terminalu WSL.';
    const sections = [...new Set(fields.map(field => field.section || 'Sekrety'))];
    $('#secret-fields').innerHTML = sections.map(section => `<fieldset class="secret-fieldset"><legend>${escapeHtml(section)}</legend>${fields.filter(field => field.section === section).map(secretFieldHtml).join('')}</fieldset>`).join('');
    $('#secret-confirm').checked = false;
    $('#secret-confirm-label').textContent = 'Rozumiem, że zapis zmienia zaszyfrowane sekrety wybranego profilu.';
    $('#secret-submit').textContent = 'Zapisz zaszyfrowane';
    $('#secret-submit').disabled = !(status.key && status.file);
    dialog.showModal();
  }

  function openSecretSetup(profile) {
    const status = state.secretProfiles[profile] || {};
    const fields = state.secretSetupFields;
    if (!fields.length) return toast('Nie udało się pobrać pól pierwszej konfiguracji.', true);
    if (status.file) return toast(`Plik sekretów profilu ${profile.toUpperCase()} już istnieje. Użyj edytora.`, true);
    const dialog = $('#secret-dialog');
    dialog.dataset.mode = 'setup';
    dialog.dataset.profile = profile;
    $('#secret-dialog-title').textContent = `Utwórz sekrety ${profile.toUpperCase()}`;
    $('#secret-dialog-status').textContent = `Klucz age: ${status.key ? 'OK' : 'zostanie utworzony'} · plik zaszyfrowany: zostanie utworzony`;
    $('#secret-dialog-warning').textContent = 'To jest pierwsza konfiguracja. Dla istniejących wolumenów PostgreSQL wpisz dokładnie dotychczasowe hasła; dla nowego środowiska możesz ustawić własne silne wartości. Hasła nie trafią do logu zadania.';
    const sections = [...new Set(fields.map(field => field.section || 'Sekrety'))];
    $('#secret-fields').innerHTML = sections.map(section => `<fieldset class="secret-fieldset"><legend>${escapeHtml(section)}</legend>${fields.filter(field => field.section === section).map(secretSetupFieldHtml).join('')}</fieldset>`).join('');
    $('#secret-confirm').checked = false;
    $('#secret-confirm-label').textContent = 'Rozumiem, że utworzenie zapisze zaszyfrowane sekrety wybranego profilu.';
    $('#secret-submit').textContent = 'Utwórz i zaszyfruj';
    $('#secret-submit').disabled = false;
    dialog.showModal();
  }

  function openAction(actionId) {
    const item = state.actions.find(entry => entry.id === actionId); if (!item) return;
    if (item.kind === 'secret-edit') return openSecretEditor(item.profile);
    if (item.kind === 'secret-setup') return openSecretSetup(item.profile);
    const dialog = $('#action-dialog'); dialog.dataset.action = item.id;
    $('#dialog-group').textContent = item.group; $('#dialog-title').textContent = item.title; $('#dialog-description').textContent = item.description;
    $('#dialog-fields').innerHTML = item.fields.map(fieldHtml).join('') || '<p class="muted">Operacja nie wymaga dodatkowych parametrów.</p>';
    const danger = $('#danger-confirm'); danger.hidden = !item.danger; $('input', danger).checked = false;
    dialog.showModal();
  }

  async function submitAction(event) {
    event.preventDefault(); const dialog = $('#action-dialog'); const item = state.actions.find(entry => entry.id === dialog.dataset.action);
    const values = Object.fromEntries(new FormData(event.currentTarget).entries()); const confirmed = !item.danger || $('input', $('#danger-confirm')).checked;
    if (item.danger && !confirmed) return toast('Potwierdź operację przed uruchomieniem.', true);
    try {
      const job = await api('/api/jobs', {method:'POST', body:JSON.stringify({action:item.id, values, confirmed})});
      dialog.close(); state.selectedJob = job.id; showDrawer(job); pollJob(job.id); toast(`Uruchomiono: ${item.title}`);
    } catch (error) { toast(error.message, true); }
  }

  async function submitSecretEdit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const dialog = $('#secret-dialog');
    if (!$('#secret-confirm').checked) return toast('Potwierdź zapis zmienionych sekretów.', true);
    const values = Object.fromEntries([...new FormData(form).entries()].filter(([name, value]) => name !== 'confirm' && value !== ''));
    try {
      const result = await api('/api/secrets/update', {method: 'POST', body: JSON.stringify({profile: dialog.dataset.profile, values, confirmed: true})});
      dialog.close();
      await loadSecrets();
      toast(result.message || 'Sekrety zapisano zaszyfrowane.');
    } catch (error) { toast(error.message, true); }
  }

  async function submitSecretSetup(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const dialog = $('#secret-dialog');
    if (!$('#secret-confirm').checked) return toast('Potwierdź utworzenie zaszyfrowanego pliku sekretów.', true);
    const values = Object.fromEntries([...new FormData(form).entries()].filter(([name]) => name !== 'confirm'));
    try {
      const result = await api('/api/secrets/setup', {method: 'POST', body: JSON.stringify({profile: dialog.dataset.profile, values, confirmed: true})});
      dialog.close();
      await loadSecrets();
      toast(result.message || 'Sekrety utworzono zaszyfrowane.');
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
    try {
      const job = await api(`/api/jobs/${id}`); showDrawer(job);
      if (state.selectedJob === id && $('#view-jobs').classList.contains('active')) $('#job-output').textContent = job.output || 'Oczekiwanie na dane…';
      if (job.status === 'running' || job.status === 'queued') state.jobTimer = setTimeout(() => pollJob(id), 1000);
      else { toast(job.status === 'success' ? `Zakończono: ${job.title}` : `Błąd: ${job.title}`, job.status !== 'success'); loadJobs(); loadHealth(); }
    } catch (error) { toast(error.message, true); }
  }

  async function loadJobs() {
    try {
      const data = await api('/api/jobs'); state.jobs = data.jobs;
      const html = data.jobs.map(job => `<button class="job-row ${state.selectedJob === job.id ? 'active' : ''}" data-job="${job.id}"><span class="job-status ${job.status}">${job.status}</span><strong>${escapeHtml(job.title)}</strong><small>${formatTime(job.createdAt)} · ${escapeHtml(job.command)}</small></button>`).join('');
      $('#job-history').className = 'job-history'; $('#job-history').innerHTML = html || '<div class="empty-state">Brak zadań.</div>';
      $('#recent-jobs').className = 'job-list'; $('#recent-jobs').innerHTML = html ? data.jobs.slice(0,4).map(job => `<div class="service-preview-row"><span class="state-light ${job.status === 'success' ? 'online' : ''}"></span><strong>${escapeHtml(job.title)}</strong><small>${escapeHtml(job.status)}</small></div>`).join('') : '<div class="empty-state">Nie uruchomiono jeszcze żadnego zadania.</div>';
    } catch (error) { toast(error.message, true); }
  }

  async function selectJob(id) {
    state.selectedJob = id; $$('.job-row').forEach(row => row.classList.toggle('active', row.dataset.job === id));
    try { const job = await api(`/api/jobs/${id}`); $('#job-output').textContent = job.output || 'Brak treści logu.'; if (job.status === 'running') pollJob(id); }
    catch (error) { toast(error.message, true); }
  }

  function statusLabel(value) { return value === 'NEW' ? 'Oczekuje' : value === 'ANSWERED' ? 'Odpowiedziano' : value || '—'; }
  async function loadChat() {
    try {
      const data = await api('/api/chat/messages'); const messages = data.messages || []; const pending = messages.filter(item => item.status === 'NEW').length;
      $('#chat-badge').hidden = pending === 0; $('#chat-badge').textContent = pending;
      $('#conversation-list').innerHTML = messages.map(item => `<button class="conversation-card ${state.selectedChat?.id === item.id ? 'active' : ''}" data-chat="${item.id}"><header><strong>${escapeHtml(item.customerName)}</strong><time>${escapeHtml(statusLabel(item.status))}</time></header><p>${escapeHtml(item.subject || 'Pytanie klienta')}</p><p>${escapeHtml(item.message)}</p></button>`).join('') || '<div class="empty-state">Brak wiadomości.</div>';
      if (state.selectedChat) { state.selectedChat = messages.find(item => item.id === state.selectedChat.id) || null; renderConversation(); }
      state.chatMessages = messages;
    } catch (error) { $('#conversation-list').innerHTML = `<div class="empty-state">${escapeHtml(error.message)}</div>`; }
  }

  function renderConversation() {
    const item = state.selectedChat; if (!item) return;
    $('#conversation-detail').innerHTML = `<p class="eyebrow accent">ROZMOWA #${item.id}</p><h3>${escapeHtml(item.subject || 'Pytanie klienta')}</h3><p class="customer-meta">${escapeHtml(item.customerName)} · ${escapeHtml(item.customerEmail)}</p><div class="message-bubble">${escapeHtml(item.message)}</div>${item.replyMessage ? `<div class="message-bubble reply"><strong>MiniTech Store</strong><br>${escapeHtml(item.replyMessage)}</div>` : ''}<form class="reply-form"><label class="field-label">Odpowiedź<textarea name="message" required maxlength="4000" placeholder="Napisz jasną i pomocną odpowiedź dla klienta…"></textarea></label><button class="primary-button">Wyślij odpowiedź</button></form>`;
  }

  async function sendReply(event) {
    if (!event.target.matches('.reply-form')) return; event.preventDefault(); const message = new FormData(event.target).get('message');
    try { await api(`/api/chat/${state.selectedChat.id}/reply`, {method:'POST', body:JSON.stringify({message})}); toast('Odpowiedź wysłana do klienta i Notification Service.'); await loadChat(); }
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
      // The dialog also stores the currently selected action in data-action.
      // Only real launcher buttons may open/rebuild a form; otherwise every
      // click inside the dialog resets its inputs and reopens a cancelled modal.
      const actionButton = event.target.closest('button[data-action]');
      if (actionButton) openAction(actionButton.dataset.action);
      const job = event.target.closest('[data-job]'); if (job) selectJob(job.dataset.job);
      const chat = event.target.closest('[data-chat]'); if (chat) { state.selectedChat = state.chatMessages.find(item => String(item.id) === chat.dataset.chat); renderConversation(); loadChat(); }
    });
    $('#action-form').addEventListener('submit', submitAction); $$('#action-dialog .modal-close,#action-dialog .modal-cancel').forEach(button => button.addEventListener('click', () => $('#action-dialog').close()));
    $('#secret-form').addEventListener('submit', submitSecretForm); $$('#secret-dialog .modal-close,#secret-dialog .modal-cancel').forEach(button => button.addEventListener('click', () => $('#secret-dialog').close()));
    $('#operation-search').addEventListener('input', filterOperations); $('#operation-group').addEventListener('change', filterOperations);
    $('#health-refresh').addEventListener('click', loadHealth); $('#refresh-button').addEventListener('click', () => { loadHealth(); loadJobs(); if ($('#view-chat').classList.contains('active')) loadChat(); });
    $('#jobs-refresh').addEventListener('click', loadJobs); $('#chat-refresh').addEventListener('click', loadChat); $('#conversation-detail').addEventListener('submit', sendReply);
    $('#drawer-open').addEventListener('click', () => { setView('jobs'); if (state.selectedJob) selectJob(state.selectedJob); });
    $('#terminal-start').addEventListener('click', startTerminal); $('#terminal-form').addEventListener('submit', sendTerminal);
  }

  async function init() {
    bind();
    $('#local-address').textContent = location.host;
    try {
      const data = await api('/api/bootstrap'); state.token = data.token; state.profile = data.profile; state.actions = data.actions; $('#profile-value').textContent = data.profile; $('#store-link').href = data.links.store; renderOperations();
      const hash = location.hash.slice(1); if (['dashboard','services','operations','chat','tests','jobs','terminal'].includes(hash)) setView(hash);
      await Promise.all([loadHealth(), loadJobs(), loadSecrets()]);
    } catch (error) { toast(`Panel nie może pobrać konfiguracji: ${error.message}`, true); }
  }
  init();
})();
