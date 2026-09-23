import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RefsetView from '../src/views/RefsetView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const api = vi.hoisted(() => ({
  listRulePage: vi.fn(), listRefSets: vi.fn(), deleteRefEntry: vi.fn(), addRefEntry: vi.fn(),
  createRefSet: vi.fn(), deleteRefSet: vi.fn(),
}))
const confirmDanger = vi.hoisted(() => vi.fn())
vi.mock('../src/api', () => api)
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => ({ confirmDanger }) }))

interface EntryActions {
  loadRefSets: () => Promise<void>
  refSets: Array<{ id: string; entries: string[] }>
  loadError: string
  selectedId: string
  entrySelection: string[]
  importText: string
  openSet: (id: string) => Promise<void>
  openImport: () => void
  removeEntry: (value: string) => Promise<void>
  removeSelected: () => Promise<void>
  importEntries: () => Promise<void>
  removeRefSet: (id: string) => Promise<void>
}

let wrapper: VueWrapper | undefined
async function mountSets() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/refsets', component: RefsetView }] })
  await router.push('/refsets')
  wrapper = mount(RouterView, { global: {
    plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } },
  } })
  await flushPromises()
  const actions = wrapper.findComponent(RefsetView).vm as unknown as EntryActions
  await actions.openSet('a')
  await flushPromises()
  return actions
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

beforeEach(() => {
  api.listRefSets.mockResolvedValue([
    { id: 'a', name: 'first', entries: ['one', 'two'] },
    { id: 'b', name: 'second', entries: ['one', 'two', 'keep'] },
  ])
  api.listRulePage.mockResolvedValue({ items: [], total: 0, page: 1, size: 20, totalPages: 0 })
  api.deleteRefEntry.mockResolvedValue({})
  api.addRefEntry.mockResolvedValue({})
  api.deleteRefSet.mockResolvedValue({})
  confirmDanger.mockResolvedValue(true)
})
afterEach(() => { wrapper?.unmount(); vi.resetAllMocks() })

describe('reference set operation targets', () => {
  it('does not restore deleted entries from a late list response', async () => {
    const actions = await mountSets()
    const old = deferred<Array<{ id: string; name: string; entries: string[] }>>()
    api.listRefSets.mockReturnValueOnce(old.promise)
    const refresh = actions.loadRefSets()
    const signal = api.listRefSets.mock.calls.at(-1)![0].signal as AbortSignal
    api.listRefSets.mockResolvedValueOnce([{ id: 'a', name: 'first', entries: ['two'] }])
    await actions.removeEntry('one')
    expect(signal.aborted).toBe(true)
    old.resolve([{ id: 'a', name: 'first', entries: ['one', 'two'] }])
    await refresh
    expect(actions.refSets[0].entries).toEqual(['two'])
    api.listRefSets.mockRejectedValueOnce(new Error('refresh unavailable'))
    await actions.loadRefSets()
    expect(actions.refSets[0].entries).toEqual(['two'])
    expect(actions.loadError).toBe('refresh unavailable')
  })

  it('cancels list reads and skips mutation follow-up reads after unmount', async () => {
    const actions = await mountSets()
    const read = deferred<never[]>()
    const write = deferred<unknown>()
    api.listRefSets.mockReturnValueOnce(read.promise)
    const refresh = actions.loadRefSets()
    const signal = api.listRefSets.mock.calls.at(-1)![0].signal as AbortSignal
    api.deleteRefEntry.mockReturnValueOnce(write.promise)
    const removed = actions.removeEntry('one')
    await flushPromises()
    const calls = api.listRefSets.mock.calls.length
    wrapper!.unmount(); wrapper = undefined
    expect(signal.aborted).toBe(true)
    read.resolve([]); write.resolve({})
    await Promise.all([refresh, removed])
    expect(api.listRefSets).toHaveBeenCalledTimes(calls)
  })

  it('does not confuse ingestion reference sets with rule watchlists', async () => {
    await mountSets()
    expect(api.listRulePage).not.toHaveBeenCalled()
    expect(wrapper!.text()).toContain('接入事件富化')
    const remove = wrapper!.findAll('button').find(button => button.classes().includes('is-plain') && button.text() === '删除')
    expect(remove).toBeDefined()
    expect(remove!.attributes('disabled')).toBeUndefined()
  })
  it('keeps a single deletion on the set shown when confirmation opened', async () => {
    const actions = await mountSets()
    const confirmation = deferred<boolean>()
    confirmDanger.mockReturnValueOnce(confirmation.promise)
    const removed = actions.removeEntry('one')
    actions.selectedId = 'b'
    actions.entrySelection = ['one', 'keep']
    confirmation.resolve(true)
    await removed
    expect(api.deleteRefEntry).toHaveBeenCalledWith('a', 'one')
    expect(actions.entrySelection).toEqual(['one', 'keep'])
  })

  it('pins every batch deletion and prevents switching or closing while it runs', async () => {
    const actions = await mountSets()
    actions.entrySelection = ['one', 'two']
    const first = deferred<unknown>()
    api.deleteRefEntry.mockReturnValueOnce(first.promise).mockRejectedValueOnce(new Error('rejected'))
    const removed = actions.removeSelected()
    await flushPromises()
    await actions.openSet('b')
    expect(actions.selectedId).toBe('a')
    const closed = vi.fn()
    wrapper!.findComponent(ElDrawer).props('beforeClose')!(closed)
    expect(closed).not.toHaveBeenCalled()

    // A route/state change must still be unable to redirect later requests.
    actions.selectedId = 'b'
    actions.entrySelection = ['keep']
    first.resolve({})
    await removed
    expect(api.deleteRefEntry.mock.calls).toEqual([['a', 'one'], ['a', 'two']])
    expect(actions.entrySelection).toEqual(['keep'])
    wrapper!.findComponent(ElDrawer).props('beforeClose')!(closed)
    expect(closed).toHaveBeenCalledTimes(1)
  })

  it('pins every imported entry to the original set', async () => {
    const actions = await mountSets()
    actions.openImport()
    actions.importText = 'one\ntwo'
    const first = deferred<unknown>()
    api.addRefEntry.mockReturnValueOnce(first.promise)
    const imported = actions.importEntries()
    await flushPromises()
    actions.selectedId = 'b'
    first.resolve({})
    await imported
    expect(api.addRefEntry.mock.calls).toEqual([['a', 'one'], ['a', 'two']])
  })

  it('does not close another set when an earlier set deletion completes', async () => {
    const actions = await mountSets()
    const confirmation = deferred<boolean>()
    confirmDanger.mockReturnValueOnce(confirmation.promise)
    const removed = actions.removeRefSet('a')
    actions.selectedId = 'b'
    confirmation.resolve(true)
    await removed
    expect(api.deleteRefSet).toHaveBeenCalledWith('a')
    expect(actions.selectedId).toBe('b')
  })
})
