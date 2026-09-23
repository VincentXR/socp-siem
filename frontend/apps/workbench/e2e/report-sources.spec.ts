import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl, workbenchOrigin } from './helpers'

test('report sources recover independently, dated archives remain reachable, and downloads open from the click', async ({ page, context }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = [], prefixes: string[] = [], downloads: string[] = []
  let dailyCalls = 0, trendCalls = 0
  const root = 'reports/default/'
  page.on('pageerror', error => errors.push(error.message))
  await context.route('**/saved-report.json', route => route.fulfill({ contentType: 'application/json', body: '{"total":42}' }))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/report-web/api/v1/reports/daily') {
      dailyCalls++
      data = { date: '2026-09-23', total: 42, bySeverity: { INFO: 42 }, byRule: [], source: 'clickhouse', degraded: false, freshness: null, degradationReason: null }
    } else if (path === '/report-web/api/v1/reports/trend7d') {
      if (++trendCalls === 1) {
        await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: 'Trend unavailable' }) })
        return
      }
      data = { days: ['2026-09-23'], counts: [42], source: 'alert-web', degraded: true, freshness: null, degradationReason: 'Fallback trend evidence' }
    } else if (path === '/report-web/api/v1/reports/archive' && route.request().method() === 'POST') {
      data = { archived: true, day: '20260924', archiveKey: `${root}20260924/snapshot-generated.json`, schemaVersion: 1 }
    } else if (path === '/report-web/api/v1/reports/archive') {
      const prefix = url.searchParams.get('prefix') || 'reports/'
      prefixes.push(prefix)
      const all = prefix === 'reports/'
      const capped = all || prefix === `${root}20260924/`
      const objects = capped ? Array.from({ length: 500 }, (_, index) => ({ key: `${all ? `${root}20200101/` : prefix}report-${index}.json`, size: 100 }))
        : [{ key: `${prefix}daily.json`, size: 100 }]
      data = { prefix: all ? root : prefix, count: objects.length, limit: 500, truncated: capped, objects }
    } else if (path === '/report-web/api/v1/reports/archive/download') {
      downloads.push(url.searchParams.get('key') || '')
      data = { key: url.searchParams.get('key'), url: `${workbenchOrigin()}/saved-report.json` }
    } else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  await page.goto('/reports')
  await expect(page.locator('.metrics-row .num').first()).toHaveText('42')
  const charts = page.locator('.report-charts')
  await expect(charts.getByRole('alert')).toContainText('Trend unavailable')
  await charts.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(charts.getByRole('alert')).toContainText('Fallback trend evidence')
  expect(dailyCalls).toBe(1); expect(trendCalls).toBe(2)
  const archive = page.locator('.report-storage-card')
  await expect(archive.getByRole('alert')).toContainText('500')
  const date = page.locator('.report-archive-filter input')
  await date.fill('2026-09-23'); await date.press('Enter')
  await expect.poll(() => prefixes.at(-1)).toBe(`${root}20260923/`)
  await expect(archive.getByRole('alert')).toHaveCount(0)
  await expect(archive.locator('.report-file-date').first()).toHaveText('2026-09-23')
  const [popup] = await Promise.all([page.waitForEvent('popup'), archive.getByRole('button', { name: 'Download', exact: true }).click()])
  await popup.waitForURL('**/saved-report.json')
  expect(await popup.evaluate(() => window.opener === null)).toBe(true)
  await expect(popup.locator('body')).toContainText('42')
  await popup.close()
  await page.getByRole('button', { name: 'Generate Report', exact: true }).click()
  await expect.poll(() => prefixes.at(-1)).toBe(`${root}20260924/`)
  await expect(date).toHaveValue('2026-09-24')
  await expect(archive.getByRole('alert')).toContainText('500')
  const generated = archive.getByRole('button', { name: 'Download generated snapshot', exact: true })
  const [generatedPopup] = await Promise.all([page.waitForEvent('popup'), generated.click()])
  await generatedPopup.waitForURL('**/saved-report.json')
  expect(downloads.at(-1)).toBe(`${root}20260924/snapshot-generated.json`)
  await generatedPopup.close()
  await page.setViewportSize({ width: 390, height: 844 })
  await charts.scrollIntoViewIfNeeded()
  const columns = charts.locator('.el-col')
  const first = await columns.nth(0).boundingBox(), second = await columns.nth(1).boundingBox()
  expect(first!.width).toBeGreaterThan(250)
  expect(second!.y).toBeGreaterThanOrEqual(first!.y + first!.height)
  await page.screenshot({ path: testInfo.outputPath('report-charts-mobile.png'), fullPage: false })
  await archive.scrollIntoViewIfNeeded()
  await generated.scrollIntoViewIfNeeded()
  await expect(generated).toBeInViewport()
  const generatedBox = await generated.boundingBox()
  expect(generatedBox!.x + generatedBox!.width).toBeLessThanOrEqual(390)
  try {
    await expect.poll(async () => {
      const box = await archive.getByRole('button', { name: 'Download', exact: true }).first().boundingBox()
      return box ? box.x >= 0 && box.x + box.width <= 390 : false
    }).toBe(true)
  } catch (failure) {
    await testInfo.attach('archive-layout.json', { contentType: 'application/json', body: JSON.stringify(await archive.evaluate(element =>
      [...element.querySelectorAll('.el-table, .el-table__body, .el-scrollbar__wrap, td')].map(node => ({
        className: node.className, width: node.getBoundingClientRect().width, x: node.getBoundingClientRect().x,
        position: getComputedStyle(node).position, right: getComputedStyle(node).right,
      })))) })
    throw failure
  }
  await expect(archive.locator('td.el-table-fixed-column--right').first()).not.toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
  await page.screenshot({ path: testInfo.outputPath('report-archive-mobile.png'), fullPage: false })
  expect(unexpected).toEqual([]); expect(errors).toEqual([])
})
