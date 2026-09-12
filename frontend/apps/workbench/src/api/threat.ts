import { del, get, patch, post, put, type ApiRequestOptions } from './core'
import type { Ioc } from './models'
import { withQuery } from '../lib/query'

export const listIocs = (type?: string, options?: ApiRequestOptions) => get<Ioc[]>(withQuery('/threat-web/api/v1/iocs', { type }), options)
export type IocInput = { type: string; value: string; severity?: string; source?: string; description?: string; tags?: string[] }
export const createIoc = (i: IocInput) => post<Ioc>('/threat-web/api/v1/iocs', i)
export const updateIoc = (id: string, i: IocInput) => put<Ioc>(`/threat-web/api/v1/iocs/${encodeURIComponent(id)}`, i)
export const setIocRevoked = (id: string, revoked: boolean) => patch<Ioc>(`/threat-web/api/v1/iocs/${encodeURIComponent(id)}/lifecycle`, { revoked })
export const importIocs = (items: Array<{ type: string; value: string; severity?: string; source?: string; description?: string; tags?: string[] }>) => post<{ imported: number; skipped: number; errors: string[] }>('/threat-web/api/v1/iocs/import', items)
export const deleteIoc = (id: string) => del(`/threat-web/api/v1/iocs/${encodeURIComponent(id)}`)
export const tiMatch = (value: string, options?: ApiRequestOptions) => get<{ value: string; matched: boolean; ioc?: Ioc }>(withQuery('/threat-web/api/v1/iocs/match', { value }), options)
export const tiStats = () => get<{ total: number; byType: Record<string, number> }>('/threat-web/api/v1/stats')
