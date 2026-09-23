import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import ElButton from 'element-plus/es/components/button/index.mjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SituationView from '../src/views/SituationView.vue'
import { translate } from '../src/i18n'
import { ApiError } from '../src/api'

const api = vi.hoisted(() => ({ alarmStats: vi.fn(), gasEngineStats: vi.fn(), gasRecentAlerts: vi.fn(), ingestSummary: vi.fn(), currentSession: vi.fn() }))
vi.mock('../src/api', async original => ({ ...await original<typeof import('../src/api')>(), ...api }))
vi.mock('../src/lib/echarts', () => ({ loadEcharts: async () => ({
  init: () => ({ isDisposed: () => false, setOption: () => {}, resize: () => {}, dispose: () => {} }),
  graphic: { LinearGradient: class {} },
}) }))

const stats = { total: 12, bySeverity: {}, trend7d: {}, topRules: [], byRiskLevel: { HIGH: 3 }, avgRisk: 68, topRisk: [] }
const engine = { rules: 2, eventCount: 91, alertCount: 7, dropCount: 2, suppressedCount: 4, queueLoad: 0.3 }
const ingest = { collectors: 1, accepted: 5, skipped: 0, forwarded: 5, bytes: 400, eps1m: 25, byHealth: {}, sources: 1, enabledSources: 1 }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
let client: QueryClient
let wrapper: ReturnType<typeof mount<typeof SituationView>> | undefined
let stream: { onerror?: () => void } | undefined

function setup() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  wrapper = mount(SituationView, { props: { theme: 'light' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  return wrapper
}
function refresh() {
  wrapper!.findAllComponents(ElButton).find(button => button.text() === translate('common.refresh'))!.vm.$emit('click', new MouseEvent('click'))
  return flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('EventSource', class {
    onerror?: () => void
    constructor() { stream = this }
    addEventListener() {}
    close() {}
  })
  api.alarmStats.mockResolvedValue(stats)
  api.gasEngineStats.mockResolvedValue(engine)
  api.gasRecentAlerts.mockResolvedValue([])
  api.ingestSummary.mockResolvedValue(ingest)
  api.currentSession.mockResolvedValue({ username: 'analyst', role: 'analyst', tenant: 'default' })
})
afterEach(() => {
  wrapper?.unmount(); wrapper = undefined
  client?.clear()
  stream = undefined
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('situation refresh ownership', () => {
  it('retains a failed source and identifies its values as stale', async () => {
    const view = setup(); await flushPromises()
    expect(view.findAll('.k-num').map(node => node.text())).toEqual(['91', '7', '4', '2', '25', '30%'])
    api.gasEngineStats.mockRejectedValueOnce(new Error('engine unavailable'))
    api.ingestSummary.mockRejectedValueOnce(new DOMException('ingest timeout', 'TimeoutError'))
    api.alarmStats.mockResolvedValueOnce({ ...stats, total: 13 })
    await refresh()
    expect(view.findAll('.k-num').map(node => node.text())).toEqual(['91', '7', '4', '2', '25', '30%'])
    expect(view.text()).toContain('13')
    expect(view.text()).toContain(translate('situation.staleData'))
    expect(view.text()).toContain('engine unavailable')
    expect(view.text()).toContain('ingest timeout')
    expect((view.vm as unknown as { epsHistory: number[] }).epsHistory).toEqual([25])
    await refresh()
    expect(view.text()).not.toContain(translate('situation.staleData'))
    expect((view.vm as unknown as { epsHistory: number[] }).epsHistory).toEqual([25, 25])
  })

  it('shows unavailable values before any successful source response', async () => {
    for (const request of Object.values(api)) request.mockRejectedValueOnce(new Error('offline'))
    const view = setup(); await flushPromises()
    expect(view.findAll('.k-num').map(node => node.text())).toEqual(Array(6).fill(translate('time.notAvailable')))
    expect(view.text()).toContain(translate('situation.dataUnavailable'))
    expect(view.find('.sit-kpis .el-progress').exists()).toBe(false)
  })

  it('continues metric polling while the live stream is paused', async () => {
    vi.useFakeTimers()
    const view = setup(); await flushPromises()
    view.findAllComponents(ElButton).find(button => button.text() === translate('situation.pause'))!.vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    const calls = api.alarmStats.mock.calls.length
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(api.alarmStats.mock.calls.length).toBeGreaterThan(calls)
  })

  it('does not publish a late refresh after the view is left', async () => {
    const view = setup(); await flushPromises()
    const pending = deferred<typeof engine>()
    api.gasEngineStats.mockReturnValueOnce(pending.promise)
    view.findAllComponents(ElButton).find(button => button.text() === translate('common.refresh'))!.vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    const signal = api.gasEngineStats.mock.calls.at(-1)![0].signal as AbortSignal
    view.unmount(); wrapper = undefined
    expect(signal.aborted).toBe(true)
    pending.resolve({ ...engine, eventCount: 999 }); await flushPromises()
    expect((client.getQueryData<{ engine: typeof engine }>(['situation', 'snapshot']))?.engine.eventCount).toBe(91)
  })

  it('reconnects after a temporary session-check failure and logs out only on 401', async () => {
    const view = setup(); await flushPromises()
    api.currentSession.mockRejectedValueOnce(new Error('network unavailable'))
    stream!.onerror!(); await flushPromises()
    expect(view.emitted('session-expired')).toBeUndefined()
    expect(view.text()).toContain(translate('situation.streamReconnecting'))
    view.findAllComponents(ElButton).find(button => button.text() === translate('situation.pause'))!.vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    view.findAllComponents(ElButton).find(button => button.text() === translate('situation.resume'))!.vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    api.currentSession.mockRejectedValueOnce(new ApiError(401, 'unauthorized'))
    stream!.onerror!(); await flushPromises()
    expect(view.emitted('session-expired')).toHaveLength(1)
  })
})
