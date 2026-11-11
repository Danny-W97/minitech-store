const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../../frontend/store-client.js'), 'utf8');
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const reply = (data, status = 200, headers = {}) => new Response(JSON.stringify(data), {status, headers});
test('bounded JSON requests cancel streaming bodies before retaining an oversized response', async () => {
    let cancelled = false;
    const stream = new ReadableStream({pull(controller) {controller.enqueue(new TextEncoder().encode('x'.repeat(100)));}, cancel() {cancelled = true;}});
    const f = await fixture(() => new Response(stream));
    await assert.rejects(f.client.request('/customers/me/data-export/jobs', {maxResponseBytes: 150}), {code: 'RESPONSE_LIMIT'});
    assert.equal(cancelled, true); assert.equal(f.calls[0].init.maxResponseBytes, undefined);
});
test('bounded JSON transport keeps split UTF-8 characters and refresh semantics', async () => {
    const bytes = new TextEncoder().encode('{"name":"Żółć"}');
    const stream = new ReadableStream({start(controller) {for (const byte of bytes) controller.enqueue(new Uint8Array([byte])); controller.close();}});
    const f = await fixture(() => new Response(stream));
    assert.deepEqual(await f.client.request('/customers/me/data-export/jobs', {maxResponseBytes: bytes.length}), {name: 'Żółć'});
});
function deferred() {
    let resolve;
    const promise = new Promise(done => {resolve = done;});
    return {promise, resolve};
}
test('logout fences late JSON and binary responses even when fetch ignores abort',async()=>{
    for(const binary of [false,true]){
        const pending=deferred();const f=await fixture(()=>pending.promise);
        const result=binary?f.client.blob('/invoices/1/pdf'):f.client.request('/customers/1');
        const rejected=assert.rejects(result,{name:'AbortError'});
        f.client.invalidate();pending.resolve(binary?new Response('private pdf'):reply({email:'private@example.test'}));await rejected;
        assert.equal(f.calls[0].init.signal.aborted,true);
    }
});
async function fixture(handler, options = {}) {
    const {createStoreClient} = await modulePromise;
    const calls = [];
    let token = 'old-fixture', refreshes = 0;
    const client = createStoreClient({baseUrl: 'http://127.0.0.1:18080/api', getAccessToken: () => token,
        canRefresh: path => !['/customers/login', '/customers/register', '/customers/refresh', '/customers/logout'].includes(path),
        refreshSession: async () => {refreshes++; token = 'new-fixture'; return true;},
        fetch: async (url, init) => {calls.push({url, init}); return handler(url, init, calls.length - 1);}, ...options});
    return {client, calls, token(value) {token = value;}, refreshes: () => refreshes};
}

test('requests use live session headers and do not mutate Headers or command options', async () => {
    const f = await fixture(() => reply({ok: true}));
    const headers = new Headers({'Idempotency-Key': 'immutable-operation', Authorization: 'caller-value'});
    const options = {method: 'POST', body: '{"fixture":1}', headers, credentials: 'omit', redirect: 'follow', mode: 'no-cors'};
    await f.client.request('/orders', options);
    assert.equal(f.calls[0].init.headers.get('Authorization'), 'Bearer old-fixture');
    assert.equal(f.calls[0].init.headers.get('Content-Type'), 'application/json');
    assert.equal(f.calls[0].init.headers.get('Idempotency-Key'), 'immutable-operation');
    assert.equal(f.calls[0].init.credentials, 'include'); assert.equal(f.calls[0].init.redirect, 'error'); assert.equal(f.calls[0].init.mode, 'cors');
    assert.equal(headers.get('Content-Type'), null); assert.equal(headers.get('Authorization'), 'caller-value');
    f.token('changed-fixture'); await f.client.request('/orders');
    assert.equal(f.calls[1].init.headers.get('Authorization'), 'Bearer changed-fixture');
});

