<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  cancelV2Run,
  getV2ArtifactContent,
  getV2Run,
  listV2Artifacts,
  listV2Events,
  listV2NodeAttempts,
  listV2Nodes,
  listV2Runs,
  rerunV2Run,
  resolveV2Unknown,
  retryV2Run,
  type SoarV2Artifact,
  type SoarV2Attempt,
  type SoarV2Event,
  type SoarV2NodeRun,
  type SoarV2Run,
} from '../../api'

const runs = ref<SoarV2Run[]>([])
const selectedRunId = ref('')
const run = ref<SoarV2Run | null>(null)
const nodes = ref<SoarV2NodeRun[]>([])
const events = ref<SoarV2Event[]>([])
const artifacts = ref<SoarV2Artifact[]>([])
const attempts = ref<SoarV2Attempt[]>([])
const selectedNodeRunId = ref('')
const loading = ref(false)
const errorMessage = ref('')
const streamState = ref<'closed' | 'live' | 'polling'>('closed')
let pollTimer: ReturnType<typeof setInterval> | undefined
let stream: EventSource | undefined

const selectedNode = computed(() => nodes.value.find(node => node.id === selectedNodeRunId.value))
const lastSequence = computed(() => events.value.reduce((max, item) => Math.max(max, item.sequence || 0), 0))
const unknownNodes = computed(() => nodes.value.filter(node => ['ACTION_UNKNOWN', 'UNKNOWN'].includes(node.status)))

function json(value: unknown): string {
  if (value === undefined || value === null || value === '') return ''
  if (typeof value === 'string') return value
  try { return JSON.stringify(value) } catch { return String(value) }
}

async function loadRuns() {
  try {
    const result = await listV2Runs(0, 100)
    runs.value = result.items
    if (!selectedRunId.value && runs.value[0]) selectedRunId.value = runs.value[0].runId
    if (selectedRunId.value) await refreshRun()
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to load SOAR runs'
  }
}

