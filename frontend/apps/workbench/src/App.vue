<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { setChartTheme } from './lib/echarts'
import { useRequest } from './composables/useRequest'

/** 主题（在 echarts 主题注册前声明，registerTheme 加载期即读取） */
const theme = ref<'light' | 'dark'>('light')

setChartTheme(theme.value)

import LoginView from './LoginView.vue'
import AnimatedNumber from './AnimatedNumber.vue'
import AppShell from './components/AppShell.vue'
import OverviewView from './views/OverviewView.vue'
import AlarmsView from './views/AlarmsView.vue'
import { getVisibleMenuGroups } from './app/navigation'
import {
  listAlarms, listAlarmsPaged,
  checkHealth, HEALTH_TARGETS,
  alarmStats,
  login as apiLogin, setToken, clearToken, setUnauthorizedHandler,
  exportAlarms,
  type AlarmPage, type AlarmSortField, type AlarmSortOrder,
} from './api'

const AiAssistantView = defineAsyncComponent(() => import('./views/AiAssistantView.vue'))
const AssetsView = defineAsyncComponent(() => import('./views/AssetsView.vue'))
const EndpointsView = defineAsyncComponent(() => import('./views/EndpointsView.vue'))
const HealthView = defineAsyncComponent(() => import('./views/HealthView.vue'))
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

// ---------- 导航 ----------
const activeMenu = ref('overview')

/** 菜单分组（按安全域归类，减少导航噪音） */
const MENU_VIEW = computed(() => getVisibleMenuGroups())
/** 当前菜单的中文名（顶栏面包屑用） */
const activeLabel = computed(() => {
  for (const g of MENU_VIEW.value) {
    const m = g.items.find(x => x.key === activeMenu.value)
    if (m) return m.label
  }
  return '安全概览'
})

/** Apple 线条风格 SVG 图标（24×24 viewBox，stroke 1.6） */
const MENU_ICONS: Record<string, string> = {
  dashboard: '<path d="M4 13h6V4H4v9Zm0 7h6v-5H4v5Zm10 0h6v-9h-6v9Zm0-16v5h6V4h-6Z"/>',
  radar: '<circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="3.5"/><path d="M12 3.5v4M12 16.5v4M3.5 12h4M16.5 12h4M6 6l2.8 2.8M15.2 15.2 18 18M18 6l-2.8 2.8M8.8 15.2 6 18"/>',
  alarm: '<path d="M12 3a8 8 0 0 0-8 8c0 3.3-1 5-2 6h20c-1-1-2-2.7-2-6a8 8 0 0 0-8-8Z"/><path d="M10 21h4"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/>',
  ingest: '<path d="M12 3v12M7 10l5 5 5-5"/><path d="M4 19h16"/>',
  meta: '<path d="M4 4h16v6H4zM4 14h16v6H4z"/>',
  detect: '<circle cx="12" cy="12" r="3"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4M4.9 4.9l2.8 2.8M16.3 16.3l2.8 2.8M19.1 4.9l-2.8 2.8M7.7 16.3l-2.8 2.8"/>',
  ueba: '<circle cx="8" cy="9" r="4"/><path d="M2 20c1.2-3 3.4-4.5 6-4.5s4.8 1.5 6 4.5"/><path d="M17 5c2.5 1 4 3 4 6"/><path d="M18 3.5V7h-3.5"/>',
  soar: '<path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z"/>',
  report: '<path d="M4 4h16v16H4z"/><path d="M8 16V9M12 16V7M16 16v-4"/>',
  assets: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 9h18M8 5v14"/>',
  endpoints: '<path d="M12 2 4 6v6c0 5 3.4 8.6 8 10 4.6-1.4 8-5 8-10V6l-8-4Z"/><path d="M9 12l2 2 4-4"/>',
  ai: '<rect x="4" y="7" width="16" height="12" rx="3"/><path d="M12 4v3M9 2v3M15 2v3M9.5 13h5M9.5 16h3"/>',
  threat: '<path d="M12 2c-3.5 2-6 5-6 9v5l6 4 6-4v-5c0-4-2.5-7-6-9Z"/><path d="M8 13c1.5-1 3-1.5 4-3 1 1.5 2.5 2 4 3"/>',
  attack: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1.2" fill="currentColor" stroke="none"/>',
  notify: '<path d="M12 3a7 7 0 0 0-7 7c0 3-1 5-2.5 6.5h19C20 15 19 13 19 10a7 7 0 0 0-7-7Z"/><path d="M10 20h4"/>',
  case: '<path d="M4 8h16v12H4z"/><path d="M8 8V5h8v3M4 12h16M10 15h4"/>',
  refset: '<path d="M5 3h14a1 1 0 0 1 1 1v15l-3-2-3 2-3-2-3 2-3-2V4a1 1 0 0 1 1-1Z"/>',
  compliance: '<path d="M6 4h12v16l-6-3-6 3V4Z"/><path d="m9 11 2 2 4-4"/>',
  health: '<path d="M12 21C7 16.5 3 13 3 9a5 5 0 0 1 9-3 5 5 0 0 1 9 3c0 4-4 7.5-9 12Z"/><path d="M8.5 10h2l1.5-3 2 5 1.5-2h2"/>',
}


