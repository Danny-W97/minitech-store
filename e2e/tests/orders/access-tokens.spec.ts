import { test, expect } from '../../fixtures/store.fixture';

test('czat zapisuje losowy klucz i przesyła go przy wznowieniu rozmowy', async ({ page }) => {
  let capability: string | undefined;
  let restoredCapability: string | undefined;
  await page.route('**/chat/messages**', async route => {
    if (route.request().method() === 'POST') capability = route.request().headers()['x-chat-access-token'];
    else restoredCapability = route.request().headers()['x-chat-access-token'];
    await route.fulfill({ json: { id: 101, customerName: 'Guest', customerEmail: 'guest@example.com',
      subject: 'Question', message: 'Hello', replyMessage: null, createdAt: new Date().toISOString() } });
  });
  await page.goto('/index.html');
  await page.locator('#chat-launcher').click();
  await page.locator('#chat-name').fill('Guest');
  await page.locator('#chat-email').fill('guest@example.com');
  await page.locator('#chat-message').fill('Hello');
  await page.locator('#chat-submit').click();
  await expect(page.locator('#chat-status')).toContainText('Wiadomość wysłana');
  expect(capability).toMatch(/^[A-Za-z0-9_-]{43}$/);
  await page.reload();
  await expect.poll(() => restoredCapability).toBe(capability);
});

test('PDF faktury jest pobierany z nagłówkiem sesji klienta', async ({ page, mockStore }) => {
  mockStore.sessionActive = true;
  await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
  await page.route('**/invoices/order/9101', route => route.fulfill({ json: {
    id: 44, orderId: 9101, invoiceNumber: 'FV/44', netAmount: 100, vatAmount: 23, grossAmount: 123
  } }));
  let authorization: string | undefined;
  await page.route('**/invoices/order/9101/pdf', route => {
    authorization = route.request().headers().authorization;
    return route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.4\n' });
  });
  await page.goto('/order-detail.html?id=9101');
  const downloaded = page.waitForEvent('download');
  await page.locator('#download-invoice').click();
  expect((await downloaded).suggestedFilename()).toBe('invoice-9101.pdf');
  expect(authorization).toMatch(/^Bearer /);
});

test('zamówienie do uzgodnienia pokazuje komunikat i nie proponuje faktury ani zwrotu', async ({ page, mockStore }) => {
  mockStore.sessionActive = true;
  await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
  await page.route('**/orders/9101', route => route.fulfill({ json: {
    id: 9101, status: 'PAID', paymentState: 'PAID', lifecycleReviewRequired: true,
    totalPrice: 10, items: [], deliveryAddress: {}, invoiceRequested: true
  } }));
  let invoiceReads = 0;
  await page.route('**/invoices/order/9101', route => { invoiceReads += 1; return route.fulfill({ json: { id: 44 } }); });
  await page.goto('/order-detail.html?id=9101');
  await expect(page.locator('#order-detail-status')).toContainText('wymaga weryfikacji');
  await expect(page.locator('#download-invoice')).toHaveCount(0);
  await expect(page.locator('#return-request-form')).toHaveCount(0);
  expect(invoiceReads).toBe(0);
});
