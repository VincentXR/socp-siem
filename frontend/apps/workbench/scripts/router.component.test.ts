import { describe, expect, it, vi } from 'vitest'
import { CHUNK_RELOAD_KEY, NOT_FOUND_ROUTE, guardChunkReload, isChunkLoadError, router } from '../src/app/router'
import { MENU_PATHS } from '../src/app/routes'
import { translate } from '../src/i18n'

describe('workbench router', () => {
  it('keeps the root and dead-link redirects pointed at Overview', () => {
    const routes = router.getRoutes()
    const root = routes.find(record => record.path === '/')
    const catchAll = routes.find(record => record.path === '/:pathMatch(.*)*')
    expect(root?.redirect).toBe(MENU_PATHS.overview)
    expect(catchAll?.redirect).toBe(MENU_PATHS.overview)
  })

  it('names only the dead-link catch-all so the shell can report the fallback cause', () => {
    const catchAll = router.getRoutes().find(record => record.path === '/:pathMatch(.*)*')
    expect(catchAll?.name).toBe(NOT_FOUND_ROUTE)
    expect(router.resolve('/does-not-exist').name).toBe(NOT_FOUND_ROUTE)
    expect(router.resolve(MENU_PATHS.overview).name).toBe('overview')
    expect(router.resolve('/').name).not.toBe(NOT_FOUND_ROUTE)
  })

  it('labels each editor sub-page crumb with a defined message', () => {
    const crumbKeys = router.getRoutes()
      .map(record => record.meta?.crumbKey)
      .filter((key): key is string => typeof key === 'string')
    expect(crumbKeys.length).toBeGreaterThan(0)
    for (const key of crumbKeys) {
      // vue-i18n echoes an undefined key verbatim, which would render a raw
      // message path in the header instead of a page name.
      expect(translate(key)).not.toBe(key)
    }
  })
})

function fakeStorage() {
  const entries = new Map<string, string>()
  return {
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => { entries.set(key, value) },
    has: () => entries.has(CHUNK_RELOAD_KEY),
  }
}

describe('chunk-reload guard', () => {
  it('recognises Chrome and Firefox dynamic-import failures', () => {
    expect(isChunkLoadError('Failed to fetch dynamically imported module: /assets/x.js')).toBe(true)
    expect(isChunkLoadError('error loading dynamically imported module: /assets/x.js')).toBe(true)
    expect(isChunkLoadError('Importing a module script failed.')).toBe(true)
    expect(isChunkLoadError('TypeError: cannot read props')).toBe(false)
  })

  it('spends the per-incident budget once and stays silent on an unrelated error', () => {
    const storage = fakeStorage()
    const reload = vi.fn()
    const deps = { message: 'Failed to fetch dynamically imported module: /a.js', online: true, storage, reload }
    expect(guardChunkReload(deps)).toBe(true)
    expect(reload).toHaveBeenCalledTimes(1)
    // The timestamp is written so a second failure in the same boot is blocked.
    expect(storage.has()).toBe(true)
    expect(guardChunkReload(deps)).toBe(false)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(guardChunkReload({ ...deps, message: 'boom' })).toBe(false)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('does not charge the offline tab, so recovery survives a reconnect', () => {
    const storage = fakeStorage()
    const reload = vi.fn()
    expect(guardChunkReload({ message: 'error loading dynamically imported module', online: false, storage, reload })).toBe(false)
    expect(reload).not.toHaveBeenCalled()
    expect(storage.has()).toBe(false)
  })
})
