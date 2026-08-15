export function unwrapApiBody<T>(body: unknown): T {
  if (body && typeof body === 'object' && 'code' in body && 'data' in body) {
    const envelope = body as { code: number; message?: string; data: T }
    if (envelope.code !== 0) throw new Error(envelope.message || `code=${envelope.code}`)
    return envelope.data
  }
  return body as T
}
