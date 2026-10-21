import { computed, onUnmounted, ref, type Ref } from 'vue'
import { useWriteAccess } from '../useWriteAccess'
import { useConfirm } from '../useConfirm'
import { useI18n } from '../useI18n'

/** Only the ingest workspaces participate in these read and write lifetimes. */
export type IngestLoadKey = 'sources' | 'outputs' | 'rules' | 'ruleOptions' | 'selectedRules' | 'tasks' | 'summary' | 'categories' | 'quarantine'

export function useIngestWorkspace() {
  const canWrite = useWriteAccess()
  const { t } = useI18n()
  const { confirmDanger } = useConfirm()
  const actionBusy = ref(false)
  const actionError = ref('')
  const confirming = ref(false)
  const loadErrors = ref<Partial<Record<IngestLoadKey, string>>>({})
  const loadStates = ref<Partial<Record<IngestLoadKey, boolean>>>({})
  const loadError = computed(() => [...new Set(Object.values(loadErrors.value).filter(Boolean))].join(' · '))
  const readControllers = new Map<IngestLoadKey, AbortController>()
  let disposed = false

  function abortRead(key: IngestLoadKey) { readControllers.get(key)?.abort() }
  async function loadList<T>(key: IngestLoadKey, request: (signal: AbortSignal) => Promise<T>, publish: (value: T) => void): Promise<void> {
    if (disposed) return
    abortRead(key)
    const controller = new AbortController()
    readControllers.set(key, controller)
    loadStates.value = { ...loadStates.value, [key]: true }
    const ownsResult = () => !disposed && readControllers.get(key) === controller && !controller.signal.aborted
    try {
      const value = await request(controller.signal)
      if (!ownsResult()) return
      publish(value)
      loadErrors.value = { ...loadErrors.value, [key]: '' }
    } catch (failure) {
      if (ownsResult()) loadErrors.value = { ...loadErrors.value, [key]: String(failure) }
    } finally {
      if (readControllers.get(key) === controller) {
        readControllers.delete(key)
        loadStates.value = { ...loadStates.value, [key]: false }
      }
    }
  }

  /** Serialize page writes, but leave each failure with its original surface. */
  async function run(action: () => Promise<void>, error: Ref<string> = actionError): Promise<boolean> {
    if (actionBusy.value || disposed) return false
    actionBusy.value = true
    error.value = ''
    try { await action(); return true }
    catch (failure) {
      if (!disposed) error.value = failure instanceof Error ? failure.message : String(failure)
      return false
    } finally { actionBusy.value = false }
  }

  async function confirmWrite(key: string, params?: Record<string, string>): Promise<boolean> {
    if (!canWrite.value || actionBusy.value || confirming.value || disposed) return false
    confirming.value = true
    try {
      const confirmed = await confirmDanger(t(key, params))
      return confirmed && canWrite.value && !actionBusy.value && !disposed
    } finally { confirming.value = false }
  }

  onUnmounted(() => {
    disposed = true
    for (const controller of readControllers.values()) controller.abort()
    readControllers.clear()
  })
  return { canWrite, actionBusy, actionError, confirming, loadErrors, loadStates, loadError,
    loadList, abortRead, run, confirmWrite, get disposed() { return disposed } }
}
export type IngestWorkspace = ReturnType<typeof useIngestWorkspace>
