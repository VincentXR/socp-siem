<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  createV2AutomationRule,
  createV2Connection,
  discardV2DeadDispatch,
  deleteV2Connection,
  getV2Stats,
  listV2Actions,
  listV2AutomationRules,
  listV2Connections,
  listV2DeadDispatches,
  listV2ManualTasksPage,
  patchV2AutomationRule,
  requeueV2DeadDispatch,
  setV2AutomationRuleEnabled,
  setV2ConnectionEnabled,
  testV2AutomationRules,
  testV2Connection,
  completeV2ManualTask,
  type SoarV2ActionDescriptor,
  type SoarV2AutomationRule,
  type SoarV2Connection,
  type SoarV2DeadLetter,
  type SoarV2ManualTask,
  type SoarV2Stats,
} from '../../api'

export type SoarControlPlaneSection = 'rules' | 'connections' | 'tasks' | 'operations' | 'connections-and-ops' | 'all'
type Tab = 'rules' | 'connections' | 'tasks' | 'operations'

const props = withDefaults(defineProps<{
  initialTab?: Tab
  hideTabs?: boolean
  section?: SoarControlPlaneSection
}>(), {
  initialTab: 'rules',
  hideTabs: false,
  section: 'all',
})

const tab = ref<Tab>(
  props.section && props.section !== 'all' && props.section !== 'connections-and-ops'
    ? props.section
    : (props.initialTab || 'rules')
)

watch(() => props.section, (val) => {
  if (val && val !== 'all' && val !== 'connections-and-ops') {
    tab.value = val
  } else if (val === 'connections-and-ops' && !['connections', 'operations'].includes(tab.value)) {
    tab.value = 'connections'
  }
})
const loading = ref(false)
const message = ref('')
const errorMessage = ref('')
const rules = ref<SoarV2AutomationRule[]>([])
const connections = ref<SoarV2Connection[]>([])
const actions = ref<SoarV2ActionDescriptor[]>([])
const tasks = ref<SoarV2ManualTask[]>([])
const deadLetters = ref<SoarV2DeadLetter[]>([])
const stats = ref<SoarV2Stats | null>(null)
const showRuleForm = ref(false)
const showConnectionForm = ref(false)
const ruleEventText = ref('{\n  "eventId": "sample-alert-1",\n  "type": "alert.created",\n  "severity": "HIGH"\n}')
const ruleTestResult = ref<Record<string, unknown>[] | null>(null)
const taskInputs = reactive<Record<string, string>>({})
const ruleForm = reactive({
  name: '', triggerType: 'alert.created', priority: 100, playbookVersionIds: '',
  conditions: '{}', suppression: '{\n  "dedupWindowSeconds": 300,\n  "conflictStrategy": "QUEUE"\n}',
})
const connectionForm = reactive({
  name: '', connectorType: 'http.webhook', endpoint: '', authSecretRef: '', allowedHosts: '', enabled: true,
})
const pendingCount = computed(() => tasks.value.filter(item => item.status === 'PENDING').length)

function clearFeedback() { message.value = ''; errorMessage.value = '' }
function failureText(failure: unknown) { return failure instanceof Error ? failure.message : 'SOAR V2 request failed' }
function parseJson(value: string, fallback: unknown = {}) {
  try { return value.trim() ? JSON.parse(value) : fallback } catch { throw new Error('JSON payload is invalid') }
}

async function load() {
  if (loading.value) return
  loading.value = true
  clearFeedback()
  const results = await Promise.allSettled([
    listV2AutomationRules(0, 100), listV2Connections(0, 100), listV2Actions(),
    listV2ManualTasksPage(true, 0, 100), listV2DeadDispatches(), getV2Stats(),
  ])
  const [ruleResult, connectionResult, actionResult, taskResult, deadResult, statsResult] = results
  if (ruleResult.status === 'fulfilled') rules.value = ruleResult.value.items
  if (connectionResult.status === 'fulfilled') connections.value = connectionResult.value.items
  if (actionResult.status === 'fulfilled') actions.value = actionResult.value
  if (taskResult.status === 'fulfilled') tasks.value = taskResult.value.items
  if (deadResult.status === 'fulfilled') deadLetters.value = deadResult.value
  if (statsResult.status === 'fulfilled') stats.value = statsResult.value
  const failures = results.filter(item => item.status === 'rejected')
  if (failures.length && failures.length === results.length) errorMessage.value = failureText(failures[0].reason)
  loading.value = false
}

