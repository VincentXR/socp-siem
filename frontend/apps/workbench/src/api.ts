// SOCP 统一控制台 API 客户端（聚合所有后端）
import { unwrapApiBody } from './lib/api-response'
// 令牌：登录成功后存 localStorage；未登录返回空串（强制验签下任何兜底 token 都 401）
export function getToken(): string {
  try { return localStorage.getItem('socp_token') || '' } catch { return '' }
}
export function setToken(t: string): void { try { localStorage.setItem('socp_token', t) } catch { /* ignore */ } }
export function clearToken(): void { try { localStorage.removeItem('socp_token') } catch { /* ignore */ } }

/** 网关登录：签发 JWT 并持久化；失败抛错 */
export async function login(username: string, password: string): Promise<{ token: string; username: string; role: string; tenant: string; expiresIn: number }> {
  const res = await fetch('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const data = await res.json()
  if (!res.ok) throw new Error(data?.message || '登录失败')
  setToken(data.token)
  return data
}

// ---------- 类型 ----------
export interface Alarm {
  id: string
  ruleId: string
  ruleName: string
  severity: string
  message: string
  entity: string
  status: string
  occurredAt: string
  mitre?: string
  tiHits?: string
}
export interface LogSource {
  id: string; name: string; type: string; format: string
  path: string | null; address: string | null; topic: string | null
  env: string | null; enabled: boolean; createdAt: string
  readFrom?: string | null; multiline?: string | null
  sinkTargetId?: string | null; parseRuleIds?: string[]; description?: string | null
  protocol?: string | null; charset?: string | null; timeField?: string | null
  timezone?: string | null; tags?: string[]; frequency?: number | null
  categoryId?: string | null; groupId?: string | null
}
export interface DataSourceType { id: string; code: string; name: string; description: string; enabled: boolean; createdAt: string }
export interface LogCategory { id: string; code: string; name: string; description: string; defaultSeverity: string; enabled: boolean; createdAt: string }
export interface FieldDef {
  id: string; fieldName: string; fieldLabel: string; fieldType: string
  source: string; searchable: boolean; aggregatable: boolean; stored: boolean; description: string
}
export interface Playbook {
  id: string; name: string; trigger: string
  actions: string[]; enabled: boolean; status: string
}
export interface ReportSummary {
  date: string; total: number
  bySeverity: Record<string, number>
  byRule: Array<{ rule: string; count: number }>
}
export interface Asset {
  id: string; name: string; type: string; ip: string
  os: string; owner: string; criticality: string
}
export interface Endpoint {
  id: string; hostname: string; ip: string; os: string
  agentVersion: string; status: string; lastHeartbeat: string
}
export interface TenantInfo {
  id: string; name: string; code: string
  userCount: number; alarmCount: number
}
export interface AiResult {
  question: string; answer: string; suggestion: string | null; elapsedMs: number
}

export const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const
export const SOURCE_TYPES = ['FILE', 'SOCKET', 'SYSLOG', 'KAFKA', 'WINDOWS_EVENT', 'AGENT', 'HTTP_API', 'DATABASE', 'CLOUD'] as const
export const PARSE_FORMATS = ['AUTO', 'SYSLOG', 'JSON', 'KV', 'CEF', 'LEEF'] as const

// ---------- 通用请求 ----------
const authHeader = (): string => `Bearer ${getToken()}`

/** 401 处理（2026-08-13）：token 过期/失效时统一清 token 并通知 App 登出，
 *  否则页面停留在"已登录"态但所有请求 401，用户无法触发重新登录。 */
let unauthorizedHandler: (() => void) | null = null
export function setUnauthorizedHandler(fn: (() => void) | null): void { unauthorizedHandler = fn }

function assertOk(res: Response): void {
  if (res.status === 401) {
    clearToken()
    const h = unauthorizedHandler
    if (h) h()
    throw new Error('登录已过期，请重新登录')
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (!headers.has('Accept')) headers.set('Accept', 'application/json')
  if (!headers.has('Authorization')) headers.set('Authorization', authHeader())
  const res = await fetch(path, { ...init, headers })
  assertOk(res)
  return unwrapApiBody<T>(await res.json())
}

async function get<T>(path: string): Promise<T> {
  return requestJson<T>(path)
}

async function post<T>(path: string, data?: unknown): Promise<T> {
  return requestJson<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: data === undefined ? undefined : JSON.stringify(data),
  })
}

