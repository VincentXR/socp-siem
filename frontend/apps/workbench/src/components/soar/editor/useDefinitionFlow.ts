/**
 * Reactive graph model for the SOAR V2 editor (definition <-> Vue Flow).
 *
 * `rawRoot` (the definition document) and `positions` are the only state that
 * is mutated. Vue Flow nodes/edges are a derived view that is pushed into the
 * Vue Flow store whenever the raw graph changes (`rebuild()`).
 *
 * Loaded raw node/edge objects are never re-keyed or cloned: `data.raw` holds
 * the very object stored in `rawRoot.nodes`, so serialization round-trips
 * byte-for-byte (unknown/extra keys and `port` vs `when` spelling survive).
 */
import { computed, nextTick, ref, type ComputedRef, type Ref } from 'vue'
import { MarkerType, type Connection, type Edge, type NodeDragEvent, type NodeMouseEvent, type VueFlowStore } from '@vue-flow/core'
import { createHistory, deepClone } from './history'
import { isCreationType, nodeTypeMeta, uniqueNodeId } from './nodeRegistry'
import { mapIssuesToNodes } from './validation'
import {
  mergeRunHighlights,
  preferredRunStatus,
  runStatusTone,
  type NodeRunHighlight,
  type RunHighlightRow,
} from './runHighlight'
import type {
  EditorDefinition,
  EditorEdge,
  EditorNode,
  FlowEdgeData,
  FlowNodeData,
  GraphPosition,
  LayoutPayload,
  NodeIssueState,
  PositionsMap,
  ValidationIssue,
  VfEdgeInput,
  VfNodeInput,
} from './types'

export const NODE_LAYOUT_COLUMNS = 3
export const NODE_WIDTH_STEP = 245
export const NODE_HEIGHT_STEP = 112
const GRID_ORIGIN = { x: 45, y: 42 }

/** Cap on recorded undo/redo states; older snapshots are evicted. */
const HISTORY_LIMIT = 100

/* ------------------------------------------------------------------ */
/* Pure mapping functions (exported for unit tests)                    */
/* ------------------------------------------------------------------ */

export function fallbackPosition(index: number): GraphPosition {
  return {
    x: GRID_ORIGIN.x + (index % NODE_LAYOUT_COLUMNS) * NODE_WIDTH_STEP,
    y: GRID_ORIGIN.y + Math.floor(index / NODE_LAYOUT_COLUMNS) * NODE_HEIGHT_STEP,
  }
}

