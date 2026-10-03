<script setup lang="ts">
import { computed, inject } from 'vue'
import { useRouter } from 'vue-router'
import OverviewView from '../views/OverviewView.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'
import { normalizeRole } from '../app/roles'

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
const canOpenSoar = computed(() => ['admin', 'analyst', 'approver'].includes(normalizeRole(state.currentRole.value)))

function goAlarms(query: Record<string, string> = {}): void {
  void router.push({ name: 'alarms', query })
}

function openAlarm(id: string): void {
  void router.push({ name: 'alarms', query: { alarmId: id } })
}

function goCases(): void {
  void router.push({ name: 'case' })
}

function goSearch(): void {
  void router.push({ name: 'search' })
}

function goSoar(): void {
  void router.push({ name: 'soar' })
}

</script>

<template>
  <OverviewView
    :availability="overview.availability.value"
    :health-state="overview.healthStatus.value"
    :stat="stat"
    :sit-stats="sitStats"
    :filtered-alarms="alarms"
    :healths="healths"
    :loading="overviewLoading"
    :refreshing="overview.refreshing.value"
    :updated-at="overview.updatedAt.value"
    :error="overviewError"
    :go-alarms="goAlarms"
    :open-alarm="openAlarm"
    :go-cases="goCases"
    :go-search="goSearch"
    :go-soar="canOpenSoar ? goSoar : undefined"
    @refresh="overview.refreshOverview"
  />
</template>
