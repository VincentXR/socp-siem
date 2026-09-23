import { expect, test, type Page } from '@playwright/test'
import type { CaseInfo } from '../src/api/models'
import { readFile } from 'node:fs/promises'
import { workbenchOrigin, isWorkbenchBackendUrl as isMockedBackendUrl } from './helpers'

const WORKBENCH_ORIGIN = workbenchOrigin()

function isAuthSessionUrl(url: URL): boolean {
  return url.origin === WORKBENCH_ORIGIN && url.pathname === '/auth/session'
}

function isAuthLoginUrl(url: URL): boolean {
  return url.origin === WORKBENCH_ORIGIN && url.pathname === '/auth/login'
}

/**
 * Register before the endpoint mocks so Playwright's newest-first route
 * matching lets explicit mocks handle known calls and this route catches any
 * newly introduced backend call that the test did not model.
 */
async function installNetworkGuard(page: Page): Promise<string[]> {
  const unexpected: string[] = []
  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (isMockedBackendUrl(url)) {
      unexpected.push(`${request.method()} ${url.pathname}`)
      await route.abort()
      return
    }
    await route.continue()
  })
  return unexpected
}

async function mockSession(page: Page, role: 'viewer' | 'analyst' | 'admin' = 'analyst') {
  await mockWorkbenchReads(page)
  await page.route(isAuthSessionUrl, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ username: `${role}-user`, role, tenant: 'default' }),
  }))
}

async function mockWorkbenchReads(page: Page) {
  const responses: Record<string, unknown> = {
    '/auth/operators': { items: [] },
    '/api/v1/system/health': { status: 'up', services: {}, checkedAt: '2026-09-20T00:00:00Z' },
    '/alert-web/api/alarms': { items: [], total: 0, page: 1, size: 10, totalPages: 0 },
    '/alert-web/api/alarms/stats': { total: 0, bySeverity: {}, byRule: {}, trend: [] },
    '/incident-web/api/v1/stats': { total: 0, open: 0, resolved: 0 },
    '/detect-web/api/v1/rules/options': { items: [], total: 0 },
  }
  await page.route(isMockedBackendUrl, async route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'GET' && Object.hasOwn(responses, path)) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(responses[path]) })
    } else {
      await route.fallback()
    }
  })
}

