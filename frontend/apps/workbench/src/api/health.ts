import { get, isAbortError, type ApiRequestOptions } from './core'

export async function checkHealth(path: string, options: ApiRequestOptions = {}): Promise<'up' | 'down'> {
  try {
    const body = await get<{ status?: string }>(path, { ...options, timeoutMs: options.timeoutMs ?? 3000, unwrap: false })
    return String(body?.status ?? '').toUpperCase() === 'UP' ? 'up' : 'down'
  } catch (error) {
    if (isAbortError(error)) throw error
    return 'down'
  }
}

export const HEALTH_TARGETS = [
  { name: 'alert-web', path: '/alert-web/actuator/health' },
  { name: 'search-config', path: '/search-config/actuator/health' },
  { name: 'detect-web', path: '/detect-web/actuator/health' },
  { name: 'detect-model', path: '/detect-model/actuator/health' },
  { name: 'soar-web', path: '/soar-web/actuator/health' },
  { name: 'report-web', path: '/report-web/actuator/health' },
  { name: 'asset-web', path: '/asset-web/actuator/health' },
  { name: 'soc-base', path: '/soc-base/actuator/health' },
  { name: 'hips-web', path: '/hips-web/actuator/health' },
  { name: 'ai-assistant', path: '/ai-assistant/actuator/health' },
  { name: 'threat-web', path: '/threat-web/actuator/health' },
  { name: 'attack-web', path: '/attack-web/actuator/health' },
  { name: 'notify-web', path: '/notify-web/actuator/health' },
  { name: 'incident-web', path: '/incident-web/actuator/health' },
  { name: 'api-gateway', path: '/actuator/health' },
]
