export type ObservationState = 'unknown' | 'loading' | 'healthy' | 'degraded' | 'stale'

/** Missing coverage is never an all-clear. A failed refresh retains data, marked stale. */
export function healthState(services: Record<string, string>, targets: readonly { name: string }[],
  options: { fetching?: boolean; failed?: boolean; updatedAt?: number; now?: number } = {}): ObservationState {
  const known = targets.filter(target => services[target.name] === 'up' || services[target.name] === 'down')
  if (!known.length) return options.fetching ? 'loading' : 'unknown'
  if (options.failed || (options.updatedAt !== undefined && (options.now ?? Date.now()) - options.updatedAt > 90_000)) return 'stale'
  if (known.some(target => services[target.name] === 'down')) return 'degraded'
  return known.length === targets.length ? 'healthy' : 'unknown'
}
