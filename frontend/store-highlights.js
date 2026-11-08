import { stockFor } from './store-cart.js?v=20261028-a43-32';
import { loadProducts, priceHistoryLabel, productCard } from './store-catalog.js?v=20261028-a43-32';
import {
    heroCarousel, heroPause, heroSlides, heroTopics, hotShotCard, hotShotCountdown, hotShotDots,
    hotShotNext, hotShotPrev, hotShotSection, newProductsCarousel, newProductsGrid, newProductsNext,
    newProductsPrev, promotionGrid, promotionPanelTimer, recentRecommendationsGrid,
    recentRecommendationsSection, recentlyViewedGrid, recentlyViewedSection,
} from './store-dom.js?v=20261028-a43-32';
import { escapeHtml, finitePrice, formatMoney } from './store-format.js?v=20261028-a43-32';
import { runtime, state } from './store-state.js?v=20261028-a43-32';

export function renderHeroSlide(index) {
    if (!heroSlides.length) return;
    runtime.heroIndex = (index + heroSlides.length) % heroSlides.length;
    heroSlides.forEach((slide, slideIndex) => {
        const active = slideIndex === runtime.heroIndex;
        slide.hidden = !active;
        slide.classList.toggle('is-active', active);
        slide.setAttribute('aria-hidden', String(!active));
    });
    heroTopics.forEach((topic, topicIndex) => {
        const active = topicIndex === runtime.heroIndex;
        topic.classList.toggle('is-active', active);
        topic.setAttribute('aria-selected', String(active));
    });
}

export function moveHeroSlide(direction) {
    renderHeroSlide(runtime.heroIndex + direction);
    if (!runtime.heroPaused && !heroCarousel?.matches(':hover') && !heroCarousel?.matches(':focus-within')) {
        startHeroAutoScroll();
    }
}

export function startHeroAutoScroll() {
    pauseHeroAutoScroll();
    if (runtime.heroPaused || heroSlides.length < 2) return;
    runtime.heroAutoScroll = window.setInterval(() => renderHeroSlide(runtime.heroIndex + 1), 6500);
}

export function pauseHeroAutoScroll() {
    if (runtime.heroAutoScroll !== null) {
        window.clearInterval(runtime.heroAutoScroll);
        runtime.heroAutoScroll = null;
    }
}

export function resumeHeroAutoScroll() {
    if (!runtime.heroPaused && !heroCarousel?.matches(':hover') && !heroCarousel?.matches(':focus-within')) {
        startHeroAutoScroll();
    }
}

export function toggleHeroAutoScroll() {
    runtime.heroPaused = !runtime.heroPaused;
    if (heroPause) {
        heroPause.textContent = runtime.heroPaused ? '▶' : 'Ⅱ';
        heroPause.setAttribute('aria-label', runtime.heroPaused
            ? 'Wznów automatyczne przewijanie'
            : 'Wstrzymaj automatyczne przewijanie');
    }
    if (runtime.heroPaused) pauseHeroAutoScroll();
    else startHeroAutoScroll();
}

export function renderHighlights() {
    const promotions = state.products
        .filter(product => product.promotional && Number(product.discountPercent) > 0)
        .sort((left, right) => Number(right.discountPercent) - Number(left.discountPercent))
        .slice(0, 6);
    const newProducts = state.products
        .filter(product => product.newProduct)
        .slice(0, 6);
    if (promotionGrid) {
        promotionGrid.innerHTML = promotions.length
            ? promotions.map(product => productCard(product, 'highlight')).join('')
            : '<div class="catalog-message">Brak aktywnych promocji.</div>';
    }
    runtime.hotShotItems = promotions.slice(0, 3);
    runtime.hotShotIndex = 0;
    renderHotShotCard();
    if (newProductsGrid) {
        newProductsGrid.innerHTML = newProducts.length
            ? newProducts.map(product => productCard(product, 'new')).join('')
            : '<div class="catalog-message">Brak nowych produktów.</div>';
    }
    updateNewProductsCarousel(newProducts.length > 1);
    const rotationDate = state.products.find(product => product.rotationDate)?.rotationDate;
    if (rotationDate) {
        const promotionNote = document.querySelector('#promotions-rotation-note');
        const newProductsNote = document.querySelector('#new-products-rotation-note');
        if (promotionNote) promotionNote.textContent = `Oferta ważna w rotacji z dnia ${rotationDate}.`;
        if (newProductsNote) newProductsNote.textContent = `Wybór obowiązuje w rotacji z dnia ${rotationDate}.`;
    }
    updateHeroProducts(newProducts, promotions, rotationDate);
    scheduleDailyRotationRefresh();
    startPromotionCountdown();
}

