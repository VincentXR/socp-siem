/**
 * Node registry for the SOAR V2 graph editor.
 *
 * Keyed by every SoarNodeType (13 values). Only START, ACTION, CONDITION,
 * APPROVAL and END are creation-allowed; every other type is shown in the
 * palette as a disabled "Coming soon" entry and can never be dropped onto the
 * canvas. Existing definitions that contain unsupported types still render as
 * read-only generic cards.
 */
import type { EditorNode, PortSpec, SoarNodeType, SoarNodeTypeMeta } from './types'

const DEFAULT_PORT: PortSpec = { token: '', labelKey: 'soar.port.default', label: 'Default' }
const SUCCESS_PORT: PortSpec = { token: 'success', labelKey: 'soar.port.success', label: 'success' }
const FAILURE_PORT: PortSpec = { token: 'failure', labelKey: 'soar.port.failure', label: 'failure' }
const ERROR_PORT: PortSpec = { token: 'error', labelKey: 'soar.port.error', label: 'error' }
const UNKNOWN_PORT: PortSpec = { token: 'unknown', labelKey: 'soar.port.unknown', label: 'unknown' }
const TRUE_PORT: PortSpec = { token: 'true', labelKey: 'soar.port.true', label: 'true' }
const FALSE_PORT: PortSpec = { token: 'false', labelKey: 'soar.port.false', label: 'false' }
const APPROVED_PORT: PortSpec = { token: 'approved', labelKey: 'soar.port.approved', label: 'approved' }
const REJECTED_PORT: PortSpec = { token: 'rejected', labelKey: 'soar.port.rejected', label: 'rejected' }

export const CREATION_TYPES: readonly SoarNodeType[] = ['START', 'ACTION', 'CONDITION', 'APPROVAL', 'END']

export function isCreationType(type: string): boolean {
  return CREATION_TYPES.includes(type as SoarNodeType)
}

/**
 * Palette iteration order: the five creation-capable types first, then the
 * coming-soon group in the order the current editor lists them.
 */
export const NODE_TYPE_ORDER: readonly SoarNodeType[] = [
  'START', 'ACTION', 'CONDITION', 'APPROVAL', 'END',
  'SWITCH', 'PARALLEL', 'JOIN', 'FOREACH', 'DELAY', 'MANUAL_TASK', 'SUB_PLAYBOOK', 'SET_VARIABLE',
]

