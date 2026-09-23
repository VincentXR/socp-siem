import { expect, test } from '@playwright/test'
import { isWorkbenchBackendUrl } from './helpers'

test('UEBA keeps selected details and current score inputs through delayed and failed reads', async ({ page }, testInfo) => {
  const unexpected: string[] = [], errors: string[] = []
  let releaseEntity!: () => void, releaseScore!: () => void, entityCalls = 0, scoreCalls = 0
  const entityGate = new Promise<void>(resolve => { releaseEntity = resolve })
  const scoreGate = new Promise<void>(resolve => { releaseScore = resolve })
  const entities = ['host-a', 'host-b'].map(entity => ({ entity, risk: 30, level: 'MEDIUM', alerts: 1,
    critical: false, maxSeverity: 'MEDIUM', firstSeen: null, lastSeen: null, mitre: [], topRules: [] }))
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!isWorkbenchBackendUrl(url)) { await route.continue(); return }
    let data: unknown
    if (path === '/auth/session') data = { username: 'analyst', role: 'analyst', tenant: 'default', locale: 'en-US' }
    else if (path === '/auth/operators') data = { items: [] }
    else if (path === '/detect-web/api/v1/ueba/entities') data = entities
    else if (path === '/detect-web/api/v1/ueba/summary') data = { entities: 2, byLevel: { MEDIUM: 2 }, maxRisk: 30, halfLifeHours: 24 }
    else if (path === '/detect-web/api/v1/watchlists') data = []
    else if (path === '/attack-web/api/v1/techniques') data = { items: [{ id: 'T1110', name: 'Brute Force' }], total: 1 }
    else if (path === '/detect-web/api/v1/ueba/entities/host-a') { entityCalls++; await entityGate; data = { ...entities[0], risk: 99 } }
    else if (path === '/detect-web/api/v1/ueba/entities/host-b') data = { ...entities[1], risk: 42 }
    else if (path === '/detect-web/api/v1/ueba/score') {
      const call = ++scoreCalls
      if (call === 2) await scoreGate
      if (call === 3) {
        await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: 'Scoring unavailable' }) })
        return
      }
      const score = call === 1 ? 10 : call === 2 ? 99 : 42
      data = { score, level: 'HIGH', breakdown: { severity: score } }
    } else { unexpected.push(`${route.request().method()} ${path}`); await route.abort(); return }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(path.startsWith('/auth/') ? data : { code: 0, data }) })
  })
  try {
    await page.goto('/ueba')
    await page.getByRole('button', { name: 'host-a', exact: true }).click()
    const drawer = page.getByRole('dialog')
    await expect.poll(() => entityCalls).toBe(1)
    await expect(drawer.getByRole('status')).toBeVisible()
    await drawer.getByRole('button', { name: 'Close this dialog' }).click()
    await expect(drawer).not.toBeVisible()
    await page.getByRole('button', { name: 'host-b', exact: true }).click()
    await expect(drawer.locator('.risk-pill')).toHaveText('42')
    releaseEntity()
    await expect(drawer).toHaveAccessibleName('host-b')
    await page.setViewportSize({ width: 390, height: 844 })
    await expect.poll(async () => {
      const box = await drawer.boundingBox()
      return Boolean(box && box.x >= 0 && box.x + box.width <= 391)
    }).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('ueba-detail-mobile.png'), fullPage: true })
    await drawer.getByRole('button', { name: 'Close this dialog' }).click()
    await page.getByRole('tab', { name: 'Advanced Tools', exact: true }).click()
    const panel = page.getByRole('tabpanel', { name: 'Advanced Tools', exact: true })
    await expect(panel.locator('.bd-val')).toHaveText('+10')
    await expect(panel.locator('.bd-label')).toHaveText('Severity Baseline')
    const slider = panel.getByRole('slider').first()
    await slider.focus(); await slider.press('ArrowRight')
    await expect.poll(() => scoreCalls).toBe(2)
    await expect(panel.getByRole('status')).toBeVisible()
    await expect(panel.locator('.bd-val')).toHaveCount(0)
    await slider.press('ArrowRight')
    await expect(panel.getByRole('alert')).toContainText('Scoring unavailable')
    releaseScore()
    await expect(panel.locator('.bd-val')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('ueba-score-error-mobile.png'), fullPage: true })
    await panel.getByRole('button', { name: 'Retry', exact: true }).click()
    await expect(panel.locator('.bd-val')).toHaveText('+42')
    await expect(panel.getByRole('alert')).toHaveCount(0)
    expect(unexpected).toEqual([]); expect(errors).toEqual([])
  } finally { releaseEntity(); releaseScore() }
})