export function updateHeroProducts(newProducts, promotions, rotationDate) {
    if (!heroCarousel || !heroSlides.length || !state.products.length) return;
    const dateKey = rotationDate || new Date().toISOString().slice(0, 10);
    const buildProducts = state.products.filter(product =>
        ['CPU', 'RAM', 'SSD', 'Płyta główna'].includes(product.category)
    );
    const selections = {
        new: selectDailyProduct(newProducts, dateKey, 'new'),
        promo: selectDailyProduct(promotions, dateKey, 'promo'),
        build: selectDailyProduct(buildProducts, dateKey, 'build')
    };

    heroCarousel.dataset.rotationDate = dateKey;
    heroSlides.forEach(slide => {
        const kind = slide.dataset.heroKind;
        const product = selections[kind];
        if (!product) return;
        const image = slide.querySelector('[data-hero-image]');
        const label = slide.querySelector('[data-hero-label]');
        const link = slide.querySelector('[data-hero-link]');

        slide.dataset.productId = String(product.id);
        if (image && product.imageUrl) {
            image.src = product.imageUrl;
            image.alt = `${product.name} — produkt rotacji ${dateKey}`;
        }
        if (label) label.textContent = `${product.category || 'TECH'} / ${product.name}`.toUpperCase();
        if (link) link.setAttribute('href', `product.html?id=${encodeURIComponent(product.id)}`);

        updateHeroText(slide, product, dateKey);
    });
}

export function updateHeroText(slide, product, dateKey) {
    const badge = slide.querySelector('[data-hero-badge]');
    const lead = slide.querySelector('[data-hero-lead]');
    const quantity = stockFor(product.id)?.availableQuantity;
    const available = Number.isInteger(quantity) ? `${quantity} szt.` : 'sprawdź dostępność';
    const price = finitePrice(product.price, product.regularPrice);
    if (slide.dataset.heroKind === 'promo') {
        const savings = Math.max(0, finitePrice(product.regularPrice, price) - price);
        if (badge) badge.textContent = `-${Number(product.discountPercent || 0)}%`;
        if (lead) lead.textContent = `${product.name}: ${formatMoney(price)}. Oszczędzasz ${formatMoney(savings)}; dostępne ${available}. Oferta z ${dateKey}.`;
    } else if (slide.dataset.heroKind === 'new') {
        if (badge) badge.textContent = 'NOWOŚĆ';
        if (lead) lead.textContent = `${product.name} od ${formatMoney(price)}. Dostępne ${available}. Codzienny wybór Product i Promotion Service z ${dateKey}.`;
    } else {
        if (badge) badge.textContent = 'ZESTAW DNIA';
        if (lead) lead.textContent = `${product.name} — ${product.manufacturer || 'MiniTech'} ${product.model || ''}. Cena ${formatMoney(price)}, dostępne ${available}.`;
    }
}

export function selectDailyProduct(products, dateKey, salt) {
    if (!Array.isArray(products) || !products.length) return null;
    const candidates = [...products]
        .filter(product => product && product.id !== undefined)
        .sort((left, right) => String(left.id).localeCompare(String(right.id), 'pl', { numeric: true }));
    if (!candidates.length) return null;
    const seed = `${dateKey}:${salt}`;
    let hash = 0;
    for (let index = 0; index < seed.length; index += 1) {
        hash = ((hash << 5) - hash + seed.charCodeAt(index)) | 0;
    }
    return candidates[Math.abs(hash) % candidates.length];
}

