import { describe, expect, it, vi } from 'vitest'
import { ref, shallowRef } from 'vue'
import type { VueFlowStore } from '@vue-flow/core'
import { useDefinitionFlow } from '../useDefinitionFlow'
import type { EditorDefinition } from '../types'

const confirmDanger = vi.hoisted(() => vi.fn())
vi.mock('../../../../composables/useConfirm', () => ({
  useConfirm: () => ({ confirmDanger }),
}))

/** Minimal Vue Flow store double, plus capture of the removal handler the canvas uses. */
function storeDouble(selected: () => unknown[] = () => []) {
  const noop = vi.fn()
  let nodesChange: ((changes: { type: string; id: string }[]) => void) | undefined
  const store = {
    nodes: ref([]),
    edges: ref([]),
    vueFlowRef: shallowRef(undefined),
    getSelectedNodes: ref(selected()) as never,
    getSelectedEdges: ref([]),
    setNodes: noop,
    setEdges: noop,
    addNodes: vi.fn(),
    removeSelectedElements: noop,
    addSelectedNodes: noop,
    findNode: vi.fn(() => undefined),
    fitView: vi.fn(async () => undefined),
    onConnect: noop,
    onNodeDragStop: noop,
    onNodeClick: noop,
    onPaneClick: noop,
    onNodesChange: (handler: typeof nodesChange) => { nodesChange = handler },
    onEdgesChange: noop,
  } as unknown as VueFlowStore & { emitRemoval: (id: string) => void }
  store.emitRemoval = (id: string) => nodesChange?.([{ type: 'remove', id }])
  return store
}

/** `LEGACY_HOOK` is loadable but not a palette type, so deletion needs a confirm. */
function definition(): EditorDefinition {
  return {
    schemaVersion: 'soar.playbook',
    entryNodeId: 'start',
    nodes: [
      { id: 'start', type: 'START', name: 'Start' },
      { id: 'legacy', type: 'LEGACY_HOOK', name: 'Retired action' },
      { id: 'end', type: 'END', name: 'Done', outcome: 'SUCCEEDED' },
    ],
    edges: [{ from: 'start', to: 'legacy' }, { from: 'legacy', to: 'end' }],
  } as unknown as EditorDefinition
}

function ids(flowNodes: { id: string }[]): string[] {
  return flowNodes.map(node => node.id)
}

describe('read-only node deletion confirmation', () => {
  it('keeps the node when the operator cancels the property-panel delete', async () => {
    const flow = useDefinitionFlow(storeDouble())
    flow.applyDefinition(definition())
    confirmDanger.mockResolvedValueOnce(false)
    await flow.removeNode('legacy')
    expect(ids(flow.getDefinition().nodes)).toContain('legacy')
    expect(confirmDanger).toHaveBeenCalledTimes(1)
  })

  it('removes the node and its edges once the operator confirms', async () => {
    const flow = useDefinitionFlow(storeDouble())
    flow.applyDefinition(definition())
    confirmDanger.mockResolvedValueOnce(true)
    await flow.removeNode('legacy')
    expect(ids(flow.getDefinition().nodes)).not.toContain('legacy')
    expect(flow.getDefinition().edges.every(edge => edge.from !== 'legacy' && edge.to !== 'legacy')).toBe(true)
  })

  it('does not prompt a creatable node type at all', async () => {
    const flow = useDefinitionFlow(storeDouble())
    flow.applyDefinition(definition())
    await flow.removeNode('end')
    expect(ids(flow.getDefinition().nodes)).not.toContain('end')
    expect(confirmDanger).not.toHaveBeenCalled()
  })

  it('refuses a second concurrent request instead of stacking message boxes', async () => {
    const flow = useDefinitionFlow(storeDouble())
    flow.applyDefinition(definition())
    let release: ((value: boolean) => void) | undefined
    confirmDanger.mockImplementationOnce(() => new Promise<boolean>(resolve => { release = resolve }))
    const first = flow.removeNode('legacy')
    const second = flow.removeNode('legacy')
    await expect(second).resolves.toBeUndefined()
    expect(confirmDanger).toHaveBeenCalledTimes(1)
    release?.(false)
    await first
    // Both the refused duplicate and the cancelled original leave the node alone.
    expect(ids(flow.getDefinition().nodes)).toContain('legacy')
  })

  it('keeps a cancelled canvas selection delete intact', async () => {
    const store = storeDouble(() => [{ id: 'legacy', data: { raw: { name: 'Retired action' } } }])
    const flow = useDefinitionFlow(store)
    flow.applyDefinition(definition())
    confirmDanger.mockResolvedValueOnce(false)
    await flow.deleteSelection()
    expect(ids(flow.getDefinition().nodes)).toContain('legacy')
  })

  it('puts the node back on the canvas before awaiting a keyboard removal confirm', async () => {
    const store = storeDouble()
    const flow = useDefinitionFlow(store)
    flow.applyDefinition(definition())
    confirmDanger.mockResolvedValueOnce(false)
    store.emitRemoval('legacy')
    // Vue Flow already dropped it, so the restore is queued ahead of the prompt.
    await vi.waitFor(() => expect(confirmDanger).toHaveBeenCalledTimes(1))
    expect(store.addNodes).toHaveBeenCalledWith(expect.arrayContaining([expect.objectContaining({ id: 'legacy' })]))
    await vi.waitFor(() => expect(ids(flow.getDefinition().nodes)).toContain('legacy'))
  })
})
