const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const crypto = require('node:crypto');
const modulePromise = import('data:text/javascript;base64,' + Buffer.from(fs.readFileSync('frontend/account-export-jobs.js', 'utf8')).toString('base64'));
const BASE = '/customers/me/data-export/jobs';
const copy = value => structuredClone(value);
const error = status => Object.assign(new Error('private upstream error <img onerror=alert(1)>'), {status});
const hold = () => {let resolve; const promise = new Promise(done => {resolve = done;}); return {promise, resolve};};

async function fixture(options = {}) {
    const {createAccountExportJobs} = await modulePromise;
    let clock = Date.parse('2026-10-28T12:00:00Z'), owner = 17, job = null, id = 0;
    const calls = [], clicks = [], created = [], revoked = [], states = [], timers = new Map(), steps = new Map();
    let parts = [], hook = options.hook;
    function makeJob(state = 'ACTIVE') {
        return {schemaVersion: 1, ownerId: String(owner), jobId: crypto.randomUUID(), state,
            createdAt: new Date(clock).toISOString(), expiresAt: new Date(clock + 3600000).toISOString(), updatedAt: new Date(clock).toISOString(),
            policyHash: 'a'.repeat(64), recordedBytes: 0, partCount: 0, maxBytes: 16777216, maxPartBytes: 524288, maxTasks: 4096,
            progress: {done: 0, pending: 1, failed: 0}, automaticScopeComplete: state === 'READY', complete: false};
    }
    function part(data = '{"amount":123456789.123456789,"orderId":9007199254740993,"name":"Żółć"}', sequence = 1) {
        return {sequence, scope: 'payment-for-order', parentId: '9007199254740993', detailId: '0', cursor: '0',
            bytes: Buffer.byteLength(data), sha256: crypto.createHash('sha256').update(data).digest('hex'),
            readStartedAt: new Date(clock).toISOString(), readFinishedAt: new Date(clock).toISOString(), dataJson: data};
    }
    function ready(state = 'READY', count = 1, data) {
        job ||= makeJob(state); job.state = state; job.automaticScopeComplete = state === 'READY';
        parts = Array.from({length: count}, (_, n) => part(data, n + 1));
        job.partCount = count; job.recordedBytes = parts.reduce((sum, p) => sum + p.bytes, 0);
        job.progress = {done: count, pending: state === 'ACTIVE' ? 1 : 0, failed: state === 'PARTIAL' ? 1 : 0};
    }
    const request = async (path, init) => {
        calls.push({path, init});
        if (hook) {const result = await hook(path, init); if (result !== undefined) return result;}
        if (path === BASE) {
            if (init.method === 'GET') return {schemaVersion: 1, complete: false, jobs: job ? [copy(job)] : []};
            job = makeJob(); return copy(job);
        }
        if (path.endsWith('/advance')) {
            const key = init.headers['Idempotency-Key'];
            if (!steps.has(key)) {ready(); steps.set(key, copy(job));}
            return copy(steps.get(key));
        }
        if (path.endsWith('/retry')) {job.state = 'ACTIVE'; job.progress.pending = 1; job.progress.failed = 0; return copy(job);}
        if (path.endsWith('/cancel')) {job.state = 'CANCELLED'; job.automaticScopeComplete = false; return copy(job);}
        if (path.endsWith('/manifest')) return {...copy(job), scope: 'account-export-manifest',
            sourceDefinitions: Array.from({length: 25}, (_, n) => ({scope: n ? 'other-' + n : 'payment-for-order'})),
            serviceClassification: Object.fromEntries(Array.from({length: 14}, (_, n) => ['service-' + n, {}])),
            separateProofRequired: ['chat'], manualReviewRequired: ['notes'], copyBoundaries: ['backup'], limitations: ['not a snapshot']};
        if (path.includes('/chunks?')) {
            const after = Number(new URL('http://fixture' + path).searchParams.get('after')), selected = copy(parts.slice(after, after + 3));
            return {schemaVersion: 1, jobId: job.jobId, ownerId: job.ownerId, expiresAt: job.expiresAt, parts: selected,
                nextSequence: selected.at(-1)?.sequence ?? after, hasMore: after + selected.length < parts.length, complete: false};
        }
        return copy(job);
    };
    const model = createAccountExportJobs({request, getOwner: () => owner, crypto: crypto.webcrypto, now: () => clock,
        document: {body: {append() {}}, createElement() {return {click() {clicks.push({name: this.download, href: this.href});}, remove() {}};}},
        createObjectURL(blob) {created.push(blob); return 'blob:fixture-' + created.length;}, revokeObjectURL(url) {revoked.push(url);},
        setTimeout(callback, delay) {timers.set(++id, {callback, delay}); return id;}, clearTimeout(key) {timers.delete(key);}});
    model.subscribe(state => states.push(state));
    return {model, calls, clicks, created, revoked, states, timers, makeJob, ready, part,
        job: () => job, parts: () => parts, setJob: value => {job = value;}, owner: value => {owner = value;},
        hook: value => {hook = value;}, clock: value => {clock += value;}, current: () => states.at(-1)};
}