test('endpoint deep links page complete associations and keep unregister failures reviewable', async ({ page }, testInfo) => {
  const unexpected = await installNetworkGuard(page)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await mockSession(page)
  const endpoint = { id: 'outside', hostname: 'linked-host', ip: '203.0.113.7', os: 'Linux', status: 'ONLINE', agentVersion: 'agent', lastHeartbeat: '2026-09-21T01:00:00Z' }
  let historyReads = 0
  let deletes = 0
  let deleted = false
  let releaseDelete!: () => void
  const pending = new Promise<void>(resolve => { releaseDelete = resolve })
  const historyPages: number[] = []
  const assetPages: number[] = []
  await page.route('**/hips-web/api/v1/endpoints**', async route => {
    const url = new URL(route.request().url())
    const fulfill = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status === 200 ? 0 : status, message: 'Endpoint service unavailable', data }) })
    if (url.pathname.endsWith('/stats')) return fulfill({ total: 120, online: 30, events: 5000 })
    if (url.pathname.endsWith('/endpoints')) return deleted ? fulfill(null, 503) : fulfill({ items: [{ ...endpoint, id: 'listed', hostname: 'Other host' }], total: 120 })
    if (url.pathname.endsWith('/outside/events')) {
      historyReads++
      if (historyReads === 1) return fulfill(null, 503)
      const requestedPage = Number(url.searchParams.get('page'))
      const size = Number(url.searchParams.get('size'))
      expect(size).toBe(20); historyPages.push(requestedPage)
      return fulfill({ items: Array.from({ length: 20 }, (_, index) => ({ eventId: `event-${(requestedPage - 1) * size + index}`, type: 'process', message: `Evidence ${(requestedPage - 1) * size + index}`, receivedAt: '2026-09-21T01:00:00Z' })), total: 61 })
    }
    if (url.pathname.endsWith('/outside') && route.request().method() === 'DELETE') {
      deletes++
      if (deletes === 1) { await pending; return fulfill(null, 503) }
      deleted = true; return fulfill({ removed: true })
    }
    if (url.pathname.endsWith('/outside')) return fulfill(endpoint)
    await route.fallback()
  })
  await page.route('**/asset-web/api/v1/assets/related?**', async route => {
    const url = new URL(route.request().url())
    expect(url.searchParams.get('ip')).toBe(endpoint.ip)
    expect(url.searchParams.get('name')).toBe(endpoint.hostname)
    const requestedPage = Number(url.searchParams.get('page'))
    expect(url.searchParams.get('size')).toBe('20'); assetPages.push(requestedPage)
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: Array.from({ length: 20 }, (_, index) => ({ id: `asset-${requestedPage}-${index}`, name: `Matching asset ${requestedPage}-${index}`, ip: endpoint.ip, type: 'SERVER', criticality: 'HIGH', owner: 'security' })), total: 42 }) })
  })
  await page.goto('/endpoints?page=2&q=unrelated&endpointId=outside')
  const drawer = page.locator('.el-drawer')
  await expect(drawer).toContainText('linked-host')
  const history = drawer.getByTestId('endpoint-events')
  const assets = drawer.getByTestId('endpoint-assets')
  await expect(history).toContainText('Endpoint service unavailable')
  await expect(history).not.toContainText('No events match')
  await history.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(history).toContainText('Evidence 0')
  await expect(history).toContainText('do not establish device identity')
  await history.locator('.btn-next').click()
  await drawer.getByRole('tab', { name: 'Matching Assets', exact: true }).click()
  await assets.locator('.btn-next').click()
  await expect(assets).toContainText('Matching asset 2-0')
  await drawer.getByRole('tab', { name: 'Events with This Hostname', exact: true }).click()
  await expect(history).toContainText('Evidence 20')
  expect(historyPages).toContain(2); expect(assetPages).toContain(2)
  await page.setViewportSize({ width: 390, height: 844 })
  await history.locator('.el-pagination').scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('endpoint-history-mobile.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  const pagerBox = await history.locator('.el-pagination').boundingBox()
  expect(pagerBox).not.toBeNull(); expect(pagerBox!.x + pagerBox!.width).toBeLessThanOrEqual(390)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await drawer.getByRole('button', { name: 'Unregister', exact: true }).click()
  await expect(page.locator('.el-message-box')).toContainText('outside')
  await page.locator('.el-message-box').getByRole('button', { name: 'Confirm', exact: true }).click()
  await expect(drawer.getByRole('button', { name: 'Unregister', exact: true })).toBeDisabled()
  await drawer.locator('.el-drawer__close-btn').click()
  await expect(page).toHaveURL(/endpointId=outside/)
  expect(deletes).toBe(1)
  releaseDelete()
  await expect(drawer.locator('[role="alert"]')).toContainText('Endpoint service unavailable')
  await expect(drawer.locator('[role="alert"]')).toBeInViewport()
  await page.screenshot({ path: testInfo.outputPath('endpoint-unregister-retry.png'), fullPage: true })
  await drawer.getByRole('button', { name: 'Unregister', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: 'Confirm', exact: true }).click()
  await expect(page).not.toHaveURL(/endpointId=/)
  expect(deletes).toBe(2)
  await expect(page).toHaveURL(/page=2/)
  expect(errors).toEqual([]); expect(unexpected).toEqual([])
})

