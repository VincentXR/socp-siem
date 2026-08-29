<script setup lang="ts">
import { computed, inject } from 'vue'
import AttackView from '../views/AttackView.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'

const state = inject(WORKBENCH_STATE)
if (!state) throw new Error('Workbench state is not provided')
const alarms = computed(() => state.overview.alarms.value)
</script>

<template><AttackView :alarms="alarms" /></template>
