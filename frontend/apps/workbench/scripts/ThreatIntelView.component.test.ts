import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import ThreatIntelView from '../src/views/ThreatIntelView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const mocks = vi.hoisted(() => ({
  threatIntelApi: {
    list: vi.fn().mockResolvedValue({ items: [], total: 0 }),
    stats: vi.fn().mockResolvedValue({ total: 7, byType: { IP: 4, DOMAIN: 2, SHA256: 1 } }),
    match: vi.fn(), create: vi.fn(), update: vi.fn(), setRevoked: vi.fn(), bulkImport: vi.fn(), remove: vi.fn(),
  },
}))

vi.mock('../src/api/domains', () => mocks)

function mountView() {
  return mount(ThreatIntelView, {
    global: { provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } },
  })
}

describe('threat intel metric cards', () => {
  it('renders one shared metric card per reported type plus the total', async () => {
    const wrapper = mountView()
    await flushPromises()
    const cards = wrapper.findAll('.ti-metrics .metric-card')
    expect(cards).toHaveLength(4)
    expect(cards[0].find('.metric-card-label').text()).toBe('情报总量')
    expect(cards[0].find('.metric-card-value').text()).toBe('7')
    expect(cards[0].classes()).toContain('metric-card--info')
    expect(cards[1].find('.metric-card-label').text()).toBe('IP')
    expect(cards[1].find('.metric-card-value').text()).toBe('4')
    expect(cards[1].classes()).toContain('metric-card--neutral')
    // The legacy hand-rolled card bar wrapped every metric in an el-card.
    expect(wrapper.findAll('.ti-metrics .el-card')).toHaveLength(0)
    wrapper.unmount()
  })

  it('follows the number of types returned by the statistics endpoint', async () => {
    mocks.threatIntelApi.stats.mockResolvedValueOnce({ total: 2, byType: { URL: 2 } })
    const wrapper = mountView()
    await flushPromises()
    const cards = wrapper.findAll('.ti-metrics .metric-card')
    expect(cards).toHaveLength(2)
    expect(cards[1].text()).toBe('URL2')
    wrapper.unmount()
  })
})
