import { flushPromises, shallowMount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import AiAssistantView from '../src/views/AiAssistantView.vue'
import type { AiResult, InvestigationResult } from '../src/api'

const mocks = vi.hoisted(() => ({ getAlarm: vi.fn(), getAlarmEvidence: vi.fn(), splSearch: vi.fn(), tiMatch: vi.fn(), getCase: vi.fn(), aiAsk: vi.fn(), investigateAlert: vi.fn(), reanalyzeAlert: vi.fn(), appendInvestigationToIncident: vi.fn(),
  route: { query: {} as Record<string, string | undefined> }, replace: vi.fn(), push: vi.fn() }))
vi.mock('../src/api', async original => ({ ...await original<typeof import('../src/api')>(), ...mocks }))
vi.mock('vue-router', () => ({ useRoute: () => mocks.route, useRouter: () => ({ push: mocks.push, replace: mocks.replace }) }))
function deferred<T = AiResult>() {
  let resolve!: (value: T) => void, reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const answer = (question: string): AiResult => ({ question, answer: `answer ${question}`, source: 'KNOWLEDGE_BASE', suggestion: null, elapsedMs: 1 })
beforeEach(() => {
  vi.resetAllMocks()
  mocks.route = reactive({ query: {} as Record<string, string | undefined> })
  mocks.replace.mockImplementation(async location => { mocks.route.query = location.query })
})

describe('AI answer request ownership', () => {
  it.each(['success', 'failure'])('reset discards a late %s without finishing the next question', async outcome => {
    const first = deferred(), second = deferred()
    mocks.aiAsk.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const wrapper = shallowMount(AiAssistantView, { global: { renderStubDefaultSlot: true } })
    const input = wrapper.findAllComponents(ElInput)[0]!
    input.vm.$emit('update:modelValue', 'first'); await nextTick()
    wrapper.findAllComponents(ElButton)[0]!.vm.$emit('click'); await nextTick()
    const signal = mocks.aiAsk.mock.calls[0]![1].signal as AbortSignal
    wrapper.findAllComponents(ElButton)[1]!.vm.$emit('click'); await nextTick()
    expect(signal.aborted).toBe(true)
    expect(input.props('modelValue')).toBe('')
    input.vm.$emit('update:modelValue', 'second'); await nextTick()
    wrapper.findAllComponents(ElButton)[0]!.vm.$emit('click'); await nextTick()
    if (outcome === 'success') first.resolve(answer('first'))
    else first.reject(new Error('stale error'))
    await flushPromises()
    expect(wrapper.findAllComponents(ElButton)[0]!.props('loading')).toBe(true)
    expect(wrapper.find('.ai-result').exists()).toBe(false)
    expect(wrapper.find('.ai-error').exists()).toBe(false)
    second.resolve(answer('second')); await flushPromises()
    expect(wrapper.find('.ai-result-answer').text()).toBe('answer second')
    expect(wrapper.findAllComponents(ElButton)[0]!.props('loading')).toBe(false)
    wrapper.unmount()
  })

  it('aborts a pending answer when leaving the view', async () => {
    const pending = deferred()
    mocks.aiAsk.mockReturnValueOnce(pending.promise)
    const wrapper = shallowMount(AiAssistantView, { global: { renderStubDefaultSlot: true } })
    wrapper.findAllComponents(ElInput)[0]!.vm.$emit('update:modelValue', 'question'); await nextTick()
    wrapper.findAllComponents(ElButton)[0]!.vm.$emit('click'); await nextTick()
    const signal = mocks.aiAsk.mock.calls[0]![1].signal as AbortSignal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
    pending.reject(new Error('late failure')); await flushPromises()
  })
})


const investigation = (alertId: string): InvestigationResult => ({ investigationId: `job-${alertId}`, alertId,
  revision: 1, status: 'COMPLETED', analysis: `analysis ${alertId}`, recommendedSpl: '', timeline: [], hypotheses: [],
  citations: [], nextActions: [], degradedSources: [] })
const setupInvestigation = () => shallowMount(AiAssistantView, { global: { renderStubDefaultSlot: true } })

describe('AI investigation context', () => {
  it('reloads reused routes, cancels the old poll and rejects its late result', async () => {
    const a = deferred<InvestigationResult>(), b = deferred<InvestigationResult>()
    mocks.investigateAlert.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    mocks.route.query = { alarmId: 'a' }
    const wrapper = setupInvestigation()
    const signal = mocks.investigateAlert.mock.calls[0]![1].signal as AbortSignal
    mocks.route.query = { alarmId: 'b' }; await nextTick()
    expect(signal.aborted).toBe(true)
    expect(mocks.investigateAlert.mock.calls.map(call => call[0])).toEqual(['a', 'b'])
    b.resolve(investigation('b')); await flushPromises()
    a.resolve(investigation('a')); await flushPromises()
    expect(wrapper.find('.ai-analysis').text()).toBe('analysis b')
    mocks.route.query = {}; await nextTick()
    expect(wrapper.find('.ai-analysis').exists()).toBe(false)
    wrapper.unmount()
  })

  it('clears results on input edits and loads a submitted draft exactly once through the URL', async () => {
    mocks.route.query = { alertId: 'a' }
    mocks.investigateAlert.mockImplementation(async id => investigation(id))
    const wrapper = setupInvestigation(); await flushPromises()
    const panel = wrapper.find('.ai-investigation-panel')
    panel.findComponent(ElInput).vm.$emit('update:modelValue', 'b'); await nextTick()
    expect(wrapper.find('.ai-analysis').exists()).toBe(false)
    panel.findAllComponents(ElButton).find(button => button.props('link'))!.vm.$emit('click')
    expect(mocks.push).toHaveBeenCalledWith({ name: 'alarms', query: { alarmId: 'a' } })
    panel.findAllComponents(ElButton).find(button => !button.props('link') && button.props('type') === 'primary')!.vm.$emit('click')
    await flushPromises()
    expect(mocks.route.query).toEqual({ alarmId: 'b', alertId: undefined })
    expect(mocks.investigateAlert.mock.calls.map(call => call[0])).toEqual(['a', 'b'])
    expect(wrapper.find('.ai-analysis').text()).toBe('analysis b')
    wrapper.unmount()
  })

  it.each(['success', 'failure'])('does not apply an old append %s to the new investigation', async outcome => {
    mocks.route.query = { alarmId: 'a' }
    mocks.investigateAlert.mockImplementation(async id => investigation(id))
    const pending = deferred<InvestigationResult>()
    mocks.appendInvestigationToIncident.mockReturnValueOnce(pending.promise)
    const wrapper = setupInvestigation(); await flushPromises()
    const append = () => wrapper.findAllComponents(ElButton).find(button => button.props('type') === 'success')!
    append().vm.$emit('click'); await nextTick()
    expect(mocks.appendInvestigationToIncident).toHaveBeenCalledWith('job-a', undefined)
    mocks.route.query = { alarmId: 'b' }; await flushPromises()
    expect(wrapper.find('.ai-analysis').text()).toBe('analysis b')
    expect(append().props('disabled')).toBe(true)
    expect(append().props('loading')).toBe(false)
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
    if (outcome === 'success') pending.resolve({ ...investigation('a'), summaryAppended: true })
    else pending.reject(new Error('old append failed'))
    await flushPromises()
    expect(wrapper.find('.ai-analysis').text()).toBe('analysis b')
    expect(wrapper.find('.ai-error').exists()).toBe(false)
    expect(append().props('disabled')).toBeFalsy()
    wrapper.unmount()
  })

  it('fails closed on a response belonging to another alert', async () => {
    mocks.route.query = { alarmId: 'a' }
    mocks.investigateAlert.mockResolvedValue(investigation('foreign'))
    const wrapper = setupInvestigation(); await flushPromises()
    expect(wrapper.find('.ai-analysis').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(mocks.appendInvestigationToIncident).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('pins reanalysis to the displayed revision so a retried command is idempotent', async () => {
    mocks.route.query = { alarmId: 'a' }
    mocks.investigateAlert.mockResolvedValue({ ...investigation('a'), revision: 4 })
    mocks.reanalyzeAlert.mockResolvedValue({ ...investigation('a'), revision: 5 })
    const wrapper = setupInvestigation(); await flushPromises()

    const reanalyze = wrapper.find('.ai-investigation-panel').findAllComponents(ElButton)
      .find(button => !button.props('type') && !button.props('link'))!
    reanalyze.vm.$emit('click'); await flushPromises()

    expect(mocks.reanalyzeAlert).toHaveBeenCalledWith('a', 4, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(wrapper.find('.ai-analysis').text()).toBe('analysis a')
    wrapper.unmount()
  })
})

describe('AI evidence citations and resumable identity', () => {
  it('resumes the URL job, resolves an authoritative citation and opens suggested SPL as a draft', async () => {
    mocks.route.query = { alarmId: 'a', jobId: 'persisted-job', caseId: 'case-a' }
    mocks.investigateAlert.mockResolvedValue({ ...investigation('a'), citations: [{ id: 'alert:a', source: 'alert', label: 'Alarm' }] })
    mocks.getAlarm.mockResolvedValue({ id: 'a', message: 'authoritative alarm' })
    const wrapper = setupInvestigation(); await flushPromises()
    expect(mocks.investigateAlert).toHaveBeenCalledWith('a', expect.objectContaining({ jobId: 'persisted-job' }))
    const view = wrapper.vm as unknown as { inspectCitation: (id: string) => Promise<void>; citationEvidence: unknown; citationLoading: boolean; openSuggestedSearch: (query: string) => void }
    await view.inspectCitation('alert:a')
    expect(mocks.getAlarm).toHaveBeenCalledWith('a', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(view.citationEvidence).toMatchObject({ message: 'authoritative alarm' })
    await view.inspectCitation('missing-reference')
    expect(view.citationLoading).toBe(false)
    expect(view.citationEvidence).toBeNull()
    view.openSuggestedSearch('host="edge" | stats count')
    expect(mocks.push).toHaveBeenCalledWith(expect.objectContaining({ name: 'search', query: expect.objectContaining({ draft: 'host="edge" | stats count', alarmId: 'a', caseId: 'case-a' }) }))
    expect(mocks.splSearch).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('rejects a late citation read after switching to another alarm', async () => {
    const pending = deferred<{ id: string }>()
    mocks.route.query = { alarmId: 'a' }
    mocks.investigateAlert.mockImplementation(async id => ({ ...investigation(id), citations: [{ id: `alert:${id}`, label: 'Alarm', source: 'alert' }] }))
    mocks.getAlarm.mockReturnValue(pending.promise)
    const wrapper = setupInvestigation(); await flushPromises()
    const view = wrapper.vm as unknown as { inspectCitation: (id: string) => Promise<void>; citationEvidence: unknown }
    const inspection = view.inspectCitation('alert:a')
    const signal = mocks.getAlarm.mock.calls[0][1].signal as AbortSignal
    mocks.route.query = { alarmId: 'b' }; await flushPromises()
    expect(signal.aborted).toBe(true)
    pending.resolve({ id: 'a' }); await inspection
    expect(view.citationEvidence).toBeNull()
    wrapper.unmount()
  })
})
