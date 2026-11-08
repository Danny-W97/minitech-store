import { i18n } from './i18n.js?v=20261028-a43-32';
import { createCustomerSession, canRefreshCustomerPath as canRefresh } from './customer-session.js?v=20261027-1';
import { createStoreClient } from './store-client.js?v=20261026-2';
import { createInvoiceDownloads } from './invoice-downloads.js?v=20261026-1';

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
const customerSession = createCustomerSession({baseUrl: API_BASE, guard: sessionGuard, expire: expireOrderSession,
    onSession(session) {
        accessToken = session.accessToken; customer = session.customer;
        sessionGuard?.start(() => expireOrderSession(true)); sessionGuard?.touch();
    },
    onFailure({status}) {
        const hadSession = Boolean(customer || accessToken);
        invoiceDownloads.cancelAll(); accessToken = null; customer = null;
        if (hadSession) showError(status === 401 || status === 403
            ? 'Sesja wygasła. Zaloguj się ponownie w sklepie.'
            : 'Nie udało się odświeżyć sesji. Odśwież stronę i spróbuj ponownie.');
    },
});
const storeClient = createStoreClient({
    baseUrl: API_BASE, getAccessToken: () => accessToken,
    getHeaders: () => {
        const capability = window.MiniTechApi.orderAccess(orderId);
        return capability ? { 'X-Order-Access-Token': capability } : {};
    },
    refreshSession, canRefresh
});
const request = (...args) => storeClient.request(...args);
const invoiceDownloads = createInvoiceDownloads({
    requestBlob: storeClient.blob, document,
    getOwner: () => customer?.id ?? null,
    getOrderAccess: id => window.MiniTechApi.orderAccess(id)
});

loadOrderDetail();

