import { i18n } from './i18n.js?v=20261028-a43-32';
import { loadWishlist, renderWishlistPanel } from './store-account-data.js?v=20261028-a43-32';
import { toggleWishlist } from './store-account.js?v=20261028-a43-32';
import { renderCart, saveCart, stockFor } from './store-cart.js?v=20261028-a43-32';
import { pruneCompareIds, renderCompareControls, toggleCompare } from './store-comparison.js?v=20261028-a43-32';
import {
    bestsellerGrid, catalogMessage, categoryFilter, categoryPills, globalSearchInput, heroCarousel,
    heroSlides, hotShotCard, manufacturerFilter, priceMaxFilter, priceMinFilter, productGrid,
    searchInput, sortFilter, technicalParameterFilter, technicalValueFilter,
} from './store-dom.js?v=20261028-a43-32';
import { showToast } from './store-feedback.js?v=20261028-a43-32';
import { escapeHtml, finitePrice, formatMoney } from './store-format.js?v=20261028-a43-32';
import { renderHighlights, renderRecentlyViewed, updateHeroText } from './store-highlights.js?v=20261028-a43-32';
import { API_BASE, api } from './store-services.js?v=20261028-a43-32';
import { CATEGORY_PARAMETER_KEYS, TECHNICAL_PARAMETER_LABELS, runtime, state } from './store-state.js?v=20261028-a43-32';

export async function loadProducts() {
    runtime.catalogContext?.controller.abort();
    runtime.catalogContext?.observer?.disconnect();
    runtime.catalogContext?.mutations?.disconnect();
    const context = { controller: new AbortController() };
    runtime.catalogContext = context;
    if (catalogMessage) setCatalogMessage('Ładowanie katalogu…');
    try {
        const products = await api('/products', { anonymous: true, signal: context.controller.signal, timeoutMs: 8000 });
        if (!currentCatalog(context)) return;
        state.products = Array.isArray(products) ? products.filter(product => product.active) : [];
        state.inventory = {}; state.lowestPrices = {};
        context.reader = window.MiniTechCatalogData.createInventoryReader({
            signal: context.controller.signal,
            request: (ids, signal) => api(`/inventory/items/batch?productIds=${ids.join(',')}`, { anonymous: true, signal }),
            onUpdate: update => { if (currentCatalog(context)) { Object.assign(state.inventory, update); updateStockDisplay(new Set(Object.keys(update))); } }
        });
        pruneCompareIds();
        state.cart.forEach(item => {
            const currentProduct = state.products.find(product => product.id === item.id);
            if (currentProduct) item.price = Number(currentProduct.price);
        });
        saveCart();
        fillCategories();
        fillManufacturers();
        applyCatalogUrlFilters();
        if (state.customer) {
            loadWishlist().catch(() => {});
        } else {
            renderWishlistPanel();
        }
        renderHighlights();
        renderProducts();
        renderRecentlyViewed();
        renderCart();
        renderCompareControls();
        if (catalogMessage) setCatalogMessage(`${state.products.length} aktywnych produktów · API Gateway ${API_BASE}`);
        observeCatalogStock(context);
        context.reader.load(state.cart.map(item => item.id));
        loadHotShotPrices(context);
        loadBestsellers(context);
    } catch (error) {
        if (!currentCatalog(context)) return;
        if (productGrid) productGrid.innerHTML = '';
        if (catalogMessage) setCatalogMessage(`Nie można pobrać katalogu: ${error.message}`, true);
    }
}

export function currentCatalog(context) { return Boolean(context && runtime.catalogContext === context && !context.controller.signal.aborted); }

export async function loadBestsellers(context = runtime.catalogContext) {
    if (!bestsellerGrid || !context) return;
    try {
        const ranking = await api('/orders/bestsellers?limit=6', { anonymous: true, signal: context.controller.signal, timeoutMs: 3500 });
        if (!currentCatalog(context)) return;
        const rankedProducts = (Array.isArray(ranking) ? ranking : [])
            .map(entry => state.products.find(product => String(product.id) === String(entry.productId)))
            .filter(Boolean);
        bestsellerGrid.innerHTML = rankedProducts.length
            ? rankedProducts.map(product => productCard(product, 'highlight')).join('')
            : '<div class="catalog-message">Bestsellery pojawią się po pierwszych opłaconych zamówieniach.</div>';
        const note = document.querySelector('#bestsellers-note');
        if (note && rankedProducts.length) note.textContent = 'Ranking aktualizowany na podstawie opłaconych zamówień.';
    } catch {
        if (!currentCatalog(context)) return;
        bestsellerGrid.innerHTML = '<div class="catalog-message">Nie można pobrać rankingu bestsellerów.</div>';
    }
}

