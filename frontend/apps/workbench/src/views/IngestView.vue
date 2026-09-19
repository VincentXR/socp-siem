<script setup lang="ts">
import { useWriteAccess } from '../composables/useWriteAccess'
const canWrite = useWriteAccess()
import { useFormDialog } from '../composables/useFormDialog'
import { useRoute, useRouter } from 'vue-router'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
const route = useRoute()
const router = useRouter()

import { useMutation } from '../composables/useMutation'
import { useConfirm } from '../composables/useConfirm'
import ActionFeedback from '../components/ActionFeedback.vue'
import FormField from '../components/FormField.vue'
import FormGrid from '../components/FormGrid.vue'
import FormSection from '../components/FormSection.vue'
const mutation = useMutation()
const { busy: actionBusy, error: actionError } = mutation
const { confirmDanger } = useConfirm()
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/input-number/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElInputNumber from 'element-plus/es/components/input-number/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { onMounted, ref } from 'vue'
import {
  ApiError,
  createOutput, createSource, deleteOutput, deleteParseRule, deleteSource, updateSource,
  ingestSummary, listCategories, listIngestTasks, listOutputs, listParseRules, listSources,
  previewParse, renderConfig, startIngestTask, stopIngestTask,
  SOURCE_TYPES, PARSE_FORMATS,
  type IngestTask, type IngestSummary, type LogCategory, type LogSource, type LogSourceInput, type ParseRule, type SinkTarget,
} from '../api'
import { useI18n } from '../composables/useI18n'
import { fmtBytes, fmtTime } from '../lib/ui'
import PageHeader from '../components/PageHeader.vue'

const { t } = useI18n()
const ingestTab = ref(String(route.query.tab || 'tasks'))
const sources = ref<LogSource[]>([])
const outputs = ref<SinkTarget[]>([])
const parseRules = ref<ParseRule[]>([])
const logCategories = ref<LogCategory[]>([])
const newSource = ref({ name: '', type: 'FILE', format: 'AUTO', path: '', address: '', topic: '', env: 'local', readFrom: 'beginning', multiline: '', protocol: 'tcp', charset: 'utf-8', timezone: 'Asia/Shanghai', tags: '', frequency: 1 as number | null, categoryId: '', groupId: '', sinkTargetId: '', parseRuleIds: [] as string[], enabled: true })
const newOutput = ref({ name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true })
const sourceErrors = ref<Record<string, string>>({})
const outputErrors = ref<Record<string, string>>({})
/** Background list refresh, the dialogs, and page actions keep separate errors. */
const loadError = ref('')
const sourceError = ref('')
const outputError = ref('')
const renderError = ref('')
const renderText = ref('')
const showRender = ref(false)
const showSourceDialog = ref(false)
const showOutputDialog = ref(false)
const editingSourceId = ref<string | null>(null)

const tasks = ref<IngestTask[]>([])
const taskSummary = ref<IngestSummary | null>(null)
const taskBusy = ref<Record<string, boolean>>({})
const testDialog = ref(false)
const testTarget = ref<IngestTask | null>(null)
const testSample = ref('')
type ParsePreviewAttempt = { ruleId?: string; rule?: string; format?: string; matched: boolean; error?: string }
type ParsePreviewResult = { ok: boolean; matched: boolean; sample: string; rule?: string; format?: string; fields: Record<string, string>; attempts?: ParsePreviewAttempt[]; error?: string }
const testResult = ref<ParsePreviewResult | null>(null)
const testLoading = ref(false)

type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'
const HEALTH_KEYS: Record<string, string> = {
  HEALTHY: 'common.healthy', DEGRADED: 'common.degraded', STALE: 'common.stale',
  IDLE: 'common.idle', ERROR: 'common.error', DISABLED: 'common.disabled',
}
function healthMeta(health: string) {
  const type: TagType = health === 'HEALTHY' ? 'success' : health === 'ERROR' ? 'danger' : health === 'DEGRADED' || health === 'STALE' ? 'warning' : 'info'
  return { text: HEALTH_KEYS[health] ? t(HEALTH_KEYS[health]) : health, type }
}
function outputLabel(id: string | null | undefined): string {
  if (!id) return t('ingest.disabledDefault')
  const output = outputs.value.find(item => item.id === id)
  return output ? `${output.name} · ${output.id}` : id
}

function reportLoadFailure(text: string): void {
  loadError.value = loadError.value ? `${loadError.value} · ${text}` : text
}