test('anonymous reads omit bearer credentials and cookies but preserve explicit guest capability', async () => {
    const f = await fixture(() => reply([]));
    await f.client.request('/orders/1', {anonymous: true, paged: true, timeoutMs: 1500,
        credentials: 'include', headers: [['authorization', 'caller-value'], ['X-Order-Access-Token', 'guest-fixture']]});
    const {init} = f.calls[0];
    assert.equal(init.credentials, 'omit'); assert.equal(init.headers.has('Authorization'), false);
    assert.equal(init.headers.get('X-Order-Access-Token'), 'guest-fixture');
    for (const key of ['anonymous', 'paged', 'timeoutMs']) assert.equal(key in init, false);
});

test('untrusted addresses and paths escaping the configured API are blocked before fetch', async () => {
    const {createStoreClient} = await modulePromise;
    for (const baseUrl of ['file:///api', 'https://user:pass@example.test/api', 'https://example.test/api?token=x', 'https://example.test/api#x']) {
        assert.throws(() => createStoreClient({baseUrl}), /Invalid MiniTech API address/);
    }
    const f = await fixture(() => reply({}));
    for (const candidate of ['https://attacker.test/api', '//attacker.test/api', '/../../private', '/%2e%2e/private', '/orders#token', 'orders']) {
        await assert.rejects(f.client.request(candidate), /Invalid MiniTech API path/);
    }
    assert.equal(f.calls.length, 0);
});

test('JSON, empty bodies and plain downstream messages retain their original meaning', async () => {
    const f = await fixture((url, init, index) => [new Response(null, {status: 204}), new Response('plain success'), new Response('Gateway unavailable', {status: 502}), reply({})][index]);
    assert.equal(await f.client.request('/fixture'), null);
    assert.equal(await f.client.request('/fixture'), 'plain success');
    await assert.rejects(f.client.request('/fixture'), {message: 'Gateway unavailable', status: 502, path: '/fixture'});
});

test('business error metadata and fallback HTTP messages survive response decoding', async () => {
    const f = await fixture((url, init, index) => index === 0 ? reply({message: 'Conflict', details: {code: 'INTENT_CONFLICT', orderId: 91}}, 409) : new Response('', {status: 503}));
    await assert.rejects(f.client.request('/orders'), {message: 'Conflict', code: 'INTENT_CONFLICT', orderId: 91, status: 409, path: '/orders'});
    await assert.rejects(f.client.request('/orders'), {message: 'HTTP 503', status: 503});
});

test('paged results preserve opaque cursors and require a literal true header', async () => {
    const f = await fixture((url, init, index) => reply([{id: 1}], 200, index === 0 ? {'X-Has-More': 'true', 'X-Next-Cursor': '9223372036854775806'} : {'X-Has-More': 'TRUE'}));
    assert.deepEqual(await f.client.request('/orders', {paged: true}), {items: [{id: 1}], hasMore: true, nextCursor: '9223372036854775806'});
    assert.deepEqual(await f.client.request('/orders', {paged: true}), {items: [{id: 1}], hasMore: false, nextCursor: '0'});
});

test('parallel unauthorized requests share one refresh and retry using the fresh token', async () => {
    const held = deferred(), started = deferred();
    let token = 'old', refreshes = 0;
    const f = await fixture((url, init) => init.headers.get('Authorization') === 'Bearer old' ? reply({message: 'Expired'}, 401) : reply({ok: true}), {
        getAccessToken: () => token,
        refreshSession: async () => {refreshes++; started.resolve(); await held.promise; token = 'new'; return true;}
    });
    const a = f.client.request('/orders/1'), b = f.client.request('/orders/2');
    await started.promise;
    held.resolve();
    assert.deepEqual(await Promise.all([a, b]), [{ok: true}, {ok: true}]);
    assert.equal(refreshes, 1); assert.equal(f.calls.length, 4);
    assert.equal(f.calls.filter(call => call.init.headers.get('Authorization') === 'Bearer new').length, 2);
});

