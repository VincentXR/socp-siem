/**
 * Structural diff between two playbook definitions — used by the editor's
 * "compare with published revision" view. Pure functions so the result can be
 * unit-tested without mounting the editor.
 */
import type { EditorDefinition, EditorEdge, EditorNode } from './types'
import { edgePortKey } from './useDefinitionFlow'

export interface NodeFieldDiff {
  field: string
  before: string
  after: string
}

export interface NodeDiffRow {
  id: string
  name: string
  type: string
  change: 'added' | 'removed' | 'changed'
  fields: NodeFieldDiff[]
}

export interface EdgeDiffRow {
  from: string
  to: string
  port: string
  change: 'added' | 'removed'
}

export interface DefinitionDiff {
  nodes: NodeDiffRow[]
  edges: EdgeDiffRow[]
  /** True when neither nodes nor edges differ. */
  unchanged: boolean
}

function jsonValue(value: unknown): string {
  if (value === undefined) return '∅'
  try {
    return JSON.stringify(value) ?? '∅'
  } catch {
    return '∅'
  }
}

function nodeMeta(node: EditorNode): { name: string; type: string } {
  return {
    name: typeof node.name === 'string' && node.name.trim() ? node.name : '',
    type: String(node.type ?? ''),
  }
}

function edgeKey(edge: EditorEdge): string {
  return `${String(edge.from)}\u0000${String(edge.to)}\u0000${edgePortKey(edge)}`
}

/**
 * Diffs `after` (the version being reviewed) against `before` (the baseline,
 * usually the published revision). Nodes are matched by id; edges by
 * from/to/port, so rewiring a branch shows up as remove + add.
 */
export function diffDefinitions(before: EditorDefinition, after: EditorDefinition): DefinitionDiff {
  const beforeById = new Map(before.nodes.map(node => [node.id, node]))
  const afterById = new Map(after.nodes.map(node => [node.id, node]))

  const nodes: NodeDiffRow[] = []
  for (const [id, node] of afterById) {
    const meta = nodeMeta(node)
    if (!beforeById.has(id)) {
      nodes.push({ id, ...meta, change: 'added', fields: [] })
      continue
    }
    const previous = beforeById.get(id)!
    const fields: NodeFieldDiff[] = []
    for (const field of new Set([...Object.keys(previous), ...Object.keys(node)])) {
      if (field === 'id') continue
      const beforeValue = field in previous ? previous[field] : undefined
      const afterValue = field in node ? node[field] : undefined
      if (jsonValue(beforeValue) !== jsonValue(afterValue)) {
        fields.push({ field, before: jsonValue(beforeValue), after: jsonValue(afterValue) })
      }
    }
    if (fields.length) {
      nodes.push({ id, ...meta, name: meta.name || nodeMeta(previous).name, change: 'changed', fields })
    }
  }
  for (const [id, node] of beforeById) {
    if (!afterById.has(id)) nodes.push({ id, ...nodeMeta(node), change: 'removed', fields: [] })
  }

  const beforeEdges = new Map(before.edges.map(edge => [edgeKey(edge), edge]))
  const afterEdges = new Map(after.edges.map(edge => [edgeKey(edge), edge]))
  const edges: EdgeDiffRow[] = []
  for (const [key, edge] of afterEdges) {
    if (!beforeEdges.has(key)) {
      edges.push({ from: String(edge.from), to: String(edge.to), port: edgePortKey(edge), change: 'added' })
    }
  }
  for (const [key, edge] of beforeEdges) {
    if (!afterEdges.has(key)) {
      edges.push({ from: String(edge.from), to: String(edge.to), port: edgePortKey(edge), change: 'removed' })
    }
  }

  return { nodes, edges, unchanged: nodes.length === 0 && edges.length === 0 }
}
