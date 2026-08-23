export const MENU_PATHS = {
  overview: '/overview',
  situation: '/situation',
  alarms: '/alarms',
  case: '/cases',
  search: '/search',
  notify: '/notify',
  detect: '/detect',
  ueba: '/ueba',
  soar: '/soar',
  attack: '/attack',
  assets: '/assets',
  endpoints: '/endpoints',
  'threat-intel': '/threat-intel',
  refset: '/reference-sets',
  ingest: '/ingest',
  meta: '/metadata',
  compliance: '/compliance',
  report: '/reports',
  ai: '/assistant',
} as const

export type MenuKey = keyof typeof MENU_PATHS

const PATH_MENUS = new Map<string, MenuKey>(
  Object.entries(MENU_PATHS).map(([menu, path]) => [path, menu as MenuKey]),
)

export function isMenuKey(value: string): value is MenuKey {
  return Object.hasOwn(MENU_PATHS, value)
}

export function pathForMenu(menu: MenuKey): string {
  return MENU_PATHS[menu]
}

export function menuForPath(pathname: string): MenuKey {
  const normalized = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname
  return PATH_MENUS.get(normalized) ?? 'overview'
}

export function accessibleMenu(menu: MenuKey, visibleMenus: ReadonlySet<string>): MenuKey {
  return visibleMenus.has(menu) ? menu : 'overview'
}
