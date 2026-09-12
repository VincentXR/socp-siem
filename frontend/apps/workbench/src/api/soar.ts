import { del, get, patch, post, put } from './core'

export interface SoarPlaybook {
  id: string; name: string; description?: string; owner?: string; status: string
  latestPublishedVersion?: number | null; draftVersion?: number | null
  tags: string[]; createdAt?: string; updatedAt?: string
}
export interface SoarVersion {
  id: string; playbookId: string; version: number; status: string; schemaVersion: string
  playbookStatus?: string; definition: unknown; layout: unknown; definitionHash: string; riskSummary: Record<string, unknown>
  rowVersion?: number; createdAt?: string; publishedAt?: string
}
export interface SoarRun {
  runId: string; requestId: string; playbookId: string; playbookVersionId: string
  playbookVersion: number; status: string; triggerType: string; definitionHash: string
  temporalWorkflowId?: string; temporalRunId?: string; errorCode?: string; errorMessage?: string
  /** True when the API returned an existing run for an idempotent request. */
  duplicate?: boolean
  createdAt?: string; startedAt?: string; completedAt?: string
}
export interface SoarApproval {
  id: string; runId: string; approvalKey?: string; nodeRunId?: string; actionRef?: string
  inputHash?: string; targetSnapshot?: unknown; requiredApprovals?: number; approvedVotes?: number
  decisions?: Array<{ id: string; actor: string; decision: string; reason?: string; createdAt?: string }>
  status: string; requestedBy: string; approver?: string
  reason?: string; decisionReason?: string; createdAt?: string; expiresAt?: string; decidedAt?: string
}
export interface SoarArtifact {
  id: string; runId: string; nodeRunId?: string; mediaType: string; sizeBytes: number
  sha256: string; storageRef: string; classification: string; expiresAt?: string; createdAt?: string
}
export interface SoarNodeRun {
  id: string; runId: string; nodeId: string; iterationPath?: string; nodeType: string; status: string
  input?: unknown; output?: unknown; idempotencyKey?: string; errorCode?: string; errorMessage?: string
  startedAt?: string; completedAt?: string; updatedAt?: string
}
export interface SoarAttempt {
  id: string; nodeRunId: string; attemptNo: number; status: string; requestHash?: string
  remoteOperationId?: string; receipt?: unknown; errorCode?: string; errorMessage?: string
  retryable?: boolean; startedAt?: string; completedAt?: string; createdAt?: string
}
export interface SoarEvent {
  id: string; runId: string; nodeRunId?: string; sequence: number; eventType: string; actor?: string
  summary: string; detail?: unknown; traceId?: string; createdAt?: string
}
export interface SoarPage<T> { page: number; size: number; total: number; items: T[] }
export interface SoarTemplate {
  id: string; version: number; name: string; description: string; eventTypes: string[]
  requiredConnectors: string[]; risk: string; attackTags: string[]
}

export const listPlaybooks = (page = 0, size = 20) =>
  get<SoarPage<SoarPlaybook>>(`/soar-web/api/playbooks?page=${page}&size=${size}`)
export const createPlaybook = (p: { name: string; description?: string; tags?: string[] }) =>
  post<SoarPlaybook>('/soar-web/api/playbooks', p)
export const importPlaybook = (p: { name: string; description?: string; tags?: string[]; definition: unknown; layout?: unknown }) =>
  post<SoarVersion & { imported?: boolean }>('/soar-web/api/playbooks/import', p)
export const listRuns = (page = 0, size = 20) =>
  get<SoarPage<SoarRun>>(`/soar-web/api/runs?page=${page}&size=${size}`)
export const queueRun = (p: { requestId: string; playbookVersionId: string; subject?: Record<string, unknown>; inputs?: Record<string, unknown> }) =>
  post<SoarRun>('/soar-web/api/runs', p)
export const cancelWorkflowRun = (id: string, reason?: string) =>
  post<SoarRun>(`/soar-web/api/runs/${encodeURIComponent(id)}/cancel`, { reason })
export const listApprovals = () => get<SoarApproval[]>('/soar-web/api/approvals')
export const approve = (id: string, reason?: string) => post<SoarApproval>(`/soar-web/api/approvals/${encodeURIComponent(id)}/approve`, { reason })
export const reject = (id: string, reason?: string) => post<SoarApproval>(`/soar-web/api/approvals/${encodeURIComponent(id)}/reject`, { reason })
export const listTemplates = () => get<SoarTemplate[]>('/soar-web/api/templates')
export const installTemplate = (id: string) => post(`/soar-web/api/templates/${encodeURIComponent(id)}/install`)
export const retryRun = (id: string, reason?: string) => post<SoarRun>(`/soar-web/api/runs/${encodeURIComponent(id)}/retry`, { reason })
export const rerunRun = (id: string, reason?: string) => post<SoarRun>(`/soar-web/api/runs/${encodeURIComponent(id)}/rerun`, { reason, confirm: true })
export const getDefinitionSchema = () => get<Record<string, unknown>>('/soar-web/api/definition-schema')
export const validateVersion = (playbookId: string, version: number) =>
  post<Record<string, unknown>>(`/soar-web/api/playbooks/${encodeURIComponent(playbookId)}/versions/${version}/validate`)
