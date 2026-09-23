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

test('preserves multiline quoted fields, escaped quotes, CRLF and BOM', async () => {
  const file = new File(['\uFEFFname,description\r\nweb-01,"first\r\nsecond, ""quoted"""\r\n'], 'assets.csv')
  assert.deepEqual(await readImportRows(file), [{ name: 'web-01', description: 'first\r\nsecond, "quoted"' }])
})

test('rejects ambiguous CSV shapes instead of overwriting headers or dropping cells', async () => {
  for (const text of ['name,name\na,b', 'name,ip\nhost', 'name\na,b', 'name\n"unclosed', 'name\n"closed"extra', 'name\na"b']) {
    await assert.rejects(readImportRows(new File([text], 'rows.csv')))
  }
})

test('bounds file admission before reading and rejects empty or oversized row sets', async () => {
  let read = false
  await assert.rejects(readImportRows({ name: 'large.csv', size: 2097153, text: async () => { read = true; return 'name\nhost' } } as File))
  assert.equal(read, false)
  for (const text of ['{}', '[]', '[null]', JSON.stringify(Array.from({ length: 501 }, () => ({ name: 'host' })))]) {
    await assert.rejects(readImportRows(new File([text], 'rows.json')))
  }
  await assert.rejects(readImportRows(new File(['name\n' + 'host\n'.repeat(501)], 'rows.csv')))
  assert.equal((await readImportRows(new File(['name\n' + 'host\n'.repeat(500)], 'rows.csv'))).length, 500)
})
