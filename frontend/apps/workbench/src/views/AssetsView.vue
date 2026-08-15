<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
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
import { readImportRows, type ImportRow } from '../lib/resource-import'

const assetStat = ref<{ total: number; byType: Record<string, number>; byCriticality: Record<string, number> } | null>(null)
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('assets')
const showAssetDialog = ref(false)
const assetImportInput = ref<HTMLInputElement | null>(null)
const editingAssetId = ref<string | null>(null)
const assetForm = ref({ name: '', type: 'SERVER', ip: '', os: '', owner: '', criticality: 'HIGH' })
const assetTypes = [
  { value: 'SERVER', label: '服务器' },
  { value: 'DATABASE', label: '数据库' },
  { value: 'FIREWALL', label: '防火墙' },
  { value: 'MESSAGE', label: '消息组件' },
  { value: 'LOADBALANCER', label: '负载均衡' },
  { value: 'APPLICATION', label: '应用' },
  { value: 'NETWORK', label: '网络设备' },
]
const criticalityOptions = [
  { value: 'CRITICAL', label: '关键' },
  { value: 'HIGH', label: '高' },
  { value: 'MEDIUM', label: '中' },
  { value: 'LOW', label: '低' },
]
const rowValue = (row: ImportRow, ...keys: string[]) => {
  const key = keys.find(candidate => row[candidate] !== undefined)
  return key ? String(row[key] ?? '').trim() : ''
}
const assetsList = useResourceList<Asset>({
  searchFields: asset => [
    asset.name, asset.type, assetTypes.find(item => item.value === asset.type)?.label,
    asset.ip, asset.os, asset.owner, asset.criticality,
    criticalityOptions.find(item => item.value === asset.criticality)?.label,
  ],
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
    if (assetResult.status === 'rejected' || statResult.status === 'rejected') {
      ElMessage.warning('资产列表或统计数据加载不完整，请稍后刷新')
    }
  } finally {
    loading.value = false
  }
}

async function removeAsset(id: string) {
  if (!window.confirm('删除后将无法恢复，是否继续？')) return
  try {
    await assetApi.remove(id)
    ElMessage.success('资产已删除')
    await loadAssets()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除资产失败')
  }
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
  if (!assetForm.value.name.trim()) {
    ElMessage.warning('请输入资产名称')
    return
  }
  if (!assetForm.value.ip.trim()) {
    ElMessage.warning('请输入 IP 或主机地址')
    return
  }
  const payload = {
    name: assetForm.value.name.trim(), type: assetForm.value.type, ip: assetForm.value.ip.trim(),
    os: assetForm.value.os.trim(), owner: assetForm.value.owner.trim(), criticality: assetForm.value.criticality,
  }
  try {
    if (editingAssetId.value) await assetApi.update(editingAssetId.value, payload)
    else await assetApi.create(payload)
    showAssetDialog.value = false
    ElMessage.success(editingAssetId.value ? '资产已更新' : '资产已创建')
    await loadAssets()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存资产失败')
  }
}

function selectAssetImport() {
  assetImportInput.value?.click()
}

async function importAssetFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const rows = await readImportRows(file)
    const payload = rows.map(row => {
      const rawType = rowValue(row, 'type', '类型').toUpperCase()
      const type = assetTypes.find(item => item.value === rawType || item.label === rowValue(row, 'type', '类型'))?.value ?? 'SERVER'
      const rawCriticality = rowValue(row, 'criticality', '关键度', '关键性').toUpperCase()
      const criticality = criticalityOptions.find(item => item.value === rawCriticality || item.label === rowValue(row, 'criticality', '关键度', '关键性'))?.value ?? 'HIGH'
      return {
        name: rowValue(row, 'name', '名称'), type, ip: rowValue(row, 'ip', 'IP', '地址'),
        os: rowValue(row, 'os', '系统'), owner: rowValue(row, 'owner', '负责人'), criticality,
      }
    })
    const result = await assetApi.bulkImport(payload)
    if (result.skipped) ElMessage.warning(`已导入 ${result.imported} 条，跳过 ${result.skipped} 条`)
    else ElMessage.success(`成功导入 ${result.imported} 条资产`)
    await loadAssets()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '资产导入失败')
  } finally {
    input.value = ''
  }
}

onMounted(loadAssets)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="资产管理" description="盘点关键资产、负责人和业务属性，为风险研判提供上下文。">
      <template #actions>
        <el-button type="primary" size="small" @click="openCreateAsset">新增资产</el-button>
        <el-button size="small" @click="selectAssetImport">批量导入</el-button>
        <el-button size="small" :loading="loading" @click="loadAssets">刷新</el-button>
        <input ref="assetImportInput" type="file" accept=".csv,.json,application/json,text/csv" hidden @change="importAssetFile" />
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
      <el-table :data="assetsPaged" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @sort-change="assetsList.onSortChange">
        <el-table-column prop="name" column-key="name" label="名称" :width="columnWidth('name', 140)" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="type" column-key="type" label="类型" :width="columnWidth('type', 100)" sortable="custom">
          <template #default="{ row }">{{ assetTypes.find(item => item.value === row.type)?.label ?? row.type }}</template>
        </el-table-column>
        <el-table-column prop="ip" column-key="ip" label="IP" :width="columnWidth('ip', 120)" sortable="custom" />
        <el-table-column prop="os" column-key="os" label="系统" :width="columnWidth('os')" min-width="140" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="owner" column-key="owner" label="负责人" :width="columnWidth('owner', 100)" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="criticality" column-key="criticality" label="关键度" :width="columnWidth('criticality', 90)" sortable="custom">
          <template #default="{ row }"><el-tag :type="row.criticality === 'CRITICAL' ? 'danger' : row.criticality === 'HIGH' ? 'warning' : 'info'" size="small">{{ criticalityOptions.find(item => item.value === row.criticality)?.label ?? row.criticality }}</el-tag></template>
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
        <el-form-item label="类型"><el-select v-model="assetForm.type" style="width: 180px"><el-option v-for="type in assetTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select></el-form-item>
        <el-form-item label="IP" required><el-input v-model="assetForm.ip" placeholder="如：10.0.0.30" /></el-form-item>
        <el-form-item label="系统"><el-input v-model="assetForm.os" placeholder="如：Ubuntu 24.04" /></el-form-item>
        <el-form-item label="负责人"><el-input v-model="assetForm.owner" placeholder="如：infra" /></el-form-item>
        <el-form-item label="关键度"><el-select v-model="assetForm.criticality" style="width: 180px"><el-option v-for="level in criticalityOptions" :key="level.value" :label="level.label" :value="level.value" /></el-select></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAssetDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!assetForm.name.trim() || !assetForm.ip.trim()" @click="saveAsset">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
