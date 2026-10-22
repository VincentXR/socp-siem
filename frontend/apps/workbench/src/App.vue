<script setup lang="ts">
import { computed, onMounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import 'element-plus/es/components/message/style/css.mjs'
import ElConfigProvider from 'element-plus/es/components/config-provider/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import LoginView from './LoginView.vue'
import AppShell from './components/AppShell.vue'
import BrandMark from './components/BrandMark.vue'
import { getVisibleMenuGroups } from './app/navigation'
import { exportAlarms, HEALTH_TARGETS } from './api'
import { useAlarmQuery } from './composables/useAlarmQuery'
import { useAuth } from './composables/useAuth'
import { useOverview } from './composables/useOverview'
import { useTheme } from './composables/useTheme'
import { useWorkbenchRoute } from './composables/useWorkbenchRoute'
import { accessibleMenu, isMenuKey } from './app/routes'
import { CHUNK_RELOAD_KEY, NOT_FOUND_ROUTE } from './app/router'
import { useI18n } from './composables/useI18n'
import { WORKBENCH_STATE } from './app/workbenchState'

const { t, elLocale } = useI18n()
const auth = useAuth()
const { currentUser, currentRole, currentTenant, currentPermissions, operatorOptions, operatorLabels, isAuthed, authReady, userInitials } = auth
const router = useRouter()
const route = useRoute()
const mainContent = ref<HTMLElement | null>(null)
// This app scrolls its main region, not window. A different page starts at
// its header; query-only changes (filters, paging, drawers) keep their position.
watch(() => route.path, () => {
  if (!mainContent.value) return
  mainContent.value.scrollTop = 0
  mainContent.value.scrollLeft = 0
}, { flush: 'post' })
const { activeMenu, navigate } = useWorkbenchRoute()
const menuGroups = computed(() => getVisibleMenuGroups(currentRole.value, t, currentPermissions.value))
const routeMenuAllowed = computed(() => menuGroups.value.some(group => group.items.some(item => item.key === activeMenu.value)))
const activeLabel = computed(() => {
  for (const group of menuGroups.value) {
    const item = group.items.find(menuItem => menuItem.key === activeMenu.value)
    if (item) return item.label
  }
  return t('menu.overview')
})

const { theme, initTheme, toggleTheme } = useTheme()
const overviewEnabled = computed(() => isAuthed.value && activeMenu.value === 'overview')
const overview = useOverview(overviewEnabled, isAuthed)
const { alarms } = overview
const alarmQuery = useAlarmQuery()

const isOffline = ref(typeof navigator !== 'undefined' ? !navigator.onLine : false)

function onLoginDone(user: string, role: string, tenant?: string, permissions?: string[]) {
  auth.onLoginDone(user, role, tenant, permissions)
}

/**
 * Landing back on Overview has two distinct causes, and an operator needs the
 * right one: the role cannot open the requested page, or the deep link matched
 * no route at all and the catch-all already rewrote it. `grouping` keeps the
 * mount-time guard and the menu watcher from stacking duplicate notices.
 */
function announceFallback(reason: 'forbidden' | 'not-found') {
  ElMessage.warning({
    message: t(reason === 'not-found' ? 'errors.NOT_FOUND' : 'nav.accessRedirected'),
    grouping: true,
  })
}

function onMenuChange(key: string) {
  if (!isMenuKey(key)) return
  const visibleMenus = new Set(menuGroups.value.flatMap(group => group.items.map(item => item.key)))
  const allowed = accessibleMenu(key, visibleMenus)
  if (allowed !== key) announceFallback('forbidden')
  navigate(allowed)
}

provide(WORKBENCH_STATE, {
  theme,
  currentUser,
  currentRole,
  currentTenant,
  currentPermissions,
  operatorOptions,
  operatorLabels,
  overview,
  alarmQuery,
  alarms,
  navigate: onMenuChange,
  exportAlarms,
  logout: auth.doLogout,
})

watch([activeMenu, isAuthed], ([key, authed]) => {
  if (authed && key === 'alarms') void alarmQuery.loadAlarmPage()
}, { immediate: true })

watch(menuGroups, groups => {
  const visibleMenus = new Set(groups.flatMap(group => group.items.map(item => item.key)))
  const allowed = accessibleMenu(activeMenu.value, visibleMenus)
  if (allowed !== activeMenu.value) {
    announceFallback('forbidden')
    navigate(allowed, true)
  }
})

// The router resolves a dead link through the named catch-all before this
// component settles, so the original address only survives on `redirectedFrom`.
watch(() => route.redirectedFrom, from => {
  if (from?.name === NOT_FOUND_ROUTE) announceFallback('not-found')
}, { immediate: true })

onMounted(async () => {
  initTheme()
  if (typeof window !== 'undefined') {
    window.addEventListener('online', () => { isOffline.value = false })
    window.addEventListener('offline', () => { isOffline.value = true })
  }
  // The initial history navigation can still be pending when App mounts. Wait
  // for the router before normalising the active menu, otherwise a deep link
  // such as /soar is transiently seen as / and redirected to /overview.
  await router.isReady()
  // The first navigation resolved from the current chunk set, so the rolling-
  // deploy recovery budget for this boot is spent; clear it so an unrelated
  // failure later can arm its own single reload.
  try { window.sessionStorage.removeItem(CHUNK_RELOAD_KEY) } catch { /* storage unavailable */ }
  if (!await auth.initAuth()) return
  // Authentication must retain an allowed editor/deep link and its query.
  // Menu navigation intentionally goes to the list, so use it only when the
  // restored session cannot access the current menu.
  if (!routeMenuAllowed.value) onMenuChange(activeMenu.value)
})
</script>

<template>
  <el-config-provider :locale="elLocale">
    <div v-if="isOffline" class="socp-offline-banner">
      {{ t('app.offlineBanner') }}
    </div>
    <div v-if="!authReady" class="auth-bootstrap" role="status">
      <BrandMark class="auth-bootstrap-mark" />
      <strong>SOCP</strong>
      <span>{{ t('common.loading') }}</span>
    </div>
    <LoginView v-else-if="!isAuthed" @done="onLoginDone" />
    <AppShell
      v-else
      :health-state="overview.healthStatus.value"
      :health-coverage="Object.keys(overview.healths.value).length ? t('overview.onlineCoverage', { online: HEALTH_TARGETS.filter(target => overview.healths.value[target.name] === 'up').length, total: HEALTH_TARGETS.length }) : '—'"
      :menu-groups="menuGroups"
      :active-menu="activeMenu"
      :active-label="activeLabel"
      :theme="theme"
      :current-user="currentUser"
      :current-role="currentRole"
      :user-initials="userInitials"
      @menu-change="onMenuChange"
      @toggle-theme="toggleTheme"
      @logout="auth.doLogout"
    >
      <main id="main-content" ref="mainContent" class="socp-content" tabindex="-1"><RouterView v-if="routeMenuAllowed" /></main>
    </AppShell>
  </el-config-provider>
</template>

<style scoped>
.auth-bootstrap {
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 9px;
  color: var(--ns-text-2);
  background: var(--ns-bg-subtle);
}
.auth-bootstrap strong { color: var(--ns-text); font-size: 14px; letter-spacing: .12em; }
.auth-bootstrap > span:last-child { color: var(--ns-text-3); font-size: 12px; }
.auth-bootstrap-mark { width: 42px; height: 42px; margin-bottom: 5px; }
</style>
