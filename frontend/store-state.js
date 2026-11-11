import { GUEST_CART_STORAGE_KEY, createCartStorage } from './cart-storage.js?v=20261026-1';

export function hasAuthenticationMarker() {
    try {
        const value = Number(localStorage.getItem(sessionGuard?.storageKey || ''));
        return Number.isFinite(value) && value > 0;
    } catch {
        return false;
    }
}

export function loadCompareIds() {
    try {
        const stored = JSON.parse(localStorage.getItem('minitech-compare') || '[]');
        return Array.isArray(stored)
            ? stored.map(Number).filter(id => Number.isInteger(id))
            : [];
    } catch {
        return [];
    }
}

export const cartStore = createCartStorage({ storage: () => localStorage });

export const CHECKOUT_ATTEMPT_STORAGE_PREFIX = 'minitech-checkout-attempt:';

export const sessionGuard = window.MiniTechSession;

cartStore.initialize();

export const runtime = {
    activeCartStorageKey: GUEST_CART_STORAGE_KEY,
    authenticatedSessionExpected: hasAuthenticationMarker(),
    catalogContext: null,
    stockSortVersion: 0,
    passwordResetMode: false,
    mfaEnrollment: null,
    newProductsAutoScroll: null,
    newProductsDrag: null,
    suppressNewProductsClick: false,
    heroAutoScroll: null,
    heroIndex: 0,
    heroPaused: false,
    promotionCountdownInterval: null,
    dailyRotationRefreshTimer: null,
    hotShotRotationInterval: null,
    hotShotItems: [],
    hotShotIndex: 0,
    hotShotRotationPaused: false,
    cartPreviewCloseTimer: null,
    purchaseMode: 'guest',
    pendingPurchaseMode: null,
    checkoutStep: 'cart',
    checkoutSubmissionInProgress: false,
    cachedPaymentConfiguration: null,
    orderHistoryGeneration: 0
};

export const state = {
    products: [],
    inventory: {},
    lowestPrices: {},
    deliveryMethods: [],
    pickupLocations: [],
    deliveryQuote: null,
    coupon: null,
    cart: cartStore.load(runtime.activeCartStorageKey),
    customer: null,
    accessToken: null,
    mfaChallenge: null,
    wishlist: new Set(),
    compareIds: new Set(loadCompareIds())
};

export const MAX_COMPARE_PRODUCTS = 4;

export const CATEGORY_PARAMETER_KEYS = Object.freeze({
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

export const TECHNICAL_PARAMETER_LABELS = Object.freeze({
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

export const LOGIN_REQUIRED_THRESHOLD = 1000;
