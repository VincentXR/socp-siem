<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import SoarV2ControlPlane from '../components/soar/SoarV2ControlPlane.vue'
import SoarV2Editor from '../components/soar/SoarV2Editor.vue'
import SoarV2RunInspector from '../components/soar/SoarV2RunInspector.vue'
import type { RunHighlightRow, RunOpenRequest } from '../components/soar/editor/runHighlight'
import {
  approveV2,
  installV2Template,
  listV2Approvals,
  listV2Playbooks,
  listV2Runs,
  listV2Templates,
  rejectV2,
  type SoarV2Approval,
  type SoarV2Playbook,
  type SoarV2Run,
  type SoarV2Template,
} from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()
const route = useRoute()

type SoarTab = 'playbooks' | 'rules' | 'runs' | 'approvals' | 'connections'
const activeTab = ref<SoarTab>('playbooks')

const playbooks = ref<SoarV2Playbook[]>([])
const v2Runs = ref<SoarV2Run[]>([])
const approvals = ref<SoarV2Approval[]>([])
const templates = ref<SoarV2Template[]>([])
const showEditor = ref(false)
const selectedPlaybookId = ref('')
const createRequestToken = ref(0)
const loadError = ref('')
const editorRef = ref<{
  hasUnsavedChanges: boolean
  applyRunHighlights?: (rows: readonly RunHighlightRow[] | null | undefined) => void
} | null>(null)
/** Pending "open this run in the visual editor" hand-off to SoarV2Editor. */
const openRunRequest = ref<RunOpenRequest | null>(null)
const loading = ref(false)
const contextAlarmId = computed(() => typeof route.query.alarmId === 'string' ? route.query.alarmId : '')
// Approval decision state
const approvalFilter = ref<'PENDING' | 'ALL'>('PENDING')
const displayedApprovals = computed(() => {
  if (approvalFilter.value === 'PENDING') {
    return approvals.value.filter(item => item.status === 'PENDING')
  }
  return approvals.value
})

const approvalModal = ref({
  visible: false,
  approvalId: '',
  runId: '',
  actionRef: '',
  isApprove: true,
  reason: '',
  loading: false,
})

function openApprovalModal(row: any, approve: boolean) {
  approvalModal.value = {
    visible: true,
    approvalId: String(row?.id || ''),
    runId: String(row?.runId || ''),
    actionRef: String(row?.actionRef || row?.nodeRunId || ''),
    isApprove: approve,
    reason: '',
    loading: false,
  }
}

async function submitApprovalDecision() {
  if (!approvalModal.value.reason.trim()) return
  approvalModal.value.loading = true
  try {
    if (approvalModal.value.isApprove) {
      await approveV2(approvalModal.value.approvalId, approvalModal.value.reason.trim())
    } else {
      await rejectV2(approvalModal.value.approvalId, approvalModal.value.reason.trim())
    }
    approvalModal.value.visible = false
    await loadPlaybooks()
  } finally {
    approvalModal.value.loading = false
  }
}

async function loadPlaybooks() {
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    const [playbookResult, runResult, approvalResult, templateResult] = await Promise.allSettled([
      listV2Playbooks(0, 100),
      listV2Runs(),
      listV2Approvals(),
      listV2Templates(),
    ])
    if (playbookResult.status === 'fulfilled') playbooks.value = playbookResult.value.items
    v2Runs.value = runResult.status === 'fulfilled' ? runResult.value.items : []
    approvals.value = approvalResult.status === 'fulfilled' ? approvalResult.value : []
    templates.value = templateResult.status === 'fulfilled' ? templateResult.value : []
    const firstFailure = [playbookResult, runResult, approvalResult, templateResult].find(result => result.status === 'rejected')
    if (firstFailure?.status === 'rejected') loadError.value = firstFailure.reason instanceof Error ? firstFailure.reason.message : 'Unable to load SOAR data'
  } finally {
    loading.value = false
  }
}

async function installTemplate(id: string) {
  try {
    const result = await installV2Template(id) as { playbook?: { id?: string } }
    await loadPlaybooks()
    const playbookId = String(result?.playbook?.id || '')
    if (playbookId) openEditorForPlaybook(playbookId)
  } catch (failure) {
    loadError.value = failure instanceof Error ? failure.message : 'Unable to install the playbook template'
  }
}

/**
 * Hiding the editor unmounts it, so warn when it still holds unsaved changes
 * (the dirty state is tracked by SoarV2Editor and surfaced through its ref).
 */
