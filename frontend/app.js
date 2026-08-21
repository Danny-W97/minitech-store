const LEGACY_CART_STORAGE_KEY = 'minitech-cart';
const GUEST_CART_STORAGE_KEY = 'minitech-cart:guest';
const CUSTOMER_CART_STORAGE_PREFIX = 'minitech-cart:customer:';
const CART_STORAGE_SCHEMA_KEY = 'minitech-cart:schema-version';
const CART_STORAGE_SCHEMA_VERSION = '2';
const CHECKOUT_ATTEMPT_STORAGE_PREFIX = 'minitech-checkout-attempt:';
const sessionGuard = window.MiniTechSession;
let activeCartStorageKey = GUEST_CART_STORAGE_KEY;
let authenticatedSessionExpected = hasAuthenticationMarker();

removeLegacyCartStorage();
migrateCartStorageScope();

const state = {
    products: [],
    inventory: {},
    lowestPrices: {},
    deliveryMethods: [],
    deliveryQuote: null,
    coupon: null,
    cart: loadCart(activeCartStorageKey),
    customer: null,
    accessToken: null,
    mfaChallenge: null,
    wishlist: new Set(),
    compareIds: new Set(loadCompareIds())
};

const MAX_COMPARE_PRODUCTS = 4;
const CATEGORY_PARAMETER_KEYS = Object.freeze({
    CPU: ['cores', 'threads', 'socket', 'boostGHz'],
    GPU: ['memory', 'interface', 'rayTracing'],
    RAM: ['capacity', 'type', 'speedMHz'],
    SSD: ['capacity', 'interface', 'readSpeedMBps'],
    'Płyta główna': ['socket', 'chipset', 'formFactor', 'wifi'],
    Monitor: ['size', 'resolution', 'refreshHz', 'panel'],
    Laptop: ['screen', 'memory', 'storage', 'gpu'],
    Klawiatura: ['layout', 'switches', 'connection', 'wireless', 'backlight'],
    Słuchawki: ['type', 'noiseCancelling', 'batteryHours']
});
const TECHNICAL_PARAMETER_LABELS = Object.freeze({
    cores: 'Rdzenie',
    threads: 'Wątki',
    socket: 'Gniazdo procesora',
    boostGHz: 'Taktowanie boost (GHz)',
    memory: 'Pamięć',
    interface: 'Interfejs',
    rayTracing: 'Ray tracing',
    capacity: 'Pojemność',
    type: 'Typ',
    speedMHz: 'Taktowanie (MHz)',
    readSpeedMBps: 'Odczyt (MB/s)',
    chipset: 'Chipset',
    formFactor: 'Format płyty',
    wifi: 'Wi-Fi',
    size: 'Rozmiar',
    resolution: 'Rozdzielczość',
    refreshHz: 'Odświeżanie (Hz)',
    panel: 'Panel',
    screen: 'Ekran',
    storage: 'Dysk',
    gpu: 'Karta graficzna',
    layout: 'Układ klawiszy',
    switches: 'Przełączniki',
    connection: 'Łączność',
    wireless: 'Bezprzewodowa',
    backlight: 'Podświetlenie',
    noiseCancelling: 'Redukcja hałasu',
    batteryHours: 'Czas pracy (h)'
});
let refreshPromise = null;
let passwordResetMode = false;
let mfaEnrollment = null;
let newProductsAutoScroll = null;
let newProductsDrag = null;
let suppressNewProductsClick = false;
let heroAutoScroll = null;
let heroIndex = 0;
let heroPaused = false;
let promotionCountdownInterval = null;
let dailyRotationRefreshTimer = null;
let hotShotRotationInterval = null;
let hotShotItems = [];
let hotShotIndex = 0;
let hotShotRotationPaused = false;
let cartPreviewCloseTimer = null;

const API_BASE = getApiBase();
const productGrid = document.querySelector('#product-grid');
const promotionGrid = document.querySelector('#promotion-grid');
const hotShotSection = document.querySelector('#hot-shot');
const hotShotCard = document.querySelector('#hot-shot-card');
const hotShotCountdown = document.querySelector('#hot-shot-countdown');
const hotShotDots = document.querySelector('#hot-shot-dots');
const hotShotPrev = document.querySelector('#hot-shot-prev');
const hotShotNext = document.querySelector('#hot-shot-next');
const promotionPanelTimer = document.querySelector('#promotion-panel-timer');
const newProductsGrid = document.querySelector('#new-products-grid');
const newProductsCarousel = document.querySelector('[data-carousel="new-products"]');
const newProductsPrev = document.querySelector('#new-products-prev');
const newProductsNext = document.querySelector('#new-products-next');
const bestsellerGrid = document.querySelector('#bestsellers-grid');
const recentlyViewedSection = document.querySelector('#recently-viewed');
const recentlyViewedGrid = document.querySelector('#recently-viewed-grid');
const recentRecommendationsSection = document.querySelector('#recent-recommendations');
const recentRecommendationsGrid = document.querySelector('#recent-recommendations-grid');
const compareTray = document.querySelector('#compare-tray');
const compareHint = document.querySelector('#compare-hint');
const compareChips = document.querySelector('#compare-chips');
const compareOpen = document.querySelector('#compare-open');
const compareClear = document.querySelector('#compare-clear');
const compareModal = document.querySelector('#compare-modal');
const compareClose = document.querySelector('#compare-close');
const compareTable = document.querySelector('#compare-table');
const promotionCodeInput = document.querySelector('#promotion-code');
const promotionCodeApply = document.querySelector('#promotion-code-apply');
const promotionCodeFeedback = document.querySelector('#promotion-code-feedback');
const heroCarousel = document.querySelector('#hero-carousel');
const heroSlides = [...document.querySelectorAll('[data-hero-slide]')];
const heroTopics = [...document.querySelectorAll('[data-hero-topic]')];
const heroPrev = document.querySelector('#hero-prev');
const heroNext = document.querySelector('#hero-next');
const heroPause = document.querySelector('#hero-pause');
const catalogMessage = document.querySelector('#catalog-message');
const searchInput = document.querySelector('#product-search');
const globalSearchForm = document.querySelector('#global-search-form');
const globalSearchInput = document.querySelector('#global-product-search');
const categoryFilter = document.querySelector('#category-filter');
const categoryPills = [...document.querySelectorAll('.category-pill[data-category]')];
const manufacturerFilter = document.querySelector('#manufacturer-filter');
const priceMinFilter = document.querySelector('#price-min-filter');
const priceMaxFilter = document.querySelector('#price-max-filter');
const technicalParameterFilter = document.querySelector('#technical-parameter-filter');
const technicalValueFilter = document.querySelector('#technical-value-filter');
const sortFilter = document.querySelector('#sort-filter');
const cartItems = document.querySelector('#cart-items');
const cartEmpty = document.querySelector('#cart-empty');
const cartCount = document.querySelector('#cart-count');
const cartTotal = document.querySelector('#cart-total');
const cartCheckout = document.querySelector('#cart-checkout');
const cartArea = document.querySelector('#cart-area');
const cartToggle = document.querySelector('#cart-toggle');
const cartPreview = document.querySelector('#cart-preview');
const cartPreviewItems = document.querySelector('#cart-preview-items');
const cartPreviewCount = document.querySelector('#cart-preview-count');
const cartPreviewTotal = document.querySelector('#cart-preview-total');
const cartPreviewClose = document.querySelector('#cart-preview-close');
const cartPreviewCheckout = document.querySelector('#cart-preview-checkout');
const checkoutForm = document.querySelector('#checkout-form');
const checkoutSubmit = document.querySelector('#checkout-submit');
const checkoutToSummary = document.querySelector('#checkout-to-summary');
const checkoutSummaryStep = document.querySelector('#checkout-summary-step');
const checkoutSummaryItems = document.querySelector('#checkout-summary-items');
const checkoutSummaryEmail = document.querySelector('#checkout-summary-email');
const checkoutSummaryAddress = document.querySelector('#checkout-summary-address');
const checkoutSummaryDelivery = document.querySelector('#checkout-summary-delivery');
const checkoutSummaryPayment = document.querySelector('#checkout-summary-payment');
const summaryStepProducts = document.querySelector('#summary-step-products');
const summaryStepDiscountRow = document.querySelector('#summary-step-discount-row');
const summaryStepDiscount = document.querySelector('#summary-step-discount');
const summaryStepDelivery = document.querySelector('#summary-step-delivery');
const summaryStepNet = document.querySelector('#summary-step-net');
const summaryStepVat = document.querySelector('#summary-step-vat');
const summaryStepGross = document.querySelector('#summary-step-gross');
const checkoutSummaryBack = document.querySelector('#checkout-summary-back');
const checkoutSummarySubmit = document.querySelector('#checkout-summary-submit');
const checkoutStatus = document.querySelector('#checkout-status');
const checkoutLayout = document.querySelector('#checkout');
const checkoutSection = document.querySelector('.checkout-section');
const checkoutProgressSteps = [...document.querySelectorAll('[data-checkout-progress]')];
const cartThresholdNote = document.querySelector('#cart-threshold-note');
const cartStockWarning = document.querySelector('#cart-stock-warning');
const storeHomeBrands = [...document.querySelectorAll('.topbar > .brand, .checkout-flow-brand')];
const checkoutModeText = document.querySelector('#checkout-mode-text');
const deliveryMethod = document.querySelector('#delivery-method');
const deliveryDate = document.querySelector('#delivery-date');
const deliveryAddressFields = document.querySelector('#delivery-address-fields');
const guestContactFields = document.querySelector('#guest-contact-fields');
const deliveryEmailInput = document.querySelector('#delivery-email');
const deliveryPhoneInput = document.querySelector('#delivery-phone');
const pickupLocationField = document.querySelector('#pickup-location-field');
const pickupLocation = document.querySelector('#pickup-location');
const deliveryQuote = document.querySelector('#delivery-quote');
const summaryProductsGross = document.querySelector('#summary-products-gross');
const summaryDiscountRow = document.querySelector('#summary-discount-row');
const summaryDiscountLabel = document.querySelector('#summary-discount-label');
const summaryDiscount = document.querySelector('#summary-discount');
const summaryDeliveryGross = document.querySelector('#summary-delivery-gross');
const summaryDeliveryDate = document.querySelector('#summary-delivery-date');
const summaryNet = document.querySelector('#summary-net');
const summaryVat = document.querySelector('#summary-vat');
const summaryGross = document.querySelector('#summary-gross');
const invoiceRequested = document.querySelector('#invoice-requested');
const billingDataPanel = document.querySelector('#billing-data');
const billingCompany = document.querySelector('#billing-company');
const billingBuyerName = document.querySelector('#billing-buyer-name');
const billingCompanyName = document.querySelector('#billing-company-name');
const billingTaxId = document.querySelector('#billing-tax-id');
const checkoutBack = document.querySelector('#checkout-back');
const checkoutChoiceModal = document.querySelector('#checkout-choice-modal');
const checkoutChoiceClose = document.querySelector('#checkout-choice-close');
const checkoutChoiceAccount = document.querySelector('#checkout-choice-account');
const checkoutChoiceGuest = document.querySelector('#checkout-choice-guest');
let purchaseMode = 'guest';
let pendingPurchaseMode = null;
let checkoutStep = 'cart';
let checkoutSubmissionInProgress = false;
const LOGIN_REQUIRED_THRESHOLD = 1000;
const authToggle = document.querySelector('#auth-toggle');
const accountArea = document.querySelector('#account-area');
const accountMenu = document.querySelector('#account-menu');
const accountMenuProfile = document.querySelector('#account-menu-profile');
const accountMenuOrders = document.querySelector('#account-menu-orders');
const accountMenuWishlist = document.querySelector('#account-menu-wishlist');
const accountMenuSecurity = document.querySelector('#account-menu-security');
const accountMenuLogout = document.querySelector('#account-menu-logout');
const authModal = document.querySelector('#auth-modal');
const authClose = document.querySelector('#auth-close');
const authMode = document.querySelector('#auth-mode');
const authLoginChoice = document.querySelector('#auth-login-choice');
const authRegisterChoice = document.querySelector('#auth-register-choice');
const authForm = document.querySelector('#auth-form');
const authEmail = document.querySelector('#auth-email');
const authPassword = document.querySelector('#auth-password');
const authPasswordConfirmLabel = document.querySelector('#auth-password-confirm-label');
const authPasswordConfirm = document.querySelector('#auth-password-confirm');
const authRegisterFields = document.querySelector('#auth-register-fields');
const authSubmit = document.querySelector('#auth-submit');
const authModeSelector = document.querySelector('#auth-mode-selector');
const authForgotPassword = document.querySelector('#auth-forgot-password');
const authResetForm = document.querySelector('#auth-reset-form');
const authResetEmail = document.querySelector('#auth-reset-email');
const authResetTokenLabel = document.querySelector('#auth-reset-token-label');
const authResetToken = document.querySelector('#auth-reset-token');
const authResetPasswordLabel = document.querySelector('#auth-reset-password-label');
const authResetPassword = document.querySelector('#auth-reset-password');
const authResetSubmit = document.querySelector('#auth-reset-submit');
const authResetBack = document.querySelector('#auth-reset-back');
const authResend = document.querySelector('#auth-resend');
const authResendButton = document.querySelector('#auth-resend-button');
const authResendStatus = document.querySelector('#auth-resend-status');
const authLogout = document.querySelector('#auth-logout');
const authMfaPanel = document.querySelector('#auth-mfa-panel');
const authMfaHelp = document.querySelector('#auth-mfa-help');
const authMfaCode = document.querySelector('#auth-mfa-code');
const authMfaCodeLabel = document.querySelector('#auth-mfa-code-label');
const authMfaSubmit = document.querySelector('#auth-mfa-submit');
const authMfaPasskey = document.querySelector('#auth-mfa-passkey');
const authMfaStatus = document.querySelector('#auth-mfa-status');
const authSecurityPanel = document.querySelector('#auth-security-panel');
const authMfaSummary = document.querySelector('#auth-mfa-summary');
const authTotpStart = document.querySelector('#auth-totp-start');
const authTotpSetup = document.querySelector('#auth-totp-setup');
const authTotpQr = document.querySelector('#auth-totp-qr');
const authTotpSecretLabel = document.querySelector('#auth-totp-secret-label');
const authTotpSecret = document.querySelector('#auth-totp-secret');
const authTotpCodeLabel = document.querySelector('#auth-totp-code-label');
const authTotpCode = document.querySelector('#auth-totp-code');
const authTotpConfirm = document.querySelector('#auth-totp-confirm');
const authTotpDisable = document.querySelector('#auth-totp-disable');
const authTotpDisablePanel = document.querySelector('#auth-totp-disable-panel');
const authTotpDisableCode = document.querySelector('#auth-totp-disable-code');
const authTotpDisableConfirm = document.querySelector('#auth-totp-disable-confirm');
const authPasskeyRegister = document.querySelector('#auth-passkey-register');
const authSecurityStatus = document.querySelector('#auth-security-status');
const authProfilePanel = document.querySelector('#auth-profile-panel');
const authProfileEmail = document.querySelector('#auth-profile-email');
const authProfileFirstName = document.querySelector('#auth-profile-first-name');
const authProfileLastName = document.querySelector('#auth-profile-last-name');
const authProfilePhone = document.querySelector('#auth-profile-phone');
const authProfileStreet = document.querySelector('#auth-profile-street');
const authProfilePostalCode = document.querySelector('#auth-profile-postal-code');
const authProfileCity = document.querySelector('#auth-profile-city');
const authProfileCountry = document.querySelector('#auth-profile-country');
const authProfileSave = document.querySelector('#auth-profile-save');
const authProfileStatus = document.querySelector('#auth-profile-status');
const authOrdersPanel = document.querySelector('#auth-orders-panel');
const authOrdersList = document.querySelector('#auth-orders-list');
const authOrdersStatus = document.querySelector('#auth-orders-status');
const authWishlistPanel = document.querySelector('#auth-wishlist-panel');
const authWishlistList = document.querySelector('#auth-wishlist-list');
const authWishlistStatus = document.querySelector('#auth-wishlist-status');
const accountStatus = document.querySelector('#account-status');
const successModal = document.querySelector('#success-modal');
const successMessage = document.querySelector('#success-message');
const toast = document.querySelector('#toast');

