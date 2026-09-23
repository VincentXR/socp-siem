import { mount, flushPromises } from '@vue/test-utils'
import { h, ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ElSelect from 'element-plus/es/components/select/index.mjs'
import CasesView from '../src/views/CasesView.vue'
import PagerBar from '../src/components/PagerBar.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
import type { CaseInfo, Paged, TimelineEvent } from '../src/api/models'

const mocks = vi.hoisted(() => ({
  list: vi.fn(), get: vi.fn(), stats: vi.fn(), timeline: vi.fn(), create: vi.fn(), updateStatus: vi.fn(), export: vi.fn(),
  success: vi.fn(), info: vi.fn(), warning: vi.fn(), confirm: vi.fn(),
}))
vi.mock('../src/api/domains', async original => ({ ...await original<object>(), caseApi: mocks }))
vi.mock('element-plus/es/components/message/index.mjs', () => ({ default: { success: mocks.success, info: mocks.info, warning: mocks.warning } }))
vi.mock('element-plus/es/components/message-box/index.mjs', () => ({ default: { confirm: mocks.confirm } }))

const incident = (id: string): CaseInfo => ({ id, title: `Case ${id}`, entity: 'host-one', severity: 'HIGH', status: 'OPEN',
  assignee: 'alice', ruleIds: ['rule-one'], alarmIds: ['alarm-one'], timeline: [] })
const events = (message: string, total = 1): Paged<TimelineEvent> => ({
  items: [{ ts: '2026-09-21T01:00:00Z', type: 'NOTE', source: 'analyst', message }], total,
})
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
let wrapper: ReturnType<typeof mount>
async function open(path = '/cases?caseId=outside', role = 'analyst') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { name: 'case', path: '/cases', component: CasesView },
    { name: 'alarms', path: '/alarms', component: { template: '<p>Alarms</p>' } },
  ] })
  await router.push(path)
  await router.isReady()
  wrapper = mount({ render: () => h(RouterView) }, { attachTo: document.body, global: {
    plugins: [router], provide: { [WORKBENCH_STATE as symbol]: {
      currentRole: ref(role), currentUser: ref('alice'), operatorOptions: ref(['alice', 'bob']),
    } },
  } })
  await flushPromises()
  return router
}
async function click(label: string) {
  const button = wrapper.findAll('button').find(item => item.text() === label)
  expect(button, label).toBeTruthy()
  await button!.trigger('click'); await flushPromises()
}
async function setStatus(status: string) {
  wrapper.find('.case-status-row').findComponent(ElSelect).vm.$emit('update:modelValue', status)
  await flushPromises()
}

