import { computed, type Ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { alarmStats, checkHealth, HEALTH_TARGETS, listAlarms } from '../api'

export function useOverview(enabled: Ref<boolean>) {
  const alarmsQuery = useQuery({
    queryKey: ['overview', 'alarms'],
    queryFn: ({ signal }) => listAlarms(undefined, { signal }),
    enabled,
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  })

  const healthQuery = useQuery({
    queryKey: ['overview', 'health'],
    queryFn: async ({ signal }) => {
      const results = await Promise.all(HEALTH_TARGETS.map(target => checkHealth(target.path, { signal, timeoutMs: 3_000 })))
      const map: Record<string, 'up' | 'down'> = {}
      HEALTH_TARGETS.forEach((target, index) => { map[target.name] = results[index] })
      return map
    },
    enabled,
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  })

  const statsQuery = useQuery({
    queryKey: ['overview', 'stats'],
    queryFn: ({ signal }) => alarmStats({ signal }),
    enabled,
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  })

  const alarms = computed(() => alarmsQuery.data.value ?? [])
  const healths = computed(() => healthQuery.data.value ?? {})
  const sitStats = computed(() => statsQuery.data.value ?? null)
  const stat = computed(() => ({
    total: alarms.value.length,
    critical: alarms.value.filter(alarm => alarm.severity === 'CRITICAL').length,
    high: alarms.value.filter(alarm => alarm.severity === 'HIGH').length,
    online: Object.values(healths.value).filter(status => status === 'up').length,
  }))

  async function refreshOverview() {
    await Promise.allSettled([alarmsQuery.refetch(), healthQuery.refetch()])
  }

  async function loadOverviewStats() {
    await statsQuery.refetch().catch(() => undefined)
  }

  return { alarms, healths, sitStats, stat, refreshOverview, loadOverviewStats }
}
