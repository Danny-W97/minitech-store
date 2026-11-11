const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const source = fs.readFileSync('frontend/account-export.js', 'utf8');
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const body = (id = 17, extra = {}) => new Blob([JSON.stringify({schemaVersion: 1,
    scope: 'customer-profile-and-security-summary', complete: false,
    profile: {id, lastName: 'Żółć'}, omissions: [], ...extra})], {type: 'application/json'});

async function fixture(options = {}) {
    const {createAccountExportDownloads} = await modulePromise;
    const calls = [], created = [], clicked = [], revoked = [], timers = [], states = [];
    let owner = 17;
    const downloads = createAccountExportDownloads({getOwner: () => owner,
        requestBlob(path, options) {
            let resolve, reject;
            const promise = new Promise((yes, no) => {resolve = yes; reject = no;});
            calls.push({path, options, resolve, reject}); return promise;
        },
        document: {createElement() {return {click() {clicked.push({href: this.href, filename: this.download});}};}},
        createObjectURL(blob) {created.push(blob); return 'blob:fixture-' + created.length;},
        revokeObjectURL(url) {revoked.push(url);}, setTimeout(callback, delay) {timers.push({callback, delay});}, ...options});
    downloads.subscribe(value => states.push(value));
    return {downloads, calls, created, clicked, revoked, timers, states, owner(value) {owner = value;}};
}

test('profile download keeps original UTF-8 bytes and fixed filename with shared authenticated transport', async () => {
    const f = await fixture(), blob = body();
    const pending = f.downloads.download(); await Promise.resolve();
    assert.equal(f.calls[0].path, '/customers/me/data-export');
    assert.equal(f.calls[0].options.headers.Accept, 'application/json');
    assert.equal(f.calls[0].options.cache, 'no-store');
    assert.equal(f.calls[0].options.anonymous, undefined);
    f.calls[0].resolve(blob); await pending;
    assert.equal(f.created[0], blob);
    assert.deepEqual(f.clicked, [{href: 'blob:fixture-1', filename: 'customer-profile-export.json'}]);
    assert.equal(f.timers[0].delay, 1000); f.timers[0].callback();
    assert.deepEqual(f.revoked, ['blob:fixture-1']);
    assert.deepEqual(f.states.map(s => s.phase), ['idle', 'busy', 'done']);
});

test('concurrent export clicks share one request and download', async () => {
    const f = await fixture(); const a = f.downloads.download(), b = f.downloads.download();
    assert.equal(a, b); await Promise.resolve(); assert.equal(f.calls.length, 1);
    f.calls[0].resolve(body()); await Promise.all([a, b]); assert.equal(f.clicked.length, 1);
});

test('missing or unsafe owner never sends a request', async () => {
    const f = await fixture();
    for (const owner of [null, undefined]) {
        f.owner(owner); await assert.rejects(f.downloads.download(), {code: 'SESSION_EXPIRED'});
    }
    for (const owner of [{}, 0, -1, 1.1, Number.MAX_SAFE_INTEGER + 1, '01', '../17']) {
        f.owner(owner); await assert.rejects(f.downloads.download(), {code: 'INVALID_ACCOUNT_OWNER'});
        assert.equal(f.states.at(-1).phase, 'error');
    }
    assert.deepEqual(f.calls, []);
});

test('HTTP failure permits a manual retry without downloading an error file', async () => {
    const f = await fixture(); const a = f.downloads.download(); await Promise.resolve();
    const error = Object.assign(new Error('<img onerror=alert(1)>'), {status: 503});
    f.calls[0].reject(error); await assert.rejects(a, e => e === error);
    assert.deepEqual(f.states.at(-1), {phase: 'error', code: undefined, status: 503});
    assert.deepEqual(f.created, []);
    const b = f.downloads.download(); await Promise.resolve(); f.calls[1].resolve(body()); await b;
    assert.equal(f.clicked.length, 1);
});

test('cancellation before queued request starts sends nothing', async () => {
    const f = await fixture(); const pending = f.downloads.download(); f.downloads.cancelAll();
    await assert.rejects(pending, {name: 'AbortError'}); assert.deepEqual(f.calls, []);
});

