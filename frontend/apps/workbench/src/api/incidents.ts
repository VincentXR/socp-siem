import { downloadFile, get, post, type ApiRequestOptions } from './core'
import type { CaseInfo, TimelineEvent } from './models'
import { withQuery } from '../lib/query'

export const listCases = () => get<CaseInfo[]>('/incident-web/api/v1/incidents')
export const createCase = (item: { title: string; entity?: string; severity: string; assignee?: string }) => post<{ case: CaseInfo }>('/incident-web/api/v1/incidents', item)
export const caseTimeline = (id: string) => get<{ caseId: string; timeline: TimelineEvent[] }>(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/timeline`)
export const setCaseStatus = (id: string, status: string, assignee?: string, options?: ApiRequestOptions) => post<{ case: CaseInfo }>(withQuery(`/incident-web/api/v1/incidents/${encodeURIComponent(id)}/status`, { status, assignee }), undefined, options)
export const caseStats = () => get<{ total: number; open: number; resolved: number }>('/incident-web/api/v1/stats')
export const exportCases = () => downloadFile('/incident-web/api/v1/incidents/export', 'cases.json')
