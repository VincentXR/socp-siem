import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import UebaWatchlistsPanel from '../src/components/ueba/UebaWatchlistsPanel.vue'

describe('watchlist persistence feedback', () => {
  it('loads members only when opened and retries a failed detail request', async () => {
    const load = vi.fn().mockRejectedValueOnce(new Error('Details unavailable')).mockResolvedValueOnce({ name: 'admins', size: 1, values: ['alice'] })
    const wrapper = mount(UebaWatchlistsPanel, { global: { stubs: { teleport: true, PagerBar: true } }, props: {
      watchlists: [{ name: 'admins', size: 1 }], load, append: vi.fn(), create: vi.fn(),
    } })
    await flushPromises()
    expect(load).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(item => item.text() === '条目管理')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Details unavailable')
    await wrapper.findAll('button').find(item => item.text() === '重试')!.trigger('click')
    await flushPromises()
    expect(load).toHaveBeenLastCalledWith('admins')
    expect(wrapper.text()).toContain('alice')
    wrapper.unmount()
  })

  it('ignores older detail responses after selecting a different list', async () => {
    let resolveFirst!: (value: { name: string; size: number; values: string[] }) => void
    const first = new Promise<{ name: string; size: number; values: string[] }>(resolve => { resolveFirst = resolve })
    const load = vi.fn((name: string) => name === 'admins' ? first : Promise.resolve({ name: 'staff', size: 1, values: ['bob'] }))
    const wrapper = mount(UebaWatchlistsPanel, { global: { stubs: { teleport: true, PagerBar: true } }, props: {
      watchlists: [{ name: 'admins', size: 1 }, { name: 'staff', size: 1 }], load, append: vi.fn(), create: vi.fn(),
    } })
    await flushPromises()
    const buttons = wrapper.findAll('button').filter(item => item.text() === '条目管理')
    await buttons[0]!.trigger('click')
    await buttons[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('bob')
    resolveFirst({ name: 'admins', size: 1, values: ['alice'] })
    await flushPromises()
    expect(wrapper.text()).toContain('bob')
    expect(wrapper.text()).not.toContain('alice')
    wrapper.unmount()
  })

  it('keeps append input when persistence fails and clears it only on success', async () => {
    const append = vi.fn().mockRejectedValueOnce(new Error('Database unavailable')).mockResolvedValueOnce({ name: 'admins', size: 3, values: ['alice', 'bob', 'carol'] })
    const wrapper = mount(UebaWatchlistsPanel, { global: { stubs: { teleport: true, PagerBar: true } }, props: {
      watchlists: [{ name: 'admins', size: 1 }], append, create: vi.fn(),
      load: vi.fn().mockResolvedValue({ name: 'admins', size: 1, values: ['alice'] }),
    } })
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '条目管理')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('a[href="/detect?reference=admins"]').exists()).toBe(true)
    const input = wrapper.find('textarea[placeholder="追加值"]')
    await input.setValue('bob, carol')
    const button = wrapper.findAll('button').find(item => item.text() === '追加')!
    await button.trigger('click'); await flushPromises()
    expect((input.element as HTMLTextAreaElement).value).toBe('bob, carol')
    expect(wrapper.text()).toContain('Database unavailable')
    await button.trigger('click'); await flushPromises()
    expect(append).toHaveBeenLastCalledWith('admins', ['bob', 'carol'])
    expect((input.element as HTMLTextAreaElement).value).toBe('')
    wrapper.unmount()
  })
})
