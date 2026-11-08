import {test, expect, CUSTOMER} from '../../fixtures/store.fixture';
import type {Download, Page} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const exportPath = '**/customers/me/data-export';
const envelope = (customer = CUSTOMER) => ({schemaVersion: 1, scope: 'customer-profile-and-security-summary',
  complete: false, generatedAt: '2026-10-28T10:00:00Z', profile: {...customer, lastName: 'Żółć'},
  security: {totpEnabled: true, passkeyCount: 1, unusedRecoveryCodeCount: 8},
  omissions: [{scope: 'authenticationSecrets', status: 'excluded'}, {scope: 'otherServices', status: 'pending'}]});

async function profile(page: Page) {
  await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
  await page.goto('/index.html');
  await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
  await page.locator('#auth-toggle').click();
  await page.locator('#account-menu-profile').click();
  await expect(page.locator('#auth-profile-export')).toBeVisible();
}

async function downloadedBytes(download: Download) {
  const stream = await download.createReadStream();
  if (!stream) throw Error('Missing profile download');
  const chunks: Buffer[] = [];
  for await (const chunk of stream) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks);
}

test('keyboard downloads exact UTF-8 profile with partial scope and cleans the object URL', async ({page, mockStore}) => {
  mockStore.sessionActive = true;
  const bytes = Buffer.from(JSON.stringify(envelope()));
  let authorization = '', saves = 0;
  page.on('request', request => {if (request.method() === 'PUT' && request.url().endsWith('/profile')) saves++;});
  await page.addInitScript(() => {
    const events: string[] = [];
    (window as any).exportUrlEvents = events;
    const create = URL.createObjectURL.bind(URL), revoke = URL.revokeObjectURL.bind(URL);
    URL.createObjectURL = blob => {events.push('create'); return create(blob);};
    URL.revokeObjectURL = url => {events.push('revoke'); revoke(url);};
  });
  await page.route(exportPath, async route => {
    authorization = route.request().headers().authorization;
    await route.fulfill({contentType: 'application/json', body: bytes});
  });
  await profile(page);
  await expect(page.locator('#auth-profile-export-hint')).toContainText('Nie obejmuje zamówień');
  await page.locator('#auth-profile-first-name').fill('Niezapisana zmiana');
  await page.locator('#auth-profile-export').focus();
  const ready = page.waitForEvent('download'); await page.keyboard.press('Enter'); const file = await ready;
  expect(file.suggestedFilename()).toBe('customer-profile-export.json');
  expect(await downloadedBytes(file)).toEqual(bytes);
  expect(authorization).toBe('Bearer playwright.header.signature'); expect(saves).toBe(0);
  await expect(page.locator('#auth-profile-export-status')).toContainText('Rozpoczęto pobieranie');
  await expect(page.locator('#auth-profile-first-name')).toHaveValue('Niezapisana zmiana');
  await expect.poll(() => page.evaluate(() => (window as any).exportUrlEvents)).toEqual(['create', 'revoke']);
  expect(await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)])))
    .not.toContain('customer-profile-and-security-summary');
});

test('error permits retry and repeated clicks share one pending download', async ({page, mockStore}) => {
  mockStore.sessionActive = true;
  let calls = 0, release!: () => void;
  const held = new Promise<void>(resolve => {release = resolve;});
  const downloads: Download[] = []; page.on('download', value => downloads.push(value));
  await page.route(exportPath, async route => {
    if (++calls === 1) return route.fulfill({status: 503, json: {message: '<img src=x onerror="window.exportInjected=1">'}});
    await held; await route.fulfill({json: envelope()});
  });
  await profile(page);
  const button = page.locator('#auth-profile-export');
  await button.click(); await expect(page.locator('#auth-profile-export-status')).toContainText('Spróbuj ponownie');
  expect(await page.evaluate(() => (window as any).exportInjected)).toBeUndefined();
  await expect(button).toBeEnabled(); await button.click();
  await expect(button).toBeDisabled(); await expect(button).toHaveAttribute('aria-busy', 'true');
  await button.dispatchEvent('click'); await button.dispatchEvent('click');
  const ready = page.waitForEvent('download'); release(); await ready;
  expect(calls).toBe(2); expect(downloads).toHaveLength(1); await expect(button).toBeEnabled();
});

test('expired access token refreshes once and uses the renewed bearer', async ({page, mockStore}) => {
  mockStore.sessionActive = true; let refreshes = 0;
  const tokens: string[] = [];
  await page.route('**/customers/refresh', route => route.fulfill({json: {accessToken: `export-token-${++refreshes}`, customer: CUSTOMER}}));
  await page.route(exportPath, route => {
    tokens.push(route.request().headers().authorization);
    return tokens.length === 1 ? route.fulfill({status: 401, json: {message: 'Expired'}}) : route.fulfill({json: envelope()});
  });
  await profile(page);
  const ready = page.waitForEvent('download'); await page.locator('#auth-profile-export').click(); await ready;
  expect(tokens).toEqual(['Bearer export-token-1', 'Bearer export-token-2']); expect(refreshes).toBe(2);
});

