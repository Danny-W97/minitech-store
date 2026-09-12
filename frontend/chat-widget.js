(function () {
    const apiBase = getApiBase();
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
                    <div class="chat-bubble chat-bubble-support" id="chat-reply-bubble" hidden>
                        <span>MiniTech Store</span>
                        <p id="chat-thread-reply"></p>
                    </div>
                    <p class="chat-live-status" id="chat-live-status">Centrala odpowie tutaj.</p>
                </section>
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
    const replyBubble = widget.querySelector('#chat-reply-bubble');
    const threadReply = widget.querySelector('#chat-thread-reply');
    const liveStatus = widget.querySelector('#chat-live-status');
    const emailFallback = widget.querySelector('#chat-email-fallback');
    const conversationStorageKey = `minitech.chat.conversationId.${apiBase}`;
    let conversationId = readConversationId();
    let pollTimer = null;

    launcher.addEventListener('click', () => {
        const open = panel.hidden;
        panel.hidden = !open;
        launcher.setAttribute('aria-expanded', String(open));
        if (open) {
            prefillFromAccount();
            if (conversationId) {
                thread.hidden = false;
                startPolling();
            }
            updateEmailFallback();
            name.focus();
        }
    });
    close.addEventListener('click', closeChat);
    form.addEventListener('submit', async event => {
        event.preventDefault();
        status.textContent = 'Wysyłanie wiadomości…';
        status.className = 'chat-status';
        submit.disabled = true;
        try {
            const response = await fetch(`${apiBase}/chat/messages`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    customerName: name.value.trim(),
                    customerEmail: email.value.trim(),
                    subject: widget.querySelector('#chat-subject').value.trim(),
                    message: widget.querySelector('#chat-message').value.trim()
                })
            });
            const payload = await response.json().catch(() => ({}));
            if (!response.ok) throw new Error(payload.message || `HTTP ${response.status}`);
            conversationId = Number(payload.id);
            saveConversationId(conversationId);
            renderConversation(payload);
            startPolling();
            widget.querySelector('#chat-subject').value = '';
            widget.querySelector('#chat-message').value = '';
            status.textContent = 'Wiadomość wysłana. Odpowiedź pojawi się w tym oknie czatu.';
            status.className = 'chat-status success';
            prefillFromAccount();
            updateEmailFallback();
        } catch (error) {
            status.textContent = `Nie udało się wysłać wiadomości: ${error.message}`;
            status.className = 'chat-status error';
        } finally {
            submit.disabled = false;
        }
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && !panel.hidden) closeChat();
    });

    [email, name, widget.querySelector('#chat-subject'), widget.querySelector('#chat-message')]
        .forEach(field => field.addEventListener('input', updateEmailFallback));
    restoreConversation();

    function closeChat() {
        panel.hidden = true;
        launcher.setAttribute('aria-expanded', 'false');
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
        } catch (_) {
            // Private browsing can disable localStorage; live polling still works until refresh.
        }
    }

    function clearConversationId() {
        try {
            window.localStorage.removeItem(conversationStorageKey);
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
        if (!conversationId || pollTimer) return;
        pollTimer = window.setInterval(() => loadConversation(false), 3000);
    }

    function stopPolling() {
        if (!pollTimer) return;
        window.clearInterval(pollTimer);
        pollTimer = null;
    }

    async function loadConversation(showError) {
        if (!conversationId) return;
        try {
            const response = await fetch(`${apiBase}/chat/messages/${conversationId}`);
            const payload = await response.json().catch(() => ({}));
            if (response.status === 404) {
                conversationId = null;
                clearConversationId();
                stopPolling();
                thread.hidden = true;
                return;
            }
            if (!response.ok) throw new Error(payload.message || `HTTP ${response.status}`);
            renderConversation(payload);
            if (payload.replyMessage) stopPolling();
        } catch (error) {
            if (showError) {
                liveStatus.textContent = `Nie udało się odświeżyć rozmowy: ${error.message}`;
                liveStatus.className = 'chat-live-status error';
            }
        }
    }

    function renderConversation(payload) {
        thread.hidden = false;
        threadId.textContent = `Rozmowa #${payload.id}`;
        threadState.textContent = payload.replyMessage ? 'Odpowiedziano' : 'Oczekuje na odpowiedź';
        threadMessage.textContent = payload.message || '';
        const answered = Boolean(payload.replyMessage);
        replyBubble.hidden = !answered;
        threadReply.textContent = payload.replyMessage || '';
        liveStatus.textContent = answered
            ? 'Odpowiedź z Centrali jest już dostępna.'
            : 'Czekamy na odpowiedź Centrali. Okno sprawdza nowe wiadomości co 3 sekundy.';
        liveStatus.className = 'chat-live-status';
    }

    function applyChatLanguage() {
        const isEn = window.MiniTechI18n && window.MiniTechI18n.getLanguage() === 'en';
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
        const isEn = window.MiniTechI18n && window.MiniTechI18n.getLanguage() === 'en';
        const subject = widget.querySelector('#chat-subject').value.trim() || (isEn ? 'Question to MiniTech Store' : 'Pytanie do MiniTech Store');
        const body = widget.querySelector('#chat-message').value.trim() || (isEn ? 'Hello,\n\n' : 'Dzień dobry,\n\n');
        const supportEmail = window.MINITECH_SUPPORT_EMAIL || 'company@minitech.local';
        const emailLink = widget.querySelector('#chat-email-fallback');
        if (emailLink) emailLink.href = `mailto:${supportEmail}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
    }

    function getApiBase() {
        if (window.MINITECH_API_BASE) return window.MINITECH_API_BASE.replace(/\/$/, '');
        if (window.location.port !== '18070' && window.location.protocol !== 'file:') {
            return `${window.location.origin}/api`;
        }
        const host = window.location.hostname || '127.0.0.1';
        return `http://${host}:18080/api`;
    }
}());