export async function loadHotShotPrices(context) {
    const candidates = state.products
        .filter(product => product.promotional && Number(product.discountPercent) > 0)
        .sort((left, right) => Number(right.discountPercent) - Number(left.discountPercent))
        .slice(0, 3);
    const entries = await window.MiniTechCatalogData.mapLimit(candidates, 2, async product => {
        try {
            const history = await api(`/promotions/history/${encodeURIComponent(product.id)}/lowest?days=30`,
                { anonymous: true, signal: context.controller.signal, timeoutMs: 3500 });
            const lowest = history?.lowestPrice == null ? NaN : Number(history.lowestPrice);
            return [product.id, history?.historyComplete === true && Number.isFinite(lowest) ? lowest : null];
        } catch {
            return [product.id, null];
        }
    });
    if (!currentCatalog(context)) return;
    state.lowestPrices = Object.fromEntries(entries);
    updateHotShotData();
}

export function observeCatalogStock(context) {
    const observed = new WeakSet();
    const cardsIn = node => node instanceof Element
        ? [...(node.matches('.product-card') ? [node] : []), ...node.querySelectorAll('.product-card')] : [];
    const register = cards => {
        const ids = [];
        cards.forEach(card => {
            if (observed.has(card)) return;
            const id = Number(card.querySelector('[data-action="add"]')?.dataset.productId);
            if (!Number.isSafeInteger(id)) return;
            card.dataset.stockProductId = String(id); observed.add(card); ids.push(id);
            context.observer?.observe(card);
        });
        // First logical page; subsequent cards are enriched as they enter the viewport.
        context.reader.load(ids.slice(0, 24));
    };
    if ('IntersectionObserver' in window) {
        context.observer = new IntersectionObserver(entries => {
            if (!currentCatalog(context)) return;
            context.reader.load(entries.filter(entry => entry.isIntersecting).map(entry => entry.target.dataset.stockProductId));
        }, { rootMargin: '80px' });
    }
    context.mutations = new MutationObserver(changes => {
        if (!currentCatalog(context)) return;
        const added = [];
        changes.forEach(change => {
            change.removedNodes.forEach(node => cardsIn(node).forEach(card => { context.observer?.unobserve(card); observed.delete(card); }));
            change.addedNodes.forEach(node => added.push(...cardsIn(node)));
        });
        register(added);
    });
    context.mutations.observe(document.body, { childList: true, subtree: true });
    register([...document.querySelectorAll('.product-card')]);
    context.reader.load(runtime.hotShotItems.map(item => item.id));
    context.reader.load([...document.querySelectorAll('[data-hero-kind][data-product-id]')].map(slide => slide.dataset.productId));
}

export function updateStockDisplay(changed) {
    const isEn = i18n?.getLanguage() === 'en';
    document.querySelectorAll('.product-card [data-action="add"]').forEach(button => {
        if (!changed.has(button.dataset.productId)) return;
        const empty = stockFor(button.dataset.productId)?.availableQuantity === 0;
        button.disabled = empty;
        button.classList.toggle('add-button-sold-out', empty);
        const label = empty ? (isEn ? 'Out of stock' : 'Brak produktu') : (isEn ? 'Add to cart' : 'Dodaj do koszyka');
        if (button.textContent !== label) button.textContent = label;
    });
    updateHotShotData();
    heroSlides.forEach(slide => {
        if (!changed.has(slide.dataset.productId)) return;
        const product = state.products.find(item => String(item.id) === slide.dataset.productId);
        if (product) updateHeroText(slide, product, heroCarousel?.dataset.rotationDate || product.rotationDate);
    });
    renderCart();
}