export const dryRunVersion = (playbookId: string, version: number, subject?: Record<string, unknown>, inputs?: Record<string, unknown>) =>
  post<Record<string, unknown>>(`/soar-web/api/playbooks/${encodeURIComponent(playbookId)}/versions/${version}/dry-run`, { subject, inputs })
export const getPlaybook = (id: string) => get<SoarPlaybook>(`/soar-web/api/playbooks/${encodeURIComponent(id)}`)
export const updatePlaybook = (id: string, changes: { name?: string; description?: string; tags?: string[]; status?: 'ACTIVE' | 'ARCHIVED'; rowVersion?: number }) =>
  patch<SoarPlaybook>(`/soar-web/api/playbooks/${encodeURIComponent(id)}`, changes)
export const listVersions = (playbookId: string) => get<SoarVersion[]>(`/soar-web/api/playbooks/${encodeURIComponent(playbookId)}/versions`)
export const createVersion = (playbookId: string) => post<SoarVersion>(`/soar-web/api/playbooks/${encodeURIComponent(playbookId)}/versions`)
export const createDraft = (playbookId: string) => post<SoarVersion>(`/soar-web/api/playbooks/${encodeURIComponent(playbookId)}/drafts`)
export const getVersion = (playbookId: string, version: number) =>
  get<SoarVersion>(`/soar-web/api/playbooks/${encodeURIComponent(playbookId)}/versions/${version}`)
export const saveVersion = (playbookId: string, version: number, definition: unknown, layout?: unknown, rowVersion?: number) =>
  put<SoarVersion>(`/soar-web/api/playbooks/${encodeURIComponent(playbookId)}/versions/${version}`, { definition, layout, rowVersion })
export const publishVersion = (playbookId: string, version: number) =>
  post<SoarVersion>(`/soar-web/api/playbooks/${encodeURIComponent(playbookId)}/versions/${version}/publish`)
export const deprecateVersion = (playbookId: string, version: number) =>
  post<SoarVersion>(`/soar-web/api/playbooks/${encodeURIComponent(playbookId)}/versions/${version}/deprecate`)
export const exportVersion = (playbookId: string, version: number) =>
  get<SoarVersion & { format?: string; exportedAt?: string }>(`/soar-web/api/playbooks/${encodeURIComponent(playbookId)}/versions/${version}/export`)
export const getRun = (id: string) => get<SoarRun>(`/soar-web/api/runs/${encodeURIComponent(id)}`)
export const listNodes = (runId: string) => get<SoarNodeRun[]>(`/soar-web/api/runs/${encodeURIComponent(runId)}/nodes`)
export const listNodeAttempts = (nodeRunId: string, page = 0, size = 20) =>
  get<SoarPage<SoarAttempt>>(`/soar-web/api/node-runs/${encodeURIComponent(nodeRunId)}/attempts?page=${page}&size=${size}`)
export const listEvents = (runId: string, after = 0, page = 0, size = 100) =>
  get<SoarPage<SoarEvent>>(`/soar-web/api/runs/${encodeURIComponent(runId)}/events?after=${after}&page=${page}&size=${size}`)
export const listArtifacts = (runId: string) => get<SoarArtifact[]>(`/soar-web/api/runs/${encodeURIComponent(runId)}/artifacts`)
export const uploadArtifact = (runId: string, content: unknown, options?: { nodeRunId?: string; mediaType?: string; classification?: string }) => {
  const params = new URLSearchParams()
  if (options?.nodeRunId) params.set('nodeRunId', options.nodeRunId)
  if (options?.mediaType) params.set('mediaType', options.mediaType)
  if (options?.classification) params.set('classification', options.classification)
  const suffix = params.toString() ? `?${params.toString()}` : ''
  return post<SoarArtifact>(`/soar-web/api/runs/${encodeURIComponent(runId)}/artifacts${suffix}`, content)
}
export const getArtifact = (id: string) => get<SoarArtifact>(`/soar-web/api/artifacts/${encodeURIComponent(id)}`)
export const getArtifactContent = (id: string) => get<unknown>(`/soar-web/api/artifacts/${encodeURIComponent(id)}/content`)
export const resolveUnknown = (nodeRunId: string, resolution: 'CONFIRMED_SUCCEEDED' | 'CONFIRMED_NOT_EXECUTED', evidence: string, reason: string) =>
  post<Record<string, unknown>>(`/soar-web/api/node-runs/${encodeURIComponent(nodeRunId)}/resolve-unknown`, { resolution, evidence, reason })
