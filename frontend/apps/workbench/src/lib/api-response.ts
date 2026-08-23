/** Stable backend response envelope shared by every business API. */
export interface ApiEnvelope<T> {
  code: number
  message?: string
  data: T
  traceId?: string | null
  timestamp?: string
}

export function unwrapApiBody<T>(body: unknown): T {
  if (body && typeof body === 'object' && 'code' in body && 'data' in body) {
    const envelope = body as ApiEnvelope<T>
    if (envelope.code !== 0) throw new Error(envelope.message || `code=${envelope.code}`)
    return envelope.data
  }
  return body as T
}
