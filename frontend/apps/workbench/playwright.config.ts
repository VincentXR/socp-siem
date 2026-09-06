import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  // Keep the browser contract suite deterministic on developer laptops and
  // small CI runners while still exercising independent tests concurrently.
  workers: 2,
  retries: process.env.CI ? 1 : 0,
  // Slower shared runners load the echarts/vue-flow heavy view chunks in
  // well over the default 5s on cold starts; keep assertions strict but give
  // route/view transitions enough headroom so timing does not masquerade as
  // a router regression.
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [['html', { open: 'never' }], ['list']] : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'pnpm dev --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
