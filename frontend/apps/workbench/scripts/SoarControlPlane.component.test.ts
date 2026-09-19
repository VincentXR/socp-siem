import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SoarControlPlane from '../src/components/soar/SoarControlPlane.vue'

const mocks = vi.hoisted(() => ({
  listAutomationRules: vi.fn(),
  listConnections: vi.fn(),
  listActions: vi.fn(),
  listManualTasksPage: vi.fn(),
  listDeadDispatches: vi.fn(),
  getStats: vi.fn(),
}))
vi.mock('../src/api', async importOriginal => ({ ...await importOriginal<object>(), ...mocks }))

const emptyPage = { page: 0, size: 100, total: 0, totalPages: 0, items: [] }

beforeEach(() => {
  mocks.listAutomationRules.mockResolvedValue(emptyPage)
  mocks.listConnections.mockResolvedValue(emptyPage)
  mocks.listActions.mockResolvedValue([])
  mocks.listManualTasksPage.mockResolvedValue(emptyPage)
  mocks.listDeadDispatches.mockResolvedValue([])
  mocks.getStats.mockResolvedValue({ dispatchBacklog: 0, signalBacklog: 0, runsByStatus: {} })
})

async function mountPlane(props: { section: 'rules' | 'tasks' | 'connections-and-ops'; hideTabs?: boolean }) {
  const wrapper = mount(SoarControlPlane, { props })
  await flushPromises()
  return wrapper
}

describe('soar control plane data scoping', () => {
  it('loads only the rule catalog for the rules section', async () => {
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    expect(mocks.listAutomationRules).toHaveBeenCalled()
    expect(mocks.listConnections).not.toHaveBeenCalled()
    expect(mocks.listActions).not.toHaveBeenCalled()
    expect(mocks.listManualTasksPage).not.toHaveBeenCalled()
    expect(mocks.listDeadDispatches).not.toHaveBeenCalled()
    expect(mocks.getStats).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('starts on connections for the combined section and defers operations requests until the tab opens', async () => {
    const wrapper = await mountPlane({ section: 'connections-and-ops' })
    expect(mocks.listConnections).toHaveBeenCalled()
    expect(mocks.listActions).toHaveBeenCalled()
    expect(mocks.listDeadDispatches).not.toHaveBeenCalled()
    expect(mocks.getStats).not.toHaveBeenCalled()
    expect(mocks.listAutomationRules).not.toHaveBeenCalled()
    const tabs = wrapper.findAll('.soar-tabs button')
    expect(tabs).toHaveLength(2)
    expect(tabs[0].attributes('aria-selected')).toBe('true')
    await tabs[1].trigger('click')
    await flushPromises()
    expect(tabs[1].attributes('aria-selected')).toBe('true')
    expect(mocks.listDeadDispatches).toHaveBeenCalled()
    expect(mocks.getStats).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps the empty-state hidden while the first load is in flight', async () => {
    let release: (value: typeof emptyPage) => void = () => {}
    mocks.listManualTasksPage.mockImplementation(() => new Promise(resolve => { release = resolve }))
    const wrapper = mount(SoarControlPlane, { props: { section: 'tasks', hideTabs: true } })
    await flushPromises()
    expect(wrapper.find('.soar-empty').exists()).toBe(false)
    release(emptyPage)
    await flushPromises()
    expect(wrapper.find('.soar-empty').exists()).toBe(true)
    wrapper.unmount()
  })
})
