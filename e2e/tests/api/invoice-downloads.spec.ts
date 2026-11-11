import { test, expect, CUSTOMER } from '../../fixtures/store.fixture';
import type { Download, Page } from '@playwright/test';

const PDF = Buffer.concat([Buffer.from('%PDF-1.4\n'), Buffer.from([0, 128, 255]), Buffer.from('\n%%EOF')]);
const invoice = {id: 44, orderId: 9101, invoiceNumber: 'FV/44', netAmount: 100, vatAmount: 23, grossAmount: 123};

async function enableSession(page: Page) {
  await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
}

async function history(page: Page) {
  await page.route('**/orders/customer/**', route => route.fulfill({json: [{id: 9101, status: 'PAID', paymentState: 'PAID', invoiceRequested: true, totalPrice: 123, items: []}]}));
  await page.goto('/index.html');
  await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
  await page.locator('#auth-toggle').click();
  await page.locator('#account-menu-orders').click();
  await expect(page.locator('[data-invoice-order="9101"]')).toBeVisible();
}

test('an expired access token refreshes once while preserving the exact PDF bytes', async ({ page, mockStore }) => {
  mockStore.sessionActive = true; await enableSession(page);
  let refreshes = 0;
  const tokens: string[] = [];
  await page.route('**/customers/refresh', route => route.fulfill({json: {accessToken: `pdf-token-${++refreshes}`, customer: CUSTOMER}}));
  await page.route('**/invoices/order/9101', route => route.fulfill({json: invoice}));
  await page.route('**/invoices/order/9101/pdf', route => {
    tokens.push(route.request().headers().authorization);
    return tokens.length === 1 ? route.fulfill({status: 401, json: {message: 'Expired token'}})
      : route.fulfill({contentType: 'application/pdf', body: PDF});
  });
  await page.goto('/order-detail.html?id=9101');
  const ready = page.waitForEvent('download');
  await page.locator('#download-invoice').click();
  const download = await ready;
  const stream = await download.createReadStream();
  if (!stream) throw new Error('Missing downloaded file');
  const chunks: Buffer[] = [];
  for await (const chunk of stream) chunks.push(Buffer.from(chunk));
  expect(Buffer.concat(chunks)).toEqual(PDF);
  expect(download.suggestedFilename()).toBe('invoice-9101.pdf');
  expect(tokens).toEqual(['Bearer pdf-token-1', 'Bearer pdf-token-2']);
  expect(refreshes).toBe(2);
});

test('guest invoice access sends its capability without bearer credentials or cookies', async ({ page, context, baseURL, mockStore }) => {
  void mockStore;
  await context.addCookies([{name: 'invoice-fixture', value: 'must-not-be-sent', url: baseURL!}]);
  await page.addInitScript(() => {
    const base = location.port === '18070' ? `${location.protocol}//${location.hostname}:18080/api` : `${location.origin}/api`;
    sessionStorage.setItem(`minitech.order.access.${base}.9101`, 'A'.repeat(43));
  });
  let refreshes = 0;
  page.on('request', request => {if (request.url().endsWith('/customers/refresh')) refreshes++;});
  await page.route('**/orders/9101', route => route.fulfill({json: {id: 9101, status: 'PAID', paymentState: 'PAID', totalPrice: 123, items: [], deliveryAddress: {}}}));
  await page.route('**/invoices/order/9101', route => route.fulfill({json: invoice}));
  let headers: Record<string, string> = {};
  let address = '';
  await page.route('**/invoices/order/9101/pdf', async route => {
    headers = await route.request().allHeaders(); address = route.request().url();
    await route.fulfill({contentType: 'application/pdf', body: PDF});
  });
  await page.goto('/order-detail.html?id=9101');
  const ready = page.waitForEvent('download'); await page.locator('#download-invoice').click(); await ready;
  expect(headers['x-order-access-token']).toBe('A'.repeat(43));
  expect(headers.authorization).toBeUndefined(); expect(headers.cookie).toBeUndefined();
  expect(address).not.toContain('A'.repeat(43)); expect(refreshes).toBe(0);
});

test('failed session renewal shows a sign-in message instead of silently cancelling the PDF', async ({ page, mockStore }) => {
  mockStore.sessionActive = true; await enableSession(page);
  let refreshes = 0;
  const downloads: Download[] = [];
  page.on('download', download => downloads.push(download));
  await page.route('**/customers/refresh', route => ++refreshes === 1
    ? route.fulfill({json: {accessToken: 'pdf-expiring-fixture', customer: CUSTOMER}})
    : route.fulfill({status: 401, json: {message: 'Refresh session expired'}}));
  await page.route('**/invoices/order/9101', route => route.fulfill({json: invoice}));
  await page.route('**/invoices/order/9101/pdf', route => route.fulfill({status: 401, json: {message: 'Expired access token'}}));
  await page.goto('/order-detail.html?id=9101');
  await page.locator('#download-invoice').click();
  await expect(page.locator('#order-detail-status')).toContainText('Sesja wygasła');
  await expect(page.locator('#order-detail-card')).toBeHidden();
  expect(refreshes).toBe(2); expect(downloads).toEqual([]);
});

test('an invoice error permits retry and parallel retry clicks share one download', async ({ page, mockStore }) => {
  mockStore.sessionActive = true; await enableSession(page);
  let calls = 0, release!: () => void;
  const held = new Promise<void>(resolve => {release = resolve;});
  const downloads: Download[] = [];
  page.on('download', download => downloads.push(download));
  await page.route('**/invoices/order/9101/pdf', async route => {
    if (++calls === 1) {await route.fulfill({status: 503, json: {message: 'PDF fixture unavailable'}}); return;}
    await held; await route.fulfill({contentType: 'application/pdf', body: PDF});
  });
  await history(page);
  const button = page.locator('[data-invoice-order="9101"]');
  await button.click(); await expect(page.locator('#auth-orders-status')).toContainText('PDF fixture unavailable');
  await button.click(); await expect.poll(() => calls).toBe(2);
  await button.click();
  const ready = page.waitForEvent('download'); release(); await ready;
  expect(calls).toBe(2); expect(downloads).toHaveLength(1);
});

test('logout aborts a pending PDF and its late response cannot trigger a download', async ({ page, mockStore }) => {
  mockStore.sessionActive = true; await enableSession(page);
  let release!: () => void, started = false, finished = false;
  const held = new Promise<void>(resolve => {release = resolve;});
  const downloads: Download[] = [];
  page.on('download', download => downloads.push(download));
  await page.route('**/invoices/order/9101/pdf', async route => {
    started = true; await held;
    await route.fulfill({contentType: 'application/pdf', body: PDF}); finished = true;
  });
  await history(page);
  await page.locator('[data-invoice-order="9101"]').click(); await expect.poll(() => started).toBe(true);
  await page.locator('#auth-close').click(); await page.locator('#auth-toggle').click();
  const cancelled = page.waitForEvent('requestfailed', request => request.url().endsWith('/invoices/order/9101/pdf'));
  await page.locator('#account-menu-logout').click(); await cancelled;
  await expect(page.locator('#auth-toggle')).toContainText('Zaloguj się');
  release(); await expect.poll(() => finished).toBe(true);
  expect(downloads).toEqual([]);
});