document.querySelector('#refresh-products')?.addEventListener('click', loadProducts);
document.querySelector('#clear-cart')?.addEventListener('click', clearCart);
storeHomeBrands.forEach(brand => brand.addEventListener('click', event => {
    event.preventDefault();
    event.stopPropagation();
    const homeUrl = new URL('./index.html#top', window.location.href);
    window.location.assign(homeUrl.href);
}));
cartCheckout.addEventListener('click', openCheckoutChoice);
cartToggle.addEventListener('click', event => {
    event.stopPropagation();
    toggleCartPreview();
});
cartArea?.addEventListener('mouseenter', () => openCartPreview());
cartArea?.addEventListener('mouseleave', scheduleCloseCartPreview);
cartArea?.addEventListener('focusin', openCartPreview);
cartArea?.addEventListener('focusout', event => {
    if (!cartArea.contains(event.relatedTarget)) scheduleCloseCartPreview();
});
cartPreviewClose?.addEventListener('click', closeCartPreview);
cartPreviewCheckout?.addEventListener('click', () => {
    closeCartPreview();
    if (checkoutLayout.classList.contains('checkout-only')) {
        backToCart();
        return;
    }
    if (!document.body.classList.contains('cart-screen-active')) showCartPage();
});
checkoutBack.addEventListener('click', backToCart);
checkoutToSummary?.addEventListener('click', prepareCheckoutSummary);
checkoutSummaryBack?.addEventListener('click', () => {
    setCheckoutStep('delivery');
    window.history.replaceState({}, document.title, '#checkout');
});
checkoutSummarySubmit?.addEventListener('click', () => checkoutForm.requestSubmit());
searchInput?.addEventListener('input', renderProducts);
categoryFilter?.addEventListener('change', () => {
    fillManufacturers();
    resetTechnicalFilterForCategory();
    renderProducts();
});
manufacturerFilter?.addEventListener('change', renderProducts);
priceMinFilter?.addEventListener('input', renderProducts);
priceMaxFilter?.addEventListener('input', renderProducts);
technicalParameterFilter?.addEventListener('change', renderProducts);
technicalValueFilter?.addEventListener('input', renderProducts);
sortFilter?.addEventListener('change', renderProducts);
categoryPills.forEach(pill => pill.addEventListener('click', selectCategoryFromStrip));
productGrid?.addEventListener('click', handleProductAction);
promotionGrid?.addEventListener('click', handleProductAction);
hotShotCard?.addEventListener('click', handleProductAction);
hotShotDots?.addEventListener('click', event => {
    const dot = event.target.closest('[data-hot-shot-index]');
    if (!dot) return;
    hotShotIndex = Number(dot.dataset.hotShotIndex) || 0;
    renderHotShotCard();
});
hotShotPrev?.addEventListener('click', () => rotateHotShot(-1));
hotShotNext?.addEventListener('click', () => rotateHotShot(1));
hotShotSection?.addEventListener('mouseenter', () => { hotShotRotationPaused = true; });
hotShotSection?.addEventListener('mouseleave', () => { hotShotRotationPaused = false; });
hotShotSection?.addEventListener('focusin', () => { hotShotRotationPaused = true; });
hotShotSection?.addEventListener('focusout', event => {
    if (!hotShotSection.contains(event.relatedTarget)) hotShotRotationPaused = false;
});
bestsellerGrid?.addEventListener('click', handleProductAction);
recentlyViewedGrid?.addEventListener('click', handleProductAction);
recentRecommendationsGrid?.addEventListener('click', handleProductAction);
newProductsGrid?.addEventListener('click', handleProductAction);
compareOpen?.addEventListener('click', openCompareModal);
compareClear?.addEventListener('click', clearCompare);
compareClose?.addEventListener('click', closeCompareModal);
compareModal?.addEventListener('click', event => {
    if (event.target === compareModal) closeCompareModal();
});
compareChips?.addEventListener('click', event => {
    const button = event.target.closest('[data-compare-remove]');
    if (button) toggleCompare(button.dataset.compareRemove);
});
newProductsPrev?.addEventListener('click', () => scrollNewProducts(-1));
newProductsNext?.addEventListener('click', () => scrollNewProducts(1));
newProductsGrid?.addEventListener('pointerdown', startNewProductsDrag);
newProductsGrid?.addEventListener('pointermove', moveNewProductsDrag);
newProductsGrid?.addEventListener('pointerup', endNewProductsDrag);
newProductsGrid?.addEventListener('pointercancel', endNewProductsDrag);
newProductsGrid?.addEventListener('click', event => {
    if (!suppressNewProductsClick) return;
    event.preventDefault();
    event.stopPropagation();
    suppressNewProductsClick = false;
}, true);
newProductsCarousel?.addEventListener('mouseenter', pauseNewProductsAutoScroll);
newProductsCarousel?.addEventListener('mouseleave', resumeNewProductsAutoScroll);
newProductsCarousel?.addEventListener('focusin', pauseNewProductsAutoScroll);
newProductsCarousel?.addEventListener('focusout', event => {
    if (!newProductsCarousel.contains(event.relatedTarget)) resumeNewProductsAutoScroll();
});
heroPrev?.addEventListener('click', () => moveHeroSlide(-1));
heroNext?.addEventListener('click', () => moveHeroSlide(1));
heroTopics.forEach(topic => topic.addEventListener('click', () => renderHeroSlide(Number(topic.dataset.heroTopic))));
heroPause?.addEventListener('click', toggleHeroAutoScroll);
heroCarousel?.addEventListener('mouseenter', pauseHeroAutoScroll);
heroCarousel?.addEventListener('mouseleave', resumeHeroAutoScroll);
heroCarousel?.addEventListener('focusin', pauseHeroAutoScroll);
heroCarousel?.addEventListener('focusout', event => {
    if (!heroCarousel.contains(event.relatedTarget)) resumeHeroAutoScroll();
});
cartItems.addEventListener('click', handleCartAction);
cartPreviewItems?.addEventListener('click', handleCartAction);
checkoutForm.addEventListener('submit', submitCheckout);
promotionCodeApply?.addEventListener('click', () => validatePromotionCode().catch(() => {}));
promotionCodeInput?.addEventListener('keydown', event => {
    if (event.key === 'Enter') {
        event.preventDefault();
        validatePromotionCode().catch(() => {});
    }
});
deliveryMethod?.addEventListener('change', () => {
    if (deliveryDate) deliveryDate.value = '';
    syncDeliveryModeUi();
    loadDeliveryQuote().catch(() => {});
});
deliveryDate?.addEventListener('change', () => loadDeliveryQuote().catch(() => {}));
document.querySelector('#delivery-postal-code')?.addEventListener('change', () => loadDeliveryQuote().catch(() => {}));
invoiceRequested?.addEventListener('change', syncBillingUi);
billingCompany?.addEventListener('change', syncBillingUi);
billingTaxId?.addEventListener('input', validateBillingTaxId);
deliveryEmailInput?.addEventListener('input', () => {
    const recipientEmail = document.querySelector('#recipient-email');
    if (recipientEmail) recipientEmail.value = deliveryEmailInput.value;
});
authToggle.addEventListener('click', openAuthModal);
accountMenuProfile.addEventListener('click', openProfilePanel);
accountMenuOrders.addEventListener('click', openOrderHistoryPanel);
accountMenuWishlist.addEventListener('click', openWishlistPanel);
accountMenuSecurity.addEventListener('click', openSecurityPanel);
accountMenuLogout.addEventListener('click', () => logoutCustomer());
document.addEventListener('click', event => {
    if (!accountArea.contains(event.target)) closeAccountMenu();
    if (cartArea && !cartArea.contains(event.target)) closeCartPreview();
});
document.addEventListener('keydown', event => {
    if (event.key === 'Escape') closeCartPreview();
});
checkoutChoiceClose.addEventListener('click', closeCheckoutChoice);
checkoutChoiceAccount.addEventListener('click', () => choosePurchaseMode('account'));
checkoutChoiceGuest.addEventListener('click', () => choosePurchaseMode('guest'));
authClose.addEventListener('click', closeAuthModal);
authMode.addEventListener('change', updateAuthMode);
authLoginChoice?.addEventListener('click', () => selectAuthMode('login'));
authRegisterChoice?.addEventListener('click', () => selectAuthMode('register'));
authPassword.addEventListener('input', validatePasswordMatch);
authPasswordConfirm.addEventListener('input', validatePasswordMatch);
authForm.addEventListener('submit', submitAuth);
authForgotPassword?.addEventListener('click', () => selectPasswordResetMode());
authResetForm?.addEventListener('submit', submitPasswordReset);
authResetBack?.addEventListener('click', () => {
    passwordResetMode = false;
    selectAuthMode('login');
    updateAuthMode();
});
authResendButton?.addEventListener('click', resendVerification);
authLogout.addEventListener('click', () => logoutCustomer());
authMfaSubmit.addEventListener('click', submitMfaLogin);
authMfaPasskey.addEventListener('click', startWebAuthnLogin);
authTotpStart.addEventListener('click', beginTotpEnrollment);
authTotpConfirm.addEventListener('click', confirmTotpEnrollment);
authTotpDisable.addEventListener('click', () => {
    authTotpDisablePanel.hidden = false;
    authTotpDisableCode.focus();
});
authTotpDisableConfirm.addEventListener('click', disableTotp);
authPasskeyRegister.addEventListener('click', registerPasskey);
authProfilePanel.addEventListener('submit', saveProfile);
authWishlistList.addEventListener('click', handleWishlistPanelAction);
document.querySelector('#success-close').addEventListener('click', closeSuccessModal);
globalSearchInput?.addEventListener('keydown', event => {
    if (event.key === 'Enter') {
        event.preventDefault();
        submitGlobalSearch();
    }
});
globalSearchForm?.addEventListener('submit', event => {
    event.preventDefault();
    submitGlobalSearch();
});

renderCart();
updateCustomerSummary();
renderHeroSlide(0);
startHeroAutoScroll();
restoreSession().finally(() => {
    loadProducts();
    loadDeliveryMethods();
    loadVerification();
    loadPasswordReset();
});

function getApiBase() {
    if (window.MINITECH_API_BASE) {
        return window.MINITECH_API_BASE.replace(/\/$/, '');
    }
    const queryApi = new URLSearchParams(window.location.search).get('api');
    if (queryApi) {
        return queryApi.replace(/\/$/, '');
    }
    if (window.location.port !== '18070' && window.location.protocol !== 'file:') {
        return `${window.location.origin}/api`;
    }
    const host = window.location.hostname || '127.0.0.1';
    return `http://${host}:18080/api`;
}

function renderHeroSlide(index) {
    if (!heroSlides.length) return;
    heroIndex = (index + heroSlides.length) % heroSlides.length;
    heroSlides.forEach((slide, slideIndex) => {
        const active = slideIndex === heroIndex;
        slide.hidden = !active;
        slide.classList.toggle('is-active', active);
        slide.setAttribute('aria-hidden', String(!active));
    });
    heroTopics.forEach((topic, topicIndex) => {
        const active = topicIndex === heroIndex;
        topic.classList.toggle('is-active', active);
        topic.setAttribute('aria-selected', String(active));
    });
}

function moveHeroSlide(direction) {
    renderHeroSlide(heroIndex + direction);
    if (!heroPaused && !heroCarousel?.matches(':hover') && !heroCarousel?.matches(':focus-within')) {
        startHeroAutoScroll();
    }
}

function startHeroAutoScroll() {
    pauseHeroAutoScroll();
    if (heroPaused || heroSlides.length < 2) return;
    heroAutoScroll = window.setInterval(() => renderHeroSlide(heroIndex + 1), 6500);
}

function pauseHeroAutoScroll() {
    if (heroAutoScroll !== null) {
        window.clearInterval(heroAutoScroll);
        heroAutoScroll = null;
    }
}

function resumeHeroAutoScroll() {
    if (!heroPaused && !heroCarousel?.matches(':hover') && !heroCarousel?.matches(':focus-within')) {
        startHeroAutoScroll();
    }
}

function toggleHeroAutoScroll() {
    heroPaused = !heroPaused;
    if (heroPause) {
        heroPause.textContent = heroPaused ? '▶' : 'Ⅱ';
        heroPause.setAttribute('aria-label', heroPaused
            ? 'Wznów automatyczne przewijanie'
            : 'Wstrzymaj automatyczne przewijanie');
    }
    if (heroPaused) pauseHeroAutoScroll();
    else startHeroAutoScroll();
}

async function api(path, options = {}, retryAfterRefresh = true) {
    const response = await fetch(`${API_BASE}${path}`, {
        ...options,
        cache: options.cache || 'no-store',
        credentials: 'include',
        headers: {
            ...(options.body ? { 'Content-Type': 'application/json' } : {}),
            ...(state.accessToken ? { Authorization: `Bearer ${state.accessToken}` } : {}),
            ...(options.headers || {})
        }
    });
    const raw = await response.text();
    let data = raw;
    try {
        data = raw ? JSON.parse(raw) : null;
    } catch {
        // Keep non-JSON downstream errors readable.
    }
    if (response.status === 401 && retryAfterRefresh && canRefresh(path) && await refreshSession()) {
        return api(path, options, false);
    }
    if (!response.ok) {
        const message = data && typeof data === 'object'
            ? (data.message || data.error || `HTTP ${response.status}`)
            : raw || `HTTP ${response.status}`;
        const error = new Error(message);
        error.status = response.status;
        error.path = path;
        throw error;
    }
    return data;
}

function canRefresh(path) {
    return !['/customers/login', '/customers/register', '/customers/refresh', '/customers/logout']
        .includes(path);
}

async function refreshSession() {
    if (refreshPromise) return refreshPromise;
    if (!sessionGuard?.isFresh()) {
        await expireBrowserSession(false);
        return false;
    }
    refreshPromise = fetch(`${API_BASE}/customers/refresh`, {
        method: 'POST',
        credentials: 'include',
        cache: 'no-store',
        headers: { Accept: 'application/json' }
    }).then(async response => {
        if (!response.ok) {
            if (response.status === 401 || response.status === 403) {
                sessionGuard?.clear();
                clearCustomerSession();
            }
            return false;
        }
        const session = await response.json();
        setSession(session);
        return true;
    }).catch(() => false).finally(() => {
        refreshPromise = null;
    });
    return refreshPromise;
}

