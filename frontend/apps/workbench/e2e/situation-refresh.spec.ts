import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('situation retains failed metrics and keeps the stream pause separate from statistics', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = []
  let failEngine = false, failIngest = false
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {
    localStorage.setItem('socp-locale', 'en-US')
    Object.defineProperty(window, 'EventSource', { value: class { addEventListener() {} close() {} } })
  })
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    if (path === '/auth/session') {
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ username: 'analyst', role: 'analyst', tenant: 'default' }) })
      return
    }
    if (path === '/auth/operators') {
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [] }) })
      return
    }
    if ((path === '/detect-web/api/v1/stats' && failEngine) || (path === '/search-config/api/v1/ingest/tasks/summary' && failIngest)) {
      await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: `${failEngine && path.includes('/detect-web') ? 'Engine' : 'Ingest'} unavailable` }) })
      return
    }
    let data: unknown
    if (path === '/alert-web/api/alarms/stats') data = { total: 12, bySeverity: {}, trend7d: {}, topRules: [], byRiskLevel: { HIGH: 3 }, avgRisk: 68, topRisk: [] }
    else if (path === '/detect-web/api/v1/stats') data = { rules: 2, eventCount: 91, alertCount: 7, dropCount: 2, suppressedCount: 4, queueLoad: 0.3 }
    else if (path === '/detect-web/api/v1/alerts') data = []
    else if (path === '/search-config/api/v1/ingest/tasks/summary') data = { collectors: 1, accepted: 5, skipped: 0, forwarded: 5, bytes: 400, eps1m: 25, byHealth: {}, sources: 1, enabledSources: 1 }
    else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, data }) })
  })
  await page.goto('/situation')
  await expect(page.locator('.sit-kpis .k-num')).toHaveText(['91', '7', '4', '2', '25', '30%'])
  await page.getByRole('button', { name: 'Pause Stream' }).click()
  failEngine = true; failIngest = true
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('last successful values')
  await expect(page.locator('.sit-kpis .k-num')).toHaveText(['91', '7', '4', '2', '25', '30%'])
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('situation-stale-mobile.png'), fullPage: true })
  expect(unexpected).toEqual([]); expect(errors).toEqual([])
})
