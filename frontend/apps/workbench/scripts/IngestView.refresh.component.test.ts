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
  listIngestTasks: vi.fn(), ingestSummary: vi.fn(), listCategories: vi.fn(), previewParse: vi.fn(),
}))
vi.mock('../src/api', async original => ({ ...await original<typeof import('../src/api')>(), ...api }))
const summary = { collectors: 1, accepted: 19, skipped: 2, forwarded: 17, bytes: 512, eps1m: 25,
  byHealth: { HEALTHY: 1 }, sources: 3, enabledSources: 2 }
type View = { refreshAll: () => Promise<void>; sources: Array<{ id: string }>; logCategories: Array<{ id: string }>
  openTest: (task: IngestTask) => void; runTest: () => Promise<void>; testDialog: boolean
  testResult: { fields: Record<string, string>; attempts?: Array<{ ruleId?: string }> } | null
  sourcePage: number; sourceTotal: number; sourceSearchDraft: string
  loadSources: () => Promise<void>; applySourceSearch: () => void
  rulePage: number; ruleTotal: number; ruleSearchDraft: string; parseRules: Array<{ id: string }>
  applyRuleSearch: () => void; openEditSource: (source: LogSource) => void
  sourceRuleOptionLabel: (id: string) => string; searchSourceRules: (query: string) => void }
async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: IngestView }] })
  await router.push('/')
  const root = mount(RouterView, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } } })
  return { root, wrapper: root.findComponent(IngestView) }
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
  api.previewParse.mockResolvedValue({ matched: true, fields: {} })
})

describe('ingest independent reads', () => {
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
    api.previewParse.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ matched: true, fields: { task: 'B' } })
    view.openTest(task('A'))
    const oldRun = view.runTest(); await flushPromises()
    const oldSignal = api.previewParse.mock.calls[0]![1].signal as AbortSignal
    view.openTest(task('B'))
    expect(oldSignal.aborted).toBe(true)
    await view.runTest()
    old.resolve({ matched: true, fields: { task: 'A' } }); await oldRun
    expect(view.testResult?.fields.task).toBe('B')
    const closing = deferred<{ matched: boolean; fields: Record<string, string> }>()
    api.previewParse.mockReturnValueOnce(closing.promise)
    view.openTest(task('C'))
    const closingRun = view.runTest(); await flushPromises()
    const closingSignal = api.previewParse.mock.calls.at(-1)![1].signal as AbortSignal
    view.testDialog = false; await nextTick()
    expect(closingSignal.aborted).toBe(true)
    closing.resolve({ matched: true, fields: { task: 'C' } }); await closingRun
    expect(view.testResult).toBeNull()
    root.unmount()
  })

  it('bounds parse previews to four requests while preserving rule order', async () => {
    const { root, wrapper } = await setup(); await flushPromises()
    const view = wrapper.vm as unknown as View
    const gates: Array<{ resolve: (value: { matched: boolean; fields: Record<string, string> }) => void }> = []
    api.previewParse.mockImplementation(() => {
      const gate = deferred<{ matched: boolean; fields: Record<string, string> }>()
      gates.push(gate)
      return gate.promise
    })
    view.openTest({ id: 'many', name: 'many', collector: 'vector', format: 'AUTO',
      parseRuleIds: Array.from({ length: 10 }, (_, index) => `R-${index}`) } as unknown as IngestTask)
    const run = view.runTest(); await flushPromises()
    expect(gates).toHaveLength(4)
    for (const gate of gates.slice(0, 4)) gate.resolve({ matched: false, fields: {} })
    await flushPromises(); expect(gates).toHaveLength(8)
    for (const gate of gates.slice(4, 8)) gate.resolve({ matched: false, fields: {} })
    await flushPromises(); expect(gates).toHaveLength(10)
    for (const gate of gates.slice(8)) gate.resolve({ matched: false, fields: {} })
    await run
    expect(view.testResult?.attempts?.map(attempt => attempt.ruleId)).toEqual(Array.from({ length: 10 }, (_, index) => `R-${index}`))
    root.unmount()
  })
})
