<script setup lang="ts">
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import type { NodeTypesObject } from '@vue-flow/core'
import {
  createV2Playbook,
  createV2Version,
  dryRunV2Version,
  getV2Version,
  listV2Playbooks,
  listV2Versions,
  publishV2Version,
  saveV2Version,
  validateV2Version,
  type SoarV2Playbook,
  type SoarV2Version,
} from '../../api'
import { useDefinitionFlow } from './editor/useDefinitionFlow'
import SoarFlowNode from './editor/SoarFlowNode.vue'
import SoarFlowPalette from './editor/SoarFlowPalette.vue'
import SoarFlowPropertyPanel from './editor/SoarFlowPropertyPanel.vue'
import { PALETTE_DATA_TYPE } from './editor/types'
import type { EditorNode, ValidationIssue, ValidationResult } from './editor/types'

type JsonObject = Record<string, unknown>

const FLOW_ID = 'soar-v2-flow'
const TONE_COLORS: Record<string, string> = {
  start: '#2563eb',
  end: '#64748b',
  action: '#0891b2',
  logic: '#7c3aed',
  control: '#ea580c',
  wait: '#ca8a04',
  human: '#db2777',
  data: '#059669',
}

const props = withDefaults(defineProps<{ initialPlaybookId?: string }>(), { initialPlaybookId: '' })
const emit = defineEmits<{ saved: [SoarV2Version] }>()

const flowStore = useVueFlow(FLOW_ID)
const flow = useDefinitionFlow(flowStore, text => { errorMessage.value = text })

const nodeTypes: NodeTypesObject = { 'soar-flow-node': SoarFlowNode }

const playbooks = ref<SoarV2Playbook[]>([])
const versions = ref<SoarV2Version[]>([])
const selectedPlaybookId = ref(props.initialPlaybookId)
const selectedVersionNo = ref<number | null>(null)
const definitionText = ref('')
const rowVersion = ref<number | undefined>()
const validation = ref<ValidationResult | null>(null)
const dryRunText = ref('{\n  "eventId": "sample-alert-1",\n  "eventType": "alert.created",\n  "severity": "HIGH"\n}')
const dryRunResult = ref<JsonObject | null>(null)
const loading = ref(false)
const saving = ref(false)
const message = ref('')
const errorMessage = ref('')

const selectedVersion = computed(() => versions.value.find(version => version.version === selectedVersionNo.value))
const isDraft = computed(() => selectedVersion.value?.status === 'DRAFT')
const issueCount = computed(() =>
  (validation.value?.errors?.length ?? 0) + (validation.value?.warnings?.length ?? 0))
const flowSelectionCount = computed(() =>
  flowStore.getSelectedNodes.value.length + flowStore.getSelectedEdges.value.length)
const selectedRawNode = computed<EditorNode | null>(() => {
  const id = flow.selectedNodeId.value
  if (!id) return null
  return flow.getDefinition().nodes.find(node => node.id === id) ?? null
})

/* ---------------- definition JSON sync ---------------- */
function syncDefinitionText(): void {
  definitionText.value = JSON.stringify(flow.getDefinition(), null, 2)
}

watch(() => flow.graphRevision.value, syncDefinitionText)

/* ---------------- dirty guard ---------------- */
const beforeUnloadHandler = (event: BeforeUnloadEvent): void => {
  event.preventDefault()
  event.returnValue = ''
}
watch(() => flow.dirty.value, (dirty) => {
  if (dirty) window.addEventListener('beforeunload', beforeUnloadHandler)
  else window.removeEventListener('beforeunload', beforeUnloadHandler)
})

function discardGuard(): boolean {
  if (!flow.dirty.value) return true
  return window.confirm('Discard unsaved changes?')
}

/* ---------------- playbook/version API (unchanged clients) ---------------- */
async function loadCatalog() {
  if (!discardGuard()) return
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listV2Playbooks(0, 100)
    playbooks.value = result.items
    const wanted = props.initialPlaybookId && result.items.some(item => item.id === props.initialPlaybookId)
      ? props.initialPlaybookId : (selectedPlaybookId.value && result.items.some(item => item.id === selectedPlaybookId.value)
        ? selectedPlaybookId.value : result.items[0]?.id ?? '')
    selectedPlaybookId.value = wanted
    if (wanted) await loadVersions()
    else flow.resetToEmpty()
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to load SOAR V2 playbooks'
  } finally {
    loading.value = false
  }
}

