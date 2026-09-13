import assert from 'node:assert/strict'
import test from 'node:test'
import { useRequest } from '../src/composables/useRequest.ts'
import { unwrapApiBody, ApiBusinessError, type ApiEnvelope } from '../src/lib/api-response.ts'
import { withQuery } from '../src/lib/query.ts'
import type { ReportSummary, ReportTrend, SearchResult } from '../src/api.ts'

test('unwraps successful API envelopes', () => {
  const response: ApiEnvelope<{ id: string }> = {
    code: 0, message: 'ok', data: { id: 'a-1' }, traceId: 'trace-1', timestamp: '2026-08-24T00:00:00Z',
  }
  assert.deepEqual(unwrapApiBody<{ id: string }>(response), { id: 'a-1' })
})

test('raises the server message for failed API envelopes', () => {
  assert.throws(
    () => unwrapApiBody({ code: 1003, message: 'invalid query', data: null }),
    { message: 'invalid query' },
  )
})

test('raises a business error for failed envelopes serialized without a data key', () => {
  // ApiResult serializes with NON_NULL, so fail() drops the null data key.
  try {
    unwrapApiBody({ code: 1003, message: 'invalid query', traceId: 'trace-1', timestamp: '2026-09-13T00:00:00Z' })
    assert.fail('expected unwrapApiBody to throw')
  } catch (error) {
    assert.ok(error instanceof ApiBusinessError)
    assert.equal(error.code, 1003)
    assert.equal(error.message, 'invalid query')
    assert.equal(error.traceId, 'trace-1')
  }
})

test('raises a business error with the fallback message when the envelope omits message', () => {
  try {
    unwrapApiBody({ code: 500, message: null, data: null })
    assert.fail('expected unwrapApiBody to throw')
  } catch (error) {
    assert.ok(error instanceof ApiBusinessError)
    assert.equal(error.code, 500)
    assert.equal(error.message, 'code=500')
  }
})

test('unwraps Void success envelopes without a data key to undefined', () => {
  const body = { code: 0, message: 'ok', traceId: null, timestamp: '2026-09-13T00:00:00Z' }
  assert.equal(unwrapApiBody(body), undefined)
})

test('keeps bare bodies with a numeric domain code and no envelope markers unchanged', () => {
  const body = { code: 404, detail: 'missing' }
  assert.deepEqual(unwrapApiBody(body), body)
})

test('keeps bare bodies with a domain string code field unchanged', () => {
  const body = { code: 'SYSLOG', name: 'syslog' }
  assert.deepEqual(unwrapApiBody(body), body)
})

test('keeps non-envelope response bodies unchanged', () => {
  assert.deepEqual(unwrapApiBody<string[]>(['a', 'b']), ['a', 'b'])
})

test('keeps search and report provenance metadata from successful responses', () => {
  const search = unwrapApiBody<SearchResult>({
    code: 0,
    data: {
      total: 1,
      events: [],
      stat: null,
      source: 'local-cache',
      degraded: true,
      freshness: '2026-08-23T11:00:00Z',
      degradationReason: 'OpenSearch did not return a result',
    },
  })
  const summary = unwrapApiBody<ReportSummary>({
    code: 0,
    data: {
      date: '2026-08-23', total: 1, bySeverity: { HIGH: 1 }, byRule: [],
      source: 'alert-web', degraded: true, freshness: null,
      degradationReason: 'ClickHouse unavailable',
    },
  })
  const trend: ReportTrend = {
    days: ['08-23'], counts: [1], source: 'clickhouse', degraded: false,
    freshness: '2026-08-23T11:00:00Z', degradationReason: null,
  }

  assert.equal(search.degraded, true)
  assert.equal(search.source, 'local-cache')
  assert.equal(summary.degradationReason, 'ClickHouse unavailable')
  assert.equal(trend.freshness, '2026-08-23T11:00:00Z')
})

test('encodes query values and omits empty values', () => {
  assert.equal(withQuery('/search', { q: 'a b&c', page: 1, empty: '', missing: undefined }), '/search?q=a+b%26c&page=1')
})

test('aborts a stale request when a newer request starts', async () => {
  const requests = useRequest<number>()
  let aborted = false
  const first = requests.execute(signal => new Promise<number>((resolve, reject) => {
    signal.addEventListener('abort', () => {
      aborted = true
      reject(signal.reason)
    }, { once: true })
    setTimeout(() => resolve(1), 50)
  }))
  const second = requests.execute(async () => 2)

  assert.equal(await second, 2)
  assert.equal(await first, undefined)
  assert.equal(aborted, true)
  assert.equal(requests.loading.value, false)
})