/** Port vocabulary matches the backend `validateEdgePort` table exactly. */
export const SOAR_NODE_REGISTRY: Record<SoarNodeType, SoarNodeTypeMeta> = {
  START: {
    type: 'START',
    label: 'Start',
    labelKey: 'soar.nodeType.START',
    description: 'Workflow entry',
    descriptionKey: 'soar.nodeTypeDesc.START',
    tone: 'start',
    creationAllowed: true,
    comingSoon: false,
    sourcePorts: [DEFAULT_PORT],
    acceptsTarget: false,
    defaultCreate: (id) => ({ id, type: 'START', name: 'Start' }),
  },
  ACTION: {
    type: 'ACTION',
    label: 'Action',
    labelKey: 'soar.nodeType.ACTION',
    description: 'Connector operation',
    descriptionKey: 'soar.nodeTypeDesc.ACTION',
    tone: 'action',
    creationAllowed: true,
    comingSoon: false,
    sourcePorts: [DEFAULT_PORT, SUCCESS_PORT, FAILURE_PORT, ERROR_PORT, UNKNOWN_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'ACTION', name: 'Action', actionRef: '', parameters: {}, target: {} }),
  },
  CONDITION: {
    type: 'CONDITION',
    label: 'Condition',
    labelKey: 'soar.nodeType.CONDITION',
    description: 'Safe expression branch',
    descriptionKey: 'soar.nodeTypeDesc.CONDITION',
    tone: 'logic',
    creationAllowed: true,
    comingSoon: false,
    sourcePorts: [TRUE_PORT, FALSE_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'CONDITION', name: 'Condition', expression: "trigger.severity == 'HIGH'" }),
  },
  APPROVAL: {
    type: 'APPROVAL',
    label: 'Approval',
    labelKey: 'soar.nodeType.APPROVAL',
    description: 'Durable human gate',
    descriptionKey: 'soar.nodeTypeDesc.APPROVAL',
    tone: 'human',
    creationAllowed: true,
    comingSoon: false,
    sourcePorts: [APPROVED_PORT, REJECTED_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'APPROVAL', name: 'Approval', config: { timeoutSeconds: 86400 } }),
  },
  END: {
    type: 'END',
    label: 'End',
    labelKey: 'soar.nodeType.END',
    description: 'Terminal outcome',
    descriptionKey: 'soar.nodeTypeDesc.END',
    tone: 'end',
    creationAllowed: true,
    comingSoon: false,
    sourcePorts: [],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'END', name: 'Done', outcome: 'SUCCEEDED' }),
  },
  SWITCH: {
    type: 'SWITCH',
    label: 'Switch',
    labelKey: 'soar.nodeType.SWITCH',
    description: 'Case/default branch',
    descriptionKey: 'soar.nodeTypeDesc.SWITCH',
    tone: 'logic',
    creationAllowed: false,
    comingSoon: true,
    sourcePorts: [DEFAULT_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'SWITCH' }),
  },
  PARALLEL: {
    type: 'PARALLEL',
    label: 'Parallel',
    labelKey: 'soar.nodeType.PARALLEL',
    description: 'Fan-out to branches',
    descriptionKey: 'soar.nodeTypeDesc.PARALLEL',
    tone: 'control',
    creationAllowed: false,
    comingSoon: true,
    sourcePorts: [DEFAULT_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'PARALLEL' }),
  },
  JOIN: {
    type: 'JOIN',
    label: 'Join',
    labelKey: 'soar.nodeType.JOIN',
    description: 'Deterministic fan-in',
    descriptionKey: 'soar.nodeTypeDesc.JOIN',
    tone: 'control',
    creationAllowed: false,
    comingSoon: true,
    sourcePorts: [DEFAULT_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'JOIN' }),
  },
  FOREACH: {
    type: 'FOREACH',
    label: 'For each',
    labelKey: 'soar.nodeType.FOREACH',
    description: 'Bounded collection loop',
    descriptionKey: 'soar.nodeTypeDesc.FOREACH',
    tone: 'control',
    creationAllowed: false,
    comingSoon: true,
    sourcePorts: [DEFAULT_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'FOREACH' }),
  },
  DELAY: {
    type: 'DELAY',
    label: 'Delay',
    labelKey: 'soar.nodeType.DELAY',
    description: 'Temporal timer',
    descriptionKey: 'soar.nodeTypeDesc.DELAY',
    tone: 'wait',
    creationAllowed: false,
    comingSoon: true,
    sourcePorts: [DEFAULT_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'DELAY' }),
  },
  MANUAL_TASK: {
    type: 'MANUAL_TASK',
    label: 'Manual task',
    labelKey: 'soar.nodeType.MANUAL_TASK',
    description: 'Structured analyst input',
    descriptionKey: 'soar.nodeTypeDesc.MANUAL_TASK',
    tone: 'human',
    creationAllowed: false,
    comingSoon: true,
    sourcePorts: [DEFAULT_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'MANUAL_TASK' }),
  },
  SUB_PLAYBOOK: {
    type: 'SUB_PLAYBOOK',
    label: 'Sub-playbook',
    labelKey: 'soar.nodeType.SUB_PLAYBOOK',
    description: 'Published child version',
    descriptionKey: 'soar.nodeTypeDesc.SUB_PLAYBOOK',
    tone: 'control',
    creationAllowed: false,
    comingSoon: true,
    sourcePorts: [DEFAULT_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'SUB_PLAYBOOK' }),
  },
  SET_VARIABLE: {
    type: 'SET_VARIABLE',
    label: 'Set variable',
    labelKey: 'soar.nodeType.SET_VARIABLE',
    description: 'Write vars.* only',
    descriptionKey: 'soar.nodeTypeDesc.SET_VARIABLE',
    tone: 'data',
    creationAllowed: false,
    comingSoon: true,
    sourcePorts: [DEFAULT_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'SET_VARIABLE' }),
  },
}

export function nodeTypeMeta(nodeType: string): SoarNodeTypeMeta | undefined {
  return SOAR_NODE_REGISTRY[nodeType as SoarNodeType]
}

/** tone metadata for rendering accents; falls back to 'action'. */
export function toneOf(nodeType: string): SoarNodeTypeMeta['tone'] {
  return nodeTypeMeta(nodeType)?.tone ?? 'action'
}

/**
 * Node id generation reused verbatim from the current editor: base
 * `type.toLowerCase().replace('_','-')`, suffix `-2`, `-3`, ... until unique.
 */
export function uniqueNodeId(baseType: string, existing: readonly string[]): string {
  const used = new Set(existing)
  const base = baseType.toLowerCase().replace(/_/g, '-')
  let id = base
  let index = 2
  while (used.has(id)) id = `${base}-${index++}`
  return id
}

/** Canonical key order `id, type, name, …` matches backend templates. */
export function createNode(type: SoarNodeType, id: string): EditorNode {
  const meta = SOAR_NODE_REGISTRY[type]
  return meta.defaultCreate(id)
}
