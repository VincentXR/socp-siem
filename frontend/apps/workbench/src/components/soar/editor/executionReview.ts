/** The declared action plan is reviewable without executing a connector.
 * Expressions remain explicit: this is not a claim of resolved runtime effects. */
export function declaredEffects(definition: unknown): unknown[] {
  if (!definition || typeof definition !== 'object') return []
  const nodes = (definition as { nodes?: unknown }).nodes
  if (!Array.isArray(nodes)) return []
  return nodes.slice(0, 200).filter(node => node && typeof node === 'object' && ['ACTION', 'SUB_PLAYBOOK'].includes(String(node.type)))
    .map(node => ({ node: node.name || node.id, action: node.actionRef || node.playbookVersionId, target: node.target ?? {}, parameters: node.parameters ?? {} }))
}
