<script setup lang="ts">
import { computed, inject } from 'vue'
import { useRouter } from 'vue-router'
import UebaView from '../views/UebaView.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'
import { ALARM_STATUS_ALL } from '../app/alarm-statuses'

const injectedState = inject(WORKBENCH_STATE)
if (!injectedState) throw new Error('Workbench state is not provided')
const state: NonNullable<typeof injectedState> = injectedState
const theme = computed(() => state.theme.value)
const router = useRouter()

function goToAlarms(entity: string): void {
  void router.push({ name: 'alarms', query: { status: ALARM_STATUS_ALL, entity } })
}
</script>

<template>
  <UebaView :theme="theme" @go-alarms="goToAlarms" />
</template>
