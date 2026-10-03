import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'

type Source = {
  id: string
  name: string
  description: string | null
  timeField: string | null
  path: string | null
}

const live = process.env.SOCP_LIVE_E2E === 'true'

async function apiData<T>(response: Awaited<ReturnType<APIRequestContext['get']>>): Promise<T> {
  const body = await response.json()
  expect(response.ok(), JSON.stringify(body)).toBe(true)
  expect(body.code).toBe(0)
  return body.data as T
}

async function sourceMatches(page: Page, name: string): Promise<Source[]> {
  const response = await page.request.get(
    `/search-config/api/v1/sources?page=1&size=20&q=${encodeURIComponent(name)}`,
  )
  const data = await apiData<{ items: Source[] }>(response)
  return data.items.filter(source => source.name === name)
}

function field(drawer: Locator, label: string): Locator {
  return drawer.locator('.el-form-item').filter({ hasText: label }).first()
}

async function login(page: Page): Promise<void> {
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await page.goto('/')
  await page.getByLabel('Username').fill(process.env.SOCP_E2E_USERNAME ?? 'admin')
  await page.getByLabel('Password').fill(
    process.env.SOCP_E2E_PASSWORD ?? process.env.SOAR_VERIFY_PASSWORD ?? 'admin123',
  )
  await page.getByRole('button', { name: 'Sign In to Console' }).click()
  await expect(page.locator('.socp-shell')).toBeVisible()
}

