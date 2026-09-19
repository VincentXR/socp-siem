<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
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
import DataTableCard from '../components/DataTableCard.vue'
import EmptyState from '../components/EmptyState.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import FormField from '../components/FormField.vue'
import FormGrid from '../components/FormGrid.vue'
import FormSection from '../components/FormSection.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { useDebouncedWatch } from '../composables/useDebouncedWatch'
import { useLatestRequest } from '../composables/useLatestRequest'
import { useListQuery } from '../composables/useListQuery'
import { useFormDialog } from '../composables/useFormDialog'
import { useConfirm } from '../composables/useConfirm'
import { tOr } from '../utils/i18nLabel'
import { threatIntelApi, type Ioc } from '../api/domains'
import { SEVERITIES, type IocInput } from '../api'
import { readImportRows, type ImportRow } from '../lib/resource-import'
import { useI18n } from '../composables/useI18n'
import { useWriteAccess } from '../composables/useWriteAccess'

const { t, d } = useI18n()
const canWrite = useWriteAccess()
const { confirmDanger } = useConfirm()
const IOC_TYPES = ['IP', 'DOMAIN', 'URL', 'SHA256', 'MD5', 'EMAIL']
const tiStat = ref<{ total?: number; byType?: Record<string, number> }>({})
const loadError = ref('')
const matchValue = ref('')
const showIocDialog = ref(false)
const showDetailDrawer = ref(false)
const editingIocId = ref<string | null>(null)
const detailIoc = ref<Ioc | null>(null)
const iocImportInput = ref<HTMLInputElement | null>(null)
const importPreviewVisible = ref(false)
const importPreviewRows = ref<IocInput[]>([])
const importBusy = ref(false)
const iocBusy = ref(false)
const lifecycleBusyId = ref('')
const matchBusy = ref(false)
const newIoc = ref({ type: 'IP', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' })
const tiMatchResult = ref<{ value: string; matched: boolean; ioc?: Ioc } | null>(null)
const iocs = ref<Ioc[]>([])
const iocSize = ref(10)
const iocTotal = ref(0)
const listQuery = useListQuery({ routeName: 'threat-intel', total: iocTotal, size: iocSize, fields: [{ key: 'type', validate: type => IOC_TYPES.includes(type) ? type : '' }] })
const iocPage = listQuery.page
const iocKeyword = listQuery.keyword
const iocType = listQuery.filters.type
const loading = ref(false)
const latestRequest = useLatestRequest()
const matchRequest = useLatestRequest()
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('threat-intel')

const lifecycleLabel = (value: unknown) => {
  const ioc = value as Ioc
  if (ioc.revoked) return { text: t('threat.revoked'), type: 'danger' as const }
  const expiration = ioc.expiration || ioc.validUntil
  if (expiration && Date.parse(expiration) <= Date.now()) return { text: t('threat.expired'), type: 'warning' as const }
  return { text: t('threat.active'), type: 'success' as const }
}
const formatTime = (value?: string | null) => value ? d(value, 'dateTime') : t('time.notAvailable')

function rowValue(row: ImportRow, ...keys: string[]): string {
  const key = keys.find(candidate => row[candidate] !== undefined)
  return key ? String(row[key] ?? '').trim() : ''
}

async function loadTi(): Promise<void> {
  const request = latestRequest.start()
  loading.value = true
  loadError.value = ''
  try {
    const [listResult, statResult] = await Promise.allSettled([
      threatIntelApi.list(iocType.value || undefined, iocPage.value, iocSize.value, listQuery.keywordParam.value, { signal: request.signal }),
      threatIntelApi.stats({ signal: request.signal }),
    ])
    if (!request.isCurrent()) return
    if (listResult.status === 'fulfilled') {
      iocs.value = listResult.value.items
      iocTotal.value = listResult.value.total
    }
    else loadError.value = listResult.reason instanceof Error ? listResult.reason.message : String(listResult.reason)
    if (!request.isCurrent()) return
    if (statResult.status === 'fulfilled') tiStat.value = statResult.value
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

function resetIocForm(): void { newIoc.value = { type: 'IP', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' } }
function openCreateIoc(): void { if (!canWrite.value) return; editingIocId.value = null; resetIocForm(); showIocDialog.value = true }
function openEditIoc(value: unknown): void {
  if (!canWrite.value) return
  const ioc = value as Ioc
  editingIocId.value = ioc.id
  newIoc.value = { type: ioc.type, value: ioc.value, severity: ioc.severity, source: ioc.source, description: ioc.description || '', tags: (ioc.tags || []).join(', ') }
  showDetailDrawer.value = false
  showIocDialog.value = true
}

async function saveIoc(): Promise<void> {
  if (iocBusy.value || !newIoc.value.value.trim()) return
  const payload = { type: newIoc.value.type.toUpperCase(), value: newIoc.value.value.trim(), severity: newIoc.value.severity, source: newIoc.value.source.trim() || 'manual', description: newIoc.value.description.trim() || undefined, tags: newIoc.value.tags.split(/[,;\n]+/).map(tag => tag.trim()).filter(Boolean) }
  iocBusy.value = true
  try {
    if (editingIocId.value) await threatIntelApi.update(editingIocId.value, payload)
    else await threatIntelApi.create(payload)
    ElMessage.success(editingIocId.value ? t('threat.updated') : t('threat.added'))
    showIocDialog.value = false
    await loadTi()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : t('threat.saveFailed')) }
  finally { iocBusy.value = false }
}

async function removeIoc(ioc: Ioc): Promise<void> {
  if (!canWrite.value) return
  if (!await confirmDanger(t('threat.confirmDelete', { value: ioc.value }), { title: t('common.delete') })) return
  try { await threatIntelApi.remove(ioc.id); ElMessage.success(t('threat.deleted')); await loadTi() }
  catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : t('threat.deleteFailed')) }
}

async function toggleLifecycle(value: unknown): Promise<void> {
  if (!canWrite.value || lifecycleBusyId.value) return
  const ioc = value as Ioc
  // The list keeps the row after a revoke, so confirm the intent to stop matching first.
  if (!ioc.revoked && !await confirmDanger(t('threat.revokeConfirm'), { title: t('threat.revoke') })) return
  lifecycleBusyId.value = ioc.id
  try {
    await threatIntelApi.setRevoked(ioc.id, !ioc.revoked)
    ElMessage.success(ioc.revoked ? t('threat.restored') : t('threat.revokedSuccess'))
    await loadTi()
    detailIoc.value = iocs.value.find(item => item.id === ioc.id) ?? null
  }
  catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : t('threat.lifecycleFailed')) }
  finally { lifecycleBusyId.value = '' }
}

function openDetail(ioc: Ioc): void { detailIoc.value = ioc; showDetailDrawer.value = true }
function openDetailRow(row: unknown): void { openDetail(row as Ioc) }

async function doTiMatch(): Promise<void> {
  const value = matchValue.value.trim()
  matchRequest.cancel()
  tiMatchResult.value = null
  if (!value) { matchBusy.value = false; return }
  const request = matchRequest.start()
  matchBusy.value = true
  try {
    const result = await threatIntelApi.match(value, { signal: request.signal })
    if (!request.isCurrent()) return
    tiMatchResult.value = result
  }
  catch (cause) { if (request.isCurrent()) ElMessage.error(cause instanceof Error ? cause.message : t('threat.matchFailed')) }
  finally { if (request.isCurrent()) matchBusy.value = false }
}

function selectIocImport(): void { iocImportInput.value?.click() }
async function importIocFile(event: Event): Promise<void> {
  if (!canWrite.value) return
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const rows = await readImportRows(file)
    const payload = rows.map(row => {
      const severity = rowValue(row, 'severity').toUpperCase()
      return { type: rowValue(row, 'type').toUpperCase() || 'IP', value: rowValue(row, 'value'), severity: SEVERITIES.includes(severity as typeof SEVERITIES[number]) ? severity : 'HIGH', source: rowValue(row, 'source') || 'import', description: rowValue(row, 'description'), tags: rowValue(row, 'tags').split(/[,;\n]+/).map(tag => tag.trim()).filter(Boolean) }
    }).filter(row => row.value)
    importPreviewRows.value = payload
    importPreviewVisible.value = true
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : t('threat.importFailed')) }
  finally { input.value = '' }
}

