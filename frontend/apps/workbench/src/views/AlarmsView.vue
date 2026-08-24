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
    <PageHeader title="告警查询" description="按规则、实体和严重级别筛选告警，并从右侧抽屉完成处置。" />
    <div class="alarm-toolbar">
      <div class="alarm-filter-controls">
        <el-input v-model="keyword" class="alarm-keyword-input" placeholder="关键词：实体 / 消息" clearable style="width:230px" @keyup.enter="props.onSearch" @clear="props.onSearch" />
        <el-input v-model="rule" class="alarm-rule-input" placeholder="规则 ID" clearable style="width:170px" @keyup.enter="props.onSearch" @clear="props.onSearch" />
        <el-select v-model="severity" placeholder="全部级别" clearable style="width:140px" @change="props.onSearch"><el-option v-for="item in SEVERITIES" :key="item" :label="item" :value="item" /></el-select>
        <el-select v-model="status" placeholder="全部状态" clearable style="width:150px" @change="props.onSearch"><el-option v-for="item in DISP_STATUSES" :key="item" :label="item" :value="item" /></el-select>
        <el-button size="small" @click="props.onSearch">查询</el-button>
      </div>
      <div class="alarm-toolbar-actions">
        <span class="toolbar-count">共 {{ props.alarmPageData.total }} 条</span>
        <el-button size="small" @click="props.exportCsv">导出 CSV</el-button>
        <el-button size="small" @click="props.exportJson">导出 JSON</el-button>
      </div>
    </div>

    <el-card shadow="never" class="alarm-table-card">
      <el-table :data="props.filteredAlarms" class="alarm-table" height="calc(100vh - 318px)" size="small" row-key="id" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @sort-change="handleSortChange" @row-click="openAlarmRow">
        <el-table-column prop="occurredAt" column-key="occurredAt" label="发生时间" :width="columnWidth('occurredAt', 172)" sortable="custom"><template #default="{ row }"><span class="mono">{{ relTime(row.occurredAt) }}</span></template></el-table-column>
        <el-table-column prop="severity" column-key="severity" label="级别" :width="columnWidth('severity', 100)" sortable="custom"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
        <el-table-column prop="ruleName" column-key="ruleName" label="规则" :width="columnWidth('ruleName')" min-width="180" sortable="custom" show-overflow-tooltip><template #default="{ row }">{{ row.ruleName || row.ruleId }}</template></el-table-column>
        <el-table-column prop="entity" column-key="entity" label="实体" :width="columnWidth('entity')" min-width="150" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="status" column-key="status" label="状态" :width="columnWidth('status', 125)" sortable="custom"><template #default="{ row }"><span class="alarm-status" :class="(row.status || 'OPEN').toLowerCase()">{{ row.status || 'OPEN' }}</span></template></el-table-column>
        <el-table-column prop="riskScore" column-key="riskScore" label="风险分" :width="columnWidth('riskScore', 90)" sortable="custom"><template #default="{ row }">{{ row.riskScore ?? '—' }}</template></el-table-column>
        <el-table-column prop="message" column-key="message" label="消息" :width="columnWidth('message')" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="78" fixed="right" :resizable="false"><template #default="{ row }"><el-button link type="primary" size="small" @click.stop="openAlarmRow(row)">处置</el-button></template></el-table-column>
      </el-table>
      <EmptyState v-if="!props.filteredAlarms.length" title="暂无告警" description="调整筛选条件后重试，或等待新的告警进入系统。" />
    </el-card>

    <div class="alarm-pagination">
      <el-pagination v-model:current-page="pageNum" :page-size="props.alarmPageSize" :total="props.alarmPageData.total" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="props.loadPage" @size-change="() => { pageNum = 1; props.loadPage() }" />
    </div>

    <AlarmDispositionDrawer v-model="drawerVisible" :alarm="currentAlarm" :go-case="props.goCase" :go-search="props.goSearch" />
  </div>
</template>
