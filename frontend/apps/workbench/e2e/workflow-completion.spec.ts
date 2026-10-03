import { expect, test, type Page, type Route } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

const reply = (route: Route, data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status === 200 ? 0 : status, message: status === 200 ? '' : 'Write unavailable', data }) })
async function session(page: Page) {
  const unexpected: string[] = []
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await page.route(isWorkbenchBackendUrl, route => {
    const path = new URL(route.request().url()).pathname
    const defaults: Record<string, unknown> = {
      '/auth/session': { username: 'alice', role: 'admin', tenant: 'default' },
      '/auth/operators': { items: ['alice'] },
      '/api/v1/system/health': { status: 'up', services: {} },
      '/alert-web/api/alarms': { items: [], total: 0 },
      '/alert-web/api/alarms/stats': { total: 0 },
      '/incident-web/api/v1/stats': { total: 0 },
      '/detect-web/api/v1/rules/options': { items: [], total: 0 },
    }
    if (route.request().method() === 'GET' && Object.hasOwn(defaults, path)) {
      if (path.startsWith('/auth/')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(defaults[path]) })
      return reply(route, defaults[path])
    }
    unexpected.push(`${route.request().method()} ${path}`)
    return route.abort()
  })
  return unexpected
}

test('enabled file sources require a real target and output edits preserve binding and credential intent', async ({ page }) => {
  const unexpected = await session(page)
  let sourceWrites = 0
  let output = { id: 'out-stable', name: 'Receiver', type: 'HTTP', uri: 'https://example.test/ingest', authTokenConfigured: true, enabled: true }
  const edits: unknown[] = []
  await page.route('**/search-config/api/v1/**', route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (route.request().method() === 'PUT' && path.endsWith('/outputs/out-stable')) {
      const body = route.request().postDataJSON()
      edits.push(body); output = { ...output, ...body.target }; return reply(route, { ...output, authToken: undefined })
    }
    if (route.request().method() === 'POST' && path.endsWith('/sources')) {
      sourceWrites++; return reply(route, { ...route.request().postDataJSON(), id: 'source-new' })
    }
    if (route.request().method() !== 'GET') return route.fallback()
    if (path.endsWith('/outputs')) return reply(route, [output])
    if (path.endsWith('/sources') || path.endsWith('/parse-rules') || path.includes('parse-failures')) return reply(route, { items: [], total: 0 })
    if (path.endsWith('/summary')) return reply(route, {})
    if (path.endsWith('/tasks') || path.endsWith('/categories')) return reply(route, [])
    return route.fallback()
  })
  await page.goto('/ingest?tab=sources')
  await page.getByRole('button', { name: '+ Add Log Source', exact: true }).click()
  const drawer = page.locator('.el-drawer.open')
  await drawer.getByLabel('Source name', { exact: true }).fill('Audit logs')
  await drawer.getByRole('button', { name: 'Add Log Source', exact: true }).click()
  await expect(drawer).toContainText('Enter the collector target')
  expect(sourceWrites).toBe(0)
  await drawer.getByLabel('File path', { exact: true }).fill('/var/log/audit.log')
  await drawer.getByRole('button', { name: 'Add Log Source', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  expect(sourceWrites).toBe(1)
  await expect(page.getByRole('alert')).toContainText('does not mean the collector is running')
  await page.getByRole('tab', { name: 'Output Configuration', exact: true }).click()
  await page.getByRole('row').filter({ hasText: 'Receiver' }).getByRole('button', { name: 'Edit', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Edit', exact: true })
  await dialog.getByLabel('Name', { exact: true }).fill('Receiver renamed')
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  expect(edits).toEqual([expect.objectContaining({ credentialAction: 'KEEP', target: expect.objectContaining({ name: 'Receiver renamed', authToken: null }) })])
  await page.getByRole('row').filter({ hasText: 'Receiver renamed' }).getByRole('button', { name: 'Edit', exact: true }).click()
  await dialog.getByLabel('Credential update').press('Enter')
  await page.getByRole('option', { name: 'Clear credential', exact: true }).click()
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  expect(edits[1]).toMatchObject({ credentialAction: 'CLEAR' })
  expect(output.id).toBe('out-stable')
  expect(unexpected).toEqual([])
})

test('case notes retry the same durable identity and the next note gets a fresh identity', async ({ page }) => {
  const unexpected = await session(page)
  const writes: URLSearchParams[] = []
  const notes: Array<{ ts: string; message: string; type: string; source: string }> = []
  await page.route('**/incident-web/api/v1/incidents**', route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/notes')) {
      writes.push(url.searchParams)
      if (writes.length === 1) return reply(route, null, 503)
      notes.push({ ts: '2026-09-01T00:00:00Z', message: url.searchParams.get('content')!, type: 'NOTE', source: 'alice' })
      return reply(route, { added: true })
    }
    if (url.pathname.endsWith('/case-a')) return reply(route, { id: 'case-a', title: 'Investigation A', status: 'OPEN', severity: 'HIGH' })
    if (url.pathname.endsWith('/timeline')) return reply(route, { items: notes, total: notes.length })
    return reply(route, { items: [], total: 0 })
  })
  await page.goto('/cases?caseId=case-a')
  const drawer = page.locator('.el-drawer.open')
  await drawer.getByLabel('Notes', { exact: true }).fill('Evidence checked')
  await drawer.getByRole('button', { name: 'Add investigation note' }).click()
  await expect(drawer.getByRole('alert')).toBeVisible()
  await expect(drawer.getByLabel('Notes', { exact: true })).toHaveValue('Evidence checked')
  await drawer.getByRole('button', { name: 'Add investigation note' }).click()
  await expect(drawer.getByLabel('Notes', { exact: true })).toHaveValue('')
  await expect(drawer.getByRole('region', { name: 'Response Timeline' })).toContainText('Evidence checked')
  expect(writes[0].get('idempotencyKey')).toBe(writes[1].get('idempotencyKey'))
  await drawer.getByLabel('Notes', { exact: true }).fill('Second evidence')
  await drawer.getByRole('button', { name: 'Add investigation note' }).click()
  await expect(drawer.getByLabel('Notes', { exact: true })).toHaveValue('')
  expect(writes[2].get('idempotencyKey')).not.toBe(writes[1].get('idempotencyKey'))
  expect(notes).toHaveLength(2)
  expect(unexpected).toEqual([])
})

test('notification history requests real page and status scopes and can edit an off-page channel', async ({ page }) => {
  const unexpected = await session(page)
  const reads: URLSearchParams[] = []
  await page.route('**/notify-web/api/v1/**', route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/channels')) return reply(route, { items: [], total: 0 })
    if (url.pathname.endsWith('/channels/off-page')) return reply(route, { id: 'off-page', name: 'Off-page receiver', type: 'WEBHOOK', target: 'https://example.test/hook', enabled: false })
    if (url.pathname.endsWith('/dispatch-log')) {
      reads.push(url.searchParams)
      return reply(route, { total: 41, items: [{ channel: 'Off-page receiver', channelId: 'off-page', alarmId: 'alarm-a', type: 'WEBHOOK', status: 'failed', errorCode: 'invalid_auth', detail: 'Credential rejected', retryable: false, ts: '2026-09-01T00:00:00Z' }] })
    }
    return route.fallback()
  })
  await page.goto('/notify')
  const history = page.locator('.el-card').filter({ hasText: 'invalid_auth' })
  await history.locator('.el-pager').getByText('2', { exact: true }).click()
  await expect.poll(() => reads.at(-1)?.get('page')).toBe('2')
  await history.getByRole('combobox', { name: 'Status', exact: true }).press('Enter')
  await page.getByRole('option', { name: 'Failed', exact: true }).click()
  await expect.poll(() => reads.at(-1)?.get('status')).toBe('failed')
  expect(reads.at(-1)?.get('page')).toBe('1')
  await history.locator('.el-table__expand-icon').click()
  await expect(history).toContainText('Credential rejected')
  await history.getByRole('button', { name: 'Edit this channel' }).click()
  await expect(page.getByRole('dialog', { name: 'Edit', exact: true })).toContainText('Enabled channels receive default notifications for all tenant alarms')
  await expect(page.getByRole('dialog').getByLabel('Name', { exact: true })).toHaveValue('Off-page receiver')
  expect(unexpected).toEqual([])
})


