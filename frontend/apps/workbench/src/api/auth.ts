import { post, requestJson } from './core'

export interface SessionIdentity { username: string; role: string; tenant: string; locale?: string; permissions?: string[] }

export async function login(username: string, password: string): Promise<SessionIdentity & { expiresIn: number }> {
  return requestJson('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  }, { auth: false, notifyUnauthorized: false })
}
export const authCapabilities = () => requestJson<{ localPassword: boolean; oidc: boolean }>(
  '/auth/capabilities', {}, { unwrap: false, notifyUnauthorized: false },
)

export const currentSession = () => requestJson<SessionIdentity>(
  '/auth/session', {}, { unwrap: false, notifyUnauthorized: false },
)
export interface OperatorDirectoryItem { id: string; label: string; role: string; current: boolean }
export const listOperators = () => requestJson<{ items: OperatorDirectoryItem[]; source: string }>(
  '/auth/operators', {}, { unwrap: false },
)
export const logout = () => post<void>('/auth/logout', undefined, { unwrap: false, notifyUnauthorized: false })
