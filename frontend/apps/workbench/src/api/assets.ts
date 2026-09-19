import { del, get, post, put } from './core'
import type { ApiRequestOptions } from './core'
import type { Asset, Endpoint, Paged } from './models'
import { withQuery } from '../lib/query'

export const listAssets = (page = 1, size = 10, q?: string, options?: ApiRequestOptions) =>
  get<Paged<Asset>>(withQuery('/asset-web/api/v1/assets', { page, size, q }), options)
export const createAsset = (a: Partial<Asset>) => post<Asset>('/asset-web/api/v1/assets', a)
export const updateAsset = (id: string, a: Partial<Asset>) => put<Asset>(`/asset-web/api/v1/assets/${encodeURIComponent(id)}`, a)
export const deleteAsset = (id: string) => del(`/asset-web/api/v1/assets/${encodeURIComponent(id)}`)
export const importAssets = (items: Array<Partial<Asset>>) => post<{ imported: number; skipped: number; errors: string[] }>('/asset-web/api/v1/assets/import', items)
export const assetStats = (options?: ApiRequestOptions) => get<{ total: number; byType: Record<string, number>; byCriticality: Record<string, number> }>('/asset-web/api/v1/assets/stats', options)

export const listEndpoints = (page = 1, size = 10, q?: string, options?: ApiRequestOptions) =>
  get<Paged<Endpoint>>(withQuery('/hips-web/api/v1/endpoints', { page, size, q }), options)
export type EndpointEvent = {
  eventId?: string
  hostname?: string
  type?: string
  receivedAt?: string
  [key: string]: unknown
}
export const listEndpointEvents = (page = 1, size = 50, options?: ApiRequestOptions) =>
  get<Paged<EndpointEvent>>(withQuery('/hips-web/api/v1/endpoints/events', { page, size }), options)
export const endpointStats = (options?: ApiRequestOptions) => get<{ total: number; online: number; byType?: Record<string, number>; eventByType?: Record<string, number>; events?: number }>('/hips-web/api/v1/endpoints/stats', options)
export const deleteEndpoint = (id: string) => del(`/hips-web/api/v1/endpoints/${encodeURIComponent(id)}`)
