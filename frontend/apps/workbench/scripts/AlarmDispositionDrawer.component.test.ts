import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import AlarmDispositionDrawer from '../src/components/AlarmDispositionDrawer.vue'

const mocks = vi.hoisted(() => ({
  listAlarmFeedback: vi.fn().mockResolvedValue([]), saveAlarmFeedback: vi.fn(), listSimilarAlarms: vi.fn().mockResolvedValue([]), claimAlarm: vi.fn(),
  getDisposition: vi.fn().mockResolvedValue({ status: 'OPEN', assignee: null, notes: [] }),
  getAlarmEvidence: vi.fn().mockResolvedValue({
    alarmId: 'alarm-1', total: 1, complete: true, query: 'eventId:evt-1',
    items: [{ id: 'evidence-1', eventId: 'evt-1', timestamp: '2026-08-25T00:00:00Z', source: 'syslog', host: 'host-1', severity: 'HIGH', raw: 'blocked', fields: {}, order: 0 }],
  }),
  getAlarmDeliveries: vi.fn().mockResolvedValue([]),
  requeueAlarmDelivery: vi.fn().mockResolvedValue({ id: 'delivery-1', status: 'PENDING', replayed: true }),
  setDispositionStatus: vi.fn().mockResolvedValue(undefined),
  assignAlarm: vi.fn().mockResolvedValue(undefined),
  addAlarmNote: vi.fn().mockResolvedValue(undefined),
}))
const routeState = vi.hoisted(() => ({ query: {} as Record<string, string>, fullPath: '/alarms?alarmId=alarm-1' }))
vi.mock('vue-router', () => ({ useRoute: () => routeState, useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))
const confirmation = vi.hoisted(() => ({
  confirmDanger: vi.fn().mockResolvedValue(true),
  promptInput: vi.fn().mockResolvedValue('channel credentials corrected; destination checked'),
}))

vi.mock('../src/api/alarms', () => ({
  ...mocks,
}))
vi.mock('../src/api/incidents', () => ({
  getCaseByAlarm: vi.fn().mockResolvedValue({
    id: 'case-1', title: 'Related case', entity: 'user:alice', severity: 'HIGH', status: 'OPEN',
    assignee: '', ruleIds: ['rule-1'], alarmIds: ['alarm-1'], timeline: [],
  }),
  createCaseFromAlarm: vi.fn(),
}))
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => confirmation }))

const alarm = {
  id: 'alarm-1', ruleId: 'rule-1', ruleName: 'Suspicious login', severity: 'HIGH',
  message: 'blocked', entity: 'user:alice', status: 'OPEN', occurredAt: '2026-08-25T00:00:00Z',
}

