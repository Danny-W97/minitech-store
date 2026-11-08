import { i18n } from './i18n.js?v=20261028-a43-32';
import { GUEST_CART_STORAGE_KEY } from './cart-storage.js?v=20261026-1';
import { currentCatalog } from './store-catalog.js?v=20261028-a43-32';
import { clearCheckoutAttempt, showCartPage } from './store-checkout.js?v=20261105-1';
import { cartSubtotal, renderPriceSummary } from './store-delivery.js?v=20261028-a43-32';
import {
    cartArea, cartCheckout, cartCount, cartEmpty, cartItems, cartPreview, cartPreviewCheckout,
    cartPreviewCount, cartPreviewItems, cartPreviewTotal, cartStockWarning, cartThresholdNote,
    cartToggle, cartTotal, promotionCodeFeedback, promotionCodeInput,
} from './store-dom.js?v=20261028-a43-32';
import { showToast } from './store-feedback.js?v=20261028-a43-32';
import { escapeHtml, formatMoney } from './store-format.js?v=20261028-a43-32';
import { LOGIN_REQUIRED_THRESHOLD, cartStore, runtime, state } from './store-state.js?v=20261028-a43-32';

export function handleCartAction(event) {
    const button = event.target.closest('[data-action]');
    if (!button) return;
    const actionCameFromPreview = Boolean(button.closest('#cart-preview'));
    const id = Number(button.dataset.productId);
    const item = state.cart.find(cartItem => cartItem.id === id);
    if (!item) return;
    if (button.dataset.action === 'increase') item.quantity += 1;
    if (button.dataset.action === 'decrease') item.quantity -= 1;
    if (button.dataset.action === 'remove' || item.quantity <= 0) {
        state.cart = state.cart.filter(cartItem => cartItem.id !== id);
    }
    saveCart();
    renderCart();
    if (actionCameFromPreview && state.cart.length) openCartPreview();
}

export function renderCart() {
    const isEn = i18n && i18n.getLanguage() === 'en';
    const quantity = state.cart.reduce((sum, item) => sum + item.quantity, 0);
    const total = state.cart.reduce((sum, item) => sum + item.price * item.quantity, 0);
    const stockIssues = getCartStockIssues();
    cartCount.textContent = quantity;
    cartTotal.textContent = formatMoney(total);
    renderPriceSummary();
    cartEmpty.hidden = state.cart.length > 0;
    cartCheckout.hidden = state.cart.length === 0;
    cartCheckout.disabled = state.cart.length === 0;
    if (cartStockWarning) {
        cartStockWarning.hidden = stockIssues.length === 0;
        cartStockWarning.textContent = stockIssues.length
            ? (isEn ? `Insufficient stock: ${formatCartStockIssues(stockIssues)}. Reduce quantity or remove item from cart.` : `Brak wystarczającej liczby produktów: ${formatCartStockIssues(stockIssues)}. Zmniejsz ilość lub usuń pozycję z koszyka.`)
            : '';
    }
    updateCartThresholdNote();
    cartItems.innerHTML = state.cart.map(item => {
        const stock = stockFor(item.id);
        const atLimit = Number.isInteger(stock?.availableQuantity) && item.quantity >= stock.availableQuantity;
        const stockInfo = Number.isInteger(stock?.availableQuantity) ? (isEn ? ` · stock: ${stock.availableQuantity} pcs` : ` · magazyn: ${stock.availableQuantity} szt.`) : '';
        return `
        <div class="cart-row">
            <div>
                <div class="cart-row-name">${escapeHtml(item.name)}</div>
                <div class="cart-row-meta">${formatMoney(item.price)} · ${escapeHtml(item.sku || '')}${stockInfo}</div>
            </div>
            <div class="cart-row-actions">
                <button class="quantity-button" data-action="decrease" data-product-id="${item.id}" type="button">−</button>
                <span class="quantity-value">${item.quantity}</span>
                <button class="quantity-button" data-action="increase" data-product-id="${item.id}" type="button" ${atLimit ? 'disabled' : ''}>+</button>
            </div>
        </div>`;
    }).join('');
    renderCartPreview();
}

export function updateCartThresholdNote() {
    if (!cartThresholdNote) return;
    const isEn = i18n && i18n.getLanguage() === 'en';
    if (!state.cart.length) {
        cartThresholdNote.textContent = '';
    } else if (cartSubtotal() > LOGIN_REQUIRED_THRESHOLD && !state.customer) {
        cartThresholdNote.textContent = isEn
            ? 'Orders over 1,000 PLN require sign in or creating an account.'
            : 'Zamówienie powyżej 1 000 zł wymaga zalogowania lub założenia konta.';
    } else if (state.customer) {
        cartThresholdNote.textContent = isEn
            ? `Order will be saved to account ${state.customer.email}.`
            : `Zamówienie zostanie zapisane na koncie ${state.customer.email}.`;
    } else {
        cartThresholdNote.textContent = isEn
            ? 'Up to 1,000 PLN you can purchase as a guest — no account required.'
            : 'Do 1 000 zł możesz kupić jako gość — konto nie jest wymagane.';
    }
}

