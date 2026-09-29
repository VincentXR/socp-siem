import { get, post, type ApiRequestOptions } from './core'
import type { IngestParseFailure, IngestSummary, IngestTask, IngestTestResult, Paged } from './models'
import { withQuery } from '../lib/query'

export const listIngestTasks = (options?: ApiRequestOptions) => get<IngestTask[]>('/search-config/api/v1/ingest/tasks', options)
export const ingestSummary = (options?: ApiRequestOptions) => get<IngestSummary>('/search-config/api/v1/ingest/tasks/summary', options)
export const startIngestTask = (id: string) => post<{ id: string; enabled: boolean; task: IngestTask }>(`/search-config/api/v1/ingest/tasks/${encodeURIComponent(id)}/start`)
export const stopIngestTask = (id: string) => post<{ id: string; enabled: boolean; task: IngestTask }>(`/search-config/api/v1/ingest/tasks/${encodeURIComponent(id)}/stop`)
export const testIngestTask = (id: string, sample?: string) => post<IngestTestResult>(`/search-config/api/v1/ingest/tasks/${encodeURIComponent(id)}/test`, sample ? { sample } : {})
export const listIngestParseFailures = (page = 1, size = 50, options?: ApiRequestOptions) =>
  get<Paged<IngestParseFailure>>(withQuery('/search-config/api/v1/ingest/parse-failures', { page, size }), options)
export const replayIngestParseFailure = (id: string) =>
  post<IngestParseFailure & { replayed?: boolean; created?: number; duplicates?: number }>(`/search-config/api/v1/ingest/parse-failures/${encodeURIComponent(id)}/replay`)
