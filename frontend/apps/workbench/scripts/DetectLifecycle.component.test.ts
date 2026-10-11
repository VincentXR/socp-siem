import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { effectScope, ref, type EffectScope } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DetectView from '../src/views/DetectView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
import { useDetectRuleTest } from '../src/composables/detect/useDetectRuleTest'
import { formFromRule, type RuleEditorForm } from '../src/lib/detection-editor'
import type { RuleSpec } from '../src/api'

const api = vi.hoisted(() => ({
  getRule: vi.fn(), listRulePage: vi.fn(), gasStats: vi.fn(), listFields: vi.fn(),
  listWatchlists: vi.fn(), listTechniques: vi.fn(), testGasRules: vi.fn(),
  createGasRule: vi.fn(), updateGasRule: vi.fn(), activateGasRule: vi.fn(),
}))
vi.mock('../src/api', async original => ({ ...await original<object>(), ...api }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function rule(id = 'rule-a', name = id): RuleSpec {
  return { id, name, type: 'pattern', severity: 'HIGH', enabled: false, status: 'DRAFT',
    revisionToken: 'a'.repeat(64), match: [{ field: 'msg', op: 'contains', value: 'Failed' }] }
}
const result = [{ id: 'rule-a', name: 'rule-a', type: 'pattern', matched: true, eventCount: 1, alerts: [] }]
let scope: EffectScope | undefined
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  api.getRule.mockImplementation(async (id: string) => rule(id))
  api.listRulePage.mockResolvedValue({ items: [rule()], total: 1, page: 1, size: 20, totalPages: 1 })
  api.gasStats.mockResolvedValue({ rules: 1, queueLoad: 0 })
  api.listFields.mockResolvedValue([])
  api.listWatchlists.mockResolvedValue([])
  api.listTechniques.mockResolvedValue({ items: [] })
  api.testGasRules.mockResolvedValue(result)
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; scope?.stop(); scope = undefined })

function preview() {
  const identity = ref('/detect/rules/rule-a/edit')
  const revision = ref<string | undefined>('a'.repeat(64))
  const form = ref(formFromRule(rule()))
  scope = effectScope()
  const test = scope.run(() => useDetectRuleTest({
    canManageRules: ref(true), rules: ref([rule()]), showRuleEditor: ref(true),
    ruleEditingId: ref('rule-a'), ruleForm: form, loadedRevisionToken: revision,
    identity: () => identity.value, draftSpec: () => rule(),
  }))!
  test.showTest.value = true
  return { test, identity, revision, form }
}

describe('isolated rule preview ownership', () => {
  it.each(['success', 'failure'])('ignores a closed drawer’s late %s while its reopened request is pending', async outcome => {
    const first = deferred<typeof result>()
    const current = deferred<typeof result>()
    api.testGasRules.mockReturnValueOnce(first.promise).mockReturnValueOnce(current.promise)
    const { test } = preview()
    const old = test.runIsolatedTest()
    await test.runIsolatedTest()
    expect(api.testGasRules).toHaveBeenCalledTimes(1)
    test.showTest.value = false
    expect(test.testing.value).toBe(false)
    test.showTest.value = true
    const pending = test.runIsolatedTest()
    if (outcome === 'success') first.resolve(result)
    else first.reject(new Error('old drawer failure'))
    await old
    expect(test.testing.value).toBe(true)
    expect(test.testError.value).toBe('')
    expect(test.testResult.value).toBeNull()
    expect(test.testReceipt.value).toBeNull()
    current.resolve(result)
    await pending
    expect(test.testing.value).toBe(false)
    expect(test.testResult.value?.matched).toBe(1)
  })

  it('does not confuse A → B → A navigation with the original editor session', async () => {
    const first = deferred<typeof result>()
    api.testGasRules.mockReturnValueOnce(first.promise)
    const { test, identity } = preview()
    const old = test.runIsolatedTest()
    identity.value = '/detect/rules/rule-b/edit'
    identity.value = '/detect/rules/rule-a/edit'
    expect(test.showTest.value).toBe(false)
    test.showTest.value = true
    await test.runIsolatedTest()
    first.resolve([{ ...result[0]!, matched: false }])
    await old
    expect(test.testResult.value?.matched).toBe(1)
    expect(test.testReceipt.value?.result.matched).toBe(1)
  })

  it('invalidates a running preview when the target changes', async () => {
    const first = deferred<typeof result>()
    api.testGasRules.mockReturnValueOnce(first.promise)
    const { test } = preview()
    const old = test.runIsolatedTest()
    test.testRuleId.value = 'rule-b'
    first.reject(new Error('old target'))
    await old
    expect(test.testError.value).toBe('')
    expect(test.testing.value).toBe(false)
    expect(test.testReceipt.value).toBeNull()
  })

  it.each(['success', 'failure'])('ignores %s after its owner is disposed', async outcome => {
    const first = deferred<typeof result>()
    api.testGasRules.mockReturnValueOnce(first.promise)
    const { test } = preview()
    const old = test.runIsolatedTest()
    scope!.stop()
    if (outcome === 'success') first.resolve(result)
    else first.reject(new Error('disposed request'))
    await old
    expect(test.testResult.value).toBeNull()
    expect(test.testError.value).toBe('')
    expect(test.testReceipt.value).toBeNull()
  })

  it('marks a captured receipt stale after draft edits or a revision change', async () => {
    const { test, form, revision } = preview()
    await test.runIsolatedTest()
    expect(test.testReceiptStale.value).toBe(false)
    test.showTest.value = false
    expect(test.testReceipt.value?.revision).toBe('a'.repeat(64))
    form.value.name = 'Changed draft'
    expect(test.testReceiptStale.value).toBe(true)
    form.value = formFromRule(rule())
    expect(test.testReceiptStale.value).toBe(false)
    revision.value = 'b'.repeat(64)
    expect(test.testReceiptStale.value).toBe(true)
  })
})

