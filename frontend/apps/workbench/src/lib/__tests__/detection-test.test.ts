import { describe, expect, it } from 'vitest'
import type { DetectionIngestEvent, RuleCondition } from '../../api'
import { conditionMatches, eventFieldValue, traceRuleConditions } from '../detection-test'

const event: DetectionIngestEvent = {
  source: 'auth',
  host: 'workbench-01',
  severity: 'HIGH',
  msg: 'Failed password for admin',
  fields: { user: 'admin', attempts: '6' },
}

describe('detection test evidence', () => {
  it('reads standard and nested event fields', () => {
    expect(eventFieldValue(event, 'src_ip')).toBeUndefined()
    expect(eventFieldValue(event, 'fields.user')).toBe('admin')
    expect(eventFieldValue(event, 'user')).toBe('admin')
    expect(eventFieldValue(event, 'severity')).toBe('HIGH')
  })

  it('evaluates the supported visual operators against a sample event', () => {
    const condition = (op: string, value: string): RuleCondition => ({ field: 'fields.attempts', op, value })
    expect(conditionMatches(condition('gte', '5'), event)).toBe(true)
    expect(conditionMatches(condition('regex', '^6$'), event)).toBe(true)
    expect(conditionMatches(condition('inlist', '4, 6'), event)).toBe(true)
    expect(conditionMatches(condition('contains', '7'), event)).toBe(false)
  })

  it('returns observed values and marks whitelist hits separately', () => {
    const rule = {
      match: [{ field: 'severity', op: 'eq', value: 'HIGH' }],
      whitelist: [{ field: 'fields.user', op: 'eq', value: 'admin' }],
    }
    const trace = traceRuleConditions(rule, [event])
    expect(trace.hasConditionMatch).toBe(true)
    expect(trace.whitelistMatched).toBe(true)
    expect(trace.conditions.map(item => item.observed)).toEqual(['HIGH', 'admin'])
  })
})
