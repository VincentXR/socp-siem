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
import { useResourceList } from '../composables/useResourceList'
import { endpointApi, type Endpoint } from '../api/domains'

const endpointStat = ref<{ total: number; online: number; byType: Record<string, number> } | null>(null)
const endpointsList = useResourceList<Endpoint>({
  searchFields: endpoint => [endpoint.hostname, endpoint.ip, endpoint.os, endpoint.agentVersion, endpoint.status],
})
const { items: endpoints, page, size, keyword, loading, filtered: endpointsFiltered, paged: endpointsPaged, setItems } = endpointsList

async function loadEndpoints() {
  if (loading.value) return
  loading.value = true
  try {
    const [endpointResult, statResult] = await Promise.allSettled([endpointApi.list(), endpointApi.stats()])
    if (endpointResult.status === 'fulfilled') {
      setItems(endpointResult.value)
    }
    if (statResult.status === 'fulfilled') endpointStat.value = statResult.value
  } finally {
    loading.value = false
  }
}

async function removeEndpoint(id: string) {
  await endpointApi.remove(id)
  await loadEndpoints()
}

onMounted(loadEndpoints)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="端点防护" description="查看端点在线状态、Agent 版本和基础系统信息。">
      <template #actions><el-button size="small" :loading="loading" @click="loadEndpoints">刷新</el-button></template>
    </PageHeader>

    <div v-if="endpointStat" class="page-metrics">
      <MetricCard label="端点总数" tone="info">{{ endpointStat.total }}</MetricCard>
      <MetricCard label="在线端点" tone="success">{{ endpointStat.online }}</MetricCard>
      <MetricCard label="离线端点" tone="warning">{{ endpointStat.total - endpointStat.online }}</MetricCard>
      <MetricCard label="端点类型" tone="neutral">{{ Object.keys(endpointStat.byType || {}).length }}</MetricCard>
    </div>

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="endpointsFiltered.length">
      <template #toolbar>
        <FilterToolbar>
        <el-input v-model="keyword" placeholder="搜索主机名 / IP / 系统" clearable @input="page = 1" />
        <span class="toolbar-count">共 {{ endpointsFiltered.length }} 条</span>
        </FilterToolbar>
      </template>
      <el-table :data="endpointsPaged" size="small">
        <el-table-column prop="hostname" label="主机名" width="140" sortable show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="120" sortable />
        <el-table-column prop="os" label="系统" min-width="140" sortable show-overflow-tooltip />
        <el-table-column prop="agentVersion" label="Agent 版本" width="120" sortable show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="80" sortable>
          <template #default="{ row }"><el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ row }"><el-button link type="danger" size="small" @click="removeEndpoint(row.id)">注销</el-button></template>
        </el-table-column>
      </el-table>
    </DataTableCard>
  </div>
</template>