test('alarm verdict records rationale without changing disposition or detection rules', async ({ page }) => {
  const unexpected = await session(page)
  const feedback: Array<Record<string, unknown>> = []
  const writes: string[] = []
  await page.route('**/alert-web/api/alarms/alarm-a**', route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'POST') {
      writes.push(path)
      feedback.push({ ...route.request().postDataJSON(), alarmId: 'alarm-a', id: 'verdict-1', actor: 'alice' })
      return reply(route, feedback.at(-1))
    }
    if (path.endsWith('/feedback')) return reply(route, feedback)
    if (path.endsWith('/disposition')) return reply(route, { status: 'OPEN', assignee: '', notes: [] })
    if (path.endsWith('/evidence')) return reply(route, { items: [], total: 0, complete: true })
    if (path.endsWith('/deliveries')) return reply(route, [])
    if (path.endsWith('/alarm-a')) return reply(route, { id: 'alarm-a', title: 'Investigate login', severity: 'HIGH', status: 'OPEN', occurredAt: '2026-09-01T00:00:00Z' })
    return route.fallback()
  })
  await page.route('**/incident-web/api/v1/incidents/by-alarm?**', route => reply(route, null, 404))
  await page.goto('/alarms?alarmId=alarm-a')
  const drawer = page.locator('.el-drawer.open')
  await drawer.getByRole('tab', { name: 'Verdict', exact: true }).click()
  await drawer.getByLabel('Describe the evidence and rationale').fill('Approved maintenance login')
  await drawer.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(drawer.getByLabel('Describe the evidence and rationale')).toHaveValue('')
  await expect(drawer).toContainText('Approved maintenance login')
  expect(feedback[0]).toMatchObject({ alarmId: 'alarm-a', kind: 'FALSE_POSITIVE', reason: 'Approved maintenance login' })
  expect(writes).toEqual(['/alert-web/api/alarms/alarm-a/feedback'])
  expect(unexpected).toEqual([])
})

