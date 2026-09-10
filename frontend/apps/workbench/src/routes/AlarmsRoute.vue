<script setup lang="ts">
import { computed, inject } from 'vue'
import { useRouter } from 'vue-router'
import AlarmsView from '../views/AlarmsView.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'
import { exportAlarms } from '../api/alarms'

const injectedState = inject(WORKBENCH_STATE)
if (!injectedState) throw new Error('Workbench state is not provided')
const state = injectedState

const query = state.alarmQuery
const alarmKeyword = query.alarmKeyword
const alarmSeverity = query.alarmSeverity
const alarmStatus = query.alarmStatus
const alarmRule = query.alarmRule
const alarmPageNum = query.alarmPageNum
const alarmPageSize = query.alarmPageSize
const filteredAlarms = computed(() => query.filteredAlarms.value)
const alarmPageData = computed(() => query.alarmPageData.value)
const alarmLoading = computed(() => query.loading.value)
const alarmError = computed(() => query.error.value?.message ?? '')
const canWrite = computed(() => ['admin', 'analyst', 'role_admin', 'role_analyst'].includes(state.currentRole.value.toLowerCase()))
const router = useRouter()

function goCase(caseId?: string) {
  if (caseId) void router.push({ name: 'case', query: { caseId } })
  else state.navigate('case')
}
function goSearch() { state.navigate('search') }
function goAi(alarmId: string) {
  void router.push({ name: 'ai', query: { alarmId } })
}
function goSoar(alarmId: string) {
  void router.push({ name: 'soar', query: { alarmId } })
}
function exportWithCurrentFilters(format: 'csv' | 'json') {
  return exportAlarms(format, {
    q: alarmKeyword.value.trim() || undefined,
    severity: alarmSeverity.value || undefined,
    status: alarmStatus.value || undefined,
    rule: alarmRule.value.trim() || undefined,
    sort: query.alarmSort.value,
    order: query.alarmOrder.value,
  })
}
</script>

<template>
  <AlarmsView
    v-model:keyword="alarmKeyword"
    v-model:severity="alarmSeverity"
    v-model:status="alarmStatus"
    v-model:rule="alarmRule"
    v-model:page-num="alarmPageNum"
    :filtered-alarms="filteredAlarms"
    :alarm-page-data="alarmPageData"
    :alarm-page-size="alarmPageSize"
    :loading="alarmLoading"
    :error="alarmError"
    :on-search="query.onAlarmSearch"
    :load-page="query.loadAlarmPage"
    :on-sort-change="query.onAlarmSortChange"
    :export-csv="() => exportWithCurrentFilters('csv')"
    :export-json="() => exportWithCurrentFilters('json')"
    :go-case="goCase"
    :go-search="goSearch"
    :go-ai="goAi"
    :go-soar="goSoar"
    :assignee-options="state.currentUser.value ? [state.currentUser.value] : []"
    :can-write="canWrite"
  />
</template>
