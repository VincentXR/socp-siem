<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { onMounted, ref } from 'vue'
import DataTableCard from '../components/DataTableCard.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import { useResourceList } from '../composables/useResourceList'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { threatIntelApi, type Ioc } from '../api/domains'
import { SEVERITIES } from '../api'
import { readImportRows, type ImportRow } from '../lib/resource-import'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()

const tiStat = ref<{ total?: number; byType?: Record<string, number> }>({})
const iocType = ref('')
const showIocDialog = ref(false)
const iocImportInput = ref<HTMLInputElement | null>(null)
const newIoc = ref({ type: 'ip', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' })
const tiMatchResult = ref<{ value: string; matched: boolean; ioc?: Ioc } | null>(null)
const iocList = useResourceList<Ioc>({
  searchFields: ioc => [ioc.type, ioc.value, ioc.severity, ioc.source, ioc.description],
  filter: ioc => !iocType.value || ioc.type === iocType.value,
})
const {
  items: iocs,
  page: iocPage,
  size: iocSize,
  keyword: iocKeyword,
  loading,
  filtered: iocsFiltered,
  paged: iocsPaged,
  setItems,
  resetPage,
} = iocList
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('threat-intel')
const rowValue = (row: ImportRow, ...keys: string[]) => {
  const key = keys.find(candidate => row[candidate] !== undefined)
  return key ? String(row[key] ?? '').trim() : ''
}

function openIocDialog() {
  newIoc.value = { type: 'ip', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' }
  showIocDialog.value = true
}

async function loadTi() {
  if (loading.value) return
  loading.value = true
  try {
    const [listResult, statResult] = await Promise.allSettled([threatIntelApi.list(iocType.value || undefined), threatIntelApi.stats()])
    if (listResult.status === 'fulfilled') {
      setItems(listResult.value)
      resetPage()
    }
    if (statResult.status === 'fulfilled') tiStat.value = statResult.value
  } finally {
    loading.value = false
  }
}

async function addIoc() {
  if (!newIoc.value.value.trim()) return
  await threatIntelApi.create({
    type: newIoc.value.type,
    value: newIoc.value.value.trim(),
    severity: newIoc.value.severity,
    source: newIoc.value.source,
    description: newIoc.value.description || undefined,
    tags: newIoc.value.tags ? newIoc.value.tags.split(',').map(s => s.trim()).filter(Boolean) : undefined,
  })
  showIocDialog.value = false
  ElMessage.success(t('threat.added'))
  await loadTi()
}

async function removeIoc(id: string) {
  await threatIntelApi.remove(id)
  ElMessage.success(t('threat.deleted'))
  await loadTi()
}

async function doTiMatch() {
  const val = iocKeyword.value.trim()
  if (!val) {
    tiMatchResult.value = null
    return
  }
  tiMatchResult.value = await threatIntelApi.match(val)
}

function selectIocImport() {
  iocImportInput.value?.click()
}

async function importIocFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const rows = await readImportRows(file)
    const payload = rows.map(row => {
      const type = rowValue(row, 'type', '类型').toLowerCase() || 'ip'
      const rawSeverity = rowValue(row, 'severity', '严重度', '级别').toUpperCase()
      const severity = SEVERITIES.includes(rawSeverity as typeof SEVERITIES[number]) ? rawSeverity : 'HIGH'
      const rawTags = rowValue(row, 'tags', '标签')
      return {
        type, value: rowValue(row, 'value', '值', '情报值', '指标值'),
        severity, source: rowValue(row, 'source', '来源') || 'import',
        description: rowValue(row, 'description', '描述'),
        tags: rawTags ? rawTags.split(/[,，\s]+/).filter(Boolean) : undefined,
      }
    })
    const result = await threatIntelApi.bulkImport(payload)
    if (result.skipped) ElMessage.warning(t('threat.importSkipped', { imported: result.imported, skipped: result.skipped }))
    else ElMessage.success(t('threat.importSuccess', { count: result.imported }))
    await loadTi()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (t('threat.importFailed')))
  } finally {
    input.value = ''
  }
}

