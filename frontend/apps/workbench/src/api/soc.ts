import { get, post, type ApiRequestOptions } from './core'
import type { TenantInfo } from './models'

export const listTenants = () => get<TenantInfo[]>('/soc-base/api/v1/tenants')
export const socOverview = () => get<Record<string, unknown>>('/soc-base/api/v1/overview')
export const complianceFrameworks = (options?: ApiRequestOptions) => get<{ frameworks: Array<{ name: string; controls: Array<{ id: string; name: string; ruleIds: string[] }> }> }>('/soc-base/api/v1/compliance/frameworks', options)
export const complianceCoverage = (ruleIds: string[], options?: ApiRequestOptions) => post<{
  byFramework: Array<{ framework: string; controls: Array<{ id: string; name: string; covered: boolean; mappedRules: string[]; assessment?: string; owner?: string; validUntil?: string; evidence?: Record<string, unknown> }>; coverage: number }>
  totalControls: number; coveredControls: number; coverage: number; contentVersion?: string; generatedAt?: string
}>('/soc-base/api/v1/compliance/coverage', { ruleIds }, options)
