<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { onMounted, ref } from 'vue'
import DataTableCard from '../components/DataTableCard.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { useResourceList } from '../composables/useResourceList'
import { assetApi, type Asset } from '../api/domains'

const assetStat = ref<{ total: number; byType: Record<string, number>; byCriticality: Record<string, number> } | null>(null)
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('assets')
const assetsList = useResourceList<Asset>({
  searchFields: asset => [asset.name, asset.type, asset.ip, asset.os, asset.owner, asset.criticality],
})
const { items: assets, page, size, keyword, loading, filtered: assetsFiltered, paged: assetsPaged, setItems } = assetsList

async function loadAssets() {
  if (loading.value) return
  loading.value = true
  try {
    const [assetResult, statResult] = await Promise.allSettled([assetApi.list(), assetApi.stats()])
    if (assetResult.status === 'fulfilled') {
      setItems(assetResult.value)
    }
    if (statResult.status === 'fulfilled') assetStat.value = statResult.value
  } finally {
    loading.value = false
  }
}

async function removeAsset(id: string) {
  await assetApi.remove(id)
  await loadAssets()
}

onMounted(loadAssets)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="资产管理" description="盘点关键资产、负责人和业务属性，为风险研判提供上下文。">
      <template #actions><el-button size="small" :loading="loading" @click="loadAssets">刷新</el-button></template>
    </PageHeader>

    <div v-if="assetStat" class="page-metrics">
      <MetricCard label="资产总数" tone="info">{{ assetStat.total }}</MetricCard>
      <MetricCard label="关键资产" tone="danger">{{ assetStat.byCriticality?.CRITICAL ?? 0 }}</MetricCard>
      <MetricCard label="高价值资产" tone="warning">{{ assetStat.byCriticality?.HIGH ?? 0 }}</MetricCard>
      <MetricCard label="资产类型" tone="neutral">{{ Object.keys(assetStat.byType || {}).length }}</MetricCard>
    </div>

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="assetsFiltered.length">
      <template #toolbar>
        <FilterToolbar>
        <el-input v-model="keyword" placeholder="搜索名称 / IP / 类型 / 负责人" clearable @input="page = 1" />
        <span class="toolbar-count">共 {{ assetsFiltered.length }} 条</span>
        </FilterToolbar>
      </template>
      <el-table :data="assetsPaged" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd">
        <el-table-column prop="name" column-key="name" label="名称" :width="columnWidth('name', 140)" sortable show-overflow-tooltip />
        <el-table-column prop="type" column-key="type" label="类型" :width="columnWidth('type', 100)" sortable />
        <el-table-column prop="ip" column-key="ip" label="IP" :width="columnWidth('ip', 120)" sortable />
        <el-table-column prop="os" column-key="os" label="系统" :width="columnWidth('os')" min-width="140" sortable show-overflow-tooltip />
        <el-table-column prop="owner" column-key="owner" label="负责人" :width="columnWidth('owner', 100)" sortable show-overflow-tooltip />
        <el-table-column prop="criticality" column-key="criticality" label="关键度" :width="columnWidth('criticality', 90)" sortable>
          <template #default="{ row }"><el-tag :type="row.criticality === 'CRITICAL' ? 'danger' : row.criticality === 'HIGH' ? 'warning' : 'info'" size="small">{{ row.criticality }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="70" :resizable="false">
          <template #default="{ row }"><el-button link type="danger" size="small" @click="removeAsset(row.id)">删除</el-button></template>
        </el-table-column>
      </el-table>
    </DataTableCard>
  </div>
</template>
