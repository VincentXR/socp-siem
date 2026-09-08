<script setup lang="ts">
import { computed, inject } from 'vue'
import { useRouter } from 'vue-router'
import SituationView from '../views/SituationView.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'

const state = inject(WORKBENCH_STATE)
if (!state) throw new Error('Workbench state is not provided')
const theme = computed(() => state.theme.value)
const router = useRouter()

function openAlarm(id: string): void {
  void router.push({ name: 'alarms', query: { alarmId: id } })
}
</script>

<template>
  <SituationView :theme="theme" @session-expired="state.logout" @go-alarm="openAlarm" />
</template>