async function put<T>(path: string, data?: unknown): Promise<T> {
  return requestJson<T>(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: data === undefined ? undefined : JSON.stringify(data),
  })
}

async function del<T>(path: string): Promise<T> {
  return requestJson<T>(path, { method: 'DELETE' })
}

// ---------- ALERT 告警 ----------
export const listAlarms = (q?: string) => get<Alarm[]>(`/alert-web/api/alarms${q ? `?q=${q}` : ''}`)
/** 分页查询告警（后端真分页：page 从 1 起，size 默认 20） */
export interface AlarmPage { items: Alarm[]; total: number; page: number; size: number }
export const listAlarmsPaged = (page: number, size: number, q?: string, severity?: string) =>
  get<AlarmPage>(`/alert-web/api/alarms?page=${page}&size=${size}${q ? `&q=${encodeURIComponent(q)}` : ''}${severity ? `&severity=${severity}` : ''}`)
export const createAlarm = (a: Partial<Alarm>) => post<Alarm>('/alert-web/api/alarms', a)
export interface Disposition {
  status: string; assignee: string | null
  notes: Array<{ author: string; content: string; at: string }>
}
export const getDisposition = (id: string) => get<Disposition>(`/alert-web/api/alarms/${id}/disposition`)
export const setDispositionStatus = (id: string, status: string) =>
  put<Disposition>(`/alert-web/api/alarms/${id}/status`, { status })
export const assignAlarm = (id: string, assignee: string) =>
  post<Disposition>(`/alert-web/api/alarms/${id}/assign`, { assignee })
export const addAlarmNote = (id: string, content: string, author = 'operator') =>
  post<Disposition>(`/alert-web/api/alarms/${id}/notes`, { content, author })

// ---------- SEARCH 采集 ----------
export interface ParseRule {
  id: string; name: string; sourceId: string | null; format: string
  pattern: string | null
  mapping: Array<{ group: string; field: string | null; value: string | null }>
  setFields: Array<{ group: string; field: string | null; value: string | null }>
  enabled: boolean; order: number
}
export interface SinkTarget { id: string; name: string; type: string; uri: string; authToken: string | null; enabled: boolean }
export const listSources = () => get<LogSource[]>('/search-config/api/v1/sources')
export const createSource = (s: Record<string, unknown>) => post<LogSource>('/search-config/api/v1/sources', s)
export const deleteSource = (id: string) => del(`/search-config/api/v1/sources/${id}`)
export const renderConfig = () => post<string>('/search-config/api/v1/render')
export const listParseRules = () => get<ParseRule[]>('/search-config/api/v1/parse-rules')
export const createParseRule = (r: Partial<ParseRule>) => post<ParseRule>('/search-config/api/v1/parse-rules', r)
export const deleteParseRule = (id: string) => del(`/search-config/api/v1/parse-rules/${id}`)
export const previewParse = (body: { ruleId?: string; format?: string; pattern?: string; line: string }) =>
  post<{ matched: boolean; fields: Record<string, string>; error?: string; rule?: string; format?: string }>('/search-config/api/v1/parse-rules/preview', body)
export const listOutputs = () => get<SinkTarget[]>('/search-config/api/v1/outputs')
export const createOutput = (o: Partial<SinkTarget>) => post<SinkTarget>('/search-config/api/v1/outputs', o)
export const deleteOutput = (id: string) => del(`/search-config/api/v1/outputs/${id}`)

