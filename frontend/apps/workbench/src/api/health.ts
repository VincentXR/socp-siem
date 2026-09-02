import { get, type ApiRequestOptions } from './core'

export type HealthStatus = 'up' | 'down'

export interface HealthSnapshot {
  status: HealthStatus
  services: Record<string, HealthStatus>
  checkedAt: string
}

export const getHealthSnapshot = (options?: ApiRequestOptions) =>
  get<HealthSnapshot>('/api/v1/system/health', options)

export const HEALTH_TARGETS = [
  { name: 'alert-web' },
  { name: 'search-config' },
  { name: 'detect-web' },
  { name: 'detect-model' },
  { name: 'soar-web' },
  { name: 'report-web' },
  { name: 'asset-web' },
  { name: 'soc-base' },
  { name: 'hips-web' },
  { name: 'ai-assistant' },
  { name: 'threat-web' },
  { name: 'attack-web' },
  { name: 'notify-web' },
  { name: 'incident-web' },
  { name: 'api-gateway' },
]
