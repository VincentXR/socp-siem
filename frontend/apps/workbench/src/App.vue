<script setup lang="ts">
import { computed, onMounted, provide, ref, watch } from 'vue'
import ElConfigProvider from 'element-plus/es/components/config-provider/index.mjs'
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
import { useI18n } from './composables/useI18n'
import { WORKBENCH_STATE } from './app/workbenchState'

const { t, elLocale } = useI18n()
const auth = useAuth()
const { currentUser, currentRole, isAuthed, userInitials } = auth
const { activeMenu, navigate } = useWorkbenchRoute()
const menuGroups = computed(() => getVisibleMenuGroups(currentRole.value, t))
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

function onMenuChange(key: string) {
  if (!isMenuKey(key)) return
  const visibleMenus = new Set(menuGroups.value.flatMap(group => group.items.map(item => item.key)))
  navigate(accessibleMenu(key, visibleMenus))
}

provide(WORKBENCH_STATE, {
  theme,
  overview,
  alarmQuery,
  alarms,
  navigate: onMenuChange,
  exportAlarms,
  logout: auth.doLogout,
})

watch(activeMenu, key => {
  if (key === 'alarms') void alarmQuery.loadAlarmPage()
})

watch(menuGroups, groups => {
  const visibleMenus = new Set(groups.flatMap(group => group.items.map(item => item.key)))
  const allowed = accessibleMenu(activeMenu.value, visibleMenus)
  if (allowed !== activeMenu.value) navigate(allowed, true)
})

onMounted(async () => {
  initTheme()
  if (typeof window !== 'undefined') {
    window.addEventListener('online', () => { isOffline.value = false })
    window.addEventListener('offline', () => { isOffline.value = true })
  }
  if (!await auth.initAuth()) return
  onMenuChange(activeMenu.value)
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
      <main class="socp-content"><RouterView /></main>
    </AppShell>
  </el-config-provider>
</template>
