const {test, before} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
let createCustomerSession;
before(async () => ({createCustomerSession} = await import('data:text/javascript;base64,' + Buffer.from(fs.readFileSync('frontend/customer-session.js', 'utf8')).toString('base64'))));
const value = {accessToken: 'fixture-only', customer: {id: 1}};
const response = (status = 200, data = value) => ({ok: status === 200, status, json: async () => data});
function fixture(send, options = {}) {
    const sessions = [], failures = [], calls = [], cleared = [], expired = [];
    const guard = {isFresh: () => true, clear: () => cleared.push(true)};
    const client = createCustomerSession({baseUrl: 'https://store.test/api', guard,
        fetch: (...args) => {calls.push(args); return send(...args);}, locks: () => null,
        onSession: session => sessions.push(session), onFailure: error => failures.push(error),
        expire: async () => expired.push(true), ...options});
    return {client, guard, sessions, failures, calls, cleared, expired};
}
function deferred() {let resolve; const promise = new Promise(r => {resolve = r;}); return {promise, resolve};}
test('concurrent refresh shares one request, its cookie options and one delivered session', async () => {
    const held = deferred(), f = fixture(() => held.promise);
    const a = f.client.refresh(), b = f.client.refresh(); assert.equal(a, b);
    held.resolve(response()); assert.equal(await a, true); assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0][0], 'https://store.test/api/customers/refresh');
    assert.equal(f.calls[0][1].credentials, 'include'); assert.equal(f.calls[0][1].redirect, 'error');
    assert.equal(f.sessions.length, 1);
});
test('all pages use the same cross-tab lock and recheck freshness after waiting', async () => {
    let run; const f = fixture(async () => response(), {locks: () => ({request(name, work) {assert.equal(name, 'minitech-refresh-session'); run = work; return new Promise(resolve => {setImmediate(async () => resolve(await work()));});}})});
    const task = f.client.refresh(); await Promise.resolve(); assert.ok(run);
    f.guard.isFresh = () => false; assert.equal(await task, false); assert.equal(f.calls.length, 0); assert.equal(f.expired.length, 1);
});
test('logout cancels the request and ignores a non-cooperative late body', async () => {
    const body = deferred(); const f = fixture(async () => ({ok: true, json: () => body.promise}));
    const task = f.client.refresh(); await Promise.resolve(); await Promise.resolve();
    f.client.invalidate(); assert.equal(f.calls[0][1].signal.aborted, true);
    body.resolve(value); assert.equal(await task, false); assert.equal(f.sessions.length, 0);
});
test('invalidation before lock dispatch does not send a request', async () => {
    const f = fixture(async () => response()); const task = f.client.refresh(); f.client.invalidate();
    assert.equal(await task, false); assert.equal(f.calls.length, 0);
});
test('a stale finally cannot remove a newer refresh promise', async () => {
    const first = deferred(), second = deferred(); let count = 0;
    const f = fixture(() => ++count === 1 ? first.promise : second.promise);
    const a = f.client.refresh(); await Promise.resolve(); f.client.invalidate();
    const b = f.client.refresh(); await Promise.resolve(); first.resolve(response()); await a;
    assert.equal(f.client.refresh(), b); second.resolve(response()); assert.equal(await b, true);
});
test('401 and 403 clear the idle-session guard; other failures do not', async () => {
    for (const status of [401, 403, 503]) {
        const f = fixture(async () => response(status)); assert.equal(await f.client.refresh(), false);
        assert.equal(f.cleared.length, status === 503 ? 0 : 1); assert.equal(f.failures[0].status, status);
    }
});
test('malformed sessions and network failure never deliver credentials', async () => {
    for (const data of [null, {}, {accessToken: 'a'}, {accessToken: '', customer: {id: 1}}]) {
        const f = fixture(async () => response(200, data)); assert.equal(await f.client.refresh(), false);
        assert.equal(f.sessions.length, 0); assert.equal(f.failures.length, 1);
    }
    const f = fixture(async () => {throw Error('offline');}); assert.equal(await f.client.refresh(), false);
});
test('restore retries only transient refresh failures with the configured bounded waits', async () => {
    let count = 0; const waits = []; const f = fixture(async () => response(++count === 3 ? 200 : 503), {wait: async ms => waits.push(ms)});
    assert.equal(await f.client.restore({attempts: 3}), true); assert.deepEqual(waits, [250, 500]);
});
test('restore stops after a definitive rejection clears freshness', async () => {
    let fresh = true; const f = fixture(async () => response(401), {guard: {isFresh: () => fresh, clear: () => {fresh = false;}}});
    assert.equal(await f.client.restore({attempts: 3}), false); assert.equal(f.calls.length, 1);
});
test('a fresh explicit login invalidates pending restore retries', async () => {
    const held = deferred(); const f = fixture(async () => response(503), {wait: () => held.promise});
    const task = f.client.restore({attempts: 3}); await new Promise(resolve => setImmediate(resolve));
    f.client.invalidate(); held.resolve(); assert.equal(await task, false); assert.equal(f.calls.length, 1);
});
test('invalid API bases fail before any request', () => {
    for (const baseUrl of ['file:///test', 'https://a:b@store.test', 'https://store.test/api?x=1'])
        assert.throws(() => fixture(async () => response(), {baseUrl}));
});
test('public authentication paths cannot recursively refresh their own errors', async () => {
    const {canRefreshCustomerPath} = await import('data:text/javascript;base64,' + Buffer.from(fs.readFileSync('frontend/customer-session.js','utf8')).toString('base64'));
    for (const path of ['/customers/login','/customers/register','/customers/refresh','/customers/logout?x=1']) assert.equal(canRefreshCustomerPath(path),false);
    assert.equal(canRefreshCustomerPath('/orders/1'),true); assert.equal(canRefreshCustomerPath('/customers/mfa/status'),true);
});
