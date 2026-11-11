

export function canonicalCheckoutIntent(value) {
    const { deliveryCost, ...intent } = value;
    intent.cart = [...intent.cart].sort((a, b) => a.id - b.id);
    function stable(item) {
        if (Array.isArray(item)) return item.map(stable);
        if (item && typeof item === 'object') return Object.fromEntries(Object.keys(item).sort().map(key => [key, stable(item[key])]));
        return item;
    }
    return JSON.stringify(stable(intent));
}

export function checkoutAttemptFingerprintMatches(attempt, expected) {
    try { return canonicalCheckoutIntent(JSON.parse(attempt.fingerprint)) === expected; }
    catch { return false; }
}