export function updateHotShotData() {
    if (!hotShotCard || !runtime.hotShotItems.length) return;
    const product = runtime.hotShotItems[runtime.hotShotIndex % runtime.hotShotItems.length];
    const stock = stockFor(product.id);
    const available = stock?.availableQuantity, sold = stock?.soldQuantity, reserved = stock?.reservedQuantity;
    const values = hotShotCard.querySelectorAll('.hot-shot-stock-row strong');
    if (values[0]) values[0].textContent = Number.isInteger(available) ? available : '—';
    if (values[1]) values[1].textContent = Number.isInteger(sold) ? sold : '—';
    const total = (available || 0) + (sold || 0) + (reserved || 0);
    const percent = total > 0 ? Math.min(100, Math.max(sold > 0 ? 3 : 0, Math.round((sold || 0) / total * 100))) : 0;
    const bar = hotShotCard.querySelector('.hot-shot-stock-bar');
    if (bar) { bar.setAttribute('aria-valuenow', String(percent)); bar.firstElementChild.style.width = `${percent}%`; }
    const buy = hotShotCard.querySelector('.hot-shot-buy');
    if (buy) { buy.disabled = available === 0; buy.textContent = available === 0 ? 'Brak produktu' : 'Kup teraz'; }
    const lowest = state.lowestPrices[product.id];
    const label = hotShotCard.querySelector('.hot-shot-lowest');
    if (label) label.textContent = priceHistoryLabel(lowest);
}

export function priceHistoryLabel(price) {
    const translate = (key, fallback) => i18n?.t(key, fallback) || fallback;
    return Number.isFinite(price)
        ? `${translate('lowestPrice30DaysPrefix', 'Najniższa cena z ostatnich 30 dni:')} ${formatMoney(price)}`
        : translate('priceHistoryIncomplete', 'Brak pełnej historii cen z 30 dni.');
}

export function fillCategories() {
    if (!categoryFilter) return;
    const isEn = i18n && i18n.getLanguage() === 'en';
    const selected = categoryFilter.value;
    const categories = [...new Set(state.products.map(product => product.category).filter(Boolean))].sort();
    categoryFilter.innerHTML = `<option value="all">${isEn ? 'All' : 'Wszystkie'}</option>` + categories
        .map(category => {
            const label = i18n ? i18n.translateCategory(category) : category;
            return `<option value="${escapeHtml(category)}">${escapeHtml(label)}</option>`;
        })
        .join('');
    categoryFilter.value = categories.includes(selected) ? selected : 'all';
}

export function fillManufacturers(selectedOverride = null) {
    if (!manufacturerFilter) return;
    const isEn = i18n && i18n.getLanguage() === 'en';
    const category = categoryFilter?.value || 'all';
    const selected = selectedOverride === null ? manufacturerFilter.value : selectedOverride;
    const categoryProducts = category === 'all'
        ? state.products
        : state.products.filter(product => product.category === category);
    const manufacturers = [...new Set(categoryProducts.map(product => product.manufacturer).filter(Boolean))].sort();
    manufacturerFilter.innerHTML = `<option value="all">${isEn ? 'All' : 'Wszyscy'}</option>` + manufacturers
        .map(manufacturer => `<option value="${escapeHtml(manufacturer)}">${escapeHtml(manufacturer)}</option>`)
        .join('');
    manufacturerFilter.value = manufacturers.includes(selected) ? selected : 'all';
}

