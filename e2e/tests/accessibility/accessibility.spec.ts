import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { test, expect, waitForCatalog, CUSTOMER } from '../../fixtures/store.fixture';

async function audit(page: Page, name: string) {
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  const violations = result.violations.filter(row => ['serious', 'critical'].includes(row.impact || ''))
    .map(row => ({ id: row.id, impact: row.impact, nodes: row.nodes.map(node => ({ target: node.target, failure: node.failureSummary })) }));
  await test.info().attach(`axe-${name}`, { body: JSON.stringify({ url: page.url(), violations, incompleteRules: result.incomplete.map(row => row.id) }, null, 2), contentType: 'application/json' });
  expect(violations).toEqual([]);
}

async function tabTo(page: Page, selector: string, key = 'Tab') {
  for (let count = 0; count < 160; count++) {
    if (await page.locator(selector).evaluate(element => element === document.activeElement)) return;
    await page.keyboard.press(key);
  }
  throw new Error(`Keyboard could not reach ${selector}`);
}

async function containedFocus(page: Page, selector: string) {
  await expect.poll(() => page.locator(selector).evaluate(dialog => dialog.contains(document.activeElement))).toBe(true);
}

test('strona główna nie ma poważnych ani krytycznych naruszeń semantycznej dostępności', async ({ page, mockStore }) => {
  void mockStore;
  await page.goto('/index.html');
  await expect(page.locator('#hero-carousel')).toBeVisible();
  await audit(page, 'home');
});

for (const path of ['/index.html', '/catalog.html']) {
  test(`dialog logowania ma zamknięty cykl fokusu, inertne tło i powrót po Escape: ${path}`, async ({ page, mockStore }) => {
    void mockStore;
    await page.goto(path);
    await page.locator('#auth-toggle').focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('#auth-title')).toBeFocused();
    await expect(page.locator('#auth-modal')).toHaveJSProperty('open', true);
    expect(await page.locator('#auth-modal').evaluate(dialog => dialog.matches(':modal'))).toBe(true);
    await page.locator('#auth-toggle').evaluate(button => (button as HTMLElement).focus());
    await containedFocus(page, '#auth-modal');
    await page.locator('#auth-close').focus();
    await page.keyboard.press('Shift+Tab');
    await expect(page.locator('#auth-forgot-password')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.locator('#auth-close')).toBeFocused();
    for (let index = 0; index < 14; index++) {
      await page.keyboard.press(index % 2 ? 'Shift+Tab' : 'Tab');
      await containedFocus(page, '#auth-modal');
    }
    await expect(page.locator('body')).toHaveCSS('overflow', 'hidden');
    await audit(page, 'login');
    await page.screenshot({ path: test.info().outputPath('login-dialog.png') });
    await page.keyboard.press('Escape');
    await expect(page.locator('#auth-modal')).toBeHidden();
    await expect(page.locator('#auth-toggle')).toBeFocused();
    await expect(page.locator('body')).not.toHaveClass(/store-dialog-open/);
    await page.keyboard.press('Enter');
    await page.locator('#auth-close').click();
    await expect(page.locator('#auth-toggle')).toBeFocused();
  });
}

test('rejestracja i reset hasła przechodzą pomiar z kontrastem', async ({ page, mockStore }) => {
  void mockStore;
  await page.goto('/index.html');
  await page.locator('#auth-toggle').click();
  await page.locator('#auth-register-choice').click();
  await audit(page, 'registration');
  await page.locator('#auth-login-choice').click();
  await page.locator('#auth-forgot-password').click();
  await audit(page, 'password-reset');
  await containedFocus(page, '#auth-modal');
  await page.keyboard.press('Escape');
  await expect(page.locator('#auth-toggle')).toBeFocused();
});

