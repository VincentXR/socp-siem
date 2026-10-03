import { describe, expect, it, vi } from 'vitest'
import { localDateTime, utcInstant, validTimeWindow } from '../time-range'
import { stageDetectionSample, takeDetectionSample } from '../detection-sample'

describe('workflow state boundaries', () => {
  it('round-trips local editor times as UTC instants and rejects reversed ranges', () => {
    const instant = '2026-09-01T08:01:02.123Z'
    expect(utcInstant(localDateTime(instant))).toBe(instant)
    expect(localDateTime('bad')).toBe('')
    expect(utcInstant('')).toBe('')
    expect(validTimeWindow('', instant)).toBe(false)
    expect(validTimeWindow('bad', instant)).toBe(false)
    expect(validTimeWindow(instant, '2026-09-01T00:00:00Z')).toBe(false)
    expect(validTimeWindow(instant, instant)).toBe(true)
  })
  it('hands off one immutable bounded sample once, without accepting a different token', () => {
    const sample = { eventId: 'e1', timestamp: '2026-09-01T00:00:00Z', source: 'auth', host: 'edge', severity: 'HIGH', msg: 'failed', fields: { user: 'alice' } }
    const token = stageDetectionSample(sample)
    sample.fields.user = 'bob'
    expect(takeDetectionSample('wrong')).toBeUndefined()
    expect(takeDetectionSample(token)?.fields.user).toBe('alice')
    expect(takeDetectionSample(token)).toBeUndefined()
    expect(() => stageDetectionSample({ ...sample, msg: '\u754c'.repeat(30_000) })).toThrow('64 KiB')
    const expiring = stageDetectionSample(sample)
    const now = Date.now()
    vi.spyOn(Date, 'now').mockReturnValue(now + 300_001)
    expect(takeDetectionSample(expiring)).toBeUndefined()
    vi.restoreAllMocks()
  })
})
