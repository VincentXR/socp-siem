import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SoarControlPlane from '../src/components/soar/SoarControlPlane.vue'
import SoarCatalogPager from '../src/components/soar/SoarCatalogPager.vue'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import type { SoarPage, SoarPlaybook, SoarVersion } from '../src/api'

const mocks = vi.hoisted(() => ({
  listAutomationRules: vi.fn(),
  listConnections: vi.fn(),
  listActions: vi.fn(),
  listManualTasksPage: vi.fn(),
  listDeadDispatches: vi.fn(),
  getStats: vi.fn(),
  confirmDanger: vi.fn(),
  setAutomationRuleEnabled: vi.fn(),
  listPlaybooks: vi.fn(),
  listVersions: vi.fn(),
  createAutomationRule: vi.fn(),
  patchAutomationRule: vi.fn(),
}))
vi.mock('../src/api', async importOriginal => ({ ...await importOriginal<object>(), ...mocks }))
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => ({ confirmDanger: mocks.confirmDanger }) }))

const emptyPage = { page: 0, size: 25, total: 0, totalPages: 0, items: [] }

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
  mocks.listPlaybooks.mockResolvedValue(emptyPage)
  mocks.listVersions.mockResolvedValue([])
  mocks.createAutomationRule.mockResolvedValue({})
  mocks.patchAutomationRule.mockResolvedValue({})
})

async function mountPlane(props: { section: 'rules' | 'tasks' | 'connections-and-ops'; hideTabs?: boolean }) {
  const wrapper = mount(SoarControlPlane, { props })
  await flushPromises()
  return wrapper
}

