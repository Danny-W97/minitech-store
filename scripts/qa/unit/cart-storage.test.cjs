const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// Evaluate the production ES module without importing any page or DOM globals.
const source = fs.readFileSync(path.join(__dirname, '../../../frontend/cart-storage.js'), 'utf8');
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

async function fixture(entries = {}) {
    const { createCartStorage } = await modulePromise;
    const values = new Map(Object.entries(entries));
    const calls = [];
    const storage = {
        getItem(key) { calls.push(['get', key]); return values.get(key) ?? null; },
        setItem(key, value) { calls.push(['set', key]); values.set(key, String(value)); },
        removeItem(key) { calls.push(['remove', key]); values.delete(key); }
    };
    return { values, calls, storage, store: createCartStorage({ storage: () => storage }) };
}

test('cart identities select a separate guest, customer A and customer B key', async () => {
    const { cartStorageKey } = await modulePromise;
    assert.equal(cartStorageKey({id: 17}), 'minitech-cart:customer:17');
    assert.equal(cartStorageKey({id: '18'}), 'minitech-cart:customer:18');
    for (const customer of [null, {}, {id: 0}, {id: -1}, {id: 1.5}, {id: 'bad'}]) {
        assert.equal(cartStorageKey(customer), 'minitech-cart:guest');
    }
});

test('migration deletes legacy/old guest data while preserving every customer cart', async () => {
    const f = await fixture({
        'minitech-cart': '[{"id":1}]', 'minitech-cart:guest': '[{"id":2}]',
        'minitech-cart:customer:17': '[{"id":3}]', 'minitech-cart:customer:18': '[{"id":4}]'
    });
    f.store.initialize();
    assert.equal(f.values.has('minitech-cart'), false);
    assert.equal(f.values.has('minitech-cart:guest'), false);
    assert.equal(f.values.get('minitech-cart:schema-version'), '2');
    assert.deepEqual(f.store.load(f.store.key({id:17})), [{id:3}]);
    assert.deepEqual(f.store.load(f.store.key({id:18})), [{id:4}]);
});

test('repeated initialization on schema 2 preserves the current guest cart', async () => {
    const f = await fixture({'minitech-cart:schema-version':'2', 'minitech-cart:guest':'[{"id":2,"quantity":3}]', 'minitech-cart':'[]'});
    f.store.initialize(); f.store.initialize();
    assert.deepEqual(f.store.load(), [{id:2,quantity:3}]);
    assert.equal(f.values.has('minitech-cart'), false);
});

test('an empty customer cart never falls back to guest or legacy data', async () => {
    const f = await fixture({'minitech-cart':'[{"id":1}]', 'minitech-cart:guest':'[{"id":2}]'});
    assert.deepEqual(f.store.load(f.store.key({id:17})), []);
    assert.deepEqual(f.calls, [['get','minitech-cart:customer:17']]);
});

test('saving a customer cart does not copy or overwrite another scope', async () => {
    const f = await fixture();
    f.store.save(f.store.key({id:17}), [{id:3,quantity:2}]);
    f.store.save(f.store.key({id:18}), [{id:4,quantity:1}]);
    f.store.save(f.store.key(null), [{id:5,quantity:1}]);
    assert.deepEqual(f.store.load(f.store.key({id:17})), [{id:3,quantity:2}]);
    f.store.clearGuest();
    assert.deepEqual(f.store.load(), []);
    assert.deepEqual(f.store.load(f.store.key({id:18})), [{id:4,quantity:1}]);
    assert.deepEqual(f.store.load(f.store.key({id:17})), [{id:3,quantity:2}]);
});

test('malformed JSON and non-array values become an empty cart without evaluation', async () => {
    for (const value of ['{', 'null', '123', '{}', '"text"', '(()=>{throw 1})()']) {
        const f = await fixture({'minitech-cart:guest':value});
        assert.deepEqual(f.store.load(), []);
    }
});

test('mutating a loaded array does not write until the page explicitly saves', async () => {
    const f = await fixture({'minitech-cart:guest':'[{"id":2,"quantity":1}]'});
    const cart = f.store.load(); cart[0].quantity = 2;
    assert.equal(f.store.load()[0].quantity, 1);
    assert.equal(f.store.save('minitech-cart:guest',cart), true);
    assert.equal(f.store.load()[0].quantity, 2);
});

test('unavailable storage is resolved lazily and never prevents page actions', async () => {
    const { createCartStorage } = await modulePromise;
    let calls = 0;
    const store = createCartStorage({storage: () => { calls++; throw Error('private browsing'); }});
    assert.equal(calls, 0);
    assert.doesNotThrow(() => store.initialize());
    assert.deepEqual(store.load(), []);
    assert.equal(store.save('minitech-cart:guest',[]), false);
    assert.equal(store.clearGuest(), false);
});

test('a full storage quota cannot throw from save or initialization', async () => {
    const { createCartStorage } = await modulePromise;
    const storage = {getItem: () => null, removeItem() {}, setItem() { throw Error('quota'); }};
    const store = createCartStorage({storage: () => storage});
    assert.doesNotThrow(() => store.initialize());
    assert.equal(store.save('minitech-cart:guest',[{id:2}]), false);
    assert.deepEqual(store.load(), []);
});
