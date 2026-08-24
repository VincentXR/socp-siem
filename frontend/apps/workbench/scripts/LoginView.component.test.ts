import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from '../src/LoginView.vue'

const login = vi.hoisted(() => vi.fn())
const storage = new Map<string, string>()

const localStorageMock = {
  getItem: (key: string) => storage.get(key) ?? null,
  setItem: (key: string, value: string) => { storage.set(key, value) },
  removeItem: (key: string) => { storage.delete(key) },
  clear: () => { storage.clear() },
}

vi.mock('../src/api', () => ({ login }))

describe('LoginView', () => {
  beforeEach(() => {
    login.mockReset()
    vi.stubGlobal('localStorage', localStorageMock)
    localStorageMock.clear()
  })

  afterEach(() => vi.unstubAllGlobals())

  it('submits credentials and emits the authenticated user', async () => {
    login.mockResolvedValue({ username: 'alice', role: 'analyst' })
    const wrapper = mount(LoginView)

    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('alice')
    await inputs[1].setValue('secret')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(login).toHaveBeenCalledWith('alice', 'secret')
    expect(wrapper.emitted('done')).toEqual([['alice', 'analyst']])
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
