import { i18n } from './i18n.js?v=20261028-a43-32';
import { createCustomerSession, canRefreshCustomerPath as canRefresh } from './customer-session.js?v=20261027-1';
import { createStoreClient } from './store-client.js?v=20261026-2';
import { createCartStorage } from './cart-storage.js?v=20261026-1';

const API_BASE = getApiBase();
const cartStore = createCartStorage({ storage: () => localStorage });
const sessionGuard = window.MiniTechSession;
const productQuery = new URLSearchParams(window.location.search);
const productId = productQuery.get('id');
const reviewOrderId = productQuery.get('reviewOrder');
const loading = document.querySelector('#product-loading');
const detail = document.querySelector('#product-detail');
const errorPanel = document.querySelector('#product-error');
const errorMessage = document.querySelector('#product-error-message');
const addButton = document.querySelector('#product-add');
const productImage = document.querySelector('#product-image');
const productSymbol = document.querySelector('#product-symbol');
const reviewsSection = document.querySelector('#product-reviews');
const reviewSummary = document.querySelector('#review-summary');
const reviewList = document.querySelector('#review-list');
const reviewForm = document.querySelector('#review-form');
const reviewOrder = document.querySelector('#review-order');
const reviewRating = document.querySelector('#review-rating');
const reviewTitle = document.querySelector('#review-title');
const reviewContent = document.querySelector('#review-content');
const reviewSubmit = document.querySelector('#review-submit');
const reviewStatus = document.querySelector('#review-status');
const reviewLoginHint = document.querySelector('#review-login-hint');
const recommendationsSection = document.querySelector('#recommendations');
const recommendationsStatus = document.querySelector('#recommendations-status');
const recommendationGrid = document.querySelector('#recommendation-grid');
const lowestPriceElement = document.querySelector('#product-lowest-price');
let product = null;
let availableQuantity = null;
let lowestPrice30Days = null;
let accessToken = null;
let customer = null;
const customerSession = createCustomerSession({baseUrl: API_BASE, guard: sessionGuard, expire: expireProductSession,
    onSession(session) {
        accessToken = session.accessToken; customer = session.customer;
        sessionGuard?.start(() => expireProductSession(true)); sessionGuard?.touch();
    },
    onFailure() {accessToken = null; customer = null;},
});
const storeClient = createStoreClient({
    baseUrl: API_BASE, getAccessToken: () => accessToken,
    refreshSession, canRefresh
});
const request = (...args) => storeClient.request(...args);

cartStore.initialize();
loadProduct();

async function loadProduct() {
    if (!productId || !/^\d+$/.test(productId)) {
        showError('Brak poprawnego identyfikatora produktu.');
        return;
    }
    try {
        await restoreSession();
        product = await request(`/products/${encodeURIComponent(productId)}`);
        try {
            const stock = await request(`/inventory/items/${encodeURIComponent(productId)}`);
            availableQuantity = Number.isInteger(stock?.availableQuantity) ? stock.availableQuantity : null;
        } catch {
            availableQuantity = null;
        }
        renderProduct();
        rememberRecentlyViewed(product);
        await Promise.all([loadLowestPrice(), loadReviews(), loadRecommendations(), loadReviewEligibility()]);
        renderProduct();
    } catch (error) {
        showError(error.message);
    }
}

async function loadLowestPrice() {
    try {
        const response = await request(`/promotions/history/${encodeURIComponent(productId)}/lowest?days=30`);
        const value = response?.lowestPrice == null ? NaN : Number(response.lowestPrice);
        lowestPrice30Days = response?.historyComplete === true && Number.isFinite(value) ? value : null;
    } catch {
        lowestPrice30Days = null;
    }
}

async function loadRecommendations() {
    if (!recommendationsSection || !recommendationGrid) return;
    try {
        const recommendations = await request(`/recommendations/products/${encodeURIComponent(productId)}?limit=4`);
        if (!Array.isArray(recommendations) || !recommendations.length) return;
        recommendationGrid.innerHTML = recommendations.map(renderRecommendation).join('');
        recommendationsSection.hidden = false;
    } catch (error) {
        recommendationsSection.hidden = false;
        recommendationsStatus.textContent = 'Rekomendacje są chwilowo niedostępne.';
        recommendationGrid.innerHTML = `<p class="catalog-message">${escapeHtml(error.message)}</p>`;
    }
}

