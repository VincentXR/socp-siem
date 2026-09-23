import { mount, flushPromises } from '@vue/test-utils'
import { h, ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import EndpointsView from '../src/views/EndpointsView.vue'
import { translate } from '../src/i18n'
import PagerBar from '../src/components/PagerBar.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
import type { Endpoint } from '../src/api/models'

const mocks = vi.hoisted(() => ({ list: vi.fn(), get: vi.fn(), stats: vi.fn(), history: vi.fn(), events: vi.fn(), remove: vi.fn(), related: vi.fn(), assetList: vi.fn(), confirm: vi.fn(), info: vi.fn() }))
vi.mock('../src/api/domains', async original => ({ ...await original<object>(), endpointApi: mocks, assetApi: { related: mocks.related, list: mocks.assetList } }))
vi.mock('element-plus/es/components/message/index.mjs', () => ({ default: { info: mocks.info } }))
vi.mock('element-plus/es/components/message-box/index.mjs', () => ({ default: { confirm: mocks.confirm } }))
const endpoint = (id: string): Endpoint => ({ id, hostname: `host-${id}`, ip: '203.0.113.7', os: 'Linux', status: 'ONLINE', agentVersion: 'agent', lastHeartbeat: '2026-09-21T01:00:00Z' })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (value: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
let wrapper: ReturnType<typeof mount>
async function open(path = '/endpoints?endpointId=outside', role = 'analyst') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { name: 'endpoints', path: '/endpoints', component: EndpointsView },
    { name: 'assets', path: '/assets', component: { template: '<p>Assets</p>' } },
  ] })
  await router.push(path); await router.isReady()
  wrapper = mount({ render: () => h(RouterView) }, { attachTo: document.body, global: {
    plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref(role), currentUser: ref('alice') } },
  } })
  await flushPromises()
  return router
}
async function click(label: string, scope = '') {
  const button = (scope ? wrapper.find(scope) : wrapper).findAll('button').find(item => item.text() === label)
  expect(button, label).toBeTruthy()
  await button!.trigger('click'); await flushPromises()
}