// ---------- SEARCH 元数据管理 ----------
export const listDataSourceTypes = () => get<DataSourceType[]>('/search-config/api/v1/meta/data-source-types')
export const createDataSourceType = (t: Partial<DataSourceType>) => post<DataSourceType>('/search-config/api/v1/meta/data-source-types', t)
export const deleteDataSourceType = (id: string) => del(`/search-config/api/v1/meta/data-source-types/${id}`)
export const listCategories = () => get<LogCategory[]>('/search-config/api/v1/meta/categories')
export const createCategory = (c: Partial<LogCategory>) => post<LogCategory>('/search-config/api/v1/meta/categories', c)
export const deleteCategory = (id: string) => del(`/search-config/api/v1/meta/categories/${id}`)
export const listFields = () => get<FieldDef[]>('/search-config/api/v1/meta/fields')
export const createField = (f: Partial<FieldDef>) => post<FieldDef>('/search-config/api/v1/meta/fields', f)
export const deleteField = (id: string) => del(`/search-config/api/v1/meta/fields/${id}`)

// ---------- SPL 检索 ----------
export interface SearchEvent {
  timestamp: string; source: string; host: string; severity: string; msg: string
  fields: Record<string, string>
}
export interface SearchResult {
  total: number
  events: SearchEvent[]
  stat: { type: string; rows: Array<{ key: string; count: number }> } | null
}
export const splSearch = (q: string) => get<SearchResult>(`/search-config/api/v1/search?q=${encodeURIComponent(q)}`)

// ---------- DETECT 检测 ----------
export const listRules = () => get<unknown[]>('/detect-web/api/v1/rules')
export const createGasRule = (spec: Record<string, unknown>) => post<Record<string, unknown>>('/detect-web/api/v1/rules', spec)
export const updateGasRule = (id: string, spec: Record<string, unknown>) => put<Record<string, unknown>>(`/detect-web/api/v1/rules/${id}`, spec)
export const deleteGasRule = (id: string) => del(`/detect-web/api/v1/rules/${id}`)
export const gasStats = () => get<Record<string, unknown>>('/detect-web/api/v1/stats')
export const gasAlerts = () => get<unknown[]>('/detect-web/api/v1/alerts')
export const gasIngest = (ev: unknown) => post<unknown>('/detect-web/api/v1/ingest', ev)

// ---------- SOAR 编排 ----------
export const listPlaybooks = () => get<Playbook[]>('/soar-web/api/v1/playbooks')
export const listPlaybookExecutions = () => get<Array<Record<string, unknown>>>('/soar-web/api/v1/playbooks/executions')
export const createPlaybook = (p: { name: string; trigger: string; actions: string[]; enabled: boolean }) =>
  post<Playbook>('/soar-web/api/v1/playbooks', p)
export const deletePlaybook = (id: string) => del(`/soar-web/api/v1/playbooks/${id}`)
export const togglePlaybook = (id: string) => post<Playbook>(`/soar-web/api/v1/playbooks/${id}/toggle`)

// ---------- REPORT 报表 ----------
export const dailyReport = () => get<ReportSummary>('/report-web/api/v1/reports/daily')
export const trend7d = () => get<{ days: string[]; counts: number[] }>('/report-web/api/v1/reports/trend7d')

// ---------- ASSET 资产 ----------
export const listAssets = () => get<Asset[]>('/asset-web/api/v1/assets')
export const createAsset = (a: Partial<Asset>) => post<Asset>('/asset-web/api/v1/assets', a)
export const deleteAsset = (id: string) => del(`/asset-web/api/v1/assets/${id}`)
export const assetStats = () => get<{ total: number; byType: Record<string, number>; byCriticality: Record<string, number> }>('/asset-web/api/v1/assets/stats')

// ---------- SOC 底座 ----------
export const listTenants = () => get<TenantInfo[]>('/soc-base/api/v1/tenants')
export const socOverview = () => get<Record<string, unknown>>('/soc-base/api/v1/overview')

// ---------- HIPS 端点 ----------
export const listEndpoints = () => get<Endpoint[]>('/hips-web/api/v1/endpoints')
export const endpointStats = () => get<{ total: number; online: number; byType: Record<string, number> }>('/hips-web/api/v1/endpoints/stats')

