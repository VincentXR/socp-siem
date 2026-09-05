import { del, get, patch, post, put } from './core'
import type { Playbook, PlaybookExecution } from './models'

export const listPlaybooks = () => get<Playbook[]>('/soar-web/api/v1/playbooks')
export const listPlaybookExecutions = () => get<PlaybookExecution[]>('/soar-web/api/v1/playbooks/executions')
export const createPlaybook = (p: { name: string; trigger: string; actions: string[]; enabled: boolean }) => post<Playbook>('/soar-web/api/v1/playbooks', p)
export const deletePlaybook = (id: string) => del(`/soar-web/api/v1/playbooks/${encodeURIComponent(id)}`)
export const togglePlaybook = (id: string) => post<Playbook>(`/soar-web/api/v1/playbooks/${encodeURIComponent(id)}/toggle`)

export interface SoarV2Playbook {
  id: string; name: string; description?: string; owner?: string; status: string
  latestPublishedVersion?: number | null; draftVersion?: number | null
  tags: string[]; createdAt?: string; updatedAt?: string
}
export interface SoarV2Version {
  id: string; playbookId: string; version: number; status: string; schemaVersion: string
  playbookStatus?: string; definition: unknown; layout: unknown; definitionHash: string; riskSummary: Record<string, unknown>
  rowVersion?: number; createdAt?: string; publishedAt?: string
}
export interface SoarV2Run {
  runId: string; requestId: string; playbookId: string; playbookVersionId: string
  playbookVersion: number; status: string; triggerType: string; definitionHash: string
  temporalWorkflowId?: string; temporalRunId?: string; errorCode?: string; errorMessage?: string
  createdAt?: string; startedAt?: string; completedAt?: string
}
export interface SoarV2Approval {
  id: string; runId: string; approvalKey?: string; nodeRunId?: string; actionRef?: string
  inputHash?: string; targetSnapshot?: unknown; requiredApprovals?: number; approvedVotes?: number
  decisions?: Array<{ id: string; actor: string; decision: string; reason?: string; createdAt?: string }>
  status: string; requestedBy: string; approver?: string
  reason?: string; decisionReason?: string; createdAt?: string; expiresAt?: string; decidedAt?: string
}
export interface SoarV2Artifact {
  id: string; runId: string; nodeRunId?: string; mediaType: string; sizeBytes: number
  sha256: string; storageRef: string; classification: string; expiresAt?: string; createdAt?: string
}
export interface SoarV2NodeRun {
  id: string; runId: string; nodeId: string; iterationPath?: string; nodeType: string; status: string
  input?: unknown; output?: unknown; idempotencyKey?: string; errorCode?: string; errorMessage?: string
  startedAt?: string; completedAt?: string; updatedAt?: string
}
export interface SoarV2Attempt {
  id: string; nodeRunId: string; attemptNo: number; status: string; requestHash?: string
  remoteOperationId?: string; receipt?: unknown; errorCode?: string; errorMessage?: string
  retryable?: boolean; startedAt?: string; completedAt?: string; createdAt?: string
}
export interface SoarV2Event {
  id: string; runId: string; nodeRunId?: string; sequence: number; eventType: string; actor?: string
  summary: string; detail?: unknown; traceId?: string; createdAt?: string
}
export interface SoarV2Page<T> { page: number; size: number; total: number; items: T[] }
export interface SoarV2Template {
  id: string; version: number; name: string; description: string; eventTypes: string[]
  requiredConnectors: string[]; risk: string; attackTags: string[]
}

export const listV2Playbooks = (page = 0, size = 20) =>
  get<SoarV2Page<SoarV2Playbook>>(`/soar-web/api/v2/playbooks?page=${page}&size=${size}`)
export const createV2Playbook = (p: { name: string; description?: string; tags?: string[] }) =>
  post<SoarV2Playbook>('/soar-web/api/v2/playbooks', p)
export const importV2Playbook = (p: { name: string; description?: string; tags?: string[]; definition: unknown; layout?: unknown }) =>
  post<SoarV2Version & { imported?: boolean }>('/soar-web/api/v2/playbooks/import', p)
