import { onScopeDispose } from 'vue'

/** Cancel the previous batch and expose a guard for late responses. */
export function useLatestRequest() {
  let generation = 0
  let controller: AbortController | null = null

  function start(): { signal: AbortSignal; isCurrent: () => boolean } {
    controller?.abort()
    controller = new AbortController()
    const requestGeneration = ++generation
    return {
      signal: controller.signal,
      isCurrent: () => requestGeneration === generation,
    }
  }

  function cancel(): void {
    generation += 1
    controller?.abort()
    controller = null
  }

  onScopeDispose(cancel)
  return { start, cancel }
}
