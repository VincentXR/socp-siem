import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useRequest } from './useRequest'
import { listAlarmsPaged, type AlarmPage, type AlarmSortField, type AlarmSortOrder } from '../api'

export function useAlarmQuery() {
  const route = useRoute()
  const router = useRouter()
  const alarmSeverity = ref('')
  const alarmKeyword = ref('')
  const alarmStatus = ref('')
  const alarmRule = ref('')
  const alarmSort = ref<AlarmSortField>('occurredAt')
  const alarmOrder = ref<AlarmSortOrder>('descending')
  const alarmPageNum = ref(1)
  const alarmPageSize = ref(10)
  const emptyPage: AlarmPage = { items: [], total: 0, page: 1, size: 10 }
  const request = useRequest<AlarmPage>(emptyPage)
  const alarmPageData = computed(() => request.data.value ?? emptyPage)
  const filteredAlarms = computed(() => alarmPageData.value.items)

  let applyingRouteQuery = false

  function readRouteQuery(): void {
    const query = route.query
    alarmKeyword.value = typeof query.q === 'string' ? query.q : ''
    alarmSeverity.value = typeof query.severity === 'string' ? query.severity : ''
    alarmStatus.value = typeof query.status === 'string' ? query.status : ''
    alarmRule.value = typeof query.rule === 'string' ? query.rule : ''
    alarmPageNum.value = Number.isFinite(Number(query.page)) && Number(query.page) > 0 ? Number(query.page) : 1
    alarmPageSize.value = [10, 20, 50, 100].includes(Number(query.size)) ? Number(query.size) : 10
    const sort = String(query.sort || '')
    alarmSort.value = 'occurredAt'
    if (['occurredAt', 'severity', 'ruleName', 'entity', 'status', 'riskScore'].includes(sort)) {
      alarmSort.value = sort as AlarmSortField
    }
    const order = String(query.order || '')
    alarmOrder.value = 'descending'
    if (order === 'ascending' || order === 'descending') alarmOrder.value = order
  }

  function writeRouteQuery(): void {
    if (applyingRouteQuery || route.name !== 'alarms') return
    const query: Record<string, string> = {}
    if (alarmKeyword.value.trim()) query.q = alarmKeyword.value.trim()
    if (alarmSeverity.value) query.severity = alarmSeverity.value
    if (alarmStatus.value) query.status = alarmStatus.value
    if (alarmRule.value.trim()) query.rule = alarmRule.value.trim()
    if (alarmPageNum.value > 1) query.page = String(alarmPageNum.value)
    if (alarmPageSize.value !== 10) query.size = String(alarmPageSize.value)
    if (alarmSort.value !== 'occurredAt') query.sort = alarmSort.value
    if (alarmOrder.value !== 'descending') query.order = alarmOrder.value
    void router.replace({ query })
  }

  readRouteQuery()
  watch(() => route.query, () => {
    applyingRouteQuery = true
    readRouteQuery()
    applyingRouteQuery = false
    if (route.name === 'alarms') void loadAlarmPage()
  }, { deep: true })

  watch([alarmPageNum, alarmPageSize], () => {
    if (!applyingRouteQuery && route.name === 'alarms') writeRouteQuery()
  })

  async function loadAlarmPage() {
    await request.execute(signal => listAlarmsPaged(
      alarmPageNum.value,
      alarmPageSize.value,
      alarmKeyword.value.trim() || undefined,
      alarmSeverity.value || undefined,
      alarmStatus.value || undefined,
      alarmRule.value.trim() || undefined,
      alarmSort.value,
      alarmOrder.value,
      { signal },
    ))
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
