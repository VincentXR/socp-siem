import { put, del, get, post, type ApiRequestOptions } from './core'
import { withQuery } from '../lib/query'
import type { Channel, DispatchLogEntry, Paged } from './models'

export const getChannel = (id: string) => get<Channel>(`/notify-web/api/v1/channels/${encodeURIComponent(id)}`)
export const listChannels = (options?: ApiRequestOptions, page = 1, size = 500) => get<Paged<Channel>>(withQuery('/notify-web/api/v1/channels', { page, size }), options)
export const createChannel = (c: { name: string; type: string; target: string; enabled?: boolean; description?: string }) => post<Channel>('/notify-web/api/v1/channels', c)
export const deleteChannel = (id: string) => del(`/notify-web/api/v1/channels/${encodeURIComponent(id)}`)
export const toggleChannel = (id: string) => post<{ channel: Channel }>(`/notify-web/api/v1/channels/${encodeURIComponent(id)}/toggle`)
export const dispatchLog = (options?: ApiRequestOptions, page = 1, size = 500, status?: string) => get<Paged<DispatchLogEntry>>(withQuery('/notify-web/api/v1/dispatch-log', { page, size, status }), options)

export const updateChannel = (id: string, channel: Omit<Channel, 'id'>) => put<Channel>(`/notify-web/api/v1/channels/${encodeURIComponent(id)}`, channel)
export const testChannel = (id: string) => post<{ status: string; detail?: string }>(`/notify-web/api/v1/channels/${encodeURIComponent(id)}/test`)