function toggleEditor(): void {
  if (showEditor.value && editorRef.value?.hasUnsavedChanges && !confirm(t('soarV2.discardChanges'))) return
  showEditor.value = !showEditor.value
}

/**
 * Run inspector → visual editor hand-off: reveal the playbook tab with the
 * editor, then let SoarV2Editor load the exact run version and overlay the run
 * node statuses (Slice 4). Unsaved graph edits are discarded after a confirm.
 */
function handleOpenRunInEditor(request: RunOpenRequest): void {
  if (editorRef.value?.hasUnsavedChanges && !confirm(t('soarV2.runHighlightDiscardChanges'))) return
  showEditor.value = true
  activeTab.value = 'playbooks'
  selectedPlaybookId.value = request.playbookId
  openRunRequest.value = { ...request }
}

function openEditorForCreate(): void {
  if (showEditor.value && editorRef.value?.hasUnsavedChanges && !confirm(t('soarV2.discardChanges'))) return
  activeTab.value = 'playbooks'
  selectedPlaybookId.value = ''
  showEditor.value = true
  createRequestToken.value += 1
}

function openEditorForPlaybook(id: string): void {
  if (showEditor.value && editorRef.value?.hasUnsavedChanges && !confirm(t('soarV2.discardChanges'))) return
  activeTab.value = 'playbooks'
  selectedPlaybookId.value = id
  showEditor.value = true
}

function playbookName(id: string): string {
  return playbooks.value.find(playbook => playbook.id === id)?.name || id
}

function lastRun(playbookId: string): SoarV2Run | undefined {
  return v2Runs.value
    .filter(run => run.playbookId === playbookId)
    .sort((left, right) => String(right.createdAt || '').localeCompare(String(left.createdAt || '')))[0]
}

function runTag(status?: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'primary'
  if (status === 'WAITING_APPROVAL' || status === 'WAITING_MANUAL_TASK') return 'warning'
  return 'info'
}

const v2StatusSummary = computed(() => {
  const summary: Record<string, number> = {}
  for (const run of v2Runs.value) summary[run.status] = (summary[run.status] || 0) + 1
  return summary
})

onMounted(loadPlaybooks)
</script>

