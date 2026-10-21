import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, expect, it } from 'vitest'
import AppShell from '../src/components/AppShell.vue'

let wrapper: ReturnType<typeof mount>
beforeEach(() => localStorage.clear())
afterEach(() => wrapper?.unmount())

async function openShell() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  wrapper = mount(AppShell, { attachTo: document.body, props: {
    menuGroups: [
      { group: 'Operations', items: [{ key: 'overview', label: 'Overview', icon: 'dashboard' }] },
      { group: 'Data', defaultCollapsed: true, items: [{ key: 'ingest', label: 'Ingest', icon: 'ingest' }] },
      { group: 'Reports', defaultCollapsed: true, items: [{ key: 'report', label: 'Reports', icon: 'report' }] },
    ], activeMenu: 'overview', activeLabel: 'Overview', theme: 'light', currentUser: 'demo', currentRole: 'analyst', userInitials: 'DE',
  }, global: { plugins: [router], stubs: { CommandPalette: true } } })
}

it('expands each previously untouched group on the first click and persists collapse', async () => {
  await openShell()
  for (const index of [1, 2]) {
    const button = wrapper.findAll('.socp-menu-group')[index]!
    const items = wrapper.findAll('.socp-menu-items')[index]!
    expect(items.isVisible()).toBe(false)
    await button.trigger('click')
    expect(button.attributes('aria-expanded')).toBe('true')
    expect(items.isVisible()).toBe(true)
    await button.trigger('click')
    expect(items.isVisible()).toBe(false)
  }
  expect(JSON.parse(localStorage.getItem('socp.sidebar.collapsed-groups')!)).toMatchObject({ ingest: true, report: true })
  wrapper.unmount()
  await openShell()
  expect(wrapper.findAll('.socp-menu-items')[1]!.isVisible()).toBe(false)
  await wrapper.setProps({ activeMenu: 'ingest' })
  expect(wrapper.findAll('.socp-menu-items')[1]!.isVisible()).toBe(true)
})