describe('endpoint investigation', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.list.mockResolvedValue({ items: [endpoint('listed')], total: 120 })
    mocks.get.mockImplementation(async id => endpoint(id))
    mocks.stats.mockResolvedValue({ total: 120, online: 30, events: 2000 })
    mocks.history.mockImplementation(async id => ({ items: [{ eventId: `${id}-event`, message: `Evidence ${id}` }], total: 61 }))
    mocks.related.mockResolvedValue({ items: [{ id: 'asset', name: 'Matching asset', ip: '203.0.113.7', type: 'SERVER', criticality: 'HIGH' }], total: 42 })
    mocks.remove.mockResolvedValue({ removed: true })
    mocks.confirm.mockResolvedValue('confirm')
  })
  afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

  it('opens off-page details and pages both associations without global prefix fetches', async () => {
    const router = await open('/endpoints?page=2&q=unrelated&endpointId=outside')
    expect(mocks.get).toHaveBeenCalledWith('outside', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(mocks.history).toHaveBeenCalledWith('outside', 1, 20, expect.anything())
    expect(mocks.related).toHaveBeenCalledWith('203.0.113.7', 'host-outside', 1, 20, expect.anything())
    expect(mocks.events).not.toHaveBeenCalled(); expect(mocks.assetList).not.toHaveBeenCalled()
    expect(wrapper.find('.el-drawer').text()).toContain(translate('endpoints.eventMatchHint'))
    wrapper.find('[data-testid="endpoint-events"]').findComponent(PagerBar).vm.$emit('update:currentPage', 2)
    wrapper.find('[data-testid="endpoint-assets"]').findComponent(PagerBar).vm.$emit('update:currentPage', 2)
    await flushPromises()
    expect(mocks.history).toHaveBeenLastCalledWith('outside', 2, 20, expect.anything())
    expect(mocks.related).toHaveBeenLastCalledWith('203.0.113.7', 'host-outside', 2, 20, expect.anything())
    await click('Matching asset', '.el-drawer')
    expect(router.currentRoute.value.query.assetId).toBe('asset')
  })

  it('does not fetch detail associations until selected and restores a closed deep link through history', async () => {
    const router = await open('/endpoints?page=2&q=before')
    expect(mocks.get).not.toHaveBeenCalled(); expect(mocks.history).not.toHaveBeenCalled()
    await click(translate('common.details'))
    expect(router.currentRoute.value.query.endpointId).toBe('listed')
    await wrapper.find('.el-drawer__close-btn').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ page: '2', q: 'before' })
    router.back(); await vi.waitFor(() => expect(router.currentRoute.value.query.endpointId).toBe('listed'))
    await flushPromises()
    expect(wrapper.find('.el-drawer').text()).toContain('host-listed')
  })

  it('keeps lookup, assets, events and stats failures distinct and retryable', async () => {
    mocks.get.mockRejectedValueOnce(new Error('Detail unavailable'))
    mocks.list.mockRejectedValue(new Error('List unavailable'))
    mocks.stats.mockRejectedValue(new Error('Stats unavailable'))
    mocks.related.mockRejectedValueOnce(new Error('Asset lookup unavailable'))
    mocks.history.mockRejectedValueOnce(new Error('History unavailable'))
    await open()
    expect(wrapper.find('.el-drawer').text()).toContain('Detail unavailable')
    expect(wrapper.text()).toContain('Stats unavailable')
    expect(wrapper.find('.page-metrics').exists()).toBe(false)
    await click(translate('common.retry'), '.el-drawer')
    expect(wrapper.find('[data-testid="endpoint-assets"]').text()).toContain('Asset lookup unavailable')
    expect(wrapper.find('[data-testid="endpoint-events"]').text()).toContain('History unavailable')
    expect(wrapper.find('.el-drawer').text()).not.toContain(translate('endpoints.noRuntimeEvents'))
    expect(wrapper.find('.el-drawer').text()).not.toContain(translate('endpoints.noRelatedAsset'))
    await click(translate('common.retry'), '[data-testid="endpoint-assets"]')
    await click(translate('common.retry'), '[data-testid="endpoint-events"]')
    expect(wrapper.find('.el-drawer').text()).toContain('Evidence outside')
    expect(wrapper.find('.el-drawer').text()).toContain('Matching asset')
  })

  it('ignores stale details and associations after route selection changes', async () => {
    const detail = deferred<Endpoint>()
    const history = deferred<unknown>()
    const assets = deferred<unknown>()
    mocks.get.mockReturnValueOnce(detail.promise)
    const router = await open('/endpoints?endpointId=first')
    mocks.history.mockReturnValueOnce(history.promise); mocks.related.mockReturnValueOnce(assets.promise)
    await router.push('/endpoints?endpointId=second'); await flushPromises()
    await router.push('/endpoints?endpointId=third'); await flushPromises()
    detail.resolve(endpoint('first'))
    history.resolve({ items: [{ eventId: 'obsolete', message: 'OLD EVIDENCE' }], total: 1 })
    assets.resolve({ items: [{ id: 'obsolete', name: 'OLD ASSET' }], total: 1 })
    await flushPromises()
    expect(wrapper.find('.el-drawer').text()).toContain('host-third')
    expect(wrapper.find('.el-drawer').text()).toContain('Evidence third')
    expect(wrapper.find('.el-drawer').text()).not.toContain('OLD')
  })

  it('pins deletion to its confirmed identity and keeps a newer selection after acknowledgement', async () => {
    const confirmation = deferred<string>()
    mocks.confirm.mockReturnValueOnce(confirmation.promise)
    const router = await open('/endpoints?endpointId=listed')
    await click(translate('endpoints.unregister'), '.el-drawer')
    expect(mocks.confirm.mock.calls[0][0]).toContain('listed')
    await router.push('/endpoints?endpointId=other'); await flushPromises()
    mocks.list.mockRejectedValueOnce(new Error('Refresh unavailable'))
    confirmation.resolve('confirm'); await flushPromises()
    expect(mocks.remove).toHaveBeenCalledWith('listed')
    expect(router.currentRoute.value.query.endpointId).toBe('other')
    expect(wrapper.find('.el-drawer').text()).toContain('host-other')
    expect(wrapper.find('.el-table').text()).not.toContain('host-listed')
    expect(wrapper.text()).toContain('Refresh unavailable')
  })

  it('blocks close, navigation and duplicate deletion until failure settles, then permits retry', async () => {
    const pending = deferred<unknown>()
    mocks.remove.mockReturnValueOnce(pending.promise)
    const router = await open()
    await click(translate('endpoints.unregister'), '.el-drawer')
    await click(translate('endpoints.unregister'), '.el-drawer')
    await wrapper.find('.el-drawer__close-btn').trigger('click'); await flushPromises()
    await router.push('/assets'); await flushPromises()
    expect(router.currentRoute.value.query.endpointId).toBe('outside')
    const unload = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    expect(mocks.remove).toHaveBeenCalledTimes(1)
    pending.reject(new Error('Unregister unavailable')); await flushPromises()
    expect(wrapper.find('.el-drawer').text()).toContain('Unregister unavailable')
    await click(translate('endpoints.unregister'), '.el-drawer')
    expect(mocks.remove).toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.query.endpointId).toBeUndefined()
  })

  it('restores query pages without a delayed keyword reset and hides writes from viewers', async () => {
    const router = await open('/endpoints?page=2&q=before&endpointId=outside', 'viewer')
    expect(wrapper.find('.el-drawer').text()).not.toContain(translate('endpoints.unregister'))
    await router.push('/endpoints?page=3&q=after&endpointId=outside'); await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 400)); await flushPromises()
    expect(router.currentRoute.value.query.page).toBe('3')
    expect(mocks.list).toHaveBeenLastCalledWith(3, 10, 'after', expect.anything())
  })
})
