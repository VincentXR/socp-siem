import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import ElMessageBox from 'element-plus/es/components/message-box/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import 'element-plus/es/components/message-box/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import { useI18n } from './useI18n'

export function useUnsavedChanges(value: () => unknown, active: () => boolean = () => true, pending: () => boolean = () => false) {
  const { t } = useI18n()
  const baseline = ref(JSON.stringify(value()))
  const dirty = computed(() => active() && JSON.stringify(value()) !== baseline.value)
  function markSaved() { baseline.value = JSON.stringify(value()) }
  async function canLeave() {
    if (pending()) { ElMessage.info(t('common.busySaving')); return false }
    if (!dirty.value) return true
    try {
      await ElMessageBox.confirm(t('forms.unsaved'), t('forms.unsavedTitle'), {
        confirmButtonText: t('forms.discard'), cancelButtonText: t('forms.keepEditing'), type: 'warning',
      })
      return true
    } catch { return false }
  }
  function beforeUnload(event: BeforeUnloadEvent) {
    if (pending() || dirty.value) { event.preventDefault(); event.returnValue = '' }
  }
  onBeforeRouteLeave(canLeave)
  onBeforeRouteUpdate((to, from) => !pending() && to.path === from.path || canLeave())
  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
  return { dirty, markSaved, canLeave }
}
