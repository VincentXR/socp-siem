import { mount, flushPromises } from '@vue/test-utils'
import { h, ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AssetsView from '../src/views/AssetsView.vue'
import PagerBar from '../src/components/PagerBar.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
import type { Asset, Endpoint, Paged } from '../src/api/models'

const mocks = vi.hoisted(() => ({
  list: vi.fn(), get: vi.fn(), stats: vi.fn(), create: vi.fn(), update: vi.fn(), bulkImport: vi.fn(), remove: vi.fn(),
  related: vi.fn(), endpointList: vi.fn(), success: vi.fn(), info: vi.fn(), confirm: vi.fn(),
}))
vi.mock('../src/api/domains', async original => ({ ...await original<object>(), assetApi: mocks,
  endpointApi: { related: mocks.related, list: mocks.endpointList },
}))
vi.mock('element-plus/es/components/message/index.mjs', () => ({ default: { success: mocks.success, info: mocks.info } }))
vi.mock('element-plus/es/components/message-box/index.mjs', () => ({ default: { confirm: mocks.confirm } }))

const asset = (id: string): Asset => ({ id, name: `Asset ${id}`, type: 'SERVER', ip: '203.0.113.7', os: 'Linux', owner: 'sec', criticality: 'HIGH' })
const endpoint = (hostname: string): Endpoint => ({ id: hostname, hostname, ip: '203.0.113.7', os: 'Linux', status: 'ONLINE', agentVersion: 'agent', lastHeartbeat: '2026-09-21T01:00:00Z' })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
let wrapper: ReturnType<typeof mount>
async function open(path = '/assets?assetId=outside') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { name: 'assets', path: '/assets', component: AssetsView },
    { name: 'endpoints', path: '/endpoints', component: { template: '<p>Endpoints</p>' } },
  ] })
  await router.push(path); await router.isReady()
  wrapper = mount({ render: () => h(RouterView) }, { attachTo: document.body, global: {
    plugins: [router], provide: { [WORKBENCH_STATE as symbol]: {
      currentRole: ref('analyst'), currentUser: ref('alice'), operatorOptions: ref(['alice', 'sec']),
    } },
  } })
  await flushPromises()
  return router
}
async function click(label: string, scope = '') {
  const container = scope ? wrapper.find(scope) : wrapper
  const button = container.findAll('button').find(item => item.text() === label)
  expect(button, label).toBeTruthy()
  await button!.trigger('click'); await flushPromises()
}
async function chooseFile(text: string, name = 'assets.json', size = text.length) {
  const input = wrapper.find('input[type="file"]')
  const read = vi.fn(async () => text)
  Object.defineProperty(input.element, 'files', { configurable: true, value: [{ name, size, text: read }] })
  await input.trigger('change'); await flushPromises()
  return read
}

