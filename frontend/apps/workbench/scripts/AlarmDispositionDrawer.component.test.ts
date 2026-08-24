import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import AlarmDispositionDrawer from '../src/components/AlarmDispositionDrawer.vue'

const mocks = vi.hoisted(() => ({
  getDisposition: vi.fn().mockResolvedValue({ status: 'OPEN', assignee: null, notes: [] }),
  getAlarmEvidence: vi.fn().mockResolvedValue({
    alarmId: 'alarm-1', total: 1, complete: true, query: 'eventId:evt-1',
    items: [{ id: 'evidence-1', eventId: 'evt-1', timestamp: '2026-08-25T00:00:00Z', source: 'syslog', host: 'host-1', severity: 'HIGH', raw: 'blocked', fields: {}, order: 0 }],
  }),
  setDispositionStatus: vi.fn().mockResolvedValue(undefined),
  assignAlarm: vi.fn().mockResolvedValue(undefined),
  addAlarmNote: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('../src/api/alarms', () => ({
  ...mocks,
}))
vi.mock('../src/api/incidents', () => ({
  listCases: vi.fn().mockResolvedValue([]),
}))

const alarm = {
  id: 'alarm-1', ruleId: 'rule-1', ruleName: 'Suspicious login', severity: 'HIGH',
  message: 'blocked', entity: 'user:alice', status: 'OPEN', occurredAt: '2026-08-25T00:00:00Z',
}

describe('AlarmDispositionDrawer', () => {
  it('loads evidence and invokes status action from the drawer', async () => {
    const wrapper = mount(AlarmDispositionDrawer, {
      props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() },
    })
    await flushPromises()

    expect(mocks.getDisposition).toHaveBeenCalledWith('alarm-1')
    expect(mocks.getAlarmEvidence).toHaveBeenCalledWith('alarm-1')
    expect(wrapper.text()).toContain('blocked')

    const updateButton = wrapper.findAll('button').find(button => button.text() === '更新')
    expect(updateButton).toBeTruthy()
    await updateButton!.trigger('click')
    expect(mocks.setDispositionStatus).toHaveBeenCalledWith('alarm-1', 'OPEN')
  })

  it('opens the evidence query in the search view', async () => {
    const goSearch = vi.fn()
    const wrapper = mount(AlarmDispositionDrawer, {
      props: { modelValue: true, alarm, goCase: vi.fn(), goSearch },
    })
    await flushPromises()

    const searchButton = wrapper.findAll('button').find(button => button.text() === '在日志检索中打开')
    expect(searchButton).toBeTruthy()
    await searchButton!.trigger('click')
    expect(window.sessionStorage.getItem('socp.search.query')).toBe('eventId:evt-1')
    expect(goSearch).toHaveBeenCalledOnce()
  })
})
