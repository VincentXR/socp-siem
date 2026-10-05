<script setup lang="ts">
import { useConfirm } from '../composables/useConfirm'
import { getAlarm } from '../api/alarms'
import { lookupRuleOptions, type RuleOption } from '../api/detect'
import type { Alarm } from '../api/models'
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
import 'element-plus/es/components/tabs/style/css.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
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
import { useFocusReturn } from '../composables/useFocusReturn'
import { caseApi, type CaseInfo, type TimelineEvent } from '../api/domains'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'
import { WORKBENCH_STATE } from '../app/workbenchState'

const { t, d } = useI18n()
const { confirmDanger } = useConfirm()
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
const latestAlarms = useLatestRequest()
const latestRules = useLatestRequest()
const timeline = ref<TimelineEvent[]>([])
const timelineError = ref('')
const timelineLoading = ref(false)
const timelinePage = ref(1)
const timelineSize = ref(20)
const timelineTotal = ref(0)
const associatedAlarms = ref<string[]>([])
const alarmDetails = ref<Record<string, Alarm>>({})
const alarmDetailErrors = ref<string[]>([])
const ruleDetails = ref<Record<string, RuleOption>>({})
const referenceLoading = ref(false)
const associatedAlarmPage = ref(1)
const associatedAlarmPageSize = ref(20)
const associatedAlarmTotal = ref(0)
const associatedAlarmError = ref('')
const associatedRules = ref<string[]>([])
const associatedRulePage = ref(1)
const associatedRulePageSize = ref(20)
const associatedRuleTotal = ref(0)
const associatedRuleError = ref('')
const drawerVisible = ref(false)
const detailTab = ref('investigation')
const restoreDrawerFocus = useFocusReturn(drawerVisible)
const createDialogVisible = ref(false)
const caseForm = ref({ title: '', entity: '', severity: 'HIGH', assignee: '' })
const titleError = ref('')
const newStatus = ref('')
const detailAssignee = ref('')
const noteContent = ref('')
const noteEvidence = ref('')
const closure = ref({ classification: '', result: '', reason: '', evidence: '', remainingActions: '' })
const needsClosure = computed(() => Boolean(detail.value && ['RESOLVED', 'CLOSED'].includes(newStatus.value) && newStatus.value !== detail.value.status))
const currentUser = computed(() => workbenchState?.currentUser.value ?? '')
let noteAttempt = { signature: '', key: '' }
let changeAttempt = { signature: '', key: '' }
let claimAttempt = { signature: '', key: '' }
const returnTarget = computed(() => {
  const value = route.query.returnTo
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.includes('\\') && router.resolve(value).matched.length ? value : ''
})
const exportMutation = useMutation()
const selectedId = computed(() => typeof route.query.caseId === 'string' ? route.query.caseId : '')
const CASE_STATUSES = ['OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED']
const loadError = ref('')
const cases = ref<CaseInfo[]>([])
const size = ref(20)
const total = ref(0)
const listQuery = useListQuery({ routeName: 'case', total, size, fields: [{ key: 'status', validate: status => CASE_STATUSES.includes(status) ? status : '' }, { key: 'queue', validate: queue => ['mine', 'unassigned'].includes(queue) ? queue : '' }] })
const page = listQuery.page
const keyword = listQuery.keyword
const statusFilter = listQuery.filters.status
const queueFilter = listQuery.filters.queue
const loading = ref(false)
const latestRequest = useLatestRequest()
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('cases')
function assigneeLabel(id: string): string {
  const labels = workbenchState?.operatorLabels?.value
  return labels && Object.hasOwn(labels, id) ? labels[id] : id
}
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
      caseApi.list(page.value, size.value, listQuery.keywordParam.value, statusFilter.value || undefined, { signal: request.signal }, queueFilter.value as 'mine' | 'unassigned' || undefined),
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
  latestAlarms.cancel(); latestRules.cancel()
  const id = selectedId.value
  detail.value = null
  newStatus.value = ''; detailAssignee.value = ''; noteContent.value = ''; noteEvidence.value = ''
  closure.value = { classification: '', result: '', reason: '', evidence: '', remainingActions: '' }
  alarmDetails.value = {}; ruleDetails.value = {}; alarmDetailErrors.value = []
  noteAttempt = { signature: '', key: '' }; changeAttempt = { signature: '', key: '' }
  detailGuard.markSaved()
  timeline.value = []; timelineError.value = ''; timelineLoading.value = false
  timelinePage.value = 1; timelineTotal.value = 0
  associatedAlarms.value = []; associatedAlarmPage.value = 1; associatedAlarmTotal.value = 0; associatedAlarmError.value = ''
  associatedRules.value = []; associatedRulePage.value = 1; associatedRuleTotal.value = 0; associatedRuleError.value = ''
  detailError.value = ''; actionError.value = ''
  createDialogVisible.value = false
  drawerVisible.value = Boolean(id)
  detailLoading.value = Boolean(id)
  if (!id) return
  try {
    const result = await caseApi.get(id, { signal: request.signal })
    if (!request.isCurrent()) return
    applyDetail(result)
    void Promise.all([loadTimeline(), loadAlarms(), loadRules()])
  } catch (failure) {
    if (request.isCurrent()) detailError.value = String(failure)
  } finally {
    if (request.isCurrent()) detailLoading.value = false
  }
}

