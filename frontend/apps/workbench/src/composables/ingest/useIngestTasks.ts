import { computed, onUnmounted, ref, watch, type Ref } from 'vue'
import { ingestSummary, listIngestTasksPage, previewSource, startIngestTask, stopIngestTask, type IngestTask, type IngestSummary } from '../../api'
import { useI18n } from '../useI18n'
import type { IngestWorkspace } from './useIngestWorkspace'

export function useIngestTasks(workspace: IngestWorkspace, activeTab: Ref<string>) {
  const { canWrite, actionError, loadErrors, loadStates, loadList, confirmWrite } = workspace
  const { t } = useI18n()
  const tasksLoading = computed(() => Boolean(loadStates.value.tasks) || Boolean(loadStates.value.summary))
  const tasks = ref<IngestTask[]>([])
  const taskPage = ref(1)
  const taskSize = ref(20)
  const taskTotal = ref(0)
  const taskSummary = ref<IngestSummary | null>(null)
  const summaryStale = computed(() => !!loadErrors.value.summary && !!taskSummary.value)
  const taskBusy = ref<Record<string, boolean>>({})
  const testDialog = ref(false)
  const testTarget = ref<IngestTask | null>(null)
  const testSample = ref('')
  type ParsePreviewAttempt = { ruleId?: string; rule?: string; format?: string; matched: boolean; error?: string }
  type ParsePreviewResult = { ok: boolean; matched: boolean; sample: string; rule?: string; format?: string; fields: Record<string, string>; attempts?: ParsePreviewAttempt[]; error?: string }
  const testResult = ref<ParsePreviewResult | null>(null)
  const testLoading = ref(false)
  let previewGeneration = 0
  let previewController: AbortController | null = null
  type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'
  const HEALTH_KEYS: Record<string, string> = {
    HEALTHY: 'common.healthy', DEGRADED: 'common.degraded', STALE: 'common.stale',
    IDLE: 'common.idle', ERROR: 'common.error', DISABLED: 'common.disabled',
  }
  function healthMeta(health: string) {
    const type: TagType = health === 'HEALTHY' ? 'success' : health === 'ERROR' ? 'danger' : health === 'DEGRADED' || health === 'STALE' ? 'warning' : 'info'
    return { text: HEALTH_KEYS[health] ? t(HEALTH_KEYS[health]) : health, type }
  }
  const loadTaskPage = () => loadList('tasks', signal => listIngestTasksPage(taskPage.value, taskSize.value, { signal }), value => {
    tasks.value = value.items
    taskTotal.value = value.total
    const lastPage = Math.max(1, Math.ceil(value.total / taskSize.value))
    if (taskPage.value > lastPage) taskPage.value = lastPage
  })
  const loadTasks = () => Promise.all([
    loadTaskPage(),
    loadList('summary', signal => ingestSummary({ signal }), value => { taskSummary.value = value }),
  ])
  watch([taskPage, taskSize], () => { if (activeTab.value === 'tasks') void loadTaskPage() })
  async function toggleTask(task: IngestTask) {
    const { id, name, enabled } = task
    if (taskBusy.value[id]) return
    if (!await confirmWrite(enabled ? 'ingest.stopTaskConfirm' : 'ingest.startTaskConfirm', { name })) return
    if (taskBusy.value[id]) return
    const current = tasks.value.find(item => item.id === id)
    if (!current || current.enabled !== enabled) return
    taskBusy.value = { ...taskBusy.value, [id]: true }
    try { enabled ? await stopIngestTask(id) : await startIngestTask(id); await loadTasks() }
    catch (failure) { if (!workspace.disposed) actionError.value = failure instanceof Error ? failure.message : String(failure) }
    finally { taskBusy.value = { ...taskBusy.value, [id]: false } }
  }
  function openTest(task: IngestTask) {
    if (!canWrite.value) return
    cancelPreview()
    actionError.value = ''
    testTarget.value = task; testSample.value = ''; testResult.value = null; testDialog.value = true
  }
  function cancelPreview() {
    previewGeneration++
    previewController?.abort()
    previewController = null
    testLoading.value = false
  }
  function toggleTaskRow(row: unknown) { toggleTask(row as IngestTask) }
  function openTestRow(row: unknown) { openTest(row as IngestTask) }
  function defaultPreviewSample(task: IngestTask): string {
    return JSON.stringify({
      collector: task.collector,
      host: 'preview-host',
      source: 'auth',
      severity: 'HIGH',
      message: 'Failed password for invalid user admin',
      user: 'admin',
    }, null, 2)
  }
  watch(testSample, () => { cancelPreview(); testResult.value = null }, { flush: 'sync' })
  async function runTest() {
    if (!canWrite.value || !testTarget.value || !testDialog.value || testLoading.value || !testSample.value.trim()) return
    const target = testTarget.value
    const generation = ++previewGeneration
    const controller = new AbortController()
    previewController = controller
    testLoading.value = true
    testResult.value = null
    const sample = testSample.value
    try {
      const result = await previewSource(target.id, sample, { signal: controller.signal })
      if (!workspace.disposed && testDialog.value && generation === previewGeneration && !controller.signal.aborted) testResult.value = result
    } catch (error) {
      if (!workspace.disposed && testDialog.value && generation === previewGeneration && !controller.signal.aborted) testResult.value = { ok: false, matched: false, sample, fields: {}, error: String(error) }
    } finally {
      if (generation === previewGeneration) { previewController = null; testLoading.value = false }
    }
  }
  watch(testDialog, open => { if (!open) cancelPreview() }, { flush: 'sync' })
  onUnmounted(cancelPreview)

  return {
    tasks, taskPage, taskSize, taskTotal, taskSummary, tasksLoading, summaryStale, taskBusy, loadTasks, toggleTask, toggleTaskRow, healthMeta,
    testDialog, testTarget, testSample, testResult, testLoading, openTest, openTestRow, defaultPreviewSample,
    runTest,
  }
}
