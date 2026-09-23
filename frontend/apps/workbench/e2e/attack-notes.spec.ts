import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('ATT&CK retains coverage and permits correction after a failed note save', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = []
  let projectionReads = 0, noteWrites = 0
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/alert-web/api/alarms/technique-counts') data = { from: '2026-09-16T00:00:00Z', until: '2026-09-23T00:00:00Z', counts: { T1110: 151 } }
    else if (path === '/attack-web/api/v1/tactics') data = { items: [{ id: 'TA0001', name: 'Initial Access' }] }
    else if (path === '/attack-web/api/v1/techniques') data = { items: [{ id: 'T1110', name: 'Brute Force', tactic: 'TA0001', url: 'https://example.com/T1110', description: 'Fixture technique' }] }
    else if (path === '/detect-web/api/v1/rules/active-techniques') {
      if (++projectionReads === 2) { await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: 'Rule projection unavailable' }) }); return }
      data = ['T1110']
    } else if (path === '/attack-web/api/v1/coverage') data = { coverage: 100, coveredTechniques: 1, totalTechniques: 1, uncovered: [], byTactic: [] }
    else if (path === '/attack-web/api/v1/techniques/T1110/note') {
      if (route.request().method() === 'PUT') {
        if (++noteWrites === 1) { await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: 'Note save unavailable' }) }); return }
        expect(route.request().postDataJSON()).toEqual({ note: 'Corrected note' })
      }
      data = { note: 'Existing note' }
    } else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  await page.goto('/attack')
  await expect(page.locator('.am-cov')).toHaveText('1/1')
  await expect(page.locator('.am-badge')).toHaveText('151')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.attack-activity-scope')).toContainText('last seven days')
  await page.screenshot({ path: testInfo.outputPath('attack-activity-mobile.png'), fullPage: true })
  await page.getByRole('button', { name: 'Recalculate', exact: true }).click()
  await expect(page.getByText(/Showing the last successful coverage result/)).toBeVisible()
  await expect(page.locator('.am-cov')).toHaveText('1/1')
  await page.getByRole('button', { name: 'Local note', exact: true }).click()
  const dialog = page.getByRole('dialog')
  const input = dialog.locator('textarea').last()
  await expect(input).toHaveValue('Existing note')
  await input.fill('First draft')
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('Note save unavailable')
  await expect(input).toBeEnabled()
  await expect(input).toHaveValue('First draft')
  await page.setViewportSize({ width: 390, height: 844 })
  await input.scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('attack-note-failure-mobile.png') })
  await input.fill('Corrected note')
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(noteWrites).toBe(2)
  expect(unexpected).toEqual([]); expect(errors).toEqual([])
})