async function restoreSession() {
    if (!sessionGuard?.isFresh()) {
        await expireBrowserSession(false);
        return false;
    }
    for (let attempt = 0; attempt < 3; attempt += 1) {
        if (await refreshSession()) return true;
        if (attempt < 2) {
            await new Promise(resolve => window.setTimeout(resolve, 250 * (attempt + 1)));
        }
    }
    return false;
}

async function loadProducts() {
    if (catalogMessage) setCatalogMessage('Ładowanie katalogu…');
    try {
        const products = await api('/products');
        state.products = Array.isArray(products) ? products.filter(product => product.active) : [];
        pruneCompareIds();
        state.cart.forEach(item => {
            const currentProduct = state.products.find(product => product.id === item.id);
            if (currentProduct) item.price = Number(currentProduct.price);
        });
        saveCart();
        await loadInventoryStock();
        fillCategories();
        fillManufacturers();
        applyCatalogUrlFilters();
        if (state.customer) {
            await loadWishlist();
        } else {
            renderWishlistPanel();
        }
        await loadHotShotPrices();
        renderHighlights();
        renderProducts();
        renderRecentlyViewed();
        await loadBestsellers();
        renderCart();
        renderCompareControls();
        if (catalogMessage) setCatalogMessage(`${state.products.length} aktywnych produktów · API Gateway ${API_BASE}`);
    } catch (error) {
        if (productGrid) productGrid.innerHTML = '';
        if (catalogMessage) setCatalogMessage(`Nie można pobrać katalogu: ${error.message}`, true);
    }
}

async function loadBestsellers() {
    if (!bestsellerGrid) return;
    try {
        const ranking = await api('/orders/bestsellers?limit=6');
        const rankedProducts = (Array.isArray(ranking) ? ranking : [])
            .map(entry => state.products.find(product => String(product.id) === String(entry.productId)))
            .filter(Boolean);
        bestsellerGrid.innerHTML = rankedProducts.length
            ? rankedProducts.map(product => productCard(product, 'highlight')).join('')
            : '<div class="catalog-message">Bestsellery pojawią się po pierwszych opłaconych zamówieniach.</div>';
        const note = document.querySelector('#bestsellers-note');
        if (note && rankedProducts.length) note.textContent = 'Ranking aktualizowany na podstawie opłaconych zamówień.';
    } catch {
        bestsellerGrid.innerHTML = '<div class="catalog-message">Nie można pobrać rankingu bestsellerów.</div>';
    }
}

async function loadInventoryStock() {
    const inventoryEntries = await Promise.all(state.products.map(async product => {
        try {
            return [product.id, await api(`/inventory/items/${product.id}`)];
        } catch {
            return [product.id, null];
        }
    }));
    state.inventory = Object.fromEntries(inventoryEntries);
}

async function loadHotShotPrices() {
    const candidates = state.products
        .filter(product => product.promotional && Number(product.discountPercent) > 0)
        .sort((left, right) => Number(right.discountPercent) - Number(left.discountPercent))
        .slice(0, 3);
    const entries = await Promise.all(candidates.map(async product => {
        try {
            const history = await api(`/promotions/history/${encodeURIComponent(product.id)}/lowest?days=30`);
            const lowest = Number(history?.lowestPrice);
            return [product.id, Number.isFinite(lowest) ? lowest : null];
        } catch {
            return [product.id, null];
        }
    }));
    state.lowestPrices = Object.fromEntries(entries);
}

async function loadDeliveryMethods() {
    try {
        const methods = await api('/deliveries/methods');
        state.deliveryMethods = Array.isArray(methods) ? methods : [];
        renderDeliveryMethods();
        await loadDeliveryQuote();
    } catch (error) {
        state.deliveryMethods = [];
        renderDeliveryMethods();
        if (deliveryQuote) {
            deliveryQuote.textContent = `Nie można pobrać metod dostawy: ${error.message}`;
            deliveryQuote.classList.add('error');
        }
    }
}

function renderDeliveryMethods() {
    if (!deliveryMethod) return;
    const selected = deliveryMethod.value;
    deliveryMethod.innerHTML = state.deliveryMethods.length
        ? state.deliveryMethods.map(method => `
            <option value="${escapeHtml(method.code)}">${escapeHtml(method.name)} — od ${formatMoney(method.basePrice)}</option>
        `).join('')
        : '<option value="">Brak dostępnych metod</option>';
    deliveryMethod.value = state.deliveryMethods.some(method => method.code === selected)
        ? selected
        : (state.deliveryMethods[0]?.code || '');
    syncDeliveryModeUi();
}

function syncDeliveryModeUi() {
    const pickup = deliveryMethod?.value === 'STORE_PICKUP';
    if (deliveryAddressFields) {
        deliveryAddressFields.hidden = pickup;
        deliveryAddressFields.querySelectorAll('input').forEach(input => {
            input.required = !pickup;
        });
    }
    if (pickupLocationField) pickupLocationField.hidden = !pickup;
    if (deliveryDate) deliveryDate.required = true;
}

function syncGuestContactFields() {
    const isGuest = purchaseMode !== 'account' || !state.customer;
    const recipientEmailField = document.querySelector('#recipient-email-field');
    const recipientEmailInput = document.querySelector('#recipient-email');
    if (guestContactFields) {
        guestContactFields.hidden = !isGuest;
        if (deliveryEmailInput) deliveryEmailInput.required = isGuest;
        if (deliveryPhoneInput) deliveryPhoneInput.required = false;
    }
    if (recipientEmailField) {
        recipientEmailField.hidden = isGuest;
        if (recipientEmailInput) recipientEmailInput.required = !isGuest;
    }
}

function cartSubtotal() {
    return roundMoney(state.cart.reduce((sum, item) => sum + Number(item.price || 0) * item.quantity, 0));
}

function roundMoney(value) {
    return Math.round((Number(value || 0) + Number.EPSILON) * 100) / 100;
}

function renderPriceSummary() {
    if (!summaryGross) return;
    const productsGross = roundMoney(cartSubtotal());
    const discountGross = roundMoney(Math.min(productsGross, Math.max(0, Number(state.coupon?.discountAmount || 0))));
    const deliveryGross = roundMoney(state.deliveryQuote?.deliveryCost || 0);
    const gross = roundMoney(Math.max(0, productsGross - discountGross + deliveryGross));
    const net = roundMoney(gross / 1.23);
    const vat = roundMoney(gross - net);

    summaryProductsGross.textContent = formatMoney(productsGross);
    summaryDiscountRow.hidden = discountGross <= 0;
    summaryDiscountLabel.textContent = state.coupon?.code
        ? `Rabat (${state.coupon.code}${state.coupon.discountPercent ? ` · ${state.coupon.discountPercent}%` : ''})`
        : 'Rabat';
    summaryDiscount.textContent = `−${formatMoney(discountGross)}`;
    summaryDeliveryGross.textContent = formatMoney(deliveryGross);
    summaryDeliveryDate.textContent = state.deliveryQuote?.selectedDate
        ? `${state.deliveryQuote.fulfillmentType === 'STORE_PICKUP' ? 'Odbiór' : 'Dostawa'} · ${formatDeliveryDate(state.deliveryQuote.selectedDate)}`
        : '—';
    summaryNet.textContent = formatMoney(net);
    summaryVat.textContent = formatMoney(vat);
    summaryGross.textContent = formatMoney(gross);
    if (summaryStepProducts) summaryStepProducts.textContent = formatMoney(productsGross);
    if (summaryStepDiscountRow) summaryStepDiscountRow.hidden = discountGross <= 0;
    if (summaryStepDiscount) summaryStepDiscount.textContent = `−${formatMoney(discountGross)}`;
    if (summaryStepDelivery) summaryStepDelivery.textContent = formatMoney(deliveryGross);
    if (summaryStepNet) summaryStepNet.textContent = formatMoney(net);
    if (summaryStepVat) summaryStepVat.textContent = formatMoney(vat);
    if (summaryStepGross) summaryStepGross.textContent = formatMoney(gross);
}

async function loadDeliveryQuote() {
    if (!deliveryMethod || !state.cart.length || !deliveryMethod.value) {
        state.deliveryQuote = null;
        if (deliveryQuote) deliveryQuote.textContent = 'Wybierz sposób dostawy, aby zobaczyć koszt.';
        renderPriceSummary();
        return null;
    }
    const postalCode = document.querySelector('#delivery-postal-code')?.value.trim() || '00-000';
    const itemCount = state.cart.reduce((sum, item) => sum + item.quantity, 0);
    try {
        const quote = await api('/deliveries/quote', {
            method: 'POST',
            body: JSON.stringify({
                methodCode: deliveryMethod.value,
                postalCode,
                itemCount,
                // JavaScript stores money as binary floating-point values. Without
                // normalisation a basket such as 2886.97 can be serialized as
                // 2886.9700000000003 and rejected by Delivery Service (@Digits(…, 2)).
                subtotal: roundMoney(cartSubtotal()),
                requestedDate: deliveryDate?.value || null
            })
        });
        if (deliveryDate && Array.isArray(quote.availableDates)) {
            const selected = deliveryDate.value;
            deliveryDate.innerHTML = quote.availableDates.map(date => `
                <option value="${escapeHtml(date)}">${escapeHtml(formatDeliveryDate(date))}</option>
            `).join('');
            deliveryDate.value = quote.availableDates.includes(selected)
                ? selected
                : (quote.selectedDate || quote.availableDates[0] || '');
        }
        state.deliveryQuote = quote;
        syncDeliveryModeUi();
        renderPriceSummary();
        if (deliveryQuote) {
            const eta = quote.etaMinDays === quote.etaMaxDays
                ? `${quote.etaMinDays} dzień`
                : `${quote.etaMinDays}–${quote.etaMaxDays} dni`;
            const modeLabel = quote.fulfillmentType === 'STORE_PICKUP' ? 'odbiór osobisty' : 'dostawa';
            deliveryQuote.textContent = `${quote.name}: ${modeLabel}, ${quote.freeDelivery ? '0,00 zł' : formatMoney(quote.deliveryCost)} · termin: ${formatDeliveryDate(quote.selectedDate)} · przewidywany czas: ${eta}. Razem: ${formatMoney(quote.totalPrice)}`;
            deliveryQuote.classList.remove('error');
        }
        return quote;
    } catch (error) {
        state.deliveryQuote = null;
        renderPriceSummary();
        if (deliveryQuote) {
            deliveryQuote.textContent = `Nie można wyliczyć kosztu dostawy: ${error.message}`;
            deliveryQuote.classList.add('error');
        }
        throw error;
    }
}

function formatDeliveryDate(value) {
    if (!value) return 'brak terminu';
    const date = new Date(`${value}T12:00:00`);
    return Number.isNaN(date.getTime())
        ? value
        : date.toLocaleDateString('pl-PL', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' });
}

async function validatePromotionCode() {
    if (!promotionCodeInput || !promotionCodeFeedback) return null;
    const code = promotionCodeInput.value.trim();
    state.coupon = null;
    renderPriceSummary();
    if (!code) {
        promotionCodeFeedback.textContent = 'Wpisz kod rabatowy.';
        promotionCodeFeedback.className = 'coupon-feedback error';
        return null;
    }
    promotionCodeApply.disabled = true;
    promotionCodeFeedback.textContent = 'Sprawdzam kod…';
    promotionCodeFeedback.className = 'coupon-feedback';
    try {
        const response = await api('/promotions/codes/validate', {
            method: 'POST',
            body: JSON.stringify({ code, subtotal: roundMoney(cartSubtotal()) })
        });
        if (!response.valid) throw new Error(response.message || 'Kod rabatowy jest nieprawidłowy.');
        state.coupon = response;
        renderPriceSummary();
        promotionCodeInput.value = response.code;
        promotionCodeFeedback.textContent = `${response.message} Rabat: ${formatMoney(response.discountAmount)} (${response.discountPercent}%).`;
        promotionCodeFeedback.className = 'coupon-feedback success';
        return response;
    } catch (error) {
        state.coupon = null;
        renderPriceSummary();
        promotionCodeFeedback.textContent = error.message;
        promotionCodeFeedback.className = 'coupon-feedback error';
        throw error;
    } finally {
        promotionCodeApply.disabled = false;
    }
}

function fillCategories() {
    if (!categoryFilter) return;
    const selected = categoryFilter.value;
    const categories = [...new Set(state.products.map(product => product.category).filter(Boolean))].sort();
    categoryFilter.innerHTML = '<option value="all">Wszystkie</option>' + categories
        .map(category => `<option value="${escapeHtml(category)}">${escapeHtml(category)}</option>`)
        .join('');
    categoryFilter.value = categories.includes(selected) ? selected : 'all';
}

function fillManufacturers(selectedOverride = null) {
    if (!manufacturerFilter) return;
    const category = categoryFilter?.value || 'all';
    const selected = selectedOverride === null ? manufacturerFilter.value : selectedOverride;
    const categoryProducts = category === 'all'
        ? state.products
        : state.products.filter(product => product.category === category);
    const manufacturers = [...new Set(categoryProducts.map(product => product.manufacturer).filter(Boolean))].sort();
    manufacturerFilter.innerHTML = '<option value="all">Wszyscy</option>' + manufacturers
        .map(manufacturer => `<option value="${escapeHtml(manufacturer)}">${escapeHtml(manufacturer)}</option>`)
        .join('');
    manufacturerFilter.value = manufacturers.includes(selected) ? selected : 'all';
}

function fillTechnicalParameters(selectedOverride = null) {
    if (!technicalParameterFilter) return;
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
        : discoveredKeys.sort((left, right) => left.localeCompare(right, 'pl')));
    const categorySelected = category !== 'all';
    const emptyLabel = categorySelected ? 'Wszystkie parametry' : 'Wybierz kategorię';
    technicalParameterFilter.innerHTML = `<option value="all">${emptyLabel}</option>` + keys
        .map(key => `<option value="${escapeHtml(key)}">${escapeHtml(TECHNICAL_PARAMETER_LABELS[key] || key)}</option>`)
        .join('');
    technicalParameterFilter.value = keys.includes(selected) ? selected : 'all';
    technicalParameterFilter.disabled = !categorySelected || keys.length === 0;
    if (technicalValueFilter) {
        technicalValueFilter.disabled = !categorySelected || keys.length === 0;
        technicalValueFilter.placeholder = categorySelected && keys.length > 0
            ? 'np. DDR5, 32GB'
            : 'Najpierw wybierz kategorię';
        technicalValueFilter.setAttribute('aria-label', categorySelected
            ? `Wartość parametru kategorii ${category}`
            : 'Wartość parametru — wybierz kategorię');
    }
    const parameterLabel = document.querySelector('#technical-parameter-label');
    if (parameterLabel) parameterLabel.textContent = categorySelected
        ? `Parametr: ${category}`
        : 'Parametr kategorii';
}

function resetTechnicalFilterForCategory() {
    if (technicalParameterFilter) technicalParameterFilter.value = 'all';
    if (technicalValueFilter) technicalValueFilter.value = '';
    fillTechnicalParameters('all');
}

