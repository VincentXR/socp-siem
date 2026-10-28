import { expect, test, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'

// No route interception: these checks exercise the deployed gateway and services.
// Run only against disposable fixtures with the same credentials as the backend.
const live = process.env.SOCP_LIVE_E2E === 'true'
const pages = [
  ['/overview', '/alert-web/api/alarms/stats'],
  ['/situation', '/alert-web/api/alarms/stats'],
  ['/alarms', '/alert-web/api/alarms'],
  ['/cases', '/incident-web/api/v1/incidents'],
  ['/search', '/search-config/api/v1/meta/fields'],
  ['/notify', '/notify-web/api/v1/channels'],
  ['/detect', '/detect-web/api/v1/rules'],
  ['/ueba', '/detect-web/api/v1/ueba/entities'],
  ['/soar', '/soar-web/api/playbooks'],
  ['/attack', '/attack-web/api/v1/techniques'],
  ['/assets', '/asset-web/api/v1/assets'],
  ['/endpoints', '/hips-web/api/v1/endpoints'],
  ['/threat-intel', '/threat-web/api/v1/iocs'],
  ['/reference-sets', '/search-config/api/v1/reference-sets'],
  ['/ingest?tab=sources', '/search-config/api/v1/sources'],
  ['/metadata', '/search-config/api/v1/meta/fields'],
  ['/compliance', '/soc-base/api/v1/compliance/frameworks'],
  ['/reports', '/report-web/api/v1/reports/daily'],
] as const

async function login(page: Page, user: string): Promise<void> {
  const users = JSON.parse(process.env.SOCP_AUTH_USERS ?? '{}') as Record<string, string>
  expect(Boolean(users[user]), `Missing disposable credentials for ${user}`).toBe(true)
  await page.addInitScript(() => localStorage.setItem('socp-locale', 'en-US'))
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill(users[user])
  await page.locator('form').getByRole('button').click()
  await expect(page.locator('.socp-shell')).toBeVisible()
}

test.describe('live workbench service integration', () => {
  test.skip(!live, 'requires isolated real services and explicit fixture credentials')
  for (const user of ['admin', 'demo']) {
    test(`${user} loads business pages through real service responses`, async ({ page: loginPage, context }, testInfo) => {
      test.setTimeout(300_000)
      const errors: string[] = []
      const apiFailures: string[] = []
      const observe = (page: Page) => {
        page.on('pageerror', error => errors.push(error.message))
        page.on('response', response => {
          const path = new URL(response.url()).pathname
          if (path.includes('/api/') && response.status() >= 400) {
            apiFailures.push(`${response.status()} ${path}`)
          }
        })
      }
      observe(loginPage)
      await login(loginPage, user)
      await loginPage.close()
      for (const [path, endpoint] of pages) {
        await test.step(path, async () => {
          // Each document owns its response listener. Shared endpoints on the
          // previous route must not satisfy this route's API assertion.
          const page = await context.newPage()
          observe(page)
          const responsePromise = page.waitForResponse(response =>
            new URL(response.url()).pathname === endpoint && response.request().method() === 'GET',
          { timeout: 30_000 })
          await page.goto(path)
          const response = await responsePromise
          expect(response.status(), `${path}: ${endpoint}`).toBe(200)
          const body = await response.json()
          expect(body.code, `${path}: API envelope`).toBe(0)
          expect(body.data, `${path}: API data`).not.toBeNull()
          await expect(page.locator('.socp-shell')).toBeVisible()
          expect(new URL(page.url()).pathname).toBe(path.split('?')[0])
          expect(errors, `${path}: uncaught browser exceptions`).toEqual([])
          expect(apiFailures, `${path}: failed service responses`).toEqual([])
          await page.close()
        })
      }
      await test.step('assistant responds and renders the real service result', async () => {
        const page = await context.newPage()
        observe(page)
        await page.goto('/assistant')
        await page.locator('.ai-question-panel input').fill('Summarize recent security alerts')
        const responsePromise = page.waitForResponse(response =>
          new URL(response.url()).pathname === '/ai-assistant/api/v1/ai/ask'
          && response.request().method() === 'POST')
        await page.getByRole('button', { name: 'Ask AI', exact: true }).click()
        const response = await responsePromise
        expect(response.status()).toBe(200)
        const body = await response.json()
        expect(body.code).toBe(0)
        expect(body.data.answer).toEqual(expect.any(String))
        expect(body.data.answer.trim().length).toBeGreaterThan(0)
        await expect(page.locator('.ai-result-answer')).toHaveText(body.data.answer)
        expect(errors).toEqual([])
        expect(apiFailures).toEqual([])
        await page.close()
      })
      await testInfo.attach('real-service-pages', {
        body: JSON.stringify({ user, pages, browserErrors: errors, apiFailures }), contentType: 'application/json',
      })
    })
  }

  test('viewer cannot bypass write permissions through the gateway', async ({ page }) => {
    await login(page, 'viewer')
    // Empty bodies are intentional: authorization must reject before validation.
    for (const path of ['/asset-web/api/v1/assets', '/threat-web/api/v1/iocs',
      '/search-config/api/v1/sources', '/detect-web/api/v1/rules']) {
      const response = await page.request.post(path, {
        data: {}, headers: { Origin: new URL(page.url()).origin },
      })
      expect(response.status(), path).toBe(403)
    }
    const response = await page.request.get('/alert-web/api/alarms?page=1&size=1')
    expect(response.status()).toBe(200)
    expect((await response.json()).code).toBe(0)
    await expect(page.locator('.socp-shell')).toBeVisible()
  })

  test('analyst saves and downloads an intact report from real object storage', async ({ page }, testInfo) => {
    test.setTimeout(90_000)
    await login(page, 'demo')
    const dailyPromise = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/report-web/api/v1/reports/daily')
    await page.goto('/reports')
    const daily = (await (await dailyPromise).json()).data
    expect(daily.source).toBe('clickhouse')
    expect(daily.degraded).toBe(false)
    const archivePromise = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/report-web/api/v1/reports/archive'
      && response.request().method() === 'POST')
    await page.getByRole('button', { name: 'Save daily snapshot', exact: true }).click()
    const archiveResponse = await archivePromise
    expect(archiveResponse.status()).toBe(200)
    const archive = (await archiveResponse.json()).data
    expect(archive.archived).toBe(true)
    expect(archive.archiveKey).toMatch(/^reports\/default\/\d{8}\/snapshot-.+\.json$/)
    const row = page.getByRole('row').filter({ hasText: archive.archiveKey })
    await expect(row).toBeVisible()
    const objectPromise = page.context().waitForEvent('response', {
      predicate: response => new URL(response.url()).pathname.endsWith('/' + archive.archiveKey)
        && response.request().method() === 'GET',
    })
    const popupPromise = page.waitForEvent('popup')
    const downloadPromise = popupPromise.then(popup => popup.waitForEvent('download'))
    await row.getByRole('button', { name: 'Download JSON', exact: true }).click()
    const popup = await popupPromise
    const objectResponse = await objectPromise
    expect(objectResponse.status()).toBe(200)
    // The signed object response is an attachment, so Chromium exposes its
    // bytes through Download rather than Network.getResponseBody.
    const download = await downloadPromise
    expect(await download.failure()).toBeNull()
    expect(download.suggestedFilename()).toBe(archive.archiveKey.split('/').at(-1))
    const downloadedPath = await download.path()
    expect(downloadedPath).not.toBeNull()
    const bytes = await readFile(downloadedPath!)
    const snapshot = JSON.parse(bytes.toString('utf8'))
    expect(snapshot.schemaVersion).toBe(1)
    expect(snapshot.daily.total).toBe(daily.total)
    expect(snapshot.daily.source).toBe('clickhouse')
    expect(snapshot.daily.degraded).toBe(false)
    expect(snapshot.trend7d.days).toHaveLength(7)
    const repeated = await page.request.get(objectResponse.url())
    expect(repeated.status()).toBe(200)
    expect(await repeated.body()).toEqual(bytes)
    await testInfo.attach('archive-integrity', {
      body: JSON.stringify({ key: archive.archiveKey, bytes: bytes.length,
        sha256: createHash('sha256').update(bytes).digest('hex') }),
      contentType: 'application/json',
    })
    await popup.close()
  })
})
