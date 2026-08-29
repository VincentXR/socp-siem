<script setup lang="ts">
import { computed, inject } from 'vue'
import OverviewView from '../views/OverviewView.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'

const state = inject(WORKBENCH_STATE)
if (!state) throw new Error('Workbench state is not provided')

const overview = state.overview
const stat = computed(() => overview.stat.value)
const sitStats = computed(() => overview.sitStats.value)
const alarms = computed(() => overview.alarms.value)
const healths = computed(() => overview.healths.value)
</script>

<template>
  <OverviewView
    :stat="stat"
    :sit-stats="sitStats"
    :filtered-alarms="alarms"
    :healths="healths"
    @refresh="overview.refreshOverview"
  />
</template>
