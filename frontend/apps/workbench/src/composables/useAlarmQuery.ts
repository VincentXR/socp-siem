import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useRequest } from './useRequest'
import { listAlarmsPaged, type AlarmPage, type AlarmSortField, type AlarmSortOrder } from '../api'

// Structural stand-ins for the vue-router objects so the composable can be
// exercised with plain fakes in tests (useListQuery convention) while the
// real call site keeps the zero-argument form.
export interface AlarmQueryRoute {
  name: unknown
  query: Record<string, unknown>
}

export interface AlarmQueryRouter {
  replace: (to: { query: Record<string, unknown> }) => unknown
}

export interface AlarmQueryParams {
  page: number
  size: number
  q?: string
  severity?: string
  status?: string
  rule?: string
  sort: AlarmSortField
  order: AlarmSortOrder
  signal: AbortSignal
}

export interface AlarmQueryOptions {
  route?: AlarmQueryRoute
  router?: AlarmQueryRouter
  fetchPage?: (params: AlarmQueryParams) => Promise<AlarmPage>
}

const SORT_FIELDS: string[] = ['occurredAt', 'severity', 'ruleName', 'entity', 'status', 'riskScore']

export function useAlarmQuery(options: AlarmQueryOptions = {}) {
  const route = options.route ?? (useRoute() as unknown as AlarmQueryRoute)
  const router = options.router ?? (useRouter() as unknown as AlarmQueryRouter)
  const fetchPage = options.fetchPage ?? (params => listAlarmsPaged(
    params.page,
    params.size,
    params.q,
    params.severity,
    params.status,
    params.rule,
    params.sort,
    params.order,
    { signal: params.signal },
  ))
  const alarmSeverity = ref('')
  const alarmKeyword = ref('')
  const alarmStatus = ref('')
  const alarmRule = ref('')
  const alarmSort = ref<AlarmSortField>('occurredAt')
  const alarmOrder = ref<AlarmSortOrder>('descending')
  const alarmPageNum = ref(1)
  const alarmPageSize = ref(10)
  const emptyPage: AlarmPage = { items: [], total: 0, page: 1, size: 10, totalPages: 0 }
  const request = useRequest<AlarmPage>(emptyPage)
  const alarmPageData = computed(() => request.data.value ?? emptyPage)
  const filteredAlarms = computed(() => alarmPageData.value.items)

  let applyingRouteQuery = false

  function readRouteQuery(): void {
    if (route.name !== 'alarms') return
    const query = route.query
    alarmKeyword.value = typeof query.q === 'string' ? query.q : ''
    alarmSeverity.value = typeof query.severity === 'string' ? query.severity : ''
    alarmStatus.value = typeof query.status === 'string' ? query.status : ''
    alarmRule.value = typeof query.rule === 'string' ? query.rule : ''
    // Integers only, matching useListQuery: '2.5' must not reach the request
    // (the backend @RequestParam Integer answers it with a 500) or the URL.
    const nextPage = Number(query.page)
    alarmPageNum.value = Number.isInteger(nextPage) && nextPage >= 1 ? nextPage : 1
    alarmPageSize.value = [10, 20, 50, 100].includes(Number(query.size)) ? Number(query.size) : 10
    const sort = String(query.sort || '')
    alarmSort.value = 'occurredAt'
    if (SORT_FIELDS.includes(sort)) {
      alarmSort.value = sort as AlarmSortField
    }
    const order = String(query.order || '')
    alarmOrder.value = 'descending'
    if (order === 'ascending' || order === 'descending') alarmOrder.value = order
  }

  function writeRouteQuery(): void {
    if (applyingRouteQuery || route.name !== 'alarms') return
    // Start from the live query so unmanaged but functionally-consumed keys
    // (alarmId deep links in particular) survive; managed keys clear to
    // undefined instead of being dropped silently.
    const query: Record<string, unknown> = { ...route.query }
    query.q = alarmKeyword.value.trim() || undefined
    query.severity = alarmSeverity.value || undefined
    query.status = alarmStatus.value || undefined
    query.rule = alarmRule.value.trim() || undefined
    query.page = alarmPageNum.value > 1 ? String(alarmPageNum.value) : undefined
    query.size = alarmPageSize.value !== 10 ? String(alarmPageSize.value) : undefined
    query.sort = alarmSort.value !== 'occurredAt' ? alarmSort.value : undefined
    query.order = alarmOrder.value !== 'descending' ? alarmOrder.value : undefined
    void router.replace({ query })
  }

  readRouteQuery()
  watch(() => route.query, () => {
    if (route.name !== 'alarms') return
    applyingRouteQuery = true
    readRouteQuery()
    // The [page,size] watcher below is pre-flush and runs in the same tick as
    // this one; resetting the echo guard synchronously here let it observe the
    // just-read values and re-issue a replace() that clobbered the route we
    // were applying. nextTick keeps the guard up for that watcher.
    void nextTick(() => { applyingRouteQuery = false })
    void loadAlarmPage()
  }, { deep: true })

  watch([alarmPageNum, alarmPageSize], () => {
    if (!applyingRouteQuery && route.name === 'alarms') writeRouteQuery()
  })

  async function loadAlarmPage() {
    await request.execute(signal => fetchPage({
      page: alarmPageNum.value,
      size: alarmPageSize.value,
      q: alarmKeyword.value.trim() || undefined,
      severity: alarmSeverity.value || undefined,
      status: alarmStatus.value || undefined,
      rule: alarmRule.value.trim() || undefined,
      sort: alarmSort.value,
      order: alarmOrder.value,
      signal,
    }))
  }

  function onAlarmSearch() {
    alarmPageNum.value = 1
    writeRouteQuery()
    void loadAlarmPage()
  }

  function onAlarmSortChange(field: AlarmSortField, order: AlarmSortOrder) {
    alarmSort.value = field
    alarmOrder.value = order
    alarmPageNum.value = 1
    writeRouteQuery()
    void loadAlarmPage()
  }

  return {
    alarmSeverity, alarmKeyword, alarmStatus, alarmRule,
    alarmPageNum, alarmPageSize, alarmSort, alarmOrder, alarmPageData, filteredAlarms,
    loading: request.loading, error: request.error,
    loadAlarmPage, onAlarmSearch, onAlarmSortChange,
  }
}
