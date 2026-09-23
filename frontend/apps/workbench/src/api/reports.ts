import { get, post, type ApiRequestOptions } from './core'
import type { ReportSummary, ReportTrend } from './models'
import { withQuery } from '../lib/query'

export const dailyReport = (options?: ApiRequestOptions) => get<ReportSummary>('/report-web/api/v1/reports/daily', options)
export const trend7d = (options?: ApiRequestOptions) => get<ReportTrend>('/report-web/api/v1/reports/trend7d', options)
export const archiveReport = () => post<{ archived: boolean; day: string; archiveKey: string; schemaVersion: number }>('/report-web/api/v1/reports/archive')
export const listArchive = (prefix = 'reports/', options?: ApiRequestOptions) => get<{ prefix: string; count: number; limit: number; truncated: boolean; objects: Array<{ key: string; size: number }> }>(withQuery('/report-web/api/v1/reports/archive', { prefix }), options)
export const downloadArchivedReport = (key: string, options?: ApiRequestOptions) => get<{ key: string; url: string }>(withQuery('/report-web/api/v1/reports/archive/download', { key }), options)
