import { post, requestJson } from './core'

export async function login(username: string, password: string): Promise<{ username: string; role: string; tenant: string; locale?: string; expiresIn: number }> {
  return requestJson('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  }, { auth: false, notifyUnauthorized: false })
}

export const currentSession = () => requestJson<{ username: string; role: string; tenant: string; locale?: string }>(
  '/auth/session', {}, { unwrap: false, notifyUnauthorized: false },
)
export interface OperatorDirectoryItem { id: string; label: string; role: string; current: boolean }
export const listOperators = () => requestJson<{ items: OperatorDirectoryItem[]; source: string }>(
  '/auth/operators', {}, { unwrap: false },
)
export const logout = () => post<void>('/auth/logout', undefined, { unwrap: false, notifyUnauthorized: false })
