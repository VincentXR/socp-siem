<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
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

type JsonObject = Record<string, unknown>
type NodeType = typeof NODE_TYPES[number]['type']

interface EditorNode extends JsonObject {
  id: string
  type: NodeType | string
  name?: string
}

interface EditorEdge extends JsonObject {
  from: string
  to: string
  port?: string
}

interface EditorDefinition extends JsonObject {
  schemaVersion: string
  entryNodeId: string
  nodes: EditorNode[]
  edges: EditorEdge[]
  limits?: JsonObject
}

interface Position { x: number; y: number }
interface ValidationIssue { code?: string; nodeId?: string; path?: string; message?: string }
interface ValidationResult { valid?: boolean; errors?: ValidationIssue[]; warnings?: ValidationIssue[]; definitionHash?: string; riskSummary?: JsonObject }

const NODE_TYPES = [
  { type: 'START', label: 'Start', tone: 'start', description: 'Workflow entry' },
  { type: 'END', label: 'End', tone: 'end', description: 'Terminal outcome' },
  { type: 'ACTION', label: 'Action', tone: 'action', description: 'Connector operation' },
  { type: 'CONDITION', label: 'Condition', tone: 'logic', description: 'Safe expression branch' },
  { type: 'SWITCH', label: 'Switch', tone: 'logic', description: 'Case/default branch' },
  { type: 'PARALLEL', label: 'Parallel', tone: 'control', description: 'Fan-out to branches' },
  { type: 'JOIN', label: 'Join', tone: 'control', description: 'Deterministic fan-in' },
  { type: 'FOREACH', label: 'For each', tone: 'control', description: 'Bounded collection loop' },
  { type: 'DELAY', label: 'Delay', tone: 'wait', description: 'Temporal timer' },
  { type: 'APPROVAL', label: 'Approval', tone: 'human', description: 'Durable human gate' },
  { type: 'MANUAL_TASK', label: 'Manual task', tone: 'human', description: 'Structured analyst input' },
  { type: 'SUB_PLAYBOOK', label: 'Sub-playbook', tone: 'control', description: 'Published child version' },
  { type: 'SET_VARIABLE', label: 'Set variable', tone: 'data', description: 'Write vars.* only' },
] as const

const props = withDefaults(defineProps<{ initialPlaybookId?: string }>(), { initialPlaybookId: '' })
const emit = defineEmits<{ saved: [SoarV2Version] }>()

const playbooks = ref<SoarV2Playbook[]>([])
const versions = ref<SoarV2Version[]>([])
const selectedPlaybookId = ref(props.initialPlaybookId)
const selectedVersionNo = ref<number | null>(null)
const definition = ref<EditorDefinition>(emptyDefinition())
const definitionText = ref('')
const nodeConfigText = ref('{}')
const nodePositions = ref<Record<string, Position>>({})
const selectedNodeId = ref('start')
const connectFromId = ref('')
const rowVersion = ref<number | undefined>()
const validation = ref<ValidationResult | null>(null)
const dryRunText = ref('{\n  "eventId": "sample-alert-1",\n  "eventType": "alert.created",\n  "severity": "HIGH"\n}')
const dryRunResult = ref<JsonObject | null>(null)
const loading = ref(false)
const saving = ref(false)
const message = ref('')
const errorMessage = ref('')
const canvas = ref<HTMLDivElement | null>(null)
let drag: { id: string; dx: number; dy: number } | null = null

const selectedNode = computed(() => definition.value.nodes.find(node => node.id === selectedNodeId.value))
const selectedVersion = computed(() => versions.value.find(version => version.version === selectedVersionNo.value))
const isDraft = computed(() => selectedVersion.value?.status === 'DRAFT')
const issueCount = computed(() => (validation.value?.errors?.length ?? 0) + (validation.value?.warnings?.length ?? 0))
const palette = computed(() => NODE_TYPES)
const renderedEdges = computed(() => definition.value.edges.map((edge, index) => {
  const from = nodePositions.value[edge.from] ?? fallbackPosition(index)
  const to = nodePositions.value[edge.to] ?? fallbackPosition(index + 1)
  return {
    ...edge,
    id: `${edge.from}-${edge.to}-${index}`,
    x1: from.x + 94,
    y1: from.y + 38,
    x2: to.x,
    y2: to.y + 38,
  }
}))

