<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/descriptions/style/css.mjs'
import 'element-plus/es/components/divider/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { ElDescriptions, ElDescriptionsItem } from 'element-plus/es/components/descriptions/index.mjs'
import ElDivider from 'element-plus/es/components/divider/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, ref, watch } from 'vue'
import SevBadge from './SevBadge.vue'
import type { Alarm, AlarmEvidenceResponse, CaseInfo, Disposition, Ioc } from '../api'
import { addAlarmNote, assignAlarm, getAlarmEvidence, getDisposition, setDispositionStatus } from '../api/alarms'
import { createCaseFromAlarm, listCases as loadCases } from '../api/incidents'
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
}>(), {
  canWrite: true,
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
const detailsLoading = ref(false)
const newStatus = ref('OPEN')
const newAssignee = ref('')
const newNote = ref('')
const statusBusy = ref(false)
const assignBusy = ref(false)
const noteBusy = ref(false)
const creatingCase = ref(false)
const actionError = ref('')
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
  actionError.value = ''
  newStatus.value = alarm.status || 'OPEN'
  newAssignee.value = ''
  newNote.value = ''
  noteKey = ''
  const [disp, ev, cases] = await Promise.allSettled([getDisposition(alarm.id), getAlarmEvidence(alarm.id), loadCases()])
  if (token !== loadToken) return
  detailsLoading.value = false
  if (disp.status === 'fulfilled') disposition.value = disp.value
  else dispositionError.value = t('drawer.loadDispositionFailed')
  if (ev.status === 'fulfilled') evidence.value = ev.value
  else evidenceError.value = t('drawer.loadEvidenceFailed')
  if (cases.status === 'fulfilled') relatedCase.value = cases.value.items.find(item => item.alarmIds.includes(alarm.id)) ?? null
  else relatedCaseError.value = t('drawer.loadCaseFailed')
}

function retryDetails(): void {
  if (props.alarm) void loadDetails(props.alarm)
}

watch(() => [props.modelValue, props.alarm?.id] as const, ([visible]) => {
  if (visible && props.alarm) void loadDetails(props.alarm)
}, { immediate: true })

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
    const cases = await loadCases()
    relatedCase.value = cases.items.find(item => item.id === result.caseId || item.caseNo === result.caseNo) ?? null
    if (result.duplicate) ElMessage.info(t('drawer.caseAlreadyLinked'))
    else ElMessage.success(t('drawer.caseCreated'))
    emit('updated')
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : String(error)
  } finally {
    creatingCase.value = false
  }
}

function openEvidenceSearch() {
  const query = evidence.value?.query
  if (!query) return
  props.goSearch(query)
}
</script>

