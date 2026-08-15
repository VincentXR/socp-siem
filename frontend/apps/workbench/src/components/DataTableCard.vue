<script setup lang="ts">
import 'element-plus/es/components/card/style/css.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import PagerBar from './PagerBar.vue'

defineProps<{ total: number }>()
const currentPage = defineModel<number>('currentPage', { default: 1 })
const pageSize = defineModel<number>('pageSize', { default: 10 })
</script>

<template>
  <el-card class="data-table-card" shadow="never">
    <slot name="toolbar" />
    <div class="data-table-content"><slot /></div>
    <PagerBar v-model:current-page="currentPage" v-model:page-size="pageSize" :total="total" />
  </el-card>
</template>
