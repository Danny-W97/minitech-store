import { test as base, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

const test = base.extend<{}, { ccUrl: string }>({
  ccUrl: [async ({}, use) => {
    const process = spawn(global.process.env.CC_TEST_PYTHON || 'python3', ['scripts/qa/fixtures/control_center_browser.py'], {
      windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    });
    const lines = createInterface({ input: process.stdout });
    const ready = new Promise<string>((resolve, reject) => {
      process.once('error', reject);
      process.once('exit', code => reject(new Error(`CC fixture exited: ${code}`)));
      lines.once('line', line => { try { resolve(JSON.parse(line).url); } catch (error) { reject(error); } });
    });
    let errors = '';
    process.stderr.on('data', chunk => { errors = (errors + chunk).slice(-4000); });
    try { await use(await ready); }
    catch (error) { throw new Error(`${error}\n${errors}`); }
    finally { lines.close(); process.kill(); }
  }, { scope: 'worker' }],
});

test('@ai-delivery-review external turns show exact history, cancel without sending and require a new confirmation', async ({page,ccUrl}) => {
  const deliveries:any[]=[];let previews=0;
  await page.route('**/api/ai/chat/preview',route=>{
    const body=route.request().postDataJSON();previews++;
    return route.fulfill({json:{mode:'external',provider:'fixture',model:'safe-model',target:'https://provider.example.invalid/v1/chat/completions',historyIncluded:true,
      wirePayload:{messages:[{role:'system',content:'Static context'},...body.history,{role:'user',content:body.message}]},reviewToken:'review-'+previews}});
  });
  await page.route('**/api/ai/chat',route=>{deliveries.push(route.request().postDataJSON());return route.fulfill({json:{reply:'Earlier local personal data <img src=x onerror="window.reviewAttack=1">'}});});
  await page.goto(`${ccUrl}/#assistant`);
  await page.locator('#ai-input').fill('Cancelled question');await page.locator('#ai-send-btn').click();
  const dialog=page.locator('#ai-delivery-review');await expect(dialog).toBeVisible();
  await expect(dialog.locator('[data-ai-review-payload]')).toContainText('Cancelled question');
  expect(deliveries).toHaveLength(0);await dialog.locator('[data-ai-review-cancel]').click();
  await expect(dialog).not.toBeVisible();expect(deliveries).toHaveLength(0);
  await page.locator('#ai-input').fill('First accepted');await page.locator('#ai-send-btn').click();await expect(dialog).toBeVisible();
  await dialog.locator('[data-ai-review-confirm]').click();await expect.poll(()=>deliveries.length).toBe(1);
  expect(deliveries[0]).toEqual({message:'First accepted',history:[],reviewToken:'review-2'});
  await expect(page.locator('#ai-send-btn')).toBeEnabled();
  await page.locator('#ai-input').fill('Second accepted');await page.locator('#ai-send-btn').click();await expect(dialog).toBeVisible();
  await expect(dialog.locator('[data-ai-review-payload]')).toContainText('Earlier local personal data');
  await expect(dialog.locator('[data-ai-review-payload] img')).toHaveCount(0);
  expect(await page.evaluate(()=>(window as any).reviewAttack)).toBeUndefined();expect(deliveries).toHaveLength(1);
  expect((await new AxeBuilder({page}).include('#ai-delivery-review').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze()).violations).toEqual([]);
  await page.screenshot({path:test.info().outputPath('ai-delivery-preview.png')});
  await dialog.locator('[data-ai-review-confirm]').click();await expect.poll(()=>deliveries.length).toBe(2);
  expect(deliveries[1].reviewToken).toBe('review-3');
  await expect(dialog.locator('[data-ai-review-payload]')).toHaveText('');
  expect(await page.evaluate(()=>Object.keys(localStorage).some(key=>key.includes('review')))).toBe(false);
});

test('@ai-delivery-review Escape and session expiry close and erase preview without delivery', async ({page,ccUrl})=>{
  let deliveries=0;
  await page.route('**/api/ai/chat/preview',route=>route.fulfill({json:{mode:'external',provider:'fixture',model:'fixture',target:'https://provider.example.invalid',historyIncluded:false,wirePayload:{private:'review-only-private'},reviewToken:'synthetic-only'}}));
  await page.route('**/api/ai/chat',route=>{deliveries++;return route.fulfill({json:{reply:'unexpected'}});});
  await page.goto(`${ccUrl}/#assistant`);
  const dialog=page.locator('#ai-delivery-review');
  await page.locator('#ai-input').fill('Escape');await page.locator('#ai-send-btn').click();await expect(dialog).toBeVisible();
  await page.keyboard.press('Escape');await expect(dialog).not.toBeVisible();expect(deliveries).toBe(0);
  await page.locator('#ai-input').fill('Expire');await page.locator('#ai-send-btn').click();await expect(dialog).toBeVisible();
  await page.evaluate(async()=>{
    const {api}=await import('/panel-services.js?v=20261027-a42');
    await api('/api/test/expire',{method:'POST',body:'{}'});
    await api('/api/jobs').catch(()=>{});
  });
  await expect(page.locator('#panel-session-status')).toBeVisible();await expect(dialog).not.toBeVisible();
  expect(deliveries).toBe(0);expect(await page.content()).not.toContain('review-only-private');
});

test('ending one panel session forgets its view and rejects late personal data',async({page,ccUrl})=>{
  const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));
  let token='';page.on('request',request=>{if(request.url().includes('/api/jobs'))token=request.headers()['x-control-center-token']||token;});
  await page.goto(ccUrl);await expect(page.locator('#profile-value')).toHaveText('dev');
  await expect.poll(()=>token).not.toBe('');
  const invalidSend=await page.evaluate(async()=>{
    const {api}=await import('/panel-services.js?v=20261027-a42');
    try{await api('/api/data/invoices/send-email',{method:'POST',body:'{}'});return 200;}catch(error){return (error as any).status;}
  });
  expect(invalidSend).toBe(400);
  const other=await page.context().newPage();await other.goto(ccUrl);await expect(other.locator('#profile-value')).toHaveText('dev');
  await page.evaluate(async()=>{
    const {runtime}=await import('/panel-state.js?v=20261027-a42');runtime.cachedCustomers=[{email:'private-session@example.test'}];
    (document.querySelector('#dispatch-recipient') as HTMLInputElement).value='private-session@example.test';
    document.querySelector('#conversation-detail')!.textContent='private-session@example.test';
  });
  let finish!:()=>void;const held=new Promise<void>(resolve=>finish=resolve);let started=false;
  await page.route('**/api/test/late-customer',async route=>{started=true;await held;await route.fulfill({json:{email:'late-private@example.test'}}).catch(()=>{});});
  await page.evaluate(async()=>{
    const {api}=await import('/panel-services.js?v=20261027-a42');
    const {runtime}=await import('/panel-state.js?v=20261027-a42');
    (window as any).latePrivate=api('/api/test/late-customer').then(row=>{runtime.cachedCustomers=[row];}).catch(()=>{});
  });
  await expect.poll(()=>started).toBe(true);
  await page.locator('#panel-logout').click();await expect(page.locator('#panel-session-status')).toBeVisible();
  finish();await page.evaluate(()=>(window as any).latePrivate);
  await expect.poll(async()=>(await page.request.get(ccUrl+'/api/jobs',{headers:{'X-Control-Center-Token':token}})).status()).toBe(401);
  const snapshot=await page.evaluate(async()=>{
    const {runtime}=await import('/panel-state.js?v=20261027-a42');
    return JSON.stringify({dom:document.body.innerHTML,customers:runtime.cachedCustomers});
  });
  expect(snapshot).not.toContain('private-session@example.test');
  await other.locator('.nav-item[data-view="jobs"]').click();await other.locator('#jobs-refresh').click();await expect(other.locator('#panel-session-status')).toHaveCount(0);
  expect(errors).toEqual([]);await page.screenshot({path:test.info().outputPath('session-ended.png')});await other.close();
});

test('chat history preserves drafts, shows both replies and retries with one key', async ({ page, ccUrl }) => {
  let lastEntryId=3;let fail=true;
  const keys:string[]=[];
  await page.route('**/api/chat/messages?**',route=>route.fulfill({json:{messages:[{id:77,lastEntryId,customerName:'Guest',customerEmail:'guest@example.test',subject:'Question',message:'Initial question',status:'ANSWERED',replyMessage:'Second answer'},{id:78,lastEntryId:1,customerName:'Other guest',customerEmail:'other@example.test',message:'Other question',status:'NEW'}],nextCursor:'0',hasMore:false}}));
  await page.route('**/api/chat/77/entries?**',route=>route.fulfill({json:{entries:[{id:2,author:'SUPPORT',message:'First answer'},{id:3,author:'SUPPORT',message:'Second answer'}],nextCursor:null,hasMore:false}}));
  await page.route('**/api/chat/77/reply',route=>{
    keys.push(route.request().postDataJSON().requestId);
    return fail?route.fulfill({status:503,json:{error:'Lost receipt'}}):route.fulfill({json:{id:77}});
  });
  await page.goto(`${ccUrl}/#chat`);
  await page.locator('[data-chat="77"]').click();
  await expect(page.locator('#conversation-history .message-bubble')).toHaveCount(2);
  const draft=page.locator('#conversation-detail textarea');
  await draft.fill('Reply draft');lastEntryId=4;
  await page.locator('#chat-refresh').click();
  await expect(draft).toHaveValue('Reply draft');
  await page.locator('#conversation-detail .reply-form button').click();
  await expect(page.locator('#toast')).toContainText('Lost receipt');
  await page.locator('[data-chat="78"]').click();await expect(draft).toHaveValue('');
  await draft.fill('Other conversation draft');
  await page.locator('[data-chat="77"]').click();await expect(draft).toHaveValue('Reply draft');
  lastEntryId=5;await page.locator('#chat-refresh').click();
  await expect(draft).toHaveValue('Reply draft');
  fail=false;await page.locator('#conversation-detail .reply-form button').click();
  await expect(draft).toHaveValue('');
  expect(keys).toHaveLength(2);expect(keys[0]).toBe(keys[1]);
});

