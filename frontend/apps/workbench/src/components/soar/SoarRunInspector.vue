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
import { useI18n } from '../../composables/useI18n'
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

const props = withDefaults(defineProps<{ canWrite?: boolean }>(), { canWrite: true })
const emit = defineEmits<{ 'open-in-editor': [payload: RunOpenRequest] }>()

const { t } = useI18n()

const runs = ref<SoarRun[]>([])
const selectedRunId = ref('')
const run = ref<SoarRun | null>(null)
const nodes = ref<SoarNodeRun[]>([])
const events = ref<SoarEvent[]>([])
const artifacts = ref<SoarArtifact[]>([])
const attempts = ref<SoarAttempt[]>([])
const selectedNodeRunId = ref('')
const loading = ref(false)
const errorMessage = ref('')
const streamState = ref<'closed' | 'live' | 'polling'>('closed')
const queueDialogVisible = ref(false)
const queueLoading = ref(false)
const queueError = ref('')
const queueMessage = ref('')
const controlBusy = ref<'cancel' | 'retry' | 'rerun' | 'resolve' | ''>('')
const publishedVersions = ref<Array<{ version: SoarVersion; playbook: SoarPlaybook }>>([])
const queueForm = ref({
  playbookVersionId: '',
  requestId: '',
  subject: '{}',
  inputs: '{\n  "eventId": "workbench-manual-run",\n  "eventType": "manual.test"\n}',
})
let pollTimer: ReturnType<typeof setInterval> | undefined
let stream: EventSource | undefined

const selectedNode = computed(() => nodes.value.find(node => node.id === selectedNodeRunId.value))
const lastSequence = computed(() => events.value.reduce((max, item) => Math.max(max, item.sequence || 0), 0))
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

async function loadPublishedVersions(): Promise<void> {
  queueError.value = ''
  try {
    const page = await listPlaybooks(0, 100)
    const results = await Promise.allSettled(page.items.map(async playbook => {
      const versions = await listVersions(playbook.id)
      return versions
        .filter(version => version.status === 'PUBLISHED')
        .map(version => ({ version, playbook }))
    }))
    publishedVersions.value = results
      .filter((result): result is PromiseFulfilledResult<Array<{ version: SoarVersion; playbook: SoarPlaybook }>> => result.status === 'fulfilled')
      .flatMap(result => result.value)
    if (!publishedVersions.value.length) {
      queueError.value = t('soar.noPublishedVersions')
      return
    }
    if (!publishedVersions.value.some(item => item.version.id === queueForm.value.playbookVersionId)) {
      queueForm.value.playbookVersionId = publishedVersions.value[0].version.id
    }
  } catch (failure) {
    publishedVersions.value = []
    queueError.value = failureText(failure)
  }
}

function openQueueDialog(): void {
  if (!props.canWrite) return
  queueMessage.value = ''
  queueError.value = ''
  queueForm.value = {
    playbookVersionId: publishedVersions.value[0]?.version.id ?? '',
    requestId: newRequestId(),
    subject: '{}',
    inputs: '{\n  "eventId": "workbench-manual-run",\n  "eventType": "manual.test"\n}',
  }
  queueDialogVisible.value = true
  void loadPublishedVersions()
}

function parseObject(value: string, label: string): Record<string, unknown> {
  let parsed: unknown
  try { parsed = JSON.parse(value.trim() || '{}') } catch { throw new Error(t('soar.invalidJson', { label })) }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error(t('soar.jsonObjectRequired', { label }))
  return parsed as Record<string, unknown>
}

