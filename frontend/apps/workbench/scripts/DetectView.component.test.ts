import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
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