/** Moves a failure out of the shared action slot into the surface that owns it. */
function isolateError(target: { value: string }, completed: boolean): void {
  if (completed) { target.value = ''; return }
  target.value = actionError.value
  actionError.value = ''
}

async function loadSources() { try { sources.value = await listSources() } catch (failure) { reportLoadFailure(String(failure)) } }
async function loadOutputs() { try { outputs.value = await listOutputs() } catch (failure) { reportLoadFailure(String(failure)) } }
async function loadParseRules() { try { parseRules.value = await listParseRules() } catch (failure) { reportLoadFailure(String(failure)) } }
async function loadTasks() {
  const [taskResult, summaryResult] = await Promise.allSettled([listIngestTasks(), ingestSummary()])
  if (taskResult.status === 'fulfilled') tasks.value = taskResult.value
  else reportLoadFailure(String(taskResult.reason))
  taskSummary.value = summaryResult.status === 'fulfilled' ? summaryResult.value : null
}
function onIngestTab(key: string | number) {
  const tab = String(key)
  ingestTab.value = tab
  if (tab === 'sources') loadSources()
  if (tab === 'outputs') loadOutputs()
  if (tab === 'rules') loadParseRules()
  if (tab === 'tasks') loadTasks()
}

const EMPTY_SOURCE = { name: '', type: 'FILE', format: 'AUTO', path: '', address: '', topic: '', env: 'local', readFrom: 'beginning', multiline: '', protocol: 'tcp', charset: 'utf-8', timezone: 'Asia/Shanghai', tags: '', frequency: 1, categoryId: '', groupId: '', sinkTargetId: '', parseRuleIds: [] as string[], enabled: true }

function openCreateSource() {
  if (!canWrite.value) return
  editingSourceId.value = null
  newSource.value = { ...EMPTY_SOURCE }
  sourceErrors.value = {}
  sourceError.value = ''
  actionError.value = ''
  showSourceDialog.value = true
}
function openEditSource(source: LogSource) {
  if (!canWrite.value) return
  editingSourceId.value = source.id
  newSource.value = {
    name: source.name, type: source.type || 'FILE', format: source.format || 'AUTO',
    path: source.path || '', address: source.address || '', topic: source.topic || '', env: source.env || 'local',
    readFrom: source.readFrom || 'beginning', multiline: source.multiline || '', protocol: source.protocol || 'tcp',
    charset: source.charset || 'utf-8', timezone: source.timezone || 'Asia/Shanghai', tags: (source.tags || []).join(','),
    frequency: source.frequency ?? 1, categoryId: source.categoryId || '', groupId: source.groupId || '',
    sinkTargetId: source.sinkTargetId || '',
    parseRuleIds: [...(source.parseRuleIds || [])], enabled: source.enabled,
  }
  sourceErrors.value = {}
  sourceError.value = ''
  actionError.value = ''
  showSourceDialog.value = true
}

/** Required fields only; empty optional targets are submitted as empty. */
function validateSource(): boolean {
  sourceErrors.value = {}
  if (!newSource.value.name.trim()) sourceErrors.value.name = t('forms.fieldRequired', { field: t('ingest.sourceName') })
  return !Object.keys(sourceErrors.value).length
}

function validateOutput(): boolean {
  outputErrors.value = {}
  if (!newOutput.value.name.trim()) outputErrors.value.name = t('forms.fieldRequired', { field: t('ingest.outputName') })
  const uri = newOutput.value.uri.trim()
  if (!uri) outputErrors.value.uri = t('forms.fieldRequired', { field: t('ingest.targetUrl') })
  else {
    try {
      const url = new URL(uri)
      if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) outputErrors.value.uri = t('forms.invalidInput')
    } catch { outputErrors.value.uri = t('forms.invalidInput') }
  }
  return !Object.keys(outputErrors.value).length
}