export function emptyDefinition(): EditorDefinition {
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

export function rawNodeType(raw: EditorNode): string {
  return String(raw?.type ?? '').toUpperCase()
}

export function isUnsupportedNodeType(raw: EditorNode): boolean {
  return !isCreationType(rawNodeType(raw))
}

/** Unified port token: original `port` spelling wins, then `when`, else ''. */
export function edgePortKey(edge: EditorEdge): string {
  if (typeof edge.port === 'string' && edge.port.length) return edge.port
  if (typeof edge.when === 'string' && edge.when.length) return edge.when
  return ''
}

/** One SWITCH case row, matching the engine's {@code config.cases} array item. */
export interface SwitchCaseRow {
  value: string
  port: string
}

/**
 * Reads the SWITCH case array with the same precedence as the backend
 * `switchBranch`/validator: a top-level `node.cases` array wins, otherwise the
 * `node.config.cases` array. Each row carries `value` (matched expression
 * value) plus the outgoing edge `port` (or the `when`/`toPort` spellings).
 */
export function readSwitchCases(raw: EditorNode): SwitchCaseRow[] {
  const source = (raw && typeof raw === 'object' ? raw : {}) as EditorNode
  const top = Array.isArray(source.cases) ? source.cases : undefined
  const config = source.config && typeof source.config === 'object'
    ? source.config as Record<string, unknown> : {}
  const nested = Array.isArray(config.cases) ? config.cases : undefined
  const items = top ?? nested ?? []
  const rows: SwitchCaseRow[] = []
  for (const item of items) {
    if (!item || typeof item !== 'object') continue
    const row = item as Record<string, unknown>
    const value = typeof row.value === 'string' ? row.value : typeof row.when === 'string' ? row.when : ''
    const port = typeof row.port === 'string' ? row.port : typeof row.toPort === 'string' ? row.toPort : ''
    rows.push({ value, port })
  }
  return rows
}

/** Distinct non-default source-port tokens declared by a SWITCH node's cases. */
function switchCasePortTokens(raw: EditorNode): string[] {
  const seen = new Set<string>()
  for (const row of readSwitchCases(raw)) {
    if (row.port) seen.add(row.port)
  }
  return [...seen]
}

/**
 * Loads a definition without rebuilding raw objects: nodes/edges keep their
 * identity, top-level keys keep their position. Only structural defaults are
 * filled when the document is missing them.
 */
export function normalizeDefinition(value: unknown): EditorDefinition {
  const candidate = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>
  const root = candidate as EditorDefinition
  const rawNodes = Array.isArray(candidate.nodes) ? candidate.nodes : []
  const rawEdges = Array.isArray(candidate.edges) ? candidate.edges : []

  const nodes: EditorNode[] = rawNodes
    .filter((item): item is EditorNode => Boolean(item && typeof item === 'object' && String((item as EditorNode).id ?? '')))
  const edges: EditorEdge[] = rawEdges
    .filter((item): item is EditorEdge => Boolean(item && typeof item === 'object'))
    .filter(edge => String(edge.from ?? '') && String(edge.to ?? ''))

  if (typeof candidate.schemaVersion !== 'string') candidate.schemaVersion = 'soar.playbook/v2'
  if (typeof candidate.entryNodeId !== 'string') candidate.entryNodeId = 'start'
  candidate.nodes = nodes.length ? nodes : emptyDefinition().nodes
  candidate.edges = edges
  return root
}

export function parseLayout(layout?: unknown): PositionsMap {
  const positions: PositionsMap = {}
  const layoutObject = layout && typeof layout === 'object' ? layout as Record<string, unknown> : {}
  const rows = Array.isArray(layoutObject.nodes) ? layoutObject.nodes : []
  for (const item of rows) {
    if (!item || typeof item !== 'object') continue
    const row = item as Record<string, unknown>
    const id = String(row.id ?? '')
    if (!id) continue
    const x = numberValue(row.x, 0)
    const y = numberValue(row.y, 0)
    positions[id] = { x, y }
  }
  return positions
}

function numberValue(value: unknown, fallback: number): number {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

/** Ensures every node has a position (fallback grid for missing ones) and prunes orphans. */
export function completePositions(def: EditorDefinition, layout?: unknown): PositionsMap {
  const positions = parseLayout(layout)
  const next: PositionsMap = {}
  def.nodes.forEach((node, index) => {
    next[node.id] = positions[node.id] ?? fallbackPosition(index)
  })
  return next
}

/** Resolve source ports for one node: registry set + dynamic anchors for tokens on its own edges. */
function resolveSourcePorts(raw: EditorNode, def: EditorDefinition): FlowNodeData['sourcePorts'] {
  const upper = rawNodeType(raw)
  const meta = nodeTypeMeta(upper)
  const base = meta ? [...meta.sourcePorts] : [{ token: '', labelKey: '', label: 'Default' }]
  const unsupported = isCreationType(upper) ? false : true
  // SWITCH exposes one handle per declared case port in addition to the fixed
  // registry set, so its case edges can be drawn after the case table changes.
  const dynamicPorts = unsupported || upper === 'SWITCH'
  if (!dynamicPorts && meta) return base
  const staticTokens = new Set(base.map(port => port.token))
  const append = (token: string): void => {
    if (!token || staticTokens.has(token)) return
    staticTokens.add(token)
    base.push({ token, labelKey: '', label: token })
  }
  if (upper === 'SWITCH') {
    for (const token of switchCasePortTokens(raw)) append(token)
  }
  for (const edge of def.edges) {
    if (edge.from !== raw.id) continue
    append(edgePortKey(edge))
  }
  if (!staticTokens.has('')) {
    staticTokens.add('')
    base.push({ token: '', labelKey: '', label: 'Default' })
  }
  return base
}

/**
 * Resolves the view-only run-highlight fields for a definition node. Returns
 * null when no row matched, or when every matched status is outside the
 * recognised set (those nodes keep their current look).
 */
export function runHighlightFields(
  nodeId: string,
  highlights: Readonly<Record<string, NodeRunHighlight>> = {},
): Pick<FlowNodeData, 'runStatus' | 'runIterations' | 'runIterationPaths'> | null {
  const entry = highlights[nodeId]
  if (!entry) return null
  const status = preferredRunStatus(entry.statuses)
  if (!status || !runStatusTone(status)) return null
  return { runStatus: status, runIterations: entry.rowCount, runIterationPaths: entry.iterationPaths }
}

export function buildFlowNodes(
  def: EditorDefinition,
  positions: PositionsMap,
  issues: Record<string, NodeIssueState> = {},
  runHighlights: Readonly<Record<string, NodeRunHighlight>> = {},
): VfNodeInput[] {
  return def.nodes.map((raw) => {
    const upper = rawNodeType(raw)
    const meta = nodeTypeMeta(upper)
    const unsupported = isCreationType(upper) ? false : true
    const startsWithOutEdge = upper === 'START' && def.edges.some(edge => edge.from === raw.id)
    const issue = issues[raw.id]
    const highlight = runHighlightFields(raw.id, runHighlights)
    return {
      id: raw.id,
      type: 'soar-flow-node',
      position: positions[raw.id] ?? fallbackPosition(0),
      data: {
        raw,
        nodeType: upper,
        unsupported,
        tone: meta?.tone ?? 'action',
        sourcePorts: resolveSourcePorts(raw, def),
        acceptsTarget: meta?.acceptsTarget ?? true,
        errors: issue?.errors.length ?? 0,
        warnings: issue?.warnings.length ?? 0,
        ...(highlight ?? {}),
      },
      draggable: true,
      selectable: true,
      connectable: unsupported || startsWithOutEdge ? false : true,
      deletable: upper === 'START' ? false : true,
    }
  })
}

export function buildFlowEdges(def: EditorDefinition): VfEdgeInput[] {
  const nodeTypes = new Map(def.nodes.map(node => [node.id, rawNodeType(node)]))
  return def.edges.map((rawEdge, index) => {
    const token = edgePortKey(rawEdge)
    const sourceUnsupported = isCreationType(nodeTypes.get(rawEdge.from) ?? '') ? false : true
    const targetUnsupported = isCreationType(nodeTypes.get(rawEdge.to) ?? '') ? false : true
    const data: FlowEdgeData = {
      rawEdge,
      from: rawEdge.from,
      to: rawEdge.to,
      portKey: token,
    }
    return {
      id: `${rawEdge.from}→${rawEdge.to}::${token}::${index}`,
      type: 'default',
      source: rawEdge.from,
      target: rawEdge.to,
      sourceHandle: token === '' ? 'default' : token,
      targetHandle: 'in',
      ...(token ? { label: token } : {}),
      markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14, color: '#94a3b8' },
      data,
      deletable: sourceUnsupported || targetUnsupported ? false : true,
    }
  })
}

export function serializeLayout(positions: PositionsMap): LayoutPayload {
  return { nodes: Object.entries(positions).map(([id, p]) => ({ id, x: Math.round(p.x), y: Math.round(p.y) })) }
}

export function effectiveEntryNodeId(def: EditorDefinition): string {
  const start = def.nodes.find(node => rawNodeType(node) === 'START')
  return start?.id ?? String(def.entryNodeId ?? '')
}

/** Canonical structural key for the dirty comparison (array order cannot fake dirt). */
export function canonicalKey(def: EditorDefinition, positions: PositionsMap): string {
  const nodes = [...def.nodes].map(node => JSON.stringify(node)).sort()
  const edges = [...def.edges].map(edge => JSON.stringify(edge)).sort()
  const layout = Object.entries(positions)
    .map(([id, p]) => `${id}:${Math.round(p.x)},${Math.round(p.y)}`)
    .sort()
  const head: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(def)) {
    if (key === 'nodes' || key === 'edges' || key === 'entryNodeId') continue
    head[key] = value
  }
  return JSON.stringify({
    head,
    entryNodeId: effectiveEntryNodeId(def),
    nodes,
    edges,
    layout,
  })
}

