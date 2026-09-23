import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('compliance retains successful evidence through failed refresh and recovers explicitly', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = []
  let lookupCalls = 0
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/soc-base/api/v1/compliance/frameworks') data = { frameworks: [{ name: 'Test framework', controls: [{ id: 'C1', name: 'Audit events', ruleIds: ['R1'] }] }] }
    else if (path === '/detect-web/api/v1/rules/lookup') {
      expect(route.request().postDataJSON()).toEqual({ ids: ['R1'] })
      if (++lookupCalls === 2) {
        await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: 'Rule catalogue unavailable' }) }); return
      }
      data = [{ id: 'R1', name: 'Audit rule', type: 'pattern', status: lookupCalls === 1 ? 'ACTIVE' : 'DRAFT' }]
    } else if (path === '/soc-base/api/v1/compliance/coverage') {
      expect(route.request().postDataJSON()).toEqual({ ruleIds: ['R1'] })
      data = { coverage: 100, totalControls: 1, coveredControls: 1, contentVersion: 'fixture-v1', generatedAt: '2026-09-23T00:00:00Z',
        byFramework: [{ framework: 'Test framework', coverage: 100, controls: [{ id: 'C1', name: 'Audit events', mappedRules: ['R1'], assessment: 'gap', validUntil: '2027-12-31' }] }] }
    } else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  await page.goto('/compliance')
  const metrics = page.locator('.compliance-metrics .metric-card-value')
  await expect(metrics).toHaveText(['100%', '1', '1', '0'])
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(page.getByText('Refresh failed. Showing the last successful mapping result.', { exact: true })).toBeVisible()
  await expect(metrics).toHaveText(['100%', '1', '1', '0'])
  await expect(page.locator('.compliance-card')).toContainText('Audit events')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('compliance-stale-mobile.png'), fullPage: true })
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await expect(metrics).toHaveText(['100%', '1', '0', '0'])
  await expect(page.getByText('Refresh failed. Showing the last successful mapping result.', { exact: true })).toHaveCount(0)
  expect(unexpected).toEqual([]); expect(errors).toEqual([])
})