function renderRecommendation(item) {
    const visual = item.imageUrl
        ? `<img src="${escapeHtml(item.imageUrl)}" alt="${escapeHtml(item.name)}" loading="lazy">`
        : `<span>${escapeHtml((item.category || 'TECH').slice(0, 3).toUpperCase())}</span>`;
    return `<article class="recommendation-card">
        <a class="recommendation-visual" href="product.html?id=${encodeURIComponent(item.productId)}">${visual}</a>
        <p class="product-category">${escapeHtml(item.category || 'TECH')}</p>
        <h3><a href="product.html?id=${encodeURIComponent(item.productId)}">${escapeHtml(item.name)}</a></h3>
        <p class="product-meta">${escapeHtml(item.manufacturer || '')} · ${escapeHtml(item.model || '')}</p>
        <strong class="recommendation-price">${formatMoney(item.price)}</strong>
        <p class="recommendation-reason">${escapeHtml(item.reason || 'Polecane z aktualnej oferty.')}</p>
    </article>`;
}

function renderProduct() {
    const isEn = i18n && i18n.getLanguage() === 'en';
    const category = product.category || 'TECH';
    const translatedCategory = i18n ? i18n.translateCategory(category) : category;
    document.title = `${product.name} — MiniTech Store`;
    document.querySelector('#product-category').textContent = translatedCategory;
    productSymbol.textContent = category.slice(0, 3).toUpperCase();
    if (product.imageUrl) {
        productImage.src = product.imageUrl;
        productImage.alt = product.name;
        productImage.hidden = false;
        productSymbol.hidden = true;
    } else {
        productImage.hidden = true;
        productSymbol.hidden = false;
    }
    const noData = isEn ? 'No data' : 'Brak danych';
    document.querySelector('#product-name').textContent = product.name;
    document.querySelector('#product-manufacturer').textContent = product.manufacturer || noData;
    document.querySelector('#product-model').textContent = product.model || noData;
    document.querySelector('#product-sku').textContent = product.sku || noData;
    document.querySelector('#product-price').textContent = formatMoney(product.price);
    const regularPriceElement = document.querySelector('#product-regular-price');
    const offerElement = document.querySelector('#product-offer');
    const hasDiscount = Number(product.discountPercent) > 0 && Number(product.regularPrice) > Number(product.price);
    regularPriceElement.hidden = !hasDiscount;
    regularPriceElement.textContent = hasDiscount ? formatMoney(product.regularPrice) : '';
    offerElement.hidden = !hasDiscount;
    offerElement.textContent = hasDiscount
        ? (isEn ? `Daily deal: -${Number(product.discountPercent)}%. Rotates every 24 hours.` : `Promocja dnia: -${Number(product.discountPercent)}%. Oferta rotuje co 24 godziny.`)
        : (product.newProduct ? (isEn ? 'New arrival in today\'s rotation.' : 'Nowość w dzisiejszej rotacji katalogu.') : '');
    if (lowestPriceElement) {
        lowestPriceElement.hidden = false;
        lowestPriceElement.textContent = Number.isFinite(lowestPrice30Days)
            ? `${i18n?.t('lowestPrice30DaysPrefix', isEn ? 'Lowest price in the last 30 days:' : 'Najniższa cena z ostatnich 30 dni:')} ${formatMoney(lowestPrice30Days)}`
            : i18n?.t('priceHistoryIncomplete', isEn ? 'Full 30-day price history is not available yet.' : 'Brak pełnej historii cen z 30 dni.')
                || (isEn ? 'Full 30-day price history is not available yet.' : 'Brak pełnej historii cen z 30 dni.');
    }
    const soldOut = availableQuantity === 0;
    const stock = document.querySelector('#product-stock');
    stock.textContent = Number.isInteger(availableQuantity)
        ? (soldOut ? (isEn ? 'Out of stock' : 'Brak w magazynie') : (isEn ? `In stock: ${availableQuantity} pcs` : `Dostępne: ${availableQuantity} szt.`))
        : (isEn ? 'Stock information unavailable' : 'Stan magazynowy niedostępny');
    stock.classList.toggle('empty', soldOut);
    addButton.disabled = soldOut;
    addButton.textContent = soldOut ? (isEn ? 'Out of stock' : 'Brak produktu') : (isEn ? 'Add to cart' : 'Dodaj do koszyka');
    const paramEntries = Object.entries(product.technicalParameters || {});
    document.querySelector('#product-specs').innerHTML = paramEntries
        .map(([key, value]) => {
            const label = i18n ? i18n.translateParamKey(key) : key;
            return `<dt>${escapeHtml(label)}</dt><dd>${escapeHtml(String(value))}</dd>`;
        })
        .join('') || (isEn ? '<dt>Information</dt><dd>No additional parameters.</dd>' : '<dt>Informacja</dt><dd>Brak dodatkowych parametrów.</dd>');
    loading.hidden = true;
    detail.hidden = false;
}

