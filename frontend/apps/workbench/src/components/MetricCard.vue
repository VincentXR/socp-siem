<script setup lang="ts">
type MetricTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

const props = withDefaults(defineProps<{
  label: string
  tone?: MetricTone
  interactive?: boolean
}>(), {
  tone: 'neutral',
  interactive: false,
})

const emit = defineEmits<{ click: [] }>()

function activate(): void {
  if (props.interactive) emit('click')
}
</script>

<template>
  <article
    class="metric-card"
    :class="[`metric-card--${props.tone}`, { 'metric-card--interactive': props.interactive }]"
    :role="props.interactive ? 'button' : undefined"
    :tabindex="props.interactive ? 0 : undefined"
    @click="activate"
    @keydown.enter.prevent="activate"
    @keydown.space.prevent="activate"
  >
    <div class="metric-card-label">{{ props.label }}</div>
    <div class="metric-card-value"><slot>—</slot></div>
    <div v-if="$slots.hint" class="metric-card-hint"><slot name="hint" /></div>
  </article>
</template>