export function fillTechnicalParameters(selectedOverride = null) {
    if (!technicalParameterFilter) return;
    const isEn = i18n && i18n.getLanguage() === 'en';
    const category = categoryFilter?.value || 'all';
    const selected = selectedOverride === null ? technicalParameterFilter.value : selectedOverride;
    const categoryProducts = category === 'all'
        ? []
        : state.products.filter(product => product.category === category);
    const discoveredKeys = [...new Set(categoryProducts
        .flatMap(product => Object.keys(product.technicalParameters || {})))];
    const configuredKeys = CATEGORY_PARAMETER_KEYS[category] || [];
    const keys = (configuredKeys.length
        ? configuredKeys.filter(key => discoveredKeys.includes(key))
        : discoveredKeys.sort((left, right) => left.localeCompare(right, isEn ? 'en' : 'pl')));
    const categorySelected = category !== 'all';
    const emptyLabel = categorySelected ? (isEn ? 'All parameters' : 'Wszystkie parametry') : (isEn ? 'Select category' : 'Wybierz kategorię');
    technicalParameterFilter.innerHTML = `<option value="all">${emptyLabel}</option>` + keys
        .map(key => {
            const label = i18n ? i18n.translateParamKey(key) : (TECHNICAL_PARAMETER_LABELS[key] || key);
            return `<option value="${escapeHtml(key)}">${escapeHtml(label)}</option>`;
        })
        .join('');
    technicalParameterFilter.value = keys.includes(selected) ? selected : 'all';
    technicalParameterFilter.disabled = !categorySelected || keys.length === 0;
    if (technicalValueFilter) {
        technicalValueFilter.disabled = !categorySelected || keys.length === 0;
        technicalValueFilter.placeholder = categorySelected && keys.length > 0
            ? 'np. DDR5, 32GB'
            : (isEn ? 'Select category first' : 'Najpierw wybierz kategorię');
        technicalValueFilter.setAttribute('aria-label', categorySelected
            ? (isEn ? `Parameter value for category ${category}` : `Wartość parametru kategorii ${category}`)
            : (isEn ? 'Parameter value — select category' : 'Wartość parametru — wybierz kategorię'));
    }
    const parameterLabel = document.querySelector('#technical-parameter-label');
    if (parameterLabel) parameterLabel.textContent = categorySelected
        ? (isEn ? `Parameter: ${i18n ? i18n.translateCategory(category) : category}` : `Parametr: ${category}`)
        : (isEn ? 'Category parameter' : 'Parametr kategorii');
}

export function resetTechnicalFilterForCategory() {
    if (technicalParameterFilter) technicalParameterFilter.value = 'all';
    if (technicalValueFilter) technicalValueFilter.value = '';
    fillTechnicalParameters('all');
}

export function applyCatalogUrlFilters() {
    if (document.body.dataset.page !== 'catalog') return;
    const params = new URLSearchParams(window.location.search);
    const query = params.get('search') || '';
    const category = params.get('category') || 'all';
    if (searchInput) searchInput.value = query;
    if (globalSearchInput) globalSearchInput.value = query;
    if (categoryFilter && [...categoryFilter.options].some(option => option.value === category)) {
        categoryFilter.value = category;
    }
    fillManufacturers();
    fillTechnicalParameters();
    if (priceMinFilter) priceMinFilter.value = params.get('minPrice') || '';
    if (priceMaxFilter) priceMaxFilter.value = params.get('maxPrice') || '';
    const parameter = params.get('parameter') || 'all';
    const parameterIsValid = technicalParameterFilter
        && [...technicalParameterFilter.options].some(option => option.value === parameter);
    if (parameterIsValid) {
        technicalParameterFilter.value = parameter;
    }
    if (technicalValueFilter) technicalValueFilter.value = parameterIsValid && parameter !== 'all'
        ? params.get('parameterValue') || ''
        : '';
}

export function optionalPriceFilterValue(value) {
    const raw = String(value ?? '').trim().replace(',', '.');
    if (!raw) return null;
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : null;
}

