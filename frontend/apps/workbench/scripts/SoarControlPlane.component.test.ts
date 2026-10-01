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
  confirmDanger: vi.fn(),
  setAutomationRuleEnabled: vi.fn(),
}))
vi.mock('../src/api', async importOriginal => ({ ...await importOriginal<object>(), ...mocks }))
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => ({ confirmDanger: mocks.confirmDanger }) }))

const emptyPage = { page: 0, size: 100, total: 0, totalPages: 0, items: [] }

beforeEach(() => {
  Object.values(mocks).forEach(mock => mock.mockReset())
  mocks.listAutomationRules.mockResolvedValue(emptyPage)
  mocks.listConnections.mockResolvedValue(emptyPage)
  mocks.listActions.mockResolvedValue([])
  mocks.listManualTasksPage.mockResolvedValue(emptyPage)
  mocks.listDeadDispatches.mockResolvedValue([])
  mocks.getStats.mockResolvedValue({ dispatchBacklog: 0, signalBacklog: 0, runsByStatus: {} })
  mocks.confirmDanger.mockResolvedValue(true)
  mocks.setAutomationRuleEnabled.mockResolvedValue({})
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

  it('reserves an operation while confirmation is pending and sends only the captured rule state', async () => {
    const rule = { id: 'rule-1', name: 'Rule A', enabled: true, priority: 1, eventType: 'alert.created', playbookVersionIds: [] }
    mocks.listAutomationRules.mockResolvedValue({ ...emptyPage, items: [rule] })
    let confirm: (result: boolean) => void = () => {}
    mocks.confirmDanger.mockImplementationOnce(() => new Promise(resolve => { confirm = resolve }))
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    const toggle = wrapper.findAll('button').find(button => button.text() === '禁用')!
    toggle.element.click()
    toggle.element.click()
    await flushPromises()
    expect(mocks.confirmDanger).toHaveBeenCalledTimes(1)
    rule.enabled = false
    confirm(true)
    await flushPromises()
    expect(mocks.setAutomationRuleEnabled).toHaveBeenCalledExactlyOnceWith('rule-1', false)
    wrapper.unmount()
  })

  it('does not execute an action after permission is removed while confirming', async () => {
    mocks.listAutomationRules.mockResolvedValue({ ...emptyPage, items: [{ id: 'rule-1', name: 'Rule A', enabled: true, playbookVersionIds: [] }] })
    let confirm: (result: boolean) => void = () => {}
    mocks.confirmDanger.mockImplementationOnce(() => new Promise(resolve => { confirm = resolve }))
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    wrapper.findAll('button').find(button => button.text() === '禁用')!.element.click()
    await wrapper.setProps({ canPublish: false })
    confirm(true)
    await flushPromises()
    expect(mocks.setAutomationRuleEnabled).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('starts the new tab load without waiting for the aborted connection catalog', async () => {
    let finish: (value: typeof emptyPage) => void = () => {}
    mocks.listConnections.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    mocks.getStats.mockResolvedValue({ dispatchBacklog: 7, signalBacklog: 0, runsByStatus: {} })
    const wrapper = await mountPlane({ section: 'connections-and-ops' })
    const signal = mocks.listConnections.mock.calls[0][2].signal as AbortSignal
    await wrapper.findAll('.soar-tabs button')[1].trigger('click')
    await flushPromises()
    expect(signal.aborted).toBe(true)
    expect(mocks.getStats).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.soar-stat-grid b').text()).toBe('7')
    finish(emptyPage)
    await flushPromises()
    expect(wrapper.find('.soar-stat-grid b').text()).toBe('7')
    wrapper.unmount()
  })

  it('does not execute a pending row confirmation after unmount', async () => {
    mocks.listAutomationRules.mockResolvedValue({ ...emptyPage, items: [{ id: 'rule-1', name: 'Rule A', enabled: true }] })
    let confirm: (result: boolean) => void = () => {}
    mocks.confirmDanger.mockImplementationOnce(() => new Promise(resolve => { confirm = resolve }))
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    wrapper.findAll('button').find(button => button.text() === '禁用')!.element.click()
    wrapper.unmount()
    confirm(true)
    await flushPromises()
    expect(mocks.setAutomationRuleEnabled).not.toHaveBeenCalled()
  })
})
