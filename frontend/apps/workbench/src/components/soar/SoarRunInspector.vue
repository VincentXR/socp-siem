<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { RunOpenRequest } from './editor/runHighlight'
import { useConfirm } from '../../composables/useConfirm'
import { initialRunInputs } from './editor/runInputs'
import { coalescedRefresh } from '../../lib/coalesced-refresh'
import { declaredEffects } from './editor/executionReview'
import SchemaInputForm from '../SchemaInputForm.vue'
import { validateSchemaInput } from '../../utils/schemaValidation'
import { useFormDialog } from '../../composables/useFormDialog'
import { useI18n } from '../../composables/useI18n'
import RowActivate from '../RowActivate.vue'
import SoarCatalogPager from './SoarCatalogPager.vue'
import { tOr } from '../../utils/i18nLabel'
import {
  cancelWorkflowRun,
  getArtifactContent,
  getRun,
  listPlaybooks,
  listVersions,
  listArtifacts,
  listEvents,
  listNodeAttempts,
  listNodes,
  listRuns,
  queueRun,
  rerunRun,
  resolveUnknown as resolveUnknownApi,
  retryRun,
  type SoarArtifact,
  type SoarAttempt,
  type SoarEvent,
  type SoarNodeRun,
  type SoarVersion,
  type SoarPlaybook,
  type SoarRun,
} from '../../api'

const props = withDefaults(defineProps<{
  initialRunId?: string
  contextAlarmId?: string
  contextCaseId?: string
  canWrite?: boolean
  canExecute?: boolean
  canOperate?: boolean
  /** False while the runs pane is hidden; pauses projection polling and the live stream. */
  active?: boolean
}>(), { initialRunId: '', contextAlarmId: '', contextCaseId: '', canWrite: true, canExecute: true, canOperate: true, active: true })
const emit = defineEmits<{ 'open-in-editor': [payload: RunOpenRequest]; 'select-run': [runId: string] }>()

const { t } = useI18n()
const { confirmDanger, promptInput } = useConfirm()

const catalogPageSize = 25
const runs = ref<SoarRun[]>([])
const runsPage = ref(0)
const runKeyword = ref('')
const runStatusFilter = ref('')
const runsTotal = ref(0)
const runsTotalPages = ref(0)
const selectedRunId = ref(props.initialRunId)
const run = ref<SoarRun | null>(null)
const nodes = ref<SoarNodeRun[]>([])
const events = ref<SoarEvent[]>([])
const artifacts = ref<SoarArtifact[]>([])
const attempts = ref<SoarAttempt[]>([])
const selectedNodeRunId = ref('')
const loading = ref(false)
const runsLoading = ref(false)
const runsError = ref('')
const errorMessage = ref('')
const streamState = ref<'closed' | 'live' | 'polling'>('closed')
const queueDialogVisible = ref(false)
const queueLoading = ref(false)
const queueError = ref('')
const queueMessage = ref('')
const controlBusy = ref<'cancel' | 'retry' | 'rerun' | 'resolve' | ''>('')
const queuePlaybooks = ref<SoarPlaybook[]>([])
const queuePlaybooksPage = ref(0)
const queuePlaybooksTotal = ref(0)
const queuePlaybooksTotalPages = ref(0)
const queueCatalogLoading = ref(false)
const queueVersionsLoading = ref(false)
const queueCatalogError = ref('')
const queuePlaybook = ref<SoarPlaybook | null>(null)
const publishedVersions = ref<Array<{ version: SoarVersion; playbook: SoarPlaybook }>>([])
const queueForm = ref({
  playbookVersionId: '',
  requestId: '',
  subject: '{}',
  inputs: '{}',
})
const queueInputs = ref<Record<string, unknown>>({})
const queueInputsValid = ref(true)
const queueInputSchema = computed(() => (publishedVersions.value.find(item => item.version.id === queueForm.value.playbookVersionId)?.version.definition as Record<string, unknown> | undefined)?.inputSchema)
watch(() => queueForm.value.playbookVersionId, () => {
  queueInputs.value = initialRunInputs(queueInputSchema.value, { alarmId: props.contextAlarmId, caseId: props.contextCaseId })
  queueForm.value.inputs = JSON.stringify(queueInputs.value, null, 2)
}, { flush: 'sync' })
watch(queueInputs, value => { queueForm.value.inputs = JSON.stringify(value, null, 2) }, { deep: true, flush: 'sync' })
const queueGuard = useFormDialog(queueDialogVisible, () => queueForm.value, () => queueLoading.value)
let pollTimer: ReturnType<typeof setInterval> | undefined
let stream: EventSource | undefined
let disposed = false
let runsController: AbortController | null = null
let runController: AbortController | null = null
let projectionController: AbortController | null = null
let projectionPending = false
let attemptController: AbortController | null = null
let queueCatalogController: AbortController | null = null
let queueVersionsController: AbortController | null = null

// Keep the selected identity visible while browsing another page. Only the
// current page and one selected item are retained, never the whole catalog.
const queuePlaybookOptions = computed(() => queuePlaybook.value && !queuePlaybooks.value.some(item => item.id === queuePlaybook.value!.id)
  ? [queuePlaybook.value, ...queuePlaybooks.value] : queuePlaybooks.value)
const selectedRunOffPage = computed(() => selectedRunId.value && !runs.value.some(item => item.runId === selectedRunId.value))

