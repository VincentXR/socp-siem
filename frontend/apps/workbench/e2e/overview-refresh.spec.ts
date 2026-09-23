import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('overview freshness advances only when every source has a successful refresh', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = []
  let failCases = false
  await page.clock.setFixedTime(new Date('2026-09-20T10:00:00Z'))
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    let data: unknown
    if (path === '/auth/session') data = { username: 'viewer', role: 'viewer', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/alert-web/api/alarms') data = { items: [], total: 0, page: 1, size: 100, totalPages: 0 }
    else if (path === '/alert-web/api/alarms/stats') data = { total: 0, bySeverity: {}, trend7d: {}, topRisk: [] }
    else if (path === '/api/v1/system/health') data = { status: 'up', services: { 'detect-web': 'up' }, checkedAt: new Date().toISOString() }
    else if (path === '/incident-web/api/v1/stats') {
      if (failCases) {
        await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: 'Case statistics unavailable' }) })
        return
      }
      data = { total: 0, open: 0, resolved: 0 }
    } else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  await page.goto('/overview')
  const freshness = page.locator('.page-description')
  await expect(freshness).toContainText('Oldest successful refresh:')
  const original = await freshness.textContent()
  failCases = true
  await page.clock.setFixedTime(new Date('2026-09-20T11:00:00Z'))
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Case statistics unavailable')
  await expect(freshness).toHaveText(original!)
  failCases = false
  await page.clock.setFixedTime(new Date('2026-09-20T12:00:00Z'))
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(freshness).not.toHaveText(original!)
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('overview-refresh-mobile.png'), fullPage: true })
  expect(unexpected).toEqual([]); expect(errors).toEqual([])
})
