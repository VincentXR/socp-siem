import { flushPromises, mount, shallowMount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OverviewView from '../src/views/OverviewView.vue'
import ElButton from 'element-plus/es/components/button/index.mjs'
import PageHeader from '../src/components/PageHeader.vue'
import MetricCard from '../src/components/MetricCard.vue'
import type { Alarm } from '../src/api'
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
  it('renders missing alarm dispositions as unknown without requesting an undefined translation key', async () => {
    const warning = vi.spyOn(console, 'warn')
    const wrapper = mount(OverviewView, { props: {
      stat: { total: 2, critical: 0, high: 2, activeCases: 0, online: 0 }, healths: {},
      filteredAlarms: [
        { id: 'missing-status', ruleName: 'Missing disposition', severity: 'HIGH' },
        { id: 'open-status', ruleName: 'Known disposition', severity: 'HIGH', status: 'OPEN' },
      ] as unknown as Alarm[],
      availability: { alarms: true, stats: true, cases: true },
    }, global: { stubs: { TrendChart: true, AnimatedNumber: true } } })
    try {
      await flushPromises()
      expect(wrapper.findAll('.ov-alert-status').map(status => status.text())).toEqual(['未知', '待处理'])
      expect(warning.mock.calls.flat().join(' ')).not.toContain('statuses.undefined')
    } finally { wrapper.unmount() }
  })

  it('keeps zero severity bars empty, includes unclassified alarms and counts only observed target services', () => {
    const wrapper = shallowMount(OverviewView, { props: {
      stat: { total: 10, critical: 0, high: 6, activeCases: 0, online: 99 },
      sitStats: { bySeverity: { HIGH: 6, UNKNOWN: 4 } },
      healths: { 'detect-web': 'up', 'report-web': 'unknown', unregistered: 'up' }, filteredAlarms: [],
      availability: { alarms: true, stats: true, cases: true },
    }, global: { renderStubDefaultSlot: true, stubs: { ElCard: { template: '<section><header><slot name="header" /></header><slot /></section>' } } } })
    try {
      expect(wrapper.find('[data-level="CRITICAL"] .overview-severity-fill').attributes('style')).toContain('width: 0%')
      expect(wrapper.find('[data-level="HIGH"] .overview-severity-fill').attributes('style')).toContain('width: 60%')
      expect(wrapper.find('[data-level="UNKNOWN"]').text()).toContain('4')
      expect(wrapper.find('[data-level="UNKNOWN"] .overview-severity-fill').attributes('style')).toContain('width: 40%')
      expect(wrapper.find('#overview-service-health header').text()).toContain('1 / 1')
      expect(wrapper.findAll('.overview-health-segments [data-state="up"]')).toHaveLength(1)
      expect(wrapper.find('.overview-hero-empty').text()).toContain('暂无按日趋势数据')
      expect(wrapper.find('.overview-hero-chart-head .mono').text()).toContain('—')
    } finally { wrapper.unmount() }
  })

  it('loads the priority queue from active alarms, not the historical top-risk summary', async () => {
    const historical = { id: 'closed', ruleName: 'Closed historical risk', entity: 'host', severity: 'HIGH', riskScore: 99 }
    const pending = { ...historical, id: 'pending', ruleName: 'Older pending investigation', status: 'INVESTIGATING', occurredAt: '2020-01-01T00:00:00Z', riskScore: 70 }
    mocks.alarmStats.mockResolvedValue({ total: 1, bySeverity: { HIGH: 1 }, topRisk: [historical] })
    mocks.listAlarmsPaged.mockImplementation((_page, _size, _q, _severity, status) => Promise.resolve({ items: status === 'ACTIVE' ? [pending] : [] }))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
    const openAlarm = vi.fn()
    let state!: ReturnType<typeof useOverview>
    const host = defineComponent({ setup() {
      state = useOverview(ref(true))
      return () => h(OverviewView, { stat: state.stat.value, healths: state.healths.value,
        filteredAlarms: state.alarms.value, sitStats: state.sitStats.value,
        priorityAlarms: state.priorityAlarms.value, availability: state.availability.value, openAlarm })
    } })
    const wrapper = shallowMount(host, { global: { plugins: [[VueQueryPlugin, { queryClient: client }]],
      stubs: { OverviewView: false }, renderStubDefaultSlot: true } })
    try {
      await vi.waitFor(() => expect(state.refreshing.value).toBe(false))
      const queue = wrapper.find('.overview-priority-card')
      expect(queue.text()).not.toContain('Closed historical risk')
      expect(queue.text()).toContain('Older pending investigation')
      expect(mocks.listAlarmsPaged).toHaveBeenCalledWith(1, 5, undefined, undefined, 'ACTIVE', undefined,
        'riskScore', 'descending', expect.objectContaining({ signal: expect.any(AbortSignal) }))
      await queue.find('button.overview-priority-item').trigger('click')
      expect(openAlarm).toHaveBeenCalledWith('pending')

      // A successful statistics response cannot turn a failed queue read into
      // a successful empty queue, even on its first request.
      client.removeQueries({ queryKey: ['overview', 'priority'] })
      mocks.listAlarmsPaged.mockImplementation((_page, _size, _q, _severity, status) => status === 'ACTIVE'
        ? Promise.reject(new Error('priority queue unavailable')) : Promise.resolve({ items: [] }))
      await state.refreshOverview(); await flushPromises()
      expect(state.error.value).toBe('priority queue unavailable')
      expect(state.availability.value.priority).toBe(false)
      expect(wrapper.find('.overview-priority-card [role="status"]').exists()).toBe(true)
    } finally { wrapper.unmount(); client.clear() }
  })

  it('uses all dispositions for historical severity counts but retains the work-queue default', () => {
    const goAlarms = vi.fn()
    const wrapper = shallowMount(OverviewView, { props: {
      stat: { total: 5, critical: 2, high: 3, activeCases: 0, online: 0 }, healths: {}, filteredAlarms: [], goAlarms,
    }, global: { renderStubDefaultSlot: true } })
    try {
      wrapper.findAllComponents(MetricCard)[0]!.vm.$emit('click')
      expect(goAlarms).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'ALL', severity: 'CRITICAL' }))
      wrapper.find('.overview-actions button').trigger('click')
      expect(goAlarms).toHaveBeenLastCalledWith()
    } finally { wrapper.unmount() }
  })

  it('labels a partial initial load and does not present unknown totals as zero', () => {
    const wrapper = shallowMount(OverviewView, { props: {
      stat: { total: 0, critical: 0, high: 0, activeCases: 0, online: 0 }, healths: {}, filteredAlarms: [],
      availability: { alarms: false, stats: false, cases: false }, updatedAt: 0, error: 'offline',
    }, global: { renderStubDefaultSlot: true, stubs: { PageHeader: false } } })
    try {
      expect(wrapper.findComponent(PageHeader).text()).toContain('部分数据未能加载')
      expect(wrapper.find('.overview-window').text()).toContain('—')
    } finally { wrapper.unmount() }
  })

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
