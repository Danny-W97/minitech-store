(function initializeMiniTechSession(global) {
    'use strict';

    const STORAGE_KEY = 'minitech-auth:last-activity';
    const TIMEOUT_MS = 30 * 60 * 1000;
    const STORAGE_WRITE_THROTTLE_MS = 10 * 1000;
    const ACTIVITY_EVENTS = ['pointerdown', 'pointermove', 'keydown', 'touchstart', 'scroll'];

    let enabled = false;
    let expiryTimer = null;
    let expiryHandler = null;
    let lastActivity = 0;
    let lastStoredActivity = 0;
    let lastObservedActivity = 0;

    function readStoredActivity() {
        try {
            const value = Number(global.localStorage.getItem(STORAGE_KEY));
            return Number.isFinite(value) && value > 0 ? value : 0;
        } catch {
            return 0;
        }
    }

    function isFresh(now = Date.now()) {
        const activity = Math.max(lastActivity, readStoredActivity());
        return activity > 0 && now >= activity && now - activity < TIMEOUT_MS;
    }

    function scheduleExpiry() {
        if (!enabled) return;
        if (expiryTimer !== null) global.clearTimeout(expiryTimer);
        const activity = Math.max(lastActivity, readStoredActivity());
        const delay = activity > 0 ? TIMEOUT_MS - (Date.now() - activity) : 0;
        expiryTimer = global.setTimeout(expireIfInactive, Math.max(0, delay) + 25);
    }

    function touch(forceStore = false) {
        if (!enabled && !forceStore) return;
        const now = Date.now();
        lastActivity = now;
        if (forceStore || now - lastStoredActivity >= STORAGE_WRITE_THROTTLE_MS) {
            try {
                global.localStorage.setItem(STORAGE_KEY, String(now));
                lastStoredActivity = now;
            } catch {
                // The in-memory timer still protects the current tab.
            }
        }
        scheduleExpiry();
    }

    function expireIfInactive() {
        if (!enabled) return;
        if (isFresh()) {
            lastActivity = Math.max(lastActivity, readStoredActivity());
            scheduleExpiry();
            return;
        }
        const handler = expiryHandler;
        clear();
        if (typeof handler === 'function') Promise.resolve(handler()).catch(() => {});
    }

    function handleStorage(event) {
        if (event.key !== STORAGE_KEY) return;
        const value = Number(event.newValue);
        lastActivity = Number.isFinite(value) && value > 0 ? value : 0;
        if (!lastActivity) {
            expireIfInactive();
            return;
        }
        scheduleExpiry();
    }

    function recordActivity() {
        const now = Date.now();
        if (now - lastObservedActivity < 1000) return;
        lastObservedActivity = now;
        touch(false);
    }

    function start(onExpired) {
        stop();
        enabled = true;
        expiryHandler = onExpired;
        lastActivity = readStoredActivity();
        lastStoredActivity = lastActivity;
        ACTIVITY_EVENTS.forEach(eventName => global.addEventListener(eventName, recordActivity, { passive: true }));
        global.addEventListener('storage', handleStorage);
        scheduleExpiry();
    }

    function stop() {
        if (expiryTimer !== null) global.clearTimeout(expiryTimer);
        expiryTimer = null;
        if (enabled) {
            ACTIVITY_EVENTS.forEach(eventName => global.removeEventListener(eventName, recordActivity));
            global.removeEventListener('storage', handleStorage);
        }
        enabled = false;
        expiryHandler = null;
    }

    function clear() {
        stop();
        lastActivity = 0;
        lastStoredActivity = 0;
        lastObservedActivity = 0;
        try {
            global.localStorage.removeItem(STORAGE_KEY);
        } catch {
            // Nothing else is required when browser storage is unavailable.
        }
    }

    global.MiniTechSession = Object.freeze({
        storageKey: STORAGE_KEY,
        timeoutMs: TIMEOUT_MS,
        isFresh,
        start,
        touch: () => {
            if (!enabled) enabled = true;
            touch(true);
        },
        stop,
        clear
    });
})(window);