describe('AlarmDispositionDrawer', () => {

  it('drops a pending note result and clears its busy state after same-alarm close and reopen', async () => {
    let finish!: () => void
    mocks.addAlarmNote.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() } })
    await flushPromises()
    const view = wrapper.vm as unknown as { newNote: string; noteBusy: boolean; doAddNote: () => Promise<void> }
    view.newNote = 'old submitted note'
    const pending = view.doAddNote()
    await wrapper.setProps({ modelValue: false }); await wrapper.setProps({ modelValue: true }); await flushPromises()
    expect(view.noteBusy).toBe(false)
    view.newNote = 'new draft'
    const reads = mocks.getDisposition.mock.calls.length
    finish(); await pending; await flushPromises()
    expect(view.newNote).toBe('new draft')
    expect(mocks.getDisposition).toHaveBeenCalledTimes(reads)
    expect(wrapper.emitted('updated')).toBeUndefined()
    wrapper.unmount()
  })

  it('does not publish a pending note result after write permission is revoked', async () => {
    let finish!: () => void
    mocks.addAlarmNote.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() } })
    await flushPromises()
    const view = wrapper.vm as unknown as { newNote: string; noteBusy: boolean; doAddNote: () => Promise<void> }
    view.newNote = 'submitted note'
    const pending = view.doAddNote()
    await wrapper.setProps({ canWrite: false })
    const reads = mocks.getDisposition.mock.calls.length
    finish(); await pending; await flushPromises()
    expect(view.noteBusy).toBe(false)
    expect(mocks.getDisposition).toHaveBeenCalledTimes(reads)
    expect(wrapper.emitted('updated')).toBeUndefined()
    wrapper.unmount()
  })

  it('fences repeated delivery recovery confirmations and rechecks admin permission before sending', async () => {
    let confirm!: (value: boolean) => void
    confirmation.confirmDanger.mockImplementationOnce(() => new Promise<boolean>(resolve => { confirm = resolve }))
    const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn(), canAdmin: true } })
    await flushPromises()
    const delivery = { deliveryId: 'notify-dead', alarmId: 'alarm-1', destination: 'NOTIFY', status: 'DEAD', attempts: 1 }
    const view = wrapper.vm as unknown as { requeueDelivery: (value: typeof delivery) => Promise<void> }
    const pending = view.requeueDelivery(delivery)
    const repeated = view.requeueDelivery(delivery)
    await wrapper.setProps({ canAdmin: false })
    confirm(true); await Promise.all([pending, repeated]); await flushPromises()
    expect(confirmation.confirmDanger).toHaveBeenCalledOnce()
    expect(confirmation.promptInput).not.toHaveBeenCalled()
    expect(mocks.requeueAlarmDelivery).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('loads evidence and invokes status action from the drawer', async () => {
    const wrapper = mount(AlarmDispositionDrawer, {
      props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() },
    })
    await flushPromises()

    expect(mocks.getDisposition).toHaveBeenCalledWith('alarm-1')
    expect(mocks.getAlarmEvidence).toHaveBeenCalledWith('alarm-1')
    expect(mocks.getAlarmDeliveries).toHaveBeenCalledWith('alarm-1')
    expect(wrapper.text()).toContain('blocked')

    const updateButton = wrapper.findAll('button').find(button => button.text() === '更新')
    expect(updateButton).toBeTruthy()
    await updateButton!.trigger('click')
    expect(mocks.setDispositionStatus).toHaveBeenCalledWith('alarm-1', 'OPEN', '', 'UNDETERMINED')
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
    expect(goSearch).toHaveBeenCalledExactlyOnceWith('eventId:evt-1')
  })

  it('sends one Idempotency-Key per note and reuses it when the submit is retried', async () => {
    mocks.addAlarmNote.mockRejectedValueOnce(new Error('network down'))
    const wrapper = mount(AlarmDispositionDrawer, {
      props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() },
    })
    await flushPromises()

    const addButton = wrapper.findAll('button').find(button => button.text() === '添加')
    expect(addButton).toBeTruthy()
    await wrapper.find('input[placeholder="添加调查备注"]').setValue('  已确认是扫描流量  ')
    await addButton!.trigger('click')
    await flushPromises()
    expect(mocks.addAlarmNote).toHaveBeenCalledTimes(1)
    const [alarmId, content, , idempotencyKey] = mocks.addAlarmNote.mock.calls[0]
    expect(alarmId).toBe('alarm-1')
    expect(content).toBe('已确认是扫描流量')
    expect(String(idempotencyKey)).toMatch(/^workbench-note-/)

    await addButton!.trigger('click')
    await flushPromises()
    expect(mocks.addAlarmNote).toHaveBeenCalledTimes(2)
    expect(mocks.addAlarmNote.mock.calls[1][3]).toBe(idempotencyKey)
  })

  it('only exposes AI investigation to operators allowed to write', async () => {
    const goAi = vi.fn()
    const wrapper = mount(AlarmDispositionDrawer, {
      props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn(), goAi, canWrite: false },
    })
    await flushPromises()
    const aiButton = () => wrapper.findAll('button').find(button => button.text().includes('AI'))
    expect(aiButton()).toBeUndefined()
    await wrapper.setProps({ canWrite: true })
    expect(aiButton()).toBeDefined()
    await aiButton()!.trigger('click')
    expect(goAi).toHaveBeenCalledExactlyOnceWith('alarm-1', 'case-1')
    wrapper.unmount()
  })

  it('does not let a late note response for alarm A clear alarm B draft or refresh B', async () => {
    let finishA!: () => void
    mocks.addAlarmNote.mockImplementationOnce(() => new Promise<void>((resolve) => { finishA = resolve }))
    const wrapper = mount(AlarmDispositionDrawer, {
      props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() },
    })
    await flushPromises()
    const noteInput = () => wrapper.find('input[placeholder="添加调查备注"]')
    await noteInput().setValue('A note')
    const addButton = wrapper.findAll('button').find(button => button.text() === '添加')
    await addButton!.trigger('click')
    await flushPromises()

    const alarmB = { ...alarm, id: 'alarm-2', message: 'second alarm' }
    await wrapper.setProps({ alarm: alarmB })
    await flushPromises()
    await noteInput().setValue('B draft must survive')
    const dispositionCallsBeforeLateResponse = mocks.getDisposition.mock.calls.length

    finishA()
    await flushPromises()

    expect(mocks.addAlarmNote).toHaveBeenCalledWith(
      'alarm-1', 'A note', 'operator', expect.stringMatching(/^workbench-note-/),
    )
    expect(mocks.getDisposition.mock.calls.length).toBe(dispositionCallsBeforeLateResponse)
    expect((noteInput().element as HTMLInputElement).value).toBe('B draft must survive')
  })

  it('requires an operator verification record before requeueing a notification delivery', async () => {
    const delivery = { deliveryId: 'notify-dead', alarmId: 'alarm-1', destination: 'NOTIFY',
      status: 'DEAD', attempts: 1, lastError: 'result unknown' }
    mocks.getAlarmDeliveries.mockResolvedValueOnce([delivery]).mockResolvedValueOnce([{ ...delivery, status: 'PENDING' }])
    const wrapper = mount(AlarmDispositionDrawer, {
      props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn(), canAdmin: true },
    })
    await flushPromises()

    await (wrapper.vm as unknown as { requeueDelivery: (value: typeof delivery) => Promise<void> }).requeueDelivery(delivery)
    await flushPromises()

    expect(confirmation.confirmDanger).toHaveBeenCalledOnce()
    expect(confirmation.promptInput).toHaveBeenCalledOnce()
    expect(mocks.requeueAlarmDelivery).toHaveBeenCalledWith('notify-dead', {
      reason: 'channel credentials corrected; destination checked', confirmUnknown: true,
    })
    wrapper.unmount()
  })

})


