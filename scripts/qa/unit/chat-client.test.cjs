const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../../frontend/chat-client.js'), 'utf8');
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const A = 'A'.repeat(43), B = 'B'.repeat(43);
async function fixture() {
    const {createChatClient} = await modulePromise;
    const calls = [];
    const client = createChatClient({request(path, options, retry) {
        let resolve, reject;
        const promise = new Promise((done, fail) => {resolve = done; reject = fail;});
        calls.push({path, options, retry, resolve, reject}); return promise;
    }});
    return {client, calls};
}

test('create and follow-up preserve payloads and capabilities without enabling refresh or retry', async () => {
    const f = await fixture();
    const message = {customerName: 'Fixture', customerEmail: 'fixture@example.test', message: 'Question'};
    const a = f.client.create(message, A); f.calls[0].resolve({id: 77}); await a;
    const intent = {message: 'Follow-up', requestId: 'immutable-fixture-key'};
    const b = f.client.reply(77, intent, A); f.calls[1].resolve({id: 77}); await b;
    assert.equal(f.calls[0].path, '/chat/messages'); assert.equal(f.calls[1].path, '/chat/messages/77/entries');
    assert.deepEqual(JSON.parse(f.calls[0].options.body), message); assert.deepEqual(JSON.parse(f.calls[1].options.body), intent);
    for (const call of f.calls) {
        assert.equal(call.options.method, 'POST'); assert.equal(call.options.anonymous, true); assert.equal(call.retry, false);
        assert.equal(call.options.headers.get('X-Chat-Access-Token'), A); assert.equal(call.path.includes(A), false);
    }
    assert.equal(intent.requestId, 'immutable-fixture-key');
});

test('identical concurrent reads share a promise, but completed results are not cached', async () => {
    const f = await fixture();
    const a = f.client.conversation(77, A), b = f.client.conversation('77', A);
    assert.equal(a, b); await Promise.resolve(); assert.equal(f.calls.length, 1);
    f.calls[0].resolve({id: 77}); await a;
    const c = f.client.conversation(77, A); await Promise.resolve(); assert.equal(f.calls.length, 2);
    f.calls[1].resolve({id: 77}); await c;
});

test('different cursors, conversations and capabilities keep independent reads with exact text IDs', async () => {
    const f = await fixture();
    const reads = [f.client.history('9223372036854775806', A, '9223372036854775805'),
        f.client.history('9223372036854775806', A, 0), f.client.history(78, B)];
    await Promise.resolve(); assert.equal(f.calls.length, 3);
    assert.equal(f.calls[0].path, '/chat/messages/9223372036854775806/entries?before=9223372036854775805&size=50');
    assert.equal(f.calls[2].options.headers.get('X-Chat-Access-Token'), B);
    for (const call of f.calls) {assert.equal(call.options.signal.aborted, false); call.resolve({entries: []});}
    await Promise.all(reads);
});

test('cancellation aborts old reads and a late completion cannot delete a newer request', async () => {
    const f = await fixture();
    const old = f.client.conversation(77, A); await Promise.resolve(); f.client.cancelReads();
    assert.equal(f.calls[0].options.signal.aborted, true);
    const current = f.client.conversation(77, A); await Promise.resolve();
    f.calls[0].resolve({id: 77}); await assert.rejects(old, {name: 'AbortError'});
    assert.equal(f.client.conversation(77, A), current);
    f.calls[1].resolve({id: 77}); assert.deepEqual(await current, {id: 77});
});

test('cancelled queued reads never reach HTTP', async () => {
    const f = await fixture(); const pending = f.client.history(77, A); f.client.cancelReads();
    await assert.rejects(pending, {name: 'AbortError'}); assert.deepEqual(f.calls, []);
});

test('cancelling reads does not cancel or retry a submitted message', async () => {
    const f = await fixture();
    const pending = f.client.reply(77, {message: 'Follow-up', requestId: 'same-key'}, A);
    f.client.cancelReads(); assert.equal(f.calls[0].options.signal, undefined);
    f.calls[0].resolve({id: 77}); await pending;
    const failed = f.client.reply(77, {message: 'Follow-up', requestId: 'same-key'}, A);
    f.calls[1].reject(Object.assign(new Error('Lost receipt'), {status: 503}));
    await assert.rejects(failed, {status: 503}); assert.equal(f.calls.length, 2);
    assert.equal(JSON.parse(f.calls[1].options.body).requestId, 'same-key');
});