async function loadOrderDetail() {
    if (!orderId || !/^\d+$/.test(orderId)) {
        showError('Brak poprawnego identyfikatora zamówienia.');
        return;
    }
    try {
        const guestAccess = window.MiniTechApi.orderAccess(orderId);
        if (!guestAccess && !await restoreSession()) {
            showError('Zaloguj się, aby zobaczyć szczegóły swojego zamówienia.');
            return;
        }
        const order = await request(`/orders/${encodeURIComponent(orderId)}`);
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
    if (!isOrderPaid(order)) return null;
    try { return await request(`/invoices/order/${encodeURIComponent(order.id)}`); } catch { return null; }
}

async function loadTracking(order) {
    if (order.paymentState !== 'COLLECTION_DUE' && !isOrderPaid(order)) {
        return null;
    }
    try {
        return await request(`/deliveries/orders/${encodeURIComponent(order.id)}/tracking`);
    } catch {
        return null;
    }
}

function isOrderPaid(order) {
    if (order.lifecycleReviewRequired) return false;
    return order.paymentState ? order.paymentState === 'PAID'
        : ['PAID', 'SHIPPED', 'COMPLETED'].includes(String(order.status || '').toUpperCase());
}

function renderOrder(order, tracking, invoice) {
    const isEn = i18n && i18n.getLanguage() === 'en';
    document.title = isEn ? `Order #${order.id} — MiniTech Store` : `Zamówienie #${order.id} — MiniTech Store`;
    subtitle.textContent = isEn ? `Order #${order.id} · ${orderStatusLabel(order.status)}` : `Zamówienie #${order.id} · ${orderStatusLabel(order.status)}`;
    statusMessage.textContent = '';
    if (order.lifecycleReviewRequired) statusMessage.textContent = isEn
        ? 'This order requires verification by store support.'
        : 'To zamówienie wymaga weryfikacji przez obsługę sklepu.';
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
        : `<p class="order-detail-muted">${isEn ? 'No order items.' : 'Brak pozycji zamówienia.'}</p>`;
    const discount = Number(order.discountAmount || 0);
    const fulfillmentLabel = order.fulfillmentType === 'STORE_PICKUP'
        ? (isEn ? 'Store pickup' : 'Odbiór osobisty')
        : (isEn ? 'Delivery' : 'Dostawa');
    const scheduledDate = order.deliveryDate
        ? formatDeliveryDate(order.deliveryDate)
        : (isEn ? 'Date not set' : 'Termin nieustalony');
    const isPickup = order.fulfillmentType === 'STORE_PICKUP';
    const returnEligible = customer && order.customerId === customer.id && order.paymentState === 'PAID'
        && order.lifecycleReviewRequired === false && ['PAID', 'SHIPPED', 'COMPLETED'].includes(order.status)
        && items.length && items.every(item => Number.isSafeInteger(item.id) && item.id > 0 && Number.isSafeInteger(item.quantity) && item.quantity > 0);
    const physicalReturnEligible = order.status === 'COMPLETED';
    detailCard.innerHTML = `
        <div class="order-detail-summary">
            <div><span>${isEn ? 'Status' : 'Status'}</span><strong class="auth-order-status">${escapeHtml(orderStatusLabel(order.status))}</strong></div>
            <div><span>${isEn ? 'Date' : 'Data'}</span><strong>${escapeHtml(formatOrderDate(order.createdAt))}</strong></div>
            <div><span>${isEn ? 'Payment' : 'Płatność'}</span><strong>${isOrderPaid(order)
                ? (isEn ? 'Paid' : 'Opłacone')
                : order.paymentState === 'REFUNDED' ? (isEn ? 'Refunded' : 'Płatność zwrócona')
                : order.paymentState === 'REFUND_PENDING' ? (isEn ? 'Refund pending' : 'Zwrot płatności w toku')
                : order.paymentState === 'COLLECTION_DUE' ? (isEn ? 'Due on handover' : 'Do zapłaty przy odbiorze') : (isEn ? 'Awaiting payment' : 'Oczekuje na płatność')}</strong></div>
            <div><span>${isEn ? 'Fulfillment' : 'Realizacja'}</span><strong>${escapeHtml(fulfillmentLabel)} · ${escapeHtml(order.deliveryMethod || '—')}</strong></div>
            <div><span>${isEn ? 'Scheduled' : 'Termin'}</span><strong>${escapeHtml(scheduledDate)}</strong></div>
            ${order.pickupLocation ? `<div><span>${isEn ? 'Pickup location' : 'Punkt odbioru'}</span><strong>${escapeHtml(order.pickupLocation)}</strong></div>` : ''}
        </div>
        <section class="order-detail-data-group">
            <p class="eyebrow">${isEn ? 'USER DATA' : 'DANE UŻYTKOWNIKA'}</p>
            ${userName ? `<div><span>${isEn ? 'User' : 'Użytkownik'}</span><strong>${escapeHtml(userName)}</strong></div>` : ''}
            ${order.customerEmail ? `<div><span>${isEn ? 'Email' : 'E-mail'}</span><strong>${escapeHtml(order.customerEmail)}</strong></div>` : ''}
        </section>
        <section class="order-detail-data-group">
            <p class="eyebrow">${isPickup ? (isEn ? 'PICKUP POINT' : 'PUNKT ODBIORU') : (isEn ? 'DELIVERY ADDRESS' : 'ADRES DOSTAWY')}</p>
            ${isPickup
                ? `<div><span>${isEn ? 'Point' : 'Punkt'}</span><strong>${escapeHtml(order.pickupLocation || 'MiniTech Store — Warszawa')}</strong></div>`
                : (addressLine ? `<div><span>${isEn ? 'Address' : 'Adres'}</span><strong>${escapeHtml(addressLine)}</strong></div>` : `<p class="order-detail-muted">${isEn ? 'No address data.' : 'Brak danych adresowych.'}</p>`)}
        </section>
        <section class="order-detail-data-group">
            <p class="eyebrow">${isEn ? 'DELIVERY TRACKING' : 'ŚLEDZENIE DOSTAWY'}</p>
            ${tracking ? `
                <div><span>${isEn ? 'Carrier' : 'Przewoźnik'}</span><strong>${escapeHtml(tracking.carrier)}</strong></div>
                <div><span>${isEn ? 'Tracking number' : 'Numer przesyłki'}</span><strong>${escapeHtml(tracking.trackingNumber)}</strong></div>
                <div><span>${isEn ? 'Carrier status' : 'Status przewoźnika'}</span><strong>${escapeHtml(shipmentStatusLabel(tracking.status))}</strong></div>
                <div><span>${isEn ? 'Last update' : 'Ostatnia aktualizacja'}</span><strong>${escapeHtml(formatOrderDate(tracking.updatedAt))}</strong></div>
            ` : `<p class="order-detail-muted">${isEn ? 'Tracking number has not been assigned yet.' : 'Numer przesyłki nie został jeszcze nadany. Po opłaceniu zamówienia zostanie utworzone śledzenie w profilu dev/test.'}</p>`}
        </section>
        <section class="order-detail-items">
            <p class="eyebrow">${isEn ? 'ORDER ITEMS' : 'POZYCJE ZAMÓWIENIA'}</p>
            ${itemsMarkup}
        </section>
        <section class="order-detail-data-group order-detail-invoice">
            <p class="eyebrow">${isEn ? 'INVOICE' : 'FAKTURA'}</p>
            ${invoice ? `<div><span>${isEn ? 'Invoice number' : 'Numer faktury'}</span><strong>${escapeHtml(invoice.invoiceNumber)}</strong></div>
                <div><span>${isEn ? 'Net' : 'Netto'}</span><strong>${formatMoney(invoice.netAmount)}</strong></div>
                <div><span>${isEn ? 'VAT' : 'VAT'}</span><strong>${formatMoney(invoice.vatAmount)}</strong></div>
                <div><span>${isEn ? 'Gross' : 'Brutto'}</span><strong>${formatMoney(invoice.grossAmount)}</strong></div>
                <div class="order-detail-invoice-download"><span>${isEn ? 'File' : 'Plik'}</span><strong><button type="button" class="secondary-button" id="download-invoice">${isEn ? 'Download PDF' : 'Pobierz PDF'}</button></strong></div>`
                : `<p class="order-detail-muted">${order.invoiceRequested
                    ? (isEn ? 'Your invoice was requested. The document will appear here once issued.' : 'Faktura została zamówiona. Dokument pojawi się tutaj po wystawieniu.')
                    : (isEn ? 'No invoice has been issued for this order.' : 'Dla tego zamówienia nie wystawiono faktury.')}</p>`}
        </section>
        ${returnEligible ? `<section class="return-request-box">
            <p class="eyebrow">${isEn ? 'RETURN OR COMPLAINT' : 'ZWROT LUB REKLAMACJA'}</p>
            <h2>${isEn ? 'Need help with your purchase?' : 'Potrzebujesz pomocy z zakupem?'}</h2>
            <p class="form-hint">${isEn ? 'Choose quantities for this case; 0 skips an item. One case of each type is supported per order. Approval does not automatically refund your payment.' : 'Wybierz ilości do zgłoszenia; 0 pomija pozycję. Dla zamówienia obsługiwane jest jedno zgłoszenie każdego typu. Zatwierdzenie nie oznacza automatycznego zwrotu płatności.'}</p>
            <form id="return-request-form" class="return-request-form">
                <label>${isEn ? 'Request type' : 'Typ zgłoszenia'}<select id="return-request-type">${physicalReturnEligible ? `<option value="RETURN">${isEn ? 'Return' : 'Zwrot'}</option>` : ''}<option value="COMPLAINT">${isEn ? 'Complaint' : 'Reklamacja'}</option></select></label>
                ${items.map(item => `<label>${escapeHtml(item.productName || 'Produkt')} · ${isEn ? 'quantity' : 'ilość'} (0–${item.quantity})<input class="return-quantity" data-order-item-id="${item.id}" type="number" min="0" max="${item.quantity}" step="1" value="${item.quantity}" required></label>`).join('')}
                <label>${isEn ? 'Reason' : 'Powód'}<input id="return-request-reason" maxlength="120" required placeholder="${isEn ? 'e.g. Product does not meet expectations' : 'np. Produkt nie spełnia oczekiwań'}"></label>
                <label>${isEn ? 'Description' : 'Opis'}<textarea id="return-request-description" maxlength="2000" rows="4" required placeholder="${isEn ? 'Describe your case' : 'Opisz sprawę'}"></textarea></label>
                <button class="primary-button" type="submit">${isEn ? 'Submit request' : 'Wyślij zgłoszenie'}</button>
                <p class="return-request-status" id="return-request-status" role="status"></p>
            </form>
        </section>` : ''}
        ${discount > 0 ? `<p class="order-detail-discount">${isEn ? 'Discount' : 'Rabat'}: −${formatMoney(discount)}${order.promotionCode ? ` · ${isEn ? 'code' : 'kod'} ${escapeHtml(order.promotionCode)}` : ''}</p>` : ''}
        <div class="order-detail-total"><span>${isEn ? 'Total' : 'Razem'}</span><strong>${formatMoney(order.totalPrice)}</strong></div>
        <p class="form-hint">${isOrderPaid(order)
            ? (isEn ? 'Paid items can be reviewed using "Add review" button. Reviews are moderated.' : 'Opłacone produkty możesz ocenić przyciskiem „Dodaj opinię”. Opinia trafi do moderacji.')
            : (isEn ? 'Reviews become available once the order is paid.' : 'Dodanie opinii będzie możliwe po opłaceniu zamówienia.')}</p>`;
    detailCard.hidden = false;
    detailCard.querySelector('#download-invoice')?.addEventListener('click', async () => {
        try { await invoiceDownloads.download(order.id); }
        catch (error) { if (error.name !== 'AbortError') showError(`Nie udało się pobrać faktury: ${error.message}`); }
    });
    detailCard.querySelector('#return-request-form')?.addEventListener('submit', submitReturnRequest);
    backLink.href = 'index.html#top';
}

async function submitReturnRequest(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const status = form.querySelector('#return-request-status');
    const button = form.querySelector('button[type="submit"]');
    const isEn = i18n?.getLanguage() === 'en';
    button.disabled = true; status.textContent = isEn ? 'Submitting request…' : 'Wysyłam zgłoszenie…'; status.className = 'return-request-status';
    try {
        const selections = [...form.querySelectorAll('.return-quantity')].map(input => ({ orderItemId: Number(input.dataset.orderItemId), quantity: Number(input.value), maximum: Number(input.max) }));
        if (selections.some(item => !Number.isSafeInteger(item.quantity) || item.quantity < 0 || item.quantity > item.maximum)
                || !selections.some(item => item.quantity > 0)) throw new Error(isEn ? 'Choose at least one unit from the order.' : 'Wybierz co najmniej jedną sztukę z pozycji zamówienia.');
        const saved = await request('/returns', { method: 'POST', body: JSON.stringify({
            orderId: Number(orderId), customerId: customer.id, customerEmail: customer.email,
            requestType: form.querySelector('#return-request-type').value,
            reason: form.querySelector('#return-request-reason').value.trim(),
            description: form.querySelector('#return-request-description').value.trim(),
            items: selections.filter(item => item.quantity > 0).map(({orderItemId, quantity}) => ({orderItemId, quantity}))
        })});
        status.textContent = isEn ? `Request saved: #${saved.id}. Approval does not automatically refund your payment.`
            : `Zgłoszenie zapisane: #${saved.id}. Zatwierdzenie zgłoszenia nie oznacza automatycznego zwrotu płatności.`;
        status.className = 'return-request-status success'; form.reset();
    } catch (error) { status.textContent = error.message || 'Nie udało się wysłać zgłoszenia.'; status.className = 'return-request-status error'; }
    finally { button.disabled = false; }
}

function renderItem(order, item) {
    const productLink = item.productId
        ? `<a class="secondary-button" href="product.html?id=${encodeURIComponent(item.productId)}">Szczegóły produktu</a>`
        : '';
    const reviewLink = isOrderPaid(order) && item.productId
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

function refreshSession() { return customerSession.refresh(); }
function restoreSession() { return customerSession.restore(); }

async function expireOrderSession(showMessage) {
    customerSession.invalidate();
    invoiceDownloads.cancelAll();
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
    return window.MiniTechApi.baseUrl;
}

function orderStatusLabel(status) {
    if (i18n) return i18n.translateOrderStatus(status);
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
    const isEn = i18n && i18n.getLanguage() === 'en';
    const dict = isEn ? {
        LABEL_CREATED: 'Label created',
        READY_FOR_PICKUP: 'Ready for pickup',
        IN_TRANSIT: 'In transit',
        OUT_FOR_DELIVERY: 'Out for delivery',
        DELIVERED: 'Delivered',
        PICKED_UP: 'Picked up',
        CANCELLED: 'Cancelled'
    } : {
        LABEL_CREATED: 'Etykieta utworzona',
        READY_FOR_PICKUP: 'Gotowe do odbioru',
        IN_TRANSIT: 'W transporcie',
        OUT_FOR_DELIVERY: 'Wydano do doręczenia',
        DELIVERED: 'Doręczona',
        PICKED_UP: 'Odebrana',
        CANCELLED: 'Anulowana'
    };
    return dict[status] || status || (isEn ? 'Unknown' : 'Nieznany');
}

function formatDeliveryDate(value) {
    const isEn = i18n && i18n.getLanguage() === 'en';
    const date = new Date(`${value}T12:00:00`);
    return Number.isNaN(date.getTime())
        ? String(value)
        : date.toLocaleDateString(isEn ? 'en-US' : 'pl-PL', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' });
}

function formatOrderDate(value) {
    const isEn = i18n && i18n.getLanguage() === 'en';
    if (!value) return isEn ? 'Date unavailable' : 'Data niedostępna';
    const date = new Date(value);
    return Number.isNaN(date.getTime())
        ? String(value)
        : date.toLocaleString(isEn ? 'en-US' : 'pl-PL', { dateStyle: 'medium', timeStyle: 'short' });
}

function formatMoney(value) {
    if (i18n) return i18n.formatMoney(value);
    return `${Number(value || 0).toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} zł`;
}

function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>'"]/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[character]));
}

window.addEventListener('minitech:language-changed', () => {
    loadOrderDetail();
});
