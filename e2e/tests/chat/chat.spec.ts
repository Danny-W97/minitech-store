import { test, expect } from '../../fixtures/store.fixture';
import AxeBuilder from '@axe-core/playwright';

test('czat wysyła wiadomość, pamięta rozmowę i pokazuje odpowiedź Centrali', async ({ page, mockStore }) => {
  await page.goto('/index.html');
  await page.locator('#chat-launcher').click();
  await expect(page.locator('#chat-panel')).toBeVisible();
  await page.locator('#chat-name').fill('Playwright');
  await page.locator('#chat-email').fill('playwright@minitech.local');
  await page.locator('#chat-subject').fill('Test czatu');
  await page.locator('#chat-message').fill('Czy produkt jest dostępny?');
  mockStore.chatAnswered = true;
  await page.locator('#chat-submit').click();
  await expect(page.locator('#chat-thread-id')).toHaveText('Rozmowa #77');
  await expect(page.locator('#chat-thread-state')).toHaveText('Odpowiedziano', { timeout: 6_000 });
  await expect(page.locator('#chat-thread-reply')).toHaveText('Tak, produkt jest dostępny.');
  await expect(page.locator('#chat-name')).toBeHidden();
  await expect(page.locator('#chat-email')).toBeHidden();
  await expect(page.locator('#chat-history-older')).toBeHidden();

  await page.reload();
  await page.locator('#chat-launcher').click();
  await expect(page.locator('#chat-thread-id')).toHaveText('Rozmowa #77');
});

test('Escape zamyka panel, a formularz ma awaryjny link e-mail', async ({ page, mockStore }) => {
  void mockStore;
  await page.goto('/index.html');
  await page.locator('#chat-launcher').click();
  await expect(page.locator('#chat-email-fallback')).toHaveAttribute('href', /^mailto:/);
  await page.keyboard.press('Escape');
  await expect(page.locator('#chat-panel')).toBeHidden();
});

test('dwie odpowiedzi pozostają chronologiczne, a odpytywanie działa po pierwszej', async ({ page, mockStore }) => {
  mockStore.chatAnswered=true;
  const entries=[{id:2,author:'SUPPORT',initialMessage:false,message:'Pierwsza odpowiedź',createdAt:'2026-10-26T10:00:00Z'}];
  let reads=0;
  await page.route('**/chat/messages/77/entries?*',route=>{reads++;return route.fulfill({json:{entries,nextCursor:null,hasMore:false}});});
  await page.goto('/index.html');await page.locator('#chat-launcher').click();
  await page.locator('#chat-name').fill('Guest');await page.locator('#chat-email').fill('guest@example.test');
  await page.locator('#chat-message').fill('Question');await page.locator('#chat-submit').click();
  await expect(page.locator('#chat-thread-reply')).toHaveText('Pierwsza odpowiedź');
  entries.push({id:3,author:'SUPPORT',initialMessage:false,message:'Druga odpowiedź',createdAt:'2026-10-26T10:01:00Z'});
  await expect(page.locator('#chat-entry-history .chat-bubble p')).toHaveText(['Pierwsza odpowiedź','Druga odpowiedź'],{timeout:7000});
  await page.keyboard.press('Escape');await expect(page.locator('#chat-launcher')).toBeFocused();
  const before=reads;await page.waitForTimeout(3300);expect(reads).toBe(before);
});

test('dalsza wiadomość używa tej samej rozmowy i tego samego klucza przy ponowieniu', async ({ page, mockStore }) => {
  void mockStore;
  const keys:string[]=[];let commits=0;
  await page.route('**/chat/messages/77/entries',async route=>{
    const body=route.request().postDataJSON();expect(body.message).toBe('Dalsze pytanie');keys.push(body.requestId);
    if(keys.length===1){commits++;return route.fulfill({status:503,json:{message:'Lost receipt'}});}
    return route.fulfill({json:{id:77,message:'Question',status:'NEW'}});
  });
  await page.goto('/index.html');await page.locator('#chat-launcher').click();
  await page.locator('#chat-name').fill('Guest');await page.locator('#chat-email').fill('guest@example.test');
  await page.locator('#chat-message').fill('Question');await page.locator('#chat-submit').click();
  await expect(page.locator('#chat-thread-id')).toHaveText('Rozmowa #77');
  await page.locator('#chat-message').fill('Dalsze pytanie');await page.locator('#chat-submit').click();
  await expect(page.locator('#chat-status')).toContainText('Lost receipt');
  await page.locator('#chat-submit').click();await expect(page.locator('#chat-message')).toHaveValue('');
  expect(keys).toHaveLength(2);expect(keys[0]).toBe(keys[1]);expect(commits).toBe(1);
});