const selectedNode = computed(() => nodes.value.find(node => node.id === selectedNodeRunId.value))
const lastSequence = ref(0)
const eventPageSize = 200
const historyPage = ref<number | null>(null)
const historyEvents = ref<SoarEvent[]>([])
const eventTotalPages = ref(0)
const durableEventCount = ref(0)
const eventError = ref('')
const historyLoading = ref(false)
const visibleEvents = computed(() => historyPage.value === null ? events.value : historyEvents.value)
let historyController: AbortController | null = null
function mergeEvents(incoming: SoarEvent[]): void {
  const merged = new Map(events.value.map(item => [item.sequence, item]))
  for (const item of incoming) merged.set(item.sequence, item)
  events.value = [...merged.values()].sort((a, b) => a.sequence - b.sequence).slice(-eventPageSize)
}
async function loadEventHistory(page: number | null): Promise<void> {
  historyController?.abort()
  if (page === null) { historyPage.value = null; historyLoading.value = false; return }
  const controller = new AbortController()
  historyController = controller
  const runId = selectedRunId.value
  historyLoading.value = true
  try {
    const result = await listEvents(runId, 0, page, eventPageSize, { signal: controller.signal })
    if (disposed || controller.signal.aborted || runId !== selectedRunId.value) return
    historyEvents.value = result.items
    historyPage.value = page
  } catch (failure) { if (!controller.signal.aborted) eventError.value = failureText(failure) }
  finally { if (historyController === controller) historyLoading.value = false }
}
async function catchUpEvents(runId: string, generation: number, signal: AbortSignal): Promise<void> {
  // Bounded per refresh; the cursor is advanced only through durable REST pages.
  // SSE arrivals must not skip unseen events when the stream has gaps.
  for (let page = 0; page < 3; page++) {
    const result = await listEvents(runId, lastSequence.value, 0, eventPageSize, { signal })
    if (disposed || signal.aborted || generation !== loadGeneration || runId !== selectedRunId.value) return
    durableEventCount.value += result.items.filter(item => item.sequence > lastSequence.value).length
    eventTotalPages.value = Math.ceil(durableEventCount.value / eventPageSize)
    mergeEvents(result.items)
    const next = Math.max(lastSequence.value, ...result.items.map(item => item.sequence))
    const advanced = next > lastSequence.value
    lastSequence.value = next
    eventError.value = ''
    if (!advanced || result.items.length < eventPageSize) break
  }
}
const unknownNodes = computed(() => nodes.value.filter(node => ['ACTION_UNKNOWN', 'UNKNOWN'].includes(node.status)))

function failureText(failure: unknown): string {
  return failure instanceof Error ? failure.message : t('soar.requestFailed')
}

function newRequestId(): string {
  const suffix = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2)
  return `workbench-${suffix}`
}

function queueReadsActive(): boolean {
  return !disposed && props.active && props.canExecute && queueDialogVisible.value
}

function cancelQueueReads(): void {
  queueCatalogController?.abort()
  queueVersionsController?.abort()
  queueCatalogController = null
  queueVersionsController = null
  queueCatalogLoading.value = false
  queueVersionsLoading.value = false
}

async function loadQueuePlaybooks(): Promise<void> {
  if (!queueReadsActive()) return
  queueCatalogController?.abort()
  const controller = new AbortController()
  queueCatalogController = controller
  queueCatalogLoading.value = true
  queueCatalogError.value = ''
  try {
    const result = await listPlaybooks(queuePlaybooksPage.value, catalogPageSize, { signal: controller.signal })
    if (!queueReadsActive() || controller.signal.aborted || queueCatalogController !== controller) return
    queuePlaybooksTotal.value = result.total
    queuePlaybooksTotalPages.value = result.totalPages ?? Math.ceil(result.total / catalogPageSize)
    const lastPage = Math.max(0, queuePlaybooksTotalPages.value - 1)
    if (queuePlaybooksPage.value > lastPage) {
      queuePlaybooksPage.value = lastPage
      await loadQueuePlaybooks()
      return
    }
    queuePlaybooks.value = result.items
    if (!queuePlaybook.value) {
      const first = result.items.find(item => item.latestPublishedVersion != null) ?? result.items[0]
      if (first) void selectQueuePlaybook(first.id, true)
    }
  } catch (failure) {
    if (!queueReadsActive() || controller.signal.aborted || queueCatalogController !== controller) return
    queueCatalogError.value = failureText(failure)
  } finally {
    if (queueCatalogController === controller) { queueCatalogController = null; queueCatalogLoading.value = false }
  }
}

function changeQueuePlaybooksPage(page: number): void {
  if (!queueReadsActive() || queueLoading.value || page < 0 || page >= queuePlaybooksTotalPages.value) return
  queuePlaybooksPage.value = page
  queuePlaybooks.value = []
  void loadQueuePlaybooks()
}

async function selectQueuePlaybook(id: string, automatic = false): Promise<void> {
  if (!queueReadsActive() || queueLoading.value) return
  const selected = queuePlaybookOptions.value.find(item => item.id === id)
  if (!selected) return
  const pristine = automatic && !queueGuard.dirty.value
  queueVersionsController?.abort()
  const controller = new AbortController()
  queueVersionsController = controller
  queuePlaybook.value = selected
  publishedVersions.value = []
  queueForm.value.playbookVersionId = ''
  queueVersionsLoading.value = true
  queueError.value = ''
  try {
    const versions = await listVersions(selected.id, { signal: controller.signal })
    if (!queueReadsActive() || controller.signal.aborted || queueVersionsController !== controller) return
    publishedVersions.value = versions.filter(version => version.status === 'PUBLISHED').map(version => ({ version, playbook: selected }))
    // Automatic defaults should not create a phantom unsaved-change prompt.
    const stillPristine = pristine && !queueGuard.dirty.value
    queueForm.value.playbookVersionId = publishedVersions.value[0]?.version.id ?? ''
    if (stillPristine) queueGuard.markSaved()
    if (!publishedVersions.value.length) queueError.value = t('soar.noPublishedVersionsForPlaybook')
  } catch (failure) {
    if (!queueReadsActive() || controller.signal.aborted || queueVersionsController !== controller) return
    queueError.value = failureText(failure)
  } finally {
    if (queueVersionsController === controller) { queueVersionsController = null; queueVersionsLoading.value = false }
  }
}

function openQueueDialog(): void {
  if (!props.canExecute || !props.active || queueLoading.value) return
  cancelQueueReads()
  queueMessage.value = ''
  queueError.value = ''
  queueCatalogError.value = ''
  queuePlaybooksPage.value = 0
  queuePlaybooksTotal.value = 0
  queuePlaybooksTotalPages.value = 0
  queuePlaybooks.value = []
  queuePlaybook.value = null
  publishedVersions.value = []
  queueForm.value = {
    playbookVersionId: '',
    requestId: newRequestId(),
    subject: JSON.stringify({ ...(props.contextAlarmId ? { type: 'alert', id: props.contextAlarmId } : props.contextCaseId ? { type: 'case', id: props.contextCaseId } : {}), ...(props.contextAlarmId ? { alarmId: props.contextAlarmId } : {}), ...(props.contextCaseId ? { caseId: props.contextCaseId } : {}) }, null, 2),
    inputs: '{}',
  }
  queueDialogVisible.value = true
  queueGuard.markSaved()
  void loadQueuePlaybooks()
}

