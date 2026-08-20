const API_BASE = getApiBase();
const sessionGuard = window.MiniTechSession;
const query = new URLSearchParams(window.location.search);
const orderId = query.get('id');
const subtitle = document.querySelector('#order-detail-subtitle');
const statusMessage = document.querySelector('#order-detail-status');
const detailCard = document.querySelector('#order-detail-card');
const backLink = document.querySelector('#order-detail-back');
let accessToken = null;
let customer = null;

loadOrderDetail();

async function loadOrderDetail() {
    if (!orderId || !/^\d+$/.test(orderId)) {
        showError('Brak poprawnego identyfikatora zamówienia.');
        return;
    }
    try {
        if (!await restoreSession()) {
            showError('Zaloguj się, aby zobaczyć szczegóły swojego zamówienia.');
            return;
        }
        const orders = await request(`/orders/customer/${encodeURIComponent(customer.id)}`);
        const order = (Array.isArray(orders) ? orders : [])
            .find(item => String(item.id) === String(orderId));
        if (!order) {
            showError('Nie znaleziono zamówienia na tym koncie.');
            return;
        }
        const tracking = await loadTracking(order);
        const invoice = await loadInvoice(order);
        renderOrder(order, tracking, invoice);
    } catch (error) {
        showError(error.message || 'Nie udało się pobrać szczegółów zamówienia.');
    }
}

async function loadInvoice(order) {
    if (!['PAID', 'SHIPPED', 'COMPLETED'].includes(String(order.status || '').toUpperCase())) return null;
    try { return await request(`/invoices/order/${encodeURIComponent(order.id)}`); } catch { return null; }
}

async function loadTracking(order) {
    if (!['PAID', 'SHIPPED', 'COMPLETED'].includes(String(order.status || '').toUpperCase())) {
        return null;
    }
    try {
        return await request(`/deliveries/orders/${encodeURIComponent(order.id)}/tracking`);
    } catch {
        return null;
    }
}