test('asset links use complete paged associations and retain reviewed writes through failures', async ({ page }, testInfo) => {
  const unexpected = await installNetworkGuard(page)
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await mockSession(page)
  let asset = { id: 'outside-page', name: 'Linked asset', ip: '203.0.113.7', type: 'SERVER', os: 'Linux', owner: 'sec', criticality: 'HIGH' }
  let writes = 0
  let imports = 0
  let releaseWrite!: () => void
  const pendingWrite = new Promise<void>(resolve => { releaseWrite = resolve })
  const associations: string[] = []
  await page.route('**/asset-web/api/v1/assets**', async route => {
    const url = new URL(route.request().url())
    const method = route.request().method()
    const fulfill = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ code: status === 200 ? 0 : status, message: status === 200 ? '' : 'Asset write unavailable', data }) })
    if (url.pathname.endsWith('/stats')) return fulfill({ total: 1000, byType: { SERVER: 1000 }, byCriticality: { HIGH: 1000 } })
    if (url.pathname.endsWith('/assets') && method === 'GET') return fulfill({ items: [{ ...asset, id: 'listed', name: 'A different listed asset' }], total: 1000 })
    if (url.pathname.endsWith('/outside-page') && method === 'GET') return fulfill(asset)
    if (url.pathname.endsWith('/outside-page') && method === 'PUT') {
      writes++
      if (writes === 1) { await pendingWrite; return fulfill(null, 503) }
      asset = { ...asset, ...route.request().postDataJSON() }
      return fulfill(asset)
    }
    if (url.pathname.endsWith('/import') && method === 'POST') {
      imports++
      expect(route.request().postDataJSON()).toEqual([expect.objectContaining({ name: 'Reviewed import', ip: '203.0.113.9', type: 'SERVER' })])
      return imports === 1 ? fulfill(null, 503) : fulfill({ imported: 1, skipped: 0, errors: [] })
    }
    await route.fallback()
  })
  await page.route('**/hips-web/api/v1/endpoints/related?**', async route => {
    const url = new URL(route.request().url())
    expect(url.searchParams.get('ip')).toBe(asset.ip)
    expect(url.searchParams.get('hostname')).toBe(asset.name)
    const current = Number(url.searchParams.get('page'))
    const size = Number(url.searchParams.get('size'))
    associations.push(`${current}:${size}`)
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: Array.from({ length: size }, (_, index) => ({
      id: `agent-${current}-${index}`, hostname: `Related host ${(current - 1) * size + index + 1}`, ip: asset.ip, os: 'Linux',
      status: 'ONLINE', agentVersion: 'agent', lastHeartbeat: '2026-09-21T01:00:00Z',
    })), total: 61 }) })
  })
  await page.goto('/assets?page=2&q=unrelated&assetId=outside-page')
  const drawer = page.locator('.el-drawer')
  await expect(drawer).toContainText('Linked asset')
  const related = drawer.getByRole('region', { name: 'Related Endpoints' })
  await expect(related).toContainText('Related host 1')
  await related.locator('.el-pager').getByText('2', { exact: true }).click()
  await expect(related).toContainText('Related host 21')
  expect(associations).toEqual(['1:20', '2:20'])
  await drawer.getByRole('button', { name: 'Edit', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Edit Asset', exact: true })
  await dialog.getByPlaceholder('e.g. web-prod-01').fill('Reviewed asset name')
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog.getByPlaceholder('e.g. web-prod-01')).toBeDisabled()
  await dialog.getByRole('button', { name: 'Close this dialog' }).click()
  await expect(dialog).toBeVisible()
  expect(writes).toBe(1)
  releaseWrite()
  await expect(dialog.getByRole('alert')).toContainText('Asset write unavailable')
  await expect(dialog.getByPlaceholder('e.g. web-prod-01')).toHaveValue('Reviewed asset name')
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(drawer).toContainText('Reviewed asset name')
  await drawer.locator('.el-drawer__close-btn').click()
  await expect(page).toHaveURL(/page=2&q=unrelated$/)
  await page.locator('input[type="file"]').setInputFiles({ name: 'assets.json', mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify([{ name: 'Reviewed import', ip: '203.0.113.9' }])) })
  const preview = page.getByRole('dialog', { name: 'Review asset import', exact: true })
  await expect(preview).toContainText('Reviewed import')
  expect(imports).toBe(0)
  await preview.getByRole('button', { name: 'Import reviewed rows', exact: true }).click()
  await expect(preview.getByRole('status')).toContainText('resubmitting can create duplicates')
  await expect(page.locator('.el-message:visible')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('asset-import-unconfirmed.png'), fullPage: true })
  await preview.getByRole('button', { name: 'Import reviewed rows', exact: true }).click()
  await expect(preview).not.toBeVisible()
  await expect(page.getByRole('status')).toContainText('Imported 1, skipped 0')
  expect(imports).toBe(2)
  await page.goBack()
  await expect(drawer).toContainText('Reviewed asset name')
  await page.setViewportSize({ width: 390, height: 844 })
  await related.locator('.el-pagination').scrollIntoViewIfNeeded()
  const bounds = await drawer.locator('.el-drawer__body').evaluate(element => ({ width: element.clientWidth, scroll: element.scrollWidth }))
  expect(bounds.scroll).toBeLessThanOrEqual(bounds.width + 1)
  await page.screenshot({ path: testInfo.outputPath('asset-associations-mobile.png'), fullPage: true })
  expect(pageErrors).toEqual([])
  expect(unexpected).toEqual([])
})