test('partial AI investigation exposes missing evidence and navigable citations', async ({ page }) => {
  const unexpected = await session(page)
  const result = { investigationId: 'job-a', alertId: 'alarm-a', revision: 1, status: 'PARTIAL', analysis: 'Provisional conclusion',
    recommendedSpl: 'eventId="event-a"', timeline: [], hypotheses: [], nextActions: [], degradedSources: ['OpenSearch'],
    citations: [{ id: 'incident:case-a', source: 'incident-web', description: 'Related case' }], summaryAppended: true, incidentId: 'case-a' }
  await page.route('**/ai-assistant/api/v1/ai/investigations/**', route => reply(route, route.request().method() === 'POST' ? { jobId: 'job-a' } : result))
  await page.route('**/search-config/api/v1/meta/fields', route => reply(route, []))
  await page.route('**/search-config/api/v1/search?**', route => reply(route, { events: [], total: 0, source: 'opensearch' }))
  await page.goto('/assistant?alarmId=alarm-a')
  await expect(page.getByRole('alert')).toContainText('Missing evidence sources: OpenSearch')
  const citation = page.getByRole('link', { name: 'Related case', exact: true })
  await expect(citation).toHaveAttribute('href', '/cases?caseId=case-a')
  await page.getByRole('button', { name: 'Run in log search' }).click()
  await expect(page).toHaveURL(/\/search\?q=/)
  expect(new URL(page.url()).searchParams.get('q')).toBe('eventId="event-a"')
  expect(unexpected).toEqual([])
})