async function confirmIocImport(): Promise<void> {
  if (!canWrite.value || importBusy.value || !importPreviewRows.value.length) return
  importBusy.value = true
  try {
    const imported = await threatIntelApi.bulkImport(importPreviewRows.value)
    if (imported.skipped) ElMessage.warning(t('threat.importSkipped', { imported: imported.imported, skipped: imported.skipped }))
    else ElMessage.success(t('threat.importSuccess', { count: imported.imported }))
    importPreviewRows.value = []
    importPreviewVisible.value = false
    await loadTi()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : t('threat.importFailed')) }
  finally { importBusy.value = false }
}

const iocDialogGuard = useFormDialog(showIocDialog, () => newIoc.value, () => iocBusy.value)
onMounted(loadTi)
watch([iocPage, iocSize], () => { listQuery.sync(); void loadTi() })
useDebouncedWatch(iocKeyword, () => {
  if (iocPage.value !== 1) iocPage.value = 1
  else { listQuery.sync(); void loadTi() }
})
function onIocTypeChange(): void {
  iocPage.value = 1
  listQuery.sync()
  void loadTi()
}
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :eyebrow="t('menuGroup.assetsAndIntel')" :title="t('threat.title')" :description="t('threat.description')"><template #actions><el-button size="small" :loading="loading" @click="loadTi">{{ t('common.refresh') }}</el-button></template></PageHeader>
    <div class="page-metrics ti-metrics"><MetricCard :label="t('threat.total')" tone="info">{{ tiStat.total ?? 0 }}</MetricCard><MetricCard v-for="(count, kind) in (tiStat.byType || {})" :key="kind" :label="kind">{{ count }}</MetricCard></div>

    <div class="threat-query-layout">
      <FilterToolbar class="ti-query-toolbar" :count="iocTotal"><el-input v-model="iocKeyword" :placeholder="t('threat.listSearchPlaceholder')" clearable @input="iocPage = 1" /><el-select v-model="iocType" :placeholder="t('threat.allTypes')" clearable @change="onIocTypeChange"><el-option v-for="type in IOC_TYPES" :key="type" :label="type" :value="type" /></el-select></FilterToolbar>
      <div class="threat-match-tool"><div><strong>{{ t('threat.matchToolTitle') }}</strong><span>{{ t('threat.matchToolHint') }}</span></div><div class="threat-match-controls"><el-input v-model="matchValue" :placeholder="t('threat.matchPlaceholder')" @keyup.enter="doTiMatch" /><el-button type="primary" :loading="matchBusy" @click="doTiMatch">{{ t('threat.checkMatch') }}</el-button></div></div>
    </div>
    <el-alert v-if="matchBusy" :title="t('common.loading')" type="info" :closable="false" style="margin-bottom:14px" />
    <el-alert v-else-if="tiMatchResult" :title="tiMatchResult.matched ? t('threat.matched', { value: tiMatchResult.ioc?.value ?? '-', severity: tiMatchResult.ioc?.severity ?? '-' }) : t('threat.noMatch')" :type="tiMatchResult.matched ? 'error' : 'info'" :closable="false" style="margin-bottom:14px" />
    <div class="add-bar"><el-button v-if="canWrite" type="primary" @click="openCreateIoc">{{ t('threat.addIoc') }}</el-button><el-button v-if="canWrite" @click="selectIocImport">{{ t('threat.batchImport') }}</el-button><input ref="iocImportInput" type="file" accept=".csv,.json,application/json,text/csv" hidden @change="importIocFile" /><span class="hint">{{ t('threat.descriptionHint') }}</span></div>

    <DataTableCard v-model:current-page="iocPage" v-model:page-size="iocSize" :total="iocTotal" :loading="loading" :error="loadError" :retry="loadTi" :empty-title="t('threat.iocList')" :empty-description="t('threat.description')"><el-table :data="iocs" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @row-click="openDetailRow"><el-table-column prop="type" column-key="type" :label="t('common.type')" :width="columnWidth('type', 90)" /><el-table-column prop="value" column-key="value" :label="t('threat.iocValue')" :width="columnWidth('value')" min-width="180" show-overflow-tooltip /><el-table-column prop="source" column-key="source" :label="t('common.source')" :width="columnWidth('source', 120)" show-overflow-tooltip /><el-table-column prop="confidence" :label="t('threat.confidence')" width="100"><template #default="{ row }">{{ row.confidence == null ? t('time.notAvailable') : `${row.confidence}%` }}</template></el-table-column><el-table-column :label="t('common.status')" width="90"><template #default="{ row }"><el-tag :type="lifecycleLabel(row).type" size="small">{{ lifecycleLabel(row).text }}</el-tag></template></el-table-column><el-table-column :label="t('threat.validUntil')" width="155"><template #default="{ row }">{{ formatTime(row.validUntil || row.expiration) }}</template></el-table-column><el-table-column v-if="canWrite" :label="t('common.actions')" width="150" :resizable="false"><template #default="{ row }"><el-button v-if="canWrite" link type="primary" size="small" @click.stop="openEditIoc(row as Ioc)">{{ t('common.edit') }}</el-button><el-button v-if="canWrite" link :type="row.revoked ? 'success' : 'warning'" size="small" :loading="lifecycleBusyId === row.id" :disabled="!!lifecycleBusyId && lifecycleBusyId !== row.id" @click.stop="toggleLifecycle(row as Ioc)">{{ row.revoked ? t('threat.restore') : t('threat.revoke') }}</el-button></template></el-table-column></el-table></DataTableCard>

    <el-dialog v-model="showIocDialog" :before-close="iocDialogGuard.beforeClose" :close-on-click-modal="false" :title="editingIocId ? t('threat.editIoc') : t('threat.addIoc')" width="640px"><el-form :disabled="iocBusy" label-position="top"><p class="dialog-hint">{{ t('threat.manualScopeHint') }}</p><FormGrid :columns="2">
          <FormField :label="t('threat.iocValue')" required :hint="t('threat.valueHint')" full>
            <el-input v-model="newIoc.value" :placeholder="t('threat.valuePlaceholder')" />
          </FormField>
          <FormField :label="t('common.type')">
            <el-select v-model="newIoc.type"><el-option v-for="type in IOC_TYPES" :key="type" :label="type" :value="type" /></el-select>
          </FormField>
          <FormField :label="t('common.severity')" :hint="t('threat.severityHint')">
            <el-select v-model="newIoc.severity"><el-option v-for="severity in SEVERITIES" :key="severity" :label="tOr(t, 'severities.' + severity, severity)" :value="severity" /></el-select>
          </FormField>
          <FormField :label="t('common.source')">
            <el-input v-model="newIoc.source" :placeholder="t('threat.sourcePlaceholder')" />
          </FormField>
          <FormField :label="t('threat.tags')" :hint="t('threat.tagsHint')">
            <el-input v-model="newIoc.tags" :placeholder="t('threat.tagsPlaceholder')" />
          </FormField>
          <FormField :label="t('common.description')" full>
            <el-input v-model="newIoc.description" type="textarea" :rows="3" />
          </FormField>
        </FormGrid></el-form><template #footer><el-button @click="iocDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button type="primary" :disabled="!newIoc.value.trim()" :loading="iocBusy" @click="saveIoc">{{ t('common.save') }}</el-button></template></el-dialog>

    <el-dialog v-model="importPreviewVisible" :title="t('threat.importPreviewTitle')" width="720px" :close-on-click-modal="false"><p class="dialog-hint">{{ t('forms.importPreview', { count: importPreviewRows.length, shown: Math.min(importPreviewRows.length, 20) }) }}</p><el-table :data="importPreviewRows.slice(0, 20)" size="small" max-height="360" border><el-table-column prop="type" :label="t('common.type')" width="90" /><el-table-column prop="value" :label="t('threat.iocValue')" min-width="200" show-overflow-tooltip /><el-table-column prop="severity" :label="t('common.severity')" width="110" /><el-table-column prop="source" :label="t('common.source')" width="130" show-overflow-tooltip /></el-table><template #footer><el-button @click="importPreviewVisible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="importBusy" @click="confirmIocImport">{{ t('threat.confirmImport') }}</el-button></template></el-dialog>

    <el-drawer :model-value="showDetailDrawer" :title="detailIoc?.value || t('threat.iocDetails')" size="min(560px, 96vw)" @close="showDetailDrawer = false"><template v-if="detailIoc"><div class="threat-detail-status"><SevBadge :value="detailIoc.severity" /><el-tag :type="lifecycleLabel(detailIoc).type" size="small">{{ lifecycleLabel(detailIoc).text }}</el-tag><span class="mono">{{ detailIoc.type }}</span></div><dl class="threat-detail-grid"><dt>{{ t('common.source') }}</dt><dd>{{ detailIoc.source || t('time.notAvailable') }}</dd><dt>{{ t('threat.confidence') }}</dt><dd>{{ detailIoc.confidence == null ? t('time.notAvailable') : `${detailIoc.confidence}%` }}</dd><dt>{{ t('threat.validFrom') }}</dt><dd>{{ formatTime(detailIoc.validFrom) }}</dd><dt>{{ t('threat.validUntil') }}</dt><dd>{{ formatTime(detailIoc.validUntil || detailIoc.expiration) }}</dd><dt>{{ t('threat.provenance') }}</dt><dd>{{ detailIoc.provenance || t('time.notAvailable') }}</dd><dt>{{ t('common.description') }}</dt><dd>{{ detailIoc.description || t('time.notAvailable') }}</dd></dl><div v-if="detailIoc.tags?.length" class="threat-detail-tags"><el-tag v-for="tag in detailIoc.tags" :key="tag" size="small">{{ tag }}</el-tag></div><div v-if="canWrite" class="threat-detail-actions"><el-button type="primary" @click="openEditIoc(detailIoc)">{{ t('common.edit') }}</el-button><el-button :type="detailIoc.revoked ? 'success' : 'warning'" :loading="lifecycleBusyId === detailIoc.id" :disabled="!!lifecycleBusyId && lifecycleBusyId !== detailIoc.id" @click="toggleLifecycle(detailIoc)">{{ detailIoc.revoked ? t('threat.restore') : t('threat.revoke') }}</el-button><el-button type="danger" plain @click="removeIoc(detailIoc)">{{ t('common.delete') }}</el-button></div></template></el-drawer>
  </div>
</template>