export function renderCartPreview() {
    if (!cartPreview || !cartPreviewItems) return;
    const quantity = state.cart.reduce((sum, item) => sum + item.quantity, 0);
    const total = state.cart.reduce((sum, item) => sum + item.price * item.quantity, 0);
    if (cartPreviewCount) cartPreviewCount.textContent = quantity;
    if (cartPreviewTotal) cartPreviewTotal.textContent = formatMoney(total);
    if (cartPreviewCheckout) cartPreviewCheckout.disabled = state.cart.length === 0;

    if (!state.cart.length) {
        cartPreviewItems.innerHTML = '<p class="cart-preview-empty">Koszyk jest pusty. Dodaj produkt z katalogu.</p>';
        return;
    }

    cartPreviewItems.innerHTML = state.cart.map(item => {
        const product = state.products.find(candidate => Number(candidate.id) === Number(item.id));
        const stock = stockFor(item.id);
        const atLimit = Number.isInteger(stock?.availableQuantity) && item.quantity >= stock.availableQuantity;
        const symbol = escapeHtml((product?.category || 'MT').slice(0, 3).toUpperCase());
        const visual = product?.imageUrl
            ? `<img src="${escapeHtml(product.imageUrl)}" alt="" loading="lazy">`
            : `<span>${symbol}</span>`;
        return `
            <div class="cart-preview-item">
                <a class="cart-preview-item-visual" href="product.html?id=${encodeURIComponent(item.id)}" aria-label="Szczegóły: ${escapeHtml(item.name)}">${visual}</a>
                <div class="cart-preview-item-info">
                    <a class="cart-preview-item-name" href="product.html?id=${encodeURIComponent(item.id)}">${escapeHtml(item.name)}</a>
                    <p class="cart-preview-item-meta">${item.quantity} szt. · ${escapeHtml(item.sku || '')}</p>
                    <p class="cart-preview-item-price">${formatMoney(item.price * item.quantity)}</p>
                </div>
                <div class="cart-preview-item-actions">
                    <div class="cart-preview-quantity">
                        <button class="quantity-button" data-action="decrease" data-product-id="${item.id}" type="button" aria-label="Zmniejsz ilość">−</button>
                        <span class="quantity-value">${item.quantity}</span>
                        <button class="quantity-button" data-action="increase" data-product-id="${item.id}" type="button" ${atLimit ? 'disabled' : ''} aria-label="Zwiększ ilość">+</button>
                    </div>
                    <button class="cart-preview-remove" data-action="remove" data-product-id="${item.id}" type="button">Usuń</button>
                </div>
            </div>`;
    }).join('');
}

export function openCartPreview() {
    if (!cartPreview || !cartToggle) return;
    if (runtime.cartPreviewCloseTimer !== null) window.clearTimeout(runtime.cartPreviewCloseTimer);
    cartPreview.hidden = false;
    cartToggle.setAttribute('aria-expanded', 'true');
}

export function closeCartPreview() {
    if (!cartPreview || !cartToggle) return;
    const restoreFocus = cartPreview.contains(document.activeElement);
    if (runtime.cartPreviewCloseTimer !== null) {
        window.clearTimeout(runtime.cartPreviewCloseTimer);
        runtime.cartPreviewCloseTimer = null;
    }
    cartPreview.hidden = true;
    cartToggle.setAttribute('aria-expanded', 'false');
    if (restoreFocus) cartToggle.focus({ preventScroll: true });
}

export function scheduleCloseCartPreview() {
    if (cartArea?.matches(':focus-within, :hover')) return;
    if (runtime.cartPreviewCloseTimer !== null) window.clearTimeout(runtime.cartPreviewCloseTimer);
    runtime.cartPreviewCloseTimer = window.setTimeout(() => {
        runtime.cartPreviewCloseTimer = null;
        if (!cartArea?.matches(':focus-within, :hover')) closeCartPreview();
    }, 160);
}

export function toggleCartPreview() {
    if (!cartPreview) return;
    if (cartPreview.hidden) openCartPreview();
    else closeCartPreview();
}

export function clearCart() {
    state.cart = [];
    clearCheckoutAttempt();
    saveCart();
    renderCart();
}

export function stockFor(productId) {
    return state.inventory[productId] || null;
}

