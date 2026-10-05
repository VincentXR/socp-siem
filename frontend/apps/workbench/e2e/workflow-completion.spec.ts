import { expect, test, type Page, type Route } from '@playwright/test'
import { expectMobileNavigationClosed, isWorkbenchBackendUrl, mockInvestigationReadiness } from './helpers'

const reply = (route: Route, data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status === 200 ? 0 : status, message: status === 200 ? '' : 'Write unavailable', data }) })
async function session(page: Page) {
  const unexpected: string[] = []
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await page.route(isWorkbenchBackendUrl, route => {
    const path = new URL(route.request().url()).pathname
    const defaults: Record<string, unknown> = {
      '/auth/session': { username: 'alice', role: 'admin', tenant: 'default' },
      '/auth/operators': { items: [{ id: 'alice', label: 'Alice', role: 'analyst', current: true }, { id: 'bob-subject', label: 'Bob', role: 'analyst', current: false }] },
      '/api/v1/system/health': { status: 'up', services: {} },
      '/alert-web/api/alarms': { items: [], total: 0 },
      '/alert-web/api/alarms/stats': { total: 0 },
      '/alert-web/api/v1/suppressions': [],
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
  await mockInvestigationReadiness(page)
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
    if (path.endsWith('/sources/source-new/setup')) return reply(route, { source: { id: 'source-new', name: 'Audit logs', type: 'FILE', path: '/var/log/audit.log', enabled: true }, collectorTag: 'search-source-new', nativeVector: true, appliedState: 'UNKNOWN', configurationVersion: 'saved-v1', output: null, problems: [], pipeline: [], pipelineMode: 'BUILTIN_THEN_SPARSE_FALLBACK' })
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
  await expect(drawer).toContainText('Complete this connection field before enabling')
  expect(sourceWrites).toBe(0)
  await drawer.getByLabel('File path', { exact: true }).fill('/var/log/audit.log')
  await drawer.getByRole('button', { name: 'Add Log Source', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Add Log Source', exact: true })).not.toBeVisible()
  await expect(page.getByRole('dialog', { name: 'Guided setup', exact: true })).toBeVisible()
  await page.getByRole('dialog', { name: 'Guided setup', exact: true }).getByRole('button', { name: 'Close this dialog' }).click()
  expect(sourceWrites).toBe(1)
  await expect(page.getByRole('alert').filter({ hasText: 'does not mean the collector is running' })).toBeVisible()
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
  const writes: Array<{ content: string; idempotencyKey: string }> = []
  const notes: Array<{ ts: string; message: string; type: string; source: string }> = []
  await page.route('**/incident-web/api/v1/incidents**', route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/notes')) {
      const body = route.request().postDataJSON() as { content: string; idempotencyKey: string }
      writes.push(body)
      if (writes.length === 1) return reply(route, null, 503)
      notes.push({ ts: '2026-09-01T00:00:00Z', message: body.content, type: 'NOTE', source: 'alice' })
      return reply(route, { case: { id: 'case-a', title: 'Investigation A', status: 'OPEN', severity: 'HIGH', rowVersion: notes.length, alarmIds: [], ruleIds: [], timeline: [] } })
    }
    if (url.pathname.endsWith('/case-a')) return reply(route, { id: 'case-a', title: 'Investigation A', status: 'OPEN', severity: 'HIGH' })
    if (url.pathname.endsWith('/timeline')) return reply(route, { items: notes, total: notes.length })
    return reply(route, { items: [], total: 0 })
  })
  await page.goto('/cases?caseId=case-a')
  const drawer = page.locator('.el-drawer.open')
  await drawer.getByLabel('Investigation note', { exact: true }).fill('Evidence checked')
  await drawer.getByRole('button', { name: 'Add note', exact: true }).click()
  await expect(drawer.getByRole('alert')).toBeVisible()
  await expect(drawer.getByLabel('Investigation note', { exact: true })).toHaveValue('Evidence checked')
  await drawer.getByRole('button', { name: 'Add note', exact: true }).click()
  await expect(drawer.getByLabel('Investigation note', { exact: true })).toHaveValue('')
  await expect(drawer.getByRole('region', { name: 'Response Timeline' })).toContainText('Evidence checked')
  expect(writes[0].idempotencyKey).toBe(writes[1].idempotencyKey)
  await drawer.getByLabel('Investigation note', { exact: true }).fill('Second evidence')
  await drawer.getByRole('button', { name: 'Add note', exact: true }).click()
  await expect(drawer.getByLabel('Investigation note', { exact: true })).toHaveValue('')
  expect(writes[2].idempotencyKey).not.toBe(writes[1].idempotencyKey)
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
  await history.getByRole('combobox', { name: 'Delivery status', exact: true }).press('Enter')
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


test('alarm verdict stays independent while suppression requires scope confirmation and can be released', async ({ page }, testInfo) => {
  const unexpected = await session(page)
  const feedback: Array<Record<string, unknown>> = []
  const writes: string[] = []
  let windows: Array<Record<string, unknown>> = []
  const suppressionWrites: Array<Record<string, unknown>> = []
  await page.route('**/alert-web/api/v1/suppressions**', route => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON()
      suppressionWrites.push(body)
      windows = [{ ...body, id: 'window-1', expiresAt: '2026-10-06T00:00:00Z' }]
      return reply(route, windows[0])
    }
    if (route.request().method() === 'DELETE') {
      expect(new URL(route.request().url()).searchParams.get('entity')).toBe('host-a')
      windows = []; return reply(route, null)
    }
    return reply(route, windows)
  })
  await page.route('**/alert-web/api/alarms/alarm-a**', route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'POST') {
      writes.push(path)
      feedback.push({ ...route.request().postDataJSON(), alarmId: 'alarm-a', id: 'verdict-1', actor: 'alice' })
      return reply(route, feedback.at(-1))
    }
    if (path.endsWith('/feedback')) return reply(route, feedback)
    if (path.endsWith('/similar')) return reply(route, [])
    if (path.endsWith('/disposition')) return reply(route, { status: 'OPEN', assignee: '', notes: [] })
    if (path.endsWith('/evidence')) return reply(route, { items: [], total: 0, complete: true })
    if (path.endsWith('/deliveries')) return reply(route, [])
    if (path.endsWith('/alarm-a')) return reply(route, { id: 'alarm-a', ruleId: 'rule-a', entity: 'host-a', title: 'Investigate login', severity: 'HIGH', status: 'OPEN', occurredAt: '2026-09-01T00:00:00Z' })
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
  expect(suppressionWrites).toEqual([])
  const panel = drawer.locator('.suppression-panel')
  await panel.getByLabel('Suppression rationale', { exact: true }).fill('Planned scanner for this host')
  await panel.getByLabel('Suppression duration', { exact: true }).selectOption('1')
  await panel.getByRole('button', { name: 'Confirm suppression or extension' }).click()
  const confirmation = page.locator('.el-message-box')
  await expect(confirmation).toContainText('host-a')
  expect(suppressionWrites).toEqual([])
  await confirmation.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(panel.getByLabel('Suppression rationale', { exact: true })).toHaveValue('Planned scanner for this host')
  await panel.getByRole('button', { name: 'Confirm suppression or extension' }).click()
  await confirmation.getByRole('button', { name: 'Confirm', exact: true }).click()
  await expect(panel.getByRole('button', { name: 'Release window' })).toBeVisible()
  expect(suppressionWrites).toEqual([expect.objectContaining({ ruleId: 'rule-a', entity: 'host-a', ruleWide: false, windowSeconds: 3600 })])
  await expect(confirmation).not.toBeVisible()
  await panel.getByRole('heading', { name: 'Future alarm suppression windows' }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('suppression-window.png'), fullPage: true, animations: 'disabled' })
  await panel.getByRole('button', { name: 'Release window' }).click()
  await confirmation.getByRole('button', { name: 'Confirm', exact: true }).click()
  await expect(panel.getByRole('button', { name: 'Release window' })).toHaveCount(0)
  expect(unexpected).toEqual([])
})

test('partial AI investigation exposes missing evidence and navigable citations', async ({ page }) => {
  const unexpected = await session(page)
  const searches: string[] = []
  const result = { investigationId: 'job-a', alertId: 'alarm-a', revision: 1, status: 'PARTIAL', analysis: 'Provisional conclusion',
    recommendedSpl: 'eventId="event-a"', timeline: [], hypotheses: [], nextActions: [], degradedSources: ['OpenSearch'],
    citations: [{ id: 'incident:case-a', source: 'incident-web', description: 'Related case' }], summaryAppended: true, incidentId: 'case-a' }
  await page.route('**/ai-assistant/api/v1/ai/investigations/**', route => reply(route, route.request().method() === 'POST' ? { jobId: 'job-a' } : result))
  await page.route('**/search-config/api/v1/meta/fields', route => reply(route, []))
  await page.route('**/search-config/api/v1/search?**', route => {
    searches.push(new URL(route.request().url()).searchParams.get('q')!)
    return reply(route, { events: [], total: 0, source: 'opensearch' })
  })
  await page.goto('/assistant?alarmId=alarm-a')
  await expect(page.getByRole('alert')).toContainText('Missing evidence sources: OpenSearch')
  const citation = page.getByRole('link', { name: 'Related case', exact: true })
  await expect(citation).toHaveAttribute('href', '/cases?caseId=case-a')
  await page.getByRole('button', { name: 'Review query in Search', exact: true }).click()
  await expect(page).toHaveURL(/\/search\?draft=/)
  expect(new URL(page.url()).searchParams.get('draft')).toBe('eventId="event-a"')
  expect(searches).toEqual([])
  await page.getByRole('button', { name: 'Run Search', exact: true }).click()
  await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('eventId="event-a"')
  expect(new URL(page.url()).searchParams.has('draft')).toBe(false)
  await expect.poll(() => searches).toEqual(['eventId="event-a"'])
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
  await page.getByRole('button', { name: 'Custom', exact: true }).click()
  await page.getByLabel('From (UTC)', { exact: true }).fill('2026-09-01T08:00')
  await page.getByLabel('To (UTC)', { exact: true }).fill('2026-09-02T08:00')
  await page.getByRole('button', { name: 'Run Search', exact: true }).click()
  await expect.poll(() => queries.at(-1)).toContain('(host=a OR host=b) AND timestamp>=')
  const applied = queries.at(-1)
  expect(new URL(page.url()).searchParams.get('from')).toMatch(/Z$/)
  await page.getByLabel('From (UTC)', { exact: true }).fill('2026-10-01T08:00')
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
  await page.getByRole('combobox', { name: 'Filter by Status', exact: true }).press('Enter')
  await page.getByRole('option', { name: 'All statuses', exact: true }).click()
  await expect.poll(() => reads.at(-1)?.get('status')).toBeNull()
  exported = undefined
  await page.getByRole('button', { name: 'Export CSV', exact: true }).click()
  await expect.poll(() => exported?.get('status')).toBeNull()
  expect(exported?.get('assignee')).toBe('alice')
  await page.setViewportSize({ width: 390, height: 844 })
  await expectMobileNavigationClosed(page)
  await page.screenshot({ path: testInfo.outputPath('alarm-queue-mobile.png'), fullPage: true, animations: 'disabled' })
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
  const alarmScopes: URLSearchParams[] = []
  await page.route('**/attack-web/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/tactics')) return reply(route, { items: [{ id: 'credential-access', name: 'Credential Access' }] })
    if (path.endsWith('/techniques')) return reply(route, { items: [technique] })
    if (path.endsWith('/techniques/T1110/note')) return reply(route, { note: 'Reviewed technique context' })
    if (path.endsWith('/coverage')) return reply(route, { totalTechniques: 1, coveredTechniques: 1, coverage: 100, byTactic: [], uncovered: [] })
    return route.fallback()
  })
  await page.route('**/detect-web/api/v1/rules/active-techniques', route => reply(route, ['T1110']))
  await page.route('**/alert-web/api/alarms/technique-counts', route => reply(route, { from: '2026-09-01', until: '2026-09-02', counts: { T1110: 3 } }))
  await page.route('**/alert-web/api/alarms?**', route => {
    alarmScopes.push(new URL(route.request().url()).searchParams)
    return reply(route, { items: [], total: 0 })
  })
  await page.route('**/detect-web/api/v1/rules/by-technique?**', route => {
    scopes.push(new URL(route.request().url()).searchParams)
    return reply(route, { items: [{ id: 'rule-a', name: 'Login detection', status: 'ACTIVE' }], total: 21 })
  })
  await page.goto('/attack')
  await page.locator('.am-cell').click()
  const detail = page.getByRole('dialog', { name: 'Technique details', exact: true })
  await expect(detail).toContainText('Login detection')
  await expect(detail).toContainText('Reviewed technique context')
  expect(scopes[0].get('technique')).toBe('T1110')
  expect(alarmScopes[0].get('technique')).toBe('T1110')
  await detail.locator('.el-pager').getByText('2', { exact: true }).click()
  await expect.poll(() => scopes.at(-1)?.get('page')).toBe('2')
  await page.screenshot({ path: testInfo.outputPath('attack-rule-pivot.png'), fullPage: true })
  await detail.getByRole('button', { name: 'Related alarms', exact: true }).click()
  await expect(page).toHaveURL(/\/alarms\?rule=rule-a&technique=T1110/)
  expect(unexpected).toEqual([])
})

