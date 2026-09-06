/**
 * Validator-error -> node-border mapping.
 *
 * `validateV2Version` returns issues where each entry is
 * `{ severity: 'ERROR'|'WARNING', code, nodeId, path, message }`. `nodeId` may
 * be null for document-scope errors; `path` may name a nodes-array index.
 */
import type { EditorNode, NodeIssueState, ValidationIssue } from './types'

/**
 * Maps backend issues onto existing node ids.
 *  1. issue.nodeId naming an existing node id wins;
 *  2. otherwise an issue.path like `/nodes/3/name` resolves to the node at that
 *     array index;
 *  3. otherwise the issue is document-scoped and gets no border highlight.
 */
export function mapIssuesToNodes(
  issues: readonly ValidationIssue[] | undefined | null,
  nodes: readonly EditorNode[],
): Record<string, NodeIssueState> {
  const byId = new Map<string, EditorNode>()
  for (const node of nodes) byId.set(node.id, node)

  const byIndex = new Map<number, EditorNode>()
  nodes.forEach((node, index) => byIndex.set(index, node))

  const stateByNodeId = new Map<string, NodeIssueState>()

  const ensure = (nodeId: string): NodeIssueState => {
    let entry = stateByNodeId.get(nodeId)
    if (!entry) {
      entry = { errors: [], warnings: [], codeSet: new Set<string>() }
      stateByNodeId.set(nodeId, entry)
    }
    return entry
  }

  for (const issue of issues ?? []) {
    const resolved = resolveIssueNodeId(issue, byId, byIndex)
    if (!resolved) continue
    const state = ensure(resolved)
    if (issue.code) state.codeSet.add(issue.code)
    if (issue.severity === 'WARNING') state.warnings.push(issue)
    else state.errors.push(issue)
  }
  return Object.fromEntries(stateByNodeId)
}

function resolveIssueNodeId(
  issue: ValidationIssue,
  byId: Map<string, EditorNode>,
  byIndex: Map<number, EditorNode>,
): string | null {
  if (issue.nodeId && byId.has(issue.nodeId)) return issue.nodeId
  const match = issue.path ? /^\/nodes\/(\d+)(?:\/|$)/.exec(issue.path) : null
  if (match) {
    const indexed = byIndex.get(Number(match[1]))
    if (indexed) return indexed.id
  }
  return null
}

export function totalIssueCount(issues: readonly ValidationIssue[] | undefined | null): number {
  return issues?.length ?? 0
}

export function emptyNodeIssueState(): NodeIssueState {
  return { errors: [], warnings: [], codeSet: new Set<string>() }
}