function parseObject(value: string, label: string): Record<string, unknown> {
  let parsed: unknown
  try { parsed = JSON.parse(value.trim() || '{}') } catch { throw new Error(t('soar.invalidJson', { label })) }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error(t('soar.jsonObjectRequired', { label }))
  return parsed as Record<string, unknown>
}

async function submitQueue(): Promise<void> {
  if (!queueReadsActive() || queueLoading.value || queueVersionsLoading.value) return
  queueError.value = ''
  queueMessage.value = ''
  if (!publishedVersions.value.some(item => item.version.id === queueForm.value.playbookVersionId)) {
    queueError.value = t('soar.choosePublishedVersion')
    return
  }
  queueLoading.value = true
  const context = `${props.contextAlarmId}:${props.contextCaseId}`
  try {
    const subject = parseObject(queueForm.value.subject, 'Subject')
    const inputs = queueInputSchema.value ? JSON.parse(JSON.stringify(queueInputs.value)) as Record<string, unknown> : parseObject(queueForm.value.inputs, 'Inputs')
    const version = publishedVersions.value.find(item => item.version.id === queueForm.value.playbookVersionId)!.version
    const requestId = queueForm.value.requestId.trim() || newRequestId()
    if (queueInputSchema.value && (!queueInputsValid.value || validateSchemaInput(inputs, queueInputSchema.value).length)) throw new Error(t('analystJourney.liveInputsRequired'))
    if (!(await confirmDanger(t('analystJourney.liveExecutionConfirmation', { revision: version.version, target: JSON.stringify(subject), inputs: JSON.stringify(inputs), risk: JSON.stringify(version.riskSummary) ?? '', effects: JSON.stringify(declaredEffects(version.definition)) }))) || !queueReadsActive() || context !== `${props.contextAlarmId}:${props.contextCaseId}` || version.id !== queueForm.value.playbookVersionId) return
    const result = await queueRun({
      requestId,
      playbookVersionId: version.id,
      subject, inputs,
    })
    if (disposed || context !== `${props.contextAlarmId}:${props.contextCaseId}`) return
    queueDialogVisible.value = false
    queueMessage.value = result.duplicate
      ? t('soar.runDuplicate', { runId: result.runId })
      : t('soar.runAccepted', { runId: result.runId })
    selectedRunId.value = result.runId
    await loadRuns()
  } catch (failure) {
    queueError.value = failureText(failure)
  } finally {
    queueLoading.value = false
  }
}

/** True when the selected run points at a version the graph editor can open. */
const canOpenInEditor = computed(() => Boolean(run.value?.playbookId && run.value?.playbookVersion))

/**
 * Projects the run's node projection into an editor request: load the exact
 * immutable version the run executed, then overlay each node-run status.
 */
function openInEditor(): void {
  const selected = run.value
  if (!selected?.playbookId || !selected.playbookVersion) return
  emit('open-in-editor', {
    token: `${selected.runId}:${selected.playbookVersion}:${Date.now()}`,
    runId: selected.runId,
    playbookId: selected.playbookId,
    version: selected.playbookVersion,
    rows: nodes.value.map(node => ({
      nodeId: node.nodeId,
      status: node.status,
      iterationPath: node.iterationPath ?? null,
    })),
  })
}

function json(value: unknown): string {
  if (value === undefined || value === null || value === '') return ''
  if (typeof value === 'string') return value
  try { return JSON.stringify(value) } catch { return String(value) }
}

async function loadRuns(refreshSelected = true) {
  if (disposed || !props.active) return
  runsController?.abort()
  const controller = new AbortController()
  runsController = controller
  runsLoading.value = true
  runsError.value = ''
  try {
    const result = await listRuns(runsPage.value, catalogPageSize, { signal: controller.signal }, { alarmId: props.contextAlarmId || undefined, caseId: props.contextCaseId || undefined, q: runKeyword.value.trim() || undefined, status: runStatusFilter.value || undefined })
    if (disposed || !props.active || controller.signal.aborted || runsController !== controller) return
    runsTotal.value = result.total
    runsTotalPages.value = result.totalPages ?? Math.ceil(result.total / catalogPageSize)
    const lastPage = Math.max(0, runsTotalPages.value - 1)
    if (runsPage.value > lastPage) {
      runsPage.value = lastPage
      await loadRuns(refreshSelected)
      return
    }
    runs.value = result.items
    if (!selectedRunId.value && runs.value[0]) {
      selectedRunId.value = runs.value[0].runId
    } else if (refreshSelected && selectedRunId.value) await refreshRun()
  } catch (failure) {
    if (disposed || !props.active || controller.signal.aborted || runsController !== controller) return
    runsError.value = failure instanceof Error ? failure.message : t('soar.unableLoadRuns')
  } finally {
    if (runsController === controller) { runsController = null; runsLoading.value = false }
  }
}

function changeRunsPage(page: number): void {
  if (disposed || !props.active || controlBusy.value || page < 0 || page >= runsTotalPages.value) return
  runsPage.value = page
  runs.value = []
  void loadRuns(false)
}

// Guards against a late response for a previous run overwriting the current
// one: every run-level load carries the generation it started with and drops
// its results once a newer selection (or reload) has taken over.
let loadGeneration = 0

