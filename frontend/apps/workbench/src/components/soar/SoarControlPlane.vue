<script setup lang="ts">
import { useFormDialog } from '../../composables/useFormDialog'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import ElInputNumber from 'element-plus/es/components/input-number/index.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/input-number/style/css.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import SchemaInputForm from '../SchemaInputForm.vue'
const showRuleTest = ref(false)
const editingRule = ref<SoarAutomationRule | null>(null)
const selectedTask = ref<SoarManualTask | null>(null)
const taskValue = ref<Record<string, unknown>>({})
const taskValid = ref(true)
const saving = ref(false)

import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import FieldConditionBuilder from '../FieldConditionBuilder.vue'
import { useI18n } from '../../composables/useI18n'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  createAutomationRule,
  createConnection as createConnectionApi,
  discardDeadDispatch,
  deleteConnection,
  getStats,
  listActions,
  listAutomationRules,
  listConnections,
  listDeadDispatches,
  listManualTasksPage,
  listPlaybooks,
  listVersions,
  patchAutomationRule,
  requeueDeadDispatch,
  setAutomationRuleEnabled,
  setConnectionEnabled,
  testAutomationRules,
  testConnection as testConnectionApi,
  completeManualTask,
  type SoarActionDescriptor,
  type SoarAutomationRule,
  type SoarConnection,
  type SoarDeadLetter,
  type SoarManualTask,
  type SoarStats,
} from '../../api'
import type { FieldDef, RuleCondition } from '../../api'

export type SoarControlPlaneSection = 'rules' | 'connections' | 'tasks' | 'operations' | 'connections-and-ops' | 'all'
type Tab = 'rules' | 'connections' | 'tasks' | 'operations'