test('case deep links retain drafts, page the full timeline and survive a failed status update', async ({ page }, testInfo) => {
  const unexpected = await installNetworkGuard(page)
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await mockSession(page)
  const incident = { id: 'outside-page', caseNo: 'INC-20260921-ABC123', title: 'Linked investigation', entity: 'host-one',
    severity: 'HIGH', status: 'OPEN', assignee: 'alice', alarmIds: ['alarm-one'], ruleIds: ['rule-one'], timeline: [] }
  let writes = 0
  let releaseFirst!: () => void
  const firstWrite = new Promise<void>(resolve => { releaseFirst = resolve })
  const timelinePages: string[] = []
  await page.route('**/incident-web/api/v1/**', async route => {
    const url = new URL(route.request().url())
    const method = route.request().method()
    const fulfill = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ code: status === 200 ? 0 : status, message: status === 200 ? '' : 'Case write unavailable', data }) })
    if (url.pathname.endsWith('/stats')) return fulfill({ total: 80, open: 80, resolved: 0 })
    if (url.pathname.endsWith('/incidents') && method === 'GET') {
      return fulfill({ items: [{ ...incident, id: 'listed-case', title: 'A different case on this page' }], total: 80 })
    }
    if (url.pathname.endsWith('/incidents/outside-page') && method === 'GET') return fulfill({ found: true, case: incident })
    if (url.pathname.endsWith('/incidents/outside-page/timeline')) {
      const currentPage = Number(url.searchParams.get('page'))
      const size = Number(url.searchParams.get('size'))
      timelinePages.push(`${currentPage}:${size}`)
      return fulfill({ total: 121, items: Array.from({ length: size }, (_, index) => ({ ts: '2026-09-21T01:00:00Z',
        type: 'NOTE', source: 'analyst', message: `Timeline event ${(currentPage - 1) * size + index + 1}` })) })
    }
    if (url.pathname.endsWith('/incidents/outside-page/status') && method === 'POST') {
      writes++
      if (writes === 1) { await firstWrite; return fulfill(null, 503) }
      incident.status = url.searchParams.get('status')!
      return fulfill({ case: incident })
    }
    await route.fallback()
  })
  await page.goto('/cases?page=2&status=OPEN&caseId=outside-page')
  const drawer = page.locator('.el-drawer')
  await expect(drawer).toContainText('Linked investigation')
  const timeline = drawer.getByRole('region', { name: 'Response Timeline' })
  await expect(timeline.getByText('Timeline event 1', { exact: true })).toBeVisible()
  await timeline.locator('.el-pager').getByText('2', { exact: true }).click()
  await expect(timeline.getByText('Timeline event 21', { exact: true })).toBeVisible()
  expect(timelinePages).toEqual(['1:20', '2:20'])
  await drawer.locator('.case-status-row .el-select').click()
  await page.getByRole('option', { name: 'Investigating', exact: true }).click()
  await drawer.locator('.el-drawer__close-btn').click()
  await expect(page.getByRole('dialog').filter({ hasText: 'unsaved' })).toBeVisible()
  await page.getByRole('button', { name: 'Keep editing', exact: true }).click()
  await expect(page).toHaveURL(/caseId=outside-page/)
  await drawer.getByRole('button', { name: 'Update Status', exact: true }).click()
  await expect(drawer.locator('.case-status-row input')).toBeDisabled()
  await expect(drawer.locator('.el-descriptions input')).toBeDisabled()
  await drawer.locator('.el-drawer__close-btn').click()
  await expect(drawer).toBeVisible()
  expect(writes).toBe(1)
  releaseFirst()
  await expect(drawer.getByRole('alert')).toContainText('Case write unavailable')
  await expect(drawer.locator('.case-status-row input')).toBeEnabled()
  await expect(page.locator('.el-message')).not.toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('case-failed-status.png'), fullPage: true })
  await drawer.getByRole('button', { name: 'Update Status', exact: true }).click()
  await expect(drawer.getByRole('alert')).not.toBeVisible()
  expect(writes).toBe(2)
  await drawer.locator('.el-drawer__close-btn').click()
  await expect(drawer).not.toBeVisible()
  await expect(page).toHaveURL(/page=2&status=OPEN$/)
  await page.goBack()
  await expect(drawer).toContainText('Linked investigation')
  await expect(page).toHaveURL(/caseId=outside-page/)
  await page.reload()
  await expect(drawer).toContainText('Linked investigation')
  await page.setViewportSize({ width: 390, height: 844 })
  await timeline.locator('.el-pagination').scrollIntoViewIfNeeded()
  const dimensions = await drawer.locator('.el-drawer__body').evaluate(element => ({ width: element.clientWidth, scroll: element.scrollWidth }))
  expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.width + 1)
  await page.screenshot({ path: testInfo.outputPath('case-timeline-mobile.png'), fullPage: true })
  expect(pageErrors).toEqual([])
  expect(unexpected).toEqual([])
})

