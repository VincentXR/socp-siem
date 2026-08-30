<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
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
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { onMounted, ref } from 'vue'
import {
  createOutput, createParseRule, createSource, deleteOutput, deleteParseRule, deleteSource, updateSource,
  ingestSummary, listCategories, listIngestTasks, listOutputs, listParseRules, listSources,
  renderConfig, startIngestTask, stopIngestTask, testIngestTask, previewParse,
  SOURCE_TYPES, PARSE_FORMATS,
  type IngestTask, type IngestSummary, type IngestTestResult, type LogCategory, type LogSource, type LogSourceInput, type ParseRule, type SinkTarget,
} from '../api'
import { useI18n } from '../composables/useI18n'
import { fmtBytes, fmtTime } from '../lib/ui'

const { t } = useI18n()
const ingestTab = ref('tasks')
const sources = ref<LogSource[]>([])
const outputs = ref<SinkTarget[]>([])
const parseRules = ref<ParseRule[]>([])
const logCategories = ref<LogCategory[]>([])
const newSource = ref({ name: '', type: 'FILE', format: 'AUTO', path: '', address: '', topic: '', env: 'local', readFrom: 'beginning', multiline: '', protocol: 'tcp', charset: 'utf-8', timezone: 'Asia/Shanghai', tags: '', frequency: 1, categoryId: '', groupId: '', enabled: true })
const newOutput = ref({ name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true })
const newRule = ref({ name: '', format: 'REGEX', pattern: '', sourceId: '', enabled: true, order: 10 })
const previewLine = ref('Aug 07 01:00:00 web01 sshd[123]: Failed password for admin from 10.0.0.99 port 55006 ssh2')
const previewRuleId = ref('')
const previewResult = ref<{ matched: boolean; fields: Record<string, string>; error?: string } | null>(null)
const renderText = ref('')
const showRender = ref(false)
const showSourceDialog = ref(false)
const showOutputDialog = ref(false)
const showRuleDialog = ref(false)
const editingSourceId = ref<string | null>(null)

const tasks = ref<IngestTask[]>([])
const taskSummary = ref<IngestSummary | null>(null)
const taskBusy = ref<Record<string, boolean>>({})
const testDialog = ref(false)
const testTarget = ref<IngestTask | null>(null)
const testSample = ref('')
const testResult = ref<IngestTestResult | null>(null)
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

