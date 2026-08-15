<script setup lang="ts">
import 'element-plus/es/components/pagination/style/css.mjs'
import ElPagination from 'element-plus/es/components/pagination/index.mjs'
/**
 * 统一分页器（案件/资产/IOC/剧本执行等列表页共用）。
 * 透传 el-pagination 的 current-page / page-size / total。
 */
defineProps<{ total: number }>()
const page = defineModel<number>('currentPage', { default: 1 })
const size = defineModel<number>('pageSize', { default: 10 })
</script>

<template>
  <div style="display:flex;justify-content:flex-end;margin-top:12px">
    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
      :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
      @size-change="() => { page = 1 }" />
  </div>
</template>