test('chat panel UI elements are ordered within sidebar without breaking layout grid', async ({ page, ccUrl }) => {
  await page.route('**/api/chat/messages?**', route => route.fulfill({
    json: {
      messages: [
        { id: 101, lastEntryId: 1, customerName: 'Anna Nowak', customerEmail: 'anna@example.test', subject: 'Pytanie o zamówienie', message: 'Kiedy wysyłka?', status: 'NEW' },
        { id: 102, lastEntryId: 2, customerName: 'Piotr Wiśniewski', customerEmail: 'piotr@example.test', subject: 'Faktura', message: 'Poproszę o duplikat', status: 'ANSWERED' }
      ],
      nextCursor: '0',
      hasMore: false
    }
  }));
  await page.goto(`${ccUrl}/#chat`);
  const chatLayout = page.locator('.chat-layout');
  await expect(chatLayout).toBeVisible();
  const sidebar = page.locator('.chat-sidebar');
  await expect(sidebar).toBeVisible();
  const filters = sidebar.locator('#chat-list-filters');
  await expect(filters).toBeVisible();
  await expect(filters.locator('#chat-page-search')).toBeVisible();
  await expect(filters.locator('#chat-page-status')).toBeVisible();
  const directChildren = await chatLayout.locator('> *').count();
  expect(directChildren).toBe(2);
  await expect(sidebar.locator('[data-chat="101"]')).toBeVisible();
  await expect(sidebar.locator('[data-chat="102"]')).toBeVisible();
  await expect(page.locator('#conversation-detail .empty-state')).toContainText('Wybierz rozmowę z listy');
  await sidebar.locator('[data-chat="101"]').click();
  await expect(page.locator('#conversation-detail h3')).toContainText('Pytanie o zamówienie');
  await page.screenshot({ path: '/mnt/c/Users/dancy/.gemini/antigravity/brain/9b5e300a-0122-4580-ae0d-dc71ecb21ad3/chat_ordered.png' });
});

test('customers show real fields, a persistent failure, retry and a genuine empty result', async ({ page, ccUrl }) => {
  let failed = true;
  await page.route('**/api/data/customers?**', route => {
    if (failed) return route.fulfill({status: 503, json: {error: 'Lista klientów jest niedostępna.'}});
    const search = new URL(route.request().url()).searchParams.get('search');
    return route.fulfill({json: {customers: search ? [] : [{id: 7, email: 'jan@audit.example', first_name: 'Jan', last_name: 'Kowalski', city: 'Warszawa', created_at: '2026-10-26T07:00:00Z'}], hasMore: false, nextCursor: '0'}});
  });
  await page.goto(`${ccUrl}/#data`);
  await page.locator('#data-tab-customers-btn').click();
  await expect(page.locator('#customers-error')).toBeVisible();
  await expect(page.locator('#customers-tbody')).toContainText('Lista klientów niedostępna');
  await expect(page.locator('#customers-tbody')).not.toContainText('Brak zarejestrowanych');
  failed = false;
  await page.locator('#customers-retry').click();
  await expect(page.locator('#customers-error')).toBeHidden();
  await expect(page.locator('#customers-tbody')).toContainText('Jan Kowalski');
  await expect(page.locator('#customers-tbody')).toContainText('Warszawa');
  await expect(page.locator('#customers-table th')).toHaveCount(5);
  await expect(page.locator('#customers-sort-select option[value^="balance"], #customers-sort-select option[value^="username"]')).toHaveCount(0);
  await page.locator('#customers-search-input').fill('missing');
  await expect(page.locator('#customers-tbody')).toContainText('Brak klientów dla podanego filtra');
  await expect(page.locator('#customers-error')).toBeHidden();
});

test('an old failed customer search cannot overwrite a newer successful search', async ({ page, ccUrl }) => {
  let releaseOld!: () => void;
  let oldStarted = false;
  let oldFinished = false;
  const oldWait = new Promise<void>(resolve => { releaseOld = resolve; });
  await page.route('**/api/data/customers?**', async route => {
    const search = new URL(route.request().url()).searchParams.get('search');
    if (search === 'old') {
      oldStarted = true; await oldWait;
      await route.fulfill({status: 503, json: {error: 'Stara awaria'}});
      oldFinished = true;
      return;
    }
    return route.fulfill({json: {customers: [{id: 8, email: 'new@audit.example', first_name: 'Nowy', last_name: 'Klient', city: 'Gdańsk', created_at: '2026-10-26T07:00:00Z'}], hasMore: false, nextCursor: '0'}});
  });
  await page.goto(`${ccUrl}/#data`);
  await page.locator('#data-tab-customers-btn').click();
  await expect(page.locator('#customers-tbody')).toContainText('Nowy Klient');
  await page.locator('#customers-search-input').fill('old');
  await expect.poll(() => oldStarted).toBe(true);
  const cancelled = page.waitForEvent('requestfailed', request => request.url().includes('search=old'));
  await page.locator('#customers-search-input').fill('new');
  await cancelled;
  await expect(page.locator('#customers-table')).toHaveAttribute('aria-busy', 'false');
  await expect(page.locator('#customers-tbody')).toContainText('Nowy Klient');
  releaseOld(); await expect.poll(() => oldFinished).toBe(true);
  await expect(page.locator('#customers-error')).toBeHidden();
  await expect(page.locator('#customers-tbody')).toContainText('Nowy Klient');
});

test('filter changes block old page cursors until the new result arrives', async ({ page, ccUrl }) => {
  let release!: () => void;
  let started = false;
  const held = new Promise<void>(resolve => { release = resolve; });
  const queries: URL[] = [];
  await page.route('**/api/data/orders?**', async route => {
    const url = new URL(route.request().url()); queries.push(url);
    const filtered = url.searchParams.get('search') === 'new';
    if (filtered) { started = true; await held; }
    await route.fulfill({json: {orders: [{id: filtered ? 77 : 999, status: 'PAID', customerEmail: filtered ? 'new@audit.example' : 'old@audit.example'}], hasMore: !filtered, nextCursor: '9223372036854775806'}});
  });
  await page.goto(`${ccUrl}/#data`);
  const next = page.locator('#list-page-orders [data-next]');
  await expect(next).toBeEnabled();
  await page.locator('#orders-search-input').fill('new');
  await expect(next).toBeDisabled();
  await expect.poll(() => started).toBe(true);
  await expect(page.locator('#list-page-orders')).toHaveAttribute('aria-busy', 'true');
  release();
  await expect(page.locator('#orders-tbody')).toContainText('#77');
  await expect(page.locator('#list-page-orders')).toHaveAttribute('aria-busy', 'false');
  expect(queries).toHaveLength(2);
  expect(queries[1].searchParams.get('cursor')).toBe('0');
  await expect(page.locator('#list-page-orders')).toContainText('Strona 1');
});

test('retrying a failed filter starts at zero instead of restoring a cursor from the old query', async ({ page, ccUrl }) => {
  let filteredCalls = 0;
  const cursors: string[] = [];
  await page.route('**/api/data/orders?**', route => {
    const url = new URL(route.request().url());
    const cursor = url.searchParams.get('cursor')!;
    const filtered = url.searchParams.get('search') === 'new';
    if (filtered) {
      cursors.push(cursor);
      if (++filteredCalls === 1) return route.fulfill({status: 503, json: {error: 'New filter unavailable'}});
    }
    return route.fulfill({json: {orders: [{id: filtered ? 77 : cursor === '0' ? 999 : 499, customerEmail: 'new@audit.example', status: 'PAID'}], hasMore: !filtered && cursor === '0', nextCursor: '500'}});
  });
  await page.goto(`${ccUrl}/#data`);
  await page.locator('#list-page-orders [data-next]').click();
  await expect(page.locator('#orders-tbody')).toContainText('#499');
  await page.locator('#orders-search-input').fill('new');
  await expect(page.locator('#toast')).toContainText('New filter unavailable');
  await expect(page.locator('#list-page-orders')).toContainText('Strona 1');
  await page.locator('#data-refresh-btn').click();
  await expect(page.locator('#orders-tbody')).toContainText('#77');
  expect(cursors).toEqual(['0', '0']);
});

test('session expiry cancels a pending list and blocks its late result', async ({ page, ccUrl }) => {
  let release!: () => void;
  let started = false, finished = false;
  const held = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/data/customers?**', async route => {
    started = true; await held;
    await route.fulfill({json: {customers: [{id: 11, email: 'late@audit.example'}], hasMore: false}});
    finished = true;
  });
  await page.goto(`${ccUrl}/#data`);
  await page.locator('#data-tab-customers-btn').click();
  await expect.poll(() => started).toBe(true);
  const cancelled = page.waitForEvent('requestfailed', request => request.url().includes('/api/data/customers?'));
  await page.route('**/api/health', route => route.fulfill({status: 401, json: {code: 'CC_SESSION_EXPIRED', error: 'Sesja panelu wygasła.'}}));
  await page.locator('#refresh-button').click();
  await cancelled;
  await expect(page.locator('#terminal-input')).toBeDisabled();
  release(); await expect.poll(() => finished).toBe(true);
  await expect(page.locator('#customers-tbody')).not.toContainText('late@audit.example');
  await expect(page.locator('#panel-session-status')).toBeVisible();
});

