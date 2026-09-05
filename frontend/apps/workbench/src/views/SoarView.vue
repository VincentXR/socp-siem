<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import PagerBar from '../components/PagerBar.vue'
import SoarV2ControlPlane from '../components/soar/SoarV2ControlPlane.vue'
import SoarV2Editor from '../components/soar/SoarV2Editor.vue'
import SoarV2RunInspector from '../components/soar/SoarV2RunInspector.vue'
import {
  approveV2,
  createPlaybook,
  deletePlaybook,
  installV2Template,
  listPlaybookExecutions,
  listPlaybooks,
  listV2Approvals,
  listV2Runs,
  listV2Templates,
  rejectV2,
  togglePlaybook,
  type Playbook,
  type PlaybookExecution,
  type SoarV2Approval,
  type SoarV2Run,
  type SoarV2Template,
} from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()

type SoarTab = 'playbooks' | 'rules' | 'runs' | 'approvals' | 'connections'
const activeTab = ref<SoarTab>('playbooks')

const playbooks = ref<Playbook[]>([])
const executions = ref<PlaybookExecution[]>([])
const v2Runs = ref<SoarV2Run[]>([])
const approvals = ref<SoarV2Approval[]>([])
const templates = ref<SoarV2Template[]>([])
const page = ref(1)
const size = ref(10)
const dialogVisible = ref(false)
const showEditor = ref(false)
const loading = ref(false)
const form = ref({ name: '', trigger: '', actions: '', enabled: true })
const executionsPaged = computed(() => executions.value.slice((page.value - 1) * size.value, page.value * size.value))

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
    reason: approve ? 'Verified risk parameters; approved for execution.' : 'Security risk detected; rejected.',
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
  try {
    const [playbookResult, executionResult, runResult, approvalResult, templateResult] = await Promise.allSettled([
      listPlaybooks(),
      listPlaybookExecutions(),
      listV2Runs(),
      listV2Approvals(),
      listV2Templates(),
    ])
    if (playbookResult.status === 'fulfilled') playbooks.value = playbookResult.value
    executions.value = executionResult.status === 'fulfilled' ? executionResult.value : []
    v2Runs.value = runResult.status === 'fulfilled' ? runResult.value.items : []
    approvals.value = approvalResult.status === 'fulfilled' ? approvalResult.value : []
    templates.value = templateResult.status === 'fulfilled' ? templateResult.value : []
  } finally {
    loading.value = false
  }
}

async function installTemplate(id: string) {
  await installV2Template(id)
  await loadPlaybooks()
}

async function addPlaybook() {
  await createPlaybook({
    name: form.value.name,
    trigger: form.value.trigger,
    actions: form.value.actions.split(/[,，\n]/).map(action => action.trim()).filter(Boolean),
    enabled: form.value.enabled,
  })
  dialogVisible.value = false
  form.value = { name: '', trigger: '', actions: '', enabled: true }
  await loadPlaybooks()
}

async function removePlaybook(id: string) {
  if (!confirm(t('soar.confirmDelete'))) return
  await deletePlaybook(id)
  await loadPlaybooks()
}

