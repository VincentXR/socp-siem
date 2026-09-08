<script setup lang="ts">
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import PagerBar from './PagerBar.vue'
import { useI18n } from '../composables/useI18n'

const props = withDefaults(defineProps<{
  total: number
  loading?: boolean
  error?: string
  emptyTitle?: string
  emptyDescription?: string
  retry?: () => void
}>(), {
  loading: false,
  error: '',
  emptyTitle: '',
  emptyDescription: '',
  retry: undefined,
})
const currentPage = defineModel<number>('currentPage', { default: 1 })
const pageSize = defineModel<number>('pageSize', { default: 10 })
const { t } = useI18n()
</script>

<template>
  <el-card class="data-table-card" shadow="never">
    <slot name="toolbar" />
    <div class="data-table-content">
      <div v-if="props.loading" class="list-state list-state-loading" role="status">{{ t('common.loading') }}</div>
      <div v-else-if="props.error" class="list-state list-state-error" role="alert">
        <strong>{{ t('common.failed') }}</strong>
        <span>{{ props.error }}</span>
        <el-button v-if="props.retry" size="small" @click="props.retry?.()">{{ t('common.refresh') }}</el-button>
      </div>
      <div v-else-if="props.total === 0 && props.emptyTitle" class="list-state-empty">
        <div class="list-empty-mark">—</div>
        <strong>{{ props.emptyTitle }}</strong>
        <span v-if="props.emptyDescription">{{ props.emptyDescription }}</span>
      </div>
      <slot v-else />
    </div>
    <PagerBar v-if="!props.loading && !props.error && props.total > 0" v-model:current-page="currentPage" v-model:page-size="pageSize" :total="props.total" />
  </el-card>
</template>
