import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import IngestView from '../src/views/IngestView.vue'
import PageHeader from '../src/components/PageHeader.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
import { translate } from '../src/i18n'
import type { IngestTask, LogSource } from '../src/api'

const api = vi.hoisted(() => ({
  listSourcesPage: vi.fn(), listOutputs: vi.fn(), listParseRulesPage: vi.fn(), resolveParseRules: vi.fn(),
  listIngestTasks: vi.fn(), ingestSummary: vi.fn(), listCategories: vi.fn(), previewSource: vi.fn(),
  listIngestParseFailures: vi.fn(), replayIngestParseFailure: vi.fn(), updateSource: vi.fn(), createSource: vi.fn(),
  updateOutput: vi.fn(), createOutput: vi.fn(), validateOutputConfig: vi.fn(),
  getSource: vi.fn(), getSourceSetup: vi.fn(), startIngestTask: vi.fn(), stopIngestTask: vi.fn(), confirm: vi.fn(),
}))
vi.mock('../src/api', async original => ({ ...await original<typeof import('../src/api')>(), ...api }))
vi.mock('element-plus/es/components/message-box/index.mjs', () => ({ default: { confirm: api.confirm } }))
const summary = { collectors: 1, accepted: 19, skipped: 2, forwarded: 17, bytes: 512, eps1m: 25,
  byHealth: { HEALTHY: 1 }, sources: 3, enabledSources: 2 }
type View = { refreshAll: () => Promise<void>; sources: Array<{ id: string }>; logCategories: Array<{ id: string }>
  openTest: (task: IngestTask) => void; runTest: () => Promise<void>; testDialog: boolean; testSample: string
  testResult: { fields: Record<string, string>; attempts?: Array<{ ruleId?: string }> } | null
  sourcePage: number; sourceTotal: number; sourceSearchDraft: string
  loadSources: () => Promise<void>; applySourceSearch: () => void
  rulePage: number; ruleTotal: number; ruleSearchDraft: string; parseRules: Array<{ id: string }>
  applyRuleSearch: () => void; openEditSource: (source: LogSource) => void
  sourceRuleOptionLabel: (id: string) => string; searchSourceRules: (query: string) => void
  newSource: Record<string, unknown>; saveSource: (asDraft?: boolean) => Promise<void>; showSetup: boolean; setupId: string; openCreateSource: () => void
  parseFailures: Array<Record<string, unknown>>; replayParseFailure: (row: Record<string, unknown>) => Promise<void>
  sourcesLoading: boolean; refreshing: boolean; actionBusy: boolean; tasks: IngestTask[]
  toggleTask: (task: IngestTask) => Promise<void>
  openSetup: (id: string) => void; closeSetup: () => void; editSourceById: (id: string) => Promise<void>; showSourceDialog: boolean
  openEditOutput: (output: import('../src/api').SinkTarget) => void; addOutput: () => Promise<void>; checkOutput: () => Promise<void>
  newOutput: { name: string; type: string; uri: string; authToken: string; enabled: boolean }; credentialAction: 'KEEP' | 'REPLACE' | 'CLEAR'
  outputErrors: Record<string, string>; outputValidation: string
  editingSourceId: string | null; onIngestTab: (tab: string) => void }
