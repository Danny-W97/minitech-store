import { test, expect, CUSTOMER } from '../../fixtures/store.fixture';
import { createServer } from 'node:http';

for (const productPage of [false, true]) {
  test(`${productPage ? 'product eligibility' : 'store order history'} retries a 401 with the refreshed session`, async ({ page, mockStore }) => {
    mockStore.sessionActive = true;
    await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
    let refreshes = 0;
    const tokens: string[] = [];
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/customers/refresh', route => {
      refreshes++;
      return route.fulfill({json: {accessToken: `fixture-token-${refreshes}`, customer: CUSTOMER}});
    });
    await page.route('**/orders/customer/**', route => {
      tokens.push(route.request().headers().authorization);
      if (tokens.length === 1) return route.fulfill({status: 401, json: {message: 'Expired access token'}});
      return route.fulfill({headers: {'X-Has-More': 'false', 'X-Next-Cursor': '0'}, json: [{id: 9101, status: 'PAID', totalPrice: 10, items: []}]});
    });
    await page.goto(productPage ? '/product.html?id=101' : '/index.html');
    if (productPage) {
      await expect(page.locator('#review-form')).toBeVisible();
      await expect(page.locator('#review-order')).toContainText('#9101');
    } else {
      await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
      await page.locator('#auth-toggle').click();
      await page.locator('#account-menu-orders').click();
      await expect(page.locator('#auth-orders-list')).toContainText('#9101');
    }
    expect(tokens).toEqual(['Bearer fixture-token-1', 'Bearer fixture-token-2']);
    expect(refreshes).toBe(2);
    expect(errors).toEqual([]);
  });
}

test('guest order details retain the order capability header without starting a customer session', async ({ page, mockStore }) => {
  void mockStore;
  await page.addInitScript(() => {
    const base = location.port === '18070' ? `${location.protocol}//${location.hostname}:18080/api` : `${location.origin}/api`;
    sessionStorage.setItem(`minitech.order.access.${base}.9101`, 'A'.repeat(43));
  });
  let refreshes = 0;
  page.on('request', request => {if (request.url().endsWith('/customers/refresh')) refreshes++;});
  let headers: Record<string, string> = {};
  let address = '';
  await page.route('**/orders/9101', route => {
    headers = route.request().headers(); address = route.request().url();
    return route.fulfill({json: {id: 9101, customerId: null, status: 'CONFIRMED', paymentState: 'AWAITING_PAYMENT', totalPrice: 10, items: [], deliveryAddress: {}}});
  });
  await page.goto('/order-detail.html?id=9101');
  await expect(page.locator('#order-detail-card')).toBeVisible();
  expect(headers['x-order-access-token']).toBe('A'.repeat(43));
  expect(headers.authorization).toBeUndefined();
  expect(address).not.toContain('A'.repeat(43));
  expect(refreshes).toBe(0);
});

test('a login redirect cannot forward credentials to another origin', async ({ page, mockStore }) => {
  void mockStore;
  const foreign: string[] = [];
  let received = false;
  // WebKit cannot fulfill an intercepted request with a redirect status.
  // Use a real HTTP response so every engine exercises its native fetch policy.
  const server = createServer((request, response) => {
    response.setHeader('Access-Control-Allow-Origin', request.headers.origin || '*');
    response.setHeader('Access-Control-Allow-Credentials', 'true');
    response.setHeader('Access-Control-Allow-Headers', 'content-type,authorization');
    response.setHeader('Access-Control-Allow-Methods', 'POST,OPTIONS');
    if (request.method === 'OPTIONS') {response.writeHead(204); response.end(); return;}
    request.resume();
    request.on('end', () => {
      received = true;
      response.writeHead(307, {location: 'https://store-redirect.example.invalid/collect'});
      response.end();
    });
  });
  await new Promise<void>((resolve, reject) => {server.once('error', reject); server.listen(0, '127.0.0.1', resolve);});
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Missing fixture address');
  const origin = `http://127.0.0.1:${address.port}`;
  await page.addInitScript(origin => Object.assign(window, {MINITECH_API_BASE: `${origin}/api`, MINITECH_ALLOWED_API_ORIGINS: [origin]}), origin);
  await page.route('https://store-redirect.example.invalid/**', route => {
    foreign.push(route.request().url());
    return route.fulfill({body: 'unexpected redirect'});
  });
  await page.route('**/customers/login', route => route.continue());
  try {
    await page.goto('/index.html');
    await page.locator('#auth-toggle').click();
    await page.locator('#auth-email').fill(CUSTOMER.email);
    await page.locator('#auth-password').fill('Playwright1!');
    await page.locator('#auth-submit').click();
    await expect(page.locator('#account-status')).toContainText(/fetch|network|load/i);
    await expect(page.locator('#auth-submit')).toBeEnabled();
    expect(received).toBe(true);
    expect(foreign).toEqual([]);
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});
