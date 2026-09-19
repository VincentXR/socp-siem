import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { MENU_PATHS, type MenuKey } from './routes'

/**
 * Keep page ownership in the router.  Each view is a lazy route component so
 * navigation metadata, code-splitting, and lifecycle all share one source of
 * truth instead of a second conditional tree in App.vue.
 */
const pageComponents: Record<MenuKey, NonNullable<RouteRecordRaw['component']>> = {
  overview: () => import('../routes/OverviewRoute.vue'),
  situation: () => import('../routes/SituationRoute.vue'),
  alarms: () => import('../routes/AlarmsRoute.vue'),
  case: () => import('../views/CasesView.vue'),
  search: () => import('../views/SearchView.vue'),
  notify: () => import('../views/NotifyView.vue'),
  detect: () => import('../views/DetectView.vue'),
  ueba: () => import('../routes/UebaRoute.vue'),
  soar: () => import('../views/SoarView.vue'),
  attack: () => import('../routes/AttackRoute.vue'),
  assets: () => import('../views/AssetsView.vue'),
  endpoints: () => import('../views/EndpointsView.vue'),
  'threat-intel': () => import('../views/ThreatIntelView.vue'),
  refset: () => import('../views/RefsetView.vue'),
  ingest: () => import('../views/IngestView.vue'),
  meta: () => import('../views/MetaView.vue'),
  compliance: () => import('../views/ComplianceView.vue'),
  report: () => import('../routes/ReportRoute.vue'),
  ai: () => import('../views/AiAssistantView.vue'),
}

const menuRoutes: RouteRecordRaw[] = (Object.keys(MENU_PATHS) as MenuKey[]).map(menu => ({
  path: MENU_PATHS[menu],
  name: menu,
  component: pageComponents[menu],
  meta: { menu },
}))

/**
 * The catch-all keeps a name so the shell can tell a dead deep link apart from
 * a role-based fallback: the router records the unmatched location as
 * `route.redirectedFrom`, and only this route carries this name.
 */
export const NOT_FOUND_ROUTE = 'not-found'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: MENU_PATHS.overview },
    ...menuRoutes,
    { path: '/detect/rules/new', name: 'rule-new', component: pageComponents.detect, meta: { menu: 'detect', editor: true, crumbKey: 'detect.createRule' } },
    { path: '/detect/rules/:ruleId/edit', name: 'rule-edit', component: pageComponents.detect, meta: { menu: 'detect', editor: true, crumbKey: 'detect.editRule' } },
    { path: '/soar/playbooks/new', name: 'playbook-new', component: pageComponents.soar, meta: { menu: 'soar', editor: true, crumbKey: 'soar.createPlaybook' } },
    { path: '/soar/playbooks/:playbookId/edit', name: 'playbook-edit', component: pageComponents.soar, meta: { menu: 'soar', editor: true, crumbKey: 'soar.editorTitle' } },
    // `parser-edit` has no dedicated message key yet, so it keeps the two-segment breadcrumb.
    { path: '/ingest/parsers/new', name: 'parser-new', component: () => import('../views/ParseRuleEditorView.vue'), meta: { menu: 'ingest', crumbKey: 'ingest.addParseRule' } },
    { path: '/ingest/parsers/:parserId/edit', name: 'parser-edit', component: () => import('../views/ParseRuleEditorView.vue'), meta: { menu: 'ingest' } },
    { path: '/:pathMatch(.*)*', name: NOT_FOUND_ROUTE, redirect: MENU_PATHS.overview },
  ],
})

// A rolling deployment can leave an open tab pointing at a chunk name that the
// next build no longer serves. One guarded reload recovers that tab without an
// infinite loop when the deployment is genuinely broken. The one-shot budget is
// a per-incident timestamp: App.vue clears it once the router is ready, so a
// later, unrelated failure gets its own single reload instead of inheriting a
// spent flag.
export const CHUNK_RELOAD_KEY = 'socp.workbench.chunk-reload'
const CHUNK_ERROR = /failed to fetch dynamically imported module|error loading dynamically imported module|importing a module script failed|loading chunk .* failed/i

export function isChunkLoadError(message: string): boolean {
  return CHUNK_ERROR.test(message)
}

/**
 * Returns true only when it spent the reload budget and fired the reload. An
 * offline tab is never charged: a reload could not refetch the chunk anyway.
 */
export function guardChunkReload(deps: {
  message: string
  online: boolean
  storage: Pick<Storage, 'getItem' | 'setItem'>
  reload: () => void
}): boolean {
  if (!isChunkLoadError(deps.message)) return false
  if (!deps.online) return false
  try {
    if (deps.storage.getItem(CHUNK_RELOAD_KEY)) return false
    deps.storage.setItem(CHUNK_RELOAD_KEY, String(Date.now()))
  } catch {
    // Without a durable guard a blocked storage implementation would turn a
    // recoverable chunk error into an infinite reload loop.
    return false
  }
  deps.reload()
  return true
}

if (typeof window !== 'undefined') {
  router.onError(error => {
    guardChunkReload({
      message: error instanceof Error ? error.message : String(error),
      online: window.navigator.onLine,
      storage: window.sessionStorage,
      reload: () => window.location.reload(),
    })
  })
}