function applyCatalogUrlFilters() {
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

function optionalPriceFilterValue(value) {
    const raw = String(value ?? '').trim().replace(',', '.');
    if (!raw) return null;
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : null;
}

function renderProducts() {
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
    productGrid.innerHTML = visible.map(productCard).join('');
}

function renderHighlights() {
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
    hotShotItems = promotions.slice(0, 3);
    hotShotIndex = 0;
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

function updateHeroProducts(newProducts, promotions, rotationDate) {
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
        const badge = slide.querySelector('[data-hero-badge]');
        const lead = slide.querySelector('[data-hero-lead]');
        const link = slide.querySelector('[data-hero-link]');
        const stock = stockFor(product.id);
        const available = Number.isInteger(stock?.availableQuantity)
            ? `${stock.availableQuantity} szt.`
            : 'sprawdź dostępność';
        const price = finitePrice(product.price, product.regularPrice);

        slide.dataset.productId = String(product.id);
        if (image && product.imageUrl) {
            image.src = product.imageUrl;
            image.alt = `${product.name} — produkt rotacji ${dateKey}`;
        }
        if (label) label.textContent = `${product.category || 'TECH'} / ${product.name}`.toUpperCase();
        if (link) link.setAttribute('href', `product.html?id=${encodeURIComponent(product.id)}`);

        if (kind === 'promo') {
            const regularPrice = finitePrice(product.regularPrice, price);
            const savings = Math.max(0, regularPrice - price);
            if (badge) badge.textContent = `-${Number(product.discountPercent || 0)}%`;
            if (lead) lead.textContent = `${product.name}: ${formatMoney(price)}. Oszczędzasz ${formatMoney(savings)}; dostępne ${available}. Oferta z ${dateKey}.`;
        } else if (kind === 'new') {
            if (badge) badge.textContent = 'NOWOŚĆ';
            if (lead) lead.textContent = `${product.name} od ${formatMoney(price)}. Dostępne ${available}. Codzienny wybór Product i Promotion Service z ${dateKey}.`;
        } else {
            if (badge) badge.textContent = 'ZESTAW DNIA';
            if (lead) lead.textContent = `${product.name} — ${product.manufacturer || 'MiniTech'} ${product.model || ''}. Cena ${formatMoney(price)}, dostępne ${available}.`;
        }
    });
}

function selectDailyProduct(products, dateKey, salt) {
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

function scheduleDailyRotationRefresh() {
    if (dailyRotationRefreshTimer !== null) window.clearTimeout(dailyRotationRefreshTimer);
    const now = new Date();
    const nextUtcDay = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1, 0, 0, 2);
    dailyRotationRefreshTimer = window.setTimeout(async () => {
        dailyRotationRefreshTimer = null;
        await loadProducts();
    }, Math.max(1000, nextUtcDay - now.getTime()));
}