async function createRule() {
  clearFeedback()
  try {
    const ids = ruleForm.playbookVersionIds.split(/[,\n]/).map(value => value.trim()).filter(Boolean)
    if (!ruleForm.name.trim() || !ids.length) throw new Error('Rule name and at least one published version are required')
    await createV2AutomationRule({
      name: ruleForm.name.trim(), triggerType: ruleForm.triggerType.trim() || 'ANY',
      priority: Number(ruleForm.priority) || 0, enabled: false,
      conditions: parseJson(ruleForm.conditions), actions: ids.map(playbookVersionId => ({ playbookVersionId })),
      suppression: parseJson(ruleForm.suppression),
    })
    showRuleForm.value = false
    message.value = 'Automation rule created disabled; enable it after review'
    await load()
  } catch (failure) { errorMessage.value = failureText(failure) }
}

async function toggleRule(rule: SoarV2AutomationRule) {
  clearFeedback()
  try { await setV2AutomationRuleEnabled(rule.id, !rule.enabled); message.value = `Rule ${rule.enabled ? 'disabled' : 'enabled'}`; await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
}

async function removeRule(rule: SoarV2AutomationRule) {
  if (!window.confirm(`Disable automation rule “${rule.name}”?`)) return
  clearFeedback()
  try { await patchV2AutomationRule(rule.id, { enabled: false, rowVersion: rule.rowVersion }); message.value = 'Rule disabled'; await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
}

async function testRules() {
  clearFeedback()
  try { ruleTestResult.value = await testV2AutomationRules(parseJson(ruleEventText.value) as Record<string, unknown>) }
  catch (failure) { errorMessage.value = failureText(failure) }
}

async function createConnection() {
  clearFeedback()
  try {
    const allowedHosts = connectionForm.allowedHosts.split(/[,\n]/).map(value => value.trim()).filter(Boolean)
    if (!connectionForm.name.trim() || !connectionForm.endpoint.trim() || !allowedHosts.length) {
      throw new Error('Connection name, HTTPS endpoint and an allowlisted host are required')
    }
    await createV2Connection({ name: connectionForm.name.trim(), connectorType: connectionForm.connectorType.trim(),
      endpoint: connectionForm.endpoint.trim(), authSecretRef: connectionForm.authSecretRef.trim() || undefined,
      allowedHosts, enabled: connectionForm.enabled })
    showConnectionForm.value = false
    message.value = 'Connection created; run a connection test before publishing a playbook'
    await load()
  } catch (failure) { errorMessage.value = failureText(failure) }
}

async function toggleConnection(connection: SoarV2Connection) {
  clearFeedback()
  try { await setV2ConnectionEnabled(connection.id, !connection.enabled); message.value = 'Connection state updated'; await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
}

async function testConnection(connection: SoarV2Connection) {
  clearFeedback()
  try { const result = await testV2Connection(connection.id); message.value = `Connection test: ${result.status}`; await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
}

async function removeConnection(connection: SoarV2Connection) {
  if (!window.confirm(`Delete connection “${connection.name}”? Published references are protected.`)) return
  clearFeedback()
  try { await deleteV2Connection(connection.id); message.value = 'Connection disabled and removed from the active catalog'; await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
}

async function completeTask(task: SoarV2ManualTask) {
  clearFeedback()
  try {
    const value = parseJson(taskInputs[task.id] || '{}')
    if (typeof value !== 'object' || Array.isArray(value) || value === null) throw new Error('Manual task input must be a JSON object')
    await completeV2ManualTask(task.id, value as Record<string, unknown>)
    message.value = 'Manual task completed and a durable signal was queued'
    await load()
  } catch (failure) { errorMessage.value = failureText(failure) }
}

async function requeue(letter: SoarV2DeadLetter) {
  clearFeedback()
  try { await requeueV2DeadDispatch(letter.id, 'Workbench operator requeue'); message.value = 'Dead letter requeued'; await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
}

async function discard(letter: SoarV2DeadLetter) {
  if (!window.confirm('Discard this dead letter? The associated run may be suppressed.')) return
  clearFeedback()
  try { await discardV2DeadDispatch(letter.id, 'Workbench operator discard'); message.value = 'Dead letter discarded'; await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
}

onMounted(() => { void load() })
</script>

<template>
  <el-card shadow="never" class="soar-v2-control-plane">
    <template #header>
      <div class="soar-v2-control-header">
        <div>
          <strong>{{ section === 'rules' ? 'SOAR V2 · 自动化规则' : section === 'tasks' ? 'SOAR V2 · 人工任务' : section === 'connections-and-ops' ? 'SOAR V2 · 连接与运维' : 'SOAR V2 control plane' }}</strong>
          <span class="soar-v2-subtitle">{{ section === 'rules' ? 'event routing · conditions · dedup' : section === 'tasks' ? 'human-in-the-loop task queue' : section === 'connections-and-ops' ? 'connectors · allowlists · dead dispatches' : 'automation · connections · analyst tasks · dead-letter operations' }}</span>
        </div>
        <el-button size="small" :loading="loading" @click="load">Refresh</el-button>
      </div>
    </template>

    <div v-if="!hideTabs && (!section || section === 'all')" class="soar-v2-tabs" role="tablist" aria-label="SOAR V2 control plane">
      <button v-for="item in (['rules', 'connections', 'tasks', 'operations'] as Tab[])" :key="item" type="button" :class="{ active: tab === item }" role="tab" :aria-selected="tab === item" @click="tab = item">
        {{ item === 'rules' ? 'Automation rules' : item === 'connections' ? 'Connections' : item === 'tasks' ? `Manual tasks (${pendingCount})` : 'Operations' }}
      </button>
    </div>
    <div v-else-if="!hideTabs && section === 'connections-and-ops'" class="soar-v2-tabs" role="tablist" aria-label="SOAR V2 connections and ops">
      <button v-for="item in (['connections', 'operations'] as Tab[])" :key="item" type="button" :class="{ active: tab === item }" role="tab" :aria-selected="tab === item" @click="tab = item">
        {{ item === 'connections' ? 'Connections & Catalog' : 'Dead-letter & Operations' }}
      </button>
    </div>
    <div v-if="message" class="soar-v2-feedback success">{{ message }}</div>
    <div v-if="errorMessage" class="soar-v2-feedback error">{{ errorMessage }}</div>

    <section v-if="tab === 'rules'" class="soar-v2-control-section">
      <div class="soar-v2-section-toolbar"><div><b>Event → playbook routing</b><small>Rules are disabled by default when created and bind only immutable published versions.</small></div><div><el-button size="small" @click="showRuleForm = !showRuleForm">{{ showRuleForm ? 'Close form' : 'New rule' }}</el-button><el-button size="small" @click="testRules">Test event</el-button></div></div>
      <div v-if="showRuleForm" class="soar-v2-form-grid">
        <label>Name<input v-model="ruleForm.name" placeholder="High severity response" /></label>
        <label>Trigger type<input v-model="ruleForm.triggerType" placeholder="alert.created" /></label>
        <label>Priority<input v-model.number="ruleForm.priority" type="number" min="0" max="10000" /></label>
        <label>Published version IDs<textarea v-model="ruleForm.playbookVersionIds" rows="2" placeholder="one version id per line" /></label>
        <label>Conditions JSON<textarea v-model="ruleForm.conditions" rows="3" spellcheck="false" /></label>
        <label>Suppression JSON<textarea v-model="ruleForm.suppression" rows="3" spellcheck="false" /></label>
        <div class="soar-v2-form-actions"><el-button type="primary" size="small" @click="createRule">Create disabled rule</el-button></div>
      </div>
      <div class="soar-v2-test-box"><textarea v-model="ruleEventText" rows="3" spellcheck="false" aria-label="Automation test event" /><pre v-if="ruleTestResult">{{ JSON.stringify(ruleTestResult, null, 2) }}</pre></div>
      <div class="soar-v2-table-scroll"><table><thead><tr><th>Name</th><th>Trigger</th><th>Priority</th><th>Revision</th><th>Status</th><th>Target versions</th><th>Action</th></tr></thead><tbody><tr v-for="rule in rules" :key="rule.id"><td><b>{{ rule.name }}</b><small>{{ rule.id }}</small></td><td>{{ rule.triggerType }}</td><td>{{ rule.priority }}</td><td>{{ rule.revision || 1 }}</td><td><el-tag size="small" :type="rule.enabled ? 'success' : 'info'">{{ rule.enabled ? 'ENABLED' : 'DISABLED' }}</el-tag></td><td class="mono">{{ JSON.stringify(rule.actions) }}</td><td class="nowrap"><el-button link size="small" @click="toggleRule(rule)">{{ rule.enabled ? 'Disable' : 'Enable' }}</el-button><el-button link type="danger" size="small" @click="removeRule(rule)">Disable</el-button></td></tr></tbody></table><div v-if="!rules.length" class="soar-v2-empty">No V2 automation rules.</div></div>
    </section>

    <section v-else-if="tab === 'connections'" class="soar-v2-control-section">
      <div class="soar-v2-section-toolbar"><div><b>Connector assets and egress policy</b><small>Secrets are references only; endpoints must pass HTTPS and host allowlist checks.</small></div><div><el-button size="small" @click="showConnectionForm = !showConnectionForm">{{ showConnectionForm ? 'Close form' : 'New connection' }}</el-button><details class="soar-v2-inline-details"><summary>Action catalog ({{ actions.length }})</summary><div class="soar-v2-action-catalog"><span v-for="action in actions" :key="action.actionRef"><b>{{ action.actionRef }}</b><small>{{ action.riskLevel }} · {{ action.idempotency }} · {{ action.production ? 'production' : 'certification required' }}</small></span></div></details></div></div>
      <div v-if="showConnectionForm" class="soar-v2-form-grid">
        <label>Name<input v-model="connectionForm.name" placeholder="EDR production" /></label>
        <label>Connector type<input v-model="connectionForm.connectorType" placeholder="http.webhook" /></label>
        <label>HTTPS endpoint<input v-model="connectionForm.endpoint" placeholder="https://api.example.test/response" /></label>
        <label>Secret ref<input v-model="connectionForm.authSecretRef" placeholder="secret://SOAR_EDR_TOKEN" /></label>
        <label>Allowed hosts<input v-model="connectionForm.allowedHosts" placeholder="api.example.test" /></label>
        <label class="soar-v2-checkbox"><input v-model="connectionForm.enabled" type="checkbox" /> Enabled after create</label>
        <div class="soar-v2-form-actions"><el-button type="primary" size="small" @click="createConnection">Create connection</el-button></div>
      </div>
      <div class="soar-v2-table-scroll"><table><thead><tr><th>Name</th><th>Type</th><th>Endpoint</th><th>Status</th><th>Last test</th><th>Action</th></tr></thead><tbody><tr v-for="connection in connections" :key="connection.id"><td><b>{{ connection.name }}</b><small>{{ connection.id }}</small></td><td>{{ connection.connectorType }}</td><td class="mono">{{ connection.endpoint }}</td><td><el-tag size="small" :type="connection.status === 'HEALTHY' ? 'success' : connection.enabled ? 'warning' : 'info'">{{ connection.status }}</el-tag></td><td>{{ connection.lastTestAt || '-' }}<small>{{ connection.lastTestError || '' }}</small></td><td class="nowrap"><el-button link size="small" @click="testConnection(connection)">Test</el-button><el-button link size="small" @click="toggleConnection(connection)">{{ connection.enabled ? 'Disable' : 'Enable' }}</el-button><el-button link type="danger" size="small" @click="removeConnection(connection)">Delete</el-button></td></tr></tbody></table><div v-if="!connections.length" class="soar-v2-empty">No tenant connections.</div></div>
    </section>

    <section v-else-if="tab === 'tasks'" class="soar-v2-control-section">
      <div class="soar-v2-section-toolbar"><div><b>Human-in-the-loop tasks</b><small>Completion is schema-checked and delivered through the durable signal outbox.</small></div></div>
      <div class="soar-v2-table-scroll"><table><thead><tr><th>Task</th><th>Run / node</th><th>Assignee</th><th>Due</th><th>JSON input</th><th>Action</th></tr></thead><tbody><tr v-for="task in tasks" :key="task.id"><td><b>{{ task.id }}</b><small>{{ task.status }}</small></td><td class="mono">{{ task.runId }} / {{ task.nodeId }}</td><td>{{ task.assignee || 'any approver' }}</td><td>{{ task.dueAt || '-' }}</td><td><input v-model="taskInputs[task.id]" class="soar-v2-task-input" placeholder="{}" aria-label="Manual task JSON input" /></td><td><el-button size="small" type="primary" plain @click="completeTask(task)">Complete</el-button></td></tr></tbody></table><div v-if="!tasks.length" class="soar-v2-empty">No pending manual tasks.</div></div>
    </section>

    <section v-else class="soar-v2-control-section">
      <div class="soar-v2-stat-grid"><div><b>{{ stats?.dispatchBacklog ?? 0 }}</b><small>dispatch backlog</small></div><div><b>{{ stats?.signalBacklog ?? 0 }}</b><small>signal backlog</small></div><div><b>{{ deadLetters.length }}</b><small>dead letters</small></div><div><b>{{ Object.values(stats?.runsByStatus || {}).reduce((sum, value) => sum + value, 0) }}</b><small>projected runs</small></div></div>
      <div class="soar-v2-section-toolbar"><div><b>Dead-letter operations</b><small>Requeue only after checking the remote receipt; discard is an audited terminal decision.</small></div></div>
      <div class="soar-v2-table-scroll"><table><thead><tr><th>Kind</th><th>Run</th><th>Signal key</th><th>Attempts</th><th>Last error</th><th>Action</th></tr></thead><tbody><tr v-for="letter in deadLetters" :key="`${letter.kind}-${letter.id}`"><td>{{ letter.kind || 'DISPATCH' }}<small>{{ letter.signalType || '' }}</small></td><td class="mono">{{ letter.runId }}</td><td class="mono">{{ letter.signalKey || '-' }}</td><td>{{ letter.attempts }}</td><td>{{ letter.lastError || '-' }}</td><td class="nowrap"><el-button link size="small" @click="requeue(letter)">Requeue</el-button><el-button link type="danger" size="small" @click="discard(letter)">Discard</el-button></td></tr></tbody></table><div v-if="!deadLetters.length" class="soar-v2-empty">No dead dispatches or signals.</div></div>
    </section>
  </el-card>
</template>

<style scoped>
.soar-v2-control-plane { margin-top: 16px; border: 1px solid var(--ns-border); }
.soar-v2-control-header, .soar-v2-section-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 14px; }
.soar-v2-subtitle, .soar-v2-section-toolbar small { display: block; margin-top: 4px; color: var(--ns-text-3); font-size: 11px; }
.soar-v2-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--ns-border); margin-bottom: 12px; }
.soar-v2-tabs button { border: 0; border-bottom: 2px solid transparent; padding: 8px 11px; background: transparent; color: var(--ns-text-2); cursor: pointer; font: inherit; font-size: 11px; }
.soar-v2-tabs button:hover, .soar-v2-tabs button.active { border-bottom-color: var(--ns-accent); color: var(--ns-accent); }
.soar-v2-feedback { margin: 8px 0; padding: 7px 10px; border-radius: 5px; font-size: 11px; }.soar-v2-feedback.success { color: var(--ns-success); background: color-mix(in srgb, var(--ns-success) 9%, transparent); }.soar-v2-feedback.error { color: var(--ns-danger); background: color-mix(in srgb, var(--ns-danger) 9%, transparent); }
.soar-v2-control-section { min-width: 0; }.soar-v2-section-toolbar { margin-bottom: 10px; }.soar-v2-section-toolbar > div:first-child { min-width: 0; }
.soar-v2-form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; margin: 10px 0; padding: 10px; border: 1px solid var(--ns-border); border-radius: 5px; background: var(--ns-bg-subtle); }
.soar-v2-form-grid label { display: flex; flex-direction: column; gap: 4px; color: var(--ns-text-3); font-size: 10px; }.soar-v2-form-grid input, .soar-v2-form-grid textarea, .soar-v2-test-box textarea, .soar-v2-task-input { width: 100%; box-sizing: border-box; border: 1px solid var(--ns-border); border-radius: 4px; padding: 6px 7px; background: var(--ns-bg); color: var(--ns-text); font: inherit; font-size: 11px; }.soar-v2-form-grid textarea, .soar-v2-test-box textarea { resize: vertical; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }.soar-v2-form-actions { align-self: end; }.soar-v2-checkbox { justify-content: flex-end; flex-direction: row !important; align-items: center; gap: 7px !important; }.soar-v2-checkbox input { width: auto; }
.soar-v2-test-box { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 9px; margin-bottom: 10px; }.soar-v2-test-box pre { max-height: 100px; margin: 0; overflow: auto; padding: 7px; border: 1px solid var(--ns-border); border-radius: 4px; font-size: 10px; }
.soar-v2-table-scroll { max-height: 330px; overflow: auto; }.soar-v2-control-plane table { width: 100%; border-collapse: collapse; font-size: 10px; }.soar-v2-control-plane th, .soar-v2-control-plane td { padding: 7px 6px; border-bottom: 1px solid var(--ns-border); text-align: left; vertical-align: top; }.soar-v2-control-plane th { color: var(--ns-text-3); font-size: 9px; text-transform: uppercase; }.soar-v2-control-plane td b, .soar-v2-control-plane td small { display: block; }.soar-v2-control-plane td small { margin-top: 2px; color: var(--ns-text-3); font-size: 9px; }.mono { max-width: 300px; overflow-wrap: anywhere; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }.nowrap { white-space: nowrap; }.soar-v2-empty { padding: 10px 0; color: var(--ns-text-3); font-size: 11px; }
.soar-v2-inline-details { position: relative; display: inline-block; margin-left: 6px; color: var(--ns-text-2); font-size: 10px; }.soar-v2-inline-details summary { cursor: pointer; }.soar-v2-action-catalog { position: absolute; z-index: 2; right: 0; top: 22px; display: grid; width: min(520px, 80vw); max-height: 240px; overflow: auto; gap: 6px; padding: 9px; border: 1px solid var(--ns-border); border-radius: 5px; background: var(--ns-bg); box-shadow: 0 5px 20px rgb(0 0 0 / 16%); }.soar-v2-action-catalog span { display: flex; justify-content: space-between; gap: 10px; }.soar-v2-action-catalog small { color: var(--ns-text-3); }.soar-v2-task-input { min-width: 180px; }.soar-v2-stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 14px; }.soar-v2-stat-grid > div { padding: 10px; border: 1px solid var(--ns-border); border-radius: 5px; background: var(--ns-bg-subtle); }.soar-v2-stat-grid b, .soar-v2-stat-grid small { display: block; }.soar-v2-stat-grid b { font-size: 20px; }.soar-v2-stat-grid small { margin-top: 3px; color: var(--ns-text-3); font-size: 10px; }
@media (max-width: 850px) { .soar-v2-form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.soar-v2-test-box { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .soar-v2-control-header, .soar-v2-section-toolbar { align-items: flex-start; flex-direction: column; }.soar-v2-form-grid, .soar-v2-stat-grid { grid-template-columns: 1fr; }.soar-v2-tabs { overflow-x: auto; }.soar-v2-tabs button { white-space: nowrap; } }
</style>