describe('soar control plane data scoping', () => {
  it('prefills a published-version handoff for review without creating or enabling a rule', async () => {
    const wrapper = mount(SoarControlPlane, { props: { section: 'rules', initialVersionId: 'published-version', canWrite: true } })
    await flushPromises()
    expect(versionPicker(wrapper).props('modelValue')).toEqual(['published-version'])
    expect(mocks.createAutomationRule).not.toHaveBeenCalled()
    expect(mocks.setAutomationRuleEnabled).not.toHaveBeenCalled()
    await wrapper.setProps({ canWrite: false })
    await flushPromises()
    expect(wrapper.findAllComponents(ElDrawer).some(drawer => drawer.props('modelValue'))).toBe(false)
    wrapper.unmount()
  })

  it('does not open a version handoff for a read-only operator', async () => {
    const wrapper = mount(SoarControlPlane, { props: { section: 'rules', initialVersionId: 'published-version', canWrite: false } })
    await flushPromises()
    expect(wrapper.findAllComponents(ElDrawer).some(drawer => drawer.props('modelValue'))).toBe(false)
    expect(mocks.listPlaybooks).not.toHaveBeenCalled()
    expect(mocks.createAutomationRule).not.toHaveBeenCalled()
    wrapper.unmount()
  })

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

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function catalogPage<T>(items: T[], page = 0, size = 25): SoarPage<T> {
  return { page, size, total: items.length, totalPages: Math.ceil(items.length / size), items: items.slice(page * size, (page + 1) * size) }
}

const catalogRows = Array.from({ length: 126 }, (_, index) => ({
  id: `catalog-${index + 1}`, name: `Catalog ${index + 1}`, enabled: true, priority: 1,
  triggerType: 'alert.created', actions: [], status: 'PENDING', runId: `run-${index + 1}`, nodeId: 'node',
}))
const playbooks: SoarPlaybook[] = Array.from({ length: 126 }, (_, index) => ({
  id: `playbook-${index + 1}`, name: `Playbook ${index + 1}`, status: 'ACTIVE', tags: [],
}))
function publishedVersion(playbookId: string): SoarVersion {
  return { id: `${playbookId}-published`, playbookId, version: 2, status: 'PUBLISHED', schemaVersion: '1', definition: {}, layout: {}, definitionHash: '', riskSummary: {} }
}

type Plane = Awaited<ReturnType<typeof mountPlane>>
function tablePager(wrapper: Plane) { return wrapper.findAllComponents(SoarCatalogPager).at(-1)! }
function playbookPicker(wrapper: Plane) { return wrapper.findAllComponents(ElSelect).find(select => select.classes().includes('soar-rule-playbook-select'))! }
function versionPicker(wrapper: Plane) { return wrapper.findAllComponents(ElSelect).find(select => select.classes().includes('soar-rule-version-select'))! }
function playbookPager(wrapper: Plane) { return wrapper.findAllComponents(SoarCatalogPager)[0] }
async function openNewRule(wrapper: Plane) {
  await wrapper.find('.soar-section-toolbar button').trigger('click')
  await flushPromises()
}
async function closeRuleDrawer(wrapper: Plane) {
  wrapper.findAllComponents(ElDrawer)[0].vm.$emit('update:modelValue', false)
  await flushPromises()
}
function optionValues(picker: ReturnType<typeof versionPicker>) {
  return picker.findAllComponents(ElOption).map(option => option.props('value'))
}

describe('bounded control-plane catalog pages', () => {
  it.each(['rules', 'connections-and-ops', 'tasks'] as const)('lets %s browse past 100 records without fetching hidden pages', async section => {
    mocks.listAutomationRules.mockImplementation((page, size) => Promise.resolve(catalogPage(catalogRows, page, size)))
    mocks.listConnections.mockImplementation((page, size) => Promise.resolve(catalogPage(catalogRows, page, size)))
    mocks.listManualTasksPage.mockImplementation((_pending, page, size) => Promise.resolve(catalogPage(catalogRows, page, size)))
    const wrapper = await mountPlane({ section, hideTabs: true })
    expect(wrapper.findAll('tbody tr')).toHaveLength(25)
    expect(wrapper.find('tbody').text()).toContain('catalog-1')
    for (let page = 1; page <= 4; page++) {
      await tablePager(wrapper).findAll('button')[1].trigger('click')
      await flushPromises()
      expect(tablePager(wrapper).props('page')).toBe(page)
    }
    expect(wrapper.find('tbody').text()).toContain('catalog-101')
    expect(wrapper.findAll('tbody tr')).toHaveLength(25)
    const calls = section === 'rules' ? mocks.listAutomationRules.mock.calls
      : section === 'tasks' ? mocks.listManualTasksPage.mock.calls.map(call => call.slice(1))
        : mocks.listConnections.mock.calls
    expect(calls.map(call => call.slice(0, 2))).toEqual([[0, 25], [1, 25], [2, 25], [3, 25], [4, 25]])
    if (section === 'connections-and-ops') expect(mocks.listActions).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('retains independent page positions when the section changes', async () => {
    mocks.listAutomationRules.mockImplementation((page, size) => Promise.resolve(catalogPage(catalogRows, page, size)))
    mocks.listConnections.mockImplementation((page, size) => Promise.resolve(catalogPage(catalogRows, page, size)))
    mocks.listManualTasksPage.mockImplementation((_pending, page, size) => Promise.resolve(catalogPage(catalogRows, page, size)))
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    tablePager(wrapper).vm.$emit('change', 4)
    await flushPromises()
    await wrapper.setProps({ section: 'connections-and-ops' })
    await flushPromises()
    expect(tablePager(wrapper).props('page')).toBe(0)
    tablePager(wrapper).vm.$emit('change', 2)
    await flushPromises()
    await wrapper.setProps({ section: 'tasks' })
    await flushPromises()
    expect(tablePager(wrapper).props('page')).toBe(0)
    tablePager(wrapper).vm.$emit('change', 1)
    await flushPromises()
    await wrapper.setProps({ section: 'rules' })
    await flushPromises()
    expect(tablePager(wrapper).props('page')).toBe(4)
    expect(wrapper.find('tbody').text()).toContain('catalog-101')
    await wrapper.setProps({ section: 'connections-and-ops' })
    await flushPromises()
    expect(tablePager(wrapper).props('page')).toBe(2)
    wrapper.unmount()
  })

  it.each(['rules', 'connections-and-ops', 'tasks'] as const)('clamps %s after the current last page is removed', async section => {
    let rows = catalogRows
    mocks.listAutomationRules.mockImplementation((page, size) => Promise.resolve(catalogPage(rows, page, size)))
    mocks.listConnections.mockImplementation((page, size) => Promise.resolve(catalogPage(rows, page, size)))
    mocks.listManualTasksPage.mockImplementation((_pending, page, size) => Promise.resolve(catalogPage(rows, page, size)))
    const wrapper = await mountPlane({ section, hideTabs: true })
    tablePager(wrapper).vm.$emit('change', 4)
    await flushPromises()
    rows = catalogRows.slice(0, 60)
    await wrapper.find('.soar-control-header button').trigger('click')
    await flushPromises()
    expect(tablePager(wrapper).props('page')).toBe(2)
    expect(tablePager(wrapper).props('total')).toBe(60)
    expect(wrapper.findAll('tbody tr')).toHaveLength(10)
    expect(wrapper.find('tbody').text()).toContain('catalog-51')
    rows = []
    await wrapper.find('.soar-control-header button').trigger('click')
    await flushPromises()
    expect(tablePager(wrapper).props('page')).toBe(0)
    expect(tablePager(wrapper).props('totalPages')).toBe(0)
    expect(wrapper.findAll('tbody tr')).toHaveLength(0)
    wrapper.unmount()
  })

  it.each(['resolve', 'reject'] as const)('ignores an old page %s after newer navigation and aborts pending work on unmount', async outcome => {
    const old = deferred<SoarPage<typeof catalogRows[number]>>()
    const current = deferred<SoarPage<typeof catalogRows[number]>>()
    mocks.listAutomationRules.mockResolvedValueOnce(catalogPage(catalogRows))
      .mockImplementationOnce(() => old.promise).mockImplementationOnce(() => current.promise)
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    tablePager(wrapper).vm.$emit('change', 4)
    await flushPromises()
    const oldSignal = mocks.listAutomationRules.mock.calls[1][2].signal as AbortSignal
    await wrapper.setProps({ section: 'tasks' })
    await flushPromises()
    await wrapper.setProps({ section: 'rules' })
    await flushPromises()
    expect(oldSignal.aborted).toBe(true)
    if (outcome === 'resolve') old.resolve(catalogPage([], 4))
    else old.reject(new Error('obsolete page error'))
    await flushPromises()
    expect(mocks.listAutomationRules).toHaveBeenCalledTimes(3)
    expect(tablePager(wrapper).props('loading')).toBe(true)
    expect(wrapper.text()).not.toContain('obsolete page error')
    current.resolve(catalogPage(catalogRows, 4))
    await flushPromises()
    expect(wrapper.find('tbody').text()).toContain('catalog-101')
    const last = deferred<SoarPage<typeof catalogRows[number]>>()
    mocks.listAutomationRules.mockImplementationOnce(() => last.promise)
    await tablePager(wrapper).findAll('button')[0].trigger('click')
    const lastSignal = mocks.listAutomationRules.mock.calls.at(-1)![2].signal as AbortSignal
    wrapper.unmount()
    expect(lastSignal.aborted).toBe(true)
    last.resolve(catalogPage([], 3))
    await flushPromises()
    expect(mocks.listAutomationRules).toHaveBeenCalledTimes(4)
  })
})

describe('paged rule playbook selection', () => {
  beforeEach(() => {
    mocks.listPlaybooks.mockImplementation((page, size) => Promise.resolve(catalogPage(playbooks, page, size)))
    mocks.listVersions.mockImplementation(id => Promise.resolve([
      publishedVersion(id), { ...publishedVersion(id), id: `${id}-draft`, status: 'DRAFT', version: 3 },
    ]))
  })

  it('fetches only the selected playbook versions and preserves selected labels while browsing beyond 100', async () => {
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    await openNewRule(wrapper)
    expect(mocks.listPlaybooks).toHaveBeenCalledTimes(1)
    expect(mocks.listPlaybooks).toHaveBeenLastCalledWith(0, 25, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(mocks.listVersions).not.toHaveBeenCalled()
    playbookPicker(wrapper).vm.$emit('change', 'playbook-1')
    await flushPromises()
    expect(mocks.listVersions).toHaveBeenCalledTimes(1)
    expect(optionValues(versionPicker(wrapper))).toEqual(['playbook-1-published'])
    versionPicker(wrapper).vm.$emit('update:modelValue', ['playbook-1-published'])
    await flushPromises()
    for (let page = 1; page <= 4; page++) {
      await playbookPager(wrapper).findAll('button')[1].trigger('click')
      await flushPromises()
    }
    expect(optionValues(playbookPicker(wrapper))).toContain('playbook-101')
    expect(optionValues(playbookPicker(wrapper))).toHaveLength(26)
    expect(playbookPicker(wrapper).props('modelValue')).toBe('playbook-1')
    expect(mocks.listVersions).toHaveBeenCalledTimes(1)
    playbookPicker(wrapper).vm.$emit('change', 'playbook-101')
    await flushPromises()
    expect(mocks.listVersions).toHaveBeenLastCalledWith('playbook-101', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(optionValues(versionPicker(wrapper))).toEqual(['playbook-1-published', 'playbook-101-published'])
    expect(versionPicker(wrapper).findAllComponents(ElOption)[0].props('label')).toBe('Playbook 1 · Revision 2')
    versionPicker(wrapper).vm.$emit('update:modelValue', ['playbook-1-published', 'playbook-101-published'])
    await flushPromises()
    playbookPicker(wrapper).vm.$emit('change', 'playbook-102')
    await flushPromises()
    expect(optionValues(versionPicker(wrapper))).toHaveLength(3)
    versionPicker(wrapper).vm.$emit('update:modelValue', ['playbook-101-published'])
    await flushPromises()
    expect(optionValues(versionPicker(wrapper))).toEqual(['playbook-101-published', 'playbook-102-published'])
    wrapper.unmount()
  })

  it('keeps existing off-page version IDs and action settings when an edited rule is saved', async () => {
    mocks.listAutomationRules.mockResolvedValue(catalogPage([{
      ...catalogRows[0], name: 'Existing rule', rowVersion: 8,
      actions: [{ playbookVersionId: 'off-page-version', inputs: { preserve: true } }],
    }]))
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    await wrapper.findAll('tbody button')[1].trigger('click')
    await flushPromises()
    expect(versionPicker(wrapper).props('modelValue')).toEqual(['off-page-version'])
    expect(optionValues(versionPicker(wrapper))).toContain('off-page-version')
    playbookPicker(wrapper).vm.$emit('change', 'playbook-1')
    await flushPromises()
    expect(optionValues(versionPicker(wrapper))).toEqual(['off-page-version', 'playbook-1-published'])
    const drawer = wrapper.findAllComponents(ElDrawer)[0]
    await drawer.findAll('.el-drawer__footer button').at(-1)!.trigger('click')
    await flushPromises()
    expect(mocks.patchAutomationRule).toHaveBeenCalledWith('catalog-1', expect.objectContaining({
      actions: [{ playbookVersionId: 'off-page-version', inputs: { preserve: true } }], rowVersion: 8,
    }))
    expect(mocks.listVersions).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('clamps the playbook page after shrink without altering selected versions', async () => {
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    await openNewRule(wrapper)
    playbookPicker(wrapper).vm.$emit('change', 'playbook-1')
    await flushPromises()
    versionPicker(wrapper).vm.$emit('update:modelValue', ['playbook-1-published'])
    mocks.listPlaybooks.mockImplementation((page, size) => Promise.resolve(catalogPage(playbooks.slice(0, 30), page, size)))
    playbookPager(wrapper).vm.$emit('change', 4)
    await flushPromises()
    expect(playbookPager(wrapper).props('page')).toBe(1)
    expect(playbookPager(wrapper).props('total')).toBe(30)
    expect(versionPicker(wrapper).props('modelValue')).toEqual(['playbook-1-published'])
    expect(mocks.listPlaybooks.mock.calls.map(call => call[0])).toEqual([0, 4, 1])
    wrapper.unmount()
  })

  it.each(['resolve', 'reject'] as const)('discards obsolete version %s and does not clear a newer loading state', async outcome => {
    const old = deferred<SoarVersion[]>()
    const current = deferred<SoarVersion[]>()
    mocks.listVersions.mockImplementationOnce(() => old.promise).mockImplementationOnce(() => current.promise)
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    await openNewRule(wrapper)
    playbookPicker(wrapper).vm.$emit('change', 'playbook-1')
    await flushPromises()
    const oldSignal = mocks.listVersions.mock.calls[0][1].signal as AbortSignal
    playbookPicker(wrapper).vm.$emit('change', 'playbook-2')
    await flushPromises()
    expect(oldSignal.aborted).toBe(true)
    if (outcome === 'resolve') old.resolve([publishedVersion('playbook-1')])
    else old.reject(new Error('obsolete version error'))
    await flushPromises()
    expect(versionPicker(wrapper).props('loading')).toBe(true)
    expect(optionValues(versionPicker(wrapper))).toEqual([])
    expect(wrapper.findAllComponents(ElDrawer)[0].text()).not.toContain('obsolete version error')
    current.resolve([publishedVersion('playbook-2')])
    await flushPromises()
    expect(optionValues(versionPicker(wrapper))).toEqual(['playbook-2-published'])
    wrapper.unmount()
  })

  it('aborts old playbook pages across close and repeated reopen without blocking the new request', async () => {
    const old = deferred<SoarPage<SoarPlaybook>>()
    const current = deferred<SoarPage<SoarPlaybook>>()
    mocks.listPlaybooks.mockImplementationOnce(() => old.promise).mockImplementationOnce(() => current.promise)
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    await openNewRule(wrapper)
    const oldSignal = mocks.listPlaybooks.mock.calls[0][2].signal as AbortSignal
    await closeRuleDrawer(wrapper)
    expect(oldSignal.aborted).toBe(true)
    await openNewRule(wrapper)
    expect(mocks.listPlaybooks).toHaveBeenCalledTimes(2)
    old.resolve(catalogPage(playbooks.slice(0, 1)))
    await flushPromises()
    expect(playbookPicker(wrapper).props('loading')).toBe(true)
    expect(optionValues(playbookPicker(wrapper))).toEqual([])
    current.resolve(catalogPage(playbooks))
    await flushPromises()
    expect(optionValues(playbookPicker(wrapper))).toHaveLength(25)
    const versions = deferred<SoarVersion[]>()
    mocks.listVersions.mockImplementationOnce(() => versions.promise)
    playbookPicker(wrapper).vm.$emit('change', 'playbook-1')
    await flushPromises()
    const versionSignal = mocks.listVersions.mock.calls[0][1].signal as AbortSignal
    await closeRuleDrawer(wrapper)
    expect(versionSignal.aborted).toBe(true)
    await openNewRule(wrapper)
    versions.resolve([publishedVersion('playbook-1')])
    await flushPromises()
    expect(optionValues(versionPicker(wrapper))).toEqual([])
    expect(versionPicker(wrapper).props('modelValue')).toEqual([])
    wrapper.unmount()
  })

  it.each(['permission', 'section'] as const)('closes the rule picker and aborts its reads when %s changes', async change => {
    const versions = deferred<SoarVersion[]>()
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    await openNewRule(wrapper)
    mocks.listVersions.mockImplementationOnce(() => versions.promise)
    playbookPicker(wrapper).vm.$emit('change', 'playbook-1')
    await flushPromises()
    const signal = mocks.listVersions.mock.calls[0][1].signal as AbortSignal
    if (change === 'permission') await wrapper.setProps({ canWrite: false })
    else await wrapper.setProps({ section: 'tasks' })
    expect(signal.aborted).toBe(true)
    versions.resolve([publishedVersion('playbook-1')])
    await flushPromises()
    if (change === 'permission') await wrapper.setProps({ canWrite: true })
    else await wrapper.setProps({ section: 'rules' })
    await flushPromises()
    expect(wrapper.findAllComponents(ElDrawer)[0].props('modelValue')).toBe(false)
    await openNewRule(wrapper)
    expect(optionValues(versionPicker(wrapper))).toEqual([])
    expect(mocks.listPlaybooks).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('aborts both editor catalogs on unmount and suppresses late errors', async () => {
    const versions = deferred<SoarVersion[]>()
    const books = deferred<SoarPage<SoarPlaybook>>()
    const wrapper = await mountPlane({ section: 'rules', hideTabs: true })
    await openNewRule(wrapper)
    mocks.listVersions.mockImplementationOnce(() => versions.promise)
    playbookPicker(wrapper).vm.$emit('change', 'playbook-1')
    mocks.listPlaybooks.mockImplementationOnce(() => books.promise)
    playbookPager(wrapper).vm.$emit('change', 1)
    await flushPromises()
    const versionSignal = mocks.listVersions.mock.calls[0][1].signal as AbortSignal
    const bookSignal = mocks.listPlaybooks.mock.calls[1][2].signal as AbortSignal
    wrapper.unmount()
    expect(versionSignal.aborted).toBe(true)
    expect(bookSignal.aborted).toBe(true)
    versions.reject(new Error('late version failure'))
    books.reject(new Error('late playbook failure'))
    await flushPromises()
  })
})