test('starsza strona historii nie duplikuje wpisów i treść nie wykonuje HTML', async ({ page, mockStore }) => {
  void mockStore;
  await page.route('**/chat/messages/77/entries?*',route=>{
    const old=new URL(route.request().url()).searchParams.get('before')==='10';
    return route.fulfill({json:{entries:old?[{id:5,author:'SUPPORT',message:'Starsza odpowiedź'}]:[{id:10,author:'SUPPORT',message:'<img src=x onerror="window.chatXss=1">'}],nextCursor:old?null:10,hasMore:!old}});
  });
  await page.goto('/index.html');await page.locator('#chat-launcher').click();
  await page.locator('#chat-name').fill('Guest');await page.locator('#chat-email').fill('guest@example.test');
  await page.locator('#chat-message').fill('Question');await page.locator('#chat-submit').click();
  await expect(page.locator('#chat-history-older')).toBeVisible();await page.locator('#chat-history-older').click();
  await expect(page.locator('#chat-entry-history .chat-bubble p')).toHaveText(['Starsza odpowiedź','<img src=x onerror="window.chatXss=1">']);
  await expect(page.locator('#chat-entry-history img')).toHaveCount(0);
});

test('po dłuższej przerwie można uzupełnić lukę między stronami historii', async ({ page, mockStore }) => {
  void mockStore;let advanced=false;
  await page.route('**/chat/messages/77/entries?*',route=>{
    const older=new URL(route.request().url()).searchParams.get('before')==='100';
    const entries=older?[{id:50,author:'SUPPORT',message:'Wiadomość z przerwy'}]:advanced?[{id:100,author:'SUPPORT',message:'Najnowsza wiadomość'}]:[{id:2,author:'SUPPORT',message:'Pierwsza wiadomość'}];
    return route.fulfill({json:{entries,nextCursor:advanced&&!older?100:null,hasMore:advanced&&!older}});
  });
  await page.goto('/index.html');await page.locator('#chat-launcher').click();
  await page.locator('#chat-name').fill('Guest');await page.locator('#chat-email').fill('guest@example.test');
  await page.locator('#chat-message').fill('Question');await page.locator('#chat-submit').click();
  await expect(page.locator('#chat-thread-reply')).toHaveText('Pierwsza wiadomość');
  await page.keyboard.press('Escape');advanced=true;await page.locator('#chat-launcher').click();
  await expect(page.locator('#chat-message')).toBeFocused();
  await expect(page.locator('#chat-history-older')).toBeVisible();await page.locator('#chat-history-older').click();
  await expect(page.locator('#chat-entry-history .chat-bubble p')).toHaveText(['Pierwsza wiadomość','Wiadomość z przerwy','Najnowsza wiadomość']);
});

test.describe('mały ekran czatu', () => {
test.use({viewport:{width:320,height:640},screen:{width:320,height:640}});
test('otwarta historia czatu mieści się na 320 px i przechodzi pomiar dostępności', async ({ page, mockStore }) => {
  mockStore.chatAnswered=true;
  await page.goto('/index.html');
  await page.locator('#chat-launcher').click();
  await page.locator('#chat-name').fill('Guest');await page.locator('#chat-email').fill('guest@example.test');
  await page.locator('#chat-message').fill('Question');await page.locator('#chat-submit').click();
  await expect(page.locator('#chat-thread-reply')).toBeVisible();
  const result=await new AxeBuilder({page}).include('#chat-panel').withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();
  expect(result.violations).toEqual([]);
  const bounds=await page.locator('#chat-panel').boundingBox();expect(bounds!.x).toBeGreaterThanOrEqual(0);expect(bounds!.x+bounds!.width).toBeLessThanOrEqual(320);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(320);
  await page.screenshot({path:test.info().outputPath('chat-history-320.png')});
});
});
