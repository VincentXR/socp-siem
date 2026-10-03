import { describe, expect, it } from 'vitest'
import { healthState } from './health-state'

const targets = [{ name: 'a' }, { name: 'b' }]
describe('health observation truthfulness', () => {
  it('never describes absent or partial coverage as healthy', () => {
    expect(healthState({}, targets)).toBe('unknown')
    expect(healthState({}, targets, { fetching: true })).toBe('loading')
    expect(healthState({ a: 'up' }, targets)).toBe('unknown')
    expect(healthState({ a: 'up', b: 'starting' }, targets)).toBe('unknown')
    expect(healthState({ a: 'down' }, targets)).toBe('degraded')
    expect(healthState({ a: 'up', b: 'up' }, targets)).toBe('healthy')
  })
  it('preserves observations but flags failures and expired timestamps', () => {
    const up = { a: 'up', b: 'up' }
    expect(healthState(up, targets, { failed: true })).toBe('stale')
    expect(healthState(up, targets, { updatedAt: 1000, now: 92000 })).toBe('stale')
    expect(healthState(up, targets, { updatedAt: 1000, now: 91000 })).toBe('healthy')
    expect(healthState({}, targets, { failed: true })).toBe('unknown')
  })
})
