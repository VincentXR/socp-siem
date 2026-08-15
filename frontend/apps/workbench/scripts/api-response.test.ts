import assert from 'node:assert/strict'
import test from 'node:test'
import { unwrapApiBody } from '../src/lib/api-response.ts'

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
