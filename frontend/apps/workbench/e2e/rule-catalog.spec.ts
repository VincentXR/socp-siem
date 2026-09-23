import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('rule 501 can be paged, filtered, edited by link, and selected for alarm filtering', async ({ page }) => {
  const rules = Array.from({ length: 501 }, (_, index) => ({
    id: `rule-${String(index + 1).padStart(4, '0')}`, name: `Catalog rule ${index + 1}`,
    type: 'pattern', severity: 'HIGH', status: 'DISABLED', enabled: false,
    match: [{ field: 'msg', op: 'contains', value: 'sample' }],
  }))
  const unexpected: string[] = []
  const queries: URL[] = []
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    const path = url.pathname
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/detect-web/api/v1/stats') data = { rules: 501, eventCount: 0, alertCount: 0, queueLoad: 0 }
    else if (path === '/search-config/api/v1/meta/fields' || path === '/detect-web/api/v1/watchlists') data = []
    else if (path === '/attack-web/api/v1/techniques') data = { items: [], total: 0 }
    else if (path === '/detect-web/api/v1/rules' || path === '/detect-web/api/v1/rules/options') {
      queries.push(url)
      const matched = rules.filter(rule => `${rule.id} ${rule.name}`.includes(url.searchParams.get('q') || ''))
      const number = Number(url.searchParams.get('page'))
      const size = Number(url.searchParams.get('size'))
      data = { items: matched.slice((number - 1) * size, number * size), total: matched.length, page: number, size, totalPages: Math.ceil(matched.length / size) }
    } else if (path === '/detect-web/api/v1/rules/rule-0501') data = rules[500]
    else if (path === '/alert-web/api/alarms') data = { items: [], total: 0, page: 1, size: 20, totalPages: 0 }
    else {
      unexpected.push(`${route.request().method()} ${path}`)
      await route.abort()
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  await page.goto('/detect?page=26&size=20')
  await expect(page.getByRole('row').filter({ hasText: 'Catalog rule 501' })).toBeVisible()
  await page.locator('.el-pagination__sizes').click()
  await page.getByRole('option', { name: '50/page', exact: true }).click()
  await expect(page).toHaveURL(/size=50/)
  await expect(page).not.toHaveURL(/page=26/)
  await page.getByRole('textbox', { name: 'Search', exact: true }).fill('Catalog rule 501')
  await page.locator('.rule-search').getByRole('button', { name: 'Search', exact: true }).click()
  const found = page.getByRole('row').filter({ hasText: 'Catalog rule 501' })
  await expect(found).toBeVisible()
  await found.getByRole('button', { name: 'Edit', exact: true }).click()
  await expect(page).toHaveURL(/rules\/rule-0501\/edit/)
  await expect(page.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('Catalog rule 501')
  await page.reload()
  await expect(page.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('Catalog rule 501')
  await page.getByRole('button', { name: 'Back to list', exact: true }).click()
  await expect(page).toHaveURL(/q=Catalog/)
  await expect(page.getByRole('row').filter({ hasText: 'Catalog rule 501' })).toBeVisible()
  await page.screenshot({ path: '../../../docs/_local/audit-rule-catalog.png', fullPage: true })

  await page.goto('/alarms')
  const selector = page.locator('.alarm-rule-input')
  await selector.click()
  await selector.getByRole('combobox').fill('Catalog rule 501')
  await page.getByRole('option').filter({ hasText: 'Catalog rule 501' }).click()
  await expect.poll(() => queries.some(url => url.pathname.endsWith('/options') && url.searchParams.get('q') === 'Catalog rule 501')).toBe(true)
  expect(unexpected).toEqual([])
})
