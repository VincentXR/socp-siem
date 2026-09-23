import { flushPromises, shallowMount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OverviewView from '../src/views/OverviewView.vue'
import ElButton from 'element-plus/es/components/button/index.mjs'
import PageHeader from '../src/components/PageHeader.vue'
import { useOverview } from '../src/composables/useOverview'

const mocks = vi.hoisted(() => ({ alarmStats: vi.fn(), caseStats: vi.fn(), getHealthSnapshot: vi.fn(), listAlarmsPaged: vi.fn() }))
vi.mock('../src/api', async original => ({ ...await original<typeof import('../src/api')>(), ...mocks }))
beforeEach(() => {
  vi.resetAllMocks()
  mocks.alarmStats.mockResolvedValue({ total: 1, bySeverity: { HIGH: 1 } })
  mocks.caseStats.mockResolvedValue({ open: 1 })
  mocks.getHealthSnapshot.mockResolvedValue({ services: { detect: 'up' } })
  mocks.listAlarmsPaged.mockResolvedValue({ items: [] })
})
afterEach(() => vi.restoreAllMocks())

describe('overview successful refresh time', () => {
  it('keeps the oldest successful fetch through an in-flight or failed refresh', async () => {
    const clock = vi.spyOn(Date, 'now').mockReturnValue(1_000_000)
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
    let state!: ReturnType<typeof useOverview>
    const host = defineComponent({ setup() {
      state = useOverview(ref(true))
      return () => h(OverviewView, { stat: state.stat.value, healths: state.healths.value,
        filteredAlarms: state.alarms.value, updatedAt: state.updatedAt.value, refreshing: state.refreshing.value,
        error: state.error.value, onRefresh: state.refreshOverview })
    } })
    const wrapper = shallowMount(host, { global: { plugins: [[VueQueryPlugin, { queryClient: client }]],
      stubs: { OverviewView: false, PageHeader: false }, renderStubDefaultSlot: true } })
    try {
      await vi.waitFor(() => expect(state.updatedAt.value).toBe(1_000_000))
      const heading = () => wrapper.findComponent(PageHeader).text()
      const before = heading()
      let reject!: (cause: Error) => void
      mocks.caseStats.mockReturnValueOnce(new Promise((_, no) => { reject = no }))
      clock.mockReturnValue(2_000_000)
      wrapper.findComponent(ElButton).vm.$emit('click'); await flushPromises()
      expect(wrapper.findComponent(ElButton).props('loading')).toBe(true)
      expect(heading()).toBe(before)
      reject(new Error('case statistics unavailable'))
      await vi.waitFor(() => expect(state.refreshing.value).toBe(false))
      expect(state.updatedAt.value).toBe(1_000_000)
      expect(heading()).toBe(before)
      expect(wrapper.find('[role="alert"]').text()).toContain('case statistics unavailable')
      clock.mockReturnValue(3_000_000)
      await state.refreshOverview(); await flushPromises()
      expect(state.updatedAt.value).toBe(3_000_000)
      expect(heading()).not.toBe(before)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    } finally { wrapper.unmount(); client.clear() }
  })

  it('does not invent a successful timestamp while an initial source has failed', async () => {
    mocks.caseStats.mockRejectedValue(new Error('no case data'))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
    let state!: ReturnType<typeof useOverview>
    const host = defineComponent({ setup() { state = useOverview(ref(true)); return () => h('div') } })
    const wrapper = shallowMount(host, { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
    try {
      await vi.waitFor(() => expect(state.error.value).toBe('no case data'))
      expect(state.updatedAt.value).toBe(0)
    } finally { wrapper.unmount(); client.clear() }
  })
})
