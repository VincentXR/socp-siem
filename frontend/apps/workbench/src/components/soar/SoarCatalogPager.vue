<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import { useI18n } from '../../composables/useI18n'

const props = withDefaults(defineProps<{
  page: number
  total: number
  totalPages: number
  loading?: boolean
  disabled?: boolean
  label?: string
}>(), { loading: false, disabled: false, label: undefined })
const emit = defineEmits<{ change: [page: number] }>()
const { t } = useI18n()

function change(page: number): void {
  if (props.loading || props.disabled || page < 0 || page >= props.totalPages || page === props.page) return
  emit('change', page)
}
</script>

<template>
  <nav class="soar-catalog-pager" :aria-label="label" :aria-busy="loading">
    <span>{{ t('common.total', { total }) }}</span>
    <el-button size="small" :disabled="loading || disabled || page <= 0" @click="change(page - 1)">{{ t('common.previousPage') }}</el-button>
    <span aria-live="polite">{{ t('common.pageSummary', { page: totalPages ? page + 1 : 0, total: totalPages }) }}</span>
    <el-button size="small" :disabled="loading || disabled || page + 1 >= totalPages" @click="change(page + 1)">{{ t('common.nextPage') }}</el-button>
  </nav>
</template>

<style scoped>
.soar-catalog-pager { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 10px; color: var(--ns-text-2); font-size: 12px; }
.soar-catalog-pager .el-button + .el-button { margin-left: 0; }
</style>
