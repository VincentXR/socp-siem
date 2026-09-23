import { describe, expect, it } from 'vitest'
import { prepareIocImport } from './ioc-import'

describe('indicator import admission', () => {
  it('retains JSON tag arrays and normalizes CSV fields without dropping rows', () => {
    const rows = prepareIocImport([{ type: 'domain', value: ' example.test ', tags: ['one', 'two'] },
      { value: '203.0.113.1', tags: 'one; two', severity: 'low' }])
    expect(rows[0]).toMatchObject({ type: 'DOMAIN', value: 'example.test', tags: ['one', 'two'], severity: 'HIGH' })
    expect(rows[1]).toMatchObject({ type: 'IP', severity: 'LOW', tags: ['one', 'two'] })
  })
  it('rejects the entire preview for invalid or missing fields', () => {
    for (const invalid of [{ value: '' }, { value: {} }, { value: 'x', type: 'unknown' },
      { value: 'x', severity: 'unknown' }, { value: 'x'.repeat(2049) }, { value: 'x', source: 's'.repeat(257) },
      { value: 'x', description: 'd'.repeat(4097) }, { value: 'x', tags: [null] },
      { value: 'x', tags: ['t'.repeat(129)] }, { value: 'x', tags: Array(33).fill('tag') }]) {
      expect(() => prepareIocImport([{ value: 'valid.test' }, invalid])).toThrow()
    }
    expect(() => prepareIocImport([])).toThrow()
    expect(() => prepareIocImport(Array(501).fill({ value: 'valid.test' }))).toThrow()
  })
})
