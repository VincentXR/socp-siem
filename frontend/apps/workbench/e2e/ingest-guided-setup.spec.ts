import { expect, test } from '@playwright/test'
import { expectMobileNavigationClosed, isWorkbenchBackendUrl } from './helpers'

test('guided setup keeps preview and authoritative first-event verification separate', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = []
  let degraded = true, previewWrites = 0, searches = 0
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  const source = { id: 'source-a', name: 'Real application source', type: 'FILE', format: 'JSON', path: '/var/log/app.log', enabled: true, parseRuleIds: [] }
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    if (path === '/auth/session') { await route.fulfill({ json: { username: 'analyst', role: 'analyst', tenant: 'default' } }); return }
    if (path === '/auth/operators') { await route.fulfill({ json: { items: [] } }); return }
    if (path === '/api/v1/system/health') {
      await route.fulfill({ json: { code: 0, message: 'OK', data: { status: 'up', services: {}, checkedAt: '2026-10-02T00:00:00Z' } } }); return
    }
    let data: unknown
    if (path === '/search-config/api/v1/sources/source-a/setup') data = { source, collectorTag: 'search-real-application-source', nativeVector: true, appliedState: 'UNKNOWN', configurationVersion: 'saved-fingerprint', output: { id: 'platform-search-ingest', name: 'Platform ingest', type: 'SEARCH', uri: 'https://ingest.example/ingest', enabled: true }, pipeline: [], problems: [] }
    else if (path === '/search-config/api/v1/sources/source-a/preview') {
      previewWrites++
      expect(route.request().postDataJSON()).toEqual({ sample: '{"message":"real sample"}' })
      data = { sourceId: source.id, sample: 'real sample', ok: true, matched: true, parserVersion: 'v1', fields: { message: 'real sample', source_id: source.id }, writesEvent: false, mayTriggerDownstreamActions: false }
    }
    else if (path === '/search-config/api/v1/search') {
      searches++
      expect(url.searchParams.get('q')).toContain('source_id="source-a"')
      data = { source: degraded ? 'local-cache' : 'opensearch', degraded, events: [{ eventId: 'indexed-event-a', timestamp: new Date().toISOString(), msg: 'actual event', fields: { source_id: source.id } }] }
    }
    else if (path === '/search-config/api/v1/sources') data = { items: [source], total: 1, page: 1, size: 20, totalPages: 1 }
    else if (path === '/search-config/api/v1/parse-rules' || path === '/search-config/api/v1/ingest/parse-failures') data = { items: [], total: 0, page: 1, size: 50, totalPages: 0 }
    else if (path === '/search-config/api/v1/outputs' || path === '/search-config/api/v1/meta/categories' || path === '/search-config/api/v1/ingest/tasks') data = []
    else if (path === '/search-config/api/v1/ingest/tasks/summary') data = { sources: 1, enabledSources: 1, accepted: 0, forwarded: 0, skipped: 0, bytes: 0, eps1m: 0, byHealth: {} }
    else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ json: { code: 0, data } })
  })
  await page.goto('/ingest?tab=sources&sourceId=source-a')
  const drawer = page.getByRole('dialog', { name: 'Guided setup' })
  await expect(drawer).toContainText('Collector applied state: unknown')
  await expect(drawer.getByRole('button', { name: 'Preview effective pipeline' })).toBeDisabled()
  await drawer.getByLabel('Paste a real raw log sample').fill('{"message":"real sample"}')
  await drawer.getByRole('button', { name: 'Preview effective pipeline' }).click()
  await expect(drawer).toContainText('This does not prove admission or collection')
  expect(previewWrites).toBe(1)
  expect(searches).toBe(0)
  await drawer.getByRole('button', { name: 'Check indexed events' }).click()
  await expect(drawer).toContainText('verification remains inconclusive')
  degraded = false
  await drawer.getByRole('button', { name: 'Check indexed events' }).click()
  await expect(drawer).toContainText('A real indexed event was observed for this source')
  await expect(drawer).toContainText('indexed-event-a')
  await page.setViewportSize({ width: 390, height: 844 })
  await expectMobileNavigationClosed(page)
  await expect.poll(() => drawer.evaluate(element => {
    const bounds = element.getBoundingClientRect()
    return bounds.width > 0 && bounds.left >= 0 && bounds.right <= window.innerWidth
  })).toBe(true)
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('guided-ingest-mobile.png'), fullPage: true, animations: 'disabled' })
  expect(unexpected).toEqual([])
  expect(errors).toEqual([])
})
