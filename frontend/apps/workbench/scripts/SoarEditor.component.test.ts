import { flushPromises, shallowMount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SoarEditor from '../src/components/soar/SoarEditor.vue'

const api = vi.hoisted(() => ({ listPlaybooks: vi.fn(), listVersions: vi.fn(), getVersion: vi.fn(), queueRun: vi.fn() }))
const confirmation = vi.hoisted(() => ({ confirmDanger: vi.fn(), promptInput: vi.fn() }))
vi.mock('../src/api', async original => ({ ...await original<object>(), ...api }))
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => confirmation }))
const version = { id: 'version-a', playbookId: 'playbook-a', version: 1, status: 'PUBLISHED', rowVersion: 1,
  riskSummary: {}, definition: { schemaVersion: 'soar.playbook', entryNodeId: 'start', nodes: [{ id: 'start', type: 'START' }, { id: 'end', type: 'END' }], edges: [{ from: 'start', to: 'end' }] } }
type View = { queueRun: () => Promise<void>; liveRunText: string; runBusy: boolean; errorMessage: string; sessionReady: boolean }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  const wrapper = shallowMount(SoarEditor, { props: { initialPlaybookId: 'playbook-a', contextAlarmId: 'alarm-a' }, global: { plugins: [router], renderStubDefaultSlot: true } })
  await flushPromises()
  return { wrapper, view: wrapper.vm as unknown as View }
}
beforeEach(() => {
  vi.resetAllMocks()
  api.listPlaybooks.mockResolvedValue({ items: [{ id: 'playbook-a', name: 'Response A' }] })
  api.listVersions.mockResolvedValue([version])
  api.getVersion.mockResolvedValue(structuredClone(version))
  api.queueRun.mockResolvedValue({ runId: 'run-a' })
  confirmation.confirmDanger.mockResolvedValue(true)
})
describe('published response submission ownership', () => {
  it('does not submit when execution permission changes while confirmation is open', async () => {
    const { wrapper, view } = await setup()
    expect(view.sessionReady).toBe(true)
    const answer = deferred<boolean>(); confirmation.confirmDanger.mockReturnValueOnce(answer.promise)
    const pending = view.queueRun()
    await wrapper.setProps({ canExecute: false }); await wrapper.setProps({ canExecute: true })
    answer.resolve(true); await pending
    expect(api.queueRun).not.toHaveBeenCalled()
    expect(view.runBusy).toBe(false)
    wrapper.unmount()
  })
  it('never submits an input snapshot superseded while confirmation is open', async () => {
    const { wrapper, view } = await setup()
    view.liveRunText = '{"ip":"192.0.2.1"}'
    const answer = deferred<boolean>(); confirmation.confirmDanger.mockReturnValueOnce(answer.promise)
    const pending = view.queueRun()
    view.liveRunText = '{"ip":"192.0.2.2"}'
    answer.resolve(true); await pending
    expect(api.queueRun).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('keeps a pending request identity through context changes and JSON key reordering', async () => {
    const { wrapper, view } = await setup()
    view.liveRunText = '{"ip":"192.0.2.1","ttl":30}'
    api.queueRun.mockRejectedValueOnce(new Error('response lost'))
    await view.queueRun()
    const original = api.queueRun.mock.calls[0]![0]
    await wrapper.setProps({ contextAlarmId: 'alarm-b' }); await wrapper.setProps({ contextAlarmId: 'alarm-a' })
    view.liveRunText = '{"ttl":30,"ip":"192.0.2.1"}'
    await view.queueRun()
    expect(api.queueRun.mock.calls[1]![0]).toEqual(original)
    expect(wrapper.emitted('open-run')).toEqual([['run-a']])
    wrapper.unmount()
  })
  it('does not publish an accepted run into a revoked execution session', async () => {
    const { wrapper, view } = await setup()
    const receipt = deferred<{ runId: string }>(); api.queueRun.mockReturnValueOnce(receipt.promise)
    const pending = view.queueRun(); await flushPromises()
    await wrapper.setProps({ canExecute: false })
    receipt.resolve({ runId: 'accepted-old-run' }); await pending
    expect(wrapper.emitted('open-run')).toBeUndefined()
    expect(view.runBusy).toBe(false)
    wrapper.unmount()
  })
  it('reuses the immutable key for an identical ambiguous retry and warns before a distinct submission', async () => {
    const { wrapper, view } = await setup()
    view.liveRunText = '{"ip":"192.0.2.1"}'
    api.queueRun.mockRejectedValueOnce(new Error('response lost')).mockRejectedValueOnce(new Error('still unknown'))
    await view.queueRun()
    const original = api.queueRun.mock.calls[0]![0]
    await view.queueRun()
    expect(api.queueRun.mock.calls[1]![0]).toEqual(original)
    view.liveRunText = '{"ip":"192.0.2.2"}'
    confirmation.confirmDanger.mockResolvedValueOnce(false)
    await view.queueRun()
    expect(api.queueRun).toHaveBeenCalledTimes(2)
    expect(confirmation.confirmDanger.mock.calls.at(-1)![0]).toContain(original.requestId)
    confirmation.confirmDanger.mockResolvedValue(true)
    await view.queueRun()
    expect(api.queueRun.mock.calls[2]![0]).toEqual(expect.objectContaining({ inputs: { ip: '192.0.2.2' } }))
    expect(api.queueRun.mock.calls[2]![0].requestId).not.toBe(original.requestId)
    await flushPromises()
    expect(wrapper.text()).toContain(original.requestId)
    wrapper.unmount()
  })
})