export function scheduleDailyRotationRefresh() {
    if (runtime.dailyRotationRefreshTimer !== null) window.clearTimeout(runtime.dailyRotationRefreshTimer);
    const now = new Date();
    const nextUtcDay = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1, 0, 0, 2);
    runtime.dailyRotationRefreshTimer = window.setTimeout(async () => {
        runtime.dailyRotationRefreshTimer = null;
        await loadProducts();
    }, Math.max(1000, nextUtcDay - now.getTime()));
}

export function renderHotShotCard() {
    if (!hotShotSection || !hotShotCard) return;
    if (!runtime.hotShotItems.length) {
        hotShotSection.hidden = true;
        stopHotShotRotation();
        return;
    }

    const product = runtime.hotShotItems[runtime.hotShotIndex % runtime.hotShotItems.length];
    const price = finitePrice(product.price, product.regularPrice);
    const regularPrice = finitePrice(product.regularPrice, price);
    const savings = Math.max(0, regularPrice - price);
    const lowestRaw = state.lowestPrices[product.id];
    const lowestPrice = lowestRaw === null || lowestRaw === undefined ? null : Number(lowestRaw);
    const stock = stockFor(product.id);
    const available = Number.isInteger(stock?.availableQuantity) ? stock.availableQuantity : null;
    const sold = Number.isInteger(stock?.soldQuantity) ? stock.soldQuantity : 0;
    const reserved = Number.isInteger(stock?.reservedQuantity) ? stock.reservedQuantity : 0;
    const totalTracked = available === null ? 0 : available + reserved + sold;
    const soldPercent = totalTracked > 0
        ? Math.min(100, Math.max(sold > 0 ? 3 : 0, Math.round((sold / totalTracked) * 100)))
        : 0;
    const symbol = escapeHtml((product.category || 'MT').slice(0, 3).toUpperCase());
    const visual = product.imageUrl
        ? `<img src="${escapeHtml(product.imageUrl)}" alt="${escapeHtml(product.name)}" loading="lazy">`
        : `<span class="visual-symbol">${symbol}</span>`;
    const stockLabel = available === null ? '—' : available;
    const soldLabel = Number.isInteger(stock?.soldQuantity) ? sold : '—';

    hotShotSection.hidden = false;
    hotShotCard.innerHTML = `
        <article class="hot-shot-product">
            <div class="hot-shot-visual">
                <span class="hot-shot-badge">-${Number(product.discountPercent)}%</span>
                <span class="hot-shot-saving"><span>Oszczędzasz</span><strong>${formatMoney(savings)}</strong></span>
                ${visual}
            </div>
            <div class="hot-shot-product-body">
                <p class="product-category">${escapeHtml(product.category || 'TECH')}</p>
                <h3><a href="product.html?id=${encodeURIComponent(product.id)}">${escapeHtml(product.name)}</a></h3>
                <p class="hot-shot-meta">${escapeHtml(product.manufacturer || '')} · ${escapeHtml(product.model || product.sku || '')}</p>
                <div class="hot-shot-pricing">
                    <strong class="hot-shot-price">${formatMoney(price)}</strong>
                    <span class="hot-shot-regular">${formatMoney(regularPrice)}</span>
                </div>
                <p class="hot-shot-savings-note">Oszczędzasz ${formatMoney(savings)} dzięki promocji dnia.</p>
                <p class="hot-shot-lowest">${escapeHtml(priceHistoryLabel(lowestPrice))}</p>
                <div class="hot-shot-stock-row"><span>Pozostało: <strong>${stockLabel}</strong></span><span>Sprzedano: <strong>${soldLabel}</strong></span></div>
                <div class="hot-shot-stock-bar" role="progressbar" aria-label="Udział sprzedanych sztuk" aria-valuenow="${soldPercent}" aria-valuemin="0" aria-valuemax="100"><span style="width: ${soldPercent}%"></span></div>
                <button class="primary-button hot-shot-buy" type="button" data-action="add" data-product-id="${product.id}" ${available === 0 ? 'disabled' : ''}>${available === 0 ? 'Brak produktu' : 'Kup teraz'}</button>
            </div>
        </article>`;

    if (hotShotDots) {
        hotShotDots.innerHTML = runtime.hotShotItems.map((item, index) => `
            <button class="hot-shot-dot ${index === runtime.hotShotIndex ? 'is-active' : ''}" type="button"
                data-hot-shot-index="${index}" aria-label="Pokaż ofertę ${index + 1}" aria-current="${index === runtime.hotShotIndex}"></button>`).join('');
    }
    if (hotShotPrev) hotShotPrev.hidden = runtime.hotShotItems.length < 2;
    if (hotShotNext) hotShotNext.hidden = runtime.hotShotItems.length < 2;
    if (runtime.hotShotRotationInterval === null && runtime.hotShotItems.length > 1) startHotShotRotation();
}

