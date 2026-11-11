import {test, expect, CUSTOMER} from '../../fixtures/store.fixture';
import type {Page, Download} from '@playwright/test';
import {createHash, randomUUID} from 'node:crypto';
import AxeBuilder from '@axe-core/playwright';

const BASE = '/customers/me/data-export/jobs';
const ROOT = '#auth-account-export';
const action = (page: Page, name: string) => page.locator(`${ROOT} [data-export-action="${name}"]`);
const status = (page: Page) => page.locator(`${ROOT} [data-export-status]`);
const deferred = () => {let resolve!: () => void; const promise = new Promise<void>(yes => {resolve = yes;}); return {promise, resolve};};
async function bytes(download: Download) {const stream = await download.createReadStream(); if (!stream) throw Error('Missing export'); const chunks: Buffer[] = []; for await (const c of stream) chunks.push(Buffer.from(c)); return Buffer.concat(chunks);}

function job(state = 'ACTIVE', owner = CUSTOMER.id) {
  const created = Date.now();
  return {schemaVersion: 1, ownerId: String(owner), jobId: randomUUID(), state,
    createdAt: new Date(created).toISOString(), expiresAt: new Date(created + 3600000).toISOString(), updatedAt: new Date(created).toISOString(),
    policyHash: 'a'.repeat(64), recordedBytes: 0, partCount: 0, maxBytes: 16777216, maxPartBytes: 524288, maxTasks: 4096,
    progress: {done: 0, pending: 1, failed: 0}, automaticScopeComplete: state === 'READY', complete: false};
}
async function fixture(page: Page, initial: ReturnType<typeof job> | null = null, content?: string[]) {
  let current = initial, rows: any[] = [];
  const calls: {path: string, method: string, authorization?: string, key?: string}[] = [];
  let advanceHook: (() => Promise<void>) | null = null, chunkHook: (() => Promise<void>) | null = null, fail = false, invalid = false;
  function ready(state = 'READY') {
    current ||= job(state); current.state = state; current.automaticScopeComplete = state === 'READY';
    rows = (content || ['{"orderId":9007199254740993,"amount":123456789.123456789,"name":"Żółć"}']).map((raw, n) => ({
      sequence: n + 1, scope: 'payment-for-order', parentId: '9007199254740993', detailId: '0', cursor: '0',
      dataJson: raw, bytes: Buffer.byteLength(raw), sha256: createHash('sha256').update(raw).digest('hex'),
      readStartedAt: current!.createdAt, readFinishedAt: current!.createdAt}));
    current.partCount = rows.length; current.recordedBytes = rows.reduce((sum, row) => sum + row.bytes, 0);
    current.progress = {done: rows.length, pending: 0, failed: state === 'PARTIAL' ? 1 : 0};
  }
  if (initial && initial.state !== 'ACTIVE') ready(initial.state);
  await page.route('**/customers/me/data-export/jobs**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace(/^\/api(?:\/v1)?/, ''), method = request.method();
    calls.push({path, method, authorization: request.headers().authorization, key: request.headers()['idempotency-key']});
    const json = (body: unknown, code = 200) => route.fulfill({status: code, contentType: 'application/json', body: JSON.stringify(body)});
    if (path === BASE && method === 'GET') return json({schemaVersion: 1, complete: false, jobs: current ? [current] : []});
    if (path === BASE && method === 'POST') {current = job(); return json(current);}
    if (path.endsWith('/advance')) {
      if (advanceHook) await advanceHook();
      if (fail) {fail = false; ready('PARTIAL');} else ready();
      return json(current);
    }
    if (path.endsWith('/retry')) {current!.state = 'ACTIVE'; current!.automaticScopeComplete = false; current!.progress.pending = 1; return json(current);}
    if (path.endsWith('/cancel')) {current!.state = 'CANCELLED'; current!.automaticScopeComplete = false; return json(current);}
    if (path.endsWith('/manifest')) return json({...current, scope: 'account-export-manifest',
      sourceDefinitions: Array.from({length: 25}, (_, n) => ({scope: n ? 'scope-' + n : 'payment-for-order'})),
      serviceClassification: Object.fromEntries(Array.from({length: 14}, (_, n) => ['service-' + n, {}])),
      separateProofRequired: ['chat'], manualReviewRequired: ['notes'], copyBoundaries: ['backup'], limitations: ['not a snapshot']});
    if (path.endsWith('/chunks')) {
      if (chunkHook) await chunkHook();
      const after = Number(url.searchParams.get('after')), selected = structuredClone(rows.slice(after, after + 3));
      if (invalid && selected.length) selected[0].sha256 = 'b'.repeat(64);
      return json({schemaVersion: 1, ownerId: current!.ownerId, jobId: current!.jobId, expiresAt: current!.expiresAt,
        parts: selected, nextSequence: selected.at(-1)?.sequence ?? after, hasMore: after + selected.length < rows.length, complete: false});
    }
    return json(current);
  });
  return {calls, current: () => current!, ready, fail: () => {fail = true;}, invalid: () => {invalid = true;},
    advance: (hook: () => Promise<void>) => {advanceHook = hook;}, chunks: (hook: () => Promise<void>) => {chunkHook = hook;}};
}
async function open(page: Page) {
  await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
  await page.goto('/index.html'); await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
  await page.locator('#auth-toggle').click(); await page.locator('#account-menu-profile').click();
  await expect(action(page, 'refresh')).toBeEnabled();
}

