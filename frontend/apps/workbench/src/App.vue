<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'

/** 主题（在 echarts 主题注册前声明，registerTheme 加载期即读取） */
const theme = ref<'light' | 'dark'>('light')

// Linear 风全局 echarts 主题（浅色/深色自适应，淡网格 / 细轴 / 圆角 tooltip / 品牌色系）
function themeColor(k: 'grid' | 'axis' | 'label' | 'tooltipBg' | 'tooltipText' | 'legend'): string {
  const dark = theme.value === 'dark'
  return {
    grid: dark ? 'rgba(255,255,255,.06)' : 'rgba(31,35,40,.06)',
    axis: dark ? '#3d444d' : '#d1d9e0',
    label: dark ? '#9198a1' : '#59636e',
    tooltipBg: dark ? '#21262d' : '#ffffff',
    tooltipText: dark ? '#e6edf3' : '#1f2328',
    legend: dark ? '#9198a1' : '#59636e',
  }[k]
}
/** 注册（或覆盖）echarts 'socp' 主题；主题切换时重调以刷新颜色 */
function registerChartTheme() {
  echarts.registerTheme('socp', {
    color: ['#4493f8', '#0969da', '#30d158', '#ff9f0a', '#f85149', '#8250df', '#39c5cf', '#bf8700'],
    backgroundColor: 'transparent',
    textStyle: { color: themeColor('label'), fontFamily: '-apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif' },
    title: { textStyle: { color: themeColor('label'), fontSize: 13, fontWeight: 600 }, subtextStyle: { color: themeColor('legend'), fontSize: 11 } },
    legend: { textStyle: { color: themeColor('legend'), fontSize: 11 } },
    tooltip: {
      backgroundColor: themeColor('tooltipBg'),
      borderColor: themeColor('grid'),
      borderWidth: 1,
      textStyle: { color: themeColor('tooltipText'), fontSize: 12 },
      extraCssText: 'border-radius:10px;box-shadow:0 8px 24px rgba(0,0,0,.12);',
    },
    categoryAxis: {
      axisLine: { lineStyle: { color: themeColor('axis') } },
      axisTick: { show: false },
      axisLabel: { color: themeColor('label'), fontSize: 11 },
      splitLine: { show: false },
    },
    valueAxis: {
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: themeColor('label'), fontSize: 11 },
      splitLine: { lineStyle: { color: themeColor('grid'), type: 'dashed' } },
    },
    line: { smooth: true, symbol: 'circle', symbolSize: 6, lineStyle: { width: 2 } },
    bar: { itemStyle: { borderRadius: [6, 6, 0, 0] } },
  })
}
registerChartTheme()
import LoginView from './LoginView.vue'
import AnimatedNumber from './AnimatedNumber.vue'
import TrendChart from './components/TrendChart.vue'
import SevBadge from './components/SevBadge.vue'
import PagerBar from './components/PagerBar.vue'
import {
  listAlarms, listAlarmsPaged, getDisposition, setDispositionStatus, assignAlarm, addAlarmNote,
  listSources, createSource, deleteSource, renderConfig,
  listParseRules, createParseRule, deleteParseRule, previewParse,
  listOutputs, createOutput, deleteOutput,
  listDataSourceTypes, createDataSourceType, deleteDataSourceType,
  listCategories, createCategory, deleteCategory,
  listFields, createField, deleteField,
  listRules, createGasRule, updateGasRule, deleteGasRule, gasStats, gasIngest,
  splSearch,
  listPlaybooks, createPlaybook, deletePlaybook, togglePlaybook, listPlaybookExecutions,
  dailyReport, trend7d,
  listAssets, deleteAsset, assetStats,
  listTenants, socOverview,
  listEndpoints, deleteEndpoint, endpointStats,
  archiveReport, listArchive,
  aiAsk, checkHealth, HEALTH_TARGETS,
  listIocs, createIoc, deleteIoc, tiMatch, tiStats,
  listTactics, listTechniques, attackCoverage,
  listChannels, createChannel, deleteChannel, toggleChannel, dispatchLog,
  listCases, caseTimeline, setCaseStatus, caseStats,
  listRefSets, createRefSet, deleteRefSet, addRefEntry,
  complianceFrameworks, complianceCoverage,
  uebaEntities, uebaEntity, uebaSummary, uebaScore,
  listWatchlists, putWatchlist, appendWatchlist, deleteWatchlist,
  listIngestTasks, ingestSummary, startIngestTask, stopIngestTask, testIngestTask,
  alarmStats, gasRecentAlerts, gasEngineStats,
  login as apiLogin, clearToken,
  exportAlarms, exportCases, exportSearch,
  SEVERITIES, SOURCE_TYPES, PARSE_FORMATS,
  type Alarm, type AlarmPage, type LogSource, type ParseRule, type SinkTarget,
  type DataSourceType, type LogCategory, type FieldDef,
  type Disposition, type SearchEvent, type SearchResult,
  type Playbook, type ReportSummary, type Asset, type Endpoint, type TenantInfo, type AiResult,
  type Ioc, type Technique, type Channel, type CaseInfo, type TimelineEvent, type ReferenceSet,
  type RiskEntity, type RiskSummary, type ScoreBreakdown, type Watchlist,
  type IngestTask, type IngestSummary, type AlarmStats, type GasAlert, type GasStats,
} from './api'

// ---------- 导航 ----------
const activeMenu = ref('overview')

