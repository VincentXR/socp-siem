import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'
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
async function mountSearch(query: Record<string, string> = {}, identity = { currentUser: ref('alice'), currentTenant: ref('tenant-a') }) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/search', name: 'search', component: SearchView }] })
  await router.push({ name: 'search', query })
  wrapper = mount(RouterView, { global: { plugins: [router], provide: { [WORKBENCH_STATE as symbol]: identity } } })
  await flushPromises()
  return router
}

function button(label: string) {
  return wrapper!.findAll('button').find(candidate => candidate.text() === label)!
}

beforeEach(() => {
  setLocale('en-US')
  window.localStorage.clear()
  api.listFields.mockResolvedValue([])
  api.listAlarmsByEvent.mockResolvedValue([])
  api.splSearch.mockResolvedValue(result('first', 'cursor-2'))
})
afterEach(() => { wrapper?.unmount(); vi.resetAllMocks() })

describe('search investigation state', () => {
  it('isolates saved queries across tenant and subject changes and discards the unscoped legacy store', async () => {
    const item = (name: string) => JSON.stringify([{ id: name, name, query: 'source=auth', range: 'all' }])
    localStorage.setItem('socp.search.saved-queries', item('legacy-secret'))
    localStorage.setItem('socp.search.saved-queries.v2:tenant-a:alice', item('A Alice'))
    localStorage.setItem('socp.search.saved-queries.v2:tenant-b:alice', item('B Alice'))
    const identity = { currentUser: ref('alice'), currentTenant: ref('tenant-a') }
    await mountSearch({}, identity)
    expect(wrapper!.text()).toContain('A Alice')
    expect(wrapper!.text()).not.toContain('legacy-secret')
    expect(localStorage.getItem('socp.search.saved-queries')).toBeNull()
    identity.currentTenant.value = 'tenant-b'; await flushPromises()
    expect(wrapper!.text()).toContain('B Alice')
    expect(wrapper!.text()).not.toContain('A Alice')
    identity.currentUser.value = 'bob'; await flushPromises()
    expect(wrapper!.text()).not.toContain('B Alice')
  })

  it('does not execute generated SPL until the analyst reviews and explicitly runs it', async () => {
    const router = await mountSearch({ draft: 'source=auth | stats count by user', range: 'all', alarmId: 'alarm-1', returnTo: '/ai?alarmId=alarm-1' })
    expect(api.splSearch).not.toHaveBeenCalled()
    expect(wrapper!.get('.search-query-row textarea').element).toHaveProperty('value', 'source=auth | stats count by user')
    await button('Run Search').trigger('click'); await flushPromises()
    expect(api.splSearch.mock.calls[0][0]).toBe('source=auth | stats count by user')
    expect(router.currentRoute.value.query).toMatchObject({ q: 'source=auth | stats count by user', alarmId: 'alarm-1' })
    expect(router.currentRoute.value.query.draft).toBeUndefined()
  })
  it('applies custom UTC bounds before the pipeline and rejects reversed ranges', async () => {
    await mountSearch({ q: 'host="edge" | stats count', range: 'custom', from: '2026-10-01T00:00:00.000Z', to: '2026-10-02T00:00:00.000Z' })
    expect(api.splSearch.mock.calls[0][0]).toBe('(host="edge") AND timestamp>=2026-10-01T00:00:00.000Z AND timestamp<=2026-10-02T00:00:00.000Z | stats count')
    const dates = wrapper!.findAll('input[type="datetime-local"]')
    await dates[0].setValue('2026-10-03T00:00:00')
    await button('Run Search').trigger('click'); await flushPromises()
    expect(api.splSearch).toHaveBeenCalledTimes(1)
  })

  it('serializes export formats, retains the applied query and restores controls after failure', async () => {
    let reject!: (error: Error) => void
    api.exportSearch.mockReturnValueOnce(new Promise((_resolve, failure) => { reject = failure }))
    await mountSearch({ q: 'source=auth', range: 'all' })
    const applied = api.splSearch.mock.calls[0]?.[0]
    await button('Export JSON').trigger('click')
    await button('Export CSV').trigger('click')
    expect(api.exportSearch).toHaveBeenCalledExactlyOnceWith(applied, 'json')
    expect(button('Export CSV').attributes('disabled')).toBeDefined()
    await wrapper!.get('.search-query-row textarea').setValue('source=web')
    reject(new Error('Export unavailable')); await flushPromises()
    expect(button('Export CSV').attributes('disabled')).toBeUndefined()
    await button('Export CSV').trigger('click'); await flushPromises()
    expect(api.exportSearch).toHaveBeenLastCalledWith(applied, 'csv')
    expect(api.exportSearch).toHaveBeenCalledTimes(2)
  })

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

  it('keeps page-size changes on applied custom bounds when range fields are edited', async () => {
    const router = await mountSearch({ q: 'host=edge', range: 'custom', from: '2026-10-01T00:00:00.000Z', to: '2026-10-02T00:00:00.000Z' })
    const applied = api.splSearch.mock.calls[0][0]
    const dates = wrapper!.findAll('input[type="datetime-local"]')
    await dates[0].setValue('2026-10-03T00:00:00')
    const size = wrapper!.get('.search-pagination').getComponent(ElSelect)
    size.vm.$emit('update:modelValue', 100)
    size.vm.$emit('change', 100)
    await flushPromises()
    expect(api.splSearch.mock.calls.at(-1)).toEqual([applied, expect.objectContaining({ limit: 100 })])
    expect(router.currentRoute.value.query).toMatchObject({ range: 'custom', from: '2026-10-01T00:00:00.000Z', to: '2026-10-02T00:00:00.000Z' })
    expect(dates[0].element).toHaveProperty('value', '2026-10-03T00:00')
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
