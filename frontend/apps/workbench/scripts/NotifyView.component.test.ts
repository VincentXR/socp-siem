import { mount, flushPromises } from '@vue/test-utils'
import { h, ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import NotifyView from '../src/views/NotifyView.vue'
import { WORKBENCH_STATE } from '../src/app/workbenchState'

const mocks = vi.hoisted(() => ({
  listChannels: vi.fn(), dispatchLog: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), testChannel: vi.fn(),
  deleteChannel: vi.fn(), toggleChannel: vi.fn(), success: vi.fn(), info: vi.fn(), confirm: vi.fn(),
}))
vi.mock('../src/api', async original => ({ ...await original<object>(), ...mocks }))
vi.mock('element-plus/es/components/message/index.mjs', () => ({ default: { success: mocks.success, info: mocks.info } }))
vi.mock('element-plus/es/components/message-box/index.mjs', () => ({ default: { confirm: mocks.confirm } }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

const channel = { id: 'channel-one', name: 'Saved channel', type: 'SLACK', target: 'https://example.com/webhook', enabled: false, description: '' }
let wrapper: ReturnType<typeof mount>
async function open(existing = false) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/notify', component: NotifyView }, { path: '/elsewhere', component: { template: '<p>Elsewhere</p>' } },
  ] })
  await router.push('/notify')
  await router.isReady()
  wrapper = mount({ render: () => h(RouterView) }, { attachTo: document.body, global: {
    plugins: [router],
    provide: { [WORKBENCH_STATE as symbol]: { currentRole: ref('admin') } },
  } })
  await flushPromises()
  await click(existing ? '编辑' : '新建渠道')
  if (!existing) {
    await wrapper.find('.el-dialog input').setValue('Saved channel')
    await wrapper.find('.el-dialog input[placeholder="https://example.com/webhook"]').setValue(channel.target)
  }
  return router
}

async function click(label: string) {
  const button = wrapper.findAll('button').find(item => item.text() === label)
  expect(button, label).toBeTruthy()
  await button!.trigger('click')
  await flushPromises()
}

describe('notification configuration and test delivery', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.listChannels.mockResolvedValue({ items: [], total: 0 })
    mocks.dispatchLog.mockResolvedValue({ items: [], total: 0 })
    mocks.createChannel.mockImplementation(async payload => ({ ...payload, id: channel.id }))
    mocks.updateChannel.mockImplementation(async (id, payload) => ({ ...payload, id }))
    mocks.testChannel.mockResolvedValue({ status: 'sent' })
    mocks.confirm.mockResolvedValue('confirm')
  })
  afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

  it('retains the acknowledged channel after a failed test and retries without another write', async () => {
    mocks.testChannel.mockRejectedValueOnce(new Error('Test delivery unavailable'))
    const router = await open()
    await click('保存并测试')
    expect(mocks.createChannel).toHaveBeenCalledTimes(1)
    expect(mocks.createChannel.mock.calls[0][0].enabled).toBe(false)
    expect(wrapper.find('.el-dialog').text()).toContain('渠道配置已保存，但测试未确认送达')
    expect(wrapper.find('.el-dialog [role="alert"]').text()).toContain('Test delivery unavailable')
    expect(wrapper.find('.el-dialog input').element).toHaveProperty('value', channel.name)
    expect(mocks.testChannel).toHaveBeenCalledWith(channel.id)
    await click('重新测试')
    expect(mocks.createChannel).toHaveBeenCalledTimes(1)
    expect(mocks.updateChannel).not.toHaveBeenCalled()
    expect(mocks.testChannel).toHaveBeenCalledTimes(2)
    await router.push('/elsewhere')
    expect(router.currentRoute.value.path).toBe('/elsewhere')
    expect(mocks.confirm).not.toHaveBeenCalled()
  })

  it('updates the same saved channel when the operator corrects it after test failure', async () => {
    mocks.testChannel.mockRejectedValueOnce(new Error('Rejected target'))
    await open()
    await click('保存并测试')
    await wrapper.find('.el-dialog input[placeholder="https://example.com/webhook"]').setValue('https://example.com/corrected')
    await click('保存并测试')
    expect(mocks.createChannel).toHaveBeenCalledTimes(1)
    expect(mocks.updateChannel).toHaveBeenCalledWith(channel.id, expect.objectContaining({ target: 'https://example.com/corrected' }))
    expect(mocks.testChannel).toHaveBeenLastCalledWith(channel.id)
  })

  it('does not start a test or clear inputs when persistence fails', async () => {
    mocks.createChannel.mockRejectedValueOnce(new Error('Save unavailable'))
    await open()
    await click('保存并测试')
    expect(mocks.testChannel).not.toHaveBeenCalled()
    expect(wrapper.find('.el-dialog input').element).toHaveProperty('value', channel.name)
    expect(wrapper.find('.el-dialog [role="alert"]').text()).toContain('Save unavailable')
    expect(wrapper.find('.el-dialog').text()).not.toContain('渠道配置已保存')
  })

  it('blocks closing, route/query changes and beforeunload during an unchanged-form test', async () => {
    mocks.listChannels.mockResolvedValue({ items: [channel], total: 1 })
    const result = deferred<{ status: string }>()
    mocks.testChannel.mockReturnValueOnce(result.promise)
    const router = await open(true)
    await click('保存并测试')
    expect(mocks.updateChannel).not.toHaveBeenCalled()
    expect(wrapper.find('.el-dialog input').attributes('disabled')).toBeDefined()
    await click('保存并测试')
    expect(mocks.testChannel).toHaveBeenCalledTimes(1)
    await router.push('/elsewhere')
    expect(router.currentRoute.value.fullPath).toBe('/notify')
    await router.push('/notify?different=1')
    expect(router.currentRoute.value.fullPath).toBe('/notify')
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    expect(mocks.confirm).not.toHaveBeenCalled()
    result.reject(new Error('Test failed'))
    await flushPromises()
    await router.push('/elsewhere')
    expect(router.currentRoute.value.path).toBe('/elsewhere')
  })

  it('keeps read refreshes separate and ignores a late pre-save catalog response', async () => {
    const stale = deferred<{ items: typeof channel[]; total: number }>()
    mocks.listChannels.mockReturnValueOnce(stale.promise)
    mocks.listChannels.mockResolvedValue({ items: [channel], total: 1 })
    await open()
    await click('保存')
    expect(wrapper.text()).toContain('Saved channel')
    stale.resolve({ items: [{ ...channel, name: 'Obsolete catalog' }], total: 1 })
    await flushPromises()
    expect(wrapper.text()).not.toContain('Obsolete catalog')
    expect(wrapper.text()).toContain('Saved channel')
  })

  it('distinguishes local logging from external delivery and rejects an unknown success status', async () => {
    mocks.testChannel.mockResolvedValueOnce({ status: 'queued' }).mockResolvedValueOnce({ status: 'logged' })
    await open()
    await click('保存并测试')
    expect(mocks.success).not.toHaveBeenCalled()
    expect(wrapper.find('.el-dialog [role="alert"]').text()).toContain('响应未确认测试送达')
    await click('重新测试')
    expect(mocks.success).toHaveBeenCalledWith('测试已记录到本地，未发送外部通知')
    expect(mocks.createChannel).toHaveBeenCalledTimes(1)
  })
})