<template>
  <div class="page-pad view-enter soar-view">
    <PageHeader :eyebrow="t('menuGroup.detectAndResponse')" :title="t('soar.title')" :description="t('soar.description')">
      <template #actions>
        <el-button size="small" :loading="loading" @click="loadPlaybooks">{{ t('common.refresh') }}</el-button>
        <el-button type="primary" size="small" @click="openEditorForCreate">{{ t('soar.createPlaybook') }}</el-button>
      </template>
    </PageHeader>
    <div v-if="contextAlarmId" class="soar-context-banner">
      <span>{{ t('soar.contextFromAlarm') }} <code>{{ contextAlarmId }}</code></span>
      <small>{{ t('soar.contextFromAlarmHint') }}</small>
    </div>

    <el-tabs v-model="activeTab" class="soar-tabs">
      <!-- 14.1 剧本 (Playbooks) -->
      <el-tab-pane :label="t('soar.tabPlaybooks')" name="playbooks">
        <div class="soar-tab-content">
          <!-- Action bar for editor / new playbook -->
          <div class="soar-editor-toggle-bar">
            <el-button :type="showEditor ? 'primary' : 'default'" size="small" @click="toggleEditor">
              {{ showEditor ? t('soarV2.editorHide') : t('soarV2.editorShow') }}
            </el-button>
          </div>

          <!-- Visual Playbook Editor (Collapsible) -->
          <div v-if="showEditor" class="soar-editor-container">
            <SoarV2Editor
              ref="editorRef"
              :initial-playbook-id="selectedPlaybookId"
              :open-run="openRunRequest"
              :create-request="createRequestToken"
              :context-alarm-id="contextAlarmId"
            />
          </div>

          <!-- Golden Templates -->
          <el-card shadow="never" class="soar-card">
            <template #header>
              <div class="soar-card-header">
                <strong>{{ t('soar.templates') }} · {{ t('soar.installDraft') }}</strong>
                <small class="soar-header-hint">{{ t('soarV2.templateHint') }}</small>
              </div>
            </template>
            <el-table :data="templates" size="small" border>
              <el-table-column prop="name" :label="t('common.name')" min-width="190" show-overflow-tooltip />
              <el-table-column prop="description" :label="t('common.description')" min-width="300" show-overflow-tooltip />
              <el-table-column prop="risk" label="Risk" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.risk === 'CRITICAL' ? 'danger' : row.risk === 'HIGH' ? 'warning' : 'info'">{{ row.risk }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.actions')" width="130">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="installTemplate(String(row.id))">{{ t('soar.installDraft') }}</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <!-- Playbook List -->
          <el-card shadow="never" class="soar-card">
            <template #header>
              <div class="soar-card-header">
                <strong>{{ t('soar.playbooks') }}</strong>
                <small class="soar-header-hint">{{ t('soarV2.playbookListHint') }}</small>
              </div>
            </template>
            <el-table :data="playbooks" size="small">
              <el-table-column :label="t('common.name')" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  <div class="soar-playbook-name">{{ row.name }}</div>
                  <code class="soar-secondary-id">{{ row.id }}</code>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.status')" width="120">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : row.status === 'ARCHIVED' ? 'info' : 'warning'">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('soarV2.versions')" width="150">
                <template #default="{ row }">
                  <span v-if="row.latestPublishedVersion">v{{ row.latestPublishedVersion }} · {{ t('soarV2.published') }}</span>
                  <span v-else class="soar-text-muted">{{ t('soarV2.noPublishedVersion') }}</span>
                  <small v-if="row.draftVersion" class="soar-version-note">{{ t('soarV2.draftVersion', { version: row.draftVersion }) }}</small>
                </template>
              </el-table-column>
              <el-table-column :label="t('soarV2.lastRun')" min-width="190">
                <template #default="{ row }">
                  <template v-if="lastRun(row.id)">
                    <el-tag size="small" :type="runTag(lastRun(row.id)?.status)">{{ lastRun(row.id)?.status }}</el-tag>
                    <small class="soar-version-note">{{ lastRun(row.id)?.createdAt || '—' }}</small>
                  </template>
                  <span v-else class="soar-text-muted">{{ t('soarV2.noRuns') }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="updatedAt" :label="t('soarV2.updatedAt')" width="190" show-overflow-tooltip />
              <el-table-column :label="t('common.actions')" width="100" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openEditorForPlaybook(row.id)">{{ t('common.view') }}</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </div>
      </el-tab-pane>

      <!-- 14.2 自动化规则 (Automation Rules) -->
      <el-tab-pane :label="t('soar.tabRules')" name="rules">
        <div class="soar-tab-content">
          <SoarV2ControlPlane section="rules" :hide-tabs="true" />
        </div>
      </el-tab-pane>

      <!-- 14.3 运行 (Runs) -->
      <el-tab-pane :label="t('soar.tabRuns')" name="runs">
        <div class="soar-tab-content">
          <!-- Status summary -->
          <el-card shadow="never" class="soar-card">
            <template #header>
              <div class="soar-card-header">
                <strong>{{ t('soarV2.runSummary') }}</strong>
              </div>
            </template>
            <div class="soar-v2-summary">
              <el-tag v-for="(count, status) in v2StatusSummary" :key="status" size="small" :type="status === 'FAILED' ? 'danger' : status === 'SUCCEEDED' ? 'success' : 'info'">{{ status }}: {{ count }}</el-tag>
              <el-tag type="warning" size="small">{{ t('soar.pendingApprovals') }}: {{ approvals.filter(item => item.status === 'PENDING').length }}</el-tag>
            </div>
            <el-table :data="v2Runs" size="small">
              <el-table-column prop="runId" label="Run ID" min-width="180" show-overflow-tooltip />
              <el-table-column :label="t('soar.playbook')" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">{{ playbookName(row.playbookId) }}</template>
              </el-table-column>
              <el-table-column prop="status" label="Status" width="150">
                <template #default="{ row }">
                  <el-tag size="small" :type="runTag(row.status)">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="triggerType" :label="t('soarV2.triggerType')" width="140" show-overflow-tooltip />
              <el-table-column prop="playbookVersion" label="Version" width="90" />
              <el-table-column prop="createdAt" label="Created" width="210" />
            </el-table>
          </el-card>

          <!-- Interactive Inspector -->
          <SoarV2RunInspector @open-in-editor="handleOpenRunInEditor" />
        </div>
      </el-tab-pane>

      <!-- 14.4 审批与人工任务 (Approvals & Tasks) -->
      <el-tab-pane :label="t('soar.tabApprovals')" name="approvals">
        <div class="soar-tab-content">
          <!-- Approvals Table with filter -->
          <el-card shadow="never" class="soar-card">
            <template #header>
              <div class="soar-card-header">
                <div>
                  <strong>{{ t('soar.tabApprovals') }}</strong>
                  <small class="soar-header-hint">{{ t('soarV2.approvalHint') }}</small>
                </div>
                <div class="soar-header-filter">
                  <el-button size="small" :type="approvalFilter === 'PENDING' ? 'primary' : 'default'" @click="approvalFilter = 'PENDING'">{{ t('soar.pendingApprovals') }} ({{ approvals.filter(item => item.status === 'PENDING').length }})</el-button>
                  <el-button size="small" :type="approvalFilter === 'ALL' ? 'primary' : 'default'" @click="approvalFilter = 'ALL'">{{ t('soar.allApprovals') }} ({{ approvals.length }})</el-button>
                </div>
              </div>
            </template>
            <el-table :data="displayedApprovals" size="small" border class="soar-approval-table">
              <el-table-column prop="runId" label="Run ID" min-width="180" show-overflow-tooltip />
              <el-table-column prop="actionRef" label="Action" min-width="140" show-overflow-tooltip />
              <el-table-column prop="reason" label="Reason" min-width="250" show-overflow-tooltip />
              <el-table-column prop="status" label="Status" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" label="Created At" width="180" />
              <el-table-column :label="t('common.actions')" width="160">
                <template #default="{ row }">
                  <template v-if="row.status === 'PENDING'">
                    <el-button link type="success" size="small" @click="openApprovalModal(row, true)">{{ t('soar.approve') }}</el-button>
                    <el-button link type="danger" size="small" @click="openApprovalModal(row, false)">{{ t('soar.reject') }}</el-button>
                  </template>
                  <span v-else class="soar-text-muted">-</span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <!-- Manual Tasks -->
          <SoarV2ControlPlane section="tasks" :hide-tabs="true" />
        </div>
      </el-tab-pane>

      <!-- 14.5 连接与运维 (Connections & Ops) -->
      <el-tab-pane :label="t('soar.tabConnections')" name="connections">
        <div class="soar-tab-content">
          <SoarV2ControlPlane section="connections-and-ops" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <div v-if="loadError" class="soar-load-error" role="alert">{{ loadError }}</div>

    <!-- Approval Decision Dialog -->
    <el-dialog v-model="approvalModal.visible" :title="approvalModal.isApprove ? t('soar.approve') : t('soar.reject')" width="480px">
      <div class="soar-approval-dialog-body">
        <p><strong>Run:</strong> {{ approvalModal.runId }}</p>
        <p v-if="approvalModal.actionRef"><strong>Action:</strong> {{ approvalModal.actionRef }}</p>
        <el-form label-position="top">
          <el-form-item :label="t('soar.decisionReason')">
            <el-input v-model="approvalModal.reason" type="textarea" :rows="3" :placeholder="t('soarV2.decisionPlaceholder')" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="approvalModal.visible = false">{{ t('common.cancel') }}</el-button>
        <el-button :type="approvalModal.isApprove ? 'success' : 'danger'" :loading="approvalModal.loading" @click="submitApprovalDecision">
          {{ approvalModal.isApprove ? t('soar.approve') : t('soar.reject') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.soar-view { display: flex; flex-direction: column; gap: 16px; }
.soar-tabs { margin-top: 8px; }
.soar-tab-content { display: flex; flex-direction: column; gap: 16px; margin-top: 8px; }
.soar-card { border: 1px solid var(--ns-border); }
.soar-card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.soar-header-hint { color: var(--ns-text-3); font-size: 11px; margin-left: 8px; font-weight: normal; }
.soar-header-filter { display: flex; gap: 8px; }
.soar-editor-toggle-bar { display: flex; justify-content: flex-end; margin-bottom: 4px; }
.soar-editor-container { margin-bottom: 8px; border-radius: 6px; overflow: hidden; }
.soar-action-tag, .soar-result-tag { margin-right: 4px; }
.soar-playbook-name { color: var(--ns-text); font-weight: 650; }
.soar-secondary-id { display: block; margin-top: 3px; color: var(--ns-text-3); font-size: 10px; }
.soar-version-note { display: block; margin-top: 4px; color: var(--ns-text-3); font-size: 10px; }
.soar-load-error { margin-top: 10px; color: var(--ns-danger); font-size: 12px; }
.soar-v2-summary { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.soar-approval-table { margin-top: 8px; }
.soar-approval-dialog-body p { margin-bottom: 8px; font-size: 12px; color: var(--ns-text-2); }
.soar-text-muted { color: var(--ns-text-3); font-size: 11px; }
</style>
