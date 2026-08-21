import { test, expect, waitForCatalog } from '../../fixtures/store.fixture';

async function addProduct(page: import('@playwright/test').Page, name: string) {
  await page.locator('.product-card').filter({ hasText: name }).locator('button[data-action="add"]').click();
}

async function openCheckout(page: import('@playwright/test').Page) {
  await page.locator('#cart-area').hover();
  await expect(page.locator('#cart-preview')).toBeVisible();
  await page.locator('#cart-preview-checkout').click();
  await page.locator('#cart-checkout').click();
}

test.describe('Finalizacja zamówienia', () => {
  test.beforeEach(async ({ page, mockStore }) => {
    void mockStore;
    await page.goto('/catalog.html');
    await waitForCatalog(page);
  });

  test('gość kupuje koszyk poniżej 1000 zł i widzi pełne podsumowanie ceny', async ({ page }) => {
    await addProduct(page, 'Kingston Fury Beast 32GB');
    await openCheckout(page);
    await page.locator('#checkout-choice-guest').click();

    await page.locator('#delivery-first-name').fill('Jan');
    await page.locator('#delivery-last-name').fill('Kowalski');
    await page.locator('#delivery-street').fill('Testowa 1');
    await page.locator('#delivery-postal-code').fill('00-001');
    await page.locator('#delivery-city').fill('Warszawa');
    await page.locator('#delivery-email').fill('guest@minitech.local');
    await page.locator('#delivery-phone').fill('+48 123 456 789');
    await expect(page.locator('#delivery-date option')).not.toHaveCount(0);

    await page.locator('#promotion-code').fill('WELCOME10');
    await page.locator('#promotion-code-apply').click();
    await expect(page.locator('#promotion-code-feedback')).toContainText('Rabat: 50,00 zł');
    await expect(page.locator('#summary-discount')).toContainText('50,00 zł');
    await expect(page.locator('#summary-delivery-gross')).toHaveText('14,99 zł');
    await expect(page.locator('#summary-net')).not.toHaveText('0,00 zł');
    await expect(page.locator('#summary-vat')).not.toHaveText('0,00 zł');
    await expect(page.locator('#summary-gross')).toHaveText('464,98 zł');

    await page.locator('#checkout-to-summary').click();
    await expect(page.locator('#checkout-summary-step')).toBeVisible();
    await expect(page.locator('#checkout-summary-email')).toHaveText('guest@minitech.local');
    await expect(page.locator('#summary-step-discount')).toContainText('50,00 zł');
    await expect(page.locator('#summary-step-delivery')).toHaveText('14,99 zł');
    await expect(page.locator('#summary-step-gross')).toHaveText('464,98 zł');
    await page.locator('#checkout-summary-submit').click();
    await expect(page.locator('#catalog-message')).toContainText('Zakup zakończony pomyślnie.');
    await expect(page.locator('#cart-count')).toHaveText('0');
  });

  test('zamówienie powyżej 1000 zł wymaga konta', async ({ page }) => {
    await addProduct(page, 'ASUS GeForce RTX 4070 SUPER');
    await openCheckout(page);
    await expect(page.locator('#auth-modal')).toBeVisible();
    await expect(page.locator('#toast')).toContainText('powyżej 1 000 zł wymagane jest konto');
    await expect(page.locator('#checkout-choice-modal')).toBeHidden();
  });

  test('usuwa produkt wyprzedany po wejściu do podsumowania', async ({ page, mockStore }) => {
    await addProduct(page, 'Kingston Fury Beast 32GB');
    await openCheckout(page);
    await page.locator('#checkout-choice-guest').click();

    await page.locator('#delivery-first-name').fill('Jan');
    await page.locator('#delivery-last-name').fill('Kowalski');
    await page.locator('#delivery-street').fill('Testowa 1');
    await page.locator('#delivery-postal-code').fill('00-001');
    await page.locator('#delivery-city').fill('Warszawa');
    await page.locator('#delivery-email').fill('stock@minitech.local');
    await page.locator('#delivery-phone').fill('+48 123 456 789');
    await page.locator('#checkout-to-summary').click();

    await expect(page.locator('#checkout-summary-step')).toBeVisible();
    mockStore.inventoryOverrides.set(101, 0);
    await page.locator('#checkout-summary-submit').click();

    await expect(page.locator('#checkout-summary-step')).toBeHidden();
    await expect(page.locator('#cart-count')).toHaveText('0');
    await expect(page.locator('#cart-empty')).toBeVisible();
    await expect(page.locator('#toast')).toContainText('wyprzedany i usunięty z koszyka');
    expect(mockStore.createdOrderCount).toBe(0);
    expect(mockStore.createdPaymentCount).toBe(0);
  });

  test('usuwa z koszyka produkt, którego stan spadł do zera przed opłaceniem', async ({ page, mockStore }) => {
    await addProduct(page, 'Kingston Fury Beast 32GB');
    await openCheckout(page);
    await page.locator('#checkout-choice-guest').click();

    await page.locator('#delivery-first-name').fill('Jan');
    await page.locator('#delivery-last-name').fill('Kowalski');
    await page.locator('#delivery-street').fill('Testowa 1');
    await page.locator('#delivery-postal-code').fill('00-001');
    await page.locator('#delivery-city').fill('Warszawa');
    await page.locator('#delivery-email').fill('soldout@minitech.local');
    await page.locator('#delivery-phone').fill('+48 123 456 789');
    await page.locator('#checkout-to-summary').click();

    await expect(page.locator('#checkout-summary-step')).toBeVisible();
    mockStore.inventoryOverrides.set(101, 0);
    await page.locator('#checkout-summary-submit').click();

    await expect(page.locator('#checkout-summary-step')).toBeHidden();
    await expect(page.locator('#cart-count')).toHaveText('0');
    await expect(page.locator('#cart-empty')).toBeVisible();
    await expect(page.locator('#toast')).toContainText('wyprzedany i usunięty z koszyka');
    expect(mockStore.createdOrderCount).toBe(0);
    expect(mockStore.createdPaymentCount).toBe(0);
  });

  test('usuwa z koszyka pozycję z ilością zero przed przejściem do płatności', async ({ page, mockStore }) => {
    await page.addInitScript(() => {
      localStorage.setItem('minitech-cart:guest', JSON.stringify([
        { id: 101, name: 'Kingston Fury Beast 32GB', sku: 'RAM-KNG-FURY-32GB', price: 499.99, quantity: 0 }
      ]));
    });
    await page.reload();
    await waitForCatalog(page);
    await openCheckout(page);
    await page.locator('#checkout-choice-guest').click();

    await page.locator('#delivery-first-name').fill('Jan');
    await page.locator('#delivery-last-name').fill('Kowalski');
    await page.locator('#delivery-street').fill('Testowa 1');
    await page.locator('#delivery-postal-code').fill('00-001');
    await page.locator('#delivery-city').fill('Warszawa');
    await page.locator('#delivery-email').fill('zero@minitech.local');
    await page.locator('#delivery-phone').fill('+48 123 456 789');
    await page.locator('#checkout-to-summary').click();

    await expect(page.locator('#checkout-summary-step')).toBeHidden();
    await expect(page.locator('#cart-count')).toHaveText('0');
    await expect(page.locator('#cart-empty')).toBeVisible();
    await expect(page.locator('#toast')).toContainText('ilość 0 i została usunięta z koszyka');
    expect(mockStore.createdOrderCount).toBe(0);
    expect(mockStore.createdPaymentCount).toBe(0);
  });

  test('odbiór osobisty ukrywa adres i nie nalicza kosztu dostawy', async ({ page }) => {
    await addProduct(page, 'Kingston Fury Beast 32GB');
    await openCheckout(page);
    await page.locator('#checkout-choice-guest').click();
    await page.locator('#delivery-first-name').fill('Jan');
    await page.locator('#delivery-last-name').fill('Kowalski');
    await page.locator('#delivery-email').fill('pickup@minitech.local');
    await page.locator('#delivery-phone').fill('+48 123 456 789');
    await page.locator('#delivery-method').selectOption('STORE_PICKUP');
    await expect(page.locator('#delivery-address-fields')).toBeHidden();
    await expect(page.locator('#pickup-location-field')).toBeVisible();
    await expect(page.locator('#summary-delivery-gross')).toHaveText('0,00 zł');
  });

  test('wycena dostawy wysyła kwotę koszyka zaokrągloną do dwóch miejsc', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('minitech-cart:guest', JSON.stringify([
        { id: 9101, name: 'Produkt A', sku: 'TEST-A', price: 0.1, quantity: 1 },
        { id: 9102, name: 'Produkt B', sku: 'TEST-B', price: 0.2, quantity: 1 }
      ]));
    });

    const quoteRequest = page.waitForRequest(request => {
      if (!request.url().includes('/api/deliveries/quote')) return false;
      return request.postDataJSON()?.subtotal === 0.3;
    });
    await page.reload();
    await waitForCatalog(page);

    const request = await quoteRequest;
    expect(request.postDataJSON().subtotal).toBe(0.3);
    await expect(page.locator('#delivery-quote')).not.toContainText('Request validation failed');
    await expect(page.locator('#summary-delivery-gross')).toHaveText('14,99 zł');
  });

  test('faktura firmowa wymaga osobnego imienia i nazwiska oraz 10-cyfrowego NIP', async ({ page }) => {
    await addProduct(page, 'Kingston Fury Beast 32GB');
    await openCheckout(page);
    await page.locator('#checkout-choice-guest').click();

    await page.locator('#delivery-first-name').fill('Danny');
    await page.locator('#delivery-last-name').fill('Meteor');
    await page.locator('#delivery-street').fill('Testowa 1');
    await page.locator('#delivery-postal-code').fill('00-001');
    await page.locator('#delivery-city').fill('Warszawa');
    await page.locator('#delivery-email').fill('invoice@minitech.local');
    await page.locator('#delivery-phone').fill('+48 123 456 789');
    await page.locator('#invoice-requested').check();
    await page.locator('#billing-company').check();

    await expect(page.locator('label').filter({ has: page.locator('#billing-buyer-name') })).toContainText('Imię i nazwisko');
    await expect(page.locator('label').filter({ has: page.locator('#billing-buyer-name') })).not.toContainText('nazwa firmy');
    await page.locator('#billing-buyer-name').fill('Danny Meteor');
    await page.locator('#billing-company-name').fill('Falka sp. z o.o.');
    await page.locator('#billing-tax-id').fill('123456789');
    await page.locator('#billing-street').fill('Firmowa 2');
    await page.locator('#billing-postal-code').fill('00-002');
    await page.locator('#billing-city').fill('Warszawa');

    await page.locator('#checkout-to-summary').click();
    await expect(page.locator('#checkout-summary-step')).toBeHidden();
    await expect(page.locator('#billing-tax-id')).toHaveJSProperty(
      'validationMessage',
      'NIP musi składać się dokładnie z 10 cyfr.'
    );

    await page.locator('#billing-tax-id').fill('1234567890');
    await page.locator('#checkout-to-summary').click();
    await expect(page.locator('#checkout-summary-step')).toBeVisible();
  });

  test('ponowienie po utracie odpowiedzi płatności wykorzystuje te same klucze idempotencji', async ({ page, mockStore }) => {
    mockStore.failFirstPaymentResponseAfterCommit = true;
    await addProduct(page, 'Kingston Fury Beast 32GB');
    await openCheckout(page);
    await page.locator('#checkout-choice-guest').click();

    await page.locator('#delivery-first-name').fill('Jan');
    await page.locator('#delivery-last-name').fill('Kowalski');
    await page.locator('#delivery-street').fill('Testowa 1');
    await page.locator('#delivery-postal-code').fill('00-001');
    await page.locator('#delivery-city').fill('Warszawa');
    await page.locator('#delivery-email').fill('retry@minitech.local');
    await page.locator('#delivery-phone').fill('+48 123 456 789');
    await page.locator('#checkout-to-summary').click();
    await page.locator('#checkout-summary-submit').click();

    await expect(page.locator('#checkout-status')).toContainText('Downstream service is unavailable');
    expect(mockStore.createdOrderCount).toBe(1);
    expect(mockStore.createdPaymentCount).toBe(1);

    await page.locator('#checkout-summary-submit').click();
    await expect(page.locator('#catalog-message')).toContainText('Zakup zakończony pomyślnie.');
    await expect(page.locator('#cart-count')).toHaveText('0');
    expect(mockStore.createdOrderCount).toBe(1);
    expect(mockStore.createdPaymentCount).toBe(1);
  });
});
