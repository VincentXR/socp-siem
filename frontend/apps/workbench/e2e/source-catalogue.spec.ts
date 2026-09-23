import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('source 501 is reachable through management paging and parse-rule name search', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = [], sourceReads: Array<{ page: number; q: string }> = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  const source = (id: string, name: string) => ({ id, name, type: 'FILE', format: 'AUTO', path: `/var/log/${id}.log`,
    address: null, topic: null, env: 'local', enabled: true, readFrom: 'beginning', multiline: null,
    sinkTargetId: null, parseRuleIds: [], description: '', protocol: 'tcp', charset: 'utf-8', timeField: null,
    timezone: 'UTC', tags: [], frequency: 1, categoryId: null, groupId: null, createdAt: '2026-09-23T00:00:00Z' })
  const deep = source('source-501', 'Deep Source')
  const rule = { id: 'rule-a', name: 'Parser A', format: 'REGEX', pattern: 'auth', sourceId: deep.id,
    enabled: false, order: 10, mapping: [], setFields: [], filters: [] }
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
    let data: unknown
    if (path === '/search-config/api/v1/sources') {
      const requestedPage = Number(url.searchParams.get('page')), size = Number(url.searchParams.get('size'))
      const q = url.searchParams.get('q') ?? ''
      sourceReads.push({ page: requestedPage, q })
      const items = q ? (deep.name.toLowerCase().includes(q.toLowerCase()) ? [deep] : [])
        : requestedPage === 26 ? [deep]
          : Array.from({ length: size }, (_, index) => source(`source-${String(index + 1).padStart(3, '0')}`, `Source ${index + 1}`))
      data = { items, total: q ? items.length : 501, page: requestedPage, size, totalPages: q ? 1 : Math.ceil(501 / size) }
    } else if (path === '/search-config/api/v1/sources/source-501') data = { source: deep }
    else if (path === '/search-config/api/v1/outputs') data = []
    else if (path === '/search-config/api/v1/parse-rules') data = { items: [rule], total: 1, page: 1,
      size: Number(url.searchParams.get('size')), totalPages: 1 }
    else if (path === '/search-config/api/v1/parse-rules/rule-a') data = rule
    else if (path === '/search-config/api/v1/parse-rules/batch/resolve') data = [rule]
    else if (path === '/search-config/api/v1/meta/fields') data = []
    else if (path === '/search-config/api/v1/ingest/tasks') data = []
    else if (path === '/search-config/api/v1/ingest/tasks/summary') data = { collectors: 0, accepted: 0, skipped: 0, forwarded: 0, bytes: 0,
      eps1m: 0, byHealth: {}, sources: 501, enabledSources: 501 }
    else if (path === '/search-config/api/v1/meta/categories') data = []
    else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, data }) })
  })

  await page.goto('/ingest?tab=sources')
  const pager = page.locator('.el-pagination:visible')
  await expect(pager).toContainText('501')
  await pager.locator('.el-pager').getByText('26', { exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: 'Deep Source' })).toBeVisible()
  expect(sourceReads.some(read => read.page === 26 && read.q === '')).toBe(true)
  await page.getByPlaceholder('Search source name').fill('Deep')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(pager).toContainText('1')
  await expect(page.getByRole('row').filter({ hasText: 'Deep Source' })).toBeVisible()
  expect(sourceReads.some(read => read.page === 1 && read.q === 'Deep')).toBe(true)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  const pagerBox = await pager.locator('..').boundingBox()
  const totalBox = await pager.locator('.el-pagination__total').boundingBox()
  expect(pagerBox && totalBox && totalBox.x >= pagerBox.x - 1
    && totalBox.x + totalBox.width <= pagerBox.x + pagerBox.width + 1).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('sources-page-mobile.png'), fullPage: true })

  await page.goto('/ingest/parsers/rule-a/edit')
  const sourceSelect = page.locator('.parser-workspace .el-form-item').filter({ hasText: 'Source' }).first().locator('.el-select')
  await expect(sourceSelect).toContainText('Deep Source')
  const previousMatches = sourceReads.filter(read => read.q === 'Deep').length
  await sourceSelect.click()
  await sourceSelect.getByRole('combobox').fill('Deep')
  await expect.poll(() => sourceReads.filter(read => read.q === 'Deep' && read.page === 1).length).toBeGreaterThan(previousMatches)
  await expect(page.getByRole('option').filter({ hasText: 'Deep Source' })).toBeVisible()
  await page.goto('/ingest/parsers/new')
  const newSourceSelect = page.locator('.parser-workspace .el-form-item').filter({ hasText: 'Source' }).first().locator('.el-select')
  const priorNewMatches = sourceReads.filter(read => read.q === 'Deep').length
  await newSourceSelect.click()
  await newSourceSelect.getByRole('combobox').fill('Deep')
  await expect.poll(() => sourceReads.filter(read => read.q === 'Deep').length).toBeGreaterThan(priorNewMatches)
  await page.getByRole('option').filter({ hasText: 'Deep Source' }).click()
  await expect(newSourceSelect).toContainText('Deep Source')
  expect(unexpected).toEqual([]); expect(errors).toEqual([])
})
