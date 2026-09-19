import assert from 'node:assert/strict'
import test from 'node:test'
import { useRequest } from '../src/composables/useRequest.ts'
import {
  ApiBusinessError,
  errorKeyForCode,
  localizedErrorMessage,
  unwrapApiBody,
  type ApiEnvelope,
} from '../src/lib/api-response.ts'
import { withQuery } from '../src/lib/query.ts'
import { tOr } from '../src/utils/i18nLabel.ts'
import { translate } from '../src/i18n/index.ts'
import type { ReportSummary, ReportTrend, SearchResult } from '../src/api.ts'

test('unwraps successful API envelopes', () => {
  const response: ApiEnvelope<{ id: string }> = {
    code: 0, message: 'ok', data: { id: 'a-1' }, traceId: 'trace-1', timestamp: '2026-08-24T00:00:00Z',
  }
  assert.deepEqual(unwrapApiBody<{ id: string }>(response), { id: 'a-1' })
})

test('keeps the backend copy for business codes without an HTTP status meaning', () => {
  assert.throws(
    () => unwrapApiBody({ code: 1003, message: 'invalid query', data: null }),
    { message: 'invalid query', rawMessage: 'invalid query' },
  )
})

test('raises a localized business error for codes that mirror an HTTP status', () => {
  // ApiResult is serialized without a data key on failed API envelopes: NON_NULL drops the null payload.
  try {
    unwrapApiBody({ code: 403, message: 'role viewer cannot publish', traceId: 'trace-1', timestamp: '2026-09-13T00:00:00Z' })
    assert.fail('expected unwrapApiBody to throw')
  } catch (error) {
    assert.ok(error instanceof ApiBusinessError)
    assert.equal(error.code, 403)
    // A meaningful backend sentence is the operator-facing text; plumbing is what
    // the localized `errors.*` fallback exists for.
    assert.equal(error.message, 'role viewer cannot publish')
    assert.equal(error.rawMessage, 'role viewer cannot publish')
    assert.equal(error.traceId, 'trace-1')
    // String(error) must stay renderable: no `ApiBusinessError: ` prefix, no raw code.
    assert.equal(String(error), error.message)
  }
})

test('localizes a failed envelope that omits the message', () => {
  try {
    unwrapApiBody({ code: 500, message: null, data: null })
    assert.fail('expected unwrapApiBody to throw')
  } catch (error) {
    assert.ok(error instanceof ApiBusinessError)
    assert.equal(error.code, 500)
    assert.equal(error.message, translate('errors.SERVER_ERROR'))
    assert.equal(error.rawMessage, 'code=500')
  }
})

test('maps HTTP statuses and mirror-coded business codes to localized error keys', () => {
  assert.equal(errorKeyForCode(401), 'errors.UNAUTHORIZED')
  assert.equal(errorKeyForCode(403), 'errors.FORBIDDEN')
  assert.equal(errorKeyForCode(404), 'errors.NOT_FOUND')
  assert.equal(errorKeyForCode(429), 'errors.RATE_LIMIT_EXCEEDED')
  assert.equal(errorKeyForCode(500), 'errors.SERVER_ERROR')
  assert.equal(errorKeyForCode(502), 'errors.SERVER_ERROR')
  // 4xx client details and custom business codes carry their own backend copy.
  assert.equal(errorKeyForCode(400), null)
  assert.equal(errorKeyForCode(409), null)
  assert.equal(errorKeyForCode(1003), null)
  assert.equal(errorKeyForCode(10001), null)
  assert.equal(errorKeyForCode(200), null)
})

test('renders mapped statuses from locale text instead of transport plumbing', () => {
  assert.equal(localizedErrorMessage(502, 'HTTP 502'), translate('errors.SERVER_ERROR'))
  assert.equal(localizedErrorMessage(404, 'HTTP 404'), translate('errors.NOT_FOUND'))
  assert.equal(localizedErrorMessage(429, 'Too many authentication attempts'), 'Too many authentication attempts')
  // Unmapped codes keep the useful backend sentence and only hide technical placeholders.
  assert.equal(localizedErrorMessage(400, 'name: must not be blank'), 'name: must not be blank')
  assert.equal(localizedErrorMessage(400, 'HTTP 400'), translate('common.failed'))
  assert.equal(localizedErrorMessage(1003, 'code=1003'), translate('common.failed'))
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

test('falls back only when a message key is genuinely missing', () => {
  // vue-i18n echoes the key on a miss, so `t(key) || fallback` keeps the key path.
  const translated = translate('report.sources.alert-web')
  assert.notEqual(translated, 'report.sources.alert-web')
  assert.equal(tOr(translate, 'report.sources.alert-web', 'alert-web'), translated)
  assert.equal(tOr(translate, 'report.sources.missing-entry', 'alert-web'), 'alert-web')
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
