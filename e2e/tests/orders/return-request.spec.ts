import { test, expect, CUSTOMER } from '../../fixtures/store.fixture';

test.describe('Zwroty i reklamacje', () => {
  test('formularz wysyła zgłoszenie jako application/json', async ({ page, mockStore }) => {
    await page.addInitScript(() => {
      localStorage.setItem('minitech-auth:last-activity', String(Date.now()));
    });

    mockStore.sessionActive = true;
    await page.goto('/order-detail.html?id=9101');

    const form = page.locator('#return-request-form');
    await expect(form).toBeVisible();
    await expect(page.locator('#return-request-type option')).toHaveCount(1);
    await expect(page.locator('#return-request-type')).toHaveValue('COMPLAINT');
    await page.locator('#return-request-reason').fill('Produkt nie spełnia oczekiwań');
    await page.locator('#return-request-description').fill('Proszę o przyjęcie zwrotu produktu.');
    await form.locator('button[type="submit"]').click();

    await expect(page.locator('#return-request-status'))
      .toContainText('Zgłoszenie zapisane');
    expect(mockStore.lastReturnContentType).toContain('application/json');
  });

  test('częściowy zwrot przesyła wybrane ilości rzeczywistych pozycji bez zlecania płatności', async ({page,mockStore}) => {
    mockStore.sessionActive=true;
    await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity',String(Date.now())));
    await page.route('**/orders/9101',route => route.fulfill({json:{id:9101,customerId:CUSTOMER.id,customerEmail:CUSTOMER.email,
      status:'COMPLETED',paymentState:'PAID',lifecycleReviewRequired:false,items:[
        {id:11,productId:101,productName:'First item',quantity:3,unitPrice:10,totalPrice:30},
        {id:12,productId:103,productName:'Second item',quantity:1,unitPrice:10,totalPrice:10}]}}));
    let payload:any=null;let payments=0;
    page.on('request',request => {
      if(request.method()==='POST' && new URL(request.url()).pathname.endsWith('/returns')) payload=request.postDataJSON();
      if(request.method()==='POST' && new URL(request.url()).pathname.endsWith('/payments')) payments++;
    });
    await page.goto('/order-detail.html?id=9101');
    await expect(page.locator('#return-request-type')).toHaveValue('RETURN');
    await page.locator('.return-quantity').nth(0).fill('2');await page.locator('.return-quantity').nth(1).fill('0');
    await page.locator('#return-request-reason').fill('One item');await page.locator('#return-request-description').fill('Partial intake');
    await page.locator('#return-request-form button[type=submit]').click();
    await expect(page.locator('#return-request-status')).toContainText('Zgłoszenie zapisane: #81');
    expect(payload.items).toEqual([{orderItemId:11,quantity:2}]);expect(payments).toBe(0);
    await expect(page.locator('#return-request-status')).toContainText('nie oznacza automatycznego zwrotu płatności');
  });

  test('puste ilości zachowują formularz i nie tworzą zgłoszenia', async ({page,mockStore}) => {
    mockStore.sessionActive=true;
    await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity',String(Date.now())));
    let writes=0;page.on('request',request => {if(request.method()==='POST' && new URL(request.url()).pathname.endsWith('/returns')) writes++;});
    await page.goto('/order-detail.html?id=9101');
    await page.locator('.return-quantity').fill('0');
    await page.locator('#return-request-reason').fill('Keep reason');await page.locator('#return-request-description').fill('Keep description');
    await page.locator('#return-request-form button[type=submit]').click();
    await expect(page.locator('#return-request-status')).toContainText('co najmniej jedną sztukę');
    await expect(page.locator('#return-request-reason')).toHaveValue('Keep reason');expect(writes).toBe(0);
  });

  test('nierozliczone pobranie nie pokazuje formularza zwrotu mimo zakończonej dostawy', async ({page,mockStore}) => {
    mockStore.sessionActive=true;
    await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity',String(Date.now())));
    await page.route('**/orders/9101',route => route.fulfill({json:{id:9101,customerId:CUSTOMER.id,status:'COMPLETED',paymentState:'COLLECTION_DUE',lifecycleReviewRequired:false,items:[{id:11,productId:101,quantity:1}]}}));
    await page.goto('/order-detail.html?id=9101');await expect(page.locator('#order-detail-subtitle')).toContainText('#9101');
    await expect(page.locator('#return-request-form')).toHaveCount(0);
  });
});