async function toggle(id: string) {
  await togglePlaybook(id)
  await loadPlaybooks()
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
    <PageHeader :title="t('soar.title')" :description="t('soar.description')">
      <template #actions>
        <el-button size="small" :loading="loading" @click="loadPlaybooks">{{ t('common.refresh') }}</el-button>
        <el-button type="primary" size="small" @click="dialogVisible = true">{{ t('soar.createPlaybook') }}</el-button>
      </template>
    </PageHeader>

    <el-tabs v-model="activeTab" class="soar-tabs">
      <!-- 14.1 剧本 (Playbooks) -->
      <el-tab-pane :label="t('soar.tabPlaybooks')" name="playbooks">
        <div class="soar-tab-content">
          <!-- Action bar for editor / new playbook -->
          <div class="soar-editor-toggle-bar">
            <el-button :type="showEditor ? 'primary' : 'default'" size="small" @click="showEditor = !showEditor">
              {{ showEditor ? '收起编排设计器 (Hide Editor)' : '展开可视化编排设计器 (Open Visual Editor)' }}
            </el-button>
          </div>

          <!-- Visual Playbook Editor (Collapsible) -->
          <div v-if="showEditor" class="soar-editor-container">
            <SoarV2Editor />
          </div>

          <!-- Golden Templates -->
          <el-card shadow="never" class="soar-card">
            <template #header>
              <div class="soar-card-header">
                <strong>{{ t('soar.templates') }} · {{ t('soar.installDraft') }}</strong>
                <small class="soar-header-hint">基于真实响应场景的最佳实践模板，一键导入为可编辑草稿</small>
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
              </div>
            </template>
            <el-table :data="playbooks" size="small" border>
              <el-table-column prop="name" :label="t('common.name')" min-width="140" show-overflow-tooltip />
              <el-table-column prop="trigger" :label="t('soar.trigger')" min-width="200" show-overflow-tooltip />
              <el-table-column :label="t('soar.actionChain')" min-width="260">
                <template #default="{ row }">
                  <el-tag v-for="action in row.actions" :key="action" size="small" class="soar-action-tag">{{ action }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.status')" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.enabled') : t('common.disabled') }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.actions')" width="130">
                <template #default="{ row }">
                  <el-button link size="small" @click="toggle(row.id)">{{ row.enabled ? t('common.disable') : t('common.enable') }}</el-button>
                  <el-button link type="danger" size="small" @click="removePlaybook(row.id)">{{ t('common.delete') }}</el-button>
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
                <strong>SOAR V2 · 运行概览 (Run Summary)</strong>
              </div>
            </template>
            <div class="soar-v2-summary">
              <el-tag v-for="(count, status) in v2StatusSummary" :key="status" size="small" :type="status === 'FAILED' ? 'danger' : status === 'SUCCEEDED' ? 'success' : 'info'">{{ status }}: {{ count }}</el-tag>
              <el-tag type="warning" size="small">{{ t('soar.pendingApprovals') }}: {{ approvals.filter(item => item.status === 'PENDING').length }}</el-tag>
            </div>
            <el-table :data="v2Runs" size="small" border>
              <el-table-column prop="runId" label="Run ID" min-width="180" show-overflow-tooltip />
              <el-table-column prop="status" label="Status" width="150">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'SUCCEEDED' ? 'success' : row.status === 'FAILED' ? 'danger' : row.status === 'RUNNING' ? 'primary' : 'warning'">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="playbookVersion" label="Version" width="90" />
              <el-table-column prop="createdAt" label="Created" width="210" />
            </el-table>
          </el-card>

          <!-- Interactive Inspector -->
          <SoarV2RunInspector />

          <!-- Legacy execution history -->
          <el-card shadow="never" class="soar-card">
            <template #header>{{ t('soar.executionHistory', { count: executions.length }) }}</template>
            <el-table :data="executionsPaged" size="small" border>
              <el-table-column prop="ts" :label="t('common.timestamp')" width="200" />
              <el-table-column prop="playbook" :label="t('soar.playbook')" min-width="140" show-overflow-tooltip />
              <el-table-column prop="trigger" :label="t('soar.triggerLabel')" min-width="160" show-overflow-tooltip />
              <el-table-column :label="t('soar.results')" min-width="320">
                <template #default="{ row }">
                  <span v-for="(result, index) in (row.results || [])" :key="index" class="soar-result-tag">
                    <el-tag size="small" :type="String(result.status).startsWith('fail') ? 'danger' : (String(result.status).startsWith('sent') || String(result.status).startsWith('created') ? 'success' : 'info')">{{ result.action }} → {{ result.status }}</el-tag>
                  </span>
                </template>
              </el-table-column>
            </el-table>
            <PagerBar v-model:current-page="page" v-model:page-size="size" :total="executions.length" />
          </el-card>
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
                  <small class="soar-header-hint">双人复核与高危动作审批（必须附带审计理由）</small>
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

    <!-- Create Playbook Dialog -->
    <el-dialog v-model="dialogVisible" :title="t('soar.createPlaybook')" width="500px">
      <el-form label-width="90px">
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('soar.triggerLabel')"><el-input v-model="form.trigger" /></el-form-item>
        <el-form-item :label="t('soar.actions')"><el-input v-model="form.actions" type="textarea" :rows="3" :placeholder="t('soar.oneActionPerLine')" /></el-form-item>
        <el-form-item :label="t('common.enable')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="addPlaybook">{{ t('common.create') }}</el-button>
      </template>
    </el-dialog>

    <!-- Approval Decision Dialog -->
    <el-dialog v-model="approvalModal.visible" :title="approvalModal.isApprove ? t('soar.approve') : t('soar.reject')" width="480px">
      <div class="soar-approval-dialog-body">
        <p><strong>Run:</strong> {{ approvalModal.runId }}</p>
        <p v-if="approvalModal.actionRef"><strong>Action:</strong> {{ approvalModal.actionRef }}</p>
        <el-form label-position="top">
          <el-form-item :label="t('soar.decisionReason')">
            <el-input v-model="approvalModal.reason" type="textarea" :rows="3" placeholder="填写审批原因与处置意见（用于审计追踪）" />
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
.soar-v2-summary { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.soar-approval-table { margin-top: 8px; }
.soar-approval-dialog-body p { margin-bottom: 8px; font-size: 12px; color: var(--ns-text-2); }
.soar-text-muted { color: var(--ns-text-3); font-size: 11px; }
</style>
