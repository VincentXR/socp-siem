import { get, post, type ApiRequestOptions } from './core'
import type { IngestSummary, IngestTask, IngestTestResult } from './models'

export const listIngestTasks = () => get<IngestTask[]>('/search-config/api/v1/ingest/tasks')
export const ingestSummary = (options?: ApiRequestOptions) => get<IngestSummary>('/search-config/api/v1/ingest/tasks/summary', options)
export const startIngestTask = (id: string) => post<{ id: string; enabled: boolean; task: IngestTask }>(`/search-config/api/v1/ingest/tasks/${encodeURIComponent(id)}/start`)
export const stopIngestTask = (id: string) => post<{ id: string; enabled: boolean; task: IngestTask }>(`/search-config/api/v1/ingest/tasks/${encodeURIComponent(id)}/stop`)
export const testIngestTask = (id: string, sample?: string) => post<IngestTestResult>(`/search-config/api/v1/ingest/tasks/${encodeURIComponent(id)}/test`, sample ? { sample } : {})
