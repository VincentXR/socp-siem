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
import { computed, onMounted, ref } from 'vue'
import DataTableCard from '../components/DataTableCard.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { useResourceList } from '../composables/useResourceList'
import { assetApi, type Asset } from '../api/domains'
import { readImportRows, type ImportRow } from '../lib/resource-import'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()

const assetStat = ref<{ total: number; byType: Record<string, number>; byCriticality: Record<string, number> } | null>(null)
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('assets')
const showAssetDialog = ref(false)
const assetImportInput = ref<HTMLInputElement | null>(null)
const editingAssetId = ref<string | null>(null)
const assetForm = ref({ name: '', type: 'SERVER', ip: '', os: '', owner: '', criticality: 'HIGH' })

const assetTypes = computed(() => [
  { value: 'SERVER', label: t('assets.types.server') },
  { value: 'DATABASE', label: t('assets.types.database') },
  { value: 'FIREWALL', label: t('assets.types.firewall') },
  { value: 'MESSAGE', label: t('assets.types.messageQueue') },
  { value: 'LOADBALANCER', label: t('assets.types.loadBalancer') },
  { value: 'APPLICATION', label: t('assets.types.application') },
  { value: 'NETWORK', label: t('assets.types.networkDevice') },
])

const criticalityOptions = computed(() => [
  { value: 'CRITICAL', label: t('assets.criticalityCritical') },
  { value: 'HIGH', label: t('assets.criticalityHigh') },
  { value: 'MEDIUM', label: t('assets.criticalityMedium') },
  { value: 'LOW', label: t('assets.criticalityLow') },
])

const rowValue = (row: ImportRow, ...keys: string[]) => {
  const key = keys.find(candidate => row[candidate] !== undefined)
  return key ? String(row[key] ?? '').trim() : ''
}
const assetsList = useResourceList<Asset>({
  searchFields: asset => [
    asset.name, asset.type, assetTypes.value.find(item => item.value === asset.type)?.label,
    asset.ip, asset.os, asset.owner, asset.criticality,
    criticalityOptions.value.find(item => item.value === asset.criticality)?.label,
  ],
})
const { items: assets, page, size, keyword, loading, filtered: assetsFiltered, paged: assetsPaged, setItems } = assetsList

async function loadAssets() {
  if (loading.value) return
  loading.value = true
  try {
    const [listResult, statResult] = await Promise.allSettled([assetApi.list(), assetApi.stats()])
    if (listResult.status === 'fulfilled') setItems(listResult.value)
    if (statResult.status === 'fulfilled') assetStat.value = statResult.value
  } finally {
    loading.value = false
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
    name: asset.name, type: asset.type, ip: asset.ip,
    os: asset.os, owner: asset.owner, criticality: asset.criticality,
  }
  showAssetDialog.value = true
}

async function removeAsset(id: string) {
  try {
    await assetApi.remove(id)
    ElMessage.success(t('assets.deleted'))
    await loadAssets()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (t('assets.deleteFailed')))
  }
}

async function saveAsset() {
  const payload = {
    name: assetForm.value.name.trim(),
    type: assetForm.value.type,
    ip: assetForm.value.ip.trim(),
    os: assetForm.value.os.trim(),
    owner: assetForm.value.owner.trim(),
    criticality: assetForm.value.criticality,
  }
  try {
    if (editingAssetId.value) await assetApi.update(editingAssetId.value, payload)
    else await assetApi.create(payload)
    showAssetDialog.value = false
    ElMessage.success(editingAssetId.value ? (t('assets.updated')) : (t('assets.created')))
    await loadAssets()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (t('assets.saveFailed')))
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
      const type = assetTypes.value.find(item => item.value === rawType || item.label === rowValue(row, 'type', '类型'))?.value ?? 'SERVER'
      const rawCriticality = rowValue(row, 'criticality', '关键度', '关键性').toUpperCase()
      const criticality = criticalityOptions.value.find(item => item.value === rawCriticality || item.label === rowValue(row, 'criticality', '关键度', '关键性'))?.value ?? 'HIGH'
      return {
        name: rowValue(row, 'name', '名称'), type, ip: rowValue(row, 'ip', 'IP', '地址'),
        os: rowValue(row, 'os', '系统'), owner: rowValue(row, 'owner', '负责人'), criticality,
      }
    })
    const result = await assetApi.bulkImport(payload)
    if (result.skipped) ElMessage.warning(t('assets.importSkipped', { imported: result.imported, skipped: result.skipped }))
    else ElMessage.success(t('assets.importSuccess', { count: result.imported }))
    await loadAssets()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (t('assets.importFailed')))
  } finally {
    input.value = ''
  }
}

