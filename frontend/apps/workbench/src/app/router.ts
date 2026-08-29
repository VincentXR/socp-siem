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

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: MENU_PATHS.overview },
    ...menuRoutes,
    { path: '/:pathMatch(.*)*', redirect: MENU_PATHS.overview },
  ],
})
