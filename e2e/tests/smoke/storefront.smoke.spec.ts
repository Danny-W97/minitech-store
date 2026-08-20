import { test, expect, waitForCatalog } from '../../fixtures/store.fixture';

test.describe('MiniTech Store smoke', () => {
  test('@smoke strona główna ładuje najważniejsze elementy bez błędów JavaScript', async ({ page, mockStore }) => {
    void mockStore;
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));

    await page.goto('/index.html');
    await expect(page.locator('.brand').first()).toContainText('MiniTech Store');
    await expect(page.locator('#hero-carousel')).toBeVisible();
    await expect(page.locator('#promotion-grid .product-card').first()).toBeVisible();
    await expect(page.locator('#new-products-grid .product-card').first()).toBeVisible();
    await expect(page.locator('[data-hero-kind="new"] [data-hero-link]'))
      .toHaveAttribute('href', /product\.html\?id=\d+/);
    await expect(page.locator('#hero-carousel')).toHaveAttribute('data-rotation-date', '2026-08-20');
    await expect(page.locator('#cart-count')).toHaveText('0');
    expect(errors).toEqual([]);
  });

  test('@smoke katalog, szczegóły produktu i koszyk tworzą spójny przepływ', async ({ page, mockStore }) => {
    void mockStore;
    await page.goto('/catalog.html');
    await waitForCatalog(page);

    const ram = page.locator('.product-card').filter({ hasText: 'Kingston Fury Beast 32GB' });
    await expect(ram).toBeVisible();
    await ram.locator('.product-detail-button').click();
    await expect(page).toHaveURL(/product\.html\?id=101/);
    await expect(page.locator('#product-name')).toHaveText('Kingston Fury Beast 32GB');

    await page.goto('/catalog.html');
    await waitForCatalog(page);
    await page.locator('.product-card').filter({ hasText: 'Kingston Fury Beast 32GB' })
      .locator('button[data-action="add"]').click();
    await expect(page.locator('#cart-count')).toHaveText('1');
    await page.locator('#cart-area').hover();
    await expect(page.locator('#cart-preview')).toBeVisible();
    await expect(page.locator('#cart-preview')).toContainText('Kingston Fury Beast 32GB');
  });

  test('@smoke logo zawsze prowadzi do strony głównej', async ({ page, mockStore }) => {
    void mockStore;
    await page.goto('/catalog.html');
    await page.locator('.topbar .brand').click();
    await expect(page).toHaveURL(/index\.html#top$/);
    await expect(page.locator('#hero-carousel')).toBeVisible();
  });
});
