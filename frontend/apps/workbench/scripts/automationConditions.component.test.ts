import { describe, expect, it } from 'vitest'
import {
  compileAutomationConditions,
  convertLegacyAutomationConditions,
  inspectAutomationConditions,
} from '../src/components/soar/automationConditions'

describe('automation condition wire contract', () => {
  it('emits canonical operators and retains repeated fields as separate predicates', () => {
    const rows = [
      { field: ' data.entity ', op: 'eq', value: '192.0.2.126' },
      { field: 'data.entity', op: 'ne', value: '192.0.2.127' },
      { field: 'data.host', op: 'contains', value: 'manual host' },
    ]
    expect(compileAutomationConditions(rows)).toEqual({ all: [
      { field: 'data.entity', operator: 'equals', value: '192.0.2.126' },
      { field: 'data.entity', operator: 'not_equals', value: '192.0.2.127' },
      { field: 'data.host', operator: 'contains', value: 'manual host' },
    ] })
    expect(rows[0].field).toBe(' data.entity ')
  })

  it('round-trips a flat canonical all group through simple rows', () => {
    const condition = { all: [
      { field: 'data.entity', operator: 'equals', value: '192.0.2.126' },
      { field: 'data.severity', operator: 'not_equals', value: 'LOW' },
      { field: 'data.host', operator: 'contains', value: 'manual host' },
    ] }
    const view = inspectAutomationConditions(condition)
    expect(view.kind).toBe('simple')
    expect(view.rows.map(row => row.op)).toEqual(['eq', 'ne', 'contains'])
    expect(compileAutomationConditions(view.rows)).toEqual(condition)
  })

  it('reads a canonical leaf without changing its meaning when saved as an all group', () => {
    const leaf = { field: 'data.entity', operator: 'equals', value: '192.0.2.126' }
    const view = inspectAutomationConditions(leaf)
    expect(view).toEqual({ kind: 'simple', rows: [
      { field: 'data.entity', op: 'eq', value: '192.0.2.126' },
    ] })
    expect(compileAutomationConditions(view.rows)).toEqual({ all: [leaf] })
  })

  it.each([
    { operator: 'not-equals', canonical: 'not_equals' },
    { operator: 'EQUALS', canonical: 'equals' },
    { operator: 'eq', canonical: 'equals' },
    { operator: 'CONTAINS', canonical: 'contains' },
    { operator: 'NOT_EQUALS', canonical: 'not_equals' },
  ])('accepts backend alias $operator without changing the source JSON', ({ operator, canonical }) => {
    const original = { field: 'data.entity', operator, value: '192.0.2.127' }
    const view = inspectAutomationConditions(original)
    expect(view.kind).toBe('simple')
    expect(compileAutomationConditions(view.rows)).toEqual({ all: [
      { field: 'data.entity', operator: canonical, value: '192.0.2.127' },
    ] })
    expect(original.operator).toBe(operator)
  })

  it('keeps the existing unconditional empty object valid', () => {
    expect(inspectAutomationConditions({})).toEqual({ kind: 'simple', rows: [] })
    expect(compileAutomationConditions([])).toEqual({})
  })

  it.each([
    { title: 'any group', value: { any: [{ field: 'data.entity', operator: 'equals', value: '192.0.2.126' }] } },
    { title: 'nested all group', value: { all: [{ all: [{ field: 'data.entity', value: '192.0.2.126' }] }] } },
    { title: 'expression', value: { expression: "data.severity == 'HIGH'" } },
    { title: 'numeric value', value: { field: 'data.count', operator: 'equals', value: 0 } },
    { title: 'boolean value', value: { field: 'data.trusted', operator: 'equals', value: false } },
    { title: 'typed in list', value: { field: 'data.level', operator: 'in', value: [1, 'HIGH', true] } },
    { title: 'exists predicate', value: { field: 'data.entity', operator: 'exists' } },
    { title: 'uppercase IN', value: { field: 'data.level', operator: 'IN', value: [1, 'HIGH'] } },
    { title: 'uppercase EXISTS', value: { field: 'data.entity', operator: 'EXISTS' } },
  ])('preserves $title as advanced JSON instead of flattening it', ({ value }) => {
    const before = structuredClone(value)
    expect(inspectAutomationConditions(value)).toEqual({ kind: 'advanced', rows: [] })
    expect(value).toEqual(before)
  })

  it('requires explicit migration of legacy flat conditions and maps event fields', () => {
    const legacy = { entity: '192.0.2.126', severity: 'HIGH', ruleId: 'RULE-MANUAL', type: 'alarm.created' }
    expect(inspectAutomationConditions(legacy)).toEqual({ kind: 'legacy', rows: [] })
    expect(convertLegacyAutomationConditions(legacy)).toEqual({ all: [
      { field: 'data.entity', operator: 'equals', value: '192.0.2.126' },
      { field: 'data.severity', operator: 'equals', value: 'HIGH' },
      { field: 'data.ruleId', operator: 'equals', value: 'RULE-MANUAL' },
      { field: 'type', operator: 'equals', value: 'alarm.created' },
    ] })
    expect(legacy.entity).toBe('192.0.2.126')
  })

  it('does not migrate an already canonical or advanced condition', () => {
    expect(() => convertLegacyAutomationConditions({ field: 'data.entity', value: '192.0.2.126' }))
      .toThrow('invalidAutomationConditions')
    expect(() => convertLegacyAutomationConditions({ any: [] })).toThrow('invalidAutomationConditions')
  })

  it.each([
    { title: 'null', value: null },
    { title: 'array root', value: [] },
    { title: 'non-array all', value: { all: {} } },
    { title: 'mixed group and leaf', value: { all: [], field: 'data.entity', value: '192.0.2.126' } },
    { title: 'blank field', value: { field: ' ', operator: 'equals', value: '192.0.2.126' } },
    { title: 'missing value', value: { field: 'data.entity', operator: 'equals' } },
    { title: 'unknown operator', value: { field: 'data.entity', operator: 'regex', value: '.*' } },
    { title: 'prototype operator', value: { field: 'data.entity', operator: 'constructor', value: '192.0.2.126' } },
    { title: 'unknown leaf property', value: { field: 'data.entity', value: '192.0.2.126', negate: true } },
    { title: 'object in list', value: { field: 'data.entity', operator: 'in', value: [{}] } },
    { title: 'blank expression', value: { expression: ' ' } },
    { title: 'non-string legacy value', value: { entity: 126 } },
  ])('rejects $title rather than silently changing the predicate', ({ value }) => {
    expect(inspectAutomationConditions(value)).toEqual({ kind: 'invalid', rows: [] })
  })

  it.each([
    { field: ' ', op: 'eq', value: '192.0.2.126' },
    { field: 'data.entity', op: 'eq', value: ' ' },
    { field: 'data.entity', op: 'regex', value: '.*' },
    { field: 'data.entity', op: 'constructor', value: '192.0.2.126' },
  ])('rejects incomplete or unsupported simple rows: %j', row => {
    expect(() => compileAutomationConditions([row])).toThrow('incompleteAutomationCondition')
  })
})
