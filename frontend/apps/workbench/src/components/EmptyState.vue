<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  description?: string
}>(), {
  description: '',
})
</script>

<template>
  <div class="empty-state" role="status">
    <div class="empty-state-mark" aria-hidden="true">—</div>
    <div class="empty-state-title">{{ title }}</div>
    <div v-if="description || $slots.description" class="empty-state-description">
      <slot name="description">{{ description }}</slot>
    </div>
    <div v-if="$slots.action" class="empty-state-action"><slot name="action" /></div>
  </div>
</template>
