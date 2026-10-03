import { expect, type Page } from '@playwright/test'
import type { GasStats } from '../src/api/models'

/**
 * Shared e2e helpers. The workbench origin must match playwright.config.ts,
 * which honors E2E_PORT so local Windows boxes can dodge reserved port
 * ranges (e.g. 4157-4256 swallowing the default 4173 with EACCES).
 */
export function workbenchOrigin(): string {
  const port = Number(process.env.E2E_PORT ?? 4173)
  return `http://127.0.0.1:${port}`
}

const BACKEND_PATH = /^\/(?:api|auth|alert-web|search-config|detect-web|soar-web|report-web|asset-web|soc-base|hips-web|ai-assistant|detect-model|asset-collect|hips-collect|threat-web|attack-web|notify-web|incident-web|actuator)(?:\/|$)/

export function isWorkbenchBackendUrl(url: URL): boolean {
  return url.origin === workbenchOrigin() && BACKEND_PATH.test(url.pathname)
}

/** Explicit empty-state reads for the alarm investigation readiness panel. */
export async function mockInvestigationReadiness(page: Page): Promise<void> {
  const stats: GasStats = { rules: 0, eventCount: 0, alertCount: 0, dropCount: 0, suppressedCount: 0, queueLoad: 0 }
  const responses: Record<string, unknown> = {
    '/detect-web/api/v1/stats': stats,
    '/detect-web/api/v1/rules?page=1&size=1': { items: [], total: 0 },
    '/detect-web/api/v1/rules?page=1&size=1&status=ACTIVE': { items: [], total: 0 },
  }
  await page.route(url => isWorkbenchBackendUrl(url) && Object.hasOwn(responses, url.pathname + url.search), async route => {
    if (route.request().method() !== 'GET') { await route.fallback(); return }
    const url = new URL(route.request().url())
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data: responses[url.pathname + url.search] }) })
  })
}

/** Wait for the off-canvas navigation to finish leaving the mobile viewport. */
export async function expectMobileNavigationClosed(page: Page): Promise<void> {
  const navigation = page.locator('#socp-primary-navigation')
  await expect(navigation).toHaveAttribute('aria-hidden', 'true')
  await expect(navigation).not.toHaveClass(/is-mobile-open/)
  await expect.poll(async () => {
    const bounds = await navigation.boundingBox()
    return bounds !== null && bounds.x + bounds.width <= 0
  }).toBe(true)
}