async function loadVersions() {
  if (!selectedPlaybookId.value) return
  const result = await listV2Versions(selectedPlaybookId.value)
  versions.value = result
  const draft = result.find(version => version.status === 'DRAFT')
  const target = draft ?? result[0]
  selectedVersionNo.value = target?.version ?? null
  if (target) await loadVersion(target.version)
}

async function loadVersion(versionNo = selectedVersionNo.value ?? 0) {
  if (!selectedPlaybookId.value || !versionNo) return
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await getV2Version(selectedPlaybookId.value, versionNo)
    selectedVersionNo.value = result.version
    rowVersion.value = result.rowVersion
    flow.applyDefinition(result.definition, result.layout)
    validation.value = null
    dryRunResult.value = null
    message.value = `Loaded v${result.version}`
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to load playbook version'
  } finally {
    loading.value = false
  }
}

async function createPlaybookAndVersion() {
  if (!discardGuard()) return
  const name = window.prompt('V2 playbook name')?.trim()
  if (!name) return
  loading.value = true
  try {
    const playbook = await createV2Playbook({ name, description: 'Created in the SOAR V2 graph editor', tags: [] })
    const version = await createV2Version(playbook.id)
    playbooks.value = [playbook, ...playbooks.value.filter(item => item.id !== playbook.id)]
    selectedPlaybookId.value = playbook.id
    versions.value = [version]
    selectedVersionNo.value = version.version
    rowVersion.value = version.rowVersion
    flow.applyDefinition(version.definition)
    validation.value = null
    message.value = 'Created a new draft'
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to create playbook'
  } finally {
    loading.value = false
  }
}

async function createVersion() {
  if (!discardGuard() || !selectedPlaybookId.value) return
  loading.value = true
  try {
    const result = await createV2Version(selectedPlaybookId.value)
    versions.value = [result, ...versions.value.filter(item => item.version !== result.version)]
    selectedVersionNo.value = result.version
    rowVersion.value = result.rowVersion
    flow.applyDefinition(result.definition)
    validation.value = null
    message.value = `Created draft v${result.version}`
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to create version'
  } finally {
    loading.value = false
  }
}

/* ---------------- selectors / change handlers ---------------- */
async function changePlaybook(): Promise<void> {
  const previous = selectedPlaybookId.value
  if (!discardGuard()) {
    selectedPlaybookId.value = previous === '' ? '' : previous
    return
  }
  versions.value = []
  selectedVersionNo.value = null
  if (selectedPlaybookId.value) await loadVersions()
  else flow.resetToEmpty()
}

async function changeVersion(): Promise<void> {
  const previous = selectedVersionNo.value
  if (!discardGuard()) {
    selectedVersionNo.value = previous
    return
  }
  await loadVersion()
}

/* ---------------- apply JSON ---------------- */
function applyDefinitionJson() {
  try {
    const parsed = JSON.parse(definitionText.value)
    flow.applyWorkingCopy(parsed)
    syncDefinitionText()
    validation.value = null
    errorMessage.value = ''
    message.value = 'Definition applied to the editor'
  } catch (failure) {
    errorMessage.value = `Definition JSON is invalid: ${failure instanceof Error ? failure.message : 'invalid JSON'}`
  }
}

/* ---------------- save / validate / dry-run / publish ---------------- */
async function save() {
  if (!selectedPlaybookId.value || !selectedVersionNo.value || !isDraft.value) return
  saving.value = true
  errorMessage.value = ''
  try {
    const payload = flow.serializeForSave()
    const result = await saveV2Version(selectedPlaybookId.value, selectedVersionNo.value,
      payload.definition, payload.layout, rowVersion.value)
    rowVersion.value = result.rowVersion
    versions.value = versions.value.map(item => item.version === result.version ? result : item)
    flow.applyDefinition(result.definition, result.layout)
    validation.value = null
    message.value = `Saved draft v${result.version}`
    emit('saved', result)
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to save draft'
  } finally {
    saving.value = false
  }
}

async function validate() {
  if (!selectedPlaybookId.value || !selectedVersionNo.value) return
  try {
    const result = await validateV2Version(selectedPlaybookId.value, selectedVersionNo.value) as ValidationResult
    validation.value = result
    const issues: ValidationIssue[] = [
      ...(result.errors ?? []),
      ...(result.warnings ?? []),
    ]
    flow.applyIssues(issues)
    errorMessage.value = ''
    message.value = result.valid ? 'Definition is publishable' : 'Definition needs attention'
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : 'Validation failed'
  }
}

