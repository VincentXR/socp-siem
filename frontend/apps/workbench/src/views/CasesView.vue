<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/descriptions/style/css.mjs'
import 'element-plus/es/components/divider/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import 'element-plus/es/components/timeline/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import { ElDescriptions, ElDescriptionsItem } from 'element-plus/es/components/descriptions/index.mjs'
import ElDivider from 'element-plus/es/components/divider/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
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
const createDialogVisible = ref(false)
const caseForm = ref({ title: '', entity: '', severity: 'HIGH', assignee: '' })
const newStatus = ref('')
const statusFilter = ref('')
const casesList = useResourceList<CaseInfo>({
  searchFields: item => [item.id, item.title, item.entity, item.severity, item.status],
  filter: item => !statusFilter.value || item.status === statusFilter.value,
  sortValue: (item, prop) => prop === 'alarmCount' ? item.alarmIds.length : (item as unknown as Record<string, unknown>)[prop],
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

function openCreateCase() {
  caseForm.value = { title: '', entity: '', severity: 'HIGH', assignee: '' }
  createDialogVisible.value = true
}

async function saveCase() {
  if (!caseForm.value.title.trim()) {
    ElMessage.warning('请输入案件标题')
    return
  }
  try {
    await caseApi.create({
      title: caseForm.value.title.trim(), entity: caseForm.value.entity.trim(),
      severity: caseForm.value.severity, assignee: caseForm.value.assignee.trim() || undefined,
    })
    createDialogVisible.value = false
    ElMessage.success('案件已创建')
    await loadCases()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建案件失败')
  }
}

onMounted(loadCases)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="案件管理" description="将告警聚合为可跟踪案件，维护处置状态和事件时间线。">
      <template #actions>
        <el-button type="primary" size="small" @click="openCreateCase">新增案件</el-button>
        <el-button size="small" @click="caseApi.export()">导出案件 JSON</el-button>
      </template>
    </PageHeader>

    <div class="page-metrics">
      <MetricCard label="案件总数" tone="info">{{ stats.total ?? 0 }}</MetricCard>
      <MetricCard label="进行中" tone="warning">{{ stats.open ?? 0 }}</MetricCard>
      <MetricCard label="已解决" tone="success">{{ stats.resolved ?? 0 }}</MetricCard>
    </div>

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="casesFiltered.length">
      <template #toolbar>
        <FilterToolbar :count="casesFiltered.length">
        <el-input v-model="keyword" placeholder="搜索案件 ID / 标题 / 实体" clearable @input="page = 1" />
        <el-select v-model="statusFilter" placeholder="全部状态" clearable @change="page = 1">
          <el-option v-for="status in ['OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED']" :key="status" :label="status" :value="status" />
        </el-select>
        </FilterToolbar>
      </template>
      <el-table :data="casesPaged" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @sort-change="casesList.onSortChange">
        <el-table-column prop="id" column-key="id" label="案件 ID" :width="columnWidth('id', 180)" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="title" column-key="title" label="标题" :width="columnWidth('title')" min-width="180" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="entity" column-key="entity" label="实体" :width="columnWidth('entity', 130)" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="severity" column-key="severity" label="级别" :width="columnWidth('severity', 90)" sortable="custom"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
        <el-table-column prop="status" column-key="status" label="状态" :width="columnWidth('status', 120)" sortable="custom"><template #default="{ row }"><el-tag :type="row.status === 'OPEN' ? 'danger' : row.status === 'RESOLVED' || row.status === 'CLOSED' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="alarmCount" column-key="alarmCount" label="关联告警" :width="columnWidth('alarmCount', 90)" sortable="custom"><template #default="{ row }">{{ row.alarmIds.length }}</template></el-table-column>
        <el-table-column label="操作" width="90" :resizable="false"><template #default="{ row }"><el-button link type="primary" size="small" @click="openCaseRow(row)">详情/时间线</el-button></template></el-table-column>
      </el-table>
    </DataTableCard>

    <el-dialog v-model="createDialogVisible" title="新增案件" width="560px">
      <el-form label-width="80px">
        <el-form-item label="标题" required><el-input v-model="caseForm.title" placeholder="如：SSH 暴力破解调查" /></el-form-item>
        <el-form-item label="关联实体"><el-input v-model="caseForm.entity" placeholder="如：203.0.113.10 或 root" /></el-form-item>
        <el-form-item label="级别"><el-select v-model="caseForm.severity" style="width: 180px"><el-option v-for="level in ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']" :key="level" :label="level" :value="level" /></el-select></el-form-item>
        <el-form-item label="负责人"><el-input v-model="caseForm.assignee" placeholder="可选，如 analyst" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveCase">创建案件</el-button>
      </template>
    </el-dialog>

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
