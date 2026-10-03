import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable } from 'element-plus/es/components/table/index.mjs'
import PagerBar from '../src/components/PagerBar.vue'
import AttackView from '../src/views/AttackView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
import { translate } from '../src/i18n'
const api = vi.hoisted(() => ({ alarmTechniqueCounts: vi.fn(), listTactics: vi.fn(), listTechniques: vi.fn(), activeRuleTechniques: vi.fn(), attackCoverage: vi.fn(), getTechniqueNote: vi.fn(), saveTechniqueNote: vi.fn(), detailRules: vi.fn(), detailAlarms: vi.fn(), push: vi.fn(), success: vi.fn(), warning: vi.fn(), info: vi.fn() }))
vi.mock('../src/api', async original => ({ ...await original<typeof import('../src/api')>(), ...api }))
vi.mock('../src/api/core', async original => ({ ...await original<typeof import('../src/api/core')>(), get: api.detailRules }))
vi.mock('../src/api/investigation-context', () => ({ techniqueAlarms: api.detailAlarms }))
vi.mock('vue-router', async original => ({ ...await original<typeof import('vue-router')>(), useRouter: () => ({ push: api.push }) }))
vi.mock('element-plus/es/components/message/index.mjs', () => ({ default: api }))
const first = { id: 'T1', name: 'First technique', tactic: 'A', url: 'https://example.com/T1', description: '' }
const second = { ...first, id: 'T2', name: 'Second technique', tactic: 'B' }
const cov = { coverage: 50, coveredTechniques: 1, totalTechniques: 2, uncovered: [], byTactic: [] }
const setup = () => mount(AttackView, { global: { provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('analyst') } } } })
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(yes => { resolve = yes }); return { promise, resolve } }
function button(wrapper: ReturnType<typeof setup>, key: string) { return wrapper.findAllComponents(ElButton).find(node => node.text() === translate(key))! }
beforeEach(() => {
  vi.resetAllMocks()
  api.alarmTechniqueCounts.mockResolvedValue({ from: '2026-09-16T00:00:00Z', until: '2026-09-23T00:00:00Z', counts: { T1: 151, T2: 0 } })
  api.listTactics.mockResolvedValue({ items: [{ id: 'A', name: 'Alpha' }, { id: 'B', name: 'Beta' }] })
  api.listTechniques.mockResolvedValue({ items: [first, second] })
  api.activeRuleTechniques.mockResolvedValue(['T1'])
  api.attackCoverage.mockResolvedValue(cov)
  api.getTechniqueNote.mockResolvedValue({ note: 'Saved note' })
  api.saveTechniqueNote.mockResolvedValue({ note: 'Saved note' })
  api.detailRules.mockResolvedValue({ items: [{ id: 'rule-1', name: 'Related rule', status: 'ACTIVE' }], total: 25 })
  api.detailAlarms.mockResolvedValue({ items: [{ id: 'alarm-1', ruleName: 'Related rule', entity: 'host-1', occurredAt: '2026-09-23T00:00:00Z' }], total: 1 })
})
describe('ATT&CK request ownership', () => {
  it('keeps the contextual technique drawer while paging all mapped rules and preserving pivots', async () => {
    const wrapper = setup(); await flushPromises()
    await wrapper.find('.am-cell').trigger('click'); await flushPromises()
    expect(api.detailRules).toHaveBeenLastCalledWith('/detect-web/api/v1/rules/by-technique?technique=T1&page=1&size=20', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(api.detailAlarms).toHaveBeenCalledWith('T1', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(wrapper.findComponent(PagerBar).props('total')).toBe(25)
    wrapper.findComponent(PagerBar).vm.$emit('update:currentPage', 2); await flushPromises()
    expect(api.detailRules).toHaveBeenLastCalledWith('/detect-web/api/v1/rules/by-technique?technique=T1&page=2&size=20', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    button(wrapper, 'workflow.relatedAlarms').vm.$emit('click')
    expect(api.push).toHaveBeenLastCalledWith({ name: 'alarms', query: { rule: 'rule-1', technique: 'T1' } })
    wrapper.findAllComponents(ElButton).find(node => node.text() === 'Related rule')!.vm.$emit('click')
    expect(api.push).toHaveBeenLastCalledWith({ name: 'rule-edit', params: { ruleId: 'rule-1' } })
    expect(wrapper.text()).toContain('Saved note')
    await wrapper.findAll('.am-cell')[1]!.trigger('click'); await flushPromises()
    expect(api.detailRules).toHaveBeenLastCalledWith('/detect-web/api/v1/rules/by-technique?technique=T2&page=1&size=20', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    wrapper.unmount()
  })

  it('cancels paged rule reads on close and ignores their late results for another technique', async () => {
    const wrapper = setup(); await flushPromises()
    await wrapper.find('.am-cell').trigger('click'); await flushPromises()
    const pending = deferred<{ items: Array<{ id: string; name: string; status: string }>; total: number }>()
    api.detailRules.mockReturnValueOnce(pending.promise)
    wrapper.findComponent(PagerBar).vm.$emit('update:currentPage', 2); await flushPromises()
    const signal = api.detailRules.mock.calls.at(-1)![1].signal as AbortSignal
    const view = wrapper.vm as unknown as { closeTechnique: () => void }
    view.closeTechnique(); await flushPromises()
    expect(signal.aborted).toBe(true)
    await wrapper.findAll('.am-cell')[1]!.trigger('click'); await flushPromises()
    pending.resolve({ items: [{ id: 'old-rule', name: 'Obsolete rule', status: 'ACTIVE' }], total: 80 }); await flushPromises()
    expect(wrapper.text()).not.toContain('Obsolete rule')
    expect(wrapper.findComponent(PagerBar).props('total')).toBe(25)
    wrapper.unmount()
  })
  it('loads exact activity directly and retains it on refresh failure', async () => {
    const wrapper = setup(); await flushPromises()
    expect(api.alarmTechniqueCounts).toHaveBeenCalledWith(['T1', 'T2'], expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(wrapper.find('.am-badge').text()).toBe('151')
    api.alarmTechniqueCounts.mockRejectedValueOnce(new Error('activity unavailable'))
    button(wrapper, 'attack.refreshActivity').vm.$emit('click'); await flushPromises()
    expect(wrapper.find('.am-badge').text()).toBe('151')
    expect(wrapper.text()).toContain(translate('attack.staleActivity'))
    wrapper.unmount()
  })
  it('ignores late activity counts when the tactic changes', async () => {
    const pending = deferred<{ from: string; until: string; counts: Record<string, number> }>()
    api.alarmTechniqueCounts.mockReturnValueOnce(pending.promise).mockResolvedValueOnce({ from: '2026-09-16T00:00:00Z', until: '2026-09-23T00:00:00Z', counts: { T2: 7 } })
    const wrapper = setup(); await flushPromises()
    const signal = api.alarmTechniqueCounts.mock.calls[0]![1].signal as AbortSignal
    api.listTechniques.mockResolvedValueOnce({ items: [second] })
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'B'); await flushPromises()
    expect(signal.aborted).toBe(true)
    pending.resolve({ from: '2026-09-16T00:00:00Z', until: '2026-09-23T00:00:00Z', counts: { T1: 999 } }); await flushPromises()
    expect(wrapper.findAll('.am-badge').map(node => node.text())).toEqual(['7'])
    wrapper.unmount()
  })
  it('ignores an old filter response and does not reload global coverage per filter', async () => {
    const wrapper = setup(); await flushPromises()
    const old = deferred<{ items: typeof first[] }>()
    api.listTechniques.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ items: [second] })
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'A'); await flushPromises()
    const signal = api.listTechniques.mock.calls.at(-1)![1].signal as AbortSignal
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'B'); await flushPromises()
    expect(signal.aborted).toBe(true)
    old.resolve({ items: [first] }); await flushPromises()
    expect(wrapper.findComponent(ElTable).props('data')).toEqual([second])
    expect(wrapper.findAll('.am-head').map(node => node.text())).toEqual(['Beta0/1'])
    expect(api.attackCoverage).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it('retains coverage on failure and does not classify unknown catalogue IDs as covered', async () => {
    const wrapper = setup(); await flushPromises()
    expect(wrapper.findAll('.am-cell')[1]!.classes()).not.toContain('am-cell--covered')
    api.activeRuleTechniques.mockRejectedValueOnce(new Error('coverage unavailable'))
    button(wrapper, 'attack.refreshCoverage').vm.$emit('click'); await flushPromises()
    expect(wrapper.text()).toContain('50%')
    expect(wrapper.text()).toContain(translate('attack.staleCoverage'))
    wrapper.unmount()
  })
  it('stops the coverage chain after leaving even if the transport ignores abort', async () => {
    const pending = deferred<string[]>()
    api.activeRuleTechniques.mockReturnValueOnce(pending.promise)
    const wrapper = setup(); await flushPromises()
    const signal = api.activeRuleTechniques.mock.calls[0]![0].signal as AbortSignal
    wrapper.unmount(); expect(signal.aborted).toBe(true)
    pending.resolve(['T1']); await flushPromises()
    expect(api.attackCoverage).not.toHaveBeenCalled()
  })
  it('can close a loading note and ignores its response after a different note opens', async () => {
    const pending = deferred<{ note: string }>()
    api.getTechniqueNote.mockReturnValueOnce(pending.promise).mockResolvedValueOnce({ note: 'Second saved note' })
    const wrapper = setup(); await flushPromises()
    button(wrapper, 'forms.note').vm.$emit('click'); await flushPromises()
    const signal = api.getTechniqueNote.mock.calls[0]![1].signal as AbortSignal
    button(wrapper, 'common.cancel').vm.$emit('click'); await flushPromises()
    expect(wrapper.findAllComponents(ElDialog).find(dialog => dialog.findAllComponents(ElInput).length)!.props('modelValue')).toBe(false)
    expect(signal.aborted).toBe(true)
    wrapper.findAllComponents(ElButton).filter(node => node.text() === translate('forms.note'))[1]!.vm.$emit('click'); await flushPromises()
    pending.resolve({ note: 'Old note' }); await flushPromises()
    expect(wrapper.findAllComponents(ElDialog).find(dialog => dialog.findAllComponents(ElInput).length)!.findAllComponents(ElInput).at(-1)!.props('modelValue')).toBe('Second saved note')
    wrapper.unmount()
  })
  it('allows correcting and retrying a failed note save while keeping pending writes guarded', async () => {
    const pending = deferred<{ note: string }>()
    api.saveTechniqueNote.mockRejectedValueOnce(new Error('write unavailable')).mockReturnValueOnce(pending.promise)
    const wrapper = setup(); await flushPromises()
    button(wrapper, 'forms.note').vm.$emit('click'); await flushPromises()
    const input = wrapper.findAllComponents(ElDialog).find(dialog => dialog.findAllComponents(ElInput).length)!.findAllComponents(ElInput).at(-1)!
    input.vm.$emit('update:modelValue', 'Draft one')
    button(wrapper, 'common.save').vm.$emit('click'); await flushPromises()
    expect(input.props('disabled')).toBe(false)
    expect(input.props('modelValue')).toBe('Draft one')
    input.vm.$emit('update:modelValue', 'Corrected draft')
    button(wrapper, 'common.save').vm.$emit('click'); await flushPromises()
    button(wrapper, 'common.cancel').vm.$emit('click'); await flushPromises()
    expect(wrapper.findAllComponents(ElDialog).find(dialog => dialog.findAllComponents(ElInput).length)!.props('modelValue')).toBe(true)
    expect(api.saveTechniqueNote).toHaveBeenLastCalledWith('T1', 'Corrected draft')
    pending.resolve({ note: 'Corrected draft' }); await flushPromises()
    expect(wrapper.findAllComponents(ElDialog).find(dialog => dialog.findAllComponents(ElInput).length)!.props('modelValue')).toBe(false)
    expect(api.listTechniques).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
