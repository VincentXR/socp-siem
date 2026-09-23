import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('watchlist catalogue pages summaries and loads members on demand', async ({ page }) => {
  const lists = Array.from({ length: 41 }, (_, index) => ({ name: `watchlist-${String(index + 1).padStart(2, '0')}`, size: 1 }))
  const members = new Map(lists.map(list => [list.name, ['alice']]))
  const unexpected: string[] = []
  const detailReads: string[] = []
  let catalogueReads = 0
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    const path = url.pathname
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/detect-web/api/v1/ueba/entities') data = []
    else if (path === '/detect-web/api/v1/ueba/summary') data = { entities: 0, byLevel: {}, maxRisk: 0, halfLifeHours: 24 }
    else if (path === '/detect-web/api/v1/ueba/score') data = { score: 0, level: 'LOW', breakdown: {} }
    else if (path === '/attack-web/api/v1/techniques') data = { items: [], total: 0 }
    else if (path === '/detect-web/api/v1/watchlists') {
      expect(url.searchParams.get('includeValues')).toBe('false')
      catalogueReads++
      data = lists
    } else if (path.startsWith('/detect-web/api/v1/watchlists/')) {
      const name = decodeURIComponent(path.split('/').at(-1)!)
      if (route.request().method() === 'POST') {
        members.set(name, [...new Set([...(members.get(name) || []), ...route.request().postDataJSON() as string[]])])
      } else if (route.request().method() === 'GET') detailReads.push(name)
      else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
      data = { name, values: members.get(name), size: members.get(name)?.length }
    } else {
      unexpected.push(`${route.request().method()} ${path}`)
      await route.abort()
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  await page.goto('/ueba')
  await page.getByRole('tab', { name: 'Watchlists', exact: true }).click()
  const catalogue = page.getByRole('tabpanel').filter({ has: page.getByRole('button', { name: 'Add Watchlist' }) })
  await expect(catalogue.getByRole('row').filter({ hasText: 'watchlist-01' })).toBeVisible()
  expect(detailReads).toEqual([])
  await catalogue.getByRole('button', { name: 'Go to next page' }).click()
  await expect(catalogue.getByRole('row').filter({ hasText: 'watchlist-21' })).toBeVisible()
  await catalogue.getByRole('row').filter({ hasText: 'watchlist-21' }).getByRole('button', { name: 'Manage entries' }).click()
  const drawer = page.getByRole('dialog', { name: 'watchlist-21' })
  await expect(drawer.getByRole('cell', { name: 'alice', exact: true })).toBeVisible()
  expect(detailReads).toEqual(['watchlist-21'])
  await drawer.getByPlaceholder('Append value').fill('bob, carol')
  await drawer.getByRole('button', { name: 'Append', exact: true }).click()
  await expect(drawer.getByRole('cell', { name: 'bob', exact: true })).toBeVisible()
  await expect(drawer.getByPlaceholder('Append value')).toHaveValue('')
  expect(catalogueReads).toBe(1)
  expect(detailReads).toEqual(['watchlist-21'])
  await page.screenshot({ path: '../../../docs/_local/audit-watchlists.png', fullPage: true })
  expect(unexpected).toEqual([])
})

test('creation conflict preserves input and lets the analyst choose a new name', async ({ page }) => {
  const saved = new Map<string, string[]>([['taken', ['existing-owner']]])
  const unexpected: string[] = []
  let creates = 0
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    const path = url.pathname
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/detect-web/api/v1/ueba/entities') data = []
    else if (path === '/detect-web/api/v1/ueba/summary') data = { entities: 0, byLevel: {}, maxRisk: 0, halfLifeHours: 24 }
    else if (path === '/detect-web/api/v1/ueba/score') data = { score: 0, level: 'LOW', breakdown: {} }
    else if (path === '/attack-web/api/v1/techniques') data = { items: [], total: 0 }
    else if (path === '/detect-web/api/v1/watchlists' && route.request().method() === 'GET') data = []
    else if (path === '/detect-web/api/v1/watchlists' && route.request().method() === 'POST') {
      creates++
      const body = route.request().postDataJSON() as { name: string; values: string[] }
      if (saved.has(body.name)) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 409, message: 'watchlist already exists' }) })
        return
      }
      saved.set(body.name, body.values)
      data = { name: body.name, values: body.values, size: body.values.length }
    } else {
      unexpected.push(`${route.request().method()} ${path}`)
      await route.abort()
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  await page.goto('/ueba')
  await page.getByRole('tab', { name: 'Watchlists', exact: true }).click()
  await page.getByRole('button', { name: 'Add Watchlist', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Add Watchlist', exact: true })
  const name = dialog.getByPlaceholder('e.g. vip_accounts')
  const values = dialog.getByPlaceholder('Comma, space, or line separated')
  await name.fill('taken')
  await values.fill('alice, bob')
  await dialog.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(dialog.getByText('This name already exists. Choose another name.', { exact: true })).toBeVisible()
  await expect(name).toHaveValue('taken')
  await expect(values).toHaveValue('alice, bob')
  expect(saved.get('taken')).toEqual(['existing-owner'])
  await page.screenshot({ path: '../../../docs/_local/audit-watchlist-create-conflict.png', fullPage: true })
  await name.fill('new-accounts')
  await dialog.getByRole('button', { name: 'Create', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: 'new-accounts', exact: true })
  await expect(drawer.getByRole('cell', { name: 'alice', exact: true })).toBeVisible()
  await expect(drawer.getByRole('cell', { name: 'bob', exact: true })).toBeVisible()
  expect(saved.get('new-accounts')).toEqual(['alice', 'bob'])
  expect(creates).toBe(2)
  expect(unexpected).toEqual([])
})