export function startHotShotRotation() {
    stopHotShotRotation();
    if (runtime.hotShotItems.length < 2) return;
    runtime.hotShotRotationInterval = window.setInterval(() => {
        if (runtime.hotShotRotationPaused) return;
        rotateHotShot(1);
    }, 9000);
}

export function stopHotShotRotation() {
    if (runtime.hotShotRotationInterval !== null) {
        window.clearInterval(runtime.hotShotRotationInterval);
        runtime.hotShotRotationInterval = null;
    }
}

export function rotateHotShot(direction) {
    if (runtime.hotShotItems.length < 2) return;
    runtime.hotShotIndex = (runtime.hotShotIndex + direction + runtime.hotShotItems.length) % runtime.hotShotItems.length;
    renderHotShotCard();
}

export function renderRecentlyViewed() {
    if (!recentlyViewedSection || !recentlyViewedGrid) return;
    const ids = readRecentlyViewedIds();
    const recent = ids
        .map(id => state.products.find(product => Number(product.id) === Number(id)))
        .filter(Boolean);
    recentlyViewedSection.hidden = recent.length === 0;
    recentlyViewedGrid.innerHTML = recent.length
        ? recent.slice(0, 6).map(product => productCard(product, 'highlight')).join('')
        : '';

    if (!recentRecommendationsSection || !recentRecommendationsGrid) return;
    const recentSet = new Set(recent.map(product => Number(product.id)));
    const lastViewed = recent[0];
    const recommendations = lastViewed
        ? state.products
            .filter(product => product.category === lastViewed.category && !recentSet.has(Number(product.id)))
            .slice(0, 3)
        : [];
    recentRecommendationsSection.hidden = recommendations.length === 0;
    recentRecommendationsGrid.innerHTML = recommendations
        .map(product => productCard(product, 'highlight')).join('');
}

export function readRecentlyViewedIds() {
    try {
        const stored = JSON.parse(localStorage.getItem('minitech-recent-products') || '[]');
        return Array.isArray(stored)
            ? stored.map(entry => Number(entry?.id)).filter(Number.isFinite)
            : [];
    } catch {
        return [];
    }
}

export function startPromotionCountdown() {
    const note = document.querySelector('#promotions-rotation-note');
    if (!note && !hotShotCountdown) return;
    if (runtime.promotionCountdownInterval !== null) window.clearInterval(runtime.promotionCountdownInterval);
    updatePromotionCountdown();
    runtime.promotionCountdownInterval = window.setInterval(updatePromotionCountdown, 1000);
}

