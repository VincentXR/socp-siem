// @vitest-environment node
import { createServer } from 'node:http'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderSourceConfig } from '../src/api/ingest'
import { renderConfig } from '../src/api/search'
import { setUnauthorizedHandler } from '../src/api/core'

const toml = '# Vector configuration\ndata_dir = "/var/lib/vector"\n'
const nativeFetch = globalThis.fetch
let origin = ''
let responseStatus = 200
let holdBody = false
const requests: Array<{ method: string; path: string; accept: string }> = []
// A loopback HTTP fixture preserves the controller's text/plain contract.
// Unlike mocking get/post, the real client negotiates and decodes the response.
const server = createServer((request, response) => {
  response.setHeader('Connection', 'close')
  const accept = request.headers.accept || ''
  requests.push({ method: request.method!, path: request.url!, accept })
  if (!accept.includes('text/plain')) {
    response.writeHead(406, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ code: 406, message: 'No acceptable representation' }))
    return
  }
  if (responseStatus !== 200) {
    response.writeHead(responseStatus, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ code: responseStatus, message: 'Rendering denied' }))
    return
  }
  response.writeHead(200, { 'Content-Type': 'text/plain;charset=UTF-8' })
  if (holdBody) { response.write('# pending configuration\n'); return }
  response.end(toml)
})

beforeAll(async () => {
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  if (!address || typeof address === 'string') throw new Error('HTTP fixture did not open a port')
  origin = `http://127.0.0.1:${address.port}`
})
beforeEach(() => {
  responseStatus = 200; holdBody = false; requests.length = 0
  vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => nativeFetch(new URL(String(input), origin), init))
})
afterEach(() => { vi.restoreAllMocks(); setUnauthorizedHandler(null) })
afterAll(async () => {
  server.closeAllConnections()
  await new Promise<void>(resolve => server.close(() => resolve()))
})

describe('Vector configuration text transport', () => {
  it('renders both source and whole-tenant configuration as text, preserving encoded IDs and cookies', async () => {
    expect(await renderSourceConfig('source/a')).toBe(toml)
    expect(await renderConfig()).toBe(toml)
    expect(requests).toMatchObject([
      { method: 'GET', path: '/search-config/api/v1/sources/source%2Fa/vector-config', accept: expect.stringContaining('text/plain') },
      { method: 'POST', path: '/search-config/api/v1/render', accept: expect.stringContaining('text/plain') },
    ])
    expect(vi.mocked(fetch).mock.calls.every(([, init]) => init?.credentials === 'same-origin')).toBe(true)
  })

  it('retains JSON error details and the existing unauthorized-session callback', async () => {
    const unauthorized = vi.fn()
    setUnauthorizedHandler(unauthorized)
    responseStatus = 401
    await expect(renderSourceConfig('source-a')).rejects.toMatchObject({ status: 401, message: 'Rendering denied' })
    expect(unauthorized).toHaveBeenCalledTimes(1)
    responseStatus = 409
    await expect(renderConfig()).rejects.toMatchObject({ status: 409, message: 'Rendering denied' })
    expect(unauthorized).toHaveBeenCalledTimes(1)
  })

  it('keeps timeout coverage while reading a response body and honors caller cancellation', async () => {
    holdBody = true
    await expect(renderSourceConfig('source-a', { timeoutMs: 1000 })).rejects.toMatchObject({ name: 'TimeoutError' })
    const managed = vi.mocked(fetch).mock.calls[0][1]!.signal!
    expect(managed.aborted).toBe(true)
    expect(managed.reason.name).toBe('TimeoutError')
    const controller = new AbortController()
    controller.abort()
    await expect(renderSourceConfig('source-a', { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' })
    expect(requests).toHaveLength(1)
  })
})
