import { test, expect, CUSTOMER } from '../../fixtures/store.fixture';

test.describe('Logowanie i rejestracja', () => {
  test.beforeEach(async ({ page, mockStore }) => {
    void mockStore;
    await page.goto('/index.html');
    await page.locator('#auth-toggle').click();
  });

  test('błędne dane pokazują czytelny komunikat zamiast samego HTTP 401', async ({ page }) => {
    await page.locator('#auth-email').fill('wrong@minitech.local');
    await page.locator('#auth-password').fill('WrongPassword1!');
    await page.locator('#auth-submit').click();
    await expect(page.locator('#account-status')).toContainText('Nieprawidłowy e-mail lub hasło.');
  });

  test('powtórzenie hasła występuje tylko podczas rejestracji i waliduje zgodność', async ({ page }) => {
    await expect(page.locator('#auth-password-confirm-label')).toBeHidden();
    await page.locator('#auth-register-choice').click();
    await expect(page.locator('#auth-password-confirm-label')).toBeVisible();
    await page.locator('#auth-password').fill('Playwright1!');
    await page.locator('#auth-password-confirm').fill('Different1!');
    await expect(page.locator('#auth-password-confirm')).toHaveJSProperty('validationMessage', 'Hasła muszą być identyczne.');
    await page.locator('#auth-login-choice').click();
    await expect(page.locator('#auth-password-confirm-label')).toBeHidden();
  });

  test('poprawne logowanie otwiera menu konta', async ({ page }) => {
    await page.locator('#auth-email').fill(CUSTOMER.email);
    await page.locator('#auth-password').fill('Playwright1!');
    await page.locator('#auth-submit').click();
    await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
    await page.locator('#auth-toggle').click();
    await expect(page.locator('#account-menu')).toBeVisible();
    await expect(page.locator('#account-menu-profile')).toBeVisible();
    await expect(page.locator('#account-menu-orders')).toBeVisible();
    await expect(page.locator('#account-menu-security')).toBeVisible();
  });
});
