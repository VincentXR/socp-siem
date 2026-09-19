import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SoarView from '../src/views/SoarView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const mocks = vi.hoisted(() => ({
  listPlaybooks: vi.fn(),
  listRuns: vi.fn(),
  listTemplates: vi.fn(),
  listApprovals: vi.fn(),
  approve: vi.fn(),
  listManualTasksPage: vi.fn(),
}))
vi.mock('../src/api', async importOriginal => ({ ...await importOriginal<object>(), ...mocks }))

const emptyPage = { page: 0, size: 100, total: 0, totalPages: 0, items: [] }

async function mountSoar(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/soar', name: 'soar', component: SoarView },
      { path: '/soar/playbooks/new', name: 'playbook-new', component: SoarView, meta: { editor: true } },
      { path: '/soar/playbooks/:playbookId/edit', name: 'playbook-edit', component: SoarView, meta: { editor: true } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(SoarView, {
    attachTo: document.body,
    global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('soar approvals lifecycle', () => {
  beforeEach(() => {
    mocks.listPlaybooks.mockResolvedValue(emptyPage)
    mocks.listRuns.mockResolvedValue(emptyPage)
    mocks.listTemplates.mockResolvedValue([])
    mocks.listManualTasksPage.mockResolvedValue(emptyPage)
    mocks.approve.mockResolvedValue({ id: 'approval-1', status: 'APPROVED' })
    mocks.listApprovals.mockResolvedValue([
      { id: 'approval-1', runId: 'run-1', actionRef: 'slack.post', reason: 'high-risk send', status: 'PENDING', requestedBy: 'analyst' },
    ])
  })

  afterEach(() => {
    document.body.textContent = ''
  })

  it('refetches the approval list itself after a decision is submitted', async () => {
    const { wrapper } = await mountSoar('/soar?tab=approvals')
    expect(mocks.listApprovals).toHaveBeenCalledTimes(1)
    const approveButton = wrapper.findAll('button').find(button => button.text() === '通过')
    expect(approveButton).toBeTruthy()
    await approveButton!.trigger('click')
    await flushPromises()
    const reason = document.querySelector('.soar-approval-dialog-body textarea') as HTMLTextAreaElement | null
    expect(reason).toBeTruthy()
    reason!.value = '已核对目标与影响'
    reason!.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    mocks.listApprovals.mockResolvedValueOnce([])
    const submit = [...document.querySelectorAll('.el-dialog__footer button') as NodeListOf<HTMLButtonElement>]
      .find(button => button.textContent?.includes('通过'))
    expect(submit).toBeTruthy()
    expect(submit!.disabled).toBe(false)
    submit!.click()
    await flushPromises()
    expect(mocks.approve).toHaveBeenCalledWith('approval-1', '已核对目标与影响')
    // The list refresh must hit the approvals endpoint, not the playbook catalog.
    expect(mocks.listApprovals).toHaveBeenCalledTimes(2)
    expect(mocks.listPlaybooks).toHaveBeenCalledTimes(1)
    await flushPromises()
    expect(wrapper.findAll('.soar-approval-table tbody tr')).toHaveLength(0)
    wrapper.unmount()
  })

  it('keeps tab and approval-filter state in the shareable route query', async () => {
    mocks.listApprovals.mockResolvedValue([
      { id: 'approval-1', runId: 'run-1', actionRef: 'slack.post', reason: 'x', status: 'PENDING', requestedBy: 'analyst' },
      { id: 'approval-2', runId: 'run-2', actionRef: 'slack.post', reason: 'x', status: 'APPROVED', requestedBy: 'analyst' },
    ])
    const { wrapper, router } = await mountSoar('/soar?tab=approvals&filter=ALL')
    expect(wrapper.findAll('.soar-approval-table tbody tr')).toHaveLength(2)
    const filterButtons = wrapper.findAll('.soar-header-filter button')
    expect(filterButtons).toHaveLength(2)
    await filterButtons[0].trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.soar-approval-table tbody tr')).toHaveLength(1)
    // The default filter drops out of the URL while the deep-linked tab stays.
    expect(router.currentRoute.value.query).toEqual({ tab: 'approvals' })
    await filterButtons[1].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ tab: 'approvals', filter: 'ALL' })
    wrapper.unmount()
  })
})