test('a late rejection of an old token uses the current token without another refresh', async () => {
    const held = deferred();
    const f = await fixture((url, init, index) => index === 0 ? held.promise : reply({ok: true}));
    const pending = f.client.request('/orders');
    f.token('already-renewed'); held.resolve(reply({message: 'Old token rejected'}, 401));
    assert.deepEqual(await pending, {ok: true});
    assert.equal(f.refreshes(), 0); assert.equal(f.calls[1].init.headers.get('Authorization'), 'Bearer already-renewed');
});

test('a writing request keeps its immutable command when retried after one authenticated refresh', async () => {
    const f = await fixture((url, init, index) => index === 0 ? reply({}, 401) : reply({id: 91}));
    const body = '{"customerId":17,"items":[{"productId":1,"quantity":2}]}';
    await f.client.request('/orders', {method: 'POST', body, headers: {'Idempotency-Key': 'operation-1', 'X-Order-Access-Token': 'guest-1'}});
    for (const call of f.calls) {
        assert.equal(call.init.body, body); assert.equal(call.init.method, 'POST');
        assert.equal(call.init.headers.get('Idempotency-Key'), 'operation-1');
        assert.equal(call.init.headers.get('X-Order-Access-Token'), 'guest-1');
    }
    assert.equal(f.refreshes(), 1); assert.equal(f.calls.length, 2);
});

test('a repeated 401 ends after one retry and a failed refresh never retries the operation', async () => {
    const f = await fixture(() => reply({message: 'Denied'}, 401));
    await assert.rejects(f.client.request('/orders'), {status: 401});
    assert.equal(f.refreshes(), 1); assert.equal(f.calls.length, 2);
    const failed = await fixture(() => reply({}, 401), {refreshSession: async () => false});
    await assert.rejects(failed.client.request('/orders'), {status: 401}); assert.equal(failed.calls.length, 1);
});

test('network failures and non-401 failures never retry a command', async () => {
    for (const status of [403, 409, 429, 500, 503, null]) {
        const f = await fixture(() => {if (status === null) throw Error('Lost reply'); return reply({}, status);});
        await assert.rejects(f.client.request('/orders', {method: 'POST', body: '{}'}));
        assert.equal(f.refreshes(), 0); assert.equal(f.calls.length, 1);
    }
});

test('login, explicit no-retry, anonymous and MFA proof failures never refresh', async () => {
    for (const [path, options, retry, payload] of [
        ['/customers/login', {}, true, {}], ['/orders', {}, false, {}], ['/orders', {anonymous: true}, true, {}],
        ['/customers/mfa/verify', {}, true, {details: {code: 'MFA_INVALID_PROOF'}}],
        ['/customers/mfa/verify', {}, true, {code: 'MFA_INVALID_PROOF'}]
    ]) {
        const f = await fixture(() => reply(payload, 401));
        await assert.rejects(f.client.request(path, options, retry), {status: 401});
        assert.equal(f.refreshes(), 0); assert.equal(f.calls.length, 1);
    }
});

test('cancellation blocks sending and prevents a retry while refresh is pending', async () => {
    const controller = new AbortController(), held = deferred(), started = deferred();
    const f = await fixture(() => reply({}, 401), {refreshSession: async () => {started.resolve(); await held.promise; return true;}});
    const pending = f.client.request('/orders', {signal: controller.signal});
    await started.promise; controller.abort(); held.resolve();
    await assert.rejects(pending, {name: 'AbortError'}); assert.equal(f.calls.length, 1);
    await assert.rejects(f.client.request('/orders', {signal: controller.signal}), {name: 'AbortError'});
    assert.equal(f.calls.length, 1);
});

