import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElTable } from 'element-plus/es/components/table/index.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ReportView from '../src/views/ReportView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
import { translate } from '../src/i18n'

const mocks = vi.hoisted(() => ({ dailyReport: vi.fn(), trend7d: vi.fn(), listArchive: vi.fn(), archiveReport: vi.fn(),
  downloadArchivedReport: vi.fn(), loadEcharts: vi.fn(), init: vi.fn(), success: vi.fn(), error: vi.fn() }))
vi.mock('../src/api', async original => ({ ...await original<typeof import('../src/api')>(), ...mocks }))
vi.mock('../src/lib/echarts', () => ({ loadEcharts: mocks.loadEcharts }))
vi.mock('element-plus/es/components/message/index.mjs', () => ({ default: { success: mocks.success, error: mocks.error } }))
const summary = { date: '20260923', total: 42, bySeverity: { INFO: 42 }, byRule: [], source: 'clickhouse', degraded: false }
const trend = { days: ['20260923'], counts: [42], source: 'alert-web', degraded: true, degradationReason: 'fallback source' }
const listing = (prefix = 'reports/tenant/', key = 'reports/tenant/20260923/daily.json') =>
  ({ prefix, count: 1, limit: 500, truncated: false, objects: [{ key, size: 100 }] })
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const setup = () => mount(ReportView, { props: { theme: 'light' }, global: {
  provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('analyst') } },
} })
beforeEach(() => {
  vi.restoreAllMocks(); vi.resetAllMocks()
  mocks.dailyReport.mockResolvedValue(summary)
  mocks.trend7d.mockResolvedValue(trend)
  mocks.listArchive.mockResolvedValue(listing())
  mocks.loadEcharts.mockResolvedValue({ init: mocks.init })
  mocks.init.mockImplementation(() => ({ setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() }))
})

