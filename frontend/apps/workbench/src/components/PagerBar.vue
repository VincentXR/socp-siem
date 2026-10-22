<script setup lang="ts">
import 'element-plus/es/components/pagination/style/css.mjs'
import ElPagination from 'element-plus/es/components/pagination/index.mjs'
import { computed } from 'vue'
import { useI18n } from '../composables/useI18n'
/**
 * 统一分页器（案件/资产/IOC/剧本执行等列表页共用）。
 * 透传 el-pagination 的 current-page / page-size / total。
 */
const props = withDefaults(defineProps<{ total: number; pageSizes?: number[] }>(), {
  pageSizes: () => [10, 20, 50],
})
const page = defineModel<number>('currentPage', { default: 1 })
const size = defineModel<number>('pageSize', { default: 10 })
const { t } = useI18n()
const range = computed(() => ({
  start: props.total ? Math.min((page.value - 1) * size.value + 1, props.total) : 0,
  end: Math.min(page.value * size.value, props.total),
  total: props.total,
}))
</script>

<template>
  <div class="pager-bar">
    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
      :page-sizes="props.pageSizes" :pager-count="5" layout="slot, sizes, prev, pager, next"
      @size-change="() => { page = 1 }">
      <span class="el-pagination__total pager-range">{{ t('common.pageRange', range) }}</span>
    </el-pagination>
  </div>
</template>

<style scoped>
.pager-bar { display:flex; justify-content:flex-end; min-width:0; margin-top:12px; }
.pager-bar :deep(.el-pagination) { width:100%; max-width:100%; height:auto; flex-wrap:wrap; justify-content:flex-end; row-gap:8px; }
.pager-range { flex:1; height:auto; min-height:32px; color:var(--ns-text-2); font-size:12px; white-space:normal; }
@media(max-width:600px) {
  .pager-bar :deep(.el-pagination) { justify-content:flex-start; }
  .pager-range { flex-basis:100%; }
}
</style>
