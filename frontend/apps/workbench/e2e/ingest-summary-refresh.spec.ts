import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('ingest summary failure preserves measured throughput and parse preview stays usable', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = []
  let summaryFails = false
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
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
    if (path === '/search-config/api/v1/ingest/tasks/summary' && summaryFails) {
      await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: 'Summary unavailable' }) })
      return
    }
    let data: unknown
    if (path === '/search-config/api/v1/sources') data = { items: [], total: 0, page: 1, size: 500, totalPages: 0 }
    else if (path === '/search-config/api/v1/outputs') data = []
    else if (path === '/search-config/api/v1/parse-rules') data = { items: [], total: 0, page: 1,
      size: Number(url.searchParams.get('size')), totalPages: 0 }
    else if (path === '/search-config/api/v1/ingest/tasks') data = [{ id: 'task-a', name: 'Source A', type: 'FILE', format: 'AUTO', enabled: true,
      collector: 'vector', target: '/var/log/auth.log', env: 'local', tags: [], categoryId: null, sinkTargetId: null,
      parseRuleIds: ['R-A'], createdAt: '2026-09-23T00:00:00Z', runtime: { health: 'HEALTHY', eps1m: 25, eps5m: 22, accepted: 19, skipped: 2, forwarded: 17, bytes: 512, lastError: null } }]
    else if (path === '/search-config/api/v1/ingest/tasks/summary') data = { collectors: 1, accepted: 19, skipped: 2, forwarded: 17, bytes: 512, eps1m: 25, byHealth: {}, sources: 3, enabledSources: 2 }
    else if (path === '/search-config/api/v1/meta/categories') data = []
    else if (path === '/search-config/api/v1/parse-rules/preview' && route.request().method() === 'POST') data = { matched: true, fields: { source: 'A' }, rule: 'R-A', format: 'AUTO' }
    else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, data }) })
  })
  await page.goto('/ingest')
  await expect(page.locator('.metrics-row .stat-card .num')).toHaveText(['2/3', '25', '19', '17', '2', '512 B'])
  summaryFails = true
  await page.getByRole('button', { name: 'Refresh', exact: true }).first().click()
  await expect(page.getByRole('alert').filter({ hasText: 'Task metrics could not refresh' })).toContainText('last successful values')
  await expect(page.locator('.metrics-row .stat-card .num')).toHaveText(['2/3', '25', '19', '17', '2', '512 B'])
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('ingest-stale-mobile.png'), fullPage: true })
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.getByRole('row', { name: /Source A/ }).getByRole('button', { name: 'Parse preview' }).click()
  const preview = page.getByRole('dialog', { name: 'Parse preview · Source A' })
  await preview.getByRole('button', { name: 'Run parse preview' }).click()
  await expect(preview).toContainText('"source": "A"')
  expect(unexpected).toEqual([]); expect(errors).toEqual([])
})