// ---------- REPORT 归档（MinIO） ----------
export const archiveReport = () => post<{ archived: boolean; day?: string; dailyKey?: string; error?: string }>('/report-web/api/v1/reports/archive')
export const listArchive = (prefix = 'reports/') => get<{ prefix: string; count: number; objects: Array<{ key: string; size: number }> }>(`/report-web/api/v1/reports/archive?prefix=${encodeURIComponent(prefix)}`)
export const deleteEndpoint = (id: string) => del(`/hips-web/api/v1/endpoints/${id}`)

// ---------- 威胁情报 (threat-web) ----------
export interface Ioc { id: string; type: string; value: string; severity: string; source: string; description: string; tags: string[] }
export const listIocs = (type?: string) => get<Ioc[]>(`/threat-web/api/v1/iocs${type ? `?type=${type}` : ''}`)
export const createIoc = (i: { type: string; value: string; severity?: string; source?: string; description?: string; tags?: string[] }) =>
  post<Ioc>('/threat-web/api/v1/iocs', i)
export const deleteIoc = (id: string) => del(`/threat-web/api/v1/iocs/${id}`)
export const tiMatch = (value: string) => get<{ value: string; matched: boolean; ioc?: Ioc }>(`/threat-web/api/v1/iocs/match?value=${encodeURIComponent(value)}`)
export const tiStats = () => get<{ total: number; byType: Record<string, number> }>('/threat-web/api/v1/stats')

// ---------- MITRE ATT&CK (attack-web) ----------
export interface Technique { id: string; name: string; tactic: string; url: string; description: string }
export const listTactics = () => get<unknown[]>('/attack-web/api/v1/tactics')
export const listTechniques = (tactic?: string) => get<Technique[]>(`/attack-web/api/v1/techniques${tactic ? `?tactic=${tactic}` : ''}`)
export const attackCoverage = (ruleTechs: string[]) => post<{
  byTactic: Array<{ tactic: string; name: string; total: number; covered: number; coverage: number }>
  totalTechniques: number; coveredTechniques: number; coverage: number; uncovered: string[]
}>('/attack-web/api/v1/coverage', { ruleTechniques: ruleTechs })

// ---------- 通知集成 (notify-web) ----------
export interface Channel { id: string; name: string; type: string; target: string; enabled: boolean; description: string }
export const listChannels = () => get<Channel[]>('/notify-web/api/v1/channels')
export const createChannel = (c: { name: string; type: string; target: string; enabled?: boolean; description?: string }) =>
  post<Channel>('/notify-web/api/v1/channels', c)
export const deleteChannel = (id: string) => del(`/notify-web/api/v1/channels/${id}`)
export const toggleChannel = (id: string) => post<{ channel: Channel }>(`/notify-web/api/v1/channels/${id}/toggle`)
export const dispatchLog = () => get<unknown[]>('/notify-web/api/v1/dispatch-log')

// ---------- 案件/时间线 (incident-web) ----------
export interface TimelineEvent { ts: string; type: string; message: string; source: string }
export interface CaseInfo {
  id: string; title: string; entity: string; severity: string; status: string
  ruleIds: string[]; alarmIds: string[]; timeline: TimelineEvent[]; assignee: string
}
export const listCases = () => get<CaseInfo[]>('/incident-web/api/v1/incidents')
export const caseTimeline = (id: string) => get<{ caseId: string; timeline: TimelineEvent[] }>(`/incident-web/api/v1/incidents/${id}/timeline`)
export const setCaseStatus = (id: string, status: string, assignee?: string) =>
  post<{ case: CaseInfo }>(`/incident-web/api/v1/incidents/${id}/status?status=${status}${assignee ? `&assignee=${assignee}` : ''}`)
export const caseStats = () => get<{ total: number; open: number; resolved: number }>('/incident-web/api/v1/stats')

// ---------- 查找表 (search-config) ----------
export interface ReferenceSet { id: string; name: string; description: string; entries: string[] }
export const listRefSets = () => get<ReferenceSet[]>('/search-config/api/v1/reference-sets')
export const createRefSet = (r: { name: string; description?: string; entries: string[] }) =>
  post<ReferenceSet>('/search-config/api/v1/reference-sets', r)