test('notification test failures retain one saved channel and retry the reviewed configuration', async ({ page }, testInfo) => {
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.message))
  const unexpected = await installNetworkGuard(page)
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await mockSession(page)
  let saved: { id: string; name: string; type: string; target: string; enabled: boolean; description: string } | null = null
  let creates = 0
  let updates = 0
  let tests = 0
  let releaseFirst!: () => void
  const firstTest = new Promise<void>(resolve => { releaseFirst = resolve })
  await page.route('**/notify-web/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    const fulfill = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ code: status === 200 ? 0 : status, message: status === 200 ? '' : 'Test delivery unavailable', data }) })
    if (path.endsWith('/dispatch-log') && method === 'GET') return fulfill({ items: [], total: 0 })
    if (path.endsWith('/channels') && method === 'GET') return fulfill({ items: saved ? [saved] : [], total: saved ? 1 : 0 })
    if (path.endsWith('/channels') && method === 'POST') {
      creates++
      saved = { ...route.request().postDataJSON(), id: 'browser-channel' }
      return fulfill(saved)
    }
    if (path.endsWith('/channels/browser-channel') && method === 'PUT') {
      updates++
      saved = { ...route.request().postDataJSON(), id: 'browser-channel' }
      return fulfill(saved)
    }
    if (path.endsWith('/channels/browser-channel/test') && method === 'POST') {
      tests++
      if (tests === 1) await firstTest
      return tests < 3 ? fulfill({ status: 'failed' }, 502) : fulfill({ status: 'sent' })
    }
    await route.fallback()
  })
  await page.goto('/notify')
  await page.getByRole('button', { name: 'Create Channel', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByPlaceholder('e.g. Security Ops Group').fill('Browser notification fixture')
  await dialog.getByPlaceholder('https://example.com/webhook').fill('https://example.com/first')
  await dialog.getByRole('button', { name: 'Save and test', exact: true }).click()
  await expect(dialog.getByRole('status')).toContainText('Channel configuration saved')
  await expect(dialog.getByPlaceholder('e.g. Security Ops Group')).toBeDisabled()
  await dialog.getByRole('button', { name: 'Close this dialog' }).click()
  await expect(dialog).toBeVisible()
  expect(creates).toBe(1)
  expect(updates).toBe(0)
  releaseFirst()
  await expect(dialog.getByRole('button', { name: 'Retry test', exact: true })).toBeEnabled()
  await expect(dialog.getByRole('status')).toContainText('Channel configuration is saved, but the test did not confirm delivery')
  await expect(dialog.getByPlaceholder('e.g. Security Ops Group')).toHaveValue('Browser notification fixture')
  await expect(page.locator('.el-message')).not.toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('notification-test-failure.png'), fullPage: true })
  await dialog.getByRole('button', { name: 'Retry test', exact: true }).click()
  await expect.poll(() => tests).toBe(2)
  await expect(dialog.getByRole('button', { name: 'Retry test', exact: true })).toBeEnabled()
  expect(creates).toBe(1)
  expect(updates).toBe(0)
  await dialog.getByPlaceholder('https://example.com/webhook').fill('https://example.com/corrected')
  await dialog.getByRole('button', { name: 'Save and test', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  expect(creates).toBe(1)
  expect(updates).toBe(1)
  expect(tests).toBe(3)
  expect(saved!.target).toBe('https://example.com/corrected')
  expect(saved!.enabled).toBe(false)
  expect(pageErrors).toEqual([])
  expect(unexpected).toEqual([])
})

test('rule editor holds its form and route until a save settles', async ({ page }, testInfo) => {
  const unexpected = await installNetworkGuard(page)
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await mockSession(page)
  let rule = { id: 'pending-rule', name: 'Before save', type: 'pattern', severity: 'HIGH', status: 'DRAFT', enabled: false, revisionToken: 'a'.repeat(64),
    match: [{ field: 'msg', op: 'eq', value: 'alert' }] }
  let finish!: () => void
  const pending = new Promise<void>(resolve => { finish = resolve })
  let attempts = 0
  await page.route(isMockedBackendUrl, async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/detect-web/api/v1/rules/pending-rule' && route.request().method() === 'PUT') {
      attempts++
      expect(route.request().headers()['if-match']).toBe(`"${rule.revisionToken}"`)
      rule = { ...rule, ...route.request().postDataJSON(), revisionToken: 'b'.repeat(64) }
      await pending
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rule) })
      return
    }
    const responses: Record<string, unknown> = {
      '/detect-web/api/v1/rules/pending-rule': rule,
      '/detect-web/api/v1/rules': { items: [rule], total: 1, page: 1, size: 20, totalPages: 1 },
      '/detect-web/api/v1/stats': { rules: 1, queueLoad: 0 },
      '/detect-web/api/v1/watchlists': [],
      '/search-config/api/v1/meta/fields': [],
      '/attack-web/api/v1/techniques': { items: [], total: 0 },
    }
    if (route.request().method() === 'GET' && Object.hasOwn(responses, path)) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(responses[path]) })
    } else await route.fallback()
  })
  await page.goto('/detect/rules/pending-rule/edit')
  const name = page.locator('.detect-editor-form input').first()
  await expect(name).toHaveValue('Before save')
  await name.fill('Reviewed rule')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect.poll(() => attempts).toBe(1)
  await expect(name).toBeDisabled()
  await expect(page.locator('.field-condition-row .el-input__inner').first()).toBeDisabled()
  await expect(page.locator('.advanced-json textarea')).toHaveAttribute('readonly')
  await expect(page.getByRole('status')).toContainText('Saving in progress')
  await page.locator('[data-menu-key="detect"]').click()
  await expect(page).toHaveURL(/\/detect\/rules\/pending-rule\/edit$/)
  await expect(page.getByText('Saving in progress, please wait…').last()).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('rule-save-pending.png'), fullPage: true })
  finish()
  await expect(name).toBeEnabled()
  await expect(name).toHaveValue('Reviewed rule')
  await page.locator('[data-menu-key="detect"]').click()
  await expect(page).toHaveURL(/\/detect$/)
  await expect(page.getByRole('row').filter({ hasText: 'Reviewed rule' })).toBeVisible()
  expect(attempts).toBe(1)
  expect(unexpected).toEqual([])
})

