import { del, downloadFile, get, post, put, type ApiRequestOptions } from './core'
import type { Alarm, AlarmEvidenceResponse, AlarmPage, AlarmStats, Disposition } from './models'
import { withQuery } from '../lib/query'

export const listAlarms = (q?: string, options?: ApiRequestOptions) => get<Alarm[]>(withQuery('/alert-web/api/alarms', { q }), options)
export const listAlarmsPaged = (
  page: number, size: number, q?: string, severity?: string, status?: string, rule?: string,
  sort: 'occurredAt' | 'severity' | 'ruleName' | 'entity' | 'status' | 'riskScore' = 'occurredAt',
  order: 'ascending' | 'descending' = 'descending', options?: ApiRequestOptions,
) => get<AlarmPage>(withQuery('/alert-web/api/alarms', { page, size, q, severity, status, rule, sort, order }), options)
export const createAlarm = (a: Partial<Alarm>) => post<Alarm>('/alert-web/api/alarms', a)
export const getDisposition = (id: string) => get<Disposition>(`/alert-web/api/alarms/${encodeURIComponent(id)}/disposition`)
export const getAlarmEvidence = (id: string) => get<AlarmEvidenceResponse>(`/alert-web/api/alarms/${encodeURIComponent(id)}/evidence`)
export const setDispositionStatus = (id: string, status: string) => put<Disposition>(`/alert-web/api/alarms/${encodeURIComponent(id)}/status`, { status })
export const assignAlarm = (id: string, assignee: string) => post<Disposition>(`/alert-web/api/alarms/${encodeURIComponent(id)}/assign`, { assignee })
export const addAlarmNote = (id: string, content: string, author = 'operator') => post<Disposition>(`/alert-web/api/alarms/${encodeURIComponent(id)}/notes`, { content, author })
export const alarmStats = (options?: ApiRequestOptions, window = '7d') => get<AlarmStats>(withQuery('/alert-web/api/alarms/stats', { window }), options)
export const exportAlarms = (format = 'csv') => downloadFile(withQuery('/alert-web/api/alarms/export', { format }), `alarms.${format}`)
