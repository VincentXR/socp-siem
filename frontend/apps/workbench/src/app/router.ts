import { defineComponent, h } from 'vue'
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { MENU_PATHS } from './routes'

const RouteMarker = defineComponent({
  name: 'WorkbenchRouteMarker',
  setup: () => () => h('span', { class: 'sr-only', 'aria-hidden': 'true' }),
})

const menuRoutes: RouteRecordRaw[] = Object.entries(MENU_PATHS).map(([menu, path]) => ({
  path,
  name: menu,
  component: RouteMarker,
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
