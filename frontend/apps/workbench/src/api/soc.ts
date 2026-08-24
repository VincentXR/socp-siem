import { get, post } from './core'
import type { TenantInfo } from './models'

export const listTenants = () => get<TenantInfo[]>('/soc-base/api/v1/tenants')
export const socOverview = () => get<Record<string, unknown>>('/soc-base/api/v1/overview')
export const complianceFrameworks = () => get<{ frameworks: Array<{ name: string; controls: Array<{ id: string; name: string; ruleIds: string[] }> }> }>('/soc-base/api/v1/compliance/frameworks')
export const complianceCoverage = (ruleIds: string[]) => post<{
  byFramework: Array<{ framework: string; controls: Array<{ id: string; name: string; covered: boolean; mappedRules: string[] }>; coverage: number }>
  totalControls: number; coveredControls: number; coverage: number
}>('/soc-base/api/v1/compliance/coverage', { ruleIds })
