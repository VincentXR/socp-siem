import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import SoarFlowPropertyPanel from '../SoarFlowPropertyPanel.vue'
import type { EditorNode } from '../types'
import type { SoarFlowApi } from '../useDefinitionFlow'

vi.mock('../../../../api', () => ({
  listActions: vi.fn(async () => [{
    id: 'socp.alert/get', actionRef: 'socp.alert/get@1', connectorId: 'socp.alert', connectorVersion: 1,
    production: true, displayName: 'Get alert', riskLevel: 'LOW', sideEffect: 'READ',
    idempotency: 'NATURAL', requiresConnection: false,
  }]),
  listConnections: vi.fn(async () => ({ items: [] })),
  listPlaybooks: vi.fn(async () => ({ items: [] })),
  listVersions: vi.fn(async () => []),
}))

/** Minimal flow API double: the panel only reads the graph and reports edits. */
function flowDouble(definition: { nodes: EditorNode[] }) {
  const touched = vi.fn()
  return {
    touched,
    api: {
      graphRevision: ref(1),
      getDefinition: () => definition,
      touchAfterNodeEdit: touched,
      refreshPorts: vi.fn(),
      updateNodeType: vi.fn(),
      removeNode: vi.fn(),
      nodeIssues: () => ({ errors: [], warnings: [] }),
    } as unknown as SoarFlowApi,
  }
}

function mountPanel(node: EditorNode) {
  const definition = { nodes: [node] }
  const { api, touched } = flowDouble(definition)
  const wrapper = mount(SoarFlowPropertyPanel, {
    // Element Plus selects recurse in jsdom without layout; the panel logic
    // under test (writes into the raw node) does not depend on them.
    global: { stubs: { teleport: true, ElSelect: true, ElOption: true, VariableSelector: true } },
    props: { flow: api, node },
  })
  return { wrapper, node, touched }
}

describe('SOAR property panel edits', () => {
  it('never binds an action just because an ACTION node was selected', async () => {
    const { wrapper, node, touched } = mountPanel({ id: 'act', type: 'ACTION', name: 'Block host', actionRef: '' })
    await flushPromises()
    // The catalog loads for the picker, but selecting a node must not write to
    // the definition (that used to bind the first action and dirty the draft).
    expect(node.actionRef).toBe('')
    expect(touched).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('writes nested numeric limits as numbers, not strings', async () => {
    const { wrapper, node } = mountPanel({
      id: 'loop', type: 'FOREACH', name: 'For each',
      config: { itemsPath: 'vars.items', itemVariable: 'vars.item' },
      limits: { concurrency: 1, maxItems: 100 },
    })
    await flushPromises()
    const concurrency = wrapper.findAll('.soar-flow-retry-grid input[type="number"]')[0]
    await concurrency.setValue('3')
    expect(node.limits).toMatchObject({ concurrency: 3, maxItems: 100 })
    expect(typeof (node.limits as Record<string, unknown>).concurrency).toBe('number')
    wrapper.unmount()
  })
})
