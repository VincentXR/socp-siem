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
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import DataTableCard from '../components/DataTableCard.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import PagerBar from '../components/PagerBar.vue'
import SevBadge from '../components/SevBadge.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { useDebouncedWatch } from '../composables/useDebouncedWatch'
import { useLatestRequest } from '../composables/useLatestRequest'
import { useListQuery } from '../composables/useListQuery'
import { caseApi, type CaseInfo, type TimelineEvent } from '../api/domains'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'
import { WORKBENCH_STATE } from '../app/workbenchState'

const { t, d } = useI18n()
const workbenchState = inject(WORKBENCH_STATE, null)
const route = useRoute()
const router = useRouter()

const stats = ref<{ total?: number; open?: number; resolved?: number }>({})
const statsError = ref('')
const detail = ref<CaseInfo | null>(null)
const detailError = ref('')
const detailLoading = ref(false)
const latestDetail = useLatestRequest()
const latestTimeline = useLatestRequest()
const timeline = ref<TimelineEvent[]>([])
const timelineError = ref('')
const timelineLoading = ref(false)
const timelinePage = ref(1)
const timelineSize = ref(20)
const timelineTotal = ref(0)
const drawerVisible = ref(false)
const createDialogVisible = ref(false)
const caseForm = ref({ title: '', entity: '', severity: 'HIGH', assignee: '' })
const titleError = ref('')
const newStatus = ref('')
const detailAssignee = ref('')
const exportMutation = useMutation()
const selectedId = computed(() => typeof route.query.caseId === 'string' ? route.query.caseId : '')
const CASE_STATUSES = ['OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED']
const loadError = ref('')
const cases = ref<CaseInfo[]>([])
const size = ref(20)
const total = ref(0)
const listQuery = useListQuery({ routeName: 'case', total, size, fields: [{ key: 'status', validate: status => CASE_STATUSES.includes(status) ? status : '' }] })
const page = listQuery.page
const keyword = listQuery.keyword
const statusFilter = listQuery.filters.status
const loading = ref(false)
const latestRequest = useLatestRequest()
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('cases')
const assigneeOptions = computed(() => Array.from(new Set([
  ...(workbenchState?.operatorOptions.value ?? []),
  workbenchState?.currentUser.value ?? '',
  ...cases.value.map(item => item.assignee ?? ''),
  detail.value?.assignee ?? '',
].filter(Boolean))))

