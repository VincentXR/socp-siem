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
  riskScore?: number
  riskLevel?: string
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
export type ReportSource = 'clickhouse' | 'clickhouse+alert-web' | 'alert-web' | 'unspecified'
export interface ReportSummary {
  date: string; total: number
  bySeverity: Record<string, number>
  byRule: Array<{ rule: string; count: number }>
  source: ReportSource
  degraded: boolean
  freshness: string | null
  degradationReason: string | null
}
export interface ReportTrend {
  days: string[]
  counts: number[]
  source: ReportSource
  degraded: boolean
  freshness: string | null
  degradationReason: string | null
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

export interface AlarmPage { items: Alarm[]; total: number; page: number; size: number }
export type AlarmSortField = 'occurredAt' | 'severity' | 'ruleName' | 'entity' | 'status' | 'riskScore'
export type AlarmSortOrder = 'ascending' | 'descending'
export interface Disposition {
  status: string; assignee: string | null
  notes: Array<{ author: string; content: string; at: string }>
}
export interface AlarmEvidence {
  id: string
  eventId: string | null
  timestamp: string | null
  source: string | null
  host: string | null
  severity: string | null
  raw: string | null
  fields: Record<string, string>
  order: number
}
export interface AlarmEvidenceResponse {
  alarmId: string
  total: number
  complete: boolean
  query: string
  items: AlarmEvidence[]
}

export interface ParseRule {
  id: string; name: string; sourceId: string | null; format: string
  pattern: string | null
  mapping: Array<{ group: string; field: string | null; value: string | null }>
  setFields: Array<{ group: string; field: string | null; value: string | null }>
  enabled: boolean; order: number
}
export interface SinkTarget { id: string; name: string; type: string; uri: string; authToken: string | null; enabled: boolean }
export interface SearchEvent {
  eventId: string; timestamp: string; source: string; host: string; severity: string; msg: string
  fields: Record<string, string>; ecs?: Record<string, string>
}
export type SearchSource = 'opensearch' | 'local-cache' | 'unspecified'
export interface SearchResult {
  total: number
  events: SearchEvent[]
  stat: { type: string; rows: Array<{ key: string; count: number }> } | null
  source: SearchSource
  degraded: boolean
  freshness: string | null
  degradationReason: string | null
}

export interface RuleCondition { field: string; op: string; value: string }
export interface RuleSpec {
  id: string; name: string; type: string; severity: string; message?: string
  enabled: boolean; window?: string; keyField?: string; threshold?: number
  match?: RuleCondition[]; steps?: RuleCondition[][]; mitre?: string
}
export interface GasStats {
  rules: number; eventCount: number; alertCount: number; dropCount: number
  suppressedCount: number; queueLoad: number
}
export interface GasAlert {
  id: string; timestamp: string; ruleId: string; ruleName: string
  severity: string; message: string; entity: string; evidence?: unknown[]
}
export interface DetectionIngestResult { accepted: boolean; queueLoad: number; error?: string }
export interface DetectionIngestEvent {
  eventId?: string; timestamp?: string; source: string; host?: string; severity?: string
  msg?: string; raw?: string; fields?: Record<string, string>
}

export interface PlaybookActionResult {
  action: string; status: string; target?: string; httpStatus?: number
  costMs?: number; error?: string; reason?: string
}
export interface PlaybookExecution {
  executionId: string; playbookId: string; playbook: string; status: string
  trigger: string; retryCount: number; error?: string; ts: string
  results: PlaybookActionResult[]
}

export interface Ioc { id: string; type: string; value: string; severity: string; source: string; description: string; tags: string[] }
export interface Tactic { id: string; name: string; order: number }
export interface Technique { id: string; name: string; tactic: string; url: string; description: string }
export interface Channel { id: string; name: string; type: string; target: string; enabled: boolean; description: string }
export interface DispatchLogEntry {
  ts: string; channel: string; type: string; ruleId: string; status: string
  alarmId?: string; error?: string
}
export interface TimelineEvent { ts: string; type: string; message: string; source: string }
export interface CaseInfo {
  id: string; caseNo?: string; title: string; entity: string; severity: string; status: string
  ruleIds: string[]; alarmIds: string[]; timeline: TimelineEvent[]; assignee: string
  createdAt?: string; updatedAt?: string
}
export interface ReferenceSet { id: string; name: string; description: string; entries: string[] }

export interface RiskEntity {
  entity: string; risk: number; level: string; alerts: number; maxSeverity: string
  firstSeen: string; lastSeen: string
  mitre: Array<{ technique: string; count: number }>
  topRules: Array<{ rule: string; count: number }>
  critical: boolean
}
export interface RiskSummary { entities: number; byLevel: Record<string, number>; maxRisk: number; halfLifeHours: number }
export interface ScoreBreakdown { score: number; level: string; breakdown: Record<string, number> }
export interface Watchlist { name: string; size: number; values: string[] }

export interface TaskRuntime {
  accepted: number; skipped: number; forwarded: number; bytes: number
  eps1m: number; eps5m: number; firstAt: string | null; lastAt: string | null
  lastError: string | null; lastErrorAt?: string | null; health: string
}
export interface IngestTask {
  id: string; name: string; type: string | null; format: string | null
  enabled: boolean; collector: string; target: string; env: string | null
  tags: string[]; categoryId: string | null; sinkTargetId: string | null
  parseRuleIds: string[]; createdAt: string | null; runtime: TaskRuntime
}
export interface IngestSummary {
  collectors: number; accepted: number; skipped: number; forwarded: number
  bytes: number; eps1m: number; byHealth: Record<string, number>
  sources: number; enabledSources: number
}
export interface IngestTestResult {
  id?: string; collector?: string; sample?: string; ok: boolean
  pipeline?: Record<string, unknown>; error?: string
}
export interface AlarmStats {
  total: number; bySeverity: Record<string, number>; trend7d: Record<string, number>
  topRules: Array<{ ruleId: string; count: number }>; byRiskLevel: Record<string, number>
  avgRisk: number
  topRisk: Array<{ id: string; ruleName: string; entity: string; severity: string; mitre: string | null; riskScore: number; riskLevel: string }>
}
