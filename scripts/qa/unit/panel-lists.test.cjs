const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../control-center/web/panel-lists.js'), 'utf8');
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

function deferred() {
    let resolve, reject;
    const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
    return {promise, resolve, reject};
}
async function fixture() {
    const {createPanelLists} = await modulePromise;
    const calls = [], errors = [], timers = new Map();
    let sequence = 0;
    const lists = createPanelLists({
        request(url, options) { const pending = deferred(); calls.push({url, ...options, ...pending}); return pending.promise; },
        setTimeout(callback, delay) { const id = ++sequence; timers.set(id, {callback, delay}); return id; },
        clearTimeout(id) { timers.delete(id); },
        onError: error => errors.push(error)
    });
    return {lists, calls, errors, timers, async flush() {
        const pending = [...timers.values()]; timers.clear();
        await Promise.all(pending.map(timer => timer.callback()));
    }};
}
const result = {orders: [{id: '9223372036854775806'}], hasMore: true, nextCursor: '9223372036854775806'};

test('list reads encode filters, enforce the page size and preserve opaque result cursors', async () => {
    const f = await fixture();
    const pending = f.lists.fetch('orders', '/api/data/orders', {search: 'a&b@example.test', status: 'PAID', size: '999', cursor: 'other'});
    const url = new URL(f.calls[0].url, 'http://localhost');
    assert.equal(url.searchParams.get('search'), 'a&b@example.test');
    assert.equal(url.searchParams.get('status'), 'PAID');
    assert.equal(url.searchParams.get('size'), '50');
    assert.equal(url.searchParams.get('cursor'), '0');
    assert.equal(f.calls[0].signal.aborted, false);
    f.calls[0].resolve(result);
    assert.equal(await pending, result);
});

test('superseding a read aborts its signal and discards a noncooperative late success', async () => {
    const f = await fixture();
    const old = f.lists.fetch('customers', '/api/data/customers', {search: 'old'});
    const current = f.lists.fetch('customers', '/api/data/customers', {search: 'new'});
    assert.equal(f.calls[0].signal.aborted, true);
    f.calls[1].resolve({customers: [{id: 9}], hasMore: false});
    assert.deepEqual(await current, {customers: [{id: 9}], hasMore: false});
    f.calls[0].resolve({customers: [{id: 1}], hasMore: true});
    assert.deepEqual(await old, {stale: true});
});

test('a stale failure is ignored while the current failure retains its metadata', async () => {
    const f = await fixture();
    const old = f.lists.fetch('orders', '/api/data/orders');
    const current = f.lists.fetch('orders', '/api/data/orders');
    f.calls[0].reject(Object.assign(new Error('aborted old read'), {name: 'AbortError'}));
    assert.deepEqual(await old, {stale: true});
    const error = Object.assign(new Error('current outage'), {status: 503, code: 'FIXTURE'});
    f.calls[1].reject(error);
    await assert.rejects(current, received => received === error);
});

test('different list kinds and separate panels never cancel each other', async () => {
    const a = await fixture(), b = await fixture();
    const pending = [a.lists.fetch('dispatch', '/api/data/invoices'), a.lists.fetch('dispatch-corrections', '/api/data/corrections'), b.lists.fetch('dispatch', '/api/data/invoices')];
    a.lists.dispose();
    assert.equal(a.calls[0].signal.aborted, true); assert.equal(a.calls[1].signal.aborted, true);
    assert.equal(b.calls[0].signal.aborted, false);
    a.calls.forEach(call => call.resolve(result)); b.calls[0].resolve(result);
    assert.deepEqual(await Promise.all(pending), [{stale: true}, {stale: true}, result]);
});

test('typing invalidates immediately and repeated filters debounce to one reload', async () => {
    const f = await fixture();
    const old = f.lists.fetch('customers', '/api/data/customers', {search: 'old'});
    let reloads = 0;
    const reload = force => { assert.equal(force, true); reloads++; return f.lists.fetch('customers', '/api/data/customers', {search: 'final'}); };
    f.lists.filter('customers', reload);
    assert.equal(f.calls[0].signal.aborted, true);
    f.lists.filter('customers', reload);
    assert.equal(f.timers.size, 1); assert.equal([...f.timers.values()][0].delay, 300);
    f.calls[0].resolve(result); assert.deepEqual(await old, {stale: true});
    const flushing = f.flush();
    assert.equal(reloads, 1); assert.equal(f.calls.length, 2);
    f.calls[1].resolve({customers: []}); await flushing;
    assert.deepEqual(f.errors, []);
});

test('disposal aborts active reads, cancels debounces and prevents later requests', async () => {
    const f = await fixture();
    const pending = f.lists.fetch('orders', '/api/data/orders');
    f.lists.filter('customers', () => { throw Error('must not run'); });
    f.lists.dispose(); f.lists.dispose();
    assert.equal(f.calls[0].signal.aborted, true); assert.equal(f.timers.size, 0);
    f.calls[0].reject(new Error('aborted')); assert.deepEqual(await pending, {stale: true});
    assert.deepEqual(await f.lists.fetch('orders', '/api/data/orders'), {stale: true});
    f.lists.filter('orders', () => {});
    assert.equal(f.timers.size, 0); assert.equal(f.calls.length, 1);
});

test('a filter reload rejection reaches the UI handler without an unhandled promise', async () => {
    const f = await fixture();
    const error = new Error('fixture reload failure');
    f.lists.filter('orders', async () => { throw error; });
    await f.flush();
    assert.deepEqual(f.errors, [error]);
});

test('malformed current list payloads are rejected and a later retry can succeed', async () => {
    const f = await fixture();
    for (const payload of [null, [], 'wrong']) {
        const pending = f.lists.fetch('orders', '/api/data/orders');
        f.calls.at(-1).resolve(payload);
        await assert.rejects(pending, /Nieprawidłowa odpowiedź listy/);
    }
    const pending = f.lists.fetch('orders', '/api/data/orders');
    f.calls.at(-1).resolve(result); assert.equal(await pending, result);
});
