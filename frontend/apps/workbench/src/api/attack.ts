import { get, post, put, type ApiRequestOptions } from './core'
import type { Tactic, Technique } from './models'
import { withQuery } from '../lib/query'

export const listTactics = () => get<Tactic[]>('/attack-web/api/v1/tactics')
export const listTechniques = (tactic?: string, options?: ApiRequestOptions) => get<Technique[]>(withQuery('/attack-web/api/v1/techniques', { tactic }), options)
export const updateTechnique = (id: string, technique: Partial<Omit<Technique, 'id'>>) => put<Technique>(`/attack-web/api/v1/techniques/${encodeURIComponent(id)}`, technique)
export const attackCoverage = (ruleTechs: string[]) => post<{
  byTactic: Array<{ tactic: string; name: string; total: number; covered: number; coverage: number }>
  totalTechniques: number; coveredTechniques: number; coverage: number; uncovered: string[]
}>('/attack-web/api/v1/coverage', { ruleTechniques: ruleTechs })
