import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import MetaView from '../src/views/MetaView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const api = vi.hoisted(() => ({
  listDataSourceTypes: vi.fn(), listCategories: vi.fn(), listFields: vi.fn(),
  createDataSourceType: vi.fn(), createCategory: vi.fn(), createField: vi.fn(),
  updateDataSourceType: vi.fn(), updateCategory: vi.fn(), updateField: vi.fn(),
  deleteDataSourceType: vi.fn(), deleteCategory: vi.fn(), deleteField: vi.fn(),
  SEVERITIES: ['HIGH', 'MEDIUM', 'LOW'],
}))
vi.mock('../src/api', () => api)
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => ({ confirmDanger: async () => true }) }))
let wrapper: VueWrapper | undefined
interface Actions {
  loadMeta: () => Promise<void>
  removeDsType: (id: string) => Promise<void>
  dataSourceTypes: Array<{ id: string }>
  logCategories: Array<{ id: string }>
  loadError: string
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/meta', component: MetaView }] })
  await router.push('/meta')
  wrapper = mount(RouterView, { global: {
    plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } },
  } })
  await flushPromises()
  return wrapper.findComponent(MetaView).vm as unknown as Actions
}
beforeEach(() => {
  api.listDataSourceTypes.mockResolvedValue([{ id: 'a', code: 'A', name: 'First', enabled: true }])
  api.listCategories.mockResolvedValue([{ id: 'c', code: 'C', name: 'Category', enabled: true }])
  api.listFields.mockResolvedValue([])
  api.deleteDataSourceType.mockResolvedValue({})
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.resetAllMocks() })

it('refreshes after deletion even when an older metadata read is pending', async () => {
  const view = await setup()
  const old = deferred<Array<{ id: string }>>()
  api.listDataSourceTypes.mockReturnValueOnce(old.promise)
  const refresh = view.loadMeta()
  const signal = api.listDataSourceTypes.mock.calls.at(-1)![0].signal as AbortSignal
  api.listDataSourceTypes.mockResolvedValueOnce([])
  await view.removeDsType('a')
  expect(signal.aborted).toBe(true)
  old.resolve([{ id: 'a' }]); await refresh
  expect(view.dataSourceTypes).toEqual([])
})

it('retains a failed registry while publishing successful registry refreshes', async () => {
  const view = await setup()
  api.listCategories.mockRejectedValueOnce(new Error('category unavailable'))
  api.listDataSourceTypes.mockResolvedValueOnce([{ id: 'b', code: 'B', name: 'Second' }])
  await view.loadMeta()
  expect(view.dataSourceTypes[0].id).toBe('b')
  expect(view.logCategories[0].id).toBe('c')
  expect(view.loadError).toContain('category unavailable')
})

it('cancels all pending registry reads when leaving the view', async () => {
  const view = await setup()
  const pending = deferred<never[]>()
  for (const request of [api.listDataSourceTypes, api.listCategories, api.listFields]) request.mockReturnValueOnce(pending.promise)
  const refresh = view.loadMeta()
  const signals = [api.listDataSourceTypes, api.listCategories, api.listFields].map(request => request.mock.calls.at(-1)![0].signal as AbortSignal)
  wrapper!.unmount(); wrapper = undefined
  expect(signals.every(signal => signal.aborted)).toBe(true)
  pending.resolve([]); await refresh
  const calls = api.listDataSourceTypes.mock.calls.length
  await view.loadMeta()
  expect(api.listDataSourceTypes).toHaveBeenCalledTimes(calls)
})
