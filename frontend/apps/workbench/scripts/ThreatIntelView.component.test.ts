import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import ElPagination from 'element-plus/es/components/pagination/index.mjs'
import ThreatIntelView from '../src/views/ThreatIntelView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
import type { Ioc } from '../src/api'
import { translate } from '../src/i18n'

const mocks = vi.hoisted(() => ({
  confirm: vi.fn(),
  threatIntelApi: {
    list: vi.fn().mockResolvedValue({ items: [], total: 0 }),
    stats: vi.fn().mockResolvedValue({ total: 7, byType: { IP: 4, DOMAIN: 2, SHA256: 1 } }),
    match: vi.fn(), create: vi.fn(), update: vi.fn(), setRevoked: vi.fn(), bulkImport: vi.fn(), remove: vi.fn(),
  },
}))

vi.mock('../src/api/domains', () => mocks)
vi.mock('element-plus/es/components/message-box/index.mjs', () => ({ default: { confirm: mocks.confirm } }))

describe('IOC confirmation boundaries', () => {
  it('keeps the page usable while confirming, deduplicates and binds the deleted IOC', async () => {
    mocks.confirm.mockClear(); mocks.threatIntelApi.remove.mockClear()
    const { wrapper } = await mountAtLocation({})
    const ioc = { id: 'ioc-a', value: '192.0.2.1' } as Ioc
    let confirm!: (value: string) => void
    mocks.confirm.mockReturnValueOnce(new Promise<string>(resolve => { confirm = resolve }))
    const view = wrapper.vm as unknown as { removeIoc: (ioc: Ioc) => Promise<void>; actionBusy: boolean }
    const pending = view.removeIoc(ioc)
    await view.removeIoc(ioc)
    expect(mocks.confirm).toHaveBeenCalledTimes(1)
    expect(view.actionBusy).toBe(false)
    const createButton = wrapper.findAll('button').find(button => button.text() === translate('threat.addIoc'))
    expect(createButton).toBeTruthy()
    expect(createButton!.attributes('disabled')).toBeUndefined()
    expect(mocks.threatIntelApi.remove).not.toHaveBeenCalled()
    ioc.id = 'ioc-b'
    confirm('confirm'); await pending
    expect(mocks.threatIntelApi.remove).toHaveBeenCalledExactlyOnceWith('ioc-a')
    wrapper.unmount()
  })

  it('rechecks permission after a lifecycle confirmation', async () => {
    mocks.confirm.mockClear(); mocks.threatIntelApi.setRevoked.mockClear()
    const role = ref('admin')
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
    const wrapper = mount(ThreatIntelView, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: role } } } })
    await flushPromises()
    let confirm!: (value: string) => void
    mocks.confirm.mockReturnValueOnce(new Promise<string>(resolve => { confirm = resolve }))
    const view = wrapper.vm as unknown as { toggleLifecycle: (ioc: Ioc) => Promise<void> }
    const pending = view.toggleLifecycle({ id: 'ioc-a', revoked: false } as Ioc)
    role.value = 'viewer'; confirm('confirm'); await pending
    expect(mocks.threatIntelApi.setRevoked).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('threat intel metric cards', () => {
  it('renders one shared metric card per reported type plus the total', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
    const wrapper = mount(ThreatIntelView, {
      global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } },
    })
    await flushPromises()
    const cards = wrapper.findAll('.ti-metrics .metric-card')
    expect(cards).toHaveLength(4)
    expect(cards[0].find('.metric-card-label').text()).toBe('情报总量')
    expect(cards[0].find('.metric-card-value').text()).toBe('7')
    expect(cards[0].classes()).toContain('metric-card--info')
    expect(cards[1].find('.metric-card-label').text()).toBe('IP')
    expect(cards[1].find('.metric-card-value').text()).toBe('4')
    expect(cards[1].classes()).toContain('metric-card--neutral')
    // The legacy hand-rolled card bar wrapped every metric in an el-card.
    expect(wrapper.findAll('.ti-metrics .el-card')).toHaveLength(0)
    wrapper.unmount()
  })

  it('follows the number of types returned by the statistics endpoint', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
    mocks.threatIntelApi.stats.mockResolvedValueOnce({ total: 2, byType: { URL: 2 } })
    const wrapper = mount(ThreatIntelView, {
      global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } },
    })
    await flushPromises()
    const cards = wrapper.findAll('.ti-metrics .metric-card')
    expect(cards).toHaveLength(2)
    expect(cards[1].text()).toBe('URL2')
    wrapper.unmount()
  })
})

async function mountAtLocation(query: Record<string, string>) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/threat-intel', name: 'threat-intel', component: ThreatIntelView }] })
  await router.push({ path: '/threat-intel', query })
  await router.isReady()
  const replace = vi.fn()
  router.replace = replace
  const wrapper = mount(ThreatIntelView, {
    global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } },
  })
  await flushPromises()
  return { wrapper, replace }
}

describe('threat intel deep link hydration', () => {
  it('seeds the request from the query string (page, type and keyword)', async () => {
    await mountAtLocation({ q: 'abc', type: 'DOMAIN', page: '2' })
    // listIocs(type, page, size, q)
    const call = mocks.threatIntelApi.list.mock.calls.at(-1) ?? []
    expect(call.slice(0, 4)).toEqual(['DOMAIN', 2, 10, 'abc'])
  })

  it('drops a non-whitelisted type from the deep link', async () => {
    await mountAtLocation({ type: 'NOT-A-TYPE', page: '2' })
    const call = mocks.threatIntelApi.list.mock.calls.at(-1) ?? []
    expect(call.slice(0, 4)).toEqual([undefined, 2, 10, undefined])
  })

  it('writes the page back to the URL query after paging', async () => {
    mocks.threatIntelApi.list.mockResolvedValueOnce({ items: [], total: 25 })
    const { wrapper, replace } = await mountAtLocation({})
    replace.mockClear()
    wrapper.findComponent(ElPagination).vm.$emit('update:current-page', 2)
    await flushPromises()
    const query = (replace.mock.calls.at(-1)?.[0] as { query: Record<string, unknown> }).query
    expect(query.page).toBe('2')
    expect(query.q).toBeUndefined()
    expect(query.type).toBeUndefined()
  })
})
