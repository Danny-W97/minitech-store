const API_BASE = getApiBase();
const GUEST_CART_STORAGE_KEY = 'minitech-cart:guest';
const CUSTOMER_CART_STORAGE_PREFIX = 'minitech-cart:customer:';
const CART_STORAGE_SCHEMA_KEY = 'minitech-cart:schema-version';
const CART_STORAGE_SCHEMA_VERSION = '2';
const sessionGuard = window.MiniTechSession;
let authenticatedSessionExpected = hasAuthenticationMarker();
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

migrateCartStorageScope();
loadProduct();

async function loadProduct() {
    if (!productId || !/^\d+$/.test(productId)) {
        showError('Brak poprawnego identyfikatora produktu.');
        return;
    }
    try {
        product = await request(`/products/${encodeURIComponent(productId)}`);
        try {
            const stock = await request(`/inventory/items/${encodeURIComponent(productId)}`);
            availableQuantity = Number.isInteger(stock?.availableQuantity) ? stock.availableQuantity : null;
        } catch {
            availableQuantity = null;
        }
        renderProduct();
        rememberRecentlyViewed(product);
        await Promise.all([loadLowestPrice(), loadReviews(), loadRecommendations()]);
        renderProduct();
        await restoreSession();
        await loadReviewEligibility();
    } catch (error) {
        showError(error.message);
    }
}

