import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import SoarCatalogPager from '../src/components/soar/SoarCatalogPager.vue'
import SoarRunInspector from '../src/components/soar/SoarRunInspector.vue'

const mocks = vi.hoisted(() => ({
  listPlaybooks: vi.fn(), listVersions: vi.fn(), queueRun: vi.fn(),
  listRuns: vi.fn(), getRun: vi.fn(), listNodes: vi.fn(), listEvents: vi.fn(), listArtifacts: vi.fn(),
  confirmDanger: vi.fn(), promptInput: vi.fn(), rerunRun: vi.fn(), retryRun: vi.fn(), cancelWorkflowRun: vi.fn(),
}))
vi.mock('../src/api', async importOriginal => ({ ...await importOriginal<object>(), ...mocks }))
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => ({ confirmDanger: mocks.confirmDanger, promptInput: mocks.promptInput }) }))

const pageOf = <T>(items: T[], page = 0, total = items.length) => ({ items, page, size: 25, total, totalPages: Math.ceil(total / 25) })
const playbook = (id: number) => ({ id: `pb-${id}`, name: `Playbook ${id}`, status: 'ACTIVE', latestPublishedVersion: 1, tags: [] })
const version = (id: string) => ({ id: `${id}-v1`, playbookId: id, version: 1, status: 'PUBLISHED' })
const fixture = (runId: string) => ({ runId, playbookId: 'pb-1', playbookVersion: 1, status: 'FAILED' })

beforeEach(() => {
  Object.values(mocks).forEach(mock => mock.mockReset())
  mocks.listRuns.mockResolvedValue(pageOf([fixture('run-1'), fixture('run-2')]))
  mocks.listPlaybooks.mockResolvedValue(pageOf([playbook(1), playbook(2)]))
  mocks.listVersions.mockImplementation(async id => [version(id)])
  mocks.queueRun.mockResolvedValue(fixture('queued-run'))
  mocks.getRun.mockImplementation(async id => fixture(id))
  mocks.listNodes.mockResolvedValue([])
  mocks.listEvents.mockResolvedValue({ items: [] })
  mocks.listArtifacts.mockResolvedValue([])
  mocks.confirmDanger.mockResolvedValue(true)
  mocks.rerunRun.mockResolvedValue(fixture('retry-1'))
})
afterEach(() => { vi.useRealTimers(); document.body.textContent = '' })

describe('SOAR run control identities and lifecycle', () => {
  it('lets a six-second fallback projection finish instead of cancelling it at every five-second poll', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const wrapper = mount(SoarRunInspector)
    await flushPromises()
    expect(wrapper.find('.soar-stream-state.polling').exists()).toBe(true)
    mocks.getRun.mockImplementation(id => new Promise(resolve => {
      setTimeout(() => resolve({ ...fixture(id), status: 'SUCCEEDED' }), 6000)
    }))
    const firstReadCount = mocks.getRun.mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    const signal = mocks.getRun.mock.calls.at(-1)![1].signal as AbortSignal
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(signal.aborted).toBe(false)
    expect(mocks.getRun).toHaveBeenCalledTimes(firstReadCount + 1)
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.find('.soar-run-summary .el-tag').text()).toBe('成功')
    expect(signal.aborted).toBe(false)
    // The missed tick is coalesced into one trailing refresh for newer evidence.
    expect(mocks.getRun).toHaveBeenCalledTimes(firstReadCount + 2)
    wrapper.unmount()
  })

  it('still cancels an in-flight fallback projection on an explicit refresh', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const wrapper = mount(SoarRunInspector)
    await flushPromises()
    mocks.getRun.mockImplementation(id => new Promise(resolve => {
      setTimeout(() => resolve(fixture(id)), 6000)
    }))
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    const signal = mocks.getRun.mock.calls.at(-1)![1].signal as AbortSignal
    wrapper.findAll('button').find(button => button.text() === '刷新')!.element.click()
    await flushPromises()
    expect(signal.aborted).toBe(true)
    wrapper.unmount()
  })

  it('does not retarget a pending confirmation when the selected run changes', async () => {
    let confirm: (result: boolean) => void = () => {}
    mocks.confirmDanger.mockImplementationOnce(() => new Promise(resolve => { confirm = resolve }))
    const wrapper = mount(SoarRunInspector, { attachTo: document.body })
    await flushPromises()
    const rerun = wrapper.findAll('button').find(button => button.text() === '重新运行')!
    expect(rerun).toBeTruthy()
    rerun.element.click()
    rerun.element.click()
    await flushPromises()
    expect(mocks.confirmDanger).toHaveBeenCalledTimes(1)
    const selection = wrapper.find<HTMLSelectElement>('.soar-run-select select')
    expect(selection.element.disabled).toBe(true)
    // Bypass the UI lock to simulate an external selection change; the handler
    // must still reject a stale confirmation rather than relying on disabled.
    selection.element.disabled = false
    await selection.setValue('run-2')
    await flushPromises()
    confirm(true)
    await flushPromises()
    expect(mocks.rerunRun).not.toHaveBeenCalled()
    expect(wrapper.find('.soar-run-summary').text()).toContain('run-2')
    wrapper.unmount()
  })

  it('aborts pending run reads when hidden and cannot restart a stream from a late response', async () => {
    let finish: (result: ReturnType<typeof fixture>) => void = () => {}
    mocks.getRun.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(SoarRunInspector)
    await flushPromises()
    const signal = mocks.getRun.mock.calls[0][1]?.signal as AbortSignal | undefined
    await wrapper.setProps({ active: false })
    expect(signal?.aborted).toBe(true)
    finish(fixture('run-1'))
    await flushPromises()
    expect(wrapper.find('.soar-stream-state.live').exists()).toBe(false)
    expect(wrapper.find('.soar-run-summary').exists()).toBe(false)
    wrapper.unmount()
  })

  it('rechecks execute permission after confirmation', async () => {
    let confirm: (result: boolean) => void = () => {}
    mocks.confirmDanger.mockImplementationOnce(() => new Promise(resolve => { confirm = resolve }))
    const wrapper = mount(SoarRunInspector)
    await flushPromises()
    wrapper.findAll('button').find(button => button.text() === '重新运行')!.element.click()
    await wrapper.setProps({ canExecute: false })
    confirm(true)
    await flushPromises()
    expect(mocks.rerunRun).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})


