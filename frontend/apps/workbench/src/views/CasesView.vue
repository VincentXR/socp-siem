<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/descriptions/style/css.mjs'
import 'element-plus/es/components/divider/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import 'element-plus/es/components/timeline/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import { ElDescriptions, ElDescriptionsItem } from 'element-plus/es/components/descriptions/index.mjs'
import ElDivider from 'element-plus/es/components/divider/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { ElTimeline, ElTimelineItem } from 'element-plus/es/components/timeline/index.mjs'
import { onMounted, ref } from 'vue'
import DataTableCard from '../components/DataTableCard.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import { useResourceList } from '../composables/useResourceList'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { caseApi, type CaseInfo, type TimelineEvent } from '../api/domains'

const stats = ref<{ total?: number; open?: number; resolved?: number }>({})
const detail = ref<CaseInfo | null>(null)
const timeline = ref<TimelineEvent[]>([])
const drawerVisible = ref(false)
const newStatus = ref('')
const statusFilter = ref('')
const casesList = useResourceList<CaseInfo>({
  searchFields: item => [item.id, item.title, item.entity, item.severity, item.status],
  filter: item => !statusFilter.value || item.status === statusFilter.value,
})
const { items: cases, page, size, keyword, loading, filtered: casesFiltered, paged: casesPaged, setItems } = casesList
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('cases')

async function loadCases() {
  if (loading.value) return
  loading.value = true
  try {
    const [caseResult, statResult] = await Promise.allSettled([caseApi.list(), caseApi.stats()])
    if (caseResult.status === 'fulfilled') setItems(caseResult.value)
    if (statResult.status === 'fulfilled') stats.value = statResult.value
  } finally { loading.value = false }
}

async function openCase(item: CaseInfo) {
  detail.value = item
  newStatus.value = item.status
  drawerVisible.value = true
  try { timeline.value = (await caseApi.timeline(item.id)).timeline } catch { timeline.value = [] }
}
function openCaseRow(row: unknown) { openCase(row as CaseInfo) }

async function updateStatus() {
  if (!detail.value || !newStatus.value) return
  const result = await caseApi.updateStatus(detail.value.id, newStatus.value)
  detail.value = result.case
  await loadCases()
}

onMounted(loadCases)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="案件管理" description="将告警聚合为可跟踪案件，维护处置状态和事件时间线。">
      <template #actions><el-button size="small" @click="caseApi.export()">导出案件 JSON</el-button></template>
    </PageHeader>

    <div class="page-metrics">
      <MetricCard label="案件总数" tone="info">{{ stats.total ?? 0 }}</MetricCard>
      <MetricCard label="进行中" tone="warning">{{ stats.open ?? 0 }}</MetricCard>
      <MetricCard label="已解决" tone="success">{{ stats.resolved ?? 0 }}</MetricCard>
    </div>

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="casesFiltered.length">
      <template #toolbar>
        <FilterToolbar>
        <el-input v-model="keyword" placeholder="搜索案件 ID / 标题 / 实体" clearable @input="page = 1" />
        <el-select v-model="statusFilter" placeholder="全部状态" clearable @change="page = 1">
          <el-option v-for="status in ['OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED']" :key="status" :label="status" :value="status" />
        </el-select>
        <span class="toolbar-count">共 {{ casesFiltered.length }} 条</span>
        </FilterToolbar>
      </template>
      <el-table :data="casesPaged" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd">
        <el-table-column prop="id" column-key="id" label="案件 ID" :width="columnWidth('id', 180)" sortable show-overflow-tooltip />
        <el-table-column prop="title" column-key="title" label="标题" :width="columnWidth('title')" min-width="180" sortable show-overflow-tooltip />
        <el-table-column prop="entity" column-key="entity" label="实体" :width="columnWidth('entity', 130)" sortable show-overflow-tooltip />
        <el-table-column prop="severity" column-key="severity" label="级别" :width="columnWidth('severity', 90)" sortable><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
        <el-table-column prop="status" column-key="status" label="状态" :width="columnWidth('status', 120)" sortable><template #default="{ row }"><el-tag :type="row.status === 'OPEN' ? 'danger' : row.status === 'RESOLVED' || row.status === 'CLOSED' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="alarmCount" column-key="alarmCount" label="关联告警" :width="columnWidth('alarmCount', 90)" sortable><template #default="{ row }">{{ row.alarmIds.length }}</template></el-table-column>
        <el-table-column label="操作" width="90" :resizable="false"><template #default="{ row }"><el-button link type="primary" size="small" @click="openCaseRow(row)">详情/时间线</el-button></template></el-table-column>
      </el-table>
    </DataTableCard>

    <el-drawer v-model="drawerVisible" :title="`案件 · ${detail?.title ?? ''}`" size="520px">
      <template v-if="detail">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="案件 ID">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="实体">{{ detail.entity }}</el-descriptions-item>
          <el-descriptions-item label="级别"><SevBadge :value="detail.severity" /></el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
          <el-descriptions-item label="关联规则" :span="2">{{ detail.ruleIds.join(', ') || '—' }}</el-descriptions-item>
          <el-descriptions-item label="关联告警" :span="2">{{ detail.alarmIds.join(', ') || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div class="case-status-row"><el-select v-model="newStatus"><el-option v-for="status in ['OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED']" :key="status" :label="status" :value="status" /></el-select><el-button type="primary" @click="updateStatus">更新状态</el-button></div>
        <el-divider content-position="left">事件时间线</el-divider>
        <el-timeline><el-timeline-item v-for="(event, index) in timeline" :key="index" :timestamp="event.ts" placement="top"><div>{{ event.message }}</div><div class="case-event-meta">{{ event.type }} · {{ event.source }}</div></el-timeline-item></el-timeline>
      </template>
    </el-drawer>
  </div>
</template>
