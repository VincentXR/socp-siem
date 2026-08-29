<script setup lang="ts">
/**
 * 告警级别徽标（CRITICAL/HIGH/MEDIUM/LOW/INFO → 配色）。
 */
import { computed } from 'vue'
import { SeverityTag } from '@socp/soc-ui'

const props = defineProps<{ value?: string | null }>()
const rawValue = computed(() => String(props.value ?? '').trim().toUpperCase())
const displayValue = computed(() => rawValue.value || 'INFO')
const tone = computed(() => {
  switch (rawValue.value) {
    case 'CRITICAL':
    case 'FATAL':
      return 'critical'
    case 'HIGH':
    case 'ERROR':
      return 'high'
    case 'MEDIUM':
    case 'WARN':
    case 'WARNING':
      return 'medium'
    case 'LOW':
      return 'low'
    case 'INFO':
    case 'NOTICE':
    case 'DEBUG':
    case 'TRACE':
    case '':
      return 'info'
    default:
      return 'info'
  }
})
</script>

<template>
  <SeverityTag :severity="displayValue" class="sev-badge" :data-tone="tone" />
</template>
