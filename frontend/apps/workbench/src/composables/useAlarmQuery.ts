import { computed, ref } from 'vue'
import { useRequest } from './useRequest'
import { listAlarmsPaged, type AlarmPage, type AlarmSortField, type AlarmSortOrder } from '../api'

export function useAlarmQuery() {
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
    void loadAlarmPage()
  }

  function onAlarmSortChange(field: AlarmSortField, order: AlarmSortOrder) {
    alarmSort.value = field
    alarmOrder.value = order
    alarmPageNum.value = 1
    void loadAlarmPage()
  }

  return {
    alarmSeverity, alarmKeyword, alarmStatus, alarmRule,
    alarmPageNum, alarmPageSize, alarmPageData, filteredAlarms,
    loadAlarmPage, onAlarmSearch, onAlarmSortChange,
  }
}
