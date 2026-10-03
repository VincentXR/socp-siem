<script setup lang="ts">
import { computed, inject } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
const alarmAssignee = query.alarmAssignee
const alarmFrom = query.alarmFrom
const alarmTo = query.alarmTo
const alarmPageNum = query.alarmPageNum
const alarmPageSize = query.alarmPageSize
const filteredAlarms = computed(() => query.filteredAlarms.value)
const alarmPageData = computed(() => query.alarmPageData.value)
const alarmLoading = computed(() => query.loading.value)
const alarmError = computed(() => query.error.value?.message ?? '')
const canWrite = computed(() => ['admin', 'analyst', 'role_admin', 'role_analyst'].includes(state.currentRole.value.toLowerCase()))
const canAdmin = computed(() => ['admin', 'role_admin'].includes(state.currentRole.value.toLowerCase()))
const router = useRouter()
const route = useRoute()

function goCase(caseId?: string) {
  if (caseId) void router.push({ name: 'case', query: { caseId, alarmId: String(route.query.alarmId || ''), returnTo: route.fullPath } })
  else state.navigate('case')
}
function goSearch(q?: string) {
  void router.push({ name: 'search', query: q ? { q, range: 'all', alarmId: String(route.query.alarmId || ''), returnTo: route.fullPath } : {} })
}
function goAi(alarmId: string, caseId?: string) {
  if (!canWrite.value) return
  void router.push({ name: 'ai', query: { alarmId, caseId, returnTo: route.fullPath } })
}
function goSoar(alarmId: string, caseId?: string) {
  void router.push({ name: 'soar', query: { alarmId, caseId, returnTo: route.fullPath } })
}
function exportWithCurrentFilters(format: 'csv' | 'json') {
  return exportAlarms(format, {
    q: alarmKeyword.value.trim() || undefined,
    severity: alarmSeverity.value || undefined,
    status: alarmStatus.value || undefined,
    rule: alarmRule.value.trim() || undefined,
    assignee: alarmAssignee.value.trim() || undefined,
    from: alarmFrom.value || undefined,
    to: alarmTo.value || undefined,
    sort: query.alarmSort.value,
    order: query.alarmOrder.value,
    owner: query.alarmOwner.value || undefined,
    ...query.investigationFilters.value,
  })
}
</script>

<template>
  <AlarmsView
    v-model:keyword="alarmKeyword"
    v-model:severity="alarmSeverity"
    v-model:status="alarmStatus"
    v-model:rule="alarmRule"
    v-model:owner="query.alarmOwner.value"
    v-model:assignee="alarmAssignee"
    v-model:from="alarmFrom"
    v-model:to="alarmTo"
    :current-user="state.currentUser.value"
    v-model:page-num="alarmPageNum"
    :filtered-alarms="filteredAlarms"
    :alarm-page-data="alarmPageData"
    :alarm-page-size="alarmPageSize"
    v-model:page-size="alarmPageSize"
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
    :assignee-options="state.operatorOptions.value"
    :can-write="canWrite"
    :can-admin="canAdmin"
  />
</template>