function renderHotShotCard() {
    if (!hotShotSection || !hotShotCard) return;
    if (!hotShotItems.length) {
        hotShotSection.hidden = true;
        stopHotShotRotation();
        return;
    }

    const product = hotShotItems[hotShotIndex % hotShotItems.length];
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
                <p class="hot-shot-lowest">Najniższa cena z 30 dni: ${Number.isFinite(lowestPrice) ? formatMoney(lowestPrice) : 'brak danych'}</p>
                <div class="hot-shot-stock-row"><span>Pozostało: <strong>${stockLabel}</strong></span><span>Sprzedano: <strong>${soldLabel}</strong></span></div>
                <div class="hot-shot-stock-bar" role="progressbar" aria-label="Udział sprzedanych sztuk" aria-valuenow="${soldPercent}" aria-valuemin="0" aria-valuemax="100"><span style="width: ${soldPercent}%"></span></div>
                <button class="primary-button hot-shot-buy" type="button" data-action="add" data-product-id="${product.id}" ${available === 0 ? 'disabled' : ''}>${available === 0 ? 'Brak produktu' : 'Kup teraz'}</button>
            </div>
        </article>`;

    if (hotShotDots) {
        hotShotDots.innerHTML = hotShotItems.map((item, index) => `
            <button class="hot-shot-dot ${index === hotShotIndex ? 'is-active' : ''}" type="button"
                data-hot-shot-index="${index}" aria-label="Pokaż ofertę ${index + 1}" aria-current="${index === hotShotIndex}"></button>`).join('');
    }
    if (hotShotPrev) hotShotPrev.hidden = hotShotItems.length < 2;
    if (hotShotNext) hotShotNext.hidden = hotShotItems.length < 2;
    if (hotShotRotationInterval === null && hotShotItems.length > 1) startHotShotRotation();
}

function startHotShotRotation() {
    stopHotShotRotation();
    if (hotShotItems.length < 2) return;
    hotShotRotationInterval = window.setInterval(() => {
        if (hotShotRotationPaused) return;
        rotateHotShot(1);
    }, 9000);
}

function stopHotShotRotation() {
    if (hotShotRotationInterval !== null) {
        window.clearInterval(hotShotRotationInterval);
        hotShotRotationInterval = null;
    }
}

function rotateHotShot(direction) {
    if (hotShotItems.length < 2) return;
    hotShotIndex = (hotShotIndex + direction + hotShotItems.length) % hotShotItems.length;
    renderHotShotCard();
}

function renderRecentlyViewed() {
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

function readRecentlyViewedIds() {
    try {
        const stored = JSON.parse(localStorage.getItem('minitech-recent-products') || '[]');
        return Array.isArray(stored)
            ? stored.map(entry => Number(entry?.id)).filter(Number.isFinite)
            : [];
    } catch {
        return [];
    }
}

function startPromotionCountdown() {
    const note = document.querySelector('#promotions-rotation-note');
    if (!note && !hotShotCountdown) return;
    if (promotionCountdownInterval !== null) window.clearInterval(promotionCountdownInterval);
    updatePromotionCountdown();
    promotionCountdownInterval = window.setInterval(updatePromotionCountdown, 1000);
}

function updatePromotionCountdown() {
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

function updateNewProductsCarousel(enabled) {
    pauseNewProductsAutoScroll();
    const hidden = !enabled;
    if (newProductsPrev) newProductsPrev.hidden = hidden;
    if (newProductsNext) newProductsNext.hidden = hidden;
    if (newProductsGrid) newProductsGrid.scrollLeft = 0;
    if (enabled) startNewProductsAutoScroll();
}

function scrollNewProducts(direction) {
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

function startNewProductsDrag(event) {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    if (event.target.closest('a, button, input, select, textarea')) return;
    pauseNewProductsAutoScroll();
    newProductsDrag = {
        startX: event.clientX,
        startScrollLeft: newProductsGrid.scrollLeft,
        moved: false
    };
    newProductsGrid.classList.add('is-dragging');
    newProductsGrid.setPointerCapture(event.pointerId);
}

function moveNewProductsDrag(event) {
    if (!newProductsDrag) return;
    const distance = event.clientX - newProductsDrag.startX;
    if (Math.abs(distance) > 5) newProductsDrag.moved = true;
    if (!newProductsDrag.moved) return;
    event.preventDefault();
    newProductsGrid.scrollLeft = newProductsDrag.startScrollLeft - distance;
}

function endNewProductsDrag(event) {
    if (!newProductsDrag) return;
    if (newProductsDrag.moved) suppressNewProductsClick = true;
    newProductsGrid.classList.remove('is-dragging');
    if (newProductsGrid.hasPointerCapture(event.pointerId)) newProductsGrid.releasePointerCapture(event.pointerId);
    newProductsDrag = null;
    resumeNewProductsAutoScroll();
}

function startNewProductsAutoScroll() {
    pauseNewProductsAutoScroll();
    if (!newProductsGrid || newProductsGrid.children.length < 2) return;
    newProductsAutoScroll = window.setInterval(() => scrollNewProducts(1), 5000);
}

function resumeNewProductsAutoScroll() {
    if (!newProductsCarousel?.matches(':hover') && !newProductsCarousel?.matches(':focus-within')) {
        startNewProductsAutoScroll();
    }
}

function pauseNewProductsAutoScroll() {
    if (newProductsAutoScroll !== null) {
        window.clearInterval(newProductsAutoScroll);
        newProductsAutoScroll = null;
    }
}

function selectCategoryFromStrip(event) {
    if (document.body.dataset.page !== 'catalog') return;
    event.preventDefault();
    if (!categoryFilter) return;
    categoryFilter.value = event.currentTarget.dataset.category;
    fillManufacturers();
    resetTechnicalFilterForCategory();
    renderProducts();
    document.querySelector('#catalog')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function syncCategoryStrip(category = categoryFilter?.value || 'all') {
    categoryPills.forEach(pill => pill.classList.toggle('active', pill.dataset.category === category));
}

function productCard(product, variant = 'catalog') {
    const symbol = escapeHtml((product.category || 'MT').slice(0, 3).toUpperCase());
    const stock = stockFor(product.id);
    const availableQuantity = stock?.availableQuantity;
    const soldOut = availableQuantity === 0;
    const visual = product.imageUrl
        ? `<img class="product-visual-image" src="${escapeHtml(product.imageUrl)}" alt="${escapeHtml(product.name)}" loading="lazy">`
        : `<span class="visual-symbol">${symbol}</span>`;
    const emphasizeNew = variant === 'new';
    const badgeLabel = emphasizeNew && product.newProduct
        ? 'NOWOŚĆ'
        : (Number(product.discountPercent) > 0
        ? `-${Number(product.discountPercent)}%`
        : (product.newProduct ? 'NOWOŚĆ' : 'ACTIVE'));
    const badgeClass = emphasizeNew && product.newProduct
        ? 'product-badge-new'
        : (Number(product.discountPercent) > 0
        ? 'product-badge-promotion'
        : (product.newProduct ? 'product-badge-new' : ''));
    const compared = state.compareIds.has(Number(product.id));
    return `
        <article class="product-card ${variant !== 'catalog' ? 'highlight-card' : ''}">
            <a class="product-visual" href="product.html?id=${encodeURIComponent(product.id)}" aria-label="Zobacz szczegóły produktu ${escapeHtml(product.name)}">
                <span class="product-badge ${badgeClass}">${badgeLabel}</span>
                ${visual}
            </a>
            <h3><a class="product-detail-link" href="product.html?id=${encodeURIComponent(product.id)}">${escapeHtml(product.name)}</a></h3>
            ${promotionSavingsMarkup(product)}
            <div class="product-footer">
                ${priceMarkup(product)}
                <div class="product-actions">
                    <a class="product-detail-button" href="product.html?id=${encodeURIComponent(product.id)}">Szczegóły</a>
                    ${variant === 'catalog' ? `<button class="compare-button ${compared ? 'is-active' : ''}" type="button" data-action="compare" data-product-id="${product.id}" aria-pressed="${compared}" title="${compared ? 'Usuń z porównania' : 'Dodaj do porównania'}">${compared ? '✓ Porównaj' : 'Porównaj'}</button>` : ''}
                    <button class="wishlist-button ${state.wishlist.has(Number(product.id)) ? 'is-active' : ''}" type="button" data-action="wishlist" data-product-id="${product.id}" aria-label="${state.wishlist.has(Number(product.id)) ? 'Usuń z ulubionych' : 'Dodaj do ulubionych'}" title="${state.wishlist.has(Number(product.id)) ? 'Usuń z ulubionych' : 'Dodaj do ulubionych'}" aria-pressed="${state.wishlist.has(Number(product.id))}">${state.wishlist.has(Number(product.id)) ? '♥' : '♡'}</button>
                    <button class="add-button ${soldOut ? 'add-button-sold-out' : ''}" type="button" data-action="add" data-product-id="${product.id}" ${soldOut ? 'disabled' : ''}>${soldOut ? 'Brak produktu' : 'Dodaj do koszyka'}</button>
                </div>
            </div>
        </article>`;
}

function priceMarkup(product) {
    const salePrice = finitePrice(product.price, product.regularPrice);
    const regularPrice = finitePrice(product.regularPrice, salePrice);
    const hasDiscount = Number(product.discountPercent) > 0 && regularPrice > salePrice;
    return `<div class="product-price-wrap">
        <strong class="product-price">${formatMoney(salePrice)}</strong>
        ${hasDiscount ? `<span class="product-price-regular">${formatMoney(regularPrice)}</span>` : ''}
    </div>`;
}

function promotionSavingsMarkup(product) {
    const salePrice = finitePrice(product.price, product.regularPrice);
    const regularPrice = finitePrice(product.regularPrice, salePrice);
    const savings = regularPrice - salePrice;
    return Number(product.discountPercent) > 0 && savings > 0
        ? `<p class="product-promotion-note">Oszczędzasz ${formatMoney(savings)} · promocja dnia</p>`
        : '';
}

function handleProductAction(event) {
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

async function toggleWishlist(productId, button = null) {
    if (!state.customer) {
        openAuthModal();
        showToast('Zaloguj się, aby zapisywać ulubione produkty.');
        return;
    }
    const numericProductId = Number(productId);
    const isSaved = state.wishlist.has(numericProductId);
    if (button) button.disabled = true;
    try {
        await api(`/wishlists/customer/${encodeURIComponent(state.customer.id)}/products/${encodeURIComponent(productId)}`, {
            method: isSaved ? 'DELETE' : 'PUT'
        });
        if (isSaved) {
            state.wishlist.delete(numericProductId);
            showToast('Usunięto z ulubionych');
        } else {
            state.wishlist.add(numericProductId);
            showToast('Dodano do ulubionych');
        }
        renderProducts();
        renderHighlights();
        renderWishlistPanel();
    } catch (error) {
        showToast(`Nie udało się zmienić ulubionych: ${error.message}`);
    } finally {
        if (button) button.disabled = false;
    }
}

function handleCartAction(event) {
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

function renderCart() {
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
            ? `Brak wystarczającej liczby produktów: ${formatCartStockIssues(stockIssues)}. Zmniejsz ilość lub usuń pozycję z koszyka.`
            : '';
    }
    updateCartThresholdNote();
    cartItems.innerHTML = state.cart.map(item => {
        const stock = stockFor(item.id);
        const atLimit = Number.isInteger(stock?.availableQuantity) && item.quantity >= stock.availableQuantity;
        return `
        <div class="cart-row">
            <div>
                <div class="cart-row-name">${escapeHtml(item.name)}</div>
                <div class="cart-row-meta">${formatMoney(item.price)} · ${escapeHtml(item.sku || '')}${Number.isInteger(stock?.availableQuantity) ? ` · magazyn: ${stock.availableQuantity} szt.` : ''}</div>
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

function updateCartThresholdNote() {
    if (!cartThresholdNote) return;
    if (!state.cart.length) {
        cartThresholdNote.textContent = '';
    } else if (cartSubtotal() > LOGIN_REQUIRED_THRESHOLD && !state.customer) {
        cartThresholdNote.textContent = 'Zamówienie powyżej 1 000 zł wymaga zalogowania lub założenia konta.';
    } else if (state.customer) {
        cartThresholdNote.textContent = `Zamówienie zostanie zapisane na koncie ${state.customer.email}.`;
    } else {
        cartThresholdNote.textContent = 'Do 1 000 zł możesz kupić jako gość — konto nie jest wymagane.';
    }
}

function renderCartPreview() {
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

function openCartPreview() {
    if (!cartPreview || !cartToggle) return;
    if (cartPreviewCloseTimer !== null) window.clearTimeout(cartPreviewCloseTimer);
    cartPreview.hidden = false;
    cartToggle.setAttribute('aria-expanded', 'true');
}

function closeCartPreview() {
    if (!cartPreview || !cartToggle) return;
    if (cartPreviewCloseTimer !== null) {
        window.clearTimeout(cartPreviewCloseTimer);
        cartPreviewCloseTimer = null;
    }
    cartPreview.hidden = true;
    cartToggle.setAttribute('aria-expanded', 'false');
}

function scheduleCloseCartPreview() {
    if (cartPreviewCloseTimer !== null) window.clearTimeout(cartPreviewCloseTimer);
    cartPreviewCloseTimer = window.setTimeout(closeCartPreview, 160);
}

function toggleCartPreview() {
    if (!cartPreview) return;
    if (cartPreview.hidden) openCartPreview();
    else closeCartPreview();
}

function clearCart() {
    state.cart = [];
    clearCheckoutAttempt();
    saveCart();
    renderCart();
}

function stockFor(productId) {
    return state.inventory[productId] || null;
}

async function refreshCartInventory() {
    const productIds = [...new Set(state.cart.map(item => Number(item.id)).filter(Number.isFinite))];
    const inventoryEntries = await Promise.all(productIds.map(async productId => {
        try {
            return [productId, await api(`/inventory/items/${productId}`)];
        } catch {
            return [productId, null];
        }
    }));
    state.inventory = { ...state.inventory, ...Object.fromEntries(inventoryEntries) };
}

function getCartStockIssues() {
    return state.cart.map(item => {
        const availableQuantity = Number(stockFor(item.id)?.availableQuantity);
        if (!Number.isFinite(availableQuantity) || item.quantity <= availableQuantity) return null;
        return { item, availableQuantity };
    }).filter(Boolean);
}

function getSoldOutCartItems() {
    return state.cart.filter(item => Number(stockFor(item.id)?.availableQuantity) === 0);
}

function formatCartStockIssues(stockIssues) {
    return stockIssues.map(({ item, availableQuantity }) =>
        `${item.name} — dostępne ${Math.max(0, Math.trunc(availableQuantity))} szt., w koszyku ${item.quantity}`
    ).join('; ');
}

function insufficientStockError(stockIssues) {
    const error = new Error(`Brak wystarczającej liczby produktów: ${formatCartStockIssues(stockIssues)}. Zmniejsz ilość lub usuń pozycję z koszyka.`);
    error.code = 'INSUFFICIENT_STOCK';
    return error;
}

function zeroQuantityCartError(items) {
    const names = items.map(item => item.name).join(', ');
    const subject = items.length > 1 ? `Pozycje ${names} miały` : `Pozycja ${names} miała`;
    const ending = items.length > 1 ? 'zostały usunięte' : 'została usunięta';
    const error = new Error(`${subject} ilość 0 i ${ending} z koszyka. Sprawdź aktualny koszyk.`);
    error.code = 'ZERO_QUANTITY_REMOVED';
    return error;
}

function resetCheckoutAfterCartChange() {
    state.coupon = null;
    state.deliveryQuote = null;
    if (promotionCodeInput) promotionCodeInput.value = '';
    if (promotionCodeFeedback) promotionCodeFeedback.textContent = '';
    clearCheckoutAttempt();
    saveCart();
    renderCart();
    showCartPage();
}

function soldOutCartError(items) {
    const names = items.map(item => item.name).join(', ');
    const error = new Error(`Produkt${items.length > 1 ? 'y' : ''} ${names} został${items.length > 1 ? 'y' : ''} wyprzedan${items.length > 1 ? 'e' : 'y'} i usunięt${items.length > 1 ? 'e' : 'y'} z koszyka. Sprawdź aktualny koszyk.`);
    error.code = 'SOLD_OUT_REMOVED';
    return error;
}

async function ensureCartStockAvailable() {
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

async function submitCheckout(event) {
    event.preventDefault();
    if (checkoutSubmissionInProgress) return;
    if (!state.cart.length) {
        setCheckoutStatus('Najpierw dodaj produkt do koszyka.', true);
        return;
    }

    const recipient = document.querySelector('#recipient-email').value.trim()
        || deliveryEmailInput?.value.trim() || '';
    const paymentMethod = document.querySelector('#payment-method').value;
    if (purchaseMode === 'account' && !state.customer) {
        setCheckoutStatus('Wybrano zakup przez konto. Zaloguj się przyciskiem obok koszyka.', true);
        pendingPurchaseMode = 'account';
        openAuthModal();
        return;
    }
    checkoutSubmissionInProgress = true;
    checkoutSubmit.disabled = true;
    if (checkoutSummarySubmit) checkoutSummarySubmit.disabled = true;
    setCheckoutStatus('Tworzę zamówienie, rezerwuję stan i przetwarzam płatność…');
    const deliveryAddress = readDeliveryAddress();
    const results = [];
    let createdOrderId = null;
    let quote;

    try {
        await ensureCartStockAvailable();
        if (promotionCodeInput?.value.trim()) {
            await validatePromotionCode();
        }
        quote = await loadDeliveryQuote();
        if (!quote) {
            throw new Error('Wybierz dostępną metodę dostawy');
        }
        const selectedDeliveryDate = deliveryDate?.value || quote.selectedDate;
        if (!selectedDeliveryDate) {
            throw new Error('Wybierz dzień dostawy lub odbioru');
        }
        const billingSnapshot = invoiceRequested?.checked ? readBillingData(deliveryAddress) : null;
        const attemptFingerprint = checkoutFingerprint({
            recipient,
            paymentMethod,
            deliveryAddress,
            deliveryMethod: quote.methodCode,
            deliveryCost: quote.deliveryCost,
            selectedDeliveryDate,
            promotionCode: state.coupon?.code || null,
            customerId: purchaseMode === 'account' ? state.customer.id : null,
            invoiceRequested: Boolean(invoiceRequested?.checked),
            billingData: billingSnapshot
        });
        let checkoutAttempt = loadCheckoutAttempt();
        if (!checkoutAttempt || checkoutAttempt.version !== 2
                || checkoutAttempt.fingerprint !== attemptFingerprint
                || !checkoutAttempt.orderIdempotencyKey) {
            checkoutAttempt = {
                version: 2,
                fingerprint: attemptFingerprint,
                orderIdempotencyKey: crypto.randomUUID(),
                orderId: null,
                completed: false
            };
            saveCheckoutAttempt(checkoutAttempt);
        }
        const orderIdempotencyKey = checkoutAttempt.orderIdempotencyKey;
        const order = await api('/orders', {
            method: 'POST',
            headers: { 'Idempotency-Key': orderIdempotencyKey },
            body: JSON.stringify({
                items: state.cart.map(item => ({ productId: item.id, quantity: item.quantity })),
                customerId: purchaseMode === 'account' ? state.customer.id : null,
                customerEmail: recipient,
                deliveryAddress,
                deliveryMethod: quote.methodCode,
                deliveryCost: quote.deliveryCost,
                promotionCode: state.coupon?.code || null,
                fulfillmentType: quote.fulfillmentType,
                deliveryDate: selectedDeliveryDate,
                deliverySlot: null,
                pickupLocation: quote.fulfillmentType === 'STORE_PICKUP'
                    ? (pickupLocation?.value || 'MiniTech Store — Warszawa') : null,
                invoiceRequested: Boolean(invoiceRequested?.checked)
            })
        });
        createdOrderId = order.id;
        checkoutAttempt.orderId = order.id;
        saveCheckoutAttempt(checkoutAttempt);
        const payment = await api('/payments', {
            method: 'POST',
            headers: { 'Idempotency-Key': `payment-${order.id}-${orderIdempotencyKey}` },
            body: JSON.stringify({ orderId: order.id, paymentMethod, simulateFailure: false, notificationRecipient: recipient,
                customerId: purchaseMode === 'account' ? state.customer.id : null,
                invoiceRequested: Boolean(invoiceRequested?.checked),
                billingData: billingSnapshot })
        });
        checkoutAttempt.completed = true;
        saveCheckoutAttempt(checkoutAttempt);
        results.push({ order, payment });
        state.cart = [];
        state.coupon = null;
        clearCheckoutAttempt();
        if (promotionCodeInput) promotionCodeInput.value = '';
        if (promotionCodeFeedback) promotionCodeFeedback.textContent = '';
        saveCart();
        renderCart();
        // Odświeżenie katalogu nie może zmienić poprawnie opłaconego zamówienia
        // w pozorny błąd transakcji. Ewentualny problem odświeżenia jest tylko
        // ostrzeżeniem i zostanie naprawiony przy kolejnym pobraniu danych.
        try {
            await loadInventoryStock();
            renderHighlights();
            renderProducts();
            await loadBestsellers();
        } catch (refreshError) {
            console.warn('Nie udało się odświeżyć katalogu po zakupie:', refreshError);
        }
        const orderNumbers = results.map(result => `#${result.order.id}`).join(', ');
        const deferredPayment = paymentMethod === 'CASH_ON_DELIVERY';
        setCheckoutStatus(`${deferredPayment ? 'Gotowe. Zamówienie przyjęte — płatność nastąpi przy odbiorze.' : 'Gotowe. Zamówienie zostało opłacone.'} Realizacja: ${quote.name}, termin: ${formatDeliveryDate(selectedDeliveryDate)} (${formatMoney(quote.deliveryCost)}). Potwierdzenie powinno pojawić się w Mailpit. Zamówienie ${orderNumbers}.`);
        checkoutStatus.classList.add('success');
        returnToStoreHome(orderNumbers, recipient, deferredPayment);
    } catch (error) {
        if (['INSUFFICIENT_STOCK', 'SOLD_OUT_REMOVED', 'ZERO_QUANTITY_REMOVED'].includes(error?.code)) {
            setCheckoutStatus(error.message, true);
        } else {
            const completedOrderNumbers = results.map(result => `#${result.order.id}`).join(', ');
            const createdOrderMessage = createdOrderId && !completedOrderNumbers
                ? ` Utworzone zamówienie #${createdOrderId} pozostało zapisane i można bezpiecznie ponowić płatność.`
                : '';
            const partialResult = completedOrderNumbers
                ? ` Zapisane i opłacone zamówienie: ${completedOrderNumbers}. Ponowienie bezpiecznie wykorzysta idempotencję.`
                : createdOrderMessage;
            setCheckoutStatus(`Nie udało się dokończyć zamówienia: ${error.message}. Sprawdź status Order/Payment Service.${partialResult}`, true);
        }
    } finally {
        checkoutSubmissionInProgress = false;
        checkoutSubmit.disabled = false;
        if (checkoutSummarySubmit) checkoutSummarySubmit.disabled = false;
    }
}

function openCheckoutChoice() {
    if (!state.cart.length) {
        showToast('Najpierw dodaj produkt do koszyka');
        return;
    }
    showCartPage();
    if (state.customer) {
        purchaseMode = 'account';
        showCheckoutPage();
        return;
    }
    if (cartSubtotal() > LOGIN_REQUIRED_THRESHOLD) {
        pendingPurchaseMode = 'account';
        showToast('Dla zamówień powyżej 1 000 zł wymagane jest konto.');
        openAuthModal();
        return;
    }
    checkoutChoiceModal.hidden = false;
}

function closeCheckoutChoice() {
    checkoutChoiceModal.hidden = true;
}

function choosePurchaseMode(mode) {
    closeCheckoutChoice();
    if (mode === 'guest' && cartSubtotal() > LOGIN_REQUIRED_THRESHOLD) {
        pendingPurchaseMode = 'account';
        showToast('Dla zamówień powyżej 1 000 zł wymagane jest konto.');
        openAuthModal();
        return;
    }
    purchaseMode = mode;
    if (mode === 'account' && !state.customer) {
        pendingPurchaseMode = mode;
        openAuthModal();
        return;
    }
    showCheckoutPage();
}

function showCheckoutPage() {
    checkoutSection.hidden = false;
    checkoutLayout.classList.remove('cart-only');
    checkoutLayout.classList.add('checkout-only');
    document.body.classList.remove('cart-screen-active');
    document.body.classList.add('checkout-screen-active');
    window.history.replaceState({}, document.title, '#checkout');
    window.scrollTo({ top: 0, behavior: 'auto' });
    setCheckoutStep('delivery');
    if (purchaseMode === 'account' && state.customer) {
        fillCustomer(state.customer);
        checkoutModeText.textContent = `Zamówienie na koncie ${state.customer.email}`;
        setCheckoutStatus(`Zamówienie zostanie zapisane na koncie ${state.customer.email}.`);
    } else {
        clearCustomerCheckoutFields();
        checkoutModeText.textContent = 'Kupuję jako gość';
        setCheckoutStatus('Kupujesz jako gość. Wpisz dane dostawy, e-mail i telefon.');
    }
    syncGuestContactFields();
    renderDeliveryMethods();
    renderPriceSummary();
    loadDeliveryQuote().catch(() => {});
}

function showCartPage() {
    checkoutSection.hidden = true;
    checkoutLayout.classList.add('cart-only');
    checkoutLayout.classList.remove('checkout-only');
    document.body.classList.remove('checkout-screen-active');
    document.body.classList.add('cart-screen-active');
    window.history.replaceState({}, document.title, '#cart');
    window.scrollTo({ top: 0, behavior: 'auto' });
    setCheckoutStep('cart');
}

function setCheckoutStep(step) {
    checkoutStep = step;
    checkoutProgressSteps.forEach(progressStep => {
        const stepName = progressStep.dataset.checkoutProgress;
        const stepIndex = ['cart', 'delivery', 'summary'].indexOf(stepName);
        const currentIndex = ['cart', 'delivery', 'summary'].indexOf(step);
        progressStep.classList.toggle('is-active', stepName === step);
        progressStep.classList.toggle('is-complete', stepIndex < currentIndex);
    });
    if (checkoutForm) checkoutForm.hidden = step === 'summary';
    if (checkoutSummaryStep) checkoutSummaryStep.hidden = step !== 'summary';
    if (checkoutToSummary) checkoutToSummary.hidden = step !== 'delivery';
    if (checkoutSubmit) checkoutSubmit.hidden = step !== 'summary';
}

async function prepareCheckoutSummary() {
    syncBillingUi();
    if (!checkoutForm.reportValidity()) return;
    try {
        await ensureCartStockAvailable();
        const quote = await loadDeliveryQuote();
        if (!quote || !deliveryDate?.value) {
            throw new Error('Wybierz dostępny dzień dostawy lub odbioru');
        }
        renderCheckoutSummary();
        setCheckoutStep('summary');
        window.history.replaceState({}, document.title, '#checkout-summary');
        window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (error) {
        setCheckoutStatus(error.message, true);
    }
}

function renderCheckoutSummary() {
    if (!checkoutSummaryItems) return;
    checkoutSummaryItems.innerHTML = state.cart.map(item => `
        <div class="checkout-summary-item">
            <span>${escapeHtml(item.name)} <small>× ${item.quantity}</small></span>
            <strong>${formatMoney(Number(item.price) * item.quantity)}</strong>
        </div>`).join('');
    const address = readDeliveryAddress();
    const addressText = state.deliveryQuote?.fulfillmentType === 'STORE_PICKUP'
        ? (pickupLocation?.value || 'MiniTech Store — Warszawa')
        : [address.firstName, address.lastName, address.street, address.postalCode, address.city, address.country]
            .filter(Boolean).join(', ');
    const recipientEmail = document.querySelector('#recipient-email')?.value.trim()
        || deliveryEmailInput?.value.trim() || '—';
    checkoutSummaryEmail.textContent = recipientEmail;
    checkoutSummaryAddress.textContent = addressText || '—';
    checkoutSummaryDelivery.textContent = `${state.deliveryQuote?.name || '—'} · ${formatDeliveryDate(deliveryDate?.value || state.deliveryQuote?.selectedDate)}`;
    checkoutSummaryPayment.textContent = document.querySelector('#payment-method')?.selectedOptions[0]?.textContent || '—';
    renderPriceSummary();
}

function backToCart() {
    showCartPage();
}

function returnToStoreHome(orderNumbers, recipient, deferredPayment = false) {
    checkoutSection.hidden = true;
    checkoutLayout.classList.add('cart-only');
    checkoutLayout.classList.remove('checkout-only');
    document.body.classList.remove('checkout-screen-active', 'cart-screen-active');
    setCheckoutStep('cart');
    successModal.hidden = true;
    window.history.replaceState({}, document.title, '#top');
    window.scrollTo({ top: 0, behavior: 'smooth' });
    const paymentLabel = deferredPayment ? 'zostało przyjęte; płatność nastąpi przy realizacji' : 'zostało opłacone';
    setCatalogMessage(`Zakup zakończony pomyślnie. Zamówienie ${orderNumbers} ${paymentLabel}, a potwierdzenie wysłano na ${recipient}.`);
    showToast(`Zamówienie ${orderNumbers} przyjęte.`, 6000);
}

function clearCustomerCheckoutFields() {
    document.querySelector('#delivery-first-name').value = '';
    document.querySelector('#delivery-last-name').value = '';
    document.querySelector('#delivery-street').value = '';
    document.querySelector('#delivery-postal-code').value = '';
    document.querySelector('#delivery-city').value = '';
    document.querySelector('#delivery-country').value = 'Polska';
    document.querySelector('#recipient-email').value = '';
    if (deliveryEmailInput) deliveryEmailInput.value = '';
    if (deliveryPhoneInput) deliveryPhoneInput.value = '';
}

function openAuthModal() {
    if (state.customer) {
        accountMenu.hidden = !accountMenu.hidden;
        authToggle.setAttribute('aria-expanded', String(!accountMenu.hidden));
        return;
    }
    closeAccountMenu();
    authModal.hidden = false;
    passwordResetMode = false;
    authMfaPanel.hidden = true;
    authSecurityPanel.hidden = true;
    authProfilePanel.hidden = true;
    authOrdersPanel.hidden = true;
    authWishlistPanel.hidden = true;
    updateAuthMode();
    if (state.customer) {
        authEmail.value = state.customer.email || '';
        authSubmit.hidden = true;
        authLogout.hidden = false;
        setAccountStatus(`Zalogowano jako ${state.customer.email}.`);
    } else {
        authSubmit.hidden = false;
        authLogout.hidden = true;
        showResendVerification(authEmail.value);
        setAccountStatus('Zaloguj się albo załóż konto, aby zapisać dane dostawy.');
    }
}

function closeAccountMenu() {
    accountMenu.hidden = true;
    authToggle.setAttribute('aria-expanded', 'false');
}

function closeAuthModal() {
    authModal.hidden = true;
    if (!state.customer) {
        state.mfaChallenge = null;
        authMfaPanel.hidden = true;
    }
}

function updateAuthMode() {
    authModeSelector.hidden = passwordResetMode;
    authForm.hidden = passwordResetMode;
    authResetForm.hidden = !passwordResetMode;
    authForgotPassword.hidden = passwordResetMode || authMode.value !== 'login' || Boolean(state.customer);
    if (passwordResetMode) {
        authResetEmail.required = !authResetToken.value.trim();
        document.querySelector('#auth-title').textContent = authResetToken.value.trim()
            ? 'Ustaw nowe haslo'
            : 'Nie pamietam hasla';
        authResetTokenLabel.hidden = !authResetToken.value.trim();
        authResetPasswordLabel.hidden = !authResetToken.value.trim();
        authResetPassword.required = Boolean(authResetToken.value.trim());
        authResetSubmit.textContent = authResetToken.value.trim() ? 'Zmien haslo' : 'Wyslij link resetu';
        return;
    }
    authResetEmail.required = true;
    document.querySelector('#auth-title').textContent = state.customer ? 'Konto' : 'Zaloguj sie';
    const registering = authMode.value === 'register';
    authPasswordConfirmLabel.hidden = !registering;
    authPasswordConfirm.required = registering;
    authRegisterFields.hidden = !registering;
    authLoginChoice?.classList.toggle('active', !registering);
    authRegisterChoice?.classList.toggle('active', registering);
    authLoginChoice?.setAttribute('aria-pressed', String(!registering));
    authRegisterChoice?.setAttribute('aria-pressed', String(registering));
    authSubmit.textContent = registering ? 'Załóż konto' : 'Zaloguj się';
    authResend.hidden = !registering || Boolean(state.customer);
    [...authRegisterFields.querySelectorAll('input')].forEach(input => {
        input.required = registering;
    });
    if (!registering) {
        authPasswordConfirm.setCustomValidity('');
    } else {
        validatePasswordMatch();
    }
}

function selectAuthMode(mode) {
    passwordResetMode = false;
    authMode.value = mode;
    updateAuthMode();
}

function selectPasswordResetMode(token = '') {
    passwordResetMode = true;
    authResetToken.value = token;
    authResetEmail.value = authEmail.value.trim();
    authResetPassword.value = '';
    authModal.hidden = false;
    closeAccountMenu();
    updateAuthMode();
    setAccountStatus(token ? 'Wpisz nowe haslo, aby zakonczyc reset.' : 'Podaj e-mail. Link resetu otrzymasz w Mailpit.');
}

async function submitAuth(event) {
    event.preventDefault();
    let registering = false;
    try {
        registering = authMode.value === 'register';
        if (registering && !validatePasswordMatch()) {
            setAccountStatus('Hasła muszą być identyczne.', true);
            authPasswordConfirm.reportValidity();
            return;
        }
        setAccountStatus(registering ? 'Tworzenie konta…' : 'Logowanie…');
        const payload = { email: authEmail.value.trim(), password: authPassword.value };
        if (registering) {
            Object.assign(payload, {
                firstName: document.querySelector('#auth-first-name').value.trim(),
                lastName: document.querySelector('#auth-last-name').value.trim(),
                street: document.querySelector('#auth-street').value.trim(),
                postalCode: document.querySelector('#auth-postal-code').value.trim(),
                city: document.querySelector('#auth-city').value.trim(),
                country: document.querySelector('#auth-country').value.trim()
            });
        }
        const authResult = await api(registering ? '/customers/register' : '/customers/login', {
            method: 'POST',
            body: JSON.stringify(payload)
        });
        if (registering) {
            authEmail.value = authResult.email || payload.email;
            authPassword.value = '';
            authPasswordConfirm.value = '';
            selectAuthMode('login');
            showResendVerification(authEmail.value);
            setAccountStatus(`Confirmation email sent to ${authEmail.value}. The link is valid for 5 minutes.`);
            showToast('Check Mailpit and confirm your account');
            return;
        }
        if (authResult?.mfaRequired) {
            showMfaChallenge(authResult);
            return;
        }
        setSession(authResult);
        state.mfaChallenge = null;
        const customer = authResult.customer;
        const nextPurchaseMode = pendingPurchaseMode;
        pendingPurchaseMode = null;
        if (nextPurchaseMode === 'account') {
            purchaseMode = 'account';
        }
        setAccountStatus(registering ? `Konto utworzone dla ${customer.email}.` : `Zalogowano jako ${customer.email}.`);
        showToast(registering ? 'Konto zostało utworzone' : 'Zalogowano do konta');
        window.setTimeout(() => {
            closeAuthModal();
            if (nextPurchaseMode === 'account') showCheckoutPage();
        }, 500);
    } catch (error) {
        if (!state.customer) {
            showResendVerification(authEmail.value);
        }
        const message = !registering && error.status === 401
            ? 'Nieprawidłowy e-mail lub hasło.'
            : error.message;
        setAccountStatus(`Operacja nie powiodła się: ${message}`, true);
    }
}

function showMfaChallenge(challenge) {
    state.mfaChallenge = challenge;
    authForm.hidden = true;
    authModeSelector.hidden = true;
    authForgotPassword.hidden = true;
    authResetForm.hidden = true;
    authSubmit.hidden = true;
    authLogout.hidden = true;
    authMfaPanel.hidden = false;
    authSecurityPanel.hidden = true;
    authProfilePanel.hidden = true;
    authOrdersPanel.hidden = true;
    authMfaCode.value = '';
    const passkey = challenge.method === 'webauthn';
    authMfaCodeLabel.hidden = passkey;
    authMfaSubmit.hidden = passkey;
    authMfaPasskey.hidden = !passkey;
    authMfaHelp.textContent = passkey
        ? 'Potwierdź logowanie za pomocą zarejestrowanego passkey.'
        : 'Wpisz kod z aplikacji uwierzytelniającej albo użyj kodu odzyskiwania.';
    authMfaStatus.textContent = `Weryfikacja ważna ${challenge.expiresIn || 300} sekund.`;
    authModal.hidden = false;
    if (passkey) {
        startWebAuthnLogin();
    }
}

async function submitMfaLogin() {
    if (!state.mfaChallenge) return;
    const code = authMfaCode.value.trim();
    if (!code) {
        authMfaCode.reportValidity();
        return;
    }
    authMfaSubmit.disabled = true;
    authMfaStatus.textContent = 'Sprawdzanie kodu...';
    try {
        const session = await api('/customers/mfa/login/verify', {
            method: 'POST',
            body: JSON.stringify({ challengeId: state.mfaChallenge.challengeId, code })
        }, false);
        finishLogin(session);
    } catch (error) {
        authMfaStatus.textContent = `Nie udało się potwierdzić kodu: ${error.message}`;
        authMfaStatus.style.color = '#bd331d';
    } finally {
        authMfaSubmit.disabled = false;
    }
}

async function startWebAuthnLogin() {
    if (!state.mfaChallenge) return;
    authMfaPasskey.disabled = true;
    authMfaStatus.textContent = 'Oczekiwanie na potwierdzenie passkey...';
    try {
        if (!window.PublicKeyCredential || !navigator.credentials) {
            throw new Error('Ta przeglądarka nie obsługuje WebAuthn.');
        }
        const options = await api(`/customers/mfa/webauthn/login/options?challengeId=${encodeURIComponent(state.mfaChallenge.challengeId)}`, {}, false);
        ensureWebAuthnHost(options.rpId);
        const credential = await navigator.credentials.get({ publicKey: {
            challenge: decodeBase64Url(options.challenge),
            timeout: options.timeout,
            rpId: options.rpId,
            allowCredentials: (options.allowCredentials || []).map(id => ({
                type: 'public-key',
                id: decodeBase64Url(id)
            })),
            userVerification: options.userVerification || 'required'
        }});
        const session = await api('/customers/mfa/webauthn/login/verify', {
            method: 'POST',
            body: JSON.stringify(serializeAssertion(state.mfaChallenge.challengeId, credential))
        }, false);
        finishLogin(session);
    } catch (error) {
        authMfaStatus.textContent = `Nie udało się użyć passkey: ${error.message}`;
        authMfaStatus.style.color = '#bd331d';
    } finally {
        authMfaPasskey.disabled = false;
    }
}

function finishLogin(session) {
    setSession(session);
    state.mfaChallenge = null;
    authMfaPanel.hidden = true;
    authMfaStatus.textContent = '';
    const customer = session.customer;
    const nextPurchaseMode = pendingPurchaseMode;
    pendingPurchaseMode = null;
    if (nextPurchaseMode === 'account') purchaseMode = 'account';
    setAccountStatus(`Zalogowano jako ${customer.email}.`);
    showToast('Zalogowano do konta');
    window.setTimeout(() => {
        closeAuthModal();
        if (nextPurchaseMode === 'account') showCheckoutPage();
    }, 500);
}

async function openSecurityPanel() {
    closeAccountMenu();
    authModal.hidden = false;
    passwordResetMode = false;
    authForm.hidden = true;
    authModeSelector.hidden = true;
    authForgotPassword.hidden = true;
    authResetForm.hidden = true;
    authMfaPanel.hidden = true;
    authSecurityPanel.hidden = false;
    authProfilePanel.hidden = true;
    authOrdersPanel.hidden = true;
    authWishlistPanel.hidden = true;
    authSubmit.hidden = true;
    authLogout.hidden = true;
    document.querySelector('#auth-title').textContent = 'Bezpieczeństwo konta';
    await loadMfaStatus();
}

function openProfilePanel() {
    if (!state.customer) {
        openAuthModal();
        return;
    }
    closeAccountMenu();
    authModal.hidden = false;
    passwordResetMode = false;
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
    document.querySelector('#auth-title').textContent = 'Profil';
    fillProfileForm(state.customer);
    authProfileStatus.textContent = '';
}

async function openOrderHistoryPanel() {
    if (!state.customer) {
        openAuthModal();
        return;
    }
    closeAccountMenu();
    authModal.hidden = false;
    passwordResetMode = false;
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
    document.querySelector('#auth-title').textContent = 'Moje zamówienia';
    await loadOrderHistory();
}

async function openWishlistPanel() {
    if (!state.customer) {
        openAuthModal();
        return;
    }
    closeAccountMenu();
    authModal.hidden = false;
    passwordResetMode = false;
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
    document.querySelector('#auth-title').textContent = 'Moje ulubione';
    await loadWishlist();
}

async function loadWishlist() {
    if (!state.customer) {
        state.wishlist = new Set();
        renderWishlistPanel();
        return;
    }
    authWishlistStatus.textContent = 'Ładowanie zapisanych produktów…';
    authWishlistStatus.style.color = '';
    try {
        const rows = await api(`/wishlists/customer/${encodeURIComponent(state.customer.id)}`);
        state.wishlist = new Set((Array.isArray(rows) ? rows : []).map(item => Number(item.productId)));
        renderWishlistPanel();
        renderProducts();
        renderHighlights();
    } catch (error) {
        authWishlistStatus.textContent = `Nie udało się pobrać ulubionych: ${error.message}`;
        authWishlistStatus.style.color = '#bd331d';
    }
}

function renderWishlistPanel() {
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

function handleWishlistPanelAction(event) {
    const button = event.target.closest('[data-action="wishlist-remove"]');
    if (!button) return;
    toggleWishlist(button.dataset.productId, button).catch(() => {});
}

async function loadOrderHistory() {
    authOrdersStatus.textContent = 'Ładowanie historii zamówień…';
    authOrdersStatus.style.color = '';
    authOrdersList.innerHTML = '';
    try {
        const orders = await api(`/orders/customer/${encodeURIComponent(state.customer.id)}`);
        if (!Array.isArray(orders) || !orders.length) {
            authOrdersStatus.textContent = 'Nie masz jeszcze zamówień zapisanych na koncie.';
            return;
        }
        authOrdersStatus.textContent = `Łącznie: ${orders.length} zamówień. Najnowsze są na górze; wybierz szczegóły, aby zobaczyć fakturę i pozycje.`;
        authOrdersList.innerHTML = orders.map(orderHistoryCard).join('');
    } catch (error) {
        authOrdersStatus.textContent = `Nie udało się pobrać historii: ${error.message}`;
        authOrdersStatus.style.color = '#bd331d';
    }
}

function orderHistoryCard(order) {
    const items = Array.isArray(order.items) ? order.items : [];
    const itemSummary = items.map(item => `${item.productName} × ${item.quantity}`).join(', ');
    const deliverySummary = order.deliveryMethod
        ? `Dostawa: ${order.deliveryMethod} · ${formatMoney(order.deliveryCost)}`
        : '';
    return `
        <article class="auth-order-card">
            <div class="auth-order-heading">
                <strong>Zamówienie #${escapeHtml(order.id)}</strong>
                <span class="auth-order-status">${escapeHtml(orderStatusLabel(order.status))}</span>
            </div>
            <p>${escapeHtml(formatOrderDate(order.createdAt))}</p>
            <p class="auth-order-items">${escapeHtml(itemSummary || 'Brak pozycji')}</p>
            ${deliverySummary ? `<p>${escapeHtml(deliverySummary)}</p>` : ''}
            <p>Faktura: ${order.invoiceRequested
                ? `<a href="${API_BASE}/invoices/order/${encodeURIComponent(order.id)}/pdf" target="_blank" rel="noreferrer">pobierz PDF</a>`
                : 'niezamówiona'}</p>
            <strong class="auth-order-total">${formatMoney(order.totalPrice)}</strong>
            <div class="auth-order-actions">
                <a class="secondary-button" href="order-detail.html?id=${encodeURIComponent(order.id)}">Szczegóły zamówienia</a>
            </div>
        </article>`;
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

function formatOrderDate(value) {
    if (!value) return 'Data niedostępna';
    const date = new Date(value);
    return Number.isNaN(date.getTime())
        ? String(value)
        : date.toLocaleString('pl-PL', { dateStyle: 'medium', timeStyle: 'short' });
}

function fillProfileForm(customer) {
    authProfileEmail.value = customer?.email || '';
    authProfileFirstName.value = customer?.firstName || '';
    authProfileLastName.value = customer?.lastName || '';
    authProfilePhone.value = customer?.phoneNumber || '';
    authProfileStreet.value = customer?.street || '';
    authProfilePostalCode.value = customer?.postalCode || '';
    authProfileCity.value = customer?.city || '';
    authProfileCountry.value = customer?.country || 'Polska';
}

async function saveProfile(event) {
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

async function loadMfaStatus() {
    authMfaSummary.textContent = 'Sprawdzanie konfiguracji MFA...';
    try {
        const status = await api('/customers/mfa/status');
        authMfaSummary.textContent = `TOTP: ${status.totpEnabled ? 'włączone' : 'wyłączone'} · passkey: ${status.webAuthnAvailable ? 'zarejestrowany' : 'brak'}.`;
        authTotpStart.hidden = status.totpEnabled;
        authTotpDisable.hidden = !status.totpEnabled;
        if (!status.totpEnabled) {
            authTotpDisablePanel.hidden = true;
            authTotpDisableCode.value = '';
        }
        authPasskeyRegister.disabled = !window.PublicKeyCredential;
    } catch (error) {
        authMfaSummary.textContent = `Nie udało się pobrać statusu MFA: ${error.message}`;
    }
}

async function beginTotpEnrollment() {
    authTotpStart.disabled = true;
    authSecurityStatus.textContent = 'Generowanie klucza TOTP...';
    try {
        mfaEnrollment = await api('/customers/mfa/totp/enroll', { method: 'POST' });
        authTotpQr.src = mfaEnrollment.qrCodeDataUri;
        authTotpQr.alt = 'Kod QR do skonfigurowania aplikacji TOTP';
        authTotpSetup.hidden = false;
        authTotpSecret.value = mfaEnrollment.secret;
        authTotpSecretLabel.hidden = false;
        authTotpCodeLabel.hidden = false;
        authTotpConfirm.hidden = false;
        authSecurityStatus.textContent = 'Dodaj klucz do aplikacji Authenticator i wpisz aktualny kod.';
    } catch (error) {
        authSecurityStatus.textContent = `Nie udało się rozpocząć TOTP: ${error.message}`;
        authSecurityStatus.style.color = '#bd331d';
        authTotpStart.disabled = false;
    }
}

async function confirmTotpEnrollment() {
    const code = authTotpCode.value.trim();
    if (!/^\d{6}$/.test(code)) {
        authSecurityStatus.textContent = 'Kod TOTP musi zawierać dokładnie 6 cyfr.';
        authSecurityStatus.style.color = '#bd331d';
        authTotpCode.focus();
        return;
    }
    authTotpConfirm.disabled = true;
    try {
        const recovery = await api('/customers/mfa/totp/confirm', {
            method: 'POST',
            body: JSON.stringify({ code })
        });
        authSecurityStatus.textContent = `TOTP włączone. Kody odzyskiwania: ${recovery.codes.join(', ')}. Zapisz je poza aplikacją.`;
        authTotpSetup.hidden = true;
        authTotpSecretLabel.hidden = true;
        authTotpCodeLabel.hidden = true;
        authTotpConfirm.hidden = true;
        authTotpStart.hidden = true;
        await loadMfaStatus();
    } catch (error) {
        authSecurityStatus.textContent = error.status === 401
            ? 'Nieprawidłowy lub wygasły kod TOTP. Wpisz aktualny kod z aplikacji Authenticator.'
            : `Nie udało się potwierdzić TOTP: ${error.message}`;
        authSecurityStatus.style.color = '#bd331d';
    } finally {
        authTotpConfirm.disabled = false;
    }
}

async function disableTotp() {
    const code = authTotpDisableCode.value.trim();
    if (!/^\d{6}$/.test(code)) {
        authSecurityStatus.textContent = 'Kod TOTP musi zawierać dokładnie 6 cyfr.';
        authSecurityStatus.style.color = '#bd331d';
        authTotpDisableCode.focus();
        return;
    }
    authTotpDisableConfirm.disabled = true;
    authSecurityStatus.textContent = 'Wyłączanie TOTP...';
    try {
        await api('/customers/mfa/totp/disable', {
            method: 'POST',
            body: JSON.stringify({ code })
        });
        authTotpDisableCode.value = '';
        authTotpDisablePanel.hidden = true;
        authSecurityStatus.textContent = 'TOTP zostało wyłączone.';
        authSecurityStatus.style.color = '#087d6e';
        await loadMfaStatus();
    } catch (error) {
        authSecurityStatus.textContent = error.status === 401
            ? 'Nieprawidłowy lub wygasły kod TOTP. Wpisz aktualny kod z aplikacji Authenticator.'
            : `Nie udało się wyłączyć TOTP: ${error.message}`;
        authSecurityStatus.style.color = '#bd331d';
    } finally {
        authTotpDisableConfirm.disabled = false;
    }
}

async function registerPasskey() {
    authPasskeyRegister.disabled = true;
    authSecurityStatus.textContent = 'Oczekiwanie na rejestrację passkey...';
    try {
        if (!window.PublicKeyCredential || !navigator.credentials) {
            throw new Error('Ta przeglądarka nie obsługuje WebAuthn.');
        }
        const options = await api('/customers/mfa/webauthn/register/options');
        ensureWebAuthnHost(options.rpId);
        const credential = await navigator.credentials.create({ publicKey: {
            challenge: decodeBase64Url(options.challenge),
            rp: { id: options.rpId, name: options.rpName },
            user: {
                id: decodeBase64Url(options.user.id),
                name: options.user.name,
                displayName: options.user.displayName
            },
            pubKeyCredParams: options.pubKeyCredParams.map(alg => ({
                type: 'public-key',
                alg: ({ ES256: -7, RS256: -257 }[alg] ?? Number(alg))
            })),
            timeout: options.timeout,
            excludeCredentials: (options.excludeCredentials || []).map(id => ({ type: 'public-key', id: decodeBase64Url(id) })),
            authenticatorSelection: { userVerification: options.userVerification || 'preferred' },
            attestation: 'none'
        }});
        await api('/customers/mfa/webauthn/register?label=Browser%20passkey', {
            method: 'POST',
            body: JSON.stringify(serializeAttestation(credential))
        });
        authSecurityStatus.textContent = 'Passkey został dodany do konta.';
        await loadMfaStatus();
    } catch (error) {
        authSecurityStatus.textContent = `Nie udało się dodać passkey: ${error.message}`;
        authSecurityStatus.style.color = '#bd331d';
    } finally {
        authPasskeyRegister.disabled = false;
    }
}

function ensureWebAuthnHost(rpId) {
    if (window.location.port !== '18070' || !rpId) return;
    const currentHost = (window.location.hostname || '').toLowerCase();
    const expectedHost = rpId.toLowerCase();
    if (currentHost !== expectedHost) {
        throw new Error(`Otwórz sklep przez http://${expectedHost}:18070; obecny adres używa innej domeny.`);
    }
}

function decodeBase64Url(value) {
    const normalized = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4);
    const bytes = Uint8Array.from(atob(normalized), character => character.charCodeAt(0));
    return bytes.buffer;
}

function encodeBase64Url(value) {
    const bytes = value instanceof ArrayBuffer ? new Uint8Array(value) : new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function serializeAssertion(challengeId, credential) {
    return {
        challengeId,
        id: credential.id,
        type: credential.type,
        rawId: encodeBase64Url(credential.rawId),
        response: {
            clientDataJSON: encodeBase64Url(credential.response.clientDataJSON),
            authenticatorData: encodeBase64Url(credential.response.authenticatorData),
            signature: encodeBase64Url(credential.response.signature),
            userHandle: credential.response.userHandle ? encodeBase64Url(credential.response.userHandle) : null
        }
    };
}

function serializeAttestation(credential) {
    return {
        id: credential.id,
        type: credential.type,
        rawId: encodeBase64Url(credential.rawId),
        response: {
            clientDataJSON: encodeBase64Url(credential.response.clientDataJSON),
            attestationObject: encodeBase64Url(credential.response.attestationObject)
        }
    };
}

async function submitPasswordReset(event) {
    event.preventDefault();
    const token = authResetToken.value.trim();
    try {
        if (!token) {
            if (!authResetEmail.checkValidity()) {
                authResetEmail.reportValidity();
                return;
            }
            await api('/customers/password-reset/request', {
                method: 'POST',
                body: JSON.stringify({ email: authResetEmail.value.trim() })
            }, false);
            setAccountStatus('Jezeli konto istnieje, link resetu zostal wyslany. Sprawdz Mailpit.');
            showToast('Sprawdz Mailpit - wyslano link resetu');
            return;
        }
        if (!authResetPassword.checkValidity()) {
            authResetPassword.reportValidity();
            return;
        }
        await api('/customers/password-reset/confirm', {
            method: 'POST',
            body: JSON.stringify({ token, password: authResetPassword.value })
        }, false);
        const url = new URL(window.location.href);
        url.searchParams.delete('reset');
        window.history.replaceState({}, document.title, `${url.pathname}${url.search}${url.hash}`);
        passwordResetMode = false;
        authResetToken.value = '';
        authResetPassword.value = '';
        selectAuthMode('login');
        setAccountStatus('Haslo zostalo zmienione. Mozesz sie zalogowac.');
        showToast('Haslo zostalo zmienione');
    } catch (error) {
        setAccountStatus(`Reset hasla nie powiodl sie: ${error.message}`, true);
    }
}

function showResendVerification(email = authEmail.value) {
    authResend.hidden = Boolean(state.customer) || authMode.value !== 'register';
    if (email?.trim()) {
        authEmail.value = email.trim();
    }
    authResendStatus.textContent = '';
}

async function resendVerification() {
    const email = authEmail.value.trim();
    if (!email || !authEmail.checkValidity()) {
        authEmail.reportValidity();
        return;
    }

    authResendButton.disabled = true;
    authResendStatus.textContent = 'Sprawdzam zgłoszenie…';
    try {
        await api('/customers/verification/resend', {
            method: 'POST',
            body: JSON.stringify({ email })
        }, false);
        authResendStatus.textContent = 'Jeżeli konto wymaga potwierdzenia, nowy link został wysłany. Sprawdź Mailpit.';
        showToast('Sprawdź Mailpit — link aktywacyjny został ponowiony');
        let secondsLeft = 60;
        const countdown = () => {
            secondsLeft -= 1;
            if (secondsLeft <= 0) {
                authResendButton.disabled = false;
                authResendButton.textContent = 'Wyślij ponownie link';
                return;
            }
            authResendButton.textContent = `Wyślij ponownie link (${secondsLeft}s)`;
            window.setTimeout(countdown, 1000);
        };
        authResendButton.textContent = 'Wyślij ponownie link (60s)';
        window.setTimeout(countdown, 1000);
    } catch (error) {
        authResendStatus.textContent = `Nie udało się wysłać linku: ${error.message}`;
        authResendButton.disabled = false;
    }
}

function validatePasswordMatch() {
    if (authMode.value !== 'register') {
        authPasswordConfirm.setCustomValidity('');
        return true;
    }
    const matches = authPassword.value === authPasswordConfirm.value;
    authPasswordConfirm.setCustomValidity(matches ? '' : 'Hasła muszą być identyczne.');
    return matches;
}

async function loadVerification() {
    const url = new URL(window.location.href);
    const token = url.searchParams.get('verify');
    if (!token) return;

    try {
        setAccountStatus('Confirming account...');
        const customer = await api(`/customers/verify?token=${encodeURIComponent(token)}`);
        url.searchParams.delete('verify');
        window.history.replaceState({}, document.title, `${url.pathname}${url.search}${url.hash}`);
        authModal.hidden = false;
        authEmail.value = customer.email || '';
        authPassword.value = '';
        authSubmit.hidden = false;
        authLogout.hidden = true;
        showResendVerification(authEmail.value);
        selectAuthMode('login');
        setAccountStatus(`Account ${customer.email} confirmed. You can now log in.`);
        showToast('Account confirmed');
    } catch (error) {
        url.searchParams.delete('verify');
        window.history.replaceState({}, document.title, `${url.pathname}${url.search}${url.hash}`);
        authModal.hidden = false;
        authSubmit.hidden = false;
        authLogout.hidden = true;
        selectAuthMode('register');
        showResendVerification(authEmail.value);
        setAccountStatus(`Account confirmation failed: ${error.message}`, true);
    }
}

function loadPasswordReset() {
    const token = new URL(window.location.href).searchParams.get('reset');
    if (token) {
        selectPasswordResetMode(token);
    }
}

async function logoutCustomer(reason = 'manual') {
    try {
        await api('/customers/logout', { method: 'POST' }, false);
    } catch {
        // Clear the browser session even if the service is temporarily unavailable.
    }
    sessionGuard?.clear();
    clearCustomerSession();
    const timedOut = reason === 'timeout';
    setAccountStatus(timedOut
        ? 'Sesja wygasła po 30 minutach bezczynności. Zaloguj się ponownie.'
        : 'Wylogowano. Możesz nadal kupować jako gość.');
    showToast(timedOut ? 'Sesja wygasła — zaloguj się ponownie' : 'Wylogowano');
}

async function expireBrowserSession(showMessage) {
    try {
        await fetch(`${API_BASE}/customers/logout`, {
            method: 'POST',
            credentials: 'include',
            cache: 'no-store'
        });
    } catch {
        // The local timeout remains authoritative when Customer Service is offline.
    }
    sessionGuard?.clear();
    clearCustomerSession();
    if (showMessage) {
        setAccountStatus('Sesja wygasła po 30 minutach bezczynności. Zaloguj się ponownie.', true);
        showToast('Sesja wygasła — zaloguj się ponownie');
    }
}

function clearCustomerSession() {
    const resetGuestCart = authenticatedSessionExpected || Boolean(state.customer || state.accessToken);
    saveCart();
    state.accessToken = null;
    state.customer = null;
    state.mfaChallenge = null;
    if (resetGuestCart) clearGuestCartStorage();
    switchCartScope(null);
    authenticatedSessionExpected = false;
    renderCart();
    closeAccountMenu();
    authSecurityPanel.hidden = true;
    authMfaPanel.hidden = true;
    authProfilePanel.hidden = true;
    authOrdersPanel.hidden = true;
    authWishlistPanel.hidden = true;
    updateCustomerSummary();
    authSubmit.hidden = false;
    authLogout.hidden = true;
}

function readDeliveryAddress() {
    const pickup = deliveryMethod?.value === 'STORE_PICKUP';
    return {
        firstName: document.querySelector('#delivery-first-name').value.trim(),
        lastName: document.querySelector('#delivery-last-name').value.trim(),
        street: pickup ? 'MiniTech Store' : document.querySelector('#delivery-street').value.trim(),
        postalCode: pickup ? '00-001' : document.querySelector('#delivery-postal-code').value.trim(),
        city: pickup ? 'Warszawa' : document.querySelector('#delivery-city').value.trim(),
        country: pickup ? 'Polska' : document.querySelector('#delivery-country').value.trim(),
        phone: deliveryPhoneInput?.value.trim() || null
    };
}

function readBillingData(deliveryAddress) {
    const company = Boolean(billingCompany?.checked);
    return {
        company,
        buyerName: billingBuyerName?.value.trim() || `${deliveryAddress.firstName} ${deliveryAddress.lastName}`.trim(),
        companyName: company ? (billingCompanyName?.value.trim() || null) : null,
        taxId: company ? (billingTaxId?.value.trim() || null) : null,
        street: document.querySelector('#billing-street')?.value.trim() || deliveryAddress.street,
        postalCode: document.querySelector('#billing-postal-code')?.value.trim() || deliveryAddress.postalCode,
        city: document.querySelector('#billing-city')?.value.trim() || deliveryAddress.city,
        country: document.querySelector('#billing-country')?.value.trim() || deliveryAddress.country
    };
}

function syncBillingUi() {
    if (!billingDataPanel) return;
    const invoice = Boolean(invoiceRequested?.checked);
    billingDataPanel.hidden = !invoice;
    const company = Boolean(billingCompany?.checked);
    const companyNameLabel = document.querySelector('#billing-company-name-label');
    const taxIdLabel = document.querySelector('#billing-tax-id-label');
    if (companyNameLabel) companyNameLabel.hidden = !invoice || !company;
    if (taxIdLabel) taxIdLabel.hidden = !invoice || !company;

    if (invoice && billingBuyerName && !billingBuyerName.value.trim()) {
        const firstName = document.querySelector('#delivery-first-name')?.value.trim() || '';
        const lastName = document.querySelector('#delivery-last-name')?.value.trim() || '';
        billingBuyerName.value = `${firstName} ${lastName}`.trim();
    }

    const requiredForInvoice = [
        billingBuyerName,
        document.querySelector('#billing-street'),
        document.querySelector('#billing-postal-code'),
        document.querySelector('#billing-city'),
        document.querySelector('#billing-country')
    ];
    requiredForInvoice.forEach(input => {
        if (input) input.required = invoice;
    });
    if (billingCompanyName) billingCompanyName.required = invoice && company;
    if (billingTaxId) billingTaxId.required = invoice && company;
    validateBillingTaxId();
}

function validateBillingTaxId() {
    if (!billingTaxId) return true;
    const companyInvoice = Boolean(invoiceRequested?.checked && billingCompany?.checked);
    const valid = !companyInvoice || /^[0-9]{10}$/.test(billingTaxId.value.trim());
    billingTaxId.setCustomValidity(valid ? '' : 'NIP musi składać się dokładnie z 10 cyfr.');
    return valid;
}

function fillCustomer(customer) {
    authEmail.value = customer.email || '';
    document.querySelector('#recipient-email').value = customer.email || '';
    document.querySelector('#delivery-first-name').value = customer.firstName || '';
    document.querySelector('#delivery-last-name').value = customer.lastName || '';
    document.querySelector('#delivery-street').value = customer.street || '';
    document.querySelector('#delivery-postal-code').value = customer.postalCode || '';
    document.querySelector('#delivery-city').value = customer.city || '';
    document.querySelector('#delivery-country').value = customer.country || 'Polska';
    if (deliveryPhoneInput) deliveryPhoneInput.value = customer.phoneNumber || '';
    if (deliveryEmailInput) deliveryEmailInput.value = customer.email || '';
    syncGuestContactFields();
}

function setSession(session) {
    state.accessToken = session.accessToken;
    state.customer = session.customer;
    switchCartScope(state.customer);
    clearGuestCartStorage();
    authenticatedSessionExpected = true;
    state.wishlist = new Set();
    fillCustomer(session.customer);
    updateCustomerSummary();
    sessionGuard?.start(() => expireBrowserSession(true));
    sessionGuard?.touch();
    loadWishlist().catch(() => {});
}

function updateCustomerSummary() {
    if (state.customer) {
        authToggle.textContent = `Konto: ${state.customer.email}`;
    } else {
        authToggle.textContent = 'Zaloguj się';
        closeAccountMenu();
    }
    updateCartThresholdNote();
}

function setAccountStatus(message, isError = false) {
    accountStatus.textContent = message;
    accountStatus.style.color = isError ? '#bd331d' : '';
}

function openSuccessModal(results, recipient) {
    successMessage.textContent = `Zamówienia ${results.map(result => `#${result.order.id}`).join(', ')} zostały opłacone. Potwierdzenie wysłano na ${recipient}.`;
    successModal.hidden = false;
}

function closeSuccessModal() {
    successModal.hidden = true;
}

function setCatalogMessage(message, isError = false) {
    if (!catalogMessage) return;
    catalogMessage.textContent = message;
    catalogMessage.style.color = isError ? '#bd331d' : '';
}

function submitGlobalSearch() {
    const query = globalSearchInput?.value.trim() || '';
    const url = new URL('catalog.html', window.location.href);
    if (query) url.searchParams.set('search', query);
    window.location.href = url.href;
}

function selectedCompareProducts() {
    return [...state.compareIds]
        .map(id => state.products.find(product => Number(product.id) === Number(id)))
        .filter(Boolean);
}

function toggleCompare(productId) {
    const id = Number(productId);
    const product = state.products.find(item => Number(item.id) === id);
    if (!product) return;

    if (state.compareIds.has(id)) {
        state.compareIds.delete(id);
        showToast('Usunięto produkt z porównania');
    } else if (state.compareIds.size >= MAX_COMPARE_PRODUCTS) {
        showToast(`Możesz porównać maksymalnie ${MAX_COMPARE_PRODUCTS} produkty.`);
        return;
    } else {
        state.compareIds.add(id);
        showToast('Dodano produkt do porównania');
    }

    saveCompareIds();
    renderProducts();
    renderHighlights();
    renderCompareControls();
}

function clearCompare() {
    if (!state.compareIds.size) return;
    state.compareIds.clear();
    saveCompareIds();
    renderProducts();
    renderHighlights();
    renderCompareControls();
    showToast('Wyczyszczono porównanie');
}

function pruneCompareIds() {
    const availableIds = new Set(state.products.map(product => Number(product.id)));
    state.compareIds = new Set([...state.compareIds].filter(id => availableIds.has(Number(id))));
    saveCompareIds();
}

function renderCompareControls() {
    if (!compareTray) return;
    const selected = selectedCompareProducts();
    compareTray.hidden = selected.length === 0;
    if (compareHint) {
        compareHint.textContent = selected.length < 2
            ? `Wybierz co najmniej 2 produkty. ${selected.length}/${MAX_COMPARE_PRODUCTS} wybranych.`
            : `${selected.length}/${MAX_COMPARE_PRODUCTS} wybranych. Gotowe do porównania.`;
    }
    if (compareOpen) compareOpen.disabled = selected.length < 2;
    if (compareClear) compareClear.disabled = selected.length === 0;
    if (compareChips) {
        compareChips.innerHTML = selected.map(product => `
            <span class="compare-chip">
                <span class="compare-chip-label">${escapeHtml(product.name)}</span>
                <button type="button" data-compare-remove="${escapeHtml(product.id)}" aria-label="Usuń ${escapeHtml(product.name)} z porównania">×</button>
            </span>
        `).join('');
    }
    document.querySelectorAll('[data-action="compare"]').forEach(button => {
        const active = state.compareIds.has(Number(button.dataset.productId));
        button.classList.toggle('is-active', active);
        button.setAttribute('aria-pressed', String(active));
        button.setAttribute('title', active ? 'Usuń z porównania' : 'Dodaj do porównania');
        button.textContent = active ? '✓ Porównaj' : 'Porównaj';
    });
}

function openCompareModal() {
    const selected = selectedCompareProducts();
    if (selected.length < 2 || !compareModal || !compareTable) return;
    compareTable.innerHTML = renderComparisonTable(selected);
    compareModal.hidden = false;
    compareClose?.focus();
}

function closeCompareModal() {
    if (compareModal) compareModal.hidden = true;
}

function renderComparisonTable(products) {
    const parameterKeys = [...new Set(products.flatMap(product => Object.keys(product.technicalParameters || {})))].sort((left, right) => left.localeCompare(right, 'pl'));
    const rows = [
        ['Kategoria', product => product.category || '—'],
        ['Producent', product => product.manufacturer || '—'],
        ['Model', product => product.model || '—'],
        ['SKU', product => product.sku || '—'],
        ['Cena', product => formatMoney(product.price)],
        ['Dostępność', product => {
            const quantity = stockFor(product.id)?.availableQuantity;
            return Number.isInteger(quantity) ? `${quantity} szt.` : 'Niedostępna';
        }],
        ...parameterKeys.map(key => [key, product => product.technicalParameters?.[key] ?? '—'])
    ];
    const header = products.map(product => `<th scope="col">${escapeHtml(product.name)}</th>`).join('');
    const body = rows.map(([label, value]) => `
        <tr>
            <th scope="row">${escapeHtml(label)}</th>
            ${products.map(product => `<td>${escapeHtml(String(value(product)))}</td>`).join('')}
        </tr>
    `).join('');
    return `<div class="comparison-table-scroll"><table class="comparison-table">
        <thead><tr><th scope="col">Parametr</th>${header}</tr></thead>
        <tbody>${body}</tbody>
    </table></div>`;
}

function setCheckoutStatus(message, isError = false) {
    checkoutStatus.textContent = message;
    checkoutStatus.classList.toggle('error', isError);
    if (!isError) checkoutStatus.classList.remove('success');
}

function showToast(message, duration = 2600) {
    toast.textContent = message;
    toast.classList.add('visible');
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => toast.classList.remove('visible'), duration);
}

function finitePrice(primary, fallback = 0) {
    const first = Number(primary);
    if (Number.isFinite(first)) return first;
    const second = Number(fallback);
    return Number.isFinite(second) ? second : 0;
}

function formatMoney(value) {
    return `${Number(value || 0).toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} zł`;
}

function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>'"]/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[character]));
}

function cartStorageKey(customer) {
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
        // The in-memory cart is still reset when persistent storage is unavailable.
    }
    if (activeCartStorageKey === GUEST_CART_STORAGE_KEY) state.cart = [];
}

function switchCartScope(customer) {
    const nextStorageKey = cartStorageKey(customer);
    if (nextStorageKey === activeCartStorageKey) return;
    saveCart();
    activeCartStorageKey = nextStorageKey;
    state.cart = loadCart(activeCartStorageKey);
    renderCart();
}

function removeLegacyCartStorage() {
    try {
        localStorage.removeItem(LEGACY_CART_STORAGE_KEY);
    } catch {
        // Storage can be unavailable in privacy mode; the in-memory cart still works.
    }
}

function migrateCartStorageScope() {
    try {
        if (localStorage.getItem(CART_STORAGE_SCHEMA_KEY) === CART_STORAGE_SCHEMA_VERSION) return;
        localStorage.removeItem(GUEST_CART_STORAGE_KEY);
        localStorage.setItem(CART_STORAGE_SCHEMA_KEY, CART_STORAGE_SCHEMA_VERSION);
    } catch {
        // The current in-memory cart remains available when storage migration is blocked.
    }
}

function loadCart(storageKey = activeCartStorageKey) {
    try {
        const stored = JSON.parse(localStorage.getItem(storageKey) || '[]');
        return Array.isArray(stored) ? stored : [];
    } catch {
        return [];
    }
}

function loadCompareIds() {
    try {
        const stored = JSON.parse(localStorage.getItem('minitech-compare') || '[]');
        return Array.isArray(stored)
            ? stored.map(Number).filter(id => Number.isInteger(id))
            : [];
    } catch {
        return [];
    }
}

function saveCompareIds() {
    localStorage.setItem('minitech-compare', JSON.stringify([...state.compareIds]));
}

function saveCart() {
    try {
        localStorage.setItem(activeCartStorageKey, JSON.stringify(state.cart));
    } catch {
        // Keep shopping available in memory when persistent storage is blocked.
    }
}

function checkoutAttemptStorageKey() {
    return `${CHECKOUT_ATTEMPT_STORAGE_PREFIX}${activeCartStorageKey}`;
}

function checkoutFingerprint(context) {
    return JSON.stringify({
        cart: state.cart.map(item => ({ id: item.id, quantity: item.quantity })),
        ...context
    });
}

function loadCheckoutAttempt() {
    try {
        const attempt = JSON.parse(sessionStorage.getItem(checkoutAttemptStorageKey()) || 'null');
        return attempt && typeof attempt === 'object' ? attempt : null;
    } catch {
        return null;
    }
}

function saveCheckoutAttempt(attempt) {
    try {
        sessionStorage.setItem(checkoutAttemptStorageKey(), JSON.stringify(attempt));
    } catch {
        // A retry in the same JavaScript execution still uses the in-memory object.
    }
}

function clearCheckoutAttempt() {
    try {
        sessionStorage.removeItem(checkoutAttemptStorageKey());
    } catch {
        // No persisted attempt to remove in privacy mode.
    }
}
