import { downloadFile, get, post, type ApiRequestOptions } from './core'
import type { Alarm, CaseInfo, Paged, TimelineEvent } from './models'
import { withQuery } from '../lib/query'

export const listCases = () => get<Paged<CaseInfo>>('/incident-web/api/v1/incidents')
export const createCase = (item: { title: string; entity?: string; severity: string; assignee?: string }) => post<{ case: CaseInfo }>('/incident-web/api/v1/incidents', item)
/** 案件时间线：page 为 1-based（后端存储层 0-based，Controller 负责换算）。 */
export const caseTimeline = (id: string, page = 1, size = 100) => get<Paged<TimelineEvent>>(withQuery(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/timeline`, { page, size }))
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