test('MFA przenosi fokus na kod, obsługuje Enter, pokazuje błąd i czyści wyzwanie po Escape', async ({ page, mockStore }) => {
  void mockStore;
  let verifies = 0;
  await page.route('**/customers/login', route => route.fulfill({ json: { mfaRequired: true, challengeId: 'a37-fixture', method: 'totp', expiresIn: 300 } }));
  await page.route('**/customers/mfa/login/verify', route => {
    verifies++;
    if (verifies === 1) return route.fulfill({ status: 401, json: { message: 'Nieprawidłowy kod MFA' } });
    mockStore.sessionActive = true;
    return route.fulfill({ json: { accessToken: 'a37.header.signature', expiresIn: 900, customer: CUSTOMER } });
  });
  await page.goto('/index.html');
  await page.locator('#auth-toggle').click();
  await page.locator('#auth-email').fill('fixture@example.test');
  await page.locator('#auth-password').fill('FixturePassword1!');
  await page.keyboard.press('Enter');
  await expect(page.locator('#auth-mfa-code')).toBeFocused();
  await page.keyboard.type('123456');
  await page.keyboard.press('Enter');
  await expect(page.locator('#auth-mfa-status')).toContainText('Nie udało się');
  expect(verifies).toBe(1);
  await audit(page, 'mfa-error');
  await page.keyboard.press('Escape');
  await expect(page.locator('#auth-toggle')).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('#auth-mfa-panel')).toBeHidden();
  await expect(page.locator('#auth-form')).toBeVisible();
  await tabTo(page, '#auth-password');
  await page.keyboard.press('Enter');
  await expect(page.locator('#auth-mfa-code')).toBeFocused();
  await page.keyboard.type('654321');
  await page.keyboard.press('Enter');
  await expect(page.locator('#auth-modal')).toBeHidden();
  await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
  await expect(page.locator('#auth-toggle')).toBeFocused();
  expect(verifies).toBe(2);
});

test('zakup gościa działa wyłącznie klawiaturą i ogłasza kroki oraz wynik', async ({ page, mockStore }) => {
  test.slow();
  void mockStore;
  await page.goto('/catalog.html');
  await waitForCatalog(page);
  await audit(page, 'catalog');
  await tabTo(page, '[data-action="add"][data-product-id="101"]');
  await page.keyboard.press('Enter');
  await tabTo(page, '#cart-toggle', 'Shift+Tab');
  await page.keyboard.press('Enter');
  await tabTo(page, '#cart-preview-checkout');
  await page.keyboard.press('Escape');
  await expect(page.locator('#cart-toggle')).toBeFocused();
  await page.keyboard.press('Enter');
  await tabTo(page, '#cart-preview-checkout');
  await page.keyboard.press('Enter');
  await tabTo(page, '#cart-checkout');
  await page.keyboard.press('Enter');
  await expect(page.locator('#checkout-choice-title')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.locator('#cart-checkout')).toBeFocused();
  await page.keyboard.press('Enter');
  await audit(page, 'purchase-choice');
  await tabTo(page, '#checkout-choice-guest');
  await page.keyboard.press('Enter');
  await expect(page.locator('#checkout-title')).toBeFocused();
  await tabTo(page, '#checkout-to-summary');
  await page.keyboard.press('Enter');
  await expect(page.locator('#delivery-first-name')).toBeFocused();
  await audit(page, 'delivery-validation');
  for (const [selector, value] of [['#delivery-first-name', 'Jan'], ['#delivery-last-name', 'Testowy'], ['#delivery-email', 'fixture@example.test'], ['#delivery-phone', '+48 123 456 789'], ['#delivery-street', 'Testowa 1'], ['#delivery-postal-code', '00-001'], ['#delivery-city', 'Warszawa']]) {
    await tabTo(page, selector);
    await page.keyboard.type(value);
  }
  await audit(page, 'delivery');
  await tabTo(page, '#invoice-requested');
  await page.keyboard.press('Space');
  await audit(page, 'invoice-fields');
  await page.keyboard.press('Space');
  await tabTo(page, '#checkout-to-summary');
  await page.keyboard.press('Enter');
  await expect(page.locator('#checkout-summary-step-title')).toBeFocused();
  await audit(page, 'order-summary');
  await tabTo(page, '#checkout-summary-submit');
  await page.keyboard.press('Enter');
  await expect(page.locator('#catalog-message')).toContainText('Zakup zakończony pomyślnie');
  await expect(page.locator('#catalog-message')).toBeFocused();
  expect(mockStore.createdOrderCount).toBe(1);
  expect(mockStore.createdPaymentCount).toBe(1);
});

test('porównanie produktów oraz potwierdzenie mają Escape i powrót fokusu', async ({ page, mockStore }) => {
  void mockStore;
  await page.goto('/catalog.html');
  await waitForCatalog(page);
  await page.locator('[data-action="compare"][data-product-id="101"]').click();
  await page.locator('[data-action="compare"][data-product-id="102"]').click();
  await page.locator('#compare-open').focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('#compare-dialog-title')).toBeFocused();
  await audit(page, 'comparison');
  for (let index = 0; index < 15; index++) { await page.keyboard.press('Tab'); await containedFocus(page, '#compare-modal'); }
  await page.keyboard.press('Escape');
  await expect(page.locator('#compare-open')).toBeFocused();
  // Exercise the retained confirmation component; current checkout announces its result inline.
  await page.evaluate(async () => {
    const entry = document.querySelector<HTMLScriptElement>('script[type="module"][src^="app.js"]')!;
    const { openSuccessModal } = await import(entry.src);
    openSuccessModal([{ order: { id: 1 } }], 'fixture@example.test');
  });
  await expect(page.locator('#success-title')).toBeFocused();
  await audit(page, 'confirmation');
  await page.keyboard.press('Escape');
  await expect(page.locator('#compare-open')).toBeFocused();
});

