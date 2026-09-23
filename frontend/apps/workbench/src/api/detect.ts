import { del, get, post, put, requestJson, type ApiRequestOptions } from './core'
import type { DetectionIngestEvent, DetectionIngestResult, GasAlert, GasStats, Paged, RiskEntity, RiskSummary, RuleSpec, ScoreBreakdown, Watchlist, WatchlistSummary } from './models'
import { withQuery } from '../lib/query'

export type RuleOption = Pick<RuleSpec, 'id' | 'name' | 'type' | 'status'>
export interface RuleListQuery { page?: number; size?: number; q?: string; status?: string; reference?: string; referenceAlias?: string }
export const listRulePage = (query: RuleListQuery = {}, options?: ApiRequestOptions) =>
  get<Paged<RuleSpec>>(withQuery('/detect-web/api/v1/rules', { page: 1, size: 20, ...query }), options)
export const getRule = (id: string, options?: ApiRequestOptions) => get<RuleSpec>(`/detect-web/api/v1/rules/${encodeURIComponent(id)}`, options)
export const listRuleOptions = (q = '', options?: ApiRequestOptions) =>
  get<Paged<RuleOption>>(withQuery('/detect-web/api/v1/rules/options', { page: 1, size: 50, q: q || undefined }), options)
export const lookupRuleOptions = (ids: string[], options?: ApiRequestOptions) =>
  post<RuleOption[]>('/detect-web/api/v1/rules/lookup', { ids }, options)
export const activeRuleTechniques = (options?: ApiRequestOptions) => get<string[]>('/detect-web/api/v1/rules/active-techniques', options)
export const createGasRule = (spec: Partial<RuleSpec>) => post<RuleSpec>('/detect-web/api/v1/rules', spec)
const ruleHeaders = (revisionToken: string | undefined) => ({ 'Content-Type': 'application/json',
  ...(revisionToken ? { 'If-Match': `"${revisionToken}"` } : {}),
})
export const updateGasRule = (id: string, spec: Partial<RuleSpec>, revisionToken: string | undefined) =>
  requestJson<RuleSpec>(`/detect-web/api/v1/rules/${encodeURIComponent(id)}`, { method: 'PUT', headers: ruleHeaders(revisionToken), body: JSON.stringify(spec) })
export const activateGasRule = (id: string, revisionToken: string | undefined) =>
  requestJson<RuleSpec>(`/detect-web/api/v1/rules/${encodeURIComponent(id)}/activate`, { method: 'POST', headers: ruleHeaders(revisionToken) })
export const deleteGasRule = (id: string, revisionToken: string | undefined) =>
  requestJson<{ removed: boolean }>(`/detect-web/api/v1/rules/${encodeURIComponent(id)}`, { method: 'DELETE', headers: ruleHeaders(revisionToken) })
export interface RuleRevision { ruleId: string; revision: number; status: string | null; source: string; changedBy: string | null; changedAt: string | null }
export interface RuleRevisionDetail extends RuleRevision { spec: RuleSpec }
export const listRuleRevisions = (id: string, page = 1, options?: ApiRequestOptions) =>
  get<Paged<RuleRevision>>(withQuery(`/detect-web/api/v1/rules/${encodeURIComponent(id)}/revisions`, { page, size: 10 }), options)
export const getRuleRevision = (id: string, revision: number, options?: ApiRequestOptions) =>
  get<RuleRevisionDetail>(`/detect-web/api/v1/rules/${encodeURIComponent(id)}/revisions/${revision}`, options)
export const restoreRuleRevision = (id: string, revision: number, revisionToken: string | undefined, absent = false) =>
  requestJson<RuleSpec>(`/detect-web/api/v1/rules/${encodeURIComponent(id)}/revisions/${revision}/restore`, {
    method: 'POST', headers: absent ? { 'If-None-Match': '*' } : ruleHeaders(revisionToken),
  })
export const gasStats = () => get<GasStats>('/detect-web/api/v1/stats')
export const gasAlerts = () => get<GasAlert[]>('/detect-web/api/v1/alerts')
export const gasIngest = (event: DetectionIngestEvent) => post<DetectionIngestResult>('/detect-web/api/v1/ingest', event)
export const gasRecentAlerts = (options?: ApiRequestOptions) => get<GasAlert[]>('/detect-web/api/v1/alerts', options)
export const gasEngineStats = (options?: ApiRequestOptions) => get<GasStats>('/detect-web/api/v1/stats', options)

export const uebaEntities = (limit = 20, options?: ApiRequestOptions) => get<RiskEntity[]>(withQuery('/detect-web/api/v1/ueba/entities', { limit }), options)
export const uebaEntity = (entity: string, options?: ApiRequestOptions) => get<RiskEntity>(`/detect-web/api/v1/ueba/entities/${encodeURIComponent(entity)}`, options)
export const uebaSummary = () => get<RiskSummary>('/detect-web/api/v1/ueba/summary')
export const uebaScore = (p: { severity: string; mitre?: string; tiHits?: number; recentAlerts?: number; assetCriticality?: number }, options?: ApiRequestOptions) => get<ScoreBreakdown>(withQuery('/detect-web/api/v1/ueba/score', {
  severity: p.severity, mitre: p.mitre, tiHits: p.tiHits ?? 0, recentAlerts: p.recentAlerts ?? 0, assetCriticality: p.assetCriticality ?? 0,
}), options)
export const listWatchlists = () => get<WatchlistSummary[]>('/detect-web/api/v1/watchlists?includeValues=false')
export const getWatchlist = (name: string) => get<Watchlist>(`/detect-web/api/v1/watchlists/${encodeURIComponent(name)}`)
export const putWatchlist = (name: string, values: string[]) => put<Watchlist>(`/detect-web/api/v1/watchlists/${encodeURIComponent(name)}`, values)
export const createWatchlist = (name: string, values: string[]) => post<Watchlist>('/detect-web/api/v1/watchlists', { name, values })
export const appendWatchlist = (name: string, values: string[]) => post<Watchlist>(`/detect-web/api/v1/watchlists/${encodeURIComponent(name)}`, values)
export const deleteWatchlist = (name: string) => del<{ removed: boolean }>(`/detect-web/api/v1/watchlists/${encodeURIComponent(name)}`)

export interface RuleDryRunResult { id: string; name: string; type: string; matched: boolean; alerts: GasAlert[]; eventCount: number }
export const testGasRules = (rules: Partial<RuleSpec>[], events: DetectionIngestEvent[]) =>
  post<RuleDryRunResult[]>('/detect-web/api/v1/rules/test', { rules, events })
