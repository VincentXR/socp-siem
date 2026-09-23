<script setup lang="ts">
import { computed, onMounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import 'element-plus/es/components/message/style/css.mjs'
import ElConfigProvider from 'element-plus/es/components/config-provider/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import LoginView from './LoginView.vue'
import AppShell from './components/AppShell.vue'
import { getVisibleMenuGroups } from './app/navigation'
import { exportAlarms } from './api'
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
const { currentUser, currentRole, operatorOptions, isAuthed, userInitials } = auth
const router = useRouter()
const route = useRoute()
const { activeMenu, navigate } = useWorkbenchRoute()
const menuGroups = computed(() => getVisibleMenuGroups(currentRole.value, t))
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
const overview = useOverview(overviewEnabled)
const { alarms } = overview
const alarmQuery = useAlarmQuery()

const isOffline = ref(typeof navigator !== 'undefined' ? !navigator.onLine : false)

function onLoginDone(user: string, role: string) {
  auth.onLoginDone(user, role)
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
  operatorOptions,
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
    <LoginView v-if="!isAuthed" @done="onLoginDone" />
    <AppShell
      v-else
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
      <main class="socp-content"><RouterView v-if="routeMenuAllowed" /></main>
    </AppShell>
  </el-config-provider>
</template>
