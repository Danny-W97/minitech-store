/** CC command catalogue and dialog. HTTP and operation confirmation stay in the app. */
const views = [
    ['dashboard', '◫', 'pulpit dashboard'], ['services', '●', 'uslugi endpoints'],
    ['operations', '⌘', 'operacje operations'], ['data', '📊', 'zamowienia magazyn klienci orders inventory customers'],
    ['events', '⚡', 'redis outbox dlq trace'], ['chaos', '💥', 'awarie testing'],
    ['chat', '◌', 'czat rozmowy support'], ['assistant', '✦', 'asystent ai'],
    ['tests', '✓', 'testy quality'], ['jobs', '≡', 'zadania logi runtime'],
    ['terminal', '>_', 'cli wsl'], ['about', 'ℹ', 'architektura informacje']
];
const icons = {'fresh-start': '🚀', 'seed-demo-data': '📦', 'doctor-fix': '🚑', 'infra-up': '◫',
    'docker-apps-up': '▶', 'docker-apps-down': '⏹', 'frontend-up': '⌁', 'app-verify': '✓', 'secrets-edit-dev': '🔒'};
const swagger = 'http://127.0.0.1:18080/swagger-ui.html';
const normalize = value => String(value || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/ł/g, 'l');

export function buildPaletteItems({language = 'pl', actions = [], viewTitles = {}} = {}) {
    const en = language === 'en';
    const items = views.map(([target, icon, keywords]) => ({id: `view:${target}`, type: 'view', target, icon,
        title: `${en ? 'View' : 'Widok'}: ${viewTitles[target] || target}`, category: en ? 'Navigation' : 'Nawigacja', keywords}));
    const seen = new Set();
    for (const action of Array.isArray(actions) ? actions : []) {
        if (typeof action?.id !== 'string' || !action.id || typeof action.title !== 'string' || !action.title.trim() || seen.has(action.id)) continue;
        let type = 'action', target = action.id;
        if (action.externalUrl) {
            try {
                const url = new URL(action.externalUrl);
                if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) continue;
                type = 'external'; target = url.href;
            } catch {continue;}
        }
        seen.add(action.id);
        items.push({id: `action:${action.id}`, type, target, title: action.title,
            category: action.group || (en ? 'Operations' : 'Operacje'), icon: type === 'external' ? '📖' : Object.hasOwn(icons, action.id) ? icons[action.id] : '⌘',
            keywords: `${action.id} ${action.description || ''}`});
    }
    if (!items.some(item => item.type === 'external' && item.target === swagger)) items.push({id: 'external:swagger', type: 'external', target: swagger,
        title: en ? 'Open Swagger UI (:18080)' : 'Otwórz Swagger UI (:18080)', category: en ? 'Docs' : 'Dokumentacja', icon: '📖'});
    items.push({id: 'report:diagnostics', type: 'report', title: en ? 'Copy Diagnostic Report' : 'Wygeneruj i skopiuj raport diagnostyczny',
        category: en ? 'Diagnostics' : 'Raport', icon: '📋'});
    return items.map(item => ({...item, searchText: normalize(`${item.title} ${item.category} ${item.target || ''} ${item.keywords || ''}`)}));
}

export function filterPaletteItems(items, query) {
    const words = normalize(query).trim().split(/\s+/).filter(Boolean);
    return words.length ? items.filter(item => words.every(word => item.searchText.includes(word))) : items;
}

export function nextPaletteIndex(index, delta, count) {
    if (count < 1) return -1;
    if (index < 0) return delta < 0 ? count - 1 : 0;
    return ((index + delta) % count + count) % count;
}

