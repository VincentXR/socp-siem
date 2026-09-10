import { ref } from 'vue'

/** Keep errors beside the operation and never clear a form before it succeeds. */
export function useMutation() {
  const busy = ref(false)
  const error = ref('')
  async function run(action: () => Promise<void>): Promise<boolean> {
    if (busy.value) return false
    busy.value = true
    error.value = ''
    try { await action(); return true }
    catch (failure) { error.value = failure instanceof Error ? failure.message : String(failure); return false }
    finally { busy.value = false }
  }
  return { busy, error, run }
}
