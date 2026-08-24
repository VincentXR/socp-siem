import { del, get, post, put } from './core'
import type { Asset, Endpoint } from './models'

export const listAssets = () => get<Asset[]>('/asset-web/api/v1/assets')
export const createAsset = (a: Partial<Asset>) => post<Asset>('/asset-web/api/v1/assets', a)
export const updateAsset = (id: string, a: Partial<Asset>) => put<Asset>(`/asset-web/api/v1/assets/${encodeURIComponent(id)}`, a)
export const deleteAsset = (id: string) => del(`/asset-web/api/v1/assets/${encodeURIComponent(id)}`)
export const importAssets = (items: Array<Partial<Asset>>) => post<{ imported: number; skipped: number; errors: string[] }>('/asset-web/api/v1/assets/import', items)
export const assetStats = () => get<{ total: number; byType: Record<string, number>; byCriticality: Record<string, number> }>('/asset-web/api/v1/assets/stats')

export const listEndpoints = () => get<Endpoint[]>('/hips-web/api/v1/endpoints')
export const endpointStats = () => get<{ total: number; online: number; byType: Record<string, number> }>('/hips-web/api/v1/endpoints/stats')
export const deleteEndpoint = (id: string) => del(`/hips-web/api/v1/endpoints/${encodeURIComponent(id)}`)