test('custom time is applied, shareable, and remains stable when the draft changes', async ({ page }) => {
  const unexpected = await session(page)
  const queries: string[] = []
  await page.route('**/search-config/api/v1/meta/fields', route => reply(route, []))
  await page.route('**/search-config/api/v1/search?**', route => {
    queries.push(new URL(route.request().url()).searchParams.get('q')!)
    return reply(route, { mode: 'events', events: [], total: 0, source: 'opensearch' })
  })
  await page.goto('/search?q=host%3Da%20OR%20host%3Db&range=all')
  await page.getByRole('button', { name: 'Custom time', exact: true }).click()
  await page.getByLabel('Start time', { exact: true }).fill('2026-09-01T08:00')
  await page.getByLabel('End time', { exact: true }).fill('2026-09-02T08:00')
  await page.getByRole('button', { name: 'Run Search', exact: true }).click()
  await expect.poll(() => queries.at(-1)).toContain('(host=a OR host=b) AND timestamp>=')
  const applied = queries.at(-1)
  expect(new URL(page.url()).searchParams.get('from')).toMatch(/Z$/)
  await page.getByLabel('Start time', { exact: true }).fill('2026-10-01T08:00')
  expect(queries.at(-1)).toBe(applied)
  await page.reload()
  await expect.poll(() => queries.at(-1)).toBe(applied)
  expect(unexpected).toEqual([])
})