test('case evidence association keeps investigation controls visible and reviews an alarm before linking', async ({ page }, testInfo) => {
  const unexpected = await session(page)
  let linked = false
  const writes: Array<Record<string, unknown>> = []
  const incident = () => ({ id: 'case-a', title: 'Investigation A', entity: 'host-a', status: 'OPEN', severity: 'HIGH', rowVersion: linked ? 1 : 0, alarmIds: [], ruleIds: [], timeline: [] })
  await page.route('**/incident-web/api/v1/incidents**', route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/alarm-associations')) {
      writes.push(route.request().postDataJSON()); linked = true
      return reply(route, { case: incident(), changed: true, duplicate: false })
    }
    if (path.endsWith('/case-a')) return reply(route, incident())
    if (path.endsWith('/alarms')) return reply(route, { items: linked ? ['alarm-a'] : [], total: linked ? 1 : 0 })
    return reply(route, { items: [], total: 0 })
  })
  await page.route('**/alert-web/api/alarms/alarm-a', route => reply(route, { id: 'alarm-a', title: 'Suspicious SSH login', entity: 'host-a', severity: 'HIGH', status: 'OPEN' }))
  await page.goto('/cases?caseId=case-a')
  const drawer = page.locator('.el-drawer.open')
  await expect(drawer.getByLabel('Investigation note', { exact: true })).toBeVisible()
  await drawer.getByRole('combobox', { name: 'Assignee', exact: true }).click()
  await expect(page.getByRole('option', { name: 'Bob (bob-subject)', exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await page.screenshot({ path: testInfo.outputPath('case-investigation-tab.png'), fullPage: true, animations: 'disabled' })
  await drawer.getByRole('tab', { name: 'Linked evidence', exact: true }).click()
  await drawer.getByRole('button', { name: 'Link alarm', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Review alarm association', exact: true })
  await dialog.getByLabel('Alarm ID', { exact: true }).fill('alarm-a')
  await dialog.getByRole('button', { name: 'Load alarm for review', exact: true }).click()
  await expect(dialog).toContainText('Suspicious SSH login')
  await dialog.getByLabel('Association change reason', { exact: true }).fill('Matches this investigation timeline')
  await page.screenshot({ path: testInfo.outputPath('case-association-review.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(dialog.getByRole('button', { name: 'Confirm', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('case-association-mobile.png'), fullPage: true, animations: 'disabled' })
  await dialog.getByRole('button', { name: 'Confirm', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(drawer.getByRole('button', { name: 'Suspicious SSH login', exact: true })).toBeVisible()
  expect(writes).toEqual([expect.objectContaining({ operation: 'ATTACH', alarmId: 'alarm-a', expectedVersion: 0, reason: 'Matches this investigation timeline', idempotencyKey: expect.any(String) })])
  expect(unexpected).toEqual([])
})
