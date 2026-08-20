import { defineConfig, devices } from '@playwright/test';

const runtimeRoot = 'gitIgnoreOwn/runtime/playwright';

export default defineConfig({
  testDir: './e2e/tests',
  outputDir: `${runtimeRoot}/test-results`,
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  workers: process.env.PLAYWRIGHT_FULL_SYSTEM === '1' ? 1 : undefined,
  timeout: 45_000,
  expect: { timeout: 8_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: `${runtimeRoot}/report`, open: 'never' }]
  ],
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:18070',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    locale: 'pl-PL',
    timezoneId: 'Europe/Warsaw'
  },
  webServer: {
    command: 'node scripts/qa/frontend-server.mjs',
    url: 'http://127.0.0.1:18070/index.html',
    reuseExistingServer: true,
    timeout: 30_000
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
    { name: 'mobile-chrome', use: { ...devices['Pixel 7'] } }
  ]
});
