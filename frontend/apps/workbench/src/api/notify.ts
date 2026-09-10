import { put, del, get, post } from './core'
import type { Channel, DispatchLogEntry } from './models'

export const listChannels = () => get<Channel[]>('/notify-web/api/v1/channels')
export const createChannel = (c: { name: string; type: string; target: string; enabled?: boolean; description?: string }) => post<Channel>('/notify-web/api/v1/channels', c)
export const deleteChannel = (id: string) => del(`/notify-web/api/v1/channels/${encodeURIComponent(id)}`)
export const toggleChannel = (id: string) => post<{ channel: Channel }>(`/notify-web/api/v1/channels/${encodeURIComponent(id)}/toggle`)
export const dispatchLog = () => get<DispatchLogEntry[]>('/notify-web/api/v1/dispatch-log')

export const updateChannel = (id: string, channel: Omit<Channel, 'id'>) => put<Channel>(`/notify-web/api/v1/channels/${encodeURIComponent(id)}`, channel)
export const testChannel = (id: string) => post<{ status: string; detail?: string }>(`/notify-web/api/v1/channels/${encodeURIComponent(id)}/test`)
