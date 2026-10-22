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
    <div class="empty-state-mark" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3h8l4 4v13H4V3h4Z"/><path d="M15 3v5h5M8 12h8M8 16h5"/></svg></div>
    <div class="empty-state-title">{{ title }}</div>
    <div v-if="description || $slots.description" class="empty-state-description">
      <slot name="description">{{ description }}</slot>
    </div>
    <div v-if="$slots.action" class="empty-state-action"><slot name="action" /></div>
  </div>
</template>