addButton.addEventListener('click', () => {
    if (!product || addButton.disabled) return;
    const cart = loadCart();
    const existing = cart.find(item => String(item.id) === String(product.id));
    if (existing) {
        if (Number.isInteger(availableQuantity) && existing.quantity >= availableQuantity) {
            addButton.disabled = true;
            addButton.textContent = 'Limit osiągnięty';
            return;
        }
        existing.quantity += 1;
    } else {
        cart.push({ id: product.id, name: product.name, sku: product.sku, price: Number(product.price), quantity: 1 });
    }
    saveCart(cart);
    addButton.textContent = 'Dodano do koszyka';
    window.setTimeout(() => { addButton.textContent = 'Dodaj do koszyka'; }, 1600);
});

reviewForm.addEventListener('submit', async event => {
    event.preventDefault();
    if (!customer || !product || !reviewOrder.value) return;
    setReviewStatus('Wysyłanie opinii…');
    reviewSubmit.disabled = true;
    try {
        await request('/reviews', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                customerId: customer.id,
                orderId: Number(reviewOrder.value),
                productId: Number(product.id),
                rating: Number(reviewRating.value),
                title: reviewTitle.value.trim(),
                content: reviewContent.value.trim()
            })
        });
        reviewForm.reset();
        reviewRating.value = '5';
        setReviewStatus('Opinia została wysłana i czeka na moderację.', 'success');
    } catch (error) {
        setReviewStatus(error.message || 'Nie udało się wysłać opinii.', 'error');
    } finally {
        reviewSubmit.disabled = false;
    }
});


function refreshSession() { return customerSession.refresh(); }
function restoreSession() { return customerSession.restore(); }

async function expireProductSession(showMessage) {
    customerSession.invalidate();
    storeClient.invalidate();
    const revocation = fetch(`${API_BASE}/customers/logout`, {
        method: 'POST', credentials: 'include', cache: 'no-store', signal: AbortSignal.timeout(5000)
    }).catch(() => {});
    sessionGuard?.clear();
    accessToken = null;
    customer = null;
    if (reviewForm) reviewForm.hidden = true;
    if (reviewLoginHint) {
        reviewLoginHint.hidden = false;
        reviewLoginHint.textContent = showMessage
            ? 'Sesja wygasła po 30 minutach bezczynności. Zaloguj się ponownie w sklepie.'
            : 'Zaloguj się, aby wystawić opinię o kupionym produkcie.';
    }
    await revocation;
}

function pageMoreButton(id, parent, label, action) {
    let button = document.getElementById(id);
    if (!button) {
        button = document.createElement('button'); button.id = id; button.type = 'button';
        button.className = 'secondary-button'; parent.after(button);
    }
    button.textContent = label; button.hidden = !action; button.disabled = false;
    button.onclick = action;
    return button;
}

async function loadReviews() {
    reviewsSection.hidden = false;
    reviewList.innerHTML = '';
    pageMoreButton('reviews-more', reviewList, '', null);
    async function load(cursor = '0') {
        const button = document.getElementById('reviews-more');
        button.disabled = true;
        try {
            const [page, summary] = await Promise.all([
                request(`/reviews/products/${encodeURIComponent(productId)}?size=20&cursor=${encodeURIComponent(cursor)}`, { paged: true }),
                cursor === '0' ? request(`/reviews/products/${encodeURIComponent(productId)}/summary`) : Promise.resolve(null)
            ]);
            if (summary) {
                const count = Number(summary.publishedCount || 0);
                reviewSummary.textContent = count ? `${Number(summary.averageRating || 0).toFixed(2)} / 5 · ${count} ${count === 1 ? 'opinia' : 'opinii'}` : 'Brak opinii — bądź pierwszy.';
            }
            const reviews = Array.isArray(page.items) ? page.items : [];
            reviewList.insertAdjacentHTML('beforeend', reviews.map(renderReview).join(''));
            if (cursor === '0' && !reviews.length) reviewList.innerHTML = '<p class="product-review-empty">Ten produkt nie ma jeszcze opublikowanych opinii.</p>';
            pageMoreButton('reviews-more', reviewList, 'Pokaż starsze opinie', page.hasMore ? () => load(page.nextCursor) : null);
        } catch (error) {
            reviewSummary.textContent = `Nie udało się pobrać opinii: ${error.message}`;
        } finally { button.disabled = false; }
    }
    await load();
}