async function loadCases() {
  const request = latestRequest.start()
  loading.value = true
  loadError.value = ''
  try {
    const [caseResult, statResult] = await Promise.allSettled([
      caseApi.list(page.value, size.value, listQuery.keywordParam.value, statusFilter.value || undefined, { signal: request.signal }),
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
    statsError.value = statResult.status === 'rejected' ? String(statResult.reason) : ''
    stats.value = statResult.status === 'fulfilled' ? statResult.value : {}
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

/** The route owns selection, so links and browser history do not depend on a list page. */
async function loadDetail() {
  const request = latestDetail.start()
  latestTimeline.cancel()
  const id = selectedId.value
  detail.value = null
  newStatus.value = ''; detailAssignee.value = ''
  detailGuard.markSaved()
  timeline.value = []; timelineError.value = ''; timelineLoading.value = false
  timelinePage.value = 1; timelineTotal.value = 0
  detailError.value = ''; actionError.value = ''
  createDialogVisible.value = false
  drawerVisible.value = Boolean(id)
  detailLoading.value = Boolean(id)
  if (!id) return
  try {
    const result = await caseApi.get(id, { signal: request.signal })
    if (!request.isCurrent()) return
    if (!result.found || !result.case.id) throw new Error(t('cases.notFound'))
    applyDetail(result.case as CaseInfo)
    void loadTimeline()
  } catch (failure) {
    if (request.isCurrent()) detailError.value = String(failure)
  } finally {
    if (request.isCurrent()) detailLoading.value = false
  }
}

function applyDetail(item: CaseInfo) {
  detail.value = item
  newStatus.value = item.status
  detailAssignee.value = item.assignee ?? ''
  detailGuard.markSaved()
}

async function loadTimeline() {
  if (!detail.value) return
  const request = latestTimeline.start()
  const id = detail.value.id
  timelineLoading.value = true; timelineError.value = ''; timeline.value = []
  try {
    const result = await caseApi.timeline(id, timelinePage.value, timelineSize.value, { signal: request.signal })
    if (!request.isCurrent()) return
    timeline.value = result.items; timelineTotal.value = result.total
  } catch (failure) {
    if (request.isCurrent()) timelineError.value = String(failure)
  } finally {
    if (request.isCurrent()) timelineLoading.value = false
  }
}

function openCaseRow(row: unknown) {
  if (actionBusy.value) return
  void router.push({ query: { ...route.query, caseId: (row as CaseInfo).id } })
}

function closeDetail() {
  const query = { ...route.query }
  delete query.caseId
  void router.push({ query })
}

function openAlarm(id: string): void {
  void router.push({ name: 'alarms', query: { alarmId: id } })
}

function openRule(id: string): void {
  if (!id.trim()) return
  void router.push({ name: 'rule-edit', params: { ruleId: id } })
}

async function updateStatus() {
  if (!canWrite.value || actionBusy.value || !detail.value || !newStatus.value) return
  const id = detail.value.id
  const status = newStatus.value
  const assignee = detailAssignee.value.trim() || undefined
  const saved = await mutation.run(async () => {
    const result = await caseApi.updateStatus(id, status, assignee)
    applyDetail(result.case)
    cases.value = cases.value.map(item => item.id === id ? result.case : item)
    ElMessage.success(t('cases.updatedSuccessfully'))
  })
  if (saved) { void loadCases(); void loadTimeline() }
}

function openCreateCase() {
  if (!canWrite.value || actionBusy.value) return
  actionError.value = ''
  titleError.value = ''
  caseForm.value = { title: '', entity: '', severity: 'HIGH', assignee: '' }
  createDialogVisible.value = true
}

async function saveCase() {
  if (!canWrite.value || actionBusy.value) return
  titleError.value = ''
  if (!caseForm.value.title.trim()) {
    titleError.value = t('cases.pleaseEnterTitle')
    return
  }
  let createdId = ''
  const saved = await mutation.run(async () => {
    const created = await caseApi.create({
      title: caseForm.value.title.trim(), entity: caseForm.value.entity.trim(),
      severity: caseForm.value.severity, assignee: caseForm.value.assignee.trim() || undefined,
    })
    createDialogVisible.value = false
    createdId = created.case.id
    ElMessage.success(t('cases.createdSuccessfully'))
  })
  if (saved) {
    await router.push({ query: { ...route.query, caseId: createdId } })
    void loadCases()
  }
}

const createDialogVisibleGuard = useFormDialog(createDialogVisible, () => caseForm.value, () => actionBusy.value)
const detailGuard = useFormDialog(drawerVisible, () => ({ status: newStatus.value, assignee: detailAssignee.value }), () => actionBusy.value)
onBeforeRouteUpdate(async (to, from) => to.query.caseId === from.query.caseId
  || await createDialogVisibleGuard.canLeave() && await detailGuard.canLeave())
onMounted(loadCases)
watch([page, size], () => { listQuery.sync(); void loadCases() })
useDebouncedWatch([keyword, statusFilter], () => {
  const routeKeyword = typeof route.query.q === 'string' ? route.query.q.trim() : ''
  const routeStatus = typeof route.query.status === 'string' && CASE_STATUSES.includes(route.query.status) ? route.query.status : ''
  // A browser-history restore is already loaded at its requested page. Do not
  // let its delayed filter watcher turn it into a fresh page-one search.
  if (keyword.value.trim() === routeKeyword && statusFilter.value === routeStatus) return
  if (page.value !== 1) page.value = 1
  else { listQuery.sync(); void loadCases() }
})
watch(selectedId, () => { void loadDetail() }, { immediate: true })
watch([timelinePage, timelineSize], () => { void loadTimeline() })
watch([() => route.query.page, () => route.query.q, () => route.query.status], () => {
  const before = { page: page.value, keyword: keyword.value.trim(), status: statusFilter.value }
  listQuery.applyRouteQuery()
  if (before.page === page.value && (before.keyword !== keyword.value.trim() || before.status !== statusFilter.value)) void loadCases()
})
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback :error="exportMutation.error.value" />
    <PageHeader :eyebrow="t('menuGroup.alarmsAndEvents')" :title="t('cases.title')" :description="t('cases.description')">
      <template #actions>
        <el-button v-if="canWrite" type="primary" size="small" :disabled="actionBusy" @click="openCreateCase">{{ t('cases.createCase') }}</el-button>
        <el-button v-if="canWrite" size="small" :loading="exportMutation.busy.value" @click="exportMutation.run(caseApi.export)">{{ t('cases.exportJson') }}</el-button>
      </template>
    </PageHeader>

    <div class="page-metrics">
      <MetricCard :label="t('cases.totalCases')" tone="info">{{ stats.total ?? '—' }}</MetricCard>
      <MetricCard :label="t('cases.activeCases')" tone="warning">{{ stats.open ?? '—' }}</MetricCard>
      <MetricCard :label="t('cases.resolvedCases')" tone="success">{{ stats.resolved ?? '—' }}</MetricCard>
    </div>
    <ActionFeedback :error="statsError" />

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="total" :loading="loading" :error="loadError" :retry="loadCases" :empty-title="t('cases.emptyCases')" :empty-description="t('cases.description')">
      <template #toolbar>
        <FilterToolbar :count="total">
        <el-input v-model="keyword" :disabled="actionBusy" :placeholder="t('cases.searchPlaceholder')" clearable @input="page = 1" />
        <el-select v-model="statusFilter" :disabled="actionBusy" :placeholder="t('cases.allStatuses')" clearable @change="page = 1">
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
        <el-table-column :label="t('common.actions')" width="100" :resizable="false"><template #default="{ row }"><el-button link type="primary" size="small" :disabled="actionBusy" @click.stop="openCaseRow(row)">{{ t('cases.detailsTimeline') }}</el-button></template></el-table-column>
      </el-table>
    </DataTableCard>

    <el-dialog v-model="createDialogVisible" :before-close="createDialogVisibleGuard.beforeClose" :title="t('cases.createCase')" width="520px"><ActionFeedback :error="actionError" />
      <el-form :disabled="actionBusy" label-position="top">
        <FormSection :title="t('cases.identity')" :hint="t('cases.identityHint')">
          <FormGrid :columns="2">
            <FormField :label="t('cases.caseTitle')" required full :error="titleError">
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
        <el-button :disabled="actionBusy" @click="createDialogVisibleGuard.cancel">{{ t('common.cancel') }}</el-button>
        <el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="saveCase">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <el-drawer :model-value="drawerVisible" :before-close="closeDetail" :title="`${t('cases.title')} · ${detail?.title ?? selectedId}`" size="min(520px, 96vw)">
      <p v-if="detailLoading" role="status">{{ t('common.loading') }}</p>
      <ActionFeedback :error="detailError" />
      <el-button v-if="detailError" @click="loadDetail">{{ t('common.retry') }}</el-button>
      <template v-if="detail">
        <ActionFeedback :error="actionError" />
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item :label="t('cases.caseId')">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.caseNo" :label="t('cases.caseNo')">{{ detail.caseNo }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.entity')">{{ detail.entity }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.severity')"><SevBadge :value="detail.severity" /></el-descriptions-item>
          <el-descriptions-item :label="t('cases.status')">{{ tOr(t, 'statuses.' + detail.status, detail.status) }}</el-descriptions-item>
          <el-descriptions-item :label="t('cases.assignee')">
            <el-select v-if="canWrite" v-model="detailAssignee" :disabled="actionBusy" filterable default-first-option allow-create clearable :placeholder="t('cases.assigneePlaceholder')" style="width:100%">
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
            <el-select v-model="newStatus" :disabled="actionBusy" :aria-label="t('cases.status')"><el-option v-for="status in CASE_STATUSES" :key="status" :label="tOr(t, 'statuses.' + status, status)" :value="status" /></el-select>
            <el-button type="primary" :loading="actionBusy" @click="updateStatus">{{ t('cases.updateStatus') }}</el-button>
          </div>
          <p class="drawer-readonly-hint">{{ t('forms.changeStatus') }} + {{ t('forms.assign') }}</p>
        </template>
        <el-divider content-position="left">{{ t('cases.timeline') }}</el-divider>
        <section class="case-timeline" :aria-label="t('cases.timeline')" :aria-busy="timelineLoading">
          <p v-if="timelineLoading" role="status">{{ t('common.loading') }}</p>
          <ActionFeedback :error="timelineError" />
          <el-button v-if="timelineError" @click="loadTimeline">{{ t('common.retry') }}</el-button>
          <p v-else-if="!timelineLoading && !timeline.length" class="dialog-hint">{{ t('cases.emptyTimeline') }}</p>
          <el-timeline v-else><el-timeline-item v-for="(event, index) in timeline" :key="index" :timestamp="d(event.ts)" placement="top"><div>{{ event.message }}</div><div class="case-event-meta">{{ event.type }} · {{ event.source }}</div></el-timeline-item></el-timeline>
          <PagerBar v-if="timelineTotal" v-model:current-page="timelinePage" v-model:page-size="timelineSize" :total="timelineTotal" />
        </section>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.case-timeline :deep(.el-pagination) {
  min-width: 0;
  flex-wrap: wrap;
  justify-content: flex-start;
  row-gap: 8px;
}
</style>