export function renderProducts() {
    const sortVersion = ++runtime.stockSortVersion;
    if (!productGrid || !searchInput || !categoryFilter || !manufacturerFilter || !sortFilter) return;
    const query = searchInput.value.trim().toLowerCase();
    const category = categoryFilter.value;
    const manufacturer = manufacturerFilter.value;
    const minPrice = optionalPriceFilterValue(priceMinFilter?.value);
    const maxPrice = optionalPriceFilterValue(priceMaxFilter?.value);
    const parameter = technicalParameterFilter?.value || 'all';
    const parameterValue = technicalValueFilter?.value.trim().toLowerCase() || '';
    const sort = sortFilter.value;
    const visible = state.products.filter(product => {
        const searchable = [product.name, product.manufacturer, product.model, product.sku, product.category]
            .filter(Boolean).join(' ').toLowerCase();
        const price = Number(product.price);
        const parameters = Object.entries(product.technicalParameters || {});
        const parameterMatches = !parameterValue
            ? true
            : parameters.some(([key, value]) => (parameter === 'all' || key === parameter)
                && String(value).toLowerCase().includes(parameterValue));
        return (!query || searchable.includes(query))
            && (category === 'all' || product.category === category)
            && (manufacturer === 'all' || product.manufacturer === manufacturer)
            && (minPrice === null || price >= minPrice)
            && (maxPrice === null || price <= maxPrice)
            && parameterMatches;
    });

    syncCategoryStrip(category);

    visible.sort((left, right) => {
        switch (sort) {
            case 'price-asc':
                return Number(left.price) - Number(right.price);
            case 'price-desc':
                return Number(right.price) - Number(left.price);
            case 'name-asc':
                return String(left.name).localeCompare(String(right.name), 'pl');
            case 'stock-desc':
                return (stockFor(right.id)?.availableQuantity ?? -1) - (stockFor(left.id)?.availableQuantity ?? -1);
            default:
                return 0;
        }
    });

    if (!visible.length) {
        productGrid.innerHTML = '<div class="catalog-message">Brak produktów pasujących do filtrów.</div>';
        return;
    }
    productGrid.innerHTML = visible.map(product => productCard(product, 'catalog')).join('');
    if (sort === 'stock-desc' && runtime.catalogContext?.reader) {
        const context = runtime.catalogContext;
        context.reader.load(visible.map(product => product.id)).then(() => {
            if (!currentCatalog(context) || runtime.stockSortVersion !== sortVersion) return;
            const cards = [...productGrid.querySelectorAll('.product-card')];
            const quantity = card => stockFor(card.querySelector('[data-action="add"]').dataset.productId)?.availableQuantity ?? -1;
            cards.sort((left, right) => quantity(right) - quantity(left));
            productGrid.append(...cards);
        });
    }
}

