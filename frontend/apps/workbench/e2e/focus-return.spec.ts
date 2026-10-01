import { expect, test, type Page } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

async function mockReads(page: Page) {
  const unexpected: string[] = []
  const errors: string[] = []
  const entity = { entity: 'host-a', risk: 30, level: 'MEDIUM', alerts: 1, critical: false,
    maxSeverity: 'MEDIUM', firstSeen: null, lastSeen: null, mitre: [], topRules: [] }
  const alarm = { id: 'alarm-a', title: 'Suspicious login', ruleId: 'rule-a', ruleName: 'Login rule',
    severity: 'HIGH', status: 'OPEN', occurredAt: '2026-09-21T01:00:00Z', entity: 'host-a' }
  const incident = { id: 'case-a', title: 'Login investigation', entity: 'host-a', severity: 'HIGH',
    status: 'OPEN', assignee: '', alarmIds: [], ruleIds: [], timeline: [] }
  const responses: Record<string, unknown> = {
    '/auth/session': { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' },
    '/auth/operators': { items: [] },
    '/api/v1/system/health': { status: 'up', services: {} },
    '/detect-web/api/v1/ueba/entities': [entity],
    '/detect-web/api/v1/ueba/summary': { entities: 1, byLevel: { MEDIUM: 1 }, maxRisk: 30, halfLifeHours: 24 },
    '/detect-web/api/v1/ueba/entities/host-a': entity,
    '/detect-web/api/v1/watchlists': [],
    '/detect-web/api/v1/ueba/score': { score: 10, level: 'LOW', breakdown: {} },
    '/attack-web/api/v1/techniques': { items: [], total: 0 },
    '/alert-web/api/alarms': { items: [alarm], total: 1, page: 1, size: 20, totalPages: 1 },
    '/alert-web/api/alarms/stats': { total: 1, bySeverity: { HIGH: 1 }, byRule: {}, trend: [] },
    '/detect-web/api/v1/rules/options': { items: [], total: 0 },
    '/alert-web/api/alarms/alarm-a/disposition': { status: 'OPEN', notes: [], assignee: null },
    '/alert-web/api/alarms/alarm-a/evidence': { alarmId: 'alarm-a', total: 0, complete: true, items: [] },
    '/alert-web/api/alarms/alarm-a/deliveries': [],
    '/incident-web/api/v1/incidents/by-alarm': incident,
    '/incident-web/api/v1/incidents': { items: [incident], total: 1 },
    '/incident-web/api/v1/incidents/case-a': incident,
    '/incident-web/api/v1/incidents/case-a/alarms': { items: [], total: 0 },
    '/incident-web/api/v1/incidents/case-a/rules': { items: [], total: 0 },
    '/incident-web/api/v1/incidents/case-a/timeline': { items: [], total: 0 },
    '/incident-web/api/v1/stats': { total: 1, open: 1, resolved: 0 },
  }
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    if (route.request().method() !== 'GET' || !Object.hasOwn(responses, url.pathname)) {
      unexpected.push(`${route.request().method()} ${url.pathname}`)
      await route.abort()
      return
    }
    const data = responses[url.pathname]
    await route.fulfill({ contentType: 'application/json',
      body: JSON.stringify(url.pathname.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  return { unexpected, errors }
}

test('UEBA keeps mouse focus in its table and restores keyboard or removed openers', async ({ page }) => {
  const evidence = await mockReads(page)
  await page.goto('/ueba')
  const row = page.getByRole('button', { name: 'host-a', exact: true })
  await expect(row).toBeVisible()
  // This cell opens the same drawer without focusing the keyboard row control.
  await page.locator('.el-table__body tr').first().locator('td').first().click()
  const drawer = page.getByRole('dialog', { name: 'host-a', exact: true })
  await expect(drawer).toBeVisible()
  await drawer.getByRole('button', { name: 'Close this dialog' }).click()
  await expect(drawer).not.toBeVisible()
  // Element Plus focuses tbody for pointer row activation. Preserve that valid
  // native return target; the next Tab must continue into this table's row.
  await expect(page.locator('.el-table__body tbody').first()).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(row).toBeFocused()

  await row.focus()
  await row.press('Enter')
  await expect(drawer).toBeVisible()
  await drawer.press('Escape')
  await expect(drawer).not.toBeVisible()
  await expect(row).toBeFocused()

  await row.press('Enter')
  await expect(drawer).toBeVisible()
  await row.evaluate(element => element.remove())
  await drawer.getByRole('button', { name: 'Close this dialog' }).click()
  await expect(drawer).not.toBeVisible()
  await expect(page.locator('#main-content')).toBeFocused()
  expect(evidence.unexpected).toEqual([])
  expect(evidence.errors).toEqual([])
})

test('alarm triage returns keyboard focus after the real close transition', async ({ page }) => {
  const evidence = await mockReads(page)
  await page.goto('/alarms')
  const opener = page.getByRole('button', { name: 'Triage', exact: true })
  await opener.focus()
  await opener.press('Enter')
  const drawer = page.getByRole('dialog')
  await expect(drawer).toContainText('Suspicious login')
  await drawer.getByRole('button', { name: 'Close this dialog' }).click()
  await expect(drawer).not.toBeVisible()
  await expect(opener).toBeFocused()
  expect(evidence.unexpected).toEqual([])
  expect(evidence.errors).toEqual([])
})

test('case details restore the opener after closing its route-owned drawer', async ({ page }) => {
  const evidence = await mockReads(page)
  await page.goto('/cases')
  const opener = page.getByRole('button', { name: 'Details', exact: true })
  await opener.focus()
  await opener.press('Enter')
  const drawer = page.getByRole('dialog')
  await expect(drawer).toContainText('Login investigation')
  await expect(page).toHaveURL(/caseId=case-a/)
  await drawer.getByRole('button', { name: 'Close this dialog' }).click()
  await expect(page).not.toHaveURL(/caseId=/)
  await expect(drawer).not.toBeVisible()
  await expect(opener).toBeFocused()
  expect(evidence.unexpected).toEqual([])
  expect(evidence.errors).toEqual([])
})
