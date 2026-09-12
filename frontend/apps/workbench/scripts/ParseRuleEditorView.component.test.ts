import { mount, flushPromises } from '@vue/test-utils'
import { h, ref } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import ParseRuleEditorView from '../src/views/ParseRuleEditorView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const mocks = vi.hoisted(() => ({
  listParseRules: vi.fn(), listSources: vi.fn().mockResolvedValue([]), listFields: vi.fn().mockResolvedValue([]),
  updateParseRule: vi.fn(), createParseRule: vi.fn(), previewParseDraft: vi.fn(),
}))
vi.mock('../src/api', async importOriginal => ({ ...await importOriginal<object>(), ...mocks }))
const rules = ['a', 'b'].map(id => ({ id, name: `Parser ${id}`, format: 'REGEX', pattern: id, enabled: false, mapping: [], setFields: [], filters: [] }))
async function openEditor(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/parsers/new', name: 'parser-new', component: ParseRuleEditorView },
    { path: '/parsers/:parserId/edit', name: 'parser-edit', component: ParseRuleEditorView },
  ] })
  await router.push(path)
  await router.isReady()
  const wrapper = mount({ render: () => h(RouterView) }, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } } } })
  await flushPromises()
  return { router, wrapper }
}

describe('parse editor route ownership', () => {
  it('reloads reused routes and writes the displayed rule identity', async () => {
    mocks.listParseRules.mockResolvedValue(rules)
    mocks.updateParseRule.mockResolvedValue(rules[1])
    const { router, wrapper } = await openEditor('/parsers/a/edit')
    expect(wrapper.find('input').element.value).toBe('Parser a')
    await router.push('/parsers/b/edit')
    await flushPromises()
    expect(wrapper.find('input').element.value).toBe('Parser b')
    await wrapper.find('.editor-footer button').trigger('click')
    await flushPromises()
    expect(mocks.updateParseRule).toHaveBeenCalledWith('b', expect.objectContaining({ name: 'Parser b', pattern: 'b' }))
    wrapper.unmount()
  })

  it('ignores an older load completing after navigation', async () => {
    let resolveOld!: (value: typeof rules) => void
    mocks.listParseRules.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve })).mockResolvedValue(rules)
    const { router, wrapper } = await openEditor('/parsers/a/edit')
    await router.push('/parsers/b/edit')
    await flushPromises()
    resolveOld([rules[0]])
    await flushPromises()
    expect(wrapper.find('input').element.value).toBe('Parser b')
    expect(wrapper.text()).not.toContain('Parse rule not found')
    wrapper.unmount()
  })

  it('keeps input after a failed save', async () => {
    mocks.listParseRules.mockResolvedValue(rules)
    mocks.updateParseRule.mockRejectedValueOnce(new Error('Database unavailable'))
    const { wrapper } = await openEditor('/parsers/a/edit')
    await wrapper.find('input').setValue('Unsaved parser name')
    await wrapper.find('.editor-footer button').trigger('click')
    await flushPromises()
    expect(wrapper.find('input').element.value).toBe('Unsaved parser name')
    expect(wrapper.text()).toContain('Database unavailable')
    wrapper.unmount()
  })
})