async function dryRun() {
  if (!selectedPlaybookId.value || !selectedVersionNo.value) return
  try {
    const inputs = JSON.parse(dryRunText.value) as JsonObject
    dryRunResult.value = await dryRunV2Version(selectedPlaybookId.value, selectedVersionNo.value, {}, inputs) as JsonObject
    errorMessage.value = ''
  } catch (failure) {
    errorMessage.value = `Dry-run failed: ${failure instanceof Error ? failure.message : 'invalid input'}`
  }
}

async function publish() {
  if (!selectedPlaybookId.value || !selectedVersionNo.value || !isDraft.value) return
  await validate()
  if (validation.value && validation.value.valid === false) return
  try {
    const result = await publishV2Version(selectedPlaybookId.value, selectedVersionNo.value)
    versions.value = versions.value.map(item => item.version === result.version ? result : item)
    rowVersion.value = result.rowVersion
    message.value = `Published v${result.version}`
    await loadVersions()
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : 'Publish failed'
  }
}

/* ---------------- canvas interactions ---------------- */
function onCanvasDrop(event: DragEvent): void {
  const type = event.dataTransfer?.getData(PALETTE_DATA_TYPE)
  if (!type) return
  event.preventDefault()
  flow.addFromDrop(type, { x: event.clientX, y: event.clientY })
}

function onKeyDown(event: KeyboardEvent): void {
  const target = event.target as HTMLElement | null
  if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
  if (event.key !== 'Delete' && event.key !== 'Backspace') return
  if (!flowStore.getSelectedNodes.value.length && !flowStore.getSelectedEdges.value.length) return
  event.preventDefault()
  flow.deleteSelection()
}

function onIssueClick(issue: ValidationIssue): void {
  const id = issue.nodeId ?? extractNodeIdFromPath(issue.path)
  if (!id) return
  flow.selectNode(id)
  flow.fitNode(id)
}

function extractNodeIdFromPath(path?: string): string | null {
  if (!path) return null
  const indexMatch = /^\/nodes\/(\d+)(?:\/|$)/.exec(path)
  if (indexMatch) {
    const rawNode = flow.getDefinition().nodes[Number(indexMatch[1])]
    return rawNode?.id ?? null
  }
  return null
}

function miniMapColor(node: { data?: { tone?: string } }): string {
  const tone = node.data?.tone ?? ''
  return TONE_COLORS[tone] ?? '#94a3b8'
}

function issueIsWarning(issue: ValidationIssue): boolean {
  return issue.severity === 'WARNING'
}

watch(() => props.initialPlaybookId, (value) => {
  if (value && value !== selectedPlaybookId.value) {
    selectedPlaybookId.value = value
    void loadVersions()
  }
})

onMounted(() => {
  document.addEventListener('keydown', onKeyDown)
  void loadCatalog()
})

onUnmounted(() => {
  document.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('beforeunload', beforeUnloadHandler)
})
</script>