function emptyDefinition(): EditorDefinition {
  return {
    schemaVersion: 'soar.playbook/v2',
    entryNodeId: 'start',
    nodes: [
      { id: 'start', type: 'START', name: 'Start' },
      { id: 'end', type: 'END', name: 'Done', outcome: 'SUCCEEDED' },
    ],
    edges: [{ from: 'start', to: 'end' }],
    limits: { maxNodeExecutions: 500, maxParallelism: 10 },
  }
}

function fallbackPosition(index: number): Position {
  return { x: 45 + (index % 3) * 245, y: 42 + Math.floor(index / 3) * 112 }
}

function normalizeDefinition(value: unknown): EditorDefinition {
  const candidate = value && typeof value === 'object' ? value as JsonObject : {}
  const rawNodes = Array.isArray(candidate.nodes) ? candidate.nodes : []
  const nodes: EditorNode[] = rawNodes
    .filter(item => item && typeof item === 'object')
    .map(item => {
      const node = item as JsonObject
      return { ...node, id: String(node.id ?? ''), type: String(node.type ?? 'ACTION').toUpperCase() }
    })
    .filter(node => node.id)
  const rawEdges = Array.isArray(candidate.edges) ? candidate.edges : []
  const edges: EditorEdge[] = rawEdges
    .filter(item => item && typeof item === 'object')
    .map(item => {
      const edge = item as JsonObject
      const port = edge.port ?? edge.when
      return { from: String(edge.from ?? edge.source ?? ''), to: String(edge.to ?? edge.target ?? ''), ...(port ? { port: String(port) } : {}) }
    })
    .filter(edge => edge.from && edge.to)
  return {
    ...candidate,
    schemaVersion: String(candidate.schemaVersion ?? 'soar.playbook/v2'),
    entryNodeId: String(candidate.entryNodeId ?? 'start'),
    nodes: nodes.length ? nodes : emptyDefinition().nodes,
    edges,
  }
}

function syncDefinitionText() {
  definitionText.value = JSON.stringify(definition.value, null, 2)
  syncNodeConfigText()
}

function syncNodeConfigText() {
  const node = selectedNode.value
  if (!node) { nodeConfigText.value = '{}'; return }
  const config: JsonObject = {}
  for (const [key, value] of Object.entries(node)) {
    if (!['id', 'type', 'name'].includes(key)) config[key] = value
  }
  nodeConfigText.value = JSON.stringify(config, null, 2)
}

function applyDefinition(value: unknown, layout?: unknown) {
  definition.value = normalizeDefinition(value)
  nodePositions.value = {}
  const layoutObject = layout && typeof layout === 'object' ? layout as JsonObject : {}
  const layoutNodes = Array.isArray(layoutObject.nodes) ? layoutObject.nodes : []
  for (const item of layoutNodes) {
    if (!item || typeof item !== 'object') continue
    const row = item as JsonObject
    const id = String(row.id ?? '')
    if (!id) continue
    nodePositions.value[id] = { x: numberValue(row.x, 0), y: numberValue(row.y, 0) }
  }
  definition.value.nodes.forEach((node, index) => {
    if (!nodePositions.value[node.id]) nodePositions.value[node.id] = fallbackPosition(index)
  })
  if (!definition.value.nodes.some(node => node.id === selectedNodeId.value)) selectedNodeId.value = definition.value.nodes[0]?.id ?? ''
  syncDefinitionText()
}

