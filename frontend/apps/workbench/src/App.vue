<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import ElConfigProvider from 'element-plus/es/components/config-provider/index.mjs'
import LoginView from './LoginView.vue'
import AppShell from './components/AppShell.vue'
import OverviewView from './views/OverviewView.vue'
import AlarmsView from './views/AlarmsView.vue'
import { getVisibleMenuGroups } from './app/navigation'
import { exportAlarms } from './api'
import { useAlarmQuery } from './composables/useAlarmQuery'
import { useAuth } from './composables/useAuth'
import { useOverview } from './composables/useOverview'
import { useTheme } from './composables/useTheme'
import { useWorkbenchRoute } from './composables/useWorkbenchRoute'
import { accessibleMenu, isMenuKey } from './app/routes'
import { useI18n } from './composables/useI18n'

const AiAssistantView = defineAsyncComponent(() => import('./views/AiAssistantView.vue'))
const AssetsView = defineAsyncComponent(() => import('./views/AssetsView.vue'))
const EndpointsView = defineAsyncComponent(() => import('./views/EndpointsView.vue'))
const SearchView = defineAsyncComponent(() => import('./views/SearchView.vue'))
const SoarView = defineAsyncComponent(() => import('./views/SoarView.vue'))
const NotifyView = defineAsyncComponent(() => import('./views/NotifyView.vue'))
const CasesView = defineAsyncComponent(() => import('./views/CasesView.vue'))
const RefsetView = defineAsyncComponent(() => import('./views/RefsetView.vue'))
const ComplianceView = defineAsyncComponent(() => import('./views/ComplianceView.vue'))
const ReportView = defineAsyncComponent(() => import('./views/ReportView.vue'))
const ThreatIntelView = defineAsyncComponent(() => import('./views/ThreatIntelView.vue'))
const AttackView = defineAsyncComponent(() => import('./views/AttackView.vue'))
const IngestView = defineAsyncComponent(() => import('./views/IngestView.vue'))
const MetaView = defineAsyncComponent(() => import('./views/MetaView.vue'))
const DetectView = defineAsyncComponent(() => import('./views/DetectView.vue'))
const UebaView = defineAsyncComponent(() => import('./views/UebaView.vue'))
const SituationView = defineAsyncComponent(() => import('./views/SituationView.vue'))

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
const overview = useOverview(isAuthed)
const { alarms, healths, sitStats, stat, refreshOverview, loadOverviewStats } = overview
const alarmQuery = useAlarmQuery()
const {
  alarmSeverity,
  alarmKeyword,
  alarmStatus,
  alarmRule,
  alarmPageNum,
  alarmPageSize,
  alarmPageData,
  filteredAlarms,
  loadAlarmPage,
  onAlarmSearch,
  onAlarmSortChange,
} = alarmQuery

const isOffline = ref(typeof navigator !== 'undefined' ? !navigator.onLine : false)

function onLoginDone(user: string, role: string) {
  auth.onLoginDone(user, role)
  void refreshOverview()
  void loadOverviewStats()
}

function onMenuChange(key: string) {
  if (!isMenuKey(key)) return
  const visibleMenus = new Set(menuGroups.value.flatMap(group => group.items.map(item => item.key)))
  navigate(accessibleMenu(key, visibleMenus))
}

watch(activeMenu, key => {
  if (key === 'overview') void refreshOverview()
  if (key === 'alarms') void loadAlarmPage()
})

watch(menuGroups, groups => {
  const visibleMenus = new Set(groups.flatMap(group => group.items.map(item => item.key)))
  const allowed = accessibleMenu(activeMenu.value, visibleMenus)
  if (allowed !== activeMenu.value) navigate(allowed, true)
})

onMounted(async () => {
  initTheme()
  if (typeof window !== 'undefined') {
    window.addEventListener('online', () => { isOffline.value = false; void refreshOverview() })
    window.addEventListener('offline', () => { isOffline.value = true })
  }
  if (!await auth.initAuth()) return
  onMenuChange(activeMenu.value)
  void refreshOverview()
  void loadOverviewStats()
})
</script>

<template>
  <el-config-provider :locale="elLocale">
    <RouterView />
    <div v-if="isOffline" class="fixed top-0 left-0 w-full z-50 bg-amber-500 text-white text-xs py-1 text-center font-medium shadow">
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
      <main class="socp-content">
        <OverviewView
          v-if="activeMenu === 'overview'"
          :stat="stat"
          :sit-stats="sitStats"
          :filtered-alarms="alarms"
          :healths="healths"
          @refresh="refreshOverview"
        />

        <SituationView v-else-if="activeMenu === 'situation'" :theme="theme" @session-expired="auth.doLogout" />

        <AlarmsView
          v-else-if="activeMenu === 'alarms'"
          v-model:keyword="alarmKeyword"
          v-model:severity="alarmSeverity"
          v-model:status="alarmStatus"
          v-model:rule="alarmRule"
          v-model:page-num="alarmPageNum"
          :filtered-alarms="filteredAlarms"
          :alarm-page-data="alarmPageData"
          :alarm-page-size="alarmPageSize"
          :on-search="onAlarmSearch"
          :load-page="loadAlarmPage"
          :on-sort-change="onAlarmSortChange"
          :export-csv="() => exportAlarms('csv')"
          :export-json="() => exportAlarms('json')"
          :go-case="() => onMenuChange('case')"
          :go-search="() => onMenuChange('search')"
        />

        <SearchView v-else-if="activeMenu === 'search'" />
        <IngestView v-else-if="activeMenu === 'ingest'" />
        <MetaView v-else-if="activeMenu === 'meta'" />
        <DetectView v-else-if="activeMenu === 'detect'" />
        <UebaView v-else-if="activeMenu === 'ueba'" :theme="theme" @go-alarms="keyword => { alarmKeyword = keyword; onMenuChange('alarms') }" />
        <SoarView v-else-if="activeMenu === 'soar'" />
        <ReportView v-else-if="activeMenu === 'report'" :theme="theme" />
        <AssetsView v-else-if="activeMenu === 'assets'" />
        <EndpointsView v-else-if="activeMenu === 'endpoints'" />
        <AiAssistantView v-else-if="activeMenu === 'ai'" />
        <ThreatIntelView v-else-if="activeMenu === 'threat-intel'" />
        <AttackView v-else-if="activeMenu === 'attack'" :alarms="alarms" />
        <NotifyView v-else-if="activeMenu === 'notify'" />
        <CasesView v-else-if="activeMenu === 'case'" />
        <RefsetView v-else-if="activeMenu === 'refset'" />
        <ComplianceView v-else-if="activeMenu === 'compliance'" />
      </main>
    </AppShell>
  </el-config-provider>
</template>
