import { i18n } from './i18n.js?v=20261028-a43-32';
import { createStoreClient } from './store-client.js?v=20261026-2';
import { createChatClient } from './chat-client.js?v=20261026-1';

(function () {
    const apiBase = getApiBase();
    const chatClient = createChatClient({request: createStoreClient({baseUrl: apiBase}).request});
    const widget = document.createElement('div');
    widget.className = 'chat-widget';
    widget.innerHTML = `
        <button class="chat-launcher" id="chat-launcher" type="button" aria-expanded="false" aria-controls="chat-panel" aria-label="Czat">
            <span class="chat-launcher-icon" aria-hidden="true">✉</span>
            <span class="chat-launcher-label" id="chat-launcher-text">Czat</span>
        </button>
        <section class="chat-panel" id="chat-panel" hidden aria-labelledby="chat-title">
            <header class="chat-panel-header">
                <div>
                    <p class="eyebrow">MINITECH STORE</p>
                    <h2 id="chat-title">Cześć!</h2>
                    <p id="chat-subtitle">Jak możemy Ci pomóc?</p>
                </div>
                <button class="chat-panel-close" id="chat-close" type="button" aria-label="Zamknij czat">×</button>
            </header>
            <div class="chat-panel-body">
                <p class="chat-intro" id="chat-intro">Masz pytanie o produkt, zamówienie albo dostawę? Napisz do nas — odpowiedź pojawi się tutaj i zostanie wysłana na podany adres e-mail.</p>
                <section class="chat-thread" id="chat-thread" hidden aria-live="polite">
                    <div class="chat-thread-header">
                        <strong id="chat-thread-id">Rozmowa</strong>
                        <span id="chat-thread-state">Nowa</span>
                    </div>
                    <div class="chat-bubble chat-bubble-customer">
                        <span id="chat-bubble-user-label">Ty</span>
                        <p id="chat-thread-message"></p>
                    </div>
                    <button class="secondary-button" id="chat-history-older" type="button" hidden>Wczytaj starsze wiadomości</button>
                    <div id="chat-entry-history"></div>
                    <p id="chat-history-status" role="status"></p>
                    <p class="chat-live-status" id="chat-live-status">Centrala odpowie tutaj.</p>
                </section>
                <button class="secondary-button" id="chat-new-conversation" type="button" hidden>Nowa rozmowa</button>
                <form id="chat-form" class="chat-form">
                    <label id="chat-name-label">Imię<input id="chat-name" type="text" maxlength="100" autocomplete="name" required></label>
                    <label id="chat-email-label">E-mail<input id="chat-email" type="email" maxlength="255" autocomplete="email" required></label>
                    <label id="chat-subject-label">Temat<input id="chat-subject" type="text" maxlength="160" placeholder="np. Pytanie o zamówienie"></label>
                    <label id="chat-message-label">Wiadomość<textarea id="chat-message" maxlength="4000" rows="5" required placeholder="W czym możemy pomóc?"></textarea></label>
                    <button class="primary-button full-width" type="submit" id="chat-submit">Wyślij wiadomość</button>
                    <p class="chat-status" id="chat-status" role="status"></p>
                </form>
                <p class="chat-fallback" id="chat-fallback">Czekasz długo na odpowiedź? <a id="chat-email-fallback" href="mailto:company@minitech.local">Wyślij e-mail</a>.</p>
            </div>
        </section>
    `;
    document.body.appendChild(widget);

    const launcher = widget.querySelector('#chat-launcher');
    const panel = widget.querySelector('#chat-panel');
    const close = widget.querySelector('#chat-close');
    const form = widget.querySelector('#chat-form');
    const status = widget.querySelector('#chat-status');
    const submit = widget.querySelector('#chat-submit');
    const email = widget.querySelector('#chat-email');
    const name = widget.querySelector('#chat-name');
    const thread = widget.querySelector('#chat-thread');
    const threadId = widget.querySelector('#chat-thread-id');
    const threadState = widget.querySelector('#chat-thread-state');
    const threadMessage = widget.querySelector('#chat-thread-message');
    const history = widget.querySelector('#chat-entry-history');
    const older = widget.querySelector('#chat-history-older');
    const newConversation = widget.querySelector('#chat-new-conversation');
    const historyEntries = new Map();
    let historyStarted = false;
    let olderCursor = null;
    let pendingReply = null;
    let loading = null;
    const liveStatus = widget.querySelector('#chat-live-status');
    const emailFallback = widget.querySelector('#chat-email-fallback');
    const conversationStorageKey = `minitech.chat.conversationId.${apiBase}`;
    const tokenStorageKey = `${conversationStorageKey}.accessToken`;
    let conversationToken;
    try { conversationToken = window.localStorage.getItem(tokenStorageKey); } catch { conversationToken = null; }
    let conversationId = conversationToken ? readConversationId() : null;
    let pollTimer = null;

    launcher.addEventListener('click', () => {
        const open = panel.hidden;
        if (!open) { closeChat(); return; }
        panel.hidden = false;
        launcher.setAttribute('aria-expanded', String(open));
        if (open) {
            prefillFromAccount();
            if (conversationId) {
                thread.hidden = false;
                loadConversation(true);
                startPolling();
            }
            updateEmailFallback();
            (conversationId ? widget.querySelector('#chat-message') : name).focus();
        }
    });
    close.addEventListener('click', closeChat);
    older.addEventListener('click', () => loadHistory(olderCursor));
    newConversation.addEventListener('click', () => {
        resetConversation();
        name.focus();
    });
    form.addEventListener('submit', async event => {
        event.preventDefault();
        status.textContent = 'Wysyłanie wiadomości…';
        status.className = 'chat-status';
        submit.disabled = true;
        newConversation.disabled = true;
        try {
            const followUp = Boolean(conversationId);
            const text = widget.querySelector('#chat-message').value.trim();
            if (!pendingReply || pendingReply.message !== text) pendingReply = { message: text, requestId: crypto.randomUUID() };
            const newConversationToken = followUp ? conversationToken : window.MiniTechApi.newAccessToken();
            const payload = followUp
                ? await chatClient.reply(conversationId, pendingReply, newConversationToken)
                : await chatClient.create({
                    customerName: name.value.trim(),
                    customerEmail: email.value.trim(),
                    subject: widget.querySelector('#chat-subject').value.trim(),
                    message: widget.querySelector('#chat-message').value.trim()
                }, newConversationToken);
            conversationToken = newConversationToken;
            conversationId = Number(payload.id);
            saveConversationId(conversationId);
            pendingReply = null;
            renderConversation(payload);
            await loadHistory(0);
            startPolling();
            widget.querySelector('#chat-subject').value = '';
            if (widget.querySelector('#chat-message').value.trim() === text) widget.querySelector('#chat-message').value = '';
            status.textContent = 'Wiadomość wysłana. Odpowiedź pojawi się w tym oknie czatu.';
            status.className = 'chat-status success';
            prefillFromAccount();
            updateEmailFallback();
        } catch (error) {
            status.textContent = `Nie udało się wysłać wiadomości: ${error.message}`;
            status.className = 'chat-status error';
        } finally {
            submit.disabled = false;
            newConversation.disabled = false;
        }
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && !panel.hidden && !document.querySelector('dialog[open]')) closeChat();
    });

    [email, name, widget.querySelector('#chat-subject'), widget.querySelector('#chat-message')]
        .forEach(field => field.addEventListener('input', updateEmailFallback));
    restoreConversation();

    function closeChat() {
        panel.hidden = true;
        launcher.setAttribute('aria-expanded', 'false');
        stopPolling();
        cancelReads();
        launcher.focus();
    }

    function prefillFromAccount() {
        const accountEmail = document.querySelector('#auth-email')?.value;
        if (!email.value && accountEmail && accountEmail.includes('@')) email.value = accountEmail;
        const accountName = document.querySelector('#auth-profile-first-name')?.value;
        if (!name.value && accountName) name.value = accountName;
        updateEmailFallback();
    }

    function readConversationId() {
        try {
            const value = Number.parseInt(window.localStorage.getItem(conversationStorageKey) || '', 10);
            return Number.isInteger(value) && value > 0 ? value : null;
        } catch (_) {
            return null;
        }
    }

    function saveConversationId(id) {
        try {
            window.localStorage.setItem(conversationStorageKey, String(id));
            window.localStorage.setItem(tokenStorageKey, conversationToken);
        } catch (_) {
            // Private browsing can disable localStorage; live polling still works until refresh.
        }
    }

    function clearConversationId() {
        try {
            window.localStorage.removeItem(conversationStorageKey);
            window.localStorage.removeItem(tokenStorageKey);
        } catch (_) {
            // Ignore storage errors; the in-memory state is still cleared.
        }
    }

    function restoreConversation() {
        if (!conversationId) return;
        thread.hidden = false;
        loadConversation(true);
        startPolling();
    }

    function startPolling() {
        if (!conversationId || pollTimer || panel.hidden || document.hidden) return;
        pollTimer = window.setInterval(() => loadConversation(false), 3000);
    }

    function stopPolling() {
        if (!pollTimer) return;
        window.clearInterval(pollTimer);
        pollTimer = null;
    }

    function cancelReads() {
        chatClient.cancelReads();
        loading = null;
    }

    function resetConversation() {
        stopPolling(); cancelReads();
        conversationId = null; conversationToken = null; pendingReply = null;
        clearConversationId(); historyEntries.clear(); history.replaceChildren(); historyStarted = false; olderCursor = null;
        older.hidden = true; newConversation.hidden = true; thread.hidden = true;
        widget.querySelector('#chat-history-status').textContent = '';
        [name, email, widget.querySelector('#chat-subject')].forEach(input => {input.parentElement.hidden = false;});
        name.required = true; email.required = true;
    }

    async function loadConversation(showError) {
        if (!conversationId || loading) return;
        const flight = {};
        loading = flight;
        const requestedId = conversationId;
        const requestedToken = conversationToken;
        try {
            const payload = await chatClient.conversation(requestedId, requestedToken);
            if (conversationId !== requestedId || conversationToken !== requestedToken) return;
            renderConversation(payload);
            await loadHistory(0);
        } catch (error) {
            if (error.name === 'AbortError' || conversationId !== requestedId || conversationToken !== requestedToken) return;
            if (error.status === 404) {resetConversation(); return;}
            if (showError) {
                liveStatus.textContent = `Nie udało się odświeżyć rozmowy: ${error.message}`;
                liveStatus.className = 'chat-live-status error';
            }
        } finally { if (loading === flight) loading = null; }
    }

    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {stopPolling(); cancelReads();} else if (!panel.hidden) { loadConversation(false); startPolling(); }
    });

    async function loadHistory(before = 0) {
        if (!conversationId) return;
        const id=conversationId, token=conversationToken;
        try {
            const page = await chatClient.history(id, token, before || 0);
            if (id!==conversationId || token!==conversationToken) return;
            const previousLast=Math.max(0,...[...historyEntries.values()].map(entry=>Number(entry.id)));
            const latestFirst=Math.min(...(page.entries || []).map(entry=>Number(entry.id)));
            if (!historyStarted || before || (page.hasMore && previousLast < latestFirst)) olderCursor=page.nextCursor;
            historyStarted=true;
            widget.querySelector('#chat-history-status').textContent='';
            (page.entries || []).forEach(entry => historyEntries.set(String(entry.id),entry));
            renderEntries();
            older.hidden=!olderCursor;
        } catch (error) {
            if (error.name === 'AbortError' || id !== conversationId || token !== conversationToken) return;
            widget.querySelector('#chat-history-status').textContent='Nie udało się odświeżyć historii. Zachowano ostatni widok.';
        }
    }

    function renderEntries() {
        const entries=[...historyEntries.values()].filter(entry => !entry.initialMessage).sort((a,b) => Number(a.id)-Number(b.id));
        const lastSupport=entries.filter(entry => entry.author==='SUPPORT').at(-1);
        const fragment=document.createDocumentFragment();
        for (const entry of entries) {
            const bubble=document.createElement('div');bubble.className=`chat-bubble chat-bubble-${entry.author==='SUPPORT'?'support':'customer'}`;
            const label=document.createElement('span');label.textContent=entry.author==='SUPPORT'?'MiniTech Store':(i18n?.getLanguage()==='en'?'You':'Ty');
            const text=document.createElement('p');text.textContent=entry.message;
            if (entry===lastSupport) text.id='chat-thread-reply';
            const time=document.createElement('time');time.dateTime=entry.createdAt || '';time.textContent=entry.createdAt?new Date(entry.createdAt).toLocaleString():'';
            bubble.append(label,text,time);fragment.append(bubble);
        }
        history.replaceChildren(fragment);
    }

    function renderConversation(payload) {
        thread.hidden = false;
        threadId.textContent = `Rozmowa #${payload.id}`;
        const answered = payload.status ? payload.status==='ANSWERED' : Boolean(payload.replyMessage);
        threadState.textContent = answered ? 'Odpowiedziano' : 'Oczekuje na odpowiedź';
        threadMessage.textContent = payload.message || '';
        newConversation.hidden=false;
        [name,email,widget.querySelector('#chat-subject')].forEach(input => { input.parentElement.hidden=true; });
        name.required=false;email.required=false;
        if (!historyStarted && payload.replyMessage) {
            const bubble=document.createElement('div');bubble.className='chat-bubble chat-bubble-support';
            const text=document.createElement('p');text.id='chat-thread-reply';text.textContent=payload.replyMessage;bubble.append(text);history.replaceChildren(bubble);
        }
        liveStatus.textContent = answered
            ? 'Odpowiedź z Centrali jest już dostępna.'
            : 'Czekamy na odpowiedź Centrali. Okno sprawdza nowe wiadomości co 3 sekundy.';
        liveStatus.className = 'chat-live-status';
    }

    function applyChatLanguage() {
        const isEn = i18n && i18n.getLanguage() === 'en';
        older.textContent=isEn?'Load older messages':'Wczytaj starsze wiadomości';
        newConversation.textContent=isEn?'New conversation':'Nowa rozmowa';
        if(historyStarted)renderEntries();
        const launcherBtn = widget.querySelector('#chat-launcher');
        if (launcherBtn) launcherBtn.setAttribute('aria-label', isEn ? 'Chat' : 'Czat');
        const launcherText = widget.querySelector('#chat-launcher-text');
        if (launcherText) launcherText.textContent = isEn ? 'Chat' : 'Czat';
        const title = widget.querySelector('#chat-title');
        if (title) title.textContent = isEn ? 'Hello!' : 'Cześć!';
        const subtitle = widget.querySelector('#chat-subtitle');
        if (subtitle) subtitle.textContent = isEn ? 'How can we help you?' : 'Jak możemy Ci pomóc?';
        const intro = widget.querySelector('#chat-intro');
        if (intro) intro.textContent = isEn ? 'Have a question about a product, order, or delivery? Write to us — responses appear here and will be emailed to your address.' : 'Masz pytanie o produkt, zamówienie albo dostawę? Napisz do nas — odpowiedź pojawi się tutaj i zostanie wysłana na podany adres e-mail.';
        const userLabel = widget.querySelector('#chat-bubble-user-label');
        if (userLabel) userLabel.textContent = isEn ? 'You' : 'Ty';
        const closeBtn = widget.querySelector('#chat-close');
        if (closeBtn) closeBtn.setAttribute('aria-label', isEn ? 'Close chat' : 'Zamknij czat');

        const nameInp = widget.querySelector('#chat-name');
        const nameLbl = widget.querySelector('#chat-name-label');
        if (nameLbl) nameLbl.childNodes[0].nodeValue = isEn ? 'Name' : 'Imię';

        const emailInp = widget.querySelector('#chat-email');
        const emailLbl = widget.querySelector('#chat-email-label');
        if (emailLbl) emailLbl.childNodes[0].nodeValue = isEn ? 'Email' : 'E-mail';

        const subjInp = widget.querySelector('#chat-subject');
        const subjLbl = widget.querySelector('#chat-subject-label');
        if (subjLbl) subjLbl.childNodes[0].nodeValue = isEn ? 'Subject' : 'Temat';
        if (subjInp) subjInp.placeholder = isEn ? 'e.g. Question about order' : 'np. Pytanie o zamówienie';

        const msgInp = widget.querySelector('#chat-message');
        const msgLbl = widget.querySelector('#chat-message-label');
        if (msgLbl) msgLbl.childNodes[0].nodeValue = isEn ? 'Message' : 'Wiadomość';
        if (msgInp) msgInp.placeholder = isEn ? 'How can we help you?' : 'W czym możemy pomóc?';

        const submitBtn = widget.querySelector('#chat-submit');
        if (submitBtn) submitBtn.textContent = isEn ? 'Send message' : 'Wyślij wiadomość';

        const fallback = widget.querySelector('#chat-fallback');
        if (fallback) {
            fallback.innerHTML = isEn
                ? 'Waiting for a response? <a id="chat-email-fallback" href="mailto:company@minitech.local">Send an email</a>.'
                : 'Czekasz długo na odpowiedź? <a id="chat-email-fallback" href="mailto:company@minitech.local">Wyślij e-mail</a>.';
        }
    }

    applyChatLanguage();
    window.addEventListener('minitech:language-changed', () => {
        applyChatLanguage();
    });

    function updateEmailFallback() {
        const isEn = i18n && i18n.getLanguage() === 'en';
        const subject = widget.querySelector('#chat-subject').value.trim() || (isEn ? 'Question to MiniTech Store' : 'Pytanie do MiniTech Store');
        const body = widget.querySelector('#chat-message').value.trim() || (isEn ? 'Hello,\n\n' : 'Dzień dobry,\n\n');
        const supportEmail = window.MINITECH_SUPPORT_EMAIL || 'company@minitech.local';
        const emailLink = widget.querySelector('#chat-email-fallback');
        if (emailLink) emailLink.href = `mailto:${supportEmail}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
    }

    function getApiBase() {
        return window.MiniTechApi.baseUrl;
    }
}());
