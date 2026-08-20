import { test, expect } from '@playwright/test';

test.describe('Pełny system w przeglądarce', () => {
  test.skip(process.env.PLAYWRIGHT_FULL_SYSTEM !== '1', 'Wymaga wszystkich serwisów dev/test, Redis i Mailpit.');

  test('@full-system @smoke katalog, koszyk i checkout gościa działają przez prawdziwy API Gateway', async ({ page, request }) => {
    const apiBase = process.env.PLAYWRIGHT_API_BASE_URL || 'http://127.0.0.1:18080/api';
    const productsResponse = await request.get(`${apiBase}/products`);
    expect(productsResponse.ok()).toBeTruthy();
    const products = await productsResponse.json();
    const candidates = products.filter((product: { active: boolean; price: number }) => product.active && Number(product.price) < 900);
    let selected: { id: number; name: string; sku: string; price: number } | undefined;
    for (const product of candidates) {
      const inventory = await request.get(`${apiBase}/inventory/items/${product.id}`);
      if (inventory.ok() && Number((await inventory.json()).availableQuantity) > 0) {
        selected = product;
        break;
      }
    }
    expect(selected, 'Brak produktu poniżej 900 zł z dodatnim stanem magazynowym').toBeTruthy();

    await page.goto(`/catalog.html?search=${encodeURIComponent(selected!.sku)}`);
    const card = page.locator('.product-card').filter({ hasText: selected!.name });
    await expect(card).toBeVisible();
    await card.locator('button[data-action="add"]').click();
    await page.locator('#cart-toggle').click();
    await page.locator('#cart-preview-checkout').click();
    await page.locator('#cart-checkout').click();
    await page.locator('#checkout-choice-guest').click();

    const email = `playwright-${Date.now()}@minitech.local`;
    await page.locator('#delivery-first-name').fill('Browser');
    await page.locator('#delivery-last-name').fill('Tester');
    await page.locator('#delivery-street').fill('Testowa 1');
    await page.locator('#delivery-postal-code').fill('00-001');
    await page.locator('#delivery-city').fill('Warszawa');
    await page.locator('#recipient-email').fill(email);
    await page.locator('#checkout-to-summary').click();
    await expect(page.locator('#checkout-summary-step')).toBeVisible();
    await page.locator('#checkout-summary-submit').click();
    await expect(page.locator('#catalog-message')).toContainText('Zakup zakończony pomyślnie.', { timeout: 30_000 });

    await expect.poll(async () => {
      const result = await request.get(`http://127.0.0.1:18025/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`);
      if (!result.ok()) return 0;
      const payload = await result.json();
      if (Array.isArray(payload.messages)) return payload.messages.length;
      return Number(payload.total || payload.messages_count || 0);
    }, { timeout: 20_000 }).toBeGreaterThan(0);
  });
});
