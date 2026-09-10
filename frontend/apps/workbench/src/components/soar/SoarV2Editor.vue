<script setup lang="ts">
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
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
  getV2Playbook,
  getV2Version,
  listV2Playbooks,
  listV2Versions,
  queueV2Run,
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
import { summarizeRunHighlights, type RunHighlightRow, type RunOpenRequest, type RunStatusTone } from './editor/runHighlight'
import { PALETTE_DATA_TYPE } from './editor/types'
import type { EditorNode, ValidationIssue, ValidationResult } from './editor/types'
import { useI18n } from '../../composables/useI18n'

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

/** Legend label key per run-status tone (both locale packs ship these keys). */
const RUN_TONE_KEY: Record<RunStatusTone, string> = {
  succeeded: 'soarV2.runTone.succeeded',
  failed: 'soarV2.runTone.failed',
  unknown: 'soarV2.runTone.unknown',
  timeout: 'soarV2.runTone.timeout',
  running: 'soarV2.runTone.running',
  waiting: 'soarV2.runTone.waiting',
  cancelled: 'soarV2.runTone.cancelled',
  suppressed: 'soarV2.runTone.suppressed',
}

const props = withDefaults(defineProps<{
  initialPlaybookId?: string
  /** External "open this run in the editor" request from the run inspector. */
  openRun?: RunOpenRequest | null
  /** Incrementing token used by the page-level Create Playbook action. */
  createRequest?: number
  /** Optional alert context passed from the alarm workbench. */
  contextAlarmId?: string
}>(), { initialPlaybookId: '', openRun: null, createRequest: 0, contextAlarmId: '' })
const emit = defineEmits<{ saved: [SoarV2Version]; created: [id: string]; 'dirty-change': [dirty: boolean] }>()

const { t } = useI18n()

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
const dryRunText = ref('')
const dryRunResult = ref<JsonObject | null>(null)
const loading = ref(false)
const saving = ref(false)
const runBusy = ref(false)
const message = ref('')
const errorMessage = ref('')
/** Token of the openRun request already handled (guards re-runs/re-mounts). */
const handledOpenRunToken = ref<string | null>(null)
const handledCreateRequest = ref(0)
const newPlaybookVisible = ref(false)
const newPlaybookSaving = ref(false)
const newPlaybookError = ref('')
const newPlaybookForm = ref({ name: '', description: '', tags: '' })

function defaultDryRunInput(): JsonObject {
  return {
    eventId: props.contextAlarmId || 'sample-alert-1',
    eventType: 'alert.created',
    severity: 'HIGH',
    ...(props.contextAlarmId ? { alarmId: props.contextAlarmId } : {}),
  }
}

function resetDryRunInput(): void {
  dryRunText.value = JSON.stringify(defaultDryRunInput(), null, 2)
}

const runLegendEntries = computed(() => summarizeRunHighlights(flow.runHighlights.value))

const selectedVersion = computed(() => versions.value.find(version => version.version === selectedVersionNo.value))
const isDraft = computed(() => selectedVersion.value?.status === 'DRAFT')
const hasUnsavedChanges = computed(() => flow.dirty.value)
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

