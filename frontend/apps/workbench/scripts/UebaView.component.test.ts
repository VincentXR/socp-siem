import { flushPromises, shallowMount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UebaView from '../src/views/UebaView.vue'
import UebaRiskPanel from '../src/components/ueba/UebaRiskPanel.vue'
import UebaEntityDrawer from '../src/components/ueba/UebaEntityDrawer.vue'
import UebaScorePanel from '../src/components/ueba/UebaScorePanel.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
import type { RiskEntity, ScoreBreakdown } from '../src/api'

const mocks = vi.hoisted(() => ({ uebaEntities: vi.fn(), uebaSummary: vi.fn(), listWatchlists: vi.fn(),
  listTechniques: vi.fn(), uebaEntity: vi.fn(), uebaScore: vi.fn() }))
vi.mock('../src/api', async original => ({ ...await original<typeof import('../src/api')>(), ...mocks }))
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const entity = (name: string): RiskEntity => ({ entity: name, risk: 30, level: 'MEDIUM', alerts: 1,
  critical: false, maxSeverity: 'MEDIUM', firstSeen: null, lastSeen: null, mitre: [], topRules: [] })
const score = (value: number): ScoreBreakdown => ({ score: value, level: 'HIGH', breakdown: { base: value } })
async function setup() {
  const wrapper = shallowMount(UebaView, { props: { theme: 'light' }, global: {
    renderStubDefaultSlot: true, provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('analyst') } },
  } })
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  mocks.uebaEntities.mockResolvedValue([entity('a'), entity('b')])
  mocks.uebaSummary.mockResolvedValue({})
  mocks.listWatchlists.mockResolvedValue([])
  mocks.listTechniques.mockResolvedValue({ items: [] })
  mocks.uebaScore.mockResolvedValue(score(10))
})
describe('UEBA request ownership', () => {
  it('does not start a score request when initial catalogue reads finish after leaving', async () => {
    const lists = deferred<never[]>()
    mocks.listWatchlists.mockReturnValueOnce(lists.promise)
    const wrapper = await setup()
    expect(mocks.uebaScore).not.toHaveBeenCalled()
    wrapper.unmount()
    lists.resolve([]); await flushPromises()
    expect(mocks.uebaScore).not.toHaveBeenCalled()
  })
  it('keeps B selected when A completes late, and closing cancels hidden detail failures', async () => {
    const a = deferred<RiskEntity>(), b = deferred<RiskEntity>(), closed = deferred<RiskEntity>()
    mocks.uebaEntity.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise).mockReturnValueOnce(closed.promise)
    const wrapper = await setup(), risk = wrapper.findComponent(UebaRiskPanel), drawer = wrapper.findComponent(UebaEntityDrawer)
    risk.vm.$emit('select', entity('a')); await nextTick()
    const signalA = mocks.uebaEntity.mock.calls.at(-1)![1].signal as AbortSignal
    risk.vm.$emit('select', entity('b')); await nextTick()
    expect(signalA.aborted).toBe(true)
    b.resolve({ ...entity('b'), risk: 90 }); await flushPromises()
    a.resolve({ ...entity('a'), risk: 99 }); await flushPromises()
    expect(drawer.props('entity')).toMatchObject({ entity: 'b', risk: 90 })
    drawer.vm.$emit('go-alarms'); await nextTick()
    expect(wrapper.emitted('go-alarms')).toEqual([['b']])
    risk.vm.$emit('select', entity('a')); await nextTick()
    drawer.vm.$emit('update:modelValue', false); await nextTick()
    expect(mocks.uebaEntity.mock.calls.at(-1)![1].signal.aborted).toBe(true)
    closed.reject(new Error('hidden failure')); await flushPromises()
    expect(drawer.props('error')).toBeUndefined()
    wrapper.unmount()
  })
  it('invalidates scores on edits and exposes retryable local failures', async () => {
    const wrapper = await setup(), panel = wrapper.findComponent(UebaScorePanel)
    expect(panel.props('result')).toMatchObject({ score: 10 })
    const old = deferred<ScoreBreakdown>(), current = deferred<ScoreBreakdown>()
    mocks.uebaScore.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    panel.props('form').tiHits = 2; panel.vm.$emit('calculate'); await nextTick()
    const oldSignal = mocks.uebaScore.mock.calls.at(-1)![1].signal as AbortSignal
    panel.props('form').tiHits = 3; await nextTick()
    expect(oldSignal.aborted).toBe(true)
    expect(panel.props('result')).toBeNull()
    old.resolve(score(88)); await flushPromises()
    expect(panel.props('result')).toBeNull()
    panel.vm.$emit('calculate'); await nextTick()
    current.reject(new Error('scoring unavailable')); await flushPromises()
    expect(panel.props('error')).toBe('scoring unavailable')
    expect(panel.props('result')).toBeNull()
    mocks.uebaScore.mockResolvedValueOnce(score(42))
    panel.vm.$emit('calculate'); await flushPromises()
    expect(panel.props('result')).toMatchObject({ score: 42 })
    expect(panel.props('error')).toBeUndefined()
    const pending = deferred<ScoreBreakdown>()
    mocks.uebaScore.mockReturnValueOnce(pending.promise)
    panel.vm.$emit('calculate'); await nextTick()
    const signal = mocks.uebaScore.mock.calls.at(-1)![1].signal as AbortSignal
    wrapper.unmount(); expect(signal.aborted).toBe(true)
    pending.resolve(score(75)); await flushPromises()
  })
})
