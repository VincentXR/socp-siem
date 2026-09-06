/**
 * Canonical types for the SOAR V2 definition <-> Vue Flow mapping.
 *
 * The definition object is the single source of truth; the Vue Flow layer is a
 * derived view. Raw node/edge JSON is carried by identity on the Vue Flow
 * `data` so serialization round-trips byte-for-byte (unknown/extra fields are
 * never rebuilt by the editor).
 */
import type { EdgeMarkerType } from '@vue-flow/core'

/** HTML5 drag-and-drop mime type used to carry a palette node type to the canvas. */
export const PALETTE_DATA_TYPE = 'application/x-socp-node-type'

/** All SoarNodeType enum values (mirrors the backend `SoarNodeType`). */
export type SoarNodeType =
  | 'START'
  | 'END'
  | 'ACTION'
  | 'CONDITION'
  | 'APPROVAL'
  | 'SWITCH'
  | 'PARALLEL'
  | 'JOIN'
  | 'FOREACH'
  | 'DELAY'
  | 'MANUAL_TASK'
  | 'SUB_PLAYBOOK'
  | 'SET_VARIABLE'

export type NodeTone = 'start' | 'end' | 'action' | 'logic' | 'control' | 'wait' | 'human' | 'data'

/** A business source-port token ('' means the unlabelled default edge). */
export interface PortSpec {
  token: string
  labelKey: string
  label: string
}

export interface SoarNodeTypeMeta {
  type: SoarNodeType
  label: string
  labelKey: string
  description: string
  descriptionKey: string
  tone: NodeTone
  /** True only for START, ACTION, CONDITION, APPROVAL, END. */
  creationAllowed: boolean
  /** True iff !creationAllowed (palette shows a disabled "Coming soon" entry). */
  comingSoon: boolean
  sourcePorts: ReadonlyArray<PortSpec>
  acceptsTarget: boolean
  /** Canonical creation template; never invoked for unsupported types. */
  defaultCreate: (id: string) => EditorNode
}

/* ------------------------------------------------------------------ */
/* Definition model (raw JSON wrappers, kept field-for-field)          */
/* ------------------------------------------------------------------ */

/** Raw definition node. Carried on the Vue Flow node as `data.raw`. */
export interface EditorNode extends Record<string, unknown> {
  id: string
  type: string
  name?: string
}

/** Raw definition edge. Carried on the Vue Flow edge as `data.rawEdge`. */
export interface EditorEdge extends Record<string, unknown> {
  from: string
  to: string
  port?: string
  when?: string
}

/** Raw definition document (`schemaVersion: 'soar.playbook/v2'`). */
export interface EditorDefinition extends Record<string, unknown> {
  schemaVersion: string
  entryNodeId: string
  nodes: EditorNode[]
  edges: EditorEdge[]
  limits?: Record<string, unknown>
}

/* ------------------------------------------------------------------ */
/* Layout model (separate payload, byte-identical shape to today)      */
/* ------------------------------------------------------------------ */

export interface GraphPosition {
  x: number
  y: number
}

export type PositionsMap = Record<string, GraphPosition>

export interface LayoutNode {
  id: string
  x: number
  y: number
}

export interface LayoutPayload {
  nodes: LayoutNode[]
}

/* ------------------------------------------------------------------ */
/* Validation model (backend DefinitionIssue)                          */
/* ------------------------------------------------------------------ */

export interface ValidationIssue extends Record<string, unknown> {
  severity?: 'ERROR' | 'WARNING' | string
  code?: string
  nodeId?: string | null
  path?: string
  message?: string
}

export interface ValidationResult extends Record<string, unknown> {
  valid?: boolean
  errors?: ValidationIssue[]
  warnings?: ValidationIssue[]
  schemaVersion?: string
  definitionHash?: string
  nodeCount?: number
  actionCount?: number
  highRiskActionCount?: number
}

export interface NodeIssueState {
  errors: ValidationIssue[]
  warnings: ValidationIssue[]
  codeSet: Set<string>
}

/* ------------------------------------------------------------------ */
/* Vue Flow bridge                                                     */
/* ------------------------------------------------------------------ */

/** Payload stored on every Vue Flow node `data`. */
export interface FlowNodeData {
  /** The original raw definition node (object identity preserved). */
  raw: EditorNode
  /** Uppercased registry key (may not be a supported SoarNodeType). */
  nodeType: string
  /** True iff the type is not in the creation-capable registry set. */
  unsupported: boolean
  tone: NodeTone
  /** Source handles resolved for this instance (registry set or dynamic). */
  sourcePorts: PortSpec[]
  acceptsTarget: boolean
  /** error/warning counts used for the red/amber border + badge. */
  errors: number
  warnings: number
}

/** Payload stored on every Vue Flow edge `data`. */
export interface FlowEdgeData {
  rawEdge: EditorEdge
  from: string
  to: string
  portKey: string
}

export interface VfNodeInput {
  id: string
  type: 'soar-flow-node'
  position: GraphPosition
  data: FlowNodeData
  draggable: boolean
  selectable: boolean
  connectable: boolean
  deletable: boolean
}

export interface VfEdgeInput {
  id: string
  type?: string
  source: string
  target: string
  sourceHandle: string
  targetHandle: string
  label?: string
  markerEnd?: EdgeMarkerType
  data: FlowEdgeData
  deletable: boolean
}
