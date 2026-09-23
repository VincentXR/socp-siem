import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('reference set drawer stays on its target until batch deletion completes', async ({ page }) => {
  const sets = [
    { id: 'a', name: 'First set', description: '', entries: ['one', 'two'] },
    { id: 'b', name: 'Second set', description: '', entries: ['one', 'two'] },
  ]
  const deleted: string[] = []
  const unexpected: string[] = []
  let release!: () => void
  const held = new Promise<void>(resolve => { release = resolve })
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    const path = url.pathname
    const method = route.request().method()
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/search-config/api/v1/reference-sets' && method === 'GET') data = sets
    else if (path === '/detect-web/api/v1/rules' && method === 'GET') data = { items: [], total: 0 }
    else if (path === '/search-config/api/v1/reference-sets/a/entries' && method === 'DELETE') {
      const value = url.searchParams.get('value')!
      deleted.push(value)
      if (deleted.length === 1) await held
      sets[0].entries = sets[0].entries.filter(entry => entry !== value)
      data = { ok: true }
    } else {
      unexpected.push(`${method} ${path}`)
      await route.abort()
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  try {
    await page.goto('/reference-sets')
    await page.getByRole('row').filter({ hasText: 'First set' }).getByRole('button', { name: 'Manage entries' }).click()
    const drawer = page.getByRole('dialog', { name: 'First set' })
    await drawer.getByRole('button', { name: 'Select visible', exact: true }).click()
    await drawer.getByRole('button', { name: 'Delete 2', exact: true }).click()
    await page.locator('.el-message-box').getByRole('button', { name: 'Confirm', exact: true }).click()
    await expect.poll(() => deleted.length).toBe(1)
    await drawer.locator('.el-drawer__close-btn').click()
    await expect(drawer).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(drawer).toBeVisible()
    release()
    await expect.poll(() => deleted.length).toBe(2)
    await expect(drawer).not.toContainText('2 selected')
    await drawer.locator('.el-drawer__close-btn').click()
    await expect(drawer).not.toBeVisible()
    await page.getByRole('row').filter({ hasText: 'Second set' }).getByRole('button', { name: 'Manage entries' }).click()
    const second = page.getByRole('dialog', { name: 'Second set' })
    await expect(second).toContainText('one')
    await expect(second).toContainText('two')
    expect(deleted).toEqual(['one', 'two'])
    expect(unexpected).toEqual([])
  } finally {
    release()
  }
})
