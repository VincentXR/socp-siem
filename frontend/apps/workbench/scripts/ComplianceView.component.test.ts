import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ComplianceView from '../src/views/ComplianceView.vue'
import { translate } from '../src/i18n'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const api = vi.hoisted(() => ({ complianceFrameworks: vi.fn(), lookupRuleOptions: vi.fn(), complianceCoverage: vi.fn() }))
vi.mock('../src/api', async original => ({ ...await original<typeof import('../src/api')>(), ...api }))
const frameworks = { frameworks: [{ name: 'Framework', controls: [{ id: 'C1', name: 'Control', ruleIds: ['R1'] }] }] }
const coverage = { byFramework: [{ framework: 'Framework', controls: [{ id: 'C1', name: 'Control', mappedRules: ['R1'], assessment: 'gap', validUntil: 'invalid' }], coverage: 100 }],
  totalControls: 1, coveredControls: 1, coverage: 100, contentVersion: 'v1', generatedAt: 'invalid' }
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(yes => { resolve = yes })
  return { promise, resolve }
}
beforeEach(() => {
  vi.resetAllMocks()
  api.complianceFrameworks.mockResolvedValue(frameworks)
  api.lookupRuleOptions.mockResolvedValue([{ id: 'R1', status: 'ACTIVE' }])
  api.complianceCoverage.mockResolvedValue(coverage)
})

describe('compliance result ownership', () => {
  it('links readable mapped rule names to the exact editor while preserving control details', async () => {
    api.lookupRuleOptions.mockResolvedValue([{ id: 'R1', name: 'Readable rule name', status: 'ACTIVE' }])
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/compliance', component: ComplianceView },
      { path: '/detect/rules/:ruleId/edit', name: 'rule-edit', component: { template: '<p>Rule editor</p>' } },
    ] })
    await router.push('/compliance'); await router.isReady()
    const wrapper = mount(ComplianceView, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('analyst') } } } })
    await flushPromises()
    const ruleLink = wrapper.find('a[href="/detect/rules/R1/edit"]')
    expect(ruleLink.text()).toBe('Readable rule name')
    wrapper.findAllComponents(ElButton).find(node => node.text() === 'Control')!.vm.$emit('click'); await flushPromises()
    expect(wrapper.text()).toContain('C1 · Control')
    expect(wrapper.text()).toContain('Readable rule name')
    wrapper.findAllComponents(ElButton).find(node => node.text() === 'Readable rule name')!.vm.$emit('click'); await flushPromises()
    expect(router.currentRoute.value.name).toBe('rule-edit')
    expect(router.currentRoute.value.params.ruleId).toBe('R1')
    wrapper.unmount()
  })

  it.each(['complianceFrameworks', 'lookupRuleOptions', 'complianceCoverage'] as const)('retains the last complete result after %s refresh fails', async stage => {
    const wrapper = mount(ComplianceView); await flushPromises()
    const metrics = () => wrapper.findAll('.metric-card-value').map(node => node.text())
    expect(metrics()).toEqual(['100%', '1', '1', '0'])
    expect(wrapper.text()).not.toContain('Invalid Date')
    api[stage].mockRejectedValueOnce(new Error('refresh unavailable'))
    wrapper.findAllComponents(ElButton)[0]!.vm.$emit('click'); await flushPromises()
    expect(metrics()).toEqual(['100%', '1', '1', '0'])
    expect(wrapper.text()).toContain('refresh unavailable')
    expect(wrapper.text()).toContain(translate('compliance.staleResult'))
    wrapper.findAllComponents(ElButton)[0]!.vm.$emit('click'); await flushPromises()
    expect(wrapper.text()).not.toContain('refresh unavailable')
    expect(wrapper.text()).not.toContain(translate('compliance.staleResult'))
    wrapper.unmount()
  })

  it('keeps initial failure distinct from measured zero and disables recalculation until a result exists', async () => {
    api.complianceFrameworks.mockRejectedValueOnce(new Error('framework unavailable'))
    const wrapper = mount(ComplianceView); await flushPromises()
    expect(wrapper.findAll('.metric-card-value').map(node => node.text())).toEqual(Array(4).fill(translate('time.notAvailable')))
    expect(wrapper.findAllComponents(ElButton)[1]!.props('disabled')).toBe(true)
    expect(api.complianceCoverage).not.toHaveBeenCalled()
    wrapper.findAllComponents(ElButton)[0]!.vm.$emit('click'); await flushPromises()
    expect(wrapper.findAllComponents(ElButton)[1]!.props('disabled')).toBe(false)
    wrapper.unmount()
  })

  it('stops a batch chain after unmount even if an aborted transport resolves', async () => {
    api.complianceFrameworks.mockResolvedValue({ frameworks: [{ name: 'Framework', controls: [{ id: 'C1', name: 'Control', ruleIds: Array.from({ length: 201 }, (_, i) => `R${i}`) }] }] })
    const batch = deferred<Array<{ id: string; status: string }>>()
    api.lookupRuleOptions.mockReturnValueOnce(batch.promise)
    const wrapper = mount(ComplianceView); await flushPromises()
    const signal = api.lookupRuleOptions.mock.calls[0]![1].signal as AbortSignal
    wrapper.unmount(); expect(signal.aborted).toBe(true)
    batch.resolve([{ id: 'R1', status: 'ACTIVE' }]); await flushPromises()
    expect(api.lookupRuleOptions).toHaveBeenCalledTimes(1)
    expect(api.complianceCoverage).not.toHaveBeenCalled()
  })

  it('does not begin rule queries after a late framework response on an unmounted page', async () => {
    const pending = deferred<typeof frameworks>()
    api.complianceFrameworks.mockReturnValueOnce(pending.promise)
    const wrapper = mount(ComplianceView); await flushPromises()
    const signal = api.complianceFrameworks.mock.calls[0]![0].signal as AbortSignal
    wrapper.unmount(); expect(signal.aborted).toBe(true)
    pending.resolve(frameworks); await flushPromises()
    expect(api.lookupRuleOptions).not.toHaveBeenCalled()
  })
})
