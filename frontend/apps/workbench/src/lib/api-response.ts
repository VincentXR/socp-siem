import { translate } from '../i18n/index.ts'

/** Stable backend response envelope shared by every business API. */
export interface ApiEnvelope<T> {
  code: number
  message?: string
  /** ApiResult.fail() and Void ok() omit null data under NON_NULL serialization. */
  data?: T | null
  traceId?: string | null
  timestamp?: string
}

/**
 * Codes the backend aligns with real HTTP semantics. GlobalExceptionHandler
 * mirrors an ApiException code into the HTTP status when it resolves to a
 * standard error status, so an HTTP status and an envelope business code share
 * one mapping table. Other business codes stay untranslated on purpose: their
 * backend copy is the only domain-specific detail the operator has.
 */
const CODE_ERROR_KEYS: Readonly<Record<number, string>> = {
  401: 'errors.UNAUTHORIZED',
  403: 'errors.FORBIDDEN',
  404: 'errors.NOT_FOUND',
  429: 'errors.RATE_LIMIT_EXCEEDED',
}

/** Localized `errors.*` message key for an HTTP status or mirror-coded business code. */
export function errorKeyForCode(code: number): string | null {
  const mapped = CODE_ERROR_KEYS[code]
  if (mapped) return mapped
  // Only the real 5xx status range: custom business codes (1003, 10001, …) are
  // larger than 500 by construction and carry their own domain-specific copy.
  return code >= 500 && code <= 599 ? 'errors.SERVER_ERROR' : null
}

/**
 * Display text for a failed call. A meaningful backend sentence always wins:
 * operators rely on domain detail like "SOAR execute permission required", and
 * the e2e contracts were written against that copy. Transport plumbing
 * (`HTTP 502`, `code=1003`, empty bodies) is replaced by localized `errors.*`
 * text for mapped statuses and by generic failure text otherwise.
 */
export function localizedErrorMessage(code: number, rawMessage: string): string {
  const technical = !rawMessage.trim() || rawMessage === `HTTP ${code}` || rawMessage === `code=${code}`
  if (!technical) return rawMessage
  const key = errorKeyForCode(code)
  if (key) {
    const text = translate(key)
    if (text && text !== key) return text
  }
  return translate('common.failed')
}

/** Business failure carried inside an HTTP 200 envelope (code != 0). */
export class ApiBusinessError extends Error {
  readonly code: number
  readonly traceId: string | null
  /** Backend copy, kept for logs and diagnostics only; never render this directly. */
  readonly rawMessage: string

  constructor(code: number, message: string, traceId: string | null = null, rawMessage: string = message) {
    super(message)
    this.name = 'ApiBusinessError'
    this.code = code
    this.traceId = traceId
    this.rawMessage = rawMessage
  }

  /** Renderable form: `String(error)` must not leak the `ApiBusinessError: ` prefix. */
  override toString(): string {
    return this.message
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
        const rawMessage = typeof body.message === 'string' && body.message ? body.message : `code=${body.code}`
        const traceId = typeof body.traceId === 'string' ? body.traceId : null
        throw new ApiBusinessError(body.code, localizedErrorMessage(body.code, rawMessage), traceId, rawMessage)
      }
      return ('data' in body ? body.data : undefined) as T
    }
  }
  return body as T
}
