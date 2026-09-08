<script setup lang="ts">
import { computed, inject } from 'vue'
import { useRouter } from 'vue-router'
import OverviewView from '../views/OverviewView.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'

const state = inject(WORKBENCH_STATE)
if (!state) throw new Error('Workbench state is not provided')

const overview = state.overview
const stat = computed(() => overview.stat.value)
const sitStats = computed(() => overview.sitStats.value)
const alarms = computed(() => overview.alarms.value)
const healths = computed(() => overview.healths.value)
const overviewError = computed(() => overview.error.value)
const overviewLoading = computed(() => overview.loading.value)
const router = useRouter()

function goAlarms(query: Record<string, string> = {}): void {
  void router.push({ name: 'alarms', query })
}

function openAlarm(id: string): void {
  void router.push({ name: 'alarms', query: { alarmId: id } })
}

function goCases(): void {
  void router.push({ name: 'case' })
}

</script>

<template>
  <OverviewView
    :stat="stat"
    :sit-stats="sitStats"
    :filtered-alarms="alarms"
    :healths="healths"
    :loading="overviewLoading"
    :error="overviewError"
    :go-alarms="goAlarms"
    :open-alarm="openAlarm"
    :go-cases="goCases"
    @refresh="overview.refreshOverview"
  />
</template>
