/** Stable backend response envelope shared by every business API. */
export interface ApiEnvelope<T> {
  code: number
  message?: string
  data: T
  traceId?: string | null
  timestamp?: string
}

/** Business failure carried inside an HTTP 200 envelope (code != 0). */
export class ApiBusinessError extends Error {
  readonly code: number
  readonly traceId: string | null

  constructor(code: number, message: string, traceId: string | null = null) {
    super(message)
    this.name = 'ApiBusinessError'
    this.code = code
    this.traceId = traceId
  }
}

/** ApiResult.ok is the only success factory and always emits code=0. */
const SUCCESS_CODES = new Set([0])

function isJsonObject(body: unknown): body is Record<string, unknown> {
  return typeof body === 'object' && body !== null && !Array.isArray(body)
}

export function unwrapApiBody<T>(body: unknown): T {
  if (isJsonObject(body) && typeof body.code === 'number') {
    // ApiResult serializes with NON_NULL, so a failed envelope (data=null)
    // and a Void ok() both omit the `data` key. Detect envelopes by the
    // remaining markers instead; bare domain bodies without them are
    // returned untouched even when they carry a numeric `code` field.
    const envelopeLike = 'data' in body || 'traceId' in body || 'timestamp' in body
    if (envelopeLike) {
      if (!SUCCESS_CODES.has(body.code)) {
        const message = typeof body.message === 'string' && body.message ? body.message : `code=${body.code}`
        const traceId = typeof body.traceId === 'string' ? body.traceId : null
        throw new ApiBusinessError(body.code, message, traceId)
      }
      return ('data' in body ? body.data : undefined) as T
    }
  }
  return body as T
}
