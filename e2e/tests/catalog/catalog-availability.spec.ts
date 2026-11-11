import { test, expect, PRODUCTS } from '../../fixtures/store.fixture';

const stockRow = (productId: number, availableQuantity = 12) => ({ productId, availableQuantity, reservedQuantity: 0, soldQuantity: 0 });
const products = (count: number) => Array.from({ length: count }, (_, index) => ({
  ...PRODUCTS[0], id: 1000 + index, sku: `BATCH-${index}`, name: `Batch product ${index}`,
  promotional: false, newProduct: false, discountPercent: 0, imageUrl: '/qa-stock-fixture.svg'
}));

test('1000 products render before stock; only the first page is requested and image nodes stay intact', async ({ page, mockStore }) => {
  void mockStore;
  const batches: number[][] = [], individual: string[] = [];
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/qa-stock-fixture.svg', route => route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>' }));
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/products')) return route.fulfill({ json: products(1000) });
    if (/\/inventory\/items\/\d+$/.test(url.pathname)) individual.push(url.pathname);
    if (url.pathname.endsWith('/inventory/items/batch')) {
      const ids = url.searchParams.get('productIds')!.split(',').map(Number); batches.push(ids);
      await gate;
      return route.fulfill({ json: ids.map(id => stockRow(id, id === 1000 ? 0 : 12)) }).catch(() => {});
    }
    return route.fallback();
  });
  try {
    await page.goto('/catalog.html');
    await expect(page.locator('#product-grid .product-card')).toHaveCount(1000);
    await expect.poll(() => batches.length).toBeGreaterThan(0);
    expect(batches.length).toBeLessThanOrEqual(2);
    expect(new Set(batches.flat()).size).toBeLessThanOrEqual(32);
    expect(individual).toEqual([]);
    const image = page.locator('#product-grid img').first();
    await image.evaluate(node => node.setAttribute('data-qa-original-node', 'yes'));
    release();
    await expect(page.locator('#product-grid [data-action="add"][data-product-id="1000"]')).toBeDisabled();
    await expect(image).toHaveAttribute('data-qa-original-node', 'yes');
  } finally { release(); }
});

test('inventory and price-history failures leave the catalog usable with unknown availability', async ({ page, mockStore }) => {
  void mockStore;
  const individual: string[] = [];
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url());
    if (/\/inventory\/items\/\d+$/.test(url.pathname)) individual.push(url.pathname);
    if (url.pathname.endsWith('/inventory/items/batch') || url.pathname.includes('/promotions/history/')) {
      return route.fulfill({ status: 503, json: { message: 'Isolated dependency failure' } });
    }
    return route.fallback();
  });
  await page.goto('/catalog.html');
  await expect(page.locator('#product-grid .product-card')).toHaveCount(PRODUCTS.length);
  await expect(page.locator('#catalog-message')).not.toContainText('Nie można pobrać katalogu');
  await expect(page.locator('#product-grid [data-action="add"]').first()).toBeEnabled();
  expect(individual).toEqual([]);
});

test('refresh cancels old metadata and cannot overwrite a newer zero-stock snapshot', async ({ page, mockStore }) => {
  void mockStore;
  let catalogReads = 0, stockReads = 0, release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/products')) {
      catalogReads++;
      return route.fulfill({ json: [{ ...PRODUCTS[0], name: catalogReads === 1 ? 'Old catalog' : 'New catalog' }] });
    }
    if (url.pathname.endsWith('/inventory/items/batch')) {
      stockReads++;
      const old = stockReads === 1;
      if (old) await gate;
      return route.fulfill({ json: [stockRow(PRODUCTS[0].id, old ? 12 : 0)] }).catch(() => {});
    }
    return route.fallback();
  });
  try {
    await page.goto('/catalog.html');
    await expect.poll(() => stockReads).toBe(1);
    await page.locator('#refresh-products').click();
    await expect(page.locator('#product-grid')).toContainText('New catalog');
    const button = page.locator('#product-grid [data-action="add"]').first();
    await expect(button).toBeDisabled();
    release();
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(button).toBeDisabled();
    await expect(page.locator('#product-grid')).not.toContainText('Old catalog');
  } finally { release(); }
});

test('explicit stock sorting batches the filtered set with at most two requests in flight', async ({ page, mockStore }) => {
  void mockStore;
  const batches: number[][] = [];
  let active = 0, maximum = 0;
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/products')) return route.fulfill({ json: products(250) });
    if (url.pathname.endsWith('/inventory/items/batch')) {
      const ids = url.searchParams.get('productIds')!.split(',').map(Number); batches.push(ids);
      active++; maximum = Math.max(maximum, active);
      await new Promise(resolve => setTimeout(resolve, 30));
      await route.fulfill({ json: ids.map(id => stockRow(id, id === 1249 ? 999 : 1)) });
      active--; return;
    }
    return route.fallback();
  });
  await page.goto('/catalog.html');
  await expect(page.locator('#product-grid .product-card')).toHaveCount(250);
  await page.locator('#sort-filter').selectOption('stock-desc');
  await expect(page.locator('#product-grid .product-card').first()).toContainText('Batch product 249');
  expect(maximum).toBeLessThanOrEqual(2);
  expect(batches.length).toBeLessThanOrEqual(5);
  expect(batches.every(ids => ids.length <= 100)).toBe(true);
});
