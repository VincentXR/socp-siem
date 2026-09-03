<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/pagination/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElPagination from 'element-plus/es/components/pagination/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ref } from 'vue'
import AlarmDispositionDrawer from '../components/AlarmDispositionDrawer.vue'
import EmptyState from '../components/EmptyState.vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { relTime } from '../lib/ui'
import { SEVERITIES, type Alarm } from '../api'
import { useI18n } from '../composables/useI18n'

const props = defineProps<{
  filteredAlarms: Alarm[]
  alarmPageData: { total: number }
  alarmPageSize: number
  onSearch: () => void
  loadPage: () => void
  onSortChange: (field: 'occurredAt' | 'severity' | 'ruleName' | 'entity' | 'status' | 'riskScore', order: 'ascending' | 'descending') => void
  exportCsv: () => void
  exportJson: () => void
  goCase: () => void
  goSearch: () => void
}>()

const { t } = useI18n()

const keyword = defineModel<string>('keyword', { default: '' })
const severity = defineModel<string>('severity', { default: '' })
const status = defineModel<string>('status', { default: '' })
const rule = defineModel<string>('rule', { default: '' })
const pageNum = defineModel<number>('pageNum', { default: 1 })
const drawerVisible = ref(false)
const currentAlarm = ref<Alarm | null>(null)
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('alarms')
const DISP_STATUSES = ['OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED']

function openAlarm(alarm: Alarm) {
  currentAlarm.value = alarm
  drawerVisible.value = true
}

function openAlarmRow(row: unknown) {
  openAlarm(row as Alarm)
}

function handleSortChange({ prop, order }: { prop?: string | null; order?: 'ascending' | 'descending' | null }) {
  const allowed = ['occurredAt', 'severity', 'ruleName', 'entity', 'status', 'riskScore'] as const
  const field = allowed.includes(prop as typeof allowed[number]) ? prop as typeof allowed[number] : 'occurredAt'
  props.onSortChange(field, order ?? 'descending')
}
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('alarms.title')" :description="t('alarms.description')" />
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
        <el-button size="small" @click="props.exportCsv">{{ t('common.exportCsv') }}</el-button>
        <el-button size="small" @click="props.exportJson">{{ t('common.exportJson') }}</el-button>
      </div>
    </div>

    <el-card shadow="never" class="alarm-table-card">
      <el-table :data="props.filteredAlarms" class="alarm-table" height="calc(100vh - 318px)" size="small" row-key="id" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @sort-change="handleSortChange" @row-click="openAlarmRow">
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
      <EmptyState v-if="!props.filteredAlarms.length" :title="t('alarms.noAlarmsFound')" :description="t('alarms.adjustFiltersHint')" />
    </el-card>

    <div class="alarm-pagination">
      <el-pagination v-model:current-page="pageNum" :page-size="props.alarmPageSize" :total="props.alarmPageData.total" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="props.loadPage" @size-change="() => { pageNum = 1; props.loadPage() }" />
    </div>

    <AlarmDispositionDrawer v-model="drawerVisible" :alarm="currentAlarm" :go-case="props.goCase" :go-search="props.goSearch" />
  </div>
</template>