async function loadAlarms() {
  if (!detail.value) return
  const request = latestAlarms.start()
  const id = detail.value.id
  associatedAlarmError.value = ''; referenceLoading.value = true
  try {
    const result = await caseApi.alarms(id, associatedAlarmPage.value, associatedAlarmPageSize.value, { signal: request.signal })
    if (!request.isCurrent()) return
    associatedAlarms.value = result.items; associatedAlarmTotal.value = result.total
    alarmDetails.value = {}; alarmDetailErrors.value = []
    // Bound detail fan-out to four reads at a time while keeping failed IDs navigable.
    for (let offset = 0; offset < result.items.length; offset += 4) {
      if (!request.isCurrent()) return
      const ids = result.items.slice(offset, offset + 4)
      const records = await Promise.allSettled(ids.map(alarmId => getAlarm(alarmId, { signal: request.signal })))
      if (!request.isCurrent()) return
      records.forEach((record, index) => {
        if (record.status === 'fulfilled') alarmDetails.value[ids[index]] = record.value
        else alarmDetailErrors.value.push(ids[index])
      })
      if (alarmDetailErrors.value.length) associatedAlarmError.value = t('workflow.partialAlarmDetails')
    }
  } catch (failure) {
    if (request.isCurrent()) associatedAlarmError.value = String(failure)
  } finally { if (request.isCurrent()) referenceLoading.value = false }
}

async function loadRules() {
  if (!detail.value) return
  const request = latestRules.start()
  const id = detail.value.id
  associatedRuleError.value = ''
  try {
    const result = await caseApi.rules(id, associatedRulePage.value, associatedRulePageSize.value, { signal: request.signal })
    if (!request.isCurrent()) return
    associatedRules.value = result.items; associatedRuleTotal.value = result.total
    if (canWrite.value && result.items.length) {
      const records = await lookupRuleOptions(result.items, { signal: request.signal })
      if (!request.isCurrent()) return
      ruleDetails.value = Object.fromEntries(records.map(item => [item.id, item]))
    }
  } catch (failure) {
    if (request.isCurrent()) associatedRuleError.value = String(failure)
  }
}

function applyDetail(item: CaseInfo) {
  detail.value = item
  newStatus.value = item.status
  detailAssignee.value = item.assignee ?? ''
  closure.value = { classification: '', result: '', reason: '', evidence: '', remainingActions: '' }
  // A successful metadata save must not silently mark a pending note as saved.
  const pendingNote = noteContent.value; const pendingEvidence = noteEvidence.value
  noteContent.value = ''; noteEvidence.value = ''
  detailGuard.markSaved()
  noteContent.value = pendingNote; noteEvidence.value = pendingEvidence
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
  void router.push({ name: 'alarms', query: { alarmId: id, caseId: detail.value?.id, returnTo: route.fullPath } })
}

function openRule(id: string): void {
  if (!canWrite.value || !id.trim()) return
  void router.push({ name: 'rule-edit', params: { ruleId: id }, query: { caseId: detail.value?.id, returnTo: route.fullPath } })
}

