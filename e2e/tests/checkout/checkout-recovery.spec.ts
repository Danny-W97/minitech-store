import { test, expect, CUSTOMER, waitForCatalog } from '../../fixtures/store.fixture';
import type { Page } from '@playwright/test';

async function login(page: Page, email = CUSTOMER.email) {
  await page.locator('#auth-toggle').click();
  await page.locator('#auth-email').fill(email);
  await page.locator('#auth-password').fill('Playwright1!');
  await page.locator('#auth-submit').click();
  await expect(page.locator('#auth-toggle')).toContainText(email);
  await expect(page.locator('#auth-modal')).toBeHidden();
}
async function logout(page: Page) {
  if (await page.locator('#auth-toggle').textContent() === 'Zaloguj się') return;
  await page.locator('#auth-toggle').click();
  await expect(page.locator('#account-menu')).toBeVisible();
  await page.locator('#account-menu-logout').click();
  await expect(page.locator('#auth-toggle')).toHaveText('Zaloguj się');
}
async function addItem(page: Page) {
  await page.locator('.product-card').filter({ hasText: 'Kingston Fury Beast 32GB' })
    .locator('button[data-action="add"]').click();
  await expect(page.locator('#cart-count')).not.toHaveText('0');
}
async function summary(page: Page, account = false) {
  await expect(page.locator('#cart-count')).not.toHaveText('0');
  await page.locator('#cart-area').hover();
  await expect(page.locator('#cart-preview-checkout')).toBeEnabled();
  await page.locator('#cart-preview-checkout').click();
  await page.locator('#cart-checkout').click();
  if (!account) await page.locator('#checkout-choice-guest').click();
  await page.locator('#delivery-first-name').fill('Jan');
  await page.locator('#delivery-last-name').fill('Tester');
  await page.locator('#delivery-street').fill('Testowa 1');
  await page.locator('#delivery-postal-code').fill('00-001');
  await page.locator('#delivery-city').fill('Warszawa');
  if (!account) {
    await page.locator('#delivery-email').fill('retry@example.test');
    await page.locator('#delivery-phone').fill('500600700');
  }
  await page.locator('#checkout-to-summary').click();
  await expect(page.locator('#checkout-summary-step')).toBeVisible();
  await expect(page.locator('#checkout-summary-submit')).toBeVisible();
  await expect(page.locator('#checkout-summary-submit')).toBeEnabled();
}
async function savedAttempts(page: Page) {
  return page.evaluate(() => Object.fromEntries(Object.keys(sessionStorage)
    .filter(key => key.startsWith('minitech-checkout-attempt:'))
    .map(key => [key, JSON.parse(sessionStorage.getItem(key)!)])));
}

