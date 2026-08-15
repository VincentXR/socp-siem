<script setup lang="ts">
/**
 * 告警级别徽标（CRITICAL/HIGH/MEDIUM/LOW/INFO → 配色）。
 */
import { computed } from 'vue'

const props = defineProps<{ value: string }>()
const tone = computed(() => props.value.trim().toLowerCase() || 'info')
</script>

<template>
  <span class="sev-badge" :class="`sev-${tone}`">
    {{ value }}
  </span>
</template>
