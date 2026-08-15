<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
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
const showAssetDialog = ref(false)
const editingAssetId = ref<string | null>(null)
const assetForm = ref({ name: '', type: 'SERVER', ip: '', os: '', owner: '', criticality: 'HIGH' })
const assetTypes = ['SERVER', 'DATABASE', 'FIREWALL', 'MESSAGE', 'LOADBALANCER', 'APPLICATION', 'NETWORK']
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

function openCreateAsset() {
  editingAssetId.value = null
  assetForm.value = { name: '', type: 'SERVER', ip: '', os: '', owner: '', criticality: 'HIGH' }
  showAssetDialog.value = true
}

function openEditAsset(asset: Asset) {
  editingAssetId.value = asset.id
  assetForm.value = {
    name: asset.name, type: asset.type, ip: asset.ip, os: asset.os,
    owner: asset.owner, criticality: asset.criticality,
  }
  showAssetDialog.value = true
}

async function saveAsset() {
  if (!assetForm.value.name.trim() || !assetForm.value.ip.trim()) return
  const payload = {
    name: assetForm.value.name.trim(), type: assetForm.value.type, ip: assetForm.value.ip.trim(),
    os: assetForm.value.os.trim(), owner: assetForm.value.owner.trim(), criticality: assetForm.value.criticality,
  }
  if (editingAssetId.value) await assetApi.update(editingAssetId.value, payload)
  else await assetApi.create(payload)
  showAssetDialog.value = false
  await loadAssets()
}

onMounted(loadAssets)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="资产管理" description="盘点关键资产、负责人和业务属性，为风险研判提供上下文。">
      <template #actions>
        <el-button type="primary" size="small" @click="openCreateAsset">新增资产</el-button>
        <el-button size="small" :loading="loading" @click="loadAssets">刷新</el-button>
      </template>
    </PageHeader>

    <div v-if="assetStat" class="page-metrics">
      <MetricCard label="资产总数" tone="info">{{ assetStat.total }}</MetricCard>
      <MetricCard label="关键资产" tone="danger">{{ assetStat.byCriticality?.CRITICAL ?? 0 }}</MetricCard>
      <MetricCard label="高价值资产" tone="warning">{{ assetStat.byCriticality?.HIGH ?? 0 }}</MetricCard>
      <MetricCard label="资产类型" tone="neutral">{{ Object.keys(assetStat.byType || {}).length }}</MetricCard>
    </div>

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="assetsFiltered.length">
      <template #toolbar>
        <FilterToolbar :count="assetsFiltered.length">
        <el-input v-model="keyword" placeholder="搜索名称 / IP / 类型 / 负责人" clearable @input="page = 1" />
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
        <el-table-column label="操作" width="125" :resizable="false">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEditAsset(row as Asset)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeAsset(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </DataTableCard>

    <el-dialog v-model="showAssetDialog" :title="editingAssetId ? '编辑资产' : '新增资产'" width="560px">
      <el-form label-width="80px">
        <el-form-item label="名称" required><el-input v-model="assetForm.name" placeholder="如：web-prod-01" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="assetForm.type" style="width: 180px"><el-option v-for="type in assetTypes" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <el-form-item label="IP" required><el-input v-model="assetForm.ip" placeholder="如：10.0.0.30" /></el-form-item>
        <el-form-item label="系统"><el-input v-model="assetForm.os" placeholder="如：Ubuntu 24.04" /></el-form-item>
        <el-form-item label="负责人"><el-input v-model="assetForm.owner" placeholder="如：infra" /></el-form-item>
        <el-form-item label="关键度"><el-select v-model="assetForm.criticality" style="width: 180px"><el-option v-for="level in ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']" :key="level" :label="level" :value="level" /></el-select></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAssetDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!assetForm.name.trim() || !assetForm.ip.trim()" @click="saveAsset">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