/* ------------------------------------------------------------------ */
/* Composables / shell state                                          */
/* ------------------------------------------------------------------ */

/** In-memory copy/cut payload: deep copies of the selected sub-graph. */
export interface EditorClipboard {
  nodes: EditorNode[]
  edges: EditorEdge[]
  positions: Record<string, GraphPosition>
  /** Consecutive pastes step by ~40px so copies never overlap the originals. */
  pasteCount: number
}

export interface SoarFlowApi {
  selectedNodeId: Ref<string>
  positions: Ref<PositionsMap>
  nodeIssueMap: Ref<Record<string, NodeIssueState>>
  validationStale: Ref<boolean>
  validationIssues: Ref<ValidationIssue[]>
  graphRevision: Ref<number>
  dirty: ComputedRef<boolean>
  canUndo: ComputedRef<boolean>
  canRedo: ComputedRef<boolean>
  undo: () => void
  redo: () => void
  copySelection: () => boolean
  cutSelection: () => boolean
  pasteSelection: () => boolean
  autoLayout: () => void
  nodeCount: ComputedRef<number>
  edgeCount: ComputedRef<number>
  applyDefinition: (value: unknown, layout?: unknown) => void
  applyWorkingCopy: (value: unknown, layout?: unknown) => void
  resetToEmpty: () => void
  markBaseline: () => void
  serializeForSave: () => { definition: EditorDefinition; layout: LayoutPayload }
  addNode: (type: string, at?: { x: number; y: number }) => string | null
  addFromDrop: (type: string, clientPoint: { x: number; y: number }) => string | null
  addAtViewportCenter: (type: string) => string | null
  removeSelected: () => void
  removeNode: (id: string) => void
  deleteSelection: () => void
  getDefinition: () => EditorDefinition
  selectNode: (id: string) => void
  clearSelection: () => void
  updateNodeType: (id: string, type: string) => void
  touchAfterNodeEdit: (node?: EditorNode) => void
  /** Re-derives flow nodes so SWITCH case ports appear/disappear as handles. */
  refreshPorts: () => void
  applyIssues: (issues: readonly ValidationIssue[]) => void
  nodeIssues: (id: string) => { errors: ValidationIssue[]; warnings: ValidationIssue[] }
  runHighlights: Readonly<Ref<Record<string, NodeRunHighlight>>>
  hasRunHighlights: ComputedRef<boolean>
  applyRunHighlights: (rows: readonly RunHighlightRow[] | null | undefined) => void
  fitNode: (id: string) => void
  isValidConnection: (connection: Connection) => boolean
}