async function loadReviewEligibility() {
    if (!customer) { reviewForm.hidden = true; reviewLoginHint.hidden = false; return; }
    const customerId = customer.id;
    reviewOrder.innerHTML = '';
    pageMoreButton('review-orders-more', reviewOrder, '', null);
    async function load(cursor = '0') {
        const button = document.getElementById('review-orders-more');
        button.disabled = true;
        try {
            const page = await request(`/orders/customer/${encodeURIComponent(customerId)}?status=PAID&productId=${encodeURIComponent(product.id)}&size=20&cursor=${encodeURIComponent(cursor)}`, { paged: true });
            if (customer?.id !== customerId) return;
            const orders = Array.isArray(page.items) ? page.items : [];
            reviewOrder.insertAdjacentHTML('beforeend', orders.map(order => `<option value="${escapeHtml(order.id)}">Zamówienie #${escapeHtml(order.id)} · ${formatMoney(order.totalPrice)}</option>`).join(''));
            const eligible = reviewOrder.options.length > 0;
            reviewForm.hidden = !eligible; reviewLoginHint.hidden = eligible;
            reviewLoginHint.textContent = eligible ? '' : 'Nie znaleziono opłaconego zamówienia z tym produktem. Po zakupie będzie można dodać opinię.';
            if (Array.from(reviewOrder.options).some(option => option.value === String(reviewOrderId))) reviewOrder.value = reviewOrderId;
            pageMoreButton('review-orders-more', reviewOrder, 'Pokaż starsze zakupy', page.hasMore ? () => load(page.nextCursor) : null);
        } catch (error) {
            if (customer?.id !== customerId) return;
            reviewLoginHint.hidden = false;
            reviewLoginHint.textContent = `Nie udało się pobrać historii zamówień: ${error.message}`;
        } finally { button.disabled = false; }
    }
    await load();
}

function renderReview(review) {
    const stars = '★'.repeat(Number(review.rating || 0)) + '☆'.repeat(Math.max(0, 5 - Number(review.rating || 0)));
    return `<article class="product-review-card">
        <div class="product-review-card-heading"><strong>${escapeHtml(review.title)}</strong><span class="product-review-stars" aria-label="Ocena ${escapeHtml(review.rating)} na 5">${stars}</span></div>
        <p>${escapeHtml(review.content)}</p>
        <small>Opinia opublikowana ${formatDate(review.createdAt)}</small>
    </article>`;
}

function setReviewStatus(message, type = '') {
    reviewStatus.textContent = message;
    reviewStatus.className = `review-status${type ? ` ${type}` : ''}`;
}

function getApiBase() {
    return window.MiniTechApi.baseUrl;
}

function cartStorageKey() {
    return cartStore.key(customer);
}

function loadCart() {
    return cartStore.load(cartStorageKey());
}

function saveCart(cart) {
    cartStore.save(cartStorageKey(), cart);
}

function rememberRecentlyViewed(item) {
    if (!item?.id) return;
    try {
        const stored = JSON.parse(localStorage.getItem('minitech-recent-products') || '[]');
        const entries = Array.isArray(stored) ? stored : [];
        const current = {
            id: Number(item.id),
            name: item.name,
            category: item.category,
            manufacturer: item.manufacturer,
            model: item.model,
            sku: item.sku,
            imageUrl: item.imageUrl
        };
        const next = [current, ...entries.filter(entry => Number(entry?.id) !== Number(item.id))].slice(0, 8);
        localStorage.setItem('minitech-recent-products', JSON.stringify(next));
    } catch {
        // Local history is optional and must not block product details.
    }
}

function showError(message) {
    loading.hidden = true;
    detail.hidden = true;
    errorMessage.textContent = message;
    errorPanel.hidden = false;
}

function formatMoney(value) {
    if (i18n) return i18n.formatMoney(value);
    return `${Number(value || 0).toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} zł`;
}

function formatDate(value) {
    const isEn = i18n && i18n.getLanguage() === 'en';
    if (!value) return isEn ? 'recently' : 'niedawno';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? (isEn ? 'recently' : 'niedawno') : date.toLocaleDateString(isEn ? 'en-US' : 'pl-PL');
}

function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>'"]/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[character]));
}

window.addEventListener('minitech:language-changed', () => {
    if (product) {
        renderProduct();
        loadRecommendations();
    }
});
