import assert from 'node:assert/strict'
import test from 'node:test'
import { useRequest } from '../src/composables/useRequest.ts'
import { unwrapApiBody } from '../src/lib/api-response.ts'
import { withQuery } from '../src/lib/query.ts'
import type { ReportSummary, ReportTrend, SearchResult } from '../src/api.ts'

test('unwraps successful API envelopes', () => {
  assert.deepEqual(unwrapApiBody<{ id: string }>({ code: 0, data: { id: 'a-1' } }), { id: 'a-1' })
})

test('raises the server message for failed API envelopes', () => {
  assert.throws(
    () => unwrapApiBody({ code: 1003, message: 'invalid query', data: null }),
    { message: 'invalid query' },
  )
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