<template>
  <el-card shadow="never" class="soar-v2-editor">
    <template #header>
      <div class="soar-v2-editor-header">
        <div>
          <strong>SOAR V2 · Playbook graph editor</strong>
          <span class="soar-v2-subtitle">immutable versions · safe nodes · dry-run before publish</span>
        </div>
        <div class="soar-v2-editor-selects">
          <select v-model="selectedPlaybookId" aria-label="V2 playbook" @change="changePlaybook">
            <option value="">Select playbook</option>
            <option v-for="playbook in playbooks" :key="playbook.id" :value="playbook.id">{{ playbook.name }}</option>
          </select>
          <select v-model.number="selectedVersionNo" aria-label="V2 version" @change="changeVersion">
            <option :value="null">Version</option>
            <option v-for="version in versions" :key="version.id" :value="version.version">v{{ version.version }} · {{ version.status }}</option>
          </select>
        </div>
      </div>
    </template>

    <div class="soar-v2-editor-toolbar">
      <el-button size="small" @click="createPlaybookAndVersion">New V2 playbook</el-button>
      <el-button size="small" :disabled="!selectedPlaybookId" @click="createVersion">New draft version</el-button>
      <el-button size="small" :loading="loading" @click="loadCatalog">Reload</el-button>
      <span class="soar-v2-toolbar-spacer" />
      <el-tag v-if="selectedVersion" size="small" :type="isDraft ? 'warning' : 'success'">v{{ selectedVersion.version }} · {{ selectedVersion.status }}</el-tag>
      <el-tag v-if="validation" size="small" :type="flow.validationStale.value ? 'info' : validation.valid ? 'success' : 'danger'">
        {{ flow.validationStale.value ? 'OUTDATED' : validation.valid ? 'VALID' : 'INVALID' }}{{ issueCount ? ` · ${issueCount}` : '' }}
      </el-tag>
      <el-button size="small" @click="validate" :disabled="!selectedVersionNo">Validate</el-button>
      <el-button size="small" @click="dryRun" :disabled="!selectedVersionNo">Dry-run</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="save" :disabled="!isDraft">Save draft</el-button>
      <el-button size="small" type="success" @click="publish" :disabled="!isDraft">Publish</el-button>
    </div>

    <div v-if="message" class="soar-v2-editor-message">{{ message }}</div>
    <div v-if="errorMessage" class="soar-v2-editor-error">{{ errorMessage }}</div>

    <div class="soar-v2-editor-body">
      <SoarFlowPalette :flow="flow" />

      <section class="soar-v2-canvas-panel" aria-label="Playbook graph">
        <div class="soar-v2-canvas" @dragover.prevent @drop="onCanvasDrop">
          <VueFlow
            id="soar-v2-flow"
            :node-types="nodeTypes"
            :delete-key-code="null"
            :is-valid-connection="flow.isValidConnection"
            :min-zoom="0.2"
            :max-zoom="2"
            :zoom-on-scroll="true"
            :nodes-draggable="true"
            :nodes-connectable="true"
          >
            <Background pattern-color="#94a3b8" :gap="18" :size="1" />
            <Controls position="bottom-right" />
            <MiniMap position="bottom-left" :pannable="true" :zoomable="true" :node-color="miniMapColor" />
          </VueFlow>
        </div>
        <div class="soar-v2-canvas-footer">
          <span>{{ flow.nodeCount.value }} nodes · {{ flow.edgeCount.value }} edges</span>
          <span v-if="flowSelectionCount">{{ flowSelectionCount }} selected</span>
          <el-button size="small" type="danger" plain :disabled="!selectedRawNode || selectedRawNode.type === 'START'" @click="flow.removeSelected()">Remove selected</el-button>
        </div>
      </section>

      <SoarFlowPropertyPanel :flow="flow" :node="selectedRawNode" />
    </div>

    <div class="soar-v2-editor-lower">
      <div class="soar-v2-json-panel">
        <div class="soar-v2-panel-title">Definition JSON · advanced import/export</div>
        <textarea v-model="definitionText" rows="12" spellcheck="false" aria-label="Definition JSON" />
        <el-button size="small" @click="applyDefinitionJson">Apply JSON</el-button>
      </div>
      <div class="soar-v2-json-panel">
        <div class="soar-v2-panel-title">Dry-run input</div>
        <textarea v-model="dryRunText" rows="5" spellcheck="false" aria-label="Dry-run input" />
        <pre v-if="dryRunResult" class="soar-v2-result">{{ JSON.stringify(dryRunResult, null, 2) }}</pre>
      </div>
      <div v-if="validation" class="soar-v2-validation-panel">
        <div class="soar-v2-panel-title">Validation result</div>
        <div v-for="issue in [...(validation.errors || []), ...(validation.warnings || [])]" :key="`${issue.code}-${issue.path}-${issue.message}`" class="soar-v2-issue" :class="{ warning: issueIsWarning(issue) }" role="button" tabindex="0" @click="onIssueClick(issue)" @keydown.enter="onIssueClick(issue)">
          <b>{{ issue.code || 'ISSUE' }}</b><span>{{ issue.nodeId ? `${issue.nodeId} · ` : '' }}{{ issue.path || '' }}</span><p>{{ issue.message }}</p>
        </div>
        <div v-if="validation.definitionHash" class="soar-v2-hash">definition hash: {{ validation.definitionHash }}</div>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.soar-v2-editor { margin-top: 16px; border: 1px solid var(--ns-border); }
