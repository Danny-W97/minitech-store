import { defineConfig, devices } from '@playwright/test';

const browser = process.env.CC_TEST_BROWSER || 'chromium';
if (!['chromium', 'firefox', 'webkit'].includes(browser)) throw new Error('Unsupported CC test browser');
const device = browser === 'firefox' ? 'Desktop Firefox' : browser === 'webkit' ? 'Desktop Safari' : 'Desktop Chrome';

export default defineConfig({
  testDir: './e2e/control-center',
  outputDir: process.env.CC_TEST_OUTPUT || 'gitIgnoreOwn/runtime/playwright/cc-results',
  workers: 1,
  timeout: 30_000,
  expect: { timeout: 8_000 },
  forbidOnly: Boolean(process.env.CI),
  reporter: [['list']],
  use: {
    ...devices[device],
    browserName: browser as 'chromium' | 'firefox' | 'webkit',
    locale: 'pl-PL',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    launchOptions: { args: browser === 'chromium' ? ['--host-resolver-rules=MAP attacker.test 127.0.0.1', '--no-proxy-server'] : [] },
  },
});