export const deleteRefSet = (id: string) => del(`/search-config/api/v1/reference-sets/${id}`)
export const addRefEntry = (id: string, value: string) =>
  post<{ ok: boolean; size: number }>(`/search-config/api/v1/reference-sets/${id}/entries`, { value })

// ---------- 合规模板 (soc-base) ----------
export const complianceFrameworks = () => get<{ frameworks: Array<{ name: string; controls: Array<{ id: string; name: string; ruleIds: string[] }> }> }>('/soc-base/api/v1/compliance/frameworks')
export const complianceCoverage = (ruleIds: string[]) => post<{
  byFramework: Array<{ framework: string; controls: Array<{ id: string; name: string; covered: boolean; mappedRules: string[] }>; coverage: number }>
  totalControls: number; coveredControls: number; coverage: number
}>('/soc-base/api/v1/compliance/coverage', { ruleIds })

// ---------- UEBA / 威胁评分 / 观察名单 (detect-web) ----------
export interface RiskEntity {
  entity: string
  risk: number
  level: string
  alerts: number
  maxSeverity: string
  firstSeen: string
  lastSeen: string
  mitre: Array<{ technique: string; count: number }>
  topRules: Array<{ rule: string; count: number }>
  critical: boolean
}
export interface RiskSummary {
  entities: number
  byLevel: Record<string, number>
  maxRisk: number
  halfLifeHours: number
}
export interface ScoreBreakdown {
  score: number
  level: string
  breakdown: Record<string, number>
}
export interface Watchlist { name: string; size: number; values: string[] }

export const uebaEntities = (limit = 20) => get<RiskEntity[]>(`/detect-web/api/v1/ueba/entities?limit=${limit}`)
export const uebaEntity = (entity: string) => get<RiskEntity>(`/detect-web/api/v1/ueba/entities/${encodeURIComponent(entity)}`)
export const uebaSummary = () => get<RiskSummary>('/detect-web/api/v1/ueba/summary')
export const uebaScore = (p: { severity: string; mitre?: string; tiHits?: number; recentAlerts?: number; assetCriticality?: number }) => {
  const qs = new URLSearchParams({
    severity: p.severity,
    tiHits: String(p.tiHits ?? 0),
    recentAlerts: String(p.recentAlerts ?? 0),
    assetCriticality: String(p.assetCriticality ?? 0),
  })
  if (p.mitre) qs.set('mitre', p.mitre)
  return get<ScoreBreakdown>(`/detect-web/api/v1/ueba/score?${qs.toString()}`)
}
export const listWatchlists = () => get<Watchlist[]>('/detect-web/api/v1/watchlists')
export const putWatchlist = (name: string, values: string[]) => put<Watchlist>(`/detect-web/api/v1/watchlists/${encodeURIComponent(name)}`, values)
export const appendWatchlist = (name: string, values: string[]) => post<Watchlist>(`/detect-web/api/v1/watchlists/${encodeURIComponent(name)}`, values)
export const deleteWatchlist = (name: string) => del<{ removed: boolean }>(`/detect-web/api/v1/watchlists/${encodeURIComponent(name)}`)

// ---------- 接入任务 (search-config) ----------
export interface TaskRuntime {
  accepted: number; skipped: number; forwarded: number; bytes: number
  eps1m: number; eps5m: number
  firstAt: string | null; lastAt: string | null
  lastError: string | null; lastErrorAt?: string | null
  health: string
}
export interface IngestTask {
  id: string; name: string; type: string | null; format: string | null
  enabled: boolean; collector: string; target: string; env: string | null
  tags: string[]; categoryId: string | null; sinkTargetId: string | null
  parseRuleIds: string[]; createdAt: string | null
  runtime: TaskRuntime
}
export interface IngestSummary {
  collectors: number; accepted: number; skipped: number; forwarded: number
  bytes: number; eps1m: number; byHealth: Record<string, number>
  sources: number; enabledSources: number
}
export const listIngestTasks = () => get<IngestTask[]>('/search-config/api/v1/ingest/tasks')
export const ingestSummary = () => get<IngestSummary>('/search-config/api/v1/ingest/tasks/summary')
export const startIngestTask = (id: string) => post<{ id: string; enabled: boolean; task: IngestTask }>(`/search-config/api/v1/ingest/tasks/${id}/start`)
export const stopIngestTask = (id: string) => post<{ id: string; enabled: boolean; task: IngestTask }>(`/search-config/api/v1/ingest/tasks/${id}/stop`)
export const testIngestTask = (id: string, sample?: string) =>
  post<{ id: string; collector: string; sample: string; ok: boolean; pipeline: Record<string, unknown> }>(
    `/search-config/api/v1/ingest/tasks/${id}/test`, sample ? { sample } : {})

