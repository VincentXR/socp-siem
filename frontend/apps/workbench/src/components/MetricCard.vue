<script setup lang="ts">
type MetricTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

withDefaults(defineProps<{
  label: string
  tone?: MetricTone
}>(), {
  tone: 'neutral',
})
</script>

<template>
  <article class="metric-card" :class="`metric-card--${tone}`">
    <div class="metric-card-label">{{ label }}</div>
    <div class="metric-card-value"><slot>—</slot></div>
    <div v-if="$slots.hint" class="metric-card-hint"><slot name="hint" /></div>
  </article>
</template>
