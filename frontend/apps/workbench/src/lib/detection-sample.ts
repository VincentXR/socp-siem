import type { SearchEvent } from '../api/models'

// A single, short-lived in-memory handoff. Log bodies never enter URLs or browser storage.
let staged: { token: string; expiresAt: number; sample: SearchEvent } | undefined
export function stageDetectionSample(sample: SearchEvent): string {
  const json = JSON.stringify(sample)
  if (new TextEncoder().encode(json).byteLength > 65_536) throw new Error('Detection sample exceeds 64 KiB')
  const token = crypto.randomUUID()
  staged = { token, expiresAt: Date.now() + 300_000, sample: JSON.parse(json) as SearchEvent }
  return token
}
export function takeDetectionSample(token: string): SearchEvent | undefined {
  const current = staged
  if (!current || current.token !== token) return undefined
  staged = undefined
  return current.expiresAt >= Date.now() ? current.sample : undefined
}
