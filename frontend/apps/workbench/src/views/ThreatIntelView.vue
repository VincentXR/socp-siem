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
import RowActivate from '../components/RowActivate.vue'
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
import { readImportRows } from '../lib/resource-import'
import { IOC_TYPES, prepareIocImport } from '../lib/ioc-import'
import { useMutation } from '../composables/useMutation'
import { useUnsavedChanges } from '../composables/useUnsavedChanges'
import { useRoute } from 'vue-router'
import ActionFeedback from '../components/ActionFeedback.vue'
import { useI18n } from '../composables/useI18n'
import { useWriteAccess } from '../composables/useWriteAccess'

const { t, d } = useI18n()
const canWrite = useWriteAccess()
const { confirmDanger } = useConfirm()
const route = useRoute()
const mutation = useMutation()
const actionBusy = mutation.busy
const actionError = mutation.error
const importUnconfirmed = ref(false)
const importResult = ref<{ imported: number; skipped: number; errors: string[] } | null>(null)
const statsError = ref('')
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

async function loadTi(): Promise<void> {
  const request = latestRequest.start()
  loading.value = true
  loadError.value = ''
  statsError.value = ''
  try {
    const [listResult, statResult] = await Promise.allSettled([
      threatIntelApi.list(iocType.value || undefined, iocPage.value, iocSize.value, listQuery.keywordParam.value, { signal: request.signal }),
      threatIntelApi.stats({ signal: request.signal }),
    ])
    if (!request.isCurrent()) return
    if (listResult.status === 'fulfilled') {
      iocs.value = listResult.value.items
      iocTotal.value = listResult.value.total
      const current = iocs.value.find(item => item.id === detailIoc.value?.id)
      if (current) detailIoc.value = current
    }
    else loadError.value = listResult.reason instanceof Error ? listResult.reason.message : String(listResult.reason)
    if (!request.isCurrent()) return
    if (statResult.status === 'fulfilled') tiStat.value = statResult.value
    else { tiStat.value = {}; statsError.value = statResult.reason instanceof Error ? statResult.reason.message : String(statResult.reason) }
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

function resetIocForm(): void { newIoc.value = { type: 'IP', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' } }
function openCreateIoc(): void { if (!canWrite.value || actionBusy.value || showIocDialog.value || importPreviewVisible.value) return; actionError.value = ''; editingIocId.value = null; resetIocForm(); showIocDialog.value = true }
function openEditIoc(value: unknown): void {
  if (!canWrite.value || actionBusy.value || showIocDialog.value || importPreviewVisible.value) return
  actionError.value = ''
  const ioc = value as Ioc
  editingIocId.value = ioc.id
  newIoc.value = { type: ioc.type, value: ioc.value, severity: ioc.severity, source: ioc.source, description: ioc.description || '', tags: (ioc.tags || []).join(', ') }
  showDetailDrawer.value = false
  showIocDialog.value = true
}

async function saveIoc(): Promise<void> {
  if (!canWrite.value || actionBusy.value || !newIoc.value.value.trim()) return
  const id = editingIocId.value
  const payload = { type: newIoc.value.type.toUpperCase(), value: newIoc.value.value.trim(), severity: newIoc.value.severity, source: newIoc.value.source.trim() || 'manual', description: newIoc.value.description.trim() || undefined, tags: newIoc.value.tags.split(/[,;\n]+/).map(tag => tag.trim()).filter(Boolean) }
  await mutation.run(async () => {
    const saved = id ? await threatIntelApi.update(id, payload) : await threatIntelApi.create(payload)
    if (detailIoc.value?.id === saved.id) detailIoc.value = saved
    latestRequest.cancel()
    iocDialogGuard.markSaved()
    showIocDialog.value = false
    ElMessage.success(id ? t('threat.updated') : t('threat.added'))
    await loadTi()
  })
}

async function removeIoc(ioc: Ioc): Promise<void> {
  if (!canWrite.value || actionBusy.value) return
  await mutation.run(async () => {
    if (!await confirmDanger(t('threat.confirmDelete', { value: ioc.value }), { title: t('common.delete') })) return
    await threatIntelApi.remove(ioc.id)
    latestRequest.cancel()
    if (detailIoc.value?.id === ioc.id) { showDetailDrawer.value = false; detailIoc.value = null }
    ElMessage.success(t('threat.deleted'))
    await loadTi()
  })
}

async function toggleLifecycle(value: unknown): Promise<void> {
  if (!canWrite.value || actionBusy.value) return
  const ioc = value as Ioc
  await mutation.run(async () => {
    if (!ioc.revoked && !await confirmDanger(t('threat.revokeConfirm'), { title: t('threat.revoke') })) return
    lifecycleBusyId.value = ioc.id
    try {
      const updated = await threatIntelApi.setRevoked(ioc.id, !ioc.revoked)
      latestRequest.cancel()
      if (detailIoc.value?.id === ioc.id) detailIoc.value = updated
      ElMessage.success(ioc.revoked ? t('threat.restored') : t('threat.revokedSuccess'))
      await loadTi()
    } finally { lifecycleBusyId.value = '' }
  })
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

function selectIocImport(): void {
  if (!canWrite.value || actionBusy.value || showIocDialog.value || importPreviewVisible.value) return
  iocImportInput.value?.click()
}
async function importIocFile(event: Event): Promise<void> {
  if (!canWrite.value || actionBusy.value) return
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  await mutation.run(async () => {
    try {
      importPreviewRows.value = prepareIocImport(await readImportRows(file))
      importUnconfirmed.value = false; importResult.value = null
      importPreviewVisible.value = true
    } finally { input.value = '' }
  })
}

async function confirmIocImport(): Promise<void> {
  if (!canWrite.value || actionBusy.value || !importPreviewRows.value.length) return
  const payload = importPreviewRows.value.map(row => ({ ...row, tags: [...(row.tags ?? [])] }))
  await mutation.run(async () => {
    try { importResult.value = await threatIntelApi.bulkImport(payload) }
    catch (failure) { importUnconfirmed.value = true; throw failure }
    latestRequest.cancel()
    importUnconfirmed.value = false
    importPreviewRows.value = []
    importGuard.markSaved()
    importPreviewVisible.value = false
    await loadTi()
  })
}

watch(matchValue, () => { matchRequest.cancel(); matchBusy.value = false; tiMatchResult.value = null })
const iocDialogGuard = useFormDialog(showIocDialog, () => newIoc.value, () => actionBusy.value)
const importGuard = useFormDialog(importPreviewVisible, () => importPreviewRows.value, () => actionBusy.value)
useUnsavedChanges(() => null, () => false, () => actionBusy.value)
onMounted(loadTi)
watch([iocPage, iocSize, iocType], () => { listQuery.sync(); void loadTi() })
useDebouncedWatch(iocKeyword, () => {
  if (iocKeyword.value.trim() === (typeof route.query.q === 'string' ? route.query.q.trim() : '')) return
  if (iocPage.value !== 1) iocPage.value = 1
  else { listQuery.sync(); void loadTi() }
})
watch([() => route.query.q, () => route.query.page, () => route.query.type], () => {
  const before = { page: iocPage.value, type: iocType.value, keyword: iocKeyword.value.trim() }
  listQuery.applyRouteQuery()
  if (before.page === iocPage.value && before.type === iocType.value && before.keyword !== iocKeyword.value.trim()) void loadTi()
})
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback v-if="!showIocDialog && !importPreviewVisible" :error="actionError" />
    <ActionFeedback :error="statsError" />
    <el-alert v-if="importResult" :title="t('threat.importSkipped', { imported: importResult.imported, skipped: importResult.skipped })" :type="importResult.skipped ? 'warning' : 'success'" :closable="false"><ul v-if="importResult.errors.length"><li v-for="(error, index) in importResult.errors.slice(0, 20)" :key="index">{{ error }}</li></ul></el-alert>
    <PageHeader :eyebrow="t('menuGroup.assetsAndIntel')" :title="t('threat.title')" :description="t('threat.description')"><template #actions><el-button size="small" :loading="loading" @click="loadTi">{{ t('common.refresh') }}</el-button></template></PageHeader>
    <div class="page-metrics ti-metrics"><MetricCard :label="t('threat.total')" tone="info">{{ tiStat.total ?? '\u2014' }}</MetricCard><MetricCard v-for="(count, kind) in (tiStat.byType || {})" :key="kind" :label="kind">{{ count }}</MetricCard></div>

    <div class="threat-query-layout">
      <FilterToolbar class="ti-query-toolbar" :count="iocTotal"><el-input v-model="iocKeyword" :placeholder="t('threat.listSearchPlaceholder')" clearable @input="iocPage = 1" /><el-select v-model="iocType" :placeholder="t('threat.allTypes')" clearable @change="iocPage = 1"><el-option v-for="type in IOC_TYPES" :key="type" :label="type" :value="type" /></el-select></FilterToolbar>
      <div class="threat-match-tool"><div><strong>{{ t('threat.matchToolTitle') }}</strong><span>{{ t('threat.matchToolHint') }}</span></div><div class="threat-match-controls"><el-input v-model="matchValue" :placeholder="t('threat.matchPlaceholder')" @keyup.enter="doTiMatch" /><el-button type="primary" :loading="matchBusy" @click="doTiMatch">{{ t('threat.checkMatch') }}</el-button></div></div>
    </div>
    <el-alert v-if="matchBusy" :title="t('common.loading')" type="info" :closable="false" style="margin-bottom:14px" />
    <el-alert v-else-if="tiMatchResult" :title="tiMatchResult.matched ? t('threat.matched', { value: tiMatchResult.ioc?.value ?? '-', severity: tiMatchResult.ioc?.severity ?? '-' }) : t('threat.noMatch')" :type="tiMatchResult.matched ? 'error' : 'info'" :closable="false" style="margin-bottom:14px" />
    <div class="add-bar"><el-button v-if="canWrite" type="primary" :disabled="actionBusy" @click="openCreateIoc">{{ t('threat.addIoc') }}</el-button><el-button v-if="canWrite" :disabled="actionBusy" @click="selectIocImport">{{ t('threat.batchImport') }}</el-button><input ref="iocImportInput" type="file" accept=".csv,.json,application/json,text/csv" hidden @change="importIocFile" /><span class="hint">{{ t('threat.descriptionHint') }}</span></div>

    <DataTableCard v-model:current-page="iocPage" v-model:page-size="iocSize" :total="iocTotal" :loading="loading" :error="loadError" :retry="loadTi" :empty-title="t('threat.iocList')" :empty-description="t('threat.description')"><el-table :data="iocs" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @row-click="openDetailRow"><el-table-column prop="type" column-key="type" :label="t('common.type')" :width="columnWidth('type', 90)" /><el-table-column prop="value" column-key="value" :label="t('threat.iocValue')" :width="columnWidth('value')" min-width="180" show-overflow-tooltip><template #default="{ row }"><RowActivate :aria-label="row.value" @activate="openDetailRow(row)">{{ row.value }}</RowActivate></template></el-table-column><el-table-column prop="source" column-key="source" :label="t('common.source')" :width="columnWidth('source', 120)" show-overflow-tooltip /><el-table-column prop="confidence" :label="t('threat.confidence')" width="100"><template #default="{ row }">{{ row.confidence == null ? t('time.notAvailable') : `${row.confidence}%` }}</template></el-table-column><el-table-column :label="t('common.status')" width="90"><template #default="{ row }"><el-tag :type="lifecycleLabel(row).type" size="small">{{ lifecycleLabel(row).text }}</el-tag></template></el-table-column><el-table-column :label="t('threat.validUntil')" width="155"><template #default="{ row }">{{ formatTime(row.validUntil || row.expiration) }}</template></el-table-column><el-table-column v-if="canWrite" :label="t('common.actions')" width="150" :resizable="false"><template #default="{ row }"><el-button v-if="canWrite" link type="primary" size="small" :disabled="actionBusy" @click.stop="openEditIoc(row as Ioc)">{{ t('common.edit') }}</el-button><el-button v-if="canWrite" link :type="row.revoked ? 'success' : 'warning'" size="small" :loading="lifecycleBusyId === row.id" :disabled="actionBusy" @click.stop="toggleLifecycle(row as Ioc)">{{ row.revoked ? t('threat.restore') : t('threat.revoke') }}</el-button></template></el-table-column></el-table></DataTableCard>

    <el-dialog v-model="showIocDialog" :before-close="iocDialogGuard.beforeClose" :close-on-click-modal="false" :title="editingIocId ? t('threat.editIoc') : t('threat.addIoc')" width="640px"><ActionFeedback :error="actionError" /><el-form :disabled="actionBusy" label-position="top"><p class="dialog-hint">{{ t('threat.manualScopeHint') }}</p><FormGrid :columns="2">
          <FormField :label="t('threat.iocValue')" required :hint="t('threat.valueHint')" full>
            <el-input v-model="newIoc.value" :maxlength="2048" :placeholder="t('threat.valuePlaceholder')" />
          </FormField>
          <FormField :label="t('common.type')">
            <el-select v-model="newIoc.type"><el-option v-for="type in IOC_TYPES" :key="type" :label="type" :value="type" /></el-select>
          </FormField>
          <FormField :label="t('common.severity')" :hint="t('threat.severityHint')">
            <el-select v-model="newIoc.severity"><el-option v-for="severity in SEVERITIES" :key="severity" :label="tOr(t, 'severities.' + severity, severity)" :value="severity" /></el-select>
          </FormField>
          <FormField :label="t('common.source')">
            <el-input v-model="newIoc.source" :maxlength="256" :placeholder="t('threat.sourcePlaceholder')" />
          </FormField>
          <FormField :label="t('threat.tags')" :hint="t('threat.tagsHint')">
            <el-input v-model="newIoc.tags" :placeholder="t('threat.tagsPlaceholder')" />
          </FormField>
          <FormField :label="t('common.description')" full>
            <el-input v-model="newIoc.description" :maxlength="4096" type="textarea" :rows="3" />
          </FormField>
        </FormGrid></el-form><template #footer><el-button @click="iocDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button type="primary" :disabled="!canWrite || actionBusy || !newIoc.value.trim()" :loading="actionBusy" @click="saveIoc">{{ t('common.save') }}</el-button></template></el-dialog>

    <el-dialog v-model="importPreviewVisible" :before-close="importGuard.beforeClose" :title="t('threat.importPreviewTitle')" width="720px" :close-on-click-modal="false"><ActionFeedback :error="actionError" /><p v-if="importUnconfirmed" role="status" class="dialog-hint">{{ t('threat.importUnconfirmed') }}</p><p class="dialog-hint">{{ t('threat.importLimits') }}</p><p class="dialog-hint">{{ t('forms.importPreview', { count: importPreviewRows.length, shown: Math.min(importPreviewRows.length, 20) }) }}</p><el-table :data="importPreviewRows.slice(0, 20)" size="small" max-height="360" border><el-table-column prop="type" :label="t('common.type')" width="90" /><el-table-column prop="value" :label="t('threat.iocValue')" min-width="200" show-overflow-tooltip /><el-table-column prop="severity" :label="t('common.severity')" width="110" /><el-table-column prop="source" :label="t('common.source')" width="130" show-overflow-tooltip /></el-table><template #footer><el-button @click="importGuard.cancel">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="actionBusy" :disabled="!canWrite || actionBusy || !importPreviewRows.length" @click="confirmIocImport">{{ t('threat.confirmImport') }}</el-button></template></el-dialog>

    <el-drawer :model-value="showDetailDrawer" :title="detailIoc?.value || t('threat.iocDetails')" size="min(560px, 96vw)" @close="showDetailDrawer = false"><template v-if="detailIoc"><ActionFeedback :error="actionError" /><div class="threat-detail-status"><SevBadge :value="detailIoc.severity" /><el-tag :type="lifecycleLabel(detailIoc).type" size="small">{{ lifecycleLabel(detailIoc).text }}</el-tag><span class="mono">{{ detailIoc.type }}</span></div><dl class="threat-detail-grid"><dt>{{ t('common.source') }}</dt><dd>{{ detailIoc.source || t('time.notAvailable') }}</dd><dt>{{ t('threat.confidence') }}</dt><dd>{{ detailIoc.confidence == null ? t('time.notAvailable') : `${detailIoc.confidence}%` }}</dd><dt>{{ t('threat.validFrom') }}</dt><dd>{{ formatTime(detailIoc.validFrom) }}</dd><dt>{{ t('threat.validUntil') }}</dt><dd>{{ formatTime(detailIoc.validUntil || detailIoc.expiration) }}</dd><dt>{{ t('threat.provenance') }}</dt><dd>{{ detailIoc.provenance || t('time.notAvailable') }}</dd><dt>{{ t('common.description') }}</dt><dd>{{ detailIoc.description || t('time.notAvailable') }}</dd></dl><div v-if="detailIoc.tags?.length" class="threat-detail-tags"><el-tag v-for="tag in detailIoc.tags" :key="tag" size="small">{{ tag }}</el-tag></div><div v-if="canWrite" class="threat-detail-actions"><el-button type="primary" :disabled="actionBusy" @click="openEditIoc(detailIoc)">{{ t('common.edit') }}</el-button><el-button :type="detailIoc.revoked ? 'success' : 'warning'" :loading="lifecycleBusyId === detailIoc.id" :disabled="actionBusy" @click="toggleLifecycle(detailIoc)">{{ detailIoc.revoked ? t('threat.restore') : t('threat.revoke') }}</el-button><el-button type="danger" plain :disabled="actionBusy" @click="removeIoc(detailIoc)">{{ t('common.delete') }}</el-button></div></template></el-drawer>
  </div>
</template>
