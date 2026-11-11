const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../../frontend/store-checkout.js'), 'utf8').replace(/^export /gm, '');
const intentHelpers = fs.readFileSync(path.join(__dirname, '../../../frontend/checkout-intent.js'), 'utf8').replace(/^export /gm, '');
const helpers = source.slice(source.indexOf('function checkoutFingerprint('), source.indexOf('function findCheckoutAttemptByOrder('));
const submit = source.slice(source.indexOf('async function submitCheckout('), source.indexOf('function openCheckoutChoice('));
const save = source.slice(source.indexOf('function saveCheckoutAttempt('));

function fixture({blocked = false} = {}) {
  const stored = new Map(), calls = [], messages = [];
  let sequence = 0, cost = 10, failure = 'lost reply';
  const address = {firstName: 'Jan', lastName: 'Test', street: 'Street', postalCode: '00-001', city: 'City', country: 'PL', phone: null};
  const context = vm.createContext({
    i18n: null,
    JSON, Object, Array, Number, crypto: {randomUUID() {return `attempt-${++sequence}`;}}, console,
    state: {cart: [{id: 1, quantity: 1}], customer: {id: 17}, coupon: null}, runtime: {purchaseMode: 'account', checkoutSubmissionInProgress: false},
    window: {MiniTechApi: {newAccessToken() {return `capability-${sequence}`;}}},
    document: {querySelector(selector) {return {value: selector === '#payment-method' ? 'CARD' : 'owner@example.test'};}},
    checkoutSubmissionInProgress: false, checkoutSubmit: {}, checkoutSummarySubmit: {},
    deliveryEmailInput: null, invoiceRequested: {checked: false}, promotionCodeInput: {value: ''}, deliveryDate: {value: '2026-11-01'},
    readDeliveryAddress() {return {...address};}, setCheckoutStatus(value) {messages.push(value);},
    applyPaymentConfiguration: async () => ({supportedMethods: ['CARD']}), ensureCartStockAvailable: async () => {},
    loadDeliveryQuote: async () => ({methodCode: 'STANDARD_COURIER', fulfillmentType: 'HOME_DELIVERY', deliveryCost: cost, selectedDate: '2026-11-01'}),
    checkoutAttemptStorageKey: () => 'attempt',
    sessionStorage: {getItem(key) {return stored.get(key) || null;}, setItem(key, value) {if (blocked) throw Error('blocked'); stored.set(key, value);}, removeItem(key) {stored.delete(key);}},
    api: async (url, options) => {calls.push({url, ...options}); throw Object.assign(Error('Interrupted'), typeof failure === 'string' ? {} : failure);}
  });
  vm.runInContext(intentHelpers + helpers + save + submit, context);
  return {context, calls, stored, messages, cost(value) {cost = value;}, failure(value) {failure = value;},
    run: () => vm.runInContext('submitCheckout({preventDefault() {}})', context)};
}

test('a lost order reply and new delivery quote retain the same checkout key and capability', async () => {
  const f = fixture(); await f.run(); f.cost(99); await f.run();
  assert.equal(f.calls.length, 2);
  assert.equal(f.calls[0].headers['Idempotency-Key'], f.calls[1].headers['Idempotency-Key']);
  assert.equal(f.calls[0].headers['X-Order-Access-Token'], f.calls[1].headers['X-Order-Access-Token']);
  assert.equal(JSON.parse(f.calls[1].body).deliveryCost, 99); // The server retains its original quote.
});
test('a stored version 2 fingerprint with the previous delivery cost is migrated without rotating the key', async () => {
  const f = fixture(); await f.run();
  const saved = JSON.parse(f.stored.get('attempt'));
  const legacy = JSON.parse(saved.fingerprint); legacy.deliveryCost = 10;
  saved.fingerprint = JSON.stringify(legacy); f.stored.set('attempt', JSON.stringify(saved));
  f.cost(50); await f.run();
  assert.equal(f.calls[0].headers['Idempotency-Key'], f.calls[1].headers['Idempotency-Key']);
});
test('blocked session storage prevents an order POST and keeps the cart', async () => {
  const f = fixture({blocked: true}); await f.run();
  assert.equal(f.calls.length, 0); assert.equal(f.context.state.cart.length, 1);
  assert.ok(f.messages.some(message => message.includes('pamięć sesji')));
});
test('pending and legacy conflicts retain the attempt for review instead of submitting payment or replacing the key', async () => {
  for (const code of ['ORDER_IN_PROGRESS', 'ORDER_IDEMPOTENCY_LEGACY_REVIEW', 'ORDER_IDEMPOTENCY_CONFLICT']) {
    const f = fixture(); f.failure({code, orderId: 42}); await f.run(); const before = JSON.parse(f.stored.get('attempt'));
    await f.run(); assert.equal(f.calls.length, 2); assert.ok(f.calls.every(call => call.url === '/orders'));
    assert.equal(JSON.parse(f.stored.get('attempt')).orderIdempotencyKey, before.orderIdempotencyKey);
    assert.equal(f.context.state.cart.length, 1);
    assert.ok(f.messages.some(message => message.includes('#42')));
  }
});


test('when payment service fails after order creation, created order number is displayed in error message', async () => {
  const f = fixture();
  f.context.api = async (url, options) => {
    f.calls.push({url, ...options});
    if (url === '/orders') return { id: 88, customerId: 17, items: [{ productId: 1, quantity: 1 }] };
    if (url === '/payments') throw Error('Downstream service is unavailable');
    throw Error('Unexpected call');
  };
  await f.run();
  assert.equal(f.calls.length, 2);
  assert.equal(f.calls[0].url, '/orders');
  assert.equal(f.calls[1].url, '/payments');
  assert.ok(f.messages.some(msg => msg.includes('#88') && msg.includes('Downstream service is unavailable')));
});
