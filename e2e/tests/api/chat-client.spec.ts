import { test, expect, CUSTOMER } from '../../fixtures/store.fixture';
import type { Page } from '@playwright/test';

async function sendInitial(page: Page, message = 'Question') {
  await page.locator('#chat-launcher').click();
  await page.locator('#chat-name').fill('Guest');
  await page.locator('#chat-email').fill('guest@example.test');
  await page.locator('#chat-message').fill(message);
  await page.locator('#chat-submit').click();
  await expect(page.locator('#chat-thread-id')).toHaveText('Rozmowa #77');
  await expect(page.locator('#chat-submit')).toBeEnabled();
}

test('chat reads and writes use only their capability even while a customer session and cookies exist', async ({ page, context, baseURL, mockStore }) => {
  mockStore.sessionActive = true;
  await context.addCookies([{name: 'chat-fixture', value: 'must-not-be-sent', url: baseURL!}]);
  await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
  const requests: Array<{url: string; method: string; headers: Record<string, string>}> = [];
  let refreshes = 0;
  page.on('request', request => {if (request.url().endsWith('/customers/refresh')) refreshes++;});
  await page.route('**/chat/messages**', async route => {
    const request = route.request();
    requests.push({url: request.url(), method: request.method(), headers: await request.allHeaders()});
    const entries = new URL(request.url()).pathname.endsWith('/entries');
    await route.fulfill({json: entries && request.method() === 'GET'
      ? {entries: [{id: 2, author: 'SUPPORT', message: 'Answer'}], hasMore: false, nextCursor: null}
      : {id: 77, message: 'Question', status: 'ANSWERED'}});
  });
  await page.goto('/index.html');
  await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
  const initialRefreshes = refreshes;
  await sendInitial(page);
  await page.locator('#chat-message').fill('Follow-up'); await page.locator('#chat-submit').click();
  await expect(page.locator('#chat-message')).toHaveValue('');
  await page.keyboard.press('Escape'); await page.locator('#chat-launcher').click();
  await expect.poll(() => requests.some(row => row.method === 'GET' && row.url.endsWith('/chat/messages/77'))).toBe(true);
  const token = requests[0].headers['x-chat-access-token'];
  expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(requests.some(row => row.method === 'POST' && row.url.endsWith('/entries'))).toBe(true);
  for (const row of requests) {
    expect(row.headers['x-chat-access-token']).toBe(token);
    expect(row.headers.authorization).toBeUndefined(); expect(row.headers.cookie).toBeUndefined();
    expect(row.url).not.toContain(token);
  }
  expect(refreshes).toBe(initialRefreshes);
});

test('closing chat cancels old history while an immediate reopen preserves the draft and rejects a late error', async ({ page, mockStore }) => {
  void mockStore;
  let phase = 0, started = false, finished = false, release!: () => void;
  const held = new Promise<void>(resolve => {release = resolve;});
  await page.route('**/chat/messages/77/entries?*', async route => {
    if (phase === 1) {
      started = true; await held;
      await route.fulfill({status: 503, json: {message: 'Old history failure'}}); finished = true; return;
    }
    await route.fulfill({json: {entries: [{id: 2, author: 'SUPPORT', message: phase === 2 ? 'Current answer' : 'Original answer'}], hasMore: false, nextCursor: null}});
  });
  await page.goto('/index.html'); await sendInitial(page);
  await page.keyboard.press('Escape'); phase = 1; await page.locator('#chat-launcher').click();
  await expect.poll(() => started).toBe(true);
  await page.locator('#chat-message').fill('Unsent draft');
  const cancelled = page.waitForEvent('requestfailed', request => request.url().includes('/chat/messages/77/entries?'));
  await page.locator('#chat-close').click(); await cancelled;
  phase = 2; await page.locator('#chat-launcher').click();
  await expect(page.locator('#chat-thread-reply')).toHaveText('Current answer');
  release(); await expect.poll(() => finished).toBe(true);
  await expect(page.locator('#chat-message')).toHaveValue('Unsent draft');
  await expect(page.locator('#chat-history-status')).toHaveText('');
  await expect(page.locator('#chat-live-status')).not.toHaveClass(/error/);
});

