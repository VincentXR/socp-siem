import { ref } from 'vue'

/**
 * Tracks one async request and ignores stale results when a newer request wins.
 */
export function useRequest<T>(initial: T | null = null) {
  const data = ref<T | null>(initial)
  const error = ref<Error | null>(null)
  const loading = ref(false)
  let requestId = 0
  let activeRequest = 0
  let activeController: AbortController | null = null

  async function execute(request: (signal: AbortSignal) => Promise<T>): Promise<T | undefined> {
    const id = ++requestId
    activeRequest = id
    activeController?.abort()
    const controller = new AbortController()
    activeController = controller
    loading.value = true
    error.value = null

    try {
      const result = await request(controller.signal)
      if (id !== activeRequest) return undefined
      data.value = result
      return result
    } catch (cause) {
      if (id === activeRequest) {
        error.value = cause instanceof Error ? cause : new Error(String(cause))
      }
      return undefined
    } finally {
      if (id === activeRequest) {
        loading.value = false
        activeController = null
      }
    }
  }

  function cancel(): void {
    activeRequest = ++requestId
    activeController?.abort()
    activeController = null
    loading.value = false
  }

  function reset(): void {
    cancel()
    data.value = initial
    error.value = null
  }

  return { data, error, loading, execute, cancel, reset }
}
