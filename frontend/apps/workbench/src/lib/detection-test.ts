import type { DetectionIngestEvent, RuleCondition, RuleSpec } from '../api'

export interface DetectionConditionTrace {
  condition: RuleCondition
  matched: boolean
  observed: string
  scope?: string
}

export interface DetectionRuleTrace {
  conditions: DetectionConditionTrace[]
  hasConditionMatch: boolean
  whitelistMatched: boolean
}

function readPath(value: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((current, part) => {
    if (!current || typeof current !== 'object') return undefined
    return (current as Record<string, unknown>)[part]
  }, value)
}

export function eventFieldValue(event: DetectionIngestEvent, field: string): unknown {
  const key = field.trim()
  if (!key) return undefined
  const eventRecord = event as unknown as Record<string, unknown>
  if (key in eventRecord) return eventRecord[key]
  if (key.startsWith('fields.')) return readPath(event.fields, key.slice('fields.'.length))
  const nested = readPath(event.fields, key)
  return nested === undefined ? readPath(eventRecord, key) : nested
}

function comparable(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function listValues(value: string): string[] {
  return value.split(/[,\n]/).map(item => item.trim()).filter(Boolean)
}

export function conditionMatches(condition: RuleCondition, event: DetectionIngestEvent): boolean {
  const actual = eventFieldValue(event, condition.field)
  const observed = comparable(actual)
  const expected = String(condition.value ?? '')
  switch (String(condition.op ?? '').toLowerCase()) {
    case 'eq': return observed === expected
    case 'ne': return observed !== expected
    case 'contains': return observed.includes(expected)
    case 'startswith': return observed.startsWith(expected)
    case 'endswith': return observed.endsWith(expected)
    case 'regex':
      try { return new RegExp(expected).test(observed) } catch { return false }
    case 'gt': return Number(observed) > Number(expected)
    case 'gte': return Number(observed) >= Number(expected)
    case 'lt': return Number(observed) < Number(expected)
    case 'lte': return Number(observed) <= Number(expected)
    case 'inlist': return listValues(expected).includes(observed)
    case 'notinlist': return !listValues(expected).includes(observed)
    default: return observed === expected
  }
}

function conditionEntries(rule: Partial<RuleSpec>): Array<{ condition: RuleCondition; scope: string }> {
  const entries: Array<{ condition: RuleCondition; scope: string }> = []
  for (const condition of rule.match ?? []) entries.push({ condition, scope: 'match' })
  for (const [index, group] of (rule.matchAny ?? []).entries()) {
    for (const condition of group) entries.push({ condition, scope: `matchAny ${index + 1}` })
  }
  for (const [index, group] of (rule.steps ?? []).entries()) {
    for (const condition of group) entries.push({ condition, scope: `step ${index + 1}` })
  }
  for (const condition of rule.whitelist ?? []) entries.push({ condition, scope: 'whitelist' })
  return entries
}

function observedValue(events: readonly DetectionIngestEvent[], condition: RuleCondition, matched: boolean): string {
  const event = matched
    ? events.find(candidate => conditionMatches(condition, candidate))
    : events[0]
  return comparable(event ? eventFieldValue(event, condition.field) : undefined) || '—'
}

export function traceRuleConditions(rule: Partial<RuleSpec>, events: readonly DetectionIngestEvent[]): DetectionRuleTrace {
  const entries = conditionEntries(rule)
  const conditions = entries.map(({ condition, scope }) => {
    const matched = events.some(event => conditionMatches(condition, event))
    return { condition, matched, observed: observedValue(events, condition, matched), scope }
  })
  const whitelistMatched = conditions.some(item => item.scope === 'whitelist' && item.matched)
  const executable = conditions.filter(item => item.scope !== 'whitelist')
  return {
    conditions,
    hasConditionMatch: executable.some(item => item.matched),
    whitelistMatched,
  }
}