onMounted(loadAssets)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('assets.title')" :description="t('assets.description')">
      <template #actions>
        <el-button type="primary" size="small" @click="openCreateAsset">{{ t('assets.createAsset') }}</el-button>
        <el-button size="small" @click="selectAssetImport">{{ t('assets.importAssets') }}</el-button>
        <el-button size="small" :loading="loading" @click="loadAssets">{{ t('common.refresh') }}</el-button>
        <input ref="assetImportInput" type="file" accept=".csv,.json,application/json,text/csv" hidden @change="importAssetFile" />
      </template>
    </PageHeader>

    <div v-if="assetStat" class="page-metrics">
      <MetricCard :label="t('assets.totalAssets')" tone="info">{{ assetStat.total }}</MetricCard>
      <MetricCard :label="t('assets.criticalAssets')" tone="danger">{{ assetStat.byCriticality?.CRITICAL ?? 0 }}</MetricCard>
      <MetricCard :label="t('assets.highValueAssets')" tone="warning">{{ assetStat.byCriticality?.HIGH ?? 0 }}</MetricCard>
      <MetricCard :label="t('assets.assetTypes')" tone="neutral">{{ Object.keys(assetStat.byType || {}).length }}</MetricCard>
    </div>

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="assetsFiltered.length">
      <template #toolbar>
        <FilterToolbar :count="assetsFiltered.length">
        <el-input v-model="keyword" :placeholder="t('assets.searchPlaceholder')" clearable @input="page = 1" />
        </FilterToolbar>
      </template>
      <el-table :data="assetsPaged" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @sort-change="assetsList.onSortChange">
        <el-table-column prop="name" column-key="name" :label="t('common.name')" :width="columnWidth('name', 140)" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="type" column-key="type" :label="t('common.type')" :width="columnWidth('type', 100)" sortable="custom">
          <template #default="{ row }">{{ assetTypes.find(item => item.value === row.type)?.label ?? row.type }}</template>
        </el-table-column>
        <el-table-column prop="ip" column-key="ip" :label="t('common.ip')" :width="columnWidth('ip', 120)" sortable="custom" />
        <el-table-column prop="os" column-key="os" :label="t('endpoints.os')" :width="columnWidth('os')" min-width="140" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="owner" column-key="owner" :label="t('assets.owner')" :width="columnWidth('owner', 100)" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="criticality" column-key="criticality" :label="t('assets.criticality')" :width="columnWidth('criticality', 90)" sortable="custom">
          <template #default="{ row }"><el-tag :type="row.criticality === 'CRITICAL' ? 'danger' : row.criticality === 'HIGH' ? 'warning' : 'info'" size="small">{{ criticalityOptions.find(item => item.value === row.criticality)?.label ?? row.criticality }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="125" :resizable="false">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEditAsset(row as Asset)">{{ t('common.edit') }}</el-button>
            <el-button link type="danger" size="small" @click="removeAsset(row.id)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </DataTableCard>

    <el-dialog v-model="showAssetDialog" :title="editingAssetId ? (t('assets.edit')) : t('assets.createAsset')" width="560px">
      <el-form label-width="90px">
        <el-form-item :label="t('common.name')" required><el-input v-model="assetForm.name" :placeholder="t('assets.namePlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.type')"><el-select v-model="assetForm.type" style="width: 180px"><el-option v-for="type in assetTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select></el-form-item>
        <el-form-item :label="t('common.ip')" required><el-input v-model="assetForm.ip" :placeholder="t('assets.ipPlaceholder')" /></el-form-item>
        <el-form-item :label="t('endpoints.os')"><el-input v-model="assetForm.os" :placeholder="t('assets.osPlaceholder')" /></el-form-item>
        <el-form-item :label="t('assets.owner')"><el-input v-model="assetForm.owner" :placeholder="t('assets.ownerPlaceholder')" /></el-form-item>
        <el-form-item :label="t('assets.criticality')"><el-select v-model="assetForm.criticality" style="width: 180px"><el-option v-for="level in criticalityOptions" :key="level.value" :label="level.label" :value="level.value" /></el-select></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAssetDialog = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :disabled="!assetForm.name.trim() || !assetForm.ip.trim()" @click="saveAsset">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>