/** 菜单分组（按安全域归类，减少导航噪音） */
const MENU_GROUPS = [
  {
    group: '总览',
    items: [
      { key: 'overview', label: '概览', icon: 'dashboard' },
      { key: 'situation', label: '实时态势', icon: 'radar' },
    ],
  },
  {
    group: '告警与事件',
    items: [
      { key: 'alarms', label: '告警查询', icon: 'alarm' },
      { key: 'case', label: '案件管理', icon: 'case' },
      { key: 'search', label: '日志检索', icon: 'search' },
      { key: 'notify', label: '通知集成', icon: 'notify' },
    ],
  },
  {
    group: '检测与响应',
    items: [
      { key: 'detect', label: '检测规则', icon: 'detect' },
      { key: 'ueba', label: 'UEBA 风险', icon: 'ueba' },
      { key: 'soar', label: '编排响应', icon: 'soar' },
      { key: 'attack', label: 'ATT&CK', icon: 'attack' },
    ],
  },
  {
    group: '资产与情报',
    items: [
      { key: 'assets', label: '资产管理', icon: 'assets' },
      { key: 'endpoints', label: '端点防护', icon: 'endpoints' },
      { key: 'threat-intel', label: '威胁情报', icon: 'threat' },
      { key: 'refset', label: '参考数据集', icon: 'refset' },
    ],
  },
  {
    group: '接入与配置',
    items: [
      { key: 'ingest', label: '日志接入', icon: 'ingest' },
      { key: 'meta', label: '元数据', icon: 'meta' },
      { key: 'compliance', label: '合规', icon: 'compliance' },
    ],
  },
  {
    group: '系统',
    items: [
      { key: 'report', label: '报表统计', icon: 'report' },
      { key: 'ai', label: 'AI 助手', icon: 'ai' },
      { key: 'health', label: '系统健康', icon: 'health' },
    ],
  },
]
// viewer（只读）角色隐藏配置/管理类菜单
const MENU_VIEWER_HIDDEN = ['ingest', 'meta', 'detect', 'soar', 'notify', 'refset']
/** 按角色过滤后的分组（隐藏整组若组内无可见项） */
const MENU_VIEW = computed(() =>
  MENU_GROUPS
    .map(g => ({ ...g, items: g.items.filter(m => !MENU_VIEWER_HIDDEN.includes(m.key)) }))
    .filter(g => g.items.length > 0))

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
  registerChartTheme()
}
function toggleTheme() {
  applyTheme(theme.value === 'light' ? 'dark' : 'light')
  // 主题切换后重绘图表（echarts canvas 不跟随 CSS 变量）
  loadSituation()
  loadReport()
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
const isAuthed = ref(false)
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
  loadSituation()
  openAlertStream()
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
    loadSituation()
  } catch (e) {
    ElMessage.error((e as Error).message || '登录失败')
  } finally {
    loginBusy.value = false
  }
}
function doLogout() {
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

// ---------- 弹窗状态（所有「新增」类操作统一走对话框） ----------
const showWlDialog = ref(false)
const showDsDialog = ref(false)
const showCatDialog = ref(false)
const showFieldDialog = ref(false)
const showIocDialog = ref(false)
const showChannelDialog = ref(false)
const showRefSetDialog = ref(false)
const showSourceDialog = ref(false)
const showOutputDialog = ref(false)
function openWlDialog() { newWl.value = { name: '', values: '' }; showWlDialog.value = true }
function openDsDialog() { newDsType.value = { code: '', name: '', description: '', enabled: true }; showDsDialog.value = true }
function openCatDialog() { newCategory.value = { code: '', name: '', description: '', defaultSeverity: 'MEDIUM', enabled: true }; showCatDialog.value = true }
function openFieldDialog() { newField.value = { fieldName: '', fieldLabel: '', fieldType: 'string', source: 'custom', searchable: true, aggregatable: true, stored: true, description: '' }; showFieldDialog.value = true }
function openIocDialog() { newIoc.value = { type: 'ip', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' }; showIocDialog.value = true }
function openChannelDialog() { newChannel.value = { name: '', type: 'SLACK', target: '', enabled: true, description: '' }; showChannelDialog.value = true }
function openRefSetDialog() { newRefSet.value = { name: '', description: '', entries: '' }; showRefSetDialog.value = true }
function openSourceDialog() { newSource.value = { name: '', type: 'FILE' as string, format: 'AUTO' as string, path: '', address: '', topic: '', env: 'local', readFrom: 'beginning', multiline: '', protocol: 'tcp', charset: 'utf-8', timezone: 'Asia/Shanghai', tags: '', frequency: 1, categoryId: '', groupId: '', enabled: true }; showSourceDialog.value = true }
function openOutputDialog() { newOutput.value = { name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true }; showOutputDialog.value = true }

// ---------- 概览 ----------
const alarms = ref<Alarm[]>([])
const healths = ref<Record<string, 'up' | 'down'>>({})
let refreshTimer: number | undefined

const stat = computed(() => ({
  total: alarms.value.length,
  critical: alarms.value.filter(a => a.severity === 'CRITICAL').length,
  high: alarms.value.filter(a => a.severity === 'HIGH').length,
  online: Object.values(healths.value).filter(s => s === 'up').length,
}))

async function refreshOverview() {
  try { alarms.value = await listAlarms() } catch { /* 静默 */ }
  const results = await Promise.all(HEALTH_TARGETS.map(h => checkHealth(h.path)))
  const map: Record<string, 'up' | 'down'> = {}
  HEALTH_TARGETS.forEach((h, i) => { map[h.name] = results[i] })
  healths.value = map
}

// ---------- 告警（后端分页查询） ----------
const alarmSeverity = ref('')
const alarmKeyword = ref('')
const alarmPageData = ref<AlarmPage>({ items: [], total: 0, page: 1, size: 20 })
const alarmPageNum = ref(1)
const alarmPageSize = ref(20)
/** 当前页告警（分页 API 结果） */
const filteredAlarms = computed(() => alarmPageData.value.items)
async function loadAlarmPage() {
  try {
    alarmPageData.value = await listAlarmsPaged(
      alarmPageNum.value, alarmPageSize.value,
      alarmKeyword.value.trim() || undefined,
      alarmSeverity.value || undefined)
  } catch { /* 静默 */ }
}
function onAlarmSearch() { alarmPageNum.value = 1; loadAlarmPage() }

// ---------- 告警详情/处置 ----------
const drawerVisible = ref(false)
const currentAlarm = ref<Alarm | null>(null)
const disposition = ref<Disposition | null>(null)
const newStatus = ref('')
const newAssignee = ref('')
const newNote = ref('')
const DISP_STATUSES = ['OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED']

async function openAlarm(a: Alarm) {
  currentAlarm.value = a
  drawerVisible.value = true
  findRelatedCase(a.id)
  try {
    disposition.value = await getDisposition(a.id)
    newStatus.value = disposition.value.status
  } catch {
    disposition.value = { status: 'OPEN', assignee: null, notes: [] }
    newStatus.value = 'OPEN'
  }
}
async function changeStatus() {
  if (!currentAlarm.value || !newStatus.value) return
  disposition.value = await setDispositionStatus(currentAlarm.value.id, newStatus.value)
}
async function doAssign() {
  if (!currentAlarm.value || !newAssignee.value.trim()) return
  disposition.value = await assignAlarm(currentAlarm.value.id, newAssignee.value.trim())
  newAssignee.value = ''
}
async function doAddNote() {
  if (!currentAlarm.value || !newNote.value.trim()) return
  disposition.value = await addAlarmNote(currentAlarm.value.id, newNote.value.trim(), 'operator')
  newNote.value = ''
}

// ---------- 接入（三子标签：输入源 / 输出配置 / 解析规则） ----------
const ingestTab = ref('tasks')
const sources = ref<LogSource[]>([])
const newSource = ref({ name: '', type: 'FILE' as string, format: 'AUTO' as string, path: '', address: '', topic: '', env: 'local', readFrom: 'beginning', multiline: '', protocol: 'tcp', charset: 'utf-8', timezone: 'Asia/Shanghai', tags: '', frequency: 1, categoryId: '', groupId: '', enabled: true })
const renderText = ref('')
const showRender = ref(false)

// 输出配置
const outputs = ref<SinkTarget[]>([])
const newOutput = ref({ name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true })
// 解析规则
const parseRules = ref<ParseRule[]>([])
const newRule = ref({ name: '', format: 'REGEX' as string, pattern: '', sourceId: '', enabled: true, order: 10 })
const showRuleDialog = ref(false)
// 预览
const previewLine = ref('Aug 07 01:00:00 web01 sshd[123]: Failed password for admin from 10.0.0.99 port 55006 ssh2')
const previewRuleId = ref('')
const previewResult = ref<{ matched: boolean; fields: Record<string, string>; error?: string } | null>(null)

async function loadSources() { sources.value = await listSources() }
async function loadOutputs() { outputs.value = await listOutputs() }
async function loadParseRules() { parseRules.value = await listParseRules() }
function onIngestTab(key: string) {
  ingestTab.value = key
  if (key === 'sources') loadSources()
  if (key === 'outputs') loadOutputs()
  if (key === 'rules') loadParseRules()
  if (key === 'tasks') loadTasks()
}
async function addSource() {
  const s: Record<string, unknown> = {
    name: newSource.value.name, type: newSource.value.type, format: newSource.value.format,
    env: newSource.value.env, enabled: newSource.value.enabled, readFrom: newSource.value.readFrom,
    protocol: newSource.value.protocol, charset: newSource.value.charset, timezone: newSource.value.timezone,
    frequency: Number(newSource.value.frequency) || 1, groupId: newSource.value.groupId || null,
    categoryId: newSource.value.categoryId || null,
  }
  if (newSource.value.multiline.trim()) s.multiline = newSource.value.multiline.trim()
  if (newSource.value.tags.trim()) s.tags = newSource.value.tags.split(/[,，\s]+/).filter(Boolean)
  if (newSource.value.type === 'FILE') s.path = newSource.value.path || 'demo/sample.log'
  if (newSource.value.type === 'SOCKET' || newSource.value.type === 'SYSLOG') s.address = newSource.value.address || '0.0.0.0:5514'
  if (newSource.value.type === 'KAFKA') s.topic = newSource.value.topic || 'socp-raw'
  await createSource(s); newSource.value.name = ''; await loadSources()
}
async function removeSource(id: string) { await deleteSource(id); await loadSources() }
async function doRender() { renderText.value = await renderConfig(); showRender.value = true }
function copyRender() { navigator.clipboard.writeText(renderText.value) }

async function addOutput() {
  await createOutput({ name: newOutput.value.name, type: newOutput.value.type, uri: newOutput.value.uri, authToken: newOutput.value.authToken || null, enabled: newOutput.value.enabled })
  newOutput.value = { name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true }
  await loadOutputs()
}
async function removeOutput(id: string) { await deleteOutput(id); await loadOutputs() }

async function addParseRule() {
  const rule: Partial<ParseRule> = {
    name: newRule.value.name, format: newRule.value.format,
    pattern: newRule.value.format === 'REGEX' ? newRule.value.pattern : null,
    sourceId: newRule.value.sourceId || null, enabled: newRule.value.enabled, order: newRule.value.order,
    mapping: [], setFields: [],
  }
  await createParseRule(rule)
  showRuleDialog.value = false
  newRule.value = { name: '', format: 'REGEX', pattern: '', sourceId: '', enabled: true, order: 10 }
  await loadParseRules()
}
async function removeParseRule(id: string) { await deleteParseRule(id); await loadParseRules() }

async function doPreview() {
  try {
    previewResult.value = await previewParse({
      ruleId: previewRuleId.value || undefined,
      line: previewLine.value,
    })
  } catch (e) {
    previewResult.value = { matched: false, fields: {}, error: e instanceof Error ? e.message : String(e) }
  }
}

// ---------- 元数据（数据源分类 / 日志类别 / 字段字典） ----------
const metaTab = ref('ds')
const dataSourceTypes = ref<DataSourceType[]>([])
const logCategories = ref<LogCategory[]>([])
const fieldDefs = ref<FieldDef[]>([])
const newDsType = ref({ code: '', name: '', description: '', enabled: true })
const newCategory = ref({ code: '', name: '', description: '', defaultSeverity: 'MEDIUM', enabled: true })
const newField = ref({ fieldName: '', fieldLabel: '', fieldType: 'string', source: 'custom', searchable: true, aggregatable: true, stored: true, description: '' })

async function loadMeta() {
  dataSourceTypes.value = await listDataSourceTypes()
  logCategories.value = await listCategories()
  fieldDefs.value = await listFields()
}
function onMetaTab(key: string) { metaTab.value = key }
async function addDsType() {
  await createDataSourceType({ code: newDsType.value.code, name: newDsType.value.name, description: newDsType.value.description, enabled: newDsType.value.enabled })
  newDsType.value = { code: '', name: '', description: '', enabled: true }
  await loadMeta()
}
async function removeDsType(id: string) { await deleteDataSourceType(id); await loadMeta() }
async function addCategory() {
  await createCategory({ code: newCategory.value.code, name: newCategory.value.name, description: newCategory.value.description, defaultSeverity: newCategory.value.defaultSeverity, enabled: newCategory.value.enabled })
  newCategory.value = { code: '', name: '', description: '', defaultSeverity: 'MEDIUM', enabled: true }
  await loadMeta()
}
async function removeCategory(id: string) { await deleteCategory(id); await loadMeta() }
async function addField() {
  await createField(newField.value)
  newField.value = { fieldName: '', fieldLabel: '', fieldType: 'string', source: 'custom', searchable: true, aggregatable: true, stored: true, description: '' }
  await loadMeta()
}
async function removeField(id: string) { await deleteField(id); await loadMeta() }

// ---------- 检测 ----------
const rules = ref<unknown[]>([])
const gasStat = ref<Record<string, unknown>>({})
const ingestMsg = ref(''); const ingestSource = ref('auth'); const ingestResult = ref('')
async function loadRules() { rules.value = await listRules(); gasStat.value = await gasStats() }
async function doIngest() {
  try {
    const r = await gasIngest({ source: ingestSource.value, msg: ingestMsg.value, fields: { src_ip: '10.0.0.9' } }) as Record<string, unknown>
    ingestResult.value = JSON.stringify(r)
  } catch (e) { ingestResult.value = String(e) }
}

// ---------- 规则编辑器 ----------
const showRuleEditor = ref(false)
const ruleEditingId = ref<string | null>(null)
const ruleForm = ref({
  id: '', name: '', type: 'pattern', severity: 'HIGH', message: '',
  keyField: 'src_ip', threshold: 5, window: '60s', enabled: true,
  match: [{ field: 'msg', op: 'contains', value: '' }],
  steps: [] as Array<Array<{ field: string; op: string; value: string }>>,
})
const COND_FIELDS = ['source', 'host', 'msg', 'severity', 'src_ip', 'dst_ip', 'user', 'action', 'http_method', 'url', 'bytes']
const COND_OPS = ['eq', 'ne', 'contains', 'startswith', 'endswith', 'regex', 'gt', 'gte', 'lt', 'lte', 'ge']

function openRuleEditor(rule?: Record<string, unknown>) {
  if (rule) {
    ruleEditingId.value = String(rule.id)
    ruleForm.value = {
      id: String(rule.id), name: String(rule.name ?? ''), type: String(rule.type ?? 'pattern'),
      severity: String(rule.severity ?? 'HIGH'), message: String(rule.message ?? ''),
      keyField: String(rule.keyField ?? 'src_ip'), threshold: Number(rule.threshold ?? 5),
      window: String(rule.window ?? '60s'), enabled: Boolean(rule.enabled ?? true),
      match: (rule.match as Array<{ field: string; op: string; value: string }> | undefined)?.length
        ? JSON.parse(JSON.stringify(rule.match))
        : [{ field: 'msg', op: 'contains', value: '' }],
      steps: rule.steps ? JSON.parse(JSON.stringify(rule.steps)) : [],
    }
  } else {
    ruleEditingId.value = null
    ruleForm.value = { id: '', name: '', type: 'pattern', severity: 'HIGH', message: '', keyField: 'src_ip', threshold: 5, window: '60s', enabled: true, match: [{ field: 'msg', op: 'contains', value: '' }], steps: [] }
  }
  showRuleEditor.value = true
}

async function saveRule() {
  if (!ruleForm.value.name.trim()) return
  const spec: Record<string, unknown> = {
    id: ruleEditingId.value || undefined,
    name: ruleForm.value.name, type: ruleForm.value.type,
    severity: ruleForm.value.severity, message: ruleForm.value.message,
    enabled: ruleForm.value.enabled, window: ruleForm.value.window,
  }
  if (ruleForm.value.type === 'threshold') {
    spec.keyField = ruleForm.value.keyField
    spec.threshold = ruleForm.value.threshold
    spec.match = ruleForm.value.match.filter(c => c.value !== '')
  } else if (ruleForm.value.type === 'pattern') {
    spec.match = ruleForm.value.match.filter(c => c.value !== '')
  } else {
    spec.keyField = ruleForm.value.keyField
    spec.steps = ruleForm.value.steps.filter(s => s.some(c => c.value !== ''))
  }
  try {
    if (ruleEditingId.value) await updateGasRule(ruleEditingId.value, spec)
    else await createGasRule(spec)
    showRuleEditor.value = false
    await loadRules()
  } catch (e) {
    ingestResult.value = `保存失败: ${e instanceof Error ? e.message : e}`
  }
}

async function removeRule(id: string) {
  if (!confirm('确认删除该规则？删除后立即热更新引擎。')) return
  await deleteGasRule(id)
  await loadRules()
}

async function toggleRule(rule: Record<string, unknown>) {
  await updateGasRule(String(rule.id), { ...rule, enabled: !rule.enabled })
  await loadRules()
}

// ---------- SPL 检索 ----------
const searchQuery = ref('source=auth severity=HIGH')
const searchResult = ref<SearchResult | null>(null)
const searchLoading = ref(false)
const maxStatCount = computed(() =>
  Math.max(1, ...(searchResult.value?.stat?.rows.map(r => Number(r.count)) ?? [1])),
)
const SEARCH_EXAMPLES = [
  'source=auth severity=HIGH',
  'msg contains "blocked" | top src_ip 5',
  'severity>=HIGH | timechart',
  'src_ip=10.0.0.9 OR user=admin',
  'source=web | count by http_method',
  'bytes>=1000 | head 10',
]
async function doSearch() {
  searchLoading.value = true
  try {
    searchResult.value = await splSearch(searchQuery.value)
  } catch (e) {
    searchResult.value = null
    ingestResult.value = `检索失败: ${e instanceof Error ? e.message : e}`
  } finally {
    searchLoading.value = false
  }
}

// ---------- 编排 ----------
const playbooks = ref<Playbook[]>([])
const pbExecutions = ref<Array<Record<string, unknown>>>([])
const showPbDialog = ref(false)
const newPb = ref({ name: '', trigger: '', actions: '', enabled: true })
async function loadPlaybooks() {
  playbooks.value = await listPlaybooks()
  try { pbExecutions.value = await listPlaybookExecutions() } catch { pbExecutions.value = [] }
}
async function addPb() {
  await createPlaybook({ name: newPb.value.name, trigger: newPb.value.trigger, actions: newPb.value.actions.split(/[,，\n]/).map(s => s.trim()).filter(Boolean), enabled: newPb.value.enabled })
  showPbDialog.value = false; newPb.value = { name: '', trigger: '', actions: '', enabled: true }; await loadPlaybooks()
}
async function removePb(id: string) { await deletePlaybook(id); await loadPlaybooks() }
async function togglePb(id: string) { await togglePlaybook(id); await loadPlaybooks() }

// ---------- 报表 ----------
const report = ref<ReportSummary | null>(null)
const trend = ref<{ days: string[]; counts: number[] } | null>(null)
const chartBar = shallowRef<echarts.ECharts>(); const chartLine = shallowRef<echarts.ECharts>()
const barEl = ref<HTMLElement>(); const lineEl = ref<HTMLElement>()
/** 主题感知取色：图表文字/轴线在深色下用浅灰、浅色下用深灰，避免白底黑字看不见 */
function tc(light: string, dark: string): string { return theme.value === 'dark' ? dark : light }
async function loadReport() {
  const [r, t] = await Promise.all([dailyReport(), trend7d()])
  report.value = r; trend.value = t
  setTimeout(() => {
    if (barEl.value && r) {
      chartBar.value?.dispose(); chartBar.value = echarts.init(barEl.value, 'socp')
      chartBar.value.setOption({
        title: { text: '告警级别分布', textStyle: { fontSize: 14 } }, tooltip: {},
        xAxis: { type: 'category', data: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] }, yAxis: { type: 'value' },
        series: [{ type: 'bar', data: ['CRITICAL','HIGH','MEDIUM','LOW'].map(k => r.bySeverity[k] ?? 0),
          itemStyle: { color: (p: { dataIndex: number }) => ['#f56c6c','#e63946','#e6a23c','#909399'][p.dataIndex] } }],
      })
    }
    if (lineEl.value && t) {
      chartLine.value?.dispose(); chartLine.value = echarts.init(lineEl.value, 'socp')
      chartLine.value.setOption({
        title: { text: '近 7 日趋势', textStyle: { fontSize: 14 } }, tooltip: { trigger: 'axis' },
        xAxis: { type: 'category', data: t.days }, yAxis: { type: 'value' },
        series: [{ type: 'line', smooth: true, data: t.counts, areaStyle: {} }],
      })
    }
  }, 100)
}

// ---------- 资产 ----------
const assets = ref<Asset[]>([])
const assetStat = ref<{ total: number; byType: Record<string, number>; byCriticality: Record<string, number> } | null>(null)
async function loadAssets() {
  const [a, s] = await Promise.allSettled([listAssets(), assetStats()])
  if (a.status === 'fulfilled') assets.value = a.value
  if (s.status === 'fulfilled') assetStat.value = s.value
}
async function removeAsset(id: string) { await deleteAsset(id); await loadAssets() }

// ---------- 端点 ----------
const endpoints = ref<Endpoint[]>([])
const endpointStat = ref<{ total: number; online: number; byType: Record<string, number> } | null>(null)
async function loadEndpoints() {
  const [e, s] = await Promise.allSettled([listEndpoints(), endpointStats()])
  if (e.status === 'fulfilled') endpoints.value = e.value
  if (s.status === 'fulfilled') endpointStat.value = s.value
}
async function removeEp(id: string) { await deleteEndpoint(id); await loadEndpoints() }


// ---------- 列表前端分页（案件/资产/IOC/剧本执行） ----------
const casePage = ref(1), caseSize = ref(10)
const assetPage = ref(1), assetSize = ref(10)
const iocPage = ref(1), iocSize = ref(10)
const pbExecPage = ref(1), pbExecSize = ref(10)
const casesPaged = computed(() => cases.value.slice((casePage.value-1)*caseSize.value, casePage.value*caseSize.value))
const assetsPaged = computed(() => assets.value.slice((assetPage.value-1)*assetSize.value, assetPage.value*assetSize.value))
const iocsPaged = computed(() => iocs.value.slice((iocPage.value-1)*iocSize.value, iocPage.value*iocSize.value))
const pbExecsPaged = computed(() => pbExecutions.value.slice((pbExecPage.value-1)*pbExecSize.value, pbExecPage.value*pbExecSize.value))

// ---------- 报表归档（MinIO） ----------
const archiveInfo = ref<{ count: number; objects: Array<{ key: string; size: number }> } | null>(null)
const archiveBusy = ref(false)
async function doArchive() {
  if (archiveBusy.value) return
  archiveBusy.value = true
  try {
    const r = await archiveReport()
    if (r.archived) {
      ElMessage.success(`报表已归档至 MinIO（${r.day}/${r.dailyKey}）`)
    } else {
      ElMessage.error(r.error || '归档失败')
    }
    await loadArchive()
  } catch (e) {
    ElMessage.error((e as Error).message || '归档失败')
  } finally {
    archiveBusy.value = false
  }
}
async function loadArchive() {
  try { archiveInfo.value = await listArchive() } catch { /* MinIO 未启用时静默 */ }
}

// ---------- SOC ----------
const tenants = ref<TenantInfo[]>([]); const socInfo = ref<Record<string, unknown>>({})
async function loadSoc() { tenants.value = await listTenants(); socInfo.value = await socOverview() }

// ---------- AI ----------
const aiQuestion = ref(''); const aiResult = ref<AiResult | null>(null); const aiLoading = ref(false)
async function doAsk() { if (!aiQuestion.value.trim()) return; aiLoading.value = true; try { aiResult.value = await aiAsk(aiQuestion.value) } finally { aiLoading.value = false } }

// ---------- 实时态势大屏 ----------
const sitStats = ref<AlarmStats | null>(null)
const sitEngine = ref<GasStats | null>(null)
const sitIngest = ref<IngestSummary | null>(null)
const liveFeed = ref<Array<GasAlert & { _new?: boolean }>>([])
const liveOn = ref(true)
const liveSevFilter = ref('')
const epsHistory = ref<number[]>([])
let liveTimer: number | undefined
// SSE 实时告警流：替代轮询等待，新告警即时插入 feed 并刷新 KPI
let alertStream: EventSource | null = null
function openAlertStream() {
  try {
    alertStream = new EventSource('/detect-web/api/v1/stream')
    alertStream.addEventListener('alert', (e: MessageEvent) => {
      try {
        const a = JSON.parse(e.data)
        if (a && a.ruleId) {
          const item: GasAlert = {
            id: a.id ?? `sse-${a.ruleId}-${a.timestamp}`,
            timestamp: a.timestamp ?? new Date().toISOString(),
            ruleId: a.ruleId, ruleName: a.ruleName ?? '',
            severity: a.severity ?? 'INFO', message: a.message ?? '',
            entity: a.entity ?? '',
          }
          mergeFeed([item])
          loadSituation() // 同步刷新 KPI/图表，不必等下一个 4s 周期
        }
      } catch { /* 忽略异常帧 */ }
    })
    alertStream.onerror = () => { /* EventSource 内建自动重连 */ }
  } catch { /* 不支持 SSE 时退化为轮询 */ }
}
function closeAlertStream() { if (alertStream) { alertStream.close(); alertStream = null } }
const gaugeEl = ref<HTMLElement>(); const donutEl = ref<HTMLElement>(); const epsEl = ref<HTMLElement>()
const chartGauge = shallowRef<echarts.ECharts>(); const chartDonut = shallowRef<echarts.ECharts>()
const chartEps = shallowRef<echarts.ECharts>()

const feedView = computed(() =>
  liveSevFilter.value ? liveFeed.value.filter(a => a.severity === liveSevFilter.value) : liveFeed.value)
const queuePct = computed(() => Math.round(((sitEngine.value?.queueLoad ?? 0)) * 1000) / 10)

async function loadSituation() {
  const [s, e, g, i] = await Promise.allSettled([alarmStats(), gasEngineStats(), gasRecentAlerts(), ingestSummary()])
  if (s.status === 'fulfilled') sitStats.value = s.value
  if (e.status === 'fulfilled') sitEngine.value = e.value
  if (i.status === 'fulfilled') {
    sitIngest.value = i.value
    epsHistory.value = [...epsHistory.value, i.value.eps1m ?? 0].slice(-40)
  }
  if (g.status === 'fulfilled') mergeFeed(g.value)
  renderSitCharts()
}

/** 增量并入实时流：只把新 id 打上高亮标记，避免整表重绘导致的闪烁 */
function mergeFeed(incoming: GasAlert[]) {
  const known = new Set(liveFeed.value.map(a => a.id))
  const fresh = incoming.filter(a => !known.has(a.id)).map(a => ({ ...a, _new: true }))
  if (fresh.length === 0) return
  liveFeed.value = [...fresh, ...liveFeed.value.map(a => ({ ...a, _new: false }))].slice(0, 200)
  window.setTimeout(() => { liveFeed.value = liveFeed.value.map(a => ({ ...a, _new: false })) }, 1600)
}

function renderSitCharts() {
  setTimeout(() => {
    const st = sitStats.value
    // 概览页：近 7 日趋势（sparkline 风格，简洁单线）
    if (gaugeEl.value) {
      if (!chartGauge.value || chartGauge.value.isDisposed()) chartGauge.value = echarts.init(gaugeEl.value, 'socp')
      chartGauge.value.setOption({
        series: [{
          type: 'gauge', min: 0, max: 100, radius: '92%', center: ['50%', '58%'],
          startAngle: 210, endAngle: -30, splitNumber: 5,
          axisLine: { lineStyle: { width: 14, color: [[0.2, '#67c23a'], [0.4, '#95d475'], [0.65, '#e6a23c'], [0.85, '#f89898'], [1, '#f56c6c']] } },
          pointer: { width: 4, length: '62%' },
          axisTick: { distance: -14, length: 4, lineStyle: { color: 'transparent' } },
          splitLine: { distance: -14, length: 14, lineStyle: { color: 'transparent', width: 2 } },
          axisLabel: { distance: 16, fontSize: 10, color: tc('#818b98', '#9198a1') },
          detail: { valueAnimation: true, fontSize: 26, fontWeight: 700, offsetCenter: [0, '38%'], formatter: '{value}', color: tc('#1f2328', '#e6edf3') },
          title: { offsetCenter: [0, '72%'], fontSize: 12, color: tc('#59636e', '#9198a1') },
          data: [{ value: st?.avgRisk ?? 0, name: '平均威胁分' }],
        }],
      })
    }
    if (donutEl.value) {
      if (!chartDonut.value || chartDonut.value.isDisposed()) chartDonut.value = echarts.init(donutEl.value, 'socp')
      const lv = st?.byRiskLevel ?? {}
      chartDonut.value.setOption({
        tooltip: { trigger: 'item' },
        legend: { bottom: 0, itemWidth: 8, itemHeight: 8, textStyle: { fontSize: 11 } },
        series: [{
          type: 'pie', radius: ['48%', '72%'], center: ['50%', '44%'], avoidLabelOverlap: true,
          itemStyle: { borderRadius: 4, borderColor: 'transparent', borderWidth: 0 },
          label: { show: false }, labelLine: { show: false },
          data: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'].map(k => ({
            name: k, value: lv[k] ?? 0, itemStyle: { color: sevColor(k) },
          })).filter(d => d.value > 0),
        }],
      })
    }
    if (epsEl.value) {
      if (!chartEps.value || chartEps.value.isDisposed()) chartEps.value = echarts.init(epsEl.value, 'socp')
      chartEps.value.setOption({
        grid: { left: 34, right: 10, top: 18, bottom: 20 }, tooltip: { trigger: 'axis' },
        xAxis: { type: 'category', show: false, data: epsHistory.value.map((_, i) => i) },
        yAxis: { type: 'value', axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { type: 'dashed' } } },
        series: [{
          type: 'line', smooth: true, showSymbol: false, data: epsHistory.value,
          lineStyle: { color: '#67c23a', width: 2 },
          areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(103,194,58,.35)' }, { offset: 1, color: 'rgba(103,194,58,.02)' }]) },
        }],
      })
    }
  }, 80)
}

