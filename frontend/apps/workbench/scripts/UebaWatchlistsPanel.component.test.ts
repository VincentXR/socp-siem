import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import UebaWatchlistsPanel from '../src/components/ueba/UebaWatchlistsPanel.vue'

describe('watchlist persistence feedback', () => {
  it('keeps append input when persistence fails and clears it only on success', async () => {
    const append = vi.fn().mockRejectedValueOnce(new Error('Database unavailable')).mockResolvedValueOnce(undefined)
    const wrapper = mount(UebaWatchlistsPanel, { global: { stubs: { teleport: true, PagerBar: true } }, props: {
      watchlists: [{ name: 'admins', size: 1, values: ['alice'] }], append, create: vi.fn(),
    } })
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '条目管理')!.trigger('click')
    await flushPromises()
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
