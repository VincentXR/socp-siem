<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/loading/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { vLoading } from 'element-plus/es/components/loading/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { watch, computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import RowActivate from '../components/RowActivate.vue'
import SoarControlPlane from '../components/soar/SoarControlPlane.vue'
import SoarCatalogPager from '../components/soar/SoarCatalogPager.vue'
import SoarEditor from '../components/soar/SoarEditor.vue'
import SoarRunInspector from '../components/soar/SoarRunInspector.vue'
import type { RunHighlightRow, RunOpenRequest } from '../components/soar/editor/runHighlight'
import {
  getRun, listNodes,
  type SoarRun, type SoarNodeRun,
  approve,
  installTemplate as installTemplateApi,
  listApprovalsPage, getApproval,
  listPlaybooks,
  listTemplates,
  reject,
  type SoarApproval,
  type SoarPlaybook,
  type SoarTemplate,
} from '../api'
import { useI18n } from '../composables/useI18n'
import { useConfirm } from '../composables/useConfirm'
import { useFormDialog } from '../composables/useFormDialog'
import { useSoarAccess } from '../composables/useSoarAccess'
import { tOr } from '../utils/i18nLabel'

const { t, d } = useI18n()
function formatTime(value: unknown): string {
  if (!value) return '—'
  try { return d(String(value), 'dateTime') } catch { return String(value) }
}
const { confirmDanger } = useConfirm()
const soarAccess = useSoarAccess()
const canWrite = soarAccess.canEdit
const route = useRoute()
const router = useRouter()
const chooseTemplate = ref(false)
/** Template id whose install request is in flight (guards a double submit). */
const installingTemplateId = ref('')
const templateError = ref('')
const templateGuard = useFormDialog(chooseTemplate, () => null, () => Boolean(installingTemplateId.value))

type SoarTab = 'playbooks' | 'rules' | 'runs' | 'approvals' | 'connections'
const activeTab = ref<SoarTab>('playbooks')