async function loadLowestPrice() {
    try {
        const response = await request(`/promotions/history/${encodeURIComponent(productId)}/lowest?days=30`);
        const value = Number(response?.lowestPrice);
        lowestPrice30Days = Number.isFinite(value) ? value : null;
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
    const isEn = window.MiniTechI18n && window.MiniTechI18n.getLanguage() === 'en';
    const category = product.category || 'TECH';
    const translatedCategory = window.MiniTechI18n ? window.MiniTechI18n.translateCategory(category) : category;
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
        lowestPriceElement.hidden = !Number.isFinite(lowestPrice30Days);
        lowestPriceElement.textContent = Number.isFinite(lowestPrice30Days)
            ? (isEn ? `Lowest price in last 30 days: ${formatMoney(lowestPrice30Days)}` : `Najniższa cena z ostatnich 30 dni: ${formatMoney(lowestPrice30Days)}`)
            : '';
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
            const label = window.MiniTechI18n ? window.MiniTechI18n.translateParamKey(key) : key;
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

async function request(path, options = {}, retryAfterRefresh = true) {
    const headers = new Headers(options.headers || {});
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
    const response = await fetch(`${API_BASE}${path}`, {
        ...options,
        headers,
        credentials: 'include',
        cache: 'no-store'
    });
    if (response.status === 401 && retryAfterRefresh && canRefresh(path) && await refreshSession()) {
        return request(path, options, false);
    }
    const raw = await response.text();
    let data = raw;
    try { data = raw ? JSON.parse(raw) : null; } catch { /* Keep plain error text. */ }
    if (!response.ok) {
        throw new Error(data && typeof data === 'object' ? (data.message || data.error || `HTTP ${response.status}`) : raw || `HTTP ${response.status}`);
    }
    return data;
}

function canRefresh(path) {
    return !['/customers/login', '/customers/register', '/customers/refresh', '/customers/logout'].some(publicPath => path.startsWith(publicPath));
}

async function refreshSession() {
    if (!sessionGuard?.isFresh()) {
        await expireProductSession(false);
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
            clearGuestCartStorage();
            authenticatedSessionExpected = true;
            sessionGuard?.start(() => expireProductSession(true));
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
        await expireProductSession(false);
        return false;
    }
    return refreshSession();
}

async function expireProductSession(showMessage) {
    const resetGuestCart = authenticatedSessionExpected || Boolean(customer || accessToken);
    try {
        await fetch(`${API_BASE}/customers/logout`, {
            method: 'POST', credentials: 'include', cache: 'no-store'
        });
    } catch {
        // The browser state is cleared even if Customer Service is unavailable.
    }
    sessionGuard?.clear();
    accessToken = null;
    customer = null;
    if (resetGuestCart) clearGuestCartStorage();
    authenticatedSessionExpected = false;
    if (reviewForm) reviewForm.hidden = true;
    if (reviewLoginHint) {
        reviewLoginHint.hidden = false;
        reviewLoginHint.textContent = showMessage
            ? 'Sesja wygasła po 30 minutach bezczynności. Zaloguj się ponownie w sklepie.'
            : 'Zaloguj się, aby wystawić opinię o kupionym produkcie.';
    }
}

async function loadReviews() {
    reviewsSection.hidden = false;
    try {
        const [reviews, summary] = await Promise.all([
            request(`/reviews/products/${encodeURIComponent(productId)}`),
            request(`/reviews/products/${encodeURIComponent(productId)}/summary`)
        ]);
        const count = Number(summary?.publishedCount || 0);
        const average = Number(summary?.averageRating || 0);
        reviewSummary.textContent = count
            ? `${average.toFixed(2)} / 5 · ${count} ${count === 1 ? 'opinia' : 'opinii'}`
            : 'Brak opinii — bądź pierwszy.';
        reviewList.innerHTML = Array.isArray(reviews) && reviews.length
            ? reviews.map(renderReview).join('')
            : '<p class="product-review-empty">Ten produkt nie ma jeszcze opublikowanych opinii.</p>';
    } catch (error) {
        reviewSummary.textContent = 'Opinie są chwilowo niedostępne.';
        reviewList.innerHTML = `<p class="product-review-empty">${escapeHtml(error.message)}</p>`;
    }
}

async function loadReviewEligibility() {
    if (!customer) {
        reviewForm.hidden = true;
        reviewLoginHint.hidden = false;
        return;
    }
    try {
        const orders = await request(`/orders/customer/${encodeURIComponent(customer.id)}`);
        const eligible = (Array.isArray(orders) ? orders : []).filter(order =>
            order?.status === 'PAID' && (order.items || []).some(item => String(item.productId) === String(product.id))
        );
        reviewOrder.innerHTML = eligible.map(order =>
            `<option value="${escapeHtml(order.id)}">Zamówienie #${escapeHtml(order.id)} · ${formatMoney(order.totalPrice)}</option>`
        ).join('');
        reviewForm.hidden = eligible.length === 0;
        reviewLoginHint.hidden = eligible.length > 0;
        reviewLoginHint.textContent = eligible.length
            ? ''
            : 'Nie znaleziono opłaconego zamówienia z tym produktem. Po zakupie będzie można dodać opinię.';
        if (eligible.some(order => String(order.id) === String(reviewOrderId))) {
            reviewOrder.value = reviewOrderId;
            window.setTimeout(() => reviewsSection.scrollIntoView({ behavior: 'smooth', block: 'start' }), 0);
        }
    } catch {
        reviewForm.hidden = true;
        reviewLoginHint.hidden = false;
        reviewLoginHint.textContent = 'Nie udało się pobrać historii zamówień.';
    }
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
    if (window.MINITECH_API_BASE) return window.MINITECH_API_BASE.replace(/\/$/, '');
    const queryApi = new URLSearchParams(window.location.search).get('api');
    if (queryApi) return queryApi.replace(/\/$/, '');
    const host = window.location.hostname || '127.0.0.1';
    return `http://${host}:18080/api`;
}

function cartStorageKey() {
    const customerId = Number(customer?.id);
    return Number.isInteger(customerId) && customerId > 0
        ? `${CUSTOMER_CART_STORAGE_PREFIX}${customerId}`
        : GUEST_CART_STORAGE_KEY;
}

function hasAuthenticationMarker() {
    try {
        const value = Number(localStorage.getItem(sessionGuard?.storageKey || ''));
        return Number.isFinite(value) && value > 0;
    } catch {
        return false;
    }
}

function clearGuestCartStorage() {
    try {
        localStorage.removeItem(GUEST_CART_STORAGE_KEY);
    } catch {
        // Product details remain usable when persistent storage is unavailable.
    }
}

function migrateCartStorageScope() {
    try {
        if (localStorage.getItem(CART_STORAGE_SCHEMA_KEY) === CART_STORAGE_SCHEMA_VERSION) return;
        localStorage.removeItem(GUEST_CART_STORAGE_KEY);
        localStorage.setItem(CART_STORAGE_SCHEMA_KEY, CART_STORAGE_SCHEMA_VERSION);
    } catch {
        // The page can continue with an in-memory cart when storage migration is blocked.
    }
}

function loadCart() {
    try {
        const cart = JSON.parse(localStorage.getItem(cartStorageKey()) || '[]');
        return Array.isArray(cart) ? cart : [];
    } catch {
        return [];
    }
}

function saveCart(cart) {
    try {
        localStorage.setItem(cartStorageKey(), JSON.stringify(cart));
    } catch {
        // Persistent storage is optional; adding a product must not break the page.
    }
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
    if (window.MiniTechI18n) return window.MiniTechI18n.formatMoney(value);
    return `${Number(value || 0).toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} zł`;
}

function formatDate(value) {
    const isEn = window.MiniTechI18n && window.MiniTechI18n.getLanguage() === 'en';
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