<template>
  <el-drawer v-model="drawerVisible" class="alarm-detail-drawer" :title="`${t('drawer.title')} · ${props.alarm?.title || props.alarm?.ruleName || ''}`" size="min(720px, 92vw)">
    <template v-if="props.alarm">
      <el-descriptions :column="2" size="small" border style="margin-bottom:14px">
        <el-descriptions-item :label="t('drawer.ruleId')">{{ props.alarm.ruleId }}</el-descriptions-item>
        <el-descriptions-item :label="t('alarms.alertTitle')" :span="2">{{ props.alarm.title || props.alarm.ruleName || props.alarm.ruleId }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.severity')"><SevBadge :value="props.alarm.severity" /></el-descriptions-item>
        <el-descriptions-item :label="t('common.entity')">{{ props.alarm.entity }}</el-descriptions-item>
        <el-descriptions-item :label="t('alarms.occurredAt')">{{ props.alarm.occurredAt }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.message')" :span="2">{{ props.alarm.message }}</el-descriptions-item>
        <el-descriptions-item :label="t('drawer.mitre')" :span="2">
          <a v-if="props.alarm.mitre" :href="`https://attack.mitre.org/techniques/${String(props.alarm.mitre).replace('-', '/')}/`" target="_blank" style="color:var(--ns-accent-fg);font-weight:600">{{ props.alarm.mitre }}</a>
          <span v-else style="color:var(--ns-text-3)">—</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('drawer.tiHits')" :span="2">
          <span v-if="tiHits.length">
            <el-tag v-for="(hit, index) in tiHits" :key="index" size="small" type="danger" style="margin-right:6px;margin-bottom:4px">{{ hit.type }} · {{ hit.value }}</el-tag>
          </span>
          <span v-else style="color:var(--ns-text-3)">—</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" style="margin-bottom:14px" />

      <div class="alarm-context-actions">
        <span>{{ t('drawer.nextActions') }}</span>
        <el-button v-if="props.goAi && props.canWrite" size="small" type="primary" plain @click="props.goAi(props.alarm.id)">{{ t('drawer.openAiInvestigation') }}</el-button>
        <el-button v-if="props.goSoar" size="small" type="warning" plain @click="props.goSoar(props.alarm.id)">{{ t('drawer.openSoarResponse') }}</el-button>
      </div>

      <el-divider content-position="left">{{ t('drawer.evidence') }}</el-divider>
      <el-alert v-if="detailsLoading" :title="t('common.loading')" type="info" :closable="false" />
      <el-alert v-else-if="evidenceError" :title="evidenceError" type="error" :closable="false" show-icon>
        <el-button size="small" type="primary" plain @click="retryDetails">{{ t('common.retry') }}</el-button>
      </el-alert>
      <template v-else-if="evidence && evidence.items.length">
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;font-size:12px;color:var(--ns-text-3)">
          <span>{{ t('drawer.evidenceCount', { count: evidence.total }) }}</span>
          <div v-if="evidence.query" style="display:flex;align-items:center;gap:6px">
          <span class="mono" style="max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="evidence.query">{{ t('drawer.eventIdDrillDown') }}</span>
            <el-button link type="primary" size="small" @click="openEvidenceSearch">{{ t('drawer.openSearch') }}</el-button>
          </div>
        </div>
        <div v-for="item in evidence.items" :key="item.id" style="border:1px solid var(--el-border-color-lighter);border-radius:6px;padding:8px 10px;margin-bottom:8px;background:var(--ns-bg-subtle)">
          <div style="display:flex;gap:8px;align-items:center;font-size:12px;color:var(--ns-text-3);margin-bottom:4px">
            <span>{{ item.timestamp || '-' }}</span><span>{{ item.source || '-' }}</span><span>{{ item.host || '-' }}</span>
            <SevBadge v-if="item.severity" :value="item.severity" />
          </div>
          <div class="mono" style="white-space:pre-wrap;word-break:break-word;font-size:12px">{{ item.raw || '-' }}</div>
          <div v-if="item.eventId" style="margin-top:5px;color:var(--ns-text-3);font-size:11px">eventId: {{ item.eventId }}</div>
        </div>
      </template>
      <el-empty v-else :description="t('drawer.noEvidence')" :image-size="50" />

      <el-divider content-position="left">{{ t('drawer.stateFlow') }}</el-divider>
      <div v-if="detailsLoading" class="drawer-loading-hint">{{ t('common.loading') }}</div>
      <el-alert v-else-if="dispositionError" :title="dispositionError" type="error" :closable="false" show-icon>
        <el-button size="small" type="primary" plain @click="retryDetails">{{ t('common.retry') }}</el-button>
      </el-alert>
      <template v-else-if="disposition">
        <div class="drawer-readonly-hint" style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-bottom:8px">
          <span>{{ t('common.status') }}</span><el-tag size="small">{{ statusLabel(disposition.status) }}</el-tag>
          <span style="margin-left:8px">{{ t('cases.assignee') }}</span><span>{{ disposition.assignee || '—' }}</span>
        </div>
        <div v-if="props.canWrite" style="display:flex;gap:8px;margin-bottom:8px">
          <el-select v-model="newStatus" :disabled="actionPending" style="flex:1"><el-option v-for="s in DISP_STATUSES" :key="s" :label="tOr(t, 'statuses.' + s, s)" :value="s" /></el-select>
          <el-button type="primary" :loading="statusBusy" :disabled="actionPending" @click="changeStatus">{{ t('common.update') }}</el-button>
        </div>
        <div v-if="props.canWrite" style="display:flex;gap:8px;margin-bottom:14px">
          <el-select v-model="newAssignee" :disabled="actionPending" filterable default-first-option clearable :placeholder="t('drawer.assigneePlaceholder')" style="flex:1">
            <el-option v-for="assignee in assigneeOptions" :key="assignee" :label="assignee" :value="assignee" />
          </el-select><el-button :loading="assignBusy" :disabled="actionPending" @click="doAssign">{{ t('common.assign') }}</el-button>
        </div>
        <div v-else class="drawer-readonly-hint">{{ t('drawer.readOnly') }}</div>
      </template>

      <el-divider content-position="left">{{ t('drawer.notesTitle') }}</el-divider>
      <div v-if="detailsLoading" class="drawer-loading-hint">{{ t('common.loading') }}</div>
      <el-alert v-else-if="dispositionError" :title="dispositionError" type="error" :closable="false" />
      <div v-else-if="disposition && disposition.notes.length">
        <div v-for="(note, index) in disposition.notes" :key="index" style="background:var(--ns-bg-subtle);border-radius:6px;padding:8px 12px;margin-bottom:8px">
          <div style="font-size:12px;color:var(--ns-text-3)">{{ note.author }} · {{ note.at }}</div><div style="margin-top:2px">{{ note.content }}</div>
        </div>
      </div>
      <el-empty v-else-if="disposition" :description="t('drawer.noNotes')" :image-size="50" />
      <div v-if="props.canWrite && !detailsLoading && !dispositionError" style="display:flex;gap:8px;margin-top:8px">
        <el-input v-model="newNote" :placeholder="t('drawer.addNotePlaceholder')" @keyup.enter="doAddNote" /><el-button type="success" :loading="noteBusy" :disabled="actionPending" @click="doAddNote">{{ t('common.add') }}</el-button>
      </div>

      <el-divider content-position="left">{{ t('drawer.relatedCase') }}</el-divider>
      <el-card v-if="relatedCase" shadow="never" style="margin-bottom:10px">
        <div style="display:flex;justify-content:space-between;align-items:center;gap:8px">
          <div><div style="font-weight:600">{{ relatedCase.title }}</div><div style="font-size:12px;color:var(--ns-text-3);margin-top:2px">{{ relatedCase.id }} · {{ statusLabel(relatedCase.status) }} · {{ relatedCase.entity }} · {{ t('drawer.alarmCount', { count: relatedCase.alarmIds.length }) }}</div></div>
          <el-button link type="primary" size="small" @click="drawerVisible = false; props.goCase(relatedCase.id)">{{ t('drawer.goToCase') }}</el-button>
        </div>
      </el-card>
      <el-alert v-else-if="detailsLoading" :title="t('common.loading')" type="info" :closable="false" />
      <el-alert v-else-if="relatedCaseError" :title="relatedCaseError" type="error" :closable="false" show-icon>
        <el-button size="small" type="primary" plain @click="retryDetails">{{ t('common.retry') }}</el-button>
      </el-alert>
      <div v-else class="drawer-case-empty">
        <el-empty :description="t('drawer.noRelatedCase')" :image-size="50" />
        <el-button v-if="props.canWrite" type="primary" size="small" :loading="creatingCase" @click="createCase">{{ t('drawer.createCase') }}</el-button>
      </div>
    </template>
  </el-drawer>
</template>