async function loadSources() { sources.value = await listSources() }
async function loadOutputs() { outputs.value = await listOutputs() }
async function loadParseRules() { parseRules.value = await listParseRules() }
async function loadTasks() {
  const [taskResult, summaryResult] = await Promise.allSettled([listIngestTasks(), ingestSummary()])
  tasks.value = taskResult.status === 'fulfilled' ? taskResult.value : []
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

function openCreateSource() {
  editingSourceId.value = null
  newSource.value = { name: '', type: 'FILE', format: 'AUTO', path: '', address: '', topic: '', env: 'local', readFrom: 'beginning', multiline: '', protocol: 'tcp', charset: 'utf-8', timezone: 'Asia/Shanghai', tags: '', frequency: 1, categoryId: '', groupId: '', enabled: true }
  showSourceDialog.value = true
}
function openEditSource(source: LogSource) {
  editingSourceId.value = source.id
  newSource.value = {
    name: source.name, type: source.type || 'FILE', format: source.format || 'AUTO',
    path: source.path || '', address: source.address || '', topic: source.topic || '', env: source.env || 'local',
    readFrom: source.readFrom || 'beginning', multiline: source.multiline || '', protocol: source.protocol || 'tcp',
    charset: source.charset || 'utf-8', timezone: source.timezone || 'Asia/Shanghai', tags: (source.tags || []).join(','),
    frequency: source.frequency || 1, categoryId: source.categoryId || '', groupId: source.groupId || '', enabled: source.enabled,
  }
  showSourceDialog.value = true
}
async function saveSource() {
  const source: LogSourceInput = {
    name: newSource.value.name, type: newSource.value.type, format: newSource.value.format,
    env: newSource.value.env, enabled: newSource.value.enabled, readFrom: newSource.value.readFrom,
    protocol: newSource.value.protocol, charset: newSource.value.charset, timezone: newSource.value.timezone,
    frequency: Number(newSource.value.frequency) || 1, groupId: newSource.value.groupId || null,
    categoryId: newSource.value.categoryId || null,
  }
  if (newSource.value.multiline.trim()) source.multiline = newSource.value.multiline.trim()
  if (newSource.value.tags.trim()) source.tags = newSource.value.tags.split(/[,\uFF0C\s]+/).filter(Boolean)
  if (newSource.value.type === 'FILE') source.path = newSource.value.path || 'demo/sample.log'
  if (newSource.value.type === 'SOCKET' || newSource.value.type === 'SYSLOG') source.address = newSource.value.address || '0.0.0.0:5514'
  if (newSource.value.type === 'KAFKA') source.topic = newSource.value.topic || 'socp-raw'
  if (editingSourceId.value) await updateSource(editingSourceId.value, source)
  else await createSource(source)
  editingSourceId.value = null
  showSourceDialog.value = false
  await loadSources()
}
async function removeSource(id: string) {
  if (!confirm(t('ingest.deleteSourceConfirm'))) return
  await deleteSource(id)
  await loadSources()
}
async function doRender() { renderText.value = await renderConfig(); showRender.value = true }
function copyRender() { navigator.clipboard.writeText(renderText.value) }
async function addOutput() {
  await createOutput({ name: newOutput.value.name, type: newOutput.value.type, uri: newOutput.value.uri, authToken: newOutput.value.authToken || null, enabled: newOutput.value.enabled })
  newOutput.value = { name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true }
  showOutputDialog.value = false
  await loadOutputs()
}
async function removeOutput(id: string) {
  if (!confirm(t('ingest.deleteOutputConfirm'))) return
  await deleteOutput(id)
  await loadOutputs()
}
async function addParseRule() {
  await createParseRule({
    name: newRule.value.name, format: newRule.value.format,
    pattern: newRule.value.format === 'REGEX' ? newRule.value.pattern : null,
    sourceId: newRule.value.sourceId || null, enabled: newRule.value.enabled, order: newRule.value.order,
    mapping: [], setFields: [],
  } as Partial<ParseRule>)
  showRuleDialog.value = false
  newRule.value = { name: '', format: 'REGEX', pattern: '', sourceId: '', enabled: true, order: 10 }
  await loadParseRules()
}
async function removeParseRule(id: string) {
  if (!confirm(t('ingest.deleteRuleConfirm'))) return
  await deleteParseRule(id)
  await loadParseRules()
}
async function doPreview() {
  try { previewResult.value = await previewParse({ ruleId: previewRuleId.value || undefined, line: previewLine.value }) }
  catch (error) { previewResult.value = { matched: false, fields: {}, error: error instanceof Error ? error.message : String(error) } }
}

async function toggleTask(task: IngestTask) {
  taskBusy.value = { ...taskBusy.value, [task.id]: true }
  try { task.enabled ? await stopIngestTask(task.id) : await startIngestTask(task.id); await loadTasks() }
  finally { taskBusy.value = { ...taskBusy.value, [task.id]: false } }
}
function openTest(task: IngestTask) { testTarget.value = task; testSample.value = ''; testResult.value = null; testDialog.value = true }
function toggleTaskRow(row: unknown) { toggleTask(row as IngestTask) }
function openTestRow(row: unknown) { openTest(row as IngestTask) }
async function runTest() {
  if (!testTarget.value) return
  testLoading.value = true
  try { testResult.value = await testIngestTask(testTarget.value.id, testSample.value.trim() || undefined); await loadTasks() }
  catch (error) { testResult.value = { ok: false, error: String(error) } }
  finally { testLoading.value = false }
}

onMounted(async () => {
  await Promise.allSettled([loadSources(), loadOutputs(), loadParseRules(), loadTasks(), listCategories().then(result => { logCategories.value = result })])
})
</script>

<template>
  <div class="page-pad view-enter">
    <el-tabs v-model="ingestTab" @tab-change="onIngestTab">
      <el-tab-pane :label="t('ingest.tasks')" name="tasks">
        <el-row :gutter="12" style="margin-bottom:14px">
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ taskSummary?.enabledSources ?? 0 }}/{{ taskSummary?.sources ?? 0 }}</div><div class="label">{{ t('ingest.runningTotal') }}</div></div></el-card></el-col>
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#409eff">{{ taskSummary?.eps1m ?? 0 }}</div><div class="label">{{ t('ingest.eps') }}</div></div></el-card></el-col>
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#67c23a">{{ taskSummary?.accepted ?? 0 }}</div><div class="label">{{ t('ingest.accepted') }}</div></div></el-card></el-col>
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ taskSummary?.forwarded ?? 0 }}</div><div class="label">{{ t('ingest.forwarded') }}</div></div></el-card></el-col>
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" :style="{ color: (taskSummary?.skipped ?? 0) > 0 ? '#e6a23c' : '#909399' }">{{ taskSummary?.skipped ?? 0 }}</div><div class="label">{{ t('ingest.skipped') }}</div></div></el-card></el-col>
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ fmtBytes(taskSummary?.bytes ?? 0) }}</div><div class="label">{{ t('ingest.cumulativeBytes') }}</div></div></el-card></el-col>
        </el-row>
        <el-card shadow="never">
          <template #header><div style="display:flex;align-items:center;gap:10px"><span>{{ t('ingest.taskConfigMetrics') }}</span><el-tag v-for="(count, health) in (taskSummary?.byHealth ?? {})" :key="health" size="small" :type="healthMeta(String(health)).type" style="margin-left:2px">{{ healthMeta(String(health)).text }} {{ count }}</el-tag><el-button size="small" style="margin-left:auto" @click="loadTasks">{{ t('common.refresh') }}</el-button></div></template>
          <el-table :data="tasks" size="small" border>
            <el-table-column :label="t('ingest.status')" width="92"><template #default="{ row }"><el-tag :type="healthMeta(row.runtime.health).type" size="small" effect="dark">{{ healthMeta(row.runtime.health).text }}</el-tag></template></el-table-column>
            <el-table-column :label="t('ingest.task')" min-width="150" show-overflow-tooltip><template #default="{ row }"><div style="font-weight:600">{{ row.name }}</div><div class="mono" style="font-size:11px;color:#909399">{{ row.collector }}</div></template></el-table-column>
            <el-table-column prop="type" :label="t('ingest.ingestMethod')" width="110" />
            <el-table-column prop="format" :label="t('ingest.parseFormat')" width="90" />
            <el-table-column :label="t('ingest.target')" min-width="180" show-overflow-tooltip><template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.target }}</span></template></el-table-column>
            <el-table-column :label="t('ingest.epsWindow')" width="110"><template #default="{ row }"><span :style="{ color: row.runtime.eps1m > 0 ? '#67c23a' : '#c0c4cc', fontWeight: 600 }">{{ row.runtime.eps1m }}</span><span style="color:#c0c4cc"> / {{ row.runtime.eps5m }}</span></template></el-table-column>
            <el-table-column :label="t('ingest.receivedForwardedSkipped')" width="150"><template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.runtime.accepted }} / {{ row.runtime.forwarded }} / <span :style="{ color: row.runtime.skipped > 0 ? '#e6a23c' : 'inherit' }">{{ row.runtime.skipped }}</span></span></template></el-table-column>
            <el-table-column :label="t('ingest.recentData')" width="150"><template #default="{ row }"><span class="mono" style="font-size:12px">{{ fmtTime(row.runtime.lastAt) }}</span></template></el-table-column>
            <el-table-column :label="t('ingest.actions')" width="170"><template #default="{ row }"><el-button link :type="row.enabled ? 'warning' : 'success'" size="small" :loading="taskBusy[row.id]" @click="toggleTaskRow(row)">{{ row.enabled ? t('ingest.stop') : t('ingest.start') }}</el-button><el-button link type="primary" size="small" @click="openTestRow(row)">{{ t('ingest.connectivityTest') }}</el-button></template></el-table-column>
            <el-table-column type="expand"><template #default="{ row }"><div style="padding:8px 20px;font-size:12px;color:#606266"><div>{{ t('ingest.environmentDetail', { value: row.env || t('time.notAvailable') }) }} · {{ t('ingest.categoryDetail', { value: row.categoryId || t('time.notAvailable') }) }} · {{ t('ingest.outputDetail', { value: row.sinkTargetId || t('ingest.disabledDefault') }) }} · {{ t('ingest.createdDetail', { value: fmtTime(row.createdAt) }) }}</div><div style="margin-top:4px">{{ t('ingest.boundRules') }}<el-tag v-for="p in row.parseRuleIds" :key="p" size="small" style="margin-right:4px">{{ p }}</el-tag><span v-if="!row.parseRuleIds?.length" style="color:#c0c4cc">{{ t('ingest.autoDetect') }}</span></div><div v-if="row.runtime.lastError" style="margin-top:4px;color:#f56c6c">{{ t('ingest.recentError', { time: fmtTime(row.runtime.lastErrorAt ?? null) }) }}{{ row.runtime.lastError }}</div></div></template></el-table-column>
          </el-table>
        </el-card>
        <el-dialog v-model="testDialog" :title="t('ingest.testTitle', { name: testTarget?.name ?? '' })" width="680px">
          <div style="font-size:12px;color:#909399;margin-bottom:8px">{{ t('ingest.testDescription') }}</div>
          <el-input v-model="testSample" type="textarea" :rows="4" :placeholder="t('ingest.testSamplePlaceholder')" />
          <div v-if="testResult" style="margin-top:12px"><el-alert :type="testResult.ok ? 'success' : 'error'" :closable="false" :title="t(testResult.ok ? 'ingest.testPassed' : 'ingest.testFailed')" /><pre class="mono test-out">{{ JSON.stringify(testResult, null, 2) }}</pre></div>
          <template #footer><el-button @click="testDialog = false">{{ t('ingest.close') }}</el-button><el-button type="primary" :loading="testLoading" @click="runTest">{{ t('ingest.runTest') }}</el-button></template>
        </el-dialog>
      </el-tab-pane>

      <el-tab-pane :label="t('ingest.sourcesTab')" name="sources">
        <div class="add-bar"><el-button type="primary" @click="openCreateSource">+ {{ t('ingest.addSource') }}</el-button><el-button @click="loadSources">{{ t('ingest.refresh') }}</el-button><el-button type="primary" plain @click="doRender">{{ t('ingest.renderConfig') }}</el-button><span class="hint">{{ t('ingest.sourceHint') }}</span></div>
        <el-dialog v-model="showSourceDialog" :title="editingSourceId ? t('ingest.editSource') : t('ingest.addSource')" width="640px">
          <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px"><el-input v-model="newSource.name" :placeholder="t('ingest.sourceNamePlaceholder')" /><el-select v-model="newSource.type" :placeholder="t('ingest.ingestMethodPlaceholder')"><el-option v-for="type in SOURCE_TYPES" :key="type" :label="type" :value="type" /></el-select><el-select v-model="newSource.format" :placeholder="t('ingest.parseFormatPlaceholder')"><el-option v-for="format in PARSE_FORMATS" :key="format" :label="format" :value="format" /></el-select><el-select v-model="newSource.categoryId" :placeholder="t('ingest.categoryPlaceholder')" clearable><el-option v-for="category in logCategories" :key="category.id" :label="category.code + ' ' + category.name" :value="category.id" /></el-select><el-input v-model="newSource.env" :placeholder="t('ingest.environmentPlaceholder')" /></div>
          <div v-if="newSource.type === 'FILE'" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px"><el-input v-model="newSource.path" :placeholder="t('ingest.filePathPlaceholder')" /><el-select v-model="newSource.readFrom"><el-option :label="t('ingest.readFromBeginning')" value="beginning" /><el-option :label="t('ingest.readFromEnd')" value="end" /></el-select><el-input v-model.number="newSource.frequency" :placeholder="t('ingest.frequencyPlaceholder')" /></div>
          <div v-else-if="newSource.type === 'SOCKET' || newSource.type === 'SYSLOG'" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px"><el-input v-model="newSource.address" :placeholder="t('ingest.listenAddressPlaceholder')" /><el-select v-model="newSource.protocol" :placeholder="t('ingest.protocol')"><el-option label="UDP" value="udp" /><el-option label="TCP" value="tcp" /><el-option label="TLS" value="tls" /></el-select></div>
          <div v-else-if="newSource.type === 'KAFKA'" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px"><el-input v-model="newSource.topic" :placeholder="t('ingest.topicPlaceholder')" /><el-input v-model="newSource.groupId" :placeholder="t('ingest.groupIdPlaceholder')" /></div>
          <div v-else-if="['WINDOWS_EVENT', 'AGENT', 'HTTP_API', 'DATABASE', 'CLOUD'].includes(newSource.type)" style="margin-top:10px"><el-alert type="info" :closable="false" :title="t('ingest.collectorInfo', { type: newSource.type })" /></div>
          <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px"><el-select v-model="newSource.charset" :placeholder="t('ingest.charsetPlaceholder')"><el-option label="UTF-8" value="utf-8" /><el-option label="GBK" value="gbk" /><el-option label="ISO-8859-1" value="iso-8859-1" /></el-select><el-select v-model="newSource.timezone" :placeholder="t('ingest.timezonePlaceholder')"><el-option label="Asia/Shanghai" value="Asia/Shanghai" /><el-option label="UTC" value="UTC" /><el-option label="Asia/Tokyo" value="Asia/Tokyo" /></el-select><el-input v-model="newSource.tags" :placeholder="t('ingest.tagsPlaceholder')" /></div>
          <template #footer><el-switch v-model="newSource.enabled" :active-text="t('common.enabled')" style="margin-right:12px" /><el-button @click="showSourceDialog = false">{{ t('common.cancel') }}</el-button><el-button type="success" @click="saveSource">{{ editingSourceId ? t('common.save') : t('ingest.addSource') }}</el-button></template>
        </el-dialog>
        <el-card shadow="never"><el-table :data="sources" size="small" border><el-table-column prop="name" :label="t('common.name')" width="130" show-overflow-tooltip /><el-table-column prop="type" :label="t('common.type')" width="110" /><el-table-column prop="format" :label="t('ingest.parseFormat')" width="80" /><el-table-column :label="t('ingest.target')" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.path || row.address || row.topic || t('time.notAvailable') }}</template></el-table-column><el-table-column :label="t('ingest.protocol')" width="70"><template #default="{ row }">{{ row.protocol || t('time.notAvailable') }}</template></el-table-column><el-table-column prop="env" :label="t('ingest.environment')" width="65" /><el-table-column :label="t('common.enabled')" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.yes') : t('common.no') }}</el-tag></template></el-table-column><el-table-column :label="t('common.actions')" width="120"><template #default="{ row }"><el-button link type="primary" size="small" @click="openEditSource(row as LogSource)">{{ t('common.edit') }}</el-button><el-button link type="danger" size="small" @click="removeSource(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('ingest.outputTab')" name="outputs">
        <div class="add-bar"><el-button type="primary" @click="showOutputDialog = true">+ {{ t('ingest.addOutput') }}</el-button><span class="hint">{{ t('ingest.outputHint') }}</span></div>
        <el-dialog v-model="showOutputDialog" :title="t('ingest.addOutput')" width="560px"><el-form label-width="80px"><el-form-item :label="t('ingest.outputName')"><el-input v-model="newOutput.name" :placeholder="t('ingest.addOutputNamePlaceholder')" /></el-form-item><el-form-item :label="t('ingest.outputType')"><el-select v-model="newOutput.type" style="width:200px"><el-option label="GLS_INGEST" value="GLS_INGEST" /><el-option label="OPENSEARCH" value="OPENSEARCH" /><el-option label="HTTP" value="HTTP" /></el-select></el-form-item><el-form-item :label="t('ingest.targetUrl')"><el-input v-model="newOutput.uri" :placeholder="t('ingest.addOutputUrlPlaceholder')" /></el-form-item><el-form-item :label="t('common.enabled')"><el-switch v-model="newOutput.enabled" :active-text="t('common.enabled')" /></el-form-item></el-form><template #footer><el-button @click="showOutputDialog = false">{{ t('common.cancel') }}</el-button><el-button type="success" @click="addOutput">{{ t('ingest.addOutput') }}</el-button></template></el-dialog>
        <el-card shadow="never"><el-table :data="outputs" size="small" border><el-table-column prop="name" :label="t('common.name')" width="180" /><el-table-column prop="type" :label="t('common.type')" width="130" /><el-table-column prop="uri" :label="t('ingest.targetUrl')" min-width="280" show-overflow-tooltip /><el-table-column :label="t('common.enabled')" width="70"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.yes') : t('common.no') }}</el-tag></template></el-table-column><el-table-column :label="t('common.actions')" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeOutput(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('ingest.rulesTab')" name="rules">
        <div style="margin-bottom:12px"><el-button type="primary" @click="showRuleDialog = true">{{ t('ingest.addParseRule') }}</el-button><el-button @click="loadParseRules">{{ t('ingest.refresh') }}</el-button><span style="color:#909399;font-size:12px;margin-left:8px">{{ t('ingest.parserHint') }}</span></div>
        <el-card shadow="never"><el-table :data="parseRules" size="small" border><el-table-column prop="name" :label="t('ingest.ruleName')" width="180" /><el-table-column prop="format" :label="t('ingest.parseFormat')" width="90" /><el-table-column prop="pattern" :label="t('ingest.patternDescription')" min-width="300" show-overflow-tooltip /><el-table-column :label="t('common.enabled')" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.yes') : t('common.no') }}</el-tag></template></el-table-column><el-table-column :label="t('common.actions')" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeParseRule(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table></el-card>
        <el-card shadow="never" style="margin-top:14px"><template #header>{{ t('ingest.previewTitle') }}</template><div style="display:flex;gap:10px;margin-bottom:10px"><el-select v-model="previewRuleId" :placeholder="t('ingest.selectRule')" clearable style="width:260px"><el-option v-for="rule in parseRules" :key="rule.id" :label="rule.name" :value="rule.id" /></el-select><el-button type="primary" @click="doPreview">{{ t('ingest.preview') }}</el-button></div><el-input v-model="previewLine" type="textarea" :rows="2" :placeholder="t('ingest.sampleLog')" /><div v-if="previewResult" style="margin-top:12px;background:var(--ns-bg-subtle);border-radius:6px;padding:12px"><p style="margin:0 0 6px">{{ t('ingest.result') }} <el-tag :type="previewResult.matched ? 'success' : 'danger'" size="small">{{ previewResult.matched ? t('ingest.matched') : t('ingest.notMatched') }}</el-tag><span v-if="previewResult.error" style="color:#f56c6c;margin-left:8px">{{ previewResult.error }}</span></p><pre class="mono" style="margin:0;font-size:12px">{{ JSON.stringify(previewResult.fields, null, 2) }}</pre></div></el-card>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="showRender" title="vector.toml" width="720px"><el-button size="small" type="primary" @click="copyRender">{{ t('common.copy') }}</el-button><pre style="background:var(--ns-bg-subtle);border:1px solid var(--ns-border);border-radius:6px;padding:12px;font-size:12px;overflow:auto;max-height:440px;margin-top:10px">{{ renderText }}</pre></el-dialog>
    <el-dialog v-model="showRuleDialog" :title="t('ingest.addParseRule')" width="560px"><el-form label-width="90px"><el-form-item :label="t('common.name')"><el-input v-model="newRule.name" placeholder="SSHD" /></el-form-item><el-form-item :label="t('ingest.parseFormat')"><el-select v-model="newRule.format" style="width:200px"><el-option v-for="format in ['REGEX', 'JSON', 'KV', 'SYSLOG', 'CEF', 'LEEF']" :key="format" :label="format" :value="format" /></el-select></el-form-item><el-form-item v-if="newRule.format === 'REGEX'" :label="t('ingest.patternDescription')"><el-input v-model="newRule.pattern" type="textarea" :rows="3" :placeholder="t('ingest.patternPlaceholder')" /></el-form-item><el-form-item :label="t('ingest.sourceScopePlaceholder')"><el-input v-model="newRule.sourceId" :placeholder="t('ingest.sourceScopePlaceholder')" /></el-form-item><el-form-item :label="t('common.enabled')"><el-switch v-model="newRule.enabled" /></el-form-item></el-form><template #footer><el-button @click="showRuleDialog = false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="addParseRule">{{ t('common.save') }}</el-button></template></el-dialog>
  </div>
</template>
