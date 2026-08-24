import { del, get, post, type ApiRequestOptions } from './core'
import type { Ioc } from './models'
import { withQuery } from '../lib/query'

export const listIocs = (type?: string, options?: ApiRequestOptions) => get<Ioc[]>(withQuery('/threat-web/api/v1/iocs', { type }), options)
export const createIoc = (i: { type: string; value: string; severity?: string; source?: string; description?: string; tags?: string[] }) => post<Ioc>('/threat-web/api/v1/iocs', i)
export const importIocs = (items: Array<{ type: string; value: string; severity?: string; source?: string; description?: string; tags?: string[] }>) => post<{ imported: number; skipped: number; errors: string[] }>('/threat-web/api/v1/iocs/import', items)
export const deleteIoc = (id: string) => del(`/threat-web/api/v1/iocs/${encodeURIComponent(id)}`)
export const tiMatch = (value: string, options?: ApiRequestOptions) => get<{ value: string; matched: boolean; ioc?: Ioc }>(withQuery('/threat-web/api/v1/iocs/match', { value }), options)
export const tiStats = () => get<{ total: number; byType: Record<string, number> }>('/threat-web/api/v1/stats')
