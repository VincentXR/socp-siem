<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/pagination/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import ElPagination from 'element-plus/es/components/pagination/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AlarmDispositionDrawer from '../components/AlarmDispositionDrawer.vue'
import EmptyState from '../components/EmptyState.vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { relTime } from '../lib/ui'
import { SEVERITIES, type Alarm } from '../api'
import { batchUpdateAlarmDisposition } from '../api/alarms'
import { useI18n } from '../composables/useI18n'

const props = defineProps<{
  filteredAlarms: Alarm[]
  alarmPageData: { total: number }
  alarmPageSize: number
  loading: boolean
  error: string
  onSearch: () => void
  loadPage: () => void
  onSortChange: (field: 'occurredAt' | 'severity' | 'ruleName' | 'entity' | 'status' | 'riskScore', order: 'ascending' | 'descending') => void
  exportCsv: () => Promise<void>
  exportJson: () => Promise<void>
  goCase: () => void
  goSearch: () => void
  canWrite?: boolean
}>()

const { t } = useI18n()

const keyword = defineModel<string>('keyword', { default: '' })
const severity = defineModel<string>('severity', { default: '' })
const status = defineModel<string>('status', { default: '' })
const rule = defineModel<string>('rule', { default: '' })
const pageNum = defineModel<number>('pageNum', { default: 1 })
const drawerVisible = ref(false)
const currentAlarm = ref<Alarm | null>(null)
const selectedAlarms = ref<Alarm[]>([])
const batchStatus = ref('INVESTIGATING')
const batchAssignee = ref('')
const batchBusy = ref(false)
const batchError = ref('')
const exporting = ref('')
const exportError = ref('')
const route = useRoute()
const router = useRouter()
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('alarms')
const DISP_STATUSES = ['OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED']

function openAlarm(alarm: Alarm) {
  currentAlarm.value = alarm
  drawerVisible.value = true
  void router.replace({ query: { ...route.query, alarmId: alarm.id } })
}

function openAlarmRow(row: unknown) {
  openAlarm(row as Alarm)
}

function handleSortChange({ prop, order }: { prop?: string | null; order?: 'ascending' | 'descending' | null }) {
  const allowed = ['occurredAt', 'severity', 'ruleName', 'entity', 'status', 'riskScore'] as const
  const field = allowed.includes(prop as typeof allowed[number]) ? prop as typeof allowed[number] : 'occurredAt'
  props.onSortChange(field, order ?? 'descending')
}

function handleSelectionChange(rows: Alarm[]): void {
  selectedAlarms.value = rows
}

async function handleBatchUpdate(): Promise<void> {
  if (!props.canWrite || batchBusy.value || !selectedAlarms.value.length) return
  batchBusy.value = true
  batchError.value = ''
  try {
    await batchUpdateAlarmDisposition(
      selectedAlarms.value.map(alarm => alarm.id),
      { status: batchStatus.value, assignee: batchAssignee.value.trim() || undefined },
    )
    ElMessage.success(t('common.success'))
    selectedAlarms.value = []
    batchAssignee.value = ''
    await props.loadPage()
  } catch (error) {
    batchError.value = error instanceof Error ? error.message : String(error)
  } finally {
    batchBusy.value = false
  }
}

function syncAlarmFromRoute(): void {
  const id = typeof route.query.alarmId === 'string' ? route.query.alarmId : ''
  if (!id) {
    currentAlarm.value = null
    drawerVisible.value = false
    return
  }
  const alarm = props.filteredAlarms.find(item => item.id === id)
  if (alarm) {
    currentAlarm.value = alarm
    drawerVisible.value = true
  }
}

watch(() => [route.query.alarmId, props.filteredAlarms] as const, syncAlarmFromRoute, { immediate: true, deep: true })
watch(drawerVisible, visible => {
  if (!visible && route.query.alarmId) {
    const query = { ...route.query }
    delete query.alarmId
    void router.replace({ query })
  }
})