export const listV2Runs = (page = 0, size = 20) =>
  get<SoarV2Page<SoarV2Run>>(`/soar-web/api/v2/runs?page=${page}&size=${size}`)
export const queueV2Run = (p: { requestId: string; playbookVersionId: string; subject?: Record<string, unknown>; inputs?: Record<string, unknown> }) =>
  post<SoarV2Run>('/soar-web/api/v2/runs', p)
export const cancelV2Run = (id: string, reason?: string) =>
  post<SoarV2Run>(`/soar-web/api/v2/runs/${encodeURIComponent(id)}/cancel`, { reason })
export const listV2Approvals = () => get<SoarV2Approval[]>('/soar-web/api/v2/approvals')
export const approveV2 = (id: string, reason?: string) => post<SoarV2Approval>(`/soar-web/api/v2/approvals/${encodeURIComponent(id)}/approve`, { reason })
export const rejectV2 = (id: string, reason?: string) => post<SoarV2Approval>(`/soar-web/api/v2/approvals/${encodeURIComponent(id)}/reject`, { reason })
export const listV2Templates = () => get<SoarV2Template[]>('/soar-web/api/v2/templates')
export const installV2Template = (id: string) => post(`/soar-web/api/v2/templates/${encodeURIComponent(id)}/install`)
export const retryV2Run = (id: string, reason?: string) => post<SoarV2Run>(`/soar-web/api/v2/runs/${encodeURIComponent(id)}/retry`, { reason })
export const rerunV2Run = (id: string, reason?: string) => post<SoarV2Run>(`/soar-web/api/v2/runs/${encodeURIComponent(id)}/rerun`, { reason, confirm: true })
export const getV2DefinitionSchema = () => get<Record<string, unknown>>('/soar-web/api/v2/definition-schema')
export const validateV2Version = (playbookId: string, version: number) =>
  post<Record<string, unknown>>(`/soar-web/api/v2/playbooks/${encodeURIComponent(playbookId)}/versions/${version}/validate`)
export const dryRunV2Version = (playbookId: string, version: number, subject?: Record<string, unknown>, inputs?: Record<string, unknown>) =>
  post<Record<string, unknown>>(`/soar-web/api/v2/playbooks/${encodeURIComponent(playbookId)}/versions/${version}/dry-run`, { subject, inputs })
export const getV2Playbook = (id: string) => get<SoarV2Playbook>(`/soar-web/api/v2/playbooks/${encodeURIComponent(id)}`)
export const updateV2Playbook = (id: string, changes: { name?: string; description?: string; tags?: string[]; status?: 'ACTIVE' | 'ARCHIVED'; rowVersion?: number }) =>
  patch<SoarV2Playbook>(`/soar-web/api/v2/playbooks/${encodeURIComponent(id)}`, changes)
export const listV2Versions = (playbookId: string) => get<SoarV2Version[]>(`/soar-web/api/v2/playbooks/${encodeURIComponent(playbookId)}/versions`)
export const createV2Version = (playbookId: string) => post<SoarV2Version>(`/soar-web/api/v2/playbooks/${encodeURIComponent(playbookId)}/versions`)
export const createV2Draft = (playbookId: string) => post<SoarV2Version>(`/soar-web/api/v2/playbooks/${encodeURIComponent(playbookId)}/drafts`)
export const getV2Version = (playbookId: string, version: number) =>
  get<SoarV2Version>(`/soar-web/api/v2/playbooks/${encodeURIComponent(playbookId)}/versions/${version}`)
export const saveV2Version = (playbookId: string, version: number, definition: unknown, layout?: unknown, rowVersion?: number) =>
  put<SoarV2Version>(`/soar-web/api/v2/playbooks/${encodeURIComponent(playbookId)}/versions/${version}`, { definition, layout, rowVersion })
export const publishV2Version = (playbookId: string, version: number) =>
  post<SoarV2Version>(`/soar-web/api/v2/playbooks/${encodeURIComponent(playbookId)}/versions/${version}/publish`)