async function saveSource() {
  if (!canWrite.value || !validateSource()) return
  sourceError.value = ''
  isolateError(sourceError, await mutation.run(async () => {
  const source: LogSourceInput = {
    name: newSource.value.name.trim(), type: newSource.value.type, format: newSource.value.format,
    env: newSource.value.env, enabled: newSource.value.enabled, readFrom: newSource.value.readFrom,
    protocol: newSource.value.protocol, charset: newSource.value.charset, timezone: newSource.value.timezone,
    frequency: newSource.value.frequency, groupId: newSource.value.groupId || null,
    categoryId: newSource.value.categoryId || null,
    sinkTargetId: newSource.value.sinkTargetId || null,
    parseRuleIds: newSource.value.parseRuleIds,
  }
  if (newSource.value.multiline.trim()) source.multiline = newSource.value.multiline.trim()
  if (newSource.value.tags.trim()) source.tags = newSource.value.tags.split(/[,\uFF0C\s]+/).filter(Boolean)
  // An empty target stays empty: inventing a demo path handed out a source that
  // looked configured while reading a file that only exists in the repository.
  if (newSource.value.type === 'FILE') source.path = newSource.value.path.trim()
  if (newSource.value.type === 'SOCKET' || newSource.value.type === 'SYSLOG') source.address = newSource.value.address.trim()
  if (newSource.value.type === 'KAFKA') source.topic = newSource.value.topic.trim()
  if (editingSourceId.value) await updateSource(editingSourceId.value, source)
  else await createSource(source)
  editingSourceId.value = null
  showSourceDialog.value = false
  await loadSources()
  }))
}
async function removeSource(id: string) {
  if (!canWrite.value) return
  if (!await confirmDanger(t('ingest.deleteSourceConfirm'))) return
  return mutation.run(async () => {
  await deleteSource(id)
  await loadSources()
  })
}
/**
 * Turns a failed vector.toml render into actionable, bilingual guidance.
 * The backend copy is Chinese-only and hard-coded, so the two known failure
 * modes (no output target -> 409, viewer role -> 403) are mapped to locale
 * keys instead of surfacing a message a non-Chinese operator cannot act on.
 * Any other status keeps the already-localized transport message untouched.
 */
function renderFailureHint(failure: unknown): string {
  if (failure instanceof ApiError && failure.status === 409) return t('ingest.renderNoSinkHint')
  if (failure instanceof ApiError && failure.status === 403) return t('ingest.renderForbiddenHint')
  return failure instanceof Error ? failure.message : String(failure)
}
async function doRender() {
  renderError.value = ''
  renderText.value = ''
  showRender.value = false
  let failure: unknown
  const completed = await mutation.run(async () => {
    try { renderText.value = await renderConfig(); showRender.value = true }
    catch (error) { failure = error; throw error }
  })
  // A false return with no captured error means the shared action slot was busy:
  // leave the pending operation's own feedback untouched rather than showing 'undefined'.
  if (!completed && failure === undefined) return
  if (completed) return
  renderError.value = renderFailureHint(failure)
  // The render surface owns this failure; keep it out of the shared action slot.
  actionError.value = ''
}
async function copyRender() {
  renderError.value = ''
  isolateError(renderError, await mutation.run(async () => { await navigator.clipboard.writeText(renderText.value) }))
}
function openCreateOutput() {
  if (!canWrite.value) return
  newOutput.value = { name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true }
  outputErrors.value = {}
  outputError.value = ''
  actionError.value = ''
  showOutputDialog.value = true
}
async function addOutput() {
  if (!canWrite.value || !validateOutput()) return
  outputError.value = ''
  isolateError(outputError, await mutation.run(async () => {
  await createOutput({ name: newOutput.value.name.trim(), type: newOutput.value.type, uri: newOutput.value.uri.trim(), authToken: newOutput.value.authToken || null, enabled: newOutput.value.enabled })
  newOutput.value = { name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true }
  showOutputDialog.value = false
  await loadOutputs()
  }))
}
async function removeOutput(id: string) {
  if (!canWrite.value) return
  if (!await confirmDanger(t('ingest.deleteOutputConfirm'))) return
  return mutation.run(async () => {
  await deleteOutput(id)
  await loadOutputs()
  })
}
async function removeParseRule(id: string) {
  if (!canWrite.value) return
  if (!await confirmDanger(t('ingest.deleteRuleConfirm'))) return
  return mutation.run(async () => {
  await deleteParseRule(id)
  await loadParseRules()
  })
}