function toggleLive() {
  liveOn.value = !liveOn.value
  if (liveOn.value) startLive(); else stopLive()
}
function startLive() {
  stopLive()
  liveTimer = window.setInterval(() => { if (activeMenu.value === 'situation') loadSituation() }, 4000)
}
function stopLive() { if (liveTimer) { clearInterval(liveTimer); liveTimer = undefined } }

// ---------- UEBA 风险看板 ----------
const riskEntities = ref<RiskEntity[]>([])
const riskSummary = ref<RiskSummary | null>(null)
const riskLimit = ref(20)
const entityDrawer = ref(false)
const entityDetail = ref<RiskEntity | null>(null)
const watchlists = ref<Watchlist[]>([])
const wlAppend = ref<Record<string, string>>({})
const newWl = ref({ name: '', values: '' })
const uebaTab = ref('entities')
const scoreForm = ref({ severity: 'HIGH', mitre: 'T1110', tiHits: 1, recentAlerts: 3, assetCriticality: 2 })
const scoreResult = ref<ScoreBreakdown | null>(null)
const riskBarEl = ref<HTMLElement>(); const chartRiskBar = shallowRef<echarts.ECharts>()

const BREAKDOWN_LABEL: Record<string, string> = {
  base: '严重级别基线', tactic: 'ATT&CK 战术权重', intel: '情报命中加成',
  frequency: '实体频次加成', asset: '资产重要性加成',
}

async function loadUeba() {
  const [e, s, w] = await Promise.allSettled([uebaEntities(riskLimit.value), uebaSummary(), listWatchlists()])
  riskEntities.value = e.status === 'fulfilled' ? e.value : []
  riskSummary.value = s.status === 'fulfilled' ? s.value : null
  watchlists.value = w.status === 'fulfilled' ? w.value : []
  renderRiskBar()
  if (!scoreResult.value) await calcScore()
}
function renderRiskBar() {
  setTimeout(() => {
    if (!riskBarEl.value) return
    if (!chartRiskBar.value || chartRiskBar.value.isDisposed()) chartRiskBar.value = echarts.init(riskBarEl.value, 'socp')
    const top = riskEntities.value.slice(0, 10).slice().reverse()
    chartRiskBar.value.setOption({
      grid: { left: 4, right: 40, top: 10, bottom: 10, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: { type: 'value', max: 100, axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { type: 'dashed' } } },
      yAxis: { type: 'category', data: top.map(t => t.entity), axisLabel: { fontSize: 11 } },
      series: [{
        type: 'bar', barWidth: 14, data: top.map(t => ({ value: t.risk, itemStyle: { color: sevColor(t.level), borderRadius: [0, 7, 7, 0] } })),
        label: { show: true, position: 'right', fontSize: 11, formatter: '{c}' },
      }],
    })
  }, 80)
}
async function openEntity(e: RiskEntity) {
  entityDetail.value = e; entityDrawer.value = true
  try { entityDetail.value = await uebaEntity(e.entity) } catch { /* 用列表里的快照兜底 */ }
}
async function calcScore() {
  try { scoreResult.value = await uebaScore(scoreForm.value) } catch { scoreResult.value = null }
}
async function doAppendWl(name: string) {
  const raw = (wlAppend.value[name] || '').trim()
  if (!raw) return
  await appendWatchlist(name, raw.split(/[\n,，\s]+/).filter(Boolean))
  wlAppend.value[name] = ''
  watchlists.value = await listWatchlists()
}
async function doCreateWl() {
  const n = newWl.value.name.trim()
  if (!n) return
  await putWatchlist(n, newWl.value.values.split(/[\n,，\s]+/).filter(Boolean))
  newWl.value = { name: '', values: '' }
  watchlists.value = await listWatchlists()
}
async function doDeleteWl(name: string) {
  await deleteWatchlist(name)
  watchlists.value = await listWatchlists()
}
function riskColor(level: string) { return sevColor(level) }

// ---------- 接入任务（ingest 页第 4 个子标签） ----------
const tasks = ref<IngestTask[]>([])
const taskSummary = ref<IngestSummary | null>(null)
const taskBusy = ref<Record<string, boolean>>({})
const testDialog = ref(false)
const testTarget = ref<IngestTask | null>(null)
const testSample = ref('')
const testResult = ref<Record<string, unknown> | null>(null)
const testLoading = ref(false)

const HEALTH_META: Record<string, { text: string; type: string }> = {
  HEALTHY: { text: '正常', type: 'success' },
  DEGRADED: { text: '降级', type: 'warning' },
  STALE: { text: '静默', type: 'warning' },
  IDLE: { text: '待接入', type: 'info' },
  ERROR: { text: '异常', type: 'danger' },
  DISABLED: { text: '已停用', type: 'info' },
}
function healthMeta(h: string) { return HEALTH_META[h] ?? { text: h, type: 'info' } }
function fmtBytes(n: number) {
  if (!n) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB']; let i = 0; let v = n
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${Math.round(v * 10) / 10} ${u[i]}`
}
function fmtTime(s: string | null) { return s ? new Date(s).toLocaleString('zh-CN', { hour12: false }) : '—' }

async function loadTasks() {
  const [t, s] = await Promise.allSettled([listIngestTasks(), ingestSummary()])
  tasks.value = t.status === 'fulfilled' ? t.value : []
  taskSummary.value = s.status === 'fulfilled' ? s.value : null
}
async function toggleTask(t: IngestTask) {
  taskBusy.value = { ...taskBusy.value, [t.id]: true }
  try { t.enabled ? await stopIngestTask(t.id) : await startIngestTask(t.id); await loadTasks() } finally {
    taskBusy.value = { ...taskBusy.value, [t.id]: false }
  }
}
function openTest(t: IngestTask) {
  testTarget.value = t; testSample.value = ''; testResult.value = null; testDialog.value = true
}
async function runTest() {
  if (!testTarget.value) return
  testLoading.value = true
  try {
    testResult.value = await testIngestTask(testTarget.value.id, testSample.value.trim() || undefined) as unknown as Record<string, unknown>
    await loadTasks()
  } catch (err) {
    testResult.value = { ok: false, error: String(err) }
  } finally { testLoading.value = false }
}

// ---------- 系统健康看板 ----------
const healthList = ref<Array<{ name: string; path: string; status: string }>>([])
const healthEngine = ref<GasStats | null>(null)
const healthIngest = ref<IngestSummary | null>(null)
let healthTimer: number | undefined
async function loadHealth() {
  const [h, e, i] = await Promise.allSettled([
    Promise.all(HEALTH_TARGETS.map(async t => ({ ...t, status: await checkHealth(t.path) }))),
    gasEngineStats(), ingestSummary(),
  ])
  if (h.status === 'fulfilled') healthList.value = h.value
  else healthList.value = HEALTH_TARGETS.map(t => ({ ...t, status: 'down' }))
  if (e.status === 'fulfilled') healthEngine.value = e.value
  if (i.status === 'fulfilled') healthIngest.value = i.value
}
const healthUpCount = computed(() => healthList.value.filter(x => x.status === 'up').length)

// ---------- 生命周期 ----------
function onMenuChange(key: string) {
  activeMenu.value = key
  switch (key) {
    case 'overview': refreshOverview(); break
    case 'alarms': loadAlarmPage(); break
    case 'situation': loadSituation(); if (liveOn.value) startLive(); break
    case 'search': doSearch(); break
    case 'ingest': loadSources(); loadOutputs(); loadParseRules(); loadTasks(); break
    case 'meta': loadMeta(); break
    case 'detect': loadRules(); break
    case 'ueba': loadUeba(); break
    case 'soar': loadPlaybooks(); break
    case 'report': loadReport(); loadArchive(); break
    case 'assets': loadAssets(); break
    case 'endpoints': loadEndpoints(); break
    case 'threat-intel': loadTi(); break
    case 'attack': loadAttack(); break
    case 'notify': loadNotify(); break
    case 'case': loadCases(); break
    case 'refset': loadRefSets(); break
    case 'compliance': loadCompliance(); break
    case 'health': loadHealth(); break
  }
}

onMounted(() => {
  initTheme()
  try {
    currentUser.value = localStorage.getItem('socp_user') || ''
    currentRole.value = localStorage.getItem('socp_role') || ''
    isAuthed.value = !!(localStorage.getItem('socp_token') && currentUser.value)
  } catch { currentUser.value = ''; currentRole.value = ''; isAuthed.value = false }
  // 有登录态才拉数据；无登录态由 LoginView 接管（登录成功后回调再拉）
  if (!isAuthed.value) return
  refreshOverview()
  refreshTimer = window.setInterval(refreshOverview, 10_000)
  window.addEventListener('resize', onWinResize)
  openAlertStream()
})
onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  if (healthTimer) clearInterval(healthTimer)
  stopLive()
  closeAlertStream()
  for (const c of [chartBar, chartLine, chartGauge, chartDonut, chartEps, chartRiskBar]) {
    if (c.value && !c.value.isDisposed()) c.value.dispose()
  }
  window.removeEventListener('resize', onWinResize)
})

/** 图表随窗口自适应：切页时实例可能未挂载，逐个判活再 resize */
function onWinResize() {
  for (const c of [chartBar, chartLine, chartGauge, chartDonut, chartSitTrend, chartEps, chartRiskBar, chartOvTrend]) {
    if (c.value && !c.value.isDisposed()) c.value.resize()
  }
}

// ---------- 威胁情报 (threat-web) ----------
const iocs = ref<Ioc[]>([])
const tiStat = ref<{ total?: number; byType?: Record<string, number> }>({})
const iocType = ref('')
const newIoc = ref({ type: 'ip', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' })
const tiMatchValue = ref(''); const tiMatchResult = ref<{ value: string; matched: boolean; ioc?: Ioc } | null>(null)
async function loadTi() { iocs.value = await listIocs(iocType.value || undefined); try { tiStat.value = await tiStats() } catch { tiStat.value = {} } }
async function addIoc() {
  if (!newIoc.value.value.trim()) return
  await createIoc({ type: newIoc.value.type, value: newIoc.value.value.trim(), severity: newIoc.value.severity, source: newIoc.value.source, description: newIoc.value.description || undefined, tags: newIoc.value.tags ? newIoc.value.tags.split(/[,，\s]+/).filter(Boolean) : [] })
  newIoc.value = { type: 'ip', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' }; await loadTi()
}
async function removeIoc(id: string) { await deleteIoc(id); await loadTi() }
async function doTiMatch() { if (!tiMatchValue.value.trim()) return; try { tiMatchResult.value = await tiMatch(tiMatchValue.value.trim()) } catch { tiMatchResult.value = { value: tiMatchValue.value, matched: false } } }

// ---------- MITRE ATT&CK (attack-web) ----------
type AttackCov = Awaited<ReturnType<typeof attackCoverage>>
const tactics = ref<Array<{ id: string; name: string }>>([])
const techniques = ref<Technique[]>([])
const attackTech = ref('')
const attackCov = ref<AttackCov | null>(null)
const attackLoading = ref(false)
async function loadAttack() {
  tactics.value = await listTactics() as Array<{ id: string; name: string }>
  techniques.value = await listTechniques(attackTech.value || undefined)
  await computeAttackCov()
}
async function computeAttackCov() {
  attackLoading.value = true
  try {
    const rules = await listRules() as Array<Record<string, unknown>>
    const techs = rules.map(r => String(r.mitre ?? '')).filter(x => x.length > 0)
    attackCov.value = await attackCoverage(techs)
  } catch { attackCov.value = null } finally { attackLoading.value = false }
}

// ATT&CK 矩阵热力图：按战术分列，单元按技术；红=有告警命中 / 绿=已覆盖 / 灰=未覆盖
const mitreCounts = computed<Record<string, number>>(() => {
  const m: Record<string, number> = {}
  for (const a of alarms.value) if (a.mitre) m[a.mitre] = (m[a.mitre] || 0) + 1
  return m
})
const uncoveredSet = computed(() => new Set((attackCov.value?.uncovered) ?? []))
const attackMatrix = computed(() => {
  const byTac: Record<string, Array<Technique & { covered: boolean; count: number }>> = {}
  for (const t of techniques.value) {
    const key = (t.tactic || '').toString()
    ;(byTac[key] ||= []).push({ ...t, covered: !uncoveredSet.value.has(t.id), count: mitreCounts.value[t.id] || 0 })
  }
  return tactics.value.map(tac => {
    const techs = byTac[tac.id] || byTac[tac.name] || []
    const cov = attackCov.value?.byTactic?.find(b => b.tactic === tac.id || b.tactic === tac.name)
    return { tac, techs, total: techs.length, covered: techs.filter(t => t.covered).length, covPct: cov ? cov.coverage : 0 }
  })
})
function techStyle(t: { covered: boolean; count: number }) {
  if (t.count > 0) return 'background:#f56c6c;color:#fff;border-color:#f56c6c'
  if (t.covered) return 'background:var(--ns-success);color:#fff;border-color:transparent'
  return 'background:var(--ns-bg-inset);color:var(--ns-text-3);border-color:var(--ns-border)'
}

// ---------- 通知集成 (notify-web) ----------
const channels = ref<Channel[]>([])
const dispatchLogList = ref<Array<Record<string, unknown>>>([])
const newChannel = ref({ name: '', type: 'SLACK', target: '', enabled: true, description: '' })
async function loadNotify() { channels.value = await listChannels(); try { dispatchLogList.value = await dispatchLog() as Array<Record<string, unknown>> } catch { dispatchLogList.value = [] } }
async function addChannel() { if (!newChannel.value.name.trim() || !newChannel.value.target.trim()) return; await createChannel({ name: newChannel.value.name.trim(), type: newChannel.value.type, target: newChannel.value.target.trim(), enabled: newChannel.value.enabled, description: newChannel.value.description || undefined }); newChannel.value = { name: '', type: 'SLACK', target: '', enabled: true, description: '' }; await loadNotify() }
async function removeChannel(id: string) { await deleteChannel(id); await loadNotify() }
async function doToggleChannel(id: string) { await toggleChannel(id); await loadNotify() }

// ---------- 案件管理 (incident-web) ----------
const cases = ref<CaseInfo[]>([])
const caseStat = ref<{ total?: number; open?: number; resolved?: number }>({})
const caseDetail = ref<CaseInfo | null>(null)
const caseTimelineData = ref<TimelineEvent[]>([])
const caseDrawer = ref(false)
const newCaseStatus = ref('')
async function loadCases() { cases.value = await listCases(); try { caseStat.value = await caseStats() } catch { caseStat.value = {} } }
async function openCase(c: CaseInfo) { caseDetail.value = c; caseDrawer.value = true; newCaseStatus.value = c.status; try { caseTimelineData.value = (await caseTimeline(c.id)).timeline } catch { caseTimelineData.value = [] } }
async function doSetCaseStatus() { if (!caseDetail.value || !newCaseStatus.value) return; const r = await setCaseStatus(caseDetail.value.id, newCaseStatus.value); caseDetail.value = (r as { case: CaseInfo }).case; await loadCases() }

// ---------- 参考数据集 (search-config) ----------
const refSets = ref<ReferenceSet[]>([])
const newRefSet = ref({ name: '', description: '', entries: '' })
const refEntryText = ref<Record<string, string>>({})
async function loadRefSets() { refSets.value = await listRefSets() }
async function addRefSet() { if (!newRefSet.value.name.trim()) return; const entries = newRefSet.value.entries.split(/[\n,，\s]+/).filter(Boolean); await createRefSet({ name: newRefSet.value.name.trim(), description: newRefSet.value.description || undefined, entries }); newRefSet.value = { name: '', description: '', entries: '' }; await loadRefSets() }
async function removeRefSet(id: string) { await deleteRefSet(id); await loadRefSets() }
async function doAddRefEntry(id: string) { const v = (refEntryText.value[id] || '').trim(); if (!v) return; await addRefEntry(id, v); refEntryText.value[id] = ''; await loadRefSets() }

// ---------- 合规 (soc-base) ----------
type ComplianceCov = Awaited<ReturnType<typeof complianceCoverage>>
const frameworks = ref<Array<{ name: string; controls: Array<{ id: string; name: string; ruleIds: string[] }> }>>([])
const complianceCov = ref<ComplianceCov | null>(null)
const complianceLoading = ref(false)
async function loadCompliance() { const r = await complianceFrameworks(); frameworks.value = r.frameworks; await computeCompliance() }
async function computeCompliance() {
  complianceLoading.value = true
  try { const rules = await listRules() as Array<Record<string, unknown>>; const ids = rules.map(r => String(r.id ?? '')).filter(Boolean); complianceCov.value = await complianceCoverage(ids) } catch { complianceCov.value = null } finally { complianceLoading.value = false }
}

// ---------- 告警详情联动（THREAT / ATT&CK / 案件） ----------
const relatedCase = ref<CaseInfo | null>(null)
async function findRelatedCase(alarmId: string) { relatedCase.value = null; try { const all = await listCases(); relatedCase.value = all.find(c => c.alarmIds.includes(alarmId)) ?? null } catch { relatedCase.value = null } }
function tiHitsList(): Ioc[] { try { return currentAlarm.value?.tiHits ? (JSON.parse(currentAlarm.value.tiHits) as Ioc[]) : [] } catch { return [] } }
function openUrl(u: string) { if (u) window.open(u, '_blank') }

// 严重级别颜色
function sevColor(s: string) { return { CRITICAL: '#f56c6c', HIGH: '#e63946', MEDIUM: '#e6a23c', LOW: '#909399', INFO: '#909399' }[s] ?? '#909399' }
/** 相对时间：xx 分钟前 / x 小时前 / x 天前 */
function relTime(iso?: string): string {
  if (!iso) return '—'
  const t = Date.parse(iso)
  if (isNaN(t)) return iso
  const diff = Math.max(0, Date.now() - t)
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d} 天前`
  return iso.slice(0, 10)
}
</script>