async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: IngestView }] })
  await router.push('/')
  const role = ref('admin')
  const root = mount(RouterView, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: role } } } })
  return { root, router, role, wrapper: root.findComponent(IngestView) }
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
beforeEach(() => {
  vi.resetAllMocks()
  api.listSourcesPage.mockResolvedValue({ items: [], total: 0, page: 1, size: 20, totalPages: 0 })
  api.listOutputs.mockResolvedValue([])
  api.listParseRulesPage.mockResolvedValue({ items: [], total: 0, page: 1, size: 20, totalPages: 0 })
  api.resolveParseRules.mockResolvedValue([])
  api.listIngestTasks.mockResolvedValue([])
  api.ingestSummary.mockResolvedValue(summary)
  api.listCategories.mockResolvedValue([])
  api.previewSource.mockResolvedValue({ matched: true, fields: {} })
  api.listIngestParseFailures.mockResolvedValue({ items: [], total: 0, page: 1, size: 50, totalPages: 0 })
  api.updateSource.mockImplementation((id: string, body: object) => Promise.resolve({ source: { id, ...body } }))
  api.createSource.mockImplementation((body: object) => Promise.resolve({ id: 'new-source', ...body }))
  api.getSourceSetup.mockImplementation((id: string) => Promise.resolve({ source: { id, name: 'Saved source', enabled: false, type: 'FILE' }, nativeVector: true, pipeline: [], problems: [], output: null, configurationVersion: 'v1' }))
  api.confirm.mockResolvedValue('confirm')
})

