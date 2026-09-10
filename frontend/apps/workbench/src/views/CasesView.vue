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
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DataTableCard from '../components/DataTableCard.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import { useResourceList } from '../composables/useResourceList'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { caseApi, type CaseInfo, type TimelineEvent } from '../api/domains'
import { useI18n } from '../composables/useI18n'
import { WORKBENCH_STATE } from '../app/workbenchState'

const { t } = useI18n()
const workbenchState = inject(WORKBENCH_STATE, null)
const route = useRoute()
const router = useRouter()

const stats = ref<{ total?: number; open?: number; resolved?: number }>({})
const detail = ref<CaseInfo | null>(null)
const timeline = ref<TimelineEvent[]>([])
const drawerVisible = ref(false)
const createDialogVisible = ref(false)
const caseForm = ref({ title: '', entity: '', severity: 'HIGH', assignee: '' })
const newStatus = ref('')
const statusFilter = ref('')
const loadError = ref('')
const caseSorters: Record<string, (item: CaseInfo) => unknown> = {
  alarmCount: item => item.alarmIds.length,
  id: item => item.id,
  title: item => item.title,
  entity: item => item.entity,
  severity: item => item.severity,
  status: item => item.status,
  assignee: item => item.assignee,
  createdAt: item => item.createdAt ?? '',
  updatedAt: item => item.updatedAt ?? '',
}
const casesList = useResourceList<CaseInfo>({
  searchFields: item => [item.id, item.title, item.entity, item.severity, item.status],
  filter: item => !statusFilter.value || item.status === statusFilter.value,
  sortValue: (item, prop) => caseSorters[prop]?.(item) ?? '',
})
const { items: cases, page, size, keyword, loading, filtered: casesFiltered, paged: casesPaged, setItems } = casesList
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('cases')
const assigneeOptions = computed(() => Array.from(new Set([
  ...(workbenchState?.operatorOptions.value ?? []),
  workbenchState?.currentUser.value ?? '',
  ...cases.value.map(item => item.assignee ?? ''),
].filter(Boolean))))

async function loadCases() {
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    const [caseResult, statResult] = await Promise.allSettled([caseApi.list(), caseApi.stats()])
    if (caseResult.status === 'fulfilled') setItems(caseResult.value)
    else loadError.value = caseResult.reason instanceof Error ? caseResult.reason.message : String(caseResult.reason)
    if (statResult.status === 'fulfilled') stats.value = statResult.value
    openCaseFromQuery()
  } finally { loading.value = false }
}

async function openCase(item: CaseInfo) {
  detail.value = item
  newStatus.value = item.status
  drawerVisible.value = true
  try { timeline.value = (await caseApi.timeline(item.id)).timeline } catch { timeline.value = [] }
}
function openCaseRow(row: unknown) { openCase(row as CaseInfo) }

function openCaseFromQuery(): void {
  const id = typeof route.query.caseId === 'string' ? route.query.caseId : ''
  if (!id) return
  const match = cases.value.find(item => item.id === id || item.caseNo === id)
  if (match && detail.value?.id !== match.id) void openCase(match)
}

function openAlarm(id: string): void {
  void router.push({ name: 'alarms', query: { alarmId: id } })
}

async function updateStatus() {
  if (!detail.value || !newStatus.value) return
  try {
    const result = await caseApi.updateStatus(detail.value.id, newStatus.value)
    detail.value = result.case
    await loadCases()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('common.failed'))
  }
}

function openCreateCase() {
  caseForm.value = { title: '', entity: '', severity: 'HIGH', assignee: '' }
  createDialogVisible.value = true
}

async function saveCase() {
  if (!caseForm.value.title.trim()) {
    ElMessage.warning(t('cases.pleaseEnterTitle'))
    return
  }
  try {
    await caseApi.create({
      title: caseForm.value.title.trim(), entity: caseForm.value.entity.trim(),
      severity: caseForm.value.severity, assignee: caseForm.value.assignee.trim() || undefined,
    })
    createDialogVisible.value = false
    ElMessage.success(t('cases.createdSuccessfully'))
    await loadCases()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (t('cases.createFailed')))
  }
}

