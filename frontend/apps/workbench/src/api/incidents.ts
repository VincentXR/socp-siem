import { downloadFile, get, post, type ApiRequestOptions } from './core'
import type { Alarm, CaseInfo, Paged, TimelineEvent } from './models'
import { withQuery } from '../lib/query'

export const listCases = (page = 1, size = 20, q?: string, status?: string, options?: ApiRequestOptions, queue?: 'mine' | 'unassigned') =>
  get<Paged<CaseInfo>>(withQuery('/incident-web/api/v1/incidents', { page, size, q, status, queue }), options)
export const getCase = (id: string, options?: ApiRequestOptions) =>
  get<CaseInfo>(withQuery(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}`, { includeAssociations: false }), options)
export const getCaseByAlarm = (alarmId: string, options?: ApiRequestOptions) =>
  get<CaseInfo>(withQuery('/incident-web/api/v1/incidents/by-alarm', { alarmId }), options)
export const createCase = (item: { title: string; entity?: string; severity: string; assignee?: string }) => post<{ case: CaseInfo }>('/incident-web/api/v1/incidents', item)
/** 案件时间线：page 为 1-based（后端存储层 0-based，Controller 负责换算）。 */
export const caseTimeline = (id: string, page = 1, size = 100, options?: ApiRequestOptions) => get<Paged<TimelineEvent>>(withQuery(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/timeline`, { page, size }), options)
export const caseAlarms = (id: string, page = 1, size = 100, options?: ApiRequestOptions) => get<Paged<string>>(withQuery(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/alarms`, { page, size }), options)
export const caseRules = (id: string, page = 1, size = 100, options?: ApiRequestOptions) => get<Paged<string>>(withQuery(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/rules`, { page, size }), options)
export const setCaseStatus = (id: string, status: string, assignee?: string, options?: ApiRequestOptions) => post<{ case: CaseInfo }>(withQuery(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/status`, { status, assignee }), undefined, options)
export const caseStats = (options?: ApiRequestOptions) => get<{ total: number; open: number; resolved: number }>('/incident-web/api/v1/stats', options)
export interface CreateCaseFromAlarmResult {
  caseId: string
  caseNo?: string
  title?: string
  entity?: string
  status?: string
  alarmCount?: number
  created?: boolean
  duplicate?: boolean
}
export const createCaseFromAlarm = (alarm: Alarm) =>
  post<CreateCaseFromAlarmResult>('/incident-web/api/v1/incidents/from-alarm', alarm)
export const exportCases = () => downloadFile('/incident-web/api/v1/incidents/export', 'cases.json')

export interface CaseChanges {
  status: string; assignee?: string; expectedVersion: number; idempotencyKey: string
  classification?: string; result?: string; reason?: string; evidence?: string; remainingActions?: string
}
export const saveCaseChanges = (id: string, request: CaseChanges) =>
  post<{ case: CaseInfo; duplicate: boolean; changed: boolean }>(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/changes`, request)
export const claimCase = (id: string, expectedVersion: number, idempotencyKey: string) =>
  post<{ case: CaseInfo }>(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/claim`, { expectedVersion, idempotencyKey })
export const addCaseNote = (id: string, content: string, idempotencyKey: string) =>
  post<{ case: CaseInfo }>(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/notes`, { content, idempotencyKey })
export const exportCaseSummary = (id: string) => downloadFile(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/export`, 'case-summary.json')

export interface CaseAssociationChange {
  alarmId: string; operation: 'ATTACH' | 'DETACH' | 'MOVE'; targetCaseId?: string
  expectedVersion: number; targetExpectedVersion?: number; idempotencyKey: string; reason: string
}
export const changeCaseAssociation = (id: string, request: CaseAssociationChange) =>
  post<{ case: CaseInfo; targetCase?: CaseInfo; duplicate: boolean; changed: boolean }>(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/alarm-associations`, request)
