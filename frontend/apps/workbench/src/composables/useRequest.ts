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

  async function execute(request: () => Promise<T>): Promise<T | undefined> {
    const id = ++requestId
    activeRequest = id
    loading.value = true
    error.value = null

    try {
      const result = await request()
      if (id !== activeRequest) return undefined
      data.value = result
      return result
    } catch (cause) {
      if (id === activeRequest) {
        error.value = cause instanceof Error ? cause : new Error(String(cause))
      }
      return undefined
    } finally {
      if (id === activeRequest) loading.value = false
    }
  }

  function cancel(): void {
    activeRequest = ++requestId
    loading.value = false
  }

  function reset(): void {
    cancel()
    data.value = initial
    error.value = null
  }

  return { data, error, loading, execute, cancel, reset }
}