export const deprecateV2Version = (playbookId: string, version: number) =>
  post<SoarV2Version>(`/soar-web/api/v2/playbooks/${encodeURIComponent(playbookId)}/versions/${version}/deprecate`)
export const exportV2Version = (playbookId: string, version: number) =>
  get<SoarV2Version & { format?: string; exportedAt?: string }>(`/soar-web/api/v2/playbooks/${encodeURIComponent(playbookId)}/versions/${version}/export`)
export const getV2Run = (id: string) => get<SoarV2Run>(`/soar-web/api/v2/runs/${encodeURIComponent(id)}`)
export const listV2Nodes = (runId: string) => get<SoarV2NodeRun[]>(`/soar-web/api/v2/runs/${encodeURIComponent(runId)}/nodes`)
export const listV2NodeAttempts = (nodeRunId: string, page = 0, size = 20) =>
  get<SoarV2Page<SoarV2Attempt>>(`/soar-web/api/v2/node-runs/${encodeURIComponent(nodeRunId)}/attempts?page=${page}&size=${size}`)
export const listV2Events = (runId: string, after = 0, page = 0, size = 100) =>
  get<SoarV2Page<SoarV2Event>>(`/soar-web/api/v2/runs/${encodeURIComponent(runId)}/events?after=${after}&page=${page}&size=${size}`)
export const listV2Artifacts = (runId: string) => get<SoarV2Artifact[]>(`/soar-web/api/v2/runs/${encodeURIComponent(runId)}/artifacts`)
export const uploadV2Artifact = (runId: string, content: unknown, options?: { nodeRunId?: string; mediaType?: string; classification?: string }) => {
  const params = new URLSearchParams()
  if (options?.nodeRunId) params.set('nodeRunId', options.nodeRunId)
  if (options?.mediaType) params.set('mediaType', options.mediaType)
  if (options?.classification) params.set('classification', options.classification)
  const suffix = params.toString() ? `?${params.toString()}` : ''
  return post<SoarV2Artifact>(`/soar-web/api/v2/runs/${encodeURIComponent(runId)}/artifacts${suffix}`, content)
}
export const getV2Artifact = (id: string) => get<SoarV2Artifact>(`/soar-web/api/v2/artifacts/${encodeURIComponent(id)}`)
export const getV2ArtifactContent = (id: string) => get<unknown>(`/soar-web/api/v2/artifacts/${encodeURIComponent(id)}/content`)
export const resolveV2Unknown = (nodeRunId: string, resolution: 'CONFIRMED_SUCCEEDED' | 'CONFIRMED_NOT_EXECUTED', evidence: string, reason: string) =>
  post<Record<string, unknown>>(`/soar-web/api/v2/node-runs/${encodeURIComponent(nodeRunId)}/resolve-unknown`, { resolution, evidence, reason })
export const listV2ManualTasks = (pendingOnly = true) => get<Record<string, unknown>[]>(`/soar-web/api/v2/manual-tasks?pendingOnly=${pendingOnly}`)
export const completeV2ManualTask = (id: string, input: Record<string, unknown>) =>
  post<Record<string, unknown>>(`/soar-web/api/v2/manual-tasks/${encodeURIComponent(id)}/complete`, input)

export interface SoarV2AutomationRule {
  id: string; name: string; triggerType: string; priority: number; enabled: boolean
  conditions: unknown; actions: unknown; suppression: unknown; revision?: number
  dedupWindowSeconds?: number; cooldownSeconds?: number; groupBy?: string
  maxConcurrentRuns?: number; conflictStrategy?: string; validFrom?: string; validUntil?: string
  createdAt?: string; updatedAt?: string; rowVersion?: number
}
export interface SoarV2Connection {
  id: string; name: string; connectorType: string; endpoint: string; authSecretRef?: string
  allowedHosts: string[]; enabled: boolean; status: string; revision?: number
  lastTestAt?: string; lastTestStatus?: string; lastTestError?: string; rowVersion?: number
  createdAt?: string; updatedAt?: string
}
export interface SoarV2ActionDescriptor {
  connectorId: string; connectorVersion: number; production: boolean; actionRef: string; id: string
  displayName: string; description?: string; riskLevel: string; sideEffect: string
  idempotency: string; requiresConnection: boolean; requiredPermissions?: string[]
  inputSchema?: unknown; outputSchema?: unknown
}
export interface SoarV2ManualTask {
  id: string; runId: string; nodeId: string; formSchema: unknown; input?: unknown
  assignee?: string; status: string; dueAt?: string; completedBy?: string; completedAt?: string
  createdAt?: string
}
export interface SoarV2DeadLetter {
  id: string; runId: string; kind?: string; signalType?: string; signalKey?: string
  status: string; attempts: number; lastError?: string; updatedAt?: string
}
export interface SoarV2Stats {
  runsByStatus: Record<string, number>; dispatchBacklog: number; signalBacklog: number; generatedAt?: string
}

