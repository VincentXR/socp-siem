/**
 * Shared e2e helpers. The workbench origin must match playwright.config.ts,
 * which honors E2E_PORT so local Windows boxes can dodge reserved port
 * ranges (e.g. 4157-4256 swallowing the default 4173 with EACCES).
 */
export function workbenchOrigin(): string {
  const port = Number(process.env.E2E_PORT ?? 4173)
  return `http://127.0.0.1:${port}`
}