async function submitQueue(): Promise<void> {
  if (!props.canWrite) return
  queueError.value = ''
  queueMessage.value = ''
  if (!queueForm.value.playbookVersionId) {
    queueError.value = t('soar.choosePublishedVersion')
    return
  }
  queueLoading.value = true
  try {
    const result = await queueRun({
      requestId: queueForm.value.requestId.trim() || newRequestId(),
      playbookVersionId: queueForm.value.playbookVersionId,
      subject: parseObject(queueForm.value.subject, 'Subject'),
      inputs: parseObject(queueForm.value.inputs, 'Inputs'),
    })
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

async function loadRuns() {
  try {
    const result = await listRuns(0, 100)
    runs.value = result.items
    if (!selectedRunId.value && runs.value[0]) selectedRunId.value = runs.value[0].runId
    if (selectedRunId.value) await refreshRun()
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : t('soar.unableLoadRuns')
  }
}

async function refreshRun() {
  if (!selectedRunId.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [runResult, nodeResult, eventResult, artifactResult] = await Promise.all([
      getRun(selectedRunId.value),
      listNodes(selectedRunId.value),
      listEvents(selectedRunId.value, 0, 0, 200),
      listArtifacts(selectedRunId.value),
    ])
    run.value = runResult
    nodes.value = nodeResult
    events.value = eventResult.items
    artifacts.value = artifactResult
    if (!nodes.value.some(node => node.id === selectedNodeRunId.value)) {
      selectedNodeRunId.value = nodes.value[0]?.id ?? ''
    }
    await loadAttempts()
    openStream()
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : t('soar.unableLoadRunDetails')
  } finally {
    loading.value = false
  }
}

async function loadAttempts() {
  if (!selectedNodeRunId.value) { attempts.value = []; return }
  try {
    const result = await listNodeAttempts(selectedNodeRunId.value, 0, 100)
    attempts.value = result.items
  } catch (failure) {
    attempts.value = []
    errorMessage.value = failure instanceof Error ? failure.message : t('soar.unableLoadAttempts')
  }
}

function openStream() {
  closeStream()
  if (!selectedRunId.value || typeof EventSource === 'undefined') {
    streamState.value = 'polling'
    return
  }
  stream = new EventSource(`/soar-web/api/runs/${encodeURIComponent(selectedRunId.value)}/stream`)
  streamState.value = 'live'
  stream.addEventListener('run-event', event => {
    const payload = (event as MessageEvent<string>).data
    try {
      const item = JSON.parse(payload) as SoarEvent
      if (!events.value.some(existing => existing.sequence === item.sequence)) {
        events.value = [...events.value, item].sort((left, right) => left.sequence - right.sequence)
      }
      void refreshProjection()
    } catch { /* malformed stream data is ignored; the next poll repairs the projection */ }
  })
  stream.onerror = () => {
    closeStream()
    streamState.value = 'polling'
  }
}

function closeStream() {
  stream?.close()
  stream = undefined
}

async function refreshProjection() {
  if (!selectedRunId.value) return
  try {
    const [runResult, nodeResult, artifactResult] = await Promise.all([
      getRun(selectedRunId.value), listNodes(selectedRunId.value), listArtifacts(selectedRunId.value),
    ])
    run.value = runResult
    nodes.value = nodeResult
    if (!nodes.value.some(node => node.id === selectedNodeRunId.value)) {
      selectedNodeRunId.value = nodes.value[0]?.id ?? ''
    }
    artifacts.value = artifactResult
    await loadAttempts()
  } catch { /* retain the last known durable projection */ }
}

async function cancel() {
  if (!props.canWrite || !selectedRunId.value) return
  const reason = window.prompt(t('soar.cancelReason'), '')
  if (reason === null) return
  errorMessage.value = ''
  controlBusy.value = 'cancel'
  try {
    await cancelWorkflowRun(selectedRunId.value, reason)
    await refreshProjection()
  } catch (failure) {
    errorMessage.value = failureText(failure)
  } finally {
    controlBusy.value = ''
  }
}

async function retry() {
  if (!props.canWrite || !selectedRunId.value) return
  const reason = window.prompt(t('soar.retryReason'), '')
  if (reason === null) return
  errorMessage.value = ''
  controlBusy.value = 'retry'
  try {
    await retryRun(selectedRunId.value, reason)
    await loadRuns()
  } catch (failure) {
    errorMessage.value = failureText(failure)
  } finally {
    controlBusy.value = ''
  }
}

async function rerun() {
  if (!props.canWrite || !selectedRunId.value || !window.confirm(t('soar.rerunConfirm'))) return
  errorMessage.value = ''
  controlBusy.value = 'rerun'
  try {
    await rerunRun(selectedRunId.value, t('soar.rerunRun'))
    await loadRuns()
  } catch (failure) {
    errorMessage.value = failureText(failure)
  } finally {
    controlBusy.value = ''
  }
}

async function resolveUnknown(node: SoarNodeRun, resolution: 'CONFIRMED_SUCCEEDED' | 'CONFIRMED_NOT_EXECUTED') {
  if (!props.canWrite) return
  const evidence = window.prompt(t('soar.evidenceRequired'))
  if (!evidence) return
  const reason = window.prompt(t('soar.resolutionReasonRequired'))
  if (!reason) return
  errorMessage.value = ''
  controlBusy.value = 'resolve'
  try {
    await resolveUnknownApi(node.id, resolution, evidence, reason)
    await refreshProjection()
  } catch (failure) {
    errorMessage.value = failureText(failure)
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

watch(selectedRunId, () => { void refreshRun() })
watch(selectedNodeRunId, () => { void loadAttempts() })

onMounted(() => {
  void loadRuns()
  pollTimer = setInterval(() => { if (streamState.value !== 'live') void refreshProjection() }, 5000)
})
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
  closeStream()
})

function statusLabel(status: string): string {
  const key = 'soar.status.' + status
  const translated = t(key)
  return translated === key ? status : translated
}

function nodeTypeLabel(type: string): string {
  const key = 'soar.nodeType.' + type
  const translated = t(key)
  return translated === key ? type : translated
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
          <el-button v-if="props.canWrite" size="small" type="primary" plain @click="openQueueDialog">{{ t('soar.queueRun') }}</el-button>
          <select v-model="selectedRunId" :aria-label="t('soar.selectRun')">
            <option value="">{{ t('soar.selectRun') }}</option>
            <option v-for="item in runs" :key="item.runId" :value="item.runId">{{ item.runId }} · {{ statusLabel(item.status) }}</option>
          </select>
          <el-button size="small" :loading="loading" @click="loadRuns">{{ t('common.refresh') }}</el-button>
        </div>
      </div>
    </template>

    <div v-if="errorMessage" class="soar-inspector-error" role="alert">{{ errorMessage }}</div>
    <template v-if="run">
      <div class="soar-run-summary">
        <el-tag size="small" :type="run.status === 'SUCCEEDED' ? 'success' : (['FAILED', 'ACTION_UNKNOWN', 'TIMED_OUT'].includes(run.status) ? 'danger' : 'warning')">{{ statusLabel(run.status) }}</el-tag>
        <span><b>{{ run.runId }}</b></span><span>{{ t('soar.revisionLabel', { version: run.playbookVersion }) }}</span><span>{{ run.triggerType }}</span>
        <span class="soar-stream-state" :class="streamState">● {{ streamLabel(streamState) }}</span>
        <span class="soar-toolbar-spacer" />
        <el-button
          size="small"
          type="primary"
          plain
          :disabled="!canOpenInEditor"
          :title="t('soar.runHighlightOpenHint')"
          @click="openInEditor"
        >{{ t('soar.runHighlightOpen') }}</el-button>
        <template v-if="props.canWrite">
          <el-button size="small" @click="cancel" :loading="controlBusy === 'cancel'" :disabled="Boolean(controlBusy) || ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'SUPPRESSED', 'DEAD', 'CANCELLING'].includes(run.status)">{{ t('soar.cancelRun') }}</el-button>
          <el-button size="small" @click="retry" :loading="controlBusy === 'retry'" :disabled="Boolean(controlBusy) || !['FAILED', 'ACTION_UNKNOWN', 'TIMED_OUT', 'DEAD'].includes(run.status)">{{ t('soar.retryRun') }}</el-button>
          <el-button size="small" type="warning" plain @click="rerun" :loading="controlBusy === 'rerun'" :disabled="Boolean(controlBusy)">{{ t('soar.rerunRun') }}</el-button>
        </template>
      </div>

      <div v-if="run.errorCode" class="soar-run-error"><b>{{ run.errorCode }}</b> {{ run.errorMessage }}</div>

      <div class="soar-run-grid">
        <section class="soar-run-panel">
          <div class="soar-panel-title">{{ t('soar.nodeRuns') }}</div>
          <div class="soar-table-scroll">
            <table><thead><tr><th>{{ t('soar.node') }}</th><th>{{ t('common.type') }}</th><th>{{ t('common.status') }}</th><th>{{ t('soar.iteration') }}</th><th>{{ t('soar.output') }}</th></tr></thead>
              <tbody><tr v-for="node in nodes" :key="node.id" :class="{ active: selectedNodeRunId === node.id }" @click="selectedNodeRunId = node.id">
                <td><b>{{ node.nodeId }}</b><small>{{ node.id }}</small></td><td>{{ nodeTypeLabel(node.nodeType) }}</td><td><el-tag size="small" :type="['FAILED', 'ACTION_UNKNOWN', 'UNKNOWN'].includes(node.status) ? 'danger' : (node.status === 'SUCCEEDED' ? 'success' : 'info')">{{ statusLabel(node.status) }}</el-tag></td><td>{{ node.iterationPath || '-' }}</td><td class="mono">{{ json(node.output).slice(0, 180) }}</td>
              </tr></tbody>
            </table>
            <div v-if="!nodes.length" class="soar-empty">{{ t('soar.noNodeProjection') }}</div>
          </div>
          <div v-if="unknownNodes.length" class="soar-unknown-box">
            <b>{{ t('soar.unknownOutcome') }}</b>
            <div v-for="node in unknownNodes" :key="node.id" class="soar-unknown-row"><span>{{ node.nodeId }}</span><el-button size="small" type="success" plain :loading="controlBusy === 'resolve'" :disabled="Boolean(controlBusy)" @click="resolveUnknown(node, 'CONFIRMED_SUCCEEDED')">{{ t('soar.confirmSucceeded') }}</el-button><el-button size="small" type="warning" plain :loading="controlBusy === 'resolve'" :disabled="Boolean(controlBusy)" @click="resolveUnknown(node, 'CONFIRMED_NOT_EXECUTED')">{{ t('soar.confirmNotExecuted') }}</el-button></div>
          </div>
        </section>

        <section class="soar-run-panel">
          <div class="soar-panel-title">{{ t('soar.actionAttempts') }} <span v-if="selectedNode">· {{ selectedNode.nodeId }}</span></div>
          <div class="soar-table-scroll"><table><thead><tr><th>#</th><th>{{ t('common.status') }}</th><th>{{ t('soar.remoteReceipt') }}</th><th>{{ t('common.error') }}</th></tr></thead><tbody><tr v-for="attempt in attempts" :key="attempt.id"><td>{{ attempt.attemptNo }}</td><td>{{ statusLabel(attempt.status) }}</td><td class="mono">{{ attempt.remoteOperationId || json(attempt.receipt) || '-' }}</td><td>{{ attempt.errorCode || attempt.errorMessage || '-' }}</td></tr></tbody></table><div v-if="!attempts.length" class="soar-empty">{{ t('soar.noActionAttempts') }}</div></div>
          <div class="soar-panel-title soar-events-title">{{ t('soar.eventTimeline') }} · {{ events.length }} {{ t('common.itemsSuffix') || 'events' }}</div>
          <div class="soar-event-list"><div v-for="event in [...events].reverse()" :key="event.id" class="soar-event"><span class="soar-event-seq">#{{ event.sequence }}</span><span><b>{{ event.eventType }}</b><small>{{ event.summary }}</small></span><time>{{ event.createdAt || '' }}</time></div><div v-if="!events.length" class="soar-empty">{{ t('soar.noEvents') }}</div></div>
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

    <el-dialog v-if="props.canWrite" v-model="queueDialogVisible" :title="t('soar.queuePublishedRun')" width="560px">
      <p class="soar-dialog-hint">{{ t('soar.queueHint') }}</p>
      <el-form label-position="top">
        <el-form-item :label="t('soar.publishedPlaybookVersion')" required>
          <el-select v-model="queueForm.playbookVersionId" filterable :loading="publishedVersions.length === 0 && !queueError" :placeholder="t('soar.selectPublishedVersion')" style="width: 100%">
            <el-option
              v-for="item in publishedVersions"
              :key="item.version.id"
              :value="item.version.id"
              :label="`${item.playbook.name} · Revision ${item.version.version}`"
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
          <el-input v-model="queueForm.inputs" type="textarea" :rows="5" spellcheck="false" />
        </el-form-item>
      </el-form>
      <div v-if="queueError" class="soar-inspector-error" role="alert">{{ queueError }}</div>
      <template #footer>
        <el-button @click="queueDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="queueLoading" :disabled="!publishedVersions.length" @click="submitQueue">{{ t('soar.acceptAndQueue') }}</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.soar-run-inspector { margin-top: 16px; border: 1px solid var(--ns-border); }
.soar-inspector-header { display: flex; justify-content: space-between; gap: 16px; align-items: center; }
.soar-subtitle { display: block; margin-top: 4px; color: var(--ns-text-3); font-size: 11px; }
.soar-readonly-note { display: block; margin-top: 6px; color: var(--ns-warning); font-size: 11px; line-height: 1.4; }
.soar-run-select { display: flex; gap: 8px; align-items: center; }
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
