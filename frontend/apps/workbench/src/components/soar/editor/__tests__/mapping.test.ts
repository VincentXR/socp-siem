import { describe, expect, it } from 'vitest'
import {
  buildFlowEdges,
  buildFlowNodes,
  canonicalKey,
  completePositions,
  edgePortKey,
  effectiveEntryNodeId,
  fallbackPosition,
  normalizeDefinition,
  serializeLayout,
} from '../useDefinitionFlow'
import { mapIssuesToNodes } from '../validation'
import { SOAR_NODE_REGISTRY, CREATION_TYPES } from '../nodeRegistry'
import type { EditorDefinition, EditorNode, ValidationIssue } from '../types'

/** Golden-template-like definition with CONDITION true/false + APPROVAL edges and a read-only type. */
function sampleDefinition(): EditorDefinition {
  return {
    schemaVersion: 'soar.playbook/v2',
    entryNodeId: 'start',
    limits: { maxNodeExecutions: 500, maxParallelism: 10 },
    nodes: [
      { id: 'start', type: 'START', name: 'Start' },
      { id: 'cond', type: 'CONDITION', name: 'Severity gate', expression: "trigger.severity == 'HIGH'" },
      { id: 'action', type: 'ACTION', name: 'Block host', actionRef: 'socp.alert/get@1', parameters: { host: '{{event.host}}' }, target: {} },
      { id: 'approval', type: 'APPROVAL', name: 'Review', config: { timeoutSeconds: 86400, requiredApprovals: 2 } },
      { id: 'legacy', type: 'SWITCH', name: 'Legacy switch', config: { cases: ['a', 'b'] } },
      { id: 'end', type: 'END', name: 'Done', outcome: 'SUCCEEDED' },
    ],
    edges: [
      { from: 'start', to: 'cond' },
      { from: 'cond', to: 'action', when: 'true' },
      { from: 'cond', to: 'approval', when: 'false' },
      { from: 'action', to: 'approval' },
      { from: 'approval', to: 'end', port: 'approved' },
      { from: 'legacy', to: 'end', port: 'case1' },
    ],
  }
}