test('business pages preserve opaque cursors and send filters to the server', async ({ page, ccUrl }) => {
  const queries: URL[] = [];
  await page.route('**/api/data/orders?**', route => {
    const url = new URL(route.request().url()); queries.push(url);
    const first = url.searchParams.get('cursor') === '0';
    return route.fulfill({ json: { orders: [{ id: first ? 999 : 499, customerEmail: 'older@audit.example', status: 'PAID', totalPrice: 10 }], hasMore: first, nextCursor: first ? '9223372036854775806' : '0' } });
  });
  await page.goto(`${ccUrl}/#data`);
  await expect(page.locator('#orders-tbody')).toContainText('#999');
  await page.locator('#list-page-orders [data-next]').click();
  await expect(page.locator('#orders-tbody')).toContainText('#499');
  expect(queries.at(-1)?.searchParams.get('cursor')).toBe('9223372036854775806');
  await page.locator('#list-page-orders [data-prev]').click();
  await expect(page.locator('#orders-tbody')).toContainText('#999');
  await page.locator('#orders-search-input').fill('older');
  await expect.poll(() => queries.at(-1)?.searchParams.get('search')).toBe('older');
  expect(queries.at(-1)?.searchParams.get('cursor')).toBe('0');
  await expect(page.locator('#orders-tbody')).toContainText('older@audit.example');
});

test('@operator-intents stock receipt keeps the same operation after a lost reply and page reload', async ({ page, ccUrl }) => {
  let available = 10;
  const commands: Array<{operationId: string; quantity: number; productId: string}> = [];
  await page.route('**/api/data/inventory?**', route => route.fulfill({ json: {
    inventory: [{product_id: 101, name: 'Test stock', sku: 'SKU', available_quantity: available, reserved_quantity: 0}],
    hasMore: false, nextCursor: '0'
  } }));
  await page.route('**/api/data/inventory/restock', async route => {
    const command = route.request().postDataJSON(); commands.push(command);
    if (commands.length === 1) { available += command.quantity; await route.abort('failed'); return; }
    await route.fulfill({ json: {ok: true, status: 'COMMITTED', kind: 'RECEIPT', operationId: command.operationId,
      quantity: command.quantity, productId: 101, availableQuantity: available} });
  });
  await page.goto(`${ccUrl}/#data`);
  await page.locator('#data-tab-inventory-btn').click();
  await page.locator('.restock-btn[data-product-id="101"][data-qty="10"]').click();
  await expect(page.locator('.restock-btn[data-qty="10"]')).toHaveText('Ponów +10');
  await expect(page.locator('.restock-btn[data-qty="50"]')).toBeDisabled();
  expect(commands[0].operationId).toMatch(/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
  await page.reload(); await page.locator('#data-tab-inventory-btn').click();
  await expect(page.locator('.restock-btn[data-qty="10"]')).toHaveText('Ponów +10');
  await page.locator('.restock-btn[data-qty="10"]').click();
  await expect(page.locator('.restock-btn[data-qty="10"]')).toHaveText('+10');
  expect(commands).toHaveLength(2);expect(commands[1]).toEqual(commands[0]);expect(available).toBe(20);
  expect(await page.evaluate(() => localStorage.getItem('minitech-stock-receipt-v1:dev:101'))).toBeNull();
});

test('a failed next page leaves the displayed business window available', async ({ page, ccUrl }) => {
  let fail = true;
  await page.route('**/api/data/orders?**', route => {
    const first = new URL(route.request().url()).searchParams.get('cursor') === '0';
    if (!first && fail) { fail = false; return route.fulfill({ status: 503, json: { error: 'Page unavailable' } }); }
    return route.fulfill({ json: { orders: [{ id: first ? 999 : 499, status: 'PAID', totalPrice: 10 }], hasMore: first, nextCursor: first ? '500' : '0' } });
  });
  await page.goto(`${ccUrl}/#data`);
  await expect(page.locator('#orders-tbody')).toContainText('#999');
  await page.locator('#list-page-orders [data-next]').click();
  await expect(page.locator('#orders-tbody')).toContainText('#999');
  await expect(page.locator('#list-page-orders')).toContainText('Strona 1');
  await page.locator('#list-page-orders [data-next]').click();
  await expect(page.locator('#orders-tbody')).toContainText('#499');
});

test('invoice correction totals include all history while details load one page on demand', async ({ page, ccUrl }) => {
  const cursors: string[] = [];
  await page.route('**/api/data/invoices?**', route => route.fulfill({ json: { invoices: [{ id: 99, orderId: 999, invoiceNumber: 'FV-99', buyerName: 'Buyer', grossAmount: 200000, correctionCount: 100001, correctionGrossDelta: -100001 }], hasMore: false, nextCursor: '0' } }));
  await page.route('**/api/data/invoices/99/corrections?**', route => {
    const cursor = new URL(route.request().url()).searchParams.get('cursor') || '0'; cursors.push(cursor);
    return route.fulfill({ json: { items: [{ id: cursor === '0' ? 88 : 87, correctionNumber: cursor === '0' ? 'KOR-NEW' : 'KOR-OLD', correctionGross: -1, reason: 'Correction' }], hasMore: cursor === '0', nextCursor: cursor === '0' ? '88' : '0' } });
  });
  await page.goto(`${ccUrl}/#data`);
  await page.locator('#data-tab-invoices-btn').click();
  await expect(page.locator('.btn-corr-toggle')).toContainText('100001');
  expect(cursors).toEqual([]);
  await page.locator('.btn-corr-toggle').click();
  await expect(page.locator('#correction-history-99')).toContainText('KOR-NEW');
  await page.locator('#list-page-correction-99 [data-next]').click();
  await expect(page.locator('#correction-history-99')).toContainText('KOR-OLD');
  await expect(page.locator('#correction-history-99')).not.toContainText('KOR-NEW');
  expect(cursors).toEqual(['0','88']);
});

test('dispatch can page corrections independently after opening the invoice tab', async ({ page, ccUrl }) => {
  await page.route('**/api/data/invoices?**', route => route.fulfill({ json: { invoices: [{ id: 99, orderId: 999, invoiceNumber: 'FV-99', grossAmount: 10 }], hasMore: false, nextCursor: '0' } }));
  await page.route('**/api/data/invoices/dispatch-history', route => route.fulfill({ json: { history: [] } }));
  await page.route('**/api/data/corrections?**', route => {
    const first = new URL(route.request().url()).searchParams.get('cursor') === '0';
    return route.fulfill({ json: { items: [{ id: first ? 88 : 87, invoiceId: 777, orderId: 777, correctionNumber: first ? 'KOR-NEW' : 'KOR-OLD', correctionGross: -1 }], hasMore: first, nextCursor: first ? '88' : '0' } });
  });
  await page.goto(`${ccUrl}/#data`);
  await page.locator('#data-tab-invoices-btn').click();
  await expect(page.locator('#invoices-tbody')).toContainText('FV-99');
  await page.locator('[data-subtab="dispatch"]').click();
  await expect(page.locator('#dispatch-tbody')).toContainText('KOR-NEW');
  await page.locator('#list-page-dispatch-corrections [data-next]').click();
  await expect(page.locator('#dispatch-tbody')).toContainText('KOR-OLD');
});

test('language switching updates every view and survives reload with the same session protection', async ({ page, ccUrl }) => {
  let language = 'pl';
  const errors: string[] = [];
  const sessionHeaders: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/bootstrap', async route => {
    const response = await route.fetch();
    await route.fulfill({response, json: {...await response.json(), language}});
  });
  await page.route('**/api/language', route => {
    language = route.request().postDataJSON().language;
    sessionHeaders.push(route.request().headers()['x-control-center-token']);
    return route.fulfill({json: {language, actions: []}});
  });
  const titles = [
    ['dashboard', 'Operations Center', 'Centrum operacyjne'],
    ['services', 'Services & Endpoints', 'Usługi i endpointy'],
    ['operations', 'Control Operations', 'Operacje Centrali'],
    ['data', 'Business Data Inspector', 'Eksplorator Danych Biznesowych'],
    ['events', 'Events, Queues & Trace Timeline', 'Zdarzenia, Kolejki i Śledzenie Transakcji'],
    ['chaos', 'Failure Simulator & Chaos Engineering', 'Symulator Awarii & Chaos Engineering'],
    ['chat', 'Customer Support', 'Obsługa klienta'],
    ['assistant', 'AI Architecture Assistant', 'Asystent Architektury AI'],
    ['tests', 'Quality Gates & Tests', 'Testy i jakość'],
    ['jobs', 'Jobs & Logs', 'Zadania i logi'],
    ['terminal', 'WSL Terminal', 'Terminal WSL'],
    ['about', 'About MiniTech Platform', 'O platformie MiniTech'],
  ];
  await page.goto(ccUrl);
  await expect(page.locator('#lang-value')).toHaveText('PL');
  for (const [code, titleIndex] of [['en', 1], ['pl', 2]] as const) {
    await page.locator('#lang-switch-button').click();
    await expect(page.locator('html')).toHaveAttribute('lang', code);
    await expect(page.locator('#refresh-button')).toHaveAttribute('aria-label', code === 'en' ? 'Refresh data' : 'Odśwież dane');
    for (const row of titles) {
      await page.locator(`#navigation [data-view="${row[0]}"]`).click();
      await expect(page.locator('#view-title')).toHaveText(row[titleIndex]);
      await expect(page.locator(`#view-${row[0]}`)).toHaveClass(/active/);
    }
    await expect(page.locator('#about-intro-title')).toHaveText(code === 'en' ? 'About MiniTech Platform' : 'O architekturze platformy MiniTech');
    await page.locator('#navigation [data-view="jobs"]').click();
    await expect(page.locator('#job-history')).toHaveText(code === 'en' ? 'No jobs.' : 'Brak zadań.');
    await expect(page.locator('#job-output')).toHaveText(code === 'en' ? 'Select a job to view its log.' : 'Wybierz zadanie, aby zobaczyć log.');
    await page.locator('#navigation [data-view="data"]').click();
    await expect(page.locator('#list-page-orders')).toContainText(code === 'en' ? 'Page 1' : 'Strona 1');
    await page.locator('#data-tab-customers-btn').click();
    await expect(page.locator('#customers-table th[data-sort="name"]')).toContainText(code === 'en' ? 'Full name' : 'Imię i nazwisko');
    await expect(page.locator('#customers-sort-select option[value="city_asc"]')).toHaveText(code === 'en' ? 'City (A → Z)' : 'Miasto (A → Z)');
  }
  expect(sessionHeaders).toHaveLength(2);
  expect(sessionHeaders[0]).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(sessionHeaders[1]).toBe(sessionHeaders[0]);
  await page.locator('#lang-switch-button').click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await page.reload();
  await expect(page.locator('#lang-value')).toHaveText('EN');
  await expect(page.locator('#view-title')).toHaveText('Business Data Inspector');
  expect(errors).toEqual([]);
});

