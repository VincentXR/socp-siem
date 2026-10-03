import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AlarmsView from '../src/views/AlarmsView.vue'
import type { Alarm } from '../src/api'

const mocks = vi.hoisted(() => ({ confirm: vi.fn(), batchUpdateAlarmDisposition: vi.fn(), listRuleOptions: vi.fn() }))
vi.mock('element-plus/es/components/message-box/index.mjs', () => ({ default: { confirm: mocks.confirm } }))
vi.mock('../src/api/alarms', async original => ({ ...await original<object>(), batchUpdateAlarmDisposition: mocks.batchUpdateAlarmDisposition }))
vi.mock('../src/api', async original => ({ ...await original<object>(), listRuleOptions: mocks.listRuleOptions }))

function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
const alarm = (id: string) => ({ id, severity: 'HIGH', occurredAt: '2026-10-01T00:00:00Z' }) as Alarm
type View = { handleSelectionChange: (rows: Alarm[]) => void; handleBatchUpdate: () => Promise<void>
  batchOperation: 'assign' | 'status'; batchAssignee: string; batchStatus: string; batchReason: string; selectedAlarms: Alarm[] }

async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  const wrapper = mount(AlarmsView, { props: {
    filteredAlarms: [], alarmPageData: { total: 0 }, alarmPageSize: 20, loading: false, error: '',
    onSearch: vi.fn(), loadPage: vi.fn(), onSortChange: vi.fn(), exportCsv: vi.fn(), exportJson: vi.fn(),
    goCase: vi.fn(), goSearch: vi.fn(), canWrite: true, assigneeOptions: ['alice', 'bob'],
  }, global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, view: wrapper.vm as unknown as View }
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.listRuleOptions.mockResolvedValue({ items: [], total: 0 })
  mocks.batchUpdateAlarmDisposition.mockResolvedValue({})
})

describe('alarm batch confirmation', () => {
  it('confirms and writes the same selected targets and assignee, preserving a newer selection', async () => {
    const { wrapper, view } = await setup()
    view.handleSelectionChange([alarm('A')]); view.batchAssignee = 'alice'
    const confirmation = deferred<string>()
    mocks.confirm.mockReturnValueOnce(confirmation.promise)
    const operation = view.handleBatchUpdate()
    await view.handleBatchUpdate()
    expect(mocks.confirm).toHaveBeenCalledTimes(1)
    expect(mocks.confirm.mock.calls[0]?.[0]).toContain('alice')
    expect(mocks.batchUpdateAlarmDisposition).not.toHaveBeenCalled()
    view.handleSelectionChange([alarm('B')]); view.batchAssignee = 'bob'
    confirmation.resolve('confirm'); await operation
    expect(mocks.batchUpdateAlarmDisposition).toHaveBeenCalledExactlyOnceWith(['A'], { assignee: 'alice', reason: undefined })
    expect(view.selectedAlarms.map(item => item.id)).toEqual(['B'])
    expect(view.batchAssignee).toBe('bob')
    wrapper.unmount()
  })

  it('does not write after cancellation, permission loss or unmount', async () => {
    const { wrapper, view } = await setup()
    view.handleSelectionChange([alarm('A')]); view.batchOperation = 'status'; view.batchStatus = 'RESOLVED'; view.batchReason = 'Verified evidence'
    mocks.confirm.mockRejectedValueOnce(new Error('cancel'))
    await view.handleBatchUpdate()
    const confirmation = deferred<string>()
    mocks.confirm.mockReturnValueOnce(confirmation.promise)
    const pending = view.handleBatchUpdate()
    await wrapper.setProps({ canWrite: false })
    confirmation.resolve('confirm'); await pending
    expect(mocks.batchUpdateAlarmDisposition).not.toHaveBeenCalled()
    await wrapper.setProps({ canWrite: true })
    const leaving = deferred<string>()
    mocks.confirm.mockReturnValueOnce(leaving.promise)
    const late = view.handleBatchUpdate()
    wrapper.unmount(); leaving.resolve('confirm'); await late
    expect(mocks.batchUpdateAlarmDisposition).not.toHaveBeenCalled()
  })
})


it('treats ACTIVE as a list filter rather than a writable disposition', async () => {
  const { wrapper, view } = await setup()
  const options = wrapper.vm as unknown as { DISP_STATUSES: string[]; FILTER_STATUSES: string[] }
  expect(options.FILTER_STATUSES).toContain('ACTIVE')
  expect(options.DISP_STATUSES).not.toContain('ACTIVE')
  view.handleSelectionChange([alarm('A')])
  view.batchOperation = 'status'
  view.batchStatus = 'ACTIVE'
  await view.handleBatchUpdate()
  expect(mocks.confirm).not.toHaveBeenCalled()
  expect(mocks.batchUpdateAlarmDisposition).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('clears the previous ownership mode when choosing my queue or a new owner filter', async () => {
  const { wrapper } = await setup()
  await wrapper.setProps({ currentUser: 'alice' })
  const view = wrapper.vm as unknown as { owner: string; assignee: string; status: string; myQueue: () => void; searchOwner: () => void; searchAssignee: () => void }
  view.owner = 'unassigned'
  view.myQueue()
  expect(view.owner).toBe('')
  expect(view.assignee).toBe('alice')
  expect(view.status).toBe('ACTIVE')
  view.owner = 'mine'
  view.searchOwner()
  expect(view.assignee).toBe('')
  view.assignee = 'bob'
  view.searchAssignee()
  expect(view.owner).toBe('')
  wrapper.unmount()
})
