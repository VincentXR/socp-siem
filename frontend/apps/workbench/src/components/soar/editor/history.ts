/**
 * Bounded undo/redo history for the SOAR graph editor.
 *
 * The stack stores snapshots of {definition, layout}. record() deep-clones and
 * deep-freezes each snapshot so the live editor model can keep mutating the
 * current raw objects without ever corrupting a past state; undo()/redo()
 * hand back fresh (unfrozen) clones that callers may apply freely.
 */
import type { EditorDefinition, LayoutPayload } from './types'

export interface EditorHistorySnapshot {
  definition: EditorDefinition
  layout: LayoutPayload
}

export interface EditorHistory {
  /** Record a newly committed state (clears the redo tail, evicts the oldest when over the limit). */
  record(definition: EditorDefinition, layout: LayoutPayload): void
  /** Return the previous committed state as a fresh clone, or null. */
  undo(): EditorHistorySnapshot | null
  /** Return the next committed state as a fresh clone, or null. */
  redo(): EditorHistorySnapshot | null
  canUndo(): boolean
  canRedo(): boolean
  /** Drop every recorded state. */
  clear(): void
}

/** Deep clone that works in jsdom/Node and browsers (structuredClone with JSON fallback). */
export function deepClone<T>(value: T): T {
  if (typeof structuredClone === 'function') {
    try {
      return structuredClone(value)
    } catch {
      // Vue's reactive proxies are not structured-cloneable. The editor
      // model is JSON by contract, so retain a deterministic fallback for
      // snapshots created from reactive state.
    }
  }
  return JSON.parse(JSON.stringify(value)) as T
}

function deepFreeze<T>(value: T): T {
  const seen = new WeakSet<object>()
  const walk = (item: unknown): unknown => {
    if (item === null || typeof item !== 'object') return item
    if (seen.has(item)) return item
    seen.add(item)
    Object.freeze(item)
    for (const child of Object.values(item)) walk(child)
    return item
  }
  return walk(value) as T
}

function snapshotClone(snapshot: EditorHistorySnapshot): EditorHistorySnapshot {
  return { definition: deepClone(snapshot.definition), layout: deepClone(snapshot.layout) }
}

export function createHistory(limit: number): EditorHistory {
  const capacity = Number.isFinite(limit) && limit > 0 ? Math.floor(limit) : 100
  const stack: EditorHistorySnapshot[] = []
  let index = -1

  return {
    record(definition, layout) {
      const snapshot: EditorHistorySnapshot = {
        definition: deepFreeze(deepClone(definition)),
        layout: deepFreeze(deepClone(layout)),
      }
      // A new action after undo drops the redo branch.
      if (index < stack.length - 1) stack.splice(index + 1)
      stack.push(snapshot)
      if (stack.length > capacity) stack.shift()
      index = stack.length - 1
    },
    undo() {
      if (index <= 0) return null
      index -= 1
      return snapshotClone(stack[index])
    },
    redo() {
      if (index < 0 || index >= stack.length - 1) return null
      index += 1
      return snapshotClone(stack[index])
    },
    canUndo() {
      return index > 0
    },
    canRedo() {
      return index >= 0 && index < stack.length - 1
    },
    clear() {
      stack.length = 0
      index = -1
    },
  }
}
