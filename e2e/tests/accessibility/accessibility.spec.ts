import AxeBuilder from '@axe-core/playwright';
import { test, expect, waitForCatalog } from '../../fixtures/store.fixture';

test('strona główna nie ma poważnych ani krytycznych naruszeń semantycznej dostępności', async ({ page, mockStore }) => {
  void mockStore;
  await page.goto('/index.html');
  await expect(page.locator('#hero-carousel')).toBeVisible();
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa'])
    .disableRules(['color-contrast'])
    .analyze();
  expect(result.violations.filter(row => ['serious', 'critical'].includes(row.impact || ''))).toEqual([]);
});

test('katalog działa na ekranie mobilnym bez poziomego przewijania dokumentu', async ({ page, mockStore }) => {
  void mockStore;
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/catalog.html');
  await waitForCatalog(page);
  const dimensions = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }));
  expect(dimensions.width).toBeLessThanOrEqual(dimensions.viewport + 1);
  await expect(page.locator('#cart-toggle')).toBeVisible();
});