async function toggleTask(task: IngestTask) {
  if (!canWrite.value) return
  taskBusy.value = { ...taskBusy.value, [task.id]: true }
  try { task.enabled ? await stopIngestTask(task.id) : await startIngestTask(task.id); await loadTasks() }
  catch (failure) { actionError.value = failure instanceof Error ? failure.message : String(failure) }
  finally { taskBusy.value = { ...taskBusy.value, [task.id]: false } }
}
function openTest(task: IngestTask) {
  if (!canWrite.value) return
  actionError.value = ''
  testTarget.value = task; testSample.value = ''; testResult.value = null; testDialog.value = true
}
function toggleTaskRow(row: unknown) { toggleTask(row as IngestTask) }
function openTestRow(row: unknown) { openTest(row as IngestTask) }
function defaultPreviewSample(task: IngestTask): string {
  return JSON.stringify({
    collector: task.collector,
    host: 'preview-host',
    source: 'auth',
    severity: 'HIGH',
    message: 'Failed password for invalid user admin',
    user: 'admin',
  }, null, 2)
}
async function runTest() {
  if (!canWrite.value || !testTarget.value) return
  testLoading.value = true
  const sample = testSample.value.trim() || defaultPreviewSample(testTarget.value)
  const ruleIds = testTarget.value.parseRuleIds?.length ? testTarget.value.parseRuleIds : [undefined]
  try {
    const attempts = await Promise.all(ruleIds.map(async ruleId => {
      try {
        const result = await previewParse({ ruleId, format: ruleId ? undefined : testTarget.value?.format || 'AUTO', line: sample })
        return { ruleId, rule: result.rule, format: result.format, matched: result.matched, error: result.error, fields: result.fields }
      } catch (error) {
        return { ruleId, matched: false, error: error instanceof Error ? error.message : String(error), fields: {} }
      }
    }))
    const selected = attempts.find(attempt => attempt.matched) ?? attempts[0]
    testResult.value = {
      ok: Boolean(selected?.matched), matched: Boolean(selected?.matched), sample,
      rule: selected?.rule, format: selected?.format, fields: selected?.fields ?? {},
      error: selected?.matched ? undefined : selected?.error,
      attempts: attempts.length > 1 ? attempts.map(({ fields: _fields, ...attempt }) => attempt) : undefined,
    }
  } catch (error) {
    testResult.value = { ok: false, matched: false, sample, fields: {}, error: String(error) }
  }
  finally { testLoading.value = false }
}