// ---------- 主题（浅色/深色，localStorage 记忆，默认跟随系统；theme 声明见文件顶部，供 echarts 主题注册使用） ----------
function applyTheme(t: 'light' | 'dark') {
  theme.value = t
  document.documentElement.setAttribute('data-theme', t)
  try { localStorage.setItem('socp_theme', t) } catch { /* ignore */ }
  setChartTheme(t)
}
function toggleTheme() {
  applyTheme(theme.value === 'light' ? 'dark' : 'light')
}
function initTheme() {
  let t: 'light' | 'dark' | null = null
  try { t = localStorage.getItem('socp_theme') as 'light' | 'dark' | null } catch { /* ignore */ }
  if (!t) {
    t = window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  applyTheme(t)
}

// ---------- 登录态（网关 JWT；未登录回退 demo-token 兼容演示） ----------
const currentUser = ref<string>('')
const currentRole = ref<string>('')
/** 头像缩写（顶栏） */
const userInitials = computed(() => (currentUser.value || 'SY').slice(0, 2).toUpperCase())
const isAuthed = ref(false)
const queryClient = useQueryClient()
const showLoginDialog = ref(false)
const loginForm = ref({ username: 'demo', password: 'demo123' })
const loginBusy = ref(false)
function openLoginDialog() { loginForm.value = { username: 'demo', password: 'demo123' }; showLoginDialog.value = true }
/** 登录成功后回调（LoginView emit）：写状态 + 拉数据 */
function onLoginDone(user: string, role: string) {
  currentUser.value = user
  currentRole.value = role
  isAuthed.value = true
  showLoginDialog.value = false
  refreshOverview()
  loadOverviewStats()
}
async function doLogin() {
  if (loginBusy.value) return
  loginBusy.value = true
  try {
    const d = await apiLogin(loginForm.value.username, loginForm.value.password)
    currentUser.value = d.username
    currentRole.value = d.role
    try {
      localStorage.setItem('socp_user', d.username)
      localStorage.setItem('socp_role', d.role)
    } catch { /* ignore */ }
    showLoginDialog.value = false
    refreshOverview()
    loadOverviewStats()
  } catch (e) {
    ElMessage.error((e as Error).message || '登录失败')
  } finally {
    loginBusy.value = false
  }
}
function doLogout() {
  void queryClient.cancelQueries()
  clearToken()
  currentUser.value = ''
  currentRole.value = ''
  isAuthed.value = false
  try {
    localStorage.removeItem('socp_user')
    localStorage.removeItem('socp_role')
  } catch { /* ignore */ }
  location.reload()
}

// ---------- 概览 ----------
const overviewAlarmsQuery = useQuery({
  queryKey: ['overview', 'alarms'],
  queryFn: ({ signal }) => listAlarms(undefined, { signal }),
  enabled: isAuthed,
  refetchInterval: 10_000,
  refetchIntervalInBackground: false,
})
const overviewHealthQuery = useQuery({
  queryKey: ['overview', 'health'],
  queryFn: async ({ signal }) => {
    const results = await Promise.all(HEALTH_TARGETS.map(target => checkHealth(target.path, { signal, timeoutMs: 3_000 })))
    const map: Record<string, 'up' | 'down'> = {}
    HEALTH_TARGETS.forEach((target, index) => { map[target.name] = results[index] })
    return map
  },
  enabled: isAuthed,
  refetchInterval: 10_000,
  refetchIntervalInBackground: false,
})
const overviewStatsQuery = useQuery({
  queryKey: ['overview', 'stats'],
  queryFn: ({ signal }) => alarmStats({ signal }),
  enabled: isAuthed,
  refetchInterval: 10_000,
  refetchIntervalInBackground: false,
})
const alarms = computed(() => overviewAlarmsQuery.data.value ?? [])
const healths = computed(() => overviewHealthQuery.data.value ?? {})
const sitStats = computed(() => overviewStatsQuery.data.value ?? null)

const stat = computed(() => ({
  total: alarms.value.length,
  critical: alarms.value.filter(a => a.severity === 'CRITICAL').length,
  high: alarms.value.filter(a => a.severity === 'HIGH').length,
  online: Object.values(healths.value).filter(s => s === 'up').length,
}))

async function refreshOverview() {
  await Promise.allSettled([overviewAlarmsQuery.refetch(), overviewHealthQuery.refetch()])
}
async function loadOverviewStats() {
  await overviewStatsQuery.refetch().catch(() => undefined)
}

// ---------- 告警（后端分页查询） ----------
const alarmSeverity = ref('')
const alarmKeyword = ref('')
const alarmStatus = ref('')
const alarmRule = ref('')
const alarmSort = ref<AlarmSortField>('occurredAt')
const alarmOrder = ref<AlarmSortOrder>('descending')
const emptyAlarmPage: AlarmPage = { items: [], total: 0, page: 1, size: 10 }
const alarmPageRequest = useRequest<AlarmPage>(emptyAlarmPage)
const alarmPageData = computed(() => alarmPageRequest.data.value ?? emptyAlarmPage)
const alarmPageNum = ref(1)
const alarmPageSize = ref(10)
/** 当前页告警（分页 API 结果） */
const filteredAlarms = computed(() => alarmPageData.value.items)
async function loadAlarmPage() {
  await alarmPageRequest.execute(signal => listAlarmsPaged(
    alarmPageNum.value, alarmPageSize.value,
    alarmKeyword.value.trim() || undefined,
    alarmSeverity.value || undefined,
    alarmStatus.value || undefined,
    alarmRule.value.trim() || undefined,
    alarmSort.value,
    alarmOrder.value,
    { signal }))
}
function onAlarmSearch() { alarmPageNum.value = 1; void loadAlarmPage() }
function onAlarmSortChange(field: AlarmSortField, order: AlarmSortOrder) {
  alarmSort.value = field
  alarmOrder.value = order
  alarmPageNum.value = 1
  void loadAlarmPage()
}

/** 顶栏全局搜索：回车后跳到日志检索页并执行 */
const topSearch = ref('')
function onTopSearch() {
  if (!topSearch.value.trim()) return
  onMenuChange('search')
}

// ---------- 系统健康看板 ----------
// ---------- 生命周期 ----------
function onMenuChange(key: string) {
  activeMenu.value = key
  switch (key) {
    case 'overview': void refreshOverview(); break
    case 'alarms': void loadAlarmPage(); break
  }
}

onMounted(() => {
  initTheme()
  // token 过期/失效（任意 API 401）→ 清 token 回登录页，不再卡在已登录态（2026-08-13）
  setUnauthorizedHandler(() => {
    ElMessage.warning('登录已过期，请重新登录')
    setTimeout(doLogout, 600)
  })
  // OIDC 回调（2026-08-12）：网关 /auth/oidc/callback 302 回 ?socp_oidc_token=，写 token 并清 URL。
  // token 为网关统一签发的 HS256 session token，payload 里 sub=Keycloak 用户、role/tenant 来自 realm claim mapper。
  const oidcToken = new URLSearchParams(window.location.search).get('socp_oidc_token')
  if (oidcToken) {
    setToken(oidcToken)
    const claims = decodeJwtPayload(oidcToken)
    if (claims) {
      try {
        localStorage.setItem('socp_user', claims.sub || 'socp-user')
        localStorage.setItem('socp_role', claims.role || 'analyst')
      } catch { /* ignore */ }
    }
    window.history.replaceState({}, '', window.location.pathname)
  }
  try {
    currentUser.value = localStorage.getItem('socp_user') || ''
    currentRole.value = localStorage.getItem('socp_role') || ''
    isAuthed.value = !!(localStorage.getItem('socp_token') && currentUser.value)
  } catch { currentUser.value = ''; currentRole.value = ''; isAuthed.value = false }
  // 有登录态才拉数据；无登录态由 LoginView 接管（登录成功后回调再拉）
  if (!isAuthed.value) return
  void refreshOverview()
  void loadOverviewStats()
})

/** 解码 JWT payload（不验签，仅取展示用 sub/role；验签在网关/服务侧完成） */
function decodeJwtPayload(t: string): Record<string, any> | null {
  try {
    const p = t.split('.')[1]
    return JSON.parse(atob(p.replace(/-/g, '+').replace(/_/g, '/')))
  } catch { return null }
}

</script>

<template>
  <LoginView v-if="!isAuthed" @done="onLoginDone" />
  <AppShell
    v-else
    :menu-groups="MENU_VIEW"
    :active-menu="activeMenu"
    :active-label="activeLabel"
    :theme="theme"
    :current-user="currentUser"
    :current-role="currentRole"
    :user-initials="userInitials"
    :top-search="topSearch"
    @menu-change="onMenuChange"
    @toggle-theme="toggleTheme"
    @update:top-search="topSearch = $event"
    @top-search="onTopSearch"
    @logout="doLogout"
    @login="openLoginDialog"
  >
      <main class="socp-content">
        <!-- 概览 -->
        <OverviewView v-if="activeMenu === 'overview'"
          :stat="stat" :sit-stats="sitStats" :filtered-alarms="alarms" :healths="healths" @refresh="refreshOverview" />

        <SituationView v-else-if="activeMenu === 'situation'" :theme="theme" @session-expired="doLogout" />

        <!-- 告警查询 -->
        <AlarmsView v-else-if="activeMenu === 'alarms'"
          v-model:keyword="alarmKeyword" v-model:severity="alarmSeverity" v-model:status="alarmStatus" v-model:rule="alarmRule" v-model:page-num="alarmPageNum"
          :filtered-alarms="filteredAlarms" :alarm-page-data="alarmPageData" :alarm-page-size="alarmPageSize"
          :on-search="onAlarmSearch" :load-page="loadAlarmPage" :on-sort-change="onAlarmSortChange"
          :export-csv="() => exportAlarms('csv')" :export-json="() => exportAlarms('json')"
          :go-case="() => onMenuChange('case')" />

        <SearchView v-else-if="activeMenu === 'search'" :initial-query="topSearch" />

        <IngestView v-else-if="activeMenu === 'ingest'" />

        <MetaView v-else-if="activeMenu === 'meta'" />


        <DetectView v-else-if="activeMenu === 'detect'" />

        <UebaView v-else-if="activeMenu === 'ueba'" :theme="theme" @go-alarms="keyword => { alarmKeyword = keyword; onMenuChange('alarms') }" />

        <SoarView v-else-if="activeMenu === 'soar'" />

        <ReportView v-else-if="activeMenu === 'report'" :theme="theme" />

        <AssetsView v-else-if="activeMenu === 'assets'" />
        <EndpointsView v-else-if="activeMenu === 'endpoints'" />

        <!-- AI 助手 -->
        <AiAssistantView v-else-if="activeMenu === 'ai'" />

        <ThreatIntelView v-else-if="activeMenu === 'threat-intel'" />

        <AttackView v-else-if="activeMenu === 'attack'" :alarms="alarms" />

        <NotifyView v-else-if="activeMenu === 'notify'" />

        <CasesView v-else-if="activeMenu === 'case'" />

        <RefsetView v-else-if="activeMenu === 'refset'" />

        <ComplianceView v-else-if="activeMenu === 'compliance'" />
        <HealthView v-else-if="activeMenu === 'health'" />
      </main>
  </AppShell>
</template>
