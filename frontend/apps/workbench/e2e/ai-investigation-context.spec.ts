import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

const investigation = (alertId: string) => ({ investigationId: `job-${alertId}`, alertId,
  status: 'COMPLETED', analysis: `analysis ${alertId}`, recommendedSpl: '', timeline: [], hypotheses: [],
  citations: [], nextActions: [], degradedSources: [] })

test('investigation follows the selected alert while old polls and summary writes finish', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = [], submissions: string[] = [], appends: string[] = []
  let releaseA!: () => void, releaseAppend!: () => void, pollingA = false
  const pollGate = new Promise<void>(resolve => { releaseA = resolve })
  const appendGate = new Promise<void>(resolve => { releaseAppend = resolve })
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/ai-assistant/api/v1/ai/investigations/async') {
      const id = route.request().postDataJSON().alertId as string
      submissions.push(id); data = { jobId: `job-${id}` }
    } else if (/\/investigations\/job-[abc]$/.test(path)) {
      const id = path.at(-1)!
      if (id === 'a') { pollingA = true; await pollGate }
      data = investigation(id)
    } else if (/\/investigations\/job-[bc]\/append-to-incident$/.test(path)) {
      const id = path.includes('job-b') ? 'b' : 'c'
      appends.push(id)
      if (id === 'b') {
        await appendGate
        await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: 'Old append unavailable' }) })
        return
      }
      data = { ...investigation(id), summaryAppended: true, incidentId: 'incident-c' }
    } else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  try {
    await page.goto('/assistant?alertId=a')
    const panel = page.locator('.ai-investigation-panel'), input = panel.getByRole('textbox')
    await expect.poll(() => pollingA).toBe(true)
    await input.fill('b')
    await panel.getByRole('button', { name: 'Investigate', exact: true }).click()
    await expect(page).toHaveURL(/\/assistant\?alarmId=b$/)
    await expect(panel.locator('.ai-analysis')).toHaveText('analysis b')
    releaseA()
    await panel.getByRole('button', { name: 'Append summary to Incident', exact: true }).click()
    await expect.poll(() => appends).toEqual(['b'])
    await input.fill('c')
    await expect(panel.locator('.ai-analysis')).toHaveCount(0)
    await panel.getByRole('button', { name: 'Investigate', exact: true }).click()
    await expect(panel.locator('.ai-analysis')).toHaveText('analysis c')
    const append = panel.getByRole('button', { name: 'Append summary to Incident', exact: true })
    await expect(append).toBeDisabled()
    await expect(panel.getByRole('status')).toContainText('previous summary update')
    releaseAppend()
    await expect(append).toBeEnabled()
    await expect(panel.getByRole('alert')).toHaveCount(0)
    await append.click()
    await expect(panel.getByRole('button', { name: 'Appended to Incident', exact: true })).toBeDisabled()
    await expect(panel.locator('.ai-analysis')).toHaveText('analysis c')
    expect(submissions).toEqual(['a', 'b', 'c']); expect(appends).toEqual(['b', 'c'])
    await page.setViewportSize({ width: 390, height: 844 })
    await page.locator('.socp-content').evaluate(element => { element.scrollTop = 0 })
    for (const tag of await page.locator('.ai-quick-tag').all()) {
      await expect.poll(() => tag.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      const box = await tag.boundingBox()
      expect(box!.x + box!.width).toBeLessThanOrEqual(390)
    }
    await page.screenshot({ path: testInfo.outputPath('ai-prompts-mobile.png'), fullPage: true })
    await panel.scrollIntoViewIfNeeded()
    await expect.poll(() => panel.locator('.ai-investigation-head > div').evaluate(element => element.clientWidth)).toBeGreaterThan(200)
    await expect.poll(() => panel.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('ai-investigation-mobile.png'), fullPage: true })
    expect(unexpected).toEqual([]); expect(errors).toEqual([])
  } finally { releaseA(); releaseAppend() }
})
