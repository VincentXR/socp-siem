<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/loading/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { vLoading } from 'element-plus/es/components/loading/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { localDateTime, utcInstant } from '../lib/time-range'
import { ALARM_DISP_STATUSES, ALARM_FILTER_STATUSES } from '../app/alarm-statuses'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import InvestigationReadiness from '../components/InvestigationReadiness.vue'
import AlarmDispositionDrawer from '../components/AlarmDispositionDrawer.vue'
import EmptyState from '../components/EmptyState.vue'
import PageHeader from '../components/PageHeader.vue'
import PagerBar from '../components/PagerBar.vue'
import SevBadge from '../components/SevBadge.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { relTime } from '../lib/ui'
import { listRuleOptions, SEVERITIES, type Alarm, type RuleOption } from '../api'
import { useLatestRequest } from '../composables/useLatestRequest'
import { useConfirm } from '../composables/useConfirm'
import { batchUpdateAlarmDisposition, getAlarm } from '../api/alarms'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'

const props = defineProps<{
  filteredAlarms: Alarm[]
  alarmPageData: { total: number }
  alarmPageSize: number
  loading: boolean
  error: string
  onSearch: () => void
  loadPage: () => void | Promise<void>
  onSortChange: (field: 'occurredAt' | 'severity' | 'ruleName' | 'entity' | 'status' | 'riskScore', order: 'ascending' | 'descending') => void
  exportCsv: () => Promise<void>
  exportJson: () => Promise<void>
  goCase: (caseId?: string) => void
  goSearch: (query?: string) => void
  goAi?: (alarmId: string, caseId?: string) => void
  goSoar?: (alarmId: string, caseId?: string) => void
  assigneeOptions?: string[]
  canWrite?: boolean
  canAdmin?: boolean
  currentUser?: string
}>()

const { t } = useI18n()
const { confirmDanger } = useConfirm()
let disposed = false
onUnmounted(() => { disposed = true })

const keyword = defineModel<string>('keyword', { default: '' })
const severity = defineModel<string>('severity', { default: '' })
const status = defineModel<string>('status', { default: '' })
const owner = defineModel<string>('owner', { default: '' })
const rule = defineModel<string>('rule', { default: '' })
const assignee = defineModel<string>('assignee', { default: '' })
const from = defineModel<string>('from', { default: '' })
const to = defineModel<string>('to', { default: '' })
const fromInput = computed({ get: () => localDateTime(from.value), set: value => { from.value = utcInstant(value) } })
const toInput = computed({ get: () => localDateTime(to.value), set: value => { to.value = utcInstant(value) } })
function searchOwner() { assignee.value = ''; props.onSearch() }
function searchAssignee() { owner.value = ''; props.onSearch() }
function myQueue() {
  owner.value = ''; assignee.value = props.currentUser || ''; status.value = 'ACTIVE'; props.onSearch()
}
const pageNum = defineModel<number>('pageNum', { default: 1 })
const drawerVisible = ref(false)
const currentAlarm = ref<Alarm | null>(null)
const selectedAlarms = ref<Alarm[]>([])
const batchStatus = ref('')
const batchReason = ref('')
const batchClassification = ref('UNDETERMINED')
const batchOperation = ref<'assign' | 'status'>('assign')
const pageSize = defineModel<number>('pageSize', { default: 20 })
const batchAssignee = ref('')
const batchBusy = ref(false)
const batchConfirming = ref(false)
const batchError = ref('')
const exporting = ref('')
const exportError = ref('')
const ruleOptions = ref<RuleOption[]>([])
const ruleOptionRequests = useLatestRequest()
const ruleOptionsTotal = ref(0)
const ruleCatalogLoading = ref(false)
const ruleCatalogError = ref('')
const route = useRoute()
const router = useRouter()
const deepLinkRequests = useLatestRequest()
const deepLinkLoading = ref(false)
const deepLinkError = ref('')
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('alarms')
const DISP_STATUSES = ALARM_DISP_STATUSES
const FILTER_STATUSES = ALARM_FILTER_STATUSES

