import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import Panel from '../src/components/AlarmSuppressionPanel.vue'

const api = vi.hoisted(() => ({ listAlarmSuppressions: vi.fn(), recordAlarmSuppression: vi.fn(), releaseAlarmSuppression: vi.fn() }))
const confirm = vi.hoisted(() => vi.fn())
vi.mock('../src/api/alarms', () => api)
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => ({ confirmDanger: confirm }) }))
const props = { alarmId: 'alarm-a', ruleId: 'rule-1', entity: 'host-a', canWrite: true, disabled: false }
beforeEach(() => {
  vi.resetAllMocks()
  api.listAlarmSuppressions.mockResolvedValue([])
  api.recordAlarmSuppression.mockResolvedValue({})
  api.releaseAlarmSuppression.mockResolvedValue({})
  confirm.mockResolvedValue(true)
})
const createButton = (wrapper: ReturnType<typeof mount>) => wrapper.findAll('button').find(button => button.text().includes('确认创建'))!

it('requires explicit confirmation with exact entity and selected duration before creating a window', async () => {
  const wrapper = mount(Panel, { props }); await flushPromises()
  await wrapper.find('textarea').setValue('scanner evidence')
  await wrapper.find('select').setValue('1')
  confirm.mockResolvedValueOnce(false)
  await createButton(wrapper).trigger('click'); await flushPromises()
  expect(api.recordAlarmSuppression).not.toHaveBeenCalled()
  expect(wrapper.find('textarea').element.value).toBe('scanner evidence')
  await createButton(wrapper).trigger('click'); await flushPromises()
  expect(confirm).toHaveBeenLastCalledWith(expect.stringContaining('host-a'))
  expect(api.recordAlarmSuppression).toHaveBeenCalledExactlyOnceWith({
    alarmId: 'alarm-a', ruleId: 'rule-1', entity: 'host-a', ruleWide: false, reason: 'scanner evidence', windowSeconds: 3600,
  })
  wrapper.unmount()
})

it('does not expand a missing entity to rule-wide suppression without explicit selection', async () => {
  const wrapper = mount(Panel, { props: { ...props, entity: null } }); await flushPromises()
  await wrapper.find('textarea').setValue('rule-wide evidence')
  expect(createButton(wrapper).attributes('disabled')).toBeDefined()
  await wrapper.find('input[type=checkbox]').setValue(true)
  await createButton(wrapper).trigger('click'); await flushPromises()
  expect(api.recordAlarmSuppression).toHaveBeenCalledWith(expect.objectContaining({ ruleWide: true, entity: undefined }))
  expect(confirm).toHaveBeenCalledWith(expect.stringContaining('所有实体'))
  wrapper.unmount()
})

it('lists the effective scopes and releases only the selected window after confirmation', async () => {
  api.listAlarmSuppressions.mockResolvedValue([
    { id: 'one', ruleId: 'rule-1', entity: null, reason: 'maintenance', expiresAt: '2026-10-06T00:00:00Z' },
    { id: 'other', ruleId: 'rule-1', entity: 'host-b', reason: 'unrelated', expiresAt: '2026-10-06T00:00:00Z' },
  ])
  const wrapper = mount(Panel, { props }); await flushPromises()
  expect(wrapper.text()).toContain('maintenance')
  expect(wrapper.text()).not.toContain('unrelated')
  await wrapper.findAll('button').find(button => button.text() === '解除静默窗口')!.trigger('click')
  await flushPromises()
  expect(api.releaseAlarmSuppression).toHaveBeenCalledExactlyOnceWith('rule-1', null)
  wrapper.unmount()
})

it('does not send a confirmed request after switching alarms or losing permission', async () => {
  let finish!: (value: boolean) => void
  confirm.mockImplementationOnce(() => new Promise<boolean>(resolve => { finish = resolve }))
  const wrapper = mount(Panel, { props }); await flushPromises()
  await wrapper.find('textarea').setValue('old draft')
  await createButton(wrapper).trigger('click'); await flushPromises()
  await wrapper.setProps({ alarmId: 'alarm-b', entity: 'host-b' }); await flushPromises()
  await wrapper.find('textarea').setValue('new draft')
  finish(true); await flushPromises()
  expect(api.recordAlarmSuppression).not.toHaveBeenCalled()
  expect(wrapper.find('textarea').element.value).toBe('new draft')
  await wrapper.setProps({ canWrite: false }); await flushPromises()
  expect(wrapper.find('textarea').exists()).toBe(false)
  wrapper.unmount()
})
