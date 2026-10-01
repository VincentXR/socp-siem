import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SoarRunInspector from '../src/components/soar/SoarRunInspector.vue'

const mocks = vi.hoisted(() => ({
  listRuns: vi.fn(), getRun: vi.fn(), listNodes: vi.fn(), listEvents: vi.fn(), listArtifacts: vi.fn(),
  confirmDanger: vi.fn(), promptInput: vi.fn(), rerunRun: vi.fn(), retryRun: vi.fn(), cancelWorkflowRun: vi.fn(),
}))
vi.mock('../src/api', async importOriginal => ({ ...await importOriginal<object>(), ...mocks }))
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => ({ confirmDanger: mocks.confirmDanger, promptInput: mocks.promptInput }) }))

const fixture = (runId: string) => ({ runId, playbookId: 'pb-1', playbookVersion: 1, status: 'FAILED' })

beforeEach(() => {
  Object.values(mocks).forEach(mock => mock.mockReset())
  mocks.listRuns.mockResolvedValue({ items: [fixture('run-1'), fixture('run-2')] })
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
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
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
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
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
