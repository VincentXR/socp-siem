import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { buildFlowEdges, buildFlowNodes, completePositions, normalizeDefinition } from '../src/components/soar/editor/useDefinitionFlow'
import { SOAR_NODE_REGISTRY } from '../src/components/soar/editor/nodeRegistry'
import type { SoarNodeType } from '../src/components/soar/editor/types'

/**
 * Port vocabulary the backend validator accepts, transcribed from
 * `SoarGraphValidator.validateEdgePort` (services/soar-web/.../definition/
 * SoarGraphValidator.java:34-65). The validator also accepts the literal
 * spelling "default"; the editor serializes that branch as an empty token, so
 * the empty string is the expected entry here. A token that only exists on one
 * side is a bug: a missing handle makes Vue Flow drop the edge (invisible
 * connection line), an extra handle lets the canvas build a definition the
 * validator rejects.
 */
const VALIDATOR_PORTS: Partial<Record<SoarNodeType, string[]>> = {
  ACTION: ['', 'success', 'failure', 'error', 'unknown'],
  CONDITION: ['true', 'false'],
  JOIN: ['', 'success', 'failure', 'error'],
  FOREACH: ['', 'body', 'each', 'done', 'success'],
  DELAY: ['', 'success'],
  SET_VARIABLE: ['', 'success'],
  APPROVAL: ['approved', 'rejected'],
  MANUAL_TASK: ['', 'completed', 'success', 'timeout'],
  SUB_PLAYBOOK: ['', 'success', 'failure'],
}

const TEMPLATE_DIR = join(import.meta.dirname, '../../../../services/soar-web/src/main/resources/soar/templates')

function templateFiles(): string[] {
  return readdirSync(TEMPLATE_DIR).filter(name => name.endsWith('.json'))
}

function templateDefinition(file: string) {
  const raw = JSON.parse(readFileSync(join(TEMPLATE_DIR, file), 'utf8')) as { definition?: unknown }
  return normalizeDefinition(raw.definition)
}

describe('SOAR port vocabulary', () => {
  for (const [type, allowed] of Object.entries(VALIDATOR_PORTS) as Array<[SoarNodeType, string[]]>) {
    it(`mirrors the validator tokens for ${type}`, () => {
      const registry = SOAR_NODE_REGISTRY[type].sourcePorts.map(port => port.token).sort()
      expect(registry).toEqual([...allowed].sort())
    })
  }

  it('draws every edge of every golden template on a handle that exists', () => {
    const files = templateFiles()
    expect(files.length).toBeGreaterThan(0)
    for (const file of files) {
      const definition = templateDefinition(file)
      const nodes = buildFlowNodes(definition, completePositions(definition))
      const handlesById = new Map(nodes.map(node => [
        node.id,
        node.data.sourcePorts.map(port => (port.token === '' ? 'default' : port.token)),
      ]))
      const edges = buildFlowEdges(definition)
      expect(edges.length).toBe(definition.edges.length)
      for (const edge of edges) {
        const handles = handlesById.get(edge.source)
        expect(handles, `${file}: edge ${edge.source} → ${edge.target}`).toBeDefined()
        expect(handles, `${file}: edge ${edge.source} → ${edge.target} (${String(edge.label ?? '')})`)
          .toContain(edge.sourceHandle)
      }
    }
  })

  it('anchors an unknown branch token on the default handle instead of losing the line', () => {
    const definition = normalizeDefinition({
      schemaVersion: 'soar.playbook',
      entryNodeId: 'start',
      nodes: [
        { id: 'start', type: 'START', name: 'Start' },
        { id: 'loop', type: 'FOREACH', name: 'Loop' },
        { id: 'end', type: 'END', name: 'Done', outcome: 'SUCCEEDED' },
      ],
      edges: [
        { from: 'start', to: 'loop' },
        { from: 'loop', to: 'end', port: 'not-a-real-port' },
      ],
    })
    const edges = buildFlowEdges(definition)
    expect(edges[1].label).toBe('not-a-real-port')
    expect(edges[1].sourceHandle).toBe('default')
  })
})
