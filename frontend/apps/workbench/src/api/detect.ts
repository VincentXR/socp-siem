import { del, get, post, put, type ApiRequestOptions } from './core'
import type { DetectionIngestEvent, DetectionIngestResult, GasAlert, GasStats, RiskEntity, RiskSummary, RuleSpec, ScoreBreakdown, Watchlist } from './models'
import { withQuery } from '../lib/query'

export const listRules = () => get<RuleSpec[]>('/detect-web/api/v1/rules')
export const createGasRule = (spec: Partial<RuleSpec>) => post<RuleSpec>('/detect-web/api/v1/rules', spec)
export const updateGasRule = (id: string, spec: Partial<RuleSpec>) => put<RuleSpec>(`/detect-web/api/v1/rules/${encodeURIComponent(id)}`, spec)
export const deleteGasRule = (id: string) => del(`/detect-web/api/v1/rules/${encodeURIComponent(id)}`)
export const gasStats = () => get<GasStats>('/detect-web/api/v1/stats')
export const gasAlerts = () => get<GasAlert[]>('/detect-web/api/v1/alerts')
export const gasIngest = (event: DetectionIngestEvent) => post<DetectionIngestResult>('/detect-web/api/v1/ingest', event)
export const gasRecentAlerts = (options?: ApiRequestOptions) => get<GasAlert[]>('/detect-web/api/v1/alerts', options)
export const gasEngineStats = (options?: ApiRequestOptions) => get<GasStats>('/detect-web/api/v1/stats', options)

export const uebaEntities = (limit = 20, options?: ApiRequestOptions) => get<RiskEntity[]>(withQuery('/detect-web/api/v1/ueba/entities', { limit }), options)
export const uebaEntity = (entity: string) => get<RiskEntity>(`/detect-web/api/v1/ueba/entities/${encodeURIComponent(entity)}`)
export const uebaSummary = () => get<RiskSummary>('/detect-web/api/v1/ueba/summary')
export const uebaScore = (p: { severity: string; mitre?: string; tiHits?: number; recentAlerts?: number; assetCriticality?: number }, options?: ApiRequestOptions) => get<ScoreBreakdown>(withQuery('/detect-web/api/v1/ueba/score', {
  severity: p.severity, mitre: p.mitre, tiHits: p.tiHits ?? 0, recentAlerts: p.recentAlerts ?? 0, assetCriticality: p.assetCriticality ?? 0,
}), options)
export const listWatchlists = () => get<Watchlist[]>('/detect-web/api/v1/watchlists')
export const putWatchlist = (name: string, values: string[]) => put<Watchlist>(`/detect-web/api/v1/watchlists/${encodeURIComponent(name)}`, values)
export const appendWatchlist = (name: string, values: string[]) => post<Watchlist>(`/detect-web/api/v1/watchlists/${encodeURIComponent(name)}`, values)
export const deleteWatchlist = (name: string) => del<{ removed: boolean }>(`/detect-web/api/v1/watchlists/${encodeURIComponent(name)}`)