const playbooks = ref<SoarPlaybook[]>([])
const playbookPage = ref(0)
const playbookPageSize = 25
const playbookTotal = ref(0)
const playbookTotalPages = ref(0)
const approvals = ref<SoarApproval[]>([])
const templates = ref<SoarTemplate[]>([])
const showEditor = ref(Boolean(route.meta.editor))
const selectedPlaybookId = ref(String(route.params.playbookId || ''))
const createRequestToken = ref(route.name === 'playbook-new' ? 1 : 0)
const loadError = ref('')
const playbookLoadFailed = ref(false)
const editorRef = ref<{
  hasUnsavedChanges: boolean
  applyRunHighlights?: (rows: readonly RunHighlightRow[] | null | undefined) => void
} | null>(null)
/** Pending "open this run in the visual editor" hand-off to SoarEditor. */
const openRunRequest = ref<RunOpenRequest | null>(null)
const loading = ref(false)
const approvalsLoading = ref(false)
/** Replacement reads cancel stale requests and keep the newest refresh authoritative. */
let approvalController: AbortController | null = null
let disposed = false
const contextCaseId = computed(() => typeof route.query.caseId === 'string' ? route.query.caseId : '')
const returnTo = computed(() => typeof route.query.returnTo === 'string' && /^\/(alarms|cases|assistant)(?:[?#]|$)/.test(route.query.returnTo) ? route.query.returnTo : '')
const contextQuery = computed(() => ({ ...(typeof route.query.runId === 'string' ? { runId: route.query.runId } : {}), ...(returnTo.value ? { returnTo: returnTo.value } : {}), ...(contextAlarmId.value ? { alarmId: contextAlarmId.value } : {}), ...(contextCaseId.value ? { caseId: contextCaseId.value } : {}) }))
const selectedRunId = computed(() => typeof route.query.runId === 'string' ? route.query.runId : '')
function openRun(runId: string): void { void router.push({ name: 'soar', query: { ...contextQuery.value, tab: 'runs', runId } }) }
const contextAlarmId = computed(() => typeof route.query.alarmId === 'string' ? route.query.alarmId : '')
// Approval decision state
const approvalFilter = ref<'PENDING' | 'ALL'>('PENDING')
const approvalPage = ref(0)
const approvalTotal = ref(0)
const approvalTotalPages = ref(0)
watch(approvalFilter, () => { approvalPage.value = 0; void loadApprovals() })
function changeApprovalPage(page: number) { approvalPage.value = page; void loadApprovals() }
/** Approvals own their error banner; a stale list must not grey out the page. */
const approvalsError = ref('')
const displayedApprovals = computed(() => {
  if (approvalFilter.value === 'PENDING') {
    return approvals.value.filter(item => item.status === 'PENDING')
  }
  return approvals.value
})

const approvalContext = ref<{ approval: SoarApproval; run: SoarRun; node?: SoarNodeRun } | null>(null)
const approvalContextLoading = ref(false)
let approvalContextController: AbortController | null = null
const approvalExpired = computed(() => Boolean(approvalContext.value?.approval.expiresAt && Date.parse(approvalContext.value.approval.expiresAt) <= Date.now()))
const approvalModal = ref({
  visible: false,
  approvalId: '',
  runId: '',
  actionRef: '',
  isApprove: true,
  reason: '',
  loading: false,
  error: '',
})

/** useFormDialog owns a boolean ref, so the dialog's own visibility is projected. */
const approvalVisible = computed<boolean>({
  get: () => approvalModal.value.visible,
  set: value => { approvalModal.value.visible = value },
})
const approvalGuard = useFormDialog(approvalVisible, () => approvalModal.value.reason, () => approvalModal.value.loading)
watch(approvalVisible, visible => {
  if (!visible) {
    approvalContextController?.abort()
    if (route.query.approvalId) void router.replace({ query: { ...route.query, approvalId: undefined } })
  }
})
const approvalReasonMissing = computed(() => !approvalModal.value.reason.trim())

async function openApprovalModal(value: unknown, approve: boolean) {
  const row = value as SoarApproval
  if (!soarAccess.canApprove.value || approvalModal.value.loading) return
  approvalModal.value = {
    visible: true,
    approvalId: String(row?.id || ''),
    runId: String(row?.runId || ''),
    actionRef: String(row?.actionRef || row?.nodeRunId || ''),
    isApprove: approve,
    reason: '',
    loading: false,
    error: '',
  }
  approvalContextController?.abort()
  const controller = new AbortController()
  approvalContextController = controller
  approvalContextLoading.value = true
  approvalContext.value = null
  try {
    const [run, nodes] = await Promise.all([getRun(row.runId, { signal: controller.signal }), listNodes(row.runId, { signal: controller.signal })])
    if (disposed || controller.signal.aborted || approvalModal.value.approvalId !== row.id) return
    approvalContext.value = { approval: row, run, node: nodes.find(item => item.id === row.nodeRunId) }
    void router.replace({ query: { ...route.query, tab: 'approvals', approvalId: row.id } })
  } catch (failure) { if (!controller.signal.aborted) approvalModal.value.error = String(failure) }
  finally { if (approvalContextController === controller) approvalContextLoading.value = false }
}

async function submitApprovalDecision() {
  const modal = approvalModal.value
  if (!modal.visible || !soarAccess.canApprove.value || modal.loading || !modal.reason.trim() || !approvalContext.value || approvalContextLoading.value || approvalExpired.value) return
  if (approvalContext.value.approval.id !== modal.approvalId || approvalContext.value.run.runId !== modal.runId) return
  const expiry = approvalContext.value?.approval.expiresAt
  if (expiry && Date.parse(expiry) <= Date.now()) { modal.error = t('analystJourney.approvalExpired'); return }
  modal.loading = true
  modal.error = ''
  try {
    if (modal.isApprove) {
      await approve(modal.approvalId, modal.reason.trim())
    } else {
      await reject(modal.approvalId, modal.reason.trim())
    }
    approvalVisible.value = false
    ElMessage.success(t('soar.approvalRecorded'))
    await loadApprovals()
  } catch (failure) { modal.error = String(failure) } finally {
    modal.loading = false
  }
}

let baseController: AbortController | null = null

/**
 * A reload while another one is in flight must supersede it, not be dropped:
 * the previous reads are aborted and replaced immediately so the newest request
 * owns the visible data.
 */
async function loadBaseData(): Promise<void> {
  if (disposed) return
  baseController?.abort()
  const controller = new AbortController()
  baseController = controller
  loading.value = true
  loadError.value = ''
  try {
    const [playbookResult, templateResult] = await Promise.allSettled([
      listPlaybooks(playbookPage.value, playbookPageSize, { signal: controller.signal }),
      listTemplates({ signal: controller.signal }),
    ])
    if (disposed || controller.signal.aborted || baseController !== controller) return
    playbookLoadFailed.value = playbookResult.status === 'rejected'
    if (playbookResult.status === 'fulfilled') {
      const page = playbookResult.value
      playbookTotal.value = page.total
      playbookTotalPages.value = page.totalPages ?? Math.ceil(page.total / playbookPageSize)
      const lastPage = Math.max(0, playbookTotalPages.value - 1)
      if (playbookPage.value > lastPage) {
        playbookPage.value = lastPage
        await loadBaseData()
        return
      }
      playbooks.value = page.items
    }
    if (templateResult.status === 'fulfilled') templates.value = templateResult.value
    const firstFailure = [playbookResult, templateResult].find(result => result.status === 'rejected')
    if (firstFailure?.status === 'rejected') loadError.value = firstFailure.reason instanceof Error ? firstFailure.reason.message : 'Unable to load SOAR data'
  } finally {
    if (baseController === controller) { baseController = null; loading.value = false }
  }
}

async function loadApprovals(): Promise<void> {
  if (disposed) return
  approvalController?.abort()
  const controller = new AbortController()
  approvalController = controller
  approvalsLoading.value = true
  approvalsError.value = ''
  approvals.value = []
  try {
    const result = await listApprovalsPage(approvalPage.value, 25, approvalFilter.value === 'PENDING' ? 'PENDING' : undefined, { signal: controller.signal })
    if (disposed || controller.signal.aborted || approvalController !== controller) return
    approvals.value = result.items
    approvalTotal.value = result.total
    approvalTotalPages.value = result.totalPages ?? Math.ceil(result.total / 25)
    if (approvalPage.value > 0 && approvalPage.value >= approvalTotalPages.value) {
      approvalPage.value = Math.max(0, approvalTotalPages.value - 1); void loadApprovals(); return
    }
    const selectedId = typeof route.query.approvalId === 'string' ? route.query.approvalId : ''
    if (selectedId && !approvalVisible.value) {
      const selected = result.items.find(item => item.id === selectedId) ?? await getApproval(selectedId, { signal: controller.signal })
      if (!disposed && !controller.signal.aborted && selected.status === 'PENDING') void openApprovalModal(selected, true)
    }
  } catch (failure) {
    if (!disposed && !controller.signal.aborted && approvalController === controller) {
      approvalsError.value = failure instanceof Error ? failure.message : 'Unable to load SOAR approvals'
    }
  } finally {
    if (approvalController === controller) { approvalController = null; approvalsLoading.value = false }
  }
}

async function refreshSoarPage(): Promise<void> {
  await Promise.all([
    loadBaseData(),
    activeTab.value === 'approvals' ? loadApprovals() : Promise.resolve(),
  ])
}

async function installTemplate(id: string) {
  if (!canWrite.value || installingTemplateId.value || disposed) return
  installingTemplateId.value = id
  templateError.value = ''
  try {
    const result = await installTemplateApi(id) as { playbook?: { id?: string } }
    if (disposed) return
    await loadBaseData()
    if (disposed) return
    const playbookId = String(result?.playbook?.id || '')
    chooseTemplate.value = false
    ElMessage.success(t('soar.createdDraft'))
    if (playbookId) await openEditorForPlaybook(playbookId)
  } catch (failure) {
    if (!disposed) templateError.value = String(failure)
  } finally {
    installingTemplateId.value = ''
  }
}

function discardEditorChanges(): Promise<boolean> {
  return confirmDanger(t('soar.discardChanges'), { title: t('forms.unsavedTitle') })
}

/**
 * Hiding the editor unmounts it, so warn when it still holds unsaved changes
 * (the dirty state is tracked by SoarEditor and surfaced through its ref).
 */
async function toggleEditor(): Promise<void> {
  if (showEditor.value && editorRef.value?.hasUnsavedChanges && !(await discardEditorChanges())) return
  showEditor.value = !showEditor.value
}

/**
 * Run inspector → visual editor hand-off: reveal the playbook tab with the
 * editor, then let SoarEditor load the exact run version and overlay the run
 * node statuses (Slice 4). Unsaved graph edits are discarded after a confirm.
 */
async function handleOpenRunInEditor(request: RunOpenRequest): Promise<void> {
  if (editorRef.value?.hasUnsavedChanges && !(await confirmDanger(t('soar.runHighlightDiscardChanges'), { title: t('forms.unsavedTitle') }))) return
  selectedPlaybookId.value = request.playbookId
  openRunRequest.value = { ...request }
  // Complete the route transition before changing the tab. Otherwise the
  // tab-query watcher can race this push with a replace back to `/soar`.
  await router.push({ name: 'playbook-edit', params: { playbookId: request.playbookId }, query: { ...contextQuery.value, runId: request.runId } })
  activeTab.value = 'playbooks'
  showEditor.value = true
}

async function openEditorForCreate(): Promise<void> {
  if (installingTemplateId.value) return
  if (showEditor.value && editorRef.value?.hasUnsavedChanges && !(await discardEditorChanges())) return
  selectedPlaybookId.value = ''
  chooseTemplate.value = false
  await router.push({ name: 'playbook-new', query: contextQuery.value })
  activeTab.value = 'playbooks'
  showEditor.value = true
}

async function openEditorForPlaybook(id: string): Promise<void> {
  if (showEditor.value && editorRef.value?.hasUnsavedChanges && !(await discardEditorChanges())) return
  selectedPlaybookId.value = id
  chooseTemplate.value = false
  await router.push({ name: 'playbook-edit', params: { playbookId: id }, query: contextQuery.value })
  activeTab.value = 'playbooks'
  showEditor.value = true
}

function changePlaybookPage(page: number): void {
  if (page === playbookPage.value) return
  playbookPage.value = page
  playbooks.value = []
  void loadBaseData()
}

function runTag(status?: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'primary'
  if (status === 'WAITING_APPROVAL' || status === 'WAITING_MANUAL_TASK') return 'warning'
  return 'info'
}

function statusLabel(status: string): string {
  return tOr(t, 'soar.status.' + status, status)
}

watch(() => route.fullPath, () => {
  showEditor.value = Boolean(route.meta.editor)
  selectedPlaybookId.value = String(route.params.playbookId || '')
  createRequestToken.value = route.name === 'playbook-new' ? createRequestToken.value + 1 : 0
})
// Deep-link contract: /soar?tab=<pane>&filter=PENDING|ALL is shareable.
const SOAR_TABS: readonly SoarTab[] = ['playbooks', 'rules', 'runs', 'approvals', 'connections']
const contextDefaultTab = computed<SoarTab>(() => contextAlarmId.value || contextCaseId.value || selectedRunId.value ? 'runs' : 'playbooks')
let applyingRouteQuery = false
function readRouteQuery(): void {
  const tab = typeof route.query.tab === 'string' ? route.query.tab : ''
  activeTab.value = SOAR_TABS.includes(tab as SoarTab) ? tab as SoarTab : contextDefaultTab.value
  approvalFilter.value = route.query.filter === 'ALL' ? 'ALL' : 'PENDING'
}
function writeRouteQuery(): void {
  if (applyingRouteQuery || route.name !== 'soar') return
  // Carry over unrelated params (alarmId context deep-links included); only
  // tab/filter are owned here, and defaults stay out of the URL.
  const query: Record<string, string> = {}
  for (const [key, value] of Object.entries(route.query)) {
    if (key !== 'tab' && key !== 'filter' && typeof value === 'string') query[key] = value
  }
  // Preserve an explicit return to the catalog when retained run/alarm/case
  // context would otherwise infer Runs as soon as this URL is read back.
  if (activeTab.value !== 'playbooks' || contextDefaultTab.value !== 'playbooks') query.tab = activeTab.value
  if (approvalFilter.value !== 'PENDING') query.filter = approvalFilter.value
  void router.replace({ query })
}
readRouteQuery()
watch([activeTab, approvalFilter], writeRouteQuery)
watch(() => route.query, () => {
  applyingRouteQuery = true
  readRouteQuery()
  applyingRouteQuery = false
}, { deep: true })
watch(activeTab, tab => {
  if (tab === 'approvals') void loadApprovals()
})
const canLeaveEditor = async (): Promise<boolean> => !editorRef.value?.hasUnsavedChanges || await discardEditorChanges()
// Catalog/create/edit reuse this view across distinct lazy route records.
// Scope the guard to this mounted editor instead of retaining a guard on a
// previously reused record, whose component ref may already have been cleared.
const removeEditorGuard = router.beforeEach((to, from) => to.path === from.path || canLeaveEditor())
onUnmounted(removeEditorGuard)
onMounted(() => { void refreshSoarPage() })
onUnmounted(() => { disposed = true; baseController?.abort(); approvalController?.abort(); approvalContextController?.abort() })
</script>

<template>
  <div class="page-pad view-enter soar-view">
    <PageHeader :eyebrow="t('menuGroup.detectAndResponse')" :title="t('soar.title')" :description="t('soar.description')">
      <template #actions>
        <el-button :loading="loading || approvalsLoading" @click="refreshSoarPage">{{ t('common.refresh') }}</el-button>
        <el-button v-if="canWrite && !showEditor" type="primary" @click="chooseTemplate = true">{{ t('soar.createPlaybook') }}</el-button>
      </template>
    </PageHeader>
    <div v-if="loadError" class="soar-load-error" role="alert">{{ loadError }}</div>
    <div v-if="!canWrite" class="page-readonly-hint">{{ t('soar.readOnly') }}</div>
    <el-button v-if="returnTo" class="soar-back" text :aria-label="t('analystJourney.returnToInvestigation')" @click="router.push(returnTo)"><span aria-hidden="true">←</span> {{ t('analystJourney.returnToInvestigation') }}</el-button>
    <div v-if="contextAlarmId || contextCaseId" class="soar-context-banner">
      <span v-if="contextAlarmId">{{ t('soar.contextFromAlarm') }} <code>{{ contextAlarmId }}</code></span>
      <span v-if="contextCaseId">{{ t('cases.caseId') }} <code>{{ contextCaseId }}</code></span>
      <small>{{ t(contextAlarmId ? (contextCaseId ? 'soar.contextFromAlarmAndCaseHint' : 'soar.contextFromAlarmHint') : 'soar.contextFromCaseHint') }}</small>
    </div>

    <el-button v-if="showEditor" class="soar-back" text :aria-label="t('soar.backToPlaybooks')" @click="router.push({ name: 'soar', query: contextQuery })"><span aria-hidden="true">←</span> {{ t('soar.backToPlaybooks') }}</el-button>
    <SoarEditor v-if="showEditor" ref="editorRef" :initial-playbook-id="selectedPlaybookId" :open-run="openRunRequest" :create-request="createRequestToken" :context-alarm-id="contextAlarmId" :context-case-id="contextCaseId" @open-run="openRun" @automate="versionId => router.push({ name: 'soar', query: { ...contextQuery, tab: 'rules', versionId } })" :can-write="canWrite" :can-publish="soarAccess.canPublish.value" :can-execute="soarAccess.canExecute.value" @created="id => router.replace({ name: 'playbook-edit', params: { playbookId: id }, query: contextQuery })" />
    <el-dialog v-model="chooseTemplate" :before-close="templateGuard.beforeClose" :title="t('forms.selectTemplate')" width="640px" :close-on-click-modal="false">
      <p class="soar-template-intro">{{ t('soar.templateChoiceHint') }}</p>
      <el-button v-if="canWrite" type="primary" :disabled="Boolean(installingTemplateId)" @click="openEditorForCreate">{{ t('soar.createBlankPlaybook') }}</el-button>
      <div v-for="template in templates" :key="template.id" class="template-choice"><div><b>{{ template.name }}</b><p>{{ template.description }}</p></div><el-button v-if="canWrite" :loading="installingTemplateId === String(template.id)" :disabled="Boolean(installingTemplateId)" @click="installTemplate(String(template.id))">{{ t('soar.installDraft') }}</el-button></div>
      <div v-if="templateError" class="soar-load-error" role="alert">{{ templateError }}</div>
      <template #footer>
        <el-button :disabled="Boolean(installingTemplateId)" @click="templateGuard.cancel">{{ t('common.cancel') }}</el-button>
      </template>
    </el-dialog>
    <el-tabs v-if="!showEditor" v-model="activeTab" class="soar-tabs">
      <!-- 14.1 剧本 (Playbooks) -->
      <el-tab-pane :label="t('soar.tabPlaybooks')" name="playbooks" lazy>
        <div class="soar-tab-content">
          <!-- Playbook List -->
          <el-card shadow="never" class="soar-card">
            <template #header>
              <div class="soar-card-header">
                <strong>{{ t('soar.playbooks') }}</strong>
                <small class="soar-header-hint">{{ t('soar.playbookListHint') }}</small>
              </div>
            </template>
            <el-table v-loading="loading" :data="playbooks" size="small" :empty-text="t(playbookLoadFailed ? 'experience.unknownData' : 'common.empty')">
              <el-table-column :label="t('common.name')" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  <RowActivate class="soar-playbook-name" :aria-label="t('soar.openPlaybook', { name: row.name })" @activate="openEditorForPlaybook(row.id)">{{ row.name }}</RowActivate>
                  <code class="soar-secondary-id">{{ row.id }}</code>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.status')" width="120">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : row.status === 'ARCHIVED' ? 'info' : 'warning'">{{ statusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('soar.versions')" width="150">
                <template #default="{ row }">
                  <span v-if="row.latestPublishedVersion">{{ t('soar.revisionLabel', { version: row.latestPublishedVersion }) }} · {{ t('soar.published') }}</span>
                  <span v-else class="soar-text-muted">{{ t('soar.noPublishedVersion') }}</span>
                  <small v-if="row.draftVersion" class="soar-version-note">{{ t('soar.draftVersion', { version: row.draftVersion }) }}</small>
                </template>
              </el-table-column>
              <el-table-column :label="t('soar.lastRun')" min-width="190">
                <template #default="{ row }">
                  <template v-if="row.latestRun">
                    <RowActivate class="soar-latest-run" :aria-label="t('soar.inspectLatestRun', { name: row.name })" @activate="openRun(row.latestRun.runId)"><el-tag size="small" :type="runTag(row.latestRun.status)">{{ statusLabel(row.latestRun.status || '') }}</el-tag></RowActivate>
                    <small class="soar-version-note"><time :datetime="row.latestRun.createdAt">{{ formatTime(row.latestRun.createdAt) }}</time></small>
                  </template>
                  <span v-else-if="row.latestRun === null" class="soar-text-muted">{{ t('soar.noRetainedRuns') }}</span>
                  <span v-else class="soar-text-muted">—</span>
                </template>
              </el-table-column>
              <el-table-column prop="updatedAt" :label="t('soar.updatedAt')" width="184" show-overflow-tooltip><template #default="{ row }">{{ formatTime(row.updatedAt) }}</template></el-table-column>
              <el-table-column :label="t('common.actions')" width="132" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openEditorForPlaybook(row.id)">{{ t(canWrite ? 'soar.editPlaybook' : 'soar.viewPlaybook') }}</el-button>
                </template>
              </el-table-column>
            </el-table>
            <SoarCatalogPager :page="playbookPage" :total="playbookTotal" :total-pages="playbookTotalPages" :loading="loading" :label="t('soar.playbooks')" @change="changePlaybookPage" />
          </el-card>
        </div>
      </el-tab-pane>

      <!-- 14.2 自动化规则 (Automation Rules) -->
      <el-tab-pane :label="t('soar.tabRules')" name="rules" lazy>
        <div class="soar-tab-content">
          <SoarControlPlane :initial-version-id="typeof route.query.versionId === 'string' ? route.query.versionId : undefined" section="rules" :hide-tabs="true" :can-write="canWrite" :can-publish="soarAccess.canPublish.value" :can-view-connections="soarAccess.canViewConnections.value" :can-operate="soarAccess.canOperate.value" />
        </div>
      </el-tab-pane>

      <!-- 14.3 运行 (Runs) -->
      <el-tab-pane :label="t('soar.tabRuns')" name="runs" lazy>
        <div class="soar-tab-content">
          <!-- Interactive Inspector -->
          <SoarRunInspector :initial-run-id="selectedRunId" :context-alarm-id="contextAlarmId" :context-case-id="contextCaseId" @select-run="openRun" :active="activeTab === 'runs'" :can-write="canWrite" :can-execute="soarAccess.canExecute.value" :can-operate="soarAccess.canOperate.value" @open-in-editor="handleOpenRunInEditor" />
        </div>
      </el-tab-pane>

      <!-- 14.4 审批与人工任务 (Approvals & Tasks) -->
      <el-tab-pane :label="t('soar.tabApprovals')" name="approvals" lazy>
        <div class="soar-tab-content">
          <!-- Approvals Table with filter -->
          <el-card shadow="never" class="soar-card">
            <template #header>
              <div class="soar-card-header">
                <div>
                  <strong>{{ t('soar.tabApprovals') }}</strong>
                  <small class="soar-header-hint">{{ t('soar.approvalHint') }}</small>
                </div>
                <div class="soar-header-filter">
                  <el-button size="small" :type="approvalFilter === 'PENDING' ? 'primary' : 'default'" @click="approvalFilter = 'PENDING'">{{ t('soar.pendingApprovals') }}</el-button>
                  <el-button size="small" :type="approvalFilter === 'ALL' ? 'primary' : 'default'" @click="approvalFilter = 'ALL'">{{ t('soar.allApprovals') }}</el-button>
                </div>
              </div>
            </template>
            <div v-if="approvalsError" class="soar-load-error" role="alert">{{ approvalsError }}</div>
            <el-table v-loading="approvalsLoading" :data="displayedApprovals" size="small" border class="soar-approval-table" :empty-text="t('common.empty')">
              <el-table-column prop="runId" :label="t('soar.runId')" min-width="180"><template #default="{ row }"><el-button link @click="openRun(row.runId)">{{ row.runId }}</el-button></template></el-table-column>
              <el-table-column prop="actionRef" :label="t('soar.action')" min-width="140" show-overflow-tooltip />
              <el-table-column prop="reason" :label="t('soar.reason')" min-width="250" show-overflow-tooltip />
              <el-table-column prop="status" :label="t('common.status')" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'">{{ statusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" :label="t('soar.createdAt')" width="184"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
              <el-table-column :label="t('common.actions')" width="160">
                <template #default="{ row }">
                  <template v-if="row.status === 'PENDING'">
                    <el-button v-if="soarAccess.canApprove.value" link type="success" size="small" @click="openApprovalModal(row, true)">{{ t('soar.approve') }}</el-button>
                    <el-button v-if="soarAccess.canApprove.value" link type="danger" size="small" @click="openApprovalModal(row, false)">{{ t('soar.reject') }}</el-button>
                  </template>
                  <span v-else class="soar-text-muted">-</span>
                </template>
              </el-table-column>
            </el-table>
            <SoarCatalogPager :page="approvalPage" :total="approvalTotal" :total-pages="approvalTotalPages" :loading="approvalsLoading" :label="t('soar.tabApprovals')" @change="changeApprovalPage" />
          </el-card>

          <!-- Manual Tasks -->
          <SoarControlPlane section="tasks" :hide-tabs="true" :can-write="canWrite" :can-complete-tasks="soarAccess.canCompleteTasks.value" :can-view-connections="soarAccess.canViewConnections.value" :can-operate="soarAccess.canOperate.value" />
        </div>
      </el-tab-pane>

      <!-- 14.5 连接与运维 (Connections & Ops) -->
      <el-tab-pane :label="t('soar.tabConnections')" name="connections" lazy>
        <div class="soar-tab-content">
          <SoarControlPlane section="connections-and-ops" :can-write="soarAccess.canManageConnections.value" :can-view-connections="soarAccess.canViewConnections.value" :can-manage-connections="soarAccess.canManageConnections.value" :can-operate="soarAccess.canOperate.value" />
        </div>
      </el-tab-pane>
    </el-tabs>


    <!-- Approval Decision Dialog -->
    <el-dialog
      v-model="approvalVisible"
      :before-close="approvalGuard.beforeClose"
      :title="approvalModal.isApprove ? t('soar.approve') : t('soar.reject')"
      width="520px"
      :close-on-click-modal="false"
    >
      <div class="soar-approval-dialog-body">
        <p><strong>{{ t('soar.runId') }}:</strong> {{ approvalModal.runId }}</p>
        <p v-if="approvalModal.actionRef"><strong>{{ t('soar.action') }}:</strong> {{ approvalModal.actionRef }}</p>
        <div v-if="approvalContextLoading" role="status">{{ t('analystJourney.loadingApproval') }}</div>
        <div v-else-if="approvalContext">
          <p>{{ t('analystJourney.approvalRequester', { requester: approvalContext.approval.requestedBy ?? '', expires: approvalContext.approval.expiresAt || t('analystJourney.noExpiry') }) }} </p>
          <p>{{ t('analystJourney.approvalOrigin', { origin: JSON.stringify(approvalContext.run.subject) ?? '', revision: approvalContext.run.playbookVersion ?? '', hash: approvalContext.run.definitionHash ?? '' }) }} </p>
          <el-button v-if="approvalContext.run.originAlarmId" link @click="router.push({ name: 'alarms', query: { alarmId: approvalContext.run.originAlarmId } })">{{ t('analystJourney.openAlarm') }}</el-button>
          <el-button v-if="approvalContext.run.originCaseId" link @click="router.push({ name: 'case', query: { caseId: approvalContext.run.originCaseId } })">{{ t('analystJourney.openCase') }}</el-button>
          <h4>{{ t('analystJourney.resolvedTarget') }}</h4><pre>{{ JSON.stringify(approvalContext.approval.targetSnapshot, null, 2) }}</pre>
          <h4>{{ t('analystJourney.resolvedInputs') }}</h4><pre>{{ JSON.stringify(approvalContext.node?.input ?? approvalContext.approval.targetSnapshot, null, 2) }}</pre><pre>{{ JSON.stringify(approvalContext.approval.approvalPolicy, null, 2) }}</pre>
          <p>{{ t('analystJourney.inputFingerprint', { hash: approvalContext.approval.inputHash ?? '' }) }} </p>
          <el-button link @click="approvalVisible = false; openRun(approvalContext.run.runId)">{{ t('analystJourney.inspectRun') }}</el-button>
          <p v-if="approvalExpired" role="alert">{{ t('analystJourney.approvalExpired') }}</p>
        </div>
        <el-form label-position="top">
          <el-form-item :label="t('soar.decisionReason')" required :error="approvalModal.error">
            <el-input v-model="approvalModal.reason" type="textarea" :rows="3" :placeholder="t('soar.decisionPlaceholder')" />
            <p v-if="approvalReasonMissing" class="soar-approval-hint">{{ t('forms.fieldRequired', { field: t('soar.decisionReason') }) }}</p>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="approvalGuard.cancel">{{ t('common.cancel') }}</el-button>
        <el-button
          v-if="soarAccess.canApprove.value"
          :type="approvalModal.isApprove ? 'success' : 'danger'"
          :loading="approvalModal.loading"
          :disabled="approvalReasonMissing || approvalContextLoading || !approvalContext || approvalExpired"
          @click="submitApprovalDecision"
        >
          {{ approvalModal.isApprove ? t('soar.approve') : t('soar.reject') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.soar-approval-dialog-body pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 200px; overflow: auto; }
.soar-view { display: flex; flex-direction: column; gap: 16px; }
.soar-tabs { margin-top: 8px; }
.soar-tab-content { display: flex; flex-direction: column; gap: 16px; margin-top: 8px; }
.soar-card { border: 1px solid var(--ns-border); }
.soar-card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.soar-header-hint { color: var(--ns-text-3); font-size: 12px; font-weight: normal; line-height: 1.5; }
.soar-header-filter { display: flex; gap: 8px; }
.soar-editor-toggle-bar { display: flex; justify-content: flex-end; margin-bottom: 4px; }
.soar-editor-container { margin-bottom: 8px; border-radius: 6px; overflow: hidden; }
.soar-action-tag, .soar-result-tag { margin-right: 4px; }
.soar-playbook-name { color: var(--ns-text); font-weight: 650; }
.soar-secondary-id { display: block; margin-top: 3px; color: var(--ns-text-3); font-size: 12px; }
.soar-version-note { display: block; margin-top: 4px; color: var(--ns-text-3); font-size: 12px; }
.soar-load-error { margin-top: 10px; color: var(--ns-danger); font-size: 12px; }
.soar-summary { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.soar-approval-table { margin-top: 8px; }
.soar-approval-dialog-body pre { max-height: 220px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
.soar-approval-dialog-body p { margin-bottom: 8px; font-size: 12px; color: var(--ns-text-2); }
.soar-approval-dialog-body p.soar-approval-hint { margin: 4px 0 0; color: var(--ns-warning); }
.soar-text-muted { color: var(--ns-text-3); font-size: 12px; }
.soar-back { align-self: flex-start; }
.soar-template-intro { margin: 0 0 16px; color: var(--ns-text-2); font-size: 14px; line-height: 1.6; }
.template-choice { gap: 20px; margin-top: 12px; padding: 16px; border: 1px solid var(--ns-border); border-radius: 10px; background: var(--ns-bg-subtle); }
.template-choice > div { min-width: 0; }
.template-choice p { font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.template-choice :deep(.el-button) { flex-shrink: 0; }
.soar-context-banner { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 20px; padding: 14px 16px; border: 1px solid color-mix(in srgb, var(--ns-accent) 20%, var(--ns-border)); border-left: 3px solid var(--ns-accent); border-radius: 10px; background: color-mix(in srgb, var(--ns-accent) 4%, var(--ns-surface)); font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.soar-context-banner small { flex-basis: 100%; color: var(--ns-text-2); font-size: 12px; }
.soar-card-header > strong { font-size: 14px; }
.soar-secondary-id { font-variant-numeric: tabular-nums; }
.soar-playbook-name:hover { color: var(--ns-accent-fg); }
.soar-approval-dialog-body pre { padding: 14px 16px; border: 1px solid var(--ns-border); border-radius: 8px; background: var(--ns-bg-subtle); font-size: 12px; line-height: 1.7; }
@media (max-width: 720px) {
  .soar-card-header { align-items: flex-start; flex-direction: column; gap: 4px; }
  .template-choice { align-items: flex-start; flex-direction: column; gap: 8px; }
}
</style>