test('rule editor retains a conflicting draft and reloads only after review', async ({ page }, testInfo) => {
  const unexpected = await installNetworkGuard(page)
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await mockSession(page)
  let rule = { id: 'conflict-rule', name: 'Initial rule', type: 'pattern', severity: 'HIGH', status: 'DRAFT', enabled: false, revisionToken: 'a'.repeat(64), match: [{ field: 'msg', op: 'eq', value: 'alert' }] }
  let reads = 0
  let attempts = 0
  const headers: string[] = []
  await page.route(isMockedBackendUrl, async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/detect-web/api/v1/rules/conflict-rule' && route.request().method() === 'PUT') {
      attempts++
      headers.push(route.request().headers()['if-match'])
      if (attempts === 1) rule = { ...rule, name: 'Other analyst', revisionToken: 'b'.repeat(64) }
      if (headers.at(-1) !== `"${rule.revisionToken}"`) {
        await route.fulfill({ status: 412, contentType: 'application/json', body: JSON.stringify({ code: 412, message: 'changed', data: null }) })
      } else {
        rule = { ...rule, ...route.request().postDataJSON(), revisionToken: 'c'.repeat(64) }
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rule) })
      }
      return
    }
    const responses: Record<string, unknown> = {
      '/detect-web/api/v1/rules/conflict-rule': rule,
      '/detect-web/api/v1/stats': { rules: 1, queueLoad: 0 }, '/detect-web/api/v1/watchlists': [],
      '/search-config/api/v1/meta/fields': [], '/attack-web/api/v1/techniques': { items: [], total: 0 },
    }
    if (route.request().method() === 'GET' && Object.hasOwn(responses, path)) {
      if (path.endsWith('/rules/conflict-rule')) reads++
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(responses[path]) })
    } else await route.fallback()
  })
  await page.goto('/detect/rules/conflict-rule/edit')
  const name = page.locator('.detect-editor-form input').first()
  await expect(name).toHaveValue('Initial rule')
  await page.locator('.advanced-json summary').click()
  await page.locator('.advanced-json textarea').fill(JSON.stringify({ ...rule, name: 'My draft', revisionToken: 'b'.repeat(64) }))
  await page.locator('.advanced-json button').click()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.locator('.detect-save-feedback')).toContainText('Your draft is retained')
  await expect(name).toHaveValue('My draft')
  expect(reads).toBe(1)
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Download my draft' }).click()])
  expect(JSON.parse(await readFile((await download.path())!, 'utf8')).name).toBe('My draft')
  await page.locator('.detect-save-feedback').scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('rule-conflict.png'), fullPage: true })
  await page.getByRole('button', { name: 'Load current rule' }).click()
  await page.getByRole('dialog', { name: 'Unsaved changes' }).getByRole('button', { name: 'Discard', exact: true }).click()
  await expect(name).toHaveValue('Other analyst')
  await name.fill('Reviewed merge')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.locator('.detect-save-feedback')).toHaveCount(0)
  expect(headers).toEqual([`"${'a'.repeat(64)}"`, `"${'b'.repeat(64)}"`])
  expect(rule.name).toBe('Reviewed merge')
  expect(unexpected).toEqual([])
})

