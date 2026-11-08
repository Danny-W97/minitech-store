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

  test('parametr api w adresie strony nie przekierowuje danych logowania', async ({ page }) => {
    const requests: string[] = [];
    page.on('request', request => requests.push(request.url()));
    await page.goto('/index.html?api=https://attacker.example/api');
    await page.locator('#auth-toggle').click();
    await page.locator('#auth-email').fill(CUSTOMER.email);
    await page.locator('#auth-password').fill('Playwright1!');
    const loginRequest = page.waitForRequest(request => request.url().endsWith('/customers/login'));
    await page.locator('#auth-submit').click();
    const frontend = new URL(page.url());
    const expectedOrigin = frontend.port === '18070' ? 'http://127.0.0.1:18080' : frontend.origin;
    expect(new URL((await loginRequest).url()).origin).toBe(expectedOrigin);
    await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
    expect(requests.some(url => url.startsWith('https://attacker.example/'))).toBe(false);
  });

  test('wygasła konfiguracja TOTP pozwala rozpocząć ponownie i czyści klucz', async ({ page }) => {
    let starts = 0;
    await page.route('**/customers/mfa/**', route => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('/status')) return route.fulfill({ json: { totpEnabled: false, webAuthnAvailable: false, recoveryCodesRemaining: 0 } });
      if (path.endsWith('/enroll')) {
        starts++;
        return route.fulfill({ json: { secret: 'TEMPORARY-SECRET', otpauthUri: 'otpauth://test', qrCodeDataUri: '', expiresIn: 600 } });
      }
      return route.fulfill({ status: 410, json: { message: 'Setup expired', details: { code: 'MFA_ENROLLMENT_EXPIRED' } } });
    });
    await page.locator('#auth-email').fill(CUSTOMER.email);
    await page.locator('#auth-password').fill('Playwright1!');
    await page.locator('#auth-submit').click();
    await page.locator('#auth-toggle').click();
    await page.locator('#account-menu-security').click();
    await page.locator('#auth-totp-start').click();
    await page.locator('#auth-totp-code').fill('123456');
    await page.locator('#auth-totp-confirm').click();
    await expect(page.locator('#auth-security-status')).toContainText('Konfiguracja TOTP wygasła');
    await expect(page.locator('#auth-totp-secret')).toHaveValue('');
    await expect(page.locator('#auth-totp-setup')).toBeHidden();
    await expect(page.locator('#auth-totp-qr')).not.toHaveAttribute('src');
    await expect(page.locator('#auth-totp-start')).toBeEnabled();
    await page.locator('#auth-totp-start').click();
    await expect(page.locator('#auth-totp-code')).toBeVisible();
    expect(starts).toBe(2);
  });

  test('błędny kod TOTP nie uruchamia odświeżenia sesji ani ponowienia żądania', async ({ page }) => {
    let proofs = 0;
    const refreshes: string[] = [];
    await page.route('**/customers/mfa/**', route => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('/status')) return route.fulfill({ json: { totpEnabled: false, webAuthnAvailable: false, recoveryCodesRemaining: 0 } });
      if (path.endsWith('/enroll')) return route.fulfill({ json: { secret: 'TEMPORARY-SECRET', qrCodeDataUri: '', expiresIn: 600 } });
      proofs++;
      return route.fulfill({ status: 401, json: { message: 'Invalid MFA code', details: { code: 'MFA_INVALID_CODE' } } });
    });
    await page.locator('#auth-email').fill(CUSTOMER.email);
    await page.locator('#auth-password').fill('Playwright1!');
    await page.locator('#auth-submit').click();
    await page.locator('#auth-toggle').click();
    await page.locator('#account-menu-security').click();
    await page.locator('#auth-totp-start').click();
    page.on('request', request => { if (request.url().endsWith('/customers/refresh')) refreshes.push(request.url()); });
    await page.locator('#auth-totp-code').fill('123456');
    await page.locator('#auth-totp-confirm').click();
    await expect(page.locator('#auth-security-status')).toContainText('Nieprawidłowy kod TOTP');
    expect(proofs).toBe(1);
    expect(refreshes).toHaveLength(0);
  });

  test('równoległe dokumenty współdzielą blokadę odświeżania cookie sesji', async ({ page }) => {
    test.slow();
    await page.locator('#auth-email').fill(CUSTOMER.email);
    await page.locator('#auth-password').fill('Playwright1!');
    await page.locator('#auth-submit').click();
    await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
    await expect(page.locator('#auth-modal')).toBeHidden();
    let active = 0;
    let peak = 0;
    let completed = 0;
    await page.route('**/customers/refresh', async route => {
      active++;
      peak = Math.max(peak, active);
      await new Promise(resolve => setTimeout(resolve, 200));
      await route.fulfill({ json: { accessToken: 'test-access', expiresIn: 900, customer: CUSTOMER } });
      active--;
      completed++;
    });
    await page.evaluate(() => {
      for (let index = 0; index < 2; index++) {
        const frame = document.createElement('iframe');
        frame.src = `/index.html?session-check=${index}`;
        document.body.append(frame);
      }
    });
    await expect.poll(() => completed, { timeout: 25_000 }).toBe(2);
    expect(peak).toBe(1);
  });
});