describe('asset investigation and reviewed writes', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.list.mockResolvedValue({ items: [asset('listed')], total: 120 })
    mocks.stats.mockResolvedValue({ total: 120, byType: { SERVER: 120 }, byCriticality: { HIGH: 120 } })
    mocks.get.mockImplementation(async id => asset(id))
    mocks.related.mockResolvedValue({ items: [endpoint('matching-host')], total: 61 })
    mocks.create.mockImplementation(async payload => ({ ...payload, id: 'created' }))
    mocks.update.mockImplementation(async (id, payload) => ({ ...payload, id }))
    mocks.bulkImport.mockResolvedValue({ imported: 1, skipped: 0, errors: [] })
    mocks.remove.mockResolvedValue({ removed: true })
    mocks.confirm.mockResolvedValue('confirm')
  })
  afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

  it('loads off-page detail and exact paged associations without prefetched tenant inventories', async () => {
    await open('/assets')
    expect(mocks.get).not.toHaveBeenCalled()
    expect(mocks.related).not.toHaveBeenCalled()
    expect(mocks.endpointList).not.toHaveBeenCalled()
    await click('详情')
    expect(mocks.get).toHaveBeenCalledWith('listed', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(mocks.related).toHaveBeenCalledWith('203.0.113.7', 'Asset listed', 1, 20, expect.anything())
    wrapper.find('.el-drawer').findComponent(PagerBar).vm.$emit('update:currentPage', 2)
    await flushPromises()
    expect(mocks.related).toHaveBeenLastCalledWith('203.0.113.7', 'Asset listed', 2, 20, expect.anything())
  })

  it('opens a direct link despite list failure and distinguishes lookup failure from no match', async () => {
    mocks.list.mockRejectedValue(new Error('List unavailable'))
    mocks.related.mockRejectedValueOnce(new Error('Endpoint directory unavailable'))
    await open()
    expect(wrapper.find('.el-drawer').text()).toContain('Asset outside')
    expect(wrapper.find('.el-drawer [role="alert"]').text()).toContain('Endpoint directory unavailable')
    expect(wrapper.find('.asset-endpoints .asset-detail-muted').exists()).toBe(false)
    await click('重试', '.el-drawer')
    expect(wrapper.find('.el-drawer').text()).toContain('matching-host')
    expect(wrapper.text()).toContain('List unavailable')
  })

  it('ignores obsolete detail and association responses after the selected identity changes', async () => {
    const oldDetail = deferred<Asset>()
    const oldEndpoints = deferred<Paged<Endpoint>>()
    mocks.get.mockReturnValueOnce(oldDetail.promise)
    const router = await open('/assets?assetId=first')
    mocks.related.mockReturnValueOnce(oldEndpoints.promise)
    await router.push('/assets?assetId=second'); await flushPromises()
    await router.push('/assets?assetId=third'); await flushPromises()
    oldDetail.resolve(asset('first'))
    oldEndpoints.reject(new Error('Obsolete association error'))
    await flushPromises()
    expect(wrapper.find('.el-drawer').text()).toContain('Asset third')
    expect(wrapper.find('.el-drawer').text()).not.toContain('Asset first')
    expect(wrapper.find('.el-drawer').text()).not.toContain('Obsolete association error')
    expect(mocks.get.mock.calls[0][1].signal.aborted).toBe(true)
  })

  it('offers a direct-detail retry and confirms before replacing an edited asset', async () => {
    mocks.get.mockRejectedValueOnce(new Error('Asset unavailable'))
    const router = await open()
    expect(wrapper.find('.el-drawer [role="alert"]').text()).toContain('Asset unavailable')
    expect(mocks.related).not.toHaveBeenCalled()
    await click('重试', '.el-drawer')
    await click('编辑', '.el-drawer')
    await wrapper.find('.el-dialog input').setValue('Unsaved name')
    mocks.confirm.mockRejectedValueOnce(new Error('keep editing'))
    await router.push('/assets?assetId=other'); await flushPromises()
    expect(router.currentRoute.value.query.assetId).toBe('outside')
    expect(wrapper.find('.el-dialog input').element).toHaveProperty('value', 'Unsaved name')
    await router.push('/assets?assetId=other'); await flushPromises()
    expect(wrapper.find('.el-drawer').text()).toContain('Asset other')
  })

  it('retains a failed edit and applies the acknowledged asset despite background refresh failure', async () => {
    const router = await open()
    await click('编辑', '.el-drawer')
    await wrapper.find('.el-dialog input').setValue('Corrected asset')
    mocks.update.mockRejectedValueOnce(new Error('Write unavailable'))
    await click('保存', '.el-dialog')
    expect(wrapper.find('.el-dialog [role="alert"]').text()).toContain('Write unavailable')
    expect(wrapper.find('.el-dialog input').element).toHaveProperty('value', 'Corrected asset')
    mocks.list.mockRejectedValueOnce(new Error('Refresh unavailable'))
    await click('保存', '.el-dialog')
    expect(wrapper.find('.el-drawer').text()).toContain('Corrected asset')
    expect(mocks.update).toHaveBeenLastCalledWith('outside', expect.objectContaining({ name: 'Corrected asset' }))
    expect(wrapper.text()).toContain('Refresh unavailable')
    await router.push('/endpoints')
    expect(mocks.confirm).not.toHaveBeenCalled()
  })

  it('blocks changing identity or leaving during an unchanged edit submission', async () => {
    const pending = deferred<Asset>()
    mocks.update.mockReturnValueOnce(pending.promise)
    const router = await open()
    await click('编辑', '.el-drawer')
    await click('保存', '.el-dialog')
    expect(wrapper.find('.el-dialog input').attributes('disabled')).toBeDefined()
    await click('保存', '.el-dialog')
    await router.push('/assets?assetId=other')
    await router.push('/endpoints')
    expect(router.currentRoute.value.fullPath).toBe('/assets?assetId=outside')
    expect(mocks.update).toHaveBeenCalledTimes(1)
    pending.reject(new Error('Write unavailable')); await flushPromises()
    expect(wrapper.find('.el-dialog input').element).toHaveProperty('value', 'Asset outside')
  })

  it('keeps a deletion on the reviewed ID when another asset opens during confirmation', async () => {
    const confirmation = deferred<string>()
    mocks.confirm.mockReturnValueOnce(confirmation.promise)
    const router = await open('/assets')
    await click('删除')
    await router.push('/assets?assetId=other'); await flushPromises()
    mocks.list.mockRejectedValueOnce(new Error('Refresh unavailable'))
    confirmation.resolve('confirm'); await flushPromises()
    expect(mocks.remove).toHaveBeenCalledWith('listed')
    expect(router.currentRoute.value.query.assetId).toBe('other')
    expect(wrapper.find('.el-drawer').text()).toContain('Asset other')
    expect(wrapper.find('.el-table').text()).not.toContain('Asset listed')
  })

  it('requires review before import and freezes the reviewed rows until acknowledgement', async () => {
    const router = await open('/assets')
    await chooseFile(JSON.stringify([{ name: 'Imported host', ip: '203.0.113.9' }]))
    expect(mocks.bulkImport).not.toHaveBeenCalled()
    expect(wrapper.find('.el-dialog').text()).toContain('Imported host')
    mocks.bulkImport.mockRejectedValueOnce(new Error('Import response unavailable'))
    await click('导入已核对的记录', '.el-dialog')
    expect(wrapper.find('.el-dialog [role="status"]').text()).toContain('再次提交可能产生重复记录')
    expect(wrapper.find('.el-dialog').text()).toContain('Imported host')
    const pending = deferred<{ imported: number; skipped: number; errors: string[] }>()
    mocks.bulkImport.mockReturnValueOnce(pending.promise)
    await click('导入已核对的记录', '.el-dialog')
    await click('取消', '.el-dialog')
    await router.push('/endpoints')
    expect(router.currentRoute.value.path).toBe('/assets')
    await click('导入已核对的记录', '.el-dialog')
    expect(mocks.bulkImport).toHaveBeenCalledTimes(2)
    pending.resolve({ imported: 1, skipped: 0, errors: [] }); await flushPromises()
    expect(wrapper.text()).toContain('已导入')
    expect(mocks.bulkImport.mock.calls[1][0]).toEqual([expect.objectContaining({ name: 'Imported host', ip: '203.0.113.9', type: 'SERVER' })])
  })

  it('rejects oversized, empty, excessive and invalid imports before any write', async () => {
    await open('/assets')
    const read = await chooseFile('[]', 'assets.json', 2 * 1024 * 1024 + 1)
    expect(read).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('1–500')
    await chooseFile(JSON.stringify(Array.from({ length: 501 }, () => ({ name: 'host', ip: '203.0.113.7' }))))
    expect(wrapper.text()).toContain('1–500')
    await chooseFile('[]')
    expect(wrapper.text()).toContain('1–500')
    await chooseFile(JSON.stringify([{ name: 'host', ip: '203.0.113.7', criticality: 'UNKNOWN' }]))
    expect(wrapper.text()).toContain('第 1 行')
    expect(mocks.bulkImport).not.toHaveBeenCalled()
    expect(wrapper.find('.el-dialog').exists()).toBe(false)
  })

  it('restores filter pages through history without resetting them after the debounce', async () => {
    const router = await open('/assets?q=old&page=3')
    await router.push('/assets?q=restored&page=2'); await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 350)); await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ q: 'restored', page: '2' })
    expect(mocks.list).toHaveBeenLastCalledWith(2, 10, 'restored', expect.anything())
  })
})
