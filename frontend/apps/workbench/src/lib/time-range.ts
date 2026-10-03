/** datetime-local uses the operator's local zone; URLs and APIs always use UTC instants. */
export function localDateTime(instant: string): string {
  const value = new Date(instant)
  if (!Number.isFinite(value.getTime())) return ''
  return new Date(value.getTime() - value.getTimezoneOffset() * 60_000).toISOString().slice(0, 23)
}

export function utcInstant(value: string): string {
  const time = Date.parse(value)
  return Number.isFinite(time) ? new Date(time).toISOString() : ''
}

export function validTimeWindow(from: string, to: string): boolean {
  return !!from && !!to && Number.isFinite(Date.parse(from)) && Number.isFinite(Date.parse(to)) && Date.parse(from) <= Date.parse(to)
}