test('rejected refresh opens sign-in with a clear message and no profile download', async ({page, mockStore}) => {
  mockStore.sessionActive = true; let refreshes = 0;
  const downloads: Download[] = []; page.on('download', value => downloads.push(value));
  await page.route('**/customers/refresh', route => ++refreshes === 1
    ? route.fulfill({json: {accessToken: 'export-expiring', customer: CUSTOMER}})
    : route.fulfill({status: 401, json: {message: 'Expired refresh'}}));
  await page.route(exportPath, route => route.fulfill({status: 401, json: {message: 'Expired access'}}));
  await profile(page); await page.locator('#auth-profile-export').click();
  await expect(page.locator('#auth-profile-panel')).toBeHidden();
  await expect(page.locator('#auth-email')).toBeVisible();
  await expect(page.locator('#account-status')).toContainText('Sesja wygasła');
  expect(refreshes).toBe(2); expect(downloads).toEqual([]);
});

test('logout aborts immediately while logout HTTP is pending and fences the late export', async ({page, mockStore}) => {
  mockStore.sessionActive = true;
  let release!: () => void, releaseLogout!: () => void, started = false, finished = false;
  const held = new Promise<void>(resolve => {release = resolve;});
  const logout = new Promise<void>(resolve => {releaseLogout = resolve;});
  const downloads: Download[] = []; page.on('download', value => downloads.push(value));
  await page.route(exportPath, async route => {started = true; await held; await route.fulfill({json: envelope()}); finished = true;});
  await page.route('**/customers/logout', async route => {await logout; await route.fulfill({status: 204});});
  await profile(page); await page.locator('#auth-profile-export').click(); await expect.poll(() => started).toBe(true);
  await page.locator('#auth-close').click(); await page.locator('#auth-toggle').click();
  const cancelled = page.waitForEvent('requestfailed', request => request.url().endsWith('/customers/me/data-export'));
  await page.locator('#account-menu-logout').click(); await cancelled;
  release(); await expect.poll(() => finished).toBe(true);
  releaseLogout(); await expect(page.locator('#auth-toggle')).toContainText('Zaloguj się');
  expect(downloads).toEqual([]);
});

test('refresh into another account cancels old export and the new account can download', async ({page, mockStore}) => {
  mockStore.sessionActive = true; let refreshes = 0, calls = 0, started = false, finished = false, release!: () => void;
  const next = {...CUSTOMER, id: 7002, email: 'second@fixture.example'};
  const held = new Promise<void>(resolve => {release = resolve;});
  const downloads: Download[] = []; page.on('download', value => downloads.push(value));
  await page.route('**/customers/refresh', route => route.fulfill({json: {
    accessToken: `export-account-${++refreshes}`, customer: refreshes === 1 ? CUSTOMER : next}}));
  await page.route(exportPath, async route => {
    if (++calls === 1) {started = true; await held; await route.fulfill({json: envelope()}); finished = true;}
    else await route.fulfill({json: envelope(next)});
  });
  await profile(page); await page.locator('#auth-profile-export').click(); await expect.poll(() => started).toBe(true);
  await page.evaluate(async () => {
    const services = await import('/store-services.js?v=20261028-a43-32');
    await services.refreshSession();
  });
  await expect(page.locator('#auth-toggle')).toContainText(next.email);
  release(); await expect.poll(() => finished).toBe(true); expect(downloads).toEqual([]);
  const ready = page.waitForEvent('download'); await page.locator('#auth-profile-export').click();
  const file = await ready; expect(JSON.parse((await downloadedBytes(file)).toString()).profile.id).toBe(next.id);
  expect(downloads).toHaveLength(1);
});

test('wrong-account JSON and an HTML error page never become downloaded files', async ({page, mockStore}) => {
  mockStore.sessionActive = true; let calls = 0;
  const downloads: Download[] = []; page.on('download', value => downloads.push(value));
  await page.route(exportPath, route => ++calls === 1 ? route.fulfill({json: envelope({...CUSTOMER, id: 9999})})
    : route.fulfill({contentType: 'text/html', body: '<html>Unexpected success error</html>'}));
  await profile(page);
  for (let i = 0; i < 2; i++) {
    await page.locator('#auth-profile-export').click();
    await expect(page.locator('#auth-profile-export-status')).toContainText('nieprawidłowy');
    await expect(page.locator('#auth-profile-export')).toBeEnabled();
  }
  expect(calls).toBe(2); expect(downloads).toEqual([]);
});

test('English labels and messages follow language changes without changing profile inputs', async ({page, mockStore}) => {
  mockStore.sessionActive = true;
  await page.route(exportPath, route => route.fulfill({status: 503, json: {message: 'Unavailable'}}));
  await profile(page); await page.locator('#auth-profile-first-name').fill('Draft name');
  await page.locator('#auth-profile-export').click();
  await page.evaluate(async () => {const {i18n} = await import('/i18n.js?v=20261028-a43-32'); i18n.setLanguage('en');});
  await expect(page.locator('#auth-profile-export')).toHaveText('Download profile data (JSON)');
  await expect(page.locator('#auth-profile-export-hint')).toContainText('Orders and data from other services are not included');
  await expect(page.locator('#auth-profile-export-status')).toContainText('Please try again');
  await expect(page.locator('#auth-profile-first-name')).toHaveValue('Draft name');
});

test('profile export controls have accessible name, description and no serious axe violations', async ({page, mockStore}, testInfo) => {
  mockStore.sessionActive = true;
  await profile(page);
  const button = page.getByRole('button', {name: 'Pobierz dane profilu (JSON)'});
  await expect(button).toHaveAccessibleDescription(/Nie obejmuje zamówień/);
  const results = await new AxeBuilder({page}).include('#auth-profile-panel')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(results.violations.filter(v => ['serious', 'critical'].includes(v.impact || ''))).toEqual([]);
  await page.screenshot({path: testInfo.outputPath('profile-export.png')});
});