async function loadVersions(preferVersion?: number) {
  if (!selectedPlaybookId.value) return
  const result = await listV2Versions(selectedPlaybookId.value)
  versions.value = result
  const requested = preferVersion !== undefined ? result.find(version => version.version === preferVersion) : undefined
  const draft = result.find(version => version.status === 'DRAFT')
  const target = requested ?? draft ?? result[0]
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

/* ---------------- run-path highlight (Slice 4) ---------------- */

function clearRunHighlights(): void {
  flow.applyRunHighlights(null)
}

/**
 * Opens the exact immutable version a run executed, then overlays that run's
 * node statuses. Used by the run inspector's "open in visual editor" path and
 * by any caller that can load a known playbook version.
 */
async function handleOpenRunRequest(request: RunOpenRequest): Promise<void> {
  if (request.token === handledOpenRunToken.value) return
  handledOpenRunToken.value = request.token
  loading.value = true
  errorMessage.value = ''
  try {
    let catalog = playbooks.value
    if (!catalog.some(playbook => playbook.id === request.playbookId)) {
      try {
        catalog = (await listV2Playbooks(0, 100)).items
      } catch {
        catalog = []
      }
      if (!catalog.some(playbook => playbook.id === request.playbookId)) {
        const single = await getV2Playbook(request.playbookId)
        catalog = catalog.filter(playbook => playbook.id !== single.id)
        catalog = [single, ...catalog]
      }
      if (handledOpenRunToken.value !== request.token) return
      playbooks.value = catalog
    }
    if (selectedPlaybookId.value !== request.playbookId) selectedPlaybookId.value = request.playbookId
    await loadVersions(request.version)
    if (handledOpenRunToken.value !== request.token) return
    if (selectedVersionNo.value !== request.version) {
      handledOpenRunToken.value = ''
      errorMessage.value = `Run version v${request.version} is not available for ${request.playbookId}`
      return
    }
    flow.applyRunHighlights(request.rows)
    message.value = `Loaded run path v${request.version}`
  } catch (failure) {
    handledOpenRunToken.value = ''
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to open run in the editor'
  } finally {
    loading.value = false
  }
}

function openNewPlaybookDialog(): void {
  if (!discardGuard()) return
  newPlaybookError.value = ''
  newPlaybookForm.value = { name: '', description: '', tags: '' }
  newPlaybookVisible.value = true
}

async function createPlaybookAndVersion() {
  if (!discardGuard()) return
  const name = newPlaybookForm.value.name.trim()
  if (!name) {
    newPlaybookError.value = t('soarV2.playbookNameRequired')
    return
  }
  newPlaybookSaving.value = true
  newPlaybookError.value = ''
  loading.value = true
  try {
    const description = newPlaybookForm.value.description.trim() || undefined
    const tags = newPlaybookForm.value.tags.split(/[,，\n]/).map(tag => tag.trim()).filter(Boolean)
    const playbook = await createV2Playbook({ name, description, tags })
    const version = await createV2Version(playbook.id)
    playbooks.value = [playbook, ...playbooks.value.filter(item => item.id !== playbook.id)]
    selectedPlaybookId.value = playbook.id
    versions.value = [version]
    selectedVersionNo.value = version.version
    rowVersion.value = version.rowVersion
    flow.applyDefinition(version.definition)
    validation.value = null
    newPlaybookVisible.value = false
    emit('created', playbook.id)
    message.value = t('soarV2.createdDraft')
  } catch (failure) {
    newPlaybookError.value = failure instanceof Error ? failure.message : t('soarV2.createFailed')
  } finally {
    loading.value = false
    newPlaybookSaving.value = false
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
    dryRunResult.value = await dryRunV2Version(selectedPlaybookId.value, selectedVersionNo.value, contextSubject(), inputs) as JsonObject
    errorMessage.value = ''
  } catch (failure) {
    errorMessage.value = `Dry-run failed: ${failure instanceof Error ? failure.message : 'invalid input'}`
  }
}

function contextSubject(): JsonObject {
  return props.contextAlarmId ? { alarmId: props.contextAlarmId } : {}
}

async function queueRun(): Promise<void> {
  const version = selectedVersion.value
  if (!version || version.status !== 'PUBLISHED' || runBusy.value) return
  runBusy.value = true
  errorMessage.value = ''
  try {
    const inputs = JSON.parse(dryRunText.value || '{}') as JsonObject
    const requestId = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `workbench-${Date.now()}`
    const result = await queueV2Run({
      requestId,
      playbookVersionId: version.id,
      subject: contextSubject(),
      inputs,
    })
    message.value = `${t('soarV2.runQueued')} ${result.runId}`
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : t('soarV2.runQueueFailed')
  } finally {
    runBusy.value = false
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
  const modified = event.ctrlKey || event.metaKey
  const key = event.key.toLowerCase()
  if (modified && key === 'z') {
    event.preventDefault()
    if (event.shiftKey) flow.redo()
    else flow.undo()
    return
  }
  if (modified && key === 'y') {
    event.preventDefault()
    flow.redo()
    return
  }
  if (modified && key === 'c') {
    event.preventDefault()
    flow.copySelection()
    return
  }
  if (modified && key === 'x') {
    event.preventDefault()
    flow.cutSelection()
    return
  }
  if (modified && key === 'v') {
    event.preventDefault()
    flow.pasteSelection()
    return
  }
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

watch(() => props.openRun, (request) => {
  if (request && request.token !== handledOpenRunToken.value) void handleOpenRunRequest(request)
})

watch(() => props.createRequest, (request) => {
  if (request && request !== handledCreateRequest.value) {
    handledCreateRequest.value = request
    openNewPlaybookDialog()
  }
})

watch(() => flow.dirty.value, (dirty) => {
  emit('dirty-change', dirty)
})

defineExpose({
  hasUnsavedChanges,
  applyRunHighlights: (rows: readonly RunHighlightRow[] | null | undefined) => flow.applyRunHighlights(rows),
})

onMounted(() => {
  resetDryRunInput()
  document.addEventListener('keydown', onKeyDown)
  if (props.openRun && props.openRun.token !== handledOpenRunToken.value) {
    void handleOpenRunRequest(props.openRun)
  } else {
    void loadCatalog()
  }
  if (props.createRequest && props.createRequest !== handledCreateRequest.value) {
    handledCreateRequest.value = props.createRequest
    openNewPlaybookDialog()
  }
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
          <strong>{{ t('soarV2.editorTitle') }}</strong>
          <span class="soar-v2-subtitle">{{ t('soarV2.editorSubtitle') }}</span>
          <span v-if="props.contextAlarmId" class="soar-v2-context-note">{{ t('soarV2.contextAlarm') }} {{ props.contextAlarmId }}</span>
        </div>
        <div class="soar-v2-editor-selects">
          <el-select v-model="selectedPlaybookId" filterable aria-label="Playbook" @change="changePlaybook">
            <el-option value="" label="Select playbook" />
            <el-option v-for="playbook in playbooks" :key="playbook.id" :value="playbook.id" :label="playbook.name" />
          </el-select>
          <el-select v-model="selectedVersionNo" aria-label="Version" @change="changeVersion">

            <el-option v-for="version in versions" :key="version.id" :value="version.version" :label="`v${version.version} · ${version.status}`" />
          </el-select>
        </div>
      </div>
    </template>

    <div class="soar-v2-editor-toolbar">
      <el-button v-if="!selectedPlaybookId" type="primary" size="small" @click="openNewPlaybookDialog">{{ t('soarV2.blankPlaybook') }}</el-button>
      <el-button size="small" :disabled="!selectedPlaybookId" @click="createVersion">{{ t('soarV2.newDraftVersion') }}</el-button>
      <el-button size="small" :loading="loading" @click="loadCatalog">{{ t('common.refresh') }}</el-button>
      <el-button
        size="small"
        :disabled="!flow.canUndo.value"
        :title="t('soarV2.editorUndoHint')"
        @click="flow.undo()"
      >{{ t('soarV2.editorUndo') }}</el-button>
      <el-button
        size="small"
        :disabled="!flow.canRedo.value"
        :title="t('soarV2.editorRedoHint')"
        @click="flow.redo()"
      >{{ t('soarV2.editorRedo') }}</el-button>
      <el-button
        size="small"
        :title="t('soarV2.editorAutoLayoutHint')"
        @click="flow.autoLayout()"
      >{{ t('soarV2.editorAutoLayout') }}</el-button>
      <span class="soar-v2-toolbar-spacer" />
      <el-tag v-if="selectedVersion" size="small" :type="isDraft ? 'warning' : 'success'">v{{ selectedVersion.version }} · {{ selectedVersion.status }}</el-tag>
      <el-tag v-if="validation" size="small" :type="flow.validationStale.value ? 'info' : validation.valid ? 'success' : 'danger'">
        {{ flow.validationStale.value ? 'OUTDATED' : validation.valid ? 'VALID' : 'INVALID' }}{{ issueCount ? ` · ${issueCount}` : '' }}
      </el-tag>
      <el-button size="small" @click="validate" :disabled="!selectedVersionNo">{{ t('soarV2.validate') }}</el-button>
      <el-button size="small" @click="dryRun" :disabled="!selectedVersionNo">{{ t('soarV2.dryRun') }}</el-button>
      <el-button size="small" type="warning" plain :loading="runBusy" :disabled="selectedVersion?.status !== 'PUBLISHED'" @click="queueRun">{{ t('soarV2.queueRun') }}</el-button>
      <el-button
        size="small"
        :type="hasUnsavedChanges ? 'primary' : 'default'"
        :loading="saving"
        :disabled="!isDraft || !hasUnsavedChanges"
        @click="save"
      >{{ t('soarV2.saveDraft') }}</el-button>
      <el-button size="small" type="success" @click="publish" :disabled="!isDraft">{{ t('soarV2.publish') }}</el-button>
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
          <div v-if="flow.hasRunHighlights.value" class="soar-run-highlight-legend">
            <span class="soar-run-legend-title">{{ t('soarV2.runHighlightLegend') }}</span>
            <span
              v-for="entry in runLegendEntries"
              :key="entry.tone"
              class="soar-run-legend-entry"
              :class="`run-${entry.tone}`"
              :title="entry.statuses.join(', ')"
            >
              <i class="soar-run-legend-dot" />
              <span>{{ t(RUN_TONE_KEY[entry.tone]) }} · {{ entry.count }}</span>
            </span>
            <button type="button" class="soar-run-legend-clear" @click="clearRunHighlights">{{ t('soarV2.runHighlightClear') }}</button>
          </div>
        </div>
        <div class="soar-v2-canvas-footer">
          <span>{{ flow.nodeCount.value }} nodes · {{ flow.edgeCount.value }} edges</span>
          <span v-if="flowSelectionCount">{{ flowSelectionCount }} selected</span>
          <el-button size="small" type="danger" plain :disabled="!selectedRawNode || selectedRawNode.type === 'START'" @click="flow.removeSelected()">Remove selected</el-button>
        </div>
      </section>

      <SoarFlowPropertyPanel :flow="flow" :node="selectedRawNode" />
    </div>

    <el-dialog v-model="newPlaybookVisible" :title="t('soarV2.createBlankTitle')" width="520px">
      <p class="soar-v2-dialog-hint">{{ t('soarV2.createBlankHint') }}</p>
      <el-form label-position="top">
        <el-form-item :label="t('common.name')" required><el-input v-model="newPlaybookForm.name" :placeholder="t('soarV2.playbookNamePlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.description')"><el-input v-model="newPlaybookForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item :label="t('soarV2.tags')"><el-input v-model="newPlaybookForm.tags" :placeholder="t('soarV2.tagsPlaceholder')" /></el-form-item>
      </el-form>
      <div v-if="newPlaybookError" class="soar-v2-editor-error">{{ newPlaybookError }}</div>
      <template #footer><el-button @click="newPlaybookVisible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="newPlaybookSaving" @click="createPlaybookAndVersion">{{ t('soarV2.openCanvas') }}</el-button></template>
    </el-dialog>

    <div class="soar-v2-editor-lower">
      <div class="soar-v2-json-panel">
        <div class="soar-v2-panel-title">Definition JSON · advanced import/export</div>
        <el-input type="textarea" v-model="definitionText" :rows="12" spellcheck="false" aria-label="Definition JSON"  />
        <el-button size="small" @click="applyDefinitionJson">Apply JSON</el-button>
      </div>
      <div class="soar-v2-json-panel">
        <div class="soar-v2-panel-title">Dry-run input</div>
        <el-input type="textarea" v-model="dryRunText" :rows="5" spellcheck="false" aria-label="Dry-run input"  />
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
.soar-v2-dialog-hint { margin: 0 0 14px; color: var(--ns-text-2); font-size: 12px; line-height: 1.5; }
.soar-v2-editor-body { display: grid; grid-template-columns: 180px minmax(560px, 1fr) 260px; min-height: 570px; border: 1px solid var(--ns-border); border-radius: 6px; overflow: hidden; }
.soar-v2-canvas-panel { display: flex; min-width: 0; flex-direction: column; background: var(--ns-bg); }
.soar-v2-canvas { position: relative; min-height: 540px; flex: 1; background: var(--ns-bg); }
.soar-v2-canvas .vue-flow { height: 540px; }
.soar-run-highlight-legend {
  position: absolute;
  z-index: 6;
  top: 10px;
  left: 10px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  max-width: 80%;
  box-sizing: border-box;
  padding: 6px 9px;
  border: 1px solid var(--ns-border);
  border-radius: 6px;
  background: var(--ns-bg-subtle);
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.12);
  font-size: 10px;
}
.soar-run-legend-title {
  margin-right: 2px;
  color: var(--ns-text-3);
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}
.soar-run-legend-entry { display: inline-flex; align-items: center; gap: 4px; color: var(--ns-text-2); }
.soar-run-legend-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: var(--run-status-color, var(--ns-accent)); }
.soar-run-legend-entry.run-succeeded { --run-status-color: var(--ns-success); }
.soar-run-legend-entry.run-failed { --run-status-color: var(--ns-danger); }
.soar-run-legend-entry.run-unknown { --run-status-color: var(--ns-warning); }
.soar-run-legend-entry.run-timeout { --run-status-color: #ea580c; }
.soar-run-legend-entry.run-running { --run-status-color: var(--ns-accent); }
.soar-run-legend-entry.run-waiting { --run-status-color: #d97706; }
.soar-run-legend-entry.run-cancelled { --run-status-color: var(--ns-info); }
.soar-run-legend-entry.run-suppressed { --run-status-color: var(--ns-text-3); }
.soar-run-legend-clear {
  margin-left: 4px;
  padding: 1px 6px;
  border: 1px solid var(--ns-border);
  border-radius: 4px;
  background: var(--ns-bg);
  color: var(--ns-text-2);
  cursor: pointer;
  font: inherit;
  font-size: 10px;
}
.soar-run-legend-clear:hover { color: var(--ns-danger); border-color: var(--ns-danger); }
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