export function useDefinitionFlow(
  store: VueFlowStore,
  onError: (text: string) => void = () => {},
): SoarFlowApi {
  const rawRoot = ref<EditorDefinition>(emptyDefinition()) as Ref<EditorDefinition>
  const positions = ref<PositionsMap>({}) as Ref<PositionsMap>
  const selectedNodeId = ref('')
  const nodeIssueMap = ref<Record<string, NodeIssueState>>({})
  const validationStale = ref(false)
  const validationIssues = ref<ValidationIssue[]>([])
  // The freshly mounted editor starts with its canonical empty graph. Treat
  // that state as clean until a persisted definition or an explicit edit is
  // applied; otherwise opening the "new playbook" dialog immediately invokes
  // the unsaved-changes guard before the catalog has finished loading.
  const baselineKey = ref(canonicalKey(rawRoot.value, positions.value))
  /** Run-path highlight rows merged per definition node (Slice 4, view-only). */
  const runHighlightMap = ref<Record<string, NodeRunHighlight>>({})
  const hasRunHighlights = computed(() => Object.keys(runHighlightMap.value).length > 0)

  /* ---------------- undo/redo history ---------------- */
  const history = createHistory(HISTORY_LIMIT)
  const historyToken = ref(0)
  let lastHistoryKey = ''
  let clipboard: EditorClipboard | null = null

  const dirty = computed(() =>
    canonicalKey(rawRoot.value, positions.value) !== baselineKey.value)

  const canUndo = computed(() => { void historyToken.value; return history.canUndo() })
  const canRedo = computed(() => { void historyToken.value; return history.canRedo() })

  const nodeCount = computed(() => rawRoot.value.nodes.length)
  const edgeCount = computed(() => rawRoot.value.edges.length)

  /** Incremented on every definition mutation so the shell can re-sync JSON views. */
  const graphRevision = ref(0)

  function bump(): void {
    graphRevision.value += 1
  }

  /** Node/edge input descriptors pushed on the last rebuild, for restore-on-cancel. */
  let nodeInputsById = new Map<string, VfNodeInput>()
  let edgeInputsById = new Map<string, VfEdgeInput>()

  function error(text: string): void {
    onError(text)
  }

  /* ---------------- rebuild (raw -> Vue Flow store) ---------------- */

  function rebuild(): void {
    const flowNodes = buildFlowNodes(rawRoot.value, positions.value, nodeIssueMap.value, runHighlightMap.value)
    const flowEdges = buildFlowEdges(rawRoot.value)
    nodeInputsById = new Map(flowNodes.map(node => [node.id, node]))
    edgeInputsById = new Map(flowEdges.map(edge => [edge.id, edge]))
    store.setNodes(flowNodes)
    store.setEdges(flowEdges)
  }

  function refreshSelectionInStore(): void {
    store.removeSelectedElements()
    const node = store.findNode(selectedNodeId.value)
    if (node) store.addSelectedNodes([node])
  }

  /** Pushes issue counts into live Vue Flow node data without a full rebuild. */
  function syncIssueStateToStore(): void {
    for (const graphNode of store.nodes.value) {
      const entry = nodeIssueMap.value[graphNode.id]
      const errors = entry?.errors.length ?? 0
      const warnings = entry?.warnings.length ?? 0
      if (graphNode.data && (graphNode.data.errors !== errors || graphNode.data.warnings !== warnings)) {
        graphNode.data.errors = errors
        graphNode.data.warnings = warnings
      }
    }
  }

  function markStale(): void {
    validationStale.value = true
    nodeIssueMap.value = {}
    syncIssueStateToStore()
    bump()
  }

  /**
   * Mirrors the merged run highlight map into live Vue Flow node data without
   * rebuilding nodes (drags and selectors keep working while highlighting).
   */
  function syncRunHighlightsToStore(): void {
    for (const graphNode of store.nodes.value) {
      const fields = runHighlightFields(graphNode.id, runHighlightMap.value)
      if (fields) {
        graphNode.data.runStatus = fields.runStatus
        graphNode.data.runIterations = fields.runIterations
        graphNode.data.runIterationPaths = fields.runIterationPaths
      } else if (graphNode.data && 'runStatus' in graphNode.data) {
        delete graphNode.data.runStatus
        delete graphNode.data.runIterations
        delete graphNode.data.runIterationPaths
      }
    }
  }

  /**
   * Merges `/runs/{id}/nodes` rows into per-node run highlights (Slice 4).
   * Passing null/[] clears every highlight. This only overlays the canvas: it
   * never touches the definition, the dirty baseline, or the undo history.
   */
  function applyRunHighlights(rows: readonly RunHighlightRow[] | null | undefined): void {
    runHighlightMap.value = mergeRunHighlights(rows)
    syncRunHighlightsToStore()
  }

  /* ---------------- lifecycle / load ---------------- */

  function applyState(value: unknown, layout: unknown | undefined, resetBaseline: boolean): void {
    rawRoot.value = normalizeDefinition(value)
    positions.value = completePositions(rawRoot.value, layout)
    nodeIssueMap.value = {}
    validationStale.value = false
    validationIssues.value = []
    // Run highlights describe a specific executed run; they never survive a
    // definition load (undo/redo, version switch, apply-JSON all clear them).
    runHighlightMap.value = {}
    const first = rawRoot.value.nodes.find(node => rawNodeType(node) === 'START')
      ?? rawRoot.value.nodes[0]
    selectedNodeId.value = first?.id ?? ''
    rebuild()
    refreshSelectionInStore()
    if (resetBaseline) baselineKey.value = canonicalKey(rawRoot.value, positions.value)
    bump()
    void nextTick(() => { void store.fitView({ padding: 0.2 }) })
  }

  function applyDefinition(value: unknown, layout?: unknown): void {
    applyState(value, layout, true)
    resetHistory()
  }

  /** Applies a pasted/edited definition without moving the dirty baseline. */
  function applyWorkingCopy(value: unknown, layout?: unknown): void {
    applyState(value, layout, false)
    pushHistory()
  }

  function resetToEmpty(): void {
    applyDefinition(emptyDefinition())
  }

  function markBaseline(): void {
    baselineKey.value = canonicalKey(rawRoot.value, positions.value)
  }

  /* ---------------- undo/redo record points ---------------- */

  /** Start a fresh history branch anchored at the current (just-loaded/saved) state. */
  function resetHistory(): void {
    history.clear()
    history.record(rawRoot.value, serializeLayout(positions.value))
    lastHistoryKey = canonicalKey(rawRoot.value, positions.value)
    historyToken.value += 1
  }

  /** Snapshot a committed mutation; no-ops (same canonical state) are skipped. */
  function pushHistory(): void {
    const key = canonicalKey(rawRoot.value, positions.value)
    if (key === lastHistoryKey) return
    history.record(rawRoot.value, serializeLayout(positions.value))
    lastHistoryKey = key
    historyToken.value += 1
  }

  function undo(): void {
    const snapshot = history.undo()
    if (!snapshot) return
    historyToken.value += 1
    applyState(snapshot.definition, snapshot.layout, false)
    lastHistoryKey = canonicalKey(rawRoot.value, positions.value)
  }

  function redo(): void {
    const snapshot = history.redo()
    if (!snapshot) return
    historyToken.value += 1
    applyState(snapshot.definition, snapshot.layout, false)
    lastHistoryKey = canonicalKey(rawRoot.value, positions.value)
  }

  function serializeForSave(): { definition: EditorDefinition; layout: LayoutPayload } {
    const start = rawRoot.value.nodes.find(node => rawNodeType(node) === 'START')
    if (start) rawRoot.value.entryNodeId = start.id
    return { definition: rawRoot.value, layout: serializeLayout(positions.value) }
  }

  /* ---------------- node creation / selection ---------------- */

  function addNode(type: string, at?: { x: number; y: number }): string | null {
    const upper = type.trim().toUpperCase()
    if (!isCreationType(upper)) {
      error('This node type is not available yet (coming soon).')
      return null
    }
    if (upper === 'START' && rawRoot.value.nodes.some(node => rawNodeType(node) === 'START')) {
      error('A definition can contain only one START node')
      return null
    }
    const id = uniqueNodeId(upper, rawRoot.value.nodes.map(node => node.id))
    const node = { id, type: upper } as EditorNode
    const meta = nodeTypeMeta(upper)
    const created = meta?.defaultCreate(id) ?? node
    rawRoot.value.nodes.push(created)
    positions.value[id] = at ?? fallbackPosition(rawRoot.value.nodes.length - 1)
    selectedNodeId.value = id
    markStale()
    rebuild()
    refreshSelectionInStore()
    pushHistory()
    return id
  }

  function dropScreenToFlow(clientPoint: { x: number; y: number }): { x: number; y: number } | null {
    const pane = store.vueFlowRef.value
    const rect = pane?.getBoundingClientRect()
    if (!rect) return null
    return store.screenToFlowCoordinate({ x: clientPoint.x - rect.left, y: clientPoint.y - rect.top })
  }

  function addFromDrop(type: string, clientPoint: { x: number; y: number }): string | null {
    const flowPoint = dropScreenToFlow(clientPoint)
    if (!flowPoint) return null
    return addNode(type, flowPoint)
  }

  function addAtViewportCenter(type: string): string | null {
    const pane = store.vueFlowRef.value
    const rect = pane?.getBoundingClientRect()
    if (!rect) return null
    const center = { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
    return addFromDrop(type, center)
  }

  function selectNode(id: string): void {
    selectedNodeId.value = id
    refreshSelectionInStore()
  }

  function clearSelection(): void {
    selectedNodeId.value = ''
    store.removeSelectedElements()
  }

  function selectFirstAvailable(): void {
    selectedNodeId.value = rawRoot.value.nodes[0]?.id ?? ''
  }

  /* ---------------- deletion ---------------- */

  function removeNodeRaw(id: string): void {
    rawRoot.value.nodes = rawRoot.value.nodes.filter(node => node.id !== id)
    rawRoot.value.edges = rawRoot.value.edges.filter(edge => edge.from !== id && edge.to !== id)
    delete positions.value[id]
    if (selectedNodeId.value === id) selectFirstAvailable()
  }

  function removeNode(id: string): void {
    const raw = rawRoot.value.nodes.find(node => node.id === id)
    if (!raw) return
    const upper = rawNodeType(raw)
    if (upper === 'START') {
      error('START cannot be removed; replace it instead')
      return
    }
    if (!isCreationType(upper) && !window.confirm(`Delete read-only node “${raw.name || id}” and its edges?`)) return
    removeNodeRaw(id)
    markStale()
    rebuild()
    refreshSelectionInStore()
    pushHistory()
  }

  function removeSelected(): void {
    if (!selectedNodeId.value) return
    removeNode(selectedNodeId.value)
  }

  /**
   * Deletes the current Vue Flow selection (nodes + edges) with the usual
   * guards: START is refused, unsupported nodes require a confirm, and edges
   * incident to an unsupported node are never touched.
   */
  function deleteSelection(): void {
    const selectedNodes = [...store.getSelectedNodes.value]
    const selectedEdges = [...store.getSelectedEdges.value]
    if (!selectedNodes.length && !selectedEdges.length) return

    const typeById = new Map(rawRoot.value.nodes.map(node => [node.id, rawNodeType(node)]))
    if (selectedNodes.some(node => typeById.get(node.id) === 'START')) {
      error('START cannot be removed; replace it instead')
      return
    }
    const unsupported = selectedNodes.filter(node => {
      const type = typeById.get(node.id) ?? ''
      return !isCreationType(type)
    })
    for (const node of unsupported) {
      const name = String(node.data?.raw?.name ?? node.id)
      if (!window.confirm(`Delete read-only node “${name}” and its edges?`)) return
    }

    let changed = false
    for (const node of selectedNodes) {
      const exists = rawRoot.value.nodes.some(raw => raw.id === node.id)
      if (!exists) continue
      removeNodeRaw(node.id)
      changed = true
    }
    for (const edge of selectedEdges) {
      const descriptor = edgeInputsById.get(edge.id)
      if (!descriptor || !descriptor.deletable) continue
      const index = rawRoot.value.edges.indexOf(descriptor.data.rawEdge)
      if (index !== -1) {
        rawRoot.value.edges.splice(index, 1)
        changed = true
      }
    }
    if (changed) {
      markStale()
      rebuild()
      refreshSelectionInStore()
      pushHistory()
    }
  }

  /* ---------------- copy / cut / paste ---------------- */

  /** Copies the selected node sub-graph (internal edges only) for later paste. */
  function copySelection(): boolean {
    const selectedNodes = [...store.getSelectedNodes.value]
    if (!selectedNodes.length) return false
    const ids = new Set(selectedNodes.map(node => node.id))
    const nodes = rawRoot.value.nodes
      .filter(raw => ids.has(raw.id))
      .map(raw => deepClone(raw))
    if (!nodes.length) return false
    const copiedPositions: Record<string, GraphPosition> = {}
    for (const node of nodes) {
      const at = positions.value[node.id]
      copiedPositions[node.id] = at ? { ...at } : { ...fallbackPosition(0) }
    }
    const edges = rawRoot.value.edges
      .filter(edge => ids.has(edge.from) && ids.has(edge.to))
      .map(edge => deepClone(edge))
    clipboard = { nodes, edges, positions: copiedPositions, pasteCount: 0 }
    return true
  }

  function cutSelection(): boolean {
    if (!copySelection()) return false
    deleteSelection()
    return true
  }

  /** Unique id for a pasted copy: `<source>_copy`, `<source>_copy2`, ... */
  function freshPasteId(sourceId: string, used: ReadonlySet<string>): string {
    const base = `${sourceId}_copy`
    if (!used.has(base)) return base
    let index = 2
    while (used.has(`${base}${index}`)) index += 1
    return `${base}${index}`
  }

  /**
   * Inserts the clipboard sub-graph. START is never duplicated; edges whose
   * endpoints are both pasted are re-linked. Copied nodes keep whatever type
   * they had in the source definition (creation-gating only governs the
   * palette, so read-only types carried by a clipboard are preserved as-is).
   */
  function pasteSelection(): boolean {
    if (!clipboard || !clipboard.nodes.length) return false
    const used = new Set(rawRoot.value.nodes.map(node => node.id))
    const idMap = new Map<string, string>()
    const inserted: EditorNode[] = []
    const delta = 40 * (clipboard.pasteCount + 1)
    for (const source of clipboard.nodes) {
      if (rawNodeType(source) === 'START') continue
      const fresh = freshPasteId(source.id, used)
      used.add(fresh)
      idMap.set(source.id, fresh)
      const node = deepClone(source)
      node.id = fresh
      inserted.push(node)
      const at = clipboard.positions[source.id]
      const base = at ?? fallbackPosition(rawRoot.value.nodes.length + inserted.length)
      positions.value[fresh] = { x: base.x + delta, y: base.y + delta }
    }
    if (!inserted.length) return false
    const pastedEdges = clipboard.edges
      .filter(edge => idMap.has(edge.from) && idMap.has(edge.to))
      .map((edge) => {
        const copy = deepClone(edge)
        copy.from = idMap.get(edge.from) as string
        copy.to = idMap.get(edge.to) as string
        return copy
      })
    rawRoot.value.nodes.push(...inserted)
    rawRoot.value.edges.push(...pastedEdges)
    clipboard.pasteCount += 1
    selectedNodeId.value = inserted[0].id
    markStale()
    rebuild()
    refreshSelectionInStore()
    pushHistory()
    return true
  }

  /** Current definition document (no structural rewrite). */
  function getDefinition(): EditorDefinition {
    return rawRoot.value
  }

  /* ---------------- field/type edits ---------------- */

  function updateNodeType(id: string, type: string): void {
    const upper = type.trim().toUpperCase()
    if (!isCreationType(upper)) return
    const raw = rawRoot.value.nodes.find(node => node.id === id)
    if (!raw) return
    const meta = nodeTypeMeta(upper)
    if (upper === 'START' && rawRoot.value.nodes.some(node => node.id !== id && rawNodeType(node) === 'START')) {
      error('A definition can contain only one START node')
      return
    }
    raw.type = upper
    const name = typeof raw.name === 'string' && raw.name.trim() ? raw.name : meta?.label ?? upper
    raw.name = name
    markStale()
    rebuild()
    refreshSelectionInStore()
  }

  /** Node card fields are read straight from the reactive raw object; just clear stale validation. */
  function touchAfterNodeEdit(): void {
    markStale()
  }

  /** Rebuilds the Vue Flow layer so dynamic SWITCH case handles stay in sync. */
  function refreshPorts(): void {
    rebuild()
    refreshSelectionInStore()
  }

  /* ---------------- validation ---------------- */

  function applyIssues(issues: readonly ValidationIssue[]): void {
    validationIssues.value = [...issues]
    nodeIssueMap.value = mapIssuesToNodes(issues, rawRoot.value.nodes)
    validationStale.value = false
    rebuild()
  }

  function nodeIssues(id: string): { errors: ValidationIssue[]; warnings: ValidationIssue[] } {
    const entry = nodeIssueMap.value[id]
    return entry ? { errors: entry.errors, warnings: entry.warnings } : { errors: [], warnings: [] }
  }

  function fitNode(id: string): void {
    if (!store.findNode(id)) return
    void store.fitView({ nodes: [id], padding: 0.4, maxZoom: 1.25 })
  }

  /**
   * Apply a deterministic layered layout to the current graph.  This is
   * intentionally dependency-free: the saved layout remains a presentation
   * concern and must not depend on a browser-only layout engine.  A
   * topological walk puts the START-to-END path into columns; disconnected or
   * cyclic legacy nodes are placed after the reachable layers in definition
   * order so the operation is still total and repeatable.
   */
  function autoLayout(): void {
    const nodes = rawRoot.value.nodes
    if (!nodes.length) return
    const order = new Map(nodes.map((node, index) => [node.id, index]))
    const outgoing = new Map<string, string[]>()
    const indegree = new Map<string, number>()
    for (const node of nodes) {
      outgoing.set(node.id, [])
      indegree.set(node.id, 0)
    }
    for (const edge of rawRoot.value.edges) {
      if (!indegree.has(edge.from) || !indegree.has(edge.to)) continue
      outgoing.get(edge.from)?.push(edge.to)
      indegree.set(edge.to, (indegree.get(edge.to) ?? 0) + 1)
    }
    for (const targets of outgoing.values()) targets.sort((a, b) => (order.get(a) ?? 0) - (order.get(b) ?? 0))

    const depth = new Map<string, number>()
    const queue: string[] = nodes
      .filter(node => rawNodeType(node) === 'START')
      .map(node => node.id)
    if (!queue.length) queue.push(nodes[0].id)
    for (const id of queue) depth.set(id, 0)
    const remaining = new Map(indegree)
    while (queue.length) {
      const current = queue.shift() as string
      const nextDepth = (depth.get(current) ?? 0) + 1
      for (const target of outgoing.get(current) ?? []) {
        depth.set(target, Math.max(depth.get(target) ?? 0, nextDepth))
        const nextRemaining = (remaining.get(target) ?? 0) - 1
        remaining.set(target, nextRemaining)
        if (nextRemaining <= 0) queue.push(target)
      }
    }

    let fallbackDepth = Math.max(-1, ...depth.values()) + 1
    for (const node of nodes) {
      if (!depth.has(node.id)) depth.set(node.id, fallbackDepth++)
    }
    const layers = new Map<number, EditorNode[]>()
    for (const node of nodes) {
      const layer = depth.get(node.id) ?? 0
      const bucket = layers.get(layer) ?? []
      bucket.push(node)
      layers.set(layer, bucket)
    }
    const nextPositions: PositionsMap = {}
    for (const [layer, bucket] of [...layers.entries()].sort(([a], [b]) => a - b)) {
      bucket.sort((a, b) => (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0))
      bucket.forEach((node, index) => {
        nextPositions[node.id] = {
          x: GRID_ORIGIN.x + layer * NODE_WIDTH_STEP,
          y: GRID_ORIGIN.y + index * NODE_HEIGHT_STEP,
        }
      })
    }
    positions.value = nextPositions
    markStale()
    rebuild()
    refreshSelectionInStore()
    pushHistory()
    void nextTick(() => { void store.fitView({ padding: 0.2 }) })
  }

  /* ---------------- connection validation ---------------- */

  function validateNewEdge(source: string, target: string, token: string): { ok: boolean; message: string } {
    if (!source || !target) return { ok: false, message: 'Both ends of an edge are required' }
    if (source === target) return { ok: false, message: 'An edge cannot connect a node to itself' }
    const sourceRaw = rawRoot.value.nodes.find(node => node.id === source)
    const targetRaw = rawRoot.value.nodes.find(node => node.id === target)
    if (!sourceRaw || !targetRaw) return { ok: false, message: 'Edge references a node that does not exist' }
    const sourceType = rawNodeType(sourceRaw)
    const targetType = rawNodeType(targetRaw)
    if (sourceType === 'END') return { ok: false, message: 'No edge may leave an END node' }
    if (targetType === 'START') return { ok: false, message: 'No edge may enter the START node' }
    if (isUnsupportedNodeType(sourceRaw) || isUnsupportedNodeType(targetRaw)) {
      return { ok: false, message: 'Read-only node types cannot take part in new connections' }
    }
    if (sourceType === 'START' && rawRoot.value.edges.some(edge => edge.from === source)) {
      return { ok: false, message: 'START may only have one outgoing edge' }
    }
    // Port allowance is the node's actual handle set (registry plus dynamic
    // SWITCH case ports), not just the static registry list.
    if (token) {
      const allowed = resolveSourcePorts(sourceRaw, rawRoot.value).some(port => port.token === token)
      if (!allowed) return { ok: false, message: `Port “${token}” is not valid for a ${sourceType} node` }
    }
    const duplicate = rawRoot.value.edges.some(edge =>
      edge.from === source && edge.to === target && edgePortKey(edge) === token)
    if (duplicate) return { ok: false, message: 'This connection already exists' }
    return { ok: true, message: '' }
  }

  function isValidConnection(connection: Connection): boolean {
    const token = connection.sourceHandle === 'default' || !connection.sourceHandle ? '' : String(connection.sourceHandle)
    return validateNewEdge(String(connection.source ?? ''), String(connection.target ?? ''), token).ok
  }

  /* ---------------- Vue Flow event wiring ---------------- */

  store.onConnect((connection) => {
    const source = String(connection.source ?? '')
    const target = String(connection.target ?? '')
    const token = !connection.sourceHandle || connection.sourceHandle === 'default' ? '' : String(connection.sourceHandle)
    const verdict = validateNewEdge(source, target, token)
    if (!verdict.ok) {
      error(verdict.message)
      return
    }
    const rawEdge: EditorEdge = token ? { from: source, to: target, port: token } : { from: source, to: target }
    rawRoot.value.edges.push(rawEdge)
    markStale()
    rebuild()
    pushHistory()
  })

  store.onNodeDragStop(({ nodes }: NodeDragEvent) => {
    let changed = false
    for (const node of nodes) {
      if (!rawRoot.value.nodes.some(raw => raw.id === node.id)) continue
      const next: GraphPosition = { x: Math.round(node.position.x), y: Math.round(node.position.y) }
      const previous = positions.value[node.id]
      if (!previous || previous.x !== next.x || previous.y !== next.y) {
        positions.value[node.id] = next
        changed = true
      }
    }
    if (changed) pushHistory()
  })

  store.onNodeClick(({ node }: NodeMouseEvent) => {
    selectedNodeId.value = node.id
  })

  store.onPaneClick(() => {
    selectedNodeId.value = ''
  })

  store.onNodesChange((changes) => {
    const removals = changes.filter(change => change.type === 'remove')
    if (!removals.length) return
    let mutated = false
    for (const change of removals) {
      if (change.type !== 'remove') continue
      const id = change.id
      const raw = rawRoot.value.nodes.find(node => node.id === id)
      if (!raw) continue
      const upper = rawNodeType(raw)
      if (upper === 'START') {
        error('START cannot be removed; replace it instead')
        restoreNodeInStore(id)
        continue
      }
      if (!isCreationType(upper)) {
        if (!window.confirm(`Delete read-only node “${raw.name || id}” and its edges?`)) {
          restoreNodeInStore(id)
          continue
        }
      }
      removeNodeRaw(id)
      mutated = true
    }
    if (mutated) {
      markStale()
      rebuild()
      refreshSelectionInStore()
      pushHistory()
    }
  })

  store.onEdgesChange((changes) => {
    const removals = changes.filter(change => change.type === 'remove')
    if (!removals.length) return
    let mutated = false
    for (const change of removals) {
      if (change.type !== 'remove') continue
      const descriptor = edgeInputsById.get(change.id)
      if (!descriptor) continue
      const index = rawRoot.value.edges.indexOf(descriptor.data.rawEdge)
      if (index !== -1) {
        rawRoot.value.edges.splice(index, 1)
        mutated = true
      }
    }
    if (mutated) {
      markStale()
      rebuild()
      pushHistory()
    }
  })

  function restoreNodeInStore(id: string): void {
    const descriptor = nodeInputsById.get(id)
    if (descriptor) store.addNodes([descriptor])
  }

  return {
    selectedNodeId,
    positions,
    nodeIssueMap,
    validationStale,
    validationIssues,
    graphRevision,
    dirty,
    canUndo,
    canRedo,
    undo,
    redo,
    copySelection,
    cutSelection,
    pasteSelection,
    autoLayout,
    nodeCount,
    edgeCount,
    applyDefinition,
    applyWorkingCopy,
    resetToEmpty,
    markBaseline,
    serializeForSave,
    addNode,
    addFromDrop,
    addAtViewportCenter,
    removeSelected,
    removeNode,
    deleteSelection,
    getDefinition,
    selectNode,
    clearSelection,
    updateNodeType,
    touchAfterNodeEdit,
    refreshPorts,
    applyIssues,
    nodeIssues,
    runHighlights: runHighlightMap,
    hasRunHighlights,
    applyRunHighlights,
    fitNode,
    isValidConnection,
  }
}
