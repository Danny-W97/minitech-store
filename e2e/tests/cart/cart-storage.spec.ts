import { test, expect, CUSTOMER, PRODUCTS, waitForCatalog } from '../../fixtures/store.fixture';

test('koszyk gościa jest wspólny dla katalogu i szczegółów produktu po nawigacji i odświeżeniu', async ({ page, mockStore }) => {
  void mockStore;
  await page.goto('/catalog.html');await waitForCatalog(page);
  await page.locator('[data-action="add"][data-product-id="101"]').click();
  await expect(page.locator('#cart-count')).toHaveText('1');
  // Product details must also remove the obsolete, unscoped cart on schema 2.
  await page.evaluate(() => localStorage.setItem('minitech-cart','[{"id":999,"quantity":99}]'));
  await page.goto('/product.html?id=101');
  await expect(page.locator('#product-name')).toHaveText(PRODUCTS[0].name);
  await expect.poll(() => page.evaluate(() => localStorage.getItem('minitech-cart'))).toBeNull();
  await page.locator('#product-add').click();
  await expect(page.locator('#product-add')).toHaveText('Dodano do koszyka');
  await page.goto('/catalog.html');await waitForCatalog(page);
  await expect(page.locator('#cart-count')).toHaveText('2');
  await page.reload();await expect(page.locator('#cart-count')).toHaveText('2');
});

test('logowanie nie łączy koszyka gościa z kontem i nie zmienia koszyka innego klienta', async ({ page, mockStore }) => {
  void mockStore;
  await page.addInitScript(({ product, customerId }) => {
    if (localStorage.getItem('cart-scope-fixture')) return;
    localStorage.setItem('cart-scope-fixture','1');
    localStorage.setItem('minitech-cart:schema-version','2');
    localStorage.setItem('minitech-cart:guest',JSON.stringify([{...product,quantity:1}]));
    localStorage.setItem(`minitech-cart:customer:${customerId}`,JSON.stringify([{...product,quantity:2}]));
    localStorage.setItem('minitech-cart:customer:7002',JSON.stringify([{...product,quantity:3}]));
  }, { product: PRODUCTS[0], customerId: CUSTOMER.id });
  await page.goto('/catalog.html');await waitForCatalog(page);
  await expect(page.locator('#cart-count')).toHaveText('1');
  const guestBeforeLogin = await page.evaluate(() => localStorage.getItem('minitech-cart:guest'));
  await page.locator('#auth-toggle').click();
  await page.locator('#auth-email').fill(CUSTOMER.email);await page.locator('#auth-password').fill('Playwright1!');
  await page.locator('#auth-submit').click();
  await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
  await expect(page.locator('#cart-count')).toHaveText('2');
  // A43.38: the independent guest basket survives account changes until an explicit user action.
  expect(await page.evaluate(() => localStorage.getItem('minitech-cart:guest'))).toBe(guestBeforeLogin);
  await page.goto('/product.html?id=101');
  await expect(page.locator('#product-name')).toHaveText(PRODUCTS[0].name);
  await page.locator('#product-add').click();
  await expect(page.locator('#product-add')).toHaveText('Dodano do koszyka');
  const carts = await page.evaluate(() => ({
    own: JSON.parse(localStorage.getItem('minitech-cart:customer:7001') || '[]'),
    other: JSON.parse(localStorage.getItem('minitech-cart:customer:7002') || '[]'),
    guest: localStorage.getItem('minitech-cart:guest')
  }));
  expect(carts.own[0].quantity).toBe(3);expect(carts.other[0].quantity).toBe(3);expect(carts.guest).toBe(guestBeforeLogin);
  await page.goto('/catalog.html');await expect(page.locator('#cart-count')).toHaveText('3');
});

test('zablokowane localStorage nie zatrzymuje katalogu ani szczegółów produktu', async ({ page, mockStore }) => {
  void mockStore;const errors:string[]=[];
  page.on('pageerror',error => errors.push(error.message));
  await page.addInitScript(() => {
    Object.defineProperty(window,'localStorage',{get(){throw new Error('Storage disabled in fixture');}});
  });
  await page.goto('/catalog.html');await waitForCatalog(page);
  await page.locator('[data-action="add"][data-product-id="101"]').click();
  await expect(page.locator('#cart-count')).toHaveText('1');
  await page.goto('/product.html?id=101');
  await expect(page.locator('#product-name')).toHaveText(PRODUCTS[0].name);
  await page.locator('#product-add').click();
  await expect(page.locator('#product-add')).toHaveText('Dodano do koszyka');
  expect(errors).toEqual([]);
});

test('wygaśnięcie sesji na produkcie ukrywa dane konta przed odpowiedzią logout i zachowuje koszyk gościa', async ({page, mockStore}) => {
  mockStore.sessionActive = true;
  await page.addInitScript(() => {
    localStorage.setItem('minitech-auth:last-activity', String(Date.now()));
    localStorage.setItem('minitech-cart:schema-version', '2');
    localStorage.setItem('minitech-cart:guest', JSON.stringify([{id: 101, quantity: 1}]));
  });
  let release!: () => void;const held = new Promise<void>(resolve => release = resolve);let logouts = 0;
  await page.route('**/customers/logout', async route => {logouts++;await held;await route.fallback();});
  await page.goto('/product.html?id=101');
  await expect(page.locator('#review-form')).toBeVisible();
  try {
    await page.clock.fastForward(30 * 60 * 1000 + 1000);
    await expect.poll(() => logouts).toBe(1);
    await expect(page.locator('#review-form')).toBeHidden();
    await expect(page.locator('#review-login-hint')).toContainText('Sesja wygasła');
    expect(await page.evaluate(() => localStorage.getItem('minitech-auth:last-activity'))).toBeNull();
    expect(await page.evaluate(() => JSON.parse(localStorage.getItem('minitech-cart:guest')!))).toEqual([{id: 101, quantity: 1}]);
  } finally {release();}
});