test('current error metadata survives but a cancelled old failure is converted to AbortError', async () => {
    const f = await fixture();
    const current = f.client.conversation(77, A); await Promise.resolve();
    const error = Object.assign(new Error('Not found'), {status: 404}); f.calls[0].reject(error);
    await assert.rejects(current, received => received === error);
    const old = f.client.history(77, A); await Promise.resolve(); f.client.cancelReads();
    f.calls[1].reject(new Error('Old outage')); await assert.rejects(old, {name: 'AbortError'});
});

test('invalid identifiers, cursors and capability formats never reach HTTP', async () => {
    const f = await fixture();
    for (const id of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, null, {}, '01', '../77']) {
        assert.throws(() => f.client.conversation(id, A), /Nieprawidłowy identyfikator/);
    }
    for (const token of [null, '', 'A'.repeat(42), 'A'.repeat(129), '<'.repeat(43)]) {
        assert.throws(() => f.client.history(77, token), /klucza dostępu/);
    }
    for (const before of [-1, '01', '77&size=999', Number.MAX_SAFE_INTEGER + 1]) {
        assert.throws(() => f.client.history(77, A, before), /Nieprawidłowy identyfikator/);
    }
    assert.equal(f.calls.length, 0);
});

test('a malformed or mismatched conversation response cannot switch the requested identity', async () => {
    const f = await fixture();
    const pending = f.client.conversation(77, A); await Promise.resolve(); f.calls[0].resolve({id: 78});
    await assert.rejects(pending, /innej rozmowy/);
    const created = f.client.create({message: 'Question'}, A); f.calls[1].resolve({});
    await assert.rejects(created, /Nieprawidłowy identyfikator/); assert.equal(f.calls.length, 2);
});

test('invalid history is rejected and an explicit retry can recover', async () => {
    const f = await fixture(); const bad = f.client.history(77, A); await Promise.resolve(); f.calls[0].resolve({entries: null});
    await assert.rejects(bad, /Nieprawidłowa odpowiedź historii/);
    const retry = f.client.history(77, A); await Promise.resolve(); f.calls[1].resolve({entries: [], nextCursor: null});
    assert.deepEqual(await retry, {entries: [], nextCursor: null});
});

test('separate widget instances never cancel each other even with identical conversation credentials', async () => {
    const a = await fixture(), b = await fixture();
    const first = a.client.history(77, A), second = b.client.history(77, A); await Promise.resolve(); a.client.cancelReads();
    assert.equal(a.calls[0].options.signal.aborted, true); assert.equal(b.calls[0].options.signal.aborted, false);
    a.calls[0].resolve({entries: []}); b.calls[0].resolve({entries: []});
    await assert.rejects(first, {name: 'AbortError'}); assert.deepEqual(await second, {entries: []});
});

test('the real shared store transport omits cookies and bearer credentials and does not renew a chat 401', async () => {
    const storeSource = fs.readFileSync(path.join(__dirname, '../../../frontend/store-client.js'), 'utf8');
    const {createStoreClient} = await import(`data:text/javascript;base64,${Buffer.from(storeSource).toString('base64')}`);
    const {createChatClient} = await modulePromise;
    let calls = 0, refreshes = 0;
    const store = createStoreClient({baseUrl: 'http://localhost:18080/api', getAccessToken: () => 'customer-jwt', canRefresh: () => true,
        refreshSession: async () => {refreshes++; return true;}, fetch: async (url, init) => {
            calls++; assert.equal(init.credentials, 'omit'); assert.equal(init.headers.has('Authorization'), false);
            assert.equal(init.headers.get('X-Chat-Access-Token'), A); assert.equal(init.redirect, 'error');
            assert.equal(url.includes(A), false);
            return new Response(JSON.stringify({message: 'Chat access denied', code: 'CHAT_DENIED'}), {status: 401});
        }});
    const chat = createChatClient({request: store.request});
    await assert.rejects(chat.conversation(77, A), {status: 401, code: 'CHAT_DENIED'});
    assert.equal(calls, 1); assert.equal(refreshes, 0);
});