onMounted(loadTi)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('threat.title')" :description="t('threat.description')">
      <template #actions><el-button size="small" :loading="loading" @click="loadTi">{{ t('common.refresh') }}</el-button></template>
    </PageHeader>
    <div class="page-metrics ti-metrics">
      <el-card shadow="never" :body-style="{ padding: '12px 18px' }">
        <div style="font-size:12px;color:var(--ns-text-3)">{{ t('threat.total') }}</div>
        <div style="font-size:22px;font-weight:700">{{ tiStat.total ?? 0 }}</div>
      </el-card>
      <el-card v-for="(count, kind) in (tiStat.byType || {})" :key="kind" shadow="never" :body-style="{ padding: '12px 18px' }">
        <div style="font-size:12px;color:var(--ns-text-3)">{{ kind }}</div>
        <div style="font-size:22px;font-weight:700">{{ count }}</div>
      </el-card>
    </div>
    <FilterToolbar class="ti-query-toolbar" :count="iocsFiltered.length">
      <el-input v-model="iocKeyword" :placeholder="t('threat.searchPlaceholder')" clearable @input="iocPage = 1" @keyup.enter="doTiMatch" />
      <el-button type="primary" @click="doTiMatch">{{ t('threat.checkMatch') }}</el-button>
      <el-select v-model="iocType" :placeholder="t('threat.allTypes')" clearable @change="loadTi">
        <el-option v-for="t in ['ip', 'domain', 'url', 'sha256', 'email']" :key="t" :label="t" :value="t" />
      </el-select>
    </FilterToolbar>
    <el-alert v-if="tiMatchResult" :title="tiMatchResult.matched ? t('threat.matched', { value: tiMatchResult.ioc?.value ?? '—', severity: tiMatchResult.ioc?.severity ?? '—' }) : t('threat.noMatch')" :type="tiMatchResult.matched ? 'error' : 'info'" :closable="false" style="margin-bottom:14px" />
    <div class="add-bar">
      <el-button type="primary" @click="openIocDialog">+ {{ t('threat.addIoc') }}</el-button>
      <el-button @click="selectIocImport">{{ t('threat.batchImport') }}</el-button>
      <input ref="iocImportInput" type="file" accept=".csv,.json,application/json,text/csv" hidden @change="importIocFile" />
      <span class="hint">{{ t('threat.descriptionHint') }}</span>
    </div>
    <el-dialog v-model="showIocDialog" :title="t('threat.addIoc')" width="560px">
      <el-form label-width="90px">
        <el-form-item :label="t('threat.iocValue')"><el-input v-model="newIoc.value" :placeholder="t('threat.valuePlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.type')"><el-select v-model="newIoc.type" style="width:160px"><el-option v-for="t in ['ip', 'domain', 'url', 'sha256', 'email']" :key="t" :label="t" :value="t" /></el-select></el-form-item>
        <el-form-item :label="t('common.severity')"><el-select v-model="newIoc.severity" style="width:160px"><el-option v-for="s in SEVERITIES" :key="s" :label="t('severities.' + s) || s" :value="s" /></el-select></el-form-item>
        <el-form-item :label="t('common.description')"><el-input v-model="newIoc.description" :placeholder="t('common.description')" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="showIocDialog = false">{{ t('common.cancel') }}</el-button><el-button type="success" @click="addIoc">{{ t('common.submit') }}</el-button></template>
    </el-dialog>
    <DataTableCard v-model:current-page="iocPage" v-model:page-size="iocSize" :total="iocsFiltered.length">
      <el-table :data="iocsPaged" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @sort-change="iocList.onSortChange">
        <el-table-column prop="type" column-key="type" :label="t('common.type')" :width="columnWidth('type', 90)" sortable="custom" />
        <el-table-column prop="value" column-key="value" :label="t('threat.iocValue')" :width="columnWidth('value')" min-width="160" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="severity" column-key="severity" :label="t('common.severity')" :width="columnWidth('severity', 90)" sortable="custom"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
        <el-table-column prop="source" column-key="source" :label="t('common.source')" :width="columnWidth('source', 100)" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="description" column-key="description" :label="t('common.description')" :width="columnWidth('description')" min-width="160" sortable="custom" show-overflow-tooltip />
        <el-table-column :label="t('common.actions')" width="80" :resizable="false"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeIoc(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
      </el-table>
    </DataTableCard>
  </div>
</template>