function renderOrder(order, tracking, invoice) {
    document.title = `Zamówienie #${order.id} — MiniTech Store`;
    subtitle.textContent = `Zamówienie #${order.id} · ${orderStatusLabel(order.status)}`;
    statusMessage.textContent = '';
    const address = order.deliveryAddress || {};
    const userName = [address.firstName, address.lastName]
        .filter(Boolean)
        .join(', ');
    const addressLine = [address.street, address.postalCode, address.city, address.country]
        .filter(Boolean)
        .join(', ');
    const items = Array.isArray(order.items) ? order.items : [];
    const itemsMarkup = items.length
        ? items.map(item => renderItem(order, item)).join('')
        : '<p class="order-detail-muted">Brak pozycji zamówienia.</p>';
    const discount = Number(order.discountAmount || 0);
    const fulfillmentLabel = order.fulfillmentType === 'STORE_PICKUP' ? 'Odbiór osobisty' : 'Dostawa';
    const scheduledDate = order.deliveryDate
        ? formatDeliveryDate(order.deliveryDate)
        : 'Termin nieustalony';
    const isPickup = order.fulfillmentType === 'STORE_PICKUP';
    const returnEligible = ['PAID', 'SHIPPED', 'COMPLETED']
        .includes(String(order.status || '').toUpperCase());
    detailCard.innerHTML = `
        <div class="order-detail-summary">
            <div><span>Status</span><strong class="auth-order-status">${escapeHtml(orderStatusLabel(order.status))}</strong></div>
            <div><span>Data</span><strong>${escapeHtml(formatOrderDate(order.createdAt))}</strong></div>
            <div><span>Realizacja</span><strong>${escapeHtml(fulfillmentLabel)} · ${escapeHtml(order.deliveryMethod || '—')}</strong></div>
            <div><span>Termin</span><strong>${escapeHtml(scheduledDate)}</strong></div>
            ${order.pickupLocation ? `<div><span>Punkt odbioru</span><strong>${escapeHtml(order.pickupLocation)}</strong></div>` : ''}
        </div>
        <section class="order-detail-data-group">
            <p class="eyebrow">DANE UŻYTKOWNIKA</p>
            ${userName ? `<div><span>Użytkownik</span><strong>${escapeHtml(userName)}</strong></div>` : ''}
            ${order.customerEmail ? `<div><span>E-mail</span><strong>${escapeHtml(order.customerEmail)}</strong></div>` : ''}
        </section>
        <section class="order-detail-data-group">
            <p class="eyebrow">${isPickup ? 'PUNKT ODBIORU' : 'ADRES DOSTAWY'}</p>
            ${isPickup
                ? `<div><span>Punkt</span><strong>${escapeHtml(order.pickupLocation || 'MiniTech Store — Warszawa')}</strong></div>`
                : (addressLine ? `<div><span>Adres</span><strong>${escapeHtml(addressLine)}</strong></div>` : '<p class="order-detail-muted">Brak danych adresowych.</p>')}
        </section>
        <section class="order-detail-data-group">
            <p class="eyebrow">ŚLEDZENIE DOSTAWY</p>
            ${tracking ? `
                <div><span>Przewoźnik</span><strong>${escapeHtml(tracking.carrier)}</strong></div>
                <div><span>Numer przesyłki</span><strong>${escapeHtml(tracking.trackingNumber)}</strong></div>
                <div><span>Status przewoźnika</span><strong>${escapeHtml(shipmentStatusLabel(tracking.status))}</strong></div>
                <div><span>Ostatnia aktualizacja</span><strong>${escapeHtml(formatOrderDate(tracking.updatedAt))}</strong></div>
            ` : `<p class="order-detail-muted">Numer przesyłki nie został jeszcze nadany. Po opłaceniu zamówienia zostanie utworzone śledzenie w profilu dev/test.</p>`}
        </section>
        <section class="order-detail-items">
            <p class="eyebrow">POZYCJE ZAMÓWIENIA</p>
            ${itemsMarkup}
        </section>
        <section class="order-detail-data-group order-detail-invoice">
            <p class="eyebrow">FAKTURA</p>
            ${invoice ? `<div><span>Numer faktury</span><strong>${escapeHtml(invoice.invoiceNumber)}</strong></div>
                <div><span>Netto</span><strong>${formatMoney(invoice.netAmount)}</strong></div>
                <div><span>VAT</span><strong>${formatMoney(invoice.vatAmount)}</strong></div>
                <div><span>Brutto</span><strong>${formatMoney(invoice.grossAmount)}</strong></div>
                <div class="order-detail-invoice-download"><span>Plik</span><strong><a class="secondary-button" href="${API_BASE}/invoices/order/${encodeURIComponent(order.id)}/pdf" target="_blank" rel="noreferrer">Pobierz PDF</a></strong></div>`
                : '<p class="order-detail-muted">Dla tego zamówienia nie wystawiono faktury.</p>'}
        </section>
        ${returnEligible ? `<section class="return-request-box">
            <p class="eyebrow">ZWROT LUB REKLAMACJA</p>
            <h2>Potrzebujesz pomocy z zakupem?</h2>
            <p class="form-hint">Zgłoszenie trafi do obsługi i będzie widoczne na Twoim koncie.</p>
            <form id="return-request-form" class="return-request-form">
                <label>Typ zgłoszenia<select id="return-request-type"><option value="RETURN">Zwrot</option><option value="COMPLAINT">Reklamacja</option></select></label>
                <label>Powód<input id="return-request-reason" maxlength="120" required placeholder="np. Produkt nie spełnia oczekiwań"></label>
                <label>Opis<textarea id="return-request-description" maxlength="2000" rows="4" required placeholder="Opisz sprawę"></textarea></label>
                <button class="primary-button" type="submit">Wyślij zgłoszenie</button>
                <p class="return-request-status" id="return-request-status" role="status"></p>
            </form>
        </section>` : ''}
        ${discount > 0 ? `<p class="order-detail-discount">Rabat: −${formatMoney(discount)}${order.promotionCode ? ` · kod ${escapeHtml(order.promotionCode)}` : ''}</p>` : ''}
        <div class="order-detail-total"><span>Razem</span><strong>${formatMoney(order.totalPrice)}</strong></div>
        <p class="form-hint">${order.status === 'PAID'
            ? 'Opłacone produkty możesz ocenić przyciskiem „Dodaj opinię”. Opinia trafi do moderacji.'
            : 'Dodanie opinii będzie możliwe po opłaceniu zamówienia.'}</p>`;
    detailCard.hidden = false;
    detailCard.querySelector('#return-request-form')?.addEventListener('submit', submitReturnRequest);
    backLink.href = 'index.html#top';
}

async function submitReturnRequest(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const status = form.querySelector('#return-request-status');
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true; status.textContent = 'Wysyłam zgłoszenie…'; status.className = 'return-request-status';
    try {
        await request('/returns', { method: 'POST', body: JSON.stringify({
            orderId: Number(orderId), customerId: customer.id, customerEmail: customer.email,
            requestType: form.querySelector('#return-request-type').value,
            reason: form.querySelector('#return-request-reason').value.trim(),
            description: form.querySelector('#return-request-description').value.trim()
        })});
        status.textContent = 'Zgłoszenie zapisane. Obsługa skontaktuje się z Tobą przez e-mail.';
        status.className = 'return-request-status success'; form.reset();
    } catch (error) { status.textContent = error.message || 'Nie udało się wysłać zgłoszenia.'; status.className = 'return-request-status error'; }
    finally { button.disabled = false; }
}