it('loads feedback on a deep-linked verdict tab and keeps expiry on a saved review', async () => {
  routeState.query.tab = 'feedback'
  mocks.listAlarmFeedback.mockResolvedValueOnce([{ id: 'review-1', kind: 'FALSE_POSITIVE', reason: 'Previous review' }])
  const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() } })
  await flushPromises()
  const view = wrapper.vm as unknown as { feedback: Array<{ reason: string }>; feedbackLoading: boolean; feedbackReason: string; feedbackExpiry: string; submitFeedback: () => Promise<void> }
  expect(mocks.listAlarmFeedback).toHaveBeenCalledWith('alarm-1')
  expect(view.feedback).toMatchObject([{ reason: 'Previous review' }])
  expect(view.feedbackLoading).toBe(false)
  view.feedbackReason = 'Updated review'
  view.feedbackExpiry = '2099-10-01T12:00'
  mocks.saveAlarmFeedback.mockResolvedValueOnce({ id: 'review-1', kind: 'FALSE_POSITIVE', reason: 'Updated review' })
  await view.submitFeedback()
  expect(mocks.saveAlarmFeedback).toHaveBeenCalledWith('alarm-1', { kind: 'FALSE_POSITIVE', reason: 'Updated review', expiresAt: new Date('2099-10-01T12:00').toISOString() })
  expect(view.feedback).toMatchObject([{ reason: 'Updated review' }])
  expect(view.feedback).toHaveLength(1)
  wrapper.unmount()
  routeState.query = {}
})

it('ignores stale feedback after the same alarm is closed and reopened', async () => {
  let finish!: (value: unknown[]) => void
  routeState.query.tab = 'feedback'
  mocks.listAlarmFeedback.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() } })
  await flushPromises()
  await wrapper.setProps({ modelValue: false })
  mocks.listAlarmFeedback.mockResolvedValueOnce([{ id: 'new-review', kind: 'FALSE_POSITIVE', reason: 'Current review' }])
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  finish([{ id: 'stale-review', kind: 'FALSE_POSITIVE', reason: 'Old review' }])
  await flushPromises()
  const view = wrapper.vm as unknown as { feedback: Array<{ reason: string }>; feedbackLoading: boolean }
  expect(view.feedback).toMatchObject([{ reason: 'Current review' }])
  expect(view.feedbackLoading).toBe(false)
  wrapper.unmount()
  routeState.query = {}
})

it('drops a submitted review response after write permission is revoked', async () => {
  let finish!: (value: unknown) => void
  routeState.query.tab = 'feedback'
  mocks.saveAlarmFeedback.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() } })
  await flushPromises()
  const view = wrapper.vm as unknown as { feedback: unknown[]; feedbackBusy: boolean; feedbackReason: string; submitFeedback: () => Promise<void> }
  view.feedbackReason = 'Submitted review'
  const pending = view.submitFeedback()
  await wrapper.setProps({ canWrite: false })
  finish({ id: 'old-review', kind: 'FALSE_POSITIVE', reason: 'Submitted review' })
  await pending
  expect(view.feedback).toEqual([])
  expect(view.feedbackBusy).toBe(false)
  wrapper.unmount()
  routeState.query = {}
})