export const listV2AutomationRules = (page = 0, size = 50) =>
  get<SoarV2Page<SoarV2AutomationRule>>(`/soar-web/api/v2/automation-rules?page=${page}&size=${size}`)
export const createV2AutomationRule = (rule: {
  name: string; triggerType: string; priority?: number; enabled?: boolean
  conditions?: unknown; actions: unknown; suppression?: unknown; rowVersion?: number
}) => post<SoarV2AutomationRule>('/soar-web/api/v2/automation-rules', rule)
export const patchV2AutomationRule = (id: string, changes: Record<string, unknown>) =>
  patch<SoarV2AutomationRule>(`/soar-web/api/v2/automation-rules/${encodeURIComponent(id)}`, changes)
export const setV2AutomationRuleEnabled = (id: string, enabled: boolean) =>
  post<SoarV2AutomationRule>(`/soar-web/api/v2/automation-rules/${encodeURIComponent(id)}/${enabled ? 'enable' : 'disable'}`)
export const testV2AutomationRules = (event: Record<string, unknown>) =>
  post<Record<string, unknown>[]>('/soar-web/api/v2/automation-rules/test', event)

export const listV2Connections = (page = 0, size = 50) =>
  get<SoarV2Page<SoarV2Connection>>(`/soar-web/api/v2/connections?page=${page}&size=${size}`)
export const listV2Actions = () => get<SoarV2ActionDescriptor[]>('/soar-web/api/v2/actions')
export const setV2ConnectionEnabled = (id: string, enabled: boolean) =>
  post<SoarV2Connection>(`/soar-web/api/v2/connections/${encodeURIComponent(id)}/${enabled ? 'enable' : 'disable'}`)
export const testV2Connection = (id: string) =>
  post<SoarV2Connection>(`/soar-web/api/v2/connections/${encodeURIComponent(id)}/test`)
export const deleteV2Connection = (id: string) =>
  del<SoarV2Connection>(`/soar-web/api/v2/connections/${encodeURIComponent(id)}`)
export const createV2Connection = (connection: {
  name: string; connectorType: string; endpoint: string; authSecretRef?: string
  allowedHosts: string[]; enabled?: boolean; rowVersion?: number
}) => post<SoarV2Connection>('/soar-web/api/v2/connections', connection)

export const listV2ManualTasksPage = (pendingOnly = true, page = 0, size = 50) =>
  get<SoarV2Page<SoarV2ManualTask>>(`/soar-web/api/v2/manual-tasks?pendingOnly=${pendingOnly}&page=${page}&size=${size}`)
export const getV2Stats = () => get<SoarV2Stats>('/soar-web/api/v2/stats')
export const listV2DeadDispatches = () => get<SoarV2DeadLetter[]>('/soar-web/api/v2/operations/dead-dispatches')
export const requeueV2DeadDispatch = (id: string, reason?: string) =>
  post<Record<string, unknown>>(`/soar-web/api/v2/operations/dead-dispatches/${encodeURIComponent(id)}/requeue`, { reason })
export const discardV2DeadDispatch = (id: string, reason: string) =>
  post<Record<string, unknown>>(`/soar-web/api/v2/operations/dead-dispatches/${encodeURIComponent(id)}/discard`, { reason })
