import { onBeforeUnmount, watch, type Ref } from 'vue'

function isHidden(element: HTMLElement): boolean {
  for (let current: HTMLElement | null = element; current; current = current.parentElement) {
    const style = getComputedStyle(current)
    if (current.hidden || style.display === 'none' || style.visibility === 'hidden') return true
  }
  return false
}

function canFocus(element: HTMLElement | null | undefined): element is HTMLElement {
  return Boolean(element?.isConnected && !element.matches(':disabled') && !element.closest('[inert]') && !isHidden(element))
}

/**
 * Connect the returned callback to the surface's `closed` event: a model-value
 * change occurs before Element Plus releases its focus trap and transition.
 * Only recover lost focus, preserving a new focus target chosen by the user or
 * another surface. Mouse-opened rows without a focusable opener use the main
 * region, and removed openers use that same fallback.
 */
export function useFocusReturn(open: Ref<boolean>, fallback?: () => HTMLElement | null): () => void {
  let opener: HTMLElement | null = null
  let generation = 0
  let closingSurface: HTMLElement | null = null
  let disposed = false
  let pendingFrame: number | undefined
  watch(open, (visible, previouslyVisible) => {
    if (visible) {
      generation += 1
      if (pendingFrame !== undefined) cancelAnimationFrame(pendingFrame)
      pendingFrame = undefined
      const active = document.activeElement
      // Reopening during a leave transition must retain the original opener,
      // not the old drawer's still-focused control.
      if (!closingSurface?.contains(active)) {
        opener = active instanceof HTMLElement && active !== document.body ? active : null
      }
      closingSurface = null
    } else {
      const active = document.activeElement
      closingSurface = previouslyVisible && active instanceof HTMLElement
        ? active.closest<HTMLElement>('[role="dialog"]') : null
    }
  }, { flush: 'sync', immediate: true })
  onBeforeUnmount(() => {
    disposed = true
    generation += 1
    opener = null
    if (pendingFrame !== undefined) cancelAnimationFrame(pendingFrame)
  })

  return () => {
    const closedGeneration = generation
    if (pendingFrame !== undefined) cancelAnimationFrame(pendingFrame)
    // `closed` precedes update:modelValue(false). A frame after that lifecycle
    // boundary lets the parent update as well as the released trap settle.
    pendingFrame = requestAnimationFrame(() => {
      pendingFrame = undefined
      if (disposed || open.value || generation !== closedGeneration) return
      const active = document.activeElement
      if (active === document.body || active === null || active instanceof HTMLElement && !canFocus(active)) {
        let target = canFocus(opener) ? opener : fallback?.()
        if (!canFocus(target)) target = document.getElementById('main-content')
        if (canFocus(target)) target.focus({ preventScroll: true })
      }
      opener = null
      closingSurface = null
    })
  }
}
