import { describe, expect, it } from 'vitest'
import { NOT_FOUND_ROUTE, router } from '../src/app/router'
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