export function createCommandPalette({document, eventTarget = document.defaultView, getLanguage = () => 'pl',
    getActions = () => [], getViewTitles = () => ({}), onView = () => {}, onAction = () => {},
    onExternal = () => {}, onReport = () => {}, onError = () => {}}) {
    const dialog = document.getElementById('command-palette-dialog');
    const input = document.getElementById('cmd-palette-input');
    const list = document.getElementById('cmd-palette-list');
    const trigger = document.getElementById('cmd-palette-btn');
    if (!dialog || !input || !list) return Object.freeze({open() {}, close() {}, refresh() {}, dispose() {}});
    let disposed = false, catalog = [], filtered = [], selected = -1, nodes = [], activeNode = null, opener = null;
    const listeners = [];
    const status = document.createElement('p');
    status.id = `${list.id}-status`; status.className = 'empty-state'; status.setAttribute('role', 'status'); status.hidden = true; list.after(status);
    input.setAttribute('role', 'combobox'); input.setAttribute('aria-autocomplete', 'list');
    input.setAttribute('aria-controls', list.id); input.setAttribute('aria-describedby', status.id); input.setAttribute('aria-expanded', 'false');
    list.setAttribute('role', 'listbox');
    trigger?.setAttribute('aria-haspopup', 'dialog'); trigger?.setAttribute('aria-controls', dialog.id); trigger?.setAttribute('aria-expanded', 'false');

    function listen(target, type, handler) {target?.addEventListener(type, handler); listeners.push([target, type, handler]);}
    function selectedState(index, scroll = true) {
        if (selected === index && activeNode === nodes[index]) return;
        activeNode?.classList.remove('active'); activeNode?.setAttribute('aria-selected', 'false');
        selected = index; activeNode = nodes[index] || null;
        if (activeNode) {
            activeNode.classList.add('active'); activeNode.setAttribute('aria-selected', 'true'); input.setAttribute('aria-activedescendant', activeNode.id);
            if (scroll) activeNode.scrollIntoView({block: 'nearest'});
        } else input.removeAttribute('aria-activedescendant');
    }
    function render() {
        const previous = filtered[selected]?.id;
        filtered = filterPaletteItems(catalog, input.value);
        const fragment = document.createDocumentFragment(); nodes = []; activeNode = null;
        filtered.forEach((item, index) => {
            const row = document.createElement('div'); row.id = `${list.id}-option-${index}`; row.className = 'cmd-palette-item';
            row.dataset.index = String(index); row.setAttribute('role', 'option'); row.setAttribute('aria-selected', 'false');
            const left = document.createElement('div'); left.className = 'cmd-palette-item-left';
            for (const [className, value] of [['cmd-palette-item-icon', item.icon], ['cmd-palette-item-title', item.title]]) {
                const span = document.createElement('span'); span.className = className; span.textContent = value;
                if (className === 'cmd-palette-item-icon') span.setAttribute('aria-hidden', 'true');
                left.append(span);
            }
            const category = document.createElement('span'); category.className = 'cmd-palette-item-category'; category.textContent = item.category;
            row.append(left, category); fragment.append(row); nodes.push(row);
        });
        list.replaceChildren(fragment); status.hidden = filtered.length > 0;
        status.textContent = getLanguage() === 'en' ? 'No matching commands or views.' : 'Nie znaleziono pasujących poleceń ani widoków.';
        const kept = filtered.findIndex(item => item.id === previous);
        selectedState(kept >= 0 ? kept : filtered.length ? 0 : -1);
    }
    function refresh() {
        if (disposed) return;
        const en = getLanguage() === 'en';
        dialog.setAttribute('aria-label', en ? 'Command palette' : 'Paleta poleceń');
        input.setAttribute('aria-label', en ? 'Search commands and views' : 'Szukaj poleceń i widoków');
        list.setAttribute('aria-label', en ? 'Commands and views' : 'Polecenia i widoki');
        catalog = buildPaletteItems({language: getLanguage(), actions: getActions(), viewTitles: getViewTitles()});
        if (dialog.open) render();
    }
    function focusOpener() {
        if (document.querySelector('dialog[open]')) return;
        const target = opener?.isConnected && !opener.disabled ? opener : trigger;
        target?.focus();
    }
    function syncClosed() {
        input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant'); trigger?.setAttribute('aria-expanded', 'false');
    }
    function close() {
        if (!dialog.open) return;
        dialog.close(); syncClosed(); focusOpener();
    }
    function open() {
        if (disposed) return;
        if (dialog.open) {input.focus(); return;}
        if (document.querySelector('dialog[open]')) return;
        opener = document.activeElement; input.value = ''; filtered = []; selected = -1;
        refresh(); dialog.showModal(); render();
        input.setAttribute('aria-expanded', 'true'); trigger?.setAttribute('aria-expanded', 'true'); input.focus();
    }
    function execute(index) {
        const item = filtered[index]; if (!item) return;
        close();
        try {
            const result = item.type === 'view' ? onView(item.target) : item.type === 'action' ? onAction(item.target)
                : item.type === 'external' ? onExternal(item.target) : onReport();
            if (item.type === 'view') trigger?.focus();
            Promise.resolve(result).catch(onError);
        } catch (error) {onError(error);}
    }
    listen(trigger, 'click', open);
    listen(eventTarget, 'keydown', event => {
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && !event.repeat && !event.isComposing) {
            event.preventDefault(); if (dialog.open) close(); else open();
        }
    });
    listen(dialog, 'keydown', event => {
        if (event.isComposing) return;
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault(); selectedState(nextPaletteIndex(selected, event.key === 'ArrowDown' ? 1 : -1, filtered.length));
        } else if (event.key === 'Enter') {event.preventDefault(); execute(selected);}
        else if (event.key === 'Tab') {event.preventDefault(); input.focus();}
    });
    listen(dialog, 'cancel', event => {event.preventDefault(); close();});
    listen(dialog, 'close', () => {if (!dialog.open) {syncClosed(); focusOpener();}});
    listen(dialog, 'click', event => {
        if (event.target !== dialog || !dialog.open) return;
        const rect = dialog.getBoundingClientRect();
        if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) close();
    });
    listen(input, 'input', () => {filtered = []; selected = -1; render();});
    listen(list, 'click', event => {const row = event.target.closest?.('[data-index]'); if (row && list.contains(row)) execute(Number(row.dataset.index));});
    listen(list, 'pointermove', event => {const row = event.target.closest?.('[data-index]'); if (row && list.contains(row)) selectedState(Number(row.dataset.index), false);});
    refresh();
    return Object.freeze({open, close, refresh, dispose() {
        close(); disposed = true;
        for (const [target, type, handler] of listeners) target?.removeEventListener(type, handler);
        status.remove();
    }});
}
