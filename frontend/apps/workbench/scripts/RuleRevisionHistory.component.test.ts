import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RuleRevisionHistory from '../src/components/RuleRevisionHistory.vue'
import { ApiError } from '../src/api/core'

const api = vi.hoisted(() => ({ getRule: vi.fn(), listRuleRevisions: vi.fn(), getRuleRevision: vi.fn(), restoreRuleRevision: vi.fn(), confirmDanger: vi.fn() }))
vi.mock('../src/api', () => api)
vi.mock('../src/composables/useConfirm', () => ({ useConfirm: () => ({ confirmDanger: api.confirmDanger }) }))
const head = { id: 'rule', name: 'Current rule', type: 'pattern', severity: 'HIGH', enabled: false, status: 'DRAFT', revisionToken: 'a'.repeat(64) }
const summary = (revision: number) => ({ ruleId: 'rule', revision, status: 'ACTIVE', source: 'EDIT', changedBy: 'analyst', changedAt: '2026-09-21T10:00:00Z' })
const historical = { ...summary(2), spec: { ...head, name: 'Historical rule', status: 'ACTIVE', enabled: true, revisionToken: 'history-token' } }
let wrapper: VueWrapper

beforeEach(() => {
  api.getRule.mockResolvedValue(head)
  api.listRuleRevisions.mockImplementation((_id, page) => Promise.resolve({ items: page === 1 ? [summary(2), summary(1)] : [summary(1)], total: 11, totalPages: 2, page, size: 10 }))
  api.getRuleRevision.mockImplementation((_id, revision) => Promise.resolve({ ...historical, revision }))
  api.restoreRuleRevision.mockResolvedValue({ ...head, name: 'Restored rule', revisionToken: 'b'.repeat(64) })
  api.confirmDanger.mockResolvedValue(true)
})
afterEach(() => wrapper?.unmount())
async function open(canRestore = true) {
  wrapper = mount(RuleRevisionHistory, { props: { modelValue: true, ruleId: 'rule', canRestore, hasDraft: true }, global: { stubs: { teleport: true } } })
  await flushPromises()
}
async function click(text: string) {
  const button = wrapper.findAll('button').find(item => item.text() === text)
  expect(button, text).toBeTruthy()
  await button!.trigger('click')
  await flushPromises()
}

describe('rule revision review', () => {
  it('pages metadata, fetches only selected detail and restores against the reviewed head', async () => {
    await open()
    expect(api.getRuleRevision).not.toHaveBeenCalled()
    await click('下一页修订')
    expect(api.listRuleRevisions).toHaveBeenLastCalledWith('rule', 2, expect.any(Object))
    await click('对比')
    expect(wrapper.find('.history-comparison').text()).toContain('Historical rule')
    expect(wrapper.find('.history-comparison').text()).toContain('Current rule')
    expect(wrapper.find('.history-comparison').text()).not.toContain('revisionToken')
    api.confirmDanger.mockResolvedValueOnce(false)
    await click('恢复所选修订')
    expect(api.restoreRuleRevision).not.toHaveBeenCalled()
    await click('恢复所选修订')
    expect(api.confirmDanger.mock.calls.at(-1)?.[0]).toContain('ACTIVE')
    expect(api.confirmDanger.mock.calls.at(-1)?.[0]).toContain('尚未保存的草稿')
    expect(api.getRuleRevision).toHaveBeenCalledWith('rule', 1, expect.any(Object))
    expect(api.restoreRuleRevision).toHaveBeenCalledWith('rule', 1, 'a'.repeat(64), false)
    expect(wrapper.emitted('busy')).toEqual([[true], [false]])
    expect(wrapper.emitted('restored')?.[0]?.[0]).toMatchObject({ name: 'Restored rule' })
    expect(api.listRuleRevisions).toHaveBeenLastCalledWith('rule', 1, expect.any(Object))
  })

  it('keeps a stale restore visible and refreshes its precondition only on explicit review', async () => {
    api.restoreRuleRevision.mockRejectedValueOnce(new ApiError(412, 'changed'))
    await open()
    await click('对比')
    await click('恢复所选修订')
    expect(wrapper.text()).toContain('规则在对比期间已被修改')
    expect(api.getRule).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.history-comparison').text()).toContain('Historical rule')
    api.getRule.mockResolvedValue({ ...head, name: 'Concurrent winner', revisionToken: 'c'.repeat(64) })
    await click('刷新')
    expect(wrapper.find('.history-comparison').text()).toContain('Concurrent winner')
    await click('恢复所选修订')
    expect(api.restoreRuleRevision).toHaveBeenLastCalledWith('rule', 2, 'c'.repeat(64), false)
  })

  it('requires an observed 404 before using the absent-rule restore condition', async () => {
    await open()
    await click('对比')
    api.getRule.mockRejectedValueOnce(new ApiError(503, 'Read failed'))
    await click('刷新')
    expect(wrapper.findAll('button').find(item => item.text() === '恢复所选修订')!.attributes('disabled')).toBeDefined()
    api.getRule.mockRejectedValueOnce(new ApiError(404, 'deleted'))
    await click('刷新')
    expect(wrapper.text()).toContain('当前规则已删除')
    await click('恢复所选修订')
    expect(api.restoreRuleRevision).toHaveBeenCalledWith('rule', 2, undefined, true)
  })

  it('keeps history browseable for an analyst without exposing a restore control', async () => {
    await open(false)
    await click('对比')
    expect(wrapper.text()).toContain('Historical rule')
    expect(wrapper.findAll('button').some(item => item.text() === '恢复所选修订')).toBe(false)
    expect(api.restoreRuleRevision).not.toHaveBeenCalled()
  })

  it('ignores a delayed revision detail when another revision is selected', async () => {
    let finish!: (value: unknown) => void
    api.getRuleRevision.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    api.getRuleRevision.mockResolvedValueOnce({ ...historical, revision: 1, spec: { ...head, name: 'Latest selection' } })
    await open()
    const compares = wrapper.findAll('button').filter(item => item.text() === '对比')
    await compares[0].trigger('click')
    await compares[1].trigger('click')
    await flushPromises()
    finish(historical)
    await flushPromises()
    expect(wrapper.find('.history-comparison').text()).toContain('Latest selection')
    expect(wrapper.find('.history-comparison').text()).not.toContain('Historical rule')
    await click('恢复所选修订')
    expect(api.restoreRuleRevision).toHaveBeenCalledWith('rule', 1, 'a'.repeat(64), false)
  })
})
