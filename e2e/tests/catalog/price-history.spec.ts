import { test, expect } from '../../fixtures/store.fixture';

for (const scenario of [
  {name: 'empty history', lowestPrice: null, historyComplete: false},
  {name: 'null despite a completeness flag', lowestPrice: null, historyComplete: true},
  {name: 'partial history with an amount', lowestPrice: 449.99, historyComplete: false},
  {name: 'complete history', lowestPrice: 449.99, historyComplete: true},
]) {
  test(`price history: ${scenario.name} on the catalog and product page`, async ({page, mockStore}) => {
    await page.route('**/api/**/promotions/history/**', route => route.fulfill({json: scenario}));
    // Legacy /api and versioned /api/v1 are both accepted by the fixture.
    await page.route('**/api/promotions/history/**', route => route.fulfill({json: scenario}));
    const complete = scenario.historyComplete && scenario.lowestPrice !== null;
    await page.goto('/index.html');
    const hotShot = page.locator('.hot-shot-lowest');
    await expect(hotShot).toBeVisible();
    if (complete) await expect(hotShot).toContainText(/449[,.]99/);
    else await expect(hotShot).toContainText('Brak pełnej historii');
    await page.goto('/product.html?id=101');
    const detail = page.locator('#product-lowest-price');
    await expect(detail).toBeVisible();
    if (complete) await expect(detail).toContainText(/449[,.]99/);
    else {
      await expect(detail).toContainText('Brak pełnej historii');
      await expect(detail).not.toContainText(/0[,.]00/);
    }
  });
}
