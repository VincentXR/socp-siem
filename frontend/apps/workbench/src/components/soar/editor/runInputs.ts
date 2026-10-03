/** Materialize declared defaults once, then render and submit the same object.
 * Context only fills explicitly declared context fields, never arbitrary targets. */
export function initialRunInputs(schema: unknown, context: { alarmId?: string; caseId?: string }): Record<string, unknown> {
  if (!schema || typeof schema !== 'object' || Array.isArray(schema)) return {}
  const root = schema as Record<string, unknown>
  const defaults = defaultValue(root)
  const result: Record<string, unknown> = defaults && typeof defaults === 'object' && !Array.isArray(defaults)
    ? defaults as Record<string, unknown> : {}
  const properties = root.properties && typeof root.properties === 'object'
    ? root.properties as Record<string, unknown> : {}
  for (const [key, value] of Object.entries({ alarmId: context.alarmId, alertId: context.alarmId, caseId: context.caseId })) {
    if (value && Object.hasOwn(properties, key)) result[key] = value
  }
  return result
}
function defaultValue(schema: unknown, depth = 0): unknown {
  if (depth > 20 || !schema || typeof schema !== 'object' || Array.isArray(schema)) return undefined
  const field = schema as Record<string, unknown>
  if (Object.hasOwn(field, 'default')) return JSON.parse(JSON.stringify(field.default))
  if ((field.type !== undefined && field.type !== 'object') || !field.properties || typeof field.properties !== 'object') return undefined
  const result: Record<string, unknown> = {}
  for (const [key, nested] of Object.entries(field.properties)) {
    const value = defaultValue(nested, depth + 1)
    if (value !== undefined) result[key] = value
  }
  return result
}
