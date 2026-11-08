const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../../frontend/invoice-downloads.js'), 'utf8');
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const pdf = new Blob([Uint8Array.from([37, 80, 68, 70, 0, 255])], {type: 'application/pdf'});
async function fixture(options = {}) {
    const {createInvoiceDownloads} = await modulePromise;
    const calls = [], created = [], clicked = [], revoked = [], timers = [];
    let owner = 17, capability = null;
    const downloads = createInvoiceDownloads({
        requestBlob(path, options) {
            let resolve, reject;
            const promise = new Promise((done, fail) => {resolve = done; reject = fail;});
            calls.push({path, options, resolve, reject}); return promise;
        },
        getOwner: () => owner, getOrderAccess: () => capability,
        document: {createElement(tag) {assert.equal(tag, 'a'); return {click() {clicked.push({href: this.href, download: this.download});}};}},
        createObjectURL(blob) {created.push(blob); return `blob:fixture-${created.length}`;},
        revokeObjectURL(url) {revoked.push(url);},
        setTimeout(callback, delay) {timers.push({callback, delay});}, ...options
    });
    return {downloads, calls, created, clicked, revoked, timers,
        owner(value) {owner = value;}, capability(value) {capability = value;}};
}

test('invoice downloads preserve binary data, naming and customer or guest headers', async () => {
    for (const owner of [17, null]) {
        const f = await fixture(); f.owner(owner); f.capability('guest-fixture');
        const pending = f.downloads.download('91'); await Promise.resolve();
        assert.equal(f.calls[0].path, '/invoices/order/91/pdf');
        assert.equal(f.calls[0].options.headers.get('Accept'), 'application/pdf');
        assert.equal(f.calls[0].options.headers.get('X-Order-Access-Token'), 'guest-fixture');
        assert.equal(f.calls[0].options.anonymous, owner === null);
        f.calls[0].resolve(pdf); await pending;
        assert.equal(f.created[0], pdf);
        assert.deepEqual(f.clicked, [{href: 'blob:fixture-1', download: 'invoice-91.pdf'}]);
        assert.equal(f.timers[0].delay, 1000); f.timers[0].callback();
        assert.deepEqual(f.revoked, ['blob:fixture-1']);
    }
});

test('parallel clicks for one invoice share the same request and one browser download', async () => {
    const f = await fixture();
    const a = f.downloads.download(91), b = f.downloads.download('91');
    assert.equal(a, b); await Promise.resolve(); assert.equal(f.calls.length, 1);
    f.calls[0].resolve(pdf); await Promise.all([a, b]);
    assert.equal(f.clicked.length, 1);
});

test('different invoices remain independent and large identifiers stay exact strings', async () => {
    const f = await fixture();
    const a = f.downloads.download('9223372036854775806'), b = f.downloads.download(92);
    await Promise.resolve(); assert.equal(f.calls.length, 2);
    assert.equal(f.calls[0].path, '/invoices/order/9223372036854775806/pdf');
    f.calls.forEach(call => call.resolve(pdf)); await Promise.all([a, b]);
    assert.deepEqual(f.clicked.map(click => click.download), ['invoice-9223372036854775806.pdf', 'invoice-92.pdf']);
});

test('cancellation ignores a late body and its cleanup cannot remove a newer request', async () => {
    const f = await fixture();
    const old = f.downloads.download(91); await Promise.resolve();
    f.downloads.cancelAll(); assert.equal(f.calls[0].options.signal.aborted, true);
    const current = f.downloads.download(91); await Promise.resolve();
    f.calls[0].resolve(pdf); await assert.rejects(old, {name: 'AbortError'});
    assert.equal(f.created.length, 0);
    assert.equal(f.downloads.download(91), current);
    f.calls[1].resolve(pdf); await current;
    assert.equal(f.clicked.length, 1);
});

test('a changed owner or guest capability rejects a late result without creating an object URL', async () => {
    for (const change of ['owner', 'capability']) {
        const f = await fixture();
        const pending = f.downloads.download(91); await Promise.resolve();
        f[change](change === 'owner' ? 18 : 'other-capability');
        f.calls[0].resolve(pdf); await assert.rejects(pending, {name: 'AbortError'});
        assert.deepEqual(f.created, []); assert.deepEqual(f.clicked, []);
    }
});

test('an HTTP or network failure clears the pending request and allows an explicit retry', async () => {
    const f = await fixture();
    const pending = f.downloads.download(91); await Promise.resolve();
    const error = Object.assign(new Error('PDF unavailable'), {status: 503});
    f.calls[0].reject(error); await assert.rejects(pending, received => received === error);
    assert.equal(f.calls.length, 1); assert.equal(f.created.length, 0);
    const retried = f.downloads.download(91); await Promise.resolve();
    f.calls[1].resolve(pdf); await retried; assert.equal(f.clicked.length, 1);
});

test('object URLs are released when the browser download cannot be triggered', async () => {
    const f = await fixture({document: {createElement() {return {click() {throw Error('Click failed');}};}}});
    const pending = f.downloads.download(91); await Promise.resolve();
    f.calls[0].resolve(pdf); await assert.rejects(pending, /Click failed/);
    assert.equal(f.timers.length, 1); f.timers[0].callback();
    assert.deepEqual(f.revoked, ['blob:fixture-1']);
});

test('a broken scheduling hook falls back to immediate object URL cleanup', async () => {
    const f = await fixture({setTimeout() {throw Error('Timer unavailable');}});
    const pending = f.downloads.download(91); await Promise.resolve();
    f.calls[0].resolve(pdf); await pending;
    assert.equal(f.clicked.length, 1); assert.deepEqual(f.revoked, ['blob:fixture-1']);
});

test('invalid or imprecise identifiers never send a request', async () => {
    const f = await fixture();
    for (const id of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, null, undefined, {}, '0', '01', '../91', '91?token=x']) {
        await assert.rejects(f.downloads.download(id), /Nieprawidłowy identyfikator/);
    }
    assert.deepEqual(f.calls, []);
});

test('cancellation before the queued operation starts sends no request', async () => {
    const f = await fixture();
    const pending = f.downloads.download(91); f.downloads.cancelAll();
    await assert.rejects(pending, {name: 'AbortError'}); assert.deepEqual(f.calls, []);
});