describe('case selection, drafts and timeline', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.list.mockResolvedValue({ items: [incident('listed')], total: 120 })
    mocks.stats.mockResolvedValue({ total: 120, open: 120, resolved: 0 })
    mocks.get.mockImplementation(async id => ({ found: true, case: incident(id) }))
    mocks.timeline.mockImplementation(async id => events(`Timeline ${id}`))
    mocks.updateStatus.mockImplementation(async (id, status, assignee) => ({ case: { ...incident(id), status, assignee } }))
    mocks.create.mockResolvedValue({ case: incident('created') })
    mocks.confirm.mockResolvedValue('confirm')
  })
  afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

  it('opens a case outside the list page even when the list fails, and retries a missing detail', async () => {
    mocks.list.mockRejectedValue(new Error('List unavailable'))
    mocks.get.mockResolvedValueOnce({ found: false, case: {} })
    await open()
    expect(mocks.get).toHaveBeenCalledWith('outside', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(wrapper.find('.el-drawer').text()).toContain('未找到此案件')
    const retry = wrapper.find('.el-drawer').findAll('button').find(item => item.text() === '重试')!
    await retry.trigger('click'); await flushPromises()
    expect(wrapper.find('.el-drawer').text()).toContain('Case outside')
    expect(wrapper.find('.el-drawer').text()).toContain('Timeline outside')
    expect(wrapper.text()).toContain('List unavailable')
  })

  it('discards detail and timeline responses belonging to previous selections', async () => {
    const oldDetail = deferred<{ found: boolean; case: CaseInfo }>()
    const oldTimeline = deferred<Paged<TimelineEvent>>()
    mocks.get.mockReturnValueOnce(oldDetail.promise)
    const router = await open('/cases?caseId=first')
    mocks.timeline.mockReturnValueOnce(oldTimeline.promise)
    await router.push('/cases?caseId=second'); await flushPromises()
    await router.push('/cases?caseId=third'); await flushPromises()
    oldDetail.resolve({ found: true, case: incident('first') })
    oldTimeline.reject(new Error('Obsolete timeline failure'))
    await flushPromises()
    const drawer = wrapper.find('.el-drawer')
    expect(drawer.text()).toContain('Case third')
    expect(drawer.text()).toContain('Timeline third')
    expect(drawer.text()).not.toContain('Case first')
    expect(drawer.text()).not.toContain('Obsolete timeline failure')
    expect(mocks.get.mock.calls[0][1].signal.aborted).toBe(true)
  })

  it('loads bounded timeline pages and exposes retry without replacing case fields', async () => {
    mocks.timeline.mockResolvedValueOnce(events('Page one', 121)).mockRejectedValueOnce(new Error('Timeline unavailable')).mockResolvedValueOnce(events('Page two', 121))
    await open()
    expect(mocks.timeline).toHaveBeenLastCalledWith('outside', 1, 20, expect.anything())
    await setStatus('INVESTIGATING')
    wrapper.find('.el-drawer').findComponent(PagerBar).vm.$emit('update:currentPage', 2)
    await flushPromises()
    expect(mocks.timeline).toHaveBeenLastCalledWith('outside', 2, 20, expect.anything())
    expect(wrapper.find('.el-drawer').text()).toContain('Timeline unavailable')
    await click('重试')
    expect(wrapper.find('.el-drawer').text()).toContain('Page two')
    expect(wrapper.find('.el-drawer').text()).not.toContain('Page one')
    expect(wrapper.find('.case-status-row').findComponent(ElSelect).props('modelValue')).toBe('INVESTIGATING')
  })

  it('confirms a dirty case switch or close, but preserves the draft across list filter navigation', async () => {
    const router = await open()
    await setStatus('CONTAINED')
    await router.push('/cases?caseId=outside&q=needle&page=2'); await flushPromises()
    expect(mocks.confirm).not.toHaveBeenCalled()
    expect(wrapper.find('.case-status-row').findComponent(ElSelect).props('modelValue')).toBe('CONTAINED')
    expect(wrapper.find('input[placeholder="搜索案件 ID / 标题 / 实体"]').element).toHaveProperty('value', 'needle')
    mocks.confirm.mockRejectedValueOnce(new Error('keep editing'))
    await router.push('/cases?caseId=other&q=needle&page=2'); await flushPromises()
    expect(router.currentRoute.value.query.caseId).toBe('outside')
    await router.push('/cases?caseId=other&q=needle&page=2'); await flushPromises()
    expect(router.currentRoute.value.query.caseId).toBe('other')
    expect(wrapper.find('.el-drawer').text()).toContain('Case other')
    await setStatus('CLOSED')
    mocks.confirm.mockRejectedValueOnce(new Error('keep editing'))
    await wrapper.find('.el-drawer__close-btn').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query.caseId).toBe('other')
    expect(mocks.confirm).toHaveBeenCalledTimes(3)
  })

  it('freezes a status write, blocks duplicate submissions and navigation, then retains a rejected draft', async () => {
    const pending = deferred<{ case: CaseInfo }>()
    mocks.updateStatus.mockReturnValueOnce(pending.promise)
    const router = await open()
    await setStatus('CONTAINED')
    await click('更新状态')
    expect(wrapper.find('.case-status-row input').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.el-descriptions input').attributes('disabled')).toBeDefined()
    await click('更新状态')
    await router.push('/cases?caseId=other')
    await router.push('/alarms')
    await wrapper.find('.el-drawer__close-btn').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/cases?caseId=outside')
    expect(mocks.updateStatus).toHaveBeenCalledTimes(1)
    expect(mocks.updateStatus).toHaveBeenCalledWith('outside', 'CONTAINED', 'alice')
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    pending.reject(new Error('Write unavailable')); await flushPromises()
    expect(wrapper.find('.el-drawer [role="alert"]').text()).toContain('Write unavailable')
    expect(wrapper.find('.case-status-row').findComponent(ElSelect).props('modelValue')).toBe('CONTAINED')
    expect(mocks.confirm).not.toHaveBeenCalled()
  })

  it('restores history filters at their requested page without a delayed reset to page one', async () => {
    const router = await open('/cases?q=first&page=3')
    await router.push('/cases?q=second&page=2&status=OPEN'); await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 350))
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ q: 'second', page: '2', status: 'OPEN' })
    expect(mocks.list).toHaveBeenLastCalledWith(2, 20, 'second', 'OPEN', expect.anything())
    await router.push('/cases?q=third&page=2&status=RESOLVED'); await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 350))
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ q: 'third', page: '2', status: 'RESOLVED' })
    expect(mocks.list).toHaveBeenLastCalledWith(2, 20, 'third', 'RESOLVED', expect.anything())
    const input = wrapper.find('input[placeholder="搜索案件 ID / 标题 / 实体"]')
    await input.setValue('operator draft')
    await router.push({ query: { ...router.currentRoute.value.query, caseId: 'outside' } }); await flushPromises()
    expect(input.element).toHaveProperty('value', 'operator draft')
  })

  it('establishes the acknowledged status and assignee before a failed background refresh', async () => {
    const router = await open()
    await setStatus('RESOLVED')
    mocks.updateStatus.mockResolvedValueOnce({ case: { ...incident('outside'), status: 'CLOSED', assignee: '' } })
    mocks.list.mockRejectedValueOnce(new Error('Refresh unavailable'))
    mocks.timeline.mockRejectedValueOnce(new Error('Timeline refresh unavailable'))
    await click('更新状态')
    expect(wrapper.find('.case-status-row').findComponent(ElSelect).props('modelValue')).toBe('CLOSED')
    expect(wrapper.find('.el-drawer').findComponent(ElSelect).props('modelValue')).toBe('')
    expect(mocks.success).toHaveBeenCalledWith('案件已更新')
    expect(wrapper.find('.el-drawer').text()).toContain('Timeline refresh unavailable')
    await router.push('/alarms')
    expect(router.currentRoute.value.path).toBe('/alarms')
    expect(mocks.confirm).not.toHaveBeenCalled()
  })

  it('keeps a new case dialog after failure and opens its acknowledged ID after success', async () => {
    const router = await open('/cases?page=2&status=OPEN')
    await click('新建案件')
    await click('保存')
    await vi.waitFor(() => expect(wrapper.find('.el-dialog').text()).toContain('请输入案件标题'))
    expect(mocks.create).not.toHaveBeenCalled()
    await wrapper.find('.el-dialog input').setValue('New case')
    mocks.create.mockRejectedValueOnce(new Error('Create unavailable'))
    await click('保存')
    expect(wrapper.find('.el-dialog input').element).toHaveProperty('value', 'New case')
    expect(wrapper.find('.el-dialog [role="alert"]').text()).toContain('Create unavailable')
    mocks.list.mockRejectedValueOnce(new Error('Refresh unavailable'))
    await click('保存')
    expect(router.currentRoute.value.query).toEqual({ page: '2', status: 'OPEN', caseId: 'created' })
    expect(wrapper.find('.el-drawer').text()).toContain('Case created')
    expect(mocks.get).toHaveBeenLastCalledWith('created', expect.anything())
    expect(mocks.confirm).not.toHaveBeenCalled()
  })

  it('handles export failures and hides the restricted action from viewers', async () => {
    mocks.export.mockRejectedValueOnce(new Error('Export exceeds limit'))
    await open('/cases')
    await click('导出全部案件 JSON')
    expect(wrapper.text()).toContain('Export exceeds limit')
    wrapper.unmount()
    await open('/cases?caseId=outside', 'viewer')
    expect(wrapper.find('.el-drawer').text()).toContain('Case outside')
    expect(wrapper.findAll('button').some(item => item.text().includes('导出全部案件'))).toBe(false)
    expect(wrapper.findAll('button').some(item => item.text() === '更新状态')).toBe(false)
  })
})