test('a failed language save retains labels and the active terminal draft', async ({ page, ccUrl }) => {
  await page.route('**/api/language', route => route.fulfill({status: 503, json: {error: 'Language save unavailable'}}));
  await page.goto(`${ccUrl}/#terminal`);
  await page.locator('#terminal-start').click();
  await expect(page.locator('#terminal-output')).toContainText('SAFE TERMINAL');
  await page.locator('#terminal-input').fill('UNSENT FIXTURE');
  await page.locator('#lang-switch-button').click();
  await expect(page.locator('#toast')).toContainText('Language save unavailable');
  await expect(page.locator('html')).toHaveAttribute('lang', 'pl');
  await expect(page.locator('#lang-value')).toHaveText('PL');
  await expect(page.locator('#view-title')).toHaveText('Terminal WSL');
  await expect(page.locator('#terminal-input')).toHaveValue('UNSENT FIXTURE');
  await expect(page.locator('#terminal-input')).toBeEnabled();
  await page.locator('#terminal-form button').click();
  await expect(page.locator('#terminal-output')).toContainText('UNSENT FIXTURE');
});

test('normal panel protects reads and keeps its session only in memory', async ({ page, ccUrl }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const bootstrap = page.waitForResponse(response => response.url() === `${ccUrl}/api/bootstrap`);
  const health = page.waitForResponse(response => response.url() === `${ccUrl}/api/health`);
  await page.goto(ccUrl);
  const { token } = await (await bootstrap).json();
  const response = await health;
  expect(response.status()).toBe(200);
  expect((await response.request().allHeaders())['x-control-center-token']).toBe(token);
  expect(response.headers()['access-control-allow-origin']).toBeUndefined();
  expect(await page.context().cookies()).toEqual([]);
  const stored = await page.evaluate(() => JSON.stringify([localStorage, sessionStorage]));
  expect(stored).not.toContain(token);
  expect(errors).toEqual([]);
});

test('terminal creation, input and polling use the panel session', async ({ page, ccUrl }) => {
  await page.goto(`${ccUrl}/#terminal`);
  await page.locator('#terminal-start').click();
  await expect(page.locator('#terminal-output')).toContainText('SAFE TERMINAL');
  await page.locator('#terminal-input').fill('SAFE INPUT');
  await page.locator('#terminal-form button').click();
  await expect(page.locator('#terminal-output')).toContainText('SAFE INPUT');
});

test('local PDF links download with the header and no token in the URL', async ({ page, ccUrl }) => {
  const health = page.waitForResponse(response => response.url().endsWith('/api/health'));
  await page.goto(ccUrl);
  await health;
  await page.evaluate(() => {
    const link = document.createElement('a');
    link.href = '/api/test/document.pdf'; link.textContent = 'Safe PDF'; link.download = 'safe.pdf';
    document.querySelector('#view-dashboard')!.appendChild(link);
  });
  const request = page.waitForRequest(request => request.url().endsWith('/api/test/document.pdf'));
  const download = page.waitForEvent('download');
  await page.getByText('Safe PDF', { exact: true }).click();
  expect((await request).headers()['x-control-center-token']).toHaveLength(43);
  expect((await download).suggestedFilename()).toBe('safe.pdf');
  expect(page.url()).toBe(`${ccUrl}/`);
});

test('invoice ZIP button downloads through an authenticated request', async ({ page, ccUrl }) => {
  await page.goto(`${ccUrl}/#data`);
  await page.locator('#data-tab-invoices-btn').click();
  const request = page.waitForRequest(request => request.url().endsWith('/api/data/invoices/export-zip'));
  const download = page.waitForEvent('download');
  await page.locator('#invoices-export-zip-btn').click();
  expect((await request).headers()['x-control-center-token']).toHaveLength(43);
  expect((await download).suggestedFilename()).toBe('faktury.zip');
  expect(page.url()).toBe(`${ccUrl}/#data`);
});

test('invoice ZIP range is forwarded and export errors allow retry', async ({ page, ccUrl }) => {
  const queries: URL[] = [];
  await page.route('**/api/data/invoices/export-zip?**', route => {
    queries.push(new URL(route.request().url()));
    if (queries.length===1) return route.fulfill({status:413,json:{error:'Narrow the export date range'}});
    return route.fulfill({contentType:'application/zip',body:Buffer.from('UEsFBgAAAAAAAAAAAAAAAAAAAAAAAA==','base64')});
  });
  await page.goto(`${ccUrl}/#data`);
  await page.locator('#data-tab-invoices-btn').click();
  await page.locator('#invoices-export-from').fill('2026-01-01T00:00');
  await page.locator('#invoices-export-to').fill('2026-02-01T00:00');
  const button=page.locator('#invoices-export-zip-btn'); await button.click();
  await expect(page.locator('#toast')).toContainText('Narrow the export date range');
  await expect(button).toBeEnabled();
  const download=page.waitForEvent('download'); await button.click(); await download;
  expect(queries).toHaveLength(2);
  expect(queries[0].searchParams.get('from')).toMatch(/Z$/);
  expect(Date.parse(queries[0].searchParams.get('to')!)).toBeGreaterThan(Date.parse(queries[0].searchParams.get('from')!));
});

test('a pending ZIP cannot trigger a download after another request expires the session', async ({ page, ccUrl }) => {
  let release!: () => void;
  let started = false;
  const held = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/data/invoices/export-zip', async route => {
    started = true;
    await held;
    await route.fulfill({contentType: 'application/zip', body: Buffer.from('UEsFBgAAAAAAAAAAAAAAAAAAAAAAAA==', 'base64')});
  });
  const downloads: string[] = [];
  page.on('download', download => downloads.push(download.suggestedFilename()));
  await page.goto(`${ccUrl}/#data`);
  await page.locator('#data-tab-invoices-btn').click();
  const button = page.locator('#invoices-export-zip-btn');
  await button.click();
  await expect.poll(() => started).toBe(true);
  await expect(button).toBeDisabled();
  await page.route('**/api/health', route => route.fulfill({status: 401, json: {code: 'CC_SESSION_EXPIRED', error: 'Sesja panelu wygasła.'}}));
  await page.locator('#refresh-button').click();
  await expect(page.locator('#terminal-input')).toBeDisabled();
  release();
  await expect(page.locator('#panel-session-status')).toBeVisible();
  expect(downloads).toEqual([]);
});

test('a document redirect cannot forward the panel session to another origin', async ({ page, ccUrl }) => {
  const foreignRequests: string[] = [];
  const downloads: string[] = [];
  await page.route('https://audit-redirect.example.invalid/**', route => {
    foreignRequests.push(route.request().url());
    return route.fulfill({body: 'unexpected redirect'});
  });
  await page.route('**/api/data/invoices/export-zip', route => route.fulfill({
    status: 302, headers: {location: 'https://audit-redirect.example.invalid/collect'},
  }));
  page.on('download', download => downloads.push(download.suggestedFilename()));
  await page.goto(`${ccUrl}/#data`);
  await page.locator('#data-tab-invoices-btn').click();
  const button = page.locator('#invoices-export-zip-btn');
  await button.click();
  await expect(page.locator('#toast')).toContainText('fetch');
  await expect(button).toBeEnabled();
  expect(foreignRequests).toEqual([]);
  expect(downloads).toEqual([]);
});