onMounted(loadCases)
watch(() => route.query.caseId, openCaseFromQuery)
watch(drawerVisible, visible => {
  if (!visible && route.query.caseId) {
    const query = { ...route.query }
    delete query.caseId
    void router.replace({ query })
  }
})
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :eyebrow="t('menuGroup.alarmsAndEvents')" :title="t('cases.title')" :description="t('cases.description')">
      <template #actions>
        <el-button type="primary" size="small" @click="openCreateCase">{{ t('cases.createCase') }}</el-button>
        <el-button size="small" @click="caseApi.export()">{{ t('cases.exportJson') }}</el-button>
      </template>
    </PageHeader>

    <div class="page-metrics">
      <MetricCard :label="t('cases.totalCases')" tone="info">{{ stats.total ?? 0 }}</MetricCard>
      <MetricCard :label="t('cases.activeCases')" tone="warning">{{ stats.open ?? 0 }}</MetricCard>
      <MetricCard :label="t('cases.resolvedCases')" tone="success">{{ stats.resolved ?? 0 }}</MetricCard>
    </div>

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="casesFiltered.length" :loading="loading" :error="loadError" :retry="loadCases" :empty-title="t('cases.emptyCases')" :empty-description="t('cases.description')">
      <template #toolbar>
        <FilterToolbar :count="casesFiltered.length">
        <el-input v-model="keyword" :placeholder="t('cases.searchPlaceholder')" clearable @input="page = 1" />
        <el-select v-model="statusFilter" :placeholder="t('cases.allStatuses')" clearable @change="page = 1">
          <el-option v-for="status in ['OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED']" :key="status" :label="t('statuses.' + status) || status" :value="status" />
        </el-select>
        </FilterToolbar>
      </template>
      <el-table :data="casesPaged" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @sort-change="casesList.onSortChange" @row-click="openCaseRow">
        <el-table-column prop="id" column-key="id" :label="t('cases.caseId')" :width="columnWidth('id', 180)" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="title" column-key="title" :label="t('cases.caseTitle')" :width="columnWidth('title')" min-width="180" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="entity" column-key="entity" :label="t('common.entity')" :width="columnWidth('entity', 130)" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="severity" column-key="severity" :label="t('common.severity')" :width="columnWidth('severity', 90)" sortable="custom"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
        <el-table-column prop="status" column-key="status" :label="t('common.status')" :width="columnWidth('status', 120)" sortable="custom"><template #default="{ row }"><el-tag :type="row.status === 'OPEN' ? 'danger' : row.status === 'RESOLVED' || row.status === 'CLOSED' ? 'success' : 'warning'" size="small">{{ t('statuses.' + row.status) || row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="alarmCount" column-key="alarmCount" :label="t('cases.associatedAlarms')" :width="columnWidth('alarmCount', 90)" sortable="custom"><template #default="{ row }">{{ row.alarmIds.length }}</template></el-table-column>
        <el-table-column :label="t('common.actions')" width="100" :resizable="false"><template #default="{ row }"><el-button link type="primary" size="small" @click="openCaseRow(row)">{{ t('cases.detailsTimeline') }}</el-button></template></el-table-column>
      </el-table>
    </DataTableCard>

    <el-dialog v-model="createDialogVisible" :title="t('cases.createCase')" width="560px">
      <el-form label-width="90px">
        <el-form-item :label="t('cases.caseTitle')" required><el-input v-model="caseForm.title" :placeholder="t('cases.titlePlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.entity')"><el-input v-model="caseForm.entity" :placeholder="t('cases.entityPlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.severity')"><el-select v-model="caseForm.severity" style="width: 180px"><el-option v-for="level in ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']" :key="level" :label="t('severities.' + level) || level" :value="level" /></el-select></el-form-item>
        <el-form-item :label="t('cases.assignee')"><el-select v-model="caseForm.assignee" filterable default-first-option clearable :placeholder="t('cases.assigneePlaceholder')" style="width:100%"><el-option v-for="assignee in assigneeOptions" :key="assignee" :label="assignee" :value="assignee" /></el-select></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="saveCase">{{ t('cases.createCase') }}</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawerVisible" :title="`${t('cases.title')} · ${detail?.title ?? ''}`" size="520px">
      <template v-if="detail">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item :label="t('cases.caseId')">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.entity')">{{ detail.entity }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.severity')"><SevBadge :value="detail.severity" /></el-descriptions-item>
          <el-descriptions-item :label="t('common.status')">{{ t('statuses.' + detail.status) || detail.status }}</el-descriptions-item>
          <el-descriptions-item :label="t('cases.linkedRules')" :span="2">{{ detail.ruleIds.join(', ') || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('cases.associatedAlarms')" :span="2">
            <div v-if="detail.alarmIds.length" class="case-object-list"><button v-for="alarmId in detail.alarmIds" :key="alarmId" type="button" class="case-object-link mono" @click="openAlarm(alarmId)">{{ alarmId }}</button></div>
            <span v-else>—</span>
          </el-descriptions-item>
        </el-descriptions>
        <div class="case-status-row"><el-select v-model="newStatus"><el-option v-for="status in ['OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED']" :key="status" :label="t('statuses.' + status) || status" :value="status" /></el-select><el-button type="primary" @click="updateStatus">{{ t('cases.updateStatus') }}</el-button></div>
        <el-divider content-position="left">{{ t('cases.timeline') }}</el-divider>
        <el-timeline><el-timeline-item v-for="(event, index) in timeline" :key="index" :timestamp="event.ts" placement="top"><div>{{ event.message }}</div><div class="case-event-meta">{{ event.type }} · {{ event.source }}</div></el-timeline-item></el-timeline>
      </template>
    </el-drawer>
  </div>
</template>