test.describe('Odzyskiwanie checkoutu bez ponownego zakupu', () => {
  for (const account of [false, true]) for (const lost of ['order', 'payment'] as const) {
    const id = account ? (lost === 'order' ? 'A33-C03' : 'A33-C04') : (lost === 'order' ? 'A33-C01' : 'A33-C02');
    test(`[${id}] ${account ? 'konto' : 'gość'}: przeładowanie po utracie odpowiedzi ${lost} zachowuje jedną operację`, async ({ page, mockStore }) => {
      if (lost === 'order') mockStore.failFirstOrderResponseAfterCommit = true;
      else mockStore.failFirstPaymentResponseAfterCommit = true;
      const writes: { path: string; key: string; token: string; owner: number | null }[] = [];
      page.on('request', request => {
        const path = new URL(request.url()).pathname;
        if (request.method() === 'POST' && /\/(orders|payments)$/.test(path)) writes.push({
          path, key: request.headers()['idempotency-key'], token: request.headers()['x-order-access-token'],
          owner: request.postDataJSON().customerId
        });
      });
      await page.goto('/catalog.html');await waitForCatalog(page);
      if (account) await login(page);
      await addItem(page);await summary(page, account);
      await page.locator('#checkout-summary-submit').click();
      await expect(page.locator('#checkout-status')).toContainText('Downstream service is unavailable');
      expect(mockStore.createdOrderCount).toBe(1);
      expect(mockStore.createdPaymentCount).toBe(lost === 'order' ? 0 : 1);
      const before = await savedAttempts(page);
      expect(Object.keys(before)).toHaveLength(1);
      const firstWrites = writes.length;
      await page.reload();await waitForCatalog(page);
      if (account) await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
      expect(await savedAttempts(page)).toEqual(before);
      expect(writes).toHaveLength(firstWrites);
      await expect(page.locator('#cart-count')).toHaveText('1');
      await summary(page, account);
      await page.locator('#checkout-summary-submit').click();
      await expect(page.locator('#catalog-message')).toContainText('Zakup zakończony pomyślnie.');
      expect(mockStore.createdOrderCount).toBe(1);expect(mockStore.createdPaymentCount).toBe(1);
      const orders = writes.filter(x => x.path.endsWith('/orders'));
      expect(orders).toHaveLength(2);expect(new Set(orders.map(x => x.key)).size).toBe(1);
      expect(new Set(writes.map(x => x.token)).size).toBe(1);
      expect(writes[0].token).toMatch(/^[A-Za-z0-9_-]{43}$/);
      expect(writes.every(x => x.owner === (account ? CUSTOMER.id : null))).toBe(true);
      const payments = writes.filter(x => x.path.endsWith('/payments'));
      expect(payments).toHaveLength(lost === 'order' ? 1 : 2);
      expect(new Set(payments.map(x => x.key)).size).toBe(1);
      expect(await savedAttempts(page)).toEqual({});
    });
  }

  test('[A33-C05] brak zapisu klucza sesji blokuje zakup przed pierwszym POST', async ({ page, mockStore }) => {
    await page.addInitScript(() => {
      const write = Storage.prototype.setItem;
      Storage.prototype.setItem = function(key, value) {
        if (this === sessionStorage && key.startsWith('minitech-checkout-attempt:')) throw new DOMException('fixture', 'QuotaExceededError');
        return write.call(this, key, value);
      };
    });
    await page.goto('/catalog.html');await waitForCatalog(page);await addItem(page);await summary(page);
    await page.locator('#checkout-summary-submit').click();
    await expect(page.locator('#checkout-status')).toContainText('pamięć sesji');
    expect(mockStore.createdOrderCount).toBe(0);expect(mockStore.createdPaymentCount).toBe(0);
    await expect(page.locator('#cart-count')).toHaveText('1');
  });

  test('[A33-C06] równoległe wysłanie formularza nie uruchamia drugiego zamówienia', async ({ page, mockStore }) => {
    let release!: () => void;const held = new Promise<void>(resolve => release = resolve);let writes = 0;
    await page.route('**/api/orders', async route => {
      if (route.request().method() !== 'POST') return route.fallback();
      writes++;await held;await route.fallback();
    });
    await page.goto('/catalog.html');await waitForCatalog(page);await addItem(page);await summary(page);
    try {
      await page.locator('#checkout-summary-submit').click();
      await expect.poll(() => writes, { timeout: 15_000 }).toBe(1);
      await page.evaluate(() => (document.querySelector('#checkout-form') as HTMLFormElement).requestSubmit());
      expect(writes).toBe(1);
      await expect(page.locator('#checkout-summary-submit')).toBeDisabled();
    } finally {release();}
    await expect(page.locator('#catalog-message')).toContainText('Zakup zakończony pomyślnie.');
    expect(mockStore.createdOrderCount).toBe(1);expect(mockStore.createdPaymentCount).toBe(1);expect(writes).toBe(1);
  });

  test('[A33-C07] inne konto nie zużywa próby właściciela, który może potem wznowić zakup', async ({ page, mockStore }) => {
    test.slow();
    const other = { ...CUSTOMER, id: 7002, email: 'another@example.test' };
    await page.route('**/customers/login', route => {
      if (route.request().postDataJSON().email !== other.email) return route.fallback();
      mockStore.sessionActive = true;
      return route.fulfill({ json: { accessToken: 'fixture-other-owner', expiresIn: 900, customer: other } });
    });
    mockStore.failFirstOrderResponseAfterCommit = true;
    await page.goto('/catalog.html');await waitForCatalog(page);await login(page);await addItem(page);await summary(page, true);
    await page.locator('#checkout-summary-submit').click();
    await expect(page.locator('#checkout-status')).toContainText('Downstream service is unavailable');
    const original = await savedAttempts(page);expect(Object.keys(original)).toHaveLength(1);
    await page.goto('/catalog.html');await waitForCatalog(page);
    await logout(page);expect(await savedAttempts(page)).toEqual(original);
    await login(page, other.email);await addItem(page);await summary(page, true);
    await page.locator('#checkout-summary-submit').click();
    await expect(page.locator('#catalog-message')).toContainText('Zamówienie #9002');
    expect(mockStore.createdOrderCount).toBe(2);expect(mockStore.createdPaymentCount).toBe(1);
    expect(await savedAttempts(page)).toEqual(original);
    const owners = [...mockStore.ordersByIdempotencyKey.values()].map(o => o.customerId);
    expect(owners).toEqual([CUSTOMER.id, other.id]);
    await page.goto('/catalog.html');await waitForCatalog(page);
    await logout(page);await login(page);await summary(page, true);
    await page.locator('#checkout-summary-submit').click();
    await expect(page.locator('#catalog-message')).toContainText('Zamówienie #9001');
    expect(mockStore.createdOrderCount).toBe(2);expect(mockStore.createdPaymentCount).toBe(2);
    expect(await savedAttempts(page)).toEqual({});
  });
});