test('expired session blocks work and reload obtains a fresh session', async ({ page, ccUrl }) => {
  const bootstrap = page.waitForResponse(response => response.url().endsWith('/api/bootstrap'));
  const health = page.waitForResponse(response => response.url().endsWith('/api/health'));
  await page.goto(`${ccUrl}/#services`);
  const { token } = await (await bootstrap).json();
  await health;
  await expect(page.locator('#health-refresh')).toBeEnabled();
  const expired = await page.request.post(`${ccUrl}/api/test/expire`, { data: {}, headers: { 'X-Control-Center-Token': token } });
  expect(expired.status()).toBe(200);
  const denied = page.waitForResponse(response => response.url().endsWith('/api/health') && response.status() === 401);
  await page.locator('#health-refresh').click();
  await denied;
  await expect(page.locator('#toast')).toContainText('Sesja panelu');
  const terminalRequests: string[] = [];
  page.on('request', request => { if (request.url().endsWith('/api/terminal')) terminalRequests.push(request.url()); });
  await expect(page.locator('#terminal-start')).toBeDisabled();
  await page.locator('#terminal-start').dispatchEvent('click');
  await expect(page.locator('#panel-session-status')).toBeVisible();
  expect(terminalRequests).toEqual([]);
  await page.reload();
  await page.locator('#navigation [data-view="terminal"]').click();
  await page.locator('#terminal-start').click();
  await expect(page.locator('#terminal-output')).toContainText('SAFE TERMINAL');
});

test('browser rebinding host is rejected before a session is issued', async ({ page, ccUrl }) => {
  const address = ccUrl.replace('127.0.0.1', 'attacker.test');
  const response = await page.goto(`${address}/api/bootstrap`);
  expect(response?.status()).toBe(403);
  const body = await response!.json();
  expect(body.code).toBe('CC_INVALID_HOST');
  expect(body.token).toBeUndefined();
});

test('another localhost origin cannot bootstrap or run a terminal', async ({ page, ccUrl }) => {
  await page.goto(ccUrl.replace('127.0.0.1', 'localhost'));
  const result = await page.evaluate(async url => {
    const attempt = async (path: string, method: string) => {
      try { await fetch(url + path, { method, mode: 'cors' }); return 'unexpected success'; }
      catch { return 'blocked'; }
    };
    return Promise.all([attempt('/api/bootstrap', 'GET'), attempt('/api/terminal', 'POST')]);
  }, ccUrl);
  expect(result).toEqual(['blocked', 'blocked']);
});

async function paletteReady(page: Page, ccUrl: string, view = 'dashboard') {
  await page.goto(`${ccUrl}/#${view}`);
  const trigger = page.locator('#cmd-palette-btn');
  await expect(trigger).toHaveAttribute('aria-haspopup', 'dialog');
  return {trigger, dialog: page.locator('#command-palette-dialog'), input: page.locator('#cmd-palette-input')};
}

async function paletteActions(page: Page, actions: object[]) {
  await page.route('**/api/bootstrap', async route => {
    const response = await route.fetch();
    await route.fulfill({response, json: {...await response.json(), actions}});
  });
}

test('@command-palette keyboard navigation keeps its DOM, handles empty results and restores focus', async ({ page, ccUrl }) => {
  expect(page.context().browser()?.browserType().name()).toBe(process.env.CC_TEST_BROWSER || 'chromium');
  const {trigger, dialog, input} = await paletteReady(page, ccUrl, 'operations');
  const opener = page.locator('#operation-search'); await opener.focus();
  await page.keyboard.press('Control+k'); await expect(input).toBeFocused();
  await expect(input).toHaveAttribute('aria-expanded', 'true');
  const options = dialog.getByRole('option'); await expect(options).toHaveCount(14);
  const first = await options.first().elementHandle();
  await page.keyboard.press('ArrowUp'); await expect(options.last()).toHaveAttribute('aria-selected', 'true');
  await page.keyboard.press('ArrowDown'); await expect(options.first()).toHaveAttribute('aria-selected', 'true');
  expect(await first!.evaluate(node => node.isConnected)).toBe(true);
  await page.keyboard.press('Tab'); await expect(input).toBeFocused();
  await page.keyboard.press('Shift+Tab'); await expect(input).toBeFocused();
  const audit = await new AxeBuilder({page}).include('#command-palette-dialog').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(audit.violations.filter(issue => ['serious', 'critical'].includes(issue.impact || '')).map(issue => issue.id)).toEqual([]);
  await input.fill('missing command fixture'); await expect(options).toHaveCount(0);
  await expect(page.locator('#cmd-palette-list-status')).toBeVisible(); await expect(input).not.toHaveAttribute('aria-activedescendant', /.+/);
  await page.keyboard.press('ArrowDown'); await page.keyboard.press('Enter'); await expect(dialog).toBeVisible();
  await page.keyboard.press('Escape'); await expect(opener).toBeFocused();
  await expect(trigger).toHaveAttribute('aria-expanded', 'false');
});

test('@command-palette filtering finds a view by its domain keywords and keeps selection accessible', async ({ page, ccUrl }) => {
  const {trigger, dialog, input} = await paletteReady(page, ccUrl);
  await trigger.click(); await input.fill('KLIENCI');
  const option = dialog.getByRole('option'); await expect(option).toHaveCount(1);
  await expect(option).toContainText('Eksplorator');
  const selectedId = await option.getAttribute('id'); await expect(input).toHaveAttribute('aria-activedescendant', selectedId!);
  await expect(page.locator('#cmd-palette-list-status')).toBeHidden();
  await page.keyboard.press('Enter'); await expect(dialog).toBeHidden();
  await expect(page.locator('#view-data')).toHaveClass(/active/); await expect(trigger).toBeFocused();
});

test('@command-palette distinct operation IDs stay available and selection never bypasses danger confirmation', async ({ page, ccUrl }) => {
  await paletteActions(page, [
    {id: 'fixture-long', title: 'Wyczyść dane testowe', group: 'Fixture', description: 'Owned test', fields: [], danger: false},
    {id: 'fixture-short', title: 'Wyczyść dane', group: 'Fixture', description: 'Owned test', fields: [], danger: true},
    {id: 'fixture-same', title: 'Wyczyść dane', group: 'Fixture', description: 'Owned test', fields: [], danger: true}
  ]);
  let writes = 0;
  page.on('request', request => {if (request.method() === 'POST' && request.url().endsWith('/api/jobs')) writes++;});
  const {trigger, dialog, input} = await paletteReady(page, ccUrl);
  await trigger.click(); await input.fill('wyczysc'); await expect(dialog.getByRole('option')).toHaveCount(3);
  await input.fill('fixture-same'); await page.keyboard.press('Enter');
  const action = page.locator('#action-dialog'); await expect(action).toBeVisible(); await expect(action).toHaveAttribute('data-action', 'fixture-same');
  await expect(page.locator('#danger-confirm input')).not.toBeChecked();
  expect(await action.evaluate(node => node.contains(document.activeElement))).toBe(true);
  await page.keyboard.press('Control+k'); await expect(dialog).toBeHidden(); await expect(action).toBeVisible();
  await page.locator('#action-form button[type="submit"]').click(); await expect(page.locator('#toast')).toContainText('Potwierdź operację');
  expect(writes).toBe(0); await page.locator('#action-dialog .modal-cancel').click();
});

test('@command-palette catalogue text is inert and translated server names replace earlier labels', async ({ page, ccUrl }) => {
  const malicious = '<img src="https://palette-probe.example.invalid/x" onerror="window.paletteProbe=1">';
  const action = {id: 'fixture-html', title: malicious, group: 'Fixture', description: 'Owned test', fields: [], danger: false};
  await paletteActions(page, [action]);
  const foreign: string[] = [];
  await page.route('https://palette-probe.example.invalid/**', route => {foreign.push(route.request().url()); return route.fulfill({body: 'fixture'});});
  await page.route('**/api/language', route => route.fulfill({json: {language: 'en', actions: [{...action, title: 'English authoritative name'}]}}));
  const {trigger, dialog, input} = await paletteReady(page, ccUrl);
  await trigger.click(); await input.fill('fixture-html');
  await expect(dialog.getByRole('option')).toContainText(malicious); await expect(dialog.locator('img, script, svg, a')).toHaveCount(0);
  expect(foreign).toEqual([]); expect(await page.evaluate(() => (window as any).paletteProbe)).toBeUndefined();
  await page.keyboard.press('Escape'); await page.locator('#lang-switch-button').click(); await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await trigger.click(); await input.fill('fixture-html');
  await expect(input).toHaveAttribute('aria-label', 'Search commands and views'); await expect(dialog.getByRole('option')).toContainText('English authoritative name');
  await expect(dialog.getByRole('option')).not.toContainText(malicious);
});

test('@command-palette a queued close event cannot reset a dialog reopened in the same turn', async ({ page, ccUrl }) => {
  const {trigger, dialog, input} = await paletteReady(page, ccUrl);
  await trigger.focus(); await page.keyboard.press('Enter'); await expect(input).toBeFocused();
  await page.evaluate(async () => {
    document.querySelector('#command-palette-dialog')!.dispatchEvent(new Event('cancel', {cancelable: true}));
    (document.querySelector('#cmd-palette-btn') as HTMLButtonElement).click();
    await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
  });
  await expect(dialog).toHaveJSProperty('open', true); await expect(input).toHaveAttribute('aria-expanded', 'true'); await expect(input).toBeFocused();
  await page.keyboard.press('Escape'); await expect(trigger).toBeFocused();
});

