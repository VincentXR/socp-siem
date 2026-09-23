<script setup lang="ts">
import { useWriteAccess } from '../composables/useWriteAccess'
const canWrite = useWriteAccess()
import { useFormDialog } from '../composables/useFormDialog'
import { useUnsavedChanges } from '../composables/useUnsavedChanges'
import { inject } from 'vue'
import { WORKBENCH_STATE } from '../app/workbenchState'
const workbench = inject(WORKBENCH_STATE)

import { useMutation } from '../composables/useMutation'
import ActionFeedback from '../components/ActionFeedback.vue'
import FormField from '../components/FormField.vue'
import FormGrid from '../components/FormGrid.vue'
import FormSection from '../components/FormSection.vue'
const mutation = useMutation()
const { busy: actionBusy, error: actionError } = mutation
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref, watch } from 'vue'
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import DataTableCard from '../components/DataTableCard.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import PagerBar from '../components/PagerBar.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { useDebouncedWatch } from '../composables/useDebouncedWatch'
import { useLatestRequest } from '../composables/useLatestRequest'
import { useListQuery } from '../composables/useListQuery'
import { assetApi, endpointApi, type Asset, type Endpoint } from '../api/domains'
import { readImportRows, type ImportRow } from '../lib/resource-import'
import { useI18n } from '../composables/useI18n'
import { useConfirm } from '../composables/useConfirm'
import { tOr } from '../utils/i18nLabel'

const { t, d } = useI18n()
const { confirmDanger } = useConfirm()
const route = useRoute()
const router = useRouter()

const assetStat = ref<{ total: number; byType: Record<string, number>; byCriticality: Record<string, number> } | null>(null)
const loadError = ref('')
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('assets')
const showAssetDialog = ref(false)
const assetImportInput = ref<HTMLInputElement | null>(null)
const editingAssetId = ref<string | null>(null)
const assetForm = ref({ name: '', type: 'SERVER', ip: '', os: '', owner: '', criticality: 'HIGH' })
const detailEndpoints = ref<Endpoint[]>([])
const endpointInventoryError = ref('')
const endpointLoading = ref(false)
const endpointPage = ref(1)
const endpointSize = ref(20)
const endpointTotal = ref(0)
const assetDetailOpen = ref(false)
const detailAsset = ref<Asset | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const statsError = ref('')
const detailRequest = useLatestRequest()
const endpointRequest = useLatestRequest()
const selectedId = computed(() => typeof route.query.assetId === 'string' ? route.query.assetId : '')
type AssetInput = Omit<Asset, 'id'>
const importPreviewOpen = ref(false)
const importRows = ref<AssetInput[]>([])
const importUnconfirmed = ref(false)
const importResult = ref<{ imported: number; skipped: number; errors: string[] } | null>(null)

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
const assets = ref<Asset[]>([])
const size = ref(10)
const assetTotal = ref(0)
const listQuery = useListQuery({ routeName: 'assets', total: assetTotal, size })
const page = listQuery.page
const keyword = listQuery.keyword
const loading = ref(false)
const latestRequest = useLatestRequest()