async function loadRuleOptions(keyword = ''): Promise<void> {
  const request = ruleOptionRequests.start()
  ruleCatalogLoading.value = true
  ruleCatalogError.value = ''
  try {
    const result = await listRuleOptions(keyword, { signal: request.signal })
    if (!request.isCurrent()) return
    ruleOptions.value = result.items
    ruleOptionsTotal.value = result.total
  } catch (error) {
    if (!request.isCurrent()) return
    ruleOptions.value = []
    ruleOptionsTotal.value = 0
    ruleCatalogError.value = error instanceof Error ? error.message : String(error)
  } finally {
    if (request.isCurrent()) ruleCatalogLoading.value = false
  }
}

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
  if (!props.canWrite || batchBusy.value || batchConfirming.value || disposed || !selectedAlarms.value.length) return
  if (batchOperation.value === 'assign' ? !batchAssignee.value.trim() : !DISP_STATUSES.includes(batchStatus.value)) return
  const ids = selectedAlarms.value.map(alarm => alarm.id)
  const operation = batchOperation.value
  const payload: { assignee?: string; status?: string; reason?: string; classification?: string } = { ...(operation === 'assign' ? { assignee: batchAssignee.value.trim() } : { status: batchStatus.value }), reason: batchReason.value.trim() || undefined, ...(['RESOLVED', 'CLOSED'].includes(batchStatus.value) ? { classification: batchClassification.value } : {}) }
  if (operation === 'status' && ['RESOLVED', 'CLOSED'].includes(batchStatus.value) && !payload.reason) { batchError.value = t('analystJourney.batchClosureRequired'); return }
  batchConfirming.value = true
  let confirmed: boolean
  try {
    confirmed = await confirmDanger(operation === 'assign'
      ? t('alarms.batchAssignConfirm', { count: ids.length, assignee: payload.assignee ?? '' })
      : t('alarms.batchStatusConfirm', { count: ids.length, status: tOr(t, 'statuses.' + payload.status, payload.status ?? '') }))
  }
  finally { batchConfirming.value = false }
  if (!confirmed || !props.canWrite || batchBusy.value || disposed) return
  batchBusy.value = true
  batchError.value = ''
  try {
    await batchUpdateAlarmDisposition(ids, payload)
    ElMessage.success(t('common.success'))
    if (selectedAlarms.value.map(alarm => alarm.id).join('\0') === ids.join('\0')) selectedAlarms.value = []
    if (operation === 'assign' && batchAssignee.value.trim() === payload.assignee) batchAssignee.value = ''
    await props.loadPage()
  } catch (error) {
    batchError.value = error instanceof Error ? error.message : String(error)
  } finally {
    batchBusy.value = false
  }
}

async function syncAlarmFromRoute(): Promise<void> {
  const id = typeof route.query.alarmId === 'string' ? route.query.alarmId : ''
  if (!id) {
    deepLinkRequests.cancel()
    deepLinkLoading.value = false
    deepLinkError.value = ''
    currentAlarm.value = null
    drawerVisible.value = false
    return
  }
  const alarm = props.filteredAlarms.find(item => item.id === id)
  if (alarm) {
    deepLinkRequests.cancel()
    deepLinkLoading.value = false
    deepLinkError.value = ''
    currentAlarm.value = alarm
    drawerVisible.value = true
    return
  }
  if (currentAlarm.value?.id === id && drawerVisible.value) return
  const request = deepLinkRequests.start()
  deepLinkLoading.value = true
  deepLinkError.value = ''
  currentAlarm.value = null
  try {
    const loaded = await getAlarm(id, { signal: request.signal })
    if (!request.isCurrent()) return
    currentAlarm.value = loaded
    drawerVisible.value = true
  } catch (error) {
    if (request.isCurrent()) deepLinkError.value = error instanceof Error ? error.message : String(error)
  } finally {
    if (request.isCurrent()) deepLinkLoading.value = false
  }
}

watch(() => [route.query.alarmId, props.filteredAlarms] as const, () => { void syncAlarmFromRoute() }, { immediate: true, deep: true })
watch(drawerVisible, visible => {
  if (!visible && route.name === 'alarms' && route.query.alarmId) {
    const query = { ...route.query }
    delete query.alarmId
    void router.replace({ query })
  }
})

// PagerBar 只暴露 v-model，翻页/换页容量都在这一个 watcher 里收敛成一次加载（同 tick 的双变更不会重复请求）。
watch([pageNum, pageSize], () => { props.loadPage() })

