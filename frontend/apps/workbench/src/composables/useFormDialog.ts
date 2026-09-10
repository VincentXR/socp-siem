import { watch, type Ref } from 'vue'
import { useUnsavedChanges } from './useUnsavedChanges'

export function useFormDialog(open: Ref<boolean>, value: () => unknown, busy: () => boolean = () => false) {
  const changes = useUnsavedChanges(value, () => open.value)
  watch(open, visible => { if (visible) changes.markSaved() }, { flush: 'sync' })
  async function beforeClose(done: () => void) {
    if (!busy() && await changes.canLeave()) done()
  }
  async function cancel() { await beforeClose(() => { open.value = false }) }
  return { beforeClose, cancel, markSaved: changes.markSaved }
}