test('rule revision history compares and restores the reviewed version', async ({ page }, testInfo) => {
  const unexpected = await installNetworkGuard(page)
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await mockSession(page, 'admin')
  let rule = { id: 'history-rule', name: 'Current rule', type: 'pattern', severity: 'HIGH', status: 'DRAFT', enabled: false, revisionToken: 'a'.repeat(64), match: [{ field: 'msg', op: 'eq', value: 'alert' }] }
  const previous = { ...rule, name: 'Historical active rule', status: 'ACTIVE', enabled: true }
  let restored = false
  await page.route(isMockedBackendUrl, async route => {
    const path = new URL(route.request().url()).pathname
    const base = '/detect-web/api/v1/rules/history-rule'
    if (path === `${base}/revisions/1/restore` && route.request().method() === 'POST') {
      expect(route.request().headers()['if-match']).toBe(`"${rule.revisionToken}"`)
      rule = { ...previous, revisionToken: 'b'.repeat(64) }
      restored = true
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rule) })
      return
    }
    const entry = (revision: number) => ({ ruleId: rule.id, revision, status: revision === 1 ? 'ACTIVE' : 'DRAFT', source: revision === 3 ? 'RESTORE' : 'EDIT', changedBy: 'analyst', changedAt: '2026-09-21T10:00:00Z' })
    const responses: Record<string, unknown> = {
      [base]: rule,
      [`${base}/revisions`]: { items: (restored ? [3, 2, 1] : [2, 1]).map(entry), total: restored ? 3 : 2, totalPages: 1, page: 1, size: 10 },
      [`${base}/revisions/1`]: { ...entry(1), spec: previous },
      '/detect-web/api/v1/stats': { rules: 1, queueLoad: 0 }, '/detect-web/api/v1/watchlists': [],
      '/search-config/api/v1/meta/fields': [], '/attack-web/api/v1/techniques': { items: [], total: 0 },
    }
    if (route.request().method() === 'GET' && Object.hasOwn(responses, path)) {
      if (path === `${base}/revisions`) expect(new URL(route.request().url()).searchParams.get('size')).toBe('10')
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(responses[path]) })
    } else await route.fallback()
  })
  await page.goto('/detect/rules/history-rule/edit')
  const name = page.locator('.detect-editor-form input').first()
  await expect(name).toHaveValue('Current rule')
  await name.fill('My unsaved draft')
  await page.getByRole('button', { name: 'Revision history', exact: true }).click()
  const history = page.getByRole('dialog', { name: 'Revision history' })
  await history.getByRole('row').filter({ hasText: '#1' }).getByRole('button', { name: 'Compare', exact: true }).click()
  await expect(history.locator('.history-comparison')).toContainText('Historical active rule')
  await expect(history.locator('.history-comparison')).toContainText('Current rule')
  await expect(history.locator('.history-comparison')).not.toContainText('revisionToken')
  await page.screenshot({ path: testInfo.outputPath('rule-history-compare.png'), fullPage: true })
  await history.getByRole('button', { name: 'Restore selected revision' }).click()
  const confirmation = page.getByRole('dialog', { name: 'Confirm', exact: true })
  await expect(confirmation).toContainText('resume live detection')
  await expect(confirmation).toContainText('unsaved editor draft')
  await confirmation.getByRole('button', { name: 'Confirm', exact: true }).click()
  await expect(history.getByRole('status')).toContainText('restored as a new revision')
  await expect(history.getByRole('row').filter({ hasText: '#3' })).toBeVisible()
  await expect(name).toHaveValue('Historical active rule')
  expect(restored).toBe(true)
  expect(unexpected).toEqual([])
})

test('login creates a session-backed workbench without exposing a bearer token', async ({ page }) => {
  const unexpected = await installNetworkGuard(page)
  await mockWorkbenchReads(page)
  await page.route(isAuthSessionUrl, route => route.fulfill({ status: 401, body: '{}' }))
  await page.route(isAuthLoginUrl, async route => {
    const body = route.request().postDataJSON()
    expect(body).toEqual({ username: 'analyst', password: 'secret' })
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Set-Cookie': 'SOCP_SESSION=test; Path=/; HttpOnly; SameSite=Lax' },
      body: JSON.stringify({ username: 'analyst', role: 'analyst', tenant: 'default', expiresIn: 1800 }),
    })
  })
  await page.goto('/overview')
  await page.locator('input[autocomplete="username"]').fill('analyst')
  await page.locator('input[autocomplete="current-password"]').fill('secret')
  await page.locator('form').getByRole('button').click()

  await expect(page.locator('.socp-shell')).toBeVisible()
  await expect(page.evaluate(() => localStorage.getItem('socp_token'))).resolves.toBeNull()
  expect(unexpected).toEqual([])
})