const props = withDefaults(defineProps<{
  initialTab?: Tab
  hideTabs?: boolean
  section?: SoarControlPlaneSection
  canWrite?: boolean
}>(), {
  initialTab: 'rules',
  hideTabs: false,
  section: 'all',
  canWrite: true,
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
const rules = ref<SoarAutomationRule[]>([])
const connections = ref<SoarConnection[]>([])
const actions = ref<SoarActionDescriptor[]>([])
const tasks = ref<SoarManualTask[]>([])
const deadLetters = ref<SoarDeadLetter[]>([])
const stats = ref<SoarStats | null>(null)
const showRuleForm = ref(false)
const showConnectionForm = ref(false)
const ruleEventText = ref('{\n  "eventId": "sample-alert-1",\n  "type": "alert.created",\n  "severity": "HIGH"\n}')
const ruleTestResult = ref<Record<string, unknown>[] | null>(null)
const ruleConditionRows = ref<RuleCondition[]>([])
const automationFields: FieldDef[] = [
  { id: 'automation-event-type', fieldName: 'type', fieldLabel: 'Event type', fieldType: 'string', source: 'automation event', searchable: true, aggregatable: false, stored: true, description: 'alert.created, case.updated, or another event type' },
  { id: 'automation-severity', fieldName: 'severity', fieldLabel: 'Severity', fieldType: 'string', source: 'automation event', searchable: true, aggregatable: false, stored: true, description: 'INFO through CRITICAL' },
  { id: 'automation-source', fieldName: 'source', fieldLabel: 'Source', fieldType: 'string', source: 'automation event', searchable: true, aggregatable: false, stored: true, description: 'Event source or collector' },
  { id: 'automation-host', fieldName: 'host', fieldLabel: 'Host', fieldType: 'string', source: 'automation event', searchable: true, aggregatable: false, stored: true, description: 'Host associated with the event' },
  { id: 'automation-entity', fieldName: 'entity', fieldLabel: 'Entity', fieldType: 'string', source: 'automation event', searchable: true, aggregatable: false, stored: true, description: 'User, host, IP, or other entity' },
]
const publishedVersionOptions = ref<Array<{ id: string; playbookName: string; version: number; status: string }>>([])
const versionOptionsLoading = ref(false)
const versionOptionsError = ref('')
const ruleForm = reactive({
  name: '', triggerType: 'alert.created', priority: 100, playbookVersionIds: [] as string[],
  conditions: '{}', suppression: '{\n  "dedupWindowSeconds": 300,\n  "conflictStrategy": "QUEUE"\n}',
})
const connectionForm = reactive({
  name: '', connectorType: 'http.webhook', endpoint: '', authSecretRef: '', allowedHosts: '', enabled: true,
})
const taskOpen = ref(false)
const taskGuard = useFormDialog(taskOpen, () => taskValue.value, () => saving.value)
const ruleGuard = useFormDialog(showRuleForm, () => ruleForm, () => saving.value)
const connectionGuard = useFormDialog(showConnectionForm, () => connectionForm, () => saving.value)
const pendingCount = computed(() => tasks.value.filter(item => item.status === 'PENDING').length)
const connectorTypeOptions = computed(() => Array.from(new Set([
  connectionForm.connectorType,
  ...actions.value.map(action => action.connectorId),
].filter(Boolean))))

const suppressionSettings = computed<Record<string, unknown>>(() => {
  try { return parseJson(ruleForm.suppression) as Record<string, unknown> } catch { return {} }
})
function updateSuppression(key: string, value: unknown) {
  if (!props.canWrite) return
  try { ruleForm.suppression = JSON.stringify({ ...parseJson(ruleForm.suppression) as Record<string, unknown>, [key]: value }, null, 2) }
  catch (failure) { errorMessage.value = failureText(failure) }
}

function clearFeedback() { message.value = ''; errorMessage.value = '' }
function failureText(failure: unknown) { return failure instanceof Error ? failure.message : t('soar.requestFailed') }
function parseJson(value: string, fallback: unknown = {}) {
  try { return value.trim() ? JSON.parse(value) : fallback } catch { throw new Error('JSON payload is invalid') }
}

async function load() {
  if (loading.value) return
  loading.value = true
  clearFeedback()
  const results = await Promise.allSettled([
    listAutomationRules(0, 100), listConnections(0, 100), listActions(),
    listManualTasksPage(true, 0, 100), listDeadDispatches(), getStats(),
  ])
  const [ruleResult, connectionResult, actionResult, taskResult, deadResult, statsResult] = results
  if (ruleResult.status === 'fulfilled') rules.value = ruleResult.value.items
  if (connectionResult.status === 'fulfilled') connections.value = connectionResult.value.items
  if (actionResult.status === 'fulfilled') actions.value = actionResult.value
  if (taskResult.status === 'fulfilled') tasks.value = taskResult.value.items
  if (deadResult.status === 'fulfilled') deadLetters.value = deadResult.value
  if (statsResult.status === 'fulfilled') stats.value = statsResult.value
  const failures = results.filter(item => item.status === 'rejected')
  if (failures.length) errorMessage.value = failureText(failures[0].reason)
  loading.value = false
}

async function loadPublishedVersionOptions(): Promise<void> {
  if (versionOptionsLoading.value) return
  versionOptionsLoading.value = true
  versionOptionsError.value = ''
  try {
    const page = await listPlaybooks(0, 100)
    const results = await Promise.allSettled(page.items.map(async playbook => {
      const versions = await listVersions(playbook.id)
      return versions
        .filter(version => version.status === 'PUBLISHED')
        .map(version => ({ id: version.id, playbookName: playbook.name, version: version.version, status: version.status }))
    }))
    publishedVersionOptions.value = results
      .filter((result): result is PromiseFulfilledResult<Array<{ id: string; playbookName: string; version: number; status: string }>> => result.status === 'fulfilled')
      .flatMap(result => result.value)
    if (!publishedVersionOptions.value.length && results.some(result => result.status === 'rejected')) {
      versionOptionsError.value = t('soar.versionCatalogUnavailable')
    }
  } catch (failure) {
    versionOptionsError.value = failureText(failure)
  } finally {
    versionOptionsLoading.value = false
  }
}

function syncRuleConditionRows(): void {
  try {
    const parsed = parseJson(ruleForm.conditions, {})
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) { ruleConditionRows.value = []; return }
    if (Object.values(parsed as Record<string, unknown>).some(value => typeof value !== 'string')) { ruleConditionRows.value = []; return }
    ruleConditionRows.value = Object.entries(parsed as Record<string, unknown>).map(([field, value]) => ({ field, op: 'eq', value: String(value) }))
  } catch { ruleConditionRows.value = [] }
}

function commitRuleConditionRows(rows: RuleCondition[]): void {
  if (!props.canWrite) return
  ruleConditionRows.value = rows.map(row => ({ ...row }))
  const existing = parseJson(ruleForm.conditions) as Record<string, unknown>
  const advanced = Object.fromEntries(Object.entries(existing).filter(([, value]) => typeof value !== 'string'))
  const conditions = Object.fromEntries(ruleConditionRows.value.filter(row => row.field.trim() && row.value.trim()).map(row => [row.field.trim(), row.value.trim()]))
  ruleForm.conditions = JSON.stringify({ ...advanced, ...conditions }, null, 2)
}

function toggleRuleForm(): void {
  if (!props.canWrite) return
  editingRule.value = null
  Object.assign(ruleForm, { name: '', triggerType: 'alert.created', priority: 100, playbookVersionIds: [], conditions: '{}', suppression: '{}' })
  showRuleForm.value = true
  if (showRuleForm.value) { syncRuleConditionRows(); void loadPublishedVersionOptions() }
}

async function createRule() {
  if (!props.canWrite || saving.value) return
  saving.value = true
  try {
  clearFeedback()
  try {
    const ids = ruleForm.playbookVersionIds.map(value => value.trim()).filter(Boolean)
    if (!ruleForm.name.trim() || !ids.length) throw new Error(t('soar.ruleNameRequired'))
    const payload = {
      name: ruleForm.name.trim(), triggerType: ruleForm.triggerType.trim() || 'ANY',
      priority: Number(ruleForm.priority) || 0, enabled: false,
      conditions: parseJson(ruleForm.conditions), actions: ids.map(playbookVersionId => {
        const existing = (Array.isArray(editingRule.value?.actions) ? editingRule.value.actions : []).find((action: Record<string, unknown>) => action.playbookVersionId === playbookVersionId)
        return existing ? { ...existing, playbookVersionId } : { playbookVersionId }
      }),
      suppression: parseJson(ruleForm.suppression),
    }
    if (editingRule.value) await patchAutomationRule(editingRule.value.id, { ...payload, enabled: editingRule.value.enabled, rowVersion: editingRule.value.rowVersion })
    else await createAutomationRule(payload)
    showRuleForm.value = false
    ruleForm.name = ''
    ruleForm.playbookVersionIds = []
    message.value = t('soar.ruleCreatedDisabled')
    await load()
  } catch (failure) { errorMessage.value = failureText(failure) }
  } finally { saving.value = false }
}

async function toggleRule(rule: SoarAutomationRule) {
  if (!props.canWrite || saving.value) return
  saving.value = true
  try {
  clearFeedback()
  try { await setAutomationRuleEnabled(rule.id, !rule.enabled); message.value = rule.enabled ? t('soar.ruleDisabled') : t('soar.ruleEnabled'); await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
  } finally { saving.value = false }
}

function editRule(rule: SoarAutomationRule) {
  if (!props.canWrite) return
  editingRule.value = rule
  Object.assign(ruleForm, { name: rule.name, triggerType: rule.triggerType, priority: rule.priority,
    conditions: JSON.stringify(rule.conditions ?? {}, null, 2), suppression: JSON.stringify(rule.suppression ?? {}, null, 2),
    playbookVersionIds: Array.isArray(rule.actions) ? rule.actions.map(action => String((action as Record<string, unknown>).playbookVersionId || '')).filter(Boolean) : [],
  })
  syncRuleConditionRows()
  showRuleForm.value = true
  void loadPublishedVersionOptions()
}
function openTask(task: SoarManualTask) {
  selectedTask.value = task
  taskValue.value = task.input && typeof task.input === 'object' ? structuredClone(task.input) as Record<string, unknown> : {}
  taskValid.value = true
  taskOpen.value = true
}

async function testRules() {
  if (!props.canWrite || saving.value) return
  saving.value = true
  try {
  clearFeedback()
  try { ruleTestResult.value = await testAutomationRules(parseJson(ruleEventText.value) as Record<string, unknown>) }
  catch (failure) { errorMessage.value = failureText(failure) }
  } finally { saving.value = false }
}

async function createConnection() {
  if (!props.canWrite || saving.value) return
  saving.value = true
  try {
  clearFeedback()
  try {
    const allowedHosts = connectionForm.allowedHosts.split(/[,\n]/).map(value => value.trim()).filter(Boolean)
    if (!connectionForm.name.trim() || !connectionForm.endpoint.trim() || !allowedHosts.length) {
      throw new Error(t('soar.connectionRequired'))
    }
    await createConnectionApi({ name: connectionForm.name.trim(), connectorType: connectionForm.connectorType.trim(),
      endpoint: connectionForm.endpoint.trim(), authSecretRef: connectionForm.authSecretRef.trim() || undefined,
      allowedHosts, enabled: connectionForm.enabled })
    showConnectionForm.value = false
    message.value = t('soar.connectionCreated')
    await load()
  } catch (failure) { errorMessage.value = failureText(failure) }
  } finally { saving.value = false }
}

async function toggleConnection(connection: SoarConnection) {
  if (!props.canWrite || saving.value) return
  saving.value = true
  try {
  clearFeedback()
  try { await setConnectionEnabled(connection.id, !connection.enabled); message.value = t('soar.connectionStateUpdated'); await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
  } finally { saving.value = false }
}

async function testConnection(connection: SoarConnection) {
  if (!props.canWrite || saving.value) return
  saving.value = true
  try {
  clearFeedback()
  try { const result = await testConnectionApi(connection.id); message.value = t('soar.connectionTestResult', { status: result.status }); await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
  } finally { saving.value = false }
}

async function removeConnection(connection: SoarConnection) {
  if (!props.canWrite || saving.value) return
  saving.value = true
  try {
  if (!window.confirm(t('soar.deleteConnectionConfirm', { name: connection.name }))) return
  clearFeedback()
  try { await deleteConnection(connection.id); message.value = t('soar.connectionRemoved'); await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
  } finally { saving.value = false }
}

async function completeTask(task: SoarManualTask) {
  if (!props.canWrite || saving.value) return
  saving.value = true
  try {
  clearFeedback()
  try {
    if (!taskValid.value) throw new Error(t('forms.invalidInput'))
    const schema = task.formSchema as { required?: string[] } | null
    const missing = (schema?.required ?? []).filter(key => taskValue.value[key] === undefined || taskValue.value[key] === '')
    if (missing.length) throw new Error(t('soar.manualRequired', { fields: missing.join(', ') }))
    const value = taskValue.value
    if (typeof value !== 'object' || Array.isArray(value) || value === null) throw new Error(t('soar.manualInputObject'))
    await completeManualTask(task.id, value as Record<string, unknown>)
    taskOpen.value = false
    selectedTask.value = null
    message.value = t('forms.saved')
    await load()
  } catch (failure) { errorMessage.value = failureText(failure) }
  } finally { saving.value = false }
}

async function requeue(letter: SoarDeadLetter) {
  if (!props.canWrite || saving.value) return
  saving.value = true
  try {
  clearFeedback()
  try { await requeueDeadDispatch(letter.id, t('soar.requeueReason')); message.value = t('soar.deadLetterRequeued'); await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
  } finally { saving.value = false }
}

async function discard(letter: SoarDeadLetter) {
  if (!props.canWrite || saving.value) return
  saving.value = true
  try {
  if (!window.confirm(t('soar.discardDeadLetterConfirm'))) return
  clearFeedback()
  try { await discardDeadDispatch(letter.id, t('soar.discardReason')); message.value = t('soar.deadLetterDiscarded'); await load() }
  catch (failure) { errorMessage.value = failureText(failure) }
  } finally { saving.value = false }
}

const { t } = useI18n()
onMounted(() => { void load() })

function statusLabel(status: string): string {
  const key = 'soar.status.' + status
  const translated = t(key)
  return translated === key ? status : translated
}

function controlTitle(): string {
  if (props.section === 'rules') return t('soar.controlRules')
  if (props.section === 'tasks') return t('soar.controlTasks')
  if (props.section === 'connections-and-ops') return t('soar.controlConnections')
  return t('soar.controlPlane')
}

function controlSubtitle(): string {
  if (props.section === 'rules') return t('soar.subtitleRules')
  if (props.section === 'tasks') return t('soar.subtitleTasks')
  if (props.section === 'connections-and-ops') return t('soar.subtitleConnections')
  return t('soar.subtitleAll')
}
</script>

<template>
  <el-card shadow="never" class="soar-control-plane">
    <template #header>
      <div class="soar-control-header">
        <div>
          <strong>{{ controlTitle() }}</strong>
          <span class="soar-subtitle">{{ controlSubtitle() }}</span>
        </div>
        <el-button size="small" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
      </div>
    </template>

    <div v-if="!hideTabs && (!section || section === 'all')" class="soar-tabs" role="tablist" :aria-label="t('soar.controlPlane')">
      <button v-for="item in (['rules', 'connections', 'tasks', 'operations'] as Tab[])" :key="item" type="button" :class="{ active: tab === item }" role="tab" :aria-selected="tab === item" @click="tab = item">
        {{ item === 'rules' ? t('soar.automationRules') : item === 'connections' ? t('soar.connections') : item === 'tasks' ? t('soar.manualTasks', { count: pendingCount }) : t('soar.operations') }}
      </button>
    </div>
    <div v-else-if="!hideTabs && section === 'connections-and-ops'" class="soar-tabs" role="tablist" :aria-label="t('soar.controlConnections')">
      <button v-for="item in (['connections', 'operations'] as Tab[])" :key="item" type="button" :class="{ active: tab === item }" role="tab" :aria-selected="tab === item" @click="tab = item">
        {{ item === 'connections' ? t('soar.connectionsCatalog') : t('soar.deadLetterOperations') }}
      </button>
    </div>
    <div v-if="message" class="soar-feedback success">{{ message }}</div>
    <div v-if="errorMessage" class="soar-feedback error">{{ errorMessage }}</div>

    <section v-if="tab === 'rules'" class="soar-control-section">
      <div class="soar-section-toolbar"><div><b>{{ t('soar.eventRouting') }}</b><small>{{ t('soar.eventRoutingHint') }}</small></div><div><el-button v-if="props.canWrite" size="small" @click="toggleRuleForm">{{ showRuleForm ? t('soar.closeForm') : t('soar.newRule') }}</el-button><el-button size="small" @click="showRuleTest = true">{{ t('forms.test') }}</el-button></div></div>
      <el-drawer v-if="props.canWrite" v-model="showRuleForm" :before-close="ruleGuard.beforeClose" :title="editingRule ? t('forms.edit') : t('detect.createRule')" size="min(760px, 96vw)" :close-on-click-modal="false"><div v-if="errorMessage" role="alert" class="soar-feedback error">{{ errorMessage }}</div><div class="soar-form-grid">
        <label>{{ t('common.name') }}<el-input v-model="ruleForm.name" :placeholder="t('soar.namePlaceholder')" /></label>
        <label>{{ t('soar.triggerType') }}
          <el-select v-model="ruleForm.triggerType" filterable default-first-option placeholder="alert.created">
            <el-option label="alert.created" value="alert.created" />
            <el-option label="case.updated" value="case.updated" />
            <el-option label="ANY" value="ANY" />
          </el-select>
        </label>
        <label>{{ t('soar.priority') }}<el-input-number v-model="ruleForm.priority" :min="0" :max="10000" /></label>
        <label>{{ t('soar.publishedVersions') }}
          <el-select v-model="ruleForm.playbookVersionIds" multiple filterable default-first-option collapse-tags :loading="versionOptionsLoading" :placeholder="t('soar.publishedVersionsPlaceholder')">
            <el-option v-for="version in publishedVersionOptions" :key="version.id" :label="`${version.playbookName} · Revision ${version.version}`" :value="version.id"><div class="soar-version-option"><b>{{ version.playbookName }} · Revision {{ version.version }}</b><small>{{ version.id }}</small></div></el-option>
          </el-select>
          <small v-if="versionOptionsError" class="soar-field-warning">{{ versionOptionsError }}</small>
        </label>
        <div class="soar-condition-builder">
          <FieldConditionBuilder :model-value="ruleConditionRows" :read-only="!props.canWrite" :fields="automationFields" :title="t('soar.simpleConditions')" :add-label="t('common.add')" :empty-hint="t('soar.simpleConditionsHint')" :field-placeholder="t('search.fieldSearchPlaceholder')" :value-placeholder="t('common.value')" @update:model-value="commitRuleConditionRows" />
          <details class="soar-inline-details"><summary>{{ t('soar.advancedConditionsJson') }}</summary><el-input type="textarea" v-model="ruleForm.conditions" :readonly="!props.canWrite" :rows="3" spellcheck="false"  /></details>
        </div>
        <label>{{ t('forms.dedupWindow') }}<el-input-number :model-value="suppressionSettings.dedupWindowSeconds as number | undefined" :min="0" @change="value => updateSuppression('dedupWindowSeconds', value)" /></label>
        <label>{{ t('forms.conflictStrategy') }}<el-select :model-value="suppressionSettings.conflictStrategy as string | undefined" @change="value => updateSuppression('conflictStrategy', value)"><el-option v-for="strategy in ['QUEUE', 'SUPPRESS']" :key="strategy" :value="strategy" :label="strategy" /></el-select></label>
        <details><summary>{{ t('forms.advanced') }}</summary><el-input type="textarea" v-model="ruleForm.suppression" :rows="3" spellcheck="false" /></details>
        <div class="soar-form-actions"><el-button v-if="props.canWrite" type="primary" size="small" :loading="saving" @click="createRule">{{ t('common.save') }}</el-button></div>
      </div></el-drawer>
      <el-dialog v-model="showRuleTest" :title="t('forms.test')" width="720px"><div v-if="errorMessage" role="alert">{{ errorMessage }}</div><div class="soar-test-box"><el-input type="textarea" v-model="ruleEventText" :rows="3" spellcheck="false" :aria-label="t('soar.actionTestEvent')"  /><pre v-if="ruleTestResult">{{ JSON.stringify(ruleTestResult, null, 2) }}</pre></div><template #footer><el-button :loading="saving" @click="testRules">{{ t('forms.test') }}</el-button></template></el-dialog>
      <div class="soar-table-scroll"><table><thead><tr><th>{{ t('common.name') }}</th><th>{{ t('soar.triggerType') }}</th><th>{{ t('soar.priority') }}</th><th>{{ t('soar.revision') }}</th><th>{{ t('common.status') }}</th><th>{{ t('soar.publishedVersions') }}</th><th v-if="props.canWrite">{{ t('common.actions') }}</th></tr></thead><tbody><tr v-for="rule in rules" :key="rule.id"><td><b>{{ rule.name }}</b><small>{{ rule.id }}</small></td><td>{{ rule.triggerType }}</td><td>{{ rule.priority }}</td><td>{{ rule.revision || 1 }}</td><td><el-tag size="small" :type="rule.enabled ? 'success' : 'info'">{{ statusLabel(rule.enabled ? 'ENABLED' : 'DISABLED') }}</el-tag></td><td class="mono">{{ JSON.stringify(rule.actions) }}</td><td v-if="props.canWrite" class="nowrap"><el-button link size="small" @click="toggleRule(rule)">{{ rule.enabled ? t('common.disable') : t('common.enable') }}</el-button><el-button link size="small" @click="editRule(rule)">{{ t('common.edit') }}</el-button></td></tr></tbody></table><div v-if="!rules.length" class="soar-empty">{{ t('soar.noAutomationRules') }}</div></div>
    </section>

    <section v-else-if="tab === 'connections'" class="soar-control-section">
      <div class="soar-section-toolbar"><div><b>{{ t('soar.connectorAssets') }}</b><small>{{ t('soar.connectorAssetsHint') }}</small></div><div><el-button v-if="props.canWrite" size="small" @click="showConnectionForm = !showConnectionForm">{{ showConnectionForm ? t('soar.closeForm') : t('soar.newConnection') }}</el-button><details class="soar-inline-details"><summary>{{ t('soar.actionCatalog', { count: actions.length }) }}</summary><div class="soar-action-catalog"><span v-for="action in actions" :key="action.actionRef"><b>{{ action.actionRef }}</b><small>{{ action.riskLevel }} · {{ action.idempotency }} · {{ action.production ? t('soar.production') : t('soar.certificationRequired') }}</small></span></div></details></div></div>
      <el-dialog v-if="props.canWrite" v-model="showConnectionForm" :before-close="connectionGuard.beforeClose" :title="t('soar.tabConnections')" width="640px" :close-on-click-modal="false"><div v-if="errorMessage" role="alert" class="soar-feedback error">{{ errorMessage }}</div><div class="soar-form-grid">
        <label>{{ t('common.name') }}<el-input v-model="connectionForm.name" :placeholder="t('soar.connectionNamePlaceholder')" /></label>
        <label>{{ t('soar.connectorType') }}
          <el-select v-model="connectionForm.connectorType" filterable default-first-option :placeholder="t('soar.selectConnector')">
            <el-option v-for="connectorId in connectorTypeOptions" :key="connectorId" :label="connectorId" :value="connectorId" />
          </el-select>
        </label>
        <label>{{ t('soar.httpsEndpoint') }}<el-input v-model="connectionForm.endpoint" :placeholder="t('soar.endpointPlaceholder')" /></label>
        <label>{{ t('soar.secretRef') }}<el-input v-model="connectionForm.authSecretRef" :placeholder="t('soar.secretRefPlaceholder')" /></label>
        <label>{{ t('soar.allowedHosts') }}<el-input v-model="connectionForm.allowedHosts" :placeholder="t('soar.allowedHostsPlaceholder')" /></label>
        <label class="soar-checkbox"><el-switch v-model="connectionForm.enabled" /> {{ t('soar.enabledAfterCreate') }}</label>
        <div class="soar-form-actions"><el-button v-if="props.canWrite" type="primary" size="small" :loading="saving" @click="createConnection">{{ t('common.create') }}</el-button></div>
      </div></el-dialog>
      <div class="soar-table-scroll"><table><thead><tr><th>{{ t('common.name') }}</th><th>{{ t('common.type') }}</th><th>{{ t('soar.httpsEndpoint') }}</th><th>{{ t('common.status') }}</th><th>{{ t('soar.connectionTest') }}</th><th v-if="props.canWrite">{{ t('common.actions') }}</th></tr></thead><tbody><tr v-for="connection in connections" :key="connection.id"><td><b>{{ connection.name }}</b><small>{{ connection.id }}</small></td><td>{{ connection.connectorType }}</td><td class="mono">{{ connection.endpoint }}</td><td><el-tag size="small" :type="connection.status === 'HEALTHY' ? 'success' : connection.enabled ? 'warning' : 'info'">{{ statusLabel(connection.status) }}</el-tag></td><td>{{ connection.lastTestAt || '-' }}<small>{{ connection.lastTestError || '' }}</small></td><td v-if="props.canWrite" class="nowrap"><el-button link size="small" @click="testConnection(connection)">{{ t('soar.connectionTest') }}</el-button><el-button link size="small" @click="toggleConnection(connection)">{{ connection.enabled ? t('common.disable') : t('common.enable') }}</el-button><el-button link type="danger" size="small" @click="removeConnection(connection)">{{ t('common.delete') }}</el-button></td></tr></tbody></table><div v-if="!connections.length" class="soar-empty">{{ t('soar.noConnections') }}</div></div>
    </section>

    <section v-else-if="tab === 'tasks'" class="soar-control-section">
      <div class="soar-section-toolbar"><div><b>{{ t('soar.humanTasks') }}</b><small>{{ t('soar.humanTasksHint') }}</small></div></div>
      <div class="soar-table-scroll"><table><thead><tr><th>{{ t('forms.task') }}</th><th>{{ t('soar.runNode') }}</th><th>{{ t('forms.assign') }}</th><th>{{ t('soar.due') }}</th><th>{{ t('common.actions') }}</th></tr></thead><tbody><tr v-for="task in tasks" :key="task.id"><td><b>{{ task.id }}</b><small>{{ statusLabel(task.status) }}</small></td><td class="mono">{{ task.runId }} / {{ task.nodeId }}</td><td>{{ task.assignee || t('soar.anyApprover') }}</td><td>{{ task.dueAt || '-' }}</td><td><el-button size="small" type="primary" plain @click="openTask(task)">{{ t('forms.task') }}</el-button></td></tr></tbody></table><div v-if="!tasks.length" class="soar-empty">{{ t('soar.noPendingTasks') }}</div></div>
    </section>

    <section v-else class="soar-control-section">
      <div class="soar-stat-grid"><div><b>{{ stats?.dispatchBacklog ?? 0 }}</b><small>{{ t('soar.dispatchBacklog') }}</small></div><div><b>{{ stats?.signalBacklog ?? 0 }}</b><small>{{ t('soar.signalBacklog') }}</small></div><div><b>{{ deadLetters.length }}</b><small>{{ t('soar.deadLetters') }}</small></div><div><b>{{ Object.values(stats?.runsByStatus || {}).reduce((sum, value) => sum + value, 0) }}</b><small>{{ t('soar.projectedRuns') }}</small></div></div>
      <div class="soar-section-toolbar"><div><b>{{ t('soar.deadLetterOperations') }}</b><small>{{ t('soar.deadLetterHint') }}</small></div></div>
      <div class="soar-table-scroll"><table><thead><tr><th>{{ t('common.type') }}</th><th>{{ t('soar.runNode').split(' / ')[0] }}</th><th>{{ t('soar.signalKey') }}</th><th>{{ t('soar.attempts') }}</th><th>{{ t('soar.lastError') }}</th><th v-if="props.canWrite">{{ t('common.actions') }}</th></tr></thead><tbody><tr v-for="letter in deadLetters" :key="`${letter.kind}-${letter.id}`"><td>{{ letter.kind || 'DISPATCH' }}<small>{{ letter.signalType || '' }}</small></td><td class="mono">{{ letter.runId }}</td><td class="mono">{{ letter.signalKey || '-' }}</td><td>{{ letter.attempts }}</td><td>{{ letter.lastError || '-' }}</td><td v-if="props.canWrite" class="nowrap"><el-button link size="small" @click="requeue(letter)">{{ t('soar.requeue') }}</el-button><el-button link type="danger" size="small" @click="discard(letter)">{{ t('soar.discard') }}</el-button></td></tr></tbody></table><div v-if="!deadLetters.length" class="soar-empty">{{ t('soar.noDeadLetters') }}</div></div>
    </section>
    <el-drawer v-model="taskOpen" :before-close="taskGuard.beforeClose" :title="t('forms.task')" size="min(680px, 96vw)" :close-on-click-modal="false">
      <template v-if="selectedTask"><p>{{ selectedTask.runId }} · {{ selectedTask.nodeId }}</p><div v-if="errorMessage" role="alert" class="soar-feedback error">{{ errorMessage }}</div><SchemaInputForm :key="selectedTask.id" v-model="taskValue" :schema="selectedTask.formSchema" :disabled="saving" @valid="taskValid = $event" /></template>
      <template #footer><el-button v-if="props.canWrite && selectedTask" type="primary" :loading="saving" :disabled="!taskValid" @click="completeTask(selectedTask)">{{ t('forms.complete') }}</el-button></template>
    </el-drawer>
  </el-card>
</template>

<style scoped>
.soar-control-plane { margin-top: 16px; border: 1px solid var(--ns-border); }
.soar-control-header, .soar-section-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 14px; }
.soar-subtitle, .soar-section-toolbar small { display: block; margin-top: 4px; color: var(--ns-text-3); font-size: 13px; }
.soar-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--ns-border); margin-bottom: 12px; }
.soar-tabs button { border: 0; border-bottom: 2px solid transparent; padding: 8px 11px; background: transparent; color: var(--ns-text-2); cursor: pointer; font: inherit; font-size: 13px; }
.soar-tabs button:hover, .soar-tabs button.active { border-bottom-color: var(--ns-accent); color: var(--ns-accent); }
.soar-feedback { margin: 8px 0; padding: 7px 10px; border-radius: 5px; font-size: 13px; }.soar-feedback.success { color: var(--ns-success); background: color-mix(in srgb, var(--ns-success) 9%, transparent); }.soar-feedback.error { color: var(--ns-danger); background: color-mix(in srgb, var(--ns-danger) 9%, transparent); }
.soar-control-section { min-width: 0; }.soar-section-toolbar { margin-bottom: 10px; }.soar-section-toolbar > div:first-child { min-width: 0; }
.soar-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; margin: 10px 0; padding: 10px; border: 1px solid var(--ns-border); border-radius: 5px; background: var(--ns-bg-subtle); }
.soar-form-grid label { display: flex; flex-direction: column; gap: 4px; color: var(--ns-text-3); font-size: 13px; }.soar-form-grid input, .soar-form-grid textarea, .soar-form-grid .el-select, .soar-test-box textarea, .soar-task-input { width: 100%; box-sizing: border-box; border: 1px solid var(--ns-border); border-radius: 4px; padding: 6px 7px; background: var(--ns-bg); color: var(--ns-text); font: inherit; font-size: 13px; }.soar-form-grid .el-select { padding: 0; border: 0; }.soar-form-grid textarea, .soar-test-box textarea { resize: vertical; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }.soar-form-actions { align-self: end; }.soar-checkbox { justify-content: flex-end; flex-direction: row !important; align-items: center; gap: 7px !important; }.soar-checkbox input { width: auto; }.soar-version-option { display: flex; flex-direction: column; gap: 2px; line-height: 1.25; }.soar-version-option small, .soar-field-warning { color: var(--ns-warning); font-size: 12px; }
.soar-test-box { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 9px; margin-bottom: 10px; }.soar-test-box pre { max-height: 100px; margin: 0; overflow: auto; padding: 7px; border: 1px solid var(--ns-border); border-radius: 4px; font-size: 13px; }
.soar-table-scroll { max-height: 330px; overflow: auto; }.soar-control-plane table { width: 100%; border-collapse: collapse; font-size: 13px; }.soar-control-plane th, .soar-control-plane td { padding: 7px 6px; border-bottom: 1px solid var(--ns-border); text-align: left; vertical-align: top; }.soar-control-plane th { color: var(--ns-text-3); font-size: 12px; text-transform: uppercase; }.soar-control-plane td b, .soar-control-plane td small { display: block; }.soar-control-plane td small { margin-top: 2px; color: var(--ns-text-3); font-size: 12px; }.mono { max-width: 300px; overflow-wrap: anywhere; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }.nowrap { white-space: nowrap; }.soar-empty { padding: 10px 0; color: var(--ns-text-3); font-size: 13px; }
.soar-inline-details { position: relative; display: inline-block; margin-left: 6px; color: var(--ns-text-2); font-size: 13px; }.soar-inline-details summary { cursor: pointer; }.soar-action-catalog { position: absolute; z-index: 2; right: 0; top: 22px; display: grid; width: min(520px, 80vw); max-height: 240px; overflow: auto; gap: 6px; padding: 9px; border: 1px solid var(--ns-border); border-radius: 5px; background: var(--ns-bg); box-shadow: 0 5px 20px rgb(0 0 0 / 16%); }.soar-action-catalog span { display: flex; justify-content: space-between; gap: 10px; }.soar-action-catalog small { color: var(--ns-text-3); }.soar-task-input { min-width: 180px; }.soar-stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 14px; }.soar-stat-grid > div { padding: 10px; border: 1px solid var(--ns-border); border-radius: 5px; background: var(--ns-bg-subtle); }.soar-stat-grid b, .soar-stat-grid small { display: block; }.soar-stat-grid b { font-size: 20px; }.soar-stat-grid small { margin-top: 3px; color: var(--ns-text-3); font-size: 13px; }
@media (max-width: 850px) { .soar-form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.soar-test-box { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .soar-control-header, .soar-section-toolbar { align-items: flex-start; flex-direction: column; }.soar-form-grid, .soar-stat-grid { grid-template-columns: 1fr; }.soar-tabs { overflow-x: auto; }.soar-tabs button { white-space: nowrap; } }
</style>