test('the effective deadline signal reaches the page retry adapter and late body decoding', async () => {
    const parent = new AbortController(), effective = new AbortController();
    const options = {signal: parent.signal, timeoutMs: 1000};
    const f = await fixture(() => reply({}, 401), {retry(path, received, signal) {
        assert.equal(path, '/orders'); assert.equal(received, options); assert.equal(signal.aborted, effective.signal.aborted); assert.ok(signal instanceof AbortSignal); return 'delegated';
    }});
    assert.equal(await f.client.request('/orders', options, true, effective.signal), 'delegated');
    const held = deferred();
    const late = await fixture(() => ({ok: true, status: 200, text: () => held.promise}));
    const pending = late.client.request('/orders', {signal: effective.signal});
    effective.abort(); held.resolve('{"private":"late"}');
    await assert.rejects(pending, {name: 'AbortError'});
});

test('binary success preserves bytes without invoking the text or JSON decoder', async () => {
    const bytes = Uint8Array.from([37, 80, 68, 70, 0, 255]);
    const body = new Blob([bytes], {type: 'application/pdf'});
    const f = await fixture(() => ({ok: true, status: 200, blob: async () => body, text() {throw Error('Must not decode binary text');}}));
    const blob = await f.client.blob('/invoices/order/91/pdf');
    assert.equal(blob, body); assert.deepEqual(new Uint8Array(await blob.arrayBuffer()), bytes);
});

test('a binary 401 refreshes once and retains binary decoding without using the JSON retry adapter', async () => {
    const f = await fixture((url, init, index) => index === 0 ? reply({}, 401) : new Response('PDF fixture', {headers: {'Content-Type': 'application/pdf'}}), {
        retry() {throw Error('JSON adapter must not receive binary responses');}
    });
    const blob = await f.client.blob('/invoices/order/91/pdf');
    assert.equal(blob.type, 'application/pdf'); assert.equal(await blob.text(), 'PDF fixture');
    assert.equal(f.refreshes(), 1); assert.equal(f.calls[1].init.headers.get('Authorization'), 'Bearer new-fixture');
});

test('binary errors preserve backend messages and anonymous invoices cannot trigger a customer refresh', async () => {
    const f = await fixture(() => reply({message: 'Invoice denied', code: 'INVOICE_DENIED'}, 401));
    await assert.rejects(f.client.blob('/invoices/order/91/pdf', {anonymous: true}), {message: 'Invoice denied', status: 401, code: 'INVOICE_DENIED'});
    assert.equal(f.refreshes(), 0); assert.equal(f.calls[0].init.credentials, 'omit');
    assert.equal(f.calls[0].init.headers.has('Authorization'), false);
});

test('JSON and binary requests share the same pending refresh operation', async () => {
    const held = deferred(), started = deferred();
    let token = 'old', refreshes = 0;
    const f = await fixture((url, init) => init.headers.get('Authorization') === 'Bearer old' ? reply({}, 401)
        : url.endsWith('/pdf') ? new Response('PDF fixture') : reply({ok: true}), {
        getAccessToken: () => token,
        refreshSession: async () => {refreshes++; started.resolve(); await held.promise; token = 'new'; return true;}
    });
    const a = f.client.request('/orders/91'), b = f.client.blob('/invoices/order/91/pdf');
    await started.promise; held.resolve();
    const [data, blob] = await Promise.all([a, b]);
    assert.deepEqual(data, {ok: true}); assert.equal(await blob.text(), 'PDF fixture');
    assert.equal(refreshes, 1); assert.equal(f.calls.length, 4);
});

test('a late binary body after cancellation cannot reach the download and a network failure is not retried', async () => {
    const held = deferred(), controller = new AbortController();
    const f = await fixture(() => ({ok: true, status: 200, blob: () => held.promise}));
    const pending = f.client.blob('/invoices/order/91/pdf', {signal: controller.signal});
    controller.abort(); held.resolve(new Blob(['late']));
    await assert.rejects(pending, {name: 'AbortError'});
    const failed = await fixture(() => {throw Error('Network unavailable');});
    await assert.rejects(failed.client.blob('/invoices/order/91/pdf'), /Network unavailable/);
    assert.equal(failed.calls.length, 1); assert.equal(failed.refreshes(), 0);
});
