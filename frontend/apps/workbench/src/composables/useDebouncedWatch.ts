import { onScopeDispose, watch, type WatchOptions } from 'vue'

/**
 * Debounce user-driven filters while keeping the normal Vue watch lifecycle.
 * The pending timer is disposed with the component, so a page left while a
 * search is being typed cannot trigger a late request after unmount.
 */
export function useDebouncedWatch(
  source: Parameters<typeof watch>[0],
  callback: (...args: any[]) => unknown,
  delay = 300,
  options: WatchOptions = {},
): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined
  const stop = watch(source, (...args: any[]) => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = undefined
      void callback(...args)
    }, delay)
  }, options)
  const dispose = () => {
    if (timer) clearTimeout(timer)
    timer = undefined
    stop()
  }
  onScopeDispose(dispose)
  return dispose
}
