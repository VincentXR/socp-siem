<script setup lang="ts">
import { computed, inject } from 'vue'
import ReportView from '../views/ReportView.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'

const state = inject(WORKBENCH_STATE)
if (!state) throw new Error('Workbench state is not provided')
const theme = computed(() => state.theme.value)
</script>

<template><ReportView :theme="theme" /></template>
