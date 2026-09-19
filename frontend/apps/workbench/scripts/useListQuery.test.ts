import assert from 'node:assert/strict'
import test from 'node:test'
import { nextTick, ref } from 'vue'
import { useListQuery, type ListQueryRoute } from '../src/composables/useListQuery.ts'

const ALLOWED_TYPES = ['IP', 'DOMAIN']

function harness(query: Record<string, unknown> = {}, overrides: { name?: unknown; total?: number; size?: number } = {}) {
  const route: ListQueryRoute = { name: overrides.name ?? 'assets', query }
  let writes = 0
  let lastQuery: Record<string, unknown> | undefined
  const listQuery = useListQuery({
    routeName: 'assets',
    route,
    total: ref(overrides.total ?? 100),
    size: ref(overrides.size ?? 10),
    fields: [{ key: 'type', validate: type => ALLOWED_TYPES.includes(type) ? type : '' }],
    router: { replace: to => { writes += 1; lastQuery = to.query } },
  })
  return { route, listQuery, writes: () => writes, last: () => lastQuery }
}

test('read hydrates refs from the route and validates whitelisted tokens', () => {
  const { listQuery } = harness({ q: '  host-7  ', type: 'DOMAIN', page: '3' })
  assert.equal(listQuery.keyword.value, '  host-7  ')
  assert.equal(listQuery.keywordParam.value, 'host-7')
  assert.equal(listQuery.filters.type.value, 'DOMAIN')
  assert.equal(listQuery.page.value, 3)
})

test('read rejects non-whitelisted filters', () => {
  const { listQuery } = harness({ type: 'NOT-A-TYPE' })
  assert.equal(listQuery.filters.type.value, '')
})

test('read falls back to page 1 for every illegal page token', () => {
  for (const raw of ['x', '0', '-2', '2.5', '']) {
    const { listQuery } = harness({ page: raw })
    assert.equal(listQuery.page.value, 1, `page ${JSON.stringify(raw)} should fall back to 1`)
  }
})

test('sync writes the trimmed keyword and the page, preserving unrelated keys', () => {
  const { listQuery, last } = harness({ q: 'old', assetId: 'A-1', type: 'IP', page: '1' })
  listQuery.keyword.value = '  web-01  '
  listQuery.page.value = 2
  listQuery.sync()
  assert.equal(last()!.q, 'web-01')
  assert.equal(last()!.page, '2')
  assert.equal(last()!.assetId, 'A-1')
})

test('sync drops empty managed keys and the default first page', () => {
  const { listQuery, last } = harness({ q: 'stale', type: 'IP', assetId: 'A-9' })
  listQuery.keyword.value = '   '
  listQuery.filters.type.value = ''
  listQuery.page.value = 1
  listQuery.sync()
  assert.equal(last()!.q, undefined)
  assert.equal(last()!.type, undefined)
  assert.equal(last()!.page, undefined)
  assert.equal(last()!.assetId, 'A-9')
})

test('sync clamps the page to the last real page before writing the URL', () => {
  const { listQuery, last } = harness({}, { total: 50, size: 10 })
  listQuery.page.value = 99
  listQuery.sync()
  assert.equal(last()!.page, '5')
})

test('sync is a no-op while another route is active', () => {
  const { listQuery, writes } = harness({}, { name: 'endpoints' })
  listQuery.page.value = 3
  listQuery.sync()
  assert.equal(writes(), 0)
})

test('applyRouteQuery reads the route and suppresses the echo write until the next tick', async () => {
  const { route, listQuery, writes } = harness({ q: 'first', page: '1' })
  listQuery.page.value = 4
  route.query = { q: 'second', page: '3' }
  const before = writes()
  listQuery.applyRouteQuery()
  assert.equal(listQuery.keyword.value, 'second')
  assert.equal(listQuery.page.value, 3)
  listQuery.sync()
  assert.equal(writes(), before, 'echo write must be suppressed while applying route query')
  await nextTick()
  listQuery.keyword.value = 'third'
  listQuery.sync()
  assert.equal(writes(), before + 1)
})