async function refreshRun() {
  if (!selectedRunId.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [runResult, nodeResult, eventResult, artifactResult] = await Promise.all([
      getV2Run(selectedRunId.value),
      listV2Nodes(selectedRunId.value),
      listV2Events(selectedRunId.value, 0, 0, 200),
      listV2Artifacts(selectedRunId.value),
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
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to load run details'
  } finally {
    loading.value = false
  }
}

async function loadAttempts() {
  if (!selectedNodeRunId.value) { attempts.value = []; return }
  try {
    const result = await listV2NodeAttempts(selectedNodeRunId.value, 0, 100)
    attempts.value = result.items
  } catch (failure) {
    attempts.value = []
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to load action attempts'
  }
}

function openStream() {
  closeStream()
  if (!selectedRunId.value || typeof EventSource === 'undefined') {
    streamState.value = 'polling'
    return
  }
  stream = new EventSource(`/soar-web/api/v2/runs/${encodeURIComponent(selectedRunId.value)}/stream`)
  streamState.value = 'live'
  stream.addEventListener('run-event', event => {
    const payload = (event as MessageEvent<string>).data
    try {
      const item = JSON.parse(payload) as SoarV2Event
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
      getV2Run(selectedRunId.value), listV2Nodes(selectedRunId.value), listV2Artifacts(selectedRunId.value),
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
  if (!selectedRunId.value) return
  const reason = window.prompt('Cancellation reason', 'Cancelled from Workbench')
  if (reason === null) return
  await cancelV2Run(selectedRunId.value, reason)
  await refreshProjection()
}

async function retry() {
  if (!selectedRunId.value) return
  const reason = window.prompt('Retry reason', 'Retry from Workbench')
  if (reason === null) return
  await retryV2Run(selectedRunId.value, reason)
  await loadRuns()
}

async function rerun() {
  if (!selectedRunId.value || !window.confirm('Create a new execution series with new idempotency keys?')) return
  await rerunV2Run(selectedRunId.value, 'Explicit rerun from Workbench')
  await loadRuns()
}

async function resolveUnknown(node: SoarV2NodeRun, resolution: 'CONFIRMED_SUCCEEDED' | 'CONFIRMED_NOT_EXECUTED') {
  const evidence = window.prompt('Evidence reference is required')
  if (!evidence) return
  const reason = window.prompt('Resolution reason is required')
  if (!reason) return
  await resolveV2Unknown(node.id, resolution, evidence, reason)
  await refreshProjection()
}

async function viewArtifact(artifact: SoarV2Artifact) {
  try {
    const content = await getV2ArtifactContent(artifact.id)
    const text = json(content)
    const blob = new Blob([text], { type: artifact.mediaType || 'application/json' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `soar-artifact-${artifact.id}.json`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to download artifact'
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
</script>

<template>
  <el-card shadow="never" class="soar-v2-run-inspector">
    <template #header>
      <div class="soar-v2-inspector-header">
        <div>
          <strong>SOAR V2 · Run inspector</strong>
          <span class="soar-v2-subtitle">durable projection · attempts · event stream · artifacts</span>
        </div>
        <div class="soar-v2-run-select">
          <select v-model="selectedRunId" aria-label="SOAR run">
            <option value="">Select run</option>
            <option v-for="item in runs" :key="item.runId" :value="item.runId">{{ item.runId }} · {{ item.status }}</option>
          </select>
          <el-button size="small" :loading="loading" @click="loadRuns">Refresh</el-button>
        </div>
      </div>
    </template>

    <div v-if="errorMessage" class="soar-v2-inspector-error">{{ errorMessage }}</div>
    <template v-if="run">
      <div class="soar-v2-run-summary">
        <el-tag size="small" :type="run.status === 'SUCCEEDED' ? 'success' : (['FAILED', 'ACTION_UNKNOWN', 'TIMED_OUT'].includes(run.status) ? 'danger' : 'warning')">{{ run.status }}</el-tag>
        <span><b>{{ run.runId }}</b></span><span>v{{ run.playbookVersion }}</span><span>{{ run.triggerType }}</span>
        <span class="soar-v2-stream-state" :class="streamState">● {{ streamState }}</span>
        <span class="soar-v2-toolbar-spacer" />
        <el-button size="small" @click="cancel" :disabled="['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'SUPPRESSED', 'DEAD', 'CANCELLING'].includes(run.status)">Cancel</el-button>
        <el-button size="small" @click="retry" :disabled="!['FAILED', 'ACTION_UNKNOWN', 'TIMED_OUT', 'DEAD'].includes(run.status)">Retry</el-button>
        <el-button size="small" type="warning" plain @click="rerun">Rerun</el-button>
      </div>

      <div v-if="run.errorCode" class="soar-v2-run-error"><b>{{ run.errorCode }}</b> {{ run.errorMessage }}</div>

      <div class="soar-v2-run-grid">
        <section class="soar-v2-run-panel">
          <div class="soar-v2-panel-title">Node runs</div>
          <div class="soar-v2-table-scroll">
            <table><thead><tr><th>Node</th><th>Type</th><th>Status</th><th>Iteration</th><th>Output</th></tr></thead>
              <tbody><tr v-for="node in nodes" :key="node.id" :class="{ active: selectedNodeRunId === node.id }" @click="selectedNodeRunId = node.id">
                <td><b>{{ node.nodeId }}</b><small>{{ node.id }}</small></td><td>{{ node.nodeType }}</td><td><el-tag size="small" :type="['FAILED', 'ACTION_UNKNOWN', 'UNKNOWN'].includes(node.status) ? 'danger' : (node.status === 'SUCCEEDED' ? 'success' : 'info')">{{ node.status }}</el-tag></td><td>{{ node.iterationPath || '-' }}</td><td class="mono">{{ json(node.output).slice(0, 180) }}</td>
              </tr></tbody>
            </table>
            <div v-if="!nodes.length" class="soar-v2-empty">No node projection yet.</div>
          </div>
          <div v-if="unknownNodes.length" class="soar-v2-unknown-box">
            <b>Unknown remote outcomes need evidence</b>
            <div v-for="node in unknownNodes" :key="node.id" class="soar-v2-unknown-row"><span>{{ node.nodeId }}</span><el-button size="small" type="success" plain @click="resolveUnknown(node, 'CONFIRMED_SUCCEEDED')">Confirm succeeded</el-button><el-button size="small" type="warning" plain @click="resolveUnknown(node, 'CONFIRMED_NOT_EXECUTED')">Confirm not executed</el-button></div>
          </div>
        </section>

        <section class="soar-v2-run-panel">
          <div class="soar-v2-panel-title">Action attempts <span v-if="selectedNode">· {{ selectedNode.nodeId }}</span></div>
          <div class="soar-v2-table-scroll"><table><thead><tr><th>#</th><th>Status</th><th>Remote receipt</th><th>Error</th></tr></thead><tbody><tr v-for="attempt in attempts" :key="attempt.id"><td>{{ attempt.attemptNo }}</td><td>{{ attempt.status }}</td><td class="mono">{{ attempt.remoteOperationId || json(attempt.receipt) || '-' }}</td><td>{{ attempt.errorCode || attempt.errorMessage || '-' }}</td></tr></tbody></table><div v-if="!attempts.length" class="soar-v2-empty">Select a node with action attempts.</div></div>
          <div class="soar-v2-panel-title soar-v2-events-title">Event timeline · {{ events.length }} events</div>
          <div class="soar-v2-event-list"><div v-for="event in [...events].reverse()" :key="event.id" class="soar-v2-event"><span class="soar-v2-event-seq">#{{ event.sequence }}</span><span><b>{{ event.eventType }}</b><small>{{ event.summary }}</small></span><time>{{ event.createdAt || '' }}</time></div><div v-if="!events.length" class="soar-v2-empty">No events yet.</div></div>
        </section>

        <section class="soar-v2-run-panel">
          <div class="soar-v2-panel-title">Artifacts · {{ artifacts.length }}</div>
          <div v-for="artifact in artifacts" :key="artifact.id" class="soar-v2-artifact"><div><b>{{ artifact.mediaType }}</b><small>{{ artifact.sizeBytes }} bytes · {{ artifact.classification }}</small></div><el-button link size="small" @click="viewArtifact(artifact)">Download</el-button></div>
          <div v-if="!artifacts.length" class="soar-v2-empty">No artifacts attached.</div>
          <div class="soar-v2-panel-title soar-v2-events-title">Projection metadata</div>
          <dl class="soar-v2-metadata"><dt>Request</dt><dd>{{ run.requestId }}</dd><dt>Workflow</dt><dd>{{ run.temporalWorkflowId || '-' }}</dd><dt>Definition</dt><dd class="mono">{{ run.definitionHash }}</dd><dt>Events after</dt><dd>{{ lastSequence }}</dd></dl>
        </section>
      </div>
    </template>
    <div v-else class="soar-v2-empty soar-v2-no-run">No V2 run selected. Queue a published version to inspect its durable execution.</div>
  </el-card>
</template>

<style scoped>
.soar-v2-run-inspector { margin-top: 16px; border: 1px solid var(--ns-border); }
.soar-v2-inspector-header { display: flex; justify-content: space-between; gap: 16px; align-items: center; }
.soar-v2-subtitle { display: block; margin-top: 4px; color: var(--ns-text-3); font-size: 11px; }
.soar-v2-run-select { display: flex; gap: 8px; align-items: center; }
.soar-v2-run-select select { min-width: 290px; min-height: 30px; padding: 5px 8px; border: 1px solid var(--ns-border); border-radius: 5px; background: var(--ns-bg); color: var(--ns-text); font: inherit; font-size: 11px; }
.soar-v2-inspector-error, .soar-v2-run-error { margin-bottom: 10px; padding: 7px 10px; border-radius: 5px; color: var(--ns-danger); background: color-mix(in srgb, var(--ns-danger) 9%, transparent); font-size: 11px; }
.soar-v2-run-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; color: var(--ns-text-2); font-size: 11px; }
.soar-v2-toolbar-spacer { flex: 1; }
.soar-v2-stream-state { color: var(--ns-text-3); }
.soar-v2-stream-state.live { color: var(--ns-success); }.soar-v2-stream-state.polling { color: var(--ns-warning); }
.soar-v2-run-grid { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr) minmax(220px, .72fr); gap: 10px; }
.soar-v2-run-panel { min-width: 0; padding: 10px; border: 1px solid var(--ns-border); border-radius: 6px; background: var(--ns-bg-subtle); }
.soar-v2-table-scroll { max-height: 330px; overflow: auto; }
.soar-v2-run-panel table { width: 100%; border-collapse: collapse; font-size: 10px; }
.soar-v2-run-panel th, .soar-v2-run-panel td { padding: 7px 6px; border-bottom: 1px solid var(--ns-border); text-align: left; vertical-align: top; }
.soar-v2-run-panel th { color: var(--ns-text-3); font-size: 9px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; }
.soar-v2-run-panel tbody tr { cursor: pointer; }.soar-v2-run-panel tbody tr:hover, .soar-v2-run-panel tbody tr.active { background: color-mix(in srgb, var(--ns-accent) 8%, transparent); }
.soar-v2-run-panel td b, .soar-v2-run-panel td small { display: block; }.soar-v2-run-panel td small { margin-top: 2px; color: var(--ns-text-3); font-family: ui-monospace, monospace; font-size: 9px; }
.mono { max-width: 220px; overflow-wrap: anywhere; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.soar-v2-empty { padding: 10px 0; color: var(--ns-text-3); font-size: 11px; }
.soar-v2-unknown-box { margin-top: 10px; padding: 8px; border: 1px solid color-mix(in srgb, var(--ns-danger) 32%, var(--ns-border)); border-radius: 5px; color: var(--ns-danger); font-size: 11px; }
.soar-v2-unknown-row { display: flex; align-items: center; gap: 6px; margin-top: 7px; color: var(--ns-text-2); }.soar-v2-unknown-row span { margin-right: auto; font-family: ui-monospace, monospace; }
.soar-v2-events-title { margin-top: 13px; padding-top: 10px; border-top: 1px solid var(--ns-border); }
.soar-v2-event-list { max-height: 300px; overflow: auto; }.soar-v2-event { display: flex; gap: 8px; padding: 7px 0; border-bottom: 1px solid var(--ns-border); font-size: 10px; }.soar-v2-event-seq { min-width: 24px; color: var(--ns-text-3); font-family: ui-monospace, monospace; }.soar-v2-event b, .soar-v2-event small { display: block; }.soar-v2-event small { margin-top: 2px; color: var(--ns-text-2); }.soar-v2-event time { margin-left: auto; color: var(--ns-text-3); white-space: nowrap; }
.soar-v2-artifact { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 0; border-bottom: 1px solid var(--ns-border); font-size: 10px; }.soar-v2-artifact b, .soar-v2-artifact small { display: block; }.soar-v2-artifact small { margin-top: 2px; color: var(--ns-text-3); }
.soar-v2-metadata { display: grid; grid-template-columns: 70px 1fr; gap: 7px; margin: 0; font-size: 10px; }.soar-v2-metadata dt { color: var(--ns-text-3); }.soar-v2-metadata dd { margin: 0; overflow-wrap: anywhere; color: var(--ns-text-2); }
.soar-v2-no-run { min-height: 80px; }
@media (max-width: 1100px) { .soar-v2-run-grid { grid-template-columns: 1fr 1fr; }.soar-v2-run-panel:last-child { grid-column: 1 / -1; } }
@media (max-width: 720px) { .soar-v2-inspector-header { align-items: flex-start; flex-direction: column; }.soar-v2-run-select { width: 100%; }.soar-v2-run-select select { min-width: 0; flex: 1; }.soar-v2-run-grid { display: block; }.soar-v2-run-panel { margin-top: 10px; }.soar-v2-run-summary { align-items: flex-start; flex-direction: column; }.soar-v2-toolbar-spacer { display: none; } }
</style>
