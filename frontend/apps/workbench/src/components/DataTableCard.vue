<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/loading/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { vLoading } from 'element-plus/es/components/loading/index.mjs'
import { computed } from 'vue'
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
// 表格在 loading/error 期间保持挂载（旧数据与分页器不被销毁），只有真正的空结果才换成空态。
const isEmpty = computed(() => !props.loading && !props.error && props.total === 0)
</script>

<template>
  <el-card class="data-table-card" shadow="never">
    <slot name="toolbar" />
    <div v-if="props.error" class="list-state list-state-error" role="alert">
      <strong>{{ t('common.failed') }}</strong>
      <span>{{ props.error }}</span>
      <el-button v-if="props.retry" size="small" @click="props.retry?.()">{{ t('common.refresh') }}</el-button>
    </div>
    <div v-show="!isEmpty" v-loading="props.loading" class="data-table-content">
      <slot />
    </div>
    <div v-if="isEmpty" class="list-state-empty">
      <div class="list-empty-mark">—</div>
      <strong>{{ props.emptyTitle || t('common.empty') }}</strong>
      <span v-if="props.emptyDescription">{{ props.emptyDescription }}</span>
    </div>
    <PagerBar v-if="props.total > 0" v-model:current-page="currentPage" v-model:page-size="pageSize" :total="props.total" />
  </el-card>
</template>
