<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/descriptions/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { ElDescriptions, ElDescriptionsItem } from 'element-plus/es/components/descriptions/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import { computed, ref, watch } from 'vue'
import SevBadge from './SevBadge.vue'
import type { Alarm, AlarmDeliveryStatus, AlarmEvidenceResponse, CaseInfo, Disposition, Ioc } from '../api'
import { addAlarmNote, assignAlarm, getAlarmDeliveries, getAlarmEvidence, getDisposition, requeueAlarmDelivery, setDispositionStatus } from '../api/alarms'
import { createCaseFromAlarm, getCaseByAlarm } from '../api/incidents'
import { ApiError } from '../api/core'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'

const props = withDefaults(defineProps<{
  modelValue: boolean
  alarm: Alarm | null
  goCase: (caseId?: string) => void
  goSearch: (query?: string) => void
  goAi?: (alarmId: string) => void
  goSoar?: (alarmId: string) => void
  assigneeOptions?: string[]
  canWrite?: boolean
  canAdmin?: boolean
}>(), {
  canWrite: true,
  canAdmin: false,
  assigneeOptions: () => [],
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  updated: []
}>()
const drawerVisible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const { t } = useI18n()

const DISP_STATUSES = ['OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED']
const disposition = ref<Disposition | null>(null)
const dispositionError = ref('')
const evidence = ref<AlarmEvidenceResponse | null>(null)
const evidenceError = ref('')
const relatedCase = ref<CaseInfo | null>(null)
const relatedCaseError = ref('')
const deliveries = ref<AlarmDeliveryStatus[]>([])
const deliveriesError = ref('')
const requeueBusy = ref('')
const detailsLoading = ref(false)
const newStatus = ref('OPEN')
const newAssignee = ref('')
const newNote = ref('')
const statusBusy = ref(false)
const assignBusy = ref(false)
const noteBusy = ref(false)
const creatingCase = ref(false)
const actionError = ref('')
const activeTab = ref('summary')
const actionPending = computed(() => statusBusy.value || assignBusy.value || noteBusy.value)
let loadToken = 0
// Retrying the same unsent note must reuse its key so the backend set-once
// Idempotency-Key window can absorb the duplicate instead of appending twice.
let noteKey = ''

function newNoteKey(): string {
  if (!noteKey) {
    const suffix = typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : Math.random().toString(36).slice(2)
    noteKey = `workbench-note-${suffix}`
  }
  return noteKey
}

const tiHits = computed<Ioc[]>(() => {
  try {
    return props.alarm?.tiHits ? JSON.parse(props.alarm.tiHits) as Ioc[] : []
  } catch {
    return []
  }
})
const assigneeOptions = computed(() => {
  const values = new Set(props.assigneeOptions)
  if (disposition.value?.assignee) values.add(disposition.value.assignee)
  return [...values].filter(Boolean)
})
function statusLabel(status: string): string {
  const value = String(status || '')
  if (!value) return t('time.notAvailable')
  return tOr(t, 'statuses.' + value, value)
}

async function loadDetails(alarm: Alarm) {
  const token = ++loadToken
  detailsLoading.value = true
  disposition.value = null
  dispositionError.value = ''
  evidence.value = null
  evidenceError.value = ''
  relatedCase.value = null
  relatedCaseError.value = ''
  deliveries.value = []
  deliveriesError.value = ''
  actionError.value = ''
  newStatus.value = alarm.status || 'OPEN'
  newAssignee.value = ''
  newNote.value = ''
  noteKey = ''
  const [disp, ev, linkedCase, deliveryResult] = await Promise.allSettled([
    getDisposition(alarm.id), getAlarmEvidence(alarm.id), getCaseByAlarm(alarm.id), getAlarmDeliveries(alarm.id),
  ])
  if (token !== loadToken) return
  detailsLoading.value = false
  if (disp.status === 'fulfilled') disposition.value = disp.value
  else dispositionError.value = t('drawer.loadDispositionFailed')
  if (ev.status === 'fulfilled') evidence.value = ev.value
  else evidenceError.value = t('drawer.loadEvidenceFailed')
  if (linkedCase.status === 'fulfilled') relatedCase.value = linkedCase.value
  else if (!(linkedCase.reason instanceof ApiError && linkedCase.reason.status === 404)) relatedCaseError.value = t('drawer.loadCaseFailed')
  if (deliveryResult.status === 'fulfilled') deliveries.value = deliveryResult.value
  else deliveriesError.value = t('drawer.loadDeliveriesFailed')
}

function retryDetails(): void {
  if (props.alarm) void loadDetails(props.alarm)
}

watch(() => [props.modelValue, props.alarm?.id] as const, ([visible]) => {
  if (visible && props.alarm) void loadDetails(props.alarm)
}, { immediate: true })

watch(() => props.alarm?.id, () => { activeTab.value = 'summary' })

async function changeStatus() {
  if (!props.alarm || !props.canWrite || statusBusy.value) return
  statusBusy.value = true
  actionError.value = ''
  try {
    await setDispositionStatus(props.alarm.id, newStatus.value)
    disposition.value = await getDisposition(props.alarm.id)
    ElMessage.success(t('common.updated'))
    emit('updated')
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : String(error)
  } finally {
    statusBusy.value = false
  }
}

async function doAssign() {
  if (!props.alarm || !props.canWrite) return
  const assignee = newAssignee.value.trim()
  if (!assignee) { ElMessage.warning(t('forms.fieldRequired', { field: t('cases.assignee') })); return }
  if (assignBusy.value) return
  assignBusy.value = true
  actionError.value = ''
  try {
    await assignAlarm(props.alarm.id, assignee)
    newAssignee.value = ''
    disposition.value = await getDisposition(props.alarm.id)
    ElMessage.success(t('common.updated'))
    emit('updated')
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : String(error)
  } finally {
    assignBusy.value = false
  }
}

async function doAddNote() {
  if (!props.alarm || !props.canWrite) return
  const content = newNote.value.trim()
  if (!content) { ElMessage.warning(t('forms.fieldRequired', { field: t('common.notes') })); return }
  if (noteBusy.value) return
  noteBusy.value = true
  actionError.value = ''
  try {
    await addAlarmNote(props.alarm.id, content, 'operator', newNoteKey())
    newNote.value = ''
    noteKey = ''
    disposition.value = await getDisposition(props.alarm.id)
    ElMessage.success(t('common.saved'))
    emit('updated')
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : String(error)
  } finally {
    noteBusy.value = false
  }
}

async function createCase(): Promise<void> {
  if (!props.alarm || !props.canWrite || creatingCase.value) return
  creatingCase.value = true
  actionError.value = ''
  try {
    const result = await createCaseFromAlarm(props.alarm)
    relatedCase.value = await getCaseByAlarm(props.alarm.id)
    if (result.duplicate) ElMessage.info(t('drawer.caseAlreadyLinked'))
    else ElMessage.success(t('drawer.caseCreated'))
    emit('updated')
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : String(error)
  } finally {
    creatingCase.value = false
  }
}

async function requeueDelivery(delivery: AlarmDeliveryStatus): Promise<void> {
  if (!props.canAdmin || requeueBusy.value) return
  requeueBusy.value = delivery.deliveryId
  actionError.value = ''
  try {
    await requeueAlarmDelivery(delivery.deliveryId)
    if (props.alarm) deliveries.value = await getAlarmDeliveries(props.alarm.id)
    ElMessage.success(t('drawer.deliveryRequeued'))
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : String(error)
  } finally {
    requeueBusy.value = ''
  }
}

function openEvidenceSearch() {
  const query = evidence.value?.query
  if (!query) return
  props.goSearch(query)
}
</script>

<template>
  <el-drawer v-model="drawerVisible" class="alarm-detail-drawer" :title="`${t('drawer.title')} · ${props.alarm?.title || props.alarm?.ruleName || ''}`" size="min(760px, 96vw)">
    <template v-if="props.alarm">
      <div class="alarm-action-strip">
        <div class="alarm-action-state">
          <SevBadge :value="props.alarm.severity" />
          <span>{{ t('common.status') }}</span>
          <el-tag size="small">{{ statusLabel(disposition?.status || props.alarm.status) }}</el-tag>
        </div>
        <div class="alarm-action-buttons">
          <el-button v-if="evidence?.query" size="small" @click="openEvidenceSearch">{{ t('drawer.openSearch') }}</el-button>
          <el-button v-if="relatedCase" size="small" @click="drawerVisible = false; props.goCase(relatedCase.id)">{{ t('drawer.goToCase') }}</el-button>
          <el-button v-else-if="props.canWrite && !relatedCaseError" size="small" type="primary" plain :loading="creatingCase" @click="createCase">{{ t('drawer.createCase') }}</el-button>
          <el-button v-if="props.goAi && props.canWrite" size="small" type="primary" plain @click="props.goAi(props.alarm.id)">{{ t('drawer.openAiInvestigation') }}</el-button>
          <el-button v-if="props.goSoar" size="small" type="warning" plain @click="props.goSoar(props.alarm.id)">{{ t('drawer.openSoarResponse') }}</el-button>
        </div>
      </div>

      <el-alert v-if="actionError" class="alarm-action-error" :title="actionError" type="error" :closable="false" />

      <el-tabs v-model="activeTab" class="alarm-workbench-tabs">
        <el-tab-pane :label="t('drawer.tabs.summary')" name="summary">
          <section class="alarm-tab-section">
            <el-descriptions :column="2" size="small" border>
              <el-descriptions-item :label="t('drawer.ruleId')">{{ props.alarm.ruleId }}</el-descriptions-item>
              <el-descriptions-item :label="t('alarms.alertTitle')" :span="2">{{ props.alarm.title || props.alarm.ruleName || props.alarm.ruleId }}</el-descriptions-item>
              <el-descriptions-item :label="t('common.severity')"><SevBadge :value="props.alarm.severity" /></el-descriptions-item>
              <el-descriptions-item :label="t('common.entity')">{{ props.alarm.entity }}</el-descriptions-item>
              <el-descriptions-item :label="t('alarms.occurredAt')">{{ props.alarm.occurredAt }}</el-descriptions-item>
              <el-descriptions-item :label="t('common.message')" :span="2">{{ props.alarm.message }}</el-descriptions-item>
              <el-descriptions-item :label="t('drawer.mitre')" :span="2">
                <a v-if="props.alarm.mitre" class="alarm-mitre-link" :href="`https://attack.mitre.org/techniques/${String(props.alarm.mitre).replace('-', '/')}/`" target="_blank" rel="noopener noreferrer">{{ props.alarm.mitre }}</a>
                <span v-else class="alarm-muted">—</span>
              </el-descriptions-item>
              <el-descriptions-item :label="t('drawer.tiHits')" :span="2">
                <span v-if="tiHits.length" class="alarm-ti-hits"><el-tag v-for="(hit, index) in tiHits" :key="index" size="small" type="danger">{{ hit.type }} · {{ hit.value }}</el-tag></span>
                <span v-else class="alarm-muted">—</span>
              </el-descriptions-item>
            </el-descriptions>
          </section>

          <section class="alarm-tab-section">
            <h3>{{ t('drawer.relatedCase') }}</h3>
            <el-card v-if="relatedCase" shadow="never" class="alarm-related-case">
              <div>
                <strong>{{ relatedCase.title }}</strong>
                <span>{{ relatedCase.id }} · {{ statusLabel(relatedCase.status) }} · {{ relatedCase.entity }} · {{ t('drawer.alarmCount', { count: relatedCase.alarmIds.length }) }}</span>
              </div>
              <el-button link type="primary" size="small" @click="drawerVisible = false; props.goCase(relatedCase.id)">{{ t('drawer.goToCase') }}</el-button>
            </el-card>
            <el-alert v-else-if="detailsLoading" :title="t('common.loading')" type="info" :closable="false" />
            <el-alert v-else-if="relatedCaseError" :title="relatedCaseError" type="error" :closable="false" show-icon>
              <el-button size="small" type="primary" plain @click="retryDetails">{{ t('common.retry') }}</el-button>
            </el-alert>
            <div v-else class="drawer-case-empty">
              <el-empty :description="t('drawer.noRelatedCase')" :image-size="50" />
              <el-button v-if="props.canWrite" type="primary" size="small" :loading="creatingCase" @click="createCase">{{ t('drawer.createCase') }}</el-button>
            </div>
          </section>
        </el-tab-pane>

        <el-tab-pane :label="t('drawer.tabs.evidence')" name="evidence">
          <section class="alarm-tab-section">
            <div class="alarm-section-heading">
              <div><h3>{{ t('drawer.evidence') }}</h3><p v-if="evidence">{{ t('drawer.evidenceCount', { count: evidence.total }) }}</p></div>
              <el-button v-if="evidence?.query" link type="primary" size="small" @click="openEvidenceSearch">{{ t('drawer.openSearch') }}</el-button>
            </div>
            <el-alert v-if="detailsLoading" :title="t('common.loading')" type="info" :closable="false" />
            <el-alert v-else-if="evidenceError" :title="evidenceError" type="error" :closable="false" show-icon><el-button size="small" type="primary" plain @click="retryDetails">{{ t('common.retry') }}</el-button></el-alert>
            <template v-else-if="evidence && evidence.items.length">
              <article v-for="item in evidence.items" :key="item.id" class="alarm-evidence-item">
                <div class="alarm-evidence-meta"><span>{{ item.timestamp || '—' }}</span><span>{{ item.source || '—' }}</span><span>{{ item.host || '—' }}</span><SevBadge v-if="item.severity" :value="item.severity" /></div>
                <pre class="mono">{{ item.raw || '—' }}</pre>
                <small v-if="item.eventId">eventId: {{ item.eventId }}</small>
              </article>
            </template>
            <el-empty v-else :description="t('drawer.noEvidence')" :image-size="50" />
          </section>
        </el-tab-pane>

        <el-tab-pane :label="t('drawer.tabs.activity')" name="activity">
          <section class="alarm-tab-section">
            <h3>{{ t('drawer.stateFlow') }}</h3>
            <div v-if="detailsLoading" class="drawer-loading-hint">{{ t('common.loading') }}</div>
            <el-alert v-else-if="dispositionError" :title="dispositionError" type="error" :closable="false" show-icon><el-button size="small" type="primary" plain @click="retryDetails">{{ t('common.retry') }}</el-button></el-alert>
            <template v-else-if="disposition">
              <div class="alarm-current-owner"><span>{{ t('common.status') }}</span><el-tag size="small">{{ statusLabel(disposition.status) }}</el-tag><span>{{ t('cases.assignee') }}</span><strong>{{ disposition.assignee || '—' }}</strong></div>
              <div v-if="props.canWrite" class="alarm-form-row"><el-select v-model="newStatus" :disabled="actionPending"><el-option v-for="s in DISP_STATUSES" :key="s" :label="tOr(t, 'statuses.' + s, s)" :value="s" /></el-select><el-button type="primary" :loading="statusBusy" :disabled="actionPending" @click="changeStatus">{{ t('common.update') }}</el-button></div>
              <div v-if="props.canWrite" class="alarm-form-row"><el-select v-model="newAssignee" :disabled="actionPending" filterable default-first-option clearable :placeholder="t('drawer.assigneePlaceholder')"><el-option v-for="assignee in assigneeOptions" :key="assignee" :label="assignee" :value="assignee" /></el-select><el-button :loading="assignBusy" :disabled="actionPending" @click="doAssign">{{ t('common.assign') }}</el-button></div>
              <div v-else class="drawer-readonly-hint">{{ t('drawer.readOnly') }}</div>
            </template>
          </section>

          <section class="alarm-tab-section">
            <h3>{{ t('drawer.notesTitle') }}</h3>
            <div v-if="detailsLoading" class="drawer-loading-hint">{{ t('common.loading') }}</div>
            <el-alert v-else-if="dispositionError" :title="dispositionError" type="error" :closable="false" />
            <div v-else-if="disposition && disposition.notes.length" class="alarm-note-list">
              <article v-for="(note, index) in disposition.notes" :key="index"><small>{{ note.author }} · {{ note.at }}</small><p>{{ note.content }}</p></article>
            </div>
            <el-empty v-else-if="disposition" :description="t('drawer.noNotes')" :image-size="50" />
            <div v-if="props.canWrite && !detailsLoading && !dispositionError" class="alarm-form-row alarm-note-form"><el-input v-model="newNote" :placeholder="t('drawer.addNotePlaceholder')" @keyup.enter="doAddNote" /><el-button type="success" :loading="noteBusy" :disabled="actionPending" @click="doAddNote">{{ t('common.add') }}</el-button></div>
          </section>
        </el-tab-pane>

        <el-tab-pane :label="t('drawer.tabs.delivery')" name="delivery">
          <section class="alarm-tab-section">
            <div class="alarm-section-heading"><div><h3>{{ t('drawer.downstreamDelivery') }}</h3><p>{{ t('drawer.downstreamDeliveryHint') }}</p></div></div>
            <el-alert v-if="deliveriesError" :title="deliveriesError" type="error" :closable="false" show-icon><el-button size="small" type="primary" plain @click="retryDetails">{{ t('common.retry') }}</el-button></el-alert>
            <el-empty v-else-if="!detailsLoading && !deliveries.length" :description="t('drawer.noDeliveries')" :image-size="50" />
            <div v-else class="delivery-list">
              <el-card v-for="delivery in deliveries" :key="delivery.deliveryId" shadow="never" class="delivery-card">
                <div class="delivery-heading"><strong>{{ delivery.destination }}</strong><el-tag size="small" :type="delivery.status === 'DELIVERED' ? 'success' : delivery.status === 'DEAD' ? 'danger' : 'warning'">{{ delivery.status }}</el-tag></div>
                <div class="drawer-readonly-hint">{{ t('drawer.deliveryAttempts', { count: delivery.attempts }) }} · {{ delivery.deliveredAt || delivery.nextAttemptAt || '—' }}</div>
                <div v-if="delivery.lastError" class="delivery-error">{{ delivery.lastError }}</div>
                <el-button v-if="props.canAdmin && delivery.status === 'DEAD'" size="small" type="warning" plain :loading="requeueBusy === delivery.deliveryId" :disabled="Boolean(requeueBusy)" @click="requeueDelivery(delivery)">{{ t('drawer.requeueDelivery') }}</el-button>
              </el-card>
            </div>
          </section>
        </el-tab-pane>
      </el-tabs>
    </template>
  </el-drawer>
</template>

<style scoped>
.alarm-action-strip { position: sticky; top: -20px; z-index: 4; display: flex; align-items: center; justify-content: space-between; gap: 10px; margin: -20px -24px 14px; padding: 11px 24px; border-bottom: 1px solid var(--ns-border); background: color-mix(in srgb, var(--ns-surface) 96%, transparent); backdrop-filter: blur(10px); }
.alarm-action-state, .alarm-action-buttons { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }
.alarm-action-state > span:not(.sev-badge) { color: var(--ns-text-3); font-size: 11px; }
.alarm-action-error { margin-bottom: 12px; }
.alarm-workbench-tabs :deep(.el-tabs__header) { margin-bottom: 18px; }
.alarm-workbench-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: var(--ns-border); }
.alarm-tab-section { margin-bottom: 24px; }
.alarm-tab-section h3 { margin: 0 0 10px; color: var(--ns-text); font-size: 13px; font-weight: 680; }
.alarm-section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.alarm-section-heading h3 { margin-bottom: 3px; }
.alarm-section-heading p { margin: 0; color: var(--ns-text-3); font-size: 11px; }
.alarm-mitre-link { color: var(--ns-accent-fg); font-weight: 600; }
.alarm-muted { color: var(--ns-text-3); }
.alarm-ti-hits { display: flex; flex-wrap: wrap; gap: 5px; }
.alarm-related-case { border-color: var(--ns-border); }
.alarm-related-case :deep(.el-card__body) { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.alarm-related-case strong, .alarm-related-case span { display: block; }
.alarm-related-case span { margin-top: 3px; color: var(--ns-text-3); font-size: 11px; }
.alarm-evidence-item { margin-bottom: 9px; padding: 10px 12px; border: 1px solid var(--ns-border); border-radius: 7px; background: var(--ns-bg-subtle); }
.alarm-evidence-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 6px; color: var(--ns-text-3); font-size: 11px; }
.alarm-evidence-item pre { margin: 0; overflow: auto; white-space: pre-wrap; word-break: break-word; font-size: 12px; }
.alarm-evidence-item small { display: block; margin-top: 6px; color: var(--ns-text-3); }
.alarm-current-owner { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; margin-bottom: 10px; color: var(--ns-text-3); font-size: 12px; }
.alarm-current-owner span:nth-of-type(2) { margin-left: 8px; }
.alarm-current-owner strong { color: var(--ns-text-2); }
.alarm-form-row { display: flex; gap: 8px; margin-bottom: 9px; }
.alarm-form-row .el-select, .alarm-form-row .el-input { flex: 1; }
.alarm-note-form { margin-top: 10px; }
.alarm-note-list { display: grid; gap: 8px; }
.alarm-note-list article { padding: 9px 12px; border-left: 2px solid var(--ns-border-strong); background: var(--ns-bg-subtle); }
.alarm-note-list small { color: var(--ns-text-3); }
.alarm-note-list p { margin: 4px 0 0; color: var(--ns-text-2); }
.delivery-list { display: grid; gap: 8px; }
.delivery-card { border-color: var(--ns-border); }
.delivery-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.delivery-error { margin: 6px 0; color: var(--el-color-danger); font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 640px) {
  .alarm-action-strip { align-items: flex-start; flex-direction: column; top: -20px; }
  .alarm-action-buttons { width: 100%; }
  .alarm-related-case :deep(.el-card__body) { align-items: flex-start; flex-direction: column; }
}
</style>