test('formularz dialogu zachowuje dostęp do kontrolek przy 320 px i reduced motion', async ({ page, mockStore }) => {
  void mockStore;
  await page.setViewportSize({ width: 320, height: 640 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/index.html');
  await page.locator('#auth-toggle').click();
  await page.locator('#auth-register-choice').click();
  await tabTo(page, '#auth-country');
  await expect(page.locator('#auth-country')).toBeInViewport();
  await tabTo(page, '#auth-submit');
  await expect(page.locator('#auth-submit')).toBeInViewport();
  const dimensions = await page.locator('.auth-dialog').evaluate(dialog => ({ scroll: dialog.scrollWidth, width: dialog.clientWidth }));
  expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.width + 1);
  await expect(page.locator('html')).toHaveCSS('scroll-behavior', 'auto');
  await audit(page, 'registration-320px');
  await page.screenshot({ path: test.info().outputPath('registration-320px.png') });
  await page.keyboard.press('Escape');
  await expect(page.locator('#auth-toggle')).toBeFocused();
});

test('katalog działa na ekranie mobilnym bez poziomego przewijania dokumentu', async ({ page, mockStore }) => {
  void mockStore;
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/catalog.html');
  await waitForCatalog(page);
  const dimensions = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }));
  expect(dimensions.width).toBeLessThanOrEqual(dimensions.viewport + 1);
  await expect(page.locator('#cart-toggle')).toBeVisible();
});

for (const [path, ready] of [['/product.html?id=101', '#product-name'], ['/delivery.html', 'main'], ['/order-detail.html?id=9101', '#order-detail-card']]) {
  test(`kontrast i semantyka strony: ${path}`, async ({ page, mockStore }) => {
    mockStore.sessionActive = true;
    await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
    await page.goto(path);
    await expect(page.locator(ready)).toBeVisible();
    await audit(page, path.split('?')[0]);
  });
}

test('panele konta przywracają fokus z ukrytego menu i przechodzą pomiar kontrastu', async ({ page, mockStore }) => {
  mockStore.sessionActive = true;
  await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
  await page.route('**/customers/mfa/status', route => route.fulfill({ json: { totpEnabled: false, webAuthnAvailable: false, recoveryCodesRemaining: 0 } }));
  await page.goto('/index.html');
  await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
  for (const name of ['profile', 'orders', 'wishlist', 'security']) {
    await page.locator('#auth-toggle').click();
    await page.locator(`#account-menu-${name}`).focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('#auth-title')).toBeFocused();
    await audit(page, name);
    await page.keyboard.press('Escape');
    await expect(page.locator('#auth-toggle')).toBeFocused();
  }
});

test('przejście wybór zakupu → logowanie nie pozostawia modalnego tła po Escape', async ({ page, mockStore }) => {
  void mockStore;
  await page.goto('/catalog.html');
  await waitForCatalog(page);
  await page.locator('[data-action="add"][data-product-id="101"]').click();
  await page.locator('#cart-toggle').focus();
  await page.keyboard.press('Enter');
  await page.locator('#cart-preview-checkout').click();
  await page.locator('#cart-checkout').click();
  await tabTo(page, '#checkout-choice-account');
  await page.keyboard.press('Enter');
  await expect(page.locator('#checkout-choice-modal')).toBeHidden();
  await expect(page.locator('#auth-title')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.locator('#cart-checkout')).toBeFocused();
  await expect(page.locator('body')).not.toHaveClass(/store-dialog-open/);
});

test('dialog zachowuje dostęp do formularza przy powiększeniu CSS 200 procent', async ({ page, mockStore }) => {
  void mockStore;
  await page.goto('/index.html');
  await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
  await page.locator('#auth-toggle').click();
  await tabTo(page, '#auth-email');
  await page.keyboard.type('zoom@example.test');
  await tabTo(page, '#auth-forgot-password');
  await expect(page.locator('#auth-forgot-password')).toBeInViewport();
  await page.screenshot({ path: test.info().outputPath('login-zoom-200.png') });
  await page.keyboard.press('Escape');
  await expect(page.locator('#auth-toggle')).toBeFocused();
});