test('prepare and resume reuse authenticated bounded transport, immutable command IDs and one-hour metadata', async () => {
    const f = await fixture(); const a = f.model.start(), b = f.model.start(); assert.equal(a, b); await a;
    assert.equal(f.current().job.state, 'READY'); assert.equal(f.current().job.complete, false);
    assert.equal(f.calls.filter(c => c.path.endsWith('/advance')).length, 1);
    for (const {init} of f.calls) {assert.equal(init.cache, 'no-store'); assert.equal(init.timeoutMs, 12000); assert.ok(init.signal instanceof AbortSignal); assert.equal(init.anonymous, undefined); assert.equal(init.maxResponseBytes, 262144);}
    assert.match(f.calls.find(c => c.init.method === 'POST').init.headers['Idempotency-Key'], /^[a-f0-9-]{36}$/);
    assert.equal(f.created.length, 0);
});
test('discover recovers an unfinished job without creating or collecting it', async () => {
    const f = await fixture(); f.setJob(f.makeJob()); await f.model.refresh();
    assert.equal(f.calls.length, 1); assert.equal(f.current().job.state, 'ACTIVE');
    await assert.rejects(f.model.start(), {code: 'EXPORT_ALREADY_ACTIVE'});
    assert.equal(f.calls.some(c => c.init.method === 'POST'), false);
    await f.model.resume(); assert.equal(f.current().job.state, 'READY');
});
test('a failed advance response reuses its idempotency key on explicit resume', async () => {
    const f = await fixture(); f.setJob(f.makeJob()); await f.model.refresh();
    let key, first = true;
    f.hook((path, init) => {if (path.endsWith('/advance')) {if (first) {first = false; key = init.headers['Idempotency-Key']; throw error(503);} assert.equal(init.headers['Idempotency-Key'], key);}});
    await assert.rejects(f.model.resume(), {status: 503}); await f.model.resume(); assert.equal(f.current().job.state, 'READY');
});
test('PARTIAL retries only on user action and never becomes false complete success', async () => {
    const f = await fixture(); f.ready('PARTIAL'); await f.model.refresh();
    assert.equal(f.current().job.state, 'PARTIAL'); assert.equal(f.calls.some(c => c.path.endsWith('/retry')), false);
    await f.model.download(); assert.equal(f.clicks[0].name, 'account-data-export-partial.json');
    assert.match(await f.created[0].text(), /"complete":false/);
    await f.model.resume(); assert.equal(f.current().job.state, 'READY'); assert.ok(f.calls.some(c => c.path.endsWith('/retry')));
});
test('lossless JSON assembly verifies SHA-256 and preserves decimal and large ID bytes', async () => {
    const f = await fixture(); f.ready(); await f.model.refresh(); await f.model.download();
    const text = await f.created[0].text(); assert.ok(text.includes(f.parts()[0].dataJson));
    assert.match(text, /123456789\.123456789/); assert.match(text, /9007199254740993/); assert.match(text, /Żółć/);
    const document = JSON.parse(text); assert.equal(document.parts.length, 1); assert.equal(document.manifest.complete, false);
    assert.equal(document.automaticScopeComplete, true); assert.equal(document.complete, false);
    assert.equal(f.clicks[0].name, 'account-data-export.json'); assert.ok(f.calls.some(c => c.path.includes('raw=true&size=3')));
});
test('a 16 MiB export is assembled by bounded three-part pages', async () => {
    const f = await fixture(); const raw = '{"text":"' + 'x'.repeat(524277) + '"}'; assert.equal(Buffer.byteLength(raw), 524288);
    f.ready('READY', 32, raw); await f.model.refresh(); await f.model.download();
    assert.equal(JSON.parse(await f.created[0].text()).parts.length, 32); assert.ok(f.created[0].size < 25165824);
    const requests = f.calls.filter(c => c.path.includes('/chunks?')); assert.equal(requests.length, 11);
    assert.ok(requests.every(c => c.init.maxResponseBytes === 4194304));
});
test('checksum mismatch, wrong sequence and unexpected source fail without creating a URL', async () => {
    for (const corrupt of [p => {p.sha256 = 'b'.repeat(64);}, p => {p.sequence = 2;}, p => {p.scope = 'raw-secrets';}, p => {p.bytes++;}]) {
        const f = await fixture(); f.ready(); corrupt(f.parts()[0]); await f.model.refresh();
        await assert.rejects(f.model.download(), {code: 'INVALID_ACCOUNT_JOB'}); assert.deepEqual(f.created, []);
    }
});
test('wrong-account jobs and oversized lists are rejected before adoption', async () => {
    for (const change of [j => {j.ownerId = '18';}, j => {j.complete = true;}, j => {j.maxBytes++;}, j => {j.expiresAt = new Date(Date.parse(j.createdAt) + 7200000).toISOString();}]) {
        const f = await fixture(); const job = f.makeJob(); change(job); f.setJob(job);
        await assert.rejects(f.model.refresh(), {code: 'INVALID_ACCOUNT_JOB'}); assert.equal(f.current().job, null);
    }
});
test('logout, same-account relogin and account switch fence late parts and retain no data', async () => {
    for (const next of [17, 18]) {
        const f = await fixture(); f.ready(); await f.model.refresh(); const held = hold();
        f.hook(async path => {if (path.includes('/chunks?')) await held.promise;});
        const pending = f.model.download(); await new Promise(setImmediate);
        f.model.cancelAll(); f.owner(next); held.resolve(); await assert.rejects(pending, {name: 'AbortError'});
        assert.deepEqual(f.created, []); assert.equal(f.current().job, null); assert.equal(f.current().jobs.length, 0);
    }
});
test('pause aborts collection locally and cancellation invokes server revocation separately', async () => {
    const f = await fixture(); f.setJob(f.makeJob()); await f.model.refresh(); const held = hold();
    f.hook(async path => {if (path.endsWith('/advance')) await held.promise;});
    const pending = f.model.resume(); await new Promise(setImmediate); f.model.pause(); held.resolve();
    await assert.rejects(pending, {name: 'AbortError'}); assert.equal(f.current().phase, 'paused');
    assert.equal(f.calls.some(c => c.path.endsWith('/cancel')), false);
    await f.model.cancel(); assert.equal(f.current().job.state, 'CANCELLED');
});
test('one-hour timer drops metadata and revokes a downloaded object URL', async () => {
    const f = await fixture(); f.ready(); await f.model.refresh(); await f.model.download(); assert.equal(f.revoked.length, 0);
    f.clock(3600000); const expiry = [...f.timers.values()].find(timer => timer.delay === 3600000); expiry.callback();
    assert.equal(f.current().phase, 'expired'); assert.equal(f.current().job, null); assert.equal(f.revoked.length, 1);
});
test('expiry or cancellation before final confirmation prevents a late download', async () => {
    for (const mode of ['expiry', 'cancel']) {
        const f = await fixture(); f.ready(); await f.model.refresh();
        f.hook(path => {if (path.includes('/chunks?')) {if (mode === 'expiry') throw error(410); f.job().state = 'CANCELLED'; f.job().automaticScopeComplete = false;}});
        await assert.rejects(f.model.download()); assert.deepEqual(f.created, []);
        if (mode === 'expiry') assert.equal(f.current().phase, 'expired');
    }
});
test('manifest download works for failed scope and does not fetch parts', async () => {
    const f = await fixture(); f.ready('LIMIT_REACHED'); await f.model.refresh(); await f.model.downloadManifest();
    assert.equal(f.clicks[0].name, 'account-export-manifest.json'); assert.equal(f.calls.some(c => c.path.includes('/chunks?')), false);
});
test('logout revokes a pending object URL immediately and all calls require a safe account owner', async () => {
    const f = await fixture(); f.ready(); await f.model.refresh(); await f.model.download(); f.model.cancelAll(); assert.equal(f.revoked.length, 1);
    f.calls.length = 0;
    for (const owner of [null, 0, -1, '01', {}, Number.MAX_SAFE_INTEGER + 1]) {f.owner(owner); await assert.rejects(f.model.refresh(), {code: 'SESSION_EXPIRED'});}
    assert.equal(f.calls.length, 0);
});
test('a rejected refresh prompts sign-in while an ordinary logout stays a cancellation', async () => {
    const f = await fixture(); f.ready(); await f.model.refresh(); const held = hold();
    f.hook(async path => {if (path.includes('/chunks?')) {await held.promise; throw error(401);}});
    const pending = f.model.download(); await new Promise(setImmediate); f.owner(null); f.model.cancelAll('session-expired'); held.resolve();
    await assert.rejects(pending, {code: 'SESSION_EXPIRED'}); assert.equal(f.current().job, null); assert.deepEqual(f.created, []);
});
