import { test as base, expect, type Page, type Route } from '@playwright/test';

export const CUSTOMER = {
  id: 7001,
  email: 'playwright@minitech.local',
  firstName: 'Playwright',
  lastName: 'Tester',
  street: 'Testowa 1',
  postalCode: '00-001',
  city: 'Warszawa',
  country: 'Polska',
  phoneNumber: '500600700',
  enabled: true
};

export const PRODUCTS = [
  {
    id: 101,
    sku: 'RAM-KNG-FURY-32GB',
    name: 'Kingston Fury Beast 32GB',
    category: 'RAM',
    manufacturer: 'Kingston',
    model: 'Fury Beast DDR5',
    price: 499.99,
    regularPrice: 599.99,
    discountPercent: 17,
    promotional: true,
    newProduct: true,
    rotationDate: '2026-08-20',
    rotationEndsAt: '2099-12-31T23:59:59Z',
    imageUrl: 'assets/products/ram-photo.png',
    technicalParameters: { type: 'DDR5', capacity: '32GB', speedMHz: 6000 },
    active: true
  },
  {
    id: 102,
    sku: 'GPU-ASU-RTX4070S',
    name: 'ASUS GeForce RTX 4070 SUPER',
    category: 'GPU',
    manufacturer: 'ASUS',
    model: 'Dual RTX 4070 SUPER 12GB',
    price: 2899.99,
    regularPrice: 3299.99,
    discountPercent: 12,
    promotional: true,
    newProduct: true,
    rotationDate: '2026-08-20',
    rotationEndsAt: '2099-12-31T23:59:59Z',
    imageUrl: 'assets/products/gpu-photo.png',
    technicalParameters: { memory: '12GB GDDR6X', interface: 'PCIe 4.0', rayTracing: true },
    active: true
  },
  {
    id: 103,
    sku: 'SSD-SAM-990PRO-2TB',
    name: 'Samsung 990 PRO 2TB',
    category: 'SSD',
    manufacturer: 'Samsung',
    model: '990 PRO',
    price: 699.99,
    regularPrice: 699.99,
    discountPercent: 0,
    promotional: false,
    newProduct: false,
    rotationDate: '2026-08-20',
    rotationEndsAt: '2099-12-31T23:59:59Z',
    imageUrl: 'assets/products/ssd-photo.png',
    technicalParameters: { capacity: '2TB', interface: 'PCIe 4.0 NVMe', readSpeedMBps: 7450 },
    active: true
  }
];

type MockStore = {
  sessionActive: boolean;
  wishlist: Set<number>;
  chatAnswered: boolean;
  orderId: number;
  ordersByIdempotencyKey: Map<string, Record<string, unknown>>;
  paymentsByIdempotencyKey: Map<string, Record<string, unknown>>;
  failFirstPaymentResponseAfterCommit: boolean;
  paymentFailureReturned: boolean;
  inventoryOverrides: Map<number, number>;
  createdOrderCount: number;
  createdPaymentCount: number;
  lastReturnContentType: string | null;
};

type Fixtures = { mockStore: MockStore };

export const test = base.extend<Fixtures>({
  mockStore: async ({ page }, use) => {
    const store: MockStore = {
      sessionActive: false,
      wishlist: new Set<number>(),
      chatAnswered: false,
      orderId: 9001,
      ordersByIdempotencyKey: new Map(),
      paymentsByIdempotencyKey: new Map(),
      failFirstPaymentResponseAfterCommit: false,
      paymentFailureReturned: false,
      inventoryOverrides: new Map(),
      createdOrderCount: 0,
      createdPaymentCount: 0,
      lastReturnContentType: null
    };
    await installMockApi(page, store);
    await use(store);
  }
});

export { expect };

export async function waitForCatalog(page: Page) {
  await expect(page.locator('.product-card').first()).toBeVisible();
}

