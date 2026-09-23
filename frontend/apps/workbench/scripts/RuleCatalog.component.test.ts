import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DetectView from '../src/views/DetectView.vue'
import ComplianceView from '../src/views/ComplianceView.vue'
import AttackView from '../src/views/AttackView.vue'
import PagerBar from '../src/components/PagerBar.vue'
import FieldConditionBuilder from '../src/components/FieldConditionBuilder.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const api = vi.hoisted(() => ({
  listRulePage: vi.fn(), getRule: vi.fn(), gasStats: vi.fn(), listFields: vi.fn(), listWatchlists: vi.fn(),
  listTechniques: vi.fn(), listTactics: vi.fn(), activeRuleTechniques: vi.fn(), attackCoverage: vi.fn(),
  complianceFrameworks: vi.fn(), lookupRuleOptions: vi.fn(), complianceCoverage: vi.fn(),
}))
vi.mock('../src/api', async original => ({ ...await original<object>(), ...api }))
let wrapper: VueWrapper | undefined
const row = { id: 'rule-501', name: 'Late catalog rule', type: 'pattern', status: 'DISABLED', enabled: false, match: [] }
const page = { items: [row], total: 501, page: 26, size: 20, totalPages: 26 }

beforeEach(() => {
  api.listRulePage.mockResolvedValue(page)
  api.getRule.mockResolvedValue(row)
  api.gasStats.mockResolvedValue({ rules: 501 })
  api.listFields.mockResolvedValue([])
  api.listWatchlists.mockResolvedValue([])
  api.listTechniques.mockResolvedValue({ items: [] })
  api.listTactics.mockResolvedValue({ items: [] })
})
afterEach(() => { wrapper?.unmount(); vi.resetAllMocks() })

async function openRules(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/detect', name: 'detect', component: DetectView },
    { path: '/detect/rules/:ruleId/edit', name: 'rule-edit', component: DetectView, meta: { editor: true } },
  ] })
  await router.push(path)
  wrapper = mount(RouterView, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } } })
  await flushPromises()
  return router
}

describe('complete rule catalogue', () => {
  it('uses server pagination and applies filters to the entire catalogue, resetting the page', async () => {
    const router = await openRules('/detect?page=26&size=20&status=DISABLED')
    expect(api.listRulePage).toHaveBeenLastCalledWith({ page: 26, size: 20, status: 'DISABLED', q: undefined }, expect.any(Object))
    expect(wrapper!.text()).toContain('Late catalog rule')
    expect(wrapper!.findComponent(PagerBar).props('total')).toBe(501)
    await wrapper!.find('.rule-search input').setValue('Unicode 异常_%')
    await wrapper!.find('.rule-search').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.query.page).toBeUndefined()
    expect(api.listRulePage).toHaveBeenLastCalledWith({ page: 1, size: 20, status: 'DISABLED', q: 'Unicode 异常_%' }, expect.any(Object))
  })

  it('opens a deep link independently of the list and keeps return filters', async () => {
    const router = await openRules('/detect/rules/rule-501/edit?page=26&q=Late')
    expect(api.getRule).toHaveBeenCalledWith('rule-501', expect.any(Object))
    expect(api.listRulePage).not.toHaveBeenCalled()
    expect(wrapper!.find('input').element.value).toBe('Late catalog rule')
    await router.push('/detect?page=26&q=Late')
    await flushPromises()
    expect(api.listRulePage).toHaveBeenCalledWith(expect.objectContaining({ page: 26, q: 'Late' }), expect.any(Object))
  })

  it('merges a size change and the pager page reset into one URL update', async () => {
    const router = await openRules('/detect?page=26&size=20')
    const pager = wrapper!.findComponent(PagerBar)
    pager.vm.$emit('update:pageSize', 50)
    pager.vm.$emit('update:currentPage', 1)
    await flushPromises()
    expect(router.currentRoute.value.query.size).toBe('50')
    expect(router.currentRoute.value.query.page).toBeUndefined()
    expect(api.listRulePage).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 50 }), expect.any(Object))
  })

  it('filters references across the catalogue and loads the actual executable watchlists', async () => {
    api.listWatchlists.mockResolvedValue([{ name: 'privileged_accounts', size: 1 }])
    const router = await openRules('/detect?reference=privileged_accounts')
    expect(api.listRulePage).toHaveBeenCalledWith(expect.objectContaining({ reference: 'privileged_accounts' }), expect.any(Object))
    expect(wrapper!.text()).toContain('privileged_accounts')
    await router.push('/detect/rules/rule-501/edit?reference=privileged_accounts')
    await flushPromises()
    expect(wrapper!.findComponent(FieldConditionBuilder).props('referenceSets')).toEqual([
      { id: 'privileged_accounts', name: 'privileged_accounts', description: '', entries: [], size: 1 },
    ])
  })

  it('ignores a late failed list response after a newer search succeeds', async () => {
    let reject!: (reason: Error) => void
    api.listRulePage.mockReturnValueOnce(new Promise((_, fail) => { reject = fail }))
    const router = await openRules('/detect?q=old')
    await router.push('/detect?q=current')
    await flushPromises()
    reject(new Error('stale failure'))
    await flushPromises()
    expect(wrapper!.text()).toContain('Late catalog rule')
    expect(wrapper!.text()).not.toContain('stale failure')
  })

  it('looks up every framework mapping in bounded batches, including rule 501', async () => {
    const ids = Array.from({ length: 501 }, (_, i) => `rule-${i + 1}`)
    api.complianceFrameworks.mockResolvedValue({ frameworks: [{ name: 'Framework', controls: [{ id: 'C1', name: 'Control', ruleIds: ids }] }] })
    api.lookupRuleOptions.mockImplementation(async (batch: string[]) => batch.includes('rule-501') ? [{ ...row, status: 'ACTIVE' }] : [])
    api.complianceCoverage.mockResolvedValue({ byFramework: [{ framework: 'Framework', controls: [{ id: 'C1', name: 'Control', mappedRules: ['rule-501'] }], coverage: 100 }], coverage: 100 })
    wrapper = mount(ComplianceView)
    await flushPromises()
    expect(api.lookupRuleOptions).toHaveBeenCalledTimes(6)
    expect(api.lookupRuleOptions.mock.calls.flatMap(call => call[0])).toEqual(ids)
    expect(api.lookupRuleOptions.mock.calls.every(call => call[0].length <= 100)).toBe(true)
    expect(api.complianceCoverage).toHaveBeenCalledWith(['rule-501'], expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(wrapper.text()).toContain('已启用')
    expect(api.listRulePage).not.toHaveBeenCalled()
  })

  it('preserves framework load failure instead of showing empty successful coverage', async () => {
    api.complianceFrameworks.mockRejectedValue(new Error('Framework unavailable'))
    wrapper = mount(ComplianceView)
    await flushPromises()
    expect(wrapper.text()).toContain('Framework unavailable')
    expect(api.lookupRuleOptions).not.toHaveBeenCalled()
    expect(api.complianceCoverage).not.toHaveBeenCalled()
  })

  it('computes ATT&CK coverage from the complete ACTIVE technique projection', async () => {
    api.activeRuleTechniques.mockResolvedValue(['T1110', 'T1059.001'])
    api.attackCoverage.mockResolvedValue({ uncovered: [], coverage: 100, covered: 2, total: 2, byTactic: [] })
    wrapper = mount(AttackView)
    await flushPromises()
    expect(api.attackCoverage).toHaveBeenCalledWith(['T1110', 'T1059.001'], expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(api.listRulePage).not.toHaveBeenCalled()
  })
})
