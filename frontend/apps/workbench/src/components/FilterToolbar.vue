<script setup lang="ts">
withDefaults(defineProps<{
  count?: number
  countLabel?: string
}>(), {
  count: undefined,
  countLabel: '共',
})
</script>

<template>
  <div class="filter-toolbar">
    <div class="filter-toolbar-controls"><slot /></div>
    <span v-if="count !== undefined" class="toolbar-count">{{ countLabel }} {{ count }} 条</span>
  </div>
</template>