function renderItem(order, item) {
    const productLink = item.productId
        ? `<a class="secondary-button" href="product.html?id=${encodeURIComponent(item.productId)}">Szczegóły produktu</a>`
        : '';
    const reviewLink = order.status === 'PAID' && item.productId
        ? `<a class="primary-button" href="product.html?id=${encodeURIComponent(item.productId)}&reviewOrder=${encodeURIComponent(order.id)}#product-reviews">Dodaj opinię</a>`
        : '';
    return `<article class="order-detail-item">
        <div>
            <strong>${escapeHtml(item.productName || 'Produkt')}</strong>
            <p>${escapeHtml(item.sku || '')} · ilość: ${escapeHtml(item.quantity || 0)}</p>
            <p>${formatMoney(item.unitPrice)} / szt. · razem ${formatMoney(item.totalPrice)}</p>
        </div>
        <div class="order-detail-item-actions">${productLink}${reviewLink}</div>
    </article>`;
}

async function request(path, options = {}, retryAfterRefresh = true) {
    const headers = new Headers(options.headers || {});
    if (options.body != null && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
    }
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
    const response = await fetch(`${API_BASE}${path}`, {
        ...options,
        headers,
        credentials: 'include',
        cache: 'no-store'
    });
    if (response.status === 401 && retryAfterRefresh && await refreshSession()) {
        return request(path, options, false);
    }
    const raw = await response.text();
    let data = raw;
    try { data = raw ? JSON.parse(raw) : null; } catch { /* Keep plain error text. */ }
    if (!response.ok) {
        throw new Error(data && typeof data === 'object'
            ? (data.message || data.error || `HTTP ${response.status}`)
            : raw || `HTTP ${response.status}`);
    }
    return data;
}

async function refreshSession() {
    if (!sessionGuard?.isFresh()) {
        await expireOrderSession(false);
        return false;
    }
    try {
        const response = await fetch(`${API_BASE}/customers/refresh`, {
            method: 'POST', credentials: 'include', cache: 'no-store'
        });
        if (!response.ok) {
            accessToken = null;
            customer = null;
            if (response.status === 401 || response.status === 403) sessionGuard?.clear();
            return false;
        }
        const session = await response.json();
        accessToken = session.accessToken || null;
        customer = session.customer || null;
        if (accessToken && customer) {
            sessionGuard?.start(() => expireOrderSession(true));
            sessionGuard?.touch();
        }
        return Boolean(accessToken && customer);
    } catch {
        accessToken = null;
        customer = null;
        return false;
    }
}

async function restoreSession() {
    if (!sessionGuard?.isFresh()) {
        await expireOrderSession(false);
        return false;
    }
    return refreshSession();
}

async function expireOrderSession(showMessage) {
    try {
        await fetch(`${API_BASE}/customers/logout`, {
            method: 'POST', credentials: 'include', cache: 'no-store'
        });
    } catch {
        // The local timeout still removes access to order details.
    }
    sessionGuard?.clear();
    accessToken = null;
    customer = null;
    if (showMessage) showError('Sesja wygasła po 30 minutach bezczynności. Zaloguj się ponownie w sklepie.');
}

function showError(message) {
    subtitle.textContent = '';
    statusMessage.textContent = message;
    statusMessage.classList.add('error');
    detailCard.hidden = true;
}

function getApiBase() {
    if (window.MINITECH_API_BASE) return window.MINITECH_API_BASE.replace(/\/$/, '');
    const queryApi = new URLSearchParams(window.location.search).get('api');
    if (queryApi) return queryApi.replace(/\/$/, '');
    const host = window.location.hostname || '127.0.0.1';
    return `http://${host}:18080/api`;
}

function orderStatusLabel(status) {
    return ({
        CREATED: 'Utworzone',
        CONFIRMED: 'Potwierdzone',
        PAID: 'Opłacone',
        SHIPPED: 'Wysłane',
        COMPLETED: 'Zakończone',
        CANCELLED: 'Anulowane'
    })[status] || status || 'Nieznany';
}

function shipmentStatusLabel(status) {
    return ({
        LABEL_CREATED: 'Etykieta utworzona',
        READY_FOR_PICKUP: 'Gotowe do odbioru',
        IN_TRANSIT: 'W transporcie',
        OUT_FOR_DELIVERY: 'Wydano do doręczenia',
        DELIVERED: 'Doręczona',
        PICKED_UP: 'Odebrana',
        CANCELLED: 'Anulowana'
    })[status] || status || 'Nieznany';
}

function formatDeliveryDate(value) {
    const date = new Date(`${value}T12:00:00`);
    return Number.isNaN(date.getTime())
        ? String(value)
        : date.toLocaleDateString('pl-PL', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' });
}

function formatOrderDate(value) {
    if (!value) return 'Data niedostępna';
    const date = new Date(value);
    return Number.isNaN(date.getTime())
        ? String(value)
        : date.toLocaleString('pl-PL', { dateStyle: 'medium', timeStyle: 'short' });
}

function formatMoney(value) {
    return `${Number(value || 0).toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} zł`;
}

function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>'"]/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[character]));
}