async function handleExport(format: 'csv' | 'json', exporter: () => Promise<void>): Promise<void> {
  if (exporting.value) return
  exporting.value = format
  exportError.value = ''
  try { await exporter() }
  catch (error) { exportError.value = error instanceof Error ? error.message : String(error) }
  finally { exporting.value = '' }
}
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :eyebrow="t('menuGroup.alarmsAndEvents')" :title="t('alarms.title')" :description="t('alarms.description')" />
    <div class="alarm-toolbar">
      <div class="alarm-filter-controls">
        <el-input v-model="keyword" class="alarm-keyword-input" :placeholder="t('alarms.keywordPlaceholder')" clearable style="width:240px" @keyup.enter="props.onSearch" @clear="props.onSearch" />
        <el-input v-model="rule" class="alarm-rule-input" :placeholder="t('alarms.ruleFilter')" clearable style="width:170px" @keyup.enter="props.onSearch" @clear="props.onSearch" />
        <el-select v-model="severity" :placeholder="t('alarms.severityFilter')" clearable style="width:140px" @change="props.onSearch">
          <el-option v-for="item in SEVERITIES" :key="item" :label="t('severities.' + item) || item" :value="item" />
        </el-select>
        <el-select v-model="status" :placeholder="t('alarms.statusFilter')" clearable style="width:150px" @change="props.onSearch">
          <el-option v-for="item in DISP_STATUSES" :key="item" :label="t('statuses.' + item) || item" :value="item" />
        </el-select>
        <el-button size="small" @click="props.onSearch">{{ t('common.search') }}</el-button>
      </div>
      <div class="alarm-toolbar-actions">
        <span class="toolbar-count">{{ t('common.total', { total: props.alarmPageData.total }) }}</span>
        <el-button size="small" :loading="exporting === 'csv'" :disabled="Boolean(exporting)" @click="handleExport('csv', props.exportCsv)">{{ t('common.exportCsv') }}</el-button>
        <el-button size="small" :loading="exporting === 'json'" :disabled="Boolean(exporting)" @click="handleExport('json', props.exportJson)">{{ t('common.exportJson') }}</el-button>
      </div>
    </div>

    <div v-if="props.canWrite && selectedAlarms.length" class="alarm-batchbar">
      <strong>{{ selectedAlarms.length }} {{ t('alarms.selected') }}</strong>
      <span>{{ t('alarms.batchHint') }}</span>
      <el-select v-model="batchStatus" size="small" style="width:150px">
        <el-option v-for="item in DISP_STATUSES" :key="item" :label="t('statuses.' + item) || item" :value="item" />
      </el-select>
      <el-input v-model="batchAssignee" size="small" :placeholder="t('drawer.assigneePlaceholder')" style="width:180px" />
      <el-button size="small" type="primary" :loading="batchBusy" @click="handleBatchUpdate">{{ t('common.update') }}</el-button>
      <span v-if="batchError" class="alarm-batch-error" role="alert">{{ batchError }}</span>
    </div>

    <div v-if="exportError" class="alarm-feedback error" role="alert"><strong>{{ t('alarms.exportFailed') }}</strong><span>{{ exportError }}</span></div>

    <div v-if="props.error" class="alarm-feedback error" role="alert">
      <strong>{{ t('alarms.loadFailed') }}</strong>
      <span>{{ props.error }}</span>
      <el-button size="small" @click="props.loadPage">{{ t('common.refresh') }}</el-button>
    </div>

    <el-card shadow="never" class="alarm-table-card">
      <el-table :data="props.filteredAlarms" class="alarm-table" height="calc(100vh - 368px)" size="small" row-key="id" border allow-drag-last-column :class="{ 'is-loading': props.loading }" @header-dragend="onHeaderDragEnd" @sort-change="handleSortChange" @row-click="openAlarmRow" @selection-change="handleSelectionChange">
        <el-table-column v-if="props.canWrite" type="selection" width="44" fixed="left" />
        <el-table-column prop="occurredAt" column-key="occurredAt" :label="t('alarms.occurredAt')" :width="columnWidth('occurredAt', 172)" sortable="custom"><template #default="{ row }"><span class="mono">{{ relTime(row.occurredAt) }}</span></template></el-table-column>
        <el-table-column prop="severity" column-key="severity" :label="t('common.severity')" :width="columnWidth('severity', 100)" sortable="custom"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
        <el-table-column prop="title" column-key="title" :label="t('alarms.alertTitle')" :width="columnWidth('title', 220)" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.title || row.ruleName || row.ruleId }}</template></el-table-column>
        <el-table-column prop="ruleName" column-key="ruleName" :label="t('alarms.ruleName')" :width="columnWidth('ruleName')" min-width="180" sortable="custom" show-overflow-tooltip><template #default="{ row }">{{ row.ruleName || row.ruleId }}</template></el-table-column>
        <el-table-column prop="entity" column-key="entity" :label="t('common.entity')" :width="columnWidth('entity')" min-width="150" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="status" column-key="status" :label="t('common.status')" :width="columnWidth('status', 125)" sortable="custom"><template #default="{ row }"><span class="alarm-status" :class="(row.status || 'OPEN').toLowerCase()">{{ t('statuses.' + (row.status || 'OPEN')) || row.status }}</span></template></el-table-column>
        <el-table-column prop="riskScore" column-key="riskScore" :label="t('alarms.riskScore')" :width="columnWidth('riskScore', 90)" sortable="custom"><template #default="{ row }">{{ row.riskScore ?? '—' }}</template></el-table-column>
        <el-table-column prop="message" column-key="message" :label="t('common.message')" :width="columnWidth('message')" min-width="260" show-overflow-tooltip />
        <el-table-column :label="t('common.actions')" width="78" fixed="right" :resizable="false"><template #default="{ row }"><el-button link type="primary" size="small" @click.stop="openAlarmRow(row)">{{ t('alarms.triage') }}</el-button></template></el-table-column>
      </el-table>
      <div v-if="props.loading" class="alarm-loading">{{ t('common.loading') }}</div>
      <EmptyState v-else-if="!props.error && !props.filteredAlarms.length" :title="t('alarms.noAlarmsFound')" :description="t('alarms.adjustFiltersHint')" />
    </el-card>

    <div class="alarm-pagination">
      <el-pagination v-model:current-page="pageNum" :page-size="props.alarmPageSize" :total="props.alarmPageData.total" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="props.loadPage" @size-change="() => { pageNum = 1; props.loadPage() }" />
    </div>

    <AlarmDispositionDrawer v-model="drawerVisible" :alarm="currentAlarm" :go-case="props.goCase" :go-search="props.goSearch" :can-write="props.canWrite" @updated="props.loadPage" />
  </div>
</template>