async function loadAssets() {
  const request = latestRequest.start()
  loading.value = true
  loadError.value = ''
  try {
    const [listResult, statResult] = await Promise.allSettled([
      assetApi.list(page.value, size.value, listQuery.keywordParam.value, { signal: request.signal }),
      assetApi.stats({ signal: request.signal }),
    ])
    if (!request.isCurrent()) return
    if (listResult.status === 'fulfilled') {
      assets.value = listResult.value.items
      assetTotal.value = listResult.value.total
    }
    else loadError.value = listResult.reason instanceof Error ? listResult.reason.message : String(listResult.reason)
    if (!request.isCurrent()) return
    assetStat.value = statResult.status === 'fulfilled' ? statResult.value : null
    statsError.value = statResult.status === 'rejected' ? String(statResult.reason) : ''
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

function openCreateAsset() {
  if (!canWrite.value || actionBusy.value) return
  actionError.value = ''
  editingAssetId.value = null
  assetForm.value = { name: '', type: 'SERVER', ip: '', os: '', owner: '', criticality: 'HIGH' }
  showAssetDialog.value = true
}

function openAssetDetail(asset: Asset): void {
  if (actionBusy.value) return
  void router.push({ query: { ...route.query, assetId: asset.id } })
}

async function loadDetail() {
  const request = detailRequest.start()
  endpointRequest.cancel()
  const id = selectedId.value
  detailAsset.value = null; detailError.value = ''
  detailEndpoints.value = []; endpointInventoryError.value = ''; endpointTotal.value = 0
  endpointPage.value = 1; endpointLoading.value = false
  showAssetDialog.value = false
  assetDetailOpen.value = Boolean(id); detailLoading.value = Boolean(id)
  if (!id) return
  try {
    const asset = await assetApi.get(id, { signal: request.signal })
    if (!request.isCurrent()) return
    detailAsset.value = asset
    void loadRelatedEndpoints()
  } catch (failure) {
    if (request.isCurrent()) detailError.value = String(failure)
  } finally {
    if (request.isCurrent()) detailLoading.value = false
  }
}

async function loadRelatedEndpoints() {
  const asset = detailAsset.value
  if (!asset) return
  const request = endpointRequest.start()
  endpointLoading.value = true; endpointInventoryError.value = ''; detailEndpoints.value = []
  try {
    const result = await endpointApi.related(asset.ip, asset.name, endpointPage.value, endpointSize.value, { signal: request.signal })
    if (!request.isCurrent()) return
    detailEndpoints.value = result.items; endpointTotal.value = result.total
  } catch (failure) {
    if (request.isCurrent()) endpointInventoryError.value = String(failure)
  } finally {
    if (request.isCurrent()) endpointLoading.value = false
  }
}

function closeDetail() {
  const query = { ...route.query }
  delete query.assetId
  void router.push({ query })
}

function openEditAsset(asset: Asset) {
  if (!canWrite.value || actionBusy.value) return
  actionError.value = ''
  editingAssetId.value = asset.id
  assetForm.value = {
    name: asset.name, type: asset.type, ip: asset.ip,
    os: asset.os, owner: asset.owner, criticality: asset.criticality,
  }
  showAssetDialog.value = true
}

function viewAssetAlarms(): void {
  const asset = detailAsset.value
  if (!asset?.ip.trim()) return
  void router.push({ name: 'alarms', query: { q: asset.ip.trim() } })
}

function viewAssetEndpoints(): void {
  const asset = detailAsset.value
  if (!asset?.ip.trim()) return
  void router.push({ name: 'endpoints', query: { q: asset.ip.trim() } })
}

function formatTime(value: string): string {
  if (!value) return t('time.notAvailable')
  try { return d(value, 'dateTime') } catch { return value }
}

async function removeAsset(id: string) {
  if (!canWrite.value || actionBusy.value) return
  if (!await confirmDanger(t('assets.deleteConfirm'), { title: t('common.delete') })) return
  if (!canWrite.value || actionBusy.value) return
  const removed = await mutation.run(async () => {
    await assetApi.remove(id)
    latestRequest.cancel()
    assets.value = assets.value.filter(asset => asset.id !== id)
    ElMessage.success(t('assets.deleted'))
  })
  if (removed) {
    if (selectedId.value === id) closeDetail()
    void loadAssets()
  }
}

async function saveAsset() {
  if (!canWrite.value || actionBusy.value || !assetForm.value.name.trim() || !assetForm.value.ip.trim()) return
  const id = editingAssetId.value
  const payload = {
    name: assetForm.value.name.trim(),
    type: assetForm.value.type,
    ip: assetForm.value.ip.trim(),
    os: assetForm.value.os.trim(),
    owner: assetForm.value.owner.trim(),
    criticality: assetForm.value.criticality,
  }
  let savedId = ''
  const completed = await mutation.run(async () => {
    const saved = id ? await assetApi.update(id, payload) : await assetApi.create(payload)
    latestRequest.cancel()
    savedId = saved.id
    if (detailAsset.value?.id === saved.id) {
      detailRequest.cancel()
      detailAsset.value = saved
      endpointPage.value = 1
    }
    assets.value = [...assets.value.filter(asset => asset.id !== saved.id), saved]
    showAssetDialogGuard.markSaved()
    showAssetDialog.value = false
    ElMessage.success(id ? t('assets.updated') : t('assets.created'))
  })
  if (completed) {
    if (!id) await router.push({ query: { ...route.query, assetId: savedId } })
    else if (detailAsset.value?.id === savedId) void loadRelatedEndpoints()
    void loadAssets()
  }
}

function selectAssetImport() {
  if (!canWrite.value || actionBusy.value) return
  assetImportInput.value?.click()
}

async function importAssetFile(event: Event) {
  if (!canWrite.value || actionBusy.value) return
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  await mutation.run(async () => {
    try {
      if (!/\.(csv|json)$/i.test(file.name) || file.size > 2 * 1024 * 1024) throw new Error(t('assets.importLimits'))
      const rows = await readImportRows(file)
      if (!rows.length || rows.length > 500) throw new Error(t('assets.importLimits'))
      const payload = rows.map((row, index) => {
        const rawType = rowValue(row, 'type', '类型').toUpperCase()
        const type = assetTypes.value.find(item => item.value === rawType || item.label === rowValue(row, 'type', '类型'))?.value ?? (rawType || 'SERVER')
        const rawCriticality = rowValue(row, 'criticality', '关键度', '关键性').toUpperCase()
        const criticality = criticalityOptions.value.find(item => item.value === rawCriticality || item.label === rowValue(row, 'criticality', '关键度', '关键性'))?.value ?? (rawCriticality || 'HIGH')
        const item = {
          name: rowValue(row, 'name', '名称'), type, ip: rowValue(row, 'ip', 'IP', '地址'),
          os: rowValue(row, 'os', '系统'), owner: rowValue(row, 'owner', '负责人'), criticality,
        }
        if (!item.name || !item.ip || item.name.length > 128 || item.ip.length > 64 || item.os.length > 128 || item.owner.length > 128
            || type.length > 64 || !/^[A-Z0-9_-]+$/.test(type) || !criticalityOptions.value.some(option => option.value === criticality)) {
          throw new Error(t('assets.importRowInvalid', { row: index + 1 }))
        }
        return item
      })
      importRows.value = payload
      importUnconfirmed.value = false; importResult.value = null
      importPreviewOpen.value = true
    } finally {
      input.value = ''
    }
  })
}

async function confirmAssetImport() {
  if (!canWrite.value || actionBusy.value || !importRows.value.length) return
  const payload = importRows.value.map(row => ({ ...row }))
  const completed = await mutation.run(async () => {
    try {
      importResult.value = await assetApi.bulkImport(payload)
      latestRequest.cancel()
      importRows.value = []
      importPreviewOpen.value = false
      importUnconfirmed.value = false
    } catch (failure) {
      importUnconfirmed.value = true
      throw failure
    }
  })
  if (completed) void loadAssets()
}

const showAssetDialogGuard = useFormDialog(showAssetDialog, () => assetForm.value, () => actionBusy.value)
const importGuard = useFormDialog(importPreviewOpen, () => importRows.value, () => actionBusy.value)
useUnsavedChanges(() => null, () => false, () => actionBusy.value)
onBeforeRouteUpdate((to, from) => to.query.assetId === from.query.assetId || showAssetDialogGuard.canLeave())
onMounted(loadAssets)
watch([page, size], () => { listQuery.sync(); void loadAssets() })
useDebouncedWatch(keyword, () => {
  const routeKeyword = typeof route.query.q === 'string' ? route.query.q.trim() : ''
  if (keyword.value.trim() === routeKeyword) return
  if (page.value !== 1) page.value = 1
  else { listQuery.sync(); void loadAssets() }
})
watch(selectedId, () => { void loadDetail() }, { immediate: true })
watch([endpointPage, endpointSize], () => { void loadRelatedEndpoints() })
watch([() => route.query.q, () => route.query.page], () => {
  const before = { page: page.value, keyword: keyword.value.trim() }
  listQuery.applyRouteQuery()
  if (before.page === page.value && before.keyword !== keyword.value.trim()) void loadAssets()
})
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback v-if="!showAssetDialog && !importPreviewOpen" :error="actionError" />
    <PageHeader :eyebrow="t('menuGroup.assetsAndIntel')" :title="t('assets.title')" :description="t('assets.description')">
      <template #actions>
        <el-button v-if="canWrite" type="primary" size="small" :disabled="actionBusy" @click="openCreateAsset">{{ t('assets.createAsset') }}</el-button>
        <el-button v-if="canWrite" size="small" :disabled="actionBusy" @click="selectAssetImport">{{ t('assets.importAssets') }}</el-button>
        <el-button size="small" :loading="loading" @click="loadAssets">{{ t('common.refresh') }}</el-button>
        <input ref="assetImportInput" type="file" accept=".csv,.json,application/json,text/csv" hidden @change="importAssetFile" />
      </template>
    </PageHeader>
    <section v-if="importResult" role="status" class="asset-detail-section">
      <p>{{ t('assets.importSkipped', { imported: importResult.imported, skipped: importResult.skipped }) }}</p>
      <ul v-if="importResult.errors.length"><li v-for="(error, index) in importResult.errors" :key="index">{{ error }}</li></ul>
    </section>
    <ActionFeedback :error="statsError" />

    <div v-if="assetStat" class="page-metrics">
      <MetricCard :label="t('assets.totalAssets')" tone="info">{{ assetStat.total }}</MetricCard>
      <MetricCard :label="t('assets.criticalAssets')" tone="danger">{{ assetStat.byCriticality?.CRITICAL ?? 0 }}</MetricCard>
      <MetricCard :label="t('assets.highValueAssets')" tone="warning">{{ assetStat.byCriticality?.HIGH ?? 0 }}</MetricCard>
      <MetricCard :label="t('assets.assetTypes')" tone="neutral">{{ Object.keys(assetStat.byType || {}).length }}</MetricCard>
    </div>

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="assetTotal" :loading="loading" :error="loadError" :retry="loadAssets" :empty-title="t('assets.assetList')" :empty-description="t('assets.description')">
      <template #toolbar>
        <FilterToolbar :count="assetTotal">
        <el-input v-model="keyword" :disabled="actionBusy" :placeholder="t('assets.searchPlaceholder')" clearable @input="page = 1" />
        </FilterToolbar>
      </template>
      <el-table :data="assets" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @row-click="row => openAssetDetail(row as Asset)">
        <el-table-column prop="name" column-key="name" :label="t('common.name')" :width="columnWidth('name')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="type" column-key="type" :label="t('common.type')" :width="columnWidth('type', 100)">
          <template #default="{ row }">{{ assetTypes.find(item => item.value === row.type)?.label ?? row.type }}</template>
        </el-table-column>
        <el-table-column prop="ip" column-key="ip" :label="t('common.ip')" :width="columnWidth('ip', 120)" />
        <el-table-column prop="os" column-key="os" :label="t('endpoints.os')" :width="columnWidth('os', 170)" show-overflow-tooltip />
        <el-table-column prop="owner" column-key="owner" :label="t('assets.owner')" :width="columnWidth('owner', 100)" show-overflow-tooltip />
        <el-table-column prop="criticality" column-key="criticality" :label="t('assets.criticality')" :width="columnWidth('criticality', 90)">
          <template #default="{ row }"><el-tag :type="row.criticality === 'CRITICAL' ? 'danger' : row.criticality === 'HIGH' ? 'warning' : 'info'" size="small">{{ criticalityOptions.find(item => item.value === row.criticality)?.label ?? row.criticality }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="190" :resizable="false">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="actionBusy" @click.stop="openAssetDetail(row as Asset)">{{ t('common.details') }}</el-button>
            <el-button v-if="canWrite" link type="primary" size="small" :disabled="actionBusy" @click.stop="openEditAsset(row as Asset)">{{ t('common.edit') }}</el-button>
            <el-button v-if="canWrite" link type="danger" size="small" :disabled="actionBusy" @click.stop="removeAsset(row.id)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </DataTableCard>

    <el-dialog v-model="showAssetDialog" :before-close="showAssetDialogGuard.beforeClose" :title="editingAssetId ? (t('assets.edit')) : t('assets.createAsset')" width="640px"><ActionFeedback :error="actionError" />
      <el-form :disabled="actionBusy" label-position="top">
        <FormSection :title="t('assets.identity')" :hint="t('assets.identityHint')">
          <FormGrid :columns="2">
            <FormField :label="t('common.name')" required :hint="t('assets.nameHint')">
              <el-input v-model="assetForm.name" :maxlength="128" :placeholder="t('assets.namePlaceholder')" />
            </FormField>
            <FormField :label="t('common.type')">
              <el-select v-model="assetForm.type"><el-option v-for="type in assetTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select>
            </FormField>
            <FormField :label="t('common.ip')" required :hint="t('assets.ipHint')">
              <el-input v-model="assetForm.ip" :maxlength="64" :placeholder="t('assets.ipPlaceholder')" />
            </FormField>
            <FormField :label="t('endpoints.os')">
              <el-input v-model="assetForm.os" :maxlength="128" :placeholder="t('assets.osPlaceholder')" />
            </FormField>
            <FormField :label="t('assets.owner')" :hint="t('assets.ownerHint')">
              <el-select v-model="assetForm.owner" filterable clearable :placeholder="t('assets.ownerPlaceholder')"><el-option v-if="assetForm.owner" :label="assetForm.owner" :value="assetForm.owner" /><el-option v-for="person in workbench?.operatorOptions.value ?? []" :key="person" :label="person" :value="person" /></el-select>
            </FormField>
            <FormField :label="t('assets.criticality')" :hint="t('assets.criticalityHint')">
              <el-select v-model="assetForm.criticality"><el-option v-for="level in criticalityOptions" :key="level.value" :label="level.label" :value="level.value" /></el-select>
            </FormField>
          </FormGrid>
        </FormSection>
      </el-form>
      <template #footer>
        <p v-if="canWrite && (!assetForm.name.trim() || !assetForm.ip.trim())" class="dialog-hint">{{ t('forms.fieldRequired', { field: t('common.name') }) }} / {{ t('forms.fieldRequired', { field: t('common.ip') }) }}</p>
        <el-button :disabled="actionBusy" @click="showAssetDialogGuard.cancel">{{ t('common.cancel') }}</el-button>
        <el-button v-if="canWrite" type="primary" :disabled="!assetForm.name.trim() || !assetForm.ip.trim()" :loading="actionBusy" @click="saveAsset">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importPreviewOpen" :before-close="importGuard.beforeClose" :title="t('assets.importPreviewTitle')" width="720px" :close-on-click-modal="false">
      <ActionFeedback :error="actionError" />
      <p v-if="importUnconfirmed" role="status" class="dialog-hint">{{ t('assets.importUnconfirmed') }}</p>
      <p class="dialog-hint">{{ t('forms.importPreview', { count: importRows.length, shown: Math.min(importRows.length, 20) }) }}</p>
      <p class="dialog-hint">{{ t('assets.importLimits') }}</p>
      <el-table :data="importRows.slice(0, 20)" size="small" max-height="360" border>
        <el-table-column prop="name" :label="t('common.name')" min-width="160" show-overflow-tooltip />
        <el-table-column prop="ip" :label="t('common.ip')" min-width="130" />
        <el-table-column prop="type" :label="t('common.type')" width="120" />
        <el-table-column prop="criticality" :label="t('assets.criticality')" width="110" />
      </el-table>
      <template #footer><el-button :disabled="actionBusy" @click="importGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="canWrite" type="primary" :loading="actionBusy" :disabled="!importRows.length" @click="confirmAssetImport">{{ t('assets.confirmImport') }}</el-button></template>
    </el-dialog>

    <el-drawer :model-value="assetDetailOpen" :before-close="closeDetail" :title="detailAsset?.name || t('assets.assetDetails')" size="min(680px, 96vw)">
      <p v-if="detailLoading" role="status">{{ t('common.loading') }}</p>
      <ActionFeedback :error="detailError" />
      <el-button v-if="detailError" @click="loadDetail">{{ t('common.retry') }}</el-button>
      <template v-if="detailAsset">
        <div class="asset-detail-status">
          <el-tag :type="detailAsset.criticality === 'CRITICAL' ? 'danger' : detailAsset.criticality === 'HIGH' ? 'warning' : 'info'" size="small">{{ criticalityOptions.find(item => item.value === detailAsset?.criticality)?.label ?? detailAsset.criticality }}</el-tag>
          <span class="mono">{{ detailAsset.id }}</span>
        </div>
        <dl class="asset-detail-grid">
          <dt>{{ t('common.name') }}</dt><dd>{{ detailAsset.name }}</dd>
          <dt>{{ t('common.type') }}</dt><dd>{{ assetTypes.find(item => item.value === detailAsset?.type)?.label ?? detailAsset.type }}</dd>
          <dt>{{ t('common.ip') }}</dt><dd class="mono">{{ detailAsset.ip || t('time.notAvailable') }}</dd>
          <dt>{{ t('endpoints.os') }}</dt><dd>{{ detailAsset.os || t('time.notAvailable') }}</dd>
          <dt>{{ t('assets.owner') }}</dt><dd>{{ detailAsset.owner || t('time.notAvailable') }}</dd>
          <dt>{{ t('assets.criticality') }}</dt><dd>{{ criticalityOptions.find(item => item.value === detailAsset?.criticality)?.label ?? detailAsset.criticality }}</dd>
        </dl>

        <section class="asset-detail-section asset-endpoints" :aria-label="t('assets.relatedEndpoints')" :aria-busy="endpointLoading">
          <div class="asset-detail-section-head"><strong>{{ t('assets.relatedEndpoints') }}</strong><span v-if="!endpointInventoryError && !endpointLoading">{{ endpointTotal }}</span></div>
          <p v-if="endpointLoading" role="status">{{ t('common.loading') }}</p>
          <ActionFeedback :error="endpointInventoryError" />
          <el-button v-if="endpointInventoryError" @click="loadRelatedEndpoints">{{ t('common.retry') }}</el-button>
          <el-table v-else-if="detailEndpoints.length" :data="detailEndpoints" size="small">
            <el-table-column prop="hostname" :label="t('endpoints.hostname')" min-width="150" show-overflow-tooltip />
            <el-table-column prop="status" :label="t('common.status')" width="90"><template #default="{ row }"><el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'" size="small">{{ tOr(t, 'statuses.' + row.status, row.status) }}</el-tag></template></el-table-column>
            <el-table-column prop="lastHeartbeat" :label="t('endpoints.lastHeartbeat')" width="160" show-overflow-tooltip><template #default="{ row }">{{ formatTime(row.lastHeartbeat) }}</template></el-table-column>
          </el-table>
          <p v-else-if="!endpointLoading" class="asset-detail-muted">{{ t('assets.noRelatedEndpoints') }}</p>
          <PagerBar v-if="endpointTotal" v-model:current-page="endpointPage" v-model:page-size="endpointSize" :total="endpointTotal" />
        </section>

        <section class="asset-detail-section">
          <div class="asset-detail-section-head"><strong>{{ t('assets.businessLinks') }}</strong></div>
          <p class="asset-detail-muted">{{ t('assets.associationHint') }}</p>
          <div class="asset-detail-links">
            <el-button type="primary" plain :disabled="!detailAsset.ip.trim()" @click="viewAssetAlarms">{{ t('assets.viewRelatedAlarms') }}</el-button>
            <el-button plain :disabled="!detailAsset.ip.trim()" @click="viewAssetEndpoints">{{ t('assets.viewRelatedEndpoints') }}</el-button>
          </div>
        </section>

        <div v-if="canWrite" class="asset-detail-actions"><el-button type="primary" :disabled="actionBusy" @click="openEditAsset(detailAsset)">{{ t('common.edit') }}</el-button></div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.asset-endpoints :deep(.el-pagination) {
  min-width: 0;
  flex-wrap: wrap;
  row-gap: 8px;
}
</style>