onMounted(() => { void loadRuleOptions() })

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
  <div class="page-pad view-enter view--alarms">
    <PageHeader :eyebrow="t('menuGroup.alarmsAndEvents')" :title="t('alarms.title')" :description="t('alarms.description')" />
    <div class="alarm-toolbar">
      <div class="alarm-filter-controls">
        <el-input v-model="keyword" class="alarm-keyword-input" :placeholder="t('alarms.keywordPlaceholder')" clearable @keyup.enter="props.onSearch" @clear="props.onSearch" />
        <el-select v-model="rule" class="alarm-rule-input" filterable remote :remote-method="loadRuleOptions" clearable :loading="ruleCatalogLoading" :placeholder="t('alarms.ruleFilter')" @change="props.onSearch">
          <el-option v-if="rule && !ruleOptions.some(item => item.id === rule)" :label="rule" :value="rule" />
          <el-option v-for="item in ruleOptions" :key="item.id" :label="item.name" :value="item.id">
            <div class="alarm-rule-option"><b>{{ item.name }}</b><small>{{ item.id }}</small></div>
          </el-option>
        </el-select>
        <el-select v-model="severity" :placeholder="t('alarms.severityFilter')" clearable style="width:140px" @change="props.onSearch">
          <el-option v-for="item in SEVERITIES" :key="item" :label="tOr(t, 'severities.' + item, item)" :value="item" />
        </el-select>
        <el-select v-model="status" :aria-label="t('alarms.statusFilter')" :placeholder="t('alarms.statusFilter')" clearable style="width:150px" @change="props.onSearch">
          <el-option v-for="item in FILTER_STATUSES" :key="item" :label="tOr(t, 'statuses.' + item, item)" :value="item" />
        </el-select>
        <el-select v-model="assignee" clearable filterable :placeholder="t('cases.assignee')" style="width:160px" @change="searchAssignee"><el-option v-for="owner in props.assigneeOptions" :key="owner" :label="owner" :value="owner" /></el-select>
        <el-button v-if="props.currentUser" size="small" @click="myQueue">{{ t('workflow.myQueue') }}</el-button>
        <el-button size="small" @click="props.onSearch">{{ t('common.search') }}</el-button>
        <small v-if="ruleCatalogError" class="alarm-catalog-hint" :title="ruleCatalogError">{{ t('alarms.ruleCatalogUnavailable') }}</small>
        <small v-else-if="ruleOptionsTotal > ruleOptions.length" class="alarm-catalog-hint">{{ t('detect.refineRuleSearch', { total: ruleOptionsTotal }) }}</small>
      </div>
      <div class="workflow-time-range">
        <label>{{ t('workflow.timeFrom') }}<input v-model="fromInput" type="datetime-local" step="0.001" :aria-label="t('workflow.timeFrom')" /></label>
        <label>{{ t('workflow.timeTo') }}<input v-model="toInput" type="datetime-local" step="0.001" :aria-label="t('workflow.timeTo')" /></label>
      </div>
      <div class="alarm-toolbar-actions">
        <span class="toolbar-count">{{ t('common.total', { total: props.alarmPageData.total }) }}</span>
        <el-button size="small" :loading="exporting === 'csv'" :disabled="Boolean(exporting)" @click="handleExport('csv', props.exportCsv)">{{ t('common.exportCsv') }}</el-button>
        <el-button size="small" :loading="exporting === 'json'" :disabled="Boolean(exporting)" @click="handleExport('json', props.exportJson)">{{ t('common.exportJson') }}</el-button>
      </div>
    </div>

    <div class="alarm-toolbar-actions">
      <el-select v-model="owner" :aria-label="t('analystJourney.workQueue')" @change="searchOwner">
        <el-option :label="t('analystJourney.allWork')" value="" /><el-option :label="t('analystJourney.myWork')" value="mine" /><el-option :label="t('analystJourney.unassigned')" value="unassigned" />
      </el-select>
      <span v-if="route.query.entity">{{ t('analystJourney.exactEntity', { entity: String(route.query.entity) }) }} </span>
      <span v-if="route.query.from || route.query.to">{{ route.query.from || '…' }} → {{ route.query.to || '…' }}</span>
    </div>
    <div v-if="props.canWrite && selectedAlarms.length" class="alarm-batchbar">
      <strong>{{ selectedAlarms.length }} {{ t('alarms.selected') }}</strong>
      <span>{{ t('alarms.batchHint') }}</span>
      <el-select v-model="batchOperation" :disabled="batchConfirming || batchBusy" size="small" style="width:150px"><el-option :label="t('forms.assign')" value="assign" /><el-option :label="t('forms.changeStatus')" value="status" /></el-select>
      <el-select v-if="batchOperation === 'status'" v-model="batchStatus" :disabled="batchConfirming || batchBusy" size="small" style="width:150px">
        <el-option v-for="item in DISP_STATUSES" :key="item" :label="tOr(t, 'statuses.' + item, item)" :value="item" />
      </el-select>
      <el-select v-else v-model="batchAssignee" :disabled="batchConfirming || batchBusy" filterable default-first-option clearable size="small" :placeholder="t('drawer.assigneePlaceholder')" style="width:180px">
        <el-option v-for="assignee in props.assigneeOptions ?? []" :key="assignee" :label="assignee" :value="assignee" />
      </el-select>
      <select v-if="batchOperation === 'status' && ['RESOLVED', 'CLOSED'].includes(batchStatus)" v-model="batchClassification" :aria-label="t('analystJourney.batchClassification')"><option value="TRUE_POSITIVE">{{ t('analystJourney.truePositive') }}</option><option value="FALSE_POSITIVE">{{ t('analystJourney.falsePositive') }}</option><option value="BENIGN">{{ t('analystJourney.benign') }}</option><option value="UNDETERMINED">{{ t('analystJourney.undetermined') }}</option></select>
      <el-input v-model="batchReason" :aria-label="t('analystJourney.batchReason')" :placeholder="t('analystJourney.batchReasonPlaceholder')" :disabled="batchConfirming || batchBusy" />
      <el-button size="small" type="primary" :loading="batchBusy" :disabled="batchConfirming" @click="handleBatchUpdate">{{ t('common.update') }}</el-button>
      <span v-if="batchError" class="alarm-batch-error" role="alert">{{ batchError }}</span>
    </div>

    <div v-if="exportError" class="alarm-feedback error" role="alert"><strong>{{ t('alarms.exportFailed') }}</strong><span>{{ exportError }}</span></div>

    <div v-if="props.error" class="alarm-feedback error" role="alert">
      <strong>{{ t('alarms.loadFailed') }}</strong>
      <span>{{ props.error }}</span>
      <el-button size="small" @click="props.loadPage">{{ t('common.refresh') }}</el-button>
    </div>
    <div v-if="deepLinkLoading" class="alarm-feedback" role="status">{{ t('common.loading') }}</div>
    <div v-else-if="deepLinkError" class="alarm-feedback error" role="alert">
      <strong>{{ t('alarms.loadFailed') }}</strong><span>{{ deepLinkError }}</span>
      <el-button size="small" @click="syncAlarmFromRoute">{{ t('common.retry') }}</el-button>
    </div>

    <el-card shadow="never" class="alarm-table-card">
      <el-table v-loading="props.loading" :data="props.filteredAlarms" class="alarm-table" height="100%" size="small" row-key="id" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @sort-change="handleSortChange" @row-click="openAlarmRow" @selection-change="handleSelectionChange">
        <el-table-column v-if="props.canWrite" type="selection" width="44" fixed="left" />
        <el-table-column prop="occurredAt" column-key="occurredAt" :label="t('alarms.occurredAt')" :width="columnWidth('occurredAt', 172)" sortable="custom"><template #default="{ row }"><span class="mono">{{ relTime(row.occurredAt) }}</span></template></el-table-column>
        <el-table-column prop="severity" column-key="severity" :label="t('common.severity')" :width="columnWidth('severity', 100)" sortable="custom"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
        <el-table-column prop="title" column-key="title" :label="t('alarms.alertTitle')" :width="columnWidth('title', 220)" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.title || row.ruleName || row.ruleId }}</template></el-table-column>
        <el-table-column prop="ruleName" column-key="ruleName" :label="t('alarms.ruleName')" :width="columnWidth('ruleName')" min-width="180" sortable="custom" show-overflow-tooltip><template #default="{ row }">{{ row.ruleName || row.ruleId }}</template></el-table-column>
        <el-table-column prop="entity" column-key="entity" :label="t('common.entity')" :width="columnWidth('entity')" min-width="150" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="assignee" :label="t('assets.owner')" min-width="130"><template #default="{ row }">{{ row.assignee || t('analystJourney.unassigned') }}</template></el-table-column>
        <el-table-column prop="status" column-key="status" :label="t('common.status')" :width="columnWidth('status', 125)" sortable="custom"><template #default="{ row }"><span class="alarm-status" :class="(row.status || 'OPEN').toLowerCase()">{{ tOr(t, 'statuses.' + (row.status || 'OPEN'), row.status ?? '') }}</span></template></el-table-column>
        <el-table-column prop="riskScore" column-key="riskScore" :label="t('alarms.riskScore')" :width="columnWidth('riskScore', 90)" sortable="custom"><template #default="{ row }">{{ row.riskScore ?? '—' }}</template></el-table-column>
        <el-table-column prop="message" column-key="message" :label="t('common.message')" :width="columnWidth('message')" min-width="260" show-overflow-tooltip />
        <el-table-column :label="t('common.actions')" width="78" fixed="right" :resizable="false"><template #default="{ row }"><el-button link type="primary" size="small" @click.stop="openAlarmRow(row)">{{ t('alarms.triage') }}</el-button></template></el-table-column>
        <template #empty>
          <EmptyState v-if="!props.loading && !props.error" :title="t('alarms.noAlarmsFound')" :description="t('alarms.adjustFiltersHint')"><template #action><InvestigationReadiness :can-inspect="Boolean(props.canWrite)" /></template></EmptyState>
        </template>
      </el-table>
    </el-card>

    <PagerBar class="alarm-pagination" v-model:current-page="pageNum" v-model:page-size="pageSize" :total="props.alarmPageData.total" :page-sizes="[10, 20, 50, 100]" />

    <AlarmDispositionDrawer v-model="drawerVisible" :alarm="currentAlarm" :go-case="props.goCase" :go-search="props.goSearch" :go-ai="props.goAi" :go-soar="props.goSoar" :assignee-options="props.assigneeOptions" :can-write="props.canWrite" :can-admin="props.canAdmin" @updated="props.loadPage" />
  </div>
</template>