async function installMockApi(page: Page, store: MockStore) {
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api(?:\/v1)?/, '') || '/';
    const method = request.method();

    if (path === '/customers/refresh' && method === 'POST') {
      return store.sessionActive
        ? json(route, sessionPayload())
        : json(route, { message: 'No refresh session' }, 401);
    }
    if (path === '/customers/login' && method === 'POST') {
      const body = request.postDataJSON();
      if (body.email !== CUSTOMER.email || body.password !== 'Playwright1!') {
        return json(route, { message: 'Invalid credentials' }, 401);
      }
      store.sessionActive = true;
      return json(route, sessionPayload());
    }
    if (path === '/customers/logout' && method === 'POST') {
      store.sessionActive = false;
      return route.fulfill({ status: 204, body: '' });
    }
    if (path === `/customers/${CUSTOMER.id}/profile` && method === 'PUT') {
      return json(route, { ...CUSTOMER, ...request.postDataJSON() });
    }
    if (path === '/products' && method === 'GET') return json(route, PRODUCTS);
    const productMatch = path.match(/^\/products\/(\d+)$/);
    if (productMatch) {
      const product = PRODUCTS.find(row => row.id === Number(productMatch[1]));
      return product ? json(route, product) : json(route, { message: 'Not found' }, 404);
    }
    const inventoryMatch = path.match(/^\/inventory\/items\/(\d+)$/);
    if (inventoryMatch) {
      const productId = Number(inventoryMatch[1]);
      const availableQuantity = store.inventoryOverrides.get(productId) ?? (productId === 102 ? 5 : 12);
      return json(route, { productId, availableQuantity, reservedQuantity: 0 });
    }
    if (path.startsWith('/orders/bestsellers')) return json(route, [{ productId: 101, soldQuantity: 4 }]);
    if (path === `/orders/customer/${CUSTOMER.id}` && method === 'GET') {
      return json(route, [{
        id: 9101,
        status: 'PAID',
        customerEmail: CUSTOMER.email,
        totalPrice: 499.99,
        createdAt: '2026-08-20T10:00:00Z',
        fulfillmentType: 'DELIVERY',
        deliveryMethod: 'STANDARD_COURIER',
        deliveryDate: '2026-08-22',
        deliveryAddress: CUSTOMER,
        items: [{
          productId: 101,
          productName: 'Kingston Fury Beast 32GB',
          sku: 'RAM-KNG-FURY-32GB',
          quantity: 1,
          unitPrice: 499.99,
          totalPrice: 499.99
        }]
      }]);
    }
    if (path.startsWith('/promotions/history/')) return json(route, { lowestPrice: 449.99 });
    if (path.startsWith('/recommendations/products/')) {
      return json(route, PRODUCTS.slice(1).map(product => ({ ...product, productId: product.id })));
    }
    if (path.startsWith('/reviews/products/') && path.endsWith('/summary')) return json(route, { count: 0, averageRating: 0 });
    if (path.startsWith('/reviews/products/')) return json(route, []);
    if (path === '/deliveries/methods') {
      return json(route, [
        { code: 'STANDARD_COURIER', name: 'Kurier standardowy', basePrice: 14.99 },
        { code: 'STORE_PICKUP', name: 'Odbiór osobisty', basePrice: 0 }
      ]);
    }
    if (path === '/deliveries/quote' && method === 'POST') {
      const rawBody = request.postData() || '';
      const subtotalLiteral = rawBody.match(/"subtotal"\s*:\s*(-?\d+(?:\.(\d+))?)/);
      if (subtotalLiteral?.[2] && subtotalLiteral[2].length > 2) {
        return json(route, {
          message: 'Request validation failed',
          details: { subtotal: 'numeric value out of bounds (<17 digits>.<2 digits> expected)' }
        }, 400);
      }
      const body = request.postDataJSON();
      const pickup = body.methodCode === 'STORE_PICKUP';
      const selectedDate = body.requestedDate || '2026-08-22';
      const deliveryCost = pickup ? 0 : 14.99;
      return json(route, {
        methodCode: body.methodCode,
        name: pickup ? 'Odbiór osobisty' : 'Kurier standardowy',
        fulfillmentType: pickup ? 'STORE_PICKUP' : 'DELIVERY',
        deliveryCost,
        freeDelivery: pickup,
        etaMinDays: pickup ? 0 : 1,
        etaMaxDays: pickup ? 1 : 2,
        availableDates: ['2026-08-22', '2026-08-23'],
        selectedDate,
        totalPrice: Number(body.subtotal || 0) + deliveryCost
      });
    }
    if (path === '/promotions/codes/validate' && method === 'POST') {
      const body = request.postDataJSON();
      return body.code === 'WELCOME10'
        ? json(route, { valid: true, code: body.code, discountAmount: 50, discountPercent: 10, message: 'Kod rabatowy zaakceptowany.' })
        : json(route, { message: 'Kod rabatowy jest nieprawidłowy.' }, 400);
    }
    if (path.startsWith(`/wishlists/customer/${CUSTOMER.id}`)) {
      const productMatch = path.match(/\/products\/(\d+)$/);
      if (productMatch && method === 'PUT') store.wishlist.add(Number(productMatch[1]));
      if (productMatch && method === 'DELETE') store.wishlist.delete(Number(productMatch[1]));
      if (productMatch) return route.fulfill({ status: 204, body: '' });
      return json(route, [...store.wishlist].map(productId => ({ productId })));
    }
    if (path === '/orders' && method === 'POST') {
      const body = request.postDataJSON();
      const key = request.headers()['idempotency-key'] || `missing-${store.orderId}`;
      let order = store.ordersByIdempotencyKey.get(key);
      if (!order) {
        order = { id: store.orderId++, status: 'CREATED', productId: body.productId, quantity: body.quantity };
        store.ordersByIdempotencyKey.set(key, order);
        store.createdOrderCount += 1;
      }
      return json(route, order, 201);
    }
    if (path === '/payments' && method === 'POST') {
      const body = request.postDataJSON();
      const key = request.headers()['idempotency-key'] || `order-${body.orderId}`;
      let payment = store.paymentsByIdempotencyKey.get(key);
      if (!payment) {
        payment = { id: 5000 + store.createdPaymentCount + 1, orderId: body.orderId, status: 'SUCCEEDED' };
        store.paymentsByIdempotencyKey.set(key, payment);
        store.createdPaymentCount += 1;
      }
      if (store.failFirstPaymentResponseAfterCommit && !store.paymentFailureReturned) {
        store.paymentFailureReturned = true;
        return json(route, { message: 'Downstream service is unavailable' }, 503);
      }
      return json(route, payment, 201);
    }
    if (path === '/returns' && method === 'POST') {
      store.lastReturnContentType = request.headers()['content-type'] || null;
      if (!store.lastReturnContentType?.toLowerCase().includes('application/json')) {
        return json(route, { message: 'Unsupported Media Type' }, 415);
      }
      return json(route, {
        id: 81,
        ...request.postDataJSON(),
        status: 'NEW',
        createdAt: '2026-08-20T12:00:00Z'
      }, 201);
    }
    if (path === '/chat/messages' && method === 'POST') {
      const body = request.postDataJSON();
      return json(route, { id: 77, ...body, replyMessage: null }, 201);
    }
    if (path === '/chat/messages/77' && method === 'GET') {
      return json(route, {
        id: 77,
        customerName: 'Playwright',
        customerEmail: CUSTOMER.email,
        subject: 'Test czatu',
        message: 'Czy produkt jest dostępny?',
        replyMessage: store.chatAnswered ? 'Tak, produkt jest dostępny.' : null
      });
    }
    return json(route, { message: `Unhandled mock endpoint: ${method} ${path}` }, 404);
  });
}

function sessionPayload() {
  return { accessToken: 'playwright.header.signature', expiresIn: 900, customer: CUSTOMER };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json; charset=utf-8',
    body: JSON.stringify(body)
  });
}
