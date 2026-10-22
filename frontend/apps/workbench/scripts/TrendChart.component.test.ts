import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TrendChart from '../src/components/TrendChart.vue'
import { translate } from '../src/i18n'

const mocks = vi.hoisted(() => ({
  loadEcharts: vi.fn(), init: vi.fn(), setOption: vi.fn(), clear: vi.fn(), resize: vi.fn(), dispose: vi.fn(),
}))
vi.mock('../src/lib/echarts', () => ({ loadEcharts: mocks.loadEcharts }))

const runtime = {
  init: mocks.init,
  graphic: { LinearGradient: class {} },
}
let wrapper: ReturnType<typeof mount<typeof TrendChart>> | undefined
let resizeCallback: () => void
const disconnect = vi.fn()
const preference = { matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }

beforeEach(() => {
  vi.clearAllMocks()
  document.documentElement.setAttribute('data-theme', 'light')
  mocks.init.mockReturnValue({
    isDisposed: () => false, setOption: mocks.setOption, clear: mocks.clear, resize: mocks.resize, dispose: mocks.dispose,
  })
  mocks.loadEcharts.mockResolvedValue(runtime)
  preference.matches = false
  vi.stubGlobal('matchMedia', vi.fn(() => preference))
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: () => void) { resizeCallback = callback }
    observe() {}
    disconnect = disconnect
  })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.unstubAllGlobals()
  document.documentElement.removeAttribute('data-theme')
})

describe('trend chart console rendering', () => {
  it('plots only supplied daily counts with readable dark-console axes and an exact-date tooltip', async () => {
    wrapper = mount(TrendChart, { props: { variant: 'console', data: { '2026-10-22': 12, '2026-10-20': 0 } } })
    await flushPromises()
    const option = mocks.setOption.mock.calls.at(-1)![0]
    expect(option.xAxis.data).toEqual(['2026-10-20', '2026-10-22'])
    expect(option.series[0].data).toEqual([0, 12])
    expect(option.series[0].markPoint).toBeUndefined()
    expect(option.series[0].lineStyle.width).toBeGreaterThan(0)
    expect(option.xAxis.axisLabel).toMatchObject({ fontSize: 12, color: '#9cb0ce' })
    expect(option.yAxis.axisLabel.fontSize).toBe(12)
    expect(option.tooltip.formatter([{ axisValue: '2026-10-22', value: 12 }])).toContain('2026-10-22\n12')
    expect(option.animation).toBe(false)
    expect(wrapper.attributes('data-state')).toBe('ready')
    expect(wrapper.attributes('aria-label')).toContain('2026-10-20: 0')
  })

  it('clears the previous graph when data becomes unavailable or empty rather than retaining old counts', async () => {
    wrapper = mount(TrendChart, { props: { variant: 'console', data: { '2026-10-22': 8 } } })
    await flushPromises()
    await wrapper.setProps({ data: undefined })
    expect(mocks.clear).toHaveBeenCalledOnce()
    expect(wrapper.attributes('data-state')).toBe('empty')
    expect(wrapper.attributes('aria-label')).not.toContain('2026-10-22')
    await wrapper.setProps({ data: {} })
    expect(wrapper.attributes('data-state')).toBe('empty')
    expect(mocks.setOption).toHaveBeenCalledOnce()
  })

  it('does not resurrect an old data snapshot after a delayed chart module loads', async () => {
    let resolve!: (value: typeof runtime) => void
    mocks.loadEcharts.mockReturnValueOnce(new Promise<typeof runtime>(done => { resolve = done }))
    wrapper = mount(TrendChart, { props: { variant: 'console', data: { '2026-10-22': 8 } } })
    await wrapper.vm.$nextTick()
    expect(wrapper.attributes('data-state')).toBe('loading')
    await wrapper.setProps({ data: {} })
    resolve(runtime)
    await flushPromises()
    expect(mocks.init).not.toHaveBeenCalled()
    expect(wrapper.attributes('data-state')).toBe('empty')
  })

  it('shows a readable unavailable state on module failure and allows retrying only the chart', async () => {
    mocks.loadEcharts.mockRejectedValueOnce(new Error('chart module unavailable'))
    wrapper = mount(TrendChart, { props: { variant: 'console', data: { '2026-10-22': 8 } } })
    await flushPromises()
    expect(wrapper.attributes('data-state')).toBe('error')
    expect(wrapper.get('[role="status"]').text()).toBe(translate('experience.unavailable'))
    expect(mocks.init).not.toHaveBeenCalled()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.attributes('data-state')).toBe('ready')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(mocks.setOption.mock.calls.at(-1)![0].series[0].data).toEqual([8])
  })

  it('resizes with its container and reacts to reduced motion and variant changes without leaking observers', async () => {
    wrapper = mount(TrendChart, { props: { variant: 'situation', data: { '2026-10-22': 8 } } })
    await flushPromises()
    resizeCallback()
    expect(mocks.resize).toHaveBeenCalledOnce()
    expect(mocks.setOption.mock.calls.at(-1)![0].animation).toBe(true)
    preference.matches = true
    preference.addEventListener.mock.calls[0][1]()
    await flushPromises()
    expect(mocks.setOption.mock.calls.at(-1)![0].animation).toBe(false)
    await wrapper.setProps({ variant: 'console' })
    await flushPromises()
    expect(wrapper.classes()).toContain('trend-chart--console')
    expect(mocks.setOption.mock.calls.at(-1)![0].xAxis.axisLabel.color).toBe('#9cb0ce')
    wrapper.unmount()
    wrapper = undefined
    expect(disconnect).toHaveBeenCalledOnce()
    expect(mocks.dispose).toHaveBeenCalledOnce()
    expect(preference.removeEventListener).toHaveBeenCalledWith('change', preference.addEventListener.mock.calls[0][1])
  })
})
