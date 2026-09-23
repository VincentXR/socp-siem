import { mount, flushPromises } from '@vue/test-utils'
import { h, ref } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ParseRuleEditorView from '../src/views/ParseRuleEditorView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const mocks = vi.hoisted(() => ({
  getParseRule: vi.fn(), listSourcesPage: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, size: 50, totalPages: 0 }), listFields: vi.fn().mockResolvedValue([]), getSource: vi.fn(),
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
  it('keeps the latest remote source search when an older response arrives late', async () => {
    let resolveOld!: (value: { items: { id: string; name: string }[]; total: number; page: number; size: number; totalPages: number }) => void
    mocks.listSourcesPage.mockResolvedValueOnce({ items: [], total: 0, page: 1, size: 50, totalPages: 0 })
      .mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValueOnce({ items: [{ id: 'new', name: 'New Source' }], total: 1, page: 1, size: 50, totalPages: 1 })
    const { wrapper } = await openEditor('/parsers/new')
    const sourceSelect = wrapper.findAllComponents(ElSelect)[1]!
    const remoteMethod = sourceSelect.props('remoteMethod') as (query: string) => void
    vi.useFakeTimers()
    remoteMethod('old')
    await vi.advanceTimersByTimeAsync(250)
    remoteMethod('new')
    await vi.advanceTimersByTimeAsync(250)
    vi.useRealTimers()
    await flushPromises()
    resolveOld({ items: [{ id: 'old', name: 'Old Source' }], total: 1, page: 1, size: 50, totalPages: 1 })
    await flushPromises()
    expect(mocks.listSourcesPage).toHaveBeenCalledWith(1, 50, 'new', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(wrapper.findAllComponents(ElOption).map(option => option.props('label'))).toContain('New Source')
    expect(wrapper.findAllComponents(ElOption).map(option => option.props('label'))).not.toContain('Old Source')
    wrapper.unmount()
  })

  it('finds a source beyond the first 500 and retains its selected label while searching', async () => {
    const deep = { id: 'source-501', name: 'Deep Source' }
    mocks.getParseRule.mockResolvedValue({ ...rules[0], sourceId: deep.id })
    mocks.getSource.mockResolvedValue({ source: deep })
    mocks.listSourcesPage.mockResolvedValueOnce({ items: [{ id: 'source-001', name: 'First Source' }], total: 501, page: 1, size: 50, totalPages: 11 })
      .mockResolvedValueOnce({ items: [deep], total: 1, page: 1, size: 50, totalPages: 1 })
    const { wrapper } = await openEditor('/parsers/a/edit')
    expect(mocks.getParseRule).toHaveBeenCalledWith('a', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(wrapper.text()).toContain('501')
    expect(mocks.getSource).toHaveBeenCalledWith(deep.id, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    const sourceSelect = wrapper.findAllComponents(ElSelect)[1]!
    const remoteMethod = sourceSelect.props('remoteMethod') as (query: string) => void
    vi.useFakeTimers()
    remoteMethod('Deep')
    await vi.advanceTimersByTimeAsync(250)
    vi.useRealTimers()
    await flushPromises()
    expect(mocks.listSourcesPage).toHaveBeenCalledWith(1, 50, 'Deep', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(wrapper.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe(deep.id)
    expect(wrapper.text()).toContain('Deep Source')
    wrapper.unmount()
  })

  it('keeps a deleted or unavailable selected source visible with its read error', async () => {
    mocks.getParseRule.mockResolvedValue({ ...rules[0], sourceId: 'missing-source' })
    mocks.getSource.mockRejectedValueOnce(new Error('Source detail unavailable'))
    const { wrapper } = await openEditor('/parsers/a/edit')
    expect(wrapper.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('missing-source')
    expect(wrapper.text()).toContain('missing-source')
    expect(wrapper.text()).toContain('Source detail unavailable')
    expect(wrapper.find('.editor-footer button').exists()).toBe(true)
    wrapper.unmount()
  })

  it('reloads reused routes and writes the displayed rule identity', async () => {
    mocks.getParseRule.mockImplementation((id: string) => Promise.resolve(rules.find(rule => rule.id === id)))
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
    let resolveOld!: (value: typeof rules[number]) => void
    mocks.getParseRule.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
      .mockImplementation((id: string) => Promise.resolve(rules.find(rule => rule.id === id)))
    const { router, wrapper } = await openEditor('/parsers/a/edit')
    await router.push('/parsers/b/edit')
    await flushPromises()
    resolveOld(rules[0]!)
    await flushPromises()
    expect(wrapper.find('input').element.value).toBe('Parser b')
    expect(wrapper.text()).not.toContain('Parse rule not found')
    wrapper.unmount()
  })

  it('keeps input after a failed save', async () => {
    mocks.getParseRule.mockResolvedValue(rules[0])
    mocks.updateParseRule.mockRejectedValueOnce(new Error('Database unavailable'))
    const { wrapper } = await openEditor('/parsers/a/edit')
    await wrapper.find('input').setValue('Unsaved parser name')
    await wrapper.find('.editor-footer button').trigger('click')
    await flushPromises()
    expect(wrapper.find('input').element.value).toBe('Unsaved parser name')
    expect(wrapper.text()).toContain('Database unavailable')
    wrapper.unmount()
  })

  it('refuses a non-array filter payload with the localized contract message', async () => {
    mocks.getParseRule.mockResolvedValue(rules[0])
    const { wrapper } = await openEditor('/parsers/a/edit')
    const filtersField = wrapper.findAll('textarea').find(field => field.element.value === '[]')
    expect(filtersField).toBeDefined()
    await filtersField!.setValue('{}')
    await wrapper.find('.editor-footer button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('筛选条件（filters）必须是 JSON 数组。')
    expect(mocks.updateParseRule).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
