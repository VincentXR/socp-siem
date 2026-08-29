<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '../composables/useI18n'

const props = withDefaults(defineProps<{
  count?: number
  countLabel?: string
}>(), {
  count: undefined,
  countLabel: undefined,
})

const { t } = useI18n()
const resolvedCountLabel = computed(() => props.countLabel ?? t('common.totalPrefix'))
</script>

<template>
  <div class="filter-toolbar">
    <div class="filter-toolbar-controls"><slot /></div>
    <span v-if="count !== undefined" class="toolbar-count">{{ resolvedCountLabel }} {{ count }} {{ t('common.itemsSuffix') }}</span>
  </div>
</template>