export async function refreshCartInventory() {
    const productIds = [...new Set(state.cart.map(item => Number(item.id)).filter(Number.isFinite))];
    if (!productIds.length) return;
    const context = runtime.catalogContext;
    if (!context?.reader) throw new Error('Poczekaj na załadowanie katalogu i spróbuj ponownie.');
    await context.reader.load(productIds, { force: true });
    if (!currentCatalog(context)) throw new Error('Katalog został odświeżony. Sprawdź koszyk ponownie.');
}

export function getCartStockIssues() {
    return state.cart.map(item => {
        const availableQuantity = Number(stockFor(item.id)?.availableQuantity);
        if (!Number.isFinite(availableQuantity) || item.quantity <= availableQuantity) return null;
        return { item, availableQuantity };
    }).filter(Boolean);
}

export function getSoldOutCartItems() {
    return state.cart.filter(item => Number(stockFor(item.id)?.availableQuantity) === 0);
}

export function formatCartStockIssues(stockIssues) {
    return stockIssues.map(({ item, availableQuantity }) =>
        `${item.name} — dostępne ${Math.max(0, Math.trunc(availableQuantity))} szt., w koszyku ${item.quantity}`
    ).join('; ');
}

export function insufficientStockError(stockIssues) {
    const error = new Error(`Brak wystarczającej liczby produktów: ${formatCartStockIssues(stockIssues)}. Zmniejsz ilość lub usuń pozycję z koszyka.`);
    error.code = 'INSUFFICIENT_STOCK';
    return error;
}

export function zeroQuantityCartError(items) {
    const names = items.map(item => item.name).join(', ');
    const subject = items.length > 1 ? `Pozycje ${names} miały` : `Pozycja ${names} miała`;
    const ending = items.length > 1 ? 'zostały usunięte' : 'została usunięta';
    const error = new Error(`${subject} ilość 0 i ${ending} z koszyka. Sprawdź aktualny koszyk.`);
    error.code = 'ZERO_QUANTITY_REMOVED';
    return error;
}

export function resetCheckoutAfterCartChange() {
    state.coupon = null;
    state.deliveryQuote = null;
    if (promotionCodeInput) promotionCodeInput.value = '';
    if (promotionCodeFeedback) promotionCodeFeedback.textContent = '';
    clearCheckoutAttempt();
    saveCart();
    renderCart();
    showCartPage();
}

export function soldOutCartError(items) {
    const names = items.map(item => item.name).join(', ');
    const error = new Error(`Produkt${items.length > 1 ? 'y' : ''} ${names} został${items.length > 1 ? 'y' : ''} wyprzedan${items.length > 1 ? 'e' : 'y'} i usunięt${items.length > 1 ? 'e' : 'y'} z koszyka. Sprawdź aktualny koszyk.`);
    error.code = 'SOLD_OUT_REMOVED';
    return error;
}

export async function ensureCartStockAvailable() {
    const zeroQuantityItems = state.cart.filter(item => Number(item.quantity) <= 0);
    if (zeroQuantityItems.length) {
        state.cart = state.cart.filter(item => !zeroQuantityItems.includes(item));
        const error = zeroQuantityCartError(zeroQuantityItems);
        resetCheckoutAfterCartChange();
        showToast(error.message, 7000);
        throw error;
    }
    await refreshCartInventory();
    const soldOutItems = getSoldOutCartItems();
    if (soldOutItems.length) {
        state.cart = state.cart.filter(item => !soldOutItems.includes(item));
        const error = soldOutCartError(soldOutItems);
        resetCheckoutAfterCartChange();
        showToast(error.message, 7000);
        throw error;
    }
    const stockIssues = getCartStockIssues();
    if (stockIssues.length) {
        renderCart();
        throw insufficientStockError(stockIssues);
    }
}

export function cartStorageKey(customer) {
    return cartStore.key(customer);
}

export function clearGuestCartStorage() {
    cartStore.clearGuest();
    if (runtime.activeCartStorageKey === GUEST_CART_STORAGE_KEY) state.cart = [];
}

export function switchCartScope(customer) {
    const nextStorageKey = cartStorageKey(customer);
    if (nextStorageKey === runtime.activeCartStorageKey) return;
    saveCart();
    runtime.activeCartStorageKey = nextStorageKey;
    state.cart = loadCart(runtime.activeCartStorageKey);
    renderCart();
}

export function loadCart(storageKey = runtime.activeCartStorageKey) {
    return cartStore.load(storageKey);
}

export function saveCompareIds() {
    try {
        localStorage.setItem('minitech-compare', JSON.stringify([...state.compareIds]));
    } catch {
        // A blocked comparison preference must not prevent catalog/cart rendering.
    }
}

export function saveCart() {
    cartStore.save(runtime.activeCartStorageKey, state.cart);
}
