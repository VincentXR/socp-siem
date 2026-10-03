import { flushPromises, shallowMount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import AiAssistantView from '../src/views/AiAssistantView.vue'
import type { InvestigationResult } from '../src/api'
import type { InvestigationOptions } from '../src/api/ai'

const mocks = vi.hoisted(() => ({ investigateAlert: vi.fn() }))
vi.mock('../src/api', async original => ({ ...await original<typeof import('../src/api')>(), ...mocks }))

const investigation = (alertId: string, investigationId = `job-${alertId}`): InvestigationResult => ({
  investigationId, alertId, revision: 1, status: 'COMPLETED', analysis: `analysis ${alertId}`,
  recommendedSpl: '', timeline: [], hypotheses: [], citations: [], nextActions: [], degradedSources: [],
})

async function mountAssistant(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/assistant', component: AiAssistantView }] })
  await router.push(path)
  await router.isReady()
  const wrapper = shallowMount(AiAssistantView, { global: { plugins: [router], renderStubDefaultSlot: true } })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => { vi.resetAllMocks() })

describe('AI investigation route ownership', () => {
  it('submits once per alert when navigation clears a remembered job while an old poll finishes', async () => {
    const submissions: string[] = []
    let finishOldPoll!: (result: InvestigationResult) => void
    const oldPoll = new Promise<InvestigationResult>(resolve => { finishOldPoll = resolve })
    mocks.investigateAlert.mockImplementation(async (id: string, options: InvestigationOptions) => {
      if (!options.jobId) submissions.push(id)
      // A real async receipt remembers the job in the URL before polling ends.
      await Promise.resolve()
      options.onJob?.(options.jobId || `job-${id}`)
      return id === 'a' ? oldPoll : investigation(id)
    })
    const { wrapper, router } = await mountAssistant('/assistant?alertId=a')
    try {
      expect(router.currentRoute.value.query).toEqual({ alertId: 'a', jobId: 'job-a' })
      const oldSignal = mocks.investigateAlert.mock.calls[0][1].signal as AbortSignal
      const panel = wrapper.find('.ai-investigation-panel')
      const submit = panel.findAllComponents(ElButton).find(button => !button.props('link') && button.props('type') === 'primary')!
      for (const id of ['b', 'c']) {
        panel.findComponent(ElInput).vm.$emit('update:modelValue', id)
        await nextTick()
        expect(wrapper.find('.ai-analysis').exists()).toBe(false)
        submit.vm.$emit('click')
        await flushPromises()
        expect(router.currentRoute.value.query).toEqual({ alarmId: id, jobId: `job-${id}` })
        expect(wrapper.find('.ai-analysis').text()).toBe(`analysis ${id}`)
      }
      expect(oldSignal.aborted).toBe(true)
      finishOldPoll(investigation('a'))
      await flushPromises()
      expect(wrapper.find('.ai-analysis').text()).toBe('analysis c')
      expect(submissions).toEqual(['a', 'b', 'c'])
      expect(mocks.investigateAlert).toHaveBeenCalledTimes(3)
    } finally {
      wrapper.unmount()
      finishOldPoll(investigation('a'))
      await flushPromises()
    }
  })

  it('resumes a different same-alert job once and respects Back and Forward without resubmitting', async () => {
    mocks.investigateAlert.mockImplementation(async (id: string, options: InvestigationOptions) => {
      await Promise.resolve()
      options.onJob?.(options.jobId!)
      return investigation(id, options.jobId)
    })
    const { wrapper, router } = await mountAssistant('/assistant?alarmId=a&jobId=first')
    try {
      await router.push('/assistant?alarmId=a&jobId=second')
      await flushPromises()
      router.back()
      await flushPromises()
      router.forward()
      await flushPromises()
      expect(mocks.investigateAlert.mock.calls.map(call => call[1].jobId)).toEqual(['first', 'second', 'first', 'second'])
      expect(wrapper.find('.ai-analysis').text()).toBe('analysis a')
      expect(router.currentRoute.value.query.jobId).toBe('second')
    } finally { wrapper.unmount() }
  })
})