<template>
  <LoginView v-if="!isAuthed" @done="onLoginDone" />
  <div v-else class="socp-shell">
    <!-- 侧边栏 -->
    <aside class="socp-sider">
      <div class="socp-logo"><span class="dot" />SOCP 控制台</div>
      <nav class="socp-menu">
        <template v-for="g in MENU_VIEW" :key="g.group">
          <div class="socp-menu-group">{{ g.group }}</div>
          <div v-for="m in g.items" :key="m.key"
            :class="['socp-menu-item', { active: activeMenu === m.key }]"
            @click="onMenuChange(m.key)">
            <span class="icon" v-html="'<svg viewBox=\'0 0 24 24\' fill=\'none\' stroke=\'currentColor\' stroke-width=\'1.6\' stroke-linecap=\'round\' stroke-linejoin=\'round\'>' + (MENU_ICONS[m.icon] || '') + '</svg>'"></span><span>{{ m.label }}</span>
          </div>
        </template>
      </nav>
    </aside>

    <!-- 主区 -->
    <div class="socp-main">
      <header class="socp-header">
        <span class="grad-text">安全运营中心</span>
        <span class="header-sub">Security Operations Center</span>
        <span style="flex:1"></span>
        <el-button size="small" @click="toggleTheme" title="切换深色/浅色模式">
          <span class="icon" style="display:inline-flex;vertical-align:-3px" v-html="'<svg viewBox=\'0 0 24 24\' width=\'14\' height=\'14\' fill=\'none\' stroke=\'currentColor\' stroke-width=\'1.7\' stroke-linecap=\'round\' stroke-linejoin=\'round\'>' + (theme === 'light' ? '<path d=\'M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8Z\'/>' : '<circle cx=\'12\' cy=\'12\' r=\'4\'/><path d=\'M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4\'/>') + '</svg>'" />
          {{ theme === 'light' ? '深色' : '浅色' }}
        </el-button>
        <span v-if="currentUser" style="display:flex;align-items:center;gap:10px">
          <span style="font-size:13px;color:var(--ns-text-2)">{{ currentUser }} <span class="mono" style="font-size:11px;color:var(--ns-accent-fg)">{{ currentRole || 'guest' }}</span></span>
          <el-button size="small" @click="doLogout">退出</el-button>
        </span>
        <el-button v-else size="small" type="primary" @click="openLoginDialog">登录</el-button>
      </header>
      <main class="socp-content">
        <!-- 概览 -->
        <div v-if="activeMenu === 'overview'" class="page-pad view-enter">
          <!-- 主数字 Hero：总览焦点 -->
          <div class="ov-hero">
            <div class="ov-hero-main">
              <div class="ov-hero-num"><AnimatedNumber :value="stat.total" /></div>
              <div class="ov-hero-label">告警总数</div>
              <div class="ov-hero-sub" v-if="sitStats">较昨日趋势
                <span>{{ Object.keys(sitStats?.trend7d ?? {}).length }} 天趋势可用</span>
              </div>
            </div>
            <div class="ov-hero-side">
              <div class="ov-side-item">
                <div class="ov-side-num" style="color:#f85149"><AnimatedNumber :value="stat.critical + stat.high" /></div>
                <div class="ov-side-label">高危告警（CRITICAL+HIGH）</div>
              </div>
              <div class="ov-side-item">
                <div class="ov-side-num" style="color:#30d158">{{ stat.online }}/11</div>
                <div class="ov-side-label">服务在线</div>
              </div>
            </div>
          </div>

          <!-- severity 色带分布 -->
          <el-card shadow="never" style="margin-top:14px" v-if="sitStats">
            <template #header>告警级别分布</template>
            <div class="sev-band">
              <div v-for="s in ['CRITICAL','HIGH','MEDIUM','LOW']" :key="s"
                class="sev-seg" :style="{ flex: (sitStats.bySeverity[s] ?? 0) + 0.01, background: sevColor(s) }"
                :title="`${s}: ${sitStats.bySeverity[s] ?? 0}`" />
            </div>
            <div class="sev-legend">
              <span v-for="s in ['CRITICAL','HIGH','MEDIUM','LOW']" :key="s" class="sev-legend-item">
                <i class="sev-legend-dot" :style="{ background: sevColor(s) }" />{{ s }}
                <b class="mono">{{ sitStats.bySeverity[s] ?? 0 }}</b>
              </span>
            </div>
          </el-card>

          <el-row :gutter="14" style="margin-top:14px">
            <!-- 7 日趋势 -->
            <el-col :span="16">
              <el-card shadow="never" style="height:100%">
                <template #header>近 7 日告警趋势</template>
                <TrendChart :data="sitStats?.trend7d" style="height:210px" />
              </el-card>
            </el-col>
            <!-- 风险 Top -->
            <el-col :span="8">
              <el-card shadow="never" style="height:100%">
                <template #header>最需处置</template>
                <div v-if="(sitStats?.topRisk ?? []).length" class="ov-risk">
                  <div v-for="r in (sitStats?.topRisk ?? []).slice(0, 5)" :key="r.id" class="ov-risk-item">
                    <span class="feed-dot" :style="{ background: sevColor(r.severity) }" />
                    <div class="ov-risk-body">
                      <div class="ov-risk-name">{{ r.ruleName }}</div>
                      <div class="ov-risk-entity mono">{{ r.entity }}</div>
                    </div>
                    <span class="ov-risk-score mono">{{ r.riskScore ?? '—' }}</span>
                  </div>
                </div>
                <div v-else class="feed-empty">暂无风险告警</div>
              </el-card>
            </el-col>
          </el-row>

          <el-row :gutter="14" style="margin-top:14px">
            <el-col :span="16">
              <el-card shadow="never">
                <template #header>最新告警</template>
                <el-table :data="filteredAlarms.slice(0, 5)" size="small">
                  <el-table-column label="级别" width="100"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
                  <el-table-column prop="ruleName" label="规则" min-width="150" />
                  <el-table-column prop="entity" label="实体" width="120" />
                  <el-table-column prop="message" label="消息" min-width="200" show-overflow-tooltip />
                </el-table>
              </el-card>
            </el-col>
            <el-col :span="8">
              <el-card shadow="never">
                <template #header>后端服务健康</template>
                <div class="ov-health">
                  <div v-for="h in HEALTH_TARGETS" :key="h.name" class="ov-health-item">
                    <span class="ov-health-dot" :class="healths[h.name] === 'up' ? 'up' : 'down'" />
                    <span class="ov-health-name">{{ h.name }}</span>
                    <span class="ov-health-state" :class="healths[h.name] === 'up' ? 'up' : 'down'">{{ healths[h.name] === 'up' ? 'UP' : 'DOWN' }}</span>
                  </div>
                </div>
              </el-card>
            </el-col>
          </el-row>
        </div>

        <!-- 实时态势大屏 -->
        <div v-else-if="activeMenu === 'situation'" class="page-pad view-enter sit-wrap">
          <!-- KPI 条 -->
          <div class="sit-kpis">
            <div class="sit-kpi">
              <div class="k-num">{{ sitEngine?.eventCount ?? 0 }}</div><div class="k-label">引擎处理事件</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" style="color:#f56c6c">{{ sitEngine?.alertCount ?? 0 }}</div><div class="k-label">规则命中告警</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" style="color:#e6a23c">{{ sitEngine?.suppressedCount ?? 0 }}</div><div class="k-label">抑制去重</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" :style="{ color: (sitEngine?.dropCount ?? 0) > 0 ? '#f56c6c' : '#67c23a' }">{{ sitEngine?.dropCount ?? 0 }}</div>
              <div class="k-label">背压丢弃</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" style="color:#409eff">{{ sitIngest?.eps1m ?? 0 }}</div><div class="k-label">接入 EPS(1m)</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num">{{ queuePct }}%</div>
              <div class="k-label">队列水位</div>
              <el-progress :percentage="Math.min(100, queuePct)" :show-text="false" :stroke-width="4"
                :color="queuePct > 70 ? '#f56c6c' : queuePct > 30 ? '#e6a23c' : '#67c23a'" style="margin-top:4px" />
            </div>
          </div>

          <el-row :gutter="12" style="margin-bottom:12px">
            <el-col :span="6">
              <el-card shadow="never" class="sit-card">
                <template #header>威胁评分（0–100）</template>
                <div ref="gaugeEl" style="height:180px"></div>
                <div style="text-align:center;font-size:12px;color:#909399">
                  告警总量 <b style="color:#303133">{{ sitStats?.total ?? 0 }}</b>
                  · 高危 <b style="color:#f56c6c">{{ (sitStats?.byRiskLevel?.CRITICAL ?? 0) + (sitStats?.byRiskLevel?.HIGH ?? 0) }}</b>
                </div>
              </el-card>
            </el-col>
            <el-col :span="6">
              <el-card shadow="never" class="sit-card">
                <template #header>风险档位分布</template>
                <div ref="donutEl" style="height:210px"></div>
              </el-card>
            </el-col>
            <el-col :span="6">
              <el-card shadow="never" class="sit-card">
                <template #header>近 7 日告警趋势</template>
                <TrendChart :data="sitStats?.trend7d" variant="situation" style="height:210px" />
              </el-card>
            </el-col>
            <el-col :span="6">
              <el-card shadow="never" class="sit-card">
                <template #header>接入吞吐（EPS 采样）</template>
                <div ref="epsEl" style="height:210px"></div>
              </el-card>
            </el-col>
          </el-row>

          <el-row :gutter="12">
            <el-col :span="13">
              <el-card shadow="never" class="sit-card">
                <template #header>
                  <div style="display:flex;align-items:center;gap:10px">
                    <span class="live-dot" :class="{ off: !liveOn }" />
                    <span>实时事件流</span>
                    <el-select v-model="liveSevFilter" placeholder="全部级别" clearable size="small" style="width:120px">
                      <el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" />
                    </el-select>
                    <el-button size="small" @click="toggleLive">{{ liveOn ? '暂停' : '继续' }}</el-button>
                    <el-button size="small" @click="loadSituation">刷新</el-button>
                    <span style="margin-left:auto;font-size:12px;color:#909399">{{ feedView.length }} 条</span>
                  </div>
                </template>
                <div class="feed">
                  <div v-if="!feedView.length" class="feed-empty">暂无实时告警 —— 可在「日志接入 · 接入任务」里点自测灌一条样例日志</div>
                  <div v-for="a in feedView" :key="a.id" class="feed-item" :class="{ fresh: a._new }">
                    <span class="feed-dot" :style="{ background: sevColor(a.severity) }" />
                    <div class="feed-body">
                      <div class="feed-top">
                        <SevBadge :value="a.severity" />
                        <span class="feed-rule">{{ a.ruleName }}</span>
                        <span class="feed-entity mono">{{ a.entity }}</span>
                        <span class="feed-time mono">{{ new Date(a.timestamp).toLocaleTimeString('zh-CN', { hour12: false }) }}</span>
                      </div>
                      <div class="feed-msg">{{ a.message }}</div>
                    </div>
                  </div>
                </div>
              </el-card>
            </el-col>
            <el-col :span="11">
              <el-card shadow="never" class="sit-card">
                <template #header>最该处置的告警（按威胁评分）</template>
                <el-table :data="sitStats?.topRisk ?? []" size="small" height="368">
                  <el-table-column label="评分" width="86">
                    <template #default="{ row }">
                      <span class="risk-pill" :style="{ background: sevColor(row.riskLevel) }">{{ row.riskScore }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="ruleName" label="规则" min-width="150" show-overflow-tooltip />
                  <el-table-column prop="entity" label="实体" width="130" show-overflow-tooltip />
                  <el-table-column label="ATT&CK" width="92">
                    <template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.mitre || '—' }}</span></template>
                  </el-table-column>
                  <el-table-column label="级别" width="94">
                    <template #default="{ row }"><SevBadge :value="row.severity" /></template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>
          </el-row>
        </div>

        <!-- 告警查询 -->
        <div v-else-if="activeMenu === 'alarms'" class="page-pad view-enter">
          <div style="display:flex;gap:10px;margin-bottom:12px">
            <el-input v-model="alarmKeyword" placeholder="搜索规则/实体/消息" clearable style="width:280px" @keyup.enter="onAlarmSearch" @clear="onAlarmSearch" />
            <el-select v-model="alarmSeverity" placeholder="全部级别" clearable style="width:140px" @change="onAlarmSearch">
              <el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" />
            </el-select>
            <el-button size="small" @click="onAlarmSearch">查询</el-button>
            <span style="color:#909399;line-height:32px">共 {{ alarmPageData.total }} 条</span>
            <span style="flex:1"></span>
            <el-button size="small" @click="exportAlarms('csv')">导出 CSV</el-button>
            <el-button size="small" @click="exportAlarms('json')">导出 JSON</el-button>
          </div>
          <!-- 告警微卡片列表（行=卡片：状态点 + 规则 + 实体 + 状态 + 相对时间 + 操作） -->
          <div class="card-list">
            <div v-for="a in filteredAlarms" :key="a.id" class="alarm-card" @click="openAlarm(a)">
              <span class="alarm-sev-dot" :style="{ background: sevColor(a.severity) }" />
              <div class="alarm-body">
                <div class="alarm-top">
                  <span class="alarm-sev-tag" :style="{ background: sevColor(a.severity) }">{{ a.severity }}</span>
                  <span class="alarm-rule">{{ a.ruleName || a.ruleId }}</span>
                  <span class="alarm-entity mono">{{ a.entity }}</span>
                  <span class="alarm-status" :class="(a.status || 'OPEN').toLowerCase()">{{ a.status || 'OPEN' }}</span>
                  <span class="alarm-time">{{ relTime(a.occurredAt) }}</span>
                </div>
                <div class="alarm-msg">{{ a.message }}</div>
              </div>
              <el-button link type="primary" size="small" @click.stop="openAlarm(a)">处置</el-button>
            </div>
            <div v-if="!filteredAlarms.length" class="feed-empty">暂无告警</div>
          </div>
          <div style="display:flex;justify-content:flex-end;margin-top:14px">
            <el-pagination
              v-model:current-page="alarmPageNum"
              v-model:page-size="alarmPageSize"
              :total="alarmPageData.total"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @current-change="loadAlarmPage"
              @size-change="() => { alarmPageNum = 1; loadAlarmPage() }" />
          </div>

          <!-- 告警详情/处置抽屉 -->
          <el-drawer v-model="drawerVisible" :title="`告警处置 · ${currentAlarm?.ruleName ?? ''}`" size="480px">
            <template v-if="currentAlarm">
              <el-descriptions :column="2" size="small" border style="margin-bottom:14px">
                <el-descriptions-item label="规则 ID">{{ currentAlarm.ruleId }}</el-descriptions-item>
                <el-descriptions-item label="级别"><SevBadge :value="currentAlarm.severity" /></el-descriptions-item>
                <el-descriptions-item label="实体">{{ currentAlarm.entity }}</el-descriptions-item>
                <el-descriptions-item label="发生时间">{{ currentAlarm.occurredAt }}</el-descriptions-item>
                <el-descriptions-item label="消息" :span="2">{{ currentAlarm.message }}</el-descriptions-item>
                <el-descriptions-item label="ATT&CK" :span="2">
                  <a v-if="currentAlarm.mitre" :href="`https://attack.mitre.org/techniques/${String(currentAlarm.mitre).replace('-', '/')}/`" target="_blank" style="color:#409eff;font-weight:600">{{ currentAlarm.mitre }}</a>
                  <span v-else style="color:#909399">—</span>
                </el-descriptions-item>
                <el-descriptions-item label="威胁情报命中" :span="2">
                  <span v-if="tiHitsList().length">
                    <el-tag v-for="(h, i) in tiHitsList()" :key="i" size="small" type="danger" style="margin-right:6px;margin-bottom:4px">{{ h.type }} · {{ h.value }}</el-tag>
                  </span>
                  <span v-else style="color:#909399">—</span>
                </el-descriptions-item>
              </el-descriptions>

              <el-divider content-position="left">状态流转</el-divider>
              <div style="display:flex;gap:8px;margin-bottom:8px">
                <el-select v-model="newStatus" style="flex:1">
                  <el-option v-for="s in DISP_STATUSES" :key="s" :label="s" :value="s" />
                </el-select>
                <el-button type="primary" @click="changeStatus">更新</el-button>
              </div>
              <div style="display:flex;gap:8px;margin-bottom:14px">
                <el-input v-model="newAssignee" placeholder="分配人，如 ops-zhang" />
                <el-button @click="doAssign">分配</el-button>
              </div>

              <el-divider content-position="left">备注 / 调查记录</el-divider>
              <div v-if="disposition && disposition.notes.length">
                <div v-for="(n, i) in disposition.notes" :key="i" style="background:var(--ns-bg-subtle);border-radius:6px;padding:8px 12px;margin-bottom:8px">
                  <div style="font-size:12px;color:#909399">{{ n.author }} · {{ n.at }}</div>
                  <div style="margin-top:2px">{{ n.content }}</div>
                </div>
              </div>
              <el-empty v-else description="暂无备注" :image-size="50" />
              <div style="display:flex;gap:8px;margin-top:8px">
                <el-input v-model="newNote" placeholder="添加调查备注…" @keyup.enter="doAddNote" />
                <el-button type="success" @click="doAddNote">添加</el-button>
              </div>

              <el-divider content-position="left">关联案件</el-divider>
              <el-card v-if="relatedCase" shadow="never" style="margin-bottom:10px">
                <div style="display:flex;justify-content:space-between;align-items:center;gap:8px">
                  <div>
                    <div style="font-weight:600">{{ relatedCase.title }}</div>
                    <div style="font-size:12px;color:#909399;margin-top:2px">{{ relatedCase.id }} · {{ relatedCase.status }} · 实体 {{ relatedCase.entity }} · 告警 {{ relatedCase.alarmIds.length }} 条</div>
                  </div>
                  <el-button link type="primary" size="small" @click="drawerVisible = false; onMenuChange('case')">前往案件</el-button>
                </div>
              </el-card>
              <el-empty v-else description="暂无关联案件（告警创建时会自动建案/归并）" :image-size="50" />
            </template>
          </el-drawer>
        </div>

        <!-- 日志检索（SPL） -->
        <div v-else-if="activeMenu === 'search'" class="page-pad view-enter">
          <el-card shadow="never" style="margin-bottom:14px">
            <div style="display:flex;gap:10px;align-items:center">
              <el-input v-model="searchQuery" placeholder='SPL 查询，如 source=auth severity=HIGH | top src_ip 5' clearable @keyup.enter="doSearch" style="flex:1" />
              <el-button type="primary" :loading="searchLoading" @click="doSearch">执行检索</el-button>
              <el-button size="small" @click="exportSearch(searchQuery, 'json')">导出 JSON</el-button>
              <el-button size="small" @click="exportSearch(searchQuery, 'csv')">导出 CSV</el-button>
            </div>
            <div style="margin-top:10px">
              <el-tag v-for="ex in SEARCH_EXAMPLES" :key="ex" size="small" style="margin-right:8px;cursor:pointer" @click="searchQuery = ex; doSearch()">{{ ex }}</el-tag>
            </div>
          </el-card>

          <template v-if="searchResult">
            <el-card shadow="never" style="margin-bottom:14px">
              <template #header>命中 {{ searchResult.total }} 条事件</template>
              <el-table :data="searchResult.events" size="small" max-height="420">
                <el-table-column label="时间" width="150"><template #default="{ row }">{{ row.timestamp.slice(0, 19).replace('T', ' ') }}</template></el-table-column>
                <el-table-column prop="source" label="来源" width="90" />
                <el-table-column prop="host" label="主机" width="90" />
                <el-table-column label="级别" width="80"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
                <el-table-column prop="msg" label="消息" min-width="240" show-overflow-tooltip />
              </el-table>
            </el-card>
            <el-card shadow="never" v-if="searchResult.stat">
              <template #header>{{ searchResult.stat.type === 'timechart' ? '时间分布（按天）' : `统计（${searchResult.stat.type === 'top' ? 'Top' : '分组计数'}）` }}</template>
              <el-table :data="searchResult.stat.rows" size="small">
                <el-table-column prop="key" label="Key" />
                <el-table-column label="条数" width="140">
                  <template #default="{ row }">
                    <div style="display:flex;align-items:center;gap:8px">
                      <span>{{ row.count }}</span>
                      <div style="flex:1;background:var(--ns-bg-inset);border-radius:4px;height:10px;overflow:hidden">
                        <div :style="{ width: `${Math.min(100, (row.count / maxStatCount) * 100)}%`, background: '#409eff', height: '100%' }" />
                      </div>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </template>
        </div>

        <!-- 日志接入（接入任务 / 输入源 / 输出配置 / 解析规则） -->
        <div v-else-if="activeMenu === 'ingest'" class="page-pad view-enter">
          <el-tabs v-model="ingestTab" @tab-change="onIngestTab">
            <!-- 接入任务：配置 + 运行态一屏 -->
            <el-tab-pane label="接入任务" name="tasks">
              <el-row :gutter="12" style="margin-bottom:14px">
                <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ taskSummary?.enabledSources ?? 0 }}/{{ taskSummary?.sources ?? 0 }}</div><div class="label">运行中 / 总任务</div></div></el-card></el-col>
                <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#409eff">{{ taskSummary?.eps1m ?? 0 }}</div><div class="label">总 EPS(1m)</div></div></el-card></el-col>
                <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#67c23a">{{ taskSummary?.accepted ?? 0 }}</div><div class="label">已接收</div></div></el-card></el-col>
                <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ taskSummary?.forwarded ?? 0 }}</div><div class="label">已转发检测</div></div></el-card></el-col>
                <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" :style="{ color: (taskSummary?.skipped ?? 0) > 0 ? '#e6a23c' : '#909399' }">{{ taskSummary?.skipped ?? 0 }}</div><div class="label">解析跳过</div></div></el-card></el-col>
                <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ fmtBytes(taskSummary?.bytes ?? 0) }}</div><div class="label">累计流量</div></div></el-card></el-col>
              </el-row>

              <el-card shadow="never">
                <template #header>
                  <div style="display:flex;align-items:center;gap:10px">
                    <span>接入任务（配置 + 运行指标）</span>
                    <el-tag v-for="(c, h) in (taskSummary?.byHealth ?? {})" :key="h" size="small"
                      :type="healthMeta(String(h)).type" style="margin-left:2px">{{ healthMeta(String(h)).text }} {{ c }}</el-tag>
                    <el-button size="small" style="margin-left:auto" @click="loadTasks">刷新</el-button>
                  </div>
                </template>
                <el-table :data="tasks" size="small">
                  <el-table-column label="状态" width="92">
                    <template #default="{ row }">
                      <el-tag :type="healthMeta(row.runtime.health).type" size="small" effect="dark">{{ healthMeta(row.runtime.health).text }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="任务" min-width="150">
                    <template #default="{ row }">
                      <div style="font-weight:600">{{ row.name }}</div>
                      <div class="mono" style="font-size:11px;color:#909399">{{ row.collector }}</div>
                    </template>
                  </el-table-column>
                  <el-table-column prop="type" label="接入方式" width="110" />
                  <el-table-column prop="format" label="解析格式" width="90" />
                  <el-table-column label="采集目标" min-width="180" show-overflow-tooltip>
                    <template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.target }}</span></template>
                  </el-table-column>
                  <el-table-column label="EPS(1m/5m)" width="110">
                    <template #default="{ row }">
                      <span :style="{ color: row.runtime.eps1m > 0 ? '#67c23a' : '#c0c4cc', fontWeight: 600 }">{{ row.runtime.eps1m }}</span>
                      <span style="color:#c0c4cc"> / {{ row.runtime.eps5m }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="接收 / 转发 / 跳过" width="150">
                    <template #default="{ row }">
                      <span class="mono" style="font-size:12px">{{ row.runtime.accepted }} / {{ row.runtime.forwarded }} /
                        <span :style="{ color: row.runtime.skipped > 0 ? '#e6a23c' : 'inherit' }">{{ row.runtime.skipped }}</span>
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="最近数据" width="150">
                    <template #default="{ row }"><span class="mono" style="font-size:12px">{{ fmtTime(row.runtime.lastAt) }}</span></template>
                  </el-table-column>
                  <el-table-column label="操作" width="170">
                    <template #default="{ row }">
                      <el-button link :type="row.enabled ? 'warning' : 'success'" size="small"
                        :loading="taskBusy[row.id]" @click="toggleTask(row)">{{ row.enabled ? '停止' : '启动' }}</el-button>
                      <el-button link type="primary" size="small" @click="openTest(row)">连通性自测</el-button>
                    </template>
                  </el-table-column>
                  <el-table-column type="expand">
                    <template #default="{ row }">
                      <div style="padding:8px 20px;font-size:12px;color:#606266">
                        <div>环境：{{ row.env || '—' }} · 类别：{{ row.categoryId || '—' }} · 输出：{{ row.sinkTargetId || '默认' }} · 创建：{{ fmtTime(row.createdAt) }}</div>
                        <div style="margin-top:4px">绑定解析规则：
                          <el-tag v-for="p in row.parseRuleIds" :key="p" size="small" style="margin-right:4px">{{ p }}</el-tag>
                          <span v-if="!row.parseRuleIds?.length" style="color:#c0c4cc">自动识别</span>
                        </div>
                        <div v-if="row.runtime.lastError" style="margin-top:4px;color:#f56c6c">
                          最近错误（{{ fmtTime(row.runtime.lastErrorAt ?? null) }}）：{{ row.runtime.lastError }}
                        </div>
                      </div>
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>

              <el-dialog v-model="testDialog" :title="`连通性自测 · ${testTarget?.name ?? ''}`" width="680px">
                <div style="font-size:12px;color:#909399;margin-bottom:8px">
                  留空则按该源类型自动生成样例日志；样例会真实走完 解析 → 富化 → 转发检测 全链路。
                </div>
                <el-input v-model="testSample" type="textarea" :rows="4" placeholder='留空使用默认样例，或粘贴一行原始日志 / 一条 JSON' />
                <div v-if="testResult" style="margin-top:12px">
                  <el-alert :type="testResult.ok ? 'success' : 'error'" :closable="false"
                    :title="testResult.ok ? '管线贯通：样例已被接收并转发' : '未通过：样例未被接收，检查解析规则或输出配置'" />
                  <pre class="mono test-out">{{ JSON.stringify(testResult, null, 2) }}</pre>
                </div>
                <template #footer>
                  <el-button @click="testDialog = false">关闭</el-button>
                  <el-button type="primary" :loading="testLoading" @click="runTest">执行自测</el-button>
                </template>
              </el-dialog>
            </el-tab-pane>

            <!-- 输入源 -->
            <el-tab-pane label="输入源" name="sources">
              <div class="add-bar">
                <el-button type="primary" @click="openSourceDialog">+ 新增日志源</el-button>
                <el-button @click="loadSources">刷新</el-button>
                <el-button type="primary" plain @click="doRender">渲染 vector.toml</el-button>
                <span class="hint">接入方式 + 完整参数；保存后渲染 vector.toml</span>
              </div>
              <el-dialog v-model="showSourceDialog" title="新增日志源" width="640px">
                <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px">
                  <el-input v-model="newSource.name" placeholder="名称，如 fw-syslog" />
                  <el-select v-model="newSource.type" placeholder="接入方式">
                    <el-option v-for="t in SOURCE_TYPES" :key="t" :label="t" :value="t" />
                  </el-select>
                  <el-select v-model="newSource.format" placeholder="解析格式">
                    <el-option v-for="f in PARSE_FORMATS" :key="f" :label="f" :value="f" />
                  </el-select>
                  <el-select v-model="newSource.categoryId" placeholder="日志类别" clearable>
                    <el-option v-for="c in logCategories" :key="c.id" :label="`${c.code} ${c.name}`" :value="c.id" />
                  </el-select>
                  <el-input v-model="newSource.env" placeholder="环境标签" />
                </div>
                <div v-if="newSource.type==='FILE'" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px">
                  <el-input v-model="newSource.path" placeholder="文件路径/glob，如 /var/log/auth.log" />
                  <el-select v-model="newSource.readFrom">
                    <el-option label="beginning 全量回放" value="beginning" /><el-option label="end 只收新增" value="end" />
                  </el-select>
                  <el-input v-model.number="newSource.frequency" placeholder="轮询间隔(秒)" />
                </div>
                <div v-else-if="newSource.type==='SOCKET'||newSource.type==='SYSLOG'" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px">
                  <el-input v-model="newSource.address" placeholder="监听 host:port，如 0.0.0.0:514" />
                  <el-select v-model="newSource.protocol">
                    <el-option label="UDP" value="udp" /><el-option label="TCP" value="tcp" /><el-option label="TLS" value="tls" />
                  </el-select>
                </div>
                <div v-else-if="newSource.type==='KAFKA'" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px">
                  <el-input v-model="newSource.topic" placeholder="主题，如 socp-raw" />
                  <el-input v-model="newSource.groupId" placeholder="消费组，如 search-group" />
                </div>
                <div v-else-if="newSource.type==='WINDOWS_EVENT'||newSource.type==='AGENT'||newSource.type==='HTTP_API'||newSource.type==='DATABASE'||newSource.type==='CLOUD'" style="margin-top:10px">
                  <el-alert type="info" :closable="false" :title="`${newSource.type} 由对应采集器负责（Winlogbeat/Agent/Webhook/DB CDC/云 SDK），采集器输出统一走 NDJSON → SEARCH ingest`" />
                </div>
                <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px">
                  <el-select v-model="newSource.charset" placeholder="字符集">
                    <el-option label="UTF-8" value="utf-8" /><el-option label="GBK" value="gbk" /><el-option label="ISO-8859-1" value="iso-8859-1" />
                  </el-select>
                  <el-select v-model="newSource.timezone" placeholder="时区">
                    <el-option label="Asia/Shanghai" value="Asia/Shanghai" /><el-option label="UTC" value="UTC" /><el-option label="Asia/Tokyo" value="Asia/Tokyo" />
                  </el-select>
                  <el-input v-model="newSource.tags" placeholder="标签（逗号分隔），如 app=nginx,team=infra" />
                </div>
                <template #footer>
                  <el-switch v-model="newSource.enabled" active-text="启用" style="margin-right:12px" />
                  <el-button @click="showSourceDialog = false">取消</el-button>
                  <el-button type="success" @click="addSource(); showSourceDialog = false">新增日志源</el-button>
                </template>
              </el-dialog>
              <el-card shadow="never">
                <el-table :data="sources" size="small">
                  <el-table-column prop="name" label="名称" width="130" />
                  <el-table-column prop="type" label="类型" width="110" />
                  <el-table-column prop="format" label="格式" width="80" />
                  <el-table-column label="目标" min-width="160"><template #default="{ row }">{{ row.path || row.address || row.topic || '-' }}</template></el-table-column>
                  <el-table-column label="协议" width="70"><template #default="{ row }">{{ row.protocol || '-' }}</template></el-table-column>
                  <el-table-column prop="env" label="环境" width="65" />
                  <el-table-column label="启用" width="65"><template #default="{ row }"><el-tag :type="row.enabled?'success':'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column>
                  <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeSource(row.id)">删除</el-button></template></el-table-column>
                </el-table>
              </el-card>
            </el-tab-pane>

            <!-- 输出配置 -->
            <el-tab-pane label="输出配置" name="outputs">
              <div class="add-bar">
                <el-button type="primary" @click="openOutputDialog">+ 新增输出</el-button>
                <span class="hint">渲染时取第一个启用的输出作为 Vector sink 目标；缺省为 SEARCH 自身 ingest</span>
              </div>
              <el-dialog v-model="showOutputDialog" title="新增输出" width="560px">
                <el-form label-width="80px">
                  <el-form-item label="名称"><el-input v-model="newOutput.name" placeholder="名称" /></el-form-item>
                  <el-form-item label="类型">
                    <el-select v-model="newOutput.type" style="width:200px">
                      <el-option label="GLS_INGEST" value="GLS_INGEST" /><el-option label="OPENSEARCH" value="OPENSEARCH" /><el-option label="HTTP" value="HTTP" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="目标 URL"><el-input v-model="newOutput.uri" placeholder="如 http://host:9200/_bulk" /></el-form-item>
                  <el-form-item label="启用"><el-switch v-model="newOutput.enabled" active-text="启用" /></el-form-item>
                </el-form>
                <template #footer><el-button @click="showOutputDialog = false">取消</el-button><el-button type="success" @click="addOutput(); showOutputDialog = false">新增输出</el-button></template>
              </el-dialog>
              <el-card shadow="never">
                <el-table :data="outputs" size="small">
                  <el-table-column prop="name" label="名称" width="180" />
                  <el-table-column prop="type" label="类型" width="130" />
                  <el-table-column prop="uri" label="目标 URL" min-width="280" show-overflow-tooltip />
                  <el-table-column label="启用" width="70"><template #default="{ row }"><el-tag :type="row.enabled?'success':'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column>
                  <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeOutput(row.id)">删除</el-button></template></el-table-column>
                </el-table>
              </el-card>
            </el-tab-pane>

            <!-- 解析规则 -->
            <el-tab-pane label="解析规则" name="rules">
              <div style="margin-bottom:12px">
                <el-button type="primary" @click="showRuleDialog = true">新增解析规则</el-button>
                <el-button @click="loadParseRules">刷新</el-button>
                <span style="color:#909399;font-size:12px;margin-left:8px">定义「一行日志 → 字段」的提取方式，可现场用示例行验证</span>
              </div>
              <el-card shadow="never">
                <el-table :data="parseRules" size="small">
                  <el-table-column prop="name" label="规则名" width="180" />
                  <el-table-column prop="format" label="格式" width="90" />
                  <el-table-column prop="pattern" label="正则/描述" min-width="300" show-overflow-tooltip />
                  <el-table-column label="启用" width="65"><template #default="{ row }"><el-tag :type="row.enabled?'success':'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column>
                  <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeParseRule(row.id)">删除</el-button></template></el-table-column>
                </el-table>
              </el-card>

              <!-- 预览面板 -->
              <el-card shadow="never" style="margin-top:14px">
                <template #header>解析预览（用示例行验证规则）</template>
                <div style="display:flex;gap:10px;margin-bottom:10px">
                  <el-select v-model="previewRuleId" placeholder="选择已有规则（留空用临时正则）" clearable style="width:260px">
                    <el-option v-for="r in parseRules" :key="r.id" :label="r.name" :value="r.id" />
                  </el-select>
                  <el-button type="primary" @click="doPreview">预览</el-button>
                </div>
                <el-input v-model="previewLine" type="textarea" :rows="2" placeholder="示例日志行" />
                <div v-if="previewResult" style="margin-top:12px;background:var(--ns-bg-subtle);border-radius:6px;padding:12px">
                  <p style="margin:0 0 6px">
                    结果：<el-tag :type="previewResult.matched ? 'success' : 'danger'" size="small">{{ previewResult.matched ? '命中' : '未命中' }}</el-tag>
                    <span v-if="previewResult.error" style="color:#f56c6c;margin-left:8px">{{ previewResult.error }}</span>
                  </p>
                  <pre class="mono" style="margin:0;font-size:12px">{{ JSON.stringify(previewResult.fields, null, 2) }}</pre>
                </div>
              </el-card>
            </el-tab-pane>
          </el-tabs>
          <el-dialog v-model="showRender" title="vector.toml" width="720px">
            <el-button size="small" type="primary" @click="copyRender">复制</el-button>
            <pre style="background:var(--ns-bg-subtle);border:1px solid var(--ns-border);border-radius:6px;padding:12px;font-size:12px;overflow:auto;max-height:440px;margin-top:10px">{{ renderText }}</pre>
          </el-dialog>
          <el-dialog v-model="showRuleDialog" title="新增解析规则" width="560px">
            <el-form label-width="90px">
              <el-form-item label="名称"><el-input v-model="newRule.name" placeholder="如：SSHD 认证失败提取" /></el-form-item>
              <el-form-item label="格式">
                <el-select v-model="newRule.format" style="width:200px">
                  <el-option v-for="f in ['REGEX','JSON','KV','SYSLOG','CEF','LEEF']" :key="f" :label="f" :value="f" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="newRule.format === 'REGEX'" label="正则">
                <el-input v-model="newRule.pattern" type="textarea" :rows="3" placeholder='命名分组正则，如：Failed password for (?&lt;user&gt;\S+) from (?&lt;srcip&gt;\d+\.\d+\.\d+\.\d+)&#10;注意：组名不支持下划线（用 srcip 再映射为 src_ip）' />
              </el-form-item>
              <el-form-item label="作用于源"><el-input v-model="newRule.sourceId" placeholder="留空=全局规则" /></el-form-item>
              <el-form-item label="启用"><el-switch v-model="newRule.enabled" /></el-form-item>
            </el-form>
            <template #footer><el-button @click="showRuleDialog = false">取消</el-button><el-button type="primary" @click="addParseRule">保存</el-button></template>
          </el-dialog>
        </div>

        <!-- 元数据（数据源分类 / 日志类别 / 字段字典） -->
        <div v-else-if="activeMenu === 'meta'" class="page-pad view-enter">
          <el-tabs v-model="metaTab" @tab-change="onMetaTab">
            <!-- 数据源分类 -->
            <el-tab-pane label="数据源分类" name="ds">
              <div class="add-bar">
                <el-button type="primary" @click="openDsDialog">+ 新增数据源分类</el-button>
                <span class="hint">接入方式注册表：9 类内置 + 可扩展</span>
              </div>
              <el-dialog v-model="showDsDialog" title="新增数据源分类" width="520px">
                <el-form label-width="80px">
                  <el-form-item label="编码"><el-input v-model="newDsType.code" placeholder="如 SYSLOG" /></el-form-item>
                  <el-form-item label="名称"><el-input v-model="newDsType.name" placeholder="如 Syslog 协议" /></el-form-item>
                  <el-form-item label="说明"><el-input v-model="newDsType.description" placeholder="说明" /></el-form-item>
                  <el-form-item label="启用"><el-switch v-model="newDsType.enabled" /></el-form-item>
                </el-form>
                <template #footer><el-button @click="showDsDialog = false">取消</el-button><el-button type="success" @click="addDsType(); showDsDialog = false">新增分类</el-button></template>
              </el-dialog>
              <el-card shadow="never">
                <template #header>接入方式注册表（9 类内置 + 可扩展）</template>
                <el-table :data="dataSourceTypes" size="small">
                  <el-table-column prop="code" label="编码" width="130" />
                  <el-table-column prop="name" label="名称" width="150" />
                  <el-table-column prop="description" label="说明" min-width="300" show-overflow-tooltip />
                  <el-table-column label="启用" width="65"><template #default="{ row }"><el-tag :type="row.enabled?'success':'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column>
                  <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeDsType(row.id)">删除</el-button></template></el-table-column>
                </el-table>
              </el-card>
            </el-tab-pane>

            <!-- 日志类别 -->
            <el-tab-pane label="日志类别" name="cats">
              <div class="add-bar">
                <el-button type="primary" @click="openCatDialog">+ 新增日志类别</el-button>
                <span class="hint">日志分类体系：对齐 SIEM Taxonomy / MITRE ATT&CK</span>
              </div>
              <el-dialog v-model="showCatDialog" title="新增日志类别" width="520px">
                <el-form label-width="80px">
                  <el-form-item label="编码"><el-input v-model="newCategory.code" placeholder="如 AUTH" /></el-form-item>
                  <el-form-item label="名称"><el-input v-model="newCategory.name" placeholder="名称" /></el-form-item>
                  <el-form-item label="基线级别">
                    <el-select v-model="newCategory.defaultSeverity" style="width:160px">
                      <el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="说明"><el-input v-model="newCategory.description" placeholder="说明" /></el-form-item>
                  <el-form-item label="启用"><el-switch v-model="newCategory.enabled" /></el-form-item>
                </el-form>
                <template #footer><el-button @click="showCatDialog = false">取消</el-button><el-button type="success" @click="addCategory(); showCatDialog = false">新增类别</el-button></template>
              </el-dialog>
              <el-card shadow="never">
                <template #header>日志分类体系（对齐 SIEM Taxonomy / MITRE ATT&CK）</template>
                <el-table :data="logCategories" size="small">
                  <el-table-column prop="code" label="编码" width="120" />
                  <el-table-column prop="name" label="名称" width="130" />
                  <el-table-column prop="description" label="说明" min-width="260" show-overflow-tooltip />
                  <el-table-column label="基线级别" width="100"><template #default="{ row }"><SevBadge :value="row.defaultSeverity" /></template></el-table-column>
                  <el-table-column label="启用" width="65"><template #default="{ row }"><el-tag :type="row.enabled?'success':'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column>
                  <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeCategory(row.id)">删除</el-button></template></el-table-column>
                </el-table>
              </el-card>
            </el-tab-pane>

            <!-- 字段字典 -->
            <el-tab-pane label="字段字典" name="fields">
              <div class="add-bar">
                <el-button type="primary" @click="openFieldDialog">+ 新增字段</el-button>
                <span class="hint">统一字段语义，解析 / 检索 / 告警共用</span>
              </div>
              <el-dialog v-model="showFieldDialog" title="新增字段" width="540px">
                <el-form label-width="80px">
                  <el-form-item label="字段名"><el-input v-model="newField.fieldName" placeholder="如 src_ip" /></el-form-item>
                  <el-form-item label="中文名"><el-input v-model="newField.fieldLabel" placeholder="中文名" /></el-form-item>
                  <el-form-item label="类型">
                    <el-select v-model="newField.fieldType" style="width:160px">
                      <el-option v-for="t in ['string','int','long','float','ip','date','bool','json']" :key="t" :label="t" :value="t" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="来源">
                    <el-select v-model="newField.source" style="width:160px">
                      <el-option label="system" value="system" /><el-option label="parse" value="parse" /><el-option label="custom" value="custom" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="索引策略">
                    <el-checkbox v-model="newField.searchable">检索</el-checkbox>
                    <el-checkbox v-model="newField.aggregatable">聚合</el-checkbox>
                    <el-checkbox v-model="newField.stored">存储</el-checkbox>
                  </el-form-item>
                  <el-form-item label="说明"><el-input v-model="newField.description" placeholder="说明" /></el-form-item>
                </el-form>
                <template #footer><el-button @click="showFieldDialog = false">取消</el-button><el-button type="success" @click="addField(); showFieldDialog = false">新增字段</el-button></template>
              </el-dialog>
              <el-card shadow="never">
                <template #header>字段字典（统一字段语义，解析/检索/告警共用）</template>
                <el-table :data="fieldDefs" size="small">
                  <el-table-column prop="fieldName" label="字段名" width="130" />
                  <el-table-column prop="fieldLabel" label="中文名" width="110" />
                  <el-table-column prop="fieldType" label="类型" width="80" />
                  <el-table-column prop="source" label="来源" width="80" />
                  <el-table-column label="索引策略" width="150">
                    <template #default="{ row }">
                      <el-tag v-if="row.searchable" size="small" type="success" style="margin-right:4px">检索</el-tag>
                      <el-tag v-if="row.aggregatable" size="small" type="warning" style="margin-right:4px">聚合</el-tag>
                      <el-tag v-if="row.stored" size="small" type="info">存储</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
                  <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeField(row.id)">删除</el-button></template></el-table-column>
                </el-table>
              </el-card>
            </el-tab-pane>
          </el-tabs>
        </div>

        <!-- 检测规则 -->
        <div v-else-if="activeMenu === 'detect'" class="page-pad view-enter">
          <el-row :gutter="12" style="margin-bottom:14px">
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ gasStat.rules ?? 0 }}</div><div class="label">规则数</div></div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ gasStat.eventCount ?? 0 }}</div><div class="label">事件数</div></div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#f56c6c">{{ gasStat.alertCount ?? 0 }}</div><div class="label">告警数</div></div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ ((gasStat.queueLoad ?? 0) as number * 100).toFixed(0) }}%</div><div class="label">队列水位</div></div></el-card></el-col>
          </el-row>
          <el-card shadow="never" style="margin-bottom:14px">
            <template #header>模拟事件投递</template>
            <div style="display:flex;gap:10px;align-items:center">
              <el-select v-model="ingestSource" style="width:120px"><el-option label="auth" value="auth" /><el-option label="web" value="web" /><el-option label="firewall" value="firewall" /></el-select>
              <el-input v-model="ingestMsg" placeholder="如：Failed password for admin" style="width:360px" />
              <el-button type="primary" @click="doIngest">投递</el-button>
              <span v-if="ingestResult" class="mono" style="font-size:12px;color:#67c23a">{{ ingestResult }}</span>
            </div>
          </el-card>
          <el-card shadow="never">
            <template #header>
              <div style="display:flex;justify-content:space-between;align-items:center">
                <span>规则列表（可新建/编辑/删除/启停，保存后引擎热更新）</span>
                <el-button type="primary" size="small" @click="openRuleEditor()">新建规则</el-button>
              </div>
            </template>
            <el-table :data="rules" size="small">
              <el-table-column prop="id" label="ID" width="150" />
              <el-table-column prop="name" label="名称" min-width="150" />
              <el-table-column prop="type" label="类型" width="90" />
              <el-table-column prop="severity" label="级别" width="85"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
              <el-table-column label="匹配条件" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">{{ (row.match || []).map((c: { field: string; op: string; value: string }) => `${c.field} ${c.op} ${c.value}`).join(' AND ') || (row.steps || []).length + ' 步关联' || '-' }}</template>
              </el-table-column>
              <el-table-column label="启用" width="70"><template #default="{ row }"><el-tag :type="row.enabled?'success':'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="170">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openRuleEditor(row)">编辑</el-button>
                  <el-button link size="small" @click="toggleRule(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
                  <el-button link type="danger" size="small" @click="removeRule(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <!-- 规则编辑对话框 -->
          <el-dialog v-model="showRuleEditor" :title="ruleEditingId ? '编辑规则' : '新建规则'" width="640px">
            <el-form label-width="90px">
              <el-form-item label="名称"><el-input v-model="ruleForm.name" placeholder="如：SSH 暴力破解" /></el-form-item>
              <el-form-item label="类型">
                <el-radio-group v-model="ruleForm.type">
                  <el-radio value="pattern">模式</el-radio>
                  <el-radio value="threshold">阈值</el-radio>
                  <el-radio value="correlation">关联</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="级别">
                <el-select v-model="ruleForm.severity" style="width:160px">
                  <el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" />
                </el-select>
              </el-form-item>
              <el-form-item label="告警消息"><el-input v-model="ruleForm.message" placeholder="支持 {key} {count} {host} 占位" /></el-form-item>
              <el-form-item label="窗口"><el-input v-model="ruleForm.window" placeholder="如 60s / 5m / 1h" style="width:120px" /></el-form-item>
              <template v-if="ruleForm.type === 'threshold'">
                <el-form-item label="分组字段"><el-select v-model="ruleForm.keyField" style="width:160px"><el-option v-for="f in COND_FIELDS" :key="f" :label="f" :value="f" /></el-select></el-form-item>
                <el-form-item label="触发阈值"><el-input v-model.number="ruleForm.threshold" type="number" style="width:120px" /></el-form-item>
              </template>
              <template v-if="ruleForm.type === 'correlation'">
                <el-form-item label="关联字段"><el-select v-model="ruleForm.keyField" style="width:160px"><el-option v-for="f in COND_FIELDS" :key="f" :label="f" :value="f" /></el-select></el-form-item>
                <el-form-item label="关联步骤">
                  <div v-for="(step, si) in ruleForm.steps" :key="si" style="border:1px solid #e4e7ed;border-radius:6px;padding:8px;margin-bottom:8px">
                    <div style="font-size:12px;color:#909399;margin-bottom:4px">步骤 {{ si + 1 }}（同一实体按序命中）</div>
                    <div v-for="(c, ci) in step" :key="ci" style="display:flex;gap:6px;margin-bottom:4px">
                      <el-select v-model="c.field" size="small" style="width:110px"><el-option v-for="f in COND_FIELDS" :key="f" :label="f" :value="f" /></el-select>
                      <el-select v-model="c.op" size="small" style="width:100px"><el-option v-for="o in COND_OPS" :key="o" :label="o" :value="o" /></el-select>
                      <el-input v-model="c.value" size="small" placeholder="值" style="flex:1" />
                      <el-button size="small" type="danger" link @click="step.splice(ci, 1)">删</el-button>
                    </div>
                    <el-button size="small" link type="primary" @click="ruleForm.steps[si].push({ field: 'msg', op: 'contains', value: '' })">+ 条件</el-button>
                    <el-button v-if="ruleForm.steps.length > 1" size="small" link type="danger" @click="ruleForm.steps.splice(si, 1)">删除步骤</el-button>
                  </div>
                  <el-button size="small" type="primary" plain @click="ruleForm.steps.push([{ field: 'msg', op: 'contains', value: '' }])">+ 步骤</el-button>
                </el-form-item>
              </template>
              <el-form-item v-else label="匹配条件">
                <div v-for="(c, ci) in ruleForm.match" :key="ci" style="display:flex;gap:6px;margin-bottom:4px;width:100%">
                  <el-select v-model="c.field" size="small" style="width:110px"><el-option v-for="f in COND_FIELDS" :key="f" :label="f" :value="f" /></el-select>
                  <el-select v-model="c.op" size="small" style="width:110px"><el-option v-for="o in COND_OPS" :key="o" :label="o" :value="o" /></el-select>
                  <el-input v-model="c.value" size="small" placeholder="值（条件间为 AND）" style="flex:1" />
                  <el-button size="small" type="danger" link @click="ruleForm.match.splice(ci, 1)">删</el-button>
                </div>
                <el-button size="small" type="primary" plain @click="ruleForm.match.push({ field: 'msg', op: 'contains', value: '' })">+ 条件</el-button>
              </el-form-item>
              <el-form-item label="启用"><el-switch v-model="ruleForm.enabled" /></el-form-item>
            </el-form>
            <template #footer><el-button @click="showRuleEditor = false">取消</el-button><el-button type="primary" @click="saveRule">保存并热更新</el-button></template>
          </el-dialog>
        </div>

        <!-- UEBA 风险看板 -->
        <div v-else-if="activeMenu === 'ueba'" class="page-pad view-enter">
          <el-row :gutter="12" style="margin-bottom:14px">
            <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num">{{ riskSummary?.entities ?? 0 }}</div><div class="label">画像实体数</div></div></el-card></el-col>
            <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#f56c6c">{{ riskSummary?.maxRisk ?? 0 }}</div><div class="label">最高风险分</div></div></el-card></el-col>
            <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#e6a23c">{{ (riskSummary?.byLevel?.CRITICAL ?? 0) + (riskSummary?.byLevel?.HIGH ?? 0) }}</div><div class="label">高危实体</div></div></el-card></el-col>
            <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num">{{ riskSummary?.halfLifeHours ?? 0 }}h</div><div class="label">风险半衰期</div></div></el-card></el-col>
            <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#409eff">{{ watchlists.length }}</div><div class="label">观察名单</div></div></el-card></el-col>
          </el-row>

          <el-tabs v-model="uebaTab">
            <!-- 实体风险 -->
            <el-tab-pane label="实体风险排行" name="entities">
              <div style="display:flex;gap:10px;align-items:center;margin-bottom:12px">
                <span style="font-size:13px;color:#909399">Top N</span>
                <el-input-number v-model="riskLimit" :min="5" :max="100" :step="5" size="small" @change="loadUeba" />
                <el-button size="small" @click="loadUeba">刷新</el-button>
                <span style="font-size:12px;color:#909399">
                  风险分 = 严重级别 + ATT&CK 战术权重 + 情报命中 + 频次 + 资产重要性，按 {{ riskSummary?.halfLifeHours ?? 6 }} 小时半衰期指数衰减
                </span>
              </div>
              <el-row :gutter="12">
                <el-col :span="10">
                  <el-card shadow="never">
                    <template #header>风险 Top 10</template>
                    <div ref="riskBarEl" style="height:340px"></div>
                  </el-card>
                </el-col>
                <el-col :span="14">
                  <el-card shadow="never">
                    <template #header>实体明细（点击行下钻）</template>
                    <el-table :data="riskEntities" size="small" height="340" @row-click="openEntity">
                      <el-table-column label="风险" width="80">
                        <template #default="{ row }"><span class="risk-pill" :style="{ background: riskColor(row.level) }">{{ row.risk }}</span></template>
                      </el-table-column>
                      <el-table-column label="实体" min-width="150">
                        <template #default="{ row }">
                          <span class="mono">{{ row.entity }}</span>
                          <el-tag v-if="row.critical" size="small" type="danger" effect="dark" style="margin-left:6px">核心资产</el-tag>
                        </template>
                      </el-table-column>
                      <el-table-column prop="alerts" label="告警数" width="80" />
                      <el-table-column label="最高级别" width="100">
                        <template #default="{ row }"><SevBadge :value="row.maxSeverity" /></template>
                      </el-table-column>
                      <el-table-column label="主要战术" min-width="140">
                        <template #default="{ row }">
                          <el-tag v-for="m in row.mitre.slice(0, 3)" :key="m.technique" size="small" style="margin-right:4px">{{ m.technique }}×{{ m.count }}</el-tag>
                          <span v-if="!row.mitre.length" style="color:#c0c4cc">—</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="最近活动" width="150">
                        <template #default="{ row }"><span class="mono" style="font-size:12px">{{ fmtTime(row.lastSeen) }}</span></template>
                      </el-table-column>
                    </el-table>
                  </el-card>
                </el-col>
              </el-row>
            </el-tab-pane>

            <!-- 观察名单 -->
            <el-tab-pane label="观察名单" name="watchlists">
              <div class="add-bar">
                <el-button type="primary" @click="openWlDialog">+ 新增观察名单</el-button>
                <span class="hint">名单可被规则条件 <code class="mono">op=inlist / notinlist</code> 引用，改完立即生效，无需重载规则</span>
              </div>
              <el-dialog v-model="showWlDialog" title="新增观察名单" width="560px">
                <el-form label-width="92px">
                  <el-form-item label="名单标识"><el-input v-model="newWl.name" placeholder="如 vip_accounts" /></el-form-item>
                  <el-form-item label="成员值">
                    <el-input v-model="newWl.values" type="textarea" :rows="4" placeholder="值，逗号/空格/换行分隔" />
                  </el-form-item>
                </el-form>
                <template #footer>
                  <el-button @click="showWlDialog = false">取消</el-button>
                  <el-button type="primary" @click="doCreateWl(); showWlDialog = false">创建/覆盖</el-button>
                </template>
              </el-dialog>
              <el-row :gutter="12">
                <el-col v-for="w in watchlists" :key="w.name" :span="8" style="margin-bottom:12px">
                  <el-card shadow="never" class="wl-card">
                    <template #header>
                      <div style="display:flex;align-items:center;gap:8px">
                        <span class="mono" style="font-weight:600">{{ w.name }}</span>
                        <el-tag size="small" type="info">{{ w.size }} 项</el-tag>
                        <el-button link type="danger" size="small" style="margin-left:auto" @click="doDeleteWl(w.name)">删除</el-button>
                      </div>
                    </template>
                    <div class="wl-values">
                      <el-tag v-for="v in w.values" :key="v" size="small" style="margin:2px" class="mono">{{ v }}</el-tag>
                      <span v-if="!w.values.length" style="color:#c0c4cc;font-size:12px">空名单</span>
                    </div>
                    <div style="display:flex;gap:6px;margin-top:10px">
                      <el-input v-model="wlAppend[w.name]" size="small" placeholder="追加值" @keyup.enter="doAppendWl(w.name)" />
                      <el-button size="small" @click="doAppendWl(w.name)">追加</el-button>
                    </div>
                  </el-card>
                </el-col>
              </el-row>
            </el-tab-pane>

            <!-- 评分模型 -->
            <el-tab-pane label="评分模型试算" name="score">
              <el-row :gutter="12">
                <el-col :span="10">
                  <el-card shadow="never">
                    <template #header>输入条件</template>
                    <el-form label-width="120px" size="small">
                      <el-form-item label="严重级别">
                        <el-select v-model="scoreForm.severity" @change="calcScore" style="width:160px">
                          <el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" />
                        </el-select>
                      </el-form-item>
                      <el-form-item label="ATT&CK 技术">
                        <el-input v-model="scoreForm.mitre" placeholder="如 T1486" style="width:160px" @change="calcScore" />
                      </el-form-item>
                      <el-form-item label="情报命中数">
                        <el-slider v-model="scoreForm.tiHits" :min="0" :max="5" show-stops @change="calcScore" />
                      </el-form-item>
                      <el-form-item label="近 1h 同实体告警">
                        <el-slider v-model="scoreForm.recentAlerts" :min="0" :max="20" @change="calcScore" />
                      </el-form-item>
                      <el-form-item label="资产重要性">
                        <el-slider v-model="scoreForm.assetCriticality" :min="0" :max="3" show-stops @change="calcScore" />
                      </el-form-item>
                    </el-form>
                  </el-card>
                </el-col>
                <el-col :span="14">
                  <el-card shadow="never">
                    <template #header>评分拆解（与检测/分析侧同一口径）</template>
                    <div v-if="scoreResult">
                      <div style="display:flex;align-items:baseline;gap:12px;margin-bottom:16px">
                        <span style="font-size:44px;font-weight:700" :style="{ color: riskColor(scoreResult.level) }">{{ scoreResult.score }}</span>
                        <SevBadge :value="scoreResult.level" />
                        <span style="font-size:12px;color:#909399">总分上限 100</span>
                      </div>
                      <div v-for="(v, k) in scoreResult.breakdown" :key="k" class="bd-row">
                        <span class="bd-label">{{ BREAKDOWN_LABEL[k] ?? k }}</span>
                        <div class="bd-bar"><div class="bd-fill" :style="{ width: Math.min(100, v) + '%', background: riskColor(scoreResult.level) }" /></div>
                        <span class="bd-val">+{{ v }}</span>
                      </div>
                    </div>
                    <el-empty v-else description="评分服务不可用" />
                  </el-card>
                </el-col>
              </el-row>
            </el-tab-pane>
          </el-tabs>

          <!-- 实体下钻抽屉 -->
          <el-drawer v-model="entityDrawer" size="480px" :title="entityDetail?.entity ?? '实体画像'">
            <div v-if="entityDetail">
              <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px">
                <span class="risk-pill lg" :style="{ background: riskColor(entityDetail.level) }">{{ entityDetail.risk }}</span>
                <div>
                  <div style="font-weight:600" class="mono">{{ entityDetail.entity }}</div>
                  <div style="font-size:12px;color:#909399">{{ entityDetail.level }} · {{ entityDetail.alerts }} 条告警</div>
                </div>
                <el-tag v-if="entityDetail.critical" type="danger" effect="dark" style="margin-left:auto">核心资产</el-tag>
              </div>
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="最高级别"><SevBadge :value="entityDetail.maxSeverity" /></el-descriptions-item>
                <el-descriptions-item label="首次出现">{{ fmtTime(entityDetail.firstSeen) }}</el-descriptions-item>
                <el-descriptions-item label="最近活动">{{ fmtTime(entityDetail.lastSeen) }}</el-descriptions-item>
              </el-descriptions>
              <h4 style="margin:16px 0 8px">ATT&CK 技术分布</h4>
              <el-table :data="entityDetail.mitre" size="small" border>
                <el-table-column prop="technique" label="技术" width="120" />
                <el-table-column prop="count" label="次数" width="80" />
                <el-table-column label="占比">
                  <template #default="{ row }">
                    <el-progress :percentage="Math.round(row.count / entityDetail!.alerts * 100)" :stroke-width="10" />
                  </template>
                </el-table-column>
              </el-table>
              <h4 style="margin:16px 0 8px">触发最多的规则</h4>
              <el-table :data="entityDetail.topRules" size="small" border>
                <el-table-column prop="rule" label="规则" min-width="180" show-overflow-tooltip />
                <el-table-column prop="count" label="次数" width="80" />
              </el-table>
              <div style="margin-top:16px">
                <el-button type="primary" plain @click="entityDrawer = false; alarmKeyword = entityDetail!.entity; onMenuChange('alarms')">查看该实体全部告警</el-button>
              </div>
            </div>
          </el-drawer>
        </div>

        <!-- 编排响应 -->
        <div v-else-if="activeMenu === 'soar'" class="page-pad view-enter">
          <div style="margin-bottom:12px"><el-button type="primary" @click="showPbDialog = true">新建剧本</el-button><el-button @click="loadPlaybooks">刷新</el-button></div>
          <el-card shadow="never">
            <el-table :data="playbooks" size="small">
              <el-table-column prop="name" label="剧本" min-width="140" />
              <el-table-column prop="trigger" label="触发条件" min-width="200" show-overflow-tooltip />
              <el-table-column label="动作链" min-width="260"><template #default="{ row }"><el-tag v-for="a in row.actions" :key="a" size="small" style="margin-right:6px">{{ a }}</el-tag></template></el-table-column>
              <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag :type="row.enabled?'success':'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="130"><template #default="{ row }"><el-button link size="small" @click="togglePb(row.id)">{{ row.enabled ? '停用' : '启用' }}</el-button><el-button link type="danger" size="small" @click="removePb(row.id)">删除</el-button></template></el-table-column>
            </el-table>
          </el-card>
          <el-card shadow="never" style="margin-top:14px">
            <template #header>执行历史（最近 {{ pbExecutions.length }} 条）</template>
            <el-table :data="pbExecsPaged" size="small">
              <el-table-column prop="ts" label="时间" width="200" />
              <el-table-column prop="playbook" label="剧本" min-width="140" />
              <el-table-column prop="trigger" label="触发" min-width="160" show-overflow-tooltip />
              <el-table-column label="动作结果" min-width="320">
                <template #default="{ row }">
                  <span v-for="(r, i) in (row.results as any[] || [])" :key="i" style="margin-right:8px">
                    <el-tag size="small" :type="String(r.status).startsWith('fail') ? 'danger' : (String(r.status).startsWith('sent') || String(r.status).startsWith('created') ? 'success' : 'info')">{{ r.action }} → {{ r.status }}</el-tag>
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
          <el-dialog v-model="showPbDialog" title="新建剧本" width="500px">
            <el-form label-width="80px">
              <el-form-item label="名称"><el-input v-model="newPb.name" /></el-form-item>
              <el-form-item label="触发"><el-input v-model="newPb.trigger" /></el-form-item>
              <el-form-item label="动作"><el-input v-model="newPb.actions" type="textarea" :rows="3" placeholder="每行一个动作" /></el-form-item>
              <el-form-item label="启用"><el-switch v-model="newPb.enabled" /></el-form-item>
            </el-form>
            <template #footer><el-button @click="showPbDialog = false">取消</el-button><el-button type="primary" @click="addPb">创建</el-button></template>
          </el-dialog>
        </div>

        <!-- 报表统计 -->
        <div v-else-if="activeMenu === 'report'" class="page-pad view-enter">
          <div style="margin-bottom:12px;display:flex;gap:10px;align-items:center">
            <el-button @click="loadReport">刷新</el-button>
            <el-button type="primary" :loading="archiveBusy" @click="doArchive">归档至 MinIO</el-button>
            <span v-if="archiveInfo" style="font-size:12px;color:var(--ns-text-3)">已归档 {{ archiveInfo.count }} 个对象</span>
          </div>
          <el-row :gutter="12" style="margin-bottom:14px" v-if="report">
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ report.total }}</div><div class="label">今日告警</div></div></el-card>
          <PagerBar v-model:current-page="pbExecPage" v-model:page-size="pbExecSize" :total="pbExecutions.length" />
