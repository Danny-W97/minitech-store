/** Persistent cart data only; authentication and rendering stay with each page. */
export const GUEST_CART_STORAGE_KEY = 'minitech-cart:guest';
const CUSTOMER_CART_STORAGE_PREFIX = 'minitech-cart:customer:';
const LEGACY_CART_STORAGE_KEY = 'minitech-cart';
const CART_STORAGE_SCHEMA_KEY = 'minitech-cart:schema-version';
const CART_STORAGE_SCHEMA_VERSION = '2';

export function cartStorageKey(customer) {
    const customerId = Number(customer?.id);
    return Number.isInteger(customerId) && customerId > 0
        ? `${CUSTOMER_CART_STORAGE_PREFIX}${customerId}`
        : GUEST_CART_STORAGE_KEY;
}

/** Resolve storage lazily so private browsing cannot prevent page initialization. */
export function createCartStorage({ storage = () => globalThis.localStorage } = {}) {
    function clearGuest() {
        try {
            storage().removeItem(GUEST_CART_STORAGE_KEY);
            return true;
        } catch {
            return false;
        }
    }

    function initialize() {
        try {
            storage().removeItem(LEGACY_CART_STORAGE_KEY);
        } catch {
            // A legacy cart must never be used as fallback for an identified customer.
        }
        try {
            const target = storage();
            if (target.getItem(CART_STORAGE_SCHEMA_KEY) === CART_STORAGE_SCHEMA_VERSION) return;
            target.removeItem(GUEST_CART_STORAGE_KEY);
            target.setItem(CART_STORAGE_SCHEMA_KEY, CART_STORAGE_SCHEMA_VERSION);
        } catch {
            // Storage is optional; the page still owns its live in-memory state.
        }
    }

    function load(key = GUEST_CART_STORAGE_KEY) {
        try {
            const value = JSON.parse(storage().getItem(key) || '[]');
            return Array.isArray(value) ? value : [];
        } catch {
            return [];
        }
    }

    function save(key, cart) {
        try {
            storage().setItem(key, JSON.stringify(cart));
            return true;
        } catch {
            return false;
        }
    }

    return Object.freeze({ key: cartStorageKey, initialize, clearGuest, load, save });
}
