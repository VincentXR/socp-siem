import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SearchView from '../src/views/SearchView.vue'
import { setLocale } from '../src/i18n/locale-manager'
import type { SearchResult } from '../src/api/models'
import { ElSelect } from 'element-plus/es/components/select/index.mjs'

const api = vi.hoisted(() => ({ splSearch: vi.fn(), listFields: vi.fn(), exportSearch: vi.fn(), listAlarmsByEvent: vi.fn() }))
vi.mock('../src/api', () => api)

const result = (msg: string, nextCursor: string | null = null): SearchResult => ({
  total: 100,
  events: [{ eventId: msg, timestamp: '2026-09-20T12:00:00Z', source: 'auth', host: 'edge', severity: 'HIGH', msg, fields: {} }],
  nextCursor,
  source: 'opensearch',
  degraded: false,
  stat: null,
})

let wrapper: VueWrapper | undefined
async function mountSearch(query: Record<string, string> = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/search', name: 'search', component: SearchView }] })
  await router.push({ name: 'search', query })
  wrapper = mount(RouterView, { global: { plugins: [router] } })
  await flushPromises()
  return router
}

function button(label: string) {
  return wrapper!.findAll('button').find(candidate => candidate.text() === label)!
}

beforeEach(() => {
  setLocale('en-US')
  localStorage.clear()
  api.listFields.mockResolvedValue([])
  api.listAlarmsByEvent.mockResolvedValue([])
  api.splSearch.mockResolvedValue(result('first', 'cursor-2'))
})
afterEach(() => { wrapper?.unmount(); vi.resetAllMocks() })

describe('search investigation state', () => {
  it('allows newlines and IME confirmation without running a query', async () => {
    await mountSearch({ range: 'all' })
    const editor = wrapper!.get('.search-query-row textarea')
    await editor.trigger('keydown', { key: 'Enter', shiftKey: true })
    await editor.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(api.splSearch).toHaveBeenCalledTimes(1)
    await editor.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(api.splSearch).toHaveBeenCalledTimes(2)
  })

  it('keeps paging, exports and links on the applied query while the editor contains a draft', async () => {
    const router = await mountSearch({ q: 'source=auth', range: '30m', to: '2026-09-20T12:00:00.000Z', size: '25' })
    expect(api.splSearch).toHaveBeenCalledTimes(1)
    const scopedQuery = api.splSearch.mock.calls[0][0]
    expect(scopedQuery).toContain('timestamp>=2026-09-20T11:30:00.000Z')
    await wrapper!.get('.search-query-row textarea').setValue('source=web')
    api.splSearch.mockResolvedValueOnce(result('second'))
    await button('Next').trigger('click')
    await flushPromises()
    expect(api.splSearch.mock.calls.at(-1)).toEqual([scopedQuery, expect.objectContaining({ cursor: 'cursor-2', limit: 25 })])
    expect(router.currentRoute.value.query).toMatchObject({ q: 'source=auth', page: '2', size: '25', to: '2026-09-20T12:00:00.000Z' })
    expect(wrapper!.get('.search-query-row textarea').element).toHaveProperty('value', 'source=web')
    expect(wrapper!.get('[role="status"]').text()).toContain('Query edited')
    await button('Export JSON').trigger('click')
    expect(api.exportSearch).toHaveBeenCalledWith(scopedQuery, 'json')
  })

  it('restores the executed query and fixed time window on browser back and forward', async () => {
    const router = await mountSearch({ q: 'source=auth', range: '1h', to: '2026-09-20T12:00:00.000Z' })
    const firstQuery = api.splSearch.mock.calls[0][0]
    await wrapper!.get('.search-query-row textarea').setValue('source=web')
    await button('Run Search').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.q).toBe('source=web')
    router.back()
    await flushPromises()
    expect(api.splSearch.mock.calls.at(-1)?.[0]).toBe(firstQuery)
    expect(wrapper!.get('.search-query-row textarea').element).toHaveProperty('value', 'source=auth')
    router.forward()
    await flushPromises()
    expect(api.splSearch.mock.calls.at(-1)?.[0]).toContain('(source=web)')
    expect(api.splSearch).toHaveBeenCalledTimes(4)
  })

  it('changes page size without applying a draft or advancing the result time window', async () => {
    const router = await mountSearch({ q: 'source=auth', range: '1h', to: '2026-09-20T12:00:00.000Z' })
    const applied = api.splSearch.mock.calls[0][0]
    await wrapper!.get('.search-query-row textarea').setValue('source=web')
    const size = wrapper!.get('.search-pagination').getComponent(ElSelect)
    size.vm.$emit('update:modelValue', 100)
    size.vm.$emit('change', 100)
    await flushPromises()
    expect(api.splSearch.mock.calls.at(-1)).toEqual([applied, expect.objectContaining({ limit: 100 })])
    expect(router.currentRoute.value.query).toMatchObject({ q: 'source=auth', size: '100', to: '2026-09-20T12:00:00.000Z' })
    expect(wrapper!.get('.search-query-row textarea').element).toHaveProperty('value', 'source=web')
  })

  it('does not let a superseded response clear the newer loading state or overwrite its results', async () => {
    const resolvers: Array<(value: SearchResult) => void> = []
    api.splSearch.mockImplementation(() => new Promise<SearchResult>(resolve => resolvers.push(resolve)))
    const router = await mountSearch({ q: 'source=auth' })
    const firstSignal = api.splSearch.mock.calls[0][1].signal as AbortSignal
    await router.push({ name: 'search', query: { q: 'source=web', range: 'all' } })
    await flushPromises()
    expect(firstSignal.aborted).toBe(true)
    resolvers[0](result('obsolete'))
    await flushPromises()
    expect(button('Run Search').classes()).toContain('is-loading')
    expect(wrapper!.text()).not.toContain('obsolete')
    resolvers[1](result('current'))
    await flushPromises()
    expect(button('Run Search').classes()).not.toContain('is-loading')
    expect(wrapper!.text()).toContain('current')
  })

  it('lands on the last successful page when restoring a cursor chain fails', async () => {
    api.splSearch.mockResolvedValueOnce(result('page one', 'cursor-2')).mockRejectedValueOnce(new Error('Backend unavailable'))
    const router = await mountSearch({ q: '*', range: 'all', page: '3' })
    expect(api.splSearch).toHaveBeenCalledTimes(2)
    expect(wrapper!.text()).toContain('page one')
    expect(wrapper!.text()).toContain('Backend unavailable')
    expect(router.currentRoute.value.query.page).toBeUndefined()
  })

  it('ignores malformed saved preferences without preventing search', async () => {
    localStorage.setItem('socp.search.saved-queries', '{"invalid":true}')
    await mountSearch({ range: 'all' })
    expect(wrapper!.findAll('.search-saved-remove')).toHaveLength(0)
    expect(wrapper!.text()).toContain('first')
  })
})
