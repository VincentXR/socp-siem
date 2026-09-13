import { describe, expect, it, vi } from 'vitest'
import { ref, shallowRef } from 'vue'
import type { VueFlowStore } from '@vue-flow/core'
import { useDefinitionFlow } from '../useDefinitionFlow'
import type { EditorDefinition } from '../types'

/** Minimal Vue Flow store double: the composable only needs these members. */
function storeDouble(): VueFlowStore {
  const noop = vi.fn()
  return {
    nodes: ref([]),
    edges: ref([]),
    vueFlowRef: shallowRef(undefined),
    getSelectedNodes: ref([]),
    getSelectedEdges: ref([]),
    setNodes: noop,
    setEdges: noop,
    addNodes: noop,
    removeSelectedElements: noop,
    addSelectedNodes: noop,
    findNode: vi.fn(() => undefined),
    fitView: vi.fn(async () => undefined),
    onConnect: noop,
    onNodeDragStop: noop,
    onNodeClick: noop,
    onPaneClick: noop,
    onNodesChange: noop,
    onEdgesChange: noop,
  } as unknown as VueFlowStore
}

function definition(): EditorDefinition {
  return {
    schemaVersion: 'soar.playbook',
    entryNodeId: 'start',
    nodes: [
      { id: 'start', type: 'START', name: 'Start' },
      { id: 'cond', type: 'CONDITION', name: 'Gate', expression: "trigger.severity == 'HIGH'" },
      { id: 'end', type: 'END', name: 'Done', outcome: 'SUCCEEDED' },
    ],
    edges: [
      { from: 'start', to: 'cond' },
      { from: 'cond', to: 'end', port: 'true' },
    ],
  }
}

describe('canvas connection validity', () => {
  it('keeps every stored edge valid, because Vue Flow drops the edges it rejects', () => {
    const flow = useDefinitionFlow(storeDouble())
    flow.applyDefinition(definition())

    // createGraphEdges() runs this predicate for each edge it builds and skips
    // the edge on false — rejecting a stored edge hides its connection line.
    expect(flow.isValidConnection({ source: 'start', target: 'cond', sourceHandle: 'default', targetHandle: 'in' } as never)).toBe(true)
    expect(flow.isValidConnection({ source: 'cond', target: 'end', sourceHandle: 'true', targetHandle: 'in' } as never)).toBe(true)
  })

  it('still rejects connections the definition may not contain', () => {
    const flow = useDefinitionFlow(storeDouble())
    flow.applyDefinition(definition())

    // self loop
    expect(flow.isValidConnection({ source: 'cond', target: 'cond', sourceHandle: 'true' } as never)).toBe(false)
    // END may not have an outgoing edge
    expect(flow.isValidConnection({ source: 'end', target: 'start', sourceHandle: 'default' } as never)).toBe(false)
    // CONDITION only declares true/false ports
    expect(flow.isValidConnection({ source: 'cond', target: 'start', sourceHandle: 'failure' } as never)).toBe(false)
  })
})