export const listManualTasks = (pendingOnly = true) => get<Record<string, unknown>[]>(`/soar-web/api/manual-tasks?pendingOnly=${pendingOnly}`)
export const completeManualTask = (id: string, input: Record<string, unknown>) =>
  post<Record<string, unknown>>(`/soar-web/api/manual-tasks/${encodeURIComponent(id)}/complete`, input)

export interface SoarAutomationRule {
  id: string; name: string; triggerType: string; priority: number; enabled: boolean
  conditions: unknown; actions: unknown; suppression: unknown; revision?: number
  dedupWindowSeconds?: number; cooldownSeconds?: number; groupBy?: string
  maxConcurrentRuns?: number; conflictStrategy?: string; validFrom?: string; validUntil?: string
  createdAt?: string; updatedAt?: string; rowVersion?: number
}
export interface SoarConnection {
  id: string; name: string; connectorType: string; endpoint: string; authSecretRef?: string
  allowedHosts: string[]; enabled: boolean; status: string; revision?: number
  lastTestAt?: string; lastTestStatus?: string; lastTestError?: string; rowVersion?: number
  createdAt?: string; updatedAt?: string
}
export interface SoarActionDescriptor {
  connectorId: string; connectorVersion: number; production: boolean; actionRef: string; id: string
  displayName: string; description?: string; riskLevel: string; sideEffect: string
  idempotency: string; requiresConnection: boolean; requiredPermissions?: string[]
  inputSchema?: unknown; outputSchema?: unknown
}
export interface SoarManualTask {
  id: string; runId: string; nodeId: string; formSchema: unknown; input?: unknown
  assignee?: string; status: string; dueAt?: string; completedBy?: string; completedAt?: string
  createdAt?: string
}
export interface SoarDeadLetter {
  id: string; runId: string; kind?: string; signalType?: string; signalKey?: string
  status: string; attempts: number; lastError?: string; updatedAt?: string
}
export interface SoarStats {
  runsByStatus: Record<string, number>; dispatchBacklog: number; signalBacklog: number; generatedAt?: string
}

export const listAutomationRules = (page = 0, size = 50) =>
  get<SoarPage<SoarAutomationRule>>(`/soar-web/api/automation-rules?page=${page}&size=${size}`)
export const createAutomationRule = (rule: {
  name: string; triggerType: string; priority?: number; enabled?: boolean
  conditions?: unknown; actions: unknown; suppression?: unknown; rowVersion?: number
}) => post<SoarAutomationRule>('/soar-web/api/automation-rules', rule)
export const patchAutomationRule = (id: string, changes: Record<string, unknown>) =>
  patch<SoarAutomationRule>(`/soar-web/api/automation-rules/${encodeURIComponent(id)}`, changes)
export const setAutomationRuleEnabled = (id: string, enabled: boolean) =>
  post<SoarAutomationRule>(`/soar-web/api/automation-rules/${encodeURIComponent(id)}/${enabled ? 'enable' : 'disable'}`)
export const testAutomationRules = (event: Record<string, unknown>) =>
  post<Record<string, unknown>[]>('/soar-web/api/automation-rules/test', event)

export const listConnections = (page = 0, size = 50) =>
  get<SoarPage<SoarConnection>>(`/soar-web/api/connections?page=${page}&size=${size}`)
export const listActions = () => get<SoarActionDescriptor[]>('/soar-web/api/actions')
export const setConnectionEnabled = (id: string, enabled: boolean) =>
  post<SoarConnection>(`/soar-web/api/connections/${encodeURIComponent(id)}/${enabled ? 'enable' : 'disable'}`)
export const testConnection = (id: string) =>
  post<SoarConnection>(`/soar-web/api/connections/${encodeURIComponent(id)}/test`)
export const deleteConnection = (id: string) =>
  del<SoarConnection>(`/soar-web/api/connections/${encodeURIComponent(id)}`)
export const createConnection = (connection: {
  name: string; connectorType: string; endpoint: string; authSecretRef?: string
  allowedHosts: string[]; enabled?: boolean; rowVersion?: number
}) => post<SoarConnection>('/soar-web/api/connections', connection)

export const listManualTasksPage = (pendingOnly = true, page = 0, size = 50) =>
  get<SoarPage<SoarManualTask>>(`/soar-web/api/manual-tasks?pendingOnly=${pendingOnly}&page=${page}&size=${size}`)
export const getStats = () => get<SoarStats>('/soar-web/api/stats')
export const listDeadDispatches = () => get<SoarDeadLetter[]>('/soar-web/api/operations/dead-dispatches')
export const requeueDeadDispatch = (id: string, reason?: string) =>
  post<Record<string, unknown>>(`/soar-web/api/operations/dead-dispatches/${encodeURIComponent(id)}/requeue`, { reason })
export const discardDeadDispatch = (id: string, reason: string) =>
  post<Record<string, unknown>>(`/soar-web/api/operations/dead-dispatches/${encodeURIComponent(id)}/discard`, { reason })