async function refreshRun() {
  if (disposed || !props.active || !selectedRunId.value) return
  const generation = ++loadGeneration
  runController?.abort()
  projectionController?.abort()
  projectionPending = false
  attemptController?.abort()
  closeStream()
  const controller = new AbortController()
  runController = controller
  const options = { signal: controller.signal }
  const runId = selectedRunId.value
  if (run.value?.runId !== runId) {
    run.value = null
    nodes.value = []
    events.value = []
    lastSequence.value = 0
    historyPage.value = null
    historyEvents.value = []
    eventError.value = ''
    historyController?.abort()
    artifacts.value = []
    attempts.value = []
    selectedNodeRunId.value = ''
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const [runResult, nodeResult, eventResult, artifactResult] = await Promise.all([
      getRun(runId, options),
      listNodes(runId, options),
      listEvents(runId, 0, 0, 200, options),
      listArtifacts(runId, options),
    ])
    if (disposed || !props.active || controller.signal.aborted || generation !== loadGeneration) return
    run.value = runResult
    nodes.value = nodeResult
    durableEventCount.value = eventResult.total ?? eventResult.items.length
    eventTotalPages.value = eventResult.totalPages ?? Math.ceil(durableEventCount.value / eventPageSize)
    const latest = eventTotalPages.value > 1
      ? await listEvents(runId, 0, eventTotalPages.value - 1, eventPageSize, options) : eventResult
    if (disposed || controller.signal.aborted || generation !== loadGeneration) return
    // A partially filled final page still shows the latest 200 receipts, not
    // merely the last remainder. History pages keep their durable offsets.
    const preceding = eventTotalPages.value > 1 && latest.items.length < eventPageSize
      ? (eventTotalPages.value === 2 ? eventResult : await listEvents(runId, 0, eventTotalPages.value - 2, eventPageSize, options)) : null
    if (disposed || controller.signal.aborted || generation !== loadGeneration) return
    events.value = [...(preceding?.items ?? []), ...latest.items].slice(-eventPageSize)
    lastSequence.value = Math.max(0, ...events.value.map(item => item.sequence))
    artifacts.value = artifactResult
    if (!nodes.value.some(node => node.id === selectedNodeRunId.value)) {
      selectedNodeRunId.value = nodes.value[0]?.id ?? ''
    }
    await loadAttempts()
    if (disposed || !props.active || controller.signal.aborted || generation !== loadGeneration) return
    openStream()
  } catch (failure) {
    if (disposed || !props.active || controller.signal.aborted || generation !== loadGeneration) return
    errorMessage.value = failure instanceof Error ? failure.message : t('soar.unableLoadRunDetails')
  } finally {
    if (generation === loadGeneration) loading.value = false
    if (runController === controller) runController = null
  }
}

async function loadAttempts() {
  if (disposed || !props.active) return
  attemptController?.abort()
  const controller = new AbortController()
  attemptController = controller
  const nodeRunId = selectedNodeRunId.value
  if (!nodeRunId) { attempts.value = []; attemptController = null; return }
  try {
    const result = await listNodeAttempts(nodeRunId, 0, 100, { signal: controller.signal })
    if (disposed || !props.active || controller.signal.aborted || nodeRunId !== selectedNodeRunId.value) return
    attempts.value = result.items
  } catch (failure) {
    if (disposed || !props.active || controller.signal.aborted || nodeRunId !== selectedNodeRunId.value) return
    attempts.value = []
    errorMessage.value = failure instanceof Error ? failure.message : t('soar.unableLoadAttempts')
  } finally {
    if (attemptController === controller) attemptController = null
  }
}

function openStream() {
  closeStream()
  if (disposed || !props.active || !selectedRunId.value || typeof EventSource === 'undefined') {
    streamState.value = 'polling'
    return
  }
  const streamRunId = selectedRunId.value
  const generation = loadGeneration
  stream = new EventSource(`/soar-web/api/runs/${encodeURIComponent(streamRunId)}/stream`)
  const source = stream
  streamState.value = 'live'
  stream.addEventListener('run-event', event => {
    // An old run's EventSource stays open until the new run's load reaches
    // openStream(); its late events must not append to the new timeline.
    if (disposed || !props.active || source !== stream || streamRunId !== selectedRunId.value || generation !== loadGeneration) return
    const payload = (event as MessageEvent<string>).data
    try {
      const item = JSON.parse(payload) as SoarEvent
      mergeEvents([item])
      projectionRefresh.request()
    } catch { /* malformed stream data is ignored; the next poll repairs the projection */ }
  })
  stream.onerror = () => {
    if (source !== stream) return
    closeStream()
    streamState.value = 'polling'
  }
}

function closeStream() {
  stream?.close()
  stream = undefined
}

const projectionRefresh = coalescedRefresh(() => refreshProjection(), 5_000)

async function refreshProjection(force = false) {
  if (disposed || !props.active || !selectedRunId.value || loading.value) return
  // Poll ticks and stream events coalesce while this run is loading. Cancelling
  // at each five-second tick would starve healthy but slower requests forever.
  if (projectionController && !projectionController.signal.aborted && !force) {
    projectionPending = true
    return
  }
  projectionPending = false
  projectionController?.abort()
  const controller = new AbortController()
  projectionController = controller
  const options = { signal: controller.signal }
  const runId = selectedRunId.value
  const generation = loadGeneration
  try {
    const [runResult, nodeResult, artifactResult] = await Promise.all([
      getRun(runId, options), listNodes(runId, options), listArtifacts(runId, options),
    ])
    if (disposed || !props.active || controller.signal.aborted || generation !== loadGeneration) return
    run.value = runResult
    nodes.value = nodeResult
    if (!nodes.value.some(node => node.id === selectedNodeRunId.value)) {
      selectedNodeRunId.value = nodes.value[0]?.id ?? ''
    }
    artifacts.value = artifactResult
    await catchUpEvents(runId, generation, controller.signal)
    await loadAttempts()
  } catch (failure) { if (!controller.signal.aborted && generation === loadGeneration) eventError.value = `Timeline/projection may be stale: ${failureText(failure)}` }
  finally {
    if (projectionController === controller) {
      projectionController = null
      const pending = projectionPending
      projectionPending = false
      if (pending && !controller.signal.aborted && generation === loadGeneration) void refreshProjection()
    }
  }
}