async function openQueue(wrapper: ReturnType<typeof mount>) {
  await wrapper.findAll('button').find(button => button.text() === '排队执行')!.trigger('click')
  await flushPromises()
}

function queuePager(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAllComponents(SoarCatalogPager).find(item => item.classes().includes('soar-queue-playbooks-pager'))!
}

function versionSelect(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAllComponents(ElSelect).find(item => !item.classes().includes('soar-queue-playbook'))!
}

describe('SOAR bounded run and published-version catalogs', () => {
  it('reaches run 101 with bounded pages and retains the selection across page changes and refresh', async () => {
    const allRuns = Array.from({ length: 125 }, (_, index) => fixture(`run-${index + 1}`))
    mocks.listRuns.mockImplementation(async (page, size) => pageOf(allRuns.slice(page * size, (page + 1) * size), page, allRuns.length))
    const wrapper = mount(SoarRunInspector)
    await flushPromises()
    const pager = wrapper.findComponent(SoarCatalogPager)
    expect(pager.props('total')).toBe(125)
    for (let page = 1; page <= 4; page++) {
      await pager.findAll('button')[1].trigger('click')
      await flushPromises()
      expect(mocks.listRuns).toHaveBeenLastCalledWith(page, 25, expect.objectContaining({ signal: expect.any(AbortSignal) }), expect.any(Object))
    }
    const select = wrapper.find<HTMLSelectElement>('.soar-run-select select')
    expect(select.findAll('option')).toHaveLength(27) // 25 rows, selected run 1, and the empty choice
    expect(select.element.value).toBe('run-1')
    await select.setValue('run-101')
    await flushPromises()
    await pager.findAll('button')[0].trigger('click')
    await flushPromises()
    expect(select.element.value).toBe('run-101')
    expect(wrapper.find('.soar-run-summary').text()).toContain('run-101')
    await wrapper.findAll('button').find(button => button.text() === '刷新')!.trigger('click')
    await flushPromises()
    expect(mocks.listRuns).toHaveBeenLastCalledWith(3, 25, expect.any(Object), expect.any(Object))
    expect(select.element.value).toBe('run-101')
    wrapper.unmount()
  })

  it('discards a stale run page and corrects an empty tail page after the catalog shrinks', async () => {
    mocks.listRuns.mockResolvedValue(pageOf([fixture('run-1')], 0, 101))
    const wrapper = mount(SoarRunInspector)
    await flushPromises()
    const pager = wrapper.findComponent(SoarCatalogPager)
    let finish: (value: ReturnType<typeof pageOf<ReturnType<typeof fixture>>>) => void = () => {}
    mocks.listRuns.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    pager.vm.$emit('change', 4)
    await flushPromises()
    const signal = mocks.listRuns.mock.calls.at(-1)![2].signal as AbortSignal
    pager.vm.$emit('change', 0)
    await flushPromises()
    expect(signal.aborted).toBe(true)
    finish(pageOf([fixture('stale-run')], 4, 101))
    await flushPromises()
    expect(wrapper.find('.soar-run-select').text()).not.toContain('stale-run')
    expect(pager.props('page')).toBe(0)
    mocks.listRuns.mockResolvedValueOnce(pageOf([], 4, 26)).mockResolvedValueOnce(pageOf([fixture('run-26')], 1, 26))
    pager.vm.$emit('change', 4)
    await flushPromises()
    expect(mocks.listRuns).toHaveBeenLastCalledWith(1, 25, expect.any(Object), expect.any(Object))
    expect(pager.props('page')).toBe(1)
    expect(pager.props('total')).toBe(26)
    expect(wrapper.find<HTMLSelectElement>('.soar-run-select select').element.value).toBe('run-1')
    wrapper.unmount()
  })

  it('recovers a failed page without dropping the inspected run or leaving a stale catalog error', async () => {
    mocks.listRuns.mockResolvedValue(pageOf([fixture('run-1')], 0, 51))
    const wrapper = mount(SoarRunInspector)
    await flushPromises()
    const pager = wrapper.findComponent(SoarCatalogPager)
    mocks.listRuns.mockRejectedValueOnce(new Error('Run page unavailable'))
    await pager.findAll('button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Run page unavailable')
    expect(wrapper.find('.soar-run-summary').text()).toContain('run-1')
    mocks.listRuns.mockResolvedValue(pageOf([fixture('run-51')], 2, 51))
    await pager.findAll('button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('Run page unavailable')
    expect(wrapper.find<HTMLSelectElement>('.soar-run-select select').element.value).toBe('run-1')
    await wrapper.find('.soar-run-select select').setValue('')
    await flushPromises()
    expect(wrapper.find('.soar-run-summary').exists()).toBe(false)
    wrapper.unmount()
  })

  it('reaches playbook 101 without a version-request fan-out and queues its selected immutable version', async () => {
    const catalog = Array.from({ length: 125 }, (_, index) => playbook(index + 1))
    mocks.listPlaybooks.mockImplementation(async (page, size) => pageOf(catalog.slice(page * size, (page + 1) * size), page, catalog.length))
    const wrapper = mount(SoarRunInspector, { attachTo: document.body })
    await flushPromises()
    await openQueue(wrapper)
    expect(mocks.listVersions).toHaveBeenCalledExactlyOnceWith('pb-1', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    const pager = queuePager(wrapper)
    for (let page = 1; page <= 4; page++) {
      await pager.findAll('button')[1].trigger('click')
      await flushPromises()
    }
    expect(mocks.listPlaybooks).toHaveBeenLastCalledWith(4, 25, expect.any(Object))
    expect(mocks.listVersions).toHaveBeenCalledTimes(1)
    const playbookSelect = wrapper.findAllComponents(ElSelect).find(item => item.classes().includes('soar-queue-playbook'))!
    expect(playbookSelect.findAllComponents(ElOption)).toHaveLength(26)
    playbookSelect.vm.$emit('change', 'pb-101')
    await flushPromises()
    expect(mocks.listVersions).toHaveBeenLastCalledWith('pb-101', expect.any(Object))
    expect(versionSelect(wrapper).props('modelValue')).toBe('pb-101-v1')
    await pager.findAll('button')[0].trigger('click')
    await flushPromises()
    expect(playbookSelect.props('modelValue')).toBe('pb-101')
    expect(versionSelect(wrapper).props('modelValue')).toBe('pb-101-v1')
    const submit = [...document.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button')].find(button => button.textContent?.includes('确认并排队'))!
    submit.click()
    await flushPromises()
    expect(mocks.queueRun).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ playbookVersionId: 'pb-101-v1' }))
    expect(wrapper.find<HTMLSelectElement>('.soar-run-select select').element.value).toBe('queued-run')
    wrapper.unmount()
  })

  it('aborts the old version request when the chosen playbook changes and ignores its late reply', async () => {
    let finish: (value: ReturnType<typeof version>[]) => void = () => {}
    mocks.listVersions.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(SoarRunInspector, { attachTo: document.body })
    await flushPromises()
    await openQueue(wrapper)
    const signal = mocks.listVersions.mock.calls[0][1].signal as AbortSignal
    const playbookSelect = wrapper.findAllComponents(ElSelect).find(item => item.classes().includes('soar-queue-playbook'))!
    playbookSelect.vm.$emit('change', 'pb-2')
    await flushPromises()
    expect(signal.aborted).toBe(true)
    expect(versionSelect(wrapper).props('modelValue')).toBe('pb-2-v1')
    finish([version('pb-1')])
    await flushPromises()
    expect(versionSelect(wrapper).props('modelValue')).toBe('pb-2-v1')
    expect(versionSelect(wrapper).findAllComponents(ElOption).map(option => option.props('value'))).toEqual(['pb-2-v1'])
    wrapper.unmount()
  })

  it('drops catalog and version replies from dismissed dialogs when reopened', async () => {
    let finishCatalog: (value: ReturnType<typeof pageOf<ReturnType<typeof playbook>>>) => void = () => {}
    mocks.listPlaybooks.mockImplementationOnce(() => new Promise(resolve => { finishCatalog = resolve }))
    const wrapper = mount(SoarRunInspector, { attachTo: document.body })
    await flushPromises()
    await openQueue(wrapper)
    const catalogSignal = mocks.listPlaybooks.mock.calls[0][2].signal as AbortSignal
    wrapper.findComponent(ElDialog).vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(catalogSignal.aborted).toBe(true)
    let finishVersions: (value: ReturnType<typeof version>[]) => void = () => {}
    mocks.listVersions.mockImplementationOnce(() => new Promise(resolve => { finishVersions = resolve }))
    await openQueue(wrapper)
    const versionSignal = mocks.listVersions.mock.calls[0][1].signal as AbortSignal
    finishCatalog(pageOf([playbook(999)]))
    await flushPromises()
    expect(mocks.listVersions).toHaveBeenCalledTimes(1)
    expect(mocks.listVersions).toHaveBeenLastCalledWith('pb-1', expect.any(Object))
    wrapper.findComponent(ElDialog).vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(versionSignal.aborted).toBe(true)
    mocks.listPlaybooks.mockResolvedValue(pageOf([playbook(2)]))
    await openQueue(wrapper)
    finishVersions([version('pb-1')])
    await flushPromises()
    expect(versionSelect(wrapper).props('modelValue')).toBe('pb-2-v1')
    expect(wrapper.findComponent(ElDialog).props('modelValue')).toBe(true)
    wrapper.unmount()
  })

  it('lets a pristine automatically populated queue dialog close without a discard prompt', async () => {
    const wrapper = mount(SoarRunInspector, { attachTo: document.body })
    await flushPromises()
    await openQueue(wrapper)
    const cancel = [...document.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button')].find(button => button.textContent?.trim() === '取消')!
    cancel.click()
    await flushPromises()
    expect(wrapper.findComponent(ElDialog).props('modelValue')).toBe(false)
    expect(document.querySelector('.el-message-box')).toBeNull()
    wrapper.unmount()
  })

  it('shows a selected-playbook version failure and does not queue a previously selected version', async () => {
    const wrapper = mount(SoarRunInspector, { attachTo: document.body })
    await flushPromises()
    await openQueue(wrapper)
    mocks.listVersions.mockRejectedValueOnce(new Error('Versions unavailable'))
    wrapper.findAllComponents(ElSelect).find(item => item.classes().includes('soar-queue-playbook'))!.vm.$emit('change', 'pb-2')
    await flushPromises()
    expect(versionSelect(wrapper).props('modelValue')).toBe('')
    expect(document.body.textContent).toContain('Versions unavailable')
    const submit = [...document.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button')].find(button => button.textContent?.includes('确认并排队'))!
    expect(submit.disabled).toBe(true)
    expect(mocks.queueRun).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('analyst response context and receipts', () => {
  it('prefills declared inputs and submits exactly the displayed edited values with both origin identities', async () => {
    mocks.listVersions.mockResolvedValue([{ ...version('pb-1'), definition: {
      inputSchema: { type: 'object', additionalProperties: false, required: ['alarmId', 'caseId', 'reason', 'enabled'], properties: {
        alarmId: { type: 'string' }, caseId: { type: 'string' }, reason: { type: 'string', default: 'reviewed' }, enabled: { type: 'boolean', default: false },
      } } }, riskSummary: { highRiskActionCount: 1 } }])
    const wrapper = mount(SoarRunInspector, { props: { contextAlarmId: 'alarm-77', contextCaseId: 'case-88' }, attachTo: document.body })
    await flushPromises(); await openQueue(wrapper)
    const form = wrapper.findComponent({ name: 'SchemaInputForm' })
    expect(form.props('modelValue')).toEqual({ alarmId: 'alarm-77', caseId: 'case-88', reason: 'reviewed', enabled: false })
    form.vm.$emit('update:modelValue', { ...form.props('modelValue'), reason: 'confirmed by analyst' })
    await flushPromises()
    const view = wrapper.vm as unknown as { submitQueue: () => Promise<void> }
    await view.submitQueue(); await flushPromises()
    const submitted = mocks.queueRun.mock.calls[0][0]
    expect(submitted.subject).toEqual({ type: 'alert', id: 'alarm-77', alarmId: 'alarm-77', caseId: 'case-88' })
    expect(submitted.inputs).toEqual({ alarmId: 'alarm-77', caseId: 'case-88', reason: 'confirmed by analyst', enabled: false })
    expect(mocks.confirmDanger.mock.calls[0][0]).toContain(JSON.stringify(submitted.inputs))
    expect(wrapper.emitted('select-run')?.at(-1)).toEqual(['queued-run'])
    wrapper.unmount()
  })

  it('blocks execution when schema form displays invalid raw JSON, even if last valid model remains', async () => {
    mocks.listVersions.mockResolvedValue([{ ...version('pb-1'), definition: { inputSchema: { type: 'object', properties: { reason: { default: 'reviewed', type: 'string' } } } } }])
    const wrapper = mount(SoarRunInspector, { attachTo: document.body })
    await flushPromises(); await openQueue(wrapper)
    const form = wrapper.findComponent({ name: 'SchemaInputForm' })
    await form.get('details textarea').setValue('{invalid')
    await (wrapper.vm as unknown as { submitQueue: () => Promise<void> }).submitQueue()
    expect(mocks.confirmDanger).not.toHaveBeenCalled()
    expect(mocks.queueRun).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('opens an exact deep-linked run outside the first page and retains all durable history pages', async () => {
    const events = Array.from({ length: 450 }, (_, index) => ({ sequence: index + 1, eventType: `receipt-${index + 1}`, createdAt: '2026-10-02T00:00:00Z', payload: {} }))
    mocks.listEvents.mockImplementation(async (_runId, after, page, size) => {
      const filtered = events.filter(item => item.sequence > after)
      return { items: filtered.slice(page * size, (page + 1) * size), total: filtered.length, totalPages: Math.ceil(filtered.length / size), page, size }
    })
    const wrapper = mount(SoarRunInspector, { props: { initialRunId: 'retained-run-450' } })
    await flushPromises()
    expect(mocks.getRun).toHaveBeenCalledWith('retained-run-450', expect.any(Object))
    expect(wrapper.find<HTMLSelectElement>('.soar-run-select select').element.value).toBe('retained-run-450')
    expect(mocks.listEvents).toHaveBeenCalledWith('retained-run-450', 0, 2, 200, expect.any(Object))
    const view = wrapper.vm as unknown as { events: unknown[]; visibleEvents: unknown[]; loadEventHistory: (page: number | null) => Promise<void> }
    expect(view.events).toHaveLength(200)
    expect(view.events[0]).toMatchObject({ sequence: 251 })
    await view.loadEventHistory(0)
    expect(view.visibleEvents).toHaveLength(200)
    expect(view.visibleEvents[0]).toMatchObject({ sequence: 1 })
    await view.loadEventHistory(null)
    expect(view.visibleEvents.at(-1)).toMatchObject({ sequence: 450 })
    wrapper.unmount()
  })

  it('rejects stale SSE events from the prior run and repairs the current run through REST', async () => {
    class Source {
      static all: Source[] = []
      listener?: (event: { data: string }) => void
      onerror?: () => void
      closed = false
      constructor(readonly url: string) { Source.all.push(this) }
      addEventListener(_name: string, listener: (event: { data: string }) => void) { this.listener = listener }
      close() { this.closed = true }
      emit(sequence: number) { this.listener?.({ data: JSON.stringify({ sequence, eventType: 'receipt', payload: {} }) }) }
    }
    vi.stubGlobal('EventSource', Source)
    try {
      const wrapper = mount(SoarRunInspector)
      await flushPromises()
      const previous = Source.all.at(-1)!
      await wrapper.find('.soar-run-select select').setValue('run-2'); await flushPromises()
      expect(previous.closed).toBe(true)
      previous.emit(900)
      expect((wrapper.vm as unknown as { events: unknown[] }).events).toEqual([])
      mocks.getRun.mockResolvedValueOnce({ ...fixture('run-2'), status: 'SUCCEEDED' })
      Source.all.at(-1)!.emit(1); await flushPromises()
      expect(wrapper.find('.soar-run-summary .el-tag').text()).toBe('成功')
      wrapper.unmount()
    } finally { vi.unstubAllGlobals() }
  })
})
