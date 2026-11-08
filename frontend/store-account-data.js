import { i18n } from './i18n.js?v=20261028-a43-32';
import {
    closeAccountMenu, fillCustomer, openAuthModal, toggleWishlist, updateCustomerSummary,
} from './store-account.js?v=20261028-a43-32';
import { renderProducts } from './store-catalog.js?v=20261028-a43-32';
import {
    authForgotPassword, authForm, authLogout, authMfaPanel, authModal, authModeSelector,
    authOrdersList, authOrdersPanel, authOrdersStatus, authProfileCity, authProfileCountry,
    authProfileEmail, authProfileFirstName, authProfileLastName, authProfilePanel, authProfilePhone,
    authProfilePostalCode, authProfileSave, authProfileStatus, authProfileStreet, authResetForm,
    authSecurityPanel, authSubmit, authWishlistList, authWishlistPanel, authWishlistStatus, dialogs,
} from './store-dom.js?v=20261028-a43-32';
import { showToast } from './store-feedback.js?v=20261028-a43-32';
import { escapeHtml, formatMoney, formatOrderDate } from './store-format.js?v=20261028-a43-32';
import { renderHighlights } from './store-highlights.js?v=20261028-a43-32';
import { api, invoiceDownloads } from './store-services.js?v=20261028-a43-32';
import { runtime, state } from './store-state.js?v=20261028-a43-32';

export function openProfilePanel() {
    if (!state.customer) {
        openAuthModal();
        return;
    }
    closeAccountMenu();
    dialogs.open(authModal);
    runtime.passwordResetMode = false;
    authForm.hidden = true;
    authModeSelector.hidden = true;
    authForgotPassword.hidden = true;
    authResetForm.hidden = true;
    authMfaPanel.hidden = true;
    authSecurityPanel.hidden = true;
    authProfilePanel.hidden = false;
    authOrdersPanel.hidden = true;
    authWishlistPanel.hidden = true;
    authSubmit.hidden = true;
    authLogout.hidden = true;
    const isEn = i18n && i18n.getLanguage() === 'en';
    document.querySelector('#auth-title').textContent = isEn ? 'Profile' : 'Profil';
    fillProfileForm(state.customer);
    authProfileStatus.textContent = '';
    window.dispatchEvent(new CustomEvent('minitech:profile-opened'));
}

export async function openOrderHistoryPanel() {
    if (!state.customer) {
        openAuthModal();
        return;
    }
    closeAccountMenu();
    dialogs.open(authModal);
    runtime.passwordResetMode = false;
    authForm.hidden = true;
    authModeSelector.hidden = true;
    authForgotPassword.hidden = true;
    authResetForm.hidden = true;
    authMfaPanel.hidden = true;
    authSecurityPanel.hidden = true;
    authProfilePanel.hidden = true;
    authOrdersPanel.hidden = false;
    authWishlistPanel.hidden = true;
    authSubmit.hidden = true;
    authLogout.hidden = true;
    const isEn = i18n && i18n.getLanguage() === 'en';
    document.querySelector('#auth-title').textContent = isEn ? 'My Orders' : 'Moje zamówienia';
    await loadOrderHistory();
}

export async function openWishlistPanel() {
    if (!state.customer) {
        openAuthModal();
        return;
    }
    closeAccountMenu();
    dialogs.open(authModal);
    runtime.passwordResetMode = false;
    authForm.hidden = true;
    authModeSelector.hidden = true;
    authForgotPassword.hidden = true;
    authResetForm.hidden = true;
    authMfaPanel.hidden = true;
    authSecurityPanel.hidden = true;
    authProfilePanel.hidden = true;
    authOrdersPanel.hidden = true;
    authWishlistPanel.hidden = false;
    authSubmit.hidden = true;
    authLogout.hidden = true;
    const isEn = i18n && i18n.getLanguage() === 'en';
    document.querySelector('#auth-title').textContent = isEn ? 'My Wishlist' : 'Moje ulubione';
    await loadWishlist();
}