.soar-v2-editor-header { display: flex; justify-content: space-between; gap: 16px; align-items: center; }
.soar-v2-subtitle { display: block; margin-top: 4px; color: var(--ns-text-3); font-size: 11px; }
.soar-v2-editor-selects { display: flex; gap: 8px; flex-wrap: wrap; }
.soar-v2-editor select, .soar-v2-editor input, .soar-v2-editor textarea { box-sizing: border-box; border: 1px solid var(--ns-border); border-radius: 5px; background: var(--ns-bg); color: var(--ns-text); font: inherit; }
.soar-v2-editor select, .soar-v2-editor input { min-height: 30px; padding: 5px 8px; }
.soar-v2-editor textarea { width: 100%; padding: 8px; resize: vertical; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11px; line-height: 1.45; }
.soar-v2-editor-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; margin-bottom: 10px; }
.soar-v2-toolbar-spacer { flex: 1; }
.soar-v2-editor-message, .soar-v2-editor-error { margin: 6px 0 10px; border-radius: 5px; padding: 7px 10px; font-size: 12px; }
.soar-v2-editor-message { color: var(--ns-success); background: color-mix(in srgb, var(--ns-success) 10%, transparent); }
.soar-v2-editor-error { color: var(--ns-danger); background: color-mix(in srgb, var(--ns-danger) 10%, transparent); }
.soar-v2-editor-body { display: grid; grid-template-columns: 180px minmax(560px, 1fr) 260px; min-height: 570px; border: 1px solid var(--ns-border); border-radius: 6px; overflow: hidden; }
.soar-v2-canvas-panel { display: flex; min-width: 0; flex-direction: column; background: var(--ns-bg); }
.soar-v2-canvas { position: relative; min-height: 540px; flex: 1; background: var(--ns-bg); }
.soar-v2-canvas .vue-flow { height: 540px; }
.soar-v2-canvas-footer { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-top: 1px solid var(--ns-border); color: var(--ns-text-3); font-size: 11px; }
.soar-v2-canvas-footer span:first-child { margin-right: auto; }
:deep(.vue-flow__edge-text) { font-size: 9px; font-weight: 600; }
:deep(.vue-flow__edge-textbg) { fill: var(--ns-bg); }
:deep(.vue-flow__minimap) { background: var(--ns-bg-subtle); border: 1px solid var(--ns-border); }
.soar-v2-panel-title { color: var(--ns-text-2); font-size: 11px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; margin-bottom: 9px; }
.soar-v2-editor-lower { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(220px, .7fr) minmax(220px, .8fr); gap: 10px; margin-top: 10px; }
.soar-v2-json-panel, .soar-v2-validation-panel { min-width: 0; padding: 10px; border: 1px solid var(--ns-border); border-radius: 6px; background: var(--ns-bg-subtle); }
.soar-v2-result { max-height: 190px; overflow: auto; margin: 7px 0 0; padding: 8px; border-radius: 4px; background: var(--ns-bg-inset); color: var(--ns-text-2); font-size: 10px; white-space: pre-wrap; }
.soar-v2-issue { margin: 0 -2px 7px; padding: 6px 7px; border-left: 3px solid var(--ns-danger); background: color-mix(in srgb, var(--ns-danger) 7%, transparent); font-size: 10px; cursor: pointer; }
.soar-v2-issue:hover { outline: 1px solid var(--ns-border); }
.soar-v2-issue.warning { border-left-color: var(--ns-warning); background: color-mix(in srgb, var(--ns-warning) 8%, transparent); }
.soar-v2-issue b, .soar-v2-issue span { margin-right: 5px; }
.soar-v2-issue span { color: var(--ns-text-3); }
.soar-v2-issue p { margin: 3px 0 0; color: var(--ns-text-2); }
.soar-v2-hash { color: var(--ns-text-3); font-family: ui-monospace, monospace; font-size: 9px; overflow-wrap: anywhere; }
@media (max-width: 1100px) {
  .soar-v2-editor-body { grid-template-columns: 155px minmax(520px, 1fr); }
  :deep(.soar-flow-inspector) { grid-column: 1 / -1; border-top: 1px solid var(--ns-border); border-left: 0; }
  .soar-v2-editor-lower { grid-template-columns: 1fr 1fr; }
  .soar-v2-validation-panel { grid-column: 1 / -1; }
}
@media (max-width: 720px) {
  .soar-v2-editor-header { align-items: flex-start; flex-direction: column; }
  .soar-v2-editor-body { display: block; }
  :deep(.soar-flow-palette) { border-right: 0; border-bottom: 1px solid var(--ns-border); display: grid; grid-template-columns: repeat(2, 1fr); gap: 3px; }
  :deep(.soar-flow-palette .soar-v2-panel-title),
  :deep(.soar-flow-palette .soar-flow-connect-help) { grid-column: 1 / -1; }
  :deep(.soar-flow-inspector) { border-left: 0; border-top: 1px solid var(--ns-border); }
  .soar-v2-editor-lower { display: block; }
  .soar-v2-json-panel, .soar-v2-validation-panel { margin-top: 10px; }
}
</style>