describe('ingest independent reads', () => {
  it('preserves output credentials by default and only replaces or clears with explicit intent', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    const output = { id: 'output-a', name: 'Receiver', type: 'HTTP', uri: 'https://example.test/ingest', enabled: true, authTokenConfigured: true } as import('../src/api').SinkTarget
    view.openEditOutput(output); await flushPromises()
    expect(view.credentialAction).toBe('KEEP')
    expect(view.newOutput.authToken).toBe('')
    view.newOutput.name = 'Renamed'
    await view.addOutput(); await flushPromises()
    expect(api.updateOutput).toHaveBeenLastCalledWith('output-a', expect.objectContaining({ name: 'Renamed', authToken: null }), 'KEEP')

    view.openEditOutput(output); view.credentialAction = 'REPLACE'; await flushPromises()
    await view.addOutput()
    expect(api.updateOutput).toHaveBeenCalledTimes(1)
    expect(view.outputErrors.authToken).toBeTruthy()
    view.newOutput.authToken = 'replacement-token'
    await view.addOutput(); await flushPromises()
    expect(api.updateOutput).toHaveBeenLastCalledWith('output-a', expect.objectContaining({ authToken: 'replacement-token' }), 'REPLACE')

    view.openEditOutput(output); view.credentialAction = 'CLEAR'; await flushPromises()
    await view.addOutput(); await flushPromises()
    expect(api.updateOutput).toHaveBeenLastCalledWith('output-a', expect.objectContaining({ authToken: null }), 'CLEAR')
    expect(api.createOutput).not.toHaveBeenCalled()
    root.unmount()
  })

  it('retains static output validation without sending credentials for a keep operation', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    view.openEditOutput({ id: 'output-a', name: 'Receiver', type: 'HTTP', uri: 'https://example.test/ingest', enabled: true } as import('../src/api').SinkTarget)
    await view.checkOutput(); await flushPromises()
    expect(api.validateOutputConfig).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ authToken: null }))
    expect(view.outputValidation).toBeTruthy()
    expect(api.updateOutput).not.toHaveBeenCalled()
    view.newOutput.uri = 'https://example.test/ingest#fragment'
    await view.checkOutput()
    expect(api.validateOutputConfig).toHaveBeenCalledTimes(1)
    expect(view.outputErrors.uri).toBeTruthy()
    root.unmount()
  })

  it('keeps guided source selection synchronized with opening, Back, Forward and closing', async () => {
    const { root, router, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    view.openSetup('source-a'); await flushPromises()
    expect(router.currentRoute.value.query.sourceId).toBe('source-a')
    router.back(); await flushPromises()
    expect(view.showSetup).toBe(false)
    router.forward(); await flushPromises()
    expect(view.showSetup).toBe(true)
    expect(view.setupId).toBe('source-a')
    view.closeSetup(); await flushPromises()
    expect(router.currentRoute.value.query.sourceId).toBeUndefined()
    root.unmount()
  })

  it('does not open a delayed source editor after closing or selecting another setup', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    const pending = deferred<{ source: LogSource }>()
    api.getSource.mockReturnValueOnce(pending.promise)
    view.openSetup('source-a'); await flushPromises()
    const editing = view.editSourceById('source-a')
    view.closeSetup(); await flushPromises()
    view.openSetup('source-b'); await flushPromises()
    pending.resolve({ source: { id: 'source-a', name: 'Old source', enabled: false, type: 'FILE' } as LogSource })
    await editing; await flushPromises()
    expect(view.showSourceDialog).toBe(false)
    expect(view.showSetup).toBe(true)
    expect(view.setupId).toBe('source-b')
    root.unmount()
  })

  it('does not replay a quarantine event after permission is revoked during confirmation', async () => {
    const { root, wrapper, role } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    const confirmation = deferred<string>()
    api.confirm.mockReturnValueOnce(confirmation.promise)
    const replay = view.replayParseFailure({ id: 'failure-a' })
    role.value = 'viewer'; await flushPromises()
    confirmation.resolve('confirm'); await replay
    expect(api.replayIngestParseFailure).not.toHaveBeenCalled()
    root.unmount()
  })

  it('saves an incomplete source as a disabled draft and opens its guided setup', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    view.openCreateSource()
    view.newSource.name = 'Incomplete source'
    await view.saveSource()
    expect(api.createSource).not.toHaveBeenCalled()
    await view.saveSource(true); await flushPromises()
    expect(api.createSource).toHaveBeenCalledWith(expect.objectContaining({ name: 'Incomplete source', enabled: false, path: null }))
    expect(view.showSetup).toBe(true)
    expect(view.setupId).toBe('new-source')
    expect(api.getSourceSetup).toHaveBeenCalledWith('new-source', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    root.unmount()
  })

  it('keeps a newer source read loading when a superseded read finishes', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    const first = deferred<{ items: []; total: number; page: number; size: number; totalPages: number }>()
    const second = deferred<{ items: []; total: number; page: number; size: number; totalPages: number }>()
    api.listSourcesPage.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const oldRead = view.loadSources(), currentRead = view.loadSources()
    expect(view.sourcesLoading).toBe(true)
    first.resolve({ items: [], total: 0, page: 1, size: 20, totalPages: 0 }); await oldRead
    expect(view.sourcesLoading).toBe(true)
    second.resolve({ items: [], total: 0, page: 1, size: 20, totalPages: 0 }); await currentRead
    expect(view.sourcesLoading).toBe(false)
    root.unmount()
  })

  it('confirms a configuration change without claiming the collector stops, and suppresses duplicate requests', async () => {
    const task = { id: 'task-a', name: 'Source A', enabled: true, runtime: { health: 'HEALTHY' } } as IngestTask
    api.listIngestTasks.mockResolvedValue([task])
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    const confirmation = deferred<string>()
    api.confirm.mockReturnValueOnce(confirmation.promise)
    const first = view.toggleTask(view.tasks[0]!)
    await view.toggleTask(view.tasks[0]!)
    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(api.confirm.mock.calls[0]?.[0]).toContain('手工应用 Vector')
    expect(view.actionBusy).toBe(false)
    expect(api.stopIngestTask).not.toHaveBeenCalled()
    confirmation.resolve('confirm'); await first
    expect(api.stopIngestTask).toHaveBeenCalledExactlyOnceWith('task-a')
    root.unmount()
  })

  it('does not toggle a changed task after confirmation or execute a confirmation after leaving', async () => {
    const task = { id: 'task-a', name: 'Source A', enabled: true, runtime: { health: 'HEALTHY' } } as IngestTask
    api.listIngestTasks.mockResolvedValue([task])
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    const confirmation = deferred<string>()
    api.confirm.mockReturnValueOnce(confirmation.promise)
    const operation = view.toggleTask(view.tasks[0]!)
    view.tasks[0]!.enabled = false
    confirmation.resolve('confirm'); await operation
    expect(api.stopIngestTask).not.toHaveBeenCalled()
    expect(api.startIngestTask).not.toHaveBeenCalled()
    const leaving = deferred<string>()
    api.confirm.mockReturnValueOnce(leaving.promise)
    const late = view.toggleTask(view.tasks[0]!)
    root.unmount()
    leaving.resolve('confirm'); await late
    expect(api.startIngestTask).not.toHaveBeenCalled()
  })

  it('refreshes the quarantine together with the rest of the page', async () => {
    const { root } = await setup(); await flushPromises()
    expect(api.listIngestParseFailures).toHaveBeenCalledWith(1, 50, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    root.unmount()
  })

  it('sends a complete replacement when editing so hidden source fields survive', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    view.openEditSource({
      id: 'source-a', name: 'Before', type: 'FILE', format: 'JSON', path: '/var/log/a.log',
      address: null, topic: null, env: 'prod', enabled: true, createdAt: '2026-09-01T00:00:00Z',
      readFrom: 'end', multiline: '{ mode = "continue_through" }', sinkTargetId: 'sink-a',
      parseRuleIds: ['rule-a'], description: 'Owned by detection engineering', protocol: 'tcp',
      charset: 'utf-8', timeField: '@timestamp', timezone: 'UTC', tags: ['team=blue'],
      frequency: 7, categoryId: 'auth', groupId: 'group-a',
    })
    await flushPromises()
    view.newSource.name = 'After'
    await view.saveSource()
    expect(api.updateSource).toHaveBeenCalledWith('source-a', expect.objectContaining({
      name: 'After', description: 'Owned by detection engineering', timeField: '@timestamp',
      path: '/var/log/a.log', readFrom: 'end', multiline: '{ mode = "continue_through" }',
      sinkTargetId: 'sink-a', parseRuleIds: ['rule-a'], tags: ['team=blue'], groupId: 'group-a',
    }))
    root.unmount()
  })

  it('shows and retains the latest replay outcome before refreshing quarantine', async () => {
    const failed = { id: 'failure-a', collectorId: 'vector', rawPayload: 'bad', receivedAt: '2026-09-01T00:00:00Z',
      parserVersion: 'v1', failureReason: 'first error', replayStatus: 'PENDING', replayAttempts: 0 }
    api.listIngestParseFailures.mockResolvedValueOnce({ items: [failed], total: 1, page: 1, size: 50, totalPages: 1 })
      .mockResolvedValueOnce({ items: [{ ...failed, lastError: 'latest parser error', replayAttempts: 1 }], total: 1, page: 1, size: 50, totalPages: 1 })
    api.replayIngestParseFailure.mockResolvedValue({ ...failed, lastError: 'latest parser error', replayAttempts: 1, replayed: false })
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    await view.replayParseFailure(failed)
    await flushPromises()
    expect(api.replayIngestParseFailure).toHaveBeenCalledWith('failure-a')
    expect(view.parseFailures[0]?.lastError).toBe('latest parser error')
    expect(view.parseFailures[0]?.replayAttempts).toBe(1)
    root.unmount()
  })

  it('pages and searches rule 501 while retaining its selected source-binding label', async () => {
    const deep = { id: 'rule-501', name: 'Deep parser', format: 'KV', pattern: null, sourceId: null,
      enabled: true, order: 501, mapping: [], setFields: [], filters: [] }
    api.listParseRulesPage.mockImplementation((page: number, size: number, q: string) => Promise.resolve({
      items: q === 'Deep' || page === 26 ? [deep] : [{ ...deep, id: 'rule-001', name: 'First parser' }],
      total: q === 'Deep' ? 1 : 501, page, size, totalPages: q === 'Deep' ? 1 : Math.ceil(501 / size),
    }))
    api.resolveParseRules.mockResolvedValue([deep])
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    expect(view.ruleTotal).toBe(501)
    view.rulePage = 26; await flushPromises()
    expect(api.listParseRulesPage).toHaveBeenCalledWith(26, 20, '', expect.any(Object))
    expect(view.parseRules[0]?.id).toBe(deep.id)
    view.ruleSearchDraft = 'Deep'; view.applyRuleSearch(); await flushPromises()
    expect(api.listParseRulesPage).toHaveBeenCalledWith(1, 20, 'Deep', expect.any(Object))
    view.openEditSource({ id: 'source-a', name: 'Source A', type: 'FILE', format: 'AUTO',
      parseRuleIds: [deep.id], enabled: true } as LogSource)
    await flushPromises()
    expect(api.resolveParseRules).toHaveBeenCalledWith([deep.id], expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(view.sourceRuleOptionLabel(deep.id)).toBe('Deep parser')
    vi.useFakeTimers()
    view.searchSourceRules('Deep')
    await vi.advanceTimersByTimeAsync(250)
    vi.useRealTimers()
    await flushPromises()
    expect(api.listParseRulesPage).toHaveBeenCalledWith(1, 50, 'Deep', expect.any(Object))
    expect(view.sourceRuleOptionLabel(deep.id)).toBe('Deep parser')
    root.unmount()
  })

  it('pages beyond 500 sources, searches by name and clamps an emptied last page', async () => {
    api.listSourcesPage.mockResolvedValueOnce({ items: [{ id: 'first-source' }], total: 501, page: 1, size: 20, totalPages: 26 })
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    expect(view.sourceTotal).toBe(501)
    expect(api.listSourcesPage).toHaveBeenCalledWith(1, 20, '', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    api.listSourcesPage.mockResolvedValueOnce({ items: [{ id: 'source-501' }], total: 501, page: 26, size: 20, totalPages: 26 })
    view.sourcePage = 26; await flushPromises()
    expect(api.listSourcesPage).toHaveBeenCalledWith(26, 20, '', expect.any(Object))
    expect(view.sources[0]?.id).toBe('source-501')
    api.listSourcesPage.mockResolvedValueOnce({ items: [], total: 500, page: 26, size: 20, totalPages: 25 })
      .mockResolvedValueOnce({ items: [{ id: 'source-500' }], total: 500, page: 25, size: 20, totalPages: 25 })
    await view.loadSources(); await flushPromises()
    expect(view.sourcePage).toBe(25)
    expect(view.sources[0]?.id).toBe('source-500')
    api.listSourcesPage.mockResolvedValueOnce({ items: [{ id: 'named-source' }], total: 1, page: 1, size: 20, totalPages: 1 })
    view.sourceSearchDraft = 'Critical'; view.applySourceSearch(); await flushPromises()
    expect(api.listSourcesPage).toHaveBeenCalledWith(1, 20, 'Critical', expect.any(Object))
    expect(view.sources[0]?.id).toBe('named-source')
    root.unmount()
  })

  it('keeps the last successful summary and labels it stale after failure', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const metrics = () => wrapper.findAll('.metrics-row .stat-card .num').map(node => node.text())
    expect(metrics()).toEqual(['2/3', '25', '19', '17', '2', '512 B'])
    api.ingestSummary.mockRejectedValueOnce(new Error('summary unavailable'))
    await wrapper.findComponent(PageHeader).find('button').trigger('click'); await flushPromises()
    expect(metrics()).toEqual(['2/3', '25', '19', '17', '2', '512 B'])
    expect(wrapper.text()).toContain(translate('ingest.summaryStale'))
    expect(wrapper.text()).toContain('summary unavailable')
    await wrapper.findComponent(PageHeader).find('button').trigger('click'); await flushPromises()
    expect(wrapper.text()).not.toContain(translate('ingest.summaryStale'))
    root.unmount()
  })

  it('shows unavailable metrics and a category failure without erasing successful lists', async () => {
    api.ingestSummary.mockRejectedValueOnce(new Error('summary offline'))
    api.listCategories.mockRejectedValueOnce(new Error('categories offline'))
    api.listSourcesPage.mockResolvedValueOnce({ items: [{ id: 'source-a', name: 'Source A' }], total: 1, page: 1, size: 20, totalPages: 1 })
    const { root, wrapper } = await setup(); await flushPromises()
    expect(wrapper.findAll('.metrics-row .stat-card .num').map(node => node.text())).toEqual(Array(6).fill(translate('time.notAvailable')))
    expect(wrapper.text()).toContain('categories offline')
    expect((wrapper.vm as unknown as View).sources[0]?.id).toBe('source-a')
    root.unmount()
  })

  it('ignores an older source response after a later refresh and aborts reads on unmount', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    const old = deferred<{ items: Array<{ id: string }>; total: number; page: number; size: number; totalPages: number }>()
    api.listSourcesPage.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ items: [{ id: 'new-source' }], total: 1, page: 1, size: 20, totalPages: 1 })
    const first = view.refreshAll()
    const oldSignal = api.listSourcesPage.mock.calls.at(-1)![3].signal as AbortSignal
    await view.refreshAll()
    expect(oldSignal.aborted).toBe(true)
    old.resolve({ items: [{ id: 'old-source' }], total: 1, page: 1, size: 20, totalPages: 1 }); await first
    expect(view.sources[0]?.id).toBe('new-source')
    const pending = deferred<Array<{ id: string }>>()
    api.listCategories.mockReturnValueOnce(pending.promise)
    const last = view.refreshAll()
    const signal = api.listCategories.mock.calls.at(-1)![0].signal as AbortSignal
    root.unmount(); expect(signal.aborted).toBe(true)
    pending.resolve([{ id: 'late-category' }]); await last
    expect(view.logCategories).toEqual([])
  })

  it('does not show a prior task preview after switching tasks or closing the dialog', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    const task = (id: string) => ({ id, name: id, collector: 'vector', format: 'AUTO', parseRuleIds: [`R-${id}`] }) as unknown as IngestTask
    const old = deferred<{ matched: boolean; fields: Record<string, string> }>()
    api.previewSource.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ matched: true, fields: { task: 'B' } })
    view.openTest(task('A')); view.testSample = 'sample A'
    const oldRun = view.runTest(); await flushPromises()
    const oldSignal = api.previewSource.mock.calls[0]![2].signal as AbortSignal
    view.openTest(task('B')); view.testSample = 'sample B'
    expect(oldSignal.aborted).toBe(true)
    await view.runTest()
    old.resolve({ matched: true, fields: { task: 'A' } }); await oldRun
    expect(view.testResult?.fields.task).toBe('B')
    const closing = deferred<{ matched: boolean; fields: Record<string, string> }>()
    api.previewSource.mockReturnValueOnce(closing.promise)
    view.openTest(task('C')); view.testSample = 'sample C'
    const closingRun = view.runTest(); await flushPromises()
    const closingSignal = api.previewSource.mock.calls.at(-1)![2].signal as AbortSignal
    view.testDialog = false; await nextTick()
    expect(closingSignal.aborted).toBe(true)
    closing.resolve({ matched: true, fields: { task: 'C' } }); await closingRun
    expect(view.testResult).toBeNull()
    root.unmount()
  })

  it('previews the effective source pipeline once instead of independently executing every bound rule', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    view.openTest({ id: 'many', name: 'many', collector: 'vector', format: 'AUTO',
      parseRuleIds: Array.from({ length: 10 }, (_, index) => `R-${index}`) } as unknown as IngestTask)
    await view.runTest()
    expect(api.previewSource).not.toHaveBeenCalled()
    view.testSample = 'real raw sample'
    await view.runTest()
    expect(api.previewSource).toHaveBeenCalledTimes(1)
    expect(api.previewSource).toHaveBeenCalledWith('many', 'real raw sample', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    root.unmount()
  })

  it('discards a pending preview after the visible sample changes', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    const pending = deferred<{ matched: boolean; fields: Record<string, string> }>()
    api.previewSource.mockReturnValueOnce(pending.promise)
    view.openTest({ id: 'a' } as IngestTask); view.testSample = 'old sample'
    const run = view.runTest(); await flushPromises()
    view.testSample = 'new sample'
    pending.resolve({ matched: true, fields: { input: 'old sample' } }); await run
    expect(view.testResult).toBeNull()
    root.unmount()
  })
})