describe('report source and lifecycle ownership', () => {
  it('keeps a successful daily report when trend fails and retries only the failed source', async () => {
    mocks.trend7d.mockRejectedValueOnce(new Error('trend unavailable'))
    const wrapper = setup(); await flushPromises()
    expect(wrapper.find('.metrics-row').text()).toContain('42')
    expect(wrapper.find('.report-charts [role="alert"]').text()).toContain('trend unavailable')
    const bar = mocks.init.mock.results[0]!.value
    expect(bar.setOption.mock.calls[0][0].series[0].data).toEqual([0, 0, 0, 0, 42])
    wrapper.find('.report-charts [role="alert"]').findComponent(ElButton).vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    expect(mocks.dailyReport).toHaveBeenCalledTimes(1)
    expect(mocks.trend7d).toHaveBeenCalledTimes(2)
    expect(wrapper.findAllComponents(ElAlert).some(alert => alert.props('description') === 'fallback source')).toBe(true)
    wrapper.unmount()
    expect(bar.dispose).toHaveBeenCalledOnce()
  })

  it('keeps a successful trend even while daily data is still pending, then aborts on leaving', async () => {
    const daily = deferred<typeof summary>()
    mocks.dailyReport.mockReturnValueOnce(daily.promise)
    const wrapper = setup(); await flushPromises()
    expect(mocks.init).toHaveBeenCalledTimes(1)
    expect(mocks.init.mock.results[0]!.value.setOption.mock.calls[0][0].series[0].data).toEqual([42])
    const signal = mocks.dailyReport.mock.calls[0]![0].signal as AbortSignal
    wrapper.unmount(); expect(signal.aborted).toBe(true)
    const loads = mocks.loadEcharts.mock.calls.length
    daily.resolve(summary); await flushPromises()
    expect(mocks.loadEcharts).toHaveBeenCalledTimes(loads)
  })

  it('discards an old archive response after the date filter changes', async () => {
    mocks.listArchive.mockResolvedValueOnce({ ...listing(), truncated: true })
    const wrapper = setup(); await flushPromises()
    expect(wrapper.findAllComponents(ElAlert).some(alert => String(alert.props('title')).includes('500'))).toBe(true)
    expect(wrapper.find('.report-file-date').text()).toBe('2026-09-23')
    const old = deferred<ReturnType<typeof listing>>()
    mocks.listArchive.mockReturnValueOnce(old.promise).mockResolvedValueOnce(listing('reports/tenant/20260922/', 'new-file'))
    const picker = wrapper.findComponent(ElInput)
    picker.vm.$emit('update:modelValue', '2026-09-21'); await flushPromises()
    const signal = mocks.listArchive.mock.calls.at(-1)![1].signal as AbortSignal
    picker.vm.$emit('update:modelValue', '2026-09-22'); await flushPromises()
    expect(signal.aborted).toBe(true)
    old.resolve(listing('reports/tenant/20260921/', 'old-file')); await flushPromises()
    expect(wrapper.find('.report-storage-card').findComponent(ElTable).props('data')).toEqual([{ key: 'new-file', size: 100 }])
    expect(mocks.listArchive.mock.calls.at(-1)![0]).toBe('reports/tenant/20260922/')
    wrapper.unmount()
  })

  it('does not refresh or toast a durable archive operation after leaving', async () => {
    const pending = deferred<{ archived: boolean }>()
    mocks.archiveReport.mockReturnValueOnce(pending.promise)
    const wrapper = setup(); await flushPromises()
    wrapper.findAllComponents(ElButton).find(button => button.props('type') === 'primary')!.vm.$emit('click', new MouseEvent('click'))
    await flushPromises(); wrapper.unmount()
    pending.resolve({ archived: true }); await flushPromises()
    expect(mocks.listArchive).toHaveBeenCalledTimes(1)
    expect(mocks.success).not.toHaveBeenCalled()
  })

  it.each(['https://files.example/report.json', 'javascript:alert(1)'])('opens a detached tab and validates the download URL %s', async url => {
    const pending = deferred<{ key: string; url: string }>()
    mocks.downloadArchivedReport.mockReturnValueOnce(pending.promise)
    const popup = { opener: {}, close: vi.fn(), location: { replace: vi.fn() } }
    const open = vi.spyOn(window, 'open').mockReturnValue(popup as unknown as Window)
    const wrapper = setup(); await flushPromises()
    wrapper.findAllComponents(ElButton).find(button => button.text() === translate('report.download'))!.vm.$emit('click', new MouseEvent('click'))
    expect(open).toHaveBeenCalledWith('about:blank', '_blank')
    expect(popup.opener).toBeNull()
    pending.resolve({ key: listing().objects[0]!.key, url }); await flushPromises()
    if (url.startsWith('https:')) {
      expect(popup.close).not.toHaveBeenCalled()
      expect(popup.location.replace).toHaveBeenCalledWith(url)
    } else {
      expect(popup.close).toHaveBeenCalledOnce()
      expect(popup.location.replace).not.toHaveBeenCalled()
      expect(mocks.error).toHaveBeenCalledOnce()
    }
    wrapper.unmount()
  })

  it.each([false, true])('selects the generated report date, including after initial listing failure: %s', async initiallyFailed => {
    if (initiallyFailed) mocks.listArchive.mockRejectedValueOnce(new Error('listing unavailable'))
    mocks.archiveReport.mockResolvedValue({ archived: true, day: '20260923' })
    const wrapper = setup(); await flushPromises()
    wrapper.findAllComponents(ElButton).find(button => button.props('type') === 'primary')!.vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('2026-09-23')
    expect(mocks.listArchive.mock.calls.at(-1)![0]).toBe('reports/tenant/20260923/')
    wrapper.unmount()
  })


  it.each([false, true])('downloads the generated key independently of a capped or failed listing: %s', async failed => {
    const key = 'reports/tenant/20260923/snapshot-created.json'
    mocks.archiveReport.mockResolvedValueOnce({ archived: true, day: '20260923', archiveKey: key, schemaVersion: 1 })
    const wrapper = setup(); await flushPromises()
    if (failed) mocks.listArchive.mockRejectedValue(new Error('listing unavailable'))
    else mocks.listArchive.mockResolvedValue({ ...listing(), truncated: true })
    wrapper.findAllComponents(ElButton).find(button => button.props('type') === 'primary')!.vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    expect(wrapper.find('.report-generated').text()).toContain('2026-09-23')
    const popup = { opener: {}, close: vi.fn(), location: { replace: vi.fn() } }
    vi.spyOn(window, 'open').mockReturnValue(popup as unknown as Window)
    mocks.downloadArchivedReport.mockResolvedValue({ key, url: 'https://files.example/snapshot.json' })
    wrapper.find('.report-generated').findComponent(ElButton).vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    expect(mocks.downloadArchivedReport).toHaveBeenCalledWith(key, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(popup.location.replace).toHaveBeenCalledWith('https://files.example/snapshot.json')
    expect(popup.opener).toBeNull()
    mocks.archiveReport.mockRejectedValueOnce(new Error('generation unavailable'))
    wrapper.findAllComponents(ElButton).find(button => button.props('type') === 'primary')!.vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    expect(wrapper.find('.report-generated').exists()).toBe(true)
    wrapper.unmount()
  })

  it('does not initialize charts when their module finishes loading after unmount', async () => {
    const module = deferred<{ init: typeof mocks.init }>()
    mocks.loadEcharts.mockReturnValue(module.promise)
    const wrapper = setup(); await flushPromises()
    expect(mocks.loadEcharts).toHaveBeenCalled()
    wrapper.unmount()
    module.resolve({ init: mocks.init }); await flushPromises()
    expect(mocks.init).not.toHaveBeenCalled()
  })

})
