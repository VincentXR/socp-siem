import { del, downloadFile, get, post, put, requestJson, type ApiRequestOptions } from './core'
import type {
  Alarm, AlarmBatchDispositionResult, AlarmFeedback, AlarmFeedbackKind,
  AlarmDeliveryStatus, AlarmEvidenceResponse, AlarmPage, AlarmSortField, AlarmSortOrder, AlarmStats, Disposition,
} from './models'
import { withQuery } from '../lib/query'

export const listAlarms = (q?: string, options?: ApiRequestOptions) => get<Alarm[]>(withQuery('/alert-web/api/alarms', { q }), options)
export const listAlarmsByEvent = (eventId: string) =>
  get<Alarm[]>(withQuery('/alert-web/api/alarms/by-event', { eventId }))
export interface AlarmInvestigationFilters { assignee?: string; owner?: string; entity?: string; from?: string; to?: string; technique?: string; severityGroup?: string }
export const listAlarmsPaged = (
  page: number, size: number, q?: string, severity?: string, status?: string, rule?: string,
  sort: 'occurredAt' | 'severity' | 'ruleName' | 'entity' | 'status' | 'riskScore' = 'occurredAt',
  order: 'ascending' | 'descending' = 'descending', options?: ApiRequestOptions, filters: AlarmInvestigationFilters = {},
) => get<AlarmPage>(withQuery('/alert-web/api/alarms', { page, size, q, severity, status, rule, sort, order, ...filters }), options)
export const createAlarm = (a: Partial<Alarm>) => post<Alarm>('/alert-web/api/alarms', a)
export const getAlarm = (id: string, options?: ApiRequestOptions) =>
  get<Alarm>(`/alert-web/api/alarms/${encodeURIComponent(id)}`, options)
export const getDisposition = (id: string) => get<Disposition>(`/alert-web/api/alarms/${encodeURIComponent(id)}/disposition`)
export const getAlarmEvidence = (id: string, options?: ApiRequestOptions) => get<AlarmEvidenceResponse>(`/alert-web/api/alarms/${encodeURIComponent(id)}/evidence`, options)
export const getAlarmDeliveries = (id: string) =>
  get<AlarmDeliveryStatus[]>(`/alert-web/api/alarms/${encodeURIComponent(id)}/deliveries`)
export const requeueAlarmDelivery = (deliveryId: string, recovery?: { reason: string; confirmUnknown: boolean }) =>
  post<{ id: string; type: string; tenantId: string; status: string; requeuedAt: string }>(
    `/alert-web/api/admin/outbox/alarm-deliveries/${encodeURIComponent(deliveryId)}/requeue`, recovery,
  )
export const setDispositionStatus = (id: string, status: string, reason?: string, classification?: string) => put<Disposition>(`/alert-web/api/alarms/${encodeURIComponent(id)}/status`, { status, reason, classification })
export const claimAlarm = (id: string) => post<Disposition>(`/alert-web/api/alarms/${encodeURIComponent(id)}/claim`)
export const assignAlarm = (id: string, assignee: string) => post<Disposition>(`/alert-web/api/alarms/${encodeURIComponent(id)}/assign`, { assignee })
export const addAlarmNote = (id: string, content: string, author = 'operator', idempotencyKey?: string) =>
  requestJson<Disposition>(`/alert-web/api/alarms/${encodeURIComponent(id)}/notes`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
    },
    body: JSON.stringify({ content, author }),
  })
export const batchUpdateAlarmDisposition = (alarmIds: string[], mutation: { status?: string; assignee?: string; reason?: string; classification?: string }) =>
  post<AlarmBatchDispositionResult>('/alert-web/api/v1/alarms/batch/disposition', { alarmIds, ...mutation })
export const listAlarmFeedback = (id: string) =>
  get<AlarmFeedback[]>(`/alert-web/api/alarms/${encodeURIComponent(id)}/feedback`)
export const saveAlarmFeedback = (id: string, feedback: { kind: AlarmFeedbackKind; reason: string; expiresAt?: string; actor?: string }) =>
  post<AlarmFeedback>(`/alert-web/api/alarms/${encodeURIComponent(id)}/feedback`, feedback)
export const listSimilarAlarms = (id: string, limit = 20) =>
  get<Alarm[]>(withQuery(`/alert-web/api/alarms/${encodeURIComponent(id)}/similar`, { limit }))
export const alarmStats = (options?: ApiRequestOptions, window = '7d') => get<AlarmStats>(withQuery('/alert-web/api/alarms/stats', { window }), options)

export interface AlarmExportFilters extends AlarmInvestigationFilters {
  q?: string
  severity?: string
  status?: string
  rule?: string
  sort?: AlarmSortField
  order?: AlarmSortOrder
}

/** Export the same result set represented by the alarm explorer filters. */
export const exportAlarms = (format = 'csv', filters: AlarmExportFilters = {}) =>
  downloadFile(
    withQuery('/alert-web/api/alarms/export', { format, ...filters }),
    `alarms.${format}`,
  )

/** Exact per-technique counts within the server's rolling seven-day window. */
export const alarmTechniqueCounts = (techniqueIds: string[], options?: ApiRequestOptions) =>
  post<{ from: string; until: string; counts: Record<string, number> }>('/alert-web/api/alarms/technique-counts', { techniqueIds }, options)
