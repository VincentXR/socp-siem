import { describe, expect, it } from 'vitest'
import { diffDefinitions } from '../definitionDiff'
import type { EditorDefinition } from '../types'

function baseDefinition(): EditorDefinition {
  return {
    schemaVersion: 'soar.playbook',
    entryNodeId: 'start',
    nodes: [
      { id: 'start', type: 'START', name: 'Start' },
      { id: 'act', type: 'ACTION', name: 'Block host', actionRef: 'socp.alert/get@1', parameters: { host: '1.2.3.4' } },
      { id: 'end', type: 'END', name: 'Done', outcome: 'SUCCEEDED' },
    ],
    edges: [
      { from: 'start', to: 'act' },
      { from: 'act', to: 'end', port: 'success' },
    ],
  }
}

describe('definition diff (draft vs published)', () => {
  it('reports identical definitions as unchanged', () => {
    const result = diffDefinitions(baseDefinition(), JSON.parse(JSON.stringify(baseDefinition())))
    expect(result.unchanged).toBe(true)
    expect(result.nodes).toEqual([])
    expect(result.edges).toEqual([])
  })

  it('detects added, removed and changed nodes with field-level detail', () => {
    const after = baseDefinition()
    after.nodes = after.nodes.filter(node => node.id !== 'end')
    const action = after.nodes.find(node => node.id === 'act')!
    action.name = 'Quarantine host'
    ;(action as Record<string, unknown>).severity = 'HIGH'
    delete (action as Record<string, unknown>).parameters
    after.nodes.push({ id: 'sw', type: 'SWITCH', name: 'Switch', expression: 'trigger.severity' })
    after.edges = after.edges.filter(edge => edge.to !== 'end')
    after.edges.push({ from: 'act', to: 'sw', port: 'success' })

    const result = diffDefinitions(baseDefinition(), after)

    const changed = result.nodes.find(row => row.id === 'act')!
    expect(changed.change).toBe('changed')
    expect(changed.fields.map(field => field.field).sort()).toEqual(['name', 'parameters', 'severity'])
    const nameDiff = changed.fields.find(field => field.field === 'name')!
    expect(nameDiff.before).toContain('Block host')
    expect(nameDiff.after).toContain('Quarantine host')

    expect(result.nodes.find(row => row.id === 'end')?.change).toBe('removed')
    expect(result.nodes.find(row => row.id === 'sw')?.change).toBe('added')

    expect(result.edges.some(row => row.change === 'removed' && row.port === 'success')).toBe(true)
    expect(result.edges.some(row => row.change === 'added' && row.to === 'sw')).toBe(true)
    expect(result.unchanged).toBe(false)
  })

  it('treats a rewired branch port as remove plus add', () => {
    const after = baseDefinition()
    after.edges = [{ from: 'start', to: 'act' }, { from: 'act', to: 'end', port: 'failure' }]
    const result = diffDefinitions(baseDefinition(), after)
    expect(result.edges.filter(row => row.change === 'removed').map(row => row.port)).toEqual(['success'])
    expect(result.edges.filter(row => row.change === 'added').map(row => row.port)).toEqual(['failure'])
  })
})