const jobA = {id: 'aaaaaaaaaaaa', title: 'Fixture A', status: 'queued', command: 'fixture-a', createdAt: 1};
const jobB = {id: 'bbbbbbbbbbbb', title: 'Fixture B', status: 'success', command: 'fixture-b', createdAt: 2};
async function fixtureJobHistory(page: Page, jobs: object[]) {
  await page.addInitScript(() => {
    (window as any).jobNotifications = [];
    Object.defineProperty(window, 'Notification', {configurable: true, value: class {
      static permission = 'granted';
      static requestPermission() {return Promise.resolve('granted');}
      constructor(title: string) {(window as any).jobNotifications.push(title);}
    }});
  });
  const posts: object[] = [];
  await page.route('**/api/jobs', route => {
    if (route.request().method() === 'POST') {
      posts.push(route.request().postDataJSON()); return route.fulfill({json: jobB});
    }
    return route.fulfill({json: {jobs}});
  });
  return posts;
}

test('@terminal-session duplicate starts and inputs are blocked while a new draft survives acknowledgment', async ({page, ccUrl}) => {
  let starts=0, writes=0, releaseStart!:()=>void, releaseInput!:()=>void;
  const startWait=new Promise<void>(r=>{releaseStart=r;}), inputWait=new Promise<void>(r=>{releaseInput=r;});
  await page.route('**/api/terminal',async route=>{starts++;await startWait;await route.fulfill({json:{id:'term-a',alive:true}});});
  await page.route('**/api/terminal/term-a',route=>route.fulfill({json:{id:'term-a',alive:true,output:'Fixture terminal'}}));
  await page.route('**/api/terminal/term-a/input',async route=>{writes++;expect(route.request().postDataJSON()).toEqual({data:'first\n'});await inputWait;await route.fulfill({json:{ok:true}});});
  await page.goto(`${ccUrl}/#terminal`);await page.locator('#terminal-start').click();
  await expect.poll(()=>starts).toBe(1);await expect(page.locator('#terminal-start')).toBeDisabled();
  await page.locator('#terminal-start').dispatchEvent('click');expect(starts).toBe(1);
  releaseStart();await expect(page.locator('#terminal-output')).toHaveText('Fixture terminal');
  await page.locator('#terminal-input').fill('first');await page.locator('#terminal-form button').click();
  await expect.poll(()=>writes).toBe(1);await page.locator('#terminal-input').fill('next draft');
  await page.locator('#terminal-form').dispatchEvent('submit');expect(writes).toBe(1);
  releaseInput();await expect(page.locator('#terminal-form button')).toBeEnabled();
  await expect(page.locator('#terminal-input')).toHaveValue('next draft');
});

test('@terminal-session replacing a terminal aborts its old read and dead terminals disable input', async ({page, ccUrl}) => {
  let starts=0,oldStarted=false,release!:()=>void;const held=new Promise<void>(r=>{release=r;});
  await page.route('**/api/terminal',route=>route.fulfill({json:{id:++starts===1?'term-a':'term-b',alive:true}}));
  await page.route('**/api/terminal/term-a',async route=>{oldStarted=true;await held;await route.fulfill({json:{id:'term-a',alive:true,output:'STALE TERMINAL'}}).catch(()=>{});});
  await page.route('**/api/terminal/term-b',route=>route.fulfill({json:{id:'term-b',alive:false,output:'Final <script>inert</script> log'}}));
  await page.goto(`${ccUrl}/#terminal`);await page.locator('#terminal-start').click();
  await expect.poll(()=>oldStarted).toBe(true);
  const cancelled=page.waitForEvent('requestfailed',r=>r.url().endsWith('/api/terminal/term-a'));
  await page.locator('#terminal-start').click();await cancelled;release();
  await expect(page.locator('#terminal-output')).toHaveText('Final <script>inert</script> log');
  await expect(page.locator('#terminal-output script')).toHaveCount(0);
  await expect(page.locator('#terminal-input')).toBeDisabled();await expect(page.locator('#terminal-form button')).toBeDisabled();
  expect(starts).toBe(2);
});

test('@terminal-session expiry cancels a pending read and prevents creation from being restarted', async ({page, ccUrl}) => {
  let expired=false,starts=0,reading=false,release!:()=>void;const held=new Promise<void>(r=>{release=r;});
  await page.route('**/api/health',route=>expired?route.fulfill({status:401,json:{code:'CC_SESSION_EXPIRED',error:'Fixture expired'}}):route.continue());
  await page.route('**/api/terminal',route=>{starts++;return route.fulfill({json:{id:'term-a',alive:true}});});
  await page.route('**/api/terminal/term-a',async route=>{reading=true;await held;await route.fulfill({json:{id:'term-a',alive:true,output:'STALE TERMINAL'}}).catch(()=>{});});
  const healthy=page.waitForResponse(r=>r.url().endsWith('/api/health'));
  await page.goto(`${ccUrl}/#terminal`);await healthy;await page.locator('#terminal-start').click();
  await expect.poll(()=>reading).toBe(true);expired=true;
  const cancelled=page.waitForEvent('requestfailed',r=>r.url().endsWith('/api/terminal/term-a'));
  await page.locator('#health-refresh').dispatchEvent('click');await cancelled;release();
  await expect(page.locator('#terminal-start')).toBeDisabled();await expect(page.locator('#terminal-input')).toBeDisabled();
  await page.locator('#terminal-start').dispatchEvent('click');expect(starts).toBe(1);
  await expect(page.locator('#terminal-output')).not.toContainText('STALE TERMINAL');
});

test('@job-monitor queued jobs advance to completion with one read despite repeated selection', async ({page, ccUrl}) => {
  const jobs = [{...jobA}]; const posts = await fixtureJobHistory(page, jobs);
  let calls = 0, release!: () => void; const held = new Promise<void>(resolve => {release = resolve;});
  await page.route(`**/api/jobs/${jobA.id}`, async route => {
    const index = ++calls; if (index === 1) await held;
    const status = index === 1 ? 'queued' : index === 2 ? 'running' : 'success'; jobs[0].status = status;
    await route.fulfill({json: {...jobA, status, output: `Fixture stage ${index}`}});
  });
  await page.goto(`${ccUrl}/#jobs`); const row = page.locator(`[data-job="${jobA.id}"]`);
  await row.click(); await expect.poll(() => calls).toBe(1);
  await row.click(); await row.click(); expect(calls).toBe(1);
  await expect(page.locator('#job-output')).toHaveAttribute('aria-busy', 'true');
  release(); await expect(page.locator('#job-output')).toHaveText('Fixture stage 3');
  await expect(page.locator('#drawer-status')).toHaveClass(/success/);
  await expect(page.locator('#job-output')).toHaveAttribute('aria-busy', 'false');
  expect(calls).toBe(3); expect(posts).toEqual([]);
  expect(await page.evaluate(() => (window as any).jobNotifications)).toHaveLength(1);
  await row.click(); await expect.poll(() => calls).toBe(4);
  expect(await page.evaluate(() => (window as any).jobNotifications)).toHaveLength(1);
});

test('@job-monitor switching selection aborts an old read without replacing the new log or busy state', async ({page, ccUrl}) => {
  const posts = await fixtureJobHistory(page, [jobA, jobB]);
  let oldStarted = false, newStarted = false, releaseOld!: () => void, releaseNew!: () => void;
  const oldWait = new Promise<void>(resolve => {releaseOld = resolve;});
  const newWait = new Promise<void>(resolve => {releaseNew = resolve;});
  await page.route(`**/api/jobs/${jobA.id}`, async route => {
    oldStarted = true; await oldWait;
    await route.fulfill({json: {...jobA, status: 'failed', output: 'STALE LOG', diagnosis: {category: 'STALE'}}}).catch(() => {});
  });
  await page.route(`**/api/jobs/${jobB.id}`, async route => {newStarted = true; await newWait; await route.fulfill({json: {...jobB, output: 'Current <script>inert</script> log'}});});
  await page.goto(`${ccUrl}/#jobs`); await page.locator(`[data-job="${jobA.id}"]`).click();
  await expect.poll(() => oldStarted).toBe(true);
  const cancelled = page.waitForEvent('requestfailed', request => request.url().endsWith(`/api/jobs/${jobA.id}`));
  await page.locator(`[data-job="${jobB.id}"]`).click(); await cancelled;
  await expect.poll(() => newStarted).toBe(true); releaseOld();
  await expect(page.locator('#job-output')).toHaveAttribute('aria-busy', 'true');
  await expect(page.locator('#drawer-title')).toHaveText('Fixture B');
  releaseNew(); await expect(page.locator('#job-output')).toHaveText('Current <script>inert</script> log');
  await expect(page.locator('#job-output script')).toHaveCount(0);
  await expect(page.locator('#job-diagnostic-slot')).toBeEmpty();
  await expect(page.locator('#drawer-status')).toHaveClass(/success/);
  expect(await page.evaluate(() => (window as any).jobNotifications)).toEqual([]); expect(posts).toEqual([]);
});

test('@job-monitor a failed read can be retried by selecting the job without rerunning its command', async ({page, ccUrl}) => {
  const posts = await fixtureJobHistory(page, [jobA]); let calls = 0;
  await page.route(`**/api/jobs/${jobA.id}`, route => ++calls === 1
    ? route.fulfill({status: 503, json: {error: 'Fixture log unavailable'}})
    : route.fulfill({json: {...jobA, status: 'success', output: 'Recovered log'}}));
  await page.goto(`${ccUrl}/#jobs`); await page.locator(`[data-job="${jobA.id}"]`).click();
  await expect(page.locator('#toast')).toContainText('Fixture log unavailable');
  await expect(page.locator('#job-output')).toHaveAttribute('aria-busy', 'false');
  expect(calls).toBe(1); await page.locator(`[data-job="${jobA.id}"]`).click();
  await expect(page.locator('#job-output')).toHaveText('Recovered log'); expect(calls).toBe(2); expect(posts).toEqual([]);
});

