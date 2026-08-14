<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import PagerBar from '../components/PagerBar.vue'
import { assetStats, deleteAsset, listAssets, type Asset } from '../api'

const assets = ref<Asset[]>([])
const assetStat = ref<{ total: number; byType: Record<string, number>; byCriticality: Record<string, number> } | null>(null)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const assetsPaged = computed(() => assets.value.slice((page.value - 1) * size.value, page.value * size.value))

async function loadAssets() {
  if (loading.value) return
  loading.value = true
  try {
    const [assetResult, statResult] = await Promise.allSettled([listAssets(), assetStats()])
    if (assetResult.status === 'fulfilled') {
      assets.value = assetResult.value
      if (page.value > 1 && assetsPaged.value.length === 0) page.value = 1
    }
    if (statResult.status === 'fulfilled') assetStat.value = statResult.value
  } finally {
    loading.value = false
  }
}

async function removeAsset(id: string) {
  await deleteAsset(id)
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

    <el-card shadow="never">
      <el-table :data="assetsPaged" size="small">
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="ip" label="IP" width="120" />
        <el-table-column prop="os" label="系统" min-width="140" />
        <el-table-column prop="owner" label="负责人" width="100" />
        <el-table-column prop="criticality" label="关键度" width="90">
          <template #default="{ row }"><el-tag :type="row.criticality === 'CRITICAL' ? 'danger' : row.criticality === 'HIGH' ? 'warning' : 'info'" size="small">{{ row.criticality }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ row }"><el-button link type="danger" size="small" @click="removeAsset(row.id)">删除</el-button></template>
        </el-table-column>
      </el-table>
      <PagerBar v-model:current-page="page" v-model:page-size="size" :total="assets.length" />
    </el-card>
  </div>
</template>
