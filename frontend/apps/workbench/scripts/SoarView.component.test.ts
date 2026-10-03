import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SoarView from '../src/views/SoarView.vue'
import SoarCatalogPager from '../src/components/soar/SoarCatalogPager.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const mocks = vi.hoisted(() => ({
  listPlaybooks: vi.fn(),
  listRuns: vi.fn(), getRun: vi.fn(), listNodes: vi.fn(), listEvents: vi.fn(), listArtifacts: vi.fn(),
  listTemplates: vi.fn(),
  listApprovals: vi.fn(),
  approve: vi.fn(),
  listManualTasksPage: vi.fn(),
}))
vi.mock('../src/api', async importOriginal => ({ ...await importOriginal<object>(), ...mocks }))

const emptyPage = { page: 0, size: 100, total: 0, totalPages: 0, items: [] as Array<Record<string, unknown>> }

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
    Object.values(mocks).forEach(mock => mock.mockReset())
    mocks.listPlaybooks.mockResolvedValue(emptyPage)
    mocks.listRuns.mockResolvedValue(emptyPage)
    mocks.getRun.mockResolvedValue({ runId: 'run-1', status: 'SUCCEEDED', subject: { type: 'alert', id: 'alarm-1' }, playbookVersion: 2, definitionHash: 'hash-v2' })
    mocks.listNodes.mockResolvedValue([])
    mocks.listEvents.mockResolvedValue(emptyPage)
    mocks.listArtifacts.mockResolvedValue([])
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

  it('loads immutable target, parameters, policy and origin before allowing approval', async () => {
    let finish!: (value: unknown[]) => void
    mocks.listNodes.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    mocks.listApprovals.mockResolvedValue([{ id: 'approval-1', runId: 'run-1', nodeRunId: 'node-1', actionRef: 'firewall.block', status: 'PENDING', requestedBy: 'analyst', targetSnapshot: { ip: '192.0.2.1' }, inputHash: 'exact-hash', approvalPolicy: { mode: 'two-person' } }])
    const { wrapper } = await mountSoar('/soar?tab=approvals&approvalId=approval-1')
    const view = wrapper.vm as unknown as { approvalModal: { reason: string }; submitApprovalDecision: () => Promise<void> }
    view.approvalModal.reason = 'Reviewed'
    await view.submitApprovalDecision()
    expect(mocks.approve).not.toHaveBeenCalled()
    finish([{ id: 'node-1', input: { target: '192.0.2.1', duration: 60 } }]); await flushPromises()
    expect(document.querySelector('.soar-approval-dialog-body')?.textContent).toContain('192.0.2.1')
    expect(document.querySelector('.soar-approval-dialog-body')?.textContent).toContain('duration')
    expect(document.querySelector('.soar-approval-dialog-body')?.textContent).toContain('two-person')
    expect(document.querySelector('.soar-approval-dialog-body')?.textContent).toContain('exact-hash')
    await view.submitApprovalDecision()
    expect(mocks.approve).toHaveBeenCalledWith('approval-1', 'Reviewed')
    wrapper.unmount()
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

  it('starts a new refresh immediately and ignores a late response from the aborted request', async () => {
    let oldResult: (value: typeof emptyPage) => void = () => {}
    mocks.listPlaybooks.mockImplementationOnce(() => new Promise(resolve => { oldResult = resolve }))
    const { wrapper } = await mountSoar('/soar')
    const firstSignal = mocks.listPlaybooks.mock.calls[0][2].signal as AbortSignal
    mocks.listPlaybooks.mockResolvedValueOnce({ ...emptyPage, items: [{ id: 'newest', name: 'Latest catalog', status: 'ACTIVE' }] })
    const refresh = wrapper.findAllComponents({ name: 'ElButton' }).find(button => button.text() === '刷新')!
    refresh.vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    expect(firstSignal.aborted).toBe(true)
    expect(mocks.listPlaybooks).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('Latest catalog')
    oldResult({ ...emptyPage, items: [{ id: 'old', name: 'Stale catalog', status: 'ACTIVE' }] })
    await flushPromises()
    expect(wrapper.text()).toContain('Latest catalog')
    expect(wrapper.text()).not.toContain('Stale catalog')
    wrapper.unmount()
  })

  it.each(['runId=run-1', 'alarmId=alarm-1', 'caseId=case-1'])('preserves an explicit catalog tab across route history with %s context', async context => {
    const { wrapper, router } = await mountSoar(`/soar?${context}`)
    try {
      expect(wrapper.get('#tab-runs').attributes('aria-selected')).toBe('true')
      await wrapper.get('#tab-playbooks').trigger('click')
      await flushPromises()
      const catalogQuery = { ...Object.fromEntries(new URLSearchParams(context)), tab: 'playbooks' }
      expect(router.currentRoute.value.query).toEqual(catalogQuery)
      expect(wrapper.get('#tab-playbooks').attributes('aria-selected')).toBe('true')
      await router.push(`/soar?${context}&tab=runs`)
      await flushPromises()
      expect(wrapper.get('#tab-runs').attributes('aria-selected')).toBe('true')
      router.back()
      await flushPromises()
      expect(router.currentRoute.value.query).toEqual(catalogQuery)
      expect(wrapper.get('#tab-playbooks').attributes('aria-selected')).toBe('true')
      router.forward()
      await flushPromises()
      expect(wrapper.get('#tab-runs').attributes('aria-selected')).toBe('true')
    } finally { wrapper.unmount() }
  })

  it('navigates beyond 100 playbooks and uses the authoritative retained-run summary', async () => {
    const catalog = Array.from({ length: 137 }, (_, index) => ({
      id: `pb-${index}`, name: `Playbook ${index}`, status: 'ACTIVE', tags: [],
      latestRun: index === 125 ? { runId: 'old-run', status: 'SUCCEEDED', createdAt: '2025-01-01T00:00:00Z' } : null,
    }))
    mocks.listPlaybooks.mockImplementation((page: number, size: number) => Promise.resolve({
      page, size, total: catalog.length, totalPages: Math.ceil(catalog.length / size), items: catalog.slice(page * size, (page + 1) * size),
    }))
    mocks.listRuns.mockResolvedValue({ ...emptyPage, total: 1, totalPages: 1, items: [{ runId: 'run-1', status: 'SUCCEEDED' }] })
    const { wrapper, router } = await mountSoar('/soar')
    expect(mocks.listPlaybooks.mock.calls[0].slice(0, 2)).toEqual([0, 25])
    expect(wrapper.findAll('.soar-playbook-name')).toHaveLength(25)
    wrapper.findComponent(SoarCatalogPager).vm.$emit('change', 5)
    await flushPromises()
    expect(mocks.listPlaybooks.mock.calls.at(-1)!.slice(0, 2)).toEqual([5, 25])
    expect(wrapper.text()).toContain('Playbook 136')
    expect(wrapper.text()).toContain('2025-01-01T00:00:00Z')
    expect(wrapper.findAll('.soar-playbook-name')).toHaveLength(12)
    expect(mocks.listRuns).not.toHaveBeenCalled()
    await wrapper.get('#tab-runs').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.runId).toBe('run-1')
    expect(wrapper.find('.soar-run-summary').text()).toContain('run-1')
    await wrapper.get('#tab-playbooks').trigger('click')
    await flushPromises()
    expect(wrapper.get('#tab-playbooks').attributes('aria-selected')).toBe('true')
    expect(router.currentRoute.value.query).toEqual({ tab: 'playbooks', runId: 'run-1' })
    expect(wrapper.get('#pane-playbooks').isVisible()).toBe(true)
    expect(wrapper.findAll('.soar-playbook-name')[0].text()).toBe('Playbook 125')
    expect(wrapper.findComponent(SoarCatalogPager).props('page')).toBe(5)
    wrapper.findComponent(SoarCatalogPager).vm.$emit('change', 0)
    await flushPromises()
    expect(wrapper.findAll('.soar-playbook-name')[0].text()).toBe('Playbook 0')
    wrapper.unmount()
  })

  it('does not present a failed page read as rows from the preceding page', async () => {
    mocks.listPlaybooks.mockResolvedValueOnce({ ...emptyPage, total: 101, totalPages: 5,
      items: [{ id: 'page-zero', name: 'Previous page row', status: 'ACTIVE', latestRun: null }],
    })
    const { wrapper } = await mountSoar('/soar')
    expect(wrapper.text()).toContain('Previous page row')
    mocks.listPlaybooks.mockRejectedValueOnce(new Error('Page unavailable'))
    wrapper.findComponent(SoarCatalogPager).vm.$emit('change', 1)
    await flushPromises()
    expect(wrapper.text()).toContain('Page unavailable')
    expect(wrapper.text()).not.toContain('Previous page row')
    expect(wrapper.findComponent(SoarCatalogPager).props('page')).toBe(1)
    wrapper.unmount()
  })

  it('corrects an emptied last page after refresh without looping or showing stale rows', async () => {
    mocks.listPlaybooks.mockImplementation((page: number, size: number) => Promise.resolve({
      page, size, total: 101, totalPages: 5, items: [{ id: `pb-${page}`, name: `Page ${page}`, status: 'ACTIVE', latestRun: null }],
    }))
    const { wrapper } = await mountSoar('/soar')
    wrapper.findComponent(SoarCatalogPager).vm.$emit('change', 4)
    await flushPromises()
    mocks.listPlaybooks.mockImplementation((page: number, size: number) => Promise.resolve({
      page, size, total: 1, totalPages: 1, items: page === 0 ? [{ id: 'remaining', name: 'Remaining', status: 'ACTIVE', latestRun: null }] : [],
    }))
    const refresh = wrapper.findAllComponents({ name: 'ElButton' }).find(button => button.text() === '刷新')!
    refresh.vm.$emit('click', new MouseEvent('click'))
    await flushPromises()
    expect(mocks.listPlaybooks.mock.calls.slice(-2).map(call => call[0])).toEqual([4, 0])
    expect(wrapper.findComponent(SoarCatalogPager).props('page')).toBe(0)
    expect(wrapper.text()).toContain('Remaining')
    expect(wrapper.text()).not.toContain('Page 4')
    wrapper.unmount()
  })

  it('aborts pending reads on unmount without running a queued reload', async () => {
    let settle: (value: typeof emptyPage) => void = () => {}
    mocks.listPlaybooks.mockImplementationOnce(() => new Promise(resolve => { settle = resolve }))
    const { wrapper } = await mountSoar('/soar')
    const signal = mocks.listPlaybooks.mock.calls[0][2].signal as AbortSignal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
    settle(emptyPage)
    await flushPromises()
    expect(mocks.listPlaybooks).toHaveBeenCalledTimes(1)
  })
})