export function updatePromotionCountdown() {
    const note = document.querySelector('#promotions-rotation-note');
    if (!note && !hotShotCountdown) return;
    const now = new Date();
    const nextUtcDay = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1));
    const totalSeconds = Math.max(0, Math.floor((nextUtcDay.getTime() - now.getTime()) / 1000));
    const hours = String(Math.floor(totalSeconds / 3600)).padStart(2, '0');
    const minutes = String(Math.floor((totalSeconds % 3600) / 60)).padStart(2, '0');
    const seconds = String(totalSeconds % 60).padStart(2, '0');
    const countdown = `${hours}:${minutes}:${seconds}`;
    if (note) note.textContent = `Promocje kończą się za: ${countdown} (rotacja UTC).`;
    if (hotShotCountdown) hotShotCountdown.textContent = `Do końca rotacji: ${countdown} (UTC)`;
    if (promotionPanelTimer) promotionPanelTimer.textContent = `Do końca rotacji: ${countdown}`;
}

export function updateNewProductsCarousel(enabled) {
    pauseNewProductsAutoScroll();
    const hidden = !enabled;
    if (newProductsPrev) newProductsPrev.hidden = hidden;
    if (newProductsNext) newProductsNext.hidden = hidden;
    if (newProductsGrid) newProductsGrid.scrollLeft = 0;
    if (enabled) startNewProductsAutoScroll();
}

export function scrollNewProducts(direction) {
    if (!newProductsGrid || !newProductsGrid.children.length) return;
    const firstCard = newProductsGrid.querySelector('.highlight-card');
    const step = firstCard ? firstCard.getBoundingClientRect().width + 16 : newProductsGrid.clientWidth * .82;
    const atEnd = direction > 0
        && newProductsGrid.scrollLeft + newProductsGrid.clientWidth >= newProductsGrid.scrollWidth - 8;
    const atStart = direction < 0 && newProductsGrid.scrollLeft <= 8;
    const target = atEnd
        ? 0
        : (atStart ? newProductsGrid.scrollWidth : newProductsGrid.scrollLeft + (direction * step));
    newProductsGrid.scrollTo({ left: Math.max(0, target), behavior: 'smooth' });
}

export function startNewProductsDrag(event) {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    if (event.target.closest('a, button, input, select, textarea')) return;
    pauseNewProductsAutoScroll();
    runtime.newProductsDrag = {
        startX: event.clientX,
        startScrollLeft: newProductsGrid.scrollLeft,
        moved: false
    };
    newProductsGrid.classList.add('is-dragging');
    newProductsGrid.setPointerCapture(event.pointerId);
}

export function moveNewProductsDrag(event) {
    if (!runtime.newProductsDrag) return;
    const distance = event.clientX - runtime.newProductsDrag.startX;
    if (Math.abs(distance) > 5) runtime.newProductsDrag.moved = true;
    if (!runtime.newProductsDrag.moved) return;
    event.preventDefault();
    newProductsGrid.scrollLeft = runtime.newProductsDrag.startScrollLeft - distance;
}

export function endNewProductsDrag(event) {
    if (!runtime.newProductsDrag) return;
    if (runtime.newProductsDrag.moved) runtime.suppressNewProductsClick = true;
    newProductsGrid.classList.remove('is-dragging');
    if (newProductsGrid.hasPointerCapture(event.pointerId)) newProductsGrid.releasePointerCapture(event.pointerId);
    runtime.newProductsDrag = null;
    resumeNewProductsAutoScroll();
}

export function startNewProductsAutoScroll() {
    pauseNewProductsAutoScroll();
    if (!newProductsGrid || newProductsGrid.children.length < 2) return;
    runtime.newProductsAutoScroll = window.setInterval(() => scrollNewProducts(1), 5000);
}

export function resumeNewProductsAutoScroll() {
    if (!newProductsCarousel?.matches(':hover') && !newProductsCarousel?.matches(':focus-within')) {
        startNewProductsAutoScroll();
    }
}

export function pauseNewProductsAutoScroll() {
    if (runtime.newProductsAutoScroll !== null) {
        window.clearInterval(runtime.newProductsAutoScroll);
        runtime.newProductsAutoScroll = null;
    }
}