export async function loadWishlist() {
    if (!state.customer) {
        state.wishlist = new Set();
        renderWishlistPanel();
        return;
    }
    const owner=state.customer.id;
    authWishlistStatus.textContent = 'Ładowanie zapisanych produktów…';
    authWishlistStatus.style.color = '';
    try {
        const rows = await api(`/wishlists/customer/${encodeURIComponent(state.customer.id)}`);
        if(state.customer?.id!==owner)return;
        state.wishlist = new Set((Array.isArray(rows) ? rows : []).map(item => Number(item.productId)));
        renderWishlistPanel();
        renderProducts();
        renderHighlights();
    } catch (error) {
        if(state.customer?.id!==owner||error.name==='AbortError')return;
        authWishlistStatus.textContent = `Nie udało się pobrać ulubionych: ${error.message}`;
        authWishlistStatus.style.color = '#bd331d';
    }
}

export function renderWishlistPanel() {
    if (!authWishlistList || !authWishlistStatus) return;
    const products = [...state.wishlist]
        .map(productId => state.products.find(product => Number(product.id) === Number(productId)))
        .filter(Boolean);
    if (!products.length) {
        authWishlistList.innerHTML = '<p class="auth-wishlist-empty">Nie masz jeszcze zapisanych produktów.</p>';
        if (!authWishlistStatus.textContent || authWishlistStatus.textContent.startsWith('Ładowanie')) {
            authWishlistStatus.textContent = '';
        }
        return;
    }
    authWishlistList.innerHTML = products.map(product => `
        <article class="auth-wishlist-card">
            <div>
                <a href="product.html?id=${encodeURIComponent(product.id)}">${escapeHtml(product.name)}</a>
                <p>${escapeHtml(product.sku || product.model || product.category || '')}</p>
            </div>
            <button class="secondary-button" type="button" data-action="wishlist-remove" data-product-id="${product.id}">Usuń</button>
        </article>`).join('');
    authWishlistStatus.textContent = `${products.length} zapisanych produktów.`;
}

export function handleWishlistPanelAction(event) {
    const button = event.target.closest('[data-action="wishlist-remove"]');
    if (!button) return;
    toggleWishlist(button.dataset.productId, button).catch(() => {});
}

export async function loadOrderHistory() {
    const generation = ++runtime.orderHistoryGeneration;
    const customerId = state.customer?.id;
    authOrdersStatus.textContent = 'Ładowanie historii zamówień…';
    authOrdersStatus.style.color = '';
    authOrdersList.innerHTML = '';
    let count = 0;
    async function load(cursor = '0') {
        const moreButton = authOrdersList.querySelector('[data-orders-more]');
        if (moreButton) moreButton.disabled = true;
        try {
            const page = await api(`/orders/customer/${encodeURIComponent(customerId)}?size=20&cursor=${encodeURIComponent(cursor)}`, { paged: true });
            if (generation !== runtime.orderHistoryGeneration || state.customer?.id !== customerId) return;
            moreButton?.remove();
            const orders = Array.isArray(page.items) ? page.items : [];
            count += orders.length;
            authOrdersList.insertAdjacentHTML('beforeend', orders.map(orderHistoryCard).join(''));
            authOrdersStatus.textContent = count ? `Wyświetlono: ${count} zamówień. Najnowsze są na górze.` : 'Nie masz jeszcze zamówień zapisanych na koncie.';
            authOrdersList.querySelectorAll('[data-invoice-order]').forEach(button => {
                button.onclick = async () => {
                    try { await invoiceDownloads.download(button.dataset.invoiceOrder); }
                    catch (error) { if (error.name !== 'AbortError') authOrdersStatus.textContent = `Nie udało się pobrać faktury: ${error.message}`; }
                };
            });
            if (page.hasMore) {
                const button = document.createElement('button');
                button.type = 'button'; button.className = 'secondary-button'; button.dataset.ordersMore = '';
                button.textContent = 'Pokaż starsze zamówienia';
                button.onclick = () => load(page.nextCursor);
                authOrdersList.append(button);
            }
        } catch (error) {
            if (generation !== runtime.orderHistoryGeneration || state.customer?.id !== customerId) return;
            authOrdersStatus.textContent = `Nie udało się pobrać historii: ${error.message}`;
            authOrdersStatus.style.color = '#bd331d';
        } finally {
            if (moreButton) moreButton.disabled = false;
        }
    }
    await load();
}

