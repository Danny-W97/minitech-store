const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../../frontend/store-checkout.js'), 'utf8').replace(/^export /gm, '');
const start = source.indexOf('async function resumeStripeCheckoutReturn()');
const end = source.indexOf('\nfunction saveCheckoutAttempt(', start);
assert.ok(start > 0 && end > start);
test('checkout recovery looks only in the active account or guest scope',()=>{
  const begin=source.indexOf('function findCheckoutAttemptByOrder('),finish=source.indexOf('\nfunction showStripePaymentStatus(',begin);
  const stored=new Map([['checkout:customer:17',JSON.stringify({orderId:42,paymentId:9,orderAccessToken:'private-fixture'})]]);
  let active='checkout:customer:18';
  const context=vm.createContext({Number,JSON,CHECKOUT_ATTEMPT_STORAGE_PREFIX:'checkout:',checkoutAttemptStorageKey:()=>active,
    sessionStorage:{get length(){return stored.size;},key:n=>[...stored.keys()][n],getItem:key=>stored.get(key)}});
  vm.runInContext(source.slice(begin,finish),context);
  assert.equal(vm.runInContext('findCheckoutAttemptByOrder(42)',context),null);
  active='checkout:customer:17';assert.equal(vm.runInContext('findCheckoutAttemptByOrder(42).attempt.paymentId',context),9);
  active='checkout:guest';assert.equal(vm.runInContext('findCheckoutAttemptByOrder(42)',context),null);
  assert.equal(stored.size,1);
});

async function run({customerId = null, status = 'SUCCEEDED', missing = false, denied = false, otherCart = false} = {}) {
  const calls = [], messages = [], removedAttempts = [], removedCarts = [], home = [];
  const state = {cart: [{id: 1}], coupon: 'COUPON'};
  const stored = {attempt: {paymentId: 9, customerId, orderAccessToken: 'capability', recipient: 'owner@example.test'}, storageKey: `checkout:${otherCart ? 'other-cart' : 'current-cart'}`};
  const classList = {add() {}, remove() {}};
  const context = vm.createContext({
    i18n: null,
    URLSearchParams, Number, Promise, state, CHECKOUT_ATTEMPT_STORAGE_PREFIX: 'checkout:', runtime: {activeCartStorageKey: 'current-cart'},
    window: {location: {search: '?paymentReturn=success&paymentOrder=42', pathname: '/index.html'}, history: {replaceState() {}}, setTimeout(fn) {fn();}},
    document: {title: 'Shop', body: {classList}}, checkoutSection: {}, checkoutLayout: {classList}, setCheckoutStep() {},
    findCheckoutAttemptByOrder() {return missing ? null : stored;},
    showStripePaymentStatus(...args) {messages.push(args);},
    api: async (path, options) => {calls.push({path, options}); if (denied) throw Error('403'); return {status, checkoutUrl: status === 'PENDING' ? 'https://checkout.stripe.com/c/pay/test' : null};},
    sessionStorage: {removeItem(key) {removedAttempts.push(key);}}, localStorage: {removeItem(key) {removedCarts.push(key);}},
    saveCart() {}, renderCart() {}, returnToStoreHome(...args) {home.push(args);}
  });
  await vm.runInContext(source.slice(start, end) + '\nresumeStripeCheckoutReturn()', context);
  return {state, calls, messages, removedAttempts, removedCarts, home};
}

test('registered customer payment polling retains authentication after returning from Stripe', async () => {
  const result = await run({customerId: 17});
  assert.equal(result.calls[0].options.anonymous, false);
  assert.equal(result.state.cart.length, 0);assert.equal(result.home.length, 1);
});
test('guest pending payment retains cart and polls with its order capability', async () => {
  const result = await run({status: 'PENDING'});
  assert.equal(result.calls.length, 12);assert.equal(result.calls[0].options.anonymous, true);
  assert.equal(result.calls[0].options.headers['X-Order-Access-Token'], 'capability');
  assert.equal(result.state.cart.length, 1);assert.equal(result.removedAttempts.length, 0);assert.equal(result.home.length, 0);
});
for (const status of ['REFUNDED', 'FAILED']) test(`${status} keeps the cart and ends the old attempt without claiming a purchase`, async () => {
  const result = await run({status});assert.equal(result.state.cart.length, 1);
  assert.equal(result.removedAttempts.length, 1);assert.equal(result.home.length, 0);
  assert.ok(result.messages[0][0].includes(status === 'REFUNDED' ? 'zwrócona' : 'nie została zakończona'));
});
test('success query parameters cannot replace missing ownership or an API confirmation', async () => {
  const missing = await run({missing: true});assert.equal(missing.calls.length, 0);assert.equal(missing.home.length, 0);
  const denied = await run({customerId: 17, denied: true});assert.equal(denied.state.cart.length, 1);assert.equal(denied.home.length, 0);
});
test('payment for a previous cart does not erase the currently active cart', async () => {
  const result = await run({otherCart: true});assert.equal(result.state.cart.length, 1);
  assert.deepEqual(result.removedCarts, ['other-cart']);
});
