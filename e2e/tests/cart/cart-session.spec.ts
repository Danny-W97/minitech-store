import { test, expect, CUSTOMER, waitForCatalog } from '../../fixtures/store.fixture';

async function addRam(page: import('@playwright/test').Page) {
  await page.locator('.product-card').filter({ hasText: 'Kingston Fury Beast 32GB' })
    .locator('button[data-action="add"]').click();
}

async function login(page: import('@playwright/test').Page) {
  await page.locator('#auth-toggle').click();
  await page.locator('#auth-email').fill(CUSTOMER.email);
  await page.locator('#auth-password').fill('Playwright1!');
  await page.locator('#auth-submit').click();
  await expect(page.locator('#auth-toggle')).toContainText(`Konto: ${CUSTOMER.email}`);
  await expect(page.locator('#auth-modal')).toBeHidden();
}

test.describe('Koszyk i sesja klienta', () => {
  test.beforeEach(async ({ page, mockStore }) => {
    void mockStore;
    await page.goto('/catalog.html');
    await waitForCatalog(page);
  });

  test('zmienia ilość, respektuje magazyn i usuwa produkt', async ({ page }) => {
    await addRam(page);
    await page.locator('#cart-area').hover();
    const preview = page.locator('#cart-preview');
    await expect(preview).toContainText('Kingston Fury Beast 32GB');
    await preview.getByRole('button', { name: 'Zwiększ ilość' }).click();
    await expect(preview.locator('.quantity-value')).toHaveText('2');
    await page.locator('#cart-area').hover();
    await preview.getByRole('button', { name: 'Zmniejsz ilość' }).click();
    await expect(preview.locator('.quantity-value')).toHaveText('1');
    await page.locator('#cart-area').hover();
    await preview.getByRole('button', { name: 'Usuń' }).click();
    await expect(page.locator('#cart-count')).toHaveText('0');
  });

  test('koszyk gościa pozostaje po odświeżeniu', async ({ page }) => {
    await addRam(page);
    await page.reload();
    await waitForCatalog(page);
    await expect(page.locator('#cart-count')).toHaveText('1');
    expect(await page.evaluate(() => localStorage.getItem('minitech-cart:guest'))).toContain('Kingston Fury');
  });

  test('jednorazowa migracja usuwa stary koszyk gościa', async ({ page }) => {
    await page.evaluate(() => {
      localStorage.setItem('minitech-cart:guest', JSON.stringify([{ id: 2, name: 'Stary koszyk', quantity: 27 }]));
      localStorage.removeItem('minitech-cart:schema-version');
    });

    await page.reload();
    await waitForCatalog(page);

    await expect(page.locator('#cart-count')).toHaveText('0');
    expect(await page.evaluate(() => JSON.parse(localStorage.getItem('minitech-cart:guest') || '[]'))).toEqual([]);
    expect(await page.evaluate(() => localStorage.getItem('minitech-cart:schema-version'))).toBe('2');
  });

  test('produkty konta nie są widoczne po wylogowaniu i wracają po ponownym logowaniu', async ({ page }) => {
    await addRam(page);
    await expect(page.locator('#cart-count')).toHaveText('1');

    await login(page);
    await addRam(page);
    await expect(page.locator('#cart-count')).toHaveText('1');

    await page.locator('#auth-toggle').click();
    await page.locator('#account-menu-logout').click();
    await expect(page.locator('#auth-toggle')).toHaveText('Zaloguj się');
    await expect(page.locator('#cart-count')).toHaveText('1');
    expect(await page.evaluate(() => localStorage.getItem('minitech-cart:guest'))).toContain('Kingston Fury');

    await login(page);
    await expect(page.locator('#cart-count')).toHaveText('1');
  });

  test('odświeżenie strony zachowuje zalogowaną sesję i koszyk konta', async ({ page }) => {
    await login(page);
    await addRam(page);
    await page.reload();
    await waitForCatalog(page);
    await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
    await expect(page.locator('#cart-count')).toHaveText('1');
  });

  test('wylogowanie czyści dane konta przed odpowiedzią serwera i zachowuje niezależny koszyk gościa',async({page})=>{
    await addRam(page);await login(page);
    await page.evaluate(()=>{
      (document.querySelector('#auth-profile-first-name') as HTMLInputElement).value='Private fixture name';
      (document.querySelector('#billing-tax-id') as HTMLInputElement).value='1234567890';
      const qr=document.querySelector('#auth-totp-qr');qr?.setAttribute('src','data:image/png;base64,private-fixture');
    });
    let release!:()=>void;const held=new Promise<void>(resolve=>release=resolve);
    await page.route('**/api/customers/logout',async route=>{await held;await route.fallback();});
    await page.locator('#auth-toggle').click();await page.locator('#account-menu-logout').click();
    await expect(page.locator('#auth-toggle')).toHaveText('Zaloguj się');
    expect(await page.locator('#auth-profile-first-name').inputValue()).toBe('');
    expect(await page.locator('#billing-tax-id').inputValue()).toBe('');
    expect(await page.locator('#auth-totp-qr').getAttribute('src')).toBeNull();
    await expect(page.locator('#cart-count')).toHaveText('1');release();
  });

  test('sesja starsza niż 30 minut jest unieważniana po ponownym otwarciu sklepu', async ({ page, mockStore }) => {
    await login(page);
    await addRam(page);
    await page.evaluate(() => {
      localStorage.setItem('minitech-auth:last-activity', String(Date.now() - (30 * 60 * 1000) - 1000));
    });

    await page.reload();
    await waitForCatalog(page);

    await expect(page.locator('#auth-toggle')).toHaveText('Zaloguj się');
    await expect(page.locator('#cart-count')).toHaveText('0');
    await expect.poll(() => mockStore.sessionActive).toBe(false);
    expect(await page.evaluate(() => localStorage.getItem('minitech-auth:last-activity'))).toBeNull();
  });
});