function numberValue(value: unknown, fallback: number) {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? Math.max(0, Math.min(880, parsed)) : fallback
}

function layoutPayload(): JsonObject {
  return { nodes: Object.entries(nodePositions.value).map(([id, position]) => ({ id, x: Math.round(position.x), y: Math.round(position.y) })) }
}

async function loadCatalog() {
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
    else applyDefinition(emptyDefinition())
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
    applyDefinition(result.definition, result.layout)
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
    applyDefinition(version.definition)
    message.value = 'Created a new draft'
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to create playbook'
  } finally {
    loading.value = false
  }
}

async function createVersion() {
  if (!selectedPlaybookId.value) return
  loading.value = true
  try {
    const result = await createV2Version(selectedPlaybookId.value)
    versions.value = [result, ...versions.value.filter(item => item.version !== result.version)]
    selectedVersionNo.value = result.version
    rowVersion.value = result.rowVersion
    applyDefinition(result.definition)
    message.value = `Created draft v${result.version}`
  } catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : 'Unable to create version'
  } finally {
    loading.value = false
  }
}

function selectNode(id: string) {
  if (connectFromId.value && connectFromId.value !== id) {
    addEdge(connectFromId.value, id)
    connectFromId.value = ''
  }
  selectedNodeId.value = id
  syncNodeConfigText()
}

function addNode(type: NodeType) {
  if (type === 'START' && definition.value.nodes.some(node => node.type === 'START')) {
    errorMessage.value = 'A definition can contain only one START node'
    return
  }
  const base = type.toLowerCase().replace('_', '-')
  let id = base
  let index = 2
  while (definition.value.nodes.some(node => node.id === id)) id = `${base}-${index++}`
  const node = defaultNode(id, type)
  definition.value.nodes.push(node)
  nodePositions.value[id] = fallbackPosition(definition.value.nodes.length - 1)
  const source = selectedNodeId.value && definition.value.nodes.some(item => item.id === selectedNodeId.value)
    ? selectedNodeId.value : definition.value.nodes.at(-2)?.id
  if (source && source !== id && !['END'].includes(definition.value.nodes.find(item => item.id === source)?.type ?? '')) {
    addEdge(source, id)
  }
  selectedNodeId.value = id
  syncDefinitionText()
}

function defaultNode(id: string, type: NodeType): EditorNode {
  const label = NODE_TYPES.find(item => item.type === type)?.label ?? type
  const node: EditorNode = { id, type, name: label }
  if (type === 'ACTION') Object.assign(node, { actionRef: 'socp.alert/get@1', parameters: {}, target: {} })
  if (type === 'CONDITION') node.expression = "trigger.severity == 'HIGH'"
  if (type === 'SWITCH') Object.assign(node, { expression: 'trigger.severity', cases: [], config: { defaultPort: 'default' } })
  if (type === 'JOIN') node.strategy = 'ALL_SUCCESS'
  if (type === 'FOREACH') Object.assign(node, { config: { itemsPath: 'vars.items' }, limits: { maxItems: 100, concurrency: 1 } })
  if (type === 'DELAY') node.config = { durationSeconds: 60 }
  if (type === 'APPROVAL') node.config = { timeoutSeconds: 24 * 3600 }
  if (type === 'MANUAL_TASK') Object.assign(node, {
    formSchema: { type: 'object', properties: {} },
    config: { timeoutSeconds: 24 * 3600 },
  })
  if (type === 'SUB_PLAYBOOK') node.playbookVersionId = ''
  if (type === 'SET_VARIABLE') node.config = { name: `vars.${id.replace(/-/g, '_')}`, value: { $expr: 'event' } }
  if (type === 'END') node.outcome = 'SUCCEEDED'
  return node
}

