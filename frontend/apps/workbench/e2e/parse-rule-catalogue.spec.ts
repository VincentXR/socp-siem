import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('rule 501 remains manageable and selectable from a source', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = [], ruleReads: Array<{ page: number; q: string }> = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  const rules = Array.from({ length: 501 }, (_, index) => ({
    id: `rule-${index + 1}`, name: index === 500 ? 'Deep parser' : `Parser ${index + 1}`,
    format: 'KV', pattern: null, sourceId: null, enabled: true, order: index + 1,
    mapping: [], setFields: [], filters: [], createdAt: '2026-09-23T00:00:00Z',
  }))
  const deep = rules[500]!
  const source = { id: 'source-a', name: 'Bound Source', type: 'FILE', format: 'AUTO',
    path: '/var/log/a.log', address: null, topic: null, env: 'local', enabled: true,
    readFrom: 'beginning', multiline: null, sinkTargetId: null, parseRuleIds: [deep.id],
    description: '', protocol: 'tcp', charset: 'utf-8', timeField: null,
    timezone: 'UTC', tags: [], frequency: 1, categoryId: null, groupId: null,
    createdAt: '2026-09-23T00:00:00Z' }
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
    if (path === '/search-config/api/v1/parse-rules') {
      const requestedPage = Number(url.searchParams.get('page')), size = Number(url.searchParams.get('size'))
      const q = url.searchParams.get('q') ?? ''
      ruleReads.push({ page: requestedPage, q })
      const matching = q ? rules.filter(rule => rule.name.toLowerCase().includes(q.toLowerCase())) : rules
      data = { items: matching.slice((requestedPage - 1) * size, requestedPage * size),
        total: matching.length, page: requestedPage, size, totalPages: Math.ceil(matching.length / size) }
    } else if (path === '/search-config/api/v1/parse-rules/rule-501') data = deep
    else if (path === '/search-config/api/v1/parse-rules/batch/resolve') {
      data = (url.searchParams.get('ids') ?? '').split(',').map(id => rules.find(rule => rule.id === id)).filter(Boolean)
    } else if (path === '/search-config/api/v1/sources') data = { items: [source], total: 1,
      page: Number(url.searchParams.get('page')), size: Number(url.searchParams.get('size')), totalPages: 1 }
    else if (path === '/search-config/api/v1/outputs') data = []
    else if (path === '/search-config/api/v1/meta/fields') data = []
    else if (path === '/search-config/api/v1/meta/categories') data = []
    else if (path === '/search-config/api/v1/ingest/tasks') data = []
    else if (path === '/search-config/api/v1/ingest/tasks/summary') data = { collectors: 0, accepted: 0,
      skipped: 0, forwarded: 0, bytes: 0, eps1m: 0, byHealth: {}, sources: 1, enabledSources: 1 }
    else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, data }) })
  })

  await page.goto('/ingest?tab=rules')
  const pager = page.locator('.el-pagination:visible')
  await expect(pager).toContainText('501')
  await pager.locator('.el-pager').getByText('26', { exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: 'Deep parser' })).toBeVisible()
  expect(ruleReads.some(read => read.page === 26 && read.q === '')).toBe(true)
  await page.getByPlaceholder('Search rule name').fill('Deep')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(pager).toContainText('1')
  await expect(page.getByRole('row').filter({ hasText: 'Deep parser' })).toBeVisible()
  expect(ruleReads.some(read => read.page === 1 && read.q === 'Deep')).toBe(true)

  await page.goto('/ingest?tab=sources')
  await page.getByRole('row').filter({ hasText: 'Bound Source' }).getByRole('button', { name: 'Edit' }).click()
  const drawer = page.locator('.el-drawer')
  const ruleSelect = drawer.locator('.el-form-item').filter({ hasText: 'Bound parse rules:' }).locator('.el-select')
  await expect(ruleSelect).toContainText('Deep parser')
  await ruleSelect.click()
  await ruleSelect.getByRole('combobox').fill('Deep')
  await expect(page.getByRole('option').filter({ hasText: 'Deep parser' })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('parse-rule-binding-mobile.png'), fullPage: true })

  await page.goto('/ingest/parsers/rule-501/edit')
  await expect(page.locator('.parser-workspace input').first()).toHaveValue('Deep parser')
  expect(unexpected).toEqual([]); expect(errors).toEqual([])
})
