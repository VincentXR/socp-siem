import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import DataTableCard from '../src/components/DataTableCard.vue'

it('hides an empty table on read failure, keeps retry available and preserves stale rows', async () => {
  const retry = vi.fn()
  const wrapper = mount(DataTableCard, { attachTo: document.body, props: { total: 0, error: 'offline', retry },
    slots: { default: '<div data-test="table">Table content</div>' } })
  try {
    expect(wrapper.find('[data-test="table"]').isVisible()).toBe(false)
    expect(wrapper.find('.list-state-empty').exists()).toBe(false)
    await wrapper.find('.list-state-error button').trigger('click')
    expect(retry).toHaveBeenCalledOnce()
    await wrapper.setProps({ total: 2 })
    expect(wrapper.find('[data-test="table"]').isVisible()).toBe(true)
    expect(wrapper.find('[role="alert"]').text()).toContain('offline')
    await wrapper.setProps({ error: '', total: 0 })
    expect(wrapper.find('.list-state-empty').isVisible()).toBe(true)
  } finally { wrapper.unmount() }
})
