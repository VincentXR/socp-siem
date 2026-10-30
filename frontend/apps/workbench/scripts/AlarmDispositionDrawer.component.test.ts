import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AlarmDispositionDrawer from '../src/components/AlarmDispositionDrawer.vue'
import { ApiError } from '../src/api/core'

enableAutoUnmount(afterEach)

const mocks = vi.hoisted(() => ({
  listAlarmSuppressions: vi.fn().mockResolvedValue([]), recordAlarmSuppression: vi.fn(), releaseAlarmSuppression: vi.fn(),
  listAlarmFeedback: vi.fn().mockResolvedValue([]), saveAlarmFeedback: vi.fn(), listSimilarAlarms: vi.fn().mockResolvedValue([]), claimAlarm: vi.fn(),
  getDisposition: vi.fn().mockResolvedValue({ status: 'OPEN', assignee: null, notes: [] }),
  getAlarmEvidence: vi.fn().mockResolvedValue({
    alarmId: 'alarm-1', total: 1, complete: true, query: 'eventId:evt-1',
    items: [{ id: 'evidence-1', eventId: 'evt-1', timestamp: '2026-08-25T00:00:00Z', source: 'syslog', host: 'host-1', severity: 'HIGH', raw: 'blocked', fields: {}, order: 0 }],
  }),
  getAlarmDeliveries: vi.fn().mockResolvedValue([]),
  requeueAlarmDelivery: vi.fn().mockResolvedValue({ id: 'delivery-1', status: 'PENDING', replayed: true }),
  setDispositionStatus: vi.fn().mockResolvedValue({ status: 'OPEN', assignee: null, notes: [] }),
  assignAlarm: vi.fn().mockResolvedValue({ status: 'OPEN', assignee: 'analyst', notes: [] }),
  addAlarmNote: vi.fn().mockResolvedValue({ status: 'OPEN', assignee: null, notes: [] }),
}))
const routeState = vi.hoisted(() => ({ query: {} as Record<string, string>, fullPath: '/alarms?alarmId=alarm-1' }))
vi.mock('vue-router', () => ({ onBeforeRouteUpdate: vi.fn(), onBeforeRouteLeave: vi.fn(), useRoute: () => routeState, useRouter: () => ({ push: vi.fn(), replace: vi.fn(), beforeEach: () => () => {} }) }))
const confirmation = vi.hoisted(() => ({
  discard: vi.fn().mockRejectedValue(new Error('keep editing')),
  confirmDanger: vi.fn().mockResolvedValue(true),
  promptInput: vi.fn().mockResolvedValue('channel credentials corrected; destination checked'),
}))
const caseMocks = vi.hoisted(() => ({
  getCaseByAlarm: vi.fn().mockResolvedValue({
    id: 'case-1', title: 'Related case', entity: 'user:alice', severity: 'HIGH', status: 'OPEN',
    assignee: '', ruleIds: ['rule-1'], alarmIds: ['alarm-1'], timeline: [],
  }),
  createCaseFromAlarm: vi.fn(),
}))

vi.mock('../src/api/alarms', () => ({
  ...mocks,
}))
vi.mock('../src/api/incidents', () => caseMocks)
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => confirmation }))
vi.mock('element-plus/es/components/message-box/index.mjs', () => ({ default: { confirm: confirmation.discard } }))

const alarm = {
  id: 'alarm-1', ruleId: 'rule-1', ruleName: 'Suspicious login', severity: 'HIGH',
  message: 'blocked', entity: 'user:alice', status: 'OPEN', occurredAt: '2026-08-25T00:00:00Z',
}