describe('definition <-> Vue Flow mapping', () => {
  it('round-trips a definition untouched after load -> no-op edit -> save', () => {
    const input = sampleDefinition()
    const wireCopy = JSON.parse(JSON.stringify(input)) as EditorDefinition

    const definition = normalizeDefinition(wireCopy)
    const layout = { nodes: input.nodes.map((node, index) => {
      const base = fallbackPosition(index)
      return { id: node.id, x: base.x, y: base.y }
    }) }
    const positions = completePositions(definition, layout)

    // load -> (nothing edited) -> save: definition bytes are identical
    definition.entryNodeId = effectiveEntryNodeId(definition)
    expect(JSON.parse(JSON.stringify(definition))).toEqual(input)

    // layout save payload has the identical shape/values we loaded
    expect(serializeLayout(positions)).toEqual(layout)

    // Vue Flow bridge derivation stays consistent
    const flowNodes = buildFlowNodes(definition, positions)
    expect(flowNodes.map(node => node.id)).toEqual(input.nodes.map(node => node.id))
    expect(flowNodes.find(node => node.id === 'legacy')?.data.unsupported).toBe(true)
    expect(flowNodes.find(node => node.id === 'start')?.connectable).toBe(false)
    expect(flowNodes.find(node => node.id === 'cond')?.connectable).toBe(true)
  })

  it('translates CONDITION true/false and APPROVAL port tokens onto source handles', () => {
    const definition = normalizeDefinition(sampleDefinition())
    const positions = completePositions(definition)
    const edges = buildFlowEdges(definition)

    const byPair = new Map(edges.map(edge => [`${edge.source}->${edge.target}`, edge]))
    expect(byPair.get('start->cond')?.sourceHandle).toBe('default')
    expect(byPair.get('cond->action')?.sourceHandle).toBe('true')
    expect(byPair.get('cond->approval')?.sourceHandle).toBe('false')
    expect(byPair.get('approval->end')?.sourceHandle).toBe('approved')

    // a `when`-spelled CONDITION branch keeps its raw spelling byte-for-byte
    const conditionBranch = definition.edges.find(edge => edge.from === 'cond' && edge.to === 'action')
    expect(conditionBranch).toMatchObject({ from: 'cond', to: 'action', when: 'true' })
    expect(conditionBranch?.port).toBeUndefined()
    expect(edgePortKey(conditionBranch!)).toBe('true')

    // unsupported nodes anchor dynamic source handles but are not deletable
    const legacy = definition.nodes.find(node => node.id === 'legacy')!
    const legacyOut = edges.find(edge => edge.source === 'legacy')
    expect(legacyOut?.sourceHandle).toBe('case1')
    expect(legacyOut?.deletable).toBe(false)
    const legacyNode = buildFlowNodes(definition, positions).find(node => node.id === 'legacy')!
    expect(legacyNode.data.sourcePorts.map(port => port.token)).toContain('case1')
    expect(legacyNode.connectable).toBe(false)

    void SOAR_NODE_REGISTRY
  })

  it('records fallback grid positions for nodes missing a layout entry', () => {
    const definition = normalizeDefinition(sampleDefinition())
    const positions = completePositions(definition, { nodes: [{ id: 'start', x: 5, y: 5 }] })
    expect(positions.start).toEqual({ x: 5, y: 5 })
    expect(positions.cond).toEqual(fallbackPosition(1))
    expect(positions.end).toEqual(fallbackPosition(5))
  })

  it('unlocks engine-backed control-flow types for creation with semantic ports', () => {
    expect(CREATION_TYPES).toEqual(expect.arrayContaining(['PARALLEL', 'JOIN', 'FOREACH', 'SUB_PLAYBOOK']))
    for (const type of ['PARALLEL', 'JOIN', 'FOREACH', 'SUB_PLAYBOOK'] as const) {
      expect(SOAR_NODE_REGISTRY[type].comingSoon).toBe(false)
      expect(SOAR_NODE_REGISTRY[type].creationAllowed).toBe(true)
    }
    const tokens = SOAR_NODE_REGISTRY.FOREACH.sourcePorts.map(port => port.token)
    expect(tokens).toContain('body')
    expect(tokens).toContain('done')

    // an engine-backed type is now an editable, connectable node
    const definition = normalizeDefinition({
      schemaVersion: 'soar.playbook/v2',
      entryNodeId: 'start',
      nodes: [
        { id: 'start', type: 'START', name: 'Start' },
        { id: 'p', type: 'PARALLEL', name: 'Fan-out', limits: { maxParallelism: 2 } },
        { id: 'end', type: 'END', name: 'Done', outcome: 'SUCCEEDED' },
      ],
      edges: [],
    })
    const positions = completePositions(definition)
    const node = buildFlowNodes(definition, positions).find(item => item.id === 'p')!
    expect(node.data.unsupported).toBe(false)
    expect(node.connectable).toBe(true)
    expect(node.deletable).toBe(true)
  })

  it('canonical key ignores array order but notices definition and layout edits', () => {
    const definition = normalizeDefinition(sampleDefinition())
    const positions = completePositions(definition)
    const keyA = canonicalKey(definition, positions)

    const reversed = normalizeDefinition(sampleDefinition())
    reversed.nodes.reverse()
    reversed.edges.reverse()
    expect(canonicalKey(reversed, positions)).toBe(keyA)

    definition.nodes.push({ id: 'extra', type: 'ACTION', name: 'Extra' })
    expect(canonicalKey(definition, positions)).not.toBe(keyA)
  })
})

describe('mapIssuesToNodes', () => {
  const nodes = [
    { id: 'start', type: 'START' },
    { id: 'cond', type: 'CONDITION', name: 'Gate' },
    { id: 'end', type: 'END' },
  ]

  it('attaches issues by nodeId and by /nodes/<index> path', () => {
    const issues: ValidationIssue[] = [
      { severity: 'ERROR', code: 'MISSING_ACTION', nodeId: 'cond', message: 'cond needs action' },
      { severity: 'ERROR', code: 'EDGE_DUPLICATE', nodeId: 'end', path: '/nodes/2/edges/0', message: 'dup' },
      { severity: 'WARNING', code: 'WARN', nodeId: 'missing', path: '/nodes/1/name', message: 'indexed warning' },
      { severity: 'ERROR', code: 'DOC_SCOPE', message: 'no node here' },
    ]
    const mapped = mapIssuesToNodes(issues, nodes as EditorNode[])
    expect(mapped.cond.errors).toHaveLength(1)
    expect(mapped.cond.codeSet.has('MISSING_ACTION')).toBe(true)
    expect(mapped.end.errors[0].code).toBe('EDGE_DUPLICATE')
    // /nodes/1 resolves to node index 1 (cond) even when nodeId names an unknown id
    expect(mapped.cond.warnings[0].code).toBe('WARN')
    expect(mapped.cond.warnings[0].severity).toBe('WARNING')
    // document-scoped issue has no border highlight
    expect(Object.keys(mapped)).toEqual(expect.not.arrayContaining(['DOC_SCOPE']))
  })
})
