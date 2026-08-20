import { test, expect } from '../../fixtures/store.fixture';

test.describe('Zwroty i reklamacje', () => {
  test('formularz wysyła zgłoszenie jako application/json', async ({ page, mockStore }) => {
    await page.addInitScript(() => {
      localStorage.setItem('minitech-auth:last-activity', String(Date.now()));
    });

    mockStore.sessionActive = true;
    await page.goto('/order-detail.html?id=9101');

    const form = page.locator('#return-request-form');
    await expect(form).toBeVisible();
    await page.locator('#return-request-reason').fill('Produkt nie spełnia oczekiwań');
    await page.locator('#return-request-description').fill('Proszę o przyjęcie zwrotu produktu.');
    await form.locator('button[type="submit"]').click();

    await expect(page.locator('#return-request-status'))
      .toContainText('Zgłoszenie zapisane');
    expect(mockStore.lastReturnContentType).toContain('application/json');
  });
});