const showSourceDialogGuard = useFormDialog(showSourceDialog, () => newSource.value, () => actionBusy.value)
const showOutputDialogGuard = useFormDialog(showOutputDialog, () => newOutput.value, () => actionBusy.value)
async function refreshAll() {
  actionError.value = ''
  loadError.value = ''
  await Promise.allSettled([loadSources(), loadOutputs(), loadParseRules(), loadTasks(), listCategories().then(result => { logCategories.value = result })])
}
onMounted(async () => {
  await refreshAll()
})
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback :error="loadError" />
    <ActionFeedback :error="actionError" />
    <PageHeader :eyebrow="t('menuGroup.ingestAndConfig')" :title="t('ingest.title')" :description="t('ingest.description')">
      <template #actions>
        <el-button size="small" :loading="actionBusy" @click="refreshAll">{{ t('common.refresh') }}</el-button>
      </template>
    </PageHeader>
    <el-tabs v-model="ingestTab" @tab-change="onIngestTab">
      <el-tab-pane :label="t('ingest.tasks')" name="tasks">
        <el-row class="metrics-row" :gutter="12" style="margin-bottom:14px">
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ taskSummary?.enabledSources ?? 0 }}/{{ taskSummary?.sources ?? 0 }}</div><div class="label">{{ t('ingest.runningTotal') }}</div></div></el-card></el-col>
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-accent-fg)">{{ taskSummary?.eps1m ?? 0 }}</div><div class="label">{{ t('ingest.eps') }}</div></div></el-card></el-col>
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-success)">{{ taskSummary?.accepted ?? 0 }}</div><div class="label">{{ t('ingest.accepted') }}</div></div></el-card></el-col>
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ taskSummary?.forwarded ?? 0 }}</div><div class="label">{{ t('ingest.forwarded') }}</div></div></el-card></el-col>
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num" :style="{ color: (taskSummary?.skipped ?? 0) > 0 ? 'var(--ns-warning)' : 'var(--ns-text-3)' }">{{ taskSummary?.skipped ?? 0 }}</div><div class="label">{{ t('ingest.skipped') }}</div></div></el-card></el-col>
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ fmtBytes(taskSummary?.bytes ?? 0) }}</div><div class="label">{{ t('ingest.cumulativeBytes') }}</div></div></el-card></el-col>
        </el-row>
        <el-card shadow="never">
          <template #header><div style="display:flex;align-items:center;gap:10px"><span>{{ t('ingest.taskConfigMetrics') }}</span><el-tag v-for="(count, health) in (taskSummary?.byHealth ?? {})" :key="health" size="small" :type="healthMeta(String(health)).type" style="margin-left:2px">{{ healthMeta(String(health)).text }} {{ count }}</el-tag><el-button size="small" style="margin-left:auto" @click="loadTasks">{{ t('common.refresh') }}</el-button></div></template>
          <el-table :data="tasks" size="small" border>
            <el-table-column :label="t('ingest.status')" width="92"><template #default="{ row }"><el-tag :type="healthMeta(row.runtime.health).type" size="small" effect="dark">{{ healthMeta(row.runtime.health).text }}</el-tag></template></el-table-column>
            <el-table-column :label="t('ingest.task')" min-width="150" show-overflow-tooltip><template #default="{ row }"><div style="font-weight:600">{{ row.name }}</div><div class="mono" style="font-size:11px;color:var(--ns-text-3)">{{ row.collector }}</div></template></el-table-column>
            <el-table-column prop="type" :label="t('ingest.ingestMethod')" width="110" />
            <el-table-column prop="format" :label="t('ingest.parseFormat')" width="90" />
            <el-table-column :label="t('ingest.target')" min-width="180" show-overflow-tooltip><template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.target }}</span></template></el-table-column>
            <el-table-column :label="t('ingest.epsWindow')" width="110"><template #default="{ row }"><span :style="{ color: row.runtime.eps1m > 0 ? 'var(--ns-success)' : 'var(--ns-text-3)', fontWeight: 600 }">{{ row.runtime.eps1m }}</span><span style="color:var(--ns-text-3)"> / {{ row.runtime.eps5m }}</span></template></el-table-column>
            <el-table-column :label="t('ingest.receivedForwardedSkipped')" width="150"><template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.runtime.accepted }} / {{ row.runtime.forwarded }} / <span :style="{ color: row.runtime.skipped > 0 ? 'var(--ns-warning)' : 'inherit' }">{{ row.runtime.skipped }}</span></span></template></el-table-column>
            <el-table-column :label="t('ingest.recentData')" width="150"><template #default="{ row }"><span class="mono" style="font-size:12px">{{ fmtTime(row.runtime.lastAt) }}</span></template></el-table-column>
            <el-table-column :label="t('ingest.actions')" width="170"><template #default="{ row }"><el-button v-if="canWrite" link :type="row.enabled ? 'warning' : 'success'" size="small" :loading="taskBusy[row.id]" @click="toggleTaskRow(row)">{{ row.enabled ? t('ingest.stop') : t('ingest.start') }}</el-button><el-button v-if="canWrite" link type="primary" size="small" @click="openTestRow(row)">{{ t('ingest.parsePreview') }}</el-button></template></el-table-column>
            <el-table-column type="expand"><template #default="{ row }"><div style="padding:8px 20px;font-size:12px;color:var(--ns-text-2)"><div>{{ t('ingest.environmentDetail', { value: row.env || t('time.notAvailable') }) }} · {{ t('ingest.categoryDetail', { value: row.categoryId || t('time.notAvailable') }) }} · {{ t('ingest.outputDetail', { value: outputLabel(row.sinkTargetId) }) }} · {{ t('ingest.createdDetail', { value: fmtTime(row.createdAt) }) }}</div><div style="margin-top:4px">{{ t('ingest.boundRules') }}<el-tag v-for="p in row.parseRuleIds" :key="p" size="small" style="margin-right:4px">{{ p }}</el-tag><span v-if="!row.parseRuleIds?.length" style="color:var(--ns-text-3)">{{ t('ingest.autoDetect') }}</span></div><div v-if="row.runtime.lastError" style="margin-top:4px;color:var(--ns-danger)">{{ t('ingest.recentError', { time: fmtTime(row.runtime.lastErrorAt ?? null) }) }}{{ row.runtime.lastError }}</div></div></template></el-table-column>
          </el-table>
        </el-card>
        <el-dialog v-model="testDialog" :title="t('ingest.parsePreviewTitle', { name: testTarget?.name ?? '' })" width="720px">
          <div style="font-size:12px;color:var(--ns-text-3);margin-bottom:8px">{{ t('ingest.parsePreviewDescription') }}</div>
          <el-input v-model="testSample" type="textarea" :rows="4" :placeholder="t('ingest.testSamplePlaceholder')" />
          <div v-if="testResult" style="margin-top:12px"><el-alert :type="testResult.ok ? 'success' : 'error'" :closable="false" :title="t(testResult.ok ? 'ingest.parsePreviewPassed' : 'ingest.parsePreviewFailed')" /><div v-if="Object.keys(testResult.fields).length" class="parse-preview-fields"><span v-for="(value, field) in testResult.fields" :key="field"><b>{{ field }}</b><code>{{ value }}</code></span></div><pre class="mono test-out">{{ JSON.stringify(testResult, null, 2) }}</pre></div>
          <template #footer><el-button @click="testDialog = false">{{ t('ingest.close') }}</el-button><el-button type="primary" :loading="testLoading" @click="runTest">{{ t('ingest.runParsePreview') }}</el-button></template>
        </el-dialog>
      </el-tab-pane>

      <el-tab-pane :label="t('ingest.sourcesTab')" name="sources">
        <div class="add-bar"><el-button v-if="canWrite" type="primary" @click="openCreateSource">+ {{ t('ingest.addSource') }}</el-button><el-button @click="loadSources">{{ t('ingest.refresh') }}</el-button><el-button type="primary" plain @click="doRender">{{ t('ingest.renderConfig') }}</el-button><span class="hint">{{ t('ingest.sourceHint') }}</span></div>
        <ActionFeedback :error="renderError" />
        <el-drawer v-model="showSourceDialog" :before-close="showSourceDialogGuard.beforeClose" :title="editingSourceId ? t('ingest.editSource') : t('ingest.addSource')" size="min(760px, 96vw)" :close-on-click-modal="false"><ActionFeedback :error="sourceError" />
          <el-form label-position="top" :disabled="actionBusy">
            <FormSection index="01" :title="t('ingest.sourceBasics')" :hint="t('ingest.sourceBasicsHint')">
              <FormGrid :columns="2">
                <FormField :label="t('ingest.sourceName')" required :error="sourceErrors.name">
                  <el-input v-model="newSource.name" :placeholder="t('ingest.sourceNamePlaceholder')" />
                </FormField>
                <FormField :label="t('ingest.ingestMethod')" :hint="t('ingest.ingestMethodHint')">
                  <el-select v-model="newSource.type" :placeholder="t('ingest.ingestMethodPlaceholder')"><el-option v-for="type in SOURCE_TYPES" :key="type" :label="type" :value="type" /></el-select>
                </FormField>
                <FormField :label="t('ingest.parseFormat')">
                  <el-select v-model="newSource.format" :placeholder="t('ingest.parseFormatPlaceholder')"><el-option v-for="format in PARSE_FORMATS" :key="format" :label="format" :value="format" /></el-select>
                </FormField>
                <FormField :label="t('ingest.category')">
                  <el-select v-model="newSource.categoryId" filterable default-first-option clearable :placeholder="t('meta.authPlaceholder')"><el-option v-for="category in logCategories" :key="category.id" :label="category.code + ' ' + category.name" :value="category.id" /></el-select>
                </FormField>
                <FormField :label="t('ingest.boundRules')" full>
                  <el-select v-model="newSource.parseRuleIds" multiple collapse-tags filterable default-first-option :placeholder="t('ingest.autoDetect')"><el-option v-for="rule in parseRules" :key="rule.id" :label="rule.name" :value="rule.id" /></el-select>
                </FormField>
                <FormField :label="t('ingest.outputTarget')">
                  <el-select v-model="newSource.sinkTargetId" clearable filterable :placeholder="t('ingest.outputTargetPlaceholder')"><el-option :label="t('ingest.disabledDefault')" value="" /><el-option v-if="newSource.sinkTargetId && !outputs.some(output => output.id === newSource.sinkTargetId)" :label="newSource.sinkTargetId" :value="newSource.sinkTargetId" /><el-option v-for="output in outputs" :key="output.id" :label="`${output.name} · ${output.type}`" :value="output.id" /></el-select>
                </FormField>
                <FormField :label="t('ingest.environment')">
                  <el-input v-model="newSource.env" :placeholder="t('ingest.environmentPlaceholder')" />
                </FormField>
                <FormField :label="t('common.enabled')">
                  <el-switch v-model="newSource.enabled" />
                </FormField>
              </FormGrid>
            </FormSection>
            <FormSection v-if="newSource.type === 'FILE'" index="02" :title="t('ingest.sourceAccess')" :hint="t('ingest.fileAccessHint')">
              <FormGrid :columns="2">
                <FormField :label="t('ingest.multilineLabel')" :hint="t('ingest.multilineHint')" full>
                  <el-input v-model="newSource.multiline" type="textarea" :rows="2" :placeholder="t('ingest.multilinePlaceholder')" />
                </FormField>
                <FormField :label="t('ingest.filePath')"><el-input v-model="newSource.path" :placeholder="t('ingest.filePathPlaceholder')" /></FormField>
                <FormField :label="t('ingest.readFrom')"><el-select v-model="newSource.readFrom"><el-option :label="t('ingest.readFromBeginning')" value="beginning" /><el-option :label="t('ingest.readFromEnd')" value="end" /></el-select></FormField>
                <FormField :label="t('ingest.frequency')"><el-input-number v-model="newSource.frequency" :min="1" controls-position="right" /></FormField>
              </FormGrid>
            </FormSection>
            <FormSection v-else-if="newSource.type === 'SOCKET' || newSource.type === 'SYSLOG'" index="02" :title="t('ingest.sourceAccess')" :hint="t('ingest.socketAccessHint')">
              <FormGrid :columns="2">
                <FormField :label="t('ingest.listenAddress')"><el-input v-model="newSource.address" :placeholder="t('ingest.listenAddressPlaceholder')" /></FormField>
                <FormField :label="t('ingest.protocol')"><el-select v-model="newSource.protocol" :placeholder="t('ingest.protocol')"><el-option label="UDP" value="udp" /><el-option label="TCP" value="tcp" /><el-option label="TLS" value="tls" /></el-select></FormField>
              </FormGrid>
            </FormSection>
            <FormSection v-else-if="newSource.type === 'KAFKA'" index="02" :title="t('ingest.sourceAccess')" :hint="t('ingest.kafkaAccessHint')">
              <FormGrid :columns="2">
                <FormField :label="t('ingest.topic')"><el-input v-model="newSource.topic" :placeholder="t('ingest.topicPlaceholder')" /></FormField>
                <FormField :label="t('ingest.groupId')"><el-input v-model="newSource.groupId" :placeholder="t('ingest.groupIdPlaceholder')" /></FormField>
              </FormGrid>
            </FormSection>
          <div v-else-if="['WINDOWS_EVENT', 'AGENT', 'HTTP_API', 'DATABASE', 'CLOUD'].includes(newSource.type)" style="margin-top:10px"><el-alert type="info" :closable="false" :title="t('ingest.collectorInfo', { type: newSource.type })" /></div>
            <FormSection index="03" :title="t('ingest.encodingLabels')" :hint="t('ingest.encodingLabelsHint')">
              <FormGrid :columns="2">
                <FormField :label="t('ingest.charset')"><el-select v-model="newSource.charset" :placeholder="t('ingest.charsetPlaceholder')"><el-option label="UTF-8" value="utf-8" /><el-option label="GBK" value="gbk" /><el-option label="ISO-8859-1" value="iso-8859-1" /></el-select></FormField>
                <FormField :label="t('ingest.timezone')"><el-select v-model="newSource.timezone" :placeholder="t('ingest.timezonePlaceholder')"><el-option label="Asia/Shanghai" value="Asia/Shanghai" /><el-option label="UTC" value="UTC" /><el-option label="Asia/Tokyo" value="Asia/Tokyo" /></el-select></FormField>
                <FormField :label="t('ingest.tags')" :hint="t('ingest.tagsHint')" full><el-input v-model="newSource.tags" :placeholder="t('ingest.tagsPlaceholder')" /></FormField>
              </FormGrid>
            </FormSection>
          </el-form>
          <template #footer><el-button @click="showSourceDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="saveSource">{{ editingSourceId ? t('common.save') : t('ingest.addSource') }}</el-button></template>
        </el-drawer>
        <el-card shadow="never"><el-table :data="sources" size="small" border><el-table-column prop="name" :label="t('common.name')" width="130" show-overflow-tooltip /><el-table-column prop="type" :label="t('common.type')" width="110" /><el-table-column prop="format" :label="t('ingest.parseFormat')" width="80" /><el-table-column :label="t('ingest.target')" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.path || row.address || row.topic || t('time.notAvailable') }}</template></el-table-column><el-table-column :label="t('ingest.protocol')" width="70"><template #default="{ row }">{{ row.protocol || t('time.notAvailable') }}</template></el-table-column><el-table-column prop="env" :label="t('ingest.environment')" width="65" /><el-table-column :label="t('common.enabled')" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.yes') : t('common.no') }}</el-tag></template></el-table-column><el-table-column v-if="canWrite" :label="t('common.actions')" width="120"><template #default="{ row }"><el-button link type="primary" size="small" @click="openEditSource(row as LogSource)">{{ t('common.edit') }}</el-button><el-button link type="danger" size="small" @click="removeSource(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('ingest.outputTab')" name="outputs">
        <div class="add-bar"><el-button v-if="canWrite" type="primary" @click="openCreateOutput">+ {{ t('ingest.addOutput') }}</el-button><span class="hint">{{ t('ingest.outputHint') }}</span></div>
        <el-dialog v-model="showOutputDialog" :before-close="showOutputDialogGuard.beforeClose" :title="t('ingest.addOutput')" width="520px"><ActionFeedback :error="outputError" /><el-form :disabled="actionBusy" label-position="top">
            <FormGrid :columns="2">
              <FormField :label="t('ingest.outputName')" required :error="outputErrors.name">
                <el-input v-model="newOutput.name" :placeholder="t('ingest.addOutputNamePlaceholder')" />
              </FormField>
              <FormField :label="t('ingest.outputType')" :hint="t('ingest.outputTypeHint')">
                <el-select v-model="newOutput.type"><el-option label="GLS_INGEST" value="GLS_INGEST" /><el-option label="OPENSEARCH" value="OPENSEARCH" /><el-option label="HTTP" value="HTTP" /></el-select>
              </FormField>
              <FormField :label="t('ingest.targetUrl')" required :hint="t('ingest.outputUriHint')" :error="outputErrors.uri" full>
                <el-input v-model="newOutput.uri" :placeholder="t('ingest.addOutputUrlPlaceholder')" />
              </FormField>
              <FormField :label="t('ingest.outputAuthToken')" :hint="t('ingest.outputAuthTokenHint')" full>
                <el-input v-model="newOutput.authToken" type="password" show-password :placeholder="t('ingest.outputAuthTokenPlaceholder')" />
              </FormField>
              <FormField :label="t('common.enabled')">
                <el-switch v-model="newOutput.enabled" />
              </FormField>
            </FormGrid>
          </el-form><template #footer><el-button @click="showOutputDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="addOutput">{{ t('ingest.addOutput') }}</el-button></template></el-dialog>
        <el-card shadow="never"><el-table :data="outputs" size="small" border><el-table-column prop="name" :label="t('common.name')" width="180" /><el-table-column prop="type" :label="t('common.type')" width="130" /><el-table-column prop="uri" :label="t('ingest.targetUrl')" min-width="280" show-overflow-tooltip /><el-table-column :label="t('common.enabled')" width="70"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.yes') : t('common.no') }}</el-tag></template></el-table-column><el-table-column v-if="canWrite" :label="t('common.actions')" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeOutput(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('ingest.rulesTab')" name="rules">
        <div style="margin-bottom:12px"><el-button v-if="canWrite" type="primary" @click="router.push({ name: 'parser-new' })">{{ t('ingest.addParseRule') }}</el-button><el-button @click="loadParseRules">{{ t('ingest.refresh') }}</el-button><span style="color:var(--ns-text-3);font-size:12px;margin-left:8px">{{ t('ingest.parserHint') }}</span></div>
        <el-card shadow="never"><el-table :data="parseRules" size="small" border><el-table-column prop="name" :label="t('ingest.ruleName')" width="180" /><el-table-column prop="format" :label="t('ingest.parseFormat')" width="90" /><el-table-column prop="pattern" :label="t('ingest.patternDescription')" min-width="300" show-overflow-tooltip /><el-table-column :label="t('common.enabled')" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.yes') : t('common.no') }}</el-tag></template></el-table-column><el-table-column v-if="canWrite" :label="t('common.actions')" width="70"><template #default="{ row }"><el-button link size="small" @click="router.push({ name: 'parser-edit', params: { parserId: row.id } })">{{ t('common.edit') }}</el-button><el-button link type="danger" size="small" @click="removeParseRule(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="showRender" title="vector.toml" width="720px"><ActionFeedback :error="renderError" /><el-button size="small" type="primary" @click="copyRender">{{ t('common.copy') }}</el-button><pre style="background:var(--ns-bg-subtle);border:1px solid var(--ns-border);border-radius:6px;padding:12px;font-size:12px;overflow:auto;max-height:440px;margin-top:10px">{{ renderText }}</pre></el-dialog>

  </div>
</template>