// ---------- 态势大屏聚合 ----------
export interface AlarmStats {
  total: number
  bySeverity: Record<string, number>
  trend7d: Record<string, number>
  topRules: Array<{ ruleId: string; count: number }>
  byRiskLevel: Record<string, number>
  avgRisk: number
  topRisk: Array<{ id: string; ruleName: string; entity: string; severity: string; mitre: string | null; riskScore: number; riskLevel: string }>
}
export const alarmStats = () => get<AlarmStats>('/alert-web/api/alarms/stats')

// ---------- 归档导出 ----------
function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
async function downloadFile(path: string, filename: string): Promise<void> {
  const res = await fetch(path, { headers: { Authorization: authHeader() } })
  assertOk(res)
  downloadBlob(await res.blob(), filename)
}
export const exportAlarms = (format = 'csv') =>
  downloadFile(`/alert-web/api/alarms/export?format=${format}`, `alarms.${format}`)
export const exportCases = () =>
  downloadFile('/incident-web/api/v1/incidents/export', 'cases.json')
export const exportSearch = (q: string, format = 'json') =>
  downloadFile(`/search-config/api/v1/search/export?q=${encodeURIComponent(q)}&format=${format}`, `search.${format}`)

export interface GasAlert {
  id: string; timestamp: string; ruleId: string; ruleName: string
  severity: string; message: string; entity: string
  evidence?: unknown[]
}
export const gasRecentAlerts = () => get<GasAlert[]>('/detect-web/api/v1/alerts')
export interface GasStats { rules: number; eventCount: number; alertCount: number; dropCount: number; suppressedCount: number; queueLoad: number }
export const gasEngineStats = () => get<GasStats>('/detect-web/api/v1/stats')

// ---------- AI 助手 ----------
export const aiAsk = (question: string) => post<AiResult>('/ai-assistant/api/v1/ai/ask', { question })

// ---------- 健康检查 ----------
export async function checkHealth(path: string): Promise<'up' | 'down'> {
  try {
    // 必须带 token：网关对路由到业务服务的 /actuator/health 也要求 Bearer（网关自身 /actuator 例外）
    const res = await fetch(path, { headers: { Authorization: authHeader() }, signal: AbortSignal.timeout(3000) })
    if (!res.ok) return 'down'
    const body = await res.json()
    return String(body?.status ?? '').toUpperCase() === 'UP' ? 'up' : 'down'
  } catch { return 'down' }
}
export const HEALTH_TARGETS = [
  { name: 'alert-web', path: '/alert-web/actuator/health' },
  { name: 'search-config', path: '/search-config/actuator/health' },
  { name: 'detect-web', path: '/detect-web/actuator/health' },
  { name: 'detect-model', path: '/detect-model/actuator/health' },
  { name: 'soar-web', path: '/soar-web/actuator/health' },
  { name: 'report-web', path: '/report-web/actuator/health' },
  { name: 'asset-web', path: '/asset-web/actuator/health' },
  { name: 'soc-base', path: '/soc-base/actuator/health' },
  { name: 'hips-web', path: '/hips-web/actuator/health' },
  { name: 'ai-assistant', path: '/ai-assistant/actuator/health' },
  { name: 'threat-web', path: '/threat-web/actuator/health' },
  { name: 'attack-web', path: '/attack-web/actuator/health' },
  { name: 'notify-web', path: '/notify-web/actuator/health' },
  { name: 'incident-web', path: '/incident-web/actuator/health' },
  { name: 'api-gateway', path: '/actuator/health' },
]
