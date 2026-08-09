<script setup lang="ts">
/**
 * 告警级别徽标（CRITICAL/HIGH/MEDIUM/LOW/INFO → 配色）。
 */
const props = defineProps<{ value: string }>()
const COLORS: Record<string, string> = {
  CRITICAL: '#f56c6c',
  HIGH: '#e63946',
  MEDIUM: '#e6a23c',
  LOW: '#909399',
  INFO: '#909399',
}
const color = () => COLORS[props.value] ?? '#909399'
</script>

<template>
  <span :style="`background:${color()};color:#fff;border-radius:4px;padding:0 8px;font-size:12px;font-weight:600;display:inline-block;line-height:20px`">
    {{ value }}
  </span>
</template>