test('@job-monitor session expiry aborts the log read and selection cannot restart it', async ({page, ccUrl}) => {
  const posts = await fixtureJobHistory(page, [jobA, jobB]); let expired = false, calls = 0, release!: () => void;
  const held = new Promise<void>(resolve => {release = resolve;});
  await page.route('**/api/health', route => expired ? route.fulfill({status: 401, json: {code: 'CC_SESSION_EXPIRED', error: 'Fixture expired'}}) : route.continue());
  await page.route('**/api/jobs/*', async route => {calls++; await held; await route.fulfill({json: {...jobA, status: 'running', output: 'STALE LOG'}}).catch(() => {});});
  const healthy = page.waitForResponse(response => response.url().endsWith('/api/health'));
  await page.goto(`${ccUrl}/#jobs`); await healthy;
  await page.locator(`[data-job="${jobA.id}"]`).click();
  await expect.poll(() => calls).toBe(1); expired = true;
  const cancelled = page.waitForEvent('requestfailed', request => request.url().endsWith(`/api/jobs/${jobA.id}`));
  await page.locator('#health-refresh').dispatchEvent('click'); await cancelled; release();
  await expect(page.locator('#panel-session-status')).toBeVisible();
  await expect(page.locator('#job-output')).not.toContainText('STALE LOG');
  await expect(page.locator(`[data-job="${jobB.id}"]`)).toHaveCount(0);
  expect(calls).toBe(1); expect(posts).toEqual([]);
});

test('@job-monitor a newly submitted finished job notifies once and history selection only reads it', async ({page, ccUrl}) => {
  await paletteActions(page, [{id: 'fixture-command', title: 'Fixture command', group: 'Fixture', description: 'Fixture only', fields: [], danger: false}]);
  const posts = await fixtureJobHistory(page, [jobB]);
  await page.route(`**/api/jobs/${jobB.id}`, route => route.fulfill({json: {...jobB, output: 'New command done'}}));
  await page.goto(`${ccUrl}/#operations`); await page.locator('[data-action="fixture-command"]').click();
  await page.locator('#action-form button[type="submit"]').click();
  await expect(page.locator('#drawer-status')).toHaveClass(/success/);
  await expect.poll(() => posts.length).toBe(1);
  expect(posts[0]).toEqual({action: 'fixture-command', values: {}, confirmed: true});
  expect(await page.evaluate(() => (window as any).jobNotifications)).toHaveLength(1);
  await page.locator('#navigation [data-view="jobs"]').click(); await page.locator(`[data-job="${jobB.id}"]`).click();
  await expect(page.locator('#job-output')).toHaveText('New command done');
  expect(posts).toHaveLength(1); expect(await page.evaluate(() => (window as any).jobNotifications)).toHaveLength(1);
});

test('@operator-intents an uncertain correction restores its payload after reload and clears only after confirmation', async ({page, ccUrl}) => {
  const commands: any[] = [];
  await page.route('**/api/data/invoices?**', route => route.fulfill({json: {invoices: [{id: 99, invoiceNumber: 'FV-99', grossAmount: 100}], hasMore: false, nextCursor: '0'}}));
  await page.route('**/api/data/invoices/99/correction-balance', route => route.fulfill({json: {version: 2, grossAmount: '80.00'}}));
  await page.route('**/api/data/invoices/correct', route => {
    const command = route.request().postDataJSON(); commands.push(command);
    return commands.length === 1 ? route.abort('failed') : route.fulfill({json: {correction: {idempotencyKey: command.idempotencyKey, balanceVersion: 3}}});
  });
  await page.goto(`${ccUrl}/#data`); await page.locator('#data-tab-invoices-btn').click();
  await page.locator('.btn-correct-invoice').click();
  await expect(page.locator('#correction-orig-gross')).toHaveValue('80.00');
  await page.locator('#correction-reason').fill('Fixture rebate'); await page.locator('#correction-delta').fill('-10.00');
  await page.locator('#correction-submit-btn').click(); await expect(page.locator('#correction-error')).toBeVisible();
  await page.reload(); await page.locator('#data-tab-invoices-btn').click(); await page.locator('.btn-correct-invoice').click();
  await expect(page.locator('#correction-reason')).toHaveValue('Fixture rebate');
  await expect(page.locator('#correction-reason')).toHaveJSProperty('readOnly', true);
  await expect(page.locator('#correction-delta')).toHaveValue('-10.00');
  await page.locator('#correction-submit-btn').click(); await expect(page.locator('#correction-dialog')).not.toBeVisible();
  expect(commands).toHaveLength(2); expect(commands[1]).toEqual(commands[0]);
  expect(await page.evaluate(() => localStorage.getItem('minitech-invoice-correction-v1:dev:99'))).toBeNull();
});

test('@operator-intents corrupt local commands disable stock and correction submission without a POST', async ({page, ccUrl}) => {
  let posts = 0;
  await page.route('**/api/data/inventory?**', route => route.fulfill({json: {inventory: [{product_id: 101, name: 'Fixture stock', available_quantity: 10}], hasMore: false, nextCursor: '0'}}));
  await page.route('**/api/data/invoices?**', route => route.fulfill({json: {invoices: [{id: 99, invoiceNumber: 'FV-99', grossAmount: 100}], hasMore: false, nextCursor: '0'}}));
  await page.route(/\/api\/data\/(inventory\/restock|invoices\/correct)$/, route => {posts++; return route.fulfill({json: {}});});
  await page.goto(`${ccUrl}/#data`);
  await page.evaluate(() => {
    localStorage.setItem('minitech-stock-receipt-v1:dev:101', '{}');
    localStorage.setItem('minitech-invoice-correction-v1:dev:99', '{}');
  });
  await page.locator('#data-tab-inventory-btn').click();
  await expect(page.locator('.restock-btn[data-qty="10"]')).toBeDisabled();
  await expect(page.locator('.restock-btn[data-qty="50"]')).toBeDisabled();
  await page.locator('#data-tab-invoices-btn').click(); await page.locator('.btn-correct-invoice').click();
  await expect(page.locator('#correction-error')).toContainText('Nie można odczytać');
  await expect(page.locator('#correction-submit-btn')).toBeDisabled(); expect(posts).toBe(0);
});

test('@assistant-conversation pending requests block chips and Enter without losing the next draft', async ({page, ccUrl}) => {
  const commands: any[] = []; let release!: () => void;
  const held = new Promise<void>(resolve => {release = resolve;});
  await page.route('**/api/ai/chat', async route => {
    commands.push(route.request().postDataJSON());
    if (commands.length === 1) await held;
    await route.fulfill({json: {reply: commands.length === 1 ? 'First reply' : 'Second reply'}});
  });
  await page.goto(`${ccUrl}/#assistant`);
  await page.locator('#ai-input').fill('First question'); await page.locator('#ai-send-btn').click();
  await expect.poll(() => commands.length).toBe(1);
  await page.locator('#ai-input').fill('Next draft'); await page.locator('#ai-input').press('Enter');
  await expect(page.locator('.ai-chip').first()).toBeDisabled();
  await page.locator('.ai-chip').first().dispatchEvent('click');
  await expect(page.locator('#ai-input')).toHaveValue('Next draft'); expect(commands).toHaveLength(1);
  await expect(page.locator('#ai-messages')).toHaveAttribute('aria-busy', 'true');
  release(); await expect(page.locator('#ai-send-btn')).toBeEnabled();
  await page.locator('#ai-send-btn').click(); await expect(page.locator('#ai-messages')).toContainText('Second reply');
  expect(commands[0]).toEqual({message: 'First question', history: []});
  expect(commands[1]).toEqual({message: 'Next draft', history: [{role: 'user', content: 'First question'}, {role: 'assistant', content: 'First reply'}]});
  await expect(page.locator('#ai-messages .user-msg')).toHaveCount(2);
});

test('@assistant-conversation clear aborts the old response and starts a separate pending turn', async ({page, ccUrl}) => {
  const commands: any[] = []; const releases: Array<() => void> = [];
  await page.route('**/api/ai/chat', async route => {
    const index = commands.length; commands.push(route.request().postDataJSON());
    await new Promise<void>(resolve => {releases[index] = resolve;});
    await route.fulfill({json: {reply: index === 0 ? 'STALE ANSWER' : 'Fresh answer'}}).catch(() => {});
  });
  await page.goto(`${ccUrl}/#assistant`);
  await page.locator('#ai-input').fill('Old question'); await page.locator('#ai-send-btn').click();
  await expect.poll(() => releases.length).toBe(1);
  const cancelled = page.waitForEvent('requestfailed', request => request.url().endsWith('/api/ai/chat'));
  await page.locator('#ai-clear-btn').click(); await cancelled;
  await page.locator('#ai-input').fill('Fresh question'); await page.locator('#ai-send-btn').click();
  await expect.poll(() => releases.length).toBe(2); releases[0]();
  await expect(page.locator('#ai-send-btn')).toBeDisabled();
  await expect(page.locator('#ai-messages')).not.toContainText('Old question');
  await expect(page.locator('#ai-messages')).not.toContainText('STALE ANSWER');
  releases[1](); await expect(page.locator('#ai-send-btn')).toBeEnabled();
  await expect(page.locator('#ai-messages')).toContainText('Fresh answer');
  expect(commands[1]).toEqual({message: 'Fresh question', history: []});
});

