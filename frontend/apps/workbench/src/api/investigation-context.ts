import { get, type ApiRequestOptions } from './core'
import type { AlarmPage, Paged, RuleSpec } from './models'
import { withQuery } from '../lib/query'

/** Bounded, exact technique pivots; the owning services enforce tenant/role scope. */
export const techniqueRules = (technique: string, options?: ApiRequestOptions) =>
  get<Paged<RuleSpec>>(withQuery('/detect-web/api/v1/rules', { technique, page: 1, size: 20 }), options)
export const techniqueAlarms = (technique: string, options?: ApiRequestOptions) =>
  get<AlarmPage>(withQuery('/alert-web/api/alarms', { technique, page: 1, size: 10, sort: 'occurredAt', order: 'descending' }), options)