function removeSelectedNode() {
  const node = selectedNode.value
  if (!node) return
  if (node.type === 'START') {
    errorMessage.value = 'START cannot be removed; replace it instead'
    return
  }
  definition.value.nodes = definition.value.nodes.filter(item => item.id !== node.id)
  definition.value.edges = definition.value.edges.filter(edge => edge.from !== node.id && edge.to !== node.id)
  delete nodePositions.value[node.id]
  selectedNodeId.value = definition.value.nodes[0]?.id ?? ''
  syncDefinitionText()
}

function addEdge(from: string, to: string, port?: string) {
  if (!from || !to || from === to || definition.value.edges.some(edge => edge.from === from && edge.to === to && edge.port === port)) return
  definition.value.edges.push({ from, to, ...(port ? { port } : {}) })
  syncDefinitionText()
}

function startConnect() {
  if (!selectedNode.value) return
  connectFromId.value = selectedNode.value.id
  message.value = `Select the target node for ${selectedNode.value.id}`
}

function updateNodeField(field: string, value: string) {
  const node = selectedNode.value
  if (!node) return
  if (value.trim()) node[field] = value
  else delete node[field]
  syncDefinitionText()
}

function updateNodeType(value: string) {
  const node = selectedNode.value
  if (!node || !NODE_TYPES.some(item => item.type === value)) return
  if (value === 'START' && definition.value.nodes.some(item => item.id !== node.id && item.type === 'START')) {
    errorMessage.value = 'A definition can contain only one START node'
    return
  }
  node.type = value
  syncDefinitionText()
}

function nodeTimingValue(node: EditorNode): number | string {
  const config = node.config && typeof node.config === 'object' ? node.config as JsonObject : {}
  const field = node.type === 'DELAY' ? 'durationSeconds' : 'timeoutSeconds'
  const value = config[field] ?? node[field]
  return typeof value === 'number' || typeof value === 'string' ? value : ''
}

function updateNodeTiming(value: string) {
  const node = selectedNode.value
  if (!node) return
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed < 0 || !Number.isInteger(parsed)) {
    errorMessage.value = 'Timing values must be a non-negative integer number of seconds'
    return
  }
  const config = node.config && typeof node.config === 'object' ? node.config as JsonObject : {}
  const field = node.type === 'DELAY' ? 'durationSeconds' : 'timeoutSeconds'
  node.config = { ...config, [field]: Math.min(parsed, node.type === 'APPROVAL' ? 7 * 24 * 3600 : node.type === 'MANUAL_TASK' ? 30 * 24 * 3600 : 86400) }
  errorMessage.value = ''
  syncDefinitionText()
}

function applyNodeConfig() {
  const node = selectedNode.value
  if (!node) return
  try {
    const parsed = JSON.parse(nodeConfigText.value) as JsonObject
    for (const key of Object.keys(node)) if (!['id', 'type', 'name'].includes(key)) delete node[key]
    Object.assign(node, parsed)
    syncDefinitionText()
    errorMessage.value = ''
  } catch (failure) {
    errorMessage.value = `Node JSON is invalid: ${failure instanceof Error ? failure.message : 'invalid JSON'}`
  }
}

function applyDefinitionJson() {
  try {
    const parsed = JSON.parse(definitionText.value)
    applyDefinition(parsed)
    validation.value = null
    errorMessage.value = ''
    message.value = 'Definition applied to the editor'
  } catch (failure) {
    errorMessage.value = `Definition JSON is invalid: ${failure instanceof Error ? failure.message : 'invalid JSON'}`
  }
}

function beginDrag(event: PointerEvent, node: EditorNode) {
  if (event.button !== 0 || !canvas.value) return
  const position = nodePositions.value[node.id] ?? { x: 0, y: 0 }
  const rect = canvas.value.getBoundingClientRect()
  drag = { id: node.id, dx: event.clientX - rect.left - position.x, dy: event.clientY - rect.top - position.y }
  window.addEventListener('pointermove', moveDrag)
  window.addEventListener('pointerup', endDrag, { once: true })
}

