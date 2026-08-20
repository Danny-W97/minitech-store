import { test, expect, waitForCatalog } from '../../fixtures/store.fixture';

test.describe('Katalog produktów', () => {
  test.beforeEach(async ({ page, mockStore }) => {
    void mockStore;
    await page.goto('/catalog.html');
    await waitForCatalog(page);
  });

  test('wyszukuje po nazwie, producencie i SKU bez wyników innych marek', async ({ page }) => {
    const search = page.locator('#product-search');
    await search.fill('Kingston');
    await expect(page.locator('.product-card')).toHaveCount(1);
    await expect(page.locator('.product-card')).toContainText('Kingston Fury Beast 32GB');

    await search.fill('SSD-SAM-990PRO-2TB');
    await expect(page.locator('.product-card')).toHaveCount(1);
    await expect(page.locator('.product-card')).toContainText('Samsung 990 PRO 2TB');
  });

  test('producent i parametry zależą od wybranej kategorii', async ({ page }) => {
    await page.locator('#category-filter').selectOption('RAM');
    await expect(page.locator('#manufacturer-filter option')).toHaveText(['Wszyscy', 'Kingston']);
    await expect(page.locator('#technical-parameter-filter option')).toHaveText([
      'Wszystkie parametry', 'Pojemność', 'Typ', 'Taktowanie (MHz)'
    ]);

    await page.locator('#category-filter').selectOption('GPU');
    await expect(page.locator('#manufacturer-filter option')).toHaveText(['Wszyscy', 'ASUS']);
    await expect(page.locator('#technical-parameter-filter option')).toHaveText([
      'Wszystkie parametry', 'Pamięć', 'Interfejs', 'Ray tracing'
    ]);
  });

  test('filtruje po przedziale ceny i parametrze technicznym', async ({ page }) => {
    await page.locator('#price-min-filter').fill('600');
    await page.locator('#price-max-filter').fill('800');
    await expect(page.locator('.product-card')).toHaveCount(1);
    await expect(page.locator('.product-card')).toContainText('Samsung 990 PRO 2TB');

    await page.locator('#price-min-filter').fill('');
    await page.locator('#price-max-filter').fill('');
    await page.locator('#category-filter').selectOption('RAM');
    await page.locator('#technical-parameter-filter').selectOption('type');
    await page.locator('#technical-value-filter').fill('DDR5');
    await expect(page.locator('.product-card')).toHaveCount(1);
    await expect(page.locator('.product-card')).toContainText('Kingston Fury Beast 32GB');
  });

  test('sortuje ceny rosnąco i malejąco', async ({ page }) => {
    await page.locator('#sort-filter').selectOption('price-asc');
    await expect(page.locator('.product-card h3').first()).toContainText('Kingston Fury Beast 32GB');
    await page.locator('#sort-filter').selectOption('price-desc');
    await expect(page.locator('.product-card h3').first()).toContainText('ASUS GeForce RTX 4070 SUPER');
  });

  test('brak wyników pokazuje kontrolowany komunikat', async ({ page }) => {
    await page.locator('#product-search').fill('produkt-który-nie-istnieje');
    await expect(page.locator('#product-grid')).toContainText('Brak produktów pasujących do filtrów.');
  });
});