test('switching to a new conversation aborts the old summary and prevents its late 404 from clearing the new thread', async ({ page, mockStore }) => {
  void mockStore;
  let posts = 0, hold = false, started = false, finished = false, release!: () => void;
  const held = new Promise<void>(resolve => {release = resolve;});
  const tokens: string[] = [];
  await page.route('**/chat/messages', route => {
    tokens.push(route.request().headers()['x-chat-access-token']);
    return route.fulfill({json: {id: ++posts === 1 ? 77 : 78, message: route.request().postDataJSON().message, status: 'NEW'}});
  });
  await page.route('**/chat/messages/77', async route => {
    if (hold) {started = true; await held; await route.fulfill({status: 404, json: {message: 'Old conversation missing'}}); finished = true; return;}
    await route.fulfill({json: {id: 77, message: 'Question', status: 'NEW'}});
  });
  await page.route('**/chat/messages/78/entries?*', route => route.fulfill({json: {entries: [], hasMore: false, nextCursor: null}}));
  await page.goto('/index.html'); await sendInitial(page);
  await page.keyboard.press('Escape'); hold = true; await page.locator('#chat-launcher').click();
  await expect.poll(() => started).toBe(true);
  const cancelled = page.waitForEvent('requestfailed', request => request.url().endsWith('/chat/messages/77'));
  await page.locator('#chat-new-conversation').click(); await cancelled;
  await expect(page.locator('#chat-name')).toBeVisible();
  await page.locator('#chat-message').fill('Second conversation'); await page.locator('#chat-submit').click();
  await expect(page.locator('#chat-thread-id')).toHaveText('Rozmowa #78');
  release(); await expect.poll(() => finished).toBe(true);
  await expect(page.locator('#chat-thread-id')).toHaveText('Rozmowa #78');
  await expect(page.locator('#chat-thread')).toBeVisible();
  expect(tokens).toHaveLength(2); expect(tokens[1]).not.toBe(tokens[0]);
});

test('a missing stored conversation clears its credentials and restores the initial required fields', async ({ page, mockStore }) => {
  void mockStore;
  await page.addInitScript(() => {
    const base = location.port === '18070' ? `${location.protocol}//${location.hostname}:18080/api` : `${location.origin}/api`;
    const key = `minitech.chat.conversationId.${base}`;
    localStorage.setItem(key, '77'); localStorage.setItem(`${key}.accessToken`, 'A'.repeat(43));
  });
  await page.route('**/chat/messages/77', route => route.fulfill({status: 404, json: {message: 'Missing'}}));
  await page.goto('/index.html'); await page.locator('#chat-launcher').click();
  await expect(page.locator('#chat-thread')).toBeHidden();
  await expect(page.locator('#chat-name')).toBeVisible(); await expect(page.locator('#chat-name')).toHaveJSProperty('required', true);
  await expect(page.locator('#chat-email')).toHaveJSProperty('required', true);
  expect(await page.evaluate(() => Object.keys(localStorage).filter(key => key.startsWith('minitech.chat.conversationId.')))).toEqual([]);
});

test('the chat module loads and opens on every storefront page without JavaScript errors', async ({ page, mockStore }) => {
  void mockStore;
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  for (const path of ['/index.html', '/catalog.html', '/product.html?id=101', '/order-detail.html?id=9101', '/delivery.html']) {
    await page.goto(path);
    await page.locator('#chat-launcher').click(); await expect(page.locator('#chat-panel')).toBeVisible();
    await page.keyboard.press('Escape'); await expect(page.locator('#chat-panel')).toBeHidden();
  }
  expect(errors).toEqual([]);
});