async function cancel() {
  if (!props.canExecute || !selectedRunId.value || controlBusy.value) return
  const runId = selectedRunId.value
  errorMessage.value = ''
  controlBusy.value = 'cancel'
  try {
    const reason = await promptInput(t('soar.cancelReason'))
    if (!reason || !props.canExecute || disposed || !props.active || runId !== selectedRunId.value) return
    await cancelWorkflowRun(runId, reason)
    if (runId === selectedRunId.value) await refreshProjection(true)
  } catch (failure) {
    if (runId === selectedRunId.value) errorMessage.value = failureText(failure)
  } finally {
    controlBusy.value = ''
  }
}

async function retry() {
  if (!props.canExecute || !selectedRunId.value || controlBusy.value) return
  const runId = selectedRunId.value
  errorMessage.value = ''
  controlBusy.value = 'retry'
  try {
    const reason = await promptInput(t('soar.retryReason'))
    if (!reason || !props.canExecute || disposed || !props.active || runId !== selectedRunId.value) return
    await retryRun(runId, reason)
    if (runId === selectedRunId.value) await loadRuns()
  } catch (failure) {
    if (runId === selectedRunId.value) errorMessage.value = failureText(failure)
  } finally {
    controlBusy.value = ''
  }
}

async function rerun() {
  if (!props.canExecute || !selectedRunId.value || controlBusy.value) return
  const runId = selectedRunId.value
  errorMessage.value = ''
  controlBusy.value = 'rerun'
  try {
    if (!(await confirmDanger(t('soar.rerunConfirm'))) || !props.canExecute || disposed || !props.active || runId !== selectedRunId.value) return
    await rerunRun(runId, t('soar.rerunRun'))
    if (runId === selectedRunId.value) await loadRuns()
  } catch (failure) {
    if (runId === selectedRunId.value) errorMessage.value = failureText(failure)
  } finally {
    controlBusy.value = ''
  }
}

async function resolveUnknown(node: SoarNodeRun, resolution: 'CONFIRMED_SUCCEEDED' | 'CONFIRMED_NOT_EXECUTED') {
  if (!props.canOperate || controlBusy.value) return
  const runId = selectedRunId.value
  const nodeId = node.id
  errorMessage.value = ''
  controlBusy.value = 'resolve'
  try {
    const evidence = await promptInput(t('soar.evidenceRequired'))
    if (!evidence || !props.canOperate || disposed || !props.active || runId !== selectedRunId.value) return
    const reason = await promptInput(t('soar.resolutionReasonRequired'))
    if (!reason || !props.canOperate || disposed || !props.active || runId !== selectedRunId.value) return
    await resolveUnknownApi(nodeId, resolution, evidence, reason)
    if (runId === selectedRunId.value) await refreshProjection(true)
  } catch (failure) {
    if (runId === selectedRunId.value) errorMessage.value = failureText(failure)
  } finally {
    controlBusy.value = ''
  }
}

