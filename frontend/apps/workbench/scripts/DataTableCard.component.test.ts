import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import DataTableCard from '../src/components/DataTableCard.vue'
import PagerBar from '../src/components/PagerBar.vue'
import ElPagination from 'element-plus/es/components/pagination/index.mjs'

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

it('shows the displayed range, including the final and empty page, and resets paging on size changes', async () => {
  const wrapper = mount(PagerBar, { props: { total: 45, currentPage: 2, pageSize: 20 } })
  try {
    expect(wrapper.find('.pager-range').text()).toContain('21–40')
    expect(wrapper.find('.pager-range').text()).toContain('45')
    await wrapper.setProps({ currentPage: 3 })
    expect(wrapper.find('.pager-range').text()).toContain('41–45')
    wrapper.findComponent(ElPagination).vm.$emit('size-change', 10)
    expect(wrapper.emitted('update:currentPage')).toEqual([[1]])
    await wrapper.setProps({ total: 0, currentPage: 1 })
    expect(wrapper.find('.pager-range').text()).toContain('0–0')
  } finally { wrapper.unmount() }
})
