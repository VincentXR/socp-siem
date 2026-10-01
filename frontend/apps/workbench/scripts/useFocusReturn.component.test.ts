import { flushPromises, mount } from '@vue/test-utils'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import { defineComponent, h, nextTick, ref } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import { useFocusReturn } from '../src/composables/useFocusReturn'

const cleanups: (() => void)[] = []
afterEach(() => { cleanups.splice(0).forEach(cleanup => cleanup()); document.body.innerHTML = '' })

function fixture() {
  const open = ref(false)
  const present = ref(true)
  let closed = 0
  const Surface = defineComponent({
    setup() {
      const restoreFocus = useFocusReturn(open)
      return () => h(ElDrawer, {
        modelValue: open.value,
        'onUpdate:modelValue': (value: boolean) => { open.value = value },
        onClosed: () => { closed += 1; restoreFocus() },
        title: 'Investigation',
      }, () => h('button', { class: 'inside' }, 'Investigate'))
    },
  })
  const Host = defineComponent({
    setup: () => () => h('main', { id: 'main-content', tabindex: '-1' }, [
      h('button', { class: 'opener', onClick: () => { open.value = true } }, 'Open'),
      h('button', { class: 'next-target' }, 'Next task'),
      present.value ? h(Surface) : null,
    ]),
  })
  const wrapper = mount(Host, { attachTo: document.body, global: { stubs: { transition: false } } })
  cleanups.push(() => wrapper.unmount())
  return { wrapper, open, present, closed: () => closed }
}

async function openDrawer(value: ReturnType<typeof fixture>, keyboard: boolean) {
  if (keyboard) (value.wrapper.find('.opener').element as HTMLElement).focus()
  else document.body.focus()
  value.open.value = true
  await flushPromises()
  await expect.poll(() => document.activeElement?.closest('[role="dialog"]') !== null).toBe(true)
}

async function closeDrawer(value: ReturnType<typeof fixture>) {
  value.open.value = false
  await expect.poll(value.closed).toBe(1)
  await flushPromises()
  await new Promise(resolve => requestAnimationFrame(resolve))
}

describe('focus return after the real Element Plus drawer lifecycle', () => {
  it('returns to the keyboard opener after releasing the actual focus trap', async () => {
    const value = fixture()
    await openDrawer(value, true)
    await closeDrawer(value)
    expect(document.activeElement).toBe(value.wrapper.find('.opener').element)
  })

  it('returns a mouse-opened surface to the page region when no opener exists', async () => {
    const value = fixture()
    await openDrawer(value, false)
    await closeDrawer(value)
    expect(document.activeElement).toBe(value.wrapper.element)
  })

  it('uses the page region when the original opener has been removed', async () => {
    const value = fixture()
    await openDrawer(value, true)
    value.wrapper.find('.opener').element.remove()
    await closeDrawer(value)
    expect(document.activeElement).toBe(value.wrapper.element)
  })

  it('captures an opener inside another dialog on its first opening', async () => {
    const value = fixture()
    const container = document.createElement('div')
    container.setAttribute('role', 'dialog')
    const opener = value.wrapper.find('.opener').element
    opener.replaceWith(container)
    container.append(opener)
    await openDrawer(value, true)
    await closeDrawer(value)
    expect(document.activeElement).toBe(opener)
  })

  it('falls back when the opener becomes disabled while the drawer is open', async () => {
    const value = fixture()
    await openDrawer(value, true)
    const opener = value.wrapper.find('.opener').element as HTMLButtonElement
    opener.disabled = true
    await closeDrawer(value)
    expect(document.activeElement).toBe(value.wrapper.element)
  })

  it('keeps a new focus target selected after the trap releases', async () => {
    const value = fixture()
    await openDrawer(value, true)
    value.open.value = false
    await nextTick()
    const target = value.wrapper.find('.next-target').element as HTMLElement
    target.focus()
    await expect.poll(value.closed).toBe(1)
    await flushPromises()
    await new Promise(resolve => requestAnimationFrame(resolve))
    expect(document.activeElement).toBe(target)
  })

  it('does not focus the old opener during a quick close and reopen', async () => {
    const value = fixture()
    await openDrawer(value, true)
    value.open.value = false
    await nextTick()
    value.open.value = true
    await flushPromises()
    expect(document.activeElement?.closest('[role="dialog"]')).not.toBeNull()
    await new Promise(resolve => setTimeout(resolve, 80))
    expect(document.activeElement?.closest('[role="dialog"]')).not.toBeNull()
    expect(value.closed()).toBe(0)
    await closeDrawer(value)
    expect(document.activeElement).toBe(value.wrapper.find('.opener').element)
  })

  it('does not restore old-page focus after a queued close and navigation unmount', async () => {
    const value = fixture()
    await openDrawer(value, true)
    value.open.value = false
    await nextTick()
    value.present.value = false
    await nextTick()
    const target = value.wrapper.find('.next-target').element as HTMLElement
    target.focus()
    await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 80))
    expect(document.activeElement).toBe(target)
  })
})