async function viewArtifact(artifact: SoarArtifact) {
  try {
    const content = await getArtifactContent(artifact.id)
    const text = json(content)
    const blob = new Blob([text], { type: artifact.mediaType || 'application/json' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `soar-artifact-${artifact.id}.json`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : t('soar.unableDownloadArtifact')
  }
}

watch(() => [props.contextAlarmId, props.contextCaseId], () => {
  selectedRunId.value = props.initialRunId || ''
  queueDialogVisible.value = false
  runsPage.value = 0
  if (props.active) void loadRuns()
})
watch(() => props.initialRunId, id => { if (id !== selectedRunId.value) selectedRunId.value = id })
watch(selectedRunId, () => {
  if (selectedRunId.value) emit('select-run', selectedRunId.value)
  if (selectedRunId.value) { void refreshRun(); return }
  loadGeneration++
  runController?.abort()
  projectionController?.abort()
  attemptController?.abort()
  closeStream()
  run.value = null
  nodes.value = []
  events.value = []
  artifacts.value = []
  attempts.value = []
  selectedNodeRunId.value = ''
  loading.value = false
  streamState.value = 'closed'
}, { flush: 'sync' })
watch(queueDialogVisible, visible => { if (!visible) cancelQueueReads() }, { flush: 'sync' })
watch(() => props.canExecute, canExecute => { if (!canExecute) queueDialogVisible.value = false })
watch(selectedNodeRunId, () => { void loadAttempts() })

function startPolling(): void {
  stopPolling()
  pollTimer = setInterval(() => { projectionRefresh.request() }, 5000)
}
function stopPolling(): void {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = undefined }
}
watch(() => props.active, active => {
  if (!active) {
    cancelReads()
    queueDialogVisible.value = false
    stopPolling()
    closeStream()
    streamState.value = 'closed'
    return
  }
  void loadRuns()
  startPolling()
})

onMounted(() => {
  if (!props.active) return
  void loadRuns()
  startPolling()
})
onUnmounted(() => {
  disposed = true
  projectionRefresh.dispose()
  cancelReads()
  stopPolling()
  closeStream()
})

function cancelReads(): void {
  cancelQueueReads()
  historyController?.abort()
  historyLoading.value = false
  loadGeneration++
  projectionPending = false
  runsController?.abort()
  runController?.abort()
  projectionController?.abort()
  attemptController?.abort()
  loading.value = false
  runsLoading.value = false
}

function statusLabel(status: string): string {
  return tOr(t, 'soar.status.' + status, status)
}

function nodeTypeLabel(type: string): string {
  return tOr(t, 'soar.nodeType.' + type, type)
}

function streamLabel(state: 'closed' | 'live' | 'polling'): string {
  return t('soar.streamState.' + state)
}
</script>

<template>
  <el-card shadow="never" class="soar-run-inspector">
    <template #header>
      <div class="soar-inspector-header">
        <div>
          <strong>{{ t('soar.runInspectorTitle') }}</strong>
          <span class="soar-subtitle">{{ t('soar.runInspectorSubtitle') }}</span>
          <span v-if="!props.canWrite" class="soar-readonly-note">{{ t('soar.readOnly') }}</span>
        </div>
        <div class="soar-run-select">
          <el-button v-if="props.canExecute" size="small" type="primary" plain @click="openQueueDialog">{{ t('soar.queueRun') }}</el-button>
          <select v-model="selectedRunId" :disabled="Boolean(controlBusy)" :aria-label="t('soar.selectRun')">
            <option value="">{{ t('soar.selectRun') }}</option>
            <option v-if="selectedRunOffPage" :value="selectedRunId">{{ selectedRunId }}<template v-if="run?.runId === selectedRunId"> · {{ statusLabel(run.status) }}</template></option>
            <option v-for="item in runs" :key="item.runId" :value="item.runId">{{ item.runId }} · {{ statusLabel(item.status) }}</option>
          </select>
          <el-button size="small" :loading="loading || runsLoading" @click="loadRuns()">{{ t('common.refresh') }}</el-button>
        </div>
      </div>
      <form class="soar-run-select" @submit.prevent="runsPage = 0; loadRuns(false)"><el-input v-model="runKeyword" :aria-label="t('analystJourney.runSearchLabel')" :placeholder="t('analystJourney.runSearchPlaceholder')" /><select v-model="runStatusFilter" :aria-label="t('analystJourney.runStatus')"><option value="">{{ t('analystJourney.allStatuses') }}</option><option v-for="status in ['QUEUED','RUNNING','WAITING_APPROVAL','SUCCEEDED','FAILED','ACTION_UNKNOWN','CANCELLED']" :key="status">{{ status }}</option></select><el-button native-type="submit">{{ t('analystJourney.searchRuns') }}</el-button></form>
      <SoarCatalogPager class="soar-runs-pager" :page="runsPage" :total="runsTotal" :total-pages="runsTotalPages" :loading="runsLoading" :disabled="Boolean(controlBusy)" :label="t('soar.selectRun')" @change="changeRunsPage" />
    </template>

    <div v-if="runsError" class="soar-inspector-error" role="alert">{{ runsError }}</div>
    <div v-if="errorMessage" class="soar-inspector-error" role="alert">{{ errorMessage }}</div>
    <template v-if="run">
      <div class="soar-run-summary">
        <el-tag size="small" :type="run.status === 'SUCCEEDED' ? 'success' : (['FAILED', 'ACTION_UNKNOWN', 'TIMED_OUT'].includes(run.status) ? 'danger' : 'warning')">{{ statusLabel(run.status) }}</el-tag>
        <span><b>{{ run.runId }}</b></span><span>{{ t('soar.revisionLabel', { version: run.playbookVersion }) }}</span><span>{{ run.triggerType }}</span>
        <span class="soar-stream-state" :class="streamState">● {{ streamLabel(streamState) }}</span>
        <span>{{ t('analystJourney.runTarget', { type: run.subject?.type ?? '', id: run.subject?.id ?? '', requester: run.requestedBy ?? '' }) }} </span>
        <span class="soar-toolbar-spacer" />
        <el-button
          size="small"
          type="primary"
          plain
          :disabled="!canOpenInEditor"
          :title="t('soar.runHighlightOpenHint')"
          @click="openInEditor"
        >{{ t('soar.runHighlightOpen') }}</el-button>
        <template v-if="props.canExecute">
          <el-button size="small" @click="cancel" :loading="controlBusy === 'cancel'" :disabled="Boolean(controlBusy) || ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'SUPPRESSED', 'DEAD', 'CANCELLING'].includes(run.status)">{{ t('soar.cancelRun') }}</el-button>
          <el-button size="small" @click="retry" :loading="controlBusy === 'retry'" :disabled="Boolean(controlBusy) || !['FAILED', 'ACTION_UNKNOWN', 'TIMED_OUT', 'DEAD'].includes(run.status)">{{ t('soar.retryRun') }}</el-button>
          <el-button size="small" type="warning" plain @click="rerun" :loading="controlBusy === 'rerun'" :disabled="Boolean(controlBusy)">{{ t('soar.rerunRun') }}</el-button>
        </template>
      </div>

      <p>{{ t('analystJourney.outcomeGuidance') }}</p>
      <div v-if="run.errorCode" class="soar-run-error"><b>{{ run.errorCode }}</b> {{ run.errorMessage }}</div>

      <div class="soar-run-grid">
        <section class="soar-run-panel">
          <div class="soar-panel-title">{{ t('soar.nodeRuns') }}</div>
          <div class="soar-table-scroll">
            <table><thead><tr><th>{{ t('soar.node') }}</th><th>{{ t('common.type') }}</th><th>{{ t('common.status') }}</th><th>{{ t('soar.iteration') }}</th><th>{{ t('soar.output') }}</th></tr></thead>
              <tbody><tr v-for="node in nodes" :key="node.id" :class="{ active: selectedNodeRunId === node.id }" @click="selectedNodeRunId = node.id">
                <td><RowActivate :aria-label="String(node.nodeId ?? node.id)" @activate="selectedNodeRunId = node.id"><b>{{ node.nodeId }}</b><small>{{ node.id }}</small></RowActivate></td><td>{{ nodeTypeLabel(node.nodeType) }}</td><td><el-tag size="small" :type="['FAILED', 'ACTION_UNKNOWN', 'UNKNOWN'].includes(node.status) ? 'danger' : (node.status === 'SUCCEEDED' ? 'success' : 'info')">{{ statusLabel(node.status) }}</el-tag></td><td>{{ node.iterationPath || '-' }}</td><td class="mono">{{ json(node.output).slice(0, 180) }}</td>
              </tr></tbody>
            </table>
            <div v-if="!nodes.length" class="soar-empty">{{ t('soar.noNodeProjection') }}</div>
          </div>
          <div v-if="unknownNodes.length" class="soar-unknown-box">
            <b>{{ t('soar.unknownOutcome') }}</b>
            <div v-for="node in unknownNodes" :key="node.id" class="soar-unknown-row"><span>{{ node.nodeId }}</span><template v-if="props.canOperate"><el-button size="small" type="success" plain :loading="controlBusy === 'resolve'" :disabled="Boolean(controlBusy)" @click="resolveUnknown(node, 'CONFIRMED_SUCCEEDED')">{{ t('soar.confirmSucceeded') }}</el-button><el-button size="small" type="warning" plain :loading="controlBusy === 'resolve'" :disabled="Boolean(controlBusy)" @click="resolveUnknown(node, 'CONFIRMED_NOT_EXECUTED')">{{ t('soar.confirmNotExecuted') }}</el-button></template></div>
          </div>
        </section>

        <section class="soar-run-panel">
          <div class="soar-panel-title">{{ t('soar.actionAttempts') }} <span v-if="selectedNode">· {{ selectedNode.nodeId }}</span></div>
          <div class="soar-table-scroll"><table><thead><tr><th>#</th><th>{{ t('common.status') }}</th><th>{{ t('soar.remoteReceipt') }}</th><th>{{ t('common.error') }}</th></tr></thead><tbody><tr v-for="attempt in attempts" :key="attempt.id"><td>{{ attempt.attemptNo }}</td><td>{{ statusLabel(attempt.status) }}</td><td class="mono">{{ attempt.remoteOperationId || json(attempt.receipt) || '-' }}</td><td>{{ attempt.errorCode || attempt.errorMessage || '-' }}</td></tr></tbody></table><div v-if="!attempts.length" class="soar-empty">{{ t('soar.noActionAttempts') }}</div></div>
          <div class="soar-panel-title soar-events-title">{{ t('soar.eventTimeline') }} · {{ visibleEvents.length }} {{ tOr(t, 'common.itemsSuffix', 'events') }}</div>
          <div v-if="eventError" role="alert">{{ eventError }}</div>
          <div class="soar-run-select">
            <el-button size="small" :disabled="historyLoading || (historyPage ?? eventTotalPages - 1) <= 0" @click="loadEventHistory((historyPage ?? eventTotalPages - 1) - 1)">{{ t('analystJourney.olderEvents') }}</el-button>
            <el-button v-if="historyPage !== null" size="small" :disabled="historyLoading || historyPage >= eventTotalPages - 1" @click="loadEventHistory(historyPage + 1)">{{ t('analystJourney.newerEvents') }}</el-button>
            <el-button v-if="historyPage !== null" size="small" @click="loadEventHistory(null)">{{ t('analystJourney.liveEvents') }}</el-button>
            <span>{{ historyPage === null ? t('analystJourney.latestEvents') : t('analystJourney.historyPage', { page: historyPage + 1 }) }}</span>
          </div>
          <div class="soar-event-list"><div v-for="event in [...visibleEvents].reverse()" :key="event.id" class="soar-event"><span class="soar-event-seq">#{{ event.sequence }}</span><span><b>{{ event.eventType }}</b><small>{{ event.summary }}</small></span><time>{{ event.createdAt || '' }}</time></div><div v-if="!visibleEvents.length" class="soar-empty">{{ t('soar.noEvents') }}</div></div>
        </section>

        <section class="soar-run-panel">
          <div class="soar-panel-title">{{ t('soar.artifacts') }} · {{ artifacts.length }}</div>
          <div v-for="artifact in artifacts" :key="artifact.id" class="soar-artifact"><div><b>{{ artifact.mediaType }}</b><small>{{ artifact.sizeBytes }} {{ t('soar.bytes') }} · {{ artifact.classification }}</small></div><el-button link size="small" @click="viewArtifact(artifact)">{{ t('soar.download') }}</el-button></div>
          <div v-if="!artifacts.length" class="soar-empty">{{ t('soar.noArtifacts') }}</div>
          <div class="soar-panel-title soar-events-title">{{ t('soar.projectionMetadata') }}</div>
          <dl class="soar-metadata"><dt>{{ t('soar.request') }}</dt><dd>{{ run.requestId }}</dd><dt>{{ t('soar.workflow') }}</dt><dd>{{ run.temporalWorkflowId || '-' }}</dd><dt>{{ t('soar.definition') }}</dt><dd class="mono">{{ run.definitionHash }}</dd><dt>{{ t('soar.eventsAfter') }}</dt><dd>{{ lastSequence }}</dd></dl>
        </section>
      </div>
    </template>
    <div v-else class="soar-empty soar-no-run">{{ t('soar.noRunSelected') }}</div>
    <div v-if="queueMessage" class="soar-queue-message" role="status">{{ queueMessage }}</div>

    <el-dialog v-if="props.canExecute" v-model="queueDialogVisible" :before-close="queueGuard.beforeClose" :title="t('soar.queuePublishedRun')" width="520px" :close-on-click-modal="false">
      <p class="soar-dialog-hint">{{ t('soar.queueHint') }}</p>
      <el-form label-position="top" :disabled="queueLoading">
        <el-form-item :label="t('soar.playbooks')" required>
          <el-select class="soar-queue-playbook" :model-value="queuePlaybook?.id ?? ''" filterable :loading="queueCatalogLoading" style="width: 100%" @change="selectQueuePlaybook">
            <el-option v-for="item in queuePlaybookOptions" :key="item.id" :value="item.id" :label="item.name" />
          </el-select>
          <SoarCatalogPager class="soar-queue-playbooks-pager" :page="queuePlaybooksPage" :total="queuePlaybooksTotal" :total-pages="queuePlaybooksTotalPages" :loading="queueCatalogLoading" :disabled="queueLoading" :label="t('soar.playbooks')" @change="changeQueuePlaybooksPage" />
          <div v-if="queueCatalogError" class="soar-inspector-error" role="alert">{{ queueCatalogError }}</div>
        </el-form-item>
        <el-form-item :label="t('soar.publishedPlaybookVersion')" required>
          <el-select v-model="queueForm.playbookVersionId" filterable :loading="queueVersionsLoading" :disabled="queueVersionsLoading || !queuePlaybook" :placeholder="t('soar.selectPublishedVersion')" style="width: 100%">
            <el-option
              v-for="item in publishedVersions"
              :key="item.version.id"
              :value="item.version.id"
              :label="`${item.playbook.name} · ${t('soar.revisionLabel', { version: item.version.version })}`"
            >
              <span>{{ item.playbook.name }} · {{ t('soar.revisionLabel', { version: item.version.version }) }}</span>
              <small class="soar-option-id">{{ item.version.id }}</small>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item :label="t('soar.requestId')" required>
          <el-input v-model="queueForm.requestId" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('soar.subjectJson')">
          <el-input v-model="queueForm.subject" type="textarea" :rows="3" spellcheck="false" />
        </el-form-item>
        <el-form-item :label="t('soar.inputsJson')" required>
          <SchemaInputForm v-if="queueInputSchema" :key="queueForm.playbookVersionId" v-model="queueInputs" :schema="queueInputSchema" @valid="queueInputsValid = $event" :disabled="queueLoading" />
          <p v-else>{{ t('analystJourney.noQueueInputSchema') }}</p>
          <el-input v-if="!queueInputSchema" v-model="queueForm.inputs" type="textarea" :rows="5" spellcheck="false" />
        </el-form-item>
      </el-form>
      <div v-if="queueError" class="soar-inspector-error" role="alert">{{ queueError }}</div>
      <template #footer>
        <el-button @click="queueGuard.cancel">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="queueLoading" :disabled="queueVersionsLoading || !queueForm.playbookVersionId" @click="submitQueue">{{ t('soar.acceptAndQueue') }}</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.soar-run-inspector { margin-top: 16px; border: 1px solid var(--ns-border); }
.soar-inspector-header { display: flex; justify-content: space-between; gap: 16px; align-items: center; }
.soar-subtitle { display: block; margin-top: 4px; color: var(--ns-text-3); font-size: 11px; }
.soar-readonly-note { display: block; margin-top: 6px; color: var(--ns-warning); font-size: 11px; line-height: 1.4; }
.soar-run-select { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.soar-run-select select { min-width: 290px; min-height: 30px; padding: 5px 8px; border: 1px solid var(--ns-border); border-radius: 5px; background: var(--ns-bg); color: var(--ns-text); font: inherit; font-size: 11px; }
.soar-dialog-hint { margin: 0 0 14px; color: var(--ns-text-2); font-size: 12px; line-height: 1.5; }
.soar-option-id { display: block; margin-top: 2px; color: var(--ns-text-3); font-family: ui-monospace, monospace; font-size: 10px; }
.soar-queue-message { margin-top: 10px; padding: 7px 10px; border: 1px solid color-mix(in srgb, var(--ns-success) 28%, var(--ns-border)); border-radius: 5px; color: var(--ns-success); background: color-mix(in srgb, var(--ns-success) 8%, transparent); font-size: 11px; }
.soar-inspector-error, .soar-run-error { margin-bottom: 10px; padding: 7px 10px; border-radius: 5px; color: var(--ns-danger); background: color-mix(in srgb, var(--ns-danger) 9%, transparent); font-size: 11px; }
.soar-run-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; color: var(--ns-text-2); font-size: 11px; }
.soar-toolbar-spacer { flex: 1; }
.soar-stream-state { color: var(--ns-text-3); }
.soar-stream-state.live { color: var(--ns-success); }.soar-stream-state.polling { color: var(--ns-warning); }
.soar-run-grid { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr) minmax(220px, .72fr); gap: 10px; }
.soar-run-panel { min-width: 0; padding: 10px; border: 1px solid var(--ns-border); border-radius: 6px; background: var(--ns-bg-subtle); }
.soar-table-scroll { max-height: 330px; overflow: auto; }
.soar-run-panel table { width: 100%; border-collapse: collapse; font-size: 10px; }
.soar-run-panel th, .soar-run-panel td { padding: 7px 6px; border-bottom: 1px solid var(--ns-border); text-align: left; vertical-align: top; }
.soar-run-panel th { color: var(--ns-text-3); font-size: 9px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; }
.soar-run-panel tbody tr { cursor: pointer; }.soar-run-panel tbody tr:hover, .soar-run-panel tbody tr.active { background: color-mix(in srgb, var(--ns-accent) 8%, transparent); }
.soar-run-panel td b, .soar-run-panel td small { display: block; }.soar-run-panel td small { margin-top: 2px; color: var(--ns-text-3); font-family: ui-monospace, monospace; font-size: 9px; }
.mono { max-width: 220px; overflow-wrap: anywhere; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.soar-empty { padding: 10px 0; color: var(--ns-text-3); font-size: 11px; }
.soar-unknown-box { margin-top: 10px; padding: 8px; border: 1px solid color-mix(in srgb, var(--ns-danger) 32%, var(--ns-border)); border-radius: 5px; color: var(--ns-danger); font-size: 11px; }
.soar-unknown-row { display: flex; align-items: center; gap: 6px; margin-top: 7px; color: var(--ns-text-2); }.soar-unknown-row span { margin-right: auto; font-family: ui-monospace, monospace; }
.soar-events-title { margin-top: 13px; padding-top: 10px; border-top: 1px solid var(--ns-border); }
.soar-event-list { max-height: 300px; overflow: auto; }.soar-event { display: flex; gap: 8px; padding: 7px 0; border-bottom: 1px solid var(--ns-border); font-size: 10px; }.soar-event-seq { min-width: 24px; color: var(--ns-text-3); font-family: ui-monospace, monospace; }.soar-event b, .soar-event small { display: block; }.soar-event small { margin-top: 2px; color: var(--ns-text-2); }.soar-event time { margin-left: auto; color: var(--ns-text-3); white-space: nowrap; }
.soar-artifact { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 0; border-bottom: 1px solid var(--ns-border); font-size: 10px; }.soar-artifact b, .soar-artifact small { display: block; }.soar-artifact small { margin-top: 2px; color: var(--ns-text-3); }
.soar-metadata { display: grid; grid-template-columns: 70px 1fr; gap: 7px; margin: 0; font-size: 10px; }.soar-metadata dt { color: var(--ns-text-3); }.soar-metadata dd { margin: 0; overflow-wrap: anywhere; color: var(--ns-text-2); }
.soar-no-run { min-height: 80px; }
@media (max-width: 1100px) { .soar-run-grid { grid-template-columns: 1fr 1fr; }.soar-run-panel:last-child { grid-column: 1 / -1; } }
@media (max-width: 720px) { .soar-inspector-header { align-items: flex-start; flex-direction: column; }.soar-run-select { width: 100%; }.soar-run-select select { min-width: 0; flex: 1; }.soar-run-grid { display: block; }.soar-run-panel { margin-top: 10px; }.soar-run-summary { align-items: flex-start; flex-direction: column; }.soar-toolbar-spacer { display: none; } }
</style>
