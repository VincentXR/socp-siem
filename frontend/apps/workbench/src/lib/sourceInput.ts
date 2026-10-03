import type { LogSource } from '../api/models'
import type { LogSourceInput } from '../api/search'
/** Preserve every source option when changing an explicit binding. */
export function sourceInput(source: LogSource): LogSourceInput {
  return {
    name: source.name, type: source.type, format: source.format, enabled: source.enabled,
    path: source.path ?? null, address: source.address ?? null, topic: source.topic ?? null,
    env: source.env ?? null, readFrom: source.readFrom ?? null, multiline: source.multiline ?? null,
    sinkTargetId: source.sinkTargetId ?? null, parseRuleIds: source.parseRuleIds ?? [],
    description: source.description ?? null, protocol: source.protocol ?? null,
    charset: source.charset ?? null, timeField: source.timeField ?? null, timezone: source.timezone ?? null,
    tags: source.tags ?? [], frequency: source.frequency ?? null,
    ignoreOlderSeconds: source.ignoreOlderSeconds ?? null, categoryId: source.categoryId ?? null,
    groupId: source.groupId ?? null,
  }
}
