import { expect, test, type Page } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

async function fixture(page: Page, options: {
  importRows?: (rows: unknown[]) => Promise<{ status: number; data?: unknown }>
  lifecycle?: () => Promise<void>
}) {
  const unexpected: string[] = [], errors: string[] = []
  const items = ['a.example', 'b.example'].map((value, index) => ({ id: `ioc-${index}`, value, type: 'DOMAIN',
    severity: 'HIGH', source: 'fixture', description: `Details for ${value}`, tags: [], revoked: true }))
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), method = route.request().method()
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    let data: unknown
    if (url.pathname === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (url.pathname === '/auth/operators') data = { items: [] }
    else if (url.pathname === '/threat-web/api/v1/stats') data = { total: 2, byType: { DOMAIN: 2 } }
    else if (url.pathname === '/threat-web/api/v1/iocs' && method === 'GET') data = { items, total: 2, page: 1, size: 10, totalPages: 1 }
    else if (url.pathname === '/threat-web/api/v1/iocs/import' && options.importRows) {
      const result = await options.importRows(route.request().postDataJSON())
      await route.fulfill({ status: result.status, contentType: 'application/json', body: JSON.stringify(
        result.status === 200 ? { code: 0, data: result.data } : { code: result.status, message: 'Import unavailable' }) })
      return
    } else if (url.pathname === '/threat-web/api/v1/iocs/ioc-0/lifecycle' && options.lifecycle) {
      await options.lifecycle(); items[0].revoked = false; data = items[0]
    } else { unexpected.push(`${method} ${url.pathname}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(url.pathname.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  return { unexpected, errors }
}

test('indicator import validates every row and preserves an unconfirmed preview during pending writes', async ({ page }, testInfo) => {
  let writes = 0, release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const state = await fixture(page, { importRows: async rows => {
    writes++
    expect(rows).toEqual([{ type: 'DOMAIN', value: 'reviewed.example', severity: 'HIGH', source: 'import',
      description: 'first\nsecond', tags: ['one', 'two'] }])
    if (writes === 1) { await gate; return { status: 503 } }
    return { status: 200, data: { imported: 1, skipped: 0, errors: [] } }
  } })
  await page.goto('/threat-intel')
  const file = page.locator('input[type="file"]')
  await file.setInputFiles({ name: 'invalid.json', mimeType: 'application/json', buffer: Buffer.from('[{"value":"ok"},{"value":""}]') })
  await expect(page.getByRole('alert')).toContainText('Row 2')
  expect(writes).toBe(0)
  await expect(page.getByRole('dialog', { name: 'Preview threat indicators' })).not.toBeVisible()
  await file.setInputFiles({ name: 'review.csv', mimeType: 'text/csv',
    buffer: Buffer.from('type,value,description,tags\nDOMAIN,reviewed.example,"first\nsecond","one;two"\n') })
  const preview = page.getByRole('dialog', { name: 'Preview threat indicators', exact: true })
  await expect(preview).toContainText('reviewed.example')
  expect(writes).toBe(0)
  await preview.getByRole('button', { name: 'Import these indicators', exact: true }).click()
  await expect.poll(() => writes).toBe(1)
  await preview.getByRole('button', { name: 'Close this dialog' }).click()
  await expect(preview).toBeVisible()
  await expect(preview.getByRole('button', { name: 'Import these indicators', exact: true })).toBeDisabled()
  release()
  await expect(preview.getByRole('alert')).toContainText('Import unavailable')
  await expect(preview.getByRole('status')).toContainText('some rows may already have been accepted')
  await expect(page.locator('.el-message:visible')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('threat-import-unconfirmed.png'), fullPage: true })
  await preview.getByRole('button', { name: 'Import these indicators', exact: true }).click()
  await expect(preview).not.toBeVisible()
  await expect(page.getByRole('alert').filter({ hasText: 'Imported 1, skipped 0' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add IOC', exact: true })).toBeEnabled()
  expect(writes).toBe(2)
  expect(state.unexpected).toEqual([]); expect(state.errors).toEqual([])
})

test('a completed lifecycle request cannot replace a newly selected indicator detail', async ({ page }, testInfo) => {
  let started = false, release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const state = await fixture(page, { lifecycle: async () => { started = true; await gate } })
  await page.goto('/threat-intel')
  await page.getByRole('button', { name: 'a.example', exact: true }).click()
  let drawer = page.getByRole('dialog', { name: 'a.example', exact: true })
  await drawer.getByRole('button', { name: 'Restore', exact: true }).click()
  await expect.poll(() => started).toBe(true)
  await expect(drawer.getByRole('button', { name: 'Delete', exact: true })).toBeDisabled()
  await drawer.locator('.el-drawer__close-btn').click()
  await page.getByRole('button', { name: 'b.example', exact: true }).click()
  drawer = page.getByRole('dialog', { name: 'b.example', exact: true })
  await expect(drawer).toContainText('Details for b.example')
  release()
  await expect(drawer.getByRole('button', { name: 'Edit', exact: true })).toBeEnabled()
  await expect(drawer).toContainText('Details for b.example')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(async () => (await drawer.boundingBox())?.x ?? -1).toBeGreaterThanOrEqual(0)
  await expect.poll(async () => {
    const box = await drawer.boundingBox()
    return box ? box.x + box.width : 1000
  }).toBeLessThanOrEqual(391)
  const bounds = await drawer.locator('.el-drawer__body').evaluate(element => ({ width: element.clientWidth, scroll: element.scrollWidth }))
  expect(bounds.scroll).toBeLessThanOrEqual(bounds.width + 1)
  await expect(page.locator('.el-message:visible')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('threat-detail-mobile.png'), fullPage: true })
  expect(state.unexpected).toEqual([]); expect(state.errors).toEqual([])
})
