import { describe, expect, it } from 'vitest'
import { compileCondition, parseCondition } from '../conditionExpression'

describe('workflow comparison contract', () => {
  it('quotes text instead of compiling a variable reference', () => {
    expect(compileCondition([{ field: 'inputs.severity', op: 'eq', value: 'HIGH' }])).toBe('inputs.severity == "HIGH"')
  })
  it('preserves string, integer and boolean literals on round trip', () => {
    for (const expression of ['inputs.code == "123"', 'inputs.count >= 123', 'inputs.enabled == true']) {
      expect(compileCondition(parseCondition(expression))).toBe(expression)
    }
  })
  it('never silently discards a second row or an unsupported operator', () => {
    const row = { field: 'inputs.user', op: 'eq', value: 'admin' }
    expect(() => compileCondition([row, row])).toThrow()
    expect(() => compileCondition([{ ...row, op: 'startswith' }])).toThrow()
    expect(compileCondition([])).toBe('')
  })
  it('keeps complex expressions out of the single comparison editor', () => {
    expect(parseCondition('inputs.a == "one" && inputs.b == "two"')).toEqual([])
    expect(parseCondition('inputs.a == inputs.b')).toEqual([])
  })
  it('rejects invalid numeric and boolean literals', () => {
    expect(() => compileCondition([{ field: 'inputs.n', op: 'gt', value: '1.5', literalType: 'number' }])).toThrow()
    expect(() => compileCondition([{ field: 'inputs.n', op: 'eq', value: 'yes', literalType: 'boolean' }])).toThrow()
  })
})
