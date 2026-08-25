import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import UebaWatchlistsPanel from '../src/components/ueba/UebaWatchlistsPanel.vue'

describe('UebaWatchlistsPanel', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('emits bounded watchlist operations from the cards', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))
    const wrapper = mount(UebaWatchlistsPanel, {
      props: { watchlists: [{ name: 'admins', size: 1, values: ['alice'] }] },
    })

    const appendInput = wrapper.find('input[placeholder="追加值"]')
    await appendInput.setValue('bob, carol')
    const appendButton = wrapper.findAll('button').find(button => button.text() === '追加')
    expect(appendButton).toBeTruthy()
    await appendButton!.trigger('click')
    expect(wrapper.emitted('append')).toEqual([['admins', ['bob', 'carol']]])

    const deleteButton = wrapper.findAll('button').find(button => button.text() === '删除')
    expect(deleteButton).toBeTruthy()
    await deleteButton!.trigger('click')
    expect(wrapper.emitted('remove')).toEqual([['admins']])
  })
})
