export type SchemaIssue = { path: string; code: 'required' | 'type' | 'choice' | 'length' | 'range' | 'unknown' | 'size' | 'schema' }

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function equal(left: unknown, right: unknown): boolean {
  if (left === right) return true
  if (Array.isArray(left) && Array.isArray(right)) return left.length === right.length && left.every((value, index) => equal(value, right[index]))
  if (object(left) && object(right)) {
    const keys = Object.keys(left)
    return keys.length === Object.keys(right).length && keys.every(key => Object.hasOwn(right, key) && equal(left[key], right[key]))
  }
  return false
}

function matchesType(value: unknown, type: unknown): boolean {
  switch (type) {
    case 'null': return value === null
    case 'object': return object(value)
    case 'array': return Array.isArray(value)
    case 'integer': return typeof value === 'number' && Number.isInteger(value)
    case 'number': return typeof value === 'number' && Number.isFinite(value)
    case 'string': return typeof value === 'string'
    case 'boolean': return typeof value === 'boolean'
    default: return false
  }
}

/** Mirrors the bounded manual-task value contract; Java regular expressions remain server-validated. */
export function validateSchemaInput(value: unknown, schema: unknown): SchemaIssue[] {
  const issues: SchemaIssue[] = []
  function visit(value: unknown, schema: unknown, path: string, depth: number) {
    if (issues.length >= 64) return
    const add = (code: SchemaIssue['code'], at = path) => issues.push({ path: at, code })
    if (depth > 20) { add('size'); return }
    if (schema === undefined || schema === null || schema === true) return
    if (schema === false) { add('schema'); return }
    if (!object(schema)) { add('schema'); return }
    if (Array.isArray(schema.enum) && !schema.enum.some(allowed => equal(allowed, value))) add('choice')
    if (Object.hasOwn(schema, 'const') && !equal(schema.const, value)) add('choice')
    if (schema.type !== undefined && schema.type !== null) {
      const types = Array.isArray(schema.type) ? schema.type : [schema.type]
      if (!types.some(type => matchesType(value, type))) { add('type'); return }
    }
    if (typeof value === 'string') {
      const min = Number.isInteger(schema.minLength) ? Math.max(0, Number(schema.minLength)) : 0
      const max = Number.isInteger(schema.maxLength) ? Math.min(65536, Math.max(0, Number(schema.maxLength))) : 65536
      if (value.length < min || value.length > max) add('length')
    } else if (typeof value === 'number') {
      if (!Number.isFinite(value)
        || (typeof schema.minimum === 'number' && value < schema.minimum)
        || (typeof schema.maximum === 'number' && value > schema.maximum)
        || (typeof schema.exclusiveMinimum === 'number' && value <= schema.exclusiveMinimum)
        || (typeof schema.exclusiveMaximum === 'number' && value >= schema.exclusiveMaximum)) add('range')
    } else if (object(value)) {
      if (Array.isArray(schema.required)) {
        for (const key of schema.required) {
          if (typeof key === 'string' && key.trim() && (!Object.hasOwn(value, key) || value[key] === undefined)) add('required', `${path}.${key}`)
        }
      }
      const properties = object(schema.properties) ? schema.properties : {}
      for (const [key, child] of Object.entries(value)) {
        if (child === undefined) continue // Omitted by the JSON request serializer.
        if (Object.hasOwn(properties, key)) visit(child, properties[key], `${path}.${key}`, depth + 1)
        else if (schema.additionalProperties === false) add('unknown', `${path}.${key}`)
        else if (object(schema.additionalProperties)) visit(child, schema.additionalProperties, `${path}.${key}`, depth + 1)
      }
    } else if (Array.isArray(value)) {
      const min = Number.isInteger(schema.minItems) ? Math.max(0, Number(schema.minItems)) : 0
      const max = Number.isInteger(schema.maxItems) ? Math.min(1000, Math.max(0, Number(schema.maxItems))) : 1000
      if (value.length < min || value.length > max) add('length')
      if (schema.items !== undefined) value.slice(0, 1000).forEach((item, index) => visit(item, schema.items, `${path}[${index}]`, depth + 1))
    }
  }
  visit(value, schema, '$', 0)
  try {
    if (new TextEncoder().encode(JSON.stringify(value)).length > 65536) issues.push({ path: '$', code: 'size' })
  } catch { issues.push({ path: '$', code: 'type' }) }
  return issues
}
