import { localizedErrorMessage, unwrapApiBody } from '../lib/api-response'
import { getCurrentLocale } from '../i18n/locale-manager'

const DEFAULT_TIMEOUT_MS = 15_000

export interface ApiRequestOptions {
  signal?: AbortSignal
  timeoutMs?: number
  auth?: boolean
  unwrap?: boolean
  notifyUnauthorized?: boolean
}

export class ApiError extends Error {
  readonly status: number
  /** Backend copy, kept for logs and diagnostics only; `message` is the renderable text. */
  readonly rawMessage: string
  /** Structured failure details, when the server includes them in its API envelope. */
  readonly data: unknown

  constructor(status: number, message: string, rawMessage: string = message, data?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.rawMessage = rawMessage
    this.data = data
  }

  /** Renderable form: `String(error)` must show the localized copy, not `ApiError: HTTP 502`. */
  override toString(): string {
    return this.message
  }
}

export function isAbortError(error: unknown): boolean {
  return error instanceof Error && (error.name === 'AbortError' || error.name === 'TimeoutError')
}

let unauthorizedHandler: (() => void) | null = null
export function setUnauthorizedHandler(fn: (() => void) | null): void { unauthorizedHandler = fn }

async function assertOk(res: Response, notifyUnauthorized = true): Promise<void> {
  if (res.ok) return
  const upstreamAuthenticationFailure = res.status === 401 && res.headers.get('X-Socp-Session-Status') === 'valid'
  if (res.status === 401 && !upstreamAuthenticationFailure && notifyUnauthorized) unauthorizedHandler?.()
  let rawMessage = `HTTP ${res.status}`
  let data: unknown
  try {
    const body = await res.clone().json()
    data = body?.data
    if (typeof body?.message === 'string' && body.message) rawMessage = body.message
    else if (typeof body?.error === 'string' && body.error) rawMessage = body.error
  } catch { /* retain the HTTP status for non-JSON errors */ }
  // The gateway already validated this session. A downstream proof/JWKS failure
  // is an upstream fault, not a reason to discard the browser's login or drafts.
  const status = upstreamAuthenticationFailure ? 502 : res.status
  throw new ApiError(status, localizedErrorMessage(status, upstreamAuthenticationFailure ? 'HTTP 502' : rawMessage), rawMessage, data)
}

function createRequestSignal(options: ApiRequestOptions): { signal: AbortSignal; cleanup: () => void } {
  const controller = new AbortController()
  const timeout = globalThis.setTimeout(() => {
    controller.abort(new DOMException('Request timed out', 'TimeoutError'))
  }, options.timeoutMs ?? DEFAULT_TIMEOUT_MS)
  const onAbort = () => controller.abort(options.signal?.reason)
  if (options.signal?.aborted) onAbort()
  else options.signal?.addEventListener('abort', onAbort, { once: true })
  return {
    signal: controller.signal,
    cleanup: () => {
      globalThis.clearTimeout(timeout)
      options.signal?.removeEventListener('abort', onAbort)
    },
  }
}

export async function requestRaw(path: string, init: RequestInit = {}, options: ApiRequestOptions = {}): Promise<{ response: Response; cleanup: () => void }> {
  const headers = new Headers(init.headers)
  if (!headers.has('Accept')) headers.set('Accept', 'application/json')
  if (!headers.has('Accept-Language')) headers.set('Accept-Language', getCurrentLocale())
  const managed = createRequestSignal({ ...options, signal: options.signal ?? init.signal ?? undefined })
  try {
    return {
      response: await fetch(path, { ...init, headers, signal: managed.signal, credentials: 'same-origin' }),
      cleanup: managed.cleanup,
    }
  } catch (error) {
    managed.cleanup()
    throw error
  }
}

export async function requestJson<T>(path: string, init: RequestInit = {}, options: ApiRequestOptions = {}): Promise<T> {
  const raw = await requestRaw(path, init, options)
  try {
    await assertOk(raw.response, options.notifyUnauthorized !== false)
    const text = await raw.response.text()
    if (!text) return undefined as T
    const body = JSON.parse(text) as unknown
    return options.unwrap === false ? body as T : unwrapApiBody<T>(body)
  } finally {
    raw.cleanup()
  }
}

/** Plain-text artifacts still use the same cookie, cancellation and error boundary. */
export async function requestText(path: string, init: RequestInit = {}, options: ApiRequestOptions = {}): Promise<string> {
  const headers = new Headers(init.headers)
  // Error responses use the JSON API envelope, even when success is TOML/text.
  headers.set('Accept', 'text/plain, application/json;q=0.9')
  const raw = await requestRaw(path, { ...init, headers }, options)
  try {
    await assertOk(raw.response, options.notifyUnauthorized !== false)
    return await raw.response.text()
  } finally {
    raw.cleanup()
  }
}

export async function get<T>(path: string, options?: ApiRequestOptions): Promise<T> {
  return requestJson<T>(path, {}, options)
}

export async function post<T>(path: string, data?: unknown, options?: ApiRequestOptions): Promise<T> {
  return requestJson<T>(path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: data === undefined ? undefined : JSON.stringify(data),
  }, options)
}

export async function put<T>(path: string, data?: unknown, options?: ApiRequestOptions): Promise<T> {
  return requestJson<T>(path, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: data === undefined ? undefined : JSON.stringify(data),
  }, options)
}

export async function patch<T>(path: string, data?: unknown, options?: ApiRequestOptions): Promise<T> {
  return requestJson<T>(path, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: data === undefined ? undefined : JSON.stringify(data),
  }, options)
}

export async function del<T>(path: string, options?: ApiRequestOptions): Promise<T> {
  return requestJson<T>(path, { method: 'DELETE' }, options)
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export async function downloadFile(path: string, filename: string): Promise<void> {
  const raw = await requestRaw(path)
  try {
    await assertOk(raw.response)
    downloadBlob(await raw.response.blob(), filename)
  } finally {
    raw.cleanup()
  }
}