</el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#f56c6c">{{ report.bySeverity.CRITICAL ?? 0 }}</div><div class="label">CRITICAL</div></div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#e63946">{{ report.bySeverity.HIGH ?? 0 }}</div><div class="label">HIGH</div></div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#e6a23c">{{ report.bySeverity.MEDIUM ?? 0 }}</div><div class="label">MEDIUM</div></div></el-card></el-col>
          </el-row>
          <el-row :gutter="12">
            <el-col :span="12"><el-card shadow="never"><div ref="barEl" style="height:300px" /></el-card></el-col>
            <el-col :span="12"><el-card shadow="never"><div ref="lineEl" style="height:300px" /></el-card></el-col>
          </el-row>
          <el-card shadow="never" style="margin-top:14px" v-if="report">
            <template #header>TOP 规则</template>
            <el-table :data="report.byRule" size="small"><el-table-column prop="rule" label="规则" /><el-table-column prop="count" label="告警数" width="120" /></el-table>
          </el-card>
          <el-card shadow="never" style="margin-top:14px" v-if="archiveInfo?.objects.length">
            <template #header>MinIO 归档对象</template>
            <el-table :data="archiveInfo.objects" size="small">
              <el-table-column prop="key" label="对象 Key" min-width="240" />
              <el-table-column prop="size" label="大小" width="120"><template #default="{ row }">{{ (row.size / 1024).toFixed(1) }} KB</template></el-table-column>
            </el-table>
          </el-card>
        </div>

        <!-- 资产管理 -->
        <div v-else-if="activeMenu === 'assets'" class="page-pad view-enter">
          <div style="margin-bottom:12px"><el-button @click="loadAssets">刷新</el-button></div>
          <el-row :gutter="12" style="margin-bottom:14px" v-if="assetStat">
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ assetStat.total }}</div><div class="label">资产总数</div></div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#f56c6c">{{ assetStat.byCriticality?.CRITICAL ?? 0 }}</div><div class="label">关键资产</div></div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#e6a23c">{{ assetStat.byCriticality?.HIGH ?? 0 }}</div><div class="label">高价值资产</div></div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ Object.keys(assetStat.byType || {}).length }}</div><div class="label">资产类型</div></div></el-card></el-col>
          </el-row>
          <el-card shadow="never">
            <el-table :data="assetsPaged" size="small">
              <el-table-column prop="name" label="名称" width="140" />
              <el-table-column prop="type" label="类型" width="100" />
              <el-table-column prop="ip" label="IP" width="120" />
              <el-table-column prop="os" label="系统" min-width="140" />
              <el-table-column prop="owner" label="负责人" width="100" />
              <el-table-column prop="criticality" label="关键度" width="90"><template #default="{ row }"><el-tag :type="row.criticality==='CRITICAL'?'danger':row.criticality==='HIGH'?'warning':'info'" size="small">{{ row.criticality }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeAsset(row.id)">删除</el-button></template></el-table-column>
            </el-table>
          </el-card>
        </div>

        <!-- 端点防护 -->
        <div v-else-if="activeMenu === 'endpoints'" class="page-pad view-enter">
          <div style="margin-bottom:12px"><el-button @click="loadEndpoints">刷新</el-button></div>
          <el-row :gutter="12" style="margin-bottom:14px" v-if="endpointStat">
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ endpointStat.total }}</div><div class="label">端点总数</div></div></el-card>
          <PagerBar v-model:current-page="assetPage" v-model:page-size="assetSize" :total="assets.length" />
