import { get, post, put, type ApiRequestOptions } from './core'
import type { Paged, Tactic, Technique } from './models'
import { withQuery } from '../lib/query'

export const listTactics = (options?: ApiRequestOptions) => get<Paged<Tactic>>('/attack-web/api/v1/tactics', options)
export const listTechniques = (tactic?: string, options?: ApiRequestOptions) => get<Paged<Technique>>(withQuery('/attack-web/api/v1/techniques', { tactic }), options)
export const updateTechnique = (id: string, technique: Partial<Omit<Technique, 'id'>>) => put<Technique>(`/attack-web/api/v1/techniques/${encodeURIComponent(id)}`, technique)
export const attackCoverage = (ruleTechs: string[], options?: ApiRequestOptions) => post<{
  byTactic: Array<{ tactic: string; name: string; total: number; covered: number; coverage: number }>
  totalTechniques: number; coveredTechniques: number; coverage: number; uncovered: string[]
}>('/attack-web/api/v1/coverage', { ruleTechniques: ruleTechs }, options)

export const getTechniqueNote = (id: string, options?: ApiRequestOptions) => get<{ note: string }>(`/attack-web/api/v1/techniques/${encodeURIComponent(id)}/note`, options)
export const saveTechniqueNote = (id: string, note: string) => put<{ note: string }>(`/attack-web/api/v1/techniques/${encodeURIComponent(id)}/note`, { note })
