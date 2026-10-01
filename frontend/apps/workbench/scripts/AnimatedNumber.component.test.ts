import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AnimatedNumber from '../src/AnimatedNumber.vue'

afterEach(() => vi.unstubAllGlobals())

describe('number motion preference', () => {
  function motion(matches: boolean) {
    let listener: (() => void) | undefined
    const preference = {
      matches,
      addEventListener: vi.fn((_type: string, callback: () => void) => { listener = callback }),
      removeEventListener: vi.fn(),
    }
    vi.stubGlobal('matchMedia', vi.fn(() => preference))
    const requestFrame = vi.fn(() => 42)
    const cancelFrame = vi.fn()
    vi.stubGlobal('requestAnimationFrame', requestFrame)
    vi.stubGlobal('cancelAnimationFrame', cancelFrame)
    return { preference, requestFrame, cancelFrame, change: () => listener?.() }
  }

  it('renders the current value immediately when reduced motion is enabled', async () => {
    const state = motion(true)
    const wrapper = mount(AnimatedNumber, { props: { value: 120 } })
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toBe('120')
    await wrapper.setProps({ value: 240 })
    expect(wrapper.text()).toBe('240')
    expect(state.requestFrame).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('stops an active animation when the system preference changes and releases it on unmount', async () => {
    const state = motion(false)
    const wrapper = mount(AnimatedNumber, { props: { value: 120 } })
    expect(state.requestFrame).toHaveBeenCalledOnce()
    state.preference.matches = true
    state.change()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toBe('120')
    expect(state.cancelFrame).toHaveBeenCalledWith(42)
    wrapper.unmount()
    expect(state.preference.removeEventListener).toHaveBeenCalledWith('change', expect.any(Function))
  })
})
