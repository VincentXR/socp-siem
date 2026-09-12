import { describe, expect, it } from 'vitest'
import { createHistory, type EditorHistorySnapshot } from '../history'
import type { EditorDefinition, LayoutPayload } from '../types'

function baseDefinition(): EditorDefinition {
  return {
    schemaVersion: 'soar.playbook',
    entryNodeId: 'start',
    nodes: [
      { id: 'start', type: 'START', name: 'Start' },
      { id: 'end', type: 'END', name: 'Done', outcome: 'SUCCEEDED' },
    ],
    edges: [{ from: 'start', to: 'end' }],
  }
}

/** Returns a deep-copied definition carrying the given extra node ids in order. */
function definitionWith(...extraIds: string[]): EditorDefinition {
  const def = baseDefinition()
  def.nodes.push(...extraIds.map((id, index) => ({ id, type: 'ACTION', name: `Node ${index}` })))
  return JSON.parse(JSON.stringify(def)) as EditorDefinition
}

function layoutFor(def: EditorDefinition): LayoutPayload {
  return {
    nodes: def.nodes.map((node, index) => ({ id: node.id, x: index * 240, y: 0 })),
  }
}

function nodeIds(snapshot: EditorHistorySnapshot | null): string[] {
  return (snapshot?.definition.nodes ?? []).map(node => node.id)
}

describe('createHistory', () => {
  it('orders undo/redo around recorded states and reports canUndo/canRedo', () => {
    const history = createHistory(20)

    history.record(baseDefinition(), layoutFor(baseDefinition()))
    expect(history.canUndo()).toBe(false)
    expect(history.canRedo()).toBe(false)

    history.record(definitionWith('action-a'), layoutFor(definitionWith('action-a')))
    history.record(definitionWith('action-a', 'action-b'), layoutFor(definitionWith('action-a', 'action-b')))
    expect(history.canUndo()).toBe(true)
    expect(history.canRedo()).toBe(false)

    const undoB = history.undo()
    expect(nodeIds(undoB)).toEqual(['start', 'end', 'action-a'])
    expect(history.canUndo()).toBe(true)
    expect(history.canRedo()).toBe(true)

    const undoA = history.undo()
    expect(nodeIds(undoA)).toEqual(['start', 'end'])
    expect(history.canUndo()).toBe(false)
    expect(history.undo()).toBeNull()

    const redoA = history.redo()
    expect(nodeIds(redoA)).toEqual(['start', 'end', 'action-a'])
    const redoB = history.redo()
    expect(nodeIds(redoB)).toEqual(['start', 'end', 'action-a', 'action-b'])
    expect(history.canRedo()).toBe(false)
    expect(history.redo()).toBeNull()
  })

  it('evicts the oldest snapshot when the bounded stack overflows', () => {
    const history = createHistory(3)
    for (let step = 0; step < 6; step += 1) {
      const ids = Array.from({ length: step }, (_, index) => `action-${index}`)
      history.record(definitionWith(...ids), layoutFor(definitionWith(...ids)))
    }

    // Only the last three records survive; the three oldest are evicted.
    const undo1 = history.undo()
    expect(nodeIds(undo1)).toEqual(['start', 'end', 'action-0', 'action-1', 'action-2', 'action-3'])
    const undo2 = history.undo()
    expect(nodeIds(undo2)).toEqual(['start', 'end', 'action-0', 'action-1', 'action-2'])
    expect(history.canUndo()).toBe(false)
    expect(history.undo()).toBeNull()
  })

  it('keeps recorded snapshots isolated from later mutation of the live state', () => {
    const history = createHistory(20)

    const live = baseDefinition()
    history.record(live, layoutFor(live))

    // Mutating the live object after record() must not leak into the snapshot.
    live.nodes.push({ id: 'leak', type: 'ACTION', name: 'Leak' })

    const second = definitionWith('action-x')
    history.record(second, layoutFor(second))

    const restored = history.undo()
    expect(nodeIds(restored)).toEqual(['start', 'end'])
  })

  it('isolates the redo snapshot from mutations applied to the undone state', () => {
    const history = createHistory(20)
    history.record(baseDefinition(), layoutFor(baseDefinition()))
    history.record(definitionWith('action-x'), layoutFor(definitionWith('action-x')))

    const undone = history.undo()
    expect(nodeIds(undone)).toEqual(['start', 'end'])

    // Mutating the restored live state must not corrupt the redo branch.
    undone?.definition.nodes.push({ id: 'polluted', type: 'ACTION', name: 'Polluted' })

    const redone = history.redo()
    expect(nodeIds(redone)).toEqual(['start', 'end', 'action-x'])
  })

  it('clear() drops every state and the stack can be reused afterwards', () => {
    const history = createHistory(20)
    history.record(baseDefinition(), layoutFor(baseDefinition()))
    history.record(definitionWith('action-x'), layoutFor(definitionWith('action-x')))
    expect(history.canUndo()).toBe(true)

    history.clear()
    expect(history.canUndo()).toBe(false)
    expect(history.canRedo()).toBe(false)
    expect(history.undo()).toBeNull()
    expect(history.redo()).toBeNull()

    history.record(definitionWith('action-y'), layoutFor(definitionWith('action-y')))
    expect(history.canUndo()).toBe(false)
    expect(history.redo()).toBeNull()
  })

  it('record() after undo truncates the redo branch', () => {
    const history = createHistory(20)
    history.record(baseDefinition(), layoutFor(baseDefinition()))
    history.record(definitionWith('action-a'), layoutFor(definitionWith('action-a')))
    history.record(definitionWith('action-a', 'action-b'), layoutFor(definitionWith('action-a', 'action-b')))

    history.undo() // -> action-a state
    expect(history.canRedo()).toBe(true)

    history.record(definitionWith('action-c'), layoutFor(definitionWith('action-c')))
    expect(history.canRedo()).toBe(false)
    expect(history.redo()).toBeNull()

    const undone = history.undo()
    expect(nodeIds(undone)).toEqual(['start', 'end', 'action-a'])
  })
})
