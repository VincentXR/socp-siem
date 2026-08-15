<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import PagerBar from '../components/PagerBar.vue'
import { deleteEndpoint, endpointStats, listEndpoints, type Endpoint } from '../api'

const endpoints = ref<Endpoint[]>([])
const endpointStat = ref<{ total: number; online: number; byType: Record<string, number> } | null>(null)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const endpointsPaged = computed(() => endpoints.value.slice((page.value - 1) * size.value, page.value * size.value))

async function loadEndpoints() {
  if (loading.value) return
  loading.value = true
  try {
    const [endpointResult, statResult] = await Promise.allSettled([listEndpoints(), endpointStats()])
    if (endpointResult.status === 'fulfilled') {
      endpoints.value = endpointResult.value
      if (page.value > 1 && endpointsPaged.value.length === 0) page.value = 1
    }
    if (statResult.status === 'fulfilled') endpointStat.value = statResult.value
  } finally {
    loading.value = false
  }
}

async function removeEndpoint(id: string) {
  await deleteEndpoint(id)
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

    <el-card shadow="never">
      <el-table :data="endpointsPaged" size="small">
        <el-table-column prop="hostname" label="主机名" width="140" />
        <el-table-column prop="ip" label="IP" width="120" />
        <el-table-column prop="os" label="系统" min-width="140" />
        <el-table-column prop="agentVersion" label="Agent 版本" width="120" />
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ row }"><el-button link type="danger" size="small" @click="removeEndpoint(row.id)">注销</el-button></template>
        </el-table-column>
      </el-table>
      <PagerBar v-model:current-page="page" v-model:page-size="size" :total="endpoints.length" />
    </el-card>
  </div>
</template>