test('logout and login to the same account fence the old result and its cleanup', async () => {
    const f = await fixture(); const old = f.downloads.download(); await Promise.resolve();
    f.downloads.cancelAll(); assert.equal(f.calls[0].options.signal.aborted, true);
    f.owner(null); f.owner(17); const current = f.downloads.download(); await Promise.resolve();
    f.calls[0].resolve(body()); await assert.rejects(old, {name: 'AbortError'});
    assert.equal(f.downloads.download(), current); assert.deepEqual(f.created, []);
    f.calls[1].resolve(body()); await current; assert.equal(f.clicked.length, 1);
});

test('account switch fences old result even when caller forgot explicit cancellation', async () => {
    const f = await fixture(); const old = f.downloads.download(); await Promise.resolve();
    f.owner(18); const current = f.downloads.download(); await Promise.resolve();
    f.calls[0].resolve(body()); await assert.rejects(old, {name: 'AbortError'});
    f.calls[1].resolve(body(18)); await current; assert.equal(f.clicked.length, 1);
});

test('stale owner after an asynchronous body read never creates an object URL', async () => {
    const f = await fixture(); const old = f.downloads.download(); await Promise.resolve();
    const blob = body(); let release;
    blob.text = () => new Promise(resolve => {release = resolve;});
    f.calls[0].resolve(blob); await Promise.resolve(); f.owner(18);
    release(await body().text()); await assert.rejects(old, {name: 'AbortError'}); assert.deepEqual(f.created, []);
});

test('wrong-account, unknown schema, HTML, invalid JSON and oversized files fail without download', async () => {
    for (const blob of [body(18), body(17, {complete: true}), body(17, {schemaVersion: 2}), body(17, {scope: 'other'}),
        new Blob(['<html>error</html>'], {type: 'text/html'}), new Blob(['{'], {type: 'application/json'}),
        new Blob(['null'], {type: 'application/json'}), new Blob([' '.repeat(65537)], {type: 'application/json'})]) {
        const f = await fixture(); const pending = f.downloads.download(); await Promise.resolve();
        f.calls[0].resolve(blob); await assert.rejects(pending, {code: 'INVALID_ACCOUNT_EXPORT'});
        assert.deepEqual(f.created, []); assert.deepEqual(f.clicked, []);
    }
});

test('object URL is revoked even when click or scheduling fails', async () => {
    for (const failed of ['click', 'timer']) {
        const f = await fixture({document: {createElement() {return {click() {if (failed === 'click') throw Error('Click failed');}};}},
            setTimeout() {throw Error('Timer failed');}});
        const pending = f.downloads.download(); await Promise.resolve(); f.calls[0].resolve(body());
        if (failed === 'click') await assert.rejects(pending, /Click failed/); else await pending;
        assert.deepEqual(f.revoked, ['blob:fixture-1']);
    }
});

test('only rejected refresh marks cancellation as a sign-in error', async () => {
    for (const reason of ['cancelled', 'session-expired']) {
        const f = await fixture(); const pending = f.downloads.download(); await Promise.resolve();
        f.owner(null); f.downloads.cancelAll(reason); f.calls[0].reject(Object.assign(new Error('Unauthorized'), {status: 401}));
        await assert.rejects(pending, reason === 'session-expired' ? {code: 'SESSION_EXPIRED'} : {name: 'AbortError'});
        assert.deepEqual(f.created, []); assert.equal(f.states.at(-1).phase, 'idle');
    }
});

test('UI shows translated busy/error/retry state and never renders server markup', async () => {
    const {bindAccountExportUi} = await modulePromise;
    const f = await fixture(); let language = 'pl';
    const button = new EventTarget(); button.setAttribute = (name, value) => {button[name] = value;};
    const status = {}, hint = {}, events = new EventTarget();
    const detach = bindAccountExportUi({downloads: f.downloads, button, status, hint, events,
        translate: key => `${language}:${key}`, getOwner: () => 17, onSignInRequired() {throw Error('Unexpected login prompt');}});
    const pending = f.downloads.download(); await Promise.resolve(); assert.equal(button.disabled, true);
    f.calls[0].reject(Object.assign(new Error('<img onerror=alert(1)>'), {status: 503})); await assert.rejects(pending);
    assert.equal(button.disabled, false); assert.equal(status.textContent, 'pl:accountExportFailed');
    language = 'en'; events.dispatchEvent(new Event('minitech:language-changed'));
    assert.equal(status.textContent, 'en:accountExportFailed'); assert.equal(hint.textContent, 'en:accountExportHint');
    detach();
});
