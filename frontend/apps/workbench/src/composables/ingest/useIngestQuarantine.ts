import { computed, ref, watch, type Ref } from 'vue'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { listIngestParseFailures, replayIngestParseFailure, type IngestParseFailure } from '../../api'
import { useI18n } from '../useI18n'
import { useIngestCopy } from '../useIngestCopy'
import { useConfirm } from '../useConfirm'
import type { IngestWorkspace } from './useIngestWorkspace'

export function useIngestQuarantine(workspace: IngestWorkspace, activeTab: Ref<string>) {
  const { canWrite, confirming, loadList, loadStates, loadErrors } = workspace
  const { t } = useI18n()
  const copy = useIngestCopy()
  const { confirmDanger } = useConfirm()
  const parseFailuresLoading = computed(() => Boolean(loadStates.value.quarantine))
  const parseFailures = ref<IngestParseFailure[]>([])
  const parseFailurePage = ref(1)
  const parseFailureSize = ref(50)
  const parseFailureTotal = ref(0)
  const replayingFailure = ref('')
  const loadParseFailures = () => loadList('quarantine', signal =>
    listIngestParseFailures(parseFailurePage.value, parseFailureSize.value, { signal }), value => {
    parseFailures.value = value.items
    parseFailureTotal.value = value.total
  })
  async function replayParseFailure(row: IngestParseFailure) {
    if (!canWrite.value || replayingFailure.value || confirming.value || workspace.disposed) return
    const id = row.id
    confirming.value = true
    let confirmed = false
    try { confirmed = await confirmDanger(copy.value.replayWarning) }
    finally { confirming.value = false }
    if (!confirmed || !canWrite.value || workspace.disposed || replayingFailure.value) return
    replayingFailure.value = id
    try {
      const result = await replayIngestParseFailure(id)
      if (workspace.disposed) return
      const index = parseFailures.value.findIndex(item => item.id === id)
      if (index >= 0) parseFailures.value.splice(index, 1, result)
      if (result.replayStatus === 'REPLAYED') {
        ElMessage.success(t('ingest.replaySucceeded', { eventId: result.replayedEventId || t('time.notAvailable') }))
      } else {
        ElMessage.warning(t('ingest.replayFailed', { reason: result.lastError || result.failureReason }))
      }
      await loadParseFailures()
    } catch (failure) {
      if (!workspace.disposed) loadErrors.value = { ...loadErrors.value, quarantine: String(failure) }
    } finally {
      replayingFailure.value = ''
    }
  }
  watch([parseFailurePage, parseFailureSize], () => {
    if (activeTab.value === 'quarantine') void loadParseFailures()
  })

  return {
    parseFailures, parseFailuresLoading, parseFailurePage, parseFailureSize, parseFailureTotal,
    replayingFailure, loadParseFailures, replayParseFailure,
  }
}
