import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import IngestSourceSetup from '../src/components/IngestSourceSetup.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const api = vi.hoisted(() => ({ getSourceSetup: vi.fn(), previewSource: vi.fn(), renderSourceConfig: vi.fn(), splSearch: vi.fn() }))
vi.mock('../src/api', async original => ({ ...await original<object>(), ...api }))
const source = (id = 'source-a') => ({ source: { id, name: id, type: 'FILE', path: '/var/log/app.log', enabled: true }, collectorTag: `search-${id}`, nativeVector: true, appliedState: 'UNKNOWN', configurationVersion: 'saved-fingerprint', pipeline: [], problems: [], output: null })
const deferred = <T,>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }, { path: '/search', name: 'search', component: { template: '<div />' } }, { path: '/ingest', name: 'ingest', component: { template: '<div />' } }, { path: '/parsers/new', name: 'parser-new', component: { template: '<div />' } }] })
  await router.push('/')
  const role = ref('admin')
  const wrapper = mount(IngestSourceSetup, { props: { sourceId: 'source-a' }, global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: role } } } })
  await flushPromises()
  return { wrapper, router, role }
}
function button(wrapper: Awaited<ReturnType<typeof setup>>['wrapper'], label: string) { return wrapper.findAll('button').find(item => item.text() === label)! }
beforeEach(() => { vi.resetAllMocks(); api.getSourceSetup.mockImplementation((id: string) => Promise.resolve(source(id))) })
describe('source-first guided ingestion', () => {
  it('aborts and discards a rendered configuration after write access is revoked', async () => {
    const pending = deferred<string>()
    api.renderSourceConfig.mockReturnValueOnce(pending.promise)
    const { wrapper, role } = await setup()
    await button(wrapper, '渲染此源配置').trigger('click')
    const signal = api.renderSourceConfig.mock.calls[0]![1].signal as AbortSignal
    role.value = 'viewer'; await flushPromises()
    expect(signal.aborted).toBe(true)
    pending.resolve('obsolete privileged configuration'); await flushPromises()
    expect(wrapper.text()).not.toContain('obsolete privileged configuration')
    expect(wrapper.text()).not.toContain('saved-fingerprint')
    role.value = 'admin'; await flushPromises()
    expect(api.getSourceSetup).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('keeps saved, desired and applied states separate and starts with an empty sample', async () => {
    const { wrapper } = await setup()
    expect(wrapper.text()).toContain('配置已保存')
    expect(wrapper.text()).toContain('采集器应用状态：未知')
    expect(wrapper.find('textarea').element.value).toBe('')
    expect(button(wrapper, '预览实际解析流程').attributes('disabled')).toBeDefined()
    expect(api.previewSource).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('discards an older source setup response after switching sources', async () => {
    const old = deferred<ReturnType<typeof source>>()
    api.getSourceSetup.mockReturnValueOnce(old.promise)
    const { wrapper } = await setup()
    await wrapper.setProps({ sourceId: 'source-b' }); await flushPromises()
    old.resolve(source()); await flushPromises()
    expect(wrapper.text()).toContain('source-b')
    expect(wrapper.text()).not.toContain('source-a')
    wrapper.unmount()
  })
  it('cancels a preview when the sample changes and never displays the old result', async () => {
    const pending = deferred<object>()
    api.previewSource.mockReturnValueOnce(pending.promise)
    const { wrapper } = await setup()
    await wrapper.find('textarea').setValue('old sample')
    await button(wrapper, '预览实际解析流程').trigger('click')
    const signal = api.previewSource.mock.calls[0]![2].signal as AbortSignal
    await wrapper.find('textarea').setValue('new sample')
    expect(signal.aborted).toBe(true)
    pending.resolve({ ok: true, fields: { message: 'obsolete result' } }); await flushPromises()
    expect(wrapper.text()).not.toContain('obsolete result')
    wrapper.unmount()
  })
  it('does not treat degraded cached search as a verified first event', async () => {
    api.splSearch.mockResolvedValue({ source: 'local-cache', degraded: true, events: [{ eventId: 'cached', fields: { source_id: 'source-a' } }] })
    const { wrapper } = await setup()
    await button(wrapper, '检查已索引事件').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('首事件验证仍不确定')
    expect(wrapper.text()).not.toContain('已观察到此日志源的真实索引事件')
    expect(api.previewSource).not.toHaveBeenCalled()
    expect(api.splSearch).toHaveBeenCalledWith(expect.stringContaining('source_id="source-a"'), expect.objectContaining({ limit: 20 }))
    wrapper.unmount()
  })
  it('requires exact source identity in an authoritative event and links that event', async () => {
    api.splSearch.mockResolvedValue({ source: 'opensearch', degraded: false, events: [{ eventId: 'wrong', fields: { source_id: 'other' } }, { eventId: 'real-event', timestamp: '2026-10-01T01:00:00Z', msg: 'actual collector event', fields: { source_id: 'source-a' } }] })
    const { wrapper, router } = await setup()
    await button(wrapper, '检查已索引事件').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('actual collector event')
    expect(wrapper.text()).not.toContain('wrong')
    await button(wrapper, '打开事件').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query.q).toBe('eventId="real-event"')
    wrapper.unmount()
  })
  it('clears prior event evidence when a new check fails', async () => {
    api.splSearch.mockResolvedValueOnce({ source: 'opensearch', degraded: false, events: [{ eventId: 'real', fields: { source_id: 'source-a' }, msg: 'old evidence' }] }).mockRejectedValueOnce(new Error('index unavailable'))
    const { wrapper } = await setup()
    await button(wrapper, '检查已索引事件').trigger('click'); await flushPromises()
    await button(wrapper, '检查已索引事件').trigger('click'); await flushPromises()
    expect(wrapper.text()).not.toContain('old evidence')
    expect(wrapper.text()).toContain('index unavailable')
    wrapper.unmount()
  })
  it('blocks native render for external managed sources while explaining the limitation', async () => {
    api.getSourceSetup.mockResolvedValue({ ...source(), nativeVector: false, source: { ...source().source, type: 'CLOUD' } })
    const { wrapper } = await setup()
    expect(wrapper.text()).toContain('不支持自动部署此连接器')
    expect(button(wrapper, '渲染此源配置')).toBeUndefined()
    expect(api.renderSourceConfig).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