test('prepare, download lossless data and manifest, clean URL, preserve unsaved profile and local storage', async ({page, mockStore}) => {
  mockStore.sessionActive = true; const f = await fixture(page); let saved = 0;
  page.on('request', r => {if (r.method() === 'PUT' && r.url().endsWith('/profile')) saved++;});
  await page.addInitScript(() => {
    const events: string[] = []; (window as any).jobUrlEvents = events;
    const create = URL.createObjectURL.bind(URL), revoke = URL.revokeObjectURL.bind(URL);
    URL.createObjectURL = blob => {events.push('create'); return create(blob);}; URL.revokeObjectURL = url => {events.push('revoke'); revoke(url);};
  });
  await open(page); await page.locator('#auth-profile-first-name').fill('Niezapisana zmiana');
  await action(page, 'start').click(); await expect(status(page)).toContainText('Zakres automatyczny ukończony');
  expect(f.calls.filter(c => c.path.endsWith('/advance'))).toHaveLength(1);
  expect(f.calls.every(c => c.authorization === 'Bearer playwright.header.signature')).toBe(true);
  expect(f.calls.filter(c => c.method === 'POST').every(c => /^[a-f0-9-]{36}$/.test(c.key || ''))).toBe(true);
  await action(page, 'download').focus(); const downloading = page.waitForEvent('download'); await page.keyboard.press('Enter');
  const file = await downloading, raw = (await bytes(file)).toString('utf8');
  expect(file.suggestedFilename()).toBe('account-data-export.json'); expect(raw).toContain('123456789.123456789'); expect(raw).toContain('9007199254740993');
  const doc = JSON.parse(raw); expect(doc.complete).toBe(false); expect(doc.automaticScopeComplete).toBe(true); expect(doc.manifest.separateProofRequired).toEqual(['chat']);
  await expect.poll(() => page.evaluate(() => (window as any).jobUrlEvents)).toEqual(['create', 'revoke']);
  await expect(page.locator('#auth-profile-first-name')).toHaveValue('Niezapisana zmiana'); expect(saved).toBe(0);
  expect(await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]))).not.toContain('account-data-export');
});
test('partial failure remains explicit, supports partial download and manual retry', async ({page, mockStore}) => {
  mockStore.sessionActive = true; const f = await fixture(page); f.fail(); await open(page);
  await action(page, 'start').click(); await expect(status(page)).toContainText('Wynik częściowy');
  const downloading = page.waitForEvent('download'); await action(page, 'download').click(); const file = await downloading;
  expect(file.suggestedFilename()).toBe('account-data-export-partial.json'); expect(JSON.parse((await bytes(file)).toString()).automaticScopeComplete).toBe(false);
  await expect(action(page, 'resume')).toHaveText('Ponów niedostępne źródła'); await action(page, 'resume').click();
  await expect(status(page)).toContainText('Zakres automatyczny ukończony'); expect(f.calls.filter(c => c.path.endsWith('/retry'))).toHaveLength(1);
});
test('saved active job survives reload and pause; repeated clicks do not duplicate commands', async ({page, mockStore}) => {
  mockStore.sessionActive = true; const f = await fixture(page, job()); const held = deferred(); f.advance(() => held.promise);
  await open(page); await expect(action(page, 'resume')).toBeVisible(); await action(page, 'resume').click();
  await expect(action(page, 'pause')).toBeVisible(); await action(page, 'pause').click(); await expect(status(page)).toContainText('Wstrzymano');
  held.resolve(); await page.reload(); await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
  await page.locator('#auth-toggle').click(); await page.locator('#account-menu-profile').click(); await expect(action(page, 'refresh')).toBeEnabled();
  expect(f.calls.filter(c => c.path === BASE && c.method === 'POST')).toHaveLength(0);
  await action(page, 'refresh').click(); await expect(action(page, 'download')).toBeVisible();
});
test('cancellation revokes the result and disables further content downloads', async ({page, mockStore}) => {
  mockStore.sessionActive = true; await fixture(page, job('READY')); await open(page);
  await action(page, 'cancel').click(); await expect(status(page)).toContainText('Eksport anulowany');
  await expect(action(page, 'download')).toBeHidden(); await expect(action(page, 'manifest')).toBeHidden();
});
test('logout fences a delayed chunk before the logout response returns', async ({page, mockStore}) => {
  mockStore.sessionActive = true; const f = await fixture(page, job('READY')); const held = deferred(), logout = deferred();
  let started = false; f.chunks(async () => {started = true; await held.promise;}); const downloads: Download[] = []; page.on('download', d => downloads.push(d));
  await page.route('**/customers/logout', async r => {await logout.promise; await r.fulfill({status: 204});});
  await open(page); await action(page, 'download').click(); await expect.poll(() => started).toBe(true);
  await page.locator('#auth-close').click(); await page.locator('#auth-toggle').click(); await page.locator('#account-menu-logout').click();
  held.resolve(); logout.resolve(); await expect(page.locator('#auth-toggle')).toContainText('Zaloguj się'); expect(downloads).toEqual([]);
});
test('changed account during refresh rejects old file and clears job metadata', async ({page, mockStore}) => {
  mockStore.sessionActive = true; const f = await fixture(page, job('READY')), held = deferred(); let started = false, refreshes = 0;
  const next = {...CUSTOMER, id: 7002, email: 'second@fixture.example'}; const downloads: Download[] = []; page.on('download', d => downloads.push(d));
  await page.route('**/customers/refresh', r => r.fulfill({json: {accessToken: 'account-jobs-' + ++refreshes, customer: refreshes === 1 ? CUSTOMER : next}}));
  f.chunks(async () => {started = true; await held.promise;}); await open(page); await action(page, 'download').click(); await expect.poll(() => started).toBe(true);
  await page.evaluate(async () => {const services = await import('/store-services.js?v=20261028-a43-32'); await services.refreshSession();});
  held.resolve(); await expect(page.locator('#auth-toggle')).toContainText(next.email); await expect(action(page, 'download')).toBeHidden(); expect(downloads).toEqual([]);
});
test('expiry timer removes download controls and supports starting over', async ({page, mockStore}) => {
  mockStore.sessionActive = true; await fixture(page, job('READY')); await open(page);
  // Keep the 30-minute session active while the independent one-hour export deadline elapses.
  for (let n = 0; n < 2; n++) {await page.clock.fastForward(1200000); await page.evaluate(() => (window as any).MiniTechSession.touch());}
  await page.clock.fastForward(1201000); await expect(status(page)).toContainText('Eksport wygasł');
  await expect(action(page, 'download')).toBeHidden(); await expect(action(page, 'start')).toBeEnabled();
});
test('integrity errors are translated and never download files or display upstream markup', async ({page, mockStore}) => {
  mockStore.sessionActive = true; const f = await fixture(page, job('READY')); f.invalid(); const downloads: Download[] = []; page.on('download', d => downloads.push(d));
  await open(page); await action(page, 'download').click(); await expect(status(page)).toContainText('Nie pobrano pliku'); expect(downloads).toEqual([]);
});
test('rejected session refresh opens sign-in and does not download old account data', async ({page, mockStore}) => {
  mockStore.sessionActive = true; await fixture(page, job('READY')); let refreshes = 0;
  await page.route('**/customers/refresh', route => ++refreshes === 1
    ? route.fulfill({json: {accessToken: 'jobs-session', customer: CUSTOMER}})
    : route.fulfill({status: 401, json: {message: '<img onerror=alert(1)>'}}));
  await page.route('**/customers/me/data-export/jobs/*/chunks?**', route => route.fulfill({status: 401, json: {message: 'Expired'}}));
  const downloads: Download[] = []; page.on('download', d => downloads.push(d));
  await open(page); await action(page, 'download').click();
  await expect(page.locator('#auth-form')).toBeVisible(); await expect(page.locator('#account-status')).toContainText('kontynuować eksport');
  expect(downloads).toEqual([]);
});
test('large result uses several bounded pages and downloads the entire selected prefix', async ({page, mockStore}) => {
  mockStore.sessionActive = true; const data = '{"text":"' + 'x'.repeat(524277) + '"}'; const f = await fixture(page, job('READY'), Array(32).fill(data));
  await open(page); const downloading = page.waitForEvent('download'); await action(page, 'download').click();
  const raw = await bytes(await downloading); expect(JSON.parse(raw.toString()).parts).toHaveLength(32); expect(raw.length).toBeLessThan(25165824);
  expect(f.calls.filter(c => c.path.endsWith('/chunks'))).toHaveLength(11);
});
test('English, keyboard, mobile layout and accessibility expose the partial scope honestly', async ({page, mockStore}, testInfo) => {
  mockStore.sessionActive = true; await fixture(page, job('READY')); await open(page);
  await page.evaluate(async () => {const {i18n} = await import('/i18n.js?v=20261028-a43-32'); i18n.setLanguage('en');});
  await expect(page.locator('#account-export-title')).toHaveText('Account data export'); await expect(status(page)).toContainText('Automatic scope complete');
  await expect(page.locator(`${ROOT} [data-export-text="accountJobsRetention"]`)).toContainText('delete them yourself');
  await expect(action(page, 'download')).toHaveText('Download available result (JSON)');
  const results = await new AxeBuilder({page}).include('#auth-profile-panel').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(results.violations.filter(v => ['serious', 'critical'].includes(v.impact || ''))).toEqual([]);
  await page.locator(ROOT).screenshot({path: testInfo.outputPath('account-export-jobs.png')});
  const layout = await page.locator(ROOT).evaluate(node => ({scroll: node.scrollWidth, client: node.clientWidth,
    width: node.getBoundingClientRect().width, children: [...node.querySelectorAll('*')].map(child => ({tag: child.tagName,
      width: child.getBoundingClientRect().width, right: child.getBoundingClientRect().right - node.getBoundingClientRect().left}))}));
  expect(layout.scroll, JSON.stringify(layout)).toBeLessThanOrEqual(layout.client);
});
