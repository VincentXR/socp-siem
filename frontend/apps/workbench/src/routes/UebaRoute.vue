<script setup lang="ts">
import { computed, inject } from 'vue'
import { useRouter } from 'vue-router'
import UebaView from '../views/UebaView.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'

const injectedState = inject(WORKBENCH_STATE)
if (!injectedState) throw new Error('Workbench state is not provided')
const state: NonNullable<typeof injectedState> = injectedState
const theme = computed(() => state.theme.value)
const router = useRouter()

function goToAlarms(entity: string): void {
  void router.push({ name: 'alarms', query: { q: entity } })
}
</script>

<template>
  <UebaView :theme="theme" @go-alarms="goToAlarms" />
</template>