test.describe('live log-source user task', () => {
  test.skip(!live, 'requires the real SOCP gateway and databases')

  test('create, edit, abandon and retry preserve the real persisted state', async ({ page }) => {
    const run = `${Date.now()}-${Math.random().toString(16).slice(2)}`
    const originalName = `e2e-source-${run}`
    const renamed = `${originalName}-renamed`
    const abandoned = `${originalName}-abandoned`
    const retryName = `${originalName}-retry`
    const createdIds = new Set<string>()

    await login(page)
    await page.goto('/ingest?tab=sources')
    await expect(page.getByRole('button', { name: /Add Log Source/ })).toBeVisible()

    try {
      await page.getByRole('button', { name: /Add Log Source/ }).click()
      let drawer = page.locator('.el-drawer:visible').filter({ has: page.locator('.el-form') })
      await field(drawer, 'Source name').locator('input').fill(originalName)
      await drawer.locator('details').filter({ has: page.locator('.el-form-item', { hasText: 'Description' }) }).locator('summary').first().click()
      await field(drawer, 'Description').locator('textarea').fill('must survive a name-only edit')
      await field(drawer, 'File path').locator('input').fill(`/var/log/${originalName}.log`)
      await field(drawer, 'Time field').locator('input').fill('event.created')
      await drawer.getByRole('button', { name: 'Add Log Source', exact: true }).click()
      await expect(drawer).toBeHidden()
      await page.getByRole('dialog', { name: 'Guided setup' }).getByRole('button', { name: 'Close this dialog' }).click()

      let persisted = await sourceMatches(page, originalName)
      expect(persisted).toHaveLength(1)
      createdIds.add(persisted[0].id)
      expect(persisted[0]).toMatchObject({
        description: 'must survive a name-only edit',
        timeField: 'event.created',
        path: `/var/log/${originalName}.log`,
      })

      await page.getByRole('tabpanel', { name: 'Input Sources' })
        .getByRole('button', { name: 'Refresh', exact: true }).click()
      const originalRow = page.getByRole('row').filter({ hasText: originalName })
      await expect(originalRow).toBeVisible()
      await originalRow.getByRole('button', { name: 'Edit', exact: true }).click()
      drawer = page.locator('.el-drawer:visible').filter({ has: page.locator('.el-form') })
      await drawer.locator('details').filter({ has: page.locator('.el-form-item', { hasText: 'Description' }) }).locator('summary').first().click()
      await expect(field(drawer, 'Description').locator('textarea'))
        .toHaveValue('must survive a name-only edit')
      await expect(field(drawer, 'Time field').locator('input')).toHaveValue('event.created')
      await field(drawer, 'Source name').locator('input').fill(renamed)
      await drawer.getByRole('button', { name: 'Save', exact: true }).click()
      await expect(drawer).toBeHidden()
      await page.getByRole('dialog', { name: 'Guided setup' }).getByRole('button', { name: 'Close this dialog' }).click()

      persisted = await sourceMatches(page, renamed)
      expect(persisted).toHaveLength(1)
      expect(persisted[0]).toMatchObject({
        id: [...createdIds][0],
        description: 'must survive a name-only edit',
        timeField: 'event.created',
        path: `/var/log/${originalName}.log`,
      })

      const renamedRow = page.getByRole('row').filter({ hasText: renamed })
      await expect(renamedRow).toBeVisible()
      await renamedRow.getByRole('button', { name: 'Edit', exact: true }).click()
      drawer = page.locator('.el-drawer:visible').filter({ has: page.locator('.el-form') })
      await field(drawer, 'Source name').locator('input').fill(`${renamed}-draft`)
      await drawer.getByRole('button', { name: 'Cancel', exact: true }).click()
      let confirmation = page.locator('.el-message-box:visible')
      await confirmation.getByRole('button', { name: 'Keep editing' }).click()
      await expect(drawer).toBeVisible()
      await expect(field(drawer, 'Source name').locator('input')).toHaveValue(`${renamed}-draft`)
      expect(await sourceMatches(page, renamed)).toHaveLength(1)
      await drawer.getByRole('button', { name: 'Cancel', exact: true }).click()
      confirmation = page.locator('.el-message-box:visible')
      await confirmation.getByRole('button', { name: 'Discard' }).click()
      await expect(drawer).toBeHidden()
      expect(await sourceMatches(page, `${renamed}-draft`)).toHaveLength(0)

      await page.getByRole('button', { name: /Add Log Source/ }).click()
      drawer = page.locator('.el-drawer:visible').filter({ has: page.locator('.el-form') })
      await field(drawer, 'Source name').locator('input').fill(abandoned)
      await drawer.getByRole('button', { name: 'Cancel', exact: true }).click()
      confirmation = page.locator('.el-message-box:visible')
      await confirmation.getByRole('button', { name: 'Discard' }).click()
      await expect(drawer).toBeHidden()
      expect(await sourceMatches(page, abandoned)).toHaveLength(0)

      await page.getByRole('button', { name: /Add Log Source/ }).click()
      drawer = page.locator('.el-drawer:visible').filter({ has: page.locator('.el-form') })
      await field(drawer, 'Source name').locator('input').fill(retryName)
      await field(drawer, 'File path').locator('input').fill(`/var/log/${retryName}.log`)
      await drawer.locator('details').filter({ has: page.locator('.el-form-item', { hasText: 'Description' }) }).locator('summary').first().click()
      const invalidDescription = 'x'.repeat(2001)
      await field(drawer, 'Description').locator('textarea').fill(invalidDescription)
      await drawer.getByRole('button', { name: 'Add Log Source', exact: true }).click()
      await expect(drawer.getByRole('alert')).toBeVisible()
      await expect(field(drawer, 'Description').locator('textarea')).toHaveValue(invalidDescription)
      expect(await sourceMatches(page, retryName)).toHaveLength(0)

      await field(drawer, 'Description').locator('textarea').fill('corrected and retried')
      await drawer.getByRole('button', { name: 'Add Log Source', exact: true }).click()
      await expect(drawer).toBeHidden()
      const retried = await sourceMatches(page, retryName)
      expect(retried).toHaveLength(1)
      createdIds.add(retried[0].id)
    } finally {
      for (const id of createdIds) {
        await page.request.delete(`/search-config/api/v1/sources/${encodeURIComponent(id)}`)
      }
    }
  })
})
