import { mount, flushPromises } from '@vue/test-utils'
import { h, ref } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import DetectView from '../src/views/DetectView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const mocks = vi.hoisted(() => ({
  listRules: vi.fn().mockResolvedValue([{ id: 'original', name: 'Existing rule', type: 'pattern', severity: 'HIGH', status: 'DISABLED', enabled: false, match: [{ field: 'msg', op: 'eq', value: 'alert' }] }]),
  gasStats: vi.fn().mockResolvedValue({ rules: 1, queueLoad: 0 }),
  listFields: vi.fn().mockResolvedValue([]), listRefSets: vi.fn().mockResolvedValue([]), listTechniques: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 0, totalPages: 0 }),
  createGasRule: vi.fn().mockResolvedValue(null), updateGasRule: vi.fn(),
  testGasRules: vi.fn().mockResolvedValue([]),
}))
vi.mock('../src/api', async importOriginal => ({ ...await importOriginal<object>(), ...mocks }))

describe('rule editor identity', () => {
  it('preserves extension fields and legacy exclusions through a visual save', async () => {
    const rule = { id: 'roundtrip', name: 'Roundtrip', type: 'correlation', severity: 'HIGH', enabled: false, status: 'DRAFT',
      window: '60s', keyField: 'host', groupBy: 'host', routingField: 'host',
      lateEventPolicy: { allowedLateness: '90s', handling: 'ACCEPT', source: 'content-pack' },
      evidence: { fields: ['host', 'msg'] },
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
      groupBy: 'host', lateEventPolicy: rule.lateEventPolicy,
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
    // The refusing message names the block, and the row itself is marked.
    expect(wrapper.text()).toContain('条件未填写完整：需要字段、操作符和值。 全部满足')
    expect(wrapper.find('.field-condition-error').text()).toContain('条件未填写完整')
    expect(wrapper.find('.field-condition-row-invalid').exists()).toBe(true)
    wrapper.unmount()
  })

  it('prunes blank condition rows, saves, and states how many were dropped', async () => {
    mocks.listRules.mockResolvedValueOnce([{ id: 'blank', name: 'Blank rows', type: 'pattern', severity: 'HIGH', enabled: false, status: 'DRAFT',
      match: [{ field: '', op: 'eq', value: '' }, { field: 'msg', op: 'eq', value: 'alert' }] }])
    mocks.updateGasRule.mockResolvedValueOnce(null)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/detect/rules/:ruleId/edit', name: 'rule-edit', component: DetectView, meta: { editor: true } }] })
    await router.push('/detect/rules/blank/edit')
    await router.isReady()
    const wrapper = mount({ render: () => h(RouterView) }, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } } })
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '保存')!.trigger('click')
    await flushPromises()
    // Blank rows are pruned rather than blocking the save; the payload keeps
    // only the real condition and the success notice states the drop count.
    expect(mocks.updateGasRule).toHaveBeenCalledTimes(1)
    const spec = mocks.updateGasRule.mock.calls[0][1]
    expect(spec.match).toHaveLength(1)
    expect(spec.match[0].value).toBe('alert')
    expect(wrapper.find('.detect-feedback.notice').text()).toContain('已忽略 1 个空条件')
    // Only half-typed conditions are marked inline; a blank row is not one.
    expect(wrapper.findAll('.field-condition-row-invalid').length).toBe(0)
    wrapper.unmount()
  })

  it('validates only the correlation steps a correlation editor actually shows', async () => {
    mocks.listRules.mockResolvedValueOnce([{ id: 'corr', name: 'Correlation', type: 'correlation', severity: 'HIGH', enabled: false, status: 'DRAFT',
      window: '60s', groupBy: 'host',
      steps: [[{ field: 'msg', op: 'eq', value: 'start' }], [{ field: 'msg', op: 'eq', value: '' }]],
      match: [{ field: 'msg', op: 'eq', value: '' }] }])
    mocks.updateGasRule.mockResolvedValueOnce(null)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/detect/rules/:ruleId/edit', name: 'rule-edit', component: DetectView, meta: { editor: true } }] })
    await router.push('/detect/rules/corr/edit')
    await router.isReady()
    const wrapper = mount({ render: () => h(RouterView) }, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } } })
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '保存')!.trigger('click')
    await flushPromises()
    // The unrendered match block cannot block the save; the half-typed step can.
    expect(mocks.updateGasRule).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('条件未填写完整：需要字段、操作符和值。 关联步骤')
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

  it('keeps a directly opened rule editor read-only for viewers', async () => {
    mocks.listRules.mockResolvedValueOnce([{ id: 'viewer-rule', name: 'Viewer rule', type: 'pattern', severity: 'HIGH', status: 'DISABLED', enabled: false, match: [{ field: 'msg', op: 'eq', value: 'alert' }] }])
    mocks.updateGasRule.mockClear()
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/detect/rules/:ruleId/edit', name: 'rule-edit', component: DetectView, meta: { editor: true } }] })
    await router.push('/detect/rules/viewer-rule/edit')
    await router.isReady()
    const wrapper = mount({ render: () => h(RouterView) }, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('viewer') } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('当前角色仅可查看规则和测试结果')
    expect(wrapper.findAll('button').some(button => button.text() === '保存')).toBe(false)
    expect(wrapper.find('textarea').attributes('readonly')).toBeDefined()
    wrapper.unmount()
  })
})
