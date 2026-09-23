import { watch, type Ref } from 'vue'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { translate } from '../i18n'
import { useUnsavedChanges } from './useUnsavedChanges'

export function useFormDialog(open: Ref<boolean>, value: () => unknown, busy: () => boolean = () => false) {
  const changes = useUnsavedChanges(value, () => open.value, () => open.value && busy())
  watch(open, visible => { if (visible) changes.markSaved() }, { flush: 'sync' })
  async function beforeClose(done: () => void) {
    // Closing while a write is in flight would drop the result the caller is
    // still awaiting, so the dialog stays open; say so instead of looking dead.
    if (busy()) { ElMessage.info(translate('common.busySaving')); return }
    if (await changes.canLeave()) done()
  }
  async function cancel() { await beforeClose(() => { open.value = false }) }
  return { beforeClose, cancel, markSaved: changes.markSaved, dirty: changes.dirty, canLeave: changes.canLeave }
}