</el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#30d158">{{ endpointStat.online }}</div><div class="label">在线端点</div></div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#e6a23c">{{ endpointStat.total - endpointStat.online }}</div><div class="label">离线端点</div></div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ Object.keys(endpointStat.byType || {}).length }}</div><div class="label">端点类型</div></div></el-card></el-col>
          </el-row>
          <el-card shadow="never">
            <el-table :data="endpoints" size="small">
              <el-table-column prop="hostname" label="主机名" width="140" />
              <el-table-column prop="ip" label="IP" width="120" />
              <el-table-column prop="os" label="系统" min-width="140" />
              <el-table-column prop="agentVersion" label="Agent 版本" width="120" />
              <el-table-column prop="status" label="状态" width="80"><template #default="{ row }"><el-tag :type="row.status==='ONLINE'?'success':'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeEp(row.id)">注销</el-button></template></el-table-column>
            </el-table>
          </el-card>
        </div>

        <!-- AI 助手 -->
        <div v-else-if="activeMenu === 'ai'" class="page-pad view-enter">
          <el-card shadow="never">
            <div style="display:flex;gap:10px;margin-bottom:16px">
              <el-input v-model="aiQuestion" placeholder="提问：如何检测暴力破解？端口扫描怎么处理？" @keyup.enter="doAsk" style="flex:1" />
              <el-button type="primary" :loading="aiLoading" @click="doAsk">提问</el-button>
            </div>
            <div v-if="aiResult" style="background:var(--ns-bg-subtle);border-radius:8px;padding:16px">
              <p style="font-weight:600;margin:0 0 8px">问：{{ aiResult.question }}</p>
              <p style="white-space:pre-wrap;margin:0 0 12px">{{ aiResult.answer }}</p>
              <p v-if="aiResult.suggestion" style="color:#409eff;margin:0">{{ aiResult.suggestion }}</p>
              <p style="color:#909399;font-size:12px;margin:8px 0 0">耗时 {{ aiResult.elapsedMs }}ms</p>
            </div>
          </el-card>
        </div>

        <!-- 威胁情报 (threat-web) -->
        <div v-else-if="activeMenu === 'threat-intel'" class="page-pad view-enter">
          <div style="display:flex;gap:16px;margin-bottom:14px;flex-wrap:wrap">
            <el-card shadow="never" :body-style="{ padding: '12px 18px' }">
              <div style="font-size:12px;color:#909399">情报总量</div>
              <div style="font-size:22px;font-weight:700">{{ tiStat.total ?? 0 }}</div>
            </el-card>
            <el-card v-for="(c, k) in (tiStat.byType || {})" :key="k" shadow="never" :body-style="{ padding: '12px 18px' }">
              <div style="font-size:12px;color:#909399">{{ k }}</div>
              <div style="font-size:22px;font-weight:700">{{ c }}</div>
            </el-card>
          </div>
          <el-card shadow="never" style="margin-bottom:14px">
            <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">
              <el-input v-model="tiMatchValue" placeholder="匹配情报，如 185.220.101.45 或 evil-c2.com" style="width:320px" @keyup.enter="doTiMatch" />
              <el-button type="primary" @click="doTiMatch">查询命中</el-button>
              <el-select v-model="iocType" placeholder="全部类型" clearable style="width:140px" @change="loadTi">
                <el-option v-for="t in ['ip','domain','url','sha256','email']" :key="t" :label="t" :value="t" />
              </el-select>
            </div>
            <el-alert v-if="tiMatchResult" :title="tiMatchResult.matched ? `命中情报库：${tiMatchResult.ioc?.value}（${tiMatchResult.ioc?.severity}）` : '未命中情报库'" :type="tiMatchResult.matched ? 'error' : 'info'" :closable="false" style="margin-top:10px" />
          </el-card>
          <div class="add-bar">
            <el-button type="primary" @click="openIocDialog">+ 新增情报</el-button>
            <span class="hint">IP / 域名 / URL / 文件哈希 / 邮箱，命中后被规则与富化引用</span>
          </div>
          <el-dialog v-model="showIocDialog" title="新增威胁情报" width="560px">
            <el-form label-width="80px">
              <el-form-item label="情报值"><el-input v-model="newIoc.value" placeholder="如 1.2.3.4" /></el-form-item>
              <el-form-item label="类型">
                <el-select v-model="newIoc.type" style="width:160px"><el-option v-for="t in ['ip','domain','url','sha256','email']" :key="t" :label="t" :value="t" /></el-select>
              </el-form-item>
              <el-form-item label="严重度">
                <el-select v-model="newIoc.severity" style="width:160px"><el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" /></el-select>
              </el-form-item>
              <el-form-item label="描述"><el-input v-model="newIoc.description" placeholder="描述" /></el-form-item>
            </el-form>
            <template #footer><el-button @click="showIocDialog = false">取消</el-button><el-button type="success" @click="addIoc(); showIocDialog = false">新增情报</el-button></template>
          </el-dialog>
          <el-card shadow="never">
            <el-table :data="iocsPaged" size="small">
              <el-table-column prop="type" label="类型" width="90" />
              <el-table-column prop="value" label="值" min-width="160" />
              <el-table-column label="严重度" width="90"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
              <el-table-column prop="source" label="来源" width="100" />
              <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
              <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeIoc(row.id)">删除</el-button></template></el-table-column>
            </el-table>
          </el-card>
        </div>

        <!-- MITRE ATT&CK (attack-web) -->
        <div v-else-if="activeMenu === 'attack'" class="page-pad view-enter">
          <el-card shadow="never" style="margin-bottom:14px">
            <div style="display:flex;gap:20px;align-items:center;flex-wrap:wrap">
              <div>
                <div style="font-size:12px;color:#909399">检测覆盖率</div>
                <div style="font-size:30px;font-weight:700;color:#409eff">{{ attackCov ? attackCov.coverage : '—' }}%</div>
              </div>
              <div>
                <div style="font-size:12px;color:#909399">已覆盖 / 总技术</div>
                <div style="font-size:18px;font-weight:600">{{ attackCov ? attackCov.coveredTechniques : '—' }} / {{ attackCov ? attackCov.totalTechniques : '—' }}</div>
              </div>
              <el-select v-model="attackTech" placeholder="全部战术" clearable style="width:170px" @change="loadAttack">
                <el-option v-for="t in tactics" :key="t.id" :label="t.name" :value="t.id" />
              </el-select>
              <el-button :loading="attackLoading" @click="computeAttackCov">重新计算</el-button>
            </div>
            <div v-if="attackCov && attackCov.uncovered.length" style="margin-top:10px">
              <span style="color:#909399;font-size:12px">未覆盖技术：</span>
              <el-tag v-for="u in attackCov.uncovered.slice(0, 24)" :key="u" size="small" type="info" style="margin:2px">{{ u }}</el-tag>
            </div>
          </el-card>
          <PagerBar v-model:current-page="iocPage" v-model:page-size="iocSize" :total="iocs.length" />

          <el-card shadow="never" style="margin-bottom:14px">
            <template #header>ATT&CK 战术矩阵（红=有告警命中 · 绿=已覆盖 · 灰=未覆盖）</template>
            <div class="attack-matrix">
              <div v-for="col in attackMatrix" :key="col.tac.id" class="am-col">
                <div class="am-head">{{ col.tac.name }}<span class="am-cov">{{ col.covered }}/{{ col.total }}</span></div>
                <div v-for="t in col.techs" :key="t.id" class="am-cell" :style="techStyle(t)" @click="openUrl(t.url)" :title="t.id + ' ' + t.name">
                  <span class="am-id">{{ t.id }}</span><span v-if="t.count" class="am-badge">{{ t.count }}</span>
                </div>
              </div>
            </div>
          </el-card>
          <el-card shadow="never">
            <el-table :data="techniques" size="small">
              <el-table-column prop="id" label="技术 ID" width="110" />
              <el-table-column prop="name" label="名称" min-width="180" />
              <el-table-column prop="tactic" label="战术" width="130" />
              <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="primary" size="small" @click="openUrl(row.url)">详情</el-button></template></el-table-column>
            </el-table>
          </el-card>
        </div>

        <!-- 通知集成 (notify-web) -->
        <div v-else-if="activeMenu === 'notify'" class="page-pad view-enter">
          <div class="add-bar">
            <el-button type="primary" @click="openChannelDialog">+ 新增通知渠道</el-button>
            <span class="hint">SLACK / WEBHOOK / EMAIL，告警触发后实时分发</span>
          </div>
          <el-dialog v-model="showChannelDialog" title="新增通知渠道" width="560px">
            <el-form label-width="80px">
              <el-form-item label="渠道名"><el-input v-model="newChannel.name" placeholder="如 安全群" /></el-form-item>
              <el-form-item label="类型">
                <el-select v-model="newChannel.type" style="width:160px"><el-option v-for="t in ['SLACK','WEBHOOK','EMAIL']" :key="t" :label="t" :value="t" /></el-select>
              </el-form-item>
              <el-form-item label="目标"><el-input v-model="newChannel.target" placeholder="Webhook URL / 邮箱" /></el-form-item>
              <el-form-item label="描述"><el-input v-model="newChannel.description" placeholder="描述（可选）" /></el-form-item>
              <el-form-item label="启用"><el-switch v-model="newChannel.enabled" /></el-form-item>
            </el-form>
            <template #footer><el-button @click="showChannelDialog = false">取消</el-button><el-button type="success" @click="addChannel(); showChannelDialog = false">新增渠道</el-button></template>
          </el-dialog>
          <el-card shadow="never" style="margin-bottom:14px">
            <div class="sec-title" style="margin-bottom:8px">通知渠道</div>
            <el-table :data="channels" size="small">
              <el-table-column prop="name" label="名称" width="140" />
              <el-table-column prop="type" label="类型" width="100" />
              <el-table-column prop="target" label="目标" min-width="200" show-overflow-tooltip />
              <el-table-column label="启用" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="150">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="doToggleChannel(row.id)">{{ row.enabled ? '停用' : '启用' }}</el-button>
                  <el-button link type="danger" size="small" @click="removeChannel(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
          <el-card shadow="never">
            <div class="sec-title" style="margin-bottom:8px">分发日志（告警触发后实时写入）</div>
            <el-table :data="dispatchLogList" size="small">
              <el-table-column prop="ts" label="时间" width="220" />
              <el-table-column prop="channel" label="渠道" width="120" />
              <el-table-column prop="type" label="类型" width="90" />
              <el-table-column prop="ruleId" label="规则" width="140" />
              <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'sent' ? 'success' : row.status === 'failed' ? 'danger' : 'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
            </el-table>
          </el-card>
        </div>

        <!-- 案件管理 (incident-web) -->
        <div v-else-if="activeMenu === 'case'" class="page-pad view-enter">
          <div style="display:flex;gap:16px;margin-bottom:14px;flex-wrap:wrap">
            <el-card shadow="never" :body-style="{ padding: '12px 18px' }">
              <div style="font-size:12px;color:#909399">案件总数</div><div style="font-size:22px;font-weight:700">{{ caseStat.total ?? 0 }}</div>
            </el-card>
            <el-card shadow="never" :body-style="{ padding: '12px 18px' }">
              <div style="font-size:12px;color:#909399">进行中</div><div style="font-size:22px;font-weight:700;color:#e6a23c">{{ caseStat.open ?? 0 }}</div>
            </el-card>
            <el-card shadow="never" :body-style="{ padding: '12px 18px' }">
              <div style="font-size:12px;color:#909399">已解决</div><div style="font-size:22px;font-weight:700;color:#67c23a">{{ caseStat.resolved ?? 0 }}</div>
            </el-card>
            <span style="flex:1"></span>
            <el-button size="small" @click="exportCases()">导出案件 JSON</el-button>
          </div>
          <el-card shadow="never">
            <el-table :data="casesPaged" size="small">
              <el-table-column prop="id" label="案件 ID" width="180" />
              <el-table-column prop="title" label="标题" min-width="180" />
              <el-table-column prop="entity" label="实体" width="130" />
              <el-table-column label="级别" width="90"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
              <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="row.status === 'OPEN' ? 'danger' : row.status === 'RESOLVED' || row.status === 'CLOSED' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag></template></el-table-column>
              <el-table-column prop="alarmIds.length" label="关联告警" width="90" />
              <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" size="small" @click="openCase(row)">详情/时间线</el-button></template></el-table-column>
            </el-table>
          </el-card>
          <el-drawer v-model="caseDrawer" :title="`案件 · ${caseDetail?.title ?? ''}`" size="520px">
            <template v-if="caseDetail">
              <el-descriptions :column="2" size="small" border style="margin-bottom:14px">
                <el-descriptions-item label="案件 ID">{{ caseDetail.id }}</el-descriptions-item>
                <el-descriptions-item label="实体">{{ caseDetail.entity }}</el-descriptions-item>
                <el-descriptions-item label="级别"><SevBadge :value="caseDetail.severity" /></el-descriptions-item>
                <el-descriptions-item label="状态">{{ caseDetail.status }}</el-descriptions-item>
                <el-descriptions-item label="关联规则" :span="2">{{ caseDetail.ruleIds.join(', ') || '—' }}</el-descriptions-item>
                <el-descriptions-item label="关联告警" :span="2">{{ caseDetail.alarmIds.join(', ') || '—' }}</el-descriptions-item>
              </el-descriptions>
              <div style="display:flex;gap:8px;margin-bottom:14px">
                <el-select v-model="newCaseStatus" style="flex:1"><el-option v-for="s in ['OPEN','INVESTIGATING','CONTAINED','RESOLVED','CLOSED']" :key="s" :label="s" :value="s" /></el-select>
                <el-button type="primary" @click="doSetCaseStatus">更新状态</el-button>
              </div>
              <el-divider content-position="left">事件时间线</el-divider>
              <el-timeline>
                <el-timeline-item v-for="(e, i) in caseTimelineData" :key="i" :timestamp="e.ts" placement="top">
                  <div style="font-size:13px">{{ e.message }}</div>
                  <div style="font-size:12px;color:#909399">{{ e.type }} · {{ e.source }}</div>
                </el-timeline-item>
              </el-timeline>
            </template>
          </el-drawer>
        </div>

        <!-- 参考数据集 (search-config) -->
        <div v-else-if="activeMenu === 'refset'" class="page-pad view-enter">
          <div class="add-bar">
            <el-button type="primary" @click="openRefSetDialog">+ 新建参考数据集</el-button>
            <span class="hint">可被规则 op=inlist / notinlist 引用的白名单 / 黑名单集合</span>
          </div>
          <el-dialog v-model="showRefSetDialog" title="新建参考数据集" width="560px">
            <el-form label-width="80px">
              <el-form-item label="数据集名"><el-input v-model="newRefSet.name" placeholder="如 vip_users" /></el-form-item>
              <el-form-item label="描述"><el-input v-model="newRefSet.description" placeholder="描述" /></el-form-item>
              <el-form-item label="初始条目">
                <el-input v-model="newRefSet.entries" type="textarea" :rows="4" placeholder="初始条目，逗号 / 换行分隔" />
              </el-form-item>
            </el-form>
            <template #footer><el-button @click="showRefSetDialog = false">取消</el-button><el-button type="success" @click="addRefSet(); showRefSetDialog = false">新建数据集</el-button></template>
          </el-dialog>
          <el-card shadow="never" v-for="rs in refSets" :key="rs.id" style="margin-bottom:12px">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
              <div>
                <span style="font-weight:600">{{ rs.name }}</span>
                <span style="color:#909399;font-size:12px;margin-left:8px">{{ rs.description || '—' }} · {{ rs.entries.length }} 条</span>
              </div>
              <el-button link type="danger" size="small" @click="removeRefSet(rs.id)">删除</el-button>
            </div>
            <div style="margin-bottom:8px">
              <el-tag v-for="(e, i) in rs.entries.slice(0, 40)" :key="i" size="small" style="margin:2px">{{ e }}</el-tag>
              <span v-if="rs.entries.length > 40" style="color:#909399;font-size:12px">… 等 {{ rs.entries.length }} 条</span>
            </div>
            <div style="display:flex;gap:8px">
              <el-input v-model="refEntryText[rs.id]" placeholder="追加条目" style="flex:1" @keyup.enter="doAddRefEntry(rs.id)" />
              <el-button size="small" @click="doAddRefEntry(rs.id)">追加</el-button>
            </div>
          </el-card>
          <PagerBar v-model:current-page="casePage" v-model:page-size="caseSize" :total="cases.length" />

        </div>

        <!-- 合规 (soc-base) -->
        <div v-else-if="activeMenu === 'compliance'" class="page-pad view-enter">
          <el-card shadow="never" style="margin-bottom:14px">
            <div style="display:flex;gap:20px;align-items:center;flex-wrap:wrap">
              <div>
                <div style="font-size:12px;color:#909399">整体控制项覆盖率</div>
                <div style="font-size:30px;font-weight:700;color:#409eff">{{ complianceCov ? complianceCov.coverage : '—' }}%</div>
              </div>
              <div>
                <div style="font-size:12px;color:#909399">已覆盖 / 总控制项</div>
                <div style="font-size:18px;font-weight:600">{{ complianceCov ? complianceCov.coveredControls : '—' }} / {{ complianceCov ? complianceCov.totalControls : '—' }}</div>
              </div>
              <el-button :loading="complianceLoading" @click="computeCompliance">重新计算</el-button>
            </div>
          </el-card>
          <el-card shadow="never" v-for="fw in (complianceCov ? complianceCov.byFramework : [])" :key="fw.framework" style="margin-bottom:12px">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
              <strong>{{ fw.framework }}</strong>
              <span style="font-weight:700;color:#409eff">{{ fw.coverage }}%</span>
            </div>
            <el-table :data="fw.controls" size="small">
              <el-table-column prop="id" label="控制项" width="120" />
              <el-table-column prop="name" label="名称" min-width="200" />
              <el-table-column label="覆盖" width="90"><template #default="{ row }"><el-tag :type="row.covered ? 'success' : 'danger'" size="small">{{ row.covered ? '已覆盖' : '缺失' }}</el-tag></template></el-table-column>
              <el-table-column prop="mappedRules" label="映射规则" min-width="160"><template #default="{ row }"><span style="font-size:12px;color:#909399">{{ (row.mappedRules || []).join(', ') || '—' }}</span></template></el-table-column>
            </el-table>
          </el-card>
        </div>
        <!-- 系统健康看板 -->
        <div v-else-if="activeMenu === 'health'" class="page-pad view-enter">
          <div style="display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:14px">
            <el-card shadow="never"><div class="stat-card"><div class="num" :style="{ color: healthUpCount === healthList.length ? '#16a34a' : '#dc2626' }">{{ healthUpCount }}/{{ healthList.length }}</div><div class="label">服务在线</div></div></el-card>
            <el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-accent-fg)">{{ healthEngine?.eventCount ?? 0 }}</div><div class="label">引擎累计事件</div></div></el-card>
            <el-card shadow="never"><div class="stat-card"><div class="num" style="color:#f56c6c">{{ healthEngine?.alertCount ?? 0 }}</div><div class="label">引擎累计告警</div></div></el-card>
            <el-card shadow="never"><div class="stat-card"><div class="num" style="color:#409eff">{{ healthIngest?.eps1m ?? 0 }}</div><div class="label">接入 EPS(1m)</div></div></el-card>
          </div>
          <el-card shadow="never">
            <template #header>
              <div style="display:flex;align-items:center;gap:10px">
                <span>服务健康（30s 自动刷新）</span>
                <span style="flex:1"></span>
                <el-button size="small" type="primary" @click="loadHealth">刷新</el-button>
              </div>
            </template>
            <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px">
              <div v-for="svc in healthList" :key="svc.name"
                   style="border:1px solid #e8eaee;border-radius:10px;padding:12px;display:flex;flex-direction:column;gap:6px">
                <span style="font-weight:600;font-size:13px">{{ svc.name }}</span>
                <span class="mono" style="font-size:11px;color:var(--ns-text-3)">{{ svc.path }}</span>
                <span :style="{ display:'inline-flex', alignItems:'center', gap:6, fontSize:12, color: svc.status === 'up' ? '#16a34a' : '#dc2626', fontWeight: 500 }">
                  <span :style="{ width:8, height:8, borderRadius:'50%', background: svc.status === 'up' ? '#16a34a' : '#dc2626' }"></span>
                  {{ svc.status === 'up' ? 'UP' : 'DOWN' }}
                </span>
              </div>
            </div>
          </el-card>
          <el-card shadow="never" style="margin-top:14px">
            <template #header>运维信息</template>
            <div style="font-size:12px;color:var(--ns-text-2);line-height:1.9">
              引擎队列负载 <b class="mono">{{ healthEngine?.queueLoad ?? 0 }}</b> · 丢弃 <b class="mono">{{ healthEngine?.dropCount ?? 0 }}</b> · 抑制 <b class="mono">{{ healthEngine?.suppressedCount ?? 0 }}</b> ·
              规则 <b class="mono">{{ healthEngine?.rules ?? 0 }}</b> 条<br>
              服务日志位于 <span class="mono">.cache/&lt;服务名&gt;.log</span>（运行目录）；Prometheus 指标：<span class="mono">/{服务名}/actuator/prometheus</span>
            </div>
          </el-card>
        </div>
      </main>
    </div>
  </div>
</template>