test('viewer cannot navigate to write-oriented configuration pages', async ({ page }) => {
  const unexpected = await installNetworkGuard(page)
  await mockSession(page, 'viewer')
  await page.goto('/overview')

  await expect(page.locator('[data-menu-key="alarms"]')).toBeVisible()
  await expect(page.locator('[data-menu-key="detect"]')).toHaveCount(0)
  await expect(page.locator('[data-menu-key="soar"]')).toHaveCount(0)
  await expect(page.locator('[data-menu-key="notify"]')).toHaveCount(0)
  expect(unexpected).toEqual([])
})

test('router preserves deep links and browser back navigation', async ({ page }) => {
  const unexpected = await installNetworkGuard(page)
  await mockSession(page)
  await page.goto('/overview')
  await page.locator('[data-menu-key="alarms"]').click()
  await expect(page).toHaveURL(/\/alarms$/)
  await expect(page.locator('[data-menu-key="alarms"]')).toHaveAttribute('aria-current', 'page')

  await page.goBack()
  await expect(page).toHaveURL(/\/overview$/)
  await expect(page.locator('[data-menu-key="overview"]')).toHaveAttribute('aria-current', 'page')
  expect(unexpected).toEqual([])
})

test('gateway auth endpoint is reachable in a real browser smoke run', async ({ page }) => {
  test.skip(!process.env.SOCP_E2E_BACKEND_URL, 'set SOCP_E2E_BACKEND_URL to run against a live gateway')
  const baseUrl = process.env.SOCP_E2E_BACKEND_URL!.replace(/\/$/, '')
  const response = await page.goto(`${baseUrl}/auth/session`, { waitUntil: 'domcontentloaded' })
  // An unauthenticated gateway is expected to return 401; a configured test
  // session may return 200. Both prove that the browser reached the gateway.
  expect([200, 401]).toContain(response?.status())
})

test('case deep link loads detail and timeline outside the current page', async ({ page }, testInfo) => {
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await mockSession(page)
  const item: CaseInfo = { id: 'off-page', caseNo: 'CASE-OFF-PAGE', title: 'Off-page investigation', status: 'OPEN',
    entity: 'host-a', severity: 'HIGH', assignee: '', ruleIds: [], alarmIds: [], timeline: [], createdAt: '2026-09-20T00:00:00Z', updatedAt: '2026-09-20T00:00:00Z' }
  await page.route('**/incident-web/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    const body = path.endsWith('/timeline')
      ? { items: [{ ts: '2026-09-20T00:00:00Z', message: 'Durable case evidence', type: 'NOTE', source: 'analyst' }], total: 1 }
      : path.endsWith('/off-page') ? { found: true, case: item }
        : path.endsWith('/stats') ? { total: 1, open: 1, resolved: 0 } : { items: [], total: 0 }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/cases?caseId=off-page')
  await expect(page.locator('.el-drawer')).toContainText('Off-page investigation')
  await expect(page.locator('.el-drawer')).toContainText('Durable case evidence')
  await page.screenshot({ path: testInfo.outputPath('case-deep-link.png'), fullPage: true })
})

test('metadata edits stay in a dialog and retain inputs across a failed save', async ({ page }, testInfo) => {
  const unexpected = await installNetworkGuard(page)
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await mockSession(page)
  let type = { id: 'source-type-1', code: 'SYSLOG', name: 'Original source', description: '', enabled: true }
  let attempts = 0
  await page.route('**/search-config/api/v1/meta/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'PUT') {
      expect(path).toBe('/search-config/api/v1/meta/data-source-types/source-type-1')
      attempts++
      if (attempts === 1) {
        await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: 'Database unavailable' }) })
      } else {
        type = { ...type, ...route.request().postDataJSON() }
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(type) })
      }
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(path.endsWith('/data-source-types') ? [type] : []) })
  })
  await page.goto('/metadata')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('row').filter({ hasText: 'Original source' }).getByRole('button', { name: 'Edit', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Edit', exact: true })
  await expect(dialog.getByLabel('Code', { exact: true })).toBeDisabled()
  await dialog.getByLabel('Name', { exact: true }).fill('Updated source')
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('Database unavailable')
  await expect(dialog.getByLabel('Name', { exact: true })).toHaveValue('Updated source')
  await page.screenshot({ path: testInfo.outputPath('metadata-save-error.png'), fullPage: true })
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: 'Updated source' })).toBeVisible()
  await page.getByRole('button', { name: /Add Data Source Type/ }).click()
  await expect(page.getByRole('dialog').getByLabel('Name', { exact: true })).toHaveValue('')
  expect(type.id).toBe('source-type-1')
  expect(attempts).toBe(2)
  expect(unexpected).toEqual([])
})
