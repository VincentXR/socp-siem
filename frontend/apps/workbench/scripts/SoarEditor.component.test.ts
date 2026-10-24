import { flushPromises, shallowMount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SoarEditor from '../src/components/soar/SoarEditor.vue'

const api = vi.hoisted(() => ({ listPlaybooks: vi.fn(), listVersions: vi.fn(), getVersion: vi.fn(), queueRun: vi.fn(), createPlaybook: vi.fn(), createVersion: vi.fn() }))
const confirmation = vi.hoisted(() => ({ confirmDanger: vi.fn(), promptInput: vi.fn() }))
vi.mock('../src/api', async original => ({ ...await original<object>(), ...api }))
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => confirmation }))
const version = { id: 'version-a', playbookId: 'playbook-a', version: 1, status: 'PUBLISHED', rowVersion: 1,
  riskSummary: {}, definition: { schemaVersion: 'soar.playbook', entryNodeId: 'start', nodes: [{ id: 'start', type: 'START' }, { id: 'end', type: 'END' }], edges: [{ from: 'start', to: 'end' }] } }
type View = {
  queueRun: () => Promise<void>; liveRunText: string; runBusy: boolean; errorMessage: string; sessionReady: boolean
  openNewPlaybookDialog: () => Promise<void>; createPlaybookAndVersion: () => Promise<void>; loadCatalog: () => Promise<void>
  newPlaybookForm: { name: string; description: string; tags: string }; newPlaybookVisible: boolean; newPlaybookError: string
  selectedPlaybookId: string; selectedVersionNo: number | null; selectedVersion: typeof version; newPlaybookSaving: boolean
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
async function setup(initialPlaybookId = 'playbook-a') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  const wrapper = shallowMount(SoarEditor, { props: { initialPlaybookId, contextAlarmId: 'alarm-a' }, global: { plugins: [router], renderStubDefaultSlot: true } })
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
describe('new playbook initial draft contract', () => {
  const draft = { ...version, id: 'created-draft', playbookId: 'created-playbook', status: 'DRAFT' }
  const created = { id: draft.playbookId, name: 'New response', status: 'ACTIVE', tags: [], draftVersion: draft.version, latestPublishedVersion: null }

  beforeEach(() => {
    // Match the server contract: POST playbooks creates draft 1 atomically, and
    // another POST versions must fail while that draft exists.
    api.createPlaybook.mockImplementation(async () => {
      api.listPlaybooks.mockResolvedValue({ items: [created] })
      api.listVersions.mockResolvedValue([draft])
      return created
    })
    api.createVersion.mockRejectedValue(new Error('SOAR_DRAFT_ALREADY_EXISTS (409)'))
    api.getVersion.mockResolvedValue(structuredClone(draft))
  })

  async function openCreation() {
    const editor = await setup('')
    await editor.view.openNewPlaybookDialog()
    editor.view.newPlaybookForm = { name: created.name, description: 'Investigate', tags: 'response, triage' }
    return editor
  }

  it('loads the draft returned by playbook creation without posting another version', async () => {
    const { wrapper, view } = await openCreation()
    await view.createPlaybookAndVersion()
    expect(api.createPlaybook).toHaveBeenCalledExactlyOnceWith({ name: created.name, description: 'Investigate', tags: ['response', 'triage'] })
    expect(api.createVersion).not.toHaveBeenCalled()
    expect(api.getVersion).toHaveBeenCalledExactlyOnceWith(created.id, created.draftVersion, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(view.selectedVersion).toMatchObject({ id: draft.id, status: 'DRAFT', rowVersion: 1 })
    expect(view.sessionReady).toBe(true)
    expect(view.newPlaybookVisible).toBe(false)
    expect(view.newPlaybookError).toBe('')
    expect(wrapper.emitted('created')).toEqual([[created.id]])
    wrapper.unmount()
  })

  it('retains the created identity and recovers with reads after the initial draft GET fails', async () => {
    const { wrapper, view } = await openCreation()
    api.getVersion.mockRejectedValueOnce(new Error('Draft read unavailable'))
    await view.createPlaybookAndVersion()
    expect(wrapper.emitted('created')).toEqual([[created.id]])
    expect(view.selectedPlaybookId).toBe(created.id)
    expect(view.newPlaybookVisible).toBe(false)
    expect(view.errorMessage).toBe('Draft read unavailable')
    expect(view.sessionReady).toBe(false)
    await view.createPlaybookAndVersion()
    await view.loadCatalog()
    expect(api.createPlaybook).toHaveBeenCalledTimes(1)
    expect(api.createVersion).not.toHaveBeenCalled()
    expect(api.getVersion).toHaveBeenCalledTimes(2)
    expect(view.selectedVersion).toMatchObject({ id: draft.id, status: 'DRAFT' })
    expect(view.sessionReady).toBe(true)
    expect(view.errorMessage).toBe('')
    wrapper.unmount()
  })

  it('submits one create request when the submit action is triggered twice', async () => {
    const { wrapper, view } = await openCreation()
    const receipt = deferred<typeof created>()
    api.createPlaybook.mockReturnValueOnce(receipt.promise)
    const first = view.createPlaybookAndVersion()
    const second = view.createPlaybookAndVersion()
    await flushPromises()
    expect(api.createPlaybook).toHaveBeenCalledTimes(1)
    receipt.resolve(created)
    await Promise.all([first, second])
    expect(api.createVersion).not.toHaveBeenCalled()
    expect(view.sessionReady).toBe(true)
    expect(view.newPlaybookSaving).toBe(false)
    wrapper.unmount()
  })
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
