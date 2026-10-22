import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'
import { HEALTH_TARGETS } from '../src/api/health'

test('overview signal panels render actual counts and resize in both themes without inventing zero-value signals', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = []
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'zh-CN'))
  await page.emulateMedia({ reducedMotion: 'reduce', colorScheme: 'light' })
  await page.setViewportSize({ width: 1440, height: 1100 })
  page.on('pageerror', error => errors.push(error.message))
  const alarms = [
    { id: 'visual-a', ruleName: '异常登录后出现横向移动行为', entity: 'srv-finance-03', severity: 'CRITICAL', riskScore: 94, mitre: 'T1021' },
    { id: 'visual-b', ruleName: '特权账户在非工作时段访问敏感资源', entity: 'svc-admin', severity: 'HIGH', riskScore: 86, mitre: 'T1078' },
    { id: 'visual-c', ruleName: '同一来源连续触发认证失败', entity: '192.0.2.18', severity: 'HIGH', riskScore: 72, mitre: 'T1110' },
  ].map(alarm => ({ ...alarm, ruleId: alarm.id, status: 'OPEN', occurredAt: '2026-10-22T08:35:12Z' }))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!isWorkbenchBackendUrl(url)) return route.continue()
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'preview' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/alert-web/api/alarms') data = { items: alarms, total: 3, page: 1, size: Number(url.searchParams.get('size')), totalPages: 1 }
    else if (path === '/alert-web/api/alarms/stats') data = { total: 63, bySeverity: { CRITICAL: 3, HIGH: 12, MEDIUM: 28, LOW: 18, INFO: 0, UNKNOWN: 2 },
      trend7d: { '2026-10-16': 3, '2026-10-17': 7, '2026-10-18': 4, '2026-10-19': 16, '2026-10-20': 11, '2026-10-21': 9, '2026-10-22': 13 } }
    else if (path === '/incident-web/api/v1/stats') data = { total: 9, open: 4, resolved: 5 }
    else if (path === '/api/v1/system/health') data = { status: 'down', checkedAt: new Date().toISOString(),
      services: Object.fromEntries(HEALTH_TARGETS.map(service => [service.name, service.name === 'notify-web' ? 'down' : 'up'])) }
    else { unexpected.push(`${route.request().method()} ${path}`); return route.abort() }
    return route.fulfill({ json: path.startsWith('/auth/') ? data : { code: 0, data } })
  })
  await page.goto('/overview')
  const chart = page.locator('.trend-chart--console')
  await expect(chart).toHaveAttribute('data-state', 'ready')
  await expect(chart).toHaveAttribute('aria-label', /2026-10-19: 16/)
  await expect(page.locator('.overview-hero-total .kpi-anim')).toHaveText('63')
  await expect(page.locator('.overview-health-status')).toHaveText('降级')
  await expect(page.locator('[data-level="INFO"] .overview-severity-fill')).toHaveCSS('width', '0px')
  await expect(page.locator('[data-level="UNKNOWN"]')).toContainText('2')
  await page.screenshot({ path: testInfo.outputPath('overview-console-light.png'), fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '深色', exact: true }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.locator('.socp-sider')).toHaveCSS('background-color', 'rgb(22, 25, 31)')
  await expect(chart).toHaveAttribute('data-state', 'ready')
  await page.screenshot({ path: testInfo.outputPath('overview-console-dark.png'), fullPage: true, animations: 'disabled' })
  await page.locator('.overview-lower-grid').scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('overview-console-details.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.locator('.page-header').scrollIntoViewIfNeeded()
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await expect.poll(() => chart.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('overview-console-mobile.png'), fullPage: true, animations: 'disabled' })
  await page.locator('.overview-priority-card').scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('overview-console-mobile-queue.png'), fullPage: true, animations: 'disabled' })
  expect(unexpected).toEqual([]); expect(errors).toEqual([])
})

test('overview freshness advances only when every source has a successful refresh', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = []
  let failCases = false
  const pending = { id: 'pending-old', ruleId: 'r1', ruleName: 'Older pending investigation', entity: 'host', severity: 'HIGH', status: 'INVESTIGATING', occurredAt: '2020-01-01T00:00:00Z', riskScore: 70, message: 'Still needs investigation' }
  const historical = { ...pending, id: 'closed-risk', ruleName: 'Closed historical risk', status: 'CLOSED', riskScore: 99 }
  await page.clock.setFixedTime(new Date('2026-09-20T10:00:00Z'))
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    let data: unknown
    if (path === '/auth/session') data = { username: 'viewer', role: 'viewer', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/alert-web/api/alarms') {
      const priority = url.searchParams.get('status') === 'ACTIVE'
      if (priority) {
        expect(url.searchParams.get('size')).toBe('5')
        expect(url.searchParams.get('sort')).toBe('riskScore')
        expect(url.searchParams.get('order')).toBe('descending')
        expect(url.searchParams.has('from') || url.searchParams.has('to')).toBe(false)
      }
      data = { items: priority ? [pending] : [], total: priority ? 1 : 0, page: 1, size: priority ? 5 : 100, totalPages: priority ? 1 : 0 }
    }
    else if (path === '/alert-web/api/alarms/stats') data = { total: 1, bySeverity: { HIGH: 1 }, trend7d: {}, topRisk: [historical] }
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
  await expect(page.locator('.overview-priority-card')).toContainText('Older pending investigation')
  await expect(page.locator('.overview-priority-card')).not.toContainText('Closed historical risk')
  await page.screenshot({ path: testInfo.outputPath('overview-priority-desktop.png'), fullPage: true })
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
  await expect(page.locator('#socp-primary-navigation')).not.toBeInViewport()
  await page.screenshot({ path: testInfo.outputPath('overview-refresh-mobile.png'), fullPage: true })
  expect(unexpected).toEqual([]); expect(errors).toEqual([])
})