test('@assistant-conversation session expiry cancels the pending answer and clear cannot reenable sending', async ({page, ccUrl}) => {
  let expired = false, calls = 0, release!: () => void;
  const held = new Promise<void>(resolve => {release = resolve;});
  await page.route('**/api/ai/config', route => expired ? route.fulfill({status: 401, json: {code: 'CC_SESSION_EXPIRED', error: 'Session expired'}}) : route.continue());
  await page.route('**/api/ai/chat', async route => {calls++; await held; await route.fulfill({json: {reply: 'STALE ANSWER'}}).catch(() => {});});
  await page.goto(`${ccUrl}/#assistant`);
  await page.locator('#ai-input').fill('Pending'); await page.locator('#ai-send-btn').click();
  await expect.poll(() => calls).toBe(1); expired = true;
  const cancelled = page.waitForEvent('requestfailed', request => request.url().endsWith('/api/ai/chat'));
  await page.locator('#refresh-button').click(); await cancelled;
  await expect(page.locator('#ai-input')).toBeDisabled();
  release(); await page.locator('#ai-clear-btn').dispatchEvent('click');
  await expect(page.locator('#ai-send-btn')).toBeDisabled(); await expect(page.locator('.ai-chip').first()).toBeDisabled();
  await page.locator('.ai-chip').first().dispatchEvent('click'); expect(calls).toBe(1);
  await expect(page.locator('#ai-messages')).not.toContainText('STALE ANSWER');
});

test('@assistant-conversation malformed replies recover without adding a failed turn to context', async ({page, ccUrl}) => {
  const commands: any[] = [];
  await page.route('**/api/ai/chat', route => {
    commands.push(route.request().postDataJSON());
    return route.fulfill({json: commands.length === 1 ? {reply: 123} : {reply: '**Recovered**'}});
  });
  await page.goto(`${ccUrl}/#assistant`);
  await page.locator('#ai-input').fill('Malformed'); await page.locator('#ai-send-btn').click();
  await expect(page.locator('#ai-messages')).toContainText('Nieprawidłowa odpowiedź Asystenta');
  await expect(page.locator('#ai-send-btn')).toBeEnabled();
  await page.locator('#ai-input').fill('Retry'); await page.locator('#ai-send-btn').click();
  await expect(page.locator('#ai-messages .bubble-content strong').last()).toHaveText('Recovered');
  expect(commands[1]).toEqual({message: 'Retry', history: []});
});

test('@assistant-renderer formats code, quotes, lists and table values without mixing block contents', async ({ page, ccUrl }) => {
  expect(page.context().browser()?.browserType().name()).toBe(process.env.CC_TEST_BROWSER || 'chromium');
  let token = '';
  await page.route('**/api/ai/chat', route => {
    token = route.request().headers()['x-control-center-token'];
    return route.fulfill({json: {reply: '# Raport\n\n**Ważne** i *szczegół*\n\n> Cytat\n\n```bash\n  echo "**literal**"\n# not a heading\n| not | table |\n> literal quote\n```\n\n- A\n- B\n\nBetween\n\n- C\n\n| Name | Value |\n| --- | --- |\n| release---candidate | `a|b` |'}});
  });
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${ccUrl}/#assistant`);
  await page.locator('#ai-input').fill('Fixture formatting'); await page.locator('#ai-send-btn').click();
  const reply = page.locator('#ai-messages .bot-msg .bubble-content').last();
  await expect(reply.locator('h3')).toHaveText('Raport');
  await expect(reply.locator('blockquote')).toHaveText('Cytat');
  expect(await reply.locator('pre code').textContent()).toBe('  echo "**literal**"\n# not a heading\n| not | table |\n> literal quote');
  await expect(reply.locator('pre strong, pre h3, pre table, pre blockquote')).toHaveCount(0);
  await expect(reply.locator('ul')).toHaveCount(2); await expect(reply.locator('li')).toHaveText(['A', 'B', 'C']);
  await expect(reply.locator('table th')).toHaveText(['Name', 'Value']);
  await expect(reply.locator('table td')).toHaveText(['release---candidate', 'a|b']);
  expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/); expect(errors).toEqual([]);
});

test('@assistant-renderer treats HTML, scripts, links and media as inert assistant text', async ({ page, ccUrl }) => {
  const foreign: string[] = [];
  await page.route('https://assistant-probe.example.invalid/**', route => {
    foreign.push(route.request().url()); return route.fulfill({body: 'fixture'});
  });
  const attack = '<script>window.assistantProbe=1</script>\n<img src="https://assistant-probe.example.invalid/a" onerror="window.assistantProbe=2">\n<svg onload="window.assistantProbe=3"></svg>\n<iframe src="https://assistant-probe.example.invalid/frame"></iframe>\n[open](javascript:alert(1)) ![image](https://assistant-probe.example.invalid/image)';
  await page.route('**/api/ai/chat', route => route.fulfill({json: {reply: attack}}));
  await page.goto(`${ccUrl}/#assistant`);
  await page.locator('#ai-input').fill('<img src=x onerror="window.assistantProbe=4">'); await page.locator('#ai-send-btn').click();
  const reply = page.locator('#ai-messages .bot-msg .bubble-content').last();
  await expect(reply).toContainText('<script>'); await expect(reply).toContainText('<img');
  await expect(page.locator('#ai-messages script, #ai-messages img, #ai-messages svg, #ai-messages iframe, #ai-messages a')).toHaveCount(0);
  expect(await page.evaluate(() => (window as any).assistantProbe)).toBeUndefined(); expect(foreign).toEqual([]);
});

test('@assistant-renderer keeps API errors inert and a later successful reply can still be formatted', async ({ page, ccUrl }) => {
  let calls = 0;
  await page.route('**/api/ai/chat', route => ++calls === 1
    ? route.fulfill({status: 503, json: {error: '<img src=x onerror="window.assistantProbe=1">'}})
    : route.fulfill({json: {reply: '**Recovered**'}}));
  await page.goto(`${ccUrl}/#assistant`);
  await page.locator('#ai-input').fill('First fixture'); await page.locator('#ai-send-btn').click();
  await expect(page.locator('#ai-messages .bot-msg .bubble-content').last()).toContainText('<img');
  await expect(page.locator('#ai-send-btn')).toBeEnabled(); await expect(page.locator('#ai-messages img')).toHaveCount(0);
  await page.locator('#ai-input').fill('Retry fixture'); await page.locator('#ai-send-btn').click();
  await expect(page.locator('#ai-messages .bot-msg .bubble-content').last().locator('strong')).toHaveText('Recovered');
  expect(calls).toBe(2); expect(await page.evaluate(() => (window as any).assistantProbe)).toBeUndefined();
});

test('AI preferences preserve an environment key without sending it to the browser', async ({ page, ccUrl }) => {
  const config = page.waitForResponse(response => response.url().endsWith('/api/ai/config'));
  await page.goto(`${ccUrl}/#assistant`);
  const initial = await (await config).json();
  expect(initial.keySource).toBe('environment');
  expect(initial.maskedApiKey).toBe('••••1234');
  expect(initial.apiKey).toBeUndefined();
  await page.locator('#ai-settings-button').click();
  await expect(page.locator('#ai-key-input')).toHaveValue('');
  await expect(page.locator('#ai-key-status')).toContainText('zmienna środowiskowa');
  await page.locator('#ai-model-input').fill('fixture-model-changed');
  const saved = page.waitForResponse(response => response.url().endsWith('/api/ai/config') && response.request().method() === 'POST');
  await page.locator('#ai-save-btn').click();
  const reply = await saved;
  expect(reply.status()).toBe(200);
  expect(reply.request().postDataJSON().apiKey).toBe('');
  const result = await reply.json();
  expect(result.hasApiKey).toBe(true); expect(result.keySource).toBe('environment');
  expect(JSON.stringify(result)).not.toContain('cc-fixture-env-secret-1234');
  expect(await page.content()).not.toContain('cc-fixture-env-secret-1234');
  await page.locator('#ai-settings-button').click();
  await expect(page.locator('#ai-model-input')).toHaveValue('fixture-model-changed');
  await expect(page.locator('#ai-key-input')).toHaveValue('');
});

test('AI key save failure is visible and cancelled input is cleared', async ({ page, ccUrl }) => {
  const config = page.waitForResponse(response => response.url().endsWith('/api/ai/config'));
  await page.goto(`${ccUrl}/#assistant`); await config;
  await page.locator('#ai-settings-button').click();
  await page.locator('#ai-key-input').fill('cc-new-fixture-key-never-written');
  const saved = page.waitForResponse(response => response.url().endsWith('/api/ai/config') && response.request().method() === 'POST');
  await page.locator('#ai-save-btn').click();
  const rejected = await saved;
  expect(rejected.status()).toBe(400);
  expect(JSON.stringify(await rejected.json())).not.toContain('cc-new-fixture-key-never-written');
  await expect(page.locator('#ai-settings-dialog')).toBeVisible();
  await expect(page.locator('#toast')).toContainText('age');
  await page.locator('#ai-settings-dialog .modal-cancel').click();
  await expect(page.locator('#ai-key-input')).toHaveValue('');
  await page.locator('#ai-settings-button').click();
  await page.locator('#ai-key-input').fill('cc-provider-specific-key');
  await page.locator('#ai-provider-select').selectOption('antigravity');
  await expect(page.locator('#ai-key-input')).toHaveValue('');
  await page.keyboard.press('Escape');
  await expect(page.locator('#ai-key-input')).toHaveValue('');
});
