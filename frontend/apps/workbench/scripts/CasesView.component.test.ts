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
  changeAssociation: vi.fn(), list: vi.fn(), get: vi.fn(), stats: vi.fn(), timeline: vi.fn(), create: vi.fn(), updateStatus: vi.fn(), saveChanges: vi.fn(), claim: vi.fn(), addNote: vi.fn(), alarms: vi.fn(), rules: vi.fn(), exportSummary: vi.fn(), export: vi.fn(),
  alarm: vi.fn(), ruleOptions: vi.fn(), success: vi.fn(), info: vi.fn(), warning: vi.fn(), confirm: vi.fn(),
}))
vi.mock('../src/api/domains', async original => ({ ...await original<object>(), caseApi: mocks }))
vi.mock('../src/api/alarms', () => ({ getAlarm: mocks.alarm }))
vi.mock('../src/api/detect', () => ({ lookupRuleOptions: mocks.ruleOptions }))
vi.mock('element-plus/es/components/message/index.mjs', () => ({ default: { success: mocks.success, info: mocks.info, warning: mocks.warning } }))
vi.mock('element-plus/es/components/message-box/index.mjs', () => ({ default: { confirm: mocks.confirm } }))

const incident = (id: string): CaseInfo => ({ id, title: `Case ${id}`, entity: 'host-one', severity: 'HIGH', status: 'OPEN',
  assignee: 'alice', rowVersion: 0, ruleIds: ['rule-one'], alarmIds: ['alarm-one'], timeline: [] })
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
    { name: 'soar', path: '/soar', component: { template: '<p>SOAR</p>' } },
    { name: 'ai', path: '/assistant', component: { template: '<p>AI</p>' } },
    { name: 'rule-edit', path: '/rules/:ruleId', component: { template: '<p>Rule</p>' } },
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
    mocks.get.mockImplementation(async id => incident(id))
    mocks.timeline.mockImplementation(async id => events(`Timeline ${id}`))
    mocks.saveChanges.mockImplementation(async (id, request) => ({ case: { ...incident(id), ...request, rowVersion: 1 } }))
    mocks.alarm.mockResolvedValue({ id: 'alarm-one', title: 'Suspicious SSH login', entity: 'host-one', severity: 'HIGH', ruleId: 'rule-one', ruleName: 'SSH brute force' })
    mocks.ruleOptions.mockResolvedValue([{ id: 'rule-one', name: 'SSH brute force', status: 'ACTIVE' }])
    mocks.alarms.mockResolvedValue({ items: [], total: 0 })
    mocks.rules.mockResolvedValue({ items: [], total: 0 })
    mocks.addNote.mockImplementation(async id => ({ case: { ...incident(id), rowVersion: 1 } }))
    mocks.create.mockResolvedValue({ case: incident('created') })
    mocks.confirm.mockResolvedValue('confirm')
  })
  afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

  it('keeps association drafts and reuses the operation key after a conflict', async () => {
    await open()
    const view = wrapper.findComponent(CasesView).vm as unknown as {
      association: { alarmId: string; reason: string }; associationVisible: boolean;
      openAssociation: (operation: 'ATTACH') => Promise<void>; inspectAssociationAlarm: () => Promise<void>; saveAssociation: () => Promise<void>
    }
    await view.openAssociation('ATTACH'); await flushPromises()
    view.association.alarmId = 'alarm-one'; view.association.reason = 'same investigation'
    await view.inspectAssociationAlarm()
    mocks.changeAssociation.mockRejectedValueOnce(new Error('409 conflicting case version'))
    await view.saveAssociation()
    expect(view.associationVisible).toBe(true)
    expect(view.association.reason).toBe('same investigation')
    mocks.changeAssociation.mockResolvedValueOnce({ case: { ...incident('outside'), rowVersion: 1 }, changed: true, duplicate: false })
    await view.saveAssociation()
    expect(mocks.changeAssociation).toHaveBeenCalledTimes(2)
    expect(mocks.changeAssociation.mock.calls[1][1].idempotencyKey).toBe(mocks.changeAssociation.mock.calls[0][1].idempotencyKey)
    expect(mocks.changeAssociation.mock.calls[0]).toEqual(['outside', expect.objectContaining({ operation: 'ATTACH', alarmId: 'alarm-one', expectedVersion: 0, reason: 'same investigation' })])
    expect(view.associationVisible).toBe(false)
  })

  it('uses the registered AI destination with case context', async () => {
    const router = await open()
    const view = wrapper.findComponent(CasesView).vm as unknown as { openContext: (name: 'ai') => void }
    view.openContext('ai'); await flushPromises()
    expect(router.currentRoute.value.name).toBe('ai')
    expect(router.currentRoute.value.query.caseId).toBe('outside')
  })

  it('enriches alarm references in batches of four and keeps failed references usable', async () => {
    const ids = Array.from({ length: 6 }, (_, index) => `alarm-${index}`)
    const pending = ids.map(() => deferred<{ id: string; title: string; severity: string }>())
    mocks.alarms.mockResolvedValue({ items: ids, total: ids.length })
    mocks.alarm.mockImplementation(id => pending[ids.indexOf(id)].promise)
    await open()
    expect(mocks.alarm).toHaveBeenCalledTimes(4)
    pending[0].resolve({ id: ids[0], title: 'Hydrated alarm', severity: 'HIGH' })
    pending[1].reject(new Error('Unavailable'))
    pending[2].resolve({ id: ids[2], title: ids[2], severity: 'LOW' })
    await flushPromises()
    expect(mocks.alarm).toHaveBeenCalledTimes(4)
    pending[3].resolve({ id: ids[3], title: ids[3], severity: 'LOW' })
    await flushPromises()
    expect(mocks.alarm).toHaveBeenCalledTimes(6)
    pending[4].resolve({ id: ids[4], title: ids[4], severity: 'LOW' })
    pending[5].resolve({ id: ids[5], title: ids[5], severity: 'LOW' })
    await flushPromises()
    expect(wrapper.find('.case-alarm-list').text()).toContain('Hydrated alarm')
    expect(wrapper.findAll('.case-alarm-card')).toHaveLength(6)
    expect(wrapper.findAll('.case-alarm-card')[1].find('.case-object-link').text()).toBe(ids[1])
  })

  it('opens a case outside the list page even when the list fails, and retries a missing detail', async () => {
    mocks.list.mockRejectedValue(new Error('List unavailable'))
    mocks.get.mockRejectedValueOnce(new Error('未找到此案件'))
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
    const oldDetail = deferred<CaseInfo>()
    const oldTimeline = deferred<Paged<TimelineEvent>>()
    mocks.get.mockReturnValueOnce(oldDetail.promise)
    const router = await open('/cases?caseId=first')
    mocks.timeline.mockReturnValueOnce(oldTimeline.promise)
    await router.push('/cases?caseId=second'); await flushPromises()
    await router.push('/cases?caseId=third'); await flushPromises()
    oldDetail.resolve(incident('first'))
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
    mocks.saveChanges.mockReturnValueOnce(pending.promise)
    const router = await open()
    await setStatus('CONTAINED')
    await click('保存变更')
    expect(wrapper.find('.case-status-row input').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.el-descriptions input').attributes('disabled')).toBeDefined()
    await click('保存变更')
    await router.push('/cases?caseId=other')
    await router.push('/alarms')
    await wrapper.find('.el-drawer__close-btn').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/cases?caseId=outside')
    expect(mocks.saveChanges).toHaveBeenCalledTimes(1)
    expect(mocks.saveChanges).toHaveBeenCalledWith('outside', expect.objectContaining({ status: 'CONTAINED', assignee: 'alice', expectedVersion: 0, idempotencyKey: expect.any(String) }))
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
    expect(mocks.list).toHaveBeenLastCalledWith(2, 20, 'second', 'OPEN', expect.anything(), undefined)
    await router.push('/cases?q=third&page=2&status=RESOLVED'); await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 350))
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ q: 'third', page: '2', status: 'RESOLVED' })
    expect(mocks.list).toHaveBeenLastCalledWith(2, 20, 'third', 'RESOLVED', expect.anything(), undefined)
    const input = wrapper.find('input[placeholder="搜索案件 ID / 标题 / 实体"]')
    await input.setValue('operator draft')
    await router.push({ query: { ...router.currentRoute.value.query, caseId: 'outside' } }); await flushPromises()
    expect(input.element).toHaveProperty('value', 'operator draft')
  })

  it('establishes the acknowledged status and assignee before a failed background refresh', async () => {
    const router = await open()
    await setStatus('CONTAINED')
    mocks.saveChanges.mockResolvedValueOnce({ case: { ...incident('outside'), status: 'CLOSED', assignee: '' } })
    mocks.list.mockRejectedValueOnce(new Error('Refresh unavailable'))
    mocks.timeline.mockRejectedValueOnce(new Error('Timeline refresh unavailable'))
    await click('保存变更')
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
    expect(wrapper.findAll('button').some(item => item.text() === '保存变更')).toBe(false)
  })
  it('shows human-readable alarm context and carries the case back-link into response and evidence routes', async () => {
    mocks.alarms.mockResolvedValue({ items: ['alarm-one'], total: 1 })
    mocks.rules.mockResolvedValue({ items: ['rule-one'], total: 1 })
    const router = await open()
    expect(wrapper.find('.case-alarm-card').text()).toContain('Suspicious SSH login')
    expect(wrapper.find('.case-object-list').text()).toContain('SSH brute force')
    await click('运行已发布响应')
    expect(router.currentRoute.value.query).toEqual({ caseId: 'outside', tab: 'runs', returnTo: '/cases?caseId=outside' })
    await router.push('/cases?caseId=outside'); await flushPromises()
    await click('Suspicious SSH login')
    expect(router.currentRoute.value.query).toEqual({ alarmId: 'alarm-one', caseId: 'outside', returnTo: '/cases?caseId=outside' })
  })

  it('retains a failed note and idempotency key, rejects unsafe evidence URLs, then clears only after success', async () => {
    const router = await open()
    const note = wrapper.find('textarea[aria-label="调查备注"]')
    const evidence = wrapper.find('textarea[aria-label="证据链接（每行一个 HTTP/HTTPS 地址）"]')
    await note.setValue('Checked suspicious login')
    await evidence.setValue('javascript:alert(1)')
    await click('添加备注')
    expect(mocks.addNote).not.toHaveBeenCalled()
    await evidence.setValue('https://evidence.test/log/42')
    mocks.addNote.mockRejectedValueOnce(new Error('Temporary failure'))
    await click('添加备注')
    const attempt = mocks.addNote.mock.calls[0]
    expect(note.element).toHaveProperty('value', 'Checked suspicious login')
    expect(attempt).toEqual(['outside', 'Checked suspicious login\nEvidence:\nhttps://evidence.test/log/42', expect.any(String)])
    await click('添加备注')
    expect(mocks.addNote.mock.calls[1]).toEqual(attempt)
    expect(note.element).toHaveProperty('value', '')
    await setStatus('INVESTIGATING')
    await click('保存变更')
    expect(mocks.saveChanges).toHaveBeenLastCalledWith('outside', expect.objectContaining({ expectedVersion: 1 }))
    await router.push('/alarms')
    expect(mocks.confirm).not.toHaveBeenCalled()
  })

  it('requires complete closure evidence and confirmation, and sends the visible outcome with the version', async () => {
    await open()
    await setStatus('CLOSED')
    await click('保存变更')
    expect(mocks.saveChanges).not.toHaveBeenCalled()
    const classification = wrapper.findAllComponents(ElSelect).find(item => item.props('ariaLabel') === '判定')!
    classification.vm.$emit('update:modelValue', 'FALSE_POSITIVE')
    for (const label of ['处置结果', '判定理由', '依据和证据', '剩余行动（无则填写无）']) {
      await wrapper.find(`textarea[aria-label="${label}"]`).setValue(`${label} complete`)
    }
    mocks.confirm.mockRejectedValueOnce(new Error('cancel'))
    await click('保存变更')
    expect(mocks.saveChanges).not.toHaveBeenCalled()
    await click('保存变更')
    expect(mocks.saveChanges).toHaveBeenCalledWith('outside', expect.objectContaining({ status: 'CLOSED', expectedVersion: 0,
      classification: 'FALSE_POSITIVE', result: '处置结果 complete', reason: '判定理由 complete', evidence: '依据和证据 complete', remainingActions: '剩余行动（无则填写无） complete' }))
  })

  it('keeps a pending note dirty after a metadata save and preserves drafts after version conflicts', async () => {
    const router = await open()
    await wrapper.find('textarea[aria-label="调查备注"]').setValue('Not yet saved')
    await setStatus('INVESTIGATING')
    await click('保存变更')
    mocks.confirm.mockRejectedValueOnce(new Error('keep note'))
    await router.push('/alarms')
    expect(router.currentRoute.value.path).toBe('/cases')
    expect(wrapper.find('textarea[aria-label="调查备注"]').element).toHaveProperty('value', 'Not yet saved')
    mocks.saveChanges.mockRejectedValueOnce(new Error('Case changed; refresh and review before saving'))
    await setStatus('CONTAINED')
    await click('保存变更')
    expect(wrapper.find('.el-drawer').text()).toContain('Case changed; refresh')
    expect(wrapper.find('.case-status-row').findComponent(ElSelect).props('modelValue')).toBe('CONTAINED')
    mocks.confirm.mockRejectedValueOnce(new Error('keep draft'))
    await click('重新载入案件')
    expect(wrapper.find('.case-status-row').findComponent(ElSelect).props('modelValue')).toBe('CONTAINED')
  })

  it('restores my-work queue through routes and claims with authoritative version without overwriting a status draft', async () => {
    mocks.get.mockResolvedValue({ ...incident('outside'), assignee: '', rowVersion: 7 })
    mocks.claim.mockResolvedValue({ case: { ...incident('outside'), rowVersion: 8 } })
    await open('/cases?caseId=outside&queue=mine')
    expect(mocks.list).toHaveBeenLastCalledWith(1, 20, undefined, undefined, expect.anything(), 'mine')
    await setStatus('CONTAINED')
    await click('认领案件')
    expect(mocks.claim).toHaveBeenCalledWith('outside', 7, expect.any(String))
    expect(wrapper.find('.case-status-row').findComponent(ElSelect).props('modelValue')).toBe('CONTAINED')
    await click('保存变更')
    expect(mocks.saveChanges).toHaveBeenCalledWith('outside', expect.objectContaining({ status: 'CONTAINED', assignee: 'alice', expectedVersion: 8 }))
  })

  it('renders only HTTP evidence links and exposes bounded export limitations', async () => {
    mocks.timeline.mockResolvedValue(events('alice: safe https://evidence.test/log javascript:alert(1)'))
    await open()
    const links = wrapper.find('.case-timeline').findAll('a')
    expect(links).toHaveLength(1)
    expect(links[0].attributes('href')).toBe('https://evidence.test/log')
    expect(links[0].attributes('rel')).toContain('noopener')
    expect(wrapper.find('.el-drawer').text()).toContain('500')
    await click('导出案件摘要')
    expect(mocks.exportSummary).toHaveBeenCalledWith('outside')
  })

  it('does not rebase an unsaved status draft onto a concurrent owner/status change returned by note creation', async () => {
    await open()
    await setStatus('CONTAINED')
    await wrapper.find('textarea[aria-label="调查备注"]').setValue('Evidence reviewed')
    mocks.addNote.mockResolvedValueOnce({ case: { ...incident('outside'), status: 'CLOSED', assignee: 'bob', rowVersion: 8 } })
    await click('添加备注')
    expect(wrapper.find('.case-status-row').findComponent(ElSelect).props('modelValue')).toBe('CONTAINED')
    expect(wrapper.find('.el-drawer').text()).toContain('当前草稿已保留')
    await click('保存变更')
    expect(mocks.saveChanges).toHaveBeenCalledWith('outside', expect.objectContaining({ status: 'CONTAINED', expectedVersion: 0 }))
  })

})
