import type { RuleCondition } from '../../../api'

export const CONDITION_OPERATORS: Record<string, string> = {
  eq: '==', ne: '!=', contains: 'contains', gt: '>', gte: '>=', lt: '<', lte: '<=',
}

// This editor represents one comparison. Advanced expressions stay untouched.
export type ExpressionCondition = RuleCondition & { literalType?: 'string' | 'number' | 'boolean' }

export function parseCondition(expression: string): ExpressionCondition[] {
  const match = expression.trim().match(/^([A-Za-z_][\w.]*)\s+(==|!=|>=|<=|>|<|contains)\s+(?:"([^"\\]*)"|'([^'\\]*)'|(-?\d+|true|false))$/)
  if (!match) return []
  const op = Object.keys(CONDITION_OPERATORS).find(key => CONDITION_OPERATORS[key] === match[2])!
  return [{ field: match[1], op, value: match[3] ?? match[4] ?? match[5], literalType: match[5] == null ? 'string' : /^(true|false)$/.test(match[5]) ? 'boolean' : 'number' }]
}

export function compileCondition(rows: ExpressionCondition[]): string {
  if (!rows.length) return ''
  if (rows.length !== 1) throw new Error('Use advanced mode for multiple conditions.')
  const { field, op, value, literalType = 'string' } = rows[0]
  if (!/^[A-Za-z_][\w.]*$/.test(field.trim())) throw new Error('Select a valid workflow field.')
  const operator = CONDITION_OPERATORS[op]
  if (!operator) throw new Error('This operator is not supported by the workflow engine.')
  if (!value.trim()) throw new Error('A comparison value is required.')
  // Match the server grammar. Do not turn unquoted text into variable references.
  if (!/^[A-Za-z0-9_ .()<>!=&|+*/%,\[\]-]+$/.test(value)) {
    throw new Error('This value requires advanced expression editing.')
  }
  if (literalType === 'number' && !/^-?\d+$/.test(value.trim())) throw new Error('Enter an integer supported by the workflow engine.')
  if (literalType === 'boolean' && !/^(true|false)$/.test(value.trim())) throw new Error('Enter true or false.')
  const literal = literalType === 'string' ? JSON.stringify(value) : value.trim()
  return `${field.trim()} ${operator} ${literal}`
}