export function selectCategoryFromStrip(event) {
    if (document.body.dataset.page !== 'catalog') return;
    event.preventDefault();
    if (!categoryFilter) return;
    categoryFilter.value = event.currentTarget.dataset.category;
    fillManufacturers();
    resetTechnicalFilterForCategory();
    renderProducts();
    document.querySelector('#catalog')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

export function syncCategoryStrip(category = categoryFilter?.value || 'all') {
    categoryPills.forEach(pill => pill.classList.toggle('active', pill.dataset.category === category));
}

export function productCard(product, variant = 'catalog') {
    const isEn = i18n && i18n.getLanguage() === 'en';
    const symbol = escapeHtml((product.category || 'MT').slice(0, 3).toUpperCase());
    const stock = stockFor(product.id);
    const availableQuantity = stock?.availableQuantity;
    const soldOut = availableQuantity === 0;
    const visual = product.imageUrl
        ? `<img class="product-visual-image" src="${escapeHtml(product.imageUrl)}" alt="${escapeHtml(product.name)}" loading="lazy">`
        : `<span class="visual-symbol">${symbol}</span>`;
    const emphasizeNew = variant === 'new';
    const badgeLabel = emphasizeNew && product.newProduct
        ? (isEn ? 'NEW' : 'NOWOŚĆ')
        : (Number(product.discountPercent) > 0
        ? `-${Number(product.discountPercent)}%`
        : (product.newProduct ? (isEn ? 'NEW' : 'NOWOŚĆ') : 'ACTIVE'));
    const badgeClass = emphasizeNew && product.newProduct
        ? 'product-badge-new'
        : (Number(product.discountPercent) > 0
        ? 'product-badge-promotion'
        : (product.newProduct ? 'product-badge-new' : ''));
    const compared = state.compareIds.has(Number(product.id));
    const detailsLabel = isEn ? 'Details' : 'Szczegóły';
    const compareLabel = compared ? (isEn ? '✓ Compare' : '✓ Porównaj') : (isEn ? 'Compare' : 'Porównaj');
    const compareTitle = compared ? (isEn ? 'Remove from comparison' : 'Usuń z porównania') : (isEn ? 'Add to comparison' : 'Dodaj do porównania');
    const wishlistTitle = state.wishlist.has(Number(product.id)) ? (isEn ? 'Remove from wishlist' : 'Usuń z ulubionych') : (isEn ? 'Add to wishlist' : 'Dodaj do ulubionych');
    const addLabel = soldOut ? (isEn ? 'Out of stock' : 'Brak produktu') : (isEn ? 'Add to cart' : 'Dodaj do koszyka');
    return `
        <article class="product-card highlight-card">
            <a class="product-visual" href="product.html?id=${encodeURIComponent(product.id)}" aria-label="${isEn ? 'View product details' : 'Zobacz szczegóły produktu'} ${escapeHtml(product.name)}">
                <span class="product-badge ${badgeClass}">${badgeLabel}</span>
                ${visual}
            </a>
            <h3><a class="product-detail-link" href="product.html?id=${encodeURIComponent(product.id)}">${escapeHtml(product.name)}</a></h3>
            ${promotionSavingsMarkup(product)}
            <div class="product-footer">
                ${priceMarkup(product)}
                <div class="product-actions">
                    <a class="product-detail-button" href="product.html?id=${encodeURIComponent(product.id)}">${detailsLabel}</a>
                    ${variant === 'catalog' ? `<button class="compare-button ${compared ? 'is-active' : ''}" type="button" data-action="compare" data-product-id="${product.id}" aria-pressed="${compared}" title="${compareTitle}">${compareLabel}</button>` : ''}
                    <button class="wishlist-button ${state.wishlist.has(Number(product.id)) ? 'is-active' : ''}" type="button" data-action="wishlist" data-product-id="${product.id}" aria-label="${wishlistTitle}" title="${wishlistTitle}" aria-pressed="${state.wishlist.has(Number(product.id))}">${state.wishlist.has(Number(product.id)) ? '♥' : '♡'}</button>
                    <button class="add-button ${soldOut ? 'add-button-sold-out' : ''}" type="button" data-action="add" data-product-id="${product.id}" ${soldOut ? 'disabled' : ''}>${addLabel}</button>
                </div>
            </div>
        </article>`;
}

export function priceMarkup(product) {
    const salePrice = finitePrice(product.price, product.regularPrice);
    const regularPrice = finitePrice(product.regularPrice, salePrice);
    const hasDiscount = Number(product.discountPercent) > 0 && regularPrice > salePrice;
    return `<div class="product-price-wrap">
        <strong class="product-price">${formatMoney(salePrice)}</strong>
        ${hasDiscount ? `<span class="product-price-regular">${formatMoney(regularPrice)}</span>` : ''}
    </div>`;
}

export function promotionSavingsMarkup(product) {
    const isEn = i18n && i18n.getLanguage() === 'en';
    const salePrice = finitePrice(product.price, product.regularPrice);
    const regularPrice = finitePrice(product.regularPrice, salePrice);
    const savings = regularPrice - salePrice;
    const note = isEn ? `You save ${formatMoney(savings)} · daily deal` : `Oszczędzasz ${formatMoney(savings)} · promocja dnia`;
    return Number(product.discountPercent) > 0 && savings > 0
        ? `<p class="product-promotion-note">${note}</p>`
        : '';
}

export function handleProductAction(event) {
    const button = event.target.closest('[data-action]');
    if (!button) return;
    if (button.dataset.action === 'wishlist') {
        toggleWishlist(button.dataset.productId, button).catch(() => {});
        return;
    }
    if (button.dataset.action === 'compare') {
        toggleCompare(button.dataset.productId);
        return;
    }
    if (button.dataset.action !== 'add') return;
    const product = state.products.find(item => String(item.id) === button.dataset.productId);
    if (!product) return;
    const stock = stockFor(product.id);
    const existing = state.cart.find(item => item.id === product.id);
    if (Number.isInteger(stock?.availableQuantity)
        && (!existing ? 0 : existing.quantity) >= stock.availableQuantity) {
        showToast(`Osiągnięto limit magazynowy: ${stock.availableQuantity} szt.`);
        return;
    }
    if (existing) {
        existing.quantity += 1;
    } else {
        state.cart.push({ id: product.id, name: product.name, sku: product.sku, price: Number(product.price), quantity: 1 });
    }
    saveCart();
    renderCart();
    showToast(`${product.name} dodano do koszyka`);
}

export function setCatalogMessage(message, isError = false) {
    if (!catalogMessage) return;
    catalogMessage.textContent = message;
    catalogMessage.style.color = isError ? '#bd331d' : '';
}

export function submitGlobalSearch() {
    const query = globalSearchInput?.value.trim() || '';
    const url = new URL('catalog.html', window.location.href);
    if (query) url.searchParams.set('search', query);
    window.location.href = url.href;
}
