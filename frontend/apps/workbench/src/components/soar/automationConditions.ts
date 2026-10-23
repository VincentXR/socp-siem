import type { RuleCondition } from '../../api'

export const AUTOMATION_OPERATORS = ['eq', 'ne', 'contains']
const wireOperators: Record<string, string> = { eq: 'equals', ne: 'not_equals', contains: 'contains' }
const rowOperators: Record<string, string> = { equals: 'eq', eq: 'eq', not_equals: 'ne', 'not-equals': 'ne', contains: 'contains' }
const legacyFields: Record<string, string> = {
  type: 'type', eventType: 'eventType', severity: 'data.severity', entity: 'data.entity',
  ruleId: 'data.ruleId', source: 'data.source', host: 'data.host',
}
type ObjectValue = Record<string, unknown>
type ConditionView = { kind: 'simple' | 'advanced' | 'legacy' | 'invalid'; rows: RuleCondition[] }
function object(value: unknown): value is ObjectValue {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}
function exactKeys(value: ObjectValue, allowed: string[]): boolean {
  return Object.keys(value).every(key => allowed.includes(key))
}

/** Mirror the backend condition tree, keeping typed/nested predicates in JSON. */
function validCondition(value: unknown): boolean {
  if (!object(value)) return false
  if (!Object.keys(value).length) return true
  if (exactKeys(value, ['all']) && Array.isArray(value.all)) return value.all.every(validCondition)
  if (exactKeys(value, ['any']) && Array.isArray(value.any)) return value.any.every(validCondition)
  if (exactKeys(value, ['expression']) && typeof value.expression === 'string') return !!value.expression.trim()
  if (!exactKeys(value, ['field', 'operator', 'value']) || typeof value.field !== 'string' || !value.field.trim()) return false
  const suppliedOperator = value.operator ?? 'equals'
  if (typeof suppliedOperator !== 'string') return false
  const operator = suppliedOperator.toLowerCase()
  if (operator === 'exists') return true
  if (operator === 'in') return Array.isArray(value.value) && value.value.every(item => ['string', 'number', 'boolean'].includes(typeof item))
  return Object.hasOwn(rowOperators, operator) && ['string', 'number', 'boolean'].includes(typeof value.value)
}

function simpleRow(value: unknown): RuleCondition | null {
  if (!object(value) || !validCondition(value) || typeof value.field !== 'string' || typeof value.value !== 'string' || !value.value.trim()) return null
  const operator = String(value.operator ?? 'equals').toLowerCase()
  const op = Object.hasOwn(rowOperators, operator) ? rowOperators[operator] : undefined
  return op ? { field: value.field, op, value: value.value } : null
}

export function inspectAutomationConditions(value: unknown): ConditionView {
  if (object(value) && Object.keys(value).length && Object.entries(value).every(([key, item]) =>
    !['field', 'operator', 'value', 'all', 'any', 'expression'].includes(key) && typeof item === 'string')) {
    return { kind: 'legacy', rows: [] }
  }
  if (!validCondition(value)) return { kind: 'invalid', rows: [] }
  const condition = value as ObjectValue
  if (!Object.keys(condition).length) return { kind: 'simple', rows: [] }
  const leaf = simpleRow(condition)
  if (leaf) return { kind: 'simple', rows: [leaf] }
  if (Array.isArray(condition.all)) {
    const rows = condition.all.map(simpleRow)
    if (rows.every((row): row is RuleCondition => row !== null)) return { kind: 'simple', rows }
  }
  return { kind: 'advanced', rows: [] }
}

export function compileAutomationConditions(rows: RuleCondition[]): ObjectValue {
  if (rows.some(row => !row.field.trim() || !row.value.trim() || !AUTOMATION_OPERATORS.includes(row.op))) {
    throw new Error('incompleteAutomationCondition')
  }
  return rows.length ? { all: rows.map(row => ({ field: row.field.trim(), operator: wireOperators[row.op], value: row.value })) } : {}
}

/** Explicit migration only: callers must keep the saved rule disabled. */
export function convertLegacyAutomationConditions(value: unknown): ObjectValue {
  if (inspectAutomationConditions(value).kind !== 'legacy') throw new Error('invalidAutomationConditions')
  return compileAutomationConditions(Object.entries(value as ObjectValue).map(([field, expected]) => ({
    field: Object.hasOwn(legacyFields, field) ? legacyFields[field] : field, op: 'eq', value: String(expected),
  })))
}
