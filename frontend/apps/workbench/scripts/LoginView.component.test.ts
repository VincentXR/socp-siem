import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from '../src/LoginView.vue'

const { login, authCapabilities } = vi.hoisted(() => ({ login: vi.fn(), authCapabilities: vi.fn() }))
const storage = new Map<string, string>()

const localStorageMock = {
  getItem: (key: string) => storage.get(key) ?? null,
  setItem: (key: string, value: string) => { storage.set(key, value) },
  removeItem: (key: string) => { storage.delete(key) },
  clear: () => { storage.clear() },
}

vi.mock('../src/api', () => ({ login, authCapabilities }))

describe('LoginView', () => {
  beforeEach(() => {
    login.mockReset()
    authCapabilities.mockReset()
    authCapabilities.mockResolvedValue({ localPassword: true, oidc: false })
    vi.stubGlobal('localStorage', localStorageMock)
    localStorageMock.clear()
  })

  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

  it('shows only organization login when local passwords are unavailable', async () => {
    authCapabilities.mockResolvedValue({ localPassword: false, oidc: true })
    const wrapper = mount(LoginView)
    try {
      await flushPromises()
      expect(wrapper.find('form').exists()).toBe(false)
      expect(wrapper.find('input').exists()).toBe(false)
      expect(wrapper.find('.quick').exists()).toBe(false)
      expect(wrapper.find('.oidc-btn').exists()).toBe(true)
      expect(wrapper.find('.login-capability-status').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('shows safe discovery errors and retries without inventing an available sign-in method', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    authCapabilities.mockRejectedValueOnce(new Error('Internal dependency details'))
      .mockResolvedValueOnce({ localPassword: false, oidc: false })
    const wrapper = mount(LoginView)
    try {
      await flushPromises()
      expect(wrapper.find('[role="alert"]').text()).toContain('暂时无法确认登录方式')
      expect(wrapper.text()).not.toContain('Internal dependency details')
      await wrapper.find('.login-capability-error button').trigger('click')
      await flushPromises()
      expect(authCapabilities).toHaveBeenCalledTimes(2)
      expect(wrapper.find('form').exists()).toBe(false)
      expect(wrapper.find('.oidc-btn').exists()).toBe(false)
      expect(wrapper.text()).toContain('当前环境未配置可用的登录方式')
    } finally { wrapper.unmount() }
  })

  it('submits credentials and emits the authenticated user', async () => {
    login.mockResolvedValue({ username: 'alice', role: 'analyst', tenant: 'tenant-a', permissions: ['soar:publish'] })
    const wrapper = mount(LoginView)

    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('alice')
    await inputs[1].setValue('secret')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(login).toHaveBeenCalledWith('alice', 'secret')
    expect(wrapper.emitted('done')).toEqual([['alice', 'analyst', 'tenant-a', ['soar:publish']]])
    expect(localStorage.getItem('socp_user')).toBe('alice')
    expect(localStorage.getItem('socp_role')).toBe('analyst')
  })

  it('quick-fills the admin credentials', async () => {
    const wrapper = mount(LoginView)

    const adminButton = wrapper.findAll('button').find(button => button.text().includes('admin'))
    expect(adminButton).toBeTruthy()
    await adminButton!.trigger('click')

    expect((wrapper.findAll('input')[0].element as HTMLInputElement).value).toBe('admin')
    expect((wrapper.findAll('input')[1].element as HTMLInputElement).value).toBe('admin123')
  })
})
