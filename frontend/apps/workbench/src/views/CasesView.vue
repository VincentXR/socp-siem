<script setup lang="ts">
import { useWriteAccess } from '../composables/useWriteAccess'
const canWrite = useWriteAccess()
import { useFormDialog } from '../composables/useFormDialog'
import { useMutation } from '../composables/useMutation'
import ActionFeedback from '../components/ActionFeedback.vue'
import FormField from '../components/FormField.vue'
import FormGrid from '../components/FormGrid.vue'
import FormSection from '../components/FormSection.vue'
const mutation = useMutation()
const { busy: actionBusy, error: actionError } = mutation
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
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { useDebouncedWatch } from '../composables/useDebouncedWatch'
import { useLatestRequest } from '../composables/useLatestRequest'
import { caseApi, type CaseInfo, type TimelineEvent } from '../api/domains'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'
import { WORKBENCH_STATE } from '../app/workbenchState'

const { t } = useI18n()
const workbenchState = inject(WORKBENCH_STATE, null)
const route = useRoute()
const router = useRouter()

const stats = ref<{ total?: number; open?: number; resolved?: number }>({})
const detail = ref<CaseInfo | null>(null)
const timeline = ref<TimelineEvent[]>([])
const timelineError = ref('')
const drawerVisible = ref(false)
const createDialogVisible = ref(false)
const caseForm = ref({ title: '', entity: '', severity: 'HIGH', assignee: '' })
const newStatus = ref('')
const detailAssignee = ref('')
const statusFilter = ref('')
const loadError = ref('')
const cases = ref<CaseInfo[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const keyword = ref('')
const loading = ref(false)
const latestRequest = useLatestRequest()
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('cases')
const assigneeOptions = computed(() => Array.from(new Set([
  ...(workbenchState?.operatorOptions.value ?? []),
  workbenchState?.currentUser.value ?? '',
  ...cases.value.map(item => item.assignee ?? ''),
].filter(Boolean))))

async function loadCases() {
  const request = latestRequest.start()
  loading.value = true
  loadError.value = ''
  try {
    const [caseResult, statResult] = await Promise.allSettled([
      caseApi.list(page.value, size.value, keyword.value, statusFilter.value || undefined, { signal: request.signal }),
      caseApi.stats({ signal: request.signal }),
    ])
    if (!request.isCurrent()) return
    if (caseResult.status === 'fulfilled') {
      cases.value = caseResult.value.items
      total.value = caseResult.value.total
    } else {
      loadError.value = caseResult.reason instanceof Error ? caseResult.reason.message : String(caseResult.reason)
    }
    if (!request.isCurrent()) return
    if (statResult.status === 'fulfilled') stats.value = statResult.value
    openCaseFromQuery()
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

async function openCase(item: CaseInfo) {
  detail.value = item
  newStatus.value = item.status
  detailAssignee.value = item.assignee ?? ''
  drawerVisible.value = true
  detailGuard.markSaved()
  timeline.value = []; timelineError.value = ''
  try { timeline.value = (await caseApi.timeline(item.id)).items } catch (failure) { timelineError.value = String(failure) }
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

function openRule(id: string): void {
  if (!id.trim()) return
  void router.push({ name: 'rule-edit', params: { ruleId: id } })
}

async function updateStatus() {
  return mutation.run(async () => {
  if (!detail.value || !newStatus.value) return
  try {
    const result = await caseApi.updateStatus(detail.value.id, newStatus.value, detailAssignee.value.trim() || undefined)
    detail.value = result.case
    detailAssignee.value = result.case.assignee ?? detailAssignee.value
    detailGuard.markSaved()
    await loadCases()
  } catch (error) {
    throw error
  }
  })
}

function openCreateCase() {
  caseForm.value = { title: '', entity: '', severity: 'HIGH', assignee: '' }
  createDialogVisible.value = true
}

async function saveCase() {
  return mutation.run(async () => {
  if (!caseForm.value.title.trim()) {
    ElMessage.warning(t('cases.pleaseEnterTitle'))
    return
  }
  try {
    const created = await caseApi.create({
      title: caseForm.value.title.trim(), entity: caseForm.value.entity.trim(),
      severity: caseForm.value.severity, assignee: caseForm.value.assignee.trim() || undefined,
    })
    createDialogVisible.value = false
    ElMessage.success(t('cases.createdSuccessfully'))
    await loadCases()
    await openCase(created.case)
  } catch (error) {
    throw error
  }
  })
}

const createDialogVisibleGuard = useFormDialog(createDialogVisible, () => caseForm.value, () => actionBusy.value)
const detailGuard = useFormDialog(drawerVisible, () => ({ status: newStatus.value, assignee: detailAssignee.value }), () => actionBusy.value)
onMounted(loadCases)
watch([page, size], () => { void loadCases() })
useDebouncedWatch([keyword, statusFilter], () => {
  if (page.value !== 1) page.value = 1
  else void loadCases()
})
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
    <ActionFeedback :error="actionError" />
    <PageHeader :eyebrow="t('menuGroup.alarmsAndEvents')" :title="t('cases.title')" :description="t('cases.description')">
      <template #actions>
        <el-button v-if="canWrite" type="primary" size="small" @click="openCreateCase">{{ t('cases.createCase') }}</el-button>
        <el-button size="small" @click="caseApi.export()">{{ t('cases.exportJson') }}</el-button>
      </template>
    </PageHeader>

    <div class="page-metrics">
      <MetricCard :label="t('cases.totalCases')" tone="info">{{ stats.total ?? 0 }}</MetricCard>
      <MetricCard :label="t('cases.activeCases')" tone="warning">{{ stats.open ?? 0 }}</MetricCard>
      <MetricCard :label="t('cases.resolvedCases')" tone="success">{{ stats.resolved ?? 0 }}</MetricCard>
    </div>

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="total" :loading="loading" :error="loadError" :retry="loadCases" :empty-title="t('cases.emptyCases')" :empty-description="t('cases.description')">
      <template #toolbar>
        <FilterToolbar :count="total">
        <el-input v-model="keyword" :placeholder="t('cases.searchPlaceholder')" clearable @input="page = 1" />
        <el-select v-model="statusFilter" :placeholder="t('cases.allStatuses')" clearable @change="page = 1">
          <el-option v-for="status in ['OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED']" :key="status" :label="tOr(t, 'statuses.' + status, status)" :value="status" />
        </el-select>
        </FilterToolbar>
      </template>
      <el-table :data="cases" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @row-click="openCaseRow">
        <el-table-column prop="id" column-key="id" :label="t('cases.caseId')" :width="columnWidth('id', 180)" show-overflow-tooltip />
        <el-table-column prop="title" column-key="title" :label="t('cases.caseTitle')" :width="columnWidth('title')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="entity" column-key="entity" :label="t('common.entity')" :width="columnWidth('entity', 130)" show-overflow-tooltip />
        <el-table-column prop="severity" column-key="severity" :label="t('common.severity')" :width="columnWidth('severity', 90)"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
        <el-table-column prop="status" column-key="status" :label="t('common.status')" :width="columnWidth('status', 120)"><template #default="{ row }"><el-tag :type="row.status === 'OPEN' ? 'danger' : row.status === 'RESOLVED' || row.status === 'CLOSED' ? 'success' : 'warning'" size="small">{{ tOr(t, 'statuses.' + row.status, row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="alarmCount" column-key="alarmCount" :label="t('cases.associatedAlarms')" :width="columnWidth('alarmCount', 90)"><template #default="{ row }">{{ row.alarmIds.length }}</template></el-table-column>
        <el-table-column :label="t('common.actions')" width="100" :resizable="false"><template #default="{ row }"><el-button link type="primary" size="small" @click="openCaseRow(row)">{{ t('cases.detailsTimeline') }}</el-button></template></el-table-column>
      </el-table>
    </DataTableCard>

    <el-dialog v-model="createDialogVisible" :before-close="createDialogVisibleGuard.beforeClose" :title="t('cases.createCase')" width="520px"><ActionFeedback :error="actionError" />
      <el-form :disabled="actionBusy" label-position="top">
        <FormSection :title="t('cases.identity')" :hint="t('cases.identityHint')">
          <FormGrid :columns="2">
            <FormField :label="t('cases.caseTitle')" required full>
              <el-input v-model="caseForm.title" :placeholder="t('cases.titlePlaceholder')" />
            </FormField>
            <FormField :label="t('common.entity')" :hint="t('cases.entityHint')">
              <el-input v-model="caseForm.entity" :placeholder="t('cases.entityPlaceholder')" />
            </FormField>
            <FormField :label="t('common.severity')" :hint="t('cases.severityHint')">
              <el-select v-model="caseForm.severity"><el-option v-for="level in ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']" :key="level" :label="tOr(t, 'severities.' + level, level)" :value="level" /></el-select>
            </FormField>
            <FormField :label="t('cases.assignee')" :hint="t('cases.assigneeHint')">
              <el-select v-model="caseForm.assignee" filterable default-first-option allow-create clearable :placeholder="t('cases.assigneePlaceholder')"><el-option v-for="assignee in assigneeOptions" :key="assignee" :label="assignee" :value="assignee" /></el-select>
            </FormField>
          </FormGrid>
        </FormSection>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisibleGuard.cancel">{{ t('common.cancel') }}</el-button>
        <el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="saveCase">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawerVisible" :before-close="detailGuard.beforeClose" :title="`${t('cases.title')} · ${detail?.title ?? ''}`" size="min(520px, 96vw)">
      <template v-if="detail">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item :label="t('cases.caseId')">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.caseNo" :label="t('cases.caseNo')">{{ detail.caseNo }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.entity')">{{ detail.entity }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.severity')"><SevBadge :value="detail.severity" /></el-descriptions-item>
          <el-descriptions-item :label="t('cases.status')">{{ tOr(t, 'statuses.' + detail.status, detail.status) }}</el-descriptions-item>
          <el-descriptions-item :label="t('cases.assignee')">
            <el-select v-if="canWrite" v-model="detailAssignee" filterable default-first-option allow-create clearable :placeholder="t('cases.assigneePlaceholder')" style="width:100%">
              <el-option v-for="assignee in assigneeOptions" :key="assignee" :label="assignee" :value="assignee" />
            </el-select>
            <span v-else>{{ detail.assignee || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('cases.linkedRules')" :span="2">
            <div v-if="detail.ruleIds.length" class="case-object-list"><button v-for="ruleId in detail.ruleIds" :key="ruleId" type="button" class="case-object-link mono" @click="openRule(ruleId)">{{ ruleId }}</button></div>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('cases.associatedAlarms')" :span="2">
            <div v-if="detail.alarmIds.length" class="case-object-list"><button v-for="alarmId in detail.alarmIds" :key="alarmId" type="button" class="case-object-link mono" @click="openAlarm(alarmId)">{{ alarmId }}</button></div>
            <span v-else>—</span>
          </el-descriptions-item>
        </el-descriptions>
        <template v-if="canWrite">
          <p class="dialog-hint">{{ t('forms.changeStatus') }}</p>
          <div class="case-status-row">
            <el-select v-model="newStatus"><el-option v-for="status in ['OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED']" :key="status" :label="tOr(t, 'statuses.' + status, status)" :value="status" /></el-select>
            <el-button type="primary" :loading="actionBusy" @click="updateStatus">{{ t('cases.updateStatus') }}</el-button>
          </div>
          <p class="drawer-readonly-hint">{{ t('forms.changeStatus') }} + {{ t('forms.assign') }}</p>
        </template>
        <el-divider content-position="left">{{ t('cases.timeline') }}</el-divider>
        <ActionFeedback :error="timelineError" /><el-timeline><el-timeline-item v-for="(event, index) in timeline" :key="index" :timestamp="event.ts" placement="top"><div>{{ event.message }}</div><div class="case-event-meta">{{ event.type }} · {{ event.source }}</div></el-timeline-item></el-timeline>
      </template>
    </el-drawer>
  </div>
</template>
