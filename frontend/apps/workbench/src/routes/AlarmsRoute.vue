<script setup lang="ts">
import { computed, inject } from 'vue'
import AlarmsView from '../views/AlarmsView.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'

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

function goCase() { state.navigate('case') }
function goSearch() { state.navigate('search') }
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
    :on-search="query.onAlarmSearch"
    :load-page="query.loadAlarmPage"
    :on-sort-change="query.onAlarmSortChange"
    :export-csv="() => state.exportAlarms('csv')"
    :export-json="() => state.exportAlarms('json')"
    :go-case="goCase"
    :go-search="goSearch"
  />
</template>
