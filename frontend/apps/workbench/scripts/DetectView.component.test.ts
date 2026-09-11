import { mount, flushPromises } from '@vue/test-utils'
import { h, ref } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import DetectView from '../src/views/DetectView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const mocks = vi.hoisted(() => ({
  listRules: vi.fn().mockResolvedValue([{ id: 'original', name: 'Existing rule', type: 'pattern', severity: 'HIGH', status: 'DISABLED', enabled: false, match: [{ field: 'msg', op: 'eq', value: 'alert' }] }]),
  gasStats: vi.fn().mockResolvedValue({ rules: 1, queueLoad: 0 }),
  listFields: vi.fn().mockResolvedValue([]), listRefSets: vi.fn().mockResolvedValue([]), listTechniques: vi.fn().mockResolvedValue([]),
  createGasRule: vi.fn().mockResolvedValue(null), updateGasRule: vi.fn(),
  testGasRules: vi.fn().mockResolvedValue([]),
}))
vi.mock('../src/api', async importOriginal => ({ ...await importOriginal<object>(), ...mocks }))

describe('rule editor identity', () => {
  it('preserves extension fields and legacy exclusions through a visual save', async () => {
    const rule = { id: 'roundtrip', name: 'Roundtrip', type: 'correlation', severity: 'HIGH', enabled: false, status: 'DRAFT',
      window: '60s', keyField: 'host', routingField: 'host', evidence: { fields: ['host', 'msg'] },
      steps: [[{ field: 'msg', op: 'eq', value: ' start ', annotation: { source: 'import' } }], [{ field: 'msg', op: 'eq', value: ' end ', flags: ['preserve'] }]],
      alert: { title: 'Title', description: 'Description', grouping: { strategy: 'source' } },
      allowlist: [{ field: 'host', op: 'eq', value: 'trusted', source: 'import' }],
    }
    mocks.listRules.mockResolvedValueOnce([rule])
    mocks.updateGasRule.mockResolvedValueOnce(rule)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/detect/rules/:ruleId/edit', name: 'rule-edit', component: DetectView, meta: { editor: true } }] })
    await router.push('/detect/rules/roundtrip/edit')
    await router.isReady()
    const wrapper = mount({ render: () => h(RouterView) }, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } } })
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '保存')!.trigger('click')
    await flushPromises()
    expect(mocks.updateGasRule).toHaveBeenCalledWith('roundtrip', expect.objectContaining({
      evidence: rule.evidence, alert: rule.alert, steps: rule.steps, whitelist: rule.allowlist, match: [],
    }))
    expect(mocks.updateGasRule.mock.calls[0][1]).not.toHaveProperty('allowlist')
    wrapper.unmount()
  })

  it('reports reference catalog errors and refuses to silently remove incomplete conditions', async () => {
    mocks.listRules.mockResolvedValueOnce([{ id: 'incomplete', name: 'Incomplete', type: 'pattern', severity: 'HIGH', enabled: false,
      match: [{ field: 'msg', op: 'contains', value: '' }] }])
    mocks.listRefSets.mockRejectedValueOnce(new Error('Reference catalog unavailable'))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/detect/rules/:ruleId/edit', name: 'rule-edit', component: DetectView, meta: { editor: true } }] })
    await router.push('/detect/rules/incomplete/edit')
    await router.isReady()
    const wrapper = mount({ render: () => h(RouterView) }, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('Reference catalog unavailable')
    await wrapper.findAll('button').find(item => item.text() === '保存')!.trigger('click')
    await flushPromises()
    expect(mocks.updateGasRule).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请填写必填字段')
    wrapper.unmount()
  })

  it('applying copied JSON cannot update the source rule', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/detect', name: 'detect', component: DetectView }, { path: '/detect/rules/new', name: 'rule-new', component: DetectView, meta: { editor: true } }, { path: '/detect/rules/:ruleId/edit', name: 'rule-edit', component: DetectView, meta: { editor: true } }] })
    await router.push('/detect')
    await router.isReady()
    const wrapper = mount(DetectView, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } } })
    await flushPromises()
    const click = async (text: string) => {
      const button = wrapper.findAll('button').find(item => item.text() === text)
      expect(button, text).toBeTruthy()
      await button!.trigger('click')
      await flushPromises()
    }
    await click('复制')
    const apply = wrapper.find('.advanced-json button')
    expect(apply.exists()).toBe(true)
    await apply.trigger('click')
    await click('保存')
    expect(mocks.createGasRule).toHaveBeenCalledWith(expect.objectContaining({ name: 'Existing rule · copy', enabled: false }))
    expect(mocks.createGasRule.mock.calls[0][0]).not.toHaveProperty('id')
    expect(mocks.updateGasRule).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