type EditorView = {
  saveRule: () => Promise<void>
  publishSavedRule: () => Promise<void>
  saving: boolean
  saveError: string
  ruleForm: RuleEditorForm
  changes: { dirty: { value: boolean } }
}
async function editor(path = '/detect/rules/rule-a/edit') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/detect', name: 'detect', component: DetectView },
    { path: '/detect/rules/:ruleId/edit', name: 'rule-edit', component: DetectView, meta: { editor: true } },
    { path: '/elsewhere', component: { template: '<p>Elsewhere</p>' } },
  ] })
  await router.push(path)
  wrapper = mount(RouterView, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } } })
  await flushPromises()
  const view = wrapper.findComponent(DetectView).vm as unknown as EditorView
  return { view, router }
}

describe('rule editor session ownership', () => {
  it.each(['success', 'failure'])('discards an older editor fetch %s after close and reopen', async outcome => {
    const first = deferred<RuleSpec>()
    api.getRule.mockReturnValueOnce(first.promise)
    const { router } = await editor()
    await router.push('/detect')
    await flushPromises()
    await router.push('/detect/rules/rule-a/edit')
    await flushPromises()
    if (outcome === 'success') first.resolve(rule('rule-a', 'Stale draft'))
    else first.reject(new Error('stale editor failure'))
    await flushPromises()
    expect(wrapper!.find('.detect-editor-form input').element).toHaveProperty('value', 'rule-a')
    expect(wrapper!.text()).not.toContain('stale editor failure')
    expect(api.getRule.mock.calls[0]![1].signal.aborted).toBe(true)
  })

  it('locks publication and navigation through activation and reload, retaining a clean authoritative baseline', async () => {
    const activation = deferred<void>()
    const reload = deferred<RuleSpec>()
    api.activateGasRule.mockReturnValueOnce(activation.promise)
    const { view, router } = await editor()
    api.getRule.mockReturnValueOnce(reload.promise)
    const publishing = view.publishSavedRule()
    await view.publishSavedRule()
    expect(api.activateGasRule).toHaveBeenCalledExactlyOnceWith('rule-a', 'a'.repeat(64))
    await router.push('/elsewhere')
    expect(router.currentRoute.value.path).toBe('/detect/rules/rule-a/edit')
    activation.resolve()
    await flushPromises()
    expect(view.saving).toBe(true)
    expect(api.getRule).toHaveBeenCalledTimes(2)
    await view.publishSavedRule()
    expect(api.activateGasRule).toHaveBeenCalledTimes(1)
    reload.resolve({ ...rule(), enabled: true, status: 'ACTIVE', revisionToken: 'b'.repeat(64) })
    await publishing
    expect(view.saving).toBe(false)
    expect(view.ruleForm.enabled).toBe(true)
    expect(view.changes.dirty.value).toBe(false)
    await router.push('/elsewhere')
    expect(router.currentRoute.value.path).toBe('/elsewhere')
  })

  it('handles a failed publication reload without an unhandled rejection or dirty draft', async () => {
    api.activateGasRule.mockResolvedValueOnce(undefined)
    const { view } = await editor()
    api.getRule.mockRejectedValueOnce(new Error('Reload unavailable'))
    await view.publishSavedRule()
    expect(view.saving).toBe(false)
    expect(view.saveError).toBe('Reload unavailable')
    expect(view.changes.dirty.value).toBe(false)
  })

  it.each(['success', 'failure'])('discards a pending save %s after forced unmount', async outcome => {
    const write = deferred<RuleSpec>()
    api.updateGasRule.mockReturnValueOnce(write.promise)
    const { view, router } = await editor()
    const replace = vi.spyOn(router, 'replace')
    const pending = view.saveRule()
    wrapper!.unmount(); wrapper = undefined
    if (outcome === 'success') write.resolve(rule('rule-a', 'Disposed result'))
    else write.reject(new Error('disposed write failure'))
    await pending
    expect(view.ruleForm.name).toBe('rule-a')
    expect(view.saveError).toBe('')
    expect(replace).not.toHaveBeenCalled()
    expect(api.gasStats).toHaveBeenCalledTimes(1)
  })
})
