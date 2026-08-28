import { expect, test, type Page } from '@playwright/test'

async function mockSession(page: Page, role: 'viewer' | 'analyst' = 'analyst') {
  await page.route('**/auth/session', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ username: `${role}-user`, role, tenant: 'default' }),
  }))
  await page.route(/\/(alert-web|search-config|detect-web|soar-web|report-web|asset-web|soc-base|hips-web|ai-assistant|threat-web|attack-web|notify-web|incident-web)\//,
    route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }))
}

test('login creates a session-backed workbench without exposing a bearer token', async ({ page }) => {
  await page.route('**/auth/session', route => route.fulfill({ status: 401, body: '{}' }))
  await page.route('**/auth/login', async route => {
    const body = route.request().postDataJSON()
    expect(body).toEqual({ username: 'analyst', password: 'secret' })
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Set-Cookie': 'SOCP_SESSION=test; Path=/; HttpOnly; SameSite=Lax' },
      body: JSON.stringify({ username: 'analyst', role: 'analyst', tenant: 'default', expiresIn: 1800 }),
    })
  })
  await page.route(/\/(alert-web|search-config|detect-web|report-web)\//,
    route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }))

  await page.goto('/overview')
  await page.locator('input[autocomplete="username"]').fill('analyst')
  await page.locator('input[autocomplete="current-password"]').fill('secret')
  await page.locator('form').getByRole('button').click()

  await expect(page.locator('.socp-shell')).toBeVisible()
  await expect(page.evaluate(() => localStorage.getItem('socp_token'))).resolves.toBeNull()
})

test('viewer cannot navigate to write-oriented configuration pages', async ({ page }) => {
  await mockSession(page, 'viewer')
  await page.goto('/overview')

  await expect(page.locator('[data-menu-key="alarms"]')).toBeVisible()
  await expect(page.locator('[data-menu-key="detect"]')).toHaveCount(0)
  await expect(page.locator('[data-menu-key="soar"]')).toHaveCount(0)
  await expect(page.locator('[data-menu-key="notify"]')).toHaveCount(0)
})

test('router preserves deep links and browser back navigation', async ({ page }) => {
  await mockSession(page)
  await page.goto('/overview')
  await page.locator('[data-menu-key="alarms"]').click()
  await expect(page).toHaveURL(/\/alarms$/)
  await expect(page.locator('[data-menu-key="alarms"]')).toHaveAttribute('aria-current', 'page')

  await page.goBack()
  await expect(page).toHaveURL(/\/overview$/)
  await expect(page.locator('[data-menu-key="overview"]')).toHaveAttribute('aria-current', 'page')
})