function moveDrag(event: PointerEvent) {
  if (!drag || !canvas.value) return
  const rect = canvas.value.getBoundingClientRect()
  nodePositions.value[drag.id] = {
    x: Math.max(8, Math.min(890, event.clientX - rect.left - drag.dx)),
    y: Math.max(8, Math.min(490, event.clientY - rect.top - drag.dy)),
  }
}

function endDrag() {
  drag = null
  window.removeEventListener('pointermove', moveDrag)
}

async function save() {
  if (!selectedPlaybookId.value || !selectedVersionNo.value || !isDraft.value) return
  saving.value = true
  errorMessage.value = ''
  try {
    const result = await saveV2Version(selectedPlaybookId.value, selectedVersionNo.value,
      definition.value, layoutPayload(), rowVersion.value)
    rowVersion.value = result.rowVersion
    versions.value = versions.value.map(item => item.version === result.version ? result : item)
    applyDefinition(result.definition, result.layout)
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
    validation.value = await validateV2Version(selectedPlaybookId.value, selectedVersionNo.value) as ValidationResult
    errorMessage.value = ''
    message.value = validation.value.valid ? 'Definition is publishable' : 'Definition needs attention'
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

watch(selectedNodeId, syncNodeConfigText)
watch(() => props.initialPlaybookId, value => {
  if (value && value !== selectedPlaybookId.value) {
    selectedPlaybookId.value = value
    void loadVersions()
  }
})

onMounted(() => { void loadCatalog() })
onUnmounted(() => { endDrag() })
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
          <select v-model="selectedPlaybookId" aria-label="V2 playbook" @change="loadVersions">
            <option value="">Select playbook</option>
            <option v-for="playbook in playbooks" :key="playbook.id" :value="playbook.id">{{ playbook.name }}</option>
          </select>
          <select v-model.number="selectedVersionNo" aria-label="V2 version" @change="loadVersion()">
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
      <el-tag v-if="validation" size="small" :type="validation.valid ? 'success' : 'danger'">{{ validation.valid ? 'VALID' : 'INVALID' }}{{ issueCount ? ` · ${issueCount}` : '' }}</el-tag>
      <el-button size="small" @click="validate" :disabled="!selectedVersionNo">Validate</el-button>
      <el-button size="small" @click="dryRun" :disabled="!selectedVersionNo">Dry-run</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="save" :disabled="!isDraft">Save draft</el-button>
      <el-button size="small" type="success" @click="publish" :disabled="!isDraft">Publish</el-button>
    </div>

    <div v-if="message" class="soar-v2-editor-message">{{ message }}</div>
    <div v-if="errorMessage" class="soar-v2-editor-error">{{ errorMessage }}</div>

    <div class="soar-v2-editor-body">
      <aside class="soar-v2-palette" aria-label="Node palette">
        <div class="soar-v2-panel-title">Node palette</div>
        <button v-for="item in palette" :key="item.type" type="button" class="soar-v2-palette-item" :class="`tone-${item.tone}`" @click="addNode(item.type)">
          <span class="soar-v2-palette-dot" />
          <span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
        </button>
        <div class="soar-v2-connect-help">Click a node, then <b>Connect</b>, then click its target. Drag nodes to arrange the review layout.</div>
      </aside>

      <section class="soar-v2-canvas-panel" aria-label="Playbook graph">
        <div ref="canvas" class="soar-v2-canvas">
          <svg class="soar-v2-edges" viewBox="0 0 960 540" preserveAspectRatio="none" aria-hidden="true">
            <defs><marker id="soar-v2-arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L8,3 L0,6 z" fill="currentColor" /></marker></defs>
            <line v-for="edge in renderedEdges" :key="edge.id" :x1="edge.x1" :y1="edge.y1" :x2="edge.x2" :y2="edge.y2" marker-end="url(#soar-v2-arrow)" />
          </svg>
          <button v-for="node in definition.nodes" :key="node.id" type="button" class="soar-v2-flow-node" :class="[{ selected: selectedNodeId === node.id, connecting: connectFromId === node.id }, `node-${String(node.type).toLowerCase()}`]" :style="{ left: `${nodePositions[node.id]?.x ?? 20}px`, top: `${nodePositions[node.id]?.y ?? 20}px` }" @pointerdown="beginDrag($event, node)" @click="selectNode(node.id)">
            <span class="soar-v2-node-type">{{ node.type }}</span>
            <strong>{{ node.name || node.id }}</strong>
            <small v-if="node.actionRef">{{ node.actionRef }}</small>
            <small v-else>{{ node.id }}</small>
          </button>
        </div>
        <div class="soar-v2-canvas-footer">
          <span>{{ definition.nodes.length }} nodes · {{ definition.edges.length }} edges</span>
          <span v-if="connectFromId">Connecting from <b>{{ connectFromId }}</b></span>
          <el-button size="small" @click="startConnect" :disabled="!selectedNode">Connect selected</el-button>
          <el-button size="small" type="danger" plain @click="removeSelectedNode" :disabled="!selectedNode || selectedNode.type === 'START'">Remove selected</el-button>
        </div>
      </section>

      <aside class="soar-v2-inspector" aria-label="Node properties">
        <div class="soar-v2-panel-title">Node inspector</div>
        <template v-if="selectedNode">
          <label>ID<input :value="selectedNode.id" disabled /></label>
          <label>Type<select :value="selectedNode.type" @change="updateNodeType(($event.target as HTMLSelectElement).value)"><option v-for="item in palette" :key="item.type" :value="item.type">{{ item.type }}</option></select></label>
          <label>Name<input :value="selectedNode.name || ''" @input="updateNodeField('name', ($event.target as HTMLInputElement).value)" /></label>
          <label v-if="selectedNode.type === 'ACTION'">Action ref<input :value="String(selectedNode.actionRef || '')" placeholder="socp.alert/get@1" @input="updateNodeField('actionRef', ($event.target as HTMLInputElement).value)" /></label>
          <label v-if="selectedNode.type === 'CONDITION' || selectedNode.type === 'SWITCH'">Expression<input :value="String(selectedNode.expression || '')" placeholder="trigger.severity == 'HIGH'" @input="updateNodeField('expression', ($event.target as HTMLInputElement).value)" /></label>
          <label v-if="selectedNode.type === 'END'">Outcome<select :value="String(selectedNode.outcome || 'SUCCEEDED')" @change="updateNodeField('outcome', ($event.target as HTMLSelectElement).value)"><option>SUCCEEDED</option><option>PARTIALLY_SUCCEEDED</option><option>SUPPRESSED</option><option>FAILED</option></select></label>
          <label v-if="selectedNode.type === 'JOIN'">Join strategy<select :value="String(selectedNode.strategy || 'ALL_SUCCESS')" @change="updateNodeField('strategy', ($event.target as HTMLSelectElement).value)"><option>ALL_SUCCESS</option><option>ALL_DONE</option><option>ANY_SUCCESS</option></select></label>
          <label v-if="selectedNode.type === 'DELAY' || selectedNode.type === 'APPROVAL' || selectedNode.type === 'MANUAL_TASK'">Timeout / duration (seconds)<input type="number" min="0" :value="String(nodeTimingValue(selectedNode))" placeholder="900" @input="updateNodeTiming(($event.target as HTMLInputElement).value)" /></label>
          <div class="soar-v2-inspector-section"><span>Advanced node JSON</span><textarea v-model="nodeConfigText" rows="8" spellcheck="false" /><el-button size="small" @click="applyNodeConfig">Apply node JSON</el-button></div>
        </template>
        <div v-else class="soar-v2-empty">Select a node to inspect its contract.</div>
      </aside>
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
        <div v-for="issue in [...(validation.errors || []), ...(validation.warnings || [])]" :key="`${issue.code}-${issue.path}-${issue.message}`" class="soar-v2-issue" :class="{ warning: !(validation.errors || []).includes(issue) }">
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
.soar-v2-editor-body { display: grid; grid-template-columns: 180px minmax(560px, 1fr) 240px; min-height: 570px; border: 1px solid var(--ns-border); border-radius: 6px; overflow: hidden; }
.soar-v2-palette, .soar-v2-inspector { padding: 10px; background: var(--ns-bg-subtle); }
.soar-v2-palette { border-right: 1px solid var(--ns-border); }
.soar-v2-inspector { border-left: 1px solid var(--ns-border); }
.soar-v2-panel-title { color: var(--ns-text-2); font-size: 11px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; margin-bottom: 9px; }
.soar-v2-palette-item { display: flex; width: 100%; gap: 7px; align-items: center; padding: 7px 6px; text-align: left; border: 1px solid transparent; border-radius: 5px; background: transparent; color: var(--ns-text); cursor: pointer; }
.soar-v2-palette-item:hover { background: var(--ns-bg); border-color: var(--ns-border); }
.soar-v2-palette-item strong, .soar-v2-palette-item small { display: block; }
.soar-v2-palette-item strong { font-size: 11px; }
.soar-v2-palette-item small { color: var(--ns-text-3); font-size: 10px; line-height: 1.25; }
.soar-v2-palette-dot { width: 8px; height: 8px; flex: 0 0 8px; border-radius: 50%; background: var(--ns-accent); }
.tone-start .soar-v2-palette-dot, .node-start { --soar-node-color: #2563eb; }
.tone-end .soar-v2-palette-dot, .node-end { --soar-node-color: #64748b; }
.tone-action .soar-v2-palette-dot, .node-action { --soar-node-color: #0891b2; }
.tone-logic .soar-v2-palette-dot, .node-condition, .node-switch { --soar-node-color: #7c3aed; }
.tone-control .soar-v2-palette-dot, .node-parallel, .node-join, .node-foreach, .node-sub_playbook { --soar-node-color: #ea580c; }
.tone-wait .soar-v2-palette-dot, .node-delay { --soar-node-color: #ca8a04; }
.tone-human .soar-v2-palette-dot, .node-approval, .node-manual_task { --soar-node-color: #db2777; }
.tone-data .soar-v2-palette-dot, .node-set_variable { --soar-node-color: #059669; }
.soar-v2-connect-help { margin: 16px 4px 0; color: var(--ns-text-3); font-size: 10px; line-height: 1.45; }
.soar-v2-canvas-panel { min-width: 0; background: var(--ns-bg); }
.soar-v2-canvas { position: relative; min-height: 540px; height: 540px; overflow: auto; background-image: radial-gradient(var(--ns-border) .7px, transparent .7px); background-size: 18px 18px; }
.soar-v2-edges { position: absolute; inset: 0; width: 960px; height: 540px; color: var(--ns-text-3); pointer-events: none; }
.soar-v2-edges line { stroke: currentColor; stroke-width: 1.5; opacity: .7; }
.soar-v2-flow-node { position: absolute; z-index: 1; display: flex; width: 188px; min-height: 76px; flex-direction: column; align-items: flex-start; gap: 3px; padding: 8px 10px; border: 1px solid var(--soar-node-color, var(--ns-border)); border-left: 4px solid var(--soar-node-color, var(--ns-accent)); border-radius: 6px; background: var(--ns-bg); color: var(--ns-text); box-shadow: 0 2px 7px rgba(15, 23, 42, .08); text-align: left; cursor: grab; }
.soar-v2-flow-node:active { cursor: grabbing; }
.soar-v2-flow-node:hover, .soar-v2-flow-node.selected { box-shadow: 0 0 0 2px color-mix(in srgb, var(--soar-node-color, var(--ns-accent)) 25%, transparent), 0 3px 10px rgba(15, 23, 42, .12); }
.soar-v2-flow-node.connecting { outline: 2px dashed var(--ns-warning); }
.soar-v2-flow-node strong { font-size: 12px; }
.soar-v2-flow-node small { max-width: 168px; overflow: hidden; color: var(--ns-text-3); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.soar-v2-node-type { color: var(--soar-node-color, var(--ns-text-3)); font-size: 9px; font-weight: 700; letter-spacing: .06em; }
.soar-v2-canvas-footer { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-top: 1px solid var(--ns-border); color: var(--ns-text-3); font-size: 11px; }
.soar-v2-canvas-footer span:first-child { margin-right: auto; }
.soar-v2-inspector label { display: block; margin-bottom: 9px; color: var(--ns-text-2); font-size: 10px; }
.soar-v2-inspector label input, .soar-v2-inspector label select { display: block; width: 100%; margin-top: 3px; }
.soar-v2-inspector-section { margin-top: 13px; color: var(--ns-text-2); font-size: 10px; }
.soar-v2-inspector-section textarea { margin: 5px 0; }
.soar-v2-empty { color: var(--ns-text-3); font-size: 11px; line-height: 1.5; }
.soar-v2-editor-lower { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(220px, .7fr) minmax(220px, .8fr); gap: 10px; margin-top: 10px; }
.soar-v2-json-panel, .soar-v2-validation-panel { min-width: 0; padding: 10px; border: 1px solid var(--ns-border); border-radius: 6px; background: var(--ns-bg-subtle); }
.soar-v2-result { max-height: 190px; overflow: auto; margin: 7px 0 0; padding: 8px; border-radius: 4px; background: var(--ns-bg-inset); color: var(--ns-text-2); font-size: 10px; white-space: pre-wrap; }
.soar-v2-issue { margin: 0 -2px 7px; padding: 6px 7px; border-left: 3px solid var(--ns-danger); background: color-mix(in srgb, var(--ns-danger) 7%, transparent); font-size: 10px; }
.soar-v2-issue.warning { border-left-color: var(--ns-warning); background: color-mix(in srgb, var(--ns-warning) 8%, transparent); }
.soar-v2-issue b, .soar-v2-issue span { margin-right: 5px; }
.soar-v2-issue span { color: var(--ns-text-3); }
.soar-v2-issue p { margin: 3px 0 0; color: var(--ns-text-2); }
.soar-v2-hash { color: var(--ns-text-3); font-family: ui-monospace, monospace; font-size: 9px; overflow-wrap: anywhere; }
@media (max-width: 1100px) {
  .soar-v2-editor-body { grid-template-columns: 155px minmax(520px, 1fr); }
  .soar-v2-inspector { grid-column: 1 / -1; border-top: 1px solid var(--ns-border); border-left: 0; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
  .soar-v2-inspector .soar-v2-panel-title, .soar-v2-inspector-section, .soar-v2-empty { grid-column: 1 / -1; }
  .soar-v2-editor-lower { grid-template-columns: 1fr 1fr; }
  .soar-v2-validation-panel { grid-column: 1 / -1; }
}
@media (max-width: 720px) {
  .soar-v2-editor-header { align-items: flex-start; flex-direction: column; }
  .soar-v2-editor-body { display: block; }
  .soar-v2-palette { border-right: 0; border-bottom: 1px solid var(--ns-border); display: grid; grid-template-columns: repeat(2, 1fr); gap: 3px; }
  .soar-v2-panel-title, .soar-v2-connect-help { grid-column: 1 / -1; }
  .soar-v2-inspector { border-left: 0; border-top: 1px solid var(--ns-border); }
  .soar-v2-editor-lower { display: block; }
  .soar-v2-json-panel, .soar-v2-validation-panel { margin-top: 10px; }
}
</style>
