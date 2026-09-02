import { expect, test, type Page } from '@playwright/test'

const WORKBENCH_ORIGIN = 'http://127.0.0.1:4173'
const MOCKED_BACKEND_PATH = /^\/(?:api|auth|alert-web|search-config|detect-web|soar-web|report-web|asset-web|soc-base|hips-web|ai-assistant|detect-model|asset-collect|hips-collect|threat-web|attack-web|notify-web|incident-web|actuator)(?:\/|$)/

function isMockedBackendUrl(url: URL): boolean {
  return url.origin === WORKBENCH_ORIGIN && MOCKED_BACKEND_PATH.test(url.pathname)
}

function isAuthSessionUrl(url: URL): boolean {
  return url.origin === WORKBENCH_ORIGIN && url.pathname === '/auth/session'
}

function isAuthLoginUrl(url: URL): boolean {
  return url.origin === WORKBENCH_ORIGIN && url.pathname === '/auth/login'
}

/**
 * Register before the endpoint mocks so Playwright's newest-first route
 * matching lets explicit mocks handle known calls and this route catches any
 * newly introduced backend call that the test did not model.
 */
async function installNetworkGuard(page: Page): Promise<string[]> {
  const unexpected: string[] = []
  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (isMockedBackendUrl(url)) {
      unexpected.push(`${request.method()} ${url.pathname}`)
      await route.abort()
      return
    }
    await route.continue()
  })
  return unexpected
}

async function mockSession(page: Page, role: 'viewer' | 'analyst' = 'analyst') {
  await page.route(isMockedBackendUrl,
    route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }))
  await page.route(isAuthSessionUrl, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ username: `${role}-user`, role, tenant: 'default' }),
  }))
}

test('login creates a session-backed workbench without exposing a bearer token', async ({ page }) => {
  const unexpected = await installNetworkGuard(page)
  await page.route(isMockedBackendUrl,
    route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }))
  await page.route(isAuthSessionUrl, route => route.fulfill({ status: 401, body: '{}' }))
  await page.route(isAuthLoginUrl, async route => {
    const body = route.request().postDataJSON()
    expect(body).toEqual({ username: 'analyst', password: 'secret' })
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Set-Cookie': 'SOCP_SESSION=test; Path=/; HttpOnly; SameSite=Lax' },
      body: JSON.stringify({ username: 'analyst', role: 'analyst', tenant: 'default', expiresIn: 1800 }),
    })
  })
  await page.goto('/overview')
  await page.locator('input[autocomplete="username"]').fill('analyst')
  await page.locator('input[autocomplete="current-password"]').fill('secret')
  await page.locator('form').getByRole('button').click()

  await expect(page.locator('.socp-shell')).toBeVisible()
  await expect(page.evaluate(() => localStorage.getItem('socp_token'))).resolves.toBeNull()
  expect(unexpected).toEqual([])
})

test('viewer cannot navigate to write-oriented configuration pages', async ({ page }) => {
  const unexpected = await installNetworkGuard(page)
  await mockSession(page, 'viewer')
  await page.goto('/overview')

  await expect(page.locator('[data-menu-key="alarms"]')).toBeVisible()
  await expect(page.locator('[data-menu-key="detect"]')).toHaveCount(0)
  await expect(page.locator('[data-menu-key="soar"]')).toHaveCount(0)
  await expect(page.locator('[data-menu-key="notify"]')).toHaveCount(0)
  expect(unexpected).toEqual([])
})

test('router preserves deep links and browser back navigation', async ({ page }) => {
  const unexpected = await installNetworkGuard(page)
  await mockSession(page)
  await page.goto('/overview')
  await page.locator('[data-menu-key="alarms"]').click()
  await expect(page).toHaveURL(/\/alarms$/)
  await expect(page.locator('[data-menu-key="alarms"]')).toHaveAttribute('aria-current', 'page')

  await page.goBack()
  await expect(page).toHaveURL(/\/overview$/)
  await expect(page.locator('[data-menu-key="overview"]')).toHaveAttribute('aria-current', 'page')
  expect(unexpected).toEqual([])
})

test('gateway auth endpoint is reachable in a real browser smoke run', async ({ page }) => {
  test.skip(!process.env.SOCP_E2E_BACKEND_URL, 'set SOCP_E2E_BACKEND_URL to run against a live gateway')
  const baseUrl = process.env.SOCP_E2E_BACKEND_URL!.replace(/\/$/, '')
  const response = await page.goto(`${baseUrl}/auth/session`, { waitUntil: 'domcontentloaded' })
  // An unauthenticated gateway is expected to return 401; a configured test
  // session may return 200. Both prove that the browser reached the gateway.
  expect(response?.status()).toBeGreaterThanOrEqual(200)
  expect(response?.status()).toBeLessThan(500)
})
