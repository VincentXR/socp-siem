import { effectScope } from 'vue'
import { useIdentitySession } from '../src/composables/useIdentitySession'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { initialRunInputs } from '../src/components/soar/editor/runInputs'
import { declaredEffects } from '../src/components/soar/editor/executionReview'
import { coalescedRefresh } from '../src/lib/coalesced-refresh'

afterEach(() => vi.useRealTimers())
describe('analyst response execution contract', () => {
  it('invalidates reads and non-cancellable write results through A to B to A navigation and unmount', () => {
    const scope = effectScope()
    const session = scope.run(() => useIdentitySession())!
    const a = session.begin(), signal = session.signal
    const b = session.begin()
    expect(signal.aborted).toBe(true)
    expect(session.isCurrent(a)).toBe(false)
    const returnedA = session.begin()
    expect(session.isCurrent(a)).toBe(false)
    expect(session.isCurrent(b)).toBe(false)
    expect(session.isCurrent(returnedA)).toBe(true)
    scope.stop()
    expect(session.signal.aborted).toBe(true)
    expect(session.isCurrent(returnedA)).toBe(false)
  })
  it('materializes false, zero and nested defaults without inventing targets or mutating schemas', () => {
    const schema = { type: 'object', properties: {
      alarmId: { type: 'string', default: 'old-alarm' }, caseId: { type: 'string' },
      enabled: { type: 'boolean', default: false }, count: { type: 'integer', default: 0 },
      target: { type: 'string' }, options: { type: 'object', properties: { mode: { default: 'audit' } } },
      ids: { type: 'array', default: ['one'] },
    } }
    const inputs = initialRunInputs(schema, { alarmId: 'alarm-2', caseId: 'case-3' })
    expect(inputs).toEqual({ alarmId: 'alarm-2', caseId: 'case-3', enabled: false, count: 0, options: { mode: 'audit' }, ids: ['one'] })
    ;(inputs.ids as string[]).push('two')
    expect(schema.properties.ids.default).toEqual(['one'])
    expect(initialRunInputs(undefined, { alarmId: 'alarm-2' })).toEqual({})
    expect(initialRunInputs({ properties: { target: { type: 'string' } } }, { alarmId: 'alarm-2' })).toEqual({})
  })
  it('keeps declared expressions reviewable without claiming resolved runtime effects', () => {
    expect(declaredEffects({ nodes: [
      { id: 'start', type: 'TRIGGER' },
      { id: 'block', type: 'ACTION', actionRef: 'block_ip', target: { ip: '${inputs.ip}' }, parameters: { ttl: 10 } },
    ] })).toEqual([{ node: 'block', action: 'block_ip', target: { ip: '${inputs.ip}' }, parameters: { ttl: 10 } }])
  })
  it('coalesces a burst and a slow in-flight refresh into one bounded trailing read', async () => {
    vi.useFakeTimers()
    let release!: () => void
    const refresh = vi.fn().mockImplementationOnce(() => new Promise<void>(done => { release = done })).mockResolvedValue(undefined)
    const work = coalescedRefresh(refresh, 5000)
    for (let index = 0; index < 100; index++) work.request()
    await vi.advanceTimersByTimeAsync(0)
    expect(refresh).toHaveBeenCalledTimes(1)
    for (let index = 0; index < 100; index++) work.request()
    await vi.advanceTimersByTimeAsync(7000)
    expect(refresh).toHaveBeenCalledTimes(1)
    release(); await vi.advanceTimersByTimeAsync(0)
    expect(refresh).toHaveBeenCalledTimes(2)
    work.request(); work.dispose(); await vi.advanceTimersByTimeAsync(5000)
    expect(refresh).toHaveBeenCalledTimes(2)
  })
})
