import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { describe, expect, it } from 'vitest'
import RowActivate from '../src/components/RowActivate.vue'

describe('RowActivate keyboard-activatable cell', () => {
  it('exposes a focusable button role with an accessible name', () => {
    const wrapper = mount(RowActivate, { props: { ariaLabel: 'host-a' }, slots: { default: 'host-a' } })
    const span = wrapper.find('.row-activate')
    expect(span.attributes('role')).toBe('button')
    expect(span.attributes('tabindex')).toBe('0')
    expect(span.attributes('aria-label')).toBe('host-a')
    wrapper.unmount()
  })

  it('activates on Enter and on Space (Space default prevented to stop page scroll)', async () => {
    const wrapper = mount(RowActivate, { slots: { default: 'row' } })
    const el = wrapper.find('.row-activate').element
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('activate')).toHaveLength(1)
    const spaceEvent = new KeyboardEvent('keydown', { key: ' ', code: 'Space', bubbles: true, cancelable: true })
    el.dispatchEvent(spaceEvent)
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('activate')).toHaveLength(2)
    expect(spaceEvent.defaultPrevented).toBe(true)
    wrapper.unmount()
  })

  it('activates on click without bubbling to the el-table @row-click ancestor', async () => {
    let ancestorClicks = 0
    let activateCalls = 0
    const Host = defineComponent({
      setup() {
        return () => h('div', { onClick: () => { ancestorClicks += 1 } }, [
          h(RowActivate, { onActivate: () => { activateCalls += 1 } }, () => 'cell'),
        ])
      },
    })
    const wrapper = mount(Host)
    await wrapper.find('.row-activate').trigger('click')
    expect(activateCalls).toBe(1)
    expect(ancestorClicks).toBe(0)
    wrapper.unmount()
  })
})
