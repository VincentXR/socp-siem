import { flushPromises, shallowMount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import CasesView from '../src/views/CasesView.vue'
import { caseApi, type CaseInfo, type TimelineEvent } from '../src/api/domains'

const { query } = vi.hoisted(() => ({ query: {} as Record<string, string> }))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn(),
  useRoute: () => ({ query: reactive(query) }),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}))
vi.mock('../src/api/domains', () => ({ caseApi: {
  list: vi.fn(async () => ({ items: [], total: 0 })), stats: vi.fn(async () => ({ total: 0, open: 0, resolved: 0 })),
  get: vi.fn(), timeline: vi.fn(),
} }))

afterEach(() => { delete query.caseId; vi.clearAllMocks() })
const caseInfo = (id: string): CaseInfo => ({ id, title: id, entity: 'host-a', severity: 'HIGH',
  status: 'OPEN', assignee: '', ruleIds: [], alarmIds: [], timeline: [] })
type Details = { timeline: TimelineEvent[]; detail: CaseInfo }

it('ignores an old timeline even when the cancelled transport still resolves', async () => {
  const pending = new Map<string, (value: unknown) => void>()
  vi.mocked(caseApi.timeline).mockImplementation(id => new Promise(resolve => pending.set(id, resolve as (value: unknown) => void)))
  vi.mocked(caseApi.get).mockImplementation(async id => ({ found: true, case: caseInfo(id) }))
  query.caseId = 'A'
  const wrapper = shallowMount(CasesView)
  await flushPromises()
  const view = wrapper.vm as unknown as Details
  reactive(query).caseId = 'B'
  await flushPromises()
  pending.get('B')!({ items: [{ message: 'B evidence' }] })
  await flushPromises()
  pending.get('A')!({ items: [{ message: 'A evidence' }] })
  await flushPromises()
  expect(view.detail.id).toBe('B')
  expect(view.timeline).toEqual([{ message: 'B evidence' }])
  wrapper.unmount()
})

it('loads a deep-linked case outside the current page', async () => {
  query.caseId = 'off-page'
  vi.mocked(caseApi.get).mockResolvedValue({ found: true, case: caseInfo('off-page') })
  vi.mocked(caseApi.timeline).mockResolvedValue({ items: [], total: 0 } as never)
  const wrapper = shallowMount(CasesView)
  await flushPromises()
  expect(caseApi.get).toHaveBeenCalledWith('off-page', { signal: expect.any(AbortSignal) })
  expect((wrapper.vm as unknown as Details).detail.id).toBe('off-page')
  wrapper.unmount()
})