function evidenceLinks(message: string): string[] {
  return Array.from(new Set(message.match(/https?:\/\/[^\s<>"']+/g) ?? []))
}

function age(createdAt?: string): string {
  const elapsed = createdAt ? Date.now() - Date.parse(createdAt) : NaN
  if (!Number.isFinite(elapsed)) return '—'
  const minutes = Math.max(0, Math.floor(elapsed / 60000))
  if (minutes < 60) return t('cases.ageMinutes', { minutes })
  if (minutes < 1440) return t('cases.ageHours', { hours: Math.floor(minutes / 60) })
  return t('cases.ageDays', { days: Math.floor(minutes / 1440) })
}

function openContext(name: 'ai' | 'soar', alarmId?: string): void {
  if (!detail.value || !canWrite.value || actionBusy.value) return
  void router.push({ name, query: { caseId: detail.value.id, alarmId, tab: name === 'soar' ? 'runs' : undefined, returnTo: route.fullPath } })
}

const associationVisible = ref(false)
const association = ref({ operation: 'ATTACH' as 'ATTACH' | 'DETACH' | 'MOVE', alarmId: '', targetCaseId: '', reason: '' })
const associationAlarm = ref<Alarm | null>(null)
const associationTargets = ref<CaseInfo[]>([])
const associationTargetTotal = ref(0)
const associationLookupError = ref('')
const associationAlarmLoading = ref(false)
const associationTargetsLoading = ref(false)
const associationLookupLoading = computed(() => associationAlarmLoading.value || associationTargetsLoading.value)
const associationAlarmRequests = useLatestRequest()
const associationTargetRequests = useLatestRequest()
let associationAttempt = { signature: '', key: '' }
const associationGuard = useFormDialog(associationVisible, () => association.value, () => actionBusy.value)
async function openAssociation(operation: 'ATTACH' | 'DETACH' | 'MOVE', alarmId = '') {
  if (!canWrite.value || actionBusy.value || !detail.value || !await detailGuard.canLeave()) return
  applyDetail(detail.value)
  noteContent.value = ''; noteEvidence.value = ''; detailGuard.markSaved()
  association.value = { operation, alarmId, targetCaseId: '', reason: '' }
  associationAlarm.value = alarmDetails.value[alarmId] ?? null
  associationTargets.value = []; associationTargetTotal.value = 0; associationLookupError.value = ''
  associationVisible.value = true
  if (operation === 'MOVE') void searchAssociationTargets('')
}
async function inspectAssociationAlarm() {
  const request = associationAlarmRequests.start()
  const id = association.value.alarmId.trim()
  associationAlarm.value = null; associationLookupError.value = ''; associationAlarmLoading.value = true
  try {
    const value = await getAlarm(id, { signal: request.signal })
    if (request.isCurrent() && association.value.alarmId.trim() === id) associationAlarm.value = value
  } catch (error) { if (request.isCurrent()) associationLookupError.value = String(error) }
  finally { if (request.isCurrent()) associationAlarmLoading.value = false }
}
async function searchAssociationTargets(q: string) {
  const request = associationTargetRequests.start()
  associationLookupError.value = ''; associationTargetsLoading.value = true
  try {
    const result = await caseApi.list(1, 20, q, undefined, { signal: request.signal })
    if (request.isCurrent()) { associationTargets.value = result.items.filter(item => item.id !== detail.value?.id); associationTargetTotal.value = result.total }
  } catch (error) { if (request.isCurrent()) associationLookupError.value = String(error) }
  finally { if (request.isCurrent()) associationTargetsLoading.value = false }
}
async function saveAssociation() {
  if (!canWrite.value || actionBusy.value || !detail.value || !associationVisible.value) return
  const value = association.value
  if (!value.reason.trim() || !associationAlarm.value || associationAlarm.value.id !== value.alarmId.trim()) return
  const target = associationTargets.value.find(item => item.id === value.targetCaseId)
  if (value.operation === 'MOVE' && !target) return
  const id = detail.value.id
  const request = { operation: value.operation, alarmId: value.alarmId.trim(), reason: value.reason.trim(),
    expectedVersion: detail.value.rowVersion ?? 0,
    ...(target ? { targetCaseId: target.id, targetExpectedVersion: target.rowVersion ?? 0 } : {}) }
  const signature = JSON.stringify([id, request])
  if (associationAttempt.signature !== signature) associationAttempt = { signature, key: crypto.randomUUID() }
  await mutation.run(async () => {
    const result = await caseApi.changeAssociation(id, { ...request, idempotencyKey: associationAttempt.key })
    applyDetail(result.case)
    associationAttempt = { signature: '', key: '' }; associationVisible.value = false
    ElMessage.success(t('common.updated'))
    void Promise.all([loadAlarms(), loadRules(), loadTimeline(), loadCases()])
  })
}
watch(() => association.value.alarmId, id => {
  if (associationAlarm.value?.id !== id.trim()) associationAlarm.value = null
})
watch(associationVisible, visible => { if (!visible) { associationAlarmRequests.cancel(); associationTargetRequests.cancel(); associationAlarmLoading.value = false; associationTargetsLoading.value = false } })

async function claim(value: unknown) {
  const row = value as CaseInfo
  if (!canWrite.value || actionBusy.value || row.assignee || !currentUser.value) return
  const signature = JSON.stringify([row.id, row.rowVersion ?? 0])
  if (claimAttempt.signature !== signature) claimAttempt = { signature, key: crypto.randomUUID() }
  const saved = await mutation.run(async () => {
    const result = await caseApi.claim(row.id, row.rowVersion ?? 0, claimAttempt.key)
    claimAttempt = { signature: '', key: '' }
    if (detail.value?.id === row.id) {
      // Preserve an unsaved status/note while updating the authoritative owner.
      detail.value = result.case; detailAssignee.value = result.case.assignee ?? ''
    }
    cases.value = cases.value.map(item => item.id === row.id ? result.case : item)
    ElMessage.success(t('cases.claimed'))
  })
  if (saved) { void loadCases(); if (detail.value?.id === row.id) void loadTimeline() }
}

async function addNote() {
  if (!canWrite.value || actionBusy.value || !detail.value) return
  if (!noteContent.value.trim()) { actionError.value = t('cases.noteRequired'); return }
  const links = noteEvidence.value.split('\n').map(item => item.trim()).filter(Boolean)
  if (links.some(link => { try { return !['http:', 'https:'].includes(new URL(link).protocol) } catch { return true } })) {
    actionError.value = t('cases.invalidLinks'); return
  }
  const snapshot = detail.value
  const id = snapshot.id
  const metadataDraft = newStatus.value !== snapshot.status || detailAssignee.value.trim() !== (snapshot.assignee ?? '')
    || Object.values(closure.value).some(value => value.trim())
  const content = noteContent.value.trim() + (links.length ? '\nEvidence:\n' + links.join('\n') : '')
  const signature = JSON.stringify([id, content])
  if (noteAttempt.signature !== signature) noteAttempt = { signature, key: crypto.randomUUID() }
  const saved = await mutation.run(async () => {
    const result = await caseApi.addNote(id, content, noteAttempt.key)
    noteContent.value = ''; noteEvidence.value = ''; noteAttempt = { signature: '', key: '' }
    if (!metadataDraft) applyDetail(result.case)
    else if (result.case.status === snapshot.status && (result.case.assignee ?? '') === (snapshot.assignee ?? '')) detail.value = result.case
    else actionError.value = t('cases.changedWhileNoting')
    ElMessage.success(t('cases.noteAdded'))
  })
  if (saved) { timelinePage.value = Math.max(1, Math.ceil((timelineTotal.value + 1) / timelineSize.value)); void loadTimeline(); void loadCases() }
}

async function updateStatus() {
  if (!canWrite.value || actionBusy.value || !detail.value || !newStatus.value) return
  if (needsClosure.value && Object.values(closure.value).some(value => !value.trim())) {
    actionError.value = t('cases.closureRequired'); return
  }
  const id = detail.value.id
  const changes = { status: newStatus.value, assignee: detailAssignee.value.trim(), expectedVersion: detail.value.rowVersion ?? 0, ...(needsClosure.value ? closure.value : {}) }
  const signature = JSON.stringify([id, changes])
  if (changeAttempt.signature !== signature) changeAttempt = { signature, key: crypto.randomUUID() }
  const saved = await mutation.run(async () => {
    if (needsClosure.value && !await confirmDanger(t('cases.closureConfirm'))) return
    const result = await caseApi.saveChanges(id, { ...changes, idempotencyKey: changeAttempt.key })
    applyDetail(result.case)
    changeAttempt = { signature: '', key: '' }
    cases.value = cases.value.map(item => item.id === id ? result.case : item)
    ElMessage.success(t('cases.updatedSuccessfully'))
  })
  if (saved) { void loadCases(); void loadTimeline() }
}

async function refreshDetail() {
  if (actionBusy.value || !await detailGuard.canLeave()) return
  await loadDetail()
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
const detailGuard = useFormDialog(drawerVisible, () => ({ status: newStatus.value, assignee: detailAssignee.value, closure: closure.value, note: noteContent.value, evidence: noteEvidence.value }), () => actionBusy.value)
onBeforeRouteUpdate(async (to, from) => to.query.caseId === from.query.caseId
  || await createDialogVisibleGuard.canLeave() && await detailGuard.canLeave())
onMounted(loadCases)
watch([page, size], () => { listQuery.sync(); void loadCases() })
useDebouncedWatch([keyword, statusFilter, queueFilter], () => {
  const routeKeyword = typeof route.query.q === 'string' ? route.query.q.trim() : ''
  const routeStatus = typeof route.query.status === 'string' && CASE_STATUSES.includes(route.query.status) ? route.query.status : ''
  // A browser-history restore is already loaded at its requested page. Do not
  // let its delayed filter watcher turn it into a fresh page-one search.
  const routeQueue = typeof route.query.queue === 'string' && ['mine', 'unassigned'].includes(route.query.queue) ? route.query.queue : ''
  if (keyword.value.trim() === routeKeyword && statusFilter.value === routeStatus && queueFilter.value === routeQueue) return
  if (page.value !== 1) page.value = 1
  else { listQuery.sync(); void loadCases() }
})
watch(selectedId, () => { void loadDetail() }, { immediate: true })
watch([timelinePage, timelineSize], () => { void loadTimeline() })
watch([associatedAlarmPage, associatedAlarmPageSize], () => { void loadAlarms() })
watch([associatedRulePage, associatedRulePageSize], () => { void loadRules() })
watch([() => route.query.page, () => route.query.q, () => route.query.status, () => route.query.queue], () => {
  const before = { page: page.value, keyword: keyword.value.trim(), status: statusFilter.value, queue: queueFilter.value }
  listQuery.applyRouteQuery()
  if (before.page === page.value && (before.keyword !== keyword.value.trim() || before.status !== statusFilter.value || before.queue !== queueFilter.value)) void loadCases()
})
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback :error="exportMutation.error.value" />
    <ActionFeedback v-if="!drawerVisible && !createDialogVisible" :error="actionError" />
    <el-button v-if="returnTarget" link @click="router.push(returnTarget)">{{ t('cases.returnToOrigin') }}</el-button>
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

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="total" :loading="loading" :error="loadError" :retry="loadCases" :empty-title="t('cases.emptyCases')" :empty-description="t('cases.noMatches')">
      <template #toolbar>
        <FilterToolbar :count="total">
        <el-select v-model="queueFilter" :disabled="actionBusy" :aria-label="t('cases.queue')" @change="page = 1">
          <el-option :label="t('cases.allWork')" value="" />
          <el-option :label="t('cases.myWork')" value="mine" :disabled="!currentUser" />
          <el-option :label="t('cases.unassigned')" value="unassigned" />
        </el-select>
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
        <el-table-column prop="assignee" column-key="assignee" :label="t('cases.assignee')" min-width="120"><template #default="{ row }">{{ row.assignee || t('cases.unassigned') }}</template></el-table-column>
        <el-table-column column-key="age" :label="t('cases.age')" width="100"><template #default="{ row }"><span :title="row.createdAt ? d(row.createdAt) : ''">{{ age(row.createdAt) }}</span></template></el-table-column>
        <el-table-column prop="alarmCount" column-key="alarmCount" :label="t('cases.associatedAlarms')" :width="columnWidth('alarmCount', 90)"><template #default="{ row }">{{ row.alarmCount ?? row.alarmIds.length }}</template></el-table-column>
        <el-table-column :label="t('common.actions')" width="180" :resizable="false"><template #default="{ row }"><el-button link type="primary" size="small" :disabled="actionBusy" @click.stop="openCaseRow(row)">{{ t('cases.detailsTimeline') }}</el-button><el-button v-if="canWrite && !row.assignee && currentUser" link :disabled="actionBusy" @click.stop="claim(row)">{{ t('cases.claim') }}</el-button></template></el-table-column>
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
              <el-select v-model="caseForm.assignee" filterable default-first-option clearable :placeholder="t('cases.assigneePlaceholder')"><el-option v-for="assignee in assigneeOptions" :key="assignee" :label="assigneeLabel(assignee)" :value="assignee" /></el-select>
            </FormField>
          </FormGrid>
        </FormSection>
      </el-form>
      <template #footer>
        <el-button :disabled="actionBusy" @click="createDialogVisibleGuard.cancel">{{ t('common.cancel') }}</el-button>
        <el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="saveCase">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <el-drawer :model-value="drawerVisible" :before-close="closeDetail" :title="`${t('cases.title')} · ${detail?.title ?? selectedId}`" size="min(840px, 96vw)" @closed="restoreDrawerFocus">
      <p v-if="detailLoading" role="status">{{ t('common.loading') }}</p>
      <ActionFeedback :error="detailError" />
      <el-button v-if="detailError" @click="loadDetail">{{ t('common.retry') }}</el-button>
      <template v-if="detail">
        <ActionFeedback :error="actionError" />
        <el-button v-if="actionError" :disabled="actionBusy" @click="refreshDetail">{{ t('cases.reload') }}</el-button>
        <p class="dialog-hint">{{ t(canWrite ? 'cases.nextAction' : 'cases.readOnly') }}</p>
        <div class="case-work-actions">
          <el-button v-if="canWrite && !detail.assignee && currentUser" :disabled="actionBusy" @click="claim(detail)">{{ t('cases.claim') }}</el-button>
          <el-button v-if="canWrite" :disabled="actionBusy" @click="openContext('soar')">{{ t('cases.soar') }}</el-button>
          <el-button v-if="canWrite" :loading="exportMutation.busy.value" @click="exportMutation.run(() => caseApi.exportSummary(detail!.id))">{{ t('cases.summary') }}</el-button>
        </div>
        <p class="dialog-hint">{{ t('cases.summaryHint') }}</p>
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item :label="t('cases.caseId')">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.caseNo" :label="t('cases.caseNo')">{{ detail.caseNo }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.entity')">{{ detail.entity }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.severity')"><SevBadge :value="detail.severity" /></el-descriptions-item>
          <el-descriptions-item :label="t('cases.status')">{{ tOr(t, 'statuses.' + detail.status, detail.status) }}</el-descriptions-item>
          <el-descriptions-item :label="t('cases.assignee')">
            <el-select v-if="canWrite" v-model="detailAssignee" :aria-label="t('cases.assignee')" :disabled="actionBusy" filterable default-first-option clearable :placeholder="t('cases.assigneePlaceholder')" style="width:100%">
              <el-option v-for="assignee in assigneeOptions" :key="assignee" :label="assigneeLabel(assignee)" :value="assignee" />
            </el-select>
            <span v-else>{{ detail.assignee || '—' }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <el-tabs v-model="detailTab">
          <el-tab-pane :label="t('cases.investigationTab')" name="investigation">
        <template v-if="canWrite">
          <p class="dialog-hint">{{ t('forms.changeStatus') }}</p>
          <div class="case-status-row">
            <el-select v-model="newStatus" :disabled="actionBusy" :aria-label="t('cases.status')"><el-option v-for="status in CASE_STATUSES" :key="status" :label="tOr(t, 'statuses.' + status, status)" :value="status" /></el-select>
            <el-button type="primary" :loading="actionBusy" @click="updateStatus">{{ t('cases.saveChanges') }}</el-button>
          </div>
          <p class="drawer-readonly-hint">{{ t('cases.independentAlarms') }}</p>
          <FormSection v-if="needsClosure" :title="t('cases.closureTitle')" :hint="t('cases.independentAlarms')">
            <FormGrid :columns="2">
              <FormField :label="t('cases.classification')" required full>
                <el-select v-model="closure.classification" :disabled="actionBusy" :aria-label="t('cases.classification')">
                  <el-option v-for="(label, value) in { TRUE_POSITIVE: 'truePositive', FALSE_POSITIVE: 'falsePositive', BENIGN: 'benign', INCONCLUSIVE: 'inconclusive' }" :key="value" :value="value" :label="t('cases.' + label)" />
                </el-select>
              </FormField>
              <FormField v-for="field in ['result', 'reason', 'evidence', 'remainingActions'] as const" :key="field" :label="t('cases.' + field)" required full>
                <el-input v-model="closure[field]" type="textarea" :rows="2" :disabled="actionBusy" :aria-label="t('cases.' + field)" :maxlength="field === 'evidence' ? 8000 : field === 'result' ? 2000 : 4000" />
              </FormField>
            </FormGrid>
          </FormSection>
          <el-divider content-position="left">{{ t('cases.note') }}</el-divider>
          <p class="dialog-hint">{{ t('cases.noteHint') }}</p>
          <el-input v-model="noteContent" type="textarea" :rows="3" :maxlength="8000" :disabled="actionBusy" :aria-label="t('cases.note')" />
          <FormField :label="t('cases.evidenceLinks')">
            <el-input v-model="noteEvidence" type="textarea" :rows="2" :maxlength="6000" :disabled="actionBusy" :aria-label="t('cases.evidenceLinks')" />
          </FormField>
          <el-button :loading="actionBusy" @click="addNote">{{ t('cases.addNote') }}</el-button>
        </template>
        <el-divider content-position="left">{{ t('cases.timeline') }}</el-divider>
        <section class="case-timeline" :aria-label="t('cases.timeline')" :aria-busy="timelineLoading">
          <p v-if="timelineLoading" role="status">{{ t('common.loading') }}</p>
          <ActionFeedback :error="timelineError" />
          <el-button v-if="timelineError" @click="loadTimeline">{{ t('common.retry') }}</el-button>
          <p v-else-if="!timelineLoading && !timeline.length" class="dialog-hint">{{ t('cases.emptyTimeline') }}</p>
          <el-timeline v-else><el-timeline-item v-for="(event, index) in timeline" :key="index" :timestamp="d(event.ts)" placement="top"><div class="case-event-message">{{ event.message }}</div><div v-for="link in evidenceLinks(event.message)" :key="link"><a :href="link" target="_blank" rel="noopener noreferrer">{{ t('cases.evidenceLink') }}: {{ link }}</a></div><div class="case-event-meta">{{ event.type }} · {{ event.source }}</div></el-timeline-item></el-timeline>
          <PagerBar v-if="timelineTotal" v-model:current-page="timelinePage" v-model:page-size="timelineSize" :total="timelineTotal" />
        </section>
          </el-tab-pane>
          <el-tab-pane :label="t('cases.evidenceTab')" name="evidence">
            <el-button v-if="canWrite" :disabled="actionBusy" @click="openAssociation('ATTACH')">{{ t('cases.attachAlarm') }}</el-button>
            <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('cases.linkedRules')" :span="2">
            <ActionFeedback :error="associatedRuleError" />
            <el-button v-if="associatedRuleError" @click="loadRules">{{ t('common.retry') }}</el-button>
            <div v-if="associatedRules.length" class="case-object-list">
              <div v-for="ruleId in associatedRules" :key="ruleId">
                <button v-if="canWrite" type="button" class="case-object-link" @click="openRule(ruleId)">{{ ruleDetails[ruleId]?.name || ruleId }}</button>
                <span v-else>{{ ruleDetails[ruleId]?.name || Object.values(alarmDetails).find(alarm => alarm.ruleId === ruleId)?.ruleName || ruleId }}</span>
                <small class="case-event-meta"> {{ ruleId }} · {{ ruleDetails[ruleId]?.status || '—' }}</small>
              </div>
            </div>
            <span v-else-if="!associatedRuleError">{{ t('cases.noRules') }}</span>
            <p v-if="!canWrite" class="dialog-hint">{{ t('cases.ruleRestricted') }}</p>
            <PagerBar v-if="associatedRuleTotal > associatedRulePageSize" v-model:current-page="associatedRulePage" v-model:page-size="associatedRulePageSize" :total="associatedRuleTotal" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('cases.associatedAlarms')" :span="2">
            <ActionFeedback :error="associatedAlarmError" />
            <p v-if="referenceLoading" role="status">{{ t('common.loading') }}</p>
            <el-button v-if="associatedAlarmError || alarmDetailErrors.length" @click="loadAlarms">{{ t('cases.retryReferences') }}</el-button>
            <div v-if="associatedAlarms.length" class="case-alarm-list">
              <article v-for="alarmId in associatedAlarms" :key="alarmId" class="case-alarm-card">
                <button type="button" class="case-object-link" @click="openAlarm(alarmId)">{{ alarmDetails[alarmId]?.title || alarmDetails[alarmId]?.ruleName || alarmId }}</button>
                <div class="case-event-meta">{{ alarmId }} <template v-if="alarmDetails[alarmId]"> · {{ alarmDetails[alarmId].entity }} · <SevBadge :value="alarmDetails[alarmId].severity" /> · {{ alarmDetails[alarmId].occurredAt ? d(alarmDetails[alarmId].occurredAt) : '—' }} · {{ tOr(t, 'statuses.' + alarmDetails[alarmId].status, alarmDetails[alarmId].status || '—') }}</template></div>
                <p v-if="alarmDetailErrors.includes(alarmId)" class="dialog-hint">{{ t('cases.contextUnavailable') }}</p>
                <el-button v-if="canWrite" link :disabled="actionBusy" @click="openContext('ai', alarmId)">{{ t('cases.ai') }}</el-button>
                <el-button v-if="canWrite" link :disabled="actionBusy" @click="openAssociation('MOVE', alarmId)">{{ t('cases.moveAlarm') }}</el-button>
                <el-button v-if="canWrite" link type="danger" :disabled="actionBusy" @click="openAssociation('DETACH', alarmId)">{{ t('cases.detachAlarm') }}</el-button>
              </article>
            </div>
            <span v-else-if="!associatedAlarmError">{{ t('cases.noAlarms') }}</span>
            <PagerBar v-if="associatedAlarmTotal > associatedAlarmPageSize" v-model:current-page="associatedAlarmPage" v-model:page-size="associatedAlarmPageSize" :total="associatedAlarmTotal" />
          </el-descriptions-item>
            </el-descriptions>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>
    <el-dialog v-model="associationVisible" :before-close="associationGuard.beforeClose" :title="t('cases.associationTitle')" width="640px" :close-on-click-modal="false">
      <ActionFeedback :error="actionError || associationLookupError" />
      <p><strong>{{ t(association.operation === 'ATTACH' ? 'cases.attachAlarm' : association.operation === 'MOVE' ? 'cases.moveAlarm' : 'cases.detachAlarm') }}</strong> · {{ detail?.title }} · {{ detail?.id }}</p>
      <p>{{ t('cases.associationHint') }}</p>
      <el-form :disabled="actionBusy" label-position="top">
        <FormField :label="t('cases.alarmId')" required>
          <el-input v-model="association.alarmId" :disabled="association.operation !== 'ATTACH'" />
          <el-button v-if="association.operation === 'ATTACH' || !associationAlarm" :loading="associationLookupLoading" :disabled="!association.alarmId.trim()" @click="inspectAssociationAlarm">{{ t('cases.inspectAlarm') }}</el-button>
        </FormField>
        <p v-if="associationAlarm">{{ associationAlarm.title || associationAlarm.ruleName }} · {{ associationAlarm.entity }} · {{ associationAlarm.severity }}</p>
        <FormField v-if="association.operation === 'MOVE'" :label="t('cases.targetCase')" required>
          <el-select v-model="association.targetCaseId" filterable remote :remote-method="searchAssociationTargets" :loading="associationLookupLoading">
            <el-option v-for="target in associationTargets" :key="target.id" :value="target.id" :label="`${target.title} · ${target.id}`" :disabled="['RESOLVED', 'CLOSED'].includes(target.status)" />
          </el-select>
          <p v-if="associationTargetTotal > 20">{{ t('cases.narrowTargets') }}</p>
        </FormField>
        <FormField :label="t('cases.associationReason')" required><el-input v-model="association.reason" type="textarea" maxlength="2000" /></FormField>
      </el-form>
      <template #footer><el-button :disabled="actionBusy" @click="associationGuard.cancel">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="actionBusy" :disabled="!associationAlarm || !association.reason.trim() || association.operation === 'MOVE' && !association.targetCaseId" @click="saveAssociation">{{ t('common.confirm') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.case-work-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.case-alarm-list { display: grid; gap: 8px; }
.case-alarm-card { padding: 8px; border: 1px solid var(--el-border-color); border-radius: 6px; overflow-wrap: anywhere; }
.case-event-message { white-space: pre-wrap; overflow-wrap: anywhere; }
.case-timeline a { overflow-wrap: anywhere; }
.case-object-list { overflow-wrap: anywhere; }

.case-timeline :deep(.el-pagination) {
  min-width: 0;
  flex-wrap: wrap;
  justify-content: flex-start;
  row-gap: 8px;
}
</style>