export function orderHistoryCard(order) {
    const isEn = i18n && i18n.getLanguage() === 'en';
    const items = Array.isArray(order.items) ? order.items : [];
    const itemSummary = items.map(item => `${item.productName} × ${item.quantity}`).join(', ');
    const deliverySummary = order.deliveryMethod
        ? `${isEn ? 'Delivery' : 'Dostawa'}: ${order.deliveryMethod} · ${formatMoney(order.deliveryCost)}`
        : '';
    const invoiceLabel = isEn ? 'Invoice' : 'Faktura';
    const downloadLabel = isEn ? 'download PDF' : 'pobierz PDF';
    const noInvoiceLabel = isEn ? 'not requested' : 'niezamówiona';
    return `
        <article class="auth-order-card">
            <div class="auth-order-heading">
                <strong>${isEn ? 'Order' : 'Zamówienie'} #${escapeHtml(order.id)}</strong>
                <span class="auth-order-status">${escapeHtml(orderStatusLabel(order.status))}</span>
            </div>
            <p>${escapeHtml(formatOrderDate(order.createdAt))}</p>
            ${order.paymentState === 'COLLECTION_DUE' ? `<p>${isEn ? 'Cash payment due on handover.' : 'Płatność przy odbiorze oczekuje na rozliczenie.'}</p>` : ''}
            ${order.lifecycleReviewRequired ? `<p>${isEn ? 'Store support is verifying this order.' : 'Obsługa sklepu weryfikuje to zamówienie.'}</p>` : ''}
            <p class="auth-order-items">${escapeHtml(itemSummary || (isEn ? 'No items' : 'Brak pozycji'))}</p>
            ${deliverySummary ? `<p>${escapeHtml(deliverySummary)}</p>` : ''}
            <p>${invoiceLabel}: ${order.invoiceRequested
                ? order.lifecycleReviewRequired ? (isEn ? 'after verification' : 'po weryfikacji')
                    : order.paymentState === 'COLLECTION_DUE' ? (isEn ? 'after cash collection' : 'po rozliczeniu pobrania')
                    : `<button type="button" class="secondary-button" data-invoice-order="${order.id}">${downloadLabel}</button>`
                : noInvoiceLabel}</p>
            <strong class="auth-order-total">${formatMoney(order.totalPrice)}</strong>
            <div class="auth-order-actions">
                <a class="secondary-button" href="order-detail.html?id=${encodeURIComponent(order.id)}">${isEn ? 'Order details' : 'Szczegóły zamówienia'}</a>
            </div>
        </article>`;
}

export function orderStatusLabel(status) {
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

export function fillProfileForm(customer) {
    authProfileEmail.value = customer?.email || '';
    authProfileFirstName.value = customer?.firstName || '';
    authProfileLastName.value = customer?.lastName || '';
    authProfilePhone.value = customer?.phoneNumber || '';
    authProfileStreet.value = customer?.street || '';
    authProfilePostalCode.value = customer?.postalCode || '';
    authProfileCity.value = customer?.city || '';
    authProfileCountry.value = customer?.country || 'Polska';
}

export async function saveProfile(event) {
    event.preventDefault();
    if (!state.customer) return;
    authProfileSave.disabled = true;
    authProfileStatus.textContent = 'Zapisywanie danych profilu...';
    authProfileStatus.style.color = '';
    try {
        const customer = await api(`/customers/${state.customer.id}/profile`, {
            method: 'PUT',
            body: JSON.stringify({
                firstName: authProfileFirstName.value.trim(),
                lastName: authProfileLastName.value.trim(),
                phoneNumber: authProfilePhone.value.trim(),
                street: authProfileStreet.value.trim(),
                postalCode: authProfilePostalCode.value.trim(),
                city: authProfileCity.value.trim(),
                country: authProfileCountry.value.trim()
            })
        });
        state.customer = customer;
        fillProfileForm(customer);
        fillCustomer(customer);
        updateCustomerSummary();
        authProfileStatus.textContent = 'Dane profilu zostały zapisane.';
        authProfileStatus.style.color = '#087d6e';
        showToast('Profil został zaktualizowany');
    } catch (error) {
        authProfileStatus.textContent = `Nie udało się zapisać profilu: ${error.message}`;
        authProfileStatus.style.color = '#bd331d';
    } finally {
        authProfileSave.disabled = false;
    }
}
