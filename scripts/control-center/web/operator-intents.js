// Persist commands before sending them; an uncertain response must reuse the same ID.
const UUID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;

export function correctionAmount(value) {
  const text = String(value).trim();
  if (!/^-?\d{1,17}(\.\d{1,2})?$/.test(text)) throw new Error('Podaj kwotę z maksymalnie dwoma miejscami po przecinku.');
  const [whole, fraction = ''] = text.replace('-', '').split('.');
  let cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  if (text.startsWith('-')) cents = -cents;
  if (!cents) throw new Error('Kwota korekty musi być różna od zera.');
  const abs = cents < 0n ? -cents : cents;
  return `${cents < 0n ? '-' : ''}${abs / 100n}.${String(abs % 100n).padStart(2, '0')}`;
}

export function createOperatorIntents({getProfile, storage = () => localStorage,
  locks = () => navigator.locks, randomUUID = () => crypto.randomUUID()} = {}) {
  const specs = {
    correction: {
      prefix: 'minitech-invoice-correction-v1', id: 'idempotencyKey',
      valid: value => UUID.test(value.idempotencyKey) && Number.isSafeInteger(value.expectedVersion)
        && value.expectedVersion >= 0 && typeof value.reason === 'string' && !!value.reason.trim()
        && value.reason.length <= 255 && !/[\x00-\x1f\x7f]/.test(value.reason)
        && correctionAmount(value.correctionGross) === value.correctionGross,
      same: (a, b) => a.reason === b.reason && a.correctionGross === b.correctionGross && a.expectedVersion === b.expectedVersion,
      unreadable: 'Nie można odczytać poprzedniej próby korekty. Sprawdź historię dokumentów.',
      unavailable: 'Nie można bezpiecznie zapisać korekty. Użyj polecenia invoice-correct w CC.',
      conflict: () => 'Najpierw ponów poprzednią próbę korekty z tymi samymi danymi.',
    },
    stock: {
      prefix: 'minitech-stock-receipt-v1', id: 'operationId',
      valid: value => UUID.test(value.operationId) && Number.isSafeInteger(value.quantity) && value.quantity > 0,
      same: (a, b) => a.quantity === b.quantity,
      unreadable: 'Nie można odczytać zapisanej operacji magazynowej. Sprawdź jej status w rejestrze.',
      unavailable: 'Nie można bezpiecznie zapisać operacji. Użyj polecenia inventory-receive w CC.',
      conflict: value => `Najpierw rozstrzygnij operację ${value.operationId} (+${value.quantity}).`,
    },
  };
  const keyFor = (spec, resource) => `${spec.prefix}:${getProfile()}:${resource}`;
  function read(spec, key) {
    const raw = storage().getItem(key);
    if (raw === null) return null;
    try {
      const value = JSON.parse(raw);
      if (!value || !spec.valid(value)) throw new Error();
      return value;
    } catch { throw new Error(spec.unreadable); }
  }
  async function begin(spec, resource, payload) {
    if (!spec.valid({...payload, [spec.id]: '00000000-0000-4000-8000-000000000000'}))
      throw new Error('Nieprawidłowe dane operacji.');
    const lockManager = locks();
    if (!lockManager?.request) throw new Error(spec.unavailable);
    // Capture the profile before awaiting a lock, including the eventual cleanup key.
    const key = keyFor(spec, resource);
    return lockManager.request(key, () => {
      const existing = read(spec, key);
      if (existing && !spec.same(existing, payload)) throw new Error(spec.conflict(existing));
      let intent = existing;
      if (!intent) {
        let id;
        try { id = randomUUID(); } catch { throw new Error(spec.unavailable); }
        intent = {...payload, [spec.id]: id};
        if (!spec.valid(intent)) throw new Error('Nieprawidłowe dane operacji.');
      }
      storage().setItem(key, JSON.stringify(intent));
      return {...intent, storageKey: key};
    });
  }
  async function finish(spec, intent) {
    if (!intent?.storageKey?.startsWith(`${spec.prefix}:`) || !UUID.test(intent[spec.id]))
      throw new Error('Nieprawidłowy identyfikator zapisanej operacji.');
    return locks().request(intent.storageKey, () => {
      const current = read(spec, intent.storageKey);
      if (current?.[spec.id] === intent[spec.id]) storage().removeItem(intent.storageKey);
    });
  }
  return {
    readPendingCorrection: id => read(specs.correction, keyFor(specs.correction, id)),
    correctionIntent: (id, reason, amount, version, beforeGross) => begin(specs.correction, id,
      {reason, correctionGross: amount, expectedVersion: version, beforeGross}),
    finishCorrectionIntent: intent => finish(specs.correction, intent),
    readPendingStock: id => read(specs.stock, keyFor(specs.stock, id)),
    stockIntent: (id, quantity) => begin(specs.stock, id, {quantity}),
    finishStockIntent: intent => finish(specs.stock, intent),
  };
}
