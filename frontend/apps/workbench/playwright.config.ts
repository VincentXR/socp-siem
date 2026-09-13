import { defineConfig, devices } from '@playwright/test'

// Local Windows boxes frequently reserve 4157-4256 (Hyper-V dynamic port
// ranges), which swallows the default 4173 with EACCES. Override with
// E2E_PORT=<free port> locally; CI keeps the default.
const e2ePort = Number(process.env.E2E_PORT ?? 4173)

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
    baseURL: `http://127.0.0.1:${e2ePort}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `pnpm dev --host 127.0.0.1 --port ${e2ePort}`,
    url: `http://127.0.0.1:${e2ePort}`,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
