import { test, expect } from '../../fixtures/store.fixture';

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
