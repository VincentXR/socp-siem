import assert from 'node:assert/strict'
import test from 'node:test'
import { readImportRows } from '../src/lib/resource-import.ts'

test('reads CSV rows with quoted commas', async () => {
  const file = {
    name: 'assets.csv',
    text: async () => 'name,description\nweb-01,"production, web"\n',
  } as File

  assert.deepEqual(await readImportRows(file), [{ name: 'web-01', description: 'production, web' }])
})

test('reads JSON arrays and items envelopes', async () => {
  const file = {
    name: 'iocs.json',
    text: async () => JSON.stringify({ items: [{ type: 'IP', value: '203.0.113.7' }] }),
  } as File

  assert.deepEqual(await readImportRows(file), [{ type: 'IP', value: '203.0.113.7' }])
})
