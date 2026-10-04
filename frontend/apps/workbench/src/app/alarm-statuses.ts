/**
 * Alarm disposition vocabulary, kept aligned with
 * `com.socp.alert.domain.AlarmState` on the server. The server answers 400 for an
 * unknown value and 409 for one that is legal vocabulary but not reachable from
 * the alarm's current state, so this list is a UI affordance and not the rule.
 */
export const ALARM_DISP_STATUSES: readonly string[] = [
  'OPEN',
  'INVESTIGATING',
  'RESOLVED',
  'CLOSED',
  'SUPPRESSED',
]

/** Server-side pending-triage scope (OPEN + INVESTIGATING); never a storable state. */
export const ALARM_STATUS_ACTIVE = 'ACTIVE'

/**
 * Explicit "no status filter" choice. The queue defaults to ACTIVE, so "everything"
 * needs a value of its own: an empty selection is dropped from the URL and would
 * otherwise snap back to ACTIVE on the next read.
 */
export const ALARM_STATUS_ALL = 'ALL'

/** Query-only ALL must never reach either list or export predicates. */
export const alarmStatusFilter = (status?: string) => status === ALARM_STATUS_ALL ? undefined : status || undefined

const transitions: Record<string, readonly string[]> = {
  OPEN: ['INVESTIGATING', 'RESOLVED', 'CLOSED', 'SUPPRESSED'],
  INVESTIGATING: ['OPEN', 'RESOLVED', 'CLOSED', 'SUPPRESSED'],
  RESOLVED: ['INVESTIGATING', 'CLOSED'],
  CLOSED: ['INVESTIGATING'],
  SUPPRESSED: ['INVESTIGATING', 'CLOSED'],
}
/** Compatibility for older servers; the service remains authoritative. */
export const alarmTransitionOptions = (status: string) => transitions[status] ? [status, ...transitions[status]] : []

export const ALARM_FILTER_STATUSES: readonly string[] = [
  ALARM_STATUS_ACTIVE,
  ALARM_STATUS_ALL,
  ...ALARM_DISP_STATUSES,
]
