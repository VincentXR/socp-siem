import { describe, expect, it } from 'vitest'
import {
  mergeRunHighlights,
  preferredRunStatus,
  runStatusTone,
  summarizeRunHighlights,
  type NodeRunHighlight,
  type RunHighlightRow,
} from '../runHighlight'
import { buildFlowNodes, completePositions, normalizeDefinition } from '../useDefinitionFlow'
import type { EditorDefinition } from '../types'

function definition(): EditorDefinition {
  return normalizeDefinition({
    schemaVersion: 'soar.playbook',
    entryNodeId: 'start',
    nodes: [
      { id: 'start', type: 'START', name: 'Start' },
      { id: 'block', type: 'ACTION', name: 'Block host', actionRef: 'socp.alert/get@1' },
      { id: 'end', type: 'END', name: 'Done', outcome: 'SUCCEEDED' },
    ],
    edges: [
      { from: 'start', to: 'block' },
      { from: 'block', to: 'end' },
    ],
  })
}

describe('runStatusTone', () => {
  it('maps the recognised node statuses to tones', () => {
    expect(runStatusTone('SUCCEEDED')).toBe('succeeded')
    expect(runStatusTone('FAILED')).toBe('failed')
    expect(runStatusTone('ACTION_UNKNOWN')).toBe('unknown')
    expect(runStatusTone('TIMED_OUT')).toBe('timeout')
    expect(runStatusTone('RUNNING')).toBe('running')
    expect(runStatusTone('CANCELLED')).toBe('cancelled')
    expect(runStatusTone('CANCELLING')).toBe('cancelled')
    expect(runStatusTone('SUPPRESSED')).toBe('suppressed')
    expect(runStatusTone('WAITING_APPROVAL')).toBe('waiting')
    expect(runStatusTone('WAITING_INPUT')).toBe('waiting')
  })

  it('keeps the current look for unknown/missing statuses', () => {
    expect(runStatusTone('UNKNOWN')).toBeUndefined()
    expect(runStatusTone('QUEUED')).toBeUndefined()
    expect(runStatusTone('')).toBeUndefined()
    expect(runStatusTone(undefined)).toBeUndefined()
    expect(runStatusTone(null)).toBeUndefined()
  })
})

describe('mergeRunHighlights', () => {
  const rows: RunHighlightRow[] = [
    { nodeId: 'block', status: 'SUCCEEDED', iterationPath: '' },
    { nodeId: 'block', status: 'SUCCEEDED', iterationPath: '0' },
    { nodeId: 'block', status: 'FAILED', iterationPath: '0/1' },
    { nodeId: 'end', status: 'SUCCEEDED', iterationPath: '' },
  ]

  it('collapses rows per definition nodeId keeping order + row count', () => {
    const merged = mergeRunHighlights(rows)
    expect(Object.keys(merged).sort()).toEqual(['block', 'end'])

    const block = merged.block
    expect(block.statuses).toEqual(['SUCCEEDED', 'FAILED'])
    expect(block.iterationPaths).toEqual(['', '0', '0/1'])
    expect(block.rowCount).toBe(3)
    expect(merged.end.rowCount).toBe(1)
  })

  it('treats a missing/null iterationPath as the default path', () => {
    const merged = mergeRunHighlights([{ nodeId: 'a', status: 'SUCCEEDED', iterationPath: null }])
    expect(merged.a.iterationPaths).toEqual([''])
  })

  it('ignores malformed rows and returns {} for empty/null input', () => {
    expect(mergeRunHighlights([{ nodeId: '', status: 'FAILED' }, { nodeId: 'b', status: '' }]))
      .toEqual({})
    expect(mergeRunHighlights([])).toEqual({})
    expect(mergeRunHighlights(null)).toEqual({})
    expect(mergeRunHighlights(undefined)).toEqual({})
  })
})

describe('preferredRunStatus', () => {
  it('lets the worst recognised state win for the badge', () => {
    expect(preferredRunStatus(['SUCCEEDED', 'FAILED'])).toBe('FAILED')
    expect(preferredRunStatus(['RUNNING', 'SUCCEEDED'])).toBe('RUNNING')
    expect(preferredRunStatus(['TIMED_OUT', 'CANCELLED'])).toBe('TIMED_OUT')
    expect(preferredRunStatus(['WAITING_APPROVAL', 'SUCCEEDED'])).toBe('WAITING_APPROVAL')
  })

  it('keeps the badge off when every status is unrecognised', () => {
    expect(preferredRunStatus(['UNKNOWN', 'QUEUED'])).toBeUndefined()
    expect(preferredRunStatus([])).toBeUndefined()
    expect(preferredRunStatus(undefined)).toBeUndefined()
  })
})

describe('summarizeRunHighlights', () => {
  it('groups by tone with counts and drops unrecognised rows', () => {
    const map: Record<string, NodeRunHighlight> = {
      a: { statuses: ['SUCCEEDED'], iterationPaths: [''], rowCount: 1 },
      b: { statuses: ['FAILED', 'SUCCEEDED'], iterationPaths: ['', '0'], rowCount: 2 },
      c: { statuses: ['RUNNING'], iterationPaths: ['0'], rowCount: 1 },
      d: { statuses: ['UNKNOWN'], iterationPaths: [''], rowCount: 1 },
    }
    const summary = summarizeRunHighlights(map)
    expect(summary.map(entry => entry.tone)).toEqual(['succeeded', 'failed', 'running'])
    expect(summary[1]).toMatchObject({ tone: 'failed', count: 1 })
    expect(summary[1].statuses).toContain('FAILED')
    // the merged node keeps its own distinct status list in the legend bucket
    expect(summary[1].statuses).toEqual(['FAILED', 'SUCCEEDED'])
  })

  it('returns an empty list when nothing is highlighted', () => {
    expect(summarizeRunHighlights({})).toEqual([])
    expect(summarizeRunHighlights(null)).toEqual([])
    expect(summarizeRunHighlights(undefined)).toEqual([])
  })
})

describe('buildFlowNodes + run highlights', () => {
  it('attaches run status fields only when a recognised row matched', () => {
    const def = definition()
    const positions = completePositions(def)
    const highlights: Record<string, NodeRunHighlight> = {
      block: { statuses: ['FAILED'], iterationPaths: [''], rowCount: 1 },
      end: { statuses: ['UNKNOWN'], iterationPaths: [''], rowCount: 1 },
    }
    const nodes = buildFlowNodes(def, positions, {}, highlights)
    const byId = new Map(nodes.map(node => [node.id, node.data]))
    expect(byId.get('block')).toMatchObject({ runStatus: 'FAILED', runIterations: 1, runIterationPaths: [''] })
    // UNKNOWN is outside the recognised set -> node keeps its current look
    expect(byId.get('end')?.runStatus).toBeUndefined()
    expect(byId.get('start')?.runStatus).toBeUndefined()
  })

  it('stays highlight-free without a run highlight map', () => {
    const def = definition()
    const nodes = buildFlowNodes(def, completePositions(def))
    for (const node of nodes) {
      expect(node.data.runStatus).toBeUndefined()
      expect(node.data.runIterations).toBeUndefined()
    }
  })
})