test('my active queue and absolute time scope are shared by the URL and export', async ({ page }, testInfo) => {
  const unexpected = await session(page)
  const reads: URLSearchParams[] = []
  let exported: URLSearchParams | undefined
  await page.route('**/alert-web/api/alarms?**', route => {
    reads.push(new URL(route.request().url()).searchParams)
    return reply(route, { items: [], total: 0 })
  })
  await page.route('**/alert-web/api/alarms/export?**', route => {
    exported = new URL(route.request().url()).searchParams
    return route.fulfill({ contentType: 'text/csv', body: 'id,status\n' })
  })
  await page.goto('/alarms')
  await page.getByRole('button', { name: 'My queue', exact: true }).click()
  await expect.poll(() => reads.at(-1)?.get('assignee')).toBe('alice')
  expect(reads.at(-1)?.get('status')).toBe('ACTIVE')
  await page.getByLabel('Start time', { exact: true }).fill('2026-09-01T08:00')
  await page.getByLabel('End time', { exact: true }).fill('2026-09-02T08:00')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect.poll(() => reads.at(-1)?.get('from')).toMatch(/Z$/)
  await page.getByRole('button', { name: 'Export CSV', exact: true }).click()
  await expect.poll(() => exported?.get('from')).toBe(reads.at(-1)?.get('from'))
  expect(exported?.get('to')).toBe(reads.at(-1)?.get('to'))
  expect(exported?.get('assignee')).toBe('alice')
  expect(exported?.get('status')).toBe('ACTIVE')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('alarm-queue-mobile.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(unexpected).toEqual([])
})

test('an event becomes an isolated rule-test sample without an ingest or saved-rule write', async ({ page }, testInfo) => {
  const unexpected = await session(page)
  const event = { eventId: 'sample-a', timestamp: '2026-09-01T00:00:00Z', source: 'auth', host: 'edge', msg: 'Reviewed source event', fields: { user: 'alice' } }
  await page.route('**/search-config/api/v1/meta/fields', route => reply(route, []))
  await page.route('**/search-config/api/v1/search?**', route => reply(route, { mode: 'events', events: [event], total: 1, source: 'opensearch' }))
  await page.route('**/alert-web/api/alarms/by-event?**', route => reply(route, []))
  let submittedEvents: unknown
  await page.route('**/detect-web/api/v1/rules/test', route => {
    submittedEvents = route.request().postDataJSON().events
    return reply(route, [{ id: 'dry-run-draft', name: 'Draft', type: 'pattern', matched: false, alerts: [], eventCount: 1 }])
  })
  await page.route('**/detect-web/api/v1/**', route => {
    if (route.request().method() !== 'GET') return route.fallback()
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/rules')) return reply(route, { items: [], total: 0 })
    if (path.endsWith('/stats')) return reply(route, { rules: 0 })
    if (path.endsWith('/watchlists')) return reply(route, [])
    return route.fallback()
  })
  await page.route('**/attack-web/api/v1/techniques**', route => reply(route, { items: [], total: 0 }))
  await page.goto('/search?range=all')
  await page.getByRole('button', { name: 'Reviewed source event', exact: true }).click()
  await page.getByRole('button', { name: 'Use as detection sample', exact: true }).click()
  await expect(page).toHaveURL(/\/detect\/rules\/new\?sample=/)
  expect(page.url()).not.toContain('Reviewed')
  await expect(page.getByRole('status')).toContainText('isolated')
  await page.locator('.detect-editor-form input').first().fill('Reviewed event rule')
  await page.locator('.field-condition-row .el-input:not(.el-select .el-input) input').first().fill('Reviewed source event')
  await page.getByRole('button', { name: 'Test', exact: true }).click()
  const sample = page.locator('details').filter({ has: page.locator('textarea[placeholder^="[{"]') })
  await expect(sample.locator('textarea')).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Event message', exact: true })).toHaveValue(event.msg)
  await expect(sample.locator('textarea')).toHaveValue(JSON.stringify([event], null, 2))
  await page.getByRole('button', { name: 'Run isolated test', exact: true }).click()
  await expect.poll(() => submittedEvents).toEqual([event])
  await page.screenshot({ path: testInfo.outputPath('event-to-isolated-rule.png'), fullPage: true })
  expect(unexpected).toEqual([])
})

test('ATT&CK opens tenant rule associations and pivots to rule-scoped alarms', async ({ page }, testInfo) => {
  const unexpected = await session(page)
  const technique = { id: 'T1110', name: 'Brute Force', tactic: 'credential-access', description: 'Repeated credentials', url: 'https://attack.mitre.org/techniques/T1110/' }
  const scopes: URLSearchParams[] = []
  await page.route('**/attack-web/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/tactics')) return reply(route, { items: [{ id: 'credential-access', name: 'Credential Access' }] })
    if (path.endsWith('/techniques')) return reply(route, { items: [technique] })
    if (path.endsWith('/coverage')) return reply(route, { totalTechniques: 1, coveredTechniques: 1, coverage: 100, byTactic: [], uncovered: [] })
    return route.fallback()
  })
  await page.route('**/detect-web/api/v1/rules/active-techniques', route => reply(route, ['T1110']))
  await page.route('**/alert-web/api/alarms/technique-counts', route => reply(route, { from: '2026-09-01', until: '2026-09-02', counts: { T1110: 3 } }))
  await page.route('**/detect-web/api/v1/rules/by-technique?**', route => {
    scopes.push(new URL(route.request().url()).searchParams)
    return reply(route, { items: [{ id: 'rule-a', name: 'Login detection', status: 'ACTIVE' }], total: 21 })
  })
  await page.goto('/attack')
  await page.locator('.am-cell').click()
  const detail = page.getByRole('dialog', { name: 'T1110 · Brute Force' })
  await expect(detail).toContainText('Login detection')
  expect(scopes[0].get('technique')).toBe('T1110')
  await detail.locator('.el-pager').getByText('2', { exact: true }).click()
  await expect.poll(() => scopes.at(-1)?.get('page')).toBe('2')
  await page.screenshot({ path: testInfo.outputPath('attack-rule-pivot.png'), fullPage: true })
  await detail.getByRole('button', { name: 'Related alarms', exact: true }).click()
  await expect(page).toHaveURL(/\/alarms\?rule=rule-a/)
  expect(unexpected).toEqual([])
})
