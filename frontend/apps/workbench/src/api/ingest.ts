import { get, post, put, type ApiRequestOptions } from './core'
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

export type OutputInput = { name: string; type: string; uri: string; enabled: boolean; authToken?: string | null }
export type OutputCredentialAction = 'KEEP' | 'REPLACE' | 'CLEAR'
export const updateOutput = (id: string, target: OutputInput, credentialAction: OutputCredentialAction) =>
  put<import('./models').SinkTarget>(`/search-config/api/v1/outputs/${encodeURIComponent(id)}`, { target, credentialAction })
export const validateOutputConfig = (body: OutputInput) =>
  post<{ valid: boolean; networkTested: false; writesEvent: false }>('/search-config/api/v1/outputs/validate', body)

export interface SourceSetup {
  source: import('./models').LogSource
  collectorTag: string
  nativeVector: boolean
  appliedState: 'UNKNOWN'
  configurationVersion: string
  output: import('./models').SinkTarget | null
  problems: string[]
  pipeline: Array<{ id: string; name: string; enabled: boolean; exists: boolean; scopeMatches: boolean }>
  pipelineMode: string
}
export interface SourcePreview {
  sourceId: string; sample: string; ok: boolean; matched: boolean
  fields: Record<string, string>; ecs?: Record<string, string>; error?: string
  parserVersion: string; writesEvent: false; mayTriggerDownstreamActions: false
}
export const getSourceSetup = (id: string, options?: ApiRequestOptions) =>
  get<SourceSetup>(`/search-config/api/v1/sources/${encodeURIComponent(id)}/setup`, options)
export const previewSource = (id: string, sample: string, options?: ApiRequestOptions) =>
  post<SourcePreview>(`/search-config/api/v1/sources/${encodeURIComponent(id)}/preview`, { sample }, options)
export const renderSourceConfig = (id: string, options?: ApiRequestOptions) =>
  get<string>(`/search-config/api/v1/sources/${encodeURIComponent(id)}/vector-config`, options)
