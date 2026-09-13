/**
 * Node registry for the SOAR graph editor.
 *
 * Keyed by every SoarNodeType (13 values). All 13 are creation-capable:
 * every interpreter path now has workflow-level evidence, including SWITCH
 * (whose case/value editor matches the engine and validator case layout, see
 * {@code switchBranch} / the SWITCH validator rules). SWITCH keeps a Default
 * source port plus one dynamic source handle per case row declared in its
 * {@code config.cases} array.
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
const BODY_PORT: PortSpec = { token: 'body', labelKey: 'soar.port.body', label: 'body' }
const EACH_PORT: PortSpec = { token: 'each', labelKey: 'soar.port.each', label: 'each' }
const DONE_PORT: PortSpec = { token: 'done', labelKey: 'soar.port.done', label: 'done' }
const COMPLETED_PORT: PortSpec = { token: 'completed', labelKey: 'soar.port.completed', label: 'completed' }
const TIMEOUT_PORT: PortSpec = { token: 'timeout', labelKey: 'soar.port.timeout', label: 'timeout' }

export const CREATION_TYPES: readonly SoarNodeType[] = [
  'START', 'ACTION', 'CONDITION', 'SWITCH', 'APPROVAL', 'END',
  'PARALLEL', 'JOIN', 'FOREACH', 'SUB_PLAYBOOK',
  'MANUAL_TASK', 'DELAY', 'SET_VARIABLE',
]

export function isCreationType(type: string): boolean {
  return CREATION_TYPES.includes(type as SoarNodeType)
}

/**
 * Palette iteration order, all 13 types enabled (no coming-soon group now):
 * entry -> branch/types of decision -> human/control -> data primitives.
 */
