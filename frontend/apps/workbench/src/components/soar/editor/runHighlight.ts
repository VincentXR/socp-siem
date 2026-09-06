/**
 * Run-path highlight model for the SOAR V2 graph editor (Slice 4).
 *
 * The backend run-detail endpoint returns `/runs/{id}/nodes` rows with at least
 * `nodeId`, `nodeType`, `status`, `iterationPath`. A single definition node can
 * execute several times (FOREACH body, PARALLEL branches, retries), producing
 * one row per (nodeId, iterationPath) pair, so the editor merges the rows of a
 * run back onto the *definition* node shown on the canvas.
 *
 * Everything in this module is a pure function so the merge / representative /
 * legend logic can be unit-tested without mounting the Vue Flow editor.
 */

/** Visual bucket used by SoarFlowNode and the legend overlay. */
export type RunStatusTone =
  | 'succeeded'
  | 'failed'
  | 'unknown'
  | 'timeout'
  | 'running'
  | 'waiting'
  | 'cancelled'
  | 'suppressed'

/** One `/runs/{id}/nodes` row projected for the frontend. */
export interface RunHighlightRow {
  nodeId: string
  status: string
  iterationPath?: string | null
}

/** Merged per-definition-node run state (several node-run rows may collapse here). */
export interface NodeRunHighlight {
  /** Distinct statuses in first-seen order. */
  statuses: string[]
  /** Distinct iteration paths in first-seen order ('' is the default path). */
  iterationPaths: string[]
  /** Number of node-run rows merged onto this node. */
  rowCount: number
}

/** Legend entry: one tone bucket + how many nodes share it. */
export interface RunToneSummary {
  tone: RunStatusTone
  statuses: string[]
  count: number
}

/** Playbook version + node statuses requested by an external run inspector. */
export interface RunOpenRequest {
  /** Opaque token so repeated identical requests are still processed. */
  token: string
  playbookId: string
  /** The exact immutable version the run executed. */
  version: number
  /** Node-run rows projected from `/runs/{id}/nodes`. */
  rows: RunHighlightRow[]
}

/**
 * Canonical status -> tone table. Only statuses listed here get a ring/chip;
 * anything else (e.g. an unresolved literal `UNKNOWN`, a blank, or a future
 * backend value) keeps the current node look by design.
 */
const STATUS_TONE: Record<string, RunStatusTone> = {
  SUCCEEDED: 'succeeded',
  FAILED: 'failed',
  DEAD: 'failed',
  ACTION_UNKNOWN: 'unknown',
  TIMED_OUT: 'timeout',
  RUNNING: 'running',
  CANCELLED: 'cancelled',
  CANCELLING: 'cancelled',
  SUPPRESSED: 'suppressed',
}

/** Waiting statuses all share the waiting tone regardless of their suffix. */
const WAITING_PREFIX = 'WAITING_'

export function runStatusTone(status: string | null | undefined): RunStatusTone | undefined {
  if (!status) return undefined
  const upper = String(status).toUpperCase()
  if (upper in STATUS_TONE) return STATUS_TONE[upper]
  if (upper.startsWith(WAITING_PREFIX)) return 'waiting'
  return undefined
}

/** Severity used to pick the badge status when a node ran several times (lower = worse). */
const STATUS_SEVERITY: Record<string, number> = {
  FAILED: 0,
  DEAD: 0,
  TIMED_OUT: 1,
  ACTION_UNKNOWN: 2,
  CANCELLED: 3,
  CANCELLING: 3,
  SUPPRESSED: 4,
  RUNNING: 5,
  SUCCEEDED: 7,
}

function severityRank(status: string): number {
  const upper = String(status).toUpperCase()
  if (upper in STATUS_SEVERITY) return STATUS_SEVERITY[upper]
  if (upper.startsWith(WAITING_PREFIX)) return 6
  return Number.MAX_SAFE_INTEGER
}

/**
 * Picks the status the user should see first for a definition node that ran
 * multiple times (worst state wins). Returns `undefined` when no row carries a
 * recognised run status, so unhighlighted nodes keep their current look.
 */
export function preferredRunStatus(statuses: readonly string[] | undefined | null): string | undefined {
  if (!statuses || !statuses.length) return undefined
  let best: string | undefined
  let bestRank = Number.MAX_SAFE_INTEGER
  for (const status of statuses) {
    const rank = severityRank(status)
    if (rank < bestRank) {
      bestRank = rank
      best = status
    }
  }
  return best !== undefined && bestRank !== Number.MAX_SAFE_INTEGER ? best : undefined
}

/**
 * Groups `/runs/{id}/nodes` rows by definition `nodeId`, keeping the distinct
 * statuses / iteration paths in first-seen order plus a row count. Passing an
 * empty/null list returns an empty map so callers can use it to clear.
 */
export function mergeRunHighlights(
  rows: readonly RunHighlightRow[] | null | undefined,
): Record<string, NodeRunHighlight> {
  const merged: Record<string, NodeRunHighlight> = {}
  for (const row of rows ?? []) {
    const nodeId = row?.nodeId
    const status = row?.status
    if (!nodeId || !status) continue
    let entry = merged[nodeId]
    if (!entry) {
      entry = { statuses: [], iterationPaths: [], rowCount: 0 }
      merged[nodeId] = entry
    }
    if (!entry.statuses.includes(status)) entry.statuses.push(status)
    const path = typeof row.iterationPath === 'string' && row.iterationPath.length ? row.iterationPath : ''
    if (!entry.iterationPaths.includes(path)) entry.iterationPaths.push(path)
    entry.rowCount += 1
  }
  return merged
}

/** Stable order for legend rendering. */
export const RUN_TONE_ORDER: readonly RunStatusTone[] = [
  'succeeded',
  'failed',
  'unknown',
  'timeout',
  'running',
  'waiting',
  'cancelled',
  'suppressed',
]

/**
 * Summarises an active highlight map into legend buckets (only tones that are
 * actually visible on the canvas, in the canonical tone order).
 */
export function summarizeRunHighlights(
  map: Readonly<Record<string, NodeRunHighlight>> | null | undefined,
): RunToneSummary[] {
  const byTone = new Map<RunStatusTone, { statuses: string[]; count: number }>()
  for (const entry of Object.values(map ?? {})) {
    const status = preferredRunStatus(entry.statuses)
    const tone = status ? runStatusTone(status) : undefined
    if (!tone) continue
    let bucket = byTone.get(tone)
    if (!bucket) {
      bucket = { statuses: [], count: 0 }
      byTone.set(tone, bucket)
    }
    bucket.count += 1
    for (const rowStatus of entry.statuses) {
      if (!bucket.statuses.includes(rowStatus)) bucket.statuses.push(rowStatus)
    }
  }
  return RUN_TONE_ORDER
    .filter(tone => byTone.has(tone))
    .map(tone => {
      const bucket = byTone.get(tone)!
      return { tone, statuses: bucket.statuses, count: bucket.count }
    })
}