describe('AlarmDispositionDrawer', () => {
  it.each(['linked', 'detached'] as const)('retains a created case receipt after read failure, retries only the read, and accepts a later %s result', async state => {
    caseMocks.getCaseByAlarm.mockRejectedValueOnce(new ApiError(404, 'No linked case'))
      .mockRejectedValueOnce(new Error('Case detail temporarily unavailable'))
      .mockResolvedValueOnce(state === 'linked' ? { id: 'case-created', title: 'Recovered case', entity: alarm.entity, severity: 'HIGH',
        status: 'OPEN', assignee: '', ruleIds: [alarm.ruleId], alarmIds: [alarm.id], timeline: [] } : null)
    caseMocks.createCaseFromAlarm.mockResolvedValueOnce({ caseId: 'case-created', created: true })
    const goCase = vi.fn()
    const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase, goSearch: vi.fn() } })
    await flushPromises()
    const view = wrapper.vm as unknown as { createCase: () => Promise<void>; retryDetails: () => Promise<void>
      actionError: string; relatedCaseError: string; linkedCaseId: string; newNote: string }
    await view.createCase(); await flushPromises()
    expect(view.actionError).toBe('')
    expect(view.linkedCaseId).toBe('case-created')
    expect(view.relatedCaseError).not.toBe('')
    expect(wrapper.findAll('button').some(button => button.text() === '从告警创建案件')).toBe(false)
    const openCase = wrapper.findAll('button').find(button => button.text() === '前往案件')
    expect(openCase).toBeTruthy()
    await openCase!.trigger('click')
    expect(goCase).toHaveBeenCalledExactlyOnceWith('case-created')
    await view.createCase()
    expect(caseMocks.createCaseFromAlarm).toHaveBeenCalledTimes(1)
    view.newNote = 'Keep the investigation draft'
    await view.retryDetails(); await flushPromises()
    expect(view.relatedCaseError).toBe('')
    expect(view.linkedCaseId).toBe(state === 'linked' ? 'case-created' : '')
    if (state === 'linked') expect(wrapper.text()).toContain('Recovered case')
    else expect(wrapper.findAll('button').some(button => button.text() === '从告警创建案件')).toBe(true)
    expect(view.newNote).toBe('Keep the investigation draft')
    expect(caseMocks.createCaseFromAlarm).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('does not publish a created case detail for alarm A after the drawer switches to alarm B', async () => {
    let finishRead!: (value: unknown) => void
    const caseB = { id: 'case-b', title: 'Alarm B case', entity: alarm.entity, severity: 'HIGH',
      status: 'OPEN', assignee: '', ruleIds: [alarm.ruleId], alarmIds: ['alarm-b'], timeline: [] }
    caseMocks.getCaseByAlarm.mockRejectedValueOnce(new ApiError(404, 'No linked case'))
      .mockImplementationOnce(() => new Promise(resolve => { finishRead = resolve }))
      .mockResolvedValueOnce(caseB)
    caseMocks.createCaseFromAlarm.mockResolvedValueOnce({ caseId: 'case-a', created: true })
    const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() } })
    await flushPromises()
    const view = wrapper.vm as unknown as { createCase: () => Promise<void>; linkedCaseId: string; creatingCase: boolean }
    const pending = view.createCase(); await flushPromises()
    expect(view.linkedCaseId).toBe('case-a')
    await wrapper.setProps({ alarm: { ...alarm, id: 'alarm-b' } }); await flushPromises()
    expect(view.linkedCaseId).toBe('case-b')
    finishRead({ ...caseB, id: 'case-a', title: 'Alarm A case', alarmIds: [alarm.id] })
    await pending; await flushPromises()
    expect(view.linkedCaseId).toBe('case-b')
    expect(view.creatingCase).toBe(false)
    expect(wrapper.text()).toContain('Alarm B case')
    expect(wrapper.text()).not.toContain('Alarm A case')
    expect(caseMocks.getCaseByAlarm).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })

  it('retains the acknowledged replay state after a read failure and cannot requeue the stale DEAD row', async () => {
    const delivery = { deliveryId: 'delivery-recovery', alarmId: alarm.id, destination: 'SOAR', status: 'DEAD', attempts: 4, lastError: 'Old failure' }
    const pending = { ...delivery, status: 'PENDING', attempts: 0, nextAttemptAt: '2026-10-22T00:00:00Z', lastError: '' }
    mocks.getAlarmDeliveries.mockResolvedValueOnce([delivery])
      .mockRejectedValueOnce(new Error('Delivery detail temporarily unavailable'))
      .mockResolvedValueOnce([pending])
    mocks.requeueAlarmDelivery.mockResolvedValueOnce({ id: delivery.deliveryId, type: 'ALARM_DELIVERY', tenantId: 'default',
      status: 'PENDING', requeuedAt: pending.nextAttemptAt })
    const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn(), canAdmin: true } })
    await flushPromises()
    const view = wrapper.vm as unknown as { requeueDelivery: (row: typeof delivery) => Promise<void>; retryDetails: () => Promise<void>
      actionError: string; deliveriesError: string; deliveries: Array<typeof pending>; activeTab: string }
    view.activeTab = 'delivery'
    await view.requeueDelivery(delivery); await flushPromises()
    expect(view.actionError).toBe('')
    expect(view.deliveries).toMatchObject([{ deliveryId: delivery.deliveryId, status: 'PENDING', attempts: 0, lastError: '' }])
    expect(view.deliveriesError).not.toBe('')
    expect(wrapper.find('.delivery-card').exists()).toBe(true)
    expect(wrapper.findAll('button').some(button => button.text() === '重新投递')).toBe(false)
    await view.requeueDelivery(delivery)
    expect(mocks.requeueAlarmDelivery).toHaveBeenCalledTimes(1)
    await view.retryDetails()
    expect(view.deliveriesError).toBe('')
    expect(mocks.requeueAlarmDelivery).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it.each(['status', 'assign', 'note'] as const)('applies the acknowledged %s write without requiring another disposition read', async operation => {
    const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() } })
    await flushPromises()
    const receipt = { status: operation === 'status' ? 'RESOLVED' : 'OPEN', assignee: 'analyst',
      notes: [{ author: 'analyst', content: 'confirmed evidence', at: '2026-10-22T00:00:00Z' }] }
    const api = operation === 'status' ? mocks.setDispositionStatus : operation === 'assign' ? mocks.assignAlarm : mocks.addAlarmNote
    api.mockResolvedValueOnce(receipt)
    const view = wrapper.vm as unknown as {
      newStatus: string; newAssignee: string; newNote: string; closureReason: string; actionError: string
      disposition: typeof receipt; changeStatus: () => Promise<void>; doAssign: () => Promise<void>; doAddNote: () => Promise<void>
    }
    view.newStatus = operation === 'status' ? 'RESOLVED' : 'INVESTIGATING'
    view.newAssignee = 'analyst'; view.newNote = 'confirmed evidence'; view.closureReason = 'evidence reviewed'
    const reads = mocks.getDisposition.mock.calls.length
    mocks.getDisposition.mockRejectedValue(new Error('read unavailable after committed write'))
    try {
      await (operation === 'status' ? view.changeStatus() : operation === 'assign' ? view.doAssign() : view.doAddNote())
      expect(view.disposition).toEqual(receipt)
      expect(view.actionError).toBe('')
      expect(wrapper.emitted('updated')).toHaveLength(1)
      expect(mocks.getDisposition).toHaveBeenCalledTimes(reads)
      expect(view.newStatus).toBe(operation === 'status' ? 'RESOLVED' : 'INVESTIGATING')
      expect(view.newNote).toBe(operation === 'note' ? '' : 'confirmed evidence')
      expect(view.newAssignee).toBe(operation === 'assign' ? '' : 'analyst')
    } finally {
      mocks.getDisposition.mockResolvedValue({ status: 'OPEN', assignee: null, notes: [] })
      wrapper.unmount()
    }
  })

  it('retries only failed evidence while preserving investigation drafts and successful disposition data', async () => {
    mocks.getAlarmEvidence.mockRejectedValueOnce(new Error('evidence temporarily unavailable'))
    const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, canWrite: true, goCase: vi.fn(), goSearch: vi.fn() } })
    await flushPromises()
    const view = wrapper.vm as unknown as { newNote: string; closureReason: string; feedbackReason: string; retryDetails: () => Promise<void>; beforeClose: (done: () => void) => Promise<void> }
    view.newNote = 'unsaved investigation'; view.closureReason = 'pending evidence'; view.feedbackReason = 'analyst feedback'
    const reads = mocks.getDisposition.mock.calls.length
    await view.retryDetails(); await flushPromises()
    expect(mocks.getDisposition).toHaveBeenCalledTimes(reads)
    expect(view.newNote).toBe('unsaved investigation')
    expect(view.closureReason).toBe('pending evidence')
    expect(view.feedbackReason).toBe('analyst feedback')
    const beforeUnload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(beforeUnload)
    expect(beforeUnload.defaultPrevented).toBe(true)
    const closed = vi.fn()
    await view.beforeClose(closed)
    expect(confirmation.discard).toHaveBeenCalled()
    expect(closed).not.toHaveBeenCalled()
    expect(view.newNote).toBe('unsaved investigation')
    wrapper.unmount()
  })

  it('preserves a 409 reason and refreshes reachable targets rather than suggesting the same invalid move', async () => {
    mocks.getDisposition.mockResolvedValueOnce({ status: 'OPEN', assignee: null, notes: [] })
    mocks.setDispositionStatus.mockRejectedValueOnce(new ApiError(409, 'Cannot move CLOSED to OPEN; choose INVESTIGATING'))
    const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn() } })
    await flushPromises()
    mocks.getDisposition.mockResolvedValueOnce({ status: 'CLOSED', assignee: null, notes: [], allowedTransitions: ['CLOSED', 'INVESTIGATING'] })
    const view = wrapper.vm as unknown as { newStatus: string; actionError: string; DISP_STATUSES: string[]; changeStatus: () => Promise<void> }
    await view.changeStatus(); await flushPromises()
    expect(view.actionError).toContain('Cannot move CLOSED')
    expect(view.newStatus).toBe('CLOSED')
    expect(view.DISP_STATUSES).toEqual(['CLOSED', 'INVESTIGATING'])
    wrapper.unmount()
  })


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
    const delivery = { deliveryId: 'notify-dead', alarmId: 'alarm-1', destination: 'NOTIFY', status: 'DEAD', attempts: 1 }
    mocks.getAlarmDeliveries.mockResolvedValueOnce([delivery])
    const wrapper = mount(AlarmDispositionDrawer, { props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn(), canAdmin: true } })
    await flushPromises()
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

    const updateButton = wrapper.findAll('button').find(button => button.text() === '保存状态')
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

    const addButton = wrapper.findAll('button').find(button => button.text() === '添加备注')
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

  it('only exposes AI investigation and restricted asset context to operators allowed to write', async () => {
    const goAi = vi.fn()
    const wrapper = mount(AlarmDispositionDrawer, {
      props: { modelValue: true, alarm, goCase: vi.fn(), goSearch: vi.fn(), goAi, canWrite: false },
    })
    await flushPromises()
    const aiButton = () => wrapper.findAll('button').find(button => button.text().includes('AI'))
    const assetButton = () => wrapper.findAll('button').find(button => button.text() === '资产')
    expect(aiButton()).toBeUndefined()
    expect(assetButton()).toBeUndefined()
    await wrapper.setProps({ canWrite: true })
    expect(aiButton()).toBeDefined()
    expect(assetButton()).toBeDefined()
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
    const addButton = wrapper.findAll('button').find(button => button.text() === '添加备注')
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
