import { expect, test, type Page } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

const evidenceQuery = 'eventId="historical-event"'
const event = { eventId: 'historical-event', timestamp: '2026-08-01T12:00:00Z', source: 'auth', host: 'edge', severity: 'HIGH', msg: 'Suspicious login', fields: { path: 'C:\\logs\\"auth"' } }
const alarm = { id: 'alarm-1', title: 'Suspicious login', ruleId: 'r-1', ruleName: 'Login rule', severity: 'HIGH', status: 'OPEN', occurredAt: event.timestamp, entity: 'edge' }

async function mockInvestigation(page: Page) {
  const queries: URLSearchParams[] = []
  const unexpected: string[] = []
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (!isWorkbenchBackendUrl(url)) {
      await route.continue()
      return
    }
    const path = url.pathname
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [{ id: 'analyst' }] }
    else if (path === '/search-config/api/v1/meta/fields') data = []
    else if (path === '/search-config/api/v1/search') {
      queries.push(url.searchParams)
      data = { total: 100, events: [event], nextCursor: url.searchParams.get('cursor') ? null : 'next-page', source: 'opensearch', degraded: false, elapsedMs: 4, timeline: [{ key: '2026-08-01', count: 100 }] }
    } else if (path === '/alert-web/api/alarms/by-event') data = [alarm]
    else if (path === '/alert-web/api/alarms') data = { items: [alarm], total: 1, page: 1, size: 10, totalPages: 1 }
    else if (path === '/detect-web/api/v1/rules/options') data = { items: [], total: 0 }
    else if (path === '/alert-web/api/alarms/alarm-1/evidence') data = { alarmId: alarm.id, query: evidenceQuery, complete: true, total: 1, items: [{ ...event, id: 'ev-1', raw: event.msg, order: 0 }] }
    else if (path === '/alert-web/api/alarms/alarm-1/disposition') data = { status: 'OPEN', notes: [], assignee: null }
    else if (path === '/incident-web/api/v1/incidents') data = { items: [], total: 0 }
    else {
      unexpected.push(`${route.request().method()} ${path}`)
      await route.abort()
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  return { queries, unexpected }
}

test('search keeps drafts separate from results and restores investigation links', async ({ page }, testInfo) => {
  const { queries, unexpected } = await mockInvestigation(page)
  const initial = new URLSearchParams({ q: 'source=auth OR source=web', range: '1h', to: '2026-08-01T12:00:00.000Z', size: '25' })
  await page.goto(`/search?${initial}`)
  const editor = page.getByRole('textbox', { name: 'SPL query', exact: true })
  await expect(page.locator('.search-events-table')).toContainText('Suspicious login')
  expect(queries).toHaveLength(1)
  const applied = queries[0].get('q')
  await editor.fill('source=changed')
  await page.getByRole('button', { name: 'Next', exact: true }).click()
  await expect(page).toHaveURL(/page=2/)
  expect(queries.at(-1)?.get('q')).toBe(applied)
  expect(new URL(page.url()).searchParams.get('q')).toBe('source=auth OR source=web')
  await expect(page.getByRole('status')).toContainText('Query edited')
  await page.screenshot({ path: testInfo.outputPath('search-applied-and-draft.png'), fullPage: true })
  await editor.press('Enter')
  await expect(page).toHaveURL(url => url.searchParams.get('q') === 'source=changed')
  await expect(page.getByRole('status')).toHaveCount(0)
  await page.goBack()
  await expect(editor).toHaveValue('source=auth OR source=web')
  await expect(page.getByRole('button', { name: 'Run Search', exact: true })).not.toHaveClass(/is-loading/)
  expect(queries.at(-1)?.get('q')).toBe(applied)
  await page.reload()
  await expect(editor).toHaveValue('source=auth OR source=web')
  await expect(page.locator('.search-events-table')).toBeVisible()
  expect(queries.at(-1)?.get('limit')).toBe('25')
  expect(queries.at(-1)?.get('q')).toBe(applied)
  expect(unexpected).toEqual([])
})

test('event field pivots preserve values and return keyboard focus to the query', async ({ page }, testInfo) => {
  const { unexpected } = await mockInvestigation(page)
  await page.goto(`/search?${new URLSearchParams({ q: 'source=auth OR source=web | head 10', range: 'all' })}`)
  const row = page.getByRole('button', { name: 'Suspicious login', exact: true })
  await row.focus()
  await row.press('Enter')
  await page.getByRole('button', { name: 'Filter by value', exact: true }).click()
  const editor = page.getByRole('textbox', { name: 'SPL query', exact: true })
  await expect(editor).toHaveValue(String.raw`(source=auth OR source=web) AND path="C:\\logs\\\"auth\"" | head 10`)
  await expect(editor).toBeFocused()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('search-mobile.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  expect(unexpected).toEqual([])
})

test('historical alarm evidence opens a shareable search without a recent-time filter', async ({ page }) => {
  const { queries, unexpected } = await mockInvestigation(page)
  await page.goto('/alarms?alarmId=alarm-1')
  await page.getByRole('button', { name: 'Open in Log Search', exact: true }).click()
  await expect(page).toHaveURL(/\/search\?/)
  await expect(page.locator('.search-events-table')).toBeVisible()
  expect(new URL(page.url()).searchParams.get('range')).toBe('all')
  expect(new URL(page.url()).searchParams.get('q')).toBe(evidenceQuery)
  expect(queries.at(-1)?.get('q')).toBe(evidenceQuery)
  expect(unexpected).toEqual([])
})
