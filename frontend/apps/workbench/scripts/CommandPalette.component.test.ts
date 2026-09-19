import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import AppShell from '../src/components/AppShell.vue'
import CommandPalette from '../src/components/CommandPalette.vue'
import { getVisibleMenuGroups, type MenuGroup } from '../src/app/navigation'
import { translate } from '../src/i18n'

const menuGroups = getVisibleMenuGroups('admin', translate)
const items = menuGroups.flatMap(group => group.items.map(item => ({ ...item, group: group.group })))
const alarmsEntry = items.find(item => item.key === 'alarms')!
const otherGroupEntry = items.find(item => item.key === 'meta')!

function press(target: EventTarget, key: string, init: KeyboardEventInit = {}): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init })
  target.dispatchEvent(event)
  return event
}

describe('CommandPalette', () => {
  async function mountPalette(props: { modelValue: boolean, menuGroups: MenuGroup[] }) {
    const wrapper = mount(CommandPalette, { props, global: { stubs: { teleport: true } } })
    await flushPromises()
    return wrapper
  }

  it('lists every visible entry and filters by page or group name', async () => {
    const wrapper = await mountPalette({ modelValue: true, menuGroups })
    expect(wrapper.findAll('.command-palette-item')).toHaveLength(items.length)

    await wrapper.find('.command-palette input').setValue(alarmsEntry.label)
    const filtered = wrapper.findAll('.command-palette-item')
    expect(filtered).toHaveLength(1)
    expect(filtered[0].attributes('data-menu-key')).toBe('alarms')

    await wrapper.find('.command-palette input').setValue(otherGroupEntry.group)
    const groupMatches = wrapper.findAll('.command-palette-item').map(row => row.attributes('data-menu-key'))
    expect(groupMatches).toContain(otherGroupEntry.key)

    await wrapper.find('.command-palette input').setValue('zzz-no-such-page')
    expect(wrapper.findAll('.command-palette-item')).toHaveLength(0)
    expect(wrapper.find('.command-palette-empty').text()).toBe(translate('common.empty'))
    wrapper.unmount()
  })

  it('moves the highlight with arrow keys and jumps on Enter or row click', async () => {
    const wrapper = await mountPalette({ modelValue: true, menuGroups })
    const input = wrapper.find('.command-palette input')
    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(wrapper.findAll('.command-palette-item')[1].attributes('aria-selected')).toBe('true')
    await input.trigger('keydown', { key: 'ArrowUp' })
    expect(wrapper.findAll('.command-palette-item')[0].attributes('aria-selected')).toBe('true')

    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('select')?.at(-1)).toEqual([items[0].key])
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])

    await wrapper.findAll('.command-palette-item')[2].trigger('click')
    expect(wrapper.emitted('select')?.at(-1)).toEqual([items[2].key])

    await input.trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('select')?.at(-1)).toEqual([items[2].key])
    wrapper.unmount()
  })

  it('resets the query on reopen and forwards the dialog close', async () => {
    const wrapper = await mountPalette({ modelValue: true, menuGroups })
    await wrapper.find('.command-palette input').setValue('search')
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    expect((wrapper.find('.command-palette input').element as HTMLInputElement).value).toBe('')

    wrapper.findComponent(ElDialog).vm.$emit('update:modelValue', false)
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])
    wrapper.unmount()
  })
})

describe('AppShell quick jump', () => {
  async function mountShell() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', name: 'shell', component: { template: '<div />' } }],
    })
    await router.push('/overview')
    await router.isReady()
    const wrapper = mount(AppShell, {
      props: {
        menuGroups,
        activeMenu: 'overview',
        activeLabel: items[0].label,
        theme: 'light' as const,
        currentUser: 'analyst',
        currentRole: 'analyst',
        userInitials: 'AN',
      },
      global: { plugins: [router], stubs: { teleport: true } },
    })
    await flushPromises()
    return wrapper
  }

  it('opens on Ctrl+K, keeps Escape close harmless, and removes the listener on unmount', async () => {
    const wrapper = await mountShell()
    expect(wrapper.findComponent(CommandPalette).props('modelValue')).toBe(false)

    const event = press(document, 'k', { ctrlKey: true })
    expect(event.defaultPrevented).toBe(true)
    await flushPromises()
    expect(wrapper.findComponent(CommandPalette).props('modelValue')).toBe(true)
    expect(wrapper.find('.command-palette').exists()).toBe(true)

    wrapper.findComponent(CommandPalette).vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(wrapper.findComponent(CommandPalette).props('modelValue')).toBe(false)
    const cmdEvent = press(document, 'k', { metaKey: true })
    expect(cmdEvent.defaultPrevented).toBe(true)
    await flushPromises()
    expect(wrapper.findComponent(CommandPalette).props('modelValue')).toBe(true)

    press(document.body, 'Escape')
    await flushPromises()
    expect(() => press(document, 'k', { ctrlKey: true })).not.toThrow()
    await flushPromises()
    expect(wrapper.findComponent(CommandPalette).props('modelValue')).toBe(true)
    expect(wrapper.find('.command-palette').exists()).toBe(true)

    wrapper.unmount()
    expect(() => press(document, 'k', { ctrlKey: true })).not.toThrow()
  })

  it('still fires while a field owns focus and routes the pick through menu-change', async () => {
    const wrapper = await mountShell()
    const field = document.createElement('input')
    document.body.appendChild(field)
    const event = press(field, 'k', { ctrlKey: true })
    await flushPromises()
    document.body.removeChild(field)
    expect(event.defaultPrevented).toBe(true)
    expect(wrapper.findComponent(CommandPalette).props('modelValue')).toBe(true)

    const input = wrapper.find('.command-palette input')
    await input.setValue(alarmsEntry.label)
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('menu-change')?.at(-1)).toEqual(['alarms'])
    await flushPromises()
    expect(wrapper.findComponent(CommandPalette).props('modelValue')).toBe(false)
    wrapper.unmount()
  })
})
