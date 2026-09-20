import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

// Neutralise the chart: UebaRiskPanel mounts an echarts bar whose real init
// needs a canvas. The a11y behaviour under test lives in the entity table, not
// the chart, so a stub loader keeps the mount deterministic in jsdom.
vi.mock('../src/lib/echarts', () => ({
  loadEcharts: async () => ({
    init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), isDisposed: () => false }),
  }),
}))

import UebaRiskPanel from '../src/components/ueba/UebaRiskPanel.vue'

const entities = [
  { entity: 'host-a', risk: 80, level: 'HIGH', critical: false, maxSeverity: 'HIGH', alerts: 3, mitre: [], lastSeen: null },
  { entity: 'host-b', risk: 55, level: 'MEDIUM', critical: true, maxSeverity: 'MEDIUM', alerts: 1, mitre: [], lastSeen: null },
]

describe('UebaRiskPanel row keyboard accessibility', () => {
  it('exposes each entity as a keyboard-activatable cell that emits select', async () => {
    const wrapper = mount(UebaRiskPanel, { props: { theme: 'dark', entities, summary: null, riskLimit: 20 } })
    await flushPromises()
    const cells = wrapper.findAll('.row-activate')
    expect(cells).toHaveLength(2)
    expect(cells[0].attributes('role')).toBe('button')
    expect(cells[0].attributes('tabindex')).toBe('0')
    await cells[0].trigger('keydown', { key: 'Enter' })
    const select = wrapper.emitted('select')
    expect(select).toBeTruthy()
    expect(select![0][0]).toMatchObject({ entity: 'host-a' })
    wrapper.unmount()
  })
})