export const NODE_TYPE_ORDER: readonly SoarNodeType[] = [
  'START', 'ACTION', 'CONDITION', 'SWITCH', 'APPROVAL', 'END',
  'PARALLEL', 'JOIN', 'FOREACH', 'SUB_PLAYBOOK',
  'MANUAL_TASK', 'DELAY', 'SET_VARIABLE',
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
  SWITCH: {
    type: 'SWITCH',
    label: 'Switch',
    labelKey: 'soar.nodeType.SWITCH',
    description: 'Case/default branch',
    descriptionKey: 'soar.nodeTypeDesc.SWITCH',
    tone: 'logic',
    creationAllowed: true,
    comingSoon: false,
    // Default port plus dynamic case handles resolved by the mapping layer
    // from config.cases (each row declares an outgoing edge port).
    sourcePorts: [DEFAULT_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({
      id,
      type: 'SWITCH',
      name: 'Switch',
      expression: 'trigger.severity',
      config: { cases: [] },
    }),
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
  PARALLEL: {
    type: 'PARALLEL',
    label: 'Parallel',
    labelKey: 'soar.nodeType.PARALLEL',
    description: 'Fan-out to branches',
    descriptionKey: 'soar.nodeTypeDesc.PARALLEL',
    tone: 'control',
    creationAllowed: true,
    comingSoon: false,
    sourcePorts: [DEFAULT_PORT, SUCCESS_PORT, FAILURE_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'PARALLEL', name: 'Parallel', limits: { maxParallelism: 2 } }),
  },
  JOIN: {
    type: 'JOIN',
    label: 'Join',
    labelKey: 'soar.nodeType.JOIN',
    description: 'Deterministic fan-in',
    descriptionKey: 'soar.nodeTypeDesc.JOIN',
    tone: 'control',
    creationAllowed: true,
    comingSoon: false,
    sourcePorts: [DEFAULT_PORT, SUCCESS_PORT, FAILURE_PORT, ERROR_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'JOIN', name: 'Join', strategy: 'ALL_SUCCESS' }),
  },
  FOREACH: {
    type: 'FOREACH',
    label: 'For each',
    labelKey: 'soar.nodeType.FOREACH',
    description: 'Bounded collection loop',
    descriptionKey: 'soar.nodeTypeDesc.FOREACH',
    tone: 'control',
    creationAllowed: true,
    comingSoon: false,
    // Exactly the tokens SoarGraphValidator.validateEdgePort accepts for
    // FOREACH (default/body/each/done/success): a token without a handle is
    // silently undrawable, so the registry must not miss one.
    sourcePorts: [DEFAULT_PORT, BODY_PORT, EACH_PORT, DONE_PORT, SUCCESS_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({
      id,
      type: 'FOREACH',
      name: 'For each',
      config: { itemsPath: 'vars.items', itemVariable: 'vars.item' },
      limits: { concurrency: 1, maxItems: 100 },
    }),
  },
  DELAY: {
    type: 'DELAY',
    label: 'Delay',
    labelKey: 'soar.nodeType.DELAY',
    description: 'Temporal timer',
    descriptionKey: 'soar.nodeTypeDesc.DELAY',
    tone: 'wait',
    creationAllowed: true,
    comingSoon: false,
    // validateEdgePort allows DELAY only default/success.
    sourcePorts: [DEFAULT_PORT, SUCCESS_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'DELAY', name: 'Delay', config: { durationSeconds: 60 } }),
  },
  MANUAL_TASK: {
    type: 'MANUAL_TASK',
    label: 'Manual task',
    labelKey: 'soar.nodeType.MANUAL_TASK',
    description: 'Structured analyst input',
    descriptionKey: 'soar.nodeTypeDesc.MANUAL_TASK',
    tone: 'human',
    creationAllowed: true,
    comingSoon: false,
    // validateEdgePort allows MANUAL_TASK default/completed/success/timeout.
    sourcePorts: [DEFAULT_PORT, COMPLETED_PORT, SUCCESS_PORT, TIMEOUT_PORT],
    acceptsTarget: true,
    // formSchema stays at the node top level: the validator, the workflow engine
    // and the golden templates all read `node.formSchema` (never config.formSchema).
    defaultCreate: (id) => ({
      id,
      type: 'MANUAL_TASK',
      name: 'Manual task',
      formSchema: { type: 'object', properties: {}, required: [] },
      config: { timeoutSeconds: 86400 },
    }),
  },
  SUB_PLAYBOOK: {
    type: 'SUB_PLAYBOOK',
    label: 'Sub-playbook',
    labelKey: 'soar.nodeType.SUB_PLAYBOOK',
    description: 'Published child version',
    descriptionKey: 'soar.nodeTypeDesc.SUB_PLAYBOOK',
    tone: 'control',
    creationAllowed: true,
    comingSoon: false,
    sourcePorts: [DEFAULT_PORT, SUCCESS_PORT, FAILURE_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'SUB_PLAYBOOK', name: 'Sub-playbook', playbookVersionId: '' }),
  },
  SET_VARIABLE: {
    type: 'SET_VARIABLE',
    label: 'Set variable',
    labelKey: 'soar.nodeType.SET_VARIABLE',
    description: 'Write vars.* only',
    descriptionKey: 'soar.nodeTypeDesc.SET_VARIABLE',
    tone: 'data',
    creationAllowed: true,
    comingSoon: false,
    // validateEdgePort allows SET_VARIABLE only default/success.
    sourcePorts: [DEFAULT_PORT, SUCCESS_PORT],
    acceptsTarget: true,
    defaultCreate: (id) => ({ id, type: 'SET_VARIABLE', name: 'Set variable', config: { name: 'vars.note', value: '' } }),
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

/**
 * `onError` values the execution engine actually honours per node type
 * (SoarWorkflowImpl: ACTION 308-320, JOIN 364-372, FOREACH 415). Offering a
 * value the engine ignores would ship a definition that validates but behaves
 * differently at runtime.
 */
export const ON_ERROR_VALUES: Record<string, readonly string[]> = {
  ACTION: ['FAIL_RUN', 'CONTINUE', 'GOTO_ERROR_PORT', 'COMPENSATE_THEN_FAIL'],
  JOIN: ['FAIL_RUN', 'CONTINUE', 'GOTO_ERROR_PORT'],
  FOREACH: ['FAIL_RUN', 'CONTINUE'],
}

export function supportsOnError(type: string): boolean {
  return Object.prototype.hasOwnProperty.call(ON_ERROR_VALUES, type)
}

/** Reads `onError` in either accepted spelling (`SoarDefinitionValidator:274-280`). */
export function readOnError(raw: EditorNode): string {
  const top = raw.onError
  if (typeof top === 'string' && top.trim()) return top
  const config = raw.config
  const nested = config && typeof config === 'object' && !Array.isArray(config)
    ? (config as Record<string, unknown>).onError : undefined
  return typeof nested === 'string' ? nested : ''
}

/** Writes `onError` back into the spelling the node already uses. */
export function writeOnError(raw: EditorNode, value: string): void {
  const trimmed = value.trim()
  const top = raw.onError
  const config = raw.config && typeof raw.config === 'object' && !Array.isArray(raw.config)
    ? { ...(raw.config as Record<string, unknown>) } : null
  const usesConfigSpelling = !(typeof top === 'string' && top.trim()) && Boolean(config && typeof config.onError === 'string')
  if (usesConfigSpelling && config) {
    if (trimmed) config.onError = trimmed
    else delete config.onError
    raw.config = config
    return
  }
  if (trimmed) raw.onError = trimmed
  else delete raw.onError
}

/**
 * Node fields the validator accepts independently of the node type
 * (`SoarDefinitionValidator` reads `onError` on ACTION/JOIN/FOREACH, either at
 * the node top level or under `config`). A type change must keep them.
 */
const TYPE_AGNOSTIC_FIELDS: readonly string[] = ['onError']

/**
 * Body for an existing node after an explicit type change: the target type's
 * creation template plus identity and the type-agnostic policy fields.
 *
 * Fields owned by the previous type (`expression`, `parameters`, `target`,
 * `retry`, `outcome`, `strategy`, type-specific `config`/`limits` keys, …) are
 * deliberately dropped: the new type does not read them and leaving them behind
 * misleads both the operator and the validator.
 */
export function retypeNode(raw: EditorNode, type: string): EditorNode {
  const meta = nodeTypeMeta(type)
  const template = meta ? meta.defaultCreate(raw.id) : ({ id: raw.id, type } as EditorNode)
  const next: EditorNode = { ...template, id: raw.id, type }
  const name = typeof raw.name === 'string' && raw.name.trim() ? raw.name : template.name
  if (name) next.name = name
  for (const field of TYPE_AGNOSTIC_FIELDS) {
    if (raw[field] !== undefined) next[field] = raw[field]
  }
  // `config.onError` is the alternate spelling accepted by the validator.
  const rawConfig = raw.config
  if (rawConfig && typeof rawConfig === 'object' && !Array.isArray(rawConfig) && 'onError' in rawConfig) {
    const config = next.config && typeof next.config === 'object' && !Array.isArray(next.config)
      ? { ...(next.config as Record<string, unknown>) } : {}
    config.onError = (rawConfig as Record<string, unknown>).onError
    next.config = config
  }
  return next
}
