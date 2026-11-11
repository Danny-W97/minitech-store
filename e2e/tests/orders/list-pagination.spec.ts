import { test, expect, CUSTOMER } from '../../fixtures/store.fixture';

test('order history follows an opaque cursor and a failed next page preserves earlier orders', async ({ page, mockStore }) => {
  mockStore.sessionActive = true;
  await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
  const cursors: string[] = [];
  let failed = false;
  await page.route('**/orders/customer/**', async route => {
    const cursor = new URL(route.request().url()).searchParams.get('cursor') || '0';
    cursors.push(cursor);
    if (cursor !== '0' && !failed) { failed = true; return route.fulfill({ status: 503, json: { message: 'Temporary outage' } }); }
    await route.fulfill({ headers: { 'Access-Control-Expose-Headers': 'X-Has-More,X-Next-Cursor', 'X-Has-More': cursor === '0' ? 'true' : 'false', 'X-Next-Cursor': '9223372036854775806' }, json: [{ id: cursor === '0' ? 9101 : 9001, status: 'PAID', totalPrice: 10, items: [] }] });
  });
  await page.goto('/index.html');
  await page.locator('#auth-toggle').click();
  if (await page.locator('#auth-email').isVisible()) {
    await page.locator('#auth-email').fill(CUSTOMER.email);
    await page.locator('#auth-password').fill('Playwright1!');
    await page.locator('#auth-submit').click();
    await page.locator('#auth-toggle').click();
  }
  await page.locator('#account-menu-orders').click();
  await expect(page.locator('#auth-orders-list')).toContainText('#9101');
  await page.getByRole('button', { name: 'Pokaż starsze zamówienia' }).click();
  await expect(page.locator('#auth-orders-status')).toContainText('Temporary outage');
  await expect(page.locator('#auth-orders-list')).toContainText('#9101');
  await page.getByRole('button', { name: 'Pokaż starsze zamówienia' }).click();
  await expect(page.locator('#auth-orders-list')).toContainText('#9001');
  await expect(page.getByRole('button', { name: 'Pokaż starsze zamówienia' })).toHaveCount(0);
  expect(cursors).toEqual(['0', '9223372036854775806', '9223372036854775806']);
});

test('review pages preserve the full database summary', async ({ page, mockStore }) => {
  void mockStore;
  const cursors: string[] = [];
  await page.route('**/reviews/products/101**', route => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/summary')) return route.fulfill({ json: { publishedCount: 100001, averageRating: 4.25 } });
    const cursor = url.searchParams.get('cursor') || '0'; cursors.push(cursor);
    return route.fulfill({ headers: { 'Access-Control-Expose-Headers': 'X-Has-More,X-Next-Cursor', 'X-Has-More': cursor === '0' ? 'true' : 'false', 'X-Next-Cursor': '9000' }, json: [{ id: cursor === '0' ? 9000 : 8999, rating: 5, title: cursor === '0' ? 'Recent review' : 'Older review', content: 'Verified content' }] });
  });
  await page.goto('/product.html?id=101');
  await expect(page.locator('#review-summary')).toContainText('100001');
  await page.getByRole('button', { name: 'Pokaż starsze opinie' }).click();
  await expect(page.locator('#review-list')).toContainText('Recent review');
  await expect(page.locator('#review-list')).toContainText('Older review');
  await expect(page.locator('#review-summary')).toContainText('4.25');
  expect(cursors).toEqual(['0','9000']);
});

test('review purchase selection pages PAID orders for the current product', async ({ page, mockStore }) => {
  mockStore.sessionActive = true;
  await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
  const queries: URL[] = [];
  await page.route('**/orders/customer/**', route => {
    const url = new URL(route.request().url()); queries.push(url);
    const first = url.searchParams.get('cursor') === '0';
    return route.fulfill({ headers: { 'Access-Control-Expose-Headers': 'X-Has-More,X-Next-Cursor', 'X-Has-More': first ? 'true' : 'false', 'X-Next-Cursor': first ? '9000' : '0' }, json: [{ id: first ? 9000 : 8999, status: 'PAID', totalPrice: 10, items: [{ productId: 101 }] }] });
  });
  await page.goto('/product.html?id=101');
  await expect(page.locator('#review-order option')).toHaveCount(1);
  await page.getByRole('button', { name: 'Pokaż starsze zakupy' }).click();
  await expect(page.locator('#review-order option')).toHaveCount(2);
  expect(queries.map(url => [url.searchParams.get('status'),url.searchParams.get('productId'),url.searchParams.get('cursor')])).toEqual([['PAID','101','0'],['PAID','101','9000']]);
});
